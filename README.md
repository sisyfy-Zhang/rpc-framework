# RPC 框架

一个从零结构性重写的 Java RPC 框架：Netty + ZooKeeper/Nacos + Spring。架构上参考 Dubbo 的核心设计（SPI 扩展点、按扩展点拆分模块），但规模刻意收敛在个人能完全吃透的程度，不追求覆盖 Dubbo 的全部能力。

## 架构

10 个 Maven 模块，`rpc-core` 是唯一的核心依赖，其余模块都是围绕它的可插拔实现或使用示例：

| 模块 | 作用 |
|---|---|
| `rpc-api` | 服务契约注解（`@Remote` / `@RemoteInvoke`）与示例接口 |
| `rpc-core` | 协议、编解码、连接管理、动态代理、负载均衡、容错、SPI 机制——不依赖任何具体注册中心/序列化库 |
| `rpc-registry-zookeeper` / `rpc-registry-nacos` | `Registry` SPI 的两种实现，用哪个后端只需引入对应模块 |
| `rpc-serialize-fastjson2` / `rpc-serialize-protobuf` | `Serializer` SPI 的两种实现 |
| `rpc-spring-boot-starter` | Spring Boot 自动装配：`@EnableRpc` 或 `application.yml` 驱动配置 |
| `rpc-benchmark` | JMH 基准测试（负载均衡策略对比、序列化方式对比） |
| `rpc-consumer` / `rpc-demo` | 消费端/服务端使用示例 |

`Registry`/`Serializer`/`LoadBalance`/`Cluster` 是四个可插拔扩展点，按 Dubbo 的 `dubbo-registry-*`/`dubbo-serialization-*` 拆分方式各自独立成模块——用 ZooKeeper 就不会被迫把 Nacos SDK 也打进 classpath。

## 设计取舍

### 贯穿全项目的几个设计本能

- **间接引用层**：requestId → 待完成的 future、节点地址 → 连接槽位、`command` 字符串 → 反射方法，都是把"轻量标识"和"重对象本体"拆开，中间架一层可原子替换的查表，解耦两者的生命周期。
- **同步是异步的语法糖**：同步调用、异步调用、失败重试最终共用同一份底层实现，只在最外层加一层阻塞等待或重试包装，不会出现"两条路径分别维护、容易漂移"的问题。
- **有真实代价的能力默认关闭**：重试次数默认 0、服务端端口绑定默认不开启、空闲连接检测需要显式配置——任何会带来资源占用或破坏幂等性的能力，都必须调用方显式声明。

### 直接沿用 Dubbo 的部分

SPI 机制（`@SPI` 注解 + `ExtensionLoader` + `META-INF/rpc/接口全限定名` 配置文件）基本是 Dubbo `@SPI`/`ExtensionLoader` 机制的移植——这套"按需加载 + 单例缓存 + 名字索引"的设计已经被大规模验证过，没必要重新发明。四个可插拔扩展点的选取（`Registry`/`Serializer`/`LoadBalance`/`Cluster`）同样是对齐 Dubbo 已验证过的取舍，而不是自己从头猜。

### 主动偏离 Dubbo 默认做法的三处

1. **异步优先**：`CompletableFuture` 是唯一的真实调用路径，同步方法签名只是在其上 `.join()` 的语法糖。这是一个从零开始的项目才有的自由度——不需要像 Dubbo 那样背负向后兼容的历史包袱。
2. **P2C 负载均衡作为可选项**：默认策略仍是 RoundRobin，但 `p2c` 可选。JMH 基准（见 `rpc-benchmark`）在一个模拟慢节点的并发场景下测得 P2C 比 RoundRobin 快 2.1 倍——因为 RoundRobin 对节点当前排队情况视而不见，而 P2C 采样两个候选、选在途请求数更低的那个。
3. **序列化协商握手**：连接建立时双方交换各自支持的序列化方式优先级列表，取交集中对方最优先的一个。这让"消费端/服务端序列化实现不完全一致"的场景（灰度升级、混合部署）能自动兼容，而不要求全网静态配置严格一致。

### 已知的取舍

- 序列化协商时，候选列表里除"客户端最优先"之外的顺序依赖 `ConcurrentHashMap.keySet()`，遍历顺序未定义（目前只有 2 个序列化实现，暂时不可见）。
- `@Remote` bean 若实现了多个接口，只有第一个接口的方法会被注册；继承自父类但未在本类重写的方法也不会被扫描到。

## 快速开始

```bash
# 需要 JDK 17+
mvn install

# 起服务端：需要本机有 ZooKeeper 监听 2181（默认配置见 rpc-demo/application.yml）
# 从 IDE 运行 com.feynman.rpc.ProviderApplication 的 main 方法即可

# 跑消费端测试
mvn -pl rpc-consumer test

# 跑负载均衡 / 序列化基准测试
mvn -pl rpc-benchmark package
java -jar rpc-benchmark/target/benchmarks.jar
```

## 技术栈

Netty 4 · Spring / Spring Boot · ZooKeeper (Curator) / Nacos · fastjson2 / Protostuff (Protobuf) · JMH

## License

[MIT](LICENSE)
