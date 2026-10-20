# 传输路径对照调研（阶段 2a）

> 只读调研：对照 gRPC-Java、Apache Dubbo（辅以 SOFA-Bolt / Motan、Netty 官方做法）的同步调用传输路径，解释本框架在
> 同机 loopback 1KB 回显上的数字，给出候选优化清单。本文不改任何代码，不含新的实测；所有"预期收益"都是推断，须按文末的验证方式实测后才作数。

## 0. 范围、版本与方法

### 0.1 版本

| 对象 | 版本 / 提交 | 本地源码（只读） | 在线出处前缀 |
|---|---|---|---|
| 本框架 | `b5058cb`（HEAD） | 本仓库 | — |
| gRPC-Java | v1.85.0（tag `73a9039`） | `G:\rpc-build-tmp\research\grpc-java` | `https://github.com/grpc/grpc-java/blob/v1.85.0/` |
| Apache Dubbo | dubbo-3.3.6（`f158588`） | `G:\rpc-build-tmp\research\dubbo` | `https://github.com/apache/dubbo/blob/dubbo-3.3.6/` |
| Netty | 4.1.136.Final（`fca0764`） | `G:\rpc-build-tmp\research\netty` | `https://github.com/netty/netty/blob/netty-4.1.136.Final/` |
| SOFA-Bolt（SOFARPC 的传输层） | master `2a83ee9`（2026-08-26） | `G:\rpc-build-tmp\research\sofa-bolt` | `https://github.com/sofastack/sofa-bolt/blob/2a83ee9/` |
| Motan | master `4c18b71`（2025-11-24） | `G:\rpc-build-tmp\research\motan` | `https://github.com/weibocom/motan/blob/4c18b71/` |
| JDK | openjdk/jdk21u master（2026-10-20 取） | `G:\rpc-build-tmp\research\jdk21-*.java` | `https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/` |
| Protostuff | 1.8.0 | `G:\rpc-build-tmp\research\{WriteSink,ByteArrayInput}.java` | `https://github.com/protostuff/protostuff/blob/protostuff-1.8.0/` |

注意：
- 本框架引用一律以 **HEAD `b5058cb`** 为准。调研期间工作区里另一个代理正在改 `ReferenceProxy`、`RpcClient`、`PendingRequests`、
  `RpcClientConfig`、`Invocation`（`syncWaitGrace`、`RpcDeadlineTimer`），这些未提交改动不在本文的行号里；涉及处单独注明。
- SOFA-Bolt、Motan 没有钉到发布版本（只看一个关键点），JDK 源码取自 jdk21u master 而非压测机上的确切构建，行号可能有小偏差。

### 0.2 方法

逐文件读源码，结论标"出处 ID:行号区间"。ID 索引如下（本框架路径省略 `rpc-core/src/main/java/com/feynman/rpc/core/`）。

| ID | 文件 |
|---|---|
| O1 | `ReferenceProxy.java` |
| O2 | `RpcClient.java` |
| O3 | `client/NettyClient.java` |
| O4 | `client/ConnectionPool.java` |
| O5 | `client/PendingRequests.java` |
| O6 | `client/ClientHandler.java` |
| O7 | `codec/RpcEncoder.java` |
| O8 | `codec/RpcFrameDecoder.java` |
| O9 | `codec/RpcMessageDecoder.java` |
| O10 | `codec/EnvelopeCodec.java` |
| O11 | `server/NettyServer.java` |
| O12 | `server/ServerHandler.java` |
| O13 | `server/ServiceDispatcher.java` |
| O14 | `server/BusinessExecutor.java` |
| O15 | `config/ExecutorConfig.java` |
| O16 | `config/RpcClientConfig.java`（HEAD 版本） |
| O17 | `protocol/OutboundBody.java` |
| O18 | `rpc-serialize-protobuf/src/main/java/com/feynman/rpc/serialize/protobuf/TypeSchemas.java` |
| O19 | `filter/DeadlineClientFilter.java` |
| P1 | `docs/benchmarking.md`（对比对象、并发扫描节） |
| P2 | `docs/probes/harness-tail-latency.md`（结论节） |
| P3 | `docs/pitfalls.md`（第 32 条，约 420–480 行） |
| P4 | `README.md`（HEAD）第 335 行 |
| G1 | `stub/src/main/java/io/grpc/stub/ClientCalls.java` |
| G2 | `core/src/main/java/io/grpc/internal/ClientCallImpl.java` |
| G3 | `core/src/main/java/io/grpc/internal/SerializingExecutor.java` |
| G4 | `core/src/main/java/io/grpc/internal/ServerImpl.java` |
| G5 | `core/src/main/java/io/grpc/internal/GrpcUtil.java` |
| G6 | `netty/src/main/java/io/grpc/netty/WriteQueue.java` |
| G7 | `netty/src/main/java/io/grpc/netty/NettyServerStream.java` |
| G8 | `netty/src/main/java/io/grpc/netty/NettyClientStream.java` |
| G9 | `netty/src/main/java/io/grpc/netty/Utils.java` |
| G10 | `netty/src/main/java/io/grpc/netty/NettyWritableBufferAllocator.java` |
| G11 | `core/src/main/java/io/grpc/internal/MessageFramer.java` |
| G12 | `api/src/main/java/io/grpc/ServerBuilder.java` |
| G13 | `netty/src/main/java/io/grpc/netty/{NettyClientTransport,NettyServer,NettyChannelBuilder}.java` |
| G14 | <https://grpc.io/docs/guides/performance/>（官方性能最佳实践） |
| D1 | `dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/ThreadlessExecutor.java` |
| D2 | `dubbo-rpc/dubbo-rpc-api/src/main/java/org/apache/dubbo/rpc/protocol/AbstractInvoker.java` |
| D3 | `dubbo-rpc/dubbo-rpc-api/src/main/java/org/apache/dubbo/rpc/AsyncRpcResult.java` |
| D4 | `dubbo-remoting/dubbo-remoting-api/src/main/java/org/apache/dubbo/remoting/transport/dispatcher/WrappedChannelHandler.java` |
| D5 | `dubbo-remoting/dubbo-remoting-api/.../remoting/Dispatcher.java` 与 `.../transport/dispatcher/{all/AllChannelHandler,direct/DirectChannelHandler,message/MessageOnlyChannelHandler,execution/ExecutionChannelHandler}.java` |
| D6 | `dubbo-common/.../common/threadpool/ThreadPool.java`、`.../threadpool/support/fixed/FixedThreadPool.java`、`.../common/constants/CommonConstants.java` |
| D7 | `dubbo-remoting/dubbo-remoting-api/.../remoting/Constants.java` |
| D8 | `dubbo-remoting/dubbo-remoting-netty4/src/main/java/org/apache/dubbo/remoting/transport/netty4/{NettyServer,NettyClient,NettyPortUnificationServer,NettyEventLoopFactory}.java` |
| D9 | `.../netty4/NettyChannel.java` |
| D10 | `.../netty4/Netty4BatchWriteQueue.java` 与 `dubbo-common/.../common/BatchExecutorQueue.java` |
| D11 | `.../netty4/NettyCodecAdapter.java` |
| D12 | `dubbo-rpc/dubbo-rpc-dubbo/.../protocol/dubbo/{DubboCodec,Constants,DubboProtocol,DubboInvoker}.java` |
| D13 | `dubbo-rpc/dubbo-rpc-triple/.../protocol/tri/{TripleProtocol,TripleInvoker}.java`、`.../tri/transport/TripleWriteQueue.java`、`dubbo-remoting-api/.../api/connection/SingleProtocolConnectionManager.java` |
| D14 | `dubbo-rpc/dubbo-rpc-triple/.../tri/h12/AbstractServerTransportListener.java` |
| D15 | `dubbo-remoting/dubbo-remoting-api/.../exchange/support/DefaultFuture.java` |
| D16 | `dubbo-plugin/dubbo-plugin-loom/.../threadpool/support/loom/VirtualThreadPool.java` |
| D17 | <https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/tasks/framework/threading-model/>（官方线程模型文档） |
| N1 | `codec/src/main/java/io/netty/handler/codec/ByteToMessageDecoder.java` |
| N2 | `codec/.../LengthFieldBasedFrameDecoder.java` |
| N3 | `handler/src/main/java/io/netty/handler/flush/FlushConsolidationHandler.java` |
| N4 | `transport/src/main/java/io/netty/channel/AbstractChannelHandlerContext.java` |
| N5 | `transport/.../channel/nio/NioEventLoop.java` |
| N6 | `transport/.../channel/MultithreadEventLoopGroup.java` |
| N7 | `transport/.../channel/socket/DefaultSocketChannelConfig.java`、`common/.../util/internal/PlatformDependent.java` |
| N8 | `transport/.../channel/WriteBufferWaterMark.java`、`.../AdaptiveRecvByteBufAllocator.java`、`.../socket/DefaultServerSocketChannelConfig.java` |
| N9 | `buffer/.../PooledByteBufAllocator.java`、`buffer/.../ByteBufUtil.java`、`buffer/.../PoolArena.java` |
| N10 | `handler/.../timeout/IdleStateHandler.java` |
| J1 | `java/util/concurrent/CompletableFuture.java`（jdk21u） |
| J2 | `java/lang/VirtualThread.java`（jdk21u） |
| B1 | sofa-bolt `src/main/java/com/alipay/remoting/{rpc/protocol/AbstractUserProcessor,rpc/protocol/RpcRequestProcessor,codec/AbstractBatchDecoder,rpc/protocol/RpcCommandHandler,rpc/RpcConfigs}.java` |
| M1 | motan `motan-core/.../transport/AbstractSharedPoolClient.java`、`motan-core/.../common/URLParamType.java`、`motan-transport-netty4/.../netty4/{NettyClient,NettyServer}.java` |
| S1 | protostuff `protostuff-api/.../WriteSink.java`、`protostuff-core/.../ByteArrayInput.java` |

