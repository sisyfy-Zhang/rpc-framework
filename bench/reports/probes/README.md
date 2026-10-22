# 探针原始结果（探针口径，不作数）

这里放的是探针 / 对照实验的原始结果，用来复现与核对 [docs/probes/](../../../docs/probes/) 里各篇文档的数字。

## 与正式归档的区别

| | 正式归档 `bench/reports/<yyyyMMdd>-<sha7>/` | 探针结果 `bench/reports/probes/<yyyyMMdd>-task<n>-<名称>/` |
|---|---|---|
| 代码 | 干净提交：结果文件的 `git_sha` 就是目录名里的 sha，`git_dirty = false` | 基线提交加该任务未提交的改动：`git_dirty = true`，数字不对应任何一个提交 |
| 地位 | **作数**：仓库 README「压测数据」与 [docs/comparison.md](../../../docs/comparison.md) 的对比数字取自这里 | **不作数**：只支撑对应探针文档的机制分析与结论，不作为对外数字引用 |
| 内容 | 结果 JSON、生成的 Markdown、`README.md`（`bench-report.sh` 生成）、`NOTES.md` | 结果 JSON、生成的 Markdown（含 `compare.md`）、拓扑 / 控制 / 故障计划文件（`*.txt`）、故障执行记录（`*.executed`）、逐轮提取；每个目录一份手写 `README.md` |

两者都不入库日志（`*.log`，含 GC 日志）与 JFR（`*.jfr`），见仓库根的 `.gitignore`；探针目录另外不放完成标记与驱动 / 提取脚本。

## 清单

| 目录 | 任务 | 探针文档 | 数据基线 | 运行 |
|---|---|---|---|---|
| [20261022-task19-outlier-detection/](20261022-task19-outlier-detection/) | 阶段 4a 任务 19：客户端离群摘除 A/B | [outlier-detection.md](../../../docs/probes/outlier-detection.md) | f160df9 + 未提交改动 | 36 次（故障卡 on / off、closed64 与无故障对照） |
| [20261022-task20-executor-blocking/](20261022-task20-executor-blocking/) | 阶段 4a 任务 20：虚拟线程 vs 固定池 | [executor-blocking.md](../../../docs/probes/executor-blocking.md) | 121c99f + 未提交改动 | 18 次（A ~ E 五组） |
| [20261022-task21-connections-scaling/](20261022-task21-connections-scaling/) | 阶段 4a 任务 21：连接数递减探针 | [connections-scaling.md](../../../docs/probes/connections-scaling.md) | 22801f5 + 未提交改动 | 14 次（主序列 10 次 + 变体 4 次），另有冒烟（smoke，不作数） |

更早的探针（flush 合并、出站背压、故障注入等）的原始结果没有入库，各篇文档里写明了当时的数据位置。
