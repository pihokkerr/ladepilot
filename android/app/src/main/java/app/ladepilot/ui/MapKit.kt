package app.ladepilot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import app.ladepilot.data.DataRepo
import app.ladepilot.data.Plan
import app.ladepilot.data.Pricing
import app.ladepilot.data.Profile
import app.ladepilot.data.Site
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

private const val ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/"

/** Esri tiles use z/y/x order. */
private class EsriSource(name: String, layer: String) :
    OnlineTileSourceBase(name, 0, 16, 256, "", arrayOf("$ESRI$layer/MapServer/tile/")) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

internal fun powerArgb(kw: Int): Int = when {
    kw >= 250 -> 0xFFFF3B30.toInt()
    kw >= 150 -> 0xFFFFB340.toInt()
    kw > 22 -> 0xFF30D158.toInt()
    else -> 0xFF8A94A6.toInt()
}

/** kind: 0 normal, 1 among the cheapest in view, 2 planned stop */
internal class Pin(val site: Site, val text: String, val color: Int, val kind: Int, val dim: Boolean)

/** Draws price pills directly on the canvas (fast enough for thousands of chargers). */
internal class ChargerOverlay(private val onTap: (Site) -> Unit) : Overlay() {
    /** in priority order: first = most important */
    var pins: List<Pin> = emptyList()
    var origin: GeoPoint? = null
    var dest: GeoPoint? = null
    var me: GeoPoint? = null

    private val placed = ArrayList<Pair<RectF, Site>>()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = Point()

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mv.projection
        val d = mv.context.resources.displayMetrics.density
        text.textSize = 13.5f * d
        val zoom = mv.zoomLevelDouble
        val h = 26 * d
        val labels = ArrayList<Pair<RectF, Pin>>()
        val dots = ArrayList<Triple<Float, Float, Pin>>()
        for (p in pins) {
            proj.toPixels(GeoPoint(p.site.lat, p.site.lon), pt)
            if (pt.x < -60 || pt.y < -60 || pt.x > c.width + 60 || pt.y > c.height + 60) continue
            val w = text.measureText(p.text) + 28 * d
            val r = RectF(pt.x - w / 2, pt.y - h / 2, pt.x + w / 2, pt.y + h / 2)
            val label = p.kind == 2 || (zoom >= 9.0 && labels.none { RectF.intersects(it.first, r) })
            if (label) labels += r to p else dots += Triple(pt.x.toFloat(), pt.y.toFloat(), p)
        }
        for ((x, y, p) in dots) {
            dot.color = p.color
            dot.alpha = if (p.dim) 140 else 255
            c.drawCircle(x, y, (if (p.site.maxKw >= 150) 4.5f else 3.5f) * d, dot)
        }
        placed.clear()
        for ((r, p) in labels.asReversed()) {
            bg.color = when (p.kind) { 2 -> 0xFF3E6AE1.toInt(); 1 -> 0xFF30D158.toInt(); else -> 0xF2161618.toInt() }
            if (p.dim && p.kind == 0) bg.alpha = 170
            c.drawRoundRect(r, h / 2, h / 2, bg)
            stroke.color = 0x40FFFFFF
            c.drawRoundRect(r, h / 2, h / 2, stroke)
            dot.color = if (p.kind == 0) p.color else Color.WHITE
            dot.alpha = 255
            c.drawCircle(r.left + 12 * d, r.centerY(), 4.5f * d, dot)
            text.color = if (p.kind == 1) 0xFF05250F.toInt() else Color.WHITE
            c.drawText(p.text, r.left + 21 * d, r.centerY() + text.textSize * 0.36f, text)
        }
        for ((r, p) in labels) placed += r to p.site
        origin?.let { end(c, proj, it, Color.WHITE, d) }
        dest?.let { end(c, proj, it, 0xFFFF453A.toInt(), d) }
        me?.let {
            proj.toPixels(it, pt)
            dot.color = 0x553E6AE1; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 14 * d, dot)
            dot.color = Color.WHITE; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8 * d, dot)
            dot.color = 0xFF3E6AE1.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5.5f * d, dot)
        }
    }

    private fun end(c: Canvas, proj: Projection, g: GeoPoint, col: Int, d: Float) {
        proj.toPixels(g, pt)
        dot.color = Color.BLACK; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 10 * d, dot)
        dot.color = col; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7 * d, dot)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        for ((r, s) in placed) if (r.contains(e.x, e.y)) { onTap(s); return true }
        return false
    }
}

/** Owns the osmdroid MapView, the route line and the charger overlay. */
internal class MapHolder(ctx: Context, onTap: (Site) -> Unit) {
    val view: MapView
    val pins = ChargerOverlay(onTap)
    private val routeLine = Polyline()
    var profile: Profile? = null
    var plan: Plan? = null
        private set
    var minKw = 50

