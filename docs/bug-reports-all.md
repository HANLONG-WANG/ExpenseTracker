

# 分区 01-repo-a

已逐个完整阅读分区清单中的全部 37 个文件，共 3,442 行，无遗漏；另读取了相关调用方、DAO、命令适配器、测试源码和产品文档核实行为。全程只读，未运行构建、测试或模拟器。

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

4. **[严重度 中] 额度基准漏检分期、循环占用变化，继续展示旧子额度**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/CreditLimits.kt:132`
   - 问题：基准比较只检查 `date/total/kind`，遗漏 `installment/revolving`，随后又完全排除已在基准中的流水。例：基准包含一笔 1,000 的一次付消费，之后将其修正为分期，流水 ID、日期和总金额不变；系统既不更新分期占用，也不提示基准变化，仍按原子额度计算可用金额。
   - 建议：比较所有影响额度的字段；分类变化时明确要求重核基准，或计算对应子额度差额。
   - 把握：确定。

5. **[严重度 中] 日历和按日小计漏算转账手续费、借款费用及还款利息**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/CurrencyTransactions.kt:31`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/LedgerCalendar.kt:94`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/LazyPage.kt:43`
   - 问题：`dailyExpense()` 先只保留 `EXPENSE`，再只累计这些事件的费用组件。实际转账手续费属于 `TRANSFER`，借款费用属于 `BORROWING`，还款利息属于 `REPAYMENT`，全部被过滤。例：当天只有还款利息 100（或一笔转账及已确认手续费 100），月支出、预算以及首页通过 `visibleComponents()` 计算的月度消费都增加 100，日历当天及按日小计却显示 0，日历月合计也与月预算不一致，违背 README 声称的日历与月度消费同口径。
   - 建议：消费本金和费用组件分别筛选；费用按其所属可见事件、确认状态及预算排除规则计算。同步核对云端创建此类记录后的展示口径。
   - 把握：确定。

6. **[严重度 中] 信用卡周期消费关联银行预留时，现金需求重复扣减**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/CashTimeline.kt:35`、`:45`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/CardRecurrenceForecast.kt:39`
   - 问题：预留去重要求周期实例的 `accountId` 等于预留账户。信用卡周期消费使用信用卡账户，其实际还款预测使用银行账户，因此无法匹配为同一笔用途。例：信用卡周期消费 1,000，关联银行预留 1,000，信用卡也由该银行还款；时间线先减预留 1,000，再减预计还款 1,000，错误显示需要 2,000。
   - 建议：按关联实例及其实际现金付款预测去重，不能只比较消费账户与预留账户。
   - 把握：确定。

7. **[严重度 中] 循环付款方式的未来周期消费从现金预测中直接消失**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/CardRecurrenceForecast.kt:22`；分区外依据：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPools.kt:29`
   - 问题：付款方式解析为 `REVOLVING` 后直接返回空列表。但循环池来源只读取已存在的正式流水及安排，不吸收这些未记账周期实例。于是未来周期消费既没有进入循环池增量预测，也没有留下“尚未覆盖”的现金风险项；没有循环池时同样静默消失。
   - 建议：将周期消费纳入临时循环池预测；暂不支持时，应保留明确的未覆盖风险项，不能把没有计算结果表现为没有现金需求。
   - 把握：确定存在遗漏；具体预测策略需结合产品要求确定。

8. **[严重度 中] 结束活动后仍继续生成活动期间外的周期待办，但无法正常支付**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/ActivityRepository.kt:41`；分区外依据：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt:165`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt:198`
   - 问题：结束活动仅释放预留、清除当前活动，关联周期规则仍保持启用。例：10 月活动关联一个未设置结束日期的月度规则，结束活动后，11 月实例仍会生成并进入预测；支付草稿继续携带该活动 ID，却被“结束活动只补录期间事实”拒绝。云端 `activity.end` 同样如此。
   - 建议：结束时明确处理期间外的规则和实例，例如终止后续发生或转换为日常，并在预览中展示影响；保留期间内待补事实。
   - 把握：确定。

9. **[严重度 中] 活动预留允许同一币种填写两个不同金额**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/ActivityRepository.kt:52`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/ActivityProjection.kt:19`
   - 问题：只检查本位币金额和账户金额分别为正、币种各自匹配，没有检查两种币种相同时金额一致。JPY 账本可保存“预算预留 1,000 JPY、银行预留 2,000 JPY”；预算按 1,000 扣减，现金预测按 2,000 扣减，后续使用还会按这个错误比例释放。
   - 建议：账户币种等于本位币时直接派生账户金额，或要求两者严格相等；手机和云端共用该约束。
   - 把握：确定。

10. **[严重度 中] 云端可为预留选择债务账户，保存后立即成为无效现金预留**
    
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/ActivityRepository.kt:53`；`android/app/src/main/java/com/yohaku/expensetracker/data/command/PlanningCommandAdapters.kt:147`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/CashTimeline.kt:51`
    - 问题：手机预留表单只提供资金账户，但仓库层仅检查账户启用和币种，云端适配器也直接调用它。因此 `activityReserve.save` 可以成功保存信用卡或贷款账户预留，随后现金预测将其标记为 `RESERVE_ACCOUNT_REQUIRED`，无法计算资金影响。正式保存条件与消费该数据的条件相互矛盾。
    - 建议：在仓库层统一限制为资金账户，并同步命令能力说明。
    - 把握：确定。

11. **[严重度 中] 修改预留所属月份时，确认页遗漏原月份的预算变化**

    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/ActivityRepository.kt:64`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ActivityScreens.kt:240`、`:249`、`:250`
    - 问题：预留编辑始终只生成新日期所在月份的前后投影。把 10 月的 1,000 预留移到 11 月，确认页只显示 11 月增加 1,000，漏掉 10 月释放 1,000；实际提交却同时改变两个时期。
    - 建议：日期跨月时返回并展示新旧两个月的预算影响，同月时合并为一项，沿用已有跨月记账预览方式。
    - 把握：确定。

**值得注意但不确定**

- `CurrencyTransactions.kt:75` 的 `confirmSettlement()` 独立改写事件、结算和流水，没有走统一写入链的金融预测、循环池及请客分摊处理。当前搜索到的调用只有测试，正式流程使用其他入口，因此尚不能确认现有用户路径受影响；这条旧接口容易产生与正式确认流程不同的结果。
- `FinancialLedgerRepository.kt:86`、`:130` 将同币种收付关系校验放在“费用非空”的分支内。费用留空时，可接受到账大于借款本金、平台实收大于实际扣款的数据。文档明确允许费用未知，但没有说明是否支持返利等额外资金来源；建议确认这类差额的业务定义，再决定限制方向。


# 分区 02-repo-b

已完整阅读 02-repo-b 清单中的全部 35 个文件（共 3162 行），并核对相关调用方、数据库定义、云端适配器和文档；未修改文件，未运行构建、测试或模拟器。

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

6. **[严重度 中] 退款流水补录丢弃明确填写的实际入账日**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/StatementEntryDrafts.kt:36`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerChangeReview.kt:119`；关联：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/CurrencyTransactions.kt:114`
   - 问题：补录草稿分别保存发生日和入账日，但退款保存只把 `draft.date` 传给 `RefundInput`，随后用它创建 settlement 和 posting。例如退款发生于 10 月 14 日、银行入账于 10 月 16 日，最终资金入账被记为 14 日，导致现金时间线和流水核对日期错误。本机和云端均经过该路径。
   - 建议：明确退款采用哪个日期；若产品规定退款日期等于入账日，应使用已确认的 `postedDate`，并同步补录界面与命令语义。
   - 把握：确定。

7. **[严重度 中] 他人请客允许同币种原额与本位币金额互相矛盾**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/TreatRepository.kt:35`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/TreatRepository.kt:63`；云端入口：`android/app/src/main/java/com/yohaku/expensetracker/data/command/PlanningCommandAdapters.kt:181`
   - 问题：只验证 `base` 是账本币种且大于零，没有验证同币种时与 `total` 相等。日元账本可通过云端保存整单 ¥1,000、本位币 ¥10,000 的两人平分记录；原币本人份额是 ¥500，朋友往来却按 ¥5,000 计算。本机编辑器会自动保持相等，仓库层和云端没有保持同一约束。
   - 建议：同币种要求金额相等，或统一由总额派生本位币金额；外币才接收独立折算值。
   - 把握：确定。

8. **[严重度 中] 解除周期实例关联后，继续使用已经过时的金额和账户**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt:52`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt:77`
   - 问题：完成的实例保留历史快照是合理的，但 `unlinkOccurrence` 转回 `PENDING` 时只修改状态和 `eventId`。例如已完成实例为账户 A、¥1,000，后来规则改为账户 B、¥2,000，再解除旧实例关联，它会以 A、¥1,000 重新进入待办及资金预测。直到后续重新物化月份才更新，导致同一待办的内容取决于是否重新进入页面。
   - 建议：重新进入待办时按当前规则更新实例字段，与 `restoreOccurrence` 的处理保持一致。
   - 把握：确定。

9. **[严重度 中] 后续未知金额会压掉已经确定的资金不足提醒**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialOutlook.kt:18`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/ReminderRepository.kt:34`
   - 问题：账户当前 ¥100、明天已知需付 ¥250、下个月还有金额未知的周期付款时，风险模型正确保留明天的缺口日期，但将整个预测期的最大缺口设为未知。提醒生成器遇到 `maximumGapMinor==null` 就直接跳过，明天已确定的 ¥150 缺口也不再提醒。
   - 建议：区分“整个期间最大缺口未知”和“已经确定存在缺口”；后者仍应生成提醒，金额采用明确的已知缺口依据。
   - 把握：确定。

10. **[严重度 中] 停用账户后，历史转账和退款无法正常编辑**

    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/TransferTransactions.kt:14`、`:35`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/CurrencyTransactions.kt:102`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/TransferForm.kt:17`、`:103`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/RefundForm.kt:21`；对照：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt:164`
    - 问题：
      - 转账保存无条件要求两端账户以及手续费账户启用，且校验发生在读取原转账之前；转账编辑表单的账户列表也仅取启用账户，`from/to` 找不到原账户，保存按钮永久禁用。保存转账后停用旧银行账户，再仅修改备注，也无法完成。
      - 退款表单允许保留当前停用账户，但仓储无条件要求 `account.active`，界面允许预览、实际却失败。
      - 普通收支已经允许保留原记录的停用账户，三种记录口径不一致；云端 `entry.update` 走相同路径，同样受影响。
    - 建议：区分保留原账户与选择新账户；编辑历史记录时允许保留原有停用账户，统一表单和仓储的编辑规则。
    - 把握：确定。

11. **[严重度 中] 空余额池调整月付款时会因空集合抛异常**

    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPoolPaymentChanges.kt:58`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPools.kt:188`
    - 问题：系统允许零期初、尚无消费的余额池。此时正常预测可以成功且不生成账单。用户在首次消费前调整月付款、办理费填写 0，原账单和新账单均为空，比较函数对日期并集调用 `.max()`，抛出异常，无法记录设置变更。
    - 建议：明确支持“目前没有付款义务”的比较，预测期限使用池定义中的付款范围，不依赖必须存在账单。
    - 把握：确定。

12. **[严重度 低] 规则变更预览按付款日判断影响范围，与实际算法不一致**

    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialRepository.kt:61`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPredictions.kt:54`；展示：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialReviews.kt:61`
    - 问题：规则预览把付款日不早于新规则生效日的账单全部标为“需重算”。但一次付、分期和固定贷款选择规则依据合同／消费日，循环模式依据计息区间。例如 10 月消费、11 月付款，新增 11 月生效的分期规则，预览会说需要重算，实际重算仍使用 10 月规则。
    - 建议：影响预览复用实际预测的规则选择依据，避免使用付款日作为统一替代。
    - 把握：确定。

**值得注意但不确定**

- `android/app/src/main/java/com/yohaku/expensetracker/data/repository/MediaRepository.kt:57`、`:66`：给已有记录导入小票时，只在文件处理前检查记录未删除，提交事务内没有复核。若处理期间记录进入回收站，仍可能追加图片；尚未确认界面忙碌限制是否排除了这条并发路径。


# 分区 03-command-cloud

已完整阅读本分区清单中的源码、脚本、Markdown/TXT及两份 DOCX 正文；大型 JSON 按约定核对结构、字段、枚举和示例，同内容副本做了全量比较；两份完整 `formats` 目录一致。未修改文件，未运行构建、测试或模拟器。

1. **[严重度 高] 参考服务会因提交后的日志失败删除已登记的源文件**

   位置：`docs/mcp-implementation-handoff-package/reference/lib/service.ts:32`、`:34`、`:36`

   问题：上传的 `try/catch` 同时包住 R2 写入、数据库提交、提交后查询和日志写入。数据库已成功保存文件元数据后，如果后续查询或日志失败，catch仍删除R2对象，却不撤销数据库记录。重试又命中第32行的去重记录并返回成功，形成无法通过正常重传修复的“元数据存在、文件已丢失”。

   建议：明确数据库提交边界；仅在确认文件尚未登记时清理R2对象，日志应纳入相应事务或独立处理失败。

   把握：确定；定位于交接包参考实现。

2. **[严重度 中] 保存时仍接受已经撤回或过期的领取结果**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt:360`、`:376`、`:385`

   问题：`validateClaim()`只核对历史结果ID、版本、文件哈希，没有核对任务仍为ready、结果仍为ready、`stale=false`和当前输入版本。用户打开预览后，云端重新分析或发布新结果，即使前台轮询已经更新了本地任务详情，旧预览仍能保存。后续ack的409只留下提示，不会撤销已经保存的旧资料。

   建议：保存前重新核验当前可领取结果身份；发现撤回或换版时使旧预览失效，要求重新领取。

   把握：确定。

3. **[严重度 中] 流水差异关联无法选择同一账户下的多个资金端**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/data/command/FileCommandAdapters.kt:55`；关联实现：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/TransferTransactions.kt:45`

   问题：`statement.accept`只接受entry，然后用“记录ID＋账户ID”取 `.single`。正常的转账若由转出账户另付手续费，该账户会同时存在`:from`和`:fee`两条分录，命令直接因匹配多项失败。手机业务实际接受的是明确的`postingId`，云端接口却没有表达这个选择的字段。

   建议：增加明确的资金端引用或解析命令，并同步导出、schema和文档；不要用第一项匹配兜底。

   把握：确定。

4. **[严重度 中] 互请尾差补丁会被静默忽略，省略字段也不能保留原值**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/data/command/EntryCommandAdapters.kt:77`、`:103`

   问题：`treatTail`被排除在普通字段映射之外，只在同时提供`treatShares`时处理。因此单独提交`set.treatTail`会通过校验，却不改变尾差归属。另外，更新份额但省略`treatTail`时会重建整个weights，丢掉原`__tail`，违反补丁“省略保留”的约定，可能改变分摊金额。

   建议：独立处理尾差补丁，并区分未提供、显式清空和指定参与人；更新份额时保留未修改的尾差设置。

   把握：确定。

5. **[严重度 中] 记录导出缺少转入端日期和确认状态**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandExports.kt:16`、`:110`

   问题：转账只导出`toAccount/toMinor/toCurrency`，没有`toDate/toConfirmed`；统一`confirmed`取自转出端settlement。例如转出已确认、转入仍在途，导出却只有`confirmed=true`，无法区分在途和已到账，也无法还原跨日转入。协议又指导助手通过`confirmed`判断是否确认。

   建议：导出两端独立日期和确认状态，明确统一字段的含义，同步两份records schema及协议。

   把握：确定。

6. **[严重度 中] 重新连接成功后，任务仍可能永久显示“已暂停”**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt:114`、`:120`、`:414`；`android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudEntities.kt:40`；`android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudModels.kt:76`

   问题：401会把任务stage改为BLOCKED；重新绑定只恢复PREPARED/UPLOADING任务，成功刷新也不清除BLOCKED。该状态又优先于READY和IMPORTED。因此凭证修复、回执成功甚至结果已经可领取后，卡片仍显示暂停，只能通过不符合实际状态的“继续上传”绕回正常状态。

   建议：记录阻塞原因及原传输阶段；连接恢复后恢复相应任务，成功刷新应解除认证类阻塞，避免把所有恢复都当成重新上传。

   把握：确定。

