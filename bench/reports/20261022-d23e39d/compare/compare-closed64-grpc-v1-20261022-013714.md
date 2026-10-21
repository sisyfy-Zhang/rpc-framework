# compare-closed64-grpc-v1

> 标签：`archive` · 结果文件：`compare-closed64-grpc-v1-20261022-013714.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，gRPC（grpc-java，Netty 传输，HTTP/2） 闭环 64 并发下的吞吐与延迟量级

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
| 时间 | 2026-10-21T14:37:14.714496747Z → 2026-10-21T14:40:36.417282519Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

## 口径

| 项 | 值 |
|---|---|
| 目标 | grpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，1 ManagedChannel = 1 HTTP/2 connection; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider)，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 1（target.connections = number of ManagedChannels to the provider (default 1)） |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 248413、错误 0、延迟 p99 14.230ms |

## 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 506759 | 14478.8 | 4.231 | 6.414 | 7.086 | 8.380 | 25.412 | 0 | 21.3% | 38.2% | 17 / 13 | 1.43 / 0.06 | n/a | 是 |
| 2 | 502944 | 14369.8 | 4.289 | 6.406 | 7.148 | 8.307 | 27.443 | 0 | 19.4% | 38.4% | 13 / 11 | 1.38 / 0.06 | n/a | 是 |
| **3（正式）** | **503524** | **14386.4** | **4.297** | **6.377** | **7.152** | **8.471** | **25.952** | **0** | **20.9%** | **38.7%** | **12 / 12** | **1.38 / 0.06** | **n/a** | **是** |

正式轮：第 3 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 109.000（0.8%），p50_ms 0.066（1.6%），p99_ms 0.066（0.9%），p999_ms 0.164（2.0%）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 10 -> 10 | 0.819（0.789 / 0.030） | 2 -> 2 | 0.009（0.000 / 0.009） |
| biz_pool | — | — | 46 -> 46 | 2.798（2.790 / 0.008） |
| fjp | 5 -> 5 | 0.460（0.180 / 0.280） | — | — |
| timers | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.003（0.003 / 0.000） | 20 -> 20 | 0.003（0.003 / 0.000） |
| other | 2 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 1.282 | | 2.810 |

## 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

## 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

