# Runtime 1.2.2 公共合同

坐标为 `io.github.ai-repo-save:unidbg-robolectric-{client,spi,worker}:1.2.2`，JDK 21。业务实现依赖 SPI；父进程仅依赖 client。worker distribution 提供执行环境与离线 API 30 SDK。底座为 `io.github.ai-repo-save.unidbg:*:0.13.1`；唯一 Robolectric core 为 `io.github.ai-repo-save.robolectric:robolectric:1.2.0`，正式传递依赖完整 `io.github.ai-repo-save.robolectric:shadows-framework:1.2.0`，替代官方同名 framework；其他官方支持模块保持 4.17。

## 工作入口

Java 包为 `io.github.aireposave.unidbg.runtime.spi`。

```java
public interface WorkerEntrypoint {
    JsonNode run(WorkerSession session, JsonNode arguments) throws Exception;
}
```

入口必须有 public 无参构造器。库在工作执行域按 FQCN 重新加载入口，所有 emulator、VM、Module、Debugger、DvmObject、回调、observer 和 handler 均在此处创建使用。

`WorkerSession.current()` 返回当前执行线程会话。`application()` 返回配置后的真实 Android Application；`androidContext()` 返回同一上下文。native-only 模式调用 Android 访问器会失败。

`profile()`、`outputDirectory()`、`input()`、`output()`、`checkCancelled()` 提供输入、产物、持久机器通道和协作取消。

`configureEmulator(existing)`、`configureVm(vm, jni)` 仅配置，不取得所有权。`createEmulator(factory)` 自动登记并配置新 emulator，`createVm(emulator, apk, jni)` 创建 VM 并接入类工厂。`own(resource)` 登记反序关闭；同一对象重复登记只关闭一次。消费者自行创建 emulator 时，load 失败由 loader 关闭，成功后可登记幂等 LoadResult；不要同时登记 LoadResult 和其 emulator。入口返回之前可提前关闭幂等资源。

## Native 初始化返回值

工作入口通过底座正式 API 获取 `JNI_OnLoad` 的真实返回值：

```java
int jniVersion = dalvikModule.callJNI_OnLoadWithResult(emulator);
```

该方法执行 VM 版本校验，并在 `finally` 中清理局部引用；native 调用或版本校验失败时传播异常。模块未导出 `JNI_OnLoad` 时抛 `IllegalStateException`，不会将缺符号映射为 `0`。可选初始化继续使用原 `void callJNI_OnLoad`，其缺符号合同为 no-op。runtime 不复制这套清理和校验，也不改变消费者的 ELF 装载顺序或初始化策略。

## Proxy 宿主值

JNI 回调通过 `ProxyDvmObject.unpack(DvmObject<?>)` 取得宿主值，复用底座正式转换。proxy 对象数组保留原 backing array、组件类型和引用身份；proxy Class 依赖既有 ProxyClassFactory 配置。null 输入返回 null，无宿主值的 wrapper 抛 UnsupportedOperationException。该操作不新增或清理 VM 引用，也不转移所有权。

## JNI 组合

包为 `io.github.aireposave.unidbg.runtime.spi.jni`。

```java
var jni = new JniConfiguration()
    .handle("java/lang/System->currentTimeMillis()J", call -> configuredMillis)
    .businessOwner("com/example/NativeCallback", businessJni)
    .observe(event -> record(event));
session.configureVm(vm, jni);
```

路由顺序为精确描述符 handler、已注册 owner delegate、默认 Proxy。owner 使用 JNI internal name。

`Handler.invoke(JniCall)` 同步返回 JNI 值：对象为 `DvmObject`，基本类型为对应包装类型，void 为 null。handler 按条件以 `return call.proceed()` 尾委派默认 Proxy，或以 `return call.invoke(provider)` 尾委派指定 provider。`method()` 保留原 `Jni.class` Method 与重载，`signature()` 和 `arguments()` 保留本次成员与解码参数。ordinary 调用使用普通 modeled callback，V/A 使用 `*V`。

modeled `Jni` 在 backend 执行。`HostJni` 在 backend 解析成员并提取宿主参数，通过既有 `GuestJavaRuntime.submit` 和单次 `GuestJavaWaiter.suspend` 执行宿主操作。completion 回到 backend 后转换宿主结果并写入 JNI 返回值。对象转换保留宿主对象身份。宿主 getter 使用 backend 初始化时捕获的 profile、路径和 classloader；宿主线程不绑定 `WorkerSession`。