7. **[严重度 中] 失败回执被显示成“本地已导入”**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/CloudScreens.kt:255`；`android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudModels.kt:65`；`android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt:403`

   问题：`ackPending`包含所有未发送回执，不区分imported和failed。实际保存失败或用户报告无法导入后，失败回执尚未发出期间，界面反而显示“本地已导入……不会重复导入”，与真实结果相反。

   建议：按回执状态和本地导入记录分别显示成功、失败及发送状态；月评保存也应避免暗示建议已执行。

   把握：确定。

8. **[严重度 低] 分类和模板 schema 暴露了永远不支持的种类**

    位置：`android/app/src/main/java/com/yohaku/expensetracker/data/command/LedgerCommandAdapters.kt:84`、`:94`、`:209`；关联实现：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/TemplateRepository.kt:32`

    问题：两条命令直接导出完整`EntryKind`枚举，包括TRANSFER、REPAYMENT、BORROWING、REFUND；实际分类和模板始终只允许EXPENSE/INCOME。这些输入能通过附件schema，却必然在手机被拒绝，不属于依赖当前账本状态的动态限制。

    建议：在适配器schema中收窄枚举，重新生成能力清单和两份方案schema。

    把握：确定。

9. **[严重度 低] 交接包哈希清单与实际附件不一致**

    位置：`docs/mcp-implementation-handoff-package/MANIFEST.json:19`、`:25`

    问题：`reference/README.md`实际SHA-256以`7ce3b7b2`开头，清单记录`4e869c4c`；`reference/lib/formats/README.md`实际以`33a8a4a9`开头，清单记录`6bc8dbd4`。按交接清单核验会把当前附件判为不完整或被修改。

    建议：更新附件后同步生成哈希；若只记录历史基线，应明确标识并另提供当前包校验值。

    把握：确定。

10. **[严重度 低] MCP发现响应示例遗漏已经实现的事件能力**

    位置：`docs/mcp-implementation-handoff-package/rpc-examples.json:13`；`docs/mcp-implementation-handoff-package/reference/app/mcp/route.ts:13`

    问题：示例只声明`capabilities.tools`，缺少实际响应中的`resultType:"complete"`和`capabilities.events`。按示例实现兼容适配器会遗漏本交接包已经支持的事件发现能力。

    建议：同步发现响应，并补充事件订阅与取消的请求示例。

    把握：确定。

11. **[严重度 低] 交接包中的相对链接指向不存在的位置**

    位置：`docs/android-cloud-handoff-package/formats/README.md:3`；`docs/mcp-implementation-handoff-package/reference/lib/formats/README.md:12`

    问题：前者的`../implementation-spec.md`指向包内不存在的文件；后者的`examples/`目录不存在，实际样例在包根的`fixtures/`。复制正文保持一致，并未保持链接可用。

    建议：采用与交付目录匹配的链接；独立交付时包含被引用文档，或明确链接到仓库中的正式位置。

    把握：确定。

**值得注意但不确定**

- `CloudRepository.kt:103`的401处理没有绑定凭证版本：后台旧凭证请求晚返回401时，可能把刚保存并验证成功的新配置再次标记为需要重连。存在代码层面的竞态窗口，未运行并发场景验证。
- 未核查线上站点实际部署版本；上述服务端问题确认存在于仓库交付的参考源码，不能据此断言线上当前版本完全相同。


# 分区 04-db-domain

已完整阅读清单中的 62 个非生成 Kotlin 文件；`CategoryIconCatalog.kt` 已读完逻辑和全部文案，生成的 SVG 路径未逐字阅读；41 份 Room schema 已核对版本变化、表结构、字段、索引及外键与迁移的一致性。全程只读，未运行构建或测试。

1. **[严重度 高] 末期本金未知时，重算可能假定偿还了超过账单总额的本金**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/ReconciledSchedule.kt:139`、`:143`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPredictions.kt:144`
   - 问题：非末期已确认账单缺少本金时返回 `Incomplete`，末期却直接使用预测本金，且没有受已确认总额约束。例如来源本金 ¥10,000，唯一一期已确认总额 ¥5,000、本金和费用均未知、尚未付款：重算会假设未来偿还 ¥10,000 本金，显示剩余本金为零，并将计划标为完整。实际 ¥5,000 的总付款不可能偿还 ¥10,000 本金。仓储允许保存这种部分已知账单，因此并非只能通过构造非法数据触发。
   - 建议：末期同样保留本金未知状态；无法从已确认信息确定拆分时，不推导全部清偿，也不标记完整。
   - 把握：确定。

2. **[严重度 中] 固定贷款的日计息区间会重复计算或漏算付款日**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/LoanCalculation.kt:41`、`:59`；`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/ReconciledSchedule.kt:94`；`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/PaymentCalculation.kt:105`
   - 问题：相邻期次直接以上一期付款日作为下一期开始日，但界面允许起止日都计入或都不计入，固定贷款没有调整相邻端点。例如本金 ¥100,000、年利率 3.65%、365 天、按期付息到期还本，开始日 2026-01-01，付款日 02-01、03-01，两端都计入：分别计算 32 天和 29 天，利息合计 ¥610；连续区间实际只有 60 天，应为 ¥600。两端都不计入则会漏掉中间付款日。循环计划已有端点衔接处理，固定贷款口径不一致。
   - 建议：统一生成连续、不重叠的实际计息区间，试算和实际清偿后的重算共用该逻辑。
   - 把握：确定。

3. **[严重度 中] 月末首期日期被截短后，后续候选日期丢失原始日号**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/RulePreview.kt:17`、`:25`；`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/LoanCalculation.kt:22`；分区外共同问题：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/PredictionCandidates.kt:20`、`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialArrangementEditor.kt:114`、`:195`、`:197`
   - 问题：日期生成使用“首期实际日期 `plusMonths`”，而非原始付款日。例如等额本息贷款开始于 2026-01-31，首期为 02-28，生成结果是 02-28、03-28；但计算器按开始日生成的合法日期是 02-28、03-31，于是正常月末贷款被自己的试算入口判为不支持。付款日为 31 日的分期候选也会在首期为二月时持续落到每月 28 日（应为 02-28、03-31、04-30）；付款方案编辑器的计费截止日也有相同问题，可能进一步影响按日计息。云端 `financialPrediction.save` 的候选日期复用了同一仓储函数。
   - 建议：区分“按规则日号生成”和“按用户指定日期生成”；规则生成保留原始日号或明确的月末锚点，逐月独立截短；候选生成与计算器校验共用日期规则。
   - 把握：确定。

4. **[严重度 中] 一次付和分期预测允许付款日期早于债务来源日期**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/PredictedSchedule.kt:41`、`:56`；`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/PaymentCalculation.kt:39`；`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/ReconciledSchedule.kt:28`
   - 问题：付款日期只检查递增；一次付直接把传入日期作为实际日期覆盖，分期也没有检查首期与 `agreementDate` 的关系。`reconciledSchedule` 的来源日期检查仅覆盖已有保留账单，遗漏新输入的期次。例如 2026-05-01 的消费，输入 04-27 付款，仍可生成并保存预测，被当作来源出现前就已到期的义务。固定贷款和已办理变更入口有相关检查，普通预测入口没有。
   - 建议：在共享领域入口校验所有付款日期与来源日期的先后关系，同时约束本机编辑和云端直接提交的 `terms`。
   - 把握：确定。

5. **[严重度 中] 非法费率被当作“不完整规则”接收，但展示和重新编辑会抛异常**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/FinancialRules.kt:38`、`:95`；`android/app/src/main/java/com/yohaku/expensetracker/domain/finance/FinancialRuleDraft.kt:45`、`:48`；分区外入口：`android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandAdapters.kt:75`
   - 问题：`annualRate`、`totalRate` 是普通字符串，诸如 `"18%"` 的输入只被归为规则缺项；仓储允许不完整规则，云端 schema 也没有数值字符串约束。但 `toForm()` 和规则展示直接调用 `toBigDecimal()`。这类规则进入预览、历史详情或编辑时，会触发 `NumberFormatException`，无法正常展示问题并修正。这里混淆了“未知、未填写”和“已填写但格式错误”。
   - 建议：保存前拒绝非法数值字符串，未知统一使用 `null`；同步云端 schema/说明，并确保已有不合法规则可展示原值和错误、允许修正。
   - 把握：确定。

6. **[严重度 中] 小票商品仍标记不确定，列表却不显示“明细待确认”**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/data/files/ReceiptTotals.kt:11`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ReceiptWorkflowScreen.kt:32`
   - 问题：`detailsComplete` 只检查 `complete`、行金额非空和记录级 `issues`，完全忽略商品级 `uncertain_fields`。例如结果声明明细齐全、金额都有值，但某商品包含 `uncertain_fields=["line_amount"]`，列表仍只显示“已整理”，漏掉“明细待确认”。详情虽然能看到不确定字段，工作台的待确认提示却失效，与 `docs/implementation-spec.md:240` 的疑点提示要求不一致。
   - 建议：将商品级不确定字段纳入待确认判定；图片是否已整理继续保持独立判断。
   - 把握：确定。

**值得注意但不确定**

- `FinancialRules.kt:74`、`:149` 要求填写付款分配顺序，但 `PaymentCalculation.kt:148` 的计算不使用该顺序，只在计算依据里记录它；检索仓储也未发现其参与分配。尚不能确定这是仅保存平台约定，还是漏实现“先本金／先费用”的业务效果。建议明确其作用，避免用户把必填设置理解为已影响计算。


# 分区 05-platform-receipt-build

已完整阅读清单内全部文本文件，包括平台层、扫描引擎与界面、Manifest、全部 XML 资源、许可证、随包协议、Gradle 脚本及 media-verifier；二进制按要求核对类型、引用和模型哈希/长度，没有未读完的文本文件，也未修改文件或运行构建、测试及设备命令。

1. **[严重度 高] 扫描结果在入库成功前失去恢复点，失败后唯一照片被删除**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/scan/ScanFlow.kt:72`；`android/app/src/main/java/com/yohaku/expensetracker/receipt/scan/ScanTemporaryFiles.kt:29`；分区外 `android/app/src/main/java/com/yohaku/expensetracker/ui/features/MediaPanels.kt:142`、`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt:415`、`:425`
   - 问题：确认使用后，先移动选中照片并删除整个扫描会话，再清空扫描请求、异步入库。入库的 `finally` 无论成功失败都删除输出目录。例如缓存中已有拍摄照片，但剩余空间不足以复制附件或生成缩略图，入库失败后原图和输出都会消失，用户只能重拍。此外，导出期间界面重建取消协程，可能在文件已经移动后丢失返回值；保存状态仍指向已删除的旧目录，新输出路径没有恢复记录。
   - 建议：保留可恢复的输出路径和交接状态；入库成功后才清理唯一输出，失败时保留照片供重试。文件移动与状态交接应能跨界面重建恢复。
   - 把握：确定。

2. **[严重度 中] 跨月小组件漏算尚未物化的周期预留**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/platform/desktop/DesktopCoordinator.kt:57`；`android/app/src/main/java/com/yohaku/expensetracker/platform/desktop/WidgetRenderer.kt:62`；分区外 `android/app/src/main/java/com/yohaku/expensetracker/data/repository/BudgetProjection.kt:31`
   - 问题：小组件直接对数据库快照调用 `budgetProjection()`，只计算已保存的周期实例。新月份实例主要在打开账本界面时由 `materializeMonth()` 创建，桌面午夜刷新和后台维护都没有补齐。例如月预算 100,000 日元、每月房租预留 30,000 日元，跨月后尚未打开该账本，小组件会把自由可花显示为 100,000，而非 70,000，日均也随之偏高。
   - 建议：桌面计算复用覆盖本月的周期预测，或在读取快照前统一物化本月实例，保持与应用内预算口径一致。
   - 把握：确定。

3. **[严重度 中] 汇总通知中一项变化，会撤掉其他仍有效的提醒**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/platform/ReminderCoordinator.kt:74`、`:76`、`:97`
   - 问题：同账本多项提醒合并为一条通知后，只要旧通知中的任意一项不再出现在当前事实中，就取消整条通知。例如 A、B 两张卡同时提醒，用户在应用内处理 A，B 的通知也被撤掉；但 B 已登记为当天投递过，不会随重建恢复，直到下一次提醒节点才可能出现。
   - 建议：按仍有效的事项更新通知内容；全部失效时才取消。更新已有通知与再次提醒的去重应分开处理。
   - 把握：确定。

4. **[严重度 中] 财务详情通知被标记为锁屏公开内容**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/platform/ReminderCoordinator.kt:85`、`:91`、`:94`
   - 问题：通知包含账本名、账户名、金额、日期和事项，却使用 `VISIBILITY_PUBLIC`。在允许显示通知、仅隐藏敏感内容的锁屏设置下，这些财务详情仍被应用声明为可公开显示。
   - 建议：使用 `VISIBILITY_PRIVATE`，需要锁屏提示时提供不包含金额、账户等详情的公开版本。
   - 把握：确定。

5. **[严重度 中] 持续的扫描引擎故障被无限当作单帧异常丢弃**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/scan/ScanFrameFailures.kt:11`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/scan/ScanCameraSource.kt:137`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/scan/ScanCameraScreen.kt:222`；关联测试：`android/app/src/test/java/com/yohaku/expensetracker/ui/features/scan/ScanFrameFailuresTest.kt:7`
   - 问题：创建引擎、模型复制、推理和坐标映射的所有 `Exception` 都只累计计数并继续下一帧，没有持续失败状态，也不调用 `listener.failed`。例如首次使用时空间不足导致模型复制每帧都失败，或 CPU 推理持续失败，正式版仍显示“把小票放在中间”，自动拍摄永远不触发；用户无法区分拍摄姿势问题和引擎故障。`ScanFrameFailuresTest` 主动抛出 `IllegalStateException` 并要求连续失败全部返回 `null`，把这种无限失败状态固化成了正确行为。
   - 建议：保留偶发坏帧的丢弃行为，但将初始化失败或持续失败明确呈现，并提供重试入口；测试保留短暂异常恢复，并验证持久故障能暴露出来。
   - 把握：确定。

6. **[严重度 中] 轮廓调整的“无障碍动作”实际上只有测试能调用**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/scan/ScanContourCanvas.kt:23`、`:44`
   - 问题：`MoveScanHandle` 是自定义 `SemanticsPropertyKey`，并未通过标准 `customActions` 等机制暴露给 Android 无障碍服务。十个控制点也没有独立的可访问节点。TalkBack 用户只能听到“拖动控制点”的描述，无法选择、移动控制点；仪器测试能调用这个自定义属性，不代表实际无障碍可用。
   - 建议：为控制点提供可聚焦节点及标准无障碍操作，例如选择控制点后按方向移动；测试入口可继续共用同一移动函数。
   - 把握：确定。

7. **[严重度 中] 小组件允许的最小高度与响应式布局声明不一致**
   
   - 位置：`android/app/src/main/res/xml/yohaku_widget.xml:1`；`android/app/src/main/java/com/yohaku/expensetracker/platform/desktop/WidgetRenderer.kt:28`
   - 问题：组件允许高度缩到 110dp，但 Android 12+ 的两份布局都声明至少需要 140dp 高。对宽度足够、实际高度介于 110–139dp 的组件，两种布局都不满足尺寸条件，系统会回退选择最小布局，宽组件也可能变成没有模板区域的小版。布局自身也没有对应的矮版。
   - 建议：统一组件最小高度、RemoteViews 尺寸键和实际布局需求；如果支持 110dp，就提供真正适配该高度的布局。
   - 把握：较可能，具体表现取决于启动器提供的尺寸。

8. **[严重度 中] 手动处理发生普通错误后，轮廓草稿仍在但编辑界面被挡住**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/scan/ScanReviewScreen.kt:44`、`:68`、`:73`、`:117`
   - 问题：手动重新处理遇到非 `ScanGeometryUnavailable` 错误时，`failed=true` 会隐藏整个轮廓画布。提示却要求“重试或调整轮廓”，而编辑状态下没有清除错误并返回画布的入口，只能重复执行同一轮廓。若从未检测到状态进入手动编辑且还没有成功结果，“取消调整”还会直接退出整个流程。
   - 建议：已有图片和草稿可用时保留编辑画布，将处理失败作为独立提示；或增加明确的返回调整操作。初始读取失败应使用不同文案。
   - 把握：确定。

