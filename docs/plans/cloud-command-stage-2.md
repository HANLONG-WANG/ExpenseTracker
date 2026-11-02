# 云端办理阶段 2：整批撤销与适配器规范

日期：2026-11-02。状态：实现完成，验收修订中。对应[总计划阶段 2](cloud-command-plan.md)；后续命令按[适配器检查表](cloud-command-adapter-checklist.md)验收。

## 实现文件

| 文件 | 变化 |
| --- | --- |
| `data/command/CommandUndo.kt`、`CommandUndoJournal.kt` | 原分类恢复、新建行所有权登记、完整原始行快照、外键/JSON引用检查、受限移除及既有金融快照恢复 |
| `CommandAdapters.kt`、`CommandRegistry.kt`、`CommandModels.kt`、`CommandConfirmation.kt` | inverse 契约、能力清单 undo 语义、执行快照/冲突/将移除模型，展示文案不参与比较 |
| `CommandRepository.kt`、`CommandEntities.kt`、`CommandStorage.kt` | 本机逆向方案复用现有执行器；UNDONE/时间/独立撤销记录；执行 JSON 的原始/压缩格式兼容 |
| `AccountManagementRepository.kt` | 提取共享外键元数据，账户引用计数保持原语义，撤销用同一元数据精确定位引用行 |
| `CommandMigration.kt`、`YohakuDatabase.kt`、`schemas/.../37.json` | 36→37 迁移；本轮不再修改数据库结构 |
| `CommandScreens.kt`、`CommandEffectViews.kt` | 以原方案标题标识撤销、将移除清单、外部引用对象、已撤销状态/时间；手账装饰与状态独立语义节点 |
| 仪器测试及夹具、协议与文档 | 两条链路、精确恢复/引用阻止/旧格式升级、三主题流程；能力样例与协议副本同步 |

## 四个命令的最终处理

| 命令 | 撤销 |
| --- | --- |
| `account.save` | 已有账户通过 saveAccount 恢复原字段；执行记录登记的新建账户、额度配置版本在无修改、无方案外引用时整体移除 |
| `financialRule.addVersion` | 移除本方案新增规则版本，包括已有账户上的新增版本；不能移除执行前已存在的版本 |
| `financialPrediction.save` | 新建预测、账期和修订按登记移除；重算已有预测时恢复原计划/账期完整快照，并移除本次新生成的修订或账期，不通过重新计算冒充恢复 |
| `entry.update` | 按执行前分类分组恢复，复用分类写入核心并追加审计修订。分类可恢复为 null 或原已归档分类；普通编辑和云端输入仍必须指定分类 |

分类字段在持久化模型中允许 null，原值没有分类并非非法数据；此前限制仅来自普通保存入口，故本机撤销适配器允许恢复。信用卡→规则→预测（含额度）和20笔分类两条链路均支持撤销；不再存在上一版的信用卡链路例外。

## 所有权、引用与原子恢复

- 三个账户/金融命令的实际写表为 accounts、credit_limit_configs、financial_rules、financial_plans、financial_bills、financial_plan_revisions。批次事务前后读取这些表，按主键登记发生变化的完整行；`before=null` 才表示本方案新建。包括新建预测归档行，以及已有计划重算时新增的修订行。无关行不进入恢复范围。
- 快照写入 executionJson 的 version=2 撤销记录，含真实 ID、表/主键和完整 before/after。账户/金融对象比较全部字段（含 revision/时间戳）；分类目标比较完整 Event（含 revision）和 LedgerDetails。账户没有统一修订号，按现有字段快照相等核验。
- 新建对象的外键引用检查与 scopedReferenceCounts/accountUsage 共用元数据，并扫描账本业务表的 JSON、嵌套序列化字段和草稿。原方案/云端执行与去重文档属于保留的审计记录，不当作业务引用。只有本方案登记的新建行、以及将恢复原值且当前快照仍相等的已修改行，才算内部引用；其他引用逐项指出具体来源对象和字段。
- 任何修改或外部引用都在逆操作开始前阻止整批撤销。确认重跑再次检查，复用相同的媒体锁、外层事务、确认模型比较和回滚信号，不另建提交路径。
- 本机 `local.restoreExecution` 从原执行记录取证，不接受输入指定表或删除 ID；先恢复已有金融行，再按“预测修订→账期→预测→规则/额度→账户”移除本方案新建行，然后恢复已有账户字段。移除后复查所有登记行及外键/JSON引用，保证无残留。所有这些适配器不进入云端注册表，永久边界保持不变。
- 分类审计修订与正常编辑一样追加；原执行记录、来源去重、回执保留。原方案 UNDONE/undoneAt 与逆向方案执行记录同事务提交；不能再次撤销或重新执行原方案。本机撤销不新增云端回执。

