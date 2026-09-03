package app.ledger.core.security

import android.content.Context
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.database.LedgerMigrations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

sealed interface BookSessionState {
    data object Uninitialized : BookSessionState

    data object Locked : BookSessionState

    data object Opening : BookSessionState

    data class Maintenance(val reason: MaintenanceReason) : BookSessionState

    data class RecoveryRequired(val diagnosticCode: RecoveryDiagnosticCode) : BookSessionState

    data class Ready(val bookId: StableId, val generation: Long) : BookSessionState
}

enum class MaintenanceReason {
    DATABASE_MIGRATION,
    UNFINISHED_OPERATION,
    PROJECTION_REBUILD,
    CONTROLLED_MAINTENANCE,
}

enum class RecoveryDiagnosticCode {
    KEY_UNAVAILABLE,
    DATABASE_UNAVAILABLE,
    SCHEMA_INVALID,
    PROJECTION_FAILURE,
}

enum class HeadlessLeaseCapability {
    RECURRENCE_WRITE,
    BACKUP_READ,
    BACKUP_WRITE,
    EXPORT_WRITE,
    IMPORT_WRITE,
    WIDGET_SNAPSHOT_READ,
    TRASH_MAINTENANCE,
    PROJECTION_MAINTENANCE,
}

sealed interface StartupInspection {
    data object Ready : StartupInspection

    data class Maintenance(val reason: MaintenanceReason) : StartupInspection

    data class RecoveryRequired(val code: RecoveryDiagnosticCode) : StartupInspection
}

fun interface LedgerStartupInspector {
    fun inspect(database: LedgerDatabase): StartupInspection
}

interface BookDatabaseResource : Closeable {
    fun inspectStartup(): StartupInspection

    suspend fun <T> withDatabase(block: suspend (LedgerDatabase) -> T): T
}

fun interface BookDatabaseResourceFactory {
    fun open(databaseDek: SecretBytes): BookDatabaseResource
}

class SqlCipherBookDatabaseResourceFactory(
    context: Context,
    private val inspectors: List<LedgerStartupInspector> = listOf(DefaultLedgerStartupInspector),
) : BookDatabaseResourceFactory {
    private val applicationContext = context.applicationContext

    @Suppress("TooGenericExceptionCaught")
    override fun open(databaseDek: SecretBytes): BookDatabaseResource {
        val database = databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
        return try {
            database.readLedger { connection -> connection.query("SELECT 1").use { check(it.moveToFirst()) } }
            SqlCipherBookDatabaseResource(database, inspectors)
        } catch (error: Exception) {
            database.close()
            throw error
        }
    }
}

private class SqlCipherBookDatabaseResource(
    private val database: LedgerDatabase,
    private val inspectors: List<LedgerStartupInspector>,
) : BookDatabaseResource {
    override fun inspectStartup(): StartupInspection {
        var maintenance: StartupInspection.Maintenance? = null
        for (inspector in inspectors) {
            when (val result = inspector.inspect(database)) {
                is StartupInspection.RecoveryRequired -> return result
                is StartupInspection.Maintenance -> maintenance = result
                StartupInspection.Ready -> Unit
            }
        }
        return maintenance ?: StartupInspection.Ready
    }

    override suspend fun <T> withDatabase(block: suspend (LedgerDatabase) -> T): T = block(database)

    override fun close() = database.close()
}

object DefaultLedgerStartupInspector : LedgerStartupInspector {
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override fun inspect(database: LedgerDatabase): StartupInspection = try {
        database.readLedger { connection ->
            val registryValid = connection.query(
                "SELECT COUNT(*) FROM _room_schema_registry WHERE id = 1 " +
                    "AND logicalSchemaVersion = ${LedgerMigrations.CURRENT_VERSION}",
            ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == 1L }
            val cipherAvailable = connection.query("PRAGMA cipher_version").use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).startsWith("4.17.0")
            }
            if (registryValid && cipherAvailable) {
                StartupInspection.Ready
            } else {
                StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID)
            }
        }
    } catch (_: Exception) {
        StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.DATABASE_UNAVAILABLE)
    }
}

