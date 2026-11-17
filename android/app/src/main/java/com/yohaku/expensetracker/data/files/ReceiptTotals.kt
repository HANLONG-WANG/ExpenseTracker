package com.yohaku.expensetracker.data.files

import java.math.BigDecimal

/**
 * Known amounts only. Missing line amounts remain missing, even if quantity and price are known.
 * [differenceFromOriginal] compares the printed receipt total with the recorded amount, or the computed total when no
 * printed total is known but every line amount is; otherwise nothing comparable exists and it is null.
 * [differenceFromDeclared] exists only when every line amount is known, so a gap points at a misread line.
 */
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
    val allLinesKnown = record.items.isNotEmpty() && record.items.all { it.line_amount != null }
    val comparable = declared ?: total.takeIf { allLinesKnown }
    return ReceiptTotals(knownItems, adjustment, total, declared,
        record.detailsComplete,
        comparable?.takeIf { record.currency == original.currency }?.let { it - original.amount.toBigDecimal() },
        declared?.takeIf { allLinesKnown }?.let { total - it })
}
