package com.yohaku.expensetracker.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable data class BackupEntry(val path:String,val size:Long,val sha256:String)
@Serializable data class BackupBook(val id:String,val name:String,val recordCount:Long)
@Serializable data class BackupManifest(
    val format:String,val formatVersion:Int,
    val versionName:String,val versionCode:Long,val schemaVersion:Int,val createdAt:String,
    val books:List<BackupBook>,val attachmentCount:Int,val attachmentBytes:Long,val entries:List<BackupEntry>
)

/** Platform independent wire format. Only the declared payload is accepted, never arbitrary paths. */
object BackupArchive {
    val json=Json { encodeDefaults=true }
    fun safePath(path:String) {
        require(path.isNotEmpty()&&!path.startsWith('/')&&!path.contains('\\')&&!path.contains(':')&&
            path.split('/').none{it.isEmpty()||it==".."||it=="."}) { "备份路径不安全 / バックアップのパスが不正です" }
    }
    fun validate(manifest:BackupManifest,currentSchema:Int) {
        require(manifest.format=="yohaku.backup"&&manifest.formatVersion==1) { "不支持的备份格式 / 未対応のバックアップ形式です" }
        require(manifest.schemaVersion in 1..currentSchema) { "备份需要更新版本，请先更新应用 / このバックアップにはアプリの更新が必要です" }
        Instant.parse(manifest.createdAt)
        require(manifest.versionName.isNotBlank()&&manifest.versionCode>0)
        require(manifest.books.map{it.id}.distinct().size==manifest.books.size&&manifest.books.all{it.id.isNotBlank()&&it.name.isNotBlank()&&it.recordCount>=0})
        manifest.entries.forEach { e ->
            safePath(e.path)
            require(e.path=="database/yohaku.db"||e.path=="preferences.json"||e.path.startsWith("attachments/")) { "备份含未知条目 / 不明な項目があります" }
            require(e.size>=0&&e.sha256.matches(Regex("[0-9a-f]{64}"))) { "备份条目格式错误 / 項目の形式が不正です" }
        }
        val paths=manifest.entries.map{it.path}
        require(paths.distinct().size==paths.size&&"database/yohaku.db" in paths&&"preferences.json" in paths) { "备份缺少必要条目或含重复项 / 必須項目がないか重複しています" }
        val attachments=manifest.entries.filter{it.path.startsWith("attachments/")}
        require(manifest.attachmentCount==attachments.size&&manifest.attachmentBytes==attachments.fold(0L){sum,e->Math.addExact(sum,e.size)}) { "附件摘要不一致 / 添付ファイルの概要が一致しません" }
    }
    fun entry(root:File,file:File)=BackupEntry(file.relativeTo(root).invariantSeparatorsPath,file.length(),sha256(file))
    fun sha256(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    fun write(root:File,manifest:BackupManifest,output:OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(json.encodeToString(manifest).toByteArray());zip.closeEntry()
            manifest.entries.forEach{e->zip.putNextEntry(ZipEntry(e.path));File(root,e.path).inputStream().use{it.copyTo(zip)};zip.closeEntry()}
        }
    }
    /** Extract to a private empty staging directory; failure never touches live application data. */
    fun extract(input:InputStream,root:File,currentSchema:Int):BackupManifest {
        check(root.mkdirs())
        try {
            val seen=mutableSetOf<String>()
            ZipInputStream(input.buffered()).use{zip->while(true){
                val e=zip.nextEntry?:break
                safePath(e.name)
                require(!e.isDirectory&&seen.add(e.name)) { "备份含重复条目或目录项 / 重複項目またはディレクトリ項目があります" }
                require(e.name=="manifest.json"||e.name=="preferences.json"||e.name=="database/yohaku.db"||e.name.startsWith("attachments/")) { "备份含未知条目 / 不明な項目があります" }
                val file=File(root,e.name);check(file.parentFile!!.isDirectory||file.parentFile!!.mkdirs())
                file.outputStream().use{zip.copyTo(it)};zip.closeEntry()
            }}
            require("manifest.json" in seen) { "缺少 manifest.json / manifest.json がありません" }
            val manifest=json.decodeFromString<BackupManifest>(File(root,"manifest.json").readText())
            validate(manifest,currentSchema)
            require(seen==manifest.entries.map{it.path}.toSet()+"manifest.json") { "备份清单与文件不一致 / 一覧とファイルが一致しません" }
            verify(root,manifest,currentSchema)
            return manifest
        }catch(e:Exception){root.deleteRecursively();throw e}
    }
    fun verify(root:File,manifest:BackupManifest,currentSchema:Int) {
        validate(manifest,currentSchema)
        manifest.entries.forEach{e->val file=File(root,e.path)
            require(file.isFile&&file.length()==e.size&&sha256(file)==e.sha256) { "备份文件大小或 SHA-256 不一致：${e.path} / サイズまたは SHA-256 が一致しません：${e.path}" }
        }
    }
}
