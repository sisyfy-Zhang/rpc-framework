# crossval-http-open-v1

> 标签：`crossval` · 结果文件：`crossval-http-open-v1-20261019-115010.json` · 判定：**通过**

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
| git | 77d04f2（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-19T00:50:10.715659733Z → 2026-10-19T00:53:13.019691479Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

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

## 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 200000 | 5000.0 | 0.262 | 0.427 | 0.599 | 2.198 | 19.202 | 0 | 0.504 | 0.028 | 43.5% | 15.0% | 是 |
| **2（正式）** | **200000** | **5000.0** | **0.259** | **0.426** | **0.643** | **2.918** | **26.509** | **0** | **0.534** | **0.017** | **40.7%** | **13.8%** | **是** |
| 3 | 200000 | 5000.0 | 0.264 | 0.459 | 1.106 | 14.680 | 27.181 | 0 | 0.802 | 0.040 | 32.2% | 22.5% | 是 |

正式轮：第 2 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.000（0.0%），p50_ms 0.005（1.9%），p99_ms 0.507（84.6%），p999_ms 12.482（567.9%）。

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

