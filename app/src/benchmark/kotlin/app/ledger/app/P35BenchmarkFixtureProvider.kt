@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength", "TooGenericExceptionCaught", "TooManyFunctions")

package app.ledger.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.DatabaseMaintenance
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.database.WalCheckpointMode
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.feature.onboarding.OnboardingStep
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.data.SecureRoomLedgerInitializationPort
import app.ledger.finance.data.SecureRoomReferenceDataManagementPort
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.DebitCredit
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.UserAccountType
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.system.measureTimeMillis

/** Benchmark-build-only SQLCipher fixture and audit endpoint invoked by Macrobenchmark shell calls. */
public class P35BenchmarkFixtureProvider : ContentProvider() {
    private val fixtureMutex = Mutex()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = runBlocking {
        when (method) {
            METHOD_SEED -> seed()
            METHOD_AUDIT -> audit()
            METHOD_REFERENCE_AUDIT -> referenceAudit()
            else -> Bundle().apply { putString(KEY_ERROR, "UNKNOWN_METHOD") }
        }
    }

    private suspend fun seed(): Bundle = fixtureMutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = requireNotNull(context).applicationContext
            val marker = appContext.filesDir.resolve(MARKER_NAME)
            if (marker.readTextOrNull() == MARKER_VERSION) {
                return@withContext result("already-complete")
            }
            val hierarchy = DeviceKeyHierarchy(AndroidKeystoreKeys(appContext), SecurityEnvelopeStore(appContext))
            val initialization = SecureRoomLedgerInitializationPort(appContext, hierarchy)
            val currency = CurrencyCode.parse("JPY").success()
            initialization.initialize(
                InitializeLedgerCommand(
                    LedgerGenesisIds(
                        BOOK_ID,
                        id(2),
                        id(3),
                        id(4),
                        SystemLedgerCode.entries.associateWith { code -> id(100L + code.ordinal) },
                    ),
                    currency,
                    ZONE,
                    FIXED_INSTANT,
                ),
            ).success()
            initialization.createFirstAccount(
                BOOK_ID,
                InitialAccountCommand(
                    ACCOUNT_ID,
                    id(301),
                    id(302),
                    id(303),
                    id(304),
                    FIXED_INSTANT,
                    UserAccountType.BANK,
                    "Benchmark account",
                    currency,
                    "account",
                    0xff386a20.toInt(),
                ),
            ).allowDuplicate()
            initialization.createFirstCategory(
                BOOK_ID,
                InitialCategoryCommand(
                    CATEGORY_ID,
                    id(402),
                    id(403),
                    id(404),
                    FIXED_INSTANT,
                    CategoryDirection.EXPENSE,
                    "Benchmark food",
                    "benchmark food",
                    StatisticalNature.CONSUMPTION_EXPENSE,
                    "food",
                    0xff8b5000.toInt(),
                ),
            ).allowDuplicate()

