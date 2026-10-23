# 客户端离群摘除：故障注入 A/B（任务 19，阶段 4a）

回答阶段 4a 第一项："按节点的超时 / 失败率摘除"能不能止住 [fault-injection.md](fault-injection.md) 里两类持续出错的故障——
连接静默挂起（toxic-timeout：进程存活、注册中心不摘，1/3 流量超时到轮次结束）与 failfast 下的 kill -9（错误持续到 ZooKeeper 会话超时）——
同时不误伤慢节点、不在无故障时改变吞吐与延迟。全部用现有故障卡，只多一个开关 `--outlier-detection on|off`；每组 on / off 各 2 次、拓扑每次新起，
判定在跑之前登记、跑完逐条对照。**数字是探针口径**（工作区有未提交改动，`git_dirty = true`），只给机制、数据与结论。

**结论**：

- **静默挂起**（failover，toxic-timeout）：off 两次各 6.2 万个超时、到轮次结束都没恢复；on 两次都是 **5 038 个**、错误恢复 **3.0s**——
  第一批超时（1s 后）连续 5 个即摘除，之后错误只在每次到期回归时出现约 1.7k 个（1 个超时周期：放回后打到它的请求都要等满 1s 才失败），
  摘除时长 10s → 20s → 30s 倍增，60s 的轮次里只回归 2 次。总错误是 off 的 **1/12**。
- **kill -9**（failfast）：off 两次 5.0 万 / 4.8 万个传输失败，持续到 ZooKeeper 摘除（t ≈ 49）；on 两次 **221 / 397 个**，错误恢复 **1.0s**——
  几乎全部在 kill 的那一秒（连接关闭前已经发往它的请求一起失败），之后只有 10s 后到期回归时的 5 个（连续 5 个即再摘），然后注册中心摘除、状态清掉。
- **慢节点**（RR / P2C，latency 20ms）：on 下 **摘除 0 次**（8 次运行），份额与 p99 与 off 一致——离群摘除只看失败，不按延迟摘除。
- **SIGSTOP**（failover）：on 的错误恢复 **3.0s**（off 5.0s），总错误 3.4k vs 5.7k / 5.6k；on 在第一次回归时多一段约 1.7k 的超时（见下）。
- **优雅停机**（SIGTERM + deregisterGrace 2s，failfast）：on / off 都是 **0 错误、0 摘除**。
- **无故障对照**：摘除 0 次；热路径微基准（JMH）：选点过滤 0.66 ns/op，每次有响应的尝试 41 ns/op、每次坏结果 64 ns/op，**都是 0 B/op**。
  宏观吞吐 / p99 的 ±2% 在本机桌面噪声地板之下**不可判**（同一配置的轮间极差 15 ~ 28%），对照表照登，见第 4 节。

## 设置

- 机器、绑核、ZooKeeper、原生 toxiproxy、开环 5000 req/s、1KB 回显、超时 1s、预热 10s + 本轮 60s、`--open-workers per-request`：与 fault-injection.md 完全相同。
- 构建：f160df9 + 本任务的工作区改动（`git_dirty = true`）；两端 JDK 21，G1 `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`。
- 唯一的自变量：`run --outlier-detection on|off`（`FAULT_HARNESS_OPTS="--open-workers per-request --outlier-detection <on|off>"`）。on = 框架缺省阈值：
  10s 窗口内尝试 ≥ 20 且坏结果占比 ≥ 0.5，或连续 5 个坏结果；摘除 10s × 次数（上限 60s）；最多摘 50%（3 个提供者时最多摘 1 个）。
- 每组 on / off 交替跑（on-1、off-1、on-2、off-2；无故障对照的补充运行按 off、on、on、off），全部 36 次运行的轮次有效、故障按计划执行（最大偏差 300ms）。
- 时间线是每秒一行（口径同 fault-injection.md）；新增 `ejected_nodes`（该秒末被摘节点数）与累计 `ejections` / `restores`，故障表的"摘除次数" = 故障那一秒起的摘除数。
  恢复时间 = 故障时刻到"连续 3 秒错误为 0"的第一秒，**只看第一次恢复**：之后到期回归造成的错误另列。

## 1. 连接静默挂起（`fault-kill-node-failover-v1 --fault-variant toxic-timeout`）

