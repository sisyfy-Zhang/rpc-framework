# Nacos 时序校准探针结果（D-010）

记录 `NacosRegistry` 在真实 Nacos 上的两组时序：首订阅是否漏事件，以及 Registry 契约里声明的两个时间窗（实例消失窗口 40s、
不可达时注册失败上限 15s）的实际值与余量。数据来自
`steadyrpc-registry-nacos/src/test/java/io/github/sisyfyzhang/steadyrpc/registry/nacos/NacosTimingProbeTest.java`
（类上标 `@Tag("probe")`、类名以 ProbeTest 结尾，默认构建不运行；`-Pprobe` 运行；Docker 不可用时整个类 skipped）。
与 Protostuff 探针不同，这里每项都**断言上界**：超出即失败。

## 目的

1. **首订阅漏事件**：`NacosRegistry.subscribe` 先 `getAllInstances(subscribe=false)` 直接查一次服务端并推给监听者，再
   `NamingService.subscribe` 挂监听。两步之间注册的实例，会不会两边都没赶上而永远看不到？
2. **消失窗口与注册失败上限**：契约测试（`NacosRegistryContractTest`）给 Nacos 声明了 `disappearanceWindow = 40s`、
   `registerFailureBound = 15s`。实际是多少、余量多大。

## 方法

| 实验 | 做法 | 断言 |
|---|---|---|
| 1. 首订阅漏事件 | 20 轮。每轮新建一个订阅方 Registry（构造即建好 gRPC 连接，不算进竞态），两个线程同时放行：一个调 `subscribe(新 ServiceKey)`，另一个随机延后 0~50ms 后由另一个（已连上的）Registry `register` 一个实例。记录每次推送的到达时刻 | 订阅方 10s 内看到该实例（看不到 = 漏事件） |
| 2a. 消失窗口（连接正常关闭） | 提供者 Registry `register` 后，订阅方看到它，然后提供者 `close()`。**`close()` 不注销**：`NacosRegistry.close` 只调 `NamingService.shutDown`，而 nacos-client 2.4.3 的 `NacosNamingService.shutDown → NamingClientProxyDelegate.shutdown → NamingGrpcClientProxy.shutdown` 只关闭 redo 服务并 `RpcClientFactory.destroyClient`（销毁 gRPC 客户端），不调 `deregisterInstance`（对 jar 的字节码逐个方法核对过）。所以这一项等价于进程退出时内核关闭套接字（服务端收到 FIN），实例只能靠服务端的断连处理摘除。5 轮 | 每轮 ≤ 40s |
| 2a'. 消失窗口（静默死亡） | 2a 测不到"宿主掉电 / 网络分区"——那时服务端收不到 FIN。提供者改经测试里的 TCP 代理连 Nacos（代理 HTTP 端口 P 与 gRPC 端口 P+1000 两个端口，Nacos 客户端按 HTTP 端口 + 1000 推算 gRPC 端口）；订阅方看到实例后把代理"黑洞化"：已有连接继续读、两个方向的数据都丢弃，不关套接字、不发 FIN / RST，新连接接受但不连上游。实例只能靠服务端的连接保活探测摘除。2 轮 | 每轮 ≤ 40s |
| 2b. 注册失败上限 | 一个已连上的 Registry 先注册一个实例确认连接可用，然后 `docker pause` Nacos 容器（docker-java 的 `pauseContainerCmd`，与命令行 `docker pause` 同一个 API），再对它调 `register`，测抛出的耗时；另测暂停期间**新建**一个 Registry 并注册。之后 `docker unpause`，用同一个 Registry 每 200ms 重试注册直到成功，再确认订阅方看到 | 已连上的 Registry 在暂停期间 register 抛 `RegistryException` 且 ≤ 15s；unpause 后 90s 内恢复、订阅方看得到 |

## 环境

