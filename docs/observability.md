# 可观测性：指标

`steadyrpc-metrics-micrometer` 把每次调用记进 [Micrometer](https://micrometer.io)，可以导出到任何 Micrometer 支持的后端；本仓库的
[docker/](../docker/README.md) 预置了 Prometheus + Grafana 与一块面板。steadyrpc-core 不因此多出任何依赖：指标是两个普通的
`ClientFilter` / `ServerFilter` 加几个 Gauge，不用时不在调用路径上。

## 目录

- [接入](#接入)：Spring Boot · 纯 Java · 内嵌 /metrics 端点
- [指标清单](#指标清单)
- [status 取值](#status-取值)
- [计时口径与 Filter 位置](#计时口径与-filter-位置)
- [服务端指标看不到的部分](#服务端指标看不到的部分)
- [PromQL 示例](#promql-示例)
- [Grafana 面板](#grafana-面板)
- [开销与压测](#开销与压测)

## 接入

### Spring Boot

依赖 `steadyrpc-metrics-micrometer`、`spring-boot-starter-actuator`、`micrometer-registry-prometheus`（见
[接入指南 7.1](getting-started.md#71-指标)），并把 `prometheus` 加进 `management.endpoints.web.exposure.include`。

starter 的 `RpcMetricsAutoConfiguration` 在三个条件同时满足时生效：classpath 上有 Micrometer 与 `steadyrpc-metrics-micrometer`；容器里有
`MeterRegistry` bean；`rpc.metrics.enabled` 不为 `false`（缺省 `true`）。它做两件事：

- 注册 `MicrometerClientFilter` / `MicrometerServerFilter` 两个 bean，走 starter 现有的"Filter bean 自动接入"进入两条链；
- RpcClient / RpcServer bean 初始化后绑定在途与连接数 Gauge，容器关闭时解绑。RpcClient 是懒加载的，没有任何 `@RpcReference` 时
  客户端 Gauge 不存在。

starter 对 Micrometer 与指标模块都是 optional 依赖，不会强加给不需要指标的应用。

### 纯 Java

```java
MeterRegistry registry = ...;                       // 任何 MeterRegistry
RpcServer server = RpcServer.builder()
        .serverFilter(new MicrometerServerFilter(registry))
        .build();
RpcServerMetrics.bind(server, registry);             // rpc.server.inflight / rpc.server.connections / 出站背压计数

RpcClient client = RpcClient.builder()
        .clientFilter(new MicrometerClientFilter(registry))
        .build();
RpcClientMetrics.bind(client, registry);             // rpc.client.inflight / rpc.client.connections
```

`bind` 返回的对象 `close()` 时把 Gauge 从 registry 移除。Gauge 以弱引用持有客户端 / 服务端（Micrometer 缺省），不会让一个已经不用的
实例无法回收（回收后读数为 NaN）；实例 `close()` 之后读数为 0。同一个 registry 绑定多个客户端（或多个服务端）时，用
`bind(x, registry, Tags.of("client", "orders"))` 加区分 tag——Micrometer 对同名同 tag 的 Gauge 只注册第一次，后绑的会被静默忽略。

完整可运行的例子是 `steadyrpc-examples` 的 `PlainProvider`（`--metrics-port <p>`）。

### 内嵌 /metrics 端点

没有 Web 框架的进程用 `PrometheusEndpoint`：JDK 自带的 `com.sun.net.httpserver.HttpServer` 在 `GET /metrics` 上返回 Prometheus 文本格式
（`text/plain; version=0.0.4`），其它路径 404。

```java
MeterRegistry registry = PrometheusEndpoint.newRegistry();        // PrometheusMeterRegistry，以 MeterRegistry 类型返回
PrometheusEndpoint endpoint = new PrometheusEndpoint(9464, registry);  // 全部网卡；只要本机访问时用 (host, port, registry)
...
endpoint.close();
```

- 需要 `io.micrometer:micrometer-registry-prometheus`：指标模块对它是 optional 依赖，用到端点的进程自己声明。缺失时
  `newRegistry()` 与构造器抛 `IllegalStateException`，消息里写明要加哪个依赖（不是 `NoClassDefFoundError`）；
  `PrometheusEndpoint.isAvailable()` 可以先判断。
- registry 必须是 `PrometheusMeterRegistry`，或包含它的 `CompositeMeterRegistry`；否则抛 `IllegalArgumentException`。
- 请求由一个守护线程 `rpc-metrics-http-<port>` 处理，`close()` 停止监听并结束它。
- 缺省监听全部网卡，因为容器里的 Prometheus 经 `host.docker.internal` 访问宿主机；端点没有鉴权，不要在不可信网络上开放该端口。

Spring Boot 应用用 Actuator 的 `/actuator/prometheus`，不需要它。

## 指标清单

Micrometer 名（点分）在 Prometheus 里转成下划线，Timer 带 `_seconds` 单位后缀并展开成 `_bucket` / `_count` / `_sum` / `_max`。

| Micrometer 名 | Prometheus 名 | 类型 | tag | 含义 |
|---|---|---|---|---|
| `rpc.client.calls` | `rpc_client_calls_seconds_{bucket,count,sum,max}` | Timer（百分位直方图） | `service`、`method`、`status` | 客户端一次调用的耗时与结果：从最外层客户端 Filter 到调用完成（含 Cluster 的全部重试） |
| `rpc.server.calls` | `rpc_server_calls_seconds_{bucket,count,sum,max}` | Timer（百分位直方图） | `service`、`method`、`status` | 服务端一个请求的耗时与结果：从最外层服务端 Filter 到 Filter 链完成 |
| `rpc.client.inflight` | `rpc_client_inflight` | Gauge | 绑定时给的 tag | 经代理发起、尚未完成的调用数（含重试中的），`RpcClient.inFlightCalls()` |
| `rpc.client.connections` | `rpc_client_connections` | Gauge | 绑定时给的 tag | 到全部提供者已激活的连接数（含下线后排空中的），`RpcClient.activeConnections()` |
| `rpc.server.inflight` | `rpc_server_inflight` | Gauge | 绑定时给的 tag | 已进入服务端（排队或执行中，含业务返回了未完成 future 的）、尚未回包的请求数，`RpcServer.inFlight()` |
| `rpc.server.connections` | `rpc_server_connections` | Gauge | 绑定时给的 tag | 已接受、尚未关闭的入站连接数，`RpcServer.activeConnections()` |
| `rpc.server.autoread.pauses` | `rpc_server_autoread_pauses_total` | FunctionCounter | 绑定时给的 tag | 出站背压因连接不可写（待写字节超过高水位）而暂停读的累计次数，`RpcServer.autoReadPauses()`；稳态下持续增长说明有客户端读回包跟不上（[docs/probes/outbound-backpressure.md](probes/outbound-backpressure.md)） |
| `rpc.server.outbound.overload.rejections` | `rpc_server_outbound_overload_rejections_total` | FunctionCounter | 绑定时给的 tag | 连接待写字节超过出站硬上限而直接回 SERVER_OVERLOADED 的累计请求数，`RpcServer.outboundOverloadRejections()` |
| `rpc.server.executor.rejections` | `rpc_server_executor_rejections_total` | FunctionCounter | 绑定时给的 tag | 业务执行器过载（固定池队列满 / 虚拟线程或 direct 的并发达到上限）而直接回 SERVER_OVERLOADED 的累计请求数（业务没执行），`RpcServer.executorRejections()`；不含停机拒绝 |
| `rpc.server.shutdown.rejections` | `rpc_server_shutdown_rejections_total` | FunctionCounter | 绑定时给的 tag | 优雅停机期间（DRAINING 阶段在途闸门已关、或业务执行器已关闭）回 SERVER_OVERLOADED（"server shutting down"）的累计请求数，`RpcServer.shutdownRejections()` |
| `rpc.client.send.buffer.rejections` | `rpc_client_send_buffer_rejections_total` | FunctionCounter | 绑定时给的 tag | 连接待写字节超过出站硬上限、没有写出就以传输失败结束的累计请求数，`RpcClient.sendBufferRejections()` |
| `rpc.client.unwritable.switches` | `rpc_client_unwritable_switches_total` | FunctionCounter | 绑定时给的 tag | 取连接时跳过不可写连接、改用同一节点另一条连接的累计次数，`RpcClient.unwritableConnectionSwitches()` |
| `rpc.client.outlier.ejections` | `rpc_client_outlier_ejections_total` | FunctionCounter | 绑定时给的 tag | 离群摘除把提供者摘出负载均衡候选的累计次数（全部服务之和），`RpcClient.outlierEjections()`；摘除时另有一行 WARN 日志（地址、服务、原因、时长） |
| `rpc.client.outlier.restores` | `rpc_client_outlier_restores_total` | FunctionCounter | 绑定时给的 tag | 摘除到期、提供者回到候选集的累计次数，`RpcClient.outlierRestores()`（到期后第一次选点或读取观测时计；注册中心推送移除的不算）；回归时有一行 INFO 日志 |
| `rpc.client.outlier.ejected` | `rpc_client_outlier_ejected` | Gauge | 绑定时给的 tag | 此刻处于摘除期的提供者地址数，`RpcClient.ejectedNodes().size()`（`ejectedNodes()` 另给出每个地址的到期时刻） |

tag 约定：

- `service`：**接口全限定名**（如 `io.github.sisyfyzhang.steadyrpc.examples.api.InventoryService`）。选全名而不是简名：不同包里的同名接口不会合并成
  一条序列；代价是图例长一些。`version` / `group` 不进 tag（同一接口的多个版本合并统计）。
- `method`：方法名。重载方法合并为一条（参数签名不进 tag，避免把类型名带进 tag 值）。
- `status`：有界取值，见下节。**不使用异常类名**——业务异常类型无界，会让时间序列数失控。

Timer 开了 `publishPercentileHistogram()`，桶边界用 `minimumExpectedValue` / `maximumExpectedValue` 截断：客户端 100µs ~ 10s（每条序列 76 个
有限桶 + `+Inf`）；服务端 1µs ~ 10s（105 个有限桶 + `+Inf`）——轻量方法的服务端耗时只有几微秒，下界取 100µs 时几乎全部样本落进第一个桶，
p99 无法分辨（本机 echo 冒烟中约 99.9% 的服务端样本小于 100µs）。
分位数在 Prometheus 端用 `histogram_quantile` 算，可以跨实例、跨方法聚合（客户端预先算好的分位数不能相加）。

## status 取值

| 取值 | 客户端 | 服务端 |
|---|---|---|
| `OK` | 成功 | 成功 |
| `BIZ_EXCEPTION` | 业务方法抛出的异常（已按原类型还原，或降级为 `RpcRemoteException` 且线上状态为 BIZ_EXCEPTION） | 业务方法抛出的非框架异常 |
| `SERVER_ERROR` | 服务端回 SERVER_ERROR（降级为 `RpcRemoteException` 时） | 业务方法抛出的 `RpcException`（多半是它的下游调用失败）或 `Error`；Filter 链异常完成 |
| `DEADLINE_EXCEEDED` | 服务端判定请求到达时已过期；或重试前本次调用的预算已耗尽、不再发送 | `DeadlineServerFilter` 拒绝了过期请求（业务未执行） |
| `TIMEOUT` | 本端在截止时间前没等到响应（客户端独有） | — |
| `SERVER_OVERLOADED` | 服务端拒绝（满载 / 停机中），业务未执行 | Filter 以 `RpcOverloadedException` 拒绝（业务未执行） |
| `SERIALIZATION_ERROR` | 参数编码 / 返回值解码失败，或服务端回 SERIALIZATION_ERROR | 参数反序列化失败（业务未执行） |
| `TRANSPORT_FAILURE` | 没拿到服务端的答复：连不上、写失败、无可用提供者、连接中途断开（客户端独有） | — |
| `BAD_REQUEST`、`SERVICE_NOT_FOUND` | 不出现：core 把这两种回包还原为基类 `RpcException`，客户端记 `SERVER_ERROR` | 不出现：这类请求不进 Filter 链（见下文） |

说明：

- 客户端 `SERVER_OVERLOADED` 在 core 里走的是"传输失败"通道（future 异常完成，便于 failover 换节点），指标按它的真实状态记，
  不并入 `TRANSPORT_FAILURE`。
- 客户端按原类型还原出的框架异常（`RpcException` 子类）取的是该异常类型自己的状态，可能与线上状态不同（例如业务方法透传了下游的
  `RpcOverloadedException`：线上是 SERVER_ERROR，客户端记 SERVER_OVERLOADED）。以服务端的 `status` 为准。
- 客户端对未知类型降级时没有类型信息，一律记 `BIZ_EXCEPTION`；服务端回 SERVER_ERROR 而客户端把异常还原成了非框架类型（例如
  `NullPointerException`）时，客户端同样记 `BIZ_EXCEPTION`。

## 计时口径与 Filter 位置

两个 Filter 都是**异步计时**：在下游返回的 future 上挂回调记录，原样返回同一个 future，调用线程不等待、结果不变。业务方法返回
未完成的 `CompletableFuture` 时，服务端计时延续到它完成，业务线程照常释放。

| | 链（从外到内） | 计时起点 | 计时终点 |
|---|---|---|---|
| 客户端 | Tracing `-6000`（预留）→ **Metrics `-5000`** → Deadline `-2000` → 用户 Filter `0` → Cluster | 进入 Metrics Filter（代理发起调用之后） | 调用完成（含重试与本端超时） |
| 服务端 | Tracing 恢复 `-6000`（预留）→ **Metrics `-5000`** → Deadline `-4000` → 并发限制 `-3000`（规划）→ 用户 Filter `0` → 业务方法 | 进入 Metrics Filter（请求已在业务执行器上拿到线程） | Filter 链完成（业务结果已产生，尚未序列化回包） |

为什么排在最外层（比 Deadline Filter 更靠外）：服务端的 `DeadlineServerFilter`（以及以后的并发限制 Filter）会**拒绝**请求，排在它们之内的
指标 Filter 根本看不到被拒绝的请求，错误码分布里就不会出现 DEADLINE_EXCEEDED / SERVER_OVERLOADED；客户端同理，最外层还让计时覆盖
整条链（含其它 Filter 自身的开销）。Tracing 预留在 Metrics 之外（`-6000`）：将来要把 trace id 作为 exemplar 挂到指标上时，记录指标的那一刻
已经有 trace 上下文。

服务端计时**不含**在业务执行器队列里等待的时间（Filter 链在业务线程上运行）；排队的积压体现在 `rpc_server_inflight` 上。

## 服务端指标看不到的部分

服务端指标记的是"进入 Filter 链的请求"。以下情况发生在链外，`rpc.server.calls` 不计入（客户端指标照常计入）：

- IO 线程上的拒绝：业务执行器满载（队列满 / 并发达到上限）、优雅停机 DRAINING 期间到达的请求——客户端看到 `SERVER_OVERLOADED`；
- 帧或信封错误（BAD_REQUEST）、服务或方法不存在（SERVICE_NOT_FOUND）、不支持的序列化编码；
- Filter 链完成之后的改写：结果序列化失败或回包超过整帧上限（线上 SERIALIZATION_ERROR，服务端指标记的是 `OK`）、回程 attachments
  非法（线上 SERVER_ERROR）。

需要从服务端视角看过载拒绝时，对照客户端的 `status="SERVER_OVERLOADED"` 与服务端的 `rpc_server_inflight`。

## PromQL 示例

```promql
# QPS（服务端，按 status）
sum by (status) (rate(rpc_server_calls_seconds_count[30s]))

# 服务端 p99（全部方法；按方法看时 by (le, method)）
histogram_quantile(0.99, sum by (le) (rate(rpc_server_calls_seconds_bucket[30s])))

# 客户端 p99（调用方看到的往返，含网络与服务端排队）
histogram_quantile(0.99, sum by (le) (rate(rpc_client_calls_seconds_bucket[30s])))

# 错误率（status 不是 OK 的占比）
sum(rate(rpc_server_calls_seconds_count{status!="OK"}[30s])) / sum(rate(rpc_server_calls_seconds_count[30s]))

# 客户端超时与传输失败的速率
sum by (status) (rate(rpc_client_calls_seconds_count{status=~"TIMEOUT|TRANSPORT_FAILURE"}[1m]))

# 平均延迟
sum(rate(rpc_server_calls_seconds_sum[30s])) / sum(rate(rpc_server_calls_seconds_count[30s]))

# 在途与连接
rpc_server_inflight
rpc_client_connections

# 离群摘除：最近 5 分钟的摘除次数、此刻被摘的提供者数
increase(rpc_client_outlier_ejections_total[5m])
rpc_client_outlier_ejected
```

离群摘除持续发生（`increase(rpc_client_outlier_ejections_total[5m])` 大于 0 且反复出现）说明有提供者"活着但不回包"或连接不上，而注册中心没有摘它；
`RpcClient.outlierAllEjectedFallbacks()`（没有导出成指标）计"候选全在摘除期、退回全部候选"的选点次数，正常配置下应为 0。

`histogram_quantile` 的结果是桶内线性插值，精度受桶边界限制；压测报告里的分位数以压测端的 HdrHistogram 为准，两者口径不同，不要互相替代。

## Grafana 面板

`docker compose -f docker/compose.yaml up -d --wait` 之后打开 <http://localhost:3000>（匿名只读，首页即 **RPC Overview**，uid `rpc-overview`）。
数据源是 provisioning 预置的 Prometheus（uid `rpc-prometheus`）；顶部变量 `job` 选择抓取目标（缺省全部 `rpc-*`）。

| 行 | 面板 | 查询 |
|---|---|---|
| 吞吐与错误率 | QPS（服务端，按 status；虚线为客户端总 QPS） | `sum by (status) (rate(rpc_server_calls_seconds_count[30s]))` |
| | 错误率（服务端 / 客户端） | `status!="OK"` 的速率 / 总速率 |
| 延迟 | 服务端 p50 / p95 / p99 | `histogram_quantile(q, sum by (le) (rate(rpc_server_calls_seconds_bucket[30s])))` |
| | 客户端 p99（另附 p50） | 同上，`rpc_client_calls_seconds_bucket` |
| 在途与连接 | 在途（client / server） | `rpc_server_inflight`、`rpc_client_inflight` |
| | 连接数 | `rpc_server_connections`、`rpc_client_connections` |
| 错误码分布 | 服务端 / 客户端错误码（所选时间范围） | `sum by (status) (increase(rpc_*_calls_seconds_count{status!="OK"}[$__range]))` |

Prometheus 预置的抓取目标与端口约定见 [docker/README.md](../docker/README.md#端口)：`rpc-bench-server` 9464、`rpc-bench-client` 9465、
`rpc-example-provider` 9466、`rpc-example-consumer` 9467，抓取间隔 5s。

## 开销与压测

指标 Filter 在调用的热路径上：每次调用多一次 future 回调、三次 ConcurrentHashMap 读取与一次直方图记录。端到端压测
（[benchmarking.md](benchmarking.md)）的 `server` / `run` 子命令有 `--metrics-port`，缺省关闭；**正式数字默认不开**，开了要在报告的
`--label` 里写明，报告 JSON 的 `environment.metrics_enabled` / `server_environment.metrics_enabled` 如实记录两端是否开启。
