package com.yohaku.expensetracker.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class CloudHttpException(val status:Int, message:String):IOException(message)
class CloudIntegrityException:IllegalStateException("结果大小或SHA-256不一致，请刷新任务 / サイズまたはSHA-256が一致しません。タスクを更新してください")
internal const val CLOUD_FILE_LIMIT = 100_000_000L
internal fun sha256(file:File):String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
    while (true) { val n=input.read(buffer); if (n<0) break; digest.update(buffer,0,n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun validCloudName(name:String) = name.length in 1..180 && name.none { it == '/' || it == '\\' || it.isISOControl() }

class CloudApi(private val baseUrl:String, private val config:CloudConnectionConfig,
    private val openConnection:(URL)->HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    init { require(baseUrl == config.baseUrl) { "连接来源不一致 / 接続元が一致しません" } }
    private fun encode(value:String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun safe(message:String) = message.replace(config.deviceToken, "[redacted]").replace(config.platformAccessToken, "[redacted]")
    private suspend fun <T> request(path:String, method:String="GET", block:(HttpURLConnection)->T):T = withContext(Dispatchers.IO) {
        val connection = openConnection(URL(baseUrl + path))
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=15000; connection.readTimeout=180000; connection.requestMethod=method
            connection.setRequestProperty("OAI-Sites-Authorization", "Bearer ${config.platformAccessToken}")
            connection.setRequestProperty("Authorization", "Bearer ${config.deviceToken}")
            block(connection)
        } catch (e:CloudHttpException) { throw e }
        catch (_:IOException) { throw IOException("网络请求失败，请重试 / 通信に失敗しました。再試行してください") }
        finally { connection.disconnect() }
    }
    private fun checkStatus(connection:HttpURLConnection) {
        val status=connection.responseCode
        if (status in 200..299) return
        var message = "网关或认证失败，请检查连接 / ゲートウェイまたは認証に失敗しました。接続を確認してください"
        if (status !in 300..399 && connection.contentType?.substringBefore(';')?.trim() == "application/json") {
            val body=connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (body != null) try {
                (cloudJson.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull?.let { message=safe(it) }
            } catch (_:IllegalArgumentException) { /* Gateway errors need not follow the API schema. */ }
        }
        throw CloudHttpException(status,message)
    }
    private fun response(connection:HttpURLConnection):JsonObject {
        checkStatus(connection)
        if (connection.contentType?.substringBefore(';')?.trim() != "application/json")
            throw IOException("网关未返回JSON，请检查认证或网络 / JSON応答がありません。認証または通信を確認してください")
        val value = try {
            cloudJson.parseToJsonElement(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
        } catch (_:IllegalArgumentException) { throw IOException("接口响应无效 / API応答が無効です") }
        value["apiVersion"]?.let { require(it.jsonPrimitive.intOrNull == 1) { "不支持的接口版本 / 未対応のAPIバージョンです" } }
        return value
    }
    private fun taskResponse(value:JsonObject):CloudTask = try { cloudJson.decodeFromJsonElement<CloudTask>(value) }
        catch (_:IllegalArgumentException) { throw IOException("任务响应无效 / タスク応答が無効です") }
    private fun body(connection:HttpURLConnection, text:String) {
        val bytes=text.toByteArray(Charsets.UTF_8)
        connection.doOutput=true; connection.setRequestProperty("Content-Type", "application/json")
        connection.setFixedLengthStreamingMode(bytes.size); connection.outputStream.use { it.write(bytes) }
    }
    suspend fun types():JsonObject = request("/api/v1/types") { response(it) }
    suspend fun createTask(idempotencyKey:String, bodyJson:String):CloudTask = request("/api/v1/tasks", "POST") {
        it.setRequestProperty("Idempotency-Key", idempotencyKey); body(it,bodyJson); taskResponse(response(it))
    }
    suspend fun task(taskId:String):CloudTask = request("/api/v1/tasks/${encode(taskId)}") { taskResponse(response(it)) }
    suspend fun uploadFile(taskId:String, name:String, file:File, size:Long, sha256:String, mime:String):String {
        require(size in 1 until CLOUD_FILE_LIMIT && validCloudName(name)) { "文件大小或名称无效 / ファイルサイズまたは名前が無効です" }
        return request("/api/v1/tasks/${encode(taskId)}/files?name=${encode(name)}", "POST") {
            it.doOutput=true; it.setRequestProperty("Content-Type",mime); it.setRequestProperty("X-Content-SHA256",sha256)
            it.setFixedLengthStreamingMode(size)
            it.outputStream.use { output -> file.inputStream().use { input -> input.copyTo(output,65536) } }
            response(it)["fileId"]?.jsonPrimitive?.content ?: throw IOException("上传响应缺少文件ID / アップロード応答にファイルIDがありません")
        }
    }
    suspend fun submit(taskId:String):CloudTask = request("/api/v1/tasks/${encode(taskId)}/submit", "POST") { taskResponse(response(it)) }
    suspend fun downloadFile(fileId:String, expectedSize:Long, expectedSha:String, dest:File) {
        val partial=File(dest.path+".partial")
        try {
            request("/api/v1/files/${encode(fileId)}") { connection ->
                checkStatus(connection)
                if (connection.contentType?.substringBefore(';')?.trim() != "application/octet-stream")
                    throw IOException("网关返回了非结果文件，请检查连接 / 結果ファイル以外の応答です。接続を確認してください")
                if (connection.getHeaderField("X-Content-SHA256") != expectedSha ||
                    connection.getHeaderField("Content-Length")?.let { it.toLongOrNull() != expectedSize } == true) throw CloudIntegrityException()
                val digest=MessageDigest.getInstance("SHA-256"); var size=0L
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer=ByteArray(65536)
                    while (true) {
                        val n=input.read(buffer); if(n<0)break
                        size+=n; if(size>expectedSize)throw CloudIntegrityException()
                        digest.update(buffer,0,n); output.write(buffer,0,n)
                    }
                } }
                if (size!=expectedSize || digest.digest().joinToString("") { "%02x".format(it) }!=expectedSha) throw CloudIntegrityException()
                check(partial.renameTo(dest)) { "无法保存结果文件 / 結果ファイルを保存できません" }
            }
        } finally { partial.delete() }
    }
    suspend fun ack(taskId:String, resultId:String, status:String) {
        require(status in setOf("imported","failed"))
        request("/api/v1/tasks/${encode(taskId)}/ack", "POST") {
            body(it,buildJsonObject { put("resultId",resultId); put("status",status) }.toString()); response(it)
        }
    }
}
