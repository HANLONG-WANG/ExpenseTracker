# 云端办理阶段 7：月度计划、评语与问答

日期：2026-11-02。基线：阶段6 `6d96623`。状态：**阶段 7 已验收通过**。实现由 Codex 完成（其环境无法运行 Gradle/模拟器），编译、测试与视觉验收在本机完成，见文末验收结果。

## 功能与命令

| 功能 | 实现 |
|---|---|
| 月计划编辑 | 预算页附近和月评历史入口；每账本一份通用自然语言计划，原文保留空白/换行；中日文，三主题独立版式 |
| `book.monthlyPlan.save` v1 | transactional；必填 `text`，最多20000字符、完整替换；空字符串清空内容，不删行；无输出引用 |
| 每月提醒 | 默认开启，通知设置的独立 `monthlyReview` 开关；`preferences.reminders` 同步包含；每月1日提醒上月 |
| 提交页 | 提醒直达预填页；历史可手选账本开始月份至上月。展示收支、分类、预算执行、账户变化、月计划和结构范围；用户确认才入上传队列 |
| 月评任务 | general / purpose=monthly-review；领取即保存评语；可选建议需用户主动预览，继续原确认/执行/整批撤销 |
| 月评历史 | 按账本、月份保存提交快照和结果关联，显示原计划、评语及建议未执行/执行/撤销/拒绝状态；后续效果状态由方案详情显示 |
| 问答 | 无步骤结果保存为 ANSWER，直接显示回答/问题，不试运行、不出现确认按钮，可从方案历史重新打开 |

永久边界不变：恢复备份、连接凭据与设备管理、全部永久删除永不开放。月计划保存与编辑共用 `LedgerRepository.saveMonthlyPlan`。不会把月度提醒、任务创建或上传作为云端递归执行命令。

## 提醒设计

沿用 `ReminderCoordinator` 的单一非精确 AlarmManager 唤醒、已提交写入观察、设置观察、午夜重建、系统通知权限与持久去重；没有新 Worker、后台上传或第二套调度器。

- `MONTHLY_REVIEW` 使用当月1日、用户已设置的提醒时刻，提前天数固定为0，不借用周期账单提前天数；目标月份为上一自然月。
- 仅已完成设置、未回收且未归档的账本，被评月不早于账本开始月份；`monthly_reviews` 已有该账本/月份的提交行即不生成提醒（包含等待上传/等待结果的任务）。
- 使用已有 `bookId/key/date/day` 去重键与 ReminderDeliveryState.sent。重建和进程重启不会重复；错过1日不在后续日期补发，遵循现有“不补发过期日”规则；可以手动提交。
- 系统通知不可用时不发出、不记已发送；仍午夜重建。点击含月评的通知直达该账本/月份的提交页；同一通知还有重要事项时优先打开月评，其余仍可在应用内查看。
- 决定：`monthlyReview=true` 独立于原 `enabled=false`（重要事项总开关），保证新功能默认开启且不悄悄打开其他提醒。关闭全部提醒需要同时关闭这两个开关；界面有明确说明。沿用系统通知权限，默认开关不等于绕过权限。
- 阶段6已持久化的偏好队列/撤销快照缺少新字段时按默认true补全比较，避免升级后全部旧效果因字段缺失误冲突；用户后来改成false仍是真实冲突。
- `preferences.reminders` 仍是完整替换；新字段省略默认 true；全部列表继续规范排序。若启用任一提醒且无权限，走阶段6本机授权引导，整批不可撤销；已有权限时可按原值恢复偏好。

## 上传与结果格式

初次上传四份：协议 Markdown、注册表 capabilities、ledger-context、`yohaku-monthly-review-v1.json`。不附逐笔 records、图片或其他文件。用户后续在交接台明确追加的资料仍走原补充流程。

context：`book_id`、`yohaku_protocol=yohaku.command-task`、`protocol_version=1`、`purpose=monthly-review`、`context_digest`、`month=YYYY-MM`。全部结构使用原白名单 DTO，额外有 `monthly_plan.values.text`；不上传 Entity、草稿、凭据或月评历史。

月评 JSON：

