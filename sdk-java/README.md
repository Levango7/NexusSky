# NexusSky Java SDK

NexusSky Java SDK 是 NexusSky 无人机智能飞控中间件的 Java 客户端 SDK，封装了无人机管理、遥测查询、飞行命令下发、航点任务上传、编队操作和飞行日志查询等核心 API，内部使用 JDK 11+ 内置 `HttpClient`，自动添加 `X-API-Key` 认证头。

## 安装

### Maven

```xml
<dependency>
    <groupId>io.aerofleet</groupId>
    <artifactId>nexussky-sdk-java</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.aerofleet:nexussky-sdk-java:1.0.0'
```

### 环境要求

- Java 11 或更高版本
- 依赖：Jackson Databind 2.16.1（自动引入）

## 快速开始

### 1. 创建客户端

```java
import io.aerofleet.sdk.NexusSkyClient;

// 方式一：直接构造（默认强制 HTTPS）
NexusSkyClient client = new NexusSkyClient("https://cloud.example.com", "your-api-key");

// 方式二：Builder 模式
NexusSkyClient client = NexusSkyClient.builder()
        .baseUrl("https://cloud.example.com")
        .apiKey("your-api-key")
        .build();
```

### 2. 设备管理

```java
// 获取所有无人机列表
List<Map<String, Object>> drones = client.getDrones();

// 获取单个无人机详情
Map<String, Object> drone = client.getDrone(1);

// 获取遥测数据
Map<String, Object> telemetry = client.getTelemetry(1);
```

### 3. 任务上传

```java
import io.aerofleet.sdk.mission.MissionApi;
import io.aerofleet.sdk.mission.MissionApi.Waypoint;

// 通过 MissionApi 上传航点任务
MissionApi missions = client.missions();
List<Waypoint> waypoints = List.of(
        new Waypoint(39.9042, 116.4074, 50),
        new Waypoint(39.9050, 116.4080, 60),
        new Waypoint(39.9060, 116.4090, 40)
);
ApiResponse response = missions.upload(1, waypoints);
```

### 4. 飞行命令

```java
// 解锁无人机
client.sendCommand(1, "arm");

// 起飞（指定高度，单位：米）
client.sendCommand(1, "takeoff", 30);

// 返航
client.sendCommand(1, "rtl");

// 开始任务
client.sendCommand(1, "start_mission");
```

## Builder 模式

`NexusSkyClient` 支持 Builder 模式进行链式配置：

```java
NexusSkyClient client = NexusSkyClient.builder()
        .baseUrl("https://cloud.example.com")
        .apiKey("your-api-key")
        .allowInsecureHttp(false)  // 默认 false，强制 HTTPS
        .build();
```

| Builder 方法 | 说明 | 默认值 |
|---|---|---|
| `baseUrl(String)` | 后端服务基础地址 | 必填 |
| `apiKey(String)` | API 密钥，用于 `X-API-Key` 认证头 | 必填 |
| `allowInsecureHttp(boolean)` | 是否允许 HTTP（非 HTTPS） | `false` |

## 核心 API 参考

### NexusSkyClient

主入口类，提供直接 API 调用和 API 模块工厂方法。

#### 直接调用方法

| 方法 | 说明 | 返回类型 |
|---|---|---|
| `getDrones()` | 获取所有无人机列表 | `List<Map<String, Object>>` |
| `getDrone(int sysid)` | 获取单个无人机详情 | `Map<String, Object>` |
| `getTelemetry(int sysid)` | 获取无人机遥测数据 | `Map<String, Object>` |
| `sendCommand(int sysid, String command)` | 发送飞行命令 | `Map<String, Object>` |
| `sendCommand(int sysid, String command, double alt)` | 发送飞行命令（带高度参数） | `Map<String, Object>` |
| `getFormations()` | 获取所有编队列表 | `List<Map<String, Object>>` |
| `getFormation(int formationId)` | 查询单个编队状态 | `Map<String, Object>` |
| `createFormation(...)` | 创建编队 | `Map<String, Object>` |
| `commandFormation(int formationId, String type, double alt)` | 向编队下发命令 | `Map<String, Object>` |
| `transitionFormation(int formationId, String newShape, int steps)` | 队形平滑变换 | `Map<String, Object>` |
| `dissolveFormation(int formationId)` | 解散编队 | `Map<String, Object>` |

#### API 模块工厂方法

| 方法 | 返回类型 | 说明 |
|---|---|---|
| `drones()` | `DroneApi` | 无人机 API 模块 |
| `missions()` | `MissionApi` | 任务 API 模块 |
| `flightLogs()` | `FlightLogApi` | 飞行日志 API 模块 |

### DroneApi

设备管理 API，通过 `client.drones()` 获取实例。

| 方法 | 说明 | 参数 |
|---|---|---|
| `list()` | 获取所有无人机列表 | - |
| `get(int sysid)` | 获取单个无人机详情 | `sysid`：无人机系统 ID |
| `telemetry(int sysid)` | 获取无人机遥测数据 | `sysid`：无人机系统 ID |
| `arm(int sysid)` | 解锁无人机 | `sysid`：无人机系统 ID |
| `takeoff(int sysid, double alt)` | 起飞命令 | `sysid`：无人机系统 ID，`alt`：目标高度（米） |
| `rtl(int sysid)` | 返航命令 | `sysid`：无人机系统 ID |
| `startMission(int sysid)` | 开始任务命令 | `sysid`：无人机系统 ID |

所有方法返回 `ApiResponse` 对象，可通过 `getDataAsMap()`、`getDataAsList()` 等方法获取数据。

