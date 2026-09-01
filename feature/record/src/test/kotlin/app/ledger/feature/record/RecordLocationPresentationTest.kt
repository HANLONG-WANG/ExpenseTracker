package app.ledger.feature.record

import app.ledger.core.designsystem.LocationFieldState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RecordLocationPresentationTest {
    @Test
    fun everyEditorStateMapsToADistinctTruthfulFieldState() {
        assertEquals(LocationFieldState.ReadyAtSave, RecordLocationEditorState.NotRequested.toLocationFieldState())
        assertEquals(LocationFieldState.Locating, RecordLocationEditorState.Locating.toLocationFieldState())
        assertInstanceOf(LocationFieldState.Located::class.java, RecordLocationEditorState.Located("5 m", "New pin").toLocationFieldState())
        assertEquals(LocationFieldState.TimedOut, RecordLocationEditorState.Timeout.toLocationFieldState())
        assertEquals(LocationFieldState.ServiceUnavailable, RecordLocationEditorState.ServiceUnavailable.toLocationFieldState())
        assertEquals(LocationFieldState.PermissionDenied, RecordLocationEditorState.PermissionDenied.toLocationFieldState())
        assertEquals(LocationFieldState.NotRecorded, RecordLocationEditorState.Cleared.toLocationFieldState())
        assertInstanceOf(LocationFieldState.ManuallyAdjusted::class.java, RecordLocationEditorState.Manual("Home").toLocationFieldState())
    }

    @Test
    fun acquiredAndManualStatesKeepTheirRecognizableSummary() {
        assertEquals(
            LocationFieldState.Located("New pin · 5 m"),
            RecordLocationEditorState.Located("5 m", "New pin").toLocationFieldState(),
        )
        assertEquals(
            LocationFieldState.ManuallyAdjusted("Home"),
            RecordLocationEditorState.Manual("Home").toLocationFieldState(),
        )
    }
}
