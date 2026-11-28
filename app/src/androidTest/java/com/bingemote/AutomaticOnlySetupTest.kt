package com.bingemote

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutomaticOnlySetupTest {
    private companion object {
        // Selector anchors, copied verbatim from SetupScreen.kt (L214 title, L292
        // pair-code placeholder, L280 pair-card body, L48 demo helper row "MY-PC").
        // One definition shared by the control and the primary below: the two tests
        // can never drift onto different selectors for the same element.
        const val SETUP_TITLE = "Find your PC"
        const val SCROLL_VIEW_CLASS = "android.widget.ScrollView"
        const val PAIR_CODE_LABEL = "Code from your PC"
        const val PAIR_CARD_BODY = "Enter the code BingeMote is showing on your PC"
        const val DEMO_PC_ROW = "MY-PC  ("
    }
    // Failure evidence: registered like the 12 sibling UI test classes. failed()
    // writes bm-dump-<method>.{png,uix,txt} + the paired bm-dump2-* recurrence
    // capture to getExternalFilesDir on ANY failure - including the hide asserts
    // below, which have no capture of their own (the *-fail files cover only the
    // reveal asserts). Both CI scripts clear and pull exactly these names.
    @get:Rule val failureDump = FailureDumpRule()
    @org.junit.Before fun dismissSystemDialogs() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val wait = device.findObject(By.text("Wait"))
        wait?.click()
        device.waitForIdle()
    }

    @Test fun updateSuccessSharesVersionRowAndExpires() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "settings").putExtra("updateResult", "You're up to date."))
        // Rig intentionally starts at bottom, so the Settings header is offscreen.
        assertTrue(device.wait(Until.hasObject(By.text("Version " + BuildConfig.VERSION_NAME)), 4000))
        val version = device.findObject(By.text("Version " + BuildConfig.VERSION_NAME))
        val success = device.findObject(By.text("You're up to date."))
        assertFalse(device.hasObject(By.text("Updates")))
        assertNotNull(version)
        assertNotNull(success)
        assertEquals(version.visibleBounds.centerY(), success.visibleBounds.centerY())
        assertTrue(success.visibleBounds.left > version.visibleBounds.right)
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-updates-success.png"))
        // Expiry proven on PIXELS, not the a11y tree: the framework tree can keep a
        // visible-to-user ghost node long after the message leaves composition
        // (run 46097348603: pixels empty at failure time, the uix dump at failure AND
        // the later recurrence dump still listed the node). The message region's ink
        // must vanish within the same 7s, same meaning: it must actually leave the
        // screen. Ink is self-calibrating - background = 5th-percentile luminance of
        // the region, ink = pixels more than 40 above it. Real frames from that run:
        // success 2138 ink pixels, expired 0.
        val msgBounds = Rect(success.visibleBounds)
        val probe = java.io.File(ctx.cacheDir, "expiry-probe.png")
        // Positive baseline, measured at runtime - never assumed from prior
        // calibration: the painted message must put real ink in its own region, so a
        // blank or mis-located bounds can never pass the zero-ink expiry check
        // vacuously. Floor 500 ink pixels: the real painted frame measures 2138 in
        // this region (run 46097348603), a blank region measures 0.
        val baselineInk = regionInk(device, probe, msgBounds)
        assertNotNull("success frame unreadable for the baseline ink measure", baselineInk)
        assertTrue("painted message region must contain text ink (real frame 2138, floor 500)",
            baselineInk!! >= 500)
        val goneBy = System.currentTimeMillis() + 7000
        var gone = false
        while (System.currentTimeMillis() < goneBy) {
            device.waitForIdle()
            val ink = regionInk(device, probe, msgBounds)
            if (ink != null && ink == 0) { gone = true; break }
            Thread.sleep(250)
        }
        // The expired baseline is painted BEFORE the proof asserts: on a failure the
        // frame is exactly the evidence needed, so it must exist either way.
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-updates-expired.png"))
        assertTrue(device.hasObject(By.text("Version " + BuildConfig.VERSION_NAME)))
        assertTrue("'You're up to date.' must expire from the screen within 7s", gone)
    }

    @Test fun cancelReturnsToSettingsAndBottomBackIsAbsent() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Store(ctx).setupDone = true
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "setup").putExtra("pairdemo", "true"))
        assertTrue(device.wait(Until.hasObject(By.text("Cancel")), 10000))
        assertFalse(device.hasObject(By.text("Back")))
        device.findObject(By.text("Cancel")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Settings")), 5000))
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-cancel-settings.png"))
    }

    /**
     * Positive presence/selector control for the hide-transition poll in
     * pairEntryNeedsSelectedPcOrConfirmedAddress. Same screen, same reveal-with-scroll
     * loop, same By.text target, and the SAME Until.gone poll mechanism as the primary
     * assert: absent before selection, present with the pair card after it, and the
     * gone poll must not report gone while the selection stands. If the selector or
     * the gone poll were vacuous, the primary test's assertFalse could pass without
     * proving the hide, and this control fails.
     */
    @Test fun pairCodeEntryPresenceTracksSelection() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "setup").putExtra("pairdemo", "true"))
        assertTrue(device.wait(Until.hasObject(By.text(SETUP_TITLE)), 10000))
        assertFalse("pair-code entry visible before any selection",
            device.hasObject(By.text(PAIR_CODE_LABEL)))
        val row = device.findObject(By.textContains(DEMO_PC_ROW))
        assertNotNull("demo PC row not found", row)
        row.click()
        // Identical reveal-with-scroll to the primary test: the card can render below the
        // fold, so a presence assert without the scroll fails on layout, not on behavior.
        val revealBy = System.currentTimeMillis() + 10000
        while (!device.hasObject(By.text(PAIR_CODE_LABEL)) && System.currentTimeMillis() < revealBy) {
            device.wait(Until.hasObject(By.text(PAIR_CODE_LABEL)), 700)
            if (!device.hasObject(By.text(PAIR_CODE_LABEL))) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 2, 20)
            }
        }
        assertTrue("pair-code entry never appeared after selection", device.hasObject(By.text(PAIR_CODE_LABEL)))
        // Selected-state proof: the pair card renders only while chosen != null.
        assertTrue("pair card body missing - selection state not established",
            device.hasObject(By.textContains(PAIR_CARD_BODY)))
        // Non-vacuity of the gone poll itself: while the selection stands, Until.gone on the
        // same selector must keep timing out (return false) for a bounded window.
        assertFalse("gone poll reported gone while the selection still stood",
            device.wait(Until.gone(By.text(PAIR_CODE_LABEL)), 2000))
        assertTrue("pair-code entry vanished while the selection still stood",
            device.hasObject(By.text(PAIR_CODE_LABEL)))
    }

    @Test fun pairEntryNeedsSelectedPcOrConfirmedAddress() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "setup").putExtra("pairdemo", "true"))
        assertTrue(device.wait(Until.hasObject(By.text(SETUP_TITLE)), 10000))
        assertFalse(device.hasObject(By.text(PAIR_CODE_LABEL)))
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-before-selection.png"))
        val pcRow = device.findObject(By.textContains(DEMO_PC_ROW))
        assertNotNull("demo PC row not found", pcRow)
        pcRow.click()
        // Condition-driven reveal (review-driven): the pair-code section can render
        // without any scroll on large screens and can need a scroll on others, so
        // alternate a bounded render wait with a conditional scroll instead of fixed
        // swipes/sleeps. Run 45435505970 failed this assert with all four captures
        // absent (adb "No such file" stubs in the artifact), so on failure also
        // capture the actual screen state and hierarchy as evidence.
        val revealBy = System.currentTimeMillis() + 10000
        while (!device.hasObject(By.text(PAIR_CODE_LABEL)) && System.currentTimeMillis() < revealBy) {
            device.wait(Until.hasObject(By.text(PAIR_CODE_LABEL)), 700)
            if (!device.hasObject(By.text(PAIR_CODE_LABEL))) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 2, 20)
            }
        }
        if (!device.hasObject(By.text(PAIR_CODE_LABEL))) {
            device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-selected-pc-fail.png"))
            device.dumpWindowHierarchy(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-selected-pc-fail.xml"))
        }
        assertTrue(device.hasObject(By.text(PAIR_CODE_LABEL)))
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-selected-pc.png"))
        device.swipe(device.displayWidth / 2, device.displayHeight / 3,
            device.displayWidth / 2, device.displayHeight * 4 / 5, 25)
        Thread.sleep(400)
        repeat(4) {
            if (!device.hasObject(By.text("Or type the PC's address:"))) {
                device.swipe(device.displayWidth / 2, device.displayHeight / 3,
                    device.displayWidth / 2, device.displayHeight * 4 / 5, 20)
                Thread.sleep(200)
            }
        }
        assertTrue(device.hasObject(By.text("Or type the PC's address:")))
        // Pre-condition pin (review-driven): the gone poll after setText is only
        // meaningful if the pair-code entry was present, with the selection still
        // standing, immediately before the address clears it - otherwise the poll
        // proves nothing. The pair card renders below the address section, so reveal
        // it again with the same condition loop, pin both facts, then return to the
        // field. Scrolling cannot clear the selection; only the setText below can
        // (SetupScreen.kt address onValueChange clears chosen).
        val pinBy = System.currentTimeMillis() + 10000
        while (!device.hasObject(By.text(PAIR_CODE_LABEL)) && System.currentTimeMillis() < pinBy) {
            device.wait(Until.hasObject(By.text(PAIR_CODE_LABEL)), 700)
            if (!device.hasObject(By.text(PAIR_CODE_LABEL))) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 2, 20)
            }
        }
        assertTrue("pair-code entry not present immediately before the address clears the selection",
            device.hasObject(By.text(PAIR_CODE_LABEL)))
        assertTrue("pair card body missing - selection not standing before the clear",
            device.hasObject(By.textContains(PAIR_CARD_BODY)))
        // Same-viewport precondition (test-proof strategy, no scrolling proof needed). ONE accessibility
        // tree read (readSetup) must show the address label, the "Use this address" button (label AND its
        // 48dp tile) and the pair-code label, all fully inside the effective viewport Ve, where V is the one container
        // (scrollable, or the Compose ScrollView class) shared by the address label and the button label (two nested common
        // containers are ambiguous and fail) and Ve is V's rect intersected with the window and every ancestor's bounds. Small
        // swipes may only be used to REACH that state; nothing after typing scrolls. If it cannot be established, fail closed.
        val typed = "192.0.2.20"
        var before: SetupView? = null
        var nudges = 0
        while (nudges <= 8) {
            before = readSetup()
            if (before != null && before.preconditionProblem() == null) break
            if (before?.f == null)
                device.swipe(device.displayWidth / 2, device.displayHeight * 45 / 100,
                    device.displayWidth / 2, device.displayHeight * 70 / 100, 20)
            else
                device.swipe(device.displayWidth / 2, device.displayHeight * 70 / 100,
                    device.displayWidth / 2, device.displayHeight * 55 / 100, 20)
            device.waitForIdle()
            nudges++
        }
        val finalRead = readSetup()
        val preWhy = if (finalRead == null) "no accessibility snapshot" else finalRead.preconditionProblem()
        if (preWhy != null) {
            fail("same-viewport precondition not established before typing (fail closed): $preWhy")
        }
        val pre = finalRead!!
        // findObjects nodes can go stale between the query and the bounds read when a
        // recomposition lands in between (run 46097348603: StaleObjectException on
        // getVisibleBounds right here). Re-query fresh on stale - same discipline as
        // this file's single-tree reads - worst case 3 attempts.
        var addressField: androidx.test.uiautomator.UiObject2? = null
        var fieldAttempts = 0
        while (addressField == null && fieldAttempts < 3) {
            fieldAttempts++
            try {
                val f = device.findObjects(By.clazz("android.widget.EditText")).minBy { it.visibleBounds.top }
                f.text = typed
                addressField = f
            } catch (s: androidx.test.uiautomator.StaleObjectException) { device.waitForIdle() }
        }
        assertNotNull("address field unreadable across 3 fresh queries", addressField)
        // ACTION_SET_TEXT applies asynchronously through the IME/accessibility path, so a
        // read on the very next frame can race the state update (saw one CI run read back
        // empty while the identical tree passed in its sibling run). Poll briefly instead.
        val textDeadline = System.currentTimeMillis() + 3000
        var readBack: String? = null
        while (readBack != "192.0.2.20" && System.currentTimeMillis() < textDeadline) {
            readBack = try { addressField!!.text } catch (s: androidx.test.uiautomator.StaleObjectException) { break }
            if (readBack != "192.0.2.20") Thread.sleep(100)
        }
        assertEquals("192.0.2.20", readBack)
        // Same async timing class as the setText read-back above: typing the address
        // clears the selection, which removes the pair card, but the accessibility
        // SetText -> onValueChange -> recomposition chain is several asynchronous hops
        // and a one-shot gone-window can land inside them on a loaded emulator
        // (run 45424136265 on the old tree; runs 45547260244 / 45549797139 on the new
        // one - the identical class passes in the lighter focused-ui job and fails only
        // here, last suite on a hot device). The 10s figure is a worst-case CI wait
        // budget for the condition (matching this file's other transition budgets),
        // not a product-latency measurement; a healthy transition converges in the
        // first slices. The positive control (pairCodeEntryPresenceTracksSelection)
        // pins the same selector and gone poll from the presence side.
        // IME first: setText can raise the keyboard over the lower half of the screen
        // (where the pair card renders), distorting the tree the poll reads.
        device.waitForIdle()
        // Dismiss the keyboard only if it is shown (a blind back could leave the screen), and fail
        // closed if its state cannot be read: a keyboard over the card region would make an absence
        // read meaningless. (Window-list reads can lag a frame: one waitForIdle after the back.)
        fun imeShown(): Boolean {
            // Interactive window list (API 34 emulator, same source UiDevice reads its roots from): a
            // readable list always contains the app's own window, so an empty list means "not
            // readable" and fails closed instead of reading as "no keyboard".
            val windows = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            assertTrue("window list not readable - cannot rule out a keyboard over the card region",
                windows.isNotEmpty())
            return windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        if (imeShown()) { device.pressBack(); device.waitForIdle() }
        assertFalse("keyboard still shown - the card region may be covered", imeShown())
        // Verdict, from fresh single-tree reads only, no scrolling. The bug (typing does not clear the
        // selection) keeps chosen set, which (SetupScreen L267-269 and L273) shows a "Selected: host:port" row
        // 8dp under the button tile AND the pair card. Both absent is required. Absence only counts when
        // the region where the row would start is observably inside the viewport in the SAME tree:
        // tile and address label fully inside Ve (top included), the same container (class, path, rect) and window as before
        // typing (its scrollable flag may differ: the page can fit once the card is gone), the typed text present in the field, and
        // the full first line of the row (address-label height as the same-13sp-style stand-in, +4 px for rounding) between Ve's
        // top and bottom. A missing scrollable flag is never read as "fits": the arithmetic is.
        // Geometry is re-read on every tick; a missing node or violated condition is an invalid tick,
        // never a pass, and a timeout fails. Two consecutive equal reads are required. This is a
        // conservative visibility HEURISTIC: Compose/UiAutomator exposure of clipped nodes is unproven,
        // so nothing here claims a false PASS is impossible; the clamp case may fail RED by design.
        val verdictBy = System.currentTimeMillis() + 10000
        var verdict: SetupView? = null
        var lastWhy = "no accessibility snapshot"
        var prevKey: String? = null
        while (verdict == null && System.currentTimeMillis() < verdictBy) {
            device.waitForIdle()
            val now = readSetup()
            if (now == null) { prevKey = null; Thread.sleep(250); continue }
            val why = now.postProblem(typed, pre)
            if (why != null) { lastWhy = why; prevKey = null; Thread.sleep(250); continue }
            if (now.selected.isNotEmpty() || now.l.isNotEmpty()) {
                lastWhy = "still shown after typing: Selected rows=${now.selected.size}, pair-code labels=${now.l.size}; ${now.describe()}"
                prevKey = null; Thread.sleep(250); continue
            }
            val key = now.describe()
            if (key == prevKey) verdict = now else { prevKey = key; Thread.sleep(250) }
        }
        assertNotNull("address typing did not clear the selection, or the absence was not observable: $lastWhy", verdict)
        repeat(3) {
            if (!device.hasObject(By.text("Use this address"))) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 3, 20)
                Thread.sleep(250)
            }
        }
        assertTrue(device.wait(Until.hasObject(By.text("Use this address")), 3000))
        device.findObject(By.text("Use this address")).click()
        // Same condition-driven reveal as the selection case above: after the
        // address is confirmed the pair-code entry returns (with the Selected row),
        // and the fixed sleep+swipe pattern missed it in publish runs 45445415420 /
        // 45445418591 (line-126 assert, sibling jobs green on the same tree).
        val confirmRevealBy = System.currentTimeMillis() + 10000
        while (!device.hasObject(By.text(PAIR_CODE_LABEL)) && System.currentTimeMillis() < confirmRevealBy) {
            device.wait(Until.hasObject(By.text(PAIR_CODE_LABEL)), 700)
            if (!device.hasObject(By.text(PAIR_CODE_LABEL))) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 2, 20)
            }
        }
        if (!device.hasObject(By.text(PAIR_CODE_LABEL))) {
            device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-confirmed-address-fail.png"))
            device.dumpWindowHierarchy(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-confirmed-address-fail.xml"))
        }
        assertTrue(device.hasObject(By.text(PAIR_CODE_LABEL)))
        // Positive control for the verdict above, through the SAME routine and the SAME containment
        // rules (readSetup / SetupView): in the confirmed-address state exactly one non-empty
        // "Selected:" row and the pair-code label are observed inside V with the typed text in the
        // field. Without this, the absence read above would not show that it can see the row at all.
        var control: SetupView? = null
        var controlWhy = "no accessibility snapshot"
        val controlBy = System.currentTimeMillis() + 6000
        while (control == null && System.currentTimeMillis() < controlBy) {
            device.waitForIdle()
            val now = readSetup()
            controlWhy = now?.controlProblem(typed, "Selected: $typed:${HelperClient.DEFAULT_PORT}", pre) ?: "no accessibility snapshot"
            if (now != null && controlWhy == "ok") control = now else Thread.sleep(300)
        }
        assertNotNull("positive control failed: $controlWhy", control)
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-selected-address.png"))
    }

    @Test fun pendingAutomaticSetupHasDoneAndNoManualVlcFields() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "setup").putExtra("vlcpending", "true"))
        assertTrue(device.wait(Until.hasObject(By.text(SETUP_TITLE)), 10000))
        assertTrue(device.hasObject(By.text("Or type the PC's address:")))
        // Done sits at the very bottom of the scroll (paired card below the pair
        // section). Run 46097348603 proved the flow correct and the budget short:
        // Done entered the tree one settle after the 7th swipe (failure pixels show
        // it on screen; the recurrence dump lists it). Check again after each
        // settle, and allow up to 12 nudges on a hot emulator - same worst-case CI
        // budget style as this file's other transition waits, all negative asserts
        // still run every iteration.
        var foundDone = false
        var doneNudges = 0
        while (!foundDone && doneNudges < 12) {
            assertFalse(device.hasObject(By.text("VLC password")))
            assertFalse(device.hasObject(By.text("Turn on VLC's web remote")))
            assertFalse(device.hasObject(By.text("Set the password by hand instead")))
            assertFalse(device.hasObject(By.text("Hide the manual setup")))
            if (device.hasObject(By.text("Done"))) { foundDone = true; break }
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 4, 25)
            device.waitForIdle()
            Thread.sleep(300)
            if (device.hasObject(By.text("Done"))) foundDone = true
            doneNudges++
        }
        assertTrue("Automatic deferred setup still needs a Done action", foundDone)
    }

    /** Text ink in a screen region, self-calibrating: background = 5th-percentile
     *  luminance of the region, ink = pixels more than 40 above the background.
     *  Null when the frame cannot be captured, decoded, or the bounds fall outside
     *  the frame - the caller fails loud, never vacuously. */
    private fun regionInk(device: UiDevice, probe: java.io.File, bounds: Rect): Int? {
        if (!device.takeScreenshot(probe)) return null
        val bmp = android.graphics.BitmapFactory.decodeFile(probe.absolutePath) ?: return null
        val w = bounds.width(); val h = bounds.height()
        if (w <= 0 || h <= 0 || bounds.left < 0 || bounds.top < 0 ||
            bounds.right > bmp.width || bounds.bottom > bmp.height) { bmp.recycle(); return null }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, bounds.left, bounds.top, w, h)
        bmp.recycle()
        val lums = IntArray(px.size) { i ->
            val c = px[i]
            (299 * (c shr 16 and 0xFF) + 587 * (c shr 8 and 0xFF) + 114 * (c and 0xFF)) / 1000
        }
        val bg = lums.sorted()[lums.size / 20]
        return lums.count { it > bg + 40 }
    }

    // ---- single-tree reads (one getRootInActiveWindow walk per call; nothing is cached between calls) ----

    private class N(val info: AccessibilityNodeInfo, val parent: N?, val idx: Int) {
        val rect = Rect().also { info.getBoundsInScreen(it) }
        val text: String? = info.text?.toString()
        val cls: String? = info.className?.toString()
        val scrollable = info.isScrollable
        fun ancestors(): List<N> { val out = ArrayList<N>(); var p = parent; while (p != null) { out.add(p); p = p.parent }; return out }
        /** Viewport container candidates: a node that reports scrollable, OR the Compose scroll container class (a verticalScroll
         *  Column whose content fits reports scrollable=false but is still the clip box; seen in BingeMote run 45570170741's dump). */
        fun containerAncestors(): List<N> = ancestors().filter { it.scrollable || it.cls == SCROLL_VIEW_CLASS }
        /** Child-index path from the root (root = empty), identical for the same container in another walk of the same screen. */
        fun path(): List<Int> { val out = ArrayList<Int>(); var n: N? = this; while (n?.parent != null) { out.add(0, n.idx); n = n.parent }; return out }
    }

    /** Walks the whole tree. Returns false if ANY node could not be read (a null child or the depth cap),
     *  because an absence read from an incomplete walk proves nothing; callers must treat that tick as invalid. */
    private fun walk(info: AccessibilityNodeInfo, parent: N?, out: MutableList<N>, depth: Int, idx: Int): Boolean {
        val n = N(info, parent, idx); out.add(n)
        if (depth > 60) return info.childCount == 0
        var complete = true
        for (i in 0 until info.childCount) {
            val c = info.getChild(i)
            if (c == null) { complete = false; continue }
            if (!walk(c, n, out, depth + 1, i)) complete = false
        }
        return complete
    }

    private class SetupView(
        val window: Rect, val f: N?, val bt: N?, val l: List<N>, val selected: List<N>,
        val fieldText: String?, val v: N?, val vProblem: String?, val tile: Rect?, val tileSource: String,
        val density: Float, val complete: Boolean,
        /** Effective viewport: V's rect intersected with the window and EVERY ancestor's bounds up to the root (what can be seen). */
        val ve: Rect?,
        /** Stable container identity: class, root-to-V child-index path, rect. Deliberately NOT the scrollable flag (it may flip
         *  when the content shrinks or grows, a healthy transition); each snapshot's own flag is read truthfully. */
        val vId: String?,
        /** Full fingerprint for "two consecutive equal reads": identity + this snapshot's scrollable flag + window + all ancestor rects + Ve. */
        val vFp: String?,
    ) {
        fun describe() = "complete=$complete window=${rs(window)} V=${rs(v?.rect)} Ve=${rs(ve)} Vfp=${vFp} F=${rs(f?.rect)} tile=${rs(tile)}($tileSource) " +
            "L=${rs(l.firstOrNull()?.rect)} sel=${selected.size} field='${fieldText}'"
        private fun inside(r: Rect, v: Rect) = r.left >= v.left && r.right <= v.right && r.top >= v.top && r.bottom <= v.bottom && r.height() > 0
        // Text nodes sit inside layout padding, so a node that is only partly visible (its reported
        // bounds, if the framework clips them to the viewport, then lie ON the viewport edge) must not
        // count as fully inside: strict on every side. Used for the address label, the button label
        // and the card label, whose heights or centres the clearance arithmetic is derived from.
        private fun insideStrict(r: Rect, v: Rect) = r.left > v.left && r.right < v.right && r.top > v.top && r.bottom < v.bottom && r.height() > 0

        /** Common viewport checks (every tick, every state): complete walk, one unambiguous container, non-empty effective viewport. */
        private fun viewportProblem(): String? {
            if (!complete) return "accessibility tree walk incomplete (unreadable child or depth cap): ${describe()}"
            if (window.isEmpty) return "window bounds empty"
            if (v == null || ve == null) return "viewport not established ($vProblem): ${describe()}"
            if (ve.isEmpty) return "effective viewport empty (a failed check, not evidence that the row is absent): ${describe()}"
            return null
        }

        /** Before typing: label, button label and tile fully inside the effective viewport; the pair-code label present and inside it. */
        fun preconditionProblem(): String? {
            viewportProblem()?.let { return it }
            if (f == null) return "address label not found: ${describe()}"
            if (bt == null || tile == null) return "button label not found: ${describe()}"
            if (l.isEmpty()) return "pair-code label not found: ${describe()}"
            val vr = ve!!
            if (!insideStrict(f.rect, vr)) return "address label not strictly inside Ve (clipped or on the edge): ${describe()}"
            if (!insideStrict(bt.rect, vr)) return "button label not strictly inside Ve (clipped or on the edge): ${describe()}"
            if (!inside(tile, vr)) return "button tile not fully inside Ve: ${describe()}"
            if (!insideStrict(l[0].rect, vr)) return "pair-code label not strictly inside Ve (clipped or on the edge): ${describe()}"
            return null
        }

        /** After typing: the region where a still-present Selected row would start must be observable inside Ve. */
        fun postProblem(typed: String, pre: SetupView): String? {
            viewportProblem()?.let { return it }
            if (window != pre.window) return "window bounds changed: before=${rs(pre.window)} now=${rs(window)}"
            if (f == null || bt == null || tile == null) return "address label or button missing: ${describe()}"
            // Same container as before typing (class, child-index path, rect). The scrollable flag is NOT compared: typing removes the
            // pair card and the Selected row, so a page that scrolled before may legitimately fit now.
            if (vId != pre.vId) return "viewport container changed: before=${pre.vId} now=$vId"
            if (fieldText != typed) return "typed text not in the field in this tree (field='$fieldText')"
            val vr = ve!!
            if (!insideStrict(f.rect, vr)) return "address label not strictly inside Ve (clipped or on the edge): ${describe()}"
            if (!insideStrict(bt.rect, vr)) return "button label not strictly inside Ve (clipped or on the edge): ${describe()}"
            if (!inside(tile, vr)) return "button tile not fully inside Ve (top included): ${describe()}"
            // Row first-line height: the larger of the label heights read before and after typing, so a
            // label that read short in one tree cannot understate the clearance. Must be positive.
            val lineH = Math.max(f.rect.height(), pre.f!!.rect.height())
            if (lineH <= 0) return "address label height not positive: ${describe()}"
            val rowTop = tile.bottom + Math.round(8 * density)
            val rowBottom = rowTop + lineH + 4
            if (rowTop < vr.top || rowBottom > vr.bottom) return "first line of a would-be Selected row not observable inside Ve: rowTop=$rowTop rowBottom=$rowBottom Ve=${rs(vr)} scrollable=${v!!.scrollable} (button moved, clamp or scroll): ${describe()}"
            return null
        }

        /** Confirmed-address state: exactly one non-empty Selected row and the card label, inside Ve, in the SAME container as the verdict
         *  read. The control runs in its own truthful branch (the flag may differ: more content). It proves the routine finds the row with
         *  the same query inside Ve; it does not prove the verdict's no-scroll branch. */
        fun controlProblem(typed: String, expectedRow: String, pre: SetupView): String {
            viewportProblem()?.let { return it }
            if (window != pre.window) return "window bounds changed: before=${rs(pre.window)} now=${rs(window)}"
            if (vId != pre.vId) return "viewport container changed: before=${pre.vId} now=$vId"
            if (fieldText != typed) return "typed text not in the field (field='$fieldText')"
            if (selected.size != 1) return "Selected rows=${selected.size}: ${describe()}"
            val row = selected[0]
            if (row.text != expectedRow) return "Selected text '${row.text}' != '$expectedRow'"
            val vr = ve!!
            if (!inside(row.rect, vr)) return "Selected row not inside Ve: row=${rs(row.rect)} ${describe()}"
            if (l.isEmpty() || !insideStrict(l[0].rect, vr)) return "pair-code label not strictly inside Ve: ${describe()}"
            // Same containment the verdict relies on, so the control proves the routine can see the row
            // under the same rules (not at the verdict's scroll position, which it does not claim).
            if (f == null || bt == null || tile == null) return "address label or button missing: ${describe()}"
            if (!insideStrict(f.rect, vr) || !insideStrict(bt.rect, vr) || !inside(tile, vr))
                return "address label, button label or tile not inside Ve: ${describe()}"
            if (!insideStrict(row.rect, vr)) return "Selected row not strictly inside Ve: row=${rs(row.rect)} ${describe()}"
            return "ok"
        }
    }

    private fun readSetup(): SetupView? {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = ui.rootInActiveWindow ?: return null
        val nodes = ArrayList<N>()
        val complete = walk(root, null, nodes, 0, 0)
        val window = Rect().also { root.getBoundsInScreen(it) }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val f = nodes.filter { it.text == "Or type the PC's address:" }.singleOrNull()
        val bt = nodes.filter { it.text == "Use this address" }.singleOrNull()
        val l = nodes.filter { it.text == PAIR_CODE_LABEL }.sortedBy { it.rect.top }
        val selected = nodes.filter { it.text?.startsWith("Selected:") == true }
        val field = nodes.filter { it.cls == "android.widget.EditText" }.minByOrNull { it.rect.top }
        var v: N? = null
        var vProblem: String? = null
        var ve: Rect? = null
        var vId: String? = null
        var vFp: String? = null
        // V is resolved from the two nodes that are PRESENT in every state (address label and the button label); the pair-code label is
        // absent in the bug case and must not anchor anything. Exactly one container may be a common ancestor of the two (nested ones are
        // ambiguous and fail closed). There is never a window fallback.
        if (f == null || bt == null) vProblem = "address label or button not unique/present"
        else {
            val cf = f.containerAncestors(); val cb = bt.containerAncestors()
            val common = cf.filter { c -> cb.any { it === c } }
            if (common.isEmpty()) vProblem = "no container (scrollable or ScrollView) shared by the address label and the button label"
            else if (common.size > 1) vProblem = "ambiguous viewport: ${common.size} nested containers are common ancestors (${common.joinToString { rs(it.rect) }})"
            else {
                val c = common[0]
                val ancestorRects = c.ancestors().map { it.rect }
                val e = Rect(c.rect)
                var ok = e.intersect(window)
                for (r in ancestorRects) ok = ok && e.intersect(r)
                v = c
                ve = if (ok) e else Rect()
                vId = "${c.cls}|path=${c.path()}|rect=${rs(c.rect)}"
                vFp = "$vId|scrollable=${c.scrollable}|window=${rs(window)}|anc=${ancestorRects.joinToString(",") { rs(it) }}|ve=${rs(ve)}"
            }
        }
        var tile: Rect? = null
        var tileSource = "none"
        if (bt != null) {
            val half = Math.round(24 * density)
            val parent = bt.parent
            if (parent != null && parent.cls == "android.widget.Button" &&
                Math.abs(parent.rect.height() - 2 * half) <= 3) { tile = parent.rect; tileSource = "button-parent" }
            else {
                tile = Rect(bt.rect.left, bt.rect.centerY() - half, bt.rect.right, bt.rect.centerY() + half)
                tileSource = "label-center+24dp"
            }
        }
        return SetupView(window, f, bt, l, selected, field?.text?.toString(), v, vProblem, tile, tileSource, density, complete, ve, vId, vFp)
    }
}

private fun rs(r: Rect?) = if (r == null) "none" else "[${r.left},${r.top}][${r.right},${r.bottom}]"
