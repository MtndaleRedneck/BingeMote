package com.bingemote

/** Debug-build-only rig switches, set from MainActivity intent extras by the CI
 * screenshot/instrumented harness. Always null/false in release: nothing sets them
 * (BuildConfig.DEBUG gate in MainActivity) and this object is the only writer. */
object DebugRig {
    /** OpenDocumentTree initial URI - lets an instrumented test open the system
     * picker straight at a test provider root instead of hunting the roots drawer
     * (which hid a fresh provider root on the API-34 emulator, CI-observed). */
    @Volatile var pickerInitialUri: android.net.Uri? = null
}
