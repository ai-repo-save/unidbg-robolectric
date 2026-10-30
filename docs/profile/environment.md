# 环境 profile

Android 模式的生效顺序：

1. Android 引导前：worker 解析 APK 元数据，写 `effective-request.json`，设置包名与进程名属性。
2. Android 引导期间：早期 lifecycle plugin 建立包来源与进程 UID；`RuntimeApplication.onCreate` 投影包元数据。
3. 会话构造时：投影 Build、Settings、Preferences 与 services，并通过 `ShadowSystemProperties.override` 应用 Android 属性的可见值。
4. emulator 配置时：下发 guest 凭据，安装 libc 属性通道与 guest 挂载。
5. 工作入口执行。

顶层字段：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `androidSdk` | integer | Android API 级别，缺省 30 |
| `processName` | string | 进程名，缺省取 `package.name` |
| `processCredentials` | object | native 身份凭据 |
| `build` | object | Android Build 字段 |
| `nativeProperties` | object | 属性名到字符串值 |
| `settings` | object | Settings 命名空间到键值 |
| `preferences` | object | 偏好文件名到键值 |
| `filesystem` | object | guest 文件系统映射 |
| `package`、`packages` | object、array | 宿主与附加包 |
| `services` | object | 框架服务投影 |

顶层未知字段不参与投影。`package` 与 `packages` 的字段在 `docs/profile/package.md`，`services` 的字段在 `docs/profile/device-services.md`。

`androidSdk` 只接受 30；其他值使请求失败。

`processName` 写入 `robolectric.applicationProcessName`；`Application.getProcessName()` 返回该值。

## processCredentials

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `uid` | 非负 32 位 integer | 真实 UID |
| `euid` | 非负 32 位 integer | 有效 UID |
| `gid` | 非负 32 位 integer | 真实 GID |
| `egid` | 非负 32 位 integer | 有效 GID |
| `groups` | 非负 32 位 integer 数组 | 补充 GID |

`uid` 缺省时取 `package.uid`；两处同时提供且取值不同时请求失败。`groups` 显式空数组表示无补充组。其他字段缺失时保持未知，库不从 UID 推导 GID 或有效身份。

凭据通过底座 `AndroidSyscallHandler.setGuestCredentials` 供 native 身份 syscall 与文件权限查询使用。真实 UID 同时经 `ShadowProcess.setUid` 应用于 `Process.myUid()`。缺失的凭据不阻止启动；需要该身份的查询由底座抛 `UnsupportedOperationException`。

## build

| 字段 | 投影 |
| --- | --- |
| `MODEL` | `ShadowBuild.setModel` |
| `MANUFACTURER` | `ShadowBuild.setManufacturer` |
| `BRAND` | `ShadowBuild.setBrand` |
| `DEVICE` | `ShadowBuild.setDevice` |
| `PRODUCT` | `ShadowBuild.setProduct` |
| `BOARD` | `ShadowBuild.setBoard` |
| `HARDWARE` | `ShadowBuild.setHardware` |
| `FINGERPRINT` | `ShadowBuild.setFingerprint` |
| `ID` | `ShadowBuild.setId` |
| `DISPLAY` | `ShadowBuild.setDisplay` |
| `TAGS` | `ShadowBuild.setTags` |
| `TYPE` | `ShadowBuild.setType` |
| `SERIAL` | `ShadowBuild.setSerial` |

值必须为 JSON 字符串。其他字段名使请求失败。

可选 `build.VERSION` 为对象，字段 `RELEASE`、`INCREMENTAL`、`SECURITY_PATCH` 分别调用 `ShadowBuild.setVersionRelease`、`setVersionIncremental`、`setVersionSecurityPatch`。这些字段只影响 Build 值，`Build.VERSION.SDK_INT` 仍为 30。

`SERIAL` 配置 `Build.getSerial()` 并派生 `ro.serialno`。显式 `nativeProperties.ro.serialno` 与派生值不同时请求失败。`Build.SERIAL` 静态字段按 API 30 合同保持 `Build.UNKNOWN`。

## nativeProperties

属性名到字符串值的映射，键无白名单。值通过 `ShadowSystemProperties.override` 提供给 Android `SystemProperties.get`，并通过 `SystemPropertyHook` 与 `SystemProperties` 模块提供给 libc 查询。Android 通道在会话构造时生效，libc 通道在 emulator 配置时安装。两个通道使用同一映射，与 Build 静态字段相互独立。

属性值长度上限为 `PROP_VALUE_MAX - 1` 字节；键与值都不得包含 NUL。Android 模式在未配置时补入 `ro.build.version.sdk=30`；显式值不改变实际运行 SDK。

## settings

命名空间为 `global`、`secure`、`system`，值为 JSON 字符串或 null，写入 `Settings.{Global,Secure,System}.putString`。其他命名空间使请求失败；写入返回 false 时请求失败。null 值按 Android 合同删除该设置。

## preferences

键为 `Application.getSharedPreferences` 的文件名，值为字符串、boolean、32 位 integer 或 64 位 integer，分别写入对应类型的偏好项。其他值类型使请求失败；`commit()` 返回 false 时请求失败。

## filesystem

`filesystem.guestDataDirectory` 指定 guest 数据目录，缺省为 `/data/user/0/<packageName>`，映射到 `Application.getDataDir()`；挂载在 emulator 配置时安装。挂载与路径投影规则在 `docs/profile/package-source.md`。
