# exec-virtual-work10ms-closed256-monitor-v1

> 标签：`exec-probe` · 结果文件：`exec-virtual-work10ms-closed256-monitor-v1-20261022-160500.json` · 判定：**通过**

**问题**：单提供者、直连、1KB，服务端业务方法每次在 synchronized 块内 Object.wait 10ms（模拟 IO 等待），闭环 256 并发：服务端执行器 virtual 的吞吐、延迟、拒绝数、每请求 CPU 与线程数

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
| git | 121c99f（工作区有未提交改动） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-22T05:05:00.291721672Z → 2026-10-22T05:08:20.911480618Z |
| harness | rpc-bench-e2e 1.0.0 |

## 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 2（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 闭环（closed），并发 256 个虚拟用户 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 离群摘除 | 开：窗口 10s 内尝试 ≥ 20 且坏结果占比 ≥ 0.5，或连续坏结果 ≥ 5；摘除 10s × 次数（上限 60s），最多摘 50%（来源 default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 320583、错误 0、延迟 p99 56.394ms |

## 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 761796 | 21765.6 | 11.084 | 14.213 | 20.087 | 84.935 | 202.375 | 0 | 33.1% | 68.9% | 19 / 30 | 0.52 / 0.47 | 0 | 是 |
| **2（正式）** | **796923** | **22769.2** | **10.936** | **13.009** | **15.417** | **21.676** | **35.619** | **0** | **34.9%** | **67.2%** | **17 / 38** | **0.54 / 0.51** | **0** | **是** |
| 3 | 802947 | 22941.3 | 10.863 | 12.689 | 15.114 | 25.674 | 51.053 | 0 | 35.0% | 64.9% | 20 / 33 | 0.54 / 0.53 | 0 | 是 |

正式轮：第 2 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 1175.743（5.4%），p50_ms 0.221（2.0%），p99_ms 4.973（32.9%），p999_ms 63.259（291.8%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.087 / 1.229 / 15.532 ms（796228 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 0.289（0.284 / 0.006） | 3 -> 3 | 0.289（0.253 / 0.036） |
| fjp | 5 -> 5 | 0.531（0.523 / 0.009） | 256 -> 256 | 1.883（1.722 / 0.161） |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.002（0.002 / 0.000） | 20 -> 20 | 0.003（0.002 / 0.001） |
| other | 2 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 0.822 | | 2.175 |

## 断言

本卡未声明断言。

## 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 非缺省配置：服务端业务执行器为 virtual（框架缺省是 fixed）。
- 非缺省配置：服务端业务方法每次在 synchronized 块内 Object.wait 10ms（模拟 IO 等待）。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

