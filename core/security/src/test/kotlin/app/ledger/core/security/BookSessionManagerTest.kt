package app.ledger.core.security

import app.ledger.core.common.StableId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookSessionManagerTest {
    @Test
    fun `concurrent process callers initialize one shared session exactly once`() = runTest {
        val fixture = fixture()

        val foreground = async { fixture.manager.initialize() }
        val headless = async { fixture.manager.initialize() }
        foreground.await()
        headless.await()

        fixture.provider.initializeCount shouldBe 1
        fixture.manager.state.value shouldBe BookSessionState.Locked
    }

    @Test
    fun `concurrent and recreated UI owners attach to one Ready generation`() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()

        val firstOwner = async { fixture.manager.unlockUi() }
        val recreatedOwner = async { fixture.manager.unlockUi() }
        firstOwner.await()
        recreatedOwner.await()
        fixture.manager.unlockUi()

        fixture.manager.state.value shouldBe BookSessionState.Ready(fixture.bookId, 1)
        fixture.provider.openCount shouldBe 1
        fixture.factory.openCount shouldBe 1
    }

    @Test
    fun `concurrent reads reuse one resource and lock drains before close`() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()
        fixture.manager.unlockUi()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val read = async {
            fixture.manager.withOperationForTest(LedgerAccessPurpose.UiRead(1)) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        fixture.manager.activeDatabaseOperationCount() shouldBe 1

        val lock = async { fixture.manager.lockUi() }
        runCurrent()
        fixture.manager.state.value shouldBe BookSessionState.Locked
        lock.isCompleted shouldBe false
        fixture.factory.closeCount shouldBe 0

        release.complete(Unit)
        read.await()
        lock.await()
        fixture.manager.activeDatabaseOperationCount() shouldBe 0
        fixture.factory.openCount shouldBe 1
        fixture.factory.closeCount shouldBe 1
    }

    @Test
    fun `secure settings reuse the Ready key and are invalidated on lock`() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()
        fixture.manager.unlockUi()
        fixture.provider.openCount shouldBe 1

        val plaintext = "journal-filter".toByteArray()
        val associatedData = "book-filter".toByteArray()
        lateinit var captured: LedgerSecureSettings
        val ciphertext = fixture.manager.withCurrentSecureSettings(
            fixture.bookId,
            LedgerAccessMode.WRITE,
        ) { settings ->
            captured = settings
            settings.encryptSecureSettings(plaintext, associatedData)
        }
        val decrypted = fixture.manager.withCurrentSecureSettings(
            fixture.bookId,
            LedgerAccessMode.READ,
        ) { settings ->
            settings.decryptSecureSettings(ciphertext, associatedData)
        }
        decrypted.contentEquals(plaintext) shouldBe true
        fixture.provider.openCount shouldBe 1

        fixture.manager.lockUi()
        fixture.factory.closeCount shouldBe 1
        shouldThrow<LedgerSessionAccessException.SessionUnavailable> {
            fixture.manager.withCurrentSecureSettings(fixture.bookId, LedgerAccessMode.READ) { Unit }
        }
        shouldThrow<IllegalStateException> {
            captured.encryptSecureSettings(plaintext, associatedData)
        }

        fixture.manager.unlockUi()
        fixture.provider.openCount shouldBe 2
        val reopened = fixture.manager.withCurrentSecureSettings(
            fixture.bookId,
            LedgerAccessMode.READ,
        ) { settings ->
            settings.decryptSecureSettings(ciphertext, associatedData)
        }
        reopened.contentEquals(plaintext) shouldBe true
    }

    @Test
    fun `top level writes share one non reentrant ordering boundary`() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()
        fixture.manager.unlockUi()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            fixture.manager.withOperationForTest(LedgerAccessPurpose.UiWrite(1)) {
                order += "first-enter"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-exit"
            }
        }
        firstEntered.await()
        val second = async {
            fixture.manager.withOperationForTest(LedgerAccessPurpose.UiWrite(1)) { order += "second" }
        }
        runCurrent()
        order shouldBe listOf("first-enter")
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        order shouldBe listOf("first-enter", "first-exit", "second")
    }

    @Test
    fun `cancelled operation releases its count and expired generation fails closed`() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()
        fixture.manager.unlockUi()
        val entered = CompletableDeferred<Unit>()
        val operation = async {
            fixture.manager.withOperationForTest(LedgerAccessPurpose.UiRead(1)) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        operation.cancel()
        runCatching { operation.await() }
        fixture.manager.activeDatabaseOperationCount() shouldBe 0
        fixture.manager.lockUi()
        fixture.manager.unlockUi()
        shouldThrow<LedgerSessionAccessException.GenerationExpired> {
            fixture.manager.withOperationForTest(LedgerAccessPurpose.UiRead(1)) { Unit }
        }
        fixture.manager.withOperationForTest(LedgerAccessPurpose.UiRead(2)) { Unit } shouldBe Unit
    }

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
    fun `offline primary maintenance requires a closed resource and excludes every other lease`() = runTest {
        val fixture = fixture()
        fixture.manager.initialize()
        fixture.manager.unlockUi()
        fixture.manager.enterMaintenance(MaintenanceReason.CONTROLLED_MAINTENANCE)

        shouldThrow<IllegalStateException> {
            fixture.manager.acquireOfflinePrimaryMaintenancePermit(stableId(30))
        }
        fixture.manager.close()
        val permit = fixture.manager.acquireOfflinePrimaryMaintenancePermit(stableId(30))
        permit.isValidFor(fixture.bookId) shouldBe true
        permit.isValidFor(stableId(31)) shouldBe false
        shouldThrow<IllegalStateException> {
            fixture.manager.acquireMaintenanceLease(stableId(32))
        }
        shouldThrow<IllegalStateException> {
            fixture.manager.finishMaintenance()
        }

        permit.release()
        permit.isValidFor(fixture.bookId) shouldBe false
        fixture.manager.finishMaintenance()
        fixture.manager.state.value shouldBe BookSessionState.Locked
        fixture.factory.openCount shouldBe 1
        fixture.factory.closeCount shouldBe 1
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
        return Fixture(bookId, provider, factory, BookSessionManager(bookId, provider, factory, registry))
    }

    private data class Fixture(
        val bookId: StableId,
        val provider: FakeKeyProvider,
        val factory: FakeResourceFactory,
        val manager: BookSessionManager,
    )

    private class FakeKeyProvider(private val unavailable: Boolean) : DeviceLedgerKeyProvider {
        var initializeCount = 0
        var openCount = 0
        private val attachmentRootKeyset = LedgerTink.generateAeadKeyset()
        private val secureSettingsKeyset = LedgerTink.generateAeadKeyset()

        override fun initialize(bookId: StableId) {
            initializeCount += 1
        }

        override fun open(bookId: StableId): DeviceLedgerKeys {
            if (unavailable) throw SecurityException.KeyUnavailable()
            openCount += 1
            return DeviceLedgerKeys(
                databaseDek = SecretBytes.copyOf(ByteArray(32) { 1 }),
                attachmentRootKeyset = attachmentRootKeyset.useBytes(SecretBytes::copyOf),
                secureSettingsKeyset = secureSettingsKeyset.useBytes(SecretBytes::copyOf),
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

                override suspend fun <T> withDatabase(
                    block: suspend (app.ledger.core.database.LedgerDatabase) -> T,
                ): T = error("fake resource has no Room database")
            }
        }
    }

    private fun stableId(index: Long): StableId = StableId.fromUuid(UUID(0L, index))
}
