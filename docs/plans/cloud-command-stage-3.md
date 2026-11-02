# 云端办理阶段 3：基础账本全覆盖

日期：2026-11-02。状态：已验收通过（2026-11-02）。基线：阶段 2 `92db7a6`。实现时未运行 Gradle；编译、仪器、性能实测和三主题截图由验收方执行，结果见文末。

## 命令清单

下表全部 **v1 / transactional**。I=INFORMATION；R=具体风险提示，只有实际资金账户负余额加重沿用独立勾选；F=USER_FACT，必须由手机用户勾选。普通编辑不增加事实声明。每个命令的严格 schema、输出类型、默认值、前提、比较字段、失败语义、示例与 undo 来自本机 `CommandRegistry`。

| 名称 | 输入与输出 / 默认值要点 | 确认 | 撤销 |
|---|---|---|---|
| `book.create` | name/currency/startDate；主题 magazine、语言 zh、预算 null；输出 book | I/R | 移除登记的新账本及其初始预算，必须无修改/外部引用 |
| `book.update` | 本账本 name/currency/startDate/budgetMinor/theme/language 补丁；省略保持，只有预算可 null 清空 | I/R | 恢复账本与受影响预算原行 |
| `book.completeSetup` | step 默认4（0..4），complete 默认true | I/R | 恢复设置步骤与 initialized |
| `book.lifecycle` | ARCHIVE/UNARCHIVE/TRASH/RESTORE；book 省略本账本，可指定其他已有账本字面ID | I/R | 恢复原状态；恢复期限在预览与确认重查 |
| `account.save` | CASH/BANK/CREDIT/LOAN/IC、全部期初字段；默认 CREDIT，省略 creditLimit 保持；输出 account | I/R | 原字段恢复；新账户、额度版本及从属行受限移除 |
| `account.setActive` | target、active 必填，无账户回收站 | I/R | 恢复 active |
| `category.save` | name/kind，category/parent 引用、favorite/icon；新建默认根/false/自动；已有省略保留，parent:null 根、icon:null 清手选；输出 category | I/R | 原分类与层级恢复；新分类受限移除 |
| `category.setFavorite` | category、favorite 必填 | I/R | 恢复原收藏 |
| `category.lifecycle` | category、ARCHIVE/UNARCHIVE | I/R | 恢复原启用状态 |
| `category.reparent` | category、parent（null根）必填 | I/R | 恢复原层级 |
| `merchant.save` | name、可选 merchant/note；已有省略/null note 保持；输出 merchant | I/R | 原商户及关联地点资料恢复；新商户受限移除 |
| `merchantLocation.save` | merchant/name；可选 location、成对坐标，默认未知；radiusMeters 默认100；完整保存；输出 merchantLocation | I/R | 原地点恢复；新地点受限移除 |
| `merchant.setActive` | target、active | I/R | 恢复 active |
| `friend.save` | name、可选 friend；输出 friend | I/R | 原名称恢复；新朋友受限移除 |
| `friend.setActive` | target、active | I/R | 恢复 active |
| `entry.create` | EXPENSE/INCOME/TRANSFER/REFUND，默认 EXPENSE；共用表单默认值；金额均 currency+minor；输出 entry | I/R | 新记录及入账/结算/关联行/本次生成预测与修订受限移除 |
| `entry.update` | targets.ids（支持前步引用）或 select；set 为普通记录、转账、退款的完整可编辑字段补丁 | I/R | 恢复记录、所有关联与金融行的原快照；本次新增行移除 |
| `entry.confirmActual` | 同 targets/set，必须明确至少一个实际确认标志 true | I/R | 同 entry.update |
| `entry.lifecycle` | entry、TRASH/RESTORE；包含现有删除组语义的关联退款 | I/R | 恢复删除组/回收站状态、周期与流水失效状态等原值 |
| `template.save` | 完整保存普通模板字段；省略可选项采用空/未知默认；已有排序保留；输出 template | I/R | 原模板恢复；新模板受限移除 |
| `template.move` | template、direction=-1/+1；到边缘不移动 | I/R | 恢复受影响排序 |
| `template.createEntry` | template、可选 set 覆盖；以模板默认值编译为普通记账准备，预览 command=entry.create；输出 entry | I/R | 同普通新建记录；模板作为确认依据 |
| `financialRule.addVersion`（保留） | 见阶段1–2；输出 rule | I/R | 移除本次新增版本 |
| `financialPrediction.save`（保留） | 见阶段1–2；输出 prediction | I/R，新建另 F | 新预测受限移除；已有预测恢复原行 |

