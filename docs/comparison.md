# 对比报告：本框架 vs gRPC-Java vs Dubbo 3

一页读完：同一台机器、同一个压测 harness、同一套口径卡，本框架与 gRPC-Java 1.85、Dubbo 3.3.6（triple）在 1KB 回显上的吞吐与延迟，
差距从哪来，对我们不利的数字有哪些，以及故障注入的结论。**本文每个性能数字都来自归档
[`bench/reports/20261022-d23e39d/`](../bench/reports/20261022-d23e39d/)（干净提交 d23e39d，`git_dirty = false`）里的结果文件，链接指向具体文件**；
引用探针文档的数字另行标明"探针口径"（工作区有未提交改动，只说明机制，不作为对外数字）。

**结论先行**（细节与出处在后文）：

- 开箱即用（各自缺省配置）闭环 64 并发，本框架吞吐约为 gRPC / Dubbo 的 7 倍（compare.md 的"吞吐 / 首行"列：gRPC、Dubbo 都是 0.14×）；
  连接数对齐到 2 条后差距缩小到 gRPC 0.35×、Dubbo 0.25×。开环 10000 req/s 的 p99 本框架 0.798ms，gRPC / Dubbo 单连接 20 ~ 22ms、2 连接 1.3 / 4.2ms。
- 差距的大头是**缺省执行器模型（`SynchronousQueue` 交接）与缺省连接数**，不是编解码；"业务都在 IO 线程上"的 direct 对 direct 对照里差距小得多（探针口径）。
- 对我们不利的数字照登：开环 p99.9 不领先、加连接数吞吐递减、单连接卡一轮掉 12%、连接静默挂起时没有离群摘除。
- 故障注入：P2C 躲开慢节点、failover 下 `kill -9` 零错误、有界队列在突增下快速拒绝并 1 秒恢复；静默死亡是已知短板。

## 1. 环境与口径

