package com.livingroom.home

import android.content.Context
import android.graphics.Color
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TAG = "LivingRoomHome"

// ---------------------------------------------------------------------------------------------
// The fixed dock. New apps installed later are NOT added.
// ---------------------------------------------------------------------------------------------

enum class SlotKind { APP, WEB, INPUTS, SETTINGS }

data class Slot(val key: String, val label: String, val kind: SlotKind, val packages: List<String> = emptyList(), val url: String? = null, val store: String? = null)

object Slots {
    val all = listOf(
        Slot("netflix", "Netflix", SlotKind.APP, listOf("com.netflix.ninja")),
        Slot("youtube", "YouTube", SlotKind.APP, listOf("com.google.android.youtube.tv")),
        Slot("prime", "Prime Video", SlotKind.APP, listOf("com.amazon.amazonvideo.livingroom")),
        // Always shown: opens the Play Store at DStv until the app is installed.
        Slot("dstv", "DStv", SlotKind.APP, listOf("com.dstvmobile.android"), store = "com.dstvmobile.android"),
        Slot("spotify", "Spotify", SlotKind.APP, listOf("com.spotify.tv.android")),
        // SABC+ opens its app when installed, otherwise the SABC+ website in the browser.
        Slot("sabc", "SABC+", SlotKind.APP, listOf("tv.sabcplus.vod"), url = "https://sabc-plus.com/"),
        Slot("chrome", "Chrome", SlotKind.APP, listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")),
        Slot("goojara", "Goojara", SlotKind.WEB, url = "https://ww1.goojara.to/")
    )
}

data class Entry(val slot: Slot, val pkg: String?)

/** Which apps are on the home screen, and in which order. Changed from the phone remote. */
object HomeList {
    private const val KEY = "home_list"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun idOf(e: Entry): String = e.slot.key

    fun get(ctx: Context): List<String>? =
        prefs(ctx).getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }

    private fun save(ctx: Context, list: List<String>) { prefs(ctx).edit().putString(KEY, list.joinToString("\n")).apply() }

    fun add(ctx: Context, id: String) {
        if (id.isBlank()) return
        val list = (get(ctx) ?: Apps.standard(ctx).map { it.slot.key }).toMutableList()
        if (id !in list) { list += id; save(ctx, list) }
    }

    fun remove(ctx: Context, id: String) {
        val list = (get(ctx) ?: Apps.standard(ctx).map { it.slot.key }).toMutableList()
        if (list.remove(id)) save(ctx, list)
    }

    /** Bubble look for apps that have no icon on the TV (websites, or not installed yet). */
    fun letterArt(v: BubbleView, key: String, label: String) {
        when (key) {
            "goojara" -> { v.bg = Color.rgb(23, 23, 28); v.letter = "G"; v.letterColor = Color.rgb(245, 197, 24) }
            "sabc" -> { v.bg = Color.rgb(0x00, 0x60, 0xAF); v.letter = "SABC+"; v.letterColor = Color.WHITE }
            "dstv" -> { v.bg = Color.rgb(0, 51, 161); v.letter = "DStv"; v.letterColor = Color.WHITE }
            else -> { v.letter = label.take(1); v.letterColor = Color.WHITE }
        }
    }
}

object Apps {

