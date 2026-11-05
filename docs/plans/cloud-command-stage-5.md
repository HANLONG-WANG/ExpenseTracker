# 云端办理阶段 5：金融与额度全覆盖

日期：2026-11-02。状态：**阶段 5 已验收通过**。基线：阶段 4 `9a45a1a`。实现由 Codex 完成（其环境无法运行 Gradle/模拟器），编译、测试与视觉验收在本机完成，见文末验收结果。

### 金融本机查看路径更新（2026-11-04）

金融账户 UI 阶段 1 将本机入口改为：账户详情 → 账单、计划与金融规则 → 固定「总览 / 账单 / 计划 / 管理」。计算规则、还款记录、额度与恢复、版本历史及金融草稿在管理目录；网贷另有借入与合同。借还、额度及各编辑器内部能力未改，正式写入/试运行/确认/撤销协议不变。

按[用户决定 7.1](financial-account-ui-plan.md#71-用户决定2026-11-04)，本轮不添加 `CommandPreviewScreen` 金融对象导航回调或 `CommandFlowTest` 对象跳转流程；用户自行进入对应账户查看。已核对 `ui/features/command/`：现有金融预览显示的是业务对象、步骤和事实，没有引用被移除的旧 tab 标签或页尾入口，因此阶段 1 未修改云端 UI 代码；金融 UI 阶段 2 在 `CommandFinanceViews.kt` / `CommandEffectViews.kt` 同步循环还款计划和共同月付款术语，协议、校验和对象跳转边界不变。阶段 1/2 的路径文案更新不代表完整云端设备验收；阶段 3 的相关复验另见下段。

金融账户 UI 阶段 3 同步：`financialRepayment.save` / `.confirm` 的步骤预览先显示付款账户、实扣、清偿状态、余额和分配目标，再显示完整资料；信息来自该步骤快照。save 仍只记实际付款及待确认组成，confirm 仍需手机端明确勾选平台事实；UI 建议值不成为命令默认值。无新增金融对象跳转。相关验证为 `CommandFinanceExecutionTest#manualBorrowingContractAllocationAndRepayment`、`CommandFlowTest#financeCombinationPreviewAcrossLooks` 和新增还款预览方法；结果见 Android README 的金融 UI 阶段 3。

## 命令清单与适配对应

下表全部 **v1 / transactional**。I=INFORMATION，R=现有独立风险策略（实际资金账户负余额加重），F=USER_FACT（仅手机用户勾选）。R 不表示普通金额变化一律需要勾选。所有输入 schema、类型引用、默认值、前提、比较字段、执行方式、正常示例、失败处理和 undo 均由 `CommandRegistry` 的实际适配器导出。未知字段/版本/命令整份拒绝。

撤销方式：**S**=已有对象及关联行恢复完整原快照，本批新建且未修改、无外部引用的对象/从属/修订行受限移除，任一冲突阻止整批；**N**=只解析已有账期，不写业务对象。命令自己的撤销与 `financialBatchRevolving.undo` 的业务含义不同：前者只允许手机执行历史生成，后者恢复一次既有批量转循环操作。

| 名称 | 确认 | 输入、默认值与前提 | 输出与生成 ID / 撤销 |
|---|---|---|---|
| `financialRule.addVersion`（已有） | I/R | 原领域规则完整 schema；SINGLE/奖金月、INSTALLMENT、REVOLVING、FIXED_LOAN、CREDIT_LINE；固定贷款 EQUAL_PRINCIPAL/ANNUITY/INTEREST_ONLY/MANUAL，循环 FIXED_PRINCIPAL/TOTAL_PAYMENT/BALANCE_TIERS 均保留。未知金融值保持 null；启用债务账户、兼容方式和合法付款账户 | rule；步骤/规则角色；S |
| `financialRule.validate` | I/R | billId；同一期必须已有规则预测和实际确认账单；本机计算本金/费用/合计/日期差异，绑定 billRevision，不接受云端核验结果 | validation；账期+版本；S |
| `financialBill.resolve` | I | planId、number；正整数且唯一有效账期；支持前步新计划 | bill，已有账期输出；N |
| `financialRepaymentAllocation.resolve` | I | eventId 及 billId/contractId 至少一个，必须唯一匹配；支持前步新还款分配 | allocation（repaymentAllocation）；N |
| `financialManualPlan.save` | I/R/F | 完整手工表单；accountId/name/mode/periods；principalMinor=null 未知，complete/opening=false、sourceEventId=null；逐期日期递增，完整计划本金合计一致，来源不得已有有效计划 | prediction，账期按计划+number；S |
| `financialPrediction.save`（已有） | I/R，新建 F | 期初本金或来源、terms/candidates、明确非一次付起始期数；plan 指定重算；保留已确认/逾期/付款关联账期；缺资料不可执行 | prediction；逐期账期映射；S |
| `financialPool.save` | I/R/F | accountId/name/mode/terms/openingMinor/sourceDate；planId 省略新建；adoptExisting=false；shares/adoptedFees=[]；付款变更历史从现有池保留；共用池来源、未分配还款、采纳/重核校验 | prediction；池及采纳后从属计划/账期/证据映射；S |
| `financialArrangement.record` | I/R/F，期限外另 F | input 为领域安排输入，外层 appliedAt 必填；完整支持手工计划、池转出、并池、费用处理、清偿迁移、贷款额度差额。referenceRates/undoGroupId 由手机管理，禁止云端输入；实际办理时刻不得在未来 | prediction、arrangement；原/目标计划及账期业务键；S |
| `financialArrangement.reconcile` | I/R/F | planId/effectiveDate/principalMinor/bills/refunds；clearingMoves=[]，note=""；可补期初本金、手工完整性、池来源日期/还款归属；缺项不执行。poolUnallocatedShares 使用已有付款 ID→整数映射 | prediction；内嵌核对记录、调整/分配映射；S |
| `financialPool.changePayment` | I/R/F，期限外另 F | input.setting/effectiveDate/feeMinor、外层 appliedAt；费用未知不可伪造 0；feeDate=null、feeIncludedInRetained=false；共用付款变更和期限校验 | prediction；内嵌付款变更按池/生效日映射；S |
| `financialBatchRevolving.apply` | I/R/F，期限外另 F | 显式固定 planIds、accountId/paymentDate/principalMinor/poolId/effectiveDate/feeMinor、appliedAt；只接受仍可转换的同账户同付款日一次付账单；不在确认时扩大范围 | batch（revolvingBatch）；批组、安排、调整和池预测映射；S |
| `financialBatchRevolving.undo` | I/R | undoGroupId（可前步引用）；保留现有 after-plan/bill/arrangement/repayment/pool 检查；另核对账期调整原值、外键/逻辑 ID/JSON/草稿引用；冲突整批失败 | 无新输出；产生的历史修订登记；S |
| `financialBorrowing.save` | I/R | 原借入完整表单；opening=false；期初合同不产生新现金流，实际借入需 fundingAccountId/received；费用未知不当零；本金、来源、已清偿下限共用校验 | contract，实际借入另 entry；期初合同无 entry，引用该输出则失败；S |
| `financialRepayment.save` | I/R | 完整还款表单；记录实际付款、待平台确认的清偿及分配；type=NORMAL、allocations=[]；platformConfirmed 字段禁止；保留未知组成 | entry；分配按付款/账期/合同/组成映射；S |
| `financialRepayment.confirm` | I/R/F | 同上；须明确 received/principal/interest/fee/clearingDate，已知为零须明确写 0；包含 PARTIAL/EARLY/SETTLEMENT、合同/账期分配及换汇记录关联；平台实收组成一致、分配不超义务 | entry；本金减少欠款，费用入支出；S |
| `financialContract.linkPlan` | I/R | contractId/planId；同账户/来源/方式；池不能绑定单一合同；已有清偿或竞争计划限制不变 | 原关联及修订登记；S |
| `creditLimit.save` | I/R，已知占用基准 F | 完整额度配置版本；可空上限/占用/共用/恢复/口径字段须明确值或 null；usageStart=null、recoveryDelayDays=0；非负、子额度一致、基准/生效日与时区合法 | config；账户+版本；S |
| `creditLimitGroup.save` | I/R，已知占用基准 F | name/currency/totalMinor/usedMinor/basisDate/accountIds；id 省略新建；完整成员替换、至少两名、同币种、启用债务账户、无其他组归属 | group；组、成员与组修订；S |
| `creditLimitRecovery.confirm` | I/R/F | accountId/eventId/date/principalMinor 及可空分期/循环组成；只能恢复实际已还本金未恢复部分，核对付款依据与配置 | recovery；付款/日期/金额；S |
| `creditLimitRecovery.void` | I/R | recoveryId；现有未作废恢复确认；写作废状态，保留原事实 | 无新业务输出；S |
| `creditLimitObservation.record` | I/R/F | accountId/groupId 恰一项；scope/availableMinor/observedAt 必填；非负、时间不得未来；共享组只 TOTAL | observation；对象/范围/观测时刻；S |
| `financialDraft.reclassify` | I/R | sourceCategoryId/categoryId；首次预览固定匹配 slots，确认不扩大；目标为启用收入分类；保留完整草稿原值、basis、context。现有分类字段只出现在 EXPECTED 表单 | 原草稿精确恢复；S |
| `account.save`（额度扩展校准） | I/R，已知额度占用基准 F | 沿用阶段3完整账户 schema；可选 creditLimit 的占用基准与独立额度命令相同，手机声明具体账户/金额/基准日 | account；S |

借入和还款调用 `previewBorrowing`/`previewRepayment`/`commitFinancialLedger`，不编译成普通转账。规则完整模式复用原领域 serializer，无另建模式分支或限制表。预览所有确认文案由本机生成，写明账户或额度组、金额和日期；不会把助手文字作为已勾选事实。

## 永久边界判定

| 操作 | 判定及原因 | 替代 |
|---|---|---|
| 独立删除规则/核验、计划/账期/修订、合同、还款/分配、安排/调整 | forbidden；会丢失用户已有事实或审计历史 | 规则追加版本，计划重算，账单修正，安排重核；借入/还款编辑或 entry.lifecycle TRASH |
| 独立解除合同关联 | forbidden；现有界面只有带业务校验的关联调整，没有独立解除入口 | financialContract.linkPlan |
| 独立删除额度配置、组/成员/组修订、恢复确认、观测 | forbidden | 追加配置、完整保存组成员、恢复确认作废、追加实际观测 |
| 放弃金融草稿 clearDraft | forbidden；丢失尚未提交的用户输入 | 手机继续编辑/放弃；分类迁移用 financialDraft.reclassify |
| 正常保存中的还款分配/组成员替换、成功提交后的对应草稿消费 | 允许的对象编辑/提交编排；不是独立物理删除能力 | 前后关系和草稿均纳入事务与撤销日志 |
| **undoBatchRevolving 删除自身生成的 arrangements/adjustments** | **用户明确批准为有完整历史的关系恢复，允许开放**。只恢复该批操作前状态，完整历史继续保存在计划修订；不等同丢失用户已有数据的永久删除 | financialBatchRevolving.undo；完整原值/后续引用检查失败即整批失败 |
| 本机历史生成的整批撤销 | 延续既定受限移除，不对云端注册 local.* | 先恢复已有行，再依依赖移除本方案创建行；复核外键和残留引用 |

`CommandPermanentBoundary.kt` 逐项列出 18 个本阶段禁止名称及中日文替代。集中参数用例直接遍历 `permanentAlternatives.keys`，自动包含本阶段，不另建边界矩阵。一般账户/账本/回收站等永久删除继续沿用先前边界。

批量转循环旧业务检查已保留。新增归档字段 `billAdjustments` 记录自身产生的调整行，撤销前逐行核对；旧归档没有此字段时，使用相应原计划版本中保留的 `facts.billAdjustments`，不以当前值冒充原值。新增检查扫描所有本账本关系表，含 FK 元数据、逻辑 ID、JSON、金融/普通草稿；仅该操作将恢复的计划/账期及自身安排/调整视作内部，历史归档不作为业务外部引用。这里扩展的是可兼容的 JSON 归档，**不需要 Room 表迁移**。

## 适配检查表对应

| 检查项 | 对应实现与证据 |
|---|---|
| schema、默认值/未知值、示例、版本、执行方式 | 每个适配器提供真实领域 serializer、类型引用槽及明确说明；`financeAdapter` 只负责共有 schema/解引用/解码，不另建执行机制；capabilities 样例以本机生成结果为准；本轮无需重新生成 |
| 引用和固定范围 | 引用类型含 account/entry/prediction/bill/contract/arrangement/repaymentAllocation/revolvingBatch/creditLimitGroup 等，只能前序引用；显式批量 planIds 固定；金融草稿匹配 slots 在 normalize 固定；账期解析为 plan+number，完整账期事实参与比较 |
| 领域前提与失败 | 原 Repository 的 preview/commit；false/null 必须抛错回滚。缺本金/日期/费率/费用/清偿组成不当零，未完成计算拒绝整批；不留下部分写入 |
| I/R/F | 普通变化 I，负余额按既有策略 R；已办理/实际账单/清偿/恢复/观测和已知占用基准 F；期限外平台受理单独 F；cloud schema 排除事实布尔值和手机参考汇率 |
| StepEffect 与净影响 | `CommandRun` 扩展合同余额、核验、还款分配、安排/重核/池付款、恢复/观测；沿用账期、余额、额度、预算与资金预测。v3 行差异补齐配置、组成员、合同关联和草稿。净零仍保留每步 |
| revision/时间戳 | 纯创建/保存/核验/记录时刻仅从比较投影移除；保留业务日期、appliedAt/observedAt、还款/退款与额度组 revision；完整原始行始终存撤销日志。借贷提交也复用递增保存时序 |
| 新 ID | 先登记新计划/账期/安排/合同/分配/调整/额度对象及内嵌付款/重核记录，再计算派生预测。来源组合标识按完整值登记；不替换用户文本片段。集合按规范化身份排序，日期序列保留业务顺序 |
| inverse、所有权与冲突 | 每个新适配器声明快照逆操作；沿用 v3 journal 扩展金融/额度表。已有金融对象新增完整关联守卫；新行 FK/逻辑 ID/JSON/草稿检查；先恢复原值、后受限删除，末尾复核无残留与外键 |
| 原子、锁、去重 | 复用 CommandRepository 的 mediaWrites→transaction、回滚试运行、确认重跑、语义比较、执行/撤销/去重/回执同事务；没有网络或文件操作混入事务 |
| 预览与本机一致 | 沿用三主题各自 CommandCard、Panel、YSheet、RecordFacts；新增中日文业务名称、金额/日期/事实说明和移除类型。账期 resolve 是现有账期列表选择的协议引用辅助，不需新入口；其他操作均对应现有金融/额度表单与历史操作 |
| 必要验证与文档 | 六组合仪器测试、复杂事实绑定/重跑/整批撤销、集中永久边界；CommandFlowTest 复杂截图；实现规格/UI地图/README/协议三处同步 |

## 下沉、导出与保存输入

- `ui/features/FinancialDrafts.kt` 的两处 DAO `clearDraft` 改调 `LedgerRepository.discardFinancialDraft`。原自动保存/提交并消费草稿已在 Repository；继续使用原来的会话互斥和保存时序，无行为改换。
- `reclassifyFinancialDraft` 仅改 EXPECTED 表单的收入分类，其余所有字段与原始依据保留。普通记账草稿按阶段6处理，金融表单的未完成输入不能冒充已提交金融事实；恢复编辑属于本机原有草稿入口。
- 借贷专用提交使用阶段4的 `nextEntrySaveTime`/`withSaveTime`，避免同毫秒保存令默认值依赖随机 UUID。
- 原始业务输入继续使用 `financialReviewState(preserveLedgerInputs=true)`；金融比较投影只用于比较，绝不交回业务保存。原 theme/language、草稿和附属资料保留。
- 结构导出补合同剩余本金、合同计划关联和批量转循环组引用；实际还款组成和分配只随**用户所选记录**的 `financial` 白名单字段导出。两份 records schema 同步新增可选字段；不上传草稿内容或内部完整实体。

## 必要测试与待验收

新增 `CommandFinanceExecutionTest` 六项，均使用 `runBlocking<Unit>`：

1. 多卡+分期+余额池：预览回滚、跨2秒重跑一致、缺勾选拒绝、相关账户变化使确认失败并清空旧预览、重预览旧声明不可复用、确认与整批精确撤销。
2. 借入+手工计划+合同关联+账期选择+部分清偿分配：专用借贷余额、银行余额、合同剩余本金，整批撤销。
3. 规则预测+实际账单确认+规则核验+已办理分期安排+安排重核，整批撤销。
4. 额度配置+共享组+还款+恢复确认/作废/重确认+实际观测，整批撤销。
5. 余额池+月付款变更+批量转循环+业务撤销，保留已有来源计划与归档；原方案整批撤销；后续重核草稿引用阻止业务撤销及本机整批撤销，并验证先前预算步骤也回滚。
6. 金融草稿分类迁移，保留其他表单输入、basis/context，整批恢复原草稿。

每个组合核对主库回滚、确认重跑、执行后具体业务值、撤销预览回滚、完整原始行恢复及外键。既有 CommandExecutionTest 的去重/外部引用共性用例继续使用，不复制整套边界测试。

`CommandFlowTest.financeCombinationPreviewAcrossLooks` 使用 `looks=all visual=true`，输出 `cloud-command-stage-1/*-finance-preview.png`（具体事实勾选可见）、`*-finance-pool.png`（池步骤金额与业务名称）。验证未勾选/只勾选一项不可确认，全部勾选后可确认。须由用户运行并逐张查看三主题×中日文。

能力样例已由用户通过生成入口同步，本轮保持原字节。以后注册表变化时，可在 `android/` 串行运行已有生成入口：

```sh
./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest
```

本次没有运行该命令。新的 catalogDigest 必须以重新编译后的注册表导出为准。相关领域回归使用既有 FinancialArrangement/Pool/PoolPayment/Reconciliation、CreditLimit、FinancialLedger、FinancialDraft 测试中的数据与流程部分；不扩展全外观/系统/性能矩阵。

## 差异与不确定项

- 用户已明确批准批量转循环撤销为历史完整的关系恢复，按该决定实现；未引入作废字段或数据库迁移。
- 新增 `financialBill.resolve` / `financialRepaymentAllocation.resolve` 是同批新计划和还款引用其账期/分配所需的辅助命令。
- `financialDraft.reclassify` 补齐金融草稿分类迁移；普通记账草稿仍按已约定属于阶段6。
- 本阶段仅声明实现状态，不把静态检查当成编译、数据流程或视觉验收。真实网站往返仍是用户实测项。

## 修改文件

- 文档与协议：`android/README.md`、`android/app/src/main/assets/yohaku-command-protocol-v1.md`、`docs/android-cloud-handoff-package/formats/records.v1.schema.json`、`docs/android-cloud-handoff-package/formats/yohaku-command-protocol-v1.md`、`docs/formats/records.v1.schema.json`、`docs/formats/yohaku-command-protocol-v1.md`、`docs/implementation-spec.md`、`docs/plans/cloud-command-plan.md`、`docs/plans/cloud-command-stage-5.md`、`docs/ui-implementation-map.md`。
- 测试：`CommandFinanceExecutionTest.kt`、`CommandFlowTest.kt`、`CommandTestFixtures.kt`、`CommandProtocolTest.kt`。
- `data/command/`：`CommandAdapters.kt`、`CommandConfirmation.kt`、`CommandExports.kt`、`CommandFinanceFacts.kt`、`CommandLedgerFacts.kt`、`CommandPermanentBoundary.kt`、`CommandRegistry.kt`、`CommandRepository.kt`、`CommandSchema.kt`、`CommandUndo.kt`、`CommandUndoJournal.kt`、`FinanceCommandAdapters.kt`。
- `data/repository/`：`BatchRevolvingArrangements.kt`、`CreditLimitRepository.kt`、`FinancialDrafts.kt`、`FinancialLedgerRepository.kt`、`FinancialReferences.kt`、`FinancialRepository.kt`。
- `ui/features/`（含 command 子目录）：`FinancialDrafts.kt`、`CommandEffectViews.kt`、`CommandFinanceViews.kt`、`CommandScreens.kt`。

## 实现阶段静态核对

- 26 个新增/修改 Kotlin 文件通过 Kotlin PSI 语法解析（0 个语法错误）；不等同类型检查或编译通过。
- `git diff --check` 通过。协议三处副本一致；两份 records schema 一致且可解析；21 个适配器示例 JSON 文本可解析。没有运行新注册表的编译后导出，也没有手改能力样例。
- 未运行 Gradle、单元/仪器测试或新版本截图；这些均待用户验收。

## 第 1 轮返工：核验序列化、比较顺序与账期标识

本节为用户报告两项仪器失败及账期可读性问题后的源码修复说明，**不是修复后的运行验收结果**。未运行 Gradle、模拟器或新截图；验收结果仍由用户回填。

### 1. 规则核验实体缺少序列化器

`CommandRun.project` 对 `FinancialRuleValidationEntity` 调用了通用 `encoded(v)`，但该 Entity 没有 `@Serializable`。通用 reified 序列化入口允许源码编译，到核验行真正进入投影时才查找不到生成的 serializer。补充与同文件规则/计划/账期一致的 `@kotlinx.serialization.Serializable`，不捕获或忽略异常。

同步核对 project/journal：直接序列化的规则、计划、账期、合同、还款、还款分配、安排、账期调整、额度配置/组/恢复/观测实体，以及相关预测/额度 DTO，已有序列化注解。合同计划关联、额度组成员/修订、计划修订、金融草稿由 journal 的 Cursor 读取为原始 `JsonObject`，不调用其 Entity serializer，因此不为它们无端补注解。无 Room 表或迁移变化。

`CommandConfirmationTest` 增加核验行的真实序列化往返与比较投影断言：保留 `billRevision` 和未知费用差额，忽略纯审计 `checkedAt`；原失败的仪器组合保留。

### 2. 池付款及批量转循环的比较行顺序不稳定

源码链路：`financialReviewState` 按账期 UUID 排序 → `poolPaymentComparison` / `existingPoolMergeComparison` / 批量比较将这些账期直接映射成 `ArrangementMoneyRow` → 保存到 `PoolPaymentChange.comparison`、安排 `comparisonJson`。金额行只有日期、本金、费用、总额、付款账户，已经没有 ID 或期号，因此旧 `orderFinanceFacts` 的“带 ID 的集合”排序分支不会处理它们。

新账期的 UUID 在每次试运行/确认时不同，可使相同义务的数组顺序交换。受影响字段是 `comparison.original[i]` / `comparison.proposed[i]`，包括池计划的 `termsJson.pool.paymentChanges[*].comparison` 和独立 `poolPaymentChange` 投影，以及安排 `comparisonJson`。前两次随机顺序恰好一致，不保证第三次确认仍一致。现有测试输出未包含 Logcat 的 firstDifference 路径；本轮按上述源码链路定位，并用确定性反序夹具固定复现首个差异 `...comparisonJson.original[0].date`，不把它冒充设备日志中的实际索引。

修复仅在**ID 已规范化后的比较投影**识别 `FinancialArrangementComparison`，对其中 `original` / `proposed` 两个义务行集合按完整规范值排序。保留重复行及每行所有字段，不合并金额、不丢行；付款日期序列及其他同名 original 数组不改顺序。原始业务快照、归档及撤销冲突数据不改写。

核对了相关疑点：批量 `undoGroupId` 已由 `output(batch, new=true)` 登记，池付款变更/安排/调整行 ID 已按步骤和业务键登记；实际 `appliedAt` 来自明确输入并保留，创建/核验/重核的纯记录时刻仍只从比较投影移除。没有通过忽略这些业务依据来消除差异。

同一个确定性单元回归覆盖直接 `comparisonJson` 和池付款内嵌比较、不同真实 ID、反向读取顺序、重复行；同时断言日期、本金、费用、总额、付款账户及实际办理时间变化仍不相等。原池付款→批量转循环→业务撤销→整批撤销仪器用例继续复验。

### 3. 同日同期期号的账期归属

原 `resolveName` 的 bill 分支只组合期号与日期，没有沿 `planId` 查计划及其 `accountId`，导致多卡同日第 1 期同名。现在统一显示“账户名 · 计划名 · 第 N 期/回 · 日期”；名称取自现有确认模型，支持新建对象占位符及撤销前值，不展示内部 ID。

步骤摘要、净影响和逐项详情共用该标签；继续用原三主题文字、卡片及 `RecordFacts` 详情，不增加主题外控件。`CommandFlowTest.financeCombinationPreviewAcrossLooks` 补充池步骤与净影响两张卡首期名称断言，并增加 `*-finance-net.png`；原 `*-finance-pool.png` 继续截取。已查看用户首轮日文手账池截图核对旧问题，修改后的三主题中日文截图已在验收中查看。

### 本轮文件与验证范围

- 生产代码：`data/db/FinancialEntities.kt`、`data/command/CommandFinanceFacts.kt`、`ui/features/command/CommandEffectViews.kt`。
- 测试：`CommandConfirmationTest.kt`、`CommandFlowTest.kt`。没有新增边界矩阵或重复仪器流程。
- 文档：本文件、`android/README.md`、`docs/ui-implementation-map.md`。
- 用户已生成的两份能力样例保持原字节；本轮不改注册能力/schema，无需再次生成。协议内容不变。
- 本轮静态核对：5 个修改的 Kotlin 文件通过 PSI 语法解析（0 错误），`git diff --check` 通过，两份能力样例 SHA-256 与返工前一致。这不是编译、单元/仪器或新截图通过的证明。

## 验收结果

2026-11-02 本机验收（模拟器 API 36），**通过**：

- 能力样例用 `CommandCapabilitiesTest` 生成入口重新生成；单元测试全部通过；debug 与 androidTest APK 构建通过。
- 首轮发现三项问题并返工一轮：`FinancialRuleValidationEntity` 缺少序列化注解；批量转循环比较投影受账期读取顺序影响导致确认重跑不一致；多卡同期账期标签无法区分。均已修复。
- 返工后 `ruleValidationAndAppliedArrangementReconciliation` 暴露测试步骤顺序问题：确认实际账单后原预测按既有领域规则过期（`arrangement.refreshOriginal`），应用本来要求先重算再登记安排。由验收方在测试中补入 `financialPrediction.save` 重算步骤，并让预览不可执行断言输出冲突与缺项；未改生产代码。
- `CommandFinanceExecutionTest` 6 项、`CommandExecutionTest`/`CommandLedgerExecutionTest`/`CommandPlanningExecutionTest`/`DatabaseUpgradeTest` 共 33 项通过。
- `CommandFlowTest`（`looks=all visual=true`）5 项通过；已查看三主题×中日文的 `*-finance-preview.png`、`*-finance-pool.png`、`*-finance-net.png`：事实勾选写明账户、金额、日期，未勾全不可确认；账期标签为"账户名 · 计划名 · 第 N 期 · 日期"，各主题风格一致。
- 金融领域回归（CardPayment、CreditLimit、FinancialArrangement/Draft/Ledger/ManualArrangement/PlanImpact/Pool*/Prediction/Reconciliation*/Retroactive/Storage 及 FinancialDraftFlow、CreditLimitFlow、FinancialPoolPaymentFlow）73 项通过。
- 真实网站往返仍为用户实测项，未声称已验证。

### 金融规则差异与方案比较展示

`CommandEffectViews` / `CommandFinanceViews` 接入本机共用的 `FinancialDisplayFacts` 只读投影：规则新版本取该步骤 `objects.before` 中同账户同模式最高版本，与新规则比较，首次建立单独标注；方案按存于该步骤办理记录中的 comparison 对齐日期，保留同日多行、账户/组成变化及不完整范围。共用紧凑日期/原/新/差额表，仅将不同的账户或组成列为行下小注；空值与键/嵌套段删除使用不同文案。不得从当前数据库最新规则补基线，不将展示摘要作为业务输入。逐项完整前后值、整批原子性、确认要求、冲突检查和撤销协议保持原样，不新增金融对象直达。验证入口为 `CommandFlowTest#financeCombinationPreviewAcrossLooks`（含同批前序版本与共同月付款比较）。

预测/手填/余额池计划范围、核对字段与额度配置差异共用 `FinancialDisplayFacts`，每一步仅使用该步 `objects.before/after`（含未变化的相关账单），不使用最终净影响或当前数据库替换中间基线。名称也取该步快照。云端顺序为变化摘要 → 风险/缺项/未知 → 比较及逐项完整前后值入口 → 整批事实确认；原计算和归档继续是保存依据，投影不回传业务输入。整批原子性、事实要求、冲突检测、撤销协议和不新增金融对象跳转的决定保持不变。

本机完整依据入口保留逐期账单、组成、付款账户、原预测依据和版本历史；云端“逐项查看前后与连带影响”保留每个字段的前后绝对值。同批前序规则/月付款变更的中间基线由 `CommandFlowTest#financeCombinationPreviewAcrossLooks` 验证，未知/日期/账户差异由 `FinancialDisplayFactsTest` 验证。


### 金融 UI 阶段 5 联动

本机批量转换撤销入口在管理 → 办理与版本历史 → 指定批次，循环计划历史也可进入。`financialBatchRevolving.undo` 命令名及输入协议不变，预览从该批次 `BatchArrangementArchive` 生成 `BatchRestorationFacts`（步骤内 `revolvingBatchArchive` 只读展示对象），包括办理时间/批次、原消费及逐笔金额/合计、原一次付、目标循环计划和恢复范围；原撤销事务及全部冲突校验继续执行。该操作称「撤销这次批量转换记录」，执行历史的另一入口称「撤销整份云端方案」，不能互换。完整恢复事实不取当前计划替代归档。

`CommandFinanceViews` 与 `CommandEffectViews` 同步三种 sharing 关系、未配置/未知、跨账户共享组限制及限制后预计可用；上限与可用值分开。金融时间使用步骤捕获的规则时区，平台观测按原时间串偏移，既有 Z 记录显示 UTC，不补造原地区时区；记录时间单列。格式化只影响显示，不进入条件摘要或业务写入，不改变 Instant 精度。

统一「循环还款计划」「本期付款方式变更截止」「核对当前还款安排」「已替换计划」「结账日」「奖金月付款」等名称，`POOL`、枚举、命令、协议、事实确认、原子性和撤销规则不变；仍不新增金融对象跳转。`CommandFlowTest.batchUndoAndLimitRelationshipsUseArchivedFinancialFactsAcrossLooks` 覆盖撤销归档与额度预览；数据回归继续用 `CommandFinanceExecutionTest.poolPaymentBatchConversionAndAuditedRestoration` / `sharedLimitsRecoveryVoidAndActualObservation`。

批次显示以归档办理时间和原消费名称为依据，不输出内部 UUID；原消费按原付款日、名称排序，恢复列表将目标循环计划置后。本机历史仅有“查看并撤销”或已撤销后的“查看”，确认动作在详情内。

额度关系只说明一次，各项上限不在关系与金额摘要中重复。配置预览中变化的上限只在差异区显示，未变化的上限仍在关系区保留：不适用字段不显示，关系切换删除的旧上限仍明确显示旧金额 → 已移除。完整前后依据保留。

云端确认事实会规范化带 ID 的数组；批次摘要在最终展示边界应用同一业务排序，不能依赖序列化顺序。额度追加版本的差异基线取本步骤捕获的 `creditLimit.before.config`（或同一步的旧配置快照），不能把新增存储行的空 before 当成首次配置，也不能查询当前数据库补基线。
