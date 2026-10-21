# compare-open10k-dubbo-c2-v1

> 标签：`archive` · 结果文件：`compare-open10k-dubbo-c2-v1-20261022-020106.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，Dubbo 3（triple 协议） 开环 10000 req/s 下的延迟分布（连接数对齐：2 条连接）

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
| 时间 | 2026-10-21T15:01:06.515049015Z → 2026-10-21T15:04:29.323951899Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

## 口径

| 项 | 值 |
|---|---|
| 目标 | dubbo · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | hessian2 / 1 个 |
| 连接 | direct 127.0.0.1:19303，提供者 127.0.0.1:19303，2 independent Dubbo client instances = 2 triple HTTP/2 connections, calls take an instance round-robin; concurrent calls are multiplexed streams，重试 0 |
| 连接数（target.connections） | 2 → 实际 2（target.connections = number of independent Dubbo client instances (FrameworkModel + reference), one triple connection each; the reference 'connections' parameter is ignored by triple in Dubbo 3.3.6 (default: 1)） |
| 负载模型 | 开环（open），10000 req/s 等间隔发出，在途上限 20000 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |
| harness 选项 | 开环执行者 pool（64 个虚拟线程工作者，有界 FIFO 队列） · 客户端 IO 线程 dubbo default (shared client event loop: min(available cores + 1, 32) = 6) · 调度线程绑核 5（第 1 轮读回：pinned，其余线程 6-9） |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 200000、错误 5669、延迟 p99 2518.680ms、最大在途 20000 |

## 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 350000 | 10000.0 | 0.810 | 1.967 | 5.571 | 22.053 | 34.177 | 0 | 4.188 | 0.393 | 72.8% | 53.9% | 46 / 49 | 1.39 / 1.15 | n/a | 是 |
| 2 | 349997 | 9999.9 | 0.722 | 1.291 | 2.730 | 14.107 | 33.161 | 0 | 2.269 | 0.036 | 70.9% | 52.8% | 42 / 48 | 1.49 / 1.27 | n/a | 是 |
| **3（正式）** | **349999** | **10000.0** | **0.708** | **1.254** | **4.235** | **25.346** | **33.112** | **0** | **2.791** | **0.079** | **71.6%** | **52.6%** | **44 / 47** | **1.51 / 1.29** | **n/a** | **是** |

正式轮：第 3 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.086（0.0%），p50_ms 0.102（14.4%），p99_ms 2.841（104.1%），p999_ms 11.239（79.7%）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 0.806（0.626 / 0.181） | 5 -> 5 | 0.576（0.563 / 0.013） |
| biz_pool | — | — | 200 -> 200 | 1.170（1.158 / 0.011） |
| fjp | 5 -> 5 | 2.039（1.926 / 0.113） | — | — |
| timers | 1 -> 1 | 0.149（0.149 / 0.001） | — | — |
| harness | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.007（0.007 / 0.000） | 20 -> 20 | 0.007（0.007 / 0.000） |
| other | 4 -> 4 | 0.004（0.003 / 0.001） | 10 -> 10 | 0.000（0.000 / 0.000） |
| 合计 | | 3.005 | | 1.753 |

## 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

## 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19303]），经 direct 127.0.0.1:19303 发现；卡片声明的注册中心：none (--direct)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

