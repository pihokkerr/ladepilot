package app.ladepilot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One price rule of a tariff. The first rule whose country and operator match a site is used.
 * Values are in the local currency of the rule's country (Ft in Hungary, EUR elsewhere).
 */
data class TariffRule(
    /** comma separated country codes ("AT,DE") or "*" */
    val countries: String,
    /** case-insensitive text in operator or site name; blank = every operator (roaming) */
    val match: String,
    /** "fixed" = price per kWh, "discount" = off the ad-hoc price, "percent" = % off */
    val mode: String,
    /** AC (≤ 43 kW) value */
    val ac: Double?,
    /** DC value (also used for HPC when [hpc] is null) */
    val dc: Double?,
    /** HPC (≥ 150 kW) value */
    val hpc: Double? = null,
) {
    fun valueFor(kw: Int): Double? = when {
        kw <= 43 -> ac
        kw >= 150 -> hpc ?: dc
        else -> dc
    }
    fun appliesTo(country: String): Boolean = countries == "*" || countries.split(',').any { it.trim() == country }
}

/**
 * A charging card / subscription the user has.
 * Simple user tariffs use [country]/[match]/[mode]/[value]; catalog presets use [rules].
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
    /** monthly fee (in the home country's currency), 0 = none */
    val fee: Double = 0.0,
    val rules: List<TariffRule> = emptyList(),
    /** where the prices come from + date */
    val source: String = "",
    /** true = price is our estimate (e.g. percent of the ad-hoc price) */
    val estimate: Boolean = false,
) {
    val effectiveRules: List<TariffRule>
        get() = rules.ifEmpty { listOf(TariffRule(country, match, mode, value, value, value)) }
}

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
    /** countries whose charging data is downloaded (ISO codes) */
    val countries: Set<String> = Countries.DEFAULT,
    val tariffs: List<Tariff> = Tariffs.presets,
) {
    val vehicle: Vehicle get() = vehicles.firstOrNull { it.id == activeVehicleId } ?: vehicles.firstOrNull() ?: Vehicle.DEFAULT
    val capacityKwh: Double get() = vehicle.capacityKwh

    /** motorway consumption at the chosen speed, kWh/100 km (incl. season, correction, margin) */
    fun consumption(): Double = EnergyModel.kwhPerKm(vehicle, this, highwayKmh.toDouble()) * 100
}

object Countries {
    /** live + priced official data; everything else comes from Open Charge Map */
    val LIVE = setOf("AT", "DE", "HU", "IT", "SI")
    val DEFAULT = setOf("AT", "DE", "HU", "IT", "SI", "HR", "SK", "CZ", "CH")
    val NAMES = linkedMapOf(
        "AT" to "Ausztria", "DE" to "Németország", "HU" to "Magyarország", "IT" to "Olaszország",
        "SI" to "Szlovénia", "HR" to "Horvátország", "SK" to "Szlovákia", "CZ" to "Csehország", "CH" to "Svájc",
        "PL" to "Lengyelország", "RO" to "Románia", "RS" to "Szerbia", "BA" to "Bosznia-Hercegovina", "ME" to "Montenegró",
        "AL" to "Albánia", "MK" to "Észak-Macedónia", "XK" to "Koszovó", "BG" to "Bulgária", "GR" to "Görögország",
        "FR" to "Franciaország", "ES" to "Spanyolország", "PT" to "Portugália", "BE" to "Belgium", "NL" to "Hollandia",
        "LU" to "Luxemburg", "LI" to "Liechtenstein", "MC" to "Monaco", "SM" to "San Marino", "AD" to "Andorra",
        "DK" to "Dánia", "SE" to "Svédország", "NO" to "Norvégia", "FI" to "Finnország", "IS" to "Izland",
        "EE" to "Észtország", "LV" to "Lettország", "LT" to "Litvánia", "GB" to "Egyesült Királyság", "IE" to "Írország",
        "MT" to "Málta", "CY" to "Ciprus", "UA" to "Ukrajna", "MD" to "Moldova",
    )
    fun name(cc: String) = NAMES[cc] ?: cc
}

