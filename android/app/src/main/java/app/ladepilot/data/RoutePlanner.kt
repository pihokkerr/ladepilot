package app.ladepilot.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A charger near the route. */
data class Candidate(
    val site: Site,
    val alongKm: Double,
    val offKm: Double,
    /** driving seconds from the start to this point (without stops) */
    val routeSec: Double,
)

/** Candidate as seen in the list: without any charging before it. */
data class CandidateView(
    val cand: Candidate,
    val etaMillis: Long,
    val arrivalSoc: Double,
    val reachable: Boolean,
    val quote: Quote?,
)

/** A planned charging stop. */
data class ChargeStop(
    val cand: Candidate,
    val etaMillis: Long,
    val arrivalSoc: Double,
    val targetSoc: Double,
    val kwhBattery: Double,
    val kwhBilled: Double,
    val minutes: Double,
    val quote: Quote,
    /** EUR */
    val cost: Double,
)

data class Plan(
    val origin: Place,
    val dest: Place,
    val distanceKm: Double,
    val durationSec: Double,
    val startSoc: Int,
    val startMillis: Long,
    val stops: List<ChargeStop>,
    val arrivalSoc: Double,
    val arrivalMillis: Long,
    val feasible: Boolean,
    val note: String?,
    val candidates: List<CandidateView>,
    /** stops the user picked by hand (site ids) */
    val fixedStopIds: List<String>,
    /** chargers the user removed – never chosen automatically */
    val excludedIds: Set<String>,
    /** "price" or "time" */
    val optimize: String,
    /** route geometry for drawing: [lat, lon] */
    val route: List<DoubleArray>,
    /** intermediate destinations in driving order */
    val vias: List<Place> = emptyList(),
    /** km along the route where each via is reached */
    val viaKm: List<Double> = emptyList(),
    /** average consumption over this route, kWh/100 km */
    val avgKwh100: Double = 0.0,
) {
    val totalCost: Double get() = stops.sumOf { it.cost }

    /** intermediate destinations and charging stops merged in driving order, as (lat, lon) */
    val navPoints: List<Pair<Double, Double>>
        get() = (vias.mapIndexed { i, v -> viaKm.getOrElse(i) { 0.0 } to (v.lat to v.lon) } +
            stops.map { it.cand.alongKm to (it.cand.site.lat to it.cand.site.lon) })
            .sortedBy { it.first }.map { it.second }
    val chargeMinutes: Double get() = stops.sumOf { it.minutes }
}

internal class StopOption(val stop: ChargeStop, val lostMin: Double, val extraStop: Boolean)

/** The current plan, shared between phone and Android Auto. */
object PlanStore {
    val plan = MutableStateFlow<Plan?>(null)
}

object RoutePlanner {
    private const val CORRIDOR_KM = 4.0
    private const val SAMPLE_KM = 1.0

    private class RP(val lat: Double, val lon: Double, val km: Double, val sec: Double)
    private class RouteData(val km: Double, val sec: Double, val poly: List<DoubleArray>, val legKm: List<Double>,
                            val segM: List<Double>, val segS: List<Double>)
    @Volatile private var routeCache: Pair<String, RouteData>? = null

    /**
     * Best order for intermediate stops (start and destination fixed), using the OSRM trip service.
     */
    suspend fun optimizeOrder(origin: Place, vias: List<Place>, dest: Place): List<Place> = withContext(Dispatchers.IO) {
        if (vias.size < 2) return@withContext vias
        val all = listOf(origin) + vias + listOf(dest)
        val coords = all.joinToString(";") { "${it.lon},${it.lat}" }
        val body = Http.get("https://router.project-osrm.org/trip/v1/driving/$coords?source=first&destination=last&roundtrip=false&overview=false")
        val wps = JSONObject(body).getJSONArray("waypoints")
        val order = (0 until wps.length()).map { i -> i to wps.getJSONObject(i).getInt("waypoint_index") }
        order.filter { it.first in 1..vias.size }.sortedBy { it.second }.map { vias[it.first - 1] }
    }

    /** Keep roughly one point every ~150 m for drawing. */
    private fun simplify(poly: List<DoubleArray>): List<DoubleArray> {
        if (poly.size < 3) return poly
        val out = ArrayList<DoubleArray>(); var last = poly.first(); out += last
        for (p in poly) if (Geo.fastKm(last[0], last[1], p[0], p[1]) >= 0.15) { out += p; last = p }
        if (out.last() !== poly.last()) out += poly.last()
        return out
    }

