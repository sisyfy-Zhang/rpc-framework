# 任务 20：虚拟线程 vs 固定池（阻塞型业务）的原始结果

探针文档：[docs/probes/executor-blocking.md](../../../../docs/probes/executor-blocking.md)（阶段 4a 任务 20）。

**探针口径，不作数，只为复现与核对探针文档中的数字。**

- 数据的 `git_sha`：121c99f（`121c99f63d4ab8daeabb6e437a49d76f6504d615`）加该任务未提交的改动，全部 18 个结果文件都是 `git_dirty = true`，
  这些数字不对应任何一个提交。
- 标签：全部 `exec-probe`。
- 运行时间：2026-10-22（本地时间 AEDT），结果文件名里的时间戳从 15:17 到 16:15。
- 来源：`G:\rpc-build-tmp\task20\results\`（压测机 WSL 上 `--out ~/t20/res/...` 的结果收回 Windows 的副本）。只复制了结果 JSON、生成的 Markdown 与逐轮提取 `rows.json`；
  日志（`*.log`）与驱动 / 提取脚本（`*.sh`、`*.py`）没有入库。

## 目录内容

- `compare.md`：`rpc-bench-e2e compare` 用全部 18 个结果 JSON 生成的并排表（按业务等待与负载分组；同一次运行可能出现在多张表里），
  是探针文档各节表格的来源。
- `rows.json`：逐轮提取（54 轮，每次运行 3 轮，`official = true` 的 18 行是正式轮），由当时的提取脚本从结果 JSON 机械整理，数字未改动。
- `res/<组>/<卡>-<n>/`：每次运行一个目录，含结果 JSON、同名 Markdown 报告与该次的单行 `compare.md`（`sweep.sh` 生成）。`<n>` 是同一张卡的第几次。

| 目录 | 卡 | 探针文档中的位置 | 运行 |
|---|---|---|---|
| `res/A/` | `exec-{fixed,virtual}-work2ms-closed64-v1` | 第 1 节 A：业务 2ms，闭环 64 并发 | fixed / virtual 各 2 次（交替） |
| `res/B/` | `exec-{fixed,virtual}-work10ms-closed256-v1` | 第 2 节 B：业务 10ms，闭环 256 并发 | fixed / virtual 各 2 次（交替） |
| `res/C/` | `exec-{fixed,virtual}-work2ms-open3k-v1` | 第 3 节 C：业务 2ms，开环 3000 req/s | fixed / virtual 各 2 次（交替） |
| `res/D/` | `exec-virtual-work10ms-closed256-{monitor,syncpark}-v1` | 第 4 节 D：JDK 21 钉住 | monitor / syncpark 各 2 次（交替） |
| `res/E/` | `exec-{fixed,virtual}-work10ms-closed1500-v1` | 第 5 节 E：拒绝边界（事后追加） | 各 1 次 |

合计 18 次运行、54 轮，与探针文档"全部 18 次运行、54 轮有效"一致。E 组 fixed 那次的服务端统计取不到（探针文档第 5 节），
所以它在 `compare.md` 里的服务端列是 `unknown` / `—`，这是原始结果，不是归档时丢的。

## 跑法

每次运行 `bash bench/bin/sweep.sh --cards <卡>` 新起一个服务端（执行器、业务等待方式取自卡片），卡在 `bench/profiles/exec-*.yaml`；
见 [docs/benchmarking.md「并发扫描与连接数扫描」](../../../../docs/benchmarking.md#并发扫描与连接数扫描) 与探针文档「设置」一节。
并排表由 `rpc-bench-e2e` 的 `compare --in <结果目录> --out <compare.md>` 生成（`sweep.sh` 末尾就是这条命令），分组规则见 [docs/benchmarking.md「对比表」](../../../../docs/benchmarking.md#对比表)。
