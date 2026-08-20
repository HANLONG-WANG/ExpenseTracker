@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions")

package app.ledger.app

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricPrompt
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.BiometricErrorCode
import app.ledger.core.security.OneShotVaultEditor
import app.ledger.core.security.SecretBytes
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.core.security.SensitivePlaintext
import app.ledger.core.security.VaultAction
import app.ledger.core.security.VaultClipboardController
import app.ledger.core.security.VaultEditRequest
import app.ledger.core.security.VaultEncryptedFields
import app.ledger.core.security.VaultExposureRegistry
import app.ledger.core.security.VaultFieldCiphertext
import app.ledger.core.security.VaultFieldType
import app.ledger.core.security.VaultKeyHierarchy
import app.ledger.core.security.VaultPlaintextFields
import app.ledger.core.security.VaultProvisioningRequest
import app.ledger.core.security.VaultRevealRequest
import app.ledger.feature.vault.VaultCardSummary
import app.ledger.feature.vault.VaultEditSubmission
import app.ledger.feature.vault.VaultPresentationState
import app.ledger.feature.vault.VaultRequiredState
import app.ledger.feature.vault.VaultSensitiveValue
import app.ledger.finance.application.CardReferenceView
import app.ledger.finance.application.VaultCiphertext
import app.ledger.finance.application.VaultSecretApplicationPort
import app.ledger.finance.application.VaultSecretRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

internal enum class VaultAuthenticationPurpose { OPEN_LIST, REVEAL_PAN, COPY_PAN, REVEAL_CVC, EDIT_VAULT }

internal data class VaultAuthenticationPrompt(
    val cryptoObject: BiometricPrompt.CryptoObject?,
    val purpose: VaultAuthenticationPurpose,
)

