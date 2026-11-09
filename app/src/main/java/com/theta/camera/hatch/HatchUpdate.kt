package com.theta.camera.hatch

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 更新はハブ（Theta Home Hatch / Theta KidsHome Hatch）に頼む。ハブが無ければ Releases ページを開く（シリーズ共通仕様 §6.1）。 */
object HatchUpdate {
    private const val ACTION_UPDATE = "app.thetahatch.action.UPDATE"
    private val HUBS = listOf("app.thetahatch.home", "app.thetahatch.kidshome")
    private const val RELEASES = "https://github.com/kiwiman0706-beep/Theta.Camera.Hatch/releases/latest"

    fun request(context: Context) {
        for (hub in HUBS) {
            val intent = Intent(ACTION_UPDATE).setPackage(hub)
                .putExtra("packageName", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null &&
                runCatching { context.startActivity(intent) }.isSuccess) return
        }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
