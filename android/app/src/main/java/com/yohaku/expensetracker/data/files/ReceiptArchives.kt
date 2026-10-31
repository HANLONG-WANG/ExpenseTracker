package com.yohaku.expensetracker.data.files

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString

/** App-private source paths never enter the exchanged manifest. */
data class ReceiptArchiveSource(val record: ReceiptBundleRecord, val originals: Map<String, File>)

/** Generate complete private temporary ZIPs; caller marks export only after SAF destination saves.
 * Partition at receipt boundaries; a receipt larger than the target stays intact in its own part.
 * Reads and hashes original images through a fixed buffer, including when a single image is large.
 */
fun writeReceiptArchives(bundleId: String, bookId: String, sources: List<ReceiptArchiveSource>,
    directory: File, targetPartBytes: Long = 100_000_000L): List<File> {
    require(targetPartBytes > 0)
    require(sources.isNotEmpty())
    require(sources.map { it.record.receipt_id }.distinct().size == sources.size)
    val parts = mutableListOf<MutableList<ReceiptArchiveSource>>()
    var current = mutableListOf<ReceiptArchiveSource>()
    var currentBytes = 0L
    sources.forEach { source ->
        val bytes = source.record.images.fold(0L) { sum, image -> Math.addExact(sum, image.size_bytes) }
        if (current.isNotEmpty() && (bytes > targetPartBytes || currentBytes > targetPartBytes - bytes)) {
            parts += current; current = mutableListOf(); currentBytes = 0
        }
        current += source; currentBytes = Math.addExact(currentBytes, bytes)
    }
    if (current.isNotEmpty()) parts += current
    check(directory.isDirectory || directory.mkdirs())
    val outputs = mutableListOf<File>()
    try {
        parts.forEachIndexed { index, entries ->
            val manifest = ReceiptBundleFile("yohaku.receipt-bundle", 1, bundleId, bookId, index + 1, parts.size, entries.map { it.record })
            val manifestJson = FileProtocols.json.encodeToString(manifest)
            FileProtocols.receiptBundle(manifestJson)
            val output = File.createTempFile("receipt-part-${index + 1}-", ".zip", directory)
            outputs += output
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestJson.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                entries.forEach { source -> source.record.images.forEach { image ->
                    val original = source.originals[image.image_id] ?: error("图片不存在 / 画像がありません: ${image.image_id}")
                    val hash = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zip.putNextEntry(ZipEntry(image.path))
                    original.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            hash.update(buffer, 0, count); size = Math.addExact(size, count.toLong())
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                    check(size == image.size_bytes && hash.digest().joinToString("") { "%02x".format(it) } == image.sha256) {
                        "图片已变化，请重新导出 / 画像が変わりました。再出力してください: ${image.image_id}"
                    }
                } }
            }
        }
        return outputs
    } catch (e: Exception) {
        outputs.forEach { it.delete() }
        throw e
    }
}
