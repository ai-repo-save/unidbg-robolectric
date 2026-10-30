# Worker CLI

启动脚本为安装目录下的 `bin/unidbg-worker`，主类 `io.github.aireposave.unidbg.runtime.worker.WorkerMain`。`--version` 输出 runtime 版本（`RuntimeVersion.VERSION`），协议版本为 `RuntimeVersion.PROTOCOL`。

## 选项

| 选项 | 参数 | 语义 |
| --- | --- | --- |
| `--request` | 路径 | 完整请求 JSON；提供时其余选项全部忽略 |
| `--mode` | `ANDROID` 或 `NATIVE_ONLY` | 会话模式，大小写敏感 |
| `--entrypoint` | FQCN | `WorkerEntrypoint` 实现 |
| `--arguments` | 路径 | 入口参数 JSON，缺省为空对象 |
| `--profile` | 路径 | profile JSON，缺省为空对象 |
| `--output-dir` | 路径 | 会话输出目录 |
| `--task-classpath` | 列表 | 任务 JAR 与目录，用宿主 `File.pathSeparator` 分隔 |
| `--runtime-dir` | 路径 | runtime 安装目录；缺省取本类所在 JAR 的上两级目录，非安装布局时必须显式提供 |
| `--trace-id` | 字符串 | 关联标识，缺省生成 32 位十六进制 |
| `-h`、`--help` | 无 | 用法说明 |
| `-V`、`--version` | 无 | 版本 |

省略 `--request` 时，`--mode`、`--entrypoint`、`--output-dir` 为必需项。CLI 写入 `request.json`，删除输出目录中旧的 `cancel.request` 与 `result.json`，再按该文件执行。

退出码：`COMPLETED` 为 0，`CANCELLED` 为 130，其余为 1，参数用法错误由 picocli 返回 2。`--help` 与 `--version` 在创建请求与目录之前返回。

## 请求

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `protocol` | integer | 协议版本，仅记录 |
| `version` | string | runtime 版本，仅记录 |
| `sessionId` | string | 会话标识 |
| `traceId` | string | 关联标识，写入日志 MDC |
| `mode` | string | `ANDROID` 或 `NATIVE_ONLY` |
| `entrypoint` | string | 入口 FQCN |
| `arguments` | object | 入口参数 |
| `profile` | object | 环境 profile |
| `outputDirectory` | string | 会话输出目录 |
| `runtimeDirectory` | string | runtime 安装目录 |
| `classpath` | array | 追加到执行域 classpath 的路径 |

`mode` 为其他值时请求失败。`ANDROID` 模式要求 `profile.androidSdk` 为 30，并要求 `runtime-manifest.json` 记录的 SDK 文件存在。

## 结果

`result.json` 由 `result.pending.json` 原子移动生成：

| 字段 | 条件 | 语义 |
| --- | --- | --- |
| `version` | 总有 | runtime 版本 |
| `pid` | 总有 | 工作进程 PID |
| `status` | 总有 | `COMPLETED`、`FAILED`、`CANCELLED` |
| `payload` | 成功 | 入口返回的 JSON，同时写入 `payload.json` |
| `failure` | 失败 | `type`、`message`、`stack` |

失败判定同时考虑 `cancel.request` 是否存在以及异常是否为 `InterruptedException`。

## 会话目录

| 路径 | 写入方 | 内容 |
| --- | --- | --- |
| `request.json` | 调用方或 CLI | 请求信封 |
| `effective-request.json` | worker | 解析 APK 后的 Android profile |
| `payload.json` | worker | 入口返回值 |
| `result.json`、`result.pending.json` | worker | 结果 |
| `protocol.jsonl` | worker | `session.output()` 的持久副本 |
| `logs/worker.jsonl` | worker | 结构化日志 |
| `logs/stdout.log` | worker | 工作进程 stdout |
| `logs/stderr.log` | client | 工作进程 stderr |
| `host-proc-self-maps.txt` | worker | 工作 JVM 的 `/proc/self/maps` 快照 |
| `application-data/` | worker | Application 数据目录副本 |
| `application-data.location` | worker | 被复制数据目录的原始路径 |
| `cancel.request` | 调用方 | 取消信号 |
| `cancellation.json` | client | 取消摘要 |
| `aborted.json` | client | 收到进程信号时的取消摘要 |

`session.output()` 同时写入工作进程 stdout 与 `protocol.jsonl`。消息格式由入口定义，库只提供通道。入口使用 `input()` 读取请求流，使用 `output()` 写机器结果。

## 持久通道

入口在 `run` 内处理完整 stdin/stdout 协议，直到 close 或 EOF 才返回，没有默认空闲超时。client 侧对应 `SessionHandle.send`、`receive`、`endInput`；`receive` 在 EOF 时返回 `NullNode`。

取消由 `cancel.request` 文件触发。入口在 `checkCancelled()` 处响应；库在入口调用前后各检查一次。client 在 `cancellationGrace` 内等待根进程退出，之后先向子进程、再向根进程发送 TERM 并等待 `terminationWait`；仍在运行的进程收到 KILL，并获得新的 `terminationWait` 预算。取消摘要记录阶段、预算、PID 与被信号进程，`finallyConfirmed` 恒为 false，因为 TERM 与 KILL 不能证明 JVM finally 已执行。

复用输出目录时先删除旧的 `cancel.request`。`RuntimeClient.start` 与直接 CLI 都执行该清理；`--request` 方式直接使用给定请求文件，输出目录由调用方维护。
