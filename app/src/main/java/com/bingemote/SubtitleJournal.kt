package com.bingemote

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Each request owns one durable record. All calls must run off the UI thread. */
internal class SubtitleJournal(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "subtitle-operations.json"))
    companion object {
        private val lock = Any()
        private val live = mutableSetOf<String>()
        private val completed = mutableMapOf<String,Boolean>()
        private val degradedPairs=mutableSetOf<String>()
        private const val MAX_BYTES = 262144
    }
    data class Entry(val id: String, val pair: String, val media: String, val phase: String)
    private fun read(): MutableList<Entry> {
        val bytes = try { file.openRead().use { input ->
            val out=java.io.ByteArrayOutputStream();val buf=ByteArray(4096)
            while(true){val n=input.read(buf);if(n<0)break;check(out.size()+n<=MAX_BYTES);out.write(buf,0,n)};out.toByteArray()
        } } catch(e:FileNotFoundException) {
            if(file.baseFile.exists() || File(file.baseFile.path+".bak").exists())throw e
            return mutableListOf()
        }
        val arr=JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("entries")
        check(arr.length()<=1024)
        val ids=mutableSetOf<String>()
        return (0 until arr.length()).map {
            val o=arr.getJSONObject(it)
            val e=Entry(o.getString("id"),o.getString("pair"),o.getString("media"),o.getString("phase"))
            check(ids.add(e.id) && e.id.length in 1..64 && e.pair.matches(Regex("[a-f0-9]{64}|pair")) && e.media.length<=64 && e.phase in setOf("pending","unknown"))
            e
        }.toMutableList()
    }
    private fun compact(entries: List<Entry>): List<Entry> {
        val result=mutableListOf<Entry>()
        entries.groupBy{it.pair}.forEach{(pair,group)->
            result.addAll(group.filter{it.phase=="pending"})
            val unknown=group.filter{it.phase=="unknown"}.distinctBy{it.media}
            if(unknown.size>32 || unknown.any{it.media.isEmpty()})
                result.add(Entry("coarse-"+pair.take(48),pair,"","unknown"))
            else result.addAll(unknown)
        }
        return result
    }
    private fun write(entries: List<Entry>) {
        val compact=compact(entries);check(compact.size<=1024)
        val arr=org.json.JSONArray()
        compact.forEach{arr.put(JSONObject().put("id",it.id).put("pair",it.pair).put("media",it.media).put("phase",it.phase))}
        val bytes=JSONObject().put("entries",arr).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size<=MAX_BYTES)
        var stream:java.io.FileOutputStream?=null
        try{stream=file.startWrite();stream.write(bytes);file.finishWrite(stream)}
        catch(e:Exception){if(stream!=null)file.failWrite(stream);throw e}
    }
    private fun reconciled(): MutableList<Entry> {
        val original=read()
        val entries=original.mapNotNull { e ->
            when {
                completed[e.id]==false -> null
                completed[e.id]==true -> e.copy(phase="unknown")
                e.phase=="pending" && e.id !in live -> e.copy(phase="unknown")
                else -> e
            }
        }.toMutableList()
        val represented = completed.filter { (id, unknown) ->
            val existing=original.firstOrNull{it.id==id}
            existing==null || (unknown && existing.phase=="unknown")
        }.keys
        completed.keys.removeAll(represented)
        if(entries!=original) {
            try{write(entries);completed.keys.removeAll{ id->original.any{it.id==id} }}catch(_:Exception){ /* known results remain in memory for retry */ }
        }
        return compact(entries).toMutableList()
    }
    fun markDegraded(pair:String)=synchronized(lock){degradedPairs.add(pair);Unit}
    fun isDegraded(pair:String):Boolean=synchronized(lock){pair in degradedPairs}
    fun entries(pair:String):List<Entry> = synchronized(lock){reconciled().filter{it.pair==pair}}
    fun begin(pair:String,media:String?,folderLoad:Boolean=false):String = synchronized(lock){
        val entries=reconciled()
        if(folderLoad)check(pair !in degradedPairs && entries.none{it.pair==pair && (it.media.isEmpty() || it.media==media)}){"A previous file load is still unconfirmed"}
        check(entries.count{it.pair==pair && it.phase=="pending"}<16){"Subtitle requests still pending"}
        val id=UUID.randomUUID().toString();entries.add(Entry(id,pair,media.orEmpty(),"pending"));write(entries);live.add(id);id
    }
    /** Keeps a known outcome in process memory when durable cleanup needs retry. */
    fun finish(id:String,unknown:Boolean):Boolean = synchronized(lock){
        live.remove(id);completed[id]=unknown
        try{reconciled();!completed.containsKey(id)}catch(_:Exception){false}
    }
    fun clearOtherMedia(pair:String,current:String):Boolean = synchronized(lock){
        if(current.isEmpty())return@synchronized false
        try{val entries=reconciled();entries.removeAll{it.pair==pair && it.phase=="unknown" && it.media.isNotEmpty() && it.media!=current};write(entries);true}
        catch(_:Exception){false}
    }
}
