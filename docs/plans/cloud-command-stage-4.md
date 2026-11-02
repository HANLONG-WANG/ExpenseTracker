# 云端办理阶段 4：预算、活动与周期全覆盖

日期：2026-11-02。基线：阶段 3 `f5b3851`。状态：已验收通过（2026-11-02）。实现时未运行 Gradle；编译、仪器测试与截图由验收方执行，结果见文末。

## 命令清单

新增 23 个命令，全部 **v1 / transactional**，沿用 `CommandRepository`、结构化确认及 v3 撤销日志。确认类型全部为 **INFORMATION / RISK**：普通变化不用额外勾选，只有实际资金账户负余额加重沿用独立确认；没有新增 USER_FACT。下表每行均采用同一撤销规则：**已有行精确恢复原值，本方案新建且未修改、无外部引用的对象及从属行整体移除；任何冲突阻止整批**。

他人请客是实际记录，未自动加入结构上传；仅在用户选择日期范围时，记录附件的 `activityTreats` 导出该范围的未删除他人请客ID/内容/分摊，提交页单独显示数量。按账户选择不包含它们（他人付款没有本人的付款账户）；不选记录则不上传。结构附件补齐预算月份、周期商户、预留实例与剩余额、预计收入到账关联等已有规划字段。

严格输入 schema、示例、默认值、前提、确认/比较、执行方式、undo 和失败语义由 `PlanningCommandAdapters`、`ScheduleCommandAdapters` 注册表导出。所有金额都是 `{currency,minor}`，不能把未知当零。

