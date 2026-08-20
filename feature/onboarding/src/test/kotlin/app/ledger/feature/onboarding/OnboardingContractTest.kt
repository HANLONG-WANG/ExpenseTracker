package app.ledger.feature.onboarding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingContractTest {
    @Test
    fun frozenTenStepOrderAndOptionalRulesAreExact() {
        assertEquals(
            listOf("ONB-001", "ONB-002", "ONB-003", "ONB-004", "ONB-005", "ONB-006", "ONB-007", "ONB-008", "ONB-009", "ONB-010"),
            OnboardingStep.entries.map(OnboardingStep::screenId),
        )
        assertEquals(
            setOf(OnboardingStep.APP_LOCK, OnboardingStep.BACKUP, OnboardingStep.ACCOUNT, OnboardingStep.CATEGORY),
            OnboardingStep.entries.filter(OnboardingStep::optional).toSet(),
        )
        assertFalse(OnboardingStep.COMPLETE.optional)
    }

    @Test
    fun requiredStepsFailClosedAndExplicitTelemetryChoiceCanContinue() {
        assertEquals("LANGUAGE_REQUIRED", OnboardingValidator.errorCode(OnboardingUiState(step = OnboardingStep.LANGUAGE)))
        assertNull(OnboardingValidator.errorCode(OnboardingUiState(step = OnboardingStep.BASE_CURRENCY)))
        assertEquals("TIME_ZONE_REQUIRED", OnboardingValidator.errorCode(OnboardingUiState(step = OnboardingStep.TIME_ZONE)))
        assertEquals("PRIVACY_CONSENT_REQUIRED", OnboardingValidator.errorCode(OnboardingUiState(step = OnboardingStep.PRIVACY_POLICY)))
        assertNull(OnboardingValidator.errorCode(OnboardingUiState(step = OnboardingStep.TELEMETRY)))
        assertNull(OnboardingValidator.errorCode(OnboardingUiState(step = OnboardingStep.COMPLETE)))
    }

    @Test
    fun recoveryValidationAndDiagnosticsNeverExposePlaintext() {
        val short = OnboardingUiState(
            step = OnboardingStep.BACKUP,
            recoveryPassword = "short",
            recoveryPasswordConfirmation = "short",
        )
        assertEquals("RECOVERY_PASSWORD_TOO_SHORT", OnboardingValidator.errorCode(short))
        val mismatch = short.copy(recoveryPassword = "twelve-characters", recoveryPasswordConfirmation = "different-value")
        assertEquals("RECOVERY_PASSWORD_MISMATCH", OnboardingValidator.errorCode(mismatch))
        val valid = mismatch.copy(recoveryPasswordConfirmation = "twelve-characters")
        assertNull(OnboardingValidator.errorCode(valid))
        assertFalse(valid.toString().contains("twelve-characters"))
        assertTrue(valid.toString().contains("<redacted>"))
    }

    @Test
    fun allThirtyOnboardingRenderCombinationsAreRepresentable() {
        val states = OnboardingStep.entries.flatMap { step ->
            OnboardingRenderState.entries.map { render -> OnboardingUiState(step = step, renderState = render) }
        }
        assertEquals(30, states.size)
        assertEquals(30, states.map { it.step to it.renderState }.toSet().size)
    }
}