| 运行 | 窗口错误 | 有错误的秒 | 错误恢复 (s) | 恢复前错误数 | 摘除 / 回归 |
|---|---|---|---|---|---|
| on-1 | **5 038**（timeout） | 21–22、32–33、53–54 | **2.98** | 1 695 | 3 / 2 |
| on-2 | **5 038**（timeout） | 21–22、32–33、53–54 | **2.99** | 1 694 | 3 / 2 |
| off-1 | 62 447（timeout） | 21–59 | 未恢复 | 62 447 | 0 |
| off-2 | 62 498（timeout） | 21–59 | 未恢复 | 62 498 | 0 |

```text
on-1（20.02s 加 timeout toxic；21002 是被挂住的节点）
t=20 ok=3540 err=0                | 19401=0.33 19403=0.33 21002=0.33 | ejected=0 ejections=0
t=21 ok=4765 err=1461 [timeout]   | 19401=0.48 19403=0.48 21002=0.05 | ejected=1 ejections=1   ← 21.03s 首批超时，连续 5 个即摘（10s）
t=22 ok=5001 err=234  [timeout]   | 19401=0.50 19403=0.50 21002=0.00 | ejected=1 ejections=1   ← 摘除前已发往它的请求陆续超时
t=23 ok=5000 err=0                | ...                              |
t=32 ok=4762 err=1434 [timeout]   | 19401=0.48 19403=0.48 21002=0.05 | ejected=1 ejections=2 restores=1  ← 31s 到期回归，1s 后再超时、再摘（20s）
t=33 ok=5000 err=238              |                                  |
t=53 ok=4759 err=1429             |                                  | ejected=1 ejections=3 restores=2  ← 52s 回归，再摘（30s）
off-1
t=21 ok=3333 err=1554 [timeout]   | 0.33 / 0.33 / 0.33 |
t=40 ok=3333 err=1666 [timeout]   | 0.33 / 0.33 / 0.33 |
t=59 ok=3333 err=1666 [timeout]   | 0.33 / 0.33 / 0.33 |
```

客户端日志（on-1）：`Outlier ejected 127.0.0.1:21002 of ...BenchService::default for 10000ms (ejection #1): 5 consecutive failures (last: timeout)`，
之后 20000ms（#2）、30000ms（#3）。

## 2. kill -9（`fault-kill-node-failfast-v1`）

| 运行 | 窗口错误 | 有错误的秒 | 错误恢复 (s) | 恢复前错误数 | 摘除 / 回归 |
|---|---|---|---|---|---|
| on-1 | **221**（transport） | 20、30 | **0.99** | 216 | 2 / 1 |
| on-2 | **397**（transport） | 20、30 | **0.99** | 392 | 2 / 1 |
| off-1 | 50 349（transport） | 20–50 | 30.7 | 50 349 | 0 |
| off-2 | 48 497（transport） | 20–49 | 30.0 | 48 497 | 0 |

```text
on-1（20.02s kill -9 第 2 个提供者 19402）
t=20 ok=4785 err=216 [transport] | 19401=0.47 19402=0.05 19403=0.47 | ejected=1 ejections=1   ← kill 后 145ms 摘除（6 consecutive failures）
t=21 ok=5000 err=0               | 19401=0.50 19402=0.00 19403=0.50 |
t=30 ok=4995 err=5   [transport] | 19401=0.50 19402=0.00 19403=0.50 | ejections=2 restores=1   ← 到期回归，5 个连接失败后再摘（20s）
（t ≈ 49 ZooKeeper 会话超时、推送摘除，状态清掉；20s 的第二次摘除在那之前没有到期，不再有错误）
off-2
t=20 ok=3448 err=1553 [transport] | 0.33 / 0.33 / 0.33 |
t=48 ok=3335 err=1667 [transport] | 0.33 / 0.33 / 0.33 |
t=49 ok=4722 err=277  [transport] | 0.47 / 0.06 / 0.47 |   ← 摘除推送到达
```

kill 那一秒的 216 / 392 个错误不是"摘除慢"：SIGKILL 在 13:56:04.987，摘除在 13:56:05.132（日志：6 consecutive failures），中间 145ms 里发往它的请求
没有任何失败信号——被杀进程的套接字要等内核回收完进程（1GB 预触的堆）才关闭，这些请求挂在连接上，关闭时一起以传输失败结束，第 5 个结束时即摘除，
其余已经发出、无法撤回。这段之后打到它的请求为 0。

## 3. 慢节点（`fault-slow-node-rr-v1` / `fault-slow-node-p2c-v1`，`--behind-toxiproxy all`）

