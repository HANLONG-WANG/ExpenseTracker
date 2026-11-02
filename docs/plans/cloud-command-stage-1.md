# 云端办理阶段 1：框架与纵向链路

日期：2026-11-02。对应[总计划阶段 1](cloud-command-plan.md)。阶段 1 已验收通过（结果见文末）。网站未修改；真实网站往返另列用户实测。

## 文件与组件

| 组件 | 主要文件 | 实现要点 |
| --- | --- | --- |
| 协议与能力 | `data/command/CommandModels.kt`、`CommandSchema.kt`、`CommandRegistry.kt`；`assets/yohaku-command-protocol-v1.md` | 严格输入模型生成 schema；本机清单/digest；有类型前序引用；永久边界；完整信用卡链路和批量分类示例 |
| 对外资料 | `CommandExports.kt`；`docs/formats/` 与安卓交接包副本 | 白名单结构/记录 DTO，覆盖账户、分类、商户/地点/朋友、模板、预算/周期及实例、金融规则/核验/计划/账期/池/合同/安排/额度、活动/参与人/预留、预计收入；生成时间、coverage、contextDigest；按账户含相关资金端；不输出 Entity/路径/凭证/草稿 |
| 命令适配器 | `CommandAdapters.kt` | normalize → 前提 → 共用领域入口 → StepEffect；所有 null/false 提交返回都转为明确批次失败；预测不完整则停止依赖步骤，整批不可确认 |
| 批次与确认 | `CommandRepository.kt`、`CommandConfirmation.kt` | 媒体锁→外层事务；回滚信号只在事务外处理；上下文/固定目标；重跑结构化比较；ID占位符；事实/风险勾选绑定；同一事务提交历史、来源去重及回执 |
| 数据库/队列 | `CommandEntities.kt`、`CommandMigration.kt`、`YohakuDatabase.kt`、`CloudEntities.kt`、`schemas/.../36.json` | Room35→36；旧任务purpose=analysis；command_plans/command_effects；队列pending/retry骨架；执行身份不级联删除 |
| 云端接入 | `CloudRepository.kt`、`CloudModels.kt` | general+command；准备原文/自动附件/所选记录/源文件；purpose由本地任务决定；接收校验；沿用WorkManager回执恢复；可注入测试调度和汇率快照 |
| 提交/预览/历史 | `ui/features/command/CommandScreens.kt`、`CommandEffectViews.kt`、`CloudScreens.kt`、`NativeApp.kt`、`AppViewModel.kt` | 提交摘要、逐步中间效果、净影响、绝对值/未知项/口径、逐项分页、事实与风险勾选、确认/拒绝/重预览、状态历史；三主题独立构图与既有主题控件 |

对外 JSON Schema：`command-plan.v1.schema.json`、`capabilities.v1.schema.json`、`ledger-context.v1.schema.json`、`records.v1.schema.json`；每种有 valid/invalid，另有批量分类 valid。`docs/formats` 和交接包文件同步，协议的唯一源文本在应用 assets，文档中明确副本位置。错误样例不替代本机语义校验；示例 digest 为占位，实际必须用本机导出值。

## 最终命令清单

全部为 v1 / transactional；没有增加其他命令，也没有提交后执行步骤。

| 名称 | 输入与边界 | 确认类型 |
| --- | --- | --- |
| `account.save` | 名称、账户类型（默认CREDIT）、币种、期初金额/日期；可指定已有账户或前步账户引用；可附本账户 creditLimit（总/分期/循环/共用子额度、占用基准、恢复规则/延迟、占用口径/起点/时区）。类型不可改，有历史时币种不可改 | 信息与具体风险提示；实际资金账户负余额加重时单独确认 |
| `financialRule.addVersion` | 账户引用、规则定义、可选付款账户引用；信用卡 SINGLE（含奖金月窗口）、INSTALLMENT、REVOLVING 全字段；复用领域规则结构也支持已有贷款模式。保存不完整规则允许，缺项明确保留，不伪装已核验 | 信息与具体风险提示 |
| `financialPrediction.save` | 账户/模式/名称；期初本金或来源记录；明确的逐期 terms 或生成候选日期所需的 candidates；付款账户；重算时指定旧plan。非一次付必须明确原合同起始期数。保留来源、已确认/逾期/有关联付款账期 | 信息、具体风险提示；新建时必须由用户勾选“平台当前安排、来源/期初本金及日期已核对”事实 |
| `entry.update` | targets.ids 或受限 select，set 仅 category；支出/收入、相应用途分类、1–5000个固定目标；分类预算跟随变化，金额/结算/附件/付款选择/金融事实保持 | 信息与具体预算等风险提示 |

