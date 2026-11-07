# 触感对应表 · 附录：逐个交互点

配合 [触感设计](haptics.md) 阅读。本表是实现前的设计对应；实现时按源码实际语义调整的条目见 [实现偏差](haptics.md#10-实现偏差以源码实际语义为准)，以那里为准。本表列出 `android/app/src/main` 中 1282 个交互调用点（路径前缀 `android/app/src/main/java/com/yohaku/expensetracker/`），行号以提交 `3524ad1` 为准。

- **按下**：手指触发时立即播放的触感；`none` 表示按下时不震。
- **结果**：异步操作真正完成时播放的触感，见 [结果层事件](haptics.md#5-结果层事件)。失败一律为 `Error`，审阅过时一律为 `Warning`，表中不再重复。
- 组件内置的触感（`YSelect`、`YDateFields`、`YToggle`、页签等）在调用点也列出，以便核对，但实现只需在组件中写一次。

## 记账、退款、记录详情（82）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| ENTRY-001 | `ui/features/NativeApp.kt:423` | 确认实际入账金额 | `onConfirm` | none | Done |  |
| ENTRY-002 | `ui/features/NativeApp.kt:441` | TransactionList | `onRecord` | none | — | 导航/打开/关闭 |
| ENTRY-003 | `ui/features/NativeApp.kt:447` | 编辑记录 | `onEdit` | none | — | 导航/打开/关闭 |
| ENTRY-004 | `ui/features/NativeApp.kt:448` | 记录退款 | `onRefund` | none | — | 导航/打开 |
| ENTRY-005 | `ui/features/NativeApp.kt:451` | 查看原消费 | `{onRecord(original)}` | none | — | 打开预览/查看 |
| ENTRY-006 | `ui/features/NativeApp.kt:460` | 移至回收站 | `onDelete` | none | — | 打开删除审阅 |
| ENTRY-007 | `ui/features/NativeApp.kt:466` | CategoryKindTabs | `{vm.switchEntryKind(s,it)}` | TabSwitch | — | 组件内置 |
| ENTRY-008 | `ui/features/NativeApp.kt:476` | 我的常用 · 一步选择 | `{id->onChange(d.copy(categoryId=id));onChoose()}` | Select | — |  |
| ENTRY-009 | `ui/features/NativeApp.kt:477` | 最近使用 · 直接选择 | `{id->onChange(d.copy(categoryId=id));onChoose()}` | Select | — |  |
| ENTRY-010 | `ui/features/NativeApp.kt:478` | YSearch | `{query-> search=query if(query.isNotBlank())expanded=(expand` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-011 | `ui/features/NativeApp.kt:487` | CategoryGroup | `{if(children.isEmpty()){onChange(d.copy(categoryId=c.id));on` | Select | — |  |
| ENTRY-012 | `ui/features/NativeApp.kt:487` | CategoryGroup | `{onChange(d.copy(categoryId=c.id));onChoose()}` | Select | — |  |
| ENTRY-013 | `ui/features/NativeApp.kt:487` | CategoryGroup | `{id->onChange(d.copy(categoryId=id));onChoose()}` | Select | — |  |
| ENTRY-014 | `ui/features/NativeApp.kt:487` | CategoryGroup | `{onCreate(c.id)}` | none | — | 导航/打开/关闭 |
| ENTRY-015 | `ui/features/NativeApp.kt:494` | YEmpty | `{onCreate(null)}` | none | — | 导航/打开/关闭 |
| ENTRY-016 | `ui/features/NativeApp.kt:495` | ＋ 新建分类 | `{onCreate(null)}` | none | — | 导航/打开/关闭 |
| ENTRY-017 | `ui/features/NativeApp.kt:513` | EntrySummary | `onCategory` | none | — | 导航/打开 |
| ENTRY-018 | `ui/features/NativeApp.kt:524` | 金额 | `{changeAccountAmount(it)}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-019 | `ui/features/NativeApp.kt:533` | EntryQuickAmount | `{changeAccountAmount(it)}` | Step | — | 按增量缩放强度 |
| ENTRY-020 | `ui/features/NativeApp.kt:534` | 查看 / 手动填写换算 | `onRate` | none | — | 打开预览/查看 |
| ENTRY-021 | `ui/features/NativeApp.kt:537` | 本位币统计金额 | `{onChange(d.copy(baseAmount=it,referenceBaseLinked=false))}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-022 | `ui/features/NativeApp.kt:543` | 到账账户 | `{id-> val next=eligible.first{it.id==id} onChange(if(a!=null` | Select | — | 组件内置：选中菜单项 |
| ENTRY-023 | `ui/features/NativeApp.kt:548` | 添加第一个账户 | `onAccount` | none | — | 导航/打开/关闭 |
| ENTRY-024 | `ui/features/NativeApp.kt:551` | 退出活动记录模式 | `onExitActivity` | none | — | 导航/打开 |
| ENTRY-025 | `ui/features/NativeApp.kt:552` | 活动 / 可选 | `{onChange(d.copy(activityId=it,reserveId=null,reserveAmount=` | Select | — | 组件内置：选中菜单项 |
| ENTRY-026 | `ui/features/NativeApp.kt:556` | 使用预留 / 可选 | `{onChange(d.copy(reserveId=it,reserveAmount=""))}` | Select | — | 组件内置：选中菜单项 |
| ENTRY-027 | `ui/features/NativeApp.kt:557` | 使用金额 / 留空按本次支出 | `{onChange(d.copy(reserveAmount=it))}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-028 | `ui/features/NativeApp.kt:559` | 本人付款 · 活动内互请 | `{onChange(d.copy(treatEnabled=it))}` | ToggleOn/ToggleOff | — | 组件内置 |
| ENTRY-029 | `ui/features/NativeApp.kt:565` | 名称 / 说明 | `{onChange(d.copy(merchant=it,merchantId=null,merchantManuall` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-030 | `ui/features/NativeApp.kt:567` | YDateFields | `{activeDate=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| ENTRY-031 | `ui/features/NativeApp.kt:567` | YDateFields | `{index,date-> onChange(if(index==0)d.copy(date=date,settleme` | Select | — | 组件内置：选日期=Select，换月=Step |
| ENTRY-032 | `ui/features/NativeApp.kt:570` | EntryNotes | `{onChange(d.copy(note=it))}` | none | — | 备注文本输入 |
| ENTRY-033 | `ui/features/NativeApp.kt:571` | HistoricalOpeningToggle | `{onChange(d.copy(historicalIncluded=it))}` | ToggleOn/ToggleOff | — | 组件内置 |
| ENTRY-034 | `ui/features/NativeApp.kt:577` | 关联已记流水，或新增一次 | `{onChange(d.copy(occurrenceLinkEventId=it))}` | Select | — | 组件内置：选中菜单项 |
| ENTRY-035 | `ui/features/NativeApp.kt:583` | EntrySaveArea | `{existingId?.let(onPreviewExisting)?:onSave()}` | none | Saved | 记账/修改/转账真正落库时（AppViewModel.entrySaved） |
| ENTRY-036 | `ui/features/NativeApp.kt:583` | EntrySaveArea | `onCancelEdit` | none | — | 导航/打开/关闭 |
| ENTRY-037 | `ui/features/NativeApp.kt:584` | 把这笔存为模板 | `onSaveTemplate` | none | — | 导航/打开 |
| ENTRY-038 | `ui/features/EntryCurrencyControls.kt:17` | 消费 / 收付原币 | `{selected-> val currency=Currency.valueOf(selected!!) val ac` | Select | — | 组件内置：选中菜单项 |
| ENTRY-039 | `ui/features/EntryCurrencyControls.kt:24` | 原币金额 | `{onChange(d.copy(amount=it,accountAmount=if(!d.settlementCon` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-040 | `ui/features/EntryCurrencyControls.kt:30` | 入账金额状态 | `{onChange(d.copy(settlementConfirmed=it=="confirmed"))}` | Select | — | 组件内置：选中菜单项 |
| ENTRY-041 | `ui/features/EntryLifecycleSheet.kt:26` | title | `{if(!busy)onClose()}` | none | — | 关闭/返回不加触感 |
| ENTRY-042 | `ui/features/EntryLifecycleSheet.kt:36` | 上一页 | `{eventPage--}` | Step | — |  |
| ENTRY-043 | `ui/features/EntryLifecycleSheet.kt:38` | 下一页 | `{eventPage++}` | Step | — |  |
| ENTRY-044 | `ui/features/EntryLifecycleSheet.kt:45` | 查看分类预算变化 | `{categories=!categories}` | none | — | 打开预览/查看 |
| ENTRY-045 | `ui/features/EntryLifecycleSheet.kt:51` | 确认移至回收站 | `{ var saved=false vm.execute(onSuccess={if(saved)onApplied()` | none | Removed / Restored | 移入回收站/永久删除/清空=Removed；恢复原记录=Restored |
| ENTRY-046 | `ui/features/EntryMerchantInput.kt:34` | 商户 / 买了什么 | `{onChange(s.merchantInput(d,it))}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-047 | `ui/features/EntryMerchantInput.kt:35` | 商户一览 | `{query="";open=true}` | none | — | 导航/打开/关闭 |
| ENTRY-048 | `ui/features/EntryMerchantInput.kt:38` | LocatedMerchantChip | `{onChange(d.copy(merchant="",merchantId=null,merchantManuall` | none | — | 导航/打开/关闭 |
| ENTRY-049 | `ui/features/EntryMerchantInput.kt:40` | LocatedMerchantChip | `{choose(m)}` | Select | — |  |
| ENTRY-050 | `ui/features/EntryMerchantInput.kt:43` | 商户一览 | `{open=false}` | none | — | 关闭/返回不加触感 |
| ENTRY-051 | `ui/features/EntryMerchantInput.kt:44` | YSearch | `{query=it}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-052 | `ui/features/EntryMerchantInput.kt:47` | LocatedMerchantChip | `{choose(m)}` | Select | — |  |
| ENTRY-053 | `ui/features/EntryNotes.kt:26` | clickable | `{expanded=!expanded}` | none | — | 展开/收起 |
| ENTRY-054 | `ui/features/EntryNotes.kt:31` | 备注 | `onNote` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-055 | `ui/features/EntryQuickAmount.kt:31` | clickable | `{onAmount((current!!+increment).toString())}` | Step | — |  |
| ENTRY-056 | `ui/features/EntrySaveArea.kt:50` | 取消 | `onCancel` | none | — | 导航/打开/关闭 |
| ENTRY-057 | `ui/features/EntrySaveArea.kt:51` | label | `onSave` | none | Saved | 记账/修改/转账真正落库时（AppViewModel.entrySaved） |
| ENTRY-058 | `ui/features/EntrySaveArea.kt:53` | label | `onSave` | none | Saved | 记账/修改/转账真正落库时（AppViewModel.entrySaved） |
| ENTRY-059 | `ui/features/EntrySummary.kt:40` | clickable | `{focus.clearFocus();keyboard?.hide();onCategory()}` | none | — | 导航/打开 |
| ENTRY-060 | `ui/features/CardPaymentFields.kt:23` | 平台当前付款方式 | `{onChange(d.copy(cardPaymentChoice=it,cardPaymentRecheck=fal` | Select | — | 组件内置：选中菜单项 |
| ENTRY-061 | `ui/features/CardPaymentFields.kt:24` | 明确重新核对本次自动方式 | `{onChange(d.copy(cardPaymentRecheck=it))}` | ToggleOn/ToggleOff | — | 组件内置 |
| ENTRY-062 | `ui/features/CardPaymentFields.kt:25` | 分期次数 | `{onChange(d.copy(cardPaymentPeriods=it))}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-063 | `ui/features/CardPaymentFields.kt:27` | 付款月（YYYY-MM） | `{onChange(d.copy(cardPaymentMonth=it))}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-064 | `ui/features/CardPaymentFields.kt:28` | 手续费 / 未知留空，无费用填 0 | `{onChange(d.copy(cardPaymentFee=it))}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-065 | `ui/features/HistoricalOpeningToggle.kt:25` | value | `onChange` | ToggleOn/ToggleOff | — | 组件内置 |
| ENTRY-066 | `ui/features/NegativeBalanceSheet.kt:12` | 账户余额将变为负数 | `onDismiss` | none | — | 关闭/返回不加触感 |
| ENTRY-067 | `ui/features/NegativeBalanceSheet.kt:18` | 仍然保存 | `onSave` | none | Saved | 记账/修改/转账真正落库时（AppViewModel.entrySaved） |
| ENTRY-068 | `ui/features/NegativeBalanceSheet.kt:19` | 返回修改 | `onDismiss` | none | — | 导航/打开/关闭 |
| ENTRY-069 | `ui/features/RefundForm.kt:16` | 返回 | `onCancel` | none | — | 导航/打开/关闭 |
| ENTRY-070 | `ui/features/RefundForm.kt:20` | 商户退回原币金额 | `{onChange(d.copy(amount=it))}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-071 | `ui/features/RefundForm.kt:21` | 实际退款到账账户 | `{id-> val next=s.accounts.first{it.id==id} onChange(d.copy(a` | Select | — | 组件内置：选中菜单项 |
| ENTRY-072 | `ui/features/RefundForm.kt:25` | 账户实际退款 / 待到账可填预计 | `{onChange(d.copy(accountAmount=it))}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-073 | `ui/features/RefundForm.kt:26` | 原消费月冲减金额 | `{onChange(d.copy(baseAmount=it))}` | none | — | 系统输入法负责逐键反馈 |
| ENTRY-074 | `ui/features/RefundForm.kt:31` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| ENTRY-075 | `ui/features/RefundForm.kt:31` | YDateFields | `{_,date->onChange(d.copy(date=date,settlementDate=date,rateD` | Select | — | 组件内置：选日期=Select，换月=Step |
| ENTRY-076 | `ui/features/RefundForm.kt:32` | 退款状态 | `{onChange(d.copy(settlementConfirmed=it=="confirmed"))}` | Select | — | 组件内置：选中菜单项 |
| ENTRY-077 | `ui/features/RefundForm.kt:36` | EntryNotes | `{onChange(d.copy(note=it))}` | none | — | 备注文本输入 |
| ENTRY-078 | `ui/features/RefundForm.kt:38` | 预览退款变化 | `onSave` | none | — | 打开预览/查看 |
| ENTRY-079 | `ui/features/RefundForm.kt:39` | 取消修改并返回 | `onCancel` | none | — | 导航/打开/关闭 |
| ENTRY-080 | `ui/features/RefundForm.kt:46` | 退款变化预览 | `onBack` | none | — | 关闭/返回不加触感 |
| ENTRY-081 | `ui/features/RefundForm.kt:70` | 确认并保存 | `onConfirm` | none | Saved | 记账/修改/转账真正落库时（AppViewModel.entrySaved） |
| ENTRY-082 | `ui/features/RefundForm.kt:71` | 返回表单继续编辑 | `onBack` | none | — | 导航/打开/关闭 |

## 转账与汇率（41）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| TRANSFER-RATES-001 | `ui/features/TransferForm.kt:30` | CategoryKindTabs | `{vm.switchEntryKind(s,it)}` | TabSwitch | — | 组件内置 |
| TRANSFER-RATES-002 | `ui/features/TransferForm.kt:33` | 转出账户 | `{id-> val account=eligible.first{it.id==id};val currencyChan` | Select | — | 组件内置：选中菜单项 |
| TRANSFER-RATES-003 | `ui/features/TransferForm.kt:41` | 转入账户 | `{id-> val account=eligible.first{it.id==id};val currencyChan` | Select | — | 组件内置：选中菜单项 |
| TRANSFER-RATES-004 | `ui/features/TransferForm.kt:46` | 添加资金账户 | `onAccount` | none | — | 导航/打开/关闭 |
| TRANSFER-RATES-005 | `ui/features/TransferForm.kt:49` | 实际扣款总额 | `{if(knownPair)vm.changeTransferAmount(s.book.id,d.copy(amoun` | none | — | 系统输入法负责逐键反馈 |
| TRANSFER-RATES-006 | `ui/features/TransferForm.kt:51` | 实际到账净额 | `{if(knownPair)vm.changeTransferAmount(s.book.id,d.copy(toAmo` | none | — | 系统输入法负责逐键反馈 |
| TRANSFER-RATES-007 | `ui/features/TransferForm.kt:64` | 手填汇率 | `{change(d.copy(manualRate=it,rateSource="manual",rateDate=d.` | none | — | 系统输入法负责逐键反馈 |
| TRANSFER-RATES-008 | `ui/features/TransferForm.kt:71` | 估算到账额 | `{estimate("TO")}` | Select | — | 填入估算/参考值 |
| TRANSFER-RATES-009 | `ui/features/TransferForm.kt:71` | 反推转出额 | `{estimate("FROM")}` | Select | — | 填入估算/参考值 |
| TRANSFER-RATES-010 | `ui/features/TransferForm.kt:74` | 手续费 · 避免重复扣款 | `{feeOpen=!feeOpen}` | none | — | 展开/收起 |
| TRANSFER-RATES-011 | `ui/features/TransferForm.kt:79` | 明确收取的手续费 / 可留空 | `{change(d.copy(feeAmount=it))}` | none | — | 系统输入法负责逐键反馈 |
| TRANSFER-RATES-012 | `ui/features/TransferForm.kt:80` | 费用币种 | `{change(d.copy(feeCurrency=Currency.valueOf(it!!),feeAccount` | Select | — | 组件内置：选中菜单项 |
| TRANSFER-RATES-013 | `ui/features/TransferForm.kt:82` | 手续费如何扣收 | `{owner=null;change(d.copy(feePosition=TransferFeePosition.va` | Select | — | 组件内置：选中菜单项 |
| TRANSFER-RATES-014 | `ui/features/TransferForm.kt:83` | 另扣手续费的账户 | `{change(d.copy(feeAccountId=it,feeCurrency=preservedFeeCurre` | Select | — | 组件内置：选中菜单项 |
| TRANSFER-RATES-015 | `ui/features/TransferForm.kt:85` | 费用在账户中的金额 | `{change(d.copy(feeAccountAmount=it))}` | none | — | 系统输入法负责逐键反馈 |
| TRANSFER-RATES-016 | `ui/features/TransferForm.kt:86` | 手续费本位币统计金额 | `{change(d.copy(feeBaseAmount=it))}` | none | — | 系统输入法负责逐键反馈 |
| TRANSFER-RATES-017 | `ui/features/TransferForm.kt:91` | 资金状态 | `{change(d.copy(fromConfirmed=it in listOf("confirmed","inTra` | Select | — | 组件内置：选中菜单项 |
| TRANSFER-RATES-018 | `ui/features/TransferForm.kt:97` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| TRANSFER-RATES-019 | `ui/features/TransferForm.kt:97` | YDateFields | `{index,value->change(when{index==0->d.copy(date=value);index` | Select | — | 组件内置：选日期=Select，换月=Step |
| TRANSFER-RATES-020 | `ui/features/TransferForm.kt:98` | 手续费已经实际扣收 | `{change(d.copy(feeConfirmed=it))}` | ToggleOn/ToggleOff | — | 组件内置 |
| TRANSFER-RATES-021 | `ui/features/TransferForm.kt:99` | 名称 / 说明 | `{change(d.copy(merchant=it))}` | none | — | 系统输入法负责逐键反馈 |
| TRANSFER-RATES-022 | `ui/features/TransferForm.kt:100` | EntryNotes | `{change(d.copy(note=it))}` | none | — | 备注文本输入 |
| TRANSFER-RATES-023 | `ui/features/TransferForm.kt:104` | 保存这笔转账 | `{vm.saveEntry(s.book.id)}` | none | Saved | 记账/修改/转账真正落库时（AppViewModel.entrySaved） |
| TRANSFER-RATES-024 | `ui/features/TransferForm.kt:105` | 取消修改并返回 | `onCancel` | none | — | 导航/打开/关闭 |
| TRANSFER-RATES-025 | `ui/features/TransferPair.kt:67` | clickable | `onClick` | Compose | — | 从账户直接开始转账 |
| TRANSFER-RATES-026 | `ui/features/TransferPair.kt:101` | clickable | `onClick` | Select | — | 填入估算/参考值 |
| TRANSFER-RATES-027 | `ui/features/ManualRateEditor.kt:25` | 换算资料 · 保留历史金额 | `onClose` | none | — | 关闭/返回不加触感 |
| TRANSFER-RATES-028 | `ui/features/ManualRateEditor.kt:30` | 手动汇率 / 仅预填预计金额 | `{rate=it}` | none | — | 系统输入法负责逐键反馈 |
| TRANSFER-RATES-029 | `ui/features/ManualRateEditor.kt:31` | 该笔本位币统计金额 | `{base=it}` | none | — | 系统输入法负责逐键反馈 |
| TRANSFER-RATES-030 | `ui/features/ManualRateEditor.kt:32` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| TRANSFER-RATES-031 | `ui/features/ManualRateEditor.kt:32` | YDateFields | `{_,value->date=value}` | Select | — | 组件内置：选日期=Select，换月=Step |
| TRANSFER-RATES-032 | `ui/features/ManualRateEditor.kt:33` | 用此汇率重新估算入账 | `{estimate=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| TRANSFER-RATES-033 | `ui/features/ManualRateEditor.kt:35` | 保存换算资料，返回记账 | `{ var changed=d vm.execute(onSuccess={vm.changeEntry(s.book.` | none | Done |  |
| TRANSFER-RATES-034 | `ui/features/ReferenceRatePanel.kt:44` | 刷新参考汇率 | `{scope.launch{load(true)}}` | none | — | 刷新成功不震；失败走 Error |
| TRANSFER-RATES-035 | `ui/features/ReferenceRatePanel.kt:45` | 按此参考值重新估算 | `{ try{onUse(quote!!);error=null}catch(e:IllegalArgumentExcep` | Select | — | 填入估算/参考值 |
| TRANSFER-RATES-036 | `ui/features/ReferenceRatePanel.kt:70` | 自动获取参考汇率 | `{v->scope.launch{service.store.setAutomaticReferenceRates(v)` | ToggleOn/ToggleOff | — | 组件内置 |
| TRANSFER-RATES-037 | `ui/features/ReferenceRatePanel.kt:71` | 从币种 | `{from=it;if(to==it)to=Currency.entries.first{c->c.name!=it}.` | Select | — | 组件内置：选中菜单项 |
| TRANSFER-RATES-038 | `ui/features/ReferenceRatePanel.kt:72` | 到币种 | `{to=it}` | Select | — | 组件内置：选中菜单项 |
| TRANSFER-RATES-039 | `ui/features/ReferenceRatePanel.kt:73` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| TRANSFER-RATES-040 | `ui/features/ReferenceRatePanel.kt:73` | YDateFields | `{_,v->date=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| TRANSFER-RATES-041 | `ui/features/ReferenceAssetValuation.kt:45` | 刷新估值汇率 | `{scope.launch{load(true)}}` | none | — | 刷新成功不震；失败走 Error |

## 账户、账本与首次设置（68）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| ACCOUNTS-BOOKS-SETUP-001 | `ui/features/NativeApp.kt:656` | 添加账户与期初信息 | `onClose` | none | — | 关闭/返回不加触感 |
| ACCOUNTS-BOOKS-SETUP-002 | `ui/features/NativeApp.kt:659` | 账户类型 | `{type=it}` | Select | — | 组件内置：选中菜单项 |
| ACCOUNTS-BOOKS-SETUP-003 | `ui/features/NativeApp.kt:661` | 账户名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| ACCOUNTS-BOOKS-SETUP-004 | `ui/features/NativeApp.kt:662` | 账户币种 | `{currency=it}` | Select | — | 组件内置：选中菜单项 |
| ACCOUNTS-BOOKS-SETUP-005 | `ui/features/NativeApp.kt:664` | 期初欠款 | `{opening=it}` | none | — | 系统输入法负责逐键反馈 |
| ACCOUNTS-BOOKS-SETUP-006 | `ui/features/NativeApp.kt:665` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| ACCOUNTS-BOOKS-SETUP-007 | `ui/features/NativeApp.kt:665` | YDateFields | `{_,v->date=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| ACCOUNTS-BOOKS-SETUP-008 | `ui/features/NativeApp.kt:670` | 保存账户信息 | `{ if(saving)return@YButton saving=true val submittedName=nam` | none | Done |  |
| ACCOUNTS-BOOKS-SETUP-009 | `ui/features/NativeApp.kt:692` | 账本与月预算变化 | `{review=null}` | none | — | 关闭/返回不加触感 |
| ACCOUNTS-BOOKS-SETUP-010 | `ui/features/NativeApp.kt:697` | 确认保存账本与预算 | `{ if(saving)return@YButton saving=true var saved=false vm.ex` | none | Done |  |
| ACCOUNTS-BOOKS-SETUP-011 | `ui/features/NativeApp.kt:706` | 返回继续编辑 | `{review=null}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-012 | `ui/features/NativeApp.kt:711` | 创建独立账本 | `onClose` | none | — | 关闭/返回不加触感 |
| ACCOUNTS-BOOKS-SETUP-013 | `ui/features/NativeApp.kt:712` | 账本名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| ACCOUNTS-BOOKS-SETUP-014 | `ui/features/NativeApp.kt:713` | 统计本位币 | `{currency=it}` | Select | — | 组件内置：选中菜单项 |
| ACCOUNTS-BOOKS-SETUP-015 | `ui/features/NativeApp.kt:717` | 月预算 / 可稍后设置 | `{budget=it}` | none | — | 系统输入法负责逐键反馈 |
| ACCOUNTS-BOOKS-SETUP-016 | `ui/features/NativeApp.kt:720` | 保存账本 | `{ if(saving)return@YButton saving=true val submittedName=nam` | none | Done |  |
| ACCOUNTS-BOOKS-SETUP-017 | `ui/features/NativeApp.kt:765` | BackHandler | `{if(accountSheet)accountSheet=false else move(step-1)}` | none | — | 关闭/返回不加触感 |
| ACCOUNTS-BOOKS-SETUP-018 | `ui/features/NativeApp.kt:768` | YAppHeader | `{focus.clearFocus();keyboard?.hide();settingsSheet=true}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-019 | `ui/features/NativeApp.kt:774` | 从备份恢复 | `{vm.navigate("backup")}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-020 | `ui/features/NativeApp.kt:775` | 开始设置我的账本 | `{move(1)}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-021 | `ui/features/NativeApp.kt:782` | 账本名称 | `{name=it;if(s==null)persistFirst()}` | none | — | 系统输入法负责逐键反馈 |
| ACCOUNTS-BOOKS-SETUP-022 | `ui/features/NativeApp.kt:783` | 统计本位币 | `{currency=it;if(s==null)persistFirst()}` | Select | — | 组件内置：选中菜单项 |
| ACCOUNTS-BOOKS-SETUP-023 | `ui/features/NativeApp.kt:784` | 月预算 / 可稍后设置 | `{budget=it;if(s==null)persistFirst()}` | none | — | 系统输入法负责逐键反馈 |
| ACCOUNTS-BOOKS-SETUP-024 | `ui/features/NativeApp.kt:785` | YDateFields | `{dateOwner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| ACCOUNTS-BOOKS-SETUP-025 | `ui/features/NativeApp.kt:785` | YDateFields | `{_,v->date=v;if(s==null)persistFirst()}` | Select | — | 组件内置：选日期=Select，换月=Step |
| ACCOUNTS-BOOKS-SETUP-026 | `ui/features/NativeApp.kt:787` | 下一步：资金账户 | `{ if(creating)return@YButton creating=true val submittedName` | none | Done | 创建账本 |
| ACCOUNTS-BOOKS-SETUP-027 | `ui/features/NativeApp.kt:808` | SetupAccountCard | `{originalAccountId=a.id;accountSheet=true}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-028 | `ui/features/NativeApp.kt:810` | ＋ 添加已有信用卡 / 借款 | `{originalAccountId=null;accountSheet=true}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-029 | `ui/features/NativeApp.kt:813` | 先跳过，稍后添加 | `{move(step+1)}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-030 | `ui/features/NativeApp.kt:819` | 完成，进入我的空白账本 | `{vm.updateBook(it.book.copy(initialized=true))}` | none | Done |  |
| ACCOUNTS-BOOKS-SETUP-031 | `ui/features/NativeApp.kt:829` | 设置 | `{settingsSheet=false}` | none | — | 关闭/返回不加触感 |
| ACCOUNTS-BOOKS-SETUP-032 | `ui/features/NativeApp.kt:830` |  ✓ | `{persistFirst(id,language);settingsSheet=false}` | Select | — |  |
| ACCOUNTS-BOOKS-SETUP-033 | `ui/features/NativeApp.kt:831` | 备份与恢复 | `{settingsSheet=false;vm.navigate("backup")}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-034 | `ui/features/NativeApp.kt:832` | 切换为日语 | `{persistFirst(t.id,if(language=="zh")"ja" else "zh")}` | Select | — |  |
| ACCOUNTS-BOOKS-SETUP-035 | `ui/features/NativeApp.kt:844` | AccountTransferShortcut | `onTransfer` | Compose | — | 从账户直接开始转账 |
| ACCOUNTS-BOOKS-SETUP-036 | `ui/features/NativeApp.kt:845` | 账单、计划与金融规则 | `onFinancial` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-037 | `ui/features/NativeApp.kt:846` | 核对未来资金安排 | `onRisk` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-038 | `ui/features/NativeApp.kt:848` | 编辑账户 | `onEdit` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-039 | `ui/features/NativeApp.kt:856` | 账户状态与停用 | `manage` | none | — | 导航/打开 |
| ACCOUNTS-BOOKS-SETUP-040 | `ui/features/AccountsScreen.kt:65` | YEmpty | `onAdd` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-041 | `ui/features/AccountsScreen.kt:69` | ＋ 添加账户 | `onAdd` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-042 | `ui/features/AccountsScreen.kt:95` | clickable | `onClick` | none | — | 导航/打开 |
| ACCOUNTS-BOOKS-SETUP-043 | `ui/features/AccountsScreen.kt:137` | clickable | `onClick` | none | — | 导航/打开 |
| ACCOUNTS-BOOKS-SETUP-044 | `ui/features/AccountManagementSheet.kt:27` | title | `{if(!busy)onClose()}` | none | — | 关闭/返回不加触感 |
| ACCOUNTS-BOOKS-SETUP-045 | `ui/features/AccountManagementSheet.kt:48` | label | `{ var saved=false vm.execute(onSuccess={if(saved){onClose();` | none | Removed / Done | 删除账户=Removed；停用/恢复=Done |
| ACCOUNTS-BOOKS-SETUP-046 | `ui/features/AccountManagementSheet.kt:56` | 返回状态管理 | `{load(toggle)}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-047 | `ui/features/AccountManagementSheet.kt:57` | 查看删除资格与影响 | `{load(AccountOperation.DELETE)}` | none | — | 打开删除审阅 |
| ACCOUNTS-BOOKS-SETUP-048 | `ui/features/BooksScreen.kt:28` | ＋ 创建独立账本 | `onCreate` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-049 | `ui/features/BooksScreen.kt:31` | 归档与整本回收 | `onManage` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-050 | `ui/features/BooksScreen.kt:34` | clickable | `{onEdit(b.book.id)}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-051 | `ui/features/BooksScreen.kt:45` | selected | `onClick` | Select | — |  |
| ACCOUNTS-BOOKS-SETUP-052 | `ui/features/BookManagementScreen.kt:29` | 查看正常与归档账本 | `{trash=!trash}` | none | — | 打开预览/查看 |
| ACCOUNTS-BOOKS-SETUP-053 | `ui/features/BookManagementScreen.kt:43` | 恢复账本 | `{vm.previewBookOperation(book.id,BookOperation.RESTORE)}` | none | — | 导航/打开 |
| ACCOUNTS-BOOKS-SETUP-054 | `ui/features/BookManagementScreen.kt:44` | 永久删除 | `{vm.previewBookOperation(book.id,BookOperation.PURGE)}` | none | — | 打开删除审阅 |
| ACCOUNTS-BOOKS-SETUP-055 | `ui/features/BookManagementScreen.kt:49` | 取消归档 | `{vm.previewBookOperation(book.id,if(book.archived)BookOperat` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-056 | `ui/features/BookManagementScreen.kt:50` | 查看资料 | `{vm.selectBook(book.id)}` | none | — | 打开预览/查看 |
| ACCOUNTS-BOOKS-SETUP-057 | `ui/features/BookManagementScreen.kt:51` | 移至回收站 | `{vm.previewBookOperation(book.id,BookOperation.TRASH)}` | none | — | 打开删除审阅 |
| ACCOUNTS-BOOKS-SETUP-058 | `ui/features/BookManagementScreen.kt:56` | 清空整本回收站 | `{vm.execute{emptyReview=vm.repository.previewEmptyBookTrash(` | none | — | 仅生成清空预览 |
| ACCOUNTS-BOOKS-SETUP-059 | `ui/features/BookManagementScreen.kt:57` | ＋ 创建独立账本 | `onCreate` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-060 | `ui/features/BookManagementScreen.kt:58` | 永久清空整本回收站 | `{if(!busy)emptyReview=null}` | none | — | 关闭/返回不加触感 |
| ACCOUNTS-BOOKS-SETUP-061 | `ui/features/BookManagementScreen.kt:62` | 确认永久清空 | `{vm.execute { if(vm.repository.commitEmptyBookTrash(reviews)` | none | Removed |  |
| ACCOUNTS-BOOKS-SETUP-062 | `ui/features/BookManagementScreen.kt:86` | title | `{if(!busy)vm.bookOperationReview.value=null}` | none | — | 关闭/返回不加触感 |
| ACCOUNTS-BOOKS-SETUP-063 | `ui/features/BookManagementScreen.kt:97` | 确认 | `vm::commitBookOperation` | none | Removed / Restored / Done | 按 BookOperation：移入回收站/永久删除=Removed；恢复/取消归档=Restored；归档=Done |
| ACCOUNTS-BOOKS-SETUP-064 | `ui/features/BookManagementScreen.kt:111` | 备份与恢复 | `{vm.navigate("backup")}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-065 | `ui/features/SetupCards.kt:36` | clickable | `onEdit` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-066 | `ui/features/SetupCards.kt:67` | SetupAccountCard | `{onEditAccount(a)}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-067 | `ui/features/SetupCards.kt:87` | clickable | `{focus.clearFocus();keyboard?.hide();onBack()}` | none | — | 导航/打开/关闭 |
| ACCOUNTS-BOOKS-SETUP-068 | `ui/features/SetupSettingOption.kt:18` | clickable | `onClick` | Select | — |  |

## 分类（42）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| CATEGORIES-001 | `ui/features/NativeApp.kt:595` | CategoryManageGroup | `{onEdit(root)}` | none | — | 导航/打开/关闭 |
| CATEGORIES-002 | `ui/features/NativeApp.kt:595` | CategoryManageGroup | `{id->onEdit(s.categories.first{it.id==id})}` | none | — | 导航/打开/关闭 |
| CATEGORIES-003 | `ui/features/NativeApp.kt:595` | CategoryManageGroup | `{onCreate(root.id,kind)}` | none | — | 导航/打开/关闭 |
| CATEGORIES-004 | `ui/features/NativeApp.kt:598` | ＋ 添加一级分类 | `{onCreate(null,kind)}` | none | — | 导航/打开/关闭 |
| CATEGORIES-005 | `ui/features/NativeApp.kt:619` | 创建一级 / 二级分类 | `onClose` | none | — | 关闭/返回不加触感 |
| CATEGORIES-006 | `ui/features/NativeApp.kt:624` | 分类名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| CATEGORIES-007 | `ui/features/NativeApp.kt:625` | CategoryIconField | `{picking=true}` | none | — | 导航/打开/关闭 |
| CATEGORIES-008 | `ui/features/NativeApp.kt:625` | CategoryIconField | `{icon=it;picked=true}` | none | — | 导航/打开/关闭 |
| CATEGORIES-009 | `ui/features/NativeApp.kt:627` | 分类层级 | `{parent=it}` | Select | — | 组件内置：选中菜单项 |
| CATEGORIES-010 | `ui/features/NativeApp.kt:631` | 归档、调整归属或删除 | `{managing=true}` | none | — | 打开删除审阅 |
| CATEGORIES-011 | `ui/features/NativeApp.kt:633` | 创建并使用 | `{ if(saving)return@YButton saving=true val submittedName=nam` | none | Done |  |
| CATEGORIES-012 | `ui/features/CategoryArchive.kt:48` | clickable | `{open=!open}` | none | — | 展开/收起 |
| CATEGORIES-013 | `ui/features/CategoryArchive.kt:81` | clickable | `onOpen` | none | — | 导航/打开/关闭 |
| CATEGORIES-014 | `ui/features/CategoryArchive.kt:95` | clickable | `onRestore` | none | Restored |  |
| CATEGORIES-015 | `ui/features/CategoryArchive.kt:101` | clickable | `onOpen` | none | — | 导航/打开/关闭 |
| CATEGORIES-016 | `ui/features/CategoryComponents.kt:30` | clickable | `{onChoose(id)}` | Select | — |  |
| CATEGORIES-017 | `ui/features/CategoryComponents.kt:38` | clickable | `onClick` | Select | — |  |
| CATEGORIES-018 | `ui/features/CategoryComponents.kt:59` | clickable | `onToggle` | none | — | 展开/添加入口 |
| CATEGORIES-019 | `ui/features/CategoryComponents.kt:79` | clickable | `onParent` | Select | — |  |
| CATEGORIES-020 | `ui/features/CategoryComponents.kt:105` | clickable | `onParent` | Select | — |  |
| CATEGORIES-021 | `ui/features/CategoryComponents.kt:118` | clickable | `{onChild(id)}` | Select | — |  |
| CATEGORIES-022 | `ui/features/CategoryComponents.kt:140` | clickable | `onClick` | none | — | 展开/添加入口 |
| CATEGORIES-023 | `ui/features/CategoryFavorites.kt:28` | clickable | `{expanded=!expanded}` | none | — | 展开/收起 |
| CATEGORIES-024 | `ui/features/CategoryFavorites.kt:39` | clickable | `{onFavorite(c)}` | ToggleOn/ToggleOff | — | 收藏/取消收藏 |
| CATEGORIES-025 | `ui/features/CategoryManagementSheet.kt:33` | 分类状态与归属 | `{if(!busy){if(review!=null)review=null else onClose()}}` | none | — | 关闭/返回不加触感 |
| CATEGORIES-026 | `ui/features/CategoryManagementSheet.kt:37` | 操作 | `{operation=it;target=null}` | Select | — | 组件内置：选中菜单项 |
| CATEGORIES-027 | `ui/features/CategoryManagementSheet.kt:40` | 迁往分类 / 父级 | `{target=it}` | Select | — | 组件内置：选中菜单项 |
| CATEGORIES-028 | `ui/features/CategoryManagementSheet.kt:43` | 分类预算处理 | `{policy=it}` | Select | — | 组件内置：选中菜单项 |
| CATEGORIES-029 | `ui/features/CategoryManagementSheet.kt:50` | 预览调整影响 | `{vm.execute{review=vm.repository.previewCategoryOperation(s.` | none | — | 打开预览/查看 |
| CATEGORIES-030 | `ui/features/CategoryManagementSheet.kt:70` | 确认分类调整 | `{ var saved=false vm.execute(onSuccess={if(saved)onClose()})` | none | Removed / Restored / Done | 删除=Removed；取消归档=Restored；归档/调整归属=Done |
| CATEGORIES-031 | `ui/features/CategoryManagementSheet.kt:78` | 返回修改 | `{review=null}` | none | — | 导航/打开/关闭 |
| CATEGORIES-032 | `ui/features/CategoryKindTabs.kt:33` | active | `{onChoose(kind)}` | Select | — |  |
| CATEGORIES-033 | `ui/features/CategoryIconPicker.kt:40` | clickable | `onOpen` | none | — | 导航/打开/关闭 |
| CATEGORIES-034 | `ui/features/CategoryIconPicker.kt:53` | clickable | `{onPick(id)}` | Select | — |  |
| CATEGORIES-035 | `ui/features/CategoryIconPicker.kt:71` | 选择分类图标 | `onBack` | none | — | 关闭/返回不加触感 |
| CATEGORIES-036 | `ui/features/CategoryIconPicker.kt:75` | 返回编辑 | `onBack` | none | — | 导航/打开/关闭 |
| CATEGORIES-037 | `ui/features/CategoryIconPicker.kt:76` | 沿用一级「$parentName」 | `onInherit` | Select | — |  |
| CATEGORIES-038 | `ui/features/CategoryIconPicker.kt:78` | YSearch | `{query=it}` | none | — | 系统输入法负责逐键反馈 |
| CATEGORIES-039 | `ui/features/CategoryIconPicker.kt:100` | 使用这个图标 | `{onUse(pending)}` | Select | — |  |
| CATEGORIES-040 | `ui/features/CategoryIconPicker.kt:152` | selected | `{onSelect(id)}` | Select | — |  |
| CATEGORIES-041 | `ui/features/CategoryIconPicker.kt:183` | CategoryIconCell | `{onPick(id)}` | Select | — |  |
| CATEGORIES-042 | `ui/features/CategoryIconPicker.kt:198` | selected | `onClick` | Select | — |  |

## 流水与日历（43）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| LEDGER-HOME-001 | `ui/features/LedgerScreen.kt:40` | clickable | `onClick` | none | — | 导航/打开 |
| LEDGER-HOME-002 | `ui/features/LedgerScreen.kt:96` | LedgerEntryActions | `onStart` | Compose | — | 开始记一笔 |
| LEDGER-HOME-003 | `ui/features/LedgerScreen.kt:97` | LedgerViewTabs | `{calendar=it;dateOwner=null}` | TabSwitch | — | 组件内置 |
| LEDGER-HOME-004 | `ui/features/LedgerScreen.kt:99` | 筛选账户与分类 | `{filtersOpen=!filtersOpen}` | none | — | 展开/收起 |
| LEDGER-HOME-005 | `ui/features/LedgerScreen.kt:100` | 账户 | `{account=it}` | Select | — | 组件内置：选中菜单项 |
| LEDGER-HOME-006 | `ui/features/LedgerScreen.kt:101` | 分类 | `{category=it}` | Select | — | 组件内置：选中菜单项 |
| LEDGER-HOME-007 | `ui/features/LedgerScreen.kt:106` | YSearch | `{query=it}` | none | — | 系统输入法负责逐键反馈 |
| LEDGER-HOME-008 | `ui/features/LedgerScreen.kt:107` | 类型 | `{type=it}` | Select | — | 组件内置：选中菜单项 |
| LEDGER-HOME-009 | `ui/features/LedgerScreen.kt:109` | LedgerFilterDisclosure | `{filtersOpen=!filtersOpen}` | none | — | 展开/收起 |
| LEDGER-HOME-010 | `ui/features/LedgerScreen.kt:110` | 账户 | `{account=it}` | Select | — | 组件内置：选中菜单项 |
| LEDGER-HOME-011 | `ui/features/LedgerScreen.kt:111` | 分类 | `{category=it}` | Select | — | 组件内置：选中菜单项 |
| LEDGER-HOME-012 | `ui/features/LedgerScreen.kt:112` | 日期范围 | `{period=it;dateOwner=null}` | Select | — | 组件内置：选中菜单项 |
| LEDGER-HOME-013 | `ui/features/LedgerScreen.kt:114` | YDateFields | `{dateOwner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| LEDGER-HOME-014 | `ui/features/LedgerScreen.kt:114` | YDateFields | `{i,value->if(i==0)from=value else to=value}` | Select | — | 组件内置：选日期=Select，换月=Step |
| LEDGER-HOME-015 | `ui/features/LedgerScreen.kt:134` | EventRow | `{onSelect(it)}` | none | — | 导航/打开 |
| LEDGER-HOME-016 | `ui/features/LedgerScreen.kt:136` | EventRow | `{onSelect(it)}` | none | — | 导航/打开 |
| LEDGER-HOME-017 | `ui/features/LedgerControls.kt:27` | clickable | `{onStart(kind)}` | Compose | — | 开始记一笔 |
| LEDGER-HOME-018 | `ui/features/LedgerControls.kt:37` | clickable | `onToggle` | none | — | 导航/打开 |
| LEDGER-HOME-019 | `ui/features/LedgerControls.kt:48` | clickable | `onClear` | Select | — |  |
| LEDGER-HOME-020 | `ui/features/LedgerCalendar.kt:56` | clickable | `{onChange(value)}` | Select | — |  |
| LEDGER-HOME-021 | `ui/features/LedgerCalendar.kt:63` | clickable | `{onChange(value)}` | Select | — |  |
| LEDGER-HOME-022 | `ui/features/LedgerCalendar.kt:71` | clickable | `{onChange(value)}` | Select | — |  |
| LEDGER-HOME-023 | `ui/features/LedgerCalendar.kt:106` | CalendarMonthHeader | `{changeMonth(month.minusMonths(1))}` | Step | — |  |
| LEDGER-HOME-024 | `ui/features/LedgerCalendar.kt:106` | CalendarMonthHeader | `{changeMonth(month.plusMonths(1))}` | Step | — |  |
| LEDGER-HOME-025 | `ui/features/LedgerCalendar.kt:115` | detectHorizontalDragGestures | `{if(drag<-120f)changeMonth(month.plusMonths(1)) else if(drag` | PageTurn / Limit | — | 翻月成功=PageTurn；拒绝未来月=Limit；拖动中不震 |
| LEDGER-HOME-026 | `ui/features/LedgerCalendar.kt:115` | detectHorizontalDragGestures | `{drag=0f}` | PageTurn / Limit | — | 翻月成功=PageTurn；拒绝未来月=Limit；拖动中不震 |
| LEDGER-HOME-027 | `ui/features/LedgerCalendar.kt:115` | detectHorizontalDragGestures | `{_,delta->drag+=delta}` | PageTurn / Limit | — | 翻月成功=PageTurn；拒绝未来月=Limit；拖动中不震 |
| LEDGER-HOME-028 | `ui/features/LedgerCalendar.kt:120` | CalendarCell | `{selectedText=day!!.date.toString()}` | Select | — |  |
| LEDGER-HOME-029 | `ui/features/LedgerCalendar.kt:127` | CalendarDayPanel | `onSelect` | none | — | 导航/打开 |
| LEDGER-HOME-030 | `ui/features/LedgerCalendar.kt:127` | CalendarDayPanel | `{onStartOn(day.toString())}` | Compose | — | 开始记一笔 |
| LEDGER-HOME-031 | `ui/features/LedgerCalendar.kt:145` | clickable | `onClick` | Step / Limit | — | 换月=Step；已到当前月不可再往后=Limit |
| LEDGER-HOME-032 | `ui/features/LedgerCalendar.kt:154` | clickable | `onClick` | Step / Limit | — | 换月=Step；已到当前月不可再往后=Limit |
| LEDGER-HOME-033 | `ui/features/LedgerCalendar.kt:231` | clickable | `onClick` | Select | — |  |
| LEDGER-HOME-034 | `ui/features/LedgerCalendar.kt:328` | EventRow | `{onSelect(it)}` | none | — | 导航/打开 |
| LEDGER-HOME-035 | `ui/features/LedgerCalendar.kt:340` | clickable | `onAdd` | none | — | 导航/打开/关闭 |
| LEDGER-HOME-036 | `ui/features/LedgerCalendar.kt:347` | clickable | `onAdd` | none | — | 导航/打开/关闭 |
| LEDGER-HOME-037 | `ui/features/LedgerCalendar.kt:357` | clickable | `onAdd` | none | — | 导航/打开/关闭 |
| LEDGER-HOME-038 | `ui/features/HomeSections.kt:33` | clickable | `onOpen` | none | — | 导航/打开/关闭 |
| LEDGER-HOME-039 | `ui/features/HomeSections.kt:58` | clickable | `onResume` | none | — | 导航/打开 |
| LEDGER-HOME-040 | `ui/features/LazyPage.kt:60` | EventRow | `{onSelect(it)}` | none | — | 导航/打开 |
| LEDGER-HOME-041 | `ui/features/LazyPage.kt:66` | EventRow | `{onSelect(e)}` | none | — | 导航/打开 |
| LEDGER-HOME-042 | `ui/features/ReadFailureScreen.kt:18` | 重新读取账本 | `onRetry` | none | — | 导航/打开 |
| LEDGER-HOME-043 | `ui/features/ReadFailureScreen.kt:19` | 收起错误详情 | `{details=!details}` | none | — | 展开/收起 |

## 预算、周期账单与模板（77）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| BUDGET-RECURRING-TEMPLATES-001 | `ui/features/BudgetScreens.kt:20` | ‹ | `{onMonth(YearMonth.parse(month).minusMonths(1).toString())}` | Step | — |  |
| BUDGET-RECURRING-TEMPLATES-002 | `ui/features/BudgetScreens.kt:22` | › | `{onMonth(YearMonth.parse(month).plusMonths(1).toString())}` | Step | — |  |
| BUDGET-RECURRING-TEMPLATES-003 | `ui/features/BudgetScreens.kt:54` | 编辑月度计划 | `{vm.navigate("monthly-plan")}` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-004 | `ui/features/BudgetScreens.kt:55` | 月度评语与历史 | `{vm.navigate("monthly-history")}` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-005 | `ui/features/BudgetScreens.kt:74` | MonthChooser | `{month=it}` | Step | — | 换月 |
| BUDGET-RECURRING-TEMPLATES-006 | `ui/features/BudgetScreens.kt:82` | 调整本月预算 | `{openEditor(null)}` | none | — | 导航/打开 |
| BUDGET-RECURRING-TEMPLATES-007 | `ui/features/BudgetScreens.kt:84` | 月分类预算 | `onCategories` | none | — | 导航/打开 |
| BUDGET-RECURRING-TEMPLATES-008 | `ui/features/BudgetScreens.kt:87` | YEmpty | `onCategories` | none | — | 导航/打开 |
| BUDGET-RECURRING-TEMPLATES-009 | `ui/features/BudgetScreens.kt:92` | BudgetLimitRow | `{openEditor(category.id)}` | none | — | 导航/打开 |
| BUDGET-RECURRING-TEMPLATES-010 | `ui/features/BudgetScreens.kt:101` | 查看固定支出预留 | `onRecurring` | none | — | 打开预览/查看 |
| BUDGET-RECURRING-TEMPLATES-011 | `ui/features/BudgetScreens.kt:104` | 预算变化预览 | `{vm.budgetReview.value=null}` | none | — | 关闭/返回不加触感 |
| BUDGET-RECURRING-TEMPLATES-012 | `ui/features/BudgetScreens.kt:118` | 确认预算调整 | `{vm.confirmBudget{editor=null}}` | none | Done |  |
| BUDGET-RECURRING-TEMPLATES-013 | `ui/features/BudgetScreens.kt:119` | 返回继续编辑 | `{vm.budgetReview.value=null}` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-014 | `ui/features/BudgetScreens.kt:126` | 调整月预算 | `onClose` | none | — | 关闭/返回不加触感 |
| BUDGET-RECURRING-TEMPLATES-015 | `ui/features/BudgetScreens.kt:128` | 预算金额 / 留空取消设置 | `onAmount` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-016 | `ui/features/BudgetScreens.kt:130` | 沿用上月金额 | `{onAmount(previous?.let{Money(currency,it).decimal.toPlainSt` | Select | — | 填入上月金额 |
| BUDGET-RECURRING-TEMPLATES-017 | `ui/features/BudgetScreens.kt:132` | 预览预算调整 | `{vm.previewBudget(s.book.id,month,categoryId,amount,currency` | none | — | 打开预览/查看 |
| BUDGET-RECURRING-TEMPLATES-018 | `ui/features/RecurrenceScreens.kt:27` | MonthChooser | `{month=it}` | Step | — | 换月 |
| BUDGET-RECURRING-TEMPLATES-019 | `ui/features/RecurrenceScreens.kt:42` | RecurringCard | `if(o.id in pending)({onConfirm(o)})else null` | none | — | 导航/打开 |
| BUDGET-RECURRING-TEMPLATES-020 | `ui/features/RecurrenceScreens.kt:42` | RecurringCard | `{editor=o.recurrenceId}` | none | — | 导航/打开 |
| BUDGET-RECURRING-TEMPLATES-021 | `ui/features/RecurrenceScreens.kt:47` | 恢复这次待办 | `{vm.execute{vm.repository.restoreOccurrence(s.book.id,o.id)}` | none | Restored |  |
| BUDGET-RECURRING-TEMPLATES-022 | `ui/features/RecurrenceScreens.kt:51` | 关联已有支出 | `{linking=o.id}` | none | — | 导航/打开 |
| BUDGET-RECURRING-TEMPLATES-023 | `ui/features/RecurrenceScreens.kt:52` | 取消这次待办 | `{vm.execute{vm.repository.cancelOccurrence(s.book.id,o.id)}}` | none | Removed | 取消这次待办 |
| BUDGET-RECURRING-TEMPLATES-024 | `ui/features/RecurrenceScreens.kt:54` | 查看已存记录 | `{onRecord(event)}` | none | — | 打开预览/查看 |
| BUDGET-RECURRING-TEMPLATES-025 | `ui/features/RecurrenceScreens.kt:55` | 解除关联，重新待确认 | `{vm.execute{vm.repository.unlinkOccurrence(s.book.id,o.id)}}` | none | Done |  |
| BUDGET-RECURRING-TEMPLATES-026 | `ui/features/RecurrenceScreens.kt:62` | 编辑规则 | `{editor=rule.id}` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-027 | `ui/features/RecurrenceScreens.kt:65` | 添加付款账户 | `onAccounts` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-028 | `ui/features/RecurrenceScreens.kt:66` | 创建支出分类 | `onCategories` | none | — | 导航/打开 |
| BUDGET-RECURRING-TEMPLATES-029 | `ui/features/RecurrenceScreens.kt:67` | ＋ 添加周期账单 | `{editor=""}` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-030 | `ui/features/RecurrenceScreens.kt:71` | 选择已有支出 | `{linking=null}` | none | — | 关闭/返回不加触感 |
| BUDGET-RECURRING-TEMPLATES-031 | `ui/features/RecurrenceScreens.kt:83` | TransactionList | `{e->vm.previewOccurrenceLink(s.book.id,occurrence.id,e.id)}` | none | — | 导航/打开 |
| BUDGET-RECURRING-TEMPLATES-032 | `ui/features/RecurrenceScreens.kt:107` | 添加周期账单 | `onClose` | none | — | 关闭/返回不加触感 |
| BUDGET-RECURRING-TEMPLATES-033 | `ui/features/RecurrenceScreens.kt:109` | 名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-034 | `ui/features/RecurrenceScreens.kt:110` | 金额 / 未定可留空 | `{amount=it}` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-035 | `ui/features/RecurrenceScreens.kt:111` | 本位币预留 / 未折算可留空 | `{base=it}` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-036 | `ui/features/RecurrenceScreens.kt:112` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| BUDGET-RECURRING-TEMPLATES-037 | `ui/features/RecurrenceScreens.kt:112` | YDateFields | `{index,value->if(index==0)date=value else end=value}` | Select | — | 组件内置：选日期=Select，换月=Step |
| BUDGET-RECURRING-TEMPLATES-038 | `ui/features/RecurrenceScreens.kt:113` | 频率 | `{frequency=it}` | Select | — | 组件内置：选中菜单项 |
| BUDGET-RECURRING-TEMPLATES-039 | `ui/features/RecurrenceScreens.kt:114` | 付款账户 | `{id-> val next=accounts.first{it.id==id};if(next.currency!=a` | Select | — | 组件内置：选中菜单项 |
| BUDGET-RECURRING-TEMPLATES-040 | `ui/features/RecurrenceScreens.kt:117` | 分类 | `{categoryId=it}` | Select | — | 组件内置：选中菜单项 |
| BUDGET-RECURRING-TEMPLATES-041 | `ui/features/RecurrenceScreens.kt:119` | 备注 | `{fixed=fixed.copy(note=it)}` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-042 | `ui/features/RecurrenceScreens.kt:122` | 金额为预计 | `{estimated=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| BUDGET-RECURRING-TEMPLATES-043 | `ui/features/RecurrenceScreens.kt:123` | 设置结束日期 | `{owner=null;endEnabled=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| BUDGET-RECURRING-TEMPLATES-044 | `ui/features/RecurrenceScreens.kt:124` | 所属活动 / 可选 | `{activity=it}` | Select | — | 组件内置：选中菜单项 |
| BUDGET-RECURRING-TEMPLATES-045 | `ui/features/RecurrenceScreens.kt:125` | 启用此周期规则 | `{active=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| BUDGET-RECURRING-TEMPLATES-046 | `ui/features/RecurrenceScreens.kt:127` | 保存周期规则 | `{ val capturedName=name;val capturedAccount=accountId;val ca` | none | Done |  |
| BUDGET-RECURRING-TEMPLATES-047 | `ui/features/RecurrenceScreens.kt:150` | 关联变化预览 | `onDismiss` | none | — | 关闭/返回不加触感 |
| BUDGET-RECURRING-TEMPLATES-048 | `ui/features/RecurrenceScreens.kt:163` | 确认关联已有记录 | `onConfirm` | none | Done |  |
| BUDGET-RECURRING-TEMPLATES-049 | `ui/features/RecurrenceScreens.kt:164` | 返回付款表单 | `onDismiss` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-050 | `ui/features/RecurrenceScreens.kt:177` | 确认周期账单 | `{vm.dismissOccurrenceConfirmation()}` | none | — | 关闭/返回不加触感 |
| BUDGET-RECURRING-TEMPLATES-051 | `ui/features/RecurrenceScreens.kt:186` | 本次金额 | `vm::changeOccurrenceAmount` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-052 | `ui/features/RecurrenceScreens.kt:191` | 确认记账 | `{vm.confirmOccurrence()}` | none | Done |  |
| BUDGET-RECURRING-TEMPLATES-053 | `ui/features/RecurrenceScreens.kt:192` | 修改后再记 | `onEdit` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-054 | `ui/features/TemplateScreens.kt:17` | 付款方式 | `{onChange(d.clearCardPayment().copy(cardPaymentChoice=it,car` | Select | — | 组件内置：选中菜单项 |
| BUDGET-RECURRING-TEMPLATES-055 | `ui/features/TemplateScreens.kt:20` | 分期次数 | `{onChange(d.copy(cardPaymentPeriods=it))}` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-056 | `ui/features/TemplateScreens.kt:26` | 常用的一笔 | `onManage` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-057 | `ui/features/TemplateScreens.kt:30` | TemplateShelfItem | `{onUse(t)}` | Compose | — | 用模板开始记一笔 |
| BUDGET-RECURRING-TEMPLATES-058 | `ui/features/TemplateScreens.kt:52` | 编辑 | `{editing=t.id}` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-059 | `ui/features/TemplateScreens.kt:53` | ↑ 上移 | `{vm.execute{vm.repository.moveTemplate(s.book.id,t.id,-1)}}` | Step | — | 上移/下移 |
| BUDGET-RECURRING-TEMPLATES-060 | `ui/features/TemplateScreens.kt:54` | ↓ 下移 | `{vm.execute{vm.repository.moveTemplate(s.book.id,t.id,1)}}` | Step | — | 上移/下移 |
| BUDGET-RECURRING-TEMPLATES-061 | `ui/features/TemplateScreens.kt:55` | 删除 | `{deleting=t.id}` | none | — | 打开删除审阅 |
| BUDGET-RECURRING-TEMPLATES-062 | `ui/features/TemplateScreens.kt:59` | 新建模板 | `{editing=""}` | none | — | 导航/打开/关闭 |
| BUDGET-RECURRING-TEMPLATES-063 | `ui/features/TemplateScreens.kt:61` | 删除模板 | `{deleting=null}` | none | — | 关闭/返回不加触感 |
| BUDGET-RECURRING-TEMPLATES-064 | `ui/features/TemplateScreens.kt:63` | 确认删除 | `{vm.execute(onSuccess={deleting=null}){vm.repository.deleteT` | none | Removed |  |
| BUDGET-RECURRING-TEMPLATES-065 | `ui/features/TemplateScreens.kt:71` | 存为模板 | `onClose` | none | — | 关闭/返回不加触感 |
| BUDGET-RECURRING-TEMPLATES-066 | `ui/features/TemplateScreens.kt:72` | 模板名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-067 | `ui/features/TemplateScreens.kt:73` | 同时保存金额 | `{includeAmount=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| BUDGET-RECURRING-TEMPLATES-068 | `ui/features/TemplateScreens.kt:74` | 保存模板 | `{ vm.execute(onSuccess=onClose){vm.repository.saveTemplate(s` | none | Done |  |
| BUDGET-RECURRING-TEMPLATES-069 | `ui/features/TemplateScreens.kt:87` | 新建模板 | `onClose` | none | — | 关闭/返回不加触感 |
| BUDGET-RECURRING-TEMPLATES-070 | `ui/features/TemplateScreens.kt:88` | 模板名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-071 | `ui/features/TemplateScreens.kt:89` | 类型 | `{kind->d=d.clearCardPayment().copy(kind=kind,categoryId=null` | Select | — | 组件内置：选中菜单项 |
| BUDGET-RECURRING-TEMPLATES-072 | `ui/features/TemplateScreens.kt:90` | 分类 | `{d=d.copy(categoryId=it)}` | Select | — | 组件内置：选中菜单项 |
| BUDGET-RECURRING-TEMPLATES-073 | `ui/features/TemplateScreens.kt:91` | 账户 | `{id-> val next=s.accounts.first{it.id==id};d=d.clearCardPaym` | Select | — | 组件内置：选中菜单项 |
| BUDGET-RECURRING-TEMPLATES-074 | `ui/features/TemplateScreens.kt:95` | 金额 / 每次输入可留空 | `{d=d.copy(amount=it)}` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-075 | `ui/features/TemplateScreens.kt:97` | 备注 | `{d=d.copy(note=it)}` | none | — | 系统输入法负责逐键反馈 |
| BUDGET-RECURRING-TEMPLATES-076 | `ui/features/TemplateScreens.kt:98` | 所属活动 / 可选 | `{d=d.copy(activityId=it)}` | Select | — | 组件内置：选中菜单项 |
| BUDGET-RECURRING-TEMPLATES-077 | `ui/features/TemplateScreens.kt:100` | 保存模板 | `{vm.execute(onSuccess=onClose){vm.repository.saveTemplate(s.` | none | Done |  |

## 设置、主题与首页（47）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| SETTINGS-THEME-HOME-001 | `ui/features/SettingsScreen.kt:30` | 外观主题 | `onAppearance` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-002 | `ui/features/SettingsScreen.kt:31` | 切换为日语 | `onLanguage` | Select | — |  |
| SETTINGS-THEME-HOME-003 | `ui/features/SettingsScreen.kt:34` | 独立账本管理 | `onBooks` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-004 | `ui/features/SettingsScreen.kt:35` | 账本名称与资料 | `onRename` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-005 | `ui/features/SettingsScreen.kt:36` | 两级分类管理 | `onCategories` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-006 | `ui/features/SettingsScreen.kt:37` | 记账模板 | `onTemplates` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-007 | `ui/features/SettingsScreen.kt:38` | 商户与地点 | `onMerchants` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-008 | `ui/features/SettingsScreen.kt:41` | 重要提醒设置 | `onReminders` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-009 | `ui/features/SettingsScreen.kt:42` | 参考汇率与自动获取 | `onRates` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-010 | `ui/features/SettingsScreen.kt:45` | 小票识别与商品明细 | `onReceipts` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-011 | `ui/features/SettingsScreen.kt:46` | 银行 / 信用卡流水核对 | `onStatements` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-012 | `ui/features/SettingsScreen.kt:47` | 云端分析交接台 | `onCloud` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-013 | `ui/features/SettingsScreen.kt:50` | 照片保存方式 | `{showPhotoStorage=true}` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-014 | `ui/features/SettingsScreen.kt:51` | 备份与恢复 | `onBackup` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-015 | `ui/features/SettingsScreen.kt:52` | 从 CSV 导入 | `onCsv` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-016 | `ui/features/SettingsScreen.kt:55` | 回收站 · 30天 | `onTrash` | none | — | 打开删除审阅 |
| SETTINGS-THEME-HOME-017 | `ui/features/SettingsScreen.kt:58` | 照片保存方式 | `{showPhotoStorage=false}` | none | — | 关闭/返回不加触感 |
| SETTINGS-THEME-HOME-018 | `ui/features/SettingsScreen.kt:60` | 原样保存 | `{vm.execute{vm.preferences.setPhotoSaveOriginal(original)}}` | Select | — |  |
| SETTINGS-THEME-HOME-019 | `ui/features/SettingsScreen.kt:74` | selectable | `{onTheme(id)}` | Select | — | 以新主题的 Select 波形播放，即时“试手感” |
| SETTINGS-THEME-HOME-020 | `ui/features/SettingsScreen.kt:102` | clickable | `onClick` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-021 | `ui/features/ThemeScreens.kt:84` | 设置月预算 | `onBudget` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-022 | `ui/features/ThemeScreens.kt:90` | HomeResumeDraft | `onResume` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-023 | `ui/features/ThemeScreens.kt:92` | HomeTemplates | `onTemplate` | Compose | — | 用模板开始记一笔 |
| SETTINGS-THEME-HOME-024 | `ui/features/ThemeScreens.kt:92` | HomeTemplates | `{navigate("templates")}` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-025 | `ui/features/ThemeScreens.kt:94` | 预算与分类限额 | `onBudget` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-026 | `ui/features/ThemeScreens.kt:95` | 添加记账模板 | `{navigate("templates")}` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-027 | `ui/features/ThemeScreens.kt:96` | 添加周期账单 | `onRecurring` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-028 | `ui/features/ThemeScreens.kt:97` | 周期账单与历史 | `onRecurring` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-029 | `ui/features/ThemeScreens.kt:104` | 确认 / 管理周期账单 | `onRecurring` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-030 | `ui/features/ThemeScreens.kt:106` | 最近的几笔 | `{navigate("ledger")}` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-031 | `ui/features/ThemeScreens.kt:108` | TransactionList | `onEvent` | none | — | 导航/打开 |
| SETTINGS-THEME-HOME-032 | `ui/features/ThemeScreens.kt:108` | YEmpty | `onAdd` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-033 | `ui/features/ThemeScreens.kt:110` | HomeCashNote | `{navigate("accounts")}` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-034 | `ui/features/NotificationSettingsScreen.kt:68` | 每月评语提醒（每月1日，独立开启） | `{monthlyReview=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| SETTINGS-THEME-HOME-035 | `ui/features/NotificationSettingsScreen.kt:69` | 开启本账本的重要提醒 | `{enabled=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| SETTINGS-THEME-HOME-036 | `ui/features/NotificationSettingsScreen.kt:70` | 信用卡 / 借款还款 | `{repayment=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| SETTINGS-THEME-HOME-037 | `ui/features/NotificationSettingsScreen.kt:71` | 还款方式变更截止 | `{deadline=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| SETTINGS-THEME-HOME-038 | `ui/features/NotificationSettingsScreen.kt:72` | 未确认周期账单 | `{recurring=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| SETTINGS-THEME-HOME-039 | `ui/features/NotificationSettingsScreen.kt:73` | 预算超支风险 | `{budget=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| SETTINGS-THEME-HOME-040 | `ui/features/NotificationSettingsScreen.kt:74` | 付款账户资金缺口 | `{funds=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| SETTINGS-THEME-HOME-041 | `ui/features/NotificationSettingsScreen.kt:77` | repayment-days | `{repaymentDays=it}` | none | — | 系统输入法负责逐键反馈 |
| SETTINGS-THEME-HOME-042 | `ui/features/NotificationSettingsScreen.kt:78` | deadline-days | `{deadlineDays=it}` | none | — | 系统输入法负责逐键反馈 |
| SETTINGS-THEME-HOME-043 | `ui/features/NotificationSettingsScreen.kt:79` | recurring-days | `{recurringDays=it}` | none | — | 系统输入法负责逐键反馈 |
| SETTINGS-THEME-HOME-044 | `ui/features/NotificationSettingsScreen.kt:80` | reminder-time | `{time=it}` | none | — | 系统输入法负责逐键反馈 |
| SETTINGS-THEME-HOME-045 | `ui/features/NotificationSettingsScreen.kt:85` | 保存提醒偏好 | `{ try { fun days(raw:String)=raw.replace('，',',').split(',')` | none | Done |  |
| SETTINGS-THEME-HOME-046 | `ui/features/NotificationSettingsScreen.kt:98` | 打开系统通知设置 | `{context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATI` | none | — | 导航/打开/关闭 |
| SETTINGS-THEME-HOME-047 | `ui/features/NotificationSettingsScreen.kt:121` | value | `onChange` | ToggleOn/ToggleOff | — | 组件内置 |

## 分析（52）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| ANALYSIS-001 | `ui/features/AnalysisScreen.kt:59` | YTabs | `{tab=it}` | TabSwitch | — | 组件内置 |
| ANALYSIS-002 | `ui/features/AnalysisScreen.kt:61` | 筛选时间、分类与商户 | `{filtering=true}` | none | — | 导航/打开/关闭 |
| ANALYSIS-003 | `ui/features/AnalysisScreen.kt:72` | analysisModuleName(module) | `{initialModule=module;editor=true}` | none | — | 导航/打开/关闭 |
| ANALYSIS-004 | `ui/features/AnalysisScreen.kt:75` | 选择分析模块 | `{initialModule=null;editor=true}` | none | — | 导航/打开/关闭 |
| ANALYSIS-005 | `ui/features/AnalysisScreen.kt:77` | 自定义分析 | `{initialModule=null;editor=true}` | none | — | 导航/打开/关闭 |
| ANALYSIS-006 | `ui/features/AnalysisScreen.kt:84` | AnalysisCategoryList | `{detail=if(it=="FEES")"fees"else"category:$it"}` | none | — | 下钻 |
| ANALYSIS-007 | `ui/features/AnalysisScreen.kt:85` | 管理分类 | `onCategories` | none | — | 导航/打开/关闭 |
| ANALYSIS-008 | `ui/features/AnalysisScreen.kt:90` | AnalysisMerchantList | `{detail="merchant:$it"}` | none | — | 下钻 |
| ANALYSIS-009 | `ui/features/AnalysisScreen.kt:91` | 商户与保存地点 | `{vm.navigate("merchants")}` | none | — | 导航/打开/关闭 |
| ANALYSIS-010 | `ui/features/AnalysisScreen.kt:104` | 商户资料与保存地点 | `{vm.navigate("merchants")}` | none | — | 导航/打开/关闭 |
| ANALYSIS-011 | `ui/features/AnalysisScreen.kt:105` | 朋友名册 | `{vm.navigate("friends")}` | none | — | 导航/打开/关闭 |
| ANALYSIS-012 | `ui/features/AnalysisScreen.kt:106` | 小票图片工作台 | `{vm.navigate("receipts")}` | none | — | 导航/打开/关闭 |
| ANALYSIS-013 | `ui/features/AnalysisScreen.kt:119` | clickable | `onClick` | none | — | 下钻 |
| ANALYSIS-014 | `ui/features/AnalysisScreen.kt:123` | clickable | `onClick` | none | — | 下钻 |
| ANALYSIS-015 | `ui/features/AnalysisScreen.kt:127` | clickable | `onClick` | none | — | 下钻 |
| ANALYSIS-016 | `ui/features/AnalysisScreen.kt:136` | 趋势粒度 | `{mode=it}` | Select | — | 组件内置：选中菜单项 |
| ANALYSIS-017 | `ui/features/AnalysisScreen.kt:140` | AnalysisTrendChart | `{key->selected=key;onDetail((if(mode=="month")"month:"else "` | Select | — |  |
| ANALYSIS-018 | `ui/features/AnalysisScreen.kt:158` | name | `onDetail?.let{action->{action("current",key)}}` | none | — | 下钻 |
| ANALYSIS-019 | `ui/features/AnalysisScreen.kt:158` | name | `onDetail?.let{action->{action("previous",key)}}` | none | — | 下钻 |
| ANALYSIS-020 | `ui/features/AnalysisScreen.kt:178` | ActivityCard | `onActivity?.let{action->{action(a.id)}}` | none | — | 下钻 |
| ANALYSIS-021 | `ui/features/AnalysisScreen.kt:181` | periodLabel | `{onPeriod(a.id)}` | Select | — |  |
| ANALYSIS-022 | `ui/features/AnalysisScreen.kt:187` | dailyLabel | `{onPeriod("")}` | Select | — |  |
| ANALYSIS-023 | `ui/features/AnalysisScreen.kt:212` | selectedTitle | `onDismiss` | none | — | 关闭/返回不加触感 |
| ANALYSIS-024 | `ui/features/AnalysisScreen.kt:220` | AnalysisChildRow | `{child=c.id}` | none | — | 下钻 |
| ANALYSIS-025 | `ui/features/AnalysisScreen.kt:221` | AnalysisChildRow | `{child="UNSPECIFIED"}` | none | — | 下钻 |
| ANALYSIS-026 | `ui/features/AnalysisScreen.kt:224` | 返回一级分类汇总 | `{child=null}` | none | — | 导航/打开/关闭 |
| ANALYSIS-027 | `ui/features/AnalysisScreen.kt:227` | EventRow | `{onDismiss();onRecord(e)}` | none | — | 导航/打开/关闭 |
| ANALYSIS-028 | `ui/features/AnalysisScreen.kt:231` | 查看关联转账 | `{onDismiss();onRecord(f.event)}` | none | — | 打开预览/查看 |
| ANALYSIS-029 | `ui/features/AnalysisScreen.kt:236` | EventRow | `{onDismiss();onRecord(e)}` | none | — | 导航/打开/关闭 |
| ANALYSIS-030 | `ui/features/AnalysisScreen.kt:248` | 自定义分析 | `onDismiss` | none | — | 关闭/返回不加触感 |
| ANALYSIS-031 | `ui/features/AnalysisScreen.kt:257` | analysisModuleName(module) | `{enabled->modules=if(enabled)modules+module else modules-mod` | ToggleOn/ToggleOff | — |  |
| ANALYSIS-032 | `ui/features/AnalysisScreen.kt:257` | analysisModuleName(module) | `{move(-1)}` | none | — | 导航/打开/关闭 |
| ANALYSIS-033 | `ui/features/AnalysisScreen.kt:257` | analysisModuleName(module) | `{move(1)}` | none | — | 导航/打开/关闭 |
| ANALYSIS-034 | `ui/features/AnalysisScreen.kt:257` | analysisModuleName(module) | `{enabled->pinned=if(enabled)pinned+module else pinned-module` | ToggleOn/ToggleOff | — |  |
| ANALYSIS-035 | `ui/features/AnalysisScreen.kt:261` | 预览： | `{preview=if(preview==m)null else m}` | none | — | 打开预览/查看 |
| ANALYSIS-036 | `ui/features/AnalysisScreen.kt:280` | 保存分析偏好 | `{vm.execute(onSuccess=onDismiss){vm.repository.saveAnalysisP` | none | Done |  |
| ANALYSIS-037 | `ui/features/AnalysisScreen.kt:288` | 分析筛选 | `onDismiss` | none | — | 关闭/返回不加触感 |
| ANALYSIS-038 | `ui/features/AnalysisScreen.kt:289` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| ANALYSIS-039 | `ui/features/AnalysisScreen.kt:289` | YDateFields | `{index,value->if(index==0)start=value else end=value}` | Select | — | 组件内置：选日期=Select，换月=Step |
| ANALYSIS-040 | `ui/features/AnalysisScreen.kt:290` | 分类 | `{category=it}` | Select | — | 组件内置：选中菜单项 |
| ANALYSIS-041 | `ui/features/AnalysisScreen.kt:292` | 商户 | `{merchant=it}` | Select | — | 组件内置：选中菜单项 |
| ANALYSIS-042 | `ui/features/AnalysisScreen.kt:293` | 活动 | `{activity=it}` | Select | — | 组件内置：选中菜单项 |
| ANALYSIS-043 | `ui/features/AnalysisScreen.kt:295` | 应用筛选 | `{vm.execute(onSuccess={onApply(value)}){s.analysis(value)}}` | Select | — |  |
| ANALYSIS-044 | `ui/features/AnalysisScreen.kt:296` | 清除分类、商户和活动筛选 | `{category=null;merchant=null;activity=null}` | Select | — |  |
| ANALYSIS-045 | `ui/features/AnalysisPlanningViews.kt:25` | 预算月份 | `{selected=it}` | Select | — | 组件内置：选中菜单项 |
| ANALYSIS-046 | `ui/features/AnalysisPlanningViews.kt:30` | 调整月预算 | `{onManage(selected)}` | none | — | 导航/打开/关闭 |
| ANALYSIS-047 | `ui/features/AnalysisPlanningViews.kt:40` | BudgetLimitRow | `{onScope(c.id)}` | Select | — |  |
| ANALYSIS-048 | `ui/features/AnalysisPlanningViews.kt:44` | 管理月预算 | `{onManage(selected)}` | none | — | 导航/打开/关闭 |
| ANALYSIS-049 | `ui/features/AnalysisPlanningViews.kt:52` | 管理周期账单 | `onRecurring` | none | — | 导航/打开/关闭 |
| ANALYSIS-050 | `ui/features/AnalysisPlanningViews.kt:58` | 查看活动安排 | `{onActivity(r.activityId)}` | none | — | 打开预览/查看 |
| ANALYSIS-051 | `ui/features/AnalysisPlanningViews.kt:66` | 查看转账状态 | `{onTransfer(event)}` | none | — | 打开预览/查看 |
| ANALYSIS-052 | `ui/features/BasicAnalysis.kt:90` | clickable | `onClick` | none | — | 下钻 |

## 修订历史与回收站（4）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| HISTORY-TRASH-001 | `ui/features/RevisionHistory.kt:44` | 重新读取 | `{attempt++}` | none | — | 导航/打开 |
| HISTORY-TRASH-002 | `ui/features/TrashScreen.kt:38` | 恢复原记录 | `{selected=e.id;operation=EntryLifecycleOperation.RESTORE}` | none | — | 导航/打开 |
| HISTORY-TRASH-003 | `ui/features/TrashScreen.kt:39` | 永久删除 | `{selected=e.id;operation=EntryLifecycleOperation.PURGE}` | none | — | 打开删除审阅 |
| HISTORY-TRASH-004 | `ui/features/TrashScreen.kt:43` | 清空回收站 | `{selected="";operation=EntryLifecycleOperation.EMPTY}` | none | — | 打开删除审阅 |

## 活动与朋友互请（71）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| ACTIVITY-001 | `ui/features/ActivityScreens.kt:38` | 本账本活动功能 | `{enabled->vm.execute{vm.repository.setActivityMode(s.book.id` | ToggleOn/ToggleOff | — | 组件内置 |
| ACTIVITY-002 | `ui/features/ActivityScreens.kt:42` | 退出记录模式 | `{vm.execute{vm.repository.setActivityMode(s.book.id,true)}}` | ToggleOff | — | 退出记录模式 |
| ACTIVITY-003 | `ui/features/ActivityScreens.kt:48` | ActivityCard | `{selected=a.id}` | none | — | 导航/打开 |
| ACTIVITY-004 | `ui/features/ActivityScreens.kt:51` | ＋ 创建活动 | `{editing=""}` | none | — | 导航/打开/关闭 |
| ACTIVITY-005 | `ui/features/ActivityScreens.kt:55` | a.name | `{selected=a.id}` | none | — | 导航/打开 |
| ACTIVITY-006 | `ui/features/ActivityScreens.kt:56` | 恢复 | `{vm.execute{vm.repository.restoreActivity(s.book.id,a.id)}}` | none | Restored |  |
| ACTIVITY-007 | `ui/features/ActivityScreens.kt:60` | 返回全部活动 | `{selected=null}` | none | — | 导航/打开/关闭 |
| ACTIVITY-008 | `ui/features/ActivityScreens.kt:68` |  ·  | `{onRecord(e)}` | none | — | 导航/打开/关闭 |
| ACTIVITY-009 | `ui/features/ActivityScreens.kt:74` | 返回全部活动 | `{selected=null}` | none | — | 导航/打开/关闭 |
| ACTIVITY-010 | `ui/features/ActivityScreens.kt:85` | 编辑活动与预算 | `{editing=activity.id}` | none | — | 导航/打开/关闭 |
| ACTIVITY-011 | `ui/features/ActivityScreens.kt:86` | 进入记录模式 | `{vm.execute{vm.repository.setActivityMode(s.book.id,true,act` | ToggleOn | — | 进入记录模式 |
| ACTIVITY-012 | `ui/features/ActivityScreens.kt:89` | 查看删除影响 | `{deleting=activity.id}` | none | — | 打开删除审阅 |
| ACTIVITY-013 | `ui/features/ActivityScreens.kt:90` | 补录期间的支出 | `{ onExpense(activity.id,null) }` | none | — | 导航/打开 |
| ACTIVITY-014 | `ui/features/ActivityScreens.kt:102` | 调整预留 | `{reserveFor="edit:"+r.id}` | none | — | 导航/打开 |
| ACTIVITY-015 | `ui/features/ActivityScreens.kt:103` | 释放剩余预留 | `{reserveFor="release:"+r.id}` | none | — | 导航/打开 |
| ACTIVITY-016 | `ui/features/ActivityScreens.kt:106` | ＋ 预留资金 | `{reserveFor=activity.id}` | none | — | 导航/打开 |
| ACTIVITY-017 | `ui/features/ActivityScreens.kt:112` | FriendBalanceCard | `{friendDetail=b.friend.id}` | none | — | 导航/打开 |
| ACTIVITY-018 | `ui/features/ActivityScreens.kt:118` | 加入： | `{vm.execute{vm.repository.addParticipant(s.book.id,activity.` | none | Done | 加入参与者 |
| ACTIVITY-019 | `ui/features/ActivityScreens.kt:119` | 新朋友名称 | `{friendName=it}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-020 | `ui/features/ActivityScreens.kt:120` | 添加朋友 | `{vm.execute(onSuccess={friendName=""}){val id=vm.repository.` | none | Done |  |
| ACTIVITY-021 | `ui/features/ActivityScreens.kt:124` | 补录期间的朋友付款 | `{treatFriend=null;treatId=null;treatFor=activity.id}` | none | — | 导航/打开 |
| ACTIVITY-022 | `ui/features/ActivityScreens.kt:128` |  ·  | `{onRecord(e)}` | none | — | 导航/打开/关闭 |
| ACTIVITY-023 | `ui/features/ActivityScreens.kt:135` | 修正份额 | `{treatFriend=null;treatId=t.id;treatFor=activity.id}` | none | — | 导航/打开 |
| ACTIVITY-024 | `ui/features/ActivityScreens.kt:136` | 删除这次互请 | `{vm.execute{vm.repository.removeOtherTreat(s.book.id,t.id)}}` | none | Removed |  |
| ACTIVITY-025 | `ui/features/ActivityScreens.kt:140` | s.friends.first{it.id==friendId}.name | `{friendDetail=null}` | none | — | 关闭/返回不加触感 |
| ACTIVITY-026 | `ui/features/ActivityScreens.kt:147` | 补录期间的我付款 | `{friendDetail=null;onExpense(a.id,friendId)}` | none | — | 导航/打开 |
| ACTIVITY-027 | `ui/features/ActivityScreens.kt:148` | 补录期间的对方付款 | `{friendDetail=null;treatFriend=friendId;treatId=null;treatFo` | none | — | 导航/打开 |
| ACTIVITY-028 | `ui/features/ActivityScreens.kt:158` | PairHistoryRow | `original?.let{e->{friendDetail=null;onRecord(e)}}` | none | — | 导航/打开/关闭 |
| ACTIVITY-029 | `ui/features/ActivityScreens.kt:179` | 活动与预算 | `onDismiss` | none | — | 关闭/返回不加触感 |
| ACTIVITY-030 | `ui/features/ActivityScreens.kt:181` | 活动名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-031 | `ui/features/ActivityScreens.kt:182` | 开始日期 | `{start=it}` | Select | — |  |
| ACTIVITY-032 | `ui/features/ActivityScreens.kt:182` | 结束日期 | `{end=it}` | Select | — |  |
| ACTIVITY-033 | `ui/features/ActivityScreens.kt:183` | 总预算 / 可留空 | `{amount=it}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-034 | `ui/features/ActivityScreens.kt:184` | 预算 / 可留空 | `{categoryValues=categoryValues+(c.id to it)}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-035 | `ui/features/ActivityScreens.kt:185` | 从月预算排除 | `{excluded=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| ACTIVITY-036 | `ui/features/ActivityScreens.kt:186` | 结束活动并释放预留 | `{if(old?.ended!=true)ended=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| ACTIVITY-037 | `ui/features/ActivityScreens.kt:187` | 预览影响 | `{vm.execute{review=vm.repository.previewActivity(s.book.id,A` | none | — | 打开预览/查看 |
| ACTIVITY-038 | `ui/features/ActivityScreens.kt:193` | 确认保存 | `{val r=review!!;vm.execute(onSuccess=onDismiss){check(vm.rep` | none | Done |  |
| ACTIVITY-039 | `ui/features/ActivityScreens.kt:194` | 返回修改 | `{review=null}` | none | — | 导航/打开/关闭 |
| ACTIVITY-040 | `ui/features/ActivityScreens.kt:213` | 释放预留 | `onDismiss` | none | — | 关闭/返回不加触感 |
| ACTIVITY-041 | `ui/features/ActivityScreens.kt:216` | 预留名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-042 | `ui/features/ActivityScreens.kt:216` | 使用月份日期 | `{date=it}` | Select | — |  |
| ACTIVITY-043 | `ui/features/ActivityScreens.kt:217` | 来源账户 / 可选 | `{accountId=it;accountAmount=""}` | Select | — | 组件内置：选中菜单项 |
| ACTIVITY-044 | `ui/features/ActivityScreens.kt:218` | 账户币种预留金额 | `{accountAmount=it}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-045 | `ui/features/ActivityScreens.kt:219` | 关联固定账单 / 可选 | `{occurrenceId=it}` | Select | — | 组件内置：选中菜单项 |
| ACTIVITY-046 | `ui/features/ActivityScreens.kt:221` | 本位币金额 | `{amount=it}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-047 | `ui/features/ActivityScreens.kt:222` | 预览影响 | `{vm.execute{review=if(releasing)vm.repository.previewRelease` | none | — | 打开预览/查看 |
| ACTIVITY-048 | `ui/features/ActivityScreens.kt:230` | 确认 | `{vm.execute(onSuccess=onDismiss){check(vm.repository.commitR` | none | Done |  |
| ACTIVITY-049 | `ui/features/ActivityScreens.kt:231` | 返回修改 | `{review=null}` | none | — | 导航/打开/关闭 |
| ACTIVITY-050 | `ui/features/ActivityScreens.kt:239` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| ACTIVITY-051 | `ui/features/ActivityScreens.kt:239` | YDateFields | `{_,value->onDate(value)}` | Select | — | 组件内置：选日期=Select，换月=Step |
| ACTIVITY-052 | `ui/features/ActivityScreens.kt:244` | f.name | `{included->onFriends(if(included)selected+f.id else selected` | ToggleOn/ToggleOff | — | 组件内置 |
| ACTIVITY-053 | `ui/features/ActivityScreens.kt:245` | 份额分配 | `{value->onMode(TreatSplitMode.valueOf(value))}` | Select | — | 组件内置：选中菜单项 |
| ACTIVITY-054 | `ui/features/ActivityScreens.kt:247` | 份额 | `{onWeights(weights+(id to it))}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-055 | `ui/features/ActivityScreens.kt:251` | 尾差归属 | `{onWeights(weights+("__tail" to it))}` | Select | — | 组件内置：选中菜单项 |
| ACTIVITY-056 | `ui/features/ActivityScreens.kt:270` | 别人付款 · 活动内互请 | `onDismiss` | none | — | 关闭/返回不加触感 |
| ACTIVITY-057 | `ui/features/ActivityScreens.kt:272` | 这次是什么 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-058 | `ui/features/ActivityScreens.kt:272` | 发生日期 | `{date=it}` | Select | — |  |
| ACTIVITY-059 | `ui/features/ActivityScreens.kt:273` | 付款朋友 | `{payer=it;if(it !in selected)selected=selected+it}` | Select | — | 组件内置：选中菜单项 |
| ACTIVITY-060 | `ui/features/ActivityScreens.kt:274` | 原始币种 | `{currency=it}` | Select | — | 组件内置：选中菜单项 |
| ACTIVITY-061 | `ui/features/ActivityScreens.kt:275` | 整单金额 | `{amount=it}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-062 | `ui/features/ActivityScreens.kt:276` | 本位币折算 | `{base=it}` | none | — | 系统输入法负责逐键反馈 |
| ACTIVITY-063 | `ui/features/ActivityScreens.kt:277` | TreatSplitFields | `{selected=it}` | Select | — |  |
| ACTIVITY-064 | `ui/features/ActivityScreens.kt:277` | TreatSplitFields | `{mode=it.name}` | Select | — |  |
| ACTIVITY-065 | `ui/features/ActivityScreens.kt:277` | TreatSplitFields | `{weights=it}` | none | — | 份额数值输入 |
| ACTIVITY-066 | `ui/features/ActivityScreens.kt:278` | 预览份额 | `{vm.execute { val total=Money.parse(amount,Currency.valueOf(` | none | — | 打开预览/查看 |
| ACTIVITY-067 | `ui/features/ActivityScreens.kt:288` | 确认互请记录 | `{vm.execute(onSuccess=onDismiss){check(vm.repository.commitO` | none | Done |  |
| ACTIVITY-068 | `ui/features/ActivityScreens.kt:289` | 返回修改 | `{review=null}` | none | — | 导航/打开/关闭 |
| ACTIVITY-069 | `ui/features/ActivityDeletionSheet.kt:19` | 删除活动的影响 | `{if(!busy)onClose()}` | none | — | 关闭/返回不加触感 |
| ACTIVITY-070 | `ui/features/ActivityDeletionSheet.kt:34` | 分类预算变化（${value.categoryBudgets.size}项） | `{showCategories=!showCategories}` | none | — | 展开/收起 |
| ACTIVITY-071 | `ui/features/ActivityDeletionSheet.kt:41` | 确认删除活动 | `{ var saved=false vm.execute(onSuccess={if(saved)onDeleted()` | none | Removed |  |

## 备份与恢复（6）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| BACKUP-PLATFORM-001 | `ui/features/BackupScreen.kt:84` | 选择位置并导出 | `onExport` | none | Done / Error | 导出完成 |
| BACKUP-PLATFORM-002 | `ui/features/BackupScreen.kt:89` | 选择备份文件 | `onOpen` | none | — | 导航/打开/关闭 |
| BACKUP-PLATFORM-003 | `ui/features/BackupScreen.kt:100` | 确认整份恢复 | `onDismiss` | none | — | 关闭/返回不加触感 |
| BACKUP-PLATFORM-004 | `ui/features/BackupScreen.kt:107` | 替换全部数据并重启 | `onRestore` | none | Done / Error | 恢复在重启后由结果弹层出现时播放 |
| BACKUP-PLATFORM-005 | `ui/features/BackupScreen.kt:108` | 取消，保留当前数据 | `onDismiss` | none | — | 导航/打开/关闭 |
| BACKUP-PLATFORM-006 | `platform/RestoreActivity.kt:26` | BackHandler | `{ }` | none | — | 关闭/返回不加触感 |

## 商户、地点与朋友名册（51）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| MERCHANTS-LOCATION-001 | `ui/features/MerchantsScreen.kt:31` | MerchantPlaceCard | `{selected=m.id}` | none | — | 导航/打开 |
| MERCHANTS-LOCATION-002 | `ui/features/MerchantsScreen.kt:31` | MerchantPlaceCard | `{onEntry(m.id)}` | none | — | 导航/打开 |
| MERCHANTS-LOCATION-003 | `ui/features/MerchantsScreen.kt:32` | ＋ 保存商户 | `{newName="";editing=""}` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-004 | `ui/features/MerchantsScreen.kt:35` | name | `{newName=name;editing=""}` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-005 | `ui/features/MerchantsScreen.kt:37` | 全部商户 | `{selected=null}` | none | — | 导航/打开 |
| MERCHANTS-LOCATION-006 | `ui/features/MerchantsScreen.kt:41` | 编辑原名与备注 | `{editing=merchant.id}` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-007 | `ui/features/MerchantsScreen.kt:44` | 删除商户 | `{deleting=true}` | none | — | 打开删除审阅 |
| MERCHANTS-LOCATION-008 | `ui/features/MerchantsScreen.kt:45` | 删除商户 | `{deleting=false}` | none | — | 关闭/返回不加触感 |
| MERCHANTS-LOCATION-009 | `ui/features/MerchantsScreen.kt:50` | 确认删除商户 | `{vm.execute(onSuccess={ vm.entry.value?.takeIf{it.merchantId` | none | Removed |  |
| MERCHANTS-LOCATION-010 | `ui/features/MerchantsScreen.kt:60` | 编辑地点 | `{locationId=l.id;locationFor=merchant.id}` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-011 | `ui/features/MerchantsScreen.kt:61` | 移除保存地点 | `{vm.execute{vm.repository.removeMerchantLocation(s.book.id,l` | none | Removed |  |
| MERCHANTS-LOCATION-012 | `ui/features/MerchantsScreen.kt:64` | ＋ 添加地点 | `{locationId=null;locationFor=merchant.id}` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-013 | `ui/features/MerchantsScreen.kt:69` | EventRow | `{onRecord(e)}` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-014 | `ui/features/MerchantsScreen.kt:72` | EventRow | `{onRecord(e)}` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-015 | `ui/features/MerchantsScreen.kt:88` | 商户资料 | `onDismiss` | none | — | 关闭/返回不加触感 |
| MERCHANTS-LOCATION-016 | `ui/features/MerchantsScreen.kt:89` | 商户原名 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| MERCHANTS-LOCATION-017 | `ui/features/MerchantsScreen.kt:90` | 备注 | `{note=it}` | none | — | 系统输入法负责逐键反馈 |
| MERCHANTS-LOCATION-018 | `ui/features/MerchantsScreen.kt:91` | 启用候选选择 | `{active=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| MERCHANTS-LOCATION-019 | `ui/features/MerchantsScreen.kt:97` | 保留地点 | `{choices[index]=choices[index].copy(keep=it)}` | ToggleOn/ToggleOff | — | 组件内置 |
| MERCHANTS-LOCATION-020 | `ui/features/MerchantsScreen.kt:98` | 地点名称 | `{choices[index]=choices[index].copy(name=it)}` | none | — | 系统输入法负责逐键反馈 |
| MERCHANTS-LOCATION-021 | `ui/features/MerchantsScreen.kt:100` | 在地图上调整 | `{adjusting=index}` | none | — | 导航/打开 |
| MERCHANTS-LOCATION-022 | `ui/features/MerchantsScreen.kt:104` | 保存商户资料 | `{vm.execute(onSuccess=onDismiss){ vm.repository.saveMerchant` | none | Done |  |
| MERCHANTS-LOCATION-023 | `ui/features/MerchantsScreen.kt:112` | 调整地点 | `{adjusting=null}` | none | — | 关闭/返回不加触感 |
| MERCHANTS-LOCATION-024 | `ui/features/MerchantsScreen.kt:113` | PlacePicker | `{choices[index]=choices[index].copy(radius=it)}` | none | — | 地图平移/半径连续调整 |
| MERCHANTS-LOCATION-025 | `ui/features/MerchantsScreen.kt:113` | PlacePicker | `{lat,lon->choices[index]=choices[index].copy(latitude=lat,lo` | none | — | 地图平移/半径连续调整 |
| MERCHANTS-LOCATION-026 | `ui/features/MerchantsScreen.kt:114` | 完成 | `{adjusting=null}` | none | — | 导航/打开 |
| MERCHANTS-LOCATION-027 | `ui/features/MerchantsScreen.kt:124` | 保存商户地点 | `onDismiss` | none | — | 关闭/返回不加触感 |
| MERCHANTS-LOCATION-028 | `ui/features/MerchantsScreen.kt:125` | 地点名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| MERCHANTS-LOCATION-029 | `ui/features/MerchantsScreen.kt:126` | PlacePicker | `{radius=it}` | none | — | 地图平移/半径连续调整 |
| MERCHANTS-LOCATION-030 | `ui/features/MerchantsScreen.kt:126` | PlacePicker | `{lat,lon->latitude=lat;longitude=lon}` | none | — | 地图平移/半径连续调整 |
| MERCHANTS-LOCATION-031 | `ui/features/MerchantsScreen.kt:127` | 保存地点 | `{vm.execute(onSuccess=onDismiss){ vm.repository.saveMerchant` | none | Done |  |
| MERCHANTS-LOCATION-032 | `ui/features/MerchantsScreen.kt:146` | FriendDirectoryRow | `{selected=f.id}` | none | — | 导航/打开 |
| MERCHANTS-LOCATION-033 | `ui/features/MerchantsScreen.kt:148` | 返回朋友名册 | `{selected=null}` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-034 | `ui/features/MerchantsScreen.kt:152` |  ·  | `{vm.openActivityFriend.value=a.id to friend.id;vm.navigate("` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-035 | `ui/features/MerchantsScreen.kt:154` | 编辑朋友 | `{editing=friend.id}` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-036 | `ui/features/MerchantsScreen.kt:157` | ＋ 添加朋友 | `{editing=""}` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-037 | `ui/features/MerchantsScreen.kt:163` | 朋友资料 | `{editing=null}` | none | — | 关闭/返回不加触感 |
| MERCHANTS-LOCATION-038 | `ui/features/MerchantsScreen.kt:164` | 朋友名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| MERCHANTS-LOCATION-039 | `ui/features/MerchantsScreen.kt:164` | 启用名册选择 | `{active=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| MERCHANTS-LOCATION-040 | `ui/features/MerchantsScreen.kt:165` | 保存朋友资料 | `{vm.execute(onSuccess={editing=null}){val saved=vm.repositor` | none | Done |  |
| MERCHANTS-LOCATION-041 | `ui/features/ForegroundLocationControls.kt:88` | 打开系统位置设置 | `{ context.startActivity(if(systemOff)Intent(Settings.ACTION_` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-042 | `ui/features/ForegroundLocationControls.kt:128` | LocatedMerchantChip | `{ onChange(d.copy(merchant=candidate.merchant.name,merchantI` | none | — | 导航/打开/关闭 |
| MERCHANTS-LOCATION-043 | `ui/features/ForegroundLocationControls.kt:135` | 重新定位 | `locate` | none | — | 导航/打开 |
| MERCHANTS-LOCATION-044 | `ui/features/ForegroundLocationControls.kt:136` | 关闭自动定位 | `{request.stop();vm.execute{vm.preferences.setForegroundLocat` | ToggleOff | — | 关闭自动定位 |
| MERCHANTS-LOCATION-045 | `ui/features/ForegroundLocationControls.kt:138` | 启用定位，匹配保存商户 | `{ vm.execute(onSuccess=locate){vm.preferences.setForegroundL` | ToggleOn | — | 启用定位 |
| MERCHANTS-LOCATION-046 | `ui/features/ForegroundLocationControls.kt:148` | 记账时根据当前位置匹配商户 | `{enabled-> if(!enabled)request.stop() vm.execute(onSuccess={` | ToggleOn/ToggleOff | — | 组件内置 |
| MERCHANTS-LOCATION-047 | `ui/features/ForegroundLocationControls.kt:153` | 查看当前位置候选 | `locate` | none | — | 打开预览/查看 |
| MERCHANTS-LOCATION-048 | `ui/features/ForegroundLocationControls.kt:158` | LocatedMerchantChip | `{onEntry(candidate.merchant.id)}` | Select | — | 选中附近商户 |
| MERCHANTS-LOCATION-049 | `ui/features/ForegroundLocationControls.kt:169` | 回到当前位置 | `locate` | none | — | 导航/打开 |
| MERCHANTS-LOCATION-050 | `ui/features/PlacePicker.kt:90` | CapturePlaceLocation | `{fix-> val target=LatLng(fix.latitude,fix.longitude);request` | none | — | 导航/打开 |
| MERCHANTS-LOCATION-051 | `ui/features/PlacePicker.kt:95` | 匹配半径 / 米 | `onRadius` | none | — | 系统输入法负责逐键反馈 |

## 附件与小票（62）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| MEDIA-RECEIPT-001 | `ui/features/MediaPanels.kt:59` | MediaFileRow | `{preview=id}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-002 | `ui/features/MediaPanels.kt:59` | MediaFileRow | `{vm.changeEntry(s.book.id,d.copy(attachmentIds=d.attachmentI` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-003 | `ui/features/MediaPanels.kt:61` | 添加文件 / 每个小于100 MB | `{attachments.launch(Unit)}` | none | — | 导航/打开/关闭 |
| MEDIA-RECEIPT-004 | `ui/features/MediaPanels.kt:66` | MaterialReceiptImage | `{preview=id}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-005 | `ui/features/MediaPanels.kt:66` | MaterialReceiptImage | `{vm.changeEntry(s.book.id,d.copy(receiptFileIds=d.receiptFil` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-006 | `ui/features/MediaPanels.kt:66` | MaterialReceiptImage | `if(index>0&&!busy)({move(index-1)})else null` | none | — | 导航/打开/关闭 |
| MEDIA-RECEIPT-007 | `ui/features/MediaPanels.kt:66` | MaterialReceiptImage | `if(index<d.receiptFileIds.lastIndex&&!busy)({move(index+1)})` | none | — | 导航/打开/关闭 |
| MEDIA-RECEIPT-008 | `ui/features/MediaPanels.kt:73` | 选择图片 | `{receipts.launch(Unit)}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-009 | `ui/features/MediaPanels.kt:74` | 拍照 | `{camera(s.book.id,ReceiptTarget.Draft(d.mediaSession))}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-010 | `ui/features/MediaPanels.kt:78` | MediaPreview | `{preview=null}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-011 | `ui/features/MediaPanels.kt:85` | 原样保存（不压缩） | `{vm.setMediaImportOption(d.mediaSession,it)}` | ToggleOn/ToggleOff | — | 组件内置 |
| MEDIA-RECEIPT-012 | `ui/features/MediaPanels.kt:115` | MaterialFileTile | `onPreview` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-013 | `ui/features/MediaPanels.kt:115` | MaterialFileTile | `onRemove` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-014 | `ui/features/MediaPanels.kt:123` | MediaFileRow | `{preview=file.id}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-015 | `ui/features/MediaPanels.kt:128` | MaterialReceiptImage | `{preview=file.id}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-016 | `ui/features/MediaPanels.kt:132` | 打开小票工作台 | `{vm.navigate("receipts")}` | none | — | 导航/打开/关闭 |
| MEDIA-RECEIPT-017 | `ui/features/MediaPanels.kt:135` | MediaPreview | `{preview=null}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-018 | `ui/features/MediaPanels.kt:148` | 选择图片 | `{picker.launch(Unit)}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-019 | `ui/features/MediaPanels.kt:149` | 拍照 | `{camera(s.book.id,ReceiptTarget.Record(event.id))}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-020 | `ui/features/MediaPanels.kt:194` | 音频附件 | `onDismiss` | none | — | 关闭/返回不加触感 |
| MEDIA-RECEIPT-021 | `ui/features/MediaPanels.kt:198` | ImageViewer | `onPrevious` | Step | — | 上一张/下一张=Step；缩放=none |
| MEDIA-RECEIPT-022 | `ui/features/MediaPanels.kt:198` | ImageViewer | `onNext` | Step | — | 上一张/下一张=Step；缩放=none |
| MEDIA-RECEIPT-023 | `ui/features/MediaPanels.kt:198` | ImageViewer | `{ if(scale!=1f\|\|x!=0f\|\|y!=0f){scale=1f;x=0f;y=0f}else scale=` | Step | — | 上一张/下一张=Step；缩放=none |
| MEDIA-RECEIPT-024 | `ui/features/MediaPanels.kt:212` | 在其他应用打开 | `::external` | none | — | 导航/打开/关闭 |
| MEDIA-RECEIPT-025 | `ui/features/MediaPanels.kt:213` | ← 返回附件列表 | `onDismiss` | none | — | 导航/打开/关闭 |
| MEDIA-RECEIPT-026 | `ui/features/MediaPanels.kt:234` | ThemedAudio | `{ val media=player!! if(playing)media.pause()else {if(positi` | Media / Grab | — | 播放暂停=Media；按下进度条=Grab，拖动中不震 |
| MEDIA-RECEIPT-027 | `ui/features/MediaPanels.kt:234` | ThemedAudio | `{position=it;player?.seekTo(it)}` | Media / Grab | — | 播放暂停=Media；按下进度条=Grab，拖动中不震 |
| MEDIA-RECEIPT-028 | `ui/features/ReceiptWorkflowScreen.kt:35` | 应用不识别小票 · 查看整理说明 | `{showNote=!showNote}` | none | — | 打开预览/查看 |
| MEDIA-RECEIPT-029 | `ui/features/ReceiptWorkflowScreen.kt:37` | ReceiptInboxTabs | `{tab=it}` | TabSwitch | — | 组件内置 |
| MEDIA-RECEIPT-030 | `ui/features/ReceiptWorkflowScreen.kt:39` | 全选待整理 | `{selected=if(pending.all{it in selected})emptyList()else pen` | Select | — | 选择小票 |
| MEDIA-RECEIPT-031 | `ui/features/ReceiptWorkflowScreen.kt:47` | ReceiptInboxCard | `if(receipt.status!="SORTED"&&!busy)({selected=if(receipt.id ` | Select | — | 选择小票 |
| MEDIA-RECEIPT-032 | `ui/features/ReceiptWorkflowScreen.kt:47` | ReceiptInboxCard | `{onRecord(event)}` | none | — | 导航/打开/关闭 |
| MEDIA-RECEIPT-033 | `ui/features/ReceiptWorkflowScreen.kt:50` | ReceiptThumbnailFrame | `{imagePreview=file.id}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-034 | `ui/features/ReceiptWorkflowScreen.kt:54` | MediaPreview | `{imagePreview=null}` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-035 | `ui/features/ReceiptWorkflowScreen.kt:62` | 确认替换已有商品明细 | `{if(!busy)confirming=false}` | none | — | 关闭/返回不加触感 |
| MEDIA-RECEIPT-036 | `ui/features/ReceiptWorkflowScreen.kt:65` | 替换并保存 | `{onSave(needsStale,needsReplacement)}` | none | Done | 保存商品明细 |
| MEDIA-RECEIPT-037 | `ui/features/ReceiptWorkflowScreen.kt:67` | 取消，重新选择 | `onReselect` | none | — | 导航/打开/关闭 |
| MEDIA-RECEIPT-038 | `ui/features/ReceiptWorkflowScreen.kt:68` | 返回预览 | `{confirming=false}` | none | — | 打开预览/查看 |
| MEDIA-RECEIPT-039 | `ui/features/ReceiptWorkflowScreen.kt:73` | 确认商品明细 | `onClose` | none | — | 关闭/返回不加触感 |
| MEDIA-RECEIPT-040 | `ui/features/ReceiptWorkflowScreen.kt:91` | 确认保存商品明细 | `{if(needsStale\|\|needsReplacement)confirming=true else onSave` | none | Done | 保存商品明细 |
| MEDIA-RECEIPT-041 | `ui/features/ReceiptWorkflowScreen.kt:92` | 重新选择文件 | `onReselect` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-042 | `ui/features/ReceiptProducts.kt:112` | 手工填写商品明细 | `{editor=true}` | none | — | 导航/打开/关闭 |
| MEDIA-RECEIPT-043 | `ui/features/ReceiptProducts.kt:124` | 编辑买过的东西 | `{if(!busy)onClose()}` | none | — | 关闭/返回不加触感 |
| MEDIA-RECEIPT-044 | `ui/features/ReceiptProducts.kt:127` | ReceiptEditorItem | `{update(edited.copy(items=edited.items.filterIndexed{i,_->i!` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-045 | `ui/features/ReceiptProducts.kt:128` | 商品名 | `{v->updateItem(index){it.copy(name=v.ifBlank{null})}}` | none | — | 系统输入法负责逐键反馈 |
| MEDIA-RECEIPT-046 | `ui/features/ReceiptProducts.kt:130` | 数量 | `{v->updateItem(index){it.copy(quantity=v.ifBlank{null})}}` | none | — | 系统输入法负责逐键反馈 |
| MEDIA-RECEIPT-047 | `ui/features/ReceiptProducts.kt:131` | 单价 | `{v->updateItem(index){it.copy(unit_price=v.ifBlank{null})}}` | none | — | 系统输入法负责逐键反馈 |
| MEDIA-RECEIPT-048 | `ui/features/ReceiptProducts.kt:132` | 行金额 | `{v->updateItem(index){it.copy(line_amount=v.ifBlank{null})}}` | none | — | 系统输入法负责逐键反馈 |
| MEDIA-RECEIPT-049 | `ui/features/ReceiptProducts.kt:135` | 单位 | `{v->updateItem(index){it.copy(unit=v.ifBlank{null})}}` | none | — | 系统输入法负责逐键反馈 |
| MEDIA-RECEIPT-050 | `ui/features/ReceiptProducts.kt:136` | 不确定字段（逗号分隔） | `{v->updateItem(index){it.copy(uncertain_fields=v.split(',').` | none | — | 系统输入法负责逐键反馈 |
| MEDIA-RECEIPT-051 | `ui/features/ReceiptProducts.kt:137` | 说明 | `{v->updateItem(index){it.copy(note=v.ifBlank{null})}}` | none | — | 系统输入法负责逐键反馈 |
| MEDIA-RECEIPT-052 | `ui/features/ReceiptProducts.kt:139` | ＋ 增加商品 | `{update(edited.copy(items=edited.items+ReceiptItem(vm.reposi` | none | — | 打开预览/外部选择/草稿内增删 |
| MEDIA-RECEIPT-053 | `ui/features/ReceiptProducts.kt:142` | 调整类型 | `{type->update(edited.copy(adjustments=edited.adjustments.map` | Select | — | 组件内置：选中菜单项 |
| MEDIA-RECEIPT-054 | `ui/features/ReceiptProducts.kt:143` | 调整金额 | `{amount->update(edited.copy(adjustments=edited.adjustments.m` | none | — | 系统输入法负责逐键反馈 |
| MEDIA-RECEIPT-055 | `ui/features/ReceiptProducts.kt:144` | 已含于商品行 | `{included->update(edited.copy(adjustments=edited.adjustments` | ToggleOn/ToggleOff | — | 组件内置 |
| MEDIA-RECEIPT-056 | `ui/features/ReceiptProducts.kt:145` | 移除此调整 | `{update(edited.copy(adjustments=edited.adjustments.filterInd` | none | — | 打开删除审阅 |
| MEDIA-RECEIPT-057 | `ui/features/ReceiptProducts.kt:147` | ＋ 添加税费或折扣 | `{update(edited.copy(adjustments=edited.adjustments+ReceiptAd` | none | — | 导航/打开/关闭 |
| MEDIA-RECEIPT-058 | `ui/features/ReceiptProducts.kt:148` | 票面合计 | `{update(edited.copy(receipt_total=it.ifBlank{null}))}` | none | — | 系统输入法负责逐键反馈 |
| MEDIA-RECEIPT-059 | `ui/features/ReceiptProducts.kt:149` | 待确认说明（每行一项） | `{update(edited.copy(issues=it.lines().filter{line->line.isNo` | none | — | 系统输入法负责逐键反馈 |
| MEDIA-RECEIPT-060 | `ui/features/ReceiptProducts.kt:150` | 明细已补全 | `{update(edited.copy(complete=it))}` | ToggleOn/ToggleOff | — | 组件内置 |
| MEDIA-RECEIPT-061 | `ui/features/ReceiptProducts.kt:152` | 保存商品明细 | `{vm.execute(onSuccess=onClose){if(baseRevision==0)vm.reposit` | none | Done |  |
| MEDIA-RECEIPT-062 | `ui/features/ReceiptProducts.kt:153` | 取消修改 | `onClose` | none | — | 导航/打开/关闭 |

## CSV 导入（40）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| CSV-IMPORT-001 | `ui/features/CsvImportScreen.kt:33` | 选择 CSV 文件 | `{select.launch(arrayOf("*/*"))}` | none | — | 导航/打开 |
| CSV-IMPORT-002 | `ui/features/CsvImportScreen.kt:43` | 编码 | `{model.decode(encoding=it,delimiter=null)}` | Select | — | 组件内置：选中菜单项 |
| CSV-IMPORT-003 | `ui/features/CsvImportScreen.kt:45` | 分隔符 | `{model.decode(delimiter=it)}` | Select | — | 组件内置：选中菜单项 |
| CSV-IMPORT-004 | `ui/features/CsvImportScreen.kt:46` | 第一行是表头 | `{model.decode(header=it)}` | ToggleOn/ToggleOff | — | 组件内置 |
| CSV-IMPORT-005 | `ui/features/CsvImportScreen.kt:49` | CsvColumns | `model::configure` | Select | — | 列映射 |
| CSV-IMPORT-006 | `ui/features/CsvImportScreen.kt:50` | CsvMappings | `model::configure` | Select | — | 列映射 |
| CSV-IMPORT-007 | `ui/features/CsvImportScreen.kt:50` | CsvMappings | `{newAccount=true}` | none | — | 导航/打开/关闭 |
| CSV-IMPORT-008 | `ui/features/CsvImportScreen.kt:51` | CsvReview | `model::edit` | none | — | 导航/打开 |
| CSV-IMPORT-009 | `ui/features/CsvImportScreen.kt:53` | 上一步 | `{model.step(state.step-1)}` | none | — | 导航/打开 |
| CSV-IMPORT-010 | `ui/features/CsvImportScreen.kt:54` | 生成预览 | `{model.step(state.step+1)}` | none | — | 打开预览/查看 |
| CSV-IMPORT-011 | `ui/features/CsvImportScreen.kt:55` | 确认迁入正式账目 | `{confirm=true}` | none | — | 导航/打开/关闭 |
| CSV-IMPORT-012 | `ui/features/CsvImportScreen.kt:63` | 查看批次记录 | `{model.events(batch)}` | none | — | 打开预览/查看 |
| CSV-IMPORT-013 | `ui/features/CsvImportScreen.kt:64` | 撤销整批 | `{undo=batch}` | none | — | 导航/打开 |
| CSV-IMPORT-014 | `ui/features/CsvImportScreen.kt:67` | 确认 CSV 迁入 | `{confirm=false}` | none | — | 关闭/返回不加触感 |
| CSV-IMPORT-015 | `ui/features/CsvImportScreen.kt:70` | 确认导入 | `{confirm=false;model.importBatch()}` | none | Done / Error | 迁入完成（部分失败=Warning） |
| CSV-IMPORT-016 | `ui/features/CsvImportScreen.kt:72` | 撤销整批 | `{undo=null}` | none | — | 关闭/返回不加触感 |
| CSV-IMPORT-017 | `ui/features/CsvImportScreen.kt:74` | 移至回收站 | `{undo=null;model.undo(batch)}` | none | Removed | 撤销整批（移至回收站） |
| CSV-IMPORT-018 | `ui/features/CsvImportScreen.kt:76` | 批次记录 | `model::closeEvents` | none | — | 关闭/返回不加触感 |
| CSV-IMPORT-019 | `ui/features/CsvImportScreen.kt:80` | CsvPager | `{page=it}` | Step | — | 翻页 |
| CSV-IMPORT-020 | `ui/features/CsvImportScreen.kt:81` | 前往回收站恢复 | `{model.closeEvents();vm.navigate("trash")}` | none | — | 打开删除审阅 |
| CSV-IMPORT-021 | `ui/features/CsvImportScreen.kt:89` | names[i] | `{col->onChange(settings.copy(columns=if(col<0)settings.colum` | Select | — | 组件内置：选中菜单项 |
| CSV-IMPORT-022 | `ui/features/CsvImportScreen.kt:90` | 日期格式 | `{onChange(settings.copy(dateFormat=it))}` | Select | — | 组件内置：选中菜单项 |
| CSV-IMPORT-023 | `ui/features/CsvImportScreen.kt:91` | 收支判断 | `{onChange(settings.copy(rule=it))}` | Select | — | 组件内置：选中菜单项 |
| CSV-IMPORT-024 | `ui/features/CsvImportScreen.kt:95` | 空值 | `{onChange(settings.copy(types=settings.types+(value to it)))` | Select | — | 组件内置：选中菜单项 |
| CSV-IMPORT-025 | `ui/features/CsvImportScreen.kt:97` | 备注同时作为商户名 | `{onChange(settings.copy(noteAsMerchant=it))}` | ToggleOn/ToggleOff | — | 组件内置 |
| CSV-IMPORT-026 | `ui/features/CsvImportScreen.kt:103` | 目标账户（未对应时使用） | `{onChange(settings.copy(accountId=it))}` | Select | — | 组件内置：选中菜单项 |
| CSV-IMPORT-027 | `ui/features/CsvImportScreen.kt:105` | 新建账户 | `onNewAccount` | none | — | 导航/打开/关闭 |
| CSV-IMPORT-028 | `ui/features/CsvImportScreen.kt:107` | 账户值： | `{onChange(settings.copy(accounts=settings.accounts+(value to` | Select | — | 组件内置：选中菜单项 |
| CSV-IMPORT-029 | `ui/features/CsvImportScreen.kt:119` | label | `::update` | Select | — | 组件内置：选中菜单项 |
| CSV-IMPORT-030 | `ui/features/CsvImportScreen.kt:120` | 新分类名称 | `{update("NEW:$it")}` | none | — | 系统输入法负责逐键反馈 |
| CSV-IMPORT-031 | `ui/features/CsvImportScreen.kt:145` | 显示全部行（含已跳过） | `{all=it;page=0}` | ToggleOn/ToggleOff | — | 组件内置 |
| CSV-IMPORT-032 | `ui/features/CsvImportScreen.kt:155` | 跳过此行 | `{onEdit(row.source.row,(state.edits[row.source.row]?:CsvEdit` | ToggleOn/ToggleOff | — | 组件内置 |
| CSV-IMPORT-033 | `ui/features/CsvImportScreen.kt:156` | 修改金额 / 日期 | `{editing=row}` | none | — | 导航/打开/关闭 |
| CSV-IMPORT-034 | `ui/features/CsvImportScreen.kt:158` | CsvPager | `{page=it}` | Step | — | 翻页 |
| CSV-IMPORT-035 | `ui/features/CsvImportScreen.kt:162` | 修正此行 | `{editing=null}` | none | — | 关闭/返回不加触感 |
| CSV-IMPORT-036 | `ui/features/CsvImportScreen.kt:163` | 日期 | `{date=it}` | none | — | 系统输入法负责逐键反馈 |
| CSV-IMPORT-037 | `ui/features/CsvImportScreen.kt:164` | 金额 | `{amount=it}` | none | — | 系统输入法负责逐键反馈 |
| CSV-IMPORT-038 | `ui/features/CsvImportScreen.kt:165` | 保存并重新检查 | `{editing=null;onEdit(row.source.row,(state.edits[row.source.` | none | — | 打开预览/查看 |
| CSV-IMPORT-039 | `ui/features/CsvImportScreen.kt:172` | 上一页 | `{onPage((page-1).coerceAtLeast(0))}` | Step | — |  |
| CSV-IMPORT-040 | `ui/features/CsvImportScreen.kt:174` | 下一页 | `{onPage(page+1)}` | Step | — |  |

## 流水核对（42）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| STATEMENTS-001 | `ui/features/StatementImportScreen.kt:55` | 查看已有核对资料 | `{vm.selectedStatementBatch.value=p.duplicateBatchId;vm.navig` | none | — | 打开预览/查看 |
| STATEMENTS-002 | `ui/features/StatementImportScreen.kt:59` | 确认保留独立重叠批次 | `onOverlap` | ToggleOn/ToggleOff | — | 组件内置 |
| STATEMENTS-003 | `ui/features/StatementImportScreen.kt:62` | 确认导入，开始核对 | `onConfirm` | none | Done |  |
| STATEMENTS-004 | `ui/features/StatementImportScreen.kt:63` | 放弃预览 | `onCancel` | none | — | 打开删除审阅 |
| STATEMENTS-005 | `ui/features/StatementsScreen.kt:53` | clickable | `{vm.navigate("statement-import")}` | none | — | 导航/打开/关闭 |
| STATEMENTS-006 | `ui/features/StatementsScreen.kt:57` | 核对批次 | `{vm.selectedStatementBatch.value=it}` | Select | — | 组件内置：选中菜单项 |
| STATEMENTS-007 | `ui/features/StatementsScreen.kt:88` | 对应记账： | `{onRecord(event)}` | none | — | 导航/打开/关闭 |
| STATEMENTS-008 | `ui/features/StatementsScreen.kt:91` | 查看候选与差异 | `{rowId=row.row_id}` | none | — | 打开预览/查看 |
| STATEMENTS-009 | `ui/features/StatementsScreen.kt:92` | 选择类型补记 | `{supplement=row.row_id}` | none | — | 导航/打开 |
| STATEMENTS-010 | `ui/features/StatementsScreen.kt:109` | 选择正式账单并预览确认 | `{billChooser=true}` | none | — | 打开预览/查看 |
| STATEMENTS-011 | `ui/features/StatementsScreen.kt:111` | 保存完整原文件副本 | `{export.launch(null)}` | none | — | 导航/打开 |
| STATEMENTS-012 | `ui/features/StatementsScreen.kt:112` | 删除此核对资料 | `{deleting=true}` | none | — | 打开删除审阅 |
| STATEMENTS-013 | `ui/features/StatementsScreen.kt:115` | 删除核对资料 | `{deleting=false}` | none | — | 关闭/返回不加触感 |
| STATEMENTS-014 | `ui/features/StatementsScreen.kt:117` | 确认删除资料 | `{vm.execute(onSuccess={deleting=false}){vm.repository.delete` | none | Removed |  |
| STATEMENTS-015 | `ui/features/StatementsScreen.kt:129` | 查看转账 / 还款两端关联 | `{showSides=true}` | none | — | 打开预览/查看 |
| STATEMENTS-016 | `ui/features/StatementsScreen.kt:131` | 同一交易，两端各核对 | `{showSides=false}` | none | — | 关闭/返回不加触感 |
| STATEMENTS-017 | `ui/features/StatementsScreen.kt:134` | 查看原记账 | `{showSides=false;onRecord(event)}` | none | — | 打开预览/查看 |
| STATEMENTS-018 | `ui/features/StatementsScreen.kt:144` |  ·  | `{onRecord(event)}` | none | — | 导航/打开/关闭 |
| STATEMENTS-019 | `ui/features/StatementDifferenceSheets.kt:31` | 待确认差异 | `{if(!busy)onClose()}` | none | — | 关闭/返回不加触感 |
| STATEMENTS-020 | `ui/features/StatementDifferenceSheets.kt:50` | 解除此人工关联 | `{vm.execute(onSuccess=onClose){vm.repository.clearStatementD` | none | Removed | 解除人工关联 |
| STATEMENTS-021 | `ui/features/StatementDifferenceSheets.kt:60` | 确认保留差异 | `{vm.execute(onSuccess=onClose){vm.repository.acceptStatement` | none | Done |  |
| STATEMENTS-022 | `ui/features/StatementDifferenceSheets.kt:61` | 返回选择 | `{review=null}` | none | — | 导航/打开/关闭 |
| STATEMENTS-023 | `ui/features/StatementDifferenceSheets.kt:65` | 实际资金端候选 | `{selected=it}` | Select | — | 组件内置：选中菜单项 |
| STATEMENTS-024 | `ui/features/StatementDifferenceSheets.kt:68` | 差异说明 | `{note=it}` | none | — | 系统输入法负责逐键反馈 |
| STATEMENTS-025 | `ui/features/StatementDifferenceSheets.kt:69` | 预览人工差异决定 | `{var r:StatementDifferenceReview?=null;vm.execute(onSuccess=` | none | — | 打开预览/查看 |
| STATEMENTS-026 | `ui/features/StatementDifferenceSheets.kt:82` | YDateFields | `{dateOwner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| STATEMENTS-027 | `ui/features/StatementDifferenceSheets.kt:82` | YDateFields | `{_,value->posted=value}` | Select | — | 组件内置：选日期=Select，换月=Step |
| STATEMENTS-028 | `ui/features/StatementDifferenceSheets.kt:83` | 已确认实际入账日 | `{confirmedDate=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| STATEMENTS-029 | `ui/features/StatementDifferenceSheets.kt:85` | 查看原还款，确认平台清偿 | `{onRepayment(event)}` | none | — | 打开预览/查看 |
| STATEMENTS-030 | `ui/features/StatementDifferenceSheets.kt:88` | 进入原记录，预览实际确认 | `{onActual(event.id,posted)}` | none | — | 打开预览/查看 |
| STATEMENTS-031 | `ui/features/StatementDifferenceSheets.kt:121` | 先选补记类型 | `onClose` | none | — | 关闭/返回不加触感 |
| STATEMENTS-032 | `ui/features/StatementDifferenceSheets.kt:124` | 补记类型 | `{if(kind!=it){kind=it;original=null;debtAccount=account.id.t` | Select | — | 组件内置：选中菜单项 |
| STATEMENTS-033 | `ui/features/StatementDifferenceSheets.kt:126` | 债务账户 | `{debtAccount=it}` | Select | — | 组件内置：选中菜单项 |
| STATEMENTS-034 | `ui/features/StatementDifferenceSheets.kt:128` | 打开正式金融表单 | `{onFinancial(kind!!,debtAccount!!)}` | none | — | 导航/打开/关闭 |
| STATEMENTS-035 | `ui/features/StatementDifferenceSheets.kt:130` | 关联原消费 | `{original=it}` | Select | — | 组件内置：选中菜单项 |
| STATEMENTS-036 | `ui/features/StatementDifferenceSheets.kt:131` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| STATEMENTS-037 | `ui/features/StatementDifferenceSheets.kt:131` | YDateFields | `{i,date->if(i==0)occurred=date else posted=date}` | Select | — | 组件内置：选日期=Select，换月=Step |
| STATEMENTS-038 | `ui/features/StatementDifferenceSheets.kt:134` | 已核对缺少的日期 | `{dateConfirmed=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| STATEMENTS-039 | `ui/features/StatementDifferenceSheets.kt:136` | 打开普通记账表单 | `{onStart(StatementSupplementSeed(s.book.id,account.id,row,En` | none | — | 导航/打开/关闭 |
| STATEMENTS-040 | `ui/features/StatementDifferenceSheets.kt:147` | 选择账单补充资料的目标 | `onClose` | none | — | 关闭/返回不加触感 |
| STATEMENTS-041 | `ui/features/StatementDifferenceSheets.kt:151` |  ·  | `{onSelect(bill.id)}` | Select | — |  |
| STATEMENTS-042 | `ui/features/StatementDifferenceSheets.kt:152` | 打开金融账户管理 | `onManage` | none | — | 导航/打开/关闭 |

## 云端交接台（61）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| CLOUD-001 | `ui/features/CloudScreens.kt:58` | CloudConnectionCard | `{connect=true}` | none | — | 导航/打开/关闭 |
| CLOUD-002 | `ui/features/CloudScreens.kt:58` | CloudConnectionCard | `{vm.execute(onSuccess={notice="连接正常 / 接続できました"}){cloud.testC` | none | Done / Error | 连接测试 |
| CLOUD-003 | `ui/features/CloudScreens.kt:58` | CloudConnectionCard | `{connection?.let{context.startActivity(Intent(Intent.ACTION_` | none | — | 打开/外部/刷新（成功不震，失败 Error） |
| CLOUD-004 | `ui/features/CloudScreens.kt:58` | CloudConnectionCard | `{unbind=true}` | none | — | 导航/打开/关闭 |
| CLOUD-005 | `ui/features/CloudScreens.kt:65` | CloudTaskCard | `{cloud.enqueue(view.task.localId);notice="已重新排队上传 / 再アップロードを` | none | Done | 重新排队上传 |
| CLOUD-006 | `ui/features/CloudScreens.kt:65` | CloudTaskCard | `{vm.execute{cloud.refresh(view.task.localId)}}` | none | — | 打开/外部/刷新（成功不震，失败 Error） |
| CLOUD-007 | `ui/features/CloudScreens.kt:65` | CloudTaskCard | `{var result:CloudClaim?=null;var commandId:String?=null vm.e` | none | Done / Error | 领取并打开预览 |
| CLOUD-008 | `ui/features/CloudScreens.kt:65` | CloudTaskCard | `{supplementFor=view.task.localId;supplement.launch(arrayOf("` | none | — | 打开/外部/刷新（成功不震，失败 Error） |
| CLOUD-009 | `ui/features/CloudScreens.kt:65` | CloudTaskCard | `{vm.execute{cloud.discard(view.task.localId)}}` | none | Removed |  |
| CLOUD-010 | `ui/features/CloudScreens.kt:76` | 新建通用分析任务 | `{general=true}` | none | — | 导航/打开/关闭 |
| CLOUD-011 | `ui/features/CloudScreens.kt:79` | 解除这台设备的连接 | `{if(!busy)unbind=false}` | none | — | 关闭/返回不加触感 |
| CLOUD-012 | `ui/features/CloudScreens.kt:82` | 解除连接 | `{vm.execute(onSuccess={unbind=false}){cloud.unbind()}}` | none | Removed |  |
| CLOUD-013 | `ui/features/CloudScreens.kt:83` | 保留连接 | `{unbind=false}` | none | — | 导航/打开 |
| CLOUD-014 | `ui/features/CloudScreens.kt:151` | 导入连接配置 | `onImport` | none | — | 打开/外部/刷新（成功不震，失败 Error） |
| CLOUD-015 | `ui/features/CloudScreens.kt:154` | 测试连接 | `onTest` | none | Done / Error | 连接测试 |
| CLOUD-016 | `ui/features/CloudScreens.kt:155` | 打开网页工作台 | `onOpen` | none | — | 导航/打开/关闭 |
| CLOUD-017 | `ui/features/CloudScreens.kt:156` | 解除 | `onUnbind` | none | — | 打开/外部/刷新（成功不震，失败 Error） |
| CLOUD-018 | `ui/features/CloudScreens.kt:256` | 领取结果并预览 | `onClaim` | none | — | 打开/外部/刷新（成功不震，失败 Error） |
| CLOUD-019 | `ui/features/CloudScreens.kt:257` | 继续上传 | `onRetry` | none | Done | 重新排队上传 |
| CLOUD-020 | `ui/features/CloudScreens.kt:258` | 补充资料 | `onSupplement` | none | — | 打开/外部/刷新（成功不震，失败 Error） |
| CLOUD-021 | `ui/features/CloudScreens.kt:261` | 刷新状态 | `onRefresh` | none | — | 打开/外部/刷新（成功不震，失败 Error） |
| CLOUD-022 | `ui/features/CloudScreens.kt:262` | 放弃 | `onDiscard` | none | Removed |  |
| CLOUD-023 | `ui/features/CloudScreens.kt:275` | 导入连接配置 | `{if(!busy)onClose()}` | none | — | 关闭/返回不加触感 |
| CLOUD-024 | `ui/features/CloudScreens.kt:279` | 粘贴配置 JSON | `{raw=it}` | none | — | 系统输入法负责逐键反馈 |
| CLOUD-025 | `ui/features/CloudScreens.kt:280` | 检查配置 | `{parse(raw)}` | none | — | 打开预览/查看 |
| CLOUD-026 | `ui/features/CloudScreens.kt:281` | 选择配置文件 | `{picker.launch(arrayOf("application/json","text/plain","appl` | none | — | 打开/外部/刷新（成功不震，失败 Error） |
| CLOUD-027 | `ui/features/CloudScreens.kt:286` | 确认保存并测试 | `{vm.execute(onSuccess={raw="";onBound()}){cloud.bind(config)` | none | Done |  |
| CLOUD-028 | `ui/features/CloudScreens.kt:287` | 返回修改 | `{parsed=null}` | none | — | 导航/打开/关闭 |
| CLOUD-029 | `ui/features/CloudScreens.kt:300` | 通用分析任务 | `{if(!busy)onClose()}` | none | — | 关闭/返回不加触感 |
| CLOUD-030 | `ui/features/CloudScreens.kt:302` | 任务名称 | `{title=it.take(150)}` | none | — | 系统输入法负责逐键反馈 |
| CLOUD-031 | `ui/features/CloudScreens.kt:303` | 给分析台的说明 | `{note=it}` | none | — | 系统输入法负责逐键反馈 |
| CLOUD-032 | `ui/features/CloudScreens.kt:304` | CloudPickedFiles | `{files=files-it}` | none | — | 草稿内移除文件 |
| CLOUD-033 | `ui/features/CloudScreens.kt:305` | 添加文件 | `{picker.launch(arrayOf("*/*"))}` | none | — | 导航/打开/关闭 |
| CLOUD-034 | `ui/features/CloudScreens.kt:306` | 上传并提交 | `{vm.execute(onSuccess={onQueued()}){cloud.prepareGeneralTask` | none | Done |  |
| CLOUD-035 | `ui/features/CloudScreens.kt:320` | 移除 | `{onRemove(uri)}` | none | — | 打开删除审阅 |
| CLOUD-036 | `ui/features/CloudScreens.kt:351` | ReceiptBackfillSheet | `{if(!busy)onClose()}` | none | — | 导航/打开/关闭 |
| CLOUD-037 | `ui/features/CloudScreens.kt:351` | ReceiptBackfillSheet | `{if(!busy)onClose()}` | none | — | 导航/打开/关闭 |
| CLOUD-038 | `ui/features/CloudScreens.kt:351` | ReceiptBackfillSheet | `{stale,replacement-> vm.execute(onSuccess={onDone("商品明细已保存，回` | none | Done |  |
| CLOUD-039 | `ui/features/CloudScreens.kt:358` | 云端结果预览 | `{if(!busy)onClose()}` | none | — | 关闭/返回不加触感 |
| CLOUD-040 | `ui/features/CloudScreens.kt:365` | 报告无法导入 | `{vm.execute(onSuccess={onDone("已报告导入失败 / 取込失敗を報告しました")}){clo` | none | Done |  |
| CLOUD-041 | `ui/features/CloudScreens.kt:367` | StatementPreviewContent | `{ var id:String?=null vm.execute(onSuccess={vm.selectedState` | none | Done |  |
| CLOUD-042 | `ui/features/CloudScreens.kt:367` | StatementPreviewContent | `onClose` | none | — | 导航/打开/关闭 |
| CLOUD-043 | `ui/features/CloudScreens.kt:367` | StatementPreviewContent | `{overlap=it}` | ToggleOn/ToggleOff | — | 接受重叠 |
| CLOUD-044 | `ui/features/CloudScreens.kt:375` | 选择文件夹保存结果 | `{directory.launch(null)}` | none | — | 打开/外部/刷新（成功不震，失败 Error） |
| CLOUD-045 | `ui/features/CloudScreens.kt:377` | 稍后再领取 | `onClose` | none | — | 导航/打开/关闭 |
| CLOUD-046 | `ui/features/CloudScreens.kt:421` | 上传所选小票 ↗ | `{title=defaultTitle;open=true}` | none | — | 导航/打开/关闭 |
| CLOUD-047 | `ui/features/CloudScreens.kt:422` | 连接云端分析台 | `{vm.navigate("cloud")}` | none | — | 导航/打开/关闭 |
| CLOUD-048 | `ui/features/CloudScreens.kt:424` | 确认交给云端 | `{if(!busy)open=false}` | none | — | 关闭/返回不加触感 |
| CLOUD-049 | `ui/features/CloudScreens.kt:426` | 任务名称 | `{title=it.take(150)}` | none | — | 系统输入法负责逐键反馈 |
| CLOUD-050 | `ui/features/CloudScreens.kt:427` | 补充说明（可选） | `{note=it}` | none | — | 系统输入法负责逐键反馈 |
| CLOUD-051 | `ui/features/CloudScreens.kt:429` | 打包并上传 | `{vm.execute(onSuccess={open=false;note="";onQueued()}){cloud` | none | Done |  |
| CLOUD-052 | `ui/features/CloudScreens.kt:430` | 返回小票工作台 | `{open=false}` | none | — | 导航/打开/关闭 |
| CLOUD-053 | `ui/features/CloudScreens.kt:448` | 选择账户与资料 ↗ | `{if(title.isBlank())title=defaultTitle;open=true}` | none | — | 导航/打开/关闭 |
| CLOUD-054 | `ui/features/CloudScreens.kt:449` | 连接云端分析台 | `{vm.navigate("cloud")}` | none | — | 导航/打开/关闭 |
| CLOUD-055 | `ui/features/CloudScreens.kt:450` | 交给云端转换流水 | `{if(!busy)open=false}` | none | — | 关闭/返回不加触感 |
| CLOUD-056 | `ui/features/CloudScreens.kt:451` | 目标账户 | `{account=it}` | Select | — | 组件内置：选中菜单项 |
| CLOUD-057 | `ui/features/CloudScreens.kt:452` | 任务名称 | `{title=it.take(150)}` | none | — | 系统输入法负责逐键反馈 |
| CLOUD-058 | `ui/features/CloudScreens.kt:453` | 资料来源与日期范围（可选） | `{note=it}` | none | — | 系统输入法负责逐键反馈 |
| CLOUD-059 | `ui/features/CloudScreens.kt:454` | CloudPickedFiles | `{files=files-it}` | none | — | 草稿内移除文件 |
| CLOUD-060 | `ui/features/CloudScreens.kt:455` | 添加银行资料 | `{picker.launch(arrayOf("*/*"))}` | none | — | 导航/打开/关闭 |
| CLOUD-061 | `ui/features/CloudScreens.kt:457` | 上传并提交 | `{vm.execute(onSuccess={open=false;files=emptyList();note="";` | none | Done |  |

## 金融账户（账单、计划、额度、还款）（449）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| FINANCIAL-001 | `ui/features/BatchRevolvingEditor.kt:49` | 比较当前与新方案 | `{vm.execute{ review=vm.repository.previewBatchRevolving(s.bo` | none | — | 生成比较预览（过时=Warning） |
| FINANCIAL-002 | `ui/features/BatchRevolvingEditor.kt:51` | 记录已办理 | `{applying=true;platform=false;exception=false}` | none | — | 导航/打开/关闭 |
| FINANCIAL-003 | `ui/features/BatchRevolvingEditor.kt:51` | 确认记录已办理 | `{var saved=false;vm.execute(onSuccess={if(saved)onClose()els` | none | Done |  |
| FINANCIAL-004 | `ui/features/BatchRevolvingEditor.kt:59` | 建立循环还款计划 | `{onPool(null)}` | none | — | 导航/打开 |
| FINANCIAL-005 | `ui/features/BatchRevolvingEditor.kt:64` |  ·  | `{checked-> selected=if(checked)(eligible+part.planId).distin` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-006 | `ui/features/BatchRevolvingEditor.kt:72` | 转换金额 | `{amount=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-007 | `ui/features/BatchRevolvingEditor.kt:74` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-008 | `ui/features/BatchRevolvingEditor.kt:74` | YDateFields | `{_,v->effective=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-009 | `ui/features/BatchRevolvingEditor.kt:75` | 办理费 / 未知留空，无费用填 0 | `{fee=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-010 | `ui/features/BatchRevolvingEditor.kt:77` | 办理费计入的付款日 / 费用0时可不选 | `{feeDate=it;included=false}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-011 | `ui/features/BatchRevolvingEditor.kt:78` | 该期已确认费用包含本次办理费 | `{included=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-012 | `ui/features/BatchRevolvingEditor.kt:79` | 查看 / 重算循环还款计划 | `{onPool(pool.id)}` | none | — | 打开预览/查看 |
| FINANCIAL-013 | `ui/features/BatchRevolvingEditor.kt:102` | 已在平台实际办理并确认以上条件 | `{platform=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-014 | `ui/features/BatchRevolvingEditor.kt:103` | 平台已明确接受此次期限外变更 | `{exception=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-015 | `ui/features/CashTimelineView.kt:23` | 重新读取 | `{if(onRetry!=null)onRetry()else attempt++}` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-016 | `ui/features/CashTimelineView.kt:49` | 查看并处理安排 | `{onTimelineItem(item)}` | none | — | 打开预览/查看 |
| FINANCIAL-017 | `ui/features/CashTimelineView.kt:51` | 查看账单与调整方案 | `{onFinancial(item.sourceId)}` | none | — | 打开预览/查看 |
| FINANCIAL-018 | `ui/features/CashTimelineView.kt:52` | 查看周期账单 | `onRecurring` | none | — | 打开预览/查看 |
| FINANCIAL-019 | `ui/features/CashTimelineView.kt:53` | 查看活动预留 | `{onActivity(item.sourceId)}` | none | — | 打开预览/查看 |
| FINANCIAL-020 | `ui/features/CashTimelineView.kt:54` | 查看实际记录 | `{onRecord(e)}` | none | — | 打开预览/查看 |
| FINANCIAL-021 | `ui/features/CreditLimitScreen.kt:53` | 重新读取 | `vm::retryRead` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-022 | `ui/features/CreditLimitScreen.kt:72` | 设置额度 / 更新占用基准 | `{editor="config"}` | none | — | 导航/打开 |
| FINANCIAL-023 | `ui/features/CreditLimitScreen.kt:83` | 编辑共享额度与成员 | `{target=group.id;editor="group"}` | none | — | 导航/打开/关闭 |
| FINANCIAL-024 | `ui/features/CreditLimitScreen.kt:84` | 记录共享额度组平台可用值 | `{target=group.id;editor="group-observation"}` | none | — | 导航/打开 |
| FINANCIAL-025 | `ui/features/CreditLimitScreen.kt:87` | 建立共享额度组 | `{target=null;editor="group"}` | none | — | 导航/打开 |
| FINANCIAL-026 | `ui/features/CreditLimitScreen.kt:90` | 记录平台可用值 | `{target=null;editor="observation"}` | none | — | 导航/打开 |
| FINANCIAL-027 | `ui/features/CreditLimitScreen.kt:97` | 确认这笔付款的额度恢复 | `{target=p.eventId;editor="recovery"}` | none | — | 导航/打开 |
| FINANCIAL-028 | `ui/features/CreditLimitScreen.kt:102` | 作废这次恢复确认 | `{voidId=r.id}` | none | — | 打开删除审阅 |
| FINANCIAL-029 | `ui/features/CreditLimitScreen.kt:104` | 配置版本历史 | `{history=!history}` | none | — | 展开/收起 |
| FINANCIAL-030 | `ui/features/CreditLimitScreen.kt:120` | 作废恢复确认 | `{voidId=null}` | none | — | 关闭/返回不加触感 |
| FINANCIAL-031 | `ui/features/CreditLimitScreen.kt:122` | 确认作废 | `{vm.execute(onSuccess={voidId=null}){vm.repository.voidCredi` | none | Removed |  |
| FINANCIAL-032 | `ui/features/CreditLimitScreen.kt:194` | 确认保存 | `{var saved=false;vm.execute(onSuccess={if(saved)onClose()els` | none | Done |  |
| FINANCIAL-033 | `ui/features/CreditLimitScreen.kt:195` | 返回修改 | `onBack` | none | — | 导航/打开/关闭 |
| FINANCIAL-034 | `ui/features/CreditLimitScreen.kt:203` | 确认保存 | `{var saved=false;vm.execute(onSuccess={if(saved)onClose()els` | none | Done |  |
| FINANCIAL-035 | `ui/features/CreditLimitScreen.kt:226` | 预览额度设置 | `{vm.execute{review=vm.repository.previewCreditLimit(s.book.i` | none | — | 打开预览/查看 |
| FINANCIAL-036 | `ui/features/CreditLimitScreen.kt:234` | 总额度 / 未知留空 | `{total=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-037 | `ui/features/CreditLimitScreen.kt:235` | 总占用基准 / 未知留空 | `{used=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-038 | `ui/features/CreditLimitScreen.kt:237` | 额度共享方式 | `{sharing=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-039 | `ui/features/CreditLimitScreen.kt:238` | 分期上限 | `{split=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-040 | `ui/features/CreditLimitScreen.kt:238` | 循环还款上限 | `{revo=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-041 | `ui/features/CreditLimitScreen.kt:239` | 分期与循环还款共用上限 | `{combined=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-042 | `ui/features/CreditLimitScreen.kt:240` | 分期占用基准 | `{usedSplit=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-043 | `ui/features/CreditLimitScreen.kt:240` | 循环还款占用基准 | `{usedRevo=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-044 | `ui/features/CreditLimitScreen.kt:242` | 占用计算依据 | `{usage=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-045 | `ui/features/CreditLimitScreen.kt:243` | 开始占用的时间 | `{start=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-046 | `ui/features/CreditLimitScreen.kt:244` | 本金归还后的额度恢复 | `{recovery=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-047 | `ui/features/CreditLimitScreen.kt:245` | 付款后第几天恢复（自然日，0 为当天） | `{delay=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-048 | `ui/features/CreditLimitScreen.kt:246` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-049 | `ui/features/CreditLimitScreen.kt:246` | YDateFields | `{i,v->if(i==0)basisDate=v else effective=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-050 | `ui/features/CreditLimitScreen.kt:247` | 时区 | `{zone=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-051 | `ui/features/CreditLimitScreen.kt:269` | 预览共享额度 | `{vm.execute{review=vm.repository.previewCreditLimitGroup(s.b` | none | — | 打开预览/查看 |
| FINANCIAL-052 | `ui/features/CreditLimitScreen.kt:276` | 共享额度组名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-053 | `ui/features/CreditLimitScreen.kt:277` | 共享额度上限 / 未知留空 | `{total=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-054 | `ui/features/CreditLimitScreen.kt:278` | 共享额度组占用基准 / 未知留空 | `{used=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-055 | `ui/features/CreditLimitScreen.kt:280` | account.name | `{selected->members=if(selected)members+account.id else membe` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-056 | `ui/features/CreditLimitScreen.kt:282` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-057 | `ui/features/CreditLimitScreen.kt:282` | YDateFields | `{_,v->date=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-058 | `ui/features/CreditLimitScreen.kt:300` | 确认实际额度恢复 | `{if(review!=null)review=null else onClose()}` | none | — | 关闭/返回不加触感 |
| FINANCIAL-059 | `ui/features/CreditLimitScreen.kt:305` | 观测范围 | `{scope=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-060 | `ui/features/CreditLimitScreen.kt:306` | 已实际恢复本金 | `{amount=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-061 | `ui/features/CreditLimitScreen.kt:307` | 其中分期恢复 / 未知留空 | `{split=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-062 | `ui/features/CreditLimitScreen.kt:307` | 其中循环还款恢复 / 未知留空 | `{revo=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-063 | `ui/features/CreditLimitScreen.kt:308` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-064 | `ui/features/CreditLimitScreen.kt:308` | YDateFields | `{_,v->date=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-065 | `ui/features/CreditLimitScreen.kt:311` | 预览确认 | `{vm.execute{review=if(recovery)vm.repository.previewCreditLi` | none | — | 打开预览/查看 |
| FINANCIAL-066 | `ui/features/ExpectedIncomeEditor.kt:47` | 确认收入安排 | `{if(preview){review=null;receipt=null}else onClose()}` | none | — | 关闭/返回不加触感 |
| FINANCIAL-067 | `ui/features/ExpectedIncomeEditor.kt:60` | 确认保存 | `{val r=review;val a=receipt;var saved=false vm.execute(onSuc` | none | Done |  |
| FINANCIAL-068 | `ui/features/ExpectedIncomeEditor.kt:63` | 返回修改 | `{review=null;receipt=null}` | none | — | 导航/打开/关闭 |
| FINANCIAL-069 | `ui/features/ExpectedIncomeEditor.kt:67` | 预计收入名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-070 | `ui/features/ExpectedIncomeEditor.kt:68` | 到账账户 | `{accountId=it;accountAmount="";estimateJson=null}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-071 | `ui/features/ExpectedIncomeEditor.kt:69` | 预计币种 | `{currencyCode=it;accountAmount="";estimateJson=null}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-072 | `ui/features/ExpectedIncomeEditor.kt:70` | 预计金额 | `{amount=it;if(estimateJson!=null)accountAmount="";estimateJs` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-073 | `ui/features/ExpectedIncomeEditor.kt:72` | 预计到账账户金额 / 未知留空 | `{accountAmount=it;estimateJson=null}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-074 | `ui/features/ExpectedIncomeEditor.kt:78` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-075 | `ui/features/ExpectedIncomeEditor.kt:78` | YDateFields | `{_,v->date=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-076 | `ui/features/ExpectedIncomeEditor.kt:80` | 预览预计收入 | `{vm.execute{review=vm.repository.previewExpectedIncome(s.boo` | none | — | 打开预览/查看 |
| FINANCIAL-077 | `ui/features/ExpectedIncomeEditor.kt:84` | 关联已有收入 | `{existingMode=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-078 | `ui/features/ExpectedIncomeEditor.kt:87` | 已有收入记录 | `{existingId=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-079 | `ui/features/ExpectedIncomeEditor.kt:90` | 实际到账金额 | `{actualAmount=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-080 | `ui/features/ExpectedIncomeEditor.kt:91` | 实际本位币金额 | `{baseAmount=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-081 | `ui/features/ExpectedIncomeEditor.kt:92` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-082 | `ui/features/ExpectedIncomeEditor.kt:92` | YDateFields | `{_,v->actualDate=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-083 | `ui/features/ExpectedIncomeEditor.kt:93` | 收入分类 | `{category=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-084 | `ui/features/ExpectedIncomeEditor.kt:94` | 管理收入分类 | `{onClose();vm.navigate("categories")}` | none | — | 导航/打开/关闭 |
| FINANCIAL-085 | `ui/features/ExpectedIncomeEditor.kt:96` | 预览到账影响 | `{vm.execute{receipt=vm.repository.previewExpectedIncomeArriv` | none | — | 打开预览/查看 |
| FINANCIAL-086 | `ui/features/ExpectedIncomeEditor.kt:115` | 查看实际收入 | `{onRecord(actual)}` | none | — | 打开预览/查看 |
| FINANCIAL-087 | `ui/features/ExpectedIncomeEditor.kt:118` | 确认到账 | `{editor=income.id;arriving=true}` | none | — | 导航/打开/关闭 |
| FINANCIAL-088 | `ui/features/ExpectedIncomeEditor.kt:119` | 修改预计项 | `{editor=income.id;arriving=false}` | none | — | 导航/打开 |
| FINANCIAL-089 | `ui/features/ExpectedIncomeEditor.kt:120` | 取消预计收入 | `{cancellation=income.id}` | none | — | 导航/打开/关闭 |
| FINANCIAL-090 | `ui/features/ExpectedIncomeEditor.kt:125` | 添加手动预计收入 | `{editor="";arriving=false}` | none | — | 导航/打开/关闭 |
| FINANCIAL-091 | `ui/features/ExpectedIncomeEditor.kt:126` | 已到账 / 已取消记录 | `{showHistory=!showHistory}` | none | — | 展开/收起 |
| FINANCIAL-092 | `ui/features/ExpectedIncomeEditor.kt:133` | 原资料待核对 | `{resumed=null}` | none | — | 关闭/返回不加触感 |
| FINANCIAL-093 | `ui/features/ExpectedIncomeEditor.kt:135` | 放弃这份草稿 | `{vm.execute(onSuccess={resumed=null}){discardFinancialDraft(` | none | Removed |  |
| FINANCIAL-094 | `ui/features/ExpectedIncomeEditor.kt:138` | 取消预计收入 | `{cancellation=null}` | none | — | 关闭/返回不加触感 |
| FINANCIAL-095 | `ui/features/ExpectedIncomeEditor.kt:141` | 确认取消 | `{vm.execute(onSuccess={cancellation=null}){vm.repository.can` | none | Removed | 取消预计收入 |
| FINANCIAL-096 | `ui/features/FinancialAccountOverview.kt:39` | 额度与恢复 | `onLimits` | none | — | 导航/打开 |
| FINANCIAL-097 | `ui/features/FinancialAccountOverview.kt:48` | 核对这笔实际付款 | `{onRecord(e)}` | none | — | 导航/打开/关闭 |
| FINANCIAL-098 | `ui/features/FinancialAccountOverview.kt:51` | 记录还款 | `{onRepay(summary.next.singleOrNull()?.bill?.id)}` | none | — | 导航/打开 |
| FINANCIAL-099 | `ui/features/FinancialAccountOverview.kt:61` | 补齐扣款安排 | `onPlan` | none | — | 导航/打开 |
| FINANCIAL-100 | `ui/features/FinancialAccountOverview.kt:78` | 安排账户转入 | `{onFund?.invoke(other.id,bank.id)}` | Compose | — | 开始记转账/收入 |
| FINANCIAL-101 | `ui/features/FinancialAccountOverview.kt:79` | 补记已实际到账收入 | `{onFund(bank.id,null)}` | Compose | — | 开始记转账/收入 |
| FINANCIAL-102 | `ui/features/FinancialAccountOverview.kt:90` | 额度与恢复 | `onLimits` | none | — | 导航/打开 |
| FINANCIAL-103 | `ui/features/FinancialAccountOverview.kt:104` | 批量转为循环还款（リボ） | `{onBatchRevolving(summary.nextDate!!)}` | none | — | 导航/打开 |
| FINANCIAL-104 | `ui/features/FinancialAccountOverview.kt:105` | 查看该月账单 | `{onStatement(summary.nextDate!!.take(7))}` | none | — | 打开预览/查看 |
| FINANCIAL-105 | `ui/features/FinancialAccountOverview.kt:114` | 比较 / 更改付款方式 | `{choosing=true}` | none | — | 导航/打开/关闭 |
| FINANCIAL-106 | `ui/features/FinancialAccountOverview.kt:115` | 选择付款安排 | `{choosing=false}` | none | — | 关闭/返回不加触感 |
| FINANCIAL-107 | `ui/features/FinancialAccountOverview.kt:116` | FinancialCompactRow | `{choosing=false;onCompare(r.plan.id)}` | none | — | 导航/打开 |
| FINANCIAL-108 | `ui/features/FinancialAccountOverview.kt:125` | 核对原消费 / 借入 | `{onRecord(e)}` | none | — | 导航/打开/关闭 |
| FINANCIAL-109 | `ui/features/FinancialAccountOverview.kt:128` | 补齐计划与账单 | `onPlan` | none | — | 导航/打开 |
| FINANCIAL-110 | `ui/features/FinancialAccountOverview.kt:130` | 查看 / 补齐用户规则 | `onRules` | none | — | 打开预览/查看 |
| FINANCIAL-111 | `ui/features/FinancialAccountOverview.kt:139` | 还款月份 | `onMonth` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-112 | `ui/features/FinancialAccountOverview.kt:141` | 上个月 | `{onMonth(selected.minusMonths(1).toString())}` | Step | — |  |
| FINANCIAL-113 | `ui/features/FinancialAccountOverview.kt:142` | 下个月 | `{onMonth(selected.plusMonths(1).toString())}` | Step | — |  |
| FINANCIAL-114 | `ui/features/FinancialAccountOverview.kt:161` | 记录还款 | `{onRepayDate(date)}` | none | — | 导航/打开 |
| FINANCIAL-115 | `ui/features/FinancialAccountOverview.kt:162` | 批量转为循环还款（リボ） | `{onBatchRevolving(date)}` | none | — | 导航/打开 |
| FINANCIAL-116 | `ui/features/FinancialAccountOverview.kt:173` | FinancialCompactRow | `{onPeriod(r.bill.id)}` | none | — | 导航/打开 |
| FINANCIAL-117 | `ui/features/FinancialAccountOverview.kt:178` | 管理逐期计划 | `onPlan` | none | — | 导航/打开/关闭 |
| FINANCIAL-118 | `ui/features/FinancialAccountScreen.kt:109` | BackHandler | `{back()}` | none | — | 关闭/返回不加触感 |
| FINANCIAL-119 | `ui/features/FinancialAccountScreen.kt:113` | FinancialAccountBar | `::back` | TabSwitch | — | 组件内置 |
| FINANCIAL-120 | `ui/features/FinancialAccountScreen.kt:114` | FinancialTaskTabs | `{navigate(it);editMode=null;resumedDraft=null;limitRuleRetur` | TabSwitch | — | 组件内置 |
| FINANCIAL-121 | `ui/features/FinancialAccountScreen.kt:121` | 重新读取 | `vm::retryRead` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-122 | `ui/features/FinancialAccountScreen.kt:146` | 返回规则草稿 | `{returnFromLimits()}` | none | — | 导航/打开/关闭 |
| FINANCIAL-123 | `ui/features/FinancialAccountScreen.kt:160` | 填写规则 | `{editMode=mode.name}` | none | — | 导航/打开 |
| FINANCIAL-124 | `ui/features/FinancialAccountScreen.kt:164` | 规则版本历史 | `{history=!history}` | none | — | 展开/收起 |
| FINANCIAL-125 | `ui/features/FinancialAccountScreen.kt:170` | 查看当时规则 | `{historyVersion=if(historyVersion==version.id)null else vers` | none | — | 打开预览/查看 |
| FINANCIAL-126 | `ui/features/FinancialAccountScreen.kt:175` | ＋ 记录实际借入 | `{movementTarget=null;movement="borrow"}` | none | — | 导航/打开 |
| FINANCIAL-127 | `ui/features/FinancialAccountScreen.kt:176` | 补充期初合同 | `{movementTarget=null;movement="opening"}` | none | — | 导航/打开 |
| FINANCIAL-128 | `ui/features/FinancialAccountScreen.kt:184` | 编辑合同 / 借入 | `{movementTarget=c.id;movement=if(c.fundingEventId==null)"ope` | none | — | 导航/打开/关闭 |
| FINANCIAL-129 | `ui/features/FinancialAccountScreen.kt:185` | 关联已有计划 | `{if(savedContract!=c.id)createdPlan=null;contractLink=c.id}` | none | — | 导航/打开 |
| FINANCIAL-130 | `ui/features/FinancialAccountScreen.kt:186` | 对这份合同还款 | `{movementTarget=c.id;movement="repay-contract"}` | none | — | 导航/打开 |
| FINANCIAL-131 | `ui/features/FinancialAccountScreen.kt:190` | ＋ 记录实际还款 | `{movementTarget=null;movement="repay"}` | none | — | 导航/打开 |
| FINANCIAL-132 | `ui/features/FinancialAccountScreen.kt:193` | 核对 / 编辑还款 | `{movementTarget=e.id;movement="repay-edit"}` | none | — | 导航/打开/关闭 |
| FINANCIAL-133 | `ui/features/FinancialAccountScreen.kt:199` | manage/$id | `{navigate("manage/$id")}` | none | — | 导航/打开/关闭 |
| FINANCIAL-134 | `ui/features/FinancialAccountScreen.kt:204` | 规则版本历史 | `{history=true;navigate("rules",returnTo="manage/history")}` | none | — | 导航/打开/关闭 |
| FINANCIAL-135 | `ui/features/FinancialAccountScreen.kt:205` | 额度配置版本历史 | `{navigate("limits",returnTo="manage/history")}` | none | — | 导航/打开/关闭 |
| FINANCIAL-136 | `ui/features/FinancialAccountScreen.kt:207` | 计划版本与变化 | `{planHistory=plan.id}` | none | — | 导航/打开 |
| FINANCIAL-137 | `ui/features/FinancialAccountScreen.kt:212` | 添加还款安排 | `{addArrangement=true}` | none | — | 导航/打开/关闭 |
| FINANCIAL-138 | `ui/features/FinancialAccountScreen.kt:217` | 已替换计划 | `{archivedPlans=!archivedPlans}` | none | — | 展开/收起 |
| FINANCIAL-139 | `ui/features/FinancialAccountScreen.kt:218` |  ·  | `{selectedPlan=p.id}` | none | — | 导航/打开 |
| FINANCIAL-140 | `ui/features/FinancialAccountScreen.kt:280` | 账单调整预览 | `{vm.financialBillReview.value=null}` | none | — | 关闭/返回不加触感 |
| FINANCIAL-141 | `ui/features/FinancialAccountScreen.kt:292` | 确认本期金额 | `{var saved=false;vm.execute(onSuccess={if(saved)onClose()}) ` | none | Done |  |
| FINANCIAL-142 | `ui/features/FinancialAccountScreen.kt:298` | 返回修改 | `{vm.financialBillReview.value=null}` | none | — | 导航/打开/关闭 |
| FINANCIAL-143 | `ui/features/FinancialAccountScreen.kt:303` | 本期平台账单 | `{vm.financialBillReview.value=null;onClose()}` | none | — | 关闭/返回不加触感 |
| FINANCIAL-144 | `ui/features/FinancialAccountScreen.kt:306` | 预览本期调整 | `{val input=draft;vm.execute{vm.financialReviewRefreshed.valu` | none | — | 打开预览/查看 |
| FINANCIAL-145 | `ui/features/FinancialArrangementEditor.kt:88` | 比较当前与新方案 | `{vm.execute { val moves=clearingMoves.map{move->require(move` | none | — | 生成比较预览（过时=Warning） |
| FINANCIAL-146 | `ui/features/FinancialArrangementEditor.kt:97` | 已在平台办理，记录结果 | `{applying=true;platform=false;exception=false}` | none | — | 导航/打开/关闭 |
| FINANCIAL-147 | `ui/features/FinancialArrangementEditor.kt:97` | 确认记录已办理 | `{val stamp=appliedInstant;val accepted=platform;val late=exc` | none | Done |  |
| FINANCIAL-148 | `ui/features/FinancialArrangementEditor.kt:108` | 新方案名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-149 | `ui/features/FinancialArrangementEditor.kt:110` | 新付款方式 | `{m-> mode=m;merge=false;mergeFeeDate=null;mergeIncluded=fals` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-150 | `ui/features/FinancialArrangementEditor.kt:116` | 付款日期依据 | `{singleDateBasis=it;clearRows()}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-151 | `ui/features/FinancialArrangementEditor.kt:117` | 付款月（YYYY-MM） | `{singleMonth=it;clearRows()}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-152 | `ui/features/FinancialArrangementEditor.kt:118` | 按所选依据填入首次付款日 | `{vm.execute{ val basisDate=if(singleDateBasis=="BONUS")s.vis` | Select | — | 填入/选择 |
| FINANCIAL-153 | `ui/features/FinancialArrangementEditor.kt:132` | 手动录入平台新方案 | `{manual=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-154 | `ui/features/FinancialArrangementEditor.kt:135` | 已列出完整新方案 | `{manualComplete=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-155 | `ui/features/FinancialArrangementEditor.kt:140` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-156 | `ui/features/FinancialArrangementEditor.kt:140` | YDateFields | `{i,v->when(i){0->effective=v;1->firstDate=v;else->firstEnd=v` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-157 | `ui/features/FinancialArrangementEditor.kt:142` | 预测期数 | `{count=it;clearRows()}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-158 | `ui/features/FinancialArrangementEditor.kt:143` | 新安排从第几期计费 | `{firstPeriod=it;clearRows()}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-159 | `ui/features/FinancialArrangementEditor.kt:146` | 并入已有循环还款计划 | `{merge=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-160 | `ui/features/FinancialArrangementEditor.kt:150` | 办理费计入的付款日 / 费用0时可不选 | `{mergeFeeDate=it;mergeIncluded=false}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-161 | `ui/features/FinancialArrangementEditor.kt:151` | 该期已确认费用包含本次办理费 | `{mergeIncluded=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-162 | `ui/features/FinancialArrangementEditor.kt:153` | 核对原循环还款计划本期账单 | `{if(onPoolBill!=null)onPoolBill(bill.id)else onClose()}` | none | — | 导航/打开/关闭 |
| FINANCIAL-163 | `ui/features/FinancialArrangementEditor.kt:154` | 查看 / 重算原循环还款计划，保留本次输入 | `{if(onPoolReview!=null)onPoolReview(destinationPool.id)else ` | none | — | 打开预览/查看 |
| FINANCIAL-164 | `ui/features/FinancialArrangementEditor.kt:156` | 新付款账户 | `{bank=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-165 | `ui/features/FinancialArrangementEditor.kt:157` | 原方案剩余费用如何处理 | `{treatment=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-166 | `ui/features/FinancialArrangementEditor.kt:165` | 选择本金 | `{poolPartsJson=vm.repository.json.encodeToString(poolParts+P` | Select | — | 填入/选择 |
| FINANCIAL-167 | `ui/features/FinancialArrangementEditor.kt:171` | 转出本金 | `{changePoolPart(index,part.copy(principal=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-168 | `ui/features/FinancialArrangementEditor.kt:172` | 平台取消的本期费用 / 已知无费用填0 | `{changePoolPart(index,part.copy(cancelFee=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-169 | `ui/features/FinancialArrangementEditor.kt:173` | 移除这期选择 | `{poolPartsJson=vm.repository.json.encodeToString(poolParts.f` | none | — | 打开删除审阅 |
| FINANCIAL-170 | `ui/features/FinancialArrangementEditor.kt:175` | 未排期本金转出 / 不选留空 | `{unbilled=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-171 | `ui/features/FinancialArrangementEditor.kt:178` | 另列变更费用 / 未知留空，已知无费用填0 | `{fee=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-172 | `ui/features/FinancialArrangementEditor.kt:180` | 循环额度占用变动 / 未知留空，增加为正，释放为负 | `{loanLimitDelta=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-173 | `ui/features/FinancialArrangementEditor.kt:183` | 生成逐期日期 | `{vm.execute { val n=if(selected==FinanceMode.SINGLE)1 else c` | Step | — |  |
| FINANCIAL-174 | `ui/features/FinancialArrangementEditor.kt:202` | YDateFields | `{owner=it?.let{v->(i+1)*10+v}}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-175 | `ui/features/FinancialArrangementEditor.kt:202` | YDateFields | `{j,v->changeRow(if(!revolving\|\|manual\|\|j==2)row.copy(date=v)` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-176 | `ui/features/FinancialArrangementEditor.kt:204` | 本期金额 / 不含另列变更费 | `{changeRow(row.copy(total=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-177 | `ui/features/FinancialArrangementEditor.kt:205` | 本期本金 / 未知留空 | `{changeRow(row.copy(principal=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-178 | `ui/features/FinancialArrangementEditor.kt:206` | 本期费用 / 未知留空 | `{changeRow(row.copy(fee=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-179 | `ui/features/FinancialArrangementEditor.kt:218` | 清偿归属 | `{changeMove(index,move.copy(destination=it))}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-180 | `ui/features/FinancialArrangementEditor.kt:219` | 分配本金 | `{changeMove(index,move.copy(principal=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-181 | `ui/features/FinancialArrangementEditor.kt:220` | 分配利息 | `{changeMove(index,move.copy(interest=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-182 | `ui/features/FinancialArrangementEditor.kt:221` | 分配费用 | `{changeMove(index,move.copy(fee=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-183 | `ui/features/FinancialArrangementEditor.kt:222` | 移除此分配行 | `{movesJson=vm.repository.json.encodeToString(clearingMoves.f` | none | — | 打开删除审阅 |
| FINANCIAL-184 | `ui/features/FinancialArrangementEditor.kt:224` | 分配这笔清偿 | `{movesJson=vm.repository.json.encodeToString(clearingMoves+i` | Select | — | 填入/选择 |
| FINANCIAL-185 | `ui/features/FinancialArrangementEditor.kt:226` | 清偿资料已变化，移除旧分配选择 | `{movesJson=vm.repository.json.encodeToString(clearingMoves.f` | none | — | 打开删除审阅 |
| FINANCIAL-186 | `ui/features/FinancialArrangementEditor.kt:239` | 已在平台实际办理并确认以上条件 | `{platform=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-187 | `ui/features/FinancialArrangementEditor.kt:240` | 平台已明确接受此次期限外变更 | `{exception=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-188 | `ui/features/FinancialBatchHistory.kt:53` | 查看 | `{selected=item;blocked=null}` | none | — | 打开预览/查看 |
| FINANCIAL-189 | `ui/features/FinancialBatchHistory.kt:56` | 批量转换记录 | `{selected=null}` | none | — | 关闭/返回不加触感 |
| FINANCIAL-190 | `ui/features/FinancialBatchHistory.kt:60` | 查看当前还款安排 | `{selected=null;onCurrent(item.archive.before.first{it.plan.o` | none | — | 打开预览/查看 |
| FINANCIAL-191 | `ui/features/FinancialBatchHistory.kt:62` | 确认撤销这次批量转换记录 | `{vm.execute{ try { vm.repository.undoBatchRevolving(bookId,i` | none | Restored |  |
| FINANCIAL-192 | `ui/features/FinancialBatchHistory.kt:66` | 返回历史 | `{selected=null}` | none | — | 导航/打开/关闭 |
| FINANCIAL-193 | `ui/features/FinancialBillPayment.kt:34` | 查看付款 | `{onPayment(e)}` | none | — | 打开预览/查看 |
| FINANCIAL-194 | `ui/features/FinancialBillPayment.kt:35` | 核对后记录其他还款 | `{onPay(bill.id)}` | none | — | 导航/打开 |
| FINANCIAL-195 | `ui/features/FinancialBudgetScenario.kt:60` | 查看测算依据 | `{details=!details}` | none | — | 打开预览/查看 |
| FINANCIAL-196 | `ui/features/FinancialDisplayViews.kt:63` | 只看变化日期 | `{changesOnly=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-197 | `ui/features/FinancialDisplayViews.kt:169` | YDateFields | `onOwner` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-198 | `ui/features/FinancialDisplayViews.kt:169` | YDateFields | `{_,raw-> val edited=java.time.LocalDateTime.parse(raw) // Op` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-199 | `ui/features/FinancialDrafts.kt:113` | 金融草稿 | `onClose` | none | — | 关闭/返回不加触感 |
| FINANCIAL-200 | `ui/features/FinancialDrafts.kt:114` | 金融草稿 | `onClose` | none | — | 关闭/返回不加触感 |
| FINANCIAL-201 | `ui/features/FinancialDrafts.kt:114` | 重新读取 | `{attempt++}` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-202 | `ui/features/FinancialDrafts.kt:119` | 金融草稿 | `onClose` | none | — | 关闭/返回不加触感 |
| FINANCIAL-203 | `ui/features/FinancialDrafts.kt:119` | 重新读取 | `vm::retryRead` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-204 | `ui/features/FinancialDrafts.kt:138` | 放弃草稿并关闭 | `{session.vm.execute(onSuccess=session.close){session.discard` | none | Removed |  |
| FINANCIAL-205 | `ui/features/FinancialDrafts.kt:167` | 重新读取草稿 | `{attempt++}` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-206 | `ui/features/FinancialDrafts.kt:169` | 未完成金融草稿 | `{if(onOpen!=null)onOpen()else expanded=!expanded}` | none | — | 导航/打开/关闭 |
| FINANCIAL-207 | `ui/features/FinancialDrafts.kt:175` | 继续编辑 | `{onResume(context)}` | none | — | 导航/打开/关闭 |
| FINANCIAL-208 | `ui/features/FinancialDrafts.kt:186` | 金融草稿 | `onClose` | none | — | 关闭/返回不加触感 |
| FINANCIAL-209 | `ui/features/FinancialDrafts.kt:186` | 重新读取 | `vm::retryRead` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-210 | `ui/features/FinancialDrafts.kt:201` | 原资料待核对 | `onClose` | none | — | 关闭/返回不加触感 |
| FINANCIAL-211 | `ui/features/FinancialDrafts.kt:203` | 放弃这份草稿 | `{vm.execute(onSuccess=onClose){discardFinancialDraft(vm,s.bo` | none | Removed |  |
| FINANCIAL-212 | `ui/features/FinancialLedgerEditor.kt:54` | 预览借入记录 | `{vm.execute { val input=FinancialBorrowingInput(account.id,d` | none | — | 打开预览/查看 |
| FINANCIAL-213 | `ui/features/FinancialLedgerEditor.kt:65` | 合同名称 | `{update(d.copy(name=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-214 | `ui/features/FinancialLedgerEditor.kt:66` | 借款模式 | `{update(d.copy(mode=it))}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-215 | `ui/features/FinancialLedgerEditor.kt:67` | 合同本金 | `{update(d.copy(principal=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-216 | `ui/features/FinancialLedgerEditor.kt:68` | YDateFields | `{dateOwner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-217 | `ui/features/FinancialLedgerEditor.kt:68` | YDateFields | `{_,v->update(d.copy(date=v))}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-218 | `ui/features/FinancialLedgerEditor.kt:70` | 实际到账账户 | `{update(d.copy(funds=it,received="",separatePayer=null,separ` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-219 | `ui/features/FinancialLedgerEditor.kt:71` | 实际到账 | `{update(d.copy(received=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-220 | `ui/features/FinancialLedgerEditor.kt:72` | 预扣费用 / 不明留空 | `{update(d.copy(withheld=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-221 | `ui/features/FinancialLedgerEditor.kt:73` | 预扣费用统计金额 | `{update(d.copy(withheldBase=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-222 | `ui/features/FinancialLedgerEditor.kt:74` | 另付费用账户 | `{update(d.copy(separatePayer=it,separate="",separateBase="")` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-223 | `ui/features/FinancialLedgerEditor.kt:75` | 另付费用 / 未知留空 | `{update(d.copy(separate=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-224 | `ui/features/FinancialLedgerEditor.kt:76` | 另付费用统计金额 | `{update(d.copy(separateBase=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-225 | `ui/features/FinancialLedgerEditor.kt:77` | 备注 | `{update(d.copy(note=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-226 | `ui/features/FinancialLedgerEditor.kt:179` | 确认保存实际记录 | `{var saved=false;vm.execute(onSuccess={if(saved)onClose()}) ` | none | Done |  |
| FINANCIAL-227 | `ui/features/FinancialOutlookViews.kt:54` | 核对并处理 | `{onRisk(a.id)}` | none | — | 导航/打开/关闭 |
| FINANCIAL-228 | `ui/features/FinancialOutlookViews.kt:76` | clickable | `onClick` | none | — | 导航/打开 |
| FINANCIAL-229 | `ui/features/FinancialOutlookViews.kt:96` | clickable | `onClick` | none | — | 导航/打开 |
| FINANCIAL-230 | `ui/features/FinancialOutlookViews.kt:115` | 重要事项 | `onImportant` | none | — | 导航/打开 |
| FINANCIAL-231 | `ui/features/FinancialOutlookViews.kt:118` | 重新读取 | `{attempt++}` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-232 | `ui/features/FinancialOutlookViews.kt:123` | 预算已超出或按当前节奏预计超出，查看原因 | `onForecast` | none | — | 打开预览/查看 |
| FINANCIAL-233 | `ui/features/FinancialOutlookViews.kt:125` | 接下来的付款 | `onCalendar` | none | — | 导航/打开 |
| FINANCIAL-234 | `ui/features/FinancialOutlookViews.kt:129` | 查看未来资金与预算风险 | `onForecast` | none | — | 打开预览/查看 |
| FINANCIAL-235 | `ui/features/FinancialOutlookViews.kt:145` | 重新读取 | `{attempt++}` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-236 | `ui/features/FinancialOutlookViews.kt:152` | 检查未来资金缺口 | `onForecast` | none | — | 打开预览/查看 |
| FINANCIAL-237 | `ui/features/FinancialOutlookViews.kt:156` | 核对资金安排 | `onClose` | none | — | 关闭/返回不加触感 |
| FINANCIAL-238 | `ui/features/FinancialOutlookViews.kt:161` | 重新读取 | `{attempt++}` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-239 | `ui/features/FinancialOutlookViews.kt:171` | 记录转入 / 换汇 | `{onTransfer(other.id,a.id)}` | Compose | — | 开始记转账/收入 |
| FINANCIAL-240 | `ui/features/FinancialOutlookViews.kt:172` | 补记已实际到账收入 | `{onActualIncome(a.id)}` | Compose | — | 开始记转账/收入 |
| FINANCIAL-241 | `ui/features/FinancialOutlookViews.kt:173` | 添加预计收入情景 | `{onIncome(a.id)}` | none | — | 导航/打开/关闭 |
| FINANCIAL-242 | `ui/features/FinancialOutlookViews.kt:175` | 查看完整资金时间线 | `onForecast` | none | — | 打开预览/查看 |
| FINANCIAL-243 | `ui/features/FinancialOutlookViews.kt:188` | YDateFields | `{owner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-244 | `ui/features/FinancialOutlookViews.kt:188` | YDateFields | `{_,v->through=maxOf(v,today.toString())}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-245 | `ui/features/FinancialOutlookViews.kt:203` | 转账 / 换汇 | `onTransfer` | Compose | — | 开始记转账/收入 |
| FINANCIAL-246 | `ui/features/FinancialOutlookViews.kt:204` | 付款日历 | `onCalendar` | none | — | 导航/打开 |
| FINANCIAL-247 | `ui/features/FinancialOutlookViews.kt:205` | 重要事项 | `onImportant` | none | — | 导航/打开 |
| FINANCIAL-248 | `ui/features/FinancialOutlookViews.kt:206` | 未来资金与预算风险 | `onForecast` | none | — | 导航/打开 |
| FINANCIAL-249 | `ui/features/FinancialOutlookViews.kt:211` | 重新读取 | `{attempt++}` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-250 | `ui/features/FinancialOverviewDesign.kt:122` | 核对逐期计划 | `onPlans` | none | — | 导航/打开 |
| FINANCIAL-251 | `ui/features/FinancialOverviewDesign.kt:155` | 本金、费用与待确认 | `{details=!details}` | none | — | 展开/收起 |
| FINANCIAL-252 | `ui/features/FinancialOverviewDesign.kt:160` | 查看该月账单 | `{onStatement(requireNotNull(summary.nextDate).take(7))}` | none | — | 打开预览/查看 |
| FINANCIAL-253 | `ui/features/FinancialPlanHistory.kt:33` | 计划版本 | `onClose` | none | — | 关闭/返回不加触感 |
| FINANCIAL-254 | `ui/features/FinancialPlanHistory.kt:41` | 重新读取 | `{attempt++}` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-255 | `ui/features/FinancialPlanHistory.kt:49` | 查看当时各期 | `{selected=if(selected==r.version)null else r.version}` | none | — | 打开预览/查看 |
| FINANCIAL-256 | `ui/features/FinancialPlanImpacts.kt:45` | FinancialFullDetails | `{ expanded=!expanded }` | none | — | 展开/收起 |
| FINANCIAL-257 | `ui/features/FinancialPlanList.kt:26` | plan.name | `onOpen` | none | — | 导航/打开/关闭 |
| FINANCIAL-258 | `ui/features/FinancialPlanList.kt:37` | 添加还款安排 | `onClose` | none | — | 关闭/返回不加触感 |
| FINANCIAL-259 | `ui/features/FinancialPlanList.kt:39` | 录入已知账单 | `onManual` | none | — | 导航/打开 |
| FINANCIAL-260 | `ui/features/FinancialPlanList.kt:41` | 按规则生成预测 | `onPrediction` | none | — | 导航/打开 |
| FINANCIAL-261 | `ui/features/FinancialPlanList.kt:43` | 管理循环还款 | `onPool` | none | — | 导航/打开/关闭 |
| FINANCIAL-262 | `ui/features/FinancialPlanList.kt:57` | 关联已有计划 | `onClose` | none | — | 关闭/返回不加触感 |
| FINANCIAL-263 | `ui/features/FinancialPlanList.kt:64` | 新建 ·  | `{onLink(p.id)}` | none | — | 导航/打开/关闭 |
| FINANCIAL-264 | `ui/features/FinancialPlanList.kt:69` | 为此合同建立计划 | `onCreate` | none | — | 导航/打开/关闭 |
| FINANCIAL-265 | `ui/features/FinancialPlanPages.kt:28` | 完整进度与待付组成 | `{progress=!progress}` | none | — | 展开/收起 |
| FINANCIAL-266 | `ui/features/FinancialPlanPages.kt:33` | FinancialPeriodRow | `{onPeriod(bill.id)}` | none | — | 导航/打开 |
| FINANCIAL-267 | `ui/features/FinancialPlanPages.kt:40` | 计划版本与变化 | `{onHistory(plan.id)}` | none | — | 导航/打开 |
| FINANCIAL-268 | `ui/features/FinancialPlanPages.kt:41` | 调整共同月付款 | `{onPoolPayment(plan.id)}` | none | — | 导航/打开 |
| FINANCIAL-269 | `ui/features/FinancialPlanPages.kt:42` | 比较付款方案 | `{onCompare(plan.id)}` | none | — | 导航/打开 |
| FINANCIAL-270 | `ui/features/FinancialPlanPages.kt:45` | 用规则核对这份计划 | `{onCalculate(plan.id)}` | none | — | 导航/打开 |
| FINANCIAL-271 | `ui/features/FinancialPlanPages.kt:51` | 重核来源 / 退款 / 清偿 | `{onReconcile(target.id)}` | none | — | 导航/打开 |
| FINANCIAL-272 | `ui/features/FinancialPlanPages.kt:52` | 查看原安排版本 | `{onHistory(original.id)}` | none | — | 打开预览/查看 |
| FINANCIAL-273 | `ui/features/FinancialPlanPages.kt:53` | 查看并入记录 | `{onHistory(target.id)}` | none | — | 打开预览/查看 |
| FINANCIAL-274 | `ui/features/FinancialPlanPages.kt:82` | 查看原记录 | `{onRecord(event)}` | none | — | 打开预览/查看 |
| FINANCIAL-275 | `ui/features/FinancialPlanPages.kt:85` | 查看计划与全部期次 | `onPlan` | none | — | 打开预览/查看 |
| FINANCIAL-276 | `ui/features/FinancialPlanPages.kt:86` | 比较 / 更改付款方式 | `onCompare` | none | — | 导航/打开 |
| FINANCIAL-277 | `ui/features/FinancialPlanPages.kt:87` | 计划版本与变化 | `onHistory` | none | — | 导航/打开 |
| FINANCIAL-278 | `ui/features/FinancialPlanPages.kt:89` | 明确调整这期账单 | `{onConfirm(bill.id)}` | none | — | 导航/打开 |
| FINANCIAL-279 | `ui/features/FinancialPlanPages.kt:90` | 与原预测核对规则 | `{vm.execute{vm.repository.validateFinancialRule(s.book.id,bi` | none | Done / Warning | 规则核对通过/发现差异 |
| FINANCIAL-280 | `ui/features/FinancialPlanProgressView.kt:48` | YTabs | `{mode=it}` | TabSwitch | — | 组件内置 |
| FINANCIAL-281 | `ui/features/FinancialPlanProgressView.kt:73` | 查看 / 补齐还款计划 | `onPlans` | none | — | 打开预览/查看 |
| FINANCIAL-282 | `ui/features/FinancialPlanProgressView.kt:74` | 查看借还与合同明细 | `onMovements` | none | — | 打开预览/查看 |
| FINANCIAL-283 | `ui/features/FinancialPoolEditor.kt:66` | 确认保存循环还款计划 | `{var ok=false;vm.execute(onSuccess={if(ok)onClose()else{revi` | none | Done |  |
| FINANCIAL-284 | `ui/features/FinancialPoolEditor.kt:68` | 预览循环还款计划 | `{vm.execute { val source=sourceDate?.let{PoolSourceDate.valu` | none | — | 打开预览/查看 |
| FINANCIAL-285 | `ui/features/FinancialPoolEditor.kt:102` | 查看保留账单 | `{ expanded=!expanded }` | none | — | 打开预览/查看 |
| FINANCIAL-286 | `ui/features/FinancialPoolEditor.kt:117` | 来源与变动 | `{ expanded=!expanded }` | none | — | 展开/收起 |
| FINANCIAL-287 | `ui/features/FinancialPoolEditor.kt:126` | 计划名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-288 | `ui/features/FinancialPoolEditor.kt:128` | 期初属于循环的本金 / 没有请填0 | `{opening=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-289 | `ui/features/FinancialPoolEditor.kt:129` | 新增本金起算日期 | `{sourceDate=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-290 | `ui/features/FinancialPoolEditor.kt:130` | 付款账户 / 可留空 | `{bank=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-291 | `ui/features/FinancialPoolEditor.kt:131` | 预测期数 | `{count=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-292 | `ui/features/FinancialPoolEditor.kt:132` | 起始账期序号 | `{firstNumber=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-293 | `ui/features/FinancialPoolEditor.kt:133` | YDateFields | `{calendar=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-294 | `ui/features/FinancialPoolEditor.kt:133` | YDateFields | `{i,v->when(i){0->firstStart=v;1->firstEnd=v;else->firstPay=v` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-295 | `ui/features/FinancialPoolEditor.kt:134` | 生成按月候选 | `{vm.execute { val n=count.toIntOrNull()?:error("请输入期数 / 回数を入` | Step | — |  |
| FINANCIAL-296 | `ui/features/FinancialPoolEditor.kt:145` | 调整本期日期 | `{editingPeriod=if(editingPeriod==i)null else i;calendar=null` | none | — | 导航/打开/关闭 |
| FINANCIAL-297 | `ui/features/FinancialPoolEditor.kt:146` | YDateFields | `{calendar=it?.let{n->3+i*3+n}}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-298 | `ui/features/FinancialPoolEditor.kt:146` | YDateFields | `{field,v->encoded=vm.repository.json.encodeToString(rows.map` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-299 | `ui/features/FinancialPoolEditor.kt:150` | 归并以上循环计划，保留已确认与付款历史 | `{adopt=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-300 | `ui/features/FinancialPoolEditor.kt:158` | 办理费共同付款日 | `{fee(row.copy(date=it,included=false))}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-301 | `ui/features/FinancialPoolEditor.kt:159` | 平台明确取消的本笔办理费 / 未取消填0 | `{fee(row.copy(waived=it,included=false))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-302 | `ui/features/FinancialPoolEditor.kt:163` | 已核对该日保留账单包含本笔办理费 | `{fee(row.copy(included=it))}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-303 | `ui/features/FinancialPoolEditor.kt:166` | 核对这期账单 | `{onBill(bill.id)}` | none | — | 导航/打开 |
| FINANCIAL-304 | `ui/features/FinancialPoolEditor.kt:174` | 未分配本金中属于循环还款计划的金额 | `{v->shareJson=vm.repository.json.encodeToString(shares+(p.ev` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-305 | `ui/features/FinancialPoolEditor.kt:178` | 移除这份失效归属 | `{shareJson=vm.repository.json.encodeToString(shares-id)}` | none | — | 打开删除审阅 |
| FINANCIAL-306 | `ui/features/FinancialPoolPaymentEditor.kt:58` | 比较月付款调整 | `{vm.execute { val setting=RevolvingPaymentSetting(from?:erro` | none | — | 生成比较预览（过时=Warning） |
| FINANCIAL-307 | `ui/features/FinancialPoolPaymentEditor.kt:62` | 已在平台办理，记录结果 | `{applying=true;confirmed=false;exception=false}` | none | — | 导航/打开/关闭 |
| FINANCIAL-308 | `ui/features/FinancialPoolPaymentEditor.kt:62` | 确认记录已办理 | `{var saved=false;vm.execute(onSuccess={if(saved)onClose()els` | none | Done |  |
| FINANCIAL-309 | `ui/features/FinancialPoolPaymentEditor.kt:72` | YDateFields | `{calendar=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-310 | `ui/features/FinancialPoolPaymentEditor.kt:72` | YDateFields | `{_,v->effective=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-311 | `ui/features/FinancialPoolPaymentEditor.kt:73` | 从哪次付款起采用 | `{from=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-312 | `ui/features/FinancialPoolPaymentEditor.kt:74` | 新的月付款方式 | `{method=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-313 | `ui/features/FinancialPoolPaymentEditor.kt:76` | 阶梯金额包含费用吗 | `{includes=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-314 | `ui/features/FinancialPoolPaymentEditor.kt:77` | 阶梯余额判定日 | `{tierDate=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-315 | `ui/features/FinancialPoolPaymentEditor.kt:79` | 余额上限 / 末档不限留空 | `{tier(i,t.copy(bound=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-316 | `ui/features/FinancialPoolPaymentEditor.kt:80` | 该档月付款 | `{tier(i,t.copy(amount=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-317 | `ui/features/FinancialPoolPaymentEditor.kt:81` | 移除此档 | `{tiersJson=vm.repository.json.encodeToString(tiers.filterInd` | none | — | 打开删除审阅 |
| FINANCIAL-318 | `ui/features/FinancialPoolPaymentEditor.kt:83` | 添加一档 | `{tiersJson=vm.repository.json.encodeToString(tiers+PaymentTi` | none | — | 导航/打开/关闭 |
| FINANCIAL-319 | `ui/features/FinancialPoolPaymentEditor.kt:84` | 新月付款金额 | `{amount=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-320 | `ui/features/FinancialPoolPaymentEditor.kt:85` | 办理费 / 未知留空，无费用填0 | `{fee=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-321 | `ui/features/FinancialPoolPaymentEditor.kt:86` | 办理费付款日 / 无费用可不选 | `{feeDate=it;feeIncluded=false}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-322 | `ui/features/FinancialPoolPaymentEditor.kt:87` | 保留账单费用已包含本次办理费 | `{feeIncluded=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-323 | `ui/features/FinancialPoolPaymentEditor.kt:90` | 核对本期费用账单 | `{onReviewBill(bill.id)}` | none | — | 导航/打开 |
| FINANCIAL-324 | `ui/features/FinancialPoolPaymentEditor.kt:92` | 核对原循环还款计划并保留输入 | `onReviewPool` | none | — | 导航/打开 |
| FINANCIAL-325 | `ui/features/FinancialPoolPaymentEditor.kt:102` | 已在平台实际办理并确认以上条件 | `{confirmed=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-326 | `ui/features/FinancialPoolPaymentEditor.kt:103` | 平台已接受期限外变更 | `{exception=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-327 | `ui/features/FinancialPredictionEditor.kt:78` | 预览预测与影响 | `{ val inputName=name;val inputMode=mode;val inputSource=sour` | none | — | 打开预览/查看 |
| FINANCIAL-328 | `ui/features/FinancialPredictionEditor.kt:96` | 计划名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-329 | `ui/features/FinancialPredictionEditor.kt:99` | 计算模式 | `{mode=it;if(source=="EVENT")event?.let{e->suggestDates(e.dat` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-330 | `ui/features/FinancialPredictionEditor.kt:100` | 本金来源 | `{source=it;clearRows()}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-331 | `ui/features/FinancialPredictionEditor.kt:103` | 关联记录 | `{id->eventId=id;eventId?.let{e->agreement=s.events.first{it.` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-332 | `ui/features/FinancialPredictionEditor.kt:106` | 这份计划的已有本金 | `{principal=it;clearRows()}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-333 | `ui/features/FinancialPredictionEditor.kt:109` | 预测期数 / 不代表必定结清 | `{count=it;clearRows()}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-334 | `ui/features/FinancialPredictionEditor.kt:110` | 起于原合同第几期 | `{firstPeriod=it;clearRows()}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-335 | `ui/features/FinancialPredictionEditor.kt:112` | 平台已知剩余总费用 / 未知留空 | `{remainingFee=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-336 | `ui/features/FinancialPredictionEditor.kt:117` | YDateFields | `{calendar=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-337 | `ui/features/FinancialPredictionEditor.kt:117` | YDateFields | `{i,v-> var n=i if(source!="EVENT"){if(n==0)agreement=v;n--} ` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-338 | `ui/features/FinancialPredictionEditor.kt:123` | 付款账户 / 按规则可留空 | `{bank=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-339 | `ui/features/FinancialPredictionEditor.kt:124` | 按月生成候选日期 | `{ vm.execute { val n=if(activeMode==FinanceMode.SINGLE)1 els` | Select | — | 填入/选择 |
| FINANCIAL-340 | `ui/features/FinancialPredictionEditor.kt:138` | YDateFields | `{owner->calendar=owner?.let{(i+1)*10+it}}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-341 | `ui/features/FinancialPredictionEditor.kt:138` | YDateFields | `{field,value-> val next=if(!revolving\|\|field==2)row.copy(dat` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-342 | `ui/features/FinancialPredictionEditor.kt:143` | 采用本期平台本金与费用 | `{value->encoded=vm.repository.json.encodeToString(rows.mapIn` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-343 | `ui/features/FinancialPredictionEditor.kt:145` | 本期本金 | `{v->encoded=vm.repository.json.encodeToString(rows.mapIndexe` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-344 | `ui/features/FinancialPredictionEditor.kt:146` | 本期费用 | `{v->encoded=vm.repository.json.encodeToString(rows.mapIndexe` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-345 | `ui/features/FinancialPredictionEditor.kt:150` | 以上是平台当前已采用的安排，不是拟办理方案 | `{arrangementConfirmed=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-346 | `ui/features/FinancialPredictionReview.kt:58` | 确认保存预测计划 | `{var saved=false;vm.execute(onSuccess={if(saved)onSaved()els` | none | Done |  |
| FINANCIAL-347 | `ui/features/FinancialPredictionReview.kt:67` | 原预测依据 | `{open=!open}` | none | — | 展开/收起 |
| FINANCIAL-348 | `ui/features/FinancialReconciliationEditor.kt:92` | 预览重核结果 | `{vm.execute { val rows=bills.filter{row->val b=allBills.firs` | none | — | 打开预览/查看 |
| FINANCIAL-349 | `ui/features/FinancialReconciliationEditor.kt:100` | 保存本次重核版本 | `{var ok=false;vm.execute(onSuccess={if(ok)onClose()else{revi` | none | Done |  |
| FINANCIAL-350 | `ui/features/FinancialReconciliationEditor.kt:112` | 核对上一步安排 | `{onParent(parent.targetPlanId)}` | none | — | 导航/打开 |
| FINANCIAL-351 | `ui/features/FinancialReconciliationEditor.kt:118` | 先核对并入来源： | `{onParent(change.targetPlanId)}` | none | — | 导航/打开 |
| FINANCIAL-352 | `ui/features/FinancialReconciliationEditor.kt:131` | YDateFields | `{calendar=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-353 | `ui/features/FinancialReconciliationEditor.kt:131` | YDateFields | `{_,v->effective=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-354 | `ui/features/FinancialReconciliationEditor.kt:132` | 生效日转入的本金 / 非今日剩余 | `{principal=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-355 | `ui/features/FinancialReconciliationEditor.kt:133` | 来源所属的期初本金 | `{opening=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-356 | `ui/features/FinancialReconciliationEditor.kt:134` | 循环还款计划本金入账日期依据 | `{poolDate=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-357 | `ui/features/FinancialReconciliationEditor.kt:137` | 未分配本金中属于原循环还款计划的金额 / 零请填0 | `{v->poolSharesJson=vm.repository.json.encodeToString(poolSha` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-358 | `ui/features/FinancialReconciliationEditor.kt:141` | 移除失效归属 | `{poolSharesJson=vm.repository.json.encodeToString(poolShares` | none | — | 打开删除审阅 |
| FINANCIAL-359 | `ui/features/FinancialReconciliationEditor.kt:144` | 本次核对的循环占用变动 / 未知留空 | `{limitDelta=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-360 | `ui/features/FinancialReconciliationEditor.kt:149` | 已向平台核对，保留本期金额 | `{bill(row.copy(keep=it))}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-361 | `ui/features/FinancialReconciliationEditor.kt:151` | 仍待付本金 / 不明可留空 | `{bill(row.copy(principal=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-362 | `ui/features/FinancialReconciliationEditor.kt:152` | 仍待付费用 / 不明可留空 | `{bill(row.copy(fee=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-363 | `ui/features/FinancialReconciliationEditor.kt:157` | 查看 / 核对原账单 | `{onBill(b.id)}` | none | — | 打开预览/查看 |
| FINANCIAL-364 | `ui/features/FinancialReconciliationEditor.kt:160` | 本次已列完整手动方案 | `{complete=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-365 | `ui/features/FinancialReconciliationEditor.kt:163` | 其中减少新安排的本金 | `{share(row.copy(target=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-366 | `ui/features/FinancialReconciliationEditor.kt:165` | 减少该原账单 / 不分配留空 | `{share(row.copy(bills=row.bills+(b.id to it)))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-367 | `ui/features/FinancialReconciliationEditor.kt:172` | 这部分清偿归属 | `{move(index,row.copy(billId=it))}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-368 | `ui/features/FinancialReconciliationEditor.kt:173` | 分配本金 | `{move(index,row.copy(principal=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-369 | `ui/features/FinancialReconciliationEditor.kt:174` | 分配利息 | `{move(index,row.copy(interest=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-370 | `ui/features/FinancialReconciliationEditor.kt:175` | 分配费用 | `{move(index,row.copy(fee=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-371 | `ui/features/FinancialReconciliationEditor.kt:177` | 拆分另一部分 | `{moveJson=vm.repository.json.encodeToString(moves+Reconcilia` | none | — | 导航/打开 |
| FINANCIAL-372 | `ui/features/FinancialReconciliationEditor.kt:178` | 移除此部分 | `{moveJson=vm.repository.json.encodeToString(moves.filterInde` | none | — | 打开删除审阅 |
| FINANCIAL-373 | `ui/features/FinancialReconciliationEditor.kt:182` | 清偿已变化，重新读取分配行 | `{moveJson=vm.repository.json.encodeToString(clearings.map{Re` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-374 | `ui/features/FinancialReconciliationEditor.kt:183` | 核对依据 / 可留空 | `{note=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-375 | `ui/features/FinancialReconciliationEditor.kt:190` | 已核对平台当前义务与退款归属 | `{confirmed=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-376 | `ui/features/FinancialReconciliationEditor.kt:242` | 查看共同付款依据 | `{ expanded=!expanded }` | none | — | 打开预览/查看 |
| FINANCIAL-377 | `ui/features/FinancialRepaymentEditor.kt:84` | 预览实际还款 | `{vm.execute { val input=FinancialRepaymentInput(requireNotNu` | none | — | 打开预览/查看 |
| FINANCIAL-378 | `ui/features/FinancialRepaymentEditor.kt:95` | 还款目标 | `::selectTarget` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-379 | `ui/features/FinancialRepaymentEditor.kt:100` | 实际付款账户 | `{ update(d.copy(source=it,paid="",sourceFee="",sourceFeeBase` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-380 | `ui/features/FinancialRepaymentEditor.kt:103` | 实际扣款总额 | `{ update(d.withPaid(it,paidSuggestion,Currency.valueOf(sourc` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-381 | `ui/features/FinancialRepaymentEditor.kt:108` | YDateFields | `{dateOwner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-382 | `ui/features/FinancialRepaymentEditor.kt:108` | YDateFields | `{_,v->update(d.copy(date=v))}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-383 | `ui/features/FinancialRepaymentEditor.kt:109` | 平台已确认清偿组成 | `{confirmed=it;if(it)details=true}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-384 | `ui/features/FinancialRepaymentEditor.kt:122` | 费用与清偿组成 | `{details=!details}` | none | — | 展开/收起 |
| FINANCIAL-385 | `ui/features/FinancialRepaymentEditor.kt:126` | 平台实际收到 | `{update(d.copy(received=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-386 | `ui/features/FinancialRepaymentEditor.kt:127` | 清偿本金 | `{update(d.copy(principal=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-387 | `ui/features/FinancialRepaymentEditor.kt:128` | 实付利息 | `{update(d.copy(interest=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-388 | `ui/features/FinancialRepaymentEditor.kt:129` | 平台费用 | `{update(d.copy(fee=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-389 | `ui/features/FinancialRepaymentEditor.kt:130` | 利息及平台费用统计合计 | `{update(d.copy(feeBase=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-390 | `ui/features/FinancialRepaymentEditor.kt:131` | 总扣款内含付款端费用 | `{update(d.copy(sourceFee=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-391 | `ui/features/FinancialRepaymentEditor.kt:132` | 付款端费用统计金额 | `{update(d.copy(sourceFeeBase=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-392 | `ui/features/FinancialRepaymentEditor.kt:133` | YDateFields | `{dateOwner=it?.let{1}}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-393 | `ui/features/FinancialRepaymentEditor.kt:133` | YDateFields | `{_,v->update(d.copy(clearingDate=v))}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-394 | `ui/features/FinancialRepaymentEditor.kt:135` | 关联已有付款或转账 | `{links=!links}` | none | — | 展开/收起 |
| FINANCIAL-395 | `ui/features/FinancialRepaymentEditor.kt:139` | 付款来源 | `{id->val e=candidates.firstOrNull{it.id==id};update(d.withPa` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-396 | `ui/features/FinancialRepaymentEditor.kt:143` | 关联先前换汇 / 转账 | `{update(d.copy(linkedTransfer=it))}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-397 | `ui/features/FinancialRepaymentEditor.kt:145` | 分配到多个目标 | `{multiple=!multiple}` | none | — | 展开/收起 |
| FINANCIAL-398 | `ui/features/FinancialRepaymentEditor.kt:148` | 启用独立分配 | `{update(d.copy(singleTarget=false))}` | ToggleOn | — |  |
| FINANCIAL-399 | `ui/features/FinancialRepaymentEditor.kt:150` | 目标账单 | `{row(i,a.copy(billId=it,contractId=null))}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-400 | `ui/features/FinancialRepaymentEditor.kt:151` | 目标合同 | `{row(i,a.copy(contractId=it))}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-401 | `ui/features/FinancialRepaymentEditor.kt:152` | 分配本金 | `{row(i,a.copy(principal=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-402 | `ui/features/FinancialRepaymentEditor.kt:153` | 分配利息 | `{row(i,a.copy(interest=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-403 | `ui/features/FinancialRepaymentEditor.kt:154` | 分配费用 | `{row(i,a.copy(fee=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-404 | `ui/features/FinancialRepaymentEditor.kt:155` | 移除此分配 | `{update(d.copy(rows=d.rows.filterIndexed{n,_->n!=i}))}` | none | — | 打开删除审阅 |
| FINANCIAL-405 | `ui/features/FinancialRepaymentEditor.kt:157` | ＋ 添加清偿目标 | `{update(d.copy(singleTarget=false,rows=d.rows+AllocationDraf` | none | — | 导航/打开/关闭 |
| FINANCIAL-406 | `ui/features/FinancialRepaymentEditor.kt:159` | 还款类型与备注 | `{other=!other}` | none | — | 展开/收起 |
| FINANCIAL-407 | `ui/features/FinancialRepaymentEditor.kt:161` | 还款类型 | `{update(d.copy(type=it))}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-408 | `ui/features/FinancialRepaymentEditor.kt:162` | 备注 | `{update(d.copy(note=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-409 | `ui/features/FinancialReviews.kt:73` | 确认保存规则版本 | `{var saved=false;vm.execute(onSuccess={if(saved)onSaved()}) ` | none | Done |  |
| FINANCIAL-410 | `ui/features/FinancialRuleEditor.kt:63` | 预览规则变更 | `{val captured=draft;vm.execute{vm.financialReviewRefreshed.v` | none | — | 打开预览/查看 |
| FINANCIAL-411 | `ui/features/FinancialRuleEditor.kt:74` | YDateFields | `{calendar=it;trialCalendar=null;deadlineCalendar=null}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-412 | `ui/features/FinancialRuleEditor.kt:74` | YDateFields | `{_,v->change("effective",v);trial=null}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-413 | `ui/features/FinancialRuleEditor.kt:75` | 金融时区 | `{change("zone",it);trial=null}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-414 | `ui/features/FinancialRuleEditor.kt:84` | 付款账户 | `{change("bank",it);trial=null}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-415 | `ui/features/FinancialRuleEditor.kt:89` | 启用期间 ${i+1} | `{change("bonus${i}Enabled",it.toString())}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-416 | `ui/features/FinancialRuleEditor.kt:121` | 阶梯金额按哪个时点的余额 | `{change("tierBalanceDate",it);trial=null}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-417 | `ui/features/FinancialRuleEditor.kt:127` | 高级设置 · 计息、取整与分配 | `{advanced=!advanced}` | none | — | 展开/收起 |
| FINANCIAL-418 | `ui/features/FinancialRuleEditor.kt:149` | 移除此期截止时间 | `{encoded=vm.repository.json.encodeToString(draft.copy(deadli` | none | — | 打开删除审阅 |
| FINANCIAL-419 | `ui/features/FinancialRuleEditor.kt:153` | 变更对应的具体账期 | `{deadlineBill=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-420 | `ui/features/FinancialRuleEditor.kt:154` | YDateFields | `{deadlineCalendar=it;calendar=null;trialCalendar=null}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-421 | `ui/features/FinancialRuleEditor.kt:154` | YDateFields | `{_,v->deadlineAt=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-422 | `ui/features/FinancialRuleEditor.kt:157` | 采用这期截止时间 | `{ val billId=deadlineBill?:return@YButton val d=ChangeDeadli` | Select | — | 填入/选择 |
| FINANCIAL-423 | `ui/features/FinancialRuleEditor.kt:171` | YDateFields | `{trialCalendar=it;calendar=null;deadlineCalendar=null}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-424 | `ui/features/FinancialRuleEditor.kt:171` | YDateFields | `{i,v->change(dateKeys[i],v);trial=null}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-425 | `ui/features/FinancialRuleEditor.kt:172` | 计算预览 | `{val captured=draft;val capturedEncoded=encoded;var computed` | none | — | 打开预览/查看 |
| FINANCIAL-426 | `ui/features/FinancialRuleEditor.kt:186` | label | `{onChange(key,it)}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-427 | `ui/features/FinancialRuleEditor.kt:187` | label | `{onChange(key,it)}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-428 | `ui/features/FinancialRuleEditor.kt:188` | label | `{onChange(key,it)}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-429 | `ui/features/FinancialRuleEditor.kt:206` | 核对额度与恢复设置 | `onLimits` | none | — | 导航/打开 |
| FINANCIAL-430 | `ui/features/ImportantItemsScreen.kt:34` | 重新读取 | `{attempt++}` | none | — | 重读成功不震；仍失败=Error |
| FINANCIAL-431 | `ui/features/ImportantItemsScreen.kt:88` | 调整提醒 | `{vm.navigate("notification-settings")}` | none | — | 导航/打开/关闭 |
| FINANCIAL-432 | `ui/features/ImportantItemsScreen.kt:89` | 查看账户缺口与预算风险 | `onForecast` | none | — | 打开预览/查看 |
| FINANCIAL-433 | `ui/features/ImportantItemsScreen.kt:99` | clickable | `onClick` | none | — | 导航/打开 |
| FINANCIAL-434 | `ui/features/ManualFinancialPlanEditor.kt:53` | 预览已知计划 | `{ val inputName=name;val inputMode=mode;val inputOrigin=orig` | none | — | 打开预览/查看 |
| FINANCIAL-435 | `ui/features/ManualFinancialPlanEditor.kt:68` | 计划名称 | `{name=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-436 | `ui/features/ManualFinancialPlanEditor.kt:70` | 支付 / 借款模式 | `{mode=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-437 | `ui/features/ManualFinancialPlanEditor.kt:71` | 计划来源 | `{origin=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-438 | `ui/features/ManualFinancialPlanEditor.kt:73` | 已有记录 | `{sourceEvent=it}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-439 | `ui/features/ManualFinancialPlanEditor.kt:75` | 计划本金 / 不明可留空 | `{principal=it}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-440 | `ui/features/ManualFinancialPlanEditor.kt:76` | 已知完整逐期计划 | `{complete=it}` | ToggleOn/ToggleOff | — | 组件内置 |
| FINANCIAL-441 | `ui/features/ManualFinancialPlanEditor.kt:80` | 移除此期 | `{activeDate=null;encoded=vm.repository.json.encodeToString(p` | none | — | 打开删除审阅 |
| FINANCIAL-442 | `ui/features/ManualFinancialPlanEditor.kt:82` | ＋ 添加下一期 | `{activeDate=null;encoded=vm.repository.json.encodeToString(p` | Step | — |  |
| FINANCIAL-443 | `ui/features/ManualFinancialPlanEditor.kt:88` | YDateFields | `onDateOwner` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-444 | `ui/features/ManualFinancialPlanEditor.kt:88` | YDateFields | `{_,v->onChange(p.copy(date=v))}` | Select | — | 组件内置：选日期=Select，换月=Step |
| FINANCIAL-445 | `ui/features/ManualFinancialPlanEditor.kt:89` | 已知合计 | `{onChange(p.copy(total=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-446 | `ui/features/ManualFinancialPlanEditor.kt:90` | 其中本金 / 不明留空 | `{onChange(p.copy(principal=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-447 | `ui/features/ManualFinancialPlanEditor.kt:91` | 其中利息与费用 / 不明留空 | `{onChange(p.copy(fee=it))}` | none | — | 系统输入法负责逐键反馈 |
| FINANCIAL-448 | `ui/features/ManualFinancialPlanEditor.kt:92` | 付款账户 / 尚未确定可留空 | `{onChange(p.copy(paymentAccountId=it))}` | Select | — | 组件内置：选中菜单项 |
| FINANCIAL-449 | `ui/features/ManualFinancialPlanEditor.kt:107` | 确认保存已知计划 | `{var saved=false;vm.execute(onSuccess={if(saved)onSaved(r.pl` | none | Done |  |

## 云端办理方案与月度评语（44）

| ID | 位置 | 元素 | 回调 | 按下 | 结果 | 说明 |
|---|---|---|---|---|---|---|
| COMMAND-001 | `ui/features/command/CommandEffectViews.kt:149` | 逐项核对 | `onClose` | none | — | 关闭/返回不加触感 |
| COMMAND-002 | `ui/features/command/CommandEffectViews.kt:184` | 上一页 | `{page--}` | Step | — |  |
| COMMAND-003 | `ui/features/command/CommandEffectViews.kt:186` | 下一页 | `{page++}` | Step | — |  |
| COMMAND-004 | `ui/features/command/CommandFollowupViews.kt:74` | 重试 | `{if(payload.kind=="EXPORT")details=true else vm.execute{comm` | none | — | 重试（失败走 Error） |
| COMMAND-005 | `ui/features/command/CommandFollowupViews.kt:75` | 详情 | `{details=true}` | none | — | 导航/打开/关闭 |
| COMMAND-006 | `ui/features/command/CommandFollowupViews.kt:77` | 继续 | `{details=true}` | none | — | 导航/打开/关闭 |
| COMMAND-007 | `ui/features/command/CommandFollowupViews.kt:80` | title | `{details=false}` | none | — | 关闭/返回不加触感 |
| COMMAND-008 | `ui/features/command/CommandFollowupViews.kt:84` | 重试此步骤 | `{vm.execute{commands.followups.retry(row.id)}}` | none | — | 重试（失败走 Error） |
| COMMAND-009 | `ui/features/command/CommandFollowupViews.kt:88` | 打开草稿继续记账 | `{ var draftSnapshot:BookSnapshot?=null vm.execute(onSuccess=` | none | — | 导航/打开 |
| COMMAND-010 | `ui/features/command/CommandFollowupViews.kt:97` | 已在本机处理，结束引导 | `{vm.execute{commands.followups.completeInteraction(row.id)}}` | none | Done | 结束引导 |
| COMMAND-011 | `ui/features/command/CommandFollowupViews.kt:101` | 选择位置并导出 | `{picker.launch(null)}` | none | — | 外部选择器 |
| COMMAND-012 | `ui/features/command/CommandFollowupViews.kt:106` | 检查并申请权限 | `{ val required=if(location)arrayOf(Manifest.permission.ACCES` | none | — | 打开预览/查看 |
| COMMAND-013 | `ui/features/command/CommandFollowupViews.kt:111` | 保留当前权限并结束 | `{vm.execute{commands.followups.completeInteraction(row.id)}}` | none | Done | 结束引导 |
| COMMAND-014 | `ui/features/command/CommandFollowupViews.kt:171` | 另存原始方案文件 | `{picker.launch(null)}` | none | — | 外部选择器 |
| COMMAND-015 | `ui/features/command/CommandScreens.kt:38` | 提出办理需求 | `{vm.navigate("command-submit")}` | none | — | 导航/打开/关闭 |
| COMMAND-016 | `ui/features/command/CommandScreens.kt:39` | 月度评语与历史 | `{vm.navigate("monthly-history")}` | none | — | 导航/打开/关闭 |
| COMMAND-017 | `ui/features/command/CommandScreens.kt:40` | 方案历史 | `{vm.navigate("command-history")}` | none | — | 导航/打开/关闭 |
| COMMAND-018 | `ui/features/command/CommandScreens.kt:68` | 任务名称 | `{title=it.take(150)}` | none | — | 系统输入法负责逐键反馈 |
| COMMAND-019 | `ui/features/command/CommandScreens.kt:69` | 希望办理什么 | `{request=it.take(19800)}` | none | — | 系统输入法负责逐键反馈 |
| COMMAND-020 | `ui/features/command/CommandScreens.kt:71` | 添加截图或文件 | `{picker.launch(arrayOf("*/*"))}` | none | — | 导航/打开/关闭 |
| COMMAND-021 | `ui/features/command/CommandScreens.kt:75` | 记录范围 | `{mode=it}` | Select | — | 组件内置：选中菜单项 |
| COMMAND-022 | `ui/features/command/CommandScreens.kt:76` | YDateFields | `{dateOwner=it}` | Select | — | 组件内置：选日期=Select，换月=Step |
| COMMAND-023 | `ui/features/command/CommandScreens.kt:76` | YDateFields | `{i,v->if(i==0)from=v else through=v}` | Select | — | 组件内置：选日期=Select，换月=Step |
| COMMAND-024 | `ui/features/command/CommandScreens.kt:77` | 账户 | `{account=it}` | Select | — | 组件内置：选中菜单项 |
| COMMAND-025 | `ui/features/command/CommandScreens.kt:89` | 上传并提交办理需求 | `{ val ready=bundle?.takeIf{exportedScope==scope}?:return@YBu` | none | Done |  |
| COMMAND-026 | `ui/features/command/CommandScreens.kt:108` | 查看回答 | `{vm.openCommandPlan(row.planId)}` | none | — | 打开预览/查看 |
| COMMAND-027 | `ui/features/command/CommandScreens.kt:158` | 预览建议步骤 | `{vm.execute{commands.preview(row.planId)}}` | none | — | 打开预览/查看 |
| COMMAND-028 | `ui/features/command/CommandScreens.kt:200` | 逐项查看前后与连带影响 | `{details=step.objects}` | none | — | 打开预览/查看 |
| COMMAND-029 | `ui/features/command/CommandScreens.kt:208` | 查看全部净影响及未变化的相关值 | `{details=model.net}` | none | — | 打开预览/查看 |
| COMMAND-030 | `ui/features/command/CommandScreens.kt:248` | 步骤 $stepNumber ·  | `{checked->checks=if(checked)checks+requirement.key else chec` | ToggleOn/ToggleOff | — | 组件内置 |
| COMMAND-031 | `ui/features/command/CommandScreens.kt:250` | 确认撤销整份云端方案 | `{vm.execute{commands.confirm(row.planId,checks)}}` | none | Restored |  |
| COMMAND-032 | `ui/features/command/CommandScreens.kt:255` | 重新试运行 | `{checks=emptySet();vm.execute{commands.preview(row.planId)}}` | none | — | 打开预览/查看 |
| COMMAND-033 | `ui/features/command/CommandScreens.kt:256` | 拒绝此方案 | `{if(!busy)vm.execute{commands.reject(row.planId)}}` | none | Removed |  |
| COMMAND-034 | `ui/features/command/CommandScreens.kt:268` | 撤销整份云端方案 | `{ vm.execute{val inverse=commands.prepareUndo(row.planId);co` | none | — | 生成撤销预览 |
| COMMAND-035 | `ui/features/command/MonthlyScreens.kt:50` | 月度计划 | `{text=it.take(20000)}` | none | — | 系统输入法负责逐键反馈 |
| COMMAND-036 | `ui/features/command/MonthlyScreens.kt:51` | 保存月度计划 | `{val value=text?:return@YButton;vm.execute(onSuccess={vm.nav` | none | Done |  |
| COMMAND-037 | `ui/features/command/MonthlyScreens.kt:52` | 评语历史与提交 | `{vm.navigate("monthly-history")}` | none | — | 导航/打开/关闭 |
| COMMAND-038 | `ui/features/command/MonthlyScreens.kt:78` | 确认上传并提交评语 | `{val ready=bundle?:return@YButton;vm.execute(onSuccess={vm.n` | none | Done |  |
| COMMAND-039 | `ui/features/command/MonthlyScreens.kt:79` | 云端交接台 | `{vm.navigate("cloud")}` | none | — | 导航/打开/关闭 |
| COMMAND-040 | `ui/features/command/MonthlyScreens.kt:111` | 收起结构项目 | `{expanded=!expanded}` | none | — | 展开/收起 |
| COMMAND-041 | `ui/features/command/MonthlyScreens.kt:129` | 提交这个月的评语 | `{vm.openMonthlyReview(month)}` | none | — | 导航/打开 |
| COMMAND-042 | `ui/features/command/MonthlyScreens.kt:130` | 编辑月计划 | `{vm.navigate("monthly-plan")}` | none | — | 导航/打开/关闭 |
| COMMAND-043 | `ui/features/command/MonthlyScreens.kt:131` | 云端任务进度与重试 | `{vm.navigate("cloud")}` | none | — | 导航/打开/关闭 |
| COMMAND-044 | `ui/features/command/MonthlyScreens.kt:142` | 查看评语与建议 | `{vm.openCommandPlan(it.planId)}` | none | — | 打开预览/查看 |
