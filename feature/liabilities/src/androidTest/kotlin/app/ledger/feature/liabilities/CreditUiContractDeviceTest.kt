@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package app.ledger.feature.liabilities

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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.CreditStatementStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class CreditUiContractDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun allTwentyNineFrozenRequiredStatesRenderAcrossResponsiveAccessibleLocalizedMatrix() {
        val cases = cases()
        assertEquals(29, cases.size)
        assertEquals(EXPECTED, cases.groupBy(Case::screen).mapValues { (_, values) -> values.map(Case::stateName).toSet() })
        val active = mutableStateOf(cases.first())
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
                    Box(Modifier.size(case.width.dp, 1_900.dp)) {
                        CreditDestination(case.screen, CreditLoadState.Content(case.featureState), case.arguments, CreditDeviceFixtures.actions)
                    }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(case.expectedTag).assertExists()
            composeRule.onRoot().assertExists()
        }
    }

    @Test
    fun activeOverpaymentCandidateFallbackAndPositiveBalanceAreExplicitAtCompactLargeFont() {
        val overpayment = CreditPolicy.validatePayment(
            CreditDeviceFixtures.state("REC-014", CreditPresentation.EDITING).copy(
                draft = CreditDeviceFixtures.state("REC-014", CreditPresentation.EDITING).draft.copy(amount = "901"),
            ),
        )
        assertEquals(CreditPresentation.OVERPAYMENT_BLOCKED, overpayment.presentation)
        assertEquals(
            CreditPresentation.INELIGIBLE,
            CreditPolicy.autoPresentation(
                CreditDeviceFixtures.account(profile = CreditDeviceFixtures.profile(AutoGenerationMode.FORMAL_TRANSACTION)),
                CreditDeviceFixtures.statement(official = null, sealed = false),
                AutoGenerationMode.FORMAL_TRANSACTION,
            ),
        )
        val positive = CreditDeviceFixtures.state(
            "CRD-001",
            CreditPresentation.POSITIVE_BALANCE,
            CreditDeviceFixtures.snapshot(listOf(CreditDeviceFixtures.account(debt = 0, positive = 200, limit = 100_200))),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 1_600.dp)) {
                        CreditDestination("CRD-001", CreditLoadState.Content(positive), emptyMap(), CreditDeviceFixtures.actions)
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.CREDIT_ACCOUNT_DETAIL).assertExists()
    }

    @Test
    fun creditOverpaymentDisablesProductionSaveAndCannotDispatchAWrite() {
        val overpayment = CreditDeviceFixtures.state("REC-014", CreditPresentation.EDITING).let { state ->
            CreditPolicy.validatePayment(state.copy(draft = state.draft.copy(amount = "901")))
        }
        var writes = 0
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(360.dp, 800.dp)) {
                    LedgerScaffold(
                        fixedAction = {
                            val saveAllowed = CreditPolicy.validatePayment(overpayment).presentation !in setOf(
                                CreditPresentation.VALIDATION_ERROR,
                                CreditPresentation.OVERPAYMENT_BLOCKED,
                            )
                            LedgerSaveFab({ writes += 1 }, enabled = saveAllowed)
                        },
                    ) { padding ->
                        Box(Modifier.padding(padding)) {
                            CreditDestination("REC-014", CreditLoadState.Content(overpayment), emptyMap(), CreditDeviceFixtures.actions)
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(LedgerTestTags.CREDIT_PAYMENT).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.SAVE).assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(0, writes) }
    }

    private fun cases(): List<Case> {
        val empty = CreditDeviceFixtures.snapshot(listOf(CreditDeviceFixtures.account(statements = emptyList())))
        val noLimit = CreditDeviceFixtures.snapshot(listOf(CreditDeviceFixtures.account(limit = null, profile = CreditDeviceFixtures.profile().copy(standardLimitMinor = null))))
        val positive = CreditDeviceFixtures.snapshot(listOf(CreditDeviceFixtures.account(debt = 0, positive = 200, limit = 100_200)))
        val overdue = CreditDeviceFixtures.snapshot(listOf(CreditDeviceFixtures.account(overdue = 550, statements = listOf(CreditDeviceFixtures.statement(status = CreditStatementStatus.OVERDUE)))))
        val raw = listOf(
            Case("REC-014", "editing", LedgerTestTags.CREDIT_PAYMENT, CreditDeviceFixtures.state("REC-014", CreditPresentation.EDITING)),
            Case("REC-014", "overpaymentBlocked", LedgerTestTags.CREDIT_PAYMENT, CreditDeviceFixtures.state("REC-014", CreditPresentation.OVERPAYMENT_BLOCKED)),
            Case("REC-014", "unallocated", LedgerTestTags.CREDIT_PAYMENT, CreditDeviceFixtures.state("REC-014", CreditPresentation.UNALLOCATED)),
            Case("REC-014", "saving", LedgerTestTags.CREDIT_PAYMENT, CreditDeviceFixtures.state("REC-014", CreditPresentation.SAVING)),
            Case("CRD-001", "normal", LedgerTestTags.CREDIT_ACCOUNT_DETAIL, CreditDeviceFixtures.state("CRD-001", CreditPresentation.NORMAL)),
            Case("CRD-001", "overdue", LedgerTestTags.CREDIT_ACCOUNT_DETAIL, CreditDeviceFixtures.state("CRD-001", CreditPresentation.OVERDUE, overdue)),
            Case("CRD-001", "positiveBalance", LedgerTestTags.CREDIT_ACCOUNT_DETAIL, CreditDeviceFixtures.state("CRD-001", CreditPresentation.POSITIVE_BALANCE, positive)),
            Case("CRD-001", "noLimit", LedgerTestTags.CREDIT_ACCOUNT_DETAIL, CreditDeviceFixtures.state("CRD-001", CreditPresentation.NO_LIMIT, noLimit)),
            Case("CRD-001", "noStatements", LedgerTestTags.CREDIT_ACCOUNT_DETAIL, CreditDeviceFixtures.state("CRD-001", CreditPresentation.NO_STATEMENTS, empty, statementId = null)),
            Case("CRD-002", "editing", LedgerTestTags.CREDIT_PROFILE, CreditDeviceFixtures.state("CRD-002", CreditPresentation.EDITING)),
            Case("CRD-002", "validationError", LedgerTestTags.CREDIT_PROFILE, CreditDeviceFixtures.state("CRD-002", CreditPresentation.VALIDATION_ERROR)),
            Case("CRD-003", "content", LedgerTestTags.CREDIT_STATEMENTS, CreditDeviceFixtures.state("CRD-003", CreditPresentation.CONTENT)),
            Case("CRD-003", "empty", LedgerTestTags.CREDIT_STATEMENTS, CreditDeviceFixtures.state("CRD-003", CreditPresentation.EMPTY, empty, statementId = null)),
            Case("CRD-004", "estimatedOnly", LedgerTestTags.CREDIT_STATEMENT_DETAIL, CreditDeviceFixtures.state("CRD-004", CreditPresentation.ESTIMATED_ONLY, statementId = CreditDeviceFixtures.estimatedStatementId)),
            Case("CRD-004", "official", LedgerTestTags.CREDIT_STATEMENT_DETAIL, CreditDeviceFixtures.state("CRD-004", CreditPresentation.OFFICIAL)),
            Case("CRD-004", "sealed", LedgerTestTags.CREDIT_STATEMENT_DETAIL, CreditDeviceFixtures.state("CRD-004", CreditPresentation.SEALED)),
            Case("CRD-004", "overdue", LedgerTestTags.CREDIT_STATEMENT_DETAIL, CreditDeviceFixtures.state("CRD-004", CreditPresentation.OVERDUE, overdue)),
            Case("CRD-004", "paid", LedgerTestTags.CREDIT_STATEMENT_DETAIL, CreditDeviceFixtures.state("CRD-004", CreditPresentation.PAID)),
            Case("CRD-005", "editing", LedgerTestTags.CREDIT_OFFICIAL_STATEMENT, CreditDeviceFixtures.state("CRD-005", CreditPresentation.EDITING)),
            Case("CRD-005", "difference", LedgerTestTags.CREDIT_OFFICIAL_STATEMENT, CreditDeviceFixtures.state("CRD-005", CreditPresentation.DIFFERENCE)),
            Case("CRD-005", "saving", LedgerTestTags.CREDIT_OFFICIAL_STATEMENT, CreditDeviceFixtures.state("CRD-005", CreditPresentation.SAVING)),
            Case("CRD-006", "content", LedgerTestTags.CREDIT_ASSIGNMENT, CreditDeviceFixtures.state("CRD-006", CreditPresentation.CONTENT)),
            Case("CRD-006", "sealedWarning", LedgerTestTags.CREDIT_ASSIGNMENT, CreditDeviceFixtures.state("CRD-006", CreditPresentation.SEALED_WARNING)),
            Case("CRD-007", "editing", LedgerTestTags.CREDIT_PAYMENT_ALLOCATION, CreditDeviceFixtures.state("CRD-007", CreditPresentation.EDITING)),
            Case("CRD-007", "balanced", LedgerTestTags.CREDIT_PAYMENT_ALLOCATION, CreditDeviceFixtures.state("CRD-007", CreditPresentation.BALANCED)),
            Case("CRD-007", "mismatch", LedgerTestTags.CREDIT_PAYMENT_ALLOCATION, CreditDeviceFixtures.state("CRD-007", CreditPresentation.MISMATCH)),
            Case("CRD-008", "eligible", LedgerTestTags.CREDIT_AUTO_PAYMENT, CreditDeviceFixtures.state("CRD-008", CreditPresentation.ELIGIBLE)),
            Case("CRD-008", "ineligible", LedgerTestTags.CREDIT_AUTO_PAYMENT, CreditDeviceFixtures.state("CRD-008", CreditPresentation.INELIGIBLE)),
            Case("CRD-008", "candidateMode", LedgerTestTags.CREDIT_AUTO_PAYMENT, CreditDeviceFixtures.state("CRD-008", CreditPresentation.CANDIDATE_MODE)),
        )
        return raw.mapIndexed { index, case ->
            case.copy(
                width = listOf(320, 360, 480)[index % 3],
                fontScale = listOf(1f, 1.3f, 2f)[index % 3],
                locale = listOf("zh-CN", "ja-JP", "en-US")[index % 3],
                theme = if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
            )
        }
    }

    private data class Case(
        val screen: String,
        val stateName: String,
        val expectedTag: String,
        val featureState: CreditFeatureState,
        val arguments: Map<String, String> = when {
            screen == "REC-014" -> emptyMap()
            screen in setOf("CRD-001", "CRD-002", "CRD-003", "CRD-008") -> mapOf("accountId" to CreditDeviceFixtures.accountId.toString())
            screen in setOf("CRD-004", "CRD-005") -> mapOf("statementId" to (featureState.selectedStatementId ?: CreditDeviceFixtures.statementId).toString())
            else -> emptyMap()
        },
        val width: Int = 360,
        val fontScale: Float = 1f,
        val locale: String = "en-US",
        val theme: ThemeMode = ThemeMode.LIGHT,
    )

    private companion object {
        val EXPECTED = linkedMapOf(
            "REC-014" to setOf("editing", "overpaymentBlocked", "unallocated", "saving"),
            "CRD-001" to setOf("normal", "overdue", "positiveBalance", "noLimit", "noStatements"),
            "CRD-002" to setOf("editing", "validationError"),
            "CRD-003" to setOf("content", "empty"),
            "CRD-004" to setOf("estimatedOnly", "official", "sealed", "overdue", "paid"),
            "CRD-005" to setOf("editing", "difference", "saving"),
            "CRD-006" to setOf("content", "sealedWarning"),
            "CRD-007" to setOf("editing", "balanced", "mismatch"),
            "CRD-008" to setOf("eligible", "ineligible", "candidateMode"),
        )
    }
}
