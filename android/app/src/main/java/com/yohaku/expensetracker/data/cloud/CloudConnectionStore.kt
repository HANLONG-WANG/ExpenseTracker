package com.yohaku.expensetracker.data.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CloudCredentials {
    val connection:StateFlow<CloudConnectionInfo?>
    suspend fun save(config:CloudConnectionConfig)
    suspend fun markBound()
    suspend fun markNeedsReconnect()
    suspend fun credentials():CloudConnectionConfig?
    suspend fun clear()
}

class CloudConnectionStore(context:Context):CloudCredentials {
    @Serializable private data class Stored(val config:CloudConnectionConfig, val bound:Boolean=false, val needsReconnect:Boolean=false)
    private val file = File(context.noBackupFilesDir, "cloud-connection.bin")
    private val mutex = Mutex()
    private val state = MutableStateFlow<CloudConnectionInfo?>(null)
    override val connection = state.asStateFlow()
    private var loaded = false
    private var stored:Stored? = null
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create:Boolean):SecretKey {
        val existing = keyStore().getKey(ALIAS, null) as SecretKey?
        if (existing != null) return existing
        check(create) { "连接密钥不可用，请重新连接 / 接続鍵がありません。再接続してください" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun publish() { state.value = stored?.let { CloudConnectionInfo(it.config.baseUrl, it.config.id, it.config.apiVersion, it.bound, it.needsReconnect) } }
    private fun read() {
        if (loaded) return
        if (file.exists()) {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            try {
                cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                stored = cloudJson.decodeFromString<Stored>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
            } catch (_:Exception) { error("无法解密连接配置，请重新连接 / 接続設定を復号できません。再接続してください") }
        }
        loaded = true; publish()
    }
    private fun write(value:Stored) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        val encrypted = cipher.doFinal(cloudJson.encodeToString(value).toByteArray(Charsets.UTF_8))
        val temp = File(file.path + ".tmp")
        try {
            temp.outputStream().use { it.write(cipher.iv); it.write(encrypted); it.fd.sync() }
            check(temp.renameTo(file)) { "无法保存连接配置 / 接続設定を保存できません" }
        } finally { temp.delete() }
        stored = value; loaded = true; publish()
    }
    override suspend fun save(config:CloudConnectionConfig) = withContext(Dispatchers.IO) { mutex.withLock { write(Stored(config)) } }
    override suspend fun credentials():CloudConnectionConfig? = withContext(Dispatchers.IO) { mutex.withLock { read(); stored?.config } }
    override suspend fun markBound() = withContext(Dispatchers.IO) { mutex.withLock { read(); write(checkNotNull(stored).copy(bound=true, needsReconnect=false)) } }
    override suspend fun markNeedsReconnect() = withContext(Dispatchers.IO) { mutex.withLock { read(); stored?.let { write(it.copy(needsReconnect=true)) }; Unit } }
    override suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock {
        check(!file.exists() || file.delete()) { "无法删除连接配置 / 接続設定を削除できません" }
        keyStore().deleteEntry(ALIAS); stored=null; loaded=true; publish()
    } }
    companion object { private const val ALIAS = "yohaku-cloud-connection" }
}
