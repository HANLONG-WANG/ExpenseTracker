package app.ledger.core.files

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.finance.domain.AttachmentId
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentRequiredStatesDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var loader: SecureAttachmentImageLoader? = null

    @After
    fun cleanUp() {
        loader?.close()
    }

    @Test
    fun previewExternalOpenAndRenameRenderEveryRequiredStateAtLargeFont() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val metadata = AttachmentMetadataUiModel(ATTACHMENT_ID, "receipt.png", "PNG", "2 KB", "2026-08-02")
        val secureLoader = SecureAttachmentImageLoader(context, InMemoryAttachmentReader())
        loader = secureLoader
        val state = mutableStateOf<ScreenState>(ScreenState.Preview(AttachmentPreviewState.Loading))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                    Box(Modifier.size(320.dp, 1200.dp)) {
                        when (val current = state.value) {
                            is ScreenState.Preview -> AttachmentPreviewScreen(current.state, secureLoader, {}, {}, {})
                            ScreenState.ExternalOpen -> AttachmentExternalOpenDialog({}, {})
                            is ScreenState.Rename -> AttachmentRenameDialog(current.state, {}, {}, {})
                        }
                    }
                }
            }
        }

        val states = listOf(
            ScreenState.Preview(AttachmentPreviewState.Loading),
            ScreenState.Preview(AttachmentPreviewState.Image(metadata)),
            ScreenState.Preview(AttachmentPreviewState.UnsupportedPreview(metadata)),
            ScreenState.Preview(AttachmentPreviewState.DecryptError(UiErrorCode("ATTACHMENT_DECRYPTION_FAILED"))),
            ScreenState.ExternalOpen,
            ScreenState.Rename(AttachmentRenameState.Editing("receipt.png")),
            ScreenState.Rename(AttachmentRenameState.Invalid("")),
        )
        states.forEach { next ->
            composeRule.runOnIdle { state.value = next }
            composeRule.waitForIdle()
            when (next) {
                is ScreenState.Preview -> when (next.state) {
                    AttachmentPreviewState.Loading -> composeRule.onRoot().assertExists()
                    is AttachmentPreviewState.Image -> composeRule.onNodeWithTag(LedgerTestTags.ATTACHMENT_PREVIEW).assertExists()
                    is AttachmentPreviewState.UnsupportedPreview -> composeRule.onNodeWithTag(LedgerTestTags.ATTACHMENT_METADATA).assertExists()
                    is AttachmentPreviewState.DecryptError -> composeRule.onNodeWithText("ATTACHMENT_DECRYPTION_FAILED").assertExists()
                }
                ScreenState.ExternalOpen -> composeRule.onNodeWithTag(LedgerTestTags.ATTACHMENT_EXTERNAL_OPEN).assertExists()
                is ScreenState.Rename -> composeRule.onNodeWithTag(LedgerTestTags.ATTACHMENT_RENAME).assertExists()
            }
        }
    }

    private sealed interface ScreenState {
        data class Preview(val state: AttachmentPreviewState) : ScreenState
        data object ExternalOpen : ScreenState
        data class Rename(val state: AttachmentRenameState) : ScreenState
    }

    private class InMemoryAttachmentReader : AttachmentContentReader {
        override fun metadata(attachmentId: AttachmentId): AttachmentMetadata = AttachmentMetadata(
            attachmentId,
            "receipt.png",
            "image/png",
            "png",
            PNG.size.toLong(),
            Instant.parse("2026-08-02T00:00:00Z"),
        )

        override fun openOriginal(attachmentId: AttachmentId): DecryptedAttachment = DecryptedAttachment(metadata(attachmentId), ByteArrayInputStream(PNG))

        override fun openThumbnail(attachmentId: AttachmentId): DecryptedAttachment = openOriginal(attachmentId)
    }

    private companion object {
        val ATTACHMENT_ID = AttachmentId(StableId.fromUuid(UUID(0, 1)))
        val PNG = byteArrayOf(
            -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119,
            0, 0, 0, 13, 73, 68, 65, 84, 8, -41, 99, -8, -49, -64, -16, 31,
            0, 5, 0, 1, -1, -119, -103, 29, 29, 0, 0, 0, 0, 73, 69, 78, 68,
            -82, 66, 96, -126,
        )
    }
}
