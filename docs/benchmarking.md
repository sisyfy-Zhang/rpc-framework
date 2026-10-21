# 端到端压测（rpc-bench-e2e）

`rpc-bench-e2e` 是一个纯 Java 命令行：按**口径卡**（YAML）驱动真实的 `RpcServer` / `RpcClient` 进程，输出 **JSON**（事实源）
和**由 JSON 生成的 Markdown** 报告。三条原则：

- **数字只由流水线产生**：README 里的任何压测数字都引用这里生成的报告文件，不手写、不手抄。
- **环境自动采集**：OS、CPU、内存、JDK、JVM 参数、Netty 版本、git sha 与是否有未提交改动、电源与调频策略，全部由程序读取，采集失败记 `unknown`。
- **实验带断言、能失败**：卡片可以声明断言，断言不满足或有无效轮次时 `run` 以退出码 2 结束，报告里逐条写明失败项。

它与 `rpc-benchmark`（JMH 微基准，测单个组件）互补：这里测的是两个进程之间一次完整 RPC 的吞吐与延迟。

## 目录

- [构建](#构建)
- [口径卡](#口径卡)：字段说明 · 校验规则
- [子命令](#子命令)
- [WSL2 上的标准流程](#wsl2-上的标准流程)：含发压端 GC 选择的对照依据 · CPU 绑核
- [负载模型与测量](#负载模型与测量)：闭环 · 开环 · 为什么开环按计划时刻计时
- [判定与正式轮](#判定与正式轮)
- [报告字段](#报告字段)
- [交叉验证](#交叉验证)：harness 与 wrk / wrk2 打同一个 HTTP 目标——开环测量校验、闭环发压容量标定
- [对比对象（gRPC / Dubbo）](#对比对象grpc--dubbo)：CallTarget SPI · 对等性 · 开箱即用与连接数对齐 · compare.sh
- [并发扫描与连接数扫描](#并发扫描与连接数扫描)：sweep.sh
- [故障注入](#故障注入)：慢节点 / 杀节点 / 突增 · 阶梯速率 · 故障时间表与驱动脚本 · 时间线与恢复时间 · fault-topology.sh / fault-run.sh
- [已知局限](#已知局限)

## 构建

```bash
mvn -pl rpc-bench-e2e -am package -DskipTests
```

`-DskipTests` 同时跳过 JaCoCo（根 pom 里 `jacoco.skip` 缺省取 `skipTests`），`install` / `verify` 也不需要再加 `-Djacoco.skip=true`。

产物是 `rpc-bench-e2e/target/classes` 与 `rpc-bench-e2e/target/lib/*`（`maven-dependency-plugin` 在 package 阶段复制运行时依赖，不打 fat jar），
`bench/bin/*.sh` 用这两者拼 classpath。脚本开头 `export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"`，从仓库根目录运行。

对比对象（[gRPC / Dubbo](#对比对象grpc--dubbo)）各是一个模块，跑对比卡之前一起构建：

```bash
mvn -pl rpc-bench-e2e,rpc-bench-compare-grpc,rpc-bench-compare-dubbo -am package -DskipTests
```

## 口径卡

一张卡回答一个问题，放在 `bench/profiles/<profile>.yaml`。仓库自带的卡：

| 卡片 | 问题 | 负载 |
|---|---|---|
| `loopback-throughput-v1` | 同机 loopback、1KB、单提供者的吞吐量级 | 闭环 64 并发 |
| `loopback-latency-v1` | 同一拓扑在给定负载下的延迟分布（含尾延迟） | 开环 10000 req/s |
| `crossval-http-open-v1` | harness 校验卡：开环发压与测量是否与 wrk2 一致（见[交叉验证](#交叉验证)） | HTTP，开环 5000 req/s，64 连接 |
| `crossval-http-closed-capacity-v1` | harness 校验卡：闭环发压容量标定（harness 与 wrk 各自的上限与瓶颈侧） | HTTP，闭环 64 并发 |
| `compare-closed64-<rpc\|grpc\|dubbo>-v1` | 对比对象，**开箱即用**：同机、1KB、直连（`--direct`，不经注册中心），各框架缺省连接数（本框架 2、gRPC 1 个 channel、Dubbo 1 条） | 闭环 64 并发 |
| `compare-open10k-<rpc\|grpc\|dubbo>-v1` | 同上，延迟分布 | 开环 10000 req/s |
| `compare-closed64-<grpc\|dubbo>-c2-v1`、`compare-open10k-<grpc\|dubbo>-c2-v1` | 对比对象，**连接数对齐**：到提供者 2 条连接（`target.connections: 2`，与本框架缺省的每节点 2 条对齐） | 同上 |
| `sweep-closed128-rpc-v1`、`sweep-closed256-rpc-v1` | 并发扫描：与 `compare-closed64-rpc-v1` 只差并发（64 的结果与它共用），找吞吐不再随并发增长的点 | 闭环 128 / 256 并发 |
| `sweep-c4-closed128-rpc-v1`、`sweep-c8-closed128-rpc-v1` | 连接数扫描：与 `sweep-closed128-rpc-v1`（每节点 2 条）只差 `target.connections` | 闭环 128 并发，每节点 4 / 8 条 |
| `sweep-c1-closed64-rpc-v1` | 单连接上限：与 `compare-closed64-rpc-v1` 只差 `target.connections: 1`，直接读"每连接吞吐"，与 gRPC 单 channel（`compare-closed64-grpc-v1`）对位 | 闭环 64 并发，每节点 1 条 |
| `compare-closed64-rpc-direct-v1`、`compare-open10k-rpc-direct-v1`、`sweep-c1-closed64-rpc-direct-v1` | 执行器对照，**非缺省配置**：与对应的缺省卡只差 `target.server_executor: direct`（服务端 `ExecutorConfig.direct()`，业务方法在 IO 线程上执行），与 gRPC `--executor direct` 同类的上界对照点 | 同对应的缺省卡 |
| `fault-slow-node-rr-v1`、`fault-slow-node-p2c-v1` | 故障注入·慢节点：3 个提供者（经 ZooKeeper），第 2 个在 20s 时经 toxiproxy 加 latency 20ms（jitter 5ms）、40s 移除；两张卡只差 `target.load_balance`；对照 = 同卡 `--no-faults` | 开环 5000 req/s，60s，1 轮 |
| `fault-kill-node-failover-v1`、`fault-kill-node-failfast-v1` | 故障注入·杀节点：3 个提供者，20s 时 kill -9 第 2 个；两张卡只差 `target.cluster` / `target.retries`（failover 重试 2 / failfast 重试 0）；变体 `--fault-variant sigterm`（优雅停机对照）/ `toxic-timeout`（静默死亡）/ `sigstop`（进程冻结） | 开环 5000 req/s，60s，1 轮 |
| `fault-sigterm-grace2s-failover-v1`、`fault-sigterm-grace2s-failfast-v1` | 故障注入·优雅停机：20s 时 SIGTERM 第 2 个提供者，服务端 `target.server_deregister_grace: 2s`；与杀节点卡的 `--fault-variant sigterm`（grace 0）对照 | 开环 5000 req/s，60s，1 轮 |
| `fault-burst3x-bounded-v1`、`fault-burst3x-unbounded-v1` | 故障注入·突增：单提供者，业务方法 parkNanos 1ms（`target.server_work`；10 个业务线程时容量约 9k req/s，基线约 55% 利用率，15000 为 1.6 × 容量），固定池排队上限缺省 1000 vs 100000（`target.server_queue_capacity`，近似无界，非缺省） | 开环阶梯 5000 → 15000 → 5000 req/s，各 20s，1 轮 |

loopback / compare / sweep 卡都是每轮 60s、预热 20s、统计窗口 [20s, 55s]、3 轮（fault 卡是 60s、预热 10s、窗口 [10s, 60s]、1 轮，见[故障注入](#故障注入)）；两张 crossval 卡每轮 60s、预热 20s、统计窗口 [20s, 60s]
（40s，与工具的测量时长一致）、3 轮。**改口径就复制成新 id（`-v2`），不要原地改**：报告以卡片 id 为准，同一个 id 必须始终代表同一个口径。

### 字段说明

```yaml
profile: loopback-throughput-v1      # 必填，须与文件名（去扩展名）一致
question: 同机 loopback、1KB、单提供者的吞吐与延迟量级
target:
  transport: rpc                     # rpc | http（见下文“transport: http”）| classpath 上的对比对象（grpc、dubbo，见“对比对象”）
  service: BenchService#echo         # BenchService#echo | BenchService#echoOrder
  payload: { kind: bytes, size: 1024 }   # bytes（size 字节，固定种子随机内容）| order（固定 fixture，不写 size）
  serializer: protobuf               # rpc：classpath 上的 Serializer SPI 名；对比对象：它声明的编码名
  providers: 1                       # 提供者数：开压前客户端看到的提供者数必须恰好等于它
  connections: 2                     # 可选：到每个提供者的连接数，不写 = 各框架缺省（含义随 transport，见下表）
load:
  model: closed                      # closed | open
  concurrency: 64                    # closed 必填：虚拟用户数
  rate: 10000                        # open 必填：req/s
  duration: 60s                      # 每轮时长
  warmup: 20s                        # 预热：不进统计
  steady_window: [20s, 55s]          # 统计窗口，相对本轮开始
  rounds: 3
  timeout: 1s                        # 单次调用超时（不重试）
isolation:                           # 只记录与校验，写进报告的拓扑说明
  client_process: separate_jvm       # separate_jvm | same_jvm
  server_process: separate_jvm
  same_host: true
  registry: docker-compose/zookeeper # 自由文本
measure:
  histogram: { significant_digits: 3, max: 10s }   # HdrHistogram 精度与上限
  report: [throughput, p50, p95, p99, p999, max, errors_by_status]   # Markdown 轮次表的列
assert:                              # 可选
  errors_max: 0
  p99_max: 20ms
```

| 字段 | 含义 |
|---|---|
| `target.service` / `payload.kind` | `echo` 只配 `bytes`，`echoOrder` 只配 `order`（8 字段 POJO，含 `List<String>`、`Instant`、`LocalDate`，覆盖序列化器的对象图路径） |
| `target.providers` | 开压前用 `ReferenceOptions.awaitProviders` 等到提供者出现，再要求数量恰好相等；拓扑与卡片不符时不压，直接失败 |
| `target.connections` | 可选，1 ~ 64；不写 = 各框架缺省。rpc = `RpcClientConfig.connectionsPerNode`（缺省 2）；grpc = `ManagedChannel` 数（每个一条 HTTP/2 连接，每次调用轮询取一个，缺省 1）；dubbo = 独立的客户端实例数（各自的 FrameworkModel 与引用，各一条 triple 连接，每次调用轮询取一个；缺省 1。不用 reference 的 `connections` 参数：Dubbo 3.3.6 的 triple 忽略它，实测仍是 1 条连接）。实际生效的连接数与含义写进 `run.target.connections_per_node` / `connections_meaning`，Markdown 口径表一行"连接数（target.connections）"。http 卡不接受（它的连接池是 `target.http.connections`） |
| `target.server_executor` | 只用于 rpc 卡：被压服务端的业务执行器，`fixed`（缺省 = 框架缺省 `ExecutorConfig.defaults()`）\| `virtual` \| `direct`。`compare.sh` / `sweep.sh` 据此给 bench server 传 `--executor`；压测端核对服务端环境里的 `server_executor_kind`，**不符则判定不通过**（数字不属于这张卡声明的配置），核对结果进 `validity.server_executor`；不是 `fixed` 时声明段写明"非缺省配置"。对比对象的服务端执行器由各自的服务端参数决定（`COMPARE_SERVER_ARGS_<T>`），卡上不接受这个字段 |
| `target.http.url` / `target.http.connections` | 只用于 `transport: http`，见下节 |
| `target.load_balance` / `target.cluster` / `target.retries` | 可选，只用于 rpc 卡（故障注入实验）：客户端负载均衡 SPI 名（`roundrobin` \| `p2c`）、容错策略 SPI 名（`failfast` \| `failover` \| `failback`）、`ReferenceOptions.retries`（0 ~ 10）。不写 = 框架缺省、重试 0（原口径）。命令行 `run --loadbalance / --cluster / --retries` 只在卡片没写时生效，给了不同的值是用法错误；实际值与来源进 `run.target.load_balance / cluster / retries / cluster_options_source` |
| `target.server_queue_capacity` | 可选，只用于 rpc 卡且 `server_executor: fixed`：被压服务端固定池的排队上限（0 ~ 10000000，缺省 = 框架缺省 1000），即 `server --executor-queue`；压测端核对服务端环境的 `server_executor_queue_capacity`，不符判定不通过（`validity.server_options`） |
| `target.server_deregister_grace` | 可选，只用于 rpc 卡：被压服务端的 `RpcServerConfig.deregisterGrace`（0 ~ 30s，缺省 = 框架缺省 0），即 `server --deregister-grace`；压测端核对服务端环境的 `server_deregister_grace_ms`（`validity.server_options`） |
| `target.server_work` | 可选，只用于 rpc 卡：业务方法每次返回前 `LockSupport.parkNanos` 的时长（≤ 1s，模拟 IO 等待：占着业务线程、不占 CPU），即 `server --work`；不写 = 原样立即回显。压测端核对服务端环境的 `server_work_ms`（同上） |
| `load.steps` | 可选，只用于开环：`[{rate, duration}, ...]`，按顺序切换速率（每级从级起点重新按自己的间隔计数），各级时长之和必须等于 `load.duration`；与 `load.rate` 互斥（`rate` 取峰值那一级，用于在途上限 `rate × timeout × 2`）。预热段（`--pre-warmup`）按第一级的速率跑。写了它（或 `load.faults`）本轮就记[时间线](#时间线与恢复时间) |
| `load.faults` | 可选，只用于 rpc 卡，`load.rounds` 必须为 1：故障时间表 `[{at, kind, target, ...}]`（按时间顺序）。`at` 相对**本轮开始**（预热段之后的 t = 0）、落在本轮之内；`kind` ∈ `toxic-latency`（参数 `latency` 必填、`jitter` 缺省 0、`stream` 缺省 `downstream`）\| `toxic-timeout`（`timeout` 缺省 0 = 连接挂住不回包、不关连接；`stream` 缺省 `both`）\| `remove-toxic`（删掉该代理上的全部 toxic）\| `kill-9` \| `sigterm` \| `sigstop` \| `sigcont`；`target` 是提供者编号（1 起，`fault-topology.sh` 的启动顺序），不得超过 `target.providers`。harness 不执行故障，见[故障注入](#故障注入) |
| `load.concurrency` / `load.rate` | 另一种模型的字段可以出现，只记录、不使用 |
| `load.timeout` | 引用的超时；重试固定为 0（重试会把一次失败藏进更长的延迟） |
| `measure.histogram.max` | 必须大于 `load.timeout`；超出上限的值按上限记录并计入 `histogram_clamped` |
| `measure.report` | 可选项：`throughput`、`mean`、`p50`、`p90`、`p95`、`p99`、`p999`、`max`、`errors_by_status`；JSON 总是包含全部分位数 |
| `assert.errors_max` | 全部轮次**窗口内**错误之和的上限 |
| `assert.throughput_min` | 正式轮吞吐（req/s）的下限 |
| `assert.p50_max` / `p95_max` / `p99_max` / `p999_max` / `max_max` | 正式轮对应延迟的上限（时长，如 `20ms`） |

时长一律写单位：`500ms`、`20s`、`1m`，可带小数（`2.5s`）；裸数字被拒绝（`60` 是秒还是毫秒，卡片上必须写清楚）。

### transport: http

`transport: http` 的卡压一个 HTTP/1.1 回显端点（`bench server --http-port` 的 `POST /echo`），用途是[交叉验证](#交叉验证)
harness 本身，不是比较 RPC 与 HTTP。与 rpc 卡的区别只在 `target`：

```yaml
target:
  transport: http
  service: BenchService#echo         # 只能是 echo（payload.kind 只允许 bytes：请求体就是原始字节）
  payload: { kind: bytes, size: 1024 }
  http: { url: "http://127.0.0.1:19180/echo", connections: 64 }   # transport: http 时必填
```

| 字段 | 含义 |
|---|---|
| `target.http.url` | 必填，`http://host[:port]/path`（明文 HTTP/1.1，不支持 https） |
| `target.http.connections` | 开环的连接池大小，缺省 64，范围 1 ~ 100000：决定突发（停顿、GC）时排队的长短；闭环每个虚拟用户独占一条连接（连接数 = `concurrency`），此值只记录不使用 |
| `serializer` / `providers` | 不适用（没有序列化器，目标是一个 URL），写了就拒绝；报告里提供者数记为 1 |

`run` 不带 `--registry` / `--direct`（目标就是卡上的 URL；带了反而报用法错误），也不支持 `--metrics-port`（指标 Filter 是 RPC 客户端的）。

**连接模型与在途语义**（与 wrk / wrk2 一致）：keep-alive 长连接，**每条连接同一时刻只有一个在途请求**（不做 pipelining）。
闭环 = N 个虚拟用户各占一条连接串行收发，与 `wrk -c N` 同构。开环 = 共享连接池：计划时刻到了就取一条空闲连接发出；
**没有空闲连接时排队**（公平信号量，按到达先后），下一条空闲的连接立即发出，**延迟仍从计划时刻算**——与 RPC 开环（请求在客户端里排队）
和 wrk2（请求推迟到该连接空闲）同一语义。在途（排队 + 已发出）的上限仍是驱动的 `rate × timeout × 2`，超过才 `backlog_dropped`；
排队加收发合计受 `load.timeout` 约束（等不到连接也按 `timeout` 失败）。所以十几毫秒的整机停顿只表现为尾延迟，不会让一轮无效；
`connections` 决定的是突发时排队的长短。注意 harness 的"服务时间"从发压虚拟线程开始执行算起，排队等连接的时间也在其中（无排队时
等同于"实际发出 → 完成"）。（早期实现曾在没有空闲连接时直接丢弃，WSL2 上 12.8ms 级的停顿就让开环轮次无效，已改为排队。）

http 开环卡缺省用阻塞客户端（`BlockingHttpCallTarget`，`--http-client blocking`）：每条连接一个阻塞 `java.net.Socket`（TCP_NODELAY），
平台线程工作者自己写出预先拼好的整个请求、阻塞在 read 上等响应，只解析状态行与 `content-length`；超时用 `SO_TIMEOUT`（= 本次调用剩余的
`load.timeout`），超时或出错就关掉这条连接、下次重连；连接池、排队与在途语义与下面的 netty 客户端相同。它只用于交叉验证：
**HTTP 对照端只验证 harness 的计时 / 调度 / 直方图**（调度线程、工作者池、记录器、两个计时点）；RPC 卡的路径仍是 `RpcClient`
（Netty 客户端）+ 虚拟线程工作者池，客户端里的线程交接是被测框架的一部分，照常计入。

netty 客户端实现要点（`HttpCallTarget`，http 闭环卡的缺省）：Netty `HttpClientCodec` + `HttpObjectAggregator`（8MiB），每次调用在发压的虚拟线程上写出、等响应
（与 RPC 的同步代理同一种调用方式）；IO 线程数与是否忙轮询见 [harness 实现选项](#harness-实现选项)（开环卡缺省 1 个忙轮询的事件循环，
闭环卡缺省 = 可用核数、不忙轮询），Linux 用 epoll 原生传输（netty-all 自带），其它平台 NIO；超时由每个目标
一个的扫描任务（每 10ms）判定，不用限时等待（虚拟线程上的限时 park 每次都要登记 / 撤销一个定时任务，闭环高并发下开销可观）；
超时的连接直接关闭、下次使用时重连。建连后、第一轮之前每条连接串行做 200 次回显预热 JIT（不计入任何一轮，次数写进
`run.target.pre_warmup_calls_per_connection`）：冷 JVM 第一秒的慢调用会把 64 条连接占满、后面的请求排队，测的是 JIT 而不是目标。服务端环境经 `GET /environment` 取回（与 RPC 端点的 `environment()` 同一份采集）。

### 校验规则

YAML 只用 SnakeYAML 的 `SafeConstructor` 加载成 Map（`!!java...` 这类标签直接拒绝），再逐字段映射。校验一次报告**全部**问题，每条带字段路径：

- 未知字段一律拒绝（拼错的 `warm_up` 被静默忽略比报错危险得多）；重复键拒绝。
- 必填：`profile`、`question`、`target.*`、`load.model / duration / warmup / steady_window / rounds / timeout`、`isolation.*`、`measure.histogram.*`、`measure.report`；closed 必填 `concurrency`，open 必填 `rate`。
- `profile` 须匹配 `[a-z0-9][a-z0-9._-]*` 且等于文件名去扩展名。
- 枚举：`transport`（内置 `rpc` / `http` + classpath 上 `CallTargetProvider` 声明的名字；不认识时报错并列出全部可用的 transport，最常见的原因是没把对比模块加进 `BENCH_EXTRA_CP`）、`service`（对比对象只接受提供者声明的，缺省只有 `BenchService#echo`）、`payload.kind`、`model`、`client_process` / `server_process`、`serializer`（rpc：classpath 上实际可用的 SPI 名；对比对象：提供者声明的编码名）、`report` 的每一项（不得重复）。
- 范围：`size` 1 ~ 4MiB，`concurrency` 1 ~ 100000，`rate` 1 ~ 10000000，`rounds` 1 ~ 100，`providers` ≥ 1，`connections` 1 ~ 64，`significant_digits` 1 ~ 5，时长 > 0（`warmup` 可为 0）。
- 故障注入字段（`load.steps` / `load.faults` / `target.load_balance` 等）：见上表；未知子字段、kind 与参数不相配（`kill-9` 带 `latency`）、`at` 超出本轮、`target` 超出提供者数、阶梯时长之和 ≠ `duration` 都逐条报告。
- 交叉约束：`warmup < duration`；`steady_window` 恰好两个时长，`start < end`，`start ≥ warmup`，`end ≤ duration`；`histogram.max > timeout`；开环 `rate × timeout × 2` 不超过 500 万；`payload.kind` 与 `service` 匹配，`order` 不写 `size`。
- transport：`http` 必须有 `target.http.url`（`http://`、有 host、有 path），`payload.kind` 只能是 `bytes`，不得写 `serializer` / `providers` / `connections`；
  `rpc` 与对比对象不得写 `target.http`。

## 子命令

```text
server   --port <p> [--host <公布地址>] (--registry zk://host:port | --no-registry) [--serializer protobuf] [--metrics-port <p>] [--http-port <p>]
         [--io-threads <n>] [--executor fixed|virtual|direct] [--flush-consolidation on|off]
         [--outbound-backpressure on|off] [--write-buffer-water-mark <low>:<high>]
         [--executor-queue <n>] [--work <时长>] [--advertise-port <p>] [--deregister-grace <时长>|0]   （故障注入实验，见“故障注入”）
server   --transport <grpc|dubbo|...> --port <p> ...      （对比对象的服务端，见“对比对象”）
run      --profile <card.yaml> (--registry zk://host:port | --direct host:port[,host:port]) [--out <dir>] [--label <text>] [--metrics-port <p>]
         [--open-workers per-request|pool[:N]|pool-platform[:N]] [--http-client netty|blocking] [--io-threads <n>] [--io-busy-poll on|off]
         [--pin-dispatcher <cpu>|off] [--pre-warmup <时长>|0] [--flush-consolidation on|off]
         [--loadbalance rr|p2c] [--cluster failfast|failover|failback] [--retries N]
         [--fault-schedule <file> | --no-faults] [--fault-variant sigterm|sigstop|toxic-timeout] [--topology <file>]
         （transport: http 的卡不带 --registry / --direct；对比对象只用 --direct；harness 实现选项见下文；后两行见“故障注入”）
report   --in <目录 | 结果.json> [--out <file.md>]
compare  --in <目录 | 结果.json> [--out <file.md>]
crossval --harness <结果.json> --tool wrk|wrk2 --tool-output <file>[,<file>...] [--tool-command <text>] [--tool-version <text>]
         [--cpu-tool <crossval.sh 的 CPU 采样行>] --out <dir>
```

- **server**：导出 `BenchService`（原样回显；另有 `environment()` 返回服务端启动时采集的环境），其余全部是 `RpcServer` 缺省配置——压的是开箱即用的框架。配了注册中心时等到注册完成才打印
  `bench-server ready port=... pid=...`；收到 SIGTERM / Ctrl-C 后按框架的优雅停机顺序关闭。`--host` 是注册时公布的地址（同机压测用 `127.0.0.1`）。
  `--io-threads <n>` 即 `RpcServerConfig.ioThreads`（worker 事件循环数，缺省 0 = Netty 缺省 2 × 可用核数）；实际值与业务执行器的描述写进服务端环境的
  `server_io_threads` / `server_executor`（对比表的"服务端配置"列）。
  对照实验用的两个非缺省开关：`--executor fixed|virtual|direct`（缺省 `fixed` = `ExecutorConfig.defaults()`；`direct` = `ExecutorConfig.direct()`，
  业务方法在 IO 线程上执行）与 `--flush-consolidation on|off`（缺省 `on` = `RpcServerConfig.flushConsolidation`）。实际取值写进服务端环境的
  `server_executor_kind` / `server_flush_consolidation`，ready 行末尾也有 `executor=... flush_consolidation=...`。
  出站背压 A/B 另有 `--outbound-backpressure on|off`（缺省 `on` = `RpcServerConfig.outboundBackpressure`）与
  `--write-buffer-water-mark <low>:<high>`（字节，可带 `K` / `M` 后缀，如 `256K:1M`；不给 = 框架缺省 32KiB / 64KiB），实际取值写进服务端环境的
  `server_outbound_backpressure` / `server_write_buffer_water_mark`（`low:high`，字节）/ `server_outbound_hard_limit`，ready 行末尾也有
  `outbound_backpressure=... write_buffer_water_mark=...`。见 [docs/probes/outbound-backpressure.md](probes/outbound-backpressure.md)。
- **`server --transport <name> ...`**：对比对象的服务端。除 `--transport <name>` 外的参数原样交给该 transport 的 `ServerLauncher`
  （classpath 上的 `CallTargetProvider` 提供；gRPC 是 `GrpcBenchServer`，Dubbo 是 `DubboBenchServer`），它同样打印 `bench-server ready ...` 并阻塞到关闭。
- **compare**：读目录下（递归）全部结果 JSON，把同一负载模型、负载大小与时长口径、只在 transport / 连接数 / 并发 / 速率 / `--io-threads` 上不同的结果
  并排成表，见[对比表](#对比表)。`report` 汇总时也在汇总表之后附上同一份对比表。
- **run**：读卡 → 校验 → 连目标 → 取服务端环境（`environment()`，单独 30s 超时，失败记 `unknown` 不中止）→ 开压前做一次完整回显校验 → 跑 `rounds` 轮 → 写 `<out>/<profile>-<yyyyMMdd-HHmmss>.json`，再从**读回的** JSON
  生成同名 `.md`。`--out` 缺省为归档目录 `bench/reports/<yyyyMMdd>-<sha7>/`（相对仓库根）。`--label` 原样写进报告（冒烟写 `smoke`）。
- **report**：读目录下（递归）全部 `schema_version: 1` 的结果 JSON，按卡片分节汇总成一份 Markdown；目录里的交叉验证结果（`crossval-*.json`，
  `schema: crossval/*`）汇总到末尾的"交叉验证判定"一节（每份结果的 PASS/FAIL 与逐项判定）。不传 `--out` 时打印到标准输出。
  读不了或版本不对的文件跳过并在标准错误里列出。
- **`server --http-port <p>`**（缺省关闭）：在同一进程、独立端口起 HTTP/1.1 对照端点（Netty `HttpServerCodec` + `HttpObjectAggregator`
  8MiB，keep-alive，业务在 IO 线程上完成）：`POST /echo` 原样返回请求体（`application/octet-stream`），`GET /ping` 返回 `ok`，
  `GET /environment` 返回服务端环境 JSON；其它路径 404、方法不对 405。ready 行末尾多一个 `http_port=<p>`。
- **crossval**：读一次 `transport: http` 运行的结果 JSON 与 wrk / wrk2 的原始输出（`--tool-output` 可给逗号分隔的多份，取 p99 中位那一次作对照值），并排比较、按[交叉验证](#交叉验证)的口径判定，
  写 `crossval-<mode>-<yyyyMMdd-HHmmss>.json` 与由它生成的 `.md`（闭环另有结论句）；判定 PASS 退出码 0，FAIL 退出码 2。一般由 `bench/bin/crossval.sh` 调用。

- **`--metrics-port <p>`**（`server` 与 `run`，缺省关闭）：给被压的 RpcServer / RpcClient 加上 `rpc-metrics-micrometer` 的指标 Filter 与
  在途 / 连接数 Gauge，并在该端口内嵌 Prometheus 抓取端点 `/metrics`。[docker/](../docker/README.md) 里的 Prometheus 预置了
  `9464`（server）与 `9465`（run），起了 compose 之后 Grafana 的 RPC Overview 面板实时显示 QPS、p99、在途与连接数（指标说明见
  [observability.md](observability.md)）。**指标 Filter 在热路径上，正式数字默认不开 `--metrics-port`**；开了要在 `--label` 里写明
  （如 `metrics-smoke`），JSON 的 `environment.metrics_enabled`（压测端）与 `server_environment.metrics_enabled`（服务端）如实记录两端是否开启，
  `run.target.metrics_filter` 同压测端。

退出码：`0` 通过；`1` 用法错误、口径卡不合法或运行异常；`2` 实验判定未通过。

**`--direct` 的实现**：`RpcClient` 没有直连 API，但注册中心是 SPI。harness 注册了一个只读的 `direct` 注册中心扩展（`DirectRegistryFactory`），
把 `--direct` 给出的地址当作任何被订阅服务的全部提供者、订阅时立即推送。连接池、负载均衡、Cluster、超时与注册中心模式走完全相同的代码，
只是提供者列表的来源不同；core 没有为此做任何改动。

### harness 实现选项

口径卡描述"压什么、怎么算"；下面这些是"harness 用什么方式把请求发出去"，不属于口径，但会影响 harness 自身的开销与尾延迟，
所以每一项（含实际生效情况）都进 JSON 的 `run.harness_options`，Markdown 口径表多两行"harness 选项""预热段"，交叉验证报告的设置表也列出。
缺省值来自对照实验（[docs/probes/harness-tail-latency.md](probes/harness-tail-latency.md)），不改缺省就不需要写任何选项。

| 选项 | 取值（**缺省**） | 作用 | 实验结论 |
|---|---|---|---|
| `--open-workers` | rpc 卡与 netty 客户端：**`pool`**（N 个虚拟线程工作者，N = http 卡的 `connections` / rpc 卡 64；`pool:N` 指定）；http 卡用阻塞客户端时：**`pool-platform`**（N 个平台线程工作者）· `per-request`（每请求一个新虚拟线程，旧实现） | 开环调度线程把计划时刻交给谁执行。pool：调度线程只做一次入队（有界 FIFO，容量 = 在途上限；满了计 `backlog_dropped`），工作者按入队顺序取走 | pool 比 per-request 的服务时间 p99 低约 0.1ms（短卡 0.53 vs 0.61~0.64ms）、各轮更稳。平台线程工作者配 netty 客户端没有更好（p50 反而高约 30µs）；配阻塞客户端时必须是平台线程（虚拟线程的阻塞 read 要多经 JDK poller 与载体调度两跳，p50 0.206 vs 0.148ms） |
| `--http-client` | http 开环卡 **`blocking`**；http 闭环卡 **`netty`** | http 卡的客户端：`netty`（事件循环，`HttpCallTarget`）或 `blocking`（每条连接一个阻塞 `java.net.Socket`，调用方线程自己写请求、阻塞在 read 上等响应，`BlockingHttpCallTarget`）。rpc 卡不适用 | 阻塞客户端 + 平台线程工作者：没有 IO 线程交接、没有忙等的 IO 线程，服务时间 p50 / p99 与 wrk2 未修正值基本重合（正式卡 0.146 / 0.354 vs 0.156 / 0.382ms），各轮比 netty 忙轮询稳 |
| `--io-threads` | http 的 netty 客户端：开环卡 **1**；闭环卡 **可用核数**。rpc 卡：**0 = Netty 缺省（2 × 可用核数）** | 客户端事件循环线程数。http 卡：netty 客户端的事件循环（阻塞客户端没有事件循环，记 n/a）；rpc 卡：`RpcClientConfig.ioThreads`；对比对象：gRPC 是它的客户端事件循环组，Dubbo 3.3 客户端是进程级共享事件循环、没有配置项（给了就是用法错误）。实际生效的线程数由目标在建线程组时记下（`run.target.io_threads`，`run.harness_options.io_threads` 取同一个值；Netty 在第一次建线程组时缓存核数，绑核之后再算会少算） | 单独改它（5 → 2 / 1）对 http 卡 p99 的影响在噪声范围内；与忙轮询一起用 1 个 |
| `--io-busy-poll` | netty 客户端：开环卡 **`on`**；闭环卡 **`off`** | 事件循环每圈非阻塞 `epoll_wait` / `selectNow`，从不阻塞：调用方提交写出任务时不需要唤醒它（省掉一次跨线程唤醒），代价是占满一个核。rpc 卡不适用 | 唯一能消掉 harness 固定开销的一项：服务时间 p50 从约 0.225ms 降到 0.147ms（wrk2 未修正 0.149ms），p99 与 wrk2 的差从约 120µs 降到约 85µs |
| `--pin-dispatcher` | `<cpu>` · `off`（**命令行缺省 off；`bench-run.sh` 在 Linux 绑核启动时缺省加上压测端核列表的第一个核**） | Linux：开环调度线程（`bench-dispatch`）钉到该核，进程里其余线程钉到进程亲和里剩下的核（`taskset -cp`，按 `/proc/self/task/*/comm` 找线程）；每轮计时开始前钉一次，轮末核对是否有线程又落到该核（`rounds[].scheduler.pin.stray_threads_at_end`）。非 Linux 记 `unsupported`、不报错 | 发出滞后 p99 从 0.09~0.18ms 降到 0.02~0.04ms；服务时间 p99.9 / max 明显收窄（短卡 0.66 / 3.2ms vs 5.5 / 20ms）。调度线程忙等，不钉时与 IO 线程、载体线程抢核 |
| `--flush-consolidation` | **不给 = 框架缺省（on）** · `on` · `off` | 只用于 rpc 卡：`RpcClientConfig.flushConsolidation`（客户端连接 pipeline 首位的 `FlushConsolidationHandler`）。同一构建内做 flush 合并 A/B 时与 `server --flush-consolidation` 一起改；实际取值进 `run.harness_options.flush_consolidation` 与 `run.target.flush_consolidation`。对比对象给了就是用法错误 | 见 [docs/probes/flush-and-executor.md](probes/flush-and-executor.md) |
| `--pre-warmup` | **卡片的 `load.warmup`** · 时长 · `0`（关闭） | 第 1 轮之前以同样负载跑一段，不计入任何一轮；摘要（发出数、错误、延迟、发出滞后、最大在途）进 `run.pre_warmup`，`run.harness_options.pre_warmup_ms` 记时长 | 冷 JVM 的第一段（JIT、连接）在预热段里消化。`loopback-latency-v1`：第 1 轮 p99 52.6 → 3.5ms、最大在途 4220 → 387（预热段自己 p99 512ms、最大在途 5515）；HTTP 卡上的改善小于环境噪声，详见探针 |

`bench-run.sh` 另读 `BENCH_HARNESS_OPTS`（追加在命令行参数之后，`crossval.sh` 经它启动 harness，同样生效），对照实验就是这样改选项的：

```bash
BENCH_HARNESS_OPTS="--http-client netty --open-workers per-request --io-threads 5 --io-busy-poll off --pin-dispatcher off --pre-warmup 0"   bash bench/bin/crossval.sh --mode open      # 即 d5d620c 的旧实现
```

## WSL2 上的标准流程

Windows 侧是开发目录，压测在 WSL2 的 ext4 上跑（`/mnt/<盘>` 的 9P 文件系统很慢，且会把文件系统开销混进构建与类加载）。

```bash
# 1. 同步：把 Windows 侧仓库 rsync 到 ~/rpc-bench（排除 target/）
bash ~/rpc-bench-sync.sh

# 2. 构建（WSL 侧的 JDK 21 与 Maven）
cd ~/rpc-bench
export JAVA_HOME=$HOME/tools/jdk-21
mvn -q -pl rpc-bench-e2e -am package -DskipTests

# 3. 起服务端（单独一个终端，或后台运行并等待 ready 行），绑在 0-4 号核
BENCH_SERVER_CPUS=0-4 bench/bin/bench-server.sh --port 9000 --host 127.0.0.1 --no-registry
#    用注册中心时：... --registry zk://127.0.0.1:2181

# 4. 压测（另一个终端；客户端是独立 JVM），绑在 5-9 号核
BENCH_CLIENT_CPUS=5-9 bench/bin/bench-run.sh --profile bench/profiles/loopback-throughput-v1.yaml --direct 127.0.0.1:9000
BENCH_CLIENT_CPUS=5-9 bench/bin/bench-run.sh --profile bench/profiles/loopback-latency-v1.yaml  --direct 127.0.0.1:9000

# 5. 汇总
bench/bin/bench-report.sh --in bench/reports/<yyyyMMdd>-<sha7> --out bench/reports/<yyyyMMdd>-<sha7>/README.md

# 6. 停服务端：Ctrl-C，或 kill <pid>（SIGTERM，优雅关闭）
```

两端 JVM 参数缺省为 `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`，可用 `BENCH_SERVER_JAVA_OPTS` / `BENCH_CLIENT_JAVA_OPTS` 覆盖；
压测端的实际参数进报告的 `environment.jvm_args`，服务端的进 `server_environment.jvm_args`。正式数字之前，确认 `git_dirty` 为 false（报告里会写明），电源接通、调频策略记录在案。

`BENCH_GC_LOG=1` 时两个脚本各追加 `-Xlog:gc*:file=<dir>/gc-<client|server>-<yyyyMMdd-HHmmss>.log`：压测端的 `<dir>` 是 `--out` 的值，
没有 `--out` 时与服务端一样取 `BENCH_GC_LOG_DIR`（缺省 `<仓库>/target/gc-logs`）。GC 日志只用于排查，`*.log` 被 `.gitignore` 忽略，不随归档入库；
开了它 `jvm_args` 里会多出这一项。

### 发压端 GC 选择的对照依据

压测端缺省保持 G1（`-Xms1g -Xmx1g -XX:+AlwaysPreTouch`）。对照（2026-10-19，WSL2、AMD Ryzen 5 4600H、服务端绑 0-4、压测端绑 5-9，
`loopback-latency-v1` 经 ZooKeeper，开环 10000 req/s、每次 3 轮，G1 / 分代 ZGC 交替各跑 2 次，每次重启服务端；代码为 eb846f9 + 本次改动的工作区）：

| 压测端 GC | 6 轮 p50 中位 (ms) | 6 轮 p99 范围 (ms) | p99 跨轮极差 (ms) | 6 轮 p99.9 中位 / 最大 (ms) | 压测端 GC 停顿占窗口（最大） | 服务端 GC 停顿占窗口（最大） |
|---|---|---|---|---|---|---|
| G1 `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` | 0.433 | 1.868 – 3.777 | 1.909 | 13.28 / 24.33 | 0.051% | 0.029% |
| 分代 ZGC `-Xms2g -Xmx2g -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch` | 0.436 | 1.629 – 6.566 | 4.937 | 8.99 / 28.54 | 0.003%（停顿亚毫秒，MXBean 记 0–1ms；并发周期 144–501ms/窗口） | 0.020% |

裁定规则（事先定好）：ZGC 下 6 轮 p99 的跨轮极差至少降一半、p99.9 明显更好、且 p50 不劣于 G1 5% 以上，才把压测端缺省改为 ZGC。
结果：ZGC 的 p99 极差反而是 G1 的 2.6 倍（单轮 p99 6.566ms 是全部 12 轮最高，该轮服务时间 p99 同步升到 5.956ms），p99.9 中位更低但最大值更高，
不满足——保持 G1。更重要的是两种 GC 下压测端停顿都只占窗口的 0.05% 以内（G1 每窗口 4 次、合计 6–18ms），轮间 p99 的起伏与服务时间 p99 同步，
与压测端 GC 无关：本机开环卡的尾延迟波动主要不是发压端 GC 造成的。

服务端保持缺省 G1：它是被测对象，GC 选择属于被测配置的一部分，换 GC 就是换了实验对象；需要比较服务端 GC 时另开卡片，不在压测端口径里改。

### CPU 绑核

同机压测时服务端与压测端抢同一批核，谁被调度器挤占都会混进数字；开环卡的调度线程还会忙等独占一个核（见[已知局限](#已知局限)）。
三个脚本支持 `BENCH_SERVER_CPUS` / `BENCH_CLIENT_CPUS`（`taskset -c` 的核列表，如 `0-4`）：设置了且 `taskset` 可用时以 `taskset -c <list>` 前缀启动，
否则原样启动并在 stderr 提示。实际生效的亲和由进程自己读 `/proc/self/status` 的 `Cpus_allowed_list`，写进 `environment.cpus_allowed`（压测端）
与 `server_environment.cpus_allowed`（服务端），Markdown 环境表各占一行——报告记录的是"实际绑上了什么"，不是"脚本想绑什么"。

`bench-run.sh` 在 Linux 上按 `BENCH_CLIENT_CPUS` 绑核启动时，还缺省加 `--pin-dispatcher <列表的第一个核>`：开环调度线程独占该核，
进程里其余线程（IO 线程、虚拟线程载体、GC / JIT 线程）钉到剩下的核（5-9 时即调度线程 5、其余 6-9）。实际生效情况由进程读回
各线程的 `Cpus_allowed_list`，写进 `rounds[].scheduler.pin`（口径表"harness 选项"一行显示第 1 轮的结果）；不想要时传 `--pin-dispatcher off`。

WSL2 分配 10 个 vCPU（`.wslconfig` 的 `processors=10`）时的推荐划分：

| 进程 | 核 | 变量 |
|---|---|---|
| 服务端（bench-server） | 0-4 | `BENCH_SERVER_CPUS=0-4` |
| 压测端（bench-run） | 5-9 | `BENCH_CLIENT_CPUS=5-9` |
| 容器（ZooKeeper / Nacos 等，Docker Desktop） | 不绑核 | — |

容器不绑核：Docker Desktop 的容器跑在另一个发行版（docker-desktop）里，`taskset` 管不到；用到注册中心的卡在 `isolation.registry`
里写明"容器未绑核、与压测进程共享宿主核"，报告的拓扑一句会原样带出。直连卡（`--direct`）不涉及容器。

## 负载模型与测量

### 闭环（closed）

`concurrency` 个虚拟线程，各自循环"调用 → 返回 → 立即下一次"直到 `duration`；延迟 = 调用前的 `nanoTime` 到返回。
它回答"这么多并发用户时吞吐是多少"，但会**低估尾延迟**：服务端一慢，每个用户发下一次请求的时刻随之推迟，本该排队的请求根本没有被发出，
排队时间不进任何样本（coordinated omission）。所以闭环卡的报告自动附一条声明，尾延迟以开环卡为准。

### 开环（open）

单个调度线程（`bench-dispatch`）按 `rate` 生成等间隔的计划发出时刻（第 i 个 = 开始 + i × 1/rate），远时 `LockSupport.parkNanos`、最后一段自旋对齐（自旋窗口在本轮计时开始前按实测的 park 超睡量校准，Windows 上约为一个系统定时器节拍），到点就把计划时刻交给执行者：
缺省是固定的 N 个虚拟线程工作者（`--open-workers pool`，从有界 FIFO 队列按入队顺序取），也可以每个请求一个新虚拟线程（`per-request`，旧实现）；
执行者调用同步代理方法（与闭环走同一个远程方法、同一条客户端代码路径）。Linux 上 `bench-run.sh` 缺省把调度线程单独钉到一个核（`--pin-dispatcher`）。
第 1 轮之前缺省先以同样负载跑一段预热段（`--pre-warmup`，缺省 = 卡片的 warmup，不计入任何一轮）。记录三样东西：

- **延迟** = 完成时刻 − **计划**发出时刻；
- **服务时间** = 完成时刻 − 实际开始执行时刻（执行者取到它的时刻；在工作者队列里等的时间计入延迟、不计入服务时间）；
- **发出滞后** = 调度线程实际到点 − 计划时刻（调度器自身是否可信）。

在途数（排队 + 执行中）达到 `rate × timeout × 2`（或工作者队列满）时不再发出，计为 `backlog_dropped` 错误，本轮无效：此时压测端已经跟不上计划，再发只会测压测端自己。

### 为什么开环按计划时刻计时

设想服务端停顿 1 秒。开环计划在这 1 秒里本该发出 rate 个请求，它们的真实用户体验是"等到停顿结束才被处理"，延迟从各自的计划时刻算起，
从接近 1 秒线性递减到接近 0——停顿在分布里留下了与其长度和频率相称的尾巴。如果改从"实际发出"计时（或者用闭环），这些请求要么被推迟发出、
要么根本不存在，停顿只体现为寥寥几个慢样本，p99 看起来几乎不受影响。按计划时刻计时就是把"本该发生但被推迟的等待"算回去，
这是 HdrHistogram / wrk2 修正 coordinated omission 的同一思路。服务时间直方图另外保留"真正处理用了多久"，两者的差就是排队。

### 统计口径

- 延迟分位数只统计**完成时刻落在 `steady_window` 内**的**成功**调用；失败按类型分桶（`timeout`、`status:<状态码>`、`transport`、`no_provider`、`payload_mismatch`、其它异常类名），窗口内与全程各一份，不进延迟分布与吞吐。
  `transport` = 本框架客户端自己产生的传输失败（连不上、没有可用连接、连接在回包前断开、写失败、出站硬上限未发出、客户端已关闭；它们是基类 `RpcException`、
  状态码是缺省的 SERVER_ERROR，以前落进 `status:SERVER_ERROR`，与服务端回的错误混在一起），`no_provider` = 提供者列表为空；按消息前缀识别
  （`ErrorClassifier.TRANSPORT_PREFIXES`，有一条连死端口的测试守着 core 的消息）。
- 吞吐 = 窗口内成功调用数 / 窗口长度。
- 每秒成功数与错误数的时间序列覆盖全程（含预热），用来看降频、GC 停顿与抖动。
- 同一个 `RpcClient` 跨轮复用（连接预热属于第一轮 warmup 的一部分），直方图与计数每轮清零。
- 回显校验：开压前逐字节 / 全字段比对一次；压测循环里只比长度与 id。
- **CPU 采样**（Linux；其它平台记 `n/a`）：每轮统计窗口的开始与结束各读一次 `/proc/stat`，按两组核算平均忙碌率（1 − Δidle / Δtotal，
  idle 含 iowait）——客户端核 = 压测端 `environment.cpus_allowed`，服务端核 = `server_environment.cpus_allowed`（取不到或卡片声明不同机时记 n/a）。
  结果进 `rounds[].cpu`，Markdown 轮次表多两列"客户端核 CPU / 服务端核 CPU"。采样是一个只睡眠、读两次文件的虚拟线程，不在压测路径上。
  没绑核时两组核相同（都是全部核），数字仍如实记录但不能区分两侧。
- **`client_bound`**：任一有效轮的客户端核平均忙碌率 > 90% 即 `validity.client_bound = true`，声明段加一句"本卡吞吐受发压端 CPU 限制，
  是 harness 的发压上限而非服务端上限"。它不算失败（闭环卡常常如此），只是让读者知道这个吞吐数字的瓶颈在哪一侧。
- **GC 观测**：每轮统计窗口的开始与结束（与 CPU 采样同一个虚拟线程、同两个时刻）各取一次两端的累计 GC 值做差：压测端读本进程的
  `GarbageCollectorMXBean`，服务端调 `BenchService.stats()`（HTTP 卡 `GET /stats`），结果进 `rounds[].gc.client` / `rounds[].gc.server`
  = `{collections, pause_ms, pause_pct, collectors[], heap_used_mb_start, heap_used_mb_end, heap_committed_mb}`。`pause_ms` 只累加停顿类收集器：
  G1 的 Young / Old / Concurrent GC（后者记 Remark 与 Cleanup 两个停顿）计入；ZGC / Shenandoah 的 `... Cycles` 是**并发周期**的时长（应用线程照常运行），
  列在 `collectors[]` 里（`kind: concurrent_cycle`）但不计入停顿，停顿看 `... Pauses`。MXBean 时间是毫秒粒度：ZGC 的亚毫秒停顿会记成 0。
  服务端取不到（目标不是本 harness 的 bench-server）时记 `unknown`。Markdown 轮次表多一列"GC 停顿 客户端/服务端 (ms)"。
- **发压端 GC 声明**：任一有效轮的压测端 GC 停顿超过窗口时长的 1%，声明段加一句"发压端 GC 停顿占窗口 x%（第 n 轮，有效轮中最高），
  可能抬高尾延迟"（`validity.client_gc_pause_pct_max` 记录这个最大值）。不算失败：开环按计划时刻计时，发压端停顿期间该发未发的请求会把停顿
  算进延迟——这本是修正 coordinated omission 的正确做法，但停顿来自压测端而不是被测服务。
- **系统调用与上下文切换**（Linux；其它平台记 `n/a`）：与 GC 同两个时刻各取一次两端的累计值做差——读写系统调用来自 `/proc/self/io` 的
  `syscr` / `syscw`（内核按线程组累计；套接字收发与 eventfd 唤醒——Netty 的 `selector.wakeup()`——都算，`epoll_wait`、`futex` 不算），
  上下文切换是 `/proc/self/task/*/status` 的 `voluntary_ctxt_switches` / `nonvoluntary_ctxt_switches` 对存活线程求和
  （`/proc/self/status` 的这两项只是主线程的值，不能用；窗口内退出的线程其计数会丢失，`threads` 记录两次快照的线程数以便核对）。
  服务端经 `BenchService.stats()`（gRPC 的 `Stats`、HTTP 卡 `GET /stats`）返回同一份。结果进 `rounds[].io = {source, window_requests,
  client: {syscr, syscw, vol_cs, nonvol_cs, threads, *_per_request, by_group}, server: {...}}`，每请求值 = 差值 / 窗口内完成的请求数（成功 + 失败，即 `window.requests`）。
  Markdown 轮次表多一列"write 系统调用/请求 客户端/服务端"，对比表另有"上下文切换/请求"。
- **上下文切换按线程组**（`rounds[].io.<端>.by_group`，Linux）：同一次遍历里再读每个线程的 `/proc/self/task/<tid>/comm`（线程名的前 15 字节），
  按前缀归组：`netty_io`（`rpc-client-io` / `rpc-server-work` / `rpc-server-boss`，对比对象与 HTTP 卡的事件循环）、`biz_pool`（`rpc-server-biz`、
  gRPC / Dubbo 的业务线程）、`fjp`（`ForkJoinPool*`：虚拟线程载体与 commonPool）、`timers`（`CompletableFutu`——`orTimeout` 的延迟调度线程、
  `VirtualThread-u`——虚拟线程 unparker）、`harness`（`bench-*`：开环调度与工作者线程）、`jvm`（GC / JIT / VM 内部线程）、`other`（其余，含名为
  `java` 的主线程）。每组记 `threads`（两次快照的存活线程数）、`vol_cs` / `nonvol_cs` 差值与 `vol_cs_per_request` / `nonvol_cs_per_request` /
  `cs_per_request`。各组之和等于该端的 `vol_cs` / `nonvol_cs`（同一批线程）。窗口内退出又新建的线程（例如 JIT 的动态编译线程）会让所在组出现
  小的负值，与总数的口径相同。服务端经同一个统计端点返回（键 `io.group.<组>.vol_cs` 等），旧服务端没有这些键时该端不出 `by_group`。
  Markdown 报告在轮次表后给正式轮的分组小表。实测归因见 [docs/probes/write-queue-and-context-switches.md](probes/write-queue-and-context-switches.md)。
- **出站背压**（`rounds[].backpressure`，只有 rpc 服务端）：服务端统计端点（`BenchService.stats()`，与 GC / io 同一次调用）另回传
  `RpcServer.autoReadPauses()` / `outboundOverloadRejections()` 的累计值（键 `backpressure.auto_read_pauses` / `backpressure.outbound_overload_rejections`），
  压测端在窗口起止各取一次做差；同两个时刻再取压测端 `RpcClient` 的 `sendBufferRejections()` / `unwritableConnectionSwitches()`
  （`CallTarget.clientStats()`，只有 rpc 卡）：`rounds[].backpressure = {source, window: {auto_read_pauses, outbound_overload_rejections,
  client_send_buffer_rejections, client_unwritable_switches}, cumulative_at_window_end: {...同四项}}`。`auto_read_pauses` 是窗口内服务端因连接不可写（待写字节超过高水位）而暂停读的次数，量的是水位线相对负载
  是否偏低（频繁翻转 autoRead）；`outbound_overload_rejections` 是因待写字节超过出站硬上限而回 SERVER_OVERLOADED 的请求数；
  `client_send_buffer_rejections` 是压测端因连接待写字节超过客户端硬上限而没有发出的请求数，`client_unwritable_switches` 是压测端取连接时
  跳过不可写连接、改用另一条的次数（两者只进 JSON，不进 Markdown；对比对象与 HTTP 卡不出这两项）。
  服务端统计取不到记 `unknown`，取到了但没有这些键（对比对象、HTTP 端点、旧服务端）记 `n/a`。Markdown 轮次表多一列"暂停读次数"
  （窗口内有硬上限拒绝时追加"（硬上限拒绝 m）"）。
- **JFR**：`BENCH_JFR=1` 时 `bench-server.sh` / `bench-run.sh` 给 JVM 追加 `-XX:StartFlightRecording=filename=<dir>/jfr-<server|client>-<yyyyMMdd-HHmmss>.jfr,settings=profile`
  （服务端 `<dir>` = `BENCH_JFR_DIR`，缺省 `target/jfr`，`compare.sh` / `sweep.sh` 设为结果目录；压测端 = `--out`）。`*.jfr` 已被 `.gitignore` 忽略，不随归档入库；
  `settings=profile` 有可见的开销，录了 JFR 的运行不作正式数字。

## 判定与正式轮

- **故障注入 / 阶梯卡例外**（卡片有 `load.faults` 或 `load.steps`）：发出滞后 p99 > 1ms 只记标志（`rounds[].dispatcher_delayed` 与
  `dispatcher_delayed_informational`、`validity.dispatcher_delayed`），**不判无效**；声明段写明，Markdown 轮次表的延迟格加 † 并在表下注明
  "发出滞后 p99 X ms，延迟数字仅供参考"。理由：同机多提供者拓扑下（3 个服务端 JVM + ZooKeeper）压测端 CPU 约为单提供者直连的 1.5 ~ 2 倍，
  发出滞后 p99 常在 2 ~ 8ms（单提供者直连同负载约 0.07ms），而这类卡看的是错误数、恢复时间与节点份额，它们不受几毫秒的滞后影响。
- **有效轮**：预热期内有成功调用（`warmup_completed`）、没有 `backlog_dropped`、窗口内至少一次成功；开环另要求**轮内发出滞后 p99 ≤ 1ms**
  （按报告写出的毫秒值比较）。超过即 `valid = false`、原因 `dispatcher_delayed`（`rounds[].dispatcher_delayed = true`），声明段写明是哪几轮：
  调度线程自己被推迟时，从计划时刻算的延迟里混进了压测端的滞后，这一轮的尾延迟说的是压测端而不是被测服务。这样的轮不参与正式轮选取，
  数字仍列在轮次表里；交叉验证的前提检查（全部轮次有效）随之不通过。1ms 是调度器的设计目标（正常时 p99 在几十微秒），交叉验证另有更严的 100µs（c 项）。
- **正式轮**：闭环取窗口吞吐中位数那一轮，开环取 p99 中位数那一轮（偶数轮取下中位；有有效轮时只在有效轮里选）。正式轮在 Markdown 里加粗，断言里的延迟与吞吐按正式轮判定。
- **极差**：各轮吞吐与各分位数的 max − min 及其相对最小值的百分比，衡量本机的可重复性。
- **通过** = 全部轮次有效 且 全部断言满足（rpc 卡另要求服务端报告的执行器与卡片的 `target.server_executor` 一致）；否则退出码 2。

## 报告字段

JSON 顶层（`schema_version: 1`）：

| 字段 | 内容 |
|---|---|
| `label` | `--label` 的值 |
| `profile` | `id`、`parsed`（解析后的结构，时长同时给文本与毫秒）、`raw_yaml`（卡片原文） |
| `run` | `command`（完整命令行）、`target`（直连 / 注册中心、实际提供者地址、序列化器、负载均衡、每节点连接数） |
| `run.target`（rpc 卡与对比对象） | `transport`、`mode`、`address`、`providers`、`serializer`、`connections_per_node`（实际连接数：rpc 每节点连接、gRPC channel 数、Dubbo 客户端实例数）、`connection_model`（一句话的连接与多路复用方式）、`connections_meaning` / `connections_source`（`target.connections` 在这个 transport 上的含义、取自卡片还是缺省）、`io_transport`、`io_threads`（客户端事件循环的实际线程数）、`retries`、`call_style`；对比对象另有 `grpc_version` / `dubbo_version`、`retry_policy`、`url` 等 |
| `environment` | 压测端：OS、`wsl`、CPU、`available_cpus`（JVM 可用核数 = `availableProcessors`，受 taskset 影响）、`host_cpus`（主机逻辑核数：Linux 数 `/proc/cpuinfo` 的 processor 行、读不到用 `nproc --all`，Windows 读 `NUMBER_OF_PROCESSORS`）、`cpus_allowed`、内存、JDK、`jvm_args`、GC、Netty、`git_sha` / `git_dirty` / `git_dirty_files`、主机名、`power_supply`、`cpu_governor`、开始 / 结束时间、`harness_version`、`pid`、`metrics_enabled`（`--metrics-port` 是否开启）。`available_cpus` 原名 `logical_cpus`，旧结果文件照常渲染 |
| `server_environment` | 服务端同一套字段（值压平成字符串，列表以空格连接），服务端启动时采集、预热前经 `BenchService.environment()`（HTTP 卡经 `GET /environment`）取回；取不到时为 `"unknown"`。HTTP 端点的这份环境里 `metrics_enabled` 恒为 `false`（HTTP 路径上没有指标 Filter），另有 `endpoint: http`。rpc 服务端与对比对象的服务端另有 `transport`、`server_io_threads`（worker 事件循环的实际线程数）、`server_executor`（业务方法在哪执行）、`server_executor_kind`（机器可读：rpc 为 `fixed` / `virtual` / `direct`，gRPC 为 `grpc-default` / `fixed` / `direct`）；rpc 服务端另有 `server_flush_consolidation`（`on` / `off`）、`server_outbound_backpressure`（`on` / `off`）、`server_write_buffer_water_mark`（`low:high`，字节）、`server_outbound_hard_limit`（字节，0 = 关闭）；gRPC 另有 `grpc_version` / `grpc_netty_version`，Dubbo 另有 `dubbo_version` |
| `run.target`（http 卡） | `transport` / `mode` = http、`address`（URL）、`connections`、`connection_model`、`io_transport` / `io_threads` / `io_busy_poll`、`timeout_check`、`pre_warmup_calls_per_connection`、`call_style` |
| `run.harness_options` | [harness 实现选项](#harness-实现选项)的实际取值：`open_workers` / `open_pool_size`、`io_threads`（rpc 卡与对比对象取 `run.target.io_threads`）/ `io_busy_poll`（只对 http 卡）/ `io_source`、`pin_dispatcher`、`pre_warmup` / `pre_warmup_ms` / `pre_warmup_source`、`flush_consolidation`（rpc 卡：`default (on)` / `on` / `off`） |
| `run.faults`（有 `load.faults` 的卡） | `mode`（`inject` = 执行卡片的故障 \| `control` = `--no-faults` 对照）、`variant`（`--fault-variant`）、`schedule_file`、`faults`（交给驱动脚本的故障，已按变体替换）、`topology`（`--topology` 读到的"编号 → 公布地址"） |
| `run.target` 的集群字段（rpc 卡） | `load_balance` / `cluster`（实际 SPI 名）、`retries`（实际重试次数）、`cluster_options_source`（各自来自 card / command line / default）；多提供者或有时间线时另有 `server_stats_provider`：窗口的服务端 GC / io / 背压 / 执行器统计只取这一个提供者（按地址排序的第一个；以前经负载均衡取，起止两次可能落在不同节点） |
| `run.pre_warmup` | 预热段的摘要（`duration_ms`、`issued`、`successes`、`errors`、`latency_ms`、`service_time_ms`、`dispatch_lag_ms`、`backlog_dropped`、`scheduler`）；关闭时为 `"off"` |
| `rounds[]` | 每轮：开始 / 结束、`issued`、`requests_total`、`window`（请求 / 成功 / 错误）、`throughput_rps`、`latency_ms`（count / mean / min / p50 / p90 / p95 / p99 / p999 / max）、开环另有 `service_time_ms` 与 `dispatch_lag_ms`、`errors_by_type` / `errors_total_by_type`、`backlog_dropped`、`histogram_clamped`、`scheduler`（开环调度器实况：节拍、`max_inflight`、`workers` / `pool_size`、`dispatcher_thread`、`pin`——绑核读回的结果与轮末的 `stray_threads_at_end`）、`warmup_completed`、`dispatcher_delayed`（开环）、`valid` / `invalid_reasons`、`timeseries`、`cpu`（统计窗口内两组核的平均忙碌率，见[统计口径](#统计口径)）、`gc`（统计窗口内两端的 GC 次数与停顿，同上）、`io`（统计窗口内两端的读写系统调用与上下文切换及其每请求值，同上）、`backpressure`（统计窗口内 rpc 服务端的暂停读次数与出站硬上限拒绝数，同上）、`server_executor`（统计窗口内 rpc 服务端的业务执行器过载拒绝 `executor_rejections`、停机拒绝 `shutdown_rejections` 与排队等待 `queue_wait_ms` = {count, p50, p99, max}；见[故障注入](#故障注入)）；卡片有阶梯或故障时另有 `timeline` / `steps` / `faults`（见[时间线与恢复时间](#时间线与恢复时间)） |
| `summary` | `official_round`、`selection_rule`、`official`（正式轮的数字）、`spread`（各指标的 min / max / range / range_pct） |
| `validity` | `passed`、`warmup_completed`、`server_options`（rpc 卡：服务端排队上限与业务等待的期望 / 实际 / 是否相符）、`faults_executed`（注入模式：全部故障被驱动脚本报告为 ok 且实际时刻与计划相差 ≤ 500ms）、`backlog_triggered`、`all_rounds_valid`、`dispatcher_delayed_rounds`、`client_bound`、`client_gc_pause_pct_max`（有效轮压测端 GC 停顿占窗口的最大百分比）、`server_executor`（rpc 卡：卡片声明的 `expected`、服务端报告的 `actual`、`matches`）、`statements`（闭环声明、client_bound 声明、发压端 GC 声明等）、`topology`、`asserts[]`（阈值、实测、口径、是否通过） |

Markdown 由 JSON 读回后生成，分节：标题（卡片 id）与判定 → 问题 → 环境表（含"可用核数 / 主机核数"、压测端 / 服务端的 JVM 参数与 CPU 亲和、
"指标"一行列出两端的 `metrics_enabled`）→ 口径表（末尾两行"harness 选项""预热段"）→ 轮次表（正式轮加粗，末尾是客户端核 / 服务端核的 CPU 忙碌率、两端 GC 停顿、两端每请求的 write 系统调用数与服务端暂停读次数）→ 断言表 → 声明（"数字来自固定本机，不可外推" + 拓扑一句 + 模型声明）。
表格是 GitHub 风格，可以直接嵌进 README。

JSON 用手写的极简写出器生成；读回复用 SnakeYAML（JSON 是 YAML flow 风格的子集），写出器只产出两边语义一致的子集（字符串一律双引号、小数不用指数）。

## 交叉验证

目的：校验 harness 本身。让 harness 与第三方工具打**同一个**目标——`bench server --http-port` 的 HTTP 回显端点——比较两个发压工具，
不是比较 RPC 与 HTTP。开环与闭环回答的问题不同：

- **开环 = 测量校验**（vs wrk2）：harness 的发压节拍与延迟测量是否可信。
- **闭环 = 发压容量标定**（vs wrk）：harness 与 wrk 各自能压到多少、瓶颈在哪一侧。不对吞吐设通过阈值——JVM 同步调用的 harness 每次调用
  要在发压虚拟线程与 Netty IO 线程之间交接两次（写出、唤醒），这份固有开销决定了它的闭环上限，这不是测量误差；wrk 在单个 epoll 线程里收发，
  常常先压满服务端。

### 工具

wrk（闭环）与 wrk2（开环，按计划时刻计时、修正 coordinated omission）从源码编译到用户目录，并打两处补丁：墙上时钟改单调时钟（WSL2 墙钟回跳
会让 wrk2 abort、让 wrk 出现假 timeout，见 [pitfalls 第 30 条](pitfalls.md)），以及 wrk2 的 Lua 字节码改经 C 文件编译（新版 binutils 下原规则
产出的目标文件损坏）。补丁文件、验证过的上游提交与完整编译步骤见 [bench/tools/README.md](../bench/tools/README.md)。

### 流程

```bash
bash ~/rpc-bench-sync.sh && cd ~/rpc-bench
mvn -q -pl rpc-bench-e2e -am package -DskipTests
bash bench/bin/crossval.sh --mode open      # 测量校验：crossval-http-open-v1 vs wrk2 -R 5000 -U
bash bench/bin/crossval.sh --mode closed    # 容量标定：crossval-http-closed-capacity-v1 vs wrk
# 归档：输出到该 sha 的归档目录，与同一 sha 的压测结果放在一起（--label 缺省 crossval）
bash bench/bin/crossval.sh --mode open --out bench/reports/<yyyyMMdd>-<sha7>
```

`crossval.sh` 起 `bench server --port 19100 --http-port 19180 --no-registry`（`BENCH_SERVER_CPUS`，缺省 0-4）→ harness 跑对应的卡
（`BENCH_CLIENT_CPUS`，缺省 5-9，`--label` 透传，缺省 `crossval`；每轮统计窗口自带 CPU 采样）→ 停 harness 后用工具打同一 URL（`taskset -c 5-9`）：每次先预热 20s
跑一次丢弃，再测 40s（与卡片的统计窗口等长）；开环（wrk2）这样测 **3 次**，闭环（wrk）1 次（`CROSSVAL_TOOL_RUNS` 可改），
第 1 次测量的中段采样一次 `/proc/stat`。wrk：`-t 4 -c 64 -d 40s --latency -s bench/bin/post-1k.lua`；
wrk2 另加 `-R 5000 -U`（`-U` 让同一次运行同时输出修正后与未修正分布）。`post-1k.lua` POST 固定的 1024 字节请求体。最后调用 `crossval`
子命令写 `crossval-<mode>-<时间>.json` / `.md`（`--out`，缺省 `target/crossval/`，被 .gitignore 忽略）。**正式的交叉验证结果进该 sha 的归档目录**
`bench/reports/<yyyyMMdd>-<sha7>/`（`--out` 指向它），作为该 sha 下 harness 可信度的证据：`bench-report.sh` 汇总时在"交叉验证判定"一节列出
每份结果的判定与逐项数字。调试脚本时的试跑留在缺省的 `target/crossval/`。

### 开环判定（测量校验）

harness 取正式轮（与 `run` 的选法相同：有效轮里 p99 的中位那一轮）；wrk2 取 3 次测量里**未修正 p99 的中位那一次**作对照值
（对称口径，三次的数字都写进 JSON 的 `tool.measurements` 与 Markdown 的"wrk2 各次测量"表，被选中的标"对照值"）。
相对偏差 = |harness − wrk2| / wrk2（以第三方工具为参照）。三项分别判定：

| 项 | 检查 | 判据 |
|---|---|---|
| a. 达成吞吐 | harness、wrk2 各自相对目标 rate | 偏差都 ≤ 2% |
| b. 测量一致性 | harness 的**服务时间**（实际发出 → 完成）vs wrk2 的**未修正**延迟（实际发出 → 响应），p50 与 p99 | 相对偏差 ≤ 15% **或**绝对差 ≤ 100µs（取宽者：亚毫秒级时两个工具的时钟与记录点不同，百分比会被放大） |
| c. 节拍质量 | harness 的发出滞后（计划时刻 → 调度线程到点）p99 | ≤ 100µs |
| 前提 | harness **有效轮 ≥ 2**（卡片 3 轮；正式轮在有效轮里选）、有效轮窗口内无错误；wrk2 **每一次**测量都无 socket 错误、无非 2xx/3xx 响应 | — |

为什么 b 比较的是服务时间与**未修正**延迟：两边的起点定义一致（都从实际发出算）。harness 的 CO 修正延迟 = 服务时间 + 发出滞后，发出滞后由
c 独立测量并约束，所以 b、c 都通过就说明 harness 的开环延迟可信。wrk2 的"修正后"延迟不能做亚毫秒级的参照：wrk2 推迟发送时把等待向上取整到
整毫秒（giltene/wrk2@44a94c1 `src/wrk.c` 第 604 行 `msec_to_wait = round((time_usec_to_wait / 1000.0L) + 0.5)`，第 608–609 行交给毫秒粒度的
ae 定时器），每个请求都比计划时刻晚发零点几到一毫秒多，这段滞后计入它的修正后延迟（本机实测修正后 p50 约为未修正的 6 倍，见
[pitfalls 第 31 条](pitfalls.md)）。报告仍把两边的修正后延迟并排列出，标注"信息项，不判定"。

亚毫秒级对比以 100µs 绝对差为主判据。早先的实现里 b 项的偏差主要是 harness 每次调用两次线程交接（发压虚拟线程 ↔ Netty IO 线程）
的固定开销（p50 约 75µs）。HTTP 适配器是 harness 自己的代码，交叉验证要比较的是驱动与计时，所以把这段开销从适配器里拿掉：
先试过让 Netty 事件循环忙轮询（写出不再需要唤醒 IO 线程，p50 与 wrk2 重合，但忙等的 IO 线程更容易撞上宿主对 vCPU 的抢占，
正式卡 3 次只过 1 次），最终缺省是阻塞客户端 + 平台线程工作者：调用方线程自己 write / read，没有 IO 线程，正式卡上服务时间 p50 / p99
与 wrk2 未修正值的差在 ±30µs 以内。RPC 卡里 `RpcClient` 的 IO 线程交接是被测框架的一部分，照常计入。

**为什么参照端也测 3 次取中位（对称口径，`crossval/3`）**：环境停顿（宿主抢占 vCPU，见噪声地板）对两边一样生效。以前 harness 跑 3 轮取中位、
wrk2 只测 1 次，参照值撞上一次停顿就整次 FAIL：任务 10 的第 4 次正式交叉验证里 wrk2 未修正 p99 是 0.537ms（同期其余 27 次测量都在
0.329~0.382ms），harness 三轮 0.327~0.375ms，偏差 35% 全部来自参照端。两边都按"多次取中位"选代表值，单次停顿就不会单独决定判定；
阈值不变。

**为什么前提是"有效轮 ≥ 2"而不是"全部轮有效"**：开环轮被判无效的典型原因是 `dispatcher_delayed`——调度线程自己被环境停顿（宿主抢占 vCPU，
见噪声地板）推迟，这条规则本来就是为把这种轮排除出正式轮选取而设的；正式轮只在有效轮里按原规则（p99 中位）选。如果再因为它让整次
交叉验证 FAIL，等于一次环境停顿就否决 harness 的测量。剩 1 个有效轮时只有一个样本，证据不足，判 FAIL。b / c 项的阈值不变。

### 噪声地板

开环 p99 在本机上的漂移，有多少是环境本身的（2026-10-19，WSL2 10 vCPU、AMD Ryzen 5 4600H、宿主 Windows 同时运行桌面应用；细节见探针）：

- **wrk2 自己很稳**：同一时段连跑 2 次（各预热 20s + 测 40s），未修正 p99 0.339 / 0.346ms（差 7µs、2%）；穿插在 2 小时实验里的 19 次
  wrk2 测量（20s / 40s）未修正 p99 全部在 0.329 ~ 0.376ms。
- **harness 旧实现连跑 2 次**（短卡，per-request、不绑核、无预热段）：正式轮服务时间 p99 0.636 / 0.608ms，各轮 0.57 ~ 1.43ms。
- **vCPU 被宿主抢占**：一个钉在 cpu5 上忙等读 `nanoTime` 的线程（`Hiccup`），空闲系统上 40s 里有 2.4% 的时间没跑上（最长一次 10.3ms，
  ≥ 1ms 的空洞 70 次）；wrk2 加压期间 30s 里 2.0%（最长 2.1ms）。忙等的线程（开环调度线程、忙轮询的 IO 线程）比大部分时间在睡的
  wrk2 线程更容易撞上这种空洞，这是 harness 的 p99.9 / max 远大于 wrk2 的主要来源，也是同一配置各轮 p99 起伏（0.40 ~ 0.48ms）的来源。

这些数字只用来解释交叉验证的起伏，不用来放宽阈值：阈值仍是代码常量。

### 闭环判定（容量标定）

报告 harness 与 wrk 的闭环吞吐、两次运行中客户端核与服务端核的 CPU 忙碌率（harness 取正式轮统计窗口的 `rounds[].cpu`，wrk 取测量运行中段的
采样），并输出结论句"harness 闭环发压上限约 X req/s（本机、1KB、64 连接）；wrk 测得的服务端上限约 Y req/s"（措辞随瓶颈侧变化：某一侧平均
≥ 90% 即为该侧饱和）。**唯一的判定项**：两次运行的 CPU 采样都成功，且都能判别瓶颈侧——判别不了（非 Linux、未绑核、负载太轻）时结论句没有依据，
判 FAIL。吞吐与延迟各列只作信息项（wrk 闭环不做 CO 修正）。

阈值都是代码常量，没有命令行开关：放宽阈值就不再是同一个校验。

## 对比对象（gRPC / Dubbo）

同一台机器、同一个 harness、同一套口径卡，把本框架与 gRPC（grpc-java）、Dubbo 3（triple）并排压。**基础性能对比排除注册中心变量**：
三方都用 `--direct` 直连（rpc 卡的 `isolation.registry` 写 `none (--direct)`）。

### CallTarget SPI

harness 只依赖 `CallTarget`（一次 `call()` = 一次同步请求）。卡片的 `target.transport` 是内置的 `rpc` / `http` 之外的名字时，harness 用
`ServiceLoader<CallTargetProvider>` 在 classpath 上找同名的提供者：

```java
public interface CallTargetProvider {
    String transport();                       // 卡片 target.transport 的取值
    Set<String> serializers();                // target.serializer 允许的取值（该框架自带的编码名）
    default Set<String> services() { ... }    // 缺省只有 BenchService#echo
    CallTarget create(Profile profile, CallTarget.Endpoint endpoint, HarnessOptions options);
    default Optional<ServerLauncher> serverLauncher() { ... }   // bench server --transport <name> 的服务端
}
```

- 读卡时就按提供者校验（transport 名、serializer、service）；不认识的 transport 报错并列出可用的全部 transport。
- 公开给对比模块的类型只有：`CallTarget`、`CallTargetProvider`、`ServerLauncher`、`BenchCallException`（自带错误分桶名）、
  `ServerProbes`（服务端环境 / GC 统计 / 压测负载，与 bench server 同一份采集）、`Profile`、`HarnessOptions`、`BenchService` / `BenchServiceImpl`、`Bench.execute`。
  对比模块不依赖 harness 的包内实现，也不依赖 rpc-core 以外的本仓库模块。
- 对比模块的 `target/classes:target/lib/*` 经 `BENCH_EXTRA_CP` 加到脚本 classpath 的**最前面**：对比模块的第三方 jar 按它自己解析出的版本生效
  （例如 grpc-java 需要的 guava 33.6.0-android 压过 curator 带来的 32.0.0-jre），运行时与该模块的测试同一组版本。`lib/` 里不复制本仓库的模块，
  它们始终来自 `rpc-bench-e2e/target`。

### 两个对比模块

| | `rpc-bench-compare-grpc` | `rpc-bench-compare-dubbo` |
|---|---|---|
| 版本 | grpc-java 1.85.0（grpc-bom）、protobuf-java 3.25.9 | Dubbo 3.3.6（all-in-one `org.apache.dubbo:dubbo`） |
| 契约 | `bench_echo.proto`：`Echo(Bytes) returns (Bytes)`、`Environment(Empty) returns (Env)`、`Stats(Empty) returns (ServerStats)`（`Stats` 消息与同名 rpc 冲突，改名 `ServerStats`；`Empty` 自定义，不引 well-known types） | Java 接口模式（不写 IDL），**直接导出与 rpc bench server 同一个 `BenchService` 接口与 `BenchServiceImpl` 实现** |
| 代码生成 | `io.github.ascopes:protobuf-maven-plugin` 5.1.11：按平台从 Maven Central 取 protoc 与 protoc-gen-grpc-java，Windows 与 Linux 同一份配置；`org.xolstice` 0.6.1 自 2018 年不再维护，还要 os-maven-plugin 扩展 | — |
| 服务端 | `GrpcBenchServer --port --io-threads --executor grpc-default\|fixed\|direct`（缺省 grpc-default） | `DubboBenchServer --port --io-threads`（Dubbo 需要具体端口） |
| Netty | grpc-netty-shaded（重定位，与 harness 的 netty-all 互不影响） | Dubbo 3.3.6 声明依赖 netty-all 4.2.2.Final（未 shade）；根 pom 统一管理成 4.1.136.Final（`dependency:tree -Dverbose` 实证 4.2.2 被 managed 掉；3.3.6 不引用 4.2 才有的 IoHandler 系 API，测试与压测在 4.1.136 上跑通），同一 JVM 只有一份 Netty |

### 对等性：线程、执行器、连接、序列化

| | 本框架（rpc 卡） | gRPC | Dubbo |
|---|---|---|---|
| 客户端调用方式 | 同步代理方法，调用方线程（虚拟线程）上等 future | 阻塞 stub，响应回调在调用方线程上执行（ThreadlessExecutor） | 同步代理方法，ThreadlessExecutor（调用方线程处理响应） |
| 超时 / 重试 | `load.timeout`，retries 0 | 每次 `withDeadlineAfter(load.timeout)`；`disableRetry()`（gRPC 缺省开着透明重试） | `timeout = load.timeout`（ms 向上取整），`retries = 0` |
| 客户端 IO 线程 | `RpcClientConfig.ioThreads`，缺省 Netty 缺省 2 × 可用核数，NIO | 显式 NIO 事件循环组，`--io-threads`，缺省 2 × 可用核数（grpc-netty-shaded 在 Linux 缺省会用 epoll，这里不让传输实现成为变量） | Dubbo 进程级共享事件循环，线程数固定为 min(核数 + 1, 32)，没有配置项（`--io-threads` 对 dubbo 卡是用法错误）；NIO（epoll 缺省关） |
| 服务端 IO 线程 | boss 1 + worker 2 × 可用核数（`--io-threads`） | boss 1 + worker 2 × 可用核数（同上），NIO | `iothreads` 显式对齐 2 × 可用核数（Dubbo 自己的缺省是 min(核数 + 1, 32)） |
| 服务端执行器 | 固定池 2 × 可用核数、队列 1000，业务方法**离开** IO 线程 | 缺省 `grpc-default`（不调 `executor(...)`：gRPC 自带的共享缓存线程池，回调**离开** IO 线程）——与本框架缺省同一种模型，对比卡（含 c2）都用它。`--executor direct`（`directExecutor()`，回调直接在 IO 线程上执行，少一次线程交接）只是 **gRPC 的上界对照点，不是缺省用法**；`--executor fixed` 是与本框架同样大小的固定池。实际执行器写进服务端环境与 `run.target.server_executor` | Dubbo 缺省 `threadpool=fixed, threads=200`，业务方法离开 IO 线程（与本框架同一种模型，池更大） |
| 序列化 | Protostuff（`serializer: protobuf`） | protobuf 生成消息（`bytes payload = 1`） | Dubbo 3.3 Java 接口模式 triple 的缺省：**hessian2**（实测：开压前从调用 URL 读回，卡片必须写 `serializer: hessian2`，不一致就不压），外面再包一层 triple 的 protobuf 信封 |
| 指标 | 指标 Filter 缺省关（`--metrics-port` 才开） | 无 | **显式关掉**（`MetricsConfig` 全部 false）：Dubbo 看到 classpath 上有 Micrometer 就自动开启指标采集，包括 RPC 热路径上的 MetricsFilter |
| 连接（缺省） | 每个提供者 2 条 TCP 连接，按 requestId 多路复用 | 1 个 ManagedChannel = 1 条 HTTP/2 连接，每次调用一个流 | 1 条 triple HTTP/2 连接 |

对 1KB `byte[]` 的序列化差异：三方编码的主体都是"长度前缀 + 1024 字节原样拷贝"，不涉及对象图；差别在包装：本框架是自己的信封 + Protostuff，
gRPC 是一个 protobuf 字段，Dubbo 在 hessian2 编码的参数外面再包一层 triple 的 protobuf 信封（`TripleRequestWrapper`，带序列化名与参数类型名）。
这份开销没有单独测，对比表里的差距不应归因于序列化本身，除非另做剥离实验。

### 开箱即用与连接数对齐：两种配置都报

**单连接的差异是结构性的。** 一条 TCP / HTTP/2 连接固定在一个事件循环线程上：gRPC 与 Dubbo 缺省到一个地址只建一条连接，于是不论
服务端配了多少 IO 线程，干活的只有**一个** worker 事件循环（客户端同样只有一个）——闭环加压时这个线程先满，另一侧的核还空着。
任务 11 的冒烟（WSL2、20s 卡）用 jstack 看到：gRPC 单 channel 时两端各一个事件循环线程接近满载、服务端核总体只用到约一个核；Dubbo 单连接时服务端
唯一的 `NettyServerWorker` 线程满载。本框架缺省每节点 2 条连接，服务端就有 2 个 worker 在干活——所以"开箱即用"的数字比较的不只是编解码与线程模型，
还有各框架缺省的连接数。只报一组都不完整：

- **开箱即用**（`compare-*-<rpc|grpc|dubbo>-v1`）：各框架缺省连接数，回答"不调参直接用，各自是什么量级"；
- **连接数对齐**（`compare-*-<grpc|dubbo>-c2-v1`，`target.connections: 2`）：与本框架缺省的每节点 2 条对齐，回答"同样两条连接时的差距"。
  gRPC 是 2 个 ManagedChannel、每次调用轮询取一个；Dubbo 是 2 个独立的客户端实例（各自的 FrameworkModel 与引用、各一条连接）、轮询取用——
  **Dubbo reference 的 `connections` 参数对 triple 不起作用**（3.3.6 实测：`connections=2` 时 `ss -tn` 在服务端端口上仍只看到 1 条 ESTABLISHED 连接，
  jstack 里也只有 1 个 `NettyServerWorker` 在干活），所以 c2 用两个独立的 `FrameworkModel`（各自一套 bootstrap 与引用、各一条连接）实现，调用轮询取用。

`compare` 子命令据此出两类 transport 表："开箱即用：各框架缺省连接数"与"连接数对齐；connections = 2"，另给每个对比对象一张连接数表（1 vs 2）。

### 跑法

```bash
bash ~/rpc-bench-sync.sh && cd ~/rpc-bench
mvn -q -pl rpc-bench-e2e,rpc-bench-compare-grpc,rpc-bench-compare-dubbo -am package -DskipTests
bash bench/bin/compare.sh                         # rpc、grpc、grpc-c2、dubbo、dubbo-c2 × closed64、open10k → target/compare/run-<时间>/compare.md
bash bench/bin/compare.sh --out bench/reports/<yyyyMMdd>-<sha7>/compare    # 归档
COMPARE_SERVER_ARGS_GRPC="--executor direct" bash bench/bin/compare.sh --transports rpc,grpc,grpc-c2 --out <dir>   # gRPC 上界对照点（directExecutor，非缺省用法，结果另放一个目录）
bash bench/bin/compare.sh --transports rpc-direct --out <dir>   # 本框架服务端 direct（compare-*-rpc-direct-v1，非缺省配置）：与缺省结果同目录，由 server_executor 轴区分，声明段标注非缺省配置
```

`compare.sh` 依次对每个 transport（`-c<N>` / `-direct` 后缀是卡片变体，服务端按第一个 `-` 之前的 transport 起；rpc 的服务端执行器取自卡片的
`target.server_executor`，不是 `fixed` 时传 `--executor`）：起服务端（`BENCH_SERVER_CPUS`，缺省 0-4，端口 19300 + i）→
跑它的卡（`BENCH_CLIENT_CPUS`，缺省 5-9，`--direct`）→ 停服务端；全部结果写进同一目录，最后 `compare` 生成 `compare.md`。卡片判定未通过（退出码 2）
照样进对比表（判定列写明），运行出错（退出码 1）中止。`--profiles <dir> --suffix <s>` 换成别的卡（例如不入库的 20s 冒烟卡）。

### 对比表

`compare --in <dir>`（`report` 汇总时也附上）把结果分组：负载模型、服务、payload、时长 / 预热 / 窗口 / 轮数 / 超时、是否同机全部相同的为一组；
组内取值不止一种的 `transport`（连同序列化）、`connections`（实际连接数，卡片没写 `target.connections` 的标"缺省"）、`concurrency`、`rate`、
`io_threads`（只有命令行显式给了 `--io-threads` 才算，各 transport 的缺省写法不同、不算对比轴）、`server_executor`（服务端环境的 `server_executor_kind`）、
`flush_consolidation`（本框架两端的 flush 合并）自动成为对比轴，每个轴一张表、表内其余轴取值相同。
transport 表例外：`server_executor` 与 `flush_consolidation` 是各 transport 的固有属性（本框架 fixed / 合并开、gRPC grpc-default、Dubbo 两项都没有），
不参与 transport 表的分区，只在"服务端配置"列与表下的说明里出现；本框架的非缺省配置（执行器不是 fixed，例如 rpc-direct，或任一端 flush 合并关）
不进 transport 表，只进 `server_executor` / `flush_consolidation` 轴的表——所以 rpc-direct 的结果与缺省结果放同一目录，fixed 对 direct 的表才会出来。
每行取该结果的正式轮：吞吐（及相对首行的倍数）、p50 / p99 / p99.9 / max、开环的服务时间 p99、两组核的 CPU、client_bound、两端 GC 停顿、
两端每请求的 write 系统调用与上下文切换、暂停读次数（rpc 服务端窗口内的 `backpressure.window.auto_read_pauses`，有硬上限拒绝时附上；对比对象为 —）、
窗口错误、判定、连接方式、客户端 IO 线程、服务端配置（IO 线程与执行器）、结果文件。

## 并发扫描与连接数扫描

`bench/bin/sweep.sh`：同一个 rpc 服务端上依次跑一组只差一个字段的卡，结果进同一目录，最后生成按该字段并排的对比表。

```bash
bash bench/bin/sweep.sh                       # 并发扫描：closed 64 / 128 / 256（compare-closed64-rpc-v1 + sweep-closed<n>-rpc-v1）
bash bench/bin/sweep.sh --connections 2,4,8   # 连接数扫描：closed 128，每节点 2 / 4 / 8 条（sweep-closed128-rpc-v1 + sweep-c<n>-closed128-rpc-v1）
bash bench/bin/sweep.sh --cards sweep-c1-closed64-rpc-v1,compare-closed64-rpc-v1                 # 单连接上限：同一个服务端上 1 vs 2 条连接
bash bench/bin/sweep.sh --cards sweep-c1-closed64-rpc-direct-v1,compare-closed64-rpc-direct-v1   # 同上，服务端 direct（取自卡片的 target.server_executor）
SWEEP_SERVER_ARGS="--flush-consolidation off" BENCH_HARNESS_OPTS="--flush-consolidation off" bash bench/bin/sweep.sh --cards ...   # flush 合并 A/B 的 off 一侧
SWEEP_SERVER_ARGS="--outbound-backpressure off" bash bench/bin/sweep.sh --cards ...   # 出站背压 A/B 的 off 一侧（服务端开关；客户端不变）
```

`--cards <id,id,...>` 只跑列出的卡（`<profiles>/<id>.yaml`，不加后缀），各卡的 `target.server_executor` 必须一致。

目的：找吞吐不再随并发增长的点，以及此时两侧的 CPU——服务端核饱和是服务端上限，`client_bound`（压测端核 > 90%）是发压上限，两侧都没满说明瓶颈在别处。
任务 11 冒烟的观察：128 并发之后吞吐不再随并发增长、两侧核都只用到六成左右，jstack 看到服务端只有 2 个 worker 事件循环在干活且都接近满载——
**限制吞吐的是连接数（= 服务端实际在干活的 IO 线程数），不是并发**；把每节点连接数加到 8 之后 8 个 worker 都只用到三分之一左右，瓶颈转到压测端（`client_bound`）。
所以并发扫描与连接数扫描要一起看，`RpcServerConfig.ioThreads` 配多少都只有"连接数"个线程会被用上。

## 故障注入

阶段 2 的验收项：慢节点、杀节点、突增用**真实制造**的故障（toxiproxy toxic、`kill -9`、连接静默挂起、`SIGSTOP`）测，每组有对照。
分工：**harness 不碰进程与 toxiproxy**，只写"什么时候该发生什么"（故障时间表）、读回"实际什么时候发生了什么"（执行记录），并按秒记录调用方看到的一切；
驱动脚本 `bench/bin/fault-run.sh` 起拓扑、按时间表执行故障、拆拓扑。

### 拓扑：fault-topology.sh

```bash
bench/bin/fault-topology.sh up --providers 3 --out <dir> --behind-toxiproxy all     # 或 2 / 1,3 / none
bench/bin/fault-topology.sh down --out <dir>
bench/bin/fault-topology.sh wait-zk-clean                                          # 等 ZooKeeper 里的旧临时节点随会话超时消失
```

- N 个 `bench server`（端口 19401 起，`--host 127.0.0.1 --registry zk://127.0.0.1:2181`，`BENCH_SERVER_CPUS` 缺省 0-4，N 个共用这组核；
  JVM 参数缺省另加 `-Djava.net.preferIPv4Stack=true`，见下），写 `<dir>/topology.txt`（每行：编号、公布地址、pid、监听端口、代理名、HTTP 控制端口）
  与 `<dir>/server-<编号>.log`，全部注册完成（经代理的还要确认代理已能连到它）才返回。
- 每个服务端另开 `--http-port <监听端口 + 100>`（HTTP 控制端点）：harness 读拓扑文件（`run --topology`），每秒的服务端计数经 `GET /counters`、
  窗口统计经 `GET /stats` 取——在 IO 线程上回答、**不经业务执行器**。经 RPC 取的 `counters()` / `stats()` 与压测请求排同一个执行器，
  执行器排满时会排在压测请求后面、甚至被拒（突增卡的冒烟里每秒样本因此大量丢失），正好是最需要数据的时候。
- `--behind-toxiproxy`：该提供者监听真实端口，在 toxiproxy 上建代理 `rpc-fault-p<编号>`（监听 20000 + 编号，上游 = 真实端口），
  并以 `server --advertise-port <代理端口>` 注册——注册中心里写的是代理端口，消费端经代理访问它。`--advertise-port` 由 harness 里的
  注册中心 SPI 扩展 `portmap`（`PortMappingRegistryFactory`，包装 zookeeper，注册 / 摘除时换端口）实现，core 不改；RpcServer 的注册、摘除与优雅停机顺序照常。
- toxiproxy 缺省用 `docker/compose.yaml` 的实例（管理 API `127.0.0.1:8474`，容器经 `host.docker.internal` 回连 WSL 里的提供者）。
  这条路径（WSL → Docker Desktop 端口转发 → 容器 → host.docker.internal → Windows → WSL）本身有开销：同步 1KB 回显的往返在本机实测
  p50 约 +2.2ms、p99 约 +4.4ms（直连 0.09 / 0.23ms）。所以慢节点卡让**三个提供者都经代理**（`--behind-toxiproxy all`），代理路径的开销对三个节点对称，
  只有第 2 个加 toxic。`TOXIPROXY_API` / `TOXIPROXY_UPSTREAM_HOST` / `TOXIPROXY_LISTEN_HOST` 可指向同一版本 toxiproxy 的其它实例
  （例如把镜像里的二进制原生跑在 WSL 里、上游写 127.0.0.1：同样的回显往返只多约 0.26ms）。
- **IPv4 监听**：容器经 `host.docker.internal` 回连 WSL 时只连得上 IPv4 通配监听（`0.0.0.0`），JVM 缺省的 IPv6 双栈通配监听（`::`）被拒绝
  （`connect: connection refused`，代理接受客户端连接后立刻关掉）——2026-10-21 冒烟实测，慢节点卡因此全部失败。所以拓扑里的提供者都带
  `-Djava.net.preferIPv4Stack=true` 起（记进 `server_environment.jvm_args`），并且新监听要过几秒才被 WSL 的端口转发发现，`up` 会等代理真正连得上。

### 运行：fault-run.sh

```bash
bash bench/bin/fault-run.sh --card fault-kill-node-failover-v1                         # kill -9
bash bench/bin/fault-run.sh --card fault-kill-node-failover-v1 --variant sigterm       # 优雅停机对照
bash bench/bin/fault-run.sh --card fault-kill-node-failover-v1 --variant toxic-timeout # 静默死亡（第 2 个提供者自动经代理）
bash bench/bin/fault-run.sh --card fault-slow-node-p2c-v1 --behind-toxiproxy all [--no-faults]
bash bench/bin/fault-run.sh --card fault-burst3x-bounded-v1                            # 只有阶梯、没有故障的卡：脚本只管拓扑
```

1. 从卡片读提供者数与服务端配置（`server_executor` / `server_queue_capacity` / `server_work` → `--executor` / `--executor-queue` / `--work`），
   等 ZooKeeper 干净后起拓扑（缺省让 toxic 故障的目标经代理）。
2. 以 `--registry zk://127.0.0.1:2181 --fault-schedule <dir>/schedule-*.txt --topology <dir>/topology.txt` 起 harness（`BENCH_CLIENT_CPUS` 缺省 5-9），
   缺省另加 `FAULT_HARNESS_OPTS="--open-workers per-request"`：故障期间挂住到超时的调用不会占满固定的 64 个工作者（那会让发压在故障期间退化成闭环）。
3. harness 在**本轮开始时**（预热段之后）写出时间表：
   ```text
   # rpc-bench-e2e fault schedule v1
   mode inject
   round_start_epoch_ms 1792566650657
   fault 0 20000 kill-9 2
   ```
   脚本按 `round_start_epoch_ms + at_ms` 执行每一项（kill / toxiproxy HTTP API），每项追加一行到 `<schedule>.executed`：`<序号> <墙钟毫秒> ok|failed <说明>`。
4. harness 本轮结束后读回执行记录，换算成相对本轮开始的 `executed_at_ms`；**注入模式下任何一项不是 ok、或实际时刻与计划相差超过 500ms，判定不通过**
   （`validity.faults_executed = false`，退出码 2）：卡片声明的实验没有按计划发生。卡片有故障时必须给 `--fault-schedule`（注入）或 `--no-faults`（对照）之一。
5. 拆拓扑：SIGCONT（被冻结的先恢复）→ SIGTERM → 10s 不退再 KILL，删代理。

**原生 toxiproxy**（正式实验用；脚本缺省仍指向 compose）：同一版本的二进制从 compose 镜像里取出来、原生跑在 WSL 里，上游写 127.0.0.1：

```bash
id=$(docker create ghcr.io/shopify/toxiproxy:2.13.0); mkdir -p ~/tools/toxiproxy-2.13.0
docker cp $id:/toxiproxy ~/tools/toxiproxy-2.13.0/; docker rm $id
setsid nohup ~/tools/toxiproxy-2.13.0/toxiproxy -host 127.0.0.1 -port 8475 > toxiproxy.log 2>&1 < /dev/null &
export TOXIPROXY_API=http://127.0.0.1:8475 TOXIPROXY_UPSTREAM_HOST=127.0.0.1 TOXIPROXY_LISTEN_HOST=127.0.0.1 TOXIPROXY_PORT_BASE=21000
```

（代理端口段改到 21000 起：compose 的 toxiproxy 占着 20000-20010 的端口转发。）原生进程不绑核，与容器一样；卡片的 `isolation.registry` 写明"toxiproxy 原生进程（同 WSL）"。

**口径约定**（阶段一审定）：故障的 `at` 相对**本轮开始**（预热段之后的 t = 0），不是统计窗口起点；节点份额按压测端**尝试数**计（含重试，按发出计）；
恢复时间的分辨率是 1 秒（时间线的粒度）；故障卡不写断言，判定只看轮次有效、故障按计划执行、服务端配置与卡片相符。

`--fault-variant <kind>`：把卡片里的 `kill-9` 全部换成 `sigterm` / `sigstop` / `toxic-timeout`（同一时刻、同一目标），记进 `run.faults.variant` 与声明段；
同一组实验的变体共用一张卡。`--no-faults`：同一张卡的对照运行，时间表写 `mode control`（没有 fault 行），时间线照记，恢复指标按计划时刻算。

### 时间线与恢复时间

卡片有 `load.steps` 或 `load.faults` 时，每轮另有一个只睡眠的采样线程，在本轮开始后第 k 秒（k = 0, 1, …）取一次压测端客户端的累计计数
（`RpcClient.nodeCallStats()` 按节点的尝试 / 失败 / 超时、`retryAttempts()` / `retryNodeSwitches()`）与**每个提供者**的 `BenchService.counters()`
（经只连该提供者的元数据客户端，400ms 超时，取不到记"不可达"）：执行器过载 / 停机拒绝、此刻排队数、排队等待的累计直方图。

- `rounds[].timeline.rows[]`：每秒一行——`success` / `errors` / `errors_by_type` / `p50_ms` / `p99_ms`（按**完成时刻**归秒，延迟从计划时刻算，2 位有效数字）；
  `node_attempts` / `node_share`（该秒压测端向各节点发出的**尝试数**及占比，含重试）、`node_failures` / `node_timeouts`、`retries` / `retry_node_switches`；
  `server_executor_rejections` / `server_shutdown_rejections` / `server_rejections_by_provider`、`server_queued`（该秒末瞬时值之和）、`server_queue_wait_p99_ms`、
  `providers_unreachable`。服务端与客户端列是相邻两个样本之差。
- `rounds[].steps[]`：每一级的请求 / 成功 / 失败 / 成功率、吞吐、延迟分位数（卡片精度）、失败分类，`server`（该级起止两个样本之差：拒绝数、排队等待分位数、重试数），
  第 2 级起另有 `recovery`。按完成时刻归级；最后一级也收本轮结束后才完成的在途尾巴。
- `rounds[].faults[]`：卡片（或变体）的每一项，加 `executed_at_ms` / `status` / `detail`（执行记录）、`target_address`（拓扑），与 `recovery`：
  - `errors_recovery_s`：故障时刻（有执行记录用实际时刻）到"**连续 3 秒错误为 0**"的第一秒起点；故障之后一个错误都没有为 0；到本轮结束都没满足为 null（`recovered: false`）。
    另给 `errors_until_recovery`、`first_error_second` / `last_error_second`；
  - `p99_recovery_s`：条件再加"该秒 p99 ≤ 2 × 基线"（基线 = 第一个事件之前、去掉开头 2 秒的各秒 p99 中位数）；
  - `share_recovery_s`（只对 `remove-toxic` / `sigcont`，且有拓扑时）：目标节点每秒份额 ≥ 0.8 × 故障前平均份额、连续 3 秒的第一秒起点。
  - 分辨率都是 1 秒（时间线的粒度）。

Markdown 在轮次表之后多一行"服务端业务执行器（正式轮统计窗口内）"，有阶梯 / 故障时再加阶梯表、故障表与每秒时间线表。

**排队等待**（`rounds[].server_executor.queue_wait_ms`、时间线的 `server_queue_wait_p99_ms`）：bench server 自带的服务端 Filter `QueueWaitFilter`
（order -4500，在 DeadlineServerFilter 之外）在业务线程开始执行 Filter 链时记 `nanoTime − 到达时刻`（服务端 Invocation 的起点就是 IO 线程解码该帧的时刻，
DeadlineServerFilter 用同一起点判过期）；只记 echo / echoOrder。累计直方图（2 位有效数字、1µs ~ 60s）压缩后经统计端点交出，压测端对两个时刻做差，服务端不维护区间状态。
core 没有为此改动。

## 已知局限

- **多提供者拓扑下开环调度线程的发出滞后更大**：同一台 WSL2 上 3 个 bench server（共用 0-4 号核）+ ZooKeeper 时，压测端核的 CPU 约是单提供者直连的 1.5 倍以上，
  发出滞后 p99 在冒烟里是 2 ~ 8ms（单提供者直连同负载 0.07ms）。故障注入 / 阶梯卡上这条规则因此只记标志、不判无效（见[判定与正式轮](#判定与正式轮)），
  这些卡的延迟数字（含 p99）仅供参考。
- **compose 里的 toxiproxy 不适合 5000 req/s 的慢节点实验**：路径是 WSL → Docker Desktop 端口转发 → 容器 → host.docker.internal → Windows → WSL。
  同步 1KB 回显的单线程往返只多 +2.2ms（p50）/ +4.4ms（p99），但三个提供者都经它、5000 req/s 时，**不加任何 toxic 的对照**就已是 p50 11.5ms / p99 56ms，
  两侧核都到 85% 左右，20ms 的 latency toxic 被淹没。同版本二进制原生跑在 WSL 里（上游 127.0.0.1）时同一对照 p50 0.91ms。正式实验用原生进程（见[故障注入](#故障注入)）。
- **容器经 host.docker.internal 回连 WSL 只连得上 IPv4 通配监听**：JVM 缺省的 IPv6 双栈通配监听（`::`）被拒绝（`connect: connection refused`，代理接受客户端连接后
  立刻关掉）；`fault-topology.sh` 因此给提供者加 `-Djava.net.preferIPv4Stack=true`。

- **开环调度线程忙等、独占一个核**：自旋窗口按 park 超睡量自适应放大，超过请求间隔后调度线程就一直忙等——Windows（定时器粒度毫秒级）上总是如此，Linux 上在高 rate 时也如此（WSL2 上校准出的超睡约 150µs，5000 req/s 时就已一直忙等）。精度不受影响，代价是占掉压测端的一个核；开环卡的报告声明段会写明这一点，绑核时给压测端留足核。Windows 只用来跑流水线冒烟，正式数字在 WSL2 / Linux 上产生。
- **忙等的线程更容易撞上宿主对 vCPU 的抢占**（见[噪声地板](#噪声地板)）：开环调度线程总在忙等；http 卡改用 netty 客户端并忙轮询时
  还多一个忙等的 IO 线程（这也是缺省改为阻塞客户端的原因）。这是 harness 的 p99.9 / max 远大于 wrk2 的主要原因，见 docs/probes/harness-tail-latency.md。
- **`--pin-dispatcher` 只在 Linux 生效**，并且只管进程里已有的线程：轮次中途由调度线程触发新建的平台线程（例如虚拟线程调度器补的载体）
  会继承调度线程的核。每轮开始前重钉一次、轮末核对（`stray_threads_at_end`），本机的运行里没有出现过这种线程。
- **日志只输出 WARN 及以上**：harness 绑定 slf4j-simple（`simplelogger.properties`：WARN、stderr、带时间戳），框架告警可见，又不让 INFO 日志的 IO 混进测量。
- **服务端环境依赖一次调用**：`server_environment` 由服务端启动时采集、压测端预热前调用一次取回（RPC 卡经 `environment()`，HTTP 卡经
  `GET /environment`）；目标不是本 harness 的 `bench-server`（例如任意第三方 HTTP 端点）时记为 `unknown`。
- **闭环吞吐可能受限于压测端**：每次调用在发压虚拟线程与 Netty IO 线程之间各交接一次（RPC 与 HTTP 目标相同），每次调用的固定 CPU 开销
  比 wrk 这类单线程事件循环工具高；压测端核饱和时，闭环卡的吞吐是 harness 的发压上限而不是服务端上限。报告的 CPU 两列与
  `validity.client_bound` 会把这种情况标出来；本机的容量对照见[交叉验证](#交叉验证)的闭环报告。