| 名称 | 输入、默认值与输出 | 共用业务、前提及连带影响 | 比较与撤销范围 |
|---|---|---|---|
| `budget.save` | month、amount 必填；amount:null 清空；category 省略/null 为总预算 | previewBudget/commitBudget；合法月份、本位币非负金额、启用的支出分类；额度自该月延续 | 预算完整行、各相关月份预算绝对值；原行恢复/新设置行移除 |
| `budget.saveBook` | month、amount 必填；name/currency/startDate 省略或 null 保持 | previewBookBudget/commitBudget，账本信息与月预算一起保存；币种变更沿用业务约束 | 账本及预算完整行、预算变化 |
| `activity.setMode` | enabled 必填 | setActivityMode；关闭清当前活动，开启保留有效当前活动 | book_features 完整原行或新行 |
| `activity.setCurrent` | activity 必填，可 null 退出当前活动 | 模式开启，当前活动须未结束/未删除 | 当前活动及模式、引用活动 |
| `activity.save` | name/start/end 必填；activity 省略新建；budget=null、categoryBudgets=[]、excludeMonthly=false、ended=false；完整保存；输出 activity | previewActivity/commitActivity；日期、分类、币种校验；已结束不可重开；分类预算全量替换 | 活动/分类预算、相关月预算、活动已用/预留/可用、互请金额 |
| `activity.end` | activity 必填 | 保留现有字段及分类预算，经活动保存确认结束；释放未用预留、清当前活动 | 活动、预留、模式及派生金额 |
| `activity.lifecycle` | activity、TRASH/RESTORE 必填 | TRASH 复用删除影响确认；RESTORE 恢复显示与历史预算归属 | 活动、规则/实例、预留、模板、模式及匹配新记账草稿；撤销精确恢复全部原值 |
| `activityReserve.save` | activity/name/date/amount；reserve 省略新建；account/accountAmount/occurrence 默认 null；输出 reserve | previewReserve/commitReserve；模式开启、未结束活动、正金额、账户金额成对、实例关系唯一；总额不能低于已用+已释放 | 完整预留、相关实例/账户、活动余量及月预算 |
| `activityReserve.release` | reserve、amount 必填 | previewRelease/commitReserve；正数且不超过剩余预留 | 释放前后原行、可用与预留；无实际入账 |
| `activityParticipant.add` | activity、friend 必填 | addParticipant；本账本未删除活动、启用朋友；已有关系不重复增加 | 参与关系及顺序，朋友/活动依据；新关系移除不删除既有朋友 |
| `activityTreat.save` | activity/payer/total/base/date/name/shares；mode=EQUAL，treat 省略新建；输出 treat | previewOtherTreat/commitOtherTreat，复用均分/比例/金额分摊和尾差；付款朋友在参与人中，本人份额须正；结束活动仅补录期间事实 | 请客/完整分摊、参与人、互请往来前后金额；不入资金账 |
| `activityTreat.lifecycle` | treat、TRASH/RESTORE 必填 | 他人付款记录软删除/恢复；本人付款请从 entry 编辑 | 请客完整行、分摊关系与往来 |
| `recurrence.save` | name/account/category/frequency/anchorDate；amount/baseAmount=null，estimated/active=true，其余可选字段为空/null；完整保存；输出 recurrence | saveRecurrence；消费付款账户、支出分类、日期/固定付款选项；刷新已有月份及当前月待办，保留完成/取消历史 | 完整规则、实例、预算及逐期资金预测 |
| `recurrence.setActive` | recurrence、active 必填 | 重建原规则输入走 saveRecurrence；其他字段原样保留 | 规则和受影响实例原行 |
| `occurrence.resolve` | recurrence、date 必填；输出 occurrence | 取确定规则/日期实例；不存在时仅物化该规则所在月份；不能猜 UUID，日期须符合启用规则或已有历史 | 规则依据、实例完整字段；只移除实际新建行 |
| `occurrence.update` | occurrences（1–5000个明确引用）、category | 一次读取准备，允许调整完成/取消历史的分类，目标分类为启用支出分类 | 固定集合、完整实例；不改实际付款记录 |
| `occurrence.lifecycle` | occurrence、CANCEL/RESTORE | cancelOccurrence/restoreOccurrence；恢复要求启用规则仍包含该日；保留已有付款事实或恢复待办字段 | 实例全字段及相关资金/预算预测 |
| `occurrence.link` | links：1–5000组 occurrence/entry | 共用 prepareOccurrenceLink，一份快照顺序准备全部关联后写入；既有有效支出，不能重复完成另一实例；仅消费匹配的支付草稿 | 实例、记录及完整结算/关系、草稿/暂存附件关系；不重复入账 |
| `occurrence.unlink` | occurrence | unlinkOccurrence；保留记录，按当前规则恢复 PENDING/WITHDRAWN | 实例及关联记录依据 |
| `occurrence.pay` | occurrence；set={} 为记录字段补丁，不得改 kind/occurrence | occurrencePaymentDraft + entryDraft + previewEntry/commitOccurrencePayment；匹配已存草稿时保留其输入与本地附件；须待付、完整金额与付款资料；仅消费匹配支付草稿；输出 entry | 新记录、所有入账/关系/金融从属行、实例、草稿、金额/风险 |
| `expectedIncome.save` | name/amount/date/account；income 省略新建；accountAmount=null；useReferenceRates=false；输出 expectedIncome | previewExpectedIncome/commitExpectedIncome；启用资金账户、正金额，已有效到账不可编辑；外币未知保持未知，显式选参考报价时冻结在上下文 | 预计项完整字段、账户与资金情景；没有实际资金入账 |
| `expectedIncome.cancel` | income | cancelExpectedIncome；已到账不允许取消实际资金 | 原状态、revision、updatedAt 精确恢复 |
| `expectedIncome.arrive` | income；entry 与 set 必须二选一；输出 entry | previewExpectedIncomeArrival/commitExpectedIncomeArrival；新增时共享收入草稿默认值，必须给实际 amount/category；关联时不重复入账；确认入账、同账户、非期初、日期不在未来且不重复关联 | 原预计金额保留；预计项、实际记录及结算/关联全字段 |

`activityTreat.save.shares`：friend=null 表示本人；EQUAL 可省略本人项（分摊器固定包含本人），不得重复参与人；PERCENT 必须包含本人且逐人填写 percent，合计100；AMOUNTS 必须逐人填写 minor，合计等于 total；tail 省略沿用分摊器的最后参与人，显式 null 为本人。base 必填、不擅自估算。

`activity.save`、`recurrence.save`、预留和预计收入保存均为完整保存，不是补丁；省略可选字段采用表中默认值。`occurrence.pay.set` 为补丁，优先复用匹配的已存支付草稿（含本地附件），否则沿用当前实例及规则的表单默认值；金额未知或外币所需换算缺失时整批失败，不生成未完成草稿。所有 false/null 提交结果都转为批次失败。

## 永久边界判定

