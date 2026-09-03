@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth", "TooManyFunctions")

package app.ledger.core.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Schema-level deterministic maintenance for the twelve frozen analytics rollups. */
@Suppress("LargeClass", "LongParameterList")
object AnalyticsProjectionEngine {
    const val INCOME_METRIC: Int = 0
    const val EXPENSE_METRIC: Int = 1
    const val CONSUMPTION_METRIC: Int = 2
    const val NON_CONSUMPTION_EXPENSE_METRIC: Int = 3
    const val CONTRA_EXPENSE_METRIC: Int = 4
    const val NET_CASH_FLOW_METRIC: Int = 5
    const val LOAN_INTEREST_METRIC: Int = 19
    const val TRANSACTION_COUNT_METRIC: Int = 21

    val tables: List<String> = listOf(
        "analytics_daily_total",
        "analytics_daily_category",
        "analytics_daily_account",
        "analytics_daily_merchant",
        "analytics_daily_project",
        "analytics_daily_place",
        "analytics_monthly_total",
        "analytics_monthly_category",
        "analytics_monthly_account",
        "analytics_monthly_merchant",
        "analytics_monthly_project",
        "analytics_monthly_place",
    )

    fun rebuild(database: SupportSQLiteDatabase, localRevision: Long) {
        require(localRevision >= 0L)
        tables.forEach { database.execSQL("DELETE FROM $it") }
        rebuildDailyTotals(database, localRevision)
        rebuildDailyDimensions(database, localRevision)
        rebuildMonthly(database, localRevision)
        publishGeneration(database, localRevision)
    }

    /**
     * Replaces only the daily and monthly buckets touched by a financial commit.
     *
     * Every statement remains inside the caller's financial SQLite transaction, while the
     * amount of fact data read is bounded by the commit's affected local dates.
     */
    fun rebuildDates(
        database: SupportSQLiteDatabase,
        localRevision: Long,
        localDates: Set<Int>,
    ) {
        require(localRevision >= 0L)
        if (localDates.isEmpty()) return
        localDates.sorted().forEach { localDate ->
            DAILY_TABLES.forEach { table ->
                database.execSQL("DELETE FROM $table WHERE local_date=?", arrayOf<Any>(localDate))
            }
            rebuildDailyTotal(database, localRevision, localDate)
            rebuildDailyDimensions(database, localRevision, localDate)
        }
        localDates.map { it / 100 }.toSortedSet().forEach { month ->
            MONTHLY_TABLES.forEach { table ->
                database.execSQL("DELETE FROM $table WHERE year_month=?", arrayOf<Any>(month))
            }
            rebuildMonth(database, localRevision, month)
        }
    }

    /**
     * Applies only immutable facts owned by one financial commit plus the exact before/after
     * current-transaction count change. Fact UIDs are unique-index lookups, so this path is
     * bounded by the commit size rather than the number of historical facts on the same date.
     */
    fun applyCommitDeltas(
        database: SupportSQLiteDatabase,
        localRevision: Long,
        economicEffectUids: List<ByteArray>,
        journalEntryUids: List<ByteArray>,
        currentTransactionDeltas: List<AnalyticsCurrentTransactionDelta>,
    ) {
        require(localRevision >= 0L)
        economicEffectUids.chunked(DELTA_UID_CHUNK_SIZE).forEach { uids ->
            applyEconomicEffectDeltas(database, localRevision, uids)
        }
        journalEntryUids.chunked(DELTA_UID_CHUNK_SIZE).forEach { uids ->
            applyJournalEntryDeltas(database, localRevision, uids)
        }
        currentTransactionDeltas
            .groupBy(AnalyticsCurrentTransactionDelta::localDate)
            .mapValues { (_, deltas) -> deltas.fold(0L) { total, delta -> Math.addExact(total, delta.activeCountDelta) } }
            .toSortedMap()
            .forEach { (localDate, delta) -> applyCurrentTransactionCountDelta(database, localRevision, localDate, delta) }
    }