`entry.update` 包含原币/账户币/本位币金额、入账日/确认状态/汇率来源与日期、备注、账户/分类/商户、期初已包含、转账两端及手续费、退款关联校验、信用卡付款选择/期数/指定月/费用，以及已存在活动/周期/预留/互请的关联字段。附件操作仍属阶段6，现有附件保持。退款来源不可改，这是现有业务限制；原分类和商户来自原消费。借入、还款专用操作属阶段5，不伪装成普通转账。

同一方案只能写 `plan.book_id`；例外仅为 `book.lifecycle` 明确指定**已有**账本的生命周期状态。`book.create` 的新账本不能成为后续写入目标，切换账本偏好不在本阶段。结构附件仍只包含本账本；操作其他账本需要明确提供其ID，不能从名称猜ID。

## 永久边界判定表

| 对象 / 操作 | 判定 | 替代 |
|---|---|---|
| 分类 MERGE_DELETE | forbidden，迁移后物理删除源分类/子分类 | entry.update + template.save 改分类，再归档原分类；其他引用见下文 |
| 分类 DELETE_EMPTY | forbidden，没有回收站 | category.lifecycle ARCHIVE |
| 账户 AccountOperation.DELETE | forbidden，没有回收站 | account.setActive false |
| 记录 PURGE / EMPTY | forbidden，可能还清理从属资料 | entry.lifecycle TRASH / RESTORE |
| 账本 PURGE / 清空账本回收站 | forbidden | ARCHIVE / TRASH / RESTORE |
| 模板 deleteTemplate | forbidden，物理删除 | template.save 编辑或 template.move 排序 |
| 地点 removeMerchantLocation | forbidden，物理删除 | merchantLocation.save 调整；或停用商户 |
| 到期清理 purgeExpired / 任意清空回收站 | forbidden，不因“过期”开放云端清理 | 保留本机清理策略，不允许方案触发 |
| 备份恢复、连接凭证/设备 | 延续永久边界 | 用户在本机相应界面处理 |
| 其他任意物理删除命令 | forbidden，包括变换命名绕过 | 归档/停用/回收站；无替代时留本机操作 |

本领域盘点没有新增可开放的永久删除。普通编辑中的关系行替换（入账、手续费组成、地点关联、互请分摊等）属于同一对象的编辑，完整记入前后快照，不对云端开放表/行删除接口。仅本机历史能生成受限撤销，不能通过 `local.*` 云端输入调用。

分类界面合并实际迁移：记录（包括回收站记录）、模板、月/分类预算、活动分类预算、周期规则、周期实例、普通记账草稿与金融草稿。现有云端替代路径不会自动改写预算/周期/草稿；归档保留其引用。需新增的预算/周期迁移能力应在阶段4相应保存/实例命令中覆盖，草稿协调按阶段5–6对应领域补齐。在这些命令完成前，能力清单和协议明确要求在本机对应界面处理，不能声称完成完整合并。

## 下沉与批量实现

- `EntryDrafts.kt`：从 AppViewModel 提取普通/转账编辑回填、退款剩余额与比例默认值、新建账户/活动日期/商户/信用卡默认值。界面 `startEntry/editEntry/editTransfer/startRefund` 改调共用函数。
- `LedgerChangeReview.kt.prepareEntryDraft`：将支出/收入、转账、退款准备分派下沉，普通表单预览与云端复用 `prepareEntry/prepareTransfer/prepareRefund` 校验。`entryNeedsReview`、负余额策略、退款上限、期初包含、付款规则继续用既有实现。
- `BatchEntries.kt`：批量共享账本快照，逐行权威查询/写入不重读整本账本；全部来源事实写完后，共享最终账本状态更新相关信用卡预测/余额池，只刷新金融图。分类/备注补丁复用 `BatchEntryCategories` 路径，完全不重算无关金融来源。新记录保存时刻递增以表达步骤顺序，不用随机UUID打破同毫秒默认值排序。
- 参考换算复用 `ReferenceConversion` 与 `estimateTransfer`；`useReferenceRates` 明确选择冻结于执行上下文的本机报价，不覆盖实际金额、不把未知设零。外币缺基准金额/汇率整批失败，不创建半成品草稿。
- `MerchantsScreen` 的唯一相关直接 DAO 写入改为 `LedgerRepository.removeMerchantLocation`；云端仍禁止此动作。流水、金融草稿、CSV 的界面编排保持在对应阶段。

## 比较、生成 ID 与撤销

