# 构建与发行

## 环境

- JDK 21。`build.gradle.kts` 的 toolchain 固定为 21。
- 宿主 `gcc`。`:worker:compileDeviceEgl` 用它编译 `worker/src/main/native/device_egl.c`，产出 `native/linux-x86_64/libdevice-egl.so`；`:worker:processResources` 依赖该编译。
- Gradle 9.5.0 官方 wrapper，分发包 SHA-256 由 wrapper 校验。
- 联网准备依赖时按需设置 `-Dhttp.proxyHost`、`-Dhttp.proxyPort`、`-Dhttps.proxyHost`、`-Dhttps.proxyPort`。

## 依赖准备

runtime 依赖三个 fork 制品仓库，仓库本身由本仓之外的发行流程生成。

unidbg 基准为 `0.13.1`，源码在 `https://github.com/ai-repo-save/unidbg`。发行脚本输出标准 Maven-layout 仓库：

```sh
(
  cd "$HOME/git/unidbg"
  uv run --script scripts/release.py \
    --baseline 129151171590d0cfaaf46f98f93f1ac5f97fc401 \
    --java-home /usr/lib/jvm/java-21-openjdk-amd64 \
    --repository "$HOME/git/unidbg/target/release-0.13.1"
)
```

该基线提交对应上游 `fix(android): resolve publicly accessible method declarations`。输出目录下的 `release-manifest.json` 记录 `baseline_commit`、`release_commit` 与本次发行的命令。

Robolectric fork 源码在 `https://github.com/ai-repo-save/robolectric`。core 与完整 framework shadows 同时为 `1.3.0`；core POM 与 Gradle metadata 直接指向 fork framework。包来源 API 来自 core 提交 `786e1d627127434a6c31d86c1cafc031ab0f241c`，`ShadowBuild.setDisplay` 来自 `11038b7cd4addf3c9463a63f16def30ec5df2134`。在 fork 源码目录运行：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew \
  --offline --max-workers=2 -PsdkCheckApis=30 \
  :robolectric:identityIntegrationTest \
  --tests org.robolectric.StartupIdentityIntegrationTest \
  :shadows-framework:publishRuntimeShadowsPublicationToRuntimeLocalRepository \
  :robolectric:publishRuntimeCorePublicationToRuntimeLocalRepository
```

命令完整编译两个模块，并只检查 API 30。发布结果进入 fork 仓库配置的 Runtime local repository，通过 `-ProbolectricRepository` 提供给 runtime 构建。

APK 元数据解析用 `io.github.ai-repo-save.apk-parser:apk-parser:2.6.11`，源码在 `https://github.com/ai-repo-save/apk-parser`。该 fork 以原版 2.6.10 为基线，包含官方已合并的空资源表修复，按其仓库 `docs/empty-resource-table.md` 发布标准 Maven-layout。SPI 的 unidbg 依赖边排除原版 `net.dongliu:apk-parser`，依赖图只保留 fork。

## 构建 worker 安装

```sh
./gradlew --no-daemon \
  -PapkParserRepository=/path/to/apk-parser-release-repository \
  -ProbolectricRepository=/path/to/robolectric-release-repository \
  -PunidbgRepository=/path/to/unidbg-release-repository \
  :worker:installDist
```

三个 `-P` 属性都注册带 group 过滤的 Maven 仓库。仓库集合还包括 `mavenLocal()`（unidbg、robolectric、unicorn、capstone、keystone、demumble 与 `android-all-instrumented`）、`mavenCentral()`，以及 `google()` 的 `androidx.*` 与 `com.android.tools.build:apksig`。省略 `-PunidbgRepository` 或 `-ProbolectricRepository` 时，这两个 group 从 `mavenLocal()` 解析；`apk-parser` group 只由 `-PapkParserRepository` 提供。

安装目录为 `worker/build/<version>/install/unidbg-worker`。内容为 `bin/`、`lib/`、`sdk/`、`runtime-manifest.json`、`jvm-args.txt`、`LICENSE`：

