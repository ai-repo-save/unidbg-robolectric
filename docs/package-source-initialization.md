# 宿主 APK 路径初始化

`profile.package.apk` 是宿主 Application 的真实 APK 路径。`ApkMetadata.resolve` 验证绝对文件路径并解析包元数据。`WorkerExecution` 在 JUnit bootstrap 前将该路径传入正式 core 属性 `robolectric.applicationSourceDir`。core 以原 Package.codePath → ApplicationInfo → LoadedApk 初始化流程建立路径。`RuntimeApplication.onCreate` 继续配置其他包元数据，不再迟到写入 sourceDir/publicSourceDir。

此接入使用 `io.github.ai-repo-save.robolectric:robolectric:1.1.0`。`package.guestSourceDir` 仅提供 native 文件挂载别名，不代替 Java 返回的真实路径。

该初始化使 `Application.getPackageCodePath()`、`getPackageResourcePath()`、ApplicationInfo 和 PackageManager ApplicationInfo 的两个 source 字段使用同一宿主 APK 路径。初始化发生在 Application 附着前，不使用 getter 特例、Context 替换或反射补写 LoadedApk。

core 的显式 `StartupIdentityIntegrationTest` 在官方 API 30 生命周期内持久化 Application.onCreate 凭据，外部读取并精确比较上述六个路径值。消费者验证还应通过真实 APK profile 和真实 JNI 入口读取这六个值，并保留原始 APK 内容的独立哈希断言。
