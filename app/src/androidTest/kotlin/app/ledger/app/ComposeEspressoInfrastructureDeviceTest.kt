package app.ledger.app

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeEspressoInfrastructureDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composeAndEspressoShareTheInstrumentedHost() {
        composeRule.setContent {
            Box(Modifier.testTag("quality-infrastructure"))
        }

        composeRule.onNodeWithTag("quality-infrastructure").assertExists()
        onView(isRoot()).check(matches(isDisplayed()))
    }
}
