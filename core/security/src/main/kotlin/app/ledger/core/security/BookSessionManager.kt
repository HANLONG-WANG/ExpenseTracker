package app.ledger.core.security

import android.content.Context
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    override fun close() = database.close()
}

object DefaultLedgerStartupInspector : LedgerStartupInspector {
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override fun inspect(database: LedgerDatabase): StartupInspection = try {
        database.readLedger { connection ->
            val registryValid = connection.query(
                "SELECT COUNT(*) FROM _room_schema_registry WHERE id = 1 AND logicalSchemaVersion = 1",
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
    private val bookId: StableId,
    private val keyHierarchy: DeviceLedgerKeyProvider,
    private val databaseFactory: BookDatabaseResourceFactory,
    private val vaultExposureRegistry: VaultExposureRegistry,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<BookSessionState>(BookSessionState.Uninitialized)
    private var resource: BookDatabaseResource? = null
    private var uiLeaseActive = false
    private var headlessLeaseCount = 0
    private var generation = 0L

    val state: StateFlow<BookSessionState> = mutableState.asStateFlow()

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    suspend fun initialize() = mutex.withLock {
        check(mutableState.value == BookSessionState.Uninitialized)
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
    suspend fun unlockUi() = mutex.withLock {
        check(mutableState.value == BookSessionState.Locked) { "UI can be unlocked only from Locked" }
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

    suspend fun lockUi() = mutex.withLock {
        uiLeaseActive = false
        vaultExposureRegistry.onApplicationLocked()
        if (mutableState.value is BookSessionState.Ready || mutableState.value == BookSessionState.Opening) {
            mutableState.value = BookSessionState.Locked
        }
        closeIfUnused()
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
        HeadlessBookLease(operationId, capability) { releaseHeadlessLease() }
    }

    suspend fun enterMaintenance(reason: MaintenanceReason) = mutex.withLock {
        check(mutableState.value is BookSessionState.Ready || mutableState.value == BookSessionState.Locked)
        uiLeaseActive = false
        vaultExposureRegistry.onApplicationLocked()
        mutableState.value = BookSessionState.Maintenance(reason)
    }

    suspend fun finishMaintenance(recoveryRequired: RecoveryDiagnosticCode? = null) = mutex.withLock {
        check(mutableState.value is BookSessionState.Maintenance)
        mutableState.value = if (recoveryRequired == null) {
            BookSessionState.Locked
        } else {
            BookSessionState.RecoveryRequired(recoveryRequired)
        }
        closeIfUnused()
    }

    suspend fun close() = mutex.withLock {
        uiLeaseActive = false
        vaultExposureRegistry.clearAll()
        check(headlessLeaseCount == 0) { "cannot close while headless leases are active" }
        resource?.close()
        resource = null
        if (mutableState.value is BookSessionState.Ready || mutableState.value == BookSessionState.Opening) {
            mutableState.value = BookSessionState.Locked
        }
    }

    suspend fun activeHeadlessLeaseCount(): Int = mutex.withLock { headlessLeaseCount }

    private fun ensureResource(): BookDatabaseResource {
        resource?.let { return it }
        val opened = keyHierarchy.open(bookId).use { keys -> databaseFactory.open(keys.databaseDek) }
        resource = opened
        return opened
    }

    private suspend fun releaseHeadlessLease() = mutex.withLock {
        check(headlessLeaseCount > 0)
        headlessLeaseCount -= 1
        closeIfUnused()
    }

    private fun closeIfUnused() {
        if (!uiLeaseActive && headlessLeaseCount == 0) {
            resource?.close()
            resource = null
        }
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
        )
    }
}

class HeadlessBookLease internal constructor(
    val operationId: StableId,
    val capability: HeadlessLeaseCapability,
    private val releaseAction: suspend () -> Unit,
) {
    private val released = AtomicBoolean(false)

    suspend fun release() {
        if (released.compareAndSet(false, true)) releaseAction()
    }

    override fun toString(): String = "HeadlessBookLease(operationId=$operationId,capability=$capability)"
}