### 0.3 已知数字（输入，非本文测量）

WSL2、同机 loopback、1KB 回显、闭环 64（P1 对比对象节与任务简报）：

| 配置 | 吞吐 | 备注 |
|---|---|---|
| 本框架，每节点 2 连接 | 83k req/s | 每连接折合 ~42k |
| gRPC 1.85，单 channel，服务端 directExecutor | 65k | gRPC 上界对照点，非缺省用法 |
| gRPC 1.85，单 channel，缺省执行器 | 14.7k | 服务端 IO 线程 91% 在 `SynchronousQueue.offer` / unpark |
| Dubbo 3.3.6 triple，缺省 1 连接 | 13.8k | |
| Dubbo 3.3.6 triple，2 个独立实例各 1 连接 | 22.8k | `connections` 对 triple 不起作用（P1） |
| 任务 7 JFR：发压端每调用 CPU | ~37µs | 主要是系统调用与唤醒 |
| 任务 11 扫描 | 128 并发后吞吐不随并发增长，两侧核约六成；每节点 8 连接后瓶颈转压测端 | P1「并发扫描与连接数扫描」 |

### 0.4 一图总览：一次同步调用的线程交接与 flush

| 环节 | gRPC 阻塞 stub + 缺省执行器 | gRPC + 服务端 directExecutor | Dubbo triple 缺省 | Dubbo dubbo 协议（dispatcher=all） | 本框架 |
|---|---|---|---|---|---|
| ① 客户端 调用方→IO | WriteQueue：CAS 胜者一次 `eventLoop.execute`/批（G6:63-91） | 同左 | TripleWriteQueue（同构，D13） | Netty4BatchWriteQueue 一次/批（D10） | **每请求一次** `loop.execute`（O3:177-182） |
| ② 服务端 IO→业务 | `SerializingExecutor`→缓存池，`SynchronousQueue` 交接，每 call ≥1 次（G4:475-482,635-636,863） | **0**（G4:478-482） | `SerializingExecutor`→fixed 200（D14:133-137） | fixed 200，`SynchronousQueue`（D5、D6） | 固定池 `ArrayBlockingQueue` 1 次（O12:93、O14:59-63） |
| ③ 服务端 业务→IO 写出 | WriteQueue 一次/批（G7:99-125） | 0：IO 线程内入队，本轮末一次 flush | 批 | 批（D9:200-207） | **每响应一次** `ctx.writeAndFlush` 跨线程任务（O12:158） |
| ④ 客户端 IO→调用方 | ThreadlessExecutor unpark（G1:794-865） | 同左 | ThreadlessExecutor（D13） | ThreadlessExecutor（D2:340-346） | CompletableFuture `Signaller` unpark（J1:1877-1905） |
| 每请求 flush（write 系统调用） | 按批 | 按批 | 按批 | 按批 | **两端各每请求一次** |
| 跨线程交接合计 | ~4（②可能多次） | **2** | ~4 | ~4 | **4** |
| 响应反序列化所在线程 | 调用方 | 调用方 | 调用方 | 调用方（`decode.in.io=false`） | **客户端 IO 线程**（O2:362-404） |

一句话：交接次数我们与 gRPC/Dubbo 缺省相当（简报里"两次"只数了服务端与回程各一次，实际两端合计 4 次）；**差别在批量**——gRPC、Dubbo 两端都把
"跨线程写出"做成"每批一次唤醒、一次 flush"，我们是每请求一次唤醒、一次 flush。gRPC directExecutor 再省掉服务端两次交接，单连接就到 65k。

### 0.5 本框架一次同步调用的逐步路径（HEAD，读代码整理）

| 步 | 线程 | 动作 | 跨线程？ | 可能的系统调用 | 出处 |
|---|---|---|---|---|---|
| 1 | 调用方（VT） | 代理 → 闸门 → `Invocation`（复制 attachments）→ DeadlineClientFilter 写 `rpc.timeout`/`rpc.deadline` → Failfast → LoadBalance | 否 | — | O1:53-80、O19:27-33 |
| 2 | 调用方 | `attempt`：`wireAttachments` 再复制一份、校验 attachments 长度、`loadStats.begin` | 否 | — | O2:278-299 |
| 3 | 调用方 | `send`：`nextRequestId`、`register`（CHM put + `orTimeout` 入全局 STPE） | 否（但取全局锁） | 可能 futex（锁争用） | O3:129-131、O5:35-38 |
| 4 | 调用方 | `acquire`：轮询游标取已激活连接，返回 completedFuture，`whenComplete` 同步继续 | 否 | — | O4:61-71、O3:133 |
| 5 | 调用方 | `write`：按协商的序列化器序列化参数成 `byte[]`、算帧长、`ChannelRequests` 登记 | 否 | — | O3:148-175 |
| 6 | 调用方 → IO | `loop.execute(writeOnEventLoop)` | **是（交接 1）** | 事件循环在 select 中时 eventfd 写（`selector.wakeup`） | O3:177-182、N5:859-863 |
| 7 | 客户端 IO | `isWaiting`、`isActive` 检查 → `writeAndFlush` → 编码器分配整帧、写入 → IdleStateHandler 挂监听 → flush | 否 | **`write` 1 次/请求** | O3:194-211、O7:68-93、N10:302-309 |
| 8 | 服务端 IO | `read` → 累积 → 切帧（retainedSlice）→ 解头与 attachments → 闸门 → `bizExecutor.execute` | **是（交接 2）** | `read`（按批摊薄）；业务线程在 `take` 中时 futex 唤醒 | N1、O9:40-87、O12:78-101 |
| 9 | 服务端业务 | 解信封、查方法（拼 methodKey）、Filter 链、反序列化参数、反射调用、序列化返回值、构造回包 | 否 | — | O13:144-199,227-339 |
| 10 | 服务端业务 → IO | `ctx.writeAndFlush(reply)` → Netty `WriteTask` + `execute` | **是（交接 3）** | eventfd 写（IO 线程在 select 中时） | O12:158、N4:963-995 |
| 11 | 服务端 IO | 编码整帧 → flush | 否 | **`write` 1 次/响应** | O7:68-93 |
| 12 | 客户端 IO | `read` → 切帧 → `pending.complete` → 依赖链：`remove`+取消定时器（全局锁）、`onChannel.remove`、`loadStats.end`、**解码与反序列化**、`gate.exit` | 否 | 可能 futex（定时器锁） | O6:55、O5:49-55、O2:315-404、O1:80 |
| 13 | 客户端 IO → 调用方 | `Signaller.tryFire` → `VirtualThread.unpark` → FJP 外部提交 | **是（交接 4）** | 载体空闲时 futex 唤醒 | J1:1830-1870、J2:736-760 |
| 14 | 调用方 | `join` 返回，按 Result 抛异常或返回值 | 否 | — | O1:97-110 |

对照 gRPC 阻塞 stub + 服务端 directExecutor：步 6 按批（WriteQueue CAS），步 8/10 不存在（IO 线程内执行、入队、本轮末一次 flush），步 7/11 的 `write` 按批，
步 12 的反序列化在步 14 的调用方线程上做。**同样 4 处"可能的系统调用"，它们只剩 2 处且都按批摊薄**——这就是单连接 65k 与我们每连接 42k 的结构性来源（推断，待 T3 实验验证）。

---

## T1 同步调用的唤醒路径

**① gRPC 怎么做**
- `blockingUnaryCall` 为每次调用新建 `ThreadlessExecutor`，作为该 call 的回调执行器；调用方线程在 `while (!future.isDone()) executor.waitAndDrain()`
  里 park（G1:155-180）。`ThreadlessExecutor` = `ConcurrentLinkedQueue` + `volatile waiter`：`execute` 入队后 `LockSupport.unpark(waiter)`，
  `waitAndDrain` 取不到任务就 park，取到后把队列里的任务全部在本线程执行（G1:794-865）。
