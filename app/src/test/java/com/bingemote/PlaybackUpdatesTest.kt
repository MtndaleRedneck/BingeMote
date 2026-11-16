package com.bingemote
import org.junit.Assert.*
import org.junit.Test
class PlaybackUpdatesTest {
 @Test fun olderSlowReplyCannotReplaceNewerPlayback() {
  val t=Long.MAX_VALUE-10
  val newer=VlcStatus("paused","new",30,100,128,false,emptyList())
  PlaybackUpdates.publish(t,newer)
  PlaybackUpdates.publish(t-1,newer.copy(state="playing",title="old"))
  assertEquals("new",PlaybackUpdates.latest.value!!.status.title)
  assertFalse(PlaybackUpdates.latest.value!!.status.playing)
 }
}
