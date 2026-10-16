package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.*
import java.time.format.DateTimeFormatter

@Serializable private data class PaymentTierDraft(val bound:String="",val amount:String="")

@Composable internal fun FinancialPoolPaymentEditor(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,plan:FinancialPlanEntity,vm:AppViewModel,onClose:()->Unit,onReviewPool:(()->Unit)?=null,onReviewBill:((String)->Unit)?=null) {
    FinancialDraftHost(s,FinancialDraftContext("POOL_PAYMENT",a.id,targetId=plan.id),vm,onClose) {
        FinancialPoolPaymentEditorContent(s,a,f,plan,vm,onClose,onReviewPool,onReviewBill)
    }
}

@Composable private fun FinancialPoolPaymentEditorContent(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,plan:FinancialPlanEntity,vm:AppViewModel,onClose:()->Unit,onReviewPool:(()->Unit)?,onReviewBill:((String)->Unit)?) {
    val session=requireNotNull(LocalFinancialDraft.current);val c=Currency.valueOf(a.currency)
    val definition=vm.repository.json.decodeFromString<FinancialPredictionInput>(plan.termsJson!!)
    val today=LocalDate.now(vm.repository.clock).toString()
    var effective by financialField("effective"){today}
    var from by financialField<String?>("from"){definition.terms.paymentDates.firstOrNull{it>=today}}
    var method by financialField<String?>("method"){null}
    var amount by financialField("amount"){""}
    var tiersJson by financialField("tiers"){"[]"}
    var includes by financialField<Boolean?>("includes"){null}
    var tierDate by financialField<String?>("tierDate"){null}
    var fee by financialField("fee"){""}
    var feeDate by financialField<String?>("feeDate"){null}
    var feeIncluded by financialField("feeIncluded"){false}
    var appliedAt by financialField("appliedAt"){vm.repository.clock.instant().toString()}
    val tiers=vm.repository.json.decodeFromString<List<PaymentTierDraft>>(tiersJson)
    fun tier(index:Int,value:PaymentTierDraft){tiersJson=vm.repository.json.encodeToString(tiers.mapIndexed{i,t->if(i==index)value else t})}
    var review by remember{mutableStateOf<PoolPaymentChangeReview?>(null)}
    var applying by remember{mutableStateOf(false)}
    var confirmed by remember{mutableStateOf(false)}
    var exception by remember{mutableStateOf(false)}
    var refreshed by remember{mutableStateOf(false)}
    var calendar by remember{mutableStateOf<Int?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val formScroll=rememberScrollState();val previewScroll=rememberScrollState();val applyScroll=rememberScrollState()
    LaunchedEffect(review,applying){if(applying)applyScroll.scrollTo(0)else if(review!=null)previewScroll.scrollTo(0)}
    YSheet(if(applying)word("记录已办理","手続き記録")else if(review!=null)word("共同月付款比较","共通月払いの比較")else word("调整共同月付款","共通月払いの変更"),{
        if(applying)applying=false else if(review!=null)review=null else onClose()
    },scrollState=if(applying)applyScroll else if(review!=null)previewScroll else formScroll) {
        val r=review
        if(r==null) {
            FinancialDraftNotice()
            ThemeAuditPanel(plan.name) {
                YText(word("调整原池的月付款金额或计算方式。保留同一个池与利率规则，已确认、关联付款和逾期账单按原义务保留。","元プールの月支払額・方式を変更します。同じプールと金利条件を保持し、確定・支払関連・期限経過の請求は元の債務を保持します。"),12)
            }
            definition.pool!!.paymentChanges.forEach{PoolPaymentSettingFacts(it.setting,c)}
            YDateFields(listOf(word("变更生效日","変更適用日") to effective),calendar,{calendar=it},{_,v->effective=v})
            YSelect(word("从哪次付款起采用","適用する最初の支払日"),from,listOf(null to word("请选择","選択してください"))+definition.terms.paymentDates.filter{it>=effective}.map{it as String? to it},{from=it},Modifier.testTag("pool-payment-from"))
            YSelect(word("新的月付款方式","新しい月支払方式"),method,listOf(null to word("请选择","選択してください"))+PaymentMethod.entries.map{it.name as String? to poolPaymentMethod(it)},{method=it},Modifier.testTag("pool-payment-method"))
            if(method==PaymentMethod.BALANCE_TIERS.name) {
                YSelect(word("阶梯金额包含费用吗","残高別の支払額は手数料込みか"),includes,listOf(null to word("请选择","選択してください"),false to word("本金金额，费用另加","元金額・手数料は別"),true to word("含费用总金额","手数料込みの総額")),{includes=it})
                YSelect(word("阶梯余额判定日","残高の判定日"),tierDate,listOf(null to word("请选择","選択してください"),TierBalanceDate.BILL_CLOSING.name to word("计费截止时","計算終了時点"),TierBalanceDate.PAYMENT_DATE.name to word("付款日还本前","支払日の元金返済前")),{tierDate=it})
                tiers.forEachIndexed{i,t->Panel {
                    YField(word("余额上限 / 末档不限留空","残高上限・最終段階の上限なしは空欄")+" · "+a.currency,t.bound,{tier(i,t.copy(bound=it))},Modifier.testTag("pool-payment-tier-bound-$i"),numeric=true)
                    YField(word("该档月付款","この段階の月支払額")+" · "+a.currency,t.amount,{tier(i,t.copy(amount=it))},Modifier.testTag("pool-payment-tier-amount-$i"),numeric=true)
                    YButton(word("移除此档","この段階を削除"),{tiersJson=vm.repository.json.encodeToString(tiers.filterIndexed{n,_->n!=i})})
                }}
                YButton(word("添加一档","段階を追加"),{tiersJson=vm.repository.json.encodeToString(tiers+PaymentTierDraft())},Modifier.testTag("pool-payment-add-tier"))
            }else YField(word("新月付款金额","新しい月支払額")+" · "+a.currency,amount,{amount=it},Modifier.testTag("pool-payment-amount"),numeric=true)
            YField(word("办理费 / 未知留空，无费用填0","変更手数料・不明は空欄、無料は0")+" · "+a.currency,fee,{fee=it},Modifier.testTag("pool-payment-fee"),numeric=true)
            YSelect(word("办理费付款日 / 无费用可不选","手数料支払日・無料は未選択"),feeDate,listOf(null to word("请选择","選択してください"))+definition.terms.paymentDates.filter{it>=effective}.map{it as String? to it},{feeDate=it;feeIncluded=false})
            YToggle(word("保留账单费用已包含本次办理费","保持する請求費用に今回の手数料を含む"),feeIncluded,{feeIncluded=it},Modifier.testTag("pool-payment-fee-included"))
            YText(word("费用进入已有付款日。保留账单不会自动改写；若费用不足，请先核对该期账单。试算和记录办理都不改变实际资金或欠款本金。","手数料は既存の支払日へ加えます。保持する請求は自動変更せず、費用が不足する場合は元の請求を確認してください。比較・手続き記録で実資金・元金残高は変えません。"),11)
            if(onReviewBill!=null)f.bills.filter{it.active&&it.date==feeDate&&(it.planId==plan.id||definition.pool.facts?.retained?.any{r->r.bill.id==it.id}==true)}.forEach{bill->
                YButton(word("核对本期费用账单","当期の費用請求を確認")+" · "+bill.date+" · "+bill.number,{onReviewBill(bill.id)},Modifier.testTag("pool-payment-bill-${bill.id}"))
            }
            if(onReviewPool!=null)YButton(word("核对原池并保留输入","元プールを確認して入力を保持"),onReviewPool,Modifier.testTag("pool-payment-review-original"))
            if(refreshed)FinanceRefreshed()
            YButton(word("比较月付款调整","月支払の変更を比較"),{vm.execute {
                val setting=RevolvingPaymentSetting(from?:error("请选择首次付款日 / 最初の支払日を選択してください"),method?.let{PaymentMethod.valueOf(it)},amount.takeIf{it.isNotBlank()}?.let{Money.parse(it,c).minor},
                    if(method==PaymentMethod.BALANCE_TIERS.name)tiers.map{PaymentTier(it.bound.takeIf{v->v.isNotBlank()}?.let{v->Money.parse(v,c).minor},Money.parse(it.amount,c).minor)}else emptyList(),includes,tierDate?.let{TierBalanceDate.valueOf(it)})
                review=vm.repository.previewPoolPaymentChange(s.book.id,PoolPaymentChangeInput(plan.id,effective,setting,fee.takeIf{it.isNotBlank()}?.let{Money.parse(it,c).minor},feeDate,feeIncluded,cashReferenceRates(s,f,vm.referenceRates)))
            }},Modifier.testTag("pool-payment-preview"),primary=true,enabled=!busy)
        }else if(!applying) {
            PoolPaymentChangeComparison(r,c,s)
            YButton(word("已在平台办理，记录结果","返済先で手続き済み・結果を記録"),{applying=true;confirmed=false},Modifier.testTag("pool-payment-record"),primary=true,enabled=!busy&&r.calculation is Calculation.Ready)
            YButton(word("返回修改","修正に戻る"),{review=null},enabled=!busy)
        }else {
            YText(plan.name,18);PoolPaymentSettingFacts(r.input.setting,c)
            val local=Instant.parse(appliedAt).atZone(ZoneId.of(r.zoneId)).toLocalDateTime().toString()
            YText(r.zoneId,11)
            YDateFields(listOf(word("实际办理时间","実際の手続き時刻") to local),calendar,{calendar=it},{_,v->appliedAt=LocalDateTime.parse(v).atZone(ZoneId.of(r.zoneId)).toInstant().toString()})
            YToggle(word("已在平台实际办理并确认以上条件","返済先で手続き済みで上記の条件を確認した"),confirmed,{confirmed=it},Modifier.testTag("pool-payment-platform-confirmed"))
            if(r.deadlines.any{Instant.parse(appliedAt)>it.instant()})YToggle(word("平台已接受期限外变更","返済先が期限後の変更を受理した"),exception,{exception=it},Modifier.testTag("pool-payment-exception"))
            YButton(word("确认记录已办理","手続き済みとして確定"),{var saved=false;vm.execute(onSuccess={if(saved)onClose()else{review=null;applying=false;confirmed=false;refreshed=true}}) {
                saved=commitFinancialDraft(session){vm.repository.commitPoolPaymentChange(r,appliedAt,confirmed,exception)}!=null
            }},Modifier.testTag("pool-payment-confirm"),primary=true,enabled=!busy&&confirmed)
        }
    }
}

@Composable internal fun poolPaymentMethod(method:PaymentMethod)=when(method) {
    PaymentMethod.FIXED_PRINCIPAL->word("本金定额","元金定額")
    PaymentMethod.TOTAL_PAYMENT->word("含费用总额","手数料込み総額")
    PaymentMethod.BALANCE_TIERS->word("余额阶梯","残高別コース")
}

@Composable internal fun PoolPaymentSettingFacts(setting:RevolvingPaymentSetting,c:Currency) {
    YText(setting.fromPaymentDate,12)
    YText(setting.method?.let{poolPaymentMethod(it)}?:word("方式待补充","方式未入力"),14)
    if(setting.method==PaymentMethod.BALANCE_TIERS) {
        setting.tiers.forEach{YText((it.upToMinor?.let{n->"≤ "+Money(c,n).display()}?:word("其余余额","それ以上"))+" → "+Money(c,it.paymentMinor).display(),12)}
        YText((if(setting.tierPaymentIncludesFees==true)word("含费用","手数料込み")else word("费用另加","手数料別"))+" · "+if(setting.tierBalanceDate==TierBalanceDate.BILL_CLOSING)word("计费截止余额","計算終了時点の残高")else word("付款日前余额","支払前残高"),11)
    }else setting.paymentMinor?.let{YText(Money(c,it).display(),16)}
}

@Composable private fun PoolPaymentChangeComparison(r:PoolPaymentChangeReview,c:Currency,s:BookSnapshot) {
    ThemeAuditPanel(word("同一个池的新月付款","同じプールの新しい月払い"),Modifier.testTag("pool-payment-comparison")) {
        YText(r.original.name,16);PoolPaymentSettingFacts(r.input.setting,c)
        YText(word("原池和实际账务保留，只调整未来可重算的付款。","元プールと実記録を保持し、変更できる将来分だけを反映します。"),11)
    }
    when(val result=r.calculation) {
        is Calculation.Incomplete->FinancialReadiness(RuleReadiness.Incomplete(result.fields),word("需要核对","確認が必要"))
        is Calculation.Unsupported->FinancialCalculationResult(result,c)
        is Calculation.CannotSettle->FinancialCalculationResult(result,c)
        is Calculation.Ready->{
            val comparison=r.comparison!!
            if(r.prediction?.plan?.complete!=true)YText(word("原池还有范围外本金或后续新增本金，请继续核对完整范围。","元プールには範囲外元金・後日の追加元金があります。全体の範囲を引き続き確認してください。"),11,color=LocalYohaku.current.warm)
            PoolPaymentComparisonFacts(comparison,c,s)
        }
    }
    if(r.deadlines.isEmpty())YText(word("具体变更期限未配置，请以平台确认为准。","変更締切は未設定です。返済先で確認してください。"),11,color=LocalYohaku.current.warm)
    r.deadlines.forEach{YText(it.date+" "+it.time+" · "+it.zoneId,12)}
}

@Composable internal fun PoolPaymentComparisonFacts(value:FinancialArrangementComparison,c:Currency,s:BookSnapshot) {
    val unknown=word("未知 / 范围未结清","不明・範囲内で未完済")
    fun money(n:Long?)=n?.let{Money(c,it).display()}?:unknown
    RecordFacts(listOf(word("当期扣款减少","当期の支払減額") to money(value.currentReductionMinor),word("全程增加费用","全期間の追加手数料") to money(value.totalExtraFeeMinor),word("范围后剩余本金","範囲後の残元金") to money(value.proposedRemainingPrincipalMinor)))
    YText(word("资金比较截至","資金比較の終了日：")+value.fundingThrough.orEmpty()+word("，预计收入另列。","・収入見込みは別に表示。"),11)
    value.funding.forEach{fund->val a=s.accounts.first{it.id==fund.accountId};val currency=Currency.valueOf(fund.currency)
        fun gap(n:Long?)=n?.let{(if(fund.referenceEstimated)"≈ "else"")+Money(currency,it).display()}?:unknown
        ThemeAuditPanel(a.name) {
            RecordFacts(listOf(word("基础最大缺口 · 原 / 新","基本の最大不足・元 / 新") to (gap(fund.beforeGapMinor)+" → "+gap(fund.afterGapMinor)),word("含预计收入 · 原 / 新","収入見込み含む・元 / 新") to (gap(fund.beforeIncomeGapMinor)+" → "+gap(fund.afterIncomeGapMinor)),word("首次缺口 · 原 / 新","最初の不足日・元 / 新") to ((fund.beforeRiskDate?:"—")+" / "+(fund.afterRiskDate?:"—"))))
        }
    }
    value.fundingRates.forEach{ReferenceRateFacts(it)}
    if(value.proposedRemainingPrincipalMinor!=0L||value.fundingUnassigned)YText(word("仍有范围外本金或未定扣款账户，不能认定整体资金充足。","範囲外元金・引落口座未定があり、全体の資金充足は判定できません。"),11,color=LocalYohaku.current.warm)
    ArrangementRows(word("维持原方式","元の方式を維持"),value.original,c,value.originalUnscheduledPrincipalMinor.takeIf{value.originalComplete})
    ArrangementRows(word("新月付款","新しい月払い"),value.proposed,c,value.proposedRemainingPrincipalMinor.takeIf{value.proposedComplete})
}

@Composable internal fun PoolPaymentChangeHistory(change:PoolPaymentChange,c:Currency,s:BookSnapshot,zone:ZoneId) {
    ThemeAuditPanel(word("月付款变更","月払いの変更"),Modifier.testTag("pool-payment-history")) {
        YText(word("办理时保存的条件与比较","手続き時点の条件・比較"),12,Modifier.testTag("pool-payment-history-summary"))
        PoolPaymentSettingFacts(change.setting,c)
        YText(word("生效日：","適用日：")+change.effectiveDate,12)
        YText(Instant.parse(change.appliedAt).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),11)
        YText(word("办理费：","変更手数料：")+Money(c,change.feeMinor).display()+change.feeDate?.let{" · "+it}.orEmpty(),12)
        if(change.feeIncludedInRetained)YText(word("保留费用已含本次办理费，原费用基准无法分离，费用差额保持未知。","保持費用に今回の手数料を含み、元の費用基準を分離できないため差額は不明です。"),11)
        if(change.exceptionAccepted)YText(word("平台已接受期限外变更","返済先が期限後の変更を受理済み"),11)
        change.comparison?.let{PoolPaymentComparisonFacts(it,c,s)}
    }
}