9. **[严重度 低] 小组件配置页跟随系统深色模式设置系统栏，与固定浅色主题冲突**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/platform/desktop/WidgetConfigurationActivity.kt:32`；对照 `android/app/src/main/java/com/yohaku/expensetracker/MainActivity.kt:33`
   - 问题：主界面明确采用浅色纸面对应的深色系统栏图标，配置页却直接调用默认 `enableEdgeToEdge()`。系统处于深色模式时，默认策略可能采用浅色状态栏图标，但配置页三套主题仍是浅色背景，造成图标对比度不足。
   - 建议：配置页复用与应用主题一致的系统栏策略。
   - 把握：较可能，需设备视觉确认。

10. **[严重度 低] QNN 许可证转文本后双栏内容交错，正文阅读顺序损坏**
    
    - 位置：`android/app/src/main/assets/licenses/QNN-Runtime-LICENSE.txt:14`
    - 问题：`GRANT OF LICENSE` 的连续句子被另一栏的高风险用途条款逐行插入，例如 `Subject to and conditioned upon Your` 后接 `(9) administration of justice...`，原条款无法顺序阅读。这是提取顺序错误，不是普通换行问题。
    - 建议：从原始 PDF 按正确栏序重新提取并逐段核对，或同时提供原始 PDF，保持许可措辞不变。
    - 把握：确定。

**值得注意但不确定**

- **高分辨率照片的内存峰值**：`ScanCameraSource.kt:116` 请求最高可用照片分辨率；`ReceiptScanner.kt:129` 全尺寸解码；`ReceiptGeometry.kt:77` 创建全尺寸旋转中间图；`ScanReviewState.kt:94` 又全尺寸解码结果预览。输出上限检查发生较晚。存在高分辨率设备或大图处理时内存不足的风险，但未运行真机，无法确认实际协商分辨率及峰值。
- **用户手动固定的快捷方式可能残留旧信息**：`DesktopShortcuts.kt:23` 只移除动态快捷方式，未处理用户从启动器拖到桌面的固定副本。模板删除或切换账本后，固定图标可能继续保留旧名称及入口；点击后的应用内失效检查已有实现，但启动器侧行为尚未验证。


# 分区 06-components-theme-commandui

已完整逐行阅读清单中的全部 62 个 Kotlin 文件（components 51 个、command 6 个、haptics 3 个、theme 2 个），并核对相关调用方、数据模型和触感文档；没有修改文件，也没有运行构建、测试或模拟器。

1. **[严重度 高] 云端逐项核对将不同币种的费用按同一币种显示**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandEffectViews.kt:230`、`:304`
   - 问题：金额格式化仅为 `baseMinor`、`expectedBaseMinor`指定账本币种，遗漏了 `feeBaseMinor`、`sourceFeeBaseMinor`、`withheldFeeBaseMinor`、`separateFeeBaseMinor`。这些字段会继承债务对象的币种。例如人民币账本中的日元债务，`feeBaseMinor=10000` 实际是 `CN¥100.00`，核对页却显示 `¥10,000`。`sourceFeeMinor` 也会按债务币种显示，而它实际属于付款账户币种。已对照 `CommandConfirmation.kt:149` 和本机还款编辑器确认字段含义。
   - 建议：按对象类型和字段明确携带币种，分别处理本位币、债务币及付款端币种，避免依靠统一继承推断。
   - 把握：确定。

2. **[严重度 中] 修改原币种时，摘要用新币种解释修改前金额**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandEffectViews.kt:87`、`:149`
   - 问题：`EffectSummary` 优先取 `after.currency`，随后用同一个 `amount()` 格式化前后两边。原记录为 `JPY / 1000`，修改为 `CNY / 10000` 时，摘要会显示 `CN¥10.00 → CN¥100.00`，而正确的修改前金额是 `¥1,000`。`entry.update` 允许修改普通支出的原币金额及币种。
   - 建议：前后金额分别使用各自快照中的币种。
   - 把握：确定。

3. **[严重度 中] 月度上传预览截断正常的两级分类名称**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/MonthlyScreens.kt:102`、`:103`、`:114`
   - 关联：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandScreens.kt:347`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt:74`
   - 问题：分类路径由 `父分类 + " / " + 子分类` 生成，却被交给按 `" / "` 分割中日文的 `localizedCommand()`。例如“餐饮 / 午餐”在中文界面只剩“餐饮”，日文界面只剩“午餐”。多个子分类因此可能显示同名，用户无法准确核对分类汇总与预算。
   - 建议：用户名称和分类路径原样显示；仅对明确属于双语系统文案的字段做语言选择。
   - 把握：确定。

4. **[严重度 中] 通用字段渲染会替换用户原文**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandEffectViews.kt:319`、`:322`、`:327`
   - 问题：判断依据是字符串内容，而非字段类型。例如备注“@小王 代付午餐”会因为以 `@` 开头，被当成内部引用并显示为“关联对象”；名称或备注恰为 `NORMAL`、`ME`、`INCOME` 时，也会被替换成枚举译文。逐项核对因此无法忠实展示实际保存内容。
   - 建议：仅对明确的引用字段解析 ID，仅对对应枚举字段翻译值；名称、备注、计划正文保持原文。
   - 把握：确定。

5. **[严重度 中] 时区被当作对象 ID，核对页看不到真实时区**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandEffectViews.kt:323`、`:281`
   - 问题：所有以 `Id` 结尾的字段都调用 `resolveName()`，包括 `zoneId`。金融规则或额度配置中的 `Asia/Tokyo`、`Asia/Shanghai` 都会显示为“关联对象”。修改时区时甚至呈现“关联对象 → 关联对象”，掩盖实际变化。
   - 建议：将 `zoneId` 作为时区字段直接展示，并用明确的实体引用字段集合替代后缀判断。
   - 把握：确定。

6. **[严重度 中] 历史付款变更的数组索引错位，可能使用错误时区**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandEffectViews.kt:233`
   - 关联：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandFinanceViews.kt:208`
   - 问题：`flattenFacts()` 用从 1 开始的数组路径，`commandFieldZone()` 却直接将路径数字传给从 0 开始的 `getOrNull()`。第一条付款变更会读取第二条，最后一条则读取不到并退回外层计划。若历史规则的时区不同，就会按另一生效日期对应的规则展示 `appliedAt`，与该操作的真实办理时间不一致。
   - 建议：统一内部路径索引；显示编号与数据索引分开处理。
   - 把握：确定。

7. **[严重度 中] 云端草稿的业务字段在摘要和逐项详情中同时缺失**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandDraftPreview.kt:31`、`:47`
   - 关联：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandFollowupViews.kt:161`
   - 问题：`entry.prepareDraft` 支持并保存完整草稿，但摘要未展示 `budgetExcluded`、`historicalIncluded`、结算确认状态、手续费位置/付款账户、互请分摊等字段；“逐项核对”的白名单又把这些字段过滤掉。例如云端指定“不计入预算”或“手续费由另一账户单独支付”，用户在方案确认页无法核对这些实际会写入草稿的设置。
   - 建议：详情保留全部有业务含义的草稿字段，仅隐藏存储身份；摘要突出非默认设置。
   - 把握：确定。

8. **[严重度 中] 旋转屏幕后，办理需求的附件清单静默丢失**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/command/CommandScreens.kt:49`、`:55`、`:95`
   - 问题：任务名称、需求和范围使用 `rememberSaveable`，附件列表却只使用 `remember`。选择附件后旋转屏幕，Activity 重建会保留文字并清空附件；摘要重新准备完成后，提交按钮仍然可用，任务可能缺少原先选择的截图或文件。Manifest 没有接管配置变化。
   - 建议：保存附件 URI 清单或由 ViewModel 持有，并同步处理恢复后的读取权限与失效提示。
   - 把握：确定。

9. **[严重度 中] 活动分类预算超支显示为负的“超出金额”**
   
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/components/BudgetLayout.kt:75`
   - 关联：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ActivityScreens.kt:106`；`android/app/src/main/java/com/yohaku/expensetracker/domain/model/Money.kt:13`
   - 问题：组件用 `removePrefix("-")` 去除剩余额度的负号，但 `Money.display()` 将币种符号放在负号前。预算 1,000 日元、已花 1,200 日元时，传入的是 `¥-200`，最终显示“已超出 ¥-200”，而非“已超出 ¥200”。
   - 建议：从数值计算超支绝对额，再进行货币格式化。
   - 把握：确定。

10. **[严重度 低] 小物主题的互请茶杯没有正确表示占比**
    
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/components/FriendBalanceCard.kt:68`
    - 问题：茶水路径仅移动起点，高度和面积保持不变，且没有裁剪到杯内。`fill=0` 时仍绘制整块茶水，底部到达设计坐标 `y=95`，杯底却在 `y=78`；`fill=1` 时整块水向上移动，而非填满杯子。单方请客或双方均为零时就能触发。
    - 建议：固定杯底，根据比例改变液面高度，并将水面裁剪到杯内；零占比不绘制水。
    - 把握：确定。

11. **[严重度 低] 小物主题把匹配半径标成直径**
    
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/components/PlaceMapLayout.kt:106`
    - 问题：传入的 `radius` 是匹配半径，地图圆也按该半径绘制，但小物主题显示 `⌀ 100m`。直径符号表示直径，实际覆盖圆的直径却是 200 米，与手账主题的“半径 100m”含义不一致。
    - 建议：明确显示“半径”，或若使用直径符号则显示两倍数值。
    - 把握：确定。

12. **[严重度 低] 所有主题提示都带成功勾号，包括失败或失效提示**
    
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/components/ThemedToast.kt:47`
    - 关联：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:203`、`:442`
    - 问题：`YToast` 无条件绘制 `✓`。桌面入口遇到模板已删除、暂不可用或资料变化时，也通过该组件显示，形成“✓ 模板暂不可用，请检查并编辑”的矛盾表达。
    - 建议：提供提示语义类型，分别使用各主题的成功、提醒标记；普通信息也可不带状态图标。
    - 把握：确定。

13. **[严重度 低] 两处触感行为与文档规定不一致**
    
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/components/BottomNav.kt:25`、`:39`；`android/app/src/main/java/com/yohaku/expensetracker/ui/components/MerchantLayout.kt:65`
    - 问题：底部导航用映射后的 `active` 判断选中状态，却用原始 `route` 判断是否震动。流水页已选中“今天”，再次点“今天”仍发 `TabSwitch`，不符合“选中项改变时才震动”。另外，商户展开区的“记一笔”没有传 `Haptic.Compose`，调用方也未补发，导致这个记账入口缺少规定的开始记账反馈。
    - 建议：导航使用 `!selected` 判断触感；商户记账按钮显式传入 `Haptic.Compose`。
    - 把握：确定。

**值得注意但不确定**

- `ThemedSheet.kt:43`：`sheetSticky()` 使用 `positionInRoot()` 缓存位置，同时用 `graphicsLayer.translationY` 调整位置。坐标回调是否会把已平移的位置再次纳入计算、造成固定区域抖动，需要实际滚动验证；此次未按确定问题报告。
- `SystemHapticOutput.kt:28`、`:41`：系统开关检查和触摸用途标记均已实现，但在目标三星设备上是否随系统触摸振动强度正确缩放，静态阅读无法确认；`docs/haptics.md` 也明确保留了真机定稿事项。


# 分区 07-ui-shell

已逐个完整阅读清单中的全部 24 个文本文件（共 3725 行），并补读相关仓储、组件、云端续办代码及规格文档；没有未读完的清单文件，也未修改文件或运行构建、测试、模拟器。

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

3. **[严重度 中] 模板编辑仅保存名称，其余输入在重建后全部回退**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/TemplateScreens.kt:38、85、86`
   - 问题：编辑目标和名称使用 `rememberSaveable`，承载分类、账户、金额、商户、备注等字段的 `d` 却使用 `remember`。旋转屏幕后，模板弹层及新名称仍在，其余字段恢复原模板值；新增模板则回到初始值，形成容易误保存的半恢复状态。
   - 建议：完整保存模板编辑状态，或使用统一的编辑 ViewModel。
   - 把握：确定。

4. **[严重度 中] 账户筛选后的日分组与支出小计采用不同日期**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/LazyPage.kt:40、43、59`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/LedgerScreen.kt:111`
   - 问题：账户流水按 posting 的入账日期分组，但小计仍按事件发生日期计算。例如 10 月 31 日消费、11 月 2 日入账的 100 元记录，在账户筛选下显示于 11 月 2 日，支出小计却记在不可见的 10 月 31 日键上；11 月 2 日标题不显示这笔支出。
   - 建议：明确账户视图的日期口径，并让筛选、分组和小计使用一致的日期，或明确区分“入账额”与“发生日净消费”。
   - 把握：确定。

5. **[严重度 中] 同币种记录的独立入账日期被编辑页和详情页隐藏**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:452、551、616`；关联 `android/app/src/main/java/com/yohaku/expensetracker/data/repository/StatementEntryDrafts.kt:36、69`
   - 问题：流水核对支持同币种交易具有不同的发生日和入账日，但表单仅在 `foreign` 时显示入账日期，详情也排除了已确认的同币种结算。发生于 10 月 31 日、入账于 11 月 2 日的记录，用户在常规编辑中只能看到发生日，无法直接核实或修改实际影响账户余额的日期。
   - 建议：不要以是否跨币种决定入账日期的可见性；至少在两日期不同、待确认或来自流水核对时展示和允许编辑。
   - 把握：确定。

6. **[严重度 中] 外部入口切换账本后，旧账本的记账确认弹层仍会显示**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt:159、169、176、479`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:340`
   - 问题：切账本清空了 `entry`，却没有清空 `entryReview` 和 `negativeBalanceReview`；这两个弹层也没有按账本过滤。在 A 账本预览修改或确认负余额时，点击 B 的通知／桌面入口，B 页面上仍会出现 A 的账户和金额。继续确认又因 `entry == null` 而直接返回，按钮没有效果。
   - 建议：统一重置或按账本保存编辑会话及其预览；所有确认弹层必须绑定账本和草稿身份。
   - 把握：确定。

7. **[严重度 中] 直接修改路由破坏返回栈，续写草稿和删除记录后表现异常**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt:95、209、227`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:375、430`
   - 问题：
     - 流水列表 → 详情 → 选择继续已有草稿，`resumeEntry()` 直接替换路由，没有将详情入栈。保留草稿返回详情时，`leaveEntry()` 找不到详情栈项而清空栈；再关闭详情会回首页，而非流水列表。
     - 从流水详情删除记录时，`replaceRoute("ledger")` 保留栈顶的 `"ledger"`；第一次返回仍停在流水页，需要再返回一次。
   - 建议：统一路由的进入、替换与退出语义，同时维护栈，不要分别直接赋值和事后猜测裁剪。
   - 把握：确定。

8. **[严重度 中] 日期刷新不完整，跨日和跨月后可能继续显示旧统计**
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt:33`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:59`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/LedgerScreen.kt:103、110、114`
    - 问题：`today` 只在初始化和 `ON_RESUME` 更新，应用持续前台跨过午夜后，首页今日消费、剩余天数、重要事项仍使用昨日日期，新建记录却直接使用当前时间。另外，“本月”筛选调用 `YearMonth.now()`，但缓存依赖不包含 `today`；跨月恢复后若账本数据未变，可能继续沿用上月筛选结果。
    - 建议：统一使用可观察的日期来源，在日期变化时更新，并将日期纳入相关缓存依赖。
    - 把握：确定。

9. **[严重度 中] 日历将未来记录计入合计，却隐藏对应日期金额**
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/LedgerCalendar.kt:99、110、127、238`
    - 问题：月合计、日均和最大值包含整月记录，但未来日期格子强制不显示金额。假设今天 14 日，今日支出 100、20 日已记录支出 1,000，格子只看到 100，月合计却是 1,100，“最多的一天”也可能指向空白的未来格子；日均还把未来支出除以已过天数。
    - 建议：统一实际发生与未来已记记录的统计范围；若保留未来金额，应显式展示并区别于截至今日统计。
    - 把握：确定。

10. **[严重度 中] 无法预测预算时，被描述为“预计超出”**
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ImportantItemsScreen.kt:41、90`
    - 问题：有预算但 `projectedMinor == null` 也进入预算风险分支；只要实际未超支，标题一律为“预计超出”。例如存在金额未定的周期账单，无法算出预计支出，页面仍断言预计超支并显示未知金额。资料不足与已预测超支是不同状态。
    - 建议：单独呈现“资料不完整，暂无法判断”，并提供补全入口。
    - 把握：确定。

11. **[严重度 低] 全部流水的日期标题缺少年份，跨年记录无法直接区分**
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/LedgerScreen.kt:54、168`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/LazyPage.kt:59`
    - 问题：默认展示全部历史，但日标题只有月、日、星期，组内又隐藏日期；非分组行同样截掉年份。多年账本中无法从列表判断一笔“1月5日”属于哪一年，只能逐条进入详情。
    - 建议：非当前年日期显示年份，或在年份切换处增加明确分隔。
    - 把握：确定。

12. **[严重度 低] 列表／日历标签没有向辅助功能暴露选中状态**
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/LedgerCalendar.kt:49、59、65、74`
    - 问题：三个主题都仅使用 `clickable(role = Role.Tab)`，选中状态只改变颜色和装饰，没有 `selected` 语义。读屏用户能知道它是标签，却无法获知当前选择了列表还是日历。
    - 建议：使用带选中状态的 `selectable`，并声明标签组语义。
    - 把握：确定。