- `ClientCallImpl` 对非 direct 执行器再包一层 `SerializingExecutor`（G2:108-116）。IO 线程在 `messagesAvailable` 里只投递
  `MessagesAvailable` 任务，**`method.parseResponse`（protobuf 反序列化）在调用方线程执行**（G2:634-674，parse 在 662）。
- 交接：IO→调用方 1 次（headers / message / trailers 若落在同一次读里，入队三次但调用方只醒一次就全部 drain）。

**② Dubbo 怎么做**
- 同步调用 `getCallbackExecutor` 返回新的 `ThreadlessExecutor`（D2:340-346），`AsyncRpcResult.get()` 在其上 `waitAndDrain`（D3:197-230）。
  实现与 gRPC 同构：CLQ + `AtomicReference waiter` + `parkNanos`（D1:40-104）。
- 响应到达：`WrappedChannelHandler.getPreferredExecutorService` 按 requestId 找到 `DefaultFuture` 上的 ThreadlessExecutor（D4:112-135），
  dispatcher 把 `ChannelEventRunnable` 投进去；`decode.in.io` 缺省 false（D12 `Constants.java:29-31`、`DubboCodec.java:110-135`），
  响应体反序列化在调用方线程。
- **动机**（D17）：2.7.5 之前响应先交给独立的消费端线程池反序列化、再唤醒业务线程（IO→消费池→业务线程 两次交接），网关类大流量场景线程数膨胀；
  ThreadlessExecutor 复用"反正在阻塞等待"的业务线程，去掉一次交接和一个线程池。官方博客称 2.7.5 全链路优化后 QPS 提升近 30%（检索摘要，未逐字核验，见 §末"存疑"）。

**③ 我们怎么做**
- 同步方法：`future.join()`（O1:99）。JDK 21 `waitingGet` 建 `Signaller` 入栈后 `ForkJoinPool.managedBlock` → `LockSupport.park`（J1:1877-1905）；
  调用方是虚拟线程（harness 的 pool 工作者，P2）时 park = 卸载。
- 响应：`ClientHandler.channelRead` → `pending.complete(message)`（O6:55）→ `future.complete` 在 **IO 线程**上同步跑完依赖链：
  `whenComplete(loadStats.end)` → `handle(decode)`（O2:315-338）——**`decode` / `serializer.deserialize` / 异常还原都在 IO 线程**（O2:362-404）
  → `whenComplete(gate.exit)`（O1:80）→ 最后 `Signaller.tryFire` unpark 调用方。虚拟线程的 unpark 从平台线程发起时是一次
  `ForkJoinPool` 外部提交 + 可能唤醒空闲载体（J2:262-264,736-760）。
- 发出侧（T3 详述）：调用方线程序列化后 `loop.execute(writeOnEventLoop)`（O3:177-182），这是第一次交接。

**④ 差异对数字的可能解释**
- 交接次数上我们与二者相同（客户端 2 次：发出、唤醒）。不同在于：(a) 我们的 IO 线程多做了响应反序列化（1KB 时 2 次 1KB 拷贝 + Protostuff 解码 +
  `Result`/CF 依赖链），gRPC/Dubbo 把它推给调用方；(b) 我们的发出侧每请求一次 `execute`（可能伴随 `selector.wakeup()`，N5:859-863），
  二者每批一次。客户端 IO 线程在闭环 64、每连接 42k 时每请求预算约 24µs，(a)(b) 都直接吃这份预算。
- 任务 7 的"每调用 ~37µs、主要是系统调用与唤醒"与此一致：每次调用至少 1 次 eventfd 唤醒（若 IO 线程在 select 里）、1 次 `write`、
  1 次 FJP 唤醒载体（若载体空闲）。

**⑤ 候选改法**
- T1-a（中成本）：同步路径的"延迟解码"。IO 线程只做 `pending.complete(message)` 与只看帧头的判定（SERVER_OVERLOADED 走传输失败通道，O2:319-321，只需 status），
  body 解码挪到 `join()` 返回后的调用方线程——等价于 ThreadlessExecutor 的效果，但不需要新执行器（我们本来就是"一个 future 一个等待者"）。
  难点：客户端 Filter 链看到的是 `Result`（值已解码），需要一个惰性 `Result`（类似 Dubbo `DecodeableRpcResult`）或把解码放到 Filter 链之后。
- T1-b（小成本）：合并依赖链。`register` 的两个 `whenComplete`、`onChannel.remove`、`loadStats.end`、`gate.exit` 共 5 个单独阶段（O5:35-38、O3:175、O2:315、O1:80），
  可收成一个完成回调，少 4~5 个 CF + Completion 对象/调用。

**⑥ 预期收益与代价**
- T1-a：客户端 IO 线程每请求少一次反序列化（推断占客户端 IO 时间的一成上下，未测）；只有客户端 IO 线程是瓶颈时（连接少、`client_bound` 不成立时）
  才体现为吞吐。代价：Result 语义改动、Filter 可见性、异步路径不受益（异步回调仍在 IO 线程，见缺陷节 F3）。
- T1-b：分配率下降、GC 次数略降；吞吐影响小（<5%，推断）。

**⑦ 验证**
- 卡：`compare-closed64-rpc-v1`（吞吐）、`compare-open10k-rpc-v1`（p50/p99）；建议新增 `sweep-c1-closed64-rpc-v1`（每节点 1 连接，直接读"每连接吞吐"，与 gRPC direct 单 channel 65k 对位）。
- 指标：客户端 IO 线程 CPU（`top -H` / JFR `jdk.ExecutionSample` 按线程）、JFR `jdk.ObjectAllocationSample` 每请求分配字节。

---

## T2 服务端派发

**① gRPC 怎么做**
- 缺省执行器 = 进程共享 `Executors.newCachedThreadPool`（"grpc-default-executor"，G5:557-563），缓存池的队列是 `SynchronousQueue`：
  `offer` 只在有空闲线程正在 `poll` 时成功，否则新建线程。
- 每个 call 一个 `SerializingExecutor(executor)`（G4:475-482）；streamCreated 投 `MethodLookup`、`HandleServerCall` 两个任务（G4:635-636），
  之后 `messagesAvailable`、`halfClosed` 各投一个（G4:863、868）。`SerializingExecutor` 只有在 STOPPED→RUNNING 时才 `executor.execute(this)`
  （G3:93-145），所以一次 unary 视读批次与执行进度，会对缓存池产生 1~3 次交接。
- `directExecutor()`：传入 direct 时改用 `SerializeReentrantCallsDirectExecutor` 并 `stream.optimizeForDirectExecutor()`（G4:478-482），
  回调直接在 IO 线程执行。javadoc："application must not block under any circumstances … may lead to 10s of µs latency reduction but causes a
  substantial performance degradation when misused"（G12:47-62）。官方 Java 建议是"提供一个按负载限线程数的自定义执行器"（G14）。

**② Dubbo 怎么做**
- dubbo 协议：`Dispatcher` SPI 缺省 `all`（D5 `Dispatcher.java:28`）。语义（D17 + D5 源码）：

| dispatcher | 请求（received）在哪执行 | 连接 / 断开 / 异常事件 | 源码 |
|---|---|---|---|
| `all`（缺省） | 业务池 | 业务池 | `AllChannelHandler.java:39-78` |
| `direct` | IO 线程（除非目标是 ThreadlessExecutor） | IO 线程 | `DirectChannelHandler.java:38-47` |
| `message` | 业务池 | IO 线程 | `MessageOnlyChannelHandler.java:39-44` |
| `execution` | 仅 Request 进业务池，Response 在 IO（ThreadlessExecutor 除外） | IO 线程 | `ExecutionChannelHandler.java:44-62` |
| `connection` | 业务池；连接事件单独有序池 | 单独池 | D17 |

- 业务池：`ThreadPool` SPI 缺省 `fixed`（D6 `ThreadPool.java:29`），200 线程（`CommonConstants.java:111`），`queues` 缺省 0 →
  `SynchronousQueue`（`FixedThreadPool.java:50-63`、`CommonConstants.java:141`）。即与 gRPC 缓存池同一种"直接交接、不排队"，只是线程数封顶 200。
- triple：每个流 `new SerializingExecutor(业务执行器)`（D14:133-137），`onData` 等逐个投递（D14:161-175），结构照搬 gRPC。
- 拒绝：`all`/`message` 在 `RejectedExecutionException` 且消息是 Request 时回包告知（`AllChannelHandler.java:61-66`）。

**③ 我们怎么做**
- IO 线程只解帧头 + attachments（O9:40-87），`ServerHandler.dispatch` 过闸门后 `bizExecutor.execute(new DispatchTask)`（O12:78-101）。
- 缺省 `ExecutorConfig.defaults()` = 固定池 2×核数 + `ArrayBlockingQueue(1000)`（O15:22,50-52；O14:56-63）；满则 SERVER_OVERLOADED。
  虚拟线程模式：每请求一个虚拟线程 + `Semaphore` 封顶（O14:67-69,80-97）。
