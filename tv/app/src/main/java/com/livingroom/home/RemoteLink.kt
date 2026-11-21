package com.livingroom.home

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The TV end of the phone remote. Runs inside the HOME helper (always on), keeps a link to the
 * relay, carries out what the phone asks, and tells the phone what is on the TV.
 */
class RemoteLink(private val svc: HomeKeyService) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val seal: Seal? = svc.getString(R.string.link_key).trim().takeIf { it.length >= 16 }?.let { Seal(it) }
    private var mqtt: Mqtt? = null
    private val audio = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var app: String? = null          // package of the app open on the TV right now (null = home screen)
    private var controller: MediaController? = null
    private var artHash = ""
    private val seen = LinkedHashSet<String>()
    private val store = PlayStore(svc,
        send = { topic, json -> seal?.let { s -> mqtt?.publish(t(topic), s.seal(json.toByteArray())) } },
        onInstalled = { main.post { changed() } })

    fun onStoreScreen() = store.onStoreScreen()

    private val tvset = TvSettings(svc) { json -> seal?.let { s -> worker.execute { mqtt?.publish(t("tvset"), s.seal(json.toByteArray()), retain = true) } } }

    private fun t(s: String) = "${seal!!.base}/$s"

    fun start() {
        val s = seal ?: return
        mqtt = Mqtt("lrh-tv-" + java.util.UUID.randomUUID().toString().take(8),
            t("on") to "0".toByteArray(),
            onConnected = { m ->
                m.subscribe(t("cmd"))
                m.publish(t("on"), "1".toByteArray(), retain = true)
                main.post { publishState(); publishApps() }
            },
            onMessage = { topic, data -> if (topic == t("cmd")) s.open(data)?.let { main.post { handle(String(it)) } } },
            onLink = { })
        mqtt?.start()
        store.start()
        tvset.start()
        watchMedia()
        try {
            svc.registerReceiver(volume, IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply { addAction(HOME_LIST_CHANGED) })
        } catch (e: Exception) { }
    }

    fun stop() {
        store.stop()
        tvset.stop()
        try { svc.unregisterReceiver(volume) } catch (e: Exception) { }
        mqtt?.publish(t("on"), "0".toByteArray(), retain = true)
        mqtt?.stop()
    }

    private val volume = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == HOME_LIST_CHANGED) publishApps() else schedule()
        }
    }

    // ------------------------------------------------------------------------------- what is on the TV

    /** Whether a text box on the TV has focus (its keyboard is up), and what is in it. */
    private var kbd = false
    private var kbdText = ""
    private val kbdCheck = Runnable {
        val box = try { svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && it.isVisibleToUser } } catch (e: Exception) { null }
        val on = box != null
        val text = if (box != null && !(android.os.Build.VERSION.SDK_INT >= 26 && box.isShowingHintText)) box.text?.toString() ?: "" else ""
        if (on != kbd || (on && text != kbdText && !typing)) { kbd = on; kbdText = text; schedule() }
    }
    /** True for a moment after the phone typed, so the TV's echo doesn't overwrite what you're typing. */
    @Volatile private var typing = false
    private val doneTyping = Runnable { typing = false }

    fun checkKeyboard() { main.removeCallbacks(kbdCheck); main.postDelayed(kbdCheck, 250) }

    /** Called by the HOME helper whenever a different screen comes to the front. */
    fun onWindow(pkg: String) {
        val now = when {
            pkg == svc.packageName || pkg == GOOGLE_HOME -> null
            pkg in IGNORE -> return
            Apps.launchIntent(svc, pkg) != null -> pkg
            else -> return
        }
        if (now == app) return
        app = now
        pickController()
        schedule()
    }

    private fun watchMedia() {
        try {
            val msm = svc.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(svc, MediaWatch::class.java)
            msm.addOnActiveSessionsChangedListener({ pickController() }, cn, main)
        } catch (e: Exception) { Log.w(TAG, "media info unavailable (allow notification access)", e) }
    }

    private val mediaCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { schedule() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { schedule() }
        override fun onSessionDestroyed() { pickController() }
    }

    private fun pickController() {
        val list = try {
            (svc.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(svc, MediaWatch::class.java))
        } catch (e: Exception) { emptyList<MediaController>() }
        val pick = app?.let { a -> list.firstOrNull { it.packageName == a } }
        if (pick?.sessionToken != controller?.sessionToken) {
            try { controller?.unregisterCallback(mediaCb) } catch (e: Exception) { }
            controller = pick
            try { pick?.registerCallback(mediaCb, main) } catch (e: Exception) { }
        }
        schedule()
    }

    private val publishNow = Runnable { publishState() }
    private fun schedule() { main.removeCallbacks(publishNow); main.postDelayed(publishNow, 120) }

    private fun publishState() {
        val s = seal ?: return
        val o = JSONObject()
        o.put("t", System.currentTimeMillis())
        // Which build the TV runs, and how its last update went (read from GitHub when checking problems).
        o.put("v", Updater.versionOf(svc)).put("upd", Updater.last).put("dpad", HomeKeyService.lastDpad)
        if (kbd) o.put("kbd", JSONObject().put("on", true).put("text", kbdText))
        app?.let { a ->
            o.put("app", JSONObject().put("pkg", a).put("label", label(a)))
            val c = controller
            val md = c?.metadata
            val ps = c?.playbackState
            if (c != null && md != null) {
                val m = JSONObject()
                m.put("title", md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                m.put("artist", md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
                m.put("dur", md.getLong(MediaMetadata.METADATA_KEY_DURATION))
                if (ps != null) {
                    val playing = ps.state == PlaybackState.STATE_PLAYING
                    var pos = ps.position
                    if (playing && ps.lastPositionUpdateTime > 0) pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
                    m.put("pos", pos.coerceAtLeast(0)); m.put("playing", playing)
                }
                m.put("art", artFor(md))
                o.put("media", m)
            }
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        o.put("vol", JSONObject().put("l", audio.getStreamVolume(AudioManager.STREAM_MUSIC)).put("m", max)
            .put("mu", audio.isStreamMute(AudioManager.STREAM_MUSIC)))
        Weather.cached(svc)?.let { w -> o.put("wx", JSONObject().put("t", w.temp).put("label", Weather.label(w))) }
        val bytes = s.seal(o.toString().toByteArray())
        worker.execute { mqtt?.publish(t("state"), bytes, retain = true) }
    }

    /** Sends the song/video picture once per change; returns its id so the phone can match it. */
    private fun artFor(md: MediaMetadata): String {
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = md.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val id = (if (bmp != null) "b${bmp.width}x${bmp.height}:${bmp.generationId}:" else "u:") + (uri ?: "") +
            (md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
        val hash = md5(id)
        if (hash == artHash) return hash
        artHash = hash
        worker.execute {
            val pic = bmp ?: uri?.let { loadArt(it) }
            if (pic != null && hash == artHash) {
                val out = ByteArrayOutputStream()
                scale(pic, 360).compress(Bitmap.CompressFormat.JPEG, 82, out)
                val payload = hash.toByteArray() + byteArrayOf(0) + out.toByteArray()
                seal?.let { mqtt?.publish(t("art"), it.seal(payload), retain = true) }
            }
        }
        return hash
    }

    private fun loadArt(uri: String): Bitmap? = try {
        if (uri.startsWith("http")) {
            val c = URL(uri).openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000
            c.inputStream.use { BitmapFactory.decodeStream(it) }
        } else svc.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------------------- apps

    private fun publishApps() {
        val s = seal ?: return
        worker.execute {
            try {
                val pm = svc.packageManager
                val home = Apps.resolve(svc)
                val homeIds = home.map { HomeList.idOf(it) }
                val arr = JSONArray()
                val done = HashSet<String>()
                fun add(id: String, label: String, pkg: String?, onHome: Boolean, color: Int?, sys: Boolean = false) {
                    if (!done.add(id)) return
                    val o = JSONObject().put("id", id).put("label", label).put("on", onHome).put("sys", sys)
                    val v = BubbleView(svc)
                    if (pkg != null) BubbleView.bigIcon(svc, pkg)?.let { d -> v.art = d; v.fullBleed = BubbleView.fillsCircle(d) }
                    else Slots.all.firstOrNull { it.key == id }?.let { sl -> HomeList.letterArt(v, sl.key, sl.label) }
                    val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    val ms = android.view.View.MeasureSpec.makeMeasureSpec(96, android.view.View.MeasureSpec.EXACTLY); v.measure(ms, ms); v.layout(0, 0, 96, 96); v.draw(Canvas(bmp))
                    val out = ByteArrayOutputStream()
                    @Suppress("DEPRECATION")
                    bmp.compress(Bitmap.CompressFormat.WEBP, 82, out)
                    o.put("icon", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                    o.put("color", color ?: averageColor(bmp))
                    arr.put(o)
                }
                // Only apps the TV really has (and websites): nothing that still has to be installed.
                fun has(e: Entry) = e.pkg != null || e.slot.kind != SlotKind.APP
                for (e in home) if (has(e)) add(HomeList.idOf(e), e.slot.label, e.pkg, true, HaloView.knownColor(e.slot.key))
                // Each app once: an app already on the home screen is not listed again under its package name.
                val homePkgs = home.mapNotNull { it.pkg }.toSet()
                val standard = Apps.standard(svc)
                val slotOfPkg = HashMap<String, Entry>()
                for (e in standard) e.pkg?.let { slotOfPkg[it] = e }
                for (e in standard) if (has(e) && HomeList.idOf(e) !in homeIds) add(HomeList.idOf(e), e.slot.label, e.pkg, false, HaloView.knownColor(e.slot.key))
                val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { (p, _) -> p != svc.packageName && p !in IGNORE && p != "com.android.tv.settings" }
                    .distinctBy { it.first }.sortedBy { it.second.lowercase() }
                for ((p, l) in all) {
                    if (p in homePkgs || p in homeIds || slotOfPkg.containsKey(p)) continue
                    // The TV maker's own built-in apps are only shown when you search for them.
                    val sys = try { val ai = pm.getApplicationInfo(p, 0)
                        (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 } catch (e: Exception) { false }
                    add(p, l, p, false, null, sys)
                }
                mqtt?.publish(t("apps"), s.seal(arr.toString().toByteArray()), retain = true)
            } catch (e: Exception) { Log.w(TAG, "app list failed", e) }
        }
    }

    // ------------------------------------------------------------------------------- commands

    private fun handle(text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        // Only fresh, never-seen messages (stops anyone replaying an old one).
        val age = kotlin.math.abs(System.currentTimeMillis() - o.optLong("t"))
        val n = o.optString("n")
        if (age > 5 * 60_000 || n.isEmpty() || !seen.add(n)) return
        if (seen.size > 300) seen.remove(seen.first())
        when (o.optString("c")) {
            "key" -> key(o.optString("k"))
            "open" -> open(o.optString("id"))
            "search" -> search(o.optString("q"))
            "type" -> type(o.optString("text"))
            "type_live" -> typeLive(o.optString("text"))
            "type_enter" -> typeEnter()
            "home_add" -> { HomeList.add(svc, o.optString("id")); changed() }
            "home_remove" -> { HomeList.remove(svc, o.optString("id")); changed() }
            "settings" -> settings(o.optString("which"))
            "apps_get" -> publishApps()
            "update_check" -> svc.checkUpdate()
            "tvset_get" -> tvset.get(o.optString("page"))
            "wifi_join" -> tvset.joinWifi(o.optString("ssid"), o.optString("pass"))
            "bt_power" -> tvset.btPower(o.optBoolean("on"))
            "bt_connect" -> tvset.btConnect(o.optString("addr"))
            "bt_pair" -> tvset.btPair(o.optString("addr"))
            "picture_mode" -> tvset.pictureMode(o.optString("mode"))
            "picture_set" -> tvset.pictureSet(o.optString("name"), o.optInt("v"))
            "store_search" -> store.search(o.optString("q"))
            "install" -> store.install(o.optString("id"))
            "state_get" -> publishState()
        }
    }

    private fun changed() {
        svc.sendBroadcast(Intent(HOME_LIST_CHANGED).setPackage(svc.packageName))
    }

    private fun key(k: String) {
        val c = controller?.transportControls
        when (k) {
            "home" -> svc.openHome(force = true)
            "up", "down", "left", "right", "ok" -> svc.dpad(k)
            "back" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "power" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            "vol_up" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "vol_down" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "playpause" -> if (c != null) { if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) c.pause() else c.play() } else media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> c?.skipToNext() ?: media(KeyEvent.KEYCODE_MEDIA_NEXT)
            "prev" -> c?.skipToPrevious() ?: media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "fwd" -> seekBy(10_000, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            "rew" -> seekBy(-10_000, KeyEvent.KEYCODE_MEDIA_REWIND)
        }
        schedule()
    }

    private fun seekBy(ms: Long, fallback: Int) {
        val c = controller; val ps = c?.playbackState
        if (c != null && ps != null && ps.actions and PlaybackState.ACTION_SEEK_TO != 0L) {
            var pos = ps.position
            if (ps.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0) pos += SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
            c.transportControls.seekTo((pos + ms).coerceAtLeast(0))
        } else media(fallback)
    }

    private fun media(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun open(id: String) {
        val e = Apps.resolve(svc).firstOrNull { HomeList.idOf(it) == id }
        val pkg = e?.pkg ?: id.takeIf { Apps.launchIntent(svc, it) != null }
        val launch = pkg?.let { Apps.launchIntent(svc, it) }
        try {
            when {
                launch != null -> svc.startActivity(launch)
                e?.slot?.url != null -> Apps.openUrl(svc, e.slot.url)
                e?.slot?.store != null -> svc.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${e.slot.store}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (x: Exception) { Log.w(TAG, "open failed", x) }
    }

    /** Google search on the TV for what was said on the phone. */
    private fun search(q: String) {
        if (q.isBlank()) return
        for (i in listOf(
            Intent("android.search.action.GLOBAL_SEARCH").putExtra(SearchManager.QUERY, q),
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q),
            Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, q)
        )) {
            try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) { }
        }
    }

    /** Puts typed text into the box that has focus on the TV and submits it. */
    private fun type(text: String) {
        val root = svc.rootInActiveWindow ?: return
        val box = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root) ?: return
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (android.os.Build.VERSION.SDK_INT >= 30) box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** What you type on the phone's keyboard, into the focused box on the TV as you type (no Enter). */
    private fun typeLive(text: String) {
        val root = svc.rootInActiveWindow ?: return
        val box = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root) ?: return
        typing = true; main.removeCallbacks(doneTyping); main.postDelayed(doneTyping, 1500)
        kbdText = text
        box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
    }

    /** Enter on the phone's keyboard: Enter in the TV's box (search, go, next). */
    private fun typeEnter() {
        val root = svc.rootInActiveWindow ?: return
        val box = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 30) box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        checkKeyboard()
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { findEditable(it)?.let { f -> return f } }
        return null
    }

    private fun settings(which: String) {
        val tries = when (which) {
            "wifi" -> listOf(Intent(Settings.ACTION_WIFI_SETTINGS))
            "bluetooth" -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "picture" -> listOf(Intent("com.android.tv.settings.PICTURE_SETTINGS"), Intent("android.settings.PICTURE_SETTINGS"),
                Intent(Settings.ACTION_DISPLAY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            else -> emptyList()
        }
        for (i in tries) { try { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) { } }
    }

    // ------------------------------------------------------------------------------- helpers

    private fun label(pkg: String): String = try {
        svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    companion object {
        private const val TAG = "LivingRoomRemote"
        const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx"
        const val HOME_LIST_CHANGED = "com.livingroom.home.HOME_LIST_CHANGED"
        const val DPAD = "com.livingroom.home.DPAD"
        private val IGNORE = setOf("com.android.systemui", "com.google.android.tvrecommendations", "com.google.android.katniss",
            "com.android.inputmethod.latin", "com.google.android.inputmethod.latin", "com.google.android.leanback.ime",
            "org.liskovsoft.androidtv.rukeyboard", "com.google.android.tungsten.setupwraith")

        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun scale(b: Bitmap, max: Int): Bitmap {
            val k = max.toFloat() / maxOf(b.width, b.height)
            return if (k >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        }

        fun averageColor(b: Bitmap): Int {
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            val step = maxOf(1, b.width / 24)
            for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) < 200) continue
                val mx = maxOf(Color.red(p), Color.green(p), Color.blue(p)); val mn = minOf(Color.red(p), Color.green(p), Color.blue(p))
                val w = 1 + (mx - mn) / 16   // colourful pixels count more than grey ones
                r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; n += w
            }
            return if (n == 0L) Color.rgb(200, 210, 255) else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }
    }
}