| 现有实现/领域 | 判定 | 云端替代 |
|---|---|---|
| 预算页清空金额 | 允许，`putBudget(amountMinor=null)`，不是删除行 | budget.save amount:null；账本组合用 budget.saveBook |
| `clearBudgets` 等物理清表 | forbidden；出现在初始化币种调整、分类物理合并等内部实现，不提供云端清表入口；book.update/budget.saveBook 若改币种将触发该清表也拒绝 | 明确月份/分类清空额度，保留空值历史 |
| 活动删除 | `deletedAt` 软删除，允许；同时改变预留/规则/实例/模板/草稿 | activity.lifecycle TRASH；恢复显示用 RESTORE |
| 活动/预留/参与关系/活动预算的独立物理删除 | forbidden；部分没有独立本机按钮，仍不能包装成云端命令 | 活动回收，预留 release，保留参与历史/停用朋友；活动分类预算作为活动编辑完整替换 |
| 他人请客删除 | removeOtherTreat 仅设 deletedAt，允许 | activityTreat.lifecycle TRASH/RESTORE |
| 请客物理删除、清分摊表 | 不提供独立云端删除；普通请客/记录编辑中的从属行替换仍作为完整对象编辑登记 | activityTreat.save / entry.update 或软删除 |
| 周期规则物理删除 | forbidden；本机目前保存/启停，没有独立删除入口，账本整体清理仍能物理删除 | recurrence.setActive false |
| 实例物理删除 | forbidden；取消/恢复仅改状态 | occurrence.lifecycle CANCEL/RESTORE |
| 预计收入物理删除 | forbidden；取消仅改状态 | expectedIncome.cancel；实际到账记录另走 entry.lifecycle |
| 整本账本清理中的上述表删除 | 延续 book.purge / any.permanentDelete 永久边界 | 保留、归档、回收/恢复 |

上述边界加入 `CommandPermanentBoundary`、生成能力清单的 forbidden/forbiddenAlternatives 及三处协议。普通编辑替换活动分类预算、请客分摊等从属关系行，必须保留完整 before/after/removed；不开放任意删表/删行能力。受限移除只有本机历史撤销能调用。

## 分类迁移

`entry.update` 明确 ID + 仅 category 补丁也可处理回收站记录，保留回收状态；其他记录编辑及选择器仍仅处理未删除记录。组合为：批量改记录分类 → template.save 改模板 → budget.save 明确目标各生效月额度并将原分类每个生效月设 null → activity.save 替换 categoryBudgets → recurrence.save 改规则 → occurrence.update 改完成/取消等历史实例 → category.lifecycle ARCHIVE。

预算金额清空后仍保留引用旧分类的空值历史行；这不是物理合并删除。分类仍归档保留，预算使用效果等价迁移，不宣称清除了全部历史引用。目标额度如何合并必须由方案明确金额，不悄悄累加。普通/金融草稿分类引用仍归阶段5–6，`permanentAlternatives["category.mergeDelete"]` 和协议明确说明。

## 共用业务与下沉

- `RecurrenceRepository.prepareOccurrenceLink`：从单条链接准备提取；界面单条和云端批量复用。批量一份快照顺序验证全部链接后写入，拒绝同一支出重复完成实例，不逐条重读整本账本。
- `occurrencePaymentDraft`、`withOccurrenceAmount`、`EntryDraft?.isOccurrencePaymentDraft`、`consumeOccurrencePaymentDraft`：默认值选择、外币改金额使旧本位币估值失效、匹配判断从 AppViewModel 下沉；链接和命令支付共用，仅清理匹配的已存草稿及其暂存关系。`commitOccurrencePayment` 继续调用 `commitEntryReview`，界面快捷确认保持原先不清草稿的选项；云端完整支付复用匹配草稿的输入与本地附件后消费草稿；已选择已有记录的草稿要求改用 occurrence.link，避免重复记账。页面导航与媒体垃圾回收仍在 UI 提交后执行，不进入命令事务。
- `expectedIncomeReceiptDraft`：预计收入新增实际到账的日期/账户/备注默认值由表单下沉；界面和命令共用。实际金额不从预计金额推断。
- `setRecurrenceActive` 复用完整规则保存，`ensureOccurrences` 支持指定规则物化，现有页面调用语义不变。`updateOccurrenceCategories` 一次准备，覆盖历史实例分类迁移。
- 新增共用 `restoreActivity`，本机“已删除活动的历史”中每个活动的恢复按钮与云端 RESTORE 共用：仅清除 deletedAt，不复活旧预留/规则/模板/草稿。既有活动没有恢复期限及到期清理逻辑，没有凭空引入30天限制。删除弹层旧的“无法撤销”文案已改为准确说明。请客恢复同样保留原份额。
- 全仓 UI DAO 写入盘点：本阶段没有直接写入；剩余 FinancialDrafts、CsvImportViewModel、StatementsScreen 分属阶段5–6，没有在本阶段扩大范围。

