# NexusSky Python SDK

NexusSky Python SDK 是 NexusSky 无人机智能飞控中间件的 Python 客户端 SDK，封装了无人机管理、遥测查询、飞行命令下发、航点任务上传、编队操作和飞行日志查询等核心 API，内部使用 `requests` 库，自动添加 `X-API-Key` 认证头。

## 安装

```bash
pip install aerofleet-sdk
```

### 环境要求

- Python 3.8 或更高版本
- 依赖：requests >= 2.28.0（自动安装）

## 快速开始

### 1. 创建客户端

```python
from aerofleet_sdk import NexusSkyClient

# 默认强制 HTTPS
client = NexusSkyClient("https://cloud.example.com", "your-api-key")

# 开发环境允许 HTTP
client = NexusSkyClient("http://localhost:8080", "your-api-key", allow_insecure_http=True)
```

### 2. 设备管理

```python
# 获取所有无人机列表
drones = client.get_drones()

# 获取单个无人机详情
drone = client.get_drone(1)

# 获取遥测数据
telemetry = client.get_telemetry(1)
```

### 3. 任务上传

```python
from aerofleet_sdk import Mission, Waypoint

# 方式一：使用 Mission 对象
mission = Mission(waypoints=[
    Waypoint(lat=39.9042, lon=116.4074, alt=50),
    Waypoint(lat=39.9050, lon=116.4080, alt=60),
    Waypoint(lat=39.9060, lon=116.4090, alt=40),
])
client.missions.upload(1, mission)

# 方式二：使用 Waypoint 列表
client.missions.upload(1, [
    Waypoint(lat=39.9042, lon=116.4074, alt=50),
    Waypoint(lat=39.9050, lon=116.4080, alt=60),
])
```

### 4. 飞行命令

```python
# 解锁无人机
client.send_command(1, "arm")

# 起飞（指定高度，单位：米）
client.send_command(1, "takeoff", alt=30)

# 返航
client.send_command(1, "rtl")

# 开始任务
client.send_command(1, "start_mission")
```

## 核心 API 参考

### NexusSkyClient

主入口类，提供直接 API 调用和 API 模块属性。

#### 构造参数

```python
NexusSkyClient(base_url, api_key, timeout=30, allow_insecure_http=False)
```

| 参数 | 类型 | 说明 | 默认值 |
|---|---|---|---|
| `base_url` | `str` | 后端服务基础地址 | 必填 |
| `api_key` | `str` | API 密钥，用于 `X-API-Key` 认证头 | 必填 |
| `timeout` | `int` | 请求超时时间（秒） | `30` |
| `allow_insecure_http` | `bool` | 是否允许 HTTP（非 HTTPS） | `False` |

#### 直接调用方法

| 方法 | 说明 | 返回类型 |
|---|---|---|
| `get_drones()` | 获取所有无人机列表 | `list` |
| `get_drone(sysid)` | 获取单个无人机详情 | `dict` |
| `get_telemetry(sysid)` | 获取无人机遥测数据 | `dict` |
| `send_command(sysid, command, alt=0)` | 发送飞行命令 | `dict` |
| `get_formations()` | 获取所有编队列表 | `list` |
| `get_formation(formation_id)` | 查询单个编队状态 | `dict` |
| `create_formation(members, shape, ...)` | 创建编队 | `dict` |
| `command_formation(formation_id, command_type, alt=0)` | 向编队下发命令 | `dict` |
| `transition_formation(formation_id, new_shape, steps)` | 队形平滑变换 | `dict` |
| `dissolve_formation(formation_id)` | 解散编队 | `dict` |

#### API 模块属性

| 属性 | 返回类型 | 说明 |
|---|---|---|
| `drones` | `DroneApi` | 无人机 API 模块（懒加载） |
| `missions` | `MissionApi` | 任务 API 模块（懒加载） |
| `flight_logs` | `FlightLogApi` | 飞行日志 API 模块（懒加载） |

### DroneApi

设备管理 API，通过 `client.drones` 获取实例。

| 方法 | 说明 | 参数 |
|---|---|---|
| `list()` | 获取所有无人机列表 | - |
| `get(sysid)` | 获取单个无人机详情 | `sysid`：无人机系统 ID |
| `telemetry(sysid)` | 获取无人机遥测数据 | `sysid`：无人机系统 ID |
| `arm(sysid)` | 解锁无人机 | `sysid`：无人机系统 ID |
| `takeoff(sysid, alt)` | 起飞命令 | `sysid`：无人机系统 ID，`alt`：目标高度（米） |
| `rtl(sysid)` | 返航命令 | `sysid`：无人机系统 ID |
| `start_mission(sysid)` | 开始任务命令 | `sysid`：无人机系统 ID |

### MissionApi

任务管理 API，通过 `client.missions` 获取实例。

| 方法 | 说明 | 参数 |
|---|---|---|
| `upload(sysid, mission)` | 上传航点任务 | `sysid`：无人机系统 ID，`mission`：`Mission` 对象或 `Waypoint` 列表 |
| `download(sysid)` | 下载航点任务 | `sysid`：无人机系统 ID |
| `clear(sysid)` | 清除航点任务 | `sysid`：无人机系统 ID |

#### Waypoint 类

`Waypoint` 是航点数据对象，使用 `@dataclass` 定义。

