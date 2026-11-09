# 云端办理 schema 后续改进审计

本表逐项覆盖当前90条注册命令。源码路径前缀均为 `android/app/src/main/java/com/yohaku/expensetracker/`；除特别注明的领域文件，校验位置位于 `data/command/` 或 `data/repository/`。

- D：`format=date`、`pattern=^[0-9]{4}-[0-9]{2}-[0-9]{2}$`，本机另用严格 `LocalDate.parse` 排除不存在日期。
- M：`pattern=^[0-9]{4}-(0[1-9]|1[0-2])$`、`x-format=year-month`，本机 `YearMonth.parse`。
- T / I / Z：分别为 `x-format=local-time/instant/zone-id`，调用 `LocalTime.parse` / `Instant.parse` / `ZoneId.of`。不使用语义更窄的 RFC3339 time/date-time 去替代 Java 实际接受范围。
- 数值区间使用 `minimum/maximum`；金额均指最小单位整数。可空字段的硬约束在非 null 分支；不会把未知 null 改成零或新增必填。
- C：条件约束，放在 `x-local-constraint={when,schema}` 中；schema 复用同一格式/范围 helper。它是机器可读提示，由现有本机业务路径按条件执行，不是无条件 JSON Schema 断言。这样不拒绝本机本来会忽略的字段，也不把可保存的不完整规则改成拒绝保存。
- “无本次约束”表示没有可新增的日期/月/时间解析或固定整数范围；不代表没有枚举、引用、关联、数量、金额关系等现有校验。

## 逐命令审计表

