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
        OK_DEFERRED,     // the PC has a vlcrc write still waiting (any reason): control starts once it's applied
        OK_NOT_OPEN,     // the PC attests the settings are written but VLC is closed: control starts when it opens
        REFUSED,         // VLC refused after the one allowed heal, or the PC says nothing is configured
        PC_UNREACHABLE,  // the PC itself did not answer: retry
        SETUP_UNKNOWN,   // the PC answered but could not verify VLC settings right now: retry, never re-pair
    }

    fun run(store: Store): Outcome {
        val attest = try {
            // ONE coherent capture for the attestation; null (poisoned store, no host) maps
            // to PC_UNREACHABLE, the same bucket a blank host reaches via a failed call.
            val seq = store.pairingSequence() ?: return Outcome.PC_UNREACHABLE
            HelperClient.vlcSetupStatus(seq.snapshot.host, seq.snapshot.helperPort, seq.snapshot.helperToken)
        } catch (e: HelperClient.BadToken) {
            return Outcome.REFUSED // the pairing itself is broken: re-pair
        } catch (e: HelperClient.PcSetupUnknown) {
            return Outcome.SETUP_UNKNOWN
        } catch (e: Exception) {
            return Outcome.PC_UNREACHABLE
        }
        // A write is still waiting on the PC (VLC open, busy or unreadable file - the reason
        // is not attested). Don't probe VLC: its live settings may predate the pending write,
        // so a refusal there is expected and means nothing yet.
        if (attest.pending) return Outcome.OK_DEFERRED
        // The PC says nothing is set up at all: never call that done.
        if (!attest.configured) return Outcome.REFUSED
        // One coherent target per probe: a heal or re-pair can never mix the host and password.
        // Null (store broke mid-check) lands in REFUSED - the same bucket a broken store reaches
        // today via blank-password 401 + failed heal.
        return try {
            val vt = store.vlcTarget() ?: return Outcome.REFUSED
            Vlc.status(vt.host, vt.port, vt.password)
            Outcome.OK
        } catch (e: Vlc.BadAuth) {
            // The one allowed heal: fetch the PC's current copy once (never push ours at a
            // refusal), then re-check once.
            if (VlcAuthRepair.tryHealOnce(store) == null) return Outcome.REFUSED
            try {
                val vt2 = store.vlcTarget() ?: return Outcome.REFUSED   // re-read: the heal just changed the password
                Vlc.status(vt2.host, vt2.port, vt2.password)
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
