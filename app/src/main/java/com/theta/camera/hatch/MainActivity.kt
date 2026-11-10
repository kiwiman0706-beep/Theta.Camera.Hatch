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
    private lateinit var root: FrameLayout
    private lateinit var ui: FrameLayout
    private lateinit var banner: TextView
    private lateinit var liveChip: TextView
    private lateinit var lensInfo: TextView
    private lateinit var hudRows: LinearLayout
    private lateinit var flashChip: TextView
    private lateinit var timerChip: TextView
    private lateinit var evChip: TextView
    private lateinit var levelChip: TextView
    private lateinit var gridChip: TextView
    private lateinit var modeChip: TextView
    private lateinit var qualityChip: TextView
    private lateinit var fpsChip: TextView
    private lateinit var settingsChip: TextView
    private lateinit var rearChip: TextView
    private lateinit var hudToggle: TextView
    private lateinit var evBar: LinearLayout
    private lateinit var exposureSeek: SeekBar
    private lateinit var zoomPill: LinearLayout
    private val zoomChips = mutableListOf<Pair<Float, TextView>>()
    private lateinit var lastThumb: ImageView
    private lateinit var bottomBox: LinearLayout
    private lateinit var shutterBar: LinearLayout
    private lateinit var photoBtn: ShutterButton
    private lateinit var videoBtn: ShutterButton
    private lateinit var scanBtn: ShutterButton
    private lateinit var videoLabel: TextView
    private var hudShown = false
    private var pendingVideo = false
    private var videoOnly = false
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
        if (hasCamera()) startProvider() else message("カメラの許可が必要です。設定でカメラを許可すると撮影できます")
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message("マイク未許可のため、動画は無音で保存します")
        if (pendingVideo && recording == null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { pendingVideo = false; startRecording() }
    }
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val res = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(r.data) ?: return@registerForActivityResult
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { saveScan(res) } }
            n.onSuccess { message("スキャンを保存しました（${it}ページ・PDF）") }.onFailure { message("スキャンを保存できません：${it.message}") }
            updateThumb()
        }
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
        if (hasCamera()) startProvider() else permissions.launch(startPermissions())
    }
    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun message(s: String) = showBanner(s, if (s.length > 40) 4500L else 2500L)
    private fun round(color:Int, radiusDp:Int) = GradientDrawable().apply { setColor(color); cornerRadius=dp(radiusDp).toFloat() }
    private fun chip(text:String, action:()->Unit) = TextView(this).apply {
        this.text=text; textSize=13f; gravity=Gravity.CENTER; setTextColor(C_TEXT); isSingleLine=true
        minHeight=dp(40); minWidth=dp(48); setPadding(dp(12),0,dp(12),0); background=round(C_CHIP,12)
        setOnClickListener { action() }
        layoutParams=LinearLayout.LayoutParams(-2,dp(40)).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }
    }
    private fun setActive(v:TextView,on:Boolean) { v.background=round(if(on) C_ACTIVE else C_CHIP,12); v.setTextColor(if(on) C_ACTIVE_TEXT else C_TEXT) }
    private fun hrow(vararg views:View) = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; views.forEach { addView(it) } }
    private fun vcol(g:Int, vararg views:View) = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=g; views.forEach { addView(it) } }
    private fun at(g:Int, l:Int=0, t:Int=0, r:Int=0, b:Int=0) = FrameLayout.LayoutParams(-2,-2,g).apply { setMargins(dp(l),dp(t),dp(r),dp(b)) }
    private fun shadowText(s:String, size:Float, color:Int=C_TEXT) = TextView(this).apply { text=s; textSize=size; setTextColor(color); gravity=Gravity.CENTER; setShadowLayer(6f,0f,1f,Color.BLACK) }
    private fun shutterItem(b:ShutterButton, text:String, size:Int, action:()->Unit):Pair<LinearLayout,TextView> {
        val lbl=shadowText(text,12f)
        b.contentDescription=text; b.setOnClickListener { action() }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(dp(12),dp(4),dp(12),dp(4))
            addView(b,LinearLayout.LayoutParams(dp(size),dp(size))); addView(lbl) }
        return box to lbl
    }
    private val hideBanner=Runnable { if(::banner.isInitialized) banner.animate().alpha(0f).setDuration(250).withEndAction { banner.visibility=View.GONE }.start() }
    private fun showBanner(s:String, ms:Long) {
        if(!::banner.isInitialized) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); return }
        banner.animate().cancel(); banner.text=s; banner.alpha=1f; banner.visibility=View.VISIBLE
        handler.removeCallbacks(hideBanner); handler.postDelayed(hideBanner,ms)
    }
    private fun buildUi() {
        root=FrameLayout(this).apply { setBackgroundColor(C_BG) }
        preview=PreviewView(this).apply { implementationMode=PreviewView.ImplementationMode.COMPATIBLE; scaleType=PreviewView.ScaleType.FIT_CENTER; setBackgroundColor(C_BG) }
        overlay=GuideView(this).apply { grid=prefs.getBoolean("grid",false); level=prefs.getBoolean("level",false) }
        root.addView(preview,FrameLayout.LayoutParams(-1,-1)); root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        ui=FrameLayout(this); root.addView(ui,FrameLayout.LayoutParams(-1,-1))
        ui.setOnApplyWindowInsetsListener { v,ins ->
            val b=androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(ins).getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left+dp(12),b.top+dp(10),b.right+dp(12),b.bottom+dp(10)); ins
        }
        // 左上：操作（表示時のみ）・LIVE・レンズ
        flashChip=chip("") { showFlash() }
        timerChip=chip("") { timerSeconds=listOf(0,3,5,10).let { it[(it.indexOf(timerSeconds)+1)%it.size] }; prefs.edit().putInt("timer",timerSeconds).apply(); refreshInfo() }
        evChip=chip("±0 EV") { evBar.visibility=if(evBar.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        levelChip=chip("水平") { overlay.level=!overlay.level; prefs.edit().putBoolean("level",overlay.level).apply(); overlay.invalidate(); refreshInfo() }
        gridChip=chip("グリッド") { overlay.grid=!overlay.grid; prefs.edit().putBoolean("grid",overlay.grid).apply(); overlay.invalidate(); refreshInfo() }
        modeChip=chip("") { showModes() }
        qualityChip=chip("") { showVideoSettings() }
        fpsChip=chip("") { if(recording!=null) { message("録画中は変更できません"); return@chip }; sixtyFps=!sixtyFps; bindCamera(); refreshInfo() }
        hudRows=vcol(Gravity.START,
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(hrow(flashChip,timerChip,evChip,levelChip,gridChip)) },
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(hrow(modeChip,qualityChip,fpsChip)) })
        liveChip=chip("● LIVE") {}.apply { isClickable=false }
        lensInfo=shadowText("",12f,C_SUB).apply { gravity=Gravity.START; setPadding(dp(6),dp(2),dp(6),dp(2)) }
        ui.addView(vcol(Gravity.START,hudRows,liveChip,lensInfo),at(Gravity.TOP or Gravity.START,r=124))
        // 右上：設定・外画面（表示時のみ）・操作を表示
        settingsChip=chip("設定") { showSettings() }
        rearChip=chip("外画面で自撮り") { toggleRear() }
        hudToggle=chip("操作を表示") { setHud(!hudShown) }
        ui.addView(vcol(Gravity.END,settingsChip,rearChip,hudToggle),at(Gravity.TOP or Gravity.END))
        // 知らせ
        banner=TextView(this).apply { textSize=14f; setTextColor(C_ACTIVE_TEXT); background=round(C_ACTIVE,14); setPadding(dp(16),dp(10),dp(16),dp(10)); gravity=Gravity.CENTER; maxWidth=dp(520); visibility=View.GONE; elevation=dp(4).toFloat() }
        ui.addView(banner,at(Gravity.TOP or Gravity.CENTER_HORIZONTAL,l=8,t=112,r=8))
        // 下：明るさ・レンズ・シャッター
        exposureSeek=SeekBar(this).apply { setOnSeekBarChangeListener(seekListener { camera?.let { c -> watch(c.cameraControl.setExposureCompensationIndex(it+c.cameraInfo.exposureState.exposureCompensationRange.lower),"露出変更"); handler.postDelayed({refreshInfo()},150) } }) }
        evBar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; background=round(C_CHIP,22); setPadding(dp(12),dp(2),dp(4),dp(2)); visibility=View.GONE
            addView(shadowText("明るさ",12f)); addView(exposureSeek,LinearLayout.LayoutParams(dp(220),dp(40)))
            addView(chip("完了") { visibility=View.GONE }) }
        zoomPill=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; background=round(C_CHIP,24); setPadding(dp(4),dp(2),dp(4),dp(2)) }
        listOf(0.6f to "0.6×",1f to "1×",3f to "3×").forEach { (z,t) -> val c=chip(t) { if(facing!=CameraSelector.LENS_FACING_BACK) { facing=CameraSelector.LENS_FACING_BACK; selectedId=null; resetManual(); bindCamera() }; zoomTo(z) }.apply { background=null }; zoomChips.add(z to c); zoomPill.addView(c) }
        zoomPill.addView(chip("⟲") { if(recording!=null) { message("録画中は切り替えられません"); return@chip }
            facing=if(facing==CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            selectedId=null; resetManual(); bindCamera() }.apply { background=null; contentDescription="前後のカメラを切り替え" })
        photoBtn=ShutterButton(this,ShutterButton.PHOTO); videoBtn=ShutterButton(this,ShutterButton.VIDEO); scanBtn=ShutterButton(this,ShutterButton.SCAN)
        val (p,_)=shutterItem(photoBtn,"写真",64) { onPhoto() }
        val (v,vl)=shutterItem(videoBtn,"動画",76) { onVideo() }; videoLabel=vl
        val (s,_)=shutterItem(scanBtn,"スキャン",56) { startScan() }
        shutterBar=LinearLayout(this).apply { gravity=Gravity.CENTER; addView(p); addView(v); addView(s) }
        bottomBox=vcol(Gravity.CENTER_HORIZONTAL,evBar,zoomPill)
        ui.addView(bottomBox,at(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        lastThumb=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=round(C_CHIP,14); clipToOutline=true; contentDescription="直前の写真・動画を開く"; setOnClickListener { openLast() } }
        ui.addView(lastThumb,FrameLayout.LayoutParams(dp(52),dp(52),Gravity.BOTTOM or Gravity.START).apply { bottomMargin=dp(14) })
        arrangeUi(); setHud(prefs.getBoolean("hud",false)); setContentView(root); updateThumb()
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
        preview.setOnTouchListener { v2,e -> pinch.onTouchEvent(e); if(!pinch.isInProgress) gestures.onTouchEvent(e); if(e.actionMasked==MotionEvent.ACTION_UP && !pinch.isInProgress) v2.performClick(); true }
    }
    private fun setHud(show:Boolean) {
        hudShown=show; prefs.edit().putBoolean("hud",show).apply()
        val vis=if(show) View.VISIBLE else View.GONE
        hudRows.visibility=vis; lensInfo.visibility=vis; settingsChip.visibility=vis; zoomPill.visibility=vis; lastThumb.visibility=vis
        if(!show) evBar.visibility=View.GONE
        hudToggle.text=if(show) "操作を隠す" else "操作を表示"
        updateRearUi()
    }
    /** 横長の画面ではシャッターを右端の縦並びに、それ以外は下の横並び */
    private fun arrangeUi() {
        (shutterBar.parent as? ViewGroup)?.removeView(shutterBar)
        val dm=resources.displayMetrics
        val wide=dm.widthPixels>dm.heightPixels*1.3f
        shutterBar.orientation=if(wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if(wide) ui.addView(shutterBar,at(Gravity.END or Gravity.CENTER_VERTICAL))
        else bottomBox.addView(shutterBar)
    }
    private fun updateThumb() {
        val uri=lastUri ?: run { lastThumb.setImageDrawable(null); return }
        lifecycleScope.launch {
            val bmp=withContext(Dispatchers.IO) { runCatching { contentResolver.loadThumbnail(uri,android.util.Size(dp(52),dp(52)),null) }.getOrNull() }
            lastThumb.setImageBitmap(bmp)
        }
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
        p.unbindAll(); camera=null; imageCapture=null; videoCapture=null
        try {
            var selector=baseSelector()
            if (!p.hasCamera(selector)) { selectedId=null; facing=CameraSelector.LENS_FACING_BACK; selector=baseSelector() }
            val baseInfo=p.getCameraInfo(selector)
            if(extensionMode!=ExtensionMode.NONE) {
                if(extensions?.isExtensionAvailable(selector,extensionMode)==true) selector=extensions!!.getExtensionEnabledCameraSelector(selector,extensionMode)
                else { extensionMode=ExtensionMode.NONE; message("このレンズでは選択した拡張に非対応です。標準で撮影します") }
            }
            val resolution=ResolutionSelector.Builder().setAspectRatioStrategy(if(wideAspect) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setAllowedResolutionMode(if(highResolution) ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE else ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                .setResolutionStrategy(if(highResolution) ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY else ResolutionStrategy(android.util.Size(4000,3000),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
            val pb=Preview.Builder().setTargetRotation(preview.display?.rotation ?: Surface.ROTATION_0)
            pb.setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(if(wideAspect) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            val pv=pb.build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            // 写真と動画を同時に接続（起動直後にワンタップで録画・録画中も写真）
            if(!videoOnly) {
                val ib=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(100).setResolutionSelector(resolution).setFlashMode(flash).setTargetRotation(preview.display?.rotation ?: Surface.ROTATION_0)
                val caps=ImageCapture.getImageCaptureCapabilities(p.getCameraInfo(selector))
                if(rawJpeg && caps.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)) ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                else if(rawJpeg) {rawJpeg=false;message("この構成ではRAW+JPEG非対応です。JPEGに戻しました")}
                if(!rawJpeg && ultraHdr && caps.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)) ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                else if(ultraHdr) { ultraHdr=false; message("この構成ではUltra HDR非対応のためJPEGで撮影します") }
                imageCapture=ib.build()
            }
            if(extensionMode==ExtensionMode.NONE) {
                val vcaps=Recorder.getVideoCapabilities(baseInfo)
                val dr=if(videoHdr && vcaps.supportedDynamicRanges.contains(DynamicRange.HLG_10_BIT)) DynamicRange.HLG_10_BIT else DynamicRange.SDR
                if(videoHdr && dr==DynamicRange.SDR) { videoHdr=false; message("このレンズではHDR動画に非対応です。SDRに戻しました") }
                val qs=vcaps.getSupportedQualities(dr)
                if(qs.isNotEmpty()) {
                    if(!qs.contains(quality)) quality=qs.firstOrNull { it==Quality.FHD } ?: qs.first()
                    val rec=Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
                    val vb=VideoCapture.Builder(rec).setDynamicRange(dr).setTargetRotation(preview.display?.rotation ?: Surface.ROTATION_0)
                    if(sixtyFps) vb.setTargetFrameRate(Range(60,60))
                    if(stabilization && vcaps.isStabilizationSupported) vb.setVideoStabilizationEnabled(true)
                    videoCapture=vb.build()
                }
            }
            val ic=imageCapture; val vc=videoCapture
            camera=when {
                ic!=null && vc!=null -> try { p.bindToLifecycle(this,selector,pv,ic,vc) } catch(e:IllegalArgumentException) {
                    // 写真と動画の同時接続に非対応：写真だけで接続し、動画ボタンで動画用に接続し直す
                    lastError="写真と動画の同時接続に非対応: ${e.message}"; videoCapture=null; p.unbindAll(); p.bindToLifecycle(this,selector,pv,ic) }
                ic!=null -> p.bindToLifecycle(this,selector,pv,ic)
                vc!=null -> p.bindToLifecycle(this,selector,pv,vc)
                else -> error("接続できる撮影機能がありません")
            }
            camera?.let { c ->
                c.cameraInfo.zoomState.observe(this) { refreshInfo() }
                c.cameraInfo.cameraState.observe(this) { s -> s.error?.let { lastError="CameraState error ${it.code}: ${it.cause?.message ?: ""}"; message("カメラエラー。設定の「カメラを再接続」をお試しください") } }
                val ev=c.cameraInfo.exposureState
                exposureSeek.max=ev.exposureCompensationRange.upper-ev.exposureCompensationRange.lower
                exposureSeek.progress=ev.exposureCompensationIndex-ev.exposureCompensationRange.lower
                exposureSeek.isEnabled=ev.isExposureCompensationSupported
                torch=false
                applyManual()
            }
            setBusy(false); refreshInfo()
            if(pendingVideo) { pendingVideo=false; onVideo() } else tryAutoRear()
        } catch(e:Exception) {
            lastError="バインド失敗: ${e.message}"
            if(!fallback) {
                extensionMode=ExtensionMode.NONE; videoOnly=false; highResolution=false; ultraHdr=false; rawJpeg=false; videoHdr=false; sixtyFps=false; stabilization=false; quality=Quality.FHD; selectedId=null; facing=CameraSelector.LENS_FACING_BACK; resetManual()
                message("この撮影設定を利用できないため、標準設定で再接続します")
                bindCamera(true)
            } else { pendingVideo=false; fail("カメラ接続（設定の端末診断を確認してください）",e) }
        }
    }
    private fun fail(where:String,e:Exception) { lastError="$where: ${e.cause?.message ?: e.message}"; message(lastError) }
    private fun lensName(z:Float)=when { facing==CameraSelector.LENS_FACING_FRONT -> "インカメラ"; z<0.95f -> "超広角"; z>=2.5f -> "望遠"; else -> "広角" }
    private fun refreshInfo() {
        if(!::liveChip.isInitialized) return
        val z=camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        flashChip.text="⚡ "+when(flash){ImageCapture.FLASH_MODE_AUTO->"自動";ImageCapture.FLASH_MODE_ON->"ON";else->"OFF"}+if(torch) "・ライト" else ""
        setActive(flashChip,flash!=ImageCapture.FLASH_MODE_OFF||torch)
        timerChip.text=if(timerSeconds==0) "⏱ OFF" else "⏱ ${timerSeconds}秒"; setActive(timerChip,timerSeconds>0)
        val ev=camera?.cameraInfo?.exposureState
        val evValue=ev?.let { it.exposureCompensationIndex*it.exposureCompensationStep.toFloat() } ?: 0f
        evChip.text=if(abs(evValue)<0.05f) "±0 EV" else String.format(Locale.US,"%+.1f EV",evValue); setActive(evChip,abs(evValue)>=0.05f)
        setActive(levelChip,overlay.level); setActive(gridChip,overlay.grid)
        modeChip.text="写真 "+modeName(extensionMode)+if(rawJpeg) "・RAW" else if(ultraHdr) "・UHDR" else ""
        setActive(modeChip,extensionMode!=ExtensionMode.NONE||rawJpeg||ultraHdr)
        qualityChip.text=qualityName(quality)+if(videoHdr) " HDR" else ""; setActive(qualityChip,videoHdr)
        fpsChip.text=if(sixtyFps) "60 fps" else "30 fps"; setActive(fpsChip,sixtyFps)
        val res=imageCapture?.resolutionInfo?.resolution
        lensInfo.text="${lensName(z)} · ${String.format(Locale.US,"%.1f×",z)}${res?.let { "  ·  ${it.width}×${it.height}" } ?: ""}"
        val near=zoomChips.minByOrNull { abs(it.first-z) }?.first
        zoomChips.forEach { (v,c) -> if(v==near && facing==CameraSelector.LENS_FACING_BACK) { c.background=round(C_ACTIVE,18); c.setTextColor(C_ACTIVE_TEXT) } else { c.background=null; c.setTextColor(C_TEXT) } }
        if(recording==null) { liveChip.text="● LIVE"; liveChip.background=round(C_CHIP,12); liveChip.setTextColor(C_TEXT) }
        videoBtn.recording=recording!=null; videoLabel.text=if(recording!=null) "停止" else "動画"
        updateRearUi()
    }
    private fun zoomTo(value:Float) {
        val c=camera ?: return; val z=c.cameraInfo.zoomState.value ?: return
        if(value<z.minZoomRatio-0.01f) { message("このレンズの最小倍率は${String.format(Locale.US,"%.1f",z.minZoomRatio)}×です。超広角は設定の「レンズを選ぶ」から選べる場合があります") }
        watch(c.cameraControl.setZoomRatio(value.coerceIn(z.minZoomRatio,z.maxZoomRatio)),"ズーム")
    }
    private fun setBusy(busy:Boolean) {
        if(!::photoBtn.isInitialized) return
        photoBtn.isEnabled=!photoBusy; scanBtn.isEnabled=!busy && recording==null
        modeChip.isEnabled=!busy; settingsChip.isEnabled=!busy
    }
    private fun onPhoto() {
        if(photoBusy) return
        if(countDown!=null) { countDown?.cancel(); countDown=null; message("タイマーを中止しました"); return }
        if(!hasCamera()) { permissions.launch(startPermissions()); return }
        if(camera==null) { startProvider(); return }
        if(imageCapture==null) { message("この端末では録画中の写真に対応していません"); return }
        if(timerSeconds>0 && recording==null) {
            countDown=object:CountDownTimer(timerSeconds*1000L,250){
                override fun onTick(ms:Long){ showBanner("${ceil(ms/1000.0).toInt()} 秒後に撮影（写真をもう一度押すと中止）",600) }
                override fun onFinish(){ countDown=null; takePhoto() }
            }.start()
        } else takePhoto()
    }
    private fun onVideo() {
        if(recording!=null) { recording?.stop(); videoBtn.isEnabled=false; return }
        if(!hasCamera()) { permissions.launch(startPermissions()); return }
        if(camera==null) { pendingVideo=true; startProvider(); return }
        if(countDown!=null) { countDown?.cancel(); countDown=null }
        if(videoCapture==null) {
            // 拡張モードや同時接続に非対応のときは、動画用に接続し直してから録画
            if(extensionMode!=ExtensionMode.NONE) { extensionMode=ExtensionMode.NONE; message("動画のため写真モードを標準に戻しました") } else videoOnly=true
            pendingVideo=true; bindCamera(); return
        }
        if(audioEnabled && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { pendingVideo=true; microphone.launch(Manifest.permission.RECORD_AUDIO); return }
        startRecording()
    }
    private fun startPermissions()=if(audioEnabled) arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO) else arrayOf(Manifest.permission.CAMERA)
    private fun name()="Theta_"+SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(Date())
    private fun takePhoto() {
        val cap=imageCapture ?: return
        photoBusy=true; setBusy(recording!=null)
        preview.animate().alpha(0.35f).setDuration(60).withEndAction { preview.animate().alpha(1f).setDuration(160).start() }.start()
        val basename=name()
        fun output(ext:String,mime:String)=ImageCapture.OutputFileOptions.Builder(contentResolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,basename+ext);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/ThetaCamera")
        }).build()
        val pair=cap.outputFormat==ImageCapture.OUTPUT_FORMAT_RAW_JPEG
        var saved=0; var failed=false
        fun finish() {photoBusy=false;setBusy(recording!=null);refreshInfo();tryAutoRear()}
        val callback=object:ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                if(result.imageFormat==ImageFormat.JPEG || !pair) { saveLast(result.savedUri,false); updateThumb() }
                saved++
                if(!failed && saved==(if(pair)2 else 1)) {finish(); showBanner(if(pair) "RAWとJPEGを保存しました" else "写真を保存しました",1500)}
            }
            override fun onError(e:ImageCaptureException) {failed=true;finish();fail("写真保存（一部未保存の可能性）",e)}
        }
        try {
            if(pair) cap.takePicture(output(".dng","image/x-adobe-dng"),output(".jpg","image/jpeg"),main,callback)
            else cap.takePicture(output(".jpg","image/jpeg"),main,callback)
        } catch(e:Exception) {failed=true;finish();fail("写真撮影",e)}
    }

    private fun startRecording() {
        val cap=videoCapture ?: return
        try {
            val values=ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,name()+".mp4"); put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4"); put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/ThetaCamera") }
            val output=MediaStoreOutputOptions.Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
            var pending=cap.output.prepareRecording(this,output)
            val withAudio=audioEnabled && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
            if(withAudio) pending=pending.withAudioEnabled()
            recording=pending.start(main) { event ->
                when(event) {
                    is VideoRecordEvent.Start -> { refreshInfo(); setBusy(true); showBanner("${lensName(camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f)} · 録画開始${if(withAudio) "" else "（無音）"}",1500) }
                    is VideoRecordEvent.Status -> { val sec=event.recordingStats.recordedDurationNanos/1_000_000_000
                        liveChip.text="● REC ${sec/60}:${(sec%60).toString().padStart(2,'0')}"; liveChip.background=round(C_REC,12); liveChip.setTextColor(C_ACTIVE_TEXT) }
                    is VideoRecordEvent.Finalize -> {
                        recording=null; videoBtn.isEnabled=true; setBusy(false)
                        if(!event.hasError()) { saveLast(event.outputResults.outputUri,true); updateThumb(); showBanner("動画を保存しました",1500) }
                        else {lastError="動画終了エラー ${event.error}: ${event.cause?.message}"; message(lastError)}
                        refreshInfo()
                        if(videoOnly) { videoOnly=false; bindCamera() } else tryAutoRear()
                    }
                }
            }
        } catch(e:Exception) {recording=null; setBusy(false); videoBtn.isEnabled=true; fail("録画開始",e); refreshInfo()}
    }
    private fun saveLast(uri:Uri?,isVideo:Boolean) { if(uri==null || uri==Uri.EMPTY) return; lastUri=uri; lastVideo=isVideo; prefs.edit().putString("lastUri",uri.toString()).putBoolean("lastVideo",isVideo).apply() }
    private fun openLast() { val uri=lastUri ?: run {message("撮影すると、ここから直前の写真・動画を開けます");return}; try {startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,if(lastVideo) "video/*" else "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))} catch(e:Exception){message("表示できません。ギャラリーのThetaCameraフォルダを確認してください")} }
    private fun choose(title:String,items:List<String>,action:(Int)->Unit) { android.app.AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()){_,which->action(which)}.setNegativeButton("閉じる",null).show() }
    private fun modeName(m:Int)=when(m){ExtensionMode.AUTO->"メーカー自動";ExtensionMode.HDR->"HDR";ExtensionMode.NIGHT->"夜景";ExtensionMode.BOKEH->"ポートレート";ExtensionMode.FACE_RETOUCH->"美肌";else->"標準"}
    private fun qualityName(q:Quality)=when(q){Quality.UHD->"4K";Quality.FHD->"1080p";Quality.HD->"720p";Quality.SD->"SD";else->q.toString()}
    private fun showModes() {
        if(recording!=null) { message("録画中は変更できません"); return }
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
        refreshInfo()
    } }
    private fun showSettings() {
        val items=listOf<Pair<String,()->Unit>>(
            "写真モード：${modeName(extensionMode)}" to { showModes() },
            "PRO（ISO・シャッター・ピント・WB）" to { showPro() },
            "レンズを選ぶ（ID 指定）" to { showLenses() },
            "動画設定（画質・fps・HDR・手ぶれ補正）" to { showVideoSettings() },
            "音声録音：${if(audioEnabled) "ON" else "OFF"}" to { audioEnabled=!audioEnabled; prefs.edit().putBoolean("audio",audioEnabled).apply() },
            "写真解像度：${if(highResolution) "公開される最大" else "通常"}" to { highResolution=!highResolution; prefs.edit().putBoolean("highResolution",highResolution).apply(); bindCamera() },
            "写真比率：${if(wideAspect) "16:9" else "4:3"}" to { wideAspect=!wideAspect; bindCamera() },
            "Ultra HDR写真：${if(ultraHdr) "ON" else "OFF"}" to { ultraHdr=!ultraHdr; if(ultraHdr) rawJpeg=false; bindCamera() },
            "RAW+JPEG：${if(rawJpeg) "ON" else "OFF"}" to { rawJpeg=!rawJpeg; if(rawJpeg){ultraHdr=false;extensionMode=ExtensionMode.NONE}; bindCamera() },
            "開いたら外画面で自撮り：${if(autoRear) "ON" else "OFF"}" to { autoRear=!autoRear; pendingAutoRear=false; prefs.edit().putBoolean("autoRear",autoRear).apply(); updateRearUi() },
            "カメラを再接続" to { startProvider() },
            "純正カメラを開く" to { try {startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))} catch(e:Exception){message("純正カメラを起動できません")} },
            "端末診断" to { showDiagnostics() },
            "使い方・制限" to { android.app.AlertDialog.Builder(this).setTitle("Theta Camera Hatch ${BuildConfig.VERSION_NAME}").setMessage("写真・動画・スキャンの3つのボタンで撮影。動画中も写真を撮れます（端末が同時接続に対応している場合）。\n\n「操作を表示」でフラッシュ・タイマー・明るさ・水平・グリッド・写真モード・動画画質・レンズを出せます。\n\n開いたら自撮り：閉じた状態でこのアプリを開き、ほぼ全開にすると外画面への切替を要求します。OSの確認が出たら承認してください。録画・保存中は自動切替を保留します。\n\n写真と動画はDCIM/ThetaCamera、スキャンはDCIM/ThetaCamera/ScanとDocuments/ThetaCameraに保存。ピンチでズーム、タップでピント、音量キーで写真。\n\n50MP・HDR・望遠などはOSの公開範囲に依存します。Fold5実機での検証が必要です。").setPositiveButton("OK",null).show() },
            "アップデート（版 ${BuildConfig.VERSION_NAME}）" to { showUpdateMenu() })
        choose("設定",items.map { it.first }) { items[it].second(); refreshInfo() }
    }
    private fun startScan() {
        if(recording!=null) { message("録画中はスキャンできません"); return }
        val opts=com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
        com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { scanLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { message("スキャナーを起動できません（Google Play 開発者サービスが必要）：${it.message}") }
    }
    /** スキャン結果を DCIM/ThetaCamera/Scan（JPEG）と Documents/ThetaCamera（PDF）へ保存。ページ数を返す */
    private fun saveScan(res:com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult):Int {
        val base="Scan_"+SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
        fun copy(src:Uri, collection:Uri, name:String, mime:String, dir:String):Uri? {
            val dst=contentResolver.insert(collection,ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,name); put(MediaStore.MediaColumns.MIME_TYPE,mime); put(MediaStore.MediaColumns.RELATIVE_PATH,dir) }) ?: return null
            contentResolver.openInputStream(src)?.use { i -> contentResolver.openOutputStream(dst)?.use { o -> i.copyTo(o) } }
            return dst
        }
        val pages=res.pages ?: emptyList()
        var first:Uri?=null
        pages.forEachIndexed { n,pg -> val u=copy(pg.imageUri,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,"${base}_p${n+1}.jpg","image/jpeg","DCIM/ThetaCamera/Scan"); if(first==null) first=u }
        res.pdf?.let { copy(it.uri,MediaStore.Files.getContentUri("external"),"$base.pdf","application/pdf","Documents/ThetaCamera") }
        first?.let { runOnUiThread { saveLast(it,false) } }
        return pages.size
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
            }; bindCamera(); refreshInfo()
        }
    }
    private fun resetManual() {manualIso=null;manualTime=null;focusDistance=null;wb=CaptureRequest.CONTROL_AWB_MODE_AUTO}
    private fun characteristics():CameraCharacteristics? = runCatching {(getSystemService(CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(Camera2CameraInfo.from(camera!!.cameraInfo).cameraId)}.getOrNull()
    private fun showPro() {
        if(extensionMode!=ExtensionMode.NONE) {message("PRO調整は標準モードで使用してください");return}
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
        if(extensionMode!=ExtensionMode.NONE) return
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
        if(!::rearChip.isInitialized) return
        val active=rearSession!=null || areaStatus==WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE
        rearChip.text=when { active -> "内画面に戻る"; rearRequested -> "外画面へ切替中…"; else -> "外画面で自撮り" }
        setActive(rearChip,active)
        // 外画面に移っている間は、戻るボタンを常に出す
        rearChip.visibility=if(hudShown || active || rearRequested) View.VISIBLE else View.GONE
        rearChip.isEnabled=recording==null && !photoBusy && countDown==null && !rearRequested
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
        appendLine("Theta Camera Hatch ${BuildConfig.VERSION_NAME} — capability report")
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
            appendCameraDeep(manager,id,cc)
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
        appendDeviceDeep(manager)
        appendLine("No photos, videos, account details, or device serial included.")
    }
    /** 純正カメラ並みにできるかを決める値（50MP・8K・スロー・Samsung 独自キーなど） */
    private fun StringBuilder.appendCameraDeep(manager:CameraManager, id:String, cc:CameraCharacteristics) {
        val caps=cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val capNames=mapOf(1 to "MANUAL_SENSOR",2 to "MANUAL_POST_PROCESSING",3 to "RAW",4 to "PRIVATE_REPROCESSING",5 to "READ_SENSOR_SETTINGS",6 to "BURST_CAPTURE",7 to "YUV_REPROCESSING",8 to "DEPTH_OUTPUT",9 to "CONSTRAINED_HIGH_SPEED_VIDEO",10 to "MOTION_TRACKING",11 to "LOGICAL_MULTI_CAMERA",12 to "MONOCHROME",13 to "SECURE_IMAGE_DATA",14 to "SYSTEM_CAMERA",15 to "OFFLINE_PROCESSING",16 to "ULTRA_HIGH_RESOLUTION_SENSOR",17 to "REMOSAIC_REPROCESSING",18 to "DYNAMIC_RANGE_TEN_BIT",19 to "STREAM_USE_CASE",20 to "COLOR_SPACE_PROFILES")
        appendLine("Capabilities: ${caps.joinToString { capNames[it] ?: it.toString() }}")
        appendLine("Pixel array: ${cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}  active: ${cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)}")
        if(Build.VERSION.SDK_INT>=30) appendLine("Zoom ratio range: ${cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)}")
        if(Build.VERSION.SDK_INT>=31) runCatching {
            appendLine("Max-res pixel array: ${cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)}")
            val mr=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            appendLine("Max-res JPEG sizes: ${mr?.getOutputSizes(ImageFormat.JPEG)?.joinToString()}")
            appendLine("Max-res RAW sizes: ${mr?.getOutputSizes(ImageFormat.RAW_SENSOR)?.joinToString()}")
        }
        val map=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        appendLine("Largest recorder sizes: ${map?.getOutputSizes(android.media.MediaRecorder::class.java)?.sortedByDescending { it.width*it.height }?.take(4)?.joinToString()}")
        runCatching { map?.highSpeedVideoSizes?.forEach { sz -> appendLine("High-speed video $sz: ${map.getHighSpeedVideoFpsRangesFor(sz).joinToString()}") } }
        appendLine("Video stabilization modes: ${cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.joinToString()} (2=PREVIEW_STABILIZATION)")
        appendLine("Scene modes: ${cc.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.joinToString()}")
        appendLine("Face detect modes: ${cc.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.joinToString()}")
        if(Build.VERSION.SDK_INT>=33) runCatching { appendLine("10-bit profiles: ${cc.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles}") }
        if(Build.VERSION.SDK_INT>=31) runCatching {
            val ext=manager.getCameraExtensionCharacteristics(id).supportedExtensions
            val names=mapOf(0 to "AUTOMATIC",1 to "FACE_RETOUCH",2 to "BOKEH",3 to "HDR",4 to "NIGHT",5 to "EYES_FREE_VIDEOGRAPHY")
            appendLine("Camera2 extensions: ${ext.joinToString { names[it] ?: it.toString() }}")
        }
        cc.physicalCameraIds.forEach { pid -> runCatching {
            val pc=manager.getCameraCharacteristics(pid)
            appendLine("  physical $pid: focal ${pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()} mm, pixels ${pc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}${if(Build.VERSION.SDK_INT>=31) ", max-res ${pc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)}" else ""}")
        } }
        val vendor=cc.keys.map { it.name }.filter { !it.startsWith("android.") }
        appendLine("Vendor keys (${vendor.size}): ${vendor.sorted().joinToString()}")
    }
    private fun StringBuilder.appendDeviceDeep(manager:CameraManager) {
        appendLine("=== Device ===")
        if(Build.VERSION.SDK_INT>=30) runCatching { appendLine("Concurrent camera sets: ${manager.concurrentCameraIds}") }
        appendLine("Public IDs: ${manager.cameraIdList.joinToString()}")
        val hidden=(0..63).map(Int::toString).filter { it !in manager.cameraIdList && runCatching { manager.getCameraCharacteristics(it) }.isSuccess }
        appendLine("Hidden but readable IDs: ${hidden.ifEmpty { listOf("なし") }.joinToString()}")
        hidden.forEach { hid -> runCatching { val hc=manager.getCameraCharacteristics(hid)
            appendLine("  hidden $hid: facing ${hc.get(CameraCharacteristics.LENS_FACING)}, focal ${hc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()} mm, pixels ${hc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}") } }
        runCatching { val pm=packageManager; appendLine("Samsung camera: ${pm.getPackageInfo("com.sec.android.app.camera",0).versionName}") }
        appendLine("Build: ${Build.DISPLAY} / One UI(SEM_PLATFORM_INT): ${runCatching { Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null) }.getOrDefault(-1)}")
        appendLine()
    }
    private fun showDiagnostics() {
        val report=diagnosticText()
        val content=TextView(this).apply {text=report;textSize=12f;setPadding(dp(16),dp(12),dp(16),dp(12));setTextIsSelectable(true)}
        android.app.AlertDialog.Builder(this).setTitle("端末診断（対応状況）").setView(ScrollView(this).apply {addView(content)})
            .setPositiveButton("コピー"){_,_->(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Theta Camera診断",report));message("診断をコピーしました")}
            .setNeutralButton("共有"){_,_->startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"診断テキストを共有"))}
            .setNegativeButton("閉じる",null).show()
    }
    override fun onConfigurationChanged(newConfig:Configuration) {super.onConfigurationChanged(newConfig);root.post { arrangeUi() };preview.post {imageCapture?.targetRotation=preview.display.rotation;if(recording==null)videoCapture?.targetRotation=preview.display.rotation;tryAutoRear()} }
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
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {if(keyCode==KeyEvent.KEYCODE_VOLUME_UP || keyCode==KeyEvent.KEYCODE_VOLUME_DOWN){if(event.repeatCount==0)onPhoto();return true};return super.onKeyDown(keyCode,event)}
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean {if(keyCode==KeyEvent.KEYCODE_VOLUME_UP || keyCode==KeyEvent.KEYCODE_VOLUME_DOWN)return true;return super.onKeyUp(keyCode,event)}
}