    init {
        Configuration.getInstance().apply {
            userAgentValue = "Ladepilot/1.0 (Android)"
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        view = MapView(ctx)
        view.setTileSource(EsriSource("EsriDarkBase", "World_Dark_Gray_Base"))
        view.setMultiTouchControls(true)
        view.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        view.setMinZoomLevel(5.0)
        view.setMaxZoomLevel(18.0)
        view.setTilesScaledToDpi(true)
        view.overlayManager.tilesOverlay.setLoadingBackgroundColor(0xFF0E0E10.toInt())
        view.overlayManager.tilesOverlay.setLoadingLineColor(0xFF0E0E10.toInt())
        val labels = TilesOverlay(MapTileProviderBasic(ctx, EsriSource("EsriDarkRef", "World_Dark_Gray_Reference")), ctx)
        labels.setLoadingBackgroundColor(Color.TRANSPARENT)
        labels.setLoadingLineColor(Color.TRANSPARENT)
        view.overlays.add(labels)
        routeLine.outlinePaint.color = 0xFF3E6AE1.toInt()
        routeLine.outlinePaint.strokeWidth = 6 * ctx.resources.displayMetrics.density
        routeLine.outlinePaint.strokeCap = Paint.Cap.ROUND
        routeLine.outlinePaint.isAntiAlias = true
        routeLine.infoWindow = null
        view.overlays.add(routeLine)
        view.overlays.add(pins)
        view.controller.setZoom(9.0)
        view.controller.setCenter(GeoPoint(48.2, 16.37))
        view.addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { refresh(); return true }
            override fun onZoom(event: ZoomEvent?): Boolean { refresh(); return true }
        }, 120))
    }

    fun setPlan(p: Plan?) {
        val changedRoute = p?.route !== plan?.route
        plan = p
        routeLine.setPoints(p?.route?.map { GeoPoint(it[0], it[1]) } ?: emptyList())
        pins.origin = p?.origin?.let { GeoPoint(it.lat, it.lon) }
        pins.dest = p?.dest?.let { GeoPoint(it.lat, it.lon) }
        refresh()
        if (p != null && changedRoute) fitRoute()
    }

    fun fitRoute() {
        val pts = plan?.route?.map { GeoPoint(it[0], it[1]) } ?: return
        if (pts.size < 2) return
        view.post {
            try {
                val bb = BoundingBox.fromGeoPoints(pts)
                // leave room for the search card on top and the sheet at the bottom
                val south = bb.latSouth - (bb.latNorth - bb.latSouth) * 0.9
                val north = bb.latNorth + (bb.latNorth - bb.latSouth) * 0.25
                view.zoomToBoundingBox(BoundingBox(north, bb.lonEast, south, bb.lonWest), true, 60)
            } catch (e: Exception) { }
        }
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0

    /** Recomputes the visible price pins off the main thread. */
    fun refresh() {
        val prof = profile ?: return
        if (view.width == 0) { view.post { refresh() }; return }
        val bb = view.boundingBox
        val stops = plan?.stops?.map { it.cand.site.id }?.toSet() ?: emptySet()
        val kw = minKw
        val gen = ++generation
        worker.execute {
            val now = System.currentTimeMillis()
            val vis = ArrayList<Pair<Site, app.ladepilot.data.Quote?>>()
            for (s in DataRepo.sites) {
                if (s.lat < bb.latSouth || s.lat > bb.latNorth || s.lon < bb.lonWest || s.lon > bb.lonEast) continue
                if (s.maxKw < kw && s.id !in stops) continue
                vis += s to Pricing.quote(s, kw, prof, now)
            }
            val priced = vis.filter { it.second != null && it.first.id !in stops }.sortedBy { it.second!!.eur }
            val bestEur = priced.firstOrNull { !it.second!!.free }?.second?.eur
            val best = priced.filter { !it.second!!.free }.take(5)
                .filter { bestEur != null && it.second!!.eur <= bestEur + 0.03 }.map { it.first.id }.toSet()
            val ordered = vis.filter { it.first.id in stops } + priced.take(350) +
                vis.filter { it.second == null && it.first.id !in stops }.take(50)
            val list = ordered.map { (s, q) ->
                val av = Pricing.availability(s, kw)
                Pin(
                    s,
                    q?.let { Pricing.labelQ(s, it) + if (s.cur == "HUF") " Ft" else "" } ?: "–",
                    powerArgb(s.maxKw),
                    if (s.id in stops) 2 else if (s.id in best) 1 else 0,
                    dim = av != null && av.free == 0,
                )
            }
            view.post {
                if (gen == generation) { pins.pins = list; view.invalidate() }
            }
        }
    }
}
