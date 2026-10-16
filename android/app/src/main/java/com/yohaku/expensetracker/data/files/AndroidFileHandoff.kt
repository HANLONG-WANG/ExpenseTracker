package com.yohaku.expensetracker.data.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.first
import com.yohaku.expensetracker.data.repository.*

/** Decoder rejects malformed UTF-8 instead of silently replacing source characters. */
fun readUtf8Document(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("无法读取文件 / ファイルを読み込めません")
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

/** A new destination is removed on a failed copy. Existing user files are never replaced. */
fun writeNewTreeDocument(context: Context, tree: Uri, name: String, mime: String, write: (java.io.OutputStream) -> Unit): Uri {
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val document = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
        ?: error("无法创建文件 / ファイルを作成できません")
    try {
        val output = context.contentResolver.openOutputStream(document, "w")
            ?: error("无法写入所选位置 / 選択した場所へ書き込めません")
        output.use(write)
        return document
    } catch (e: Exception) {
        try { DocumentsContract.deleteDocument(context.contentResolver, document) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
        throw e
    }
}

/** Manifest is captured before opening the system picker, so returning cannot change the selection.
 * Each successful part is registered after its stream closes; no temporary ZIP is called exported.
 */
suspend fun LedgerRepository.saveReceiptArchivesToTree(context: Context, tree: Uri, manifest: ReceiptBundleFile) {
    val book = dao.book(manifest.book_id) ?: error("账本不存在 / 帳簿がありません")
    val snapshot = snapshot(book).first()
    val sources = manifest.records.map { record ->
        ReceiptArchiveSource(record, record.images.associate { image ->
            image.image_id to mediaFile(snapshot.files.firstOrNull { it.id == image.image_id }
                ?: error("原图不存在 / 元画像がありません"))
        })
    }
    val directory = File(context.cacheDir, "receipt-export-${manifest.bundle_id}")
    try {
        val parts = writeReceiptArchives(manifest.bundle_id, manifest.book_id, sources, directory)
        parts.forEachIndexed { index, zip ->
            val partManifest = ZipFile(zip).use { archive -> FileProtocols.receiptBundle(
                archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader(Charsets.UTF_8).use { it.readText() }) }
            writeNewTreeDocument(context, tree, "yohaku-receipts-${manifest.bundle_id}-${index + 1}-of-${parts.size}.zip", "application/zip") { output ->
                zip.inputStream().use { it.copyTo(output, 65536) }
            }
            recordSavedReceiptExport(partManifest)
        }
    } finally { directory.deleteRecursively() }
}