- **没有"在 IO 线程上执行业务"的选项**（`ExecutorConfig` 是 sealed，只有 Fixed / VirtualThreads，O15:19）。
- 回包：业务线程 `ctx.writeAndFlush(reply)`（O12:153-159），见 T3。

**④ 差异对数字的可能解释**
- gRPC 缺省 14.7k vs directExecutor 65k（4.4×）说明在 1KB 回显这种"业务几乎为零"的负载上，**派发交接本身就是主成本**；
  其 IO 线程 91% 在 `SynchronousQueue.offer`/unpark，正是"每个任务都要找到一个正在 poll 的空闲线程并唤醒它"的代价。
- 我们的 `ArrayBlockingQueue` 交接比 `SynchronousQueue` 便宜（生产者不必等消费者就位，锁 + `notEmpty.signal`），这是我们缺省配置比 gRPC/Dubbo 缺省高 5~6 倍的主要来源之一（推断；
  另一来源是我们缺省 2 连接）。但相对 gRPC direct，我们服务端每请求仍多两次交接（IO→业务、业务→IO），
  这解释了"每连接 42k < gRPC direct 单连接 65k"——即使 gRPC 每请求还要做 HTTP/2 帧与 HPACK。

**⑤ 候选改法**
- T2-a（中成本，opt-in）：`ExecutorConfig.direct()`（sealed 增加一种），`ServerHandler` 在 IO 线程上直接 `dispatcher.handle`；回包用 `ctx.write`，
  在 `channelReadComplete` 统一 flush（或配合 T3 的 FlushConsolidationHandler）。限定给"非阻塞、微秒级"的服务；文档写明误用后果（同 G12）。
  按服务（ServiceOptions）而非全局开启更安全：同一端口上阻塞型服务仍走池。
- T2-b（小成本）：保留固定池，但 IO 线程按"一次读批次"把多条请求打包成一个任务投递（SOFA-Bolt 的 `AbstractBatchDecoder` 把一次读出的多条消息
  作为 `List` 往下传，`RpcCommandHandler` 一次 `execute` 处理整批，B1 `AbstractBatchDecoder.java:30-53,266-282`、`RpcCommandHandler.java:100-120`，
  缺省开启 `RpcConfigs.java:54-55`）。代价：同批请求串行执行，队头阻塞；与"每请求独立 deadline/拒绝"的语义要重新对齐。

**⑥ 预期收益与代价**
- T2-a：去掉服务端 2 次交接，参照 gRPC 的 4.4×，我们预期单连接服务端上限显著提升（量级推断 1.3~2×，因为我们现有交接更便宜）；p50 降几十 µs（G12 的说法）。
  代价：隔离性（阻塞即卡住同一事件循环上的所有连接）；在途计数、running、拒绝语义需重定义；**不能做缺省**（违背"默认不牺牲隔离性"）。
- T2-b：交接次数按批摊薄；收益取决于每次读出的帧数（闭环 64/2 连接时预计每批多帧）。代价：串行化引入的尾延迟、语义复杂度；优先级低于 T3。

**⑦ 验证**
- 卡：`compare-closed64-rpc-v1` 服务端分别以 fixed / direct 起（同 gRPC 的 `--executor direct` 做法，结果另放目录、标"非缺省"）；`compare-open10k-rpc-v1` 看 p50/p99。
- 指标：吞吐、服务端 worker 线程 CPU、服务时间 p50；JFR `jdk.ThreadPark` 计数（业务线程 park/unpark 次数/请求）。

---

## T3 写出与 flush

**① gRPC 怎么做**
- `WriteQueue`（每连接一个）：任意线程 `enqueue(command, flush)` 进 `ConcurrentLinkedQueue`；需要 flush 时 `scheduleFlush` 用
  `AtomicBoolean` CAS，**只有胜者**调用一次 `channel.eventLoop().execute(later)`（G6:63-91）。`later` 在事件循环上把队列里的命令全部执行，
  每 128 条 flush 一次、末尾至少 flush 一次（G6:122-153）。注释写明"不要在事件循环外 `channel.write`，它不 flush 就不会立即唤醒"（G6:65-67）。
- 客户端流的 headers/消息/半关闭与服务端流的 headers/消息/trailers 都经它（G8:171-207、G7:99-133）。
- directExecutor 时服务端回包在 IO 线程上入队，`scheduleFlush` 的任务排在本轮读之后，**一轮读出的所有请求的回包合并成一次 flush**。

**② Dubbo 怎么做**
- `NettyChannel.send` → `Netty4BatchWriteQueue.enqueue`（D9:192-215）。父类 `BatchExecutorQueue`：CLQ + `AtomicBoolean scheduled`，
  CAS 胜者一次 `eventLoop.execute`，执行时把快照里的消息 `prepare` 进 `MultiMessage`，每 128 条或末条 `flush`（D10 `BatchExecutorQueue.java:25-85`）；
  `flush` 对整个 `MultiMessage` 只调一次 `writeAndFlush`（D10 `Netty4BatchWriteQueue.java:59-86`）。triple 的 `TripleWriteQueue` 同样继承它（D13）。
- `encode.in.io` 缺省 false（D6 `CommonConstants.java:608-610`、D9:98）：**业务/调用方线程**用 `channel.alloc().buffer()` 编码成 ByteBuf，
  IO 线程的 `InternalEncoder` 再 `out.writeBytes(buf)` 拷进一个大缓冲区（D11:63-89）——批内多条消息合成一个出站缓冲区、一次写。

**③ 我们怎么做**
- 客户端：调用方线程序列化后，每请求一次 `loop.execute(() -> writeOnEventLoop(...))`（O3:177-182）；事件循环上每请求一次
  `channel.writeAndFlush(message)`（O3:205）→ 编码器按确切长度分配整帧（O7:68-93）→ **每请求一次 flush**。
- 服务端：业务线程 `ctx.writeAndFlush(reply)`（O12:158）。Netty 对事件循环外的 `writeAndFlush` 包一个 `WriteTask` 并 `execute`（非 lazy，会唤醒，
  N4:963-995、1175-1183）→ **每响应一个任务、一次 flush**。
- 没有任何 flush 合并：pipeline 里没有 `FlushConsolidationHandler`（O3:90-96、O11:101-105）。
- 附带：两端 `IdleStateHandler` 配了 writer idle，每次 write 都 `promise.unvoid().addListener(writeListener)`（N10:302-309），每写一次多一个监听器回调。

**④ 差异对数字的可能解释**
- 每连接每秒 42k 次 `write` 系统调用（两端各一份）+ 同量级的跨线程任务投递与潜在 `selector.wakeup()`（eventfd 写）。gRPC/Dubbo 在同样负载下
  一个事件循环轮次只 flush 一次（闭环 64/连接时一轮通常能攒多条），系统调用次数按批摊薄。
- 这与 P2 的证据一致：忙轮询让"调用方提交写出时不用唤醒 IO 线程"后服务时间 p50 从 ~0.22ms 降到 0.147ms，"此前所谓两次交接约 75µs 的固定开销，大部分就是这一次唤醒"。
- 也与 P1 的现象一致：连接数加到 8 后每个 worker 只用到 1/3，说明每连接的 IO 线程是被"每请求固定开销"（系统调用 + 任务）而非带宽压住的。

**⑤ 候选改法**
- T3-a（极小成本）：两端 pipeline **首位**加 `new FlushConsolidationHandler(256, true)`（N3:30-58,98-140,207-212）。
  `consolidateWhenNoReadInProgress=true` 时，事件循环外来的 flush 改为提交一个 flush 任务，排在它之前已入队的写任务都会被同一次 flush 带走；
  读循环中的 flush 推迟到 `channelReadComplete`。不改语义、不加定时器。只省 flush，不省任务投递/唤醒。
- T3-b（小~中成本）：每连接一个写队列（gRPC `WriteQueue` / Dubbo `BatchExecutorQueue` 同构）：调用方/业务线程把 `RpcMessage` 入 MPSC 队列，
  CAS 胜者投一次 drain 任务；drain 在事件循环上逐条做现有的检查（已超时不写、连接已断立即失败，O3:194-211），逐条 `write(msg, promise)`，末尾一次 `flush`。
  客户端替换 `NettyClient.write` 的 execute 段，服务端替换 `ServerHandler.reply`。编码仍在事件循环上分配（不违背 O17 的原则）。
- T3-c（随 T2-a）：IO 线程执行时用 `ctx.write` + 读完成时 flush。

**⑥ 预期收益与代价**
- T3-a：每请求 flush/`write` 系统调用数下降到"每轮一次"；服务端回包路径受益最大（业务线程并发回包）。预期每连接吞吐 +10~30%（推断，量级参考
  gRPC/Dubbo 的批量设计 + P2 的唤醒证据；须实测）。p50 低负载时不变（无定时器），高负载时可能略降。风险低：Netty 官方组件，主要风险是低并发时
  flush 任务多排一轮队列（微秒级）。
