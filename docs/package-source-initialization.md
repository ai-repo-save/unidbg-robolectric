# 宿主 APK 来源与路径身份

`profile.package.apk` 是宿主 Application 的物理 APK。`ApkMetadata.resolve` 验证绝对文件路径，解析元数据并验证签名。`package.guestSourceDir` 是可选的 Android 可见来源路径。

`ProcessProfilePlugin` 在 sandbox 的早期生命周期调用 `RuntimeEnvironment.setApplicationPackageSource(new ApplicationPackageSource(physicalApk, guestSourceDir))`。core 在 Application 附着前，将可见路径写入 Package.codePath，并沿原 ApplicationInfo、LoadedApk 初始化流程建立身份。`RuntimeApplication.onCreate` 继续配置其他包元数据。

`Application.getPackageCodePath()`、`getPackageResourcePath()`、ApplicationInfo 和 PackageManager ApplicationInfo 的两个 source 字段使用同一可见路径。API 30 的 Java binary resource shadow 在实际打开 ZIP 时解析到物理 APK；`ApkAssets.getAssetPath()` 保留可见身份。资源缓存按物理来源区分。runtime 继续使用已有 GuestFiles 将同一 `guestSourceDir` 映射到物理 APK，供 native open/read 使用。

未提供 `guestSourceDir` 时，框架保留物理来源路径的既有行为；未提供 APK 时，保持未配置来源的框架行为。库不推导 `/data/app` 身份，也不增加必须提供 guestSourceDir 的限制。附加包不创建文件挂载。

此接入依赖正式制品 `io.github.ai-repo-save.robolectric:{robolectric,shadows-framework}:1.3.0`。它不替换 Context、不补写 LoadedApk，也不为宿主目录增加业务路由。当前验收范围为 API 30 的 Java binary resources，不声明其他 SDK 或 native resource mode 的支持。

## 定向验证

core 的显式 `StartupIdentityIntegrationTest` 在 Application.onCreate 持久化六个来源路径与 ApkAssets 路径，外部读取并精确比较；资源字节与独立 ZIP 读取结果比较。

runtime 的 `apkStartupIntegration` 使用调用方提供的真实 APK。在同一 Android worker 中，它读取六个 Java 路径和六个 ARM64 JNI 路径，通过 AssetManager 读取 asset 与资源条目，再通过 native libc 打开 JNI 返回的 guest 路径并读取完整 APK。外部进程读取持久化凭据，比较路径、ZIP 条目字节及完整 APK 的 SHA-256。此目标不加载应用 SO，也不访问设备。

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew \
  --offline --max-workers=2 \
  -PdevelopmentCoreClasspath=/absolute/unidbg/target/file-observer-core-classpath.txt \
  -PapkParserRepository=/absolute/apk-parser-repository \
  -ProbolectricRepository=/absolute/robolectric-repository \
  -PstartupApk=/absolute/original.apk \
  apkStartupIntegration --tests org.example.unidbg.ApkStartupIntegrationTest
```

`developmentCoreClasspath` 选择已构建的底座 classes；Robolectric 仍通过正式仓库解析完整 1.3.0 制品。验收输出位于 `build/development/evidence/<run>/apk-startup`；未使用开发底座时，位于 `build/<version>/evidence/<run>/apk-startup`。
