# 压测报告汇总

由 `rpc-bench-e2e report` 从 JSON 结果文件生成；每个数字都可在对应 JSON 中找到。

| 口径卡 | 运行次数 | 最近一次 | 判定 |
|---|---|---|---|
| [compare-closed64-dubbo-c2-v1](#compare-closed64-dubbo-c2-v1) | 1 | 2026-10-21T14:57:43.641404489Z | **通过** |
| [compare-closed64-dubbo-v1](#compare-closed64-dubbo-v1) | 1 | 2026-10-21T14:50:49.974731133Z | **通过** |
| [compare-closed64-grpc-c2-v1](#compare-closed64-grpc-c2-v1) | 1 | 2026-10-21T14:44:00.129148858Z | **通过** |
| [compare-closed64-grpc-v1](#compare-closed64-grpc-v1) | 1 | 2026-10-21T14:37:14.714496747Z | **通过** |
| [compare-closed64-rpc-direct-v1](#compare-closed64-rpc-direct-v1) | 1 | 2026-10-21T15:04:38.224175318Z | **通过** |
| [compare-closed64-rpc-v1](#compare-closed64-rpc-v1) | 1 | 2026-10-21T14:27:03.071014213Z | **通过** |
| [compare-open10k-dubbo-c2-v1](#compare-open10k-dubbo-c2-v1) | 1 | 2026-10-21T15:01:06.515049015Z | **通过** |
| [compare-open10k-dubbo-v1](#compare-open10k-dubbo-v1) | 1 | 2026-10-21T14:54:12.820512879Z | **通过** |
| [compare-open10k-grpc-c2-v1](#compare-open10k-grpc-c2-v1) | 1 | 2026-10-21T14:47:22.020213340Z | **通过** |
| [compare-open10k-grpc-v1](#compare-open10k-grpc-v1) | 1 | 2026-10-21T14:40:37.078703658Z | **通过** |
| [compare-open10k-rpc-direct-v1](#compare-open10k-rpc-direct-v1) | 1 | 2026-10-21T15:07:59.418395663Z | **通过** |
| [compare-open10k-rpc-v1](#compare-open10k-rpc-v1) | 1 | 2026-10-21T14:30:24.659749822Z | **通过** |
| [crossval-http-closed-capacity-v1](#crossval-http-closed-capacity-v1) | 1 | 2026-10-21T15:41:33.635327629Z | **通过** |
| [crossval-http-open-v1](#crossval-http-open-v1) | 3 | 2026-10-21T16:25:57.951165425Z | **通过** |
| [loopback-latency-v1](#loopback-latency-v1) | 1 | 2026-10-21T15:31:42.824388436Z | **通过** |
| [loopback-throughput-v1](#loopback-throughput-v1) | 1 | 2026-10-21T15:28:20.737128534Z | **通过** |
| [sweep-c1-closed64-rpc-direct-v1](#sweep-c1-closed64-rpc-direct-v1) | 1 | 2026-10-21T15:11:22.748049778Z | **通过** |
| [sweep-c1-closed64-rpc-v1](#sweep-c1-closed64-rpc-v1) | 1 | 2026-10-21T14:33:49.726626831Z | **通过** |
| [sweep-c4-closed128-rpc-v1](#sweep-c4-closed128-rpc-v1) | 1 | 2026-10-21T15:21:28.545135233Z | **通过** |
| [sweep-c8-closed128-rpc-v1](#sweep-c8-closed128-rpc-v1) | 1 | 2026-10-21T15:24:49.565398572Z | **通过** |
| [sweep-closed128-rpc-v1](#sweep-closed128-rpc-v1) | 1 | 2026-10-21T15:14:45.806529258Z | **通过** |
| [sweep-closed256-rpc-v1](#sweep-closed256-rpc-v1) | 1 | 2026-10-21T15:18:07.017965080Z | **通过** |

## 对比表

同一负载模型、负载大小与时长口径，只在 transport / 并发 / 速率 / 客户端 IO 线程数上不同的结果并排（`compare` 子命令同一份表）。

### 闭环 · 1024 B · `BenchService#echo` · 每轮 60s（预热 20s，窗口 [20s, 55s]）× 3 轮 · 超时 1s

> 对比轴：`transport`（开箱即用：各框架缺省连接数；concurrency = 64）。

| transport（序列化） | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **rpc（protobuf）** | `compare-closed64-rpc-v1` | 101943.2 | 1.00× | 0.573 | 1.458 | 2.789 | 15.385 | 57.2% | 49.1% | 否 | 58 / 54 | 0.19 / 0.16 | 0.27 / 0.27 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-closed64-rpc-v1-20261022-012703.json` |
| **grpc（protobuf）** | `compare-closed64-grpc-v1` | 14386.4 | 0.14× | 4.297 | 7.152 | 8.471 | 25.952 | 20.9% | 38.7% | 否 | 12 / 12 | 1.38 / 0.06 | 1.28 / 2.81 | — | 0 | 通过 | 1 ManagedChannel = 1 HTTP/2 connection; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-closed64-grpc-v1-20261022-013714.json` |
| **dubbo（hessian2）** | `compare-closed64-dubbo-v1` | 14401.8 | 0.14× | 4.530 | 7.307 | 10.166 | 35.226 | 23.7% | 41.6% | 否 | 63 / 62 | 0.11 / 0.07 | 0.25 / 2.73 | — | 0 | 通过 | 1 triple HTTP/2 connection per address; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-closed64-dubbo-v1-20261022-015049.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

transport 表不按服务端执行器与 flush 合并分区：它们是各框架自己的缺省（见"服务端配置"列）；本框架的非缺省配置（执行器不是 fixed，或 flush 合并关）不进 transport 表，见 `server_executor` / `flush_consolidation` 轴的表。

> 对比轴：`transport`（连接数对齐；connections = 1，concurrency = 64）。

| transport（序列化） | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **rpc（protobuf）** | `sweep-c1-closed64-rpc-v1` | 92771.5 | 1.00× | 0.633 | 1.540 | 2.679 | 27.984 | 38.1% | 38.8% | 否 | 54 / 47 | 0.09 / 0.10 | 0.19 / 0.26 | 0 | 0 | 通过 | 1 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-c1-closed64-rpc-v1-20261022-013349.json` |
| **grpc（protobuf）** | `compare-closed64-grpc-v1` | 14386.4 | 0.16× | 4.297 | 7.152 | 8.471 | 25.952 | 20.9% | 38.7% | 否 | 12 / 12 | 1.38 / 0.06 | 1.28 / 2.81 | — | 0 | 通过 | 1 ManagedChannel = 1 HTTP/2 connection; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-closed64-grpc-v1-20261022-013714.json` |
| **dubbo（hessian2）** | `compare-closed64-dubbo-v1` | 14401.8 | 0.16× | 4.530 | 7.307 | 10.166 | 35.226 | 23.7% | 41.6% | 否 | 63 / 62 | 0.11 / 0.07 | 0.25 / 2.73 | — | 0 | 通过 | 1 triple HTTP/2 connection per address; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-closed64-dubbo-v1-20261022-015049.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

transport 表不按服务端执行器与 flush 合并分区：它们是各框架自己的缺省（见"服务端配置"列）；本框架的非缺省配置（执行器不是 fixed，或 flush 合并关）不进 transport 表，见 `server_executor` / `flush_consolidation` 轴的表。

> 对比轴：`transport`（连接数对齐；connections = 2，concurrency = 64）。

| transport（序列化） | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **rpc（protobuf）** | `compare-closed64-rpc-v1` | 101943.2 | 1.00× | 0.573 | 1.458 | 2.789 | 15.385 | 57.2% | 49.1% | 否 | 58 / 54 | 0.19 / 0.16 | 0.27 / 0.27 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-closed64-rpc-v1-20261022-012703.json` |
| **grpc（protobuf）** | `compare-closed64-grpc-c2-v1` | 35516.3 | 0.35× | 1.677 | 4.678 | 7.152 | 35.160 | 52.4% | 64.0% | 否 | 28 / 27 | 1.61 / 0.18 | 1.49 / -1.30 | — | 0 | 通过 | 2 ManagedChannels = 2 HTTP/2 connections, calls take a channel round-robin; concurrent calls are multiplexed streams | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-closed64-grpc-c2-v1-20261022-014400.json` |
| **dubbo（hessian2）** | `compare-closed64-dubbo-c2-v1` | 25963.1 | 0.25× | 2.302 | 5.784 | 9.765 | 49.578 | 56.1% | 68.0% | 否 | 136 / 110 | 0.32 / 0.17 | 0.42 / 2.43 | — | 0 | 通过 | 2 independent Dubbo client instances = 2 triple HTTP/2 connections, calls take an instance round-robin; concurrent calls are multiplexed streams | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-closed64-dubbo-c2-v1-20261022-015743.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

transport 表不按服务端执行器与 flush 合并分区：它们是各框架自己的缺省（见"服务端配置"列）；本框架的非缺省配置（执行器不是 fixed，或 flush 合并关）不进 transport 表，见 `server_executor` / `flush_consolidation` 轴的表。

> 对比轴：`connections`（transport = rpc（protobuf），concurrency = 64，server_executor = direct，flush_consolidation = client on / server on）。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1** | rpc（protobuf） | `sweep-c1-closed64-rpc-direct-v1` | 124541.0 | 1.00× | 0.494 | 1.144 | 2.212 | 21.709 | 39.5% | 17.2% | 否 | 69 / 59 | 0.08 / 0.05 | 0.18 / 0.02 | 0 | 0 | 通过 | 1 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 direct (NOT the default): business methods run on the IO threads (max in-flight 10000) | `sweep-c1-closed64-rpc-direct-v1-20261022-021122.json` |
| **2（缺省）** | rpc（protobuf） | `compare-closed64-rpc-direct-v1` | 142671.6 | 1.15× | 0.421 | 0.977 | 2.284 | 19.038 | 60.3% | 27.2% | 否 | 79 / 69 | 0.18 / 0.08 | 0.24 / 0.04 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 direct (NOT the default): business methods run on the IO threads (max in-flight 10000) | `compare-closed64-rpc-direct-v1-20261022-020438.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`connections`（transport = rpc（protobuf），concurrency = 64，server_executor = fixed，flush_consolidation = client on / server on）。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1** | rpc（protobuf） | `sweep-c1-closed64-rpc-v1` | 92771.5 | 1.00× | 0.633 | 1.540 | 2.679 | 27.984 | 38.1% | 38.8% | 否 | 54 / 47 | 0.09 / 0.10 | 0.19 / 0.26 | 0 | 0 | 通过 | 1 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-c1-closed64-rpc-v1-20261022-013349.json` |
| **2（缺省）** | rpc（protobuf） | `compare-closed64-rpc-v1` | 101943.2 | 1.10× | 0.573 | 1.458 | 2.789 | 15.385 | 57.2% | 49.1% | 否 | 58 / 54 | 0.19 / 0.16 | 0.27 / 0.27 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-closed64-rpc-v1-20261022-012703.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`connections`（transport = rpc（protobuf），concurrency = 128，server_executor = fixed，flush_consolidation = client on / server on）。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **2（缺省）** | rpc（protobuf） | `sweep-closed128-rpc-v1` | 141161.6 | 1.00× | 0.837 | 2.191 | 4.641 | 43.516 | 63.9% | 49.9% | 否 | 84 / 72 | 0.15 / 0.11 | 0.19 / 0.18 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-closed128-rpc-v1-20261022-021445.json` |
| **4** | rpc（protobuf） | `sweep-c4-closed128-rpc-v1` | 138567.0 | 0.98× | 0.799 | 2.830 | 5.100 | 33.751 | 78.2% | 61.5% | 否 | 84 / 79 | 0.38 / 0.23 | 0.25 / 0.24 | 0 | 0 | 通过 | 4 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-c4-closed128-rpc-v1-20261022-022128.json` |
| **8** | rpc（protobuf） | `sweep-c8-closed128-rpc-v1` | 126244.3 | 0.89× | 0.814 | 4.420 | 7.320 | 35.193 | 85.6% | 69.7% | 否 | 85 / 80 | 0.61 / 0.40 | 0.44 / 0.39 | 0 | 0 | 通过 | 8 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-c8-closed128-rpc-v1-20261022-022449.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`connections`（transport = grpc（protobuf），concurrency = 64，server_executor = grpc-default，flush_consolidation = —）。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（缺省）** | grpc（protobuf） | `compare-closed64-grpc-v1` | 14386.4 | 1.00× | 4.297 | 7.152 | 8.471 | 25.952 | 20.9% | 38.7% | 否 | 12 / 12 | 1.38 / 0.06 | 1.28 / 2.81 | — | 0 | 通过 | 1 ManagedChannel = 1 HTTP/2 connection; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-closed64-grpc-v1-20261022-013714.json` |
| **2** | grpc（protobuf） | `compare-closed64-grpc-c2-v1` | 35516.3 | 2.47× | 1.677 | 4.678 | 7.152 | 35.160 | 52.4% | 64.0% | 否 | 28 / 27 | 1.61 / 0.18 | 1.49 / -1.30 | — | 0 | 通过 | 2 ManagedChannels = 2 HTTP/2 connections, calls take a channel round-robin; concurrent calls are multiplexed streams | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-closed64-grpc-c2-v1-20261022-014400.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`connections`（transport = dubbo（hessian2），concurrency = 64，server_executor = —，flush_consolidation = —）。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（缺省）** | dubbo（hessian2） | `compare-closed64-dubbo-v1` | 14401.8 | 1.00× | 4.530 | 7.307 | 10.166 | 35.226 | 23.7% | 41.6% | 否 | 63 / 62 | 0.11 / 0.07 | 0.25 / 2.73 | — | 0 | 通过 | 1 triple HTTP/2 connection per address; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-closed64-dubbo-v1-20261022-015049.json` |
| **2** | dubbo（hessian2） | `compare-closed64-dubbo-c2-v1` | 25963.1 | 1.80× | 2.302 | 5.784 | 9.765 | 49.578 | 56.1% | 68.0% | 否 | 136 / 110 | 0.32 / 0.17 | 0.42 / 2.43 | — | 0 | 通过 | 2 independent Dubbo client instances = 2 triple HTTP/2 connections, calls take an instance round-robin; concurrent calls are multiplexed streams | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-closed64-dubbo-c2-v1-20261022-015743.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`concurrency`（transport = rpc（protobuf），connections = 2，server_executor = fixed，flush_consolidation = client on / server on）。

| 并发 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **64** | rpc（protobuf） | `compare-closed64-rpc-v1` | 101943.2 | 1.00× | 0.573 | 1.458 | 2.789 | 15.385 | 57.2% | 49.1% | 否 | 58 / 54 | 0.19 / 0.16 | 0.27 / 0.27 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-closed64-rpc-v1-20261022-012703.json` |
| **128** | rpc（protobuf） | `sweep-closed128-rpc-v1` | 141161.6 | 1.38× | 0.837 | 2.191 | 4.641 | 43.516 | 63.9% | 49.9% | 否 | 84 / 72 | 0.15 / 0.11 | 0.19 / 0.18 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-closed128-rpc-v1-20261022-021445.json` |
| **256** | rpc（protobuf） | `sweep-closed256-rpc-v1` | 162498.2 | 1.59× | 1.474 | 3.701 | 5.915 | 35.652 | 61.7% | 48.5% | 否 | 103 / 88 | 0.13 / 0.07 | 0.14 / 0.13 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-closed256-rpc-v1-20261022-021807.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`server_executor`（transport = rpc（protobuf），connections = 1，concurrency = 64，flush_consolidation = client on / server on）。

| 服务端执行器 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **direct** | rpc（protobuf） | `sweep-c1-closed64-rpc-direct-v1` | 124541.0 | 1.00× | 0.494 | 1.144 | 2.212 | 21.709 | 39.5% | 17.2% | 否 | 69 / 59 | 0.08 / 0.05 | 0.18 / 0.02 | 0 | 0 | 通过 | 1 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 direct (NOT the default): business methods run on the IO threads (max in-flight 10000) | `sweep-c1-closed64-rpc-direct-v1-20261022-021122.json` |
| **fixed** | rpc（protobuf） | `sweep-c1-closed64-rpc-v1` | 92771.5 | 0.74× | 0.633 | 1.540 | 2.679 | 27.984 | 38.1% | 38.8% | 否 | 54 / 47 | 0.09 / 0.10 | 0.19 / 0.26 | 0 | 0 | 通过 | 1 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-c1-closed64-rpc-v1-20261022-013349.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`server_executor`（transport = rpc（protobuf），connections = 2，concurrency = 64，flush_consolidation = client on / server on）。

| 服务端执行器 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **direct** | rpc（protobuf） | `compare-closed64-rpc-direct-v1` | 142671.6 | 1.00× | 0.421 | 0.977 | 2.284 | 19.038 | 60.3% | 27.2% | 否 | 79 / 69 | 0.18 / 0.08 | 0.24 / 0.04 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 direct (NOT the default): business methods run on the IO threads (max in-flight 10000) | `compare-closed64-rpc-direct-v1-20261022-020438.json` |
| **fixed** | rpc（protobuf） | `compare-closed64-rpc-v1` | 101943.2 | 0.71× | 0.573 | 1.458 | 2.789 | 15.385 | 57.2% | 49.1% | 否 | 58 / 54 | 0.19 / 0.16 | 0.27 / 0.27 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-closed64-rpc-v1-20261022-012703.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

### 开环 · 1024 B · `BenchService#echo` · 每轮 60s（预热 20s，窗口 [20s, 55s]）× 3 轮 · 超时 1s

> 对比轴：`transport`（开箱即用：各框架缺省连接数）。

| transport（序列化） | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 服务时间 p99 (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **rpc（protobuf）** | `compare-open10k-rpc-v1` | 10000.0 | 1.00× | 0.440 | 0.798 | 14.475 | 32.621 | 0.702 | 50.0% | 37.6% | 否 | 8 / 7 | 1.77 / 1.58 | 3.01 / 2.18 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-open10k-rpc-v1-20261022-013024.json` |
| **grpc（protobuf）** | `compare-open10k-grpc-v1` | 9999.7 | 1.00× | 1.226 | 20.955 | 37.552 | 44.728 | 7.741 | 52.1% | 36.6% | 否 | 11 / 8 | 3.22 / 0.45 | 2.99 / 2.86 | — | 0 | 通过 | 1 ManagedChannel = 1 HTTP/2 connection; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-open10k-grpc-v1-20261022-014037.json` |
| **dubbo（hessian2）** | `compare-open10k-dubbo-v1` | 10000.0 | 1.00× | 0.776 | 22.233 | 30.753 | 41.746 | 5.308 | 59.1% | 42.4% | 否 | 42 / 45 | 1.08 / 0.65 | 2.37 / 1.46 | — | 0 | 通过 | 1 triple HTTP/2 connection per address; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-open10k-dubbo-v1-20261022-015412.json` |

每行取该结果的正式轮（开环：窗口 p99 中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。开环延迟从计划发出时刻算起（修正 coordinated omission）。

transport 表不按服务端执行器与 flush 合并分区：它们是各框架自己的缺省（见"服务端配置"列）；本框架的非缺省配置（执行器不是 fixed，或 flush 合并关）不进 transport 表，见 `server_executor` / `flush_consolidation` 轴的表。

> 对比轴：`transport`（连接数对齐；connections = 2）。

| transport（序列化） | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 服务时间 p99 (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **rpc（protobuf）** | `compare-open10k-rpc-v1` | 10000.0 | 1.00× | 0.440 | 0.798 | 14.475 | 32.621 | 0.702 | 50.0% | 37.6% | 否 | 8 / 7 | 1.77 / 1.58 | 3.01 / 2.18 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-open10k-rpc-v1-20261022-013024.json` |
| **grpc（protobuf）** | `compare-open10k-grpc-c2-v1` | 10000.1 | 1.00× | 0.585 | 1.338 | 8.511 | 33.014 | 1.239 | 60.9% | 50.1% | 否 | 13 / 11 | 4.07 / 2.53 | 4.18 / 3.61 | — | 0 | 通过 | 2 ManagedChannels = 2 HTTP/2 connections, calls take a channel round-robin; concurrent calls are multiplexed streams | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-open10k-grpc-c2-v1-20261022-014722.json` |
| **dubbo（hessian2）** | `compare-open10k-dubbo-c2-v1` | 10000.0 | 1.00× | 0.708 | 4.235 | 25.346 | 33.112 | 2.791 | 71.6% | 52.6% | 否 | 44 / 47 | 1.51 / 1.29 | 3.01 / 1.75 | — | 0 | 通过 | 2 independent Dubbo client instances = 2 triple HTTP/2 connections, calls take an instance round-robin; concurrent calls are multiplexed streams | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-open10k-dubbo-c2-v1-20261022-020106.json` |

每行取该结果的正式轮（开环：窗口 p99 中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。开环延迟从计划发出时刻算起（修正 coordinated omission）。

transport 表不按服务端执行器与 flush 合并分区：它们是各框架自己的缺省（见"服务端配置"列）；本框架的非缺省配置（执行器不是 fixed，或 flush 合并关）不进 transport 表，见 `server_executor` / `flush_consolidation` 轴的表。

> 对比轴：`connections`（transport = grpc（protobuf），server_executor = grpc-default，flush_consolidation = —）。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 服务时间 p99 (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（缺省）** | grpc（protobuf） | `compare-open10k-grpc-v1` | 9999.7 | 1.00× | 1.226 | 20.955 | 37.552 | 44.728 | 7.741 | 52.1% | 36.6% | 否 | 11 / 8 | 3.22 / 0.45 | 2.99 / 2.86 | — | 0 | 通过 | 1 ManagedChannel = 1 HTTP/2 connection; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-open10k-grpc-v1-20261022-014037.json` |
| **2** | grpc（protobuf） | `compare-open10k-grpc-c2-v1` | 10000.1 | 1.00× | 0.585 | 1.338 | 8.511 | 33.014 | 1.239 | 60.9% | 50.1% | 否 | 13 / 11 | 4.07 / 2.53 | 4.18 / 3.61 | — | 0 | 通过 | 2 ManagedChannels = 2 HTTP/2 connections, calls take a channel round-robin; concurrent calls are multiplexed streams | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-open10k-grpc-c2-v1-20261022-014722.json` |

每行取该结果的正式轮（开环：窗口 p99 中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。开环延迟从计划发出时刻算起（修正 coordinated omission）。

> 对比轴：`connections`（transport = dubbo（hessian2），server_executor = —，flush_consolidation = —）。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 服务时间 p99 (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（缺省）** | dubbo（hessian2） | `compare-open10k-dubbo-v1` | 10000.0 | 1.00× | 0.776 | 22.233 | 30.753 | 41.746 | 5.308 | 59.1% | 42.4% | 否 | 42 / 45 | 1.08 / 0.65 | 2.37 / 1.46 | — | 0 | 通过 | 1 triple HTTP/2 connection per address; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-open10k-dubbo-v1-20261022-015412.json` |
| **2** | dubbo（hessian2） | `compare-open10k-dubbo-c2-v1` | 10000.0 | 1.00× | 0.708 | 4.235 | 25.346 | 33.112 | 2.791 | 71.6% | 52.6% | 否 | 44 / 47 | 1.51 / 1.29 | 3.01 / 1.75 | — | 0 | 通过 | 2 independent Dubbo client instances = 2 triple HTTP/2 connections, calls take an instance round-robin; concurrent calls are multiplexed streams | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-open10k-dubbo-c2-v1-20261022-020106.json` |

每行取该结果的正式轮（开环：窗口 p99 中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。开环延迟从计划发出时刻算起（修正 coordinated omission）。

> 对比轴：`server_executor`（transport = rpc（protobuf），connections = 2，flush_consolidation = client on / server on）。

| 服务端执行器 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 服务时间 p99 (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **direct** | rpc（protobuf） | `compare-open10k-rpc-direct-v1` | 10000.0 | 1.00× | 0.251 | 0.530 | 3.133 | 14.967 | 0.446 | 49.6% | 21.7% | 否 | 8 / 6 | 1.66 / 0.99 | 3.37 / 0.99 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 direct (NOT the default): business methods run on the IO threads (max in-flight 10000) | `compare-open10k-rpc-direct-v1-20261022-020759.json` |
| **fixed** | rpc（protobuf） | `compare-open10k-rpc-v1` | 10000.0 | 1.00× | 0.440 | 0.798 | 14.475 | 32.621 | 0.702 | 50.0% | 37.6% | 否 | 8 / 7 | 1.77 / 1.58 | 3.01 / 2.18 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-open10k-rpc-v1-20261022-013024.json` |

每行取该结果的正式轮（开环：窗口 p99 中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。开环延迟从计划发出时刻算起（修正 coordinated omission）。

### 开环 · 1024 B · `BenchService#echo` · 每轮 60s（预热 20s，窗口 [20s, 60s]）× 3 轮 · 超时 1s · 目标 http

> 同一口径的重复运行（没有字段不同）。

| transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 服务时间 p99 (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| http | `crossval-http-open-v1` | 5000.0 | 1.00× | 0.180 | 0.403 | 0.684 | 8.839 | 0.334 | 23.4% | 12.9% | 否 | 0 / 2 | 1.00 / 1.00 | -0.02 / 0.99 | 0 | 0 | 通过 | open: pool of target.http.connections; one in-flight request per connection; no idle connection at the scheduled time -> queue (FIFO) for the next idle one, latency still from the scheduled time; in-flight cap (queued + sent) = rate x timeout x 2 | n/a (blocking client) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `crossval-http-open-v1-20261022-023508.json` |
| http | `crossval-http-open-v1` | 5000.0 | 1.00× | 0.188 | 0.402 | 2.666 | 20.595 | 0.321 | 24.5% | 22.9% | 否 | 0 / 2 | 1.00 / 1.00 | -1.01 / 1.01 | 0 | 0 | 通过 | open: pool of target.http.connections; one in-flight request per connection; no idle connection at the scheduled time -> queue (FIFO) for the next idle one, latency still from the scheduled time; in-flight cap (queued + sent) = rate x timeout x 2 | n/a (blocking client) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `crossval-http-open-v1-20261022-031809.json` |
| http | `crossval-http-open-v1` | 5000.0 | 1.00× | 0.182 | 0.401 | 2.433 | 13.746 | 0.315 | 37.1% | 14.9% | 否 | 0 / 2 | 1.00 / 1.00 | -1.02 / 0.99 | 0 | 0 | 通过 | open: pool of target.http.connections; one in-flight request per connection; no idle connection at the scheduled time -> queue (FIFO) for the next idle one, latency still from the scheduled time; in-flight cap (queued + sent) = rate x timeout x 2 | n/a (blocking client) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `crossval-http-open-v1-20261022-032557.json` |

每行取该结果的正式轮（开环：窗口 p99 中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。开环延迟从计划发出时刻算起（修正 coordinated omission）。

## compare-closed64-dubbo-c2-v1

> 标签：`archive` · 结果文件：`compare-closed64-dubbo-c2-v1-20261022-015743.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，Dubbo 3（triple 协议） 闭环 64 并发下的吞吐与延迟量级（连接数对齐：2 条连接）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T14:57:43.641404489Z → 2026-10-21T15:01:05.690582084Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

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

### 轮次

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

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19303]），经 direct 127.0.0.1:19303 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-closed64-dubbo-v1

> 标签：`archive` · 结果文件：`compare-closed64-dubbo-v1-20261022-015049.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，Dubbo 3（triple 协议） 闭环 64 并发下的吞吐与延迟量级

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T14:50:49.974731133Z → 2026-10-21T14:54:12.234664405Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | dubbo · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | hessian2 / 1 个 |
| 连接 | direct 127.0.0.1:19302，提供者 127.0.0.1:19302，1 triple HTTP/2 connection per address; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider)，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 1（target.connections = number of independent Dubbo client instances (FrameworkModel + reference), one triple connection each; the reference 'connections' parameter is ignored by triple in Dubbo 3.3.6 (default: 1)） |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 dubbo default (shared client event loop: min(available cores + 1, 32) = 6) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 214397、错误 0、延迟 p99 27.132ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 487934 | 13941.0 | 4.612 | 6.816 | 8.536 | 12.190 | 19.235 | 0 | 26.5% | 41.9% | 65 / 58 | 0.12 / 0.07 | n/a | 是 |
| **2（正式）** | **504063** | **14401.8** | **4.530** | **6.341** | **7.307** | **10.166** | **35.226** | **0** | **23.7%** | **41.6%** | **63 / 62** | **0.11 / 0.07** | **n/a** | **是** |
| 3 | 505334 | 14438.1 | 4.518 | 6.361 | 7.324 | 9.519 | 32.195 | 0 | 23.7% | 41.5% | 68 / 58 | 0.11 / 0.07 | n/a | 是 |

正式轮：第 2 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 497.143（3.6%），p50_ms 0.094（2.1%），p99_ms 1.229（16.8%），p999_ms 2.671（28.1%）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 1 -> 1 | 0.050（0.050 / 0.000） | 2 -> 2 | 0.027（0.013 / 0.014） |
| biz_pool | — | — | 200 -> 200 | 2.696（2.622 / 0.074） |
| fjp | 5 -> 5 | 0.183（0.181 / 0.002） | — | — |
| timers | 1 -> 1 | 0.009（0.009 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.006（0.006 / 0.000） | 20 -> 20 | 0.006（0.006 / 0.000） |
| other | 3 -> 3 | 0.002（0.002 / 0.000） | 10 -> 10 | 0.000（0.000 / 0.000） |
| 合计 | | 0.250 | | 2.729 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19302]），经 direct 127.0.0.1:19302 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-closed64-grpc-c2-v1

> 标签：`archive` · 结果文件：`compare-closed64-grpc-c2-v1-20261022-014400.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，gRPC（grpc-java，Netty 传输，HTTP/2） 闭环 64 并发下的吞吐与延迟量级（连接数对齐：2 条连接）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T14:44:00.129148858Z → 2026-10-21T14:47:21.401092233Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

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

### 轮次

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

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19301]），经 direct 127.0.0.1:19301 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-closed64-grpc-v1

> 标签：`archive` · 结果文件：`compare-closed64-grpc-v1-20261022-013714.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，gRPC（grpc-java，Netty 传输，HTTP/2） 闭环 64 并发下的吞吐与延迟量级

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T14:37:14.714496747Z → 2026-10-21T14:40:36.417282519Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

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

### 轮次

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

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-closed64-rpc-direct-v1

> 标签：`archive` · 结果文件：`compare-closed64-rpc-direct-v1-20261022-020438.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，本框架 闭环 64 并发下的吞吐与延迟量级（服务端执行器 direct，非缺省）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:04:38.224175318Z → 2026-10-21T15:07:58.769207698Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 2（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 2313225、错误 0、延迟 p99 2.884ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 4879997 | 139428.5 | 0.425 | 0.779 | 1.076 | 2.513 | 34.275 | 0 | 60.2% | 27.4% | 83 / 72 | 0.18 / 0.08 | 0 | 是 |
| **2（正式）** | **4993506** | **142671.6** | **0.421** | **0.746** | **0.977** | **2.284** | **19.038** | **0** | **60.3%** | **27.2%** | **79 / 69** | **0.18 / 0.08** | **0** | **是** |
| 3 | 5094685 | 145562.4 | 0.414 | 0.724 | 0.941 | 2.232 | 32.096 | 0 | 60.6% | 27.5% | 81 / 69 | 0.17 / 0.09 | 0 | 是 |

正式轮：第 2 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 6133.943（4.4%），p50_ms 0.011（2.7%），p99_ms 0.135（14.3%），p999_ms 0.281（12.6%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.001 / 0.01 / 3.047 ms（4992561 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 0.039（0.037 / 0.003） | 3 -> 3 | 0.038（0.038 / 0.000） |
| fjp | 5 -> 5 | 0.204（0.194 / 0.011） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.001（0.001 / 0.000） | 20 -> 20 | 0.001（0.001 / 0.000） |
| other | 2 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 0.244 | | 0.039 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 非缺省配置：服务端业务执行器为 direct（框架缺省是 fixed）。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-closed64-rpc-v1

> 标签：`archive` · 结果文件：`compare-closed64-rpc-v1-20261022-012703.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，本框架 闭环 64 并发下的吞吐与延迟量级

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T14:27:03.071014213Z → 2026-10-21T14:30:23.982203398Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 2（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 1747257、错误 0、延迟 p99 3.011ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 3457345 | 98781.3 | 0.586 | 1.125 | 1.574 | 3.062 | 33.227 | 0 | 57.2% | 49.3% | 60 / 54 | 0.19 / 0.16 | 0 | 是 |
| **2（正式）** | **3568011** | **101943.2** | **0.573** | **1.077** | **1.458** | **2.789** | **15.385** | **0** | **57.2%** | **49.1%** | **58 / 54** | **0.19 / 0.16** | **0** | **是** |
| 3 | 3670244 | 104864.1 | 0.561 | 1.040 | 1.379 | 2.466 | 16.957 | 0 | 57.1% | 49.4% | 59 / 53 | 0.19 / 0.17 | 0 | 是 |

正式轮：第 2 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 6082.828（6.2%），p50_ms 0.025（4.5%），p99_ms 0.195（14.1%），p999_ms 0.596（24.2%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.08 / 0.204 / 5.308 ms（3567371 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 0.050（0.046 / 0.004） | 3 -> 3 | 0.081（0.081 / 0.000） |
| biz_pool | — | — | 10 -> 10 | 0.192（0.191 / 0.001） |
| fjp | 5 -> 5 | 0.223（0.213 / 0.009） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| jvm | 21 -> 20 | 0.001（0.001 / 0.000） | 20 -> 20 | 0.001（0.001 / 0.000） |
| other | 2 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 0.274 | | 0.274 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-open10k-dubbo-c2-v1

> 标签：`archive` · 结果文件：`compare-open10k-dubbo-c2-v1-20261022-020106.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，Dubbo 3（triple 协议） 开环 10000 req/s 下的延迟分布（连接数对齐：2 条连接）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:01:06.515049015Z → 2026-10-21T15:04:29.323951899Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

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

### 轮次

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

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19303]），经 direct 127.0.0.1:19303 发现；卡片声明的注册中心：none (--direct)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-open10k-dubbo-v1

> 标签：`archive` · 结果文件：`compare-open10k-dubbo-v1-20261022-015412.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，Dubbo 3（triple 协议） 开环 10000 req/s 下的延迟分布

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T14:54:12.820512879Z → 2026-10-21T14:57:34.766232771Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | dubbo · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | hessian2 / 1 个 |
| 连接 | direct 127.0.0.1:19302，提供者 127.0.0.1:19302，1 triple HTTP/2 connection per address; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider)，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 1（target.connections = number of independent Dubbo client instances (FrameworkModel + reference), one triple connection each; the reference 'connections' parameter is ignored by triple in Dubbo 3.3.6 (default: 1)） |
| 负载模型 | 开环（open），10000 req/s 等间隔发出，在途上限 20000 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |
| harness 选项 | 开环执行者 pool（64 个虚拟线程工作者，有界 FIFO 队列） · 客户端 IO 线程 dubbo default (shared client event loop: min(available cores + 1, 32) = 6) · 调度线程绑核 5（第 1 轮读回：pinned，其余线程 6-9） |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 200000、错误 3956、延迟 p99 2290.090ms、最大在途 20000 |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（正式）** | **350000** | **10000.0** | **0.776** | **1.742** | **22.233** | **30.753** | **41.746** | **0** | **5.308** | **0.023** | **59.1%** | **42.4%** | **42 / 45** | **1.08 / 0.65** | **n/a** | **是** |
| 2 | 350001 | 10000.0 | 0.772 | 1.733 | 6.849 | 27.099 | 37.487 | 0 | 4.596 | 0.026 | 58.5% | 43.0% | 43 / 44 | 1.08 / 0.64 | n/a | 是 |
| 3 | 350002 | 10000.1 | 0.785 | 2.513 | 72.024 | 106.955 | 111.215 | 0 | 7.074 | 0.064 | 57.8% | 42.7% | 40 / 41 | 1.05 / 0.62 | n/a | 是 |

正式轮：第 1 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.057（0.0%），p50_ms 0.013（1.7%），p99_ms 65.175（951.6%），p999_ms 79.856（294.7%）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 1 -> 1 | 0.306（0.280 / 0.026） | 3 -> 3 | 0.096（0.094 / 0.002） |
| biz_pool | — | — | 200 -> 200 | 1.358（1.355 / 0.003） |
| fjp | 5 -> 5 | 1.899（1.841 / 0.058） | — | — |
| timers | 1 -> 1 | 0.155（0.153 / 0.002） | — | — |
| harness | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| jvm | 21 -> 20 | 0.008（0.007 / 0.001） | 20 -> 20 | 0.007（0.007 / 0.000） |
| other | 6 -> 4 | 0.002（0.002 / 0.000） | 11 -> 10 | 0.000（0.000 / 0.000） |
| 合计 | | 2.370 | | 1.461 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19302]），经 direct 127.0.0.1:19302 发现；卡片声明的注册中心：none (--direct)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-open10k-grpc-c2-v1

> 标签：`archive` · 结果文件：`compare-open10k-grpc-c2-v1-20261022-014722.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，gRPC（grpc-java，Netty 传输，HTTP/2） 开环 10000 req/s 下的延迟分布（连接数对齐：2 条连接）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T14:47:22.020213340Z → 2026-10-21T14:50:43.186936660Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | grpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19301，提供者 127.0.0.1:19301，2 ManagedChannels = 2 HTTP/2 connections, calls take a channel round-robin; concurrent calls are multiplexed streams，重试 0 |
| 连接数（target.connections） | 2 → 实际 2（target.connections = number of ManagedChannels to the provider (default 1)） |
| 负载模型 | 开环（open），10000 req/s 等间隔发出，在途上限 20000 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |
| harness 选项 | 开环执行者 pool（64 个虚拟线程工作者，有界 FIFO 队列） · 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5（第 1 轮读回：pinned，其余线程 6-9） |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 200000、错误 0、延迟 p99 594.018ms、最大在途 5997 |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 350000 | 10000.0 | 0.595 | 0.931 | 1.466 | 6.005 | 13.492 | 0 | 1.370 | 0.035 | 63.1% | 50.5% | 17 / 11 | 4.04 / 2.49 | n/a | 是 |
| **2（正式）** | **350003** | **10000.1** | **0.585** | **0.884** | **1.338** | **8.511** | **33.014** | **0** | **1.239** | **0.039** | **60.9%** | **50.1%** | **13 / 11** | **4.07 / 2.53** | **n/a** | **是** |
| 3 | 349999 | 10000.0 | 0.585 | 0.877 | 1.278 | 12.804 | 35.914 | 0 | 1.202 | 0.031 | 62.1% | 50.0% | 8 / 8 | 4.08 / 2.54 | n/a | 是 |

正式轮：第 2 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.115（0.0%），p50_ms 0.010（1.7%），p99_ms 0.188（14.7%），p999_ms 6.799（113.2%）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 10 -> 10 | 1.838（1.663 / 0.175） | 5 -> 5 | 0.757（0.747 / 0.010） |
| biz_pool | — | — | 38 -> 45 | 2.847（2.833 / 0.015） |
| fjp | 5 -> 5 | 2.333（2.200 / 0.133） | — | — |
| timers | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| harness | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.004（0.004 / 0.000） | 20 -> 20 | 0.004（0.004 / 0.000） |
| other | 3 -> 3 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 4.175 | | 3.608 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19301]），经 direct 127.0.0.1:19301 发现；卡片声明的注册中心：none (--direct)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-open10k-grpc-v1

> 标签：`archive` · 结果文件：`compare-open10k-grpc-v1-20261022-014037.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，gRPC（grpc-java，Netty 传输，HTTP/2） 开环 10000 req/s 下的延迟分布

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T14:40:37.078703658Z → 2026-10-21T14:43:58.225069384Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | grpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，1 ManagedChannel = 1 HTTP/2 connection; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider)，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 1（target.connections = number of ManagedChannels to the provider (default 1)） |
| 负载模型 | 开环（open），10000 req/s 等间隔发出，在途上限 20000 |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |
| harness 选项 | 开环执行者 pool（64 个虚拟线程工作者，有界 FIFO 队列） · 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5（第 1 轮读回：pinned，其余线程 6-9） |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 200000、错误 0、延迟 p99 676.856ms、最大在途 6838 |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 350002 | 10000.1 | 1.128 | 4.293 | 45.646 | 82.051 | 87.622 | 0 | 6.832 | 0.051 | 51.7% | 36.7% | 15 / 12 | 3.26 / 0.50 | n/a | 是 |
| 2 | 349996 | 9999.9 | 1.077 | 2.177 | 5.677 | 20.955 | 32.883 | 0 | 4.600 | 0.032 | 51.0% | 36.1% | 9 / 8 | 3.33 / 0.54 | n/a | 是 |
| **3（正式）** | **349989** | **9999.7** | **1.226** | **6.136** | **20.955** | **37.552** | **44.728** | **0** | **7.741** | **0.088** | **52.1%** | **36.6%** | **11 / 8** | **3.22 / 0.45** | **n/a** | **是** |

正式轮：第 3 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.371（0.0%），p50_ms 0.149（13.8%），p99_ms 39.969（704.1%），p999_ms 61.096（291.6%）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 10 -> 10 | 1.259（1.243 / 0.016） | 3 -> 3 | 0.005（0.002 / 0.003） |
| biz_pool | — | — | 33 -> 34 | 2.847（2.846 / 0.001） |
| fjp | 5 -> 5 | 1.725（1.657 / 0.067） | — | — |
| timers | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| harness | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.004（0.004 / 0.000） | 20 -> 20 | 0.004（0.004 / 0.000） |
| other | 3 -> 3 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 2.988 | | 2.856 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-open10k-rpc-direct-v1

> 标签：`archive` · 结果文件：`compare-open10k-rpc-direct-v1-20261022-020759.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，本框架 开环 10000 req/s 下的延迟分布（服务端执行器 direct，非缺省）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:07:59.418395663Z → 2026-10-21T15:11:20.169345608Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 2（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 开环（open），10000 req/s 等间隔发出，在途上限 20000 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |
| harness 选项 | 开环执行者 pool（64 个虚拟线程工作者，有界 FIFO 队列） · 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5（第 1 轮读回：pinned，其余线程 6-9） |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 200000、错误 0、延迟 p99 90.178ms、最大在途 1121 |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（正式）** | **350000** | **10000.0** | **0.251** | **0.359** | **0.530** | **3.133** | **14.967** | **0** | **0.446** | **0.013** | **49.6%** | **21.7%** | **8 / 6** | **1.66 / 0.99** | **0** | **是** |
| 2 | 350000 | 10000.0 | 0.249 | 0.353 | 0.470 | 1.826 | 11.452 | 0 | 0.408 | 0.011 | 48.7% | 19.5% | 7 / 8 | 1.66 / 0.99 | 0 | 是 |
| 3 | 350000 | 10000.0 | 0.250 | 0.362 | 0.575 | 10.248 | 40.141 | 0 | 0.500 | 0.012 | 55.7% | 20.8% | 5 / 7 | 1.66 / 0.98 | 0 | 是 |

正式轮：第 1 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.000（0.0%），p50_ms 0.002（0.8%），p99_ms 0.105（22.3%），p999_ms 8.422（461.2%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.006 / 0.022 / 0.362 ms（349769 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 1.414（1.399 / 0.015） | 5 -> 5 | 0.983（0.975 / 0.008） |
| fjp | 5 -> 5 | 1.941（1.935 / 0.006） | — | — |
| timers | 2 -> 2 | 0.011（0.011 / 0.000） | — | — |
| harness | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.004（0.004 / 0.000） | 20 -> 20 | 0.003（0.003 / 0.000） |
| other | 3 -> 3 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 3.370 | | 0.986 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 非缺省配置：服务端业务执行器为 direct（框架缺省是 fixed）。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## compare-open10k-rpc-v1

> 标签：`archive` · 结果文件：`compare-open10k-rpc-v1-20261022-013024.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，本框架 开环 10000 req/s 下的延迟分布

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T14:30:24.659749822Z → 2026-10-21T14:33:45.743127243Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 2（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 开环（open），10000 req/s 等间隔发出，在途上限 20000 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |
| harness 选项 | 开环执行者 pool（64 个虚拟线程工作者，有界 FIFO 队列） · 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5（第 1 轮读回：pinned，其余线程 6-9） |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 200000、错误 0、延迟 p99 150.864ms、最大在途 1714 |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 349998 | 9999.9 | 0.444 | 0.640 | 1.343 | 19.579 | 32.096 | 0 | 0.839 | 0.085 | 50.6% | 38.4% | 8 / 7 | 1.75 / 1.55 | 0 | 是 |
| **2（正式）** | **350000** | **10000.0** | **0.440** | **0.602** | **0.798** | **14.475** | **32.621** | **0** | **0.702** | **0.034** | **50.0%** | **37.6%** | **8 / 7** | **1.77 / 1.58** | **0** | **是** |
| 3 | 350001 | 10000.0 | 0.375 | 0.570 | 0.776 | 7.975 | 33.194 | 0 | 0.688 | 0.018 | 47.8% | 36.8% | 9 / 5 | 1.82 / 1.67 | 0 | 是 |

正式轮：第 2 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.086（0.0%），p50_ms 0.069（18.4%），p99_ms 0.567（73.1%），p999_ms 11.604（145.5%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.07 / 0.147 / 21.496 ms（349940 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 1.084（1.039 / 0.046） | 5 -> 5 | 1.173（1.171 / 0.003） |
| biz_pool | — | — | 10 -> 10 | 0.999（0.999 / 0.000） |
| fjp | 5 -> 5 | 1.922（1.899 / 0.024） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| harness | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.003（0.003 / 0.000） | 20 -> 20 | 0.003（0.003 / 0.000） |
| other | 3 -> 3 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 3.009 | | 2.175 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## crossval-http-closed-capacity-v1

> 标签：`archive` · 结果文件：`crossval-http-closed-capacity-v1-20261022-024133.json` · 判定：**通过**

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:41:33.635327629Z → 2026-10-21T15:44:56.001492979Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

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
| harness 选项 | HTTP 客户端 netty · HTTP IO 线程 5 · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 2161260、错误 0、延迟 p99 3.680ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（正式）** | **5248909** | **131222.7** | **0.418** | **0.981** | **1.531** | **4.411** | **158.728** | **0** | **97.4%** | **74.5%** | **30 / 28** | **1.01 / 1.00** | **0** | **是** |
| 2 | 5199622 | 129990.6 | 0.423 | 0.991 | 1.523 | 4.256 | 39.780 | 0 | 97.7% | 74.1% | 29 / 28 | 1.01 / 1.00 | 0 | 是 |
| 3 | 5283379 | 132084.5 | 0.420 | 0.970 | 1.414 | 3.914 | 44.237 | 0 | 98.1% | 74.2% | 29 / 28 | 1.01 / 1.00 | 0 | 是 |

正式轮：第 1 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 2093.925（1.6%），p50_ms 0.005（1.2%），p99_ms 0.117（8.3%），p999_ms 0.497（12.7%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max — / — / — ms（0 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 5 -> 5 | 0.390（0.012 / 0.377） | 12 -> 12 | 0.365（0.319 / 0.046） |
| fjp | 5 -> 5 | 0.470（0.462 / 0.008） | 2 -> 1 | 0.000（0.000 / 0.000） |
| timers | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| jvm | 21 -> 21 | 0.001（0.000 / 0.000） | 21 -> 20 | 0.000（0.000 / 0.000） |
| other | 3 -> 2 | 0.000（0.000 / 0.000） | 3 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 0.861 | | 0.365 |

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

### crossval-http-open-v1-20261022-023508.json

> 标签：`archive` · 结果文件：`crossval-http-open-v1-20261022-023508.json` · 判定：**通过**

**问题**：校验 harness 开环的达成吞吐、服务时间与节拍质量

#### 环境

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

#### 口径

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

#### 轮次

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

#### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

#### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19180]），经 http://127.0.0.1:19180/echo 发现；卡片声明的注册中心：none (direct HTTP URL)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

### crossval-http-open-v1-20261022-031809.json

> 标签：`crossval` · 结果文件：`crossval-http-open-v1-20261022-031809.json` · 判定：**通过**

**问题**：校验 harness 开环的达成吞吐、服务时间与节拍质量

#### 环境

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
| git | d8dc78b（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T16:18:09.436860180Z → 2026-10-21T16:21:32.076333756Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

#### 口径

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
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 100000、错误 0、延迟 p99 0.481ms、最大在途 37 |

#### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 199999 | 5000.0 | 0.207 | 0.334 | 0.433 | 1.595 | 151.388 | 0 | 0.358 | 0.014 | 24.7% | 23.7% | 0 / 3 | 1.00 / 1.00 | 0 | 是 |
| **2（正式）** | **200001** | **5000.0** | **0.188** | **0.303** | **0.402** | **2.666** | **20.595** | **0** | **0.321** | **0.013** | **24.5%** | **22.9%** | **0 / 2** | **1.00 / 1.00** | **0** | **是** |
| 3 | 200000 | 5000.0 | 0.179 | 0.295 | 0.390 | 1.084 | 10.633 | 0 | 0.315 | 0.012 | 24.0% | 11.3% | 8 / 2 | 1.00 / 1.00 | 0 | 是 |

正式轮：第 2 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.050（0.0%），p50_ms 0.028（15.6%），p99_ms 0.043（11.0%），p999_ms 1.582（145.9%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max — / — / — ms（0 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | — | — | 12 -> 12 | 1.001（0.988 / 0.013） |
| fjp | 5 -> 5 | -0.006（-0.004 / -0.002） | — | — |
| timers | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| harness | 65 -> 0 | -1.005（-1.005 / -0.001） | — | — |
| jvm | 20 -> 20 | 0.006（0.006 / 0.000） | 20 -> 20 | 0.006（0.006 / 0.000） |
| other | 7 -> 6 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | -1.005 | | 1.007 |

#### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

#### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19180]），经 http://127.0.0.1:19180/echo 发现；卡片声明的注册中心：none (direct HTTP URL)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

### crossval-http-open-v1-20261022-032557.json

> 标签：`crossval` · 结果文件：`crossval-http-open-v1-20261022-032557.json` · 判定：**通过**

**问题**：校验 harness 开环的达成吞吐、服务时间与节拍质量

#### 环境

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
| git | d8dc78b（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T16:25:57.951165425Z → 2026-10-21T16:29:20.292115435Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

#### 口径

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
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 100000、错误 0、延迟 p99 0.587ms、最大在途 92 |

#### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 199999 | 5000.0 | 0.183 | 0.293 | 0.399 | 4.069 | 42.205 | 0 | 0.315 | 0.010 | 22.0% | 26.1% | 0 / 2 | 1.00 / 1.00 | 0 | 是 |
| **2（正式）** | **200000** | **5000.0** | **0.182** | **0.296** | **0.401** | **2.433** | **13.746** | **0** | **0.315** | **0.011** | **37.1%** | **14.9%** | **0 / 2** | **1.00 / 1.00** | **0** | **是** |
| 3 | 200000 | 5000.0 | 0.181 | 0.311 | 0.434 | 2.671 | 17.170 | 0 | 0.340 | 0.011 | 23.3% | 13.0% | 4 / 2 | 1.00 / 1.00 | 0 | 是 |

正式轮：第 2 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.025（0.0%），p50_ms 0.002（1.1%），p99_ms 0.035（8.8%），p999_ms 1.636（67.2%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max — / — / — ms（0 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | — | — | 12 -> 12 | 0.986（0.985 / 0.001） |
| fjp | 5 -> 4 | -0.006（-0.004 / -0.002） | — | — |
| timers | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| harness | 65 -> 0 | -1.023（-1.002 / -0.021） | — | — |
| jvm | 20 -> 20 | 0.006（0.006 / 0.000） | 20 -> 20 | 0.006（0.006 / 0.000） |
| other | 7 -> 6 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | -1.023 | | 0.992 |

#### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

#### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19180]），经 http://127.0.0.1:19180/echo 发现；卡片声明的注册中心：none (direct HTTP URL)。
- 开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。
- 开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## loopback-latency-v1

> 标签：`archive` · 结果文件：`loopback-latency-v1-20261022-023142.json` · 判定：**通过**

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:31:42.824388436Z → 2026-10-21T15:35:04.009989262Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | zk://127.0.0.1:2181，提供者 127.0.0.1:9000，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 2（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 开环（open），10000 req/s 等间隔发出，在途上限 20000 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 docker-compose/zookeeper |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 计划发出时刻（开环，修正 coordinated omission） |
| harness 选项 | 开环执行者 pool（64 个虚拟线程工作者，有界 FIFO 队列） · 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5（第 1 轮读回：pinned，其余线程 6-9） |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 200000、错误 0、延迟 p99 90.702ms、最大在途 1026 |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（正式）** | **350001** | **10000.0** | **0.382** | **0.578** | **0.782** | **2.059** | **5.526** | **0** | **0.710** | **0.020** | **56.9%** | **37.4%** | **8 / 6** | **1.81 / 1.66** | **0** | **是** |
| 2 | 350001 | 10000.0 | 0.399 | 0.626 | 1.166 | 8.385 | 24.658 | 0 | 0.955 | 0.034 | 50.4% | 38.1% | 16 / 9 | 1.78 / 1.61 | 0 | 是 |
| 3 | 349998 | 9999.9 | 0.380 | 0.573 | 0.759 | 2.022 | 7.164 | 0 | 0.681 | 0.024 | 49.6% | 38.0% | 5 / 8 | 1.82 / 1.66 | 0 | 是 |

正式轮：第 1 轮（开环：窗口 p99 的中位数那一轮）。各轮极差：throughput_rps 0.086（0.0%），p50_ms 0.019（5.0%），p99_ms 0.407（53.6%），p999_ms 6.363（314.7%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.061 / 0.14 / 2.621 ms（349662 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 1.154（1.102 / 0.053） | 5 -> 5 | 1.292（1.286 / 0.005） |
| biz_pool | — | — | 10 -> 10 | 1.001（0.999 / 0.002） |
| fjp | 5 -> 5 | 1.935（1.912 / 0.022） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| harness | 1 -> 1 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.004（0.004 / 0.000） | 20 -> 20 | 0.003（0.003 / 0.000） |
| other | 9 -> 9 | 0.000（0.000 / 0.000） | 8 -> 8 | 0.000（0.000 / 0.000） |
| 合计 | | 3.093 | | 2.296 |

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

> 标签：`archive` · 结果文件：`loopback-throughput-v1-20261022-022820.json` · 判定：**通过**

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:28:20.737128534Z → 2026-10-21T15:31:42.158218921Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | zk://127.0.0.1:2181，提供者 127.0.0.1:9000，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 2（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 docker-compose/zookeeper |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 1721779、错误 0、延迟 p99 3.129ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 3597224 | 102777.8 | 0.573 | 1.046 | 1.401 | 2.955 | 16.589 | 0 | 59.2% | 49.9% | 64 / 57 | 0.20 / 0.17 | 0 | 是 |
| 2 | 3648965 | 104256.1 | 0.570 | 1.028 | 1.347 | 2.609 | 7.336 | 0 | 60.0% | 49.6% | 63 / 57 | 0.20 / 0.17 | 0 | 是 |
| **3（正式）** | **3637162** | **103918.9** | **0.570** | **1.034** | **1.365** | **2.531** | **7.901** | **0** | **59.7%** | **49.8%** | **65 / 52** | **0.20 / 0.17** | **0** | **是** |

正式轮：第 3 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 1478.314（1.4%），p50_ms 0.003（0.5%），p99_ms 0.054（4.0%），p999_ms 0.424（16.8%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.08 / 0.201 / 6.095 ms（3635592 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 0.049（0.045 / 0.004） | 3 -> 3 | 0.082（0.082 / 0.000） |
| biz_pool | — | — | 10 -> 10 | 0.188（0.187 / 0.001） |
| fjp | 5 -> 5 | 0.232（0.221 / 0.011） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.001（0.001 / 0.000） | 20 -> 20 | 0.001（0.001 / 0.000） |
| other | 8 -> 8 | 0.000（0.000 / 0.000） | 8 -> 8 | 0.000（0.000 / 0.000） |
| 合计 | | 0.282 | | 0.271 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:9000]），经 zk://127.0.0.1:2181 发现；卡片声明的注册中心：docker-compose/zookeeper。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## sweep-c1-closed64-rpc-direct-v1

> 标签：`archive` · 结果文件：`sweep-c1-closed64-rpc-direct-v1-20261022-021122.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，本框架 闭环 64 并发下的吞吐与两侧 CPU（每节点 1 条连接：单连接上限；服务端执行器 direct，非缺省）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:11:22.748049778Z → 2026-10-21T15:14:43.434044859Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 1，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 1 → 实际 1（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 1676526、错误 0、延迟 p99 2.951ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 4247880 | 121368.0 | 0.499 | 0.875 | 1.245 | 2.245 | 36.733 | 0 | 39.8% | 18.3% | 70 / 58 | 0.08 / 0.05 | 0 | 是 |
| 2 | 4378984 | 125113.8 | 0.492 | 0.821 | 1.121 | 2.245 | 9.830 | 0 | 40.1% | 15.3% | 68 / 61 | 0.08 / 0.05 | 0 | 是 |
| **3（正式）** | **4358934** | **124541.0** | **0.494** | **0.827** | **1.144** | **2.212** | **21.709** | **0** | **39.5%** | **17.2%** | **69 / 59** | **0.08 / 0.05** | **0** | **是** |

正式轮：第 3 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 3745.829（3.1%），p50_ms 0.007（1.4%），p99_ms 0.124（11.1%），p999_ms 0.033（1.5%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.001 / 0.007 / 1.925 ms（4359212 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 1 -> 1 | 0.014（0.014 / 0.000） | 2 -> 2 | 0.022（0.022 / 0.000） |
| fjp | 5 -> 5 | 0.166（0.164 / 0.002） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.001（0.001 / 0.000） | 20 -> 20 | 0.001（0.001 / 0.000） |
| other | 2 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 0.181 | | 0.023 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 非缺省配置：服务端业务执行器为 direct（框架缺省是 fixed）。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## sweep-c1-closed64-rpc-v1

> 标签：`archive` · 结果文件：`sweep-c1-closed64-rpc-v1-20261022-013349.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，本框架 闭环 64 并发下的吞吐与两侧 CPU（每节点 1 条连接：单连接上限）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T14:33:49.726626831Z → 2026-10-21T14:37:10.285449656Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 1，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 1 → 实际 1（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 闭环（closed），并发 64 个虚拟用户 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 1172221、错误 0、延迟 p99 3.838ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 3276331 | 93609.5 | 0.629 | 1.144 | 1.522 | 2.564 | 30.245 | 0 | 38.4% | 39.0% | 55 / 47 | 0.09 / 0.10 | 0 | 是 |
| **2（正式）** | **3247004** | **92771.5** | **0.633** | **1.155** | **1.540** | **2.679** | **27.984** | **0** | **38.1%** | **38.8%** | **54 / 47** | **0.09 / 0.10** | **0** | **是** |
| 3 | 2855594 | 81588.4 | 0.712 | 1.349 | 1.847 | 3.070 | 30.523 | 0 | 37.7% | 38.8% | 49 / 43 | 0.08 / 0.10 | 0 | 是 |

正式轮：第 2 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 12021.057（14.7%），p50_ms 0.083（13.2%），p99_ms 0.325（21.4%），p999_ms 0.506（19.7%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.076 / 0.16 / 2.556 ms（3245282 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 1 -> 1 | 0.017（0.017 / 0.000） | 2 -> 2 | 0.040（0.040 / 0.000） |
| biz_pool | — | — | 10 -> 10 | 0.215（0.215 / 0.000） |
| fjp | 5 -> 5 | 0.172（0.171 / 0.001） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.001（0.001 / 0.000） | 20 -> 20 | 0.001（0.001 / 0.000） |
| other | 2 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 0.190 | | 0.256 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## sweep-c4-closed128-rpc-v1

> 标签：`archive` · 结果文件：`sweep-c4-closed128-rpc-v1-20261022-022128.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，本框架 闭环 128 并发下的吞吐与两侧 CPU（并发扫描）（每节点 4 条连接）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:21:28.545135233Z → 2026-10-21T15:24:48.983079377Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 4，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 4 → 实际 4（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 闭环（closed），并发 128 个虚拟用户 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 2237626、错误 0、延迟 p99 5.112ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（正式）** | **4849846** | **138567.0** | **0.799** | **1.872** | **2.830** | **5.100** | **33.751** | **0** | **78.2%** | **61.5%** | **84 / 79** | **0.38 / 0.23** | **0** | **是** |
| 2 | 4831774 | 138050.7 | 0.803 | 1.872 | 2.861 | 5.071 | 29.573 | 0 | 78.4% | 61.5% | 84 / 81 | 0.38 / 0.23 | 0 | 是 |
| 3 | 4858483 | 138813.8 | 0.795 | 1.885 | 2.904 | 5.304 | 15.679 | 0 | 78.2% | 62.1% | 85 / 82 | 0.40 / 0.24 | 0 | 是 |

正式轮：第 1 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 763.114（0.6%），p50_ms 0.008（1.0%），p99_ms 0.074（2.6%），p999_ms 0.233（4.6%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.086 / 0.362 / 30.54 ms（4845782 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 4 -> 4 | 0.114（0.088 / 0.026） | 9 -> 9 | 0.119（0.115 / 0.004） |
| biz_pool | — | — | 10 -> 10 | 0.118（0.111 / 0.007） |
| fjp | 5 -> 5 | 0.132（0.120 / 0.012） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| jvm | 21 -> 20 | 0.001（0.001 / 0.000） | 20 -> 20 | 0.001（0.001 / 0.000） |
| other | 3 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 0.247 | | 0.238 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## sweep-c8-closed128-rpc-v1

> 标签：`archive` · 结果文件：`sweep-c8-closed128-rpc-v1-20261022-022449.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，本框架 闭环 128 并发下的吞吐与两侧 CPU（并发扫描）（每节点 8 条连接）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:24:49.565398572Z → 2026-10-21T15:28:10.018650642Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 8，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 8 → 实际 8（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 闭环（closed），并发 128 个虚拟用户 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 1836625、错误 0、延迟 p99 6.644ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 4326517 | 123614.8 | 0.826 | 2.394 | 4.542 | 7.373 | 43.516 | 0 | 85.6% | 69.2% | 83 / 78 | 0.61 / 0.40 | 0 | 是 |
| **2（正式）** | **4418552** | **126244.3** | **0.814** | **2.314** | **4.420** | **7.320** | **35.193** | **0** | **85.6%** | **69.7%** | **85 / 80** | **0.61 / 0.40** | **0** | **是** |
| 3 | 4482114 | 128060.4 | 0.810 | 2.232 | 4.301 | 7.135 | 33.817 | 0 | 86.1% | 69.6% | 83 / 82 | 0.60 / 0.40 | 0 | 是 |

正式轮：第 2 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 4445.629（3.6%），p50_ms 0.016（2.0%），p99_ms 0.241（5.6%），p999_ms 0.238（3.3%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.094 / 0.537 / 15.204 ms（4412907 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 8 -> 8 | 0.302（0.246 / 0.056） | 11 -> 11 | 0.275（0.252 / 0.023） |
| biz_pool | — | — | 10 -> 10 | 0.114（0.093 / 0.021） |
| fjp | 5 -> 5 | 0.131（0.107 / 0.024） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.001（0.001 / 0.000） | 20 -> 20 | 0.001（0.001 / 0.000） |
| other | 2 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 0.434 | | 0.390 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## sweep-closed128-rpc-v1

> 标签：`archive` · 结果文件：`sweep-closed128-rpc-v1-20261022-021445.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，本框架 闭环 128 并发下的吞吐与两侧 CPU（并发扫描）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:14:45.806529258Z → 2026-10-21T15:18:06.396910047Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 2（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 闭环（closed），并发 128 个虚拟用户 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 2051402、错误 0、延迟 p99 5.706ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 4856959 | 138770.3 | 0.850 | 1.598 | 2.265 | 4.280 | 18.399 | 0 | 63.9% | 50.8% | 84 / 74 | 0.15 / 0.11 | 0 | 是 |
| **2（正式）** | **4940656** | **141161.6** | **0.837** | **1.553** | **2.191** | **4.641** | **43.516** | **0** | **63.9%** | **49.9%** | **84 / 72** | **0.15 / 0.11** | **0** | **是** |
| 3 | 4962092 | 141774.1 | 0.839 | 1.544 | 2.093 | 3.385 | 26.673 | 0 | 64.4% | 50.3% | 87 / 73 | 0.15 / 0.11 | 0 | 是 |

正式轮：第 2 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 3003.800（2.2%），p50_ms 0.013（1.6%），p99_ms 0.172（8.2%），p999_ms 1.256（37.1%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.082 / 0.234 / 18.35 ms（4939251 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 0.026（0.024 / 0.002） | 3 -> 3 | 0.046（0.045 / 0.000） |
| biz_pool | — | — | 10 -> 10 | 0.131（0.131 / 0.001） |
| fjp | 5 -> 5 | 0.166（0.155 / 0.011） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 21 | 0.001（0.001 / 0.000） | 20 -> 20 | 0.001（0.001 / 0.000） |
| other | 2 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 0.193 | | 0.178 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## sweep-closed256-rpc-v1

> 标签：`archive` · 结果文件：`sweep-closed256-rpc-v1-20261022-021807.json` · 判定：**通过**

**问题**：同机 loopback、1KB、单提供者、直连，本框架 闭环 256 并发下的吞吐与两侧 CPU（并发扫描）

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
| git | d23e39d（干净） |
| 主机名 | localhost |
| 电源 / 调频 | AC1=1 / n/a |
| 时间 | 2026-10-21T15:18:07.017965080Z → 2026-10-21T15:21:27.472608476Z |
| harness | rpc-bench-e2e 1.0.0-SNAPSHOT |

### 口径

| 项 | 值 |
|---|---|
| 目标 | rpc · `BenchService#echo` · bytes，1024 B（固定种子随机内容） |
| 序列化 / 提供者 | protobuf / 1 个 |
| 连接 | direct 127.0.0.1:19300，提供者 127.0.0.1:19300，每节点连接 2，负载均衡 roundrobin，重试 0 |
| 连接数（target.connections） | 缺省 → 实际 2（target.connections = RpcClientConfig.connectionsPerNode (default 2)） |
| 负载模型 | 闭环（closed），并发 256 个虚拟用户 |
| 负载均衡 / 容错 / 重试 | roundrobin / failfast / 0（来源 cluster=default, load_balance=default, retries=default） |
| 时长 | 60s / 轮，预热 20s，统计窗口 [20s, 55s]，共 3 轮 |
| 超时 | 1s |
| 隔离 | client separate_jvm · server separate_jvm · 同机 是 · 注册中心 none (--direct) |
| 直方图 | HdrHistogram，3 位有效数字，上限 10s；只统计完成时刻落在窗口内的成功调用 |
| 延迟起点 | 调用发出前（闭环） |
| harness 选项 | 客户端 IO 线程 default (Netty: 2 x available cores = 10) · 调度线程绑核 5 |
| 预热段 | 20s（load.warmup），第 1 轮之前以同样负载跑、不计入任何轮：发出 2823513、错误 0、延迟 p99 5.882ms |

### 轮次

| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 错误（窗口内） | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 暂停读次数 | 有效 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 5658618 | 161674.8 | 1.484 | 2.775 | 3.734 | 5.595 | 11.977 | 0 | 61.8% | 48.2% | 107 / 92 | 0.13 / 0.07 | 0 | 是 |
| 2 | 5700826 | 162880.7 | 1.468 | 2.767 | 3.721 | 5.648 | 24.609 | 0 | 61.6% | 48.5% | 105 / 92 | 0.13 / 0.07 | 0 | 是 |
| **3（正式）** | **5687438** | **162498.2** | **1.474** | **2.750** | **3.701** | **5.915** | **35.652** | **0** | **61.7%** | **48.5%** | **103 / 88** | **0.13 / 0.07** | **0** | **是** |

正式轮：第 3 轮（闭环：窗口吞吐的中位数那一轮）。各轮极差：throughput_rps 1205.943（0.7%），p50_ms 0.016（1.1%），p99_ms 0.033（0.9%），p999_ms 0.320（5.7%）。

服务端业务执行器（正式轮统计窗口内）：过载拒绝 0，停机拒绝 0，排队等待 p50 / p99 / max 0.092 / 0.328 / 8.978 ms（5686755 个请求；到达 → 业务线程开始执行）。

上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：

| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） |
|---|---|---|---|---|
| netty_io | 2 -> 2 | 0.023（0.022 / 0.001） | 5 -> 5 | 0.025（0.024 / 0.000） |
| biz_pool | — | — | 10 -> 10 | 0.106（0.106 / 0.001） |
| fjp | 5 -> 5 | 0.115（0.107 / 0.008） | — | — |
| timers | 2 -> 2 | 0.000（0.000 / 0.000） | — | — |
| jvm | 20 -> 20 | 0.001（0.001 / 0.000） | 20 -> 20 | 0.001（0.001 / 0.000） |
| other | 2 -> 2 | 0.000（0.000 / 0.000） | 2 -> 2 | 0.000（0.000 / 0.000） |
| 合计 | | 0.139 | | 0.132 |

### 断言

| 断言 | 阈值 | 实测 | 口径 | 结果 |
|---|---|---|---|---|
| `errors_max` | 0 | 0 | 全部轮次窗口内错误之和 | 通过 |

### 声明

- 数字来自固定的一台本机（localhost，AMD Ryzen 5 4600H with Radeon Graphics），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。
- 拓扑：client 与 server 是两个独立 JVM，同一台机器（loopback），1 个提供者（[127.0.0.1:19300]），经 direct 127.0.0.1:19300 发现；卡片声明的注册中心：none (--direct)。
- 闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。
- 错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。

## 交叉验证判定

harness 与 wrk / wrk2 打同一个 HTTP 目标的比较结果（`crossval` 子命令），口径见 docs/benchmarking.md「交叉验证」。

| 结果文件 | 模式 | 工具 | harness 结果 | 判定 |
|---|---|---|---|---|
| [crossval-open-20261022-024132.json](#crossval-open-20261022-024132json) | open (measurement) | wrk2 | crossval-http-open-v1-20261022-023508.json | **PASS** |
| [crossval-closed-20261022-024556.json](#crossval-closed-20261022-024556json) | closed (capacity) | wrk | crossval-http-closed-capacity-v1-20261022-024133.json | **PASS** |
| [crossval-open-20261022-032433.json](#crossval-open-20261022-032433json) | open (measurement) | wrk2 | crossval-http-open-v1-20261022-031809.json | **PASS** |
| [crossval-open-20261022-033221.json](#crossval-open-20261022-033221json) | open (measurement) | wrk2 | crossval-http-open-v1-20261022-032557.json | **PASS** |

### crossval-open-20261022-024132.json

> 判定：**PASS** · 创建于 2026-10-22T02:41:31.949096809+11:00 · harness：`crossval-http-open-v1-20261022-023508.json`（git d23e39d（干净））· 工具命令：`taskset -c 5-9 wrk2 -t 4 -c 64 -R 5000 --latency -U -s bench/bin/post-1k.lua -d 40s http://127.0.0.1:19180/echo`

**问题**：同一个 HTTP 目标、开环同一 rate，harness 与 wrk2 的发压节拍与测量是否一致——校验的是 harness 本身

| 组 | 判据 | 实测 | 相对偏差 | 结果 |
|---|---|---|---|---|
| 前提 | harness 有效轮 ≥ 2（正式轮在有效轮中选）、有效轮窗口内无错误 | valid_rounds=3/3, valid_rounds_window_errors=0 | — | 通过 |
| 前提 | wrk2 全部 3 次测量无 socket 错误、无非 2xx/3xx 响应 | socket_errors=0, non_2xx_3xx=0（3 次合计） | — | 通过 |
| a. 达成吞吐 | harness 相对目标 rate 偏差 ≤ 2% | 5000 vs 5000 req/s | 0.0% | 通过 |
| a. 达成吞吐 | wrk2 相对目标 rate 偏差 ≤ 2% | 4995.8 vs 5000 req/s | 0.1% | 通过 |
| b. 测量一致性 | harness 服务时间 p50 vs wrk2 未修正 p50：相对偏差 ≤ 15% 或绝对差 ≤ 100µs（取宽者） | 0.142 vs 0.149 ms | 4.7% | 通过 |
| b. 测量一致性 | harness 服务时间 p99 vs wrk2 未修正 p99：相对偏差 ≤ 15% 或绝对差 ≤ 100µs（取宽者） | 0.334 vs 0.348 ms | 4.0% | 通过 |
| c. 节拍质量 | harness 发出滞后 p99 ≤ 100µs | 0.009 ms | — | 通过 |

### crossval-closed-20261022-024556.json

> 判定：**PASS** · 创建于 2026-10-22T02:45:56.953949071+11:00 · harness：`crossval-http-closed-capacity-v1-20261022-024133.json`（git d23e39d（干净））· 工具命令：`taskset -c 5-9 wrk -t 4 -c 64 --latency -s bench/bin/post-1k.lua -d 40s http://127.0.0.1:19180/echo`

**问题**：同一个 HTTP 目标、闭环同一并发，harness 与 wrk 各自能压到多少、瓶颈在哪一侧——发压容量标定

| 组 | 判据 | 实测 | 相对偏差 | 结果 |
|---|---|---|---|---|
| 唯一判定项 | 两次运行的 CPU 采样都成功，且都能判别瓶颈侧（某一侧平均 ≥ 90%） | harness 运行：压测端饱和；wrk 运行：服务端饱和 | — | 通过 |

### crossval-open-20261022-032433.json

> 判定：**PASS** · 创建于 2026-10-22T03:24:33.691730063+11:00 · harness：`crossval-http-open-v1-20261022-031809.json`（git d8dc78b（干净））· 工具命令：`taskset -c 5-9 wrk2 -t 4 -c 64 -R 5000 --latency -U -s bench/bin/post-1k.lua -d 40s http://127.0.0.1:19180/echo`

**问题**：同一个 HTTP 目标、开环同一 rate，harness 与 wrk2 的发压节拍与测量是否一致——校验的是 harness 本身

| 组 | 判据 | 实测 | 相对偏差 | 结果 |
|---|---|---|---|---|
| 前提 | harness 有效轮 ≥ 2（正式轮在有效轮中选）、有效轮窗口内无错误 | valid_rounds=3/3, valid_rounds_window_errors=0 | — | 通过 |
| 前提 | wrk2 全部 3 次测量无 socket 错误、无非 2xx/3xx 响应 | socket_errors=0, non_2xx_3xx=0（3 次合计） | — | 通过 |
| a. 达成吞吐 | harness 相对目标 rate 偏差 ≤ 2% | 5000.0 vs 5000 req/s | 0.0% | 通过 |
| a. 达成吞吐 | wrk2 相对目标 rate 偏差 ≤ 2% | 4995.5 vs 5000 req/s | 0.1% | 通过 |
| b. 测量一致性 | harness 服务时间 p50 vs wrk2 未修正 p50：相对偏差 ≤ 15% 或绝对差 ≤ 100µs（取宽者） | 0.149 vs 0.155 ms | 3.9% | 通过 |
| b. 测量一致性 | harness 服务时间 p99 vs wrk2 未修正 p99：相对偏差 ≤ 15% 或绝对差 ≤ 100µs（取宽者） | 0.321 vs 0.382 ms | 16.0% | 通过 |
| c. 节拍质量 | harness 发出滞后 p99 ≤ 100µs | 0.013 ms | — | 通过 |

### crossval-open-20261022-033221.json

> 判定：**PASS** · 创建于 2026-10-22T03:32:21.427585985+11:00 · harness：`crossval-http-open-v1-20261022-032557.json`（git d8dc78b（干净））· 工具命令：`taskset -c 5-9 wrk2 -t 4 -c 64 -R 5000 --latency -U -s bench/bin/post-1k.lua -d 40s http://127.0.0.1:19180/echo`

**问题**：同一个 HTTP 目标、开环同一 rate，harness 与 wrk2 的发压节拍与测量是否一致——校验的是 harness 本身

| 组 | 判据 | 实测 | 相对偏差 | 结果 |
|---|---|---|---|---|
| 前提 | harness 有效轮 ≥ 2（正式轮在有效轮中选）、有效轮窗口内无错误 | valid_rounds=3/3, valid_rounds_window_errors=0 | — | 通过 |
| 前提 | wrk2 全部 3 次测量无 socket 错误、无非 2xx/3xx 响应 | socket_errors=0, non_2xx_3xx=0（3 次合计） | — | 通过 |
| a. 达成吞吐 | harness 相对目标 rate 偏差 ≤ 2% | 5000 vs 5000 req/s | 0.0% | 通过 |
| a. 达成吞吐 | wrk2 相对目标 rate 偏差 ≤ 2% | 4996.0 vs 5000 req/s | 0.1% | 通过 |
| b. 测量一致性 | harness 服务时间 p50 vs wrk2 未修正 p50：相对偏差 ≤ 15% 或绝对差 ≤ 100µs（取宽者） | 0.143 vs 0.150 ms | 4.7% | 通过 |
| b. 测量一致性 | harness 服务时间 p99 vs wrk2 未修正 p99：相对偏差 ≤ 15% 或绝对差 ≤ 100µs（取宽者） | 0.315 vs 0.346 ms | 9.0% | 通过 |
| c. 节拍质量 | harness 发出滞后 p99 ≤ 100µs | 0.011 ms | — | 通过 |

