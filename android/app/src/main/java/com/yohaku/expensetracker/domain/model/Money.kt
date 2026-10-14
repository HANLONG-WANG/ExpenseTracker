package com.yohaku.expensetracker.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable

@Serializable
enum class Currency(val scale: Int, val symbol: String) { JPY(0,"¥"), CNY(2,"CN¥"), USD(2,"US$") }
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
    val merchant: String = "", val note: String = "", val historicalIncluded: Boolean = false
)
