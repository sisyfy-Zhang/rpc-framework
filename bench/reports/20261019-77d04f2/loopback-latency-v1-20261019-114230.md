# loopback-latency-v1

> 标签：`baseline` · 结果文件：`loopback-latency-v1-20261019-114230.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者，开环 10000 req/s 下的延迟分布

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
| 时间 | 2026-10-19T00:42:30.345697598Z → 2026-10-19T00:45:31.415974445Z |
| harness | rpc-bench-e2e 0.0.1-SNAPSHOT |

## 口径

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

## 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 350000 | 10000.0 | 0.433 | 0.766 | 2.746 | 18.874 | 40.927 | 0 | 2.257 | 1.147 | 54.7% | 38.8% | 是 |
| 2 | 350000 | 10000.0 | 0.423 | 0.686 | 1.562 | 7.705 | 21.217 | 0 | 1.407 | 0.043 | 53.6% | 39.2% | 是 |
| **3（正式）** | **350000** | **10000.0** | **0.465** | **0.848** | **2.437** | **12.247** | **35.881** | **0** | **2.163** | **0.099** | **59.0%** | **40.3%** | **是** |

正式轮：第 3 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.000（0.0%），p50_ms 0.042（9.9%），p99_ms 1.184（75.8%），p999_ms 11.169（145.0%）。

## 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

## 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19100]），经 zk://127.0.0.1:2181 发现；卡片声明的注册中心：docker-compose/zookeeper。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

