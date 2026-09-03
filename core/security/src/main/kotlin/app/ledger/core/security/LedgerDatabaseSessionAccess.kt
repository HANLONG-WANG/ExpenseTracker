package app.ledger.core.security

import app.ledger.core.common.StableId
import app.ledger.core.database.LedgerDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class LedgerAccessMode {
    READ,
    WRITE,
}

sealed interface LedgerAccessPurpose {
    val mode: LedgerAccessMode

    data class UiRead(val generation: Long) : LedgerAccessPurpose {
        override val mode: LedgerAccessMode = LedgerAccessMode.READ
    }

    data class UiWrite(val generation: Long) : LedgerAccessPurpose {
        override val mode: LedgerAccessMode = LedgerAccessMode.WRITE
    }

    data class Headless(val lease: HeadlessBookLease) : LedgerAccessPurpose {
        override val mode: LedgerAccessMode = when (lease.capability) {
            HeadlessLeaseCapability.BACKUP_READ,
            HeadlessLeaseCapability.WIDGET_SNAPSHOT_READ,
            -> LedgerAccessMode.READ
            HeadlessLeaseCapability.RECURRENCE_WRITE,
            HeadlessLeaseCapability.BACKUP_WRITE,
            HeadlessLeaseCapability.EXPORT_WRITE,
            HeadlessLeaseCapability.IMPORT_WRITE,
            HeadlessLeaseCapability.TRASH_MAINTENANCE,
            HeadlessLeaseCapability.PROJECTION_MAINTENANCE,
            -> LedgerAccessMode.WRITE
        }
    }

    data class ExclusiveMaintenance(val lease: MaintenanceBookLease) : LedgerAccessPurpose {
        override val mode: LedgerAccessMode = LedgerAccessMode.WRITE
    }
}

interface LedgerDatabaseOperationAccess {
    suspend fun <T> withCurrentDatabase(
        bookId: StableId,
        mode: LedgerAccessMode,
        block: suspend (LedgerDatabase) -> T,
    ): T
}

