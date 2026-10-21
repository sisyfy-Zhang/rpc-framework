# 本批说明（20261022-d23e39d）

阶段 2 归档：在干净提交 d23e39d 上跑完全部对比卡、扫描卡与阶段 1 的四张延续卡，每张卡一次。所有结果文件的 `git_sha` 都是 d23e39d，
`git_dirty = false`，判定全部通过，没有重跑。故障卡（`fault-*`）不在本批，见 [docs/probes/fault-injection.md](../../../docs/probes/fault-injection.md)。

- `compare/`：本框架缺省（fixed 执行器）、本框架 direct（非缺省配置，上界对照）、gRPC 与 Dubbo 的开箱即用与连接数对齐（c2）卡，closed64 + open10k；`compare.md` 是并排表。
- `sweep/`：单连接（c1，fixed / direct）、并发 128 / 256、连接数 4 / 8；`compare.md` 是并排表。并发 64 / 2 连接的基线就是 `compare/compare-closed64-rpc-v1`。
- 根目录：`loopback-*`（经 ZooKeeper 注册中心，按卡声明）、`crossval-*`（开环 vs wrk2 三次取中位、闭环 vs wrk），`README.md` 由 `bench-report.sh` 生成。

表格（两份 `compare.md` 与 `README.md`）由修复提交 18d7858 的工具从现有 JSON 重新生成；数据 JSON 均为 d23e39d 上的运行，重新生成时没有重跑压测。

README 于任务 13 由该任务的提交（`CompareReport` 分组键加入寻址方式 `run.target.mode`：经 ZooKeeper 的 `loopback-*` 卡不再与直连的 compare 卡同表）用 `bench-report.sh --in` 本目录重新生成；既有 JSON 未改动。`crossval-repeat/run-2`、`run-3` 是任务 13 在 d8dc78b 上追加的两次开环交叉验证（d8dc78b 与 d23e39d 之间只改了对比表工具与归档），README 的交叉验证一节随之列出三次。

## 与 d5d620c 批次的口径差别：不可直接并排

d5d620c（20261019-d5d620c）之后，框架缺省行为与 harness 都有改动，**本批与 d5d620c 批次不可直接并排**：

| 改动 | 提交 | 影响 |
|---|---|---|
| flush 合并缺省开（两端 pipeline 首位 `FlushConsolidationHandler(256, true)`） | 8508668（任务 14） | 闭环吞吐明显上升，每请求 write 系统调用约从 1.0 降到 0.2（[flush-and-executor.md](../../../docs/probes/flush-and-executor.md)） |
| 出站背压缺省开（服务端不可写时停读 + 硬上限 1MiB，水位线 32KiB / 64KiB） | 4226fbe（任务 16） | 正常负载下代价在 ±2% 以内（[outbound-backpressure.md](../../../docs/probes/outbound-backpressure.md)） |
| 服务端回包改用 ReplyPromise（promise 子类 + 静态监听器） | 9f3ec8c（任务 15） | 每个回包少一次分配 |
| `RpcClientConfig` / `RpcServerConfig.ioThreads` 可配 | b5058cb（任务 11） | 缺省仍是 Netty 缺省 2 × 可用核数 = 10，实际线程数不变 |
| 同步调用第二道等待上界 `syncWaitGrace` | 3c90a5d（任务 11b） | 只影响超时路径 |
| harness：开环 64 个虚拟线程工作池、调度线程独占 cpu5、正式轮前 20s 预热段、发出滞后 p99 > 1ms 的轮判无效 | a1604d4（任务 10） | 开环尾延迟的测量口径变了（d5d620c 时没有这些） |

## 同卡并列（口径不同，仅示变化幅度）

| 卡 | 指标 | d5d620c（20261019） | d23e39d（本批） |
|---|---|---|---|
| `loopback-throughput-v1`（闭环 64） | 吞吐 / p50 / p99 / p99.9 | 79,081 req/s / 0.730 / 2.331 / 4.903 ms | 103,919 req/s / 0.570 / 1.365 / 2.531 ms |
| `loopback-latency-v1`（开环 10k/s） | p50 / p99 / p99.9 | 0.443 / 2.617 / 18.858 ms | 0.382 / 0.782 / 2.059 ms |
| `compare-closed64-rpc-v1`（闭环 64，直连） | 吞吐 / p50 / p99 / p99.9 | 该批无此卡（卡在任务 11 引入） | 101,943 req/s / 0.573 / 1.458 / 2.789 ms |
| `compare-open10k-rpc-v1`（开环 10k/s，直连） | p50 / p99 / p99.9 | 该批无此卡（同上） | 0.440 / 0.798 / 14.475 ms |

每格都取该次运行的正式轮。两张 compare 卡在 d5d620c 时还不存在，没有归档数字；能参考的只有
[flush-and-executor.md](../../../docs/probes/flush-and-executor.md) 的 E1 flush off 一侧（8dcab78 + 未提交改动，探针数字不作数）：
closed64 吞吐 90,709 / 88,039 req/s，open10k p99 0.870 / 0.867 ms。

## 观察：连接数扫描

并发 128 时，每节点连接数 2 → 4 → 8 的吞吐是 141,162 → 138,567 → 126,244 req/s，逐级下降（c8 比 c2 低约 11%），p99 从 2.191 升到 2.830 再到 4.420 ms。
压测端核的忙碌率从 63.9% 升到 78.2% 再到 85.6%，三张卡都没有 `client_bound`（阈值 90%）。服务端核从 49.9% 升到 61.5% 再到 69.7%。
这与任务 11 冒烟的结论**不一致**：任务 11 认为限制吞吐的是连接数，加到 8 条后瓶颈转到压测端。本批数据里加连接没有带来吞吐，反而让两侧 CPU 都更高。
同时并发 128 → 256（2 连接）吞吐仍从 141,162 升到 162,498 req/s，也与任务 11 "128 并发之后吞吐不再随并发增长"不一致。
任务 11 是在 flush 合并缺省开（任务 14）之前测的，口径不同；原因本批没有剥离，只记数据。

## 环境

WSL2 Ubuntu（10 vCPU；`.wslconfig` memory=8GB，报告里读到的总内存 9.7 GiB），内核 6.18.33.2-microsoft-standard-WSL2，宿主 Windows 11、AMD Ryzen 5 4600H，接通电源。服务端绑 cpu 0-4，压测端绑 5-9（开环调度线程独占 cpu5），
两端 `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`、G1，Temurin JDK 21.0.12，Netty 4.1.136。运行期间宿主空闲（用户停掉了其它任务，宿主 CPU 约 7~13%）。
compare / sweep / crossval 都不经注册中心（`--direct`），容器全部停掉；只有 loopback 两张卡按卡片声明起了 ZooKeeper 容器。
运行时间 2026-10-22 01:27 ~ 02:46（AEDT，即 UTC 2026-10-21 14:27 ~ 15:46），22 张卡加 2 次交叉验证依次跑完，中间没有插入其它负载。

## 不可外推

数字来自一台固定的笔记本，压测端与服务端同机，走 loopback，没有真实网络。它们说明这台机器上各配置之间的相对关系与变化幅度，不代表生产环境的吞吐或延迟，
也不能外推到其它硬件、内核、虚拟化方式或负载形态。单次运行（每卡 3 轮）的极差见各卡 Markdown；gRPC / Dubbo 单连接开环卡各轮 p99 波动很大（几毫秒到几十毫秒），只看量级。
