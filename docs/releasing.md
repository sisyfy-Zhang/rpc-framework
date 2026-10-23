# 发布到 Maven Central

本仓库通过 [Central Portal](https://central.sonatype.com)（`central-publishing-maven-plugin`）发布到 Maven Central。
推送 `vX.Y.Z` tag 触发 [`.github/workflows/release.yml`](../.github/workflows/release.yml)：签名、打包、上传、等 Central 校验通过；
**最后一步"发布"由人在 Central Portal 上手动点**（`autoPublish=false`）。

## 发布面

| 发布（`io.github.sisyfy-zhang`） | 不发布（`maven.deploy.skip=true`，且在 central-publishing 的 `excludeArtifacts` 名单里） |
|---|---|
| `steadyrpc-parent`（pom）、`steadyrpc-bom`（pom）、`steadyrpc-core`、`steadyrpc-registry-tck`、`steadyrpc-registry-zookeeper`、`steadyrpc-registry-nacos`、`steadyrpc-serialize-fastjson2`、`steadyrpc-serialize-protobuf`、`steadyrpc-metrics-micrometer`、`steadyrpc-spring-boot-starter` | `steadyrpc-examples`、`steadyrpc-integration-tests`、`steadyrpc-benchmark`、`steadyrpc-bench-e2e`、`steadyrpc-bench-compare-grpc`、`steadyrpc-bench-compare-dubbo` |

每个 jar 模块上传 `.jar`、`-sources.jar`、`-javadoc.jar`、`.pom` 及各自的 `.asc` 签名（`steadyrpc-core` 的 `-tests.jar` 只给本仓库的 steadyrpc-integration-tests 用，**不发布**：release 用 `-Dmaven.test.skip=true` 构建，test-jar 目标随之跳过；唯一依赖它的 steadyrpc-integration-tests 本身不发布，release 构建用 `-pl '!steadyrpc-integration-tests'` 把它排除出 reactor，否则 test 作用域依赖解析失败）；
两个 pom 模块上传 `.pom` 与 `.asc`。校验和（md5 / sha1 / sha256 / sha512）由插件打包时生成。

- **groupId 与 Java 包名不同**：坐标是 `io.github.sisyfy-zhang`，包名是 `io.github.sisyfyzhang.steadyrpc.*`。Central 的 `io.github.<用户名>` 命名空间按 GitHub 账号验证；包名与之同源，只是去掉了 Java 包名里不允许的连字符。
- `steadyrpc-bom` **不继承** `steadyrpc-parent`（否则使用方 import BOM 时会连带导入父 pom 锁定的 Netty / Spring / Jackson 等第三方版本），
  所以它的版本号与 release profile 单独写在 `steadyrpc-bom/pom.xml`，改版本时要一起改。
- `central-publishing-maven-plugin` 以 Maven 扩展的方式接管**所有**模块的 deploy，**不看 `maven.deploy.skip`**；
  不发布的模块靠根 pom 里的 `excludeArtifacts`（按 artifactId）排除。新增模块时两处都要考虑。

### 2.0.0 改名说明

2.0.0 起项目改名为 steady-rpc：坐标 `rpc-*` → `steadyrpc-*`（groupId 不变），包 `com.feynman.rpc` → `io.github.sisyfyzhang.steadyrpc`，GitHub 仓库 `rpc-framework` → `steady-rpc`（旧 URL 由 GitHub 自动重定向）。`rpc.*` 配置属性、注解与类名、`META-INF/rpc/` 目录、线上协议均不变（[CHANGELOG](../CHANGELOG.md)）。发布 2.0.0 时额外注意：

- **旧坐标 1.0.0 不下架**：Central 上的 `rpc-*:1.0.0` 不可删除，也无需删除；已有使用方继续可用，迁移方式见 CHANGELOG。
- **javadoc.io**：新坐标 `steadyrpc-core` 在 Central 上可见之后，才能在 javadoc.io 上打开 `io.github.sisyfy-zhang/steadyrpc-core` 并点 Sync；发布前 README 的 javadoc 徽章会显示未找到。
- **Codecov**：仓库改名后在 Codecov 的仓库设置里点 Resync，让它按新仓库名关联上传；徽章 URL 已换成新仓库名。
- **Scorecard**：`api.scorecard.dev` 上的项目按仓库 URL 索引，徽章与查看链接已改为 `github.com/sisyfy-Zhang/steady-rpc`；新 URL 的分数要等 scorecard workflow 在 main 上跑过一次才出现。

## 一次性前置

1. **Central 账号与命名空间**：用 GitHub 账号 `sisyfy-Zhang` 登录 central.sonatype.com，`io.github.sisyfy-zhang` 命名空间会自动验证
   （若没有自动出现：Namespaces → Add Namespace，按提示在该 GitHub 账号下建一个以验证码命名的临时公开仓库，验证后删除）。
2. **User token**：Central Portal → 右上角账户 → *Generate User Token*，得到一对 username / password（不是登录密码）。
3. **GPG 密钥**（专用于发布，建议单独一把）：
   ```bash
   gpg --full-generate-key                       # RSA 4096 或 ed25519，设口令
   gpg --list-secret-keys --keyid-format long    # 记下 key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>   # Central 校验签名要能在公共 keyserver 上找到公钥
   gpg --keyserver keys.openpgp.org     --send-keys <KEYID>   # 可选，多发一个
   gpg --armor --export-secret-keys <KEYID> > private.asc     # 导出私钥，存进 Secret 后立即删除本地文件
   ```
4. **仓库 Secrets**（Settings → Secrets and variables → Actions，名字固定，release.yml 按名读取）：

   | Secret | 内容 |
   |---|---|
   | `CENTRAL_USERNAME` | 第 2 步 user token 的 username |
   | `CENTRAL_TOKEN` | 第 2 步 user token 的 password |
   | `GPG_PRIVATE_KEY` | 第 3 步导出的 ASCII armored 私钥（`private.asc` 全文） |
   | `GPG_PASSPHRASE` | 该私钥的口令 |

   ```bash
   gh secret set CENTRAL_USERNAME
   gh secret set CENTRAL_TOKEN
   gh secret set GPG_PRIVATE_KEY < private.asc && rm private.asc
   gh secret set GPG_PASSPHRASE
   ```

## 版本号规则

- 语义化版本 `MAJOR.MINOR.PATCH`。main 上永远是下一个版本的快照 `X.Y.Z-SNAPSHOT`；Central 只接收非 SNAPSHOT 版本。
- 版本号出现在：根 `pom.xml` 的 `<version>`、14 个子模块 `<parent>` 里的 `<version>`、`steadyrpc-bom/pom.xml` 的 `<version>`。
  三处必须一致；release.yml 会核对 tag 与根 pom、steadyrpc-bom 的版本，不一致直接失败。
- 已发布的版本**不可覆盖、不可删除**（见[回滚与重发](#回滚与重发)），所以任何改动都发新版本号。

## 依赖漏洞核对

发布步骤 1 之前做，三项都要过（第 2 项 (b) 的命中按 Boot 版本解读）（规则与版本线约束见 [SECURITY.md「依赖漏洞」](../SECURITY.md#依赖漏洞)）：

1. **reactor 依赖树 0 命中**：
   ```bash
   ./mvnw -B dependency:tree > tree.log
   python -I bench/tools/osv_scan.py tree.log      # 只用标准库，联网查 api.osv.dev；退出码 0 = 无命中
   ```
2. **使用方视角**：根 pom 的 `dependencyManagement` 对使用方不生效，所以再用两个消费者工程各解析一次。
   先 `./mvnw -B install -DskipTests` 把当前版本装进本地仓库，在仓库外各建一个目录放 `pom.xml`（`rpc.version` 换成根 pom 的 `<version>`）。

   **(a) 不导入任何 BOM**（非 Boot 应用：Netty / jackson / ZooKeeper 全由本框架的直接依赖决定），要求 **0 命中**：
   ```xml
   <project xmlns="http://maven.apache.org/POM/4.0.0">
     <modelVersion>4.0.0</modelVersion>
     <groupId>tmp.vuln</groupId><artifactId>consumer-nobom</artifactId><version>0</version>
     <properties><rpc.version>X.Y.Z</rpc.version></properties>
     <dependencies>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-core</artifactId><version>${rpc.version}</version></dependency>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-registry-zookeeper</artifactId><version>${rpc.version}</version></dependency>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-registry-nacos</artifactId><version>${rpc.version}</version></dependency>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-serialize-protobuf</artifactId><version>${rpc.version}</version></dependency>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-serialize-fastjson2</artifactId><version>${rpc.version}</version></dependency>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-metrics-micrometer</artifactId><version>${rpc.version}</version></dependency>
     </dependencies>
   </project>
   ```

   **(b) Spring Boot 父 pom + steadyrpc-bom**（Boot 应用的典型写法）：这里的 Netty / jackson 由 Boot 的 BOM 决定，**命中按 Boot 版本解读**——
   落在 Boot 管理的构件上的命中随 Boot 补丁版本修复（SECURITY.md「依赖漏洞」），不算本框架的阻断项；落在 ZooKeeper 或本框架直接依赖上的命中仍要 0。
   `<parent>` 的版本用本框架当前测试的 Boot 版本（根 pom 的 `spring-boot.version`）：
   ```xml
   <project xmlns="http://maven.apache.org/POM/4.0.0">
     <modelVersion>4.0.0</modelVersion>
     <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>4.1.0</version><relativePath/></parent>
     <groupId>tmp.vuln</groupId><artifactId>consumer-bootparent</artifactId><version>0</version>
     <properties><rpc.version>X.Y.Z</rpc.version></properties>
     <dependencyManagement><dependencies>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-bom</artifactId><version>${rpc.version}</version><type>pom</type><scope>import</scope></dependency>
     </dependencies></dependencyManagement>
     <dependencies>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-spring-boot-starter</artifactId></dependency>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-registry-zookeeper</artifactId></dependency>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-registry-nacos</artifactId></dependency>
       <dependency><groupId>io.github.sisyfy-zhang</groupId><artifactId>steadyrpc-serialize-protobuf</artifactId></dependency>
     </dependencies>
   </project>
   ```
   ```bash
   # 在仓库根目录执行，用同一个 wrapper；两个目录各跑一次
   ./mvnw -B -f <消费者目录>/pom.xml dependency:tree > consumer-tree.log
   python -I bench/tools/osv_scan.py consumer-tree.log
   ```
   顺带看一眼两份树里的 `io.netty:*` 是否各自只有一个版本（(a) 为根 pom 的 4.1 线，(b) 为 Boot 管理的 4.2 线）。
3. **Scorecard**：main 上最近一次 scorecard workflow 的 Vulnerabilities 检查为 10/10（`gh workflow run scorecard.yml` 可手动触发；
   分数在 Security → Code scanning 或 `api.scorecard.dev` 上看）。

有命中时按 SECURITY.md 处理：版本线内有修复版本就升级；确属不适用的写进 SECURITY.md「不适用说明」，不在发布前静默放过。

## 发布步骤

以 `2.0.0` 为例，在 main 上、工作区干净、CI 全绿、[依赖漏洞核对](#依赖漏洞核对)通过（下面给人看的命令写 `mvn`，本机 Maven 3.9.x 即可；CI 用仓库里的 `./mvnw`，Maven 版本固定为 3.9.12）：

```bash
# 1. 去掉 -SNAPSHOT（只改 pom 里的版本号；改完核对再没有 $V-SNAPSHOT）
V=2.0.0
git grep -l "<version>$V-SNAPSHOT</version>" -- '*pom.xml' | xargs sed -i "s#<version>$V-SNAPSHOT</version>#<version>$V</version>#"
git grep -n "$V-SNAPSHOT" -- '*pom.xml'             # 应无输出
mvn -B clean install                                 # 全量构建 + 测试在本机再过一遍
git commit -am "发布 2.0.0" && git push origin main  # 等 ci.yml 通过

# 2. 打 tag 并推送，触发 release.yml
git tag -a v2.0.0 -m "steady-rpc 2.0.0"
git push origin v2.0.0

# 3. 发布后回到快照
git grep -l '<version>2.0.0</version>' -- '*pom.xml' | xargs sed -i 's#<version>2.0.0</version>#<version>2.0.1-SNAPSHOT</version>#'
git grep -n '<version>2.0.0</version>' -- '*pom.xml' # 应无输出（第三方依赖都走属性，不会误伤；仍要看一眼 diff）
git commit -am "版本回到 2.0.1-SNAPSHOT" && git push origin main
```

## release.yml 做了什么

1. checkout；`actions/setup-java`（temurin 21，maven 缓存，与 ci.yml 相同），并用 `server-id: central` 生成 `~/.m2/settings.xml`，
   用户名 / 密码写成 `${env.CENTRAL_USERNAME}` / `${env.CENTRAL_TOKEN}`，真实值只在 deploy 步骤的环境变量里出现。
2. `gpg --batch --import` 导入 `GPG_PRIVATE_KEY`。
3. 核对 tag 与版本：`v2.0.0` 必须对应根 pom 与 steadyrpc-bom 的 `2.0.0`，且不是 SNAPSHOT。
4. 先 `./mvnw -v`（打出 Maven / resolver 版本；wrapper 把 Maven 固定为 3.9.12，不用 runner 镜像自带的 mvn），再 `./mvnw -Prelease -Dmaven.test.skip=true -Djacoco.skip=true -pl '!steadyrpc-integration-tests' -Dmaven.install.skip=true deploy`（`MAVEN_GPG_PASSPHRASE` 来自 `GPG_PASSPHRASE`）：release profile 生成 sources / javadoc jar（javadoc 的
   doclint 为 `all,-missing`，有错误即失败）、gpg 签名（`--pinentry-mode loopback`），central-publishing 把发布模块打成一个 bundle 上传，
   等到 Central 校验通过（`waitUntil=validated`）。测试由 ci.yml 保证，这里用 `-Dmaven.test.skip=true` 连测试编译一起跳过（因此 steadyrpc-core 的 test-jar 也不生成、不发布）。
5. 把各发布模块 `target/` 下的 jar / pom / asc 与 central-publishing 的 bundle 作为 workflow artifact 上传，便于核对。

## 在 Central Portal 上发布

release.yml 成功后，到 central.sonatype.com → *Publish* → *Deployments*：

- 状态应为 **VALIDATED**。展开核对组件清单（10 个组件，每个的文件齐全、签名通过）。
- 确认无误点 **Publish**；状态经 PUBLISHING → **PUBLISHED**，通常十几分钟到几十分钟后在 Maven Central 搜索与 `repo1.maven.org` 上可见。
- 有问题点 **Drop** 丢弃这次 deployment（未发布前可以丢弃，不占用版本号）。

## 回滚与重发

- **未点 Publish 之前**（VALIDATED / FAILED）：在 Portal 上 Drop；修复后可以用**同一个版本号**重来——删除远端与本地 tag
  （`git push origin :refs/tags/v2.0.0 && git tag -d v2.0.0`），把修复提交到 main，重新打 tag 推送。
- **已经 PUBLISHED 之后**：Central 上的版本**不可覆盖、不可删除、不可撤回**。有问题只能修复后发新版本（如 `2.0.1`），
  并在 README / Release Notes 里说明旧版本的问题。不要试图复用已发布的版本号或移动已发布版本的 tag。
- release.yml 在上传前失败（签名、javadoc、版本核对）：Central 侧没有任何东西，修复后按"未点 Publish 之前"的方式重打 tag。

## 本地演练（不上传）

**只在 CI（Linux）上真正发布。** 在 Windows 上 central-publishing 打出的 bundle 里条目路径是反斜杠（`io\github\...`），不要从 Windows 本机上传。
本地只做演练，且不碰自己的钥匙环：用临时目录与一次性测试密钥。下面的命令在 Git Bash 里用 `./mvnw`（与 release.yml 同一个 Maven 3.9.12；
wrapper 把发行包下载到 `$HOME/.m2/wrapper`，改了 HOME 会在临时目录里再下载一次）；在 Windows 的 cmd / PowerShell 里把 `./mvnw` 换成 `mvnw.cmd`。

```bash
# Git Bash 的 gpg 是 MSYS 程序：GNUPGHOME 若写成 G:/... 会被当成相对路径；Maven（java.exe）再拉起 gpg 时只有 HOME 会被换回 POSIX 路径，
# 所以用临时 HOME（Java 在 Windows 上的 user.home 不看 HOME，~/.m2 不受影响），钥匙环落在 $HOME/.gnupg
export HOME=/g/tmp/release-rehearsal && mkdir -p "$HOME/.gnupg"
gpg --batch --passphrase 'test-pass' --pinentry-mode loopback --quick-generate-key "rpc-ci-test <noreply@example.com>" rsa2048 sign 1d
KEYID=$(gpg --list-secret-keys --with-colons | awk -F: '/^sec/{print $5; exit}')

# 1. 产物与签名：每个发布模块的 target/ 下应有 .jar / -sources.jar / -javadoc.jar / .pom 与各自的 .asc，steadyrpc-bom 与根目录只有 .pom 与 .asc
MAVEN_GPG_PASSPHRASE=test-pass ./mvnw -Prelease -Dmaven.test.skip=true -Djacoco.skip=true -pl '!steadyrpc-integration-tests' -Dmaven.install.skip=true -Dgpg.keyname=$KEYID verify

# 2. 仓库目录结构：绕开 deploy 阶段（那里是 central-publishing），直接调 maven-deploy-plugin 写进本地文件仓库；
#    它认 maven.deploy.skip，结果应只有 io/github/sisyfy-zhang/ 下 10 个 artifactId
MAVEN_GPG_PASSPHRASE=test-pass ./mvnw -Prelease -Dmaven.test.skip=true -Djacoco.skip=true -pl '!steadyrpc-integration-tests' -Dmaven.install.skip=true -Dgpg.keyname=$KEYID verify \
  org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy -DaltDeploymentRepository=local::file:///G:/tmp/staging

gpg --batch --yes --delete-secret-and-public-key <FINGERPRINT>
```

想看 central-publishing 真正打出的 bundle：在**版本已改成非 SNAPSHOT 的副本**上（不要在工作区里改），用一个只含假凭据的 settings.xml，
把上传地址指向本机一个没人监听的端口，并跳过 install（免得把测试签名的 2.0.0 装进本地仓库）——这条命令与 release.yml 的 deploy 步骤逐字相同，
只多了假凭据、测试密钥与上传地址。**还要用与 CI 相同的 Maven 版本**：CI（ci.yml / release.yml）用 `./mvnw`，版本由 `.mvn/wrapper/maven-wrapper.properties` 固定为 3.9.12，
本地演练同样用 `./mvnw`。bundle 的内容随 Maven 与 central-publishing 的版本组合而变：此前 CI 用 runner 镜像自带的 Maven，
ubuntu-24.04 镜像 20261018 升到 3.10.0（resolver 2）后 bundle 混进了本地仓库跟踪文件。
演练后核对 bundle 里只有 `io/github/sisyfy-zhang/<artifactId>/<版本>/` 下的文件，没有 `maven-metadata-*.xml` 与 `_remote.repositories`：

```bash
./mvnw -s settings-dummy.xml -Prelease -Dmaven.test.skip=true -Djacoco.skip=true -pl '!steadyrpc-integration-tests' -Dmaven.install.skip=true -Dgpg.keyname=$KEYID \
    -DcentralBaseUrl=http://127.0.0.1:9 deploy
# 上传必然失败（连接被拒），但 bundle 已生成：steadyrpc-bom/target/central-publishing/central-bundle.zip（工作目录在 reactor 第一个模块下）
unzip -l steadyrpc-bom/target/central-publishing/central-bundle.zip
```

注意 `-DskipPublishing=true` 在 0.11.0 里会把所有构件在暂存前就过滤掉，**不会**生成 bundle，不能用来演练打包。