- 每步保留原有余额/额度/预算/逐期预测投影，并补充账本、分类、商户/地点、朋友、记录所有关联表与模板的结构化前后字段。其他账本生命周期比较与界面相同的对象数量范围。即使归档后恢复、移入回收站后恢复，最终值相同也登记触及对象，保留中间状态并设置撤销冲突基准。
- 输出ID按步骤/角色映射；自动生成入账、手续费、互请、预测/账期等ID再按父对象/业务内容映射。自动从属ID在派生投影前登记；云端预览的同日资金项目按语义身份排序，避免随机UUID改变逐项运行余额，普通界面的原排序保持。嵌套JSON解析后只映射完整标识值。记录创建时间等纯审计值不参与确认；保存顺序与记忆默认值参与；退款事实revision等保留。回收站日期保留业务日期语义，精确恢复期限由领域操作重查。
- executionJson 撤销文档升级到 version=3（Room 仍 v37，无数据库迁移）。新增 `removed` 标记表示普通编辑移除的原有关联行，保存完整原值；恢复这些行是 INSERT 原快照，不是重新计算。旧 v1/v2 文档读取与原本机适配器继续支持。
- 所有权表范围显式列在 `CommandUndoJournal`，只记录事务内实际新增/改动/被替换的行；已有记录另比较完整 Event、LedgerDetails、地点、关联退款/来源、来源预测/账期、周期关系与流水关联；层级移动比较子分类集合，账本币种变更比较从属引用范围。新对象检查 Room 外键、无外键的逻辑 Id 关联、嵌套JSON、草稿；任一外部引用/修改阻止整批。
- 版本3按原快照恢复已有行，再移除登记的新行（含本次写入的记录修订），外键延迟至事务末并检查无残留。这样恢复原记录 revision 和默认值保存顺序，避免退款修订使原预测变为“过期”。**区别于阶段2分类专用逆操作的追加修订：新v3记录用精确快照恢复；旧v2记录仍走原逆操作。** 原执行/撤销方案和去重审计始终保留。
- 撤销预览列出新账本、分类、商户/地点、朋友、记录、模板及从属/修订行，禁止部分撤销。原方案 UNDONE 与撤销执行记录原子写入。

## 界面与文件

沿用既有三主题独立 `CommandCard`、`Panel`、`YSheet` 与 `RecordFacts`，扩展新增命令标题、业务对象名称、字段/状态/币种/图标/主题/语言展示和撤销类型，中日文均覆盖；内部字段键、UUID、删除组ID不直接呈现。组合截图入口 `CommandFlowTest.ledgerCombinationPreviewAcrossLooks`，文件 `cloud-command-stage-1/*-ledger-preview.png`。

主要文件：`data/command/{LedgerCommandAdapters,EntryCommandAdapters,CommandLedgerFacts,CommandPermanentBoundary,CommandAdapters,CommandRegistry,CommandModels,CommandConfirmation,CommandRepository,CommandUndoJournal,CommandUndo}.kt`；`data/repository/{EntryDrafts,BatchEntries,LedgerChangeReview,LedgerRepository,CardPayments,CashTimeline,FinancialPools,FinancialRepository,AnalysisRepository}.kt`；`data/cloud/CloudRepository.kt`；`data/db/EntryTemplateEntity.kt`（仅序列化注解）；`ui/features/{AppViewModel,MerchantsScreen,command/CommandScreens,command/CommandEffectViews}.kt`；仪器测试、协议三处同步文本、能力 schema/样例和文档。

## 必要验证与未验证项

- `CommandLedgerExecutionTest`：六个领域组合，均验证预览回滚→两秒后重新预览模型一致→确认后具体业务值→撤销预览回滚→精确恢复原业务行/外键；禁止操作集中逐项验证整份 FAILED 且前面的合法步骤未写入。
- 同类性能用例：3,700笔导入夹具中单条命令批量改500笔备注，`Log.i("CommandStage3", ...)` 输出 previewMs/confirmMs，无耗时断言。
- `CommandFlowTest`：组合方案预览，`looks=all visual=true`，三主题×中日文 `*-ledger-preview.png`；新增对象字段详情复用主题布局。撤销新对象展示也由同一对象类型渲染；保留原撤销截图流程。
- 复用阶段1–2解析、冲突重跑、双重去重、引用阻止与升级用例，不扩增边缘测试。
- 首版提交时只做了 Kotlin PSI、diff 和 JSON 静态检查；用户随后完成了首轮编译、仪器与视觉检查，结果见下节。本轮仍不运行 Gradle，修改后的分类撤销截图及新增/调整测试待用户复验。