- T3-b：在 T3-a 基础上再省"每请求一次 execute + 可能的 wakeup"。预期再 +10~20%（推断）。风险：顺序与失败回调要逐条保留；需要新的内部类与测试
  （超时请求不写出、连接断开时队列里的请求立即失败、关闭时清空队列释放）。
- 两者都不改变 `ChannelOutboundBuffer` 无界的问题（见缺陷 F1）。

**⑦ 验证**
- 卡：`compare-closed64-rpc-v1`、`sweep-c4/c8-closed128-rpc-v1`（看每连接吞吐与 worker CPU 是否改善）、`compare-open10k-rpc-v1`（p50/p99 不得回退）。
- 指标：每请求 `write`/`writev`/`futex`/eventfd 写次数（`strace -c -f -p <pid>` 短采样或 `perf stat -e 'syscalls:sys_enter_write*'`，WSL2 上 perf 可用性待确认）、
  吞吐、两端 IO 线程 CPU、JFR `jdk.SocketWrite` 事件数。

---

## T4 连接模型

**① gRPC 怎么做**
- 一个 `ManagedChannel` 到一个目标缺省 pick_first，一条 HTTP/2 连接承载所有并发流；一条连接固定在一个事件循环上。
- 官方建议：连接上活跃流数达到上限时 RPC 在客户端排队，"为高负载区域单独建 channel"或"用 channel 池把 RPC 分摊到多条连接"，并称多 channel 是
  "临时性的变通"（G14）。grpc-java 不内建"每目标 N 条连接"。

**② Dubbo 怎么做**
- dubbo 协议：`connections` 缺省 0 → 共享连接，`shareconnections` 缺省 1（D12 `DubboProtocol.java:452-471`、`Constants.java:27`）——
  同一地址上所有服务共用 1 条连接；`connections=N` 则每服务独占 N 条，`DubboInvoker` 轮询（D12 `DubboInvoker.java:96-101`）。
- triple：`refer` 时 `PortUnificationExchanger.connect(url)`，按地址一条连接、引用计数复用（D13 `TripleProtocol.java:184-193`、
  `SingleProtocolConnectionManager.java:44-62`），**没有 `connections` 参数路径**——与 P1 实测"`connections=2` 仍只有 1 条 ESTABLISHED"一致。

**③ 辅：Motan / SOFA-Bolt**
- Motan：`AbstractSharedPoolClient` 每个 referer 固定 `minClientConnection` 条连接（缺省 **2**，`URLParamType.java:53`），`getChannel` 原子计数取模轮询、
  失效则顺延并重建（M1 `AbstractSharedPoolClient.java:42-117`）——与我们几乎同构。
- SOFA-Bolt 看的是线程模型（T2-b 已引用），连接模型未查（见存疑）。

**④ 我们怎么做**
- 每节点 `connectionsPerNode` 条连接，缺省 2（O16 HEAD:50），按地址跨服务共享；`ConnectionPool.acquire` 原子游标轮询、跳过未激活的槽（O4:61-89）。
- IO 线程：客户端/服务端 worker 缺省 Netty 的 2×核数（O3:78、O11:83、N6:40-41），但**一条连接只绑一个事件循环**，实际干活的 IO 线程数 = 连接数（P1）。

**⑤ "吞吐随连接数增长、服务端只有连接数个 IO 线程干活"在各框架里对应的设计选择**

| 框架 | 缺省连接数 | 每连接并发方式 | 想用满多核的官方答案 | 单连接上限由什么决定 |
|---|---|---|---|---|
| gRPC | 1 / channel | HTTP/2 多路复用 | 多 channel / channel 池（G14） | 事件循环每请求的 HTTP/2 + 交接成本；directExecutor 时 65k |
| Dubbo dubbo 协议 | 1（共享） | requestId 多路复用 | `connections=N` | 同上 + `SynchronousQueue` 交接 |
| Dubbo triple | 1 / 地址 | HTTP/2 | 无内建，只能多实例（P1 c2 做法） | 13.8k |
| Motan | 2 | requestId 多路复用 | `minClientConnection` | — |
| 本框架 | 2 | requestId 多路复用 | `connectionsPerNode` | 每请求一次跨线程任务 + 一次 flush（两端），~42k |

结论：所有框架都是"连接 = 事件循环"的 Netty 模型，没有谁在单连接上用多个 IO 线程；差别只在**单连接上每请求的 IO 线程成本**。
我们的连接数扫描现象（P1）不是缺陷，而是 T3 每请求成本的放大镜：把每连接成本降下来，同样 2 条连接就能更接近核数上限。

**⑥ 候选改法与收益/代价**
- T4-a（极小成本）：缺省 `connectionsPerNode` 2→4。收益：按任务 11 扫描，吞吐提升直到压测端成为瓶颈；代价：服务端连接数 ×2（N 个客户端 × 4），
  并且掩盖 T3 的每连接成本，对比口径（"开箱即用"）随之改变。**建议先做 T3，再重跑连接数扫描后裁定**。
- T4-b（不建议）：自适应连接数（按在途数扩缩）。复杂度高、与排空/重连逻辑（ConnectionManager）耦合，收益可由 T4-a 覆盖。

**⑦ 验证**
- 卡：`sweep-closed128-rpc-v1` / `sweep-c4-` / `sweep-c8-closed128-rpc-v1`，再加建议的 `sweep-c1-closed64-rpc-v1`；指标：每连接吞吐 = 吞吐 / 连接数、
  每个 worker 线程 CPU、`client_bound`。

---

## T5 编解码路径与拷贝

**① gRPC 怎么做**
- 出站：`MessageFramer` 在**调用方线程**上向 `NettyWritableBufferAllocator` 要缓冲区，后者直接 `allocator.buffer(capacityHint, capacityHint)`
  （池化，G10:37-48；G11:248-294）；protobuf 序列化直接写进 ByteBuf，没有中间 `byte[]`。代价是调用方线程碰池化分配器（与我们 P3 的原则相反，见 T7）。
- gRPC 自建分配器：`maxOrder=8`（2MiB chunk）、heap/direct 按 preferDirect（G9:139-189）。
- 入站：HTTP/2 DATA 帧 → `NettyReadableBuffer`（不 retain 的包装）→ 解帧器累积 → `parseResponse(InputStream)` 在调用方线程（G2:662）。

**② Dubbo 怎么做**
- 出站（`encode.in.io=false`）：调用方/业务线程 `channel.alloc().buffer()`（初始容量缺省、随写扩容）编码 → IO 线程 `out.writeBytes(buf)` 再拷一次（D9:200-205、D11:63-89）。
- 入站：`InternalDecoder extends ByteToMessageDecoder`（MERGE_CUMULATOR）循环 `codec.decode`（D11:91-121）；`DubboCodec` 在 IO 线程把 body
  `readMessageData` 读成 `byte[]`（一次拷贝），反序列化推迟到业务线程（D12 `DubboCodec.java:110-135,236-256`）。

**③ 我们怎么做**
- 帧解码：`RpcFrameDecoder extends LengthFieldBasedFrameDecoder`（O8），`extractFrame` = `buffer.retainedSlice`（N2:501-502），零拷贝；
  `RpcMessageDecoder` 解头、解 attachments 成 Map、body `readRetainedSlice`（O9:40-87）。
- body 惰性：服务端在业务线程 `EnvelopeCodec.readRequest`（slice，不拷贝，O10:150-171）→ 每个参数 `ByteBufUtil.getBytes` 拷成 `byte[]` 再反序列化（O13:243）。
- 出站：调用方/业务线程只产出 `byte[]` + 确切长度（`OutboundBody`，O17），编码器在事件循环上一次分配整帧（O7:68-93）。

**④ 一次 1KB 回显往返的拷贝清单（读代码推断，稳态，不含内核拷贝）**

| # | 线程 | 位置 | 拷贝内容 | 出处 |
|---|---|---|---|---|
| 1 | 客户端调用方 | Protostuff `toByteArray`（1KB 数组先被 `LinkedBuffer` 零拷贝挂链，再整体拷进结果数组） | 1KB | O18:446-453；S1 `WriteSink.java:56-71` |
| 2 | 客户端 IO | `RpcEncoder.encodeWhole` → `body.writeTo(frame)` | 1KB → 池化直接内存 | O7:78-84 |
| 3* | 服务端 IO | 累积：读到半帧且已有 retained slice 存活（`refCnt>1`）时 `expandCumulation`，**旧尾巴 + 整个新读块**一起拷进新缓冲区 | 最多一次读块（≤64KiB） | N1:83-116,574-591 |
| 4 | 服务端业务 | `ByteBufUtil.getBytes(arg)` 直接内存 → `byte[]` | 1KB | O13:243 |
| 5 | 服务端业务 | Protostuff `ByteArrayInput.readByteArray` | 1KB | S1 `ByteArrayInput.java:469-481` |
| 6 | 服务端业务 | 序列化返回值（同 #1） | 1KB | O13:318 |
| 7 | 服务端 IO | 编码器 `writeTo`（同 #2） | 1KB | O7:84 |
| 8* | 客户端 IO | 累积（同 #3） | ≤一次读块 | N1 |
| 9 | 客户端 IO | `ByteBufUtil.getBytes(value)` | 1KB | O2:404 |
| 10 | 客户端 IO | Protostuff 反序列化（同 #5） | 1KB | — |

