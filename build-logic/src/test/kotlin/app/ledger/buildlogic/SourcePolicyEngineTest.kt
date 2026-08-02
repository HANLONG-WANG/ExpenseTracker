package app.ledger.buildlogic

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourcePolicyEngineTest {
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
