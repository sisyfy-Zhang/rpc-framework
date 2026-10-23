# docs/images：README 用图

这些图都由入库脚本 [`bench/bin/charts.py`](../../bench/bin/charts.py) 生成，数据图只读归档的结果 JSON，不手抄数字。**请勿手改 SVG**：改数据或样式都改脚本，然后重新生成。

```bash
python bench/bin/charts.py --reports bench/reports --out docs/images
```

- 依赖：Python 3 标准库 + matplotlib（最近一次用 Python 3.12.4、matplotlib 3.8.4、Windows 上的 Microsoft YaHei 生成）。
  脚本依次找 Microsoft YaHei / PingFang SC / Noto Sans CJK SC / Source Han Sans SC / SimHei / WenQuanYi Micro Hei，都没有时改用英文标签并在标准错误上提示。
- 文字转成了路径（`svg.fonttype = path`），看图的人不需要装中文字体；每张 SVG 自带不透明的浅色背景，GitHub 明暗主题下都能读。宽 960px。
- 输出是确定的：同一份 JSON 加同一个 matplotlib 版本，重复生成的 SVG 逐字节相同（固定 `svg.hashsalt`，不写日期）。换 matplotlib 版本后 SVG 字节可能变，图上的数字不会变。
- 脚本先检查每个来源文件（卡片 id、`git_sha`、`git_dirty`、判定通过、离群摘除开关与目录名一致），不符就退出码 1，不出图。
- 每张数据图旁边有一份同名 `.md`，列出图上的每个数字、来源 JSON 路径、取数字段与口径，也由脚本生成。README 引用图里的数字时以它为准。

最近生成：2026-10-23（归档数据同 main = 8fb26c9 时；之后只改了图上文字：标题去掉"越高越好 / 越低越好"，故障图去掉标题下的探针口径警示行与底部的恢复时间脚注——探针口径改由测试条件行里的 git_dirty、本表与 [fault-recovery.md](fault-recovery.md) 标明）。

| 图 | 内容 | 数据来源 | 口径 | 数据表 |
|---|---|---|---|---|
| [throughput-closed64.svg](throughput-closed64.svg) | 闭环 64 并发吞吐：本框架 fixed（缺省）/ direct（opt-in），gRPC-Java 开箱即用 / 2 连接，Dubbo triple 开箱即用 / 2 连接 | `bench/reports/20261022-d23e39d/compare/compare-closed64-*.json`（6 个） | 正式轮（3 轮中窗口吞吐的中位那一轮）的 `summary.official.throughput_rps`；干净提交 d23e39d | [throughput-closed64.md](throughput-closed64.md) |
| [tail-latency-open10k.svg](tail-latency-open10k.svg) | 开环 10,000 req/s 的 p50 与 p99（对数轴），灰带是三轮 p99 的极差；同样 6 种配置 | `bench/reports/20261022-d23e39d/compare/compare-open10k-*.json`（6 个） | 正式轮（3 轮中窗口 p99 的中位那一轮）的 `summary.official.p50_ms` / `p99_ms`，极差取 `summary.spread.p99_ms`；延迟从计划发出时刻算；干净提交 d23e39d | [tail-latency-open10k.md](tail-latency-open10k.md) |
| [fault-recovery.svg](fault-recovery.svg) | 离群摘除开 / 关：kill -9（failfast）与连接静默挂起（toxic-timeout，failover）下的窗口错误数与错误恢复时间，开 / 关各 2 次 | `bench/reports/probes/20261022-task19-outlier-detection/{kill,timeout}/*/*.json`（8 个） | **探针口径（git_dirty，f160df9 + 未提交改动），不作数**，见 [probes/outlier-detection.md](../probes/outlier-detection.md)；`summary.official.window_errors` 与 `rounds[0].faults[0].recovery.errors_recovery_s` | [fault-recovery.md](fault-recovery.md) |
| [architecture.svg](architecture.svg) | 架构：调用方 → 消费端（Filter 链 → Cluster → 负载均衡 · 离群摘除 → 连接池）→ 协议 v2 帧 → 服务端（IO 线程 → 业务执行器 → Filter 链 → 服务实现）；旁路注册中心与 Micrometer 指标 | 不读数据，按原根 README「架构」一节的 ASCII 图重画 | — | — |

三张数据图底部都有一行测试条件：同机 WSL2 loopback、AMD Ryzen 5 4600H、服务端 cpu 0-4 / 压测端 cpu 5-9、1KB 回显、轮次与归档 sha，以及"同机数字不可外推"。
完整口径见 [docs/benchmarking.md](../benchmarking.md) 与 [docs/comparison.md](../comparison.md)。

换归档批次时改脚本顶部的 `COMPARE_BATCH` / `COMPARE_SHA` / `PROBE_BATCH` / `PROBE_SHA` 四个常量，重新生成，再核对各 `.md` 数据表与 README 正文里的数字。
