# rpc-framework

一个可以被真实项目依赖的 Java RPC 库：Netty 传输 + 自研二进制协议 + ZooKeeper / Nacos 服务发现 + Spring Boot starter。
核心是纯 Java（不依赖任何容器），同一个 JVM 里可以 new 出多个客户端与服务端；Spring Boot 只是在它外面包了一层。

> 当前状态：**阶段 0（可用性修复）已完成**，尚未发布到 Maven 仓库，需要先在本地 `mvn install`。
> 还没有链路追踪与压测数据（见[路线图](#路线图)）；调用指标见 [docs/observability.md](docs/observability.md)。使用前请先读[已知边界](#已知边界)。

## 目录

- [特性](#特性)
- [架构](#架构)
- [模块](#模块)
- [环境要求](#环境要求)
- [快速开始](#快速开始)：纯 Java · Spring Boot starter · 运行 rpc-examples
- [配置项](#配置项)
- [设计要点](#设计要点)
- [已知边界](#已知边界)
- [测试与证据](#测试与证据)
- [路线图](#路线图)
- [文档](#文档) · [许可证](#许可证)

## 特性

只列已经实现并有测试覆盖的能力：

- **纯 Java 核心**：`RpcServer` / `RpcClient` 是可以 new、start、close 的实例，配置是不可变对象；无全局可变状态，同 JVM 多实例互不干扰。
- **协议 v2**：23 字节定长头 + 框架自编码的 attachments 段与请求 / 响应信封（[规格](docs/protocol.md)）。
- **方法模型**：多参数、重载（按参数签名分发）、泛型参数与返回值（`List<User>`、`Map<K,V>`、`CompletableFuture<List<T>>`）。
- **服务模型**：`interface + version + group`，同一接口可按版本 / 分组暴露多份。
- **序列化**：Protostuff（缺省；按方法声明的泛型类型构造 schema、多态位置的类名白名单、`java.time` 按 ISO 文本编码）与 fastjson2；建连时双方协商。
- **注册中心**：ZooKeeper（会话过期后自动重建临时节点、订阅推送合并去重、注册中心不可达时后台重试）与 Nacos；两者跑同一套契约测试（TCK）。
- **消费端推空保护**：注册中心推送空列表时保留旧地址并告警。
- **负载均衡**：`roundrobin`、`p2c`（两个随机候选取在途数少者）。**容错**：`failfast`、`failover`、`failback`。
- **Filter 链**：客户端 / 服务端同一个 `Filter` 接口，按 `order()` 稳定排序；**attachments** 经 `RpcContext` 设置，按调用消费即清。
- **deadline**：超时是一次调用（含所有重试）的总预算，以剩余毫秒传给服务端；排队中已过期的请求不执行、回 `DEADLINE_EXCEEDED`；服务端业务里发起的下游调用自动继承剩余预算。
- **异常**：业务异常跨进程按原类型还原（类名白名单），不认识的类降级为 `RpcRemoteException` 并保留原类名与消息；方法声明的受检异常原样抛出；远端堆栈默认截断到 8KB。
- **服务端异步到底**：业务方法返回 `CompletableFuture` 时不阻塞业务线程；执行器可选固定线程池（有界队列）或虚拟线程（并发上限），满载时回 `SERVER_OVERLOADED`（业务未执行，调用方可安全换节点）。
- **优雅停机**：服务端 摘注册 → 宽限期 → 拒新请求 → 等在途 → 关端口；客户端 拒新调用 → 等在途；提供者下线后，消费端对它的连接排空在途后再关闭。
- **Spring Boot 4 starter**：`@RpcService` / `@RpcReference`、`rpc.*` 配置（带 IDE 提示元数据）、Filter bean 自动接入、`RpcClientCustomizer` / `RpcServerCustomizer`、`SmartLifecycle` 非阻塞启动与异步停机、可选的 Actuator 健康指示器。

## 架构

```
                 ┌──────────────────────── 你的应用 ────────────────────────┐
                 │  @RpcReference 字段 / client.reference(Itf.class)        │
                 └───────────────┬─────────────────────────────────────────┘
                                 │ 动态代理（同步方法 = 在 future 上等待）
 消费端 RpcClient ┌──────────────▼──────────────────────────────────────────┐
                 │ Filter 链（内置 Deadline + 你的 ClientFilter，按 order）  │
                 │ Cluster（failfast/failover/failback，只重试传输失败）     │
                 │ LoadBalance（roundrobin/p2c） ← 服务目录 ← Registry 订阅   │
                 │ 连接池（每节点 N 条，下线节点排空后关闭）· 在途表 · 协商    │
                 └──────────────┬──────────────────────────────────────────┘
                                │ TCP · 协议 v2：头 | attachments | 信封(参数逐个序列化)
 服务端 RpcServer ┌──────────────▼──────────────────────────────────────────┐
                 │ IO 线程：解帧、解 attachments、停机 / 过载闸门（不碰 body） │
                 │ 业务执行器（固定池 + 有界队列 / 虚拟线程 + 信号量）         │
                 │ Filter 链（内置 Deadline 检查 + 你的 ServerFilter）        │
                 │ 方法表（method + paramSig）→ 按声明类型惰性反序列化参数 → 反射调用 │
                 │ 返回 future 时在完成线程上回包（不 join）                   │
                 └──────────────┬──────────────────────────────────────────┘
                                │ register / unregister（bind 成功后，后台重试）
                         ┌──────▼──────┐
                         │  ZooKeeper  │  /rpc/{interface:version:group}/providers/{host:port}
                         │  或 Nacos    │
                         └─────────────┘
 四个 SPI（META-INF/rpc/<接口全名>）：RegistryFactory · Serializer · LoadBalance · Cluster
```

## 模块

| 模块 | 作用 | 你需要依赖它吗 |
|---|---|---|
| `rpc-core` | RpcClient / RpcServer、协议编解码、Filter 链、四个 SPI 接口与负载均衡 / 容错实现。只依赖 Netty 与 slf4j-api | 必需 |
| `rpc-registry-zookeeper` | ZooKeeper 注册中心（Curator 5.8） | 二选一 |
| `rpc-registry-nacos` | Nacos 注册中心（nacos-client 2.4） | 二选一 |
| `rpc-serialize-protobuf` | Protostuff 序列化器（SPI 名 `protobuf`，缺省），推荐 | 至少一个 |
| `rpc-serialize-fastjson2` | fastjson2 序列化器（SPI 名 `fastjson2`），可读、便于调试 | 至少一个 |
| `rpc-spring-boot-starter` | Spring Boot 4 自动配置 | Boot 应用 |
| `rpc-metrics-micrometer` | Micrometer 指标：客户端 / 服务端调用 Timer（service / method / status）、在途与连接数 Gauge、可内嵌的 Prometheus `/metrics` 端点；不依赖 Spring | 要指标时 |
| `rpc-registry-tck` | Registry 契约测试（14 条），第三方注册中心实现可直接继承 | 实现注册中心时 |
| `rpc-examples` | 可运行示例：Boot provider / consumer、纯 Java provider / consumer、本机试用的嵌入式 ZooKeeper | 参考 |
| `rpc-integration-tests` | 跨模块集成测试、全仓架构规则、覆盖率汇总（不发布） | — |
| `rpc-benchmark` | JMH 微基准（序列化、负载均衡选点） | — |
| `rpc-bench-e2e` | 端到端压测 harness：按口径卡（YAML）驱动独立的 server / client 进程，输出 JSON 与由 JSON 生成的 Markdown 报告 | — |
| `rpc-bench-compare-grpc` / `rpc-bench-compare-dubbo` | 对比对象：同一契约的 gRPC（grpc-java）与 Dubbo 3（triple）服务端和 harness 的 `CallTarget` 实现，见 [docs/benchmarking.md](docs/benchmarking.md#对比对象grpc--dubbo) | — |

## 环境要求

- **JDK 21**（编译目标 21；CI 另跑一个 JDK 25 的可选任务）。
- Maven 3.9+。
- 运行示例需要一个 ZooKeeper：真实 ZooKeeper、`docker run`，或示例自带的嵌入式 ZooKeeper（无需安装任何东西）。
- 构建与测试**不需要** Docker 和本机中间件：ZooKeeper 测试用进程内 TestingServer；Nacos 测试在没有 Docker 时自动跳过。

## 快速开始

先在仓库根目录安装到本地仓库（阶段 3 发布后改为直接引用仓库坐标）：

```bash
mvn install -DskipTests
```

依赖坐标（当前为本地 SNAPSHOT）：

```xml
<dependency>
  <groupId>com.feynman.rpc</groupId>
  <artifactId>rpc-spring-boot-starter</artifactId>   <!-- 纯 Java 用 rpc-core -->
  <version>0.0.1-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>com.feynman.rpc</groupId>
  <artifactId>rpc-registry-zookeeper</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>com.feynman.rpc</groupId>
  <artifactId>rpc-serialize-protobuf</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 1. 纯 Java

接口与 DTO 放在双方共享的 API 包里（DTO 用普通类，**不要用 record**，见[已知边界](#已知边界)）。下面两段代码就是
`rpc-examples` 的 [`PlainProvider`](rpc-examples/src/main/java/com/feynman/rpc/examples/plain/PlainProvider.java) 与
[`PlainConsumer`](rpc-examples/src/main/java/com/feynman/rpc/examples/plain/PlainConsumer.java)，由集成测试 `PlainJavaExamplesTest` 跑通。

服务端：

```java
	public static RpcServer start(String zookeeper, String host, int port) {
		RpcServer server = RpcServer.builder()
				.port(port)
				.host(host)                                    // 注册给消费端的地址；null 表示自动探测本机地址
				.registry(RegistryConfig.zookeeper(zookeeper))
				.serializer("protobuf")
				.shutdownTimeout(Duration.ofSeconds(10))       // 停机时等在途请求的上限
				.build();
		server.export(InventoryService.class, new InventoryServiceImpl());
		server.start();                                    // 绑定端口后立即返回，注册在后台进行
		return server;
	}
```

```java
		RpcServer server = start(zookeeper, null, port);
		Runtime.getRuntime().addShutdownHook(new Thread(server::close, "plain-provider-shutdown")); // Ctrl+C / SIGTERM → 优雅下线
		server.registered().get(30, TimeUnit.SECONDS);    // 需要"已可被发现"时再等（例如 readiness）
```

消费端：

```java
	public static RpcClient connect(String zookeeper) {
		return RpcClient.builder()
				.registry(RegistryConfig.zookeeper(zookeeper))
				.serializer("protobuf")
				.loadBalance("p2c")
				.build();
	}

	/** 对 {@link InventoryService} 发起一组示例调用，返回每一步的结果描述。 */
	public static List<String> demo(RpcClient client) {
		InventoryService inventory = client.reference(InventoryService.class,
				ReferenceOptions.defaults().timeout(Duration.ofSeconds(3)));
		int left = inventory.reserve(2001L, 4, "plain-1");          // 多参数：long + Integer + String
		List<User> buyers = inventory.buyers(2001L);                // 泛型返回：元素就是 User
		String rejected;
		try {
			inventory.reserve(2001L, 100, "plain-2");
			rejected = "unexpected success";
		} catch (InsufficientStockException e) {                      // 业务异常按类型还原
			rejected = "InsufficientStockException: " + e.getMessage();
		}
		return List.of("left=" + left, "buyers=" + buyers, rejected);
	}
```

`RpcClient` 用完 `close()`（或 try-with-resources）：它会等在途调用完成（上限 `shutdownTimeout`），再关连接、注销订阅、关 IO 线程。

### 2. Spring Boot starter

提供方标 `@RpcService`（它元注解了 `@Component`），消费方在字段上标 `@RpcReference`：

```java
@RpcService
public class UserFacadeImpl implements UserFacade {
```

```java
@Component
public class UserGateway {

	@RpcReference(timeout = 5000)
	private UserFacade userFacade;

	public User register(int id, String name) {
		return userFacade.saveUser(new User(id, name));
	}
}
```

```yaml
rpc:
  registry:
    type: zookeeper
    address: localhost:2181
  # 首选序列化方式；classpath 上同时有 fastjson2，建连时与对端协商
  serializer: protobuf
  server:
    port: 9000
    weight: 1
    # 优雅停机等待在途请求的上限（摘注册之后、关端口之前）
    shutdown-timeout: 10s
    # 摘注册后继续服务的宽限期，让注册中心把下线推送到消费端再开始拒绝新请求（生产建议 1s）
    deregister-grace: 1s
```

容器里有 `@RpcService` 才会绑定端口；`SpringApplication.run()` 不会被 RPC 阻塞；`SIGTERM` / `context.close()` 走完整的优雅下线。
从空白 Boot 4 工程开始的完整步骤见 **[docs/getting-started.md](docs/getting-started.md)**。

### 3. 运行 rpc-examples

`rpc-examples` 有两套可运行示例（Boot 与纯 Java），默认连 `localhost:2181`。完整说明见 [rpc-examples/README.md](rpc-examples/README.md)，最短路径：

```bash
mvn install -DskipTests
cd rpc-examples
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
CP="target/classes:$(cat target/classpath.txt)"          # Windows PowerShell 见 rpc-examples/README.md

java -cp "$CP" com.feynman.rpc.examples.EmbeddedZooKeeper                  # 终端 1：本机 ZooKeeper（或 docker run -d -p 2181:2181 zookeeper:3.9）
java -cp "$CP" com.feynman.rpc.examples.provider.ProviderApplication       # 终端 2：Boot provider
java -cp "$CP" com.feynman.rpc.examples.consumer.ConsumerApplication       # 终端 3：Boot consumer，调用一次后退出
```

## 配置项

Boot 的 `rpc.*` 属性与纯 Java builder 一一对应；没写的项取 core 的缺省值（缺省值只在 core 定义一份）。

**通用**

| 属性 | builder | 缺省 | 说明 |
|---|---|---|---|
| `rpc.registry.type` | `registry(RegistryConfig)` | `zookeeper` | 注册中心 SPI 名：`zookeeper` / `nacos` |
| `rpc.registry.address` | 同上 | `localhost:2181` | 注册中心地址 |
| `rpc.registry.properties.*` | `RegistryConfig.withProperty(k, v)` | — | 实现相关：ZK 的 `session-timeout-ms`（30000）、`connection-timeout-ms`（5000）、`connect-wait-ms`（1000）、`push-merge-ms`（50）；Nacos 的 `namespace`、`username`、`password`、`connect-wait-ms`（3000） |
| `rpc.serializer` | `serializer(String)` | `protobuf`（SPI 缺省名） | 客户端首选 / 服务端兜底的序列化方式；缺省值需要 `rpc-serialize-protobuf` 在 classpath 上，只带 fastjson2 时设为 `fastjson2` |
| `rpc.serializer-options.*` | `serializerOption(k, v)` | — | 交给序列化器，如 `protobuf.allowed-packages: "com.acme.api.,com.acme.dto."`（多态位置按名加载的包白名单） |

**客户端 `rpc.client.*`**（`RpcClient.builder()`）

| 属性 | builder | 缺省 | 说明 |
|---|---|---|---|
| `load-balance` | `loadBalance` | `roundrobin` | `roundrobin` / `p2c` |
| `cluster` | `cluster` | `failfast` | `failfast` / `failover` / `failback`；只在引用声明了 `retries > 0` 时重试 |
| `connections-per-node` | `connectionsPerNode` | 2 | 到每个提供者的连接数 |
| `default-timeout` | `defaultTimeout` | 20s | 一次调用（含重试）的总预算；`@RpcReference(timeout=)` / `ReferenceOptions.timeout` 覆盖 |
| `empty-protection` | `emptyProtection` | true | 推空保护 |
| `shutdown-timeout` | `shutdownTimeout` | 10s | close 时等在途调用的上限 |
| `exception-allowlist` | `exceptionAllowlist(String...)` | — | 追加允许按类名还原的异常包前缀 |
| `io-threads` | `ioThreads` | 0 | IO 事件循环线程数；0 = Netty 缺省（2 × 可用核数），只影响本客户端 |
| `sync-wait-grace` | `syncWaitGrace` | 1s | 同步调用等待的第二道上界：最多等 deadline 剩余时间 + 该值，定时器没按时触发时也不会无限挂住（docs/pitfalls.md 第 34 条）；异步方法不受约束 |
| — | `clientFilter(Filter...)` | — | Boot 中为容器里的 `ClientFilter` bean |

引用级选项（`@RpcReference` / `ReferenceOptions`）：`version`、`group`、`timeout`（毫秒）、`retries`（缺省 0）、`inheritDeadline`（缺省 true）。

**服务端 `rpc.server.*`**（`RpcServer.builder()`）

| 属性 | builder | 缺省 | 说明 |
|---|---|---|---|
| `port` | `port` | 9000 | 0 = 随机端口，注册实际端口 |
| `host` | `host` | 自动探测 | 注册给消费端的地址；多网卡 / 容器里请显式设置 |
| `weight` | `weight` | 1 | 注册权重；`@RpcService(weight=)` 覆盖 |
| `shutdown-timeout` | `shutdownTimeout` | 10s | 停机时等在途请求的上限 |
| `deregister-grace` | `deregisterGrace` | 0 | 摘注册后、拒新请求前的宽限期；**生产建议 1s** |
| `remote-stack-trace` | `remoteStackTrace` | true | 异常回包是否带服务端堆栈 |
| `remote-stack-trace-max-bytes` | `remoteStackTraceMaxBytes` | 8192 | 回传堆栈上限（最小 256） |
| `io-threads` | `ioThreads` | 0 | IO 事件循环（worker）线程数；0 = Netty 缺省（2 × 可用核数）。业务方法在 executor 上执行，与它无关 |
| `executor.type` | `executor(ExecutorConfig)` | `fixed` | `fixed` / `virtual` |
| `executor.threads` | `ExecutorConfig.fixed(threads, queue)` | CPU 核数 × 2 | 固定池线程数 |
| `executor.queue-capacity` | 同上 | 1000 | 固定池排队上限，满了回 `SERVER_OVERLOADED` |
| `executor.max-concurrent` | `ExecutorConfig.virtualThreads(max)` | 10000 | 虚拟线程同时执行上限 |
| — | `serverFilter(Filter...)` | — | Boot 中为容器里的 `ServerFilter` bean |

**Boot 专属**：`RpcClientCustomizer` / `RpcServerCustomizer` bean 在属性与 Filter bean 之后修改 builder；
`management.health.rpc.enabled`（有 Actuator 时，缺省 true）：健康组件 `rpc`，已启动且已注册为 UP，未注册或停机中为 OUT_OF_SERVICE。
停机最长耗时约 `deregister-grace + shutdown-timeout + 5s`，`spring.lifecycle.timeout-per-shutdown-phase`（Boot 缺省 30s）必须大于它。

## 设计要点

**为什么信封和 attachments 由框架自己编码，不交给序列化器。** 帧头之后先是 attachments（字符串键值）段，再是请求信封
（service / version / group / method / paramSig，加上逐个参数的长度前缀字节）。序列化器只处理业务参数与返回值。
这样 IO 线程只读头和 attachments 就能做停机 / 过载拒绝，不必先反序列化 body；错误路径（序列化失败、协商失败）
也仍然有 requestId 与元数据可以回包；参数能按方法声明的泛型类型逐个反序列化，并推迟到业务线程、被拒绝的请求根本不解。
换序列化器不改变线上格式。

**为什么 SPI 只负责"找到类"，实例由 client / server 创建并持有。** 旧实现的注册中心、连接、在途表都是进程级单例，
类加载即连注册中心，同一 JVM 起不了第二个客户端，也关不干净。现在 `META-INF/rpc/<接口全名>` 只把名字映射到类，
有状态的实现（Registry、LoadBalance 游标、Cluster 重试线程、带配置的序列化器）由每个 `RpcClient` / `RpcServer` 各自 new、
各自在 `close()` 里释放。框架所有线程名以 `rpc-` 开头，测试断言关闭后它们全部消失。

**为什么超时是"整次调用的预算"。** `timeout` 覆盖一次调用的全部重试（gRPC deadline 语义），预算耗尽就结束，不会再换节点重发：
超时之后再重试，对非幂等调用更危险，也只会让本就过载的下游更忙。发送时把剩余毫秒写进 `rpc.timeout`，服务端用"到达时刻 + 剩余"
判定过期——用相对值而不是绝对时间戳，避免两台机器时钟偏差。在服务端业务方法里发起的下游调用自动取 min(自身超时, 上游剩余)，
上游已经放弃的请求不会让下游白干。

**为什么失败分两个通道、只重试其中一个。** 调用的 future 异常完成只表示**传输失败**：连不上、连接重置、写失败、无可用节点、
服务端过载 / 停机拒绝（这些情况下业务方法确定没执行）。只有它会让 `failover` 换节点重试，而且只在引用显式声明了 `retries > 0`
时。服务端已经执行并给出答复的错误（业务异常、`DEADLINE_EXCEEDED`、序列化失败）以及本地超时放在结果里，永不重试。
默认 `retries = 0`：非幂等调用被意外执行两次的代价，远高于少一次重试。

**为什么 Protostuff 要按声明类型构造 schema，并加类名白名单。** 探针（[docs/probes/protostuff-1.8.0.md](docs/probes/protostuff-1.8.0.md)）发现：
对 JDK 集合类调用 `RuntimeSchema.getSchema` 会静默损坏数据并永久污染全局策略；`Object` / 接口字段会按元素写全限定类名、
接收方按名加载。所以序列化器按方法签名上的 `Type` 递归构造 schema（集合 / Map 用集合 schema + 元素 schema），并为每个实例
持有一份带白名单的 ID 策略：多态位置的类名不在白名单（JDK 集合与基础类型 + `protobuf.allowed-packages`）内就拒绝加载。
这比 autoType 黑名单更稳：默认拒绝，新的利用链不需要追着补。

**为什么停机顺序是"先摘注册、再拒新请求"。** 先拒绝会让还没收到下线推送的消费端持续撞上拒绝；先摘注册，绝大多数新流量在推送到达后
自然不再进来。完整顺序：取消后台注册 → 摘注册 → 宽限期（`deregister-grace`，等推送传播）→ DRAINING（新请求回
`SERVER_OVERLOADED`，业务未执行，配了重试的调用方会换节点）→ 等在途（上限 `shutdown-timeout`）→ 关端口与连接 → 关注册中心会话。
超时仍未完成的请求会被中断，它们的调用方随连接关闭立即失败，而不是挂到自己的超时。

## 已知边界

使用前请确认这些限制对你的场景可以接受：

- **Protostuff 不支持 `record` 作为业务 DTO**（参数、返回值、它们的字段）：1.8.0 的 Unsafe 与反射两种模式都无法还原 record。用普通类（无参构造 + 可变字段）；fastjson2 无此限制。
- **Protostuff 的字段演进需要纪律**：要么全部字段加 `@Tag`，要么只在末尾追加字段；在中间插入字段会静默错位。声明为具体非 final 父类的字段会丢失子类型。
- **Nacos 实现没有在真实 Nacos 上验证过**：它通过了与 ZooKeeper 相同的契约测试代码，但这些测试需要 Docker，在开发机上一直是跳过状态（CI 的 Linux runner 会实跑）。ZooKeeper 是主实现。
- **Windows 上没有真实 SIGTERM**：`Process.destroy()` 在 Windows 上是强杀，不触发 JVM 关闭钩子；优雅停机在 Windows 上只能通过 `context.close()` / `server.close()` 验证，真实信号用例只在 Linux 上运行。
- **ZooKeeper 不可达时，下线节点会残留到会话超时**：进程退出但无法连上 ZK 摘注册时，临时节点要等服务端判定会话过期（缺省请求 30s，实际值取决于 ZK 的 tickTime 协商）才消失，期间消费端可能仍选到它（会以连接失败的形式出现）。
- **连接重置后，`retries > 0` 可能重发已经执行过的请求**：请求写出后连接断开，客户端无法知道服务端是否已执行，会按传输失败处理并重试。只对幂等方法设置 `retries`，或在业务侧用 `rpc.idempotency-key` attachment 自行去重（框架不内置去重）。
- **返回 `CompletableFuture` 的方法，用户回调在 Netty IO 线程上执行**：在 `thenApply` / `whenComplete` 里做阻塞操作会卡住该连接上的所有请求；需要阻塞时用 `thenApplyAsync(fn, yourExecutor)`。
- **还没有链路追踪与压测数据**：attachments 已预留 W3C `traceparent` 等键，但 OpenTelemetry 集成、吞吐 / 延迟数据都在[路线图](#路线图)里，本 README 不给出任何性能数字。Micrometer 调用指标已由 `rpc-metrics-micrometer` 提供（[observability.md](docs/observability.md)）。
- **压缩标志位未实现**：收到置位的请求会回 `BAD_REQUEST`；单向调用标志服务端已支持，客户端尚不发送。
- **服务端 Filter 运行在业务线程上**：IO 线程只做停机 / 执行器满载的拒绝；deadline 检查与用户 Filter 在业务执行器上运行，所以被执行器排队的请求要先拿到线程才会被 deadline 拒绝。

## 测试与证据

```bash
mvn clean install                   # 全量：单元 + 集成 + 架构规则 + 覆盖率检查（约 3~4 分钟）
```

集成测试请用 `verify` / `install` 跑，不要只跑到 `test` 阶段：泄漏断言扩展经 rpc-core 的 test-jar 共享，`test` 阶段 test-jar 尚未打包，
Maven 会把 rpc-core 的全部测试类放进集成测试的 classpath（`LeakCheckWiringTest` 会因此失败并提示）。

| 机制 | 说明 |
|---|---|
| 集成测试 | `rpc-integration-tests`：多实例路由、优雅停机（含进程内 Boot 变体与 Linux 下的真实 SIGTERM 子进程用例）、deadline 与 attachments、过载、异常映射、序列化矩阵（Protostuff 与 fastjson2 各跑一遍）、示例应用端到端。ZooKeeper 用进程内 TestingServer |
| Registry TCK | `rpc-registry-tck` 的 `RegistryContractTest`（14 条：首次推送、去重、跨 key 隔离、关闭后消失窗口、线程回收、后端不可达时订阅并在恢复后补推……），ZooKeeper / Nacos / 内存参考实现都继承它 |
| 架构规则 | ArchUnit：core 不依赖 Spring、全仓禁止 `System.setProperty`、无可变静态状态（白名单除外），每条规则带"能失败"的自测；maven-enforcer 在依赖层面禁止 core 引入 Spring |
| 编解码模糊测试 | jqwik：attachments / 信封随机往返、随机字节输入不崩溃且不泄漏 ByteBuf |
| 泄漏断言 | `RpcLeakCheckExtension`（JUnit 自动注册）：rpc-core 与集成测试的**每个测试类**结束后，`rpc-` 线程须在 5s 内归零、测试类持有的 client / server 在途为零；带阳性对照（`LeakFixtureTest`，默认 `@Disabled`） |
| 覆盖率 | JaCoCo：每个模块的测试都挂 agent；`rpc-integration-tests` 汇总报告在 `target/site/jacoco-aggregate/`；rpc-core 行覆盖率 ≥ 70% 检查两次（core 自身单元测试；全部模块测试的聚合） |
| Netty 内存泄漏 | `mvn clean install "-DargLine=-Dio.netty.leakDetection.level=paranoid" \| tee build.log`，之后 `grep "LEAK:" build.log` 应为空，且 `grep "level = PARANOID" build.log` 有输出（证明检测器确实开着） |
| 特征化探针 | 会改第三方库全局状态的探针类以 `*ProbeTest` 命名，默认不跑：`mvn test -Pprobe -pl rpc-serialize-protobuf -am` |

CI（`.github/workflows/ci.yml`）：Linux + JDK 21 全量构建（Docker 可用，Nacos 契约测试实跑）、Linux 真实 SIGTERM 用例、paranoid 泄漏检测、JDK 25 可选任务。

## 路线图

- **阶段 1 · 证据基础设施**：`docker compose up` 一条命令起 ZooKeeper、Nacos、Prometheus、Grafana、toxiproxy；Micrometer 指标 Filter 与 Grafana 面板（QPS、p99、在途、连接数、错误码）；压测 CLI 输出带环境与口径的 JSON / Markdown 报告；用 wrk / k6 交叉验证自研压测客户端（偏差 < 15%）。
  - 压测 harness 已就位：口径卡、子命令、WSL2 标准流程、报告字段与 harness 交叉验证见 [docs/benchmarking.md](docs/benchmarking.md)；README 中的压测数字只引用它生成的报告文件。
- **阶段 2 · 跑数据**：同机吞吐与 p50 / p99（对照 gRPC-Java、Dubbo triple）；慢节点下 RoundRobin 与 P2C 的 p99；杀一个提供者的错误数与恢复时间；流量突增 3 倍时过载保护的效果；每组 3 轮，附环境说明与"不可外推"声明。
- **阶段 3 · 发布**：CI 全绿；坐标发布到 Maven Central 或 GitHub Packages；空白 Boot 4 工程按接入文档 15 分钟跑通。
- **阶段 4 · 可选（每项自带对比实验）**：自适应并发限制、虚拟线程与固定池对比、Apache Fory 序列化（含 record 支持）、流式调用、OpenTelemetry 集成。

## 文档

- [docs/getting-started.md](docs/getting-started.md)：15 分钟接入指南（空白 Boot 4 工程 → provider + consumer），含常见错误对照表
- [docs/protocol.md](docs/protocol.md)：协议 v2 规格（帧头、attachments、信封、状态码、协商、连接关闭规则）
- [docs/pitfalls.md](docs/pitfalls.md)：开发中踩过的坑（现象 / 根因 / 修法 / 教训）
- [docs/benchmarking.md](docs/benchmarking.md)：端到端压测：口径卡字段、`server` / `run` / `report` / `crossval` 子命令、WSL2 标准流程、报告字段、harness 交叉验证
- [docs/observability.md](docs/observability.md)：指标：指标名与 tag、status 取值、计时口径、PromQL 示例、Grafana 面板、纯 Java 内嵌 `/metrics`；中间件与面板的一键环境见 [docker/README.md](docker/README.md)
- [docs/probes/protostuff-1.8.0.md](docs/probes/protostuff-1.8.0.md)：Protostuff 1.8.0 行为探针结果
- [rpc-examples/README.md](rpc-examples/README.md)：示例的运行方式

## 许可证

本项目代码以 [MIT](LICENSE) 许可证发布。`rpc-serialize-protobuf` 中的 `io/protostuff/runtime/AllowlistIdStrategy.java`
派生自 Protostuff 1.8.0（Apache License 2.0），继续适用 Apache 2.0，署名与修改说明见 [NOTICE](NOTICE)，许可证全文见
[licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)。
