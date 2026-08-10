@file:Suppress("MagicNumber", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package app.ledger.app

import android.content.Context
import android.util.AtomicFile
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.application.MaterializedRestorePackage
import app.ledger.finance.application.RestoreArtifactSwapPort
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Crash-recoverable swap for encrypted settings, attachment objects and the optional Vault envelope. */
internal class AndroidRestoreArtifactSwapPort(context: Context) : RestoreArtifactSwapPort {
    private val applicationContext = context.applicationContext

    override fun stage(value: MaterializedRestorePackage) {
        val paths = paths(value.operationId, value.bookId)
        cleanup(value.operationId)
        val settingsSource = value.settingsPath?.let(::File)?.canonicalFile
            ?: error("restored settings are missing")
        require(settingsSource.isFile)
        copyFile(settingsSource, paths.settingsStage)
        value.attachmentDirectoryPath?.let(::File)?.canonicalFile?.let { source ->
            require(source.isDirectory)
            copyDirectory(source, paths.attachmentsStage)
        } ?: require(paths.attachmentsStage.mkdirs())
        value.vaultEnvelopePath?.let(::File)?.canonicalFile?.let { source ->
            require(source.isFile)
            copyFile(source, paths.vaultStage)
        }
        writeDescriptor(paths.descriptor, value.bookId)
    }

    override fun exchange(operationId: StableId) {
        val paths = paths(operationId, readBookId(operationId))
        require(paths.descriptor.isFile && paths.settingsStage.isFile && paths.attachmentsStage.isDirectory)
        writeMarker(paths.marker)
        swapFile(paths.settingsLive, paths.settingsStage, paths.settingsSafety)
        swapDirectory(paths.attachmentsLive, paths.attachmentsStage, paths.attachmentsSafety)
        if (paths.vaultStage.isFile) swapFile(paths.vaultLive, paths.vaultStage, paths.vaultSafety)
    }

    override fun rollback(operationId: StableId) {
        val descriptor = descriptor(operationId)
        if (!descriptor.isFile) return
        val paths = paths(operationId, readBookId(operationId))
        if (!paths.marker.isFile) return
        restoreFile(paths.settingsLive, paths.settingsStage, paths.settingsSafety)
        restoreDirectory(paths.attachmentsLive, paths.attachmentsStage, paths.attachmentsSafety)
        restoreFile(paths.vaultLive, paths.vaultStage, paths.vaultSafety)
        AtomicFile(paths.marker).delete()
    }

    override fun recover(operationId: StableId): Boolean {
        val descriptor = descriptor(operationId)
        if (!descriptor.isFile) return false
        val paths = paths(operationId, readBookId(operationId))
        if (!paths.marker.isFile) return false
        rollback(operationId)
        return true
    }

    override fun cleanup(operationId: StableId) {
        val descriptor = descriptor(operationId)
        val bookId = runCatching { readBookId(operationId) }.getOrNull()
        val paths = bookId?.let { paths(operationId, it) }
        paths?.let {
            deleteScoped(it.settingsStage)
            deleteScoped(it.settingsSafety)
            deleteScoped(it.attachmentsStage)
            deleteScoped(it.attachmentsSafety)
            deleteScoped(it.vaultStage)
            deleteScoped(it.vaultSafety)
            AtomicFile(it.marker).delete()
        }
        AtomicFile(descriptor).delete()
    }

    private fun swapFile(live: File, stage: File, safety: File) {
        deleteScoped(safety)
        if (live.isFile) atomicMove(live, safety)
        try {
            atomicMove(stage, live)
        } catch (error: Exception) {
            if (safety.isFile) atomicMove(safety, live)
            throw error
        }
    }

    private fun swapDirectory(live: File, stage: File, safety: File) {
        deleteScoped(safety)
        val safetyParent = requireNotNull(safety.parentFile)
        require(safetyParent.isDirectory || safetyParent.mkdirs())
        if (live.isDirectory) atomicMove(live, safety)
        try {
            atomicMove(stage, live)
        } catch (error: Exception) {
            if (safety.isDirectory) atomicMove(safety, live)
            throw error
        }
    }

    private fun restoreFile(live: File, stage: File, safety: File) {
        when {
            safety.isFile -> {
                deleteScoped(live)
                atomicMove(safety, live)
            }
            !stage.exists() -> deleteScoped(live)
        }
    }

    private fun restoreDirectory(live: File, stage: File, safety: File) {
        when {
            safety.isDirectory -> {
                deleteScoped(live)
                atomicMove(safety, live)
            }
            !stage.exists() -> deleteScoped(live)
        }
    }

    private fun copyFile(source: File, target: File) {
        val parent = requireNotNull(target.parentFile)
        require(parent.isDirectory || parent.mkdirs())
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, COPY_BUFFER_BYTES)
                output.fd.sync()
            }
        }
    }

    private fun copyDirectory(source: File, target: File) {
        deleteScoped(target)
        require(target.mkdirs())
        source.walkTopDown().forEach { item ->
            val relative = item.relativeTo(source).path
            val destination = if (relative.isEmpty()) target else File(target, relative)
            require(destination.canonicalFile.toPath().startsWith(target.canonicalFile.toPath()))
            if (item.isDirectory) {
                require(destination.isDirectory || destination.mkdirs())
            } else {
                copyFile(item, destination)
            }
        }
    }

    private fun atomicMove(source: File, target: File) {
        val parent = requireNotNull(target.parentFile)
        require(parent.isDirectory || parent.mkdirs())
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IllegalStateException("atomic artifact exchange is unavailable", error)
        }
    }

    private fun deleteScoped(value: File) {
        if (!value.exists()) return
        val roots = listOf(applicationContext.filesDir.canonicalFile, applicationContext.noBackupFilesDir.canonicalFile)
        require(roots.any { value.canonicalFile.toPath().startsWith(it.toPath()) } && value.canonicalFile !in roots)
        value.walkBottomUp().forEach { file -> check(file.delete()) }
    }

    private fun writeDescriptor(target: File, bookId: StableId) {
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            val data = DataOutputStream(output)
            data.writeInt(DESCRIPTOR_MAGIC)
            data.write(bookId.bytes)
            data.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun writeMarker(target: File) {
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            val data = DataOutputStream(output)
            data.writeInt(MARKER_MAGIC)
            data.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun readBookId(operationId: StableId): StableId = DataInputStream(AtomicFile(descriptor(operationId)).openRead()).use { input ->
        require(input.readInt() == DESCRIPTOR_MAGIC)
        StableId.fromBytes(ByteArray(StableId.BYTE_COUNT).also(input::readFully)).required()
    }

    private fun descriptor(operationId: StableId) = applicationContext.filesDir.resolve("restore-artifacts-${operationId.hex()}.descriptor")

    private fun paths(operationId: StableId, bookId: StableId): ArtifactPaths {
        val operation = operationId.hex()
        val attachmentParent = File(applicationContext.noBackupFilesDir, "attachment_objects/${bookId.toUuid()}")
        val vaultParent = File(applicationContext.noBackupFilesDir, "vault-backup-envelopes-v1")
        val vaultName = bookId.toString() + ".envelope"
        return ArtifactPaths(
            descriptor(operationId),
            applicationContext.filesDir.resolve("restore-artifacts-$operation.marker"),
            applicationContext.filesDir.resolve("ledger_app_settings.pb"),
            applicationContext.filesDir.resolve(".restore-$operation.settings.stage"),
            applicationContext.filesDir.resolve(".restore-$operation.settings.safety"),
            File(attachmentParent, "objects"),
            File(attachmentParent, ".restore-$operation.attachments.stage"),
            File(attachmentParent, ".restore-$operation.attachments.safety"),
            File(vaultParent, vaultName),
            File(vaultParent, ".$vaultName.restore-$operation.stage"),
            File(vaultParent, ".$vaultName.restore-$operation.safety"),
        )
    }

    private data class ArtifactPaths(
        val descriptor: File,
        val marker: File,
        val settingsLive: File,
        val settingsStage: File,
        val settingsSafety: File,
        val attachmentsLive: File,
        val attachmentsStage: File,
        val attachmentsSafety: File,
        val vaultLive: File,
        val vaultStage: File,
        val vaultSafety: File,
    )

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val DESCRIPTOR_MAGIC = 0x52535441
        const val MARKER_MAGIC = 0x5253544d
    }
}

private fun StableId.hex(): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
private fun <T> DomainResult<T>.required(): T = (this as DomainResult.Success).value
