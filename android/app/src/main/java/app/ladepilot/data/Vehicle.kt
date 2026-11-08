package app.ladepilot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * A car the user plans with. Base data from Open EV Data
 * (https://github.com/KilowattApp/open-ev-data, MIT with attribution).
 */
data class Vehicle(
    val id: String,
    val name: String,
    /** usable battery capacity when new, kWh */
    val usableKwh: Double,
    /** average (WLTP-like) consumption, kWh/100 km */
    val avgCons: Double,
    val dcMaxKw: Double,
    val acMaxKw: Double,
    /** coarse charging curve from the database: (SoC %, kW) */
    val curve: List<Pair<Double, Double>> = emptyList(),
    /** battery state of health, % (e.g. 91.9 after a battery test) */
    val sohPct: Double = 100.0,
) {
    /** capacity actually available for planning */
    val capacityKwh: Double get() = usableKwh * sohPct / 100.0

    /** Typical DC charging power at a given SoC, never above what the charger delivers. */
    fun chargeKw(socPct: Double, chargerKw: Int): Double {
        // realistic taper shape (based on measured Model Y / Model 3 curves), scaled to the car's peak
        val shape = when {
            socPct < 20 -> 0.92; socPct < 30 -> 0.80; socPct < 40 -> 0.68; socPct < 50 -> 0.56
            socPct < 60 -> 0.46; socPct < 70 -> 0.38; socPct < 80 -> 0.30; socPct < 90 -> 0.20; else -> 0.12
        }
        var kw = dcMaxKw * shape
        // the database curve can only lower it (it is usually optimistic)
        if (curve.size >= 2) {
            val c = curve.sortedBy { it.first }
            val hi = c.indexOfFirst { it.first >= socPct }
            val db = when {
                hi <= 0 -> c.first().second
                else -> {
                    val (x0, y0) = c[hi - 1]; val (x1, y1) = c[hi]
                    if (x1 == x0) y1 else y0 + (y1 - y0) * (socPct - x0) / (x1 - x0)
                }
            }
            kw = min(kw, db)
        }
        return min(kw, chargerKw * 0.95).coerceAtLeast(3.0)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kwh", usableKwh); put("avg", avgCons)
        put("dc", dcMaxKw); put("ac", acMaxKw); put("soh", sohPct)
        put("curve", JSONArray().apply { curve.forEach { put(JSONArray().put(it.first).put(it.second)) } })
    }

    companion object {
        val DEFAULT = Vehicle(
            "3012e28f-2dc0-4b2b-9bab-2fe03279b082", "Tesla Model Y Long Range Dual Motor (2022)",
            75.0, 16.9, 250.0, 11.0, listOf(0.0 to 250.0, 50.0 to 250.0, 80.0 to 100.0, 100.0 to 25.0),
        )

        fun fromJson(o: JSONObject) = Vehicle(
            o.getString("id"), o.getString("name"), o.getDouble("kwh"), o.getDouble("avg"),
            o.optDouble("dc", 50.0), o.optDouble("ac", 11.0),
            o.optJSONArray("curve")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } } } ?: emptyList(),
            o.optDouble("soh", 100.0),
        )
    }
}

/** Searchable list of cars shipped with the app (assets/cars.json). */
object CarDb {
    data class Entry(val id: String, val brand: String, val model: String, val variant: String, val year: Int?,
                     val kwh: Double, val avg: Double, val dc: Double, val ac: Double, val curve: List<Pair<Double, Double>>) {
        val title get() = listOf(brand, model, variant).filter { it.isNotBlank() }.joinToString(" ") + (year?.let { " ($it)" } ?: "")
        fun toVehicle() = Vehicle(id, title, kwh, avg, dc, ac, curve)
    }

    @Volatile private var cache: List<Entry>? = null

    fun all(ctx: Context): List<Entry> {
        cache?.let { return it }
        val text = ctx.assets.open("cars.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("cars")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                o.getString("id"), o.getString("b"), o.getString("m"), o.optString("v"),
                if (o.isNull("y")) null else o.optInt("y"),
                o.getDouble("kwh"), o.getDouble("avg"), o.optDouble("dc", 50.0), o.optDouble("ac", 11.0),
                o.optJSONArray("curve")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } } } ?: emptyList(),
            )
        }
        cache = list
        return list
    }

    fun search(ctx: Context, q: String): List<Entry> {
        val words = q.lowercase().split(" ").filter { it.isNotBlank() }
        return all(ctx).filter { e -> words.all { w -> e.title.lowercase().contains(w) } }.take(60)
    }
}

/**
 * Speed dependent consumption model.
 * The database gives an average (WLTP-like) value; at 120 km/h real cars use ~12 % more,
 * and air drag (~55 % of highway consumption) grows with the square of speed.
 */
object EnergyModel {
    fun isWinter(profile: Profile): Boolean = when (profile.season) {
        "summer" -> false
        "winter" -> true
        else -> LocalDate.now().monthValue.let { it >= 11 || it <= 3 }
    }

    /** kWh per km at a given driving speed. */
    fun kwhPerKm(v: Vehicle, profile: Profile, speedKmh: Double): Double {
        val c120 = v.avgCons * 1.12
        val per100 = if (speedKmh < 60) {
            c120 * 0.78                                   // stop-and-go city driving
        } else {
            val r = speedKmh / 120.0
            c120 * (0.45 + 0.55 * r * r)
        }
        val season = if (isWinter(profile)) 1.15 else 1.0
        return per100 * season * (1 + profile.correctionPct / 100.0) * (1 + profile.marginPct / 100.0) / 100.0
    }

    /** Speed actually driven on a road segment, from the router's average speed. */
    fun drivenSpeed(routerKmh: Double, profile: Profile): Double = when {
        routerKmh >= 75 -> profile.highwayKmh.toDouble()             // motorway / expressway
        routerKmh >= 55 -> min(100.0, max(80.0, routerKmh * 1.15))   // rural roads (HU 90, AT 100)
        else -> routerKmh                                            // towns
    }
}
