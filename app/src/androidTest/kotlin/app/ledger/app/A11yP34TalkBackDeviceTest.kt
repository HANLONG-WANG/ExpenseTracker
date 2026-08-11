package app.ledger.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerNavigationBar
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerTopLevel
import app.ledger.core.designsystem.ThemeMode
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class A11yP34TalkBackDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun talkBackServiceCompletesTheCriticalRecordNavigationFlow() {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val packageManager = instrumentation.targetContext.packageManager
        assertTrue(
            "The API 36 accessibility device must contain the real TalkBack package",
            packageManager.getInstalledPackages(0).any { it.packageName == TALKBACK_PACKAGE },
        )
        assertTrue("P34 device preparation did not enable TalkBack", waitForTalkBack())
        val step = mutableStateOf(RecordStep.CATEGORY)
        val amount = mutableStateOf("100")
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                LedgerScaffold(
                    bottomBar = {
                        LedgerNavigationBar(LedgerTopLevel.RECORD, onSelected = {})
                    },
                    fixedAction = if (step.value == RecordStep.FORM) {
                        {
                            LedgerSaveFab(
                                onClick = { step.value = RecordStep.SAVED },
                                modifier = Modifier.testTag(SAVE_TAG),
                            )
                        }
                    } else {
                        null
                    },
                    formContent = true,
                ) { _ ->
                    Column(Modifier.fillMaxSize()) {
                        when (step.value) {
                            RecordStep.CATEGORY -> LedgerButton(
                                text = "Food and dining",
                                onClick = { step.value = RecordStep.FORM },
                                modifier = Modifier.testTag(CATEGORY_TAG),
                            )
                            RecordStep.FORM -> LedgerTextField(
                                value = amount.value,
                                onValueChange = { amount.value = it },
                                label = "Amount",
                                modifier = Modifier.testTag(AMOUNT_TAG),
                            )
                            RecordStep.SAVED -> LedgerButton(
                                text = "Recorded",
                                onClick = {},
                                modifier = Modifier.testTag(RECORDED_TAG),
                            )
                        }
                    }
                }
            }
        }

        performAccessibilityClick("Food and dining")
        composeRule.onNodeWithTag(AMOUNT_TAG).assertExists()
        performAccessibilityClick("Save")
        composeRule.onNodeWithTag(RECORDED_TAG).assertExists()
        composeRule.onNodeWithTag(LedgerTestTags.BOTTOM_NAVIGATION).assertExists()
        composeRule.onNodeWithText("Record").assertExists()
        assertTrue("TalkBack stopped during the record flow", waitForTalkBack())
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

    private fun findAccessibilityNode(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        if (root != null) {
            if (root.text?.toString() == label || root.contentDescription?.toString() == label || root.hintText?.toString() == label) {
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

    private enum class RecordStep { CATEGORY, FORM, SAVED }

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
        const val SERVICE_POLL_ATTEMPTS = 30
        const val SERVICE_POLL_MILLIS = 200L
        const val CATEGORY_TAG = "p34_talkback_category"
        const val AMOUNT_TAG = "p34_talkback_amount"
        const val SAVE_TAG = "p34_talkback_save"
        const val RECORDED_TAG = "p34_talkback_recorded"
        const val MAXIMUM_DIAGNOSTIC_NODES = 80
    }
}