interface LedgerSecureSettings {
    fun encryptSecureSettings(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    fun decryptSecureSettings(ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

interface LedgerSecureSettingsOperationAccess {
    suspend fun <T> withCurrentSecureSettings(
        bookId: StableId,
        mode: LedgerAccessMode,
        block: suspend (LedgerSecureSettings) -> T,
    ): T
}

interface LedgerSessionOperationAccess :
    LedgerDatabaseOperationAccess,
    LedgerSecureSettingsOperationAccess

interface LedgerDatabaseSessionAccess : LedgerSessionOperationAccess {
    fun readyGeneration(bookId: StableId): Long?

    suspend fun <T> withDatabase(
        bookId: StableId,
        purpose: LedgerAccessPurpose,
        block: suspend (LedgerDatabase) -> T,
    ): T

    suspend fun <T> withSecureSettings(
        bookId: StableId,
        purpose: LedgerAccessPurpose,
        block: suspend (LedgerSecureSettings) -> T,
    ): T

    override suspend fun <T> withCurrentDatabase(
        bookId: StableId,
        mode: LedgerAccessMode,
        block: suspend (LedgerDatabase) -> T,
    ): T = withCurrentSessionDatabase(bookId, mode, block)

    override suspend fun <T> withCurrentSecureSettings(
        bookId: StableId,
        mode: LedgerAccessMode,
        block: suspend (LedgerSecureSettings) -> T,
    ): T = withCurrentSessionSecureSettings(bookId, mode, block)
}

sealed class LedgerSessionAccessException(message: String) : IllegalStateException(message) {
    class SessionUnavailable : LedgerSessionAccessException("ledger session is unavailable")
    class BookMismatch : LedgerSessionAccessException("ledger session book mismatch")
    class GenerationExpired : LedgerSessionAccessException("ledger session generation expired")
    class CapabilityDenied : LedgerSessionAccessException("ledger access capability denied")
}

/**
 * Resolves the current block-scoped capability. Headless callers must install their lease with
 * [withHeadlessLedgerAccess]; ordinary UI callers are bound to the current Ready generation.
 */
private suspend fun <T> LedgerDatabaseSessionAccess.withCurrentSessionDatabase(
    bookId: StableId,
    mode: LedgerAccessMode,
    block: suspend (LedgerDatabase) -> T,
): T {
    val purpose = resolveCurrentPurpose(bookId, mode)
    return withDatabase(bookId, purpose, block)
}

private suspend fun <T> LedgerDatabaseSessionAccess.withCurrentSessionSecureSettings(
    bookId: StableId,
    mode: LedgerAccessMode,
    block: suspend (LedgerSecureSettings) -> T,
): T {
    val purpose = resolveCurrentPurpose(bookId, mode)
    return withSecureSettings(bookId, purpose, block)
}

private suspend fun LedgerDatabaseSessionAccess.resolveCurrentPurpose(
    bookId: StableId,
    mode: LedgerAccessMode,
): LedgerAccessPurpose {
    val context = currentCoroutineContext()
    val nestedWrite = context[LedgerWriteContext]
    if (nestedWrite != null) {
        return if (mode == LedgerAccessMode.WRITE) {
            LedgerAccessPurpose.UiWrite(nestedWrite.generation)
        } else {
            LedgerAccessPurpose.UiRead(nestedWrite.generation)
        }
    }
    val headless = context[LedgerHeadlessAccessContext]?.lease
    val purpose = if (headless != null) {
        LedgerAccessPurpose.Headless(headless)
    } else {
        val generation = readyGeneration(bookId) ?: throw LedgerSessionAccessException.SessionUnavailable()
        if (mode == LedgerAccessMode.WRITE) {
            LedgerAccessPurpose.UiWrite(generation)
        } else {
            LedgerAccessPurpose.UiRead(generation)
        }
    }
    if (mode == LedgerAccessMode.WRITE && purpose.mode != LedgerAccessMode.WRITE) {
        throw LedgerSessionAccessException.CapabilityDenied()
    }
    return purpose
}

class LedgerHeadlessAccessContext internal constructor(
    internal val lease: HeadlessBookLease,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LedgerHeadlessAccessContext>
}

suspend fun <T> withHeadlessLedgerAccess(lease: HeadlessBookLease, block: suspend () -> T): T = withContext(LedgerHeadlessAccessContext(lease)) { block() }

class HeadlessLedgerDatabaseAccess(
    private val sessionAccess: LedgerDatabaseSessionAccess,
    private val lease: HeadlessBookLease,
) : LedgerDatabaseOperationAccess {
    override suspend fun <T> withCurrentDatabase(
        bookId: StableId,
        mode: LedgerAccessMode,
        block: suspend (LedgerDatabase) -> T,
    ): T {
        val purpose = LedgerAccessPurpose.Headless(lease)
        if (mode == LedgerAccessMode.WRITE && purpose.mode != LedgerAccessMode.WRITE) {
            throw LedgerSessionAccessException.CapabilityDenied()
        }
        return sessionAccess.withDatabase(bookId, purpose, block)
    }
}

suspend fun <T> ActiveBookSessionRuntime.withHeadlessDatabaseAccess(
    bookId: StableId,
    operationId: StableId,
    capability: HeadlessLeaseCapability,
    block: suspend (LedgerDatabaseOperationAccess) -> T,
): T {
    val manager = activate(bookId)
    var lease: HeadlessBookLease? = null
    return try {
        if (manager.state.value == BookSessionState.Uninitialized) manager.initialize()
        lease = manager.acquireHeadlessLease(operationId, capability)
        val access = HeadlessLedgerDatabaseAccess(this, lease)
        withHeadlessLedgerAccess(lease) { block(access) }
    } finally {
        try {
            lease?.release()
        } finally {
            if (manager.state.value !is BookSessionState.Ready) manager.close()
        }
    }
}

/** A process-wide non-reentrant ordering boundary when held by the active session runtime. */
class LedgerWriteCoordinator {
    private val mutex = Mutex()

    internal suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}

internal class LedgerWriteContext(
    internal val owner: BookSessionManager,
    internal val bookId: StableId,
    internal val generation: Long,
    internal val resource: BookDatabaseResource,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LedgerWriteContext>
}

internal class LedgerReadContext(
    internal val owner: BookSessionManager,
    internal val bookId: StableId,
    internal val generation: Long,
    internal val resource: BookDatabaseResource,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LedgerReadContext>
}

fun interface ActiveBookSessionManagerFactory {
    fun create(bookId: StableId): BookSessionManager
}

/** Process-scoped owner/dispatcher used by UI and headless callers in the same app process. */
class ActiveBookSessionRuntime(
    private val managerFactory: ActiveBookSessionManagerFactory,
) : LedgerDatabaseSessionAccess {
    private val switchMutex = Mutex()

    @Volatile private var activeManager: BookSessionManager? = null

    suspend fun activate(bookId: StableId): BookSessionManager = switchMutex.withLock {
        val current = activeManager
        if (
            current != null &&
            current.bookId == bookId &&
            current.state.value !is BookSessionState.RecoveryRequired
        ) {
            return@withLock current
        }
        current?.close()
        managerFactory.create(bookId).also { activeManager = it }
    }

    fun managerOrNull(bookId: StableId): BookSessionManager? = activeManager?.takeIf { it.bookId == bookId }

    suspend fun clear(manager: BookSessionManager) = switchMutex.withLock {
        if (activeManager === manager) {
            manager.close()
            activeManager = null
        }
    }

    override fun readyGeneration(bookId: StableId): Long? = managerOrNull(bookId)?.readyGeneration(bookId)

    override suspend fun <T> withDatabase(
        bookId: StableId,
        purpose: LedgerAccessPurpose,
        block: suspend (LedgerDatabase) -> T,
    ): T = managerOrNull(bookId)?.withDatabase(bookId, purpose, block)
        ?: throw LedgerSessionAccessException.SessionUnavailable()

    override suspend fun <T> withSecureSettings(
        bookId: StableId,
        purpose: LedgerAccessPurpose,
        block: suspend (LedgerSecureSettings) -> T,
    ): T = managerOrNull(bookId)?.withSecureSettings(bookId, purpose, block)
        ?: throw LedgerSessionAccessException.SessionUnavailable()
}
