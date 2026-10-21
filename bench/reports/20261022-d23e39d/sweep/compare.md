# 对比表

由 `rpc-bench-e2e compare` 从 JSON 结果文件生成；每行取该结果的正式轮，每个数字都可在对应 JSON 中找到。

## 闭环 · 1024 B · `BenchService#echo` · 每轮 60s（预热 20s，窗口 [20s, 55s]）× 3 轮 · 超时 1s

> 对比轴：`connections`（concurrency = 128，server_executor = fixed）。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **2（缺省）** | rpc（protobuf） | `sweep-closed128-rpc-v1` | 141161.6 | 1.00× | 0.837 | 2.191 | 4.641 | 43.516 | 63.9% | 49.9% | 否 | 84 / 72 | 0.15 / 0.11 | 0.19 / 0.18 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-closed128-rpc-v1-20261022-021445.json` |
| **4** | rpc（protobuf） | `sweep-c4-closed128-rpc-v1` | 138567.0 | 0.98× | 0.799 | 2.830 | 5.100 | 33.751 | 78.2% | 61.5% | 否 | 84 / 79 | 0.38 / 0.23 | 0.25 / 0.24 | 0 | 0 | 通过 | 4 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-c4-closed128-rpc-v1-20261022-022128.json` |
| **8** | rpc（protobuf） | `sweep-c8-closed128-rpc-v1` | 126244.3 | 0.89× | 0.814 | 4.420 | 7.320 | 35.193 | 85.6% | 69.7% | 否 | 85 / 80 | 0.61 / 0.40 | 0.44 / 0.39 | 0 | 0 | 通过 | 8 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-c8-closed128-rpc-v1-20261022-022449.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`concurrency`（connections = 2，server_executor = fixed）。

| 并发 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **128** | rpc（protobuf） | `sweep-closed128-rpc-v1` | 141161.6 | 1.00× | 0.837 | 2.191 | 4.641 | 43.516 | 63.9% | 49.9% | 否 | 84 / 72 | 0.15 / 0.11 | 0.19 / 0.18 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-closed128-rpc-v1-20261022-021445.json` |
| **256** | rpc（protobuf） | `sweep-closed256-rpc-v1` | 162498.2 | 1.15× | 1.474 | 3.701 | 5.915 | 35.652 | 61.7% | 48.5% | 否 | 103 / 88 | 0.13 / 0.07 | 0.14 / 0.13 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-closed256-rpc-v1-20261022-021807.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

> 对比轴：`server_executor`（connections = 1，concurrency = 64）。

| 服务端执行器 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **direct** | rpc（protobuf） | `sweep-c1-closed64-rpc-direct-v1` | 124541.0 | 1.00× | 0.494 | 1.144 | 2.212 | 21.709 | 39.5% | 17.2% | 否 | 69 / 59 | 0.08 / 0.05 | 0.18 / 0.02 | 0 | 0 | 通过 | 1 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 direct (NOT the default): business methods run on the IO threads (max in-flight 10000) | `sweep-c1-closed64-rpc-direct-v1-20261022-021122.json` |
| **fixed** | rpc（protobuf） | `sweep-c1-closed64-rpc-v1` | 92771.5 | 0.74× | 0.633 | 1.540 | 2.679 | 27.984 | 38.1% | 38.8% | 否 | 54 / 47 | 0.09 / 0.10 | 0.19 / 0.26 | 0 | 0 | 通过 | 1 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 fixed pool (10 threads, queue 1000): business methods run off the IO threads | `sweep-c1-closed64-rpc-v1-20261022-013349.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

