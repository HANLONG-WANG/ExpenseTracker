@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")

package app.ledger.feature.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.SensitiveValueField

@Composable
public fun VaultDestination(
    state: VaultPresentationState,
    actions: VaultActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().testTag("vault_root").padding(vertical = LedgerTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        when (state.screenId) {
            "VLT-001" -> VaultList(state, actions)
            "VLT-002" -> VaultDetail(state, actions)
            "VLT-003" -> VaultEditor(state, actions)
            "VLT-004" -> VaultAuthenticationStatus(state)
        }
    }
}

@Composable
private fun VaultList(state: VaultPresentationState, actions: VaultActions) {
    LedgerBanner(stringResource(R.string.vault_security_banner), LedgerBannerVariant.INFO)
    if (state.presentation == VaultRequiredState.VLT_001_DEVICE_SECURITY_MISSING) {
        LedgerBanner(
            stringResource(R.string.vault_device_security_missing),
            LedgerBannerVariant.DANGER,
            actionLabel = stringResource(R.string.vault_open_security_settings),
            onAction = actions.onOpenDeviceSecurity,
        )
        return
    }
    if (state.cards.isEmpty() || state.presentation == VaultRequiredState.VLT_001_EMPTY) {
        LedgerEmptyState(
            stringResource(R.string.vault_empty),
            stringResource(R.string.vault_empty_body),
            stringResource(R.string.vault_open_cards),
            actions.onOpenDeviceSecurity,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        items(state.cards, key = { it.cardId.toString() }) { card ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onCard(card.cardId) }) {
                Row(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        LedgerText(card.displayName, LedgerTextRole.SECTION)
                        LedgerText(
                            card.lastFour?.let { stringResource(R.string.vault_tail, it) }
                                ?: stringResource(R.string.vault_no_tail),
                            LedgerTextRole.SUPPORTING,
                        )
                    }
                    LedgerText(
                        stringResource(if (card.hasSecret) R.string.vault_configured else R.string.vault_not_configured),
                        LedgerTextRole.LABEL,
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultDetail(state: VaultPresentationState, actions: VaultActions) {
    val card = requireNotNull(state.selectedCard)
    LedgerText(card.displayName, LedgerTextRole.TITLE)
    LedgerText(card.lastFour?.let { stringResource(R.string.vault_tail, it) } ?: stringResource(R.string.vault_no_tail), LedgerTextRole.SUPPORTING)
    if (state.presentation == VaultRequiredState.VLT_002_AUTO_HIDDEN) {
        LedgerBanner(stringResource(R.string.vault_auto_hidden), LedgerBannerVariant.INFO)
    } else if (state.presentation == VaultRequiredState.VLT_002_AUTH_FAILED) {
        LedgerBanner(stringResource(R.string.vault_auth_failed), LedgerBannerVariant.DANGER)
    } else if (state.presentation == VaultRequiredState.VLT_002_AUTHENTICATING) {
        LedgerBanner(stringResource(R.string.vault_authenticating), LedgerBannerVariant.INFO)
    }
    var pan = ""
    state.primaryNumber?.readUtf8 { pan = it }
    SensitiveValueField(
        revealedValue = pan,
        revealed = state.primaryNumber != null,
        onReveal = { actions.onRevealPrimaryNumber(card.cardId) },
        onHide = actions.onHide,
        secondsRemaining = state.secondsRemaining,
        copyAllowed = true,
        onCopy = { actions.onCopyPrimaryNumber(card.cardId) },
    )
    var securityCode = ""
    state.securityCode?.readUtf8 { securityCode = it }
    LedgerText(stringResource(R.string.vault_security_code), LedgerTextRole.SECTION)
    SensitiveValueField(
        revealedValue = securityCode,
        revealed = state.securityCode != null,
        onReveal = { actions.onRevealSecurityCode(card.cardId) },
        onHide = actions.onHide,
        secondsRemaining = state.secondsRemaining,
        copyAllowed = false,
        onCopy = null,
    )
    LedgerButton(stringResource(R.string.vault_edit), { actions.onEdit(card.cardId) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
}

@Composable
private fun VaultEditor(state: VaultPresentationState, actions: VaultActions) {
    val card = requireNotNull(state.selectedCard)
    if (state.presentation == VaultRequiredState.VLT_003_AUTH_REQUIRED) {
        LedgerBanner(stringResource(R.string.vault_edit_auth_required), LedgerBannerVariant.WARNING)
        LedgerButton(stringResource(R.string.vault_authenticate_edit), { actions.onAuthenticateEdit(card.cardId) }, Modifier.fillMaxWidth())
        return
    }
    var holder by remember(card.cardId) { mutableStateOf("") }
    var number by remember(card.cardId) { mutableStateOf("") }
    var expiry by remember(card.cardId) { mutableStateOf("") }
    var code by remember(card.cardId) { mutableStateOf("") }
    var custom by remember(card.cardId) { mutableStateOf("") }
    if (state.presentation == VaultRequiredState.VLT_003_SAVING) LedgerBanner(stringResource(R.string.vault_saving), LedgerBannerVariant.INFO)
    SensitiveInput(
        holder,
        { holder = it.take(VaultEditSubmission.MAXIMUM_HOLDER_NAME_CHARACTERS) },
        stringResource(R.string.vault_holder),
    )
    SensitiveInput(
        number,
        { number = it.filter(Char::isDigit).take(VaultEditSubmission.MAXIMUM_PRIMARY_NUMBER_DIGITS) },
        stringResource(R.string.vault_primary_number),
    )
    SensitiveInput(
        expiry,
        { expiry = it.take(VaultEditSubmission.MAXIMUM_EXPIRY_CHARACTERS) },
        stringResource(R.string.vault_expiry),
    )
    SensitiveInput(
        code,
        { code = it.filter(Char::isDigit).take(VaultEditSubmission.MAXIMUM_SECURITY_CODE_DIGITS) },
        stringResource(R.string.vault_security_code),
    )
    SensitiveInput(
        custom,
        { custom = it.take(VaultEditSubmission.MAXIMUM_CUSTOM_FIELDS_CHARACTERS) },
        stringResource(R.string.vault_custom_fields),
    )
    LedgerButton(
        stringResource(R.string.vault_save),
        { actions.onSave(card.cardId, VaultEditSubmission(holder, number, expiry, code, custom)) },
        Modifier.fillMaxWidth(),
        enabled = !state.pending && listOf(holder, number, expiry, code, custom).any(String::isNotBlank),
    )
}

@Composable
private fun SensitiveInput(value: String, onValueChange: (String) -> Unit, label: String) {
    LedgerTextField(
        value,
        onValueChange,
        label,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
    )
}

@Composable
private fun VaultAuthenticationStatus(state: VaultPresentationState) {
    val message = when (state.presentation) {
        VaultRequiredState.VLT_004_PROMPT -> R.string.vault_authenticating
        VaultRequiredState.VLT_004_SUCCESS -> R.string.vault_auth_success
        VaultRequiredState.VLT_004_FAILURE -> R.string.vault_auth_failed
        VaultRequiredState.VLT_004_CANCELLED -> R.string.vault_auth_cancelled
        else -> error("invalid VLT-004 presentation")
    }
    LedgerBanner(stringResource(message), if (state.presentation == VaultRequiredState.VLT_004_FAILURE) LedgerBannerVariant.DANGER else LedgerBannerVariant.INFO)
}