确定性的载荷拷贝 **8 次**，另有 2 处条件性的累积拷贝（*）。gRPC 少掉 #1/#6 的中间 `byte[]`（直接写 ByteBuf），Dubbo 缺省多一次出站拷贝（D11:66-79）——
三者拷贝次数同一量级，**1KB 下拷贝不是主矛盾**（每次 1KB memcpy 约几十 ns 量级，对比每请求数 µs 的系统调用与唤醒）。

关于 *：`MERGE_CUMULATOR` 在"累积缓冲区为空且输入连续"时直接复用输入（N1:91-95），否则若 `refCnt()>1`（我们的 retained slice 正持有它）就走
`expandCumulation`：新分配 + 拷旧尾巴 + 拷整个新读块（N1:99-105、574-591）。闭环多在途时一次读不落在帧边界是常态，所以 #3/#8 可能比 1KB 拷贝更贵
（拷的是整个读块）。另一副作用：retained slice 让整个读块（AdaptiveRecvByteBufAllocator 最大 64KiB，N8 `AdaptiveRecvByteBufAllocator.java:37-40`）在请求排队期间一直不能释放，
排队 1000 个请求时直接内存占用可放大到 帧长的几十倍（推断）。**频率未知，需要探针**（见 T5-c）。

**⑤ 每请求对象分配（读代码推断，主要项）**

| 线程 | 分配 | 出处 |
|---|---|---|
| 客户端调用方 | `Invocation` + `LinkedHashMap`（attachments）；`DeadlineClientFilter` 每次写 2 个 attachment（4 个 String，`currentTimeMillis`）；`wireAttachments` 再复制一份 `LinkedHashMap` | O19:27-33、O2:352-360 |
| 客户端调用方 | `ArrayList` 参数表、`LinkedBuffer(256B)` + 结果 `byte[]`、`OutboundRequest`、`RpcMessage`、`execute` 的 lambda | O2:303-310、O18:446-453、O3:171-182 |
| 客户端调用方 | `CompletableFuture` 约 8 个（pending、orTimeout 内部 whenComplete、register 的 whenComplete、acquire 的 completedFuture 与 whenComplete、`onChannel.remove`、`loadStats.end`、`handle`、`gate.exit`）+ 各自的 Completion 节点 + `Signaller`；`orTimeout` 还有 `ScheduledFutureTask`/`Timeout`/`Canceller` | O5:35-38、O3:129-146,175、O2:314-338、O1:80、J1:2749-2756 |
| 客户端调用方 / IO | 在途表、`ChannelRequests` 两个 `ConcurrentHashMap` 各 1 个节点 + `Long` 装箱 | O5:27、O3:173-175 |
| 服务端 IO | attachments `HashMap` + 4 个 String、`RpcMessage`、`DispatchTask`、`Tracked` | O9:81、O12:93、O14:80-97 |
| 服务端业务 | `duplicate()`、5 个路由 String、参数 slice 列表、`ServiceKey`、**每请求拼一次 `methodKey` 字符串**、`Invocation`、`Object[]`、2 个 `byte[]`、`Result`、CF 若干、回包 `byte[]` + `OutboundValue` + `RpcMessage` | O10:150-171、O13:169-199,243,318-338 |
| 两端 IO | 池化：读缓冲区（按读摊薄）、整帧缓冲区；Recycler：slice、`WriteTask`；非池化：`ChannelPromise` + 监听器 lambda、`IdleStateHandler` 的 unvoid promise | O7:78、N4:963-995、N10:302-309 |

**⑥ 候选改法与收益/代价**
- T5-a（小成本）：`ServiceDispatcher` 方法查找改成两级 map（method → paramSig → ProviderMethod）或缓存 key，去掉每请求字符串拼接（O13:180）；
  客户端 attachments 在 `wireAttachments` 里只在重试时复制。收益：分配率小幅下降。风险低。
- T5-b（中成本，SPI 变更）：`Serializer` 增加 `deserialize(ByteBuf/ByteBuffer, Type)` 缺省方法，Protostuff 实现直接从 `nioBuffer` 读，省 #4/#9 两次拷贝与两个 `byte[]`。
  收益小（1KB），大载荷时才明显。需 Fable 裁定 SPI 变化。
- T5-c（探针，小成本）：自定义 `Cumulator` 包装 MERGE_CUMULATOR 计数"复用 / 追加 / expand"三种分支与拷贝字节数，跑一轮 closed64 看 #3/#8 的真实频率；
  若显著，再比较 COMPOSITE_CUMULATOR（N1:123 起）或"IO 线程把 body 拷成独立缓冲区、让累积缓冲区 refCnt 回到 1"两种方案。

**⑦ 验证**
- 卡：`compare-closed64-rpc-v1`；指标：JFR `jdk.ObjectAllocationSample`（按栈聚合，每请求字节数）、harness 每轮 GC 观测（两端 GC 次数/停顿）、
  探针输出的 expand 次数/请求。

---

## T6 Netty 配置基线

| 项 | gRPC-Java 1.85（grpc-netty-shaded） | Dubbo 3.3.6（netty4） | 本框架 | Netty 4.1.136 缺省 |
|---|---|---|---|---|
| 传输 | Linux 有 epoll 就用 epoll（G9:117-137） | NIO；`netty.epoll.enable=true` 且 Linux 才 epoll（D8 `NettyEventLoopFactory.java:52-72`） | NIO（O3:78、O11:82-83） | — |
| 服务端 worker 线程 | 0 → Netty 缺省（G9:91-93,124-127） | `iothreads` 缺省 min(核数+1, 32)（D7:118、D8 `NettyServer.java:157-160`） | 0 → Netty 缺省（O11:83） | 2×核数（N6:40-41） |
| 客户端 IO 线程 | 同上，进程共享 | 进程共享一组，min(核数+1, 32)，不可配（D8 `NettyClient.java:71-72`） | 每 RpcClient 一组，`ioThreads` 可配（O3:78） | 2×核数 |
| boss | 1 | 1 | 1（O11:82） | — |
| 分配器 | 自建 Pooled，maxOrder=8，可关（G9:139-189） | `PooledByteBufAllocator.DEFAULT`（D8 `NettyServer.java:175`、`NettyClient.java:116`） | `ByteBufAllocator.DEFAULT`（未设） | pooled（N9 `ByteBufUtil.java:77-81`），preferDirect |
| `useCacheForAllThreads` | 缺省 false | 缺省 false | 缺省 false（P3 据此只在事件循环分配） | false（N9 `PooledByteBufAllocator.java:149-150`） |
| 在哪个线程分配出站缓冲区 | 调用方/应用线程（G10、G11） | 调用方/业务线程（`encode.in.io=false`，D9:200-205） | **只在事件循环**（O17、O7:78） | — |
| TCP_NODELAY | 未显式设，取 Netty 缺省 true | 显式 true（D8 `NettyServer.java:173`、`NettyClient.java:115`） | 未显式设，取 Netty 缺省 true | true（非 Android，N7 `DefaultSocketChannelConfig.java:51-53`、`PlatformDependent.java:90`） |
| SO_KEEPALIVE | true（G13 `NettyClientTransport.java:274`、`NettyServer.java:234`） | true | true（O3:82、O11:93） | false |
| SO_SNDBUF / SO_RCVBUF | 未设 | 未设 | 未设 | OS 缺省 |
| WRITE_BUFFER_WATER_MARK | 未设；靠 HTTP/2 流控（窗口 1MiB，G13 `NettyChannelBuilder.java:87`）+ `isReady` | 未设 | 未设，且**从不检查 `isWritable`**（缺陷 F1） | 32KiB / 64KiB（N8 `WriteBufferWaterMark.java:38-39`） |
| 接收缓冲 | Adaptive | Adaptive | Adaptive | 64 / 2048 / 65536（N8） |
| SO_BACKLOG | 未设 | 未设 | 128（O11:92） | `NetUtil.SOMAXCONN`（N8 `DefaultServerSocketChannelConfig.java:45`） |
| 写出批量 | WriteQueue（G6） | BatchExecutorQueue（D10） | 无 | — |
| 业务执行器缺省 | 缓存池，SynchronousQueue（G5:557-563） | fixed 200，queues 0 = SynchronousQueue（D6） | fixed 2×核数，ABQ 1000（O15:50-52） | — |
| 响应反序列化 | 调用方线程 | 调用方线程 | 客户端 IO 线程 | — |
| 每请求超时定时器 | 调用所在 transport 的 scheduledExecutor（推断：Netty 事件循环） | 全局 `HashedWheelTimer`，30ms tick（D15:65-66,107-109） | JDK `CompletableFuture.orTimeout` → 进程级单线程 `ScheduledThreadPoolExecutor`，取消即出队（O5:38、J1:2749-2756,2866-2887） | — |