    private fun applyEconomicEffectDeltas(
        database: SupportSQLiteDatabase,
        revision: Long,
        uids: List<ByteArray>,
    ) {
        if (uids.isEmpty()) return
        val placeholders = uids.joinToString(",") { "?" }
        val arguments = uids.map { it as Any }.toTypedArray()
        val totalDeltas = database.query(
            "SELECT accrual_local_date," +
                "SUM(CASE WHEN nature=0 THEN polarity*base_amount_minor ELSE 0 END)," +
                "SUM(CASE WHEN nature=1 THEN polarity*base_amount_minor WHEN nature=2 THEN -polarity*base_amount_minor ELSE 0 END)," +
                "SUM(CASE WHEN nature=1 AND is_consumption=1 THEN polarity*base_amount_minor WHEN nature=2 AND is_consumption=1 THEN -polarity*base_amount_minor ELSE 0 END)," +
                "SUM(CASE WHEN nature=1 AND is_consumption=0 THEN polarity*base_amount_minor WHEN nature=2 AND is_consumption=0 THEN -polarity*base_amount_minor ELSE 0 END)," +
                "SUM(CASE WHEN nature=2 THEN polarity*base_amount_minor ELSE 0 END)," +
                "SUM(CASE WHEN nature=1 AND component=1 THEN polarity*base_amount_minor ELSE 0 END) " +
                "FROM economic_effect WHERE uid IN ($placeholders) GROUP BY accrual_local_date",
            arguments,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        EconomicTotalDelta(
                            cursor.getInt(0),
                            cursor.getLong(1),
                            cursor.getLong(2),
                            cursor.getLong(3),
                            cursor.getLong(4),
                            cursor.getLong(5),
                            cursor.getLong(6),
                        ),
                    )
                }
            }
        }
        totalDeltas.forEach { delta ->
            applyTotalDelta(database, revision, delta.localDate, INCOME_METRIC, delta.income)
            applyTotalDelta(database, revision, delta.localDate, EXPENSE_METRIC, delta.expense)
            applyTotalDelta(database, revision, delta.localDate, CONSUMPTION_METRIC, delta.consumption)
            applyTotalDelta(database, revision, delta.localDate, NON_CONSUMPTION_EXPENSE_METRIC, delta.nonConsumption)
            applyTotalDelta(database, revision, delta.localDate, CONTRA_EXPENSE_METRIC, delta.contraExpense)
            applyTotalDelta(database, revision, delta.localDate, LOAN_INTEREST_METRIC, delta.loanInterest)
        }
        val categoryDeltas = database.query(
            "SELECT accrual_local_date,category_id,nature,SUM(polarity*base_amount_minor) " +
                "FROM economic_effect WHERE uid IN ($placeholders) AND category_id IS NOT NULL " +
                "GROUP BY accrual_local_date,category_id,nature",
            arguments,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(CategoryDelta(cursor.getInt(0), cursor.getLong(1), cursor.getInt(2), cursor.getLong(3)))
                }
            }
        }
        categoryDeltas.forEach { delta ->
            applyCategoryDelta(database, revision, delta.localDate, delta.categoryId, delta.nature, delta.amount)
        }
        listOf("merchant", "project").forEach { dimension ->
            val dimensionDeltas = database.query(
                "SELECT accrual_local_date,${dimension}_id," +
                    "SUM(CASE WHEN nature=1 THEN polarity*base_amount_minor " +
                    "WHEN nature=2 THEN -polarity*base_amount_minor ELSE 0 END) " +
                    "FROM economic_effect WHERE uid IN ($placeholders) AND ${dimension}_id IS NOT NULL " +
                    "GROUP BY accrual_local_date,${dimension}_id",
                arguments,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(DimensionDelta(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2)))
                    }
                }
            }
            dimensionDeltas.forEach { delta ->
                applyDimensionDelta(database, revision, dimension, delta.localDate, delta.dimensionId, delta.amount)
            }
        }
        val placeDeltas = database.query(
            "SELECT ee.accrual_local_date,lr.place_id," +
                "SUM(CASE WHEN ee.nature=1 THEN ee.polarity*ee.base_amount_minor " +
                "WHEN ee.nature=2 THEN -ee.polarity*ee.base_amount_minor ELSE 0 END) " +
                "FROM economic_effect ee JOIN transaction_revision tr ON tr.id=ee.source_revision_id " +
                "JOIN location_record lr ON lr.id=tr.location_record_id " +
                "WHERE ee.uid IN ($placeholders) AND lr.place_id IS NOT NULL " +
                "GROUP BY ee.accrual_local_date,lr.place_id",
            arguments,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(DimensionDelta(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2)))
                }
            }
        }
        placeDeltas.forEach { delta ->
            applyDimensionDelta(database, revision, "place", delta.localDate, delta.dimensionId, delta.amount)
        }
    }

    private fun applyJournalEntryDeltas(
        database: SupportSQLiteDatabase,
        revision: Long,
        uids: List<ByteArray>,
    ) {
        if (uids.isEmpty()) return
        val placeholders = uids.joinToString(",") { "?" }
        val arguments = uids.map { it as Any }.toTypedArray()
        val netCashFlowDeltas = database.query(
            "SELECT je.local_date,SUM(CASE WHEN p.side=la.normal_side THEN p.base_amount_minor ELSE -p.base_amount_minor END) " +
                "FROM journal_entry je JOIN posting p ON p.journal_entry_id=je.id " +
                "JOIN ledger_account la ON la.id=p.ledger_account_id " +
                "JOIN user_account ua ON ua.ledger_account_id=la.id AND ua.type IN (0,1) " +
                "WHERE je.uid IN ($placeholders) GROUP BY je.local_date",
            arguments,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getLong(1))
            }
        }
        netCashFlowDeltas.forEach { (localDate, amount) ->
            applyTotalDelta(database, revision, localDate, NET_CASH_FLOW_METRIC, amount)
        }
        val accountDeltas = database.query(
            "SELECT je.local_date,ua.id," +
                "SUM(CASE WHEN p.side=la.normal_side THEN p.base_amount_minor ELSE 0 END)," +
                "SUM(CASE WHEN p.side<>la.normal_side THEN p.base_amount_minor ELSE 0 END) " +
                "FROM journal_entry je JOIN posting p ON p.journal_entry_id=je.id " +
                "JOIN ledger_account la ON la.id=p.ledger_account_id JOIN user_account ua ON ua.ledger_account_id=la.id " +
                "WHERE je.uid IN ($placeholders) GROUP BY je.local_date,ua.id",
            arguments,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(AccountDelta(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3)))
                }
            }
        }
        accountDeltas.forEach { delta ->
            applyAccountDelta(database, revision, delta.localDate, delta.accountId, delta.inflow, delta.outflow)
        }
    }

    private fun applyTotalDelta(
        database: SupportSQLiteDatabase,
        revision: Long,
        localDate: Int,
        metric: Int,
        delta: Long,
    ) {
        database.execSQL(
            "INSERT INTO analytics_daily_total(local_date,metric,amount_base_minor,as_of_local_revision) VALUES(?,?,?,?) " +
                "ON CONFLICT(local_date,metric) DO UPDATE SET amount_base_minor=amount_base_minor+excluded.amount_base_minor," +
                "as_of_local_revision=excluded.as_of_local_revision",
            arrayOf<Any>(localDate, metric, delta, revision),
        )
        database.execSQL(
            "INSERT INTO analytics_monthly_total(year_month,metric,amount_base_minor,as_of_local_revision) VALUES(?,?,?,?) " +
                "ON CONFLICT(year_month,metric) DO UPDATE SET amount_base_minor=amount_base_minor+excluded.amount_base_minor," +
                "as_of_local_revision=excluded.as_of_local_revision",
            arrayOf<Any>(localDate / 100, metric, delta, revision),
        )
    }

    private fun applyCategoryDelta(
        database: SupportSQLiteDatabase,
        revision: Long,
        localDate: Int,
        categoryId: Long,
        nature: Int,
        delta: Long,
    ) {
        database.execSQL(
            "INSERT INTO analytics_daily_category(local_date,category_id,nature,amount_base_minor,as_of_local_revision) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(local_date,category_id,nature) DO UPDATE SET amount_base_minor=amount_base_minor+excluded.amount_base_minor," +
                "as_of_local_revision=excluded.as_of_local_revision",
            arrayOf<Any>(localDate, categoryId, nature, delta, revision),
        )
        database.execSQL(
            "INSERT INTO analytics_monthly_category(year_month,category_id,nature,amount_base_minor,as_of_local_revision) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(year_month,category_id,nature) DO UPDATE SET amount_base_minor=amount_base_minor+excluded.amount_base_minor," +
                "as_of_local_revision=excluded.as_of_local_revision",
            arrayOf<Any>(localDate / 100, categoryId, nature, delta, revision),
        )
    }

    private fun applyAccountDelta(
        database: SupportSQLiteDatabase,
        revision: Long,
        localDate: Int,
        accountId: Long,
        inflowDelta: Long,
        outflowDelta: Long,
    ) {
        database.execSQL(
            "INSERT INTO analytics_daily_account(local_date,account_id,inflow_base_minor,outflow_base_minor,as_of_local_revision) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(local_date,account_id) DO UPDATE SET inflow_base_minor=inflow_base_minor+excluded.inflow_base_minor," +
                "outflow_base_minor=outflow_base_minor+excluded.outflow_base_minor,as_of_local_revision=excluded.as_of_local_revision",
            arrayOf<Any>(localDate, accountId, inflowDelta, outflowDelta, revision),
        )
        database.execSQL(
            "INSERT INTO analytics_monthly_account(year_month,account_id,inflow_base_minor,outflow_base_minor,as_of_local_revision) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(year_month,account_id) DO UPDATE SET inflow_base_minor=inflow_base_minor+excluded.inflow_base_minor," +
                "outflow_base_minor=outflow_base_minor+excluded.outflow_base_minor,as_of_local_revision=excluded.as_of_local_revision",
            arrayOf<Any>(localDate / 100, accountId, inflowDelta, outflowDelta, revision),
        )
    }

    private fun applyDimensionDelta(
        database: SupportSQLiteDatabase,
        revision: Long,
        dimension: String,
        localDate: Int,
        dimensionId: Long,
        delta: Long,
    ) {
        require(dimension in ANALYTICS_DIMENSIONS)
        database.execSQL(
            "INSERT INTO analytics_daily_$dimension(local_date,${dimension}_id,amount_base_minor,as_of_local_revision) VALUES(?,?,?,?) " +
                "ON CONFLICT(local_date,${dimension}_id) DO UPDATE SET amount_base_minor=amount_base_minor+excluded.amount_base_minor," +
                "as_of_local_revision=excluded.as_of_local_revision",
            arrayOf<Any>(localDate, dimensionId, delta, revision),
        )
        database.execSQL(
            "INSERT INTO analytics_monthly_$dimension(year_month,${dimension}_id,amount_base_minor,as_of_local_revision) VALUES(?,?,?,?) " +
                "ON CONFLICT(year_month,${dimension}_id) DO UPDATE SET amount_base_minor=amount_base_minor+excluded.amount_base_minor," +
                "as_of_local_revision=excluded.as_of_local_revision",
            arrayOf<Any>(localDate / 100, dimensionId, delta, revision),
        )
    }

    private fun applyCurrentTransactionCountDelta(
        database: SupportSQLiteDatabase,
        revision: Long,
        localDate: Int,
        delta: Long,
    ) {
        if (delta == 0L) return
        applyTotalDelta(database, revision, localDate, TRANSACTION_COUNT_METRIC, delta)
        val daily = singleLong(
            database,
            "SELECT amount_base_minor FROM analytics_daily_total WHERE local_date=? AND metric=?",
            arrayOf<Any>(localDate, TRANSACTION_COUNT_METRIC),
        )
        val monthly = singleLong(
            database,
            "SELECT amount_base_minor FROM analytics_monthly_total WHERE year_month=? AND metric=?",
            arrayOf<Any>(localDate / 100, TRANSACTION_COUNT_METRIC),
        )
        check(daily >= 0L && monthly >= 0L) { "analytics transaction count underflow" }
        if (daily == 0L) {
            database.execSQL(
                "DELETE FROM analytics_daily_total WHERE local_date=? AND metric=?",
                arrayOf<Any>(localDate, TRANSACTION_COUNT_METRIC),
            )
        }
        if (monthly == 0L) {
            database.execSQL(
                "DELETE FROM analytics_monthly_total WHERE year_month=? AND metric=?",
                arrayOf<Any>(localDate / 100, TRANSACTION_COUNT_METRIC),
            )
        }
    }

    /** Enters the book maintenance state before replacing only derived analytics rows. */
    fun repairInMaintenance(database: SupportSQLiteDatabase, localRevision: Long): Boolean {
        val entered = database.compileStatement("UPDATE book SET state=1 WHERE id=1 AND state=0").executeUpdateDelete()
        if (entered != 1) return false
        return try {
            rebuild(database, localRevision)
            true
        } finally {
            database.execSQL("UPDATE book SET state=0 WHERE id=1 AND state=1")
        }
    }

    fun audit(database: SupportSQLiteDatabase, localRevision: Long): AnalyticsProjectionAudit {
        val stale = staleTables(database, localRevision)
        val live = canonicalHash(database)
        database.execSQL("SAVEPOINT analytics_projection_audit")
        val rebuilt = try {
            rebuild(database, localRevision)
            canonicalHash(database)
        } finally {
            database.execSQL("ROLLBACK TO SAVEPOINT analytics_projection_audit")
            database.execSQL("RELEASE SAVEPOINT analytics_projection_audit")
        }
        return AnalyticsProjectionAudit(live, rebuilt, stale)
    }

    fun canonicalHash(database: SupportSQLiteDatabase): String {
        val digest = MessageDigest.getInstance("SHA-256")
        tables.forEach { table ->
            digest.update(table.toByteArray(Charsets.UTF_8))
            database.query("SELECT * FROM $table ORDER BY 1,2,3").use { cursor ->
                val bytes = ByteArrayOutputStream()
                DataOutputStream(bytes).use { output ->
                    while (cursor.moveToNext()) writeRow(output, cursor)
                }
                digest.update(bytes.toByteArray())
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun staleTables(database: SupportSQLiteDatabase, localRevision: Long): Set<String> = buildSet {
        val currentGeneration = singleLong(
            database,
            "SELECT COUNT(*) FROM projection_family_state WHERE family=? AND as_of_local_revision=? " +
                "AND as_of_valuation_revision=(SELECT valuation_revision FROM book WHERE id=1)",
            arrayOf<Any>(ANALYTICS_FAMILY, localRevision),
        ) == 1L
        if (!currentGeneration) addAll(tables)
        val effects = singleLong(database, "SELECT COUNT(*) FROM economic_effect")
        val current = singleLong(database, "SELECT COUNT(*) FROM current_transaction_projection WHERE state=0")
        if (effects > 0L && singleLong(database, "SELECT COUNT(*) FROM analytics_daily_total") == 0L) {
            add("analytics_daily_total")
        }
        if (current > 0L && singleLong(database, "SELECT COUNT(*) FROM analytics_monthly_total") == 0L) {
            add("analytics_monthly_total")
        }
    }

    private fun rebuildDailyTotals(database: SupportSQLiteDatabase, revision: Long) {
        database.execSQL(
            """
            INSERT INTO analytics_daily_total(local_date,metric,amount_base_minor,as_of_local_revision)
            SELECT accrual_local_date,$INCOME_METRIC,
              SUM(CASE WHEN nature=0 THEN polarity*base_amount_minor ELSE 0 END),?
            FROM economic_effect GROUP BY accrual_local_date
            UNION ALL
            SELECT accrual_local_date,$EXPENSE_METRIC,
              SUM(CASE WHEN nature=1 THEN polarity*base_amount_minor WHEN nature=2 THEN -polarity*base_amount_minor ELSE 0 END),?
            FROM economic_effect GROUP BY accrual_local_date
            UNION ALL
            SELECT accrual_local_date,$CONSUMPTION_METRIC,
              SUM(CASE WHEN nature=1 AND is_consumption=1 THEN polarity*base_amount_minor
                       WHEN nature=2 AND is_consumption=1 THEN -polarity*base_amount_minor ELSE 0 END),?
            FROM economic_effect GROUP BY accrual_local_date
            UNION ALL
            SELECT accrual_local_date,$NON_CONSUMPTION_EXPENSE_METRIC,
              SUM(CASE WHEN nature=1 AND is_consumption=0 THEN polarity*base_amount_minor
                       WHEN nature=2 AND is_consumption=0 THEN -polarity*base_amount_minor ELSE 0 END),?
            FROM economic_effect GROUP BY accrual_local_date
            UNION ALL
            SELECT accrual_local_date,$CONTRA_EXPENSE_METRIC,
              SUM(CASE WHEN nature=2 THEN polarity*base_amount_minor ELSE 0 END),?
            FROM economic_effect GROUP BY accrual_local_date
            UNION ALL
            SELECT je.local_date,$NET_CASH_FLOW_METRIC,
              SUM(CASE WHEN p.side=la.normal_side THEN p.base_amount_minor ELSE -p.base_amount_minor END),?
            FROM posting p JOIN journal_entry je ON je.id=p.journal_entry_id
              JOIN ledger_account la ON la.id=p.ledger_account_id
              JOIN user_account ua ON ua.ledger_account_id=la.id AND ua.type IN (0,1)
            GROUP BY je.local_date
            UNION ALL
            SELECT accrual_local_date,$LOAN_INTEREST_METRIC,
              SUM(CASE WHEN nature=1 AND component=1 THEN polarity*base_amount_minor ELSE 0 END),?
            FROM economic_effect GROUP BY accrual_local_date
            UNION ALL
            SELECT local_date,$TRANSACTION_COUNT_METRIC,COUNT(*),?
            FROM current_transaction_projection WHERE state=0 GROUP BY local_date
            """.trimIndent(),
            arrayOf<Any>(revision, revision, revision, revision, revision, revision, revision, revision),
        )
    }

    private fun rebuildDailyTotal(database: SupportSQLiteDatabase, revision: Long, localDate: Int) {
        database.execSQL(
            """
            INSERT INTO analytics_daily_total(local_date,metric,amount_base_minor,as_of_local_revision)
            SELECT accrual_local_date,$INCOME_METRIC,SUM(CASE WHEN nature=0 THEN polarity*base_amount_minor ELSE 0 END),?
              FROM economic_effect WHERE accrual_local_date=? GROUP BY accrual_local_date
            UNION ALL SELECT accrual_local_date,$EXPENSE_METRIC,SUM(CASE WHEN nature=1 THEN polarity*base_amount_minor WHEN nature=2 THEN -polarity*base_amount_minor ELSE 0 END),?
              FROM economic_effect WHERE accrual_local_date=? GROUP BY accrual_local_date
            UNION ALL SELECT accrual_local_date,$CONSUMPTION_METRIC,SUM(CASE WHEN nature=1 AND is_consumption=1 THEN polarity*base_amount_minor WHEN nature=2 AND is_consumption=1 THEN -polarity*base_amount_minor ELSE 0 END),?
              FROM economic_effect WHERE accrual_local_date=? GROUP BY accrual_local_date
            UNION ALL SELECT accrual_local_date,$NON_CONSUMPTION_EXPENSE_METRIC,SUM(CASE WHEN nature=1 AND is_consumption=0 THEN polarity*base_amount_minor WHEN nature=2 AND is_consumption=0 THEN -polarity*base_amount_minor ELSE 0 END),?
              FROM economic_effect WHERE accrual_local_date=? GROUP BY accrual_local_date
            UNION ALL SELECT accrual_local_date,$CONTRA_EXPENSE_METRIC,SUM(CASE WHEN nature=2 THEN polarity*base_amount_minor ELSE 0 END),?
              FROM economic_effect WHERE accrual_local_date=? GROUP BY accrual_local_date
            UNION ALL SELECT je.local_date,$NET_CASH_FLOW_METRIC,SUM(CASE WHEN p.side=la.normal_side THEN p.base_amount_minor ELSE -p.base_amount_minor END),?
              FROM posting p JOIN journal_entry je ON je.id=p.journal_entry_id
                JOIN ledger_account la ON la.id=p.ledger_account_id
                JOIN user_account ua ON ua.ledger_account_id=la.id AND ua.type IN (0,1)
              WHERE je.local_date=? GROUP BY je.local_date
            UNION ALL SELECT accrual_local_date,$LOAN_INTEREST_METRIC,SUM(CASE WHEN nature=1 AND component=1 THEN polarity*base_amount_minor ELSE 0 END),?
              FROM economic_effect WHERE accrual_local_date=? GROUP BY accrual_local_date
            UNION ALL SELECT local_date,$TRANSACTION_COUNT_METRIC,COUNT(*),?
              FROM current_transaction_projection WHERE state=0 AND local_date=? GROUP BY local_date
            """.trimIndent(),
            arrayOf<Any>(
                revision, localDate,
                revision, localDate,
                revision, localDate,
                revision, localDate,
                revision, localDate,
                revision, localDate,
                revision, localDate,
                revision, localDate,
            ),
        )
    }

    private fun rebuildDailyDimensions(database: SupportSQLiteDatabase, revision: Long) {
        database.execSQL(
            """
            INSERT INTO analytics_daily_category(local_date,category_id,nature,amount_base_minor,as_of_local_revision)
            SELECT accrual_local_date,category_id,nature,SUM(polarity*base_amount_minor),?
            FROM economic_effect WHERE category_id IS NOT NULL
            GROUP BY accrual_local_date,category_id,nature
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
        database.execSQL(
            """
            INSERT INTO analytics_daily_account(local_date,account_id,inflow_base_minor,outflow_base_minor,as_of_local_revision)
            SELECT je.local_date,ua.id,
              SUM(CASE WHEN p.side=la.normal_side THEN p.base_amount_minor ELSE 0 END),
              SUM(CASE WHEN p.side<>la.normal_side THEN p.base_amount_minor ELSE 0 END),?
            FROM posting p JOIN journal_entry je ON je.id=p.journal_entry_id
              JOIN ledger_account la ON la.id=p.ledger_account_id
              JOIN user_account ua ON ua.ledger_account_id=la.id
            GROUP BY je.local_date,ua.id
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
        rebuildExpenseDimension(database, "merchant", revision)
        rebuildExpenseDimension(database, "project", revision)
        database.execSQL(
            """
            INSERT INTO analytics_daily_place(local_date,place_id,amount_base_minor,as_of_local_revision)
            SELECT ee.accrual_local_date,lr.place_id,
              SUM(CASE WHEN ee.nature=1 THEN ee.polarity*ee.base_amount_minor
                       WHEN ee.nature=2 THEN -ee.polarity*ee.base_amount_minor ELSE 0 END),?
            FROM economic_effect ee JOIN transaction_revision tr ON tr.id=ee.source_revision_id
              JOIN location_record lr ON lr.id=tr.location_record_id
            WHERE lr.place_id IS NOT NULL
            GROUP BY ee.accrual_local_date,lr.place_id
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
    }

    private fun rebuildDailyDimensions(database: SupportSQLiteDatabase, revision: Long, localDate: Int) {
        database.execSQL(
            "INSERT INTO analytics_daily_category(local_date,category_id,nature,amount_base_minor,as_of_local_revision) " +
                "SELECT accrual_local_date,category_id,nature,SUM(polarity*base_amount_minor),? FROM economic_effect " +
                "WHERE accrual_local_date=? AND category_id IS NOT NULL GROUP BY accrual_local_date,category_id,nature",
            arrayOf<Any>(revision, localDate),
        )
        database.execSQL(
            "INSERT INTO analytics_daily_account(local_date,account_id,inflow_base_minor,outflow_base_minor,as_of_local_revision) " +
                "SELECT je.local_date,ua.id,SUM(CASE WHEN p.side=la.normal_side THEN p.base_amount_minor ELSE 0 END)," +
                "SUM(CASE WHEN p.side<>la.normal_side THEN p.base_amount_minor ELSE 0 END),? " +
                "FROM posting p JOIN journal_entry je ON je.id=p.journal_entry_id " +
                "JOIN ledger_account la ON la.id=p.ledger_account_id JOIN user_account ua ON ua.ledger_account_id=la.id " +
                "WHERE je.local_date=? GROUP BY je.local_date,ua.id",
            arrayOf<Any>(revision, localDate),
        )
        listOf("merchant", "project").forEach { dimension ->
            database.execSQL(
                "INSERT INTO analytics_daily_$dimension(local_date,${dimension}_id,amount_base_minor,as_of_local_revision) " +
                    "SELECT accrual_local_date,${dimension}_id,SUM(CASE WHEN nature=1 THEN polarity*base_amount_minor " +
                    "WHEN nature=2 THEN -polarity*base_amount_minor ELSE 0 END),? FROM economic_effect " +
                    "WHERE accrual_local_date=? AND ${dimension}_id IS NOT NULL GROUP BY accrual_local_date,${dimension}_id",
                arrayOf<Any>(revision, localDate),
            )
        }
        database.execSQL(
            "INSERT INTO analytics_daily_place(local_date,place_id,amount_base_minor,as_of_local_revision) " +
                "SELECT ee.accrual_local_date,lr.place_id,SUM(CASE WHEN ee.nature=1 THEN ee.polarity*ee.base_amount_minor " +
                "WHEN ee.nature=2 THEN -ee.polarity*ee.base_amount_minor ELSE 0 END),? " +
                "FROM economic_effect ee JOIN transaction_revision tr ON tr.id=ee.source_revision_id " +
                "JOIN location_record lr ON lr.id=tr.location_record_id WHERE ee.accrual_local_date=? " +
                "AND lr.place_id IS NOT NULL GROUP BY ee.accrual_local_date,lr.place_id",
            arrayOf<Any>(revision, localDate),
        )
    }

    private fun rebuildExpenseDimension(database: SupportSQLiteDatabase, dimension: String, revision: Long) {
        require(dimension == "merchant" || dimension == "project")
        database.execSQL(
            """
            INSERT INTO analytics_daily_$dimension(local_date,${dimension}_id,amount_base_minor,as_of_local_revision)
            SELECT accrual_local_date,${dimension}_id,
              SUM(CASE WHEN nature=1 THEN polarity*base_amount_minor
                       WHEN nature=2 THEN -polarity*base_amount_minor ELSE 0 END),?
            FROM economic_effect WHERE ${dimension}_id IS NOT NULL
            GROUP BY accrual_local_date,${dimension}_id
            """.trimIndent(),
            arrayOf<Any>(revision),
        )
    }

    private fun rebuildMonthly(database: SupportSQLiteDatabase, revision: Long) {
        database.execSQL(
            "INSERT INTO analytics_monthly_total SELECT local_date/100,metric,SUM(amount_base_minor),? " +
                "FROM analytics_daily_total GROUP BY local_date/100,metric",
            arrayOf<Any>(revision),
        )
        database.execSQL(
            "INSERT INTO analytics_monthly_category SELECT local_date/100,category_id,nature,SUM(amount_base_minor),? " +
                "FROM analytics_daily_category GROUP BY local_date/100,category_id,nature",
            arrayOf<Any>(revision),
        )
        database.execSQL(
            "INSERT INTO analytics_monthly_account SELECT local_date/100,account_id,SUM(inflow_base_minor),SUM(outflow_base_minor),? " +
                "FROM analytics_daily_account GROUP BY local_date/100,account_id",
            arrayOf<Any>(revision),
        )
        listOf("merchant", "project", "place").forEach { dimension ->
            database.execSQL(
                "INSERT INTO analytics_monthly_$dimension SELECT local_date/100,${dimension}_id,SUM(amount_base_minor),? " +
                    "FROM analytics_daily_$dimension GROUP BY local_date/100,${dimension}_id",
                arrayOf<Any>(revision),
            )
        }
    }

    private fun rebuildMonth(database: SupportSQLiteDatabase, revision: Long, month: Int) {
        database.execSQL(
            "INSERT INTO analytics_monthly_total SELECT local_date/100,metric,SUM(amount_base_minor),? " +
                "FROM analytics_daily_total WHERE local_date/100=? GROUP BY local_date/100,metric",
            arrayOf<Any>(revision, month),
        )
        database.execSQL(
            "INSERT INTO analytics_monthly_category SELECT local_date/100,category_id,nature,SUM(amount_base_minor),? " +
                "FROM analytics_daily_category WHERE local_date/100=? GROUP BY local_date/100,category_id,nature",
            arrayOf<Any>(revision, month),
        )
        database.execSQL(
            "INSERT INTO analytics_monthly_account SELECT local_date/100,account_id,SUM(inflow_base_minor),SUM(outflow_base_minor),? " +
                "FROM analytics_daily_account WHERE local_date/100=? GROUP BY local_date/100,account_id",
            arrayOf<Any>(revision, month),
        )
        listOf("merchant", "project", "place").forEach { dimension ->
            database.execSQL(
                "INSERT INTO analytics_monthly_$dimension SELECT local_date/100,${dimension}_id,SUM(amount_base_minor),? " +
                    "FROM analytics_daily_$dimension WHERE local_date/100=? GROUP BY local_date/100,${dimension}_id",
                arrayOf<Any>(revision, month),
            )
        }
    }

    private fun singleLong(
        database: SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?> = emptyArray(),
    ): Long = database.query(sql, args).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun writeRow(output: DataOutputStream, cursor: Cursor) {
        val included = (0 until cursor.columnCount).filter { cursor.getColumnName(it) != "as_of_local_revision" }
        output.writeInt(included.size)
        for (index in included) {
            output.writeInt(cursor.getType(index))
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> Unit
                Cursor.FIELD_TYPE_INTEGER -> output.writeLong(cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT, Cursor.FIELD_TYPE_STRING -> output.writeBytes(cursor.getString(index).toByteArray())
                Cursor.FIELD_TYPE_BLOB -> output.writeBytes(cursor.getBlob(index))
            }
        }
    }

    private fun DataOutputStream.writeBytes(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun publishGeneration(database: SupportSQLiteDatabase, localRevision: Long) {
        val valuationRevision = singleLong(database, "SELECT valuation_revision FROM book WHERE id=1")
        database.execSQL(
            "INSERT INTO projection_family_state(family,as_of_local_revision,as_of_valuation_revision) VALUES(?,?,?) " +
                "ON CONFLICT(family) DO UPDATE SET as_of_local_revision=excluded.as_of_local_revision," +
                "as_of_valuation_revision=excluded.as_of_valuation_revision",
            arrayOf<Any>(ANALYTICS_FAMILY, localRevision, valuationRevision),
        )
    }

    private val DAILY_TABLES: List<String>
        get() = tables.filter { it.startsWith("analytics_daily_") }

    private val MONTHLY_TABLES: List<String>
        get() = tables.filter { it.startsWith("analytics_monthly_") }

    private const val ANALYTICS_FAMILY: Int = 13
    private const val DELTA_UID_CHUNK_SIZE: Int = 400
    private val ANALYTICS_DIMENSIONS: Set<String> = setOf("merchant", "project", "place")
}

data class AnalyticsCurrentTransactionDelta(
    val localDate: Int,
    val activeCountDelta: Long,
)

private data class EconomicTotalDelta(
    val localDate: Int,
    val income: Long,
    val expense: Long,
    val consumption: Long,
    val nonConsumption: Long,
    val contraExpense: Long,
    val loanInterest: Long,
)

private data class CategoryDelta(
    val localDate: Int,
    val categoryId: Long,
    val nature: Int,
    val amount: Long,
)

private data class DimensionDelta(
    val localDate: Int,
    val dimensionId: Long,
    val amount: Long,
)

private data class AccountDelta(
    val localDate: Int,
    val accountId: Long,
    val inflow: Long,
    val outflow: Long,
)

data class AnalyticsProjectionAudit(
    val liveHash: String,
    val rebuiltHash: String,
    val staleTables: Set<String>,
) {
    val consistent: Boolean = liveHash == rebuiltHash && staleTables.isEmpty()
}
