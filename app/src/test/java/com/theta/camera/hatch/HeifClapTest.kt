package com.theta.camera.hatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HeifClapTest {
    private fun sample() = javaClass.getResourceAsStream("/sample.heic")!!.readBytes()

    @Test fun insertsClapAndKeepsFileReadable() {
        val src = sample()
        val out = HeifClap.insert(src, 400, 300)
        assertTrue(String(out, Charsets.ISO_8859_1).contains("clap"))
        assertTrue(out.size > src.size)
        // libheif で読めるか確かめるために書き出す（-Dheif.out=パス）
        System.getProperty("heif.out")?.let { File(it).writeBytes(out) }
    }

    @Test fun visibleFractionPortraitScreen() {
        // センサー 4000x3000 を 90 度回して縦の画面（1000x2000）に FILL：表示は 3000x4000 の縦長、画面はもっと細長い → 横が切れる
        val (fw, fh) = HeifClap.visibleFraction(4000, 3000, 90, 1000, 2000)
        // 表示の横（センサーの高さ）が 0.375×1/0.5 … 画面の縦横比 0.5 ÷ 表示の縦横比 0.75 = 0.667
        assertEquals(1.0, fw, 1e-9)
        assertEquals(0.5 / 0.75, fh, 1e-9)
    }

    @Test fun visibleFractionWideScreen() {
        val (fw, fh) = HeifClap.visibleFraction(4000, 3000, 0, 1600, 900)
        assertEquals(1.0, fw, 1e-9)
        assertEquals((4.0 / 3.0) / (16.0 / 9.0), fh, 1e-9)
    }
}
