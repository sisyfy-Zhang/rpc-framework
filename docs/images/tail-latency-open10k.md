# tail-latency-open10k.svg：数据与口径

由 `python bench/bin/charts.py --reports bench/reports --out docs/images` 从归档 JSON 生成，请勿手改；数字以下表与来源 JSON 为准。

图：开环 10k p50 / p99。

| 图中的行 | p50 (ms) | p99 (ms) | 三轮 p99 极差 (ms) | 正式轮 | 来源 JSON |
|---|---|---|---|---|---|
| 本框架 fixed（缺省） | 0.440 | 0.798 | 0.776 ~ 1.343 | 第 2 轮 | `bench/reports/20261022-d23e39d/compare/compare-open10k-rpc-v1-20261022-013024.json` |
| 本框架 direct（opt-in） | 0.251 | 0.530 | 0.470 ~ 0.575 | 第 1 轮 | `bench/reports/20261022-d23e39d/compare/compare-open10k-rpc-direct-v1-20261022-020759.json` |
| gRPC-Java 开箱即用（1 channel） | 1.226 | 20.955 | 5.677 ~ 45.646 | 第 3 轮 | `bench/reports/20261022-d23e39d/compare/compare-open10k-grpc-v1-20261022-014037.json` |
| gRPC-Java 2 连接 | 0.585 | 1.338 | 1.278 ~ 1.466 | 第 2 轮 | `bench/reports/20261022-d23e39d/compare/compare-open10k-grpc-c2-v1-20261022-014722.json` |
| Dubbo triple 开箱即用（1 连接） | 0.776 | 22.233 | 6.849 ~ 72.024 | 第 1 轮 | `bench/reports/20261022-d23e39d/compare/compare-open10k-dubbo-v1-20261022-015412.json` |
| Dubbo triple 2 连接 | 0.708 | 4.235 | 2.730 ~ 5.571 | 第 3 轮 | `bench/reports/20261022-d23e39d/compare/compare-open10k-dubbo-c2-v1-20261022-020106.json` |

取数字段：`summary.official.p50_ms` / `p99_ms`（正式轮 = 3 轮中窗口 p99 的中位那一轮，`summary.selection_rule`；统计窗口 [20s, 55s]，开环 10000 req/s，延迟从计划发出时刻算）；极差取 `summary.spread.p99_ms.min` / `max`。
横轴为对数轴（p99 跨 0.5 ~ 22ms，三轮极差到 72ms），数值标签保留 3 位小数，与 JSON 一致。

测试条件：同机 WSL2 loopback · AMD Ryzen 5 4600H · 服务端 cpu 0-4 / 压测端 cpu 5-9（taskset 各 5 核）· 1KB 回显 · 直连 · 每卡 3 轮 × 60s 取正式轮
归档 d23e39d（git_dirty = false）· 同机数字不可外推
