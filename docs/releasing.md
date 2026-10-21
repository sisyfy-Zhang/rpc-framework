# 发布到 Maven Central

本仓库通过 [Central Portal](https://central.sonatype.com)（`central-publishing-maven-plugin`）发布到 Maven Central。
推送 `vX.Y.Z` tag 触发 [`.github/workflows/release.yml`](../.github/workflows/release.yml)：签名、打包、上传、等 Central 校验通过；
**最后一步"发布"由人在 Central Portal 上手动点**（`autoPublish=false`）。

## 发布面

| 发布（`io.github.sisyfy-zhang`） | 不发布（`maven.deploy.skip=true`，且在 central-publishing 的 `excludeArtifacts` 名单里） |
|---|---|
| `rpc-parent`（pom）、`rpc-bom`（pom）、`rpc-core`、`rpc-registry-tck`、`rpc-registry-zookeeper`、`rpc-registry-nacos`、`rpc-serialize-fastjson2`、`rpc-serialize-protobuf`、`rpc-metrics-micrometer`、`rpc-spring-boot-starter` | `rpc-examples`、`rpc-integration-tests`、`rpc-benchmark`、`rpc-bench-e2e`、`rpc-bench-compare-grpc`、`rpc-bench-compare-dubbo` |

每个 jar 模块上传 `.jar`、`-sources.jar`、`-javadoc.jar`、`.pom` 及各自的 `.asc` 签名（`rpc-core` 的 `-tests.jar` 只给本仓库的 rpc-integration-tests 用，**不发布**：release 用 `-Dmaven.test.skip=true` 构建，test-jar 目标随之跳过；唯一依赖它的 rpc-integration-tests 本身不发布，release 构建用 `-pl '!rpc-integration-tests'` 把它排除出 reactor，否则 test 作用域依赖解析失败）；
两个 pom 模块上传 `.pom` 与 `.asc`。校验和（md5 / sha1 / sha256 / sha512）由插件打包时生成。

- **groupId 与 Java 包名不同**：坐标是 `io.github.sisyfy-zhang`，包名是 `com.feynman.rpc.*`。Central 的 `io.github.<用户名>` 命名空间按 GitHub 账号验证，包名不受此约束，不改。
- `rpc-bom` **不继承** `rpc-parent`（否则使用方 import BOM 时会连带导入父 pom 锁定的 Netty / Spring / Jackson 等第三方版本），
  所以它的版本号与 release profile 单独写在 `rpc-bom/pom.xml`，改版本时要一起改。
- `central-publishing-maven-plugin` 以 Maven 扩展的方式接管**所有**模块的 deploy，**不看 `maven.deploy.skip`**；
  不发布的模块靠根 pom 里的 `excludeArtifacts`（按 artifactId）排除。新增模块时两处都要考虑。

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
- 版本号出现在：根 `pom.xml` 的 `<version>`、14 个子模块 `<parent>` 里的 `<version>`、`rpc-bom/pom.xml` 的 `<version>`。
  三处必须一致；release.yml 会核对 tag 与根 pom、rpc-bom 的版本，不一致直接失败。
- 已发布的版本**不可覆盖、不可删除**（见[回滚与重发](#回滚与重发)），所以任何改动都发新版本号。

## 发布步骤

以 `1.0.0` 为例，在 main 上、工作区干净、CI 全绿：

```bash
# 1. 去掉 -SNAPSHOT（只改 pom 里的版本号；改完核对再没有 1.0.0-SNAPSHOT）
git grep -l '<version>1.0.0-SNAPSHOT</version>' -- '*pom.xml' | xargs sed -i 's#<version>1.0.0-SNAPSHOT</version>#<version>1.0.0</version>#'
git grep -n '1.0.0-SNAPSHOT' -- '*pom.xml'          # 应无输出
mvn -B clean install                                 # 全量构建 + 测试在本机再过一遍
git commit -am "发布 1.0.0" && git push origin main  # 等 ci.yml 通过

# 2. 打 tag 并推送，触发 release.yml
git tag -a v1.0.0 -m "rpc-framework 1.0.0"
git push origin v1.0.0

# 3. 发布后回到快照
git grep -l '<version>1.0.0</version>' -- '*pom.xml' | xargs sed -i 's#<version>1.0.0</version>#<version>1.0.1-SNAPSHOT</version>#'
git grep -n '<version>1.0.0</version>' -- '*pom.xml' # 应无输出（第三方依赖都走属性，不会误伤；仍要看一眼 diff）
git commit -am "版本回到 1.0.1-SNAPSHOT" && git push origin main
```

## release.yml 做了什么

1. checkout；`actions/setup-java`（temurin 21，maven 缓存，与 ci.yml 相同），并用 `server-id: central` 生成 `~/.m2/settings.xml`，
   用户名 / 密码写成 `${env.CENTRAL_USERNAME}` / `${env.CENTRAL_TOKEN}`，真实值只在 deploy 步骤的环境变量里出现。
2. `gpg --batch --import` 导入 `GPG_PRIVATE_KEY`。
3. 核对 tag 与版本：`v1.0.0` 必须对应根 pom 与 rpc-bom 的 `1.0.0`，且不是 SNAPSHOT。
4. `mvn -Prelease -Dmaven.test.skip=true -Djacoco.skip=true -pl '!rpc-integration-tests' deploy`（`MAVEN_GPG_PASSPHRASE` 来自 `GPG_PASSPHRASE`）：release profile 生成 sources / javadoc jar（javadoc 的
   doclint 为 `all,-missing`，有错误即失败）、gpg 签名（`--pinentry-mode loopback`），central-publishing 把发布模块打成一个 bundle 上传，
   等到 Central 校验通过（`waitUntil=validated`）。测试由 ci.yml 保证，这里用 `-Dmaven.test.skip=true` 连测试编译一起跳过（因此 rpc-core 的 test-jar 也不生成、不发布）。
5. 把各发布模块 `target/` 下的 jar / pom / asc 与 central-publishing 的 bundle 作为 workflow artifact 上传，便于核对。

## 在 Central Portal 上发布

release.yml 成功后，到 central.sonatype.com → *Publish* → *Deployments*：

- 状态应为 **VALIDATED**。展开核对组件清单（10 个组件，每个的文件齐全、签名通过）。
- 确认无误点 **Publish**；状态经 PUBLISHING → **PUBLISHED**，通常十几分钟到几十分钟后在 Maven Central 搜索与 `repo1.maven.org` 上可见。
- 有问题点 **Drop** 丢弃这次 deployment（未发布前可以丢弃，不占用版本号）。

## 回滚与重发

- **未点 Publish 之前**（VALIDATED / FAILED）：在 Portal 上 Drop；修复后可以用**同一个版本号**重来——删除远端与本地 tag
  （`git push origin :refs/tags/v1.0.0 && git tag -d v1.0.0`），把修复提交到 main，重新打 tag 推送。
- **已经 PUBLISHED 之后**：Central 上的版本**不可覆盖、不可删除、不可撤回**。有问题只能修复后发新版本（如 `1.0.1`），
  并在 README / Release Notes 里说明旧版本的问题。不要试图复用已发布的版本号或移动已发布版本的 tag。
- release.yml 在上传前失败（签名、javadoc、版本核对）：Central 侧没有任何东西，修复后按"未点 Publish 之前"的方式重打 tag。

## 本地演练（不上传）

**只在 CI（Linux）上真正发布。** 在 Windows 上 central-publishing 打出的 bundle 里条目路径是反斜杠（`io\github\...`），不要从 Windows 本机上传。
本地只做演练，且不碰自己的钥匙环：用临时目录与一次性测试密钥。

```bash
# Git Bash 的 gpg 是 MSYS 程序：GNUPGHOME 若写成 G:/... 会被当成相对路径；Maven（java.exe）再拉起 gpg 时只有 HOME 会被换回 POSIX 路径，
# 所以用临时 HOME（Java 在 Windows 上的 user.home 不看 HOME，~/.m2 不受影响），钥匙环落在 $HOME/.gnupg
export HOME=/g/tmp/release-rehearsal && mkdir -p "$HOME/.gnupg"
gpg --batch --passphrase 'test-pass' --pinentry-mode loopback --quick-generate-key "rpc-ci-test <noreply@example.com>" rsa2048 sign 1d
KEYID=$(gpg --list-secret-keys --with-colons | awk -F: '/^sec/{print $5; exit}')

# 1. 产物与签名：每个发布模块的 target/ 下应有 .jar / -sources.jar / -javadoc.jar / .pom 与各自的 .asc，rpc-bom 与根目录只有 .pom 与 .asc
MAVEN_GPG_PASSPHRASE=test-pass mvn -Prelease -Dmaven.test.skip=true -Djacoco.skip=true -pl '!rpc-integration-tests' -Dgpg.keyname=$KEYID verify

# 2. 仓库目录结构：绕开 deploy 阶段（那里是 central-publishing），直接调 maven-deploy-plugin 写进本地文件仓库；
#    它认 maven.deploy.skip，结果应只有 io/github/sisyfy-zhang/ 下 10 个 artifactId
MAVEN_GPG_PASSPHRASE=test-pass mvn -Prelease -Dmaven.test.skip=true -Djacoco.skip=true -pl '!rpc-integration-tests' -Dgpg.keyname=$KEYID verify \
  org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy -DaltDeploymentRepository=local::file:///G:/tmp/staging

gpg --batch --yes --delete-secret-and-public-key <FINGERPRINT>
```

想看 central-publishing 真正打出的 bundle：在**版本已改成非 SNAPSHOT 的副本**上（不要在工作区里改），用一个只含假凭据的 settings.xml，
把上传地址指向本机一个没人监听的端口，并跳过 install（免得把测试签名的 1.0.0 装进本地仓库）：

```bash
mvn -s settings-dummy.xml -Prelease -Dmaven.test.skip=true -Djacoco.skip=true -pl '!rpc-integration-tests' -Dmaven.install.skip=true -Dgpg.keyname=$KEYID \
    -DcentralBaseUrl=http://127.0.0.1:9 deploy
# 上传必然失败（连接被拒），但 bundle 已生成：rpc-bom/target/central-publishing/central-bundle.zip（工作目录在 reactor 第一个模块下）
unzip -l rpc-bom/target/central-publishing/central-bundle.zip
```

注意 `-DskipPublishing=true` 在 0.11.0 里会把所有构件在暂存前就过滤掉，**不会**生成 bundle，不能用来演练打包。
