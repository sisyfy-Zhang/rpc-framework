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
- [WSL2 上的标准流程](#wsl2-上的标准流程)：含 CPU 绑核
- [负载模型与测量](#负载模型与测量)：闭环 · 开环 · 为什么开环按计划时刻计时
- [判定与正式轮](#判定与正式轮)
- [报告字段](#报告字段)
- [交叉验证](#交叉验证)：harness 与 wrk / wrk2 打同一个 HTTP 目标——开环测量校验、闭环发压容量标定
- [已知局限](#已知局限)

## 构建

```bash
mvn -pl rpc-bench-e2e -am package -DskipTests
```

`-DskipTests` 同时跳过 JaCoCo（根 pom 里 `jacoco.skip` 缺省取 `skipTests`），`install` / `verify` 也不需要再加 `-Djacoco.skip=true`。

产物是 `rpc-bench-e2e/target/classes` 与 `rpc-bench-e2e/target/lib/*`（`maven-dependency-plugin` 在 package 阶段复制运行时依赖，不打 fat jar），
`bench/bin/*.sh` 用这两者拼 classpath。脚本开头 `export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"`，从仓库根目录运行。

## 口径卡

一张卡回答一个问题，放在 `bench/profiles/<profile>.yaml`。仓库自带四张：

| 卡片 | 问题 | 负载 |
|---|---|---|
| `loopback-throughput-v1` | 同机 loopback、1KB、单提供者的吞吐量级 | 闭环 64 并发 |
| `loopback-latency-v1` | 同一拓扑在给定负载下的延迟分布（含尾延迟） | 开环 10000 req/s |
| `crossval-http-open-v1` | harness 校验卡：开环发压与测量是否与 wrk2 一致（见[交叉验证](#交叉验证)） | HTTP，开环 5000 req/s，64 连接 |
| `crossval-http-closed-capacity-v1` | harness 校验卡：闭环发压容量标定（harness 与 wrk 各自的上限与瓶颈侧） | HTTP，闭环 64 并发 |

两张 loopback 卡都是每轮 60s、预热 20s、统计窗口 [20s, 55s]、3 轮；两张 crossval 卡每轮 60s、预热 20s、统计窗口 [20s, 60s]
（40s，与工具的测量时长一致）、3 轮。**改口径就复制成新 id（`-v2`），不要原地改**：报告以卡片 id 为准，同一个 id 必须始终代表同一个口径。

### 字段说明

```yaml
profile: loopback-throughput-v1      # 必填，须与文件名（去扩展名）一致
question: 同机 loopback、1KB、单提供者的吞吐与延迟量级
target:
  transport: rpc                     # rpc | http（http 卡见下文“transport: http”）
  service: BenchService#echo         # BenchService#echo | BenchService#echoOrder
  payload: { kind: bytes, size: 1024 }   # bytes（size 字节，固定种子随机内容）| order（固定 fixture，不写 size）
  serializer: protobuf               # classpath 上的 Serializer SPI 名
  providers: 1                       # 提供者数：开压前客户端看到的提供者数必须恰好等于它
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
| `target.http.url` / `target.http.connections` | 只用于 `transport: http`，见下节 |
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

客户端实现要点（`HttpCallTarget`）：Netty `HttpClientCodec` + `HttpObjectAggregator`（8MiB），每次调用在发压的虚拟线程上写出、等响应
（与 RPC 的同步代理同一种调用方式）；IO 线程数 = 可用核数，Linux 用 epoll 原生传输（netty-all 自带），其它平台 NIO；超时由每个目标
一个的扫描任务（每 10ms）判定，不用限时等待（虚拟线程上的限时 park 每次都要登记 / 撤销一个定时任务，闭环高并发下开销可观）；
超时的连接直接关闭、下次使用时重连。建连后、第一轮之前每条连接串行做 200 次回显预热 JIT（不计入任何一轮，次数写进
`run.target.pre_warmup_calls_per_connection`）：冷 JVM 第一秒的慢调用会把 64 条连接占满、后面的请求排队，测的是 JIT 而不是目标。服务端环境经 `GET /environment` 取回（与 RPC 端点的 `environment()` 同一份采集）。

### 校验规则

YAML 只用 SnakeYAML 的 `SafeConstructor` 加载成 Map（`!!java...` 这类标签直接拒绝），再逐字段映射。校验一次报告**全部**问题，每条带字段路径：

- 未知字段一律拒绝（拼错的 `warm_up` 被静默忽略比报错危险得多）；重复键拒绝。
- 必填：`profile`、`question`、`target.*`、`load.model / duration / warmup / steady_window / rounds / timeout`、`isolation.*`、`measure.histogram.*`、`measure.report`；closed 必填 `concurrency`，open 必填 `rate`。
- `profile` 须匹配 `[a-z0-9][a-z0-9._-]*` 且等于文件名去扩展名。
- 枚举：`transport`、`service`、`payload.kind`、`model`、`client_process` / `server_process`、`serializer`（classpath 上实际可用的 SPI 名）、`report` 的每一项（不得重复）。
- 范围：`size` 1 ~ 4MiB，`concurrency` 1 ~ 100000，`rate` 1 ~ 10000000，`rounds` 1 ~ 100，`providers` ≥ 1，`significant_digits` 1 ~ 5，时长 > 0（`warmup` 可为 0）。
- 交叉约束：`warmup < duration`；`steady_window` 恰好两个时长，`start < end`，`start ≥ warmup`，`end ≤ duration`；`histogram.max > timeout`；开环 `rate × timeout × 2` 不超过 500 万；`payload.kind` 与 `service` 匹配，`order` 不写 `size`。
- transport：`http` 必须有 `target.http.url`（`http://`、有 host、有 path），`payload.kind` 只能是 `bytes`，不得写 `serializer` / `providers`；
  `rpc` 不得写 `target.http`。

## 子命令

```text
server   --port <p> [--host <公布地址>] (--registry zk://host:port | --no-registry) [--serializer protobuf] [--metrics-port <p>] [--http-port <p>]
run      --profile <card.yaml> (--registry zk://host:port | --direct host:port[,host:port]) [--out <dir>] [--label <text>] [--metrics-port <p>]
         （transport: http 的卡不带 --registry / --direct）
report   --in <目录 | 结果.json> [--out <file.md>]
crossval --harness <结果.json> --tool wrk|wrk2 --tool-output <file> [--tool-command <text>] [--tool-version <text>]
         [--cpu-tool <crossval.sh 的 CPU 采样行>] --out <dir>
```

- **server**：导出 `BenchService`（原样回显；另有 `environment()` 返回服务端启动时采集的环境），其余全部是 `RpcServer` 缺省配置——压的是开箱即用的框架。配了注册中心时等到注册完成才打印
  `bench-server ready port=... pid=...`；收到 SIGTERM / Ctrl-C 后按框架的优雅停机顺序关闭。`--host` 是注册时公布的地址（同机压测用 `127.0.0.1`）。
- **run**：读卡 → 校验 → 连目标 → 取服务端环境（`environment()`，单独 30s 超时，失败记 `unknown` 不中止）→ 开压前做一次完整回显校验 → 跑 `rounds` 轮 → 写 `<out>/<profile>-<yyyyMMdd-HHmmss>.json`，再从**读回的** JSON
  生成同名 `.md`。`--out` 缺省为归档目录 `bench/reports/<yyyyMMdd>-<sha7>/`（相对仓库根）。`--label` 原样写进报告（冒烟写 `smoke`）。
- **report**：读目录下（递归）全部 `schema_version: 1` 的结果 JSON，按卡片分节汇总成一份 Markdown；目录里的交叉验证结果（`crossval-*.json`，
  `schema: crossval/*`）汇总到末尾的"交叉验证判定"一节（每份结果的 PASS/FAIL 与逐项判定）。不传 `--out` 时打印到标准输出。
  读不了或版本不对的文件跳过并在标准错误里列出。
- **`server --http-port <p>`**（缺省关闭）：在同一进程、独立端口起 HTTP/1.1 对照端点（Netty `HttpServerCodec` + `HttpObjectAggregator`
  8MiB，keep-alive，业务在 IO 线程上完成）：`POST /echo` 原样返回请求体（`application/octet-stream`），`GET /ping` 返回 `ok`，
  `GET /environment` 返回服务端环境 JSON；其它路径 404、方法不对 405。ready 行末尾多一个 `http_port=<p>`。
- **crossval**：读一次 `transport: http` 运行的结果 JSON 与 wrk / wrk2 的原始输出，并排比较、按[交叉验证](#交叉验证)的口径判定，
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

### CPU 绑核

同机压测时服务端与压测端抢同一批核，谁被调度器挤占都会混进数字；开环卡的调度线程还会忙等独占一个核（见[已知局限](#已知局限)）。
三个脚本支持 `BENCH_SERVER_CPUS` / `BENCH_CLIENT_CPUS`（`taskset -c` 的核列表，如 `0-4`）：设置了且 `taskset` 可用时以 `taskset -c <list>` 前缀启动，
否则原样启动并在 stderr 提示。实际生效的亲和由进程自己读 `/proc/self/status` 的 `Cpus_allowed_list`，写进 `environment.cpus_allowed`（压测端）
与 `server_environment.cpus_allowed`（服务端），Markdown 环境表各占一行——报告记录的是"实际绑上了什么"，不是"脚本想绑什么"。

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

单个调度线程按 `rate` 生成等间隔的计划发出时刻（第 i 个 = 开始 + i × 1/rate），远时 `LockSupport.parkNanos`、最后一段自旋对齐（自旋窗口在本轮计时开始前按实测的 park 超睡量校准，Windows 上约为一个系统定时器节拍），到点就把调用交给一个新的虚拟线程
（同步代理方法，与闭环走同一个远程方法、同一条客户端代码路径）。记录三样东西：

- **延迟** = 完成时刻 − **计划**发出时刻；
- **服务时间** = 完成时刻 − 实际开始执行时刻；
- **发出滞后** = 调度线程实际到点 − 计划时刻（调度器自身是否可信）。

在途数达到 `rate × timeout × 2` 时不再发出，计为 `backlog_dropped` 错误，本轮无效：此时压测端已经跟不上计划，再发只会测压测端自己。

### 为什么开环按计划时刻计时

设想服务端停顿 1 秒。开环计划在这 1 秒里本该发出 rate 个请求，它们的真实用户体验是"等到停顿结束才被处理"，延迟从各自的计划时刻算起，
从接近 1 秒线性递减到接近 0——停顿在分布里留下了与其长度和频率相称的尾巴。如果改从"实际发出"计时（或者用闭环），这些请求要么被推迟发出、
要么根本不存在，停顿只体现为寥寥几个慢样本，p99 看起来几乎不受影响。按计划时刻计时就是把"本该发生但被推迟的等待"算回去，
这是 HdrHistogram / wrk2 修正 coordinated omission 的同一思路。服务时间直方图另外保留"真正处理用了多久"，两者的差就是排队。

### 统计口径

- 延迟分位数只统计**完成时刻落在 `steady_window` 内**的**成功**调用；失败按类型分桶（`timeout`、`status:<状态码>`、`payload_mismatch`、其它异常类名），窗口内与全程各一份，不进延迟分布与吞吐。
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

## 判定与正式轮

- **有效轮**：预热期内有成功调用（`warmup_completed`）、没有 `backlog_dropped`、窗口内至少一次成功。
- **正式轮**：闭环取窗口吞吐中位数那一轮，开环取 p99 中位数那一轮（偶数轮取下中位；有有效轮时只在有效轮里选）。正式轮在 Markdown 里加粗，断言里的延迟与吞吐按正式轮判定。
- **极差**：各轮吞吐与各分位数的 max − min 及其相对最小值的百分比，衡量本机的可重复性。
- **通过** = 全部轮次有效 且 全部断言满足；否则退出码 2。

## 报告字段

JSON 顶层（`schema_version: 1`）：

| 字段 | 内容 |
|---|---|
| `label` | `--label` 的值 |
| `profile` | `id`、`parsed`（解析后的结构，时长同时给文本与毫秒）、`raw_yaml`（卡片原文） |
| `run` | `command`（完整命令行）、`target`（直连 / 注册中心、实际提供者地址、序列化器、负载均衡、每节点连接数） |
| `environment` | 压测端：OS、`wsl`、CPU、`available_cpus`（JVM 可用核数 = `availableProcessors`，受 taskset 影响）、`host_cpus`（主机逻辑核数：Linux 数 `/proc/cpuinfo` 的 processor 行、读不到用 `nproc --all`，Windows 读 `NUMBER_OF_PROCESSORS`）、`cpus_allowed`、内存、JDK、`jvm_args`、GC、Netty、`git_sha` / `git_dirty` / `git_dirty_files`、主机名、`power_supply`、`cpu_governor`、开始 / 结束时间、`harness_version`、`pid`、`metrics_enabled`（`--metrics-port` 是否开启）。`available_cpus` 原名 `logical_cpus`，旧结果文件照常渲染 |
| `server_environment` | 服务端同一套字段（值压平成字符串，列表以空格连接），服务端启动时采集、预热前经 `BenchService.environment()`（HTTP 卡经 `GET /environment`）取回；取不到时为 `"unknown"`。HTTP 端点的这份环境里 `metrics_enabled` 恒为 `false`（HTTP 路径上没有指标 Filter），另有 `endpoint: http` |
| `run.target`（http 卡） | `transport` / `mode` = http、`address`（URL）、`connections`、`connection_model`、`io_transport` / `io_threads`、`timeout_check`、`pre_warmup_calls_per_connection`、`call_style` |
| `rounds[]` | 每轮：开始 / 结束、`issued`、`requests_total`、`window`（请求 / 成功 / 错误）、`throughput_rps`、`latency_ms`（count / mean / min / p50 / p90 / p95 / p99 / p999 / max）、开环另有 `service_time_ms` 与 `dispatch_lag_ms`、`errors_by_type` / `errors_total_by_type`、`backlog_dropped`、`histogram_clamped`、`scheduler`（开环调度器实况）、`warmup_completed`、`valid` / `invalid_reasons`、`timeseries`、`cpu`（统计窗口内两组核的平均忙碌率，见[统计口径](#统计口径)） |
| `summary` | `official_round`、`selection_rule`、`official`（正式轮的数字）、`spread`（各指标的 min / max / range / range_pct） |
| `validity` | `passed`、`warmup_completed`、`backlog_triggered`、`all_rounds_valid`、`client_bound`、`statements`（闭环声明、client_bound 声明等）、`topology`、`asserts[]`（阈值、实测、口径、是否通过） |

Markdown 由 JSON 读回后生成，分节：标题（卡片 id）与判定 → 问题 → 环境表（含"可用核数 / 主机核数"、压测端 / 服务端的 JVM 参数与 CPU 亲和、
"指标"一行列出两端的 `metrics_enabled`）→ 口径表 → 轮次表（正式轮加粗，末尾两列是客户端核 / 服务端核的 CPU 忙碌率）→ 断言表 → 声明（"数字来自固定本机，不可外推" + 拓扑一句 + 模型声明）。
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
（`BENCH_CLIENT_CPUS`，缺省 5-9，`--label` 透传，缺省 `crossval`；每轮统计窗口自带 CPU 采样）→ 停 harness 后用工具打同一 URL（`taskset -c 5-9`）：先预热 20s
跑一次丢弃，再测 40s（与卡片的统计窗口等长），测量运行中段采样一次 `/proc/stat`。wrk：`-t 4 -c 64 -d 40s --latency -s bench/bin/post-1k.lua`；
wrk2 另加 `-R 5000 -U`（`-U` 让同一次运行同时输出修正后与未修正分布）。`post-1k.lua` POST 固定的 1024 字节请求体。最后调用 `crossval`
子命令写 `crossval-<mode>-<时间>.json` / `.md`（`--out`，缺省 `target/crossval/`，被 .gitignore 忽略）。**正式的交叉验证结果进该 sha 的归档目录**
`bench/reports/<yyyyMMdd>-<sha7>/`（`--out` 指向它），作为该 sha 下 harness 可信度的证据：`bench-report.sh` 汇总时在"交叉验证判定"一节列出
每份结果的判定与逐项数字。调试脚本时的试跑留在缺省的 `target/crossval/`。

### 开环判定（测量校验）

harness 取正式轮（与 `run` 的选法相同），相对偏差 = |harness − wrk2| / wrk2（以第三方工具为参照）。三项分别判定：

| 项 | 检查 | 判据 |
|---|---|---|
| a. 达成吞吐 | harness、wrk2 各自相对目标 rate | 偏差都 ≤ 2% |
| b. 测量一致性 | harness 的**服务时间**（实际发出 → 完成）vs wrk2 的**未修正**延迟（实际发出 → 响应），p50 与 p99 | 相对偏差 ≤ 15% **或**绝对差 ≤ 100µs（取宽者：亚毫秒级时两个工具的时钟与记录点不同，百分比会被放大） |
| c. 节拍质量 | harness 的发出滞后（计划时刻 → 调度线程到点）p99 | ≤ 100µs |
| 前提 | harness 全部轮次有效、正式轮窗口内无错误；wrk2 无 socket 错误、无非 2xx/3xx 响应 | — |

为什么 b 比较的是服务时间与**未修正**延迟：两边的起点定义一致（都从实际发出算）。harness 的 CO 修正延迟 = 服务时间 + 发出滞后，发出滞后由
c 独立测量并约束，所以 b、c 都通过就说明 harness 的开环延迟可信。wrk2 的"修正后"延迟不能做亚毫秒级的参照：wrk2 推迟发送时把等待向上取整到
整毫秒（giltene/wrk2@44a94c1 `src/wrk.c` 第 604 行 `msec_to_wait = round((time_usec_to_wait / 1000.0L) + 0.5)`，第 608–609 行交给毫秒粒度的
ae 定时器），每个请求都比计划时刻晚发零点几到一毫秒多，这段滞后计入它的修正后延迟（本机实测修正后 p50 约为未修正的 6 倍，见
[pitfalls 第 31 条](pitfalls.md)）。报告仍把两边的修正后延迟并排列出，标注"信息项，不判定"。

亚毫秒级对比以 100µs 绝对差为主判据：在本机的口径下，b 项的相对偏差主要反映 harness 每次调用两次线程交接（发压虚拟线程 ↔ Netty IO 线程）
的固定开销（约 75µs），p50 往往只靠绝对差这一条通过——这是 harness 调用方式的已知成本，不是测量误差；p99 的相对偏差则在 15% 以内。

### 闭环判定（容量标定）

报告 harness 与 wrk 的闭环吞吐、两次运行中客户端核与服务端核的 CPU 忙碌率（harness 取正式轮统计窗口的 `rounds[].cpu`，wrk 取测量运行中段的
采样），并输出结论句"harness 闭环发压上限约 X req/s（本机、1KB、64 连接）；wrk 测得的服务端上限约 Y req/s"（措辞随瓶颈侧变化：某一侧平均
≥ 90% 即为该侧饱和）。**唯一的判定项**：两次运行的 CPU 采样都成功，且都能判别瓶颈侧——判别不了（非 Linux、未绑核、负载太轻）时结论句没有依据，
判 FAIL。吞吐与延迟各列只作信息项（wrk 闭环不做 CO 修正）。

阈值都是代码常量，没有命令行开关：放宽阈值就不再是同一个校验。

## 已知局限

- **开环调度线程忙等、独占一个核**：自旋窗口按 park 超睡量自适应放大，超过请求间隔后调度线程就一直忙等——Windows（定时器粒度毫秒级）上总是如此，Linux 上在高 rate 时也如此。精度不受影响，代价是占掉压测端的一个核；开环卡的报告声明段会写明这一点，绑核时给压测端留足核。Windows 只用来跑流水线冒烟，正式数字在 WSL2 / Linux 上产生。
- **日志只输出 WARN 及以上**：harness 绑定 slf4j-simple（`simplelogger.properties`：WARN、stderr、带时间戳），框架告警可见，又不让 INFO 日志的 IO 混进测量。
- **服务端环境依赖一次调用**：`server_environment` 由服务端启动时采集、压测端预热前调用一次取回（RPC 卡经 `environment()`，HTTP 卡经
  `GET /environment`）；目标不是本 harness 的 `bench-server`（例如任意第三方 HTTP 端点）时记为 `unknown`。
- **闭环吞吐可能受限于压测端**：每次调用在发压虚拟线程与 Netty IO 线程之间各交接一次（RPC 与 HTTP 目标相同），每次调用的固定 CPU 开销
  比 wrk 这类单线程事件循环工具高；压测端核饱和时，闭环卡的吞吐是 harness 的发压上限而不是服务端上限。报告的 CPU 两列与
  `validity.client_bound` 会把这种情况标出来；本机的容量对照见[交叉验证](#交叉验证)的闭环报告。
