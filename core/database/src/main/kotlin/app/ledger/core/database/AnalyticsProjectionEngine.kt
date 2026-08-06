@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth", "TooManyFunctions")

package app.ledger.core.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Schema-level deterministic maintenance for the twelve frozen analytics rollups. */
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
        tables.forEach { table ->
            val mismatched = singleLong(
                database,
                "SELECT COUNT(*) FROM $table WHERE as_of_local_revision <> ?",
                arrayOf(localRevision),
            )
            if (mismatched > 0L) add(table)
        }
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

    private fun singleLong(
        database: SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?> = emptyArray(),
    ): Long = database.query(sql, args).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun writeRow(output: DataOutputStream, cursor: Cursor) {
        output.writeInt(cursor.columnCount)
        for (index in 0 until cursor.columnCount) {
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
}

data class AnalyticsProjectionAudit(
    val liveHash: String,
    val rebuiltHash: String,
    val staleTables: Set<String>,
) {
    val consistent: Boolean = liveHash == rebuiltHash && staleTables.isEmpty()
}