Event 提供 `phase()`（BEFORE / AFTER / FAILED）、`call()`、`result()`、`failure()` 和 `targetThrown()`。事件均在 backend 执行。BEFORE 可调用 `WorkerSession.checkCancelled()`；抛出异常时通知 FAILED 并终止该次操作。正常完成通知 AFTER，result 为 JNI 值。目标 Java 异常通知 FAILED，保留原 Throwable 和 `targetThrown=true`，底座将原异常设为 JNI pending exception。同步 handler 可抛出 `ProxyTargetException(original)` 表达同一结果。BEFORE、handler、宿主桥和 AFTER 的其他异常按原失败传播。FAILED observer 抛出的异常附入原失败的 suppressed。等待取消时通知 FAILED。

acceptMethod/acceptField 是同步元数据操作，result 为 boolean；精确 handler 或 owner 注册可接受对应成员，其余查询默认 Proxy。toReflectedMethod 按 owner / Proxy 同步路由。结果 handler 处理成员调用与字段访问，观察器通过 `call.method()` 区分元数据操作。ThrowNew 的 `invokeException` 直接转发标准 HostJni。

在解析业务类和执行 native 初始化之前配置 JNI。

## 未发布底座开发消费

开发构建从底座构建输出读取核心 classes 路径和依赖路径，使用 Gradle Jar 打包核心 classes，并通过 Gradle 解析 Maven 依赖。runtime 自身提供 Jackson、picocli、Logback 和 fork APK parser。

```sh
./gradlew -PdevelopmentCoreClasspath=/absolute/core/classpath.txt \
  -PdevelopmentConsumerClasspath=/absolute/consumer-classpath.txt \
  -ProbolectricRepository=file:///absolute/robolectric-repository \
  -PapkParserRepository=file:///absolute/apk-parser-repository \
  developmentInstall
./gradlew -PdevelopmentCoreClasspath=/absolute/core/classpath.txt \
  -PdevelopmentConsumerClasspath=/absolute/consumer-classpath.txt \
  -ProbolectricRepository=file:///absolute/robolectric-repository \
  -PapkParserRepository=file:///absolute/apk-parser-repository \
  developmentJniIntegration
```

`developmentCoreClasspath` 指向底座构建输出的 classpath 文件，内容为平台路径分隔符连接的目录和 JAR 路径。目录作为核心 classes 打包，依赖 JAR 使用相邻 Maven POM 中的坐标。底座 classpath 包含消费者依赖时，`developmentConsumerClasspath` 指定该消费者的 classpath 文件，由 runtime 自身的依赖声明替代这些依赖。Gradle 排除已发布的同名核心模块，使用选定的核心 classes。

各模块开发输出位于自身的 `build/development/`。`developmentInstall` 调用 `:worker:installDist`，安装目录为 `worker/build/development/install/unidbg-worker/`。消费者通过 `-PruntimeDevelopmentDirectory=/absolute/unidbg-worker` 使用安装目录中的 `lib/*.jar` 编译。

`developmentJniIntegration` 仅显式执行现有单项 lifecycle/JNI 集成，不加入默认测试集。它使用真实子进程，覆盖 worker 隔离、持久通道、取消与资源关闭，同时验证 ordinary/A 的 modeled overload、HostJni owner、原目标异常、JNI 观察值与无 WorkerSession 的宿主 getter。

## Profile

基础 JSON 示例：

```json
{
  "androidSdk": 30,
  "processName": "com.example.application:analysis",
  "package": {
    "name": "com.example.application",
    "versionName": "1.0",
    "versionCode": 1,
    "targetSdk": 30,
    "uid": 10000
  },
  "build": {"MODEL": "Configured model", "MANUFACTURER": "Configured manufacturer"},
  "settings": {"global": {"adb_enabled": "0"}, "secure": {"android_id": "configured-id"}},
  "preferences": {"preferences-name": {"key": "value"}},
  "nativeProperties": {"ro.debuggable": "0"},
  "filesystem": {"guestDataDirectory": "/data/user/0/com.example.application"}
}
```

Android 模式提供 `package.name` 或单独提供 `package.apk`。`apk` 是所建宿主 Application 的 APK 身份来源，必须是存在的绝对文件路径；库在同 JVM 引导前通过 `io.github.ai-repo-save.apk-parser:apk-parser:2.6.11` 读取 name / versionName / versionCode / targetSdk，通过官方 apksig 9.4.1 正式 API 按 API 30 验证 v1/v2/v3 签名并派生 GET_SIGNATURES 身份。验证失败时拒绝启动；存在签名轮换历史时只返回最旧证书，其他情况每个 signer 对应单张证书 DER，不展开证书链。显式提供同字段时必须与 APK 一致，冲突拒绝；派生后的配置保存在 effective-request.json。不执行 DEX。

