# 安全策略

## 支持的版本

| 版本 | 是否接收安全修复 |
|---|---|
| 2.0.x | 是 |
| 1.0.x | 只接收安全修复，截至 2026-12-31（旧坐标 `rpc-*`；之后请升级到 2.0.x） |
| < 1.0 | 否（未发布到 Maven Central） |

安全修复只发在受支持版本线的新补丁版本里（例如 2.0.1；1.0.x 线若有修复，以旧坐标 `rpc-*` 的 1.0.y 发布）。是否为 1.0.x 出补丁视严重程度而定，不保证每个修复都回移。Maven Central 上已发布的版本不可覆盖，修复不会回改旧版本号。

## 报告漏洞

**请不要通过公开 issue、讨论区或 PR 报告安全问题。**

请使用 GitHub 的私密漏洞报告：打开本仓库的 **Security** 页 → **Report a vulnerability**
（直达链接：<https://github.com/sisyfy-Zhang/steady-rpc/security/advisories/new>）。
报告只对你和仓库维护者可见。

请尽量提供：

- 受影响的模块与版本（如 `steadyrpc-core 2.0.0`，或旧坐标的 `rpc-core 1.0.0`）；
- 问题类型（例如反序列化、协议解析、拒绝服务、信息泄露）与影响；
- 复现步骤或最小复现代码，以及所用的配置（序列化器、注册中心、执行器等）；
- 你知道的缓解办法（如果有）。

## 响应流程

本项目由个人维护，下面的时间是保守承诺：

- **14 天内**确认收到并给出初步回复；
- 确认问题后，在 Security Advisory 里与你沟通修复方案与时间表；修复发布前不公开细节；
- 修复随新补丁版本发布，同时公开 Security Advisory（如适用会申请 CVE），并在征得同意后致谢报告者。

如果 14 天内没有收到回复，请在原报告里追加一条消息提醒。

## 依赖漏洞

第三方依赖的已知漏洞（GHSA / CVE）按下面的规则处理；OpenSSF Scorecard 的 Vulnerabilities 检查与 Dependabot 持续提示，发布前人工核对一次。

**发布前核对**（[docs/releasing.md「依赖漏洞核对」](docs/releasing.md#依赖漏洞核对)）：用 [`bench/tools/osv_scan.py`](bench/tools/osv_scan.py) 对照 [OSV](https://osv.dev) 扫两棵依赖树，都要求 0 命中——

- reactor 的依赖树（`./mvnw -B dependency:tree`），即本仓库构建与测试实际用到的版本；
- **使用方视角**的依赖树：不导入任何 BOM、直接依赖各发布模块的消费者工程解析出来的版本（另看一份以 Boot 为父 pom 的，命中按 Boot 版本解读）。根 pom 的 `dependencyManagement` 对使用方不生效，
  两棵树可能不同（1.0.0 时使用方经 nacos-client 拿到 jackson 2.13.5、经 zookeeper 拿到 netty-handler 4.1.119，reactor 里却是 2.20.2 / 4.1.136）。

**版本线约束**：修复在当前版本线内跟进补丁，下面几条线不随漏洞修复跨线升级（Dependabot 也按此忽略跨线更新，见 `.github/dependabot.yml`）：

| 依赖 | 版本线 | 原因 |
|---|---|---|
| Netty | 4.1 | 构建、测试与压测口径都在 4.1 上；Boot 4 应用里是 Boot 管理的 4.2（见 [getting-started 第 2 节](docs/getting-started.md#2-新建空白-boot-4-工程并加依赖)），CI 的 `netty-42` 作业另跑全量测试 |
| protobuf-java | 3.25 | 只用于 gRPC 对比模块：生成代码与 grpc-protobuf 依赖的运行时同一条线 |
| nacos-client | 2.x | 3.x 是不兼容的大版本，注册中心实现与契约测试按 2.x 写 |

**为使用方做的对齐**（2.0.0 起）：

- `steadyrpc-bom` 除本框架模块外只管理 ZooKeeper 的版本（Spring Boot 不管理它）；不管理 Netty / jackson，以免覆盖 Boot 应用的版本
  （Boot 4 生态绑定 Netty 4.2）；
- `steadyrpc-registry-zookeeper` 排除 zookeeper 自带的全部 `io.netty:*`，Netty 一律来自 steadyrpc-core 的 netty-all（同一版本，不会混入旧版）；
- `steadyrpc-registry-nacos` 直接声明 jackson-core / jackson-databind：这是对上游 nacos-client 2.4.3 写死 jackson 2.13.5 的绕行，
  nacos-client 换掉写死版本后可以撤掉。
- **Spring Boot 应用**的 Netty 与 jackson 由 Boot 的 BOM 管理，不由本框架决定：例如 Boot 4.1.0 管理的 jackson 2.21.4 仍有已公开的 jackson 公告，
  修复随 Boot 的补丁版本进来，请保持 Boot 补丁版本最新。非 Boot 应用拿到的是本框架直接依赖的版本（上面两条）。

**不适用说明**：下面这些公告所在的构件确实在 classpath 上，仍随补丁版本升级，但本框架的代码路径不触及：

- 框架不使用 Netty 的 TLS / SNI（`SslHandler`、`SniHandler`）、IP 过滤（`ipfilter`）、HTTP 编解码（含 CORS）与 SCTP；
  steadyrpc-core 只用到 channel、buffer、基础 codec、timeout、flush 与 util（netty-all 把其余子模块一并带进来）；
- log4j-api 只经 Spring Boot 的 starter-logging 进入，在 `steadyrpc-spring-boot-starter` 里是 optional 的 Actuator 依赖，不传递给使用方。

**提示**：使用方开启 ZooKeeper 客户端 TLS（`zookeeper.clientCnxnSocket=org.apache.zookeeper.ClientCnxnSocketNetty` 且 `zookeeper.client.secure=true`）时，
ZooKeeper 客户端改走 Netty 的 TLS 实现，Netty（`netty-handler`）与 ZooKeeper 自身（`ZKTrustManager`）的 TLS 相关公告就进入攻击面，请保持两者在最新补丁版本。

## 范围说明

以下属于当前版本的设计边界，单独出现时不按漏洞处理：

- 框架目前**不提供传输加密与调用方认证**（没有内置 TLS / mTLS），应部署在可信网络内，或由使用方在外层（网络隔离、Sidecar 等）提供；
- 注册中心（ZooKeeper / Nacos）的访问控制由注册中心自身负责；
- [docs/getting-started.md「已知限制」](docs/getting-started.md#11-已知限制)列出的功能限制。

但下面这类问题即使在可信网络的假设下也请报告：绕过类名白名单（多态字段、异常还原）的反序列化、能让服务端崩溃或耗尽资源（内存、线程、连接）的畸形帧或请求序列、
跨调用串号或泄漏 attachments 等。
