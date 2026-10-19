# 压测报告汇总

由 `rpc-bench-e2e report` 从 JSON 结果文件生成；每个数字都可在对应 JSON 中找到。

| 口径卡 | 运行次数 | 最近一次 | 判定 |
|---|---|---|---|
| [crossval-http-closed-capacity-v1](#crossval-http-closed-capacity-v1) | 1 | 2026-10-19T00:54:23.558434476Z | **通过** |
| [crossval-http-open-v1](#crossval-http-open-v1) | 2 | 2026-10-19T00:50:10.715659733Z | **通过** |
| [loopback-latency-v1](#loopback-latency-v1) | 1 | 2026-10-19T00:42:30.345697598Z | **通过** |
| [loopback-throughput-v1](#loopback-throughput-v1) | 1 | 2026-10-19T00:27:59.803266202Z | **通过** |

## crossval-http-closed-capacity-v1

> 标签：`crossval` · 结果文件：`crossval-http-closed-capacity-v1-20261019-115423.json` · 判定：**通过**

**问题**：标定 harness 闭环发压上限与服务端上限（64 连接、1KB）

### 环境

| 项 | 值 |
|---|---|
| 操作系统 | Linux 6.18.33.2-microsoft-standard-WSL2 (amd64) |
| WSL | 是 |
| CPU | AMD Ryzen 5 4600H with Radeon Graphics |
| 可用核数 / 主机核数 | 5 / 10（服务端 5 / 10） |
| 内存 | 9.7 GiB |
| JDK | 21.0.12.1+1-LTS · Eclipse Adoptium · OpenJDK 64-Bit Server VM |
| JVM 参数（压测端） | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| 服务端 JVM 参数 | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| CPU 亲和（压测端） | 5-9 |
| 服务端 CPU 亲和 | 0-4 |
| 指标 | 压测端 metrics_enabled=false · 服务端 metrics_enabled=false |
| GC | G1 Young Generation G1 Concurrent GC G1 Old Generation |
| Netty | 4.1.136.Final |
| git | 77d04f2（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-19T00:54:23.558434476Z → 2026-10-19T00:57:25.457467586Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | http · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | —（HTTP 请求体就是原始字节）/ 1 个 URL |
| 连接 | http://127.0.0.1:19180/echo，64 条 keep-alive 连接、每条同一时刻 1 个在途请求（closed: one connection per virtual user (pool = load.concurrency); one in-flight request per connection），重试 0 |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 60s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (direct HTTP URL) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 4954537 | 123863.4 | 0.429 | 1.085 | 1.859 | 4.690 | 55.968 | 0 | 97.1% | 73.9% | 是 |
| 2 | 5056015 | 126400.4 | 0.419 | 1.051 | 1.933 | 4.989 | 37.061 | 0 | 96.5% | 74.3% | 是 |
| **3（正式）** | **4979464** | **124486.6** | **0.421** | **1.068** | **2.138** | **5.231** | **35.291** | **0** | **95.8%** | **74.0%** | **是** |

正式轮：第 3 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 2536.950（2.0%），p50_ms 0.010（2.4%），p99_ms 0.279（15.0%），p999_ms 0.541（11.5%）。

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19180]），经 http://127.0.0.1:19180/echo 发现；卡片声明的注册中心：none (direct HTTP URL)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 本卡吞吐受发压端 CPU 限制（有效轮里客户端核平均忙碌率超过 90%），是 harness 的发压上限而非服务端上限。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## crossval-http-open-v1

### crossval-http-open-v1-20261019-114545.json

> 标签：`crossval` · 结果文件：`crossval-http-open-v1-20261019-114545.json` · 判定：**通过**

**问题**：校验 harness 开环的达成吞吐、服务时间与节拍质量

#### 环境

| 项 | 值 |
|---|---|
| 操作系统 | Linux 6.18.33.2-microsoft-standard-WSL2 (amd64) |
| WSL | 是 |
| CPU | AMD Ryzen 5 4600H with Radeon Graphics |
| 可用核数 / 主机核数 | 5 / 10（服务端 5 / 10） |
| 内存 | 9.7 GiB |
| JDK | 21.0.12.1+1-LTS · Eclipse Adoptium · OpenJDK 64-Bit Server VM |
| JVM 参数（压测端） | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| 服务端 JVM 参数 | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| CPU 亲和（压测端） | 5-9 |
| 服务端 CPU 亲和 | 0-4 |
| 指标 | 压测端 metrics_enabled=false · 服务端 metrics_enabled=false |
| GC | G1 Young Generation G1 Concurrent GC G1 Old Generation |
| Netty | 4.1.136.Final |
| git | 77d04f2（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-19T00:45:45.133175914Z → 2026-10-19T00:48:46.779605686Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

#### 口径

| 项 | 值 |
|---|---|
| 目标 | http · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | —（HTTP 请求体就是原始字节）/ 1 个 URL |
| 连接 | http://127.0.0.1:19180/echo，64 条 keep-alive 连接、每条同一时刻 1 个在途请求（open: pool of target.http.connections; one in-flight request per connection; no idle connection at the scheduled time -> queue (FIFO) for the next idle one, latency still from the scheduled time; in-flight cap (queued + sent) = rate x timeout x 2），重试 0 |
| 负载模型 | 开环（open），5000 req/s 等间隔发出，在途上限 10000 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 60s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (direct HTTP URL) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |

#### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（正式）** | **200000** | **5000.0** | **0.265** | **0.428** | **0.616** | **2.214** | **8.446** | **0** | **0.521** | **0.018** | **45.6%** | **15.2%** | **是** |
| 2 | 200001 | 5000.0 | 0.270 | 0.444 | 0.615 | 1.744 | 8.028 | 0 | 0.518 | 0.027 | 31.3% | 23.2% | 是 |
| 3 | 200000 | 5000.0 | 0.261 | 0.439 | 0.692 | 13.369 | 58.556 | 0 | 0.585 | 0.018 | 39.0% | 16.4% | 是 |

正式轮：第 1 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.025（0.0%），p50_ms 0.009（3.4%），p99_ms 0.077（12.5%），p999_ms 11.625（666.6%）。

#### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

#### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19180]），经 http://127.0.0.1:19180/echo 发现；卡片声明的注册中心：none (direct HTTP URL)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

### crossval-http-open-v1-20261019-115010.json

> 标签：`crossval` · 结果文件：`crossval-http-open-v1-20261019-115010.json` · 判定：**通过**

**问题**：校验 harness 开环的达成吞吐、服务时间与节拍质量

#### 环境

| 项 | 值 |
|---|---|
| 操作系统 | Linux 6.18.33.2-microsoft-standard-WSL2 (amd64) |
| WSL | 是 |
| CPU | AMD Ryzen 5 4600H with Radeon Graphics |
| 可用核数 / 主机核数 | 5 / 10（服务端 5 / 10） |
| 内存 | 9.7 GiB |
| JDK | 21.0.12.1+1-LTS · Eclipse Adoptium · OpenJDK 64-Bit Server VM |
| JVM 参数（压测端） | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| 服务端 JVM 参数 | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| CPU 亲和（压测端） | 5-9 |
| 服务端 CPU 亲和 | 0-4 |
| 指标 | 压测端 metrics_enabled=false · 服务端 metrics_enabled=false |
| GC | G1 Young Generation G1 Concurrent GC G1 Old Generation |
| Netty | 4.1.136.Final |
| git | 77d04f2（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-19T00:50:10.715659733Z → 2026-10-19T00:53:13.019691479Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

#### 口径

| 项 | 值 |
|---|---|
| 目标 | http · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | —（HTTP 请求体就是原始字节）/ 1 个 URL |
| 连接 | http://127.0.0.1:19180/echo，64 条 keep-alive 连接、每条同一时刻 1 个在途请求（open: pool of target.http.connections; one in-flight request per connection; no idle connection at the scheduled time -> queue (FIFO) for the next idle one, latency still from the scheduled time; in-flight cap (queued + sent) = rate x timeout x 2），重试 0 |
| 负载模型 | 开环（open），5000 req/s 等间隔发出，在途上限 10000 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 60s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (direct HTTP URL) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |

#### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 200000 | 5000.0 | 0.262 | 0.427 | 0.599 | 2.198 | 19.202 | 0 | 0.504 | 0.028 | 43.5% | 15.0% | 是 |
| **2（正式）** | **200000** | **5000.0** | **0.259** | **0.426** | **0.643** | **2.918** | **26.509** | **0** | **0.534** | **0.017** | **40.7%** | **13.8%** | **是** |
| 3 | 200000 | 5000.0 | 0.264 | 0.459 | 1.106 | 14.680 | 27.181 | 0 | 0.802 | 0.040 | 32.2% | 22.5% | 是 |

正式轮：第 2 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.000（0.0%），p50_ms 0.005（1.9%），p99_ms 0.507（84.6%），p999_ms 12.482（567.9%）。

#### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

#### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19180]），经 http://127.0.0.1:19180/echo 发现；卡片声明的注册中心：none (direct HTTP URL)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## loopback-latency-v1

> 标签：`baseline` · 结果文件：`loopback-latency-v1-20261019-114230.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者，开环 10000 req/s 下的延迟分布

### 环境

| 项 | 值 |
|---|---|
| 操作系统 | Linux 6.18.33.2-microsoft-standard-WSL2 (amd64) |
| WSL | 是 |
| CPU | AMD Ryzen 5 4600H with Radeon Graphics |
| 可用核数 / 主机核数 | 5 / 10（服务端 5 / 10） |
| 内存 | 9.7 GiB |
| JDK | 21.0.12.1+1-LTS · Eclipse Adoptium · OpenJDK 64-Bit Server VM |
| JVM 参数（压测端） | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| 服务端 JVM 参数 | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| CPU 亲和（压测端） | 5-9 |
| 服务端 CPU 亲和 | 0-4 |
| 指标 | 压测端 metrics_enabled=false · 服务端 metrics_enabled=false |
| GC | G1 Young Generation G1 Concurrent GC G1 Old Generation |
| Netty | 4.1.136.Final |
| git | 77d04f2（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-19T00:42:30.345697598Z → 2026-10-19T00:45:31.415974445Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | zk://127.0.0.1:2181，提供者 127.0.0.1:19100，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 负载模型 | 开环（open），10000 req/s 等间隔发出，在途上限 20000 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 docker-compose/zookeeper |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 350000 | 10000.0 | 0.433 | 0.766 | 2.746 | 18.874 | 40.927 | 0 | 2.257 | 1.147 | 54.7% | 38.8% | 是 |
| 2 | 350000 | 10000.0 | 0.423 | 0.686 | 1.562 | 7.705 | 21.217 | 0 | 1.407 | 0.043 | 53.6% | 39.2% | 是 |
| **3（正式）** | **350000** | **10000.0** | **0.465** | **0.848** | **2.437** | **12.247** | **35.881** | **0** | **2.163** | **0.099** | **59.0%** | **40.3%** | **是** |

正式轮：第 3 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.000（0.0%），p50_ms 0.042（9.9%），p99_ms 1.184（75.8%），p999_ms 11.169（145.0%）。

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19100]），经 zk://127.0.0.1:2181 发现；卡片声明的注册中心：docker-compose/zookeeper。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## loopback-throughput-v1

> 标签：`baseline` · 结果文件：`loopback-throughput-v1-20261019-112759.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者，闭环 64 并发下的吞吐与延迟量级

### 环境

| 项 | 值 |
|---|---|
| 操作系统 | Linux 6.18.33.2-microsoft-standard-WSL2 (amd64) |
| WSL | 是 |
| CPU | AMD Ryzen 5 4600H with Radeon Graphics |
| 可用核数 / 主机核数 | 5 / 10（服务端 5 / 10） |
| 内存 | 9.7 GiB |
| JDK | 21.0.12.1+1-LTS · Eclipse Adoptium · OpenJDK 64-Bit Server VM |
| JVM 参数（压测端） | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| 服务端 JVM 参数 | `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` |
| CPU 亲和（压测端） | 5-9 |
| 服务端 CPU 亲和 | 0-4 |
| 指标 | 压测端 metrics_enabled=false · 服务端 metrics_enabled=false |
| GC | G1 Young Generation G1 Concurrent GC G1 Old Generation |
| Netty | 4.1.136.Final |
| git | 77d04f2（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-19T00:27:59.803266202Z → 2026-10-19T00:31:00.737999161Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | zk://127.0.0.1:2181，提供者 127.0.0.1:19100，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 docker-compose/zookeeper |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 2603288 | 74379.7 | 0.791 | 1.474 | 2.195 | 4.289 | 21.905 | 0 | 61.3% | 58.9% | 是 |
| 2 | 2666831 | 76195.2 | 0.775 | 1.413 | 2.067 | 4.284 | 10.494 | 0 | 60.9% | 59.0% | 是 |
| **3（正式）** | **2624127** | **74975.1** | **0.785** | **1.450** | **2.105** | **4.219** | **40.206** | **0** | **60.6%** | **58.7%** | **是** |

正式轮：第 3 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 1815.514（2.4%），p50_ms 0.016（2.1%），p99_ms 0.128（6.2%），p999_ms 0.070（1.7%）。

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19100]），经 zk://127.0.0.1:2181 发现；卡片声明的注册中心：docker-compose/zookeeper。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