    /** The apps on the home screen: the list set from the phone remote, or the standard ones. */
    fun resolve(ctx: Context): List<Entry> {
        val standard = standard(ctx)
        val custom = HomeList.get(ctx) ?: return standard
        val byKey = standard.associateBy { it.slot.key }
        val pm = ctx.packageManager
        return custom.mapNotNull { id ->
            byKey[id] ?: if (Slots.all.any { it.key == id }) null
            else if (isLaunchable(ctx, id)) {
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(id, 0)).toString() } catch (e: Exception) { id }
                Entry(Slot(id, label, SlotKind.APP, listOf(id)), id)
            } else null
        }
    }

    fun standard(ctx: Context): List<Entry> {
        val pm = ctx.packageManager
        val launchables by lazy {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        }
        return Slots.all.mapNotNull { slot ->
            when (slot.kind) {
                SlotKind.APP -> {
                    val pkg = slot.packages.firstOrNull { isLaunchable(ctx, it) }
                        ?: launchables.firstOrNull { it.loadLabel(pm).toString().trim().let { l -> l.equals(slot.label, true) || l.startsWith(slot.label + " ", true) } }?.activityInfo?.packageName
                    pkg?.let { Entry(slot, it) } ?: (if (slot.url != null || slot.store != null) Entry(slot, null) else null)
                }
                else -> Entry(slot, null)
            }
        }
    }

    fun isLaunchable(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0).enabled && launchIntent(ctx, pkg) != null
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun launchIntent(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The app's own TV tile artwork (the same one Google TV uses), or null. */
    fun banner(ctx: Context, pkg: String): Drawable? {
        val pm = ctx.packageManager
        return try {
            pm.getLeanbackLaunchIntentForPackage(pkg)?.component?.let { pm.getActivityBanner(it) }
                ?: pm.getApplicationBanner(pkg)
        } catch (e: Exception) { null }
    }

    fun icon(ctx: Context, pkg: String): Drawable? = try { ctx.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }

    /** Opens a web address in Chrome if it is installed, otherwise in any browser. */
    fun openUrl(ctx: Context, url: String, preferPkg: String? = null): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (preferPkg != null) {
            try { ctx.startActivity(Intent(view).setPackage(preferPkg)); return true } catch (e: Exception) { }
        }
        for (p in Slots.all.first { it.key == "chrome" }.packages) {
            try { ctx.startActivity(Intent(view).setPackage(p)); return true } catch (e: Exception) { }
        }
        return try { ctx.startActivity(view); true } catch (e: Exception) { false }
    }

    /** Finds an installed app whose name contains the given text (for example "SABC"). */
    fun findByName(ctx: Context, part: String): String? {
        val pm = ctx.packageManager
        val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return all.firstOrNull { it.loadLabel(pm).toString().contains(part, true) }?.activityInfo?.packageName
    }
}

// ---------------------------------------------------------------------------------------------
// Picks shown above Netflix and YouTube
// ---------------------------------------------------------------------------------------------

data class Pick(val title: String, val meta: String, val pictureUri: String?, val open: String?)

object Feeds {

    private const val YT_PREFS = "youtube"
    val CHANNELS = listOf(
        "Jabulile Mahlobo" to "UCJ9YPex-IAD8ujnl23HJDTw",
        "CVK Instrumentals" to "UCMu8BlEpJ02Iv0PENtgxRUg"
    )

    /** Netflix's own picks, from the rows Netflix shares with the TV. Empty if it shares none. */
    fun netflix(ctx: Context): List<Pick> {
        val out = mutableListOf<Pick>()
        try {
            ctx.contentResolver.query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)?.use { c ->
                fun s(col: String) = c.getColumnIndex(col).let { if (it >= 0 && !c.isNull(it)) c.getString(it) else null }
                while (c.moveToNext() && out.size < 4) {
                    if (s("package_name") != "com.netflix.ninja") continue
                    val title = s("title") ?: continue
                    out += Pick(title, s("genre") ?: "", s("poster_art_uri") ?: s("thumbnail_uri"), s("intent_uri"))
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Netflix picks not readable: ${e.message}")
        }
        return out
    }

    /** Newest two videos from each channel. Cached so they still show offline. */
    fun youtubeCached(ctx: Context): List<Pick> {
        val json = ctx.getSharedPreferences(YT_PREFS, Context.MODE_PRIVATE).getString("picks2", null) ?: return emptyList()
        return try {
            val a = JSONArray(json)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Pick(o.getString("t"), o.getString("m"), if (o.has("p")) o.getString("p") else null, if (o.has("o")) o.getString("o") else null)
            }
        } catch (e: Exception) { emptyList() }
    }

