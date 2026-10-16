package com.yohaku.expensetracker.domain.reconcile

import com.yohaku.expensetracker.data.files.StatementFile
import java.math.BigDecimal

/** A real confirmed account posting, not an original foreign amount or a forecast. */
data class ReconcilePosting(
    val bookId: String, val accountId: String, val postingId: String, val eventId: String,
    val currency: String, val amount: BigDecimal, val direction: String,
    val postedDate: String, val occurredDate: String,
)
data class StatementMatch(val rowId: String, val postingId: String)
data class StatementDifference(val rowId: String, val candidatePostingIds: List<String>)
data class StatementMatchingResult(
    val exact: List<StatementMatch>, val statementOnly: List<String>,
    val differences: List<StatementDifference>, val ledgerOnly: List<String>,
    val uncovered: List<String>,
)

/** One row occupies one posting globally. Caller supplies postings occupied by other batches.
 * Only this file's account and explicit date semantics define coverage. No cross-account inference.
 */
fun matchStatement(file: StatementFile, postings: List<ReconcilePosting>,
    occupiedPostingIds: Set<String> = emptySet()): StatementMatchingResult {
    val scoped = postings.filter { it.bookId == file.book_id && it.accountId == file.account_id && it.currency == file.account_currency }
    fun date(p: ReconcilePosting) = if (file.date_basis == "posted_date") p.postedDate else p.occurredDate
    val available = scoped.filter { it.postingId !in occupiedPostingIds }.sortedBy { it.postingId }
    val used = occupiedPostingIds.toMutableSet()
    val exact = mutableListOf<StatementMatch>()
    val remaining = mutableListOf<com.yohaku.expensetracker.data.files.StatementRow>()
    // Allocate exact matches first: a loose candidate must never consume another row's exact match.
    file.rows.forEach { row ->
        val p = available.firstOrNull { it.postingId !in used && it.direction == row.direction && date(it) == row.date && it.amount.compareTo(row.settlement.amount.toBigDecimal()) == 0 }
        if (p == null) remaining += row else { used += p.postingId; exact += StatementMatch(row.row_id, p.postingId) }
    }
    val differences = mutableListOf<StatementDifference>()
    val statementOnly = mutableListOf<String>()
    remaining.forEach { row ->
        val candidates = available.filter { it.postingId !in used && it.direction == row.direction &&
            (date(it) == row.date || it.amount.compareTo(row.settlement.amount.toBigDecimal()) == 0) }
        if (candidates.isEmpty()) statementOnly += row.row_id
        else differences += StatementDifference(row.row_id, candidates.map { it.postingId })
    }
    val unmatched = scoped.filter { it.postingId !in used }
    val covered = unmatched.filter { date(it) >= file.from && date(it) <= file.to && file.complete && file.issues.isEmpty() }
    return StatementMatchingResult(exact, statementOnly, differences, covered.map { it.postingId },
        unmatched.filter { it !in covered }.map { it.postingId })
}
