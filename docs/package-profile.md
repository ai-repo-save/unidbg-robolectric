# 包 Profile

Runtime 1.2.0 的 Android 模式通过 `profile.package` 配置唯一宿主，通过可选的 `profile.packages` 数组配置附加已安装包。每项必须提供非空 `name` 或用于派生包名的 `apk`。附加包名必须唯一，与宿主重名时拒绝启动。

库在 Android 引导前解析 APK，将结果写入 `effective-request.json`。`RuntimeApplication.onCreate()` 在工作入口启动前投影宿主与附加包。宿主保留框架创建的 `ApplicationInfo`；附加包使用独立的 `PackageInfo` 和 `ApplicationInfo`，统一通过公开的 `ShadowPackageManager.installPackage` 安装。`android` 包也由调用方在清单中显式配置。

省略 `packages` 时保留框架初始清单，提供该数组时向清单安装这些包。附加包不改变宿主 Application、进程名或进程 UID。`package.uid` 仍由早期进程初始化插件应用；附加包的 `uid` 只设置其 `ApplicationInfo.uid`。

## 元数据字段

宿主与附加包共用以下字段：

| 字段 | JSON 类型 | 来源或投影目标 |
| --- | --- | --- |
| `name` | string | `PackageInfo.packageName`、`ApplicationInfo.packageName` |
| `apk` | string | 存在的绝对 APK 路径，用于解析元数据与验证签名 |
| `versionName` | string 或 null | `PackageInfo.versionName` |
| `versionCode` | 64 位 integer | `PackageInfo.setLongVersionCode` |
| `targetSdk` | 32 位 integer | `ApplicationInfo.targetSdkVersion` |
| `uid` | 非负 32 位 integer | `ApplicationInfo.uid` |
| `flags` | 32 位 integer | `ApplicationInfo.flags` |
| `nativeLibraryDir` | string | `ApplicationInfo.nativeLibraryDir` |
| `signatures` | string 数组 | Base64 编码的签名证书 DER，投影至 `PackageInfo.signatures` |
| `installer` | string | `PackageManager.setInstallerPackageName` |
| `requestedPermissions` | string 数组或 null | `PackageInfo.requestedPermissions` |
| `requestedPermissionsFlags` | 32 位 integer 数组或 null | `PackageInfo.requestedPermissionsFlags` |

提供 `apk` 时，库通过 apk-parser 2.6.10 派生 `name`、`versionName`、`versionCode` 和 APK 声明的 `targetSdk`，通过 apksig 派生 `signatures`。显式提供的同名字段必须与派生结果一致，冲突时拒绝启动。附加包复用相同的解析和冲突检查。

未提供的字段保留现有框架对象或新对象的初始值。库不按机型或包名补齐包事实。公开的 `installPackage` 会设置 `FLAG_INSTALLED`，并为缺失的包名、进程名和目标 SDK 应用 Robolectric 自身的安装语义。

宿主 `apk` 同时供框架装载宿主资源。附加包 `apk` 仅作为元数据来源，不加载其 DEX，也不创建 Application。宿主 `guestSourceDir` 是 native IO 的显式挂载别名；附加包清单不创建文件挂载。

## 签名验证

库依赖官方 `com.android.tools.build:apksig:9.4.1`，通过 `ApkVerifier` 正式 API 验证 API 30 的签名安装规则，最低和最高检查平台版本均为 30。验证失败时拒绝启动；不从未验证的签名块提取可信身份。

`signatures` 表达 API 30 `GET_SIGNATURES` 返回的签名身份。存在签名轮换历史时，只取 lineage 中最旧证书；其他情况取 `getSignerCertificates()` 返回的每个 signer 的单张证书。证书链中的中间证书不作为额外 signer。该规则对应 [AOSP Android 11 PackageParser.generatePackageInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/content/pm/PackageParser.java#845)。

显式 `signatures` 使用相同语义；每个元素是一张证书 DER 的 Base64 字符串。该字段不配置 `SigningInfo` 或 `GET_SIGNING_CERTIFICATES`。

## 已采集的权限

`requestedPermissions` 和 `requestedPermissionsFlags` 必须同时提供。两个数组必须等长，索引一一对应；两个字段同时为 `null` 时投影为两个 null 字段。空数组表达已采集的空集合。

调用方提供实际 PackageManager 采集的权限名称、顺序及 flags 整数值。库原样投影这些值，不推导 `REQUESTED_PERMISSION_GRANTED`，也不把 APK 的 `uses-permission` 声明转换为已安装包的权限或授权状态。APK 清单不足以确定 API 30 PackageManager 的授权事实；因此即使提供 `apk`，权限字段仍由采集结果单独配置。

## 诊断

签名验证事件为 `apk_signature_error`、`apk_signature_warning`、`apk_signatures_verified`。日志保留验证器返回的全局、各签名方案 signer、被忽略的 v1 signer 及 source stamp 的错误和警告。安装投影事件为 `package_profile_installed`，记录包名及输入位置。

日志沿用 worker 的 JSON 持久化及 `trace_id`、`session_id`。输入保存在 `worker_started` 事件与 `effective-request.json`；启动失败由 worker 边界记录堆栈。

## 定向验证

`PackageProfileIntegrationTest` 位于独立的 integration 源集，不加入默认测试集。调用方提供真实采集的 profile 与独立 oracle JSON。`oracle.packages` 以实际包名为键，每项包含 `name`、`versionName`、`versionCode`、`uid`、`targetSdk`、`signatures`；需要核对权限的包另含 `requestedPermissions` 和 `requestedPermissionsFlags`。`signatures` 从真机导出的完整 DER 编码为 Base64，不从待验证 APK 生成预期值。

以下命令只运行这一项集成测试。`PACKAGE_PROFILE` 和 `PACKAGE_ORACLE` 指向调用方准备的绝对 JSON 文件路径。

```sh
./gradlew --no-daemon integrationClasses :worker:installDist
runtime="$PWD/worker/build/install/unidbg-worker"
evidence="$PWD/build/evidence/package-profile-$(date -u +%Y%m%dT%H%M%S)"
java -cp "$PWD/build/classes/java/integration:$runtime/lib/*" \
  -Dintegration.runtime="$runtime" \
  -Dintegration.fixtureClasspath="$PWD/build/classes/java/integration" \
  -Dintegration.evidenceDir="$evidence" \
  -Dintegration.packageProfile="${PACKAGE_PROFILE:?}" \
  -Dintegration.packageOracle="${PACKAGE_ORACLE:?}" \
  org.junit.runner.JUnitCore org.example.unidbg.PackageProfileIntegrationTest
```

测试通过真实 ARM64 JNIEnv 表调用 `getPackageInfo`、`Signature.toByteArray` 和权限字段读取，完整比较查询结果与 oracle。`pm-snapshot.json`、`effective-request.json` 和 worker 日志保存在该次证据目录。
