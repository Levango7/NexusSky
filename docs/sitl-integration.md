# PX4 SITL 接入指南（真飞控固件 × AeroFleet 云端）

> SITL（Software In The Loop）：把 PX4 **真实飞控固件**编译成 PC 程序跑起来，
> 传感器数据由仿真提供。它说的 MAVLink、跑的任务状态机与真机完全一致。
> 后端/地面站零改动——这正是"替换数据源，不替换架构"的验证时刻。

## 架构

```
[PX4 SITL (WSL2)] --MAVLink/UDP 14556--> [cloud-backend (Windows) 14550]
       |                                        |
   (px4io/仿真传感器)                    REST/WS -> gcs-web
```

PX4 SITL 默认对外发多路 MAVLink（14540..14549），其中 **14556 是"支持命令的 remote GCS"端口**。

## 步骤（一次性搭建）

```bash
# 1) WSL2 Ubuntu-24.04 内：
cd ~
git clone --depth 1 --branch v1.17.0 https://github.com/PX4/PX4-Autopilot.git
cd PX4-Autopilot
bash Tools/setup/ubuntu.sh            # 构建依赖（约 10 分钟）
make px4_sitl_default                 # 编译（首次约 20-40 分钟）

# 2) 无头模式启动（不弹 jMAVSim 窗口），对外监听 UDP：
PX4_SIM_HOST=127.0.0.1 PX4_SYSID=1 ./build/px4_sitl_default/bin/px4 -d /dev/null \
  -s etc/init/posix-airframes/rcS_posix 2>&1 | tee sitl.log
# SITL 起来后自动向 127.0.0.1:14550..14556 发 MAVLink

# 3) Windows 后端（已运行）。SITL 的包从 WSL 到达 Windows localhost —— WSL2 的
#    localhost 与 Windows 双向互通（默认 networkingMode=NAT）。
```

## 与后端的对接差异（已做好的兼容层）

| 差异点 | PX4 行为 | AeroFleet 兼容层 |
|---|---|---|
| 任务拉取请求 | 回 `MISSION_REQUEST`(43)（不只 51） | PendingAcks 双消息 ID 登记 waiter |
| MISSION_COUNT 目标 | 拒绝 broadcast(0) | `targetSystem()` 从注册表取真实 sysid |
| MISSION_START(300) | 部分版本 UNSUPPORTED | 回退 `DO_SET_MODE`(176) auto 模式 |
| 心跳 custom_mode | PX4 nav_state 枚举 | `px4NavStateLabel()` 已映射 |

## 已知边界

- SITL 传感器仿真里 GPS 信号依赖 `SYS_FORCE_VTYP`/EKF，解锁前可能需要虚拟 GPS 健康
  （`COM_ARM_WO_GPS` 参数），首次联调时按 STATUSTEXT 提示调参。
- PX4 需要先收到 GCS 心跳才向 14556 端口回包 —— 后端 discovery 机制已兼容。
- 多机 SITL（不同 SYSID/端口）由 `PX4_SIM_HOST`/`-t` 参数控制，骨架阶段单机验证。

## 验证清单（SITL 联调 PASS 标准）

- [ ] 设备上线（PX4 心跳被识别为 Drone-1，mode=MANUAL→ Standby）
- [ ] 任务上传（PX4 用 MISSION_REQUEST 拉取，双 waiter 生效）
- [ ] ARM → PX4 状态机进 ARMED（遥测 armed=true）
- [ ] 开始任务 → PX4 进 MISSION 模式（custom_mode=3），飞机沿航点飞
- [ ] RTL → PX4 返航降落
- [ ] 轨迹 200 点留存
