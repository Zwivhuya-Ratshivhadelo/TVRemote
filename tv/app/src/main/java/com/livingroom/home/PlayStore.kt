package com.livingroom.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Apps you don't have yet, from Google Play.
 *  - search: looks the name up on Google Play and sends back the apps with their icons.
 *  - install: opens that app in the TV's Play Store and presses Install; once it is on the TV it goes on the home screen.
 */
class PlayStore(private val svc: HomeKeyService, private val send: (topic: String, json: String) -> Unit, private val onInstalled: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(6)
    @Volatile private var lastQuery = ""
    private var pending: String? = null
    private var pendingAt = 0L
    private var pressed = false

    fun start() {
        try {
            svc.registerReceiver(added, IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") })
        } catch (e: Exception) { Log.w(TAG, "install watch off", e) }
    }

    fun stop() { try { svc.unregisterReceiver(added) } catch (e: Exception) { } }

    // ------------------------------------------------------------------------------- search

    fun search(q: String) {
        val query = q.trim(); lastQuery = query
        if (query.length < 2) return
        pool.execute {
            try {
                val html = get("https://play.google.com/store/search?q=" + URLEncoder.encode(query, "UTF-8") + "&c=apps&hl=en&gl=ZA")
                val ids = Regex("""/store/apps/details\?id=([A-Za-z0-9_.]+)""").findAll(html).map { it.groupValues[1] }
                    .distinct().filter { !isInstalled(it) }.take(12).toList()
                val jobs = ids.map { id -> pool.submit(Callable { details(id) }) }
                val arr = JSONArray()
                for (j in jobs) try { j.get(15, TimeUnit.SECONDS)?.let { arr.put(it) } } catch (e: Exception) { }
                if (query == lastQuery) send("found", JSONObject().put("q", query).put("apps", arr).toString())
            } catch (e: Exception) {
                Log.w(TAG, "play search failed", e)
                if (query == lastQuery) send("found", JSONObject().put("q", query).put("apps", JSONArray()).put("err", true).toString())
            }
        }
    }

    private fun details(id: String): JSONObject? {
        val html = get("https://play.google.com/store/apps/details?id=$id&hl=en&gl=ZA")
        fun meta(p: String) = Regex("""<meta property="$p" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        val title = meta("og:title")?.let { unescape(it).removeSuffix(" - Apps on Google Play").trim() } ?: return null
        val o = JSONObject().put("id", id).put("label", title)
        meta("og:image")?.let { img ->
            try {
                val bytes = getBytes(unescape(img).substringBefore("=") + "=s96")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { b ->
                    val bmp = Bitmap.createScaledBitmap(b, 96, 96, true)
                    val out = ByteArrayOutputStream()
                    @Suppress("DEPRECATION") bmp.compress(Bitmap.CompressFormat.WEBP, 82, out)
                    o.put("icon", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)).put("color", RemoteLink.averageColor(bmp))
                }
            } catch (e: Exception) { }
        }
        return o
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    private fun open(u: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 10000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
        setRequestProperty("Accept-Language", "en")
    }
    private fun get(u: String) = open(u).inputStream.use { String(it.readBytes()) }
    private fun getBytes(u: String) = open(u).inputStream.use { it.readBytes() }

    private fun isInstalled(p: String) = try { svc.packageManager.getApplicationInfo(p, 0); true } catch (e: Exception) { false }

    // ------------------------------------------------------------------------------- install

    fun install(id: String) {
        if (id.isBlank()) return
        if (isInstalled(id)) { HomeList.add(svc, id); onInstalled(); status(id, "done"); return }
        pending = id; pendingAt = System.currentTimeMillis(); pressed = false
        status(id, "installing")
        try {
            svc.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
                .setPackage(VENDING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) { pending = null; status(id, "fail"); return }
        // If the Play Store never offers Install (the app is not made for TV), say so.
        main.postDelayed({ if (pending == id && !pressed) { pending = null; status(id, "notv"); svc.openHome(force = true) } }, 25_000)
    }

    /** Called with every Play Store screen change while an install is waiting: presses its Install button. */
    fun onStoreScreen() {
        val id = pending ?: return
        if (pressed || System.currentTimeMillis() - pendingAt > 25_000) return
        val root = svc.rootInActiveWindow ?: return
        val btn = find(root) { n ->
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: ""
            t == "install" || t == "get" || t == "install on tv"
        } ?: return
        var c: AccessibilityNodeInfo? = btn
        while (c != null && !c.isClickable) c = c.parent
        if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            pressed = true
            // Downloads keep going in the background; back to our home screen.
            main.postDelayed({ svc.openHome(force = true) }, 2500)
            main.postDelayed({ if (pending == id) { pending = null; status(id, "fail") } }, 10 * 60_000)
        }
    }

    private fun find(n: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (test(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c, test)?.let { return it } }
        return null
    }

    private val added = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val p = i.data?.schemeSpecificPart ?: return
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            if (p == pending) pending = null
            // Anything newly installed from the phone goes straight on the home screen.
            if (Apps.launchIntent(svc, p) != null && p == lastInstalledFromPhone(p)) { HomeList.add(svc, p); status(p, "done") }
            onInstalled()
        }
    }

    private val askedFromPhone = HashSet<String>()
    private fun lastInstalledFromPhone(p: String): String? = if (askedFromPhone.remove(p)) p else null

    private fun status(id: String, st: String) {
        if (st == "installing") askedFromPhone += id
        send("inst", JSONObject().put("id", id).put("st", st).toString())
    }

    companion object {
        private const val TAG = "LivingRoomHome"
        const val VENDING = "com.android.vending"
    }
}
