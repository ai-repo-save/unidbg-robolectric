# 设备服务 profile

`profile.services` 在会话构造时投影，即 Application 创建之后、工作入口执行之前。支持的键为 `systemFeatures`、`systemAvailableFeatures`、`systemSharedLibraryNames`、`display`、`sensors`、`memory`；其他键使请求失败。

省略 `services` 或其中某一项时，对应框架状态保持初始值。整数字段要求有符号 32 位 JSON 整数，浮点字段要求可表示为有限 `float` 的 JSON 数字，布尔字段要求 JSON boolean。

## systemFeatures

功能名称到 JSON boolean 的映射，通过 `ShadowPackageManager.setSystemFeature(name, supported)` 设置，供 `PackageManager.hasSystemFeature(String)` 查询。显式 false 表示不支持；未列出的功能保持框架初始状态。

## systemAvailableFeatures

`FeatureInfo` 配置对象数组。提供数组时，库先调用 `ShadowPackageManager.clearSystemAvailableFeatures()`，再按输入顺序调用 `addSystemAvailableFeature`。每个非 null `name` 同时通过 `setSystemFeature(name, true)` 标记为支持。

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `name` | string 或 null，必填 | `FeatureInfo.name`；null 表示 OpenGL ES 版本条目 |
| `version` | 32 位 integer | `FeatureInfo.version`，缺省 0 |
| `reqGlEsVersion` | 32 位 integer | `FeatureInfo.reqGlEsVersion`，缺省 0 |

未列出的功能支持配置保持原值。空数组使 `getSystemAvailableFeatures()` 返回 null，符合 Robolectric getter 语义。fork 的 `hasSystemFeature(String,int)` 恒返回 false，该投影只同步单参数查询。

## systemSharedLibraryNames

string 数组，通过 `ShadowPackageManager.clearSystemSharedLibraryNames()` 与 `addSystemSharedLibraryName` 替换 `PackageManager.getSystemSharedLibraryNames()` 的结果。顺序与输入一致；空数组得到空结果。

## display

`display` 配置默认显示 `Display.DEFAULT_DISPLAY`。库从 `Application` 的 `DisplayManager` 取得显示对象，通过以下公开 API 设置字段：

| 字段 | 类型 | 投影 |
| --- | --- | --- |
| `name` | string | `ShadowDisplay.setName` |
| `flags` | integer | `ShadowDisplay.setFlags` |
| `type` | integer | `ShadowDisplay.setType` |
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

`width` 与 `height` 是应用可用像素尺寸，`realWidth` 与 `realHeight` 是 real metrics 的像素尺寸。`densityDpi`、`xdpi`、`ydpi` 的单位为 DPI。`type`、`state`、`rotation`、`flags` 取 Android 对应常量的整数值。设置同时作用于当前显示对象与后续创建的显示对象。

`mode` 的字段全部必填：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `physicalWidth` | integer | `Display.getMode().getPhysicalWidth()`，像素 |
| `physicalHeight` | integer | `Display.getMode().getPhysicalHeight()`，像素 |
| `refreshRate` | number | 刷新率，Hz |

库保留当前模式 ID，把支持的模式替换为该单一模式。模式物理尺寸与 real metrics 独立配置。

投影只作用于默认显示；显示数量、多模式清单、HDR 与 cutout 不在该 schema 内。省略 `type` 时保留框架初始显示类型。

API 30 的 `Application` 是非 visual Context，`Application.getDisplay()` 抛出异常符合 Android 合同；消费者通过 `DisplayManager.getDisplays()` 或 `getDisplay(id)` 查询显示。

## sensors

默认传感器配置数组，每种 `type` 至多一项，重复类型使请求失败。该数组表达 `SensorManager.getDefaultSensor(type)` 可查询的默认传感器集合。

| 字段 | 类型 | 投影 |
| --- | --- | --- |
| `type` | integer | `SensorBuilder.setType`，必填 |
| `name` | string | `SensorBuilder.setName` |
| `wakeUp` | boolean | `SensorBuilder.setWakeUpFlag` |
| `maximumRange` | number | `SensorBuilder.setMaximumRange` |
| `minDelay` | integer | `SensorBuilder.setMinDelay` |
| `maxDelay` | integer | `SensorBuilder.setMaxDelay` |
| `fifoMaxEventCount` | integer | `SensorBuilder.setFifoMaxEventCount` |
| `fifoReservedEventCount` | integer | `SensorBuilder.setFifoReservedEventCount` |

延迟单位为微秒，FIFO 计数单位为事件，`maximumRange` 的单位由传感器类型决定。可选字段缺省时取 `SensorBuilder` 默认值：`name` 为 null，`wakeUp` 为 false，其余数值为 0。

提供数组时，库先移除现有传感器，再注册数组中的传感器。空数组表示默认传感器集合为空，未配置类型的 `getDefaultSensor(type)` 返回 null。`getSensorList` 同样只返回该集合。

`SensorBuilder` 无公开设置的字段（`vendor`、`version`、`power`、`resolution`、`stringType`、`handle` 等）属于被拒绝的输入。

## memory

`memory.totalMem` 为 64 位整数，单位为字节，必填。库把它写入 `ActivityManager.MemoryInfo.totalMem`，再经 `ShadowActivityManager.setMemoryInfo` 提供给 `ActivityManager.getMemoryInfo`。该值对应设备 `dumpsys meminfo` 的 Total RAM 乘以 1024。
