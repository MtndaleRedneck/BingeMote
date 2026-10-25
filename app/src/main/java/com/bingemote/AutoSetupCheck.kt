package com.bingemote

/**
 * The step-3 automatic setup check - the production path, driven once when step 3 opens
 * after a pair that carried the PC-picked password. The PC attests what is actually
 * configured; only then does a VLC probe refine "works now" vs "works when VLC opens".
 * A network or VLC error never becomes a green Done merely because the PC answered.
 */
object AutoSetupCheck {
    enum class Outcome {
        OK,              // VLC answered with the password: remote control works now
        OK_DEFERRED,     // the PC has a vlcrc write waiting: control starts when VLC reopens
        OK_NOT_OPEN,     // the PC attests the settings are written but VLC is closed: control starts when it opens
        REFUSED,         // VLC refused after the one allowed heal, or the PC says nothing is configured
        PC_UNREACHABLE,  // the PC itself did not answer: retry
    }

    fun run(store: Store): Outcome {
        val attest = try {
            HelperClient.vlcSetupStatus(store.host, store.helperPort, store.helperToken)
        } catch (e: HelperClient.BadToken) {
            return Outcome.REFUSED // the pairing itself is broken: re-pair
        } catch (e: Exception) {
            return Outcome.PC_UNREACHABLE
        }
        // A write waits for VLC to close. Don't probe VLC: the running copy still has its
        // old settings, so a refusal there is expected and means nothing yet.
        if (attest.pending) return Outcome.OK_DEFERRED
        // The PC says nothing is set up at all: never call that done.
        if (!attest.configured) return Outcome.REFUSED
        return try {
            Vlc.status(store.host, store.vlcPort, store.vlcPassword)
            Outcome.OK
        } catch (e: Vlc.BadAuth) {
            // The one allowed heal: fetch the PC's current copy once (never push ours at a
            // refusal), then re-check once.
            if (!VlcAuthRepair.tryHealOnce(store)) return Outcome.REFUSED
            try {
                Vlc.status(store.host, store.vlcPort, store.vlcPassword)
                Outcome.OK
            } catch (e2: Vlc.BadAuth) {
                Outcome.REFUSED
            } catch (e2: Exception) {
                Outcome.OK_NOT_OPEN
            }
        } catch (e: Exception) {
            // VLC is not answering, but the PC attests the settings are written: VLC is
            // simply closed, and control starts when it opens.
            Outcome.OK_NOT_OPEN
        }
    }
}
