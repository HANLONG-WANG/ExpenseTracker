@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MatchingDeclarationName",
    "ktlint:standard:function-naming",
    "FunctionNaming",
)

package app.ledger.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerProgressIndicator
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerSnackbarController
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.rememberLedgerSnackbarController

public data class OnboardingActions(
    val onLanguage: (OnboardingLanguage) -> Unit,
    val onCurrencySearch: (String) -> Unit,
    val onCurrency: (String) -> Unit,
    val onZoneSearch: (String) -> Unit,
    val onZone: (String) -> Unit,
    val onPrivacyAccepted: (Boolean) -> Unit,
    val onTelemetry: (Boolean) -> Unit,
    val onCrashReporting: (Boolean) -> Unit,
    val onAppLock: (Boolean) -> Unit,
    val onAppLockTimeout: (Long) -> Unit,
    val onRecoveryPassword: (String) -> Unit,
    val onRecoveryPasswordConfirmation: (String) -> Unit,
    val onAccountName: (String) -> Unit,
    val onAccountType: (InitialAccountType) -> Unit,
    val onCategoryName: (String) -> Unit,
    val onCategoryDirection: (InitialCategoryDirection) -> Unit,
    val onBack: () -> Unit,
    val onNext: () -> Unit,
    val onSkip: () -> Unit,
)

@Composable
public fun OnboardingScreen(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
    snackbarController: LedgerSnackbarController = rememberLedgerSnackbarController(),
) {
    LedgerScaffold(
        modifier.fillMaxSize().testTag(LedgerTestTags.ONBOARDING_ROOT),
        snackbarController = snackbarController,
        formContent = true,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            LedgerText(
                stringResource(R.string.onboarding_progress, state.step.ordinal + 1, OnboardingStep.entries.size),
                LedgerTextRole.LABEL,
            )
            LedgerProgressIndicator(
                progress = (state.step.ordinal + 1f) / OnboardingStep.entries.size,
                accessibleText = stringResource(R.string.onboarding_progress_accessible, state.step.ordinal + 1),
            )
            LedgerText(stepTitle(state.step), LedgerTextRole.TITLE)
            LedgerText(stepExplanation(state.step), LedgerTextRole.SUPPORTING)
            if (state.renderState == OnboardingRenderState.VALIDATION_ERROR) {
                LedgerBanner(
                    message = validationMessage(state.errorCode),
                    variant = LedgerBannerVariant.DANGER,
                )
            }
            StepContent(state, actions)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
            ) {
                if (state.step != OnboardingStep.LANGUAGE) {
                    LedgerButton(
                        stringResource(R.string.onboarding_back),
                        actions.onBack,
                        Modifier.weight(1f).testTag(LedgerTestTags.ONBOARDING_SECONDARY),
                        variant = LedgerButtonVariant.SECONDARY,
                        enabled = state.renderState != OnboardingRenderState.SUBMITTING,
                    )
                }
                if (state.step.optional) {
                    LedgerButton(
                        stringResource(R.string.onboarding_later),
                        actions.onSkip,
                        Modifier.weight(1f),
                        variant = LedgerButtonVariant.TEXT,
                        enabled = state.renderState != OnboardingRenderState.SUBMITTING,
                    )
                }
                LedgerButton(
                    if (state.step == OnboardingStep.COMPLETE) {
                        stringResource(R.string.onboarding_start)
                    } else {
                        stringResource(R.string.onboarding_continue)
                    },
                    actions.onNext,
                    Modifier.weight(1f).testTag(LedgerTestTags.ONBOARDING_PRIMARY),
                    enabled = state.renderState != OnboardingRenderState.SUBMITTING,
                )
            }
        }
    }
}