| 字段 | 含义与口径 |
|---|---|
| format / version / book_id / month / currency | yohaku.monthly-review / 1 / 账本 / 被评月 / 账本币种 |
| monthlyPlan | 本次提交时计划原文 |
| incomeMinor / expenseMinor | 收入与净消费；退款回原消费月，费用组成按自身日期；不把转账当收入或消费 |
| categories | id/name/kind/amountMinor；直接分类，不重复汇总到父分类；费用组成单列 |
| budgets | categoryId/name/limitMinor/spentMinor/remainingMinor；全月及已设分类预算，继承历史设置，排除独立活动；未知上限/剩余保留null |
| accounts | id/name/currency/openingDate/beforeMinor/afterMinor/changeMinor；各自币种，已确认入账日；未开立为null；月中开立的变化含期初资金 |
| provisionalExpenseCount / basis | 待确认消费笔数与统计口径说明；反映提交时已知事实，不冒称冻结的月末审计 |

结构是当前结构，月汇总是被评月，不混淆两者余额。提交页一次生成并展示快照，确认上传同一份；任务与月份快照在同一事务保存。重复点击依赖账本+月份主键阻止重复任务落库。已提交月份在交接台重试原任务，不创建另一份月任务。若用户在本机放弃尚未创建远端任务的准备资料，沿用原 discard 并在同事务释放该月占位，之后可重新提交；不会删除已领取评语。

结果仍为 `yohaku.command-plan`：非空 message 是评语，steps 是可选建议；助手不得声明 USER_FACT。领取成功在同事务写评语关联、去重及 imported 回执；这里 imported 表示评语保存，不代表建议执行。建议失败仍保留评语，不补发“评语导入失败”回执。每月采用首次成功领取的评语，后续新的 plan_id 不覆盖已保存月份；相同结果按既有去重返回。历史通过原方案状态显示执行/撤销，拒绝建议不删除评语。普通无步骤回答也领取即保存并回执。

## Room 与备份

Room **37→38**：

- `monthly_plans(bookId PRIMARY KEY, text)`，一个账本一行。
- `monthly_reviews(bookId,month PRIMARY KEY,localId UNIQUE,snapshotJson,submittedAt,planId)`，保留提交月、上传快照及建议方案关联。
- 两表有到 books 的归属外键；本机永久删除账本可整体清除，云端无对应删除命令。其他既有表/执行记录不修改。
- 月评快照使用既有版本化压缩文档封装，避免完整结构占用单行 CursorWindow。备份沿用全部表的一致性快照；恢复使用 ALL_MIGRATIONS。提醒开关作为有默认值的 ReminderPreferences 字段沿用备份。
- 历史快照不是账户/金融计划的实时业务引用，不参与其撤销冲突扫描；真实账本外键仍参与，不能通过撤销新建账本抹掉后来保存的评语。
- `DatabaseUpgradeTest` 新增37起点保留账本/预算/方案并验证新表；`BackupRoundTripTest` 补月计划和评语行恢复断言。Room 38 schema 文件待用户编译由 Room 生成，本轮不伪造 identity hash。

## `book.monthlyPlan.save` 适配检查表对应

| 检查项 | 对应实现 |
|---|---|
| schema/默认/null/未知/示例 | `MonthlyPlanInput` 严格 text 字符串，maxLength=20000；省略/null拒绝，空文保留行。注册表提供示例和业务说明；静态schema副本补代表定义，能力样例由用户生成 |
| 引用/目标 | 无引用输出、无选择器；唯一目标为任务账本，不跨账本；新建账本不能在同方案随后写入 |
| 前提/共享操作 | 账本存在且未回收、initialized、未归档；编辑页和命令均调用 saveMonthlyPlan；异常直接导致批次失败 |
| 确认 | INFORMATION；不加风险勾选或 USER_FACT；原文→新文、账本识别事实和步骤效果保留 |
| 比较 | book完整识别字段及计划原行/新行，保留精确文本与行不存在状态；按原模型排序；不增加时间戳或新随机身份 |
| 撤销 | 月计划行加入v3 journal；原行恢复原文，新行仅本机历史受限移除；当前完整行不同即阻止整批；沿用mediaWrites→事务、预览回滚/确认重跑、去重和UNDONE记录 |
| 本机UI | 杂志：粗细线编辑栏；手账：有行距的纸页与手写标题；小物：圆角资料卡。提交、历史、评语和建议使用主题内组件，全部中日文 |
| 验证 | MonthlyCommandTest覆盖原文空白/换行、预览回滚、真实相关变更重跑拒绝、确认、精确撤销；既有框架集中用例继续覆盖去重/永久边界，不重复建立矩阵 |

## 必要验证（待用户执行）

