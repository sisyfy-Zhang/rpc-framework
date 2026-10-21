# 踩坑档案

按现象 → 根因 → 修法 → 教训记录，只收有复用价值的；新条目追加在末尾。

## 1. Maven 默认 JDK 与仓库编译目标不一致

**现象**：本机 `JAVA_HOME` 指向 JDK 11（`D:\CS\JAVA\jdk-11`，`mvn -v` 显示 `Java version: 11.0.25`），直接运行 `mvn` 编译失败：

```
[INFO] Compiling 5 source files with javac [debug release 17] to target\classes
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project rpc-api: Fatal error compiling: 错误: 不支持发行版本 17 -> [Help 1]
```

**根因**：根 `pom.xml` 设置 `maven.compiler.release=17`，Maven 用 `JAVA_HOME` 指向的 JDK 运行 javac，JDK 11 的 javac 不认识 release 17。

**修法**：把 `JAVA_HOME` 切到 JDK 21（用户动作）。不想改系统环境变量时，按命令临时指定：`JAVA_HOME='D:\CS\JAVA\jdk-21' mvn ...`。

**教训**：把构建所需 JDK 写进 README 环境要求并在 CI 固定版本。

## 2. Protostuff 对 JDK 集合类调 RuntimeSchema.getSchema 静默损坏并污染全局 IdStrategy

**现象**（见 [docs/probes/protostuff-1.8.0.md](probes/protostuff-1.8.0.md) P2a–P2d）：

- `ProtobufSerializer` 直接序列化顶层 `ArrayList<User>`：不抛异常，只产出 2 字节；解码出的列表 `size()=2`，`get(0)` 抛 `ArrayIndexOutOfBoundsException`，`toString`/`equals` 抛 `ConcurrentModificationException`。
- 顶层 `HashMap<String,User>`：不抛异常，解码为空 Map。
- 更隐蔽的是副作用：对某个 IdStrategy 调过 `getSchema(ArrayList/HashMap)` 之后，同一策略下其他类的 `Object` 字段里的 `ArrayList`、`Map` 值里的 `List` 也开始损坏，`Object` 字段里的 `HashMap` 变空。全局 `RuntimeEnv.ID_STRATEGY` 上同样复现（P2d），即一次错误调用会影响同一 JVM 之后所有走全局策略的序列化。声明为 `List<User>` 的字段不受影响。

**根因**：`RuntimeSchema.getSchema(Class)` 把传入的类一律当 POJO 处理，按非 transient 字段生成 schema：`ArrayList` 只得到 `[1=size]`，`HashMap` 只得到 `[1=threshold 2=loadFactor]`，真正存数据的字段是 transient 的，被跳过。生成的 POJO schema 登记在该 IdStrategy 里，之后动态类型字段（`Object`、Map 值等）遇到 `ArrayList`/`HashMap` 时改用这份 POJO schema，而不是 Protostuff 自带的集合编码。`ProtobufSerializer.schemaFor` 对任意 `Class` 都调 `RuntimeSchema.getSchema`，所以顶层传集合就会触发。

**修法**（已定方案，截至本条记录时尚未落地到 `ProtobufSerializer`）：

- 按声明的 `Type`（含泛型参数）构造 Collection / Map 对应的 schema，而不是对集合运行时类调 `RuntimeSchema.getSchema`。
- 加守卫：禁止对 JDK 集合类（`Collection`/`Map` 的实现）调用 `RuntimeSchema.getSchema`，命中即抛异常。

**教训**：

- 不抛异常的损坏只有特征化测试能提前抓到；对第三方库在边界输入上的行为，要用探针记录实际结果，而不是凭文档或直觉假设。
- 会改全局状态的测试要隔离运行：`ProtostuffBehaviorProbeTest` 标 `@Tag("probe")`，默认构建排除，只用 `-Pprobe` 单独跑。

## 3. Protostuff 1.8.0 不支持 record

**现象**（见 [docs/probes/protostuff-1.8.0.md](probes/protostuff-1.8.0.md) P5）：

- 默认 Unsafe 模式，`RuntimeSchema.getSchema` 阶段即失败：

  ```
  java.lang.UnsupportedOperationException: can't get field offset on a record class: private final int com.feynman.rpc.serialize.protobuf.ProtostuffBehaviorProbeTest$Point.x
  ```

- 反射模式（`-Dprotostuff.runtime.use_sun_misc_unsafe=false`），序列化成功，`mergeFrom` 阶段失败：

  ```
  java.lang.RuntimeException: java.lang.IllegalAccessException: Can not set final int field com.feynman.rpc.serialize.protobuf.ProtostuffBehaviorProbeTest$Point.x to (int)3
  ```

**根因**：Protostuff 1.8.0 的运行时 schema 先创建实例、再逐字段写入来还原对象。record 的字段是 final 的，JDK 不允许通过 Unsafe 取 record 字段偏移，也不允许反射修改 record 的 final 字段，所以两种模式都无法还原 record。

**修法**：经序列化器传输的业务 DTO（RPC 参数、返回值）用普通类（可变字段 + 无参构造）；框架内部类型不走序列化器，可以用 record。

**教训**：语言新特性能否用于线上传输取决于序列化库的支持，引入前先用探针在两种运行模式下确认；“编译通过”不代表“能序列化”。

## 4. Netty 往"正在建连"的 channel 上写不会被缓冲，而是直接失败

**现象**：旧 `ConnectionPool.next()` 在没有已激活连接时会返回一条仍在建连的 channel，注释认为"Netty 会先缓冲写入，等连接建立后统一 flush"。用探针验证（Netty 4.1.136，NIO）：

- 连 `127.0.0.1`：`isActive()` 在写入时为 false，但写入成功——本机回环的 `connect()` 在事件循环里几乎同步完成，写任务排在它之后执行，掩盖了问题。
- 连一个不可达地址（`10.255.255.1:80`，连接真正处于进行中）：

  ```
  active at write time = false
  write success = false, cause = java.nio.channels.NotYetConnectedException
  ```

**根因**：`AbstractChannel.AbstractUnsafe.flush0()` 在 `!isActive()` 且 channel 仍 open 时，对已 flush 的待写消息直接 `failFlushed(new NotYetConnectedException())`，并不会挂起到连上为止。

**修法**：`ConnectionPool.acquire()` 返回 `CompletableFuture<Channel>`：有已激活连接就直接给；只有建连中的连接时，在它的 connect future 上等完成再写；全都不可用才失败。发送失败另外挂 write listener 立刻让请求失败，而不是挂到超时。

**教训**：本机回环测试会掩盖"连接尚未建立"一类时序问题；涉及建连时序的假设要用真正会处于进行中状态的连接（不可达地址、toxiproxy 延迟）去验证，而不是看注释或在 localhost 上跑通就算数。

## 5. `mvn -rf :模块` 续跑时兄弟模块会从本地仓库解析旧快照

**现象**：全量构建在 rpc-spring-boot-starter 失败后，按提示用 `mvn verify -rf :rpc-spring-boot-starter` 续跑，starter 的测试编译报大量"找不到符号"（`com.feynman.rpc.core.ServiceKey`、`RegistryFactory` 等刚在 rpc-core 里新增的类）。

**根因**：`-rf` 只把从该模块开始的模块放进 reactor，前面的 rpc-core 不在本次 reactor 里，于是按普通依赖从 `~/.m2` 解析 `rpc-core:0.0.1-SNAPSHOT`——那是很早以前 install 过的旧版本，没有新 API。

**修法**：改动跨模块 API 后直接从根目录全量 `mvn clean verify`；只想跑部分模块用 `-pl <模块> -am`，让上游模块一起进 reactor。

**教训**：`-rf` 只适用于"上游模块本次没改过"的场景；SNAPSHOT 依赖从本地仓库解析时不会提示它是旧的，报错看起来像代码问题而不是构建问题。

## 6. Protostuff 1.8.0 的 `DefaultIdStrategy` 是 final，白名单策略无法通过继承实现

**现象**：按设计要"继承 DefaultIdStrategy、在按名加载前校验包前缀"，但 `io.protostuff.runtime.DefaultIdStrategy` 声明为 `public final class`，无法继承。退一步做委托（自写 IdStrategy 子类，把调用转给一个内部 DefaultIdStrategy）也不行：

- IdStrategy 的抽象方法都是 `protected`，跨包只能在"自己类型的实例"上调用，不能转调另一个对象的；
- 更根本的是，DefaultIdStrategy 为多态 POJO 建的 `Lazy` schema 持有的是**内部那个**策略，嵌套的 Object / 接口字段再解析类名时走的是内部策略，白名单被绕过。

**根因**：策略对象既负责"按名找类"，也是它创建的所有 schema 的回指；白名单必须由 schema 实际持有的那个策略来执行。

**修法**：在 rpc-serialize-protobuf 里放一个同名包 `io.protostuff.runtime` 下的 `AllowlistIdStrategy`，逐行沿用 DefaultIdStrategy 的实现（Apache 2.0，文件头注明来源），只改两处：所有 `RuntimeEnv.loadClass(className)` 前先过白名单；`getSchemaWrapper(Class, create)` 拒绝为 Collection / Map 实现类建 POJO schema（第 2 条的守卫）。同名包是为了用到包级可见的 `EnumIO.newEnumIO`、`RuntimeSchema.resolvePipeSchema` 等。

**教训**：设计里写"继承某第三方类"之前先确认它不是 final、扩展点是否真的可达；"包一层委托"对会回指自身的对象（策略、工厂、上下文）往往不成立。

