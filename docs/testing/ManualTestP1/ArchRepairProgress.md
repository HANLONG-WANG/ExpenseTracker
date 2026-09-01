# Arch 架构修复进度

> 依据：`Arch.md`。本文件只记录修复进度，不修改审查原文。

## 2026-08-20

### 已完成：恢复前安全快照必须等待用户确认后清理

- 替换恢复和合并恢复成功后不再调用通用 `cleanup` 删除恢复前数据库、旧密钥恢复材料及旧设置/附件制品。
- `cleanup(operationId)` 现在只清理未完成流程的临时制品；检测到已完成交换标记时会保留全部恢复前安全制品。
- 新增 `confirmSafetySnapshotCleanup(operationId)`，只有恢复结果页的显式用户动作会调用它并删除旧制品。
- 已完成交换在进程重启恢复检查中不再被当作中断任务自动清理。
- 恢复结果页显示保留的安全快照 ID 和显式“确认并清理”按钮；直接返回账本不会触发清理。
- 补充设备契约测试断言：普通 cleanup 与进程重启后安全数据库/标记仍存在，显式确认后才删除。
- 补充直编译复核修正了通用 `cleanup` 的完成标记读取签名：现在以 `operationId` 读取交换标记并识别 `FINALIZED`，确保已完成恢复的安全数据库、密钥与附件制品不会因错误参数而越过保留保护。

涉及实现：

- `finance/application/.../RestoreLedgerApplication.kt`
- `finance/data/.../SecureRoomRestoreLedgerApplicationPort.kt`
- `transfer/data/.../RestoreCoordinator.kt`
- `app/.../RestoreController.kt`
- `feature/transfer/.../RestoreFlowScreen.kt`

验证状态：已通过 `git diff --check` 和调用点静态审计；按任务约束未使用 Gradle wrapper，设备测试留待允许的 CI 入口执行。

### 已完成：Journal、Posting 与交易修订无条件不可变

- 删除维护态事实删除端口与 `RoomPrivacyPurgeWriter`，不再存在可打开的 `_schema_runtime_guard.allow_fact_purge` 路径。
- append-only 触发器不再允许任何维护态例外；v5 迁移会移除旧 guard 并重建无条件拒绝更新/删除的触发器。
- 受控清除改为单事务追加无敏感金额/文本的 `purge_tombstone`，随后重建当前投影；原交易、修订、Journal、Posting 和效果事实全部保留。
- Journal 当前列表与详情会排除有获胜 tombstone 的交易；合并恢复导入 tombstone 时同样不再删除历史事实。
- 备份保留期删除改走普通加密数据库写事务；只有备份目录的引用/对象行允许按其独立保留策略删除，不再借用财务事实删除开关。
- 更新设备测试，断言逻辑清除后当前投影消失而交易修订、Journal、Posting 仍存在，并删除旧 guard 断言。

涉及实现：

- `core/database/.../LedgerSchemaDefinition.kt`
- `finance/data/.../RoomLogicalPurgeValidator.kt`
- `finance/data/.../RoomFinancialCommitRepository.kt`
- `finance/data/.../RoomMergeImporter.kt`
- `finance/data/.../SecureRoomControlledPurgeApplicationPort.kt`
- `finance/data/.../SecureRoomJournalApplicationPort.kt`
- `transfer/data/.../SqlCipherBackupCatalog.kt`

验证状态：基础 v5 SQL 建库与 tombstone 视图检查通过，`git diff --check` 通过；完整迁移与故障测试将在本轮后续测试修复项统一补齐。

### 已完成：Posting 保存可审计估值来源

- 领域 `Posting` 新增 `PostingValuationSource`，区分同币种、冻结 FX 证据和由冻结交易估值分配三种来源。
- 创建 Posting 时校验来源与币种/估值率组合一致；冲销和重新应用会保留原始来源。
- `posting` 表 v5 新增非空 `valuation_source` 并按历史币种与估值率回填。
- 规划器、持久化 writer、快照 mapper 与 canonical hash 均纳入该字段，单条 Posting 可自行还原估值来源。
- 不可变事实冲销审计也显式比较 `valuationSource`，防止冲销行在金额和汇率相同的情况下悄然改变估值证据来源。

涉及实现：

