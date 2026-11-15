# 高严重度问题汇总

摘自 [bug-reports-all.md](bug-reports-all.md)，收录其中全部严重度为「高」的问题：代码审查 21 条（按原分区排列，编号与原文一致，内容未改动），以及视觉检查结果中的 3 条高严重度项。

## 分区 01-repo-a

1. **[严重度 高] 切换记账类型后退出，会无提示丢弃已填写内容和附件**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/EntryDefaults.kt:29`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt:87`、`:256`、`:261`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:226`；关联测试：`android/app/src/androidTest/java/com/yohaku/expensetracker/EntryExperienceFlowTest.kt:85–100`
   - 问题：两条路径都会把保留下来的用户输入判为“未编辑”：
     - `hasManualInput()` 对“非转账且尚未选择分类”直接返回 `false`。新建转账填写备注、添加附件后，切换为支出或收入，代码会保留备注和附件，但清空分类。此时退出分类页，会直接执行 `leaveEntry(..., keep=false)`，清除草稿及暂存媒体，不询问保留。
     - `switchEntryKind()` 保留备注、附件和小票后调用 `openDraft()`，后者重新记录输入基线。支出草稿填写备注、添加照片后切换为转账，不再修改而直接关闭，`entryHasManualInput()` 会判为没有输入，同样清除草稿并触发未引用附件清理。这里丢失的是用户输入，并非文档允许忽略的单纯分类选择。
     - `EntryExperienceFlowTest` 第 94 行断言切换类型后 `entryHasManualInput()` 为假，把这一行为固化成了正确结果。
   - 建议：按实际输入和媒体状态判断是否需要保留提示；切换类型时保留已有输入的修改状态，不要把保留下来的用户内容重新视为初始默认值。测试改为验证关闭时提供保留/放弃选择。
   - 把握：确定。

2. **[严重度 高] 待确认还款可以超额分配，导致现金预测低估备款**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialLedgerRepository.kt:153`、`:173`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/CashTimeline.kt:58`
   - 问题：`prepareRepaymentAllocations()` 在未确认清偿时提前返回，跳过分配合计校验；现金预测却直接使用这些分配抵扣账单。例：实际扣款、本金均填写 1,000，向两张账单各分配本金 1,000，清偿保持待确认，可以保存；现金只扣了 1,000，预测却减少了 2,000 的待备款。云端 `financialRepayment.save` 同样走此路径。
   - 建议：待确认状态也应校验已知金额之间的分配上限；依据不完整的分配不能作为确定金额抵扣现金需求。
   - 把握：确定。

3. **[严重度 高] 恢复已删除还款绕过账单和合同的剩余本金校验**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/EntryLifecycleRepository.kt:57`、`:78`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialLedgerRepository.kt:174`
   - 问题：恢复仅检查期限、退款上限和预留使用，不检查还款分配。例：1,000 本金账单已被还款 A 结清，删除 A 后录入还款 B 再次结清，随后恢复 A，两笔分配同时生效，账单剩余变成 −1,000。正常保存还款会拒绝这种超额分配，恢复却允许；手填计划也未必产生预测失效提示。手机回收站和云端 `entry.lifecycle` 均受影响。
   - 建议：对恢复后的完整账务状态复用账单、合同及池内本金分配校验；冲突时要求先调整关联。
   - 把握：确定。

## 分区 02-repo-b

1. **[严重度 高] 期初本金可以重复分配给多个还款计划**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialRepository.kt:106`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPredictions.kt:82`
   - 问题：手填计划只检查自身各期本金之和，不检查账户剩余可分配期初本金。例如账户期初欠款 ¥10,000，可以连续保存两份本金均为 ¥10,000 的完整期初计划，产生 ¥20,000 的付款义务。预测入口虽有上限检查，却只统计仍然 `active` 的 `OPENING` 计划，遗漏余额池占用的期初本金，以及已办理转换后停用的原期初计划。
   - 建议：统一计算期初本金占用，涵盖独立计划、余额池和已办理转换关系；手填与预测入口共用该约束。
   - 把握：确定。

2. **[严重度 高] 已并入余额池的消费仍可重新建立独立计划**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPredictions.kt:111`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPredictions.kt:122`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialRepository.kt:117`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPoolMerge.kt:24`
   - 问题：来源去重只检查带相同 `sourceEventId` 的活动计划。一次付消费全额转入余额池后，原计划可以停用，新建的 `POOL_MEMBER` 也明确为非活动，而余额池本身没有 `sourceEventId`。此时重新选择该消费创建一次付预测会通过校验，同一本金同时出现在余额池和新计划中。手填入口同样遗漏余额池来源归属。
   - 建议：按债务来源及有效办理关系检查归属，不能仅凭计划的 `active` 和直接来源字段判断“尚无计划”。
   - 把握：确定。

3. **[严重度 高] 确认转账手续费流水时，会把手续费金额填入转账本金**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/StatementEntryDrafts.kt:53`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/StatementEntryDrafts.kt:61`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/TransferTransactions.kt:45`
   - 问题：转账支持独立的 `:fee` 入账，但流水实际确认只传递事件和账户，未保留具体 posting 身份。例如银行转出 ¥1,000，另扣手续费预计 ¥10、尚未确认；用实际手续费 ¥15 的流水进入确认时，函数因账户等于转出账户，将 `amount` 改成 ¥15，手续费仍保持原值和待确认状态。按预填内容保存会改错本金。手续费由第三个账户支付时，则会错误拒绝方向或账户。
   - 建议：实际确认应定位具体 posting，并分别更新转出、转入或独立手续费字段；同步调整本机入口和云端命令。
   - 把握：确定。