| 命令 | 字段 → 约束 | 原有本机校验位置 |
|---|---|---|
| 1. `account.save` | openingDate：D；creditLimit：L（同 creditLimit.save）；openingMinor 无固定正负约束 | CommandAdapters.kt AccountCommandAdapter.validate；LedgerRepository.kt saveAccount；CreditLimitRepository.kt previewCreditLimit |
| 2. `financialRule.addVersion` | rule.effectiveFrom：D；rule.zoneId：Z；rule.deadlines[].date/time/zoneId：D/T/Z；billing.closingDay/paymentDay：C[1,31]，paymentMonthOffset：C≥0；bonusPayments[].*Month：C[1,12]，*Day：C[1,31]；installment.supportedPeriods[]/feeTable[].periods：C≥1，totalFeeMinor：C≥0；loanInterest/revolving.interest.yearDays：C≥1（DAILY）；revolving.paymentMinor、tiers[].paymentMinor/upToMinor：C≥1 | FinancialRepository.kt previewFinancialRule；FinancialRules.kt issues、ChangeDeadline.instant、billingDates；CardPaymentDates.kt bonusPaymentIssues（不完整规则可保存） |
| 3. `financialPrediction.save` | terms.agreementDate/paymentDates[]/accrualWindows[].start/end：D；firstPeriodNumber≥1；additionalFees[].number≥1、minor≥0；overrides[].number≥1、principalMinor/feeMinor≥0；remainingFeeMinor：C≥0；paymentSettings[].paymentMinor/tiers[].paymentMinor/upToMinor：C≥1；candidates.agreementDate/firstDate/firstEnd：D，count[1,1200]，firstPeriodNumber≥1，remainingFeeMinor：C≥0；openingPrincipalMinor：C≥1 | CommandAdapters.kt PredictionCommandAdapter.execute；PredictionCandidates.kt predictionCandidates；FinancialPredictions.kt sourcePrincipal；PredictedSchedule.kt predictedSchedule；ReconciledSchedule.kt reconciledSchedule；PaymentCalculation.kt installmentPlan |
| 4. `book.monthlyPlan.save` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 5. `book.create` | startDate：D；budgetMinor≥0 | LedgerCommandAdapters.kt BookAdapter；LedgerRepository.kt createBook/updateBook |
| 6. `book.update` | startDate：D；budgetMinor≥0 | LedgerCommandAdapters.kt BookAdapter；LedgerRepository.kt createBook/updateBook |
| 7. `book.completeSetup` | step：[0,4] | LedgerCommandAdapters.kt BookAdapter.validate |
| 8. `book.lifecycle` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 9. `account.setActive` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 10. `category.save` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 11. `category.setFavorite` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 12. `category.lifecycle` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 13. `category.reparent` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 14. `merchant.save` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 15. `merchantLocation.save` | radiusMeters≥1 | AnalysisRepository.kt saveMerchantLocation |
| 16. `friend.save` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 17. `merchant.setActive` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 18. `friend.setActive` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 19. `entry.create` | date/settlementDate/rateDate/toDate：D；feeDate：C(D)；cardPaymentMonth：C(M)，cardPaymentPeriods：C≥1；amount/accountAmount/baseAmount/toAmount/feeAmount/feeAccountAmount/feeBaseAmount/reserveAmount 的 minor：C≥1；cardPaymentFee.minor：C≥0 | LedgerRepository.kt prepareEntry；CurrencyTransactions.kt；TransferTransactions.kt prepareTransfer；CardPayments.kt cardPayment；LedgerRepository.kt 预留消费检查 |
| 20. `entry.update` | targets.select.from/through：D；set.date/settlementDate/rateDate/toDate：D；feeDate：C(D)；cardPaymentMonth：C(M)，cardPaymentPeriods：C≥1；amount/accountAmount/baseAmount/toAmount/feeAmount/feeAccountAmount/feeBaseAmount/reserveAmount 的 minor：C≥1；cardPaymentFee.minor：C≥0 | CommandAdapters.kt validateSelector；LedgerRepository.kt prepareEntry；CurrencyTransactions.kt；TransferTransactions.kt prepareTransfer；CardPayments.kt cardPayment；LedgerRepository.kt 预留消费检查 |
| 21. `entry.confirmActual` | targets.select.from/through：D；set.date/settlementDate/rateDate/toDate：D；feeDate：C(D)；cardPaymentMonth：C(M)，cardPaymentPeriods：C≥1；amount/accountAmount/baseAmount/toAmount/feeAmount/feeAccountAmount/feeBaseAmount/reserveAmount 的 minor：C≥1；cardPaymentFee.minor：C≥0 | CommandAdapters.kt validateSelector；LedgerRepository.kt prepareEntry；CurrencyTransactions.kt；TransferTransactions.kt prepareTransfer；CardPayments.kt cardPayment；LedgerRepository.kt 预留消费检查 |
| 22. `entry.lifecycle` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 23. `template.save` | amountMinor≥1；cardPaymentPeriods：C≥1 | TemplateRepository.kt saveTemplate/fixedCardPayment |
| 24. `template.move` | direction：[-1,1] 且 enum=[-1,1]（不接受0） | LedgerCommandAdapters.kt TemplateAdapter.validate；TemplateRepository.kt moveTemplate |
| 25. `template.createEntry` | set.date/settlementDate/rateDate/toDate：D；feeDate：C(D)；cardPaymentMonth：C(M)，cardPaymentPeriods：C≥1；amount/accountAmount/baseAmount/toAmount/feeAmount/feeAccountAmount/feeBaseAmount/reserveAmount 的 minor：C≥1；cardPaymentFee.minor：C≥0 | LedgerRepository.kt prepareEntry；CurrencyTransactions.kt；TransferTransactions.kt prepareTransfer；CardPayments.kt cardPayment；LedgerRepository.kt 预留消费检查 |
| 26. `budget.save` | month：M；amount.minor≥0 | PlanningCommandAdapters.kt BudgetAdapter.validate；BudgetRepository.kt previewBudget |
| 27. `budget.saveBook` | month：M；startDate：D；amount.minor≥0 | PlanningCommandAdapters.kt BudgetAdapter.validate；BudgetRepository.kt previewBookBudget；LedgerRepository.kt updateBook |
| 28. `activity.setMode` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 29. `activity.setCurrent` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 30. `activity.save` | start/end：D；budget.minor/categoryBudgets[].amount.minor≥0 | ActivityRepository.kt previewActivity |
| 31. `activity.end` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 32. `activity.lifecycle` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 33. `activityReserve.save` | date：D；amount.minor≥1；accountAmount.minor：C≥1（指定account时） | ActivityRepository.kt previewReserve |
| 34. `activityReserve.release` | amount.minor≥1 | ActivityRepository.kt previewRelease |
| 35. `activityParticipant.add` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 36. `activityTreat.save` | date：D；total.minor/base.minor≥1 | TreatRepository.kt prepareTreat |
| 37. `activityTreat.lifecycle` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 38. `recurrence.save` | anchorDate/endDate：D；amount.minor≥1；baseAmount.minor：C≥1（已知外币金额时）；cardPaymentPeriods：C≥1 | RecurrenceRepository.kt saveRecurrence；TemplateRepository.kt fixedCardPayment |
| 39. `recurrence.setActive` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 40. `occurrence.resolve` | date：D | ScheduleCommandAdapters.kt OccurrenceAdapter.validate |
| 41. `occurrence.update` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 42. `occurrence.lifecycle` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 43. `occurrence.link` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 44. `occurrence.unlink` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 45. `occurrence.pay` | set.date/settlementDate/rateDate/toDate：D；feeDate：C(D)；cardPaymentMonth：C(M)，cardPaymentPeriods：C≥1；amount/accountAmount/baseAmount/toAmount/feeAmount/feeAccountAmount/feeBaseAmount/reserveAmount 的 minor：C≥1；cardPaymentFee.minor：C≥0 | LedgerRepository.kt prepareEntry；CurrencyTransactions.kt；TransferTransactions.kt prepareTransfer；CardPayments.kt cardPayment；LedgerRepository.kt 预留消费检查 |
| 46. `expectedIncome.save` | date：D；amount.minor/accountAmount.minor≥1 | ExpectedIncomeRepository.kt previewExpectedIncome |
| 47. `expectedIncome.cancel` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 48. `expectedIncome.arrive` | set.date/settlementDate/rateDate/toDate：D；feeDate：C(D)；cardPaymentMonth：C(M)，cardPaymentPeriods：C≥1；amount/accountAmount/baseAmount/toAmount/feeAmount/feeAccountAmount/feeBaseAmount/reserveAmount 的 minor：C≥1；cardPaymentFee.minor：C≥0 | LedgerRepository.kt prepareEntry；CurrencyTransactions.kt；TransferTransactions.kt prepareTransfer；CardPayments.kt cardPayment；LedgerRepository.kt 预留消费检查 |
| 49. `financialBill.resolve` | number≥1 | FinanceCommandAdapters.kt financialBill.resolve execute |
| 50. `financialRepaymentAllocation.resolve` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 51. `financialRule.validate` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 52. `financialManualPlan.save` | periods[].date：D；totalMinor/principalMinor/feeMinor≥0；principalMinor≥1 | FinancialRepository.kt validateKnownBill/previewManualFinancialPlan |
| 53. `financialBill.confirm` | bill.date：D；totalMinor/principalMinor/feeMinor≥0 | FinancialRepository.kt validateKnownBill/previewFinancialBillConfirmation |
| 54. `financialPool.save` | terms.agreementDate/paymentDates[]/accrualWindows[].start/end：D；firstPeriodNumber≥1；additionalFees[].number≥1、minor≥0；overrides[].number≥1、principalMinor/feeMinor≥0；remainingFeeMinor：C≥0；paymentSettings[].paymentMinor/tiers[].paymentMinor/upToMinor：C≥1；openingMinor≥0；unallocatedShares[].minor：C≥0；adoptedFees[].waivedMinor：C≥0 | FinancialPools.kt poolFacts/prepareFinancialPool；PredictedSchedule.kt；ReconciledSchedule.kt；PaymentCalculation.kt |
| 55. `financialArrangement.record` | appliedAt：I；input.terms.agreementDate/paymentDates[]/accrualWindows[].start/end：D；firstPeriodNumber≥1；additionalFees[].number≥1、minor≥0；overrides[].number≥1、principalMinor/feeMinor≥0；remainingFeeMinor：C≥0；paymentSettings[].paymentMinor/tiers[].paymentMinor/upToMinor：C≥1；input.manualPeriods[].date：D；totalMinor/principalMinor/feeMinor≥0；input.changeFeeMinor≥0；input.transferPrincipalMinor：C≥1；input.poolSelection.bills[].principalMinor/cancelledFeeMinor、input.poolSelection.unbilledPrincipalMinor：C≥0；input.clearingMoves[].period≥1、principalMinor/interestMinor/feeMinor≥0 | FinancialArrangements.kt prepareFinancialArrangement/commitFinancialArrangement；FinancialPoolArrangements.kt；FinancialRepository.kt validateKnownBill；共享计划计算 |
| 56. `financialArrangement.reconcile` | effectiveDate：D；principalMinor/openingSourceMinor≥0；poolUnallocatedShares.*：C≥0；bills[].principalMinor/feeMinor、refunds[].targetMinor/bills[].principalMinor、clearingMoves[].principalMinor/interestMinor/feeMinor≥0 | FinancialArrangementReconciliation.kt prepareArrangementReconciliation（包括 failed/Incomplete 阻止执行） |
| 57. `financialPool.changePayment` | appliedAt：I；input.effectiveDate/input.setting.fromPaymentDate：D；input.feeMinor≥0；input.setting.paymentMinor/tiers[].paymentMinor/upToMinor：C≥1 | FinancialPoolPaymentChanges.kt preparePoolPaymentChange/commitPoolPaymentChange；FinancialRules.kt RevolvingRule.issues |
| 58. `financialBatchRevolving.apply` | appliedAt：I；input.paymentDate/effectiveDate：D；input.principalMinor≥1，input.feeMinor≥0 | BatchRevolvingArrangements.kt previewBatchRevolving；SinglePaymentTransfers.kt allocateSinglePaymentTransfers；FinancialArrangements.kt prepareFinancialArrangement/commitFinancialArrangement |
| 59. `financialBatchRevolving.undo` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 60. `financialBorrowing.save` | date：D；principal.minor≥1；received/withheldFee/separateFee/withheldFeeBase/separateFeeBase 的 minor：C≥0（实际借入，费用本位币仅在使用时） | FinancialLedgerRepository.kt previewBorrowing/requireMoney/feeBase |
| 61. `financialRepayment.save` | date/clearingDate：D；paid.minor≥1；received/principal/interest/fee/sourceFee 的 minor≥0；allocations[].principalMinor/interestMinor/feeMinor≥0；feeBase/sourceFeeBase.minor：C≥0 | FinancialLedgerRepository.kt previewRepayment/prepareRepaymentAllocations/requireMoney/feeBase |
| 62. `financialRepayment.confirm` | date/clearingDate：D；paid.minor≥1；received/principal/interest/fee/sourceFee 的 minor≥0；allocations[].principalMinor/interestMinor/feeMinor≥0；feeBase/sourceFeeBase.minor：C≥0 | FinancialLedgerRepository.kt previewRepayment/prepareRepaymentAllocations/requireMoney/feeBase |
| 63. `financialContract.linkPlan` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 64. `creditLimit.save` | effectiveFrom/basisDate：D；zoneId：Z；recoveryDelayDays≥0；totalMinor/installmentMinor/revolvingMinor/combinedMinor/usedTotalMinor/usedInstallmentMinor/usedRevolvingMinor≥0 | CreditLimitRepository.kt previewCreditLimit/checkAmounts/today |
| 65. `creditLimitGroup.save` | basisDate：D；totalMinor/usedMinor≥0 | CreditLimitRepository.kt previewCreditLimitGroup/checkAmounts |
| 66. `creditLimitRecovery.confirm` | date：D；principalMinor≥1；installmentMinor/revolvingMinor≥0 | CreditLimitRepository.kt previewCreditLimitRecovery/checkAmounts |
| 67. `creditLimitRecovery.void` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 68. `creditLimitObservation.record` | observedAt：I；availableMinor≥0 | CreditLimitRepository.kt recordCreditLimitObservation/checkAmounts |
| 69. `financialDraft.reclassify` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 70. `statement.accept` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 71. `statement.unlink` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 72. `statement.supplement` | occurredDate/postedDate：D（手工schema）；set.date/settlementDate/rateDate/toDate：D；feeDate：C(D)；cardPaymentMonth：C(M)，cardPaymentPeriods：C≥1；amount/accountAmount/baseAmount/toAmount/feeAmount/feeAccountAmount/feeBaseAmount/reserveAmount 的 minor：C≥1；cardPaymentFee.minor：C≥0 | FileCommandAdapters.kt StatementAdapter.validate；StatementEntryDrafts.kt；LedgerRepository.kt prepareEntry；CurrencyTransactions.kt；TransferTransactions.kt prepareTransfer；CardPayments.kt cardPayment；LedgerRepository.kt 预留消费检查 |
| 73. `statement.confirmActual` | postedDate：D（手工schema）；set.baseAmount.minor：C≥1 | FileCommandAdapters.kt StatementAdapter.validate；StatementEntryDrafts.kt；CurrencyTransactions.kt |
| 74. `csvBatch.undo` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 75. `preferences.analysis` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 76. `receipt.items.save` | items[].unitPrice.minor/amount.minor、adjustments[].amount.minor、total.minor≥0 | FileCommandAdapters.kt → saveDirectReceiptItems → data/files/FileProtocols.kt receipt-items 校验 的 number/money |
| 77. `entryDraft.reclassify` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 78. `book.select` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 79. `preferences.reminders` | time：T；repaymentDays[]/deadlineDays[]/recurringDays[]：[0,365] | FollowupCommandAdapters.kt PreferenceAdapter.validate → ReminderPreferences.validate (domain/reminders/ReminderPlan.kt) |
| 80. `preferences.automaticRates` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 81. `preferences.location` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 82. `preferences.photoStorage` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 83. `preferences.scanAutoCapture` | enabled：必填 boolean；本机默认 false，全账本共用；应用前原值须匹配 | FollowupCommandAdapters.kt PreferenceAdapter → AppPreferences.applyCommandPreference；撤销沿用 RestorePreferenceAdapter |
| 84. `preferences.scanAutoProcess` | enabled：必填 boolean；本机默认 true，全账本共用；已有图片显式处理不受此开关影响 | FollowupCommandAdapters.kt PreferenceAdapter → AppPreferences.applyCommandPreference；撤销沿用 RestorePreferenceAdapter |
| 85. `preferences.haptics` | enabled：必填 boolean；本机默认 true，全账本共用；应用前原值须匹配 | FollowupCommandAdapters.kt PreferenceAdapter → AppPreferences.applyCommandPreference；撤销沿用 RestorePreferenceAdapter |
| 86. `attachment.attachSource` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 87. `entry.prepareDraft` | set.date/settlementDate：D；其余金额/信用卡条件提示复用 entry schema，不把不完整草稿变成正式入账校验；rateDate/toDate/feeDate 仍无硬格式限制 | FollowupCommandAdapters.kt 本机草稿 execute → entryDraft(allowIncomplete=true)；仅解析 draft.date/settlementDate |
| 88. `backup.export` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 89. `statement.exportOriginal` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |
| 90. `command.exportResult` | 无本次约束；保持现有输入校验 | 对应注册 adapter.validate/execute |