- `finance/domain/.../AccountingFacts.kt`
- `finance/domain/.../AccountingRuleEngine.kt`
- `finance/domain/.../CanonicalFinancialHash.kt`
- `finance/data/.../RoomFinancialPlanWriter.kt`
- `finance/data/.../RoomReferenceFinancialSnapshotMapper.kt`
- `core/database/.../ledger_schema_v5_architecture_alignment.sql`

### 已完成：统一预算效果支持 ADJUST

- `BudgetEffectKind` 新增 `ADJUST`，冲销语义保持为 `ADJUST`。
- v5 新增统一 `budget_effect_line` 视图，将交易预算效果与独立 `budget_adjustment` 事实规范化为 `USE / RESTORE / ADJUST` 三类行。
- 预算投影统一从该效果行契约汇总，手工调整不再只存在于无法由 `BudgetEffectLine.kind` 表达的旁路。

涉及实现：

- `finance/domain/.../AccountingFacts.kt`
- `finance/domain/.../DeterministicFinancialPlanner.kt`
- `finance/domain/.../FinancialFactAudit.kt`
- `finance/data/.../RoomProjectionEngine.kt`
- `core/database/.../ledger_schema_v5_architecture_alignment.sql`

### 已完成：当前可修改实体补齐统一版本元数据

- v5 为卡片、分类、商家、地点、项目、目标补齐 `content_hash`，为参与人/结算活动补齐 `row_version` 与 `content_hash`，为蓝图/重复系列补齐三项字段。
- 迁移从每个实体最新不可变 `entity_revision` 回填版本和内容哈希。
- 对没有独立 `entity_revision` 的预算月、预算模板、信用账单、分期与贷款当前行，迁移按 `entity_change`/commit revision 回填三项元数据，避免历史行只保留零值默认值。
- 参考资料、结算与自动化写入端在写入不可变审计行后，同事务同步当前实体的 `last_commit_id`、`row_version`、`content_hash`；不再出现审计修订已更新而当前实体元数据缺失的状态。
- 补充全表审计后，信用账户当前配置 `credit_account_profile` 也补齐 `row_version` 与 `content_hash`；迁移以配置自身 `last_commit_id` 为时间边界，从所属账户的 `entity_change` 历史精确恢复当时版本，后续写入与账户变更使用同一版本号和哈希。自定义分析定义属于 DL-116 明确隔离于 BookCommit 的非财务配置修订，保留其独立 `row_version`/不可变修订契约，不伪造 `last_commit_id`。
- 迁移回归种子为同一账户加入 profile 提交之后的“未来”账户版本，并断言 v1/v2/v3/v4 升级后 profile 仍按自身 `last_commit_id` 得到版本 1 与当时哈希，防止回填错误吸收未来状态。
- 历史 v4 的预算月/模板尚未写 `EntityChange`；v5 回填现会优先用实体变更，缺失时沿当前不可变修订的 `created_commit_id` 读取 `command_receipt.payload_hash`（再以 commit root 作保底），并从修订号恢复 `row_version`。同类回退也覆盖信用账单、分期、贷款、蓝图与重复系列，避免旧账本留下默认零元数据；宿主 SQLite 的 v4→v5 种子已验证预算月得到提交 1、版本 1 和当时 payload hash。
- 所有 v1/v2/v3/v4 SQLCipher 迁移起点的设备契约种子也加入了“没有旧 `EntityChange` 的预算月”，升级后逐一起点断言其三项元数据来自历史 receipt/revision，而不是默认值。
- 补充复核纠正了预算实体变更构造的函数错位：`planGoalMovement` 恢复为空实体变更，`planBudgetMutation` 为预算月与预算模板生成正确的 `EntityChange`，从而让同一 BookCommit 内的三项当前实体元数据同步真正生效。

涉及实现：

- `core/database/.../ledger_schema_v5_architecture_alignment.sql`
- `finance/data/.../SecureRoomReferenceDataManagementPort.kt`
- `finance/data/.../SecureRoomSettlementApplicationPort.kt`
- `finance/data/.../SecureRoomAutomationApplicationPort.kt`

验证状态：v5 基础建库和列存在性检查通过；旧版本起点回填断言将在迁移契约测试项补齐。

### 已完成：金额汇总溢出返回明确数值范围错误

