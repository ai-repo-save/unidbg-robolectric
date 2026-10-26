# 宿主 APK 路径初始化

`profile.package.apk` 是宿主 Application 的真实 APK 路径。`ApkMetadata.resolve` 验证绝对文件路径并解析包元数据。`WorkerExecution` 在 JUnit bootstrap 前将该路径传入正式 core 属性 `robolectric.applicationSourceDir`。core 以原 Package.codePath → ApplicationInfo → LoadedApk 初始化流程建立路径。`RuntimeApplication.onCreate` 继续配置其他包元数据，不再迟到写入 sourceDir/publicSourceDir。

此接入要求 `io.github.ai-repo-save.robolectric:robolectric:1.1.0`。运行库发行必须同步使用该依赖；core `1.0.0` 不识别此属性。`package.guestSourceDir` 继续仅提供 native 文件挂载别名，不代替 Java 返回的真实路径。

该初始化使 `Application.getPackageCodePath()`、`getPackageResourcePath()`、ApplicationInfo 和 PackageManager ApplicationInfo 的两个 source 字段使用同一宿主 APK 路径。初始化发生在 Application 附着前，不使用 getter 特例、Context 替换或反射补写 LoadedApk。

core 的显式 `StartupIdentityIntegrationTest` 在官方 API 30 生命周期内持久化 Application.onCreate 凭据，外部读取并精确比较上述六个路径值。消费者验证还应通过真实 APK profile 和真实 JNI 入口读取这六个值，并保留原始 APK 内容的独立哈希断言。

## 序列号验证

[AOSP API 30 Build 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r17/core/java/android/os/Build.java#116) 规定静态 `Build.SERIAL` 为 `Build.UNKNOWN`，其初始值读取 `no.such.thing`，而 `Build.getSerial()` 调用受权限限制的设备标识服务。两者不是同一读取合同。

[Robolectric 4.17 ShadowBuild 源码](https://github.com/robolectric/robolectric/blob/0706415db828e1bdcf4f0c820784fa760d0185a2/shadows/framework/src/main/java/org/robolectric/shadows/ShadowBuild.java#116) 的 `setSerial` 只设置 `getSerial()` 返回值，不改静态字段。运行库沿用该公开 API。

真实序列号 profile 的验证应分别精确断言 `Build.getSerial()` 和 native `ro.serialno` 等于采集值；若还采集静态字段，则独立断言 `Build.SERIAL == Build.UNKNOWN`。设备采集时保留调用身份和 getSerial 的成功或权限失败结果，不从 native property 推断普通应用具有读取标识的权限。
