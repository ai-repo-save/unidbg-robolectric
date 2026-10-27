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
    .handle("java/lang/System->currentTimeMillis()J", call -> JniCallPlan.value(configuredMillis))
    .businessOwner("com/example/NativeCallback", businessJni)
    .proxyOwner("com/example/ActualJavaCallback")
    .observe(event -> record(event));
session.configureVm(vm, jni);
```

顺序为 exact descriptor handler、已注册业务 owner delegate、显式 proxyOwner 与标准 Android/Java Proxy、未知拒绝。`proxyOwner(String internalClassName)` 用于当前 worker 中确有 Java 实现的已知业务类；元数据与调用都使用库已有 Proxy，配置时验证类可加载。owner 使用 JNI internal name，不提供全局 catch-all 或 businessOwner 对 Android/Java owner 的覆盖。元数据 acceptMethod/acceptField 只返回是否接受，不执行结果 handler。

`Handler.plan(JniCall)`、`JniCall.proceed()`、`invoke(Jni)` 和 `Continuation.plan(Jni)` 返回底座 `JniCallPlan`。`method()` 保留原 `Jni.class` Method 与 overload，`signature()` 和 `arguments()` 保留本次成员与解码参数。ordinary 调用使用普通 modeled callback，V/A 使用 `*V`。`proceed()` 绑定标准 Proxy；`invoke(provider)` 绑定指定 provider，保留 HostJni 能力，modeled provider 在 backend 执行。VM 与 class provider 的组合均暴露 HostJni。

所有 plan 节点属于同一次 JNI 成员、operation、参数与返回类型。`backend(provider)` 执行本次 modeled callback；`host(Callable<?>)` 只持有宿主值；`value(normalized)` 接收 DvmObject、primitive wrapper 或 void null；`failure(original, targetThrown)` 显式传递失败。`then(success)` 变换结果，`then(success, failure)` 显式恢复或继续失败。continuation 在 backend 执行，返回同一个底座 DTO；runtime 不执行计划，不阻塞等待宿主线程。HOST 的结果由底座先规范化，VALUE/BACKEND 结果已经规范化。调用者不能将 raw host object 或 native handle 传给 `value`。

Event 提供 `phase()`（BEFORE / AFTER / FAILED）、`call()`、`result()`、`failure()` 和 `targetThrown()`。BEFORE 在 backend 绑定时执行，失败直接返回 `failure(original, false)`，不执行 operation、AFTER 或 FAILED。Handler 绑定失败通知 FAILED 后返回同一原始失败。正常完成才通知 AFTER；其 result 为原 JNI 类型。目标 Java 异常的 FAILED 保留原 Throwable 与 `targetThrown=true`，底座按最终未恢复结果安装 JNI pending exception。BEFORE、AFTER 与 continuation 异常属于 backend 失败。FAILED observer 异常附入原失败的 suppressed，不恢复失败。`observe` 只通知；显式恢复使用 `then` 的 failure 分支。Handler 内恢复成功后，组合观察器看到最终 AFTER。

acceptMethod/acceptField 是独立同步元数据操作，其 result 为 boolean；结果 handler 不处理元数据查询。toReflectedMethod 按 owner / Proxy 路由同步元数据，不执行结果 handler。需要成员值的观察器按 `call.method()` 选择 getter/call 操作。ThrowNew 的 `planException` 直接转发标准 HostJni，不伪造原 Jni Method，也不扩大配置与观察器覆盖范围。标准成员识别覆盖公开接口与父类。宿主 getter 使用 backend 初始化时捕获的 profile、路径和 classloader；宿主线程不绑定 WorkerSession。

所有配置必须在解析业务类和执行 native 初始化之前完成。不要再反射修改 VM/class/method maps。

## 未发布底座开发消费

当前源码声明版本为 1.2.2；开发装配不是新发布的 1.2.2。默认发行依赖和版本保持原声明。新 SPI 需要含 JniCallPlan 的底座 build identity，在正式坐标升级前使用显式开发入口：

```sh
./gradlew -PdevelopmentBuildIdentity=/absolute/build-identity.json \
  -ProbolectricRepository=file:///absolute/robolectric-repository \
  -PapkParserRepository=file:///absolute/apk-parser-repository \
  developmentInstall
