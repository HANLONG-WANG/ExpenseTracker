package app.ledger.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.security.BookSessionState
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.feature.record.OrdinaryRecordLoadState
import app.ledger.feature.onboarding.OnboardingLanguage
import app.ledger.feature.onboarding.OnboardingStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class A11yP34TalkBackDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    @SdkSuppress(minSdkVersion = 36)
    fun talkBackServiceCompletesTheCriticalRecordNavigationFlow() = runBlocking {
        val viewModel = ViewModelProvider(composeRule.activity)[AppRootViewModel::class.java]
        val initial = withTimeout(DEVICE_TIMEOUT_MILLIS) {
            viewModel.rootState.first { state ->
                state is AppRootState.Onboarding || (state as? AppRootState.Session)?.state is BookSessionState.Ready
            }
        }
        if ((initial as? AppRootState.Session)?.state is BookSessionState.Ready) {
            composeRule.runOnUiThread {
                viewModel.clearLocalBookData()
                viewModel.sensitiveSettingsAuthenticationSucceeded()
            }
            awaitStep(viewModel, OnboardingStep.LANGUAGE)
        }
        completeOnboarding(viewModel)
        withTimeout(DEVICE_TIMEOUT_MILLIS) {
            viewModel.rootState.first { state -> (state as? AppRootState.Session)?.state is BookSessionState.Ready }
        }

        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val packageManager = instrumentation.targetContext.packageManager
        assertTrue(
            "The API 36 accessibility device must contain the real TalkBack package",
            packageManager.getInstalledPackages(0).any { it.packageName == TALKBACK_PACKAGE },
        )
        enableTalkBackForDedicatedFlow()
        assertTrue("The dedicated device flow could not enable TalkBack", waitForTalkBack())

        composeRule.waitUntil(DEVICE_TIMEOUT_MILLIS) {
            runCatching { composeRule.onNodeWithText(CATEGORY_NAME, substring = true).fetchSemanticsNode() }.isSuccess
        }
        performAccessibilityClick(CATEGORY_NAME)
        composeRule.waitUntil(DEVICE_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }

        // The editor's category control must enter the same production full-grid destination,
        // and choosing there must return to this complete editor rather than a dropdown shortcut.
        performAccessibilityClick(CATEGORY_NAME)
        composeRule.waitUntil(DEVICE_TIMEOUT_MILLIS) {
            viewModel.navigator.currentKey.contract.screenId.value == "REC-004"
        }
        composeRule.onNodeWithTag(LedgerTestTags.CATEGORY_GRID).assertExists()
        performAccessibilityClick(CATEGORY_NAME)
        composeRule.waitUntil(DEVICE_TIMEOUT_MILLIS) {
            viewModel.navigator.currentKey.contract.screenId.value == "REC-003"
        }

        // Exercise the actual amount field's parser, validation focus path and half-even currency
        // rounding before committing through the production fixed save action.
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("1/0")
        performAccessibilityClick("Save")
        composeRule.onNodeWithTag(LedgerTestTags.RECORD_VALIDATION).assertExists()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("100/3")
        composeRule.waitUntil(DEVICE_TIMEOUT_MILLIS) {
            ((viewModel.ordinaryRecord.value as? OrdinaryRecordLoadState.Content)?.editor?.draft?.resultMinor) == 33L
        }
        assertEquals(
            33L,
            (viewModel.ordinaryRecord.value as OrdinaryRecordLoadState.Content).editor?.draft?.resultMinor,
        )
        performAccessibilityClick("Save")
        composeRule.waitUntil(DEVICE_TIMEOUT_MILLIS) {
            viewModel.navigator.currentKey.contract.screenId.value == "REC-001"
        }
        composeRule.onNodeWithText(CATEGORY_NAME, substring = true).assertExists()
        composeRule.onNodeWithText("Record").assertExists()
        assertTrue("TalkBack stopped during the record flow", waitForTalkBack())

        composeRule.runOnUiThread {
            viewModel.clearLocalBookData()
            viewModel.sensitiveSettingsAuthenticationSucceeded()
        }
    }

    private suspend fun completeOnboarding(viewModel: AppRootViewModel) {
        while (true) {
            val root = viewModel.rootState.value
            val state = (root as? AppRootState.Onboarding)?.state ?: return
            composeRule.runOnUiThread {
                when (state.step) {
                    OnboardingStep.LANGUAGE -> viewModel.selectLanguage(OnboardingLanguage.ENGLISH)
                    OnboardingStep.BASE_CURRENCY -> viewModel.selectCurrency("JPY")
                    OnboardingStep.TIME_ZONE -> viewModel.selectZone("Asia/Tokyo")
                    OnboardingStep.PRIVACY_POLICY -> viewModel.setPrivacyAccepted(true)
                    OnboardingStep.TELEMETRY -> {
                        viewModel.setTelemetry(false)
                        viewModel.setCrashReporting(false)
                    }
                    OnboardingStep.APP_LOCK, OnboardingStep.BACKUP -> Unit
                    OnboardingStep.ACCOUNT -> viewModel.updateAccountName(ACCOUNT_NAME)
                    OnboardingStep.CATEGORY -> viewModel.updateCategoryName(CATEGORY_NAME)
                    OnboardingStep.COMPLETE -> Unit
                }
                if (state.step in setOf(OnboardingStep.APP_LOCK, OnboardingStep.BACKUP)) {
                    viewModel.onboardingSkip()
                } else {
                    viewModel.onboardingNext()
                }
            }
            if (state.step == OnboardingStep.COMPLETE) return
            withTimeout(DEVICE_TIMEOUT_MILLIS) {
                viewModel.rootState.first { next ->
                    (next as? AppRootState.Onboarding)?.state?.let {
                        it.step != state.step && it.renderState != app.ledger.feature.onboarding.OnboardingRenderState.SUBMITTING
                    } == true || (next as? AppRootState.Session)?.state is BookSessionState.Ready
                }
            }
        }
    }

    private suspend fun awaitStep(viewModel: AppRootViewModel, step: OnboardingStep) {
        withTimeout(DEVICE_TIMEOUT_MILLIS) {
            viewModel.rootState.first { state -> (state as? AppRootState.Onboarding)?.state?.step == step }
        }
    }

    private fun performAccessibilityClick(label: String) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        repeat(SERVICE_POLL_ATTEMPTS) {
            composeRule.waitForIdle()
            val labeled = findAccessibilityNode(automation.rootInActiveWindow, label)
            var actionable = labeled
            while (actionable != null && !actionable.isClickable) actionable = actionable.parent
            if (actionable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                composeRule.waitForIdle()
                return
            }
            SystemClock.sleep(SERVICE_POLL_MILLIS)
        }
        throw AssertionError(
            "TalkBack-accessible action was not independently clickable: $label; " +
                accessibilityTreeSummary(automation.rootInActiveWindow),
        )
    }

    private fun enableTalkBackForDedicatedFlow() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.executeShellCommand("settings put secure enabled_accessibility_services $TALKBACK_COMPONENT").close()
        automation.executeShellCommand("settings put secure accessibility_enabled 1").close()
    }

    private fun findAccessibilityNode(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        if (root != null) {
            if (
                root.text?.toString()?.contains(label) == true ||
                root.contentDescription?.toString()?.contains(label) == true ||
                root.hintText?.toString()?.contains(label) == true
            ) {
                result = root
            } else {
                var index = 0
                while (result == null && index < root.childCount) {
                    result = findAccessibilityNode(root.getChild(index), label)
                    index += 1
                }
            }
        }
        return result
    }

    private fun accessibilityTreeSummary(root: AccessibilityNodeInfo?): String {
        val entries = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || entries.size >= MAXIMUM_DIAGNOSTIC_NODES) return
            val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString())
                .joinToString("|")
            if (label.isNotBlank() || node.isClickable) {
                entries += "$depth:${node.className}:$label:click=${node.isClickable}:visible=${node.isVisibleToUser}"
            }
            repeat(node.childCount) { index -> visit(node.getChild(index), depth + 1) }
        }
        visit(root, 0)
        return entries.joinToString(";")
    }

    @After
    fun disableTalkBackBeforePixelAndRemainingUiSuites() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.executeShellCommand("settings delete secure enabled_accessibility_services").close()
        automation.executeShellCommand("settings put secure accessibility_enabled 0").close()
        repeat(SERVICE_POLL_ATTEMPTS) {
            if (!waitForTalkBack(singleAttempt = true)) return
            SystemClock.sleep(SERVICE_POLL_MILLIS)
        }
        assertTrue("TalkBack remained active after the dedicated flow", !waitForTalkBack(singleAttempt = true))
    }

    companion object {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()

        private fun waitForTalkBack(singleAttempt: Boolean = false): Boolean {
            val manager = instrumentation.targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            repeat(if (singleAttempt) 1 else SERVICE_POLL_ATTEMPTS) {
                if (manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                        .any { it.resolveInfo.serviceInfo.packageName == TALKBACK_PACKAGE }
                ) {
                    return true
                }
                SystemClock.sleep(SERVICE_POLL_MILLIS)
            }
            return false
        }

        const val TALKBACK_PACKAGE = "com.google.android.marvin.talkback"
        const val TALKBACK_COMPONENT =
            "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
        const val SERVICE_POLL_ATTEMPTS = 30
        const val SERVICE_POLL_MILLIS = 200L
        const val MAXIMUM_DIAGNOSTIC_NODES = 80
        const val DEVICE_TIMEOUT_MILLIS = 60_000L
        const val ACCOUNT_NAME = "TalkBack test cash"
        const val CATEGORY_NAME = "TalkBack test expense"
    }
}
