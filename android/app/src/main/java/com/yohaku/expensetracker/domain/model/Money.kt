package com.yohaku.expensetracker.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable

@Serializable
enum class Currency(val scale: Int, val symbol: String) { JPY(0,"¥"), CNY(2,"CN¥"), USD(2,"US$") }
@Serializable
data class Money(val currency: Currency, val minor: Long) {
    val decimal: BigDecimal get() = BigDecimal.valueOf(minor, currency.scale)
    fun digits(): String = java.text.DecimalFormat("#,##0" + if(currency.scale==2)".00" else "", java.text.DecimalFormatSymbols(java.util.Locale.JAPAN)).format(decimal)
    fun display(): String = currency.symbol + digits()
    companion object {
        fun parse(text: String, currency: Currency): Money {
            val decimal = text.trim().toBigDecimalOrNull() ?: error("请输入有效金额 / 有効な金額を入力してください")
            require(decimal >= BigDecimal.ZERO) { "金额不能为负 / 金額は0以上にしてください" }
            return Money(currency, decimal.setScale(currency.scale, RoundingMode.UNNECESSARY).movePointRight(currency.scale).longValueExact())
        }
    }
}
@Serializable
enum class AccountType { CASH, BANK, CREDIT, LOAN, IC;
    val debt: Boolean get() = this == CREDIT || this == LOAN
}
@Serializable
enum class EntryKind { EXPENSE, INCOME, TRANSFER, REPAYMENT, BORROWING, REFUND }
@Serializable
data class EntryDraft(
    val id: String? = null, val kind: EntryKind = EntryKind.EXPENSE,
    val categoryId: String? = null, val accountId: String? = null,
    val amount: String = "", val baseAmount: String = "", val date: String = java.time.LocalDate.now().toString(),
    val merchant: String = "", val merchantId:String?=null,val merchantManuallyEdited:Boolean=false,val note: String = "", val historicalIncluded: Boolean = false,
    val originalCurrency: Currency? = null, val accountAmount: String = "",
    val settlementConfirmed: Boolean = true, val settlementDate: String = date,
    val rateSource: String = "manual", val rateDate: String = date,
    val originalEventId: String? = null, val manualRate: String = "",
    val toAccountId:String?=null,val toAmount:String="",val toDate:String=date,
    val fromConfirmed:Boolean=false,val toConfirmed:Boolean=false,
    val feeAmount:String="",val feeCurrency:Currency?=null,val feeAccountAmount:String="",val feeBaseAmount:String="",val feePosition:TransferFeePosition=TransferFeePosition.INCLUDED_FROM,
    val feeAccountId:String?=null,val feeDate:String=date,val feeConfirmed:Boolean=false,
    val estimateDirection:String="TO",val activityId:String?=null,val occurrenceId:String?=null,
    val reserveId:String?=null,val reserveAmount:String="",val treatEnabled:Boolean=false,val treatFriends:List<String> = emptyList(),
    val mediaSession:String=java.util.UUID.randomUUID().toString(),val mediaInitialized:Boolean=false,val attachmentIds:List<String> = emptyList(),val receiptFileIds:List<String> = emptyList(),
    val treatMode:TreatSplitMode=TreatSplitMode.EQUAL,val treatWeights:Map<String,String> = emptyMap(),
    val referenceQueryDate:String?=null,val referenceAccountLinked:Boolean=false,val referenceBaseLinked:Boolean=false,
    val referenceExpectedFromMinor:Long?=null,val referenceExpectedToMinor:Long?=null,
    val referenceExpectedAccountMinor:Long?=null,val referenceExpectedBaseMinor:Long?=null,
    val referenceRates:List<com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot> = emptyList(),
    val occurrenceLinkEventId:String?=null,
    val cardPaymentChoice:String?=null,val cardPaymentPeriods:String="",val cardPaymentRecheck:Boolean=false
)

@Serializable
enum class TransferFeePosition { INCLUDED_FROM, DEDUCTED_TO, SEPARATE }

@Serializable
enum class RecurrenceFrequency { WEEKLY, MONTHLY, YEARLY }

@Serializable
enum class TreatSplitMode { EQUAL, AMOUNTS, PERCENT }