13. **[严重度 低] 零预算时，日历图例解释与实际计算不一致**
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/LedgerCalendar.kt:97、106、132、305`
    - 问题：预算可以设置为 0。此时日历仍按“存在预算”计算，所有正支出都超支且进度满格；但 `dailyBudget` 被置为 `null`，图例进入无预算分支，杂志主题解释为“满格＝本月最多的一天”。不同金额全部满格，与解释不符。
    - 建议：区分“无预算”和“零预算”，图例与计算使用同一状态。
    - 把握：确定。

**值得注意但不确定**

- `NativeApp.kt:248、311、325` 的账本按钮和底栏位于 Activity 内容中，记账表单则使用全屏模态 `Dialog`（`ui/components/ThemedSheet.kt:74`）。文档要求记账中可点击顶部切账本，但真实触摸可能被弹层遮罩截获；现有 Compose 测试点击底层节点不足以确认真实可达性，需要设备验证。
- `HomeCover.kt:110` 的日元主金额使用固定字号、横向布局和受限行高，`HomeDailyStats` 也有固定多列金额布局。较大金额与较大系统字体组合可能溢出；本次未进行视觉验证，因此不作为确定问题。


# 分区 08-ui-fin-a

已完整读完清单中的全部 19 个文件（共 2,982 行，无遗漏），并核对了相关数据层、公共控件、调用方及云端命令实现；全程只读，未运行构建、测试或模拟器。

1. **[严重度 高] 日历索引跨步骤复用，可使付款方案确认页崩溃**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialArrangementEditor.kt:203`、`:98`、`:239`；`android/app/src/main/java/com/yohaku/expensetracker/ui/components/Components.kt:258`
   - 问题：逐期日历把展开位置编码为 `(期次索引+1)*10+字段索引`，最终办理时间却直接复用这个值。填写有效方案后，展开第一期日历、不选日期也不收起，直接点击“比较当前与新方案”再进入“记录结果”，`owner` 仍为 10，而最终时间控件只有一个字段，访问 `fields[10]` 越界。日历是内嵌控件，展开期间底部按钮仍可点击。
   - 建议：表单日历与办理时间分别保存展开状态；切换步骤、切换手动模式导致字段集合改变时，清除原展开位置。
   - 把握：确定。

2. **[严重度 中] 账户总览只检查最后余额，遗漏扣款过程中已经出现的资金缺口**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialAccountOverview.kt:68`、`:69`、`:73`、`:76`
   - 问题：预测取该账户时间线的最后一步，并仅根据最后余额决定是否提示不足。例如当前资金 1,000，明天本卡扣款 600、另有周期扣款 600，同日还有一笔已记录的未来转入 500：数据层按照“先扣后入”计算，会先出现 −200，再恢复至 300。但这里显示“扣款后 300”，当前缺口按本卡单独计算为 0，也不显示未来不足警告。与时间线实际存在的风险不一致。
   - 建议：复用 `timeline.accountRisks()` 的首次不足和最大缺口；将期末余额与扣款时资金是否足够分开表达。
   - 把握：确定。

3. **[严重度 中] 未配置单账户额度时，已经建立的共享额度组会从界面消失**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/CreditLimitScreen.kt:75`、`:88`；根源涉及 `android/app/src/main/java/com/yohaku/expensetracker/data/repository/CreditLimits.kt:145`
   - 问题：界面通过额度估算结果的 `groupId` 查找共享组，但 `accountCreditLimit()` 在账户没有配置时提前返回，丢失了真实成员关系。两个尚未配置额度的债务账户可以合法建立共享组；保存后页面仍显示“建立共享额度组”，没有编辑组及记录组平台值的入口。再次建立则因账户已经属于其他组而失败。
   - 建议：从 `l.members` 获取真实组关系，不让“能否计算额度”决定“是否展示已有组”；数据层的未知估算结果也应保留成员关系。
   - 把握：确定。

4. **[严重度 中] 从草稿恢复付款调整后，核对原计划和账单的流程失效**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialDrafts.kt:216`、`:217`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialArrangementEditor.kt:154`、`:155`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialAccountScreen.kt:229`
   - 问题：正常打开编辑器会传入原循环计划、费用账单的跳转及返回回调，草稿恢复分支却没有传。恢复“付款方案变更”草稿后，点击“查看／重算原循环还款计划，保留本次输入”实际上只关闭编辑器；恢复“共同月付款变更”草稿后，相应核对按钮直接消失。需要先重算原计划或核对费用才能继续的操作因此中断。
   - 建议：草稿恢复路径与正常入口共用导航回调，并补全相应返回状态；不要把缺少回调处理成关闭页面。
   - 把握：确定。

5. **[严重度 中] 实际收入失效后的修复草稿，被错误判定为不可继续**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ExpectedIncomeEditor.kt:117`、`:133`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/ExpectedIncomeRepository.kt:51`
   - 问题：预计收入确认到账后，删除关联收入，预计项仍为 `ARRIVED`，但 `expectedIncomeNeedsReview()` 为真。页面和仓库都允许重新确认到账。用户开始修复、输入后退出，再从草稿列表“继续编辑”，恢复分支却只接受 `PENDING`，错误提示“已取消或到账”，只能关闭或放弃草稿。正常编辑与草稿恢复采用了不同的可操作条件。
   - 建议：统一预计项的可编辑判定，将需要重新核对关联的状态纳入恢复条件。
   - 把握：确定。

**值得注意但不确定**

- `android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialComparisonTable.kt:34`、`:38`、`:71`：`ArrangementRows()` 单独排序付款行，却按原索引读取 `remainingByRow`、`periodNumbers`。无序输入会让期次、期后本金与付款行错配；目前找到的携带这些附属列表的调用方已预先排序，因此尚未证实当前存在实际触发入口。


# 分区 09-ui-fin-b

已完整阅读本分区清单中的全部 22 个文件（共 2,819 行），并核对相关数据层、领域计算、调用方及云端命令；没有遗漏，也没有修改文件或运行构建、测试、设备命令。

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

3. **[严重度 中] 停用账户仍可进入流水补记，点击后触发未捕获异常**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/StatementDifferenceSheets.kt:137`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/StatementEntry.kt:7`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/StatementEntryDrafts.kt:15`  
   问题：账户停用后，其核对资料仍保留，补记按钮也仍可用；但生成草稿使用 `first { it.id == seed.accountId && it.active }`。用户为停用账户的旧流水补记支出时会抛出 `NoSuchElementException`。这条调用链直接从点击回调执行，没有经过 `vm.execute` 的错误处理。  
   建议：明确停用账户的补记政策；入口提示先启用，或在数据层允许符合约定的历史补记，避免可点击入口直接触发异常。  
   把握：确定。

4. **[严重度 中] 贷款额度占用变动无法填写合法的负数**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialReconciliationEditor.kt:62`、`:96`、`:144`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialArrangementReconciliation.kt:110`  
   问题：领域允许 `loanLimitDeltaMinor` 在负本金至正本金之间，负数用于减少额度占用；表单却使用只接受非负数的 `Money.parse`。输入 `-1000` 会报“金额不能为负”。云端命令可以保存负值，但手机打开该安排后，即使不修改此值，也无法通过重核预览。同类问题还出现在分区外的 `FinancialArrangementEditor.kt:93`。  
   建议：为金额差值使用支持正负号的解析方法，继续由领域层校验允许范围。  
   把握：确定。

5. **[严重度 中] 重核预览的待付合计忽略本次清偿迁移**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialReconciliationEditor.kt:232`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialArrangementReconciliation.kt:131`  
   问题：数据层先替换清偿分配，再计算账单调整；UI 预览却只叠加调整，仍使用旧清偿分配。例如把 A 账单已清偿的 100 元移给 B，并填写 A 待付 100、B 待付 0，预览合计可能仍显示 A 为 0、B 为 100，与同页本金栏和实际保存结果相反。  
   建议：使用包含 `clearingChanges` 和 `adjustments` 的同一份保存后快照计算预览，避免 UI 重建不完整结果。  
   把握：确定。

6. **[严重度 中] 部分还款后，建议金额会再次分配已经付过的手续费**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialRepaymentState.kt:20`、`:23`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialObligations.kt:17`；关联测试：`android/app/src/test/java/com/yohaku/expensetracker/ui/features/FinancialRepaymentStateTest.kt:22`、`:34`
   问题：代码先扣除已付费用总额，再从剩余费用中重新取整笔 `basis.additionalFeeMinor`，没有扣除已付的手续费部分。例如原利息 100、办理费 30，已单独支付办理费 30 后，剩余应为利息 100、手续费 0；表单却建议利息 70、手续费 30。总额正确但清偿组成错误，且能够通过保存校验；待确认付款同样存在这个问题。现有测试分别验证追加手续费和部分还款，但部分还款用例的费用始终为零，没有覆盖这一组合。
   建议：按实际及待确认分配分别累计、扣除利息和手续费；无法可靠拆分时保持未知。补一个“已付追加手续费后再次还款”的用例。
   把握：确定。

7. **[严重度 中] 未知付款端费用被当成零，用于推导“实付利息”**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialRepaymentState.kt:48`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialRepaymentEditor.kt:124`、`:134`  
   问题：平台实收未填、付款端费用也未填时，同币种分支直接按付款端费用为零推导实收。例如仅填写扣款 1,100、本金 1,000、平台费 0，就自动得到利息 100；如果扣款实际含 20 元银行手续费，这个利息就是错的。该推导也违背页面“未填写仍为未知”及设计文档第 5.3 节的约定。  
   建议：只有已填写平台实收，或付款端费用明确已知时，才推导利息；空值不参与零费用假设。  
   把握：确定。

8. **[严重度 中] 修改扣款金额会覆盖用户手动填写的利息**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialRepaymentState.kt:38`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialRepaymentEditor.kt:137`  
   问题：`withDerivedInterest` 尊重 `interestManual`，但之前执行的 `withPaid` 不尊重。用户手动修正利息后，只要将扣款金额改回账单建议金额，利息就被建议值覆盖；随后 `interestManual=true` 又阻止重新推导。页面承诺的“手动填写后不再覆盖”实际不成立。  
   建议：所有自动填充入口统一遵守手动编辑标记；恢复建议值应是明确操作。  
   把握：确定。

9. **[严重度 中] 手续费未知的账单，在详情和进度中又显示成确定合计**

   位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialPlanPages.kt:72`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialPlanProgressView.kt:24`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialPredictionReview.kt:43`  
   问题：延期付款手续费未知时，数据保存 `additionalFeeUnknown=true`，数值 total 暂时只有本金。列表及 `FinancialBillFacts` 会显示“合计未知”，上述位置却直接显示 `billOutstanding.totalMinor`。例如本金 10,000、手续费未知，同一页面会同时出现“合计未知”和“实际清偿后待付 ¥10,000”。  
   建议：统一传播“合计是否已知”的状态；这些数值只能标为已知部分或下限，不能作为完整待付合计。  
   把握：确定。

10. **[严重度 中] 账户列表的“当前欠款”提前计入未来流水**

    位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AccountsScreen.kt:45`、`:93`、`:135`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt:50`  
    问题：资金账户按截至今日余额展示，债务账户却调用不限制日期的 `s.balance(a)`。例如录入明天清偿的 10,000 元还款，列表今天就把欠款减掉，而付款账户资金尚未减少。进入金融账户后，“当前未偿本金”又使用 `cashBalanceAt`，两页显示不同。  
    建议：“当前欠款”统一使用截至今日的口径；若保留全日期账面余额，应明确命名并另列未来变动。  
    把握：确定。

11. **[严重度 中] 恢复重核草稿后，新增加的清偿记录无法参与分配编辑**

    位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialReconciliationEditor.kt:70`、`:169`、`:182`  
    问题：分配编辑行只从草稿 `moves` 读取。开始重核并保存草稿后，退出记录一笔新还款，再回来时，新清偿虽已进入 `clearings`，却不会加入 `moves`。重新读取按钮只检查“旧行已不存在”，不检查“出现新行”；旧草稿为空时，新清偿甚至完全没有编辑入口。  
    建议：比较两边的分配 ID 集合，新增和删除都提供明确刷新；保留仍有效行的用户输入。  
    把握：确定。

12. **[严重度 中] 流水列表每展示一行，都重新扫描整个账本生成资金端**

    位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/StatementsScreen.kt:71`、`:87`、`:143`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FileWorkflowRepository.kt:63`  
    问题：每条流水调用一次 `s.reconciliationPostings()`；该方法遍历全部 posting，并为每个 posting 线性查找事件。设流水行数为 R、posting 数为 P、事件数为 E，页面产生约 `R×P×E` 的重复扫描，还会反复创建金额对象。普通多年账本导入数百行流水就会形成较重的主线程负担。  
    建议：按快照一次生成 posting 索引，行内直接查找；底层使用已有事件索引，较长列表采用惰性渲染。  
    把握：确定（复杂度可由代码确认，未测量运行耗时）。

13. **[严重度 低] “下次付款”仍指向已经支付、只待平台确认的账单**

    位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialPlanList.kt:20`、`:26`  
    问题：下一期只按 `billOutstanding > 0` 选择，没有扣除已付待确认分配。例如 11 月账单已全额付款待确认、12 月仍需付款，计划卡片仍将 11 月和完整账单金额显示为“下次付款”。数据层账户摘要已使用 `cashRequiredMinor` 排除这种情况，两处口径不同。  
    建议：区分“尚未清偿确认”和“下一次仍需付款”；下一次付款复用账户摘要的选择逻辑。  
    把握：确定。

14. **[严重度 低] 已确认差异仍计入“待确认”数量**

    位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/StatementsScreen.kt:65`、`:80`  
    问题：摘要的“待确认”数量直接加上全部 `p.decisions.size`，其中包含有效的人工确认。用户确认所有差异后，行状态显示“已确认差异”，摘要却仍显示相同数量的“待确认”。  
    建议：单列已确认差异，或将该统计标签改为“差异”；待确认只统计尚未决定及决定已失效的行。  
    把握：确定。

