# compare-closed64-dubbo-c2-v1

> 标签：`archive` · 结果文件：`compare-closed64-dubbo-c2-v1-20261022-015743.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，Dubbo 3（triple 协议） 闭环 64 并发下的吞吐与延迟量级（连接数对齐：2 条连接）

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
| 时间 | 2026-10-21T14:57:43.641404489Z → 2026-10-21T15:01:05.690582084Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

## 口径

| 项 | 值 |
|---|---|
| 目标 | dubbo · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | hessian2 / 1 个 |
| 连接 | direct 127.0.0.1:19303，提供者 127.0.0.1:19303，2 independent Dubbo client instances = 2 triple HTTP/2 connections, calls take an instance round-robin; concurrent calls are multiplexed streams，重试 0 |
| 连接数（target.connections） | 2 → 实际 2（target.connections = number of independent Dubbo client instances (FrameworkModel + reference), one triple connection each; the reference 'connections' parameter is ignored by triple in Dubbo 3.3.6 (default: 1)） |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 dubbo default (shared client event loop: min(available cores + 1, 32) = 6) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 343758、错误 0、延迟 p99 22.446ms |

## 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 890260 | 25436.0 | 2.337 | 4.325 | 6.074 | 9.036 | 20.595 | 0 | 59.1% | 68.4% | 131 / 111 | 0.33 / 0.17 | n/a | 是 |
| 2 | 927168 | 26490.5 | 2.273 | 3.992 | 5.501 | 8.122 | 39.256 | 0 | 56.6% | 68.5% | 138 / 113 | 0.32 / 0.17 | n/a | 是 |
| **3（正式）** | **908707** | **25963.1** | **2.302** | **4.119** | **5.784** | **9.765** | **49.578** | **0** | **56.1%** | **68.0%** | **136 / 110** | **0.32 / 0.17** | **n/a** | **是** |

正式轮：第 3 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 1054.514（4.1%），p50_ms 0.064（2.8%），p99_ms 0.573（10.4%），p999_ms 1.643（20.2%）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 0.116（0.105 / 0.012） | 3 -> 3 | 0.198（0.018 / 0.180） |
| biz_pool | — | — | 200 -> 200 | 2.222（2.063 / 0.159） |
| fjp | 5 -> 5 | 0.291（0.283 / 0.008） | — | — |
| timers | 1 -> 1 | 0.008（0.008 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.005（0.005 / 0.000） | 20 -> 20 | 0.005（0.005 / 0.000） |
| other | 3 -> 3 | 0.001（0.001 / 0.000） | 10 -> 10 | 0.000（0.000 / 0.000） |
| 合计 | | 0.421 | | 2.425 |

## 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

## 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19303]），经 direct 127.0.0.1:19303 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

