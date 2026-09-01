@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")

package app.ledger.feature.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.SensitiveValueField
import app.ledger.core.designsystem.rememberLedgerRetainedState

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
            "VLT-003" -> VaultEditor(state, actions, Modifier.weight(1f))
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
    if (state.presentation == VaultRequiredState.VLT_001_LOCKED) {
        LedgerBanner(
            stringResource(R.string.vault_locked_body, state.cards.size),
            LedgerBannerVariant.WARNING,
        )
        LedgerButton(
            stringResource(if (state.pending) R.string.vault_authenticating else R.string.vault_unlock_list),
            actions.onAuthenticateList,
            Modifier.fillMaxWidth(),
            enabled = !state.pending,
        )
        return
    }
    if (state.cards.isEmpty() || state.presentation == VaultRequiredState.VLT_001_EMPTY) {
        LedgerEmptyState(
            stringResource(R.string.vault_empty),
            stringResource(R.string.vault_empty_body),
            stringResource(R.string.vault_open_cards),
            actions.onOpenCards,
        )
        return
    }
    LedgerBanner(stringResource(R.string.vault_unlocked_session), LedgerBannerVariant.INFO)
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
private fun VaultEditor(state: VaultPresentationState, actions: VaultActions, modifier: Modifier = Modifier) {
    val card = requireNotNull(state.selectedCard)
    if (state.presentation == VaultRequiredState.VLT_003_AUTH_REQUIRED) {
        LedgerBanner(stringResource(R.string.vault_edit_auth_required), LedgerBannerVariant.WARNING)
        LedgerButton(stringResource(R.string.vault_authenticate_edit), { actions.onAuthenticateEdit(card.cardId) }, Modifier.fillMaxWidth())
        return
    }
    if (state.presentation == VaultRequiredState.VLT_003_SAVING) {
        LedgerLoadingState(modifier, stringResource(R.string.vault_saving))
        return
    }
    val initial = remember(card.cardId, state.editValues) { state.editValues.toEditorDraft() }
    var holder by rememberLedgerRetainedState("vault.holder") { initial.holder }
    var number by rememberLedgerRetainedState("vault.number") { initial.number }
    var expiry by rememberLedgerRetainedState("vault.expiry") { initial.expiry }
    var code by rememberLedgerRetainedState("vault.code") { initial.code }
    var customFields by rememberLedgerRetainedState("vault.customFields") { initial.customFields }
    var nextCustomFieldId by rememberLedgerRetainedState("vault.nextCustomFieldId") { initial.customFields.size.toLong() }
    var saveAttempted by rememberLedgerRetainedState("vault.saveAttempted") { false }
    val serializedCustomFields = customFields
        .filter { it.label.isNotBlank() || it.value.isNotBlank() }
        .joinToString("\n") { "${it.label.trim()}: ${it.value}" }
    val customFieldsComplete = customFields.all { it.label.isBlank() == it.value.isBlank() }
    val changed = holder != initial.holder || number != initial.number || expiry != initial.expiry || code != initial.code || customFields != initial.customFields
    val saving = false
    val valid = changed && customFieldsComplete && serializedCustomFields.length <= VaultEditSubmission.MAXIMUM_CUSTOM_FIELDS_CHARACTERS

    LedgerScaffold(
        modifier = modifier,
        formContent = true,
        fixedAction = {
            VaultSaveBar(saving, !state.pending) {
                saveAttempted = true
                if (valid) {
                    actions.onSave(
                        card.cardId,
                        VaultEditSubmission(holder, number, expiry, code, serializedCustomFields),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        ) {
            item { LedgerBanner(stringResource(R.string.vault_authenticated_values_hint), LedgerBannerVariant.INFO) }
            if (saveAttempted && !changed) {
                item { LedgerBanner(stringResource(R.string.vault_no_changes), LedgerBannerVariant.INFO) }
            }
            item {
                SensitiveInput(
                    holder,
                    { holder = it.take(VaultEditSubmission.MAXIMUM_HOLDER_NAME_CHARACTERS) },
                    stringResource(R.string.vault_holder),
                    enabled = !saving,
                )
            }
            item {
                SensitiveInput(
                    number,
                    { number = it.filter(Char::isDigit).take(VaultEditSubmission.MAXIMUM_PRIMARY_NUMBER_DIGITS) },
                    stringResource(R.string.vault_primary_number),
                    enabled = !saving,
                )
            }
            item {
                SensitiveInput(
                    expiry,
                    { expiry = it.take(VaultEditSubmission.MAXIMUM_EXPIRY_CHARACTERS) },
                    stringResource(R.string.vault_expiry),
                    enabled = !saving,
                )
            }
            item {
                SensitiveInput(
                    code,
                    { code = it.filter(Char::isDigit).take(VaultEditSubmission.MAXIMUM_SECURITY_CODE_DIGITS) },
                    stringResource(R.string.vault_security_code),
                    enabled = !saving,
                )
            }
            item { LedgerText(stringResource(R.string.vault_custom_fields), LedgerTextRole.SECTION) }
            items(customFields, key = VaultCustomField::id) { field ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
                    ) {
                        LedgerTextField(
                            field.label,
                            { updated ->
                                customFields = customFields.map {
                                    if (it.id == field.id) it.copy(label = updated.take(VaultCustomField.MAXIMUM_LABEL_CHARACTERS)) else it
                                }
                            },
                            stringResource(R.string.vault_custom_field_label),
                            enabled = !saving,
                        )
                        SensitiveInput(
                            field.value,
                            { updated ->
                                customFields = customFields.map {
                                    if (it.id == field.id) it.copy(value = updated.take(VaultCustomField.MAXIMUM_VALUE_CHARACTERS)) else it
                                }
                            },
                            stringResource(R.string.vault_custom_field_value),
                            enabled = !saving,
                        )
                        LedgerButton(
                            stringResource(R.string.vault_remove_custom_field),
                            { customFields = customFields.filterNot { it.id == field.id } },
                            Modifier.fillMaxWidth(),
                            LedgerButtonVariant.SECONDARY,
                            enabled = !saving,
                        )
                    }
                }
            }
            item {
                LedgerButton(
                    stringResource(R.string.vault_add_custom_field),
                    {
                        customFields = customFields + VaultCustomField(nextCustomFieldId, "", "")
                        nextCustomFieldId += 1L
                    },
                    Modifier.fillMaxWidth(),
                    LedgerButtonVariant.SECONDARY,
                    enabled = !saving,
                )
            }
            if (!customFieldsComplete) {
                item { LedgerBanner(stringResource(R.string.vault_custom_field_incomplete), LedgerBannerVariant.WARNING) }
            }
            if (serializedCustomFields.length > VaultEditSubmission.MAXIMUM_CUSTOM_FIELDS_CHARACTERS) {
                item { LedgerBanner(stringResource(R.string.vault_custom_fields_too_long), LedgerBannerVariant.DANGER) }
            }
        }
    }
}

@Composable
private fun SensitiveInput(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    LedgerTextField(
        value,
        onValueChange,
        label,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        sensitive = true,
    )
}

@Composable
private fun VaultSaveBar(saving: Boolean, enabled: Boolean, onSave: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            LedgerButton(
                stringResource(if (saving) R.string.vault_saving else R.string.vault_save),
                onSave,
                Modifier.fillMaxWidth().testTag(LedgerTestTags.SAVE),
                enabled = enabled,
            )
        }
    }
}

private data class VaultCustomField(val id: Long, val label: String, val value: String) {
    companion object {
        const val MAXIMUM_LABEL_CHARACTERS: Int = 80
        const val MAXIMUM_VALUE_CHARACTERS: Int = 500
    }
}

private data class VaultEditorDraft(
    val holder: String,
    val number: String,
    val expiry: String,
    val code: String,
    val customFields: List<VaultCustomField>,
)

private fun VaultEditValues?.toEditorDraft(): VaultEditorDraft {
    val serialized = this?.customFields.readOrEmpty()
    return VaultEditorDraft(
        this?.holderName.readOrEmpty(),
        this?.primaryNumber.readOrEmpty(),
        this?.expiry.readOrEmpty(),
        this?.securityCode.readOrEmpty(),
        serialized.lineSequence().filter(String::isNotBlank).mapIndexed { index, line ->
            val parts = line.split(": ", limit = 2)
            VaultCustomField(index.toLong(), parts.first(), parts.getOrElse(1) { "" })
        }.toList(),
    )
}

private fun VaultSensitiveValue?.readOrEmpty(): String {
    var value = ""
    runCatching { this?.readUtf8 { value = it } }
    return value
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
