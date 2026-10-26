# 正式运行库与调用项目迁移

## 目标

将现有 PoC 提供的 Android Java 环境桥接能力做成正式库。DroidGuard 与 TikTok 统一使用该库。交付包含正式源码与本地制品验证。经用户授权，协调者向公开的 ai-repo-save/robolectric、ai-repo-save/unidbg-robolectric 及既有 unidbg 远端推送固定提交，供 CI 从 HTTPS 构建；不部署服务或发布容器。

## 执行边界

- JDK 21。先支持已实测的 Android 11 / API 30，native resolver API 独立配置。
- 一个完整分析会话对应一个工作 JVM。JNI、原生指针、回调、调试器和全部业务步骤留在该进程。
- 持久入口从启动至 close / EOF 共用同一会话，无默认空闲超时。不建立工作进程池。
- 分发启动器直接执行工作 JVM。Java client 直接创建工作子进程，不增加中间 JVM。
- 官方 Robolectric runner 完成初始化和清理；业务入口在 sandbox 内按 FQCN 重新加载。跨进程及 bootstrap 边界只传 JSON、路径和标量，不传活对象。
- native-only 模式不初始化 Robolectric。CPU、ELF、JNI_OnLoad 和自定义 syscall 策略仍由调用项目决定。
- APK 保留 unidbg 元数据、签名、assets 和原生组件来源职责。Android 包身份通过明确配置建立，不承诺执行 APK DEX。实际业务需要的资源能力须沿公开入口验证后接入。

## 制品

- fork 坐标：`io.github.ai-repo-save.unidbg:*:0.10.1`。正式修改 reactor 坐标和自依赖，保留 Java 包名与 native JNI 名称；DvmClass/ProxyDvmClass 的正式 Jni 接口扩展承载 JDK Proxy 组合。
- 唯一 Robolectric core：`io.github.ai-repo-save.robolectric:robolectric:1.0.0`，官方支持模块保持 4.17。在原 Package/进程绑定初始化处提供公开 package/process 配置，不编译替代 resource APK。UID 使用官方早期 lifecycle plugin。
- 新库坐标：`io.github.ai-repo-save:unidbg-robolectric-{client,spi,worker}:1.0.1`。
- client 负责进程生命周期与数据结果；SPI 负责工作进程内入口、资源、JNI 组合；worker 负责官方 Android 引导、环境投影和离线发行。
- worker distribution 集中包含正式依赖、AndroidX AAR 的 classes.jar、预插桩 SDK、来源与许可证。manifest 记录版本、源码 Git 提交、坐标、JDK/API 与配置路径等构建事实。运行时不下载 SDK；依赖下载由 Gradle/Maven 及其自身机制负责。
- 提供标准 Maven-layout 本地仓库与一份 worker 安装。默认 releaseLocal 只发布必要 JAR 并安装，不将 runtime ZIP 绑定到 Maven publication；distZip/distTar 仅由使用者显式请求。Docker 显式接收本地制品 context，构建与运行均使用 JDK 21。
- 远端 CI 从公开仓库的固定提交构建正式制品，不依赖本机 Maven 缓存、私有 token 或大型二进制入 Git。不关闭 CI；远端源码可达与本地镜像验证分别记录。

## 公共合同

`RuntimeClient.start(RunRequest)` 创建完整会话。请求包含 runtime 安装目录、入口 FQCN、任务 classpath、JSON 参数、模式、profile、cwd、产物目录和取消预算。返回的 handle 负责等待、关闭、取消与持久通道。

`WorkerEntrypoint.run(WorkerSession, JsonNode)` 在工作 JVM 内执行整个任务。会话提供当前配置、资源登记、Android 上下文访问、通道与取消检查。资源逆序关闭，保留关闭异常；最终成功结果在资源和 Android teardown 完成后发布。

通用 JNI 接线使用正式 ProxyClassFactory / ProxyDvmClass 扩展：观察器包围调用，按完整描述符注册的业务 handler 优先，再使用明确注册的 business owner class-scoped delegate，显式 proxyOwner 的已知 Java 实现与普通 Android / Java 的公开接口和父类成员调用转 Proxy，未知业务调用明确失败。proxyOwner 必须能在当前工作类加载器加载，不要求消费者创建 ProxyClassLoader。owner delegate 不允许全局 catch-all 或覆盖 Android / Java 命名空间；它自行处理已知业务类的具体成员。acceptMethod / acceptField 元数据查询由组合层返回是否接受，不执行结果 handler。消费者移除反射修改 VM classMap / methodMap / jni 的接线。

环境配置先覆盖实测基础能力和消费者实际需要的字段，不预造完整设备模型。Build、Settings、包、偏好及 native properties 的共同事实来自同一输入。guest 路径和宿主 Application 数据目录分别表示，通过明确映射共享文件。业务侧使用公开配置扩展接入其它服务；不得再定义同名 android.* 桩。

DroidGuard 的确定性 crypto、软件 AndroidKeyStore、业务 callbacks、原始 native fixtures，以及 TikTok 的 MS/TTNet/组件业务 JNI 仍归消费者。库不把这些输入固化成 Android 默认行为。

stdout 保留机器结果或业务持久协议；运行日志走 stderr 和持久 JSON 文件，原始诊断独立保存。失败保留堆栈与阶段，取消回收整个会话子树；强杀记录未完成状态，不宣称 finally 已执行。

## 岗位与顺序

1. 底座发行岗位修改并提交 unidbg reactor，发行本地稳定制品。
2. 运行库岗位提交可编译公共合同并通知两个接入岗位，完成库与发行实现。
3. DroidGuard 和 TikTok 岗位各自独占对应仓库，迁移全部现存会话创建入口，删除被正式 SDK 替代的桩和桥接补丁。保留算法和原始输入职责。
4. 接入所需公开 API 缺口由运行库岗位集中处理，不在消费者复制运行库或私有反射。
5. 提交后由独立 reviewer 审查指定提交，实施岗位裁定有效问题并独立提交修复。

## 验证

集成验证只通过显式任务执行，不加入默认 check。使用稳定发行制品，不以相邻源码或旧 SNAPSHOT 代替。

- 库：ARM64 JNI 操作四类 Android API；Java/native 配置与文件映射；业务优先和观察通道；同会话多步骤；独立工作 JVM 隔离；native-only；失败和取消后的进程及资源回收；离线发行。
- DroidGuard：既有精确 SO / bytecode 的 init、ss、he、close 和实际产物；同一会话内多次调用；HostLinker 的持久请求与关闭。
- TikTok：04/08/09 原生算法定向验证；现有 tiktok-pro/43.9.3 输入的原版和补丁 sscronet 会话，共用自签 HTTPS endpoint，保留决定性返回 1 / 0；本地 Docker 真实链路。
- 不下载或替换缺失的旧 APK 来制造通过；不改算法断言或无关历史 expected。实测暴露的迁移缺口按实际责任修复。
