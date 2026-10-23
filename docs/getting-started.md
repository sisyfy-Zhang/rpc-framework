# 15 分钟接入指南

从一个空白的 Spring Boot 4 工程开始，接入 provider（暴露服务）与 consumer（调用服务），用 ZooKeeper 做服务发现。
本文所有 Java 与 YAML 代码都**原样取自 `rpc-examples` 模块**（集成测试 `DocsSnippetsTest` 会逐字核对，`ExamplesEndToEndTest`
会把这两个应用真正跑一遍），所以照抄就能工作。

> 不用 Spring？看第 10 节[纯 Java](#10-不用-spring纯-java)，同样的接口，`RpcServer.builder()` / `RpcClient.builder()` 各十几行。

## 目录

1. [准备：JDK、构建本仓库](#1-准备)（[1.1 模块一览](#11-模块一览)）
2. [新建空白 Boot 4 工程并加依赖](#2-新建空白-boot-4-工程并加依赖)
3. [起一个 ZooKeeper](#3-起一个-zookeeper)
4. [定义接口与 DTO（以及要遵守的约束）](#4-定义接口与-dto)
5. [provider：实现并暴露服务](#5-provider)
6. [consumer：注入并调用](#6-consumer)
7. [验证调用](#7-验证调用)（[7.1 指标](#71-指标)）
8. [上线前再看一眼](#8-上线前再看一眼)
9. [常见错误对照表](#9-常见错误对照表)
10. [不用 Spring：纯 Java](#10-不用-spring纯-java)
11. [已知限制](#11-已知限制)

## 1. 准备

- JDK 21、Maven 3.9+。
- 依赖坐标：groupId `io.github.sisyfy-zhang`，版本 `1.0.0`，已发布到 [Maven Central](https://central.sonatype.com/namespace/io.github.sisyfy-zhang)，第 2 节的 pom 片段直接从 Central 解析。
  **groupId 与 Java 包名不同**：坐标是 `io.github.sisyfy-zhang`，代码里 import 的包是 `com.feynman.rpc.*`。
- 用已发布的 `1.0.0` 不需要 clone 本仓库。只有想用 main 上的最新代码时，才需要把本仓库安装到本地 Maven 仓库，并把下文的 `rpc.version` 换成本仓库根 `pom.xml` 的 `<version>`（main 上通常是下一个版本的 `-SNAPSHOT`）。
  第 3 步的方式 B（嵌入式 ZooKeeper）也要用到这份 clone：

```bash
git clone https://github.com/sisyfy-Zhang/rpc-framework.git
cd rpc-framework
mvn install -DskipTests
```

### 1.1 模块一览

发布到 Central 的模块都在 `rpc-bom` 里；后五行是仓库内部模块，不发布。

| 模块 | 作用 | 你需要依赖它吗 |
|---|---|---|
| `rpc-core` | RpcClient / RpcServer、协议编解码、Filter 链、四个 SPI 接口与负载均衡 / 容错实现。只依赖 Netty 与 slf4j-api | 必需 |
| `rpc-bom` | 物料清单（BOM）：`<scope>import</scope>` 后各 rpc-* 模块不用写版本，只管本框架模块、不覆盖第三方版本（用法见[第 2 节](#2-新建空白-boot-4-工程并加依赖)） | 推荐 |
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
| `rpc-bench-compare-grpc` / `rpc-bench-compare-dubbo` | 对比对象：同一契约的 gRPC（grpc-java）与 Dubbo 3（triple）服务端和 harness 的 `CallTarget` 实现，见 [docs/benchmarking.md](benchmarking.md#对比对象grpc--dubbo) | — |

## 2. 新建空白 Boot 4 工程并加依赖

用 [start.spring.io](https://start.spring.io) 生成一个 Spring Boot 4.1.x、Java 21、不勾选任何依赖的 Maven 工程（或任何已有的 Boot 4 工程）。
实际项目里接口通常放在一个独立的 `xxx-api` 模块，provider 与 consumer 各是一个应用；为了 15 分钟跑通，下面把它们放在同一个工程里
（`rpc-examples` 就是这样组织的：`api` / `provider` / `consumer` 三个包）。

- 下文每段代码前标的是它在 `rpc-examples` 里的路径；照抄时把前缀 `rpc-examples/` 换成你的工程根目录，**保留包名**
  `com.feynman.rpc.examples.*`（要换成自己的包名就三个包一起换，并同步改 import）。
- 删掉 start.spring.io 生成的 `DemoApplication` 与 `src/test` 下的 `DemoApplicationTests`：同一工程里会有 provider、consumer
  两个启动类（第 5、6 节），多出来的第三个 main 只会让 `mvn spring-boot:run` 更难选；删了 `DemoApplication` 而留着
  `DemoApplicationTests` 会让 `mvn test` 找不到 `@SpringBootConfiguration`。

在 `pom.xml` 里加入（先导入 BOM `rpc-bom`，各 rpc-* 模块就不用逐个写版本；BOM 只管本框架模块，不覆盖 Spring Boot 管理的第三方版本）：

```xml
<properties>
  <rpc.version>1.0.0</rpc.version>   <!-- 用本地 install 的版本时改成本仓库根 pom.xml 的 <version> -->
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.sisyfy-zhang</groupId>
      <artifactId>rpc-bom</artifactId>
      <version>${rpc.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- Boot 集成：@RpcService / @RpcReference、rpc.* 配置、生命周期 -->
  <dependency>
    <groupId>io.github.sisyfy-zhang</groupId>
    <artifactId>rpc-spring-boot-starter</artifactId>
  </dependency>
  <!-- 注册中心：ZooKeeper（换 Nacos 用 rpc-registry-nacos 并设 rpc.registry.type=nacos） -->
  <dependency>
    <groupId>io.github.sisyfy-zhang</groupId>
    <artifactId>rpc-registry-zookeeper</artifactId>
  </dependency>
  <!-- 序列化：至少一个。Protostuff 是缺省（不配 rpc.serializer 时用它）；fastjson2 可读、便于调试，两者可以同时在 classpath 上，建连时协商 -->
  <dependency>
    <groupId>io.github.sisyfy-zhang</groupId>
    <artifactId>rpc-serialize-protobuf</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.sisyfy-zhang</groupId>
    <artifactId>rpc-serialize-fastjson2</artifactId>
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

（Windows 的 classpath 分隔符是 `;`，PowerShell 写法见 [rpc-examples/README.md](../rpc-examples/README.md#运行)。）

写自动化测试时，在测试里直接 `new TestingServer(true)`（依赖 `org.apache.curator:curator-test`，test scope），
把 `getConnectString()` 作为 `rpc.registry.address` 传给应用即可——本仓库的集成测试全部这样做，不依赖 Docker。

## 4. 定义接口与 DTO

接口、DTO、业务异常放在 provider 与 consumer 共享的包里（本节 5 个文件：三个接口、一个 DTO、一个业务异常——下文的 provider 与
consumer 代码都依赖它们，少一个就编译不过）：

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

`rpc-examples/src/main/java/com/feynman/rpc/examples/api/UserService.java`：

```java
package com.feynman.rpc.examples.api;

import java.util.List;

/** 领域服务：provider 内部被 UserFacade 本地调用，同时也对外暴露（演示一个进程暴露多个接口）。 */
public interface UserService {

	void save(User user);

	void saveList(List<User> users);
}
```

`rpc-examples/src/main/java/com/feynman/rpc/examples/api/InventoryService.java`：

```java
package com.feynman.rpc.examples.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 库存服务（下单 → 预占库存的调用形态）：多参数、基本类型与包装类型混用、泛型返回、异步方法、业务异常。
 * 服务端按"方法名 + 参数类型签名"分发、按声明的泛型类型逐个反序列化参数，所以这些写法与本地接口没有区别。
 */
public interface InventoryService {

	/**
	 * 预占库存。
	 *
	 * @return 预占后的剩余库存
	 * @throws InsufficientStockException 库存不足（业务失败，不重试）
	 */
	int reserve(long skuId, Integer quantity, String orderNo);

	/** 某 SKU 的购买者（按预占顺序）。 */
	List<User> buyers(long skuId);

	/** 同上，异步形态：返回值的声明类型是 {@code CompletableFuture<List<User>>}，框架按 T = List&lt;User&gt; 反序列化。 */
	CompletableFuture<List<User>> buyersAsync(long skuId);
}
```

`rpc-examples/src/main/java/com/feynman/rpc/examples/api/User.java`：

```java
package com.feynman.rpc.examples.api;

/**
 * 示例 DTO。经序列化器传输的类型用普通类（无参构造 + 可变字段）而不是 record：
 * Protostuff 1.8 无法还原 record。
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

`rpc-examples/src/main/java/com/feynman/rpc/examples/provider/InventoryServiceImpl.java`：

```java
package com.feynman.rpc.examples.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.feynman.rpc.examples.api.InsufficientStockException;
import com.feynman.rpc.examples.api.InventoryService;
import com.feynman.rpc.examples.api.User;
import com.feynman.rpc.starter.RpcService;

/** 内存版库存：每个 SKU 初始 10 件。 */
@RpcService
public class InventoryServiceImpl implements InventoryService {

	private static final int INITIAL_STOCK = 10;

	private final Map<Long, Integer> stock = new ConcurrentHashMap<>();
	private final Map<Long, List<User>> buyers = new ConcurrentHashMap<>();

	@Override
	public synchronized int reserve(long skuId, Integer quantity, String orderNo) {
		int left = stock.getOrDefault(skuId, INITIAL_STOCK);
		if (quantity == null || quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive: " + quantity);
		}
		if (left < quantity) {
			throw new InsufficientStockException("sku " + skuId + " has " + left + " left, order " + orderNo + " wants " + quantity);
		}
		stock.put(skuId, left - quantity);
		List<User> list = buyers.computeIfAbsent(skuId, k -> new ArrayList<>());
		list.add(new User(list.size() + 1, orderNo));
		return left - quantity;
	}

	@Override
	public synchronized List<User> buyers(long skuId) {
		return new ArrayList<>(buyers.getOrDefault(skuId, List.of()));
	}

	@Override
	public CompletableFuture<List<User>> buyersAsync(long skuId) {
		return CompletableFuture.completedFuture(buyers(skuId));
	}
}
```

启动类。provider 与 consumer 同在一个工程，各有一份配置，所以用 `spring.config.name` 区分（provider 读 `provider.yml`，
consumer 读 `consumer.yml`），照抄即可。以后拆成两个独立应用时，各自 `SpringApplication.run(XxxApplication.class, args)`、
用默认的 `application.yml`，去掉 `spring.config.name`：

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

配置放在 `src/main/resources/provider.yml`（不是 `application.yml`：同一工程里 consumer 也会读到 `application.yml`）：

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

# Actuator：health 组件 rpc（已注册 = UP；尚未注册、注册丢失或停机中 = OUT_OF_SERVICE）接进 readiness 组，并列出组件明细
management:
  endpoint:
    health:
      show-components: always
      group:
        readiness:
          include: readinessState,rpc

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
"deadline 剩余时间 + grace"，定时器没有按时触发（如进程提交内存耗尽）时也会抛 `RpcDeadlineExceededException`
（消息含 `deadline timer did not fire within grace`），不会无限挂住。返回 `CompletableFuture` 的异步方法不受它约束，调用方应自带
`orTimeout`。

### 6.1 启动时等待提供者出现（awaitProvidersMs）

provider 的"注册完成"不等于 consumer 的"已经看到它"：consumer 若比 provider 先启动、先订阅，新节点要靠注册中心推送才进入
它的提供者列表，推送可能晚于启动后的第一次调用，那次调用就以 `No available provider` 失败。缺省行为不变：注入立即完成，
没有提供者的调用快速失败，由调用方自己决定重试与降级。

需要"启动完成即可用"时，给引用加上等待上限：

- Boot：`@RpcReference(timeout = 3000, awaitProvidersMs = 10000)`——注入时阻塞到该服务至少有一个提供者，最多 10 秒；
  超时抛 `RpcException`（消息含服务名与等待时长），bean 创建失败、应用启动失败。容器 refresh 会因此最多多等这么久。
- 纯 Java：`client.reference(UserFacade.class, ReferenceOptions.defaults().awaitProviders(Duration.ofSeconds(10)))`，
  语义相同；超时后 `RpcClient` 照常可用、可 `close()`，订阅保留，之后的推送照常生效。

等待由注册中心推送唤醒，不轮询；提供者已经可见时立即返回。它只保证"返回时至少看到一个提供者"，不保证之后一直有——
提供者全部下线时调用仍按原样快速失败。

## 7. 验证调用

在工程根目录开两个终端，先启动 provider（常驻，Ctrl+C 优雅下线），再启动 consumer（调用一次后退出）。工程里有两个启动类，
`spring-boot:run` 必须指定 `main-class`，否则报 `Unable to find a single main class`：

```bash
# 终端 1：provider
mvn spring-boot:run -Dspring-boot.run.main-class=com.feynman.rpc.examples.provider.ProviderApplication
# 终端 2：consumer
mvn spring-boot:run -Dspring-boot.run.main-class=com.feynman.rpc.examples.consumer.ConsumerApplication
```

同一个原因，这个"两个应用放一个工程"的布局不能直接 `mvn package` 成可执行 jar（`repackage` 同样报 `Unable to find a single main class`）：
它只用来试跑；要部署时按第 2 节说的拆成独立应用，各自一个启动类。

provider 日志里应看到（下面是在本机实际运行 rpc-examples 的输出，去掉了时间戳与进程号；地址以实际为准）：

```text
INFO  [main] com.feynman.rpc.core.RpcServer : Exported com.feynman.rpc.examples.api.InventoryService::default -> com.feynman.rpc.examples.provider.InventoryServiceImpl
INFO  [main] com.feynman.rpc.core.RpcServer : Exported com.feynman.rpc.examples.api.UserService::default -> com.feynman.rpc.examples.provider.UserServiceImpl
INFO  [main] com.feynman.rpc.core.RpcServer : Exported com.feynman.rpc.examples.api.UserFacade::default -> com.feynman.rpc.examples.provider.UserFacadeImpl
INFO  [main] com.feynman.rpc.core.RpcServer : RpcServer bound on port 9000 (advertised as 192.168.0.103:9000), services=[com.feynman.rpc.examples.api.InventoryService::default, com.feynman.rpc.examples.api.UserService::default, com.feynman.rpc.examples.api.UserFacade::default]
INFO  [main] c.f.r.e.provider.ProviderApplication : Started ProviderApplication in 2.975 seconds (process running for 3.939)
INFO  [er-registry-5-1] com.feynman.rpc.core.RpcServer : Registered com.feynman.rpc.examples.api.InventoryService::default at 192.168.0.103:9000
INFO  [er-registry-5-1] com.feynman.rpc.core.RpcServer : Registered com.feynman.rpc.examples.api.UserService::default at 192.168.0.103:9000
INFO  [er-registry-5-1] com.feynman.rpc.core.RpcServer : Registered com.feynman.rpc.examples.api.UserFacade::default at 192.168.0.103:9000
WARN  [-server-biz-4-2] c.f.rpc.core.server.ServiceDispatcher : com.feynman.rpc.examples.api.InventoryService::default#reserve (request 2) threw com.feynman.rpc.examples.api.InsufficientStockException: sku 3001 has 7 left, order order-2 wants 100
INFO  [-server-biz-4-4] c.f.rpc.examples.provider.TenantFilter : buyers called for tenant acme
INFO  [-server-biz-4-5] c.f.r.examples.provider.UserServiceImpl : UserServiceImpl.save called with User{id=1, name=zzz}
```

（`WARN` 那一行是业务异常的服务端日志：异常照常回给调用方，见下面的验证清单。）

consumer 启动、调用、退出：

```text
INFO  [main] c.f.rpc.core.client.ServiceDirectory : Providers of com.feynman.rpc.examples.api.InventoryService::default updated: [192.168.0.103:9000]
INFO  [main] c.f.rpc.core.client.ServiceDirectory : Providers of com.feynman.rpc.examples.api.UserFacade::default updated: [192.168.0.103:9000]
INFO  [main] c.f.r.e.consumer.ConsumerApplication : Started ConsumerApplication in 3.487 seconds (process running for 4.328)
INFO  [main] c.f.r.e.consumer.CallShapesRunner : left=7
INFO  [main] c.f.r.e.consumer.CallShapesRunner : InsufficientStockException: sku 3001 has 7 left, order order-2 wants 100
INFO  [main] c.f.r.e.consumer.CallShapesRunner : async buyers=[User{id=1, name=order-1}]
INFO  [main] c.f.r.e.consumer.CallShapesRunner : buyers(tenant=acme)=1
INFO  [main] c.f.rpc.examples.consumer.DemoRunner : Remote saveUser returned User{id=1, name=zzz}
INFO  [main] com.feynman.rpc.core.RpcClient : RpcClient closed in 130ms (all in-flight calls completed)
```

ZooKeeper 里的节点结构（`zkCli.sh ls -R /rpc`）：`/rpc/{接口全名}:{version}:{group}/providers/{host:port}`，节点数据是
`weight=1&proto=2&ts=...&pid=...` 形式的元数据。停掉 provider（Ctrl+C）后节点立即消失。

**验证清单**。`DemoRunner` 只发了一次同步调用；业务异常跨进程、异步、attachments 三种形态由下面这个 runner 演示（consumer
启动时与 `DemoRunner` 一起执行，任何一步失败都会让启动失败，日志就是上面 `CallShapesRunner` 那四行），provider 侧用一个
`ServerFilter` 读 attachment（`ServerFilter` / `ClientFilter` bean 由 starter 自动接入）。这两个文件也照抄进工程：

`rpc-examples/src/main/java/com/feynman/rpc/examples/consumer/CallShapesRunner.java`：

```java
package com.feynman.rpc.examples.consumer;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.feynman.rpc.core.RpcContext;
import com.feynman.rpc.examples.api.InsufficientStockException;
import com.feynman.rpc.examples.api.InventoryService;
import com.feynman.rpc.examples.api.User;

/** 启动后再演示三种调用形态：业务异常跨进程、异步、attachments（同步调用见 {@link DemoRunner}）。 */
@Component
@ConditionalOnProperty(prefix = "examples.consumer", name = "demo-call", havingValue = "true", matchIfMissing = true)
public class CallShapesRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(CallShapesRunner.class);

	private final OrderGateway orders;

	public CallShapesRunner(OrderGateway orders) {
		this.orders = orders;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		demo(3001L).forEach(line -> log.info("{}", line));
	}

	/** 对同一个 SKU（初始 10 件）依次调用，返回每一步的结果。 */
	public List<String> demo(long skuId) throws Exception {
		InventoryService inventory = orders.inventory();
		int left = inventory.reserve(skuId, 3, "order-1");
		String rejected;
		try {
			inventory.reserve(skuId, 100, "order-2");
			rejected = "unexpected success";
		} catch (InsufficientStockException e) {                      // 业务异常按类型还原；远端堆栈在 e.getCause()
			rejected = "InsufficientStockException: " + e.getMessage();
		}
		List<User> buyers = inventory.buyersAsync(skuId).get(5, TimeUnit.SECONDS);   // 异步：返回 CompletableFuture
		RpcContext.setAttachment("tenant", "acme");                  // 只随紧接着的下一次调用发出，发出即清空
		int tagged = inventory.buyers(skuId).size();                 // provider 的 TenantFilter 读到 tenant=acme
		return List.of("left=" + left, rejected, "async buyers=" + buyers, "buyers(tenant=acme)=" + tagged);
	}
}
```

`rpc-examples/src/main/java/com/feynman/rpc/examples/provider/TenantFilter.java`：

```java
package com.feynman.rpc.examples.provider;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.feynman.rpc.core.filter.Invocation;
import com.feynman.rpc.core.filter.Invoker;
import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.filter.ServerFilter;

/**
 * 服务端 Filter 示例：读取调用方用 {@code RpcContext.setAttachment("tenant", ...)} 带来的 attachment。
 * ServerFilter bean 由 starter 自动接入。Filter 里从 {@link Invocation} 读；业务方法里用
 * {@code RpcContext.getServerAttachment("tenant")}（入站上下文在链尾、调用业务方法前才绑定到线程上）。
 */
@Component
public class TenantFilter implements ServerFilter {

	private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

	private volatile String lastTenant;

	@Override
	public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
		String tenant = invocation.getAttachment("tenant");
		lastTenant = tenant;
		if (tenant != null) {
			log.info("{} called for tenant {}", invocation.methodName(), tenant);
		}
		return next.invoke(invocation);
	}

	/** 最近一次调用带来的 tenant，没带为 null（集成测试用它确认 attachment 只随下一次调用发出）。 */
	public String lastTenant() {
		return lastTenant;
	}
}
```

要点：
- 业务异常按类型还原：消息与服务端一致，远端堆栈挂在 `e.getCause()` 上；异常类要在接口所在包（第 4 节约束）。
- 异步方法的 `thenApply` 等回调在 IO 线程上执行，不要在里面阻塞（第 8 节）。
- `RpcContext.setAttachment` 只对当前线程紧接着的下一次调用生效，发出即清空，不会串到之后的调用；服务端业务方法里用
  `RpcContext.getServerAttachment(key)` 读，Filter 里用 `invocation.getAttachment(key)` 读。

### 7.1 指标

可选。`rpc-metrics-micrometer` 把每次调用记成 Micrometer Timer（`rpc.client.calls` / `rpc.server.calls`，tag 为 `service`、`method`、`status`），
在途与连接数记成 Gauge；指标名、tag 取值与 PromQL 见 [observability.md](observability.md)。

**Boot 应用**：加下面四个依赖，再暴露 Prometheus 端点（`management.endpoints.web.exposure.include` 加上 `prometheus`）。
容器里有 `MeterRegistry`（Actuator 提供）时 starter 自动注册两个指标 Filter 并绑定 Gauge，不用写代码；`rpc.metrics.enabled: false` 关闭。
`/actuator/prometheus` 是 HTTP 端点，需要 Web 服务器：本文的 `ProviderApplication` 用 `WebApplicationType.NONE` 启动、
第 2 步也没有 Web 依赖，所以这里要加 `spring-boot-starter-webmvc`（Boot 4 的 Spring MVC starter），启动时再把应用类型切回 servlet。

```xml
<dependency>
  <groupId>io.github.sisyfy-zhang</groupId>
  <artifactId>rpc-metrics-micrometer</artifactId>   <!-- 版本由第 2 步导入的 rpc-bom 管理 -->
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webmvc</artifactId>   <!-- 提供 HTTP 服务器；已是 Web 应用可省略 -->
</dependency>
```

用命令行参数覆盖 `WebApplicationType.NONE` 并暴露端点（也可以写进 `provider.yml`），再跑一次 consumer 后抓取：

```bash
mvn spring-boot:run -Dspring-boot.run.main-class=com.feynman.rpc.examples.provider.ProviderApplication "-Dspring-boot.run.arguments=--spring.main.web-application-type=servlet --management.endpoints.web.exposure.include=health,prometheus"
curl -s localhost:8080/actuator/prometheus | grep '^rpc_server_calls_seconds_count'
# rpc_server_calls_seconds_count{method="buyers",service="com.feynman.rpc.examples.api.InventoryService",status="OK"} 1
# rpc_server_calls_seconds_count{method="buyersAsync",service="com.feynman.rpc.examples.api.InventoryService",status="OK"} 1
# rpc_server_calls_seconds_count{method="reserve",service="com.feynman.rpc.examples.api.InventoryService",status="BIZ_EXCEPTION"} 1
# rpc_server_calls_seconds_count{method="reserve",service="com.feynman.rpc.examples.api.InventoryService",status="OK"} 1
# rpc_server_calls_seconds_count{method="saveUser",service="com.feynman.rpc.examples.api.UserFacade",status="OK"} 1
```

（这 5 行来自第 7 节 consumer 的一次运行：`DemoRunner` 的 `saveUser`，加上 `CallShapesRunner` 的四次 `InventoryService` 调用；
业务异常记为 `status="BIZ_EXCEPTION"`。）

抓取地址是 `http://<host>:<management 端口>/actuator/prometheus`（未单独设 `management.server.port` 时就是应用的 HTTP 端口，缺省 8080）。

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
- 把健康组件 `rpc` 接到 readiness（Actuator 在 classpath 上时自动装配）。示例的 `provider.yml` 已经这样配（Boot 缺省不列出各组件，
  `show-components: always` 之后才能按路径读 `/actuator/health/rpc`）：

```yaml
# Actuator：health 组件 rpc（已注册 = UP；尚未注册、注册丢失或停机中 = OUT_OF_SERVICE）接进 readiness 组，并列出组件明细
management:
  endpoint:
    health:
      show-components: always
      group:
        readiness:
          include: readinessState,rpc
```

  启动后全部服务注册成功才 UP；尚未注册成功（启动时注册中心不可达，后台在重试）、注册过之后节点丢失尚未重建，或停机中为
  OUT_OF_SERVICE。"节点丢失"目前只有 ZooKeeper 实现会报告：会话过期时临时节点随之消失，health 立即转 OUT_OF_SERVICE，
  节点在新会话上重建后回到 UP；ZooKeeper 整体不可达时，要等会话超时（`session-timeout-ms`，缺省 30s）Curator 才判定会话丢失，
  这之前节点在服务端仍然存在。Nacos 实现不报告（nacos-client 没有公开的连接事件），断连期间仍报 UP。
- 只给幂等方法设置 `retries`：连接在请求写出后断开时，客户端无法判断服务端是否已执行，重试可能导致重复执行。
- 返回 `CompletableFuture` 的方法：`thenApply` 等回调在 Netty IO 线程上执行，不要在里面阻塞；需要时用 `thenApplyAsync(fn, executor)`。
  在这些回调里调用**同步**方法会直接抛 `IllegalStateException`（"called on this RpcClient's IO event-loop thread"）——阻塞事件循环会让
  它上面的所有连接停摆，嵌套调用的响应若也由它读取就永远读不到；回调里请调异步方法（`thenCompose`），或先切到自己的执行器。
- `rpc.server.executor.type: direct`（`ExecutorConfig.direct()`）是 opt-in 的非缺省选项：业务方法直接在收到请求的 IO 线程上执行，省掉两次线程交接。
  **业务方法阻塞会卡住同一事件循环上的所有连接，仅适用于纯 CPU、微秒级的方法；与 gRPC directExecutor 同类。** 在业务方法里调下游 RPC（同步）、
  读数据库、睡眠、等锁都算阻塞；拿不准就保持缺省的 `fixed`。`max-concurrent`（缺省 10000）约束在途请求数（主要是返回未完成 future 的异步方法），超出回 `SERVER_OVERLOADED`。
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

## 10. 不用 Spring：纯 Java

依赖把第 2 节的 `rpc-spring-boot-starter` 换成 `rpc-core`（版本同样由 `rpc-bom` 管理），注册中心与序列化模块不变。

接口与 DTO 放在双方共享的 API 包里（DTO 用普通类，**不要用 record**，见[第 4 节](#4-定义接口与-dto)与[第 11 节](#protostuff不支持-record字段演进需要纪律)）。下面两段代码就是
`rpc-examples` 的 [`PlainProvider`](../rpc-examples/src/main/java/com/feynman/rpc/examples/plain/PlainProvider.java) 与
[`PlainConsumer`](../rpc-examples/src/main/java/com/feynman/rpc/examples/plain/PlainConsumer.java)，由集成测试 `PlainJavaExamplesTest` 跑通。
其中的接口 `InventoryService`、实现 `InventoryServiceImpl`、DTO `User` 与业务异常 `InsufficientStockException` 的完整代码见
[第 4、5 节](#4-定义接口与-dto)。

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

运行这两个示例（与 Boot 版本可同时运行，纯 Java provider 用 9100 端口）见 [rpc-examples/README.md「运行」](../rpc-examples/README.md#运行)。

## 11. 已知限制

使用前请确认这些限制对你的场景可以接受。性能数字的口径另见 README「[性能与证据](../README.md#性能与证据)」。

### Protostuff：不支持 record，字段演进需要纪律

**不支持 `record` 作为业务 DTO**（参数、返回值、它们的字段）：1.8.0 的 Unsafe 与反射两种模式都无法还原 record。用普通类（无参构造 + 可变字段）；fastjson2 无此限制。

**字段演进需要纪律**：要么全部字段加 `@Tag`，要么只在末尾追加字段；在中间插入字段会静默错位。声明为具体非 final 父类的字段会丢失子类型。

### Nacos 实现没有在真实 Nacos 上验证过

它通过了与 ZooKeeper 相同的契约测试代码，但这些测试需要 Docker，在开发机上一直是跳过状态（CI 的 Linux runner 会实跑）。ZooKeeper 是主实现。

### Windows 上没有真实 SIGTERM

`Process.destroy()` 在 Windows 上是强杀，不触发 JVM 关闭钩子；优雅停机在 Windows 上只能通过 `context.close()` / `server.close()` 验证，真实信号用例只在 Linux 上运行。

### ZooKeeper 不可达时，下线节点会残留到会话超时

进程退出但无法连上 ZK 摘注册时，临时节点要等服务端判定会话过期（缺省请求 30s，实际值取决于 ZK 的 tickTime 协商）才消失，期间消费端可能仍选到它（会以连接失败的形式出现）。

### 连接重置后，retries > 0 可能重发已经执行过的请求

请求写出后连接断开，客户端无法知道服务端是否已执行，会按传输失败处理并重试。只对幂等方法设置 `retries`，或在业务侧用 `rpc.idempotency-key` attachment 自行去重（框架不内置去重）。

### 返回 CompletableFuture 的方法，用户回调在 Netty IO 线程上执行

在 `thenApply` / `whenComplete` 里做阻塞操作会卡住该连接上的所有请求；需要阻塞时用 `thenApplyAsync(fn, yourExecutor)`。

### 离群摘除只按失败、按时间放回

离群摘除在 1.0.0 之后加入 main（缺省开）。进程存活但不回包、被 kill 但注册中心还没摘时，坏节点在连续 5 个坏结果（或 10 秒内过半失败）后被摘，摘除期间不再分到流量；
但它**不做半开探测与主动健康检查**——到期直接放回，若节点仍坏，放回后的前几个请求会再失败一次才再被摘（时长按次数倍增到 60s）；**不按延迟摘除**（慢节点交给 `p2c`）；
只有一个提供者的服务永远不摘（最多摘一半、至少留 1 个）；没有服务级熔断。实验见 [docs/probes/outlier-detection.md](probes/outlier-detection.md)。

### 固定池过载时可能"拥塞崩溃"

执行器只按队列长度拒绝，不看排队等待。若"排队上限 × 业务时长 / 线程数 ≥ 调用超时"，过载时挤进队列的请求轮到执行时调用方已经超时，
服务端满负荷却几乎没有成功（实测业务 10ms、缺省 10 线程 + 队列 1000、超时 1s、闭环 1500 并发：每秒约 8.4 万次拒绝，成功率约 0）。阻塞型业务按
"线程数 ≥ 阻塞时长 × 峰值 QPS、排队等待远小于超时"配置，或换 virtual；排队等待感知的准入 / 自适应限流在后续计划里。见 [docs/probes/executor-blocking.md](probes/executor-blocking.md)。

### 服务端 Filter 运行在业务线程上

IO 线程只做停机 / 执行器满载的拒绝；deadline 检查与用户 Filter 在业务执行器上运行，所以被执行器排队的请求要先拿到线程才会被 deadline 拒绝。

### 尚未实现：链路追踪、压缩、客户端单向调用

**还没有链路追踪**：attachments 已预留 W3C `traceparent` 等键，OpenTelemetry 集成在后续计划里。Micrometer 调用指标已由 `rpc-metrics-micrometer` 提供（[observability.md](observability.md)）。性能数字只来自一台笔记本的同机 loopback，见 README「[性能与证据](../README.md#性能与证据)」的"不可外推"。

**压缩标志位未实现**：收到置位的请求会回 `BAD_REQUEST`；单向调用标志服务端已支持，客户端尚不发送。
