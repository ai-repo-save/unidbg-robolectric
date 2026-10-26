# Runtime 1.0.1 公共合同

坐标为 `io.github.ai-repo-save:unidbg-robolectric-{client,spi,worker}:1.0.1`，JDK 21。业务实现依赖 SPI；父进程仅依赖 client。worker distribution 提供执行环境与离线 API 30 SDK。底座为 `io.github.ai-repo-save.unidbg:*:0.10.1`；唯一 Robolectric core 为 `io.github.ai-repo-save.robolectric:robolectric:1.0.0`，复用官方支持模块 4.17。

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

## JNI 组合

包为 `io.github.aireposave.unidbg.runtime.spi.jni`。

```java
var jni = new JniConfiguration()
    .handle("java/lang/System->currentTimeMillis()J", call -> configuredMillis)
    .businessOwner("com/example/NativeCallback", businessJni)
    .proxyOwner("com/example/ActualJavaCallback")
    .observe(event -> record(event));
session.configureVm(vm, jni);
```

顺序为 exact descriptor handler、已注册业务 owner delegate、显式 proxyOwner 与标准 Android/Java Proxy、未知拒绝。`proxyOwner(String internalClassName)` 用于当前 worker 中确有 Java 实现的已知业务类；元数据与调用都使用库已有 Proxy，配置时验证类可加载。owner 使用 JNI internal name，不提供全局 catch-all 或 businessOwner 对 Android/Java owner 的覆盖。元数据 acceptMethod/acceptField 只返回是否接受，不执行结果 handler。

`JniCall` 提供 `method()`、`signature()`、`arguments()`、`proceed()` 和 `invoke(Jni)`。`proceed()` 调用标准 Proxy；`invoke` 保持原 Jni overload，便于复用业务 AbstractJni。Event 有 `phase()`（BEFORE / AFTER / FAILED）、`call()`、`result()`、`failure()`。observer 不会因调用由 Proxy 命中而被绕过。acceptMethod/acceptField 是独立元数据操作，其 result 为 boolean；需要成员值的观察器按 call.method() 选择真实 getter/call 操作。标准成员识别覆盖公开接口与父类，不维护 provider 白名单。JNI 组合使用 JDK 接口 Proxy，没有额外字节码生成依赖。

所有配置必须在解析业务类和执行 native 初始化之前完成。不要再反射修改 VM/class/method maps。

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

Android 模式提供 `package.name` 或单独提供 `package.apk`。`apk` 是所建宿主 Application 的 APK 身份来源，必须是存在的绝对文件路径；库在同 JVM 引导前使用底座已有 apk-parser 读取 name / versionName / versionCode / targetSdk 与该解析器支持的 v1 签名。显式提供同字段时必须与 APK 一致，冲突拒绝；派生后的配置保存在 effective-request.json。不执行 DEX。

`createVm(emulator, moduleApk, jni)` 的模块 APK 独立于此来源。例如 DroidGuard 下载 VM 的 APK 包不是宿主 GMS，不能将它当作 package.apk 来重定义 Application 身份。

可选 package 字段还有 `signatures`（Base64 签名字节数组）、`installer`、`flags`、`nativeLibraryDir`、`guestSourceDir`。物理 `apk` 用于宿主资源/元数据，`guestSourceDir` 只是 native IO 的显式文件挂载别名，不把宿主 APK 路径宣称为 guest 路径。包身份由轻量 core fork 的公开 applicationPackageName/applicationProcessName 配置在官方 Package、ApplicationInfo、BoundApplication 原初始化处建立。可选 package.uid 由官方 TestEnvironmentLifecyclePlugin.onSetupApplicationState 在绑定前调用 ShadowProcess.setUid；未提供时保持 Process.myUid 原默认。Application 使用官方 base，不替换 Context、另装身份包或改私有字段；元数据在 onCreate 中通过公开字段和 PM shadow 配置。

Build 支持 MODEL / MANUFACTURER / BRAND / DEVICE / PRODUCT / BOARD / HARDWARE / FINGERPRINT / SERIAL。共同的 native 属性由相同输入派生，冲突输入拒绝。Settings 支持 global / secure / system，值是字符串或 null。偏好支持字符串、boolean、int、long；更复杂服务配置在入口中使用公开 shadows，不建立通用设备桩。

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
