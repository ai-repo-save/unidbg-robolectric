# 验证

集成验证只通过显式任务或显式 JUnit 调用执行，不加入默认 `test` 与 `check`。证据输出到 `build/<version>/evidence/<runId>/`，`runId` 为本次运行开始时的时间戳。

## 任务

| 任务 | 测试 | 输入 |
| --- | --- | --- |
| `jniIntegration` | `RuntimeIntegrationTest` | 无 |
| `apkStartupIntegration` | `ApkStartupIntegrationTest` | `-PstartupApk=<绝对 APK 路径>` |
| `outputReuseIntegration` | `OutputReuseIntegrationTest` | 无 |
| `developmentJniIntegration` | `RuntimeIntegrationTest.independentWorkersAndNativeSideEffects` | `-PdevelopmentCoreClasspath` |

前三项依赖 `:worker:installDist`，并使用该安装运行工作进程。`jniIntegration` 在 Gradle 侧固定设置 Robolectric 下载源 `https://repo.maven.apache.org/maven2` 与代理 `127.0.0.1:7891`；工作进程本身按 `runtime-manifest.json` 离线运行。

`jniIntegration` 覆盖：同一宿主的两个 Android 会话具有不同 PID；会话内包、Settings、Preferences、Build、native 属性、JNI model 与 guest 文件读写生效；native-only 模式；入口异常产生 FAILED；持久通道的多轮请求与响应；协作取消、子进程取消与阻塞取消后的进程回收；标准输入通过工作进程描述符读取。

`apkStartupIntegration` 使用真实 APK，记录解析器来源、六个 Java 来源路径、六个 ARM64 JNI 来源路径与 ApkAssets 路径，并在外部进程比较 ZIP 条目字节与完整 APK 的 SHA-256。该测试不加载应用 SO，也不访问设备。

`outputReuseIntegration` 先由 client 启动并取消一个会话，再用同一输出目录启动第二个会话，最后由 `bin/unidbg-worker` 直接运行第三个会话。

## 手工调用

`PackageProfileIntegrationTest` 与 `DeviceServicesIntegrationTest` 没有对应的 Gradle 任务，通过 JUnit4 入口运行。

`PackageProfileIntegrationTest` 需要调用方提供真实采集的 profile 与独立 oracle。`oracle.packages` 以包名为键，每项包含 `name`、`versionName`、`versionCode`、`uid`、`targetSdk`、`signatures`；需要核对权限的包另含 `requestedPermissions` 与 `requestedPermissionsFlags`。`signatures` 取自真机导出的完整 DER 编码，库不从待验证 APK 生成预期值。

```sh
./gradlew --no-daemon integrationClasses :worker:installDist
runtime="$PWD/worker/build/<version>/install/unidbg-worker"
evidence="$PWD/build/<version>/evidence/package-profile-$(date -u +%Y%m%dT%H%M%S)"
java -cp "$PWD/build/<version>/classes/java/integration:$runtime/lib/*" \
  -Dintegration.runtime="$runtime" \
  -Dintegration.fixtureClasspath="$PWD/build/<version>/classes/java/integration" \
  -Dintegration.evidenceDir="$evidence" \
  -Dintegration.packageProfile="$PACKAGE_PROFILE" \
  -Dintegration.packageOracle="$PACKAGE_ORACLE" \
  org.junit.runner.JUnitCore org.example.unidbg.PackageProfileIntegrationTest
```

该测试通过真实 ARM64 JNIEnv 表调用 `getPackageInfo`、`Signature.toByteArray` 与权限字段读取，比较查询结果与 oracle。

`DeviceServicesIntegrationTest` 启动 `WorkerMain` 子进程，在工作入口通过公开框架 getter 读回 profile 中的功能、共享库、显示、传感器与内存配置：

```sh
./gradlew --no-daemon integrationClasses :worker:installDist
runtime="$PWD/worker/build/<version>/install/unidbg-worker"
java \
  -Dintegration.runtime="$runtime" \
  -Dintegration.evidenceDir="$PWD/build/<version>/evidence/services-$(date -u +%Y%m%dT%H%M%S%N)" \
  -cp "worker/build/<version>/classes/java/main:worker/build/<version>/resources/main:spi/build/<version>/classes/java/main:client/build/<version>/classes/java/main:build/<version>/classes/java/integration:build/<version>/resources/integration:$runtime/lib/*" \
  org.junit.runner.JUnitCore org.example.unidbg.DeviceServicesIntegrationTest
```

子进程的 classpath 取测试 JVM 的 `java.class.path`，因此该命令列出 worker、spi 与 client 的当前 classes。

## 证据

任务把 JUnit XML 与 HTML 报告写入 `evidence/<runId>/xml` 与 `evidence/<runId>/html`，工作会话产物写入 `evidence/<runId>/artifacts`。`apkStartupIntegration` 与 `outputReuseIntegration` 使用各自的子目录。

证据保留到显式清理。存在活动 worker 时不要清理或覆盖安装目录。
