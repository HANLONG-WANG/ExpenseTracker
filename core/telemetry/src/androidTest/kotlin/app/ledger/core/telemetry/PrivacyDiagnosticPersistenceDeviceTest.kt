package app.ledger.core.telemetry

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.acra.ACRA
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrivacyDiagnosticPersistenceDeviceTest {
    private lateinit var context: Context
    private lateinit var storeRoot: File
    private var now = 1_700_000_000_000L
    private var seed = 1

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        storeRoot = context.noBackupFilesDir.resolve(PrivacyDiagnosticStore.ROOT_NAME)
        storeRoot.deleteRecursively()
        TelemetryRuntime.clear()
    }

    @After
    fun cleanup() {
        TelemetryRuntime.clear()
        storeRoot.deleteRecursively()
    }

    @Test
    fun consentQueuesSurviveManagerRestartAndDisablingDeletesQueueAndIdentifierFiles() {
        manager().apply {
            record(feature())
            recordCrash(crash())
            assertTrue(snapshot().featureEvents.isEmpty())
            assertTrue(snapshot().crashEvents.isEmpty())
            applyConsent(true, true, true)
            record(feature())
            recordCrash(crash())
        }
        val restarted = manager()
        assertTrue(restarted.snapshot().featureEvents.size == 1)
        assertTrue(restarted.snapshot().crashEvents.size == 1)
        val previousFiles = storeRoot.listFiles().orEmpty().map(File::getName).toSet()
        assertTrue("feature.id" in previousFiles && "crash.id" in previousFiles)
        val previousIdentifierBytes = storeRoot.resolve("feature.id").readBytes()

        restarted.applyConsent(true, false, false)
        val disabledFiles = storeRoot.listFiles().orEmpty().map(File::getName).toSet()
        assertFalse("feature.id" in disabledFiles)
        assertFalse("crash.id" in disabledFiles)
        assertFalse("feature.queue" in disabledFiles)
        assertFalse("crash.queue" in disabledFiles)

        now += 1_000L
        restarted.applyConsent(true, true, true)
        val newIdentifierBytes = storeRoot.resolve("feature.id").readBytes()
        assertNotEquals(previousIdentifierBytes.contentHashCode(), newIdentifierBytes.contentHashCode())
    }

    @Test
    fun persistedFilesNeverContainBusinessOrVaultSentinelsAndCorruptionFailsClosed() {
        val sentinel = "PAN-4111111111111111-CVC-123-private-note"
        val manager = manager()
        manager.applyConsent(true, true, true)
        manager.record(feature())
        manager.recordCrash(PrivacyCrashSanitizer.fromThrowable(IllegalStateException(sentinel)))
        val persisted = storeRoot.walkTopDown().filter(File::isFile).flatMap { it.readBytes().asSequence() }.toList().toByteArray()
        assertFalse(persisted.toString(Charsets.ISO_8859_1).contains(sentinel))
        storeRoot.resolve("crash.queue").writeText(sentinel)
        assertTrue(manager().snapshot().crashEvents.isEmpty())
        assertFalse(storeRoot.resolve("crash.queue").exists())
    }

    @Test
    fun acraCustomSenderAndApplicationExitInfoCollectorUseOnlyTheWhitelistedQueue() {
        val manager = manager()
        manager.applyConsent(true, false, true)
        TelemetryRuntime.install(manager)
        AcraPrivacyInstaller.install(context.applicationContext as Application)
        assertTrue(ACRA.isInitialised)
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() is SanitizingUncaughtExceptionHandler)
        ApplicationExitDiagnosticCollector(context).collect()
        manager.snapshot().crashEvents.forEach { event ->
            assertTrue(event.diagnostic.frames.size <= SanitizedCrashDiagnostic.MAXIMUM_STACK_FRAMES)
            assertTrue(event.diagnostic.frames.all { '@' !in it.className && '@' !in it.methodName })
        }
    }

    private fun manager(): PrivacyDiagnosticManager = PrivacyDiagnosticManager(
        context,
        { now },
        { bytes -> bytes.fill(seed++.toByte()) },
        PrivacyDiagnosticRuntime(
            "1.0.0",
            android.os.Build.VERSION.SDK_INT,
            DeviceCapabilityCategory.DEVICE_CREDENTIAL_ONLY,
        ),
    )

    private fun feature() = FeatureDiagnosticEvent(
        FeatureEventName.FEATURE_OPENED,
        FeatureEntry.VAULT,
        DiagnosticOutcome.SUCCEEDED,
        DurationBucket.UNDER_1_SECOND,
    )

    private fun crash() = SanitizedCrashDiagnostic(
        CrashKind.JAVA_UNCAUGHT,
        SanitizedErrorCode.UNCAUGHT_EXCEPTION,
        listOf(requireNotNull(SanitizedStackFrame.create("app.ledger.Safe", "run", 12))),
    )
}