./gradlew -PdevelopmentBuildIdentity=/absolute/build-identity.json \
  -ProbolectricRepository=file:///absolute/robolectric-repository \
  -PapkParserRepository=file:///absolute/apk-parser-repository \
  developmentJniIntegration
```

输入必须为 schema 1、kind=build、published=false。开发模式逐文件核对三核心 classes 的 identity，按精确 module 排除旧 unidbg-api / unidbg-android / unidbg-unicorn2 依赖，再接入原 classes 与正式 Maven POM 声明的依赖。producer 附加的 consumer_classpath_file 按原路径和 SHA-256 分离；runtime 由自己的 Gradle 图提供 Jackson、picocli 与 Logback。底座依赖使用 Gradle 正常解析与冲突决议。runtime 保留 net.dongliu:apk-parser:2.6.10 的明确排除，唯一 parser 为自身已声明的 fork 2.6.11；不按文件名猜测坐标。

开发输出位于 `build/development/<identity-sha256前16位>/`。`developmentInstall` 使用原 WorkerMain 与 client 协议；worker JAR 的 Class-Path 保留有序依赖与未经重新编译的核心目录。安装 manifest 记录 development=true、published=false、sourceDeclaredVersion、源码提交与脏状态、源码文件摘要、完整底座 identity、输入摘要、producer consumer 排除、parser 差异、declared/resolved 依赖、安装二进制与有序 classpath。`spiClasspath` 提供消费者所需的新 SPI JAR、三核心目录与 SPI 依赖的已安装路径，逐项记录 kind、文件摘要与核心 component；消费者复用该清单，不再独立解析底座 identity。开发 classpath 不声明与 collector 的完整 classpath 逐项相等。装配验证 parser 类仅有 fork 来源、SLF4J provider 仅为 Logback。开发模式拒绝 publish、releaseLocal 与普通发行安装任务。

`developmentJniIntegration` 仅显式执行现有单项 lifecycle/JNI 集成，不加入默认测试集。它使用真实子进程，覆盖 worker 隔离、持久通道、取消与资源关闭，同时验证 ordinary/A 的 modeled overload、HostJni owner、Host→Host continuation、失败恢复、规范化观察值与无 WorkerSession 的宿主 getter。

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
  --profile /absolute/profile.json --output-dir /absolute/new-session
```

`--mode` 为 ANDROID 或 NATIVE_ONLY。会话启动检查 API 版本及所选 SDK 的必需文件，安装 manifest 记录版本、源码提交、依赖坐标和配置路径等构建事实。直接 CLI 使用调用进程的真实 cwd；在启动前由 shell、Python 或 ProcessBuilder 设置，不通过修改 user.dir 冒充 chdir。`--request` 接收 client 生成的协议 JSON。`--help`、`--version` 无任务副作用。

`DirectMainEntrypoint` 的 arguments 为 `{"mainClass":"com.example.Main","arguments":["arg"]}`。它在同一执行域调用原 main，stdout 为原有机器数据，库日志保持 stderr；原 main 必须 return，不能调用 System.exit。

JUnit5 适配由消费者实现 WorkerEntrypoint，并在 run 内调用官方 JUnit Platform Launcher、执行原 selectors/参数扩展并写真实 XML。不要把 native 对象返回父 Gradle Test，或把业务工作交给库内部 JUnit4 bootstrap 作正确性断言。

持久入口在 run 中处理完整 stdin/stdout 协议，直到 close / EOF 才返回；无默认空闲超时。每次 send/receive 传 JSON，原始诊断不进入机器通道。client 在发送取消信号前捕获已启动子树；cancellationGrace 内根进程退出后仍保留子进程句柄，继续回收存活后代。取消预算先等待协作关闭，再分别给 TERM 和必要的 KILL 一个 terminationWait。completion 在取消清理完成后才报告终态。SLF4J 事件关联 trace/session、PID、阶段、预算和等待结果；cancellation.json 保存完整摘要，有进程信号时 aborted.json 保存同一摘要。finallyConfirmed=false，不将 TERM/KILL 等同于已运行 JVM finally。MCP 服务也必须在此作用域内创建和关闭。
