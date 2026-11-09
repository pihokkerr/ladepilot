package app.ladepilot.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

data class Quote(
    /** comparable price per kWh in EUR: tariffed energy price + per-minute fee converted to kWh */
    val eur: Double,
    /** energy part per kWh after the user's tariff (EUR) */
    val energyEur: Double,
    /** ad-hoc energy price per kWh (EUR), 0 if the charger only bills per minute */
    val baseEur: Double,
    val tariff: Tariff?,
    val eurMin: Double?,
    val kw: Int,
    /** true when [eur] contains an estimate from a per-minute fee */
    val estimated: Boolean = false,
    /** list price of the operator (no per-site data) */
    val approx: Boolean = false,
    val free: Boolean = false,
    /** the charger publishes no price at all; [eur] is a planning assumption */
    val unknown: Boolean = false,
)

data class Availability(val free: Int, val known: Int, val total: Int, val off: Int)

object Pricing {
    private val zone: ZoneId = ZoneId.of("Europe/Vienna")

    /** Ad-hoc price per kWh (EUR) of a point at a given moment. */
    fun baseEur(site: Site, p: ChargePoint?, atMillis: Long = System.currentTimeMillis()): Double? {
        site.daily?.let { d ->
            val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
            val m = t.hour * 60 + t.minute
            d.firstOrNull { m >= it.startMin && m < it.endMin }?.let { return it.eur }
        }
        site.abs?.let { a ->
            if (a.isNotEmpty()) {
                val s = atMillis / 1000
                var v: Double? = null
                for (x in a) if (x.epochSec <= s) v = x.eur
                val siteNow = v ?: a.first().eur
                // abs holds the site price; scale per-point prices that differ from it
                val first = a.first().eur
                val pt = p?.eurKwh
                return if (pt != null && first > 0 && kotlin.math.abs(pt - first) > 0.005) pt * siteNow / first else siteNow
            }
        }
        return p?.eurKwh
    }

    /** The rule of [t] that applies to [site], or null. */
    fun ruleFor(t: Tariff, site: Site): TariffRule? {
        if (!t.enabled) return null
        for (r in t.effectiveRules) {
            if (!r.appliesTo(site.country) && !r.appliesTo(site.src)) continue
            if (r.match.isBlank() || site.op.contains(r.match, true) || site.name.contains(r.match, true)) return r
        }
        return null
    }

    /** Applies the cheapest matching tariff for a point of [kw] kW. */
    fun applyTariffs(site: Site, eur: Double, tariffs: List<Tariff>, kw: Int = site.maxKw): Pair<Double, Tariff?> {
        var best = eur
        var used: Tariff? = null
        for (t in tariffs) {
            val r = ruleFor(t, site) ?: continue
            val value = r.valueFor(kw) ?: continue
            val v = when (r.mode) {
                "fixed" -> value / site.fx
                "percent" -> eur * (1 - value / 100.0)
                else -> eur - value / site.fx
            }.coerceAtLeast(0.0)
            if (v < best - 1e-9) { best = v; used = t }
        }
        return best to used
    }

    /** Average power a Model Y actually draws: AC limited by the 11 kW on-board charger. */
    fun effectiveKw(kw: Int): Double = if (kw <= 22) kw.coerceIn(3, 11).toDouble() else (kw * 0.7).coerceAtMost(110.0)

    /** Cheapest comparable price among points with at least [minKw]. */
    fun quote(site: Site, minKw: Int, profile: Profile, atMillis: Long = System.currentTimeMillis()): Quote? {
        var best: Quote? = null
        for (p in site.points) {
            if (p.kw < minKw) continue
            val q = if (p.free) {
                Quote(0.0, 0.0, 0.0, null, null, p.kw, free = true)
            } else {
                val base = baseEur(site, p, atMillis)
                val perMin = p.eurMin?.takeIf { it > 0 }
                if (base == null && perMin == null) continue
                val (energy, t) = if (base != null) applyTariffs(site, base, profile.tariffs, p.kw) else (0.0 to null)
                val timePart = (perMin ?: 0.0) * 60.0 / effectiveKw(p.kw)
                Quote(energy + timePart, energy, base ?: 0.0, t, perMin, p.kw, estimated = timePart > 0 || p.approx, approx = p.approx)
            }
            if (best == null || q.eur < best.eur || (q.eur == best.eur && p.kw > best.kw)) best = q
        }
        return best
    }

