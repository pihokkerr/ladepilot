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
    private class RouteData(val km: Double, val sec: Double, val poly: List<DoubleArray>, val legKm: List<Double>)
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

    suspend fun geocode(query: String): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = Http.get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&accept-language=hu&q=$q")
        val arr = JSONArray(body)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val parts = o.optString("display_name").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val name = parts.take(3).joinToString(", ")
            Place(name, o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
    }

    /** Typical Model Y LR charge curve (kW available at a given SoC on a fast charger). */
    private fun curveKw(soc: Double): Double = when {
        soc < 20 -> 230.0
        soc < 30 -> 200.0
        soc < 40 -> 170.0
        soc < 50 -> 140.0
        soc < 60 -> 115.0
        soc < 70 -> 95.0
        soc < 80 -> 75.0
        soc < 90 -> 50.0
        else -> 30.0
    }

    fun chargeMinutes(fromSoc: Double, toSoc: Double, chargerKw: Int, capacityKwh: Double): Double {
        var m = 0.0
        var s = fromSoc
        val step = 1.0
        while (s < toSoc - 1e-6) {
            val inc = min(step, toSoc - s)
            val kw = min(chargerKw * 0.95, curveKw(s)).coerceAtLeast(5.0)
            m += capacityKwh * inc / 100.0 / kw * 60.0
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
                all.joinToString(";") { "${it.lon},${it.lat}" } + "?overview=full&geometries=polyline6"
            val body = withContext(Dispatchers.IO) { Http.get(url) }
            val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            val legs = route.optJSONArray("legs")
            val legKm = if (legs == null) emptyList() else (0 until legs.length()).map { legs.getJSONObject(it).getDouble("distance") / 1000.0 }
            RouteData(route.getDouble("distance") / 1000.0, route.getDouble("duration"),
                Geo.decodePolyline(route.getString("geometry"), 6), legKm).also { routeCache = key to it }
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

        // ---- 4. energy model
        val cap = profile.capacityKwh
        val kwhPerKm = profile.consumption() / 100.0
        val socPerKm = kwhPerKm / cap * 100.0
        val reserve = profile.reservePct.toDouble()
        val maxSoc = profile.maxChargeSoc.toDouble()
        val loss = 1 + profile.lossPct / 100.0
        fun detourKm(c: Candidate) = c.offKm * 2 * 1.3
        fun detourSec(c: Candidate) = detourKm(c) / 50.0 * 3600

        val views = cands.map { c ->
            val eta = startMillis + (c.routeSec * 1000).toLong()
            val a = startSoc - (c.alongKm + c.offKm * 1.3) * socPerKm
            CandidateView(c, eta, a, a >= reserve, Pricing.quote(c.site, profile.routeMinKw, profile, eta))
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
                ?: Quote(0.0, 0.0, 0.0, null, null, c.site.maxKw)
            val arrival = soc - (c.alongKm - pos + c.offKm * 1.3) * socPerKm
            if (arrival < reserve) {
                note = "${c.site.name} csak ${arrival.toInt()}%-kal érhető el."
                if (arrival < 0) feasible = false
            }
            val nextKm = if (i < fixed.lastIndex) fixed[i + 1].alongKm else totalKm
            val need = reserve + (nextKm - c.alongKm + c.offKm * 1.3) * socPerKm + 2.0
            val target = ceil(min(max(need, arrival), maxSoc)).coerceAtLeast(arrival)
            val kwhBat = (target - arrival) / 100.0 * cap
            val mins = chargeMinutes(arrival, target, q.kw, cap)
            val cost = kwhBat * loss * q.energyEur + (q.eurMin ?: 0.0) * mins +
                (if (kwhBat > 0) (c.site.startFeeLocal ?: 0.0) / c.site.fx else 0.0)
            val st = ChargeStop(c, eta, arrival, target, kwhBat, kwhBat * loss, mins, q, cost)
            stops += st
            delaySec += mins * 60 + detourSec(c) + 300
            pos = c.alongKm
            soc = target - c.offKm * 1.3 * socPerKm
        }

        // ---- 5b. automatic stops: search combinations of stops and pick the best whole trip
        //      price mode: total cost (+ a little for time); time mode: total time (+ a little for cost)
        val stopOverheadMin = 5.0
        fun evaluate(pos0: Double, soc0: Double, delay0: Double, strict: Boolean): List<StopOption> {
            val reachKm = pos0 + (soc0 - reserve) / socPerKm
            return cands.asSequence()
                .filter { it.alongKm > pos0 + 3 && it.alongKm + it.offKm * 1.3 <= reachKm && it.site.id !in excludedIds }
                .mapNotNull { c ->
                    val eta = startMillis + ((c.routeSec + delay0) * 1000).toLong()
                    val q = Pricing.quote(c.site, profile.routeMinKw, profile, eta) ?: return@mapNotNull null
                    val arrival = soc0 - (c.alongKm - pos0 + c.offKm * 1.3) * socPerKm
                    // only stop where charging is actually needed (no "top up 1 %" stops)
                    if (strict && arrival > 60.0) return@mapNotNull null
                    val need = reserve + (totalKm - c.alongKm + c.offKm * 1.3) * socPerKm + 2.0
                    val target = ceil(min(max(need, arrival + 10), maxSoc))
                    if (strict && target - arrival < 10) return@mapNotNull null
                    val kwhBat = (target - arrival) / 100.0 * cap
                    val billed = kwhBat * loss
                    val mins = chargeMinutes(arrival, target, q.kw, cap)
                    val cost = billed * q.energyEur + (q.eurMin ?: 0.0) * mins + ((c.site.startFeeLocal ?: 0.0) / c.site.fx)
                    StopOption(ChargeStop(c, eta, arrival, target, kwhBat, billed, mins, q, cost),
                        lostMin = mins + detourSec(c) / 60 + stopOverheadMin, extraStop = need > maxSoc)
                }.toList()
        }
        fun score(cost: Double, minutes: Double) = if (optimize == "time") minutes + cost * 0.5 else cost + minutes * 0.1

        class Tail(val stops: List<ChargeStop>, val cost: Double, val minutes: Double, val soc: Double, val pos: Double, val delay: Double)
        fun search(pos0: Double, soc0: Double, delay0: Double, depth: Int): Tail? {
            if (soc0 - (totalKm - pos0) * socPerKm >= reserve) return Tail(emptyList(), 0.0, 0.0, soc0, pos0, delay0)
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
                val rest = search(st.cand.alongKm, st.targetSoc - st.cand.offKm * 1.3 * socPerKm,
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
        val arrivalSoc = soc - (totalKm - pos) * socPerKm
        Plan(
            origin, dest, totalKm, totalSec, startSoc, startMillis, stops, arrivalSoc,
            startMillis + ((totalSec + delaySec) * 1000).toLong(), feasible, note, views,
            fixedStopIds, excludedIds, optimize, simplify(poly), vias, viaKm,
        )
    }
}
