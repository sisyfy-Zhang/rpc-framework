# 15 分钟接入指南

从一个空白的 Spring Boot 4 工程开始，接入 provider（暴露服务）与 consumer（调用服务），用 ZooKeeper 做服务发现。
本文所有 Java 与 YAML 代码都**原样取自 `rpc-examples` 模块**（集成测试 `DocsSnippetsTest` 会逐字核对，`ExamplesEndToEndTest`
会把这两个应用真正跑一遍），所以照抄就能工作。

> 不用 Spring？看 README 的[纯 Java 快速开始](../README.md#1-纯-java)，同样的接口，`RpcServer.builder()` / `RpcClient.builder()` 各十几行。

## 目录

1. [准备：JDK、构建本仓库](#1-准备)
2. [新建空白 Boot 4 工程并加依赖](#2-新建空白-boot-4-工程并加依赖)
3. [起一个 ZooKeeper](#3-起一个-zookeeper)
4. [定义接口与 DTO（以及要遵守的约束）](#4-定义接口与-dto)
5. [provider：实现并暴露服务](#5-provider)
6. [consumer：注入并调用](#6-consumer)
7. [验证调用](#7-验证调用)（[7.1 指标](#71-指标)）
8. [上线前再看一眼](#8-上线前再看一眼)
9. [常见错误对照表](#9-常见错误对照表)

## 1. 准备

- JDK 21、Maven 3.9+。
- 框架尚未发布到 Maven 仓库（阶段 3 发布后，这一步改为直接引用仓库坐标）。先把本仓库安装到本地 Maven 仓库：

```bash
git clone https://github.com/sisyfy-Zhang/rpc-framework.git
cd rpc-framework
mvn install -DskipTests
```

## 2. 新建空白 Boot 4 工程并加依赖

用 [start.spring.io](https://start.spring.io) 生成一个 Spring Boot 4.1.x、Java 21、不勾选任何依赖的 Maven 工程（或任何已有的 Boot 4 工程）。
实际项目里接口通常放在一个独立的 `xxx-api` 模块，provider 与 consumer 各是一个应用；为了 15 分钟跑通，下面把它们放在同一个工程里
（`rpc-examples` 就是这样组织的：`api` / `provider` / `consumer` 三个包）。

在 `pom.xml` 里加入：

```xml
<properties>
  <rpc.version>0.0.1-SNAPSHOT</rpc.version>   <!-- 本地 install 的快照版本 -->
</properties>

<dependencies>
  <!-- Boot 集成：@RpcService / @RpcReference、rpc.* 配置、生命周期 -->
  <dependency>
    <groupId>com.feynman.rpc</groupId>
    <artifactId>rpc-spring-boot-starter</artifactId>
    <version>${rpc.version}</version>
  </dependency>
  <!-- 注册中心：ZooKeeper（换 Nacos 用 rpc-registry-nacos 并设 rpc.registry.type=nacos） -->
  <dependency>
    <groupId>com.feynman.rpc</groupId>
    <artifactId>rpc-registry-zookeeper</artifactId>
    <version>${rpc.version}</version>
  </dependency>
  <!-- 序列化：至少一个。Protostuff 是缺省（不配 rpc.serializer 时用它）；fastjson2 可读、便于调试，两者可以同时在 classpath 上，建连时协商 -->
  <dependency>
    <groupId>com.feynman.rpc</groupId>
    <artifactId>rpc-serialize-protobuf</artifactId>
    <version>${rpc.version}</version>
  </dependency>
  <dependency>
    <groupId>com.feynman.rpc</groupId>
    <artifactId>rpc-serialize-fastjson2</artifactId>
    <version>${rpc.version}</version>
  </dependency>
  <!-- 可选：有 Actuator 时自动注册健康组件 rpc（已启动且已注册 = UP） -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
</dependencies>
```

## 3. 起一个 ZooKeeper

任选一种，监听 `localhost:2181`：

```bash
# A. Docker（推荐，和生产最接近）
docker run -d --name zk -p 2181:2181 zookeeper:3.9

# B. 不装任何东西：rpc-examples 自带的嵌入式 ZooKeeper（curator-test 的 TestingServer，仅供试验）
cd rpc-framework/rpc-examples
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" com.feynman.rpc.examples.EmbeddedZooKeeper
```

写自动化测试时，在测试里直接 `new TestingServer(true)`（依赖 `org.apache.curator:curator-test`，test scope），
把 `getConnectString()` 作为 `rpc.registry.address` 传给应用即可——本仓库的集成测试全部这样做，不依赖 Docker。

## 4. 定义接口与 DTO

接口、DTO、业务异常放在 provider 与 consumer 共享的包里：

`rpc-examples/src/main/java/com/feynman/rpc/examples/api/UserFacade.java`：

```java
package com.feynman.rpc.examples.api;

import java.util.List;

/** 面向消费端的门面服务：示例 consumer 只引用它。 */
public interface UserFacade {

	User saveUser(User user);

	void saveUsers(List<User> users);
}
```

`rpc-examples/src/main/java/com/feynman/rpc/examples/api/User.java`：

```java
package com.feynman.rpc.examples.api;

/**
 * 示例 DTO。经序列化器传输的类型用普通类（无参构造 + 可变字段）而不是 record：
 * Protostuff 1.8 无法还原 record（见 docs/pitfalls.md 第 3 条）。
 */
public class User {

	private Integer id;
	private String name;

	public User() {
	}

	public User(Integer id, String name) {
		this.id = id;
		this.name = name;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return "User{id=" + id + ", name=" + name + "}";
	}
}
```

`rpc-examples/src/main/java/com/feynman/rpc/examples/api/InsufficientStockException.java`：

```java
package com.feynman.rpc.examples.api;

/**
 * 业务异常示例：库存不足。放在 api 包里与接口一起发布——消费端 classpath 上有这个类，框架就会把服务端抛出的
 * 异常还原成同一类型（消息一致、远端堆栈挂在 cause 上），调用方按类型 catch，和本地调用一样。
 * 需要 {@code (String)} 构造器，框架用它重建异常。
 */
public class InsufficientStockException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public InsufficientStockException(String message) {
		super(message);
	}
}
```

约束（违反时的现象见[第 9 节](#9-常见错误对照表)）：

| 约束 | 原因 |
|---|---|
| 远程类型必须是**接口** | 消费端用 JDK 动态代理生成实现 |
| 经网络传输的 DTO 用**普通类**（无参构造 + 可变字段），**不要用 record** | Protostuff 1.8 无法还原 record（fastjson2 可以，但两端协商到 Protostuff 时会失败） |
| DTO 字段演进：要么全部字段加 `@Tag`，要么**只在末尾追加** | Protostuff 不加 `@Tag` 时按声明顺序编号，中间插入字段会静默错位 |
| 字段声明为接口 / 抽象类 / `Object` 时，实现类所在包要加进 `rpc.serializer-options.protobuf.allowed-packages` | 这些位置按类名加载，白名单外的类名被拒绝（安全考虑） |
| 字段不要声明为"具体的非 final 父类"去装子类实例 | Protostuff 会按声明类型编码，子类型丢失 |
| 业务异常放在接口所在包（或子包），带 `(String)` 或 `(String, Throwable)` 构造器 | 默认只还原接口所在包、`java.` / `javax.` 与框架包里的异常；其他包用 `rpc.client.exception-allowlist` 追加 |
| 方法可以重载、多参数、泛型参数 / 返回值；异步方法返回 `CompletableFuture<T>` | 协议按 `方法名 + 参数类型签名` 分发，按声明的泛型类型反序列化 |

## 5. provider

实现类标 `@RpcService`（它元注解了 `@Component`，不需要再写 `@Service`）。暴露的是容器里的最终实例，事务等切面对远程调用同样生效。
实现类里依赖的其他 bean 照常注入：

`rpc-examples/src/main/java/com/feynman/rpc/examples/provider/UserFacadeImpl.java`：

```java
package com.feynman.rpc.examples.provider;

import java.util.List;

import com.feynman.rpc.examples.api.User;
import com.feynman.rpc.examples.api.UserFacade;
import com.feynman.rpc.examples.api.UserService;
import com.feynman.rpc.starter.RpcService;

/** 暴露给消费端的门面；内部依赖的 UserService 是本地 bean 注入，不经过网络。 */
@RpcService
public class UserFacadeImpl implements UserFacade {

	private final UserService userService;

	public UserFacadeImpl(UserService userService) {
		this.userService = userService;
	}

	@Override
	public User saveUser(User user) {
		userService.save(user);
		return user;
	}

	@Override
	public void saveUsers(List<User> users) {
		userService.saveList(users);
	}
}
```

`rpc-examples/src/main/java/com/feynman/rpc/examples/provider/UserServiceImpl.java`：

```java
package com.feynman.rpc.examples.provider;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.examples.api.User;
import com.feynman.rpc.examples.api.UserService;
import com.feynman.rpc.starter.RpcService;

/**
 * 参数按方法签名上的泛型类型反序列化：远程传来的 {@code List<User>} 元素就是 User，而不是 Map / JSONObject。
 */
@RpcService
public class UserServiceImpl implements UserService {

	private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

	@Override
	public void save(User user) {
		log.info("UserServiceImpl.save called with {}", user);
	}

	@Override
	public void saveList(List<User> users) {
		log.info("UserServiceImpl.saveList called with {} users, first={}", users.size(), users.isEmpty() ? null : users.get(0).getName());
	}
}
```

启动类。示例模块里 provider 与 consumer 同在一个模块，所以用 `spring.config.name` 区分两份配置；你的工程里直接
`SpringApplication.run(ProviderApplication.class, args)` 并使用默认的 `application.yml` 即可：

`rpc-examples/src/main/java/com/feynman/rpc/examples/provider/ProviderApplication.java`：

```java
package com.feynman.rpc.examples.provider;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 服务提供方示例。{@code @RpcService} 标注的实现被 starter 暴露；RPC 服务端在容器启动完成后由
 * SmartLifecycle 非阻塞地启动，{@code run()} 正常返回，进程由 Netty 的非守护线程保活，
 * 收到 SIGTERM（或 {@code context.close()}）时 Spring 关闭容器，RpcServer 优雅下线：摘注册 → 拒新请求 → 等在途
 * （上限 {@code rpc.server.shutdown-timeout}）→ 关端口与连接 → 关注册中心。
 *
 * <p>配置在 {@code provider.yml}（同一模块里还有 consumer，用不同的 spring.config.name 区分）。
 */
@SpringBootApplication
public class ProviderApplication {

	public static void main(String[] args) {
		builder().run(args);
	}

	/** main 与集成测试共用的启动方式：测试在此基础上覆盖注册中心地址与端口。 */
	public static SpringApplicationBuilder builder() {
		return new SpringApplicationBuilder(ProviderApplication.class)
				.web(WebApplicationType.NONE)
				.properties("spring.config.name=provider");
	}
}
```

配置（示例里叫 `provider.yml`，你的工程里就是 `application.yml`）：

`rpc-examples/src/main/resources/provider.yml`：

```yaml
# 服务提供方示例配置（ProviderApplication 以 spring.config.name=provider 加载本文件）
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
    executor:
      type: fixed
      threads: 16
      # 有界队列：满了的请求回 SERVER_OVERLOADED（业务未执行，配了重试的调用方会换节点）
      queue-capacity: 1000

logging:
  level:
    com.feynman.rpc: INFO
```

要点：
- `SpringApplication.run()` 正常返回，RPC 服务端在容器刷新完成后才绑定端口；容器里没有 `@RpcService` 时不绑定端口。
- 注册在后台进行，注册中心暂时不可达不会拖住启动（端口照常服务，后台每 0.5s~10s 重试）。
- `SIGTERM` / `context.close()` 触发优雅下线：摘注册 → 宽限期 → 拒新请求 → 等在途（上限 `shutdown-timeout`）→ 关端口。
  `spring.lifecycle.timeout-per-shutdown-phase`（缺省 30s）要大于 `deregister-grace + shutdown-timeout + 5s`。

## 6. consumer

在任意 bean 的接口类型字段上标 `@RpcReference`。注入发生在初始化回调之前，`@PostConstruct` 里就能用：

`rpc-examples/src/main/java/com/feynman/rpc/examples/consumer/UserGateway.java`：

```java
package com.feynman.rpc.examples.consumer;

import org.springframework.stereotype.Component;

import com.feynman.rpc.examples.api.User;
import com.feynman.rpc.examples.api.UserFacade;
import com.feynman.rpc.starter.RpcReference;

/** 业务代码里使用远程服务的样子：字段上标 {@code @RpcReference}，像本地接口一样调用。 */
@Component
public class UserGateway {

	@RpcReference(timeout = 5000)
	private UserFacade userFacade;

	public User register(int id, String name) {
		return userFacade.saveUser(new User(id, name));
	}
}
```

业务异常按类型 catch，与本地调用写法相同：

`rpc-examples/src/main/java/com/feynman/rpc/examples/consumer/OrderGateway.java`：

```java
package com.feynman.rpc.examples.consumer;

import java.util.List;

import org.springframework.stereotype.Component;

import com.feynman.rpc.examples.api.InsufficientStockException;
import com.feynman.rpc.examples.api.InventoryService;
import com.feynman.rpc.examples.api.User;
import com.feynman.rpc.starter.RpcReference;

/** 下单路径上的远程库存调用：业务异常按类型 catch，与本地调用写法相同。 */
@Component
public class OrderGateway {

	@RpcReference(timeout = 3000)
	private InventoryService inventory;

	/** @return 下单成功返回剩余库存，库存不足返回 -1 */
	public int placeOrder(long skuId, int quantity, String orderNo) {
		try {
			return inventory.reserve(skuId, quantity, orderNo);
		} catch (InsufficientStockException e) {
			return -1;
		}
	}

	public InventoryService inventory() {
		return inventory;
	}

	public List<User> buyers(long skuId) {
		return inventory.buyers(skuId);
	}
}
```

启动后发起一次调用的 `ApplicationRunner`：

`rpc-examples/src/main/java/com/feynman/rpc/examples/consumer/DemoRunner.java`：

```java
package com.feynman.rpc.examples.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.feynman.rpc.examples.api.User;

/** 启动后发起一次示例调用；{@code examples.consumer.demo-call=false} 可关闭（集成测试自己驱动调用）。 */
@Component
@ConditionalOnProperty(prefix = "examples.consumer", name = "demo-call", havingValue = "true", matchIfMissing = true)
public class DemoRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

	private final UserGateway gateway;

	public DemoRunner(UserGateway gateway) {
		this.gateway = gateway;
	}

	@Override
	public void run(ApplicationArguments args) {
		User saved = gateway.register(1, "zzz");
		log.info("Remote saveUser returned {}", saved);
	}
}
```

`rpc-examples/src/main/java/com/feynman/rpc/examples/consumer/ConsumerApplication.java`：

```java
package com.feynman.rpc.examples.consumer;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 服务消费方示例：{@link UserGateway} 里的 {@code @RpcReference} 字段由 starter 注入远程代理，
 * {@link DemoRunner} 在启动后发起一次调用。纯消费端应用不会绑定任何端口。
 *
 * <p>配置在 {@code consumer.yml}。main 里调用完即关闭容器：示例是一次性客户端，不需要常驻。
 */
@SpringBootApplication
public class ConsumerApplication {

	public static void main(String[] args) {
		try (ConfigurableApplicationContext context = builder().run(args)) {
			// DemoRunner 已在 run() 返回前执行完毕
		}
	}

	public static SpringApplicationBuilder builder() {
		return new SpringApplicationBuilder(ConsumerApplication.class)
				.web(WebApplicationType.NONE)
				.properties("spring.config.name=consumer");
	}
}
```

`rpc-examples/src/main/resources/consumer.yml`：

```yaml
# 服务消费方示例配置（ConsumerApplication 以 spring.config.name=consumer 加载本文件）
rpc:
  registry:
    type: zookeeper
    address: localhost:2181
  # 首选序列化方式；classpath 上同时有 fastjson2，建连时与对端协商
  serializer: protobuf
  client:
    load-balance: roundrobin
    cluster: failfast
    default-timeout: 5s
    empty-protection: true

logging:
  level:
    com.feynman.rpc: INFO
```

`@RpcReference` 的属性：`version`、`group`（与 `@RpcService` 对应）、`timeout`（毫秒，整次调用含重试的总预算，0 = 用
`rpc.client.default-timeout`）、`retries`（缺省 0；只对传输失败重试，只给幂等方法开）、`inheritDeadline`（缺省 true：
在服务端业务里发起的下游调用自动取上游剩余时间与自身超时的较小者）、`awaitProvidersMs`（缺省 0，见下一小节）。

超时由每个请求的 deadline 定时器给出。同步方法另有第二道上界 `rpc.client.sync-wait-grace`（缺省 1s）：调用方线程最多等
"deadline 剩余时间 + grace"，定时器没有按时触发（如进程提交内存耗尽，见 pitfalls 第 34 条）时也会抛 `RpcDeadlineExceededException`
（消息含 `deadline timer did not fire within grace`），不会无限挂住。返回 `CompletableFuture` 的异步方法不受它约束，调用方应自带
`orTimeout`。

### 6.1 启动时等待提供者出现（awaitProvidersMs）

provider 的"注册完成"不等于 consumer 的"已经看到它"：consumer 若比 provider 先启动、先订阅，新节点要靠注册中心推送才进入
它的提供者列表，推送可能晚于启动后的第一次调用，那次调用就以 `No available provider` 失败（背景见
[踩坑档案第 29 条](pitfalls.md#29-registered--discovered提供者注册完成不等于消费者已经看到它)）。缺省行为不变：注入立即完成，
没有提供者的调用快速失败，由调用方自己决定重试与降级。

需要"启动完成即可用"时，给引用加上等待上限：

- Boot：`@RpcReference(timeout = 3000, awaitProvidersMs = 10000)`——注入时阻塞到该服务至少有一个提供者，最多 10 秒；
  超时抛 `RpcException`（消息含服务名与等待时长），bean 创建失败、应用启动失败。容器 refresh 会因此最多多等这么久。
- 纯 Java：`client.reference(UserFacade.class, ReferenceOptions.defaults().awaitProviders(Duration.ofSeconds(10)))`，
  语义相同；超时后 `RpcClient` 照常可用、可 `close()`，订阅保留，之后的推送照常生效。

等待由注册中心推送唤醒，不轮询；提供者已经可见时立即返回。它只保证"返回时至少看到一个提供者"，不保证之后一直有——
提供者全部下线时调用仍按原样快速失败。

## 7. 验证调用

先启动 provider，再启动 consumer。provider 日志里应看到（下面是在本机实际运行 rpc-examples 的输出，去掉了时间戳与进程号；地址以实际为准）：

```text
INFO  [main] com.feynman.rpc.core.RpcServer : Exported com.feynman.rpc.examples.api.InventoryService::default -> com.feynman.rpc.examples.provider.InventoryServiceImpl
INFO  [main] com.feynman.rpc.core.RpcServer : Exported com.feynman.rpc.examples.api.UserService::default -> com.feynman.rpc.examples.provider.UserServiceImpl
INFO  [main] com.feynman.rpc.core.RpcServer : Exported com.feynman.rpc.examples.api.UserFacade::default -> com.feynman.rpc.examples.provider.UserFacadeImpl
INFO  [main] com.feynman.rpc.core.RpcServer : RpcServer bound on port 9000 (advertised as 192.168.0.103:9000), services=[com.feynman.rpc.examples.api.InventoryService::default, com.feynman.rpc.examples.api.UserService::default, com.feynman.rpc.examples.api.UserFacade::default]
INFO  [main] c.f.r.e.provider.ProviderApplication : Started ProviderApplication in 2.032 seconds (process running for 2.746)
INFO  [er-registry-5-1] com.feynman.rpc.core.RpcServer : Registered com.feynman.rpc.examples.api.InventoryService::default at 192.168.0.103:9000
INFO  [er-registry-5-1] com.feynman.rpc.core.RpcServer : Registered com.feynman.rpc.examples.api.UserService::default at 192.168.0.103:9000
INFO  [er-registry-5-1] com.feynman.rpc.core.RpcServer : Registered com.feynman.rpc.examples.api.UserFacade::default at 192.168.0.103:9000
INFO  [-server-biz-4-1] c.f.r.examples.provider.UserServiceImpl : UserServiceImpl.save called with User{id=1, name=zzz}
```

consumer 启动、调用、退出：

```text
INFO  [main] c.f.rpc.core.client.ServiceDirectory : Providers of com.feynman.rpc.examples.api.UserFacade::default updated: [192.168.0.103:9000]
INFO  [main] c.f.rpc.core.client.ServiceDirectory : Providers of com.feynman.rpc.examples.api.InventoryService::default updated: [192.168.0.103:9000]
INFO  [main] c.f.r.e.consumer.ConsumerApplication : Started ConsumerApplication in 2.4 seconds (process running for 3.119)
INFO  [main] c.f.rpc.examples.consumer.DemoRunner : Remote saveUser returned User{id=1, name=zzz}
INFO  [main] com.feynman.rpc.core.RpcClient : RpcClient closed in 123ms (all in-flight calls completed)
```

ZooKeeper 里的节点结构（`zkCli.sh ls -R /rpc`）：`/rpc/{接口全名}:{version}:{group}/providers/{host:port}`，节点数据是
`weight=1&proto=2&ts=...&pid=...` 形式的元数据。停掉 provider（Ctrl+C）后节点立即消失。

### 7.1 指标

可选。`rpc-metrics-micrometer` 把每次调用记成 Micrometer Timer（`rpc.client.calls` / `rpc.server.calls`，tag 为 `service`、`method`、`status`），
在途与连接数记成 Gauge；指标名、tag 取值与 PromQL 见 [observability.md](observability.md)。

**Boot 应用**：加下面三个依赖，再在配置里暴露 Prometheus 端点（`management.endpoints.web.exposure.include` 加上 `prometheus`）。
容器里有 `MeterRegistry`（Actuator 提供）时 starter 自动注册两个指标 Filter 并绑定 Gauge，不用写代码；`rpc.metrics.enabled: false` 关闭。

```xml
<dependency>
  <groupId>com.feynman.rpc</groupId>
  <artifactId>rpc-metrics-micrometer</artifactId>
  <version>${rpc.version}</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

抓取地址是 `http://<host>:<management 端口>/actuator/prometheus`。

**纯 Java**：Filter 在 builder 上加，Gauge 用 `RpcServerMetrics.bind` / `RpcClientMetrics.bind` 绑定。下面是 `PlainProvider` 的写法
（`--metrics-port 9466` 运行它，就能被 [docker/](../docker/README.md) 里预置的 Prometheus 抓到）：

```java
	public static RpcServer startWithMetrics(String zookeeper, String host, int port, MeterRegistry registry) {
		RpcServer server = RpcServer.builder()
				.port(port)
				.host(host)
				.registry(RegistryConfig.zookeeper(zookeeper))
				.serializer("protobuf")
				.shutdownTimeout(Duration.ofSeconds(10))
				.serverFilter(new MicrometerServerFilter(registry))   // 每次调用记 Timer rpc.server.calls
				.build();
		RpcServerMetrics.bind(server, registry);                   // Gauge rpc.server.inflight / rpc.server.connections
		server.export(InventoryService.class, new InventoryServiceImpl());
		server.start();
		return server;
	}
```

没有 Web 框架的进程用 `PrometheusEndpoint`（JDK 自带的 HttpServer）暴露 `/metrics`，需要额外带上 `micrometer-registry-prometheus`：

```java
		MeterRegistry registry = PrometheusEndpoint.newRegistry();      // 需要 micrometer-registry-prometheus
		RpcServer server = startWithMetrics(zookeeper(rest), null, port(rest), registry);
		PrometheusEndpoint endpoint = new PrometheusEndpoint(metricsPort, registry);   // http://<本机>:<metricsPort>/metrics
```

## 8. 上线前再看一眼

- `rpc.server.host`：容器 / 多网卡环境显式设置，否则自动探测的地址可能是消费端连不到的那个。
- `rpc.server.deregister-grace: 1s`，并确认 `spring.lifecycle.timeout-per-shutdown-phase` 与 Kubernetes 的
  `terminationGracePeriodSeconds` 都大于 `deregister-grace + shutdown-timeout + 5s`。
- 把健康组件 `rpc` 接到 readiness：已注册才 UP，注册中心不可达或停机中为 OUT_OF_SERVICE（Actuator 在 classpath 上时自动装配）。
- 只给幂等方法设置 `retries`：连接在请求写出后断开时，客户端无法判断服务端是否已执行，重试可能导致重复执行。
- 返回 `CompletableFuture` 的方法：`thenApply` 等回调在 Netty IO 线程上执行，不要在里面阻塞；需要时用 `thenApplyAsync(fn, executor)`。
- 横切逻辑（traceId、租户、鉴权）写成 `ClientFilter` / `ServerFilter` bean，starter 会自动接入；程序化调整配置用
  `RpcClientCustomizer` / `RpcServerCustomizer` bean。

## 9. 常见错误对照表

| 现象（异常 / 日志） | 原因 | 处理 |
|---|---|---|
| 启动失败：`No extension named 'protobuf' found for type com.feynman.rpc.core.serialize.Serializer` | `rpc.serializer` 写的实现不在 classpath 上；没配 `rpc.serializer` 时缺省就是 `protobuf`，只带了 `rpc-serialize-fastjson2` 也会这样（注册中心同理：`... for type com.feynman.rpc.core.registry.RegistryFactory`） | 加对应模块依赖（`rpc-serialize-protobuf` / `rpc-registry-zookeeper` 等），或改正名字（只用 fastjson2 时设 `rpc.serializer: fastjson2`） |
| provider 日志反复出现 `Failed to create registry ...` 或 `Failed to register ... (attempt N), retrying in ...ms; port ... keeps serving but is not discoverable yet` | 注册中心不可达或地址写错；端口已经在服务，但消费端发现不了它 | 检查 `rpc.registry.address` 与网络；恢复后自动注册，无需重启。health 组件 `rpc` 此时为 OUT_OF_SERVICE |
| consumer 调用抛 `RpcException: No available provider for: ...` | 订阅到的提供者为空：provider 没启动 / 还没注册成功 / `version`、`group` 与 provider 不一致 / 注册中心不可达 | 确认 provider 已注册（日志 `Registered ... at ...`）；核对 `@RpcReference` 与 `@RpcService` 的 version / group |
| `RpcException: SERVICE_NOT_FOUND from server for ...: no such method: ...`（或 `no such service: ...`） | 两端接口版本不一致（方法名或参数类型不同） | 两端使用同一版本的 API 包；需要兼容时用新的 `version` 并行暴露 |
| `RpcDeadlineExceededException: RPC call timed out after 5000ms: ...` 或 `server rejected expired request ...` | 调用在预算内没有完成（慢、排队、网络）；后者表示请求在服务端排队时已经过期、业务方法没执行 | 加大 `@RpcReference(timeout=)` 或 `rpc.client.default-timeout`；检查服务端执行器是否打满（`queue-capacity`、`threads`） |
| `RpcDeadlineExceededException: ... the deadline timer did not fire within grace ...` | deadline 过后又等了 `sync-wait-grace` 仍没有任何结果：客户端进程的定时 / 调度没有按时运行（提交内存耗尽、长时间 STW、CPU 饿死） | 查进程与机器的内存（Windows 看提交内存 / 页面文件，Linux 看 OOM 与 swap）、GC 日志；不要靠调大 grace 掩盖 |
| `RpcOverloadedException: SERVER_OVERLOADED from ...` | 服务端执行器满载，或该节点正在停机（消息里有 `shutting down`）；业务没执行 | 扩容 / 调整执行器；停机期间的拒绝配合 `retries` 会自动换节点 |
| 拿到 `RpcRemoteException: com.acme.xxx.SomeException: ...` 而不是 `SomeException` | 异常类不在还原白名单内（默认只有接口所在包、`java.`、`javax.`、框架包），或 consumer 的 classpath 上没有这个类、它没有 `(String)` 构造器 | 把异常放到接口所在包，或配置 `rpc.client.exception-allowlist: [com.acme.xxx.]`；`remoteClassName()` 可取原类名 |
| `RpcSerializationException: ... class not allowed by protostuff allowlist: com.acme.dto.Foo ...` | DTO 里有接口 / 抽象类 / `Object` 声明的字段，实际类型不在 Protostuff 白名单内 | `rpc.serializer-options.protobuf.allowed-packages: "com.acme.dto."`（两端都配） |
| `RpcSerializationException: Request frame too large ...` 或 `SERIALIZATION_ERROR from server ...: result too large ...` | 单次请求 / 返回值编码后整帧超过 8 MiB（协议上限）；请求不会发出、也不会重试 | 分页或分块传输；大文件走对象存储，只传引用 |
| 异常消息里有 `can't get field offset on a record class` | DTO 是 record，且协商到了 Protostuff | 改成普通类（见第 4 节约束） |
| `context.close()` / SIGTERM 后进程很久才退出，日志 `still in flight after shutdownTimeout` | 有业务方法超过 `shutdown-timeout` 仍未完成，被中断 | 调大 `rpc.server.shutdown-timeout`（同时调大容器的停机等待上限），或缩短业务耗时 |
