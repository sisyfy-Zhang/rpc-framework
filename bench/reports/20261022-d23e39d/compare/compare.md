# 对比表

由 `rpc-bench-e2e compare` 从 JSON 结果文件生成；每行取该结果的正式轮，每个数字都可在对应 JSON 中找到。

## 闭环 · 1024 B · `BenchService#echo` · 每轮 60s（预热 20s，窗口 [20s, 55s]）× 3 轮 · 超时 1s

> 对比轴：`transport`（开箱即用：各框架缺省连接数）。

| transport（序列化） | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **rpc（protobuf）** | `compare-closed64-rpc-v1` | 101943.2 | 1.00× | 0.573 | 1.458 | 2.789 | 15.385 | 57.2% | 49.1% | 否 | 58 / 54 | 0.19 / 0.16 | 0.27 / 0.27 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-closed64-rpc-v1-20261022-012703.json` |
| **grpc（protobuf）** | `compare-closed64-grpc-v1` | 14386.4 | 0.14× | 4.297 | 7.152 | 8.471 | 25.952 | 20.9% | 38.7% | 否 | 12 / 12 | 1.38 / 0.06 | 1.28 / 2.81 | — | 0 | 通过 | 1 ManagedChannel = 1 HTTP/2 connection; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-closed64-grpc-v1-20261022-013714.json` |
| **dubbo（hessian2）** | `compare-closed64-dubbo-v1` | 14401.8 | 0.14× | 4.530 | 7.307 | 10.166 | 35.226 | 23.7% | 41.6% | 否 | 63 / 62 | 0.11 / 0.07 | 0.25 / 2.73 | — | 0 | 通过 | 1 triple HTTP/2 connection per address; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-closed64-dubbo-v1-20261022-015049.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

transport 表不按服务端执行器与 flush 合并分区：它们是各框架自己的缺省（见"服务端配置"列）；本框架的非缺省配置（执行器不是 fixed，或 flush 合并关）不进 transport 表，见 `server_executor` / `flush_consolidation` 轴的表。

> 对比轴：`transport`（连接数对齐；connections = 2）。

| transport（序列化） | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **rpc（protobuf）** | `compare-closed64-rpc-v1` | 101943.2 | 1.00× | 0.573 | 1.458 | 2.789 | 15.385 | 57.2% | 49.1% | 否 | 58 / 54 | 0.19 / 0.16 | 0.27 / 0.27 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-closed64-rpc-v1-20261022-012703.json` |
| **grpc（protobuf）** | `compare-closed64-grpc-c2-v1` | 35516.3 | 0.35× | 1.677 | 4.678 | 7.152 | 35.160 | 52.4% | 64.0% | 否 | 28 / 27 | 1.61 / 0.18 | 1.49 / -1.30 | — | 0 | 通过 | 2 ManagedChannels = 2 HTTP/2 connections, calls take a channel round-robin; concurrent calls are multiplexed streams | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-closed64-grpc-c2-v1-20261022-014400.json` |
| **dubbo（hessian2）** | `compare-closed64-dubbo-c2-v1` | 25963.1 | 0.25× | 2.302 | 5.784 | 9.765 | 49.578 | 56.1% | 68.0% | 否 | 136 / 110 | 0.32 / 0.17 | 0.42 / 2.43 | — | 0 | 通过 | 2 independent Dubbo client instances = 2 triple HTTP/2 connections, calls take an instance round-robin; concurrent calls are multiplexed streams | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-closed64-dubbo-c2-v1-20261022-015743.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

transport 表不按服务端执行器与 flush 合并分区：它们是各框架自己的缺省（见"服务端配置"列）；本框架的非缺省配置（执行器不是 fixed，或 flush 合并关）不进 transport 表，见 `server_executor` / `flush_consolidation` 轴的表。

> 对比轴：`connections`（transport = grpc（protobuf），server_executor = grpc-default，flush_consolidation = —）。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（缺省）** | grpc（protobuf） | `compare-closed64-grpc-v1` | 14386.4 | 1.00× | 4.297 | 7.152 | 8.471 | 25.952 | 20.9% | 38.7% | 否 | 12 / 12 | 1.38 / 0.06 | 1.28 / 2.81 | — | 0 | 通过 | 1 ManagedChannel = 1 HTTP/2 connection; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-closed64-grpc-v1-20261022-013714.json` |
| **2** | grpc（protobuf） | `compare-closed64-grpc-c2-v1` | 35516.3 | 2.47× | 1.677 | 4.678 | 7.152 | 35.160 | 52.4% | 64.0% | 否 | 28 / 27 | 1.61 / 0.18 | 1.49 / -1.30 | — | 0 | 通过 | 2 ManagedChannels = 2 HTTP/2 connections, calls take a channel round-robin; concurrent calls are multiplexed streams | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads) | `compare-closed64-grpc-c2-v1-20261022-014400.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`connections`（transport = dubbo（hessian2），server_executor = —，flush_consolidation = —）。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **1（缺省）** | dubbo（hessian2） | `compare-closed64-dubbo-v1` | 14401.8 | 1.00× | 4.530 | 7.307 | 10.166 | 35.226 | 23.7% | 41.6% | 否 | 63 / 62 | 0.11 / 0.07 | 0.25 / 2.73 | — | 0 | 通过 | 1 triple HTTP/2 connection per address; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider) | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-closed64-dubbo-v1-20261022-015049.json` |
| **2** | dubbo（hessian2） | `compare-closed64-dubbo-c2-v1` | 25963.1 | 1.80× | 2.302 | 5.784 | 9.765 | 49.578 | 56.1% | 68.0% | 否 | 136 / 110 | 0.32 / 0.17 | 0.42 / 2.43 | — | 0 | 通过 | 2 independent Dubbo client instances = 2 triple HTTP/2 connections, calls take an instance round-robin; concurrent calls are multiplexed streams | dubbo default (shared client event loop: min(available cores + 1, 32) = 6) | IO 线程 default (aligned with Netty: 2 x available cores = 10)；执行器 dubbo default (threadpool=fixed, threads=200): business methods run off the IO threads | `compare-closed64-dubbo-c2-v1-20261022-015743.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`server_executor`（transport = rpc（protobuf），connections = 2，flush_consolidation = client on / server on）。

| 服务端执行器 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **direct** | rpc（protobuf） | `compare-closed64-rpc-direct-v1` | 142671.6 | 1.00× | 0.421 | 0.977 | 2.284 | 19.038 | 60.3% | 27.2% | 否 | 79 / 69 | 0.18 / 0.08 | 0.24 / 0.04 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 direct (NOT the default): business methods run on the IO threads (max in-flight 10000) | `compare-closed64-rpc-direct-v1-20261022-020438.json` |
| **fixed** | rpc（protobuf） | `compare-closed64-rpc-v1` | 101943.2 | 0.71× | 0.573 | 1.458 | 2.789 | 15.385 | 57.2% | 49.1% | 否 | 58 / 54 | 0.19 / 0.16 | 0.27 / 0.27 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `compare-closed64-rpc-v1-20261022-012703.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

## 开环 · 1024 B · `BenchService#echo` · 每轮 60s（预热 20s，窗口 [20s, 55s]）× 3 轮 · 超时 1s

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

