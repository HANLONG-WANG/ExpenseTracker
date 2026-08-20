@file:Suppress("FunctionNaming", "MagicNumber", "MaxLineLength", "ktlint:standard:function-naming")

package app.ledger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.common.DomainResult
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.SearchField
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import java.util.Currency
import java.util.Locale

public data class CurrencySettingsState(
    val baseCurrency: CurrencyCode,
    val accountCurrencies: Set<CurrencyCode>,
    val visibleCodes: List<CurrencyCode>,
    val query: String = "",
)

public object CurrencySettingsPolicy {
    private val catalog = JvmLegalTenderCurrencyCatalog.create()

    public fun create(base: CurrencyCode, accountCurrencies: Set<CurrencyCode>, persistedOrder: List<String>): CurrencySettingsState {
        val legal = catalog.activeLegalTenderCurrencies().map { it.code }.toSet()
        val persisted = persistedOrder.mapNotNull { (CurrencyCode.parse(it) as? DomainResult.Success)?.value }.filter { it in legal }.distinct()
        val required = listOf(base) + accountCurrencies.sorted()
        return CurrencySettingsState(base, accountCurrencies, (persisted + required).distinct())
    }

    public fun search(state: CurrencySettingsState, value: String): CurrencySettingsState = state.copy(query = value.take(20))

    public fun toggle(state: CurrencySettingsState, code: CurrencyCode): CurrencySettingsState {
        val required = code == state.baseCurrency || code in state.accountCurrencies
        if (required && code in state.visibleCodes) return state
        return state.copy(visibleCodes = if (code in state.visibleCodes) state.visibleCodes - code else state.visibleCodes + code)
    }

    public fun move(state: CurrencySettingsState, code: CurrencyCode, delta: Int): CurrencySettingsState {
        val from = state.visibleCodes.indexOf(code)
        return if (from < 0) {
            state
        } else {
            val to = (from + delta).coerceIn(0, state.visibleCodes.lastIndex)
            if (from == to) {
                state
            } else {
                val reordered = state.visibleCodes.toMutableList().also { values -> values.add(to, values.removeAt(from)) }
                state.copy(visibleCodes = reordered)
            }
        }
    }

    public fun filteredCodes(state: CurrencySettingsState, locale: Locale): List<CurrencyCode> = catalog.activeLegalTenderCurrencies().map { it.code }.filter { code ->
        state.query.isBlank() || code.value.contains(state.query, ignoreCase = true) || currencyName(code, locale).contains(state.query, ignoreCase = true)
    }.sortedWith(compareBy<CurrencyCode> { it !in state.visibleCodes }.thenBy { state.visibleCodes.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.thenBy(CurrencyCode::value))

    public fun currencyName(code: CurrencyCode, locale: Locale): String = Currency.getInstance(code.value).getDisplayName(locale)
}

@Composable
public fun CurrencySettingsDestination(
    state: CurrencySettingsState,
    onSearch: (String) -> Unit,
    onToggle: (CurrencyCode) -> Unit,
    onMove: (CurrencyCode, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val codes = CurrencySettingsPolicy.filteredCodes(state, locale)
    val visible = codes.filter { it in state.visibleCodes }
    val hidden = codes.filterNot { it in state.visibleCodes }
    Column(
        modifier.fillMaxSize().testTag(LedgerTestTags.CURRENCY_SETTINGS_ROOT).padding(horizontal = LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        SearchField(state.query, onSearch, onClear = { onSearch("") }, placeholder = stringResource(R.string.currency_settings_search))
        LedgerText(stringResource(R.string.currency_settings_explanation), LedgerTextRole.SUPPORTING)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            item { LedgerText(stringResource(R.string.currency_settings_visible), LedgerTextRole.SECTION) }
            items(visible, key = { "visible-${it.value}" }) { code ->
                CurrencySettingRow(state, code, locale, onToggle, onMove)
            }
            item { LedgerText(stringResource(R.string.currency_settings_hidden), LedgerTextRole.SECTION) }
            items(hidden, key = { "hidden-${it.value}" }) { code ->
                CurrencySettingRow(state, code, locale, onToggle, onMove)
            }
        }
    }
}

@Composable
private fun CurrencySettingRow(
    state: CurrencySettingsState,
    code: CurrencyCode,
    locale: Locale,
    onToggle: (CurrencyCode) -> Unit,
    onMove: (CurrencyCode, Int) -> Unit,
) {
    val required = code == state.baseCurrency || code in state.accountCurrencies
    val visibleIndex = state.visibleCodes.indexOf(code)
    Column(Modifier.fillMaxWidth()) {
        LedgerToggleRow(
            title = "${code.value} · ${CurrencySettingsPolicy.currencyName(code, locale)}",
            checked = visibleIndex >= 0,
            onCheckedChange = { onToggle(code) },
            supportingText = when {
                code == state.baseCurrency -> stringResource(R.string.currency_settings_base_required)
                code in state.accountCurrencies -> stringResource(R.string.currency_settings_account_required)
                else -> null
            },
            enabled = !required,
        )
        if (visibleIndex >= 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LedgerButton(
                    stringResource(R.string.currency_settings_move_up),
                    { onMove(code, -1) },
                    variant = LedgerButtonVariant.TEXT,
                    enabled = visibleIndex > 0,
                    compact = true,
                )
                LedgerButton(
                    stringResource(R.string.currency_settings_move_down),
                    { onMove(code, 1) },
                    variant = LedgerButtonVariant.TEXT,
                    enabled = visibleIndex < state.visibleCodes.lastIndex,
                    compact = true,
                )
            }
        }
    }
}
