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
class MainActivity : ComponentActivity(), SensorEventListener, app.thetahatch.adb.AdbTarget {
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
    private var aspect = "3:4"   // 3:4・9:16・1:1・フル（見えている範囲がそのまま保存される）
    private lateinit var stage: FrameLayout
    private lateinit var aspectChip: IconButton
    private var flexHingeTop = -1   // 半分折った Flex モードのとき、折り目の上端（px）
    private var burstActive = false
    private var burstCount = 0
    private var burstBase = ""
    private var ultraHdr = false
    private var rawJpeg = false
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var torch = false
    private var timerSeconds = 0
    private var quality = Quality.FHD
    private var videoHdr = false
    private var sixtyFps = false
    private var slowFps = 0          // 120・240 ならスローモーション（高速度撮影）
    private var sceneHdr = false     // Camera2 のシーンモード HDR
    private var lowLight = false     // 暗所ブースト（OS が対応していれば）
    private val voice by lazy { VoiceShutter(this) { if(recording==null) { showBanner("「はいチーズ」",800); onPhoto() } } }
    private var voiceOn = false
    private lateinit var voiceChip: IconButton
    private lateinit var thumbStrip: LinearLayout
    private lateinit var folderRow: LinearLayout
    private lateinit var stripBox: LinearLayout
    private var folders = mutableListOf<String>()
    private var saveFolder = "未整理"
    private var stripFilter: String? = null
    // 画面の向きは縦に固定し、端末を横にしたらアイコンだけ回す（純正カメラと同じ）
    private val rotatables = mutableListOf<View>()
    private var lockPortrait = true
    private var deviceAngle = 0
    private var uiAngle = 0f
    private var halfOpen = false
    private var flexLandscape = false
    private val recentErrors = ArrayDeque<String>()
    private var burstLimit = 100
    private var recordStartedAt = 0L
    private var heifClap = false     // 全体を HEIF で残し、画面の範囲を clap で表示
    private val meta by lazy { MetaStore(this) }
    private var currentEvent: String? = null
    private var selectedUri: Uri? = null
    private var searchQuery = ""
    private var starOnly = false
    private var videoName = ""
    private lateinit var eventBtn: TextView
    private lateinit var starBtn: IconButton
    private lateinit var searchBtn: IconButton
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
    private lateinit var flashChip: IconButton
    private lateinit var timerChip: IconButton
    private lateinit var evChip: IconButton
    private lateinit var levelChip: IconButton
    private lateinit var gridChip: IconButton
    private lateinit var modeChip: IconButton
    private lateinit var qualityChip: IconButton
    private lateinit var fpsChip: IconButton
    private lateinit var settingsChip: IconButton
    private lateinit var rearChip: IconButton
    private lateinit var hudToggle: IconButton
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
    private val presentOp = WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA
    private var presentStatus = WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
    private var coverSession: WindowAreaSessionPresenter? = null
    private var coverRequested = false
    private var coverImage: ImageView? = null
    private var coverShutter = false
    private lateinit var coverChip: IconButton
    private val mirror = object : Runnable { override fun run() {
        val img = coverImage ?: return
        preview.bitmap?.let { img.setImageBitmap(it) }
        handler.postDelayed(this, 66)
    } }
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
        lockPortrait = prefs.getBoolean("lockPortrait", true)
        heifClap = prefs.getBoolean("heifClap", false)
        applyOrientationLock()
        highResolution = prefs.getBoolean("highResolution", false)
        aspect = prefs.getString("aspect", "フル") ?: "フル"
        coverShutter = prefs.getBoolean("coverShutter", false)
        folders = (prefs.getString("folders", null)?.split("\n")?.filter { it.isNotBlank() } ?: listOf("家族写真","仕事写真","未整理")).toMutableList()
        saveFolder = prefs.getString("saveFolder", "未整理") ?: "未整理"
        if(saveFolder !in folders) folders.add(saveFolder)
        voiceOn = prefs.getBoolean("voice", false)
        currentEvent = prefs.getString("currentEvent", null)
        timerSeconds = prefs.getInt("timer", 0)
        audioEnabled = prefs.getBoolean("audio", true)
        lastUri = prefs.getString("lastUri", null)?.let(Uri::parse)
        lastVideo = prefs.getBoolean("lastVideo", false)
        buildUi()
        setupFold()
        autoCheckUpdate()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) return
                val q = when (angle) { in 45..134 -> 90; in 135..224 -> 180; in 225..314 -> 270; else -> 0 }
                if (q != deviceAngle) { deviceAngle = q; updateFlexOrientation(); rotateUi() }
                if (recording != null) return
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
    /** モノクロ・背景透明のアイコン。長押しで名前が出る */
    private fun icon(kind:String, desc:String, action:()->Unit) = IconButton(this,kind).apply {
        contentDescription=desc; tooltipText=desc; setOnClickListener { action() }; rotation=uiAngle; rotatables.add(this)
        layoutParams=LinearLayout.LayoutParams(dp(44),dp(44)).apply { setMargins(dp(1),0,dp(1),0) }
    }
    private fun hrow(vararg views:View) = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; views.forEach { addView(it) } }
    private fun vcol(g:Int, vararg views:View) = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=g; views.forEach { addView(it) } }
    private fun at(g:Int, l:Int=0, t:Int=0, r:Int=0, b:Int=0) = FrameLayout.LayoutParams(-2,-2,g).apply { setMargins(dp(l),dp(t),dp(r),dp(b)) }
    private fun shadowText(s:String, size:Float, color:Int=C_TEXT) = TextView(this).apply { text=s; textSize=size; setTextColor(color); gravity=Gravity.CENTER; setShadowLayer(6f,0f,1f,Color.BLACK) }
    private fun shutterItem(b:ShutterButton, text:String, size:Int, action:()->Unit):View {
        b.contentDescription=text; b.tooltipText=text; b.setOnClickListener { action() }
        return FrameLayout(this).apply { setPadding(dp(14),dp(6),dp(14),dp(6)); addView(b,FrameLayout.LayoutParams(dp(size),dp(size),Gravity.CENTER)) }
    }
    private val hideBanner=Runnable { if(::banner.isInitialized) banner.animate().alpha(0f).setDuration(250).withEndAction { banner.visibility=View.GONE }.start() }
    private fun showBanner(s:String, ms:Long) {
        if(!::banner.isInitialized) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); return }
        banner.animate().cancel(); banner.text=s; banner.alpha=1f; banner.visibility=View.VISIBLE
        handler.removeCallbacks(hideBanner); handler.postDelayed(hideBanner,ms)
    }
    private fun buildUi() {
        root=FrameLayout(this).apply { setBackgroundColor(C_BG) }
        preview=PreviewView(this).apply { implementationMode=PreviewView.ImplementationMode.COMPATIBLE; scaleType=PreviewView.ScaleType.FILL_CENTER; setBackgroundColor(C_BG) }
        overlay=GuideView(this).apply { grid=prefs.getBoolean("grid",false); level=prefs.getBoolean("level",false) }
        stage=FrameLayout(this)
        stage.addView(preview,FrameLayout.LayoutParams(-1,-1)); stage.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        root.addView(stage,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        root.addOnLayoutChangeListener { _,l,t,r,b,ol,ot,or2,ob -> if(r-l!=or2-ol || b-t!=ob-ot) layoutStage() }
        ui=FrameLayout(this); root.addView(ui,FrameLayout.LayoutParams(-1,-1))
        ui.setOnApplyWindowInsetsListener { v,ins ->
            val b=androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(ins).getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left+dp(8),b.top+dp(6),b.right+dp(8),b.bottom+dp(8)); ins
        }
        // 左上：操作（表示時のみ）・LIVE・レンズ
        flashChip=icon("flash","フラッシュ") { showFlash() }
        timerChip=icon("timer","セルフタイマー") { timerSeconds=listOf(0,3,5,10).let { it[(it.indexOf(timerSeconds)+1)%it.size] }; prefs.edit().putInt("timer",timerSeconds).apply(); refreshInfo() }
        evChip=icon("ev","明るさ") { evBar.visibility=if(evBar.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        aspectChip=icon("aspect","比率") { chooseAspect() }
        modeChip=icon("mode","写真モード") { showModes() }
        levelChip=icon("level","水平") { overlay.level=!overlay.level; prefs.edit().putBoolean("level",overlay.level).apply(); overlay.invalidate(); refreshInfo() }
        gridChip=icon("grid","グリッド") { overlay.grid=!overlay.grid; prefs.edit().putBoolean("grid",overlay.grid).apply(); overlay.invalidate(); refreshInfo() }
        qualityChip=icon("quality","動画の画質") { showVideoSettings() }
        voiceChip=icon("voice","声シャッター（はいチーズ）") { setVoice(!voiceOn) }
        fpsChip=icon("fps","フレームレート・スロー") { if(recording!=null) { message("録画中は変更できません"); return@icon }
            val opts=fpsOptions(); val cur=if(slowFps>0) slowFps else if(sixtyFps) 60 else 30
            val next=opts[(opts.indexOf(cur)+1)%opts.size]
            sixtyFps=next==60; slowFps=if(next>=120) next else 0
            if(slowFps>0) message("スローモーション ${slowFps}fps（写真・音声なし）")
            bindCamera(); refreshInfo() }
        hudRows=vcol(Gravity.START,
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(hrow(flashChip,timerChip,evChip,aspectChip,modeChip)) },
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(hrow(levelChip,gridChip,voiceChip,qualityChip,fpsChip)) })
        liveChip=shadowText("● LIVE",12f).apply { gravity=Gravity.START; setPadding(dp(10),dp(8),dp(10),dp(4)); letterSpacing=0.08f }
        lensInfo=shadowText("",11f,C_SUB).apply { gravity=Gravity.START; setPadding(dp(10),0,dp(10),dp(2)) }
        ui.addView(vcol(Gravity.START,hudRows,liveChip,lensInfo),at(Gravity.TOP or Gravity.START,r=56))
        // 右上：操作の表示切替・設定・外画面
        hudToggle=icon("hud","操作を表示／隠す") { setHud(!hudShown) }
        settingsChip=icon("settings","設定") { showSettings() }
        coverChip=icon("cover","外画面ライブ") { toggleCover() }
        rearChip=icon("rear","外画面で自撮り") { toggleRear() }
        ui.addView(vcol(Gravity.END,hudToggle,settingsChip,coverChip,rearChip),at(Gravity.TOP or Gravity.END))
        // 知らせ
        banner=TextView(this).apply { textSize=13f; setTextColor(Color.WHITE); background=round(Color.argb(150,0,0,0),16); setPadding(dp(16),dp(10),dp(16),dp(10)); gravity=Gravity.CENTER; maxWidth=dp(520); visibility=View.GONE; elevation=dp(4).toFloat() }
        ui.addView(banner,at(Gravity.TOP or Gravity.CENTER_HORIZONTAL,l=60,t=56,r=60))
        // 下：明るさ・レンズ・シャッター
        exposureSeek=SeekBar(this).apply { setOnSeekBarChangeListener(seekListener { camera?.let { c -> watch(c.cameraControl.setExposureCompensationIndex(it+c.cameraInfo.exposureState.exposureCompensationRange.lower),"露出変更"); handler.postDelayed({refreshInfo()},150) } }) }
        exposureSeek.progressTintList=android.content.res.ColorStateList.valueOf(Color.WHITE); exposureSeek.thumbTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
        exposureSeek.progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.argb(140,255,255,255))
        evBar=LinearLayout(this).apply { gravity=Gravity.CENTER; visibility=View.GONE; addView(exposureSeek,LinearLayout.LayoutParams(dp(260),dp(40))) }
        zoomPill=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(2),0,dp(2)) }
        listOf(0.6f to "0.6",1f to "1×",3f to "3").forEach { (z,t) ->
            val c=shadowText(t,12f).apply { typeface=Typeface.DEFAULT_BOLD; contentDescription="${t}倍"
                setOnClickListener { if(facing!=CameraSelector.LENS_FACING_BACK) { facing=CameraSelector.LENS_FACING_BACK; selectedId=null; resetManual(); bindCamera() }; zoomTo(z) }
                layoutParams=LinearLayout.LayoutParams(dp(40),dp(40)).apply { setMargins(dp(3),0,dp(3),0) } }
            zoomChips.add(z to c); rotatables.add(c); zoomPill.addView(c) }
        zoomPill.addView(icon("flip","前後のカメラを切り替え") { if(recording!=null) { message("録画中は切り替えられません"); return@icon }
            facing=if(facing==CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            selectedId=null; resetManual(); bindCamera() })
        photoBtn=ShutterButton(this,ShutterButton.PHOTO); videoBtn=ShutterButton(this,ShutterButton.VIDEO); scanBtn=ShutterButton(this,ShutterButton.SCAN)
        val p=shutterItem(photoBtn,"写真（長押しで連写）",62) { onPhoto() }
        val v=shutterItem(videoBtn,"動画",74) { onVideo() }
        val s=shutterItem(scanBtn,"スキャン（書類・QR）",50) { onScan() }
        photoBtn.setOnLongClickListener { startBurst(); true }
        photoBtn.setOnTouchListener { _,e -> if(e.actionMasked==MotionEvent.ACTION_UP || e.actionMasked==MotionEvent.ACTION_CANCEL) burstActive=false; false }
        shutterBar=LinearLayout(this).apply { gravity=Gravity.CENTER; addView(p); addView(v); addView(s) }
        thumbStrip=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        folderRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        eventBtn=shadowText("",12f).apply { setPadding(dp(14),0,dp(14),0); minHeight=dp(36); maxWidth=dp(260); isSingleLine=true; ellipsize=android.text.TextUtils.TruncateAt.END
            background=GradientDrawable().apply { cornerRadius=dp(18).toFloat(); setColor(Color.argb(60,0,0,0)); setStroke(dp(1),Color.argb(150,255,255,255)) }
            contentDescription="イベント"; setOnClickListener { showEvents() } }
        starBtn=icon("star","☆ お気に入り（選んだ写真）") { toggleStar() }
        val infoBtn=icon("info","詳細・コメント・タグ（選んだ写真）") { showDetail() }
        searchBtn=icon("search","探す（コメント・タグ・イベント・☆）") { showSearch() }
        stripBox=vcol(Gravity.START,
            hrow(eventBtn,starBtn,infoBtn,searchBtn),
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(thumbStrip) },
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(folderRow) })
        bottomBox=vcol(Gravity.CENTER_HORIZONTAL,stripBox,evBar,zoomPill)
        ui.addView(bottomBox,at(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        lastThumb=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(Color.argb(60,0,0,0)); setStroke(dp(2),Color.WHITE) }; clipToOutline=true; setPadding(dp(2),dp(2),dp(2),dp(2)); contentDescription="直前の写真・動画を開く"; setOnClickListener { openLast() } }
        rotatables.add(lastThumb); rotatables.add(scanBtn)
        ui.addView(lastThumb,FrameLayout.LayoutParams(dp(46),dp(46),Gravity.BOTTOM or Gravity.START).apply { bottomMargin=dp(20); leftMargin=dp(6) })
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
        hudRows.visibility=vis; lensInfo.visibility=vis; coverChip.visibility=vis; settingsChip.visibility=vis; zoomPill.visibility=vis; stripBox.visibility=vis; lastThumb.visibility=if(show) View.GONE else View.VISIBLE
        if(show) { refreshStrip(); buildFolderRow(); refreshInfoBar() }
        if(!show) evBar.visibility=View.GONE
        hudToggle.active=show
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
        if(::thumbStrip.isInitialized && hudShown) { refreshStrip(); refreshInfoBar() }
    }
    private fun saveDir()="DCIM/ThetaCamera/$saveFolder"
    /** 選んだ写真（無ければ直前に撮ったもの） */
    private fun target():Uri? = selectedUri ?: lastUri
    private fun refreshInfoBar() {
        if(!::eventBtn.isInitialized) return
        eventBtn.text=meta.event(currentEvent)?.let { "◆ "+meta.label(it) } ?: "イベントなし"
        starBtn.active=target()?.let { meta.get(it)?.favorite }==true; starBtn.variant=if(starBtn.active) "on" else "off"
        searchBtn.active=searchQuery.isNotBlank()||starOnly
    }
    private fun toggleStar() {
        val u=target() ?: run { message("先に写真を撮るか、下のサムネイルを選んでください"); return }
        val m=meta.ensure(u,saveFolder) ?: run { message("この写真には付けられません"); return }
        m.favorite=!m.favorite; meta.changed(m)
        showBanner(if(m.favorite) "☆ お気に入りにしました" else "お気に入りを外しました",1200)
        refreshInfoBar(); refreshStrip()
    }
    /** イベント：撮影中に作る・選ぶ。選んだあとに撮るものに付く（CAM-018・019） */
    private fun showEvents() {
        val items=mutableListOf<Pair<String,()->Unit>>("イベントなし（記録だけ）" to { setEvent(null) })
        meta.events.sortedByDescending { it.start }.forEach { e -> items+=(if(e.id==currentEvent) "● " else "")+meta.label(e) to { setEvent(e.id) } }
        items+="＋ 新しいイベント" to { newEvent() }
        target()?.let { u -> meta.get(u)?.let { m -> if(currentEvent!=null && m.eventId!=currentEvent) items+="選んだ写真を今のイベントに入れる" to { m.eventId=currentEvent; meta.changed(m); message("イベントに入れました") }
            if(m.eventId!=null) items+="選んだ写真をイベントから外す（写真は消えません）" to { m.eventId=null; meta.changed(m); message("イベントから外しました") } } }
        choose("イベント",items.map { it.first }) { items[it].second(); refreshInfoBar() }
    }
    private fun setEvent(id:String?) { currentEvent=id; prefs.edit().putString("currentEvent",id).apply(); refreshInfoBar()
        meta.event(id)?.let { showBanner("これから撮るものは「${meta.label(it)}」に入ります",1800) } }
    private fun newEvent() {
        val today=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
        val name=EditText(this).apply { hint="名前（例：レゴランド）"; isSingleLine=true }
        val start=EditText(this).apply { setText(today); hint="開始日 2026-11-09"; isSingleLine=true }
        val end=EditText(this).apply { setText(today); hint="終了日 2026-11-10"; isSingleLine=true }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),0); addView(name); addView(start); addView(end) }
        android.app.AlertDialog.Builder(this).setTitle("新しいイベント").setView(box)
            .setPositiveButton("作って使う") { _,_ ->
                val ok=Regex("\\d{4}-\\d{2}-\\d{2}")
                val n=name.text.toString().trim(); val a=start.text.toString().trim(); val b=end.text.toString().trim()
                if(n.isEmpty() || !ok.matches(a) || !ok.matches(b)) { message("名前と日付（2026-11-09 の形）を入れてください"); return@setPositiveButton }
                val e=meta.addEvent(n,minOf(a,b),maxOf(a,b)); setEvent(e.id)
                // 指定する前に撮った、期間内でイベントの無い記録を、範囲を示して後から入れる
                val fmt=SimpleDateFormat("yyyy-MM-dd",Locale.US)
                val before=meta.all().filter { it.eventId==null && fmt.format(Date(it.created)) in e.start..e.end }
                if(before.isNotEmpty()) android.app.AlertDialog.Builder(this).setTitle("前に撮ったものも入れますか？")
                    .setMessage("${e.start}〜${e.end} に撮った、イベントの無い ${before.size} 件をこのイベントに入れます。")
                    .setPositiveButton("入れる") { _,_ -> before.forEach { m -> m.eventId=e.id; meta.changed(m) }; message("${before.size} 件を入れました") }
                    .setNegativeButton("入れない",null).show()
            }.setNegativeButton("やめる",null).show()
    }
    /** 詳細：コメント・タグ・☆（CAM-030・031） */
    private fun showDetail() {
        val u=target() ?: run { message("先に写真を撮るか、下のサムネイルを選んでください"); return }
        val m=meta.ensure(u,saveFolder) ?: run { message("この写真には付けられません"); return }
        val comment=EditText(this).apply { setText(m.comment); hint="コメント（例：長女が初めてジェットコースターに乗ったよ）"; minLines=2 }
        val tags=EditText(this).apply { setText(m.tags.joinToString(" ")); hint="タグ（空白で区切る：長女 初めて）"; isSingleLine=true }
        val star=CheckBox(this).apply { text="☆ お気に入り"; isChecked=m.favorite }
        val info=TextView(this).apply { textSize=12f; text="${m.name}\nフォルダ：${m.folder}　イベント：${meta.event(m.eventId)?.let { meta.label(it) } ?: "なし"}" }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),0); addView(info); addView(comment); addView(tags); addView(star) }
        android.app.AlertDialog.Builder(this).setTitle("詳細").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _,_ ->
                m.comment=comment.text.toString().trim(); m.favorite=star.isChecked
                m.tags.clear(); m.tags.addAll(tags.text.toString().split(' ','　',',','、').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct())
                meta.changed(m); refreshInfoBar(); refreshStrip(); showBanner("保存しました",1000)
            }.setNegativeButton("閉じる",null).show()
    }
    /** 探す：コメント・タグ・イベント名・フォルダで絞る、☆だけ */
    private fun showSearch() {
        val q=EditText(this).apply { setText(searchQuery); hint="例：ジェットコースター 長女"; isSingleLine=true }
        val star=CheckBox(this).apply { text="☆ だけ"; isChecked=starOnly }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),0); addView(q); addView(star) }
        android.app.AlertDialog.Builder(this).setTitle("探す").setView(box)
            .setPositiveButton("探す") { _,_ -> searchQuery=q.text.toString().trim(); starOnly=star.isChecked; refreshStrip(); refreshInfoBar() }
            .setNeutralButton("すべて表示") { _,_ -> searchQuery=""; starOnly=false; refreshStrip(); refreshInfoBar() }
            .setNegativeButton("閉じる",null).show()
    }
    /** 撮った写真・動画を丸いサムネイルで並べる（新しい順、最大 20）。フォルダを押すとそのフォルダだけ */
    private fun refreshStrip() {
        val filter=stripFilter
        lifecycleScope.launch {
            val items=withContext(Dispatchers.IO) { runCatching {
                val uri=MediaStore.Files.getContentUri("external")
                val sel="${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3)"
                val arg=if(filter==null) "DCIM/ThetaCamera/%" else "DCIM/ThetaCamera/$filter/%"
                contentResolver.query(uri,arrayOf(MediaStore.MediaColumns._ID,MediaStore.Files.FileColumns.MEDIA_TYPE),sel,arrayOf(arg),"${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    val out=mutableListOf<Pair<Uri,Boolean>>()
                    while(c.moveToNext() && out.size<20) { val video=c.getInt(1)==3
                        out+=(android.content.ContentUris.withAppendedId(if(video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,c.getLong(0)) to video) }
                    out
                } ?: emptyList()
            }.getOrDefault(emptyList()) }
            thumbStrip.removeAllViews()
            items.filter { meta.matches(meta.get(it.first),searchQuery,starOnly) }.forEach { (u,v) ->
                val t=ThumbView(this@MainActivity,v).apply { rotation=uiAngle; contentDescription=if(v) "動画（押すと選ぶ、もう一度で開く）" else "写真（押すと選ぶ、もう一度で開く）"
                    favorite=meta.get(u)?.favorite==true; selected2=u==selectedUri
                    setOnClickListener { if(selectedUri==u) openUri(u,v) else { selectedUri=u; refreshStrip(); refreshInfoBar() } } }
                thumbStrip.addView(t,LinearLayout.LayoutParams(dp(46),dp(46)).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) })
                lifecycleScope.launch { t.setImageBitmap(withContext(Dispatchers.IO) { runCatching { contentResolver.loadThumbnail(u,android.util.Size(dp(46),dp(46)),null) }.getOrNull() }) }
            }
        }
    }
    /** 保存先フォルダ。短く押す＝一覧をそのフォルダに絞る、長押し＝以後の保存先にする（CAM-020） */
    private fun buildFolderRow() {
        folderRow.removeAllViews()
        folders.forEach { f ->
            val cur=f==saveFolder; val filtered=f==stripFilter
            folderRow.addView(shadowText(if(cur) "● $f" else f,12f).apply {
                setPadding(dp(12),0,dp(12),0); minHeight=dp(36)
                background=GradientDrawable().apply { cornerRadius=dp(18).toFloat(); setColor(if(filtered) Color.argb(110,255,255,255) else Color.argb(40,0,0,0)); setStroke(dp(1),if(cur) Color.WHITE else Color.argb(120,255,255,255)) }
                contentDescription="$f（長押しで保存先にする）"
                setOnClickListener { stripFilter=if(stripFilter==f) null else f; buildFolderRow(); refreshStrip() }
                setOnLongClickListener { saveFolder=f; prefs.edit().putString("saveFolder",f).apply(); showBanner("これから撮るものは「$f」に保存${if(recording!=null) "（録画中の動画は開始時のフォルダ）" else ""}",2000); buildFolderRow(); true }
            },LinearLayout.LayoutParams(-2,dp(36)).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) })
        }
        folderRow.addView(icon("plus","フォルダを追加") { addFolder() })
    }
    private fun addFolder() {
        val edit=EditText(this).apply { hint="フォルダ名（例：家族写真）"; isSingleLine=true }
        android.app.AlertDialog.Builder(this).setTitle("フォルダを追加").setView(FrameLayout(this).apply { setPadding(dp(20),dp(8),dp(20),0); addView(edit) })
            .setPositiveButton("追加") { _,_ -> val n=edit.text.toString().trim().replace("/","・")
                if(n.isNotEmpty() && n !in folders) { folders.add(n); prefs.edit().putString("folders",folders.joinToString("\n")).apply(); buildFolderRow() } }
            .setNegativeButton("やめる",null).show()
    }
    private fun openUri(u:Uri,video:Boolean) { runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u,if(video) "video/*" else "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { message("表示できません") } }
    private fun setVoice(on:Boolean) {
        if(on && !voice.available()) { message("この端末では音声認識が使えません"); return }
        if(on && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); return }
        voiceOn=on; prefs.edit().putBoolean("voice",on).apply()
        if(on && recording==null) voice.start() else voice.stop()
        if(on) showBanner("「はいチーズ」で写真を撮ります",1800)
        refreshInfo()
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
            if(slowFps>0) extensionMode=ExtensionMode.NONE
            if(extensionMode!=ExtensionMode.NONE) {
                if(extensions?.isExtensionAvailable(selector,extensionMode)==true) selector=extensions!!.getExtensionEnabledCameraSelector(selector,extensionMode)
                else { extensionMode=ExtensionMode.NONE; message("このレンズでは選択した拡張に非対応です。標準で撮影します") }
            }
            val ratio=if(tallAspect()) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val resolution=ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
                .setAllowedResolutionMode(if(highResolution) ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE else ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                .setResolutionStrategy(if(highResolution) ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY else ResolutionStrategy(android.util.Size(4000,3000),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
            val pb=Preview.Builder().setTargetRotation(preview.display?.rotation ?: Surface.ROTATION_0)
            pb.setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            // 強い手ぶれ補正（プレビューと動画の両方）。対応していなければ動画だけの補正
            val previewStab=stabilization && slowFps==0 && runCatching { Preview.getPreviewCapabilities(baseInfo).isStabilizationSupported }.getOrDefault(false)
            if(previewStab) pb.setPreviewStabilizationEnabled(true)
            val pv=pb.build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            if(slowFps>0) {
                // スローモーション：高速度撮影の専用セッション（写真は撮れない）
                val hs=Recorder.getHighSpeedVideoCapabilities(baseInfo) ?: error("このレンズはスローモーション非対応です")
                val hq=hs.getSupportedQualities(DynamicRange.SDR)
                val q=hq.firstOrNull { it==Quality.FHD } ?: hq.firstOrNull() ?: error("このレンズはスローモーション非対応です")
                val vc=VideoCapture.Builder(Recorder.Builder().setQualitySelector(QualitySelector.from(q)).build()).setTargetRotation(preview.display?.rotation ?: Surface.ROTATION_0).build()
                videoCapture=vc; imageCapture=null
                camera=p.bindToLifecycle(this,selector,HighSpeedVideoSessionConfig.Builder(vc).setPreview(pv).setFrameRateRange(Range(slowFps,slowFps)).setSlowMotionEnabled(true).build())
            } else {
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
                    if(stabilization && !previewStab && vcaps.isStabilizationSupported) vb.setVideoStabilizationEnabled(true)
                    videoCapture=vb.build()
                }
            }
            val ic=imageCapture; val vc=videoCapture
            // 画面に見えている範囲（比率）で切り出して保存する
            // HEIF（全体を残す）のときは切り出さない。表示範囲は clap で書く
            val vp=if(heifClap) null else preview.viewPort
            fun bind(vararg uc:UseCase):Camera = if(vp!=null) p.bindToLifecycle(this,selector,UseCaseGroup.Builder().setViewPort(vp).apply { uc.forEach { addUseCase(it) } }.build()) else p.bindToLifecycle(this,selector,*uc)
            camera=when {
                ic!=null && vc!=null -> try { bind(pv,ic,vc) } catch(e:IllegalArgumentException) {
                    // 写真と動画の同時接続に非対応：写真だけで接続し、動画ボタンで動画用に接続し直す
                    lastError="写真と動画の同時接続に非対応: ${e.message}"; videoCapture=null; p.unbindAll(); bind(pv,ic) }
                ic!=null -> bind(pv,ic)
                vc!=null -> bind(pv,vc)
                else -> error("接続できる撮影機能がありません")
            }
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
                if(lowLight) watch(c.cameraControl.enableLowLightBoostAsync(true),"暗所ブースト")
            }
            setBusy(false); refreshInfo()
            if(pendingVideo) { pendingVideo=false; onVideo() } else tryAutoRear()
        } catch(e:Exception) {
            lastError="バインド失敗: ${e.message}"
            if(!fallback) {
                extensionMode=ExtensionMode.NONE; videoOnly=false; slowFps=0; sceneHdr=false; lowLight=false; highResolution=false; ultraHdr=false; rawJpeg=false; videoHdr=false; sixtyFps=false; stabilization=false; quality=Quality.FHD; selectedId=null; facing=CameraSelector.LENS_FACING_BACK; resetManual()
                message("この撮影設定を利用できないため、標準設定で再接続します")
                bindCamera(true)
            } else { pendingVideo=false; fail("カメラ接続（設定の端末診断を確認してください）",e) }
        }
    }
    private fun fail(where:String,e:Exception) { lastError="$where: ${e.cause?.message ?: e.message}"; noteError(lastError); message(lastError) }
    private fun noteError(s:String) { android.util.Log.e(app.thetahatch.adb.AdbBridge.TAG,s); recentErrors.addLast("${SimpleDateFormat("HH:mm:ss",Locale.US).format(Date())} $s"); while(recentErrors.size>10) recentErrors.removeFirst() }
    private fun lensName(z:Float)=when { facing==CameraSelector.LENS_FACING_FRONT -> "インカメラ"; z<0.95f -> "超広角"; z>=2.5f -> "望遠"; else -> "広角" }
    private fun refreshInfo() {
        if(!::liveChip.isInitialized) return
        val z=camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        flashChip.variant=when { torch -> "torch"; flash==ImageCapture.FLASH_MODE_AUTO -> "auto"; flash==ImageCapture.FLASH_MODE_ON -> "on"; else -> "off" }
        flashChip.active=flash!=ImageCapture.FLASH_MODE_OFF||torch
        timerChip.badge=if(timerSeconds==0) null else "$timerSeconds"; timerChip.active=timerSeconds>0
        val ev=camera?.cameraInfo?.exposureState
        val evValue=ev?.let { it.exposureCompensationIndex*it.exposureCompensationStep.toFloat() } ?: 0f
        evChip.badge=if(abs(evValue)<0.05f) null else String.format(Locale.US,"%+.1f",evValue); evChip.active=abs(evValue)>=0.05f
        levelChip.active=overlay.level; gridChip.active=overlay.grid
        voiceChip.variant=if(voiceOn) "on" else "off"; voiceChip.active=voiceOn
        modeChip.badge=when { rawJpeg -> "RAW"; ultraHdr -> "UHDR"; sceneHdr -> "HDR"; lowLight -> "暗所"; else -> when(extensionMode) { ExtensionMode.AUTO->"AUTO"; ExtensionMode.HDR->"HDR"; ExtensionMode.NIGHT->"夜景"; ExtensionMode.BOKEH->"ぼかし"; ExtensionMode.FACE_RETOUCH->"美肌"; else->null } }
        modeChip.active=modeChip.badge!=null
        qualityChip.variant=qualityName(quality).replace("1080p","FHD").replace("720p","HD"); qualityChip.badge=if(videoHdr) "HDR" else null; qualityChip.active=videoHdr
        fpsChip.variant=if(slowFps>0) "$slowFps" else if(sixtyFps) "60" else "30"; fpsChip.badge=if(slowFps>0) "SLOW" else null; fpsChip.active=sixtyFps||slowFps>0
        aspectChip.variant=aspect
        val res=imageCapture?.resolutionInfo?.resolution
        lensInfo.text="${lensName(z)} · ${String.format(Locale.US,"%.1f×",z)}${res?.let { "  ·  ${it.width}×${it.height}" } ?: ""}"
        val near=zoomChips.minByOrNull { abs(it.first-z) }?.first
        zoomChips.forEach { (v,c) -> val on=v==near && facing==CameraSelector.LENS_FACING_BACK
            c.background=if(on) GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(Color.argb(70,0,0,0)); setStroke(dp(1),Color.WHITE) } else null
            c.text=if(on) String.format(Locale.US,"%.1f×",z).replace(".0×","×") else when(v) { 0.6f -> "0.6"; 1f -> "1"; else -> "3" } }
        if(recording==null) { liveChip.text="● LIVE"; liveChip.setTextColor(C_TEXT) }
        videoBtn.recording=recording!=null; videoBtn.contentDescription=if(recording!=null) "録画を停止" else "動画"
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
        if(imageCapture==null) { message(if(slowFps>0) "スローモーション中は写真を撮れません（fps を 30 に戻すと撮れます）" else "この端末では録画中の写真に対応していません"); return }
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
            put(MediaStore.MediaColumns.DISPLAY_NAME,basename+ext);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.RELATIVE_PATH,saveDir())
        }).build()
        val pair=cap.outputFormat==ImageCapture.OUTPUT_FORMAT_RAW_JPEG
        if(heifClap && !pair) { takeHeif(cap,basename); return }
        var saved=0; var failed=false
        fun finish() {photoBusy=false;setBusy(recording!=null);refreshInfo();tryAutoRear()}
        val callback=object:ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                if(result.imageFormat==ImageFormat.JPEG || !pair) { result.savedUri?.let { meta.create(it,basename+".jpg",saveFolder,currentEvent) }; saveLast(result.savedUri,false); updateThumb() }
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

    /**
     * 写真を全体（センサーの画角いっぱい）で HEIF に保存し、画面に見えている範囲を clap で付ける。
     * 対応したビューアは画面どおりに表示し、画素は全部残るので後から広げられる。失敗したら JPEG のまま保存する。
     */
    private fun takeHeif(cap:ImageCapture, basename:String) {
        val viewW=stage.width; val viewH=stage.height
        val dispRot=runCatching { camera!!.cameraInfo.getSensorRotationDegrees(preview.display?.rotation ?: Surface.ROTATION_0) }.getOrDefault(90)
        val folder=saveFolder; val event=currentEvent; val dir=saveDir()
        fun done() { photoBusy=false; setBusy(recording!=null); refreshInfo(); tryAutoRear() }
        try {
            cap.takePicture(main,object:ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(img:ImageProxy) {
                    val rot=img.imageInfo.rotationDegrees; val w=img.width; val h=img.height
                    val buf=img.planes[0].buffer; val jpeg=ByteArray(buf.remaining()).also { buf.get(it) }; img.close()
                    val (fw,fh)=HeifClap.visibleFraction(w,h,dispRot,maxOf(viewW,1),maxOf(viewH,1))
                    val cw=((w*fw).roundToInt()/2*2).coerceIn(2,w); val ch=((h*fh).roundToInt()/2*2).coerceIn(2,h)
                    lifecycleScope.launch {
                        val r=withContext(Dispatchers.IO) { runCatching {
                            val bmp=BitmapFactory.decodeByteArray(jpeg,0,jpeg.size) ?: error("画像を読めません")
                            val tmp=java.io.File(cacheDir,"$basename.heic"); tmp.delete()
                            val wr=androidx.heifwriter.HeifWriter.Builder(tmp.absolutePath,bmp.width,bmp.height,androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP)
                                .setQuality(95).setRotation(rot).build()
                            wr.start(); wr.addBitmap(bmp); wr.stop(15000); wr.close(); bmp.recycle()
                            val out=HeifClap.insert(tmp.readBytes(),cw,ch); tmp.delete()
                            writeImage(out,"$basename.heic","image/heic",dir) to "$basename.heic"
                        }.recoverCatching { e ->
                            noteError("HEIF 保存に失敗、JPEG で保存: ${e.message}")
                            writeImage(jpeg,"$basename.jpg","image/jpeg",dir) to "$basename.jpg"
                        } }
                        r.onSuccess { (uri,nm) -> uri?.let { meta.create(it,nm,folder,event).also { m -> m.crop="${cw}x${ch} / ${w}x${h}"; meta.changed(m) } }
                            saveLast(uri,false); updateThumb(); showBanner(if(nm.endsWith(".heic")) "写真を保存しました（全体＋表示範囲）" else "写真を保存しました（JPEG）",1500) }
                            .onFailure { fail("写真保存",Exception(it)) }
                        done()
                    }
                }
                override fun onError(e:ImageCaptureException) { done(); fail("写真撮影",e) }
            })
        } catch(e:Exception) { done(); fail("写真撮影",e) }
    }
    private fun writeImage(bytes:ByteArray, name:String, mime:String, dir:String):Uri? {
        val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name); put(MediaStore.MediaColumns.MIME_TYPE,mime); put(MediaStore.MediaColumns.RELATIVE_PATH,dir)
            put(MediaStore.Images.Media.DATE_TAKEN,System.currentTimeMillis()); put(MediaStore.MediaColumns.IS_PENDING,1) }) ?: return null
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        contentResolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
        return uri
    }

    private fun startRecording() {
        val cap=videoCapture ?: return
        try {
            videoName=name()+".mp4"; val vFolder=saveFolder; val vEvent=currentEvent
            val values=ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,videoName); put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4"); put(MediaStore.MediaColumns.RELATIVE_PATH,saveDir()) }
            val output=MediaStoreOutputOptions.Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
            var pending=cap.output.prepareRecording(this,output)
            val withAudio=audioEnabled && slowFps==0 && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
            if(withAudio) pending=pending.withAudioEnabled()
            recording=pending.start(main) { event ->
                when(event) {
                    is VideoRecordEvent.Start -> { recordStartedAt=System.currentTimeMillis(); voice.stop(); refreshInfo(); setBusy(true); showBanner("${lensName(camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f)} · 録画開始${if(withAudio) "" else "（無音）"}",1500) }
                    is VideoRecordEvent.Status -> { val sec=event.recordingStats.recordedDurationNanos/1_000_000_000
                        liveChip.text="● REC ${sec/60}:${(sec%60).toString().padStart(2,'0')}"; liveChip.setTextColor(C_REC) }
                    is VideoRecordEvent.Finalize -> {
                        if(voiceOn) voice.start()
                        recording=null; videoBtn.isEnabled=true; setBusy(false)
                        if(!event.hasError()) { meta.create(event.outputResults.outputUri,videoName,vFolder,vEvent); saveLast(event.outputResults.outputUri,true); updateThumb(); showBanner("動画を保存しました",1500) }
                        else {lastError="動画終了エラー ${event.error}: ${event.cause?.message}"; noteError(lastError); message(lastError)}
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
        val ext=listOf(ExtensionMode.AUTO,ExtensionMode.HDR,ExtensionMode.NIGHT,ExtensionMode.BOKEH,ExtensionMode.FACE_RETOUCH).filter { runCatching {extensions?.isExtensionAvailable(baseSelector(),it)==true}.getOrDefault(false) }
        val items=mutableListOf<Pair<String,()->Unit>>("標準" to { extensionMode=ExtensionMode.NONE; sceneHdr=false; lowLight=false })
        if(characteristics()?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.contains(CaptureRequest.CONTROL_SCENE_MODE_HDR)==true)
            items+="HDR（明暗差の大きい場面）" to { extensionMode=ExtensionMode.NONE; sceneHdr=true; lowLight=false }
        if(camera?.cameraInfo?.isLowLightBoostSupported==true) items+="暗所ブースト（暗い場所を明るく）" to { extensionMode=ExtensionMode.NONE; sceneHdr=false; lowLight=true }
        ext.forEach { m -> items+=modeName(m) to { extensionMode=m; sceneHdr=false; lowLight=false; slowFps=0 } }
        choose("写真モード",items.map { it.first }) { items[it].second(); resetManual(); bindCamera(); refreshInfo() }
    }
    /** 選べるフレームレート：30、通常で 60 があれば 60、高速度撮影があれば 120・240（スロー） */
    private fun fpsOptions():List<Int> {
        val cc=characteristics() ?: return listOf(30)
        val out=mutableListOf(30)
        if(cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.any { it.lower==60 && it.upper==60 }==true) out+=60
        runCatching { cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.highSpeedVideoFpsRanges?.filter { it.lower==it.upper && it.upper>=120 }?.map { it.upper }?.distinct()?.sorted()?.let { out+=it } }
        return out
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
            "比率：$aspect" to { chooseAspect() },
            "写真の保存形式：${if(heifClap) "HEIF（全体を残し、画面の範囲を表示）" else "JPEG（画面どおりに切る）"}" to { heifClap=!heifClap; prefs.edit().putBoolean("heifClap",heifClap).apply(); bindCamera()
                showBanner(if(heifClap) "写真は全体を残し、画面の範囲を表示範囲として記録します（連写・RAW は JPEG）" else "写真は画面どおりに切って JPEG で保存します",3500) },
        ).toMutableList()
        val formats=runCatching { ImageCapture.getImageCaptureCapabilities(camera!!.cameraInfo).supportedOutputFormats }.getOrDefault(emptySet())
        if(formats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)) items.add(6,"Ultra HDR写真：${if(ultraHdr) "ON" else "OFF"}" to { ultraHdr=!ultraHdr; if(ultraHdr) rawJpeg=false; bindCamera() })
        if(formats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)) items.add(6,"RAW+JPEG：${if(rawJpeg) "ON" else "OFF"}" to { rawJpeg=!rawJpeg; if(rawJpeg){ultraHdr=false;extensionMode=ExtensionMode.NONE;sceneHdr=false}; bindCamera() })
        items.addAll(listOf<Pair<String,()->Unit>>(
            "外画面ライブにシャッターも出す：${if(coverShutter) "ON" else "OFF"}" to { coverShutter=!coverShutter; prefs.edit().putBoolean("coverShutter",coverShutter).apply(); if(coverSession!=null) { stopCover(); startCover() } },
            "開いたら外画面で自撮り：${if(autoRear) "ON" else "OFF"}" to { autoRear=!autoRear; pendingAutoRear=false; prefs.edit().putBoolean("autoRear",autoRear).apply(); updateRearUi() },
            "画面の向き：${if(lockPortrait) "縦に固定（アイコンだけ回る）" else "端末に合わせて回る"}" to { lockPortrait=!lockPortrait; prefs.edit().putBoolean("lockPortrait",lockPortrait).apply(); flexLandscape=false; applyOrientationLock(); rotateUi() },
            "カメラを再接続" to { startProvider() },
            "純正カメラを開く" to { try {startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))} catch(e:Exception){message("純正カメラを起動できません")} },
            "端末診断" to { showDiagnostics() },
            "使い方・制限" to { android.app.AlertDialog.Builder(this).setTitle("Theta Camera Hatch ${BuildConfig.VERSION_NAME}").setMessage("写真・動画・スキャンの3つのボタンで撮影。動画中も写真を撮れます（端末が同時接続に対応している場合）。\n\n「操作を表示」でフラッシュ・タイマー・明るさ・水平・グリッド・写真モード・動画画質・レンズを出せます。\n\n開いたら自撮り：閉じた状態でこのアプリを開き、ほぼ全開にすると外画面への切替を要求します。OSの確認が出たら承認してください。録画・保存中は自動切替を保留します。\n\n写真と動画はDCIM/ThetaCamera、スキャンはDCIM/ThetaCamera/ScanとDocuments/ThetaCameraに保存。ピンチでズーム、タップでピント、音量キーで写真。\n\n50MP・HDR・望遠などはOSの公開範囲に依存します。Fold5実機での検証が必要です。").setPositiveButton("OK",null).show() },
            "アップデート（版 ${BuildConfig.VERSION_NAME}）" to { showUpdateMenu() }))
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
        if(sceneHdr && slowFps==0) b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_USE_SCENE_MODE).setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE,CaptureRequest.CONTROL_SCENE_MODE_HDR)
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
                        presentStatus=areaInfo?.getCapability(presentOp)?.status ?: WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
                        updateRearUi(); tryAutoRear()
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                        updateFlex(info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull())
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
        rearChip.contentDescription=when { active -> "内画面に戻る"; rearRequested -> "外画面へ切替中"; else -> "外画面で自撮り" }; rearChip.tooltipText=rearChip.contentDescription
        rearChip.active=active
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
        stopCover()
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
        appendLine("Cover present (dual preview): $presentStatus / session=${coverSession!=null} (UNAVAILABLE は閉じているときなど一時的に使えない、UNSUPPORTED は非対応)")
        camera?.cameraInfo?.let { ci -> appendLine("Low light boost: ${ci.isLowLightBoostSupported}; torch strength: ${ci.isTorchStrengthSupported}; preview stabilization: ${runCatching { Preview.getPreviewCapabilities(ci).isStabilizationSupported }.getOrNull()}")
            appendLine("High-speed qualities: ${runCatching { Recorder.getHighSpeedVideoCapabilities(ci)?.getSupportedQualities(DynamicRange.SDR)?.joinToString { qualityName(it) } }.getOrNull()}") }
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
    /** 外画面ライブ（純正のデュアルプレビュー）：開いて背面で撮るとき、外画面に映像を出して撮られる人にも見せる */
    private fun toggleCover() { if(coverSession!=null || coverRequested) stopCover() else startCover() }
    private fun startCover() {
        val info=areaInfo
        if(rearSession!=null) { message("外画面で自撮り中は使えません"); return }
        if(info==null || presentStatus!=WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE) {
            message(if(presentStatus==WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED) "この端末・OSでは外画面への同時表示に対応していません（端末診断の Cover present を確認）" else "いまは外画面に表示できません。本体を開いてから押してください")
            return
        }
        coverRequested=true; updateCoverUi()
        try {
            areaController?.presentContentOnWindowArea(info.token,this,main,object:WindowAreaPresentationSessionCallback {
                override fun onSessionStarted(session:WindowAreaSessionPresenter) {
                    coverRequested=false; coverSession=session; session.setContentView(buildCoverView(session.context))
                    handler.removeCallbacks(mirror); handler.post(mirror); updateCoverUi()
                }
                override fun onSessionEnded(t:Throwable?) {
                    coverRequested=false; coverSession=null; coverImage=null; handler.removeCallbacks(mirror)
                    t?.let { lastError="外画面ライブ: ${it.message}"; message(lastError) }; updateCoverUi()
                }
                override fun onContainerVisibilityChanged(isVisible:Boolean) {}
            })
        } catch(e:Exception) { coverRequested=false; fail("外画面ライブ",e); updateCoverUi() }
    }
    private fun stopCover() { handler.removeCallbacks(mirror); runCatching { coverSession?.close() }; coverSession=null; coverImage=null; coverRequested=false; if(::coverChip.isInitialized) updateCoverUi() }
    private fun buildCoverView(ctx:Context):View {
        val frame=FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        // 撮られる人が鏡のように見られるよう左右反転
        val img=ImageView(ctx).apply { scaleType=ImageView.ScaleType.FIT_CENTER; scaleX=-1f }
        frame.addView(img,FrameLayout.LayoutParams(-1,-1)); coverImage=img
        val d=ctx.resources.displayMetrics.density
        frame.addView(TextView(ctx).apply { text="● LIVE"; textSize=13f; setTextColor(C_TEXT); background=GradientDrawable().apply { setColor(C_CHIP); cornerRadius=12*d }; setPadding((12*d).toInt(),(6*d).toInt(),(12*d).toInt(),(6*d).toInt()) },
            FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply { setMargins((16*d).toInt(),(24*d).toInt(),0,0) })
        if(coverShutter) frame.addView(ShutterButton(ctx,ShutterButton.PHOTO).apply { contentDescription="外画面で写真"; setOnClickListener { onPhoto() } },
            FrameLayout.LayoutParams((72*d).toInt(),(72*d).toInt(),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=(32*d).toInt() })
        return frame
    }
    private fun updateCoverUi() {
        if(!::coverChip.isInitialized) return
        val on=coverSession!=null
        coverChip.active=on; coverChip.alpha=if(coverRequested) 0.5f else 1f
    }
    private fun tallAspect()=aspect=="9:16" || aspect=="フル"
    private fun chooseAspect() {
        if(recording!=null) { message("録画中は変更できません"); return }
        val list=listOf("3:4","9:16","1:1","フル")
        choose("比率（見えている範囲で保存）",list) { aspect=list[it]; prefs.edit().putString("aspect",aspect).apply(); layoutStage(force=true); refreshInfo() }
    }
    /** プレビューの枠を比率に合わせる。Flex モードでは折り目より上だけを使う */
    private fun layoutStage(force:Boolean=false) {
        if(!::stage.isInitialized) return
        val W=root.width; val H=root.height; if(W==0 || H==0) return
        val areaH=if(flexHingeTop>0) flexHingeTop.coerceAtMost(H) else H
        val r=when(aspect) { "9:16" -> 9 to 16; "1:1" -> 1 to 1; "フル" -> 0 to 0; else -> 3 to 4 }
        var w=W; var h=areaH
        if(r.first>0) {
            val (a,b)=if(W<=areaH) r else r.second to r.first   // a:b = 幅:高さ
            if(W.toLong()*b > areaH.toLong()*a) { h=areaH; w=areaH*a/b } else { w=W; h=W*b/a }
        }
        val g=if(flexHingeTop>0) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        val old=stage.layoutParams as FrameLayout.LayoutParams
        if(!force && old.width==w && old.height==h && old.gravity==g) return
        stage.layoutParams=FrameLayout.LayoutParams(w,h,g)
        if(camera!=null && recording==null) stage.post { bindCamera() }
    }
    /** 半分に折って横向きの折り目があれば、上半分を映像・下半分を操作にする（Flex モード） */
    // ===== ADB の窓口（テスト・PC からの操作用） =====
    override fun adbStatus():org.json.JSONObject {
        val J={ org.json.JSONObject() }
        val z=camera?.cameraInfo?.zoomState?.value
        val missing=listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO).filter { ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED }
        return J().put("app","camera").put("packageName",packageName).put("versionName",BuildConfig.VERSION_NAME).put("versionCode",BuildConfig.VERSION_CODE)
            .put("resumed",lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            .put("permissionsMissing",org.json.JSONArray(missing))
            .put("camera",J().put("bound",camera!=null).put("facing",if(facing==CameraSelector.LENS_FACING_BACK) "back" else "front")
                .put("zoom",z?.zoomRatio?.toDouble() ?: JSONObjectNull).put("zoomRange",z?.let { "${it.minZoomRatio}-${it.maxZoomRatio}" } ?: JSONObjectNull)
                .put("lens",lensName(z?.zoomRatio ?: 1f)).put("photoReady",imageCapture!=null).put("videoReady",videoCapture!=null)
                .put("recording",recording!=null).put("recordSeconds",if(recording!=null) (System.currentTimeMillis()-recordStartedAt)/1000 else 0)
                .put("photoBusy",photoBusy).put("burst",burstActive).put("timerRunning",countDown!=null)
                .put("mode",modeKey()).put("raw",rawJpeg).put("aspect",aspect).put("quality",qualityName(quality)).put("videoHdr",videoHdr)
                .put("fps",if(slowFps>0) slowFps else if(sixtyFps) 60 else 30).put("slowMotion",slowFps>0)
                .put("flash",when { torch -> "torch"; flash==ImageCapture.FLASH_MODE_AUTO -> "auto"; flash==ImageCapture.FLASH_MODE_ON -> "on"; else -> "off" })
                .put("timer",timerSeconds).put("ev",camera?.cameraInfo?.exposureState?.let { it.exposureCompensationIndex*it.exposureCompensationStep.toDouble() } ?: 0.0)
                .put("photoResolution",imageCapture?.resolutionInfo?.resolution?.toString() ?: JSONObjectNull).put("saveFormat",if(heifClap) "heif-clap" else "jpeg-crop"))
            .put("fold",J().put("hingeAngle",lastAngle?.toDouble() ?: JSONObjectNull).put("halfOpen",halfOpen).put("flexMode",flexHingeTop>0).put("flexLandscape",flexLandscape)
                .put("deviceAngle",deviceAngle).put("lockPortrait",lockPortrait)
                .put("rearDisplay",areaStatus.toString()).put("rearSession",rearSession!=null).put("autoRear",autoRear)
                .put("coverPresent",presentStatus.toString()).put("coverLive",coverSession!=null))
            .put("ui",J().put("hud",hudShown).put("voiceShutter",voiceOn).put("saveFolder",saveFolder).put("folders",org.json.JSONArray(folders))
                .put("event",meta.event(currentEvent)?.let { meta.label(it) } ?: JSONObjectNull).put("lastUri",lastUri?.toString() ?: JSONObjectNull))
            .put("errors",org.json.JSONArray(recentErrors.toList()))
    }
    private val JSONObjectNull: Any get() = org.json.JSONObject.NULL
    private fun modeKey()=when { sceneHdr -> "hdr"; lowLight -> "lowlight"; else -> when(extensionMode) { ExtensionMode.AUTO->"auto"; ExtensionMode.BOKEH->"bokeh"; ExtensionMode.FACE_RETOUCH->"face"; ExtensionMode.HDR->"ext-hdr"; ExtensionMode.NIGHT->"night"; else->"standard" } }
    override fun adbCommand(command:String, extras:Bundle):Pair<Int,org.json.JSONObject> {
        fun ok(msg:String="accepted")=0 to org.json.JSONObject().put("ok",true).put("result",msg)
        fun bad(msg:String)=1 to org.json.JSONObject().put("error",msg)
        val v=extras.getString("value") ?: extras.getString("action") ?: ""
        if(recording!=null && command in setOf("MODE","FPS","ASPECT","FACING","REBIND")) return bad("recording")
        return when(command) {
            "STATUS" -> 0 to adbStatus()
            "GET" -> { val k=extras.getString("key") ?: return bad("key required"); 0 to org.json.JSONObject().put("key",k).put("value",prefs.all[k] ?: JSONObjectNull) }
            "SET" -> { val k=extras.getString("key") ?: return bad("key required"); val b=v=="true"||v=="1"||v=="on"
                when(k) {
                    "autoRear" -> { autoRear=b; prefs.edit().putBoolean(k,b).apply() }
                    "audio" -> { audioEnabled=b; prefs.edit().putBoolean(k,b).apply() }
                    "grid" -> { overlay.grid=b; prefs.edit().putBoolean(k,b).apply(); overlay.invalidate() }
                    "level" -> { overlay.level=b; prefs.edit().putBoolean(k,b).apply(); overlay.invalidate() }
                    "lockPortrait" -> { lockPortrait=b; prefs.edit().putBoolean(k,b).apply(); flexLandscape=false; applyOrientationLock(); rotateUi() }
                    "coverShutter" -> { coverShutter=b; prefs.edit().putBoolean(k,b).apply() }
                    "updateAuto" -> prefs.edit().putBoolean(k,b).apply()
                    "highResolution" -> { highResolution=b; prefs.edit().putBoolean(k,b).apply(); bindCamera() }
                    "heifClap" -> { heifClap=b; prefs.edit().putBoolean(k,b).apply(); bindCamera() }
                    else -> return bad("unknown key")
                }; refreshInfo(); ok("$k=$v") }
            "OPEN" -> { when(v) { "settings" -> showSettings(); "diagnostics" -> showDiagnostics(); "update" -> showUpdateMenu(); "events" -> showEvents(); "search" -> showSearch(); "detail" -> showDetail(); else -> return bad("screen: settings|diagnostics|update|events|search|detail") }; ok(v) }
            "CHECK_UPDATE" -> { checkUpdate(manual=true); ok() }
            "PHOTO" -> { onPhoto(); ok() }
            "BURST" -> { val n=(extras.getString("count") ?: v).toIntOrNull()?.coerceIn(1,100) ?: 5; burstLimit=n; startBurst(); ok("burst $n") }
            "VIDEO" -> { when(v) { "start" -> if(recording==null) onVideo(); "stop" -> if(recording!=null) onVideo(); else -> onVideo() }; ok() }
            "ZOOM" -> { val z=v.toFloatOrNull() ?: return bad("value=zoom ratio"); zoomTo(z); ok("zoom $z") }
            "FACING" -> { facing=if(v=="front") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK; selectedId=null; resetManual(); bindCamera(); ok(v) }
            "MODE" -> { extensionMode=ExtensionMode.NONE; sceneHdr=false; lowLight=false
                when(v) { "standard" -> {}; "hdr" -> sceneHdr=true; "lowlight" -> lowLight=true; "auto" -> extensionMode=ExtensionMode.AUTO; "bokeh" -> extensionMode=ExtensionMode.BOKEH; "face" -> extensionMode=ExtensionMode.FACE_RETOUCH; else -> return bad("value: standard|hdr|lowlight|auto|bokeh|face") }
                if(extensionMode!=ExtensionMode.NONE) slowFps=0; resetManual(); bindCamera(); refreshInfo(); ok(v) }
            "FPS" -> { val f=v.toIntOrNull() ?: return bad("value: 30|60|120|240"); if(f !in fpsOptions()) return bad("unsupported: ${fpsOptions()}")
                sixtyFps=f==60; slowFps=if(f>=120) f else 0; bindCamera(); refreshInfo(); ok("$f") }
            "ASPECT" -> { val a=if(v=="full") "フル" else v; if(a !in listOf("3:4","9:16","1:1","フル")) return bad("value: 3:4|9:16|1:1|full")
                aspect=a; prefs.edit().putString("aspect",a).apply(); layoutStage(force=true); refreshInfo(); ok(v) }
            "EV" -> { val c=camera ?: return bad("camera not bound"); val e=v.toFloatOrNull() ?: return bad("value=EV")
                val st=c.cameraInfo.exposureState; val idx=(e/st.exposureCompensationStep.toFloat()).roundToInt().coerceIn(st.exposureCompensationRange.lower,st.exposureCompensationRange.upper)
                watch(c.cameraControl.setExposureCompensationIndex(idx),"露出変更"); handler.postDelayed({refreshInfo()},200); ok("index $idx") }
            "FLASH" -> { when(v) { "off" -> { flash=ImageCapture.FLASH_MODE_OFF; if(torch) { torch=false; camera?.cameraControl?.enableTorch(false) } }
                    "auto" -> flash=ImageCapture.FLASH_MODE_AUTO; "on" -> flash=ImageCapture.FLASH_MODE_ON
                    "torch" -> { torch=!torch; camera?.cameraControl?.enableTorch(torch) }
                    else -> return bad("value: off|auto|on|torch") }
                imageCapture?.flashMode=flash; refreshInfo(); ok(v) }
            "TIMER" -> { val t=v.toIntOrNull() ?: return bad("value: 0|3|5|10"); timerSeconds=t; prefs.edit().putInt("timer",t).apply(); refreshInfo(); ok("$t") }
            "HUD" -> { setHud(v!="hide"); ok(v) }
            "FOLDER" -> { if(v.isBlank()) return bad("value=folder name"); if(v !in folders) { folders.add(v); prefs.edit().putString("folders",folders.joinToString("\n")).apply() }
                saveFolder=v; prefs.edit().putString("saveFolder",v).apply(); if(hudShown) buildFolderRow(); ok(v) }
            "EVENT" -> { if(v=="none" || v.isBlank()) { setEvent(null); return ok("none") }
                val today=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
                val e=meta.events.firstOrNull { it.name==v } ?: meta.addEvent(v,extras.getString("start") ?: today,extras.getString("end") ?: extras.getString("start") ?: today)
                setEvent(e.id); ok(meta.label(e)) }
            "VOICE" -> { setVoice(v=="on"); ok(v) }
            "COVER" -> { if(v=="off") stopCover() else startCover(); ok(v) }
            "REAR" -> { toggleRear(); ok() }
            "REBIND" -> { startProvider(); ok() }
            "DIAG" -> 0 to org.json.JSONObject().put("report",diagnosticText())
            else -> 1 to org.json.JSONObject().put("error","unknown command")
        }
    }
    private fun applyOrientationLock() {
        requestedOrientation=when { flexLandscape -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            lockPortrait -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    /** 半分折って横にしたときだけ横画面にして Flex モードへ。開き切ったら縦に戻す */
    private fun updateFlexOrientation() {
        if(!lockPortrait) return
        val want=halfOpen && (deviceAngle==90 || deviceAngle==270)
        if(want==flexLandscape) return
        if(!want && halfOpen && flexLandscape) return   // 半開きのまま少し傾けただけでは戻さない
        flexLandscape=want; applyOrientationLock(); rotateUi()
    }
    /** 端末の向きに合わせて、アイコン・数字・サムネイルを起こす */
    private fun rotateUi() {
        if(!::banner.isInitialized) return
        val target=if(lockPortrait && !flexLandscape) when(deviceAngle) { 90 -> -90f; 180 -> 180f; 270 -> 90f; else -> 0f } else 0f
        uiAngle=target
        val views=rotatables+(0 until thumbStrip.childCount).map { thumbStrip.getChildAt(it) }
        views.forEach { v -> var d=target-v.rotation; while(d>180) d-=360; while(d<-180) d+=360; v.animate().rotationBy(d).setDuration(220).start() }
        // 知らせの帯は横向きのとき画面の中央で回す
        val lp=banner.layoutParams as FrameLayout.LayoutParams
        lp.gravity=if(target==0f) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        banner.layoutParams=lp; banner.rotation=target
    }
    private fun updateFlex(feature:FoldingFeature?) {
        halfOpen=feature?.state==FoldingFeature.State.HALF_OPENED
        if(!halfOpen && flexLandscape) { flexLandscape=false; applyOrientationLock() } else updateFlexOrientation()
        val flex=feature!=null && feature.state==FoldingFeature.State.HALF_OPENED && feature.orientation==FoldingFeature.Orientation.HORIZONTAL
        val loc=IntArray(2); root.getLocationInWindow(loc)
        val top=if(flex) feature!!.bounds.top-loc[1] else -1
        if(top==flexHingeTop) return
        val entering=flexHingeTop<=0 && top>0
        flexHingeTop=top; layoutStage(force=true)
        if(entering) showBanner("Flex モード：上が映像、下が操作",1500)
    }
    /** 写真ボタン長押しで連写（離すと止まる。最大 100 枚） */
    private fun startBurst() {
        val cap=imageCapture ?: run { message("この状態では連写できません"); return }
        if(photoBusy || countDown!=null || burstActive) return
        if(cap.outputFormat==ImageCapture.OUTPUT_FORMAT_RAW_JPEG) { message("RAW+JPEG では連写できません"); return }
        burstActive=true; burstCount=0; burstBase=name(); shootBurst()
    }
    private fun shootBurst() {
        val cap=imageCapture
        if(!burstActive || cap==null || burstCount>=burstLimit) { burstActive=false; finishBurst(); return }
        val bname="${burstBase}_B${(burstCount+1).toString().padStart(3,'0')}.jpg"
        val opts=ImageCapture.OutputFileOptions.Builder(contentResolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,bname); put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg"); put(MediaStore.MediaColumns.RELATIVE_PATH,saveDir())
        }).build()
        try {
            cap.takePicture(opts,main,object:ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r:ImageCapture.OutputFileResults) { burstCount++; r.savedUri?.let { meta.create(it,bname,saveFolder,currentEvent) }; saveLast(r.savedUri,false); showBanner("連写 $burstCount 枚",900); shootBurst() }
                override fun onError(e:ImageCaptureException) { burstActive=false; finishBurst(); fail("連写",e) }
            })
        } catch(e:Exception) { burstActive=false; finishBurst(); fail("連写",e) }
    }
    private fun finishBurst() { burstLimit=100; if(burstCount>0) { updateThumb(); showBanner("連写 $burstCount 枚を保存しました",1800) } }
    private fun onScan() {
        if(recording!=null) { message("録画中はスキャンできません"); return }
        choose("スキャン",listOf("書類（枠と傾きを自動で補正）","QR コード・バーコード")) { if(it==0) startScan() else startQr() }
    }
    private fun startQr() {
        val opts=com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder().enableAutoZoom().build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this,opts).startScan()
            .addOnSuccessListener { b -> showQr(b.rawValue ?: b.displayValue ?: "", b.url?.url) }
            .addOnFailureListener { message("QR を読めません（Google Play 開発者サービスが必要）：${it.message}") }
    }
    private fun showQr(text:String, url:String?) {
        val link=url ?: text.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val view=TextView(this).apply { this.text=text; textSize=15f; setTextIsSelectable(true); setPadding(dp(20),dp(12),dp(20),dp(4)) }
        val d=android.app.AlertDialog.Builder(this).setTitle("読み取り結果").setView(view)
            .setNegativeButton("コピー") { _,_ -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("QR",text)); message("コピーしました") }
            .setNeutralButton("共有") { _,_ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"共有")) }
        if(link!=null) d.setPositiveButton("開く") { _,_ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link))) }.onFailure { message("開けません") } }
        d.show()
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
        super.onResume(); if(voiceOn && recording==null) voice.start(); orientationListener?.enable()
        hinge?.let {sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_NORMAL)}
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_UI)}
        if(hinge==null && resources.configuration.screenWidthDp<500 && rearSession==null) updateAngle(0f)
        handler.postDelayed(rearCheck,800)
    }
    override fun onPause() {super.onPause();voice.stop();orientationListener?.disable();sensors.unregisterListener(this);countDown?.cancel();countDown=null;if(!photoBusy && recording==null){setBusy(false);refreshInfo()} }
    override fun onStop() {super.onStop();recording?.stop();handler.removeCallbacks(rearCheck);foldTrigger.reset()}
    override fun onStart() {
        super.onStart()
        app.thetahatch.adb.AdbBridge.attach(this)
        runCatching { startService(Intent(this, app.thetahatch.adb.StatusService::class.java)) }
    }
    override fun onDestroy() {app.thetahatch.adb.AdbBridge.detach(this);stopCover();countDown?.cancel();recording?.close();runCatching {rearSession?.close()};handler.removeCallbacksAndMessages(null);super.onDestroy()}
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
            paint.style=Paint.Style.FILL; paint.color=Color.argb(a*70/255,0,0,0); c.drawCircle(cx,cy,r-d,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=2*d; paint.color=Color.argb(a,255,255,255); c.drawCircle(cx,cy,r-1.5f*d,paint)
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

/** モノクロの線アイコン（24 マスの座標で描く）。active は下に点、badge は小さな文字 */
class IconButton(context:Context, private val kind:String):View(context) {
    var active=false; set(v) { field=v; invalidate() }
    var badge:String?=null; set(v) { field=v; invalidate() }
    var variant:String=""; set(v) { field=v; invalidate() }
    private val d=resources.displayMetrics.density
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND; color=Color.WHITE }
    private val f=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL; color=Color.WHITE }
    private val t=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textAlign=Paint.Align.CENTER; typeface=Typeface.DEFAULT_BOLD }
    private var ox=0f; private var oy=0f
    init { isClickable=true; isFocusable=true }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    private fun x(v:Float)=ox+v*d
    private fun y(v:Float)=oy+v*d
    private fun line(a:Float,b:Float,c2:Float,e:Float,c:Canvas)=c.drawLine(x(a),y(b),x(c2),y(e),p)
    private fun rect(l:Float,tp:Float,r:Float,b:Float,rad:Float,c:Canvas)=c.drawRoundRect(x(l),y(tp),x(r),y(b),rad*d,rad*d,p)
    private fun text(s:String,cx:Float,cy:Float,size:Float,c:Canvas) { t.textSize=size*d; c.drawText(s,x(cx),y(cy)-(t.descent()+t.ascent())/2,t) }
    override fun onDraw(c:Canvas) {
        ox=(width-24*d)/2f; oy=(height-24*d)/2f
        val a=if(isEnabled) 255 else 90
        val sh=Color.argb(160,0,0,0)
        for(paint in listOf(p,f,t)) { paint.alpha=a; paint.setShadowLayer(2.5f*d,0f,0.5f*d,sh) }
        p.strokeWidth=1.7f*d
        if(isPressed) { val bg=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(55,255,255,255) }; c.drawCircle(width/2f,height/2f,20*d,bg) }
        when(kind) {
            "flash" -> {
                val path=Path().apply { moveTo(x(13f),y(2.5f)); lineTo(x(5f),y(13.5f)); lineTo(x(11f),y(13.5f)); lineTo(x(10f),y(21.5f)); lineTo(x(18.5f),y(10f)); lineTo(x(12.5f),y(10f)); close() }
                c.drawPath(path,p)
                when(variant) { "off","" -> line(3.5f,3.5f,20.5f,20.5f,c); "auto" -> text("A",20f,19f,8f,c); "torch" -> text("T",20f,19f,8f,c) }
            }
            "timer" -> { c.drawCircle(x(12f),y(13.5f),8*d,p); line(10f,2.5f,14f,2.5f,c); line(18f,6.5f,19.5f,5f,c)
                badge?.let { text(it,12f,13.8f,8.5f,c) } ?: line(12f,13.5f,12f,9f,c) }
            "ev" -> { c.drawCircle(x(12f),y(12f),8.5f*d,p); line(12f,3.5f,12f,20.5f,c); line(6f,10f,10f,10f,c); line(8f,8f,8f,12f,c); line(14f,14f,18f,14f,c) }
            "aspect" -> when(variant) {
                "9:16" -> rect(7f,2.5f,17f,21.5f,1.5f,c); "1:1" -> rect(4.5f,4.5f,19.5f,19.5f,1.5f,c); "3:4" -> rect(6f,4f,18f,20f,1.5f,c)
                else -> { for((sx,sy) in listOf(4f to 4f,20f to 4f,4f to 20f,20f to 20f)) { val dx=if(sx<12) 4f else -4f; val dy=if(sy<12) 4f else -4f; line(sx,sy,sx+dx,sy,c); line(sx,sy,sx,sy+dy,c) }; text("FULL",12f,12f,5.5f,c) } }
            "mode" -> { val path=Path().apply { moveTo(x(10f),y(5f)); quadTo(x(11f),y(12f),x(18f),y(13f)); quadTo(x(11f),y(14f),x(10f),y(21f)); quadTo(x(9f),y(14f),x(2f),y(13f)); quadTo(x(9f),y(12f),x(10f),y(5f)); close() }
                c.drawPath(path,p); line(19f,2.5f,19f,7.5f,c); line(16.5f,5f,21.5f,5f,c) }
            "level" -> { line(2f,12f,8.5f,12f,c); line(15.5f,12f,22f,12f,c); c.drawCircle(x(12f),y(12f),2.6f*d,p) }
            "grid" -> { rect(3.5f,3.5f,20.5f,20.5f,1.5f,c); line(9.2f,3.5f,9.2f,20.5f,c); line(14.8f,3.5f,14.8f,20.5f,c); line(3.5f,9.2f,20.5f,9.2f,c); line(3.5f,14.8f,20.5f,14.8f,c) }
            "quality","fps" -> { rect(2f,6f,22f,18f,2.5f,c); text(variant,12f,12f,if(variant.length>=3) 6.5f else 7.5f,c) }
            "settings" -> { p.strokeWidth=2.6f*d; for(i in 0 until 8) { val ang=Math.toRadians(i*45.0); line(12f+6.6f*cos(ang).toFloat(),12f+6.6f*sin(ang).toFloat(),12f+9.2f*cos(ang).toFloat(),12f+9.2f*sin(ang).toFloat(),c) }
                p.strokeWidth=1.7f*d; c.drawCircle(x(12f),y(12f),6.6f*d,p); c.drawCircle(x(12f),y(12f),2.6f*d,p) }
            "cover" -> { rect(3f,3f,13f,21f,2f,c); rect(15.5f,5.5f,21f,18.5f,1.5f,c); c.drawCircle(x(8f),y(12f),2.2f*d,p) }
            "rear" -> { rect(6.5f,2.5f,17.5f,21.5f,2.5f,c); rect(8.5f,4.5f,13.5f,13.5f,2f,c)
                c.drawCircle(x(11f),y(6.8f),1.1f*d,f); c.drawCircle(x(11f),y(9.3f),1.1f*d,f); c.drawCircle(x(11f),y(11.6f),1.1f*d,f) }
            "hud" -> { for((yy,kx) in listOf(6.5f to 15f,12f to 8f,17.5f to 17f)) { line(3f,yy,21f,yy,c); c.drawCircle(x(kx),y(yy),2.3f*d,f) } }
            "voice" -> { rect(9f,2.5f,15f,14f,3f,c); c.drawArc(x(5.5f),y(6.5f),x(18.5f),y(17.5f),0f,180f,false,p); line(12f,17.5f,12f,21f,c); line(8.5f,21f,15.5f,21f,c)
                if(variant!="on") line(4f,3f,20f,21f,c) }
            "star" -> { val path=Path(); for(i in 0 until 10) { val rr=if(i%2==0) 9.5f else 4.2f; val a=Math.toRadians(-90.0+i*36); val px=x(12f+rr*cos(a).toFloat()); val py=y(12.8f+rr*sin(a).toFloat()); if(i==0) path.moveTo(px,py) else path.lineTo(px,py) }; path.close()
                c.drawPath(path,if(variant=="on") f else p) }
            "info" -> { c.drawCircle(x(12f),y(12f),9f*d,p); line(12f,11f,12f,16.5f,c); c.drawCircle(x(12f),y(7.6f),1.2f*d,f) }
            "search" -> { c.drawCircle(x(10.5f),y(10.5f),6.5f*d,p); line(15.3f,15.3f,20.5f,20.5f,c) }
            "plus" -> { c.drawCircle(x(12f),y(12f),9f*d,p); line(12f,7.5f,12f,16.5f,c); line(7.5f,12f,16.5f,12f,c) }
            "flip" -> { c.drawArc(x(5f),y(5f),x(19f),y(19f),200f,130f,false,p); c.drawArc(x(5f),y(5f),x(19f),y(19f),20f,130f,false,p)
                line(17.6f,6.6f,18.4f,9.6f,c); line(18.4f,9.6f,15.4f,9.4f,c); line(6.4f,17.4f,5.6f,14.4f,c); line(5.6f,14.4f,8.6f,14.6f,c) }
        }
        badge?.takeIf { kind!="timer" }?.let { text(it,12f,26.5f,6.5f,c) }
        if(active && (badge==null || kind=="timer")) c.drawCircle(width/2f,oy+27f*d,1.6f*d,f)
    }
}

