package com.yohaku.expensetracker.receipt.scan

import ai.onnxruntime.*
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Lazy, single-worker sessions. Only the background worker compiles QNN contexts. */
internal class ScannerModels(private val context: Context) : AutoCloseable {
    enum class Ep { CPU, QNN_GPU, QNN_HTP_FP16 }
    enum class Model(val file: String, val sha256: String, val bytes: Long) {
        // Generated from the packaged, unmodified float models; see tools/receipt-scanner/README.md.
        ENCODER("edge_sam_3x_encoder.onnx", "719a498cf5b3fe9be9f01ee513e13d3915f9028aa4f23dfd30eaaa0a17143159", 22098300L),
        DECODER("edge_sam_3x_decoder.onnx", "83a2174d54571596913dcb7455d021e713623c3dca30a31c8c41ab98c9fb0863", 15937006L),
        SYNTHETIC("syntheticdoc_31x45.onnx", "c70caa2c08ef54c1c8329d283b3c25143449522b89560c3afaf495748a5bdb7a", 31595706L)
    }
    private data class Loaded(val session: OrtSession, val ep: Ep, var actual: String? = null)
    private val env = OrtEnvironment.getEnvironment()
    private val sessions = linkedMapOf<Pair<Model, Ep>, Loaded>()
    private val blocked = hashSetOf<Pair<Model, Ep>>()
    private val notices = hashSetOf<String>()
    private val used = linkedMapOf<String, String>()
    private val root = File(context.noBackupFilesDir, "receipt-scanner/contexts")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    // The debug CPU AAR has no QNN provider: do not attempt addQnn and throw on every frame.
    private val qnnAvailable = OrtEnvironment.getAvailableProviders().any { it.name == "QNN" }

