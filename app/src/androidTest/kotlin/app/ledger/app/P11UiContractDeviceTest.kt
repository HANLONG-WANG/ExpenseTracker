package app.ledger.app

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.security.MaintenanceReason
import app.ledger.core.security.RecoveryDiagnosticCode
import app.ledger.feature.onboarding.OnboardingRenderState
import app.ledger.feature.onboarding.OnboardingScreen
import app.ledger.feature.onboarding.OnboardingScreenAction
import app.ledger.feature.onboarding.OnboardingStep
import app.ledger.feature.onboarding.OnboardingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class P11UiContractDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allSixtyFiveFrozenGlobalAndOnboardingStatesRender() {
        val targets = globalTargets() + OnboardingStep.entries.flatMap { step ->
            OnboardingRenderState.entries.map { render -> RenderTarget.Onboarding(step, render) }
        }
        assertEquals(65, targets.size)
        val active = mutableStateOf(targets.first())
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                Box(Modifier.testTag(STATE_HOST_TAG)) { renderState(active.value) }
            }
        }
        targets.forEach { target ->
            composeRule.runOnIdle { active.value = target }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(STATE_HOST_TAG).assertExists()
        }
    }

    @Test
    fun recoveryPasswordIsAbsentFromRenderedAndSemanticTrees() {
        val secret = "unique-recovery-secret-2048"
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                OnboardingScreen(
                    validOnboarding(OnboardingStep.BACKUP, OnboardingRenderState.CONTENT).copy(
                        recoveryPassword = secret,
                        recoveryPasswordConfirmation = secret,
                    ),
                    noOpActions,
                )
            }
        }
        composeRule.onNodeWithText(secret, substring = true, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(LedgerTestTags.ONBOARDING_ROOT).assertExists()
    }

    @Test
    fun constrainedWidthsFontScalesThemesAndDynamicBoundaryDoNotClipRoot() {
        val cases = listOf(
            RenderCase(320, 1f, ThemeMode.LIGHT, false),
            RenderCase(320, 2f, ThemeMode.DARK, false),
            RenderCase(360, 1.3f, ThemeMode.LIGHT, true),
            RenderCase(480, 1f, ThemeMode.DARK, true),
        )
        val active = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = active.value
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f, case.fontScale)) {
                LedgerTheme(case.theme, dynamicColor = case.dynamic, reduceMotion = case.fontScale == 2f) {
                    Box(Modifier.size(case.width.dp, 2000.dp).testTag(MATRIX_TAG)) {
                        OnboardingScreen(validOnboarding(OnboardingStep.BACKUP, OnboardingRenderState.VALIDATION_ERROR), noOpActions)
                    }
                }
            }
        }
        cases.forEach { case ->
            composeRule.runOnIdle { active.value = case }
            composeRule.waitForIdle()
            val bounds = composeRule.onNodeWithTag(MATRIX_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals(case.width.toFloat(), bounds.width, .5f)
            assertTrue(bounds.height > 0f)
        }
    }

    @Test
    fun simplifiedChineseJapaneseAndEnglishAppResourcesRender() {
        val locales = listOf(
            Locale.SIMPLIFIED_CHINESE to "应用已锁定",
            Locale.JAPANESE to "アプリはロックされています",
            Locale.ENGLISH to "App locked",
        )
        val active = mutableStateOf(locales.first())
        composeRule.setContent {
            val context = localizedTargetContext(active.value.first)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                    LockScreen(AppAuthenticationState.CREDENTIAL_ONLY, onAuthenticate = {})
                }
            }
        }
        locales.forEach { localeCase ->
            composeRule.runOnIdle { active.value = localeCase }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(localeCase.second).assertExists()
        }
    }

    private fun globalTargets(): List<RenderTarget.Global> = buildList {
        listOf("uninitialized", "locked", "opening", "maintenance", "recoveryRequired", "ready").forEach { add(RenderTarget.Global("G-001", it)) }
        AppAuthenticationState.entries.forEach { add(RenderTarget.Global("G-002", it.name)) }
        OpeningPresentation.entries.forEach { add(RenderTarget.Global("G-003", it.name)) }
        MaintenancePresentation.entries.forEach { add(RenderTarget.Global("G-004", it.name)) }
        RecoveryPresentation.entries.forEach { add(RenderTarget.Global("G-005", it.name)) }
        MorePresentation.entries.forEach { add(RenderTarget.Global("G-006", it.name)) }
        OperationCenterPresentation.entries.forEach { add(RenderTarget.Global("G-007", it.name)) }
        listOf("content", "notFound").forEach { add(RenderTarget.Global("G-008", it)) }
    }

    @androidx.compose.runtime.Composable
    private fun renderState(target: RenderTarget) {
        when (target) {
            is RenderTarget.Onboarding -> OnboardingScreen(validOnboarding(target.step, target.render), noOpActions)
            is RenderTarget.Global -> when (target.screen) {
                "G-001" -> when (target.state) {
                    "uninitialized" -> LedgerLoadingState(label = "uninitialized")
                    "locked" -> LockScreen(AppAuthenticationState.CREDENTIAL_ONLY, {})
                    "opening" -> OpeningBookScreen(OpeningPresentation.OPENING, {})
                    "maintenance" -> MaintenanceScreen(MaintenanceReason.CONTROLLED_MAINTENANCE, MaintenancePresentation.RUNNING)
                    "recoveryRequired" -> RecoveryRequiredScreen(
                        RecoveryDiagnosticCode.SCHEMA_INVALID,
                        RecoveryPresentation.NO_BACKUP,
                        {},
                        {},
                        {},
                    )
                    else -> MoreScreen(MorePresentation.CONTENT, {}, {}, {})
                }
                "G-002" -> LockScreen(AppAuthenticationState.valueOf(target.state), {})
                "G-003" -> OpeningBookScreen(OpeningPresentation.valueOf(target.state), {})
                "G-004" -> MaintenanceScreen(MaintenanceReason.CONTROLLED_MAINTENANCE, MaintenancePresentation.valueOf(target.state))
                "G-005" -> RecoveryRequiredScreen(
                    RecoveryDiagnosticCode.SCHEMA_INVALID,
                    RecoveryPresentation.valueOf(target.state),
                    {},
                    {},
                    {},
                )
                "G-006" -> MoreScreen(MorePresentation.valueOf(target.state), {}, {}, {})
                "G-007" -> OperationCenterScreen(OperationCenterPresentation.valueOf(target.state), {})
                "G-008" -> HelpScreen("getting-started".takeIf { target.state == "content" }, {})
            }
        }
    }

    private fun validOnboarding(step: OnboardingStep, render: OnboardingRenderState): OnboardingUiState = OnboardingUiState(
        step = step,
        renderState = render,
        language = app.ledger.feature.onboarding.OnboardingLanguage.SIMPLIFIED_CHINESE,
        baseCurrency = "JPY",
        zoneId = "Asia/Tokyo",
        privacyAccepted = true,
        errorCode = "SETTINGS_WRITE_FAILED".takeIf { render == OnboardingRenderState.VALIDATION_ERROR },
    )

    private fun localizedTargetContext(locale: Locale): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocales(LocaleList(locale)) })
    }

    private sealed interface RenderTarget {
        data class Global(val screen: String, val state: String) : RenderTarget
        data class Onboarding(val step: OnboardingStep, val render: OnboardingRenderState) : RenderTarget
    }

    private data class RenderCase(val width: Int, val fontScale: Float, val theme: ThemeMode, val dynamic: Boolean)

    private val noOpActions: (OnboardingScreenAction) -> Unit = {}

    private companion object {
        const val STATE_HOST_TAG = "p11_contract_state_host"
        const val MATRIX_TAG = "p11_layout_matrix"
    }
}
