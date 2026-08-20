package app.ledger.app

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.app.settings.SessionRestorePolicyProto
import app.ledger.core.common.StableId
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SessionGateState
import app.ledger.core.navigation.TopLevelDestination
import app.ledger.core.security.BookSessionState
import app.ledger.feature.onboarding.OnboardingLanguage
import app.ledger.feature.onboarding.OnboardingStep
import app.ledger.feature.record.OrdinaryRecordLoadState
import app.ledger.feature.record.RecordEditorMode
import app.ledger.feature.record.RecordTab
import app.ledger.finance.application.OrdinaryDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Hilt Activity, Proto DataStore, Keystore and SQLCipher bootstrap. */
@RunWith(AndroidJUnit4::class)
class P11RuntimeDeviceTest {
    @Test
    fun realColdStartCompletesTenStepsAndOpensEmptyExpenseRoot() = runBlocking {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var viewModel: AppRootViewModel
            scenario.onActivity { viewModel = ViewModelProvider(it)[AppRootViewModel::class.java] }

            awaitStep(viewModel, OnboardingStep.LANGUAGE)
            scenario.action {
                viewModel.selectLanguage(OnboardingLanguage.ENGLISH)
                viewModel.onboardingNext()
            }
            awaitStep(viewModel, OnboardingStep.BASE_CURRENCY)
            scenario.action {
                viewModel.selectCurrency("JPY")
                viewModel.onboardingNext()
            }
            awaitStep(viewModel, OnboardingStep.TIME_ZONE)
            scenario.action {
                viewModel.selectZone("Asia/Tokyo")
                viewModel.onboardingNext()
            }
            awaitStep(viewModel, OnboardingStep.PRIVACY_POLICY)
            scenario.action {
                viewModel.setPrivacyAccepted(true)
                viewModel.onboardingNext()
            }
            awaitStep(viewModel, OnboardingStep.TELEMETRY)
            scenario.action {
                viewModel.setTelemetry(false)
                viewModel.setCrashReporting(false)
                viewModel.onboardingNext()
            }
            awaitStep(viewModel, OnboardingStep.APP_LOCK)
            scenario.action { viewModel.onboardingSkip() }
            awaitStep(viewModel, OnboardingStep.BACKUP)
            scenario.action { viewModel.onboardingSkip() }
            awaitStep(viewModel, OnboardingStep.ACCOUNT)
            scenario.action {
                viewModel.updateAccountName("Device test cash")
                viewModel.onboardingNext()
            }
            awaitStep(viewModel, OnboardingStep.CATEGORY)
            scenario.action {
                viewModel.updateCategoryName("Device test expense")
                viewModel.onboardingNext()
            }
            awaitStep(viewModel, OnboardingStep.COMPLETE)
            scenario.action { viewModel.onboardingNext() }

            withTimeout(DEVICE_TIMEOUT_MILLIS) {
                viewModel.rootState.first { state ->
                    (state as? AppRootState.Session)?.state is BookSessionState.Ready
                }
            }
            assertEquals(TopLevelDestination.RECORD, viewModel.navigator.currentTopLevel)
            assertEquals("REC-001", viewModel.navigator.currentKey.contract.screenId.value)
            assertEquals("EXPENSE", viewModel.navigator.currentKey.encodedArguments["tab"])

            val saved = viewModel.settings.first { it.onboardingComplete }
            assertEquals(16, saved.bookId.toByteArray().count())
            assertEquals("en", saved.languageTag)
            assertEquals("JPY", saved.baseCurrency)
            assertEquals("Asia/Tokyo", saved.zoneId)
            assertTrue(saved.privacyAccepted)
            assertTrue(saved.diagnosticsChoiceRecorded)
            assertFalse(saved.telemetryEnabled)
            assertFalse(saved.crashReportingEnabled)
            assertFalse(saved.appLockEnabled)
            assertFalse(saved.recoveryPasswordConfigured)
            assertTrue(saved.firstAccountCreated)
            assertTrue(saved.firstCategoryCreated)
            assertEquals(
                SessionRestorePolicyProto.SESSION_RESTORE_SHORT_BACKGROUND,
                saved.restorePolicy,
            )

            // Drive the same ViewModel actions that the production Compose destinations dispatch,
            // through the real Navigation 3 stack and SQLCipher write path. The optional location
            // prefetch is intentionally unresolved; its timeout/failure must never block the save.
            val recordHome = withTimeout(DEVICE_TIMEOUT_MILLIS) {
                viewModel.ordinaryRecord.first { it is OrdinaryRecordLoadState.Content } as OrdinaryRecordLoadState.Content
            }
            val categoryId = recordHome.snapshot.references.categories.single { it.name == "Device test expense" }.id
            scenario.assertWidgetQuickEntryOpensPrefilledFormWithoutSubmittingMutation(viewModel, categoryId)
            scenario.action {
                viewModel.recordExpression("1250")
                viewModel.saveOrdinaryRecord()
            }
            val savedRecordHome = withTimeout(DEVICE_TIMEOUT_MILLIS) {
                viewModel.ordinaryRecord.first {
                    viewModel.navigator.currentKey.contract.screenId.value == "REC-001" &&
                        (it as? OrdinaryRecordLoadState.Content)?.selectedCategoryId == categoryId
                } as OrdinaryRecordLoadState.Content
            }
            assertEquals(RecordTab.EXPENSE, savedRecordHome.tab)
            assertEquals(categoryId, savedRecordHome.selectedCategoryId)

            scenario.action { viewModel.openRecordEditor(RecordEditorMode.CREATE, OrdinaryDirection.EXPENSE, categoryId, null) }
            withTimeout(DEVICE_TIMEOUT_MILLIS) {
                viewModel.ordinaryRecord.first { viewModel.navigator.currentKey.contract.screenId.value == "REC-003" }
            }
            scenario.action {
                viewModel.navigateRecord(
                    "REC-004",
                    mapOf("selectedId" to categoryId),
                    mapOf("direction" to OrdinaryDirection.EXPENSE.name),
                )
            }
            withTimeout(DEVICE_TIMEOUT_MILLIS) {
                viewModel.ordinaryRecord.first {
                    viewModel.navigator.currentKey.contract.screenId.value == "REC-004" &&
                        (it as? OrdinaryRecordLoadState.Content)?.snapshot?.references?.categories?.isNotEmpty() == true
                }
            }
            assertEquals("REC-004", viewModel.navigator.currentKey.contract.screenId.value)
            scenario.action { viewModel.requestRootBack() }

            // A short background transition keeps all in-memory stack histories intact.
            viewModel.navigator.select(TopLevelDestination.JOURNAL)
            viewModel.navigator.navigate(
                app.ledger.core.navigation.LedgerRouteContract.destination(ScreenId("G-007")),
                SessionGateState.READY,
            )
            viewModel.onApplicationBackgrounded()
            viewModel.onApplicationForegrounded()
            assertEquals(TopLevelDestination.JOURNAL, viewModel.navigator.currentTopLevel)
            assertEquals("G-007", viewModel.navigator.currentKey.contract.screenId.value)

            // Leave the device test with no locally persisted book or recovery material.
            viewModel.clearLocalBookData()
            // Authentication behavior is exercised with a real credential in the P32
            // security suite; this callback completes only this test's cleanup request.
            viewModel.sensitiveSettingsAuthenticationSucceeded()
            awaitStep(viewModel, OnboardingStep.LANGUAGE)
        }
    }

    private suspend fun awaitStep(viewModel: AppRootViewModel, expected: OnboardingStep) {
        withTimeout(DEVICE_TIMEOUT_MILLIS) {
            viewModel.rootState.first { state ->
                (state as? AppRootState.Onboarding)?.state?.let {
                    it.step == expected && it.renderState != app.ledger.feature.onboarding.OnboardingRenderState.SUBMITTING
                } == true
            }
        }
    }

    private fun ActivityScenario<MainActivity>.action(block: () -> Unit) {
        onActivity { block() }
    }

    private suspend fun ActivityScenario<MainActivity>.assertWidgetQuickEntryOpensPrefilledFormWithoutSubmittingMutation(
        viewModel: AppRootViewModel,
        categoryId: StableId,
    ) {
        val quickEntry = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("ledger://widget/quick/CATEGORY/EXPENSE/$categoryId"),
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        onActivity { it.startActivity(quickEntry) }
        val editor = withTimeout(DEVICE_TIMEOUT_MILLIS) {
            viewModel.ordinaryRecord.first {
                viewModel.navigator.currentKey.contract.screenId.value == "REC-003" &&
                    (it as? OrdinaryRecordLoadState.Content)?.editor != null
            } as OrdinaryRecordLoadState.Content
        }.editor!!
        assertEquals(RecordEditorMode.CREATE, editor.mode)
        assertEquals(categoryId, editor.draft.categoryId)
        assertEquals("", editor.draft.expression)
        assertFalse(editor.draft.dirty)
        assertFalse(viewModel.ordinaryRecordPending.value)
    }

    private companion object {
        const val DEVICE_TIMEOUT_MILLIS = 60_000L
    }
}
