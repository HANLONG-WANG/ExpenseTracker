# 云端办理阶段 0：实现与待验记录

日期：2026-11-02。对应[已批准的总计划](cloud-command-plan.md)。阶段 0 已完成设备验收，关键假设成立，可以进入阶段 1。初次实现时未运行 Gradle、adb 或模拟器；后续验收结果和耗时见文末。

## 锁顺序与文件边界

全局顺序为 `mediaWrites → 数据库事务`。持锁事务内调用 `internal` 核心，不再次获取非可重入的媒体锁；调用约定用注释说明，没有新增运行时防御。

全仓检索 `mediaWrites`、`Mutex`、`withLock`、显式 `lock/unlock`、`synchronized` 并核对调用方：生产代码中媒体锁共 5 处，均为先锁后事务，未发现现有的事务内反向获取媒体锁路径。

| 公开入口 | 拆出的核心 | 行为边界 |
|---|---|---|
| `commitBookOperation` | `commitBookOperationInTransaction` | 保留范围比对、恢复期限复核和全部操作分支 |
| `commitEmptyBookTrash` | `commitEmptyBookTrashInTransaction` | 保留整批预览比对与清空行为 |
| `collectUnusedMedia` | `collectUnusedMediaInTransaction` | 保留引用检查及物理文件删除；文件删除不能随数据库回滚，不用于试运行 |
| `importMediaResult` | `commitImportedMediaInTransaction` | 只抽出 blob/草稿引用登记；文件准备、每文件事务及异常清理边界不变，完整导入须在外层事务之外调用 |
| `BackupService.export` | `exportSnapshotInTransaction` | 抽出数据库、附件和偏好快照；保持锁与事务覆盖范围，生成的文件不能随主库回滚 |

所有公开签名和既有调用方不变。另有 `MediaFlowTest` 的显式媒体锁，用于测试导入等待，没有反向事务嵌套。

其他锁也已核对：`AppViewModel.writes`、`FinancialDraftSession.writes`、`CloudRepository` 任务锁存在先锁后业务事务的编排；这些锁不属于 `LedgerRepository` 的事务内业务核心，也没有被这些核心反向获取，故未扩展改造到 UI 会话或云任务网络/文件工作流。`ReminderCoordinator`、汇率、云连接存储和恢复文件锁亦未发现反向获取媒体锁的路径。

## 仪器测试

新增 `CommandSandboxTest.kt`，使用独立磁盘 Room 数据库、固定时钟和独立附件目录；业务数据均由现有 Repository 函数写入，不直接写 SQL。

| 规格项 | 方法及证据 |
|---|---|
| 1、3：前序写入可见、嵌套回滚 | `laterStepsReadEarlierWritesAndNestedTransactionsRollBack`：外层事务中调用 `saveAccount → previewEntry → commitEntryReview`，从 `snapshot.first()` 和 DAO `graph.first()` 检查账户、记录、余额、结算、posting 和修订投影。测试专用异常只在最外层事务外捕获，随后核对对象不存在且完整 graph 恢复原值 |
| 2：回滚无可见失效 | `rollbackDoesNotInvalidateButRealCommitDoes`：先注册 Room 原始失效观察者，执行同一业务链并回滚，等待 500ms 断言没有回调；随后真实提交，5 秒内必须收到包含 accounts/events 的通知。观察表未被前置建账本/分类写入，避免前置写入的延迟通知干扰；不使用去重过滤 |
| 4：锁顺序 | `bookCoreAndConcurrentMediaCleanupCompleteWithGlobalLockOrder`：外层持媒体锁和事务；另一个协程调用公开 `collectUnusedMedia` 并确定已挂起等待锁，随后调用归档核心。15 秒超时保护，断言归档与孤立文件清理均完成 |
| 5：耗时 | `fiftyCategoryEditsOnLargeLedgerReportRollbackTiming`：参照 `LargeLedgerTest`，通过 `csvPreview/importCsv` 导入跨约三年的 3,700 条虚构记录；外层持锁事务中对 50 条不同支出执行 `previewEntry → commitEntryReview` 改分类并回滚。检查只改变分类/修订及最终 graph 还原；`Log.i("CommandSandbox", ...)` 输出总毫秒和每步平均毫秒，无耗时断言 |

没有额外边界测试。第 5 项默认运行，不需要性能参数。计时包含加锁、事务、50 步预览/提交和回滚，不包含建账本与 CSV 导入。

## 验收结果（2026-11-02）

- 单元测试、`assembleDebug`、`assembleDebugAndroidTest`：通过。
- `CommandSandboxTest` 4 项全部通过（模拟器 ExpenseTracker_API_36，API 36，x86_64）。
- 回归 `BookLifecycleStorageTest`、`MediaStorageTest`、`BackupRoundTripTest`、`MediaFlowTest`、`BookManagementFlowTest` 共 22 项通过。
- 耗时：3,700 笔账本上 50 步 `previewEntry → commitEntryReview` 改分类加回滚，总计约 8,129 ms，平均每步约 163 ms。逐条调用现有入口时，每步都会重新读取整本账本，批量命令不能这样实现，阶段 1 必须改为批量准备，并设定性能目标。
- 结论：关键假设成立，可以进入阶段 1。
