package com.bingemote

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Whole-header geometry on real rendered screens (not icon bounds alone): for Home, Movies, Settings, Your PC and
 * Mouse it logs, in px and dp, the header button top/bottom/height, the title (leftmost text on the button's row)
 * top/bottom/centre, and the first content edge (top of the first visible text/desc node below the button). It
 * also walks Home -> Settings -> Home through real taps and samples the button during the transition. Every
 * screen must put the button at the same top with the same height, the title centred on the button, and the
 * first content edge at the same distance below the button. Numbers are logged with tag HeaderGeom.
 */
class HeaderGeometryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val dens = context.resources.displayMetrics.density
    private fun dp(px: Int) = "%.1f".format(px / dens)

    data class H(val screen: String, val hdr: Rect, val btn: Rect, val title: Rect?, val titleText: String?, val content: Rect?, val contentText: String?)

    private fun launch(screen: String) {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", screen))
    }

    private fun buttonDesc(screen: String) = if (screen == "main") "Settings" else "Back"

    private fun measure(screen: String): H {
        val desc = buttonDesc(screen)
        var last: Exception? = null
        repeat(5) {
            try {
                val node = device.wait(Until.findObject(By.desc(desc)), 15000)
                assertNotNull("'$desc' button not found on $screen", node)
                val btn = node!!.visibleBounds
                val hdrNode = device.wait(Until.findObject(By.res("screen_header")), 5000)
                assertNotNull("screen_header container not found on $screen", hdrNode)
                val hdr = hdrNode!!.visibleBounds
                Thread.sleep(700) // let the entry animation settle
                val all = device.findObjects(By.pkg(context.packageName))
                val items = all.mapNotNull { n ->
                    try {
                        val t = n.text ?: n.contentDescription
                        if (t.isNullOrBlank()) null else Triple(n.visibleBounds, t, n.contentDescription == desc)
                    } catch (e: androidx.test.uiautomator.StaleObjectException) { null }
                }
                val row = items.filter { !it.third && it.first.height() > 0 &&
                    Math.abs(it.first.centerY() - btn.centerY()) <= btn.height() && it.first.right <= device.displayWidth * 7 / 10 }
                // Title = the leftmost text on the button's row that is not the button itself.
                val title = if (screen == "main") row.filter { it.first.left > btn.right || it.first.left > 0 }.minByOrNull { it.first.left } else row.filter { it.first.left > btn.right }.minByOrNull { it.first.left }
                val below = items.filter { !it.third && it.first.top >= btn.bottom && it.first != title?.first }
                val content = below.minByOrNull { it.first.top }
                return H(screen, hdr, btn, title?.first, title?.second?.toString(), content?.first, content?.second?.toString())
            } catch (e: androidx.test.uiautomator.StaleObjectException) { last = e; Thread.sleep(500) }
        }
        throw last!!
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        val h = (bmp.height * 0.22).toInt()
        val dir = File(context.getExternalFilesDir(null), "header-geometry").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { Bitmap.createBitmap(bmp, 0, 0, bmp.width, h).compress(Bitmap.CompressFormat.PNG, 100, it) }
        device.executeShellCommand("screencap -p /sdcard/hg-$name.png")
    }

    private fun report(h: H): String {
        val b = h.btn; val t = h.title; val c = h.content
        return "screen=${h.screen} density=$dens header[top=${h.hdr.top} bottom=${h.hdr.bottom} h=${h.hdr.height()} | dp ${dp(h.hdr.top)}/${dp(h.hdr.bottom)}/${dp(h.hdr.height())}] btn[top=${b.top} bottom=${b.bottom} h=${b.height()} | dp ${dp(b.top)}/${dp(b.bottom)}/${dp(b.height())}] " +
            "title='${h.titleText}'[top=${t?.top} bottom=${t?.bottom} centerY=${t?.centerY()} left=${t?.left}] btnCenterY=${b.centerY()} " +
            "content='${h.contentText}'[top=${c?.top}] contentGapBelowBtn=${c?.let { it.top - b.bottom }}px/${c?.let { dp(it.top - b.bottom) }}dp"
    }

    @Test fun wholeHeaderIsIdenticalOnEveryScreen() {
        val screens = listOf("main", "movies", "settings", "yourpc", "mouse")
        val hs = screens.map { s -> launch(s); val h = measure(s); shot("hdr-$s"); Log.i("HeaderGeom", report(h)); h }
        val home = hs[0]
        val sb = StringBuilder()
        for (h in hs.drop(1)) {
            if (h.hdr.top != home.hdr.top || h.hdr.bottom != home.hdr.bottom || h.hdr.height() != home.hdr.height())
                sb.append("${h.screen}: header container top/bottom/height ${h.hdr.top}/${h.hdr.bottom}/${h.hdr.height()} vs Home ${home.hdr.top}/${home.hdr.bottom}/${home.hdr.height()}\n")
            if (h.btn.top != home.btn.top) sb.append("${h.screen}: button top ${h.btn.top}px (${dp(h.btn.top)}dp) vs Home ${home.btn.top}px (${dp(home.btn.top)}dp)\n")
            if (h.btn.height() != home.btn.height()) sb.append("${h.screen}: button height ${h.btn.height()} vs Home ${home.btn.height()}\n")
        }
        for (h in hs) if (Math.abs(h.btn.centerY() - h.hdr.centerY()) > 1) sb.append("${h.screen}: button centre ${h.btn.centerY()} vs header container centre ${h.hdr.centerY()}\n")
        for (h in hs) {
            val t = h.title ?: continue
            if (Math.abs(t.centerY() - h.btn.centerY()) > 1) sb.append("${h.screen}: title centre ${t.centerY()} vs button centre ${h.btn.centerY()}\n")
        }
        val subs = hs.drop(1).filter { it.content != null && it.screen != "mouse" }
        val ref = subs.firstOrNull()
        for (h in subs) if (h.content!!.top - h.btn.bottom != ref!!.content!!.top - ref.btn.bottom)
            sb.append("${h.screen}: first-content gap below header button ${h.content.top - h.btn.bottom}px vs ${ref.screen} ${ref.content!!.top - ref.btn.bottom}px\n")
        Log.i("HeaderGeom", "mismatches: ${if (sb.isEmpty()) "none" else sb.toString().replace("\n", " | ")}")
        assertTrue("Header geometry differs between screens:\n$sb", sb.isEmpty())
    }

    @Test fun homeToSettingsAndBackKeepsTheSameHeader() {
        launch("main")
        val h0 = measure("main"); Log.i("HeaderGeom", "T0 " + report(h0))
        device.findObject(By.desc("Settings")).click()
        val samples = mutableListOf<Rect>()
        val end = System.currentTimeMillis() + 900
        while (System.currentTimeMillis() < end) {
            device.findObject(By.desc("Back"))?.let { try { samples.add(it.visibleBounds) } catch (_: Exception) {} }
            Thread.sleep(60)
        }
        Log.i("HeaderGeom", "transition Back-button samples: " + samples.joinToString { "${it.top}/${it.height()}" })
        val hs = measure("settings"); shot("hdr-transition-settings"); Log.i("HeaderGeom", "T1 " + report(hs))
        device.findObject(By.desc("Back")).click()
        val h2 = measure("main"); shot("hdr-transition-home-again"); Log.i("HeaderGeom", "T2 " + report(h2))
        assertTrue("button moved during the transition: ${samples.map { it.top }.toSet()}", samples.map { it.top }.toSet().size <= 1)
        assertEquals("Settings header container top/bottom vs Home", listOf(h0.hdr.top, h0.hdr.bottom), listOf(hs.hdr.top, hs.hdr.bottom))
        assertEquals("Settings button top vs Home", h0.btn.top, hs.btn.top)
        assertEquals("button height", h0.btn.height(), hs.btn.height())
        assertEquals("Home header container after returning", listOf(h0.hdr.top, h0.hdr.bottom), listOf(h2.hdr.top, h2.hdr.bottom))
        assertEquals("Home header after returning: button top", h0.btn.top, h2.btn.top)
        assertEquals("Home header after returning: button height", h0.btn.height(), h2.btn.height())
        assertEquals("Home content edge after returning", h0.content?.top, h2.content?.top)
    }
}
