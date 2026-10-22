# 任务 21：连接数递减探针的原始结果

探针文档：[docs/probes/connections-scaling.md](../../../../docs/probes/connections-scaling.md)（阶段 4a 任务 21）。

**探针口径，不作数，只为复现与核对探针文档中的数字。**

- 数据的 `git_sha`：22801f5（`22801f574c1205b551aa6c65beebbc10b96908ba`）加该任务未提交的改动，
  全部 20 个结果文件（14 次正式运行 + 6 个冒烟结果）都是 `git_dirty = true`，这些数字不对应任何一个提交。
- 标签：主序列 `conn-probe`，变体 `conn-probe-io2` / `conn-probe-direct`，冒烟 `smoke`。
- 运行时间：2026-10-22（本地时间 AEDT）。冒烟 16:53 ~ 16:55；原序列结果文件名时间戳 22:45 ~ 23:12，补跑 23:17 ~ 23:31。
- 来源：压测机 WSL `~/t21/`。只复制了结果 JSON、生成的 Markdown、`control-topology.txt`（`sweep.sh` 写的 HTTP 控制端点拓扑）、逐轮提取；
  服务端日志（`*.log`）、完成标记（`*.done`、`smoke/done`）、驱动 / 提取脚本与冒烟用的临时短卡（`~/t21/smokecards/`）没有入库。

## 目录内容

每个运行目录是一次 `sweep.sh` 运行：结果 JSON、同名 Markdown 报告、该次的 `compare.md`、`control-topology.txt`。

| 目录 | 卡 | 探针文档中的位置 | 运行 |
|---|---|---|---|
| `res/M/` | `sweep-closed128-rpc-v1` / `sweep-c4-closed128-rpc-v1` / `sweep-c8-closed128-rpc-v1` | 「主序列」原序列（顺序 c2, c4, c8, c8, c4, c2） | `c2-1`、`c4-1`、`c8-1`、`c8-2`、`c4-2`、`c2-2` |
| `res/V/` | `sweep-c8-closed128-rpc-v1` + `BENCH_HARNESS_OPTS="--io-threads 2"`；`sweep-closed128-rpc-direct-v1`；`sweep-c8-closed128-rpc-direct-v1` | 「变体」 | `c8-io2`、`c2-direct`、`c8-direct` |
| `res-r2/M/` | 同 `res/M/` | 「主序列」补跑（顺序 c2, c4, c8, c4） | `c2-3`、`c4-3`、`c8-3`、`c4-4` |
| `res-r2/V/` | `sweep-c8-closed128-rpc-direct-v1` | 「变体」c8-direct 第 2 次 | `c8-direct-2` |
| `smoke/` | 由正式卡派生的短卡 `*-short`（每轮 12s、预热 4s、窗口 [4s, 10s]、1 轮） | 「口径说明」里引用的冒烟数字；**smoke，不作数** | `c2`、`c8`、`c8-io2`、`direct`（c2 与 c8 两张 direct 短卡）、`c2-rpcstats`（`BENCH_CONTROL_HTTP=off`，服务端统计经 RPC 取，用于和 HTTP 通道对照） |

「重复性」表的各次正式轮吞吐：c2 = `c2-1` / `c2-2` / `c2-3`，c4 = `c4-1` / `c4-2` / `c4-3` / `c4-4`，c8 = `c8-1` / `c8-2` / `c8-3`，c8-direct = `c8-direct` / `c8-direct-2`。
主序列 3 / 4 / 3 次与变体 1 / 1 / 2 次，与探针文档「实验」表一致。

逐轮提取（由当时的提取脚本从结果 JSON 机械整理，数字未改动；是提取结果，不是 harness 的输出）：

- `extract.md`：原序列每次运行的正式轮、按线程组的 CPU 与切换、按卡均值（只含原序列）。
- `extract-all.md`：同上，列出原序列的全部轮次。
- `extract-r2.md` / `extract-r2-all.md`：补跑的对应两份（只含补跑）。
- `res/rows.json`、`res-r2/rows.json`、`smoke/rows.json`：同一提取脚本的逐轮数据（JSON）。

探针文档里的均值表（「主序列」「变体」）是原序列与补跑合在一起的正式轮算术平均；两份 extract 各自的"按卡均值"只覆盖各自那一批，
所以和文档里的均值表不同，这不是对不上。

## 跑法

每次运行 `bash bench/bin/sweep.sh --cards <卡> --label <标签> --out <目录>` 新起一个服务端，统计经 HTTP 控制端点取；
连接数探针的两种变体命令见 [docs/benchmarking.md「并发扫描与连接数扫描」](../../../../docs/benchmarking.md#并发扫描与连接数扫描)，实验设计见探针文档「实验」一节。
