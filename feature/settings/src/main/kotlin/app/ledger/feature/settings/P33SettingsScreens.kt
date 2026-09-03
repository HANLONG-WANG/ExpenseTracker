@file:Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")

package app.ledger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ledger.core.designsystem.AmountSize
import app.ledger.core.designsystem.AmountText
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerBottomSheet
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerModalDialog
import app.ledger.core.designsystem.LedgerProgressIndicator
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.SearchField
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.MoneyUiModel
import java.text.NumberFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.TimeZone

enum class SettingsThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }

enum class SettingsDateFormat { LOCALE_DEFAULT, YEAR_MONTH_DAY, DAY_MONTH_YEAR, MONTH_DAY_YEAR }

enum class SettingsWeekStart { LOCALE_DEFAULT, MONDAY, SUNDAY }

data class RemainingSettingsState(
    val screenId: String,
    val themeMode: SettingsThemeMode,
    val dynamicColor: Boolean,
    val defaultAmountsHidden: Boolean,
    val reduceMotion: Boolean,
    val languageTag: String,
    val dateFormat: SettingsDateFormat,
    val numberFormatSummary: String,
    val zoneId: String,
    val availableZoneIds: List<String>,
    val weekStart: SettingsWeekStart,
    val datePreview: String,
    val appVersion: String,
    val licenses: List<String>,
)

sealed interface RemainingSettingsScreenAction {
    data class Navigate(val screenId: String) : RemainingSettingsScreenAction
    data class ThemeModeChanged(val mode: SettingsThemeMode) : RemainingSettingsScreenAction
    data class DynamicColorChanged(val enabled: Boolean) : RemainingSettingsScreenAction
    data class DefaultAmountsHiddenChanged(val hidden: Boolean) : RemainingSettingsScreenAction
    data class ReduceMotionChanged(val enabled: Boolean) : RemainingSettingsScreenAction
    data class LanguageTagChanged(val tag: String) : RemainingSettingsScreenAction
    data class DateFormatChanged(val format: SettingsDateFormat) : RemainingSettingsScreenAction
    data class ZoneIdChanged(val zoneId: String) : RemainingSettingsScreenAction
    data class WeekStartChanged(val weekStart: SettingsWeekStart) : RemainingSettingsScreenAction
    data object OpenSourceCode : RemainingSettingsScreenAction
    data object OpenPrivacyPolicy : RemainingSettingsScreenAction
}

internal class RemainingSettingsActions(
    val navigate: (String) -> Unit,
    val setThemeMode: (SettingsThemeMode) -> Unit,
    val setDynamicColor: (Boolean) -> Unit,
    val setDefaultAmountsHidden: (Boolean) -> Unit,
    val setReduceMotion: (Boolean) -> Unit,
    val setLanguageTag: (String) -> Unit,
    val setDateFormat: (SettingsDateFormat) -> Unit,
    val setZoneId: (String) -> Unit,
    val setWeekStart: (SettingsWeekStart) -> Unit,
    val openSourceCode: () -> Unit,
    val openPrivacyPolicy: () -> Unit,
)

internal fun remainingSettingsActions(onAction: (RemainingSettingsScreenAction) -> Unit): RemainingSettingsActions = RemainingSettingsActions(
    navigate = { onAction(RemainingSettingsScreenAction.Navigate(it)) },
    setThemeMode = { onAction(RemainingSettingsScreenAction.ThemeModeChanged(it)) },
    setDynamicColor = { onAction(RemainingSettingsScreenAction.DynamicColorChanged(it)) },
    setDefaultAmountsHidden = { onAction(RemainingSettingsScreenAction.DefaultAmountsHiddenChanged(it)) },
    setReduceMotion = { onAction(RemainingSettingsScreenAction.ReduceMotionChanged(it)) },
    setLanguageTag = { onAction(RemainingSettingsScreenAction.LanguageTagChanged(it)) },
    setDateFormat = { onAction(RemainingSettingsScreenAction.DateFormatChanged(it)) },
    setZoneId = { onAction(RemainingSettingsScreenAction.ZoneIdChanged(it)) },
    setWeekStart = { onAction(RemainingSettingsScreenAction.WeekStartChanged(it)) },
    openSourceCode = { onAction(RemainingSettingsScreenAction.OpenSourceCode) },
    openPrivacyPolicy = { onAction(RemainingSettingsScreenAction.OpenPrivacyPolicy) },
)

