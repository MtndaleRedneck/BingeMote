package com.bingemote.stream

internal object HttpRange {
    fun parse(value: String): LongRange? {
        // Unsupported suffix/multi/unknown units are ignored (full 200), never partly parsed.
        val m = Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(value.trim()) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].let { if (it.isEmpty()) Long.MAX_VALUE else it.toLongOrNull() ?: return null }
        return start..end
    }
    fun resolve(size: Long, range: LongRange?): Triple<Int, Long, Long> {
        if (range == null) return Triple(200, 0L, size - 1)
        if (size <= 0 || range.first < 0 || range.first >= size || range.last < range.first) return Triple(416, 0L, -1L)
        return Triple(206, range.first, minOf(range.last, size - 1))
    }
}