- `analytics:domain` 新增稳定错误码 `ANALYTICS_NUMERIC_RANGE_EXCEEDED`。
- 分析数据库边界不再把 `Math.*Exact` 或 SQLite `SUM` 的 integer-overflow 统一误报为数据库不可用，而是返回上述数值范围错误。
- 数值范围识别只匹配明确的 integer-overflow / out-of-range 原因链；普通 SQLite `datatype mismatch` 仍按数据库/数据故障处理，不会被误报成金额溢出。
- 财务投影提交与投影维护边界同样识别 SQLite integer-overflow，并返回现有 `FinanceDataError.NumericRangeExceeded`；投影失败仍会由外层事务整体回滚。
- finance:data 的流水、逻辑清除、预算、退款、项目/目标、信用、分期、贷款、结算、特殊记账和参考资料查询端口统一复用同一 SQLite 原因链映射，直接查询中的 `SUM` 溢出也不再退化为 `DatabaseUnavailable`。
- 新增 SQLCipher 设备测试，以两个 `Long.MAX_VALUE` 日汇总触发 SQLite 聚合溢出并断言明确错误类型。
- 新增 JVM 原因链测试，锁定包装后的 integer-overflow 仍映射为数值范围错误，同时普通 `datatype mismatch` 不会被误分类。

涉及实现：

- `analytics/domain/.../AnalyticsPorts.kt`
- `analytics/data/.../SecureRoomAnalyticsApplicationPort.kt`
- `finance/data/.../SqlSupport.kt`
- `finance/data/.../RoomFinancialCommitRepository.kt`
- `finance/data/.../RoomProjectionMaintenanceService.kt`

验证状态：错误映射调用链静态审计与 `git diff --check` 通过；设备断言已补充，按任务约束未在本地调用 Gradle wrapper。

### 已完成：正式 schema 迁移覆盖所有旧版本起点与关键数据库能力

- 正式 schema 提升到 v5，并注册连续的 1→2、2→3、3→4、4→5 非破坏迁移及四段迁移契约。
- SQLCipher 迁移设备测试不再只测 v1：同一契约逐一构造 v1、v2、v3、v4 前身并升级到 v5。
- 每个起点升级后均验证：账本数据保留、schema registry/contract hash 正确、15 个投影族存在、Journal 平衡。
- 每个起点升级后实际执行 analytics 投影重建并校验 live/rebuilt hash 一致，同时执行 FTS5 MATCH 与 R*Tree 空间范围查询。
- 新增 v5 Room schema 导出文件，并调整 append-only 触发器断言以区分财务事实与具有独立保留策略的备份目录表。

涉及实现：

- `core/database/.../LedgerMigrations.kt`
- `core/database/.../LedgerSchemaDefinition.kt`
- `core/database/.../EncryptedSchemaV1DeviceTest.kt`
- `core/database/.../MigrationContractTest.kt`
- `core/database/schemas/.../5.json`

验证状态：迁移 SQL 的基础 SQLite 建库/列/视图检查与静态契约检查通过；宿主 SQLite 未编译 FTS5/RTree，因此这两项由新增 SQLCipher 设备测试验证，按任务约束未从本地调用 Gradle wrapper。

### 已完成：补齐提交后进程终止与投影契约升级故障测试

- 新增仅在 SQLite 事务已经成功提交并返回后触发的 `AFTER_DATABASE_COMMIT` 故障点；该点明确位于不可回滚边界之外。
- 新增设备测试模拟该点进程立即终止、关闭全部数据库句柄并重新打开；重启后从 durable `command_receipt` 恢复，同命令重放不产生重复交易、修订、Journal 或 Posting。
- v5 新增 `projection_contract_state`，将投影算法契约版本与账本数据 revision 分开记录；启动检查同时校验两者。
- 全量或增量投影发布会原子更新投影契约版本和 rebuilt revision。
- 新增设备测试模拟应用更新后旧投影契约版本：重启进入 `PROJECTION_VERSION_MISMATCH` 维护态，确定性重建后恢复 READY，且新版本与账本 revision 均正确发布。

涉及实现：

- `finance/data/.../RoomFinancialCommitRepository.kt`
- `finance/data/.../RoomProjectionEngine.kt`
- `finance/data/.../RoomProjectionMaintenanceService.kt`
- `core/database/.../ledger_schema_v5_architecture_alignment.sql`
- `finance/data/.../RoomFinancialDataDeviceTest.kt`

