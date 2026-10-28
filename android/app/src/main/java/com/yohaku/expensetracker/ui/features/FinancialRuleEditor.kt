package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.serialization.encodeToString
import java.time.LocalDate

@Composable internal fun FinanceFormSection(title:String,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    Column(Modifier.fillMaxWidth().drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))else null)}.padding(top=18.dp,bottom=6.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        YText(title,if(t.id=="objects")15 else 18,weight=FontWeight(if(t.id=="objects")650 else 450),lineHeightMultiplier=1.8f,cssLineBox=true)
        content()
    }
}

@Composable internal fun FinancialRuleEditor(s:BookSnapshot,account:AccountEntity,mode:FinanceMode,original:FinancialRuleVersion?,vm:AppViewModel,onClose:()->Unit,onLimits:(()->Unit)?=null) {
    FinancialDraftHost(s,FinancialDraftContext("RULE",account.id,mode=mode.name),vm,onClose){FinancialRuleEditorContent(s,account,mode,original,vm,onClose,onLimits)}
}

@Composable private fun FinancialRuleEditorContent(s:BookSnapshot,account:AccountEntity,mode:FinanceMode,original:FinancialRuleVersion?,vm:AppViewModel,onClose:()->Unit,onLimits:(()->Unit)?=null) {
    val currency=Currency.valueOf(account.currency)
    var encoded by financialField("encoded"){vm.repository.json.encodeToString(original?.toForm(currency)?:FinancialRuleDraft(mode,mapOf("effective" to LocalDate.now(vm.repository.clock).toString(),"zone" to if(currency==Currency.CNY)"Asia/Shanghai"else "Asia/Tokyo")))}
    val draft=vm.repository.json.decodeFromString<FinancialRuleDraft>(encoded)
    fun change(key:String,value:String){encoded=vm.repository.json.encodeToString(draft.with(key,value))}
    var advanced by rememberSaveable{mutableStateOf(false)}
    var calendar by remember{mutableStateOf<Int?>(null)}
    var trialCalendar by remember{mutableStateOf<Int?>(null)}
    var deadlineCalendar by remember{mutableStateOf<Int?>(null)}
    var deadlineBill by financialField<String?>("deadlineBill"){null}
    var deadlineAt by financialField("deadlineAt"){LocalDate.now(vm.repository.clock).toString()+"T09:00"}
    val financialGraph by vm.financialState.collectAsStateWithLifecycle()
    val accountPlans=financialGraph?.takeIf{it.book.id==s.book.id}?.plans?.filter{it.accountId==account.id}.orEmpty()
    val accountBills=financialGraph?.bills?.filter{bill->bill.active&&accountPlans.any{it.id==bill.planId}}.orEmpty()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val review by vm.financialRuleReview.collectAsStateWithLifecycle()
    var trial by remember{mutableStateOf<Calculation<List<PaymentPeriod>>?>(null)}
    val interestMode=mode in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE)||mode==FinanceMode.FIXED_LOAN&&draft["loanMethod"]!=LoanMethod.MANUAL.name
    val revolving=mode in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE)
    if(review?.rule?.accountId==account.id){FinancialRuleReviewSheet(review!!,vm,onClose);return}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(15.dp)) {
        YButton(word("返回规则列表","条件一覧へ戻る"),{vm.financialRuleReview.value=null;onClose()},Modifier.testTag("finance-rule-cancel"))
        YText(account.name+" · "+financeLabel(mode.name),14)
        ThemeAuditPanel(word("不采用机构预设","会社別プリセットなし")) {
            YText(word("各模式独立填写。缺项会明确列出；已确认账单保留原值。","方式ごとに設定します。不足情報を表示し、確定請求は元の値を保持します。"),11,lineHeightMultiplier=1.9f)
        }
        FinancialDraftNotice()
        FinanceFormSection(word("生效日期与时区","適用日とタイムゾーン")) {
            YDateFields(listOf(word("规则生效日","条件の適用日") to draft["effective"]),calendar,{calendar=it;trialCalendar=null;deadlineCalendar=null},{_,v->change("effective",v);trial=null})
            YField(word("金融时区","金融取引のタイムゾーン"),draft["zone"],{change("zone",it);trial=null},Modifier.testTag("finance-zone"))
        }
        if(mode in listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT,FinanceMode.REVOLVING))FinanceFormSection(word("账期与扣款","締日と引落し")) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                RuleText(draft,"closing",financialIssueName("billing.closingDay"),{k,v->change(k,v);trial=null},Modifier.weight(1f),true)
                RuleText(draft,"payday",financialIssueName("billing.paymentDay"),{k,v->change(k,v);trial=null},Modifier.weight(1f),true)
            }
            RuleBoolean(draft,"closingIncluded",word("締め日当天属于本期","締日当日を当期に含む"),{k,v->change(k,v);trial=null})
            RuleText(draft,"monthOffset",word("还款月距締め月的月数（0为当月）","締月から支払月までの月数（0は当月）"),{k,v->change(k,v);trial=null},numeric=true)
            YSelect(word("扣款账户","引落口座"),draft["bank"],listOf("" to word("未填写","未入力"))+s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}.map{it.id to (it.name+" · "+it.currency)},{change("bank",it);trial=null})
            YText(word("31日遇短月按月末。未配置营业日历时，本期实际扣款日期需手动确认。","31日がない月は月末です。営業日カレンダー未設定の場合、当期の引落日は手動確認してください。"),11,color=LocalYohaku.current.muted)
        }
        if(mode==FinanceMode.SINGLE)FinanceFormSection(word("ボーナス払い（可选，最多两段）","ボーナス払い（任意・最大2区分）")) {
            (0..1).forEach{i->
                YToggle(word("启用期间 ${i+1}","期間${i+1}を使用"),draft["bonus${i}Enabled"]=="true",{change("bonus${i}Enabled",it.toString())})
                if(draft["bonus${i}Enabled"]=="true")listOf(
                    ("StartMonth" to word("利用起始月","利用開始月")) to ("StartDay" to word("利用起始日","利用開始日")),
                    ("EndMonth" to word("利用结束月","利用終了月")) to ("EndDay" to word("利用结束日","利用終了日")),
                    ("PaymentMonth" to word("付款月","支払月")) to ("PaymentDay" to word("付款日","支払日"))
                ).forEach{(month,day)->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    RuleText(draft,"bonus$i${month.first}",month.second,{k,v->change(k,v);trial=null},Modifier.weight(1f),true)
                    RuleText(draft,"bonus$i${day.first}",day.second,{k,v->change(k,v);trial=null},Modifier.weight(1f),true)
                }}
            }
            YText(word("按消费日期所在的利用期间，在期间结束后的付款月日扣款；付款日遇短月按月末。","利用日が属する期間の終了後、指定の支払月日に引き落とします。短い月は月末です。"),11,color=LocalYohaku.current.muted)
        }
        if(mode==FinanceMode.INSTALLMENT)FinanceFormSection(word("分期手续费","分割手数料")) {
            RuleText(draft,"periods",word("支持期数，逗号分隔","対応回数・カンマ区切り"),{k,v->change(k,v);trial=null})
            RuleChoice(draft,"feeMethod",word("分期计算方式","分割計算方式"),InstallmentFeeMethod.entries.map{it.name},{k,v->change(k,v);trial=null})
            when(draft["feeMethod"]) {
                "TOTAL_AMOUNT"->RuleText(draft,"totalFee",word("总费用","総手数料")+" · "+currency.name,{k,v->change(k,v);trial=null},numeric=true)
                "TOTAL_RATE"->RuleText(draft,"totalRate",word("适用总费率（%）","適用総料率（%）"),{k,v->change(k,v);trial=null},numeric=true)
                "PER_HUNDRED_TABLE"->RuleText(draft,"feeTable",word("期数:每100单位费用，逗号分隔","回数:100通貨単位当たり手数料、カンマ区切り"),{k,v->change(k,v);trial=null})
            }
            YText(word("各期数的数值由用户填写，不根据リボ年率推定。","各回数の値は手入力です。リボ年率から推定しません。"),11,color=LocalYohaku.current.muted)
        }
        if(mode==FinanceMode.FIXED_LOAN)FinanceFormSection(word("借款计算方式","借入の計算方式")) {
            RuleChoice(draft,"loanMethod",word("还款计算方式","返済計算方式"),LoanMethod.entries.map{it.name},{k,v->change(k,v);trial=null})
        }
        if(interestMode)FinanceFormSection(word("利息与还款","利息と返済")) {
            RuleText(draft,"apr",word("年手续费率 / 利率（%）","年手数料率・利率（%）"),{k,v->change(k,v);trial=null},numeric=true)
            RuleChoice(draft,"frequency",word("计费方式","計算方式"),ChargeFrequency.entries.map{it.name},{k,v->change(k,v);trial=null})
            if(revolving) {
                RuleChoice(draft,"paymentMethod",word("每月还款方式","毎月の支払方式"),PaymentMethod.entries.map{it.name},{k,v->change(k,v);trial=null})
                if(draft["paymentMethod"]==PaymentMethod.BALANCE_TIERS.name) {
                    RuleText(draft,"tiers",word("上限:月金额，最后一档用 *，逗号分隔","残高上限:月額、最終区分は *、カンマ区切り"),{k,v->change(k,v);trial=null})
                    YSelect(word("阶梯金额按哪个时点的余额","残高別コースの判定時点"),draft["tierBalanceDate"],listOf("" to word("未填写","未入力"),"BILL_CLOSING" to word("本期计费截止余额","当期計算終了時点の残高"),"PAYMENT_DATE" to word("付款日还本前余额","支払日の元金返済前残高")),{change("tierBalanceDate",it);trial=null})
                    RuleBoolean(draft,"tiersIncludeFees",word("阶梯金额包含费用","コースの月額に手数料を含む"),{k,v->change(k,v);trial=null})
                }else RuleText(draft,"payment",word("月还款设定金额","月々の設定額")+" · "+currency.name,{k,v->change(k,v);trial=null},numeric=true)
                if(mode==FinanceMode.REVOLVING)RuleBoolean(draft,"automatic",word("自动リボ","自動リボ"),{k,v->change(k,v);trial=null})
            }
        }
        YButton(word("高级设置 · 计息、取整与分配","詳細設定・計息、端数処理と充当")+if(advanced)" −"else " +",{advanced=!advanced},Modifier.testTag("finance-advanced"))
        if(advanced)FinanceFormSection(word("高级规则","詳細条件")) {
            if(interestMode) {
                RuleChoice(draft,"balance",word("计费余额依据","計算対象残高"),BalanceBasis.entries.map{it.name},{k,v->change(k,v);trial=null})
                if(draft["frequency"]==ChargeFrequency.DAILY.name) {
                RuleText(draft,"yearDays",word("年度天数","年間日数"),{k,v->change(k,v);trial=null},numeric=true)
                RuleBoolean(draft,"includeStart",word("计息包含起始日","計算に開始日を含む"),{k,v->change(k,v);trial=null})
                RuleBoolean(draft,"includeEnd",word("计息包含截止日","計算に終了日を含む"),{k,v->change(k,v);trial=null})
                }
                RuleChoice(draft,"first",word("首次计费规则","初回の手数料"),FirstCharge.entries.map{it.name},{k,v->change(k,v);trial=null})
                RuleChoice(draft,"roundAt",word("取整时点","端数処理の時点"),ChargeRounding.entries.map{it.name},{k,v->change(k,v);trial=null})
            }
            if(mode!=FinanceMode.SINGLE)RuleChoice(draft,"rounding",word("取整方向","端数処理の方向"),listOf("DOWN","HALF_UP","UP"),{k,v->change(k,v);trial=null})
            if(mode==FinanceMode.INSTALLMENT||mode==FinanceMode.FIXED_LOAN&&draft["loanMethod"]==LoanMethod.EQUAL_PRINCIPAL.name)RuleChoice(draft,"principalRemainder",word("本金尾差","元金の端数"),RemainderPlacement.entries.map{it.name},{k,v->change(k,v);trial=null})
            if(mode==FinanceMode.INSTALLMENT)RuleChoice(draft,"feeRemainder",word("费用尾差","手数料の端数"),RemainderPlacement.entries.map{it.name},{k,v->change(k,v);trial=null})
            if(revolving||mode==FinanceMode.FIXED_LOAN)RuleChoice(draft,"allocation",word("付款分配顺序","支払額の充当順序"),AllocationOrder.entries.map{it.name},{k,v->change(k,v);trial=null})
            if(mode!=FinanceMode.FIXED_LOAN)FinancialRuleRecovery(s,account,vm,draft["recovery"],onLimits)
            if(mode in listOf(FinanceMode.INSTALLMENT,FinanceMode.REVOLVING)) {
                YText(word("本期支払い変更期限","今期の支払方法変更締切"),15)
                draft.deadlines.forEach{d->
                    val bill=accountBills.firstOrNull{it.id==d.billId}
                    YText((bill?.let{word("第${it.number}期","第${it.number}回")+" · "+it.date}?:word("账期资料待核查","対象請求を要確認"))+" · "+d.date+" "+d.time+" · "+d.zoneId,11)
                    YButton(word("移除此期截止时间","この回の締切を削除"),{encoded=vm.repository.json.encodeToString(draft.copy(deadlines=draft.deadlines.filterNot{it==d}))},enabled=!busy)
                }
                if(accountBills.isEmpty())YText(word("先保存已知账单，再为具体账期填写日期与时间。","既知の請求を保存してから、対象請求の締切日時を入力してください。"),11,color=LocalYohaku.current.muted)
                else {
                    YSelect(word("变更对应的具体账期","変更対象の請求"),deadlineBill,listOf(null to word("请选择","選択してください"))+accountBills.sortedBy{it.date}.map{b->b.id as String? to (accountPlans.first{it.id==b.planId}.name+" · "+word("第${b.number}期","第${b.number}回")+" · "+b.date)},{deadlineBill=it},Modifier.testTag("finance-deadline-bill"))
                    YDateFields(listOf(word("变更截止日期与时间","変更締切の日時") to deadlineAt),deadlineCalendar,{deadlineCalendar=it;calendar=null;trialCalendar=null},{_,v->deadlineAt=v})
                    YText(word("截止时间使用金融时区：","締切のタイムゾーン：")+draft["zone"],11)
                    YButton(word("采用这期截止时间","この回の締切を採用"),{
                        val billId=deadlineBill?:return@YButton
                        val d=ChangeDeadline(billId,mode,deadlineAt.substringBefore("T"),deadlineAt.substringAfter("T"),draft["zone"])
                        encoded=vm.repository.json.encodeToString(draft.copy(deadlines=draft.deadlines.filterNot{it.billId==billId&&it.changeType==mode}+d))
                    },Modifier.testTag("finance-deadline-apply"),enabled=!busy&&deadlineBill!=null)
                }
            }
            RuleText(draft,"unsupported",word("特殊算法说明 / 需手动计划时填写","特殊な計算条件・手動プランが必要な場合"),{k,v->change(k,v);trial=null})
        }
        FinanceFormSection(word("计算预览","計算プレビュー")) {
            RuleText(draft,"trialAmount",word("用于核对的本金","検算する元金")+" · "+currency.name,{k,v->change(k,v);trial=null},numeric=true)
            if(mode in listOf(FinanceMode.INSTALLMENT,FinanceMode.FIXED_LOAN))RuleText(draft,"trialCount",word("期数","回数"),{k,v->change(k,v);trial=null},numeric=true)
            val today=LocalDate.now(vm.repository.clock).toString()
            val dateKeys=when(mode){FinanceMode.SINGLE->listOf("trialStart");FinanceMode.INSTALLMENT->listOf("trialPayment");FinanceMode.FIXED_LOAN->listOf("trialStart","trialPayment");else->listOf("trialStart","trialEnd","trialPayment")}
            YDateFields(dateKeys.map{key->(if(key=="trialStart")word("消费 / 计息起始日","利用日・計算開始日")else if(key=="trialEnd")word("计息截止日","計算終了日")else word("首次付款日","初回支払日")) to draft[key].ifBlank{today}},trialCalendar,{trialCalendar=it;calendar=null;deadlineCalendar=null},{i,v->change(dateKeys[i],v);trial=null})
            YButton(word("计算预览","計算プレビュー"),{val captured=draft;val capturedEncoded=encoded;var computed:Calculation<List<PaymentPeriod>>?=null;vm.execute(onSuccess={if(encoded==capturedEncoded)trial=computed}) {
                val r=captured.build(s.book.id,account.id,currency)
                val start=LocalDate.parse(captured["trialStart"].ifBlank{today})
                val end=LocalDate.parse(captured["trialEnd"].ifBlank{today})
                val pay=LocalDate.parse(captured["trialPayment"].ifBlank{today})
                val count=if(mode in listOf(FinanceMode.INSTALLMENT,FinanceMode.FIXED_LOAN))captured["trialCount"].toIntOrNull()?:error("请输入有效期数 / 有効な回数を入力してください")else 1
                computed=calculateRulePreview(r,RulePreviewInput(Money.parse(captured["trialAmount"],currency).minor,count,start,end,pay))
            }},Modifier.testTag("finance-calculate"),enabled=!busy)
            trial?.let{FinancialCalculationResult(it,currency)}
        }
        YButton(word("预览规则变更","条件の変更を確認"),{val captured=draft;vm.execute{vm.financialReviewRefreshed.value=false;vm.financialRuleReview.value=vm.repository.previewFinancialRule(s.book.id,account.id,captured.build(s.book.id,account.id,currency))}},Modifier.testTag("finance-rule-preview"),primary=true,enabled=!busy)
    }
}