## 7. Protostuff 默认标志下，POJO 里的空集合字段解码为 null

**现象**：`Item{tags = []}` 经 Protostuff 往返后 `tags == null`；fastjson2 则保持 `[]`。同一个 A0-4 参数化用例只在 protobuf 分支失败：

```
expected: <{odd=[Item{1,a,[]}, Item{3,c,null}], ...}> but was: <{..., odd=[Item{1,a,null}, Item{3,c,null}]}>
```

**根因**：默认标志下集合字段走 `RuntimeRepeatedFieldFactory`，元素按 protobuf repeated 字段平铺写入，空集合一个字节都不写，读端无从区分"空"与"null"；字段里的 null 元素也默认被丢弃。`RuntimeEnv.COLLECTION_SCHEMA_ON_REPEATED_FIELDS` / `PRESERVE_NULL_ELEMENTS` 可以改变这一点，但它们是 JVM 级系统属性（本仓库禁止 System.setProperty）。

**修法**：IdStrategy 的构造参数 `flags` 按实例生效。ProtobufSerializer 用 `DEFAULT_FLAGS | COLLECTION_SCHEMA_ON_REPEATED_FIELDS | PRESERVE_NULL_ELEMENTS` 构造自己的策略：集合字段编码成嵌套消息（空集合 = 长度 0 的嵌套消息），null 元素按位置保留。两端都是本序列化器，线上格式变化不影响互通。

**教训**："空 vs null"要在每一层（信封、根对象、字段、元素）分别验证；第三方库的全局开关先找有没有按实例的等价物，而不是去改系统属性。

## 8. Netty 引用计数断言：slice 的 refCnt 是父缓冲区的，EMPTY_BUFFER 恒为 1

**现象**：编解码模糊测试里，"释放每条解出的消息后断言 `message.refCnt() == 0`"在部分随机输入上失败（refCnt 为 1），但并没有泄漏；另一个用例对空输入断言"输入缓冲区 refCnt 为 0"也失败。

**根因**：

- `readRetainedSlice` 得到的 slice 与父缓冲区共享引用计数，`slice.refCnt()` 返回的是父缓冲区的计数。同一输入切出两帧时，第一条消息释放后父缓冲区仍被第二条持有，计数自然不为 0。
- 长度为 0 的 slice、`Unpooled.wrappedBuffer(new byte[0])` 返回的都是共享的 `EMPTY_BUFFER`，`release()` 是空操作，`refCnt()` 恒为 1。

**修法**：泄漏判据改为"全部消息释放、channel finish 之后，**原始输入缓冲区**的 refCnt 为 0"（空输入除外）；另用 `-Dio.netty.leakDetection.level=paranoid` 跑集成测试，并先做一次故意泄漏的阳性对照，确认这套日志配置确实能打印 `LEAK:`。

**教训**：引用计数断言要断在"所有权的根"上，而不是派生视图上；泄漏检测工具要先证明它在当前配置下能报警，"零告警"才有意义。

## 9. surefire 默认只收 `*Test` / `Test*` / `*Tests` / `*TestCase`：jqwik 的 `*Properties` 类会被静默跳过

**现象**（编写时核对 surefire 配置发现，未等到它造成漏测）：jqwik 属性测试类最初按社区习惯命名为 `CodecFuzzProperties`。这样的类不会出现在 surefire 的测试计划里，构建照样 BUILD SUCCESS，属性测试等于没跑。

**根因**：maven-surefire 的默认 includes 是 `**/Test*.java`、`**/*Test.java`、`**/*Tests.java`、`**/*TestCase.java`；不匹配的类不进测试计划，也不报任何提示。jqwik 引擎本身是正常注册的。

**修法**：属性测试类同样以 `Test` 结尾（`CodecFuzzTest`）；另外 jqwik 默认会在工作目录写 `.jqwik-database`，在 `src/test/resources/junit-platform.properties` 里设 `jqwik.database = target/jqwik-database`，免得模块根目录多出未跟踪文件。

**教训**：新增测试类型后，用测试报告里的类名和用例数确认它真的跑了，而不是只看 BUILD SUCCESS。

## 10. curator-test 5.x 的 `KillSession.kill` 只在客户端注入过期，服务端的旧会话和临时节点还活着

**现象**：A0-3 用 `KillSession.kill(client.getZookeeperClient().getZooKeeper())` 模拟会话过期。探针（会话超时 20s）显示：kill 之后客户端立刻换了新会话（`0x...0000` → `0x...0002`），但另一个客户端看到的临时节点在之后至少 7 秒里一直存在，`ephemeralOwner` 仍是旧会话 `0x...0000`。如果断言写成"10 秒内节点重新出现"，即使完全没有重注册逻辑也会通过（节点根本没消失过）。

**根因**：curator-test 5.8 的 `KillSession.kill(ZooKeeper)` 只调用 `zk.getTestable().injectSessionExpiration()`，让客户端在本地处理一个 Expired 事件；服务端并不知道，旧会话要等到会话超时才过期、临时节点才删除。这和生产里"服务端先判定过期、客户端随后收到 Expired"的顺序相反。

**修法**：
- 断言改为"节点的 `ephemeralOwner` 变成了提供者当前的新会话 ID"，并做了变异验证：关掉重注册后该用例 10 秒超时失败。
- 重注册必须处理 `NodeExistsException`：节点属于当前会话就只在数据不同时 `setData`；属于别的会话（旧会话尚未在服务端过期）就删掉重建，否则旧会话到期时会把节点一起带走。

**教训**：测试工具模拟出来的故障，和真实故障的时序不一定一样。先用探针看清模拟器实际做了什么，再决定断言什么；"节点存在"这类可能本来就成立的状态不能证明恢复逻辑生效了。

## 11. Curator 5.8 的 runSafe 执行器在 `CuratorFramework.close()` 后不会关闭，每个客户端泄漏一个线程

**现象**：换成 `CuratorCache` 之后，"close 后 5 秒内 `rpc-registry-zk-*` 线程全部结束"的契约用例失败，残留一个 `rpc-registry-zk-{id}-4`，停在 `ThreadPoolExecutor.getTask → LinkedBlockingQueue.take`。旧的一次性 watcher 实现没有这个问题。

**根因**：`CuratorFrameworkImpl.makeRunSafeService` 在没有显式配置时，用 builder 的 threadFactory 新建 `Executors.newSingleThreadExecutor`，而 `close()` 从不关闭它。`CuratorCache` 的监听回调经 `client.runSafe(...)` 投递，第一次使用时这个线程就被创建出来，之后一直不退出。

**修法**：自己创建 runSafe 执行器，经 `CuratorFrameworkFactory.builder().runSafeService(executor)` 交给 Curator，在 `client.close()` 之后自己 `shutdown` 并 `awaitTermination`。

**教训**：库的 `close()` 不一定回收它创建的所有资源。框架线程统一加前缀，并在每次 close 后断言"带前缀的线程在 N 秒内为零"，这种泄漏在第一次引入新组件时就能发现。

## 12. ZooKeeper 客户端的 SendThread / EventThread 名字取自"构造 ZooKeeper 的那个线程"

**现象**：给 Curator 配了 `threadFactory("rpc-registry-zk-...")` 之后，线程快照里仍有 `main-SendThread(127.0.0.1:2181)`、`main-EventThread`（在 surefire 里是测试线程名），看不出属于哪个 Registry，按前缀做的线程泄漏断言也覆盖不到它们。

**根因**：`ClientCnxn.makeThreadName(suffix)` = `Thread.currentThread().getName()`（去掉 `-EventThread`）+ 后缀，取的是调用 `new ZooKeeper(...)` 的线程。Curator 的 threadFactory 只管 Curator 自己的线程。

**修法**：自定义 `ZookeeperFactory`（`NamedZookeeperFactory`），每次在一个名为 `rpc-registry-zk-{instanceId}` 的短命线程上构造 ZooKeeper，结果是 `rpc-registry-zk-{id}-SendThread(...)` / `rpc-registry-zk-{id}-EventThread`。会话过期后 Curator 重建 ZooKeeper 也走这个工厂，名字保持一致。不采用"临时改调用方线程名"的写法，因为那会改到不属于自己的线程。

**教训**：要让线程名可追踪，得先查清每个线程是在哪里、以谁的名义创建的；只配 threadFactory 往往漏掉下层库自己起的线程。

## 13. `@BeforeAll` 里 assumption 失败时，surefire 报告为"0 个测试"而不是 skipped

**现象**：Nacos 契约测试在 `@BeforeAll`（PER_CLASS 生命周期）里用 `assumeTrue(Docker 可用)` 门控。Docker 不可用时，构建输出 `Tests run: 0, Failures: 0, Errors: 0, Skipped: 0 -- in NacosRegistryContractTest`，看起来像这个类没有测试，而不是被跳过了。

**根因**：类级回调里抛出 `TestAbortedException` 会让整个容器 aborted，里面的测试方法不会被逐个报告，surefire 3.2.5 统计为 0。

**修法**：契约基类在 `@BeforeAll` 里捕获 `TestAbortedException` 并记下原因，在 `@BeforeEach` 里再 `assumeTrue(原因 == null, 原因)`。现在报告为 `Tests run: 13, Skipped: 13`，每个用例都带有"Docker is not available and RPC_TEST_NACOS_ADDRESS ... is not set"。

**教训**：门控测试要让"跳过"在报告里看得见、带原因，否则会被误读成没有测试，或者以为它跑过了。

