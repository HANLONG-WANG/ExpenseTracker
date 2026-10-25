package com.yohaku.expensetracker.data.cloud

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class CloudContractTest {
    private fun config(origin:String="https://example.com") = CloudConnectionConfig("device",origin,"device-test","platform-test","OAI-Sites-Authorization",1)
    @Test fun configurationRequiresSupportedVersionAndOrigin() {
        val valid=cloudJson.encodeToString(config("https://example.com/"))
        assertEquals("https://example.com",parseCloudConnectionConfig(valid.dropLast(1)+",\"future\":true}").baseUrl)
        for (value in listOf(config().copy(apiVersion=2),config("http://example.com"),config("https://example.com/path"),config("https://example.com?x=1"))) {
            assertThrows(IllegalArgumentException::class.java) { parseCloudConnectionConfig(cloudJson.encodeToString(value)) }
        }
        val local=cloudJson.encodeToString(config("http://10.0.2.2:8787"))
        assertThrows(IllegalArgumentException::class.java) { parseCloudConnectionConfig(local) }
        assertEquals("http://10.0.2.2:8787",parseCloudConnectionConfig(local,true).baseUrl)
        assertThrows(IllegalArgumentException::class.java) { parseCloudConnectionConfig(cloudJson.encodeToString(config("http://example.com:8787")),true) }
    }
    @Test fun onlyCurrentPublishedResultIsClaimableAndImportsArePermanent() {
        val good=CloudResult("good",1,3,"file","ready",false,CloudValidation(emptyList(),listOf("warning")))
        val task=CloudTask("remote","general","ready",3,listOf(CloudFile("file","result","result.txt",4,"sha")),
            listOf(good.copy(id="draft",version=4,state="draft"),good.copy(id="stale",version=3,stale=true),
                good.copy(id="old",version=2,inputRevision=2),good),emptyList())
        assertEquals("good",task.claimableResult()?.id)
        assertNull(task.copy(status="review").claimableResult())
        assertNull(task.copy(results=task.results.dropLast(1)).claimableResult())
        val row=CloudTaskEntity("local","book","general","title",null,null,"https://example.com","key","{}",
            taskId="remote",stage="SUBMITTED",remoteJson=cloudJson.encodeToString(task),createdAt=1,updatedAt=1)
        assertEquals(CloudPhase.READY,cloudTaskView(row,emptyList(),emptyList(),emptyList()).phase)
        val imported=CloudImportEntity(importKey(row.origin,"remote","good","sha"),"local","remote","good",1,"sha","saved.txt",2)
        val ack=CloudAckEntity("local","good","imported","remote",2)
        val view=cloudTaskView(row,emptyList(),listOf(imported),listOf(ack))
        assertEquals(CloudPhase.IMPORTED,view.phase); assertTrue(view.ackPending)
        val conflict=cloudTaskView(row,emptyList(),listOf(imported),listOf(ack.copy(sentAt=3,lastError="version conflict")))
        assertEquals(CloudPhase.IMPORTED,conflict.phase); assertFalse(conflict.ackPending)
        assertEquals(listOf("version conflict"),conflict.ackWarnings)
        assertEquals(CloudPhase.SUBMITTED,cloudTaskView(row.copy(remoteJson=null),emptyList(),emptyList(),emptyList()).phase)
        assertEquals(CloudPhase.BLOCKED,cloudTaskView(row.copy(stage="BLOCKED"),emptyList(),emptyList(),emptyList()).phase)
    }
    @Test fun httpUsesTwoHeadersFixedLengthAndNeverFollowsRedirects() = runBlocking {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val received=mutableMapOf<String,String?>(); val redirects=AtomicInteger()
        val directory=Files.createTempDirectory("cloud-http").toFile()
        val file=File(directory,"source").apply { writeText("raw binary source") }
        val sha=sha256(file)
        server.createContext("/api/v1/tasks/task/files") { exchange ->
            for(name in listOf("Authorization","OAI-Sites-Authorization","Content-Length","Transfer-Encoding","X-Content-SHA256","Origin"))received[name]=exchange.requestHeaders.getFirst(name)
            received["query"]=exchange.requestURI.rawQuery
            received["body"]=exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val bytes="{\"fileId\":\"file\",\"future\":true}".toByteArray()
            exchange.responseHeaders.add("Content-Type","application/json"); exchange.sendResponseHeaders(201,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/v1/types") { exchange ->
            received["typesAuth"]=exchange.requestHeaders.getFirst("Authorization")
            received["typesPlatform"]=exchange.requestHeaders.getFirst("OAI-Sites-Authorization")
            exchange.responseHeaders.add("Location","/stolen"); exchange.sendResponseHeaders(302,-1); exchange.close()
        }
        server.createContext("/stolen") { exchange -> redirects.incrementAndGet(); exchange.sendResponseHeaders(200,-1); exchange.close() }
        server.start()
        try {
            val cfg=config("http://127.0.0.1:${server.address.port}"); val api=CloudApi(cfg.baseUrl,cfg)
            assertEquals("file",api.uploadFile("task","a + %.txt",file,file.length(),sha,"text/plain"))
            assertEquals("Bearer device-test",received["Authorization"])
            assertEquals("Bearer platform-test",received["OAI-Sites-Authorization"])
            assertEquals(file.length().toString(),received["Content-Length"])
            assertEquals(sha,received["X-Content-SHA256"]); assertNull(received["Transfer-Encoding"]); assertNull(received["Origin"])
            assertEquals("name=a%20%2B%20%25.txt",received["query"]); assertEquals(file.readText(),received["body"])
            try { api.types(); fail("Redirect should fail") } catch(e:CloudHttpException) { assertEquals(302,e.status) }
            assertEquals(0,redirects.get())
            assertEquals("Bearer device-test",received["typesAuth"]); assertEquals("Bearer platform-test",received["typesPlatform"])
        } finally { server.stop(0); directory.deleteRecursively() }
    }
    @Test fun downloadMismatchDeletesPartialFile() = runBlocking {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val bytes="wrong".toByteArray()
        val expected=MessageDigest.getInstance("SHA-256").digest("right".toByteArray()).joinToString("") { "%02x".format(it) }
        server.createContext("/api/v1/files/file") { exchange ->
            exchange.responseHeaders.add("Content-Type","application/octet-stream")
            exchange.responseHeaders.add("X-Content-SHA256",expected)
            exchange.sendResponseHeaders(200,bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val directory=Files.createTempDirectory("cloud-download").toFile(); val dest=File(directory,"result")
        try {
            val cfg=config("http://127.0.0.1:${server.address.port}")
            try { CloudApi(cfg.baseUrl,cfg).downloadFile("file",5,expected,dest); fail("Hash mismatch should fail") }
            catch(_:CloudIntegrityException) { assertFalse(dest.exists()); assertFalse(File(dest.path+".partial").exists()) }
        } finally { server.stop(0); directory.deleteRecursively() }
    }
}
