@file:Suppress("MagicNumber", "TooManyFunctions")

package app.ledger.core.telemetry

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class PrivacyDiagnosticStore(private val root: File) {
    private val featureQueue = root.resolve("feature.queue")
    private val crashQueue = root.resolve("crash.queue")
    private val featureIdentifier = root.resolve("feature.id")
    private val crashIdentifier = root.resolve("crash.id")
    private val consent = root.resolve("consent.state")

    init {
        require(root.name == ROOT_NAME)
        check(root.mkdirs() || root.isDirectory)
    }

    fun readFeatureEvents(): List<FeatureQueueEntry> = readFile(featureQueue, FEATURE_MAGIC) { input ->
        FeatureQueueEntry(
            input.readLong(),
            FeatureDiagnosticEvent(
                input.readEnum(),
                input.readEnum(),
                input.readEnum(),
                input.readEnum(),
                input.readEnum(),
            ),
        )
    }

    fun writeFeatureEvents(values: List<FeatureQueueEntry>) = writeFile(featureQueue, FEATURE_MAGIC, values) { output, value ->
        output.writeLong(value.occurredAtEpochMillis)
        output.writeEnum(value.event.name)
        output.writeEnum(value.event.entry)
        output.writeEnum(value.event.outcome)
        output.writeEnum(value.event.duration)
        output.writeEnum(value.event.errorCode)
    }

    fun readCrashEvents(): List<CrashQueueEntry> = readFile(crashQueue, CRASH_MAGIC) { input ->
        val occurredAt = input.readLong()
        val kind = input.readEnum<CrashKind>()
        val code = input.readEnum<SanitizedErrorCode>()
        val count = input.readInt().also { require(it in 0..SanitizedCrashDiagnostic.MAXIMUM_STACK_FRAMES) }
        val frames = buildList(count) {
            repeat(count) {
                val frame = SanitizedStackFrame.create(input.readBoundedAscii(), input.readBoundedAscii(), input.readInt())
                    ?: error("invalid sanitized stack frame")
                add(frame)
            }
        }
        CrashQueueEntry(occurredAt, SanitizedCrashDiagnostic(kind, code, frames))
    }

    fun writeCrashEvents(values: List<CrashQueueEntry>) = writeFile(crashQueue, CRASH_MAGIC, values) { output, value ->
        output.writeLong(value.occurredAtEpochMillis)
        output.writeEnum(value.diagnostic.kind)
        output.writeEnum(value.diagnostic.errorCode)
        output.writeInt(value.diagnostic.frames.size)
        value.diagnostic.frames.forEach { frame ->
            output.writeBoundedAscii(frame.className)
            output.writeBoundedAscii(frame.methodName)
            output.writeInt(frame.lineNumber)
        }
    }

    fun readFeatureIdentifier(): RotatingInstallId? = readIdentifier(featureIdentifier)
    fun readCrashIdentifier(): RotatingInstallId? = readIdentifier(crashIdentifier)
    fun writeFeatureIdentifier(value: RotatingInstallId) = writeIdentifier(featureIdentifier, value)
    fun writeCrashIdentifier(value: RotatingInstallId) = writeIdentifier(crashIdentifier, value)

    fun deleteFeatureData() {
        delete(featureQueue)
        delete(featureIdentifier)
    }

    fun deleteCrashData() {
        delete(crashQueue)
        delete(crashIdentifier)
    }

    fun readConsent(): DiagnosticConsentState = if (!consent.exists()) {
        DiagnosticConsentState()
    } else {
        runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(consent))).use { input ->
                require(input.readInt() == CONSENT_MAGIC && input.readInt() == FORMAT_VERSION)
                DiagnosticConsentState(
                    privacyAccepted = input.readBoolean(),
                    featureEnabled = input.readBoolean(),
                    crashEnabled = input.readBoolean(),
                    featureEnabledAtEpochMillis = input.readLong(),
                    crashEnabledAtEpochMillis = input.readLong(),
                    lastExitCollectedAtEpochMillis = input.readLong(),
                ).also { require(input.read() == -1) }
            }
        }.getOrElse {
            delete(consent)
            DiagnosticConsentState()
        }
    }

    fun writeConsent(value: DiagnosticConsentState) = atomicWrite(consent) { output ->
        output.writeInt(CONSENT_MAGIC)
        output.writeInt(FORMAT_VERSION)
        output.writeBoolean(value.privacyAccepted)
        output.writeBoolean(value.featureEnabled)
        output.writeBoolean(value.crashEnabled)
        output.writeLong(value.featureEnabledAtEpochMillis)
        output.writeLong(value.crashEnabledAtEpochMillis)
        output.writeLong(value.lastExitCollectedAtEpochMillis)
    }

    fun deleteAll() {
        root.listFiles().orEmpty().forEach(::delete)
        check(root.mkdirs() || root.isDirectory)
    }

    private fun readIdentifier(file: File): RotatingInstallId? = if (!file.exists()) {
        null
    } else {
        runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readInt() == IDENTIFIER_MAGIC && input.readInt() == FORMAT_VERSION)
                val createdAt = input.readLong()
                val bytes = ByteArray(RotatingInstallId.BYTE_COUNT).also(input::readFully)
                require(input.read() == -1)
                RotatingInstallId(bytes, createdAt).also { bytes.fill(0) }
            }
        }.getOrElse {
            delete(file)
            null
        }
    }

    private fun writeIdentifier(file: File, value: RotatingInstallId) = atomicWrite(file) { output ->
        output.writeInt(IDENTIFIER_MAGIC)
        output.writeInt(FORMAT_VERSION)
        output.writeLong(value.createdAtEpochMillis)
        val bytes = value.copyBytes()
        try {
            output.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun <T> readFile(file: File, magic: Int, read: (DataInputStream) -> T): List<T> = if (!file.exists()) {
        emptyList()
    } else {
        runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readInt() == magic && input.readInt() == FORMAT_VERSION)
                val count = input.readInt().also { require(it in 0..MAXIMUM_QUEUE_ENTRIES) }
                buildList(count) { repeat(count) { add(read(input)) } }.also { require(input.read() == -1) }
            }
        }.getOrElse {
            delete(file)
            emptyList()
        }
    }

    private fun <T> writeFile(file: File, magic: Int, values: List<T>, write: (DataOutputStream, T) -> Unit) {
        require(values.size <= MAXIMUM_QUEUE_ENTRIES)
        if (values.isEmpty()) {
            delete(file)
            return
        }
        atomicWrite(file) { output ->
            output.writeInt(magic)
            output.writeInt(FORMAT_VERSION)
            output.writeInt(values.size)
            values.forEach { write(output, it) }
        }
    }

    private fun atomicWrite(file: File, block: (DataOutputStream) -> Unit) {
        check(root.mkdirs() || root.isDirectory)
        val temporary = root.resolve("${file.name}.partial")
        FileOutputStream(temporary).use { stream ->
            val output = DataOutputStream(BufferedOutputStream(stream))
            block(output)
            output.flush()
            stream.fd.sync()
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun delete(file: File) {
        if (file.exists()) check(file.delete())
        root.resolve("${file.name}.partial").let { partial -> if (partial.exists()) check(partial.delete()) }
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val ordinal = readInt()
        return enumValues<T>().getOrNull(ordinal) ?: error("unknown diagnostic enum")
    }

    private fun DataOutputStream.writeEnum(value: Enum<*>) = writeInt(value.ordinal)

    private fun DataInputStream.readBoundedAscii(): String {
        val size = readInt().also { require(it in 1..MAXIMUM_ASCII_BYTES) }
        val bytes = ByteArray(size).also(::readFully)
        return try {
            bytes.toString(Charsets.US_ASCII)
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataOutputStream.writeBoundedAscii(value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        require(bytes.size in 1..MAXIMUM_ASCII_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    companion object {
        const val ROOT_NAME: String = "privacy-diagnostics-v1"
        const val MAXIMUM_QUEUE_ENTRIES: Int = 2_048
        private const val MAXIMUM_ASCII_BYTES = 240
        private const val FORMAT_VERSION = 1
        private const val FEATURE_MAGIC = 0x4c465131
        private const val CRASH_MAGIC = 0x4c435131
        private const val IDENTIFIER_MAGIC = 0x4c494431
        private const val CONSENT_MAGIC = 0x4c434f31
    }
}
