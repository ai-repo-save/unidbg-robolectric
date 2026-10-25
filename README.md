# unidbg / Robolectric JNI 集成验证

此独立仓库验证 ARM64 JNI 调用能否操作 Robolectric Android 对象。不迁移业务代码，不提供 Android 桩实现。

## 环境与依赖

- Linux x86-64、JDK 21、Gradle 9.5.0 官方 wrapper。
- Robolectric 4.17、Android 11 / API 30。编译和宿主类路径使用 Central 的 `android-all:11-robolectric-6757853`；运行时由 Robolectric 解析 `android-all-instrumented:11-robolectric-6757853-i7`。
- 本地 Maven 中的 `com.github.zhkl0228:unidbg-android:0.9.10-SNAPSHOT` 和 `unidbg-unicorn2:0.9.10-SNAPSHOT`。本次来源为 `~/git/unidbg` 的 `1291511`，需先在该仓安装产物。
- `com.github.zhkl0228` 依赖只从本地 Maven 获取；Robolectric、SDK、JUnit、日志库和其他普通依赖从 Maven Central 获取；Robolectric 的 AndroidX 测试依赖从 Google Maven 获取。
- Java 插件通过 Gradle artifact transform 提取 AndroidX AAR 的 `classes.jar`，不加载其 Android 资源。本验证没有应用资源或 APK。
- Robolectric 的传递运行时依赖包含 `nativeruntime-dist-compat:1.0.19`（159144356 字节）。下载该 JAR 与加载其原生库是两个不同阶段；是否加载以本次进程映射为准。
- 依赖缓存使用默认 `~/.gradle`；Robolectric SDK 使用其默认本地缓存。缓存和构建证据不入 Git。

Gradle 9.5.0 发行 ZIP（`gradle-9.5.0-bin.zip`）的 SHA256 固定为 `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746`，校验对象不是 `gradle-wrapper.jar`。

## 显式运行

先用 JDK 21 将上述 unidbg 源码的全模块产物安装到本地 Maven，保留 Javadoc 构建，跳过业务测试和 GPG 签名：

```sh
(
  cd "$HOME/git/unidbg"
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -DskipTests -Dgpg.skip=true install
)
```

再确保本机 HTTP 代理监听 `127.0.0.1:7891`。在本仓目录执行：

```sh
./gradlew \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7891 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7891 \
  jniIntegration --console=plain --stacktrace
```

四个 JVM 参数配置 Gradle 的依赖下载。`jniIntegration` 另向测试 JVM 传入 `robolectric.dependency.proxy.host/port`，配置 Robolectric SDK resolver。代理只在本机使用，不转发到其他机器。

集成验证属于独立 `integration` source set，仅 `jniIntegration` 执行。默认 `test` 和 `check` 不执行它。每次显式运行都会重新执行测试。

## 执行路径

`RobolectricJniIntegrationTest` 是唯一整体集成入口。它通过公开 JUnit4 `JUnitCore` 顺序运行同一 `AndroidSession` 两次。每次会话由官方 `RobolectricTestRunner` 完成 Android 环境初始化和清理，使用公开 `RuntimeEnvironment.getApplication()` 获取应用。

`Arm64Jni` 为每次会话创建 ARM64 emulator，选择 `Unicorn2Factory`，把应用类加载器传给公开 `ProxyClassFactory(ClassLoader)`，通过 `ProxyDvmObject` 包装对象。JNI 函数地址来自 `JNIEnv` 表，`Module.emulateFunction` 调用 emulator 的 `eFunc`，在 Unicorn2 中执行 unidbg 提供的 ARM64 SVC trampoline。参数使用 ARM64 `jvalue` 布局；没有自建 NDK 二进制、APK、JNI 签名分支或预设 Android 返回值。

每次会话执行完整流程：

1. JNI 读取相同键的初始 Global、Secure 和偏好值，验证前次会话状态已清理。
2. JNI 写入并读取 `Settings.Global/Secure`，宿主 Android API 独立读取精确值。
3. 公开 `ShadowBuild.setModel` 配置 MODEL，JNI 读取静态字段。
4. 公开 `ShadowPackageManager.installPackage` 安装 `PackageInfo`，JNI 经 Context、PackageManager、`getPackageInfo` 和字段读取取得包名及版本。
5. JNI 执行 `SharedPreferences.edit/putString/commit`，通过独立创建的 package context 读取完整键值映射。
6. 从公开 `Application.getDataDir()` 枚举并复制真实文件，记录路径、大小和 SHA-256。此步骤不访问 SharedPreferences 私有字段，也不推测其文件格式。
7. 保存完整 `/proc/self/maps` 快照，记录当前 Linux x86-64 ABI 的库文件名、公开 `getResource` URL、映射匹配结果和 Android / unidbg 类加载器归属，关闭临时内存和 emulator。

