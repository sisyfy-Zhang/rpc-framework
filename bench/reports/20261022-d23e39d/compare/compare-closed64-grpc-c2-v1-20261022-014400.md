# compare-closed64-grpc-c2-v1

> 标签：`archive` · 结果文件：`compare-closed64-grpc-c2-v1-20261022-014400.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，gRPC（grpc-java，Netty 传输，HTTP/2） 闭环 64 并发下的吞吐与延迟量级（连接数对齐：2 条连接）

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
| 时间 | 2026-10-21T14:44:00.129148858Z → 2026-10-21T14:47:21.401092233Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

## 口径

| 项 | 值 |
|---|---|
| 目标 | grpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19301，提供者 127.0.0.1:19301，2 ManagedChannels = 2 HTTP/2 connections, calls take a channel round-robin; concurrent calls are multiplexed streams，重试 0 |
| 连接数（target.connections） | 2 → 实际 2（target.connections = number of ManagedChannels to the provider (default 1)） |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 516944、错误 0、延迟 p99 9.822ms |

## 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1213878 | 34682.2 | 1.717 | 3.000 | 4.829 | 7.389 | 16.228 | 0 | 54.2% | 63.3% | 29 / 26 | 1.63 / 0.18 | n/a | 是 |
| 2 | 1253841 | 35824.0 | 1.658 | 2.871 | 4.735 | 7.688 | 38.109 | 0 | 53.2% | 63.5% | 31 / 26 | 1.62 / 0.18 | n/a | 是 |
| **3（正式）** | **1243070** | **35516.3** | **1.677** | **2.929** | **4.678** | **7.152** | **35.160** | **0** | **52.4%** | **64.0%** | **28 / 27** | **1.61 / 0.18** | **n/a** | **是** |

正式轮：第 3 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 1141.800（3.3%），p50_ms 0.059（3.6%），p99_ms 0.151（3.2%），p999_ms 0.536（7.5%）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 10 -> 10 | 0.898（0.842 / 0.057） | 3 -> 3 | 0.107（0.006 / 0.101） |
| biz_pool | — | — | 64 -> 40 | -1.408（-1.348 / -0.061） |
| fjp | 5 -> 5 | 0.586（0.301 / 0.285） | — | — |
| timers | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.002（0.002 / 0.000） | 20 -> 20 | 0.002（0.002 / 0.000） |
| other | 2 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 1.486 | | -1.299 |

## 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

## 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19301]），经 direct 127.0.0.1:19301 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