4. **[严重度 高] 云端流水补录可以绕过“来源金额不可修改”的限制**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/StatementEntryDrafts.kt:33`；分区外根因：`android/app/src/main/java/com/yohaku/expensetracker/data/command/FileCommandAdapters.kt:70`、`:72`、`android/app/src/main/java/com/yohaku/expensetracker/data/command/EntryCommandAdapters.kt:133`
   - 问题：`statement.supplement` 禁止覆盖 `accountAmount`，却允许 `set.amount`。通用适配器随后在同币种时执行 `accountAmount=d.amount`。因此，¥1,000 的银行流水配上 `set.amount={currency:"JPY",minor:2000}`，最终可以保存 ¥2,000 的实际入账，而 USER_FACT 确认文案仍要求用户确认流水中的 ¥1,000，来源保护和确认内容相互矛盾。
   - 建议：按流水已知事实限制补充字段，并在草稿适配完成后校验来源端金额、币种、日期和方向仍与流水一致；需要补充原币金额时，也不能间接覆盖来源端。
   - 把握：确定。

5. **[严重度 高] 云端外币流水补录及预计收入到账的“已确认”状态被新建默认逻辑覆盖**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/StatementEntryDrafts.kt:36`；分区外根因：`android/app/src/main/java/com/yohaku/expensetracker/data/command/EntryCommandAdapters.kt:131`、`android/app/src/main/java/com/yohaku/expensetracker/data/command/FileCommandAdapters.kt:72`、`:74`、`android/app/src/main/java/com/yohaku/expensetracker/data/command/ScheduleCommandAdapters.kt:106`
   - 问题：`entryDraft()` 对所有新建外币记录，只要 `set` 没提供 `settlementConfirmed` 就强制设为 `false`，未区分已有业务种子：
     - 流水草稿明确设置 `settlementConfirmed=true`，却被重置为待确认。例如人民币消费、日元银行已实际扣款，补录后银行余额不会计入这笔已确认扣款。流水适配器又禁止在 `set` 中填写 `settlementConfirmed`，调用方无法修正。
     - `expectedIncome.arrive` 创建外币实际收入时同样被改为 `false`，随后被到账业务“必须已确认”的校验拒绝，即使实际到账金额已经完整提供。
   - 建议：区分普通新建草稿的默认确认状态与流水、到账等已确定的业务事实，保留后者的确认状态。
   - 把握：确定。

## 分区 03-command-cloud

