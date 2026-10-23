# 配置项

Boot 的 `rpc.*` 属性与纯 Java builder 一一对应；没写的项取 core 的缺省值（缺省值只在 core 定义一份）。
下面各表的"属性"列是 Boot 的属性名（客户端、服务端两表省略了 `rpc.client.` / `rpc.server.` 前缀），"builder"列是纯 Java 的对应方法。

## 目录

- [通用](#通用)
- [客户端 `rpc.client.*`](#客户端-rpcclient)（[引用级选项](#引用级选项)）
- [服务端 `rpc.server.*`](#服务端-rpcserver)
- [Spring Boot 专属](#spring-boot-专属)
- [端口](#端口)

## 通用

| 属性 | builder | 缺省 | 说明 |
|---|---|---|---|
| `rpc.registry.type` | `registry(RegistryConfig)` | `zookeeper` | 注册中心 SPI 名：`zookeeper` / `nacos` |
| `rpc.registry.address` | 同上 | `localhost:2181` | 注册中心地址 |
| `rpc.registry.properties.*` | `RegistryConfig.withProperty(k, v)` | — | 实现相关：ZK 的 `session-timeout-ms`（30000）、`connection-timeout-ms`（5000）、`connect-wait-ms`（1000）、`push-merge-ms`（50）；Nacos 的 `namespace`、`username`、`password`、`connect-wait-ms`（3000） |
| `rpc.serializer` | `serializer(String)` | `protobuf`（SPI 缺省名） | 客户端首选 / 服务端兜底的序列化方式；缺省值需要 `rpc-serialize-protobuf` 在 classpath 上，只带 fastjson2 时设为 `fastjson2` |
| `rpc.serializer-options.*` | `serializerOption(k, v)` | — | 交给序列化器，如 `protobuf.allowed-packages: "com.acme.api.,com.acme.dto."`（多态位置按名加载的包白名单） |

## 客户端 `rpc.client.*`

对应 `RpcClient.builder()`。

| 属性 | builder | 缺省 | 说明 |
|---|---|---|---|
| `load-balance` | `loadBalance` | `roundrobin` | `roundrobin` / `p2c` |
| `cluster` | `cluster` | `failfast` | `failfast` / `failover` / `failback`；只在引用声明了 `retries > 0` 时重试 |
| `connections-per-node` | `connectionsPerNode` | 2 | 到每个提供者的连接数 |
| `default-timeout` | `defaultTimeout` | 20s | 一次调用（含重试）的总预算；`@RpcReference(timeout=)` / `ReferenceOptions.timeout` 覆盖 |
| `empty-protection` | `emptyProtection` | true | 推空保护 |
| `shutdown-timeout` | `shutdownTimeout` | 10s | close 时等在途调用的上限 |
| `exception-allowlist` | `exceptionAllowlist(String...)` | — | 追加允许按类名还原的异常包前缀 |
| `io-threads` | `ioThreads` | 0 | IO 事件循环线程数；0 = Netty 缺省（2 × 可用核数），只影响本客户端 |
| `sync-wait-grace` | `syncWaitGrace` | 1s | 同步调用等待的第二道上界：最多等 deadline 剩余时间 + 该值，定时器没按时触发时也不会无限挂住；异步方法不受约束 |
| `flush-consolidation` | `flushConsolidation` | true | 连接 pipeline 首位加 Netty 的 `FlushConsolidationHandler`（合并 flush，不改协议字节、不加定时器）；关掉只用于对照实验 |
| `write-buffer-low` / `write-buffer-high` | `writeBufferWaterMark(low, high)` | 32KB / 64KB | 每条连接的写缓冲水位线（Netty `WRITE_BUFFER_WATER_MARK`，缺省即 Netty 缺省；支持 `64KB` 写法，纯数字按字节）。待写字节超过 high 时连接不可写，同一节点有多条连接时优先选可写的 |
| `outbound-hard-limit` | `outboundHardLimit` | high × 16 | 一条连接不可写且待写字节超过它时，新请求不写出、以"未发出"的传输失败结束（failover 且 `retries > 0` 时换节点）；0 = 关闭 |
| `outlier-detection.enabled` | `outlierDetection(OutlierDetectionConfig)` | true | 离群摘除开关（`OutlierDetectionConfig.disabled()` 关闭）。坏结果 = 本地超时、`DEADLINE_EXCEEDED`、传输失败（含"未发出"）、`SERVER_OVERLOADED`；业务异常与其它服务端错误不算 |
| `outlier-detection.min-requests` / `failure-ratio` | 同上（`OutlierDetectionConfig.builder()`） | 20 / 0.5 | 比例条件：最近 10 秒（10 个 1 秒桶）内尝试 ≥ min-requests 且坏结果占比 ≥ failure-ratio 时摘除 |
| `outlier-detection.consecutive-failures` | 同上 | 5 | 连续条件：连续这么多个坏结果时摘除；0 = 不用这个条件 |
| `outlier-detection.base-ejection` / `max-ejection` | 同上 | 10s / 60s | 摘除时长 = base × 该地址累计被摘次数，上限 max；到期自动回到候选集（不做半开探测、不做主动健康检查）；放回后每连续健康满一个 max，累计次数减 1（最低 0，长期健康后下一次摘除回到 base） |
| `outlier-detection.max-ejection-percent` | 同上 | 50 | 最多摘除的提供者比例：摘掉之后健康的至少保留 `ceil(n × (100 − p) / 100)` 个、且至少 1 个；万一候选全在摘除期，退回全部候选 |
| — | `clientFilter(Filter...)` | — | Boot 中为容器里的 `ClientFilter` bean |

`outlier-detection.*`（离群摘除）在 1.0.0 之后的 main 上加入，1.0.0 没有这几项；机制与 A/B 实验见
[probes/outlier-detection.md](probes/outlier-detection.md)，边界见 [getting-started.md「已知限制」](getting-started.md#离群摘除只按失败按时间放回)。

### 引用级选项

引用级选项（`@RpcReference` / `ReferenceOptions`）：`version`、`group`、`timeout`（毫秒）、`retries`（缺省 0）、`inheritDeadline`（缺省 true）。

## 服务端 `rpc.server.*`

对应 `RpcServer.builder()`。

| 属性 | builder | 缺省 | 说明 |
|---|---|---|---|
| `port` | `port` | 9000 | 0 = 随机端口，注册实际端口 |
| `host` | `host` | 自动探测 | 注册给消费端的地址；多网卡 / 容器里请显式设置 |
| `weight` | `weight` | 1 | 注册权重；`@RpcService(weight=)` 覆盖 |
| `shutdown-timeout` | `shutdownTimeout` | 10s | 停机时等在途请求的上限 |
| `deregister-grace` | `deregisterGrace` | 0 | 摘注册后、拒新请求前的宽限期；**生产建议 1s** |
| `remote-stack-trace` | `remoteStackTrace` | true | 异常回包是否带服务端堆栈 |
| `remote-stack-trace-max-bytes` | `remoteStackTraceMaxBytes` | 8192 | 回传堆栈上限（最小 256） |
| `io-threads` | `ioThreads` | 0 | IO 事件循环（worker）线程数；0 = Netty 缺省（2 × 可用核数）。业务方法在 executor 上执行，与它无关 |
| `flush-consolidation` | `flushConsolidation` | true | 同客户端：业务线程并发回包时多个回包合成一次写出；关掉只用于对照实验 |
| `write-buffer-low` / `write-buffer-high` | `writeBufferWaterMark(low, high)` | 32KB / 64KB | 每条已接受连接的写缓冲水位线（同客户端）：回包待写字节超过 high 时连接不可写 |
| `outbound-backpressure` | `outboundBackpressure` | true | 出站背压：连接不可写时暂停读它、恢复可写再读，不读回包的客户端不能让回包在直接内存里无界堆积；暂停读超过读空闲 30s 的连接被关闭。关掉只用于对照实验（docs/probes/outbound-backpressure.md） |
| `outbound-hard-limit` | `outboundHardLimit` | high × 16 | 连接待写字节超过它时，新到的请求直接回 `SERVER_OVERLOADED`（业务不执行）；0 = 关闭，只在背压开时生效 |
| `executor.type` | `executor(ExecutorConfig)` | `fixed` | `fixed` / `virtual` / `direct`（opt-in：业务方法在 IO 线程上执行。**业务方法阻塞会卡住同一事件循环上的所有连接，仅适用于纯 CPU、微秒级的方法；与 gRPC directExecutor 同类**） |
| `executor.threads` | `ExecutorConfig.fixed(threads, queue)` | CPU 核数 × 2 | 固定池线程数 |
| `executor.queue-capacity` | 同上 | 1000 | 固定池排队上限，满了回 `SERVER_OVERLOADED` |
| `executor.max-concurrent` | `ExecutorConfig.virtualThreads(max)` / `ExecutorConfig.direct(max)` | 10000 | 虚拟线程同时执行上限 / direct 的在途请求上限（超出回 `SERVER_OVERLOADED`） |
| — | `serverFilter(Filter...)` | — | Boot 中为容器里的 `ServerFilter` bean |

## Spring Boot 专属

`RpcClientCustomizer` / `RpcServerCustomizer` bean 在属性与 Filter bean 之后修改 builder；
`management.health.rpc.enabled`（有 Actuator 时，缺省 true）：健康组件 `rpc`，已启动且已注册为 UP，未注册、注册丢失（ZooKeeper 会话过期，节点尚未重建；Nacos 不报告）或停机中为 OUT_OF_SERVICE。
停机最长耗时约 `deregister-grace + shutdown-timeout + 5s`，`spring.lifecycle.timeout-per-shutdown-phase`（Boot 缺省 30s）必须大于它。

## 端口

- **RPC 服务端**：`rpc.server.port` / `port(int)`，缺省 9000；0 = 随机端口，注册中心里登记的是实际绑定的端口。注册给消费端的地址由 `host` 决定（缺省自动探测）。
- **客户端**不监听端口。
- **rpc-examples**：Boot provider 9000（`provider.yml`），纯 Java `PlainProvider` 9100；`PlainProvider --metrics-port <p>` 另开内嵌 Prometheus `/metrics` 端点（[rpc-examples/README.md](../rpc-examples/README.md#运行)）。
- **本机中间件与压测进程**：ZooKeeper、Nacos、Prometheus、Grafana、toxiproxy 的宿主机端口，以及 Prometheus 预置抓取的 `rpc-bench-server` / `rpc-bench-client` / 示例应用的指标端口，见 [docker/README.md「端口」](../docker/README.md#端口)；指标端点本身见 [observability.md](observability.md#内嵌-metrics-端点)。
