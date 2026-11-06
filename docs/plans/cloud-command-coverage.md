# 云端办理写入路径全覆盖审计

审计日期：2026-11-03。基线：阶段7 `5216585`。状态：阶段8已验收（2026-11-03 本机编译与设备测试）。

共 **243 条写入路径**：命令 **111**、永久边界 **40**、内部编排 **92**。注册表为 **88 个命令**；触感反馈增量登记 `AppPreferences.setHapticsEnabled` 与 `AppViewModel.setHapticsEnabled` 两条路径，对应同一 `preferences.haptics` 命令。`cloud.task.discard` 与本机 `merchant.delete` 永久边界不变（forbidden 共59项）。未发现未下沉的UI DAO写入。

2026-11-06 触感增量待统一编译与验收：命令侧、展示、撤销、文档及快照已接入；分组 B 文件范围外的 `AppPreferences.commandValue`、`commandValues`、`applyCommandPreference` 须补上 `haptics` 分支，保留原值校验与 DataStore 原子效果凭据，才具备可执行的偏好命令。

### 金融本机查看路径更新（2026-11-04）

金融账户 UI 阶段 1–5 将本机入口改为：账户详情 → 账单、计划与金融规则 → 固定「总览 / 账单 / 计划 / 管理」。计算规则、还款记录、额度与恢复、版本历史及金融草稿在管理目录；网贷另有借入与合同。计划区为摘要列表 → 详情 → 期次；新建从“添加还款安排”选择方式，合同无候选可建立计划后返回确认关联。“循环还款计划”仍使用原余额池模型。云端对象及字段同步计划/共同月付款术语，不新增金融对象跳转；金融草稿继续用 FinancialDraftResume。还款基本区与条件展开、单目标建议/联动和账单双状态已接入；云端还款预览先看付款账户、实扣、清偿及分配目标，完整能力保留。只简化录入，不放宽清偿确认要求；正式写入/试运行/确认/撤销协议不变。规则新版本与方案比较接入共用只读展示投影，基线取命令步骤快照与办理比较归档，仍保留完整前后值；本机规则、方案调整、共同月付款、批量转换及借还为全屏流程。预测/手填/余额池、额度配置及重核亦进入统一全屏流程，计划影响先显示变化和保留范围。