15. **[严重度 低] 日文空状态存在重复词**

    位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialPlanProgressView.kt:69`  
    问题：循环借入没有资金池时，日文显示 `口座の循環循環返済プランは未作成`，重复了“循環”。  
    建议：改为 `口座の循環返済プランは未作成`，与其他页面统一。  
    把握：确定。

**值得注意但不确定**

- `android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialPoolEditor.kt:132`：生成候选后修改预测期数、首期付款日或计费起止日，不会清空或更新已经生成的 rows，预览仍提交旧 rows。行为已确认，但尚不能确定产品是否有意要求用户再次点击生成；目前没有明确的失效提示。
- `android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialPoolPaymentEditor.kt:160`：历史比较通过当前 `s.accounts.first` 查付款账户，没有使用历史账户快照。若历史引用账户在解除现有引用后可以删除，打开旧历史可能崩溃；尚未完整证明账户删除约束是否始终阻止该场景。


# 分区 10-ui-entry

已完整阅读清单中的全部 36 个 Kotlin 文件（共 2,945 行，无遗漏），并只读核查相关调用方、仓储、云端适配器及文档；未修改文件，未运行构建或测试。

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

4. **[严重度 中] 修改转账汇率或手续费后，预计金额继续沿用旧结果**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/TransferForm.kt:64`、`:78`、`:81`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt:365`
   - 问题：只有修改两端金额时才调用 `changeTransferAmount`。修改汇率、手续费或扣收方式只更新草稿，不重新计算，也不清除已有预计值。例如 100 USD 按 150 换算得到预计到账 15000 JPY，随后将汇率改为 160，到账仍是 15000，且可以直接保存。与界面“手填汇率只联动尚未确认的一端”的说明不符。
   - 建议：统一处理估算依赖变化，对由估算产生且尚未确认的一端重新计算；输入不完整时标记为待估算，避免旧值继续充当有效结果。
   - 把握：确定。

5. **[严重度 中] 替换小票图片后，本地商品明细编辑无法修复来源关联**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ReceiptProducts.kt:103`、`:120`、`:153`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FileWorkflowRepository.kt:170`
   - 问题：已有商品明细引用图片 A，将小票图片替换为 B 后，界面仍以旧文档打开编辑器，且没有修改 `image_ids` 的入口。保存固定走 `editReceiptItems`，该方法既禁止改变来源关联，又要求旧来源图片仍在当前小票中，因此任何修改都会报“来源图片已变化”。新增图片后，也无法通过本地编辑器明确将新图纳入已整理范围。
   - 建议：增加核对当前图片并更新来源关联的明确流程，保存时保留修订检查。云端 `receipt.items.save` 已支持显式传入当前图片并调用 `saveDirectReceiptItems`，本地入口应补齐对应能力。
   - 把握：确定。

6. **[严重度 中] 地点选择器把地图中心当作已选位置，新增或改名地点都会写入未经选择的坐标**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/PlacePicker.kt:36`、`:70`、`:73`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/MerchantsScreen.kt:249`、`:257`、`:259`
   - 问题：地图初始化立即调用 `centerChanged`，把视口中心写入表单：
     - 新增地点：没有初始地点时，地图使用固定坐标 `36.2, 138.25`，“保存地点”按钮随即可用。用户拒绝定位权限、定位超时，或者定位尚未完成时，填写名称并保存就可能把该固定坐标存为商户地点，后续附近商户匹配也随之错误。
     - 编辑无坐标地点：数据层及云端 `merchantLocation.save` 支持经纬度同时为空，列表也明确支持“仅地点名称”。但本机编辑强制打开地图，保存又强制要求非空坐标；编辑一个云端创建的无坐标地点、仅修改名称，也会把它变成默认日本中心点或用户当前位置的地理围栏。
   - 建议：区分地图视口中心与用户选定地点，保留“未设置坐标”状态；首次有效定位或用户明确选点后，才写入可保存的坐标，与云端的可空坐标语义一致。
   - 把握：确定。

7. **[严重度 中] 修订历史漏显关键账务变更，有时只剩版本标题**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/RevisionHistory.kt:57`、`:80`、`:100`、`:127`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerChangeReview.kt:15`
   - 问题：
     - 已保存的活动归属、预留使用和互请分配没有参与展示比较；只调整这些字段时，修改记录可以只显示版本和时间。
     - 手续费摘要不包含账户及确认状态；把另扣费用从“未扣收”改为“已扣收”，虽然改变余额和消费，历史却可能显示完全相同的前后文本。
     - 本位币金额变更还受“原币不等于本位币”限制；原币为 JPY、本位币为 JPY、付款账户为 USD 时，修改本位币统计金额也会被漏掉。
   - 建议：根据实际变化比较并展示 `LedgerDetails` 中的业务字段；本位币金额是否展示应由金额变化决定。
   - 把握：确定。

8. **[严重度 中] 只有外币债务时，本位币参考估值入口被隐藏**

   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ReferenceAssetValuation.kt:16`、`:43`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AccountsScreen.kt:38`、`:70`
   - 问题：估值组件支持资金与债务，但调用方的 `hasForeign` 只检查资金账户。例如 JPY 账本、JPY 银行账户加 USD 信用卡，存在外币欠款，却完全不显示参考估值和刷新入口。
   - 建议：估值入口根据全部相关账户是否存在外币决定；资金汇总标题是否分币种展示继续单独判断。根因在分区外调用方。
   - 把握：确定。

9. **[严重度 低] 历史补录提示使用账本开始日，实际校验使用账户期初日**

    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/HistoricalOpeningToggle.kt:25`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:621`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/LedgerRepository.kt:175`
    - 问题：账本开始日与账户期初日可以不同，但历史补录开关只展示前者。例如账本始于一月、账户期初为十一月，十月记录可以属于账户期初已包含的历史，提示却只给出一月日期，无法说明实际适用边界。
    - 建议：传入所选账户的期初日期，文案明确为“账户期初日”。
    - 把握：确定。

10. **[严重度 低] 媒体选择器标题没有按界面语言切换**

    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/MediaPanels.kt:59`、`:60`、`:195`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/MediaPickerContract.kt:10`
    - 问题：附件及小票选择器直接使用固定的“中文 / 日文”标题传给系统 chooser。在日文界面仍显示中文开头，在中文界面也重复显示日文，与其他界面的语言选择不一致。
    - 建议：构造选择器标题时通过 `word(...)` 选择当前语言。
    - 把握：确定。

11. **[严重度 低] 手账主题的收入分类页仍显示支出导语**

    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/CategoryCover.kt:37`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:515`
    - 问题：分类封面不接收记账类型。切换到收入后，手账主题仍显示“今天的小花费”及日文“今日の、小さな支出”，与正在记录工资等收入的操作不符。
    - 建议：封面根据收支类型选择文案，或使用适用于两者的描述。
    - 把握：确定。

**值得注意但不确定**

- `ReferenceAssetValuation.kt:35` 对资金使用截至 `asOf` 的余额，对债务却使用包含所有已确认日期的 `balance()`；存在未来日期债务分录时，两侧时间口径不同。需要结合债务“当前本金”的产品定义确认是否有意如此。
- `ReceiptProducts.kt:37` 将单价、金额列固定为较窄宽度，手账主题数字字号更大；较长金额及放大字体可能出现明显换行或拥挤。本次按约束未运行视觉验证，不能确认实际影响程度。


# 分区 11-ui-activity

已完整逐行读完清单中的 12 个文件：ActivityCategoryBudgetEditor.kt、ActivityDeletionSheet.kt、ActivityScreens.kt、AnalysisPlanningViews.kt、AnalysisScreen.kt、BasicAnalysis.kt、BudgetScreens.kt、CsvImportScreen.kt、CsvImportViewModel.kt、MerchantHistory.kt、MerchantsScreen.kt、RecurrenceScreens.kt，并核对了相关数据层、组件、云端适配器和文档；未修改文件，未运行构建、测试或模拟器。

1. **[严重度 中] 表单重建后仅恢复部分字段，可能静默保存丢失内容的版本**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ActivityScreens.kt:191`、`:290`、`:292`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/RecurrenceScreens.kt:151`
   - 问题：活动分类预算、互请参与人及分配权重、周期规则的商户/备注/付款方式使用普通 `remember`，而相邻字段使用 `rememberSaveable`。例如新增活动并填好分类预算后旋转屏幕，名称、总预算仍在，分类预算却恢复为空；继续保存会创建缺少分类预算的活动。编辑周期规则时也会丢失尚未保存的备注、商户等修改。
   - 建议：将同一表单作为整体保存状态，使用合适的 Saver 或 ViewModel，保证恢复后的字段属于同一版本。
   - 把握：确定。

2. **[严重度 中] 未打开应用的整月周期账单不会进入逾期待办**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/RecurrenceScreens.kt:26`、`:33`
   - 根源涉及分区外：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/RecurrenceRepository.kt:42`、`:165`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/NativeApp.kt:235`
   - 问题：页面只生成选中月份的实例，往月逾期列表只收集已经落库的实例。假设九月创建每月房租，整个十月没有打开应用，十一月重新打开：十月实例从未生成，不会出现在“先处理”中；手动翻到十月生成后，它才会出现。后台预测也仅从当前月份向后生成，不补这个缺口。
   - 建议：按规则有效范围补齐遗漏月份，或通过统一投影生成应存在的历史待办，再与已处理状态合并；不要让是否访问过某个月决定待办是否存在。
   - 把握：确定。

3. **[严重度 中] 已归档子分类的消费被错误标成“未细分”**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/BudgetScreens.kt:89`、`:91`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AnalysisPlanningViews.kt:36`、`:38`
   - 关联位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/components/BudgetLayout.kt:182`
   - 问题：调用方只传入启用的子分类，但父分类消费仍包含归档子分类。组件又用“父消费－传入子分类消费之和”计算未细分消费。例如餐饮下早餐消费 100、午餐消费 200，归档早餐后，会显示餐饮 300、午餐 200、未细分 100；实际没有任何直接记入餐饮的消费。
   - 建议：独立计算直接记入一级的消费，不通过可见子分类反推；归档项是否展示与统计归属分开处理。
   - 把握：确定。

4. **[严重度 中] CSV 首次解析失败后，无法使用界面承诺的手动编码功能**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/CsvImportViewModel.kt:42`、`:47`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/CsvImportScreen.kt:40`、`:46`
   - 问题：打开文件先清空 `document`，随后自动解码、解析；编码和分隔符控件却全部要求 `document != null`。例如无 BOM 的 UTF-16LE、带引号 CSV，自动解码可能先按 UTF-8 接受其字节，再因引号后的 NUL 解析失败。此时只剩错误提示，用户无法选择界面原本支持的 UTF-16LE；重选同一文件仍重复失败。
   - 建议：文件已读取后就允许调整编码、分隔符和表头选项，不以首次解析成功作为显示这些控件的前提。
   - 把握：确定。

5. **[严重度 中] CSV 逐行修正改变收支类型后，分类映射仍依据原始金额**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/CsvImportScreen.kt:111`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/CsvImportViewModel.kt:60`、`:78`
   - 问题：正式预览调用解析器时传入 `edits`，分类映射和规范化时却不传。按金额正负判断收支、分类为空的文件，原始金额全部为负；将其中一行从 `-100` 修正为 `100` 后，该行变为收入，需要收入默认分类。但返回映射步骤仍只展示原始支出组，无法设置所需的收入默认分类。
   - 建议：映射分组、规范化和预览统一使用修正后的行数据；类型改变时及时展示新的分类映射需求。
   - 把握：确定。

6. **[严重度 中] CSV 问题行减少后，分页可能停在不可返回的空页**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/CsvImportScreen.kt:147`、`:149`、`:161`、`:174`
   - 问题：页码不会随过滤后的问题行数量缩减而校正。例如有 21 个问题行，进入第二页修好最后一行后，问题行剩 20 个，但 `page` 仍为 1，列表从第 21 个开始取而显示为空；由于总数不超过 20，分页按钮也消失。用户仍有 20 行待处理，却无法直接返回第一页。
   - 建议：问题列表变化后把页码限制在有效范围，或在修正导致当前页消失时退回上一页。
   - 把握：确定。

7. **[严重度 低] 选择分类后，预算中的预计换算提示被无条件关闭**
   - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AnalysisPlanningViews.kt:29`
   - 问题：分类未选中时统计未确认入账的支出，选中任何分类后直接把 `provisional` 设置为零。分类内即使存在尚未确认的外币消费，其已花、余额仍使用预计本位币金额，却不再显示预计换算提示。
   - 建议：按当前分类及其子分类统计未确认支出，而不是关闭提示。
   - 把握：确定。

8. **[严重度 低] 关闭活动功能后，仍提供必然失败的互请编辑入口**
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ActivityScreens.kt:152`
    - 关联位置：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/TreatRepository.kt:72`
    - 问题：活动功能关闭后，“修正份额”仍可进入并填写；直到点击预览，数据层才拒绝并要求开启活动功能。其他活动编辑入口已经按功能开关限制，此处行为不一致。
    - 建议：入口统一检查活动功能状态，或明确允许历史互请修正并同步数据层规则。
    - 把握：确定。

9. **[严重度 低] “别人付款”表单显示了“本人付款”的说明**
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ActivityScreens.kt:278`、`:307`
    - 问题：`OtherTreatEditor` 复用 `TreatSplitFields`，导致“别人付款”表单仍显示“本人付款的整单只记一次”。这与该流程“不产生本人消费或账户扣款”的实际语义不对应，中日文均存在。
    - 建议：让共享份额组件接收付款方语境，分别提供说明，或使用适用于双方的中性文案。
    - 把握：确定。

10. **[严重度 低] 保留了一整套没有调用入口的旧分析实现**
    - 位置：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/BasicAnalysis.kt:29`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AnalysisScreen.kt:123`
    - 问题：全源码及测试引用搜索表明，`CategoryBreakdown`、`AnalysisTotal`、`CategorySpendRow`、`AnalysisRankRow` 均没有调用方；`AnalysisPeriod`、`SpendMeter` 等只被这些未使用实现引用。实际分析页已使用另一套组件，这套旧实现形成独立的死代码链，维护或修复它不会影响实际页面。
    - 建议：删除已替代的实现，保留实际使用的分析组件路径。
    - 把握：确定。

### 值得注意但不确定

- `RecurrenceScreens.kt:33` 先按选中月份过滤，再形成“7天内”分组。因此十一月末不会展示十二月初、实际在未来七天内的账单。月份导航可能意味着这是有意限制，但文案没有写“本月内”，现有规格也未明确跨月边界。
- `ActivityScreens.kt:286` 对未结束活动也将新互请日期强制夹在活动起止日期内。如果活动实际延长但尚未修改结束日期，新记录默认会落到过去的结束日。数据层对未结束活动允许期间外日期，默认日期策略是否应该如此需要确认。


# 分区 12-tests-1

已完整阅读清单内全部 23 个测试文件，共 5,094 行，并核对相关生产代码、迁移夹具和文档；无遗漏，未修改文件或运行任何测试、构建、模拟器命令。

1. **[严重度 高] 内存/临时数据库测试会清理真实应用偏好，并可能写入假汇率**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/` 下的 `ActivityFlowTest.kt:37`、`BudgetRecurrenceFlowTest.kt:40`、`AnalysisFlowTest.kt:38`、`CardPaymentFlowTest.kt:36`、`CommandFlowTest.kt:72`、`EntryFlowTest.kt:33–34、170–181`、`DraftEditingFlowTest.kt:37–54`、`FinancialProgressFlowTest.kt:36`、`FinancialReconciliationChainFlowTest.kt:36`、`FinancialReconciliationFlowTest.kt:36`、`FinancialRepaymentFlowTest.kt:36`、`FinancialRetroactiveFlowTest.kt:36`、`FinancialRuleModesFlowTest.kt:36`、`FinancialScaleFlowTest.kt:36`、`HomePlanningFlowTest.kt:33`、`LedgerCalendarTest.kt:34`、`LargeLedgerTest.kt:30`、`MerchantFlowTest.kt:39`、`ProcessRecoveryTest.kt:37`、`TestReferenceRates.kt:17`；关联生产代码：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/AppViewModel.kt:144`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/AppPreferences.kt:72`、`ReferenceRates.kt:59–66`
   - 问题：这些测试隔离了 Room，却通过 `AppPreferences(context)` 连接应用实际的 `app` DataStore（`app.preferences_pb`）：
     - ViewModel 初始化按测试库中的账本执行 `pruneBookPreferences()`。在已有应用数据的设备上运行，真实账本都不在测试库中，其提醒设置、前台定位授权偏好和当前账本选择会被删除，测试结束也没有恢复。
     - 假汇率服务 `testReferenceRates()` 同样使用该持久存储。自动获取开启且没有当天查询缓存时，打开换算面板就会把 `rate=1` 写入应用汇率缓存，生产代码将其保存为普通汇率快照，随后可能按“当天已查询”直接复用。
     - 另有测试直接改变触感、照片保存设置，结束时未完整恢复（`CommandFlowTest` 只恢复触感），还会留下选中账本等偏好；首次设置测试也没有初始化已有的设置草稿，造成测试间相互影响。
   - 建议：像 `HapticsFlowTest` 一样统一注入独立临时 DataStore，结束时关闭、删除；云端仓库、ViewModel 和测试汇率服务共享该测试实例；进程恢复测试使用专用持久偏好文件。
   - 把握：确定。

2. **[严重度 中] 分析口径测试没有执行其声称验证的计算**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/AnalysisProjectionTest.kt:36`
   - 问题：`parentChildrenRefundsFeesFiltersAndComparisonUseTheSameEconomicFacts` 创建了父子分类、跨月退款、转账手续费和对比期间数据，但随后仅检查关联 ID、手续费存储值及商户关联，完全没有调用 `analysis`、分类汇总或 `compareAnalysis`。例如分析漏计 300 日元手续费、退款归错月份，该测试仍会通过。
   - 建议：使用现有夹具直接断言分析结果及筛选结果，例如十月净额 3,300、截至十月十四日 2,800、上月同期 200，并核对父子分类归属。
   - 把握：确定。

3. **[严重度 中] 云端详情测试可被背景页面文案满足**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/CommandFlowTest.kt:326`、`:523`
   - 问题：点击详情后，仅在整个语义树搜索包含“记录／記録”或“预留／確保額”的任意文本。背景确认页本就包含这些词，且第二处在点击前已经检查过“活动余量”。因此详情按钮失效、弹层未打开，或弹层缺失实际字段时，断言仍可能通过。
   - 建议：先确认详情弹层及标题存在，再限定 `sheet-scroll` 内检查该步骤的具体对象、金额和前后值。
   - 把握：确定。

4. **[严重度 低] “最常用商户优先”只比较了一个不足以证明排序的对象**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/BudgetRecurrenceFlowTest.kt:732`、`:749`
   - 问题：夹具先创建九家未使用商户，再创建有两笔记录的“東京ガス”，最后创建 Blue Bottle；断言仅要求東京ガス位于 Blue Bottle 前。错误列表即使把前九家未使用商户全部排在東京ガス前，这条断言仍通过，与注释声称的“超过此前保存的商户”不符。
   - 建议：检查東京ガス确实为第一项，或至少比较它与先创建、零使用次数的商户，并核对使用次数。
   - 把握：确定。

5. **[严重度 低] 临时 DataStore 的协程作用域没有释放**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/CardPaymentTest.kt:138`
   - 问题：循环中的三个付款方式分别创建独立 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，但没有保留或取消作用域，只删除偏好文件。DataStore 读取后其后台作用域仍存活；断言失败时连临时文件删除也会跳过。
   - 建议：保存作用域，在 `finally` 中取消并等待结束，再删除临时文件。
   - 把握：确定。

