# 性能基准与决策记录

> 本文件记录“**先测量、再决定改不改**”的性能评估。结论可能与直觉相反——
> 那正是基准的价值：防止用风险换不存在的问题。

## P1：UDP 接收零分配（帧解码对象池化）—— **已评估，不改造**

### 背景

ROADMAP 原条目：「P1 UDP 接收线程零分配：帧解码对象池化 + GC 抖动消除」。
直觉上“每帧都 new 对象”听起来就该池化。但做之前先量。

### 基准设计（可复现）

`mavlink-core/src/test/java/io/aerofleet/mavlink/ParseAllocationBenchmark.java`
（**不进 CI**：默认 `assumeTrue(-Dbench=1)` 跳过，不给 CI 加时长）：

```bash
mvn test -pl mavlink-core -Dtest=ParseAllocationBenchmark -Dbench=1 \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

测量点：解析吞吐、每帧堆分配（`com.sun.management.ThreadMXBean`
线程分配计量）、单帧最坏耗时；预热 20 万帧后测 200 万帧。

### 实测数字（本机，JDK 17 Temurin，2026-10-12）

| 指标 | 数值 |
|---|---|
| 解析吞吐 | **2,132,196 帧/秒**（200 万帧耗时 938ms） |
| 每帧堆分配 | **192 bytes/帧** |
| 单帧最坏解析耗时 | **2.4 μs** |
| 参考真实负载（遥测 5Hz × 9 机） | **45 帧/秒** |
| 可预见扩展负载（50 机 × 50Hz） | 2,500 帧/秒 |

### 结论：**改造风险 > 收益，不做**

- **吞吐余量**：213 万 vs 45 帧/秒 = **约 4.7 万倍**；即使按 50 机 × 50Hz 的
  可预见上限（2,500 帧/秒）也还有 **850 倍余量**。单帧 2.4μs × 2,500 =
  0.6% 单核占用。
- **分配量**：192 B/帧 × 2,500 帧/秒 = **480 KB/秒**——对 JVM 年轻代 GC
  是可忽略量级（WebSocket 推送一帧的序列化分配都远大于此）。
- **改造的真实代价**：payload 会逃逸给**异步**的 WS 推送
  （`TelemetryPusher` 的 `@Scheduled` 才序列化），直接池化 frame/payload
  等于把“复用”变成“数据竞态”。唯一正解是把 `MavlinkFrame` 改成零拷贝窗口
  （payload 指向共享 buf + offset/len，解码时由调用方复制）——那会改变
  `MavlinkFrame` 的公共 API 语义，波及全部 payload 消费方
  （cloud-backend / link-sim / regulator-sim / 全部既有测试），
  为一个不存在的问题付这笔账不成立。

### 什么情况下要重开本条

- 实测负载达到 **10 万帧/秒** 量级（当前余量的 2000 倍以上）；
- 或 GC 日志在满载遥测场景出现可观测的 young GC 停顿（需先复现，再立项）。

### 附注：`receiveLoop` 已经是“每包零分配”

`UdpMavlinkTransport.receiveLoop` 的 `byte[2048]` 与 `DatagramPacket`
均在循环外只分配一次、逐包复用（`setLength` + `receive` + `ByteBuffer.wrap`
堆内零分配）。也就是说传输层在更早的轮次已优化过；本条要动的只剩
parser 里每帧的三个对象（payload 数组 + MavlinkFrame + ParseResult），
即上文数字所描述的部分。
