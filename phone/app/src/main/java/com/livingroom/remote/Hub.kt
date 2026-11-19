package com.livingroom.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class TvApp(val id: String, val label: String, val on: Boolean, val icon: Bitmap?, val color: Int, val sys: Boolean = false)
data class Media(val title: String, val artist: String, val dur: Long, val pos: Long, val playing: Boolean, val art: String, val at: Long)
data class TvState(val app: String?, val appLabel: String, val media: Media?, val vol: Int, val volMax: Int, val muted: Boolean,
                   val temp: Int?, val wx: String?)

/** The one link to the TV, shared by every screen. Keeps the last news from the TV so screens open instantly. */
object Hub {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var mqtt: Mqtt? = null
    private var seal: Seal? = null
    private var users = 0
    private var cacheDir: File? = null

    @Volatile var linked = false; private set
    @Volatile var tvOnline = false; private set
    var state: TvState? = null; private set
    var apps: List<TvApp> = emptyList(); private set
    var art: Pair<String, Bitmap>? = null; private set
    /** Google Play results for the last search: query → apps not on the TV yet. */
    var found: Pair<String, List<TvApp>>? = null; private set
    var foundFailed = false; private set
    /** The TV's picture, Wi-Fi and Bluetooth settings, as the TV last sent them (null until it does). */
    var tvSet: JSONObject? = null; private set
    /** Play installs asked from the phone: id → installing / done / notv / fail. */
    val installs = java.util.concurrent.ConcurrentHashMap<String, String>()
    val ready: Boolean get() = seal != null

    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { for (l in listeners.toList()) l() }

    private val queue = ArrayList<Pair<Long, ByteArray>>()

    private fun t(s: String) = "${seal!!.base}/$s"

    /** Every screen calls this when it shows… */
    fun start(ctx: Context) {
        users++
        main.removeCallbacks(stopNow)
        if (mqtt != null) return
        val key = ctx.getString(R.string.link_key).trim()
        if (key.length < 16) return
        val s = Seal(key); seal = s
        cacheDir = ctx.cacheDir
        if (state == null) loadCache(s)
        mqtt = Mqtt("lrh-ph-" + UUID.randomUUID().toString().take(8), null,
            onConnected = { m ->
                m.subscribe(t("on"), t("state"), t("apps"), t("art"), t("found"), t("inst"), t("tvset"))
                synchronized(queue) {
                    val now = System.currentTimeMillis()
                    for ((at, b) in queue) if (now - at < 10_000) m.publish(t("cmd"), b)
                    queue.clear()
                }
            },
            onMessage = { topic, data -> onMessage(s, topic, data) },
            onLink = { up -> linked = up; changed() })
        mqtt?.start()
    }

    /** …and this when it hides. The link stays up a moment so moving between screens is seamless. */
    fun stop() {
        users = maxOf(0, users - 1)
        if (users == 0) main.postDelayed(stopNow, 4000)
    }

    private val stopNow = Runnable { if (users == 0) { mqtt?.stop(); mqtt = null; linked = false } }

    fun send(c: String, vararg kv: Pair<String, Any>) {
        val s = seal ?: return
        val o = JSONObject().put("c", c).put("t", System.currentTimeMillis()).put("n", UUID.randomUUID().toString())
        for ((k, v) in kv) o.put(k, v)
        val bytes = s.seal(o.toString().toByteArray())
        worker.execute {
            val m = mqtt
            if (m == null || !m.connected || !m.publish(t("cmd"), bytes)) synchronized(queue) { queue += System.currentTimeMillis() to bytes }
        }
    }

    // ------------------------------------------------------------------------------- from the TV