            val elapsed = hierarchy.open(BOOK_ID).use { keys ->
                keys.databaseDek.useBytes { passphrase ->
                    EncryptedDatabaseFactory.openPrimary(appContext, passphrase).use { database ->
                        measureTimeMillis { TargetScaleSeeder(appContext.filesDir.resolve(OBJECT_DIRECTORY)).seed(database) }
                    }
                }
            }
            (appContext as LedgerApplication).settingsRepository.update {
                it.onboardingComplete = true
                it.onboardingStep = OnboardingStep.COMPLETE.toProto()
                it.languageTag = "en"
                it.baseCurrency = "JPY"
                it.zoneId = ZONE.id
                it.privacyAccepted = true
                it.telemetryEnabled = false
                it.crashReportingEnabled = false
                it.diagnosticsChoiceRecorded = true
                it.bookId = ByteString.copyFrom(BOOK_ID.bytes)
                it.firstAccountCreated = true
                it.firstCategoryCreated = true
                it.securitySettingsInitialized = true
                it.obscureRecentTasks = true
                it.trashRetentionDays = 30
            }
            marker.writeText(MARKER_VERSION)
            result("seeded", elapsed)
        }
    }

    private suspend fun audit(): Bundle = withContext(Dispatchers.IO) {
        val appContext = requireNotNull(context).applicationContext
        try {
            val hierarchy = DeviceKeyHierarchy(AndroidKeystoreKeys(appContext), SecurityEnvelopeStore(appContext))
            hierarchy.open(BOOK_ID).use { keys ->
                keys.databaseDek.useBytes { passphrase ->
                    EncryptedDatabaseFactory.openPrimary(appContext, passphrase).use { database ->
                        val audit = TargetScaleAudit(appContext.filesDir.resolve(OBJECT_DIRECTORY)).run(database)
                        Bundle().apply {
                            putString(KEY_STATUS, "audited")
                            putString(KEY_SUMMARY, audit)
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            Bundle().apply { putString(KEY_ERROR, failure.message ?: failure::class.java.simpleName) }
        }
    }

    private suspend fun referenceAudit(): Bundle = withContext(Dispatchers.IO) {
        val appContext = requireNotNull(context).applicationContext
        try {
            val hierarchy = DeviceKeyHierarchy(AndroidKeystoreKeys(appContext), SecurityEnvelopeStore(appContext))
            val port = SecureRoomReferenceDataManagementPort(appContext, hierarchy)
            var entryCount = 0
            val entryMillis = measureTimeMillis {
                val snapshot = (port.entrySnapshot(BOOK_ID) as DomainResult.Success).value
                entryCount = snapshot.merchants.size + snapshot.places.size + snapshot.locations.size + snapshot.accountTransactions.size
            }
            var fullCount = 0
            val fullMillis = measureTimeMillis {
                val snapshot = (port.snapshot(BOOK_ID) as DomainResult.Success).value
                fullCount = snapshot.merchants.size + snapshot.places.size + snapshot.locations.size + snapshot.accountTransactions.size
            }
            Bundle().apply {
                putString(KEY_STATUS, "audited")
                putString(KEY_SUMMARY, "entrySnapshotMs=$entryMillis,entryRows=$entryCount,fullSnapshotMs=$fullMillis,fullRows=$fullCount")
            }
        } catch (failure: Exception) {
            Bundle().apply { putString(KEY_ERROR, failure.message ?: failure::class.java.simpleName) }
        }
    }

    private fun result(status: String, elapsedMillis: Long = 0L): Bundle = Bundle().apply {
        putString(KEY_STATUS, status)
        putLong(KEY_ELAPSED, elapsedMillis)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private fun DomainResult<Unit>.allowDuplicate() {
        if (this is DomainResult.Failure && error.code != "DUPLICATE_INITIAL_REFERENCE") error(error.code)
    }

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(P35_NAMESPACE, value))

    internal companion object {
        const val METHOD_SEED = "seed"
        const val METHOD_AUDIT = "audit"
        const val METHOD_REFERENCE_AUDIT = "references"
        const val KEY_STATUS = "status"
        const val KEY_ELAPSED = "elapsedMillis"
        const val KEY_SUMMARY = "summary"
        const val KEY_ERROR = "error"
        const val MARKER_NAME = "p35-target-scale-v1.complete"
        const val MARKER_VERSION = "P35_TARGET_SCALE_V1"
        const val OBJECT_DIRECTORY = "p35_attachment_objects"
        const val P35_NAMESPACE = 0x5035L
        val BOOK_ID: StableId = StableId.fromUuid(UUID(P35_NAMESPACE, 1L))
        val ACCOUNT_ID: StableId = StableId.fromUuid(UUID(P35_NAMESPACE, 300L))
        val CATEGORY_ID: StableId = StableId.fromUuid(UUID(P35_NAMESPACE, 400L))
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
        val FIXED_INSTANT: Instant = Instant.parse("2026-08-11T00:00:00Z")
    }
}

private class TargetScaleSeeder(private val objectDirectory: File) {
    fun seed(database: LedgerDatabase) {
        val refs = database.readLedger { db ->
            FixtureReferences(
                commit = db.scalar("SELECT head_commit_id FROM book WHERE id=1"),
                account = db.scalar("SELECT id FROM user_account WHERE uid=?", arrayOf<Any?>(P35BenchmarkFixtureProvider.ACCOUNT_ID.bytes)),
                accountLedger = db.scalar("SELECT ledger_account_id FROM user_account WHERE uid=?", arrayOf<Any?>(P35BenchmarkFixtureProvider.ACCOUNT_ID.bytes)),
                expenseLedger = db.scalar("SELECT id FROM ledger_account WHERE system_code='SYSTEM_EXPENSE_CONSUMPTION'"),
                category = db.scalar("SELECT id FROM category WHERE uid=?", arrayOf<Any?>(P35BenchmarkFixtureProvider.CATEGORY_ID.bytes)),
                revision = db.scalar("SELECT local_revision FROM book WHERE id=1"),
            )
        }
        seedPlaces(database, refs.commit)
        seedTransactions(database, refs)
        seedAttachments(database)
        writeObjectFiles()
        database.inLedgerTransaction { db ->
            db.execSQL("UPDATE book SET first_financial_commit_at=coalesce(first_financial_commit_at,?) WHERE id=1", arrayOf<Any?>(P35BenchmarkFixtureProvider.FIXED_INSTANT.toEpochMilli()))
            DatabaseMaintenance.optimize(db)
        }
        DatabaseMaintenance.checkpoint(database.openHelper.writableDatabase, WalCheckpointMode.TRUNCATE)
    }

    private fun seedPlaces(database: LedgerDatabase, commitId: Long) {
        var start = database.readLedger { it.scalar("SELECT COUNT(*) FROM merchant WHERE id>=?", arrayOf<Any?>(MERCHANT_BASE)) }.toInt()
        while (start < MERCHANTS_AND_PLACES) {
            val end = minOf(start + BATCH_SIZE, MERCHANTS_AND_PLACES)
            val count = end - start
            database.inLedgerTransaction { db ->
                db.execSQL(
                    "$SEQUENCE INSERT INTO merchant(id,uid,name,normalized_name,status,merged_into_id,last_commit_id,row_version) " +
                        "SELECT ?+x,randomblob(16),'Merchant '||(?+x),'merchant '||(?+x),0,NULL,?,1 FROM seq WHERE x<?",
                    arrayOf<Any?>(MERCHANT_BASE + start, start, start, commitId, count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO place(id,uid,name,center_lat_e7,center_lon_e7,merchant_id,status,merged_into_id,last_commit_id,row_version) " +
                        "SELECT ?+x,randomblob(16),'Place '||(?+x),356800000+((?+x)%10000),1397600000+((?+x)%10000),?+x,0,NULL,?,1 FROM seq WHERE x<?",
                    arrayOf<Any?>(PLACE_BASE + start, start, start, start, MERCHANT_BASE + start, commitId, count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO location_record(id,uid,lat_e7,lon_e7,accuracy_mm,captured_at,source,provider,place_id,created_commit_id) " +
                        "SELECT ?+x,randomblob(16),356800000+((?+x)%10000),1397600000+((?+x)%10000),1000,?,0,NULL,?+x,? FROM seq WHERE x<?",
                    arrayOf<Any?>(LOCATION_BASE + start, start, start, P35BenchmarkFixtureProvider.FIXED_INSTANT.toEpochMilli(), PLACE_BASE + start, commitId, count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO place_rtree(place_id,min_lat,max_lat,min_lon,max_lon) " +
                        "SELECT ?+x,35.68+((?+x)%10000)/10000000.0,35.68+((?+x)%10000)/10000000.0,139.76+((?+x)%10000)/10000000.0,139.76+((?+x)%10000)/10000000.0 FROM seq WHERE x<?",
                    arrayOf<Any?>(PLACE_BASE + start, start, start, start, start, count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO location_rtree(location_id,min_lat,max_lat,min_lon,max_lon) " +
                        "SELECT ?+x,35.68+((?+x)%10000)/10000000.0,35.68+((?+x)%10000)/10000000.0,139.76+((?+x)%10000)/10000000.0,139.76+((?+x)%10000)/10000000.0 FROM seq WHERE x<?",
                    arrayOf<Any?>(LOCATION_BASE + start, start, start, start, start, count),
                )
            }
            start = end
        }
    }

    private fun seedTransactions(database: LedgerDatabase, refs: FixtureReferences) {
        var start = database.readLedger { it.scalar("SELECT COUNT(*) FROM business_transaction WHERE id>=?", arrayOf<Any?>(TRANSACTION_BASE)) }.toInt()
        while (start < CURRENT_TRANSACTIONS) {
            val end = minOf(start + BATCH_SIZE, CURRENT_TRANSACTIONS)
            val count = end - start
            database.inLedgerTransaction { db ->
                db.execSQL(
                    "$SEQUENCE INSERT INTO business_transaction(id,uid,kind,current_revision_id,lifecycle_state,created_commit_id,last_commit_id,row_version,trashed_at,purge_after,content_hash) " +
                        "SELECT ?+x,randomblob(16),0,NULL,0,?,?,1,NULL,NULL,randomblob(32) FROM seq WHERE x<?",
                    arrayOf<Any?>(TRANSACTION_BASE + start, refs.commit, refs.commit, count),
                )
                db.execSQL(revisionSql(start, end, refs, second = false))
                db.execSQL(revisionSql(start, end, refs, second = true))
                db.execSQL(
                    "$SEQUENCE INSERT INTO expense_revision_detail(revision_id,payer_kind,payer_account_id,payer_card_id,payer_participant_id,settlement_activity_id,installment_plan_id) " +
                        "SELECT ?+x*2,0,?,NULL,NULL,NULL,NULL FROM seq WHERE x<? UNION ALL SELECT ?+x*2,0,?,NULL,NULL,NULL,NULL FROM seq WHERE x<?",
                    arrayOf<Any?>(REVISION_BASE + start * 2L, refs.account, count, REVISION_BASE + start * 2L + 1L, refs.account, count),
                )
                db.execSQL(
                    "$SEQUENCE UPDATE business_transaction SET current_revision_id=?+(id-?)*2+1 WHERE id>=? AND id<?",
                    arrayOf<Any?>(REVISION_BASE, TRANSACTION_BASE, TRANSACTION_BASE + start, TRANSACTION_BASE + end),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO journal_entry(id,uid,source_revision_id,applies_revision_id,entry_role,reverses_entry_id,effective_at,zone_id,local_date,base_currency,base_debit_total_minor,base_credit_total_minor,posting_count,rule_set_version,created_commit_id,content_hash) " +
                        "SELECT ?+x,randomblob(16),?+x*2,?+x*2,0,NULL,1800000000000+(?+x),'Asia/Tokyo',20260811,'JPY',1,1,2,1,?,randomblob(32) FROM seq WHERE x<?",
                    arrayOf<Any?>(JOURNAL_BASE + start, REVISION_BASE + start * 2L + 1L, REVISION_BASE + start * 2L + 1L, start, refs.commit, count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO posting(id,uid,journal_entry_id,line_no,ledger_account_id,side,account_amount_minor,account_currency,base_amount_minor,base_currency,valuation_rate_decimal,posting_role,reversal_of_posting_id) " +
                        "SELECT ?+x*2,randomblob(16),?+x,1,?,?,1,'JPY',1,'JPY',NULL,0,NULL FROM seq WHERE x<? " +
                        "UNION ALL SELECT ?+x*2,randomblob(16),?+x,2,?,?,1,'JPY',1,'JPY',NULL,0,NULL FROM seq WHERE x<?",
                    arrayOf<Any?>(POSTING_BASE + start * 2L, JOURNAL_BASE + start, refs.accountLedger, DebitCredit.CREDIT.ordinal, count, POSTING_BASE + start * 2L + 1L, JOURNAL_BASE + start, refs.expenseLedger, DebitCredit.DEBIT.ordinal, count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO current_transaction_projection(transaction_id,transaction_uid,kind,state,current_revision_id,occurred_at,local_date,primary_account_id,secondary_account_id,card_id,category_id,merchant_id,project_id,goal_id,settlement_activity_id,payer_participant_id,input_amount_minor,input_currency,account_amount_minor,account_currency,economic_base_minor,note_preview,has_attachment,has_location,is_refund,is_refunded,has_installment,source_type,as_of_local_revision) " +
                        "SELECT ?+x,(SELECT uid FROM business_transaction WHERE id=?+x),0,0,?+x*2,1800000000000+(?+x),20260811,?,NULL,NULL,?,?+((?+x)%?),NULL,NULL,NULL,NULL,1,'JPY',1,'JPY',1,CASE WHEN ?+x=? THEN 'needle-p35' ELSE NULL END,CASE WHEN ?+x<100000 THEN 1 ELSE 0 END,1,0,0,0,0,? FROM seq WHERE x<?",
                    arrayOf<Any?>(TRANSACTION_BASE + start, TRANSACTION_BASE + start, REVISION_BASE + start * 2L + 1L, start, refs.account, refs.category, MERCHANT_BASE, start, MERCHANTS_AND_PLACES, start, CURRENT_TRANSACTIONS - 1, start, refs.revision, count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO transaction_fts(transaction_id,category_name,merchant_name,merchant_aliases,note,project_name,settlement_activity_name,participant_names,attachment_names,lifecycle_state) " +
                        "SELECT ?+x,'Benchmark food','','',CASE WHEN ?+x=? THEN 'needle-p35' ELSE '' END,'','','','',0 FROM seq WHERE x<?",
                    arrayOf<Any?>(TRANSACTION_BASE + start, start, CURRENT_TRANSACTIONS - 1, count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO economic_effect(id,uid,source_entry_id,source_revision_id,reversal_of_id,polarity,nature,component,is_consumption,base_amount_minor,accrual_local_date,category_id,merchant_id,project_id,rule_set_version) " +
                        "SELECT ?+x,randomblob(16),?+x,?+x*2,NULL,1,?,0,1,1,20260811,?,?+((?+x)%?),NULL,1 FROM seq WHERE x<?",
                    arrayOf<Any?>(EFFECT_BASE + start, JOURNAL_BASE + start, REVISION_BASE + start * 2L + 1L, StatisticalNature.CONSUMPTION_EXPENSE.ordinal, refs.category, MERCHANT_BASE, start, MERCHANTS_AND_PLACES, count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO budget_effect(id,source_revision_id,reversal_of_id,polarity,kind,target_year_month,category_id,root_category_id,base_amount_minor,rule_set_version) " +
                        "SELECT ?+x,?+x*2,NULL,1,0,202608,?,?,1,1 FROM seq WHERE x<?",
                    arrayOf<Any?>(BUDGET_EFFECT_BASE + start, REVISION_BASE + start * 2L + 1L, refs.category, refs.category, count),
                )
            }
            start = end
        }
    }

    private fun revisionSql(start: Int, end: Int, refs: FixtureReferences, second: Boolean): String {
        val offset = if (second) 1L else 0L
        val revisionNumber = if (second) 2 else 1
        val previous = if (second) (REVISION_BASE + start * 2L).toString() else "NULL"
        return "$SEQUENCE INSERT INTO transaction_revision(id,uid,transaction_id,revision_no,action,resulting_state,previous_revision_id,created_commit_id,created_at,occurred_at,zone_id,local_date,category_id,statistical_nature_snapshot,merchant_id,project_id,goal_id,location_record_id,note,amount_expression,source_type,source_reference_uid,content_hash) " +
            "SELECT ${REVISION_BASE + start * 2L + offset}+x*2,randomblob(16),${TRANSACTION_BASE + start}+x,$revisionNumber,${if (second) 1 else 0},0,${if (second) "$previous+x*2" else previous},${refs.commit},1800000000000+$start+x,1800000000000+$start+x,'Asia/Tokyo',20260811,${refs.category},${StatisticalNature.CONSUMPTION_EXPENSE.ordinal},$MERCHANT_BASE+(($start+x)%$MERCHANTS_AND_PLACES),NULL,NULL,$LOCATION_BASE+(($start+x)%$MERCHANTS_AND_PLACES),CASE WHEN $start+x=${CURRENT_TRANSACTIONS - 1} THEN 'needle-p35' ELSE NULL END,NULL,0,NULL,randomblob(32) FROM seq WHERE x<${end - start}"
    }

    private fun seedAttachments(database: LedgerDatabase) {
        var start = database.readLedger { it.scalar("SELECT COUNT(*) FROM attachment WHERE id>=?", arrayOf<Any?>(ATTACHMENT_BASE)) }.toInt()
        while (start < ATTACHMENT_FILES) {
            val end = minOf(start + BATCH_SIZE, ATTACHMENT_FILES)
            val count = end - start
            database.inLedgerTransaction { db ->
                db.execSQL(
                    "$SEQUENCE INSERT INTO encrypted_blob(id,uid,storage_name,plaintext_sha256,plaintext_size,mime_type,extension,wrapped_data_key,encryption_version,reference_count_projection,created_at) " +
                        "SELECT ?+x,randomblob(16),printf('p35_%08x.object',?+x),randomblob(32),64,'application/octet-stream','bin',randomblob(64),1,2,? FROM seq WHERE x<?",
                    arrayOf<Any?>(BLOB_BASE + start, start, P35BenchmarkFixtureProvider.FIXED_INSTANT.toEpochMilli(), count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO attachment(id,uid,blob_id,display_name,imported_at,status) " +
                        "SELECT ?+x,randomblob(16),?+x,'Benchmark attachment',?,0 FROM seq WHERE x<?",
                    arrayOf<Any?>(ATTACHMENT_BASE + start, BLOB_BASE + start, P35BenchmarkFixtureProvider.FIXED_INSTANT.toEpochMilli(), count),
                )
                db.execSQL(
                    "$SEQUENCE INSERT INTO transaction_revision_attachment(revision_id,attachment_id,sort_order) " +
                        "SELECT ?+x*2,?+x,0 FROM seq WHERE x<? UNION ALL SELECT ?+x*2,?+x,0 FROM seq WHERE x<?",
                    arrayOf<Any?>(REVISION_BASE + start * 2L + 1L, ATTACHMENT_BASE + start, count, REVISION_BASE + (start + ATTACHMENT_FILES) * 2L + 1L, ATTACHMENT_BASE + start, count),
                )
            }
            start = end
        }
    }

    private fun writeObjectFiles() {
        require(objectDirectory.isDirectory || objectDirectory.mkdirs())
        val payload = ByteArray(64) { index -> (index xor 0x35).toByte() }
        repeat(ATTACHMENT_FILES) { index ->
            val target = objectDirectory.resolve("p35_%08x.object".format(index))
            if (!target.isFile) FileOutputStream(target).use { it.write(payload) }
        }
    }

    private data class FixtureReferences(
        val commit: Long,
        val account: Long,
        val accountLedger: Long,
        val expenseLedger: Long,
        val category: Long,
        val revision: Long,
    )

    private companion object {
        const val CURRENT_TRANSACTIONS = 500_000
        const val MERCHANTS_AND_PLACES = 5_000
        const val ATTACHMENT_FILES = 50_000
        const val BATCH_SIZE = 1_000
        const val MERCHANT_BASE = 1_000_000L
        const val PLACE_BASE = 1_100_000L
        const val LOCATION_BASE = 1_200_000L
        const val TRANSACTION_BASE = 2_000_000L
        const val REVISION_BASE = 3_000_000L
        const val JOURNAL_BASE = 5_000_000L
        const val POSTING_BASE = 6_000_000L
        const val EFFECT_BASE = 8_000_000L
        const val BUDGET_EFFECT_BASE = 9_000_000L
        const val BLOB_BASE = 10_000_000L
        const val ATTACHMENT_BASE = 11_000_000L
        const val SEQUENCE = "WITH d(n) AS (VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9)), " +
            "seq(x) AS (SELECT a.n+10*b.n+100*c.n FROM d a,d b,d c) "
    }
}

private class TargetScaleAudit(private val objectDirectory: File) {
    fun run(database: LedgerDatabase): String {
        val beforeHeap = usedHeap()
        val beforeFd = descriptorCount()
        val timings = linkedMapOf<String, Long>()
        database.readLedger { db ->
            require(db.scalar("SELECT COUNT(*) FROM business_transaction") >= 500_000L) { "current transactions below target" }
            require(db.scalar("SELECT COUNT(*) FROM transaction_revision") >= 1_000_000L) { "history below target" }
            require(db.scalar("SELECT COUNT(*) FROM posting") >= 1_000_000L) { "postings below target" }
            require(db.scalar("SELECT COUNT(*) FROM transaction_revision_attachment") >= 100_000L) { "attachment associations below target" }
            require(db.scalar("SELECT COUNT(*) FROM merchant") >= 5_000L) { "merchants below target" }
            require(db.scalar("SELECT COUNT(*) FROM place") >= 5_000L) { "places below target" }
            require(streamingFileCount(objectDirectory) >= 50_000L) { "attachment files below target" }
            timings["pagingMs"] = measureQuery(db, "SELECT transaction_id,occurred_at FROM current_transaction_projection WHERE state=0 ORDER BY occurred_at DESC,transaction_id DESC LIMIT 41")
            val recentDefaultsSql = "SELECT recent.kind,c.uid,ua.uid,pc.uid,recent.occurred_at FROM (SELECT kind,category_id,primary_account_id,card_id,occurred_at,transaction_id FROM current_transaction_projection INDEXED BY ix_current_transaction_keyset WHERE state=0 AND kind IN (0,1) ORDER BY occurred_at DESC,transaction_id DESC LIMIT 50) recent JOIN category c ON c.id=recent.category_id LEFT JOIN user_account ua ON ua.id=recent.primary_account_id LEFT JOIN payment_card pc ON pc.id=recent.card_id ORDER BY recent.occurred_at DESC,recent.transaction_id DESC"
            timings["recordDefaultsMs"] = measureQuery(db, recentDefaultsSql)
            timings["searchMs"] = measureQuery(db, "SELECT transaction_id FROM transaction_fts WHERE transaction_fts MATCH 'needle' LIMIT 41")
            timings["reportMs"] = measureQuery(db, "SELECT accrual_local_date,SUM(base_amount_minor) FROM economic_effect WHERE accrual_local_date BETWEEN 20260101 AND 20261231 AND is_consumption=1 GROUP BY accrual_local_date")
            timings["mapMs"] = measureQuery(db, "SELECT l.place_id,COUNT(*) FROM location_rtree r JOIN location_record l ON l.id=r.location_id WHERE r.min_lat<=36 AND r.max_lat>=35 AND r.min_lon<=140 AND r.max_lon>=139 GROUP BY l.place_id LIMIT 10000")
            val pagePlan = db.plan("SELECT transaction_id,occurred_at FROM current_transaction_projection WHERE state=0 ORDER BY occurred_at DESC,transaction_id DESC LIMIT 41")
            require(pagePlan.contains("INDEX", ignoreCase = true))
            val recentDefaultsPlan = db.plan(recentDefaultsSql)
            require(recentDefaultsPlan.contains("ix_current_transaction_keyset")) { "record plan=$recentDefaultsPlan" }
            val reportPlan = db.plan("SELECT SUM(base_amount_minor) FROM economic_effect WHERE accrual_local_date BETWEEN 20260101 AND 20261231 AND nature=0")
            require(reportPlan.contains("ix_economic_effect_date_nature"))
            val mapPlan = db.plan("SELECT location_id FROM location_rtree WHERE min_lat<=36 AND max_lat>=35 AND min_lon<=140 AND max_lon>=139")
            require(mapPlan.contains("VIRTUAL TABLE INDEX", ignoreCase = true))
            val checkpoint = DatabaseMaintenance.checkpoint(db, WalCheckpointMode.PASSIVE)
            require(checkpoint.busyConnections >= 0L)
        }
        val heapGrowth = usedHeap() - beforeHeap
        val fdGrowth = descriptorCount() - beforeFd
        require(heapGrowth < 64L * 1024L * 1024L) { "heap growth $heapGrowth" }
        require(fdGrowth <= 8L) { "descriptor growth $fdGrowth" }
        require(timings.getValue("pagingMs") <= 5_000L)
        require(timings.getValue("recordDefaultsMs") <= 5_000L) { "recordDefaultsMs=${timings.getValue("recordDefaultsMs")}" }
        require(timings.getValue("searchMs") <= 5_000L)
        require(timings.getValue("reportMs") <= 10_000L)
        require(timings.getValue("mapMs") <= 10_000L)
        return (timings + mapOf("heapGrowthBytes" to heapGrowth, "fdGrowth" to fdGrowth, "nativeHeapBytes" to Debug.getNativeHeapAllocatedSize())).entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    private fun measureQuery(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long = measureTimeMillis {
        database.query(sql).use { cursor -> while (cursor.moveToNext()) cursor.getLong(0) }
    }

    private fun usedHeap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    private fun descriptorCount(): Long = Files.newDirectoryStream(File("/proc/self/fd").toPath()).use { stream -> stream.count().toLong() }

    private fun streamingFileCount(directory: File): Long = Files.newDirectoryStream(directory.toPath()).use { stream -> stream.count().toLong() }
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.scalar(sql: String, args: Array<out Any?> = emptyArray()): Long = query(sql, args).use { cursor ->
    check(cursor.moveToFirst())
    cursor.getLong(0)
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.plan(sql: String): String = query("EXPLAIN QUERY PLAN $sql").use { cursor ->
    buildString { while (cursor.moveToNext()) append(cursor.getString(3)).append('\n') }
}

private fun File.readTextOrNull(): String? = takeIf(File::isFile)?.readText()

private inline fun <T> LedgerDatabase.use(block: (LedgerDatabase) -> T): T = try {
    block(this)
} finally {
    close()
}