| 运行 | 摘除 | 窗口 p50 / p99 / p99.9 (ms) | 节点 2 份额：故障前 / 中 / 后（每秒中位） | 故障中每秒 p50 / p99 中位 (ms) |
|---|---|---|---|---|
| rr-on-1 | **0** | 0.837 / 24.871 / 26.919 | 0.333 / 0.333 / 0.333 | 0.868 / 25.035 |
| rr-on-2 | **0** | 0.819 / 24.887 / 44.433 | 0.333 / 0.333 / 0.333 | 0.885 / 25.035 |
| rr-off-1 | 0 | 0.842 / 24.871 / 32.375 | 0.333 / 0.333 / 0.333 | 0.868 / 25.035 |
| rr-off-2 | 0 | 0.864 / 25.444 / 68.420 | 0.333 / 0.333 / 0.333 | 0.897 / 25.166 |
| p2c-on-1 | **0** | 0.800 / 18.924 / 25.281 | 0.333 / 0.034 / 0.332 | 0.795 / 22.938 |
| p2c-on-2 | **0** | 0.784 / 17.957 / 24.871 | 0.332 / 0.032 / 0.333 | 0.762 / 22.282 |
| p2c-off-1 | 0 | 0.782 / 20.365 / 49.218 | 0.334 / 0.032 / 0.333 | 0.766 / 22.807 |
| p2c-off-2 | 0 | 0.857 / 26.034 / 73.269 | 0.333 / 0.036 / 0.337 | 0.827 / 23.069 |

8 次运行没有一个错误、没有一次摘除：20ms 的延迟远低于 1s 超时，不产生坏结果。窗口 p99 / p99.9 的差异在同一配置的两次之间同样存在
（p2c-off 20.4 vs 26.0ms，后一次发出滞后 p99 1.99ms、被标志），故障期间每秒 p99 的中位数四种组合两两相差 ≤ 0.8ms。

## 4. 无故障对照

判定 4 的两条证据是：**零摘除**（全部无故障运行与慢节点运行都没有一次摘除，客户端日志里没有一行 `Outlier ejected`）与**热路径微基准**。
宏观 ±2% 在本机噪声地板之下不可判（下面的宏观对照表照登，但不据此下结论）。

**热路径微基准**（`steadyrpc-benchmark` 的 `OutlierDetectorBenchmark`，JMH 1 fork、预热 3 × 1s、测量 5 × 1s、单线程，`-prof gc`；Windows 开发机、JDK 21.0.11，
与压测机不是同一环境，只看量级）：

| 基准 | 含义 | ns/op | 分配 (B/op) |
|---|---|---|---|
| `filterNoneEjected` | 选点前过滤候选（3 个提供者，没有节点被摘）：一次 volatile 读、原样返回列表 | 0.656 ± 0.027 | ≈ 0（10⁻⁵，测量噪声） |
| `onSuccess` | 每次有响应的尝试：Map 查找、`System.nanoTime`、桶 CAS、连续计数读 | 41.3 ± 0.8 | ≈ 0（10⁻⁴） |
| `onFailureNoEjection` | 每次坏结果但不触发摘除：两个桶 CAS、连续计数自增、汇总 10 个桶判断比例条件 | 64.0 ± 1.2 | ≈ 0（10⁻³） |

每次调用多出的是一次过滤加一次结果回喂，约 42ns、不分配；按 closed64 的约 9 万 req/s 算，是每秒约 3.8ms CPU（不到一个核的 0.4%），
低于下面宏观对照能分辨的量级。跑法：`java -jar steadyrpc-benchmark/target/benchmarks.jar OutlierDetectorBenchmark -prof gc`。

**宏观对照（噪声地板下不可判）**

**closed64**（`compare-closed64-rpc-v1`：单提供者直连、2 条连接、闭环 64 并发，3 轮 × 60s；`bench/bin/compare.sh --transports rpc --loads closed64`）。
预登记是"一次"，第一对的差别（-4.7%）超出 ±2%，但 on 那一次自己的三轮就从 83k 爬到 98k，于是按 off、on、on、off 再跑两对（同卡、同判据）：

| 运行 | 三轮吞吐 (req/s) | 中位 | 三轮 p99 (ms) | 中位 |
|---|---|---|---|---|
| on-1 | 83 089 / 86 038 / 97 831 | 86 038 | 2.044 / 2.267 / 1.575 | 2.044 |
| off-1 | 89 443 / 90 301 / 91 338 | 90 301 | 1.834 / 1.904 / 1.867 | 1.867 |
| off-2 | 88 031 / 92 875 / 102 512 | 92 875 | 2.011 / 1.691 / 1.390 | 1.691 |
| on-2 | 103 575 / 105 169 / 102 780 | 103 575 | 1.419 / 1.355 / 1.394 | 1.394 |
| on-3 | 73 589 / 94 854 / 102 150 | 94 854 | 3.617 / 1.650 / 1.415 | 1.650 |
| off-3 | 94 938 / 87 199 / 99 334 | 94 938 | 1.712 / 1.973 / 1.503 | 1.712 |

