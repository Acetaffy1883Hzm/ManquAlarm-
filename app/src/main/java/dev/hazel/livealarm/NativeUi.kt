@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hazel.livealarm

import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal typealias Action = (String, JSONObject) -> Boolean
private fun json(key: String, value: Any) = JSONObject().put(key, value)
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
private fun stamp(time: Long, zone: String = "device"): String = if (time <= 0) "尚未检测" else try { DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(if (zone == "device") ZoneId.systemDefault() else ZoneId.of(zone)).format(Instant.ofEpochMilli(time)) } catch (_: Exception) { "时间不可用" }
private val LocalCardAlpha = staticCompositionLocalOf { 1f }

@Composable
internal fun NativeApp(host: MainActivity, state: JSONObject, alarm: Boolean) {
    val config = state.optJSONObject("config") ?: JSONObject()
    var route by rememberSaveable { mutableStateOf("home") }
    var dialog by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val action: Action = { name, value ->
        try { host.performAction(name, value); true }
        catch (e: Exception) { scope.launch { snack.showSnackbar(e.message ?: "操作未完成，请重试") }; false }
    }
    val save: (String, Any) -> Unit = { key, value -> action("save", json(key, value)) }
    val colors = MaterialTheme.colorScheme
    SideEffect {
        WindowCompat.getInsetsController(host.window, host.window.decorView).apply {
            isAppearanceLightStatusBars = colors.background.luminance() > .5f
            isAppearanceLightNavigationBars = colors.background.luminance() > .5f
        }
        host.window.statusBarColor = colors.background.toArgb()
        host.window.navigationBarColor = colors.background.toArgb()
    }
    val background by rememberBackground(state.optString("backgroundPath"))
    CompositionLocalProvider(LocalCardAlpha provides config.optInt("cardOpacity", 94).coerceIn(75, 100) / 100f) {
        Box(Modifier.fillMaxSize().background(colors.background).testTag("native_root")) {
            background?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = config.optInt("backgroundDim", 40).coerceIn(0, 90) / 100f)))
            }
            if (alarm) {
                AlarmScreen(state, action)
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            } else {
                Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snack) },
                    topBar = {
                        TopAppBar(title = { Text(when (route) { "schedule" -> "提醒时段"; "sound" -> "声音"; "settings" -> "设置"; "appearance" -> "外观"; "history" -> "运行记录"; else -> "满区闹钟" }, fontWeight = FontWeight.Bold) },
                            navigationIcon = { if (route in listOf("appearance", "history")) IconButton(onClick = { route = if (route == "appearance") "settings" else "home" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                            actions = { if (route != "history") IconButton(onClick = { route = "history" }, modifier = Modifier.testTag("history")) { Icon(Icons.Rounded.History, "运行记录") } },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background.copy(alpha = .95f)))
                    }, bottomBar = {
                        NavigationBar(containerColor = colors.surface.copy(alpha = .97f)) {
                            listOf(Triple("home", "守候", Icons.Rounded.Notifications), Triple("schedule", "时段", Icons.Rounded.Schedule), Triple("sound", "声音", Icons.AutoMirrored.Rounded.VolumeUp), Triple("settings", "设置", Icons.Rounded.Settings)).forEach { (id, title, icon) ->
                                NavigationBarItem(selected = route == id || id == "settings" && route == "appearance", onClick = { route = id }, icon = { Icon(icon, null) }, label = { Text(title) }, modifier = Modifier.testTag("nav_$id"))
                            }
                        }
                    }) { padding ->
                    key(route) {
                        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (route) {
                                "home" -> HomeScreen(state, action, { dialog = "test" }, { route = "settings" }, { route = "appearance" })
                                "schedule" -> ScheduleScreen(config, action)
                                "sound" -> SoundScreen(config, save, action) { dialog = "test" }
                                "settings" -> SettingsScreen(state, action, save, { route = "appearance" }, { dialog = it })
                                "appearance" -> AppearanceScreen(config, state, save, action)
                                "history" -> HistoryScreen(host, state, action)
                            }
                        }
                    }
                }
            }
        }
    }
    BackHandler(enabled = !alarm && route != "home") { route = if (route == "appearance") "settings" else "home" }
    BackHandler(enabled = alarm || route == "home") { action("minimize", JSONObject()) }
    when (dialog) {
        "test" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("测试这次提醒") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("先用较低音量确认声音和振动，再锁屏测试。测试使用与正式提醒相同的原生播放流程。")
                Button(onClick = { if (action("test", JSONObject())) dialog = null }, modifier = Modifier.fillMaxWidth().testTag("test_now")) { Text("立即响铃") }
                OutlinedButton(onClick = { if (action("testLater", JSONObject())) { dialog = null; Toast.makeText(host, "15 秒后响铃，现在可以锁屏", Toast.LENGTH_LONG).show() } }, modifier = Modifier.fillMaxWidth()) { Text("15 秒后锁屏测试") }
                if (state.optLong("testAt") > 0) TextButton(onClick = { action("cancelTest", JSONObject()); dialog = null }) { Text("取消已安排的测试") }
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } })
        "compat" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("通知受阻时仍尝试响铃？") }, text = { Text("开启后，即使通知权限未获准，也会按你的时段和音量尝试播放铃声。通知栏或锁屏页面可能不显示；可以从桌面打开应用停止响铃。此开关不会修改系统通知权限。") }, confirmButton = { TextButton(onClick = { save("soundWithoutNotifications", true); dialog = null }) { Text("允许兼容响铃") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
        "report" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("保存通知排查包") }, text = { Text("默认导出权限、通道、运行状态与诊断记录。仅在需要深入排查时附带可读取的系统权限组件安装包；文件只保存到你选择的位置，不会自动上传。") }, confirmButton = { TextButton(onClick = { action("exportNotificationReport", json("includeComponents", false)); dialog = null }) { Text("仅诊断记录") } }, dismissButton = { TextButton(onClick = { action("exportNotificationReport", json("includeComponents", true)); dialog = null }) { Text("附带权限组件") } })
        "help" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("通知与后台帮助") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val p = state.optJSONObject("permissions") ?: JSONObject()
                Text("通知授权：${if (p.optBoolean("notifications")) "已允许" else "未允许"}\n策略检查：${if (p.optString("notificationPolicy") == "revoked") "系统策略拒绝授权" else "未检测到策略拒绝或暂不可读取"}")
                Text("在系统中允许通知、自启动和后台电池运行。小米等机型的清理策略需要在手机设置中检查。通知被设备管理策略固定拒绝时，应用无法自行解除。")
                Text("从最近任务隐藏仅减少误划卡片；系统强制停止、关机与断网仍会影响提醒。恢复检查可能被系统延后。")
                TextButton(onClick = { action("permission", json("kind", "notificationSettings")) }) { Text("打开通知设置") }
                TextButton(onClick = { action("notificationProbe", JSONObject()) }) { Text("发送验证通知") }
                TextButton(onClick = { dialog = "report" }) { Text("导出排查包") }
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("知道了") } })
        "accessibility" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("可选的守候恢复辅助") }, text = { Text("仅检查本应用守候是否运行，服务中断时尝试请求恢复。不读取屏幕内容、不点击其他应用；只有守候开启时才工作，仍不能突破系统强制停止。") }, confirmButton = { TextButton(onClick = { action("permission", json("kind", "accessibility")); dialog = null }) { Text("前往系统设置") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
        "privacy" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("数据与使用说明") }, text = { Text("直接检测灰泽满的 B 站公开直播接口，不需要账号、Cookie 或云端中转。设置、铃声、背景图片和记录保存在本机，不上传。\n\n背景图片通过系统选择器选取，复制并缩放后保存于应用内部。备份暂不包含自选铃声与背景图片，换机后需重新选择。\n\n原生界面沿用同一套时段、去重和响铃逻辑。提醒仍受网络、接口、通知授权和手机后台策略影响。") }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("知道了") } })
    }
}