object Tariffs {
    private fun r(c: String, m: String, mode: String, ac: Double?, dc: Double?, hpc: Double? = null) = TariffRule(c, m, mode, ac, dc, hpc)

    /**
     * Catalog of common cards and subscriptions (AT / DE / HU / Europe). All off by default.
     * Prices incl. VAT, as published by the providers or price comparisons; dates in [Tariff.source].
     */
    val presets = listOf(
        // ---------------------------------------------------------------- Ausztria
        Tariff("at-wien-go", "Wien Energie Tanke kWh GO", "AT", "", "fixed", 0.0, false, "kártya egyszeri 9,90 €", 0.0,
            listOf(r("AT", "Wien Energie", "fixed", 0.43, 0.43), r("AT", "", "fixed", 0.70, 0.70)), "Wien Energie, 2026"),
        Tariff("at-wien-smart", "Wien Energie Tanke SMART", "AT", "", "fixed", 0.0, false, "", 5.90,
            listOf(r("AT", "Wien Energie", "fixed", 0.40, 0.40)), "Wien Energie, 2026"),
        Tariff("at-wien-pro", "Wien Energie Tanke PRO", "AT", "", "fixed", 0.0, false, "", 29.90,
            listOf(r("AT", "Wien Energie", "fixed", 0.36, 0.36)), "Wien Energie, 2026"),
        Tariff("at-oeamtc", "ÖAMTC ePower", "AT", "", "fixed", 0.0, false, "partnerhálózat +0,10 €, külföld +0,20 €", 0.0,
            listOf(r("AT", "ÖAMTC", "fixed", 0.44, 0.54), r("AT", "", "fixed", 0.54, 0.64), r("*", "", "fixed", 0.64, 0.74)), "ÖAMTC, 2026"),
        Tariff("at-evn-easy", "EVN #Drive Easy", "AT", "", "fixed", 0.0, false, "aktiválás 10 €", 0.0,
            listOf(r("AT", "EVN", "fixed", 0.54, 0.54)), "EVN tarifalap, 2026.07."),
        Tariff("at-evn-smart", "EVN #Drive Smart", "AT", "", "fixed", 0.0, false, "aktiválás 10 €", 4.95,
            listOf(r("AT", "EVN", "fixed", 0.45, 0.47, 0.49), r("AT", "", "fixed", 0.54, 0.64, 0.69)), "EVN tarifalap, 2026.05."),
        Tariff("at-smatrics-simple", "SMATRICS Charge Simple", "AT", "", "fixed", 0.0, false, "aktiválás 19,90 €", 0.0,
            listOf(r("AT", "SMATRICS", "fixed", 0.55, 0.65)), "SMATRICS árlap, 2024 – ellenőrizd"),
        Tariff("at-smatrics-roam", "SMATRICS Charge & Roam+", "AT", "", "fixed", 0.0, false, "", 8.90,
            listOf(r("AT", "SMATRICS", "fixed", 0.50, 0.55), r("*", "", "fixed", 0.69, 0.79)), "SMATRICS árlap, 2024 – ellenőrizd"),
        Tariff("at-linz-easy", "LINZ AG EASY POWER", "AT", "", "fixed", 0.0, false, "egyszeri 20 €, roaming felár nélkül", 0.0,
            listOf(r("*", "", "fixed", 0.54, 0.64)), "LINZ AG, 2025.02."),
        Tariff("at-linz-more", "LINZ AG MORE POWER", "AT", "", "fixed", 0.0, false, "", 7.50,
            listOf(r("AT", "LINZ AG", "fixed", 0.39, 0.49, 0.74)), "LINZ AG, 2025.02."),
        Tariff("at-shell", "Shell Recharge", "AT", "", "fixed", 0.0, false, "idegen hálózaton +0,35 €/töltés", 0.0,
            listOf(r("*", "Shell", "fixed", 0.54, 0.69), r("AT,DE", "", "fixed", 0.54, 0.74)), "emcaustria.at, 2026.08."),
        Tariff("at-shell-deal", "Shell Recharge e-Deal", "AT", "", "fixed", 0.0, false, "max. 500 kWh/hó", 9.90,
            listOf(r("AT", "Shell", "fixed", 0.43, 0.55)), "emcaustria.at, 2026.08."),
        // ---------------------------------------------------------------- Németország
        Tariff("de-enbw-s", "EnBW mobility+ S", "DE", "", "fixed", 0.0, false, "más hálózaton 0,56–0,89 € (változó)", 0.0,
            listOf(r("DE", "EnBW", "fixed", 0.56, 0.56)), "EnBW, 2026.09."),
        Tariff("de-enbw-m", "EnBW mobility+ M", "DE", "", "fixed", 0.0, false, "más hálózaton 0,56–0,89 € (változó)", 5.99,
            listOf(r("DE", "EnBW", "fixed", 0.46, 0.46)), "EnBW, 2026.09."),
        Tariff("de-enbw-l", "EnBW mobility+ L", "DE", "", "fixed", 0.0, false, "más hálózaton 0,56–0,89 € (változó)", 11.99,
            listOf(r("DE", "EnBW", "fixed", 0.39, 0.39)), "EnBW, 2026.09."),
        Tariff("de-ewe", "EWE Go Standard", "DE", "", "fixed", 0.0, false, "", 0.0,
            listOf(r("DE", "EWE Go", "fixed", 0.52, 0.52), r("DE", "", "fixed", 0.62, 0.62)), "elektroquatsch.de, 2026.02."),
        // ---------------------------------------------------------------- Több ország
        Tariff("eu-ionity-motion", "IONITY Motion", "*", "", "fixed", 0.0, false, "DE-n kívül becslés (−25%)", 5.99,
            listOf(r("DE", "IONITY", "fixed", 0.51, 0.51), r("*", "IONITY", "percent", 25.0, 25.0)), "ladechef.de, 2026.11.", true),
        Tariff("eu-ionity-power", "IONITY Power", "*", "", "fixed", 0.0, false, "DE-n kívül becslés (−40%)", 11.99,
            listOf(r("DE", "IONITY", "fixed", 0.41, 0.41), r("*", "IONITY", "percent", 40.0, 40.0)), "ladechef.de, 2026.11.", true),
        Tariff("eu-tesla-owner", "Tesla-tulajdonos / Membership", "*", "", "fixed", 0.0, false,
            "becslés: a közzétett ad-hoc ár −22%; a pontos árat az autó mutatja", 0.0,
            listOf(r("*", "Tesla", "percent", 22.0, 22.0)), "becslés", true),
        // ---------------------------------------------------------------- Magyarország (OTPR)
        Tariff("hu-mobiliti-vip", "Mobiliti VIP", "HU", "Mobiliti", "discount", 26.0, false, "2990 Ft / 30 nap", 2990.0, source = "OTPR, 2026"),
        Tariff("hu-mol-green", "MOL MOVE Green/Silver", "HU", "MOL Plugee", "discount", 10.0, false, source = "OTPR, 2026"),
        Tariff("hu-mol-gold", "MOL MOVE Gold/VIP", "HU", "MOL Plugee", "discount", 20.0, false, source = "OTPR, 2026"),
        Tariff("hu-lidl-plus", "Lidl Plus", "HU", "Lidl", "discount", 50.0, false, "app-os fizetés", source = "OTPR, 2026"),
    )

    /** Home country used for grouping in the UI. */
    fun group(t: Tariff): String = when {
        t.id.startsWith("u-") -> "Saját"
        t.country == "AT" -> "Ausztria"
        t.country == "DE" -> "Németország"
        t.country == "HU" -> "Magyarország"
        else -> "Több ország"
    }
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
        put("countries", JSONArray(p.countries.toList()))
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
        // presets always come from the catalog in the code (prices get updated); only the switch is kept
        val enabledIds = saved.filter { it.enabled }.map { it.id }.toSet()
        val tariffs = Tariffs.presets.map { it.copy(enabled = it.id in enabledIds) } + saved.filter { it.id.startsWith("u-") }
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
            countries = o.optJSONArray("countries")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }?.takeIf { it.isNotEmpty() } ?: d.countries,
            tariffs = tariffs,
        )
    }
}