    fun youtubeFetchedAt(ctx: Context): Long = ctx.getSharedPreferences(YT_PREFS, Context.MODE_PRIVATE).getLong("at2", 0L)

    /** Blocking; call off the main thread. */
    fun youtubeFetch(ctx: Context): List<Pick>? {
        val per = mutableListOf<List<Pick>>()
        for ((name, id) in CHANNELS) {
            val conn = (URL("https://www.youtube.com/feeds/videos.xml?channel_id=$id").openConnection() as HttpURLConnection)
                .apply { connectTimeout = 8000; readTimeout = 10000 }
            try {
                if (conn.responseCode != 200) return null
                per.add(conn.inputStream.use { parseFeed(it, name) }.take(2))
            } catch (e: Exception) {
                Log.i(TAG, "YouTube feed not loaded: ${e.message}")
                return null
            } finally { conn.disconnect() }
        }
        val mixed = mutableListOf<Pick>()
        for (list in per) list.firstOrNull()?.let { mixed += it }
        val a = JSONArray()
        for (p in mixed) a.put(JSONObject().put("t", p.title).put("m", p.meta).put("p", p.pictureUri).put("o", p.open))
        ctx.getSharedPreferences(YT_PREFS, Context.MODE_PRIVATE).edit()
            .putString("picks2", a.toString()).putLong("at2", System.currentTimeMillis()).apply()
        return mixed
    }

    private fun age(iso: String?): String {
        if (iso == null) return ""
        return try {
            val t = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.UK).parse(iso)!!.time
            val days = ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
            when { days <= 0 -> " · Today"; days == 1 -> " · Yesterday"; days < 7 -> " · $days days ago"; days < 14 -> " · 1 week ago"; else -> " · ${days / 7} weeks ago" }
        } catch (e: Exception) { "" }
    }

    private fun parseFeed(input: java.io.InputStream, channel: String): List<Pick> {
        val out = mutableListOf<Pick>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var inEntry = false
        var id: String? = null; var title: String? = null; var thumb: String? = null; var published: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "entry" -> { inEntry = true; id = null; title = null; thumb = null; published = null }
                    "published" -> if (inEntry) published = p.nextText()
                    "yt:videoId" -> if (inEntry) id = p.nextText()
                    "title" -> if (inEntry && title == null) title = p.nextText()
                    "media:thumbnail" -> if (inEntry) thumb = p.getAttributeValue(null, "url")
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "entry") {
                inEntry = false
                if (id != null && title != null) {
                    out += Pick(title, channel + age(published), "https://i.ytimg.com/vi/$id/hqdefault.jpg", "https://www.youtube.com/watch?v=$id")
                }
            }
            ev = p.next()
        }
        return out
    }
}

/** Keeps pictures on the TV so they still show without internet. */
object Pictures {

    private const val MAX_FILES = 40

