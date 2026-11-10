package app.thetahatch.adb

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.ref.WeakReference

/**
 * シリーズ共通の ADB の窓口（Theta.Hatch.Series spec/THETA_ADB_SPEC.md §3）。
 * どちらも android.permission.DUMP で守るので、adb のシェルからしか呼べない。
 *
 *   状態：adb shell dumpsys activity service app.thetahatch.camera/app.thetahatch.adb.StatusService
 *   操作：adb shell am broadcast -a app.thetahatch.adb.<命令> -n app.thetahatch.camera/app.thetahatch.adb.CommandReceiver [--es key 値]
 */
interface AdbTarget {
    fun adbStatus(): JSONObject
    /** 結果コード（0＝成功）と JSON */
    fun adbCommand(command: String, extras: Bundle): Pair<Int, JSONObject>
}

object AdbBridge {
    const val TAG = "ThetaHatch"
    private var target: WeakReference<AdbTarget>? = null
    fun attach(t: AdbTarget) { target = WeakReference(t) }
    fun detach(t: AdbTarget) { if (target?.get() === t) target = null }
    fun current(): AdbTarget? = target?.get()
    fun notOpen() = JSONObject().put("error", "app not open").put("hint", "adb shell am start -n app.thetahatch.camera/com.theta.camera.hatch.MainActivity")
}

/** dumpsys で 1 行の JSON を返す。アプリが開いている間だけ動いている */
class StatusService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        val json = AdbBridge.current()?.adbStatus() ?: AdbBridge.notOpen()
        writer.println(json.toString())
    }
}

class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.action?.removePrefix("app.thetahatch.adb.") ?: ""
        val extras = intent.extras ?: Bundle()
        Log.i(AdbBridge.TAG, "adb command $command")
        val (code, json) = when (val t = AdbBridge.current()) {
            null -> if (command == "STATUS") 0 to AdbBridge.notOpen() else 2 to AdbBridge.notOpen()
            else -> runCatching { t.adbCommand(command, extras) }.getOrElse { 1 to JSONObject().put("error", it.message ?: it.toString()) }
        }
        resultCode = code
        resultData = json.toString()
    }
}
