# 包来源与 guest 文件路径

`profile.package.apk` 是宿主 Application 的物理 APK。`profile.package.guestSourceDir` 是 Android 可见的来源路径，同时作为 native APK 挂载点；提供该字段时必须同时提供 `apk`。

早期 lifecycle plugin 在 `onSetupApplicationState` 中调用 `RuntimeEnvironment.setApplicationPackageSource(new ApplicationPackageSource(physicalApk, guestSourceDir))`。core 在 Application 附着前把可见路径写入 Package 与 ApplicationInfo，并沿原 LoadedApk 初始化流程建立身份。未提供 `apk` 时该调用传入 null，`guestSourceDir` 不生效。

## Java 侧身份

`Application.getPackageCodePath()`、`getPackageResourcePath()`、`ApplicationInfo.sourceDir`、`ApplicationInfo.publicSourceDir` 与 `PackageManager` 查询返回的对应字段使用同一可见路径。`ApkAssets.getAssetPath()` 保留该可见身份。

API 30 的 Java binary resources 在实际打开 ZIP 时解析到物理 APK，资源缓存按物理来源区分。

未提供 `guestSourceDir` 时框架使用物理来源路径。验收范围为 API 30 的 Java binary resources；其他 SDK 与 native resource mode 不在验收范围内。

## native 挂载

`GuestFiles` 实现底座 `IOResolver`，把 guest 路径映射到宿主 backing：

| guest 路径 | 宿主 backing |
| --- | --- |
| `filesystem.guestDataDirectory`（缺省 `/data/user/0/<packageName>`） | `Application.getDataDir()` |
| `Application.getDataDir()` 的字符串形式 | 同一数据目录 |
| `package.guestSourceDir` | `package.apk` |

映射同时作用于 `resolve`、`queryMetadata` 与 `queryDirectory`。metadata 与目录查询使用底座 `HostFileFacts` 提供完整事实，不调用 resolve、open、fstat 或 getdents。

安装挂载时，库用 emulator rootfs 的 `FileSystem.mkdir` 创建缺失的挂载祖先，并保留已存在节点及其属性。新增祖先属于挂载声明构建的虚拟命名空间，其属性来自 rootfs backing，不代表设备目录事实。挂载根及后代使用宿主 backing，权限检查由底座执行。

附加包不创建文件挂载。

## java.io.File 查询

`configureVm` 为 `java.io.File` 安装精确 JNI handler：`getPath`、`exists`、`canRead`、`canWrite`、`canExecute`、`isFile`、`isDirectory`、`length`、`lastModified`、`list`、`listFiles`。

查询经该 VM 的 `AndroidSyscallHandler.queryMetadataAt`、`checkAccessAt`、`listDirectoryAt`，路径解析、权限判断与 backing 选择由底座负责。`getPath()` 把已声明的 Application 数据路径投影为 `guestDataDirectory`；`listFiles()` 返回同一 guest 目录下的 `File` 对象。

底座确认的 errno 按 Java File 返回约定转换为 false、0 或 null。所需事实未知或字段缺失时抛 `UnsupportedOperationException`，进入既有的 FAILED 观察与入口异常边界。其他 `java.io.File` 成员与构造器保持默认 Proxy 行为；宿主 Java 回调直接使用 JDK `File` 时访问宿主文件系统。

## 范围

基础映射覆盖实际 open、read、write、metadata 与目录查询路径。完整设备文件系统、全部 syscall 与 `/data/app` 身份不在该机制内，由工作入口通过 native resolver 与 rootfs 配置决定。
