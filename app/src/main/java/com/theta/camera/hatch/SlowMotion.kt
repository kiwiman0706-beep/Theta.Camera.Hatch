package com.theta.camera.hatch

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.max

/**
 * スローモーション（高速度撮影）を Camera2 で直接撮る。
 *
 * CameraX の高速度撮影は端末の高速度用プロファイル（media_profiles の highspeed）を前提にしていて、
 * Fold5 など Samsung 端末にはそれが無いため使えない。カメラ自体は 120／240fps の
 * constrained high-speed セッションに対応しているので、そちらで撮り、MediaRecorder で
 * 「撮影 fps → 再生 30fps」に引き伸ばして保存する（どのプレーヤーでもスローで再生される）。
 */
class SlowMotion(
    private val context: Context,
    val cameraId: String,
    val fps: Int,
    private val view: TextureView,
    private val displayRotation: () -> Int,
    private val onError: (String) -> Unit,
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val chars = manager.getCameraCharacteristics(cameraId)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("slowmo").apply { start() }
    private val bg = Handler(thread.looper)
    val size: Size = pickSize(chars, fps) ?: throw IllegalStateException("このレンズは ${fps}fps のスローモーションに非対応です")
    private val range = Range(fps, fps)
    private val recorderSurface: Surface = MediaCodec.createPersistentInputSurface()
    private var device: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outUri: Uri? = null
    private var closed = false
    /** プレビューが流れ始めたら（録画できる状態になったら）呼ぶ。メインスレッド */
    var onReady: (() -> Unit)? = null
    var ready = false; private set
    var recording = false; private set
    var startedAt = 0L; private set

    init {
        // 永続サーフェスは一度 prepare して有効にしておく（セッションに最初から入れるため）
        val tmp = java.io.File(context.cacheDir, "slowmo-prepare.mp4")
        newRecorder().apply { setOutputFile(tmp.absolutePath); prepare(); release() }
        tmp.delete()
    }

    /** プレビューを始める。TextureView の準備ができていなければ、できたときに始める */
    fun open() {
        if (view.isAvailable) openCamera()
        else view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { applyTransform() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (closed) return
        val st = view.surfaceTexture ?: return
        st.setDefaultBufferSize(size.width, size.height)
        main.post { applyTransform() }
        val ps = Surface(st); previewSurface = ps
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (closed) { d.close(); return }
                    device = d
                    try {
                        val cb = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (closed) { s.close(); return }
                                session = s as CameraConstrainedHighSpeedCaptureSession
                                try { repeat(false) } catch (e: Exception) { fail("スローモーションのプレビューに失敗: ${e.message}"); return }
                                main.post { if (!closed) { ready = true; onReady?.invoke() } }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { fail("スローモーションの接続に失敗しました") }
                        }
                        // セッションのパラメータを最初から録画用にしておく（途中で組み直させない）
                        val cfg = android.hardware.camera2.params.SessionConfiguration(
                            android.hardware.camera2.params.SessionConfiguration.SESSION_HIGH_SPEED,
                            listOf(android.hardware.camera2.params.OutputConfiguration(ps), android.hardware.camera2.params.OutputConfiguration(recorderSurface)),
                            java.util.concurrent.Executor { bg.post(it) }, cb)
                        cfg.sessionParameters = request(d, false).build()
                        d.createCaptureSession(cfg)
                    } catch (e: Exception) { fail("スローモーションの接続に失敗: ${e.message}") }
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) { d.close(); device = null; fail("カメラエラー（スローモーション）$error") }
            }, bg)
        } catch (e: Exception) { fail("カメラを開けません: ${e.message}") }
    }

    /** プレビューだけ、または録画も含めた繰り返し要求を流す */
    private fun repeat(withRecorder: Boolean) {
        val s = session ?: return; val d = device ?: return
        s.setRepeatingBurst(s.createHighSpeedRequestList(request(d, withRecorder).build()), null, bg)
    }

    /** 高速度撮影の要求。プレビューだけのときも録画用のテンプレートにそろえる（端末によっては PREVIEW だと HAL が失敗する） */
    private fun request(d: CameraDevice, withRecorder: Boolean): CaptureRequest.Builder =
        d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            previewSurface?.let { addTarget(it) }; if (withRecorder) addTarget(recorderSurface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

    private fun newRecorder(): MediaRecorder {
        @Suppress("DEPRECATION")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return r.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(size.width, size.height)
            // 撮影は fps、再生は 30fps：fps/30 倍のスローになる
            setCaptureRate(fps.toDouble())
            setVideoFrameRate(PLAYBACK_FPS)
            setVideoEncodingBitRate(bitrate(size, fps))
            setOrientationHint(orientationHint())
            setInputSurface(recorderSurface)
        }
    }

    /** 録画を始める。保存先は MediaStore（RELATIVE_PATH） */
    fun start(name: String, relativePath: String) {
        check(!recording) { "録画中です" }
        if (session == null || !ready) throw IllegalStateException("スローモーションの準備ができていません")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("保存先を作れません")
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("保存先を開けません")
            val r = newRecorder().apply { setOutputFile(fd.fileDescriptor); prepare() }
            pfd = fd; outUri = uri; recorder = r
            r.start()
            repeat(true)
            recording = true; startedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            runCatching { recorder?.release() }; recorder = null; runCatching { pfd?.close() }; pfd = null
            context.contentResolver.delete(uri, null, null); outUri = null
            throw e
        }
    }

    /** 録画を止めて保存する。保存できたらその Uri を返す */
    fun stop(): Uri? {
        if (!recording) return null
        recording = false
        runCatching { repeat(false) }
        val uri = outUri; outUri = null
        val ok = runCatching { recorder?.stop() }.isSuccess
        runCatching { recorder?.release() }; recorder = null
        runCatching { pfd?.close() }; pfd = null
        if (uri == null) return null
        if (!ok) { context.contentResolver.delete(uri, null, null); throw IllegalStateException("録画が短すぎるか、保存に失敗しました") }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    fun close() {
        if (closed) return
        closed = true
        if (recording) runCatching { stop() }
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { previewSurface?.release() }; previewSurface = null
        runCatching { recorderSurface.release() }
        view.surfaceTextureListener = null
        thread.quitSafely()
    }

    private fun fail(msg: String) { main.post { if (!closed) onError(msg) } }

    /** 画面いっぱいに映す（はみ出す分は切る）。画面の回転にも合わせる */
    fun applyTransform() {
        val vw = view.width.toFloat(); val vh = view.height.toFloat(); if (vw == 0f || vh == 0f) return
        val rot = displayRotation()
        val m = Matrix()
        val viewRect = RectF(0f, 0f, vw, vh); val cx = viewRect.centerX(); val cy = viewRect.centerY()
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            val bufRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat()).apply { offset(cx - centerX(), cy - centerY()) }
            m.setRectToRect(viewRect, bufRect, Matrix.ScaleToFit.FILL)
            val s = max(vh / size.height, vw / size.width)
            m.postScale(s, s, cx, cy); m.postRotate(90f * (rot - 2), cx, cy)
        } else {
            // 縦：バッファ（横長）は向きを直されて縦長で伸ばされるので、縦横比を戻して切り抜く
            val bw = size.height.toFloat(); val bh = size.width.toFloat()
            val s = max(vw / bw, vh / bh)
            m.setScale(bw * s / vw, bh * s / vh, cx, cy)
            if (rot == Surface.ROTATION_180) m.postRotate(180f, cx, cy)
        }
        view.setTransform(m)
    }

    private fun orientationHint(): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val deg = when (displayRotation()) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) (sensor + deg) % 360 else (sensor - deg + 360) % 360
    }

    companion object {
        const val PLAYBACK_FPS = 30

        /** その fps に対応する高速度撮影のサイズ（1080p を優先し、無ければ大きいもの） */
        fun pickSize(chars: CameraCharacteristics, fps: Int): Size? = runCatching {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val sizes = map.highSpeedVideoSizes.filter { s -> map.getHighSpeedVideoFpsRangesFor(s).any { it.lower == fps && it.upper == fps } }
            sizes.firstOrNull { it.width == 1920 && it.height == 1080 } ?: sizes.maxByOrNull { it.width * it.height }
        }.getOrNull()

        /** 1 秒に撮るコマ数に合わせたビットレート（1080p・120fps で約 30Mbps、上限 80Mbps） */
        fun bitrate(size: Size, fps: Int): Int = (size.width.toLong() * size.height * fps / 8).coerceIn(8_000_000, 80_000_000).toInt()

        /** 対応している fps（120・240 など） */
        fun supportedFps(chars: CameraCharacteristics): List<Int> = runCatching {
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.highSpeedVideoFpsRanges
                ?.filter { it.lower == it.upper && it.upper >= 120 }?.map { it.upper }?.distinct()?.sorted()
        }.getOrNull() ?: emptyList()
    }
}