    /** Reverse geocoding for "my location" labels. */
    suspend fun reverse(lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(Http.get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&accept-language=hu&lat=$lat&lon=$lon"))
            o.optString("display_name").split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(", ")
        } catch (e: Exception) { "Saját hely" }
    }

    private val COORDS = Regex("""(-?\d{1,2}\.\d+)\s*[,;\s]\s*(-?\d{1,3}\.\d+)""")
    private val AT_COORDS = Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")
    private val Q_COORDS = Regex("""[?&](?:q|query|ll|destination|daddr)=(-?\d{1,2}\.\d+)(?:,|%2C)(-?\d{1,3}\.\d+)""")
    private val DATA_COORDS = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")
    private val URL = Regex("""https?://\S+""")

    /** Coordinates written as text or inside a Google Maps link. */
    private fun coordsIn(text: String): Pair<Double, Double>? {
        for (re in listOf(DATA_COORDS, Q_COORDS, AT_COORDS, COORDS)) {
            val m = re.find(text) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
        return null
    }

    /** Follows short links like maps.app.goo.gl to the final URL. */
    private fun expandUrl(url: String): String {
        var u = url
        repeat(5) {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Ladepilot")
            val loc = c.getHeaderField("Location")
            c.disconnect()
            if (loc.isNullOrBlank()) return u
            u = if (loc.startsWith("http")) loc else java.net.URL(java.net.URL(u), loc).toString()
        }
        return u
    }

    private fun photon(q: String, near: Place?): List<Place> {
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: ""
        val body = Http.get("https://photon.komoot.io/api/?limit=8&q=${URLEncoder.encode(q, "UTF-8")}$bias")
        val feats = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until feats.length()).mapNotNull { i ->
            val f = feats.getJSONObject(i)
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val pr = f.optJSONObject("properties") ?: JSONObject()
            val street = listOf(pr.optString("street"), pr.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val town = listOf(pr.optString("postcode"), pr.optString("city").ifBlank { pr.optString("county") }).filter { it.isNotBlank() }.joinToString(" ")
            val name = pr.optString("name")
            val label = listOf(name, street, town, pr.optString("country")).filter { it.isNotBlank() }.distinct().take(3).joinToString(", ")
            Place(label.ifBlank { "${c.getDouble(1)}, ${c.getDouble(0)}" }, c.getDouble(1), c.getDouble(0))
        }
    }

    private fun nominatim(q: String): List<Place> {
        val body = Http.get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&accept-language=hu&q=${URLEncoder.encode(q, "UTF-8")}")
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val parts = o.optString("display_name").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            Place(parts.take(3).joinToString(", "), o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
    }

    /**
     * Forgiving search: coordinates, Google Maps links (also short links), place / company names,
     * long addresses copied from Google Maps (shortened step by step if nothing is found).
     */
    suspend fun geocode(query: String, near: Place? = null): List<Place> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext emptyList()
        // 1. links and coordinates
        val url = URL.find(text)?.value
        val expanded = if (url != null) try { expandUrl(url) } catch (e: Exception) { url } else null
        val decoded = try { java.net.URLDecoder.decode(expanded ?: text, "UTF-8") } catch (e: Exception) { expanded ?: text }
        coordsIn(decoded)?.let { (lat, lon) ->
            val label = Regex("""/place/([^/@?]+)""").find(decoded)?.groupValues?.get(1)?.replace('+', ' ')
                ?: if (url == null) text else reverse(lat, lon)
            return@withContext listOf(Place(label, lat, lon))
        }
        // a link to a place without coordinates: search for its name
        val q0 = Regex("""/place/([^/@?]+)""").find(decoded)?.groupValues?.get(1)?.replace('+', ' ')
            ?: Regex("""[?&]q=([^&]+)""").find(decoded)?.groupValues?.get(1)?.replace('+', ' ')
            ?: text.replace(URL, " ").trim()
        // 2. clean up typical Google Maps clutter (plus codes, country names, lone postcodes)
        val countries = setOf("österreich", "austria", "ausztria", "magyarország", "hungary", "deutschland", "germany", "németország")
        val cleaned = q0
            .replace(Regex("""\b[23456789CFGHJMPQRVWX]{4,8}\+[23456789CFGHJMPQRVWX]{2,3}\b"""), " ")
            .split(",").map { part ->
                part.split(" ").filter { it.isNotBlank() && it.lowercase() !in countries }.joinToString(" ").trim()
            }
            .filter { it.isNotEmpty() && !it.all { ch -> ch.isDigit() } }
        val fromLink = url != null
        val qTokens = tokens(cleaned.joinToString(" "))
        fun score(p: Place): Double {
            if (qTokens.isEmpty()) return 0.0
            val t = tokens(p.name)
            return qTokens.count { it in t }.toDouble() / qTokens.size
        }
        fun rank(list: List<Place>): List<Place> {
            if (list.isEmpty()) return list
            val scored = list.map { it to score(it) }.sortedByDescending { it.second }
            val best = scored.first().second
            return scored.filter { it.second >= best * 0.7 }.map { it.first }
        }
        // 3. an address in the text (street + number / postcode): try the strict address search first
        val addressParts = cleaned.filter { part -> part.any { it.isDigit() } }
        val withoutName = if (cleaned.size > 1 && cleaned.first().none { it.isDigit() }) cleaned.drop(1) else cleaned
        if (addressParts.isNotEmpty()) {
            for (a in listOf(withoutName.joinToString(", "), addressParts.joinToString(", "))) {
                val r = try { nominatim(a) } catch (e: Exception) { emptyList() }
                if (r.isNotEmpty()) {
                    // keep the place name from the link / query as label
                    val name = if (cleaned.size > 1 && cleaned.first().none { it.isDigit() }) cleaned.first() + ", " else ""
                    val out = r.map { it.copy(name = name + it.name) }
                    return@withContext if (fromLink) out.take(1) else out.take(3)
                }
            }
        }
        // 4. forgiving name search, shortened step by step; accept only good matches
        val attempts = LinkedHashSet<String>()
        attempts += cleaned.joinToString(", ")
        for (n in cleaned.size - 1 downTo 1) attempts += cleaned.take(n).joinToString(", ")
        if (cleaned.size > 1) attempts += withoutName.joinToString(", ")
        var fallback: List<Place> = emptyList()
        for (a in attempts) {
            if (a.isBlank()) continue
            val r = try { photon(a, near) } catch (e: Exception) { emptyList() }
            if (r.isEmpty()) continue
            val ranked = rank(r)
            if (score(ranked.first()) >= 0.6) return@withContext if (fromLink) ranked.take(1) else ranked
            if (fallback.isEmpty()) fallback = ranked
        }
        val nom = try { nominatim(cleaned.joinToString(", ")) } catch (e: Exception) { emptyList() }
        val result = if (nom.isNotEmpty()) rank(nom) else fallback
        if (fromLink) result.take(1) else result
    }

    private fun tokens(s: String): Set<String> {
        val norm = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        return norm.split(Regex("""[^a-z0-9]+""")).filter { it.length >= 2 && it !in setOf("utca", "ut", "str", "strasse", "gasse", "the", "und", "es") }.toSet()
    }

    /** Quick suggestions while typing. */
    suspend fun suggest(query: String, near: Place?): List<Place> = withContext(Dispatchers.IO) {
        if (query.trim().length < 3 || URL.containsMatchIn(query)) emptyList()
        else try { photon(query.trim(), near) } catch (e: Exception) { emptyList() }
    }

    fun chargeMinutes(fromSoc: Double, toSoc: Double, chargerKw: Int, vehicle: Vehicle): Double {
        var m = 0.0
        var s = fromSoc
        val cap = vehicle.capacityKwh
        while (s < toSoc - 1e-6) {
            val inc = min(1.0, toSoc - s)
            m += cap * inc / 100.0 / vehicle.chargeKw(s, chargerKw) * 60.0
            s += inc
        }
        return m
    }

    suspend fun plan(
        origin: Place,
        dest: Place,
        profile: Profile,
        sites: List<Site>,
        startSoc: Int,
        fixedStopIds: List<String> = emptyList(),
        excludedIds: Set<String> = emptySet(),
        optimize: String = "price",
        vias: List<Place> = emptyList(),
        startMillis: Long = System.currentTimeMillis(),
    ): Plan = withContext(Dispatchers.Default) {
        // ---- 1. route (cached: changing stops or battery does not need a new route)
        val all = listOf(origin) + vias + listOf(dest)
        val key = all.joinToString(";") { "${it.lat},${it.lon}" }
        val rd = routeCache?.takeIf { it.first == key }?.second ?: run {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                all.joinToString(";") { "${it.lon},${it.lat}" } + "?overview=full&geometries=polyline6&annotations=distance,duration"
            val body = withContext(Dispatchers.IO) { Http.get(url) }
            val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            val legs = route.optJSONArray("legs")
            val legKm = if (legs == null) emptyList() else (0 until legs.length()).map { legs.getJSONObject(it).getDouble("distance") / 1000.0 }
            val segM = ArrayList<Double>(); val segS = ArrayList<Double>()
            if (legs != null) for (i in 0 until legs.length()) {
                val an = legs.getJSONObject(i).optJSONObject("annotation") ?: continue
                val dA = an.optJSONArray("distance"); val tA = an.optJSONArray("duration")
                if (dA == null || tA == null) continue
                for (j in 0 until dA.length()) { segM += dA.getDouble(j); segS += tA.optDouble(j, 0.0) }
            }
            RouteData(route.getDouble("distance") / 1000.0, route.getDouble("duration"),
                Geo.decodePolyline(route.getString("geometry"), 6), legKm, segM, segS).also { routeCache = key to it }
        }
        val totalKm = rd.km
        val totalSec = rd.sec
        val poly = rd.poly
        val viaKm = rd.legKm.runningReduce { a, b -> a + b }.take(vias.size)

        // ---- 2. resample to ~1 km steps with cumulative distance
        val pts = ArrayList<RP>()
        var acc = 0.0
        var lastKept = -SAMPLE_KM
        for (i in poly.indices) {
            if (i > 0) acc += Geo.fastKm(poly[i - 1][0], poly[i - 1][1], poly[i][0], poly[i][1])
            if (acc - lastKept >= SAMPLE_KM || i == poly.lastIndex) {
                pts += RP(poly[i][0], poly[i][1], acc, 0.0); lastKept = acc
            }
        }
        val scale = if (acc > 0) totalKm / acc else 1.0
        val rps = pts.map { RP(it.lat, it.lon, it.km * scale, totalSec * it.km / acc.coerceAtLeast(0.001)) }

        // ---- 3. chargers in the corridor
        val minLat = rps.minOf { it.lat } - 0.06; val maxLat = rps.maxOf { it.lat } + 0.06
        val minLon = rps.minOf { it.lon } - 0.09; val maxLon = rps.maxOf { it.lon } + 0.09
        val cands = ArrayList<Candidate>()
        for (s in sites) {
            if (s.maxKw < profile.routeMinKw) continue
            if (s.op in profile.excludedOps) continue
            if (s.lat < minLat || s.lat > maxLat || s.lon < minLon || s.lon > maxLon) continue
            var best = Double.MAX_VALUE; var bestRp: RP? = null
            for (rp in rps) {
                val d = Geo.fastKm(s.lat, s.lon, rp.lat, rp.lon)
                if (d < best) { best = d; bestRp = rp }
            }
            if (bestRp != null && best <= CORRIDOR_KM) cands += Candidate(s, bestRp.km, best, bestRp.sec)
        }
        cands.sortBy { it.alongKm }

        // ---- 4. energy model: speed dependent consumption along the route
        val vehicle = profile.vehicle
        val cap = vehicle.capacityKwh
        val cumKm = DoubleArray(rd.segM.size + 1)
        val cumKwh = DoubleArray(rd.segM.size + 1)
        if (rd.segM.isNotEmpty()) {
            val f = totalKm / (rd.segM.sum() / 1000.0).coerceAtLeast(0.001)
            for (i in rd.segM.indices) {
                val km = rd.segM[i] / 1000.0 * f
                val routerKmh = if (rd.segS[i] > 0) rd.segM[i] / rd.segS[i] * 3.6 else 50.0
                val v = EnergyModel.drivenSpeed(routerKmh, profile)
                cumKm[i + 1] = cumKm[i] + km
                cumKwh[i + 1] = cumKwh[i] + km * EnergyModel.kwhPerKm(vehicle, profile, v)
            }
        }
        val avgKwhPerKm = if (cumKm.last() > 0) cumKwh.last() / cumKm.last()
            else EnergyModel.kwhPerKm(vehicle, profile, EnergyModel.drivenSpeed(totalKm / (totalSec / 3600).coerceAtLeast(0.01), profile))
        /** cumulative kWh at a km along the route */
        fun energyAt(km: Double): Double {
            if (cumKm.last() <= 0) return km * avgKwhPerKm
            if (km <= 0) return 0.0
            if (km >= cumKm.last()) return cumKwh.last() + (km - cumKm.last()) * avgKwhPerKm
            var lo = 0; var hi = cumKm.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (cumKm[mid] <= km) lo = mid else hi = mid }
            val t = (km - cumKm[lo]) / (cumKm[hi] - cumKm[lo]).coerceAtLeast(1e-9)
            return cumKwh[lo] + t * (cumKwh[hi] - cumKwh[lo])
        }
        /** km along the route where the cumulative energy reaches [e] */
        fun kmAtEnergy(e: Double): Double {
            if (cumKm.last() <= 0) return e / avgKwhPerKm
            if (e >= cumKwh.last()) return cumKm.last() + (e - cumKwh.last()) / avgKwhPerKm
            var lo = 0; var hi = cumKwh.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (cumKwh[mid] <= e) lo = mid else hi = mid }
            val t = (e - cumKwh[lo]) / (cumKwh[hi] - cumKwh[lo]).coerceAtLeast(1e-9)
            return cumKm[lo] + t * (cumKm[hi] - cumKm[lo])
        }
        fun socUsed(fromKm: Double, toKm: Double) = (energyAt(toKm) - energyAt(fromKm)) / cap * 100.0
        fun detourSoc(c: Candidate) = c.offKm * 1.3 * avgKwhPerKm / cap * 100.0
        val reserve = profile.reservePct.toDouble()
        val maxSoc = profile.maxChargeSoc.toDouble()
        val loss = 1 + profile.lossPct / 100.0
        fun detourKm(c: Candidate) = c.offKm * 2 * 1.3
        fun detourSec(c: Candidate) = detourKm(c) / 50.0 * 3600

