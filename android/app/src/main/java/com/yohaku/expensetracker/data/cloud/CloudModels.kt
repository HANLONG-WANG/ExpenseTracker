package com.yohaku.expensetracker.data.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI

internal val cloudJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class CloudConnectionConfig(val id:String, val baseUrl:String, val deviceToken:String,
    val platformAccessToken:String, val platformHeader:String, val apiVersion:Int) {
    override fun toString() = "CloudConnectionConfig(<redacted>)"
}

data class CloudConnectionInfo(val origin:String, val deviceId:String, val apiVersion:Int,
    val bound:Boolean, val needsReconnect:Boolean)

fun parseCloudConnectionConfig(raw:String, allowLocalHttp:Boolean=false):CloudConnectionConfig {
    val config = try { cloudJson.decodeFromString<CloudConnectionConfig>(raw) }
        catch (_:IllegalArgumentException) { error("连接配置无效 / 接続設定が無効です") }
    require(config.apiVersion == 1) { "不支持的接口版本 / 未対応のAPIバージョンです" }
    require(config.platformHeader == "OAI-Sites-Authorization" &&
        listOf(config.id, config.deviceToken, config.platformAccessToken).all { it.isNotBlank() && it.none(Char::isISOControl) }) {
        "连接配置无效 / 接続設定が無効です"
    }
    val origin = config.baseUrl.removeSuffix("/")
    val uri = try { URI(origin) } catch (_:Exception) { error("站点地址无效 / サイトURLが無効です") }
    require(uri.host != null && uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535) && (uri.scheme == "https" ||
        (allowLocalHttp && uri.scheme == "http" && uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost") && uri.port in 1..65535))) {
        "站点必须是HTTPS来源地址 / サイトにはHTTPSのオリジンを指定してください"
    }
    val port = if (uri.port == -1 || uri.scheme == "https" && uri.port == 443) "" else ":${uri.port}"
    return config.copy(baseUrl = "${uri.scheme}://${uri.host.lowercase()}$port")
}

@Serializable
data class CloudTask(val id:String, val type:String, val status:String, val inputRevision:Int,
    val files:List<CloudFile>, val results:List<CloudResult>, val events:List<CloudEvent>,
    val title:String?=null, val context:JsonObject?=null, val createdAt:String?=null, val updatedAt:String?=null)
@Serializable
data class CloudFile(val id:String, val role:String, val name:String, val size:Long, val sha256:String, val mime:String?=null)
@Serializable
data class CloudResult(val id:String, val version:Int, val inputRevision:Int, val fileId:String,
    val state:String, val stale:Boolean, val validation:CloudValidation, val summary:String?=null)
@Serializable
data class CloudValidation(val errors:List<String>, val warnings:List<String>)
@Serializable
data class CloudEvent(val id:String?=null, val message:String?=null, val createdAt:String?=null)

fun CloudTask.claimableResult():CloudResult? = if (status != "ready") null else results
    .filter { it.state == "ready" && !it.stale && it.inputRevision == inputRevision }.maxByOrNull { it.version }

data class CloudClaim(val localId:String, val bookId:String, val type:String, val taskId:String,
    val resultId:String, val resultVersion:Int, val sha256:String, val file:File, val text:String,
    val summary:String?, val warnings:List<String>)

enum class CloudPhase { PREPARING, UPLOADING, SUBMITTED, QUEUED, ANALYZING, NEEDS_INFO, REVIEW, READY, IMPORTED, ARCHIVED, BLOCKED }

data class CloudTaskView(val task:CloudTaskEntity, val transfers:List<CloudTransferEntity>,
    val imports:List<CloudImportEntity>, val pendingAcks:List<CloudAckEntity>, val phase:CloudPhase,
    val claimable:CloudResult?, val remote:CloudTask?, val ackWarnings:List<String> = emptyList()) {
    val ackPending:Boolean get() = pendingAcks.isNotEmpty()
    val latestEventMessages:List<String> get() = remote?.events.orEmpty().sortedByDescending { it.createdAt }.mapNotNull { it.message }
}

internal fun importKey(origin:String, taskId:String, resultId:String, sha256:String) = "$origin|$taskId|$resultId|$sha256"
fun cloudTaskView(task:CloudTaskEntity, transfers:List<CloudTransferEntity>, imports:List<CloudImportEntity>, acks:List<CloudAckEntity>):CloudTaskView {
    val remote = task.remoteJson?.let { cloudJson.decodeFromString<CloudTask>(it) }
    val result = remote?.claimableResult()
    val imported = result != null && imports.any { it.taskId == remote.id && it.resultId == result.id &&
        it.sha256 == remote.files.find { file -> file.id == result.fileId }?.sha256 }
    val phase = when {
        task.stage == "BLOCKED" -> CloudPhase.BLOCKED
        imported -> CloudPhase.IMPORTED
        result != null -> CloudPhase.READY
        remote?.status == "ready" -> CloudPhase.REVIEW
        remote?.status == "queued" -> CloudPhase.QUEUED
        remote?.status == "analyzing" -> CloudPhase.ANALYZING
        remote?.status == "needs_info" -> if (task.stage == "UPLOADING") CloudPhase.UPLOADING else CloudPhase.NEEDS_INFO
        remote?.status == "review" -> CloudPhase.REVIEW
        remote?.status == "archived" -> CloudPhase.ARCHIVED
        task.stage == "SUBMITTED" -> CloudPhase.SUBMITTED
        task.stage == "UPLOADING" || remote?.status == "uploading" -> CloudPhase.UPLOADING
        else -> CloudPhase.PREPARING
    }
    return CloudTaskView(task, transfers.sortedBy { it.position }, imports, acks.filter { it.sentAt == null }, phase, result, remote,
        acks.filter { it.sentAt != null }.mapNotNull { it.lastError })
}