/** 丸いサムネイル。動画は左右にフィルムの穴と再生マーク（CAM-029 の見分け） */
@android.annotation.SuppressLint("AppCompatCustomView")
class ThumbView(context:Context, private val video:Boolean):ImageView(context) {
    var favorite=false
    var selected2=false
    private val d=resources.displayMetrics.density
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        scaleType=ScaleType.CENTER_CROP; clipToOutline=true; isClickable=true
        background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(Color.argb(60,0,0,0)) }
    }
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        p.style=Paint.Style.STROKE; p.strokeWidth=(if(selected2) 3.5f else 1.5f)*d; p.color=Color.WHITE; c.drawCircle(width/2f,height/2f,width/2f-p.strokeWidth/2,p)
        if(favorite) { p.style=Paint.Style.FILL; p.color=Color.WHITE; p.setShadowLayer(2*d,0f,0f,Color.BLACK)
            val cx=width-7*d; val cy=7*d; val r=5.5f*d
            c.drawPath(Path().apply { for(i in 0 until 10) { val rr=if(i%2==0) r else r*0.45f; val a=Math.toRadians(-90.0+i*36); val px=cx+rr*cos(a).toFloat(); val py=cy+rr*sin(a).toFloat(); if(i==0) moveTo(px,py) else lineTo(px,py) }; close() },p)
            p.clearShadowLayer() }
        if(video) { p.style=Paint.Style.FILL; p.color=Color.argb(220,255,255,255)
            val cx=width/2f; val cy=height/2f; val r=6*d
            c.drawPath(Path().apply { moveTo(cx-r*0.6f,cy-r); lineTo(cx+r,cy); lineTo(cx-r*0.6f,cy+r); close() },p) }
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