### 值得注意但不确定

暂无其他证据充分、需要单独保留的疑点。以上结论来自静态核对，未宣称已通过运行复现。


# 分区 12-tests-2

已完整读完清单中的全部 16 个文件，共 4,816 行，并对照相关生产实现与文档核实；无遗漏，未修改文件，未运行测试、构建或模拟器。

**1. [严重度 中] 默认字号的高度断言会误报大字号布局**

- 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/DraftEditingFlowTest.kt:798–810`
- 问题：导航测试无条件断言底栏高度以及 `page-edition` 的固定高度，没有限制 `visual`、屏宽或字号。生产 `ThemeScreens.kt:35–50` 的页眉文字使用随字号缩放的行高，`Components.kt:51` 明确通过 `sp.toDp()` 计算。例如小物主题文字行高为 11sp，字号 1.3 时约为 14.3dp，必然超出测试的 `11±1dp`。因此项目要求的 320dp/fontScale 1.3 验证会被正常缩放误判为失败。
- 建议：原稿精确几何断言仅在约定的 390dp、fontScale 1 配置执行；其他配置保留可见性、可点击和选中状态验证。
- 把握：确定。

**2. [严重度 中] 汇率重估测试选用了不会改变金额的输入**

- 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/EntryFlowTest.kt:177–186`
- 问题：原币为 USD 100、已有账户金额为 JPY 15,000；测试开启“重新估算”时填写汇率 150，仍断言金额为 15,000。即使生产代码完全跳过重估，这个断言也会通过。之后测试直接手填 15,100 并选择实际金额，进一步掩盖了重估是否发生、是否恢复为预计状态。
- 建议：使用能产生不同结果的汇率，例如 152，先断言账户金额变为 15,200、状态变为预计，再继续实际金额确认流程。
- 把握：确定。

**3. [严重度 低] “同时验证日期上下界”的用例实际无法验证上界**

- 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/DraftEditingFlowTest.kt:729–758`
- 问题：夹具只有当月 5 日和 15 日两笔记录，选择区间为 10—20 日。若筛选实现错误地只检查 `date >= from`，现有全部断言仍会通过；随后把开始日期改为 25 日，也只是再次排除了这两笔较早记录。
- 建议：增加一笔 20 日之后的普通记录，验证合法区间同时排除左右两侧记录。
- 把握：确定。

**4. [严重度 低] 声称收入算式可用，却没有执行收入计算或保存**

- 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/DraftEditingFlowTest.kt:162–189`
- 问题：`amountExpressionSavesSameRecordAndWorksForIncome` 完整验证了支出编辑，但收入部分只切换类型、直接导航到表单、检查 `entry-operators` 存在，然后放弃草稿。没有收入分类、算式输入、计算结果或保存断言；收入算式保存失败时，该测试仍会通过。
- 建议：补一笔有效收入分类和简单算式，验证收入金额、记录类型及账户余额增加。
- 把握：确定。

**5. [严重度 低] 退款流程使用真实当天日期，却固定检查十月没有消费**

- 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/EntryFlowTest.kt:33、238–315、324–358`
- 问题：这些用例没有固定时钟，也没有设置新退款日期；生产 `AppViewModel.startRefund()` 使用当天日期。但断言始终检查 `monthExpense("2026-10")==0`。例如十一月运行时，实际退款发生在十一月，十月本来没有记录，这个断言无法发现退款被错误计入实际退款月的问题。
- 建议：固定测试时钟到预期退款月份，或根据保存后的退款日期检查对应月份，并确保与原消费月份不同。
- 把握：确定。

**值得注意但不确定**

未保留其他有足够具体证据的未证实疑点。以上结论来自静态代码审查，未进行运行验证。


# 分区 12-tests-3

已完整阅读分区清单中的全部 28 个文件（共 4,453 行），并核对相关生产代码及 README；没有修改文件，也没有运行构建、测试或模拟器。

1. **[严重度 中] 商户改名测试使用已过期的预测基线，无法发现改名引入的过期问题**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/FinancialPlanPagesTest.kt:89`、`:91`、`:95`、`:99`
   - 问题：测试取得 `plan` 后确认首期账单，却没有重新计算预测，最后只断言改名前后的 `financialPredictionStale()` 相等。生产代码确认账单时只更新账单及计划版本，不更新 `termsJson` 中保存的预测事实；过期判断又会比较这些事实。因此这里改名前就已经因确认账单而返回 `true`，改名后继续返回 `true` 也能通过，无法验证“商户改名不会使正常预测过期”。
   - 建议：确认账单后先重新计算、读取最新计划，并明确断言改名前后均为 `false`，保留现有账单不变的比较。
   - 把握：确定。

2. **[严重度 中] “所有编辑器丢弃草稿、不改变正式数据”的最终检查漏掉贷款草稿及主要金融数据**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/FinancialDraftEditorsTest.kt:52`、`:53`、`:56`、`:89`—`:92`
   - 问题：`MANUAL`、`PREDICTION`、`BORROW` 使用贷款账户，但最终只检查信用卡账户和 `accountId=null` 的草稿。`FinancialDao.drafts()` 使用 `accountId IS :accountId`，传 `null` 只查询无账户草稿，不包含贷款草稿。因此贷款草稿残留仍可通过。另外，“正式数据未改变”只检查事件为空、信用卡余额和预计收入数量；规则、计划、账单、合同、额度配置被意外修改也不会被这些断言发现。
   - 建议：用已有的 `bookDrafts(bookId)` 检查全账本草稿为空，并比较操作前后的金融图和额度图。
   - 把握：确定。

3. **[严重度 低] 默认模式下，超时诊断可能覆盖真正的 UI 超时异常**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/FinancialPoolFlowTest.kt:44`—`:48`、`:72`—`:76`
   - 问题：超时处理先调用 `shot()`，随后写入 `phase-c-pool/timeout.txt`。目录只在截图函数中创建，而默认 `visual=false` 时截图函数直接返回。在没有历史截图目录的环境中，UI 超时会变成写文件时的 `FileNotFoundException`，执行不到原异常的重新抛出，线程诊断也不会写出。
   - 建议：诊断分支自行创建目录；诊断写入失败应保留原超时作为主要异常。
   - 把握：确定。

4. **[严重度 低] 声称保留“已有核对证据”的迁移测试没有创建或检查核对证据**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/EntryLifecycleStorageTest.kt:148`—`:157`
   - 问题：`version27MigrationPreservesEventsAndExistingReviewEvidence` 只创建账户、分类及一笔普通支出，没有创建流水批次或人工核对决定。其快照比较也不包含 `statement_decisions`。即使迁移错误地清空全部核对决定，该测试仍会通过；而实际 27→28 迁移恰好修改了这张表，增加 `invalidatedAt`。
   - 建议：加入一条真实人工核对决定，迁移后检查其关联、原始证据、备注及新增字段。
   - 把握：确定。

5. **[严重度 低] 预计收入取消后的支出断言检查的是操作前快照**

   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/ExpectedIncomeTest.kt:55`—`:59`
   - 问题：`s` 在创建第三条预计收入之前读取。之后创建跨币种预计收入、取消它，再断言 `s.monthExpense(...) == 0`，仍然计算旧快照。创建或取消操作即使意外新增支出，此断言也不会失败；当前仅实际验证了取消状态。
   - 建议：取消后重新读取快照，检查支出、余额和事件数保持不变。
   - 把握：确定。

**值得注意但不确定**

- `FinancialPoolFlowTest.kt:76`、`FinancialPoolArrangementFlowTest.kt:70`、`FinancialPoolMergeFlowTest.kt:70`、`FinancialPoolPaymentFlowTest.kt:70` 的截图文件名只包含主题、语言和场景，没有包含 `loan`／`tiers` 参数。同一目录连续运行这些分支会覆盖前一分支截图。未确认外部执行流程是否每次立即另存；若没有，会丢失部分视觉验证证据。


# 分区 12-tests-4

已完整阅读清单中全部 28 个文件（共 4,581 行），并核对相关生产代码、测试辅助代码和 README；没有遗漏，未修改文件或运行测试。

1. **[严重度 中] 定位测试硬编码日文，中文筛选运行必然失败**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/LocationEntryFlowTest.kt:127`、`:157`。
   - 问题：主题语言循环结束后，测试继续查找 `field-お店 / 買ったもの`，并等待包含“タイムアウト”的状态。按照支持的参数使用 `language=zh` 时，账本仍为中文，实际控件使用“商户 / 买了什么”和“定位超时”，因此测试在正常界面上失败。完整六组矩阵最后恰好是日文，掩盖了这一依赖。
   - 建议：将后续步骤按当前语言选择文案，或明确切换到日文并等待更新；输入框优先使用已有的稳定语义标签。
   - 把握：确定。

2. **[严重度 低] 贷款表格关键文字消失时，布局断言仍会通过**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/FinancialProductionOverviewTest.kt:245`。
   - 问题：代码遍历 `matches.fetchSemanticsNodes().indices` 检查行数，却没有先断言匹配数量。正常金额场景中，如果月份 `2026.10` 或费用 `CN¥500.00` 被漏渲染、显示错误，匹配集合为空，循环不执行；此前断言只检查本金和期后本金，不能拦住这类回归。
   - 建议：先断言每项文字应有的节点数量，再检查文本布局。
   - 把握：确定。

3. **[严重度 低] “未拥有文件”测试实际只验证随机不存在的 ID**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/MediaStorageTest.kt:141`。
   - 问题：测试创建另一账本后，没有向其中导入文件，而是用 `repo.id()` 生成不存在的文件 ID。这个输入只能验证拒绝无效引用，不能验证拒绝其他账本或其他草稿拥有的真实文件。随后断言另一账本文件为空，也只是再次确认从未写入的夹具为空。即使生产代码错误地允许引用其他账本的现存文件，该用例仍可通过。
   - 建议：在另一账本创建真实文件，将其 ID 传入当前账本预览，验证拒绝且原文件关联不变。
   - 把握：确定。

4. **[严重度 低] EXIF 方向测试的纯色夹具无法验证旋转方向**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/MediaStorageTest.kt:65`、`:77`。
   - 问题：两个测试分别使用无图案图片和纯蓝图片，设置旋转 90°，最终只断言宽高及元数据。若实现错误地旋转 270°，或交换宽高并拉伸原图，仍能满足这些断言；纯色内容也无法揭示错误。这不能完整证明测试名称声称的“方向已应用／烘焙”。
   - 建议：使用带不对称色块的小图，在现有尺寸断言之外检查少量旋转后的像素位置；JPEG 使用合理色差容限即可。
   - 把握：确定。

**值得注意但不确定**

- `LocationEntryFlowTest.kt:64` 会授予真实定位权限，结束时未恢复；随后运行要求未授权状态的系统测试可能受影响。不过 README 已明确要求系统测试逐方法准备外部状态，因此未将它认定为现有执行流程中的确定失败。


# 分区 12-tests-5

已完整阅读分区 12-tests-5 清单中的全部 30 个文件，共 5,309 行，并核对了相关生产代码和验证文档；未修改文件，未运行构建、测试或设备命令。

1. **[严重度 中] 首页核对交接测试绕过真实消费者，掩盖连接云端后预填信息丢失**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/StatementDueFlowTest.kt:32`、`:74`；根因涉及分区外 `android/app/src/main/java/com/yohaku/expensetracker/ui/features/CloudScreens.kt:450`、`:456`，以及 `NativeApp.kt:286`。
   - 问题：测试未提供 `cloud`，正式交接组件直接返回；账户和期间断言又包在 `?.let` 中，即使 seed 根本没有生成也能通过。正式应用中，尚未连接时仍会清空 seed，只把账户和期间保存在当前页面局部状态。用户从首页点“去核对”→连接云端→返回，原页面已离开组合，账户恢复默认值、期间备注丢失。
   - 建议：连接完成且交接表单能够接收时再消费 seed，或把待交接状态保存在 ViewModel；测试应提供隔离的云端组件，并断言最终表单中的账户和期间，不能允许空 seed 跳过检查。
   - 把握：确定。

2. **[严重度 中] 商户真实键盘与视觉参数同时开启时，测试主动制造超时**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/MerchantFlowTest.kt:425`、`:432`。
   - 问题：使用 `phaseBKeyboard=true`、`visual=true` 时，测试先主动隐藏 IME，并确认键盘已经消失；随后没有重新聚焦输入框或显示键盘，却等待 `imeVisible()` 变为真。因此正常界面也会超时，无法完成键盘上方保存按钮的验证。
   - 建议：截图后重新聚焦输入框并等待键盘显示，或调整截图顺序，保留键盘验证需要的状态。
   - 把握：确定。

3. **[严重度 中] 删除核对资料后的“账本未变”断言使用删除前快照**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/StatementReviewStorageTest.kt:99`、`:103`、`:104`。
   - 问题：`s` 在删除前取得。删除后虽然重新查询了 decisions，但事件数量和余额仍从旧 `s` 读取。即使删除操作错误地删除流水、修改 posting 或余额，这两条断言依然检查删除前的正常结果，无法发现问题。
   - 建议：删除后重新读取快照，再核对事件、posting 和余额。
   - 把握：确定。

4. **[严重度 中] 对账失效提示的等待条件在修改前就已成立**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/StatementFileWorkflowTest.kt:155`、`:156`、`:157`。
   - 问题：修改原始流水后，只等待状态节点“存在”；该节点在修改前已经存在。Room 写入完成不代表新投影已经进入 Compose，因此测试可能立即读取旧的“差异确认済”文本，错误判定失效提示没有更新。
   - 建议：等待该节点出现预期的失效文本，或等待相应投影更新后再断言。
   - 把握：较可能；等待条件无效可以确定，是否出现失败取决于调度时序。

5. **[严重度 中] 无效主题或语言参数会让大量测试空跑后通过**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/TestLooks.kt:12`、`:13`。
   - 问题：例如传入 `language=jp` 或拼错主题名，筛选结果为空，函数直接返回空列表。大量测试把所有操作和断言放在 `for (... in testLooks(...))` 内，结果是没有执行任何场景，却被 JUnit 记为通过。
   - 建议：显式参数无效或筛选结果为空时立即报错，并显示允许值。
   - 把握：确定。

6. **[严重度 低] 进程恢复结果固定声称验证了六组外观**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/PlanningProcessRecoveryTest.kt:62`、`:118`。
   - 问题：种子阶段使用 `testLooks`，默认只创建一组，显式筛选也可能只创建部分组合；最终结果却始终写入 `"verifiedCases":6`。按默认参数完成三个阶段后，结果文件会把实际一组验证报告成六组。
   - 建议：按实际成功验证的 cases 数量生成结果，同时记录具体主题和语言。
   - 把握：确定。

7. **[严重度 低] 首页冻结参考测试没有固定日期，截图场景会随运行日期变化**
   - 位置：`android/app/src/androidTest/java/com/yohaku/expensetracker/SetupPresentationTest.kt:37`、`:41`；关联 `android/app/src/main/java/com/yohaku/expensetracker/ui/features/ThemeScreens.kt:72`、`:77`。
   - 问题：夹具消费日期固定为 `2026-10-14`，调用 `HomeScreen` 时却使用默认的系统当天日期。到 11 月运行时，这笔消费不再计入本月支出，日均额度、剩余天数及今日消费也发生变化。测试仍可能通过几何断言，但产生的已不是冻结参考所对应的数据场景。
   - 建议：传入固定的 `today=LocalDate.of(2026,10,14)`，使夹具、参考数据和截图一致。
   - 把握：确定。

### 值得注意但不确定

- `android/app/src/androidTest/java/com/yohaku/expensetracker/SystemHandoffTest.kt:127`：恢复阶段只检查已持久化计划存在，没有直接验证外部系统事件重新注册了闹钟；启动 instrumentation 时应用自身也会重建计划。验证文档说明另有启动测试前的系统闹钟取证，因此不能认定整体验收无效，但该方法单独通过不足以证明重启／升级接收器正常。
- `android/app/src/androidTest/java/com/yohaku/expensetracker/SystemHandoffTest.kt:133`：固定要求事件数为 0，与 `targetCase=budget` 在 `:61` 创建一笔真实支出的夹具不兼容。历史文档似乎只对 recurring 执行完整恢复链，因此暂不能确定其他目标是否也应支持此验证阶段。


# 分区 12-tests-6

已完整阅读清单中的全部 61 个 Kotlin 测试及夹具文件、扫描 manifest、5 份几何 JSON 和两份引导参考 JSON；其余生成 JSON、Room schema 与图片按要求核对结构、对应版本及使用方，未修改文件，也未运行构建或测试。