1. **[严重度 高] 参考服务会因提交后的日志失败删除已登记的源文件**

   位置：`docs/mcp-implementation-handoff-package/reference/lib/service.ts:32`、`:34`、`:36`

   问题：上传的 `try/catch` 同时包住 R2 写入、数据库提交、提交后查询和日志写入。数据库已成功保存文件元数据后，如果后续查询或日志失败，catch仍删除R2对象，却不撤销数据库记录。重试又命中第32行的去重记录并返回成功，形成无法通过正常重传修复的“元数据存在、文件已丢失”。

   建议：明确数据库提交边界；仅在确认文件尚未登记时清理R2对象，日志应纳入相应事务或独立处理失败。

   把握：确定；定位于交接包参考实现。

## 分区 04-db-domain

1. **[严重度 高] 末期本金未知时，重算可能假定偿还了超过账单总额的本金**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/ReconciledSchedule.kt:139`、`:143`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPredictions.kt:144`
   - 问题：非末期已确认账单缺少本金时返回 `Incomplete`，末期却直接使用预测本金，且没有受已确认总额约束。例如来源本金 ¥10,000，唯一一期已确认总额 ¥5,000、本金和费用均未知、尚未付款：重算会假设未来偿还 ¥10,000 本金，显示剩余本金为零，并将计划标为完整。实际 ¥5,000 的总付款不可能偿还 ¥10,000 本金。仓储允许保存这种部分已知账单，因此并非只能通过构造非法数据触发。
   - 建议：末期同样保留本金未知状态；无法从已确认信息确定拆分时，不推导全部清偿，也不标记完整。
   - 把握：确定。

## 分区 05-platform-receipt-build

1. **[严重度 高] 扫描结果在入库成功前失去恢复点，失败后唯一照片被删除**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/scan/ScanFlow.kt:72`；`android/app/src/main/java/com/yohaku/expensetracker/receipt/scan/ScanTemporaryFiles.kt:29`；分区外 `android/app/src/main/java/com/yohaku/expensetracker/ui/features/MediaPanels.kt:142`、`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt:415`、`:425`
   - 问题：确认使用后，先移动选中照片并删除整个扫描会话，再清空扫描请求、异步入库。入库的 `finally` 无论成功失败都删除输出目录。例如缓存中已有拍摄照片，但剩余空间不足以复制附件或生成缩略图，入库失败后原图和输出都会消失，用户只能重拍。此外，导出期间界面重建取消协程，可能在文件已经移动后丢失返回值；保存状态仍指向已删除的旧目录，新输出路径没有恢复记录。
   - 建议：保留可恢复的输出路径和交接状态；入库成功后才清理唯一输出，失败时保留照片供重试。文件移动与状态交接应能跨界面重建恢复。
   - 把握：确定。

## 分区 06-components-theme-commandui

1. **[严重度 高] 云端逐项核对将不同币种的费用按同一币种显示**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandEffectViews.kt:230`、`:304`
   - 问题：金额格式化仅为 `baseMinor`、`expectedBaseMinor`指定账本币种，遗漏了 `feeBaseMinor`、`sourceFeeBaseMinor`、`withheldFeeBaseMinor`、`separateFeeBaseMinor`。这些字段会继承债务对象的币种。例如人民币账本中的日元债务，`feeBaseMinor=10000` 实际是 `CN¥100.00`，核对页却显示 `¥10,000`。`sourceFeeMinor` 也会按债务币种显示，而它实际属于付款账户币种。已对照 `CommandConfirmation.kt:149` 和本机还款编辑器确认字段含义。
   - 建议：按对象类型和字段明确携带币种，分别处理本位币、债务币及付款端币种，避免依靠统一继承推断。
   - 把握：确定。

## 分区 07-ui-shell