三对的中位吞吐差：-4.7%、+11.5%、0.0%；均值 on 94.8k vs off 92.7k（+2.3%），p99 均值 1.70 vs 1.76ms。差的符号不一致、量级小于同一配置的轮间极差
（on-3 自己 73.6k ~ 102.2k），**本机上分不出 ±2%**。单提供者时离群摘除按设计不会摘（至少留 1 个），这张卡测的只是热路径的计数开销。

**fault-slow-node-rr --no-faults**（3 提供者经 toxiproxy、开环 5000 req/s）：

| 运行 | 吞吐 (req/s) | 窗口 p50 / p99 / p99.9 (ms) | 发出滞后 p99 (ms) | 摘除 |
|---|---|---|---|---|
| on-1 | 4 999.9 | 0.862 / 5.706 / 19.874 | 0.579 | 0 |
| off-1 | 5 000.0 | 0.873 / 4.915 / 19.694 | 0.343 | 0 |
| off-2 | 5 000.0 | 0.834 / 10.863 / 37.454 | 1.022 | 0 |
| on-2 | 5 000.0 | 0.825 / 16.245 / 57.082 | 0.951 | 0 |
| on-3 | 5 000.0 | 0.842 / 13.517 / 55.345 | 0.684 | 0 |
| off-3 | 5 000.0 | 0.835 / 3.836 / 36.733 | 0.170 | 0 |

吞吐与 p50 一致（p50 差 ≤ 1.3%）；p99 在 off 自己的三次之间就是 3.8 ~ 10.9ms（fault-injection.md 的对照两次是 10.65 / 5.24ms），on 三次 5.7 ~ 16.2ms——
on 的三次都不低于同一对里的 off，但 p99 的离散度是差值的数倍，按 1 次或 3 次都判不了 ±2%。这组的 p99 是多提供者开环拓扑下的尾部（发出滞后被标志的那类），
不是一个可以做 2% 级比较的量。

## 5. SIGSTOP（`fault-kill-node-failover-v1 --fault-variant sigstop`）

| 运行 | 窗口错误 | 有错误的秒 | 错误恢复 (s) | 恢复前错误数 | 摘除 / 回归 |
|---|---|---|---|---|---|
| on-1 | **3 356**（timeout） | 21–22、32–33 | **2.96** | 1 684 | 2 / 1 |
| on-2 | **3 357**（timeout） | 21–22、32–33 | **2.98** | 1 685 | 2 / 1 |
| off-1 | 5 745（timeout） | 21–24、48 | 4.99 | 5 743 | 0 |
| off-2 | 5 593（timeout） | 21–24、44 | 4.99 | 5 591 | 0 |

```text
on-1（20.04s SIGSTOP 第 2 个提供者 19402）
t=21 ok=4890 err=1575 [timeout] | 19401=0.49 19402=0.02 19403=0.49 | ejected=1 ejections=1   ← 21.07s 首批超时即摘
t=22 ok=5000 err=109            | 0.50 / 0.00 / 0.50               |
t=32 ok=4886 err=1557 [timeout] | 0.49 / 0.02 / 0.49               | ejections=2 restores=1   ← 31s 回归，进程仍冻结，1s 后超时、再摘（20s）
t=33 ok=5000 err=115            |                                  |
off-1
t=21 ok=3334 err=1624 [timeout] | 0.33 / 0.33 / 0.33 | retry=0
t=23 ok=4215 err=1666 [timeout] | 0.40 / 0.33 / 0.28 | retry=1241   ← 出站硬上限开始拒绝写出，failover 接管
t=24 ok=4999 err=786  [timeout] | 0.49 / 0.33 / 0.18 | retry=2463
```

on 的第一次恢复更快（摘除在第一批超时时就发生，不必等出站缓冲区写满 1MiB），但第一次回归时多一段超时：摘除期间没有请求写向它，
客户端出站缓冲区还远低于硬上限，回归后的请求照常写出、等满 1s 才超时——这就是"不做半开探测"的代价（一个超时周期 × 1/n 的流量）。
off 里出站硬上限在 2 ~ 3 秒后把它变成可重试的"未发出"，之后 failover 兜底、不再出错。两者都在 t ≈ 48 注册中心摘除。

## 6. 优雅停机（`fault-sigterm-grace2s-failfast-v1`）

