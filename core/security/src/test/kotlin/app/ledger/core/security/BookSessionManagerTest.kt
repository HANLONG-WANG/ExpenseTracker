package app.ledger.core.security

import app.ledger.core.common.StableId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID

class BookSessionManagerTest {
    @Test
    fun `UI locking keeps an authorized headless database lease alive without exposing Ready`() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()
        fixture.manager.state.value shouldBe BookSessionState.Locked
        fixture.manager.unlockUi()
        fixture.manager.state.value shouldBe BookSessionState.Ready(fixture.bookId, 1)

        val lease = fixture.manager.acquireHeadlessLease(stableId(20), HeadlessLeaseCapability.BACKUP_READ)
        fixture.factory.openCount shouldBe 1
        fixture.manager.lockUi()
        fixture.manager.state.value shouldBe BookSessionState.Locked
        fixture.factory.closeCount shouldBe 0
        fixture.manager.activeHeadlessLeaseCount() shouldBe 1

        lease.release()
        fixture.factory.closeCount shouldBe 1
        fixture.manager.activeHeadlessLeaseCount() shouldBe 0
    }

    @Test
    fun `maintenance restricts headless capabilities and recovery is fail closed`() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()
        fixture.manager.unlockUi()
        fixture.manager.enterMaintenance(MaintenanceReason.PROJECTION_REBUILD)
        fixture.manager.lockUi()
        fixture.manager.state.value shouldBe BookSessionState.Maintenance(MaintenanceReason.PROJECTION_REBUILD)
        shouldThrow<IllegalArgumentException> {
            fixture.manager.acquireHeadlessLease(stableId(21), HeadlessLeaseCapability.RECURRENCE_WRITE)
        }
        val maintenanceLease = fixture.manager.acquireHeadlessLease(
            stableId(22),
            HeadlessLeaseCapability.PROJECTION_MAINTENANCE,
        )
        maintenanceLease.release()
        fixture.manager.finishMaintenance(RecoveryDiagnosticCode.PROJECTION_FAILURE)
        fixture.manager.state.value shouldBe BookSessionState.RecoveryRequired(RecoveryDiagnosticCode.PROJECTION_FAILURE)
        shouldThrow<IllegalStateException> {
            fixture.manager.acquireHeadlessLease(stableId(23), HeadlessLeaseCapability.BACKUP_READ)
        }
    }

    @Test
    fun `invalidated device key transitions to recovery required with sanitized reason`() = runTest {
        val fixture = fixture(keyUnavailableOnOpen = true)
        fixture.manager.initialize()
        fixture.manager.unlockUi()
        fixture.manager.state.value shouldBe BookSessionState.RecoveryRequired(RecoveryDiagnosticCode.KEY_UNAVAILABLE)
        fixture.factory.openCount shouldBe 0
    }

    @Test
    fun `startup inspection routes migration and schema failures without partial Ready state`() = runTest {
        val maintenance = fixture(inspection = StartupInspection.Maintenance(MaintenanceReason.DATABASE_MIGRATION))
        maintenance.manager.initialize()
        maintenance.manager.unlockUi()
        maintenance.manager.state.value shouldBe BookSessionState.Maintenance(MaintenanceReason.DATABASE_MIGRATION)

        val recovery = fixture(inspection = StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID))
        recovery.manager.initialize()
        recovery.manager.unlockUi()
        recovery.manager.state.value shouldBe BookSessionState.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID)
        recovery.factory.closeCount shouldBe 1
    }

    @Test
    fun `headless open performs startup inspection and fails closed`() = runTest {
        val fixture = fixture(inspection = StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID))
        fixture.manager.initialize()
        shouldThrow<IllegalStateException> {
            fixture.manager.acquireHeadlessLease(stableId(24), HeadlessLeaseCapability.BACKUP_READ)
        }
        fixture.manager.state.value shouldBe BookSessionState.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID)
        fixture.factory.closeCount shouldBe 1
    }

    @Test
    fun `headless startup exception closes the resource and enters sanitized recovery`() = runTest {
        val fixture = fixture(inspectionFailure = true)
        fixture.manager.initialize()
        shouldThrow<SecurityException.DatabaseUnavailable> {
            fixture.manager.acquireHeadlessLease(stableId(25), HeadlessLeaseCapability.BACKUP_READ)
        }
        fixture.manager.state.value shouldBe BookSessionState.RecoveryRequired(RecoveryDiagnosticCode.DATABASE_UNAVAILABLE)
        fixture.factory.closeCount shouldBe 1
        fixture.manager.activeHeadlessLeaseCount() shouldBe 0
    }

    private fun fixture(
        keyUnavailableOnOpen: Boolean = false,
        inspection: StartupInspection = StartupInspection.Ready,
        inspectionFailure: Boolean = false,
    ): Fixture {
        val bookId = stableId(10)
        val provider = FakeKeyProvider(keyUnavailableOnOpen)
        val factory = FakeResourceFactory(inspection, inspectionFailure)
        val registry = VaultExposureRegistry { 0L }
        return Fixture(bookId, factory, BookSessionManager(bookId, provider, factory, registry))
    }

    private data class Fixture(
        val bookId: StableId,
        val factory: FakeResourceFactory,
        val manager: BookSessionManager,
    )

    private class FakeKeyProvider(private val unavailable: Boolean) : DeviceLedgerKeyProvider {
        override fun initialize(bookId: StableId) = Unit

        override fun open(bookId: StableId): DeviceLedgerKeys {
            if (unavailable) throw SecurityException.KeyUnavailable()
            return DeviceLedgerKeys(
                databaseDek = SecretBytes.copyOf(ByteArray(32) { 1 }),
                attachmentRootKeyset = LedgerTink.generateAeadKeyset(),
                secureSettingsKeyset = LedgerTink.generateAeadKeyset(),
            )
        }

        override fun destroyLocal(bookId: StableId) = Unit
    }

    private class FakeResourceFactory(
        private val inspection: StartupInspection,
        private val inspectionFailure: Boolean,
    ) : BookDatabaseResourceFactory {
        var openCount = 0
        var closeCount = 0

        override fun open(databaseDek: SecretBytes): BookDatabaseResource {
            databaseDek.useBytes { it.size shouldBe 32 }
            openCount += 1
            return object : BookDatabaseResource {
                override fun inspectStartup(): StartupInspection {
                    if (inspectionFailure) error("injected inspector failure")
                    return inspection
                }

                override fun close() {
                    closeCount += 1
                }
            }
        }
    }

    private fun stableId(index: Long): StableId = StableId.fromUuid(UUID(0L, index))
}