1. **[严重度 高] 编辑账户的身份在 Activity 重建后丢失，保存可能变成新增账户**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:64、94、392、699、728`
   - 问题：弹层类型 `sheet` 使用 `rememberSaveable`，但待编辑账户 `editAccount` 仅使用 `remember`。编辑已有账户时旋转屏幕，弹层会恢复，`original` 却变为 `null`，进入新增逻辑。表单字段又可恢复旧值，继续保存会生成新账户，而不是修改原账户；重复的期初余额会影响资金汇总。
   - 建议：保存待编辑账户 ID，重建后从当前账本解析对象；编辑目标不存在时明确提示，不要降级为新增。
   - 把握：确定。

2. **[严重度 高] 指定账户的“补记收入”入口实际使用了另一个默认账户**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:142、148、263、346`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt:221`
   - 问题：资金风险页将目标账户传给 `requestEntry(INCOME, accountId)`，但 `startRequestedEntry()` 只在转账时使用这个参数；收入直接调用没有账户参数的 `startEntry()`。例如核对银行 B 的资金缺口时点击“补记已实际到账收入”，若最近收入账户是 A，表单会选中 A，直接保存便记错账户，B 的缺口仍存在。
   - 建议：将收入目标账户完整传入草稿创建流程，并按收入账户资格校验。
   - 把握：确定。

## 分区 08-ui-fin-a

1. **[严重度 高] 日历索引跨步骤复用，可使付款方案确认页崩溃**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialArrangementEditor.kt:203`、`:98`、`:239`；`android/app/src/main/java/com/yohaku/expensetracker/ui/components/Components.kt:258`
   - 问题：逐期日历把展开位置编码为 `(期次索引+1)*10+字段索引`，最终办理时间却直接复用这个值。填写有效方案后，展开第一期日历、不选日期也不收起，直接点击“比较当前与新方案”再进入“记录结果”，`owner` 仍为 10，而最终时间控件只有一个字段，访问 `fields[10]` 越界。日历是内嵌控件，展开期间底部按钮仍可点击。
   - 建议：表单日历与办理时间分别保存展开状态；切换步骤、切换手动模式导致字段集合改变时，清除原展开位置。
   - 把握：确定。

## 分区 09-ui-fin-b

1. **[严重度 高] 重算已办理计划会丢失办理手续费**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialPredictionEditor.kt:83`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialArrangements.kt:167`  
   问题：已办理方案将手续费保存在 `terms.additionalFees`，但预测编辑器重新构造 `FinancialScheduleTerms` 时没有保留该字段。例如新方案首期包含 300 元办理费，首期尚未确认、付款或逾期时，打开“重算未确认预测”直接预览保存，就会把这笔费用从未来账单中删除。数据层没有阻止这种变更。  
   建议：以已保存的 terms 为基础更新可编辑字段；已办理手续费只能通过明确的调整流程修改，并在共用数据层约束，覆盖云端重算入口。  
   把握：确定。

2. **[严重度 高] 已办理循环方案重新生成日期时，会退回原消费日开始计息**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialPredictionEditor.kt:80`、`:129`；`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/ReconciledSchedule.kt:94`  
   问题：提交时正确保留已办理生效日 `agreement`，但“按月生成候选日期”对所有 EVENT 来源一律使用原事件日期。例如 10 月 1 日消费、11 月 1 日转循环，重新生成后首期计息起点变成 10 月 1 日。计算器直接采用该区间，没有按办理生效日裁剪，会额外计算转换前的利息。  
   建议：候选生成与提交共用同一个基准日；已办理方案使用当前办理生效日，并校验计息区间与该基准的关系。  
   把握：确定。

## 分区 10-ui-entry

1. **[严重度 高] 重选当前原币也会改变入账状态、清空换算依据**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/EntryCurrencyControls.kt:17`
   - 问题：币种选择回调没有排除“选择当前币种”。编辑一笔已经确认入账的 USD 消费、付款账户为 JPY 时，重新选择当前的 USD，就会执行 `settlementConfirmed=false`，并清空手填汇率及参考汇率快照。保存后，原本实际扣款的记录变成预计记录。反方向也有问题：把待确认外币记录切为账户币种，会直接设置 `settlementConfirmed=true`，无需用户确认实际到账。仓储确实按该标志决定 posting 是否生效。
   - 建议：选择当前币种应无副作用；币种变更与实际入账确认应分开处理，不能由币种选择隐式确认或撤销入账。
   - 把握：确定。