信用卡盘点：账户编辑器本身只有类型、名称、币种、期初欠款与日期；额度在 CreditLimitScreen；一次付/分期/循环及奖金月窗口在规则；消费的 SINGLE/BONUS/DEFERRED/INSTALLMENT/REVOLVING/ACCOUNT_RULE、指定月/手续费属于记录付款选择。本阶段按现有领域归属处理：前两者由 account.save/financialRule.addVersion 覆盖，预测沿用既有记录的付款选择，entry.update 只改分类并保留这些字段。没有将记账字段误当作账户字段，也没有新增范围外的消费创建/付款安排命令。共享额度组成员管理仍属阶段5。

## 下沉与现有行为

- 原 `ui/features/FinancialRuleDraft.kt` 原样迁到 `domain/finance/FinancialRuleDraft.kt`，包含表单→领域、领域→表单转换。编辑器继续使用同一实现；百分比、货币精度、默认空值和校验不变。
- `PredictionCandidates.kt` 提取按月候选付款日期与计息区间生成；`FinancialPredictionEditor` 改调该函数，仍保留原来的规则选择、奖金月/指定月处理、起止端点逻辑与逐期人工修改。
- `EntryDefaults.kt` 新增 `entryNeedsReview`，AppViewModel.saveEntry 改调；查询及原确认判定保持原样，负余额提示仍是独立策略。分类校验抽成 `validateEntryCategory`，普通记账和批处理共用。
- 给 AccountEntity/CategoryEntity 和既有预算/额度/现金预测只增加内部序列化能力，用于结构化确认；云端结构导出仍使用专用模型，不直接序列化这些 Entity。

## 批量准备与性能目标

`CommandRun` 在同一事务中缓存当前状态；选择器解析与分类准备复用同一次初始读取。`prepareEntryCategories` 一次准备整个目标集合，共用分类用途/归档校验，保留所有未修改字段。`writeEntryCategoriesInTransaction` 只写事件和修订，不逐条调用 previewEntry/commitEntryReview，不逐条读取整本账本，也不重算分类无关的金融预测。`CommandStorage.kt` 将本地大段JSON封装为带版本的gzip/base64文档，避免500笔前后模型超过Android CursorWindow单行限制；源JSON解压可精确还原，线上协议不变。每条修订保留完整 LedgerDetails 的相同前后快照；记录保存时间仍按真实写入更新，并比较保存顺序/默认值语义。结束后一次读取当前状态，统一计算预算、余额、额度和预测影响。

性能仪器测试在3700笔CSV夹具上用一个 entry.update 改500笔；计时包括试运行/回滚/模型持久化，以及确认重跑/比较/提交；不包含夹具导入和云端上传。Logcat标签 `CommandStage1` 输出 previewMs、confirmMs，目标各≤5000ms，不写耗时断言。**用户首轮实测：previewMs=927、confirmMs=1219，性能通过。**

## 语义与一致性

- 来源结果身份和规范化 UUID plan_id 双重去重；已领取的方案不可用同plan_id悄悄覆盖原预览。已执行方案收到新结果版本时只补记对应来源与回执，不执行业务第二次。
- contextDigest 对照本任务实际上传的结构附件；确认一致性不依赖整本账本digest。无关账户改名不触发拒绝；有关付款账户余额、目标记录/分类、规则或预测依据变化会拒绝。
- 确认模型保留每步效果，最终净差额为零也不抹掉中间操作。新ID按步骤+输出角色+业务键处理；rule/bill关系及逐期事实保留。
- 事件审计revision、计划/账单审计计数、非业务生成时间不直接比较；还款/退款事实revision、额度组revision和保存时间影响的排序/默认值会比较。日期/时区在重跑前后单独检查。本阶段没有恢复或实际办理变更命令，规则里的办理期限仅作规则资料，不执行有期限的办理动作。
- USER_FACT条件摘要不含展示文案；只能由手机勾选。确认不一致时清空模型、归一化方案、勾选，要求重预览。缺少本金/日期/起始期数或算法资料不生成伪完整预测。
- 执行完成后，执行状态/用户勾选、输出及占位符→实际ID映射（executionJson）、cloud_imports去重和cloud_acks回执同事务提交。进程在发送回执之前中断，启动恢复只重发回执。取消试运行或确认都让外层事务回滚。

## 验证与待验项

已做的非构建检查：

- 所有本次 Kotlin 文件经本地 Kotlin PSI 语法解析，无语法错误；这不是类型检查、KSP或编译成功的证明。
- 用SQLite执行35→36迁移，对照36.json逐表核对列类型、可空性和默认值；Room2.8.4 identity算法复现已导出的35.json哈希，再按新增结构生成36.json。初版文件为手工准备；首轮用户KSP构建已确认生成结果完全一致。
- JSON文件可解析，协议/格式副本一致；本机严格协议用例已写入JVM测试。环境没有Python jsonschema/pip，未把JSON文本解析称作外部JSON Schema验证。

待用户串行执行：

