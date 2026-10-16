package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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

@Serializable private data class PredictionPeriodDraft(val date:String,val start:String,val end:String,val override:Boolean=false,val principal:String="",val fee:String="")

@Composable internal fun FinancialPredictionEditor(s:BookSnapshot,account:AccountEntity,finance:FinancialGraph,old:FinancialPlanEntity?,vm:AppViewModel,onClose:()->Unit) {
    FinancialDraftHost(s,FinancialDraftContext("PREDICTION",account.id,targetId=old?.id),vm,onClose){FinancialPredictionEditorContent(s,account,finance,old,vm,onClose)}
}

@Composable private fun FinancialPredictionEditorContent(s:BookSnapshot,account:AccountEntity,finance:FinancialGraph,old:FinancialPlanEntity?,vm:AppViewModel,onClose:()->Unit) {
    val currency=Currency.valueOf(account.currency)
    val saved=remember(old?.id){old?.termsJson?.let{vm.repository.json.decodeFromString<FinancialPredictionInput>(it)}}
    val existingBills=finance.bills.filter{it.planId==old?.id&&it.active}.sortedBy{it.number}
    val today=LocalDate.now(vm.repository.clock).toString()
    val initialStart=saved?.terms?.agreementDate?:old?.sourceEventId?.let{id->s.events.firstOrNull{it.id==id}?.date}?:account.openingDate
    var name by financialField("name"){old?.name.orEmpty()}
    var mode by financialField("mode"){old?.mode?:if(account.type==AccountType.CREDIT.name)FinanceMode.SINGLE.name else FinanceMode.FIXED_LOAN.name}
    var source by financialField("source"){if(old?.sourceEventId!=null)"EVENT"else"OPENING"}
    var eventId by financialField("eventId"){old?.sourceEventId}
    var principal by financialField("principal"){old?.principalMinor?.let{Money(currency,it).decimal.toPlainString()}.orEmpty()}
    var agreement by financialField("agreement"){initialStart}
    var count by financialField("count"){saved?.terms?.paymentDates?.size?.toString()?:existingBills.size.takeIf{it>0}?.toString().orEmpty()}
    var firstPeriod by financialField("firstPeriod"){saved?.terms?.firstPeriodNumber?.toString().orEmpty()}
    var remainingFee by financialField("remainingFee"){saved?.terms?.remainingFeeMinor?.let{Money(currency,it).decimal.toPlainString()}.orEmpty()}
    var bank by financialField("bank"){saved?.paymentAccountId?:existingBills.firstOrNull()?.paymentAccountId}
    var firstDate by financialField("firstDate"){saved?.terms?.paymentDates?.firstOrNull()?:existingBills.firstOrNull()?.date?:today}
    var firstEnd by financialField("firstEnd"){saved?.terms?.accrualWindows?.firstOrNull()?.end?:today}
    var encoded by financialField("encoded"){vm.repository.json.encodeToString(saved?.terms?.paymentDates?.mapIndexed{i,date->val w=saved.terms.accrualWindows.getOrNull(i);val o=saved.terms.overrides.firstOrNull{it.number==i+1}
        PredictionPeriodDraft(date,w?.start?:initialStart,w?.end?:date,o!=null,o?.principalMinor?.let{Money(currency,it).decimal.toPlainString()}.orEmpty(),o?.feeMinor?.let{Money(currency,it).decimal.toPlainString()}.orEmpty())}.orEmpty())}
    val rows=vm.repository.json.decodeFromString<List<PredictionPeriodDraft>>(encoded)
    var arrangementConfirmed by financialField("arrangementConfirmed"){old!=null}
    var calendar by remember{mutableStateOf<Int?>(null)}
    val event=s.visibleEvents.firstOrNull{it.id==eventId}
    val activeMode=FinanceMode.valueOf(mode)
    val revolving=activeMode in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val review by vm.financialPredictionReview.collectAsStateWithLifecycle()
    val activeReview=review?.takeIf{it.plan.bookId==s.book.id&&it.plan.accountId==account.id}
    fun clearRows(){encoded="[]";calendar=null}
    fun suggestDates(date:String,selectedMode:String) {
        val row=finance.rules.filter{it.accountId==account.id&&it.mode==selectedMode&&it.effectiveFrom<=date}.maxWithOrNull(compareBy<FinancialRuleEntity>{it.effectiveFrom}.thenBy{it.version})
        val billing=row?.let{vm.repository.json.decodeFromString<FinancialRuleVersion>(it.json)}?.billing
        if(billing!=null&&billing.issues().isEmpty()) {
            val dates=billingDates(LocalDate.parse(date),billing)
            firstDate=dates.paymentDate.toString();firstEnd=dates.closingDate.toString()
        }
    }
    val formScroll=rememberScrollState();val reviewScroll=rememberScrollState()
    LaunchedEffect(activeReview){if(activeReview!=null)reviewScroll.scrollTo(0)}
    YSheet(if(activeReview!=null)word("预测计划与影响","予測プランと影響")else word("规则预测计划","条件による予測"),{if(activeReview!=null)vm.financialPredictionReview.value=null else onClose()},scrollState=if(activeReview!=null)reviewScroll else formScroll) {
        if(activeReview!=null)FinancialPredictionReviewContent(activeReview,vm,onClose)else {
        FinancialDraftNotice()
        ThemeAuditPanel(account.name) {YText(word("关联真实来源，只保存预测义务。消费、借入、付款与账户余额都由实际记录决定。","実際の対象に関連付け、予測の支払予定のみ保存します。支出・借入・支払・残高は実記録で決まります。"),11)}
        YField(word("计划名称","プラン名"),name,{name=it},Modifier.testTag("prediction-name"))
        if(old==null) {
            val modes=(if(account.type==AccountType.CREDIT.name)listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT,FinanceMode.REVOLVING)else listOf(FinanceMode.FIXED_LOAN,FinanceMode.CREDIT_LINE)).filter{it !in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE)||finance.plans.none{p->p.accountId==account.id&&p.active&&p.origin=="POOL"}}
            YSelect(word("计算模式","計算方式"),mode,modes.map{it.name to financeLabel(it.name)},{mode=it;if(source=="EVENT")event?.let{e->suggestDates(e.date,it)};clearRows()},Modifier.testTag("prediction-mode"))
            YSelect(word("本金来源","元金の対象"),source,listOf("OPENING" to word("期初已有欠款","期首の既存残高"),"EVENT" to word("已有消费 / 借入","既存の支出・借入")),{source=it;clearRows()},Modifier.testTag("prediction-source"))
        }else YText(financeLabel(mode)+" · "+word("重算保留来源及已确认账单","再計算でも対象と確定請求を保持"),12)
        if(source=="EVENT") {
            if(old==null)YSelect(word("关联记录","関連する記録"),eventId,listOf(null to word("请选择","選択してください"))+s.visibleEvents.filter{e->e.accountId==account.id&&!e.historicalIncluded&&e.kind==if(account.type==AccountType.CREDIT.name)EntryKind.EXPENSE.name else EntryKind.BORROWING.name}.filter{e->finance.plans.none{it.active&&it.sourceEventId==e.id}}.map{e->e.id as String? to (e.date+" · "+e.merchant+" · "+s.accountMoney(e).display())},{id->eventId=id;eventId?.let{e->agreement=s.events.first{it.id==e}.date;suggestDates(agreement,mode)};clearRows()})
            else YText(event?.let{it.date+" · "+it.merchant+" · "+s.accountMoney(it).display()}?:word("原记录已删除，请先检查来源。","元の記録が削除されています。対象を確認してください。"),12)
            YText(word("预览使用账户币金额；退回本账户的实际退款同时计入。","予測は口座通貨の金額を使用し、この口座への実返金を反映します。"),11)
        }else YField(word("这份计划的已有本金","このプランの既存元金")+" · "+currency.name,principal,{principal=it;clearRows()},numeric=true)
        if(source=="OPENING")YText(word("期初计划不增加欠款；已有计划所占本金须分开。","期首のプランは残債を増やしません。既存プランの元金とは分けてください。"),11,color=LocalYohaku.current.muted)
        if(activeMode!=FinanceMode.SINGLE) {
            YField(word("预测期数 / 不代表必定结清","予測回数・完済を保証するものではありません"),count,{count=it;clearRows()},numeric=true)
            YField(word("起于原合同第几期","元の契約の何回目からか"),firstPeriod,{firstPeriod=it;clearRows()},numeric=true)
        }
        if(source=="OPENING"&&activeMode==FinanceMode.INSTALLMENT)YField(word("平台已知剩余总费用 / 未知留空","返済先で判明した残手数料・不明なら空欄")+" · "+currency.name,remainingFee,{remainingFee=it},numeric=true)
        val labels=mutableListOf<Pair<String,String>>()
        if(source!="EVENT")labels+=word("合同 / 期初基准日","契約日・期首基準日") to agreement
        labels+=word("首次付款日期","初回支払日") to firstDate
        if(revolving)labels+=word("首次计费截止日","初回計算終了日") to firstEnd
        YDateFields(labels,calendar?.takeIf{it<10},{calendar=it},{i,v->
            var n=i
            if(source!="EVENT"){if(n==0)agreement=v;n--}
            if(n==0)firstDate=v else if(n==1)firstEnd=v
            clearRows()
        })
        YSelect(word("实际扣款账户 / 按规则可留空","実引落口座・条件から取得する場合は未選択"),bank,listOf(null to word("按规则 / 未确定","条件から取得・未定"))+s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}.map{it.id as String? to (it.name+" · "+it.currency)},{bank=it})
        YButton(word("按月生成候选日期","月ごとの候補日を作成"),{
            vm.execute {
                val n=if(activeMode==FinanceMode.SINGLE)1 else count.toIntOrNull()?:error("请输入有效期数 / 有効な回数を入力してください")
                require(n in 1..1200){"请输入1至1200期 / 1〜1200回を入力してください"}
                val start=LocalDate.parse(if(source=="EVENT")event?.date?:error("请选择来源记录 / 対象の記録を選択してください")else agreement)
                val selectedRule=finance.rules.filter{it.accountId==account.id&&it.mode==mode&&it.effectiveFrom<=start.toString()}.maxWithOrNull(compareBy<FinancialRuleEntity>{it.effectiveFrom}.thenBy{it.version})?.let{vm.repository.json.decodeFromString<FinancialRuleVersion>(it.json)}
                val payment=if(activeMode==FinanceMode.SINGLE&&selectedRule?.billing?.issues()?.isEmpty()==true)billingDates(start,selectedRule.billing).paymentDate else LocalDate.parse(firstDate)
                val end=LocalDate.parse(firstEnd)
                val interest=selectedRule?.revolving?.interest
                encoded=vm.repository.json.encodeToString(List(n){i->val previous=end.plusMonths(i-1L)
                    val nextStart=if(i==0)start else if(interest?.frequency==ChargeFrequency.MONTHLY||interest?.includeStart==true&&interest.includeEnd==true)previous.plusDays(1)
                        else if(interest?.includeStart==false&&interest.includeEnd==false)previous.minusDays(1)else previous
                    PredictionPeriodDraft(payment.plusMonths(i.toLong()).toString(),nextStart.toString(),end.plusMonths(i.toLong()).toString())})
            }
        },Modifier.testTag("prediction-generate"),enabled=!busy)
        if(rows.isNotEmpty())YText(word("以下为候选日期，请逐期核对；保存前可以修改。","以下は候補日です。各回を確認し、保存前に修正できます。"),11,color=LocalYohaku.current.muted)
        rows.forEachIndexed{i,row->Panel(Modifier.testTag("prediction-period-$i")) {
            YText(word("第${i+1}期","第${i+1}回"),15)
            val fields=if(revolving)listOf(word("计费开始","計算開始") to row.start,word("计费截止","計算終了") to row.end,word("付款日","支払日") to row.date)else listOf(word("付款日","支払日") to row.date)
            YDateFields(fields,calendar?.takeIf{it/10==i+1}?.rem(10),{owner->calendar=owner?.let{(i+1)*10+it}},{field,value->
                val next=if(!revolving||field==2)row.copy(date=value)else if(field==0)row.copy(start=value)else row.copy(end=value)
                encoded=vm.repository.json.encodeToString(rows.mapIndexed{n,p->if(n==i)next else p})
            })
            if(activeMode==FinanceMode.INSTALLMENT) {
                YToggle(word("采用本期平台本金与费用","この回の返済先の元金・手数料を使用"),row.override,{value->encoded=vm.repository.json.encodeToString(rows.mapIndexed{n,p->if(n==i)p.copy(override=value)else p})})
                if(row.override){
                    YField(word("本期本金","当期元金"),row.principal,{v->encoded=vm.repository.json.encodeToString(rows.mapIndexed{n,p->if(n==i)p.copy(principal=v)else p})},numeric=true)
                    YField(word("本期费用","当期手数料"),row.fee,{v->encoded=vm.repository.json.encodeToString(rows.mapIndexed{n,p->if(n==i)p.copy(fee=v)else p})},numeric=true)
                }
            }
        }}
        if(old==null)YToggle(word("以上是平台当前已采用的安排，不是拟办理方案","返済先で現在適用中のプランです。変更予定ではありません"),arrangementConfirmed,{arrangementConfirmed=it},Modifier.testTag("prediction-current-arrangement"))
        YButton(word("预览预测与影响","予測と影響を確認"),{
            val inputName=name;val inputMode=mode;val inputSource=source;val selectedEvent=eventId;val amount=principal;val selectedBank=bank;val dates=rows;val current=firstPeriod;val agreed=arrangementConfirmed;val fees=remainingFee;val baseDate=if(source=="EVENT"&&finance.arrangements.none{it.targetPlanId==old?.id})event?.date else agreement
            vm.execute {
                require(dates.isNotEmpty()){"请先生成并确认逐期日期 / 各回の日付を生成・確認してください"}
                val terms=FinancialScheduleTerms(baseDate?:error("请选择来源记录 / 対象の記録を選択してください"),dates.map{it.date},if(revolving)dates.map{AccrualWindow(it.start,it.end)}else emptyList(),
                    dates.mapIndexedNotNull{i,p->if(p.override)PeriodOverride(i+1,Money.parse(p.principal,currency).minor,Money.parse(p.fee,currency).minor)else null},
                    if(activeMode==FinanceMode.SINGLE)1 else current.toIntOrNull()?:error("请填写原合同期数 / 元の契約の回数を入力してください"),fees.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency).minor})
                val input=FinancialPredictionInput(account.id,inputName,FinanceMode.valueOf(inputMode),terms,if(inputSource=="EVENT")selectedEvent else null,if(inputSource=="OPENING")Money.parse(amount,currency).minor else null,selectedBank,old?.id,agreed)
                vm.financialReviewRefreshed.value=false;vm.financialPredictionReview.value=vm.repository.previewFinancialPrediction(s.book.id,input)
            }
        },Modifier.testTag("prediction-preview"),primary=true,enabled=!busy&&rows.isNotEmpty()&&arrangementConfirmed)
        }
    }
}