@Composable
private fun StepContent(state: OnboardingUiState, actions: OnboardingActions) {
    when (state.step) {
        OnboardingStep.LANGUAGE -> OnboardingLanguage.entries.forEach { language ->
            LedgerChoiceRow(languageLabel(language), state.language == language, { actions.onLanguage(language) })
        }
        OnboardingStep.BASE_CURRENCY -> {
            LedgerTextField(
                state.currencySearch,
                actions.onCurrencySearch,
                stringResource(R.string.onboarding_currency_search),
            )
            listOf("JPY", "CNY", "USD", "EUR").filter {
                state.currencySearch.isBlank() || it.contains(state.currencySearch.trim(), ignoreCase = true)
            }.forEach { currency ->
                LedgerChoiceRow(currency, state.baseCurrency == currency, { actions.onCurrency(currency) })
            }
        }
        OnboardingStep.TIME_ZONE -> {
            LedgerTextField(state.zoneSearch, actions.onZoneSearch, stringResource(R.string.onboarding_zone_search))
            listOf("Asia/Tokyo", "Asia/Shanghai", "UTC").filter {
                state.zoneSearch.isBlank() || it.contains(state.zoneSearch.trim(), ignoreCase = true)
            }.forEach { zone -> LedgerChoiceRow(zone, state.zoneId == zone, { actions.onZone(zone) }) }
            if (state.zoneId != null) LedgerText(state.zoneId, LedgerTextRole.BODY)
        }
        OnboardingStep.PRIVACY_POLICY -> {
            LedgerText(stringResource(R.string.onboarding_privacy_document), LedgerTextRole.BODY)
            LedgerToggleRow(
                stringResource(R.string.onboarding_privacy_consent),
                state.privacyAccepted,
                actions.onPrivacyAccepted,
                supportingText = stringResource(R.string.onboarding_required),
            )
        }
        OnboardingStep.TELEMETRY -> {
            LedgerText(stringResource(R.string.onboarding_telemetry_summary), LedgerTextRole.BODY)
            LedgerToggleRow(stringResource(R.string.onboarding_telemetry_toggle), state.telemetryEnabled, actions.onTelemetry)
            LedgerToggleRow(stringResource(R.string.onboarding_crash_toggle), state.crashReportingEnabled, actions.onCrashReporting)
        }
        OnboardingStep.APP_LOCK -> {
            LedgerToggleRow(
                stringResource(R.string.onboarding_lock_toggle),
                state.appLockEnabled,
                actions.onAppLock,
                supportingText = stringResource(R.string.onboarding_lock_scope),
            )
            if (state.appLockEnabled) {
                OnboardingUiState.ALLOWED_TIMEOUTS.sorted().forEach { timeout ->
                    LedgerChoiceRow(
                        timeoutLabel(timeout),
                        state.appLockTimeoutMillis == timeout,
                        { actions.onAppLockTimeout(timeout) },
                    )
                }
            }
        }
        OnboardingStep.BACKUP -> {
            LedgerText(stringResource(R.string.onboarding_recovery_warning), LedgerTextRole.BODY)
            LedgerTextField(
                state.recoveryPassword,
                actions.onRecoveryPassword,
                stringResource(R.string.onboarding_recovery_password),
                keyboardType = KeyboardType.Password,
                sensitive = true,
            )
            LedgerTextField(
                state.recoveryPasswordConfirmation,
                actions.onRecoveryPasswordConfirmation,
                stringResource(R.string.onboarding_recovery_confirm),
                keyboardType = KeyboardType.Password,
                sensitive = true,
            )
            LedgerText(stringResource(R.string.onboarding_backup_later_note), LedgerTextRole.SUPPORTING)
        }
        OnboardingStep.ACCOUNT -> {
            LedgerTextField(state.accountName, actions.onAccountName, stringResource(R.string.onboarding_account_name))
            InitialAccountType.entries.forEach { type ->
                LedgerChoiceRow(accountTypeLabel(type), state.accountType == type, { actions.onAccountType(type) })
            }
        }
        OnboardingStep.CATEGORY -> {
            LedgerTextField(state.categoryName, actions.onCategoryName, stringResource(R.string.onboarding_category_name))
            InitialCategoryDirection.entries.forEach { direction ->
                LedgerChoiceRow(categoryDirectionLabel(direction), state.categoryDirection == direction, { actions.onCategoryDirection(direction) })
            }
        }
        OnboardingStep.COMPLETE -> {
            LedgerText(stringResource(R.string.onboarding_complete_summary), LedgerTextRole.BODY)
            LedgerText(
                stringResource(
                    R.string.onboarding_complete_optional_summary,
                    if (state.firstAccountCreated) stringResource(R.string.onboarding_created) else stringResource(R.string.onboarding_skipped),
                    if (state.firstCategoryCreated) stringResource(R.string.onboarding_created) else stringResource(R.string.onboarding_skipped),
                    if (state.recoveryConfigured) stringResource(R.string.onboarding_configured) else stringResource(R.string.onboarding_skipped),
                ),
                LedgerTextRole.SUPPORTING,
            )
        }
    }
}

