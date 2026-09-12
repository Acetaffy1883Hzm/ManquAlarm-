package dev.hazel.livealarm

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal fun parseColor(hex: String): Color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color(0xFFA65C83) }
internal fun inkFor(color: Color) = if (color.luminance() > 0.179f) Color.Black else Color.White

@Composable
internal fun NativeTheme(config: JSONObject, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (config.optString("theme", "light")) { "dark" -> true; "system" -> isSystemInDarkTheme(); else -> false }
    val seed = parseColor(config.optString("seedColor", "#A65C83"))
    var scheme = if (config.optBoolean("dynamicColor") && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        val primary = lerp(seed, Color.White, .5f)
        darkColorScheme(primary = primary, onPrimary = inkFor(primary), primaryContainer = lerp(seed, Color.Black, .65f), onPrimaryContainer = lerp(seed, Color.White, .85f),
            secondary = lerp(seed, Color.White, .6f), secondaryContainer = lerp(seed, Color(0xFF222126), .7f), onSecondaryContainer = Color(0xFFF8EDF4),
            background = lerp(seed, Color(0xFF101014), .94f), surface = lerp(seed, Color(0xFF19181D), .94f), surfaceVariant = lerp(seed, Color(0xFF302D34), .85f),
            onBackground = Color(0xFFF0EAF0), onSurface = Color(0xFFF0EAF0), onSurfaceVariant = Color(0xFFCAC1CC), outline = Color(0xFF938895))
    } else {
        val primary = if (seed.luminance() > .45f) lerp(seed, Color.Black, .38f) else seed
        lightColorScheme(primary = primary, onPrimary = inkFor(primary), primaryContainer = lerp(seed, Color.White, .86f), onPrimaryContainer = lerp(seed, Color.Black, .7f),
            secondary = lerp(seed, Color(0xFF655D68), .55f), secondaryContainer = lerp(seed, Color.White, .91f), onSecondaryContainer = Color(0xFF302632),
            background = lerp(seed, Color(0xFFFEFCFF), .96f), surface = lerp(seed, Color.White, .98f), surfaceVariant = lerp(seed, Color.White, .92f),
            onBackground = Color(0xFF241D26), onSurface = Color(0xFF241D26), onSurfaceVariant = Color(0xFF665B68), outline = Color(0xFF928593))
    }
    if (dark && config.optBoolean("amoled")) scheme = scheme.copy(background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF161316))
    MaterialTheme(colorScheme = scheme, shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp)), content = content)
}
