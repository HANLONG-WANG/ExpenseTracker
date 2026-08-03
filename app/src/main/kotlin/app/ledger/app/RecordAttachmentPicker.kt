package app.ledger.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.feature.record.OrdinaryRecordLoadState
import app.ledger.feature.record.RecordEditorPresentation
import app.ledger.feature.record.SpecializedPresentation
import app.ledger.feature.record.SpecializedTransactionLoadState

/** Keeps the system SAF launcher outside the large root destination dispatch for stable Lint FIR analysis. */
@Composable
internal fun rememberRecordAttachmentPicker(onSelected: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSelected(uri)
    }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}

internal fun specializedTransactionFixedAction(
    screenId: String,
    state: SpecializedTransactionLoadState,
    pending: Boolean,
    onSave: () -> Unit,
): (@Composable BoxScope.() -> Unit)? {
    if (screenId !in setOf("REC-013", "REC-020", "REC-021", "REC-022")) return null
    return {
        val editor = (state as? SpecializedTransactionLoadState.Content)?.editor
        LedgerSaveFab(
            onSave,
            submitting = pending || editor?.presentation == SpecializedPresentation.SAVING,
            enabled = !pending,
        )
    }
}

internal fun ordinaryRecordFixedAction(
    screenId: String,
    state: OrdinaryRecordLoadState,
    pending: Boolean,
    onSave: () -> Unit,
): (@Composable BoxScope.() -> Unit)? {
    if (screenId != "REC-003") return null
    return {
        val editor = (state as? OrdinaryRecordLoadState.Content)?.editor
        LedgerSaveFab(
            onSave,
            submitting = pending || editor?.presentation == RecordEditorPresentation.SAVING,
            enabled = !pending,
        )
    }
}