@Composable
fun RemainingSettingsDestination(
    state: RemainingSettingsState,
    onAction: (RemainingSettingsScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = remainingSettingsActions(onAction)
    when (state.screenId) {
        "SETG-001" -> SettingsHub(actions, modifier)
        "SETG-002" -> AppearanceSettings(state, actions, modifier)
        "SETG-003" -> LanguageRegionSettings(state, actions, modifier)
        "SETG-005" -> CalendarSettings(state, actions, modifier)
        "SETG-012" -> AboutSettings(state, actions, modifier)
    }
}

@Composable
private fun SettingsHub(actions: RemainingSettingsActions, modifier: Modifier) {
    val sections = listOf(
        R.string.settings_section_display to listOf(
            SettingRow(R.string.settings_appearance, R.string.settings_appearance_body, "SETG-002"),
            SettingRow(R.string.settings_language_region, R.string.settings_language_region_body, "SETG-003"),
            SettingRow(R.string.settings_calendar, R.string.settings_calendar_body, "SETG-005"),
            SettingRow(R.string.settings_currencies, R.string.settings_currencies_body, "SETG-004"),
        ),
        R.string.settings_section_security to listOf(
            SettingRow(R.string.settings_app_lock, R.string.settings_app_lock_body, "SETG-006"),
            SettingRow(R.string.settings_screen_privacy, R.string.settings_screen_privacy_body, "SETG-007"),
            SettingRow(R.string.settings_trash, R.string.settings_trash_body, "SETG-008"),
            SettingRow(R.string.settings_diagnostics, R.string.settings_diagnostics_body, "SETG-009"),
            SettingRow(R.string.settings_notifications, R.string.settings_notifications_body, "SYS-002"),
        ),
        R.string.settings_section_information to listOf(
            SettingRow(R.string.settings_about, R.string.settings_about_body, "SETG-012"),
        ),
    )
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        sections.forEach { (section, rows) ->
            item { LedgerText(stringResource(section), LedgerTextRole.SECTION) }
            items(rows, key = { it.screenId }) { row ->
                SettingsCard(stringResource(row.title), stringResource(row.body)) { actions.navigate(row.screenId) }
            }
        }
    }
}

@Composable
private fun AppearanceSettings(state: RemainingSettingsState, actions: RemainingSettingsActions, modifier: Modifier) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        item { LedgerText(stringResource(R.string.settings_theme), LedgerTextRole.SECTION) }
        items(SettingsThemeMode.entries) { value ->
            LedgerChoiceRow(
                stringResource(value.label()),
                state.themeMode == value,
                { actions.setThemeMode(value) },
            )
        }
        item {
            LedgerToggleRow(
                stringResource(R.string.settings_dynamic_color),
                state.dynamicColor,
                actions.setDynamicColor,
                supportingText = stringResource(R.string.settings_dynamic_color_body),
            )
        }
        item {
            LedgerToggleRow(
                stringResource(R.string.settings_default_amounts_hidden),
                state.defaultAmountsHidden,
                actions.setDefaultAmountsHidden,
                supportingText = stringResource(R.string.settings_default_amounts_hidden_body),
            )
        }
        item {
            LedgerToggleRow(
                stringResource(R.string.settings_reduce_motion),
                state.reduceMotion,
                actions.setReduceMotion,
                supportingText = stringResource(R.string.settings_reduce_motion_body),
            )
        }
        item {
            val locale = LocalConfiguration.current.locales[0]
            val previewAmount = NumberFormat.getCurrencyInstance(locale).format(1_234.56)
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
                ) {
                    LedgerText(stringResource(R.string.settings_live_preview), LedgerTextRole.SECTION)
                    LedgerText(stringResource(R.string.settings_live_preview_body), LedgerTextRole.BODY)
                    AmountText(
                        MoneyUiModel(
                            formatted = previewAmount,
                            fullAccessibleText = previewAmount,
                            semantic = AmountSemantic.OUTFLOW,
                            visibility = if (state.defaultAmountsHidden) AmountVisibility.HIDDEN else AmountVisibility.VISIBLE,
                        ),
                        AmountSize.LARGE,
                    )
                    LedgerProgressIndicator(
                        progress = 0.64f,
                        accessibleText = stringResource(R.string.settings_live_preview_progress),
                    )
                    LedgerBanner(stringResource(R.string.settings_live_preview_action), LedgerBannerVariant.NEUTRAL)
                }
            }
        }
    }
}

