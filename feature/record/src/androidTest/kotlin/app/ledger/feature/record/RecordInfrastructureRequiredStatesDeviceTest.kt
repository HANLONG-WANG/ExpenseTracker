package app.ledger.feature.record

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.designsystem.AttachmentTransferState
import app.ledger.core.designsystem.AttachmentUiModel
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.designsystem.UiErrorCode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordInfrastructureRequiredStatesDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rec009RendersAllSixStatesAtCompactWidthAndTwoHundredPercentFont() {
        val state = mutableStateOf<RecordLocationEditorState>(RecordLocationEditorState.Locating)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 1400.dp)) {
                        RecordLocationEditorScreen(
                            state = state.value,
                            mapContent = { LedgerBanner("map surface", LedgerBannerVariant.NEUTRAL) },
                            onBack = {},
                            onRequestPermission = {},
                            onOpenMap = {},
                            onSelectPlace = {},
                            onUseLocation = {},
                        )
                    }
                }
            }
        }

        val states = listOf(
            RecordLocationEditorState.Locating,
            RecordLocationEditorState.Located("5 m", "Saved place"),
            RecordLocationEditorState.PermissionDenied,
            RecordLocationEditorState.Timeout,
            RecordLocationEditorState.Manual("Dropped pin"),
            RecordLocationEditorState.MapUnavailable,
        )
        states.forEach { next ->
            composeRule.runOnIdle { state.value = next }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LedgerTestTags.LOCATION_EDITOR).assertExists()
        }
    }

    @Test
    fun rec010RendersContentEmptyImportingAndFailedStates() {
        val ready = AttachmentUiModel("attachment_1", "receipt.pdf", "3 KB", "PDF")
        val importing = AttachmentUiModel(
            "attachment_2",
            "large.zip",
            "12 MB",
            "ZIP",
            progress = 0.42f,
            state = AttachmentTransferState.IMPORTING,
        )
        val state = mutableStateOf<RecordAttachmentsState>(RecordAttachmentsState.Empty)
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                RecordAttachmentsScreen(state.value, {}, {}, {}, {}, {})
            }
        }

        val states = listOf(
            RecordAttachmentsState.Content(listOf(ready)),
            RecordAttachmentsState.Empty,
            RecordAttachmentsState.Importing(listOf(ready, importing)),
            RecordAttachmentsState.Failed(listOf(ready), UiErrorCode("ATTACHMENT_IO_FAILURE")),
        )
        states.forEach { next ->
            composeRule.runOnIdle { state.value = next }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LedgerTestTags.ATTACHMENT_LIST).assertExists()
        }
    }
}
