package com.yohaku.expensetracker.data.files

import java.math.BigDecimal

/** Known amounts only. Missing line amounts remain missing, even if quantity and price are known. */
data class ReceiptTotals(val knownItems: BigDecimal, val additionalAdjustments: BigDecimal,
    val knownTotal: BigDecimal, val declaredTotal: BigDecimal?, val complete: Boolean,
    val differenceFromOriginal: BigDecimal?, val differenceFromDeclared: BigDecimal?)

/** Details are complete only when the result says so, every line amount is known and nothing is left to check. */
val ReceiptItemsRecord.detailsComplete get() = complete && items.all { it.line_amount != null } && issues.isEmpty()

fun receiptTotals(record: ReceiptItemsRecord, original: FileMoney): ReceiptTotals {
    val knownItems = record.items.mapNotNull { it.line_amount?.toBigDecimal() }.fold(BigDecimal.ZERO, BigDecimal::add)
    val adjustment = record.adjustments.filterNot { it.included_in_items }.fold(BigDecimal.ZERO) { sum, a ->
        if (a.type == "discount") sum - a.amount.toBigDecimal() else sum + a.amount.toBigDecimal()
    }
    val total = knownItems + adjustment
    val declared = record.receipt_total?.toBigDecimal()
    return ReceiptTotals(knownItems, adjustment, total, declared,
        record.detailsComplete,
        if(record.currency==original.currency)total - original.amount.toBigDecimal()else null, declared?.let { total - it })
}