@Composable
private fun LanguageRegionSettings(state: RemainingSettingsState, actions: RemainingSettingsActions, modifier: Modifier) {
    var showZoneChooser by remember { mutableStateOf(false) }
    var zoneQuery by remember { mutableStateOf("") }
    val locale = LocalLocale.current.platformLocale
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        item { LedgerText(stringResource(R.string.settings_language), LedgerTextRole.SECTION) }
        items(SUPPORTED_LANGUAGES) { language ->
            LedgerChoiceRow(stringResource(language.label), state.languageTag == language.tag, { actions.setLanguageTag(language.tag) })
        }
        item { LedgerText(stringResource(R.string.settings_date_format), LedgerTextRole.SECTION) }
        items(SettingsDateFormat.entries) { value ->
            LedgerChoiceRow(stringResource(value.label()), state.dateFormat == value, { actions.setDateFormat(value) })
        }
        item {
            LedgerBanner(
                stringResource(R.string.settings_number_format_summary, state.numberFormatSummary),
                LedgerBannerVariant.INFO,
            )
        }
        item {
            SelectorField(
                label = stringResource(R.string.settings_time_zone),
                selectedText = state.zoneId,
                onClick = {
                    zoneQuery = ""
                    showZoneChooser = true
                },
                supportingText = zoneDisplayName(state.zoneId, locale),
            )
        }
        item { LedgerText(stringResource(R.string.settings_week_start), LedgerTextRole.SECTION) }
        items(SettingsWeekStart.entries) { value ->
            LedgerChoiceRow(stringResource(value.label()), state.weekStart == value, { actions.setWeekStart(value) })
        }
    }
    if (showZoneChooser) {
        val zones = remember(state.availableZoneIds, zoneQuery, locale) {
            state.availableZoneIds.filter { it == "UTC" || '/' in it }.filter { zone ->
                val name = zoneDisplayName(zone, locale)
                zoneQuery.isBlank() || zone.contains(zoneQuery, ignoreCase = true) || name.contains(zoneQuery, ignoreCase = true)
            }
        }
        LedgerModalDialog(stringResource(R.string.settings_time_zone), onDismiss = { showZoneChooser = false }) {
            SearchField(
                value = zoneQuery,
                onValueChange = { zoneQuery = it.take(MAX_ZONE_QUERY) },
                onClear = { zoneQuery = "" },
                placeholder = stringResource(R.string.settings_time_zone_search),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxWidth().fillMaxSize()) {
                items(zones, key = { it }) { zone ->
                    LedgerChoiceRow(
                        zone,
                        state.zoneId == zone,
                        {
                            actions.setZoneId(zone)
                            showZoneChooser = false
                        },
                        supportingText = zoneDisplayName(zone, locale),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarSettings(state: RemainingSettingsState, actions: RemainingSettingsActions, modifier: Modifier) {
    val locale = LocalLocale.current.platformLocale
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        item { LedgerText(stringResource(R.string.settings_week_start), LedgerTextRole.SECTION) }
        items(SettingsWeekStart.entries) { value ->
            LedgerChoiceRow(stringResource(value.label()), state.weekStart == value, { actions.setWeekStart(value) })
        }
        item { LedgerText(stringResource(R.string.settings_date_format), LedgerTextRole.SECTION) }
        items(SettingsDateFormat.entries) { value ->
            LedgerChoiceRow(stringResource(value.label()), state.dateFormat == value, { actions.setDateFormat(value) })
        }
        item {
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(stringResource(R.string.settings_preview), LedgerTextRole.SECTION)
                    LedgerText(state.datePreview, LedgerTextRole.BODY)
                    LedgerText(weekPreview(state.weekStart, locale), LedgerTextRole.SUPPORTING)
                }
            }
        }
    }
}

@Composable
private fun AboutSettings(state: RemainingSettingsState, actions: RemainingSettingsActions, modifier: Modifier) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item { LedgerText(stringResource(R.string.settings_version, state.appVersion), LedgerTextRole.SECTION) }
        item { SettingsCard(stringResource(R.string.settings_privacy_policy), stringResource(R.string.settings_privacy_policy_body), actions.openPrivacyPolicy) }
        item { SettingsCard(stringResource(R.string.settings_source_code), stringResource(R.string.settings_source_code_body), actions.openSourceCode) }
        item { LedgerText(SOURCE_CODE_URL, LedgerTextRole.SUPPORTING) }
        item { LedgerText(stringResource(R.string.settings_open_source_licenses), LedgerTextRole.SECTION) }
        items(state.licenses, key = { it }) { license ->
            LedgerCard(Modifier.fillMaxWidth()) { LedgerText(license, LedgerTextRole.BODY, Modifier.padding(LedgerTheme.spacing.sm)) }
        }
    }
}

