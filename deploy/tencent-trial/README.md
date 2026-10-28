# 腾讯云试用机部署包（NexusSky pre-staging / 公网演示）

> **目标机型**：腾讯云 CVM **2核2G3M 1个月免费试用**档（或同量级小机，含 2G swap 后余量够用）。
> **定位**：给客户/联调方看的**公网可达 pre-staging**——完整前端 + 后端 + 一只模拟无人机；
> 不作为生产。生产化路径见 `deploy/helm`、`deploy/k8s`。
> **原则**：试用机**不做任何构建**——三个应用镜像直接拉 `ghcr.io`（CI 在 master 推送时发布，
> 匿名可拉，2026-10-28 实测）。

## 架构（单 VM 一览）

```
浏览器 ──:80──▶ gcs-web(nginx, 静态 SPA + /api /ws /actuator 反代)
                     │
                     ▼
             cloud-backend(:8080, prod profile, 真鉴权)
                     │                 ▲
        SQL/JDBC     │                 │ MAVLink over UDP
                     ▼                 │
         postgres(:5432) + redis(:6379) │
                                       │
        drone-sim(:14540) ◀────────────┘
        （后端主动发 GCS 心跳到 sim，sim 从入站帧学会回程——
          两边都是"回给最后说话的人"，bridge 网络内自动双向打通）
```

## 四步上机

### 0. 领机（腾讯云控制台，你的账号操作）

- 规格：CVM **2核2G3M**；镜像：**Ubuntu 24.04 LTS**（22.04 亦可）；
- **安全组入站**：只放行 `TCP 22`（SSH，建议源 IP 限制为你自己）+ `TCP 80`（Web）。
  其余端口**一个都不要开**（理由见「安全边界」）；
- 记下公网 IP；root 密码或 SSH 密钥按控制台流程设置。

### 1. 传部署包（在开发机，Git Bash）

```bash
scp -r deploy/tencent-trial root@<公网IP>:/root/nexussky-trial
```

（也可以先 `scp` 到任意目录；部署包只有脚本和配置，不含镜像。）

### 2. 宿主准备（VM 上，root）

```bash
cd /root/nexussky-trial
bash bootstrap-vm.sh
```

做三件事（幂等，可重复跑）：建 2G swap（2G 物理机的兜底）+ 装 Docker（含 compose v2）+
写 `/etc/docker/daemon.json`（腾讯云内网镜像源 `mirror.ccs.tencentyun.com`，拉 postgres/redis 基础镜像快很多）。

### 3. 起栈

```bash
bash deploy.sh
```

自动完成：生成随机密钥写 `.env`（**并把管理端密码打印一次，请立即保存**）→ 拉镜像
（ghcr 三个应用镜像 + postgres/redis 基础镜像，3Mbps 下行首次约 **15–30 分钟**，之后重部署零成本）
→ `up -d` → 轮询 `/actuator/health` 直到通过 → 打印访问地址。

## 验证清单（部署完成的判定）

```bash
# 1) 健康
curl -s http://<公网IP>/actuator/health        # 期望 {"status":"UP",...}

# 2) 前端可开
curl -s -o /dev/null -w '%{http_code}\n' http://<公网IP>/    # 期望 200

# 3) 登录 + 看机队（密码在上面打印过，也在 .env 的 AEROFLEET_USERS 行）
TOKEN=$(curl -s -X POST http://<公网IP>/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<你的密码>"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s http://<公网IP>/api/v1/drones -H "Authorization: Bearer $TOKEN"
# 期望看到 sysid=1 的设备 online=true，且 latency 几秒内位置在变化（模拟无人机在飞）
```

浏览器打开 `http://<公网IP>`：登录后设备页应看到 **Drone-1** 在线（**STANDBY 待命、位置静止是正常的**
——sim 出厂就是停机待命，跟真机落地后一样；想让它飞见下节）。

### 演示飞行（可选的"活数据"脚本，已实测）

部署脚本已自动把 Drone-1 登记进白名单（prod 口径：陌生 sysid 的帧会被
`Rejected frame from unregistered sysid` 丢弃——这就是登记那一步的原因）。
想让它在客户面前飞一条 4 点环线（起飞→2 航点→自动返航降落）：

