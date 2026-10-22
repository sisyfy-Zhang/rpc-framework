# 对比表

由 `rpc-bench-e2e compare` 从 JSON 结果文件生成；每行取该结果的正式轮，每个数字都可在对应 JSON 中找到。

## 闭环 · 1024 B · `BenchService#echo` · 每轮 12s（预热 4s，窗口 [4s, 10s]）× 1 轮 · 超时 1s

> 对比轴：`connections`。

| 连接数 | transport | 口径卡 | 吞吐 (req/s) | 吞吐 / 首行 | p50 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | 客户端核 CPU | 服务端核 CPU | client_bound | GC 停顿 客户端/服务端 (ms) | write 系统调用/请求 客户端/服务端 | 上下文切换/请求 客户端/服务端 | 暂停读次数 | 窗口错误 | 判定 | 连接 | 客户端 IO 线程 | 服务端配置 | 结果文件 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **2（缺省）** | rpc（protobuf） | `sweep-closed128-rpc-direct-short` | 136191.2 | 1.00× | 0.846 | 3.039 | 5.767 | 12.329 | 66.9% | 37.9% | 否 | 20 / 18 | 0.18 / 0.06 | 0.16 / 0.02 | 0 | 0 | 通过 | 2 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 direct (NOT the default): business methods run on the IO threads (max in-flight 10000) | `sweep-closed128-rpc-direct-short-20261022-165423.json` |
| **8** | rpc（protobuf） | `sweep-c8-closed128-rpc-direct-short` | 97827.8 | 0.72× | 0.967 | 6.668 | 13.607 | 38.961 | 96.0% | 41.0% | **是** | 18 / 14 | 0.39 / 0.17 | 0.41 / 0.13 | 0 | 0 | 通过 | 8 TCP connection(s) per provider, requests multiplexed by request id | default (Netty: 2 x available cores = 10) | IO 线程 default (Netty: 2 x available cores = 10)；执行器 direct (NOT the default): business methods run on the IO threads (max in-flight 10000) | `sweep-c8-closed128-rpc-direct-short-20261022-165440.json` |

每行取该结果的正式轮（闭环：窗口吞吐中位数那一轮）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。暂停读次数是统计窗口内 rpc 服务端因出站不可写而停读的次数（对比对象没有这项，记 —）。闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。

