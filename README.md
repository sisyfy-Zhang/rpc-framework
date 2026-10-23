# rpc-framework

[![CI](https://github.com/sisyfy-Zhang/rpc-framework/actions/workflows/ci.yml/badge.svg)](https://github.com/sisyfy-Zhang/rpc-framework/actions/workflows/ci.yml) [![CodeQL](https://github.com/sisyfy-Zhang/rpc-framework/actions/workflows/codeql.yml/badge.svg)](https://github.com/sisyfy-Zhang/rpc-framework/actions/workflows/codeql.yml) [![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/sisyfy-Zhang/rpc-framework/badge)](https://scorecard.dev/viewer/?uri=github.com/sisyfy-Zhang/rpc-framework) [![codecov](https://codecov.io/gh/sisyfy-Zhang/rpc-framework/graph/badge.svg)](https://codecov.io/gh/sisyfy-Zhang/rpc-framework) [![Maven Central](https://img.shields.io/maven-central/v/io.github.sisyfy-zhang/rpc-core)](https://central.sonatype.com/artifact/io.github.sisyfy-zhang/rpc-core) [![javadoc](https://javadoc.io/badge2/io.github.sisyfy-zhang/rpc-core/1.0.0/javadoc.svg)](https://javadoc.io/doc/io.github.sisyfy-zhang/rpc-core) [![License](https://img.shields.io/github/license/sisyfy-Zhang/rpc-framework)](LICENSE) ![JDK 21+](https://img.shields.io/badge/JDK-21%2B-blue)

可被真实项目依赖的 Java RPC 库：Netty 传输、自研二进制协议 v2、ZooKeeper / Nacos 服务发现、Spring Boot 4 starter；性能数字与故障注入都有归档的实测证据。

简体中文 | [English](README.en.md)

> `1.0.0` 已发布到 [Maven Central](https://central.sonatype.com/namespace/io.github.sisyfy-zhang)；main 上是下一版本（含离群摘除）。
> 核心是纯 Java，不依赖任何容器，Spring Boot 只是外面的一层。

## 架构

![架构：调用方 → 消费端 Filter 链 / Cluster / 负载均衡与离群摘除 / 连接池 → 协议 v2 → 服务端 IO 线程 / 业务执行器 / Filter 链 / 服务实现；旁路注册中心与指标](docs/images/architecture.svg)

调用经动态代理进入消费端 `RpcClient`：Filter 链（内置 deadline）→ Cluster 容错（只重试传输失败）→ 负载均衡与离群摘除 → 每节点 N 条连接的连接池。
服务端 IO 线程只解帧头与 attachments、做停机 / 过载闸门，业务在独立执行器上按声明类型惰性反序列化参数并调用，返回 `CompletableFuture` 时在完成线程上回包。
线上格式是协议 v2：23 字节定长头 + 框架自编码的 attachments 与请求 / 响应信封，参数逐个序列化（[规格](docs/protocol.md)）；服务端绑定端口后向 ZooKeeper 或 Nacos 注册，消费端订阅推送维护服务目录。
注册中心、序列化、负载均衡、容错是四个 SPI（`META-INF/rpc/<接口全名>`），实例由每个 client / server 各自创建并持有。

## 特性

**协议与方法模型**

- 纯 Java 核心：`RpcServer` / `RpcClient` 是可以 new、start、close 的实例，配置不可变、无全局可变状态，同 JVM 多实例互不干扰
- 协议 v2：IO 线程只读头与 attachments 就能拒绝请求，换序列化器不改线上格式（[protocol.md](docs/protocol.md)）
- 方法模型：多参数、重载（按参数签名分发）、泛型参数与返回值（`List<User>`、`Map<K,V>`、`CompletableFuture<List<T>>`），`interface + version + group` 多版本 / 分组暴露
- 序列化：Protostuff（缺省，按声明类型构造 schema + 多态位置类名白名单）与 fastjson2，建连时协商
- 异常：业务异常按原类型跨进程还原（类名白名单），受检异常原样抛出，远端堆栈缺省截断到 8KB（[protocol.md](docs/protocol.md#10-异常还原规则)）

**服务发现与容错**

- 注册中心：ZooKeeper（会话过期重建、推送合并去重、不可达时后台重试）与 Nacos，同跑 14 条契约测试（TCK）；消费端推空保护
- 负载均衡 `roundrobin` / `p2c`，容错 `failfast` / `failover` / `failback`；只重试传输失败，缺省 `retries = 0`
- deadline：超时是整次调用（含重试）的预算，以剩余毫秒传给服务端，排队中过期的请求不执行，下游调用自动继承
- 离群摘除（main，缺省开）：按节点统计超时 / 传输失败 / 过载拒绝，摘除 10s × 次数（上限 60s），最多摘一半（[A/B 探针](docs/probes/outlier-detection.md)）
- 优雅停机：摘注册 → 宽限期 → 拒新请求 → 等在途 → 关端口；下线节点的连接排空在途后再关

**性能与背压**

- 服务端异步到底：执行器 `fixed`（有界队列，缺省）/ `virtual`（并发上限）/ `direct`（opt-in），满载回 `SERVER_OVERLOADED`、业务未执行（[何时选 virtual](docs/probes/executor-blocking.md)）
- flush 合并（Netty `FlushConsolidationHandler`）与出站背压（连接不可写时暂停读、超硬上限拒绝）（[flush 探针](docs/probes/flush-and-executor.md) · [背压探针](docs/probes/outbound-backpressure.md)）
- 调用方与业务线程只序列化成 `byte[]`，ByteBuf 只在事件循环上按确切长度一次分配

**可观测与运维**

- Filter 链：客户端 / 服务端同一个 `Filter` 接口，按 `order()` 稳定排序；attachments 经 `RpcContext` 设置，W3C `traceparent` 等键已预留
- Micrometer 指标（`rpc-metrics-micrometer`，不依赖 Spring）：调用 Timer、在途与连接数 Gauge、离群摘除计数，可内嵌 Prometheus `/metrics`，附 Grafana 面板（[observability.md](docs/observability.md)）
- 每个配置项都有缺省值，Boot 属性与纯 Java builder 一一对应（[configuration.md](docs/configuration.md)）

**Spring Boot**

- Boot 4 starter：`@RpcService` / `@RpcReference`、`rpc.*` 配置（带 IDE 提示元数据）、Filter bean 自动接入、Customizer、`SmartLifecycle` 非阻塞启动与异步停机、可选 Actuator 健康指示器（[getting-started.md](docs/getting-started.md)）

## 快速开始

坐标 `io.github.sisyfy-zhang`，版本 `1.0.0`（Maven Central）；代码里 import 的包是 `com.feynman.rpc.*`（Central 的 `io.github.*` 命名空间按 GitHub 账号验证，所以 groupId 与包名不同）。
先导入 BOM，各模块不用逐个写版本：

```xml
<dependencyManagement><dependencies><dependency>
  <groupId>io.github.sisyfy-zhang</groupId><artifactId>rpc-bom</artifactId>
  <version>1.0.0</version><type>pom</type><scope>import</scope>
</dependency></dependencies></dependencyManagement>
<dependencies>
  <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>rpc-spring-boot-starter</artifactId></dependency>
  <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>rpc-registry-zookeeper</artifactId></dependency>
  <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>rpc-serialize-protobuf</artifactId></dependency>
</dependencies>
```

纯 Java 把 starter 换成 `rpc-core`；可再加 `rpc-serialize-fastjson2`，建连时与对端协商序列化方式。
BOM 管本框架模块与 ZooKeeper 的版本，不覆盖 Netty、jackson、Spring 等第三方版本；不用 BOM 时给每个依赖写 `<version>1.0.0</version>`。
想用 main 上的最新代码时，在仓库根目录 `./mvnw install -DskipTests` 装到本地仓库，并把版本换成根 `pom.xml` 的 `<version>`。

**接口**放在双方共享的 API 包里；DTO 用普通类，不要用 `record`（Protostuff 不支持）：

```java
public interface UserFacade {

	User saveUser(User user);

	void saveUsers(List<User> users);
}
```

**提供者**：实现类标 `@RpcService`（它元注解了 `@Component`），像普通 bean 一样注入依赖、实现方法：

```java
@RpcService
public class UserFacadeImpl implements UserFacade {
```

**消费者**：字段上标 `@RpcReference`，像本地接口一样调用：

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

注册中心缺省是 `localhost:2181` 的 ZooKeeper，改用 `rpc.registry.type` / `rpc.registry.address`（[全部配置项](docs/configuration.md)）。容器里有 `@RpcService` 才会绑定端口；
`SpringApplication.run()` 不会被 RPC 阻塞；`SIGTERM` / `context.close()` 走完整的优雅下线。以上代码逐字取自 [`rpc-examples`](rpc-examples/README.md)（`DocsSnippetsTest` 核对）。

- 从空白 Boot 4 工程开始的完整步骤、模块一览与常见错误：[docs/getting-started.md](docs/getting-started.md)
- 不用 Spring（`RpcServer.builder()` / `RpcClient.builder()` 各十几行）：[getting-started 第 10 节](docs/getting-started.md#10-不用-spring纯-java)
- 直接运行示例（含嵌入式 ZooKeeper，无需安装）：[rpc-examples/README.md](rpc-examples/README.md#运行)

## 性能与证据

数字只由压测流水线产生；三张图由 [`bench/bin/charts.py`](bench/bin/charts.py) 从归档 JSON 生成，图上每个数字的来源文件见 [docs/images/](docs/images/README.md)。

![闭环 64 并发吞吐：本框架 fixed / direct，gRPC-Java 与 Dubbo triple 开箱即用 / 2 连接](docs/images/throughput-closed64.svg)

闭环 64 并发：本框架 fixed（缺省）101,943.2 req/s；gRPC-Java / Dubbo triple 开箱即用 14,386.4 / 14,401.8，2 连接 35,516.3 / 25,963.1；direct（opt-in）142,671.6（[数据表](docs/images/throughput-closed64.md) · [差在哪](docs/comparison.md#3-差在哪)）。

<sub>测试条件：同机 WSL2 loopback · AMD Ryzen 5 4600H · 服务端 cpu 0-4 / 压测端 cpu 5-9 · 1KB 回显 · 直连 · 每卡 3 轮 × 60s 取正式轮 · 归档 d23e39d（git_dirty = false）· 同机 loopback 数字不可外推</sub>

![开环 10,000 req/s 的 p50 与 p99（对数轴），灰带为三轮 p99 极差](docs/images/tail-latency-open10k.svg)

开环 10,000 req/s（延迟从计划发出时刻算）：fixed p99 0.798ms；gRPC / Dubbo 开箱即用 p99 20.955 / 22.233ms 且三轮波动大，2 连接 1.338 / 4.235ms；direct 0.530ms（[数据表](docs/images/tail-latency-open10k.md)）。

<sub>测试条件：同机 WSL2 loopback · AMD Ryzen 5 4600H · 服务端 cpu 0-4 / 压测端 cpu 5-9 · 1KB 回显 · 直连 · 每卡 3 轮 × 60s 取正式轮 · 归档 d23e39d（git_dirty = false）· 同机 loopback 数字不可外推</sub>

![离群摘除开 / 关：kill -9 与连接静默挂起下的窗口错误数与恢复时间](docs/images/fault-recovery.svg)

离群摘除开 / 关：kill -9（failfast）窗口错误 50,349 / 48,497 → 221 / 397，约 1.0s 恢复；连接静默挂起（failover）62,447 / 62,498 个超时且未恢复 → 5,038 / 5,038，约 3.0s 恢复（[数据表](docs/images/fault-recovery.md)）。

<sub>测试条件：同机 WSL2 loopback · AMD Ryzen 5 4600H · 服务端 cpu 0-4 / 压测端 cpu 5-9 · 1KB 回显 · 开环 5000 req/s · 3 个提供者经 ZooKeeper · 超时 1s · 单轮 60s、t = 20s 注入故障 · 开 / 关各 2 次 · 同机 loopback 数字不可外推 · 探针口径（git_dirty），详见 [probes/outlier-detection.md](docs/probes/outlier-detection.md)</sub>

正确性由测试体系保证：跨模块集成测试（含 Linux 真实 SIGTERM）、注册中心契约测试 TCK、ArchUnit 架构规则（每条带"能失败"的自测）、jqwik 编解码模糊测试、
每个测试类结束后的线程与在途泄漏断言、Netty paranoid 泄漏检测、JaCoCo（rpc-core 行覆盖率 ≥ 70%），详见 [CONTRIBUTING.md「测试体系」](CONTRIBUTING.md#测试体系)。

## 文档

- [docs/getting-started.md](docs/getting-started.md)：15 分钟接入指南（空白 Boot 4 工程 → provider + consumer）、模块一览、纯 Java 写法、常见错误对照表
- [docs/configuration.md](docs/configuration.md)：配置项全表（通用 / 客户端 / 服务端 / Boot 专属）与端口
- [docs/protocol.md](docs/protocol.md)：协议 v2 规格（帧头、attachments、信封、状态码、协商、连接关闭规则）
- [docs/observability.md](docs/observability.md)：指标名与 tag、status 取值、计时口径、PromQL、Grafana 面板、纯 Java 内嵌 `/metrics`
- [docs/benchmarking.md](docs/benchmarking.md)：端到端压测：口径卡、子命令、WSL2 标准流程、报告字段、交叉验证、故障注入
- [docs/comparison.md](docs/comparison.md)：与 gRPC-Java / Dubbo 3 的对比报告：口径、三方表、差距来源、对我们不利的数字、方法论
- 归档数据：正式归档 [bench/reports/20261022-d23e39d/](bench/reports/20261022-d23e39d/)（干净提交，作数）· 探针结果 [bench/reports/probes/](bench/reports/probes/README.md)（git_dirty，不作数）

## 贡献与社区

- 构建与测试、代码约定、哪些改动要先开 issue、压测数据的口径纪律：[CONTRIBUTING.md](CONTRIBUTING.md)
- 安全问题请按 [SECURITY.md](SECURITY.md) 私下报告（GitHub 私密漏洞报告已开启），不要开公开 issue
- 缺陷与建议：[Issues](https://github.com/sisyfy-Zhang/rpc-framework/issues)
- 依赖更新：Dependabot 每周检查 GitHub Actions 与 Maven 依赖

## 许可证

本项目代码以 [MIT](LICENSE) 许可证发布。`rpc-serialize-protobuf` 中的 `io/protostuff/runtime/AllowlistIdStrategy.java`
派生自 Protostuff 1.8.0（Apache License 2.0），继续适用 Apache 2.0，署名与修改说明见 [NOTICE](NOTICE)，许可证全文见
[licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)。