1. **[严重度 中] 自动拍摄重置测试被另一个超时条件掩盖，错误实现仍能通过**

   位置：`android/app/src/test/java/com/yohaku/expensetracker/ui/features/scan/ScanCapturePolicyTest.kt:20`；相关实现 `android/app/src/main/java/com/yohaku/expensetracker/ui/features/scan/ScanCapturePolicy.kt:69`

   问题：前三种情况在 700ms 模拟禁用、不合格或手机移动，下一次有效帧却到 2600ms 才出现。这个间隔超过独立的 `MAX_FRAME_GAP_MS=1500`，会自行重置累计时间。

   因此，即使条件失败分支只返回零进度、完全忘记清除旧计时，当前测试仍会通过；实际连续帧下则可能在手机刚停止移动时立即拍摄，没有重新稳定满 800ms。

   建议：前三种情况在未超过帧间隔阈值时恢复有效帧，检查重新累计完整稳定时间；长时间缺帧单独验证。

   把握：确定。

2. **[严重度 低] CSV 本地分析声称只输出汇总，却打印逐笔原始金额**

   位置：`android/app/src/test/java/com/yohaku/expensetracker/CsvParserTest.kt:38、50`

   问题：注释明确承诺只打印汇总信息，但设置 `YOHAKU_CSV_SAMPLE` 后，只要存在日期或金额异常，就会向测试日志输出每条记录的行号、类型和原始 `amount`。日期错误但金额有效的私人交易也会被逐笔打印，与声明不符。

   建议：输出错误类别和计数；若需要定位，保留行号而省略原始金额。

   把握：确定。

**值得注意但不确定**

- `CashProjectionFixture.kt:21` 修改了 `postings`，却未同步 `storedPostings`，违反真实快照中两者的包含关系。当前调用的付款日期没有使这一差异影响断言，尚不能确认已有误判；未来付款场景复用该夹具时可能漏掉现金时间线中的付款。


# 分区 13-docs-1

已完整阅读清单内全部文本文件，包括所有截图采集脚本、Python 工具及四份说明文档；`design/assets/audio-example.wav` 按二进制例外核对了格式、大小和引用，并补读相关实现。未修改文件，未运行构建、测试或模拟器。

1. **[严重度 中] 通用视觉验证命令会使待审阅截图随应用卸载而消失**
   - 位置：`android/README.md:506`、`:509`、`:526`；对照 `android/README.md:78`、`:221`。
   - 问题：通用流程要求运行 `connectedDebugAndroidTest` 后查看截图，但本文另两处明确说明该任务结束会卸载应用。示例 `AnalysisFlowTest` 将截图写入应用的外部专属目录（`android/app/src/androidTest/java/com/yohaku/expensetracker/AnalysisFlowTest.kt:62`），没有配置卸载前收集这些文件。照通用命令操作，测试可能通过，却无法完成要求的截图审阅。
   - 建议：统一采用串行构建、安装、`am instrument`、导出并审阅截图的流程，或明确配置卸载前的截图收集。
   - 把握：确定。

2. **[严重度 中] 当前数据库版本说明落后两个版本，并与发布包信息混杂**
   - 位置：`android/README.md:9`、`:622`；`android/RELEASE.md:3`。
   - 问题：README 两处把当前数据库写成 v39；实际 `YohakuDatabase.kt:147` 为 **v41**，迁移列表已包含 39→40 和 40→41，后者新增 `statement_review_skips`。备份版本也直接引用当前数据库版本。与此同时，安装包章节链接的是发行说明中记载的 Room v28 历史包。维护者据此判断迁移终点、备份兼容性或安装包包含的功能，会得到不同答案。
   - 建议：明确分开“已发布 APK 元数据”和“当前源码元数据”；当前版本统一为 v41，并补齐两次迁移说明。
   - 把握：确定。

3. **[严重度 低] 小组件章节同时宣称已完成和未执行同一批验收**
   - 位置：`android/README.md:92`、`:94`。
   - 问题：第92行记载模拟器仪器测试通过、51张图片已查看，以及真实启动器配置和点击验证；紧接着第94行又说本轮未执行仪器测试，截图审阅、桌面配置仍待验收。没有日期或阶段区分，无法据此确定哪些工作已经完成，容易造成重复验收或遗漏。
   - 建议：将第94行改为明确标注日期的历史实现记录；当前待办只保留尚未完成的项目。
   - 把握：确定。

4. **[严重度 低] 云端命令数量和能力产物更新状态未同步**
   - 位置：`android/README.md:603`、`:607`、`:627`。
   - 问题：第603行仍称“当前87个命令”，而当前能力样例实际有 **90个**，注册器已包含触感、自动拍摄、自动处理三个偏好命令。第607行说能力样例和 schema 已同步更新，第627行又称当前阶段8仍需重新生成。按此检查云端覆盖范围，会误判新增能力缺失或产物尚未生成。
   - 建议：更新当前数量；将过往“待生成”说明标为历史状态，统一指向现行能力产物和覆盖审计。
   - 把握：确定。

5. **[严重度 低] CSV 章节给出的截图参数不足以生成所宣称的六组截图**
   - 位置：`android/README.md:290`；对照 `android/README.md:568`、`:584`。
   - 问题：CSV 章节称传 `csvVisual=true` 即可输出三主题中日文截图。实际 `CsvImportRoundTripTest.kt:107` 仅用此参数开启展示分支，`:125` 还要求 `visual=true` 才写图，`:130` 使用 `testLooks`，默认只有一组外观。因此单独照该说明运行不会生成截图；补上 `visual=true` 后仍不会覆盖六组。
   - 建议：明确同时使用 `csvVisual=true`、`visual=true`、`looks=all`，并沿用保留截图的运行方式。
   - 把握：确定。

6. **[严重度 低] 日文设计稿的基础导航仍向读屏提供中文标签**
   - 位置：`design/app.html:20`、`:21`；`design/app.js:26`。
   - 问题：账本按钮、主导航和弹层关闭按钮分别硬编码 `aria-label="切换账本"`、`"主导航"`、`"关闭"`。切换日文时，`render()` 更新页面语言及语言按钮、设置按钮的标签，却没有更新这三个标签。结果是日文界面仍以中文朗读基础操作名称。
   - 建议：在统一语言渲染入口同步更新这些无障碍标签。
   - 把握：确定。

### 值得注意但不确定

- `android/README.md:92` 记载小组件测试“3项通过”，但公开命令只设置 `looks=all visual=true`；第三项 `seedRealDesktopBooks` 在未设置 `desktopSeed=true` 时会跳过（`android/app/src/androidTest/java/com/yohaku/expensetracker/DesktopWidgetVisualTest.kt:170`）。可能历史验收另行执行过种子方法，尚不能确认记录错误；建议注明实际参数及通过、跳过数量。


# 分区 13-docs-2

已完整阅读清单中的 16 个非生成文件（四个 SVG、`capture.*`、`category-icons.*`、`category-management.html`、`completion.*`、`data.js`、`entry-dock-accounts-grid.html`、`experience.*`）；`category-icon-catalog.js` 按生成物例外核对，其 450 个图标的 ID、名称、关键词及路径与 Android 目录一致，无遗漏文件。

以下问题均指 HTML 原型；不将文档已说明的冻结设计、示例算法及云端入口替换视为 Android 缺陷。全程未修改文件、未运行构建或测试。

1. **[严重度 中] 还款入口丢弃指定的目标账户**
   - 位置：`design/capture.js:31`、`design/capture.js:42`、`design/completion.js:62`
   - 问题：`loan-pay` 传入 `to:id`，但 `entry()` 初始化字段时没有接收 `context.to`。例如从人民币借款详情点击还款，目标下拉会默认选中列表中的第一张信用卡，而非当前借款；直接保存就会记到错误账户。
   - 建议：初始化目标字段时接收入口参数，并确保预填状态与实际下拉选择一致。
   - 把握：确定。

2. **[严重度 中] 默认付款账户与金额输入框使用不同币种**
   - 位置：`design/capture.js:20`、`design/capture.js:42`、`design/experience.js:6`、`design/experience.js:35`
   - 问题：普通记账先选分类时，`fields.account` 默认为空；金额区域因此按 JPY 渲染，下拉却自动选中第一个实际账户。新建只有 CNY 账户的账本后直接记账，会看到“JPY”金额标签和整数步长，保存时却按 CNY 处理。
   - 建议：渲染金额区域前，先将有效的默认账户写入表单状态。
   - 把握：确定。

3. **[严重度 中] 更换分类图标会丢失父分类和预算输入**
   - 位置：`design/category-icons.js:49`、`:56`、`:107`、`:111`；`design/hierarchy.js:24`
   - 问题：父分类监听器匹配原生 `select`，实际控件却是隐藏 `input` 加按钮，监听永远不会触发；打开图标弹层又只保存名称，预算始终从已存分类读取。新建分类选择父级、填写预算后进入图标选择，返回时父级恢复初始值、预算恢复为空（编辑已有分类则恢复旧预算），继续保存会误存为一级分类或得到错误预算。
   - 建议：进入图标选择前保存完整编辑草稿，并接入真实下拉控件的变更事件；父分类选择、图标继承和返回编辑统一使用该草稿状态。
   - 把握：确定。

4. **[严重度 中] “历史补录”取消勾选后仍可能按勾选状态保存**
   - 位置：`design/capture.js:18`、`:125`；`design/completion.js:84`、`:88`、`:97`
   - 问题：`captureFields()` 将 `FormData` 合并到旧字段，未勾选的复选框不会出现在 `FormData` 中，因此旧的 `historicalIncluded='on'` 不会清除。编辑已标记历史补录的记录，取消勾选并保存，仍不改变账户余额；中途重绘也会重新勾选。
   - 建议：显式读取复选框布尔值，保存与余额处理统一使用布尔类型。
   - 把握：确定。

5. **[严重度 中] 编辑还款会重复扣减待还金额，校验失败也会执行后处理**
   - 位置：`design/completion.js:84`、`design/capture.js:123`
   - 问题：保存后的处理每次都从 `debt.next` 减去整笔还款，没有撤回原记录的影响。例如待还 1,000，记录还款 100 后为 900；只修改备注再保存，变成 800。更严重的是，费用大于总额等校验失败也返回 `true`，外层仍能找到旧记录并继续扣减。关联费用记录也只创建、不随编辑更新。
   - 建议：区分“已处理”和“保存成功”，仅成功后处理影响；编辑按新旧差量更新待还与关联费用。
   - 把握：确定。

6. **[严重度 中] 换汇另付手续费的账户没有保存，编辑会转移扣款账户**
   - 位置：`design/experience.js:61`、`:67`、`:167`
   - 问题：表单提供 `feeAccount`，但转账记录未保存它，重新编辑也不从关联费用恢复。首次选择第三个账户另付手续费后，只修改转账备注再保存，手续费账户会默认回到转出账户；代码撤回旧费用并在新账户扣款。
   - 建议：保存并恢复费用账户，编辑时保留完整费用归属。
   - 把握：确定。

7. **[严重度 中] 活动预算影响预览返回后，后续保存绕过确认**
   - 位置：`design/completion.js:56`、`:74`
   - 问题：进入影响预览时保存 `savedActivityForm`，点击“返回修改”却不清除它。再次提交时 `!savedActivityForm` 为假，直接保存、不再预览；该变量还会影响之后其他活动的修改。
   - 建议：退出预览时清除待确认状态，每次提交根据当前草稿重新判断影响。
   - 把握：确定。

8. **[严重度 中] 退款预览返回后，界面显示与实际提交值不一致**
   - 位置：`design/completion.js:30`、`:31`、`:61`；关联 `design/themes.js:52`
   - 问题：返回退款表单时只恢复输入元素的 `value`，没有恢复自定义下拉的可见文字和选中标记。例如选择“待到账”后预览、返回，界面显示默认的“已实际到账”，隐藏字段仍是 `pending`，保存结果与所见状态相反；更换到账账户也有同样问题。
   - 建议：从完整退款草稿重新渲染表单，避免单独修改隐藏字段。
   - 把握：确定。

9. **[严重度 中] 换汇保存成功后残留草稿，可以重复记账**
   - 位置：`design/completion.js:14`、`:84`；`design/experience.js:167`
   - 问题：换汇通过内层 `oldSubmitAudit()` 保存并返回 `true`，外层立即返回，跳过 `localDrafts.delete(currentBook)`。换汇过程中切主题或保留草稿后再保存，首页仍提供“继续未保存的记录”；恢复的新建草稿没有已保存记录 ID，再保存会新增第二笔转账。
   - 建议：所有成功保存路径统一清理对应草稿，失败时保留。
   - 把握：确定。

10. **[严重度 中] 新建空白账本仍携带演示商户，并自动填入新记账**
    - 位置：`design/experience.js:128`、`design/data.js:34`、`design/completion.js:128`
    - 问题：新账本通过 `seedBook()` 建立，清空了分类、记录等数据，却遗漏 `merchants`。即使用户未选择体验数据，新账本仍有虚构商户和地点，首次消费会自动填入“喫茶と余白”。
    - 建议：空白账本使用独立初始化数据，或完整清除演示商户；样例只由明确的体验操作添加。
    - 把握：确定。

11. **[严重度 中] 未知商品金额按零汇总，却显示“金额一致”**
    - 位置：`design/capture.js:50`、`:76`
    - 问题：商品合计使用 `Number(x.total)||0`，没有区分完整合计与已知小计。例如一项金额 8,500、另一项金额未知，原记录也是 8,500，预览会宣称“商品合计与记账金额一致”，掩盖未知项。Android 的 `ReceiptProducts.kt:86` 已有明细不完整提示，原型未表达该状态。
    - 建议：保留完整性标记，缺金额时显示“已知小计”和未完整提示，避免直接断言一致。
    - 把握：确定。

12. **[严重度 低] 分类图标对照页切到“分类分析”仍可能显示预算**
    - 位置：`design/category-icons.js:126`、`:127`
    - 问题：预算入口设置 `analysisTab='budget'`，分析入口没有恢复为 `spend`。先点“分类预算”再点“分类分析”，三列仍停留在预算内容，无法按按钮含义查看分析卡片。
    - 建议：每个对照状态显式设置所需页签，消除操作顺序依赖。
    - 把握：确定。

13. **[严重度 低] 外币小票差额套用了当前页面的币种**
    - 位置：`design/capture.js:49`、`design/experience.js:176`；关联 `design/app.js:10`
    - 问题：差额按商品币种计算，却通过 `yen(difference)` 使用页面币种展示。例如日元账本内原消费 USD 100、商品合计 USD 101.25，从首页打开详情会把 USD 1.25 差额显示为约 ¥1；导入预览使用 `moneyIn(diff,row.currency)`，前后口径不一致。
    - 建议：差额始终使用参与比较的商品币种格式化。
    - 把握：确定。

14. **[严重度 低] 借款服务费保存后重新打开为空，再保存会清除**
    - 位置：`design/experience.js:107`、`:166`
    - 问题：服务费字段初值固定为 `''`，其他规则字段会读取已保存值。填写服务费、保存，再打开修改其他规则，服务费显示为空，提交后原值被空字符串覆盖。
    - 建议：按 `a.loanRules?.serviceFee` 恢复字段。
    - 把握：确定。

15. **[严重度 低] 修改示例商品名称后，日文界面继续显示旧名称**
    - 位置：`design/capture.js:50`、`:139`、`:140`
    - 问题：示例商品同时有 `name` 和 `ja`；编辑器只修改 `name`，日文展示优先读取 `ja`。修改“越光米 5kg”等商品后，切到日文仍显示原名称，表现为修改未生效；日文编辑器本身也读取中文 `name`。
    - 建议：明确商品名称的编辑语义，单一用户名称不应被旧示例翻译覆盖。
    - 把握：确定。

16. **[严重度 低] 小票图片中的支付方式和页数与配套数据矛盾**
    - 位置：`design/assets/receipt-01.svg:1`、`design/assets/receipt-02.svg:1`、`design/assets/receipt-03.svg:1`、`design/data.js:20`、`design/capture.js:15`
    - 问题：三张图都写“三井住友カード”，对应记录 `f2` 却使用银行账户 `bank-s`，`f1` 使用 PayPay。`receipt-03.svg` 写“1 / 2”，但其小票组只有这一张图，且全部商品已齐全。核对样例时会被误认为存在支付方式差异或缺页。
    - 建议：统一图片与演示记录的支付资料，将单页小票标成 `1 / 1`。
    - 把握：确定。

### 值得注意但不确定

- `design/category-icons.html:15` 的三列容器同时使用 `justify-content:center`、固定宽度子项和横向滚动。窄窗口下可能产生左侧负向溢出，使第一列部分内容无法滚动到；本次未运行浏览器，未确认实际表现。
- `design/capture.css:2` 为商品单价、金额分配固定窄列并禁止换行；外币前缀及两位小数可能造成列间重叠，尤其是手账主题。需以实际字体渲染核实，暂不列为确定问题。


