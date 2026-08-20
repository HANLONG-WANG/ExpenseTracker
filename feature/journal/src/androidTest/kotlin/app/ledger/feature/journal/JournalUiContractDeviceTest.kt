@file:Suppress("LongMethod", "MaxLineLength")

package app.ledger.feature.journal

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.JournalDependencyView
import app.ledger.finance.application.JournalDetailView
import app.ledger.finance.application.JournalFxEvidenceView
import app.ledger.finance.application.JournalPurgeAssessment
import app.ledger.finance.application.JournalRevisionComparison
import app.ledger.finance.application.JournalRevisionView
import app.ledger.finance.application.JournalTransactionView
import app.ledger.finance.domain.RevisionAction
import app.ledger.finance.domain.TransactionDependencyType
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JournalUiContractDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun allFortyTwoRequiredStatesRenderAcrossWidthsFontsLocalesAndThemes() {
        val cases = cases()
        assertEquals(42, cases.size)
        assertEquals(EXPECTED, cases.groupBy(Case::screen).mapValues { (_, values) -> values.map(Case::state).toSet() })
        val active = mutableStateOf(cases.first())
        val page = MutableStateFlow(PagingData.from(listOf(ROW, ROW.copy(transactionId = id(20), localDate = LocalDate.of(2026, 8, 2)))))
        composeRule.setContent {
            val case = active.value
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val localized = base.createConfigurationContext(Configuration(configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(case.locale))) })
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, case.fontScale),
            ) {
                LedgerTheme(case.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(case.width.dp, 1_700.dp)) {
                        JournalDestination(case.screen, emptyMap<String, String>(), case.content, page, ACTIONS)
                    }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("journal_screen").assertExists()
        }
    }

    private fun cases(): List<Case> {
        val content = JournalLoadState.Content(
            detail = DETAIL,
            history = HISTORY,
            comparison = JournalRevisionComparison(HISTORY[1], HISTORY[0], listOf("amount"), listOf("account")),
            dependencies = listOf(JournalDependencyView(ID, id(30), TransactionDependencyType.REFUND, TransactionLifecycleState.ACTIVE)),
            purgeAssessment = JournalPurgeAssessment(ID, NOW, NOW.minusSeconds(1), emptySet()),
        )
        val raw = EXPECTED.flatMap { (screen, states) -> states.map { screen to it } }
        return raw.mapIndexed { index, (screen, state) ->
            Case(screen, state, content, listOf(320, 360, 480)[index % 3], listOf(1f, 1.3f, 2f)[index % 3], listOf("zh-CN", "ja-JP", "en-US")[index % 3], if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK)
        }
    }

    private data class Case(val screen: String, val state: String, val content: JournalLoadState, val width: Int, val fontScale: Float, val locale: String, val theme: ThemeMode)

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-03T04:05:06Z")
        val ID: StableId = id(1)
        val JPY: CurrencyCode = (CurrencyCode.parse("JPY") as DomainResult.Success).value
        val ROW = JournalTransactionView(ID, id(2), TransactionKind.EXPENSE, TransactionLifecycleState.ACTIVE, NOW, LocalDate.of(2026, 8, 3), "Meals", "Local shop", "Cash", 1280, JPY, null, null, listOf("attachment"), null, TransactionSource.MANUAL)
        val HISTORY = listOf(
            JournalRevisionView(id(4), 2, RevisionAction.EDIT, TransactionLifecycleState.ACTIVE, NOW, NOW, "Meals", "Cash", 1280, JPY, listOf("amount")),
            JournalRevisionView(id(3), 1, RevisionAction.CREATE, TransactionLifecycleState.ACTIVE, NOW.minusSeconds(60), NOW, "Meals", "Cash", 1000, JPY, listOf("created")),
        )
        val DETAIL = JournalDetailView(ROW, NOW.minusSeconds(60), NOW, "Asia/Tokyo", "1000+280", "private note", "Local shop", "Trip", "Station", listOf("receipt.pdf"), "included", "CONSUMPTION_EXPENSE", listOf(JournalFxEvidenceView(JPY, JPY, "1", "identity", NOW, false, false)), listOf("REFUND"), listOf("Cash:credit:1280 JPY"), "MANUAL", null, 1)
        val ACTIONS: (JournalScreenAction) -> Unit = {}
        val EXPECTED = linkedMapOf(
            "JRN-001" to setOf("loading", "content", "empty", "error", "refreshing"),
            "JRN-002" to setOf("idle", "typing", "results", "empty", "error"),
            "JRN-003" to setOf("editing", "invalid", "applying"),
            "JRN-004" to setOf("content", "empty"),
            "JRN-005" to setOf("someSelected", "allMatchingSelected", "queryChanged"),
            "JRN-006" to setOf("editing", "validating", "committing", "failed", "succeeded"),
            "JRN-007" to setOf("loading", "active", "trashed", "dependencyWarning", "notFound"),
            "JRN-008" to setOf("content", "singleRevision"),
            "JRN-009" to setOf("content", "loading"),
            "JRN-010" to setOf("content", "noDependencies", "blocked"),
            "JRN-011" to setOf("content", "empty", "selection"),
            "JRN-012" to setOf("eligible", "notEligible", "verifying", "purging"),
        )
        fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1515L, value))
    }
}