## 14. Nacos 2.x 客户端的 gRPC 端口固定为"HTTP 端口 + 1000"，Testcontainers 的随机端口映射对它无效

**现象**（编写 Nacos 契约测试时核对客户端实现发现，本机 Docker 未运行，尚未实跑）：Testcontainers 默认把 8848、9848 分别映射到两个互不相关的随机端口，但 Nacos 2.x 客户端只接受一个 HTTP 地址，gRPC 端口按"HTTP 端口 + 1000"推算，连到的是一个没有映射的端口。

**根因**：`com.alibaba.nacos.common.remote.client.grpc.GrpcClient.rpcPortOffset()` 只读 JVM 系统属性 `nacos.server.grpc.port.offset`（默认 1000），客户端 Properties 不能改它；本仓库又禁止 `System.setProperty`。

**修法**：先找一个本机空闲端口 P，并确认 P+1000 也空闲，再用 `withCreateContainerCmdModifier` 把 P 固定映射到 8848、P+1000 固定映射到 9848，客户端地址用 `127.0.0.1:P`。

**教训**：容器化中间件时要确认客户端是否从一个地址推算出其他端口；只读系统属性的配置项会和"禁止全局状态"的规则冲突，需要在环境侧（端口映射）解决。

## 15. 容量很小的有界队列：同时到达的两个请求，第二个可能在第一个"还没被取走"时就被拒绝

**现象**：过载测试把 A 配成 `ExecutorConfig.fixed(1, queueCapacity=1)`，预期"1 个执行 + 1 个排队"后第三个才被拒。两个占位请求同时发出时，偶发第二个就收到 SERVER_OVERLOADED，等待 `inFlight == 2` 的断言 5 秒超时：

```
OverloadProtectionTest.saturatedNodeRejectsAndFailoverMovesTheCallToAnotherNode:107 » ConditionTimeout
Condition with alias 'A saturated' didn't complete within 5 seconds
```

**根因**：`ThreadPoolExecutor` 的核心线程已经存在且空闲时，新任务不是直接交给它，而是先 `offer` 进队列、再由空闲线程 `take` 出来。两个请求从两个 IO 线程几乎同时提交时，第一个可能还躺在队列里没被取走，第二个 `offer` 就因为队列已满（容量 1）失败，走 AbortPolicy 拒绝。这是经队列交接任务的固有时序，不是计数错误。

**修法**：测试里逐个发出占位请求，并等前一个真正开始执行（`runningBusinessTasks() == 1`）再发下一个。生产语义不改：队列容量本来就是"突发缓冲"，默认 1000 时这个时序窗口可以忽略；需要"恰好 N 个并发"的严格语义时用虚拟线程 + 信号量（`virtualThreads(maxConcurrent)`）。

**教训**：边界条件测试（容量 0 / 1）会把平时被大容量掩盖的交接时序暴露出来；测试要先确定"前一个已经处于什么状态"再制造下一个事件，而不是假设并发提交会按发出顺序落位。

## 16. fastjson2 2.0.60 序列化 ZonedDateTime 不带偏移，夏令时回拨的那一小时读回来偏移错了

**现象**：A0-4 矩阵补 java.time 时，fastjson2 分支失败（Protostuff 分支通过）：

```
SerializationMatrixTest.javaTimeScalarsRoundTripExactly:472
expected: <2024-11-03T01:30-05:00[America/New_York]> but was: <2024-11-03T01:30-04:00[America/New_York]>
```

探针输出 `JSON.toJSONString(z)` = `"2024-11-03T01:30:00[America/New_York]"`。

**根因**：fastjson2 对 ZonedDateTime 默认只写"本地时间 + 区域 ID"、不写偏移。夏令时回拨那一小时里同一个墙上时间对应两个瞬间（-04:00 与 -05:00），读回时 `ZonedDateTime.ofLocal` 取较早的偏移，较晚那个瞬间被悄悄平移了一小时。其他时刻因为偏移唯一，所以平时测不出来。

**修法**：Fastjson2Serializer 持有自己的 `ObjectWriterProvider` / `ObjectReaderProvider`，为 ZonedDateTime 注册按 `ISO_ZONED_DATE_TIME`（偏移 + 区域）读写的实现，序列化 / 反序列化都传入带这两个 provider 的 Context。不用 `JSON.register`：那是 fastjson2 的全局默认 provider，会改变同一 JVM 里所有使用 fastjson2 的代码的行为（单测里断言了全局默认格式没有被改动）。Protostuff 侧同样用 ISO_ZONED_DATE_TIME，两种序列化器语义一致。另一个小坑：`DateTimeFormatter.ISO_*` 格式化时会输出值为 0 的秒（`01:30:00`），而 `toString()` 会省略（`01:30`），断言线上文本时要按格式化器的输出写。

**教训**：时间类型的往返测试要专门挑"有歧义的时刻"（夏令时重叠、闰日、纪元前、纳秒），普通时刻的往返通过证明不了格式无歧义；修第三方库的默认行为要找按实例生效的扩展点，而不是全局注册。

## 17. curator-test 的 TestingServer 会把协商后的会话超时压到 20 秒

**现象**：断言 ZooKeeperRegistry 新默认值（session-timeout-ms 30000）时，协商出的会话超时是 20000：

```
ZookeeperRegistryTest.tightenedDefaultsAreInEffect:87 expected: <30000> but was: <20000>
```

**根因**：ZooKeeper 服务端把客户端请求的会话超时夹在 `[2 × tickTime, 20 × tickTime]` 之间；TestingServer 默认 tickTime 是 1000ms，上限就是 20s。客户端配置的值没错，是服务端改小了。生产 ZK 的 tickTime 通常是 2000ms（上限 40s），30s 原样生效。

**修法**：这条断言单独起一个 `new TestingServer(new InstanceSpec(null, -1, -1, -1, true, -1, 2000, -1), true)`（tickTime 2000ms），断言 `getLastNegotiatedSessionTimeoutMs() == 30000`。

**教训**：会话超时是"协商值"，客户端配置只是上界之一；文档里写"下线节点残留至会话超时"时，实际窗口取决于服务端的 tickTime 配置，排查时要看协商值而不是配置值。

## 18. 连接关闭不会让这条连接上等响应的请求失败；关闭监听端口也不会关闭已接受的连接

**现象**（实现优雅停机时核对发现）：在途表按 requestId 索引、与连接无关。服务端停机超时后关连接，客户端那条连接上已经写出的请求不会立刻失败，而是各自挂到超时（默认 20s）才以 RpcDeadlineExceededException 结束——"停机超时 500ms"在调用方看来变成了 20s。另外只关 `serverChannel` 时，已接受的子连接仍然开着（旧实现靠关闭 worker 线程组顺带关掉）。

**根因**：Netty 的 channel 关闭只会让这条 channel 上尚未 flush 的写失败；已经写出、正在等响应的请求是应用层的状态，Netty 不知道。监听 channel 与子 channel 的生命周期互相独立。

**修法**：客户端在每条连接的 attribute 上记"已写出未响应"的 requestId（`ChannelRequests`），`channelInactive` 时让它们立即以 RpcException（传输失败，D-009 的"连接重置"）失败；服务端用 `DefaultChannelGroup` 记录全部已接受连接，停机时显式关闭。验收测试断言：在途 2s、shutdownTimeout 500ms 时，调用在 close 开始后约 600ms 就失败，而不是 2s 或 20s。

**教训**：请求级状态（在途表）与连接级事件之间要显式建立关联，否则"连接断了"在上层只表现为"慢"；停机测试要断言失败发生的时刻，而不只是"最终失败了"。

## 19. Windows 上 `Process.destroy()` 不会触发被测 JVM 的关闭钩子，"真实 SIGTERM"测试做不了

**现象**（任务简报指出，编写 A0-2 时确认）：想用子进程起 provider、`destroy()` 模拟 SIGTERM 验证优雅下线，在 Windows 上 Spring 的关闭钩子根本不执行。

**根因**：Windows 的 `Process.destroy()` 实现为 `TerminateProcess`，等同于强杀；JVM 关闭钩子只在正常退出、SIGTERM / SIGINT（Unix）或控制台关闭事件时运行。

**修法**：本机用进程内方式验证同一条路径（`ConfigurableApplicationContext.close()` → SmartLifecycle.stop → RpcServer.close，断言 DRAINING 状态、注册节点先于在途完成消失、新请求被拒）；真实 SIGTERM 用例放到 Linux CI（任务 4b / 阶段 3）。

**教训**：信号相关的验收在跨平台开发机上要明确"本机能验证到哪一层"，并把剩下的一层排进 CI，而不是用一个在本机永远不会触发钩子的测试假装覆盖了它。

## 20. 父 POM 把 logback 锁在 1.5.13，Spring Boot 4 应用的控制台日志满屏 `%PARSER_ERROR[clr]`

**现象**：按 README 在本机运行 rpc-examples 的 Boot provider / consumer，每行日志都变成 `%PARSER_ERROR[clr] %PARSER_ERROR[clr] ... Starting ProviderApplication ...`，启动时 logback 打印 `There is no conversion class registered for composite conversion word [clr]`。应用功能正常；集成测试有自己的 `logback-test.xml`，所以从没暴露。

**根因**：Spring Boot 4.1.0 的 `logback/defaults.xml` 用 `<conversionRule conversionWord="clr" class="..."/>` 注册颜色转换器，`class` 属性是 logback 较新版本才认识的写法；Boot 的 `spring-boot-starter-logging` 4.1.0 依赖 logback 1.5.34。父 POM 的 `dependencyManagement` 把 `logback-classic` 固定在 1.5.13，覆盖了 Boot 传递进来的版本，老版本忽略这个属性，`%clr` / `%wEx` 都解析失败。

