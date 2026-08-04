@file:Suppress("TooManyFunctions")

package app.ledger.finance.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.application.ProjectionFamily
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

internal class RoomProjectionEngine {
    fun rebuildAll(
        database: SupportSQLiteDatabase,
        localRevision: Long,
        valuationRevision: Long,
        asOfLocalDate: Int = authoritativeProjectionDate(database),
    ) {
        clearDerivedState(database)
        rebuildCurrentTransactions(database, localRevision)
        rebuildAccountBalances(database, localRevision)
        rebuildRefundDependencies(database)
        rebuildRefunds(database, localRevision)
        rebuildBudget(database, localRevision)
        rebuildProjects(database, localRevision)
        rebuildGoals(database, localRevision)
        rebuildCredit(database, localRevision, asOfLocalDate)
        rebuildInstallments(database, localRevision, asOfLocalDate)
        rebuildLoans(database, localRevision, asOfLocalDate)
        rebuildSettlement(database, localRevision)
        rebuildSearch(database)
        rebuildGeography(database)
        rebuildWidgets(database, localRevision, valuationRevision)
    }

    fun mismatchedFamilies(
        database: SupportSQLiteDatabase,
        localRevision: Long,
        valuationRevision: Long,
    ): Set<ProjectionFamily> = buildSet {
        VERSIONED_FAMILIES.forEach { (family, tables) ->
            if (tables.any { table -> count(database, "SELECT COUNT(*) FROM $table WHERE as_of_local_revision <> ?", localRevision) > 0 }) {
                add(family)
            }
        }
        ROW_COUNT_EXPECTATIONS.forEach { (family, queries) ->
            if (count(database, queries.first) != count(database, queries.second)) add(family)
        }
        if (
            count(
                database,
                "SELECT COUNT(*) FROM account_valuation_current WHERE as_of_valuation_revision <> ?",
                valuationRevision,
            ) > 0 ||
            count(
                database,
                "SELECT COUNT(*) FROM widget_book_snapshot WHERE as_of_valuation_revision <> ?",
                valuationRevision,
            ) > 0
        ) {
            add(ProjectionFamily.ACCOUNT_BALANCE)
            add(ProjectionFamily.WIDGET)
        }
        val activeTransactions = count(database, "SELECT COUNT(*) FROM business_transaction WHERE lifecycle_state = 0")
        if (count(database, "SELECT COUNT(*) FROM transaction_fts") != activeTransactions) add(ProjectionFamily.SEARCH)
        if (
            count(database, "SELECT COUNT(*) FROM location_rtree") != count(database, "SELECT COUNT(*) FROM location_record") ||
            count(database, "SELECT COUNT(*) FROM place_rtree") != count(database, "SELECT COUNT(*) FROM place")
        ) {
            add(ProjectionFamily.GEOGRAPHY)
        }
    }