```bash
# 把 <你的密码> 换成部署时打印的密码
TOKEN=$(curl -s -X POST http://<公网IP>/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<你的密码>"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

curl -s -X POST http://<公网IP>/api/v1/drones/1/mission \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"items":[
    {"cmd":"takeoff","lat":22.5907,"lon":113.9345,"alt":30},
    {"cmd":"waypoint","lat":22.5916,"lon":113.9345,"alt":30},
    {"cmd":"waypoint","lat":22.5916,"lon":113.9354,"alt":30},
    {"cmd":"rtl","lat":0,"lon":0,"alt":0}]}'
# 期望 {"uploaded":4,"status":"ok"}

curl -s -X POST http://<公网IP>/api/v1/drones/1/commands -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"type":"arm"}'
curl -s -X POST http://<公网IP>/api/v1/drones/1/commands -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"type":"start_mission"}'
```

约 1 秒后控制台里该机 `mode=MISSION`、高度爬升到 30m，飞完两点自动 RTL 返航降落
（实测：t+8s 爬到 ~22m；末点后进 RTL 直线回家，落地自动 disarm）。

## 资源账（2G 物理机上的刻意瘦身）

| 服务 | 内存 limit | 本地冒烟实测 RSS | 说明 |
|------|-----------|----------------|------|
| cloud-backend | 896M | 431M | JVM 显式 `-Xmx640m` + SerialGC（覆盖镜像默认的 G1/75%RAM——按容器限额算堆在 2G 机上太贪） |
| postgres | 384M | 52M | `shared_buffers=128MB`、`max_connections=60` |
| drone-sim | 384M | 77M | JVM `-Xmx256m` |
| redis | 128M | 3.7M | `--maxmemory 96mb`，纯缓存、关持久化 |
| gcs-web (nginx) | 96M | 25M | 静态 + 反代 |
| **五容器合计** | ~1.9G 上限 | **~589M** | 宿主 + Docker 另算，2G swap 兜底 |

余量充足——但 2C2G 只有 2 个 vCPU，**别再往这台机上装别的东西**（后端冷启动会先吃满核跑 Flyway+上下文）。

## 常用操作

```bash
bash deploy.sh status     # 容器状态与健康
bash deploy.sh logs       # 全栈日志（tail -f）
bash deploy.sh down       # 停栈（数据保留）
bash deploy.sh purge      # 停栈 + 删数据卷（到期清理用）
```

## 排障

| 症状 | 处理 |
|------|------|
| ghcr 拉镜像超慢/卡死 | 3Mbps 下 ~15–30 分钟属正常。彻底拉不动 → **Plan B（开发机传镜像）**：开发机上 `docker pull` 三个 ghcr 镜像后 `docker save ghcr.io/levango7/nexussky/cloud-backend:latest ... \| gzip > trial-images.tgz`，`scp` 上机 `gunzip -c trial-images.tgz \| docker load`，再 `bash deploy.sh`（跳过拉取可手动 `docker compose up -d`） |
| `deploy.sh` 报缺 openssl | `apt-get install -y openssl` 后重跑 |
| 脚本报 `\r` / `$'\r'` 之类怪错 | 换行符被 Windows 工具碰过：`sed -i 's/\r$//' *.sh` 后重跑（本包入库为 LF，scp 直传不会发生） |
| 80 端口被占 | 改 `.env` 的 `WEB_PORT=8080`（安全组同步放行该端口） |
| health 一直 503 | 多为 redis 未就绪；`bash deploy.sh logs` 看 `redis`/`cloud-backend`；本编排已用 healthcheck + depends_on 防这个，若仍出现请贴日志 |
| 后端起不来 | 多半是 `.env` 缺项——compose 对密钥是 **fail-fast**（缺/短于 32 字符直接拒绝启动），日志里会点名变量 |
| 日志/磁盘 | 所有服务已配 json-file 轮转（5–10MB×2–3 份）；磁盘紧张另跑 `docker image prune` |