## 没有新增硬规则的字段

1. `financialRule.addVersion.rule.billing` 的结账日/付款日越界，`installment`、利息年天数、档位和 bonus 月日不完整，现有 `previewFinancialRule` 仍可保存；只有计算使用时检查 `issues/readiness`。因此以 C 声明 1–31、1–12、非负/正数等范围，没有改成本机解析就拒绝。
2. 记录、模板、周期的信用卡期数只对信用卡分期生效；记录的 `cardPaymentMonth` 只对有效 DEFERRED 支出/转账执行 `YearMonth.parse`。输入不能独立确定当前账户类型、旧记录合并后的付款方式，所以用 C 声明，保留本机业务判断。空值、非信用卡或非该付款方式的原有忽略行为不变。
3. `terms.paymentSettings[].fromPaymentDate` 在一般计划中仅作字符串排序/比较，不能普遍加日期 parse；只有 `financialPool.changePayment.input.setting.fromPaymentDate` 明确调用 `LocalDate.parse`，在那里通过手工 patch 使用 `dateSchema()`。`mergeFeeDate`、`feeDate`（余额池变更）、`adoptedFees[].date` 主要与已有付款日期做成员匹配，没有独立 parse，不新增硬格式。记录的 `feeDate` 仅在另付转账手续费时解析，以 C 声明。
4. `openingMinor`（账户）可以有正负；`loanLimitDeltaMinor` 可以是变动值；选择器金额上下界是相互关系；金额总和、余额上限、日期先后、非未来、列表成员范围等依赖业务数据或其他字段，不发明静态上界。小票商品单价、行额、调整额及总额的最小单位整数为非负（折扣方向由type表达），与原FileProtocols一致；不能给全局 Money 加正数限制。
5. 预留账户金额、周期本位币金额、借入 opening 模式费用、还款费用本位币值，以及普通记账金额，在不完整草稿/派生币种/未启用功能时可能被忽略；只在本机真正使用时的条件约束采用 C。正式执行仍走既有仓库校验。