@Composable private fun rememberBackground(path: String): State<Bitmap?> = produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { if (path.isEmpty()) null else try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null } } }
@Composable private fun HazelPicture(modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null) { value = withContext(Dispatchers.IO) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeResource(context.resources, R.drawable.hazel, o)
        o.inSampleSize = 1; while (maxOf(o.outWidth, o.outHeight) / o.inSampleSize > 768) o.inSampleSize *= 2
        o.inJustDecodeBounds = false; BitmapFactory.decodeResource(context.resources, R.drawable.hazel, o)
    } }
    bitmap?.let { Image(it.asImageBitmap(), "灰泽满", modifier.clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Fit) }
}
@Composable private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun SectionTitle(title: String, subtitle: String = "") {
    Column(Modifier.padding(start = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
@Composable private fun ToggleRow(title: String, description: String, checked: Boolean, tag: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, fontWeight = FontWeight.Medium); if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange, modifier = Modifier.testTag(tag.ifEmpty { title }))
    }
}
@Composable private fun LinkRow(title: String, description: String = "", icon: ImageVector = Icons.AutoMirrored.Rounded.OpenInNew, tag: String = title, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).testTag(tag).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, fontWeight = FontWeight.Medium); if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun ChoiceRow(title: String, current: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Box { TextButton(onClick = { expanded = true }) { Text(options.firstOrNull { it.first == current }?.second ?: current); Icon(Icons.Rounded.ExpandMore, null) }
            DropdownMenu(expanded, { expanded = false }) { options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(id); expanded = false }) } }
        }
    }
}