- 运行：WSL2 Ubuntu（内核 6.18.33.2-microsoft-standard-WSL2，10 vCPU，AMD Ryzen 5 4600H），Temurin JDK 21.0.12.1，Maven 3.9.12；探针 JVM 未绑核
- 服务端：`nacos/nacos-server:v2.4.3`（standalone，Docker Desktop，容器未绑核、与探针共享宿主核），Testcontainers 起容器约 16s
- 客户端：nacos-client 2.4.3，`NacosRegistry` 缺省配置（`connect-wait-ms` = 3000）
- 日期：2026-10-19；工作区有未提交改动（git 18bda15 之上）
- 命令：`mvn -Pprobe -pl steadyrpc-registry-nacos -am test -Dtest=NacosTimingProbeTest -Dsurefire.failIfNoSpecifiedTests=false`
- 原始输出：`steadyrpc-registry-nacos/target/probe-nacos-timing.md`（构建产物，不入库）；本文的表格从它整理而来，数字未改动

## 数据

### 1. 首订阅漏事件（20 轮）

| 结果 | 轮数 | 可见延迟（自 register 开始） |
|---|---|---|
| 首次推送（subscribe 返回前那一次）就包含实例 | 0 | — |
| 靠后续推送补上 | 20 | min 12 / p50 527 / p90 573 / max 614 ms |
| 10s 内未见（漏事件） | **0** | — |

逐轮（subscribe 与 register 由两个线程同时放行，register 先随机延后 0~50ms）：

| 轮 | 注册延后 (ms) | subscribe 耗时 (ms) | register 耗时 (ms) | 注册相对订阅返回 | 推送次数 | 可见延迟 (ms) |
|---|---|---|---|---|---|---|
| 1 | 31 | 115 | 17 | 订阅返回前已注册完 | 2 | 82 |
| 2 | 48 | 30 | 14 | 订阅返回后才开始注册 | 2 | 547 |
| 3 | 38 | 24 | 12 | 订阅返回后才开始注册 | 2 | 488 |
| 4 | 4 | 28 | 13 | 订阅返回前已注册完 | 2 | 23 |
| 5 | 27 | 22 | 9 | 订阅返回后才开始注册 | 2 | 547 |
| 6 | 17 | 36 | 15 | 订阅返回前已注册完 | 2 | 614 |
| 7 | 0 | 24 | 9 | 订阅返回前已注册完 | 2 | 24 |
| 8 | 2 | 23 | 12 | 订阅返回前已注册完 | 2 | 21 |
| 9 | 11 | 21 | 10 | 订阅返回前已注册完 | 2 | 600 |
| 10 | 29 | 18 | 8 | 订阅返回后才开始注册 | 2 | 527 |
| 11 | 44 | 18 | 9 | 订阅返回后才开始注册 | 2 | 508 |
| 12 | 23 | 17 | 7 | 订阅返回后才开始注册 | 2 | 535 |
| 13 | 26 | 22 | 10 | 订阅返回后才开始注册 | 2 | 526 |
| 14 | 5 | 18 | 7 | 订阅返回前已注册完 | 2 | 12 |
| 15 | 49 | 15 | 6 | 订阅返回后才开始注册 | 2 | 563 |
| 16 | 7 | 21 | 6 | 订阅返回前已注册完 | 2 | 14 |
| 17 | 31 | 14 | 6 | 订阅返回后才开始注册 | 2 | 573 |
| 18 | 11 | 14 | 6 | 两者重叠 | 2 | 551 |
| 19 | 42 | 14 | 6 | 订阅返回后才开始注册 | 2 | 530 |
| 20 | 17 | 14 | 6 | 订阅返回后才开始注册 | 2 | 551 |

### 2a. 消失窗口：提供者 `close()`（不注销，连接正常关闭）

| 轮 | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| 消失耗时（自 close 开始，ms） | 597 | 599 | 596 | 599 | 597 |
| close 本身耗时（ms） | 2 | 1 | 2 | 1 | 1 |

上界 40s；实测 min 596 / max 599 ms。

### 2a'. 消失窗口：静默死亡（黑洞代理，无 FIN）

| 轮 | 消失耗时（自黑洞化开始，ms） |
|---|---|
| 1 | 26556 |
| 2 | 25452 |

上界 40s；实测 25.5 ~ 26.6s。

### 2b. 注册失败上限：`docker pause` Nacos

