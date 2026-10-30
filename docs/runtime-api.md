# Runtime API

Java 包为 `io.github.aireposave.unidbg.runtime.{client,spi}`。

## 工作入口

```java
public interface WorkerEntrypoint {
    JsonNode run(WorkerSession session, JsonNode arguments) throws Exception;
}
```

入口必须提供 public 无参构造器。库按 FQCN 在工作执行域的 classloader 中加载入口，emulator、VM、Module、Debugger、DvmObject、回调、observer 和 handler 都在该域内创建与使用。

## 会话对象

`WorkerSession` 是抽象类，实现 `AutoCloseable`。`WorkerSession.current()` 返回当前执行线程绑定的会话；无绑定时抛 `IllegalStateException`。

| 方法 | 语义 |
| --- | --- |
| `id()` | 会话标识，取自请求的 `sessionId` |
| `profile()` | 生效的 profile JSON |
| `outputDirectory()` | 会话输出目录 |
| `androidEnabled()` | Android 模式为 true |
| `application()` | 配置后的真实 Android `Application`；native-only 模式调用时抛 `IllegalStateException` |
| `androidContext()` | 返回 `application()` |
| `input()` | 进程标准输入流 |
| `output()` | 持久机器通道，同时写入进程 stdout 与 `protocol.jsonl` |
| `own(T resource)` | 登记资源并返回同一对象；同一对象重复登记只关闭一次 |
| `checkCancelled()` | 收到取消信号时抛 `InterruptedException` |
| `createEmulator(factory)` | 创建 emulator、登记并完成配置 |
| `configureEmulator(emulator)` | 只配置，不取得所有权；同一 emulator 重复调用只配置一次 |
| `createVm(emulator, apk, jni)` | 创建 VM 并接入类工厂与 JNI 组合 |
| `configureVm(vm, jni)` | 只配置既有 VM |
| `close()` | 逆序关闭已登记资源 |

资源在 `close()` 中逆序关闭，首个异常继续抛出，其余异常附入 suppressed。业务入口也可以提前关闭幂等资源。消费者自行创建 emulator 时，load 失败由自己的 loader 关闭，成功后再登记该 emulator。

## 标准输入

`input()` 是通过公开 `FileDescriptor.in` 建立的进程标准输入流。`configureEmulator` 把既有 `Stdin` 绑定到该流，并通过 `IOResolver` 为之后打开的 `IO.STDIN` 建立相同绑定。

绑定后的 native `read` 读取该流；`fstat` 通过底座的 `LinuxFileMetadata.query(IO.FD_STDIN)` 查询同一宿主描述符，使用实际 `st_mode`。其他已有标准描述符保持各自来源。输入流归进程所有，关闭 guest 描述符或 session 都保持宿主标准输入可用。`System.setIn` 与 MCP 的合并输入流与此来源相互独立。

相对路径配合非目录 `dirfd` 时，底座按 `access(2)` 返回 `ENOTDIR`。底座未显式绑定的通用 `Stdin` 继续动态读取 `System.in`，metadata 未知；需要该事实的查询明确失败。

## Native 初始化

`JNI_OnLoad` 的真实返回值通过底座正式 API 获取：

```java
int jniVersion = dalvikModule.callJNI_OnLoadWithResult(emulator);
```

该方法校验 VM 版本，在 `finally` 中清理局部引用，并传播 native 调用或版本校验的异常。模块未导出 `JNI_OnLoad` 时抛 `IllegalStateException`。可选初始化使用 `void callJNI_OnLoad`，该调用缺符号时为 no-op。库不复制这套清理与校验，也不改变消费者的 ELF 装载顺序和初始化时机。

## JNI 组合

```java
var jni = new JniConfiguration()
    .handle("java/lang/System->currentTimeMillis()J", call -> configuredMillis)
    .businessOwner("com/example/NativeCallback", businessJni)
    .observe(event -> record(event));
session.configureVm(vm, jni);
```

`handle` 按成员描述符注册精确 handler，`businessOwner` 按 JNI internal name 注册类级 delegate，`observe` 注册观察器。同一描述符或 owner 重复注册时抛 `IllegalArgumentException`。

调用路由顺序为精确 handler、已注册 owner delegate、默认 Proxy。精确 handler 或 owner 注册同时使对应 `acceptMethod`、`acceptField` 查询返回 true。

`Handler.invoke(JniCall)` 同步返回 JNI 值：对象为 `DvmObject`，基本类型为对应包装类型，void 为 null。handler 以 `return call.proceed()` 尾委派默认 Proxy，或以 `return call.invoke(provider)` 尾委派指定 provider。同步 handler 可以用 `ProxyTargetException(original)` 表达目标 Java 异常。

`JniCall` 为 record：`method()` 为本次操作对应的 `Jni` 接口 Method，`signature()` 为成员描述符，`arguments()` 为未解码的操作参数。

`JniConfiguration.Event` 为 record：`phase()` 取 BEFORE、AFTER、FAILED，另含 `call()`、`result()`、`failure()`、`targetThrown()`。BEFORE 可调用 `WorkerSession.checkCancelled()`；抛出异常时通知 FAILED 并终止该次操作。正常完成通知 AFTER。目标 Java 异常通知 FAILED，保留原 Throwable 且 `targetThrown` 为 true，底座把原异常设为 JNI pending exception。FAILED 观察器抛出的异常附入原异常的 suppressed。等待取消时通知 FAILED。