## 完整方案 schema 与产物

`CommandRegistry.planSchema()` 使用 `CommandPlan`/`CommandStep` serializer 推导对象字段和 required（顶层 message/questions/steps 可省略），固定 format/version，UUID 使用与现有规范化比较相容的大小写十六进制模式，两个 digest 声明64位小写十六进制，steps 上限与 capability limits 共用500。严格对象均为 additionalProperties=false，与 commandJson.ignoreUnknownKeys=false 一致。

steps.items.oneOf 含90条命令分支，每个分支固定 command/command_version，id 共用本机正则，input 引用 `$defs/<command>`。`$defs` 包括 stepReference 及原封复用的90份 inputSchema。顶层 description 指向协议“引用与金额”“只看附件时的填写顺序”“确认与一致性”，说明前序/输出类型、ID唯一、附件身份与digest原样复制及手机预览要求。

catalogDigest 仍计算现有能力内容，不加入额外 schema 摘要。输入 schema 和说明变化会自然改变 catalogDigest。两处 command-plan.v1.schema.json 与两处 capabilities.valid.json 已在扫描阶段 4 通过 `-Dyohaku.updateCapabilities=true` 生成，并由 `CommandCapabilitiesTest` 核验全部 90 条分支、输入示例及副本一致性。

## 规格取舍及验证边界

