package com.theta.camera.hatch

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * アプリ内の更新（シリーズ共通仕様 §6.1）。
 * - ハブ（Theta Home Hatch / Theta KidsHome Hatch）があれば、ハブに頼む
 * - 無くても単体で：Releases の最新版を確認 → APK をダウンロード → SHA-256 を照合 → Android のインストーラへ
 * インストールの権限（REQUEST_INSTALL_PACKAGES）は持たない。ダウンロードした APK は
 * 「ダウンロード」アプリ（通知）から入れる。署名が違えば Android が上書きを拒否する。
 */
object HatchUpdate {
    private const val ACTION_UPDATE = "app.thetahatch.action.UPDATE"
    private val HUBS = listOf("app.thetahatch.home", "app.thetahatch.kidshome")
    private const val REPO = "kiwiman0706-beep/Theta.Camera.Hatch"
    const val RELEASES = "https://github.com/$REPO/releases/latest"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val sha256: String?)

    /** ネットワークを使う。メインスレッドで呼ばない。 */
    fun fetchLatest(): Release {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ThetaCameraHatch")
        }
        try {
            if (c.responseCode != 200) error("GitHub から読めません（${c.responseCode}）")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val version = j.getString("tag_name").removePrefix("v")
            val assets = j.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                if (name.endsWith(".apk")) {
                    val sha = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                    return Release(version, j.optString("body"), a.getString("browser_download_url"), name, sha)
                }
            }
            error("Release に APK がありません")
        } finally { c.disconnect() }
    }

    /** "0.10.0" > "0.9.1" のように数字で比べる */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val d = r.getOrElse(i) { 0 } - l.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun hubIntent(context: Context): Intent? = HUBS.asSequence()
        .map { Intent(ACTION_UPDATE).setPackage(it).putExtra("packageName", context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { it.resolveActivity(context.packageManager) != null }

    fun hasHub(context: Context) = hubIntent(context) != null

    /** ハブの更新画面を開く。ハブが無ければ false */
    fun requestHub(context: Context): Boolean {
        val i = hubIntent(context) ?: return false
        return runCatching { context.startActivity(i) }.isSuccess
    }

    fun openReleases(context: Context) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * APK を「ダウンロード」に取得する。終わったら SHA-256 を照合し、
     * 合えばダウンロード一覧を開いて onDone(true)、合わなければ消して onDone(false)。
     */
    fun download(context: Context, r: Release, onDone: (Boolean, String) -> Unit) {
        val app = context.applicationContext
        val dm = app.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(r.apkUrl))
            .setTitle("Theta Camera Hatch ${r.version}")
            .setDescription("タップしてインストール")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, r.apkName)
        val id = dm.enqueue(req)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { app.unregisterReceiver(this) }
                Thread {
                    val ok = runCatching { verify(app, dm, id, r.sha256) }.getOrDefault(false)
                    if (!ok) dm.remove(id)
                    val msg = when {
                        ok -> "ダウンロードしました。通知か「ダウンロード」から ${r.apkName} をタップして入れてください"
                        else -> "ダウンロードした APK が壊れているか、確認できませんでした（削除しました）"
                    }
                    ContextCompat.getMainExecutor(app).execute {
                        if (ok) runCatching {
                            app.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        onDone(ok, msg)
                    }
                }.start()
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun verify(context: Context, dm: DownloadManager, id: Long, sha256: String?): Boolean {
        dm.query(DownloadManager.Query().setFilterById(id)).use { cur ->
            if (!cur.moveToFirst()) return false
            if (cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) return false
        }
        if (sha256 == null) return true  // GitHub が digest を付けていない古い Release
        val uri = dm.getUriForDownloadedFile(id) ?: return false
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        } ?: return false
        return md.digest().joinToString("") { "%02x".format(it) }.equals(sha256, ignoreCase = true)
    }
}
