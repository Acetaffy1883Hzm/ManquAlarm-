package dev.hazel.livealarm

import android.os.Bundle
import org.json.JSONObject
import androidx.activity.compose.setContent
import androidx.compose.runtime.*

open class MainActivity : NativeHostActivity() {
    private var snapshot by mutableStateOf(JSONObject())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        snapshot = readNativeState()
        setContent { NativeTheme(snapshot.optJSONObject("config") ?: JSONObject()) { NativeApp(this, snapshot, alarmPage()) } }
    }
    override fun onNativeState(value: JSONObject) { snapshot = value }
}
