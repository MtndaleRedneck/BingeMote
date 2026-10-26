package com.bingemote
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
class BingeSettingsVisualTest {
 @Test fun restoredReleaseShowsBothSkipLabels() {
  val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
  assertEquals("com.bingemote",ctx.packageName)
  assertEquals("1.12.0",ctx.packageManager.getPackageInfo(ctx.packageName,0).versionName)
  val d=UiDevice.getInstance(i)
  ctx.startActivity(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  val settings=d.wait(Until.findObject(By.desc("Settings")),10000);assertNotNull(settings);settings.click()
  assertTrue(d.wait(Until.hasObject(By.text("Primary skip buttons")),10000))
  if(!d.hasObject(By.text("Secondary skip buttons"))) {d.swipe(d.displayWidth/2,d.displayHeight*3/4,d.displayWidth/2,d.displayHeight/3,35)}
  assertTrue(d.wait(Until.hasObject(By.text("Secondary skip buttons")),5000))
  Thread.sleep(1000)
  assertTrue(d.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"binge-settings-labels.png")))
 }
}