    private fun onMessage(s: Seal, topic: String, data: ByteArray) {
        when (topic) {
            t("on") -> { tvOnline = String(data) == "1"; changed() }
            t("state") -> s.open(data)?.let { parseState(String(it))?.let { st -> state = st; tvOnline = true; changed(); save("state", it) } }
            t("apps") -> s.open(data)?.let { parseApps(String(it))?.let { a -> apps = a; changed(); save("apps", it) } }
            t("art") -> s.open(data)?.let { parseArt(it)?.let { a -> art = a; changed(); save("art", it) } }
            t("found") -> s.open(data)?.let { b -> try {
                val o = JSONObject(String(b))
                found = o.optString("q") to (parseApps(o.optJSONArray("apps")?.toString() ?: "[]") ?: emptyList())
                foundFailed = o.optBoolean("err"); changed()
            } catch (e: Exception) { } }
            t("tvset") -> s.open(data)?.let { b -> try { tvSet = JSONObject(String(b)); changed() } catch (e: Exception) { } }
            t("inst") -> s.open(data)?.let { b -> try {
                val o = JSONObject(String(b)); installs[o.optString("id")] = o.optString("st"); changed()
                if (o.optString("st") == "done") send("apps_get")
            } catch (e: Exception) { } }
        }
    }

    private fun parseState(text: String): TvState? = try {
        val o = JSONObject(text)
        val app = o.optJSONObject("app")
        val m = o.optJSONObject("media")
        val v = o.optJSONObject("vol")
        val w = o.optJSONObject("wx")
        TvState(
            app?.optString("pkg"), app?.optString("label") ?: "",
            m?.let { Media(it.optString("title"), it.optString("artist"), it.optLong("dur"), it.optLong("pos"), it.optBoolean("playing"),
                it.optString("art"), o.optLong("t", System.currentTimeMillis())) },
            v?.optInt("l") ?: 0, v?.optInt("m") ?: 15, v?.optBoolean("mu") ?: false,
            w?.optInt("t"), w?.optString("label"))
    } catch (e: Exception) { null }

    private fun parseApps(text: String): List<TvApp>? = try {
        val a = JSONArray(text)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val icon = o.optString("icon").takeIf { it.isNotEmpty() }?.let {
                val b = Base64.decode(it, Base64.DEFAULT); BitmapFactory.decodeByteArray(b, 0, b.size)
            }
            TvApp(o.optString("id"), o.optString("label"), o.optBoolean("on"), icon, o.optInt("color", Color.rgb(200, 210, 255)), o.optBoolean("sys"))
        }
    } catch (e: Exception) { null }

    private fun parseArt(b: ByteArray): Pair<String, Bitmap>? {
        val z = b.indexOf(0.toByte()); if (z <= 0) return null
        val bmp = BitmapFactory.decodeByteArray(b, z + 1, b.size - z - 1) ?: return null
        return String(b, 0, z) to bmp
    }

    /** Locally: TV id → whether it is on the TV home screen, changed straight away when you tap (the TV confirms). */
    fun setOnTv(id: String, on: Boolean) {
        apps = apps.map { if (it.id == id) it.copy(on = on) else it }
        send(if (on) "home_add" else "home_remove", "id" to id)
        changed()
    }

    // ------------------------------------------------------------------------------- last news, kept on the phone

    private fun save(name: String, plain: ByteArray) {
        val d = cacheDir ?: return
        val s = seal ?: return
        worker.execute { try { File(d, "$name.bin").writeBytes(s.seal(plain)) } catch (e: Exception) { } }
    }

    private fun loadCache(s: Seal) {
        val d = cacheDir ?: return
        fun read(n: String) = try { File(d, "$n.bin").takeIf { it.exists() }?.readBytes()?.let { s.open(it) } } catch (e: Exception) { null }
        read("apps")?.let { parseApps(String(it)) }?.let { apps = it }
        read("art")?.let { parseArt(it) }?.let { art = it }
        // The last state is only a starting point: what is "on TV" now comes from the TV.
        read("state")?.let { parseState(String(it)) }?.let { state = it.copy(app = null, media = null) }
    }
}