`createVm(emulator, moduleApk, jni)` 的模块 APK 独立于此来源。例如 DroidGuard 下载 VM 的 APK 包不是宿主 GMS，不能将它当作 package.apk 来重定义 Application 身份。

可选 package 字段还有 `signatures`（Base64 证书 DER 数组）、`installer`、`flags`、`nativeLibraryDir`、`guestSourceDir` 和成对采集的 `requestedPermissions` / `requestedPermissionsFlags`。权限两数组按索引平行，必须等长或同时为 null；库保留采集的 flags，不从 APK 声明推导授权状态。`packages` 数组提供附加已安装包，复用相同元数据投影，不改变宿主身份；重复包名或与宿主重名时拒绝启动。完整字段语义见 `docs/package-profile.md`。物理 `apk` 用于宿主资源/元数据，`guestSourceDir` 是 native IO 的显式文件挂载别名。包身份和 APK 路径由 core 的公开 applicationPackageName/applicationProcessName/applicationSourceDir 配置在 Package、ApplicationInfo、BoundApplication 和 LoadedApk 初始化处建立。可选 package.uid 由官方 TestEnvironmentLifecyclePlugin.onSetupApplicationState 在绑定前调用 ShadowProcess.setUid；未提供时保持 Process.myUid 原默认。Application 使用官方 base；其他包元数据在 onCreate 中通过公开字段和 PM shadow 配置。

Build 支持 MODEL / MANUFACTURER / BRAND / DEVICE / PRODUCT / BOARD / HARDWARE / FINGERPRINT / SERIAL。SERIAL 通过公开 ShadowBuild.setSerial 配置 Build.getSerial()，同时派生 ro.serialno；API 30 的弃用静态字段 Build.SERIAL 按 Android 合同保持 Build.UNKNOWN，不作为真实序列号的读取入口。共同的 native 属性由相同输入派生，冲突输入拒绝。Settings 支持 global / secure / system，值是字符串或 null。偏好支持字符串、boolean、int、long。可选 `services` 对象通过 `systemFeatures`、`display`、`sensors` 配置功能支持、默认显示和默认传感器集合，字段语义见 `docs/device-services-profile.md`。其他服务由入口通过公开 shadows 配置。

库挂载 guestDataDirectory 到公开 Application.getDataDir()，供 native IO 与宿主 Android API 共享真实文件；同时保留宿主 API 返回的实际数据路径别名，逐条记录 guest、host 和来源。File.getPath 不被改写，不按 JNI 签名伪造 File。native resolver API、rootfs、自定义 syscall 和 ELF 策略仍由 worker-local consumer factory 决定，不由 androidSdk 推导。

## CLI 与既有入口

```sh
bin/unidbg-worker --mode ANDROID --entrypoint com.example.Task \
  --task-classpath /absolute/task.jar --arguments /absolute/arguments.json \
  --profile /absolute/profile.json --output-dir /absolute/session
```

`--mode` 为 ANDROID 或 NATIVE_ONLY。Android 模式使用离线 API 30 SDK，安装 manifest 记录版本、源码提交、依赖坐标和配置路径等构建事实。直接 CLI 使用调用进程的真实 cwd；在启动前由 shell、Python 或 ProcessBuilder 设置，不通过修改 user.dir 冒充 chdir。`--request` 接收 client 生成的协议 JSON。`--help`、`--version` 无任务副作用。

`DirectMainEntrypoint` 的 arguments 为 `{"mainClass":"com.example.Main","arguments":["arg"]}`。它在同一执行域调用原 main，stdout 为原有机器数据，库日志保持 stderr；原 main 必须 return，不能调用 System.exit。

JUnit5 适配由消费者实现 WorkerEntrypoint，并在 run 内调用官方 JUnit Platform Launcher、执行原 selectors/参数扩展并写真实 XML。不要把 native 对象返回父 Gradle Test，或把业务工作交给库内部 JUnit4 bootstrap 作正确性断言。

持久入口在 run 中处理完整 stdin/stdout 协议，直到 close / EOF 才返回；无默认空闲超时。每次 send/receive 传 JSON，原始诊断不进入机器通道。client 在发送取消信号前捕获已启动子树；cancellationGrace 内根进程退出后仍保留子进程句柄，继续回收存活后代。取消预算先等待协作关闭，再分别给 TERM 和必要的 KILL 一个 terminationWait。completion 在取消清理完成后才报告终态。SLF4J 事件关联 trace/session、PID、阶段、预算和等待结果；cancellation.json 保存完整摘要，有进程信号时 aborted.json 保存同一摘要。finallyConfirmed=false，不将 TERM/KILL 等同于已运行 JVM finally。MCP 服务也必须在此作用域内创建和关闭。
