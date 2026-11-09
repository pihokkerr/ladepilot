package app.ladepilot.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loads the condensed charging data published by the Ladepilot backend
 * (Cloudflare Pages + KV, filled every 15/30 minutes by GitHub Actions).
 * Shared by the phone UI and the Android Auto screens (same process).
 */
object DataRepo {
    private const val TAG = "DataRepo"
    private val SOURCES = listOf(
        "AT" to "/api/at",
        "HU" to "/api/hu",
        "DE" to "/api/de",
    )
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var sites: List<Site> = emptyList(); private set
    @Volatile var loading = false; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var generated: Map<String, String?> = emptyMap(); private set
    @Volatile private var lastNetwork = 0L
    private var byId: Map<String, Site> = emptyMap()

    /** Increments whenever data changes – observed by Compose. */
    val version = MutableStateFlow(0)

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun site(id: String): Site? = byId[id]

    /** Load cached data immediately and refresh from the network when stale. */
    fun ensureLoaded(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            if (sites.isEmpty()) loadFromDisk(app)
            if (System.currentTimeMillis() - lastNetwork > MAX_AGE_MS) refresh(app)
        }
    }

    fun refreshNow(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { refresh(app) }
    }

    private suspend fun loadFromDisk(ctx: Context) = mutex.withLock {
        val parsed = withContext(Dispatchers.IO) { parseAll(ctx) }
        if (parsed.first.isNotEmpty()) publish(parsed.first, parsed.second)
    }

    private suspend fun refresh(ctx: Context) {
        if (loading) return
        mutex.withLock {
            loading = true
            notifyChanged()
            val base = ProfileStore.load(ctx).baseUrl.trimEnd('/')
            val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for ((src, path) in SOURCES) {
                    try {
                        Http.download("$base$path", cacheFile(ctx, src))
                    } catch (e: Exception) {
                        Log.w(TAG, "download $src failed", e)
                        errors += "$src: ${e.message}"
                    }
                }
            }
            val parsed = withContext(Dispatchers.IO) { parseAll(ctx) }
            lastNetwork = System.currentTimeMillis()
            lastError = if (errors.size == SOURCES.size) errors.joinToString("; ") else null
            loading = false
            if (parsed.first.isNotEmpty()) publish(parsed.first, parsed.second) else notifyChanged()
        }
    }

    private fun cacheFile(ctx: Context, src: String) = File(ctx.filesDir, "data_$src.json")

    private fun parseAll(ctx: Context): Pair<List<Site>, Map<String, String?>> {
        val all = ArrayList<Site>(90000)
        val gen = HashMap<String, String?>()
        for ((src, _) in SOURCES) {
            val f = cacheFile(ctx, src)
            if (!f.exists() || f.length() < 10) continue
            try {
                val (g, list) = f.inputStream().use { parse(it, src) }
                gen[src] = g
                all.addAll(list)
            } catch (e: Exception) {
                Log.w(TAG, "parse $src failed", e)
            }
        }
        return all to gen
    }

    private fun publish(list: List<Site>, gen: Map<String, String?>) {
        sites = list
        byId = list.associateBy { it.id }
        generated = gen
        notifyChanged()
    }

    private fun notifyChanged() {
        main.post {
            version.value = version.value + 1
            for (l in listeners) l()
        }
    }

    // ------------------------------------------------------------------ parsing
    private class Raw(
        val id: String, val name: String, val opi: Int, val lat: Double, val lon: Double,
        val addr: String, val country: String, val points: List<ChargePoint>,
        val daily: List<DailyPrice>?, val abs: List<AbsPrice>?, val cur: String, val fx: Double,
        val startFee: Double?, val idleFee: Double?,
    )

    private fun JsonReader.optString(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    private fun JsonReader.optDouble(): Double? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextDouble()

    fun parse(input: java.io.InputStream, src: String): Pair<String?, List<Site>> {
        val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        var generated: String? = null
        var ops: List<String> = emptyList()
        val raws = ArrayList<Raw>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "generated" -> generated = r.optString()
                "ops" -> {
                    val l = ArrayList<String>()
                    r.beginArray(); while (r.hasNext()) l += (r.optString() ?: ""); r.endArray()
                    ops = l
                }
                "sites" -> {
                    r.beginArray()
                    while (r.hasNext()) readSite(r)?.let { raws += it }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val sites = raws.map {
            Site(
                id = it.id, name = it.name, op = ops.getOrElse(it.opi) { "" }, lat = it.lat, lon = it.lon,
                addr = it.addr, country = it.country, points = it.points, daily = it.daily, abs = it.abs,
                cur = it.cur, fx = it.fx, src = src, startFeeLocal = it.startFee, idleFeeLocal = it.idleFee,
            )
        }
        return generated to sites
    }

    private fun readSite(r: JsonReader): Raw? {
        r.beginArray()
        val id = r.optString() ?: ""
        val name = r.optString() ?: ""
        val opi = r.nextInt()
        val lat = r.nextDouble()
        val lon = r.nextDouble()
        val addr = r.optString() ?: ""
        val country = r.optString() ?: ""
        val points = ArrayList<ChargePoint>()
        r.beginArray()
        while (r.hasNext()) {
            r.beginArray()
            val kw = r.optDouble()?.toInt() ?: 0
            val plugs = (r.optString() ?: "").intern()
            val kwh = r.optDouble()
            val perMin = r.optDouble()
            val status = r.optDouble()?.toInt() ?: 0
            val flags = if (r.hasNext()) (r.optDouble()?.toInt() ?: 0) else 0
            while (r.hasNext()) r.skipValue()
            r.endArray()
            points += ChargePoint(kw, plugs, kwh, perMin, status, flags and 1 == 1, flags and 2 == 2)
        }
        r.endArray()
        var daily: List<DailyPrice>? = null
        var abs: List<AbsPrice>? = null
        var cur = "EUR"
        var fx = 1.0
        var startFee: Double? = null
        var idleFee: Double? = null
        if (r.hasNext()) {
            if (r.peek() == JsonToken.NULL) r.nextNull() else {
                val l = ArrayList<DailyPrice>()
                r.beginArray()
                while (r.hasNext()) {
                    r.beginArray()
                    val a = r.nextInt(); val b = r.nextInt(); val v = r.nextDouble()
                    while (r.hasNext()) r.skipValue()
                    r.endArray()
                    l += DailyPrice(a, b, v)
                }
                r.endArray()
                daily = l
            }
        }
        if (r.hasNext()) {
            if (r.peek() == JsonToken.NULL) r.nextNull() else {
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "cur" -> cur = r.optString() ?: "EUR"
                        "fx" -> fx = r.optDouble() ?: 1.0
                        "abs" -> {
                            val l = ArrayList<AbsPrice>()
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                val at = r.nextLong(); val v = r.nextDouble()
                                while (r.hasNext()) r.skipValue()
                                r.endArray()
                                l += AbsPrice(at, v)
                            }
                            r.endArray()
                            abs = l
                        }
                        "fees" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "startFee" -> startFee = r.optDouble()
                                    "idle" -> idleFee = r.optDouble()
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
        }
        while (r.hasNext()) r.skipValue()
        r.endArray()
        if (points.isEmpty()) return null
        return Raw(id, name, opi, lat, lon, addr, country, points, daily, abs, cur, fx, startFee, idleFee)
    }
}
