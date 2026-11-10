package com.theta.camera.hatch

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * HEIF に「表示範囲（clap：clean aperture）」を足す。画素は全部残し、対応したビューアは範囲だけを表示する。
 *
 * - 主画像（pitm）に clap プロパティを付ける。範囲は画像の中央、幅・高さは符号化された画像の画素（回転前）。
 * - 変換プロパティは clap → irot → imir の順で適用されるので、irot/imir より前に並べる（ISO/IEC 23008-12）。
 * - meta が大きくなる分、iloc のファイル内オフセット（mdat を指すもの）をずらす。
 */
object HeifClap {
    private class Box(val type: String, val start: Int, val size: Int, val header: Int) {
        val end get() = start + size
        val body get() = start + header
    }

    private fun u32(b: ByteArray, o: Int) = ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun uN(b: ByteArray, o: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 8) or (b[o + i].toLong() and 0xff); return v }
    private fun putN(b: ByteArray, o: Int, n: Int, v: Long) { for (i in 0 until n) b[o + i] = (v ushr (8 * (n - 1 - i))).toByte() }

    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p); var header = 8
            if (size == 1L) { size = uN(b, p + 8, 8); header = 16 } else if (size == 0L) size = (to - p).toLong()
            require(size >= header && p + size <= to) { "壊れた箱" }
            out.add(Box(String(b, p + 4, 4, Charsets.ISO_8859_1), p, size.toInt(), header))
            p += size.toInt()
        }
        return out
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()

    /** 主画像に中央の表示範囲 cropW×cropH を付けた HEIF を返す */
    fun insert(src: ByteArray, cropW: Int, cropH: Int): ByteArray {
        val top = boxes(src, 0, src.size)
        val meta = top.first { it.type == "meta" }
        require(meta.header == 8) { "meta の形に非対応" }
        val metaKids = boxes(src, meta.body + 4, meta.end)
        val pitm = metaKids.first { it.type == "pitm" }
        val primary = if (src[pitm.body].toInt() == 0) u16(src, pitm.body + 4).toLong() else u32(src, pitm.body + 4)
        val iprp = metaKids.first { it.type == "iprp" }
        val iprpKids = boxes(src, iprp.body, iprp.end)
        val ipco = iprpKids.first { it.type == "ipco" }
        val ipma = iprpKids.first { it.type == "ipma" }
        val props = boxes(src, ipco.body, ipco.end)
        require(props.none { it.type == "clap" }) { "すでに clap がある" }

        // clap：幅・高さ（分数）と、中心からのずれ（0）
        val clap = box("clap", ByteBuffer.allocate(32).putInt(cropW).putInt(1).putInt(cropH).putInt(1).putInt(0).putInt(1).putInt(0).putInt(1).array())
        val clapIndex = props.size + 1
        val newIpco = box("ipco", src.copyOfRange(ipco.body, ipco.end) + clap)

        // ipma を読み直し、主画像の関連に clap を足す（irot・imir より前）
        val ver = src[ipma.body].toInt()
        var flags = uN(src, ipma.body + 1, 3).toInt()
        var p = ipma.body + 4
        val entryCount = u32(src, p).toInt(); p += 4
        val entries = mutableListOf<Pair<Long, MutableList<Pair<Boolean, Int>>>>()
        repeat(entryCount) {
            val id = if (ver < 1) u16(src, p).toLong().also { p += 2 } else u32(src, p).also { p += 4 }
            val n = src[p].toInt() and 0xff; p += 1
            val list = mutableListOf<Pair<Boolean, Int>>()
            repeat(n) {
                if (flags and 1 == 1) { val v = u16(src, p); p += 2; list.add((v and 0x8000 != 0) to (v and 0x7fff)) }
                else { val v = src[p].toInt() and 0xff; p += 1; list.add((v and 0x80 != 0) to (v and 0x7f)) }
            }
            entries.add(id to list)
        }
        val entry = entries.firstOrNull { it.first == primary } ?: (primary to mutableListOf<Pair<Boolean, Int>>()).also { entries.add(it) }
        val at = entry.second.indexOfFirst { (_, idx) -> props.getOrNull(idx - 1)?.type in setOf("irot", "imir") }
        entry.second.add(if (at < 0) entry.second.size else at, true to clapIndex)
        if (clapIndex > 127) flags = flags or 1
        val ma = ByteArrayOutputStream()
        ma.write(ver); ma.write(byteArrayOf((flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()))
        ma.write(ByteBuffer.allocate(4).putInt(entries.size).array())
        entries.forEach { (id, list) ->
            if (ver < 1) ma.write(ByteBuffer.allocate(2).putShort(id.toInt().toShort()).array()) else ma.write(ByteBuffer.allocate(4).putInt(id.toInt()).array())
            ma.write(list.size)
            list.forEach { (ess, idx) ->
                if (flags and 1 == 1) ma.write(ByteBuffer.allocate(2).putShort(((if (ess) 0x8000 else 0) or idx).toShort()).array())
                else ma.write((if (ess) 0x80 else 0) or idx)
            }
        }
        val newIpma = box("ipma", ma.toByteArray())

        val iprpBody = ByteArrayOutputStream()
        iprpKids.forEach { k -> iprpBody.write(when (k) { ipco -> newIpco; ipma -> newIpma; else -> src.copyOfRange(k.start, k.end) }) }
        val newIprp = box("iprp", iprpBody.toByteArray())
        val metaBody = ByteArrayOutputStream()
        metaBody.write(src, meta.body, 4)
        metaKids.forEach { k -> metaBody.write(if (k === iprp) newIprp else src.copyOfRange(k.start, k.end)) }
        val newMeta = box("meta", metaBody.toByteArray())
        val delta = newMeta.size - meta.size

        // iloc のうち、meta より後ろ（mdat）を指すオフセットを delta ずらす
        val ilocOld = metaKids.firstOrNull { it.type == "iloc" }
        if (ilocOld != null && delta != 0) {
            val ilocKidsNew = boxes(newMeta, 12, newMeta.size)
            val iloc = ilocKidsNew.first { it.type == "iloc" }
            fixIloc(newMeta, iloc.body, meta.end.toLong(), delta)
        }
        return src.copyOfRange(0, meta.start) + newMeta + src.copyOfRange(meta.end, src.size)
    }

    private fun fixIloc(b: ByteArray, body: Int, oldMetaEnd: Long, delta: Int) {
        val ver = b[body].toInt()
        var p = body + 4
        val offSize = (b[p].toInt() shr 4) and 0xf; val lenSize = b[p].toInt() and 0xf
        val baseSize = (b[p + 1].toInt() shr 4) and 0xf; val idxSize = if (ver == 1 || ver == 2) b[p + 1].toInt() and 0xf else 0
        p += 2
        val count = if (ver < 2) u16(b, p).also { p += 2 } else u32(b, p).toInt().also { p += 4 }
        repeat(count) {
            p += if (ver < 2) 2 else 4                      // item_ID
            var method = 0
            if (ver == 1 || ver == 2) { method = u16(b, p) and 0xf; p += 2 }
            p += 2                                          // data_reference_index
            val basePos = p; val base = uN(b, p, baseSize); p += baseSize
            val extents = u16(b, p); p += 2
            var baseFixed = false
            repeat(extents) {
                p += idxSize
                val offPos = p; val off = uN(b, p, offSize); p += offSize
                p += lenSize
                if (method == 0) {
                    if (baseSize > 0 && base > 0) {
                        if (!baseFixed && base >= oldMetaEnd) { putN(b, basePos, baseSize, base + delta); baseFixed = true }
                    } else if (offSize > 0 && off >= oldMetaEnd) putN(b, offPos, offSize, off + delta)
                }
            }
        }
    }

    /**
     * 画面に見えている範囲を、センサーの画像（回転前）の幅・高さの割合で返す。
     * 映像は FILL（画面いっぱい、はみ出しは切る）で中央に表示している前提。
     * @param rotation 画像を画面に表示するときの回転（度）
     */
    fun visibleFraction(imageW: Int, imageH: Int, rotation: Int, viewW: Int, viewH: Int): Pair<Double, Double> {
        val swap = rotation % 180 != 0
        val dispW = if (swap) imageH else imageW; val dispH = if (swap) imageW else imageH
        val dispAspect = dispW.toDouble() / dispH; val viewAspect = viewW.toDouble() / viewH
        val (fw, fh) = if (viewAspect > dispAspect) 1.0 to dispAspect / viewAspect else viewAspect / dispAspect to 1.0
        return if (swap) fh to fw else fw to fh
    }
}
