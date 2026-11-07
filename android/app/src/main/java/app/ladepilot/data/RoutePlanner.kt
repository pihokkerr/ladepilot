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
    val forcedFirstId: String?,
    /** "price" or "time" */
    val optimize: String,
) {
    val totalCost: Double get() = stops.sumOf { it.cost }
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
        forcedFirstId: String? = null,
        optimize: String = "price",
        startMillis: Long = System.currentTimeMillis(),
    ): Plan = withContext(Dispatchers.Default) {
        // ---- 1. route
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "${origin.lon},${origin.lat};${dest.lon},${dest.lat}?overview=full&geometries=polyline6"
        val body = withContext(Dispatchers.IO) { Http.get(url) }
        val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
        val totalKm = route.getDouble("distance") / 1000.0
        val totalSec = route.getDouble("duration")
        val poly = Geo.decodePolyline(route.getString("geometry"), 6)

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
        var guard = 0
        while (guard++ < 6) {
            val socAtDest = soc - (totalKm - pos) * socPerKm
            if (socAtDest >= reserve) break
            val reachKm = pos + (soc - reserve) / socPerKm
            var options = cands.filter { it.alongKm > pos + 3 && it.alongKm + it.offKm * 1.3 <= reachKm }
            if (stops.isEmpty() && forcedFirstId != null) {
                val forced = options.firstOrNull { it.site.id == forcedFirstId }
                if (forced != null) options = listOf(forced)
                else note = "A kiválasztott töltő a tartalékkal nem érhető el – másikat választottam."
            }
            if (options.isEmpty()) {
                feasible = false
                note = "A tartalék megtartásával nincs elérhető ${profile.routeMinKw}+ kW-os töltő az útvonalon. Tölts többet indulás előtt, vagy csökkentsd a minimális teljesítményt a profilban."
                break
            }
            fun evaluate(strict: Boolean): List<StopOption> = options.mapNotNull { c ->
                val eta = startMillis + ((c.routeSec + delaySec) * 1000).toLong()
                val q = Pricing.quote(c.site, profile.routeMinKw, profile, eta) ?: return@mapNotNull null
                val arrival = soc - (c.alongKm - pos + c.offKm * 1.3) * socPerKm
                val forced = stops.isEmpty() && c.site.id == forcedFirstId
                // only stop where charging is actually needed (no "top up 1 %" stops)
                if (strict && !forced && arrival > 60.0) return@mapNotNull null
                val need = reserve + (totalKm - c.alongKm + c.offKm * 1.3) * socPerKm + 2.0
                val target = ceil(min(max(need, arrival + 10), maxSoc))
                if (strict && !forced && target - arrival < 10) return@mapNotNull null
                val kwhBat = (target - arrival) / 100.0 * cap
                val billed = kwhBat * loss
                val mins = chargeMinutes(arrival, target, q.kw, cap)
                val cost = billed * q.energyEur + (q.eurMin ?: 0.0) * mins +
                    ((c.site.startFeeLocal ?: 0.0) / c.site.fx)
                StopOption(
                    ChargeStop(c, eta, arrival, target, kwhBat, billed, mins, q, cost),
                    lostMin = mins + detourSec(c) / 60, extraStop = need > maxSoc,
                )
            }
            var evals = evaluate(strict = true)
            if (evals.isEmpty()) evals = evaluate(strict = false)
            val chosen = when {
                evals.isEmpty() -> null
                optimize == "time" -> {
                    // fastest overall, but among options within 5 minutes of it take the cheapest
                    fun lost(o: StopOption) = o.lostMin + if (o.extraStop) 25.0 else 0.0
                    val fastest = evals.minOf { lost(it) }
                    evals.filter { lost(it) <= fastest + 5.0 }.minByOrNull { it.stop.quote.eur }
                }
                else -> evals.minByOrNull {
                    it.stop.quote.eur + (if (it.extraStop) 0.08 else 0.0) + it.stop.cand.offKm * 0.004 + it.stop.minutes * 0.0008
                }
            }
            val bestStop = chosen?.stop
            val st = bestStop ?: run {
                feasible = false
                note = "Az elérhető töltőkhöz nincs árinformáció."
                null
            } ?: break
            stops += st
            delaySec += st.minutes * 60 + detourSec(st.cand)
            pos = st.cand.alongKm
            soc = st.targetSoc - st.cand.offKm * 1.3 * socPerKm
        }
        val arrivalSoc = soc - (totalKm - pos) * socPerKm
        Plan(
            origin, dest, totalKm, totalSec, startSoc, startMillis, stops, arrivalSoc,
            startMillis + ((totalSec + delaySec) * 1000).toLong(), feasible, note, views, forcedFirstId, optimize,
        )
    }
}
