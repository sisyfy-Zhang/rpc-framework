# 更新日志

本文件记录每个版本的用户可见变更，格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [2.0.0] - 2026-10-23

### 不兼容变更（Breaking）

- 项目改名为 **steady-rpc**，GitHub 仓库 `rpc-framework` → `steady-rpc`（旧 URL 由 GitHub 自动重定向）。
- Maven 坐标：groupId `io.github.sisyfy-zhang` 不变，全部 artifactId 前缀 `rpc-` → `steadyrpc-`（如 `rpc-core` → `steadyrpc-core`、`rpc-bom` → `steadyrpc-bom`、`rpc-spring-boot-starter` → `steadyrpc-spring-boot-starter`）。
- Java 包名：`com.feynman.rpc` → `io.github.sisyfyzhang.steadyrpc`（各 jar 的 `Automatic-Module-Name` 同步为 `io.github.sisyfyzhang.steadyrpc.<模块>`）。
- **迁移**：把依赖的 artifactId 换成 `steadyrpc-*`、版本改为 `2.0.0`，并在代码里全局替换 import 前缀 `com.feynman.rpc` → `io.github.sisyfyzhang.steadyrpc`；自定义 SPI 实现的配置文件名（`META-INF/rpc/<接口全名>`）随接口全名一起改。
  以下**不变**：Spring Boot 配置属性前缀 `rpc.*`、注解与类名（`@RpcService` / `@RpcReference` / `RpcClient` / `RpcServer` …）、SPI 目录 `META-INF/rpc/`、线程名前缀、attachments 键名、线上协议 v2 的格式。

### 新增（Added）

- 客户端离群摘除（缺省开）：按节点统计超时 / 传输失败 / 过载拒绝，摘除时长 10s × 次数（上限 60s），最多摘一半（[configuration.md](docs/configuration.md)、[A/B 探针](docs/probes/outlier-detection.md)）。
- `RpcClient.outlierEjections()` / `outlierRestores()` / `outlierAllEjectedFallbacks()` 计数；`steadyrpc-metrics-micrometer` 导出为 `rpc.client.outlier.*` 指标。
- 发布 jar 的 MANIFEST 带 `Automatic-Module-Name` 与 `Implementation-Title` / `Implementation-Version` / `Implementation-Vendor`。
- Netty 4.2 兼容验证：CI 的 `netty-42` 作业以 Netty 4.2.x 跑全量测试（Spring Boot 4 应用里是 Boot 管理的 4.2）。

### 变更（Changed）

- 依赖升级：Netty 4.1.139.Final、ZooKeeper 3.9.6、jackson 2.21.8、log4j 2.25.5（仅经 Spring Boot starter-logging 进入）。
- `steadyrpc-bom` 除本框架模块外管理 ZooKeeper 版本（Spring Boot 不管理它）；不管理 Netty / jackson，以免覆盖 Boot 应用的版本。
- `steadyrpc-registry-zookeeper` 排除 ZooKeeper 自带的 `io.netty:*`，Netty 一律来自 `steadyrpc-core`，classpath 上不会混入旧版。

### 安全（Security）

- 依赖树中 26 个 GHSA 公告清零（reactor 与使用方视角两棵树经 OSV 扫描 0 命中），处理规则与不适用说明见 [SECURITY.md「依赖漏洞」](SECURITY.md#依赖漏洞)。

### 文档（Docs）

- README 重构（架构图、归档数据生成的对比图），新增英文版 [README.en.md](README.en.md) 与配置参考 [docs/configuration.md](docs/configuration.md)。

## [1.0.0] - 2026-10-22

首个发布到 Maven Central 的版本（旧坐标 `io.github.sisyfy-zhang:rpc-*`，包 `com.feynman.rpc`；仍可从 Central 获取）。

[2.0.0]: https://github.com/sisyfy-Zhang/steady-rpc/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/sisyfy-Zhang/steady-rpc/releases/tag/v1.0.0