```python
from aerofleet_sdk import Waypoint

wp = Waypoint(lat=39.9042, lon=116.4074, alt=50)
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `lat` | `float` | 纬度 |
| `lon` | `float` | 经度 |
| `alt` | `float` | 高度（米） |

`Waypoint` 提供 `to_dict()` 方法，返回 `{"lat": ..., "lon": ..., "alt": ...}`。

#### Mission 类

`Mission` 是任务对象，包含航点列表，使用 `@dataclass` 定义。

```python
from aerofleet_sdk import Mission, Waypoint

mission = Mission(waypoints=[
    Waypoint(lat=39.9042, lon=116.4074, alt=50),
    Waypoint(lat=39.9050, lon=116.4080, alt=60),
])
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `waypoints` | `List[Waypoint]` | 航点列表 |

`Mission` 提供 `to_dict()` 和 `from_dict(data)` 方法，支持序列化与反序列化。

### FlightLogApi

飞行日志 API，通过 `client.flight_logs` 获取实例。

| 方法 | 说明 | 参数 |
|---|---|---|
| `list()` | 查询飞行日志列表 | - |
| `get(log_id)` | 获取指定飞行日志详情 | `log_id`：日志 ID |

## 安全说明

SDK **默认强制 HTTPS**。如果 `base_url` 不是 `https://` 开头，构造时将抛出 `ValueError`。

在本地开发或测试环境中，如需使用 HTTP，可设置 `allow_insecure_http=True`：

```python
client = NexusSkyClient("http://localhost:8080", "your-api-key", allow_insecure_http=True)
```

> **警告**：`allow_insecure_http=True` 仅应在可信的本地开发环境中使用，生产环境必须使用 HTTPS。

所有请求自动携带 `X-API-Key` 认证头，无需手动设置。

## 错误处理

SDK 提供层次化的异常类体系，所有异常继承自 `SdkException`。

```python
from aerofleet_sdk import (
    SdkException,
    AuthenticationError,
    NotFoundError,
    BadRequestError,
    ServerError,
)

try:
    drones = client.get_drones()
except AuthenticationError as e:
    print(f"认证失败: {e} (HTTP {e.status_code})")
except NotFoundError as e:
    print(f"资源不存在: {e}")
except BadRequestError as e:
    print(f"请求参数错误: {e}")
except ServerError as e:
    print(f"服务器错误: {e} (HTTP {e.status_code})")
except SdkException as e:
    print(f"其他错误: {e} (HTTP {e.status_code})")
```

| 异常类 | 对应 HTTP 状态码 | 说明 |
|---|---|---|
| `BadRequestError` | 400 | 请求参数错误 |
| `AuthenticationError` | 401 / 403 | 认证失败（API Key 无效或权限不足） |
| `NotFoundError` | 404 | 资源不存在 |
| `ServerError` | >= 500 | 服务器内部错误 |
| `SdkException` | -1 或其他 | 基类，涵盖所有其他错误（网络中断、JSON 解析失败等） |

所有异常均包含 `status_code` 属性（`-1` 表示非 HTTP 错误）和 `cause` 属性（底层异常）。

## 线程安全说明

> **重要**：`NexusSkyClient` 实例**不可跨线程共享**。

内部使用的 `requests.Session` 不是线程安全的，共享实例可能导致请求异常或数据错乱。每个线程应创建独立的 `NexusSkyClient` 实例：

```python
import threading

def worker(base_url, api_key):
    # 每个线程创建自己的 client 实例
    client = NexusSkyClient(base_url, api_key)
    drones = client.get_drones()
    print(f"线程 {threading.current_thread().name}: {len(drones)} 架无人机")

threads = []
for i in range(3):
    t = threading.Thread(target=worker, args=("https://cloud.example.com", "your-api-key"))
    threads.append(t)
    t.start()

for t in threads:
    t.join()
```

## 完整使用示例

```python
from aerofleet_sdk import (
    NexusSkyClient,
    Mission,
    Waypoint,
    SdkException,
    AuthenticationError,
)

# 1. 创建客户端
client = NexusSkyClient("https://cloud.example.com", "your-api-key")

try:
    # 2. 查询无人机列表
    drones = client.get_drones()
    print(f"在线无人机数量: {len(drones)}")

    # 3. 查询遥测数据
    telemetry = client.get_telemetry(1)
    print(f"遥测: {telemetry}")

    # 4. 上传航点任务
    mission = Mission(waypoints=[
        Waypoint(lat=39.9042, lon=116.4074, alt=50),
        Waypoint(lat=39.9050, lon=116.4080, alt=60),
        Waypoint(lat=39.9060, lon=116.4090, alt=40),
    ])
    client.missions.upload(1, mission)
    print("任务上传完成")

    # 5. 解锁并起飞
    client.send_command(1, "arm")
    client.send_command(1, "takeoff", alt=30)

    # 6. 开始执行任务
    client.send_command(1, "start_mission")

    # 7. 查询飞行日志
    logs = client.flight_logs.list()
    print(f"飞行日志数量: {len(logs)}")

    # 8. 返航
    client.send_command(1, "rtl")

except AuthenticationError as e:
    print(f"认证失败，请检查 API Key: {e}")
except SdkException as e:
    print(f"API 调用失败: {e} (HTTP {e.status_code})")
```