    fun beginCall() { used.clear() }
    fun actualEps(): Map<String, String> = used.toMap()
    private fun warnOnce(key: String, reason: String) { if (notices.add(key)) Log.w(TAG, reason) }
    private fun backend(ep: Ep) = File(nativeDir, if (ep == Ep.QNN_GPU) "libQnnGpu.so" else "libQnnHtp.so")
    private fun supported(ep: Ep) = qnnAvailable && backend(ep).isFile
    private fun directory(model: Model, ep: Ep): File {
        val key = sha256(("yohaku-scan-v1|${model.sha256}|${env.version}|qnn2.42.0|${Build.FINGERPRINT}|${Build.SUPPORTED_ABIS.joinToString()}|$ep|burst|fp16|mode3|v75|static4").toByteArray())
        return File(root, "${model.name}-$ep/$key")
    }
    private fun lock(model: Model, ep: Ep) = locks.getOrPut("${root.absolutePath}/${model.name}/$ep") { ReentrantLock() }
    private fun ready(dir: File): Boolean {
        val marker = File(dir, "ready.json")
        if (!marker.isFile || !File(dir, "context.onnx").isFile) return false
        return try {
            val files = JSONObject(marker.readText())
            files.has("context.onnx") && files.keys().asSequence().all { File(dir, it).length() == files.getLong(it) && files.getLong(it) > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid context manifest: $dir", e); false
        }
    }
    private fun modelFile(model: Model): File = copyLock.withLock {
        val target = File(context.filesDir, "receipt-scanner/models/${model.file}")
        // Hash also detects a model update whose weights changed without changing file length.
        if (target.length() == model.bytes && fileHash(target) == model.sha256) return@withLock target
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val tmp = File(target.parentFile, "${model.file}.tmp")
        try {
            context.assets.open("receipt-scanner/${model.file}").use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out); out.fd.sync() }
            }
            check(tmp.length() == model.bytes && fileHash(tmp) == model.sha256) { "Model integrity failed: ${model.file}" }
            check(tmp.renameTo(target)) { "Cannot publish ${model.file}" }
        } finally { tmp.delete() }
        target
    }
    private fun create(model: Model, ep: Ep, path: File, dir: File? = null, generate: Boolean = false): Loaded {
        OrtSession.SessionOptions().use { options ->
            options.setInterOpNumThreads(1); options.setIntraOpNumThreads(6)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            if (model == Model.DECODER) options.setSymbolicDimensionValue("num_points", 4)
            if (ep != Ep.CPU) {
                val provider = linkedMapOf("backend_path" to backend(ep).absolutePath)
                if (ep == Ep.QNN_HTP_FP16) provider.putAll(mapOf("htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1", "htp_graph_finalization_optimization_mode" to "3", "htp_arch" to "75"))
                options.addQnn(provider)
                options.addConfigEntry("session.disable_cpu_ep_fallback", "0")
                options.addConfigEntry("ep.context_enable", if (generate) "1" else "0")
                options.addConfigEntry("ep.context_embed_mode", "0")
                options.addConfigEntry("ep.context_file_path", File(requireNotNull(dir), "context.onnx").absolutePath)
                if (!generate) {
                    val profiles = File(context.cacheDir, "receipt-scanner/profiles").also { check(it.isDirectory || it.mkdirs()) }
                    options.enableProfiling(File(profiles, "${model.name}-${System.nanoTime()}").absolutePath)
                }
            }
            return Loaded(env.createSession(path.absolutePath, options), ep, if (ep == Ep.CPU) "CPU" else null)
        }
    }
    private fun load(model: Model, ep: Ep): Loaded? {
        val key = model to ep
        if (key in blocked) return null
        sessions[key]?.let { return it }
        if (ep == Ep.CPU) return create(model, ep, modelFile(model)).also { sessions[key] = it }
        if (!supported(ep)) {
            warnOnce("unavailable-$ep", "$ep unavailable in this ORT build/device; using the next provider")
            blocked.add(key); return null
        }
        val mutex = lock(model, ep)
        if (!mutex.tryLock()) { warnOnce("compiling-$key", "$model/$ep is warming up; using the next provider"); return null }
        try {
            val dir = directory(model, ep)
            if (!ready(dir)) { warnOnce("pending-$key", "$model/$ep context missing or invalid; using the next provider"); return null }
            return try { create(model, ep, File(dir, "context.onnx"), dir).also { sessions[key] = it } }
            catch (error: Exception) {
                Log.w(TAG, "$model/$ep context load failed; invalidating and falling back", error)
                invalidate(dir); blocked.add(key); ScannerWarmupWorker.schedule(context); null
            }
        } finally { mutex.unlock() }
    }
    fun run(model: Model, preview: Boolean, feed: Map<String, OnnxTensor>): OrtSession.Result {
        val order = when {
            model == Model.DECODER -> listOf(Ep.CPU)
            model == Model.ENCODER && preview -> listOf(Ep.QNN_HTP_FP16, Ep.QNN_GPU, Ep.CPU)
            else -> listOf(Ep.QNN_GPU, Ep.CPU)
        }
        for (ep in order) {
            val loaded = load(model, ep) ?: continue
            try {
                val result = loaded.session.run(feed)
                try {
                    if (loaded.actual == null) loaded.actual = audit(loaded)
                    check(ep == Ep.CPU || loaded.actual != "CPU") { "$model/$ep executed no QNN nodes" }
                    used[model.name.lowercase()] = requireNotNull(loaded.actual)
                    return result
                } catch (e: Exception) { result.close(); throw e }
            } catch (error: Exception) {
                if (ep == Ep.CPU) throw error
                Log.w(TAG, "$model/$ep execution failed; falling back", error)
                sessions.remove(model to ep)?.session?.close(); blocked.add(model to ep)
                lock(model, ep).withLock { invalidate(directory(model, ep)) }
                ScannerWarmupWorker.schedule(context)
            }
        }
        error("CPU provider did not load")
    }
    /** Observe actual node execution once; registration alone is not proof that QNN ran. */
    private fun audit(loaded: Loaded): String {
        val profile = File(loaded.session.endProfiling())
        try {
            val events = JSONArray(profile.readText()); val providers = hashSetOf<String>()
            for (i in 0 until events.length()) events.getJSONObject(i).optJSONObject("args")?.optString("provider")?.takeIf { it.isNotEmpty() }?.let { providers.add(it) }
            check(providers.isNotEmpty()) { "ORT profiling exposed no execution providers" }
            val qnn = "QNNExecutionProvider" in providers; val cpu = "CPUExecutionProvider" in providers
            check(qnn || cpu) { "Unknown execution providers: $providers" }
            return if (qnn) loaded.ep.name + if (cpu) "+CPU" else "" else "CPU"
        } finally { profile.delete() }
    }
    /** Called by one low-priority worker, never by preview/detect/render. */
    fun warmup(cancelled: () -> Boolean) {
        for ((model, ep) in listOf(Model.ENCODER to Ep.QNN_HTP_FP16, Model.ENCODER to Ep.QNN_GPU, Model.SYNTHETIC to Ep.QNN_GPU)) {
            if (cancelled()) return
            if (!supported(ep)) { warnOnce("warm-$ep", "$ep unavailable; skipping background compilation"); continue }
            lock(model, ep).withLock {
                val dir = directory(model, ep)
                if (ready(dir)) return@withLock
                // The key also namespaces all external context binaries; remove stale fingerprints together.
                dir.parentFile?.listFiles()?.forEach { invalidate(it) }
                check(dir.mkdirs()) { "Cannot create context directory $dir" }
                try {
                    create(model, ep, modelFile(model), dir, generate = true).session.use { }
                    check(File(dir, "context.onnx").isFile) { "ORT generated no EP context for $model/$ep" }
                    val files = JSONObject()
                    dir.listFiles()!!.filter { it.isFile }.forEach { files.put(it.name, it.length()) }
                    val tmp = File(dir, "ready.tmp")
                    FileOutputStream(tmp).use { out -> out.write(files.toString().toByteArray()); out.fd.sync() }
                    check(tmp.renameTo(File(dir, "ready.json")))
                } catch (error: Exception) {
                    Log.w(TAG, "$model/$ep warmup failed; foreground will use the next provider", error)
                    invalidate(dir)
                }
            }
        }
    }
    override fun close() {
        var failure: Exception? = null
        for (loaded in sessions.values) try { loaded.session.close() } catch (e: Exception) { if (failure == null) failure = e else failure.addSuppressed(e) }
        sessions.clear(); failure?.let { throw it }
    }
    companion object {
        private const val TAG = "ReceiptScanner"
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        private val copyLock = ReentrantLock()
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun fileHash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun invalidate(dir: File) { check(!dir.exists() || dir.deleteRecursively()) { "Cannot remove context $dir" } }
        fun tensor(values: FloatArray, vararg shape: Long): OnnxTensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(values), shape)
        fun floats(value: OnnxValue): FloatArray {
            val b = (value as OnnxTensor).floatBuffer
            return FloatArray(b.remaining()).also { b.get(it) }
        }
    }
}
