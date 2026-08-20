package app.ledger.feature.vault

import app.ledger.core.common.StableId

public enum class VaultRequiredState(public val screenId: String, public val contractName: String) {
    VLT_001_LOCKED("VLT-001", "locked"),
    VLT_001_UNLOCKED_SESSION("VLT-001", "unlockedSession"),
    VLT_001_DEVICE_SECURITY_MISSING("VLT-001", "deviceSecurityMissing"),
    VLT_001_EMPTY("VLT-001", "empty"),
    VLT_002_MASKED("VLT-002", "masked"),
    VLT_002_AUTHENTICATING("VLT-002", "authenticating"),
    VLT_002_REVEALED("VLT-002", "revealed"),
    VLT_002_AUTO_HIDDEN("VLT-002", "autoHidden"),
    VLT_002_AUTH_FAILED("VLT-002", "authFailed"),
    VLT_003_AUTH_REQUIRED("VLT-003", "authRequired"),
    VLT_003_EDITING("VLT-003", "editing"),
    VLT_003_SAVING("VLT-003", "saving"),
    VLT_004_PROMPT("VLT-004", "prompt"),
    VLT_004_SUCCESS("VLT-004", "success"),
    VLT_004_FAILURE("VLT-004", "failure"),
    VLT_004_CANCELLED("VLT-004", "cancelled"),
}

public data class VaultCardSummary(
    val cardId: StableId,
    val displayName: String,
    val lastFour: String?,
    val hasSecret: Boolean,
) {
    init {
        require(displayName.isNotBlank())
        require(lastFour == null || Regex("[0-9]{4}").matches(lastFour))
    }
}

/** A non-serializable, non-string view over a short-lived core-security exposure handle. */
public fun interface VaultSensitiveValue {
    public fun readUtf8(consumer: (String) -> Unit)
}

public data class VaultPresentationState(
    val screenId: String,
    val presentation: VaultRequiredState,
    val cards: List<VaultCardSummary> = emptyList(),
    val selectedCard: VaultCardSummary? = null,
    val primaryNumber: VaultSensitiveValue? = null,
    val securityCode: VaultSensitiveValue? = null,
    val secondsRemaining: Int = 0,
    val pending: Boolean = false,
) {
    init {
        require(screenId in setOf("VLT-001", "VLT-002", "VLT-003", "VLT-004"))
        require(presentation.screenId == screenId)
        require(secondsRemaining in 0..MAXIMUM_EXPOSURE_SECONDS)
    }

    public companion object {
        public const val MAXIMUM_EXPOSURE_SECONDS: Int = 30
    }
}

public data class VaultEditSubmission(
    val holderName: String,
    val primaryNumber: String,
    val expiry: String,
    val securityCode: String,
    val customFields: String,
) {
    init {
        require(holderName.length <= MAXIMUM_HOLDER_NAME_CHARACTERS)
        require(primaryNumber.length <= MAXIMUM_PRIMARY_NUMBER_DIGITS)
        require(expiry.length <= MAXIMUM_EXPIRY_CHARACTERS)
        require(securityCode.length <= MAXIMUM_SECURITY_CODE_DIGITS)
        require(customFields.length <= MAXIMUM_CUSTOM_FIELDS_CHARACTERS)
        require(primaryNumber.isBlank() || primaryNumber.all(Char::isDigit))
        require(securityCode.isBlank() || securityCode.all(Char::isDigit))
    }

    public companion object {
        public const val MAXIMUM_HOLDER_NAME_CHARACTERS: Int = 120
        public const val MAXIMUM_PRIMARY_NUMBER_DIGITS: Int = 32
        public const val MAXIMUM_EXPIRY_CHARACTERS: Int = 16
        public const val MAXIMUM_SECURITY_CODE_DIGITS: Int = 8
        public const val MAXIMUM_CUSTOM_FIELDS_CHARACTERS: Int = 1_000
    }
}

public sealed interface VaultScreenAction {
    public data class CardSelected(val cardId: StableId) : VaultScreenAction
    public data class Edit(val cardId: StableId) : VaultScreenAction
    public data class RevealPrimaryNumber(val cardId: StableId) : VaultScreenAction
    public data class CopyPrimaryNumber(val cardId: StableId) : VaultScreenAction
    public data class RevealSecurityCode(val cardId: StableId) : VaultScreenAction
    public data object Hide : VaultScreenAction
    public data class AuthenticateEdit(val cardId: StableId) : VaultScreenAction
    public data class Save(val cardId: StableId, val submission: VaultEditSubmission) : VaultScreenAction
    public data object OpenDeviceSecurity : VaultScreenAction
}

internal class VaultActions(
    val onCard: (StableId) -> Unit,
    val onEdit: (StableId) -> Unit,
    val onRevealPrimaryNumber: (StableId) -> Unit,
    val onCopyPrimaryNumber: (StableId) -> Unit,
    val onRevealSecurityCode: (StableId) -> Unit,
    val onHide: () -> Unit,
    val onAuthenticateEdit: (StableId) -> Unit,
    val onSave: (StableId, VaultEditSubmission) -> Unit,
    val onOpenDeviceSecurity: () -> Unit,
)

internal fun vaultActions(onAction: (VaultScreenAction) -> Unit): VaultActions = VaultActions(
    onCard = { onAction(VaultScreenAction.CardSelected(it)) },
    onEdit = { onAction(VaultScreenAction.Edit(it)) },
    onRevealPrimaryNumber = { onAction(VaultScreenAction.RevealPrimaryNumber(it)) },
    onCopyPrimaryNumber = { onAction(VaultScreenAction.CopyPrimaryNumber(it)) },
    onRevealSecurityCode = { onAction(VaultScreenAction.RevealSecurityCode(it)) },
    onHide = { onAction(VaultScreenAction.Hide) },
    onAuthenticateEdit = { onAction(VaultScreenAction.AuthenticateEdit(it)) },
    onSave = { id, submission -> onAction(VaultScreenAction.Save(id, submission)) },
    onOpenDeviceSecurity = { onAction(VaultScreenAction.OpenDeviceSecurity) },
)