## 比较、ID 与撤销

- 新预算/活动/分类预算/参与关系/预留/请客/规则/实例/预计收入加入完整行快照。活动投影复用 activitySpent/activityHeld/friendBalances，展示上限、已用、预留、可用、分类预算和互请往来。相关链接同时比较实际记录和完整结算/关系字段。
- 显式输出按步骤+角色映射；实例按规则语义ID+日期映射，连资金预测中的未来未落库实例也在投影排序前登记，避免新规则随机ID影响现金走势比较。嵌套JSON继续解析后只映射完整标识值。
- updatedAt/createdAt 等纯审计时间不参与确认；完整原始行仍用于撤销冲突及恢复，revision 不丢失；记录及金融事实 revision 继续沿用阶段3策略。日期/时区有效性复用执行器。
- v3 所有权范围增加 book_features、activities、activity_budgets、activity_reserves、activity_participants、recurrences、expected_incomes、drafts、draft_media、attachment_links、receipts、receipt_images；treats/allocations/occurrences 继续沿用。已有关系被替换/草稿被清理时精确恢复原行。
- 已有规划对象额外保存关联集合：实例、参与人、请客分摊、记录/结算、模板、JSON草稿等。关联行新增或变化也阻止整批撤销；批量守卫一次读取关系表，共用缓存，状态刷新后失效。新对象继续检查 Room 外键、逻辑ID、嵌套JSON和草稿引用。清草稿涉及暂存附件时记录文件守卫；如后续本机清理了文件，明确冲突，不恢复缺失附件引用。
- 恢复已有行后，按依赖顺序移除执行拥有的新行，检查引用和外键。原方案/撤销/去重审计均保留。Room 仍 v37，执行文档仍 v3，无数据库结构变化，因此没有新增迁移。

## 适配检查表对应

每个命令均按上表输入/前提/范围及以下公共实现逐项适配，不另建执行或撤销机制。

| 检查表项目 | 实现/证据 |
|---|---|
| 名称、版本、schema、默认/null、输出、示例 | 23个注册能力；严格序列化DTO与类型引用槽；未知命令/版本/字段整批拒绝；每项能力含默认值、前提、比较、执行模式、undo、示例 |
| 固定引用/集合 | 仅前序类型引用；批量链接和实例分类显式1–5000目标；新实例通过规则/日期 resolve，不猜ID |
| 共用校验、确认类型、失败语义 | 原预算/活动/预留/请客/周期/预计收入 review/commit；null/false检查；I/R策略复用，无额外事实代勾 |
| 中间状态、绝对值、比较范围 | 每步完整行+相关预算/资金投影；新增活动金额投影；净零仍记录触及行；已有链接比较完整实际记录 |
| 时间/revision/ID | 纯审计时间忽略；完整原始行用于撤销；实例包含未来预测身份；新输出及嵌套关系规范化 |
| inverse、快照及冲突 | 所有适配器继承 LedgerAdapter.inverse；v3原行恢复/受限移除、规划关联守卫、附件存在守卫；任一冲突整批禁止 |
| 事务、去重、执行记录 | 继续 mediaWrites→withTransaction、试运行回滚/确认重跑；实际映射与快照同事务；UNDONE及审计保留 |
| 批量准备 | linkOccurrenceBatch、updateOccurrenceCategories 一次准备；关系守卫共用最终快照缓存 |
| 本地化/主题/移除清单 | 现有三主题独立组件；对象、字段、状态、金额中日文；预算与活动余量显示前后值；移除条目区分新对象/新增参与关系/分摊和修订，避免误读为删除已有朋友/记录 |
| 必要验证 | 下述五组合+一项真实冲突；沿用集中禁止操作与共性框架测试；无额外边界矩阵 |
| 文档/协议/样例 | 三处协议、实现规格、UI对照、Android README、总计划；用户已通过既有生成测试更新能力样例；本轮未改变能力描述/schema |

## 修改文件