        val views = cands.map { c ->
            val eta = startMillis + (c.routeSec * 1000).toLong()
            val a = startSoc - socUsed(0.0, c.alongKm) - detourSoc(c)
            CandidateView(c, eta, a, a >= reserve, Pricing.quote(c.site, profile.routeMinKw, profile, eta)
                ?: Pricing.unknownQuote(c.site, profile.routeMinKw, profile))
        }

        // ---- 5. greedy stop selection: charge only as much as needed, at the best price in reach
        val stops = ArrayList<ChargeStop>()
        var pos = 0.0
        var soc = startSoc.toDouble()
        var delaySec = 0.0
        var feasible = true
        var note: String? = null
        // ---- 5a. stops picked by hand, in route order; charge just enough for the next leg
        val fixed = fixedStopIds.mapNotNull { id -> cands.firstOrNull { it.site.id == id } }.sortedBy { it.alongKm }
        for ((i, c) in fixed.withIndex()) {
            val eta = startMillis + ((c.routeSec + delaySec) * 1000).toLong()
            val q = Pricing.quote(c.site, profile.routeMinKw, profile, eta)
                ?: Pricing.unknownQuote(c.site, profile.routeMinKw, profile)
                ?: Quote(0.0, 0.0, 0.0, null, null, c.site.maxKw)
            val arrival = soc - socUsed(pos, c.alongKm) - detourSoc(c)
            if (arrival < reserve) {
                note = "${c.site.name} csak ${arrival.toInt()}%-kal érhető el."
                if (arrival < 0) feasible = false
            }
            val nextKm = if (i < fixed.lastIndex) fixed[i + 1].alongKm else totalKm
            val need = reserve + socUsed(c.alongKm, nextKm) + detourSoc(c) + 2.0
            val target = ceil(min(max(need, arrival), maxSoc)).coerceAtLeast(arrival)
            val kwhBat = (target - arrival) / 100.0 * cap
            val mins = chargeMinutes(arrival, target, q.kw, vehicle)
            val cost = kwhBat * loss * q.energyEur + (q.eurMin ?: 0.0) * mins +
                (if (kwhBat > 0) (c.site.startFeeLocal ?: 0.0) / c.site.fx else 0.0)
            val st = ChargeStop(c, eta, arrival, target, kwhBat, kwhBat * loss, mins, q, cost)
            stops += st
            delaySec += mins * 60 + detourSec(c) + 300
            pos = c.alongKm
            soc = target - detourSoc(c)
        }

