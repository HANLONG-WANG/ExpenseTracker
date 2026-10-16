package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.time.LocalDate

@Serializable private data class PoolAdoptedFeeDraft(val arrangementId:String,val date:String,val included:Boolean=false,val waived:String="0")

@Serializable private data class PoolPeriodDraft(val date:String,val start:String,val end:String)

@Composable internal fun FinancialPoolEditor(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,old:FinancialPlanEntity?,vm:AppViewModel,onClose:()->Unit,onBill:((String)->Unit)?=null) {
    FinancialDraftHost(s,FinancialDraftContext("POOL",a.id,targetId=old?.id),vm,onClose){FinancialPoolEditorContent(s,a,f,old,vm,onClose,onBill)}
}

@Composable private fun FinancialPoolEditorContent(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,old:FinancialPlanEntity?,vm:AppViewModel,onClose:()->Unit,onBill:((String)->Unit)?) {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    val c=Currency.valueOf(a.currency);val mode=if(a.type==AccountType.CREDIT.name)FinanceMode.REVOLVING else FinanceMode.CREDIT_LINE
    val saved=remember(old?.id){old?.termsJson?.let{vm.repository.json.decodeFromString<FinancialPredictionInput>(it)}}
    var name by financialField("name"){old?.name?:a.name}
    var opening by financialField("opening"){saved?.pool?.openingMinor?.let{Money(c,it).decimal.toPlainString()}.orEmpty()}
    var sourceDate by financialField("sourceDate"){saved?.pool?.sourceDate?.name}
    var bank by financialField("bank"){saved?.paymentAccountId}
    var count by financialField("count"){saved?.terms?.paymentDates?.size?.toString()?:"12"}
    var firstNumber by financialField("firstNumber"){saved?.terms?.firstPeriodNumber?.toString()?:"1"}
    var firstStart by financialField("firstStart"){saved?.terms?.accrualWindows?.firstOrNull()?.start?:a.openingDate}
    var firstPay by financialField("firstPay"){saved?.terms?.paymentDates?.firstOrNull()?:LocalDate.now(vm.repository.clock).toString()}
    var firstEnd by financialField("firstEnd"){saved?.terms?.accrualWindows?.firstOrNull()?.end?:LocalDate.now(vm.repository.clock).toString()}
    var encoded by financialField("encoded"){vm.repository.json.encodeToString(saved?.terms?.paymentDates?.mapIndexed{i,d->PoolPeriodDraft(d,saved.terms.accrualWindows[i].start,saved.terms.accrualWindows[i].end)}.orEmpty())}
    var shareJson by financialField("shareJson"){vm.repository.json.encodeToString(saved?.pool?.unallocatedShares.orEmpty().mapValues{Money(c,it.value).decimal.toPlainString()})}
    var adopt by financialField("adopt"){saved?.pool?.adoptExisting?:false}
    val adoptable=f.adoptedArrangements(a.id,mode.name,true).filter{it.changeFeeMinor>0}
    var adoptedFeeJson by financialField("adoptedFees"){vm.repository.json.encodeToString(adoptable.map{change->
        val previous=saved?.pool?.adoptedFees?.firstOrNull{it.arrangementId==change.id}
        val first=f.bills.filter{it.planId==change.targetPlanId}.minByOrNull{it.number}?.date.orEmpty()
        PoolAdoptedFeeDraft(change.id,previous?.date?:first,previous?.includedInRetained?:false,Money(c,previous?.waivedMinor?:0).decimal.toPlainString())
    })}
    val feeDrafts=vm.repository.json.decodeFromString<List<PoolAdoptedFeeDraft>>(adoptedFeeJson)
    fun fee(value:PoolAdoptedFeeDraft){adoptedFeeJson=vm.repository.json.encodeToString(feeDrafts.filter{it.arrangementId!=value.arrangementId}+value)}
    var editingPeriod by rememberSaveable{mutableStateOf<Int?>(null)}
    var calendar by remember{mutableStateOf<Int?>(null)}
    var review by remember{mutableStateOf<FinancialPredictionReview?>(null)}
    var stale by remember{mutableStateOf(false)}
    val rows=vm.repository.json.decodeFromString<List<PoolPeriodDraft>>(encoded)
    val shares=vm.repository.json.decodeFromString<Map<String,String>>(shareJson)
    val busy by vm.busy.collectAsStateWithLifecycle();val form=rememberScrollState();val preview=rememberScrollState()
    val memberPlans=f.plans.filter{it.active&&it.accountId==a.id&&it.mode==mode.name&&it.id!=old?.id}
    val retained=f.bills.filter{b->b.active&&(memberPlans.any{it.id==b.planId}||b.planId==old?.id)&&(b.confirmed||b.date<LocalDate.now(vm.repository.clock).toString()||s.repaymentAllocations.any{it.billId==b.id}||s.billAdjustments.any{it.billId==b.id})}
    LaunchedEffect(review){if(review!=null)preview.scrollTo(0)}
    YSheet(if(review==null)word("账户余额池","残高プール")else word("余额池预测","プールの予測"),{if(review!=null)review=null else onClose()},scrollState=if(review==null)form else preview) {
        if(stale)FinanceRefreshed()
        val r=review
        if(r!=null) {
            val facts=r.input.pool!!.facts!!
            YText(r.plan.name,18)
            RecordFacts(listOf(word("期初循环本金","期首元金") to Money(c,r.input.pool.openingMinor).display(),word("纳入本金变动","元金変動") to facts.sources.size.toString(),word("保留原账单","保持請求") to facts.retained.size.toString()))
            facts.arrangementFees.forEach{fee->YText(fee.date+" · "+word("本次安排费用：","今回の予定手数料：")+Money(c,fee.minor).display(),12)}
            r.input.pool.adoptedFees.filter{it.waivedMinor>0}.forEach{fee->YText(word("原办理费中平台明确取消：","元の手数料のうち返済先取消額：")+Money(c,fee.waivedMinor).display()+word("；实际已付款不自动退回。","。実支払は自動返金しません。"),11)}
            r.input.pool.paymentChanges.forEach{PoolPaymentSettingFacts(it.setting,c)}
            if(r.input.pool.paymentChanges.isNotEmpty())YText(word("采用已办理的月付款条件；利率规则中的默认月付金额不覆盖它。再次调整请记录新的办理结果。","手続き済みの月支払条件を採用し、金利条件の既定月額では上書きしません。再変更は新しい手続き結果として記録してください。"),11)
            facts.retained.forEach{b->YText(b.bill.date+" · "+Money(c,b.totalMinor).display()+" · "+word("保留原账单，不另加一份","元の請求を保持し、重複作成しません"),11)}
            when(val result=r.calculation) {
                is Calculation.Ready->{
                    result.value.forEach{p->Panel(Modifier.testTag("pool-period-${p.period.number}")) {
                        YText(p.period.date.toString(),14)
                        RecordFacts(listOf(word("本金","元金") to Money(c,p.period.principalMinor).display(),word("利息 / 费用","利息・手数料") to Money(c,p.period.feeMinor).display(),word("合计","合計") to Money(c,p.period.totalMinor).display(),word("期后本金","支払後の元金") to Money(c,p.period.remainingPrincipalMinor).display()))
                        FinancialProjectionFacts(p.basis,c)
                    }}
                    val remaining=result.value.lastOrNull()?.period?.remainingPrincipalMinor?:0
                    YText(word("预测范围后剩余：","予測範囲後の残額：")+Money(c,remaining).display(),14,Modifier.testTag("pool-remaining"))
                }
                is Calculation.Incomplete->FinancialReadiness(RuleReadiness.Incomplete(result.fields),word("需要核对","確認が必要"))
                is Calculation.Unsupported->FinancialCalculationResult(result,c)
                is Calculation.CannotSettle->FinancialCalculationResult(result,c)
            }
            ThemeSection(word("来源与变动","対象と変動"),"PRINCIPAL FACTS")
            facts.sources.forEach{source->YText(source.date+" · "+Money(c,source.principalMinor).display()+" · "+(source.eventId?.let{id->s.events.firstOrNull{it.id==id}?.merchant}.orEmpty()),11)}
            facts.payments.forEach{p->YText(p.date+" · "+word("实际清偿本金","実返済元金")+" · "+Money(c,p.allocations.sumOf{it.principalMinor?:0}+(p.explicitMinor?:0)).display(),11)}
            YText(word("只保存一套账户循环预测；不增加借入、消费、收入或资金。确认/已付款关联/逾期账单按原义务保留，未来未付款才进入假设。","口座の循環予測を1つ保存し、借入・支出・収入・資金は増やしません。確定・支払関連・期限経過の請求は保持し、将来の未払分のみを仮定します。"),11)
            YButton(word("确认保存余额池","プールを保存"),{var ok=false;vm.execute(onSuccess={if(ok)onClose()else{review=null;stale=true}}){ok=commitFinancialDraft(draftSession){vm.repository.commitFinancialPrediction(r)}!=null}},Modifier.testTag("pool-confirm"),primary=true,enabled=!busy&&r.calculation is Calculation.Ready)
            YButton(word("返回修改","修正に戻る"),{review=null},Modifier.testTag("pool-back"),enabled=!busy)
        }else {
            FinancialDraftNotice()
            YText(word("账户内的循环本金共同计费、共同计算一次月付款，固定贷款与一次付/分期不混入。","口座内の循環元金をまとめて計算し、月の支払額を1回計算します。固定ローン・一括・分割分は含めません。"),11)
            YField(word("计划名称","プラン名"),name,{name=it})
            YText(word("账户期初：","口座期首：")+a.openingDate,12)
            YField(word("期初属于循环的本金 / 没有请填0","循環分の期首元金・なければ0"),opening,{opening=it},Modifier.testTag("pool-opening"),numeric=true)
            YSelect(word("新增本金起算日期","追加元金の開始日"),sourceDate,listOf(null to word("请选择","選択してください"),"TRANSACTION" to word("交易 / 借入发生日","取引・借入日"),"ACCOUNT_POSTING" to word("账户入账日","口座計上日")),{sourceDate=it})
            YSelect(word("实际扣款账户 / 可留空","実引落口座・未定は空欄"),bank,listOf(null to word("未确定 / 按规则","未定・条件から取得"))+s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}.map{it.id as String? to it.name},{bank=it})
            YField(word("预测期数","予測回数"),count,{count=it},numeric=true)
            YField(word("起始账期序号","開始請求回数"),firstNumber,{firstNumber=it},numeric=true)
            YDateFields(listOf(word("首期计费开始","初回計算開始") to firstStart,word("首期计费结束","初回計算終了") to firstEnd,word("首期付款日","初回支払日") to firstPay),calendar?.takeIf{it<3},{calendar=it},{i,v->when(i){0->firstStart=v;1->firstEnd=v;else->firstPay=v}})
            YButton(word("生成按月候选","月ごとの候補を作成"),{vm.execute {
                val n=count.toIntOrNull()?:error("请输入期数 / 回数を入力してください");require(n in 1..1200)
                val end=LocalDate.parse(firstEnd);val payment=LocalDate.parse(firstPay)
                val rule=f.rules.cardRule(a.id,firstStart,mode.name)?.let{vm.repository.json.decodeFromString<FinancialRuleVersion>(it.json)}?.revolving?.interest
                encoded=vm.repository.json.encodeToString(List(n){i->val previous=end.plusMonths(i-1L)
                    val start=if(i==0)LocalDate.parse(firstStart)else if(rule?.frequency==ChargeFrequency.MONTHLY||rule?.includeStart==true&&rule.includeEnd==true)previous.plusDays(1)else if(rule?.includeStart==false&&rule.includeEnd==false)previous.minusDays(1)else previous
                    PoolPeriodDraft(payment.plusMonths(i.toLong()).toString(),start.toString(),end.plusMonths(i.toLong()).toString())})
            }},Modifier.testTag("pool-generate"),enabled=!busy)
            rows.forEachIndexed{i,row->Panel {
                YText(word("第${i+1}期","第${i+1}回"),14)
                YText(row.date+" · "+row.start+" → "+row.end,11)
                YButton(word("调整本期日期","当期の日付を調整"),{editingPeriod=if(editingPeriod==i)null else i;calendar=null})
                if(editingPeriod==i)YDateFields(listOf(word("付款日","支払日") to row.date,word("计费开始","計算開始") to row.start,word("计费结束","計算終了") to row.end),calendar?.minus(3+i*3)?.takeIf{it in 0..2},{calendar=it?.let{n->3+i*3+n}},{field,v->encoded=vm.repository.json.encodeToString(rows.mapIndexed{index,p->if(index!=i)p else when(field){0->p.copy(date=v);1->p.copy(start=v);else->p.copy(end=v)}})})
            }}
            if(memberPlans.isNotEmpty()) {
                memberPlans.forEach{YText(it.name+" · "+financeLabel(it.mode),12)}
                YToggle(word("归并以上循环计划，保留已确认与付款历史","上記の循環予定を統合し、確定・支払履歴を保持"),adopt,{adopt=it},Modifier.testTag("pool-adopt"))
            }
            f.adoptedArrangements(a.id,mode.name,adopt).filter{it.changeFeeMinor>0}.forEach{change->
                val row=feeDrafts.firstOrNull{it.arrangementId==change.id}?:PoolAdoptedFeeDraft(change.id,"")
                val plan=f.plans.first{it.id==change.targetPlanId}
                Panel(Modifier.testTag("pool-adopted-fee-${change.id}")) {
                    YText(plan.name+" · "+word("原办理费","元の手数料")+" · "+Money(c,change.changeFeeMinor).display(),14)
                    YText(word("原办理记录不改写。选择这笔费用的共同付款日；若该日保留原账单，需先核对金额并明确确认已包含，不能重复加收。","元の手続き記録は変更しません。この手数料の共同支払日を選びます。当日に元請求を保持する場合は金額を確認し、包含を明示してください。重複加算しません。"),11)
                    YSelect(word("办理费共同付款日","手数料の共同支払日"),row.date,listOf("" to word("请选择","選択してください"))+rows.map{it.date to it.date},{fee(row.copy(date=it,included=false))},Modifier.testTag("pool-adopted-fee-date-${change.id}"))
                    YField(word("平台明确取消的本笔办理费 / 未取消填0","取消手数料・なしは0"),row.waived,{fee(row.copy(waived=it,included=false))},Modifier.testTag("pool-adopted-fee-waived-${change.id}"),numeric=true)
                    YText(word("只记录平台明确取消的部分，不改原办理费或实际已付费用；保留账单也须核对为当前金额。","返済先が明示した取消分のみ入力し、元の手数料・実支払額は変えません。保持する請求も現在の金額に確認してください。"),11)
                    val existing=retained.filter{it.date==row.date}
                    if(existing.isNotEmpty()) {
                        if(row.waived.toBigDecimalOrNull()?.let{it<Money(c,change.changeFeeMinor).decimal}==true)YToggle(word("已核对该日保留账单包含本笔办理费","当日の保持請求がこの手数料を含むことを確認した"),row.included,{fee(row.copy(included=it))},Modifier.testTag("pool-adopted-fee-included-${change.id}"))
                        existing.forEach{bill->
                            YText(bill.date+" / "+bill.number+" · "+(bill.feeMinor?.let{Money(c,it).display()}?:word("费用未知","手数料不明")),11)
                            if(onBill!=null)YButton(word("核对这期账单","当期の請求を確認"),{onBill(bill.id)},Modifier.testTag("pool-adopted-fee-bill-${bill.id}"))
                        }
                    }
                }
            }
            if(retained.isNotEmpty())YText(word("须包含这些原账单日期：","次の元請求日を含めてください：")+retained.map{it.date}.distinct().sorted().joinToString(" · "),11)
            val unallocated=poolUnallocatedPayments(s,a.id)
            unallocated.filter{it.minor>0||it.eventId in shares}.forEach{p->
                YField(word("未分配本金中属于池的金额","未充当のうちプール元金分")+" · "+p.date,shares[p.eventId].orEmpty(),{v->shareJson=vm.repository.json.encodeToString(shares+(p.eventId to v))},numeric=true)
            }
            (shares.keys-unallocated.map{it.eventId}.toSet()).forEach{id->
                YText(word("原付款已删除或不再适用，请核对旧归属。","元の支払は削除・変更されています。旧割当てを確認してください。"),11)
                YButton(word("移除这份失效归属","この無効な割当てを除外"),{shareJson=vm.repository.json.encodeToString(shares-id)})
            }
            YText(word("未分配还款需明确属于池的部分，零也请填0。预测期数只限定查看范围，不代表全部结清。","未充当返済はプール分を明示し、ゼロも0を入力してください。予測回数は表示範囲で、完済を意味しません。"),11)
            YButton(word("预览账户循环计划","口座の循環予定を確認"),{vm.execute {
                val source=sourceDate?.let{PoolSourceDate.valueOf(it)}?:error("请选择起算日期依据 / 開始日の根拠を選択してください")
                val initial=Money.parse(opening,c).minor
                val number=firstNumber.toIntOrNull()?:error("请输入起始期数 / 開始回数を入力してください")
                val terms=(saved?.terms?:FinancialScheduleTerms(a.openingDate,emptyList())).copy(agreementDate=a.openingDate,paymentDates=rows.map{it.date},accrualWindows=rows.map{AccrualWindow(it.start,it.end)},firstPeriodNumber=number)
                val values=shares.filterValues{it.isNotBlank()}.mapValues{Money.parse(it.value,c).minor}
                val pool=(saved?.pool?:FinancialPoolInput(initial,source)).copy(openingMinor=initial,sourceDate=source,unallocatedShares=values,adoptExisting=adopt,adoptedFees=f.adoptedArrangements(a.id,mode.name,adopt).filter{it.changeFeeMinor>0}.mapNotNull{change->feeDrafts.firstOrNull{it.arrangementId==change.id}?.let{PoolAdoptedFee(it.arrangementId,it.date,it.included,Money.parse(it.waived,c).minor)}},facts=saved?.pool?.facts?.let{facts->facts.copy(payments=facts.payments.filter{it.eventId !in values.keys})})
                review=vm.repository.previewFinancialPool(s.book.id,FinancialPredictionInput(a.id,name,mode,terms,paymentAccountId=bank,planId=old?.id,currentArrangementConfirmed=true,pool=pool))
            }},Modifier.testTag("pool-preview"),primary=true,enabled=!busy&&rows.isNotEmpty())
        }
    }
}
