# steadyrpc-examples

可运行的示例，同时也是 [接入指南](../docs/getting-started.md) 与 README 代码片段的来源（集成测试逐字核对）。

| 包 / 类 | 内容 |
|---|---|
| `api` | 共享契约：`UserFacade`、`UserService`、`InventoryService`（多参数、泛型返回、异步方法），DTO `User`，业务异常 `InsufficientStockException` |
| `provider.ProviderApplication` | Spring Boot provider：`@RpcService` 暴露三个服务，`TenantFilter`（ServerFilter）读 attachment，配置 `provider.yml`（端口 9000；health 组件 `rpc` 接进 readiness 组） |
| `consumer.ConsumerApplication` | Spring Boot consumer：`@RpcReference` 注入，启动后 `DemoRunner` 调用一次 `saveUser`、`CallShapesRunner` 演示业务异常 / 异步 / attachments，然后退出，配置 `consumer.yml` |
| `plain.PlainProvider` / `plain.PlainConsumer` | 不用 Spring 的纯 Java 版本（端口 9100） |
| `EmbeddedZooKeeper` | 本机试跑用的进程内 ZooKeeper（curator-test 的 TestingServer），默认端口 2181 |

所有示例默认连 `localhost:2181`，序列化用 Protostuff（`rpc.serializer: protobuf`）。

## 运行

先在仓库根目录构建并安装（JDK 21）：

```bash
mvn install -DskipTests
cd steadyrpc-examples
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
```

准备 classpath 变量：

```bash
# Linux / macOS
CP="target/classes:$(cat target/classpath.txt)"
```

```powershell
# Windows PowerShell
$CP = "target/classes;" + (Get-Content target/classpath.txt -Raw).Trim()
```

然后每个命令开一个终端（PowerShell 里把 `"$CP"` 写成 `$CP`）：

```bash
# 1. ZooKeeper：二选一
java -cp "$CP" io.github.sisyfyzhang.steadyrpc.examples.EmbeddedZooKeeper        # 不装任何东西
docker run -d --name zk -p 2181:2181 zookeeper:3.9                 # 或者真实 ZooKeeper

# 2. Boot provider（常驻；Ctrl+C 触发优雅下线）
java -cp "$CP" io.github.sisyfyzhang.steadyrpc.examples.provider.ProviderApplication

# 3. Boot consumer（调用一次后退出）
java -cp "$CP" io.github.sisyfyzhang.steadyrpc.examples.consumer.ConsumerApplication

# 纯 Java 版本（可与上面的 Boot 版本同时运行）
java -cp "$CP" io.github.sisyfyzhang.steadyrpc.examples.plain.PlainProvider
java -cp "$CP" io.github.sisyfyzhang.steadyrpc.examples.plain.PlainConsumer
```

`PlainProvider` 可选 `--metrics-port <p>`（如 `PlainProvider localhost:2181 9100 --metrics-port 9466`）：记录调用指标并在
`http://localhost:<p>/metrics` 暴露 Prometheus 抓取端点，`docker/` 里的 Prometheus 已预置抓取 9466（见 [docs/observability.md](../docs/observability.md)）。

预期：provider 打印 `Registered io.github.sisyfyzhang.steadyrpc.examples.api.UserFacade::default at <本机地址>:9000`；
consumer 打印 `CallShapesRunner` 的四行结果与 `Remote saveUser returned User{id=1, name=zzz}` 后退出；provider 同时打印
`buyers called for tenant acme` 与 `UserServiceImpl.save called with User{id=1, name=zzz}`。纯 Java consumer 输出三行：剩余库存、购买者列表、库存不足异常。
两个 provider 都暴露 `InventoryService`、各自持有一份内存库存：同时运行时 `PlainConsumer` 的调用会被负载均衡（p2c）分到两个节点上，
三行输出的数字可能对不上——这正好演示了服务发现与负载均衡；只想看一致的结果就只运行其中一个 provider。

覆盖配置不需要改文件，命令行参数优先：

```bash
java -cp "$CP" io.github.sisyfyzhang.steadyrpc.examples.provider.ProviderApplication --rpc.registry.address=10.0.0.5:2181 --rpc.server.port=0
```

## 在测试里使用

不需要手工启动任何东西，集成测试就是这样跑这些示例的：

- `steadyrpc-integration-tests` 的 `ExamplesEndToEndTest`：进程内 TestingServer + `ProviderApplication.builder().run(...)` + `ConsumerApplication.builder().run(...)`；
- `PlainJavaExamplesTest`：`PlainProvider.start(...)` + `PlainConsumer.demo(...)`；
- `SigtermShutdownTest`（仅 Linux）：provider 作为独立 JVM 启动，收到真实 SIGTERM 后优雅下线。

## 注意

- `EmbeddedZooKeeper` 依赖 `curator-test`，在本模块里是 `optional` 依赖，不会传递给依赖本模块的工程；它只用于试验，
  数据放在临时目录、退出即删除。
- 示例里 provider 与 consumer 在同一个模块，所以用 `spring.config.name=provider|consumer` 区分配置文件；你的工程里用默认的
  `application.yml` 即可。
