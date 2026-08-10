package app.ledger.feature.onboarding

public enum class OnboardingStep(
    public val screenId: String,
    public val optional: Boolean,
) {
    LANGUAGE("ONB-001", false),
    BASE_CURRENCY("ONB-002", false),
    TIME_ZONE("ONB-003", false),
    PRIVACY_POLICY("ONB-004", false),
    TELEMETRY("ONB-005", false),
    APP_LOCK("ONB-006", true),
    BACKUP("ONB-007", true),
    ACCOUNT("ONB-008", true),
    CATEGORY("ONB-009", true),
    COMPLETE("ONB-010", false),
    ;

    public fun next(): OnboardingStep = entries.getOrElse(ordinal + 1) { COMPLETE }

    public fun previous(): OnboardingStep = entries.getOrElse(ordinal - 1) { LANGUAGE }
}

public enum class OnboardingRenderState { CONTENT, VALIDATION_ERROR, SUBMITTING }
public enum class OnboardingLanguage(public val tag: String) { SIMPLIFIED_CHINESE("zh-CN"), JAPANESE("ja"), ENGLISH("en") }
public enum class InitialAccountType { CASH, BANK }
public enum class InitialCategoryDirection { EXPENSE, INCOME }

public data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.LANGUAGE,
    val renderState: OnboardingRenderState = OnboardingRenderState.CONTENT,
    val language: OnboardingLanguage? = null,
    val currencySearch: String = "",
    val baseCurrency: String? = null,
    val zoneSearch: String = "",
    val zoneId: String? = null,
    val privacyAccepted: Boolean = false,
    val telemetryEnabled: Boolean = true,
    val crashReportingEnabled: Boolean = true,
    val appLockEnabled: Boolean = false,
    val appLockTimeoutMillis: Long = 60_000L,
    val recoveryPassword: String = "",
    val recoveryPasswordConfirmation: String = "",
    val accountName: String = "",
    val accountType: InitialAccountType = InitialAccountType.CASH,
    val categoryName: String = "",
    val categoryDirection: InitialCategoryDirection = InitialCategoryDirection.EXPENSE,
    val firstAccountCreated: Boolean = false,
    val firstCategoryCreated: Boolean = false,
    val recoveryConfigured: Boolean = false,
    val errorCode: String? = null,
) {
    init {
        require(appLockTimeoutMillis in ALLOWED_TIMEOUTS)
        require(baseCurrency == null || CURRENCY.matches(baseCurrency))
        require(errorCode == null || ERROR_CODE.matches(errorCode))
    }

    /** Deliberately excludes recovery plaintext from diagnostics and crash representations. */
    override fun toString(): String = "OnboardingUiState(step=$step, renderState=$renderState, language=$language, baseCurrency=$baseCurrency, " +
        "zoneId=$zoneId, privacyAccepted=$privacyAccepted, telemetryEnabled=$telemetryEnabled, " +
        "crashReportingEnabled=$crashReportingEnabled, appLockEnabled=$appLockEnabled, " +
        "recoveryPassword=<redacted>, recoveryPasswordConfirmation=<redacted>, errorCode=$errorCode)"

    public companion object {
        public val ALLOWED_TIMEOUTS: Set<Long> = setOf(0L, 60_000L, 300_000L, 900_000L)
        private val CURRENCY = Regex("[A-Z]{3}")
        private val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{2,47}")
    }
}

public object OnboardingValidator {
    public fun errorCode(state: OnboardingUiState): String? = when (state.step) {
        OnboardingStep.LANGUAGE -> "LANGUAGE_REQUIRED".takeIf { state.language == null }
        OnboardingStep.BASE_CURRENCY -> "CURRENCY_REQUIRED".takeIf { state.baseCurrency == null }
        OnboardingStep.TIME_ZONE -> "TIME_ZONE_REQUIRED".takeIf { state.zoneId.isNullOrBlank() }
        OnboardingStep.PRIVACY_POLICY -> "PRIVACY_CONSENT_REQUIRED".takeUnless { state.privacyAccepted }
        OnboardingStep.TELEMETRY -> null
        OnboardingStep.APP_LOCK -> "LOCK_TIMEOUT_REQUIRED".takeIf {
            state.appLockEnabled && state.appLockTimeoutMillis !in OnboardingUiState.ALLOWED_TIMEOUTS
        }
        OnboardingStep.BACKUP -> when {
            state.recoveryPassword.isEmpty() && state.recoveryPasswordConfirmation.isEmpty() -> null
            state.recoveryPassword.length < MINIMUM_RECOVERY_PASSWORD_LENGTH -> "RECOVERY_PASSWORD_TOO_SHORT"
            state.recoveryPassword != state.recoveryPasswordConfirmation -> "RECOVERY_PASSWORD_MISMATCH"
            else -> null
        }
        OnboardingStep.ACCOUNT -> "ACCOUNT_NAME_REQUIRED".takeIf { state.accountName.isBlank() }
        OnboardingStep.CATEGORY -> "CATEGORY_NAME_REQUIRED".takeIf { state.categoryName.isBlank() }
        OnboardingStep.COMPLETE -> null
    }

    private const val MINIMUM_RECOVERY_PASSWORD_LENGTH = 12
}