        // ---- 5b. automatic stops: search combinations of stops and pick the best whole trip
        //      price mode: total cost (+ a little for time); time mode: total time (+ a little for cost)
        val stopOverheadMin = 5.0
        fun evaluate(pos0: Double, soc0: Double, delay0: Double, strict: Boolean): List<StopOption> {
            val reachKm = kmAtEnergy(energyAt(pos0) + (soc0 - reserve) / 100.0 * cap)
            return cands.asSequence()
                .filter { it.alongKm > pos0 + 3 && it.alongKm + it.offKm * 1.3 <= reachKm && it.site.id !in excludedIds }
                .mapNotNull { c ->
                    val eta = startMillis + ((c.routeSec + delay0) * 1000).toLong()
                    // chargers without a published price stay usable (Italy, Croatia …) with an assumed price
                    val q = Pricing.quote(c.site, profile.routeMinKw, profile, eta)
                        ?: Pricing.unknownQuote(c.site, profile.routeMinKw, profile) ?: return@mapNotNull null
                    val arrival = soc0 - socUsed(pos0, c.alongKm) - detourSoc(c)
                    if (arrival < reserve) return@mapNotNull null
                    // only stop where charging is actually needed (no "top up 1 %" stops)
                    if (strict && arrival > 60.0) return@mapNotNull null
                    val need = reserve + socUsed(c.alongKm, totalKm) + detourSoc(c) + 2.0
                    val target = ceil(min(max(need, arrival + 10), maxSoc))
                    if (strict && target - arrival < 10) return@mapNotNull null
                    val kwhBat = (target - arrival) / 100.0 * cap
                    val billed = kwhBat * loss
                    val mins = chargeMinutes(arrival, target, q.kw, vehicle)
                    val cost = billed * q.energyEur + (q.eurMin ?: 0.0) * mins + ((c.site.startFeeLocal ?: 0.0) / c.site.fx)
                    StopOption(ChargeStop(c, eta, arrival, target, kwhBat, billed, mins, q, cost),
                        lostMin = mins + detourSec(c) / 60 + stopOverheadMin, extraStop = need > maxSoc)
                }.toList()
        }
        fun score(cost: Double, minutes: Double) = if (optimize == "time") minutes + cost * 0.5 else cost + minutes * 0.1

