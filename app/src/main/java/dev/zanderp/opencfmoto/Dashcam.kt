// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The dash's own dashcam (DVR), as the official Zontes Smart app reads it (docs/RE-DVR.md): JSON
 * POSTs to `http://192.168.3.1:7686` on the dash's Wi-Fi — the same network we project over, so no
 * Wi-Fi switch. Every request goes out on the bike [Network] (it has no internet, so the default
 * route is mobile data).
 */
object Dashcam {
    // ponytail: fixed address from the official app (SoftAP dashes); P2P-only dashes may differ.
    const val HOST = "192.168.3.1"
    const val PORT = 7686
    private const val BASE = "http://$HOST:$PORT"
    private const val PREFS = "dashcam"

    enum class Cam(val api: String) { FRONT("front"), REAR("rear") }
    data class Group(val date: String, val time: String, val thumb: String?)
    data class Clip(val url: String, val thumb: String?, val title: String, val name: String)

    /** The bike Wi-Fi: ours while connected, else any Wi-Fi the rider joined by hand. */
    fun network(ctx: Context): Network? {
        BikeWifi.currentNetwork?.let { return it }
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    fun client(ctx: Context, readSec: Long = 10): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .apply { network(ctx)?.let { socketFactory(it.socketFactory) } }
        .build()

    /** One API call; the raw reply is logged so a new firmware's format shows up in the rider's log. */
    fun call(ctx: Context, body: JSONObject): JSONObject {
        val req = Request.Builder().url(BASE)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client(ctx).newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            LogBus.log("[DVR] ${body.optString("METHOD")} → ${r.code} ${text.take(300)}")
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return JSONObject(text)
        }
    }

    /** The dash sometimes leaves out the scheme ("192.168.3.1:7686/…") or the host ("/…"). */
    fun fullUrl(u: String?): String? = when {
        u.isNullOrBlank() -> null
        u.startsWith("http") -> u
        u.startsWith("/") -> BASE + u
        else -> "http://$u"
    }

    /** Every (day, recording block) for one camera, newest first. */
    fun groups(ctx: Context, cam: Cam): List<Group> {
        val dates = call(ctx, JSONObject().put("METHOD", "VIDEO.DATE").put("previewType", cam.api)).optJSONArray("dateList")
            ?: return emptyList()
        return (0 until dates.length()).flatMap { i ->
            val date = dates.getString(i)
            val t = call(ctx, JSONObject().put("METHOD", "VIDEO.TIME").put("date", date).put("previewType", cam.api))
                .optJSONArray("timeBeanList") ?: return@flatMap emptyList()
            (0 until t.length()).map { j ->
                val o = t.optJSONObject(j) ?: JSONObject(t.getString(j))
                Group(date, o.optString("time"), fullUrl(o.optString("thumbUrl")))
            }
        }
    }

