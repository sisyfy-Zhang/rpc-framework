# crossval-http-open-v1

> 标签：`archive` · 结果文件：`crossval-http-open-v1-20261022-023508.json` · 判定：**通过**

**问题**：校验 harness 开环的达成吞吐、服务时间与节拍质量

## 环境

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:35:08.396326655Z → 2026-10-21T15:38:30.699849085Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

## 口径

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
| harness 选项 | 开环执行者 pool-platform（64 个平台线程工作者，有界 FIFO 队列） · HTTP 客户端 blocking · 调度线程绑核 5（第 1 轮读回：pinned，其余线程 6-9） |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 100000、错误 0、延迟 p99 0.484ms、最大在途 28 |

## 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 200003 | 5000.1 | 0.178 | 0.292 | 0.412 | 4.151 | 33.358 | 0 | 0.328 | 0.011 | 22.3% | 26.6% | 0 / 2 | 1.00 / 1.00 | 0 | 是 |
| **2（正式）** | **200000** | **5000.0** | **0.180** | **0.299** | **0.403** | **0.684** | **8.839** | **0** | **0.334** | **0.009** | **23.4%** | **12.9%** | **0 / 2** | **1.00 / 1.00** | **0** | **是** |
| 3 | 200000 | 5000.0 | 0.175 | 0.281 | 0.377 | 0.547 | 9.404 | 0 | 0.320 | 0.007 | 24.1% | 22.1% | 3 / 2 | 1.00 / 1.00 | 0 | 是 |

正式轮：第 2 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.075（0.0%），p50_ms 0.005（2.9%），p99_ms 0.035（9.3%），p999_ms 3.604（658.9%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max — / — / — ms（0 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | — | — | 12 -> 12 | 0.986（0.984 / 0.003） |
| fjp | 5 -> 4 | -0.008（-0.005 / -0.003） | — | — |
| timers | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| harness | 65 -> 21 | -0.018（-0.018 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.006（0.006 / 0.000） | 20 -> 20 | 0.006（0.006 / 0.000） |
| other | 7 -> 6 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | -0.020 | | 0.992 |

## 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

## 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19180]），经 http://127.0.0.1:19180/echo 发现；卡片声明的注册中心：none (direct HTTP URL)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