验证状态：故障边界、重启重新打开路径和幂等断言已静态核对，`git diff --check` 通过；按任务约束未在本地调用 Gradle wrapper。

### 已完成：页面/流程独立状态所有权与 sealed 动作契约

- 新增生命周期绑定的页面/流程 ViewModel，分别持有普通记账、退款、特殊交易、流水、预算、项目/目标、信用卡、分期、贷款、结算、自动化、参考资料、安全设置、币种设置与操作中心状态；根 ViewModel 只通过兼容访问器协调跨流程依赖，不再直接创建这些页面的 `MutableStateFlow`。
- 分析、导入、导出、备份、恢复、批量记账与保险库继续由原有独立 Controller 持有状态，未引入全局 Redux/MVI Store。
- 所有 feature 的公共 Compose 边界均改为 typed `sealed interface …ScreenAction` 与单一 `onAction`；账户、普通记账等页面同时使用明确的 `…ScreenUiState`，其他已有 `…UiState`/`…LoadState` 契约保持不变。
- 原 `…Actions` 回调容器不再是公共 feature API，也不再由 `AppRootViewModel`/根 Compose 层构造；仅在各 feature 文件内部把 sealed 用户意图适配给现有私有 Composable，避免 UI 重写造成行为回归。
- UI 契约与 golden fixture 改为无副作用的 sealed-action sink，生产根层用穷尽 `when` 分派，新增动作时编译器会强制处理。
- 最终消费链审计发现组合 `ScreenUiState` 最初仅被暴露、根 Compose 仍分别订阅原始 load/pending 流；现已改为 Ready 壳和各流程目的地直接订阅对应页面 ViewModel 产出的组合 `UiState`，固定动作与页面内容读取同一原子状态快照。

涉及实现：

- `app/.../FeatureScreenViewModels.kt`
- `app/.../AppRootViewModel.kt`
- `app/.../*RootDestination.kt`
- `feature/accounts/.../AccountsContract.kt`
- `feature/journal/.../JournalState.kt`
- `feature/record/.../*Screens.kt`
- `feature/planning/.../*Actions.kt`
- `feature/liabilities/.../*Actions.kt`
- `feature/analysis/.../AnalysisScreens.kt`
- `feature/transfer/.../*FlowScreen.kt`
- `feature/settings/.../*Contract.kt`
- `feature/vault/.../VaultContract.kt`

验证状态：生产源码已无 `data class …Actions` 公共回调契约，根层页面状态 `MutableStateFlow` 已缩减为会话/全局策略状态；`git diff --check` 通过。

### 最终验证汇总

- 使用缓存的 Kotlin 2.4.10 编译器直接编译（未调用 Gradle）通过：finance domain/application/data、analytics domain/data、core database/network 以及本次触及的 security 边界。
- 本轮新增或强化的 `EncryptedSchemaV1DeviceTest`、`RoomFinancialDataDeviceTest`、`AnalyticsSqlCipherDeviceTest` 均已在对应主源码产物上直接编译通过；这证明测试源码和生产接口契约闭合，但不冒充真实设备执行结果。
- `SqlSupportTest` 通过 JUnit 4 直接运行（1 个测试）；预算实体元数据规划测试通过直接编译和调用，覆盖预算月/模板 `EntityChange`，并锁定目标移动不会误带预算实体变更。
- Python 契约测试共 234 个全部通过；P04、P15、P30、P31、P34、P35、P36 专项验证器全部通过。
- 所有改动 Kotlin 文件通过 ktlint 1.7.1；v5 Room schema JSON 解析通过；`git diff --check` 通过。
- 宿主 SQLite 已分别验证空账本完整应用 v1→v5、带历史数据的 v4→v5 回填、`foreign_key_check` 与 `integrity_check`；宿主 SQLite 未提供 FTS5/RTree 模块，因此相关真实查询保留在已直编译通过的 SQLCipher 设备迁移测试中。
- 最终生产扫描确认不存在财务事实物理删除端口或维护豁免，旧 `_schema_runtime_guard` 仅出现在“构造旧版本前身”的迁移测试中；生产 feature API 不再存在 `data class …Actions` 回调容器。
- `docs/testing/ManualTestFindings/Arch.md` 保持零改动。全程未调用 `gradle`、`gradlew`、`./gradlew` 或 `agent-device`。