    fun load(ctx: Context, uri: String, online: Boolean): Bitmap? {
        val dir = File(ctx.filesDir, "pictures").apply { mkdirs() }
        val file = File(dir, sha1(uri))
        if (!file.exists()) {
            val ok = try {
                val scheme = Uri.parse(uri).scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    if (!online) return null
                    download(uri, file)
                } else {
                    ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
                }
            } catch (e: Exception) { file.delete(); false }
            if (!ok) return null
            prune(dir)
        }
        file.setLastModified(System.currentTimeMillis())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 440) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun download(url: String, out: File): Boolean {
        val tmp = File(out.path + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000 }
        try {
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return tmp.renameTo(out)
        } finally { conn.disconnect(); tmp.delete() }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { !it.name.endsWith(".part") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

// ---------------------------------------------------------------------------------------------
// Weather for Sasolburg (Open-Meteo, free, no account). Kept for offline use.
// ---------------------------------------------------------------------------------------------

data class DayForecast(val day: String, val code: Int, val high: Int, val low: Int)

/** One hour of the forecast: "21:00", weather code, temperature, chance of rain. */
data class HourForecast(val at: Long, val label: String, val code: Int, val temp: Int, val rainChance: Int, val isDay: Boolean)

data class WeatherNow(
    val temp: Int, val feels: Int, val humidity: Int, val code: Int, val isDay: Boolean,
    val wind: Int, val rainMm: Float, val high: Int, val low: Int, val rainChance: Int,
    val days: List<DayForecast>, val fetchedAt: Long,
    val hours: List<HourForecast> = emptyList()
) {
    /** The next hours from now (old saved forecasts skip the hours already gone). */
    fun nextHours(n: Int = 6): List<HourForecast> {
        val now = System.currentTimeMillis()
        return hours.filter { it.at > now }.take(n)
    }
}

object Weather {

    private const val PREFS = "weather"
    private val url = "https://api.open-meteo.com/v1/forecast?latitude=-26.8136&longitude=27.8165" +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m,precipitation" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=Africa%2FJohannesburg&forecast_days=6"

    fun cached(ctx: Context): WeatherNow? {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last6", null) ?: return null
        return try { parse(JSONObject(json)) } catch (e: Exception) { null }
    }

    fun fetch(ctx: Context): WeatherNow? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000 }
        return try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).put("fetched_at", System.currentTimeMillis())
            val now = parse(json)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last6", json.toString()).apply()
            now
        } catch (e: Exception) {
            Log.i(TAG, "Weather not updated: ${e.message}"); null
        } finally { conn.disconnect() }
    }

    private fun parse(json: JSONObject): WeatherNow {
        val cur = json.getJSONObject("current")
        val d = json.getJSONObject("daily")
        val dates = d.getJSONArray("time")
        val codes = d.getJSONArray("weather_code")
        val hi = d.getJSONArray("temperature_2m_max")
        val lo = d.getJSONArray("temperature_2m_min")
        val pp = d.optJSONArray("precipitation_probability_max")
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
        val outFmt = SimpleDateFormat("EEE", Locale.UK)
        val today = inFmt.format(Calendar.getInstance().time)
        // Skip days that are already over (cached data) so the 5-day row always starts tomorrow.
        val start = (0 until dates.length()).firstOrNull { dates.getString(it) >= today } ?: 0
        val days = (start + 1 until dates.length()).take(5).map { i ->
            DayForecast(
                outFmt.format(inFmt.parse(dates.getString(i))!!), codes.getInt(i),
                Math.round(hi.getDouble(i)).toInt(), Math.round(lo.getDouble(i)).toInt()
            )
        }
        val hours = mutableListOf<HourForecast>()
        json.optJSONObject("hourly")?.let { hr ->
            val ht = hr.getJSONArray("time"); val hc = hr.getJSONArray("weather_code"); val htemp = hr.getJSONArray("temperature_2m")
            val hp = hr.optJSONArray("precipitation_probability"); val hd = hr.optJSONArray("is_day")
            val hFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.UK).apply { timeZone = java.util.TimeZone.getTimeZone("Africa/Johannesburg") }
            for (i in 0 until ht.length()) {
                val time = ht.getString(i)
                val at = try { hFmt.parse(time)!!.time } catch (e: Exception) { continue }
                hours += HourForecast(at, time.substringAfter('T'), hc.optInt(i, 3), Math.round(htemp.optDouble(i, 0.0)).toInt(),
                    hp?.optInt(i, 0) ?: 0, (hd?.optInt(i, 1) ?: 1) == 1)
            }
        }
        return WeatherNow(
            temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            feels = Math.round(cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m"))).toInt(),
            humidity = cur.optInt("relative_humidity_2m", 0),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            wind = Math.round(cur.optDouble("wind_speed_10m", 0.0)).toInt(),
            rainMm = cur.optDouble("precipitation", 0.0).toFloat(),
            high = Math.round(hi.getDouble(start)).toInt(),
            low = Math.round(lo.getDouble(start)).toInt(),
            rainChance = pp?.optInt(start, 0) ?: 0,
            days = days,
            fetchedAt = json.optLong("fetched_at", 0L),
            hours = hours
        )
    }

    /** clear, partly cloudy, cloudy, fog, rain, storm */
    fun kind(code: Int): String = when (code) {
        0, 1 -> "clear"
        2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "fog"
        95, 96, 99 -> "storm"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 71, 73, 75, 77, 85, 86 -> "rain"
        else -> "cloudy"
    }

    /** "Thunderstorms likely from 21:00 · Low 17°", or null when nothing is coming. */
    fun headsUp(w: WeatherNow): String? {
        val next = w.nextHours(6)
        val storm = next.firstOrNull { kind(it.code) == "storm" }
        val rain = next.firstOrNull { kind(it.code) == "rain" && it.rainChance >= 40 }
        val low = " · Low ${w.low}°"
        return when {
            storm != null && kind(code(w)) != "storm" -> "Thunderstorms likely from ${storm.label}$low"
            rain != null && kind(code(w)) !in setOf("rain", "storm") -> "Rain likely from ${rain.label}$low"
            else -> null
        }
    }

    /** If rain is actually being measured, treat it as rain even when the weather type says cloudy. */
    fun code(w: WeatherNow): Int =
        if (w.rainMm >= 0.1f && kind(w.code) in setOf("clear", "partly cloudy", "cloudy", "fog")) 61 else w.code

    fun label(w: WeatherNow): String = when (kind(code(w))) {
        "clear" -> "Clear"
        "partly cloudy" -> "Partly cloudy"
        "cloudy" -> "Cloudy"
        "fog" -> "Fog"
        "storm" -> "Thunderstorms"
        else -> when { w.rainMm >= 4f -> "Heavy rain"; w.rainMm < 1f -> "Light rain"; else -> "Rain showers" }
    }

    /** Which colour icon WeatherIconView draws. */
    fun iconKind(code: Int, isDay: Boolean): String = when (kind(code)) {
        "clear" -> if (isDay) "sun" else "moon"
        "partly cloudy" -> "partly"
        "fog" -> "fog"
        "storm" -> "storm"
        "rain" -> "rain"
        else -> "cloud"
    }

    fun icon(code: Int, isDay: Boolean): Int = when (kind(code)) {
        "clear" -> if (isDay) R.drawable.ic_wx_sun else R.drawable.ic_wx_moon
        "partly cloudy" -> R.drawable.ic_wx_partly
        "fog" -> R.drawable.ic_wx_fog
        "storm" -> R.drawable.ic_wx_storm
        "rain" -> R.drawable.ic_wx_rain
        else -> R.drawable.ic_wx_cloud
    }
}