        class Tail(val stops: List<ChargeStop>, val cost: Double, val minutes: Double, val soc: Double, val pos: Double, val delay: Double)
        fun search(pos0: Double, soc0: Double, delay0: Double, depth: Int): Tail? {
            if (soc0 - socUsed(pos0, totalKm) >= reserve) return Tail(emptyList(), 0.0, 0.0, soc0, pos0, delay0)
            if (depth >= 5) return null
            var evals = evaluate(pos0, soc0, delay0, strict = true)
            if (evals.isEmpty()) evals = evaluate(pos0, soc0, delay0, strict = false)
            if (evals.isEmpty()) return null
            val width = when (depth) { 0 -> 9; 1 -> 6; else -> 4 }
            val pruned = if (evals.size <= width) evals else (
                evals.sortedBy { it.stop.quote.eur }.take(width / 2 + 1) +
                    evals.sortedBy { it.lostMin }.take(width / 3 + 1) +
                    evals.sortedByDescending { it.stop.cand.alongKm }.take(width / 3 + 1)
                ).distinct()
            var best: Tail? = null
            var bestScore = Double.MAX_VALUE
            for (o in pruned) {
                val st = o.stop
                val rest = search(st.cand.alongKm, st.targetSoc - detourSoc(st.cand),
                    delay0 + o.lostMin * 60, depth + 1) ?: continue
                val cost = st.cost + rest.cost
                val minutes = o.lostMin + rest.minutes
                val sc = score(cost, minutes)
                if (sc < bestScore) {
                    bestScore = sc
                    best = Tail(listOf(st) + rest.stops, cost, minutes, rest.soc, rest.pos, rest.delay)
                }
            }
            return best
        }
        val tail = search(pos, soc, delaySec, 0)
        if (tail == null) {
            feasible = false
            note = "A tartalék megtartásával nincs elérhető ${profile.routeMinKw}+ kW-os töltő az útvonalon. Tölts többet indulás előtt, vagy csökkentsd a minimális teljesítményt a profilban."
        } else {
            stops += tail.stops
            pos = tail.pos
            soc = tail.soc
            delaySec = tail.delay
        }
        val arrivalSoc = soc - socUsed(pos, totalKm)
        Plan(
            origin, dest, totalKm, totalSec, startSoc, startMillis, stops, arrivalSoc,
            startMillis + ((totalSec + delaySec) * 1000).toLong(), feasible, note, views,
            fixedStopIds, excludedIds, optimize, simplify(poly), vias, viaKm,
            avgKwh100 = energyAt(totalKm) / totalKm.coerceAtLeast(0.001) * 100,
        )
    }
}
