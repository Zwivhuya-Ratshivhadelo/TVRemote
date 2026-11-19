package com.livingroom.home

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import com.livingroom.home.MenuDriver.Companion.find
import com.livingroom.home.MenuDriver.Companion.pressNamed
import com.livingroom.home.MenuDriver.Companion.all
import com.livingroom.home.MenuDriver.Companion.label
import com.livingroom.home.MenuDriver.Companion.type
import com.livingroom.home.MenuDriver.Step

/**
 * The phone's Picture, Wi-Fi and Bluetooth pages. Sends the phone what the TV has ("tvset") and carries out
 * what you pick. Wi-Fi and Bluetooth are read straight from Android; joining a network, connecting a device and
 * the picture settings are done in the TV's own menus by [MenuDriver].
 */
class TvSettings(private val svc: HomeKeyService, private val send: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val driver = MenuDriver(svc)
    private val prefs = svc.getSharedPreferences("tvset", Context.MODE_PRIVATE)
    private val wifi = svc.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val bt: BluetoothAdapter? = (svc.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val proxies = HashMap<Int, BluetoothProfile>()

    fun start() { watchProfiles() }

    /** The phone opened one of the pages: fresh news, and a Wi-Fi scan. */
    fun get(page: String) {
        try { @Suppress("DEPRECATION") wifi.startScan() } catch (e: Exception) { }
        if (page == "bluetooth" && !btAllowed()) askBt()
        publish()
        main.postDelayed({ publish() }, 4000)
        // The picture menu is only read when you open Picture on the phone, and only until it has been read once.
        if (page == "picture" && !prefs.contains("modes")) main.postDelayed({ readPicture() }, 800)
    }

    fun publish() {
        val o = JSONObject()
        try { o.put("wifi", wifiJson()) } catch (e: Exception) { Log.w(TAG, "wifi", e) }
        try { o.put("bt", btJson()) } catch (e: Exception) { Log.w(TAG, "bt", e) }
        pictureJson()?.let { o.put("picture", it) }
        MenuDriver.lastDump?.let { o.put("debug", it) }
        permDump?.let { o.put("debugPerm", it) }
        send(o.toString())
    }

    // ------------------------------------------------------------------------------- Wi-Fi

    private fun wifiJson(): JSONObject {
        val o = JSONObject()
        @Suppress("DEPRECATION") val info = wifi.connectionInfo
        val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
        o.put("ssid", ssid)
        if (ssid.isNotEmpty()) {
            @Suppress("DEPRECATION") val level = WifiManager.calculateSignalLevel(info.rssi, 4)
            o.put("signal", when (level) { 3 -> "Strong"; 2 -> "Good"; 1 -> "Fair"; else -> "Weak" })
        }
        val nets = JSONArray()
        @Suppress("DEPRECATION")
        val results = try { wifi.scanResults } catch (e: Exception) { emptyList() }
        results.filter { !it.SSID.isNullOrBlank() && it.SSID != ssid }.sortedByDescending { it.level }.distinctBy { it.SSID }.take(12).forEach { r ->
            val caps = r.capabilities ?: ""
            nets.put(JSONObject().put("ssid", r.SSID).put("lock", listOf("WPA", "WEP", "SAE", "PSK", "EAP").any { caps.contains(it) }))
        }
        o.put("nets", nets)
        return o
    }

    fun joinWifi(ssid: String, pass: String) {
        if (ssid.isBlank()) return
        val steps = ArrayList<Step>()
        steps += Step("find $ssid", 12_000) { pressNamed(it, ssid, scroll = true) }
        if (pass.isNotEmpty()) steps += Step("password", 8000) { type(it, pass) }
        // Some TVs want "Connect" pressed as well; if there is none, it's already connecting.
        var at = 0L
        steps += Step("connect", 8000) { r ->
            if (at == 0L) at = android.os.SystemClock.uptimeMillis()
            pressNamed(r, "Connect") || android.os.SystemClock.uptimeMillis() - at > 2500
        }
        steps += MenuDriver.pause(3000)
        driver.run(Intent(Settings.ACTION_WIFI_SETTINGS), steps) { main.postDelayed({ publish() }, 6000) }
    }

    // ------------------------------------------------------------------------------- Bluetooth

    private fun btAllowed() = android.os.Build.VERSION.SDK_INT < 31 ||
        svc.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Asks Android once for Bluetooth access; the helper presses Allow. */
    private fun askBt() {
        if (android.os.Build.VERSION.SDK_INT < 31 || askingBt) return
        askingBt = true; askedAt = System.currentTimeMillis()
        try { svc.startActivity(Intent(svc, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { askingBt = false }
        // What Android's question looked like (sent along for checking), then carry on.
        main.postDelayed({ permDump = svc.rootInActiveWindow?.let { r -> JSONObject().put("pkg", r.packageName?.toString() ?: "")
            .put("texts", JSONArray(MenuDriver.texts(r).distinct().take(40))) } }, 2500)
        main.postDelayed({ askingBt = false; watchProfiles(); publish() }, 8000)
    }

    private fun watchProfiles() {
        val a = bt ?: return
        if (!btAllowed()) return
        for (p in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 4 /* input devices: remotes, keyboards */)) {
            if (proxies.containsKey(p)) continue
            try {
                a.getProfileProxy(svc, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { proxies[profile] = proxy }
                    override fun onServiceDisconnected(profile: Int) { proxies.remove(profile) }
                }, p)
            } catch (e: Exception) { }
        }
    }

    @Suppress("MissingPermission")
    private fun btJson(): JSONObject {
        val a = bt ?: return JSONObject().put("on", false)
        val o = JSONObject().put("on", try { a.isEnabled } catch (e: Exception) { false })
        if (!btAllowed()) return o
        val connected = HashSet<String>()
        for (p in proxies.values) try { p.connectedDevices.forEach { connected += it.address } } catch (e: Exception) { }
        val devs = JSONArray()
        for (d in a.bondedDevices ?: emptySet<BluetoothDevice>()) {
            devs.put(JSONObject().put("name", d.name ?: d.address).put("addr", d.address).put("conn", d.address in connected).put("kind", kind(d)))
        }
        o.put("devices", devs)
        return o
    }

    @Suppress("MissingPermission")
    private fun kind(d: BluetoothDevice): String = when (d.bluetoothClass?.majorDeviceClass) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> if (d.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
            d.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES) "Headphones" else "Speaker"
        BluetoothClass.Device.Major.PERIPHERAL -> "Remote"
        BluetoothClass.Device.Major.PHONE -> "Phone"
        else -> ""
    }

    @Suppress("MissingPermission", "DEPRECATION")
    fun btPower(on: Boolean) {
        val a = bt ?: return
        if (!btAllowed()) { askBt(); return }
        try { if (on) a.enable() else a.disable() } catch (e: Exception) { Log.w(TAG, "bt power", e) }
        main.postDelayed({ watchProfiles(); publish() }, 2500)
    }

    @Suppress("MissingPermission")
    fun btConnect(addr: String) {
        val a = bt ?: return
        if (!btAllowed()) { askBt(); return }
        val name = try { a.getRemoteDevice(addr).name } catch (e: Exception) { null } ?: return
        val steps = listOf(
            Step("find $name", 12_000) { pressNamed(it, name, scroll = true) },
            Step("connect", 8000) { pressNamed(it, "Connect") },
            MenuDriver.pause(3000))
        driver.run(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), steps) { main.postDelayed({ publish() }, 3000) }
    }

    // ------------------------------------------------------------------------------- Picture

    private fun pictureJson(): JSONObject? {
        val modes = prefs.getString("modes", null)?.split('\n')?.filter { it.isNotBlank() } ?: return null
        val o = JSONObject().put("modes", JSONArray(modes)).put("mode", prefs.getString("mode", ""))
        if (prefs.contains("backlight")) o.put("backlight", prefs.getInt("backlight", 50))
        return o
    }

    /** The way into the picture menu: Settings > Display & Sound (or similar) > Picture. */
    private fun toPicture() = listOf(
        Step("picture", 9000) { r -> pressNamed(r, "Picture") || pressNamed(r, "Picture settings", loose = true) })

    /** Reads the TV's picture modes (and the backlight) once, so the phone can show them. */
    fun readPicture() {
        if (driver.busy) return
        val steps = ArrayList<Step>(toPicture())
        steps += Step("picture mode", 9000) { r -> pressNamed(r, "Picture mode", loose = true) }
        steps += Step("read modes", 6000) { r ->
            val items = all(r).filter { it.isVisibleToUser && it.isCheckable }
            val names = items.map { itemName(it) }.filter { it.isNotEmpty() }.distinct()
            if (names.size < 2) return@Step false
            val cur = items.firstOrNull { it.isChecked }?.let { itemName(it) } ?: ""
            prefs.edit().putString("modes", names.joinToString("\n")).putString("mode", cur).apply()
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            true
        }
        steps += Step("backlight", 6000) { r -> pressNamed(r, "Backlight", loose = true) }
        steps += Step("read backlight", 5000) { r ->
            val bar = all(r).firstOrNull { it.rangeInfo != null } ?: return@Step false
            val ri = bar.rangeInfo
            val pct = if (ri.max > ri.min) ((ri.current - ri.min) * 100f / (ri.max - ri.min)).toInt() else 50
            prefs.edit().putInt("backlight", pct).apply(); true
        }
        driver.run(Intent(Settings.ACTION_DISPLAY_SETTINGS), steps) { publish() }
    }

    fun pictureMode(mode: String) {
        if (mode.isBlank()) return
        val steps = ArrayList<Step>(toPicture())
        steps += Step("picture mode", 9000) { r -> pressNamed(r, "Picture mode", loose = true) }
        steps += Step("pick $mode", 6000) { r -> pressNamed(r, mode).also { if (it) prefs.edit().putString("mode", mode).apply() } }
        steps += MenuDriver.pause(800)
        driver.run(Intent(Settings.ACTION_DISPLAY_SETTINGS), steps) { publish() }
    }

    fun backlight(v: Int) {
        val steps = ArrayList<Step>(toPicture())
        steps += Step("backlight", 9000) { r -> pressNamed(r, "Backlight", loose = true) }
        steps += Step("set backlight", 5000) { r ->
            val bar = all(r).firstOrNull { it.rangeInfo != null } ?: return@Step false
            val ri = bar.rangeInfo
            val value = ri.min + (ri.max - ri.min) * v.coerceIn(0, 100) / 100f
            val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
            bar.performAction(android.R.id.accessibilityActionSetProgress, args).also { if (it) prefs.edit().putInt("backlight", v).apply() }
        }
        steps += MenuDriver.pause(600)
        driver.run(Intent(Settings.ACTION_DISPLAY_SETTINGS), steps) { publish() }
    }

    /** The name of a list item: its own text, or the first text inside it. */
    private fun itemName(n: AccessibilityNodeInfo): String {
        label(n).takeIf { it.isNotEmpty() }?.let { return it }
        return all(n).map { label(it) }.firstOrNull { it.isNotEmpty() } ?: ""
    }

    fun stop() { for ((p, proxy) in proxies) try { bt?.closeProfileProxy(p, proxy) } catch (e: Exception) { } }

    companion object {
        private const val TAG = "LivingRoomSettings"
        /** While Android's "Allow Bluetooth?" question is ours, the helper presses Allow. */
        @Volatile var askingBt = false
        @Volatile var permDump: JSONObject? = null
        @Volatile var askedAt = 0L
    }
}