// ---------------------------------------------------------------------------------------------
// Skeem Saam every day at 19:30
// ---------------------------------------------------------------------------------------------

object Skeem {
    const val URL = "https://sabc-plus.com/show/229734/Skeem-Saam"

    fun open(ctx: Context) {
        val sabc = Apps.findByName(ctx, "SABC")
        if (sabc != null && Apps.openUrl(ctx, URL, sabc)) return
        if (sabc != null) Apps.launchIntent(ctx, sabc)?.let { try { ctx.startActivity(it); return } catch (e: Exception) { } }
        Apps.openUrl(ctx, URL)
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1000) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, 1930, Intent(ctx, SkeemReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val exactOk = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next, pi)
        else am.setWindow(android.app.AlarmManager.RTC_WAKEUP, next, 60_000L, pi)
    }
}

class SkeemReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Skeem.schedule(ctx)
            // Google's home comes up first at start-up; open ours on top of it.
            try { ctx.startActivity(Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { Log.w(TAG, "Home screen not opened at start-up", e) }
            return
        }
        Skeem.schedule(ctx) // tomorrow
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isInteractive) return // the TV is off: Android does not let apps switch it on
        try {
            ctx.startActivity(Intent(ctx, SkeemActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) { Log.w(TAG, "Skeem Saam card not shown", e) }
    }
}