    fun canonicalHash(database: SupportSQLiteDatabase): String {
        val digest = MessageDigest.getInstance("SHA-256")
        HASH_QUERIES.forEach { (table, query) -> digestTable(database, digest, table, query) }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun digestTable(
        database: SupportSQLiteDatabase,
        digest: MessageDigest,
        table: String,
        query: String,
    ) {
        digest.update(table.toByteArray(Charsets.UTF_8))
        database.query(query).use { cursor ->
            val row = ByteArrayOutputStream()
            val output = DataOutputStream(row)
            while (cursor.moveToNext()) {
                writeRow(output, cursor)
            }
            output.flush()
            digest.update(row.toByteArray())
        }
    }

    private fun writeRow(output: DataOutputStream, cursor: Cursor) {
        output.writeInt(cursor.columnCount)
        for (column in 0 until cursor.columnCount) writeColumn(output, cursor, column)
    }

    private fun clearDerivedState(database: SupportSQLiteDatabase) {
        DERIVED_TABLES.forEach { table -> database.execSQL("DELETE FROM $table") }
    }

    private fun rebuildCurrentTransactions(database: SupportSQLiteDatabase, revision: Long) {
        database.execSQL(
            """
            INSERT INTO current_transaction_projection(
              transaction_id, transaction_uid, kind, state, current_revision_id, occurred_at, local_date,
              primary_account_id, secondary_account_id, card_id, category_id, merchant_id, project_id, goal_id,
              settlement_activity_id, payer_participant_id, input_amount_minor, input_currency,
              account_amount_minor, account_currency, economic_base_minor, note_preview, has_attachment,
              has_location, is_refund, is_refunded, has_installment, source_type, as_of_local_revision
            )
            SELECT bt.id, bt.uid, bt.kind, bt.lifecycle_state, tr.id, tr.occurred_at, tr.local_date,
              COALESCE(ed.payer_account_id, id.receiving_account_id, td.from_account_id, rd.receiving_account_id,
                cpd.payment_account_id, ldd.receiving_account_id, lpd.payment_account_id, bad.account_id,
                fxd.from_account_id, spd.local_account_id, obd.account_id),
              COALESCE(td.to_account_id, cpd.credit_account_id, fxd.to_account_id),
              COALESCE(ed.payer_card_id, td.source_card_id, rd.receiving_card_id), tr.category_id,
              COALESCE(
                (
                  WITH RECURSIVE merchant_chain(id, merged_into_id) AS (
                    SELECT id, merged_into_id FROM merchant WHERE id = tr.merchant_id
                    UNION ALL
                    SELECT next.id, next.merged_into_id FROM merchant next
                      JOIN merchant_chain current ON next.id = current.merged_into_id
                  )
                  SELECT id FROM merchant_chain WHERE merged_into_id IS NULL LIMIT 1
                ),
                tr.merchant_id
              ),
              tr.project_id, tr.goal_id, COALESCE(ed.settlement_activity_id, spd.activity_id),
              COALESCE(ed.payer_participant_id, spd.payer_participant_id),
              COALESCE(
                (SELECT amount_minor FROM revision_amount ra WHERE ra.revision_id = tr.id AND ra.representation = 0 ORDER BY component_index, role LIMIT 1),
                (SELECT amount_minor FROM revision_amount ra WHERE ra.revision_id = tr.id AND ra.representation IN (3,4) ORDER BY component_index, role LIMIT 1)
              ),
              COALESCE(
                (SELECT currency_code FROM revision_amount ra WHERE ra.revision_id = tr.id AND ra.representation = 0 ORDER BY component_index, role LIMIT 1),
                (SELECT currency_code FROM revision_amount ra WHERE ra.revision_id = tr.id AND ra.representation IN (3,4) ORDER BY component_index, role LIMIT 1)
              ),
              COALESCE(
                (SELECT amount_minor FROM revision_amount ra WHERE ra.revision_id = tr.id AND ra.representation = 1 ORDER BY component_index, role LIMIT 1),
                (SELECT amount_minor FROM revision_amount ra WHERE ra.revision_id = tr.id AND ra.representation IN (0,3,4) ORDER BY component_index, role LIMIT 1)
              ),
              COALESCE(
                (SELECT currency_code FROM revision_amount ra WHERE ra.revision_id = tr.id AND ra.representation = 1 ORDER BY component_index, role LIMIT 1),
                (SELECT currency_code FROM revision_amount ra WHERE ra.revision_id = tr.id AND ra.representation IN (0,3,4) ORDER BY component_index, role LIMIT 1)
              ),
              (SELECT SUM(CASE polarity WHEN 1 THEN base_amount_minor ELSE -base_amount_minor END)
                 FROM economic_effect ee WHERE ee.source_revision_id = tr.id),
              CASE WHEN tr.note IS NULL THEN NULL ELSE substr(tr.note, 1, 160) END,
              EXISTS(SELECT 1 FROM transaction_revision_attachment tra WHERE tra.revision_id = tr.id),
              CASE WHEN tr.location_record_id IS NULL THEN 0 ELSE 1 END,
              CASE WHEN bt.kind = 3 THEN 1 ELSE 0 END,
              EXISTS(
                SELECT 1 FROM refund_allocation rfa WHERE rfa.original_transaction_id = bt.id
                GROUP BY rfa.original_transaction_id
                HAVING SUM(CASE WHEN rfa.reversal_of_id IS NULL THEN rfa.original_currency_amount_minor ELSE -rfa.original_currency_amount_minor END) > 0
              ),
              CASE WHEN ed.installment_plan_id IS NULL THEN 0 ELSE 1 END,
              tr.source_type, ?
            FROM business_transaction bt
            JOIN transaction_revision tr ON tr.id = bt.current_revision_id
            LEFT JOIN expense_revision_detail ed ON ed.revision_id = tr.id
            LEFT JOIN income_revision_detail id ON id.revision_id = tr.id
            LEFT JOIN transfer_revision_detail td ON td.revision_id = tr.id
            LEFT JOIN refund_revision_detail rd ON rd.revision_id = tr.id
            LEFT JOIN credit_payment_revision_detail cpd ON cpd.revision_id = tr.id
            LEFT JOIN loan_disbursement_revision_detail ldd ON ldd.revision_id = tr.id
            LEFT JOIN loan_payment_revision_detail lpd ON lpd.revision_id = tr.id
            LEFT JOIN balance_adjustment_revision_detail bad ON bad.revision_id = tr.id
            LEFT JOIN fx_exchange_revision_detail fxd ON fxd.revision_id = tr.id
            LEFT JOIN settlement_payment_revision_detail spd ON spd.revision_id = tr.id
            LEFT JOIN opening_balance_revision_detail obd ON obd.revision_id = tr.id
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
    }

    private fun rebuildAccountBalances(database: SupportSQLiteDatabase, revision: Long) {
        database.execSQL(
            """
            INSERT INTO account_balance_current(account_id, normal_balance_minor, currency_code, total_debit_minor, total_credit_minor, as_of_local_revision)
            SELECT ua.id,
              CASE la.normal_side WHEN 0 THEN COALESCE(SUM(CASE p.side WHEN 0 THEN p.account_amount_minor ELSE -p.account_amount_minor END), 0)
                                  ELSE COALESCE(SUM(CASE p.side WHEN 1 THEN p.account_amount_minor ELSE -p.account_amount_minor END), 0) END,
              ua.currency_code,
              COALESCE(SUM(CASE p.side WHEN 0 THEN p.account_amount_minor ELSE 0 END), 0),
              COALESCE(SUM(CASE p.side WHEN 1 THEN p.account_amount_minor ELSE 0 END), 0), ?
            FROM user_account ua JOIN ledger_account la ON la.id = ua.ledger_account_id
            LEFT JOIN posting p ON p.ledger_account_id = la.id
            GROUP BY ua.id, la.normal_side, ua.currency_code
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
        database.execSQL(
            """
            INSERT INTO account_balance_daily(account_id, local_date, opening_minor, inflow_minor, outflow_minor, closing_minor, currency_code, as_of_local_revision)
            WITH daily AS (
              SELECT ua.id AS account_id, je.local_date, ua.currency_code,
                SUM(CASE WHEN p.side = la.normal_side THEN p.account_amount_minor ELSE 0 END) AS inflow,
                SUM(CASE WHEN p.side <> la.normal_side THEN p.account_amount_minor ELSE 0 END) AS outflow,
                SUM(CASE WHEN p.side = la.normal_side THEN p.account_amount_minor ELSE -p.account_amount_minor END) AS net
              FROM user_account ua JOIN ledger_account la ON la.id = ua.ledger_account_id
              JOIN posting p ON p.ledger_account_id = la.id JOIN journal_entry je ON je.id = p.journal_entry_id
              GROUP BY ua.id, je.local_date, ua.currency_code
            ), running AS (
              SELECT account_id, local_date, currency_code, inflow, outflow,
                COALESCE(SUM(net) OVER (PARTITION BY account_id ORDER BY local_date ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS opening,
                SUM(net) OVER (PARTITION BY account_id ORDER BY local_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS closing
              FROM daily
            )
            SELECT account_id, local_date, opening, inflow, outflow, closing, currency_code, ? FROM running
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
    }

    private fun rebuildRefunds(database: SupportSQLiteDatabase, revision: Long) {
        database.execSQL(
            """
            INSERT INTO refund_status_projection(original_transaction_id, gross_refundable_minor, refunded_minor, remaining_minor, currency_code, as_of_local_revision)
            WITH refunds AS (
              SELECT original_transaction_id, SUM(CASE WHEN reversal_of_id IS NULL THEN original_currency_amount_minor ELSE -original_currency_amount_minor END) AS refunded
              FROM refund_allocation GROUP BY original_transaction_id
            ), gross AS (
              SELECT bt.id AS transaction_id, ra.amount_minor, ra.currency_code
              FROM business_transaction bt JOIN revision_amount ra ON ra.revision_id = bt.current_revision_id
              WHERE bt.kind = 0 AND bt.lifecycle_state = 0 AND ra.representation = 0 AND ra.component_index = 0
                AND ra.role IN (0,9) AND NOT EXISTS (
                  SELECT 1 FROM revision_amount earlier WHERE earlier.revision_id = ra.revision_id AND earlier.representation = 0
                    AND (earlier.component_index < ra.component_index OR (earlier.component_index = ra.component_index AND earlier.role < ra.role))
                )
            )
            SELECT gross.transaction_id, gross.amount_minor,
              CASE WHEN COALESCE(refunds.refunded,0) > gross.amount_minor THEN gross.amount_minor ELSE COALESCE(refunds.refunded,0) END,
              CASE WHEN COALESCE(refunds.refunded,0) > gross.amount_minor THEN 0 ELSE gross.amount_minor - COALESCE(refunds.refunded,0) END,
              gross.currency_code, ?
            FROM gross LEFT JOIN refunds ON gross.transaction_id = refunds.original_transaction_id
            WHERE COALESCE(refunds.refunded,0) >= 0
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
    }

    private fun rebuildRefundDependencies(database: SupportSQLiteDatabase) {
        database.execSQL("DELETE FROM transaction_dependency WHERE dependency_type=0")
        database.execSQL(
            """
            INSERT INTO transaction_dependency(parent_transaction_id, child_transaction_id, dependency_type)
            SELECT ra.original_transaction_id, ra.refund_transaction_id, 0
            FROM refund_allocation ra
            JOIN business_transaction refund ON refund.id=ra.refund_transaction_id AND refund.lifecycle_state=0
            GROUP BY ra.original_transaction_id,ra.refund_transaction_id
            HAVING SUM(CASE WHEN ra.reversal_of_id IS NULL THEN ra.original_currency_amount_minor ELSE -ra.original_currency_amount_minor END) > 0
            """.trimIndent(),
        )
    }

    private fun rebuildBudget(database: SupportSQLiteDatabase, revision: Long) {
        database.execSQL(
            "UPDATE budget_future_reservation SET as_of_local_revision=?",
            arrayOf<Any>(revision),
        )
        val configured = database.queryList(
            "SELECT bm.year_month,bmr.base_total_minor,bm.current_revision_id FROM budget_month bm " +
                "JOIN budget_month_revision bmr ON bmr.id=bm.current_revision_id ORDER BY bm.year_month",
        ) { cursor -> BudgetConfigured(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2)) }
        val limits = configured.associate { item ->
            item.month to database.queryList(
                "SELECT category_id,amount_base_minor FROM budget_category_limit WHERE budget_month_revision_id=?",
                arrayOf(item.revisionId),
            ) { cursor -> cursor.getLong(0) to cursor.getLong(1) }.toMap()
        }
        val usages = database.queryList(
            "SELECT target_year_month,category_id,SUM(CASE kind WHEN 0 THEN polarity*base_amount_minor ELSE -polarity*base_amount_minor END) " +
                "FROM budget_effect GROUP BY target_year_month,category_id",
        ) { cursor -> BudgetAmount(cursor.getInt(0), if (cursor.isNull(1)) null else cursor.getLong(1), cursor.getLong(2)) }
            .groupBy(BudgetAmount::month)
        val adjustments = database.queryList(
            "SELECT year_month,category_id,SUM(amount_base_minor) FROM budget_adjustment GROUP BY year_month,category_id",
        ) { cursor -> BudgetAmount(cursor.getInt(0), if (cursor.isNull(1)) null else cursor.getLong(1), cursor.getLong(2)) }
            .groupBy(BudgetAmount::month)
        val relevant = (configured.map(BudgetConfigured::month) + usages.keys + adjustments.keys).distinct().sorted()
        if (relevant.isEmpty()) return
        val categoryParents = database.queryList("SELECT id,parent_id FROM category") { cursor ->
            cursor.getLong(0) to if (cursor.isNull(1)) null else cursor.getLong(1)
        }.toMap()
        var month = relevant.first().toProjectionMonth()
        val last = relevant.last().toProjectionMonth()
        val carried = mutableMapOf<Long?, Long>()
        while (month <= last) {
            val monthKey = month.toProjectionInt()
            val configuredMonth = configured.singleOrNull { it.month == monthKey }
            val monthLimits = limits[monthKey].orEmpty()
            val directUsage = usages[monthKey].orEmpty().associate { it.categoryId to it.amount }
            val monthAdjustments = adjustments[monthKey].orEmpty().associate { it.categoryId to it.amount }
            val totalUsed = exactSum(directUsage.values)
            val total = BudgetProjectionRow(
                categoryId = null,
                base = configuredMonth?.total ?: 0L,
                rollover = carried[null] ?: 0L,
                adjustment = monthAdjustments[null] ?: 0L,
                used = totalUsed,
            ).withRemaining()
            val displayCategoryIds = (monthLimits.keys + directUsage.keys.filterNotNull() + monthAdjustments.keys.filterNotNull()).toSet()
            val categories = displayCategoryIds.map { categoryId ->
                val children = categoryParents.filterValues { it == categoryId }.keys
                val used = if (children.isEmpty()) {
                    directUsage[categoryId] ?: 0L
                } else {
                    exactSum(directUsage.filterKeys { it == categoryId || it in children }.values)
                }
                BudgetProjectionRow(
                    categoryId,
                    monthLimits[categoryId] ?: 0L,
                    if (categoryId in monthLimits) carried[categoryId] ?: 0L else 0L,
                    monthAdjustments[categoryId] ?: 0L,
                    used,
                ).withRemaining()
            }
            val rows = listOf(total) + categories
            rows.forEach { row ->
                database.execSQL(
                    "INSERT INTO budget_usage_projection(" +
                        "year_month,category_id,base_budget_minor,rollover_minor," +
                        "adjustment_minor,used_minor,remaining_minor,as_of_local_revision" +
                        ") " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(monthKey, row.categoryId, row.base, row.rollover, row.adjustment, row.used, row.remaining, revision),
                )
            }
            val next = month.plusMonths(1)
            if (month < last) {
                rows.filter { it.categoryId == null || it.categoryId in monthLimits }.forEach { row ->
                    database.execSQL(
                        "INSERT INTO budget_rollover(from_year_month,to_year_month,scope,category_id,amount_base_minor,as_of_local_revision) VALUES (?,?,?,?,?,?)",
                        arrayOf<Any?>(monthKey, next.toProjectionInt(), if (row.categoryId == null) 0 else 1, row.categoryId, row.remaining, revision),
                    )
                }
            }
            carried.clear()
            carried[null] = total.remaining
            categories.filter { it.categoryId in monthLimits }.forEach { carried[it.categoryId] = it.remaining }
            month = next
        }
    }

    private fun rebuildProjects(database: SupportSQLiteDatabase, revision: Long) {
        database.execSQL(
            """
            INSERT INTO project_usage_projection(project_id, budget_base_minor, used_base_minor, restored_base_minor, remaining_base_minor, cash_inflow_base_minor, cash_outflow_base_minor, as_of_local_revision)
            WITH usage AS (
              SELECT project_id,
                COALESCE(SUM(CASE WHEN kind = 0 THEN CASE polarity WHEN 1 THEN base_amount_minor ELSE -base_amount_minor END ELSE 0 END), 0) AS used,
                COALESCE(SUM(CASE WHEN kind = 1 THEN CASE polarity WHEN 1 THEN base_amount_minor ELSE -base_amount_minor END ELSE 0 END), 0) AS restored
              FROM project_effect GROUP BY project_id
            ), cash AS (
              SELECT project_id,
                COALESCE(SUM(CASE WHEN nature IN (0,2) THEN CASE polarity WHEN 1 THEN base_amount_minor ELSE -base_amount_minor END ELSE 0 END), 0) AS inflow,
                COALESCE(SUM(CASE WHEN nature = 1 THEN CASE polarity WHEN 1 THEN base_amount_minor ELSE -base_amount_minor END ELSE 0 END), 0) AS outflow
              FROM economic_effect WHERE project_id IS NOT NULL GROUP BY project_id
            )
            SELECT pr.id, pr.budget_base_minor, COALESCE(usage.used, 0), COALESCE(usage.restored, 0),
              CASE WHEN pr.budget_base_minor IS NULL THEN NULL ELSE pr.budget_base_minor -
                COALESCE(usage.used, 0) + COALESCE(usage.restored, 0) END,
              COALESCE(cash.inflow, 0), COALESCE(cash.outflow, 0), ?
            FROM project pr LEFT JOIN usage ON usage.project_id = pr.id LEFT JOIN cash ON cash.project_id = pr.id
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
    }

    private fun rebuildGoals(database: SupportSQLiteDatabase, revision: Long) {
        database.execSQL(
            """
            INSERT INTO goal_balance_projection(goal_id, balance_minor, target_minor, currency_code, as_of_local_revision)
            SELECT g.id,
              COALESCE(SUM(CASE ge.kind WHEN 0 THEN CASE ge.polarity WHEN 1 THEN ge.amount_minor ELSE -ge.amount_minor END
                                        WHEN 1 THEN CASE ge.polarity WHEN 1 THEN -ge.amount_minor ELSE ge.amount_minor END
                                        WHEN 2 THEN CASE ge.polarity WHEN 1 THEN -ge.amount_minor ELSE ge.amount_minor END
                                        WHEN 3 THEN CASE ge.polarity WHEN 1 THEN ge.amount_minor ELSE -ge.amount_minor END
                                        ELSE CASE ge.polarity WHEN 1 THEN ge.amount_minor ELSE -ge.amount_minor END END), 0),
              g.target_amount_minor, ua.currency_code, ?
            FROM goal g JOIN user_account ua ON ua.id = g.account_id LEFT JOIN goal_effect ge ON ge.goal_id = g.id
            GROUP BY g.id, g.target_amount_minor, ua.currency_code
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
    }

    private fun rebuildCredit(database: SupportSQLiteDatabase, revision: Long, asOfLocalDate: Int) {
        database.execSQL(
            """
            INSERT INTO credit_statement_projection(statement_id, estimated_amount_minor, official_amount_minor, paid_amount_minor, remaining_amount_minor, status, as_of_local_revision)
            SELECT cs.id,
              MAX(0, COALESCE(SUM(CASE se.kind WHEN 0 THEN CASE se.polarity WHEN 1 THEN se.amount_minor ELSE -se.amount_minor END
                                        WHEN 1 THEN CASE se.polarity WHEN 1 THEN -se.amount_minor ELSE se.amount_minor END
                                        WHEN 4 THEN CASE se.polarity WHEN 1 THEN se.amount_minor ELSE -se.amount_minor END ELSE 0 END), 0)),
              csr.official_amount_minor,
              COALESCE(SUM(CASE WHEN se.kind = 2 THEN CASE se.polarity WHEN 1 THEN se.amount_minor ELSE -se.amount_minor END ELSE 0 END), 0),
              COALESCE(csr.official_amount_minor,
                SUM(CASE se.kind WHEN 0 THEN CASE se.polarity WHEN 1 THEN se.amount_minor ELSE -se.amount_minor END
                                 WHEN 1 THEN CASE se.polarity WHEN 1 THEN -se.amount_minor ELSE se.amount_minor END
                                 WHEN 4 THEN CASE se.polarity WHEN 1 THEN se.amount_minor ELSE -se.amount_minor END ELSE 0 END), 0) -
                COALESCE(SUM(CASE WHEN se.kind = 2 THEN CASE se.polarity WHEN 1 THEN se.amount_minor ELSE -se.amount_minor END ELSE 0 END), 0),
              cs.status, ?
            FROM credit_statement cs LEFT JOIN credit_statement_revision csr ON csr.id = cs.current_revision_id
              LEFT JOIN statement_effect se ON se.statement_id = cs.id
            GROUP BY cs.id, csr.official_amount_minor, cs.status
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
        database.execSQL(
            """
            INSERT INTO credit_account_projection(account_id, debt_minor, available_limit_minor, estimated_unbilled_minor, overdue_minor, currency_code, as_of_local_revision)
            SELECT ua.id, MAX(0, COALESCE(abc.normal_balance_minor, 0)),
              CASE WHEN cap.standard_limit_minor IS NULL THEN NULL ELSE cap.standard_limit_minor + COALESCE(cap.temporary_limit_minor, 0) - MAX(0, COALESCE(abc.normal_balance_minor, 0)) END,
              MAX(0, COALESCE((
                SELECT SUM(CASE se.kind WHEN 1 THEN CASE se.polarity WHEN 1 THEN -se.amount_minor ELSE se.amount_minor END
                                             WHEN 2 THEN CASE se.polarity WHEN 1 THEN -se.amount_minor ELSE se.amount_minor END
                                             ELSE CASE se.polarity WHEN 1 THEN se.amount_minor ELSE -se.amount_minor END END)
                FROM statement_effect se WHERE se.credit_account_id = ua.id AND se.statement_id IS NULL
              ), 0)),
              COALESCE((
                SELECT SUM(MAX(0, csp.remaining_amount_minor)) FROM credit_statement cs
                JOIN credit_statement_projection csp ON csp.statement_id = cs.id
                WHERE cs.credit_account_id = ua.id AND cs.due_date < ? AND cs.status <> 3
              ), 0), ua.currency_code, ?
            FROM user_account ua JOIN credit_account_profile cap ON cap.account_id = ua.id
              LEFT JOIN account_balance_current abc ON abc.account_id = ua.id
            WHERE ua.type = 2
            """.trimIndent(),
            arrayOf<Any>(asOfLocalDate, revision),
        )
    }

    private fun rebuildInstallments(database: SupportSQLiteDatabase, revision: Long, asOfLocalDate: Int) {
        database.execSQL(
            """
            INSERT INTO installment_progress_projection(plan_id, principal_minor, posted_principal_minor, unposted_committed_principal_minor, fees_minor, next_statement_date, as_of_local_revision)
            SELECT ip.id, ip.original_principal_minor,
              COALESCE(SUM(CASE WHEN isi.statement_date <= ? THEN isi.principal_minor ELSE 0 END), 0),
              MAX(0, ip.original_principal_minor - COALESCE(SUM(CASE WHEN isi.statement_date <= ? THEN isi.principal_minor ELSE 0 END), 0)),
              COALESCE(SUM(isi.fee_minor), 0), MIN(CASE WHEN isi.statement_date > ? THEN isi.statement_date END), ?
            FROM installment_plan ip LEFT JOIN installment_schedule_revision isr ON isr.id = (
                SELECT latest.id FROM installment_schedule_revision latest
                WHERE latest.plan_id = ip.id ORDER BY latest.revision_no DESC LIMIT 1
              )
              LEFT JOIN installment_schedule_item isi ON isi.schedule_revision_id = isr.id
            GROUP BY ip.id, ip.original_principal_minor
            """.trimIndent(),
            arrayOf<Any>(asOfLocalDate, asOfLocalDate, asOfLocalDate, revision),
        )
    }

    private fun authoritativeProjectionDate(database: SupportSQLiteDatabase): Int {
        val evidence = database.queryOne(
            "SELECT bc.created_at, b.default_zone_id FROM book b " +
                "JOIN book_commit bc ON bc.id = b.head_commit_id WHERE b.id = 1",
        ) { cursor -> cursor.getLong(0) to cursor.getString(1) }
            ?: abort(app.ledger.finance.application.FinanceDataError.CorruptData)
        return Instant.ofEpochMilli(evidence.first)
            .atZone(ZoneId.of(evidence.second))
            .toLocalDate()
            .toStorageInt()
    }

    private fun rebuildLoans(database: SupportSQLiteDatabase, revision: Long, asOfLocalDate: Int) {
        database.execSQL(
            """
            INSERT INTO loan_progress_projection(contract_id, tranche_id, original_principal_minor, repaid_principal_minor, remaining_principal_minor, accrued_interest_minor, next_payment_date, as_of_local_revision)
            SELECT lt.contract_id, lt.id, lt.original_principal_minor,
              COALESCE(SUM(CASE WHEN le.kind IN (1,5) THEN CASE le.polarity WHEN 1 THEN le.amount_minor ELSE -le.amount_minor END ELSE 0 END), 0),
              MAX(0, lt.original_principal_minor - COALESCE(SUM(CASE WHEN le.kind IN (1,5) THEN CASE le.polarity WHEN 1 THEN le.amount_minor ELSE -le.amount_minor END ELSE 0 END), 0)),
              COALESCE(SUM(CASE WHEN le.kind = 2 THEN CASE le.polarity WHEN 1 THEN le.amount_minor ELSE -le.amount_minor END ELSE 0 END), 0),
              (SELECT MIN(lsi.planned_date) FROM loan_schedule_item lsi
                 JOIN loan_schedule_revision lsr ON lsr.id = lsi.schedule_revision_id
                 WHERE lsr.tranche_id = lt.id AND lsi.planned_date > ?
                   AND lsr.revision_no = (SELECT MAX(latest.revision_no) FROM loan_schedule_revision latest WHERE latest.tranche_id = lt.id)), ?
            FROM loan_tranche lt LEFT JOIN loan_effect le ON le.loan_tranche_id = lt.id
            GROUP BY lt.contract_id, lt.id, lt.original_principal_minor
            """.trimIndent(),
            arrayOf<Any>(asOfLocalDate, revision),
        )
    }

    private fun rebuildSettlement(database: SupportSQLiteDatabase, revision: Long) {
        database.execSQL(
            """
            INSERT INTO settlement_position_projection(activity_id, participant_id, paid_minor, owed_minor, settled_paid_minor, settled_received_minor, net_position_minor, as_of_local_revision)
            SELECT sap.activity_id, sap.participant_id,
              COALESCE(SUM(CASE WHEN se.kind = 0 AND se.signed_delta_minor > 0 THEN se.signed_delta_minor ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN se.kind = 1 AND se.signed_delta_minor < 0 THEN -se.signed_delta_minor ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN se.kind = 2 AND se.signed_delta_minor < 0 THEN -se.signed_delta_minor ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN se.kind = 3 AND se.signed_delta_minor > 0 THEN se.signed_delta_minor ELSE 0 END), 0),
              COALESCE(SUM(se.signed_delta_minor), 0), ?
            FROM settlement_activity_participant sap LEFT JOIN settlement_effect se
              ON se.activity_id = sap.activity_id AND se.participant_id = sap.participant_id
            GROUP BY sap.activity_id, sap.participant_id
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
    }

    private fun rebuildSearch(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO transaction_fts(transaction_id, category_name, merchant_name, merchant_aliases, note, project_name, settlement_activity_name, participant_names, attachment_names, lifecycle_state)
            SELECT ctp.transaction_id, c.name, m.name,
              (SELECT group_concat(ma.alias, ' ') FROM merchant_alias ma WHERE ma.merchant_id = ctp.merchant_id),
              tr.note, pr.name, sa.name,
              (SELECT group_concat(p.name, ' ') FROM transaction_revision_settlement_share trs JOIN participant p ON p.id = trs.participant_id WHERE trs.revision_id = tr.id),
              (SELECT group_concat(a.display_name, ' ') FROM transaction_revision_attachment tra JOIN attachment a ON a.id = tra.attachment_id WHERE tra.revision_id = tr.id),
              ctp.state
            FROM current_transaction_projection ctp JOIN transaction_revision tr ON tr.id = ctp.current_revision_id
              LEFT JOIN category c ON c.id = ctp.category_id LEFT JOIN merchant m ON m.id = ctp.merchant_id
              LEFT JOIN project pr ON pr.id = ctp.project_id LEFT JOIN settlement_activity sa ON sa.id = ctp.settlement_activity_id
            WHERE ctp.state = 0
            """.trimIndent(),
        )
    }

    private fun rebuildGeography(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO location_rtree(location_id, min_lat, max_lat, min_lon, max_lon) " +
                "SELECT id, lat_e7 / 10000000.0, lat_e7 / 10000000.0, lon_e7 / 10000000.0, lon_e7 / 10000000.0 FROM location_record",
        )
        database.execSQL(
            "INSERT INTO place_rtree(place_id, min_lat, max_lat, min_lon, max_lon) " +
                "SELECT id, center_lat_e7 / 10000000.0, center_lat_e7 / 10000000.0, center_lon_e7 / 10000000.0, center_lon_e7 / 10000000.0 FROM place",
        )
    }

    private fun rebuildWidgets(database: SupportSQLiteDatabase, revision: Long, valuationRevision: Long) {
        database.execSQL(
            "INSERT INTO widget_account_snapshot(account_id, balance_minor, currency_code, as_of_local_revision) " +
                "SELECT account_id, normal_balance_minor, currency_code, ? FROM account_balance_current",
            arrayOf<Any>(revision),
        )
        database.execSQL(
            "INSERT INTO widget_credit_snapshot(account_id, debt_minor, available_limit_minor, currency_code, as_of_local_revision) " +
                "SELECT account_id, debt_minor, available_limit_minor, currency_code, ? FROM credit_account_projection",
            arrayOf<Any>(revision),
        )
        database.execSQL(
            "INSERT INTO widget_goal_snapshot(goal_id, balance_minor, target_minor, currency_code, as_of_local_revision) " +
                "SELECT goal_id, balance_minor, target_minor, currency_code, ? FROM goal_balance_projection",
            arrayOf<Any>(revision),
        )
        database.execSQL(
            """
            INSERT INTO widget_book_snapshot(id, core_net_financial_assets_base_minor, adjusted_net_financial_position_base_minor, base_currency, as_of_local_revision, as_of_valuation_revision)
            WITH core AS (
              SELECT COALESCE(SUM(CASE WHEN ua.type IN (0,1) THEN
                    CASE WHEN abc.currency_code = b.base_currency THEN abc.normal_balance_minor ELSE COALESCE(avc.current_base_value_minor, 0) END
                  ELSE -CASE WHEN abc.currency_code = b.base_currency THEN ABS(abc.normal_balance_minor) ELSE ABS(COALESCE(avc.current_base_value_minor, 0)) END END), 0) AS value
              FROM book b JOIN user_account ua LEFT JOIN account_balance_current abc ON abc.account_id = ua.id
                LEFT JOIN account_valuation_current avc ON avc.account_id = ua.id
            ), settlement AS (
              SELECT COALESCE(SUM(spp.net_position_minor), 0) AS value FROM settlement_position_projection spp
                JOIN participant p ON p.id = spp.participant_id WHERE p.is_self = 1
            )
            SELECT 1, core.value, core.value + settlement.value, b.base_currency, ?, ? FROM book b, core, settlement WHERE b.id = 1
            """.trimIndent(),
            arrayOf<Any>(revision, valuationRevision),
        )
    }

    private fun count(database: SupportSQLiteDatabase, sql: String, vararg args: Any): Long = database.queryOne(sql, args) { it.getLong(0) } ?: 0L

    private fun writeColumn(output: DataOutputStream, cursor: Cursor, column: Int) {
        output.writeInt(cursor.getType(column))
        when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> Unit
            Cursor.FIELD_TYPE_INTEGER -> output.writeLong(cursor.getLong(column))
            Cursor.FIELD_TYPE_FLOAT -> output.writeLengthPrefixed(cursor.getString(column).toByteArray(Charsets.UTF_8))
            Cursor.FIELD_TYPE_STRING -> output.writeLengthPrefixed(cursor.getString(column).toByteArray(Charsets.UTF_8))
            Cursor.FIELD_TYPE_BLOB -> output.writeLengthPrefixed(cursor.getBlob(column))
        }
    }

    private fun DataOutputStream.writeLengthPrefixed(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private companion object {
        val DERIVED_TABLES = listOf(
            "transaction_fts", "location_rtree", "place_rtree", "widget_goal_snapshot", "widget_credit_snapshot",
            "widget_account_snapshot", "widget_book_snapshot", "settlement_position_projection", "loan_progress_projection",
            "installment_progress_projection", "credit_account_projection", "credit_statement_projection",
            "goal_balance_projection", "project_usage_projection", "budget_usage_projection", "refund_status_projection",
            "budget_rollover",
            "account_balance_daily", "account_balance_current", "current_transaction_projection",
        )
        val VERSIONED_FAMILIES = mapOf(
            ProjectionFamily.CURRENT_TRANSACTION to listOf("current_transaction_projection"),
            ProjectionFamily.ACCOUNT_BALANCE to listOf("account_balance_current"),
            ProjectionFamily.ACCOUNT_DAILY to listOf("account_balance_daily"),
            ProjectionFamily.REFUND to listOf("refund_status_projection"),
            ProjectionFamily.BUDGET to listOf(
                "budget_usage_projection",
                "budget_rollover",
                "budget_future_reservation",
            ),
            ProjectionFamily.PROJECT to listOf("project_usage_projection"),
            ProjectionFamily.GOAL to listOf("goal_balance_projection"),
            ProjectionFamily.CREDIT to listOf("credit_statement_projection", "credit_account_projection"),
            ProjectionFamily.INSTALLMENT to listOf("installment_progress_projection"),
            ProjectionFamily.LOAN to listOf("loan_progress_projection"),
            ProjectionFamily.SETTLEMENT to listOf("settlement_position_projection"),
            ProjectionFamily.WIDGET to listOf("widget_book_snapshot", "widget_account_snapshot", "widget_credit_snapshot", "widget_goal_snapshot"),
        )
        val ROW_COUNT_EXPECTATIONS = listOf(
            ProjectionFamily.CURRENT_TRANSACTION to Pair(
                "SELECT COUNT(*) FROM current_transaction_projection",
                "SELECT COUNT(*) FROM business_transaction",
            ),
            ProjectionFamily.ACCOUNT_BALANCE to Pair(
                "SELECT COUNT(*) FROM account_balance_current",
                "SELECT COUNT(*) FROM user_account",
            ),
            ProjectionFamily.PROJECT to Pair("SELECT COUNT(*) FROM project_usage_projection", "SELECT COUNT(*) FROM project"),
            ProjectionFamily.GOAL to Pair("SELECT COUNT(*) FROM goal_balance_projection", "SELECT COUNT(*) FROM goal"),
            ProjectionFamily.CREDIT to Pair(
                "SELECT COUNT(*) FROM credit_statement_projection",
                "SELECT COUNT(*) FROM credit_statement",
            ),
            ProjectionFamily.INSTALLMENT to Pair(
                "SELECT COUNT(*) FROM installment_progress_projection",
                "SELECT COUNT(*) FROM installment_plan",
            ),
            ProjectionFamily.LOAN to Pair("SELECT COUNT(*) FROM loan_progress_projection", "SELECT COUNT(*) FROM loan_tranche"),
            ProjectionFamily.SETTLEMENT to Pair(
                "SELECT COUNT(*) FROM settlement_position_projection",
                "SELECT COUNT(*) FROM settlement_activity_participant",
            ),
            ProjectionFamily.WIDGET to Pair("SELECT COUNT(*) FROM widget_book_snapshot", "SELECT 1"),
            ProjectionFamily.WIDGET to Pair(
                "SELECT COUNT(*) FROM widget_account_snapshot",
                "SELECT COUNT(*) FROM account_balance_current",
            ),
        )
        val HASH_QUERIES = listOf(
            "current_transaction_projection" to "SELECT * FROM current_transaction_projection ORDER BY transaction_id",
            "account_balance_current" to "SELECT * FROM account_balance_current ORDER BY account_id",
            "account_balance_daily" to "SELECT * FROM account_balance_daily ORDER BY account_id, local_date",
            "refund_status_projection" to "SELECT * FROM refund_status_projection ORDER BY original_transaction_id",
            "budget_usage_projection" to "SELECT * FROM budget_usage_projection ORDER BY year_month, category_id",
            "budget_rollover" to "SELECT * FROM budget_rollover ORDER BY from_year_month, to_year_month, scope, category_id",
            "budget_future_reservation" to
                "SELECT * FROM budget_future_reservation ORDER BY year_month, recurrence_series_id, occurrence_date",
            "project_usage_projection" to "SELECT * FROM project_usage_projection ORDER BY project_id",
            "goal_balance_projection" to "SELECT * FROM goal_balance_projection ORDER BY goal_id",
            "credit_statement_projection" to "SELECT * FROM credit_statement_projection ORDER BY statement_id",
            "credit_account_projection" to "SELECT * FROM credit_account_projection ORDER BY account_id",
            "installment_progress_projection" to "SELECT * FROM installment_progress_projection ORDER BY plan_id",
            "loan_progress_projection" to "SELECT * FROM loan_progress_projection ORDER BY contract_id, tranche_id",
            "settlement_position_projection" to "SELECT * FROM settlement_position_projection ORDER BY activity_id, participant_id",
            "transaction_fts" to "SELECT * FROM transaction_fts ORDER BY transaction_id",
            "location_rtree" to "SELECT * FROM location_rtree ORDER BY location_id",
            "place_rtree" to "SELECT * FROM place_rtree ORDER BY place_id",
            "widget_book_snapshot" to "SELECT * FROM widget_book_snapshot ORDER BY id",
            "widget_account_snapshot" to "SELECT * FROM widget_account_snapshot ORDER BY account_id",
            "widget_credit_snapshot" to "SELECT * FROM widget_credit_snapshot ORDER BY account_id",
            "widget_goal_snapshot" to "SELECT * FROM widget_goal_snapshot ORDER BY goal_id",
        )
    }
}

private data class BudgetConfigured(val month: Int, val total: Long, val revisionId: Long)
private data class BudgetAmount(val month: Int, val categoryId: Long?, val amount: Long)
private data class BudgetProjectionRow(
    val categoryId: Long?,
    val base: Long,
    val rollover: Long,
    val adjustment: Long,
    val used: Long,
    val remaining: Long = 0L,
) {
    fun withRemaining(): BudgetProjectionRow = copy(
        remaining = Math.subtractExact(Math.addExact(Math.addExact(base, rollover), adjustment), used),
    )
}

private fun exactSum(values: Collection<Long>): Long = values.fold(0L, Math::addExact)
private fun Int.toProjectionMonth(): java.time.YearMonth = java.time.YearMonth.of(this / PROJECTION_MONTH_RADIX, this % PROJECTION_MONTH_RADIX)

private fun java.time.YearMonth.toProjectionInt(): Int = year * PROJECTION_MONTH_RADIX + monthValue

private const val PROJECTION_MONTH_RADIX = 100
