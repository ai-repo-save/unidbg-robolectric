# unidbg-robolectric

一个完整分析会话对应一个独立 JDK 21 工作 JVM。会话内复用 unidbg emulator、VM、JNI callbacks、调试器和业务状态；持久服务运行到 close / EOF，不按 JNI 或请求重建，也没有默认空闲超时。

## 公共制品

- `io.github.ai-repo-save:unidbg-robolectric-client:1.2.1`：轻量进程启动、JSON 通道、结果和取消。
- `io.github.ai-repo-save:unidbg-robolectric-spi:1.2.1`：工作入口、资源登记、JNI handler / owner delegate / observer。
- `io.github.ai-repo-save:unidbg-robolectric-worker:1.2.1`：官方 Android 生命周期的工作入口 JAR。
- 底座为 `io.github.ai-repo-save.unidbg:*:0.13.1`，保留原 Java 包和 native JNI 名称。
- Robolectric core 为 `io.github.ai-repo-save.robolectric:robolectric:1.2.0`，正式传递依赖完整 `io.github.ai-repo-save.robolectric:shadows-framework:1.2.0`，替代官方同名框架制品；其余官方支持模块为 4.17。Android 11 / API 30，SDK 为 `android-all-instrumented:11-robolectric-6757853-i7`。

支持 Linux x86-64、ARM64 Unicorn2。native resolver API、rootfs、自定义 emulator/syscall、有序 ELF 装载、post-map hooks、JNI_OnLoad 和业务回调由工作入口决定。native-only 不初始化 Robolectric，也不要求预插桩 SDK 目录。

公共签名、Profile schema、direct main 与 worker 内 JUnit5 接入合同位于 `docs/runtime-api.md`。

## 构建与安装

先准备经过独立审查的正式依赖，所有构建使用 JDK 21。unidbg 仓库为 `https://github.com/ai-repo-save/unidbg`，要求 `0.13.1`，固定源码提交由其 `target/release-0.13.1/release-manifest.json` 记录。从该提交的干净源码运行正式发行脚本：

```sh
(
  cd "$HOME/git/unidbg"
  uv run --script scripts/release.py \
    --baseline 129151171590d0cfaaf46f98f93f1ac5f97fc401 \
    --java-home /usr/lib/jvm/java-21-openjdk-amd64 \
    --repository "$HOME/git/unidbg/target/release-0.13.1"
)
```

Robolectric fork 仓库为 `https://github.com/ai-repo-save/robolectric`，core 与完整 framework shadows 均要求 `1.2.0`，源码提交为 `6395ac1d4284b661b58ffd889baffccb998eb5b3`，由两个制品各自的 `*-1.2.0.provenance.json` 记录。完整 framework 在指定服务器 `root@192.168.50.8` 的 `/root/git/robolectric-runtime` 构建；从该提交的干净源码执行：

```sh
uv run --script scripts/release-local.py \
  --java-home /usr/lib/jvm/java-21-openjdk-amd64 --no-proxy
```

将服务器 `build/repository` 中的两个正式 fork 制品及各自 provenance 保持 Maven-layout 提供给 runtime 构建，通过 `-ProbolectricRepository=/path/to/robolectric-release-repository` 解析。使用本地 Maven 时，须先将这两个已发布制品安装齐备。core 的 POM 和 Gradle metadata 已指向 fork framework；消费者只解析这一份 framework，不叠加 `org.robolectric:shadows-framework:4.17`。依赖正式发行齐备后再构建 runtime，不以相邻源码或旧制品替代。

在本仓构建。首次依赖与 SDK 准备只在此阶段联网：

```sh
./gradlew --no-daemon \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7891 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7891 \
  :worker:installDist
```

安装目录为 `worker/build/install/unidbg-worker`。Gradle 在发行构建内集中提取 AndroidX AAR 的 classes.jar，并显式准备预插桩 SDK；这些转换不要求复制到消费者构建脚本，也不声称会随 Maven POM 传播。Gradle、资源和 SDK 缓存不入 Git。

```sh
./gradlew --offline --no-daemon jniIntegration
```

会话启动检查 API 版本、所选 SDK 和必需文件。runtime-manifest 记录版本、源码 Git 提交、两个 Robolectric fork 制品坐标、完整运行依赖、JDK/API 和 SDK 路径等构建事实。Gradle/Maven 负责依赖下载及其自身校验。

