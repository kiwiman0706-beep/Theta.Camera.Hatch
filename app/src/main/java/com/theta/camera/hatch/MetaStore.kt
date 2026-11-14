package com.theta.camera.hatch

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 記録ごとのイベント・☆・タグ・コメント（PORTABLE_METADATA.md）。
 * - 端末内の索引：files/theta-index.json（消えても作り直せる控え）
 * - 持ち出し用：Documents/ThetaCamera/<フォルダ>/<名前>.xmp と <名前>.theta.json
 *   （Android は DCIM に写真以外のファイルを置けないため、写真と同じ名前で Documents 側に置く。原本は書き換えない）
 */
class MetaStore(private val context: Context) {
    data class Event(val id: String, var name: String, var start: String, var end: String)
    data class Meta(
        val assetId: String, val uri: String, val name: String, var folder: String,
        var eventId: String?, var favorite: Boolean, val tags: MutableList<String>, var comment: String, val created: Long,
    ) { var crop: String = ""; var title: String = ""; var ocr: String = ""; var kind: String = ""; var docDate: String = "" }

    val events = mutableListOf<Event>()
    private val metas = LinkedHashMap<String, Meta>()
    private val file = File(context.filesDir, "theta-index.json")
    private val io = Executors.newSingleThreadExecutor()

    init { load() }

    fun get(uri: Uri): Meta? = metas[uri.toString()]
    fun all(): Collection<Meta> = metas.values
    fun event(id: String?): Event? = events.firstOrNull { it.id == id }

    /** 撮影・保存した直後に呼ぶ */
    fun create(uri: Uri, name: String, folder: String, eventId: String?): Meta {
        val m = Meta(UUID.randomUUID().toString(), uri.toString(), name, folder, eventId, false, mutableListOf(), "", System.currentTimeMillis())
        metas[m.uri] = m
        changed(m)
        return m
    }

    /** 索引に無い古い写真に、あとから☆やコメントを付けるとき */
    fun ensure(uri: Uri, folderGuess: String): Meta? {
        get(uri)?.let { return it }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: return null
        return create(uri, name, folderGuess, null)
    }

    fun changed(m: Meta) { save(); io.execute { runCatching { writeSidecars(m) } } }

    fun addEvent(name: String, start: String, end: String): Event {
        val e = Event(UUID.randomUUID().toString(), name, start, end)
        events.add(e); save(); return e
    }

    fun matches(m: Meta?, query: String, starOnly: Boolean): Boolean {
        if (starOnly && m?.favorite != true) return false
        if (query.isBlank()) return true
        if (m == null) return false
        val ev = event(m.eventId)
        val hay = listOf(m.comment, m.tags.joinToString(" "), ev?.name ?: "", m.folder, m.name, m.title, m.kind, m.ocr).joinToString(" ")
        return query.split(' ', '　').filter { it.isNotBlank() }.all { hay.contains(it, ignoreCase = true) }
    }

    /** 例：レゴランド · 11/09–10 */
    fun label(e: Event): String {
        val s = e.start.split("-"); val t = e.end.split("-")
        if (s.size != 3 || t.size != 3) return e.name
        val range = when {
            e.start == e.end -> "${s[1]}/${s[2]}"
            s[0] == t[0] && s[1] == t[1] -> "${s[1]}/${s[2]}–${t[2]}"
            else -> "${s[1]}/${s[2]}–${t[1]}/${t[2]}"
        }
        return "${e.name} · $range"
    }

    private fun save() {
        val o = JSONObject()
        o.put("schema", "theta.index/1")
        o.put("events", JSONArray().apply { events.forEach { put(eventJson(it)) } })
        o.put("assets", JSONArray().apply {
            metas.values.forEach { m ->
                put(JSONObject().put("asset_id", m.assetId).put("uri", m.uri).put("media", m.name).put("folder", m.folder)
                    .put("event_id", m.eventId ?: JSONObject.NULL).put("favorite", m.favorite).put("tags", JSONArray(m.tags))
                    .put("comment", m.comment).put("created", m.created).put("crop", m.crop)
                    .put("title", m.title).put("ocr", m.ocr).put("kind", m.kind).put("doc_date", m.docDate))
            }
        })
        runCatching { file.writeText(o.toString()) }
    }

