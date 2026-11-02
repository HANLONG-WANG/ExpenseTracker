package com.yohaku.expensetracker.data.command

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A 500-entry before/after model can exceed a normal Android CursorWindow when stored as raw TEXT.
 * Only local long-form documents use this envelope; cloud protocol bytes are never compressed.
 * Every document is wrapped, so untrusted plan text cannot impersonate a storage envelope. */
@Serializable private data class StoredCommandDocument(val encoding:String="gzip-base64-v1",val data:String)
internal fun storeCommandJson(text:String):String {
    val bytes=ByteArrayOutputStream()
    GZIPOutputStream(bytes).use{it.write(text.toByteArray(Charsets.UTF_8))}
    return commandJson.encodeToString(StoredCommandDocument(data=Base64.getEncoder().encodeToString(bytes.toByteArray())))
}
internal fun readCommandJson(stored:String):String {
    val document=commandJson.decodeFromString<StoredCommandDocument>(stored)
    check(document.encoding=="gzip-base64-v1")
    return GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(document.data))).bufferedReader(Charsets.UTF_8).use{it.readText()}
}
internal inline fun <reified T> readCommandDocument(stored:String):T=commandJson.decodeFromString(readCommandJson(stored))
