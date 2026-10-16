package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import kotlinx.serialization.Serializable

/** Text remains editable until preview; invalid text is never converted to an unknown/zero. */
@Serializable internal data class FinancialRuleDraft(val mode:FinanceMode,val values:Map<String,String> = emptyMap(),val deadlines:List<ChangeDeadline> = emptyList()) {
    operator fun get(key:String)=values[key].orEmpty()
    fun with(key:String,value:String)=copy(values=values+(key to value))
    private fun optional(key:String)=get(key).takeIf{it.isNotBlank()}
    private fun integer(key:String)=optional(key)?.let{it.toIntOrNull()?:error("请输入整数 / 整数を入力してください")}
    private fun amount(key:String,currency:Currency)=optional(key)?.let{Money.parse(it,currency).minor}
    private inline fun <reified E:Enum<E>> choice(key:String):E?=optional(key)?.let{enumValueOf<E>(it)}
    private fun boolean(key:String):Boolean?=optional(key)?.let{it.toBooleanStrict()}
    fun build(bookId:String,accountId:String,currency:Currency):FinancialRuleVersion {
        val interest=InterestRule(optional("apr")?.let{(it.toBigDecimalOrNull()?:error("请输入有效年率 / 有効な年利率を入力してください")).movePointLeft(2).toPlainString()},
            choice("frequency"),choice("balance"),boolean("includeStart"),boolean("includeEnd"),integer("yearDays"),choice("roundAt"),optional("rounding"),choice("first"))
        val revolving=RevolvingRule(interest,choice("paymentMethod"),amount("payment",currency),optional("tiers")?.split(',')?.map{entry->
            val fields=entry.trim().split(':')
            require(fields.size==2){"阶梯格式为上限:月金额，末档用 * / 残高上限:月額の形式、最終区分は *"}
            PaymentTier(if(fields[0].trim()=="*")null else Money.parse(fields[0],currency).minor,Money.parse(fields[1],currency).minor)
        }.orEmpty(),boolean("tiersIncludeFees"),choice("allocation"),if(mode==FinanceMode.REVOLVING)boolean("automatic") else null,choice("tierBalanceDate"))
        val installment=InstallmentRule(optional("periods")?.split(',')?.map{it.trim().toIntOrNull()?:error("请输入逗号分隔的期数 / 回数をカンマ区切りで入力してください")}.orEmpty(),
            choice("feeMethod"),amount("totalFee",currency),optional("totalRate")?.let{(it.toBigDecimalOrNull()?:error("请输入有效费率 / 有効な料率を入力してください")).movePointLeft(2).toPlainString()},
            optional("feeTable")?.split(',')?.map{entry->val fields=entry.trim().split(':');require(fields.size==2){"费用表格式为期数:每100单位费用 / 回数:100通貨単位当たり手数料の形式で入力してください"}
                InstallmentFee(fields[0].trim().toIntOrNull()?:error("期数必须是整数 / 回数は整数です"),fields[1].trim())}.orEmpty(),
            choice("principalRemainder"),choice("feeRemainder"),optional("rounding"))
        return FinancialRuleVersion("",bookId,accountId,mode,get("effective"),get("zone"),
            if(mode in listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT,FinanceMode.REVOLVING))BillingRule(integer("closing"),boolean("closingIncluded"),integer("monthOffset"),integer("payday"),optional("bank"))else null,
            if(mode==FinanceMode.INSTALLMENT)installment else null,if(mode in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE))revolving else null,
            if(mode==FinanceMode.FIXED_LOAN)choice("loanMethod")else null,if(mode==FinanceMode.FIXED_LOAN)interest else null,
            choice("allocation"),null,optional("unsupported"),loanPrincipalRemainder=choice("principalRemainder"),deadlines=deadlines)
    }
}

internal fun FinancialRuleVersion.toForm(currency:Currency):FinancialRuleDraft {
    val values=mutableMapOf("effective" to effectiveFrom,"zone" to zoneId)
    fun put(key:String,value:Any?){if(value!=null)values[key]=value.toString()}
    fun amount(key:String,value:Long?)=put(key,value?.let{Money(currency,it).decimal.toPlainString()})
    billing?.let{put("closing",it.closingDay);put("closingIncluded",it.closingDayInCurrentBill);put("monthOffset",it.paymentMonthOffset);put("payday",it.paymentDay);put("bank",it.paymentAccountId)}
    val interest=revolving?.interest?:loanInterest
    interest?.let{put("apr",it.annualRate?.toBigDecimal()?.movePointRight(2)?.toPlainString());put("frequency",it.frequency);put("balance",it.balanceBasis)
        put("includeStart",it.includeStart);put("includeEnd",it.includeEnd);put("yearDays",it.yearDays);put("roundAt",it.rounding);put("rounding",it.roundingMode);put("first",it.firstCharge)}
    revolving?.let{put("paymentMethod",it.method);amount("payment",it.paymentMinor);put("tiers",it.tiers.joinToString(","){tier->(tier.upToMinor?.let{n->Money(currency,n).decimal.toPlainString()}?:"*")+":"+Money(currency,tier.paymentMinor).decimal.toPlainString()});put("tiersIncludeFees",it.tierPaymentIncludesFees);put("tierBalanceDate",it.tierBalanceDate);put("allocation",it.allocation);put("automatic",it.automatic)}
    installment?.let{put("periods",it.supportedPeriods.joinToString(","));put("feeMethod",it.feeMethod);amount("totalFee",it.totalFeeMinor);put("totalRate",it.totalRate?.toBigDecimal()?.movePointRight(2)?.toPlainString());put("feeTable",it.feeTable.joinToString(","){f->"${f.periods}:${f.feePerHundred}"});put("principalRemainder",it.principalRemainder);put("feeRemainder",it.feeRemainder);put("rounding",it.roundingMode)}
    put("loanMethod",loanMethod);put("principalRemainder",loanPrincipalRemainder?:installment?.principalRemainder);put("allocation",allocation?:revolving?.allocation);put("recovery",limitRecovery);put("unsupported",unsupportedReason)
    return FinancialRuleDraft(mode,values,deadlines)
}
