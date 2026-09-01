# 一、架构总决策

完全符合

# 二、为什么不是其他架构

- 原需求：不采用全局 Redux/MVI Store，每个页面或流程拥有自己的 ViewModel 和 `UiState`。实际行为：虽然没有引入 Redux/MVI 第三方 Store，数据库仍是权威源，但生产代码基本只有一个 `AppRootViewModel`；该类集中持有记账、流水、账户、预算、分析、转移、恢复等大量 feature 的状态与动作，并非每个页面或流程各自拥有 ViewModel。

# 三、系统上下文

完全符合

# 四、Gradle 模块架构

完全符合

# 五、运行时架构

完全符合

# 六、账务内核架构

- 原需求：`JournalEntry` 和 `Posting` 一经提交后永不修改、永不删除。实际行为：常规编辑、删除和恢复确实通过追加冲销/替换实现，且数据库触发器拒绝更新；但 `SecureFinancialFactPurgeAccess` 可在维护态打开 `_schema_runtime_guard.allow_fact_purge`，`RoomPrivacyPurgeWriter` 随后会物理删除满足保留期及净额归零条件的整条交易修订、分录和 posting 链，因此并非“永不删除”。
- 原需求：每个 posting 至少保存 `valuationSource`。实际行为：领域 `Posting` 与数据库 `posting` 表仅保存 `valuationRate`，没有估值来源字段；估值来源无法由单条 posting 自身审计还原。
- 原需求：`BudgetEffectLine.kind` 支持 `USE / RESTORE / ADJUST`。实际行为：`BudgetEffectKind` 只有 `USE` 和 `RESTORE`；预算调整由独立的 `budget_adjustment` 事实表表达，不是 `BudgetEffectLine` 的 `ADJUST` 类型。

# 七、统一财务写入入口

完全符合

# 八、Commit、版本和合并恢复

- 原需求：所有被修改的实体都记录 `lastCommitId`、`rowVersion`、`contentHash`。实际行为：这些字段没有统一存在于所有当前实体表；例如 `payment_card` 有 `last_commit_id` 与 `row_version` 但没有 `content_hash`，`project`/`goal` 有 `last_commit_id` 与 `row_version` 但没有 `content_hash`，`participant` 与 `settlement_activity` 只有 `last_commit_id`，缺少 `row_version` 和 `content_hash`。实现另以 `entity_revision` 保存修订哈希，但不等同于每个当前实体自身完整记录三项字段。

# 九、主数据库架构

完全符合

# 十、投影与轻量 CQRS

完全符合

# 十一、搜索和自定义分析架构

完全符合

# 十二、附件对象库

完全符合

# 十三、后台任务架构

完全符合

# 十四、大型导入与批量操作

完全符合

# 十五、备份架构

- 原需求：恢复完成后保留恢复前安全快照，只有用户确认后才允许清理旧数据。实际行为：替换恢复成功后，`ReplaceRestoreCoordinator` 在 `finalizeExchange` 成功后结束流程，并在 `finally` 中调用 `ledger.cleanup(operationId)`；合并恢复成功路径也立即调用 `restoreLedger.cleanup(operationId)`。`SecureRoomRestoreLedgerApplicationPort.cleanup` 会直接删除 `ledger_safety_<operationId>.db` 及相关旧制品，源码中没有等待用户确认后再清理的保留阶段。

# 十六、安全密钥架构

完全符合

# 十七、UI和导航架构

- 原需求：每个屏幕定义自己的 `ScreenUiState` 与 `sealed interface ScreenAction`，由对应 ViewModel 接收动作并产出状态。实际行为：多数 feature 没有独立 ViewModel，状态和业务动作集中在 `AppRootViewModel`；feature 层普遍以包含大量回调字段的 `data class ...Actions`（如 `OrdinaryRecordActions`、`JournalActions`、`AccountsActions`）连接根 ViewModel，也没有为每个屏幕定义 sealed Action 类型。

# 十八、小组件架构

完全符合

# 十九、错误和故障恢复

完全符合

# 二十、性能架构

- 原需求：SQLite `SUM` 可能溢出的金额查询必须使用安全汇总层、分块后以 `BigInteger` 累加，或明确返回数值范围错误。实际行为：分析与投影源码存在多处直接对 `Long` 金额列执行 SQLite `SUM(...)`（包括从 `economic_effect`、`posting` 和日/月汇总表聚合）；`analytics:domain` 没有数值范围错误类型，`SecureRoomAnalyticsApplicationPort` 的通用异常路径将此类数据库算术失败映射为 `AnalyticsError.DatabaseUnavailable`，未明确返回数值范围错误，也未对这些查询采用分块 `BigInteger` 累加。

# 二十一、测试架构

- 原需求：每个正式 schema 版本的数据库契约测试都要覆盖从所有旧版本升级，并在迁移后验证 journal 平衡、投影可重建、FTS 和 R*Tree 可查询。实际行为：当前 schema 版本为 4，源码虽注册了 1→2、2→3、3→4 且禁止 destructive migration，但真正打开 SQLCipher 数据库的迁移测试只执行 v1→v4，没有分别从 v2 和 v3 起点升级；该迁移用例仅保留一条 book 数据并检查整体完整性与投影状态行数，没有迁移后 journal 平衡、投影重建、FTS 查询和 R*Tree 查询的专项断言。
- 原需求：故障注入必须覆盖“数据库提交后进程立即终止”和“应用更新期间 projection 版本变化”。实际行为：现有测试覆盖了提交各阶段异常回滚、恢复交换进程死亡、附件中断及其他列明故障，但没有找到在账务数据库已成功提交的瞬间模拟进程终止并于重启后验证结果的测试，也没有找到在应用更新/数据库升级中改变 projection 版本并验证重建或恢复的故障注入测试。

# 二十二、冻结的架构决策记录

- 原需求：ADR-003 采用“当前状态＋不可变修订＋不可变财务日志”。实际行为：常规业务路径按追加修订和财务事实实现，但维护态隐私清理会开启 `allow_fact_purge`，并物理删除已关闭交易的修订、journal、posting 及其他财务事实，因此修订与财务日志并非无条件不可变。
- 原需求：ADR-007 规定 Journal 和 Posting 只追加、不更新、不删除。实际行为：数据库触发器默认禁止更新和删除，但 `SecureFinancialFactPurgeAccess` 与 `RoomPrivacyPurgeWriter` 提供了经维护门控的 journal/posting 物理删除路径，与冻结决策中的“不删除”不符。
