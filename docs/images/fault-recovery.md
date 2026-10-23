# fault-recovery.svg：数据与口径

由 `python bench/bin/charts.py --reports bench/reports --out docs/images` 从归档 JSON 生成，请勿手改；数字以下表与来源 JSON 为准。

图：离群摘除开 / 关的故障注入对照。

**探针口径（git_dirty = true，f160df9 + 未提交改动），不作数**，数字只说明机制；完整实验与判定见 [probes/outlier-detection.md](../probes/outlier-detection.md)。

| 故障 | 运行 | 离群摘除 | 窗口错误数 | 错误类型 | 错误恢复 (s) | 摘除 / 放回 | 来源 JSON |
|---|---|---|---|---|---|---|---|
| kill -9 一个提供者（failfast） | on-1 | on | 221 | transport 221 | 0.985 | 2 / 1 | `bench/reports/probes/20261022-task19-outlier-detection/kill/failfast-kill9-on-1/fault-kill-node-failfast-v1-20261022-135533.json` |
| kill -9 一个提供者（failfast） | on-2 | on | 397 | transport 397 | 0.986 | 2 / 1 | `bench/reports/probes/20261022-task19-outlier-detection/kill/failfast-kill9-on-2/fault-kill-node-failfast-v1-20261022-135823.json` |
| kill -9 一个提供者（failfast） | off-1 | off | 50,349 | transport 50,349 | 30.7 | 0 / 0 | `bench/reports/probes/20261022-task19-outlier-detection/kill/failfast-kill9-off-1/fault-kill-node-failfast-v1-20261022-135700.json` |
| kill -9 一个提供者（failfast） | off-2 | off | 48,497 | transport 48,497 | 29.964 | 0 / 0 | `bench/reports/probes/20261022-task19-outlier-detection/kill/failfast-kill9-off-2/fault-kill-node-failfast-v1-20261022-135942.json` |
| 连接静默挂起（toxic-timeout，failover） | on-1 | on | 5,038 | timeout 5,038 | 2.976 | 3 / 2 | `bench/reports/probes/20261022-task19-outlier-detection/timeout/timeout-on-1/fault-kill-node-failover-v1-20261022-135006.json` |
| 连接静默挂起（toxic-timeout，failover） | on-2 | on | 5,038 | timeout 5,038 | 2.988 | 3 / 2 | `bench/reports/probes/20261022-task19-outlier-detection/timeout/timeout-on-2/fault-kill-node-failover-v1-20261022-135244.json` |
| 连接静默挂起（toxic-timeout，failover） | off-1 | off | 62,447 | timeout 62,447 | 未恢复（recovered = false，故障后 40.0s 到轮末） | 0 / 0 | `bench/reports/probes/20261022-task19-outlier-detection/timeout/timeout-off-1/fault-kill-node-failover-v1-20261022-135125.json` |
| 连接静默挂起（toxic-timeout，failover） | off-2 | off | 62,498 | timeout 62,498 | 未恢复（recovered = false，故障后 40.0s 到轮末） | 0 / 0 | `bench/reports/probes/20261022-task19-outlier-detection/timeout/timeout-off-2/fault-kill-node-failover-v1-20261022-135402.json` |

取数字段：窗口错误数 `summary.official.window_errors`（单轮，统计窗口 [10s, 60s]）；错误类型 `rounds[0].errors_by_type`；恢复时间 `rounds[0].faults[0].recovery.errors_recovery_s`（判据见同一对象的 `criterion`：故障后第一个"之后连续 3 秒错误为 0"的秒，只看第一次恢复）；`recovered = false` 时画到轮末的浅灰条并标注"未恢复"，条长 = `rounds[0].window.end_ms − faults[0].executed_at_ms`；摘除 / 放回 = `faults[0].outlier_ejections` / `outlier_restores`。
错误数与恢复时间是两种量纲，分成两列各用自己的线性轴（不用双轴）；同一列两行共用刻度。

测试条件：同机 WSL2 loopback · AMD Ryzen 5 4600H · 服务端 cpu 0-4 / 压测端 cpu 5-9（taskset 各 5 核）· 1KB 回显 · 开环 5000 req/s · 3 个提供者经 ZooKeeper · 超时 1s
单轮 60s（窗口 10–60s，t = 20s 注入故障）· 开 / 关各 2 次 · f160df9 + 未提交改动（git_dirty）· 同机数字不可外推
