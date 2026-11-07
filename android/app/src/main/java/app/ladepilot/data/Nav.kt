package app.ladepilot.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

object Nav {
    private const val MAPS = "com.google.android.apps.maps"

    /** Google Maps directions with optional charging stops as waypoints. */
    fun mapsRouteUri(dest: Place, stops: List<Site>): Uri {
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate")
        sb.append("&destination=").append(dest.lat).append(',').append(dest.lon)
        if (stops.isNotEmpty()) {
            sb.append("&waypoints=").append(Uri.encode(stops.joinToString("|") { "${it.lat},${it.lon}" }))
        }
        return Uri.parse(sb.toString())
    }

    fun mapsToSite(site: Site): Uri =
        Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate&destination=${site.lat},${site.lon}")

    fun open(ctx: Context, uri: Uri) {
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(Intent(i).setPackage(MAPS))
        } catch (e: ActivityNotFoundException) {
            ctx.startActivity(i)
        }
    }

    /** Waze accepts only one destination. */
    fun waze(ctx: Context, lat: Double, lon: Double) {
        val uri = Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes")
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(Intent(i).setPackage("com.waze"))
        } catch (e: ActivityNotFoundException) {
            ctx.startActivity(i)
        }
    }

    /** geo: URI for Android Auto navigation hand-off (one destination only). */
    fun geoUri(lat: Double, lon: Double, label: String): Uri =
        Uri.parse("geo:0,0?q=$lat,$lon(${Uri.encode(label)})")
}