    private fun load() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        o.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { events.add(Event(it.getString("id"), it.getString("name"), it.getString("start"), it.getString("end"))) } }
        o.optJSONArray("assets")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { j ->
                val tags = j.optJSONArray("tags")?.let { t -> MutableList(t.length()) { t.getString(it) } } ?: mutableListOf()
                val m = Meta(j.getString("asset_id"), j.getString("uri"), j.getString("media"), j.optString("folder"),
                    if (j.isNull("event_id")) null else j.optString("event_id"), j.optBoolean("favorite"), tags, j.optString("comment"), j.optLong("created"))
                m.crop = j.optString("crop"); m.title = j.optString("title"); m.ocr = j.optString("ocr"); m.kind = j.optString("kind"); m.docDate = j.optString("doc_date")
                metas[m.uri] = m
            }
        }
    }

    private fun eventJson(e: Event) = JSONObject().put("id", e.id).put("name", e.name).put("start", e.start).put("end", e.end)

    private fun writeSidecars(m: Meta) {
        val base = m.name.substringBeforeLast('.')
        val dir = "Documents/ThetaCamera/${m.folder}"
        val ev = event(m.eventId)
        val json = JSONObject()
            .put("schema", "theta.capture/1").put("asset_id", m.assetId).put("media", m.name).put("folder", m.folder)
            .put("event", ev?.let { eventJson(it) } ?: JSONObject.NULL)
            .put("favorite", m.favorite).put("rating", if (m.favorite) 1 else 0)
            .put("tags", JSONArray(m.tags)).put("comment", m.comment)
            .put("title", m.title.ifBlank { JSONObject.NULL }).put("kind", m.kind.ifBlank { JSONObject.NULL }).put("doc_date", m.docDate.ifBlank { JSONObject.NULL }).put("ocr_text", m.ocr.ifBlank { JSONObject.NULL })
            .put("display_crop", if (m.crop.isBlank()) JSONObject.NULL else JSONObject().put("type", "clap-center").put("size", m.crop))
            .put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(m.created)))
        upsert(dir, "$base.theta.json", "application/json", json.toString(2).toByteArray())
        upsert(dir, "$base.xmp", "application/octet-stream", xmp(m, ev).toByteArray())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** xmp:Rating（☆は 1）・dc:subject（タグ＋イベント名）・dc:description（コメント） */
    private fun xmp(m: Meta, ev: Event?): String {
        val subjects = (m.tags + listOfNotNull(ev?.name)).distinct()
        return buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            append("  <rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmp:Rating=\"${if (m.favorite) 1 else 0}\">\n")
            if (subjects.isNotEmpty()) append("   <dc:subject><rdf:Bag>${subjects.joinToString("") { "<rdf:li>${esc(it)}</rdf:li>" }}</rdf:Bag></dc:subject>\n")
            if (m.title.isNotBlank()) append("   <dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(m.title)}</rdf:li></rdf:Alt></dc:title>\n")
            if (m.comment.isNotBlank()) append("   <dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(m.comment)}</rdf:li></rdf:Alt></dc:description>\n")
            append("  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>\n")
        }
    }

    private fun upsert(dir: String, name: String, mime: String, bytes: ByteArray) {
        val cr = context.contentResolver
        val col = MediaStore.Files.getContentUri("external")
        val existing = cr.query(col, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf("$dir/", name), null)
            ?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(col, c.getLong(0)) else null }
        val target = existing ?: cr.insert(col, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime); put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        }) ?: return
        cr.openOutputStream(target, "wt")?.use { it.write(bytes) }
    }
}