**修法**：`logback.version` 升到 1.5.34，与 Boot 4.1.0 对齐。

**教训**：`dependencyManagement` 里锁的版本会静默覆盖框架传递依赖的版本；锁版本时要和所用框架的 BOM 对齐，并且至少跑一次"真实应用、默认日志配置"的冒烟，而不只是带测试专用配置的集成测试。

## 21. JaCoCo `report-aggregate` 默认得到一份"看起来正常"的残缺报告

**现象**：在只放测试的 `rpc-integration-tests` 里配 `report-aggregate`，第一次生成的 `jacoco.csv` 只有表头；把被测模块改成 compile scope 后有了数据，但各模块覆盖率与"只跑各自单元测试"的数字一模一样，集成测试的贡献是零（`jacoco-sessions.html` 里没有集成测试那个 JVM 的会话）。

**根因**：
- `report-aggregate` 只汇总 compile / runtime / provided scope 的依赖模块，test scope 的依赖直接被忽略；
- `includeCurrentProject` 默认 false 时，不仅不分析本模块的类，**也不读本模块的 exec**——集成测试的执行数据恰恰在本模块的 `target/jacoco.exec` 里；
- 另外 `jacoco:check` 没有"类目录"参数，只能检查本模块 `target/classes` 里的类，没法直接对别的模块做聚合检查。

**修法**：被测模块改为 compile scope（本模块不安装、不发布，无副作用）；`includeCurrentProject=true`，报告放在 `post-integration-test` 阶段生成（此时本模块 `target/classes` 为空）；之后用 dependency 插件把 rpc-core 的类解包进本模块 `target/classes`，`verify` 阶段 `jacoco:merge` 全部模块的 exec 再 `check`。用"单独拿集成测试 exec 对 core 出报告"交叉核对过：集成测试单独覆盖 core 74.6%，与各模块单元测试合并后 83.5%。

**教训**：覆盖率聚合要核对"有哪些会话被算进来了"，而不是看到一个合理的百分比就相信；能对照的数字（单独某个 exec 的报告）先算一遍。

## 22. 用 test-jar 共享测试代码时，`mvn test` 会把整个 `target/test-classes` 塞进下游模块

**现象**：rpc-core 发布只含泄漏断言扩展的 test-jar（jar 插件 `includes` 过滤），rpc-integration-tests 依赖它。`mvn install` / `verify` 一切正常；但只跑到 `test` 阶段（`mvn test -pl rpc-core,rpc-integration-tests -am`）时，JUnit 报告发现两份 `junit-platform.properties`，其中一份来自 `rpc-core/target/test-classes`。

**根因**：`test` 阶段之前 test-jar 还没打包，Maven 的 reactor 解析会退回到上游模块的输出目录，于是整个 `target/test-classes`（包括 core 测试专用的 `META-INF/rpc` SPI 文件：假序列化器、内存注册中心）进入下游模块的 classpath，jar 插件的 `includes` 过滤此时不起作用。

**修法**：集成测试统一用 `verify` / `install` 跑（README 与 CI 均如此）；`LeakCheckWiringTest` 断言 rpc-core 的 test-classes 不在 classpath 上，误用 `mvn test` 时直接失败并说明原因。

**教训**：test-jar 的过滤只对"打出来的 jar"生效；跨模块共享测试代码时要考虑 reactor 在不同生命周期阶段解析出的是什么。

## 23. JVM 收到 SIGTERM、关闭钩子正常跑完之后，退出码是 143 而不是 0

**现象**：编写 Linux 下的真实 SIGTERM 用例（`SigtermShutdownTest`）时，按"优雅退出 = 退出码 0"写断言是错的：Spring 的关闭钩子完整执行、在途请求全部完成后，进程退出码仍为 143。

**根因**：HotSpot 处理 SIGTERM 时运行关闭钩子，然后以 `128 + 信号值` 退出（SIGTERM = 15 → 143），这是 Unix 惯例，不表示失败；被 SIGKILL 强杀才是 137。Spring Boot 不改写这个退出码。（开发机是 Windows，无法本机复现；用例由 CI 的 sigterm 作业在 Linux 上执行验证。）

**修法**：断言退出码为 143，并另外断言日志里出现 RpcServer 的 `all in-flight requests completed` 与注册节点先于在途调用完成而消失——用行为证明"优雅"，而不是用退出码。Kubernetes 等编排系统同样把 143 视为正常终止。

**教训**：退出码只说明进程怎么结束，不说明结束前做了什么；停机测试要断言可观察的行为。

## 24. `SmartLifecycle.stop(Runnable)` 改成异步后，容器的每阶段停机超时开始生效

**现象**（实现异步停止时核对 Spring 源码发现）：`RpcServerLifecycle` 原本只实现同步 `stop()`，Spring 在停机线程上直接调用它，排空多久就阻塞多久；改为 `stop(Runnable)` 异步回调后，`DefaultLifecycleProcessor` 对每个 phase 只等待有限时间：纯 Spring 默认 10s，Spring Boot 由 `spring.lifecycle.timeout-per-shutdown-phase` 决定（默认 30s）。

**根因**：默认的 `SmartLifecycle.stop(Runnable)` 实现是"同步 stop() 后回调"，等待超时形同虚设；真正异步之后，超时到了容器就继续销毁 bean、进程可能在排空完成前退出。

**修法**：在 `RpcServerLifecycle` 与 README 配置项里写明最长阻塞时长约为 `deregister-grace + shutdown-timeout + 5s`（默认约 15s），要求 `spring.lifecycle.timeout-per-shutdown-phase`（以及 Kubernetes 的 `terminationGracePeriodSeconds`）大于它。

**教训**：把同步回调改成异步时，要查清调用方对异步回调有没有超时；"更快"的代价可能是之前隐含的"一定等完"不再成立。

## 25. 引入 JaCoCo 后，`-DargLine=-Dio.netty.leakDetection.level=paranoid` 被静默丢掉，"零 LEAK"失去意义

**现象**：surefire 配成 `<argLine>@{argLine}</argLine>`、父 POM 定义空的 `argLine` 属性（让跳过 JaCoCo 时也能解析）之后，按原来的方式跑 paranoid 泄漏检测，构建通过、没有任何 `LEAK:`——但 `SerializationMatrixTest` 打印的是 `ResourceLeakDetector level = SIMPLE`。开不开 JaCoCo 都一样。

**根因**：`@{argLine}` 是 surefire 的延迟替换，只读项目属性；JaCoCo 的 prepare-agent 也是在项目属性 `argLine`（父 POM 的空值）上追加 agent。命令行 `-DargLine=...` 是用户属性，两条路径都不读它。

**修法**：`<argLine>@{argLine} ${argLine}</argLine>`：前一半取 JaCoCo 写入的 agent，后一半在模型插值时取值、用户属性优先，于是 `-DargLine=...` 重新生效。验证了三种组合：开 JaCoCo + paranoid → PARANOID 且仍产出 jacoco.exec；`-Djacoco.skip=true` + paranoid → PARANOID；不传 → SIMPLE。CI 的 paranoid 作业额外断言日志里出现 `level = PARANOID`，否则失败。

**教训**："没有报警"必须先证明报警器是开着的；每次改动测试 JVM 的启动参数之后，都要重跑一次"检测器确实生效"的检查（第 8 条的阳性对照同理）。

## 26. 超限的帧交给编码器去拒绝，失败会落进错误的通道

**现象**（去掉预检查后用 `FrameLimitsTest` 复现）：客户端发一个超过 8 MiB 的参数，得到的是 `RpcException "Failed to send request ..."`（cause 为 `EncoderException: frame too large`），看起来像网络问题，且它走传输失败通道——配了 `retries` 就会换节点重发一个注定发不出去的请求；服务端 Filter 往回程 attachments 塞 70KB 字符串，客户端等满 10s 超时，拿到的是 `RpcDeadlineExceededException`。

**根因**：`RpcEncoder` 拒绝超限帧时抛 `EncoderException`，在 Netty 里表现为"这次写失败"。客户端把写失败归为传输失败（future 异常完成），Cluster 据此重试；服务端的回包写失败没有任何人再回一个错误包，对端只能靠自己的超时结束。编码器是唯一检查点时，"确定性失败"被翻译成了"偶发传输故障"或"静默丢包"。

**修法**：发送方在交给编码器之前按同一判定（`ProtocolConstants.exceedsMaxFrame`：23 + attachments + body ≤ 8 MiB）预先检查：客户端以 `RpcSerializationException` 放进结果通道、不写出、不重试；服务端回程 attachments 不合法时改回不带 attachments 的 `SERVER_ERROR`，OK 回包超限改回 `SERIALIZATION_ERROR`。编码器保留同一检查作为最后一道闸。`FrameLimitsTest` 用计数 LoadBalance 断言只尝试一次，并用"恰好 8 MiB 能送达、多 1 字节被拒"验证两端阈值一致。

**教训**：底层组件的拒绝会被上层按"那一层的失败语义"解读；需要特定失败语义（不重试、必须回包）的错误，要在语义还清楚的那一层先拦下，而不是指望最底层的校验。

## 27. 测试替身占用 SPI 缺省名，掩盖了"缺省实现必须在 classpath 上"

**现象**：Serializer 的 SPI 缺省名从 `fastjson2` 改成 `protobuf` 后，rpc-core 的大部分端到端测试和 starter 的两个测试类同时报 `No extension named 'protobuf' found`。