**差异对数字的可能解释**：配置项本身（TCP_NODELAY、缓冲区）三方基本一致，不构成差距；可能有影响的两项是 epoll（P1 已在对比中统一为 NIO，所以不解释现有差距）与
**超时定时器**：我们每次调用在一个进程级单线程 STPE 上 `schedule` + 完成时 `cancel`（remove-on-cancel，堆删除 O(log n)），两次取同一把 `ReentrantLock`，
83k 调用/s 即 ~17 万次/s 抢同一把锁（调用方线程与 IO 线程都参与）。工作区里进行中的 `RpcDeadlineTimer` 改动保持同一结构（单线程 STPE + remove-on-cancel），
结论不变。Dubbo 用时间轮（入队 MPSC、取消 O(1)），gRPC 用 transport 自己的调度器（推断，未逐行核实）。

**候选改法**
- T6-a（小成本）：超时改用 Netty `HashedWheelTimer`（tick 1~10ms）或连接所在事件循环的 `schedule`。风险：tick 粒度让超时最多晚一个 tick；
  deadline 语义测试可能要放宽到 tick 级；时间轮在途量大时的内存。先用 JFR 看 STPE 锁的 `jdk.ThreadPark`/`jdk.JavaMonitorEnter` 是否可见再决定。
- T6-b（中成本，需批准依赖）：可选 epoll 传输（`netty-transport-native-epoll`，linux-x86_64 classifier），配置项 `transport: nio|epoll|auto`。
  收益未知（推断个位数到 15%，无本仓库数据）；对比卡须写明传输。
- T6-c（小成本，健壮性）：写背压，见缺陷 F1。

**验证**：`compare-closed64-rpc-v1`、`compare-open10k-rpc-v1`；JFR 锁事件（`jdk.ThreadPark` 按 `parkedClass=ScheduledThreadPoolExecutor$DelayedWorkQueue` 聚合）；epoll 对照须同卡两次运行仅改传输。

---

## T7 JDK 21 虚拟线程上的立场

| | gRPC-Java 1.85 | Dubbo 3.3.6 | 本框架 |
|---|---|---|---|
| 内建支持 | 无专门代码（core/netty/api 中 grep "virtual" 只命中 virtual hosting）；靠 `ServerBuilder.executor(...)` / `ManagedChannelBuilder.executor(...)` 传入任意执行器（G12） | `dubbo-plugin-loom` 提供 `threadpool=virtual`：`newThreadPerTaskExecutor(ofVirtual)`，**无并发上限**（D16:33-41） | `ExecutorConfig.virtualThreads(maxConcurrent)`，`Semaphore` 封顶，拿不到许可立即 SERVER_OVERLOADED（O14:67-69,84-87） |
| 阻塞等待对 VT 是否友好 | ThreadlessExecutor 用 `LockSupport.park`，可卸载（G1:810-823） | 同（`parkNanos`，D1:56-80） | `CompletableFuture.join` → `managedBlock` → park，可卸载（J1:1877-1905） |
| 调用方/业务线程是否碰池化分配器 | **是**：`MessageFramer` 在应用线程按确切长度分配（G10:46-48），无线程缓存时直取 arena 锁（N9 `PoolArena.java:74` 起为 `ReentrantLock`）；按确切长度分配不走 `reallocate`，P3 记录的"synchronized 内取锁钉住载体"路径大概率不触发（推断） | **是**：`encode.in.io=false` 时业务/调用方线程 `channel.alloc().buffer()` 后随写扩容（D9:200-205）——正是 P3 第 32 条的触发形态（扩容 → `reallocate`），VT 调用方大量并发时有同类卡死风险（推断，未实测） | **否**：只在事件循环分配（O17、P3） |
| 已知坑（文档） | 官方无 VT 指南；官方 Java 建议是"按负载限线程数的自定义执行器"（G14） | 官方线程模型文档未提虚拟线程（D17） | P3（池化分配 + 钉住）、`synchronized` 钉住（O15 类注释） |

**解释与建议**：在 VT 立场上我们比两者更保守也更安全（有界、分配只在事件循环），这一点应当保持，并作为对比报告里的"非性能维度"写出。
不建议跟随 Dubbo 做无界 VT 池。一个可做的小事：在对比文档里标注"Dubbo 缺省 `encode.in.io=false` + VT 调用方"的风险是推断，必要时用 P3 的复现卡（万级 VT 开环）跑一次 Dubbo 验证。

**验证**：`compare-open10k-*` 卡把发压端改为 per-request VT（`--open-workers per-request`），观察是否出现 P3 描述的卡死（`-Djdk.tracePinnedThreads=full`）。

---

## 候选优化清单（按"预期收益 ÷ 改动成本"排序）

收益均为推断量级，须实测。"每连接吞吐" = 吞吐 / 实际连接数。

| # | 改法 | 触及的类 | 主要影响指标 | 风险 | 验证卡 |
|---|---|---|---|---|---|
| 1 | 两端 pipeline 首位加 `FlushConsolidationHandler(256, true)` | `NettyClient`（initChannel）、`NettyServer`（initChannel） | 每连接吞吐↑、吞吐↑；p50 持平 | 低：官方组件，无定时器；低并发时 flush 多排一轮任务队列 | `compare-closed64-rpc-v1`、`compare-open10k-rpc-v1`、`sweep-c4/c8-closed128-rpc-v1` |
| 2 | 每连接写队列（MPSC + CAS 单次 execute + 批末一次 flush），客户端请求与服务端回包都走它 | `NettyClient.write/writeOnEventLoop`、`ServerHandler.reply`、新内部类 | 每连接吞吐↑、吞吐↑、CPU/请求↓ | 中低：逐条保留"已超时不写""连接已断即失败"、关闭时清空；需新测试 | 同上 + 系统调用计数 |
| 3 | 服务端 `ExecutorConfig.direct()`（opt-in，按服务开启更佳），IO 线程执行 + 读完成时 flush | `ExecutorConfig`、`BusinessExecutor`、`ServerHandler`、`ServiceOptions` | 吞吐↑↑、p50↓（非缺省配置） | 中：阻塞即卡住同一事件循环所有连接；在途/拒绝语义需重定义；只能 opt-in | `compare-closed64-rpc-v1`（服务端 direct，结果另目录、标非缺省） |
| 4 | 超时定时器改时间轮或事件循环 schedule（先 JFR 确认锁可见） | `PendingRequests` | 吞吐小幅↑、p99 可能改善 | 中低：超时精度变为 tick 级；与进行中的 RpcDeadlineTimer 改动合并 | `compare-closed64-rpc-v1` + JFR 锁事件 |
| 5 | 合并客户端完成依赖链（5 个 whenComplete/handle → 1 个回调）；服务端 methodKey 免拼串；attachments 只在重试时复制 | `PendingRequests`、`NettyClient`、`RpcClient`、`ReferenceProxy`、`ServiceDispatcher` | 分配率↓、GC↓、吞吐小幅↑ | 低~中：完成顺序语义（gate.exit 必须在最后）要有测试 | `compare-closed64-rpc-v1` + JFR 分配采样 |
| 6 | 同步路径延迟解码（响应反序列化挪到调用方线程，类 ThreadlessExecutor 效果） | `RpcClient.attempt/decode`、`ReferenceProxy`、`Result`（惰性） | 客户端 IO 线程 CPU↓、客户端侧受限时吞吐↑ | 中：Filter 看到的 Result 语义、异常路径 | `sweep-c1-closed64-rpc-v1`（新卡）、`compare-closed64-rpc-v1` |
| 7 | 累积拷贝探针（自定义 Cumulator 计数），视结果决定是否换策略 | 探针代码（不入主路径） | 先得数据：expand 次数/请求、拷贝字节/请求 | 低（只读探针） | `compare-closed64-rpc-v1` 一轮 |
| 8 | 缺省 `connectionsPerNode` 2→4（在 #1/#2 之后重扫再裁定） | `RpcClientConfig` 常量 | 吞吐↑（直到压测端受限） | 低实现风险；服务端连接数 ×2，掩盖每连接成本，改变"开箱即用"口径 | `sweep-*` 全套 |
| 9 | 可选 epoll 传输（`nio|epoll|auto`） | `NettyClient`、`NettyServer`、配置、依赖 | 吞吐↑（幅度未知） | 中：新增原生依赖（需批准）、平台差异、对比口径 | 同卡 NIO/epoll 两次 |
| 10 | `Serializer` 增加 ByteBuf/ByteBuffer 入参的反序列化（省两次 getBytes） | `Serializer` SPI、Protostuff/Fastjson2 实现、`ServiceDispatcher`、`RpcClient` | 大载荷时 CPU↓；1KB 下很小 | 中：SPI 变更 | 需新增大载荷卡（如 64KB） |

