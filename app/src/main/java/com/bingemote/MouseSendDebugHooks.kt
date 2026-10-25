package com.bingemote

/**
 * androidTest-only diagnosis tracing for the mouse sender loop. The only read
 * sits behind BuildConfig.DEBUG, so in release builds it is compile-time dead
 * and these fields are never touched. No shipped behavior changes.
 */
internal object MouseSendDebugHooks {
    @Volatile var traceEnabled = false
    private val buf = LongArray(1024)
    @Volatile var count = 0
        private set

    @Synchronized fun noteArrival() {
        if (count < buf.size) { buf[count] = System.currentTimeMillis(); count++ }
    }

    @Synchronized fun drainArrivals(): LongArray {
        val out = buf.copyOf(count)
        count = 0
        return out
    }
}