@Composable private fun RuleText(draft:FinancialRuleDraft,key:String,label:String,onChange:(String,String)->Unit,modifier:Modifier=Modifier,numeric:Boolean=false)=YField(label,draft[key],{onChange(key,it)},modifier.testTag("finance-field-$key"),numeric=numeric)
@Composable private fun RuleChoice(draft:FinancialRuleDraft,key:String,label:String,options:List<String>,onChange:(String,String)->Unit)=YSelect(label,draft[key],listOf("" to word("未填写","未入力"))+options.map{it to financeLabel(it)},{onChange(key,it)},Modifier.testTag("finance-choice-$key"))
@Composable private fun RuleBoolean(draft:FinancialRuleDraft,key:String,label:String,onChange:(String,String)->Unit)=YSelect(label,draft[key],listOf("" to word("未填写","未入力"),"true" to word("是","はい"),"false" to word("否","いいえ")),{onChange(key,it)},Modifier.testTag("finance-choice-$key"))

@Composable private fun FinancialRuleRecovery(s:BookSnapshot,a:AccountEntity,vm:AppViewModel,previous:String,onLimits:(()->Unit)?) {
    val graph by vm.creditLimitState.collectAsStateWithLifecycle()
    val limits=graph?.takeIf{it.book.id==s.book.id}
    val config=limits?.currentConfig(a.id,LocalDate.now(vm.repository.clock).toString())
    ThemeAuditPanel(word("额度恢复","枠回復"),Modifier.testTag("finance-rule-recovery")) {
        YText(word("本账户统一采用额度设置中的恢复条件。计算规则的保存不改动额度设置；未配置额度时仍可按已填条件计算付款。","この口座の枠設定にある回復条件を共通で使用します。計算条件の保存では枠設定を変更しません。枠が未設定でも支払額は入力条件から計算できます。"),11)
        if(limits==null)YText(word("正在读取当前额度设置…","現在の枠設定を読込中…"),12)
        else if(config==null)YText(word("尚未配置额度，利用可能额保持未知。","枠は未設定のため、利用可能額は不明です。"),12,Modifier.testTag("finance-rule-recovery-unset"))
        else {
            YText(limitLabel(config.recovery,config.recoveryDelayDays),12,Modifier.testTag("finance-rule-recovery-current"))
            RecordFacts(listOf(word("额度版本 / 生效日","枠バージョン・適用日") to (config.version.toString()+" · "+config.effectiveFrom)))
        }
        if(previous.isNotBlank()) {
            RecordFacts(listOf(word("此前填写","以前の入力") to limitLabel(previous)))
            YText(word("保留原填写记录；实际额度预测采用上方的统一设置。","以前の入力記録を保持し、実際の枠予測には上の共通設定を使用します。"),11)
        }
        if(onLimits!=null)YButton(word("核对额度与恢复设置","枠・回復設定を確認"),onLimits,Modifier.testTag("finance-rule-open-limits"))
    }
}
