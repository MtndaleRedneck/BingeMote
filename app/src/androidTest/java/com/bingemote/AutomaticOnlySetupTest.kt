package com.bingemote

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
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
        repeat(4) {
            if (!device.hasObject(By.text("Or type the PC's address:"))) {
                device.swipe(device.displayWidth / 2, device.displayHeight / 3,
                    device.displayWidth / 2, device.displayHeight * 4 / 5, 20)
                device.waitForIdle()
            }
        }
        assertTrue("address section lost after the pre-condition pin",
            device.hasObject(By.text("Or type the PC's address:")))
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        val addressField = fields.minBy { it.visibleBounds.top }
        addressField.text = "192.0.2.20"
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
        if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
        device.waitForIdle()
        // Viewport-aware bounded poll (review-driven): UiAutomator sees only the
        // current viewport, and the pin's scroll-back to the address field can leave
        // the card below the fold, so a bare hasObject absence could skip the whole
        // budget and a later sweep could catch a slow-but-real hide without it. Per
        // the actual SetupScreen layout (one verticalScroll Column, L164-165; the
        // address field L240-252 ABOVE the pair card L273-301), an absence slice
        // concludes hidden only after revealing the card's region below; a presence
        // slice gets a 700ms Until.gone window as before. Converges the moment the
        // state lands; still fails hard when the card genuinely never hides.
        val hideBy = System.currentTimeMillis() + 10000
        var hidden = false
        while (!hidden && System.currentTimeMillis() < hideBy) {
            if (device.hasObject(By.text(PAIR_CODE_LABEL))) {
                // Card in viewport: a gone window here is viewport-complete.
                hidden = device.wait(Until.gone(By.text(PAIR_CODE_LABEL)), 700)
            } else {
                // Absence only counts where the card renders, so first get there
                // POSITIVELY (review-driven: an unverified swipe cannot show the
                // region was reached). Per the actual SetupScreen layout the pair
                // card is the LAST element of the scroll column (L273-301; the VLC
                // handoff below it needs successVisible, false here), so its region
                // is the content bottom - UiScrollable.scrollToEnd stops only when
                // no forward scroll remains. When the hide has landed, chosen == null
                // leaves Card A alone (L213-268), which the run-45549797139
                // before-selection capture shows occupying ~40% of the 1080x2310@480
                // viewport: no scrollable extent, one complete tree, so the
                // not-scrollable case is already fully inspected.
                val scroller = UiScrollable(UiSelector().scrollable(true))
                if (scroller.exists()) scroller.scrollToEnd(4)
                device.waitForIdle()
                hidden = !device.hasObject(By.text(PAIR_CODE_LABEL))
            }
        }
        assertTrue("pair-code entry still shown after the address cleared the selection", hidden)
        // Viewport guard, both directions, with POSITIVE extent proof (review-driven:
        // blind repeat(3) swipes never established that the claimed region was
        // reached). Top: scroll to the verified beginning and require the
        // always-rendered Card A title as the upper anchor. Bottom: scroll to the
        // verified end - the pair card renders last, so its region is the content
        // bottom. A viewport-only disappearance resurrects the card at one extreme
        // and fails here; a real removal passes both. Same not-scrollable reasoning
        // as the poll: a fitted single viewport is already the whole tree.
        val guardScroller = UiScrollable(UiSelector().scrollable(true))
        if (guardScroller.exists()) guardScroller.scrollToBeginning(4)
        device.waitForIdle()
        assertTrue("top anchor missing - scroll extent not verified, guard proves nothing",
            device.hasObject(By.text(SETUP_TITLE)))
        assertFalse("pair-code entry reappeared above the viewport - hide was viewport-only",
            device.hasObject(By.text(PAIR_CODE_LABEL)))
        if (guardScroller.exists()) guardScroller.scrollToEnd(4)
        device.waitForIdle()
        assertFalse("pair-code entry reappeared below the viewport - hide was viewport-only",
            device.hasObject(By.text(PAIR_CODE_LABEL)))
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
        assertTrue(device.hasObject(By.text("Selected: 192.0.2.20:8488")))
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
}