**根因**：rpc-core 的测试序列化器以 `fastjson2` 为 SPI 名注册，正好"冒名"占住了旧缺省名；starter 测试 classpath 上只有 fastjson2。所有没写 `serializer(...)` 的测试都隐式依赖"缺省名恰好能解析"，这层依赖在缺省名变化之前不可见。

**修法**：测试序列化器改名 `simple`（`SimpleTestSerializer.NAME`），core 测试一律显式 `serializer(SimpleTestSerializer.NAME)`；starter 测试显式 `rpc.serializer=fastjson2`；另加 `SerializerDefaultTest` 与 starter 的缺省值断言，直接钉住缺省名。

**教训**：测试替身不要借用真实实现的名字，尤其是缺省名；"不配置也能跑"的测试应当是有意为之并有断言，而不是碰巧。

## 28. Testcontainers 2.x 只 shade 了 jackson-databind，jackson-annotations 走 classpath，与 nacos-client 带来的 2.13 冲突

**现象**：CI 首跑在 rpc-registry-nacos 的 `NacosRegistryContractTest` 类级 `startBackend` 报错，14 条契约测试一条没跑：

```
java.lang.NoSuchFieldError: Class com.fasterxml.jackson.annotation.JsonFormat$Feature does not have member field 'READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE'
	at org.testcontainers.shaded.com.fasterxml.jackson.databind.deser.std.EnumDeserializer.createContextual(...)
	...
	at org.testcontainers.DockerClientFactory.isDockerAvailable(...)
```

本机此前一直全绿：没开 Docker 时 `isDockerAvailable` 在解析 Docker 返回的 JSON 之前就返回 false，契约测试按门控跳过，出错的代码路径从未执行。开着 Docker Desktop 去掉修复重跑，本机同样复现。

**根因**：testcontainers 2.0.5 的 jar 把 jackson-databind / jackson-core 重定位到 `org.testcontainers.shaded.com.fasterxml.jackson`，但 jackson-annotations 没有 shade（jar 里没有 `shaded/.../jackson/annotation/`），shade 后的 databind 用的是普通 classpath 上的 `com.fasterxml.jackson.annotation`。docker-java-api 3.7.1 声明 jackson-annotations 2.20；nacos-client 2.4.3 → jackson-databind 2.13.5 → jackson-annotations 2.13.5 与之同在第 3 层，nacos-client 在 pom 里声明在前，Maven 就近原则打平后取先声明的 2.13.5（`dependency:tree -Dverbose` 显示 `jackson-annotations:jar:2.20:test - omitted for conflict with 2.13.5`）。2.20 的 databind 读取 2.13 里还不存在的枚举常量，于是 `NoSuchFieldError`。

**修法**：根 `pom.xml` 的 `dependencyManagement` 在 junit-bom 之后导入 `com.fasterxml.jackson:jackson-bom`（`jackson-bom.version` = 2.20.2），把 jackson 全族统一到 2.20 线（annotations 2.20，core / databind 2.20.2），nacos-client 随之使用 2.20.2。不在 rpc-registry-nacos 里直接声明 test scope 的 jackson-annotations 2.20：直接声明的 scope 会覆盖传递依赖的 scope，annotations 变成 test 后就被挤出编译期 / 运行期 classpath，而主代码里 nacos-client 的 databind 运行时离不开它。修复后本机（Docker Desktop）实跑 `NacosRegistryContractTest` 14 条，0 失败 0 错误（其中线程泄漏一条按 TCK 的能力假设跳过：Nacos 客户端不由实现控制线程名）。

**教训**：
- "部分 shade"的库最危险：被 shade 的部分自带版本，没 shade 的部分仍参与 classpath 仲裁，冲突只在走到那条代码路径时才出现；引入这类库后用 `dependency:tree -Dverbose` 看一眼它的未 shade 依赖有没有被 "omitted for conflict"。
- 按环境门控跳过的测试在本机等于没跑；CI 门禁断言"必须实跑"才能把这类问题拦下，本机能开 Docker 时也应至少实跑一次。

## 29. registered ≠ discovered：提供者"注册完成"不等于消费者"已经看到它"

**现象**：CI 的 paranoid-leak 作业（`-Dio.netty.leakDetection.level=paranoid`，整体变慢）里，`DeadlinePropagationTest` 在 `@BeforeAll` 的预热调用上偶发失败：`RpcException: No available provider for: ...`。本机与普通 CI 作业一直全绿；测试在发调用前已经 `server.registered().get(...)` 等到注册完成。

**根因**：`RpcServer.registered()` 只覆盖提供者一侧——节点已写进 ZooKeeper。消费者若在提供者注册**之前**就已订阅（测试里 A → B → C 三个服务，B 的构造器里就 `reference` 了 C），它看到新节点要靠 ZK watch 推送，推送经过 Curator 的事件线程到达 `ServiceDirectory` 需要时间；`registered()` 完成与"消费者目录非空"是两个独立事件，中间没有任何先后保证。paranoid 模式把推送拖慢约 10ms，紧随其后的预热调用就撞上了空目录。`reference()` 本身按 Registry 契约不阻塞，空目录的调用按设计快速失败。

**修法**：
- 测试侧：`rpc-integration-tests` 新增 `it/support/Discovery.awaitProviders(client, itf)`，用 Awaitility 等到 `client.providers(key)` 非空再发第一次调用；`DeadlinePropagationTest` 预热前对每个消费者调用它（提交 18bda15，不改任何断言与产品代码）。
- 框架侧（D-015）：`ReferenceOptions.awaitProviders(Duration)`（starter 为 `@RpcReference(awaitProvidersMs = ...)`），默认 null 不等待；设置后 `RpcClient.reference()` 在订阅之后阻塞到该服务的提供者视图非空，由 `ServiceDirectory` 的更新路径通过条件变量唤醒（不轮询），超时抛 `RpcException`（消息含 serviceKey 与等待时长）。`RpcClientAwaitProvidersTest`（进程内注册中心）与 `AwaitProvidersTest`（真实 ZK，先订阅后注册）覆盖它。

**教训**：
- "注册完成"和"被发现"是两个事件，分别发生在提供者与消费者两侧；任何"等 A 完成再做 B"的同步，都要确认 A 与 B 观察的是同一个状态，而不是两个碰巧通常先后发生的状态。
- 只在慢环境（paranoid、CI 共享机器）暴露的失败多半是竞态，不是环境问题；修法是等正确的条件，而不是加 sleep 或放宽超时。
- 测试工具先行、框架能力随后：测试里反复出现的"等某个条件"，往往也是使用方会遇到的问题，值得变成正式 API。

## 30. WSL2 墙钟会回跳，gettimeofday 计时的工具会出现负延迟或假超时

**现象**：在 WSL2 上做 harness 交叉验证时，wrk2 跑到一半 abort：`wrk2: src/hdr_histogram.c:54: counts_index: Assertion 'bucket_index < h->bucket_count' failed.`，
时有时无（同样的命令 15s 一次成功、10s 一次失败）。wrk 闭环一次 10s 运行报 `Socket errors: ... timeout 40`，而它自己的最大延迟只有 108ms，
远小于 2s 的超时。harness 同时段的数字没有异常。

**根因**：wrk 与 wrk2 的 `time_us()` 都用 `gettimeofday`（墙上时钟，CLOCK_REALTIME）。WSL2 虚拟机会与宿主同步时间，墙上时钟偶尔被
往回拨几毫秒——本机用一个 45s 的探测循环（比较 `time.time()` 与 `time.monotonic()`）观测到 2 次回拨。回拨那一刻在途的请求算出来的
"响应时刻 − 发出时刻"是负数：wrk2 把负数交给 HdrHistogram，`bucket_index` 越界触发断言；wrk 的 `now - c->start` 是无符号数，
下溢成巨大的值，`stats_record` 因超过上限返回 0，被当成 timeout 计数。harness 全程用 `System.nanoTime()`（单调时钟），不受影响。

**修法**：wrk 与 wrk2 的 `time_us()` 改为 `clock_gettime(CLOCK_MONOTONIC)`（只换计时源，不改测量逻辑），补丁存为
`bench/tools/wrk-monotonic-clock.patch` 与 `bench/tools/wrk2-build-and-clock.patch`，编译步骤见 `bench/tools/README.md`。打补丁后
wrk2 不再 abort，wrk 的假 timeout 归零。

**教训**：
- 压测工具链里任何用墙上时钟算时间差的地方都要排查：虚拟机、容器、NTP 校时都会让墙上时钟跳变，出来的不是"偶发的大延迟"，
  而是负数、溢出、假超时这类说不通的数。时间差一律用单调时钟（Java 的 `nanoTime`，C 的 `CLOCK_MONOTONIC`）。
- 同一个问题也会出现在容器内依赖 `currentTimeMillis` 的心跳 / 会话超时判断上：时钟回拨可能让超时晚判，前跳可能让健康的连接被误判超时。
- 第三方工具的"错误"计数不要直接信：先看它与其它指标是否自洽（40 个 timeout 却最大延迟 108ms 就不自洽）。

## 31. wrk2 的发送节拍是毫秒粒度，不能做亚毫秒级开环延迟的参照

**现象**：harness 与 wrk2 以开环 5000 req/s 打同一个本机 HTTP 回显端点（1KB，64 连接），达成吞吐两边都在目标 rate 的 0.1% 以内，
但延迟差了几倍：harness（从计划时刻计时）p50 0.25ms、p99 0.45ms，wrk2（修正后）p50 0.96ms、p99 1.93ms。按"p50 / p99 相对偏差 ≤ 15%
或绝对差 ≤ 100µs"判定，交叉验证 FAIL。