@Composable private fun stepTitle(step: OnboardingStep): String = stringResource(
    when (step) {
        OnboardingStep.LANGUAGE -> R.string.onboarding_language_title
        OnboardingStep.BASE_CURRENCY -> R.string.onboarding_currency_title
        OnboardingStep.TIME_ZONE -> R.string.onboarding_zone_title
        OnboardingStep.PRIVACY_POLICY -> R.string.onboarding_privacy_title
        OnboardingStep.TELEMETRY -> R.string.onboarding_telemetry_title
        OnboardingStep.APP_LOCK -> R.string.onboarding_lock_title
        OnboardingStep.BACKUP -> R.string.onboarding_backup_title
        OnboardingStep.ACCOUNT -> R.string.onboarding_account_title
        OnboardingStep.CATEGORY -> R.string.onboarding_category_title
        OnboardingStep.COMPLETE -> R.string.onboarding_complete_title
    },
)

@Composable private fun stepExplanation(step: OnboardingStep): String = stringResource(
    when (step) {
        OnboardingStep.LANGUAGE -> R.string.onboarding_language_explanation
        OnboardingStep.BASE_CURRENCY -> R.string.onboarding_currency_explanation
        OnboardingStep.TIME_ZONE -> R.string.onboarding_zone_explanation
        OnboardingStep.PRIVACY_POLICY -> R.string.onboarding_privacy_explanation
        OnboardingStep.TELEMETRY -> R.string.onboarding_telemetry_explanation
        OnboardingStep.APP_LOCK -> R.string.onboarding_lock_explanation
        OnboardingStep.BACKUP -> R.string.onboarding_backup_explanation
        OnboardingStep.ACCOUNT -> R.string.onboarding_account_explanation
        OnboardingStep.CATEGORY -> R.string.onboarding_category_explanation
        OnboardingStep.COMPLETE -> R.string.onboarding_complete_explanation
    },
)

@Composable private fun validationMessage(code: String?): String = when (code) {
    "LANGUAGE_REQUIRED" -> stringResource(R.string.onboarding_error_language)
    "CURRENCY_REQUIRED" -> stringResource(R.string.onboarding_error_currency)
    "TIME_ZONE_REQUIRED" -> stringResource(R.string.onboarding_error_zone)
    "PRIVACY_CONSENT_REQUIRED" -> stringResource(R.string.onboarding_error_privacy)
    "RECOVERY_PASSWORD_TOO_SHORT" -> stringResource(R.string.onboarding_error_password_short)
    "RECOVERY_PASSWORD_MISMATCH" -> stringResource(R.string.onboarding_error_password_mismatch)
    "ACCOUNT_NAME_REQUIRED" -> stringResource(R.string.onboarding_error_account)
    "CATEGORY_NAME_REQUIRED" -> stringResource(R.string.onboarding_error_category)
    "DEVICE_SECURITY_REQUIRED" -> stringResource(R.string.onboarding_error_device_security)
    else -> stringResource(R.string.onboarding_error_generic)
}

@Composable private fun languageLabel(value: OnboardingLanguage): String = stringResource(
    when (value) {
        OnboardingLanguage.SIMPLIFIED_CHINESE -> R.string.onboarding_language_zh
        OnboardingLanguage.JAPANESE -> R.string.onboarding_language_ja
        OnboardingLanguage.ENGLISH -> R.string.onboarding_language_en
    },
)

@Composable private fun accountTypeLabel(value: InitialAccountType): String = stringResource(
    if (value == InitialAccountType.CASH) R.string.onboarding_account_cash else R.string.onboarding_account_bank,
)

@Composable private fun categoryDirectionLabel(value: InitialCategoryDirection): String = stringResource(
    if (value == InitialCategoryDirection.EXPENSE) R.string.onboarding_category_expense else R.string.onboarding_category_income,
)

@Composable private fun timeoutLabel(value: Long): String = when (value) {
    0L -> stringResource(R.string.onboarding_timeout_immediate)
    60_000L -> stringResource(R.string.onboarding_timeout_one_minute)
    300_000L -> stringResource(R.string.onboarding_timeout_five_minutes)
    else -> stringResource(R.string.onboarding_timeout_fifteen_minutes)
}
