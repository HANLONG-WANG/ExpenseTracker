package com.yohaku.expensetracker.ui.features.scan

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.yohaku.expensetracker.BuildConfig
import com.yohaku.expensetracker.receipt.scan.*
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class ScanCameraFrame(
    val outline: List<ScanPoint>, // Normalized PreviewView coordinates, after CameraX transform.
    val hint: ScanHint,
    val timestampMs: Long,
    val fps: Double,
    val actualEps: Map<String, String>,
    val failedFrames: Int = 0,
)
internal enum class CameraIssue { NO_CAMERA, FAILED, ANALYSIS }
internal interface ScanCameraListener {
    fun ready(hasTorch: Boolean, zoom2xAvailable: Boolean)
    fun idle()
    fun frame(frame: ScanCameraFrame)
    fun failed(issue: CameraIssue, cause: Exception? = null)
}

/** Injectable preview/frame source AND shutter. Callbacks and lifecycle operations use main thread.
 * close must release only this source's use cases; pending capture must eventually call its callback.
 */
internal interface ScanCameraSource : AutoCloseable {
    val view: View
    fun start(owner: LifecycleOwner, listener: ScanCameraListener)
    fun capture(file: File, result: (Result<Unit>) -> Unit)
    fun torch(enabled: Boolean)
    fun setZoomRatio(ratio: Float, onApplied: () -> Unit)
}

/** Created/used/closed only on the source's analysis executor; shared with static-frame tests. */
internal class ScanFrameProcessor(context: Context) : AutoCloseable {
    private val scanner = ReceiptScanner(context)
    private val smoother = ContourSmoother()
    private var lastFrame = 0L
    fun resetContour() = smoother.reset()
    fun analyze(bgr: Mat, timestampMs: Long): Pair<ScanPreview, SmoothedContour> {
        if (timestampMs - lastFrame > ScanThresholds.MAX_FRAME_GAP_MS) smoother.reset()
        lastFrame = timestampMs
        val result = scanner.preview(bgr)
        val began = System.nanoTime()
        val smooth = smoother.update(result.outline.map { ScanPoint(it.x / bgr.cols(), it.y / bgr.rows()) })
        if (BuildConfig.DEBUG) Log.d("ScanCamera", "preview ms=${result.diagnostics.timingsMs}, smoothing=${(System.nanoTime()-began)/1e6}, EP=${result.diagnostics.actualEps}")
        return result to smooth
    }
    override fun close() = scanner.close()
}

@androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
internal class CameraXScanSource(private val context: Context) : ScanCameraSource {
    override val view = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    private val main = ContextCompat.getMainExecutor(context)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "receipt-preview") }
    private val closed = AtomicBoolean(false)
    private var processor: ScanFrameProcessor? = null // Worker only, including constructor and close.
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var camera: Camera? = null
    private var listener: ScanCameraListener? = null
    private lateinit var lifecycle: Lifecycle
    @Volatile private var zoomGeneration = 0 // Main thread writes, analyzer and delivery both check.
    private val streamObserver = Observer<PreviewView.StreamState> { state ->
        if (state == PreviewView.StreamState.STREAMING) {
            val info = checkNotNull(camera).cameraInfo
            val supports2x = (info.zoomState.value?.maxZoomRatio ?: 1f) >= 2f
            val generation = zoomGeneration
            listener?.ready(info.hasFlashUnit(), supports2x)
            // A zoom requested by ready() starts its analyzer when the ratio takes effect.
            if (generation == zoomGeneration) startAnalysis(generation)
        } else {
            zoomGeneration++
            listener?.idle()
        }
    }
    private var lastDelivered = 0L
    private fun failureCounter() = ScanFrameFailures { count, error ->
        Log.w("ScanCamera", "Dropped analysis frame; total failures=$count", error)
    }
    private val frameFailures = failureCounter()
    private val mappingFailures = failureCounter()
    private val cameraStateObserver = Observer<CameraState> { state ->
        state.error?.let { error ->
            if (!closed.get()) fail(IllegalStateException("Camera error ${error.code}", error.cause))
        }
    }

    override fun start(owner: LifecycleOwner, listener: ScanCameraListener) {
        this.listener = listener
        lifecycle = owner.lifecycle
        view.doOnLayout {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (closed.get()) return@addListener
                try {
                    val p = future.get(); provider = p
                    if (!p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        listener.failed(CameraIssue.NO_CAMERA); return@addListener
                    }
                    val rotation = view.display.rotation
                    val ratio = AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_NONE)
                    val resolution = ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(Size(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                    val a = ImageAnalysis.Builder().setResolutionSelector(resolution).setTargetRotation(rotation)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val pv = Preview.Builder().setResolutionSelector(resolution).setTargetRotation(rotation).build()
                    // Highest supported 4:3 still resolution, independently of analysis resolution.
                    val shot = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetRotation(rotation).setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
                            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()).build()
                    analysis = a; preview = pv; capture = shot
                    pv.setSurfaceProvider(view.surfaceProvider)
                    val group = UseCaseGroup.Builder().setViewPort(checkNotNull(view.viewPort))
                        .addUseCase(pv).addUseCase(a).addUseCase(shot).build()
                    camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                    // The PreviewView metering factory is valid once its preview is streaming.
                    // Observe IDLE even when the lifecycle stops and CameraX resets its controls.
                    view.previewStreamState.observeForever(streamObserver)
                    checkNotNull(camera).cameraInfo.cameraState.observe(owner, cameraStateObserver)
                } catch (e: Exception) { fail(e) }
            }, main)
        }
    }

    private fun startAnalysis(generation: Int) {
        analysis?.setAnalyzer(worker) { image -> analyze(image, generation) }
    }

    private fun analyze(image: ImageProxy, generation: Int) {
        val began = SystemClock.elapsedRealtime()
        try {
            if (closed.get() || generation != zoomGeneration) return
            val engine = processor ?: ScanFrameProcessor(context).also { processor = it }
            frameFailures.dropping(began) {
                val bgr = rgbaToUpright(image)
                try {
                    val source = ImageProxyTransformFactory().apply { isUsingCropRect = true; isUsingRotationDegrees = true }.getOutputTransform(image)
                    val (result, smooth) = engine.analyze(bgr, began)
                    val hint = scanHint(result, bgr.cols(), bgr.rows(), smooth.stable)
                    // Integer OpenCV pixel centers -> CameraX edge coordinates (+0.5), as ReceiptBench.
                    val points = smooth.points.flatMap { listOf((it.x * bgr.cols() + .5).toFloat(), (it.y * bgr.rows() + .5).toFloat()) }.toFloatArray()
                    main.execute {
                        if (closed.get() || generation != zoomGeneration) return@execute
                        try {
                            mappingFailures.dropping(SystemClock.elapsedRealtime()) {
                                val target = view.outputTransform ?: return@dropping
                                CoordinateTransform(source, target).mapPoints(points)
                                val now = SystemClock.elapsedRealtime()
                                val fps = if (lastDelivered == 0L) 0.0 else 1000.0 / (now - lastDelivered).coerceAtLeast(1)
                                lastDelivered = now
                                if (BuildConfig.DEBUG) Log.d("ScanCamera", "fps=$fps arrival-to-overlay=${now-began}ms")
                                listener?.frame(ScanCameraFrame(points.asList().chunked(2).map { ScanPoint(it[0].toDouble()/view.width, it[1].toDouble()/view.height) }, hint, began, fps, result.diagnostics.actualEps, frameFailures.count + mappingFailures.count))
                            }
                        } catch (e: Exception) { fail(e, CameraIssue.ANALYSIS) }
                    }
                } finally { bgr.release() }
            }
        } catch (e: Exception) {
            main.execute { if (!closed.get() && generation == zoomGeneration) fail(e, CameraIssue.ANALYSIS) }
        } finally { image.close() }
    }

    private fun fail(e: Exception, issue: CameraIssue = CameraIssue.FAILED) {
        Log.e("ScanCamera", "Camera failed", e)
        zoomGeneration++
        view.previewStreamState.removeObserver(streamObserver)
        analysis?.clearAnalyzer()
        listener?.failed(issue, e)
    }

    override fun capture(file: File, result: (Result<Unit>) -> Unit) {
        val shot = capture
        if (closed.get() || shot == null) { result(Result.failure(IllegalStateException("Camera is not ready"))); return }
        shot.targetRotation = view.display.rotation
        // CameraX file output writes EXIF orientation for targetRotation; the engine applies it at decode.
        shot.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = result(Result.success(Unit))
            override fun onError(exception: ImageCaptureException) = result(Result.failure(exception))
        })
    }
    override fun torch(enabled: Boolean) {
        val future = camera?.cameraControl?.enableTorch(enabled) ?: return
        future.addListener({ try { future.get() } catch (e: Exception) { if (!closed.get()) fail(e) } }, main)
    }
    private fun currentZoom(generation: Int) = !closed.get() && generation == zoomGeneration &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun setZoomRatio(ratio: Float, onApplied: () -> Unit) {
        val control = checkNotNull(camera).cameraControl
        val generation = ++zoomGeneration
        analysis?.clearAnalyzer() // Also discard CameraX's cached KEEP_ONLY_LATEST image.
        worker.execute { processor?.resetContour() }
        val zoom = control.setZoomRatio(ratio)
        zoom.addListener({
            if (!currentZoom(generation)) return@addListener
            try {
                zoom.get()
                val center = view.meteringPointFactory.createPoint(view.width / 2f, view.height / 2f)
                val focus = control.startFocusAndMetering(FocusMeteringAction.Builder(center).build())
                focus.addListener({
                    if (currentZoom(generation)) {
                        // Best effort: the camera keeps its continuous AF when this one-shot focus fails.
                        try { focus.get() } catch (e: Exception) { Log.w("ScanCamera", "Center focus after zoom failed", e) }
                    }
                }, main)
                startAnalysis(generation)
                onApplied()
            } catch (e: Exception) { fail(e) }
        }, main)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        analysis?.clearAnalyzer()
        view.previewStreamState.removeObserver(streamObserver)
        camera?.cameraInfo?.cameraState?.removeObserver(cameraStateObserver)
        camera?.cameraControl?.enableTorch(false)
        val cases = listOfNotNull(analysis, preview, capture).toTypedArray()
        if (cases.isNotEmpty()) provider?.unbind(*cases)
        listener = null
        worker.execute { try { processor?.close() } finally { processor = null } }
        worker.shutdown() // Queued close follows the in-flight frame, never blocks the UI thread.
    }
}

internal fun rgbaToUpright(image: ImageProxy): Mat {
    val plane = image.planes[0]; val buffer = plane.buffer.duplicate(); val crop = image.cropRect
    check(plane.pixelStride == 4)
    val rgba = Mat(crop.height(), crop.width(), CvType.CV_8UC4); val bgr = Mat(); val upright = Mat()
    try {
        val row = ByteArray(crop.width() * 4); val base = buffer.position()
        for (y in 0 until crop.height()) {
            buffer.position(base + (y + crop.top) * plane.rowStride + crop.left * 4); buffer.get(row); rgba.put(y, 0, row)
        }
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        when (image.imageInfo.rotationDegrees) {
            0 -> bgr.copyTo(upright)
            90 -> Core.rotate(bgr, upright, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(bgr, upright, Core.ROTATE_180)
            270 -> Core.rotate(bgr, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> error("Unexpected camera rotation")
        }
        return upright
    } catch (e: Exception) { upright.release(); throw e }
    finally { rgba.release(); bgr.release() }
}
