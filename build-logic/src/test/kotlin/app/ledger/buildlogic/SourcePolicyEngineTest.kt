package app.ledger.buildlogic

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourcePolicyEngineTest {
    @Test
    fun `rejects direct primary opens passphrase exposure and legacy adapters outside the P37 allowlist`() {
        val rules = scan(
            "finance/data/src/main/kotlin/app/ledger/finance/data/SecureRoomBudgetApplicationPort.kt",
            """
                val database = databaseDek.useBytes { passphrase ->
                    EncryptedDatabaseFactory.openPrimary(context, passphrase.copyOf())
                }
                val legacy = LegacyLivePrimaryDatabaseAccess()
                fun open() = openSelectedLedger(PRIMARY_DATABASE_NAME)
            """.trimIndent(),
        )

        rules.shouldContainAll(
            "P37-LIVE-PRIMARY-OPEN",
            "P37-DATABASE-PASSPHRASE",
            "P37-LEGACY-PRIMARY-ACCESS",
        )
    }

    @Test
    fun `allows reviewed session initialization and named-copy key boundaries`() {
        val sessionRules = scan(
            "core/security/src/main/kotlin/app/ledger/core/security/BookSessionManager.kt",
            "val database = databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }",
        )
        val copyRules = scan(
            "finance/data/src/main/kotlin/app/ledger/finance/data/SelectedLedgerDatabase.kt",
            "val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openLedgerCopy(context, name, it) }",
        )

        sessionRules.shouldNotContain("P37-LIVE-PRIMARY-OPEN")
        sessionRules.shouldNotContain("P37-DATABASE-PASSPHRASE")
        copyRules.shouldNotContain("P37-DATABASE-PASSPHRASE")
    }

    @Test
    fun `rejects manual navigation epochs and feature flow collection in the Ready shell only`() {
        val path = "app/src/main/kotlin/app/ledger/app/ReadyRootScaffold.kt"
        val rules = scan(
            path,
            """
                fun ReadyRootScaffold(viewModel: AppRootViewModel) {
                    var navigationEpoch = 0
                    val journal by viewModel.journal.collectAsStateWithLifecycle()
                }

                fun currentRouteFixedAction(viewModel: AppRootViewModel) {
                    val refund by viewModel.refund.collectAsStateWithLifecycle()
                }
            """.trimIndent(),
        )

        rules.count { it == "P37-NAVIGATION-EPOCH" } shouldBe 1
        rules.count { it == "P37-SHELL-FEATURE-COLLECTION" } shouldBe 1
    }

    @Test
    fun `allows global shell state and route selective fixed action collection`() {
        val findings = SourcePolicyEngine.scan(
            "app/src/main/kotlin/app/ledger/app/ReadyRootScaffold.kt",
            """
                fun ReadyRootScaffold(viewModel: AppRootViewModel) {
                    val settings by viewModel.settings.collectAsStateWithLifecycle()
                    val current = viewModel.navigator.version.let { viewModel.navigator.currentTopLevel }
                }

                fun currentRouteFixedAction(viewModel: AppRootViewModel) {
                    val refund by viewModel.refund.collectAsStateWithLifecycle()
                }
            """.trimIndent(),
        )

        findings.map(SourcePolicyFinding::ruleId).shouldNotContain("P37-SHELL-FEATURE-COLLECTION")
        findings.map(SourcePolicyFinding::ruleId).shouldNotContain("P37-NAVIGATION-EPOCH")
    }

    @Test
    fun `rejects full reference snapshots in interactive code and allows explicit import maintenance`() {
        scan(
            "app/src/main/kotlin/app/ledger/app/AppRootViewModel.kt",
            "suspend fun load() = referenceDataPort.snapshot(bookId)",
        ).shouldContain("P37-INTERACTIVE-FULL-SNAPSHOT")

        val maintenance = SourcePolicyEngine.scan(
            "app/src/main/kotlin/app/ledger/app/ImportController.kt",
            "suspend fun inspect() = references.snapshot(bookId)",
        )
        maintenance.map(SourcePolicyFinding::ruleId).shouldNotContain("P37-INTERACTIVE-FULL-SNAPSHOT")
    }

    @Test
    fun `rejects feature data and unmanaged UI access`() {
        val rules = scan(
            "feature/record/src/main/kotlin/Record.kt",
            """
                import androidx.room.Entity
                import androidx.compose.material3.Button
                val accent = Color(0xFF123456)
                val gap = 12.dp
                fun theme() = MaterialTheme { }
            """.trimIndent(),
        )

        rules.shouldContainAll(
            "ARCH-FEATURE-DATA",
            "UI-WRAPPER",
            "UI-COLOR-LITERAL",
            "UI-SPACING-LITERAL",
            "UI-LOCAL-THEME",
        )
    }

    @Test
    fun `rejects every P04 UI governance bypass`() {
        val rules = scan(
            "feature/journal/src/main/kotlin/UnsafeJournal.kt",
            """
                import androidx.compose.material.icons.Icons
                import androidx.compose.material3.SwipeToDismissBox
                import androidx.compose.material3.FilledTonalButton
                val hex = "#1234AB"
                val scheme = MaterialTheme.colorScheme
                fun AmountText(value: String) = value
                fun rowTag(transactionName: String) = Modifier.testTag("row_" + transactionName)
            """.trimIndent(),
        )

        rules.shouldContainAll(
            "UI-WRAPPER",
            "UI-COLOR-LITERAL",
            "UI-LOCAL-THEME",
            "UI-ICON-REGISTRY",
            "UI-SWIPE-DELETE",
            "UI-COMPONENT-DUPLICATE",
            "PRIVACY-TEST-TAG",
        )
    }

    @Test
    fun `rejects P10 SDK bypass background location and shared plaintext storage`() {
        val featureRules = scan(
            "feature/record/src/main/kotlin/UnsafeInfrastructure.kt",
            """
                import app.ledger.core.files.EncryptedAttachmentObjectStore
                import coil3.ImageLoader
                import com.google.android.gms.location.LocationServices
                import org.maplibre.android.maps.MapView
                val permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
            """.trimIndent(),
        )
        val fileRules = scan(
            "core/files/src/main/kotlin/UnsafePlaintext.kt",
            "val plaintext = context.getExternalFilesDir(null)",
        )

        featureRules.shouldContainAll(
            "ARCH-FEATURE-INFRASTRUCTURE",
            "ARCH-ATTACHMENT-SDK",
            "ARCH-GEO-SDK",
            "PRIVACY-BACKGROUND-LOCATION",
        )
        fileRules.shouldContain("PRIVACY-FILES-SHARED-STORAGE")
    }

    @Test
    fun `allows only fixed semantic test tags`() {
        val findings = SourcePolicyEngine.scan(
            "core/designsystem/src/main/kotlin/Tags.kt",
            """
                fun stable() = Modifier.testTag(LedgerTestTags.AMOUNT)
                fun localStable() = Modifier.testTag("transaction_amount_field")
            """.trimIndent(),
        )
        check(findings.isEmpty()) { findings.joinToString { it.diagnostic() } }
    }

    @Test
    fun `allows proven fixed test tag adapters and rejects dynamic drift`() {
        val navigation = """
            fun content() = Modifier.testTag(destination.navigationTestTag())

            private fun LedgerTopLevel.navigationTestTag(): String = when (this) {
                LedgerTopLevel.RECORD -> LedgerTestTags.NAVIGATION_RECORD
                LedgerTopLevel.JOURNAL -> LedgerTestTags.NAVIGATION_JOURNAL
                LedgerTopLevel.ACCOUNTS -> LedgerTestTags.NAVIGATION_ACCOUNTS
                LedgerTopLevel.BUDGET -> LedgerTestTags.NAVIGATION_BUDGET
                LedgerTopLevel.ANALYSIS -> LedgerTestTags.NAVIGATION_ANALYSIS
            }
        """.trimIndent()
        val navigationPath = "core/designsystem/src/main/kotlin/app/ledger/core/designsystem/FoundationComponents.kt"
        check(SourcePolicyEngine.scan(navigationPath, navigation).isEmpty())
        SourcePolicyEngine.scan(
            navigationPath,
            navigation.replace(
                "LedgerTopLevel.ANALYSIS -> LedgerTestTags.NAVIGATION_ANALYSIS",
                "LedgerTopLevel.ANALYSIS -> destination.runtimeTag",
            ),
        ).map(SourcePolicyFinding::ruleId).shouldContain("PRIVACY-TEST-TAG")

        val journal = """
            fun content() {
                PagedJournalList(
                    readyTestTag = LedgerTestTags.JOURNAL_SEARCH_RESULTS.takeIf { state.searchResultReady },
                )
            }

            private fun PagedJournalList(
                readyTestTag: String? = null,
            ) = Modifier.testTag(readyTestTag)
        """.trimIndent()
        val journalPath = "feature/journal/src/main/kotlin/app/ledger/feature/journal/JournalDestination.kt"
        check(SourcePolicyEngine.scan(journalPath, journal).isEmpty())
        SourcePolicyEngine.scan(
            journalPath,
            journal.replace(
                "LedgerTestTags.JOURNAL_SEARCH_RESULTS.takeIf { state.searchResultReady }",
                "state.searchText",
            ),
        ).map(SourcePolicyFinding::ruleId).shouldContain("PRIVACY-TEST-TAG")
    }

    @Test
    fun `rejects Android imports in domain`() {
        scan(
            "finance/domain/src/main/kotlin/Balance.kt",
            "import android.content.Context",
        ).shouldContain("ARCH-DOMAIN-FRAMEWORK")
    }

    @Test
    fun `rejects generic domain payload bags`() {
        scan(
            "finance/domain/src/main/kotlin/UniversalTransaction.kt",
            "data class UniversalTransaction(val payload: Map<String, Any?>)",
        ).shouldContain("ARCH-DOMAIN-GENERIC-PAYLOAD")
    }

    @Test
    fun `rejects binary floating point and unchecked sums in authoritative money code`() {
        val rules = scan(
            "core/money/src/main/kotlin/UnsafeMoney.kt",
            "fun total(values: List<Double>) = values.sum()",
        )

        rules.shouldContainAll("MONEY-BINARY-FLOAT", "MONEY-UNCHECKED-SUM")
    }

    @Test
    fun `rejects generic telemetry and ordinary logging`() {
        val rules = scan(
            "core/telemetry/src/main/kotlin/Event.kt",
            """
                val fields: Map<String, Any?> = mapOf()
                fun leak(note: String) = android.util.Log.d("ledger", note)
            """.trimIndent(),
        )
        rules.shouldContainAll("PRIVACY-TELEMETRY-MAP", "PRIVACY-LOGGING")
    }

    @Test
    fun `rejects sensitive routes and saved state`() {
        val rules = scan(
            "core/navigation/src/main/kotlin/Routes.kt",
            """
                data class EditRoute(val transactionId: String, val amount: Money, val note: String)
                fun save(savedStateHandle: SavedStateHandle) { savedStateHandle["password"] = "x" }
            """.trimIndent(),
        )
        rules.shouldContainAll("PRIVACY-ROUTE-STATE", "PRIVACY-SAVEDSTATE-KEY")
    }

    @Test
    fun `rejects feature access to security capabilities and raw secret strings`() {
        val rules = scan(
            "feature/settings/src/main/kotlin/UnsafeSecurity.kt",
            """
                import app.ledger.core.security.HeadlessBookLease
                data class VaultDraftSavedState(val plaintext: SensitivePlaintext)
                fun recover(password: String): String = password
            """.trimIndent(),
        )

        rules.shouldContainAll(
            "ARCH-FEATURE-SECURITY",
            "PRIVACY-ROUTE-STATE",
            "PRIVACY-RAW-SECRET",
        )
    }

    @Test
    fun `rejects financial DAO writes outside coordinator`() {
        scan(
            "feature/record/src/main/kotlin/Save.kt",
            "fun save(transactionDao: TransactionDao) = transactionDao.insertCurrent(command)",
        ).shouldContain("FINANCE-COORDINATOR")
    }

    @Test
    fun `rejects direct financial SQL and privileged commit ports outside data ownership`() {
        val rules = scan(
            "feature/importer/src/main/kotlin/ImportWorker.kt",
            """
                import app.ledger.finance.application.AtomicFinancialCommitRepository
                fun bypass(db: SupportSQLiteDatabase) {
                    db.execSQL("INSERT INTO journal_entry(id) VALUES (1)")
                }
            """.trimIndent(),
        )

        rules.shouldContainAll("FINANCE-WRITE-PORT", "FINANCE-SQL-WRITE")
    }

    @Test
    fun `rejects privileged planning and persistence ports in workers and importers`() {
        val rules = scan(
            "transfer/data/src/main/kotlin/ImportWorker.kt",
            """
                import app.ledger.finance.application.FinancialPlanningSnapshotRepository
                import app.ledger.finance.application.AtomicFinancialCommitRepository
                class ImportWorker
            """.trimIndent(),
        )

        rules.count { it == "FINANCE-WRITE-PORT" } shouldBe 2
    }

    @Test
    fun `rejects DAO aliases and ignores unrelated coordinator declarations`() {
        val rules = scan(
            "feature/record/src/main/kotlin/Save.kt",
            """
                class DecoyFinancialMutationCoordinator
                fun save(transactionDao: TransactionDao) {
                    val store = transactionDao
                    store.insertCurrent(command)
                }
            """.trimIndent(),
        )
        rules.shouldContain("FINANCE-COORDINATOR")
    }

    @Test
    fun `rejects fold reduce plus assign and manual Long accumulations`() {
        val rules = scan(
            "core/money/src/main/kotlin/UnsafeAggregation.kt",
            """
                fun fold(values: List<Long>) = values.fold(0L) { total, value -> total + value }
                fun reduce(values: List<Long>) = values.reduce { total, value -> total + value }
                fun loop(values: List<Long>): Long {
                    var total = 0L
                    for (value in values) total += value
                    return total
                }
                fun manual(values: List<Long>): Long {
                    var amount = 0L
                    for (value in values) amount = amount + value
                    return amount
                }
                fun obscure(values: List<Long>): Long {
                    var x: Long = 0
                    for (value in values) x += value
                    return x
                }
            """.trimIndent(),
        )
        rules.shouldContainAll("MONEY-UNCHECKED-SUM", "MONEY-UNCHECKED-ACCUMULATION")
    }

    @Test
    fun `rejects sensitive wrappers and SavedState aliases`() {
        val rules = scan(
            "core/navigation/src/main/kotlin/State.kt",
            """
                data class MoneyEnvelope(val amount: Long)
                data class EditRoute(val payload: MoneyEnvelope)
                fun save(handle: SavedStateHandle, envelope: MoneyEnvelope) {
                    val store = handle
                    store["draft"] = envelope
                }
            """.trimIndent(),
        )
        rules.shouldContainAll("PRIVACY-ROUTE-STATE", "PRIVACY-SAVEDSTATE-KEY")
    }

    @Test
    fun `rejects telemetry and logging aliases outside fixed paths`() {
        val rules = scan(
            "analytics/data/src/main/kotlin/Reporter.kt",
            """
                import android.util.Log as AuditLog
                class LedgerTelemetryReporter {
                    val fields: Map<String, Any?> = mapOf()
                    fun emit() = AuditLog.d("ledger", "event")
                }
            """.trimIndent(),
        )
        rules.shouldContainAll("PRIVACY-TELEMETRY-MAP", "PRIVACY-LOGGING")
    }

    @Test
    fun `allows a typed telemetry caller to keep unrelated route maps`() {
        val rules = scan(
            "app/src/main/kotlin/AppRootViewModel.kt",
            """
                fun navigate() = mapOf("cardId" to StableIdArgument(id))
                fun record(event: FeatureDiagnosticEvent) = TelemetryRuntime.record(event)
            """.trimIndent(),
        )

        rules.shouldNotContain("PRIVACY-TELEMETRY-MAP")
    }

    @Test
    fun `allows stable IDs and coordinator-owned writes`() {
        val findings = SourcePolicyEngine.scan(
            "finance/data/src/main/kotlin/DefaultFinancialMutationCoordinator.kt",
            """
                data class DetailRoute(val transactionId: StableId, val locationId: StableId?)
                class DefaultFinancialMutationCoordinator {
                    fun apply(postingDao: PostingDao) = postingDao.insertAll()
                }
            """.trimIndent(),
            setOf("JRN-007"),
        )
        check(findings.isEmpty()) { findings.joinToString { it.diagnostic() } }
    }

    @Test
    fun `rejects unknown screen IDs and direct nondeterminism`() {
        val rules = SourcePolicyEngine.scan(
            "app/src/main/kotlin/Destination.kt",
            "val destination = ScreenId(\"BAD-999\"); val now = Instant.now(); val id = UUID.randomUUID()",
            setOf("G-001"),
        ).map { it.ruleId }
        rules.shouldContainAll("UI-SCREEN-ID", "DETERMINISM-CLOCK", "DETERMINISM-ID")
    }

    private fun scan(path: String, source: String): List<String> = SourcePolicyEngine.scan(path, source).map { it.ruleId }
}