未发现必须依赖 interactive 才能实现的本阶段操作。与最初总计划的差异均按本轮范围：切换偏好、待补完草稿和附件归阶段6；永久删除禁止；账户没有回收站。新增记录修订的撤销处理差异与原因见上文。


## 适配检查表对应实现

| 检查项 | 本阶段实现 / 验收入口 |
|---|---|
| 严格输入、缺省/null、类型引用、未知字段 | 每个输入DTO + `descriptorSchema`/类型槽补丁；`validate` 拒绝类型不适用字段，`normalize` 固定选择器；新输出目标固化为类型引用而非试运行UUID；能力表逐命令列明 |
| 前提、确认、失败语义 | 原Repository校验/preview/commit；false/null即抛错整批回滚；普通信息与具体负余额风险，不伪造用户事实；缺金融值沿用阶段1缺项 |
| 逐步与最终比较 | `CommandRun`派生投影 + `finishLedgerEffect`完整对象/关系行；保留净零中间步骤，比较范围/保存顺序，忽略纯审计时间；恢复期限领域重查 |
| ID与执行证据 | 显式输出和自动从属ID规范化；解析嵌套JSON后映射；真实ID/完整行/触及对象/移除清单存executionJson |
| inverse/冲突/受限移除 | 每个适配器声明inverse；v3完整快照恢复；外键/逻辑ID/JSON/草稿检查；恢复后的外键与引用复核；旧本机逆操作仍兼容 |
| 原子、锁顺序、去重 | 复用CommandRepository；只调用媒体锁内的事务核心，不引入网络/文件效果；UNDONE与撤销记录同事务，原去重记录保留 |
| 本地化与主题 | 逐个命令标题、对象名称、字段、移除类型中日文；复用各主题独立组件，首轮组合/卡片撤销截图通过，本轮分类撤销摘要待复验 |
| 测试与文档 | 六领域组合、集中永久边界、3700/500性能日志、组合预览截图；首轮结果见下节；本轮新增/调整用例待复验，不把静态检查当运行通过 |

## 第 1 轮返工

用户反馈的首轮结果（不是本轮修复后的复验结论）：`CommandLedgerExecutionTest`、`CommandExecutionTest`、`CommandSandboxTest`、`DatabaseUpgradeTest` 共31项通过；3700笔/500笔备注预览约2490ms、确认约2653ms，达标；107项相关回归只有2项既有失败；三主题×中日文组合预览与卡片撤销截图已查看通过。分类撤销 UI 测试仍等待已不存在的 `undo_recat_0`，三主题均超时。

### 分类撤销预览

- `CommandFlowTest.undoPreviewConfirmAndHistory` 改为等待真实 v3 步骤 `command-step-restore_objects`，并断言20笔分类恢复、3条记录摘要和“咖啡 / コーヒー → 日常 / 日々”的实际分类变化。
- `CommandEffectViews.StepSummary` 从结构化 entry 前后值提取分类恢复摘要：总数量、前三笔的日期/商户/金额、各自分类变化，余项提示进入原有逐项详情；v2/v3共用此展示。继续使用三主题各自的卡片与文字组件，不展示内部步骤名或ID。
- 截图滚动至上述业务摘要，文件名继续为 `*-undo-preview.png`、`*-undo-history.png`。本轮新增摘要的视觉效果尚待用户复验。

### 死代码与兼容取舍

核对提交 `cad4310` 与 `92db7a6`：阶段1的执行记录只有 outputs/generatedIds，没有撤销快照，仍明确不可撤销；阶段2验收版的账户/金融逆操作是 `local.restoreExecution`，分类逆操作是 `local.entry.restoreCategory`。没有持久化逆操作生成器使用 `account.save` 或 `entry.update` 作为逆命令。

- 删除未注册的 `EntryUpdateCommandAdapter` 和只被其调用的 `entryInverse`，删除 `undoAdapter` 的两个正向命令转发分支。
- 保留 `RestoreEntryCategoryAdapter`，供已有v2分类记录使用（恢复null/旧归档分类并追加修订）；保留v2/v3共用的 `RestoreExecutionAdapter`。
- 删除不可达的 `DeleteUnusedAccountAdapter` 及其分发。早期不完整v37记录若包含 `local.account.deleteUnused`，原有 `undoAvailability` 拒绝条件仍保留，不会因删除适配器而开放不安全撤销。
- 不新增边缘用例。现有 `CommandExecutionTest.existingAccountRuleAndPredictionRecalculationRestoreExactSnapshots` 显式保存version=2，覆盖旧金融行恢复；`categoryUndoRestoresPersistedUncategorizedState` 写入与阶段2相同的逆步骤/完整守卫格式，并断言实际执行 `local.entry.restoreCategory`、恢复null分类且revision递增。避免这些“旧测试”实际只覆盖新生成的v3记录；原20笔组合继续覆盖v3。