`jniIntegration` 是发行整体集成入口，不进入默认 test/check。每次运行重新执行，XML/HTML 与工作会话产物保存在独立 `build/evidence/<UTC 时间>/`。运行日志为 stderr 和每会话 `logs/worker.jsonl`；原始诊断、完整工作 JVM maps、Android 数据副本与结果文件分别保存。证据保留到显式 clean；活动 worker 期间不 clean 或覆盖安装。

构建使用 Gradle 9.5.0 官方 wrapper，发行下载校验由 wrapper 处理。

## 正式发行

源码库为公开的 `https://github.com/ai-repo-save/unidbg-robolectric`。POM、runtime-manifest 与 release manifest 记录源码固定提交、Maven GAV 和依赖坐标。首次远端/CI 构建可通过 HTTPS 获取固定提交的三个公开源码仓库，不需要私有 token，不发布大型 SDK 二进制到 Git。

完成显式集成验证、独立审查并取得发行授权后，从干净提交创建标准 Maven-layout 仓库和一份 worker 安装：

```sh
./gradlew --offline --no-daemon \
  -PunidbgRepository=/path/to/unidbg-release-repository \
  -ProbolectricRepository=/path/to/robolectric-release-repository \
  releaseLocal
```

`validateRelease` 检查干净源码、unidbg 0.13.1 的正式 release receipt 与所需 POM/JAR，以及 Robolectric core/framework 1.2.0 各自的 POM/module/JAR/provenance。输出 `build/repository` 包含库的 JAR 制品、原坐标的 unidbg fork，以及 Robolectric core 和完整 framework 两个 fork 制品；外部 fork 的 POM/module/provenance 保留原样，release manifest 显式列出两个 Robolectric 坐标。Maven publication 不绑定 worker 压缩包，`releaseLocal` 只发布 JAR 并生成一份安装。JAR、安装目录及 manifest 来自同一源码版本。正式版本不可被重新覆盖；开发阶段安装重建须等待使用者退出，不维护运行池或镜像管理器。

本机 Maven 安装在同次发行授权下另行执行 `./gradlew --offline --no-daemon publishToMavenLocal`。需要压缩包时显式执行 `:worker:distZip` 或 `:worker:distTar`。

Docker 可通过明确的 Maven-layout 和 offline distribution 命名 context 消费制品。运行镜像只需 JDK 21、可写的临时/会话目录，不需要 Gradle/Maven或联网下载 SDK。源码推送由发布协调者执行；此流程不部署服务或发布容器。

## 已验证与边界

显式整体验证覆盖：

- 真实 Unicorn2 ARM64 JNI 表调用 Settings.Global/Secure、Build.MODEL、PackageManager 和 SharedPreferences，宿主公开 API 独立读回精确值。
- 独立工作 JVM、Application/PM/Process UID 与进程名、同会话多步和持久消息状态。
- exact handler 优先、业务 owner、显式 Java proxy owner、公开接口继承链（含真实 Conscrypt 公钥 getEncoded）、metadata 查询不执行结果 handler、标准 Proxy observer。
- 相同 Profile 派生的 Java MODEL/native property；ARM64 libc 从 guest 挂载读取真实偏好文件，并写入宿主公开 Application 目录可独立读取的文件。
- 无 SDK 目录的 native-only、正常/失败资源关闭、协作取消和预算内终止子树。

包身份由轻量 Robolectric fork 在官方 Package/BoundApplication 初始化处建立；UID 使用官方 early lifecycle plugin。库不通过替代 Context 或默认 `org.robolectric.default` 冒充业务身份。

`package.apk` 是宿主 Application 元数据源；加载模块 APK 可不同，`createDalvikVM(apk)` 不执行 DEX。文件挂载明确区分 host 实际路径与 guest 别名，不改写 File.getPath，不按 JNI 签名伪造 File。基础映射覆盖实际 open/read/write 路径，不声称替代完整设备文件系统或所有 syscall。

API 30 的被动 maps 快照不证明 Robolectric native runtime 与 Unicorn 共存。未验证任意 APK Java/资源执行、真实设备硬件服务、跨 JVM 偏好恢复、Build/PM 自动 reset、多 SDK 或多 sandbox 同进程。业务算法、原始 native fixtures、时钟/熵策略、软件 AndroidKeyStore 和应用 JNI 留在调用项目。
