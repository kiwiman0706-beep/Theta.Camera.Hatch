package com.theta.camera.hatch

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * スキャンした書類に AI-OCR をかけてタイトルなどを付ける。
 * 1. 端末内（ML Kit の日本語の文字認識）で本文を読む。外へは送らない。
 * 2. Gemini の API キーがあれば、読んだ本文を送ってタイトル・種類・日付・タグ・要約を作る（自動）。
 *    キーは利用者自身のもの。ハブ（Home・KidsHome）から借りるか、入力する（spec/THETA_SHARED_SETTINGS.md §4）。
 * キーが無い・失敗したときは端末内の結果だけで、見出しらしい行と日付からタイトルを作る。
 */
object ScanAi {
    data class Info(val title: String, val kind: String, val date: String, val tags: List<String>, val summary: String, val text: String, val byGemini: Boolean)

    private val HUBS = listOf("app.thetahatch.home", "app.thetahatch.kidshome")

    /** ページ画像を読んで、タイトルなどを返す。IO スレッドで呼ぶ */
    fun analyze(context: Context, pages: List<Uri>): Info {
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        val texts = pages.take(10).mapNotNull { u -> runCatching { Tasks.await(recognizer.process(InputImage.fromFilePath(context, u))) }.getOrNull() }
        recognizer.close()
        val text = texts.joinToString("\n\n") { it.text }.trim()
        val firstPageLines = texts.firstOrNull()?.textBlocks?.sortedBy { it.boundingBox?.top ?: 0 }?.flatMap { it.lines } ?: emptyList()
        // 端末内だけのタイトル：上のほうで文字が大きい行（行の高さ）を優先
        val heading = firstPageLines.take(12).filter { l -> val t = l.text.trim(); t.length in 3..40 && t.count { it.isDigit() } < t.length / 2 }
            .maxByOrNull { it.boundingBox?.height() ?: 0 }?.text?.trim().orEmpty()
        val edge = Info(heading, "", findDate(text), emptyList(), "", text, false)
        val key = key(context) ?: return edge
        if (text.isBlank()) return edge
        return runCatching { gemini(key, model(context), text, edge) }.getOrElse { edge }
    }

    private fun findDate(t: String): String {
        Regex("(20\\d{2})[年/.-]\\s*(\\d{1,2})[月/.-]\\s*(\\d{1,2})").find(t)?.let { m ->
            val (y, mo, d) = m.destructured; return "%s-%02d-%02d".format(y, mo.toInt(), d.toInt())
        }
        return ""
    }

    private fun gemini(key: String, model: String, text: String, edge: Info): Info {
        val prompt = """次はスキャンした書類から読み取った文字です。内容を見て、JSON だけで答えてください。
{"title": "書類のタイトル（日本語、25 字以内。ファイル名にするので記号は使わない）", "kind": "書類の種類（例：お知らせ・請求書・領収書・名刺・チラシ・契約書・メモ）", "date": "書類の主な日付 YYYY-MM-DD（無ければ空）", "tags": ["検索用のタグ（5 個まで）"], "summary": "60 字以内の要約"}

---
${text.take(12000)}"""
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val c = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 60000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-goog-api-key", key)
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) error("Gemini ${c.responseCode}")
            val res = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val out = res.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            val j = JSONObject(out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
            val tags = j.optJSONArray("tags")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            return Info(j.optString("title").ifBlank { edge.title }, j.optString("kind"), j.optString("date").ifBlank { edge.date }, tags, j.optString("summary"), text, true)
        } finally { c.disconnect() }
    }

    // ===== Gemini の API キー（端末内で暗号化して保存） =====
    private const val PREFS = "secrets"
    private const val ALIAS = "theta-camera-secrets"

    fun key(context: Context): String? = runCatching {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("gemini.key", null) ?: return null
        val raw = Base64.decode(v, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()

    fun setKey(context: Context, value: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isNullOrBlank()) { prefs.edit().remove("gemini.key").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = cipher.iv + cipher.doFinal(value.trim().toByteArray())
        prefs.edit().putString("gemini.key", Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    /** ハブ（Home・KidsHome）の Gemini キーを借りる。利用者が「借りる」を選んだときだけ呼ぶ */
    fun keyFromHub(context: Context): String? = HUBS.firstNotNullOfOrNull { pkg -> readHub(context, "content://$pkg.theta/secret/gemini.key", "gemini.key") }

    /** モデルはシリーズの共通設定（ハブの info の gemini.model）。無ければ gemini-2.5-flash */
    fun model(context: Context): String = HUBS.firstNotNullOfOrNull { pkg -> readHub(context, "content://$pkg.theta/info", "gemini.model") } ?: "gemini-2.5-flash"

    private fun readHub(context: Context, uri: String, key: String): String? = runCatching {
        context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { c ->
            val k = c.getColumnIndex("key"); val v = c.getColumnIndex("value")
            while (c.moveToNext()) if (k < 0 || c.getString(k) == key) return@use c.getString(v)
            null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