- `bin/unidbg-worker` 为启动脚本，主类 `io.github.aireposave.unidbg.runtime.worker.WorkerMain`。
- `lib/` 为 runtime classes 与运行依赖。
- `sdk/` 为 `org.robolectric:android-all-instrumented:11-robolectric-6757853-i7`。
- `jvm-args.txt` 为 `applicationDefaultJvmArgs`，即 9 个 `--add-opens` 参数。
- `runtime-manifest.json` 记录 `version`、`sourceRepository`、`sourceCommit`、`sourceDirty`、`java`、`androidSdk`、`robolectric`、`robolectricShadows`、`robolectricSupport`、`unidbg`、`sdk`、`worker`、`dependencies`。

Android 会话从 `runtime-manifest.json` 读取 `sdk.file`，把 `robolectric.offline` 设为 `true`，把 `robolectric.dependency.dir` 指向该文件所在目录。SDK 文件缺失时启动失败。运行期不需要 Gradle、Maven 或网络。

Gradle 通过 artifact transform 从 AndroidX AAR 提取 `classes.jar`。该转换属于本仓构建，不随 POM 传播给消费者。

## 发行仓库

`releaseLocal` 组装标准 Maven-layout 仓库并生成一份 worker 安装：

```sh
./gradlew --offline --no-daemon \
  -PunidbgRepository=/path/to/unidbg-release-repository \
  -ProbolectricRepository=/path/to/robolectric-release-repository \
  -PapkParserRepository=/path/to/apk-parser-release-repository \
  releaseLocal
```

`releaseLocal` 复制三个输入仓库的制品与元数据，发布 client、spi、worker 的 JAR、sources 与 javadoc，执行 `:worker:installDist`，写 `runtime-release-manifest.json` 和 `apk-parser-release-manifest.json`。输出目录为 `build/<version>/repository`。`runtime-release-manifest.json` 记录 `version`、`sourceRepository`、`sourceCommit`、三个公共坐标与依赖坐标。

Maven publication 不含 worker 压缩包。压缩包由 `:worker:distZip` 或 `:worker:distTar` 显式生成，输出到 `worker/build/<version>/distributions/`。`publishToMavenLocal` 与本流程独立，需要时单独执行。

## 开发变体

`-PdevelopmentCoreClasspath` 存在时启用开发变体，其余时间不注册相关任务。该属性指向一个文本文件，内容为平台路径分隔符连接的核心 classes 目录与依赖 JAR 路径：

```sh
./gradlew -PdevelopmentCoreClasspath=/absolute/core/classpath.txt \
  -PdevelopmentConsumerClasspath=/absolute/consumer-classpath.txt \
  -ProbolectricRepository=/path/to/robolectric-release-repository \
  -PapkParserRepository=/path/to/apk-parser-release-repository \
  developmentInstall
```

- 目录按位置打包为 `development-core-<i>.jar`，输出到 `build/development/core/`。
- 依赖 JAR 按相邻 POM 声明坐标，并使用 POM 所在目录作为仓库。
- `io.github.ai-repo-save.unidbg` 的 `unidbg-api`、`unidbg-android`、`unidbg-unicorn2` 由选定的核心 classes 替代。
- `-PdevelopmentConsumerClasspath` 指定的路径从声明依赖中剔除，由 runtime 自身的依赖声明提供。
- 各项目输出位于自身 `build/development/`；worker 安装位于 `worker/build/development/install/unidbg-worker/`，消费者用其中的 `lib/*.jar` 编译。

`developmentJniIntegration` 只运行 `RuntimeIntegrationTest.independentWorkersAndNativeSideEffects`，需要同时提供 `-PdevelopmentCoreClasspath`。

## 目录与缓存

各项目的 `buildDirectory` 为自身目录下的 `build/<version>`，不同版本的安装与证据互不覆盖。Gradle 缓存、SDK 缓存与构建产物不入 Git。
