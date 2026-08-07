package app.ledger.core.geo

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class LedgerMapAndPermissionDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actualMapLibreLifecycleRendersAllOverlayModesWithAttributionAndSanitizedSemantics() {
        val mode = mutableStateOf(LedgerMapMode.SINGLE_POINTS)
        val failed = AtomicBoolean(false)
        val exactCoordinateText = "35.681236,139.767125"
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = false) {
                LedgerMap(
                    state = LedgerMapState.Available(
                        summary = "One recorded place",
                        mode = mode.value,
                        points = listOf(
                            LedgerMapPoint(
                                StableId.fromUuid(UUID(0, 1)),
                                latitudeE7 = 356_812_360,
                                longitudeE7 = 1397_671_250,
                                weight = 1,
                            ),
                        ),
                        accessibleRows = listOf(LedgerMapAccessibleRow("Recorded place", "One transaction")),
                        userLocation = LedgerMapPoint(
                            StableId.fromUuid(UUID(0, 2)),
                            latitudeE7 = 356_800_000,
                            longitudeE7 = 1397_600_000,
                            weight = 1,
                        ),
                    ),
                    styleConfiguration = TEST_STYLE,
                    accessibleCaption = "Accessible locations",
                    accessibleColumnHeaders = listOf("Place", "Summary"),
                    showAccessibleListLabel = "Show accessible list",
                    hideAccessibleListLabel = "Hide accessible list",
                    onFailure = { failed.set(true) },
                )
            }
        }

        composeRule.onNodeWithTag(LedgerTestTags.MAP).assertExists()
        composeRule.onNodeWithText(TEST_STYLE.attribution).assertExists()
        composeRule.onNodeWithText(exactCoordinateText, substring = true, useUnmergedTree = true).assertDoesNotExist()
        LedgerMapMode.entries.forEach { next ->
            composeRule.runOnIdle { mode.value = next }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LedgerTestTags.MAP).assertExists()
        }
        assertTrue("local MapLibre style unexpectedly failed", !failed.get())
    }

    @Test
    fun unavailableMapShowsAccessibleListAndPermissionDialogCoversEveryRequiredState() {
        val permissionState = mutableStateOf(LocationPermissionDialogState.FIRST_ASK)
        composeRule.setContent {
            LedgerTheme(ThemeMode.DARK, dynamicColor = false, reduceMotion = true) {
                LedgerMap(
                    state = LedgerMapState.Unavailable(
                        summary = "Map unavailable",
                        accessibleRows = listOf(LedgerMapAccessibleRow("Saved place", "Two transactions")),
                    ),
                    styleConfiguration = TEST_STYLE,
                    accessibleCaption = "Accessible locations",
                    accessibleColumnHeaders = listOf("Place", "Summary"),
                    showAccessibleListLabel = "Show accessible list",
                    hideAccessibleListLabel = "Hide accessible list",
                    onFailure = {},
                )
                LocationPermissionDialog(permissionState.value, {}, {}, {})
            }
        }

        composeRule.onNodeWithTag(LedgerTestTags.MAP_FALLBACK).assertExists()
        LocationPermissionDialogState.entries.forEach { next ->
            composeRule.runOnIdle { permissionState.value = next }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LedgerTestTags.LOCATION_PERMISSION).assertExists()
        }
    }

    private companion object {
        val TEST_STYLE = LedgerMapStyleConfiguration(
            light = LedgerMapStyleSource.Json("""{"version":8,"sources":{},"layers":[]}"""),
            dark = LedgerMapStyleSource.Json("""{"version":8,"sources":{},"layers":[]}"""),
            attribution = "Test map attribution",
        )
    }
}
