@file:Suppress("RestrictedApi")

package app.ledger.benchmark

import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.benchmark.Outputs
import androidx.test.runner.AndroidJUnitRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Carries the AndroidX benchmark report through instrumentation results on every API level.
 * Gradle Managed Devices cannot copy additional test output on API 28, so the report is
 * integrity-tagged, compressed, and split into Binder-safe text chunks for host extraction.
 */
class P37BenchmarkRunner : AndroidJUnitRunner() {
    override fun finish(resultCode: Int, results: Bundle) {
        val augmented = Bundle(results)
        runCatching { attachBenchmarkData(augmented) }
            .onFailure { error ->
                augmented.putString(KEY_STATUS, "error:${error.javaClass.simpleName}")
            }
        super.finish(resultCode, augmented)
    }

    private fun attachBenchmarkData(results: Bundle) {
        val report = newestBenchmarkReport(Outputs.outputDirectory)
        if (report == null) {
            results.putString(KEY_STATUS, "missing")
            return
        }
        val raw = report.readBytes()
        require(raw.size in 1..MAX_RAW_BYTES)
        val encoded = Base64.encodeToString(gzip(raw), Base64.NO_WRAP)
        require(encoded.length <= MAX_ENCODED_CHARS)
        val chunks = encoded.chunked(CHUNK_CHARS)
        results.putString(KEY_STATUS, "ok")
        results.putString(KEY_ENCODING, "gzip+base64")
        results.putString(KEY_SHA256, raw.sha256())
        results.putString(KEY_SOURCE_NAME, report.name)
        results.putString(KEY_API_LEVEL, Build.VERSION.SDK_INT.toString())
        results.putString(KEY_ABIS, Build.SUPPORTED_ABIS.joinToString(","))
        results.putString(KEY_RAW_BYTES, raw.size.toString())
        results.putString(KEY_CHUNK_COUNT, chunks.size.toString())
        chunks.forEachIndexed { index, chunk ->
            results.putString("$KEY_CHUNK_PREFIX${index.toString().padStart(CHUNK_INDEX_WIDTH, '0')}", chunk)
        }
    }

    private fun newestBenchmarkReport(directory: File): File? = directory
        .walkTopDown()
        .filter(File::isFile)
        .filter { it.name.endsWith("-benchmarkData.json") }
        .maxByOrNull(File::lastModified)

    private fun gzip(value: ByteArray): ByteArray = ByteArrayOutputStream().use { bytes ->
        GZIPOutputStream(bytes).use { it.write(value) }
        bytes.toByteArray()
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MAX_RAW_BYTES = 8 * 1024 * 1024
        const val MAX_ENCODED_CHARS = 768 * 1024
        const val CHUNK_CHARS = 6 * 1024
        const val CHUNK_INDEX_WIDTH = 4
        const val KEY_STATUS = "p37BenchmarkDataStatus"
        const val KEY_ENCODING = "p37BenchmarkDataEncoding"
        const val KEY_SHA256 = "p37BenchmarkDataSha256"
        const val KEY_SOURCE_NAME = "p37BenchmarkDataSourceName"
        const val KEY_API_LEVEL = "p37BenchmarkApiLevel"
        const val KEY_ABIS = "p37BenchmarkAbis"
        const val KEY_RAW_BYTES = "p37BenchmarkDataRawBytes"
        const val KEY_CHUNK_COUNT = "p37BenchmarkDataChunkCount"
        const val KEY_CHUNK_PREFIX = "p37BenchmarkDataChunk"
    }
}