2. **[严重度 高] 自动汇率估算会因正常的未完成输入抛出未捕获异常**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/TransferForm.kt:58`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ReferenceRatePanel.kt:34`；`android/app/src/main/java/com/yohaku/expensetracker/domain/ledger/TransferEstimate.kt:14`
   - 问题：自动回填只检查转出金额可以解析且大于零，随后直接调用可能抛异常的估算函数。例如先填“转出内含手续费 100 JPY”，再输入转出金额 10000，输入到第一个字符 `1` 时，自动估算会因扣除手续费后金额不大于零而抛异常。`onAuto` 在 `LaunchedEffect` 中执行，没有手动“采用汇率”路径上的错误处理，能够导致界面崩溃。费用币种不同、账户费用金额尚未填完时也存在同一路径。
   - 建议：自动估算应识别表单尚未完成的状态，暂停估算并保留输入；自动和手动路径共用明确的估算结果及错误展示。
   - 把握：确定。

3. **[严重度 高] 展开日历后切换币种，日期字段缩减会越界崩溃**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/TransferForm.kt:22`、`:41`、`:94`；`android/app/src/main/java/com/yohaku/expensetracker/ui/components/Components.kt:257`
   - 问题：日期选择器用数组下标保存展开项。跨币种转账展开第三项“参考汇率查询日期”后，把转入账户切为同币种账户，日期列表从三项缩为两项，但 `owner` 仍为 `2`；`YDateFields` 随即访问 `fields[2]`。普通记账也有同类问题：展开外币记录的入账日期后，通过 `EntryCurrencyControls` 切为同币种，`NativeApp.kt:615` 保存的 `activeDate=1` 没有清除。
   - 建议：日期字段使用稳定标识保存展开状态；字段结构变化时主动关闭已不存在的日历，而不是继续使用旧下标。
   - 把握：确定。

## 分区 12-tests-1

1. **[严重度 高] 内存/临时数据库测试会清理真实应用偏好，并可能写入假汇率**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/` 下的 `ActivityFlowTest.kt:37`、`BudgetRecurrenceFlowTest.kt:40`、`AnalysisFlowTest.kt:38`、`CardPaymentFlowTest.kt:36`、`CommandFlowTest.kt:72`、`EntryFlowTest.kt:33–34、170–181`、`DraftEditingFlowTest.kt:37–54`、`FinancialProgressFlowTest.kt:36`、`FinancialReconciliationChainFlowTest.kt:36`、`FinancialReconciliationFlowTest.kt:36`、`FinancialRepaymentFlowTest.kt:36`、`FinancialRetroactiveFlowTest.kt:36`、`FinancialRuleModesFlowTest.kt:36`、`FinancialScaleFlowTest.kt:36`、`HomePlanningFlowTest.kt:33`、`LedgerCalendarTest.kt:34`、`LargeLedgerTest.kt:30`、`MerchantFlowTest.kt:39`、`ProcessRecoveryTest.kt:37`、`TestReferenceRates.kt:17`；关联生产代码：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt:144`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt:72`、`ReferenceRates.kt:59–66`
   - 问题：这些测试隔离了 Room，却通过 `AppPreferences(context)` 连接应用实际的 `app` DataStore（`app.preferences_pb`）：
     - ViewModel 初始化按测试库中的账本执行 `pruneBookPreferences()`。在已有应用数据的设备上运行，真实账本都不在测试库中，其提醒设置、前台定位授权偏好和当前账本选择会被删除，测试结束也没有恢复。
     - 假汇率服务 `testReferenceRates()` 同样使用该持久存储。自动获取开启且没有当天查询缓存时，打开换算面板就会把 `rate=1` 写入应用汇率缓存，生产代码将其保存为普通汇率快照，随后可能按“当天已查询”直接复用。
     - 另有测试直接改变触感、照片保存设置，结束时未完整恢复（`CommandFlowTest` 只恢复触感），还会留下选中账本等偏好；首次设置测试也没有初始化已有的设置草稿，造成测试间相互影响。
   - 建议：像 `HapticsFlowTest` 一样统一注入独立临时 DataStore，结束时关闭、删除；云端仓库、ViewModel 和测试汇率服务共享该测试实例；进程恢复测试使用专用持久偏好文件。
   - 把握：确定。