private val C_BG = Color.rgb(15, 26, 21)
private val C_CHIP = Color.argb(215, 27, 45, 36)
private val C_TEXT = Color.rgb(228, 238, 220)
private val C_SUB = Color.rgb(176, 198, 182)
private val C_ACTIVE = Color.rgb(214, 234, 176)
private val C_ACTIVE_TEXT = Color.rgb(27, 42, 31)
private val C_REC = Color.rgb(242, 132, 122)
private val C_PHOTO = Color.rgb(241, 241, 233)

/** モックと同じ丸いシャッター：写真（白）・動画（赤、録画中は四角）・スキャン（濃い緑） */
class ShutterButton(context:Context, private val kind:Int):View(context) {
    companion object { const val PHOTO=0; const val VIDEO=1; const val SCAN=2 }
    var recording=false; set(v) { field=v; invalidate() }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable=true; isFocusable=true }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(c:Canvas) {
        val d=resources.displayMetrics.density; val cx=width/2f; val cy=height/2f; val r=minOf(width,height)/2f
        val a=if(isEnabled) 255 else 90
        if(kind==SCAN) {
            paint.style=Paint.Style.FILL; paint.color=Color.argb(a*230/255,27,45,36); c.drawCircle(cx,cy,r-d,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=2*d; paint.color=Color.argb(a,214,234,176)
            val w=r*0.42f; val h=r*0.56f; c.drawRoundRect(cx-w,cy-h,cx+w,cy+h,3*d,3*d,paint)
            c.drawLine(cx-w*0.55f,cy-h*0.3f,cx+w*0.55f,cy-h*0.3f,paint); c.drawLine(cx-w*0.55f,cy+h*0.1f,cx+w*0.55f,cy+h*0.1f,paint)
        } else {
            paint.style=Paint.Style.STROKE; paint.strokeWidth=3*d; paint.color=Color.argb(a*235/255,255,255,255); c.drawCircle(cx,cy,r-1.5f*d,paint)
            paint.style=Paint.Style.FILL; val col=if(kind==PHOTO) C_PHOTO else C_REC
            paint.color=Color.argb(a,Color.red(col),Color.green(col),Color.blue(col))
            if(kind==VIDEO && recording) { val s=r*0.42f; c.drawRoundRect(cx-s,cy-s,cx+s,cy+s,6*d,6*d,paint) } else c.drawCircle(cx,cy,r-7*d,paint)
        }
        if(isPressed) { paint.style=Paint.Style.FILL; paint.color=Color.argb(60,0,0,0); c.drawCircle(cx,cy,r,paint) }
    }
}

class GuideView(context:Context):View(context) {
    var grid=true;var level=false;var roll=0f;var focusX=-1f;var focusY=-1f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas) {
        super.onDraw(c);paint.style=Paint.Style.STROKE;paint.strokeWidth=resources.displayMetrics.density
        if(grid){paint.color=Color.argb(95,255,255,255);for(i in 1..2){c.drawLine(width*i/3f,0f,width*i/3f,height.toFloat(),paint);c.drawLine(0f,height*i/3f,width.toFloat(),height*i/3f,paint)}}
        if(level){val correction=((roll+45f)%90f+90f)%90f-45f;paint.color=if(abs(correction)<2)C_ACTIVE else Color.WHITE;c.save();c.rotate(-correction,width/2f,height/2f);c.drawLine(width*.35f,height/2f,width*.65f,height/2f,paint);c.restore()}
        if(focusX>=0){paint.color=C_ACTIVE;c.drawCircle(focusX,focusY,28*resources.displayMetrics.density,paint)}
    }
}
