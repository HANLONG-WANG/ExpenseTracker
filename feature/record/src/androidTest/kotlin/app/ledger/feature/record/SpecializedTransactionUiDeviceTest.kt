@file:Suppress("LongMethod", "LongParameterList", "MaxLineLength")

package app.ledger.feature.record

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.UserAccountType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SpecializedTransactionUiDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allThirteenRequiredStatesRenderAcrossWidthsFontsLocalesAndThemes() {
        val cases = requiredStates()
        assertEquals(13, cases.size)
        assertEquals(
            EXPECTED_STATES,
            cases.groupBy(Case::screen).mapValues { (_, values) -> values.map(Case::stateName).toSet() },
        )
        val current = mutableStateOf(cases.first())
        composeRule.setContent {
            val base = LocalContext.current
            val configuration = LocalConfiguration.current
            val locale = Locale.forLanguageTag(current.value.locale)
            val localized = base.createConfigurationContext(Configuration(configuration).apply { setLocales(LocaleList(locale)) })
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(1f, current.value.fontScale),
            ) {
                LedgerTheme(current.value.theme, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(current.value.width.dp, 1_700.dp)) {
                        LedgerScaffold(
                            fixedAction = { LedgerSaveFab({}, submitting = current.value.editor.presentation == SpecializedPresentation.SAVING) },
                        ) { padding ->
                            SpecializedTransactionDestination(current.value.screen, SpecializedTransactionLoadState.Content(current.value.editor), ACTIONS, Modifier.padding(padding))
                        }
                    }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { current.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LedgerTestTags.SPECIALIZED_TRANSACTION_ROOT).assertExists()
            composeRule.onNodeWithTag(LedgerTestTags.SAVE).assertExists().assertHasClickAction()
        }
    }

    @Test
    fun validationKeepsSaveActionReachableAndShowsSameAccountAndMissingFxEvidence() {
        var editor = editor(SpecializedTransactionKind.TRANSFER)
        editor = editor.copy(draft = editor.draft.copy(toAccountId = editor.draft.fromAccountId, outgoingExpression = "100", outgoingMinor = 10_000L))
        editor = SpecializedTransactionPolicy.validate(editor)
        composeRule.setContent {
            LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                LedgerScaffold(fixedAction = { LedgerSaveFab({}) }) { padding ->
                    SpecializedTransactionDestination("REC-013", SpecializedTransactionLoadState.Content(editor), ACTIONS, Modifier.padding(padding))
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.SPECIALIZED_TRANSACTION_ROOT).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.SAVE).assertExists().assertHasClickAction()
    }

    @Test
    fun fxExchangeExposesEffectiveRateAndCostSemantics() {
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                LedgerScaffold(fixedAction = { LedgerSaveFab({}) }) { padding ->
                    SpecializedTransactionDestination(
                        "REC-021",
                        SpecializedTransactionLoadState.Content(editor(SpecializedTransactionKind.FX_EXCHANGE)),
                        ACTIONS,
                        Modifier.padding(padding),
                    )
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.SPECIALIZED_TRANSACTION_FORM)
            .performScrollToNode(hasTestTag(LedgerTestTags.EFFECTIVE_RATE_SUMMARY))
        composeRule.onNodeWithTag(LedgerTestTags.EFFECTIVE_RATE_SUMMARY).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.SPECIALIZED_TRANSACTION_FORM)
            .performScrollToNode(hasTestTag(LedgerTestTags.FX_COST_SECTION))
        composeRule.onNodeWithTag(LedgerTestTags.FX_COST_SECTION).assertExists()
    }

    private fun requiredStates(): List<Case> {
        val transfer = editor(SpecializedTransactionKind.TRANSFER)
        val adjustment = editor(SpecializedTransactionKind.BALANCE_ADJUSTMENT)
        val exchange = editor(SpecializedTransactionKind.FX_EXCHANGE)
        val opening = editor(SpecializedTransactionKind.OPENING_BALANCE)
        val raw = listOf(
            Triple("REC-013", "editing", transfer),
            Triple("REC-013", "sameAccountError", transfer.copy(draft = transfer.draft.copy(toAccountId = transfer.draft.fromAccountId))),
            Triple("REC-013", "fxRequired", transfer.copy(errors = listOf(SpecializedValidationError(SpecializedField.RATE, "FX_EVIDENCE_REQUIRED")))),
            Triple("REC-013", "saving", transfer.copy(presentation = SpecializedPresentation.SAVING)),
            Triple("REC-020", "editing", adjustment),
            Triple("REC-020", "saving", adjustment.copy(presentation = SpecializedPresentation.SAVING)),
            Triple("REC-021", "editing", exchange),
            Triple("REC-021", "sameCurrencyInfo", exchange.copy(draft = exchange.draft.copy(toAccountId = USD_ACCOUNT_2))),
            Triple("REC-021", "rateMismatch", exchange.copy(errors = listOf(SpecializedValidationError(SpecializedField.RATE, "RATE_MISMATCH")))),
            Triple("REC-021", "saving", exchange.copy(presentation = SpecializedPresentation.SAVING)),
            Triple("REC-022", "editing", opening),
            Triple("REC-022", "immutableCurrency", opening),
            Triple("REC-022", "saving", opening.copy(presentation = SpecializedPresentation.SAVING)),
        )
        return raw.mapIndexed { index, (screen, stateName, value) ->
            Case(screen, stateName, value, listOf(320, 360, 480)[index % 3], listOf(1f, 1.3f, 2f)[index % 3], listOf("zh-CN", "ja-JP", "en-US")[index % 3], if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK)
        }
    }

    private fun editor(kind: SpecializedTransactionKind): SpecializedTransactionEditorState = SpecializedTransactionPolicy.create(
        kind,
        snapshot(),
        null,
        NOW,
        ZoneId.of("Asia/Tokyo"),
        Locale.ENGLISH,
    )

    private fun snapshot(): ReferenceDataSnapshot = ReferenceDataSnapshot(
        BOOK, JPY, 4L,
        listOf(
            account(USD_ACCOUNT, "USD wallet", USD, 0),
            account(EUR_ACCOUNT, "EUR wallet", EUR, 1),
            account(USD_ACCOUNT_2, "USD reserve", USD, 2),
        ),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        null, null, true,
    )

    private fun account(id: StableId, name: String, currency: CurrencyCode, sort: Int): AccountReferenceView = AccountReferenceView(
        id, UserAccountType.CASH, name, currency, EntityStatus.ACTIVE, null, null, null, "account", 0, sort, 1, 0, null, null, false, 0,
    )

    private data class Case(val screen: String, val stateName: String, val editor: SpecializedTransactionEditorState, val width: Int, val fontScale: Float, val locale: String, val theme: ThemeMode)

    private companion object {
        val ACTIONS: (SpecializedTransactionScreenAction) -> Unit = {}
        val NOW: Instant = Instant.parse("2026-08-03T04:05:06Z")
        val BOOK: StableId = StableId.fromUuid(UUID(0x1400, 1))
        val USD_ACCOUNT: StableId = StableId.fromUuid(UUID(0x1400, 2))
        val EUR_ACCOUNT: StableId = StableId.fromUuid(UUID(0x1400, 3))
        val USD_ACCOUNT_2: StableId = StableId.fromUuid(UUID(0x1400, 4))
        val USD: CurrencyCode = (CurrencyCode.parse("USD") as DomainResult.Success).value
        val EUR: CurrencyCode = (CurrencyCode.parse("EUR") as DomainResult.Success).value
        val JPY: CurrencyCode = (CurrencyCode.parse("JPY") as DomainResult.Success).value
        val EXPECTED_STATES = mapOf(
            "REC-013" to setOf("editing", "sameAccountError", "fxRequired", "saving"),
            "REC-020" to setOf("editing", "saving"),
            "REC-021" to setOf("editing", "sameCurrencyInfo", "rateMismatch", "saving"),
            "REC-022" to setOf("editing", "immutableCurrency", "saving"),
        )
    }
}