**根因**：wrk2 推迟发送时把等待时间向上取整到整毫秒——`src/wrk.c` 第 604 行 `int msec_to_wait = round((time_usec_to_wait / 1000.0L) + 0.5);`，
再用 `aeCreateTimeEvent(thread->loop, msec_to_wait, ...)` 交给 ae 定时器，而 ae 的定时器本身按毫秒计（`src/ae.c` 的 `aeGetTime`）。
每个请求都比自己的计划时刻晚发零点几到一毫秒多，wrk2 的"修正后"延迟从计划时刻算，这段自身发送滞后全部计入。用 `-U` 同时输出
"未修正"分布（从实际发出计时）即可拆开：同一次运行里修正后 p50 0.96ms、未修正 p50 0.148ms，约 0.82ms 是 wrk2 自己的节拍误差；
harness 的服务时间（从实际执行）p50 0.22ms，发出滞后 p99 只有约 10µs（调度线程最后一段自旋对齐）。

**修法**：开环交叉验证换对比基准（不改阈值）：测量一致性比较 harness 的服务时间与 wrk2 的未修正延迟（两边起点定义一致），节拍质量单独
约束 harness 的发出滞后 p99 ≤ 100µs，wrk2 的修正后延迟只作信息项并排、注明出处。`crossval.sh` 的 wrk2 测量运行默认带 `-U`。
口径见 docs/benchmarking.md「交叉验证」。

**教训**：
- 拿第三方工具做参照之前，先确认它的时间分辨率比被测量小一个数量级以上；"业界标准工具"不等于在任何量级上都是标准。
- 修正 coordinated omission 的延迟 = 服务时间 + 发送滞后，两项要能分开看：只比合计值，分不清是被测系统慢还是发压端自己晚发。
- 结论对不上时先拆分量再下判断：这里一个 `-U` 就把"harness 有问题"变成了"参照工具在这个量级不适用"。

## 32. 虚拟线程直接用 Netty 池化分配器：没有线程缓存、在 arena 锁上排队，扩容时钉住载体线程，万级并发下整个客户端卡死

**现象**：两件看起来无关的事。
- WSL2 上开环 10000 req/s（`loopback-latency-v1`，客户端每个请求一个虚拟线程）偶发整轮卡死：线程转储里 2753 个虚拟线程停在
  `ReferenceProxy.invoke` 的 `join`，5 个载体线程（ForkJoinPool-1-worker-1..5，压测端绑 5 个核）各自"Carrying"一个虚拟线程，
  这 5 个全部是 `VirtualThread.parkOnCarrierThread` ← `ReentrantLock.lock` ← `PoolArena.lock` ← `PoolArena.reallocate` ←
  `AbstractByteBuf.writeBytes` ← `EnvelopeCodec.writeRequest` ← `NettyClient.write`；另有 2 个已卸载的虚拟线程在 `PoolArena.freeChunk`
  里等同一把锁。进程不再前进，只能 kill。用缩短到 20s 的同一张卡复现：修复前 2 次运行 2 次卡死（第 1 轮内），
  `-Djdk.tracePinnedThreads=full` 打出 `PoolArena.reallocate(PoolArena.java:317) <== monitors:1`（等 `PoolArena.lock` 与 `PoolSubpage.lock` 两种栈）。
- CI 的 paranoid 泄漏作业（run 39183249559）报 `LEAK: ByteBuf.release() was not called`：构建日志里 10 份报告（检查步骤又把它们 grep
  回显一遍，共 20 行），全部是同一条路径——Created at `RpcClient.attempt` 的 writer lambda 里 `alloc.buffer()`（调用方虚拟线程上），
  最后一条访问记录是 `EnvelopeCodec.writeValue` 里的 `writeBytes`，**之后再没有任何记录**。报告行前的线程名（`rpc-server-worker`、
  `rpc-server-biz`、`rpc-client-io`）只是"之后哪个线程分配时顺手报告了它"，不是泄漏发生处——服务端没有泄漏。本机复现率：
  `BenchEndToEndTest` paranoid 3 次中 2 次有 LEAK，另 1 次测试 JVM 因本机内存不足崩溃。

**根因**：
1. **虚拟线程不该碰池化分配器**。`PooledByteBufAllocator` 的线程缓存（`PoolThreadCache`）只给 `FastThreadLocalThread` 与事件循环线程
   （`io.netty.allocator.useCacheForAllThreads` 缺省 false），其它线程拿到的是容量为 0 的缓存：每次分配、扩容、释放都直接去 arena 抢锁；
   每个新虚拟线程第一次分配还要进一次 `synchronized` 的 `PoolThreadLocalCache.initialValue()`。开环每请求一个新虚拟线程，几千个线程同时在
   arena 锁上排队。
2. **扩容钉住载体**。请求 body 从缺省 256 字节写起，1KB 的参数写到一半要扩容，`PoolArena.reallocate` 在 `synchronized (buf)` 里调
   `allocate` 去取 arena / subpage 的 `ReentrantLock`（Netty 4.1.136 源码第 300 行起，注释自称"临界区短、对 Loom 不是问题"）。JDK 21 的虚拟线程
   在 monitor 内 park 会钉住载体（`parkOnCarrierThread`）。锁释放时交给队首等待者，而队首恰好是一个已卸载、需要载体才能继续的虚拟线程；
   5 个载体此刻全被钉住等同一把锁——锁的下一任持有者永远拿不到载体，死锁。
3. **调用方线程上已分配、还没交给 Netty 的缓冲区，遇到 Error 就丢**。`it-must-fail` 场景（1MiB 回显、1ms 超时、16 并发）里请求超时后仍在
   出站缓冲区排队写出，直接内存很快耗尽，扩容时抛 `OutOfMemoryError: Cannot reserve 4194304 bytes of direct buffer memory`。writer 只
   `catch (RuntimeException)` 释放 body，Error 穿过去；它又是在 `connections.acquire(...).whenComplete(...)` 的回调里抛出的——
   CompletableFuture 把回调异常装进一个没人看的 future，**静默吞掉**，请求挂到超时，body 随之泄漏。加诊断复跑：3 次共 99 次逃逸，全部是这个 OOM；
   服务端回包的 `toReply` / `errorReply` / `frameworkError` 有同样的 `catch (RuntimeException)` 漏洞（本机日志里有一条 "produced no reply" + OOM）。
   CI 日志里没有 OOM 字样，因为客户端这条路径的 Error 被吞了；泄漏签名（最后访问是扩容那次 `writeBytes`，之后连 `writeAndFlush` 的 touch 记录都没有）
   与本机复现完全一致。

**修法**：
- **调用方线程不碰池化分配器**（设计原则）：`RequestWriter` 只做序列化，返回不持 ByteBuf 的 `OutboundBody`（`EnvelopeCodec.request(...)`：
  路由字段 + 参数 byte[] + 预先算好的确切长度）；`RpcEncoder` 在事件循环上用 `ctx.alloc()` 按确切长度一次分配，写头 + attachments + 信封 + 参数，
  单个缓冲区、不扩容、不再 composite/retain。整帧超限检查按 `OutboundBody.length()` 在调用方线程上做（判定与编码器相同，仍以
  `RpcSerializationException` 进不重试通道）。线上字节逐字节不变（`RpcCodecTest` 对比新旧两种写法）。`writeStr` 改用
  `reserveAndWriteUtf8` 按确切字节数预留（`writeUtf8` 按每字符 3 字节预留，放不进按确切长度分配的缓冲区）。
- 发送路径上任何 `Throwable` 都让请求立即失败：writer 抛 Error 以 `RpcSerializationException` 失败，`whenComplete` 回调外层再兜一层；
  写出改为提交到连接的事件循环执行，排队期间已超时的请求不再编码写出（过载时不再把内存越堆越高）。
- 服务端回包编码的三处改为 `catch (Throwable)` 先释放 body 再抛出（随后被下面"响应侧同样处理"取代：业务线程上不再有缓冲区）。
- **响应侧同样处理**：服务端业务线程（`ExecutorConfig.virtualThreads` 时是虚拟线程）也不碰池化分配器。`ServiceDispatcher` 只把返回值
  序列化成 byte[]、把错误信封的字段备好并截断（`EnvelopeCodec.value(...)` / `EnvelopeCodec.error(...)`），回包携带 `OutboundBody`，
  由 `RpcEncoder` 在事件循环上整帧写出，线上字节不变（`RpcCodecTest` 对比 OK 值、带 remoteStack 的错误信封、框架错误的新旧写法）。
  序列化返回值时抛 Error 改回 `SERIALIZATION_ERROR`，组装回包的其余 Throwable 退回只带类名的 `SERVER_ERROR`——以前这两种都让回包 future
  异常完成，ServerHandler 只打一行 "produced no reply"，客户端挂到超时。`AttachmentsCodec.writeStr` 同样改为按确切字节数预留：第一次修复
  只改了信封的 `writeStr`，2 字节字符 / 代理对多的 attachments 后面跟一个很短的 body 时，整帧仍会因预留超出容量而编码失败（请求、回包都会）。
  护栏：ArchUnit 规则的范围扩到 server 包里非 ChannelHandler 的类（ServiceDispatcher、BusinessExecutor、ServerHandler.DispatchTask）；
  `ServerThreadAllocationTest` 用虚拟线程执行器跑 200 并发 × 10 次 4KB 回显 + 业务异常，断言所有分配都在 `rpc-server-worker-*` 上。
