package app.ladepilot.data

/** One charge point (one plug that can be used at a time). Prices are in EUR. */
data class ChargePoint(
    val kw: Int,
    val plugs: String,
    val eurKwh: Double?,
    val eurMin: Double?,
    /** 0 unknown, 1 available, 2 occupied, 3 out of service */
    val status: Int,
)

/** Recurring time-of-day price (local wall clock, minutes after midnight). */
data class DailyPrice(val startMin: Int, val endMin: Int, val eur: Double)

/** Absolute price schedule entry: from [epochSec] on, [eur] applies. */
data class AbsPrice(val epochSec: Long, val eur: Double)

class Site(
    val id: String,
    val name: String,
    val op: String,
    val lat: Double,
    val lon: Double,
    val addr: String,
    val country: String,
    val points: List<ChargePoint>,
    val daily: List<DailyPrice>?,
    val abs: List<AbsPrice>?,
    /** Currency used for display: "EUR" or "HUF" */
    val cur: String,
    /** Units of [cur] per EUR (1.0 for EUR) */
    val fx: Double,
    /** Data source: AT, HU, DE */
    val src: String,
    val startFeeLocal: Double?,
    val idleFeeLocal: Double?,
) {
    val maxKw: Int = points.maxOfOrNull { it.kw } ?: 0
    val isTesla: Boolean = op.contains("tesla", ignoreCase = true) || name.contains("supercharger", ignoreCase = true)
}

data class Place(val name: String, val lat: Double, val lon: Double)
