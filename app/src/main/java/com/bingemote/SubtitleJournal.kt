package com.bingemote

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Crash-safe per-operation journal. Pending and unresolved records are distinct;
 * completing one request cannot erase another request's uncertainty. */
internal class SubtitleJournal(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "subtitle-operations.json"))
    companion object { private val lock = Any() }
    data class Entry(val id: String, val pair: String, val media: String, val phase: String)
    private fun read(): MutableList<Entry> {
        if (!file.baseFile.exists()) return mutableListOf()
        val bytes = file.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
            while (true) { val n=input.read(buf);if(n<0)break;check(out.size()+n<=262144);out.write(buf,0,n) };out.toByteArray()
        }
        val arr=JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("entries")
        return (0 until arr.length()).map { val o=arr.getJSONObject(it);Entry(o.getString("id"),o.getString("pair"),o.getString("media"),o.getString("phase")) }.toMutableList()
    }
    private fun write(entries: List<Entry>) {
        val arr=org.json.JSONArray()
        entries.forEach { arr.put(JSONObject().put("id",it.id).put("pair",it.pair).put("media",it.media).put("phase",it.phase)) }
        var stream: java.io.FileOutputStream?=null
        try { stream=file.startWrite();stream.write(JSONObject().put("entries",arr).toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream) }
        catch(e:Exception){ if(stream!=null)file.failWrite(stream);throw e }
    }
    fun entries(pair: String): List<Entry> = synchronized(lock) { read().filter { it.pair==pair } }
    fun begin(pair: String, media: String?): String = synchronized(lock) {
        val entries=read();check(entries.size<128){"Too many unresolved subtitle operations"}
        val id=UUID.randomUUID().toString();entries.add(Entry(id,pair,media.orEmpty(),"pending"));write(entries);id
    }
    /** Never throws from the error path. False leaves the old pending record intact. */
    fun finish(id: String, unknown: Boolean): Boolean = synchronized(lock) {
        try { val entries=read();val old=entries.firstOrNull{it.id==id} ?: return@synchronized true
            entries.removeAll{it.id==id};if(unknown)entries.add(old.copy(phase="unknown"));write(entries);true
        } catch(_:Exception){false}
    }
    fun clearOtherMedia(pair: String, current: String): Boolean = synchronized(lock) {
        if(current.isEmpty())return@synchronized false
        try {val entries=read();entries.removeAll{it.pair==pair && it.phase=="unknown" && it.media.isNotEmpty() && it.media!=current};write(entries);true}
        catch(_:Exception){false}
    }
}