- `data/command/`：新增 `PlanningCommandAdapters.kt`、`ScheduleCommandAdapters.kt`；修改 `CommandRegistry.kt`、`CommandConfirmation.kt`、`CommandLedgerFacts.kt`、`CommandExports.kt`、`CommandPermanentBoundary.kt`、`CommandRepository.kt`、`CommandUndo.kt`、`CommandUndoJournal.kt`、`EntryCommandAdapters.kt`、`LedgerCommandAdapters.kt`。
- `data/repository/`：`RecurrenceRepository.kt`、`ActivityManagementRepository.kt`、`TreatRepository.kt`、`ExpectedIncomeRepository.kt`。
- `ui/features/`：`AppViewModel.kt`、`ExpectedIncomeEditor.kt`、`ActivityDeletionSheet.kt`、`command/CommandScreens.kt`、`command/CommandEffectViews.kt`。
- 测试：新增 `CommandPlanningExecutionTest.kt`，修改 `CommandLedgerExecutionTest.kt`、`CommandFlowTest.kt`。
- 文档：本文件、总计划、implementation-spec、ui-implementation-map、android/README，以及 assets、docs/formats、安卓交接包三份协议、两份 records schema（可选 activityTreats 字段，兼容旧样例）。两份能力样例已由用户运行生成测试更新，本轮未手改。

## 必要测试与验收入口

`CommandPlanningExecutionTest`：月+分类+账本预算；活动+预留/释放+参与人/他人请客+结束/回收/恢复；周期保存+支付+取消/恢复+批量关联/解除+草稿/本地附件关系精确恢复；预计收入+关联/新增实际到账+取消；分类迁移（含回收站记录、活动分类预算和取消实例）。每组验证预览回滚、跨2秒再次预览一致、执行后的具体业务值、撤销预览回滚、撤销后完整原始行恢复及外键。另一个共用用例验证真实预算变化导致确认拒绝、已有活动新增参与关系导致整批撤销冲突。

禁止操作加入阶段3 `CommandLedgerExecutionTest.permanentOperationsRejectTheWholePlanWithAlternatives` 的集中参数列表；框架的去重/重复撤销、JSON引用冲突继续复用已有测试。

`CommandFlowTest.planningCombinationPreviewAcrossLooks`：`looks=all visual=true`，截图 `cloud-command-stage-1/*-planning-preview.png`；须查看三主题×中日文及相关详情。本轮新增同一流程内的 `*-planning-mode-preview.png`，检查“否 → 是／いいえ → はい”。`ActivityManagementFlowTest.deletionPreviewAndOriginalHistoryAcrossAppearances` 在原流程中补充列表恢复操作、恢复后预留保持停用的断言和 `phase-e-activity-management/*-restore.png`。

用户已运行生成入口并通过 `CommandCapabilitiesTest`。本轮未修改能力描述/schema，无需重新生成；以后注册能力变化时，在 `android/` 串行执行：

```sh
./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest
```

该命令由真实 `CommandRegistry().export()` 同步两份样例及真实 catalogDigest；不带开关只校验；当前样例已在用户首轮验收时更新。

实现阶段仅做 Kotlin PSI 语法、diff 和协议副本一致性静态检查；没有把它们当作编译/测试或视觉验收。未发现必须拆为 interactive 才能完成的本阶段命令。金融草稿协调按已约定留阶段5–6。

## 第 1 轮返工

用户反馈的首轮结果：编译一次通过；单元测试（含已重新生成样例后的 CommandCapabilitiesTest）通过；CommandFlowTest 在 looks=all visual=true 下4项通过；活动、预算、周期、预计收入、记录、分类、账本回归57项通过。CommandPlanningExecutionTest 有3项失败。以下是源码根因及本轮修改，不能当作修改后的运行结果。

### 三项失败均属于产品代码问题

1. **monthlyCategoryAndBookBudgets**：CommandRun 复用了 financialReviewState 的比较用账本。其 reviewState 会将 theme/language 设为空，budget.saveBook 又把这份账本作为更新输入，触发 updateBook 合法值校验。financialReviewState 增加 preserveLedgerInputs 选项，命令缓存显式保留原始账本字段和草稿，金融确认调用仍默认使用原来的比较快照。不伪造主题默认值，不绕过合法值校验。原组合测试追加主题、语言保持断言。
2. **expectedIncomeExistingAndNewReceiptAndCancellation**：`$.steps[3].objects[9].after.eventId` 对应 entryDefaults（最后保存的收入记录），新到账ID已经通过 output(new=true) 登记。真正原因是到账单笔提交直接采用 clock.millis，新旧收入的保存时间/创建时间在首轮预览中相同，lastSavedEntry 的并列结果受记录列表 UUID 排序影响；两秒后重跑，新收入成为唯一最近记录，因此默认值依据发生变化。提取阶段3 BatchEntries 已用的递增保存时序为 nextEntrySaveTime/withSaveTime，并用于 commitEntryReview 与 commitExpectedIncomeArrival。保留每条保存的先后语义，不删除 entryDefaults 比较，也不冻结UUID。原组合测试追加“最新收入引用为 @newReceipt/entry/”断言。
3. **recurrencePaymentCancellationAndBatchLinkConsumeOnlyMatchingDraft**：同一比较快照还把 draft 设为了 null。支付确实写入了记录并关联实例，但 occurrencePaymentDraft 无法取回原支付草稿，只能重建规则默认值；原备注和本地附件没有进入记录，之后消费草稿又清掉了暂存关系，测试查找原备注的记录因而失败。命令保留原始草稿后，共用支付流程能读取原备注/附件并保存，再消费匹配草稿。保留原测试的备注、附件、小票、关联和完整撤销断言，没有改成接受丢失输入。