- 采用共享领域层 `SchemaFormat/SchemaRange` 的 `@SerialInfo` 注解，避免领域输入反向依赖 data.command。生成和本机 schema 校验使用相同输出；手工日期、引用替换和条件字段通过同一 helper 覆盖。
- 条件约束使用上述 C 元数据，而非一律加硬 minimum/format，原因是严格遵守“不发明本机尚未强制的规则”。这些元数据不能替代手机业务校验。
- 按需求使用四位年日期/月 pattern；Java LocalDate/YearMonth 原本还能解析带符号的扩展年份，这个 pattern 对那部分输入更严格。普通四位年范围内不改变实际日期合法性判断。
- 本轮无UI变动；不运行Gradle、模拟器，也未提交。Kotlin PSI仅检查语法，不能确认serialization代码生成、类型检查、Room或设备流程通过。正常/错误方案样例不含受影响的非法日期/月份，无需手工改写。
- 最终生成文件一致性、88例校验、编译和设备附件流程由下列命令验收。先生成再跑其他测试，命令串行执行。

```sh
cd /home/hubery-fedora/Projects/ExpenseTracker/android
./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest --no-parallel --max-workers=1
./gradlew :app:testDebugUnitTest --no-parallel --max-workers=1
./gradlew :app:connectedDebugAndroidTest --no-parallel --max-workers=1 -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.CommandFlowTest#submitReceiveReviewCheckConfirmAndHistory,com.yohaku.expensetracker.CommandFlowTest#monthlyPlanSubmissionReviewHistoryAndAnswerAcrossLooks
```