- 护栏：`CoreArchitectureTest` 规定调用方一侧（core 根包、client / cluster / filter / loadbalance 包里非 ChannelHandler 的类）不得调用
  `ByteBufAllocator` 的任何方法、`Channel.alloc()`、`ChannelHandlerContext.alloc()`；bench 模块同一条规则。`CallerThreadAllocationTest`
  给连接注入记录分配线程的分配器，200 个虚拟线程 × 10 次 4KB 回显后断言所有分配都在 `rpc-client-io-*` 上。泄漏回归测试用测试包
  `io.netty.buffer` 里的 `ByteBufLeakProbe`（给 `AbstractByteBuf.leakDetector` 挂 `LeakListener`、反复 GC，在测试内同步断言）。

修复后（同一台 WSL2、同一张 20s 冒烟卡、服务端绑 0-4、压测端绑 5-9）：开环 3 次 9 轮全部跑完、0 错误，`tracePinnedThreads` 0 条；
`BenchEndToEndTest` paranoid 连跑 5 次 0 LEAK、0 OOM。第 1 轮的在途峰值仍有 4171–11009，那是冷启动（第 1 秒只完成 1093–9176 个请求，
修复前同样如此），稳态轮 52–457。

**教训**：
- 虚拟线程上"不阻塞"的代码不等于安全：第三方库内部的 `synchronized` + 锁（这里是 Netty 的 `PoolArena.reallocate`）在 JDK 21 上会钉住载体，
  而钉住只在高并发时才变成死锁。`-Djdk.tracePinnedThreads=full` 应该是虚拟线程压测的默认参数之一。
- 池化分配器是给少量长寿命线程设计的。成千上万的短命调用方线程只做与分配器无关的工作（序列化成 byte[]），缓冲区留给事件循环分配。
- 在 CompletableFuture 回调里干活的代码，必须自己兜住 `Throwable`：回调抛出的异常（尤其是 Error）不会传播到任何地方。
- "已分配、还没交出所有权"的缓冲区只 `catch (RuntimeException)` 释放是不够的；能不在这个窗口里持有缓冲区更好。
- 泄漏报告行前的线程名是报告者，不是泄漏者；看 Created at 与最后一条访问记录，再对照"正常路径上下一条应该出现的记录"（这里是
  `writeAndFlush` 的 touch）推断它断在哪里。

## 33. 注册中心"晚到"的集成测试偶发超时：不是订阅不补推，是 ZK 启动瞬间吞掉了握手

**现象**：CI paranoid 作业（2026-10-19 03:29）`UnreachableRegistryTest.clientReferenceDoesNotWaitForAnUnreachableRegistry_andCatchesUpOnceItAppears:126`
报 `ConditionTimeout 'subscription catches up' within 15 seconds`。日志：ZK（`TestingServer`）在 18.18s 出现，服务端注册中心 18.33s 连上并完成注册；
消费端注册中心直到超时都没有 CONNECTED，24.08s 出现 `connection SUSPENDED` 与 Curator 的 `Background operation retry gave up ... ConnectionLoss`，
随后 `RpcClient closed in 14778ms`。同一时刻 ZK 服务端日志：`Close of session 0x0 ... ZooKeeperServer not running`，紧跟一条
`NullPointerException: Cannot invoke "ZKDatabase.removeCnxn(...)" because "this.zkDb" is null`。本机（Windows）paranoid 循环 10 次另有一个
**确定性**失败：`reference() blocked 2585~2732ms`（上界 2500ms），与 CI 那条无关。

**根因**：
1. **订阅本身会补推**，包括 Curator 后台重试已经放弃之后。探针 `ZookeeperLateBackendProbeTest`（`-Pprobe`）让 ZK 在订阅之后 0~35s 才出现：
   D ≥ 8s 的各次都先经历"重试放弃 → SUSPENDED"，ZK 出现后 0.1~1.05s 内订阅者都收到了提供者（两次运行共 20 次，无一例外）。原因是
   `CuratorCache` 用的 `PersistentWatcher`：后台 `addWatch` 失败时回调里立即再 `reset()`（重新排队），每次连接状态变为已连接时也 `reset()`；
   重建缓存后由本实现的合并推送补推。设计文档的契约（"后端连上之后订阅补推首次列表"）成立。
2. **CI 那次是"后端没真正连上"**。`TestingServer` 启动时监听端口先于 `ZooKeeperServer` 就绪；恰在这一瞬间连进来的客户端，服务端按
   "not running" 关闭连接，而 ZK 3.9.4 的 `NIOServerCnxn.close()`（第 607–627 行）先调 `zkServer.removeCnxn(this)`，后者是
   `zkDb.removeCnxn(cnxn)`（`ZooKeeperServer` 第 333 行），`zkDb` 尚为 null → NPE，`closeSock()` 没执行到：**服务端不回包也不关套接字**。
   客户端握手没有回应，要等 ZK 客户端的连接超时 `connectTimeout = sessionTimeout / 主机数`（`ClientCnxn` 第 421、1241 行；会话超时缺省
   30s、1 台主机 → 30s）才断开重连。期间 Curator 的后台操作照常在 connection-timeout（5s）+ 3 次退避后放弃、报 SUSPENDED——这条 ERROR
   日志是旁证，不是原因。探针第二个用例用"接受连接后不读不写不关"的端口复现客户端视角：ZK 出现后 **30.3s** 才补推
   （`SUSPENDED@7028 → CONNECTED@31292`）。测试只等 15s。
3. Windows 冷 JVM 上 `RpcClient` 构造（类加载）约 1.5s、`reference()`（订阅最多等 connect-wait-ms）约 1.06s，旧断言把两者合在一起给 2500ms。

**修法**（只改测试，不改实现）：`UnreachableRegistryTest` 的两个"ZK 出现后"的等待（服务端 `registered()`、客户端补推）上界从 15s 改为
`CATCH_UP_BOUND_SECONDS = 45`（30s 连接超时 + ≤1s 重连退避 + 推送合并 + 余量），注释写明依据；客户端构造与 `reference()` 分开计时，
`reference()` 仍是 < 2500ms，构造另给 < 5000ms。改后本机 paranoid 循环 10/10 通过。TCK 增加
`subscribeSurvivesTheClientGivingUpRetriesBeforeTheBackendAppears`：后端在订阅之后停机超过客户端重试放弃点（ZK 15s）才恢复，
订阅仍须补推（进程内参考实现 / ZK / Nacos 三个实现都跑）。

**教训**：
- "重试放弃"的 ERROR 日志很吓人，但先看它放弃的是什么：Curator 的单次后台操作放弃之后，持久 watch 会重新排队，订阅并没有死。
  判断"会不会补推"要做实验（让后端晚到不同时长），不能读日志猜。
- 测试里的"等后端恢复"上界要从客户端的超时链推出来：这里真正的上界是 ZK 客户端的握手超时（会话超时 / 主机数），不是 Curator 的
  connection-timeout，也不是拍脑袋的 15s。
- 生产上同一个窗口也存在：ZK 服务端重启的那一瞬间连进来的客户端，可能被吊住一个会话超时（缺省 30s）才重连。它有界、会自愈，
  缩短它的办法是调小 `session-timeout-ms`（代价是 GC / 网络抖动更容易误判会话过期）。
- 计时断言只框住被测的那一段：构造期的类加载与"订阅等多久"是两件事，混在一起的上界会在慢机器上误报。

## 34. 同步调用过了 deadline 仍无界挂住：超时定时器在运行时内存事故里会"丢"，只有一道上界不够

**现象**：阶段 2 任务 11 的全量构建里，`rpc-bench-e2e` 的 `BenchEndToEndTest.anAssertionThatCannotHoldFailsTheRunWithExitCode2`
（1MiB 回显 × 16 并发、1ms 超时、`--direct`）挂了 80 分钟。线程转储：唯一没退出的发压虚拟线程停在 `ReferenceProxy.invoke` 的
`CompletableFuture.join()`；`CompletableFutureDelayScheduler` 与 `VirtualThread-unparker` 空闲、队列为空；IO / 业务线程全部空闲；
没有任何 ForkJoinPool 载体线程。同一时段 Windows 提交内存耗尽：Git Bash fork 报 `STATUS_COMMITMENT_LIMIT`，另一个测试 JVM 的
hs_err 是 `G1 virtual space` mmap 失败。

**根因**（分类实验 + 确定性探针；现场属于哪一种仍有不确定性）：
1. **这张卡自己就能把整机提交内存打满**。默认堆（16GB 机器，MaxHeap 3936MiB）下单个测试 JVM 的私有提交峰值 5.9~6.3GB
   （13 份 hs_err 的 `peak`）：1MiB 的数组全是 G1 humongous 对象；服务端出站没有背压，负载结束数秒后服务端两个 IO 线程仍在
   `write0`，窗口末尾发出的 30s 元数据调用排在积压后面迟迟拿不到响应。本机空闲提交约 6GB，于是：默认堆循环 50 次，13 次 JVM
   直接 native OOM 崩溃（G1 mmap / C2 arena malloc），0 次挂起；另起进程预占到空闲提交约 0.9GB 后 12 次全部崩溃，第 12 次之后
   发压脚本自己的 fork 也报 `0xC000012D`——与现场同一症状。