/** Owns every Vault plaintext and authentication request; navigation receives stable IDs only. */
internal class VaultController(
    context: Context,
    private val port: VaultSecretApplicationPort,
    private val exposureRegistry: VaultExposureRegistry,
    private val scope: CoroutineScope,
    private val now: () -> Instant,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val keystore = AndroidKeystoreKeys(applicationContext)
    private val hierarchy = VaultKeyHierarchy(keystore, SecurityEnvelopeStore(applicationContext), exposureRegistry)
    private val clipboard = VaultClipboardController(applicationContext)
    private val mutableState = MutableStateFlow(
        VaultPresentationState("VLT-001", VaultRequiredState.VLT_001_LOCKED),
    )
    val state: StateFlow<VaultPresentationState> = mutableState.asStateFlow()
    private val mutableAuthentication = MutableSharedFlow<VaultAuthenticationPrompt>(extraBufferCapacity = 1)
    val authentication = mutableAuthentication.asSharedFlow()
    private var bookId: StableId? = null
    private var records = emptyMap<StableId, VaultSecretRecord>()
    private var cards = emptyMap<StableId, VaultCardSummary>()
    private var pending: PendingVaultAuthentication? = null
    private var editAuthorization: OneShotVaultEditor? = null
    private var exposedPan: SensitivePlaintext? = null
    private var exposedSecurityCode: SensitivePlaintext? = null
    private var timer: Job? = null

    fun openList(activeBookId: StableId, sourceCards: List<CardReferenceView>) {
        hideSensitive(autoHidden = false)
        bookId = activeBookId
        cards = sourceCards.associate { card ->
            card.id to VaultCardSummary(card.id, card.displayName, card.lastFour, hasSecret = false)
        }
        if (!keystore.vaultAuthenticationAvailable()) {
            mutableState.value = VaultPresentationState(
                "VLT-001",
                VaultRequiredState.VLT_001_DEVICE_SECURITY_MISSING,
                cards.values.toList(),
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            val ids = (port.listCardIds(activeBookId) as? DomainResult.Success)?.value.orEmpty()
            cards = cards.mapValues { (id, card) -> card.copy(hasSecret = id in ids) }
            mutableState.value = VaultPresentationState(
                "VLT-001",
                if (cards.isEmpty()) VaultRequiredState.VLT_001_EMPTY else VaultRequiredState.VLT_001_LOCKED,
                cards.values.sortedBy(VaultCardSummary::displayName),
            )
        }
    }

    fun openCard(cardId: StableId) {
        hideSensitive(autoHidden = false)
        val activeBook = requireNotNull(bookId)
        val card = requireNotNull(cards[cardId])
        scope.launch(Dispatchers.IO) {
            val record = (port.read(activeBook, cardId) as? DomainResult.Success)?.value
            records = if (record == null) records - cardId else records + (cardId to record)
            mutableState.value = VaultPresentationState(
                "VLT-002",
                VaultRequiredState.VLT_002_MASKED,
                cards = cards.values.toList(),
                selectedCard = card.copy(hasSecret = record != null),
            )
        }
    }

    fun openEditor(cardId: StableId) {
        hideSensitive(autoHidden = false)
        mutableState.value = VaultPresentationState(
            "VLT-003",
            VaultRequiredState.VLT_003_AUTH_REQUIRED,
            cards = cards.values.toList(),
            selectedCard = requireNotNull(cards[cardId]),
        )
    }

    fun synchronizeVisibleScreen(screenId: String) {
        if (screenId == mutableState.value.screenId) return
        when (screenId) {
            "VLT-001" -> mutableState.value = VaultPresentationState(
                "VLT-001",
                when {
                    !keystore.vaultAuthenticationAvailable() ->
                        VaultRequiredState.VLT_001_DEVICE_SECURITY_MISSING
                    cards.isEmpty() -> VaultRequiredState.VLT_001_EMPTY
                    else -> VaultRequiredState.VLT_001_LOCKED
                },
                cards.values.sortedBy(VaultCardSummary::displayName),
            )
            "VLT-002" -> mutableState.value.selectedCard?.cardId?.let { cardId ->
                mutableState.value = detailState(cardId, VaultRequiredState.VLT_002_MASKED)
            }
            "VLT-003" -> mutableState.value.selectedCard?.cardId?.let(::openEditor)
        }
    }

    fun requestRevealPrimaryNumber(cardId: StableId) = requestReveal(cardId, VaultFieldType.PAN, VaultAction.REVEAL_PAN)
    fun requestCopyPrimaryNumber(cardId: StableId) = requestReveal(cardId, VaultFieldType.PAN, VaultAction.COPY_PAN)
    fun requestRevealSecurityCode(cardId: StableId) = requestReveal(cardId, VaultFieldType.SECURITY_CODE, VaultAction.REVEAL_SECURITY_CODE)

    fun requestListAuthentication() {
        if (!keystore.vaultAuthenticationAvailable() || cards.isEmpty()) return
        cancelPending()
        pending = PendingVaultAuthentication.OpenList
        mutableState.value = VaultPresentationState(
            "VLT-001",
            VaultRequiredState.VLT_001_LOCKED,
            cards.values.sortedBy(VaultCardSummary::displayName),
            pending = true,
        )
        mutableAuthentication.tryEmit(VaultAuthenticationPrompt(null, VaultAuthenticationPurpose.OPEN_LIST))
    }

    private fun requestReveal(cardId: StableId, field: VaultFieldType, action: VaultAction) {
        cancelPending()
        val activeBook = requireNotNull(bookId)
        val source = records[cardId]?.let { record ->
            val encrypted = when (field) {
                VaultFieldType.PAN -> record.primaryNumber
                VaultFieldType.SECURITY_CODE -> record.securityCode
                else -> null
            }
            encrypted?.let { record to it }
        }
        if (source == null) {
            authenticationFailed()
            return
        }
        val (record, encrypted) = source
        val request = try {
            hierarchy.beginReveal(activeBook, cardId, field, record.keyVersion, action, VaultFieldCiphertext(encrypted.copyBytes()))
        } catch (_: Exception) {
            return authenticationFailed()
        }
        val purpose = when (action) {
            VaultAction.REVEAL_PAN -> VaultAuthenticationPurpose.REVEAL_PAN
            VaultAction.COPY_PAN -> VaultAuthenticationPurpose.COPY_PAN
            VaultAction.REVEAL_SECURITY_CODE -> VaultAuthenticationPurpose.REVEAL_CVC
            VaultAction.EDIT_VAULT -> error("unreachable")
        }
        pending = PendingVaultAuthentication.Reveal(request, purpose, cardId)
        mutableState.value = detailState(cardId, VaultRequiredState.VLT_002_AUTHENTICATING)
        mutableAuthentication.tryEmit(VaultAuthenticationPrompt(request.cryptoObject, purpose))
    }

    fun requestEditAuthentication(cardId: StableId) {
        cancelPending()
        editAuthorization?.close()
        editAuthorization = null
        val activeBook = requireNotNull(bookId)
        val pendingRequest: PendingVaultAuthentication = try {
            if (hierarchy.isProvisioned(activeBook)) {
                val request = hierarchy.beginEdit(activeBook)
                PendingVaultAuthentication.Edit(request, cardId)
            } else {
                val request = hierarchy.beginProvisioning(activeBook)
                PendingVaultAuthentication.Provision(request, cardId)
            }
        } catch (_: Exception) {
            return editAuthenticationFailed(cardId)
        }
        pending = pendingRequest
        mutableState.value = mutableState.value.copy(pending = true)
        mutableAuthentication.tryEmit(VaultAuthenticationPrompt(pendingRequest.cryptoObject, VaultAuthenticationPurpose.EDIT_VAULT))
    }

    fun requestRecoveredVaultRewrap(activeBookId: StableId, recoveredVaultDek: SecretBytes) {
        if (hierarchy.isProvisioned(activeBookId)) {
            recoveredVaultDek.close()
            return
        }
        cancelPending()
        val request = try {
            hierarchy.beginRestore(activeBookId, recoveredVaultDek)
        } catch (error: Exception) {
            recoveredVaultDek.close()
            throw error
        }
        val active = PendingVaultAuthentication.Restore(request, activeBookId)
        pending = active
        mutableAuthentication.tryEmit(VaultAuthenticationPrompt(active.cryptoObject, VaultAuthenticationPurpose.EDIT_VAULT))
    }

    fun authenticationSucceeded(cryptoObject: BiometricPrompt.CryptoObject?) {
        val active = pending ?: return
        pending = null
        if (active === PendingVaultAuthentication.OpenList) {
            mutableState.value = VaultPresentationState(
                "VLT-001",
                VaultRequiredState.VLT_001_UNLOCKED_SESSION,
                cards.values.sortedBy(VaultCardSummary::displayName),
            )
            return
        }
        if (cryptoObject == null) {
            active.close()
            return authenticationFailed()
        }
        try {
            when (active) {
                PendingVaultAuthentication.OpenList -> error("handled before crypto validation")
                is PendingVaultAuthentication.Reveal -> completeReveal(active, cryptoObject)
                is PendingVaultAuthentication.Edit -> {
                    editAuthorization = active.request.complete(cryptoObject)
                    beginEditWindow(active.cardId)
                }
                is PendingVaultAuthentication.Provision -> {
                    editAuthorization = active.request.completeForEditing(cryptoObject)
                    beginEditWindow(active.cardId)
                }
                is PendingVaultAuthentication.Restore -> active.request.complete(cryptoObject)
            }
        } catch (_: Exception) {
            active.close()
            when (active) {
                PendingVaultAuthentication.OpenList -> Unit
                is PendingVaultAuthentication.Reveal -> authenticationFailed()
                is PendingVaultAuthentication.Edit -> editAuthenticationFailed(active.cardId)
                is PendingVaultAuthentication.Provision -> editAuthenticationFailed(active.cardId)
                is PendingVaultAuthentication.Restore -> Unit
            }
        }
    }

    fun authenticationFailed(code: BiometricErrorCode = BiometricErrorCode.UNKNOWN) {
        val active = pending
        pending = null
        active?.close()
        when (active) {
            is PendingVaultAuthentication.Edit -> editAuthenticationFailed(active.cardId)
            is PendingVaultAuthentication.Provision -> editAuthenticationFailed(active.cardId)
            PendingVaultAuthentication.OpenList -> mutableState.value = VaultPresentationState(
                "VLT-001",
                VaultRequiredState.VLT_001_LOCKED,
                cards.values.sortedBy(VaultCardSummary::displayName),
            )
            else -> {
                val cardId = (active as? PendingVaultAuthentication.Reveal)?.cardId ?: mutableState.value.selectedCard?.cardId
                if (cardId != null) mutableState.value = detailState(cardId, VaultRequiredState.VLT_002_AUTH_FAILED)
            }
        }
        code.name
    }

    private fun completeReveal(active: PendingVaultAuthentication.Reveal, cryptoObject: BiometricPrompt.CryptoObject) {
        val plaintext = active.request.complete(cryptoObject)
        when (active.purpose) {
            VaultAuthenticationPurpose.OPEN_LIST -> error("list authentication never reveals a field")
            VaultAuthenticationPurpose.COPY_PAN -> {
                clipboard.copyPrimaryNumber(plaintext)
                plaintext.close()
                mutableState.value = detailState(active.cardId, VaultRequiredState.VLT_002_MASKED)
            }
            VaultAuthenticationPurpose.REVEAL_PAN -> {
                exposedPan?.close()
                exposedPan = plaintext
                publishExposed(active.cardId)
            }
            VaultAuthenticationPurpose.REVEAL_CVC -> {
                exposedSecurityCode?.close()
                exposedSecurityCode = plaintext
                publishExposed(active.cardId)
            }
            VaultAuthenticationPurpose.EDIT_VAULT -> error("unreachable")
        }
    }

    private fun beginEditWindow(cardId: StableId) {
        mutableState.value = VaultPresentationState(
            "VLT-003",
            VaultRequiredState.VLT_003_EDITING,
            cards.values.toList(),
            requireNotNull(cards[cardId]),
        )
        timer?.cancel()
        timer = scope.launch {
            delay(EXPOSURE_MILLIS)
            editAuthorization?.close()
            editAuthorization = null
            if (mutableState.value.screenId == "VLT-003") openEditor(cardId)
        }
    }

    fun save(cardId: StableId, submission: VaultEditSubmission) {
        val editor = editAuthorization ?: return openEditor(cardId)
        editAuthorization = null
        timer?.cancel()
        mutableState.value = mutableState.value.copy(presentation = VaultRequiredState.VLT_003_SAVING, pending = true)
        val fields = VaultPlaintextFields(
            submission.holderName.secretOrNull(),
            submission.primaryNumber.secretOrNull(),
            submission.expiry.secretOrNull(),
            submission.securityCode.secretOrNull(),
            submission.customFields.secretOrNull(),
        )
        scope.launch(Dispatchers.IO) {
            try {
                val encrypted = editor.use { it.encryptFields(requireNotNull(bookId), cardId, KEY_VERSION, fields) }
                val previous = records[cardId]
                val saved = VaultSecretRecord(
                    cardId,
                    encrypted.holderName.toPort() ?: previous?.holderName,
                    encrypted.primaryNumber.toPort() ?: previous?.primaryNumber,
                    encrypted.expiry.toPort() ?: previous?.expiry,
                    encrypted.securityCode.toPort() ?: previous?.securityCode,
                    encrypted.customFields.toPort() ?: previous?.customFields,
                    KEY_VERSION,
                    now(),
                )
                if (port.save(requireNotNull(bookId), saved) is DomainResult.Success) {
                    records = records + (cardId to saved)
                    cards = cards + (cardId to requireNotNull(cards[cardId]).copy(hasSecret = true))
                    mutableState.value = detailState(cardId, VaultRequiredState.VLT_002_MASKED)
                } else {
                    editAuthenticationFailed(cardId)
                }
            } catch (_: Exception) {
                fields.close()
                editor.close()
                editAuthenticationFailed(cardId)
            }
        }
    }

    fun hideSensitive(autoHidden: Boolean = true) {
        timer?.cancel()
        timer = null
        exposedPan?.close()
        exposedPan = null
        exposedSecurityCode?.close()
        exposedSecurityCode = null
        editAuthorization?.close()
        editAuthorization = null
        exposureRegistry.clearAll()
        val cardId = mutableState.value.selectedCard?.cardId
        if (cardId != null && mutableState.value.screenId == "VLT-002") {
            mutableState.value = detailState(
                cardId,
                if (autoHidden) VaultRequiredState.VLT_002_AUTO_HIDDEN else VaultRequiredState.VLT_002_MASKED,
            )
        } else if (cardId != null && mutableState.value.screenId == "VLT-003") {
            mutableState.value = VaultPresentationState(
                "VLT-003",
                VaultRequiredState.VLT_003_AUTH_REQUIRED,
                cards.values.toList(),
                cards[cardId],
            )
        }
    }

    fun onApplicationBackgrounded() {
        cancelPending()
        hideSensitive()
        clipboard.onApplicationBackgrounded()
        if (mutableState.value.screenId == "VLT-001" && cards.isNotEmpty()) {
            mutableState.value = VaultPresentationState(
                "VLT-001",
                VaultRequiredState.VLT_001_LOCKED,
                cards.values.sortedBy(VaultCardSummary::displayName),
            )
        }
    }

    fun onApplicationLocked() {
        onApplicationBackgrounded()
        exposureRegistry.onApplicationLocked()
    }

    private fun publishExposed(cardId: StableId) {
        timer?.cancel()
        timer = scope.launch {
            while (isActive) {
                val remaining = listOfNotNull(exposedPan?.remainingMillis(), exposedSecurityCode?.remainingMillis()).minOrNull() ?: 0L
                if (remaining <= 0L) {
                    hideSensitive()
                    break
                }
                val seconds = ((remaining + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND).toInt()
                mutableState.value = detailState(cardId, VaultRequiredState.VLT_002_REVEALED, seconds)
                delay(TIMER_REFRESH_MILLIS)
            }
        }
    }

    private fun detailState(cardId: StableId, presentation: VaultRequiredState, seconds: Int = 0): VaultPresentationState = VaultPresentationState(
        "VLT-002",
        presentation,
        cards.values.toList(),
        requireNotNull(cards[cardId]),
        exposedPan?.asView(),
        exposedSecurityCode?.asView(),
        seconds,
    )

    private fun authenticationFailed() {
        mutableState.value.selectedCard?.cardId?.let { mutableState.value = detailState(it, VaultRequiredState.VLT_002_AUTH_FAILED) }
    }

    private fun editAuthenticationFailed(cardId: StableId) {
        mutableState.value = VaultPresentationState(
            "VLT-003",
            VaultRequiredState.VLT_003_AUTH_REQUIRED,
            cards.values.toList(),
            cards[cardId],
        )
    }

    private fun cancelPending() {
        pending?.close()
        pending = null
    }

    override fun close() {
        cancelPending()
        hideSensitive(autoHidden = false)
        clipboard.close()
        records = emptyMap()
    }

    private sealed interface PendingVaultAuthentication : AutoCloseable {
        val cryptoObject: BiometricPrompt.CryptoObject?
        val cardId: StableId?

        data object OpenList : PendingVaultAuthentication {
            override val cryptoObject: BiometricPrompt.CryptoObject? = null
            override val cardId: StableId? = null
            override fun close() = Unit
        }

        data class Reveal(
            val request: VaultRevealRequest,
            val purpose: VaultAuthenticationPurpose,
            override val cardId: StableId,
        ) : PendingVaultAuthentication {
            override val cryptoObject: BiometricPrompt.CryptoObject get() = request.cryptoObject
            override fun close() = request.close()
        }

        data class Edit(val request: VaultEditRequest, override val cardId: StableId) : PendingVaultAuthentication {
            override val cryptoObject: BiometricPrompt.CryptoObject get() = request.cryptoObject
            override fun close() = request.close()
        }

        data class Provision(val request: VaultProvisioningRequest, override val cardId: StableId) : PendingVaultAuthentication {
            override val cryptoObject: BiometricPrompt.CryptoObject get() = request.cryptoObject
            override fun close() = request.close()
        }

        data class Restore(val request: VaultProvisioningRequest, override val cardId: StableId) : PendingVaultAuthentication {
            override val cryptoObject: BiometricPrompt.CryptoObject get() = request.cryptoObject
            override fun close() = request.close()
        }
    }

    private companion object {
        const val KEY_VERSION = 1
        const val EXPOSURE_MILLIS = 30_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val TIMER_REFRESH_MILLIS = 250L
    }
}

private fun String.secretOrNull(): SecretBytes? {
    if (isBlank()) return null
    val bytes = toByteArray(Charsets.UTF_8)
    return try {
        SecretBytes.copyOf(bytes)
    } finally {
        bytes.fill(0)
    }
}

private fun VaultFieldCiphertext?.toPort(): VaultCiphertext? = this?.bytes?.let { bytes ->
    try {
        VaultCiphertext.copyOf(bytes)
    } finally {
        bytes.fill(0)
    }
}

private fun SensitivePlaintext.asView(): VaultSensitiveValue = VaultSensitiveValue { consumer ->
    useBytes { bytes -> consumer(bytes.toString(Charsets.UTF_8)) }
}