@Composable private fun HomeScreen(state: JSONObject, action: Action, test: () -> Unit, settings: () -> Unit, appearance: () -> Unit) {
    val c = state.optJSONObject("config") ?: JSONObject(); val snap = state.optJSONObject("snapshot") ?: JSONObject()
    val enabled = state.optBoolean("enabled"); val running = state.optBoolean("running")
    val error = state.optString("networkError").ifEmpty { state.optString("serviceError") }
    val status = when { error.isNotEmpty() -> "等待恢复"; snap.optLong("checkedAt") == 0L -> "尚未检测"; System.currentTimeMillis() - snap.optLong("checkedAt") > maxOf(300000L, c.optInt("pollSeconds",30) * 4000L) -> "状态待更新"; snap.optInt("status") == 1 -> "正在直播"; snap.optInt("status") == 2 -> "轮播中 · 不触发提醒"; else -> "暂未开播" }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .94f)), shape = RoundedCornerShape(30.dp)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("灰泽满 HAZEL", style = MaterialTheme.typography.labelMedium)
                Text("安心去忙，\n开播叫你。", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("把期待，交给一声铃响", style = MaterialTheme.typography.bodySmall)
            }
            HazelPicture(Modifier.width(112.dp).height(142.dp))
        }
    }
    if (state.optBoolean("ringing")) Panel { Text("响铃进行中", fontWeight = FontWeight.Bold); Button(onClick = { action("openAlarm", JSONObject()) }, modifier = Modifier.fillMaxWidth()) { Text("查看提醒与关闭响铃") } }
    Panel {
        ToggleRow(if (enabled) "正在替你守候" else "开始守候", if (enabled) { if (running) "${if (state.optBoolean("inside")) "当前在提醒时段" else "时段外低频检查"} · 原生后台运行中" else "服务需要恢复，请检查后台设置" } else "开启后按你的时段检测直播", enabled, "watch_toggle") { action("toggle", json("enabled", it)) }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(status, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); TextButton(onClick = { action("refresh", JSONObject()) }) { Icon(Icons.Rounded.Refresh, null); Text("检测") } }
        if (snap.optString("title").isNotEmpty()) Text(snap.optString("title"), style = MaterialTheme.typography.bodyMedium)
        Text("最近成功检测：${stamp(snap.optLong("checkedAt"), state.optString("zone", "device"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(if (c.optBoolean("allDay")) "全天" else "${c.optJSONArray("windows")?.objects()?.count { it.optBoolean("enabled") } ?: 0} 个时段", fontWeight = FontWeight.Bold); Text("提醒范围", style = MaterialTheme.typography.bodySmall) }
            Column { Text("${c.optInt("pollSeconds", 30)} 秒", fontWeight = FontWeight.Bold); Text("检测间隔", style = MaterialTheme.typography.bodySmall) }
            Column { Text("${c.optInt("volume", 85)}%", fontWeight = FontWeight.Bold); Text("闹钟音量", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (state.optLong("testAt") > 0) Panel { Text("锁屏测试：${stamp(state.optLong("testAt"))}"); TextButton(onClick = { action("cancelTest", JSONObject()) }) { Text("取消测试") } }
    if (state.optLong("snoozeAt") > 0) Panel { Text("稍后提醒：${stamp(state.optLong("snoozeAt"))}"); TextButton(onClick = { action("cancelSnooze", JSONObject()) }) { Text("取消稍后提醒") } }
    Panel {
        LinkRow("锁屏与响铃测试", "确认声音、振动和提醒页面", Icons.Rounded.PlayCircle, "open_test", test)
        LinkRow("打开直播间", "去看看小满", Icons.AutoMirrored.Rounded.OpenInNew) { action("openLive", JSONObject()) }
    }
    val p = state.optJSONObject("permissions") ?: JSONObject()
    Panel {
        LinkRow("提醒与后台检查", if (p.optBoolean("notifications") && p.optBoolean("battery")) "主要权限已就绪，建议做一次锁屏测试" else "检查通知授权与后台电池设置", Icons.Rounded.VerifiedUser, onClick = settings)
        LinkRow("换一种喜欢的颜色", "配色方案与背景图片", Icons.Rounded.Palette, "home_appearance", appearance)
    }
    Text("同一场直播只自动提醒一次。网络、休眠与系统强制停止仍可能影响提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable private fun SoundScreen(c: JSONObject, save: (String, Any) -> Unit, action: Action, test: () -> Unit) {
    SectionTitle("选一声喜欢的铃响", "使用系统闹钟音量通道，保留渐强与自动停止。")
    Panel {
        listOf("starlight" to "星铃", "morning" to "清晨", "urgent" to "强提醒", "system" to "系统闹钟铃声").forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { save("ringtone", id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(c.optString("ringtone") == id, onClick = { save("ringtone", id) }); Text(label)
            }
        }
        LinkRow("导入自己的铃声", c.optString("customName", "未选择") + if (c.optString("ringtone") == "custom") " · 已选中" else "", Icons.Rounded.AudioFile) { action("pickAudio", JSONObject()) }
        if (c.optString("customName", "未选择") != "未选择" && c.optString("ringtone") != "custom") TextButton(onClick = { save("ringtone", "custom") }) { Text("使用已导入铃声") }
    }
    Panel { IntSlider("闹钟音量", c.optInt("volume", 85), 1..100, "%") { save("volume", it) } }
    Panel {
        ToggleRow("渐强响铃", "约 5 秒从轻到响", c.optBoolean("ramp")) { save("ramp", it) }
        ToggleRow("同时振动", "响铃时循环振动", c.optBoolean("vibrate")) { save("vibrate", it) }
        ToggleRow("通话时不强响铃", "通话时按设置振动，避免打断通话", c.optBoolean("quietCalls", true)) { save("quietCalls", it) }
        ChoiceRow("自动停止", c.optInt("duration", 60).toString(), listOf("15" to "15 秒", "30" to "30 秒", "60" to "1 分钟", "120" to "2 分钟", "300" to "5 分钟")) { save("duration", it.toInt()) }
        ChoiceRow("稍后提醒间隔", c.optInt("snoozeMinutes", 5).toString(), listOf("3" to "3 分钟", "5" to "5 分钟", "10" to "10 分钟", "15" to "15 分钟")) { save("snoozeMinutes", it.toInt()) }
    }
    Button(onClick = test, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("试一试这声铃响") }
    Text("耳机与勿扰模式下的播放由系统控制。响铃结束后恢复原音量；如果你手动改过音量，会保留你的调整。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun IntSlider(title: String, current: Int, range: IntRange, suffix: String, onSave: (Int) -> Unit) {
    var value by remember(current) { mutableFloatStateOf(current.toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontWeight = FontWeight.Medium); Text("${value.toInt()}$suffix", color = MaterialTheme.colorScheme.primary) }
        Slider(value, { value = it }, valueRange = range.first.toFloat()..range.last.toFloat(), onValueChangeFinished = { onSave(value.toInt()) }, modifier = Modifier.testTag(title))
    }
}

@Composable private fun AlarmScreen(state: JSONObject, action: Action) {
    val c = state.optJSONObject("config") ?: JSONObject(); val ringing = state.optBoolean("ringing"); val testing = state.optBoolean("alarmTest")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ringing, state.optLong("alarmUntil")) { while (ringing) { now = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp).testTag("native_alarm"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(12.dp)); AssistChip(onClick = {}, label = { Text(if (!ringing) "提醒已结束" else if (testing) "声音测试" else "灰泽满开播了") }, leadingIcon = { Icon(Icons.Rounded.NotificationsActive, null) })
        HazelPicture(Modifier.size(190.dp))
        Panel {
            Text(if (!ringing) "响铃已结束。" else if (testing) "这一声，听见了吗？" else "小满开播啦。", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(state.optString("alarmTitle"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ringing) Text("${maxOf(0L, (state.optLong("alarmUntil") - now + 999) / 1000)} 秒后自动停止", style = MaterialTheme.typography.bodySmall)
        }
        if (ringing) {
            Button(onClick = { action(if (testing) "dismiss" else "openLive", JSONObject()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("alarm_primary")) { Text(if (testing) "听见了，结束测试" else "去直播间 · 关闭响铃") }
            if (!testing) OutlinedButton(onClick = { action("snooze", JSONObject()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("${c.optInt("snoozeMinutes",5)} 分钟后再提醒") }
            TextButton(onClick = { action("dismiss", JSONObject()) }, modifier = Modifier.testTag("dismiss_alarm")) { Text("关闭响铃") }
        } else Button(onClick = { action("closeAlarm", JSONObject()) }, modifier = Modifier.fillMaxWidth()) { Text("知道了") }
        Text("返回键收起页面，铃声仍继续。请使用上方按钮或通知中的关闭按钮。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SettingsScreen(state: JSONObject, action: Action, save: (String, Any) -> Unit, appearance: () -> Unit, show: (String) -> Unit) {
    val c = state.optJSONObject("config") ?: JSONObject(); val p = state.optJSONObject("permissions") ?: JSONObject()
    Panel { LinkRow("外观与个性化", "配色方案、动态颜色、背景图片", Icons.Rounded.Palette, "appearance", appearance) }
    SectionTitle("后台守候", "检测在原生服务中运行，与页面是否打开无关。")
    Panel {
        ToggleRow("从最近任务隐藏", "减少误划任务卡片；桌面图标和通知仍可打开应用。不阻止系统强制停止。", c.optBoolean("hideRecents"), "hide_recents") { save("hideRecents", it) }
        HorizontalDivider()
        ToggleRow("定时恢复检查", "约每 15 分钟补充检查服务；系统休眠时可能推迟，不改变直播检测间隔。", c.optBoolean("recovery", true), "recovery") { save("recovery", it) }
        if (c.optBoolean("recovery", true) && state.optLong("recoveryAt") > 0) Text("已安排恢复检查 · ${stamp(state.optLong("recoveryAt"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow("优先保证提醒", "提醒时段内持续检测，更耗电", c.optBoolean("reliable", true)) { save("reliable", it) }
        ChoiceRow("检测间隔", c.optInt("pollSeconds",30).toString(), listOf("15" to "15 秒", "30" to "30 秒", "60" to "60 秒", "120" to "120 秒")) { save("pollSeconds", it.toInt()) }
        ToggleRow("重启后恢复守候", "只恢复此前已开启的守候", c.optBoolean("boot", true)) { save("boot", it) }
    }
    SectionTitle("权限与通知")
    Panel {
        data class Permission(val key: String, val title: String, val ready: Boolean, val hint: String)
        listOf(
            Permission("notifications", "允许通知", p.optBoolean("notifications"), if (p.optString("notificationPolicy") == "revoked") "系统策略拒绝授权" else "开播与守候通知"),
            Permission("watchChannel", "守候通知通道", p.optBoolean("watchChannel"), "通知内可检测或停止守候"),
            Permission("alarmChannel", "开播强提醒通道", p.optBoolean("alarmChannel"), "横幅与锁屏显示"),
            Permission("battery", "后台电池权限", p.optBoolean("battery"), "减少后台检测中断"),
            Permission("fullScreen", "全屏提醒", p.optBoolean("fullScreen"), "锁屏时显示提醒页面"),
            Permission("exact", "精确闹钟", p.optBoolean("exact"), "用于时段边界、暂缓与定时测试")
        ).forEach { item -> LinkRow(item.title, "${if (item.ready) "已就绪" else "请检查"} · ${item.hint}", if (item.ready) Icons.Rounded.CheckCircle else Icons.Rounded.Info) { action("permission", json("kind", item.key)) } }
        LinkRow("无障碍守候辅助 · 可选", if (p.optBoolean("accessibilityConnected")) "已连接" else "不读取页面，仅辅助恢复服务", Icons.Rounded.AccessibilityNew) { show("accessibility") }
        LinkRow("勿扰设置", if (p.optBoolean("dnd")) "勿扰已开启，请确认允许闹钟" else "当前未开启勿扰", Icons.Rounded.DoNotDisturbOn) { action("permission", json("kind", "dnd")) }
        LinkRow("系统自启动与应用设置", "厂商的清理策略需在手机设置中检查", Icons.Rounded.Settings) { action("permission", json("kind", "app")) }
    }
    Panel {
        ToggleRow("通知异常时仍响铃", "通知受阻时尝试播放声音，通知权限保持原状", c.optBoolean("soundWithoutNotifications"), "compat_sound") { if (it) show("compat") else save("soundWithoutNotifications", false) }
        LinkRow("悬浮关闭按钮 · 可选", "仅兼容响铃时使用", Icons.Rounded.PictureInPictureAlt) { action("permission", json("kind", "overlay")) }
    }
    SectionTitle("数据与帮助")
    Panel {
        LinkRow("通知授权帮助", icon = Icons.Rounded.HelpOutline) { show("help") }
        LinkRow("锁屏与响铃测试", icon = Icons.Rounded.PlayCircle) { show("test") }
        LinkRow("导出通知排查包", icon = Icons.Rounded.BugReport) { show("report") }
        LinkRow("导出设置与诊断记录", icon = Icons.Rounded.FileUpload) { action("export", JSONObject()) }
        LinkRow("从备份恢复设置", icon = Icons.Rounded.FileDownload) { action("import", JSONObject()) }
        LinkRow("隐私与使用说明", icon = Icons.Rounded.PrivacyTip) { show("privacy") }
        LinkRow("灰泽满的 B 站空间") { action("openProfile", JSONObject()) }
    }
    Text("满区闹钟 ${state.optString("version")}\n为灰泽满 Hazel 制作 · 非官方应用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable private fun AppearanceScreen(c: JSONObject, state: JSONObject, save: (String, Any) -> Unit, action: Action) {
    var picker by remember { mutableStateOf(false) }
    val presets = listOf("#A65C83", "#6D7DB4", "#4F7FA4", "#447A6A", "#88743C", "#B36A46", "#9865AB", "#B85872", "#536B81", "#6D7650", "#B2748C", "#655C74")
    SectionTitle("让它更像你", "选择喜欢的颜色，让界面随心变化。")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.Palette, null, Modifier.size(38.dp)); Column(Modifier.weight(1f)) { Text("属于你的满区闹钟", fontWeight = FontWeight.Bold); Text("按钮、开关和卡片一起换色", style = MaterialTheme.typography.bodySmall) }
            Switch(true, null)
        }
    }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("配色方案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(if (c.optBoolean("dynamicColor") && Build.VERSION.SDK_INT >= 31) "正在使用系统壁纸颜色" else c.optString("seedColor", "#A65C83"), style = MaterialTheme.typography.bodySmall) }
            IconButton(onClick = { picker = true }, modifier = Modifier.size(54.dp).background(Brush.sweepGradient(listOf(Color(0xFFF5B9D9), Color(0xFFACD4F3), Color(0xFFBCE4CE), Color(0xFFF7D7A8), Color(0xFFF5B9D9))), CircleShape).testTag("custom_color")) { Icon(Icons.Rounded.Edit, "自定义颜色", tint = Color(0xFF463849)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            presets.forEach { hex ->
                val selected = !c.optBoolean("dynamicColor") && c.optString("seedColor", "#A65C83").equals(hex, true)
                Box(Modifier.size(44.dp).clip(CircleShape).background(parseColor(hex)).then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier).clickable { action("save", JSONObject().put("seedColor", hex).put("dynamicColor", false)) }.testTag("color_${hex.drop(1)}"), contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Rounded.Check, "已选择", tint = inkFor(parseColor(hex)), modifier = Modifier.size(22.dp)) }
            }
        }
        TextButton(onClick = { picker = true }, modifier = Modifier.testTag("hex_picker")) { Text("选择任意颜色 / 输入 HEX") }
        HorizontalDivider()
        if (Build.VERSION.SDK_INT >= 31) ToggleRow("动态颜色", "使用系统壁纸的配色；关闭后恢复自选颜色", c.optBoolean("dynamicColor"), "dynamic_color") { save("dynamicColor", it) }
        else Text("动态颜色适用于 Android 12 及以上；当前可使用自选配色。", style = MaterialTheme.typography.bodySmall)
    }
    Panel {
        ChoiceRow("夜间模式", c.optString("theme", "light"), listOf("light" to "浅色", "dark" to "深色", "system" to "跟随系统")) { save("theme", it) }
        ToggleRow("AMOLED 纯黑模式", "深色主题使用纯黑底色；自选背景仍按你的设置显示", c.optBoolean("amoled"), "amoled") { save("amoled", it) }
    }
    SectionTitle("背景图片", "只读取你选择的图片，不需要访问整个相册。")
    Panel {
        Text(state.optString("backgroundName").ifEmpty { "还没有设置背景" }, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { action("pickBackground", JSONObject()) }, modifier = Modifier.weight(1f).testTag("pick_background")) { Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(6.dp)); Text("选择图片") }
            if (state.optString("backgroundPath").isNotEmpty()) OutlinedButton(onClick = { action("removeBackground", JSONObject()) }, modifier = Modifier.testTag("remove_background")) { Text("移除") }
        }
        Text("支持 JPG、PNG、WebP 等系统可解码图片，最大 20 MB。自动适配尺寸，原图移动后仍可使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IntSlider("背景遮罩", c.optInt("backgroundDim", 40), 0..90, "%") { save("backgroundDim", it) }
        IntSlider("卡片不透明度", c.optInt("cardOpacity", 94), 75..100, "%") { save("cardOpacity", it) }
    }
    if (picker) ColorPicker(c.optString("seedColor", "#A65C83"), { picker = false }) { hex -> if (action("save", JSONObject().put("seedColor", hex).put("dynamicColor", false))) picker = false }
}

@Composable private fun ColorPicker(initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var hex by remember { mutableStateOf(initial.uppercase()) }
    val valid = hex.matches(Regex("#[0-9A-Fa-f]{6}")); val selected = parseColor(if (valid) hex else initial)
    AlertDialog(onDismissRequest = dismiss, title = { Text("自定义配色") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth().height(74.dp).clip(RoundedCornerShape(22.dp)).background(selected), contentAlignment = Alignment.Center) { Text("配色预览", color = inkFor(selected), fontWeight = FontWeight.Bold) }
            OutlinedTextField(hex, { hex = it.take(7).uppercase() }, singleLine = true, label = { Text("HEX 颜色") }, placeholder = { Text("#A65C83") }, isError = !valid, modifier = Modifier.fillMaxWidth().testTag("hex_input"))
            listOf("红" to selected.red, "绿" to selected.green, "蓝" to selected.blue).forEachIndexed { index, (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.width(28.dp))
                    Slider(value, { updated ->
                        val channels = floatArrayOf(selected.red, selected.green, selected.blue); channels[index] = updated
                        hex = "#%02X%02X%02X".format((channels[0] * 255).toInt(), (channels[1] * 255).toInt(), (channels[2] * 255).toInt())
                    }, modifier = Modifier.weight(1f))
                    Text((value * 255).toInt().toString(), Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { save(hex) }, enabled = valid, modifier = Modifier.testTag("apply_color")) { Text("应用配色") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun ScheduleScreen(c: JSONObject, action: Action) {
    val windows = c.optJSONArray("windows") ?: JSONArray()
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var zones by remember { mutableStateOf(false) }
    fun updateWindows(list: List<JSONObject>): Boolean = action("save", json("windows", JSONArray(list)))
    SectionTitle("把提醒留在合适的时候", "按星期重复，支持跨午夜与海外时区。")
    Panel { ToggleRow("全天提醒", "关闭后使用下方启用的时间段", c.optBoolean("allDay", true), "all_day") { action("save", json("allDay", it)) } }
    windows.objects().forEach { window ->
        Panel {
            ToggleRow(window.optString("name", "提醒时段"), "${clock(window.optInt("start"))} — ${clock(window.optInt("end"))}${if (window.optInt("end") <= window.optInt("start")) " · 次日" else ""}", window.optBoolean("enabled"), "window_${window.optString("id")}") { enabled -> updateWindows(windows.objects().map { if (it.optString("id") == window.optString("id")) JSONObject(it.toString()).put("enabled", enabled) else it }) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                val selected = window.optInt("days") and (1 shl index) != 0
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(label, fontSize = 12.sp) }
            } }
            Row { TextButton(onClick = { editing = JSONObject(window.toString()) }) { Text("编辑") }; TextButton(onClick = { updateWindows(windows.objects().filter { it.optString("id") != window.optString("id") }) }) { Text("删除") } }
        }
    }
    OutlinedButton(onClick = { editing = JSONObject().put("id", UUID.randomUUID().toString()).put("name", "自定义时段").put("start", 60).put("end", 360).put("days", 127).put("enabled", true) }, modifier = Modifier.fillMaxWidth().testTag("add_schedule")) { Icon(Icons.Rounded.Add, null); Text("添加提醒时段") }
    Panel {
        LinkRow("提醒时区", c.optString("timezone", "device").let { if (it == "device") "跟随手机 · ${ZoneId.systemDefault().id}" else it }, Icons.Rounded.Public) { zones = true }
        ToggleRow("补报已开播", "开启守候或进入时段时，若已在播也提醒；同场仍去重", c.optBoolean("catchUp"), "catch_up") { action("save", json("catchUp", it)) }
    }
    Text("时间起点包含、终点不包含。跨午夜的时段归开始那天；起止相同表示连续 24 小时。关闭补报后，只提醒在所选时段内开始的直播。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    editing?.let { draft -> RuleEditor(draft, { editing = null }) { rule ->
        val current = windows.objects().toMutableList(); val index = current.indexOfFirst { it.optString("id") == rule.optString("id") }
        if (index >= 0) current[index] = rule else current.add(rule)
        if (updateWindows(current)) editing = null
    } }
    if (zones) ZonePicker({ zones = false }) { zone -> if (action("save", json("timezone", zone))) zones = false }
}

@Composable private fun RuleEditor(initial: JSONObject, dismiss: () -> Unit, save: (JSONObject) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial.optString("name")) }; var start by remember { mutableIntStateOf(initial.optInt("start")) }; var end by remember { mutableIntStateOf(initial.optInt("end")) }; var days by remember { mutableIntStateOf(initial.optInt("days")) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("提醒时段") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("rule_name"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> start = h * 60 + m }, start / 60, start % 60, true).show() }, modifier = Modifier.weight(1f)) { Text("开始 ${clock(start)}") }
                OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> end = h * 60 + m }, end / 60, end % 60, true).show() }, modifier = Modifier.weight(1f)) { Text("结束 ${clock(end)}") }
            }
            Text("重复星期", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, label ->
                FilterChip(selected = days and (1 shl i) != 0, onClick = { days = days xor (1 shl i) }, label = { Text(label) })
            } }
            if (days == 0) Text("至少选择一天", color = MaterialTheme.colorScheme.error)
            if (end <= start) Text("${if (end == start) "连续 24 小时" else "结束时间在次日"}，星期按开始那天计算。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { save(JSONObject(initial.toString()).put("name", name.ifBlank { "提醒时段" }).put("start", start).put("end", end).put("days", days)) }, enabled = days != 0, modifier = Modifier.testTag("save_rule")) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun ZonePicker(dismiss: () -> Unit, select: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val names = remember { mapOf("Asia/Shanghai" to "北京 上海", "Asia/Hong_Kong" to "香港", "Asia/Tokyo" to "东京 日本", "Asia/Singapore" to "新加坡", "Asia/Taipei" to "台北", "Europe/London" to "伦敦 英国", "America/New_York" to "纽约 美国", "America/Los_Angeles" to "洛杉矶 美国", "Australia/Sydney" to "悉尼 澳大利亚", "Europe/Paris" to "巴黎 法国", "UTC" to "协调世界时") }
    val zones = remember { ZoneId.getAvailableZoneIds().sorted() }
    AlertDialog(onDismissRequest = dismiss, title = { Text("选择提醒时区") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索城市或时区") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { select("device") }) { Text("跟随手机 · ${ZoneId.systemDefault().id}") }
            LazyColumn(Modifier.heightIn(max = 330.dp)) { items(zones.filter { it.contains(query, true) || names[it]?.contains(query, true) == true }) { zone ->
                Column(Modifier.fillMaxWidth().clickable { select(zone) }.padding(vertical = 12.dp)) { Text(names[zone] ?: zone.substringAfterLast('/').replace('_', ' '), fontWeight = FontWeight.Medium); Text(zone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

@Composable private fun HistoryScreen(host: MainActivity, state: JSONObject, action: Action) {
    val records = Prefs(host).history().objects()
    var clear by remember { mutableStateOf(false) }
    SectionTitle("每一声，都有记录", "最近 200 条，仅保存在本机。")
    TextButton(onClick = { clear = true }) { Text("清空记录") }
    if (records.isEmpty()) Panel { Text("还没有记录"); Text("开启守候或做一次响铃测试后，可以在这里查看。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    records.forEach { entry -> Panel {
        Text(entry.optString("title"), fontWeight = FontWeight.Bold)
        Text(entry.optString("detail"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stamp(entry.optLong("at"), state.optString("zone", "device")), style = MaterialTheme.typography.labelSmall)
    } }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空运行记录？") }, text = { Text("不会影响时段、铃声和守候开关。") }, confirmButton = { TextButton(onClick = { action("clearHistory", JSONObject()); clear = false }) { Text("清空") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}
