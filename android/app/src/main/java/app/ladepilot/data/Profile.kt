package app.ladepilot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A charging card / subscription the user has.
 * [value] is in the site's local currency: Ft for Hungary, EUR for Austria/Germany.
 */
data class Tariff(
    val id: String,
    val name: String,
    /** "HU", "AT", "DE" or "*" */
    val country: String,
    /** Case-insensitive text that must appear in the operator or site name, e.g. "Mobiliti" */
    val match: String,
    /** "discount" = value off per kWh, "fixed" = price per kWh, "percent" = % off */
    val mode: String,
    val value: Double,
    val enabled: Boolean,
    val note: String = "",
)

data class Profile(
    /** saved cars; the first one with [activeVehicleId] is used for planning */
    val vehicles: List<Vehicle> = listOf(Vehicle.DEFAULT),
    val activeVehicleId: String = Vehicle.DEFAULT.id,
    /** speed driven on motorways, km/h */
    val highwayKmh: Int = 130,
    /** personal correction of the consumption model, % (+ = I use more) */
    val correctionPct: Int = 0,
    /** "auto" (Nov–Mar = winter), "summer", "winter" */
    val season: String = "auto",
    /** extra safety margin on consumption, % */
    val marginPct: Int = 5,
    /** minimum state of charge at every arrival, % */
    val reservePct: Int = 10,
    val startSoc: Int = 80,
    /** max SoC we plan to charge to (above ~80 % charging gets slow) */
    val maxChargeSoc: Int = 90,
    /** charging losses billed on top of what ends up in the battery, % */
    val lossPct: Int = 7,
    val minKw: Int = 50,
    val routeMinKw: Int = 100,
    /** route stop choice: "price" (cheapest) or "time" (fastest) */
    val optimize: String = "price",
    /** "dark", "liberty" (colourful), "light" */
    val mapStyle: String = "dark",
    /** operators hidden on the map and ignored by the planner */
    val excludedOps: Set<String> = emptySet(),
    val baseUrl: String = "https://ladepilot.pages.dev",
    val tariffs: List<Tariff> = Tariffs.presets,
) {
    val vehicle: Vehicle get() = vehicles.firstOrNull { it.id == activeVehicleId } ?: vehicles.firstOrNull() ?: Vehicle.DEFAULT
    val capacityKwh: Double get() = vehicle.capacityKwh

    /** motorway consumption at the chosen speed, kWh/100 km (incl. season, correction, margin) */
    fun consumption(): Double = EnergyModel.kwhPerKm(vehicle, this, highwayKmh.toDouble()) * 100
}

object Tariffs {
    /** Hungarian offers listed by OTPR. Disabled by default – switch on what you have. */
    val presets = listOf(
        Tariff("hu-mobiliti-vip", "Mobiliti VIP", "HU", "Mobiliti", "discount", 26.0, false, "2990 Ft / 30 nap"),
        Tariff("hu-mol-green", "MOL Plugee Green/Silver", "HU", "MOL Plugee", "discount", 10.0, false),
        Tariff("hu-mol-gold", "MOL Plugee Gold/VIP", "HU", "MOL Plugee", "discount", 20.0, false),
        Tariff("hu-lidl-plus", "Lidl Plus", "HU", "Lidl", "discount", 50.0, false),
    )
}

object ProfileStore {
    private const val PREFS = "ladepilot"
    private const val KEY = "profile"
    @Volatile private var cached: Profile? = null

    fun load(ctx: Context): Profile {
        cached?.let { return it }
        val s = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val p = try { if (s == null) Profile() else fromJson(JSONObject(s)) } catch (e: Exception) { Profile() }
        cached = p
        return p
    }

    fun save(ctx: Context, p: Profile) {
        cached = p
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, toJson(p).toString()).apply()
    }

    private fun toJson(p: Profile) = JSONObject().apply {
        put("vehicles", JSONArray().apply { p.vehicles.forEach { put(it.toJson()) } })
        put("activeVehicleId", p.activeVehicleId); put("highwayKmh", p.highwayKmh); put("correctionPct", p.correctionPct)
        put("season", p.season); put("marginPct", p.marginPct); put("reservePct", p.reservePct)
        put("startSoc", p.startSoc); put("maxChargeSoc", p.maxChargeSoc); put("lossPct", p.lossPct)
        put("minKw", p.minKw); put("routeMinKw", p.routeMinKw); put("optimize", p.optimize); put("mapStyle", p.mapStyle); put("excludedOps", JSONArray(p.excludedOps.toList())); put("baseUrl", p.baseUrl)
        put("tariffs", JSONArray().apply {
            p.tariffs.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id); put("name", t.name); put("country", t.country); put("match", t.match)
                    put("mode", t.mode); put("value", t.value); put("enabled", t.enabled); put("note", t.note)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): Profile {
        val d = Profile()
        val saved = o.optJSONArray("tariffs")?.let { a ->
            (0 until a.length()).map { i ->
                val t = a.getJSONObject(i)
                Tariff(
                    t.optString("id"), t.optString("name"), t.optString("country", "*"), t.optString("match"),
                    t.optString("mode", "discount"), t.optDouble("value", 0.0), t.optBoolean("enabled", false),
                    t.optString("note", ""),
                )
            }
        } ?: emptyList()
        // keep presets that were added in newer app versions
        val ids = saved.map { it.id }.toSet()
        val tariffs = saved + Tariffs.presets.filter { it.id !in ids }
        val vehicles = o.optJSONArray("vehicles")?.let { a -> (0 until a.length()).mapNotNull { i -> try { Vehicle.fromJson(a.getJSONObject(i)) } catch (e: Exception) { null } } }
            ?.takeIf { it.isNotEmpty() } ?: d.vehicles
        return Profile(
            vehicles = vehicles,
            activeVehicleId = o.optString("activeVehicleId", vehicles.first().id),
            highwayKmh = o.optInt("highwayKmh", d.highwayKmh),
            correctionPct = o.optInt("correctionPct", d.correctionPct),
            season = o.optString("season", d.season),
            marginPct = if (o.has("vehicles")) o.optInt("marginPct", d.marginPct) else d.marginPct,
            reservePct = o.optInt("reservePct", d.reservePct),
            startSoc = o.optInt("startSoc", d.startSoc),
            maxChargeSoc = o.optInt("maxChargeSoc", d.maxChargeSoc),
            lossPct = o.optInt("lossPct", d.lossPct),
            minKw = o.optInt("minKw", d.minKw),
            routeMinKw = o.optInt("routeMinKw", d.routeMinKw),
            optimize = o.optString("optimize", d.optimize),
            mapStyle = o.optString("mapStyle", d.mapStyle),
            excludedOps = o.optJSONArray("excludedOps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
            baseUrl = o.optString("baseUrl", d.baseUrl).ifBlank { d.baseUrl },
            tariffs = tariffs,
        )
    }
}