### MissionApi

任务管理 API，通过 `client.missions()` 获取实例。

| 方法 | 说明 | 参数 |
|---|---|---|
| `upload(int sysid, List<Waypoint> waypoints)` | 上传航点任务 | `sysid`：无人机系统 ID，`waypoints`：航点列表 |
| `download(int sysid)` | 下载航点任务 | `sysid`：无人机系统 ID |
| `clear(int sysid)` | 清除航点任务 | `sysid`：无人机系统 ID |

#### Waypoint 类

`MissionApi.Waypoint` 是航点数据对象，包含纬度、经度、高度。

```java
Waypoint wp = new Waypoint(39.9042, 116.4074, 50);
```

| 字段 | 类型 | 有效范围 |
|---|---|---|
| `lat` | `double` | [-90, 90] |
| `lon` | `double` | [-180, 180] |
| `alt` | `double` | >= 0（单位：米） |

### FlightLogApi

飞行日志 API，通过 `client.flightLogs()` 获取实例。

| 方法 | 说明 | 参数 |
|---|---|---|
| `list()` | 查询飞行日志列表 | - |
| `get(String logId)` | 获取指定飞行日志详情 | `logId`：日志 ID |

### ApiResponse

通用 API 响应包装类，解析后端返回的 JSON 结构。

```json
{"status": "ok", "data": {...}}
```

| 方法 | 说明 | 返回类型 |
|---|---|---|
| `getStatus()` | 响应状态（"ok" 或 "error"） | `String` |
| `isOk()` | 判断是否成功 | `boolean` |
| `getData()` | 响应数据体 | `Object` |
| `getDataAsMap()` | 数据体作为 Map | `Map<String, Object>` |
| `getDataAsList()` | 数据体作为 List | `List<Object>` |
| `get(String key)` | 从数据体获取指定字段 | `Object` |
| `getString(String key)` | 获取字段并转为字符串 | `String` |
| `getInt(String key)` | 获取字段并转为整数 | `int` |

## 安全说明

SDK **默认强制 HTTPS**。如果 `baseUrl` 不是 `https://` 开头，构造时将抛出 `IllegalArgumentException`。

在本地开发或测试环境中，如需使用 HTTP，可通过 Builder 开启：

```java
NexusSkyClient client = NexusSkyClient.builder()
        .baseUrl("http://localhost:8080")
        .apiKey("your-api-key")
        .allowInsecureHttp(true)
        .build();
```

> **警告**：`allowInsecureHttp(true)` 仅应在可信的本地开发环境中使用，生产环境必须使用 HTTPS。

所有请求自动携带 `X-API-Key` 认证头，无需手动设置。

## 错误处理

SDK 通过 `SdkException`（继承 `RuntimeException`）封装所有 API 调用中可能出现的错误。

```java
import io.aerofleet.sdk.SdkException;

try {
    List<Map<String, Object>> drones = client.getDrones();
} catch (SdkException e) {
    System.err.println("错误: " + e.getMessage());
    System.err.println("HTTP 状态码: " + e.getStatusCode());
}
```

| `getStatusCode()` 返回值 | 说明 |
|---|---|
| `200-299` | 不会抛出异常 |
| `400` | 请求参数错误 |
| `401/403` | 认证失败（API Key 无效或权限不足） |
| `404` | 资源不存在 |
| `>= 500` | 服务器内部错误 |
| `-1` | 非 HTTP 错误（网络中断、JSON 解析失败等） |

## 完整使用示例

```java
import io.aerofleet.sdk.NexusSkyClient;
import io.aerofleet.sdk.ApiResponse;
import io.aerofleet.sdk.SdkException;
import io.aerofleet.sdk.mission.MissionApi;
import io.aerofleet.sdk.mission.MissionApi.Waypoint;
import io.aerofleet.sdk.flightlog.FlightLogApi;

import java.util.List;
import java.util.Map;

public class NexusSkyExample {

    public static void main(String[] args) {
        // 1. 创建客户端
        NexusSkyClient client = NexusSkyClient.builder()
                .baseUrl("https://cloud.example.com")
                .apiKey("your-api-key")
                .build();

        try {
            // 2. 查询无人机列表
            List<Map<String, Object>> drones = client.getDrones();
            System.out.println("在线无人机数量: " + drones.size());

            // 3. 查询遥测数据
            Map<String, Object> telemetry = client.getTelemetry(1);
            System.out.println("遥测: " + telemetry);

            // 4. 上传航点任务
            MissionApi missions = client.missions();
            List<Waypoint> waypoints = List.of(
                    new Waypoint(39.9042, 116.4074, 50),
                    new Waypoint(39.9050, 116.4080, 60),
                    new Waypoint(39.9060, 116.4090, 40)
            );
            ApiResponse uploadResp = missions.upload(1, waypoints);
            System.out.println("任务上传: " + uploadResp.getStatus());

            // 5. 解锁并起飞
            client.sendCommand(1, "arm");
            client.sendCommand(1, "takeoff", 30);

            // 6. 开始执行任务
            client.sendCommand(1, "start_mission");

            // 7. 查询飞行日志
            FlightLogApi flightLogs = client.flightLogs();
            ApiResponse logList = flightLogs.list();
            System.out.println("飞行日志: " + logList.getDataAsList());

            // 8. 返航
            client.sendCommand(1, "rtl");

        } catch (SdkException e) {
            System.err.println("API 调用失败: " + e.getMessage());
            if (e.getStatusCode() != -1) {
                System.err.println("HTTP 状态码: " + e.getStatusCode());
            }
        }
    }
}
```