### 能力样例的唯一生成来源

新增 JVM 单元测试 `CommandCapabilitiesTest.examplesMatchRegistryExport`。直接构造 `CommandRegistry().export()`，对其与 `docs/formats/examples/capabilities.valid.json`、handoff副本分别规范化JSON对象键后比较；保留数组顺序，并比较所有schema、说明、例子及真实 `catalogDigest`，不再手写样例或使用占位digest。普通执行只检查，差异会失败并提示生成命令。

在 `android/` 目录运行（本轮未运行Gradle）：

```sh
./gradlew :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest
```

需要同步真实导出时加显式开关；同一个测试先写回两处文件，再比较：

```sh
./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest
```

`app/build.gradle.kts` 将开关转发给测试JVM，并传入仓库根目录，避免Gradle进程的 `-D` 未传入fork进程、或工作目录影响样例路径。两份样例同时登记为测试任务输入，单独改动JSON也不会被Gradle的UP-TO-DATE跳过。注册表构造与导出不需要Android运行时，无需迁至androidTest。

本轮已直接在普通JVM运行用户本次构建留下的 `CommandRegistry` 类，生成真实export并写回两份样例（只做JSON格式化）。没有调用Gradle，也没有手写能力内容。本轮删除的代码未参与注册表导出；下一次编译后仍由上述测试强制核对最新源码的实际结果。

保留用户已修的 `val trash=word(...)` 与两个可空 suspend stateProvider 参数。未重新引入默认 suspend lambda。修改文件：`CommandAdapters.kt`、`CommandUndo.kt`、`CommandEffectViews.kt`、`CommandFlowTest.kt`、`CommandExecutionTest.kt`、新增 `CommandCapabilitiesTest.kt`、`app/build.gradle.kts`、两处能力样例及相关文档说明。本轮6个改动/新增Kotlin文件的PSI语法检查通过，`git diff --check`通过；另在JVM上解析两份样例并与真实export做JSON相等比较，均通过。这不代表新增JUnit测试、Gradle配置、v2仪器用例或新UI已运行通过；本轮未运行Gradle、仪器测试或查看新截图。

## 验收结果（2026-11-02）

- 编译：首轮交付有两处机械性编译错误，由验收方直接修正。
  - 本地函数中调用了 @Composable 的 `word`。
  - suspend 函数类型参数带 lambda 默认值，触发 Kotlin 后端 `AddContinuationLowering` 崩溃，改为可空参数。
- 第 1 轮返工：
  - 批量改分类的撤销流程测试仍等待旧的 `undo_recat_0` 步骤，已按 v3 `restore_objects` 更新；撤销预览改为显示恢复分类的数量与记录摘要。
  - 删除不再注册的 `EntryUpdateCommandAdapter`、`entryInverse` 等死代码。
  - 能力样例改由 `CommandCapabilitiesTest` 从真实导出生成并校验。
- 验收方另改一处文案：撤销“将移除”清单中原先把记录与修订合称“记录及修订”，容易被误读为删除记录本身；改为“本方案产生的记录修订”。
- 单元测试：全部通过，含 `CommandCapabilitiesTest`。
- 仪器测试：
  - `CommandLedgerExecutionTest`、`CommandExecutionTest`、`CommandSandboxTest`、`DatabaseUpgradeTest` 共 31 项通过。
  - `CommandFlowTest` 在 `looks=all visual=true` 下 3 项通过。
- 性能：3,700 笔账本批量改 500 笔备注，预览 2,490 ms，确认 2,653 ms，目标为各 ≤5 s。
- 回归：记录、草稿、回收站、信用卡付款、币种、转账、持久化、余额池、商户、账本、分类、账户、参考汇率共 107 项，失败 2 项，均为阶段 1 前已存在的失败：
  - `DraftEditingFlowTest.incomeAndOpeningIncludedRemovalShowTheirActualBudgetEffects`
  - `DraftEditingFlowTest.ledgerSearchAndParentFilterKeepOriginalRecordsAcrossThemes`
- 视觉：已查看三主题 × 中日文的 `*-ledger-preview.png`、`*-undo-preview.png`、`*-undo-history.png`、`*-card-undo-preview.png`。三套主题各自独立，日文无漏译。