**建议的探针与新卡（为排序提供数据，均需 Fable 批准后再建）**

| 名称 | 内容 | 回答的问题 | 服务的候选项 |
|---|---|---|---|
| `sweep-c1-closed64-rpc-v1`（新卡） | 与 `compare-closed64-rpc-v1` 只差 `target.connections: 1` | 本框架真实的"单连接上限"，与 gRPC direct 单 channel 65k、gRPC/Dubbo 缺省单连接直接对位 | #1 #2 #3 #6 |
| 系统调用计数 | closed64 正式轮中段对两端 JVM `strace -c -f -p <pid>` 10s（或 `perf stat` 若 WSL2 内核支持） | 每请求 `write`/`writev`/`futex`/`epoll_wait`/eventfd 写次数；改动前后对比 | #1 #2 #4 |
| JFR 锁与 park | `jdk.ThreadPark`、`jdk.JavaMonitorEnter` 按类聚合 | 全局定时器锁、ABQ 锁、FJP 唤醒各占多少 | #4 #2 |
| JFR 分配采样 | `jdk.ObjectAllocationSample` 按栈聚合 | 每请求分配字节、热点分配栈（验证 T5 ⑤ 的清单） | #5 #10 |
| 累积探针 | 包装 `MERGE_CUMULATOR` 计数三种分支与拷贝字节 | T5 ④ 的 #3/#8 是否显著 | #7 |

**前 3 的理由**
1. **#1 FlushConsolidationHandler**：成本最低（两处各一行），直接打在本文认定的主矛盾——两端"每请求一次 flush/`write`"——上；gRPC、Dubbo 两个独立实现都收敛到"每批一次 flush"，
   P2 又已证明唤醒/系统调用是固定开销的大头。做完它就能量化"flush 占多少"，为 #2 是否值得做提供直接数据。
2. **#2 每连接写队列**：在 #1 之上再去掉"每请求一次跨线程任务 + 可能的 eventfd 唤醒"，这是 gRPC `WriteQueue` 与 Dubbo `BatchExecutorQueue` 的共同设计，
   不违背我们任何原则（编码与分配仍在事件循环上，调用方不碰分配器，过载/超时检查逐条保留）。改动集中在两个方法，可控。
   （后记：任务 15 实测否定，见 [docs/probes/write-queue-and-context-switches.md](../probes/write-queue-and-context-switches.md)。）
3. **#3 服务端 direct（opt-in）**：上限收益最大的单项（gRPC 缺省→direct 为 4.4×，且 gRPC direct 单连接已高于我们每连接），但它牺牲隔离性，
   只能作为显式选项 + 文档警示，不改变缺省数字；所以排在两个"缺省就受益"的改动之后。它同时给对比报告提供一个与 gRPC direct 同口径的上界对照点。

## 不建议做

| 项 | 原因 |
|---|---|
| 在调用方/业务线程直接写池化 ByteBuf 以省一次中间 `byte[]`（gRPC `MessageFramer`、Dubbo `encode.in.io=false` 的做法） | 与 P3 第 32 条的结论冲突：VT 无线程缓存、争 arena 锁、扩容钉住载体可致整客户端卡死；1KB 下省一次 memcpy 的收益远小于风险 |
| 把业务执行改为缺省在 IO 线程（Dubbo `direct` / gRPC `directExecutor` 当缺省） | 违背"默认不牺牲隔离性"；任何阻塞业务都会卡住同一事件循环上的所有连接（G12 官方警告） |
| 业务池换成 `SynchronousQueue` / 缓存池（gRPC、Dubbo 缺省） | 对照数据已显示更差（14.7k / 13.8k，IO 线程 91% 在 offer/unpark），且缓存池线程数无界 |
| 无上限的虚拟线程执行器（Dubbo `threadpool=virtual`） | 违背 ExecutorConfig "必须有界"的原则（O15 类注释），过载时不拒绝只排队 |
| IO 线程忙轮询作为库缺省 | P2 证明有效，但每个 IO 线程占满一个核，只适合压测端，不适合库缺省 |
| 改用 HTTP/2 多路复用 | 单连接仍单事件循环（T4 表），HTTP/2 帧与 HPACK 每请求成本更高，不解决每连接上限 |
| 调用方先自旋再 park | VT 自旋占载体，闭环下抢压测端/服务端 CPU；收益只在极低延迟场景 |
| 去掉 `writeOnEventLoop` 的"已超时不写出"检查以省一次 map 查询 | 这是过载时防止内存越堆越高的保护（O3:190-204 注释），省下的成本可忽略 |
| 不经探针直接换 COMPOSITE_CUMULATOR | 组合缓冲区索引更慢（N1 注释），收益取决于 #7 探针数据 |
| 自适应连接数 | 与 ConnectionManager 的排空/重连逻辑耦合、复杂度高，收益可被 #8 覆盖 |

---

## 发现的明确缺陷（区别于"可优化"）

| ID | 缺陷 | 出处 | 后果 | 建议（待 Fable 裁定） |
|---|---|---|---|---|
| F1 | **无出站背压**：两端都不检查 `Channel.isWritable()`，也不设 `WRITE_BUFFER_WATER_MARK`；服务端在途计数在"交给 Netty"时就 -1（O12:31） | O3:194-211、O12:153-159、O12:31 | 一个只发请求不读响应的客户端（或网络拥塞）会让服务端回包在 `ChannelOutboundBuffer`（直接内存）里无界堆积，闸门和执行器上限都管不到；客户端同理（被限于调用方并发×超时，较轻） | 服务端：不可写时停止 autoRead（`channelWritabilityChanged` 里切换），恢复可写再开；或按连接限制"已写未冲刷字节"并对新请求回 SERVER_OVERLOADED |
| F2 | 服务端回包写失败静默：`ctx.writeAndFlush(reply)` 无监听器 | O12:158 | 编码失败（前置检查之外的情况）或连接已断时无日志，客户端只能等超时或连接关闭 | 加一个记 debug/warn 的监听器（与客户端 O3:205-210 对称） |
| F3 | 异步方法的用户回调在客户端 IO 线程上执行（已在 P4 与 getting-started HEAD:609 文档化），但**在回调里发起同步 RPC** 没有任何防护 | O1:82-95、O2:362 | `join()` 阻塞事件循环；若嵌套调用的连接恰在同一事件循环上，响应永远读不到，只能等 20s 超时，期间该事件循环上的所有连接停摆 | 同步代理入口检测"当前线程是本客户端的事件循环"时直接抛 `IllegalStateException`（成本一次 `inEventLoop` 判断） |

另：retained slice 持有整个读块导致的直接内存放大（T5 ④）属设计取舍而非缺陷，列入存疑待探针。

## 出处不足或存疑的结论

| 结论 | 状态 |
|---|---|
| 所有"预期收益"百分比（#1 +10~30%、#2 再 +10~20%、#3 1.3~2×、epoll 个位数~15%） | **推断**，无本仓库数据，须按验证卡实测 |
| 交接次数"本框架 4 次"中每次是否真的伴随系统调用（eventfd 写、futex 唤醒）取决于对方线程当时是否在睡 | 读代码推断（N5:859-863、J2:736-760），需 `strace -c`/`perf` 计数确认 |
| 累积拷贝 #3/#8 的频率与开销 | 机制有出处（N1），频率未测，需 T5-c 探针 |
| gRPC 每请求超时定时器挂在 transport 的调度器（事件循环）上 | 未逐行核实 `ClientCallImpl` 的 deadline 调度路径，标推断 |
| gRPC 缺省执行器一次 unary 产生 1~3 次池交接 | 由 `SerializingExecutor` 调度条件推出（G3、G4），未计数 |
| Dubbo 2.7.5 "QPS 提升近 30%" | 来自检索摘要引用的官方说法，未找到原文逐字核验，不作数 |
| Dubbo `encode.in.io=false` + 虚拟线程调用方会触发 P3 第 32 条同类卡死 | 推断（形态一致：调用方线程分配后随写扩容），未实测 |
| SOFA-Bolt 只看了线程模型（批量投递、`processInIOThread`），连接模型未查；Bolt 用 master 而非某个 SOFARPC 发布版的依赖版本 | 出处不足 |
| JDK 行号取自 jdk21u master，非压测机确切构建 | 行号可能偏移，逻辑一致 |
| 本框架行号以 HEAD `b5058cb` 为准；工作区里 `PendingRequests`（RpcDeadlineTimer）、`ReferenceProxy`（syncWaitGrace）等进行中的改动未纳入，T6 定时器结论已核对其结构不变 | — |

## 未完成主题

无。T1~T7 均已覆盖；T7 中 gRPC 官方对虚拟线程没有可引用的立场文档，只能从源码与通用建议推断。