@Suppress("TooManyFunctions")
class BookSessionManager(
    val bookId: StableId,
    private val keyHierarchy: DeviceLedgerKeyProvider,
    private val databaseFactory: BookDatabaseResourceFactory,
    private val vaultExposureRegistry: VaultExposureRegistry,
    private val writeCoordinator: LedgerWriteCoordinator = LedgerWriteCoordinator(),
) : LedgerDatabaseSessionAccess {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<BookSessionState>(BookSessionState.Uninitialized)
    private var resource: BookDatabaseResource? = null
    private var secureSettings: SessionLedgerSecureSettings? = null
    private var uiLeaseActive = false
    private var headlessLeaseCount = 0
    private var maintenanceLeaseActive = false
    private var offlinePrimaryMaintenancePermitActive = false
    private var activeOperationCount = 0
    private var operationDrain = CompletableDeferred(Unit)
    private var generation = 0L

    val state: StateFlow<BookSessionState> = mutableState.asStateFlow()

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    suspend fun initialize() = mutex.withLock {
        // Application-scoped headless scheduling and the foreground UI can discover the same
        // process-owned session concurrently during startup. Initialization is therefore a
        // once-per-manager operation rather than a caller-owned state transition.
        if (mutableState.value != BookSessionState.Uninitialized) return@withLock
        try {
            keyHierarchy.initialize(bookId)
            mutableState.value = BookSessionState.Locked
        } catch (_: SecurityException.KeyUnavailable) {
            mutableState.value = BookSessionState.RecoveryRequired(RecoveryDiagnosticCode.KEY_UNAVAILABLE)
        } catch (_: Exception) {
            mutableState.value = BookSessionState.RecoveryRequired(RecoveryDiagnosticCode.DATABASE_UNAVAILABLE)
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    suspend fun unlockUi() {
        val trace = LedgerSessionPerformance.begin(LedgerInteractionOperation.UNLOCK)
        try {
            mutex.withLock {
                // Recreating an Activity must attach to the process-owned Ready session instead of
                // reopening it. Concurrent foreground owners can also arrive while the first owner
                // is still Opening, so both states are idempotent attachment outcomes.
                if (
                    mutableState.value == BookSessionState.Opening ||
                    mutableState.value is BookSessionState.Ready
                ) {
                    return@withLock
                }
                check(mutableState.value == BookSessionState.Locked) {
                    "UI can be unlocked only from Locked, Opening, or Ready"
                }
                mutableState.value = BookSessionState.Opening
                try {
                    val opened = ensureResource()
                    when (val inspection = opened.inspectStartup()) {
                        StartupInspection.Ready -> {
                            uiLeaseActive = true
                            generation = Math.addExact(generation, 1L)
                            mutableState.value = BookSessionState.Ready(bookId, generation)
                        }
                        is StartupInspection.Maintenance -> {
                            uiLeaseActive = false
                            mutableState.value = BookSessionState.Maintenance(inspection.reason)
                        }
                        is StartupInspection.RecoveryRequired -> {
                            uiLeaseActive = false
                            closeIfUnused()
                            mutableState.value = BookSessionState.RecoveryRequired(inspection.code)
                        }
                    }
                } catch (error: SecurityException.KeyUnavailable) {
                    uiLeaseActive = false
                    closeIfUnused()
                    mutableState.value = BookSessionState.RecoveryRequired(RecoveryDiagnosticCode.KEY_UNAVAILABLE)
                } catch (_: Exception) {
                    uiLeaseActive = false
                    closeIfUnused()
                    mutableState.value = BookSessionState.RecoveryRequired(RecoveryDiagnosticCode.DATABASE_UNAVAILABLE)
                }
            }
        } finally {
            trace.close()
        }
    }

    suspend fun lockUi() {
        val drain = mutex.withLock {
            uiLeaseActive = false
            vaultExposureRegistry.onApplicationLocked()
            if (mutableState.value is BookSessionState.Ready || mutableState.value == BookSessionState.Opening) {
                mutableState.value = BookSessionState.Locked
            }
            closeIfUnused()
            operationDrain.takeIf { activeOperationCount > 0 }
        }
        drain?.await()
        mutex.withLock { closeIfUnused() }
    }

    suspend fun acquireHeadlessLease(
        operationId: StableId,
        capability: HeadlessLeaseCapability,
    ): HeadlessBookLease = mutex.withLock {
        check(mutableState.value != BookSessionState.Uninitialized && mutableState.value != BookSessionState.Opening) {
            "book session is not initialized"
        }
        when (mutableState.value) {
            is BookSessionState.RecoveryRequired -> check(false) { "book requires recovery" }
            is BookSessionState.Maintenance -> require(capability in MAINTENANCE_CAPABILITIES) {
                "maintenance state rejects this headless capability"
            }
            else -> Unit
        }
        val requiresInspection = resource == null
        val opened = try {
            ensureResource()
        } catch (error: SecurityException) {
            failHeadlessOpen(RecoveryDiagnosticCode.KEY_UNAVAILABLE)
            throw error
        } catch (_: Exception) {
            failHeadlessOpen(RecoveryDiagnosticCode.DATABASE_UNAVAILABLE)
            throw SecurityException.DatabaseUnavailable()
        }
        if (requiresInspection) {
            val inspection = try {
                opened.inspectStartup()
            } catch (_: Exception) {
                failHeadlessOpen(RecoveryDiagnosticCode.DATABASE_UNAVAILABLE)
                throw SecurityException.DatabaseUnavailable()
            }
            applyHeadlessInspection(inspection, capability)
        }
        headlessLeaseCount = Math.addExact(headlessLeaseCount, 1)
        HeadlessBookLease(bookId, operationId, capability, this) { releaseHeadlessLease() }
    }

    suspend fun enterMaintenance(reason: MaintenanceReason) {
        val drain = mutex.withLock {
            check(mutableState.value is BookSessionState.Ready || mutableState.value == BookSessionState.Locked)
            uiLeaseActive = false
            vaultExposureRegistry.onApplicationLocked()
            mutableState.value = BookSessionState.Maintenance(reason)
            operationDrain.takeIf { activeOperationCount > 0 }
        }
        drain?.await()
    }

    suspend fun acquireMaintenanceLease(operationId: StableId): MaintenanceBookLease = mutex.withLock {
        check(mutableState.value is BookSessionState.Maintenance)
        check(!maintenanceLeaseActive && !offlinePrimaryMaintenancePermitActive && headlessLeaseCount == 0 && activeOperationCount == 0)
        ensureResource()
        maintenanceLeaseActive = true
        MaintenanceBookLease(bookId, operationId, this) { releaseMaintenanceLease() }
    }

    /** Authorizes restore/recovery infrastructure only after the process-owned resource is closed. */
    suspend fun acquireOfflinePrimaryMaintenancePermit(operationId: StableId): OfflinePrimaryMaintenancePermit = mutex.withLock {
        check(mutableState.value is BookSessionState.Maintenance || mutableState.value is BookSessionState.RecoveryRequired)
        check(resource == null && secureSettings == null) {
            "live primary resource must be closed before offline maintenance"
        }
        check(!maintenanceLeaseActive && !offlinePrimaryMaintenancePermitActive && headlessLeaseCount == 0 && activeOperationCount == 0)
        offlinePrimaryMaintenancePermitActive = true
        OfflinePrimaryMaintenancePermit(bookId, operationId, this) { releaseOfflinePrimaryMaintenancePermit() }
    }

    suspend fun finishMaintenance(recoveryRequired: RecoveryDiagnosticCode? = null) = mutex.withLock {
        check(mutableState.value is BookSessionState.Maintenance)
        check(!maintenanceLeaseActive) { "cannot finish maintenance while its lease is active" }
        check(!offlinePrimaryMaintenancePermitActive) { "cannot finish maintenance while offline primary access is active" }
        mutableState.value = if (recoveryRequired == null) {
            BookSessionState.Locked
        } else {
            BookSessionState.RecoveryRequired(recoveryRequired)
        }
        closeIfUnused()
    }

    suspend fun close() {
        val drain = mutex.withLock {
            uiLeaseActive = false
            vaultExposureRegistry.clearAll()
            check(headlessLeaseCount == 0) { "cannot close while headless leases are active" }
            check(!maintenanceLeaseActive) { "cannot close while a maintenance lease is active" }
            check(!offlinePrimaryMaintenancePermitActive) { "cannot close while offline primary access is active" }
            if (mutableState.value is BookSessionState.Ready || mutableState.value == BookSessionState.Opening) {
                mutableState.value = BookSessionState.Locked
            }
            operationDrain.takeIf { activeOperationCount > 0 }
        }
        drain?.await()
        mutex.withLock {
            closeSessionResource()
        }
    }

    suspend fun activeHeadlessLeaseCount(): Int = mutex.withLock { headlessLeaseCount }

    suspend fun activeDatabaseOperationCount(): Int = mutex.withLock { activeOperationCount }

    override fun readyGeneration(bookId: StableId): Long? = (mutableState.value as? BookSessionState.Ready)?.takeIf { it.bookId == bookId }?.generation

    @Suppress("ReturnCount")
    override suspend fun <T> withDatabase(
        bookId: StableId,
        purpose: LedgerAccessPurpose,
        block: suspend (LedgerDatabase) -> T,
    ): T {
        val nested = currentCoroutineContext()[LedgerWriteContext]
        if (nested != null) {
            if (nested.owner !== this || nested.bookId != bookId) throw LedgerSessionAccessException.BookMismatch()
            return nested.resource.withDatabase(block)
        }
        val nestedRead = currentCoroutineContext()[LedgerReadContext]
        if (nestedRead != null) {
            if (nestedRead.owner !== this || nestedRead.bookId != bookId) throw LedgerSessionAccessException.BookMismatch()
            if (purpose.mode == LedgerAccessMode.WRITE) throw LedgerSessionAccessException.CapabilityDenied()
            return nestedRead.resource.withDatabase(block)
        }
        return if (purpose.mode == LedgerAccessMode.WRITE) {
            writeCoordinator.execute { executeDatabaseOperation(bookId, purpose, installWriteContext = true, installReadContext = false, block) }
        } else {
            executeDatabaseOperation(bookId, purpose, installWriteContext = false, installReadContext = true, block)
        }
    }

    @Suppress("ReturnCount")
    override suspend fun <T> withSecureSettings(
        bookId: StableId,
        purpose: LedgerAccessPurpose,
        block: suspend (LedgerSecureSettings) -> T,
    ): T {
        val nested = currentCoroutineContext()[LedgerWriteContext]
        if (nested != null) {
            if (nested.owner !== this || nested.bookId != bookId) {
                throw LedgerSessionAccessException.BookMismatch()
            }
            return block(requireSecureSettings())
        }
        val nestedRead = currentCoroutineContext()[LedgerReadContext]
        if (nestedRead != null) {
            if (nestedRead.owner !== this || nestedRead.bookId != bookId) {
                throw LedgerSessionAccessException.BookMismatch()
            }
            if (purpose.mode == LedgerAccessMode.WRITE) {
                throw LedgerSessionAccessException.CapabilityDenied()
            }
            return block(requireSecureSettings())
        }
        return if (purpose.mode == LedgerAccessMode.WRITE) {
            writeCoordinator.execute {
                executeSecureSettingsOperation(
                    bookId,
                    purpose,
                    installWriteContext = true,
                    installReadContext = false,
                    block,
                )
            }
        } else {
            executeSecureSettingsOperation(
                bookId,
                purpose,
                installWriteContext = false,
                installReadContext = true,
                block,
            )
        }
    }

    internal suspend fun <T> withOperationForTest(
        purpose: LedgerAccessPurpose,
        block: suspend () -> T,
    ): T = if (purpose.mode == LedgerAccessMode.WRITE) {
        writeCoordinator.execute { executeAdmittedOperation(bookId, purpose) { block() } }
    } else {
        executeAdmittedOperation(bookId, purpose) { block() }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun ensureResource(): BookDatabaseResource {
        check(!offlinePrimaryMaintenancePermitActive) { "offline primary maintenance is active" }
        resource?.let {
            check(secureSettings != null) { "session secure-settings resource is missing" }
            return it
        }
        check(secureSettings == null) { "session secure-settings resource has no database owner" }
        val keys = keyHierarchy.open(bookId)
        try {
            val openedSecureSettings = keys.copySecureSettingsForSession()
            try {
                val openedDatabase = databaseFactory.open(keys.databaseDek)
                resource = openedDatabase
                secureSettings = openedSecureSettings
                return openedDatabase
            } catch (error: Throwable) {
                openedSecureSettings.close()
                throw error
            }
        } finally {
            keys.close()
        }
    }

    private suspend fun releaseHeadlessLease() = mutex.withLock {
        check(headlessLeaseCount > 0)
        headlessLeaseCount -= 1
        closeIfUnused()
    }

    private suspend fun releaseMaintenanceLease() = mutex.withLock {
        check(maintenanceLeaseActive)
        maintenanceLeaseActive = false
        closeIfUnused()
    }

    private suspend fun releaseOfflinePrimaryMaintenancePermit() = mutex.withLock {
        check(offlinePrimaryMaintenancePermitActive)
        offlinePrimaryMaintenancePermitActive = false
    }

    private fun closeIfUnused() {
        val hasOwnerLease = uiLeaseActive || headlessLeaseCount > 0 || maintenanceLeaseActive
        if (!hasOwnerLease && activeOperationCount == 0) {
            closeSessionResource()
        }
    }

    private fun closeSessionResource() {
        val openedDatabase = resource
        val openedSecureSettings = secureSettings
        resource = null
        secureSettings = null
        try {
            openedDatabase?.close()
        } finally {
            openedSecureSettings?.close()
        }
    }

    private fun requireSecureSettings(): SessionLedgerSecureSettings = secureSettings
        ?: throw LedgerSessionAccessException.SessionUnavailable()

    private suspend fun <T> executeDatabaseOperation(
        requestedBookId: StableId,
        purpose: LedgerAccessPurpose,
        installWriteContext: Boolean,
        installReadContext: Boolean,
        block: suspend (LedgerDatabase) -> T,
    ): T = executeAdmittedOperation(requestedBookId, purpose) { admitted ->
        when {
            installWriteContext -> withContext(LedgerWriteContext(this, bookId, generation, admitted)) { admitted.withDatabase(block) }
            installReadContext -> withContext(LedgerReadContext(this, bookId, generation, admitted)) { admitted.withDatabase(block) }
            else -> admitted.withDatabase(block)
        }
    }

    private suspend fun <T> executeSecureSettingsOperation(
        requestedBookId: StableId,
        purpose: LedgerAccessPurpose,
        installWriteContext: Boolean,
        installReadContext: Boolean,
        block: suspend (LedgerSecureSettings) -> T,
    ): T = executeAdmittedOperation(requestedBookId, purpose) { admitted ->
        val settings = requireSecureSettings()
        when {
            installWriteContext -> withContext(LedgerWriteContext(this, bookId, generation, admitted)) {
                block(settings)
            }
            installReadContext -> withContext(LedgerReadContext(this, bookId, generation, admitted)) {
                block(settings)
            }
            else -> block(settings)
        }
    }

    private suspend fun <T> executeAdmittedOperation(
        requestedBookId: StableId,
        purpose: LedgerAccessPurpose,
        block: suspend (BookDatabaseResource) -> T,
    ): T {
        val admitted = mutex.withLock {
            validateAccess(requestedBookId, purpose)
            val current = resource ?: throw LedgerSessionAccessException.SessionUnavailable()
            if (activeOperationCount == 0) operationDrain = CompletableDeferred()
            activeOperationCount = Math.addExact(activeOperationCount, 1)
            current
        }
        LedgerSessionPerformance.recordSessionAcquisition()
        return try {
            block(admitted)
        } finally {
            mutex.withLock {
                check(activeOperationCount > 0)
                activeOperationCount -= 1
                if (activeOperationCount == 0) operationDrain.complete(Unit)
                closeIfUnused()
            }
        }
    }

    @Suppress("ThrowsCount")
    private fun validateAccess(requestedBookId: StableId, purpose: LedgerAccessPurpose) {
        if (requestedBookId != bookId) throw LedgerSessionAccessException.BookMismatch()
        when (purpose) {
            is LedgerAccessPurpose.UiRead -> validateUiGeneration(purpose.generation)
            is LedgerAccessPurpose.UiWrite -> validateUiGeneration(purpose.generation)
            is LedgerAccessPurpose.Headless -> if (!purpose.lease.isValidFor(this, bookId)) {
                throw LedgerSessionAccessException.CapabilityDenied()
            }
            is LedgerAccessPurpose.ExclusiveMaintenance -> if (!purpose.lease.isValidFor(this, bookId)) {
                throw LedgerSessionAccessException.CapabilityDenied()
            }
        }
    }

    @Suppress("ThrowsCount")
    private fun validateUiGeneration(requestedGeneration: Long) {
        val ready = mutableState.value as? BookSessionState.Ready
            ?: throw LedgerSessionAccessException.SessionUnavailable()
        if (ready.bookId != bookId) throw LedgerSessionAccessException.BookMismatch()
        if (ready.generation != requestedGeneration) throw LedgerSessionAccessException.GenerationExpired()
    }

    private fun failHeadlessOpen(code: RecoveryDiagnosticCode) {
        mutableState.value = BookSessionState.RecoveryRequired(code)
        closeIfUnused()
    }

    private fun applyHeadlessInspection(
        inspection: StartupInspection,
        capability: HeadlessLeaseCapability,
    ) {
        when (inspection) {
            StartupInspection.Ready -> Unit
            is StartupInspection.Maintenance -> {
                mutableState.value = BookSessionState.Maintenance(inspection.reason)
                if (capability !in MAINTENANCE_CAPABILITIES) {
                    closeIfUnused()
                    throw IllegalArgumentException("maintenance state rejects this headless capability")
                }
            }
            is StartupInspection.RecoveryRequired -> {
                mutableState.value = BookSessionState.RecoveryRequired(inspection.code)
                closeIfUnused()
                check(false) { "book requires recovery" }
            }
        }
    }

    private companion object {
        val MAINTENANCE_CAPABILITIES = setOf(
            HeadlessLeaseCapability.TRASH_MAINTENANCE,
            HeadlessLeaseCapability.PROJECTION_MAINTENANCE,
            HeadlessLeaseCapability.BACKUP_READ,
            HeadlessLeaseCapability.BACKUP_WRITE,
        )
    }
}

class HeadlessBookLease internal constructor(
    val bookId: StableId,
    val operationId: StableId,
    val capability: HeadlessLeaseCapability,
    private val owner: BookSessionManager,
    private val releaseAction: suspend () -> Unit,
) {
    private val released = AtomicBoolean(false)

    suspend fun release() {
        if (released.compareAndSet(false, true)) releaseAction()
    }

    internal fun isValidFor(manager: BookSessionManager, requestedBookId: StableId): Boolean = owner === manager && bookId == requestedBookId && !released.get()

    override fun toString(): String = "HeadlessBookLease(operationId=$operationId,capability=$capability)"
}

class MaintenanceBookLease internal constructor(
    val bookId: StableId,
    val operationId: StableId,
    private val owner: BookSessionManager,
    private val releaseAction: suspend () -> Unit,
) {
    private val released = AtomicBoolean(false)

    suspend fun release() {
        if (released.compareAndSet(false, true)) releaseAction()
    }

    internal fun isValidFor(manager: BookSessionManager, requestedBookId: StableId): Boolean = owner === manager && bookId == requestedBookId && !released.get()

    override fun toString(): String = "MaintenanceBookLease(operationId=$operationId)"
}

class OfflinePrimaryMaintenancePermit internal constructor(
    val bookId: StableId,
    val operationId: StableId,
    private val owner: BookSessionManager,
    private val releaseAction: suspend () -> Unit,
) {
    private val released = AtomicBoolean(false)

    suspend fun release() {
        if (released.compareAndSet(false, true)) releaseAction()
    }

    internal fun isValidFor(requestedBookId: StableId): Boolean = owner.bookId == requestedBookId && bookId == requestedBookId && !released.get()

    override fun toString(): String = "OfflinePrimaryMaintenancePermit(operationId=$operationId)"
}
