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
        assertTrue(device.wait(Until.gone(By.text("You're up to date.")), 7000))
        assertTrue(device.hasObject(By.text("Version " + BuildConfig.VERSION_NAME)))
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-updates-expired.png"))
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
        // 48dp tile) and the pair-code label, all fully inside the scroll viewport V, where V is the
        // nearest scrollable ancestor shared by the three (ambiguity fails). Small swipes may only be used
        // to REACH that state; nothing after typing scrolls. If it cannot be established, fail closed.
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
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        val addressField = fields.minBy { it.visibleBounds.top }
        addressField.text = typed
        // ACTION_SET_TEXT applies asynchronously through the IME/accessibility path, so a
        // read on the very next frame can race the state update (saw one CI run read back
        // empty while the identical tree passed in its sibling run). Poll briefly instead.
        val textDeadline = System.currentTimeMillis() + 3000
        while (addressField.text != "192.0.2.20" && System.currentTimeMillis() < textDeadline) Thread.sleep(100)
        assertEquals("192.0.2.20", addressField.text)
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
        // tile and address label fully inside V (top included), V and the window unchanged from before
        // typing, the typed text present in the field, and the full first line of the row
        // (address-label height as the same-13sp-style stand-in, +4 px for rounding) above V's bottom.
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
            controlWhy = now?.controlProblem(typed, "Selected: $typed:${HelperClient.DEFAULT_PORT}") ?: "no accessibility snapshot"
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
        var foundDone = false
        repeat(7) {
            assertFalse(device.hasObject(By.text("VLC password")))
            assertFalse(device.hasObject(By.text("Turn on VLC's web remote")))
            assertFalse(device.hasObject(By.text("Set the password by hand instead")))
            assertFalse(device.hasObject(By.text("Hide the manual setup")))
            if (device.hasObject(By.text("Done"))) foundDone = true
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 4, 25)
            Thread.sleep(300)
        }
        assertTrue("Automatic deferred setup still needs a Done action", foundDone)
    }

    // ---- single-tree reads (one getRootInActiveWindow walk per call; nothing is cached between calls) ----

    private class N(val info: AccessibilityNodeInfo, val parent: N?) {
        val rect = Rect().also { info.getBoundsInScreen(it) }
        val text: String? = info.text?.toString()
        val cls: String? = info.className?.toString()
        val scrollable = info.isScrollable
        fun ancestors(): List<N> { val out = ArrayList<N>(); var p = parent; while (p != null) { out.add(p); p = p.parent }; return out }
        fun scrollAncestors(): List<N> = ancestors().filter { it.scrollable }
    }

    /** Walks the whole tree. Returns false if ANY node could not be read (a null child or the depth cap),
     *  because an absence read from an incomplete walk proves nothing; callers must treat that tick as invalid. */
    private fun walk(info: AccessibilityNodeInfo, parent: N?, out: MutableList<N>, depth: Int): Boolean {
        val n = N(info, parent); out.add(n)
        if (depth > 60) return info.childCount == 0
        var complete = true
        for (i in 0 until info.childCount) {
            val c = info.getChild(i)
            if (c == null) { complete = false; continue }
            if (!walk(c, n, out, depth + 1)) complete = false
        }
        return complete
    }

    private class SetupView(
        val window: Rect, val f: N?, val bt: N?, val l: List<N>, val selected: List<N>,
        val fieldText: String?, val v: N?, val vProblem: String?, val tile: Rect?, val tileSource: String,
        val density: Float, val complete: Boolean,
    ) {
        fun describe() = "complete=$complete window=${rs(window)} V=${rs(v?.rect)} F=${rs(f?.rect)} tile=${rs(tile)}($tileSource) " +
            "L=${rs(l.firstOrNull()?.rect)} sel=${selected.size} field='${fieldText}'"
        private fun inside(r: Rect, v: Rect) = r.left >= v.left && r.right <= v.right && r.top >= v.top && r.bottom <= v.bottom && r.height() > 0
        // Text nodes sit inside layout padding, so a node that is only partly visible (its reported
        // bounds, if the framework clips them to the viewport, then lie ON the viewport edge) must not
        // count as fully inside: strict on every side. Used for the address label, the button label
        // and the card label, whose heights or centres the clearance arithmetic is derived from.
        private fun insideStrict(r: Rect, v: Rect) = r.left > v.left && r.right < v.right && r.top > v.top && r.bottom < v.bottom && r.height() > 0

        /** Before typing: label, button tile and card label all fully inside one unambiguous viewport. */
        fun preconditionProblem(): String? {
            if (!complete) return "accessibility tree walk incomplete (unreadable child or depth cap): ${describe()}"
            if (window.isEmpty) return "window bounds empty"
            if (f == null) return "address label not found: ${describe()}"
            if (bt == null || tile == null) return "button label not found: ${describe()}"
            if (l.isEmpty()) return "pair-code label not found: ${describe()}"
            if (v == null) return "viewport not established ($vProblem): ${describe()}"
            val vr = v.rect
            if (!insideStrict(f.rect, vr)) return "address label not strictly inside V (clipped or on the edge): ${describe()}"
            if (!insideStrict(bt.rect, vr)) return "button label not strictly inside V (clipped or on the edge): ${describe()}"
            if (!inside(tile, vr)) return "button tile not fully inside V: ${describe()}"
            if (!insideStrict(l[0].rect, vr)) return "pair-code label not strictly inside V (clipped or on the edge): ${describe()}"
            return null
        }

        /** After typing: the region where a still-present Selected row would start must be observable. */
        fun postProblem(typed: String, pre: SetupView): String? {
            if (!complete) return "accessibility tree walk incomplete (unreadable child or depth cap): ${describe()}"
            if (window != pre.window) return "window bounds changed: before=${rs(pre.window)} now=${rs(window)}"
            if (f == null || bt == null || tile == null) return "address label or button missing: ${describe()}"
            if (v == null) return "viewport not established ($vProblem): ${describe()}"
            if (v.rect != pre.v!!.rect) return "viewport bounds changed: before=${rs(pre.v.rect)} now=${rs(v.rect)}"
            if (fieldText != typed) return "typed text not in the field in this tree (field='$fieldText')"
            val vr = v.rect
            if (!insideStrict(f.rect, vr)) return "address label not strictly inside V (clipped or on the edge): ${describe()}"
            if (!insideStrict(bt.rect, vr)) return "button label not strictly inside V (clipped or on the edge): ${describe()}"
            if (!inside(tile, vr)) return "button tile not fully inside V (top included): ${describe()}"
            // Row first-line height: the larger of the label heights read before and after typing, so a
            // label that read short in one tree cannot understate the clearance.
            val lineH = Math.max(f.rect.height(), pre.f!!.rect.height())
            val rowTop = tile.bottom + Math.round(8 * density)
            val need = rowTop + lineH + 4
            if (need > vr.bottom) return "first line of a would-be Selected row not observable: rowTop=$rowTop need=$need Vbottom=${vr.bottom} (button moved down, clamp or scroll): ${describe()}"
            return null
        }

        /** Confirmed-address state: exactly one non-empty Selected row and the card label, inside V. */
        fun controlProblem(typed: String, expectedRow: String): String {
            if (!complete) return "accessibility tree walk incomplete (unreadable child or depth cap): ${describe()}"
            if (v == null) return "viewport not established ($vProblem): ${describe()}"
            if (fieldText != typed) return "typed text not in the field (field='$fieldText')"
            if (selected.size != 1) return "Selected rows=${selected.size}: ${describe()}"
            val row = selected[0]
            if (row.text != expectedRow) return "Selected text '${row.text}' != '$expectedRow'"
            if (!inside(row.rect, v.rect)) return "Selected row not inside V: row=${rs(row.rect)} ${describe()}"
            if (l.isEmpty() || !insideStrict(l[0].rect, v.rect)) return "pair-code label not strictly inside V: ${describe()}"
            // Same containment the verdict relies on, so the control proves the routine can see the row
            // under the same rules (not at the verdict's scroll position, which it does not claim).
            if (f == null || bt == null || tile == null) return "address label or button missing: ${describe()}"
            if (!insideStrict(f.rect, v.rect) || !insideStrict(bt.rect, v.rect) || !inside(tile, v.rect))
                return "address label, button label or tile not inside V: ${describe()}"
            if (!insideStrict(row.rect, v.rect)) return "Selected row not strictly inside V: row=${rs(row.rect)} ${describe()}"
            return "ok"
        }
    }

    private fun readSetup(): SetupView? {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = ui.rootInActiveWindow ?: return null
        val nodes = ArrayList<N>()
        val complete = walk(root, null, nodes, 0)
        val window = Rect().also { root.getBoundsInScreen(it) }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val f = nodes.filter { it.text == "Or type the PC's address:" }.singleOrNull()
        val bt = nodes.filter { it.text == "Use this address" }.singleOrNull()
        val l = nodes.filter { it.text == PAIR_CODE_LABEL }.sortedBy { it.rect.top }
        val selected = nodes.filter { it.text?.startsWith("Selected:") == true }
        val field = nodes.filter { it.cls == "android.widget.EditText" }.minByOrNull { it.rect.top }
        var v: N? = null
        var vProblem: String? = null
        if (f == null || bt == null) vProblem = "address label or button not unique/present"
        else {
            val sf = f.scrollAncestors(); val sb = bt.scrollAncestors()
            val sl = l.firstOrNull()?.scrollAncestors()
            if (sf.isEmpty()) vProblem = "no scrollable ancestor of the address label"
            else if (sb.isEmpty() || sf.first().info != sb.first().info && sf.first().rect != sb.first().rect) vProblem = "label and button have different nearest scrollable ancestors"
            else if (sl != null && (sl.isEmpty() || sf.first().rect != sl.first().rect)) vProblem = "pair-code label has a different nearest scrollable ancestor"
            else v = sf.first()
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
        return SetupView(window, f, bt, l, selected, field?.text?.toString(), v, vProblem, tile, tileSource, density, complete)
    }
}

private fun rs(r: Rect?) = if (r == null) "none" else "[${r.left},${r.top}][${r.right},${r.bottom}]"