    /** Marker / list label: "52", "~61" (estimated from per-minute fee), "0" for free. */
    /** Price assumed for route planning when a charger publishes none (EUR/kWh, before tariffs). */
    const val UNKNOWN_EUR = 0.75

    /** Planning quote for a charger without any published price, so it can still be used as a stop. */
    fun unknownQuote(site: Site, minKw: Int, profile: Profile): Quote? {
        val kw = site.points.filter { it.kw >= minKw }.maxOfOrNull { it.kw } ?: return null
        val (energy, t) = applyTariffs(site, UNKNOWN_EUR * site.fx / site.fx, profile.tariffs, kw)
        return Quote(energy, energy, UNKNOWN_EUR, t, null, kw, estimated = true, approx = true, unknown = true)
    }

    fun labelQ(site: Site, q: Quote): String = when {
        q.unknown -> "?"
        q.free -> "0"
        q.estimated -> "~" + label(site, q.eur)
        else -> label(site, q.eur)
    }

    /** Headline text for a quote. */
    fun headline(site: Site, q: Quote): String = when {
        q.free -> "Ingyenes"
        q.unknown -> "Ár nem ismert (tervezés: ~" + perKwh(site, q.energyEur) + ")"
        q.approx -> "~" + perKwh(site, q.energyEur) + " (listaár, becsült)"
        q.estimated && q.baseEur == 0.0 -> "~" + perKwh(site, q.eur) + " (percdíj alapján becsülve)"
        else -> perKwh(site, q.energyEur)
    }

    fun availability(site: Site, minKw: Int): Availability? {
        val pts = site.points.filter { it.kw >= minKw }
        if (pts.isEmpty()) return null
        val known = pts.count { it.status != 0 }
        if (known == 0) return null
        return Availability(pts.count { it.status == 1 }, known, pts.size, pts.count { it.status == 3 })
    }

    /** Short label for map markers: "52" (ct) or "154" (Ft). */
    fun label(site: Site, eur: Double): String =
        if (site.cur == "HUF") (eur * site.fx).roundToInt().toString() else (eur * 100).roundToInt().toString()

    fun unit(site: Site): String = if (site.cur == "HUF") "Ft" else "ct"

    fun perKwh(site: Site, eur: Double): String =
        if (site.cur == "HUF") "${(eur * site.fx).roundToInt()} Ft/kWh" else String.format(Locale.GERMANY, "%.2f €/kWh", eur)

    fun money(site: Site, eur: Double): String =
        if (site.cur == "HUF") "${(eur * site.fx / 10).roundToInt() * 10} Ft" else String.format(Locale.GERMANY, "%.2f €", eur)

    fun eurText(eur: Double): String = String.format(Locale.GERMANY, "%.2f €", eur)

    /** Next price change after now: (epochMillis, eur) */
    fun nextChange(site: Site, nowMillis: Long = System.currentTimeMillis()): Pair<Long, Double>? {
        val cur = baseEur(site, null, nowMillis) ?: return null
        site.abs?.let { a ->
            val s = nowMillis / 1000
            a.firstOrNull { it.epochSec > s && kotlin.math.abs(it.eur - cur) > 0.001 }?.let { return it.epochSec * 1000 to it.eur }
            return null
        }
        site.daily?.let { d ->
            val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
            val m = t.hour * 60 + t.minute
            d.sortedBy { it.startMin }.firstOrNull { it.startMin > m && kotlin.math.abs(it.eur - cur) > 0.001 }?.let {
                val at = t.withHour(it.startMin / 60).withMinute(it.startMin % 60).withSecond(0)
                return at.toInstant().toEpochMilli() to it.eur
            }
        }
        return null
    }

    fun hhmm(epochMillis: Long): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        return String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
    }
}
