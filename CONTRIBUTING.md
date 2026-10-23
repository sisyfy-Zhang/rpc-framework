# 参与贡献

感谢关注本项目。这份文档说明怎么在本地构建与测试、代码与提交有哪些约定、哪些改动需要先开 issue 讨论，以及压测数据的口径纪律。
安全问题请**不要**开公开 issue，按 [SECURITY.md](SECURITY.md) 私下报告。

## 环境要求

- **JDK 21**（编译目标 21）。CI 另有一个 JDK 25 的可选任务，失败不阻塞合并，但请不要引入只能在 21 上编译的写法。
- **Maven 用仓库自带的 Wrapper**：`./mvnw`（Windows 的 cmd / PowerShell 用 `mvnw.cmd`）。版本由 `.mvn/wrapper/maven-wrapper.properties` 固定为 3.9.12，
  与 CI 和压测机一致；不要用系统 Maven 3.10 发布或核对 bundle（3.10 下 central-publishing 的 bundle 会混进本地仓库跟踪文件，Central 校验失败）。
- 构建与测试**不需要** Docker 和本机中间件：ZooKeeper 测试用进程内 TestingServer；Nacos 契约测试在没有 Docker 时自动跳过（CI 上有 Docker，会实跑）。

## 构建与测试

```bash
# 全量：单元 + 集成 + ArchUnit 架构规则 + Registry TCK + JaCoCo 覆盖率检查（约 3~4 分钟）
./mvnw clean install

# 只编译、安装，不跑测试（-DskipTests 同时跳过 JaCoCo）
./mvnw install -DskipTests

# 单个模块（-am 连带构建它依赖的模块）
./mvnw -pl steadyrpc-core verify
./mvnw -pl steadyrpc-registry-zookeeper -am verify

# 集成测试：先装好其它模块，再单独跑 steadyrpc-integration-tests。
# 必须跑到 verify / install，不能只跑 test：泄漏断言扩展经 steadyrpc-core 的 test-jar 共享，test 阶段 test-jar 尚未打包。
# 单独跑时加 -Djacoco.skip=true：聚合覆盖率检查需要全部模块的 exec，单模块运行时没有。
./mvnw install -DskipTests
./mvnw -pl steadyrpc-integration-tests verify -Djacoco.skip=true
./mvnw -pl steadyrpc-integration-tests verify -Djacoco.skip=true -Dtest=GracefulShutdownTest -Dsurefire.failIfNoSpecifiedTests=false   # 单个测试类

# Netty ByteBuf 泄漏检测（paranoid）：之后 build.log 里不能有 "LEAK:"，且要能找到 "level = PARANOID"（证明检测器确实开着）
./mvnw clean install -Djacoco.skip=true "-DargLine=-Dio.netty.leakDetection.level=paranoid" 2>&1 | tee build.log
grep "LEAK:" build.log            # 应无输出
grep "level = PARANOID" build.log # 应有输出

# 探针类（*ProbeTest，会改第三方库全局状态，默认不跑）
./mvnw test -Pprobe -pl steadyrpc-serialize-protobuf -am
```

- 额外的 JVM 参数一律用 `-DargLine=...` 传：surefire 配置是 `@{argLine} ${argLine}`，JaCoCo agent 走前一半，你的参数走后一半。
- `SigtermShutdownTest`（真实 `kill -TERM` 一个 provider 进程）只在 Linux 上运行，Windows / macOS 上被跳过；CI 有专门的 job 并核对它确实跑了。
- CI（`.github/workflows/ci.yml`）在每次 push / PR 上跑：JDK 21 全量构建（Nacos 契约测试必须实跑）、Linux SIGTERM 用例、paranoid 泄漏检测、JDK 25 可选任务；
  另有 CodeQL 与 OpenSSF Scorecard。PR 合并前前三项必须全绿。

## 测试体系

上面的命令背后是这几类机制；README 只用一段话概括它们，细节以这里为准。

