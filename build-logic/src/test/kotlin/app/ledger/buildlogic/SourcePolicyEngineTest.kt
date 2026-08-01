package app.ledger.buildlogic

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
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
    fun `rejects Android imports in domain`() {
        scan(
            "finance/domain/src/main/kotlin/Balance.kt",
            "import android.content.Context",
        ).shouldContain("ARCH-DOMAIN-ANDROID")
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
    fun `rejects financial DAO writes outside coordinator`() {
        scan(
            "feature/record/src/main/kotlin/Save.kt",
            "fun save(transactionDao: TransactionDao) = transactionDao.insertCurrent(command)",
        ).shouldContain("FINANCE-COORDINATOR")
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
