@file:Suppress("LargeClass", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package app.ledger.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import java.math.BigInteger

data class SqliteCapabilityReport(
    val sqlCipherVersion: String,
    val fts5: Boolean,
    val rTree: Boolean,
    val json: Boolean,
    val windowFunctions: Boolean,
)

data class DatabaseIntegrityReport(
    val integrityCheck: String,
    val foreignKeyViolationCount: Int,
    val unbalancedJournalCount: Int,
    val invalidCurrentSubtypeCount: Int,
    val postingCurrencyViolationCount: Int,
    val invalidActiveApplyChainCount: Int,
    val nonZeroTrashedTransactionCount: Int,
    /** Every INV-001..INV-035 key is always present for an initialized ledger. */
    val permanentInvariantViolationCounts: Map<String, Int>,
    val auditQueryFailureCount: Int,
    val capability: SqliteCapabilityReport,
) {
    val failedInvariantIds: Set<String>
        get() = permanentInvariantViolationCounts.filterValues { it != 0 }.keys

    val isValid: Boolean
        get() = isValidIgnoringPermanentInvariants(emptySet())

    val isValidIgnoringProjectionInvariants: Boolean
        get() = isValidIgnoringPermanentInvariants(PROJECTION_INVARIANT_IDS)

    private fun isValidIgnoringPermanentInvariants(ignoredIds: Set<String>): Boolean = integrityCheck == "ok" &&
        foreignKeyViolationCount == 0 &&
        unbalancedJournalCount == 0 &&
        invalidCurrentSubtypeCount == 0 &&
        postingCurrencyViolationCount == 0 &&
        invalidActiveApplyChainCount == 0 &&
        nonZeroTrashedTransactionCount == 0 &&
        permanentInvariantViolationCounts
            .filterKeys { it !in ignoredIds }
            .values
            .all { it == 0 } &&
        auditQueryFailureCount == 0 &&
        capability.sqlCipherVersion.isNotBlank() && capability.fts5 && capability.rTree &&
        capability.json && capability.windowFunctions

    private companion object {
        val PROJECTION_INVARIANT_IDS: Set<String> = setOf("INV-031", "INV-035")
    }
}

/**
 * Full database-side repetition of the frozen INV-001..INV-035 standard.
 *
 * The checks deliberately use only authoritative Current/Revision/Fact rows. Derived-state
 * reconstruction and canonical Hash comparison are added by the finance-data comprehensive audit,
 * which runs this report before comparing a savepoint rebuild with the live projections.
 */
object DatabaseIntegrityAudit {
    val permanentInvariantIds: Set<String> = (1..PERMANENT_INVARIANT_COUNT).mapTo(linkedSetOf()) {
        "INV-${it.toString().padStart(3, '0')}"
    }

    fun run(database: SupportSQLiteDatabase): DatabaseIntegrityReport {
        val queryFailures = intArrayOf(0)
        val integrity = safeString(database, "PRAGMA integrity_check", queryFailures)
        val foreignKeys = safeRowCount(database, "PRAGMA foreign_key_check", queryFailures)
        val hasBook = safeCount(
            database,
            "SELECT EXISTS(SELECT 1 FROM book WHERE id=1)",
            queryFailures,
        ) == 1
        val invariants = if (hasBook) {
            runPermanentInvariantChecks(database, queryFailures)
        } else {
            permanentInvariantIds.associateWith { 0 }
        }
        return DatabaseIntegrityReport(
            integrityCheck = integrity,
            foreignKeyViolationCount = foreignKeys,
            unbalancedJournalCount = invariants.getValue("INV-001"),
            invalidCurrentSubtypeCount = invariants.getValue("INV-005"),
            postingCurrencyViolationCount = invariants.getValue("INV-002"),
            invalidActiveApplyChainCount = invariants.getValue("INV-008"),
            nonZeroTrashedTransactionCount = invariants.getValue("INV-009"),
            permanentInvariantViolationCounts = invariants,
            auditQueryFailureCount = queryFailures[0],
            capability = capabilityReport(database),
        )
    }

    fun capabilityReport(database: SupportSQLiteDatabase): SqliteCapabilityReport {
        val compileOptions = mutableSetOf<String>()
        database.query("PRAGMA compile_options").use { cursor ->
            while (cursor.moveToNext()) compileOptions += cursor.getString(0)
        }
        val fts5 = "ENABLE_FTS5" in compileOptions && querySucceeds(database, "SELECT count(*) FROM transaction_fts")
        val rTree = "ENABLE_RTREE" in compileOptions &&
            querySucceeds(database, "SELECT count(*) FROM location_rtree") &&
            querySucceeds(database, "SELECT count(*) FROM place_rtree")
        val json = singleInt(database, "SELECT json_valid('{\"p07\":true}')") == 1
        val window = singleInt(
            database,
            "SELECT row_number FROM (SELECT ROW_NUMBER() OVER (ORDER BY value) AS row_number FROM (SELECT 1 AS value))",
        ) == 1
        return SqliteCapabilityReport(
            sqlCipherVersion = singleString(database, "PRAGMA cipher_version"),
            fts5 = fts5,
            rTree = rTree,
            json = json,
            windowFunctions = window,
        )
    }

    private fun runPermanentInvariantChecks(
        database: SupportSQLiteDatabase,
        queryFailures: IntArray,
    ): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        fun check(id: Int, sql: String) {
            result[invariantId(id)] = safeCount(database, sql, queryFailures)
        }
        fun structural(id: Int, violations: () -> Int) {
            result[invariantId(id)] = runCatching(violations).getOrElse {
                queryFailures[0] = Math.addExact(queryFailures[0], 1)
                1
            }
        }

        check(
            1,
            """
            SELECT COUNT(*) FROM (
              SELECT je.id FROM journal_entry je LEFT JOIN posting p ON p.journal_entry_id=je.id
              GROUP BY je.id
              HAVING je.base_debit_total_minor<>je.base_credit_total_minor
                 OR COUNT(p.id)<>je.posting_count OR COUNT(p.id)<2
                 OR COALESCE(SUM(CASE WHEN p.side=0 THEN p.base_amount_minor ELSE 0 END),0)<>je.base_debit_total_minor
                 OR COALESCE(SUM(CASE WHEN p.side=1 THEN p.base_amount_minor ELSE 0 END),0)<>je.base_credit_total_minor
            )
            """.trimIndent(),
        )
        check(
            2,
            "SELECT COUNT(*) FROM posting p JOIN ledger_account la ON la.id=p.ledger_account_id JOIN book b ON b.id=1 " +
                "WHERE p.account_currency<>la.currency_code OR p.base_currency<>b.base_currency",
        )
        check(
            3,
            "SELECT COUNT(*) FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
                "LEFT JOIN current_transaction_projection ctp ON ctp.transaction_id=bt.id " +
                "WHERE ctp.transaction_id IS NOT NULL AND (ctp.category_id IS NOT tr.category_id OR ctp.project_id IS NOT tr.project_id OR ctp.goal_id IS NOT tr.goal_id)",
        )
        check(
            4,
            "SELECT COUNT(*) FROM transaction_revision tr JOIN business_transaction bt ON bt.id=tr.transaction_id " +
                "WHERE bt.kind IN (0,1) AND (tr.category_id IS NULL OR " +
                "(SELECT COUNT(DISTINCT ra.component_index) FROM revision_amount ra WHERE ra.revision_id=tr.id AND ra.role=0)<>1)",
        )
        check(
            5,
            "SELECT COUNT(*) FROM business_transaction bt LEFT JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
                "LEFT JOIN current_transaction_subtype_audit sta ON sta.transaction_id=bt.id " +
                "WHERE tr.id IS NULL OR tr.transaction_id<>bt.id OR tr.resulting_state<>bt.lifecycle_state " +
                "OR tr.revision_no<>(SELECT MAX(r2.revision_no) FROM transaction_revision r2 WHERE r2.transaction_id=bt.id) " +
                "OR COALESCE(sta.has_matching_detail,0)=0",
        )
        structural(6) { missingImmutableTriggerCount(database, HISTORICAL_FACT_TABLES) }
        check(
            7,
            """
            SELECT COUNT(*) FROM journal_entry reverse_entry
            LEFT JOIN journal_entry original ON original.id=reverse_entry.reverses_entry_id
            WHERE reverse_entry.entry_role=1 AND (
              original.id IS NULL OR original.entry_role<>0 OR reverse_entry.applies_revision_id<>original.applies_revision_id
              OR (SELECT COUNT(*) FROM journal_entry duplicate WHERE duplicate.reverses_entry_id=original.id)<>1
              OR reverse_entry.base_currency<>original.base_currency OR reverse_entry.rule_set_version<>original.rule_set_version
              OR (SELECT COUNT(*) FROM posting rp WHERE rp.journal_entry_id=reverse_entry.id)<>
                 (SELECT COUNT(*) FROM posting op WHERE op.journal_entry_id=original.id)
              OR EXISTS(
                SELECT 1 FROM posting op LEFT JOIN posting rp ON rp.reversal_of_posting_id=op.id
                WHERE op.journal_entry_id=original.id AND (
                  rp.id IS NULL OR rp.journal_entry_id<>reverse_entry.id OR rp.ledger_account_id<>op.ledger_account_id
                  OR rp.side=op.side OR rp.account_amount_minor<>op.account_amount_minor
                  OR rp.account_currency<>op.account_currency OR rp.base_amount_minor<>op.base_amount_minor
                  OR rp.base_currency<>op.base_currency
                )
              )
            )
            """.trimIndent(),
        )
        check(
            8,
            """
            SELECT COUNT(*) FROM business_transaction bt WHERE bt.lifecycle_state=0 AND (
              NOT (
                EXISTS(
                  SELECT 1 FROM journal_entry apply_entry
                  WHERE apply_entry.applies_revision_id=bt.current_revision_id AND apply_entry.entry_role=0
                    AND NOT EXISTS(SELECT 1 FROM journal_entry reverse_entry WHERE reverse_entry.reverses_entry_id=apply_entry.id)
                )
                OR EXISTS(
                  SELECT 1 FROM settlement_effect effect
                  WHERE effect.source_revision_id=bt.current_revision_id AND effect.reversal_of_id IS NULL
                    AND NOT EXISTS(SELECT 1 FROM settlement_effect reversal WHERE reversal.reversal_of_id=effect.id)
                )
                OR EXISTS(
                  SELECT 1 FROM transaction_revision current_revision
                  JOIN settlement_payment_record record ON record.uid=current_revision.source_reference_uid
                  JOIN settlement_effect effect ON effect.settlement_payment_record_id=record.id
                  WHERE current_revision.id=bt.current_revision_id AND record.linked_transaction_id IS NULL
                    AND record.reversal_of_id IS NULL AND effect.reversal_of_id IS NULL
                    AND NOT EXISTS(SELECT 1 FROM settlement_payment_record reversal WHERE reversal.reversal_of_id=record.id)
                    AND NOT EXISTS(SELECT 1 FROM settlement_effect reversal WHERE reversal.reversal_of_id=effect.id)
                )
              )
              OR EXISTS(
                SELECT 1 FROM journal_entry apply_entry JOIN transaction_revision tr ON tr.id=apply_entry.applies_revision_id
                WHERE tr.transaction_id=bt.id AND tr.id<>bt.current_revision_id AND apply_entry.entry_role=0
                  AND NOT EXISTS(SELECT 1 FROM journal_entry reverse_entry WHERE reverse_entry.reverses_entry_id=apply_entry.id)
              )
              OR EXISTS(
                SELECT 1 FROM settlement_effect effect JOIN transaction_revision tr ON tr.id=effect.source_revision_id
                WHERE tr.transaction_id=bt.id AND tr.id<>bt.current_revision_id AND effect.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM settlement_effect reversal WHERE reversal.reversal_of_id=effect.id)
              )
            )
            """.trimIndent(),
        )
        check(
            9,
            """
            SELECT COUNT(*) FROM business_transaction bt WHERE bt.lifecycle_state=1 AND (
              EXISTS(
                SELECT 1 FROM journal_entry apply_entry JOIN transaction_revision tr ON tr.id=apply_entry.applies_revision_id
                WHERE tr.transaction_id=bt.id AND apply_entry.entry_role=0
                  AND NOT EXISTS(SELECT 1 FROM journal_entry reverse_entry WHERE reverse_entry.reverses_entry_id=apply_entry.id)
              )
              OR EXISTS(
                SELECT 1 FROM economic_effect effect JOIN transaction_revision tr ON tr.id=effect.source_revision_id
                WHERE tr.transaction_id=bt.id AND effect.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM economic_effect reversal WHERE reversal.reversal_of_id=effect.id)
              )
              OR EXISTS(
                SELECT 1 FROM budget_effect effect JOIN transaction_revision tr ON tr.id=effect.source_revision_id
                WHERE tr.transaction_id=bt.id AND effect.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM budget_effect reversal WHERE reversal.reversal_of_id=effect.id)
              )
              OR EXISTS(
                SELECT 1 FROM loan_effect effect JOIN transaction_revision tr ON tr.id=effect.source_revision_id
                WHERE tr.transaction_id=bt.id AND effect.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM loan_effect reversal WHERE reversal.reversal_of_id=effect.id)
              )
              OR EXISTS(
                SELECT 1 FROM project_effect effect JOIN transaction_revision tr ON tr.id=effect.source_revision_id
                WHERE tr.transaction_id=bt.id AND effect.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM project_effect reversal WHERE reversal.reversal_of_id=effect.id)
              )
              OR EXISTS(
                SELECT 1 FROM goal_effect effect JOIN transaction_revision tr ON tr.id=effect.source_revision_id
                WHERE tr.transaction_id=bt.id AND effect.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM goal_effect reversal WHERE reversal.reversal_of_id=effect.id)
              )
              OR EXISTS(
                SELECT 1 FROM statement_effect effect JOIN transaction_revision tr ON tr.id=effect.source_revision_id
                WHERE tr.transaction_id=bt.id AND effect.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM statement_effect reversal WHERE reversal.reversal_of_id=effect.id)
              )
              OR EXISTS(
                SELECT 1 FROM settlement_effect effect JOIN transaction_revision tr ON tr.id=effect.source_revision_id
                WHERE tr.transaction_id=bt.id AND effect.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM settlement_effect reversal WHERE reversal.reversal_of_id=effect.id)
              )
              OR EXISTS(
                SELECT 1 FROM refund_allocation allocation
                WHERE allocation.refund_transaction_id=bt.id AND allocation.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM refund_allocation reversal WHERE reversal.reversal_of_id=allocation.id)
              )
              OR EXISTS(
                SELECT 1 FROM credit_payment_allocation allocation
                WHERE allocation.payment_transaction_id=bt.id AND allocation.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM credit_payment_allocation reversal WHERE reversal.reversal_of_id=allocation.id)
              )
              OR EXISTS(
                SELECT 1 FROM installment_refund_allocation allocation
                WHERE allocation.refund_transaction_id=bt.id AND allocation.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM installment_refund_allocation reversal WHERE reversal.reversal_of_id=allocation.id)
              )
              OR EXISTS(
                SELECT 1 FROM loan_actual_allocation allocation
                WHERE allocation.payment_transaction_id=bt.id AND allocation.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM loan_actual_allocation reversal WHERE reversal.reversal_of_id=allocation.id)
              )
              OR EXISTS(
                SELECT 1 FROM settlement_payment_record record
                WHERE record.linked_transaction_id=bt.id AND record.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM settlement_payment_record reversal WHERE reversal.reversal_of_id=record.id)
              )
              OR EXISTS(
                SELECT 1 FROM goal_movement movement
                WHERE movement.source_transaction_id=bt.id AND movement.reversal_of_id IS NULL
                  AND NOT EXISTS(SELECT 1 FROM goal_movement reversal WHERE reversal.reversal_of_id=movement.id)
              )
            )
            """.trimIndent(),
        )
        check(
            10,
            """
            SELECT COUNT(*) FROM refund_status_projection rsp WHERE
              (rsp.refunded_minor>rsp.gross_refundable_minor AND NOT EXISTS(
                SELECT 1 FROM refund_allocation override_allocation
                JOIN business_transaction refund ON refund.id=override_allocation.refund_transaction_id
                JOIN refund_revision_detail detail ON detail.revision_id=refund.current_revision_id
                WHERE override_allocation.original_transaction_id=rsp.original_transaction_id
                  AND refund.lifecycle_state=0 AND detail.allow_excess=1
                  AND override_allocation.reversal_of_id IS NULL
                  AND NOT EXISTS(
                    SELECT 1 FROM refund_allocation reversal
                    WHERE reversal.reversal_of_id=override_allocation.id
                  )
              )) OR rsp.refunded_minor<>COALESCE((
                SELECT SUM(CASE WHEN ra.reversal_of_id IS NULL THEN ra.original_currency_amount_minor ELSE -ra.original_currency_amount_minor END)
                FROM refund_allocation ra WHERE ra.original_transaction_id=rsp.original_transaction_id
              ),0)
            """.trimIndent(),
        )
        structural(11) {
            missingColumns(
                database,
                mapOf(
                    "journal_entry" to setOf("local_date"),
                    "economic_effect" to setOf("accrual_local_date"),
                    "budget_effect" to setOf("target_year_month"),
                ),
            )
        }
        check(
            12,
            "SELECT COUNT(*) FROM transaction_revision tr JOIN business_transaction bt ON bt.id=tr.transaction_id WHERE bt.kind=4 AND (" +
                "EXISTS(SELECT 1 FROM economic_effect e WHERE e.source_revision_id=tr.id) OR " +
                "EXISTS(SELECT 1 FROM budget_effect e WHERE e.source_revision_id=tr.id) OR " +
                "EXISTS(SELECT 1 FROM project_effect e WHERE e.source_revision_id=tr.id) OR " +
                "EXISTS(SELECT 1 FROM goal_effect e WHERE e.source_revision_id=tr.id))",
        )
        check(
            13,
            "SELECT COUNT(*) FROM transaction_revision tr JOIN business_transaction bt ON bt.id=tr.transaction_id " +
                "WHERE bt.kind=6 AND EXISTS(SELECT 1 FROM loan_effect le WHERE le.source_revision_id=tr.id AND le.kind=1) " +
                "AND NOT EXISTS(SELECT 1 FROM loan_effect le WHERE le.source_revision_id=tr.id AND le.kind IN (2,3,4)) " +
                "AND (tr.category_id IS NOT NULL OR EXISTS(SELECT 1 FROM economic_effect ee WHERE ee.source_revision_id=tr.id))",
        )
        check(
            14,
            "SELECT COUNT(*) FROM transaction_revision tr JOIN business_transaction bt ON bt.id=tr.transaction_id " +
                "WHERE bt.kind=6 AND EXISTS(SELECT 1 FROM loan_effect le WHERE le.source_revision_id=tr.id AND le.kind IN (2,3,4)) " +
                "AND (EXISTS(SELECT 1 FROM economic_effect ee WHERE ee.source_revision_id=tr.id " +
                "AND (ee.nature<>1 OR ee.is_consumption<>0)) OR " +
                "COALESCE((SELECT SUM(le.polarity*le.base_amount_minor) FROM loan_effect le " +
                "WHERE le.source_revision_id=tr.id AND le.kind IN (2,3,4)),0)<>" +
                "COALESCE((SELECT SUM(ee.polarity*ee.base_amount_minor) FROM economic_effect ee " +
                "WHERE ee.source_revision_id=tr.id AND ee.nature=1 AND ee.is_consumption=0),0))",
        )
        check(
            15,
            "SELECT COUNT(*) FROM transaction_revision tr JOIN business_transaction bt ON bt.id=tr.transaction_id WHERE bt.kind=2 AND (" +
                "EXISTS(SELECT 1 FROM economic_effect e WHERE e.source_revision_id=tr.id) OR " +
                "EXISTS(SELECT 1 FROM budget_effect e WHERE e.source_revision_id=tr.id) OR " +
                "EXISTS(SELECT 1 FROM project_effect e WHERE e.source_revision_id=tr.id) OR " +
                "EXISTS(SELECT 1 FROM goal_effect e WHERE e.source_revision_id=tr.id) OR EXISTS(" +
                "SELECT 1 FROM journal_entry je JOIN posting p ON p.journal_entry_id=je.id " +
                "JOIN ledger_account la ON la.id=p.ledger_account_id " +
                "WHERE je.applies_revision_id=tr.id AND je.entry_role=0 AND la.owner_type=0 AND la.account_class=0 " +
                "GROUP BY je.applies_revision_id HAVING SUM(CASE p.side WHEN 0 THEN p.base_amount_minor ELSE -p.base_amount_minor END)<>0))",
        )
        structural(16) { missingImmutableTriggerCount(database, setOf("revision_amount", "fx_rate_snapshot", "journal_entry", "posting")) }
        check(
            17,
            "SELECT COUNT(*) FROM economic_effect ee JOIN transaction_revision tr ON tr.id=ee.source_revision_id " +
                "WHERE tr.source_type=7 AND ee.component=4 AND ee.nature IN (0,1)",
        )
        check(
            18,
            """
            SELECT COUNT(*) FROM budget_month bm JOIN budget_month_revision bmr ON bmr.id=bm.current_revision_id
            WHERE COALESCE((
              SELECT SUM(bcl.amount_base_minor) FROM budget_category_limit bcl JOIN category c ON c.id=bcl.category_id
              WHERE bcl.budget_month_revision_id=bmr.id AND c.depth=1
            ),0)>bmr.base_total_minor
            """.trimIndent(),
        )
        check(
            19,
            """
            SELECT COUNT(*) FROM budget_month bm JOIN budget_month_revision bmr ON bmr.id=bm.current_revision_id
            JOIN budget_category_limit parent_limit ON parent_limit.budget_month_revision_id=bmr.id
            JOIN category parent ON parent.id=parent_limit.category_id AND parent.depth=1
            WHERE COALESCE((
              SELECT SUM(child_limit.amount_base_minor) FROM budget_category_limit child_limit
              JOIN category child ON child.id=child_limit.category_id
              WHERE child_limit.budget_month_revision_id=bmr.id AND child.parent_id=parent.id
            ),0)>parent_limit.amount_base_minor
            """.trimIndent(),
        )
        check(20, "SELECT COUNT(*) FROM budget_rollover br JOIN book b ON b.id=1 WHERE br.as_of_local_revision>b.local_revision")
        check(
            21,
            "SELECT (SELECT COUNT(*) FROM goal_effect ge JOIN goal g ON g.id=ge.goal_id " +
                "JOIN user_account ua ON ua.id=g.account_id WHERE ge.currency_code<>ua.currency_code) + " +
                "(SELECT COUNT(DISTINCT gm.id) FROM goal_movement gm JOIN journal_entry je " +
                "ON je.created_commit_id=gm.created_commit_id WHERE gm.source_transaction_id IS NULL)",
        )
        check(
            22,
            "SELECT COUNT(*) FROM (SELECT activity_id,source_revision_id,settlement_payment_record_id,currency_code " +
                "FROM settlement_effect GROUP BY activity_id,source_revision_id,settlement_payment_record_id,currency_code " +
                "HAVING SUM(signed_delta_minor)<>0)",
        )
        check(
            23,
            "SELECT COUNT(*) FROM settlement_payment_record spr WHERE spr.linked_transaction_id IS NOT NULL " +
                "AND NOT EXISTS(SELECT 1 FROM participant self WHERE self.is_self=1 " +
                "AND self.id IN (spr.payer_participant_id,spr.payee_participant_id)) AND EXISTS(" +
                "SELECT 1 FROM transaction_revision tr JOIN journal_entry je ON je.applies_revision_id=tr.id " +
                "JOIN posting p ON p.journal_entry_id=je.id JOIN ledger_account la ON la.id=p.ledger_account_id " +
                "WHERE tr.transaction_id=spr.linked_transaction_id AND je.entry_role=0 AND la.owner_type=0 " +
                "AND NOT EXISTS(SELECT 1 FROM journal_entry reversal WHERE reversal.reverses_entry_id=je.id))",
        )
        structural(24) { missingImmutableTriggerCount(database, setOf("settlement_payment_record", "settlement_effect")) }
        check(
            25,
            """
            SELECT COUNT(*) FROM loan_tranche lt JOIN loan_schedule_revision lsr ON lsr.tranche_id=lt.id
            WHERE lsr.revision_no=(SELECT MAX(latest.revision_no) FROM loan_schedule_revision latest WHERE latest.tranche_id=lt.id)
              AND COALESCE((SELECT SUM(item.principal_minor) FROM loan_schedule_item item WHERE item.schedule_revision_id=lsr.id),0)<>
                  lt.original_principal_minor-COALESCE((
                    SELECT SUM(CASE le.polarity WHEN 1 THEN le.amount_minor ELSE -le.amount_minor END)
                    FROM loan_effect le WHERE le.loan_tranche_id=lt.id AND le.kind IN (1,5)
                  ),0)
            """.trimIndent(),
        )
        check(
            26,
            """
            SELECT COUNT(*) FROM installment_plan ip JOIN installment_schedule_revision isr ON isr.plan_id=ip.id
            WHERE isr.revision_no=(SELECT MAX(latest.revision_no) FROM installment_schedule_revision latest WHERE latest.plan_id=ip.id)
              AND COALESCE((SELECT SUM(item.principal_minor) FROM installment_schedule_item item WHERE item.schedule_revision_id=isr.id),0)<>ip.original_principal_minor
            """.trimIndent(),
        )
        structural(27) {
            val duplicateCount = singleInt(
                database,
                "SELECT COUNT(*) FROM (SELECT series_id,series_revision_id,occurrence_instant " +
                    "FROM recurrence_occurrence GROUP BY series_id,series_revision_id,occurrence_instant HAVING COUNT(*)<>1)",
            )
            duplicateCount + if (
                hasUniqueIndex(
                    database,
                    "recurrence_occurrence",
                    listOf("series_id", "series_revision_id", "occurrence_instant"),
                )
            ) {
                0
            } else {
                1
            }
        }
        check(
            28,
            "SELECT COUNT(*) FROM recurrence_candidate rc JOIN recurrence_occurrence ro ON ro.id=rc.occurrence_id " +
                "WHERE rc.status IN (0,2,3) AND (ro.transaction_id IS NOT NULL OR ro.status=2)",
        )
        structural(29) { if (safeRowCount(database, "PRAGMA foreign_key_check", queryFailures) == 0) 0 else 1 }
        check(30, purgeTombstonePrecedenceSql())
        check(
            31,
            if (tableExists(database, "projection_family_state")) {
                "SELECT COUNT(*) FROM projection_family_state pfs JOIN book b ON b.id=1 " +
                    "WHERE pfs.as_of_local_revision<>b.local_revision OR pfs.as_of_valuation_revision<>b.valuation_revision"
            } else {
                "SELECT 0"
            },
        )
        check(32, vaultIsolationSql())
        check(
            33,
            "SELECT COUNT(*) FROM book b WHERE " +
                "(b.head_commit_id IS NULL AND (b.local_revision<>0 OR EXISTS(SELECT 1 FROM book_commit))) OR " +
                "(b.head_commit_id IS NOT NULL AND NOT EXISTS(" +
                "SELECT 1 FROM book_commit head WHERE head.id=b.head_commit_id AND head.local_revision=b.local_revision)) " +
                "OR EXISTS(SELECT 1 FROM book_commit future WHERE future.local_revision>b.local_revision)",
        )
        structural(34) { monetaryOverflowViolationCount(database) }
        check(
            35,
            if (tableExists(database, "projection_family_state")) {
                "SELECT CASE WHEN COUNT(*)=15 AND MIN(as_of_local_revision)=(SELECT local_revision FROM book WHERE id=1) " +
                    "AND MAX(as_of_local_revision)=(SELECT local_revision FROM book WHERE id=1) " +
                    "AND MIN(as_of_valuation_revision)=(SELECT valuation_revision FROM book WHERE id=1) " +
                    "AND MAX(as_of_valuation_revision)=(SELECT valuation_revision FROM book WHERE id=1) THEN 0 ELSE 1 END " +
                    "FROM projection_family_state"
            } else {
                "SELECT 0"
            },
        )
        kotlin.check(result.keys == permanentInvariantIds) { "permanent invariant audit inventory is incomplete" }
        return result.toMap()
    }

    private fun missingImmutableTriggerCount(database: SupportSQLiteDatabase, tables: Set<String>): Int = tables.sumOf { table ->
        val installed = singleInt(
            database,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('${table}_reject_update','${table}_reject_delete')",
        )
        2 - installed
    }

    private fun missingColumns(database: SupportSQLiteDatabase, required: Map<String, Set<String>>): Int = required.entries.sumOf { (table, columns) ->
        val actual = database.query("PRAGMA table_info($table)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        (columns - actual).size
    }

    private fun hasUniqueIndex(
        database: SupportSQLiteDatabase,
        table: String,
        expectedColumns: List<String>,
    ): Boolean = database.query("PRAGMA index_list($table)").use indexList@{ indexes ->
        val nameColumn = indexes.getColumnIndexOrThrow("name")
        val uniqueColumn = indexes.getColumnIndexOrThrow("unique")
        while (indexes.moveToNext()) {
            if (indexes.getInt(uniqueColumn) != 1) continue
            val name = indexes.getString(nameColumn)
            val columns = database.query("PRAGMA index_info('$name')").use { details ->
                val sequenceColumn = details.getColumnIndexOrThrow("seqno")
                val columnName = details.getColumnIndexOrThrow("name")
                buildList {
                    while (details.moveToNext()) add(details.getInt(sequenceColumn) to details.getString(columnName))
                }.sortedBy { it.first }.map { it.second }
            }
            if (columns == expectedColumns) return@indexList true
        }
        false
    }

    private fun purgeTombstonePrecedenceSql(): String = """
        SELECT COUNT(*) FROM purge_tombstone pt WHERE
          (pt.entity_type=1 AND EXISTS(SELECT 1 FROM user_account e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=2 AND EXISTS(SELECT 1 FROM payment_card e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=3 AND EXISTS(SELECT 1 FROM category e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=4 AND EXISTS(SELECT 1 FROM merchant e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=5 AND EXISTS(SELECT 1 FROM place e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=6 AND EXISTS(SELECT 1 FROM business_transaction e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=7 AND EXISTS(SELECT 1 FROM project e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=8 AND EXISTS(SELECT 1 FROM goal e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=10 AND EXISTS(SELECT 1 FROM credit_statement e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=11 AND EXISTS(SELECT 1 FROM installment_plan e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=12 AND EXISTS(SELECT 1 FROM loan_contract e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=13 AND EXISTS(SELECT 1 FROM participant e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=14 AND EXISTS(SELECT 1 FROM settlement_activity e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=15 AND EXISTS(SELECT 1 FROM transaction_blueprint e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=16 AND EXISTS(SELECT 1 FROM recurrence_series e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=17 AND EXISTS(SELECT 1 FROM attachment e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=18 AND EXISTS(SELECT 1 FROM encrypted_blob e WHERE e.uid=pt.entity_uid)) OR
          (pt.entity_type=19 AND EXISTS(SELECT 1 FROM location_record e WHERE e.uid=pt.entity_uid))
    """.trimIndent()

    private fun vaultIsolationSql(): String = """
        SELECT COUNT(*) FROM card_vault_secret secret WHERE
          EXISTS(
            SELECT 1 FROM entity_revision er WHERE
              (secret.holder_name_ciphertext IS NOT NULL AND instr(er.canonical_snapshot_blob,secret.holder_name_ciphertext)>0) OR
              (secret.pan_ciphertext IS NOT NULL AND instr(er.canonical_snapshot_blob,secret.pan_ciphertext)>0) OR
              (secret.expiry_ciphertext IS NOT NULL AND instr(er.canonical_snapshot_blob,secret.expiry_ciphertext)>0) OR
              (secret.security_code_ciphertext IS NOT NULL AND instr(er.canonical_snapshot_blob,secret.security_code_ciphertext)>0) OR
              (secret.custom_fields_ciphertext IS NOT NULL AND instr(er.canonical_snapshot_blob,secret.custom_fields_ciphertext)>0)
          ) OR EXISTS(
            SELECT 1 FROM transaction_fts fts WHERE
              (secret.holder_name_ciphertext IS NOT NULL AND instr(CAST(COALESCE(fts.note,'') AS BLOB),secret.holder_name_ciphertext)>0) OR
              (secret.pan_ciphertext IS NOT NULL AND instr(CAST(COALESCE(fts.note,'') AS BLOB),secret.pan_ciphertext)>0) OR
              (secret.security_code_ciphertext IS NOT NULL AND instr(CAST(COALESCE(fts.note,'') AS BLOB),secret.security_code_ciphertext)>0)
          )
    """.trimIndent()

    private fun monetaryOverflowViolationCount(database: SupportSQLiteDatabase): Int {
        val total = database.query(MONETARY_VALUES_SQL).use { cursor ->
            var wide = BigInteger.ZERO
            while (cursor.moveToNext()) wide = wide.add(BigInteger.valueOf(cursor.getLong(0)).abs())
            wide
        }
        return if (total > BigInteger.valueOf(Long.MAX_VALUE)) 1 else 0
    }

    private fun tableExists(database: SupportSQLiteDatabase, table: String): Boolean = singleInt(
        database,
        "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table')",
    ) == 1

    private fun invariantId(number: Int): String = "INV-${number.toString().padStart(3, '0')}"

    private fun querySucceeds(database: SupportSQLiteDatabase, sql: String): Boolean = runCatching { database.query(sql).use { it.moveToFirst() } }.isSuccess

    private fun safeRowCount(database: SupportSQLiteDatabase, sql: String, failures: IntArray): Int = runCatching {
        rowCount(database, sql)
    }.getOrElse {
        failures[0] = Math.addExact(failures[0], 1)
        1
    }

    private fun safeCount(database: SupportSQLiteDatabase, sql: String, failures: IntArray): Int = runCatching {
        singleInt(database, sql)
    }.getOrElse {
        failures[0] = Math.addExact(failures[0], 1)
        1
    }

    private fun safeString(database: SupportSQLiteDatabase, sql: String, failures: IntArray): String = runCatching {
        singleString(database, sql)
    }.getOrElse {
        failures[0] = Math.addExact(failures[0], 1)
        "audit query failed"
    }

    private fun rowCount(database: SupportSQLiteDatabase, sql: String): Int = database.query(sql).use { cursor ->
        var count = 0
        while (cursor.moveToNext()) count = Math.addExact(count, 1)
        count
    }

    private fun singleInt(database: SupportSQLiteDatabase, sql: String): Int = database.query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "query returned no row" }
        Math.toIntExact(cursor.getLong(0))
    }

    private fun singleString(database: SupportSQLiteDatabase, sql: String): String = database.query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "query returned no row" }
        cursor.getString(0)
    }

    private val HISTORICAL_FACT_TABLES = LedgerSchemaDefinition.immutableTables.filterTo(linkedSetOf()) {
        it !in setOf("backup_snapshot", "backup_object", "backup_snapshot_object")
    }

    private const val PERMANENT_INVARIANT_COUNT = 35

    private val MONETARY_VALUES_SQL = """
        SELECT amount_minor FROM revision_amount
        UNION ALL SELECT account_amount_minor FROM posting
        UNION ALL SELECT base_amount_minor FROM posting
        UNION ALL SELECT base_amount_minor FROM economic_effect
        UNION ALL SELECT base_amount_minor FROM budget_effect
        UNION ALL SELECT base_amount_minor FROM project_effect
        UNION ALL SELECT amount_minor FROM goal_effect
        UNION ALL SELECT amount_minor FROM statement_effect
        UNION ALL SELECT amount_minor FROM loan_effect
        UNION ALL SELECT base_amount_minor FROM loan_effect
        UNION ALL SELECT signed_delta_minor FROM settlement_effect
        UNION ALL SELECT amount_base_minor FROM budget_adjustment
        UNION ALL SELECT principal_minor FROM loan_schedule_item
        UNION ALL SELECT interest_minor FROM loan_schedule_item
        UNION ALL SELECT fee_minor FROM loan_schedule_item
        UNION ALL SELECT principal_minor FROM installment_schedule_item
        UNION ALL SELECT interest_minor FROM installment_schedule_item
        UNION ALL SELECT fee_minor FROM installment_schedule_item
    """.trimIndent()
}
