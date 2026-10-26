# 设备服务 Profile

Android 模式通过可选的 `profile.services` 配置框架服务。`RuntimeApplication` 初始化后，`ProfileProjection` 在调用工作入口之前应用配置。输入来自调用方；库通过 Robolectric 4.17 公开 API 投影，不预置设备采集值。

`services` 是 JSON 对象，支持 `systemFeatures`、`display`、`sensors`。省略 `services` 或其中一项时保留对应框架初始状态。所有配置对象拒绝未知字段；`systemFeatures` 的键是调用方提供的功能名称。字段值拒绝 `null` 和类型转换。整数要求有符号 32 位 JSON 整数；浮点数要求能表示为有限 `float` 的 JSON 数字。

## System features

`services.systemFeatures` 是功能名称到 JSON boolean 的映射。每项通过 `ShadowPackageManager.setSystemFeature(name, supported)` 设置，供 `PackageManager.hasSystemFeature(name)` 读取。显式 `false` 表示不支持；未列出的功能保留框架初始状态。

该映射独立于传感器和显示配置。库不根据其他服务、机型或 API 级别推导功能支持情况。

## 默认显示

`services.display` 是默认显示 `Display.DEFAULT_DISPLAY` 的配置对象。库从 `Application` 的 `DisplayManager` 获取显示，通过以下公开 API 设置字段。

| 字段 | JSON 类型 | 投影 API |
| --- | --- | --- |
| `name` | string | `ShadowDisplay.setName` |
| `flags` | integer | `ShadowDisplay.setFlags` |
| `state` | integer | `ShadowDisplay.setState` |
| `width` | integer | `ShadowDisplay.setWidth` |
| `height` | integer | `ShadowDisplay.setHeight` |
| `realWidth` | integer | `ShadowDisplay.setRealWidth` |
| `realHeight` | integer | `ShadowDisplay.setRealHeight` |
| `densityDpi` | integer | `ShadowDisplay.setDensityDpi` |
| `xdpi` | number | `ShadowDisplay.setXdpi` |
| `ydpi` | number | `ShadowDisplay.setYdpi` |
| `rotation` | integer | `ShadowDisplay.setRotation` |
| `mode` | object | `ShadowDisplayManager.ModeBuilder`、`setSupportedModes` |

`width`、`height` 是应用可用像素尺寸。`realWidth`、`realHeight` 是 real metrics 的像素尺寸。`densityDpi`、`xdpi`、`ydpi` 的单位是 DPI。`state`、`rotation`、`flags` 使用 Android 对应常量的整数值。

提供 `mode` 时，必须同时提供以下三个字段：

| 字段 | JSON 类型 | 含义 |
| --- | --- | --- |
| `physicalWidth` | integer | `Display.getMode().getPhysicalWidth()`，单位为像素 |
| `physicalHeight` | integer | `Display.getMode().getPhysicalHeight()`，单位为像素 |
| `refreshRate` | number | 模式刷新率，单位为 Hz |

库保留当前模式 ID，将支持的模式替换为该单一模式。模式物理尺寸与 real metrics 独立配置；调用方分别提供采集值。

当前投影只配置默认显示。`type`、显示数量、多模式清单、HDR 和 cutout 不属于该 schema。`ShadowDisplay` 没有公开的类型设置 API，库保留框架初始显示类型。

API 30 的 `Application` 是非 visual Context；调用 `Application.getDisplay()` 抛出异常符合 Android 合同。消费者通过 `DisplayManager.getDisplays()` 或 `getDisplay(id)` 查询显示。

## 默认传感器集合

`services.sensors` 是默认传感器配置的 JSON 数组，每种 `type` 至多一项，重复类型拒绝。它表达供 `SensorManager.getDefaultSensor(type)` 查询的默认传感器集合，不表达设备完整传感器清单或同类型多实例的优先级。

每项是以下对象，只有 `type` 必填：

| 字段 | JSON 类型 | 投影 API |
| --- | --- | --- |
| `type` | integer | `SensorBuilder.setType` |
| `name` | string | `SensorBuilder.setName` |
| `wakeUp` | boolean | `SensorBuilder.setWakeUpFlag` |
| `maximumRange` | number | `SensorBuilder.setMaximumRange` |
| `minDelay` | integer | `SensorBuilder.setMinDelay` |
| `maxDelay` | integer | `SensorBuilder.setMaxDelay` |
| `fifoMaxEventCount` | integer | `SensorBuilder.setFifoMaxEventCount` |
| `fifoReservedEventCount` | integer | `SensorBuilder.setFifoReservedEventCount` |

延迟的单位是微秒，FIFO 计数的单位是事件，`maximumRange` 的单位由传感器类型决定。省略可选字段时保留 `SensorBuilder` 默认值：`name` 为 `null`、`wakeUp` 为 `false`、其余数值为零。默认值不代表真机采集结果。

提供数组时，库通过 `ShadowSensorManager.removeSensor` 移除初始传感器，再通过 `addSensor` 注册数组中的传感器。空数组表示默认传感器集合为空，未配置类型的 `getDefaultSensor(type)` 返回 `null`。`getSensorList` 同时可见已注册的该集合。

公开 `SensorBuilder` 无法设置 `vendor`、`version`、`power`、`resolution`、`stringType`、`handle` 等字段，输入这些字段会被拒绝。调用方仅提供已采集且该 schema 支持的事实，不按其他类型推测缺失传感器。

## 显式集成验证

`DeviceServicesIntegrationTest` 属于已有 `integration` 源集，不进入默认 `test` 或 `check`。测试启动正式 `WorkerMain` 子进程，在工作入口通过公开框架 getter 读回调用方配置。已有 worker 安装提供依赖、JVM 参数和离线 SDK；开发 classes 优先于安装中的 JAR，命令不覆盖安装。

在仓库根目录执行单个测试：

```sh
./gradlew --offline --no-daemon :worker:classes compileIntegrationJava
java \
  -Dintegration.runtime="$PWD/worker/build/install/unidbg-worker" \
  -Dintegration.evidenceDir="$PWD/build/evidence/services-$(date -u +%Y%m%dT%H%M%S%N)" \
  -cp "worker/build/classes/java/main:worker/build/resources/main:spi/build/classes/java/main:client/build/classes/java/main:build/classes/java/integration:worker/build/install/unidbg-worker/lib/*" \
  org.junit.runner.JUnitCore org.example.unidbg.DeviceServicesIntegrationTest
```

## 诊断

投影日志沿用 worker 的 JSON 日志及 `trace_id`、`session_id`，事件为 `system_features_projected`、`display_profile_projected`、`default_sensors_projected`。原始输入由既有 `worker_started` 事件和 `effective-request.json` 保存。字段校验或框架调用失败会终止工作入口启动，错误由 worker 边界记录堆栈。
