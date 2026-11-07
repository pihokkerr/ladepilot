package app.ladepilot.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

data class Quote(
    /** effective price per kWh in EUR (after the user's tariff) */
    val eur: Double,
    /** ad-hoc price per kWh in EUR */
    val baseEur: Double,
    val tariff: Tariff?,
    val eurMin: Double?,
    val kw: Int,
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

    fun matches(t: Tariff, site: Site): Boolean {
        if (!t.enabled) return false
        if (t.country != "*" && t.country != site.country && t.country != site.src) return false
        if (t.match.isBlank()) return true
        return site.op.contains(t.match, true) || site.name.contains(t.match, true)
    }

    /** Applies the cheapest matching tariff. */
    fun applyTariffs(site: Site, eur: Double, tariffs: List<Tariff>): Pair<Double, Tariff?> {
        var best = eur
        var used: Tariff? = null
        for (t in tariffs) {
            if (!matches(t, site)) continue
            val v = when (t.mode) {
                "fixed" -> t.value / site.fx
                "percent" -> eur * (1 - t.value / 100.0)
                else -> eur - t.value / site.fx
            }.coerceAtLeast(0.0)
            if (v < best) { best = v; used = t }
        }
        return best to used
    }

    /** Cheapest effective price among points with at least [minKw]. */
    fun quote(site: Site, minKw: Int, profile: Profile, atMillis: Long = System.currentTimeMillis()): Quote? {
        var best: Quote? = null
        for (p in site.points) {
            if (p.kw < minKw) continue
            val base = baseEur(site, p, atMillis) ?: continue
            val (eff, t) = applyTariffs(site, base, profile.tariffs)
            if (best == null || eff < best.eur || (eff == best.eur && p.kw > best.kw)) {
                best = Quote(eff, base, t, p.eurMin, p.kw)
            }
        }
        return best
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
