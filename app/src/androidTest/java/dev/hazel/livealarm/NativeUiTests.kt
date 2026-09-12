package dev.hazel.livealarm

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.AudioManager
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NativeUiTests {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val prefs get() = Prefs(context)

    @Before fun open() {
        instrumentation.runOnMainSync { GuardianService.stopWatching(context) }
        prefs.raw().edit().clear().commit()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("native_root").assertIsDisplayed()
    }
    @After fun close() {
        instrumentation.runOnMainSync {
            GuardianService.stopWatching(context)
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<AlarmActivity>().toList().forEach { it.finish() }
        }
        scenario.close()
    }
    private fun appearance() {
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("appearance").performScrollTo().performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(500)
        // A preference callback may arrive during that pause; render its Compose frame too.
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val output = File(dir, "$name.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // Gradle uninstalls the target after instrumentation, including its external files.
        // Copy test-only screenshots with the test runner's shell before that cleanup.
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        // UiAutomation executes an argv command, so shell operators such as && are not parsed.
        val destination = "/sdcard/Download/ManquAlarm-native-screenshots"
        shell("mkdir -p $destination")
        shell("cp ${output.absolutePath} $destination/$name.png")
        assertTrue("Cannot preserve screenshot $name", shell("ls $destination/$name.png").contains("$name.png"))
    }
    private fun assertReadableDarkText(label: String) {
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        compose.waitUntil(5000) {
            val pixels = compose.onNodeWithText(label).captureToImage().toPixelMap()
            var bright = 0; var dark = 0
            for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
                val luminance = pixels[x, y].luminance()
                if (luminance > .6f) bright++
                if (luminance < .15f) dark++
            }
            bright > 10 && dark > pixels.width * pixels.height / 2
        }
    }
    @Test fun a_nativeNavigationAndThemePersistWithoutChangingAlarmRules() {
        scenario.onActivity { a ->
            fun hasWeb(view: View): Boolean = view is WebView || view is ViewGroup && (0 until view.childCount).any { hasWeb(view.getChildAt(it)) }
            assertFalse("Native app must not create a WebView", hasWeb(a.window.decorView))
            a.performAction("save", JSONObject().put("pollSeconds", 15).put("timezone", "Asia/Tokyo").put("ringtone", "morning"))
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("15 秒").fetchSemanticsNodes().isNotEmpty() }
        screenshot("01-home-pink")
        appearance()
        compose.onNodeWithTag("hex_picker").performScrollTo().performClick()
        compose.onNodeWithTag("hex_input").performTextReplacement("#397EB3")
        compose.onNodeWithTag("apply_color").performClick()
        compose.waitUntil(5000) { prefs.config().optString("seedColor") == "#397EB3" }
        assertEquals(15, prefs.config().optInt("pollSeconds")); assertEquals("Asia/Tokyo", prefs.config().optString("timezone")); assertEquals("morning", prefs.config().optString("ringtone"))
        screenshot("02-appearance-blue")
        scenario.recreate()
        compose.onNodeWithTag("native_root").assertIsDisplayed()
        assertEquals("#397EB3", prefs.config().optString("seedColor"))
        assertFalse(prefs.enabled())
        assertFalse(GuardianService.running)
    }
    @Test fun b_recentsFlagCanBeEnabledPersistedAndReversed() {
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("hide_recents").performScrollTo().performClick()
        compose.waitUntil(5000) { prefs.config().optBoolean("hideRecents") }
        fun verify(excluded: Boolean) { scenario.onActivity { a ->
            val manager = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val task = manager.appTasks.first { it.taskInfo.id == a.taskId }
            assertEquals(excluded, task.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
            assertNotNull(a.packageManager.getLaunchIntentForPackage(a.packageName))
        } }
        verify(true); scenario.recreate(); verify(true)
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("hide_recents").performScrollTo().performClick()
        compose.waitUntil(5000) { !prefs.config().optBoolean("hideRecents") }
        verify(false)
    }
    @Test fun c_backgroundImportIsBoundedPrivateAndSurvivesOriginalRemoval() {
        val original = File(context.cacheDir, "test-wallpaper.png")
        val bitmap = Bitmap.createBitmap(2800, 1800, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 2800f, 1800f, Color.rgb(189, 207, 225), Color.rgb(241, 204, 220), Shader.TileMode.CLAMP) }
        Canvas(bitmap).drawRect(0f, 0f, 2800f, 1800f, paint)
        original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        BackgroundStore.importImage(context, prefs, Uri.fromFile(original))
        val saved = prefs.raw().getString("backgroundPath", "")!!
        assertTrue(File(saved).exists()); assertEquals(context.filesDir, File(saved).parentFile)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(saved, bounds)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 1920)
        original.delete(); assertTrue(File(saved).exists())
        val invalid = File(context.cacheDir, "invalid-image").apply { writeText("invalid") }
        try { BackgroundStore.importImage(context, prefs, Uri.fromFile(invalid)); fail("Invalid image must be rejected") } catch (_: Exception) {}
        assertEquals(saved, prefs.raw().getString("backgroundPath", "")); assertTrue(File(saved).exists())
        appearance(); screenshot("03-custom-background")
        compose.onNodeWithTag("remove_background").performScrollTo().performClick()
        compose.waitUntil(5000) { prefs.raw().getString("backgroundPath", "").isNullOrEmpty() }
        assertFalse(File(saved).exists())
    }
    @Test fun d_scheduleEditingAndDarkDynamicThemes() {
        compose.onNodeWithTag("nav_schedule").performClick()
        compose.onNodeWithTag("add_schedule").performScrollTo().performClick()
        compose.onNodeWithTag("rule_name").performTextReplacement("周末提醒")
        compose.onNodeWithTag("save_rule").performClick()
        compose.waitUntil(5000) { prefs.config().optJSONArray("windows")!!.length() == 2 }
        scenario.onActivity { it.performAction("save", JSONObject().put("theme", "dark").put("amoled", true).put("seedColor", "#A65C83")) }
        assertReadableDarkText("全天提醒")
        assertReadableDarkText("周末提醒")
        compose.onNodeWithTag("all_day").performScrollTo()
        screenshot("04-native-schedule-dark")
        appearance()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            compose.onNodeWithTag("dynamic_color").performScrollTo().performClick()
            compose.waitUntil(5000) { prefs.config().optBoolean("dynamicColor") }
        }
        assertReadableDarkText("配色方案")
        if (android.os.Build.VERSION.SDK_INT >= 31) assertReadableDarkText("动态颜色")
        compose.onNodeWithTag("hex_picker").performScrollTo()
        screenshot("05-appearance-dark")
    }
    @Test fun e_recoveryRestartsEnabledWatchAndAppearanceDoesNotRestartIt() {
        var armedAt = 0L
        scenario.onActivity {
            val tomorrow = 1 shl (java.time.LocalDate.now().dayOfWeek.value % 7)
            val window = JSONObject().put("id", "test-window").put("name", "测试时段").put("start", 1).put("end", 2).put("days", tomorrow).put("enabled", true)
            prefs.update(JSONObject().put("allDay", false).put("windows", org.json.JSONArray().put(window)).put("pollSeconds", 15))
            prefs.setEnabled(true); armedAt = prefs.raw().getLong("armedAt", 0)
            WatchRecovery.receive(context)
        }
        compose.waitUntil(10000) { GuardianService.running && prefs.raw().getLong("serviceStartedAt", 0) > 0 }
        val startedAt = prefs.raw().getLong("serviceStartedAt", 0)
        scenario.onActivity { it.performAction("save", JSONObject().put("seedColor", "#397EB3").put("hideRecents", true)) }
        assertEquals(startedAt, prefs.raw().getLong("serviceStartedAt", 0))
        assertEquals(armedAt, prefs.raw().getLong("armedAt", 0))
        assertEquals(15, prefs.config().optInt("pollSeconds"))
        assertTrue(prefs.raw().getLong("lastRecovery", 0) > 0)
        assertTrue(prefs.raw().getLong("recoveryAt", 0) > System.currentTimeMillis())
    }
    @Test fun e_stoppingWatchCancelsRecoveryAndDoesNotResurrectService() {
        scenario.onActivity {
            prefs.setEnabled(true); WatchRecovery.schedule(context, true)
            assertTrue(prefs.raw().getLong("recoveryAt", 0) > System.currentTimeMillis())
            GuardianService.stopWatching(context)
            WatchRecovery.receive(context)
            assertFalse(prefs.enabled()); assertEquals(0L, prefs.raw().getLong("recoveryAt", 0)); assertFalse(GuardianService.running)
            prefs.setEnabled(true); prefs.update(JSONObject().put("recovery", false)); WatchRecovery.schedule(context, true)
            assertEquals(0L, prefs.raw().getLong("recoveryAt", 0))
            prefs.setEnabled(false)
        }
    }
    @Test fun f_nativeAlarmStartsAudioAndStopsCleanly() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val beforeVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val began = System.currentTimeMillis()
        scenario.onActivity { it.performAction("save", JSONObject().put("volume", 10).put("duration", 30)); it.performAction("test", JSONObject()) }
        compose.waitUntil(12000) { GuardianService.ringing && prefs.raw().getLong("lastAudioStartedAt", 0) >= began }
        compose.onNodeWithTag("native_alarm").assertIsDisplayed()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(manager.activeNotifications.any { it.id == GuardianService.WATCH_ID })
        screenshot("06-native-alarm")
        compose.onNodeWithTag("alarm_primary").performScrollTo().performClick()
        compose.waitUntil(8000) { !GuardianService.ringing && !GuardianService.running }
        assertEquals(beforeVolume, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }
}