# 分区 13-docs-3

已逐个完整阅读清单中的全部 26 个文本文件（字体说明及许可、全部指定 HTML/JS/CSS、两份 docs），并读取相关 Android 实现核实；无遗漏，未修改文件或运行测试。

1. **[严重度 中] 手账宽版小组件超预算时显示错误等式**
   
   位置：`design/widgets.html:284`；根因在分区外：`android/app/src/main/java/com/yohaku/expensetracker/platform/desktop/WidgetBudget.kt:19`、`android/app/src/main/java/com/yohaku/expensetracker/platform/desktop/WidgetRenderer.kt:73`、`:82`。
   
   问题：设计稿的超支算式正确保留负号，但原生投影把超支额转成正数，手账宽版却继续显示“预算 − 支出 =”。预算 180,000、支出 192,400 时，会显示“180,000 − 192,400 = 12,400”。这是实际 App 的显示逻辑错误。
   
   建议：手账算式使用带符号的预算余额，或在超支状态改为“支出 − 预算 = 超出额”。
   
   把握：确定。

2. **[严重度 中] 导入预览将文件中的日期范围直接解析为 HTML**
   
   位置：`design/forms.js:89`、`:90`；`design/views.js:31`。
   
   问题：`from`、`to` 只检查非空，随后未经转义拼入 `innerHTML`。导入一个版本、账本、账户和明细均合法，但 `from` 含 HTML 标签及事件处理器的 JSON，即可在预览阶段插入页面元素并执行脚本，不需要点击确认导入。后续核对页面也直接插入这两个字段。影响范围是浏览器设计原型，未发现这里涉及 Android 导入实现。
   
   建议：校验日期范围字段，并在所有展示位置转义或使用 `textContent`。
   
   把握：确定。

3. **[严重度 中] 消费趋势重复统计 12 日，并遗漏后半月**
   
   位置：`design/themes.js:14`、`design/views.js:5`；调用核实：`design/experience.js:96`、`design/completion.js:133`。
   
   问题：趋势固定使用 `[1,3,5,7,9,11,12]`。标为 11 的柱包含 11、12 日，标为 12 的柱又包含 12 日，同一笔消费显示两次；13 日以后的消费完全没有柱。切到上月时，示例中 9 月 20 日的消费仍计入总额，却不进入趋势。后续代码只替换了自定义区间分支，普通月份仍执行此逻辑。
   
   建议：按当前月份生成互不重叠、覆盖完整区间的日期分组，并让横轴标签准确表达分组范围。
   
   把握：确定。

4. **[严重度 低] 活动删除设计稿仍宣称无法恢复，与本机及云端流程不一致**
   
   位置：`design/management-dialogs.js:32`；核实：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/ActivityScreens.kt:61`、`android/app/src/main/java/com/yohaku/expensetracker/data/repository/ActivityManagementRepository.kt:55`、`docs/plans/cloud-command-stage-4.md:72`。
   
   问题：设计弹窗的中日文仍写“删除无法撤销／削除は元に戻せません”。当前 App 已提供恢复按钮，云端 `activity.lifecycle RESTORE` 也复用恢复接口；原生删除弹窗已经改为说明恢复不会自动重启周期和预留。继续依据此设计稿评审，会得到错误的生命周期约定。
   
   建议：同步原生弹窗说明，区分恢复活动显示与完整撤销删除的范围。
   
   把握：确定。

5. **[严重度 低] 小物主题金额输入固定显示日元符号**
   
   位置：`design/themes.js:49`；调用核实：`design/experience.js:35`。
   
   问题：所有名为 `amount`、`simAmount` 的输入都附加固定 `¥` 装饰。创建美元账户并用它记账时，标签显示 USD，金额框内仍显示 `¥`，形成相互矛盾的币种提示。
   
   建议：根据该金额字段的币种生成符号，或使用不含币种符号的硬币装饰。
   
   把握：确定。

**值得注意但不确定**

- `design/widgets.html:322` 表述小物图标沿用 App 对应颜色，但桌面绘制按模板序号取色（`android/app/src/main/java/com/yohaku/expensetracker/platform/desktop/DesktopArtwork.kt:63`），App 分类图标按图标分组取色（`android/app/src/main/java/com/yohaku/expensetracker/ui/components/CategoryIconBadge.kt:20`）。因此同一分类在桌面排序变化后可能变色；尚不能确认这是否属于刻意允许的桌面设计差异。


# 分区 13-docs-4

已完整阅读指定的全部 18 份文档，包括触感设计、实现规格、阶段 E 验收、商户改造计划、云端检查表／覆盖表／总计划／schema 审计、阶段 0–8 记录及开发计划，并对照相关源码核实；无未读文件，未修改文件或运行测试。

1. **[严重度 中] 云端“全覆盖、UI 无直接写库”的结论已不成立**
   - 位置：`docs/plans/cloud-command-coverage.md:319`、`:327`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/HomeStatementDue.kt:67`
   - 问题：“本期／这些期间不核对”直接调用 DAO 写入 `statement_review_skips`。覆盖表没有登记该操作，命令注册表也没有对应能力。用户能在手机执行的持久操作无法通过云端办理，且违背文档声明的共享仓库边界。
   - 建议：下沉共同业务入口，补齐命令及必要的导出、撤销、覆盖说明。
   - 把握：确定。

2. **[严重度 中] 流水协议字段说明不足以生成合法输入**
   - 位置：`docs/implementation-spec.md:205`、`:209`；`android/app/src/main/java/com/yohaku/expensetracker/data/files/FileProtocols.kt:30`、`:90`
   - 问题：文件字段清单漏掉必填的 `date_basis`；`row_id` 只描述为文件内唯一，并提及保留银行流水号，却未说明必须为 UUID。按此说明省略 `date_basis`，或填写 `row_id="001"`，都会被解析器拒绝。正式格式说明实际上要求银行原流水号放入 `source_ref`。
   - 建议：明确 `date_basis` 的必填性及枚举；要求生成稳定 UUID，并区分 `row_id` 与 `source_ref`。
   - 把握：确定。

3. **[严重度 中] 规格承诺更新重叠核对批次，实际只能新增**
   - 位置：`docs/implementation-spec.md:220`；`android/app/src/main/java/com/yohaku/expensetracker/data/repository/FileWorkflowRepository.kt:52`
   - 问题：文档称重叠区间“可更新对应核对批次或独立保存”。实际保存先拒绝重复 `file_id`，然后仅执行 `insertStatement`；界面也只提供“保留独立重叠批次”。修正已有流水文件后沿用原 ID，会收到“文件已导入”，无法完成文档描述的更新。
   - 建议：根据真实产品决定，删除更新承诺，或实现明确的替换预览与关联处理。
   - 把握：确定。

4. **[严重度 中] 开发计划仍禁止已经实现的备份和云端功能**
   - 位置：`docs/plans/development-plan.md:77`、`:436`；`docs/implementation-spec.md:290`、`:375`
   - 问题：“明确范围”仍写“不提供备份”“不追加云服务”，技术决策仍写“网络仅参考汇率”。当前已有完整备份恢复、云端分析／办理和联网地图。按该执行计划判断需求，会把现有正式功能误判为越界。
   - 建议：更新当前范围，区分手动本地备份、主动云端交接与仍不提供的同步／自动云备份。
   - 把握：确定。

5. **[严重度 低] 通用适配器检查表保留旧版撤销语义**
   - 位置：`docs/plans/cloud-command-adapter-checklist.md:44`、`:47`；`docs/plans/cloud-command-stage-3.md:73`
   - 问题：对照表仍描述通过 `saveAccount` 恢复账户、改分类撤销时追加修订。当前新执行记录统一生成 v3 行快照逆方案，恢复原 revision 和保存顺序，并移除本次新增修订；追加修订仅用于旧 v2 分类撤销。检查表没有注明该限制，容易指导出错误的新适配器。
   - 建议：以 v3 描述当前契约，将旧行为标为 v2 兼容说明。
   - 把握：确定。

6. **[严重度 低] “下一步”仍要求从已经完成的 P21 开始**
   - 位置：`docs/plans/development-plan.md:439`、`:526`
   - 问题：文件顶部及 P21–P25 已明确阶段 E 完成，但“当前仓库”和“执行进度与下一步”仍要求从 P21 接续。接手者按导航段执行会重复实施已完成任务。
   - 建议：统一完成状态，将这些指令改为历史说明或指向后续增量计划。
   - 把握：确定。

7. **[严重度 低] 规格来源索引因章节调整而指错内容**
   - 位置：`docs/plans/development-plan.md:600`、`:604`、`:606`、`:612`
   - 问题：P05 指向落地规格 §3.2，P07/P13 指向 §3.3；这两节现在分别是云端分析交接、云端办理，并非首次设置、草稿或附件存储。按索引阅读会漏掉真正位于 §3.1.1–3.1.2 的约束。
   - 建议：更新章节索引，优先使用具名章节链接。
   - 把握：确定。

8. **[严重度 低] 覆盖审计的最终附件清单漏掉必传 schema**
   - 位置：`docs/plans/cloud-command-coverage.md:329`；`android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt:225`
   - 问题：最终结论称普通办理必带三份、月评四份资料；代码实际还必传 `yohaku-command-plan-v1.schema.json`，分别为四份和五份。按该清单检查上传完整性或准备助手资料，会漏掉方案结构约束。
   - 建议：与实现规格及阶段 8 的现行附件表统一。
   - 把握：确定。

9. **[严重度 低] 触感文档对云端影响自相矛盾**
   - 位置：`docs/haptics.md:135`、`:141`
   - 问题：前文称触感“不影响云端协议、数据和流程”，后文及代码却已增加 `preferences.haptics`，涉及能力 schema、摘要、偏好快照和撤销。维护者只按前一条判断联动范围，会漏同步这些产物。
   - 建议：区分“触感播放属于本机 UI”与“触感偏好属于云端可管理数据”。
   - 把握：确定。

10. **[严重度 低] 实现规格的设置页清单遗漏现有入口**
    - 位置：`docs/implementation-spec.md:285`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/SettingsScreen.kt:35`
    - 问题：规格仍写“外观与语言”，没有触感开关；“账本与分类”清单也遗漏记账模板。实际分别为“外观、语言与触感”以及包含模板的设置组。依据规格做入口覆盖或 UI 验收会漏项。
    - 建议：同步当前分组名称和入口清单。
    - 把握：确定。

11. **[严重度 低] 相机触感说明仍指向已替换的外部相机流程**
    - 位置：`docs/haptics.md:101`；`android/app/src/main/java/com/yohaku/expensetracker/ui/features/MediaPanels.kt:139`
    - 问题：文档仍描述“相机无可用应用”在 `MediaPanels` 发出 `Error`。现在拍照进入应用内 `ScanFlow`，不可用状态由 `ScanCameraScreen` 处理，原条件和对应触感调用已不存在，§10 也未说明此变化。
    - 建议：按当前相机／扫描失败状态更新触感契约和实现对应位置。
    - 把握：确定。

### 值得注意但不确定

- `docs/haptics.md:10` 承诺跟随系统触摸振动强度；实现依赖系统振动属性处理强度。本次未做设备验证，不能确认不同 Android 版本及三星设置下是否完全符合该承诺，不列为已证实缺陷。


# 分区 13-docs-5

已完整读完清单中的 7 个文本文件：两份计划、产品规格、v55 校验清单、金融增量索引、UI 实现对照及小票 JSON 示例；其余字体、PDF、ZIP、源码归档和 26 张历史预览图按二进制要求核对，预览图仅检查类型、尺寸及历史引用，未逐图视觉审查。全程未修改文件、未运行构建或测试。

1. **[严重度 中] 产品规格遗漏单笔预算排除，导致首页和日历口径描述错误**
   - 位置：`docs/product-design.md:172`、`:181`、`:517`；`docs/ui-implementation-map.md:603`
   - 问题：产品规格仍把预算排除主要归因于独立活动，首页旁列名称仍写“独立活动支出”，日历说明也未区分实际消费与预算消费。实际代码将 `event.budgetExcluded` 与活动排除取并集，首页已显示“其中不计入预算”，日历金额用实际消费、颜色用预算消费。例如，没有活动的 ¥3,000 支出开启“不计入预算”后，仍显示日历金额，但不触发预算超支着色；按现规格会得到相反预期。
   - 证据：`android/app/src/main/java/com/yohaku/expensetracker/data/repository/BudgetProjection.kt:12`、`ui/features/LedgerCalendar.kt:94`、`ui/features/HomeSections.kt:23`。
   - 建议：补齐单笔及模板排除规则，并同步首页名称、退款/费用跟随规则和日历双口径说明。
   - 把握：确定。

2. **[严重度 低] 日期批量还款仍保留已被替代的费用预填规则**
   - 位置：`docs/plans/financial-account-ui-plan.md:1108`，对照同文件 `:360`
   - 问题：末节仍规定“非零合并费用不拆分”，但第 5.3 节已记录后续决定：追加费用归平台费用，其余归利息。当前日期还款也复用该拆分。例如两张账单本金各 ¥1,000，其中一张合并费用 ¥100、没有单列追加费用，实际预填本金 ¥2,000、利息 ¥100、平台费用 ¥0，而非保留未拆分状态。
   - 证据：`android/app/src/main/java/com/yohaku/expensetracker/ui/features/FinancialRepaymentState.kt:21`、`:57`；对应测试源码 `android/app/src/test/java/com/yohaku/expensetracker/ui/features/FinancialRepaymentStateTest.kt:59`。
   - 建议：更新末节规则，明确单目标及日期多目标均采用最新预填约定。
   - 把握：确定。

3. **[严重度 低] UI 实现与验收矩阵漏登记扫描页面**
   - 位置：`docs/ui-implementation-map.md:114`、`:115`；`docs/plans/receipt-scanner-plan.md:155`、`:166`
   - 问题：实现对照完整文件没有扫描相机、确认/轮廓调整页或“处理这张”的登记；UI22/UI23 仍只列附件、工作台及商品回填。扫描计划与产品规格却已声明这些页面采用定稿设计 A、阶段 1–6 完成。按“逐页实现与验收对照”组织检查时，会遗漏这批新增页面及权限、处理失败等主题状态。
   - 建议：补入扫描页面、入口、设计基准和现有验证索引，并区分已验证范围与待真机验收部分。
   - 把握：确定。

4. **[严重度 低] 云端覆盖汇总的命令数量和页面编号失效**
   - 位置：`docs/ui-implementation-map.md:576`、`:119`
   - 问题：汇总称“当前87个命令沿用UI29–UI34”，但当前能力清单为 90 个，已包含触感及两个扫描偏好命令；本文件页面矩阵也只定义到 UI31，没有 UI32–UI34。据此核查覆盖范围会漏算新增能力，并找不到所引用的页面条目。
   - 证据：`docs/formats/examples/capabilities.valid.json`；`android/app/src/main/java/com/yohaku/expensetracker/data/command/FollowupCommandAdapters.kt:50`。
   - 建议：更新当前数量或明确其历史日期；为月计划、月评和问答补齐对应编号，或改用已有章节链接。
   - 把握：确定。

### 值得注意但不确定

- `docs/plans/receipt-scanner-plan.md:200` 将“拍照约 1.1 秒、预览约 2 次/秒”写入非高通设备的回退说明，但已列出的测速依据来自 S24 Ultra。无法据仓库材料确认其他芯片性能，建议标明这是该机 CPU 路径的参考值；本次未发现足够证据证明其他设备的功能回退失败。

# 视觉检查结果

主要问题：
- 高
  - 几乎所有页面的大标题在 S24 宽度下都断成孤字，比如「这一笔，记在哪/里。」。
  - 信用卡空状态用超大标题字号显示「尚无已知待付日期」，「期」字单独掉到下一行。
  - 记了还款之后，卡片仍显示原欠款，看不到"已付、待确认"。
- 中
  - 没有商户的流水，标题只写「支出」「收入」。
  - 「使用最近可用日期」这种正常的汇率提示用了红色。
  - 汇率显示十几位小数；负号写法「¥-1,200」和「−¥」混用，与 Codex 报的 BudgetLayout 超支显示是同一根因。
  - 外币记账时，大号数字显示的是换算后金额，原币反而是小字。
  - 新建账本引导里「先跳过」是主按钮，添加账户反而是次要按钮，层级反了。
  - 杂志主题里混入了圆角胶囊按钮。
  - 小物主题下拉框用「+」「−」表示展开收起，容易读成添加和删除。
  - 杂志主题字号和对比度普遍偏低。
  - 新增周期账单时默认选中了第一个分类。