- `MonthlyCommandTest` 两项：月计划预览→执行→撤销与真实冲突；小账本汇总、仅回答、仅评语、评语+预算建议执行/撤销、账本隔离和计划快照。
- `ReminderSystemTest.monthlyReviewUsesFirstDayPreviousMonthAndPersistedDedup` 默认参数可跑；沿用时钟注入及隔离DataStore，不要求 system=true/真实系统通知：时刻前后、上月、持久去重、次日不补发、下月、关闭、已提交抑制。
- `ReminderSettingsFlowTest` 原保存流程增加月评默认开启/关闭保存断言；原 `CommandWorkflowExecutionTest` 可逆偏好组合明确同时关闭两类提醒，保留原测试“不请求系统授权”的业务意图。
- `DatabaseUpgradeTest.version37AddsMonthlyPlanAndReviewWithoutChangingExistingRows`；`BackupRoundTripTest`。
- `CommandFlowTest.monthlyPlanSubmissionReviewHistoryAndAnswerAcrossLooks`：`looks=all visual=true`，查看 `cloud-command-stage-1/*-monthly-plan.png`、`*-monthly-submit.png`、`*-monthly-review.png`、`*-monthly-history.png`、`*-answer.png`。从预算页编辑、历史手动入口和注入提醒跳转、生产任务准备/固定结果领取、主动预览并执行建议、历史及问答回看。
- 按需回归既有 CommandExecution/Planning/Finance/Workflow 与 ReminderPlan 单元测试；不增加完整外观、系统、性能专项。所有 Gradle 串行。
- 真实网站往返、实际系统通知点击和权限弹窗由用户设备实测。没有运行或查看本轮生成截图，不能把静态核对当成视觉通过。

能力清单已改变：需要用户在 android/ 运行原生成入口 `./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest`，重新生成两份 capabilities.valid.json。**本轮没有手写能力样例，也没有运行此命令。**

## 文件与实现选择

生产代码集中在新增 `data/command/MonthlyReview.kt`、`ui/features/command/MonthlyScreens.kt`，以及命令注册/导出/领取/撤销、CloudRepository、Room迁移、ReminderPlan/Repository/Coordinator、通知设置、预算入口与导航。金融批量撤销只排除历史月评快照的伪引用；既有效果类型名保持不变。测试与协议/规格/UI地图/README同步修改，完整清单以 git diff 为准。

实现选择：月计划按账本一份，月评保存原文快照；提醒独立默认开启；只评已结束月份；每月首次有效结果保留且不可被新结果覆盖。这些细节是本轮明确的实现取舍，未缩减六项功能范围。编译、运行与实际视觉效果仍待验收；未提交代码。

## 实现阶段静态核对

- 33个新增/修改Kotlin文件通过PSI语法解析，0语法错误；新改文件未发现中文紧跟未加花括号的字符串模板。不是类型检查或编译通过。
- v37导出schema在SQLite中创建后执行本次37→38 SQL，旧账本/预算值保留、新表可写、外键检查正常；不代替Room运行时迁移验证。
- JSON可解析；协议三副本一致，command-plan/月评schema及格式README副本一致；两份能力样例与HEAD原字节一致，待用户重新生成。
- git diff --check通过。未运行Gradle、模拟器、仪器测试或本轮视觉截图。

## 验收结果

2026-11-02 本机验收（模拟器 API 36），首轮即**通过**，无返工：

- 能力样例用 `CommandCapabilitiesTest` 生成入口重新生成；Room v38 schema 由编译生成（`android/app/schemas/.../38.json`）；单元测试全部通过；debug 与 androidTest APK 构建通过。
- `MonthlyCommandTest`、`CommandWorkflowExecutionTest`、`CommandFinanceExecutionTest`、`CommandExecutionTest`、`CommandLedgerExecutionTest`、`CommandPlanningExecutionTest`、`DatabaseUpgradeTest`（含 37→38）、`BackupRoundTripTest`、`ReminderSystemTest` 共 53 项通过。
- `CommandFlowTest` 与 `ReminderSettingsFlowTest`（`looks=all visual=true`）共 10 项通过；`BudgetRecurrenceFlowTest`/`BudgetRecurrenceTest` 15 项通过。
- 已查看三主题×中日文 `*-monthly-plan.png`、`*-monthly-submit.png`、`*-monthly-review.png`、`*-monthly-history.png`、`*-answer.png`：月计划编辑、预填提交页（收入/净消费/预算执行/月计划/上传项数量，声明不附具体交易与文件）、评语与可选建议预览入口、按月历史（当时月计划快照、建议执行状态）、纯回答无确认按钮，各主题风格独立且一致。
- 真实网站往返与真实通知送达仍为用户实测项，未声称已验证。
