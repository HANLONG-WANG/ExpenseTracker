package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.features.command.fieldName
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.serialization.json.*

@Composable internal fun FinancialRuleChangeSummary(changes:FinancialDisplayFacts.RuleChanges,s:BookSnapshot,currency:Currency=Currency.valueOf(s.book.currency),referenceName:(String)->String={id->s.accounts.firstOrNull{it.id==id}?.name?:id}) {
    FinancialChangeSummary {
        YText(if(changes.firstVersion)word("首次建立规则","条件を初めて作成")else word("与上一版相比","前のバージョンとの比較"),15)
        val conditions=changes.fields.filterNot{it.fieldKey in setOf("effectiveFrom","zoneId","version","mode")}
        if(conditions.isEmpty()&&!changes.firstVersion)YText(word("业务条件相同；版本与生效信息另列。","計算条件は同じです。版・適用情報は別に表示します。"),12)
        val visible=if(changes.firstVersion)conditions.filter{it.fieldKey in setOf("billing.closingDay","billing.paymentDay","billing.paymentAccountId","installment.feeMethod","installment.totalFeeMinor","installment.totalRate","revolving.interest.annualRate","revolving.method","revolving.paymentMinor","loanMethod","loanInterest.annualRate","unsupportedReason")||listOf("installment.supportedPeriods.","installment.feeTable.","revolving.tiers.","bonusPayments.").any{prefix->it.fieldKey.startsWith(prefix)}}else conditions
        visible.forEach{d->
            val key=d.fieldKey.substringAfterLast('.')
            Column(Modifier.testTag("finance-rule-diff-${d.fieldKey}"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                val parts=d.fieldKey.split('.').map{if(it.toIntOrNull()!=null)(it.toInt()+1).toString()else if(it=="billId")word("对应账期","対象請求")else if(it=="changeType")word("变更方式","変更方式")else if(it=="time")word("截止时间","締切時刻")else if(it=="annualRate")word("年率（%）","年利率（%）")else if(it=="totalRate")word("总费率（%）","総料率（%）")else fieldName(it)}
                YText(when(d.fieldKey){
                    "billing.paymentAccountId"->financialIssueName("billing.paymentAccount")
                    "billing.closingDay","billing.paymentDay"->financialIssueName(d.fieldKey)
                    else->parts.joinToString(" · ")
                },11,color=LocalYohaku.current.muted)
                val before=ruleDisplayValue(d.before,key,currency,referenceName);val after=ruleDisplayValue(d.after,key,currency,referenceName)
                YText(if(changes.firstVersion)after else "$before → $after",14)
            }
        }
        changes.fields.filter{it.fieldKey in setOf("effectiveFrom","zoneId","mode")}.forEach{d->
            YText(fieldName(d.fieldKey)+" · "+(if(changes.firstVersion)""else ruleDisplayValue(d.before,d.fieldKey,currency,referenceName)+" → ")+ruleDisplayValue(d.after,d.fieldKey,currency,referenceName),12)
        }
    }
}

@Composable private fun ruleDisplayValue(value:JsonElement?,key:String,currency:Currency,referenceName:(String)->String):String {
    if(key=="upToMinor"&&value==JsonNull)return word("无上限","上限なし")
    if(value==null)return word("已移除","削除済み")
    val raw=(value as? JsonPrimitive)?.contentOrNull
    if(raw.isNullOrEmpty())return word("未填写","未入力")
    if(key in setOf("paymentAccountId","billId"))return referenceName(raw)
    if(key.endsWith("Minor"))return Money(currency,raw.toLong()).display()
    if(key in setOf("annualRate","totalRate"))return raw.toBigDecimal().movePointRight(2).stripTrailingZeros().toPlainString()+"%"
    if(raw=="true")return word("是","はい")
    if(raw=="false")return word("否","いいえ")
    if(key in setOf("frequency","balanceBasis","rounding","roundingMode","firstCharge","method","allocation","feeMethod","principalRemainder","feeRemainder","loanMethod","loanPrincipalRemainder","mode","changeType"))return financeLabel(raw)
    if(key=="tierBalanceDate")return if(raw=="BILL_CLOSING")word("计费截止余额","計算終了時点の残高")else word("付款日前余额","支払前残高")
    if(key=="limitRecovery")return limitLabel(raw)
    return raw
}

@Composable internal fun FinancialDateComparison(value:FinancialArrangementComparison,currency:Currency,s:BookSnapshot,accountName:(String)->String={id->s.accounts.firstOrNull{it.id==id}?.name?:id}) {
    val t=LocalYohaku.current
    var changesOnly by remember(value){mutableStateOf(true)}
    val rows=remember(value){FinancialDisplayFacts.dates(value.original,value.proposed)}
    YText(word("按付款日期比较","支払日ごとの比較"),17)
    if(!value.originalComplete||!value.proposedComplete)YText(word("只比较已列付款；资料范围不完整，不代表全部债务。","記載済みの支払のみ比較します。資料の範囲が不完全なため、債務全体を表しません。"),12,Modifier.testTag("finance-comparison-incomplete"),color=t.warm)
    YToggle(if(changesOnly)word("只看变化日期","変更日のみ")else word("查看全部日期","すべての日付"),changesOnly,{changesOnly=it},Modifier.testTag("finance-comparison-changes-only"))
    val visible=if(changesOnly)rows.filter{it.changed}else rows
    if(visible.isEmpty())YText(word("已列日期的金额、付款账户和组成均无变化。","記載済みの日付の金額・引落口座・内訳に変更はありません。"),12)
    fun money(n:Long?)=n?.let{Money(currency,it).display()}?:"—"
    val unknown=word("未知","不明")
    fun component(n:Long?)=n?.let{Money(currency,it).display()}?:unknown
    val unassignedAccount=word("未定","未定")
    val tableRows=visible.map{row->
        val notes=mutableListOf<String>()
        // A missing side has no payment to compare. The dash and legend describe it;
        // the complete basis retains the new/removed payment's account and composition.
        if(row.before!=null&&row.after!=null) {
            val before=row.before;val after=row.after
            val oldAccounts=before.rows.groupBy{it.paymentAccountId}.mapValues{(_,rows)->rows.sumOf{it.totalMinor}}
            val newAccounts=after.rows.groupBy{it.paymentAccountId}.mapValues{(_,rows)->rows.sumOf{it.totalMinor}}
            fun accounts(values:Map<String?,Long>,amounts:Boolean)=values.entries.joinToString("、"){(id,n)->(id?.let(accountName)?:unassignedAccount)+if(amounts)" "+money(n)else ""}
            if(oldAccounts.keys!=newAccounts.keys)notes+=word("付款账户：","引落口座：")+accounts(oldAccounts,false)+" → "+accounts(newAccounts,false)
            else if(oldAccounts.size>1&&oldAccounts!=newAccounts)notes+=word("账户分配：","口座別金額：")+accounts(oldAccounts,true)+" → "+accounts(newAccounts,true)
            if(before.principalMinor!=after.principalMinor||before.feeMinor!=after.feeMinor) {
                fun composition(side:FinancialDisplayFacts.DateSide)=component(side.principalMinor)+"/"+component(side.feeMinor)
                notes+=word("本金/费用：","元金/費用：")+composition(before)+" → "+composition(after)
            }else if(before.rows.map{it.principalMinor to it.feeMinor}.groupingBy{it}.eachCount()!=after.rows.map{it.principalMinor to it.feeMinor}.groupingBy{it}.eachCount()) {
                notes+=word("逐笔组成有变化，详见完整依据","支払ごとの内訳が変更。すべての根拠で確認")
            }
        }
        if(row.changed&&row.deltaMinor==0L&&notes.isEmpty())notes+=word("逐笔付款有变化，详见完整依据","支払ごとの内容が変更。すべての根拠で確認")
        FinancialComparisonLine(row.date,money(row.before?.totalMinor),money(row.after?.totalMinor),
            (if(row.deltaMinor<0)"−"else if(row.deltaMinor>0)"+"else "")+Money(currency,kotlin.math.abs(row.deltaMinor)).display(),
            notes.joinToString("；"),row.changed)
    }
    if(tableRows.isNotEmpty())FinancialComparisonTable(tableRows)
    YText(word("— 表示该日无已列付款；差额只针对已列范围。小注仅列两侧不同的账户或组成。","— はその日の記載済み支払なし。差額は記載範囲のみ。注記は両側の口座・内訳の違いのみです。"),11,color=t.muted)
    val unassigned=word("付款账户未定","引落口座未定")
    FinancialFullDetails {
        ArrangementRows(word("原方案完整依据","変更前のすべての根拠"),value.original,currency,value.originalUnscheduledPrincipalMinor.takeIf{value.originalComplete},"before",accountName={it?.let(accountName)?:unassigned})
        ArrangementRows(word("新方案完整依据","変更後のすべての根拠"),value.proposed,currency,value.proposedRemainingPrincipalMinor.takeIf{value.proposedComplete},"after",accountName={it?.let(accountName)?:unassigned})
    }
}

/** Listed bill amounts are not outstanding debt; preservation is based on the two snapshots. */
@Composable internal fun FinancialPlanChangeSummary(name:String,changes:FinancialDisplayFacts.PlanChanges,currency:Currency,summary:Boolean=true) {
    val facts:@Composable ()->Unit={
        if(name.isNotBlank())YText(name,18)
        val unknown=word("未知","不明")
        fun amount(n:Long?)=n?.let{Money(currency,it).display()}?:unknown
        fun change(old:String?,next:String)=if(old!=null&&old!=next)"$old → $next"else next
        val noListed=word("无已列付款","記載済みの支払なし")
        fun first(side:FinancialDisplayFacts.PlanSide)=side.bills.minByOrNull{it.payment.date}?.let{it.payment.date+" · "+amount(it.payment.totalMinor.takeIf{_->it.totalKnown})}?:noListed
        RecordFacts(listOf(
            word("首个已列账期","最初の記載済み請求") to change(changes.before?.let(::first),first(changes.after)),
            word("计划来源本金","予定の対象元金") to change(changes.before?.let{amount(it.principalMinor)},amount(changes.after.principalMinor)),
            word("已列账期 / 合计","記載済みの回数・合計") to change(changes.before?.let{"${it.bills.size} / ${amount(it.totalMinor)}"},"${changes.after.bills.size} / ${amount(changes.after.totalMinor)}"),
            word("已列利息及费用","記載済みの利息・手数料") to change(changes.before?.let{amount(it.feeMinor)},amount(changes.after.feeMinor))))
        if(!changes.after.complete)YText(word("安排不完整；未列期次、全期费用和结清时间仍未知。","予定は不完全です。未記載の回・全期間の費用・完済日は不明です。"),12,Modifier.testTag("finance-plan-scope-incomplete"),color=LocalYohaku.current.warm)
        if(changes.before!=null) {
            YText(word("已确认账单保持不变","確定済みの請求は変更しません")+" · "+changes.retainedConfirmed,12)
            YText(word("保留其他原账期","その他の元請求を保持")+" · "+changes.retainedOther,12)
            YText(word("退出当前预测的账期","現在の予測から除外する回")+" · "+changes.withdrawn,12)
        }
    }
    if(summary)FinancialChangeSummary {facts()}else facts()
}

@Composable internal fun FinancialFieldChanges(changes:List<FinancialDisplayFacts.RuleDifference>,currency:Currency,first:Boolean=false) {
    val unknown=word("未知","不明")
    val removed=word("已移除","削除済み")
    val empty=word("未填写","未入力")
    val yes=word("是","はい");val no=word("否","いいえ")
    changes.forEach{change->
        val key=change.fieldKey
        fun value(v:JsonElement?):String {
            if(v==null)return removed
            val raw=(v as? JsonPrimitive)?.contentOrNull?:return unknown
            if(raw.isBlank())return empty
            if(key.endsWith("Minor"))return Money(currency,raw.toLong()).display()
            if(raw=="true")return yes
            if(raw=="false")return no
            return raw
        }
        @Composable fun display(v:JsonElement?,previous:Boolean=false)=when {
            v==null&&previous->word("原无此项","元の項目なし")
            key in setOf("sharing","usageBasis","usageStart","recovery")&&v!=null->limitLabel(v.jsonPrimitive.contentOrNull)
            key=="sourceDate"->when(v?.jsonPrimitive?.contentOrNull){"TRANSACTION"->word("交易日期","取引日");"ACCOUNT_POSTING"->word("账户入账日期","口座計上日");else->value(v)}
            else->value(v)
        }
        val before=display(change.before,previous=true);val after=display(change.after)
        val label=when(key){"totalMinor"->word("总额度","総枠");"openingMinor"->word("期初循环本金","期首元金");"principalMinor"->word("生效日转入本金","移行元金");"sourceDate"->word("新增本金起算日期","追加元金の開始日");else->com.yohaku.expensetracker.ui.features.command.fieldName(key)}
        RecordFacts(listOf(label to if(first)after else "$before → $after"))
    }
}

@Composable internal fun FinancialPoolScopeFacts(scope:FinancialDisplayFacts.PoolScope,currency:Currency) {
    RecordFacts(listOf(word("期初循环本金","期首元金") to ((scope.beforeOpeningMinor?.takeIf{it!=scope.openingMinor}?.let{Money(currency,it).display()+" → "}).orEmpty()+Money(currency,scope.openingMinor).display()),
        word("纳入本金变动","元金変動") to scope.sources.toString(),word("保留原账单","保持請求") to scope.retained.toString()))
}


@Composable internal fun FinancialTime(value:java.time.Instant,zone:java.time.ZoneId,semanticId:String,record:Boolean=false) {
    YText((if(record)word("记录时间：","記録日時：")else "")+FinancialDisplayFacts.time(value,zone,com.yohaku.expensetracker.ui.theme.LocalLanguage.current),11,
        Modifier.testTag("finance-time-$semanticId"))
}

@Composable internal fun FinancialTimeField(value:String,zone:java.time.ZoneId,owner:Int?,onOwner:(Int?)->Unit,onChange:(String)->Unit) {
    val instant=java.time.Instant.parse(value)
    val local=instant.atZone(zone).toLocalDateTime()
    androidx.compose.foundation.layout.Column(Modifier.testTag("finance-time-applied")) {
        YDateFields(listOf(word("实际办理时间","実際の手続き時刻") to local.toString()),owner,onOwner,{_,raw->
            val edited=java.time.LocalDateTime.parse(raw)
            // Opening/closing a calendar, or selecting the existing minute, never rewrites seconds/nanos.
            val next=financialEditedTime(value,edited,zone)
            if(next!=value)onChange(next)
        },displayValues=listOf(FinancialDisplayFacts.time(instant,zone,com.yohaku.expensetracker.ui.theme.LocalLanguage.current)))
    }
}

@Composable internal fun FinancialLimitRelationship(config:com.yohaku.expensetracker.data.db.CreditLimitConfigEntity?,currency:Currency,omitCaps:Set<String> = emptySet()) {
    FinancialLimitRelationship(FinancialDisplayFacts.limitRelationship(config),currency,omitCaps)
}

@Composable internal fun FinancialLimitRelationship(facts:FinancialDisplayFacts.LimitRelationship,currency:Currency,omitCaps:Set<String> = emptySet()) {
    fun amount(value:Long?)=value?.let{Money(currency,it).display()}
    val unknown=word("未知","不明")
    androidx.compose.foundation.layout.Column(Modifier.testTag("finance-limit-relationship")) {
        when(facts.sharing) {
            "TOTAL_ONLY"->YText(word("分期与循环还款共用总额度","分割・リボが共用する総枠")+if("totalMinor" !in omitCaps)" "+(amount(facts.total)?:unknown)else"",12)
            "SEPARATE_CAPS"->{
                RecordFacts(listOf(Triple("totalMinor",word("总额度上限","総枠上限"),facts.total),Triple("installmentMinor",word("分期子额度上限","分割個別枠上限"),facts.installment),Triple("revolvingMinor",word("循环还款子额度上限","リボ個別枠上限"),facts.revolving)).filterNot{it.first in omitCaps}.map{it.second to (amount(it.third)?:unknown)})
                YText(word("各子额度仍受总额度约束，不能相加使用。","各個別枠も総枠の制限を受け、合算して利用できません。"),11)
            }
            "SHARED_SUBPOOL"->{
                RecordFacts(listOf(Triple("totalMinor",word("总额度上限","総枠上限"),facts.total),Triple("combinedMinor",word("其中：分期与循环还款共用子额度","うち分割・リボの共通枠"),facts.combined)).filterNot{it.first in omitCaps}.map{it.second to (amount(it.third)?:unknown)})
                YText(word("共用子额度包含在总额度内，分期与循环还款共同使用。","共通の個別枠は総枠の内枠です。分割・リボで共用します。"),11)
            }
            else->YText(if(facts.configured)word("额度关系未知，不推定独立子额度。","枠の関係が不明です。独立した個別枠とは推定しません。")else word("额度关系尚未配置。","枠の関係は未設定です。"),12)
        }
    }
}

/** Called only by an actual date/time edit, never by presentation or recomposition. */
internal fun financialEditedTime(original:String,edited:java.time.LocalDateTime,zone:java.time.ZoneId):String {
        val local=java.time.Instant.parse(original).atZone(zone).toLocalDateTime()
        return if(edited.withSecond(0).withNano(0)==local.withSecond(0).withNano(0))original else edited.atZone(zone).toInstant().toString()
    }
