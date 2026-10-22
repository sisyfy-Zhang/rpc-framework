# 任务 19：客户端离群摘除 A/B 的原始结果

探针文档：[docs/probes/outlier-detection.md](../../../../docs/probes/outlier-detection.md)（阶段 4a 任务 19）。

**探针口径，不作数，只为复现与核对探针文档中的数字。**

- 数据的 `git_sha`：f160df9（`f160df9228c1a605a41cdc4c6f24464bf4db2bc5`）加该任务未提交的改动，全部 36 个结果文件都是 `git_dirty = true`，
  这些数字不对应任何一个提交。
- 标签：全部 `outlier-probe`。
- 运行时间：2026-10-22（本地时间 AEDT）。拓扑文件最早写于 13:49:58，结果文件名里的时间戳从 13:50 到 14:49，探针文档记的整批时段是 13:49 ~ 14:51。
- 来源：压测机 WSL `~/t19/p/`。只复制了结果 JSON、生成的 Markdown、拓扑 / 故障计划（`*.txt`）与故障执行记录（`*.executed`）。
  客户端 / 服务端日志（`*.log`）、完成标记（`*.done`）和驱动脚本没有入库。

## 目录内容

每个运行目录是一次运行：一个结果 JSON、同名的 Markdown 报告。故障卡另有 `topology.txt`（`fault-topology.sh` 起的拓扑）、
`schedule-*.txt`（故障计划）与 `schedule-*.txt.executed`（实际执行时刻）；无故障对照（`--no-faults`）没有计划文件。
`compare.sh` 的运行目录里另有该次的 `compare.md`（单行对比表）。

| 目录 | 卡 / 变体 | 探针文档中的位置 | 运行 |
|---|---|---|---|
| `timeout/` | `fault-kill-node-failover-v1`，`--variant toxic-timeout` | 第 1 节 连接静默挂起；判定 1 | `timeout-on-1`、`timeout-on-2`、`timeout-off-1`、`timeout-off-2` |
| `kill/` | `fault-kill-node-failfast-v1`（kill -9） | 第 2 节；判定 2 | `failfast-kill9-{on,off}-{1,2}` |
| `slow/` | `fault-slow-node-rr-v1` / `fault-slow-node-p2c-v1`，`--behind-toxiproxy all` | 第 3 节 慢节点；判定 3 | `rr-{on,off}-{1,2}`、`p2c-{on,off}-{1,2}` |
| `compare/` | `compare-closed64-rpc-v1`（`compare.sh --transports rpc --loads closed64`） | 第 4 节 closed64 宏观对照的第一对 | `compare-on-1`、`compare-off-1` |
| `repeat/compare-*` | 同上 | 第 4 节 closed64 补跑的两对（顺序 off、on、on、off） | `compare-off-2`、`compare-on-2`、`compare-on-3`、`compare-off-3` |
| `control/` | `fault-slow-node-rr-v1 --behind-toxiproxy all --no-faults` | 第 4 节 slow-node 无故障对照的第一对 | `slow-rr-control-on-1`、`slow-rr-control-off-1` |
| `repeat/slow-rr-control-*` | 同上 | 第 4 节 slow-node 无故障对照补跑（顺序 off、on、on、off） | `slow-rr-control-off-2`、`-on-2`、`-on-3`、`-off-3` |
| `sigstop/` | `fault-kill-node-failover-v1`，`--variant sigstop` | 第 5 节；判定 5 | `sigstop-{on,off}-{1,2}` |
| `sigterm/` | `fault-sigterm-grace2s-failfast-v1` | 第 6 节 优雅停机；判定 6 | `sigterm-g2s-failfast-{on,off}-{1,2}` |

合计 36 次运行（8 组 × 4 次 + closed64 与 slow-node 对照各多 2 次），与探针文档"全部 36 次运行"一致。
运行目录名里的 `on` / `off` 是 `--outlier-detection` 的取值，结果 JSON 里也记着 `outlier_detection`。

不在本目录的：

- 第 4 节的热路径微基准（`OutlierDetectorBenchmark`，Windows 开发机上的 JMH）输出不在数据来源里，复现命令见探针文档第 4 节。
- 判定 4 "客户端日志里没有一行 `Outlier ejected`"依据的日志没有入库；结果 JSON 里的累计 `outlier_ejections` / 每秒 `ejections` 可以核对零摘除。

## 跑法

见探针文档的「复现」一节（`fault-run.sh` 加 `FAULT_HARNESS_OPTS="--open-workers per-request --outlier-detection <on|off>"`，
closed64 用 `BENCH_HARNESS_OPTS="--outlier-detection <on|off>" bash bench/bin/compare.sh --transports rpc --loads closed64`），
脚本与口径见 [docs/benchmarking.md「故障注入」](../../../../docs/benchmarking.md#故障注入)。