## 安全边界（请照做）

- **对外只开 80**。`8080`（后端直连口）、`5432`、`6379`、`14550/udp` 都**不在宿主机发布**，
  只在容器网络里互通；
- **不要**把 MAVLink UDP（14550/14540）开到公网——那是给无人机发指令的通道，裸暴露等于把机队交给任何人；
- prod profile 的密钥是 fail-fast 的，`.env` 权限 600 且已 gitignore——不要把它复制进任何仓库/聊天记录；
- 试用期只跑模拟无人机；**真机联调不要做在这台公网机上**（真机用内网/SITL 本机路径，见
  `docs/sitl-integration.md`）。

## 到期与清理（重要，防"忘记的实例"）

- 免费试用到期后实例会被**回收（数据全清）**，一般不会自动扣费；**不要开自动续费**，
  到期前若想留数据：`docker compose exec postgres pg_dump -U aerofleet aerofleet_prod > backup.sql` 先拉走；
- 不用了就在控制台**销毁实例**（顺便释放弹性 IP/安全组引用）；机上可先 `bash deploy.sh purge`；
- 想继续用：试用续费折扣（卡片上写的 3.5 折）是留存手段，按需决定，别顺手开。

## 冒烟记录（2026-10-28，本地 Docker Desktop，本包不经改动直接用）

- **首启全流程一把过**：`down -v` 清空后直接 `bash deploy.sh` → 后端 healthy → gcs-web 起 →
  健康检查通过 → 自动登记 Drone-1（HTTP 201）→ 机队 `sysid=1 online=true`。
- 修掉三个实测暴露的问题（都在本包/相关 compose 里落了修正与注释）：
  1. **prod 模式登录 401**：DB 模式下登录走 `UserRepository`，`AEROFLEET_USERS` 只是无库降级，
     而库里种子 admin 已停用（V20）——正路是 `bootstrap-admin-password` 引导。
     `deploy.sh` 生成 .env 时两处同值；**顺带修了 `deploy/docker/docker-compose.yml` 与根
     `docker-compose.yml` 的同一缺口**（二者此前照样登录不了，compose-smoke 只探 health 没暴露）。
  2. **MAVLink 发现的"鸡生蛋"**：sim 若 `depends_on` 后端 healthy，则后端启动时 `drone-sim`
     还不存在、未解析地址会被记死（容器后来起了也不恢复）。改为 sim 与后端并行启动（sim 本来
     就只等对方先说话），实测首启零人工干预打通。
  3. **`sh -c` 吞参 + compose `$` 插值**（两处）：镜像 ENTRYPOINT 是 `sh -c "java $JAVA_OPTS ..."`，
     用 `command:` 传参会变成 sh 的位置参数（实测 /proc/1/cmdline）；compose 又会在解析期吃掉
     单个 `$`。最终形态 = `entrypoint: ["sh","-c","exec java $$JAVA_OPTS -jar app.jar --sysid=1 --port=14540"]`，
     实测进程参数为展开后的 `-Xms128m -Xmx256m ... --sysid=1 --port=14540`。
- 演示飞行脚本实测：上传 4 点环线 `uploaded=4 ok` → arm/start ACCEPTED →
  t+8s 高度 ~22m（MISSION）→ 末点后 RTL 回家 → 落地自动 disarm。

## 设计出处

- 基线 = `deploy/docker/docker-compose.yml`（通用生产编排）：prod 口径环境变量**全保留**
  （fail-fast 密钥、Flyway、JPA validate、MAVLink 签名开关语义），差异仅三处：
  ① 资源限额/JVM 参数按 2G 机重算；② 镜像改为 ghcr 直拉（机上不构建）；
  ③ drone-sim 的启动命令修正为整串覆盖（`sh -c` 的 ENTRYPOINT 会吞掉 `command: ["java",...]`
  形式的位置参数——见 compose 内注释）。
- 镜像来源：CI `Docker Build & Push` job（master 推送时发 `:latest` 与 `:sha-xxxxxxx`）。
  想钉版本：`.env` 的 `IMAGE_TAG=sha-xxxxxxx`。
