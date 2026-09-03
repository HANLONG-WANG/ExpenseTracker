package app.ledger.app

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

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

            awaitDeviceState("session ready after onboarding") {
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

            scenario.assertP37RouteStateContainment(viewModel)

            // Drive the same ViewModel actions that the production Compose destinations dispatch,
            // through the real Navigation 3 stack and SQLCipher write path. The optional location
            // prefetch is intentionally unresolved; its timeout/failure must never block the save.
            val recordHome = awaitDeviceState("initial Record content") {
                viewModel.ordinaryRecord.first { it is OrdinaryRecordLoadState.Content } as OrdinaryRecordLoadState.Content
            }
            val categoryId = recordHome.snapshot.references.categories.single { it.name == "Device test expense" }.id
            scenario.assertWidgetQuickEntryOpensPrefilledFormWithoutSubmittingMutation(viewModel, categoryId)
            scenario.action {
                viewModel.recordExpression("1250")
                viewModel.saveOrdinaryRecord()
            }
            awaitDeviceState("ordinary save settlement") {
                viewModel.ordinaryRecordPending.first { pending -> !pending }
            }
            val settledRecord = viewModel.ordinaryRecord.value
            assertTrue(
                "ordinary save did not return Content at REC-001: route=${viewModel.navigator.currentKey.contract.screenId.value}, state=$settledRecord",
                viewModel.navigator.currentKey.contract.screenId.value == "REC-001" &&
                    settledRecord is OrdinaryRecordLoadState.Content,
            )
            val savedRecordHome = settledRecord as OrdinaryRecordLoadState.Content
            assertEquals(RecordTab.EXPENSE, savedRecordHome.tab)
            assertEquals(categoryId, savedRecordHome.selectedCategoryId)

            scenario.action { viewModel.openRecordEditor(RecordEditorMode.CREATE, OrdinaryDirection.EXPENSE, categoryId, null) }
            awaitDeviceState("Record editor route") {
                viewModel.ordinaryRecord.first { viewModel.navigator.currentKey.contract.screenId.value == "REC-003" }
            }
            scenario.action {
                viewModel.navigateRecord(
                    "REC-004",
                    mapOf("selectedId" to categoryId),
                    mapOf("direction" to OrdinaryDirection.EXPENSE.name),
                )
            }
            awaitDeviceState("Record category route content") {
                viewModel.ordinaryRecord.first {
                    viewModel.navigator.currentKey.contract.screenId.value == "REC-004" &&
                        (it as? OrdinaryRecordLoadState.Content)?.snapshot?.references?.categories?.isNotEmpty() == true
                }
            }
            assertEquals("REC-004", viewModel.navigator.currentKey.contract.screenId.value)
            scenario.action { viewModel.requestRootBack() }

            // A short background transition keeps all in-memory stack histories intact.
            viewModel.selectRootTopLevel(TopLevelDestination.JOURNAL)
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
        awaitDeviceState("onboarding step ${expected.name}") {
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
        action { viewModel.handleDeepLink(quickEntry.data) }
        val editor = awaitDeviceState("widget quick-entry editor") {
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

    private suspend fun ActivityScenario<MainActivity>.assertP37RouteStateContainment(viewModel: AppRootViewModel) {
        val shellCompositions = AtomicInteger()
        val routeCompositions = AtomicInteger()
        P37ComposeRecompositionProbe.installForTest { scope, _ ->
            when (scope) {
                P37ComposeRecompositionProbe.Scope.READY_SHELL -> shellCompositions.incrementAndGet()
                P37ComposeRecompositionProbe.Scope.ROUTE -> routeCompositions.incrementAndGet()
            }
        }
        try {
            action { viewModel.selectRootTopLevel(TopLevelDestination.JOURNAL) }
            awaitDeviceState("Journal content for route containment") {
                viewModel.journal.first { it is app.ledger.feature.journal.JournalLoadState.Content }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            action { viewModel.selectRootTopLevel(TopLevelDestination.RECORD) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertTrue("observable navigator must recompose the Ready shell", shellCompositions.get() >= 2)
            assertTrue("observable navigator must replace the route composition", routeCompositions.get() >= 2)
            val shellBeforeOffscreenEmission = shellCompositions.get()
            val routeBeforeOffscreenEmission = routeCompositions.get()

            action { viewModel.updateJournalSearch("p37-offscreen-probe") }
            awaitDeviceState("offscreen Journal search state") {
                viewModel.journal.first {
                    (it as? app.ledger.feature.journal.JournalLoadState.Content)?.searchText == "p37-offscreen-probe"
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(shellBeforeOffscreenEmission, shellCompositions.get())
            assertEquals(routeBeforeOffscreenEmission, routeCompositions.get())
        } finally {
            P37ComposeRecompositionProbe.clearForTest()
            action { viewModel.updateJournalSearch("") }
        }
    }

    private companion object {
        const val DEVICE_TIMEOUT_MILLIS = 60_000L
    }

    private suspend fun <T> awaitDeviceState(label: String, block: suspend () -> T): T = try {
        withTimeout(DEVICE_TIMEOUT_MILLIS) { block() }
    } catch (failure: TimeoutCancellationException) {
        throw AssertionError("Timed out waiting for $label", failure)
    }
}