助手往返工具的 `export` 需要显式 `assistant=export`，会重新生成隔离测试库/夹具；如需重新交给助手验收，沿用 android/README.md 的往返导出命令（含清理/导出路径说明），新增schema会自动出现在六份实际附件中。

## 修改文件清单

- `android/README.md`
- `android/app/src/androidTest/java/com/yohaku/expensetracker/CommandAssistantRoundTripTest.kt`
- `android/app/src/androidTest/java/com/yohaku/expensetracker/CommandFlowTest.kt`
- `android/app/src/main/assets/yohaku-command-protocol-v1.md`
- `android/app/src/main/java/com/yohaku/expensetracker/data/cloud/CloudRepository.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandModels.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandRegistry.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/command/CommandSchema.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/command/EntryCommandAdapters.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/command/FileCommandAdapters.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/command/FinanceCommandAdapters.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/command/FollowupCommandAdapters.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/command/LedgerCommandAdapters.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/command/PlanningCommandAdapters.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/command/ScheduleCommandAdapters.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/repository/BatchRevolvingArrangements.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/repository/CreditLimitRepository.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialArrangementReconciliation.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialArrangements.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPoolAdoption.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPoolArrangements.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialPoolPaymentChanges.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/data/repository/FinancialRepository.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/domain/finance/CardPaymentDates.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/domain/finance/FinancialMovements.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/domain/finance/FinancialRules.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/domain/finance/PaymentCalculation.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/domain/finance/PredictedSchedule.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/domain/model/SchemaConstraints.kt`
- `android/app/src/main/java/com/yohaku/expensetracker/domain/reminders/ReminderPlan.kt`
- `android/app/src/test/java/com/yohaku/expensetracker/data/command/CommandCapabilitiesTest.kt`
- `android/app/src/test/java/com/yohaku/expensetracker/data/command/CommandProtocolTest.kt`
- `docs/android-cloud-handoff-package/README.txt`
- `docs/android-cloud-handoff-package/formats/README.md`
- `docs/android-cloud-handoff-package/formats/yohaku-command-protocol-v1.md`
- `docs/formats/README.md`
- `docs/formats/yohaku-command-protocol-v1.md`
- `docs/implementation-spec.md`
- `docs/mcp-implementation-handoff-package/reference/README.md`
- `docs/plans/cloud-command-plan.md`
- `docs/plans/cloud-command-schema-audit.md`
- `docs/plans/cloud-command-stage-7.md`
- `docs/plans/cloud-command-stage-8.md`
