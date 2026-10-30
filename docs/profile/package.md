# 包 profile

`profile.package` 配置宿主应用，`profile.packages` 配置附加已安装包。每项提供非空 `name`，或提供 `apk` 由 APK 派生包名。附加包名必须唯一，且与宿主不同。

字段：

| 字段 | 类型 | 投影目标 |
| --- | --- | --- |
| `name` | string | `PackageInfo.packageName`、`ApplicationInfo.packageName` |
| `apk` | string | 存在的绝对 APK 路径，用于派生元数据、验证签名并装载宿主资源 |
| `versionName` | string 或 null | `PackageInfo.versionName` |
| `versionCode` | 64 位 integer | `PackageInfo.setLongVersionCode` |
| `targetSdk` | 32 位 integer | `ApplicationInfo.targetSdkVersion` |
| `uid` | 非负 32 位 integer | `ApplicationInfo.uid` |
| `flags` | 32 位 integer | `ApplicationInfo.flags` |
| `nativeLibraryDir` | string | `ApplicationInfo.nativeLibraryDir` |
| `signatures` | Base64 证书 DER 数组 | `PackageInfo.signatures` |
| `installer` | string 或 null | `PackageManager.setInstallerPackageName` |
| `requestedPermissions` | string 数组或 null | `PackageInfo.requestedPermissions` |
| `requestedPermissionsFlags` | 32 位 integer 数组或 null | `PackageInfo.requestedPermissionsFlags` |
| `guestSourceDir` | string | Java 可见来源路径与 native APK 挂载点 |

`guestSourceDir` 的路径语义在 `docs/profile/package-source.md`。未列出的字段不参与投影。

## APK 派生

提供 `apk` 时，库用 `io.github.ai-repo-save.apk-parser:apk-parser:2.6.11` 派生 `name`、`versionName`、`versionCode` 与 APK 声明的 `targetSdk`，用 `com.android.tools.build:apksig:9.4.1` 派生 `signatures`。显式提供的同名字段必须与派生结果一致，冲突时请求失败。派生结果写入 `effective-request.json`。

未提供 `apk` 时，`name` 为必需字段。

`apk` 只作为宿主 Application 的物理资源与元数据来源。附加包的 `apk` 仅提供元数据，库不加载其 DEX，也不创建对应 Application。

## 签名

验证使用 `ApkVerifier` 正式 API，最低与最高检查平台版本均为 30。验证失败时请求失败；库不从未验证的签名块提取身份。

`signatures` 表达 API 30 `GET_SIGNATURES` 返回的身份集合：

- 存在签名轮换 lineage 时取 lineage 中最旧的证书。
- 其他情况取 `getSignerCertificates()` 中每个 signer 的单张证书。
- 证书链中的中间证书不作为额外 signer。

显式 `signatures` 使用同一语义，每个元素是一张证书 DER 的 Base64。与 APK 派生值不一致时请求失败。该字段不配置 `SigningInfo` 或 `GET_SIGNING_CERTIFICATES`。

## 安装

宿主 `PackageInfo` 由框架在包初始化时产生，库把该对象的 `applicationInfo` 指向 `Application.getApplicationInfo()`，再按 profile 补齐字段。附加包使用新建的 `PackageInfo` 与 `ApplicationInfo`。

两者都通过公开 `ShadowPackageManager.installPackage` 安装。该 API 设置 `FLAG_INSTALLED`，并为缺失的包名、进程名和目标 SDK 应用 Robolectric 的安装语义。宿主 `package.uid` 由早期 lifecycle plugin 通过 `ShadowProcess.setUid` 应用；附加包的 `uid` 只写入其 `ApplicationInfo`。附加包不改变宿主 Application、进程名或进程 UID。

## installer

省略 `installer` 时保留框架的安装来源状态。显式 null 按 [Android 11 PackageManager.setInstallerPackageName 合同](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/content/pm/PackageManager.java#6319) 清除来源关联，`getInstallerPackageName` 返回 null。

## 权限

`requestedPermissions` 与 `requestedPermissionsFlags` 必须成对提供，两个数组等长，索引一一对应。两者同时为 null 时投影为两个 null 字段；空数组表达空集合。

库原样投影调用方采集的权限名称与 flags 整数值，不从 APK 的 `uses-permission` 声明推导已安装包的权限或授权状态。APK 清单不足以确定 API 30 PackageManager 的授权事实，因此提供 `apk` 时权限字段仍需单独配置。
