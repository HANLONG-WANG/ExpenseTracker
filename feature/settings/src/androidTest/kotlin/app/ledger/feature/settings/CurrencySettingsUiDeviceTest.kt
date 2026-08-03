package app.ledger.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.ThemeMode
import app.ledger.core.money.CurrencyCode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrencySettingsUiDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun contentAndSearchingStatesRenderFromLegalTenderCatalog() {
        val current = mutableStateOf(CurrencySettingsPolicy.create(JPY, setOf(USD), listOf("JPY", "USD", "EUR")))
        composeRule.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false, reduceMotion = true) {
                Box(Modifier.size(320.dp, 1_200.dp)) {
                    CurrencySettingsDestination(current.value, { current.value = CurrencySettingsPolicy.search(current.value, it) }, {}, { _, _ -> })
                }
            }
        }
        composeRule.onNodeWithTag(LedgerTestTags.CURRENCY_SETTINGS_ROOT).assertExists()
        composeRule.runOnIdle { current.value = CurrencySettingsPolicy.search(current.value, "USD") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LedgerTestTags.CURRENCY_SETTINGS_ROOT).assertExists()
    }

    private companion object {
        val JPY: CurrencyCode = (CurrencyCode.parse("JPY") as DomainResult.Success).value
        val USD: CurrencyCode = (CurrencyCode.parse("USD") as DomainResult.Success).value
    }
}