| 机制 | 说明 |
|---|---|
| 集成测试 | `steadyrpc-integration-tests`：多实例路由、优雅停机（含进程内 Boot 变体与 Linux 下的真实 SIGTERM 子进程用例）、deadline 与 attachments、过载、异常映射、序列化矩阵（Protostuff 与 fastjson2 各跑一遍）、示例应用端到端。ZooKeeper 用进程内 TestingServer |
| Registry TCK | `steadyrpc-registry-tck` 的 `RegistryContractTest`（14 条：首次推送、去重、跨 key 隔离、关闭后消失窗口、线程回收、后端不可达时订阅并在恢复后补推……），ZooKeeper / Nacos / 内存参考实现都继承它 |
| 架构规则 | ArchUnit：core 不依赖 Spring、全仓禁止 `System.setProperty`、无可变静态状态（白名单除外），每条规则带"能失败"的自测；maven-enforcer 在依赖层面禁止 core 引入 Spring |
| 编解码模糊测试 | jqwik：attachments / 信封随机往返、随机字节输入不崩溃且不泄漏 ByteBuf |
| 泄漏断言 | `RpcLeakCheckExtension`（JUnit 自动注册）：steadyrpc-core 与集成测试的**每个测试类**结束后，`rpc-` 线程须在 5s 内归零、测试类持有的 client / server 在途为零；带阳性对照（`LeakFixtureTest`，默认 `@Disabled`） |
| 覆盖率 | JaCoCo：每个模块的测试都挂 agent；`steadyrpc-integration-tests` 汇总报告在 `target/site/jacoco-aggregate/`；steadyrpc-core 行覆盖率 ≥ 70% 检查两次（core 自身单元测试；全部模块测试的聚合） |
| Netty 内存泄漏 | `./mvnw clean install "-DargLine=-Dio.netty.leakDetection.level=paranoid" \| tee build.log`，之后 `grep "LEAK:" build.log` 应为空，且 `grep "level = PARANOID" build.log` 有输出（证明检测器确实开着） |
| 特征化探针 | 会改第三方库全局状态的探针类以 `*ProbeTest` 命名，默认不跑：`./mvnw test -Pprobe -pl steadyrpc-serialize-protobuf -am` |

steadyrpc-core 的 `RpcLeakCheckExtension` 经 test-jar 共享给集成测试，这也是集成测试必须跑到 `verify` / `install` 的原因：`test` 阶段 test-jar 尚未打包，
Maven 会把 steadyrpc-core 的全部测试类放进集成测试的 classpath（`LeakCheckWiringTest` 会因此失败并提示）。
README 与 `docs/getting-started.md` 里的 Java / YAML 代码块由 `DocsSnippetsTest` 逐字核对，必须原样取自 `steadyrpc-examples`；`xml` 块里的 artifactId 必须是 `steadyrpc-bom` 的模块或 `steadyrpc-examples` 的依赖。

## 代码约定

- **依赖边界**：`steadyrpc-core` 只依赖 Netty 与 slf4j-api，不依赖任何容器。`maven-enforcer-plugin` 在依赖层面禁止 core 引入 Spring（含传递依赖），
  ArchUnit 在代码层面再查一次。给 core 加任何新依赖都需要先开 issue。
- **ArchUnit 规则**（`steadyrpc-core/src/test/.../architecture/`、`steadyrpc-integration-tests/src/test/.../it/*Architecture*Test`、`NoSystemPropertiesArchTest`）：
  - core 不依赖 Spring；
  - 无可变静态状态（`static` 非 `final` 字段、静态可变容器；白名单写在 `CoreArchitectureRules`，每一项都要写理由）；
  - 全仓生产代码禁止 `System.setProperty` 与 `System.getProperties()`（配置只经不可变的 `RpcClientConfig` / `RpcServerConfig` 进入框架）；
  - 调用方一侧的代码（业务线程 / 虚拟线程上运行的部分）不直接碰 Netty 的 ByteBuf 分配器。

  规则被触发时请修代码，而不是加白名单；确实需要加白名单的，在 PR 里说明为什么。新增规则要带一个"能失败"的自测（`*SelfTest`），证明规则不是空转。
- **无全局状态**：`RpcClient` / `RpcServer` 是可以 new、start、close 的实例，同一个 JVM 里多个实例互不干扰。新功能的状态挂在实例或它持有的对象上。
- **资源与线程**：每个测试类结束后，`RpcLeakCheckExtension` 断言 `rpc-` 前缀的线程 5 秒内归零、在途请求为零。框架创建的线程名以 `rpc-` 开头；测试里创建的 client / server 必须关闭（`try-with-resources` 或 `@AfterEach`）。
- **格式**：Java 源码用 Tab 缩进，跟随所在文件的既有风格。注释与文档可以用中文，标识符、日志与异常消息用英文。
- **测试命名**：
  - 类名 `XxxTest`；跨进程 / 跨模块的放 `steadyrpc-integration-tests`；架构规则 `*ArchTest` / `*ArchitectureTest`，其自测 `*SelfTest`；
    注册中心契约测试继承 `steadyrpc-registry-tck` 的 `RegistryContractTest`。
  - 会改第三方库全局状态的探针类以 `*ProbeTest` 结尾并标 `@Tag("probe")`，surefire 默认排除，只用 `-Pprobe` 运行。
  - 方法名是描述行为的英文句子（lowerCamelCase），读出来就是断言内容，例如 `staticContainersAndArraysAreRejected`、
    `threadsCreatedByTheImplementationEndWithinFiveSecondsAfterClose`；不用 `test1` / `testFoo` 这类名字。