1. JVM `CommandProtocolTest`、`CommandConfirmationTest` 及下沉相关既有单元测试。
2. `CommandExecutionTest` 的6项：信用卡链路与持久化回执/去重；20笔分类与关联资料保持；相关/无关变化；预测缺项；中途失败/第二次账户插入时取消；3700/500性能。失败发生在正式确认的第二步规则插入（SQLite触发器），取消发生在正式确认的第二次账户插入；取消测试用Room查询回调触发真实协程取消，没有生产代码测试钩子。
3. 新升级用例 `DatabaseUpgradeTest.version35AddsCommandTablesAndPreservesExistingCloudTask`；旧v32局部降级夹具同步还原cloud_tasks，避免旧测试夹具误触发重复purpose列。
4. `CommandFlowTest` 用 `looks=all visual=true`：真实提交准备/自动附件→固定结果经生产接收入口→预览→勾选→确认→历史。截图路径 `cloud-command-stage-1/`，三主题×中日文的submit/preview/checks/history。同一流程还重建CloudRepository，检查启动恢复调度及回执发送体（内存HTTP替身），确认待发回执被发送且不重执行业务。按项目约定实际查看截图；本次未运行或查看设备截图。
5. 真实网站往返：使用用户连接配置及Work订阅，另外实测。不修改网站，不把模拟接收当作真实网络验收。

无意图性缩减或架构偏离。不同于逐条记账入口的批量分类核心是为解决阶段0实测瓶颈而实现；校验和修订语义共用，仍受同一外层事务和确认模型约束。首轮构建/单元/升级与性能已有用户通过结果；本轮信用卡链路和UI修复仍待复测，真实网站往返另行验收。


## 第一轮验收修复

用户已补 RecordFacts import、runBlocking<Unit>；本轮保留这些修改。用户确认编译、单元测试、DatabaseUpgradeTest通过，KSP生成的36.json与提交文件一致；3700笔/500目标耗时927ms、1219ms，均达标。本轮不修改数据库或批量写入核心。

- 信用卡确认失败根因：确认投影只解析 `*Json`，遗漏 `FinancialRuleEntity.json`，其中的规则ID和账户ID仍留在字符串内，无法规范化。现以规则专用投影解析 `json` 后递归映射ID，规则内容全部继续比较，不忽略字段。新增生成ID不同但规则一致／付款日改变须拒绝的回归；现有信用卡仪器测试增加两次预览模型一致性断言。
- `CommandConfirmationComparator.firstDifference` 返回首个结构化差异路径，`CommandRepreviewRequired.differencePath`携带它，Logcat `CommandStage1`以DEBUG级别记录路径、不记录值；用户错误文案保持不变。路径会优先落到步骤的实际差异字段，而不是确认摘要hash。
- 移除普通数值变化触发的笼统风险勾选。`CommandRisk`记录对象、币种、金额、日期、缺项；步骤和汇总显示负余额、额度/预算超限、资金缺口与未知项。只有实际负余额加重沿用独立确认，文字写明账户和余额；USER_FACT继续必选。未变化的主摘要值（包括未知→未知）隐藏，完整模型和详情照常保留及比较。
- `CommandFlowTest`提交页截图前调用Espresso.closeSoftKeyboard并等待布局稳定，且断言普通信用卡链路没有RISK开关。新增 `CommandRiskTest`覆盖普通变化、未知额度、具体负余额确认和额度/预测提示。

待复测：CommandConfirmationTest、CommandRiskTest、CommandExecutionTest信用卡及相关/无关变更用例、CommandFlowTest（looks=all visual=true并查看截图）。本轮仅做静态检查，未把第一轮通过结果当成本轮修复已经通过。

## 验收结果（2026-11-02）

- 编译、单元测试（含 `CommandProtocolTest`、`CommandConfirmationTest`、`CommandRiskTest`）：通过。验收时机械修正了三处：补 `RecordFacts` import、测试方法改为 `runBlocking<Unit>`、字符串模板 `${scope}`；`CommandFlowTest` 使用的 Espresso 已加入 androidTest 依赖。
- `CommandExecutionTest`、`CommandSandboxTest`、`DatabaseUpgradeTest` 共 17 项：通过。Room 生成的 36.json 与手写版本一致。
- 性能：3,700 笔账本单步改 500 笔分类，试运行约 836 ms，确认执行约 1,186 ms（目标各 ≤5 s）。
- `CommandFlowTest`（`looks=all visual=true`）：通过；三主题 × 中日文 24 张截图已逐张查看，三主题各自独立设计，日文无漏译。
- 相关回归 80 项：除 3 项之外均通过。这 3 项在本阶段改动前同样失败或属于偶发：`FinancialRuleModesFlowTest.configureAllModesAndFixedLoanMethodsWithoutChangingKnownFacts`（已用改动前构建复现）、`DraftEditingFlowTest.incomeAndOpeningIncludedRemovalShowTheirActualBudgetEffects`、`ledgerSearchAndParentFilterKeepOriginalRecordsAcrossThemes`；`categoryManagementEditsFavoritesAndCreatesSeparateIncomeWithoutChangingLedger` 单独重跑通过。
- 未验证：真实网站往返（需要用户的连接配置和 Work 订阅）。
