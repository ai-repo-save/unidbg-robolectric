# 日志与事件

worker 使用 Logback 与 Logstash encoder，事件为 JSON lines，写入两个 appender：stderr 与 `logs/worker.jsonl`（追加写入）。日志级别为 INFO。

每条记录的字段来自 encoder 与 MDC：`@timestamp`、`level`、`logger_name`、`thread_name`、`message`，以及 `trace_id`、`session_id`。事件名与事件字段在 `message` 中，格式为 `event=<name>` 后接空格分隔的 `key=value`。

工作进程 stdout 写入 `logs/stdout.log`，不进入事件流。client 把工作进程 stderr 重定向到 `logs/stderr.log`。

## 事件

| 事件 | 级别 | 内容 |
| --- | --- | --- |
| `worker_started` | INFO | 完整请求 JSON 与执行 classloader |
| `application_metadata_resolved` | INFO | 解析后的包名、版本与 `effective-request.json` 路径 |
| `session_started` | INFO | 模式、包名、进程名、UID、启动耗时与 session classloader |
| `session_resources_closed` | INFO | 资源关闭是否全部成功 |
| `session_closed` | INFO | 入口返回或异常后离开会话 |
| `worker_completed` | INFO | 入口返回 |
| `worker_failed` | ERROR | 异常对象，含 `stack_trace` |
| `worker_closed` | INFO | 结果发布前的收尾 |
| `apk_signatures_verified` | INFO | APK 路径、各签名方案结果、`GET_SIGNATURES` 证书数、lineage 存在性 |
| `apk_signature_error` | ERROR | 验证器的错误，含方案与 signer 序号 |
| `apk_signature_warning` | WARN | 验证器的警告，含方案与 signer 序号 |
| `package_profile_installed` | INFO | 包名、来源字段、签名数、权限数 |
| `emulator_configured` | INFO | backend 类名、classloader 与 native 属性映射 |
| `guest_credentials_configured` | INFO | uid、euid、gid、egid 与补充组 |
| `stdin_source_bound` | INFO | 会话、emulator、guest 路径与宿主描述符 |
| `file_mount_configured` | INFO | guest 路径、宿主路径与来源 |
| `file_mount_ancestor_created` | INFO | 新建的挂载祖先、rootfs、mode、uid、gid |
| `file_mount_ancestor_retained` | DEBUG | 已存在的挂载祖先及其查询状态 |
| `guest_file_query` | DEBUG | `java.io.File` 查询的描述符、路径与结果；按访问检查、元数据、目录、errno 四种形态 |
| `system_features_projected` | INFO | 功能条目数 |
| `system_available_features_projected` | INFO | 条目数 |
| `system_shared_library_names_projected` | INFO | 名称数 |
| `memory_profile_projected` | INFO | `totalMem` |
| `display_profile_projected` | INFO | 显示 ID |
| `default_sensors_projected` | INFO | 传感器数 |
| `application_data_preserved` | INFO | 数据目录来源、证据目标与复制文件数 |

Android 会话在签名验证、包投影、凭据、挂载与每个 services 投影处各产生一个事件。native-only 会话只产生 worker、会话与 emulator 相关事件。root 级别为 INFO，`DEBUG` 级事件（`guest_file_query`、`file_mount_ancestor_retained`）在默认配置下不输出；排查时需调整 `logback.xml` 的级别。

## 失败

运行期失败由 worker 边界捕获：`worker_failed` 记录异常类型、消息与堆栈，`result.json` 的 `failure` 字段保存同一信息。字段校验、APK 派生、签名验证与框架调用失败都进入该路径。

协作取消使 `checkCancelled()` 抛 `InterruptedException`，由 worker 边界记录 `worker_failed`，`result.json` 的 `status` 为 `CANCELLED`，退出码为 130。被强制终止的工作进程不写 `result.json`，其状态由 client 的取消标志判定。

## 证据

会话目录中的证据文件与取消摘要见 `docs/worker-cli.md`。集成测试的日志保存在各自证据目录的会话子目录中。