@Composable
private fun SettingsCard(title: String, body: String, onClick: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            LedgerText(title, LedgerTextRole.SECTION)
            LedgerText(body, LedgerTextRole.SUPPORTING)
        }
    }
}

private data class SettingRow(val title: Int, val body: Int, val screenId: String)

private data class SupportedLanguage(val tag: String, val label: Int)

private val SUPPORTED_LANGUAGES = listOf(
    SupportedLanguage("zh-CN", R.string.settings_language_chinese),
    SupportedLanguage("ja", R.string.settings_language_japanese),
    SupportedLanguage("en", R.string.settings_language_english),
)

private fun zoneDisplayName(zoneId: String, locale: java.util.Locale): String = runCatching {
    val now = ZonedDateTime.now(ZoneId.of(zoneId))
    val name = DateTimeFormatter.ofPattern("zzzz", locale).format(now)
    val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }
    "$name · GMT$offset"
}.getOrElse { zoneId }

@Composable
private fun weekPreview(value: SettingsWeekStart, locale: java.util.Locale): String {
    val firstDay = when (value) {
        SettingsWeekStart.LOCALE_DEFAULT -> WeekFields.of(locale).firstDayOfWeek
        SettingsWeekStart.MONDAY -> java.time.DayOfWeek.MONDAY
        SettingsWeekStart.SUNDAY -> java.time.DayOfWeek.SUNDAY
    }
    return stringResource(
        if (firstDay == java.time.DayOfWeek.SUNDAY) {
            R.string.settings_week_preview_sunday
        } else {
            R.string.settings_week_preview_monday
        },
    )
}

private const val MAX_ZONE_QUERY = 80
private const val SOURCE_CODE_URL = "https://github.com/HANLONG-WANG/ExpenseTracker"

private fun SettingsThemeMode.label(): Int = when (this) {
    SettingsThemeMode.FOLLOW_SYSTEM -> R.string.settings_theme_system
    SettingsThemeMode.LIGHT -> R.string.settings_theme_light
    SettingsThemeMode.DARK -> R.string.settings_theme_dark
}

private fun SettingsDateFormat.label(): Int = when (this) {
    SettingsDateFormat.LOCALE_DEFAULT -> R.string.settings_date_locale
    SettingsDateFormat.YEAR_MONTH_DAY -> R.string.settings_date_ymd
    SettingsDateFormat.DAY_MONTH_YEAR -> R.string.settings_date_dmy
    SettingsDateFormat.MONTH_DAY_YEAR -> R.string.settings_date_mdy
}

private fun SettingsWeekStart.label(): Int = when (this) {
    SettingsWeekStart.LOCALE_DEFAULT -> R.string.settings_week_locale
    SettingsWeekStart.MONDAY -> R.string.settings_week_monday
    SettingsWeekStart.SUNDAY -> R.string.settings_week_sunday
}
