# ModelDesign 修复进度

> 依据 `ModelDesign.md` 修复；不修改审查结果原文件。

## 已完成实现

### 2026-08-20：自动化正式写命令的命令级幂等

- `AutomationMutationIds` 已增加强类型 `CommandId`，并与提交、实体修订、设备等标识保持互异。
- 保存快捷模板、保存周期规则、修改单次/后续/整个周期现在都会把 `command_uid` 写入 `book_commit`，并在同一数据库事务中写入完整 `command_receipt`。
- 三类 API 现在返回 `CommandReceipt`；同一命令重放时会在版本检查之前返回首次回执，不增加 Book revision，也不重复创建修订或变更。
- 相同 `CommandId` 携带不同命令类型或载荷时返回 `DuplicateCommandPayloadMismatch`。
- 已补设备测试覆盖首次回执重放，以及三种周期修改范围重放不重复落库。

### 2026-08-20：贷款还款与换汇的条件分类约束

- `AccountingRuleEngine.loanPayment` 仅在还款包含利息、手续费或罚息费用成分时接受分类；仅偿还本金而携带分类会被领域 Planner 拒绝。
- `AccountingRuleEngine.fxExchange` 现在读取并校验分类；只有形成汇兑收益，或存在显式点差成本费用时才允许分类，零差额及仅形成权益差额的兑换携带分类都会被拒绝。
- 已补领域测试同时覆盖“仅本金还款带分类”和“零差额换汇带分类”两条拒绝路径。

### 2026-08-20：启动、备份与恢复共用的完整账本审计

- `DatabaseIntegrityAudit` 已扩展为完整的数据库侧审计：除 SQLite 完整性、外键、Journal 平衡和当前子类型外，还验证 Posting/账户/Book 币种、Active 当前未冲销效果链、Trashed 全事实族及分配记录零当前效果。
- 新增 `RoomLedgerIntegrityAudit`，在 SAVEPOINT 内从权威事实执行全投影重建，比较重建前后 canonical Hash，并同时检查 15 个投影族的版本与行数一致性；校验不会持久化重建结果。
- 启动维护检查、备份影子库校验、替换恢复与合并恢复现在共用上述完整审计，任何数据库不变量、投影重建/Hash 或标准清单不一致都会阻止就绪、备份交换或恢复完成。
- 前台解锁与后台周期执行实际创建的 `BookSessionManager` 都已安装 `RoomLedgerStartupInspector`；权威事实/标准清单失败进入恢复态，只有 `INV-031/035` 或重建 Hash 所代表的投影问题进入可修复维护态。
- 领域层新增固定的 `PermanentInvariant` 清单，严格包含 `INV-001` 至 `INV-035`；数据库审计、恢复报告和领域测试共同校验同一 35 条标识，避免三处标准漂移。
- Trashed 零效果审计覆盖 Journal、economic/budget/project/goal/statement/loan/settlement effect、退款/信用卡/分期/贷款分配、互请付款记录和目标资金移动。
- 数据库审计同时处理显式超额退款 override、外部参与人结算不触碰本地账户、Goal 独立移动无 Journal、内部转账净资产、贷款成本金额守恒、单次冲销，以及 occurrence 物理唯一索引等边界；只要 Book 存在就执行全套规则，不再因 head 异常跳过。

### 2026-08-20：逐版本迁移后验证与旧备份恢复

- v1→v2、v2→v3、v3→v4 每个 `Migration` 都在迁移事务提交前执行 `MigrationPostValidation`；失败会直接回滚迁移。
- 每一步校验 SQLCipher 可读性、`integrity_check`、外键、Journal、交易子类型、35 条数据库不变量、schema registry/contract、投影 generation、FTS5、两个 R*Tree 以及代表性 keyset 查询的索引计划和 5 秒上限。
- 原 `MigrationTestHelper` 占位测试已替换为三个使用真实 SQLCipher factory 的逐步迁移测试；前驱数据库包含 genesis head、运行时保护表和完整不可变触发器，每一步都确认实际执行了完整 35 条审计，而不是按未初始化账本跳过。
- 新增 v1、v2、v3 三种加密旧备份文件复制/恢复测试；每个旧版本均通过生产 `openLedgerCopy` 迁移到当前 v4，并验证 Book 数据保留、15 个投影 generation 和完整迁移后报告。
- 中间 schema 在迁移事务内完成事实、结构与可查询性验证；升至当前 schema 后，实际前台/后台启动入口继续执行基于权威事实的全投影重建与 canonical Hash 比较，形成迁移后两层闭环。

## 验证结果

- 未使用 Gradle、Gradle Wrapper 或 `agent-device`。
- 使用缓存依赖直接进行 Kotlin K2 编译：完整 `core/database`、迁移设备测试源、领域/应用变更、自动化数据端、完整审计/启动维护端、恢复端及后台周期入口均通过编译。
- 全部领域测试源通过编译；新增分类约束测试与 35 条永久不变量清单测试已实际执行通过。
- 从真实 v1→v4 schema 资产构造 SQLite 数据库，执行审计运行时捕获的 104 条不同 SQL，全部通过语法与表/列解析验证。
- `ModelDesign.md` 保持未修改；`git diff --check` 通过。

## 待完成

- 实现修复待办：无。受明确限制未启动 Android instrumentation；迁移测试源已静态编译，其他新增设备断言保留为后续设备门禁。
