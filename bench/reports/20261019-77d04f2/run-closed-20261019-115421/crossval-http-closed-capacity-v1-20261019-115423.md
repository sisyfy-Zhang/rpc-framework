# crossval-http-closed-capacity-v1

> 标签：`crossval` · 结果文件：`crossval-http-closed-capacity-v1-20261019-115423.json` · 判定：**通过**

**问题**：标定 harness 闭环发压上限与服务端上限（64 连接、1KB）

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
| 时间 | 2026-10-19T00:54:23.558434476Z → 2026-10-19T00:57:25.457467586Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

## 口径

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

## 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 4954537 | 123863.4 | 0.429 | 1.085 | 1.859 | 4.690 | 55.968 | 0 | 97.1% | 73.9% | 是 |
| 2 | 5056015 | 126400.4 | 0.419 | 1.051 | 1.933 | 4.989 | 37.061 | 0 | 96.5% | 74.3% | 是 |
| **3（正式）** | **4979464** | **124486.6** | **0.421** | **1.068** | **2.138** | **5.231** | **35.291** | **0** | **95.8%** | **74.0%** | **是** |

正式轮：第 3 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 2536.950（2.0%），p50_ms 0.010（2.4%），p99_ms 0.279（15.0%），p999_ms 0.541（11.5%）。

## 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

## 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19180]），经 http://127.0.0.1:19180/echo 发现；卡片声明的注册中心：none (direct HTTP URL)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 本卡吞吐受发压端 CPU 限制（有效轮里客户端核平均忙碌率超过 90%），是 harness 的发压上限而非服务端上限。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