- **覆盖率**：`steadyrpc-core` 行覆盖率下限 70%，检查两次（core 自身单元测试；全部模块测试的聚合）。不要为凑覆盖率写不做断言的测试。

## 提交信息与 PR

- 一个提交 / 一个 PR 只做一件事；重构与行为变更分开提交。
- 提交信息首行一句话说清"改了什么"（中文或英文都可以，不加句号），需要时空一行写正文：为什么改、怎么验证的、有没有已知限制。
  如果改动由 AI 工具协助完成，在末尾加 `Co-Authored-By:` 行注明。
- PR 描述写清：动机、改动范围、验证方式（跑了哪些命令、结果如何）、对使用者可见的变化（配置项、缺省行为、协议）。
- 行为变化要同步更新文档：README（特性）、[docs/configuration.md](docs/configuration.md)（配置项）、[docs/getting-started.md](docs/getting-started.md#11-已知限制)（已知限制）、`docs/` 下其它对应文档；
  README 与 getting-started 的 Java / YAML 代码块必须原样取自 `steadyrpc-examples`（`DocsSnippetsTest` 逐字核对），`rpc.*` 配置项变化同步 starter 的配置元数据（`additional-spring-configuration-metadata.json`）。
- PR 必须保持 CI 的 verify / sigterm / paranoid-leak 全绿；不要为了让 CI 通过而跳过、`@Disabled` 或放宽断言——如果断言本身不对，在 PR 里单独说明。

## 哪些改动需要先开 issue

以下改动影响兼容性、口径或发布面，请先开 issue 讨论方案，达成一致后再提 PR：

- **线协议**（[docs/protocol.md](docs/protocol.md)）：帧头、attachments、信封格式、协商流程的任何变化。
- **公开 API 与 SPI**：`RpcClient` / `RpcServer` / 配置对象的公开方法，`Filter`，四个 SPI（`RegistryFactory`、`Serializer`、`LoadBalance`、`Cluster`）的接口。
- **缺省行为**：缺省序列化器、执行器、连接数、超时、离群摘除参数等。
- **依赖**：给 `steadyrpc-core` 加依赖，或给任何发布模块加新的第三方依赖、升级大版本。
- **模块与发布面**：新增 / 拆分 / 改名模块（还涉及根 pom 的 `excludeArtifacts` 与 [docs/releasing.md](docs/releasing.md) 的发布清单）。
- **压测口径**：新增或修改口径卡、压测 harness 的测量方式、对比对象的配置。

文档措辞、测试补充、明显的 bug 修复可以直接提 PR。

## 基准与归档数据的口径纪律

README 与 [docs/comparison.md](docs/comparison.md) 里的性能数字有严格来源，详见 [docs/benchmarking.md](docs/benchmarking.md)。贡献时请遵守：

- **数字只由压测流水线产生**：口径卡（`bench/profiles/*.yaml`）驱动独立的服务端 / 客户端 JVM，JSON 是事实源，Markdown 由 JSON 生成。
  README / 文档里的任何压测数字都必须链接到生成它的结果文件，**不手写、不手抄、不四舍五入改写**。
- **改口径就复制成新 id**（如 `-v2`），不要原地改卡片：同一个卡片 id 必须始终代表同一个口径。
- **正式数字（作数）只来自干净提交**：结果里 `git_dirty = false`，归档在 `bench/reports/<yyyyMMdd>-<sha7>/`，目录名的 sha 就是结果的 `git_sha`。
- **探针数字（不作数）**：带未提交改动跑出的结果放 `bench/reports/probes/<yyyyMMdd>-task<n>-<名称>/`，引用时标明"探针口径"，
  只用来支撑机制分析，不作为对外数字（见 [bench/reports/probes/README.md](bench/reports/probes/README.md)）。冒烟运行与对照组同样不作数。
- **CI 不产出性能数字**：GitHub Actions runner 的数字不可比，不要把它们写进 README。正式数字只来自固定压测机（环境由 harness 自动采集进报告）。
- **不利的数字同样保留**：对比报告里对本项目不利的结果不删、不挑轮次；正式轮按 [判定与正式轮](docs/benchmarking.md#判定与正式轮) 的规则机械选取。
- 归档数据（`bench/reports/`）与代码改动分开提交。

## 许可证

提交的贡献按本仓库的 [MIT License](LICENSE) 授权。
