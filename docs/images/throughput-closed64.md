# throughput-closed64.svg：数据与口径

由 `python bench/bin/charts.py --reports bench/reports --out docs/images` 从归档 JSON 生成，请勿手改；数字以下表与来源 JSON 为准。

图：闭环 64 并发吞吐。

| 图中的条 | 吞吐 (req/s) | 相对 fixed | 正式轮 | 连接数 | 来源 JSON |
|---|---|---|---|---|---|
| 本框架 fixed（缺省） | 101,943.2 | — | 第 2 轮 | 2 | `bench/reports/20261022-d23e39d/compare/compare-closed64-rpc-v1-20261022-012703.json` |
| 本框架 direct（opt-in） | 142,671.6 | 1.40× | 第 2 轮 | 2 | `bench/reports/20261022-d23e39d/compare/compare-closed64-rpc-direct-v1-20261022-020438.json` |
| gRPC-Java 开箱即用（1 channel） | 14,386.4 | 0.14× | 第 3 轮 | 1 | `bench/reports/20261022-d23e39d/compare/compare-closed64-grpc-v1-20261022-013714.json` |
| gRPC-Java 2 连接 | 35,516.3 | 0.35× | 第 3 轮 | 2 | `bench/reports/20261022-d23e39d/compare/compare-closed64-grpc-c2-v1-20261022-014400.json` |
| Dubbo triple 开箱即用（1 连接） | 14,401.8 | 0.14× | 第 2 轮 | 1 | `bench/reports/20261022-d23e39d/compare/compare-closed64-dubbo-v1-20261022-015049.json` |
| Dubbo triple 2 连接 | 25,963.1 | 0.25× | 第 3 轮 | 2 | `bench/reports/20261022-d23e39d/compare/compare-closed64-dubbo-c2-v1-20261022-015743.json` |

取数字段：`summary.official.throughput_rps`（正式轮 = 3 轮中窗口吞吐的中位那一轮，`summary.selection_rule`；统计窗口 [20s, 55s]）。图上数值保留 1 位小数，倍数 = 该值 / 本框架 fixed 的值，保留 2 位。
连接数取 `run.target.connections_per_node`。

测试条件：同机 WSL2 loopback · AMD Ryzen 5 4600H · 服务端 cpu 0-4 / 压测端 cpu 5-9（taskset 各 5 核）· 1KB 回显 · 直连 · 每卡 3 轮 × 60s 取正式轮
归档 d23e39d（git_dirty = false）· 同机数字不可外推