## 兼容与界面

- v36 缺少完整撤销快照，正常读取但明确不可撤销。执行列支持原始 `{outputs,generatedIds,...}` 与 gzip-base64-v1 封装，按结构明确选择解码器；计划文档仍强制封装，不用宽泛 try/catch 掩盖损坏。首轮 v37 中仅登记旧账户删除步骤、缺少完整创建/引用快照的记录也明确不可撤销，已保存的旧撤销预览不能绕过此检查。
- 36→37 增加 undoOf 唯一关联和 undoneAt，旧数据原样保留；本轮只扩展执行文档版本，不另改 schema。
- 撤销详情/历史使用原方案说明的首个标题行，例如“撤销「批量调整咖啡记录分类」”，不展示方案 UUID。预览逐项列出账户、规则/额度版本、预测、账期和修订的“将移除”清单。
- 杂志保留编号/分栏，小物保留标签/收纳卡，手账保留纸卡/旁注。手账的装饰符与“已撤销”等状态拆成独立节点，装饰不进入可访问语义；中日文流程使用同一准确状态节点。

## 必要验证

- `CommandExecutionTest`：信用卡含额度整批恢复、完整原始行无残留及外键检查；20笔分类恢复/重复防止；执行后的记录/账户修改；外部消费、JSON草稿和账单确认阻止；已有账户新增规则并重算预测后精确恢复；原分类为空恢复。
- `DatabaseUpgradeTest.version36AddsUndoIdentityAndPreservesExecutedPlan`：真实 v36 schema 升级保留执行与去重；原始/压缩两种旧执行文档一致解码且不可撤销。
- `CommandFlowTest`：分类撤销和信用卡撤销；`looks=all visual=true`，截图目录沿用 `cloud-command-stage-1/`。分类 `*-undo-preview.png`、`*-undo-history.png`；信用卡 `*-card-undo-preview.png`（将移除清单）、`*-card-undo-history.png`。状态断言定位到具体原方案，避免跨条目匹配。
- 本轮按约束不运行 Gradle、仪器测试或设备截图；静态语法/文档一致性检查不能代替编译和视觉验收。

## 验收结果


## 验收结果（2026-11-02）

- 编译、单元测试：通过。验收时机械修正一处数组拼接的重载歧义（`CommandUndoJournal.kt`）。KSP 生成的 37.json 与手写版本一致。
- `CommandExecutionTest`、`CommandSandboxTest`、`DatabaseUpgradeTest` 共 23 项：通过。
- `CommandFlowTest`（`looks=all visual=true`）2 项：通过；三主题 × 中日文的撤销预览、撤销历史截图（含信用卡链路"将移除"列表）已查看。
- 回归 `AccountManagementFlowTest`、`AccountManagementStorageTest`、`CategoryManagementStorageTest`、`BookLifecycleStorageTest` 共 16 项：通过。
- 第一轮返工：撤销语义从"缺少现成删除操作则不可撤销"改为"本方案新建、未修改、无外部引用的对象整体移除"；修复升级后读取 36 版执行记录崩溃、手账主题撤销状态匹配、界面显示 UUID。