| 项 | 取值 |
|---|---|
| 机器 | 一台笔记本：AMD Ryzen 5 4600H，WSL2 Ubuntu（10 vCPU，内核 6.18.33.2-microsoft-standard-WSL2），接通电源；宿主运行期间空闲（[NOTES.md](../bench/reports/20261022-d23e39d/NOTES.md#环境)） |
| 绑核 | 服务端 cpu 0-4，压测端 cpu 5-9（开环调度线程独占 cpu5），两端各自独立 JVM，走 loopback |
| JVM | Temurin 21.0.12，两端 `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`、G1；Netty 4.1.136 |
| 负载 | `BenchService#echo`，1024 字节 `byte[]` 原样回显；闭环 64 并发 / 开环 10000 req/s；超时 1s |
| 轮次 | 每轮 60s（预热 20s，统计窗口 [20s, 55s]），每张卡 3 轮；闭环取吞吐中位那一轮、开环取 p99 中位那一轮为正式轮 |
| 序列化 | 本框架 Protostuff；gRPC 生成的 protobuf 消息；Dubbo triple 的 Java 接口模式缺省 hessian2（外包一层 triple 的 protobuf 信封） |
| 寻址 | 三方都 `--direct` 直连，不经注册中心（`loopback-*` 两张卡经 ZooKeeper，单列，不与对比卡并排） |
| 两种连接配置 | **开箱即用**：各框架缺省连接数（本框架每节点 2 条，gRPC 1 个 channel，Dubbo 1 条）；**连接数对齐**（`-c2` 卡）：都用 2 条 |
| 有效性 | 开环轮内发出滞后 p99 > 1ms 判无效、不参与正式轮选取；卡片断言不满足或有无效轮即判定不通过。本批 22 张卡全部通过、正式轮窗口内 0 错误；除交叉验证的闭环容量标定卡（按设计压到发压端上限）外，`client_bound`（压测端核 > 90%）全为否 |

口径细节与判定规则见 [benchmarking.md](benchmarking.md#判定与正式轮)，对等性（线程、执行器、超时、重试）见 [benchmarking.md「对比对象」](benchmarking.md#对等性线程执行器连接序列化)。

**不可外推**：压测端与服务端同机、走 loopback，没有真实网络；WSL2 虚拟化下宿主会抢占 vCPU（[噪声地板](benchmarking.md#噪声地板)）；
负载只有一种形态（1KB 回显、业务方法几乎为零，正是"框架开销占比最大"的情况）。下面的数字说明这台机器上各配置之间的相对关系与量级，
不代表生产环境的吞吐或延迟，也不能外推到其它硬件、网络、业务耗时或负载形态。每张卡只跑了一次（3 轮），轮间极差见各卡 Markdown。

## 2. 三方对比

表中每行取该卡的正式轮；并排总表（含 CPU、GC、每请求系统调用与上下文切换、连接方式与服务端配置）见
[compare/compare.md](../bench/reports/20261022-d23e39d/compare/compare.md) 与 [sweep/compare.md](../bench/reports/20261022-d23e39d/sweep/compare.md)。

### 2.1 闭环 64 并发：吞吐

| 配置 | 本框架（fixed，缺省） | gRPC（缺省执行器） | Dubbo（缺省） |
|---|---|---|---|
| 开箱即用 | [101,943.2 req/s](../bench/reports/20261022-d23e39d/compare/compare-closed64-rpc-v1-20261022-012703.md)（2 连接） | [14,386.4](../bench/reports/20261022-d23e39d/compare/compare-closed64-grpc-v1-20261022-013714.md)（1 channel，0.14×） | [14,401.8](../bench/reports/20261022-d23e39d/compare/compare-closed64-dubbo-v1-20261022-015049.md)（1 连接，0.14×） |
| 连接数对齐（2） | 同上 | [35,516.3](../bench/reports/20261022-d23e39d/compare/compare-closed64-grpc-c2-v1-20261022-014400.md)（0.35×） | [25,963.1](../bench/reports/20261022-d23e39d/compare/compare-closed64-dubbo-c2-v1-20261022-015743.md)（0.25×） |
| 连接数 1 | [92,771.5](../bench/reports/20261022-d23e39d/sweep/sweep-c1-closed64-rpc-v1-20261022-013349.md)（`sweep-c1`） | 14,386.4（即开箱即用，0.16×） | 14,401.8（即开箱即用，0.16×） |

倍数是并排表的"吞吐 / 首行"列（首行为本框架）：前两行见 [compare.md](../bench/reports/20261022-d23e39d/compare/compare.md)，连接数 1 一行见归档 [README.md](../bench/reports/20261022-d23e39d/README.md#对比表) 的"连接数对齐；connections = 1"表。同一组文件的延迟与两组核的 CPU：

| 卡 | 连接 | p50 (ms) | p99 (ms) | 客户端核 / 服务端核 CPU | write 系统调用/请求 客户端 / 服务端 |
|---|---|---|---|---|---|
| 本框架 fixed | 2 | 0.573 | 1.458 | 57.2% / 49.1% | 0.19 / 0.16 |
| 本框架 fixed（`sweep-c1`） | 1 | 0.633 | 1.540 | 38.1% / 38.8% | 0.09 / 0.10 |
| gRPC | 1 | 4.297 | 7.152 | 20.9% / 38.7% | 1.38 / 0.06 |
| gRPC c2 | 2 | 1.677 | 4.678 | 52.4% / 64.0% | 1.61 / 0.18 |
| Dubbo | 1 | 4.530 | 7.307 | 23.7% / 41.6% | 0.11 / 0.07 |
| Dubbo c2 | 2 | 2.302 | 5.784 | 56.1% / 68.0% | 0.32 / 0.17 |

读法：gRPC / Dubbo 单连接时两侧核都远没满（客户端核两成多），吞吐却只有 1.4 万——一条连接固定在一个事件循环线程上，
干活的只有两端各一个 IO 线程加缺省执行器的交接（[benchmarking.md「开箱即用与连接数对齐」](benchmarking.md#开箱即用与连接数对齐两种配置都报)）；
加到 2 条连接后 gRPC 涨到 2.47 倍、Dubbo 1.80 倍（compare.md 的连接数表）。闭环的 p99 低估尾延迟，尾延迟以开环为准。

### 2.2 开环 10000 req/s：延迟（从计划发出时刻算，修正 coordinated omission）

| 卡 | p50 (ms) | p99 (ms) | p99.9 (ms) | 服务时间 p99 (ms) | 三轮 p99 极差 |
|---|---|---|---|---|---|
| [本框架 fixed](../bench/reports/20261022-d23e39d/compare/compare-open10k-rpc-v1-20261022-013024.md)（2 连接） | 0.440 | **0.798** | 14.475 | 0.702 | 0.776 ~ 1.343 |
| [gRPC 开箱即用](../bench/reports/20261022-d23e39d/compare/compare-open10k-grpc-v1-20261022-014037.md)（1 channel） | 1.226 | 20.955 | 37.552 | 7.741 | 5.677 ~ 45.646 |
| [Dubbo 开箱即用](../bench/reports/20261022-d23e39d/compare/compare-open10k-dubbo-v1-20261022-015412.md)（1 连接） | 0.776 | 22.233 | 30.753 | 5.308 | 6.849 ~ 72.024 |
| [gRPC c2](../bench/reports/20261022-d23e39d/compare/compare-open10k-grpc-c2-v1-20261022-014722.md) | 0.585 | 1.338 | 8.511 | 1.239 | 1.278 ~ 1.466 |
| [Dubbo c2](../bench/reports/20261022-d23e39d/compare/compare-open10k-dubbo-c2-v1-20261022-020106.md) | 0.708 | 4.235 | 25.346 | 2.791 | 2.730 ~ 5.571 |

gRPC / Dubbo 单连接开环卡的各轮 p99 从几毫秒到几十毫秒，只看量级（[NOTES.md](../bench/reports/20261022-d23e39d/NOTES.md#不可外推)）。

### 2.3 本框架 fixed（缺省）vs direct（非缺省）

| 负载 | fixed（缺省） | direct（opt-in） | 来源 |
|---|---|---|---|
| 闭环 64，2 连接：吞吐 | 101,943.2 req/s | 142,671.6 req/s（fixed 为它的 0.71×） | [fixed](../bench/reports/20261022-d23e39d/compare/compare-closed64-rpc-v1-20261022-012703.md) · [direct](../bench/reports/20261022-d23e39d/compare/compare-closed64-rpc-direct-v1-20261022-020438.md) |
| 闭环 64，1 连接：吞吐 | 92,771.5 req/s | 124,541.0 req/s | [fixed](../bench/reports/20261022-d23e39d/sweep/sweep-c1-closed64-rpc-v1-20261022-013349.md) · [direct](../bench/reports/20261022-d23e39d/sweep/sweep-c1-closed64-rpc-direct-v1-20261022-021122.md) |
| 开环 10k：p50 / p99 | 0.440 / 0.798 ms | 0.251 / 0.530 ms | [fixed](../bench/reports/20261022-d23e39d/compare/compare-open10k-rpc-v1-20261022-013024.md) · [direct](../bench/reports/20261022-d23e39d/compare/compare-open10k-rpc-direct-v1-20261022-020759.md) |
| 闭环 64，2 连接：服务端核 CPU | 49.1% | 27.2% | 同第一行 |

**direct 不是缺省，也不该被当成"本框架的数字"**：它让业务方法在 Netty IO 线程上执行，省掉服务端两次线程交接（IO → 业务池、业务池 → IO），
代价是**隔离性**——业务方法一旦阻塞或耗时到毫秒级，同一事件循环上的所有连接一起卡住。它只适合纯 CPU、微秒级的方法，与 gRPC 的
`directExecutor()` 同类；1KB 回显恰好是它的最好情况，隔离性代价本批没有测。对外的数字以 fixed 为准。

### 2.4 经注册中心的单列卡

`loopback-throughput-v1`（闭环 64，经 ZooKeeper）[103,918.9 req/s，p99 1.365ms](../bench/reports/20261022-d23e39d/loopback-throughput-v1-20261022-022820.md)；
`loopback-latency-v1`（开环 10k，经 ZooKeeper）[p50 0.382 / p99 0.782ms](../bench/reports/20261022-d23e39d/loopback-latency-v1-20261022-023142.md)。
注册中心只参与发现，不在调用路径上，数字与直连的 compare 卡同一量级；口径不同，不与对比卡并排。

## 3. 差在哪

调研与探针的完整论证见 [research/transport-path-comparison.md](research/transport-path-comparison.md)（只读调研，推断）与 [probes/](probes/) 下各文。

- **服务端派发：gRPC / Dubbo 缺省执行器的 `SynchronousQueue` 交接。** gRPC 缺省执行器是进程共享的缓存线程池，Dubbo 是 200 线程的 fixed 池、
  队列缺省 0——两者都是 `SynchronousQueue`："只有某个空闲线程正在 poll 时 offer 才成功"，每个请求都要找到并唤醒一个线程
  （[调研 T2](research/transport-path-comparison.md#t2-服务端派发)）。本框架的固定池用 `ArrayBlockingQueue`（容量 1000），生产者不等消费者就位。
  在业务几乎为零的 1KB 回显上，派发交接本身就是主成本，这是开箱即用 0.14× 的主要来源之一（推断）；另一来源是缺省连接数（2 vs 1）。
- **flush 合并。** 两端 pipeline 首位的 `FlushConsolidationHandler`（缺省开）把每请求一次 flush 合成按批一次：本批闭环 64 两端每请求 `write`
  系统调用 0.19 / 0.16（gRPC 客户端 1.38），见 [compare.md](../bench/reports/20261022-d23e39d/compare/compare.md)。同一构建 on / off 的 A/B：
  闭环吞吐 2 连接 +20%、1 连接 +44%，开环无回退（探针口径，[flush-and-executor.md](probes/flush-and-executor.md)）。
- **池化分配原则：调用方线程不碰 Netty 池化分配器。** 虚拟线程没有 `PoolThreadCache`，直接争 arena 锁、扩容时钉住载体线程，
  万级并发下整个客户端卡死（[pitfalls 第 32 条](pitfalls.md)）；所以调用方 / 业务线程只序列化成 `byte[]`，帧缓冲区由事件循环分配。
  代价是比 gRPC `MessageFramer`"在应用线程直接写 ByteBuf"多一次 1KB 拷贝，这份代价没有单独测。
- **出站背压零成本。** 服务端不可写时停读、客户端优先可写连接、两端硬上限，缺省开；正常负载下相对关闭为 −1.5% ~ +1.3%（门槛 −2%），
  "只发不读"的客户端下服务端积压从 22.8MB 降到 0.16 ~ 0.35MB（探针口径，[outbound-backpressure.md](probes/outbound-backpressure.md)）；
  本批每张 rpc 卡统计窗口内的暂停读次数都是 0（[compare.md](../bench/reports/20261022-d23e39d/compare/compare.md) "暂停读次数"列）。

### 对我们不利的事实

- **direct 对 direct，差距小得多。** gRPC 缺省执行器与 `directExecutor()` 之间差 4.4 倍（[调研 0.3 节](research/transport-path-comparison.md#03-已知数字输入非本文测量)的输入：单 channel 14.7k vs 65k）；
  "业务都在 IO 线程上"的同类对照下，本框架 direct 2 连接 144k vs gRPC direct 2 个 channel 75 ~ 82k，开环 p99 本框架 **fixed** 0.71 ~ 0.88ms
  与 gRPC direct c2 0.71 ~ 0.80ms 在同一区间（探针口径，[flush-and-executor.md E2](probes/flush-and-executor.md#e2-执行器rpc-fixed--rpc-direct--grpc-direct)，gRPC direct 未进本批归档）。
  即开箱即用 0.14× 的差距里，大头是执行器模型与缺省连接数，不是"我们的编解码更快"。序列化包装的差异也没有剥离（[benchmarking.md](benchmarking.md#对等性线程执行器连接序列化)）。
- **开环 p99.9 并不领先。** 本框架 fixed 三轮 p99.9 为 19.579 / 14.475 / 7.975ms，gRPC c2 为 6.005 / 8.511 / 12.804ms
  （[rpc](../bench/reports/20261022-d23e39d/compare/compare-open10k-rpc-v1-20261022-013024.md) · [gRPC c2](../bench/reports/20261022-d23e39d/compare/compare-open10k-grpc-c2-v1-20261022-014722.md)）；
  p99.9 被宿主抢占 vCPU 的停顿主导，两边都只看量级。
- **加连接数反而递减。** 并发 128 时每节点 2 → 4 → 8 条连接，吞吐 141,161.6 → 138,567.0 → 126,244.3 req/s，p99 2.191 → 2.830 → 4.420ms，
  两侧核都更忙、都没满（[sweep/compare.md](../bench/reports/20261022-d23e39d/sweep/compare.md)）。原因本批没有剥离，与任务 11 的冒烟结论不一致（[NOTES.md](../bench/reports/20261022-d23e39d/NOTES.md#观察连接数扫描)）。
- **单连接卡第 3 轮掉 12%。** `sweep-c1-closed64-rpc-v1` 三轮吞吐 93,609.5 / 92,771.5 / 81,588.4 req/s，极差 14.7%
  （[sweep-c1](../bench/reports/20261022-d23e39d/sweep/sweep-c1-closed64-rpc-v1-20261022-013349.md)），是本批可重复性最差的 rpc 卡；第 3 轮的原因未查。
- **静默死亡没有离群摘除。** 进程存活但不回包（连接静默挂起）时，打到它的 1/3 流量持续超时到轮次结束：框架不重试超时（共享 deadline）、
  没有按节点的熔断 / 离群摘除，注册中心也不摘（见下节）。

## 4. 故障注入

三组故障都真实制造（toxiproxy toxic、`kill -9`、`SIGTERM`、`SIGSTOP`、连接静默挂起），每组都有对照，判定在跑之前登记。
**数字是探针口径**（420a1b3 + 当时的工作区改动，未归档），完整表格与时间线见 [probes/fault-injection.md](probes/fault-injection.md)。

| 实验 | 设置 | 结论 |
|---|---|---|
| 慢节点 | 3 提供者，20s 时给第 2 个的回包加 20ms 延迟，40s 移除 | P2C 把慢节点份额从 0.333 压到 0.038（RR 保持 0.333），窗口 p99 20.05 / 20.07ms vs RR 30.65 / 25.12ms；移除那一秒份额就回到 0.31 |
| 杀节点 `kill -9` | failover（重试 2）/ failfast | failover **0 错误**（死节点上的尝试立即失败、换节点）；failfast 1/3 请求失败直到 ZooKeeper 会话超时摘除（约 29s） |
| 优雅停机 `SIGTERM` | grace 0 / 2s | failover 都是 0 错误；failfast grace 0 约 100 个错误、grace 2s 0 错误——`deregisterGrace` 存在的理由 |
| 进程冻结 `SIGSTOP` | failover | 约 3 ~ 4s 超时后由出站硬上限转成可重试失败、failover 接管，错误恢复 5 ~ 7s |
| 连接静默挂起 | toxiproxy timeout toxic | **1/3 流量持续超时到轮次结束、未恢复**：已知限制，阶段 4 离群摘除 / 熔断的直接证据 |
| 突增 3 倍 | 单提供者，5000 → 15000 → 5000 req/s，队列 1000 vs 100000 | 有界队列每秒拒绝约 6.4k、成功请求 p99 封顶在 135 ~ 152ms、回落后 1s 恢复；近似无界队列 0 拒绝，但排队一路涨到 1s 超时，突增期间成功率只有 8 ~ 10%（有界 57%） |

## 5. 方法论

- **数字只由流水线产生**：harness 按口径卡（YAML）驱动独立的服务端 / 客户端 JVM，输出 JSON（事实源），Markdown 与并排表由 JSON 生成；
  环境（CPU、内核、JDK、JVM 参数、git sha 与是否有未提交改动）由程序采集。对外数字只取干净提交上的归档批。
- **预登记门槛**：每个优化先写门槛再跑（例如写队列"两张闭环卡都 ≥ +5%、交替各 2 组每组都成立、开环不回退"），跑完逐条对照；
  故障注入的判定同样先登记。
- **同尺子对照**：三方用同一个 harness、同一张卡、同一组绑核；gRPC / Dubbo 的接入只实现 harness 的 `CallTarget` SPI，
  开箱即用与连接数对齐两种配置都报，非缺省配置（direct）单独成表、标明非缺省。
- **否定结果如实入库**：每连接写队列在 flush 合并之上闭环 −5.6% ~ −11.8%、开环 p99 回退，未达门槛，实现保留在提交 `fd95e1e`、下一提交回退，
  数据与结论见 [probes/write-queue-and-context-switches.md](probes/write-queue-and-context-switches.md)。
- **harness 先被校准**：开环用 wrk2（`-U` 未修正分布，三次取中位）、闭环用 wrk 打同一个 HTTP 回显端点，比较的是两个发压工具而不是 RPC 与 HTTP；
  判据是代码常量（达成吞吐偏差 ≤ 2%、服务时间 vs wrk2 未修正 p50 / p99 偏差 ≤ 15% 或绝对差 ≤ 100µs、发出滞后 p99 ≤ 100µs）。
  开环交叉验证连续 3 次 PASS（第 1 次在 d23e39d 上，第 2、3 次在只改了对比表工具与归档的 d8dc78b 上；[第 1 次](../bench/reports/20261022-d23e39d/crossval-open-20261022-024132.md) ·
  [第 2 次](../bench/reports/20261022-d23e39d/crossval-repeat/run-2/crossval-open-20261022-032433.md) · [第 3 次](../bench/reports/20261022-d23e39d/crossval-repeat/run-3/crossval-open-20261022-033221.md)），
  harness 的尾延迟修稳过程见 [probes/harness-tail-latency.md](probes/harness-tail-latency.md)。

## 6. 复现

在 WSL2 上（标准流程与绑核见 [benchmarking.md「WSL2 上的标准流程」](benchmarking.md#wsl2-上的标准流程)）：

```bash
bash ~/rpc-bench-sync.sh && cd ~/rpc-bench
mvn -q -pl rpc-bench-e2e,rpc-bench-compare-grpc,rpc-bench-compare-dubbo -am package -DskipTests
bash bench/bin/compare.sh --out <dir>/compare                          # rpc、grpc、grpc-c2、dubbo、dubbo-c2 × closed64、open10k
bash bench/bin/compare.sh --transports rpc-direct --out <dir>/compare  # 本框架 direct（非缺省），与缺省同目录
bash bench/bin/sweep.sh --cards sweep-c1-closed64-rpc-v1,compare-closed64-rpc-v1 --out <dir>/sweep
bash bench/bin/crossval.sh --mode open --out <dir>                     # harness vs wrk2
bash bench/bin/bench-report.sh --in <dir> --out <dir>/README.md         # 汇总（含并排表与交叉验证判定）
```

确认结果 JSON 里 `git_dirty` 为 false 再拿数字；本批的运行顺序与环境见 [NOTES.md](../bench/reports/20261022-d23e39d/NOTES.md)。