    fun clips(ctx: Context, cam: Cam, g: Group): List<Clip> {
        val a = call(
            ctx, JSONObject().put("METHOD", "VIDEO.INFO.LIST").put("date", g.date).put("time", g.time)
                .put("index", 0).put("num", 120).put("previewType", cam.api).put("sort", "des"),
        ).optJSONArray("videoBeanList") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: JSONObject(a.getString(i))
            val url = fullUrl(o.optString("playUrl")) ?: return@mapNotNull null
            Clip(url, fullUrl(o.optString("thumbUrl")), o.optString("title"), o.optString("name").ifBlank { url.substringAfterLast('/') })
        }
    }

    fun pictures(ctx: Context): List<String> {
        val a = call(
            ctx, JSONObject().put("METHOD", "PICTURE.INFO.LIST").put("index", 0).put("num", 100)
                .put("previewType", "all").put("sort", "des"),
        ).optJSONArray("pictureBeanList") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> fullUrl((a.optJSONObject(i) ?: JSONObject(a.getString(i))).optString("pictureUrl")) }
    }

    // ---------------------------------------------------------------- thumbnails

    // Lazy: Android-only classes, kept out of the object's init so the pure helpers unit-test on the JVM.
    private val thumbs by lazy {
        object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
    private val thumbPool = Executors.newFixedThreadPool(3)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun thumb(ctx: Context, url: String?, maxPx: Int, done: (Bitmap?) -> Unit) {
        if (url == null) return done(null)
        thumbs.get(url)?.let { return done(it) }
        val app = ctx.applicationContext
        thumbPool.execute {
            val bmp = try {
                client(app).newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val bytes = r.body?.bytes() ?: return@use null
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1
                    while (o.outWidth / (s * 2) >= maxPx) s *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                }
            } catch (_: Exception) { null }
            bmp?.let { thumbs.put(url, it) }
            main.post { done(bmp) }
        }
    }

    // ---------------------------------------------------------------- downloads

    /** url → progress 0..100, or -1 failed. Survives leaving the screen (one download at a time). */
    val progress = ConcurrentHashMap<String, Int>()
    var onProgress: (() -> Unit)? = null
    private val downloader = Executors.newSingleThreadExecutor()

    fun isSaved(ctx: Context, name: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("saved", emptySet())!!.contains(name)

    fun download(ctx: Context, url: String, name: String, video: Boolean) {
        if (progress[url]?.let { it in 0..99 } == true) return
        val app = ctx.applicationContext
        progress[url] = 0
        main.post { onProgress?.invoke() }
        downloader.execute {
            val ok = try { save(app, url, name, video); true } catch (e: Exception) {
                LogBus.log("[DVR] download failed $name: ${e.javaClass.simpleName} ${e.message}")
                false
            }
            progress[url] = if (ok) 100 else -1
            if (ok) {
                val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                p.edit().putStringSet("saved", p.getStringSet("saved", emptySet())!! + name).apply()
            }
            main.post { onProgress?.invoke() }
        }
    }

    /** Into the gallery (Movies|Pictures/ZRide Dashcam), dated by the clip's own time so Trip video finds it. */
    private fun save(ctx: Context, url: String, name: String, video: Boolean) {
        val resolver = ctx.contentResolver
        val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/ZRide Dashcam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            takenAt(name)?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(coll, values) ?: error("MediaStore insert failed")
        try {
            client(ctx, readSec = 30).newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body ?: error("empty")
                val total = body.contentLength()
                resolver.openOutputStream(uri)!!.use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 99 / total).toInt() else 50
                            if (pct != shown) { shown = pct; progress[url] = pct; main.post { onProgress?.invoke() } }
                        }
                    }
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            LogBus.log("[DVR] saved $name")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * The clip's local time from its file name: the official app reads "<date>_<HH-MM-SS>_…"
     * (2026-11-06_14-35-12_…); "20261106_143512" and "20261106143512" work too.
     */
    internal fun takenAt(name: String): Long? {
        val m = Regex("(20\\d\\d)\\D?(\\d\\d)\\D?(\\d\\d)\\D?(\\d\\d)\\D?(\\d\\d)\\D?(\\d\\d)").find(name) ?: return null
        return try {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }.parse(m.groupValues.drop(1).joinToString(""))?.time
        } catch (_: Exception) { null }
    }

    // ---------------------------------------------------------------- live view probe

    /**
     * The official app has no live view, so we don't know if the dash streams one. Look for the usual
     * doors (RTSP, HTTP, the DVR control socket) and ask the API a few likely names; everything goes to
     * the log so a ride with the bike tells us what exists.
     */
    fun probeLive(ctx: Context): String {
        val net = network(ctx)
        val open = listOf(554, 8554, 80, 8080, 8081, 1935, 6669, PORT).filter { port ->
            try {
                (net?.socketFactory?.createSocket() ?: java.net.Socket()).use { it.connect(InetSocketAddress(HOST, port), 1500) }
                true
            } catch (_: Exception) { false }
        }
        LogBus.log("[DVR] live probe: open ports on $HOST = $open")
        val answers = listOf("VIDEO.LIVE", "LIVE.URL", "PREVIEW.URL", "RTSP.URL", "VIDEO.PREVIEW", "CAMERA.INFO", "DEVICE.INFO").mapNotNull { m ->
            try {
                call(ctx, JSONObject().put("METHOD", m).put("previewType", "front")).toString().take(120).let { "$m: $it" }
            } catch (_: Exception) { null }
        }
        return (listOf("ports: ${open.joinToString().ifBlank { "—" }}") + answers).joinToString("\n")
    }
}