按[用户决定 7.1](financial-account-ui-plan.md#71-用户决定2026-11-04)，本轮不添加 `CommandPreviewScreen` 金融对象导航回调或 `CommandFlowTest` 对象跳转流程；用户自行进入对应账户查看。已核对 `ui/features/command/`：现有金融预览显示的是业务对象、步骤和事实，没有引用被移除的旧 tab 标签或页尾入口，因此本阶段无需修改云端 UI 代码。此次文档路径更新不代表重新执行云端设备验收。

## 口径与复查方法

路径按“源码函数声明＋有不同准入规则的分支”计数，不按DAO调用次数、业务命令数或页面按钮数计数。同一保存函数有多个调用层，分别列出；同名重载分别列出。可恢复与永久删除分支拆开，故三类互斥可相加。表中“内部”是缓存/默认值/提交或交接流程的实现步骤，不是可以作为独立账本命令下发的用户操作；已有用户意图的对应命令写在说明里，不能借此隐藏未覆盖的业务功能。

先用以下rg全仓盘点，再结合函数体及调用处核对（不仅搜索含Repository字样的文件）；以Kotlin PSI确定声明归属，检查DAO的@Upsert/@Insert/@Update/@Delete及DELETE/UPDATE/INSERT Query，并沿调用追踪包装函数。DAO原语在其领域函数下归属，不另计一次。

```sh
rg -n 'fun |class .*ViewModel' android/app/src/main/java/com/yohaku/expensetracker/{data,ui}
rg -n '@(Upsert|Insert|Update|Delete)|@Query\("(DELETE|UPDATE|INSERT)' android/app/src/main/java/com/yohaku/expensetracker/data
rg -n '(dao|Dao\(\)|db)\.|execSQL|writableDatabase|storage\.edit|deleteRecursively|outputStream' android/app/src/main/java/com/yohaku/expensetracker/{data,ui}
rg -n 'preferences\.|saveCsvProfile|saveDraft|storeFinancialDraft|commitFinancialDraft' android/app/src/main/java/com/yohaku/expensetracker/ui
```

范围包含 data/repository/ 的全部扩展（含名称不叫Repository的文件）、data/command/MonthlyReview.kt中的Repository扩展、AppPreferences与BackupPreferences、三个ViewModel（包括嵌在BackupScreen.kt的BackupViewModel），以及云端/执行器/备份的对外写入口。Room迁移/建库属于内部结构演进，不注册命令；受限撤销journal/恢复适配器只被本机执行器调用，按执行器路径归类。纯读/计算、网络transport、内存状态与DAO声明不计写入路径。

## 按领域覆盖表

### 账本、账户、分类与联系人

| 写入路径 | 分类 → 命令或边界 | 说明 |
|---|---|---|
| [LedgerRepository.kt:81](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L81) `createBook` | 命令 → book.create | 新账本不能在同一方案继续写入；选择它另用 book.select。 |
| [LedgerRepository.kt:85](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L85) `updateBook` | 命令 → book.update / book.completeSetup | 信息、外观、预算、初始化完成；会物理清预算的币种重置被命令拒绝。 |
| [LedgerRepository.kt:97](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L97) `updateAppearance` | 命令 → book.update | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [LedgerRepository.kt:101](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L101) `setupStep` | 命令 → book.completeSetup | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [BookLifecycleRepository.kt:32](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BookLifecycleRepository.kt#L32) `commitBookOperation` · 可恢复分支 | 命令 → book.lifecycle | ARCHIVE/UNARCHIVE/TRASH/RESTORE；内核由执行器持媒体锁后调用。 |
| [BookLifecycleRepository.kt:32](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BookLifecycleRepository.kt#L32) `commitBookOperation` · PURGE分支 | 边界 → book.purge | 用户永久删除账本，不开放。 |
| [BookLifecycleRepository.kt:37](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BookLifecycleRepository.kt#L37) `commitBookOperationInTransaction` · 可恢复分支 | 命令 → book.lifecycle | ARCHIVE/UNARCHIVE/TRASH/RESTORE；内核由执行器持媒体锁后调用。 |
| [BookLifecycleRepository.kt:37](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BookLifecycleRepository.kt#L37) `commitBookOperationInTransaction` · PURGE分支 | 边界 → book.purge | 用户永久删除账本，不开放。 |
| [BookLifecycleRepository.kt:56](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BookLifecycleRepository.kt#L56) `purgeWholeBook` | 边界 → book.purge / book.emptyTrash | 整本账本物理清理和清空回收站；含所属金融、云端、月评资料。 |
| [BookLifecycleRepository.kt:71](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BookLifecycleRepository.kt#L71) `commitEmptyBookTrash` | 边界 → book.purge / book.emptyTrash | 整本账本物理清理和清空回收站；含所属金融、云端、月评资料。 |
| [BookLifecycleRepository.kt:76](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BookLifecycleRepository.kt#L76) `commitEmptyBookTrashInTransaction` | 边界 → book.purge / book.emptyTrash | 整本账本物理清理和清空回收站；含所属金融、云端、月评资料。 |
| [LedgerRepository.kt:106](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L106) `saveAccount` | 命令 → account.save | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [AccountManagementRepository.kt:70](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AccountManagementRepository.kt#L70) `commitAccountOperation` · ACTIVATE/DEACTIVATE | 命令 → account.setActive | 只开放启停，无账户回收站。 |
| [AccountManagementRepository.kt:70](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AccountManagementRepository.kt#L70) `commitAccountOperation` · DELETE | 边界 → account.delete | DELETE 会物理删除账户。 |
| [LedgerRepository.kt:115](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L115) `saveCategory` | 命令 → category.save / category.reparent | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [LedgerRepository.kt:140](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L140) `toggleCategoryFavorite` | 命令 → category.setFavorite | 云端传明确favorite，不用无条件toggle。 |
| [CategoryManagementRepository.kt:81](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/CategoryManagementRepository.kt#L81) `commitCategoryOperation` · ARCHIVE/UNARCHIVE/REPARENT | 命令 → category.lifecycle / category.reparent | 归档/恢复/层级；编辑从属关系纳入撤销。 |
| [CategoryManagementRepository.kt:81](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/CategoryManagementRepository.kt#L81) `commitCategoryOperation` · MERGE_DELETE/DELETE_EMPTY | 边界 → category.mergeDelete / category.deleteEmpty | 迁移后删除分类仍是永久删除；替代组合保留归档源分类及空预算历史。 |
| [AnalysisRepository.kt:16](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AnalysisRepository.kt#L16) `saveMerchant` | 命令 → merchant.save / merchant.setActive | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [EventPlaces.kt:36](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/EventPlaces.kt#L36) `saveMerchantWithLocations` | 命令 → merchant.save + merchantLocation.save | 地点逐个保存；新商户消费地点候选清理是关联编辑内部维护，不提供清表操作。 |
| [AnalysisRepository.kt:22](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AnalysisRepository.kt#L22) `saveMerchantLocation` | 命令 → merchantLocation.save | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [AnalysisRepository.kt:53](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AnalysisRepository.kt#L53) `removeMerchantLocation` | 边界 → merchantLocation.remove | 保存地点物理移除；可编辑地点或停用商户。 |
| [AnalysisRepository.kt](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AnalysisRepository.kt) `deleteMerchant` | 边界 → merchant.delete | 本机物理删除商户及保存地点、记录关联；模板/周期/草稿解除引用，历史文字保留。云端改用 merchant.setActive false。 |
| [TreatRepository.kt:18](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/TreatRepository.kt#L18) `saveFriend` | 命令 → friend.save | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [AnalysisRepository.kt:48](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AnalysisRepository.kt#L48) `setFriendActive` | 命令 → friend.setActive | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |

### 记录、转账、退款、模板和草稿

| 写入路径 | 分类 → 命令或边界 | 说明 |
|---|---|---|
| [LedgerRepository.kt:144](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L144) `saveEntry` | 命令 → entry.create / entry.update | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [LedgerChangeReview.kt:95](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerChangeReview.kt#L95) `commitEntryReview` | 命令 → entry.create / entry.update / entry.confirmActual | 同一准备/提交路径，也被实例支付、到账、流水补记复用。 |
| [CurrencyTransactions.kt:74](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/CurrencyTransactions.kt#L74) `confirmSettlement` | 命令 → entry.confirmActual | 实际结算端与入账日期，普通编辑也复用相同规则。 |
| [CurrencyTransactions.kt:90](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/CurrencyTransactions.kt#L90) `saveRefund` | 命令 → entry.create / entry.update（REFUND） | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [LedgerRepository.kt:205](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L205) `saveMovement` | 命令 → entry.create（TRANSFER）/ financialBorrowing.save / financialRepayment.save | 旧程序化资金流入口，UI无独立调用；当前云端分别走专用领域校验，不伪装借贷。 |
| [LedgerChangeReview.kt:17](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerChangeReview.kt#L17) `writeChange` | 内部 → 内部编排 | 事务内统一写记录、入账、分摊、关系及revision；是上述命令执行内核，不是单独用户操作。 |
| [BatchEntries.kt:18](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BatchEntries.kt#L18) `writeEntryBatchInTransaction` | 内部 → 内部编排 | 记录命令的批量写入与金融刷新内核。 |
| [BatchEntryCategories.kt:24](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BatchEntryCategories.kt#L24) `writeEntryCategoriesInTransaction` | 内部 → 内部编排 | 分类/备注批量优化内核；不开放任意行更新。 |
| [LedgerRepository.kt:228](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L228) `deleteEntry` | 命令 → entry.lifecycle TRASH | 旧名称deleteEntry实际上移入回收站。 |
| [LedgerRepository.kt:229](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L229) `restoreEntry` | 命令 → entry.lifecycle RESTORE | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [EntryLifecycleRepository.kt:70](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/EntryLifecycleRepository.kt#L70) `commitEntryLifecycle` · TRASH/RESTORE | 命令 → entry.lifecycle | 原消费及关联退款删除组、恢复期限、预算/流水/周期连带影响。 |
| [EntryLifecycleRepository.kt:70](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/EntryLifecycleRepository.kt#L70) `commitEntryLifecycle` · PURGE/EMPTY | 边界 → entry.purge / entry.emptyTrash | 包含物理清理的分支不能从云端调用。 |
| [EntryLifecycleRepository.kt:90](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/EntryLifecycleRepository.kt#L90) `purgeEntryGroup` | 边界 → entry.purge | 包含从属事实移除/有引用时匿名墓碑，不因保留墓碑而开放。 |
| [LedgerRepository.kt:230](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L230) `purgeExpired` | 边界 → trash.purgeExpired | 本机既有到期清理策略；云端不能触发。 |
| [EntryLifecycleRepository.kt:113](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/EntryLifecycleRepository.kt#L113) `trashEntrySet` | 内部 → 内部编排 | 软删除组公共内核，被entry.lifecycle/csvBatch.undo调用，不是独立删行接口。 |
| [TemplateRepository.kt:41](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/TemplateRepository.kt#L41) `saveTemplate` | 命令 → template.save | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [TemplateRepository.kt:52](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/TemplateRepository.kt#L52) `moveTemplate` | 命令 → template.move | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [TemplateRepository.kt:51](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/TemplateRepository.kt#L51) `deleteTemplate` | 边界 → template.delete | 模板无回收站；改用编辑或排序。 |
| [LedgerRepository.kt:220](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L220) `saveDraft` | 内部 → 内部编排 | 普通表单自动持久化临时输入；新建待补完意图用entry.prepareDraft，已有分类迁移用entryDraft.reclassify；完成记录仍走记录命令。 |
| [LedgerRepository.kt:224](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt#L224) `clearDraft` · 独立放弃 | 边界 → entryDraft.discard | 独立放弃现有输入禁止；成功提交消费匹配草稿属于提交内核（下表另列）。 |
| [EntryDrafts.kt:70](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/EntryDrafts.kt#L70) `reclassifyEntryDraft` | 命令 → entryDraft.reclassify | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |

### 预算、活动、周期与预计收入

| 写入路径 | 分类 → 命令或边界 | 说明 |
|---|---|---|
| [BudgetRepository.kt:22](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BudgetRepository.kt#L22) `commitBudget` | 命令 → budget.save / budget.saveBook | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [BudgetRepository.kt:28](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BudgetRepository.kt#L28) `setActivityMode` | 命令 → activity.setMode / activity.setCurrent | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [BudgetRepository.kt:34](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BudgetRepository.kt#L34) `saveActivity` | 命令 → activity.save | 旧简化保存入口的同一用户意图；命令采用完整previewActivity/commitActivity。 |
| [ActivityRepository.kt:36](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/ActivityRepository.kt#L36) `commitActivity` | 命令 → activity.save / activity.end | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [ActivityManagementRepository.kt:39](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/ActivityManagementRepository.kt#L39) `commitActivityDeletion` | 命令 → activity.lifecycle TRASH | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [ActivityManagementRepository.kt:55](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/ActivityManagementRepository.kt#L55) `restoreActivity` | 命令 → activity.lifecycle RESTORE | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [ActivityRepository.kt:73](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/ActivityRepository.kt#L73) `commitReserve` | 命令 → activityReserve.save / activityReserve.release | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [TreatRepository.kt:24](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/TreatRepository.kt#L24) `addParticipant` | 命令 → activityParticipant.add | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [TreatRepository.kt:68](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/TreatRepository.kt#L68) `writeTreat` | 内部 → 内部编排 | 本人/他人请客的分摊关系写入内核；完整编辑替换份额，不独立删除请客。 |
| [TreatRepository.kt:78](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/TreatRepository.kt#L78) `commitOtherTreat` | 命令 → activityTreat.save | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [TreatRepository.kt:83](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/TreatRepository.kt#L83) `removeOtherTreat` | 命令 → activityTreat.lifecycle TRASH | 仅设deletedAt，非物理删除。 |
| [TreatRepository.kt:89](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/TreatRepository.kt#L89) `restoreOtherTreat` | 命令 → activityTreat.lifecycle RESTORE | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [RecurrenceRepository.kt:16](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L16) `saveRecurrence` | 命令 → recurrence.save | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [RecurrenceRepository.kt:148](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L148) `setRecurrenceActive` | 命令 → recurrence.setActive | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [RecurrenceRepository.kt:41](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L41) `ensureOccurrences` | 内部 → 内部编排 | 由规则及月份物化待办，页面月份加载/规则保存时自动触发；云端需要引用某实例用occurrence.resolve。 |
| [RecurrenceRepository.kt:58](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L58) `cancelOccurrence` | 命令 → occurrence.lifecycle | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [RecurrenceRepository.kt:68](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L68) `restoreOccurrence` | 命令 → occurrence.lifecycle | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [RecurrenceRepository.kt:76](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L76) `unlinkOccurrence` | 命令 → occurrence.unlink | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [RecurrenceRepository.kt:81](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L81) `recheckOccurrences` | 内部 → 内部编排 | 记录回收/恢复后维护实例有效性，不能把它当成独立用户按钮。 |
| [RecurrenceRepository.kt:114](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L114) `consumeOccurrencePaymentDraft` | 内部 → 内部编排 | 成功关联/支付后消费匹配的支付草稿及暂存关系；非任意草稿删除。 |
| [RecurrenceRepository.kt:118](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L118) `commitOccurrenceLink` | 命令 → occurrence.link | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [RecurrenceRepository.kt:125](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L125) `linkOccurrenceBatch` | 命令 → occurrence.link | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [RecurrenceRepository.kt:139](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L139) `commitOccurrencePayment` | 命令 → occurrence.pay | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [RecurrenceRepository.kt:154](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt#L154) `updateOccurrenceCategories` | 命令 → occurrence.update | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [ExpectedIncomeRepository.kt:37](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/ExpectedIncomeRepository.kt#L37) `commitExpectedIncome` | 命令 → expectedIncome.save | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [ExpectedIncomeRepository.kt:41](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/ExpectedIncomeRepository.kt#L41) `cancelExpectedIncome` | 命令 → expectedIncome.cancel | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [ExpectedIncomeRepository.kt:63](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/ExpectedIncomeRepository.kt#L63) `commitExpectedIncomeArrival` | 命令 → expectedIncome.arrive | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |

### 金融、合同、安排、额度及金融草稿

| 写入路径 | 分类 → 命令或边界 | 说明 |
|---|---|---|
| [FinancialRepository.kt:64](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialRepository.kt#L64) `commitFinancialRule` | 命令 → financialRule.addVersion | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FinancialRepository.kt:127](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialRepository.kt#L127) `commitManualFinancialPlan` | 命令 → financialManualPlan.save | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FinancialRepository.kt:154](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialRepository.kt#L154) `commitFinancialBillConfirmation` | 命令 → financialBill.confirm | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FinancialRepository.kt:168](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialRepository.kt#L168) `validateFinancialRule` | 命令 → financialRule.validate | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FinancialPredictions.kt:162](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPredictions.kt#L162) `commitFinancialPrediction` | 命令 → financialPrediction.save / financialPool.save | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FinancialLedgerRepository.kt:189](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialLedgerRepository.kt#L189) `commitFinancialLedger` | 命令 → financialBorrowing.save / financialRepayment.save / financialRepayment.confirm | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FinancialLedgerRepository.kt:197](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialLedgerRepository.kt#L197) `linkFinancialContractPlan` | 命令 → financialContract.linkPlan | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FinancialArrangements.kt:223](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialArrangements.kt#L223) `commitFinancialArrangement` | 命令 → financialArrangement.record | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FinancialArrangementReconciliation.kt:240](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialArrangementReconciliation.kt#L240) `commitArrangementReconciliation` | 命令 → financialArrangement.reconcile | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FinancialPoolPaymentChanges.kt:69](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPoolPaymentChanges.kt#L69) `commitPoolPaymentChange` | 命令 → financialPool.changePayment | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [BatchRevolvingArrangements.kt:92](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BatchRevolvingArrangements.kt#L92) `commitBatchRevolving` | 命令 → financialBatchRevolving.apply | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [CreditLimitRepository.kt:87](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/CreditLimitRepository.kt#L87) `commitCreditLimit` | 命令 → creditLimit.save / creditLimitGroup.save / creditLimitRecovery.confirm / creditLimitObservation.record | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [CreditLimitRepository.kt:95](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/CreditLimitRepository.kt#L95) `voidCreditLimitRecovery` | 命令 → creditLimitRecovery.void | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FinancialDrafts.kt:31](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialDrafts.kt#L31) `reclassifyFinancialDraft` | 命令 → financialDraft.reclassify | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [BatchRevolvingArrangements.kt:115](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/BatchRevolvingArrangements.kt#L115) `undoBatchRevolving` | 命令 → financialBatchRevolving.undo | 阶段5用户已批准为完整历史下的关系恢复；仅恢复自身无冲突批次，不授权独立删除金融事实。 |
| [FinancialPlanHistory.kt:10](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPlanHistory.kt#L10) `archiveFinancialPlan` | 内部 → 内部编排 | 追加计划修订归档，保留审计，不是面向用户的归档/删除按钮。 |
| [FinancialPlanHistory.kt:16](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPlanHistory.kt#L16) `archiveExistingPlan` | 内部 → 内部编排 | 追加计划修订归档，保留审计，不是面向用户的归档/删除按钮。 |
| [FinancialPlanImpacts.kt:57](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPlanImpacts.kt#L57) `writeFinancialPlanImpacts` | 内部 → 内部编排 | 记录修改后的从属预测状态维护；两个重载均计入。 |
| [FinancialPlanImpacts.kt:61](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPlanImpacts.kt#L61) `writeFinancialPlanImpacts` | 内部 → 内部编排 | 记录修改后的从属预测状态维护；两个重载均计入。 |
| [FinancialPredictions.kt:168](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPredictions.kt#L168) `writeFinancialPrediction` | 内部 → 内部编排 | 预测提交内核，写计划、逐期账单与撤回状态。 |
| [FinancialPools.kt:199](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPools.kt#L199) `writeFinancialPoolAdoption` | 内部 → 内部编排 | 采纳及来源变更后的池刷新，由完整预测/记录操作触发。 |
| [FinancialPools.kt:213](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPools.kt#L213) `refreshFinancialPools` | 内部 → 内部编排 | 采纳及来源变更后的池刷新，由完整预测/记录操作触发。 |
| [CardPayments.kt:48](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/CardPayments.kt#L48) `refreshCardEntryPrediction` | 内部 → 内部编排 | 记账付款方式/退款变化派生预测，不新增用户消费。 |
| [FinancialDrafts.kt:13](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialDrafts.kt#L13) `storeFinancialDraft` | 内部 → 内部编排 | 各金融表单自动保存未完成输入；最终提交按各领域命令，内容不自动上传。 |
| [FinancialDrafts.kt:20](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialDrafts.kt#L20) `commitFinancialDraft` | 内部 → 内部编排 | 原子业务提交并消费对应草稿；失败保留输入。 |
| [FinancialDrafts.kt:81](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialDrafts.kt#L81) `commitFinancialDraft` | 内部 → 内部编排 | 原子业务提交并消费对应草稿；失败保留输入。 |
| [FinancialDrafts.kt:25](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialDrafts.kt#L25) `discardFinancialDraft` | 边界 → financialDraft.discard | 永久丢弃尚未提交的表单输入，留本机操作。 |
| [FinancialDrafts.kt:82](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialDrafts.kt#L82) `discardFinancialDraft` | 边界 → financialDraft.discard | 永久丢弃尚未提交的表单输入，留本机操作。 |

### 文件、媒体、导入和分析设置

| 写入路径 | 分类 → 命令或边界 | 说明 |
|---|---|---|
| [AnalysisRepository.kt:12](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AnalysisRepository.kt#L12) `saveAnalysisPreferences` | 命令 → preferences.analysis | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [FileWorkflowRepository.kt:147](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FileWorkflowRepository.kt#L147) `saveReceiptItems` | 命令 → receipt.items.save | 云端直接给商品明细，复用FileProtocols校验；旧导出包回填身份由手机管理。 |
| [FileWorkflowRepository.kt:164](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FileWorkflowRepository.kt#L164) `editReceiptItems` | 命令 → receipt.items.save | 云端直接给商品明细，复用FileProtocols校验；旧导出包回填身份由手机管理。 |
| [FileWorkflowRepository.kt:190](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FileWorkflowRepository.kt#L190) `saveDirectReceiptItems` | 命令 → receipt.items.save | 云端直接给商品明细，复用FileProtocols校验；旧导出包回填身份由手机管理。 |
| [StatementReviews.kt:72](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/StatementReviews.kt#L72) `acceptStatementDifference` | 命令 → statement.accept | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [StatementReviews.kt:78](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/StatementReviews.kt#L78) `clearStatementDecision` | 命令 → statement.unlink | 解除可恢复差异关系；不删除流水原件或记录。 |
| [StatementReviews.kt:84](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/StatementReviews.kt#L84) `deleteStatement` | 边界 → statement.delete | 流水批次物理删除，禁止；不是unlink。 |
| [FileWorkflowRepository.kt:52](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FileWorkflowRepository.kt#L52) `saveStatement` | 内部 → 内部编排 | statement类型领取器校验结果后保存流水原文件；是文件转换任务接收流程，不是当前command-plan递归发起/导入远端结果。补记/实际确认另由statement.supplement/confirmActual。 |
| [FileWorkflowRepository.kt:98](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/FileWorkflowRepository.kt#L98) `recordSavedReceiptExport` | 内部 → 内部编排 | 确认云端已收到小票包后登记来源证据；不是修改用户账本的独立操作。 |
| [CsvImportRepository.kt:50](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/CsvImportRepository.kt#L50) `importCsv` | 命令 → entry.create + category.save（按需） | 导入意图编译成记录序列，保留逐笔确认/撤销；不伪造CSV批次/列对应或让手机运行云端解析器。 |
| [CsvImportRepository.kt:89](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/CsvImportRepository.kt#L89) `undoCsvBatch` | 命令 → csvBatch.undo | 批次有效记录及关联退款进回收站，保留后来编辑。 |
| [CsvImportRepository.kt:101](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/CsvImportRepository.kt#L101) `saveCsvProfile` | 内部 → 内部编排 | CsvImportViewModel.previewNow自动写文件signature→解析设置缓存；无独立“保存配置”用户动作，不是漏掉全局偏好命令。 |
| [MediaRepository.kt:33](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/MediaRepository.kt#L33) `importMedia` | 命令 → attachment.attachSource / entry.prepareDraft(media) | 本任务源文件可自动附为普通附件；选择手机其他文件/小票图片/拍照必须interactive，由本机用户选择并保存。 |
| [MediaRepository.kt:35](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/MediaRepository.kt#L35) `importMediaResult` | 命令 → attachment.attachSource / entry.prepareDraft(media) | 本任务源文件可自动附为普通附件；选择手机其他文件/小票图片/拍照必须interactive，由本机用户选择并保存。 |
| [MediaRepository.kt:88](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/MediaRepository.kt#L88) `importUri` | 命令 → attachment.attachSource / entry.prepareDraft(media) | 本任务源文件可自动附为普通附件；选择手机其他文件/小票图片/拍照必须interactive，由本机用户选择并保存。 |
| [MediaRepository.kt:66](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/MediaRepository.kt#L66) `commitImportedMediaInTransaction` | 内部 → 内部编排 | 媒体导入内核，登记文件元数据和暂存会话；非任意文件/路径接口。 |
| [MediaRepository.kt:107](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/MediaRepository.kt#L107) `writeMedia` | 内部 → 内部编排 | 提交记录时绑定已有暂存附件/小票及消费暂存关系；普通云端entry编辑保持旧附件，独立移除仍属attachment.delete/receipt.delete边界。 |
| [MediaRepository.kt:120](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/MediaRepository.kt#L120) `collectUnusedMedia` | 边界 → media.collectUnused | 本机策略清理无引用媒体，不接受云端步骤触发。 |
| [MediaRepository.kt:125](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/MediaRepository.kt#L125) `collectUnusedMediaInTransaction` | 边界 → media.collectUnused | 本机策略清理无引用媒体，不接受云端步骤触发。 |

### 偏好、月计划与备份

| 写入路径 | 分类 → 命令或边界 | 说明 |
|---|---|---|
| [AppPreferences.kt:23](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L23) `setPhotoSaveOriginal` | 命令 → preferences.photoStorage | post-commit；权限引导需要手机操作，不能由助手授权。 |
| [AppPreferences.kt](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt) `setHapticsEnabled` | 命令 → preferences.haptics | enabled 必填；本机全账本共用，默认 true；post-commit 原值比较、效果凭据和撤销同照片偏好，无权限引导。 |
| [AppPreferences.kt:28](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L28) `setAutomaticReferenceRates` | 命令 → preferences.automaticRates | post-commit；权限引导需要手机操作，不能由助手授权。 |
| [AppPreferences.kt:36](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L36) `saveReminderSettings` | 命令 → preferences.reminders | post-commit；权限引导需要手机操作，不能由助手授权。 |
| [AppPreferences.kt:50](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L50) `setForegroundLocation` | 命令 → preferences.location | post-commit；权限引导需要手机操作，不能由助手授权。 |
| [AppPreferences.kt:71](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L71) `select` | 命令 → book.select | post-commit；权限引导需要手机操作，不能由助手授权。 |
| [AppPreferences.kt:30](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L30) `saveReferenceRateCache` | 内部 → 内部编排 | 公开汇率缓存/尝试时间；用户意图是换算或开启自动汇率，不允许云端自填缓存。 |
| [ReferenceRates.kt:41](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/ReferenceRates.kt#L41) `lookup` | 内部 → 内部编排 | 获取公开报价并写缓存，预览事务外冻结；非账本金融事实写入。 |
| [ReferenceRates.kt:71](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/ReferenceRates.kt#L71) `cashReferenceRates` | 内部 → 内部编排 | 获取公开报价并写缓存，预览事务外冻结；非账本金融事实写入。 |
| [AppPreferences.kt:44](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L44) `saveReminderRuntime` | 内部 → 内部编排 | 提醒送达去重和设备本地调度计划；不是助手可指定的用户事实。 |
| [AppPreferences.kt:62](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L62) `clearSelection` | 内部 → 内部编排 | 本机生命周期后的选择/失效账本偏好清理；无独立云端清除全部设置接口。 |
| [AppPreferences.kt:63](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L63) `pruneBookPreferences` | 内部 → 内部编排 | 本机生命周期后的选择/失效账本偏好清理；无独立云端清除全部设置接口。 |
| [AppPreferences.kt:72](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L72) `saveSetup` | 内部 → 内部编排 | 首次设置表单的草稿保存/成功消费；未建立账本时不能创建办理任务。最终设置用book.create/update/completeSetup。 |
| [AppPreferences.kt:73](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L73) `clearSetup` | 内部 → 内部编排 | 首次设置表单的草稿保存/成功消费；未建立账本时不能创建办理任务。最终设置用book.create/update/completeSetup。 |
| [AppPreferences.kt:90](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L90) `applyCommandPreference` | 内部 → 内部编排 | 后续效果内核，在同次DataStore.edit写偏好和effectId凭据；只供已确认方案/本机撤销调用。 |
| [AppPreferences.kt:118](../../android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt#L118) `restoreBackup` | 边界 → backup.restore | BackupPreferences只是序列化快照DTO（validate只读）；全部字段恢复由此入口清库重写偏好，不接受命令。 |
| [MonthlyReview.kt:34](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/MonthlyReview.kt#L34) `saveMonthlyPlan` | 命令 → book.monthlyPlan.save | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [BackupService.kt:21](../../android/app/src/main/java/com/yohaku/expensetracker/data/backup/BackupService.kt#L21) `export` | 命令 → backup.export | interactive由用户选目的地；导出开始时的全库/文件/偏好。 |
| [BackupService.kt:52](../../android/app/src/main/java/com/yohaku/expensetracker/data/backup/BackupService.kt#L52) `exportDocument` | 命令 → backup.export | interactive由用户选目的地；导出开始时的全库/文件/偏好。 |
| [BackupService.kt:42](../../android/app/src/main/java/com/yohaku/expensetracker/data/backup/BackupService.kt#L42) `exportSnapshotInTransaction` | 内部 → 内部编排 | 备份专属数据库副本与完成标记；不改主库业务事实。 |
| [BackupService.kt:84](../../android/app/src/main/java/com/yohaku/expensetracker/data/backup/BackupService.kt#L84) `snapshotDatabase` | 内部 → 内部编排 | 备份专属数据库副本与完成标记；不改主库业务事实。 |
| [BackupService.kt:72](../../android/app/src/main/java/com/yohaku/expensetracker/data/backup/BackupService.kt#L72) `inspect` | 边界 → backup.restore | 检查恢复包产生暂存副本、迁移副本和偏好，再由本机恢复入口替换；不注册为云端操作。 |
| [BackupService.kt:122](../../android/app/src/main/java/com/yohaku/expensetracker/data/backup/BackupService.kt#L122) `prepareRestore` | 边界 → backup.restore | 检查恢复包产生暂存副本、迁移副本和偏好，再由本机恢复入口替换；不注册为云端操作。 |
| [RestoreFiles.kt:33](../../android/app/src/main/java/com/yohaku/expensetracker/data/backup/RestoreFiles.kt#L33) `replace` | 边界 → backup.restore | 本机恢复原子替换与中断回滚，不是任意删文件能力。 |
| [RestoreFiles.kt:52](../../android/app/src/main/java/com/yohaku/expensetracker/data/backup/RestoreFiles.kt#L52) `recover` | 边界 → backup.restore | 本机恢复原子替换与中断回滚，不是任意删文件能力。 |
| [RestoreFiles.kt:19](../../android/app/src/main/java/com/yohaku/expensetracker/data/backup/RestoreFiles.kt#L19) `recoverOnStartup` | 边界 → backup.restore | 本机恢复原子替换与中断回滚，不是任意删文件能力。 |

### ViewModel 写入动作

| 写入路径 | 分类 → 命令或边界 | 说明 |
|---|---|---|
| [AppViewModel.kt](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt) `setHapticsEnabled` | 命令 → preferences.haptics | 本机 UI 写入口；同一全账本偏好，触感播放本身不计数据写路径。 |
| [AppViewModel.kt:101](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L101) `createBook` | 命令 → book.create + book.select | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [AppViewModel.kt:113](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L113) `selectBook` | 命令 → book.select | 选择偏好；提醒导航与月评预填是本机入口编排。 |
| [AppViewModel.kt:106](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L106) `openReminderBook` | 命令 → book.select | 选择偏好；提醒导航与月评预填是本机入口编排。 |
| [AppViewModel.kt:119](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L119) `commitBookOperation` · 可恢复分支 | 命令 → book.lifecycle | 可恢复生命周期动作；清选择/提醒重建为本机提交后维护。 |
| [AppViewModel.kt:119](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L119) `commitBookOperation` · PURGE分支 | 边界 → book.purge | 永久删除分支。 |
| [AppViewModel.kt:137](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L137) `updateBook` | 命令 → book.update / book.completeSetup | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [AppViewModel.kt:138](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L138) `appearance` | 命令 → book.update / book.completeSetup | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [AppViewModel.kt:140](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L140) `setupStep` | 命令 → book.completeSetup | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [AppViewModel.kt:139](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L139) `saveSetup` | 内部 → 内部编排 | 首次/记账表单持久化和恢复；不是独立执行账本事实的操作。 |
| [AppViewModel.kt:59](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L59) `openDraft` | 内部 → 内部编排 | 首次/记账表单持久化和恢复；不是独立执行账本事实的操作。 |
| [AppViewModel.kt:190](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L190) `persistDraft` | 内部 → 内部编排 | 首次/记账表单持久化和恢复；不是独立执行账本事实的操作。 |
| [AppViewModel.kt:158](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L158) `resumeEntry` | 内部 → 内部编排 | 首次/记账表单持久化和恢复；不是独立执行账本事实的操作。 |
| [AppViewModel.kt:396](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L396) `keepEntry` | 内部 → 内部编排 | 首次/记账表单持久化和恢复；不是独立执行账本事实的操作。 |
| [AppViewModel.kt:141](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L141) `leaveEntry` | 内部 → 内部编排 | 保留草稿分支自动保存临时输入。 |
| [AppViewModel.kt:141](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L141) `leaveEntry` · 放弃分支 | 边界 → entryDraft.discard | 放弃输入及媒体维护只能本机明确发起。 |
| [AppViewModel.kt:395](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L395) `discardEntry` · 放弃分支 | 边界 → entryDraft.discard | 放弃输入及媒体维护只能本机明确发起。 |
| [AppViewModel.kt:152](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L152) `startEntry` | 命令 → entry.create / entry.prepareDraft | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:298](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L298) `startTransfer` | 命令 → entry.create / entry.prepareDraft | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:308](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L308) `startRefund` | 命令 → entry.create / entry.prepareDraft | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:159](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L159) `editEntry` | 命令 → entry.update | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:303](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L303) `editTransfer` | 命令 → entry.update | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:232](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L232) `startTemplate` | 命令 → template.createEntry / entry.prepareDraft | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:282](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L282) `startOccurrence` | 命令 → occurrence.pay / entry.prepareDraft | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:266](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L266) `confirmOccurrence` | 命令 → occurrence.pay / entry.prepareDraft | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:314](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L314) `confirmActual` | 命令 → entry.confirmActual | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:356](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L356) `saveEntry` | 命令 → entry.create / entry.update | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:374](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L374) `confirmNegativeBalance` | 命令 → entry.create / entry.update | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:383](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L383) `confirmEntryReview` | 命令 → entry.create / entry.update | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:208](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L208) `confirmBudget` | 命令 → budget.save / budget.saveBook | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:219](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L219) `confirmOccurrenceLink` | 命令 → occurrence.link | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:320](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L320) `importFiles` | 命令 → attachment.attachSource / entry.prepareDraft(media) | 打开表单可只产生草稿；实际保存复用Repository；事实/风险由本机确认，不能预填勾选。 |
| [AppViewModel.kt:174](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L174) `changeEntry` | 内部 → 内部编排 | 表单编辑、默认值和定位候选自动持久化；最终意图由记录/草稿命令处理。媒体移除另受永久边界限制。 |
| [AppViewModel.kt:182](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L182) `switchEntryKind` | 内部 → 内部编排 | 表单编辑、默认值和定位候选自动持久化；最终意图由记录/草稿命令处理。媒体移除另受永久边界限制。 |
| [AppViewModel.kt:288](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L288) `changeTransferAmount` | 内部 → 内部编排 | 表单编辑、默认值和定位候选自动持久化；最终意图由记录/草稿命令处理。媒体移除另受永久边界限制。 |
| [AppViewModel.kt:165](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L165) `offerLocatedMerchant` | 内部 → 内部编排 | 表单编辑、默认值和定位候选自动持久化；最终意图由记录/草稿命令处理。媒体移除另受永久边界限制。 |
| [AppViewModel.kt:203](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L203) `materializeMonth` | 内部 → 内部编排 | 页面月份加载自动物化周期实例；需要云端引用时用occurrence.resolve。 |
| [AppViewModel.kt:345](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt#L345) `entrySaved` | 内部 → 内部编排 | 成功保存后的路由/通知和无引用媒体维护，不是云端清理入口。 |
| [CsvImportViewModel.kt:61](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/CsvImportViewModel.kt#L61) `step` | 内部 → 内部编排 | 进入/修改导入预览时自动保存解析列对应缓存；未创建账目。 |
| [CsvImportViewModel.kt:71](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/CsvImportViewModel.kt#L71) `previewNow` | 内部 → 内部编排 | 进入/修改导入预览时自动保存解析列对应缓存；未创建账目。 |
| [CsvImportViewModel.kt:77](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/CsvImportViewModel.kt#L77) `edit` | 内部 → 内部编排 | 进入/修改导入预览时自动保存解析列对应缓存；未创建账目。 |
| [CsvImportViewModel.kt:81](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/CsvImportViewModel.kt#L81) `importBatch` | 命令 → entry.create + category.save（按需） | 同CSV导入意图，不创建伪CSV批次。 |
| [CsvImportViewModel.kt:89](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/CsvImportViewModel.kt#L89) `undo` | 命令 → csvBatch.undo | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [BackupScreen.kt:39](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/BackupScreen.kt#L39) `export` | 命令 → backup.export | 复用同一业务保存/提交；命令保存完整前后值并沿用本机校验。 |
| [BackupScreen.kt:40](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/BackupScreen.kt#L40) `inspect` | 边界 → backup.restore | 本机恢复工作流，不由返回方案驱动。 |
| [BackupScreen.kt:50](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/BackupScreen.kt#L50) `restore` | 边界 → backup.restore | 本机恢复工作流，不由返回方案驱动。 |
| [BackupScreen.kt:45](../../android/app/src/main/java/com/yohaku/expensetracker/ui/features/BackupScreen.kt#L45) `dismiss` | 内部 → 内部编排 | 删除本次恢复检查的工作副本；不删除用户原始备份。 |

### 云端任务、凭据、结果与执行器

| 写入路径 | 分类 → 命令或边界 | 说明 |
|---|---|---|
| [CloudRepository.kt:78](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L78) `bind` | 边界 → cloud.credentials / cloud.device.manage | 连接/解除、认证失败状态都属于永久边界；不是允许的偏好。 |
| [CloudRepository.kt:89](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L89) `unbind` | 边界 → cloud.credentials / cloud.device.manage | 连接/解除、认证失败状态都属于永久边界；不是允许的偏好。 |
| [CloudRepository.kt:85](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L85) `testConnection` | 边界 → cloud.credentials / cloud.device.manage | 连接/解除、认证失败状态都属于永久边界；不是允许的偏好。 |
| [CloudConnectionStore.kt:40](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudConnectionStore.kt#L40) `key` | 边界 → cloud.credentials / cloud.device.manage | Keystore密钥、加密凭据文件和绑定状态；接口声明不计，实际实现各计一次。 |
| [CloudConnectionStore.kt:62](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudConnectionStore.kt#L62) `write` | 边界 → cloud.credentials / cloud.device.manage | Keystore密钥、加密凭据文件和绑定状态；接口声明不计，实际实现各计一次。 |
| [CloudConnectionStore.kt:72](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudConnectionStore.kt#L72) `save` | 边界 → cloud.credentials / cloud.device.manage | Keystore密钥、加密凭据文件和绑定状态；接口声明不计，实际实现各计一次。 |
| [CloudConnectionStore.kt:74](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudConnectionStore.kt#L74) `markBound` | 边界 → cloud.credentials / cloud.device.manage | Keystore密钥、加密凭据文件和绑定状态；接口声明不计，实际实现各计一次。 |
| [CloudConnectionStore.kt:75](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudConnectionStore.kt#L75) `markNeedsReconnect` | 边界 → cloud.credentials / cloud.device.manage | Keystore密钥、加密凭据文件和绑定状态；接口声明不计，实际实现各计一次。 |
| [CloudConnectionStore.kt:76](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudConnectionStore.kt#L76) `clear` | 边界 → cloud.credentials / cloud.device.manage | Keystore密钥、加密凭据文件和绑定状态；接口声明不计，实际实现各计一次。 |
| [CloudRepository.kt:132](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L132) `prepare` | 内部 → 内部编排 | 用户在提交/补充页明确选择后的任务与源文件队列；属于当前方案外层交接流程，结果不能递归提交、改变自身资料。 |
| [CloudRepository.kt:168](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L168) `copySources` | 内部 → 内部编排 | 用户在提交/补充页明确选择后的任务与源文件队列；属于当前方案外层交接流程，结果不能递归提交、改变自身资料。 |
| [CloudRepository.kt:197](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L197) `prepareStatementTask` | 内部 → 内部编排 | 用户在提交/补充页明确选择后的任务与源文件队列；属于当前方案外层交接流程，结果不能递归提交、改变自身资料。 |
| [CloudRepository.kt:212](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L212) `prepareGeneralTask` | 内部 → 内部编排 | 用户在提交/补充页明确选择后的任务与源文件队列；属于当前方案外层交接流程，结果不能递归提交、改变自身资料。 |
| [CloudRepository.kt:216](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L216) `prepareCommandTask` | 内部 → 内部编排 | 用户在提交/补充页明确选择后的任务与源文件队列；属于当前方案外层交接流程，结果不能递归提交、改变自身资料。 |
| [CloudRepository.kt:244](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L244) `prepareReceiptTask` | 内部 → 内部编排 | 用户在提交/补充页明确选择后的任务与源文件队列；属于当前方案外层交接流程，结果不能递归提交、改变自身资料。 |
| [CloudRepository.kt:263](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L263) `addSupplement` | 内部 → 内部编排 | 用户在提交/补充页明确选择后的任务与源文件队列；属于当前方案外层交接流程，结果不能递归提交、改变自身资料。 |
| [CloudRepository.kt:111](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L111) `remember` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:117](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L117) `recordFailure` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:274](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L274) `enqueue` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:275](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L275) `refreshUnlocked` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:279](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L279) `refresh` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:280](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L280) `refreshActive` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:284](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L284) `registerSubmitted` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:292](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L292) `advance` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:336](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L336) `claim` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:413](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L413) `resumePending` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:420](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L420) `sendPendingAcks` | 内部 → 内部编排 | 传输进度、远端快照、下载临时件、来源登记、重试与回执；不是独立账本操作。 |
| [CloudRepository.kt:370](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L370) `recordImport` | 内部 → 内部编排 | 结果领取/去重/格式回填/失败回执；小票业务编辑另有receipt.items.save，办理调用CommandRepository。 |
| [CloudRepository.kt:238](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L238) `receiveCommand` | 内部 → 内部编排 | 结果领取/去重/格式回填/失败回执；小票业务编辑另有receipt.items.save，办理调用CommandRepository。 |
| [CloudRepository.kt:375](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L375) `saveClaimedStatement` | 内部 → 内部编排 | 结果领取/去重/格式回填/失败回执；小票业务编辑另有receipt.items.save，办理调用CommandRepository。 |
| [CloudRepository.kt:384](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L384) `saveClaimedReceiptItems` | 内部 → 内部编排 | 结果领取/去重/格式回填/失败回执；小票业务编辑另有receipt.items.save，办理调用CommandRepository。 |
| [CloudRepository.kt:393](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L393) `recordGeneralSaved` | 内部 → 内部编排 | 结果领取/去重/格式回填/失败回执；小票业务编辑另有receipt.items.save，办理调用CommandRepository。 |
| [CloudRepository.kt:399](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L399) `reportFailed` | 内部 → 内部编排 | 结果领取/去重/格式回填/失败回执；小票业务编辑另有receipt.items.save，办理调用CommandRepository。 |
| [CloudRepository.kt:406](../../android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt#L406) `discard` | 边界 → cloud.task.discard | 本阶段补显式forbidden：放弃未上传任务删除本地任务、暂存资料及月评占位。 |
| [CommandRepository.kt:34](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandRepository.kt#L34) `receive` | 内部 → 内部编排 | 本机方案协议/预览/确认/撤销/去重/回执编排；不作为可被方案递归调用的命令。撤销仅手机历史生成local.*。 |
| [CommandRepository.kt:111](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandRepository.kt#L111) `preview` | 内部 → 内部编排 | 本机方案协议/预览/确认/撤销/去重/回执编排；不作为可被方案递归调用的命令。撤销仅手机历史生成local.*。 |
| [CommandRepository.kt:138](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandRepository.kt#L138) `confirm` | 内部 → 内部编排 | 本机方案协议/预览/确认/撤销/去重/回执编排；不作为可被方案递归调用的命令。撤销仅手机历史生成local.*。 |
| [CommandRepository.kt:180](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandRepository.kt#L180) `reject` | 内部 → 内部编排 | 本机方案协议/预览/确认/撤销/去重/回执编排；不作为可被方案递归调用的命令。撤销仅手机历史生成local.*。 |
| [CommandRepository.kt:71](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandRepository.kt#L71) `prepareUndo` | 内部 → 内部编排 | 本机方案协议/预览/确认/撤销/去重/回执编排；不作为可被方案递归调用的命令。撤销仅手机历史生成local.*。 |
| [CommandRepository.kt:183](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandRepository.kt#L183) `run` | 内部 → 内部编排 | 本机方案协议/预览/确认/撤销/去重/回执编排；不作为可被方案递归调用的命令。撤销仅手机历史生成local.*。 |
| [CommandRepository.kt:286](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandRepository.kt#L286) `recordReceipt` | 内部 → 内部编排 | 本机方案协议/预览/确认/撤销/去重/回执编排；不作为可被方案递归调用的命令。撤销仅手机历史生成local.*。 |
| [CommandRepository.kt:291](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandRepository.kt#L291) `fail` | 内部 → 内部编排 | 本机方案协议/预览/确认/撤销/去重/回执编排；不作为可被方案递归调用的命令。撤销仅手机历史生成local.*。 |
| [CommandFollowupCoordinator.kt:39](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandFollowupCoordinator.kt#L39) `resume` | 内部 → 内部编排 | 已确认后续队列状态机；绑定effectId并做偏好/媒体/导出/权限引导，不接受方案任意调度队列或指定URI。 |
| [CommandFollowupCoordinator.kt:40](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandFollowupCoordinator.kt#L40) `resumeLocked` | 内部 → 内部编排 | 已确认后续队列状态机；绑定effectId并做偏好/媒体/导出/权限引导，不接受方案任意调度队列或指定URI。 |
| [CommandFollowupCoordinator.kt:48](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandFollowupCoordinator.kt#L48) `retry` | 内部 → 内部编排 | 已确认后续队列状态机；绑定effectId并做偏好/媒体/导出/权限引导，不接受方案任意调度队列或指定URI。 |
| [CommandFollowupCoordinator.kt:54](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandFollowupCoordinator.kt#L54) `perform` | 内部 → 内部编排 | 已确认后续队列状态机；绑定effectId并做偏好/媒体/导出/权限引导，不接受方案任意调度队列或指定URI。 |
| [CommandFollowupCoordinator.kt:86](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandFollowupCoordinator.kt#L86) `attach` | 内部 → 内部编排 | 已确认后续队列状态机；绑定effectId并做偏好/媒体/导出/权限引导，不接受方案任意调度队列或指定URI。 |
| [CommandFollowupCoordinator.kt:133](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandFollowupCoordinator.kt#L133) `selectExportDestination` | 内部 → 内部编排 | 已确认后续队列状态机；绑定effectId并做偏好/媒体/导出/权限引导，不接受方案任意调度队列或指定URI。 |
| [CommandFollowupCoordinator.kt:142](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandFollowupCoordinator.kt#L142) `export` | 内部 → 内部编排 | 已确认后续队列状态机；绑定effectId并做偏好/媒体/导出/权限引导，不接受方案任意调度队列或指定URI。 |
| [CommandFollowupCoordinator.kt:185](../../android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandFollowupCoordinator.kt#L185) `completeInteraction` | 内部 → 内部编排 | 已确认后续队列状态机；绑定effectId并做偏好/媒体/导出/权限引导，不接受方案任意调度队列或指定URI。 |

## UI直接写库、非函数触发及偏好字段复核

- `ui/` 非import的DAO/db命中32处，逐处为读取：MonthlyScreens/CommandScreens月计划/月评，CommandFollowupViews读账本，AppViewModel账本/金融/额度Flow及账户选择核对，CsvImportViewModel/Screen导入配置和历史，FinancialDrafts读取草稿，FinancialPlanHistory/RevisionHistory/CreditLimitScreen历史，StatementsScreen/ReceiptWorkflowScreen/ReceiptProducts文件数据。FinancialOutlookReader.withTransaction只读金融图。**直接写DAO/SQL为0**；没有“待下沉”。
- `AppViewModel.init`仅触发本机既有purgeExpired/collectUnusedMedia/pruneBookPreferences，分别归上表边界/内部路径，不能由命令触发；初始化不是额外的用户操作。`execute`/CsvImportViewModel.work/BackupViewModel.run是协程分派，写操作已按被分派函数列出，不重复计数。
- ViewModel其余动作：refreshToday/retryRead/recentCategories、各preview*、refreshOccurrencePreview、读取文件/解码/normalized、events及导航/dismiss/焦点/媒体本次选项/临时configure只读或只改内存。CSV step/edit间接调用previewNow的持久缓存已计入。FinancialDraftSession的自动保存/提交/放弃分别落到storeFinancialDraft/commitFinancialDraft/discardFinancialDraft，UI无DAO直写。
- `BackupPreferences`的automaticReferenceRates/photoSaveOriginal/hapticsEnabled/reminderSettings/foregroundLocationBooks/activeBook分别对应开放偏好；referenceRates/reminderDelivery/commandEffectReceipts是缓存/送达/效果凭据；setupDraft是首次表单草稿。DTO本身不写；全体字段恢复只有AppPreferences.restoreBackup（永久禁止）。派生reminderPlan不在备份，重启按已提交资料重建。
- 系统权限、目录选择器和拍照是interactive的本机用户动作；文件提供器、缓存缩图、播放器/外部打开不写账本。数据库备份副本、临时上传/解包副本的完成后清理不等于云端删除用户源文件。

## 本阶段补齐与最终结论

- 写入盘点没有找到需要新增业务命令的遗漏。补明确的任务放弃边界，防止把清除月评提交占位和源文件队列解释为可远程办理的普通设置。恢复备份、凭据/设备、所有永久删除继续永不开放。
- 修正entry.confirmActual不能通过自身校验的示例；消除能力说明的阶段占位/错误字段简称；收窄生命周期/记录种类schema至实际支持值；新增集中能力示例校验。协议修正嵌套金融引用的矛盾说明，补附件独立阅读步骤、digest/缺省与null/范围差异/最小回答/月评规则。
- 普通办理的真实附件为协议+能力+结构，选范围再加records，最后是用户所选源文件；月评初次为协议+能力+结构+monthly-review。结构不含全部交易，源文件不因已经附在某记录上就自动上传。
- CommandAssistantRoundTripTest导出真实任务附件、持久测试账本；verify领取和预览但从不confirm，并核对账本/偏好未变化。独立助手的返回质量、编译与设备运行待用户验收，静态盘点不等同运行通过。

## 助手往返首轮返工补充

该阶段写入路径及240条分类口径不变（本机商户删除增量加入后为241条；触感偏好的存储与ViewModel入口加入后为上表243条）。CloudRepository.prepare新增初次source_files名称清单，copySources保留五个自动生成名称避免混淆，仍为已有任务编排路径；工具export改调真实advance完成上传，未开放附件校验例外。CommandExports补任务日期/时区及账本初始化白名单字段，是既有只读导出，不新增账本写入。能力/协议允许按用户陈述和约定推断并列假设，未放松USER_FACT、永久边界或实际执行前提。反馈逐项处理见[阶段8记录](cloud-command-stage-8.md)。

金融差异展示与本机共用紧凑日期/原/新/差额表，账户或组成仅在不同时加行下小注；规则字段改空和删除分别显示。步骤快照基线与执行协议保持不变。

预测/手填/余额池计划范围、核对字段与额度配置差异共用 `FinancialDisplayFacts`，每一步仅使用该步 `objects.before/after`（含未变化的相关账单），不使用最终净影响或当前数据库替换中间基线。名称也取该步快照。云端顺序为变化摘要 → 风险/缺项/未知 → 比较及逐项完整前后值入口 → 整批事实确认；原计算和归档继续是保存依据，投影不回传业务输入。整批原子性、事实要求、冲突检测、撤销协议和不新增金融对象跳转的决定保持不变。


### 金融 UI 阶段 5 联动

本机批量转换撤销入口在管理 → 办理与版本历史 → 指定批次，循环计划历史也可进入。`financialBatchRevolving.undo` 命令名及输入协议不变，预览从该批次 `BatchArrangementArchive` 生成 `BatchRestorationFacts`（步骤内 `revolvingBatchArchive` 只读展示对象），包括办理时间/批次、原消费及逐笔金额/合计、原一次付、目标循环计划和恢复范围；原撤销事务及全部冲突校验继续执行。该操作称「撤销这次批量转换记录」，执行历史的另一入口称「撤销整份云端方案」，不能互换。完整恢复事实不取当前计划替代归档。

`CommandFinanceViews` 与 `CommandEffectViews` 同步三种 sharing 关系、未配置/未知、跨账户共享组限制及限制后预计可用；上限与可用值分开。金融时间使用步骤捕获的规则时区，平台观测按原时间串偏移，既有 Z 记录显示 UTC，不补造原地区时区；记录时间单列。格式化只影响显示，不进入条件摘要或业务写入，不改变 Instant 精度。

统一「循环还款计划」「本期付款方式变更截止」「核对当前还款安排」「已替换计划」「结账日」「奖金月付款」等名称，`POOL`、枚举、命令、协议、事实确认、原子性和撤销规则不变；仍不新增金融对象跳转。`CommandFlowTest.batchUndoAndLimitRelationshipsUseArchivedFinancialFactsAcrossLooks` 覆盖撤销归档与额度预览；数据回归继续用 `CommandFinanceExecutionTest.poolPaymentBatchConversionAndAuditedRestoration` / `sharedLimitsRecoveryVoidAndActualObservation`。

批次显示以归档办理时间和原消费名称为依据，不输出内部 UUID；原消费按原付款日、名称排序，恢复列表将目标循环计划置后。本机历史仅有“查看并撤销”或已撤销后的“查看”，确认动作在详情内。

额度关系只说明一次，各项上限不在关系与金额摘要中重复。配置预览中变化的上限只在差异区显示，未变化的上限仍在关系区保留：不适用字段不显示，关系切换删除的旧上限仍明确显示旧金额 → 已移除。完整前后依据保留。

### 支出不计入预算（2026-11-06）

`entry.create`、`entry.update`（及共用字段的 `entry.confirmActual` / `entry.prepareDraft`）支持 `budgetExcluded` 布尔值：新记录默认 false，部分更新省略保留、显式 false 取消，不能为 null；仅 EXPENSE 可设 true，其他类型设 true 校验失败。仓库没有 `entry.save` 别名。排除月/分类预算及其待确认笔数、预算节奏与提醒，退款和手续费随原支出；分析、账户余额、日历实际金额与活动自身预算仍计入。`template.save` 是完整替换，budgetExcluded 默认 false，仅支出模板可为 true；`template.createEntry` 带入模板值并允许 set 覆盖。周期规则、实例和固定预留不新增字段。

`yohaku.records` 每条记录导出 `budgetExcluded`；账本结构的 template.values 同样包含该字段。月评的 budgets 与 provisionalExpenseCount 使用排除后的口径，expenseMinor/categories 仍为实际净消费；确认页字段显示「不计入预算 / 予算に含めない」，撤销按原字段恢复。