### 默认值与本机恢复入口

- CommandLedgerFacts 对可缺省的 book_features 存储行使用实际生效的默认状态（activityMode=false、activeActivityId=null）生成前后确认模型，恢复为缺行时也显示关闭。摘要和逐项详情共用此模型；不是只换显示文字。原始所有权日志仍保留 before=null，以保证撤销精确移除本方案新建的设置行。新建业务对象仍明确显示不存在，未知金额不伪装成默认零。
- ActivityScreens 的“已删除活动的历史”列表为每个活动增加“恢复／復元”，复用 YActionRow/YButton 的三主题设计及已有 restoreActivity。只恢复显示和历史预算归属，预留/周期不自动重启；没有新页面。
- 不新增测试方法或边缘矩阵。现有 CommandPlanningExecutionTest、CommandFlowTest 增加必要断言；现有 ActivityManagementFlowTest 补恢复操作和截图。新截图及修复后的运行结果待用户复验。

本轮修改文件：`FinancialRepository.kt`、`CommandConfirmation.kt`、`BatchEntries.kt`、`LedgerChangeReview.kt`、`ExpectedIncomeRepository.kt`、`CommandLedgerFacts.kt`、`ActivityScreens.kt`、`CommandPlanningExecutionTest.kt`、`CommandFlowTest.kt`、`ActivityManagementFlowTest.kt`、本阶段文档、Android README、UI对照表。能力描述、输入schema及两份已生成样例未修改，无需重新生成能力样例。

本轮仅做 Kotlin PSI 语法检查和 diff 静态检查，未运行 Gradle、编译、仪器或新截图视觉验证。

## 验收结果（2026-11-02）

- 编译：首轮即通过；能力样例由 `CommandCapabilitiesTest` 的生成入口更新。
- 首轮发现的问题：
  - `CommandPlanningExecutionTest` 3 项失败，根因都在产品代码：
    - 预算命令使用了清空 theme/language 的金融比较快照；
    - 同毫秒保存导致"最近记录"默认值依赖 UUID 排序；
    - 比较快照丢弃草稿，使周期支付丢失原草稿的备注和附件。
  - 布尔偏好缺行时显示"无此项 → 是"。
  - 删除活动的说明提到"恢复"，但手机端没有恢复入口。
- 第 1 轮返工：
  - 上述问题全部修复：普通单笔提交也复用递增保存时序；布尔偏好按实际默认值显示"否 → 是"。
  - "已删除活动的历史"新增三主题"恢复"按钮，与 `activity.lifecycle` RESTORE 共用业务函数。
- 单元测试：全部通过。
- 仪器测试：
  - `CommandPlanningExecutionTest`、`CommandLedgerExecutionTest`、`CommandExecutionTest`、`CommandSandboxTest`、`DatabaseUpgradeTest` 共 37 项通过。
  - `CommandFlowTest`、`ActivityManagementFlowTest` 在 `looks=all visual=true` 下 5 项通过。
- 回归：活动、请客、预算周期、预计收入、首页计划、记录、草稿、回收站、信用卡付款、转账、持久化、余额池、商户、分类、账本共 118 项，失败 2 项，均为阶段 1 前已存在的失败：
  - `DraftEditingFlowTest.incomeAndOpeningIncludedRemovalShowTheirActualBudgetEffects`
  - `DraftEditingFlowTest.ledgerSearchAndParentFilterKeepOriginalRecordsAcrossThemes`
- 视觉：已查看三主题 × 中日文的以下截图：
  - `*-planning-preview.png`、`*-planning-mode-preview.png`；
  - 活动删除历史 `*-history.png`、恢复入口 `*-restore.png`。

