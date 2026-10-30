# unidbg-robolectric

一个分析会话对应一个独立 JDK 21 工作 JVM。会话内复用 unidbg emulator、VM、JNI 回调、调试器和业务状态。持久会话运行到 close 或 EOF，没有默认空闲超时。

## 制品

| 坐标 | 职责 |
| --- | --- |
| `io.github.ai-repo-save:unidbg-robolectric-client:1.2.2` | 工作进程启动、JSON 通道、结果与取消 |
| `io.github.ai-repo-save:unidbg-robolectric-spi:1.2.2` | 工作入口、资源登记、JNI handler、owner delegate、observer |
| `io.github.ai-repo-save:unidbg-robolectric-worker:1.2.2` | 官方 Android 生命周期、环境投影、离线发行 |

底座为 `io.github.ai-repo-save.unidbg:*:0.13.1`，保留原 Java 包名与 native JNI 名称。Robolectric core 为 `io.github.ai-repo-save.robolectric:robolectric:1.3.0`，正式传递依赖完整 `io.github.ai-repo-save.robolectric:shadows-framework:1.3.0`；其余官方模块为 4.17。Android 版本为 11 / API 30，SDK 为 `android-all-instrumented:11-robolectric-6757853-i7`。

guest 架构为 ARM64，后端为 Unicorn2，宿主为 Linux x86-64。native resolver、rootfs、自定义 syscall、ELF 装载顺序、post-map hook 与 JNI_OnLoad 时机由工作入口决定。native-only 模式跳过 Robolectric 引导，也不需要 SDK 目录。

## 构建

构建需要 JDK 21、宿主 gcc 和依赖仓库：

```sh
./gradlew --no-daemon \
  -PapkParserRepository=/path/to/apk-parser-release-repository \
  -ProbolectricRepository=/path/to/robolectric-release-repository \
  :worker:installDist
```

安装目录为 `worker/build/<version>/install/unidbg-worker`；`version` 取 `build.gradle.kts` 中 `allprojects.version` 的值，当前为 1.2.2。

`apk-parser` fork 由 `-PapkParserRepository` 提供，其余依赖从 `mavenLocal()` 与 `mavenCentral()` 解析。`gcc` 用于编译 `libdevice-egl.so`。

依赖准备、离线构建、发行仓库与开发变体见 `docs/build.md`。

## 验证

发行整体集成通过显式任务执行，不进入 `test` 与 `check`：

```sh
./gradlew --offline --no-daemon jniIntegration
```

真实 APK 来源验证使用 `apkStartupIntegration -PstartupApk=/absolute/input.apk`。测试范围、参数与证据路径见 `docs/verification.md`。