| 运行 | 窗口错误 | 摘除 | 节点 2 份额 t=20 / t=21 |
|---|---|---|---|
| on-1 / on-2 | **0** / **0** | **0** / **0** | 0.04 / 0.00 |
| off-1 / off-2 | 0 / 0 | 0 / 0 | 0.04 / 0.00 |

摘注册之后 2s 内推送到达，节点在出现任何拒绝之前就离开了候选集，离群摘除没有坏结果可看；推送移除同时清掉了它的离群状态。

## 倍数衰减

本批运行之后补上（不影响上面的数字：60s 的轮次里放回后都没有连续健康满 60s 再被摘的情形）：某地址放回之后没有再被摘、满一个 `maxEjection`（缺省 60s），
累计被摘次数减 1（最低 0），每再满一个 `maxEjection` 再减 1；摘除时长仍是 `baseEjection × 累计次数`。偶发一次的节点因此不会永远背着上限时长，长期健康之后下一次摘除回到 `baseEjection`（10s）。

## 预登记判定

| 判定 | 结果 |
|---|---|
| 1. toxic-timeout：off 复现 1/3 持续超时；on 超时只持续到摘除（≤ 1 个超时周期 + 检测窗口），之后每秒错误为 0；回归后再超时即再摘，累计错误远小于 off | **达到**：off 两次 6.2 万、未恢复；on 两次 5 038，首次错误只在 21–22s（恢复 3.0s），之后只在两次回归时各约 1.7k（各 2 秒），总量 1/12 |
| 2. kill -9 failfast：off 错误持续到 ZK 摘除（≈ t=49）；on 错误在摘除后数秒内归零 | **达到**：off 到 t=49 / 50；on 恢复 0.99s，之后只有回归时 5 个 |
| 3. 慢节点 rr / p2c：on 摘除 0 次，份额与 p99 与 off 一致 | **达到**：8 次运行摘除 0 次；份额相同；故障中每秒 p99 中位相差 ≤ 0.8ms |
| 4. 无故障对照：摘除 0 次，吞吐 / p99 与 off 持平（±2%） | **摘除 0 次：达到**。**±2%：本机噪声地板下不可判**（closed64 三对差值 -4.7% / +11.5% / 0.0%、符号不一致；slow-node 对照 p99 噪声是差值的数倍），经审定改用热路径微基准替代：过滤 0.66ns、回喂 41 / 64ns，0 B/op |
| 5. SIGSTOP：on 的恢复时间不劣于 off | **达到**：2.96 / 2.98s vs 4.99 / 4.99s；但 on 在 t=32–33 多一段约 1.7k 超时（见第 5 节） |
| 6. SIGTERM grace 2s failfast：on 零错误不变，不触发摘除 | **达到**：两次 0 错误、0 摘除 |

## 复现

```bash
bash ~/rpc-bench-sync.sh && cd ~/rpc-bench
mvn -q -pl steadyrpc-bench-e2e,steadyrpc-bench-compare-grpc,steadyrpc-bench-compare-dubbo -am package -DskipTests
docker compose -f docker/compose.yaml up -d --wait zookeeper
setsid nohup ~/tools/toxiproxy-2.13.0/toxiproxy -host 127.0.0.1 -port 8475 > toxiproxy.log 2>&1 < /dev/null &
export TOXIPROXY_API=http://127.0.0.1:8475 TOXIPROXY_UPSTREAM_HOST=127.0.0.1 TOXIPROXY_LISTEN_HOST=127.0.0.1 TOXIPROXY_PORT_BASE=21000
for od in on off; do
  FAULT_HARNESS_OPTS="--open-workers per-request --outlier-detection $od" bash bench/bin/fault-run.sh --card fault-kill-node-failover-v1 --variant toxic-timeout
  FAULT_HARNESS_OPTS="--open-workers per-request --outlier-detection $od" bash bench/bin/fault-run.sh --card fault-kill-node-failfast-v1
  # 其余：--card fault-slow-node-{rr,p2c}-v1 --behind-toxiproxy all [--no-faults]、--variant sigstop、--card fault-sigterm-grace2s-failfast-v1
  BENCH_HARNESS_OPTS="--outlier-detection $od" bash bench/bin/compare.sh --transports rpc --loads closed64 --out target/compare/outlier-$od
done
```

本文的数字来自 2026-10-22 13:49 ~ 14:51 的 36 次运行（标签 `outlier-probe`）。原始结果（探针口径，不作数）归档在
[bench/reports/probes/20261022-task19-outlier-detection/](../../bench/reports/probes/20261022-task19-outlier-detection/)，来源是压测机 WSL 的 `~/t19/p/`。