2. **堆耗尽跨过到期时刻时，超时定时器会丢**。`-Xmx1g` 循环 28 次，28 次都出现 `OutOfMemoryError: Java heap space`，0 次挂起
   （碰上的概率低）。确定性探针（`-Xmx64m`，在定时器到期前后把堆填满 600ms，各 10 次）：JDK `orTimeout` 10/10 次 future 永不完成，
   调度线程本身也死于 OOM（`OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "CompletableFutureDelayScheduler"`）；
   换成"预构造无栈异常、到期不分配"的自有定时器同样 10/10 不完成（线程池工作线程在取任务时分配失败就死了）。而用
   `get(deadline 剩余 + grace)` 等待的平台线程与虚拟线程 **10/10 按时返回**——等待者自己的定时 park 不依赖那个出事的线程。
3. 现场是"超时任务分配失败被吞、虚拟线程一直 parked"（堆耗尽），还是"已经 unpark、但提交耗尽时 ForkJoinPool 建不出载体去跑
   continuation"，从现有材料分不出来：JDK 21 的 `Thread.dump_to_file`（文本与 JSON）都不输出虚拟线程状态，现场也没有堆转储。
   共同点是同一个：**唯一的上界是"别的线程会来完成这个 future"，而那个线程在内存事故里跑不起来**。按代码审查，1ms 超时与
   1MiB 写出的竞态没有能让 future 永不完成的路径（定时器在等连接之前登记，覆盖全过程）。

**修法**：
- 同步代理的等待改为有界：最多等"本次调用 deadline 的剩余时间 + `syncWaitGrace`"（`RpcClientConfig.syncWaitGrace`，缺省 1s，
  starter 属性 `rpc.client.sync-wait-grace`）。超出抛 `RpcDeadlineExceededException`（消息含 `deadline timer did not fire within grace`），
  并经 `Invocation.abandon` 让本次调用已发出的请求失败：在途表移除、在途闸门退出、连接上的请求记录与负载统计释放，不进 Cluster 重试。
  等待不响应中断（与原来的 `join()` 一致，中断标志保留）。正常情况下定时器总是先到，超时语义不变。
- 评估过、**没有采用**的方案：在途表定时器改为自有调度池 + 预构造的无栈超时实例（到期不再 `new TimeoutException()`）。
  上面的探针里它与 `orTimeout` 一样 10/10 丢超时（死的是线程池工作线程，不只是那次分配），且进程级静态调度池违反
  "core 不持有静态可变状态"的架构规则；它挡不住的场景第二道上界都能挡住，于是保持 `orTimeout`。
- 返回 `CompletableFuture` 的异步方法不受第二道上界约束（future 只由定时器 / 响应完成），javadoc 写明调用方应自带 `orTimeout`。
- 测试：占住定时器线程模拟"定时器不触发"，断言同步调用（平台线程与虚拟线程）在 deadline + grace 内抛出、在途归零、
  恢复后调用正常且正常超时仍由定时器给出；grace 配置生效（耗时上界小于缺省 grace）。

**教训**：
- **同步等待必须有第二道上界**。"会有别的线程来完成 future"在运行时内存事故（提交耗尽、堆耗尽）里不成立：定时器线程会死于
  OOM，超时任务里的分配失败会被线程池吞掉，虚拟线程可能拿不到载体。第二道上界要靠等待者自己的定时 park。
- 测试 / 压测卡的内存足迹也是被测对象：1MiB × 16 并发在默认堆下单进程峰值 6GB 提交，足以拖垮整机（连 shell 的 fork 都失败），
  这时出现的任何"怪现象"先看提交内存，再看代码。
- JDK 21 的线程转储不带虚拟线程状态；要区分"没人唤醒"与"唤醒了但没载体"，挂住时要留堆转储（看 `VirtualThread.state` 与
  future 的 result），只有线程转储不够。

## 35. 出站背压：可写性事件会嵌套乱序到达；PARANOID 下"多久没新写"不等于服务端停了

**现象**（阶段 2b 任务 16，实现出站背压时）：
1. （读 Netty 源码推演出、再用测试构造出的场景，实现一开始就按下面的修法写，没有在运行中踩到）"不可写就 `setAutoRead(false)`、
   可写就 `setAutoRead(true)`"若按事件方向切换，会把**可写**的连接停读：
   读循环里先 `writeAndFlush` 一个小回包（flush 被 FlushConsolidationHandler 推迟），再写一个越过高水位的回包。
2. "只发不读"对照测试（`NonReadingClientTest`）的背压关一侧，峰值待写字节三次运行分别是 0.94MB、1.0MB、11.8MB，预期是约 21MB。

**根因**：
1. `ChannelOutboundBuffer` 在事件循环上越过高水位时**同步**触发 `channelWritabilityChanged`；pipeline 首位的 FlushConsolidationHandler
   在"变为不可写"里先冲刷它推迟的 flush。套接字一次收下时，这次冲刷里待写字节降到低水位以下，**嵌套**触发"恢复可写"——
   它先于外层的"不可写"到达下游 handler。按方向切换会先恢复（本来就没停）、再停读，停在错误的状态，且之后不会再有"恢复可写"事件。
2. 测试判"服务端已稳定"用的是"300ms 没有新的写"。PARANOID 泄漏检测下每次缓冲区访问都记一份栈，服务端事件循环一轮读
   （16 × 64KiB 的小请求）能花掉几百毫秒，这期间业务线程已经回包、写任务排在事件循环队列里，写次数不动——"没有新写"只是事件循环忙。
   线程转储里两边的事件循环都停在 `ResourceLeakDetector$TraceRecord.<init>`。

**修法**：
- `OutboundBackpressure` 不看事件方向，每次事件都按 `channel.isWritable()` 的**当前值**对账（另记"是不是自己停的读"）；
  `OutboundBackpressureTest.reconcilesWithTheCurrentStateWhenFlushConsolidationDrainsInsideTheEvent` 固定这个场景。
- 测试改为等确定的终态：背压关时等写满全部回包次数；背压开时等"读已暂停、在途为零、写与业务调用次数 1s 不变"。两端套接字缓冲区
  在测试里固定为 64KiB（SO_SNDBUF / SO_RCVBUF），出站缓冲区里的数字不再取决于内核自动调整。改后背压关一侧稳定在 22.8MB。

**教训**：
- Netty 的状态类事件（可写性、autoRead）在同一线程上可以嵌套触发，handler 里应按当前状态对账，而不是按"这次事件说了什么"切换。
- 测试里的"等稳定"要等一个由被测对象定义的终态，不要用"一段时间没变化"——被测路径在调试级别的检测（PARANOID、JFR、断点）下
  会慢一个数量级，静默期就不再意味着完成。
- 停读之后读空闲计时照常走：长期不读回包的客户端会在读空闲阈值（服务端 30s）后被关掉，这是兜底而不是缺陷，但要写进文档。

## 36. 服务目录先发布再建连接池：providers() 已非空，第一次调用却偶发 "No connection pool"

**现象**（阶段 3，任务 17、12、13 的全量构建各一次）：`UnreachableRegistryTest.clientReferenceDoesNotWaitForAnUnreachableRegistry_andCatchesUpOnceItAppears`
等到 `client.providers(key)` 非空后立即调用，偶发 `RpcException: No connection pool for: 127.0.0.1:<port>`。单独重跑总是通过。

**根因**：`ServiceDirectory.onInstancesChanged` 先把新列表写进 volatile 的 `instances`（路由视图：Cluster 每次选点、`RpcClient.providers()`
都读它），再执行 `onChange`（`refreshConnections` → `ConnectionManager.ensurePool`）建连接池。两步之间读到新地址的调用在
`ConnectionManager.acquire` 上找不到连接池，直接失败（有连接池时 acquire 会等 connect 完成）。D-015 只把 `onChange` 放到 `signal` 之前，
保护的是 `awaitProviders` 的唤醒路径；直接读 `providers()` / 路由视图的调用方仍落在窗口里——javadoc 当时也写明了"检查时已非空"的路径不受保护。
这不只是测试的问题：新提供者上线时，并发流量选中它就会以传输失败结束，failfast 下原样返回给调用方。
进程内注册中心 + 忙等 `providers()` 非空即调用的复现测试（`ProvidersVisibleImpliesCallableTest`），修复前 200 轮里 199 轮失败。

**修法**：目录分两个视图——路由视图 `instances()` 与连接目标 `connectionTargets()`（`refreshConnections` 改读后者）。一次推送三步走：
先把连接目标扩成新旧列表的并集、执行 `onChange` 建池（下线地址的池暂留）；再发布路由视图；有地址下线时把连接目标收窄到新列表、
第二次 `onChange` 让它排空。任何时刻路由视图里的地址都已有连接池，"`providers()` 非空即可发起调用"成立；下线地址先移出路由视图再排空。
没有选"acquire 找不到池时等待 / 懒建池"：那要给 acquire 加等待与通知，刚下线的地址会挂到超时而不是快速失败换节点，懒建还会把已下线地址的池建回来。
修复后复现测试 200 轮 0 失败。

**教训**：
- "对调用方可见"与"可以被使用"必须是同一时刻：先把依赖（连接池）准备好，再发布引用它的视图；下线反过来，先撤视图、再拆依赖。
- 修一个可见性窗口时，要列出读这份状态的**所有**路径（这里有 awaitProviders 的唤醒、awaitProviders 的"已非空"、Cluster 选点、providers()），
  只修其中一条（D-015）会把剩下的变成低概率偶发。
- 低概率偶发先找能放大窗口的确定性复现（忙等读者、或在回调里注入延迟），再改代码；"单独重跑通过"不是结论。
