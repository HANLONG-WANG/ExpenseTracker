package app.ledger.feature.record

import app.ledger.core.designsystem.LocationFieldState

internal fun RecordLocationEditorState.toLocationFieldState(): LocationFieldState = when (this) {
    RecordLocationEditorState.NotRequested -> LocationFieldState.ReadyAtSave
    RecordLocationEditorState.Locating -> LocationFieldState.Locating
    is RecordLocationEditorState.Located -> LocationFieldState.Located("$selectedPlaceText · $accuracyText")
    RecordLocationEditorState.PermissionDenied -> LocationFieldState.PermissionDenied
    RecordLocationEditorState.Timeout -> LocationFieldState.TimedOut
    RecordLocationEditorState.ServiceUnavailable -> LocationFieldState.ServiceUnavailable
    RecordLocationEditorState.Cleared -> LocationFieldState.NotRecorded
    is RecordLocationEditorState.Manual -> LocationFieldState.ManuallyAdjusted(selectedPlaceText)
    RecordLocationEditorState.MapUnavailable -> LocationFieldState.Unavailable
}
