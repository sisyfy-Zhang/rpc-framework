# 压测报告汇总

由 `rpc-bench-e2e report` 从 JSON 结果文件生成；每个数字都可在对应 JSON 中找到。

| 口径卡 | 运行次数 | 最近一次 | 判定 |
|---|---|---|---|
| [crossval-http-closed-capacity-v1](#crossval-http-closed-capacity-v1) | 1 | 2026-10-19T03:59:00.055303575Z | **通过** |
| [crossval-http-open-v1](#crossval-http-open-v1) | 1 | 2026-10-19T03:54:49.819946683Z | **通过** |
| [loopback-latency-v1](#loopback-latency-v1) | 1 | 2026-10-19T03:51:38.409163793Z | **通过** |
| [loopback-throughput-v1](#loopback-throughput-v1) | 1 | 2026-10-19T03:48:26.112645410Z | **通过** |

## crossval-http-closed-capacity-v1

> 标签：`baseline` · 结果文件：`crossval-http-closed-capacity-v1-20261019-145900.json` · 判定：**通过**

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
| git | d5d620c（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-19T03:59:00.055303575Z → 2026-10-19T04:02:02.411968907Z |
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

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 4454082 | 111352.1 | 0.455 | 1.279 | 2.607 | 5.738 | 60.948 | 0 | 95.3% | 74.7% | 29 / 28 | 是 |
| **2（正式）** | **4449720** | **111243.0** | **0.451** | **1.271** | **2.814** | **6.365** | **55.050** | **0** | **94.7%** | **74.8%** | **24 / 26** | **是** |
| 3 | 4282667 | 107066.7 | 0.462 | 1.342 | 2.982 | 6.885 | 61.309 | 0 | 94.2% | 74.1% | 29 / 28 | 是 |

正式轮：第 2 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 4285.375（4.0%），p50_ms 0.011（2.4%），p99_ms 0.375（14.4%），p999_ms 1.147（20.0%）。

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

> 标签：`baseline` · 结果文件：`crossval-http-open-v1-20261019-145449.json` · 判定：**通过**

**问题**：校验 harness 开环的达成吞吐、服务时间与节拍质量

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
| git | d5d620c（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-19T03:54:49.819946683Z → 2026-10-19T03:57:52.114782505Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

### 口径

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

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 200000 | 5000.0 | 0.272 | 0.458 | 0.635 | 1.547 | 12.149 | 0 | 0.514 | 0.055 | 42.3% | 15.6% | 5 / 0 | 是 |
| **2（正式）** | **200000** | **5000.0** | **0.266** | **0.456** | **0.698** | **3.215** | **15.679** | **0** | **0.569** | **0.046** | **41.9%** | **15.3%** | **2 / 0** | **是** |
| 3 | 200000 | 5000.0 | 0.268 | 0.460 | 0.724 | 2.996 | 16.892 | 0 | 0.591 | 0.063 | 44.4% | 17.5% | 3 / 0 | 是 |

正式轮：第 2 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.000（0.0%），p50_ms 0.006（2.3%），p99_ms 0.089（14.0%），p999_ms 1.668（107.8%）。

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19180]），经 http://127.0.0.1:19180/echo 发现；卡片声明的注册中心：none (direct HTTP URL)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## loopback-latency-v1

> 标签：`baseline` · 结果文件：`loopback-latency-v1-20261019-145138.json` · 判定：**通过**

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
| git | d5d620c（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-19T03:51:38.409163793Z → 2026-10-19T03:54:39.468722555Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | zk://127.0.0.1:2181，提供者 127.0.0.1:9000，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 负载模型 | 开环（open），10000 req/s 等间隔发出，在途上限 20000 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 docker-compose/zookeeper |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 350013 | 10000.4 | 0.535 | 6.189 | 52.560 | 274.203 | 332.136 | 0 | 36.504 | 8.913 | 59.7% | 40.0% | 45 / 11 | 是 |
| **2（正式）** | **349998** | **9999.9** | **0.443** | **0.875** | **2.617** | **18.858** | **53.412** | **0** | **2.384** | **0.205** | **53.5%** | **38.8%** | **9 / 6** | **是** |
| 3 | 349997 | 9999.9 | 0.429 | 0.735 | 1.533 | 5.165 | 18.792 | 0 | 1.354 | 0.119 | 54.6% | 38.3% | 8 / 7 | 是 |

正式轮：第 2 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.457（0.0%），p50_ms 0.106（24.7%），p99_ms 51.027（3328.6%），p999_ms 269.038（5208.9%）。

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:9000]），经 zk://127.0.0.1:2181 发现；卡片声明的注册中心：docker-compose/zookeeper。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## loopback-throughput-v1

> 标签：`baseline` · 结果文件：`loopback-throughput-v1-20261019-144826.json` · 判定：**通过**

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
| git | d5d620c（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-19T03:48:26.112645410Z → 2026-10-19T03:51:27.273037348Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | zk://127.0.0.1:2181，提供者 127.0.0.1:9000，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 docker-compose/zookeeper |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 2698461 | 77098.9 | 0.742 | 1.519 | 2.527 | 4.714 | 14.410 | 0 | 58.7% | 57.5% | 52 / 44 | 是 |
| **2（正式）** | **2767834** | **79081.0** | **0.730** | **1.447** | **2.331** | **4.903** | **17.760** | **0** | **58.0%** | **57.6%** | **49 / 42** | **是** |
| 3 | 2815368 | 80439.1 | 0.726 | 1.408 | 2.173 | 4.090 | 17.908 | 0 | 58.6% | 57.9% | 51 / 44 | 是 |

正式轮：第 2 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 3340.200（4.3%），p50_ms 0.016（2.2%），p99_ms 0.354（16.3%），p999_ms 0.813（19.9%）。

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:9000]），经 zk://127.0.0.1:2181 发现；卡片声明的注册中心：docker-compose/zookeeper。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## 交叉验证判定

harness 与 wrk / wrk2 打同一个 HTTP 目标的比较结果（`crossval` 子命令），口径见 docs/benchmarking.md「交叉验证」。

| 结果文件 | 模式 | 工具 | harness 结果 | 判定 |
|---|---|---|---|---|
| [crossval-open-20261019-145853.json](#crossval-open-20261019-145853json) | open (measurement) | wrk2 | crossval-http-open-v1-20261019-145449.json | **FAIL** |
| [crossval-closed-20261019-150303.json](#crossval-closed-20261019-150303json) | closed (capacity) | wrk | crossval-http-closed-capacity-v1-20261019-145900.json | **PASS** |

### crossval-open-20261019-145853.json

> 判定：**FAIL** · 创建于 2026-10-19T14:58:53.210096871+11:00 · harness：`crossval-http-open-v1-20261019-145449.json`（git d5d620c（干净））· 工具命令：`taskset -c 5-9 wrk2 -t 4 -c 64 -R 5000 --latency -U -s bench/bin/post-1k.lua -d 40s http://127.0.0.1:19180/echo`

**问题**：同一个 HTTP 目标、开环同一 rate，harness 与 wrk2 的发压节拍与测量是否一致——校验的是 harness 本身

| 组 | 判据 | 实测 | 相对偏差 | 结果 |
|---|---|---|---|---|
| 前提 | harness 全部轮次有效、正式轮窗口内无错误 | all_rounds_valid=true, window_errors=0 | — | 通过 |
| 前提 | wrk2 无 socket 错误、无非 2xx/3xx 响应 | socket_errors=0, non_2xx_3xx=0 | — | 通过 |
| a. 达成吞吐 | harness 相对目标 rate 偏差 ≤ 2% | 5000 vs 5000 req/s | 0.0% | 通过 |
| a. 达成吞吐 | wrk2 相对目标 rate 偏差 ≤ 2% | 4996.0 vs 5000 req/s | 0.1% | 通过 |
| b. 测量一致性 | harness 服务时间 p50 vs wrk2 未修正 p50：相对偏差 ≤ 15% 或绝对差 ≤ 100µs（取宽者） | 0.227 vs 0.156 ms | 45.5% | 通过 |
| b. 测量一致性 | harness 服务时间 p99 vs wrk2 未修正 p99：相对偏差 ≤ 15% 或绝对差 ≤ 100µs（取宽者） | 0.569 vs 0.366 ms | 55.5% | **未通过** |
| c. 节拍质量 | harness 发出滞后 p99 ≤ 100µs | 0.046 ms | — | 通过 |

### crossval-closed-20261019-150303.json

> 判定：**PASS** · 创建于 2026-10-19T15:03:03.290247011+11:00 · harness：`crossval-http-closed-capacity-v1-20261019-145900.json`（git d5d620c（干净））· 工具命令：`taskset -c 5-9 wrk -t 4 -c 64 --latency -s bench/bin/post-1k.lua -d 40s http://127.0.0.1:19180/echo`

**问题**：同一个 HTTP 目标、闭环同一并发，harness 与 wrk 各自能压到多少、瓶颈在哪一侧——发压容量标定

| 组 | 判据 | 实测 | 相对偏差 | 结果 |
|---|---|---|---|---|
| 唯一判定项 | 两次运行的 CPU 采样都成功，且都能判别瓶颈侧（某一侧平均 ≥ 90%） | harness 运行：压测端饱和；wrk 运行：服务端饱和 | — | 通过 |


## 与上一批（20261019-77d04f2）对比

> 本节手工整理，不是 `rpc-bench-e2e report` 的输出；上方各节为原样生成、未改动。每个数字都摘自两批归档 JSON 的
> `summary.official` / `rounds[]` / `validity`（正式轮 = 闭环吞吐中位、开环 p99 中位那一轮）。两批同一台机器、同一组卡片与绑核
> （服务端 0-4、压测端 5-9）、`label=baseline`（上一批交叉验证的 label 为 `crossval`）、不开指标、两端 JVM 参数相同
> （`-Xms1g -Xmx1g -XX:+AlwaysPreTouch`，G1）。两批之间的代码差异：eb846f9（调用方线程不再使用池化分配器）与 d5d620c
> （响应侧同样处理；harness 每轮 GC 观测——每个统计窗口起止各多一次 `stats()` 调用）。单批每张卡只跑一次，下表的差异
> 不足以归因到某一处改动。

| 卡 | 指标（正式轮） | 77d04f2 | d5d620c |
|---|---|---|---|
| loopback-throughput-v1 | 吞吐 (req/s) | 74975.1 | 79081.0 |
| | p50 / p99 / p99.9 / max (ms) | 0.785 / 2.105 / 4.219 / 40.206 | 0.730 / 2.331 / 4.903 / 17.760 |
| | 吞吐跨轮极差 | 2.4% | 4.3% |
| | 客户端核 / 服务端核 CPU | 60.6% / 58.7% | 58.0% / 57.6% |
| | GC 停顿 客户端 / 服务端 (ms) | —（上一批无此字段） | 49 / 42（窗口 35s，0.14% / 0.12%） |
| loopback-latency-v1 | p50 / p99 / p99.9 / max (ms) | 0.465 / 2.437 / 12.247 / 35.881 | 0.443 / 2.617 / 18.858 / 53.412 |
| | p99 跨轮极差 (ms) | 1.184 | 51.027（第 1 轮 p99 52.560、发出滞后 p99 8.913ms；第 2、3 轮 2.617 / 1.533） |
| | 客户端核 / 服务端核 CPU | 59.0% / 40.3% | 53.5% / 38.8% |
| | GC 停顿 客户端 / 服务端 (ms) | — | 9 / 6（第 1 轮 45 / 11，占窗口 0.129% / 0.031%） |
| | 判定 | 通过 | 通过 |
| crossval 开环（测量校验） | 判定 | FAIL ×2 | FAIL |
| | harness 服务时间 p99 vs wrk2 未修正 p99 (ms) | 0.521 vs 0.378；0.534 vs 0.367 | 0.569 vs 0.366 |
| crossval 闭环（容量标定） | 判定 | PASS | PASS |
| | harness 闭环吞吐 (req/s) / 压测端核 CPU | 124486.6 / 95.8% | 111243.0 / 94.7% |

要点：

- 两张正式卡在新 sha 下都通过（0 错误），正式轮的 p50 两张卡都略低、p99 与 p99.9 略高，量级不变。
- 延迟卡第 1 轮出现一次整轮的尾部抬升（p99 52.6ms、p99.9 274ms），同轮**发出滞后** p99 8.9ms、p99.9 94ms——调度线程本身被推迟，
  发生在压测端；两端 GC 停顿在该轮只占窗口 0.13% / 0.03%，不是原因。按卡片规则该轮仍有效，正式轮取 p99 中位的第 2 轮。
- 开环交叉验证与上一批一样 FAIL 在同一项（harness 服务时间 p99 比 wrk2 未修正 p99 高约 0.2ms），阈值未放宽、结果原样归档。
- 闭环容量标定 PASS，两次运行分别在压测端（harness）与服务端（wrk）饱和，与上一批一致；harness 闭环吞吐比上一批低 10.6%，
  HTTP 路径不经过本次改动的 RPC 编解码，属于本机两次运行之间的差异。
