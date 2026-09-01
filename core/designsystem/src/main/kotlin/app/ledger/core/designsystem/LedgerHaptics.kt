package app.ledger.core.designsystem

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/** Semantic haptic intents used by product UI instead of gesture-specific platform constants. */
public enum class LedgerHaptic {
    SELECTION,
    TOGGLE_ON,
    TOGGLE_OFF,
    SUCCESS,
    ERROR,
    WARNING,
    DRAG_START,
}

public fun HapticFeedback.performLedgerHaptic(intent: LedgerHaptic) {
    performHapticFeedback(
        when (intent) {
            LedgerHaptic.SELECTION -> HapticFeedbackType.SegmentTick
            LedgerHaptic.TOGGLE_ON -> HapticFeedbackType.ToggleOn
            LedgerHaptic.TOGGLE_OFF -> HapticFeedbackType.ToggleOff
            LedgerHaptic.SUCCESS -> HapticFeedbackType.Confirm
            LedgerHaptic.ERROR -> HapticFeedbackType.Reject
            LedgerHaptic.WARNING -> HapticFeedbackType.GestureThresholdActivate
            LedgerHaptic.DRAG_START -> HapticFeedbackType.LongPress
        },
    )
}
