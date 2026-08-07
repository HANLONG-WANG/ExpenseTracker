@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength")

package app.ledger.analytics.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.analytics.domain.AnalyticsAlgorithmVersion
import app.ledger.analytics.domain.AnalyticsError
import app.ledger.analytics.domain.AnomalyRule
import app.ledger.analytics.domain.AnomalyRuleType
import app.ledger.analytics.domain.ConsumptionMapFilters
import app.ledger.analytics.domain.ConsumptionMapMode
import app.ledger.analytics.domain.ConsumptionMapPresentation
import app.ledger.analytics.domain.ConsumptionMapQuery
import app.ledger.analytics.domain.ConsumptionMapResult
import app.ledger.analytics.domain.DashboardItem
import app.ledger.analytics.domain.DashboardItemWidth
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.FilterExpression
import app.ledger.analytics.domain.FixedReportCatalog
import app.ledger.analytics.domain.ForecastKey
import app.ledger.analytics.domain.IntegritySeverity
import app.ledger.analytics.domain.MapViewport
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportPeriod
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.analytics.domain.SaveAnomalyRuleRequest
import app.ledger.analytics.domain.SaveDashboardRequest
import app.ledger.analytics.domain.SaveReportDefinitionRequest
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.AnalyticsProjectionEngine
import app.ledger.core.database.EncryptedDatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AnalyticsSqlCipherDeviceTest {
    private lateinit var context: Context
    private lateinit var application: SecureRoomAnalyticsApplicationPort
    private var lastPassphraseCopy: ByteArray? = null
    private var nextStableSeed: Long = 7_000L

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        val database = EncryptedDatabaseFactory.openPrimary(context, PASSPHRASE.copyOf())
        database.inLedgerTransaction { connection -> seed(connection) }
        database.close()
        application = SecureRoomAnalyticsApplicationPort(
            context,
            { PASSPHRASE.copyOf().also { lastPassphraseCopy = it } },
            app.ledger.core.common.StableIdSource { id(++nextStableSeed) },
            app.ledger.core.time.LedgerClock { java.time.Instant.parse("2026-08-06T00:00:00Z") },
        )
    }

    @Test
    fun customReportDashboardAnomalyAndForecastRoundTripThroughNormalizedEncryptedSchema() = runBlocking {
        val spec = FixedReportCatalog.definition(app.ledger.analytics.domain.FixedReport.INCOME_EXPENSE_NET).spec.copy(
            measures = listOf(Measure.EXPENSE),
            dimensions = listOf(Dimension.DATE),
            filters = FilterExpression.All,
            comparison = null,
        )
        val created = application.saveReport(
            BOOK_ID,
            SaveReportDefinitionRequest(null, "Monthly spending", null, spec, ReportVisualization.LINE),
        ).success()
        assertEquals("Monthly spending", application.savedReports(BOOK_ID).success().single().definition.name)

        val conflict = application.saveReport(
            BOOK_ID,
            SaveReportDefinitionRequest(created.definition.id, "stale", created.definition.rowVersion + 1, spec, ReportVisualization.LINE),
        )
        assertEquals(AnalyticsError.RevisionConflict, (conflict as DomainResult.Failure).error)

        val copied = application.copyReport(BOOK_ID, created.definition.id, "Monthly spending copy").success()
        assertEquals(2, application.savedReports(BOOK_ID).success().size)
        val dashboard = application.saveDashboard(
            BOOK_ID,
            SaveDashboardRequest(
                null,
                "Overview",
                null,
                listOf(
                    DashboardItem(copied.definition.id, 0, DashboardItemWidth.FULL),
                    DashboardItem(created.definition.id, 1, DashboardItemWidth.FULL),
                ),
            ),
        ).success()
        assertEquals(listOf(copied.definition.id, created.definition.id), dashboard.revision.items.map(DashboardItem::reportId))
        assertEquals(dashboard, application.dashboards(BOOK_ID).success().single())

        val anomaly = application.saveAnomalyRule(
            BOOK_ID,
            SaveAnomalyRuleRequest(
                null,
                null,
                AnomalyRule(AnomalyRuleType.LARGE_SINGLE_TRANSACTION, BigDecimal("1000"), 12, AnalyticsAlgorithmVersion(1)),
                true,
            ),
        ).success()
        assertEquals(anomaly, application.anomalyRules(BOOK_ID).success().single())
        assertTrue(application.anomalyFindings(BOOK_ID, AUGUST).success().all { it.rule.version == AnalyticsAlgorithmVersion(1) })

        val forecast = application.forecast(BOOK_ID, ForecastKey.MONTH_END_SPENDING, LocalDate.of(2026, 8, 6)).success()
        assertEquals(AnalyticsAlgorithmVersion(1), forecast.version)
        assertEquals(LocalDate.of(2026, 8, 31), forecast.throughDate)

        val reopened = EncryptedDatabaseFactory.openPrimary(context, PASSPHRASE.copyOf())
        reopened.readLedger { connection ->
            assertEquals(2L, singleLong(connection, "SELECT logicalSchemaVersion FROM _room_schema_registry WHERE id=1"))
            assertEquals(2L, singleLong(connection, "SELECT COUNT(*) FROM analytics_report_definition"))
            assertEquals(2L, singleLong(connection, "SELECT COUNT(*) FROM analytics_report_revision"))
            assertEquals(1L, singleLong(connection, "SELECT COUNT(*) FROM analytics_dashboard_revision"))
            assertEquals(1L, singleLong(connection, "SELECT COUNT(*) FROM analytics_anomaly_rule_revision"))
        }
        reopened.close()
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
    }

    @Test
    fun encryptedQueriesUseExactFinancialSemanticsAndEveryFixedReportExecutes() = runBlocking {
        val overview = application.overview(BOOK_ID, AUGUST).success()
        assertEquals(10_000L, overview.incomeMinor)
        assertEquals(3_000L, overview.allExpenseMinor)
        assertEquals(3_000L, overview.consumptionMinor)
        assertEquals(7_000L, overview.netSurplusMinor)
        assertEquals(BigDecimal("0.70000000"), overview.savingsRate)
        assertEquals(4L, overview.transactionCount)
        assertTrue(requireNotNull(lastPassphraseCopy).all { it == 0.toByte() })

        val directDatabase = EncryptedDatabaseFactory.openPrimary(context, PASSPHRASE.copyOf())
        directDatabase.readLedger { connection ->
            FixedReportCatalog.definitions.forEach { definition ->
                val compiled = ReportSqlCompiler.compile(
                    ReportQueryPlanner.select(definition.spec),
                    definition.spec,
                    AUGUST.start,
                    AUGUST.endInclusive,
                )
                connection.query(compiled.sql, compiled.arguments).use { cursor ->
                    assertTrue("${definition.key.value} returned an invalid cursor", cursor.count >= 0)
                }
            }
        }
        directDatabase.close()

        FixedReportCatalog.definitions.forEach { definition ->
            val result = application.executeFixed(BOOK_ID, definition.report, AUGUST)
            assertTrue(
                "${definition.key.value} failed: ${(result as? DomainResult.Failure)?.error?.code}",
                result is DomainResult.Success,
            )
            val execution = result.success()
            if (execution is ReportExecution.Content) {
                assertEquals(definition.report, execution.fixedReport)
                assertTrue(execution.rows.all { row -> row.measureValues.size == definition.spec.measures.size })
            }
        }
    }

    @Test
    fun staleProjectionIsNotShownAndFactRebuildRepairsToIdenticalHash() = runBlocking {
        mutateDatabase("UPDATE analytics_daily_total SET amount_base_minor=amount_base_minor+1 WHERE metric=1")
        val broken = application.integrity(BOOK_ID).success()
        assertEquals(IntegritySeverity.FAILURE, broken.severity)
        assertFalse(broken.liveProjectionHash == broken.rebuiltProjectionHash)

        val repaired = application.repairAnalyticsProjections(BOOK_ID).success()
        assertEquals(IntegritySeverity.PASS, repaired.severity)
        assertEquals(repaired.liveProjectionHash, repaired.rebuiltProjectionHash)

        mutateDatabase("UPDATE analytics_monthly_total SET as_of_local_revision=0")
        val stale = application.executeFixed(BOOK_ID, FixedReportCatalog.definitions.first().report, AUGUST).success()
        assertTrue(stale is ReportExecution.StaleProjection)
        application.repairAnalyticsProjections(BOOK_ID).success()
        assertTrue(application.executeFixed(BOOK_ID, FixedReportCatalog.definitions.first().report, AUGUST).success() is ReportExecution.Content)
    }

    @Test
    fun consumptionMapUsesRTreeFrozenBaseAmountsDefaultExclusionsAndOpaqueDrilldown() = runBlocking {
        val defaultQuery = ConsumptionMapQuery(AUGUST, MapViewport.World)
        val map = application.consumptionMap(BOOK_ID, defaultQuery).success()
        assertEquals(3_000L, map.viewportBaseAmountMinor)
        assertEquals(2L, map.viewportTransactionCount)
        assertEquals(2, map.points.size)
        assertTrue(map.query.filters.hidesTransfersRepaymentsAndLoans)
        val options = application.consumptionMapFilterOptions(BOOK_ID).success()
        assertEquals(listOf("Account 1", "Account 2"), options.accounts.map { it.label })
        assertTrue(options.categories.isEmpty())
        assertTrue(options.merchants.isEmpty())
        assertTrue(options.places.isEmpty())
        assertTrue(options.projects.isEmpty())

        val expenseLocationId = id(502)
        val detail = application.consumptionMapDetail(BOOK_ID, defaultQuery, expenseLocationId).success()
        assertEquals(4_000L, detail.point.baseAmountMinor)
        assertEquals(1L, detail.point.transactionCount)
        assertEquals(1, detail.transactionPreview.size)
        val drilldown = application.drillDown(BOOK_ID, detail.drilldownQueryId, null, 20).success()
        assertEquals(listOf(id(102)), drilldown.rows.map { it.transactionId })

        val allLocated = application.consumptionMap(
            BOOK_ID,
            defaultQuery.copy(
                mode = ConsumptionMapMode.ALL_LOCATED_TRANSACTIONS,
                presentation = ConsumptionMapPresentation.SINGLE_POINTS,
                filters = ConsumptionMapFilters().withSpecialTransactions(true),
            ),
        ).success()
        assertEquals(4L, allLocated.viewportTransactionCount)
        assertEquals(17_000L, allLocated.viewportBaseAmountMinor)
        assertEquals(4, allLocated.points.size)
    }

    @Test
    fun tenThousandLocatedTransactionsRemainDatabaseAggregatedViewportBoundedAndNodeBounded() = runBlocking {
        seedTenThousandLocatedTransactions()
        val filters = ConsumptionMapFilters().withSpecialTransactions(true)
        val world = application.consumptionMap(
            BOOK_ID,
            ConsumptionMapQuery(
                AUGUST,
                MapViewport.World,
                mode = ConsumptionMapMode.ALL_LOCATED_TRANSACTIONS,
                filters = filters,
            ),
        ).success()
        assertEquals(10_004L, world.viewportTransactionCount)
        assertEquals(ConsumptionMapResult.MAX_RENDERED_POINTS, world.points.size)
        assertTrue(world.resultLimited)

        val narrow = application.consumptionMap(
            BOOK_ID,
            world.query.copy(
                viewport = MapViewport(350_000_000, 350_100_000, 1_390_000_000, 1_390_100_000, 15),
            ),
        ).success()
        assertTrue(narrow.viewportTransactionCount in 1..200)
        assertTrue(narrow.points.size <= narrow.viewportTransactionCount)
        assertFalse(narrow.resultLimited)
    }

    private fun mutateDatabase(sql: String) {
        val database = EncryptedDatabaseFactory.openPrimary(context, PASSPHRASE.copyOf())
        database.inLedgerTransaction { it.execSQL(sql) }
        database.close()
    }

    private fun seedTenThousandLocatedTransactions() {
        val database = EncryptedDatabaseFactory.openPrimary(context, PASSPHRASE.copyOf())
        database.inLedgerTransaction { connection ->
            connection.execSQL(
                "WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<10000) " +
                    "INSERT INTO location_record(id,uid,lat_e7,lon_e7,accuracy_mm,captured_at,source,provider,created_commit_id) " +
                    "SELECT 40000+value,randomblob(16),350000000+value*1000,1390000000+value*1000,1000,1786000000000+value,0,'device',1 FROM n",
            )
            connection.execSQL(
                "WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<10000) " +
                    "INSERT INTO business_transaction(id,uid,kind,current_revision_id,lifecycle_state,created_commit_id,last_commit_id,row_version,content_hash) " +
                    "SELECT 1000+value,randomblob(16),0,NULL,0,1,1,1,randomblob(32) FROM n",
            )
            connection.execSQL(
                "WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<10000) " +
                    "INSERT INTO transaction_revision(id,uid,transaction_id,revision_no,action,resulting_state,created_commit_id,created_at,occurred_at,zone_id,local_date,location_record_id,source_type,content_hash) " +
                    "SELECT 20000+value,randomblob(16),1000+value,1,0,0,1,value,1786000000000+value,'Asia/Tokyo',20260806,40000+value,0,randomblob(32) FROM n",
            )
            connection.execSQL(
                "WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<10000) " +
                    "INSERT INTO expense_revision_detail(revision_id,payer_kind,payer_account_id) SELECT 20000+value,0,1 FROM n",
            )
            connection.execSQL(
                "WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<10000) " +
                    "UPDATE business_transaction SET current_revision_id=20000+(id-1000) WHERE id IN (SELECT 1000+value FROM n)",
            )
            connection.execSQL(
                "WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<10000) " +
                    "INSERT INTO current_transaction_projection(transaction_id,transaction_uid,kind,state,current_revision_id,occurred_at,local_date,primary_account_id," +
                    "input_amount_minor,input_currency,account_amount_minor,account_currency,economic_base_minor,has_attachment,has_location,is_refund,is_refunded,has_installment,source_type,as_of_local_revision) " +
                    "SELECT bt.id,bt.uid,0,0,20000+value,1786000000000+value,20260806,1,1,'JPY',1,'JPY',1,0,1,0,0,0,0,1 " +
                    "FROM n JOIN business_transaction bt ON bt.id=1000+value",
            )
            connection.execSQL(
                "WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<10000) " +
                    "INSERT INTO location_rtree(location_id,min_lat,max_lat,min_lon,max_lon) " +
                    "SELECT id,lat_e7/10000000.0,lat_e7/10000000.0,lon_e7/10000000.0,lon_e7/10000000.0 FROM location_record WHERE id IN (SELECT 40000+value FROM n)",
            )
        }
        database.close()
    }

    private fun singleLong(database: SupportSQLiteDatabase, sql: String): Long = database.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun seed(database: SupportSQLiteDatabase) {
        database.execSQL("INSERT INTO rule_set_version(version,algorithm_hash,activated_at) VALUES (1,?,1)", arrayOf<Any>(blob(1, 32)))
        database.execSQL(
            "INSERT INTO book_commit(id,uid,local_revision,kind,command_uid,device_instance_uid,created_at,root_hash) VALUES (1,?,1,0,?,?,1,?)",
            arrayOf<Any>(id(1).bytes, id(2).bytes, id(3).bytes, blob(4, 32)),
        )
        database.execSQL(
            "INSERT INTO book(id,uid,base_currency,default_zone_id,head_commit_id,local_revision,valuation_revision,rule_set_version,created_at,first_financial_commit_at,state) " +
                "VALUES (1,?,'JPY','Asia/Tokyo',1,1,1,1,1,1,0)",
            arrayOf<Any>(BOOK_ID.bytes),
        )
        repeat(2) { index ->
            val row = index + 1
            database.execSQL(
                "INSERT INTO ledger_account(id,uid,owner_type,account_class,normal_side,currency_code,system_code,status,created_commit_id) VALUES (?,?,0,0,0,'JPY',NULL,0,1)",
                arrayOf<Any>(row, id(10L + row).bytes),
            )
            database.execSQL(
                "INSERT INTO user_account(id,uid,ledger_account_id,type,name,currency_code,status,icon_key,color_argb,sort_order,last_commit_id,row_version,content_hash) " +
                    "VALUES (?,?,?,0,?,'JPY',0,'cash',-1,?,1,1,?)",
                arrayOf<Any>(row, id(20L + row).bytes, row, "Account $row", row, blob(30 + row, 32)),
            )
        }
        seedTransaction(database, 1, kind = 1, amount = 10_000, nature = 0, subtype = "income")
        seedTransaction(database, 2, kind = 0, amount = 4_000, nature = 1, subtype = "expense")
        seedTransaction(database, 3, kind = 3, amount = 1_000, nature = 2, subtype = "refund")
        seedTransaction(database, 4, kind = 2, amount = 2_000, nature = null, subtype = "transfer")
        AnalyticsProjectionEngine.rebuild(database, 1)
    }

    private fun seedTransaction(
        database: SupportSQLiteDatabase,
        row: Int,
        kind: Int,
        amount: Long,
        nature: Int?,
        subtype: String,
    ) {
        val transactionId = row
        val revisionId = 100 + row
        val locationId = 500 + row
        val transactionUid = id(100L + row)
        database.execSQL(
            "INSERT INTO location_record(id,uid,lat_e7,lon_e7,accuracy_mm,captured_at,source,provider,created_commit_id) VALUES (?,?,?,?,1000,?,0,'device',1)",
            arrayOf<Any>(locationId, id(500L + row).bytes, 356_000_000 + row * 10_000, 1_397_000_000 + row * 10_000, 1_786_000_000_000L + row),
        )
        database.execSQL(
            "INSERT INTO location_rtree(location_id,min_lat,max_lat,min_lon,max_lon) VALUES (?,?,?,?,?)",
            arrayOf<Any>(locationId, (356_000_000 + row * 10_000) / 10_000_000.0, (356_000_000 + row * 10_000) / 10_000_000.0, (1_397_000_000 + row * 10_000) / 10_000_000.0, (1_397_000_000 + row * 10_000) / 10_000_000.0),
        )
        database.execSQL(
            "INSERT INTO business_transaction(id,uid,kind,current_revision_id,lifecycle_state,created_commit_id,last_commit_id,row_version,content_hash) " +
                "VALUES (?,?,?,NULL,0,1,1,1,?)",
            arrayOf<Any>(transactionId, transactionUid.bytes, kind, blob(50 + row, 32)),
        )
        database.execSQL(
            "INSERT INTO transaction_revision(id,uid,transaction_id,revision_no,action,resulting_state,created_commit_id,created_at,occurred_at,zone_id,local_date,location_record_id,source_type,content_hash) " +
                "VALUES (?,?,?,1,0,0,1,?,?, 'Asia/Tokyo',20260806,?,0,?)",
            arrayOf<Any>(revisionId, id(200L + row).bytes, transactionId, row.toLong(), 1_786_000_000_000L + row, locationId, blob(70 + row, 32)),
        )
        when (subtype) {
            "income" -> database.execSQL("INSERT INTO income_revision_detail(revision_id,receiving_account_id) VALUES (?,1)", arrayOf<Any>(revisionId))
            "expense" -> database.execSQL("INSERT INTO expense_revision_detail(revision_id,payer_kind,payer_account_id) VALUES (?,0,1)", arrayOf<Any>(revisionId))
            "refund" -> database.execSQL(
                "INSERT INTO refund_revision_detail(revision_id,receiving_account_id,independent,budget_policy,allow_excess) VALUES (?,1,1,0,0)",
                arrayOf<Any>(revisionId),
            )
            "transfer" -> database.execSQL("INSERT INTO transfer_revision_detail(revision_id,from_account_id,to_account_id) VALUES (?,1,2)", arrayOf<Any>(revisionId))
        }
        database.execSQL("UPDATE business_transaction SET current_revision_id=? WHERE id=?", arrayOf<Any>(revisionId, transactionId))
        if (nature != null) {
            database.execSQL(
                "INSERT INTO economic_effect(id,uid,source_revision_id,polarity,nature,component,is_consumption,base_amount_minor,accrual_local_date,rule_set_version) " +
                    "VALUES (?,?,?,1,?,0,1,?,20260806,1)",
                arrayOf<Any>(300 + row, id(300L + row).bytes, revisionId, nature, amount),
            )
        }
        database.execSQL(
            "INSERT INTO current_transaction_projection(transaction_id,transaction_uid,kind,state,current_revision_id,occurred_at,local_date,primary_account_id,secondary_account_id," +
                "input_amount_minor,input_currency,account_amount_minor,account_currency,economic_base_minor,has_attachment,has_location,is_refund,is_refunded,has_installment,source_type,as_of_local_revision) " +
                "VALUES (?,?,?,0,?,?,20260806,1,?,?,'JPY',?,'JPY',?,0,1,?,0,0,0,1)",
            arrayOf<Any?>(transactionId, transactionUid.bytes, kind, revisionId, 1_786_000_000_000L + row, if (kind == 2) 2 else null, amount, amount, amount, if (kind == 3) 1 else 0),
        )
        database.execSQL(
            "INSERT INTO transaction_fts(transaction_id,category_name,merchant_name,merchant_aliases,note,project_name,settlement_activity_name,participant_names,attachment_names,lifecycle_state) " +
                "VALUES (?, '', '', '', '', '', '', '', '', 0)",
            arrayOf<Any>(transactionId),
        )
    }

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private fun id(seed: Long): StableId = StableId.fromUuid(UUID(0L, seed))

    private fun blob(seed: Int, count: Int): ByteArray = ByteArray(count) { index -> (seed + index).toByte() }

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0L, 999L))
        val AUGUST: ReportPeriod = ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))
        val PASSPHRASE: ByteArray = ByteArray(32) { index -> (0x41 + index).toByte() }
    }
}