| 情形 | 结果 | 耗时 (ms) |
|---|---|---|
| 已连上的 Registry 在暂停期间 `register` | `RegistryException`，根因 gRPC 请求的 `TimeoutException: Waited 3000 milliseconds …` | 3010 |
| 暂停期间**新建** Registry（构造器） | 构造成功、未抛异常，但阻塞了 | 12014 |
| 暂停期间新建的 Registry `register` | `RegistryException`，根因 `NacosException: Client not connected, current status:STARTING` | 402 |
| 暂停总时长 | — | 15469 |
| unpause 后同一 Registry 再注册 | 第一次重试即成功 | 107 |
| unpause 后订阅方看到该实例 | 看到 | 586 |

## 结论

- **首订阅不漏事件**：20 轮里没有一轮在 10s 内看不到，最慢 614ms。"先查后订"两步之间注册的实例由之后的推送补上。
  首次推送（`getAllInstances` 那一次）一轮都没赶上，是实验设计使然：register 被安排在 subscribe 开始之后 0~50ms，
  而首次查询在 subscribe 开始后几毫秒内就完成。
- **可见延迟呈两档**：约 12 ~ 82ms 的那几轮，是 register 在 `NamingService.subscribe` 那次请求之前已在服务端生效，订阅请求返回的
  服务信息里就带着实例（第二次推送）；约 490 ~ 614ms 的那几轮靠服务端的变更推送，与 Nacos 2.x 服务端推送任务的缺省合并延迟
  （`pushTaskDelay` = 500ms）一致（这一点是推断，未对服务端源码逐行核对）。第 6、9 轮 register 在订阅返回前已完成，仍然要等约 600ms：
  订阅请求拿到的服务信息可能比刚完成的注册旧几毫秒（服务端的索引更新是异步的），之后由推送补上——同样不丢。
- **正常退出的实例约 0.6s 消失**（5 轮 596 ~ 599ms，几乎是常数）：服务端在连接关闭时立即摘除临时实例，剩下的是推送延迟。
- **静默死亡的实例约 26s 消失**（25.5 ~ 26.6s）：服务端要靠连接保活探测发现对端不在了。契约声明的 40s 有约 13s（约 50%）余量。
- **服务端无响应时，已连上的客户端 3.0s 注册失败**（单次 gRPC 请求超时 3s，抛 `RegistryException`），远在 15s 上界之内；
  unpause 后 0.1s 内恢复注册、0.6s 内订阅方可见，不需要重建 Registry。
- **服务端无响应时新建 Registry，构造器阻塞约 12s**（不抛异常），之后的 register 立刻以 "Client not connected" 失败。
  契约测试的"不可达"用例用的是拒绝连接的端口，量不到这种"连得上、不响应"的情形。
- 实验没有揭示 `NacosRegistry` 的实现缺陷，主代码逻辑未改。

## 对设计的影响

- 首订阅"先查后订"不需要额外补偿。`NacosRegistry.Subscription#establish` 的注释已从"待 Docker 可用后验证"改为引用本探针
  （只改注释，逻辑未动）。
- 消失窗口 40s 可以保留：它覆盖的是静默死亡（实测约 26s），不宜收紧到 30s 以下。正常退出的提供者约 0.6s 就从消费端消失，
  所以 40s 只在掉电 / 分区时才会被用到；这段时间里对已死提供者的调用要靠调用超时与 failover 兜底。
- 注册失败上限 15s 对"已连上"的客户端余量很大（实测 3s）。但"服务端无响应时新建 Registry"的构造器会阻塞约 12s，加上首次 register
  合计约 12.4s，仍在 15s 内、余量不大——这段阻塞落在 `RpcServer` / `RpcClient` 创建注册中心的启动路径上。是否要在契约里单独声明
  "构造上限"、或在 `NacosRegistry` 里改为异步建连，留给设计裁定。
- 消费端从注册到可见的典型延迟约 0.5 ~ 0.6s（服务端推送延迟），`awaitProviders` 一类等待的超时应以秒计。

## 重跑

在 WSL2（或任意有 Docker 的 Linux）的仓库根目录执行，`JAVA_HOME` 指向 JDK 21：

```sh
mvn -Pprobe -pl steadyrpc-registry-nacos -am test -Dtest=NacosTimingProbeTest -Dsurefire.failIfNoSpecifiedTests=false
```

整类约 2 分钟（容器启动约 16s，静默死亡两轮约 1 分钟）。结束后容器由 Testcontainers 回收；`docker pause` 的那个用例在 finally 里
unpause，类结束时还会再 unpause 一次兜底。
