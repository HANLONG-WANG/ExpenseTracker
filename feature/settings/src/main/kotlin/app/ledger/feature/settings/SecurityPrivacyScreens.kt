@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package app.ledger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.designsystem.HighRiskConfirmation
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
public fun SecurityPrivacySettingsDestination(
    state: SecurityPrivacySettingsState,
    onAction: (SecurityPrivacyScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = securityPrivacyActions(onAction)
    Column(
        modifier.fillMaxSize().testTag("security_settings_root").padding(vertical = LedgerTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        when (state.screenId) {
            "SETG-006" -> AppLockSettings(state, actions)
            "SETG-007" -> ScreenPrivacySettings(state, actions)
            "SETG-008" -> TrashSettings(state, actions)
            "SETG-009" -> DiagnosticsSettings(state, actions)
            "SETG-010" -> FeatureQueue(state, actions)
            "SETG-011" -> CrashQueue(state, actions)
            "CLR-001" -> LocalClear(state, actions)
            "SYS-004" -> DeviceSecurity(state, actions)
        }
    }
}

@Composable
private fun AppLockSettings(state: SecurityPrivacySettingsState, actions: SecurityPrivacySettingsActions) {
    if (!state.deviceSecurityConfigured || state.presentation == SecuritySettingsRequiredState.SETG_006_DEVICE_SECURITY_MISSING) {
        LedgerBanner(
            stringResource(R.string.security_device_required),
            LedgerBannerVariant.DANGER,
            actionLabel = stringResource(R.string.security_open_system),
            onAction = actions.onOpenSystemSecurity,
        )
        return
    }
    LedgerToggleRow(
        stringResource(R.string.security_app_lock),
        state.appLockEnabled,
        actions.onAppLockEnabled,
        supportingText = stringResource(R.string.security_app_lock_body),
    )
    if (state.appLockEnabled) {
        AppLockTimeout.entries.forEach { timeout ->
            LedgerChoiceRow(
                timeout.label(),
                timeout == state.appLockTimeout,
                { actions.onAppLockTimeout(timeout, state.customTimeoutMinutes) },
            )
        }
        if (state.appLockTimeout == AppLockTimeout.CUSTOM) {
            var minutes by remember(state.customTimeoutMinutes) { mutableIntStateOf(state.customTimeoutMinutes) }
            LedgerTextField(
                minutes.toString(),
                { value ->
                    minutes = value.filter(Char::isDigit).toIntOrNull()?.coerceIn(
                        SecurityPrivacySettingsState.MINIMUM_CUSTOM_TIMEOUT_MINUTES,
                        SecurityPrivacySettingsState.MAXIMUM_CUSTOM_TIMEOUT_MINUTES,
                    ) ?: SecurityPrivacySettingsState.MINIMUM_CUSTOM_TIMEOUT_MINUTES
                },
                stringResource(R.string.security_custom_minutes),
            )
            LedgerButton(
                stringResource(R.string.security_apply_timeout),
                { actions.onAppLockTimeout(AppLockTimeout.CUSTOM, minutes) },
                Modifier.fillMaxWidth(),
            )
        }
        LedgerText(stringResource(R.string.security_auth_method_label), LedgerTextRole.SECTION)
        LedgerText(
            stringResource(
                when (state.authenticationCapability) {
                    DeviceAuthenticationCapability.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL -> R.string.security_auth_method_biometric_or_credential
                    DeviceAuthenticationCapability.DEVICE_CREDENTIAL_ONLY -> R.string.security_auth_method_device_credential
                    DeviceAuthenticationCapability.UNAVAILABLE -> R.string.security_auth_method_unavailable
                },
            ),
            LedgerTextRole.SUPPORTING,
        )
        LedgerButton(stringResource(R.string.security_test_lock), actions.onTestLock, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
    }
}

@Composable
private fun ScreenPrivacySettings(state: SecurityPrivacySettingsState, actions: SecurityPrivacySettingsActions) {
    LedgerToggleRow(
        stringResource(R.string.security_block_screenshots),
        state.globalScreenshotBlocked,
        actions.onGlobalScreenshotBlocked,
        supportingText = stringResource(R.string.security_block_screenshots_body),
    )
    LedgerToggleRow(
        stringResource(R.string.security_obscure_recents),
        state.obscureRecentTasks,
        actions.onObscureRecentTasks,
        supportingText = stringResource(R.string.security_obscure_recents_body),
    )
    LedgerBanner(stringResource(R.string.security_vault_always_secure), LedgerBannerVariant.INFO)
}

@Composable
private fun TrashSettings(state: SecurityPrivacySettingsState, actions: SecurityPrivacySettingsActions) {
    var customExpanded by remember(state.trashRetention) { mutableStateOf(state.trashRetention == TrashRetention.CUSTOM) }
    var customDays by remember(state.customTrashRetentionDays) { mutableIntStateOf(state.customTrashRetentionDays) }
    TrashRetention.entries.filterNot { it == TrashRetention.CUSTOM }.forEach { retention ->
        LedgerChoiceRow(retention.label(), state.trashRetention == retention && !customExpanded, {
            customExpanded = false
            actions.onTrashRetention(retention)
        })
    }
    LedgerChoiceRow(stringResource(R.string.security_trash_custom), customExpanded, { customExpanded = true })
    if (customExpanded) {
        LedgerTextField(
            customDays.toString(),
            { value ->
                customDays = value.filter(Char::isDigit).toIntOrNull()?.coerceIn(
                    SecurityPrivacySettingsState.MINIMUM_TRASH_RETENTION_DAYS,
                    SecurityPrivacySettingsState.MAXIMUM_TRASH_RETENTION_DAYS,
                ) ?: SecurityPrivacySettingsState.MINIMUM_TRASH_RETENTION_DAYS
            },
            stringResource(R.string.security_trash_custom_days),
        )
        LedgerButton(stringResource(R.string.security_trash_apply_custom), { actions.onCustomTrashRetention(customDays) }, Modifier.fillMaxWidth())
    }
    LedgerText(stringResource(R.string.security_purge_summary), LedgerTextRole.SUPPORTING)
    LedgerButton(stringResource(R.string.security_open_trash), actions.onOpenTrash, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
}

@Composable
private fun DiagnosticsSettings(state: SecurityPrivacySettingsState, actions: SecurityPrivacySettingsActions) {
    if (!state.privacyAccepted || state.presentation == SecuritySettingsRequiredState.SETG_009_PRE_CONSENT) {
        LedgerBanner(
            stringResource(R.string.diagnostics_pre_consent),
            LedgerBannerVariant.WARNING,
            actionLabel = stringResource(R.string.diagnostics_privacy_policy),
            onAction = actions.onOpenPrivacyPolicy,
        )
    }
    LedgerToggleRow(
        stringResource(R.string.diagnostics_feature),
        state.telemetryEnabled,
        actions.onTelemetryEnabled,
        supportingText = stringResource(R.string.diagnostics_feature_body),
        enabled = state.privacyAccepted,
    )
    LedgerToggleRow(
        stringResource(R.string.diagnostics_crash),
        state.crashEnabled,
        actions.onCrashEnabled,
        supportingText = stringResource(R.string.diagnostics_crash_body),
        enabled = state.privacyAccepted,
    )
    LedgerBanner(stringResource(R.string.diagnostics_whitelist), LedgerBannerVariant.INFO)
    LedgerButton(stringResource(R.string.diagnostics_feature_queue, state.featureRows.size), actions.onOpenFeatureQueue, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
    LedgerButton(stringResource(R.string.diagnostics_crash_queue, state.crashRows.size), actions.onOpenCrashQueue, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
    LedgerButton(stringResource(R.string.diagnostics_privacy_policy), actions.onOpenPrivacyPolicy, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT)
}

@Composable
private fun FeatureQueue(state: SecurityPrivacySettingsState, actions: SecurityPrivacySettingsActions) {
    if (state.featureRows.isEmpty()) {
        QueueEmptyState(stringResource(R.string.diagnostics_feature_empty_body))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        items(state.featureRows) { row ->
            QueueCard(
                row.event,
                "${row.entry} · ${row.outcome} · ${row.duration} · ${row.errorCode}",
                row.occurredAtEpochMillis.localizedDateTime(state.zoneId),
            )
        }
        item { LedgerButton(stringResource(R.string.diagnostics_delete_queue), actions.onDeleteFeatureQueue, Modifier.fillMaxWidth(), LedgerButtonVariant.DANGER) }
    }
}

@Composable
private fun CrashQueue(state: SecurityPrivacySettingsState, actions: SecurityPrivacySettingsActions) {
    if (state.crashRows.isEmpty()) {
        QueueEmptyState(stringResource(R.string.diagnostics_crash_empty_body))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        items(state.crashRows) { row ->
            QueueCard(row.kind, "${row.errorCode} · ${row.frameCount}", row.occurredAtEpochMillis.localizedDateTime(state.zoneId))
        }
        item { LedgerButton(stringResource(R.string.diagnostics_delete_queue), actions.onDeleteCrashQueue, Modifier.fillMaxWidth(), LedgerButtonVariant.DANGER) }
    }
}

@Composable
private fun QueueEmptyState(body: String) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(LedgerTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            LedgerText(stringResource(R.string.diagnostics_queue_empty), LedgerTextRole.SECTION)
            LedgerText(body, LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun QueueCard(title: String, fixedFields: String, time: String) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LedgerTheme.spacing.sm)) {
            LedgerText(title, LedgerTextRole.SECTION)
            LedgerText(fixedFields, LedgerTextRole.SUPPORTING)
            LedgerText(time, LedgerTextRole.LABEL)
        }
    }
}

@Composable
private fun LocalClear(state: SecurityPrivacySettingsState, actions: SecurityPrivacySettingsActions) {
    var phrase by remember { mutableStateOf("") }
    val scopeItems = listOf(
        R.string.clear_scope_ledger,
        R.string.clear_scope_attachments,
        R.string.clear_scope_keys,
        R.string.clear_scope_backups,
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item { LedgerBanner(stringResource(R.string.clear_scope), LedgerBannerVariant.DANGER) }
        item { LedgerText(stringResource(R.string.clear_scope_title), LedgerTextRole.SECTION) }
        items(scopeItems) { label ->
            LedgerCard(Modifier.fillMaxWidth()) {
                LedgerText(stringResource(label), LedgerTextRole.BODY, Modifier.padding(LedgerTheme.spacing.sm))
            }
        }
        item { LedgerText(stringResource(R.string.clear_not_external), LedgerTextRole.SUPPORTING) }
        if (state.presentation == SecuritySettingsRequiredState.CLR_001_FAILED) {
            item { LedgerBanner(localClearFailureMessage(state.errorCode), LedgerBannerVariant.DANGER) }
        }
        item { LocalClearAuthenticationGate(state) }
        when (state.presentation) {
            SecuritySettingsRequiredState.CLR_001_CLEARING -> item {
                LedgerBanner(stringResource(R.string.clear_in_progress), LedgerBannerVariant.WARNING)
            }
            SecuritySettingsRequiredState.CLR_001_CONFIRMING -> if (!state.localClearAuthenticationPending) {
                item {
                    val required = stringResource(R.string.clear_phrase)
                    HighRiskConfirmation(
                        stringResource(R.string.clear_local),
                        stringResource(R.string.clear_scope),
                        stringResource(R.string.clear_consequence),
                        stringResource(R.string.clear_not_external),
                        required,
                        phrase,
                        { phrase = it },
                        actions.onConfirmLocalClear,
                        actions.onCancelLocalClear,
                    )
                }
            }
            else -> item {
                LedgerButton(stringResource(R.string.clear_local), actions.onBeginLocalClear, Modifier.fillMaxWidth(), LedgerButtonVariant.DANGER)
            }
        }
    }
}

@Composable
private fun LocalClearAuthenticationGate(state: SecurityPrivacySettingsState) {
    val message = when {
        state.localClearAuthenticationPending -> R.string.clear_authentication_pending
        state.presentation == SecuritySettingsRequiredState.CLR_001_CLEARING -> R.string.clear_authentication_complete
        else -> R.string.clear_authentication_required
    }
    LedgerBanner(
        stringResource(message),
        if (state.localClearAuthenticationPending) LedgerBannerVariant.WARNING else LedgerBannerVariant.INFO,
    )
}

@Composable
private fun localClearFailureMessage(code: String?): String = stringResource(
    when (code) {
        "AUTHENTICATION_REJECTED" -> R.string.clear_failed_authentication
        "WORK_CANCELLATION_FAILED" -> R.string.clear_failed_work
        "LEDGER_CLEAR_FAILED" -> R.string.clear_failed_ledger
        else -> R.string.clear_failed_generic
    },
)

@Composable
private fun Long.localizedDateTime(zoneId: String): String {
    val locale = LocalLocale.current.platformLocale
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(LedgerTheme.timeZone)
    return Instant.ofEpochMilli(this).atZone(zone).format(
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale),
    )
}

@Composable
private fun DeviceSecurity(state: SecurityPrivacySettingsState, actions: SecurityPrivacySettingsActions) {
    if (state.deviceSecurityConfigured) {
        LedgerBanner(stringResource(R.string.security_device_configured), LedgerBannerVariant.INFO)
        LedgerButton(stringResource(R.string.security_continue), actions.onSecurityConfigured, Modifier.fillMaxWidth())
    } else {
        LedgerBanner(stringResource(R.string.security_device_required), LedgerBannerVariant.DANGER)
        LedgerButton(stringResource(R.string.security_open_system), actions.onOpenSystemSecurity, Modifier.fillMaxWidth())
    }
}

@Composable private fun AppLockTimeout.label(): String = stringResource(
    when (this) {
        AppLockTimeout.IMMEDIATE -> R.string.security_timeout_immediate
        AppLockTimeout.ONE_MINUTE -> R.string.security_timeout_one
        AppLockTimeout.FIVE_MINUTES -> R.string.security_timeout_five
        AppLockTimeout.FIFTEEN_MINUTES -> R.string.security_timeout_fifteen
        AppLockTimeout.CUSTOM -> R.string.security_timeout_custom
    },
)

@Composable private fun TrashRetention.label(): String = stringResource(
    when (this) {
        TrashRetention.SEVEN_DAYS -> R.string.security_trash_seven
        TrashRetention.THIRTY_DAYS -> R.string.security_trash_thirty
        TrashRetention.NINETY_DAYS -> R.string.security_trash_ninety
        TrashRetention.NEVER -> R.string.security_trash_never
        TrashRetention.CUSTOM -> R.string.security_trash_custom
    },
)
