@file:OptIn(androidx.window.core.ExperimentalWindowApi::class)
package com.theta.camera.hatch

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.*
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.core.resolutionselector.*
import androidx.camera.extensions.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.area.*
import androidx.window.layout.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.*

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class MainActivity : ComponentActivity(), SensorEventListener {
    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }
    private val main by lazy { ContextCompat.getMainExecutor(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var photoBusy = false
    private var countDown: CountDownTimer? = null
    private var selectedId: String? = null
    private var facing = CameraSelector.LENS_FACING_BACK
    private var video = false
    private var extensionMode = ExtensionMode.NONE
    private var highResolution = false
    private var wideAspect = false
    private var ultraHdr = false
    private var rawJpeg = false
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var torch = false
    private var timerSeconds = 0
    private var quality = Quality.FHD
    private var videoHdr = false
    private var sixtyFps = false
    private var audioEnabled = true
    private var stabilization = true
    private var manualIso: Int? = null
    private var manualTime: Long? = null
    private var focusDistance: Float? = null
    private var wb = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var lastUri: Uri? = null
    private var lastVideo = false
    private var lastError = "なし"
    private var orientationListener: OrientationEventListener? = null
    private lateinit var preview: PreviewView
    private lateinit var overlay: GuideView
    private lateinit var root: LinearLayout
    private lateinit var previewColumn: LinearLayout
    private lateinit var controlColumn: LinearLayout
    private lateinit var controlScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var rearButton: Button
    private lateinit var shutter: Button
    private lateinit var modeButton: Button
    private lateinit var zoomSeek: SeekBar
    private lateinit var exposureSeek: SeekBar
    private val configButtons = mutableListOf<Button>()
    private val foldTrigger = FoldTrigger()
    private var autoRear = true
    private var pendingAutoRear = false
    private var pendingRearAt = 0L
    private var rearRequested = false
    private var rearSession: WindowAreaSession? = null
    private var areaInfo: WindowAreaInfo? = null
    private var areaStatus = WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
    private var areaController: WindowAreaController? = null
    private val operation = WindowAreaCapability.Operation.OPERATION_TRANSFER_ACTIVITY_TO_AREA
    private val sensors by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private var hinge: Sensor? = null
    private var lastAngle: Float? = null
    private val rearCheck = Runnable { tryAutoRear() }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasCamera()) startProvider() else {
            status.text = "カメラの許可が必要です"
            message("設定でカメラを許可すると撮影できます。診断は許可なしでも開けます。")
        }
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message("マイク未許可のため、動画は無音で保存します")
        if (video && recording == null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startRecording()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        autoRear = prefs.getBoolean("autoRear", true)
        highResolution = prefs.getBoolean("highResolution", false)
        timerSeconds = prefs.getInt("timer", 0)
        audioEnabled = prefs.getBoolean("audio", true)
        lastUri = prefs.getString("lastUri", null)?.let(Uri::parse)
        lastVideo = prefs.getBoolean("lastVideo", false)
        buildUi()
        setupFold()
        autoCheckUpdate()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN || recording != null) return
                val rotation = when (angle) { in 45..134 -> Surface.ROTATION_270; in 135..224 -> Surface.ROTATION_180; in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                imageCapture?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }
        if (hasCamera()) startProvider() else permissions.launch(arrayOf(Manifest.permission.CAMERA))
    }
    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun message(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    private fun label(s: String, size: Float = 14f) = TextView(this).apply { text=s; textSize=size; setTextColor(Color.WHITE); setPadding(dp(10),dp(4),dp(10),dp(4)) }
    private fun row() = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun button(s: String, config: Boolean = true, action: () -> Unit): Button = Button(this).apply {
        text=s; textSize=13f; isAllCaps=false; minHeight=dp(48); minimumWidth=dp(64)
        setTextColor(Color.rgb(220,243,229)); setPadding(dp(12),dp(4),dp(12),dp(4))
        background=GradientDrawable().apply { setColor(Color.rgb(32,49,41)); cornerRadius=dp(16).toFloat() }
        layoutParams=LinearLayout.LayoutParams(-2,dp(48)).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }
        setOnClickListener { action() }; if(config) configButtons.add(this)
    }
    private fun scrollRow(vararg buttons: View): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled=false; addView(row().apply { buttons.forEach { addView(it) } })
    }
    private fun buildUi() {
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16,23,20)); setPadding(dp(6),dp(2),dp(6),dp(4)) }
        previewColumn=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        controlColumn=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        controlScroll=ScrollView(this).apply {isFillViewport=false;addView(controlColumn)}
        val header=row()
        header.addView(label("THETA  /  CAMERA",17f), LinearLayout.LayoutParams(0,dp(42),1f))
        header.addView(button("設定") { showSettings() }); header.addView(button("診断",false) { showDiagnostics() })
        previewColumn.addView(header)
        rearButton=button("外側画面で自撮り",false) { toggleRear() }
        previewColumn.addView(rearButton,LinearLayout.LayoutParams(-1,dp(52)))
        val stage=FrameLayout(this)
        preview=PreviewView(this).apply { implementationMode=PreviewView.ImplementationMode.COMPATIBLE; scaleType=PreviewView.ScaleType.FIT_CENTER }
        overlay=GuideView(this).apply { grid=prefs.getBoolean("grid",true) }
        stage.addView(preview,FrameLayout.LayoutParams(-1,-1)); stage.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        previewColumn.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        status=label("カメラを準備中",13f); controlColumn.addView(status)
        detail=label("",12f).apply { setTextColor(Color.rgb(150,186,166)) }; controlColumn.addView(detail)
        val zoomRow=row(); zoomRow.addView(label("ズーム"))
        zoomSeek=SeekBar(this).apply { max=1000; setOnSeekBarChangeListener(seekListener { camera?.cameraControl?.setLinearZoom(it/1000f) }) }
        zoomRow.addView(zoomSeek,LinearLayout.LayoutParams(0,dp(32),1f)); controlColumn.addView(zoomRow)
        val evRow=row(); evRow.addView(label("明るさ"))
        exposureSeek=SeekBar(this).apply { setOnSeekBarChangeListener(seekListener { camera?.let { c -> watch(c.cameraControl.setExposureCompensationIndex(it+c.cameraInfo.exposureState.exposureCompensationRange.lower),"露出変更") } }) }
        evRow.addView(exposureSeek,LinearLayout.LayoutParams(0,dp(32),1f)); controlColumn.addView(evRow)
        controlColumn.addView(scrollRow(button("レンズ") { showLenses() },button("0.6×") { zoomTo(.6f) },button("1×") { zoomTo(1f) },button("3×") { zoomTo(3f) },button("前／後") {
            facing=if(facing==CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            selectedId=null; resetManual(); bindCamera()
        }))
        modeButton=button("標準") { showModes() }
        controlColumn.addView(scrollRow(button("写真") { video=false; bindCamera() },button("動画") { video=true; bindCamera() },modeButton,button("フラッシュ") { showFlash() },button("タイマー") { choose("セルフタイマー",listOf("なし","3秒","5秒","10秒")) { timerSeconds=listOf(0,3,5,10)[it]; prefs.edit().putInt("timer",timerSeconds).apply(); refreshInfo() } },button("PRO") { showPro() }))
        val bottom=row()
        bottom.addView(button("直前の写真",false) { openLast() },LinearLayout.LayoutParams(0,dp(62),1f))
        shutter=button("●  撮影",false) { capture() }.apply { textSize=20f; setTextColor(Color.rgb(16,42,28)); background=GradientDrawable().apply { setColor(Color.rgb(142,226,181)); cornerRadius=dp(32).toFloat() } }
        bottom.addView(shutter,LinearLayout.LayoutParams(0,dp(66),1.5f))
        bottom.addView(button("グリッド",false) { overlay.grid=!overlay.grid; prefs.edit().putBoolean("grid",overlay.grid).apply(); overlay.invalidate() },LinearLayout.LayoutParams(0,dp(62),1f))
        controlColumn.addView(bottom); arrangeUi(); setContentView(root)
        val pinch=ScaleGestureDetector(this,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){ override fun onScale(d:ScaleGestureDetector):Boolean { camera?.cameraInfo?.zoomState?.value?.let { zoomTo(it.zoomRatio*d.scaleFactor) }; return true } })
        val gestures=GestureDetector(this,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapUp(e:MotionEvent):Boolean { camera?.let { c ->
                val point=preview.meteringPointFactory.createPoint(e.x,e.y)
                watch(c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).setAutoCancelDuration(5,TimeUnit.SECONDS).build()),"ピント合わせ")
                overlay.focusX=e.x; overlay.focusY=e.y; overlay.invalidate(); handler.postDelayed({overlay.focusX=-1f; overlay.invalidate()},1200)
            }; return true }
        })
        preview.contentDescription="カメラプレビュー。タップでピント、ピンチでズーム"
        preview.setOnTouchListener { v,e -> pinch.onTouchEvent(e); if(!pinch.isInProgress) gestures.onTouchEvent(e); if(e.actionMasked==MotionEvent.ACTION_UP && !pinch.isInProgress) v.performClick(); true }
    }
    private fun arrangeUi() {
        root.removeAllViews()
        val landscape=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        root.orientation=if(landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        root.addView(previewColumn,if(landscape) LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        root.addView(controlScroll,if(landscape) LinearLayout.LayoutParams(dp(320),-1) else LinearLayout.LayoutParams(-1,-2))
    }
    private fun seekListener(block:(Int)->Unit)=object:SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){ if(user) block(p) }
        override fun onStartTrackingTouch(s:SeekBar?){}; override fun onStopTrackingTouch(s:SeekBar?){}
    }
    private fun watch(f:com.google.common.util.concurrent.ListenableFuture<*>, operation:String) {
        f.addListener({ try { f.get() } catch(e:Exception) { val cause=e.cause ?: e; if(cause !is CameraControl.OperationCanceledException && cause !is java.util.concurrent.CancellationException) {lastError="$operation: ${cause.message}"; message(lastError)} } },main)
    }
    private fun startProvider() {
        if (provider!=null) { bindCamera(); return }
        val f=ProcessCameraProvider.getInstance(this)
        f.addListener({ try {
            provider=f.get()
            val x=ExtensionsManager.getInstanceAsync(this,provider!!)
            x.addListener({ try { extensions=x.get() } catch(e:Exception) { lastError="拡張の初期化: ${e.message}" }; bindCamera() },main)
        } catch(e:Exception){ fail("カメラ初期化",e) } },main)
    }
    private fun baseSelector():CameraSelector = selectedId?.let { id -> CameraSelector.Builder().addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId==id } }.build() }
        ?: CameraSelector.Builder().requireLensFacing(facing).build()
    private fun bindCamera(fallback:Boolean=false) {
        val p=provider ?: return
        if(recording!=null || photoBusy || countDown!=null || !hasCamera()) return
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        p.unbindAll(); camera=null; imageCapture=null; videoCapture=null; shutter.isEnabled=false
        try {
            var selector=baseSelector()
            if (!p.hasCamera(selector)) { selectedId=null; facing=CameraSelector.LENS_FACING_BACK; selector=baseSelector() }
            val baseInfo=p.getCameraInfo(selector)
            if(!video && extensionMode!=ExtensionMode.NONE) {
                if(extensions?.isExtensionAvailable(selector,extensionMode)==true) selector=extensions!!.getExtensionEnabledCameraSelector(selector,extensionMode)
                else { extensionMode=ExtensionMode.NONE; message("このレンズでは選択した拡張に非対応です。標準で撮影します") }
            }
            val resolution=ResolutionSelector.Builder().setAspectRatioStrategy(if(wideAspect) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setAllowedResolutionMode(if(highResolution) ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE else ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                .setResolutionStrategy(if(highResolution) ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY else ResolutionStrategy(android.util.Size(4000,3000),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
            val pb=Preview.Builder().setTargetRotation(preview.display?.rotation ?: Surface.ROTATION_0)
            if (!video) pb.setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(if(wideAspect) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            val pv=pb.build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            if(video) {
                val vcaps=Recorder.getVideoCapabilities(baseInfo)
                val dr=if(videoHdr && vcaps.supportedDynamicRanges.contains(DynamicRange.HLG_10_BIT)) DynamicRange.HLG_10_BIT else DynamicRange.SDR
                if(videoHdr && dr==DynamicRange.SDR) { videoHdr=false; message("このレンズではHDR動画に非対応です。SDRに戻しました") }
                val qs=vcaps.getSupportedQualities(dr)
                if(qs.isEmpty()) error("このレンズで動画の対応画質が取得できません")
                if(!qs.contains(quality)) quality=qs.firstOrNull { it==Quality.FHD } ?: qs.first()
                val rec=Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
                val vb=VideoCapture.Builder(rec).setDynamicRange(dr).setTargetRotation(preview.display?.rotation ?: Surface.ROTATION_0)
                if(sixtyFps) vb.setTargetFrameRate(Range(60,60))
                if(stabilization && vcaps.isStabilizationSupported) vb.setVideoStabilizationEnabled(true)
                videoCapture=vb.build(); camera=p.bindToLifecycle(this,selector,pv,videoCapture)
            } else {
                val ib=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(100).setResolutionSelector(resolution).setFlashMode(flash).setTargetRotation(preview.display?.rotation ?: Surface.ROTATION_0)
                val caps=ImageCapture.getImageCaptureCapabilities(p.getCameraInfo(selector))
                if(rawJpeg && caps.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)) ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                else if(rawJpeg) {rawJpeg=false;message("この構成ではRAW+JPEG非対応です。JPEGに戻しました")}
                if(!rawJpeg && ultraHdr && caps.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)) ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                else if(ultraHdr) { ultraHdr=false; message("この構成ではUltra HDR非対応のためJPEGで撮影します") }
                imageCapture=ib.build(); camera=p.bindToLifecycle(this,selector,pv,imageCapture)
            }
            camera?.let { c ->
                c.cameraInfo.zoomState.observe(this) { z -> zoomSeek.progress=(z.linearZoom*1000).toInt(); refreshInfo() }
                c.cameraInfo.cameraState.observe(this) { s -> s.error?.let { lastError="CameraState error ${it.code}: ${it.cause?.message ?: ""}"; status.text="カメラエラー。設定の再接続をお試しください" } }
                val ev=c.cameraInfo.exposureState
                exposureSeek.max=ev.exposureCompensationRange.upper-ev.exposureCompensationRange.lower
                exposureSeek.progress=ev.exposureCompensationIndex-ev.exposureCompensationRange.lower
                exposureSeek.isEnabled=ev.isExposureCompensationSupported
                torch=false
                if(extensionMode==ExtensionMode.NONE || video) applyManual()
            }
            shutter.isEnabled=true; setBusy(false); refreshInfo(); tryAutoRear()
        } catch(e:Exception) {
            lastError="バインド失敗: ${e.message}"
            if(!fallback) {
                extensionMode=ExtensionMode.NONE; highResolution=false; ultraHdr=false; rawJpeg=false; videoHdr=false; sixtyFps=false; stabilization=false; quality=Quality.FHD; selectedId=null; facing=CameraSelector.LENS_FACING_BACK; resetManual()
                message("この撮影設定を利用できないため、標準設定で再接続します")
                bindCamera(true)
            } else { fail("カメラ接続",e); status.text="接続できません。診断を確認してください" }
        }
    }
    private fun fail(where:String,e:Exception) { lastError="$where: ${e.cause?.message ?: e.message}"; message(lastError) }
    private fun refreshInfo() {
        if(!::status.isInitialized) return
        if(recording==null && countDown==null && !photoBusy) status.text=if(video) "動画  ${qualityName(quality)}${if(videoHdr) " · HLG HDR" else ""}${if(sixtyFps) " · 60fps要求" else ""}" else "写真  ${modeName(extensionMode)}${if(rawJpeg) " · RAW+JPEG" else if(ultraHdr) " · Ultra HDR" else ""}"
        val res=imageCapture?.resolutionInfo?.resolution
        val z=camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        detail.text="${String.format(Locale.US,"%.1f×",z)}  ·  ${res?.let { "${it.width}×${it.height}" } ?: "画角はプレビューで確認"}  ·  ${if(timerSeconds==0) "タイマーなし" else "${timerSeconds}秒"}"
        modeButton.text=if(video) "動画画質" else modeName(extensionMode)
        if(recording==null) shutter.text=if(video) "●  録画" else "●  撮影"
        updateRearUi()
    }
    private fun zoomTo(value:Float) {
        val c=camera ?: return; val z=c.cameraInfo.zoomState.value ?: return
        if(value<z.minZoomRatio) { message("このレンズの最小倍率は${z.minZoomRatio}×です。超広角は「レンズ」から選べる場合があります") }
        watch(c.cameraControl.setZoomRatio(value.coerceIn(z.minZoomRatio,z.maxZoomRatio)),"ズーム")
    }
    private fun setBusy(busy:Boolean) { configButtons.forEach { it.isEnabled=!busy }; rearButton.isEnabled=!busy; zoomSeek.isEnabled=!photoBusy; exposureSeek.isEnabled=!busy && camera?.cameraInfo?.exposureState?.isExposureCompensationSupported==true }
    private fun capture() {
        if(recording!=null) { recording?.stop(); shutter.isEnabled=false; return }
        if(photoBusy) return
        if(countDown!=null) { countDown?.cancel(); countDown=null; setBusy(false); refreshInfo(); tryAutoRear(); return }
        if(!hasCamera()) { permissions.launch(arrayOf(Manifest.permission.CAMERA)); return }
        if(camera==null) { startProvider(); return }
        if(timerSeconds>0) {
            setBusy(true); shutter.text="中止"
            countDown=object:CountDownTimer(timerSeconds*1000L,250){
                override fun onTick(ms:Long){status.text="${ceil(ms/1000.0).toInt()} 秒後に撮影"}
                override fun onFinish(){countDown=null; performCapture()}
            }.start()
        } else performCapture()
    }
    private fun performCapture() {
        if(video) {
            if(audioEnabled && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) microphone.launch(Manifest.permission.RECORD_AUDIO)
            else startRecording()
        } else takePhoto()
    }
    private fun name()="Theta_"+SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(Date())
    private fun takePhoto() {
        val cap=imageCapture ?: run { setBusy(false); return }
        photoBusy=true; setBusy(true); shutter.isEnabled=false; status.text="撮影・保存中…"
        val basename=name()
        fun output(ext:String,mime:String)=ImageCapture.OutputFileOptions.Builder(contentResolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,basename+ext);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/ThetaCamera")
        }).build()
        val pair=cap.outputFormat==ImageCapture.OUTPUT_FORMAT_RAW_JPEG
        var saved=0; var failed=false
        fun finish() {photoBusy=false;shutter.isEnabled=true;setBusy(false);refreshInfo();tryAutoRear()}
        val callback=object:ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                if(result.imageFormat==ImageFormat.JPEG || !pair) saveLast(result.savedUri,false)
                saved++
                if(!failed && saved==(if(pair)2 else 1)) {finish();message(if(pair) "RAWとJPEGを保存しました" else "DCIM/ThetaCamera に保存しました")}
            }
            override fun onError(e:ImageCaptureException) {failed=true;finish();fail("写真保存（一部未保存の可能性）",e)}
        }
        try {
            if(pair) cap.takePicture(output(".dng","image/x-adobe-dng"),output(".jpg","image/jpeg"),main,callback)
            else cap.takePicture(output(".jpg","image/jpeg"),main,callback)
        } catch(e:Exception) {failed=true;finish();fail("写真撮影",e)}
    }

    private fun startRecording() {
        val cap=videoCapture ?: run {setBusy(false); return}
        try {
            val values=ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,name()+".mp4"); put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4"); put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/ThetaCamera") }
            val output=MediaStoreOutputOptions.Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
            var pending=cap.output.prepareRecording(this,output)
            val withAudio=audioEnabled && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
            if(withAudio) pending=pending.withAudioEnabled()
            setBusy(true); shutter.text="■  停止"
            recording=pending.start(main) { event ->
                when(event) {
                    is VideoRecordEvent.Status -> { val sec=event.recordingStats.recordedDurationNanos/1_000_000_000; status.text="● 録画 ${sec/60}:${(sec%60).toString().padStart(2,'0')} ${if(withAudio) "音声あり" else "無音"}" }
                    is VideoRecordEvent.Finalize -> {
                        recording=null; shutter.isEnabled=true; setBusy(false)
                        if(!event.hasError()) {saveLast(event.outputResults.outputUri,true); message("動画を保存しました")}
                        else {lastError="動画終了エラー ${event.error}: ${event.cause?.message}"; message(lastError)}
                        refreshInfo(); tryAutoRear()
                    }
                }
            }
        } catch(e:Exception) {recording=null; setBusy(false); shutter.isEnabled=true; fail("録画開始",e); refreshInfo()}
    }
    private fun saveLast(uri:Uri?,isVideo:Boolean) { if(uri==null || uri==Uri.EMPTY) return; lastUri=uri; lastVideo=isVideo; prefs.edit().putString("lastUri",uri.toString()).putBoolean("lastVideo",isVideo).apply() }
    private fun openLast() { val uri=lastUri ?: run {message("撮影すると、ここから直前の写真・動画を開けます");return}; try {startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,if(lastVideo) "video/*" else "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))} catch(e:Exception){message("表示できません。ギャラリーのThetaCameraフォルダを確認してください")} }
    private fun choose(title:String,items:List<String>,action:(Int)->Unit) { android.app.AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()){_,which->action(which)}.setNegativeButton("閉じる",null).show() }
    private fun modeName(m:Int)=when(m){ExtensionMode.AUTO->"メーカー自動";ExtensionMode.HDR->"HDR";ExtensionMode.NIGHT->"夜景";ExtensionMode.BOKEH->"ポートレート";ExtensionMode.FACE_RETOUCH->"美肌";else->"標準"}
    private fun qualityName(q:Quality)=when(q){Quality.UHD->"4K";Quality.FHD->"1080p";Quality.HD->"720p";Quality.SD->"SD";else->q.toString()}
    private fun showModes() {
        if(video) {showVideoSettings();return}
        val modes=listOf(ExtensionMode.NONE,ExtensionMode.AUTO,ExtensionMode.HDR,ExtensionMode.NIGHT,ExtensionMode.BOKEH,ExtensionMode.FACE_RETOUCH).filter { it==ExtensionMode.NONE || runCatching {extensions?.isExtensionAvailable(baseSelector(),it)==true}.getOrDefault(false) }
        choose("写真モード（このレンズの対応機能）",modes.map(::modeName)) {extensionMode=modes[it]; resetManual(); bindCamera()}
    }
    private fun showLenses() {
        val infos=provider?.availableCameraInfos ?: return
        val titles=infos.map { info -> val c=Camera2CameraInfo.from(info); val f=c.getCameraCharacteristic(CameraCharacteristics.LENS_FACING); val lens=c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(); "${if(f==CameraCharacteristics.LENS_FACING_BACK) "背面" else "前面"}  ID ${c.cameraId}  ${lens ?: "?"} mm" }
        choose("公開されているカメラ／レンズ",titles) { selectedId=Camera2CameraInfo.from(infos[it]).cameraId; facing=if(Camera2CameraInfo.from(infos[it]).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT; resetManual(); bindCamera() }
    }
    private fun showFlash() { choose("フラッシュ／ライト",listOf("OFF","自動（写真）","ON（写真）","ライトON／OFF")) {
        if(it==3) { if(camera?.cameraInfo?.hasFlashUnit()!=true){message("このカメラはライト非対応です");return@choose};torch=!torch;camera?.cameraControl?.enableTorch(torch)?.let { f->watch(f,"ライト") } }
        else {flash=listOf(ImageCapture.FLASH_MODE_OFF,ImageCapture.FLASH_MODE_AUTO,ImageCapture.FLASH_MODE_ON)[it];imageCapture?.flashMode=flash;message(listOf("フラッシュOFF","フラッシュ自動","フラッシュON")[it])}
    } }
    private fun showSettings() {
        val items=listOf("開いたら外側自撮り：${if(autoRear) "ON" else "OFF"}","写真解像度：${if(highResolution) "公開される最大" else "通常"}","写真比率：${if(wideAspect) "16:9" else "4:3"}","Ultra HDR写真：${if(ultraHdr) "ON" else "OFF"}","動画設定", "音声録音：${if(audioEnabled) "ON" else "OFF"}","水平ガイド：${if(overlay.level) "ON" else "OFF"}","カメラを再接続", "純正カメラを開く", "使い方・制限", "RAW+JPEG：${if(rawJpeg) "ON" else "OFF"}", "アップデート（版 ${BuildConfig.VERSION_NAME}）")
        choose("設定",items) { when(it) {
            0->{autoRear=!autoRear; pendingAutoRear=false; prefs.edit().putBoolean("autoRear",autoRear).apply();updateRearUi()}
            1->{highResolution=!highResolution;prefs.edit().putBoolean("highResolution",highResolution).apply();bindCamera()}
            2->{wideAspect=!wideAspect;bindCamera()}
            3->{ultraHdr=!ultraHdr;if(ultraHdr)rawJpeg=false;bindCamera()}
            4->showVideoSettings()
            5->{audioEnabled=!audioEnabled;prefs.edit().putBoolean("audio",audioEnabled).apply()}
            6->{overlay.level=!overlay.level;overlay.invalidate()}
            7->startProvider()
            8->{try {startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))} catch(e:Exception){message("純正カメラを起動できません")}}
            9->android.app.AlertDialog.Builder(this).setTitle("Theta Camera Hatch ${BuildConfig.VERSION_NAME}").setMessage("開いたら自撮り：閉じた状態でこのアプリを開き、ほぼ全開にすると外側画面への切替を要求します。OSの確認が出たら承認してください。\n\n録画・保存中は自動切替を保留します。自動切替しない場合は大きな自撮りボタンを使用してください。\n\n写真と動画はDCIM/ThetaCameraに保存。ピンチでズーム、タップでピント、音量キーで撮影。\n\n50MP・HDR・望遠などはOSの公開範囲に依存します。純正の全機能・画質を保証するものではありません。RAW+JPEGは対応時のみ。連写・8K・スローモーションは未実装です。Fold5実機での検証が必要な初版です。").setPositiveButton("OK",null).show()
            10->{rawJpeg=!rawJpeg;if(rawJpeg){ultraHdr=false;extensionMode=ExtensionMode.NONE};bindCamera()}
            11->showUpdateMenu()
        } }
    }
    private fun showUpdateMenu() {
        val auto=prefs.getBoolean("updateAuto",true)
        val items=listOf("更新を確認","起動時に自動で確認（12時間ごと）：${if(auto) "ON" else "OFF"}","Releases ページを開く")
        choose("アップデート（今の版 ${BuildConfig.VERSION_NAME}）",items) { when(it) {
            0->checkUpdate(manual=true)
            1->prefs.edit().putBoolean("updateAuto",!auto).apply()
            2->HatchUpdate.openReleases(this)
        } }
    }
    private fun autoCheckUpdate() {
        if(!prefs.getBoolean("updateAuto",true)) return
        if(System.currentTimeMillis()-prefs.getLong("updateChecked",0L) < HatchUpdate.CHECK_INTERVAL_MS) return
        checkUpdate(manual=false)
    }
    private fun checkUpdate(manual:Boolean) {
        if(manual) message("更新を確認しています…")
        lifecycleScope.launch {
            val r=withContext(Dispatchers.IO){ runCatching { HatchUpdate.fetchLatest() } }
            if(isFinishing||isDestroyed) return@launch
            val rel=r.getOrElse { e -> if(manual) message("確認できませんでした：${e.message}"); return@launch }
            prefs.edit().putLong("updateChecked",System.currentTimeMillis()).apply()
            if(!HatchUpdate.isNewer(rel.version,BuildConfig.VERSION_NAME)) { if(manual) message("最新です（${BuildConfig.VERSION_NAME}）"); return@launch }
            // 起動時の自動確認では撮影を邪魔しないよう知らせるだけ
            if(!manual) { message("新しい版 ${rel.version} があります（設定 → アップデート）"); return@launch }
            showUpdateDialog(rel)
        }
    }
    private fun showUpdateDialog(rel:HatchUpdate.Release) {
        val notes=rel.notes.trim().take(1500).ifEmpty { "（変更点の記載なし）" }
        val b=android.app.AlertDialog.Builder(this).setTitle("新しい版 ${rel.version}（今 ${BuildConfig.VERSION_NAME}）").setMessage(notes)
        if(HatchUpdate.hasHub(this)) {
            b.setPositiveButton("ハブで更新") {_,_-> if(!HatchUpdate.requestHub(this)) startUpdateDownload(rel) }
            b.setNeutralButton("このアプリで") {_,_-> startUpdateDownload(rel) }
        } else {
            b.setPositiveButton("ダウンロードして入れる") {_,_-> startUpdateDownload(rel) }
            b.setNeutralButton("Releases") {_,_-> HatchUpdate.openReleases(this) }
        }
        b.setNegativeButton("あとで",null).show()
    }
    private fun startUpdateDownload(rel:HatchUpdate.Release) {
        runCatching { HatchUpdate.download(this,rel) {_,msg-> message(msg) } }
            .onSuccess { message("ダウンロードを始めました（${rel.apkName}）") }
            .onFailure { message("ダウンロードできません。Releases ページを開きます"); HatchUpdate.openReleases(this) }
    }
    private fun showVideoSettings() {
        val info=runCatching {provider?.getCameraInfo(baseSelector())}.getOrNull() ?: return
        val caps=Recorder.getVideoCapabilities(info)
        val dr=if(videoHdr && caps.supportedDynamicRanges.contains(DynamicRange.HLG_10_BIT)) DynamicRange.HLG_10_BIT else DynamicRange.SDR
        val qs=caps.getSupportedQualities(dr)
        val options=qs.map { "画質 ${qualityName(it)}" } + listOf("フレームレート：${if(sixtyFps) "60fpsを要求" else "自動"}","HDR動画：${if(videoHdr) "ON" else "OFF"}","動画手ぶれ補正：${if(stabilization) "ON" else "OFF"}")
        choose("動画（組合せは端末依存）",options){ index->
            when(index){
                in qs.indices -> quality=qs[index]
                qs.size -> sixtyFps=!sixtyFps
                qs.size+1 -> {if(!caps.supportedDynamicRanges.contains(DynamicRange.HLG_10_BIT)) message("このカメラはHLG HDR動画非対応です") else videoHdr=!videoHdr}
                else -> {if(!caps.isStabilizationSupported) message("動画手ぶれ補正非対応です") else stabilization=!stabilization}
            }; if(video) bindCamera()
        }
    }
    private fun resetManual() {manualIso=null;manualTime=null;focusDistance=null;wb=CaptureRequest.CONTROL_AWB_MODE_AUTO}
    private fun characteristics():CameraCharacteristics? = runCatching {(getSystemService(CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(Camera2CameraInfo.from(camera!!.cameraInfo).cameraId)}.getOrNull()
    private fun showPro() {
        if(!video && extensionMode!=ExtensionMode.NONE) {message("PRO調整は標準モードで使用してください");return}
        val cc=characteristics() ?: return
        val manual=cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)==true
        choose("PRO（対応範囲で設定）",listOf("ISO / シャッター速度", "マニュアルフォーカス", "ホワイトバランス", "すべて自動に戻す")) {
            when(it) {
                0->{ if(!manual){message("このカメラはマニュアル露出非対応です");return@choose}
                    val range=cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return@choose
                    val iso=listOf(100,200,400,800,1600,3200).filter { v->range.contains(v) }
                    choose("ISO（現在 ${manualIso ?: "自動"}）",listOf("自動")+iso.map(Int::toString)){ i ->
                        if(i==0){manualIso=null;manualTime=null;applyManual()} else {
                            val chosenIso=iso[i-1]; val tr=cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return@choose
                            val den=listOf(8000,4000,2000,1000,500,250,125,60,30,15,8,4,2,1).filter { d->tr.contains(1_000_000_000L/d) }
                            choose("シャッター速度",den.map { d->"1/$d 秒" }){ j->manualIso=chosenIso;manualTime=1_000_000_000L/den[j];applyManual()}
                        }
                    }
                }
                1->{val max=cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    if(max<=0f){message("このレンズは固定焦点です");return@choose}
                    val values=listOf(null,0f,max*.25f,max*.5f,max*.75f,max)
                    choose("ピント",listOf("オート","無限遠","遠め","中間","近め","最短")){ i->focusDistance=values[i];applyManual() }
                }
                2->{val modes=listOf(CaptureRequest.CONTROL_AWB_MODE_AUTO to "自動",CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "晴天",CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "曇天",CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "電球",CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "蛍光灯").filter { pair->cc.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.contains(pair.first)==true }
                    choose("ホワイトバランス",modes.map { p->p.second }){ i->wb=modes[i].first;applyManual() }
                }
                3->{resetManual();applyManual()}
            }
        }
    }
    private fun applyManual() {
        val c=camera ?: return
        if(!video && extensionMode!=ExtensionMode.NONE) return
        val b=CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE,wb)
        if(manualIso!=null && manualTime!=null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY,manualIso!!).setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME,manualTime!!)
            imageCapture?.flashMode=ImageCapture.FLASH_MODE_OFF
        }
        if(manualIso==null || manualTime==null) imageCapture?.flashMode=flash
        if(focusDistance!=null) b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF).setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE,focusDistance!!)
        watch(Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(b.build()),"PRO設定")
    }
    private fun setupFold() {
        if(Build.VERSION.SDK_INT>=30) hinge=sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        runCatching {
            areaController=WindowAreaController.getOrCreate()
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    areaController!!.windowAreaInfos.collect { infos ->
                        areaInfo=infos.firstOrNull { it.type==WindowAreaInfo.Type.TYPE_REAR_FACING }
                        areaStatus=areaInfo?.getCapability(operation)?.status ?: WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
                        updateRearUi(); tryAutoRear()
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                        if(hinge==null && rearSession==null && !rearRequested) {
                            val feature=info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                            when { feature?.state==FoldingFeature.State.FLAT -> updateAngle(180f)
                                feature?.state==FoldingFeature.State.HALF_OPENED -> updateAngle(90f)
                                resources.configuration.screenWidthDp<500 -> updateAngle(0f) }
                        }
                    }
                }
            }
        }.onFailure { lastError="外側画面API: ${it.message}" }
    }
    private fun updateAngle(angle:Float) {
        lastAngle=angle
        if(rearSession!=null || rearRequested) return
        if(angle<15f) pendingAutoRear=false
        if(foldTrigger.update(angle) && autoRear) { pendingAutoRear=true; pendingRearAt=SystemClock.elapsedRealtime()+700 }
        if(pendingAutoRear) {handler.removeCallbacks(rearCheck);handler.postDelayed(rearCheck,maxOf(0L,pendingRearAt-SystemClock.elapsedRealtime()))}
    }
    private fun tryAutoRear() {
        if(!pendingAutoRear || !autoRear || rearRequested || rearSession!=null) return
        if(camera==null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || recording!=null || photoBusy || countDown!=null) return
        if((lastAngle ?: 0f)<155f) return
        val remaining=pendingRearAt-SystemClock.elapsedRealtime()
        if(remaining>0){handler.removeCallbacks(rearCheck);handler.postDelayed(rearCheck,remaining);return}
        if(areaStatus==WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE) { pendingAutoRear=false; startRear() }
    }
    private fun updateRearUi() {
        if(!::rearButton.isInitialized) return
        rearButton.text=when {
            rearSession!=null || areaStatus==WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> "内側画面で撮影に戻る"
            rearRequested -> "外側画面への切替を確認中…"
            else -> "外側画面で自撮り  ·  自動${if(autoRear) "ON" else "OFF"}"
        }
        rearButton.isEnabled=recording==null && !photoBusy && countDown==null && !rearRequested
    }
    private fun toggleRear() {
        pendingAutoRear=false
        if(rearSession!=null || areaStatus==WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE) {
            runCatching { (rearSession ?: areaInfo?.getActiveSession(operation))?.close() }.onFailure {message("画面を戻せません: ${it.message}")}
            return
        }
        startRear()
    }
    private fun startRear() {
        if(recording!=null || photoBusy || countDown!=null) {message("撮影・保存が終わってから切り替えてください");return}
        val info=areaInfo
        if(info==null || areaStatus!=WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE) {
            message(if(areaStatus==WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED) "外側画面のAPIが使えません。OSを更新し、診断結果を確認してください" else "現在は外側画面へ切替できません。本体を開いて再試行してください")
            return
        }
        rearRequested=true; selectedId=null; facing=CameraSelector.LENS_FACING_BACK; resetManual(); updateRearUi()
        try {
            areaController?.transferActivityToWindowArea(info.token,this,main,object:WindowAreaSessionCallback {
                override fun onSessionStarted(session:WindowAreaSession) {rearSession=session;rearRequested=false;pendingAutoRear=false;bindCamera();updateRearUi()}
                override fun onSessionEnded(t:Throwable?) {rearSession=null;rearRequested=false;pendingAutoRear=false;foldTrigger.reset(); if(t!=null){lastError="外側画面: ${t.message}";message(lastError)};if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))bindCamera();updateRearUi()}
            })
        } catch(e:Exception) {rearRequested=false;fail("外側画面切替",e);updateRearUi()}
    }
    private fun diagnosticText():String = buildString {
        appendLine("Theta Camera 0.1.0 — capability report")
        appendLine("Date: ${Date()}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Camera permission: ${hasCamera()}")
        appendLine("Rear display: $areaStatus / session=${rearSession!=null}")
        appendLine("Hinge sensor: ${hinge!=null}; angle=$lastAngle; autoRear=$autoRear")
        appendLine("Current resolution: ${imageCapture?.resolutionInfo?.resolution}")
        appendLine("Last error: $lastError\n")
        val manager=getSystemService(CAMERA_SERVICE) as CameraManager
        runCatching { manager.cameraIdList.forEach { id ->
            val cc=manager.getCameraCharacteristics(id)
            appendLine("=== Camera $id ===")
            appendLine("Facing: ${cc.get(CameraCharacteristics.LENS_FACING)} [0=front,1=back]")
            appendLine("Hardware level: ${cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)}")
            appendLine("Focal mm: ${cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()}")
            appendLine("Physical IDs: ${cc.physicalCameraIds}")
            val caps=cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            appendLine("RAW advertised: ${caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)} (RAW+JPEG availability also depends on CameraX and stream combination)")
            appendLine("Manual sensor: ${caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)}")
            appendLine("ISO: ${cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)}")
            appendLine("Exposure ns: ${cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)}")
            appendLine("AF: ${cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.joinToString()}")
            appendLine("AE FPS ranges: ${cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString()}")
            appendLine("OIS: ${cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.joinToString()}")
            val map=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            appendLine("JPEG sizes: ${map?.getOutputSizes(ImageFormat.JPEG)?.joinToString()}")
            appendLine("High-res JPEG sizes: ${map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.joinToString()}")
            val info=provider?.availableCameraInfos?.firstOrNull { Camera2CameraInfo.from(it).cameraId==id }
            if(info!=null) runCatching {
                val sel=CameraSelector.Builder().addCameraFilter { list->list.filter {Camera2CameraInfo.from(it).cameraId==id} }.build()
                appendLine("Extensions: "+listOf(ExtensionMode.AUTO,ExtensionMode.HDR,ExtensionMode.NIGHT,ExtensionMode.BOKEH,ExtensionMode.FACE_RETOUCH).joinToString { "${modeName(it)}=${extensions?.isExtensionAvailable(sel,it)}" })
                val vc=Recorder.getVideoCapabilities(info)
                appendLine("Video dynamic ranges: ${vc.supportedDynamicRanges}")
                vc.supportedDynamicRanges.forEach { dr->appendLine("Video $dr: ${vc.getSupportedQualities(dr).joinToString {qualityName(it)}}") }
                appendLine("Video stabilization: ${vc.isStabilizationSupported}")
                appendLine("Image output formats: ${ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats}")
            }.onFailure {appendLine("CameraX query error: ${it.message}")}
            appendLine()
        } }.onFailure {appendLine("Camera enumeration error: ${it.message}")}
        appendLine("No photos, videos, account details, or device serial included.")
    }
    private fun showDiagnostics() {
        val report=diagnosticText()
        val content=TextView(this).apply {text=report;textSize=12f;setPadding(dp(16),dp(12),dp(16),dp(12));setTextIsSelectable(true)}
        android.app.AlertDialog.Builder(this).setTitle("端末診断（対応状況）").setView(ScrollView(this).apply {addView(content)})
            .setPositiveButton("コピー"){_,_->(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Theta Camera診断",report));message("診断をコピーしました")}
            .setNeutralButton("共有"){_,_->startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"診断テキストを共有"))}
            .setNegativeButton("閉じる",null).show()
    }
    override fun onConfigurationChanged(newConfig:Configuration) {super.onConfigurationChanged(newConfig);arrangeUi();preview.post {imageCapture?.targetRotation=preview.display.rotation;if(recording==null)videoCapture?.targetRotation=preview.display.rotation;tryAutoRear()} }
    override fun onResume() {
        super.onResume(); orientationListener?.enable()
        hinge?.let {sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_NORMAL)}
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_UI)}
        if(hinge==null && resources.configuration.screenWidthDp<500 && rearSession==null) updateAngle(0f)
        handler.postDelayed(rearCheck,800)
    }
    override fun onPause() {super.onPause();orientationListener?.disable();sensors.unregisterListener(this);countDown?.cancel();countDown=null;if(!photoBusy && recording==null){setBusy(false);refreshInfo()} }
    override fun onStop() {super.onStop();recording?.stop();handler.removeCallbacks(rearCheck);foldTrigger.reset()}
    override fun onDestroy() {countDown?.cancel();recording?.close();runCatching {rearSession?.close()};handler.removeCallbacksAndMessages(null);super.onDestroy()}
    override fun onSensorChanged(e:SensorEvent) { when(e.sensor.type) {
        Sensor.TYPE_HINGE_ANGLE -> updateAngle(e.values[0])
        Sensor.TYPE_ACCELEROMETER -> {if(overlay.level){overlay.roll=(atan2(e.values[0],e.values[1])*180/Math.PI).toFloat();overlay.invalidate()}}
    } }
    override fun onAccuracyChanged(s:Sensor?,accuracy:Int){}
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {if(keyCode==KeyEvent.KEYCODE_VOLUME_UP || keyCode==KeyEvent.KEYCODE_VOLUME_DOWN){if(event.repeatCount==0)capture();return true};return super.onKeyDown(keyCode,event)}
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean {if(keyCode==KeyEvent.KEYCODE_VOLUME_UP || keyCode==KeyEvent.KEYCODE_VOLUME_DOWN)return true;return super.onKeyUp(keyCode,event)}
}

class GuideView(context:Context):View(context) {
    var grid=true;var level=false;var roll=0f;var focusX=-1f;var focusY=-1f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas) {
        super.onDraw(c);paint.style=Paint.Style.STROKE;paint.strokeWidth=resources.displayMetrics.density
        if(grid){paint.color=Color.argb(95,255,255,255);for(i in 1..2){c.drawLine(width*i/3f,0f,width*i/3f,height.toFloat(),paint);c.drawLine(0f,height*i/3f,width.toFloat(),height*i/3f,paint)}}
        if(level){val correction=((roll+45f)%90f+90f)%90f-45f;paint.color=if(abs(correction)<2)Color.rgb(142,226,181) else Color.WHITE;c.save();c.rotate(-correction,width/2f,height/2f);c.drawLine(width*.35f,height/2f,width*.65f,height/2f,paint);c.restore()}
        if(focusX>=0){paint.color=Color.rgb(142,226,181);c.drawCircle(focusX,focusY,28*resources.displayMetrics.density,paint)}
    }
}