观察器在 backend 上执行。宿主操作经 `HostJni.submit` 与单次挂起转到宿主线程，完成后由 completion 回到 backend 写入 JNI 返回值。对象转换保留宿主对象身份。宿主 getter 使用 backend 初始化时捕获的 profile、路径和 classloader；宿主线程没有 `WorkerSession` 绑定。

`acceptMethod`、`acceptField`、`toReflectedMethod` 是同步元数据操作，result 为 boolean 或反射对象，不执行结果 handler。`ThrowNew` 的 `invokeException` 直接转发默认 Proxy。

在解析业务类和执行 native 初始化之前完成 JNI 配置。

## Proxy 宿主值

`ProxyDvmObject.unpack(DvmObject<?>)` 复用底座正式转换取得宿主值。proxy 对象数组保留原 backing array、组件类型与引用身份。null 输入返回 null；无宿主值的 wrapper 抛 `UnsupportedOperationException`。该操作不新增或清理 VM 引用，也不转移所有权。

## Android 引导

Android 模式的引导由 `AndroidBootstrap` 执行：JUnit4 `RobolectricTestRunner`，`@Config(sdk = 30, manifest = Config.NONE, application = RuntimeApplication.class, shadows = DeviceGlShadow.class)`。

引导顺序为装载 `libdevice-egl.so`、安装 pbuffer EGL、调用 `WorkerExecution.runSession(RuntimeEnvironment.getApplication())`。`libdevice-egl.so` 为 EGL 类初始化提供 native 实现，pbuffer EGL 提供 EGL 上下文与 GL 字符串。`DeviceGlShadow` 提供 `GLES20` 的 GL 字符串与 no-op 调用；`dg.glVendor`、`dg.glRenderer`、`dg.glVersion` 系统属性覆盖默认的采集值。

引导前，worker 把 `profile.package.name` 写入 `robolectric.applicationPackageName`，把 `processName` 写入 `robolectric.applicationProcessName`，并把 `io.github.aireposave.unidbg.runtime.egl.` 加入 `org.robolectric.packagesToNotAcquire`。

`RuntimeApplication.onCreate` 通过 `PackageManager.getPackageInfo(name, GET_SIGNATURES)` 取得宿主 `PackageInfo`，再投影 `profile.package` 与 `profile.packages`。

## client API

`RunRequest` 为 record，字段如下：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `runtimeDirectory` | `Path` | worker 安装目录，需含 `jvm-args.txt` 与 `lib/` |
| `entrypoint` | `String` | 入口 FQCN |
| `classpath` | `List<Path>` | 任务 classpath，写入请求 |
| `arguments` | `JsonNode` | 入口参数 |
| `mode` | `Mode` | `NATIVE_ONLY` 或 `ANDROID` |
| `profile` | `JsonNode` | 环境 profile |
| `workingDirectory` | `Path` | 工作进程 cwd |
| `outputDirectory` | `Path` | 会话输出目录 |
| `cancellationGrace` | `Duration` | 协作关闭预算，非负 |
| `terminationWait` | `Duration` | TERM 与 KILL 各自的等待预算，正数 |
| `systemProperties` | `Map<String,String>` | 追加为 `-D` 参数 |
| `traceId` | `String` | 关联标识 |

`RuntimeClient.start(RunRequest)` 依次创建 `outputDirectory/logs`、删除旧的 `cancel.request` 与 `result.json`、写入 `request.json`。随后用当前 JVM 的 `java` 与 `jvm-args.txt` 启动 `WorkerMain --request`，把 stderr 重定向到 `logs/stderr.log`，返回 `SessionHandle`。

`SessionHandle` 提供 `pid()`、`outputDirectory()`、`completion()`、`send(JsonNode)`、`receive()`、`endInput()`、`cancel()`、`close()`。`receive()` 在 EOF 时返回 `NullNode`。`cancel()` 等待协作关闭预算并回收整棵子树。

根进程退出后 `completion()` 完成，返回 `RunResult(status, exitCode, pid, payload, failure, outputDirectory)`。状态取 `result.json` 的 `status` 与本地取消标志：取消过或状态为 `CANCELLED` 时为 `CANCELLED`；退出码 0 且状态为 `COMPLETED` 时为 `COMPLETED`；其余为 `FAILED`。子树回收在 `cancel()` 内完成，与 `completion()` 完成时刻没有先后保证。

## Direct main 入口

`DirectMainEntrypoint` 的 arguments 为 `{"mainClass":"com.example.Main","arguments":["arg"]}`。它要求 `public static void main(String[])`，在原执行域调用该方法，并把 `System.out` 指向 `session.output()`，因此原 main 的机器数据进入 stdout 与 `protocol.jsonl`。原 main 必须返回，不能调用 `System.exit`。

## JUnit5 接入

消费者实现 `WorkerEntrypoint`，在 `run` 内调用官方 JUnit Platform Launcher，执行原 selectors 与参数扩展，并写真实 XML。live 对象留在工作 JVM 内；跨进程边界只传 JSON、路径和标量。