两次会话使用相同设置键、偏好文件名和包名，分别写入 `first`、`second` 值。初始状态断言和第二次精确值断言验证 `Settings.Global/Secure` 与 `SharedPreferences` 的会话隔离；不同进程不是隔离手段。`Build.MODEL` 和 PackageManager 仅验证逐会话配置后经 JNI 读回，未独立验证它们的自动 reset。

## 运行证据

每次任务将 JUnit XML、HTML、应用数据副本和每个会话的 `proc-self-maps.txt` 完整快照保存到独立 `build/evidence/<UTC 时间>/`。Logback 输出 JSON，包含时间、级别、事件、运行标识和会话标识，由 Gradle 写入 XML 测试输出。外层 JUnit 会话边界统一记录失败堆栈，包括 runner 引导失败；资源通过 `try` / `finally` 和 `AutoCloseable` 清理。

证据默认保留至显式 `./gradlew clean`。需要长期保存时，应在清理前归档整个运行目录。Gradle 的普通控制台输出不会自动写文件，可由调用方重定向保存。

## 实测结果

JDK 21.0.12.1、Gradle 9.5.0 下，`jniIntegration` 通过：1 项整体测试，0 失败、0 跳过；同一测试 JVM 内的两次 Robolectric 会话均完成全部精确断言。

- Global / Secure 分别读回 `global-first` / `secure-first` 和 `global-second` / `secure-second`。
- MODEL 分别为 `JNI-model-first`、`JNI-model-second`；PackageInfo 版本分别为 `release-first`、`release-second`。
- 偏好完整映射分别为 `{unidbg_probe=preference-first}`、`{unidbg_probe=preference-second}`。独立读取保存的文件确认各自包含对应值，文件大小分别为 128、129 字节。
- 两次会话初始 Settings 值均为 null，初始偏好值均为 `unset`，应用数据目录不同。
- Android、DalvikVM64、Unicorn2Backend 和 Unicorn2 JNI 类由同一个 `SdkSandboxClassLoader` 加载，两次会话复用该加载器。
- 实际后端为 `Unicorn2Backend`，两次会话快照均匹配同一份 native-lib-loader 解压的 `libunicorn.so`。API 30 对应资源为 `native/linux/x86_64/librobolectric-nativeruntime.so`，公开资源查询能定位该 JAR 条目，快照中匹配该库文件名的映射为空。
- 两次会话均记录 `emulator_closed` 和 `session_closed`，JUnit XML 持久化全部 JSON 日志。

## 验证边界

验证对象是跨 JNI 的真实 Android 对象操作、宿主可观察结果、文件副作用和 Settings / SharedPreferences 的连续会话隔离。Robolectric 提供宿主 JVM 上的 Android 框架行为；此流程不是设备上的 ART、Binder 服务、系统 Settings 数据库或完整 Android 系统。

native 证据只记录四类 API 操作后的被动快照，不主动加载 Robolectric 原生库，也不证明 Unicorn2 与 Robolectric 原生库共存。API 30 使用官方 loader 的 `robolectric-nativeruntime` 库名，匹配依据是当前发行包中的实际文件名。

Unicorn2 执行的是 JNI 表函数 trampoline，不是业务 `.so`。未验证 APK 加载、第三方 ARM64 库、Android 原生图形、真实设备、其他 SDK、其他 sandbox、Android 与 unidbg 分属不同类加载器、其他 CPU 架构、并发会话或长驻服务。文件副本用于独立检查落盘证据，不据此声称跨 JVM 重启后的 SharedPreferences 恢复能力。本次未注入运行中故障；失败时的资源清理由 `try` / `finally` 和 `AutoCloseable` 保证。

官方依据：

- https://robolectric.org/getting-started/
- https://robolectric.org/architecture/
- https://robolectric.org/configuring/
- https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html
