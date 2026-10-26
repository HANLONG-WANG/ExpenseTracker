package com.yohaku.expensetracker.data.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

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
