package app.ladepilot.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.ladepilot.data.DataRepo
import app.ladepilot.data.Geo
import app.ladepilot.data.LocationHelper
import app.ladepilot.data.Plan
import app.ladepilot.data.Pricing
import app.ladepilot.data.Profile
import app.ladepilot.data.Quote
import app.ladepilot.data.Site
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.concurrent.Executors

/** Vector map styles from OpenFreeMap – free, no API key. */
private const val OFM = "https://tiles.openfreemap.org/styles/"

internal fun powerArgb(kw: Int): Int = when {
    kw >= 250 -> 0xFFFF3B30.toInt()
    kw >= 150 -> 0xFFFFB340.toInt()
    kw > 22 -> 0xFF30D158.toInt()
    else -> 0xFF8A94A6.toInt()
}

private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

/** A charger in the visible map area, for the "nearby" list. */
internal data class NearItem(val site: Site, val quote: Quote, val distKm: Double)

/**
 * Owns the MapLibre map: price pills (symbol layer with collision handling),
 * small dots for everything else, the route line and start/destination/me markers.
 */
internal class MapHolder(private val ctx: Context, private val onTap: (Site) -> Unit) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleName: String? = null
    private var wantedStyle: String = "dark"
    private val images = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    private val density = ctx.resources.displayMetrics.density

    var profile: Profile? = null
    var minKw = 50
    var plan: Plan? = null
        private set
    var me: LatLng? = null
        set(v) { field = v; updateEnds() }

    /** cheapest chargers currently in view (for the bottom sheet when no route is planned) */
    val visible = MutableStateFlow<List<NearItem>>(emptyList())

    init {
        MapLibre.getInstance(ctx)
        view = MapView(ctx)
        view.onCreate(null)
        view.addOnDidFailLoadingMapListener {
            // fall back to the colourful style if a style URL is not available
            if (styleName != "liberty") { styleName = null; loadStyle("liberty") }
        }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isLogoEnabled = false
            val here = LocationHelper.last(ctx)
            me = here?.let { LatLng(it.latitude, it.longitude) }
            m.cameraPosition = CameraPosition.Builder()
                .target(me ?: LatLng(48.2, 16.37))
                .zoom(if (me != null) 12.0 else 8.0)
                .build()
            m.addOnCameraIdleListener { refresh() }
            m.addOnMapClickListener { ll -> handleClick(ll) }
            loadStyle(wantedStyle)
        }
    }

    // ------------------------------------------------------------ lifecycle
    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onDestroy() { view.onDestroy(); worker.shutdown() }

    // ------------------------------------------------------------ style
    fun applyStyle(name: String) {
        wantedStyle = when (name) { "light", "liberty" -> name; else -> "dark" }
        if (map != null) loadStyle(wantedStyle)
    }

    private fun loadStyle(name: String) {
        val m = map ?: return
        if (name == styleName) return
        styleName = name
        val url = when (name) { "light" -> OFM + "positron"; "liberty" -> OFM + "liberty"; else -> OFM + "dark" }
        m.setStyle(Style.Builder().fromUri(url)) { st ->
            style = st
            images.clear()
            setupLayers(st)
            updateRoute()
            updateEnds()
            refresh()
        }
    }

    private fun setupLayers(st: Style) {
        for (id in listOf("route", "dots", "pins", "stops", "ends")) st.addSource(GeoJsonSource(id))
        st.addLayer(LineLayer("route-casing", "route").withProperties(
            PropertyFactory.lineColor("#0A1A44"), PropertyFactory.lineWidth(9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        st.addLayer(LineLayer("route-line", "route").withProperties(
            PropertyFactory.lineColor("#4C7BFF"), PropertyFactory.lineWidth(5.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        st.addLayer(CircleLayer("dots", "dots").withProperties(
            PropertyFactory.circleColor(Expression.get("c")), PropertyFactory.circleRadius(Expression.get("r")),
            PropertyFactory.circleOpacity(Expression.get("o")),
            PropertyFactory.circleStrokeColor("#000000"), PropertyFactory.circleStrokeWidth(0.8f)))
        st.addLayer(SymbolLayer("pins", "pins").withProperties(
            PropertyFactory.iconImage(Expression.get("img")),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.symbolSortKey(Expression.get("prio")),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)))
        st.addLayer(SymbolLayer("stops", "stops").withProperties(
            PropertyFactory.iconImage(Expression.get("img")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)))
        st.addLayer(CircleLayer("ends", "ends").withProperties(
            PropertyFactory.circleColor(Expression.get("c")), PropertyFactory.circleRadius(Expression.get("r")),
            PropertyFactory.circleStrokeColor(Expression.get("s")), PropertyFactory.circleStrokeWidth(3f)))
    }

    // ------------------------------------------------------------ camera
    fun flyTo(lat: Double, lon: Double, zoom: Double = 13.0) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 600)
    }

    fun fitRoute() {
        val m = map ?: return
        val r = plan?.route ?: return
        if (r.size < 2) return
        val b = LatLngBounds.Builder().includes(r.map { LatLng(it[0], it[1]) }).build()
        val pad = (40 * density).toInt()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, pad, (150 * density).toInt(), pad, (110 * density).toInt()), 800)
    }

    // ------------------------------------------------------------ plan
    fun setPlan(p: Plan?) {
        val changed = p?.route !== plan?.route
        plan = p
        updateRoute()
        updateEnds()
        refresh()
        if (p != null && changed) fitRoute()
    }

    private fun updateRoute() {
        val src = style?.getSourceAs<GeoJsonSource>("route") ?: return
        val r = plan?.route
        if (r != null && r.size > 1) src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(r.map { Point.fromLngLat(it[1], it[0]) })))
        else src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    private fun updateEnds() {
        val src = style?.getSourceAs<GeoJsonSource>("ends") ?: return
        val f = ArrayList<Feature>()
        fun add(lat: Double, lon: Double, c: String, s: String, r: Float) {
            f += Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty("c", c); addStringProperty("s", s); addNumberProperty("r", r)
            }
        }
        me?.let { add(it.latitude, it.longitude, "#3E6AE1", "#FFFFFF", 7f) }
        plan?.let { p ->
            add(p.origin.lat, p.origin.lon, "#FFFFFF", "#000000", 6.5f)
            add(p.dest.lat, p.dest.lon, "#FF453A", "#000000", 7.5f)
        }
        src.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    // ------------------------------------------------------------ taps
    private fun handleClick(ll: LatLng): Boolean {
        val m = map ?: return false
        val p = m.projection.toScreenLocation(ll)
        val t = 14 * density
        val hits = m.queryRenderedFeatures(RectF(p.x - t, p.y - t, p.x + t, p.y + t), "stops", "pins", "dots")
        val id = hits.firstOrNull()?.getStringProperty("id") ?: return false
        DataRepo.site(id)?.let(onTap)
        return true
    }

    // ------------------------------------------------------------ price pills
    private fun pinBitmap(text: String, color: Int, kind: Int, dim: Boolean): Bitmap {
        val d = density
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 13.5f * d }
        val w = tp.measureText(text) + 28 * d
        val h = 26 * d
        val bmp = Bitmap.createBitmap((w + 2 * d).toInt(), (h + 2 * d).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(d, d, d + w, d + h)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = when (kind) { 2 -> 0xFF3E6AE1.toInt(); 1 -> 0xFF30D158.toInt(); else -> 0xF21B1C20.toInt() }
            if (dim && kind == 0) alpha = 175
        }
        c.drawRoundRect(r, h / 2, h / 2, bg)
        c.drawRoundRect(r, h / 2, h / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f * d; this.color = 0x55FFFFFF
        })
        c.drawCircle(r.left + 12 * d, r.centerY(), 4.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (kind == 0) color else Color.WHITE
        })
        tp.color = if (kind == 1) 0xFF05250F.toInt() else Color.WHITE
        c.drawText(text, r.left + 21 * d, r.centerY() + tp.textSize * 0.36f, tp)
        return bmp
    }

    /** Recomputes pins/dots for the visible area off the main thread. */
    fun refresh() {
        val m = map ?: return
        if (style == null) return
        val prof = profile ?: return
        val bb = m.projection.visibleRegion.latLngBounds
        val north = bb.latitudeNorth; val south = bb.latitudeSouth; val east = bb.longitudeEast; val west = bb.longitudeWest
        val stopIds = plan?.stops?.map { it.cand.site.id }?.toSet() ?: emptySet()
        val kw = minKw
        val ref = me ?: m.cameraPosition.target ?: LatLng(48.2, 16.37)
        val known = HashSet(images)
        val gen = ++generation
        worker.execute {
            val now = System.currentTimeMillis()
            val vis = ArrayList<Pair<Site, Quote?>>()
            for (s in DataRepo.sites) {
                if (s.lat < south || s.lat > north || s.lon < west || s.lon > east) continue
                if (s.maxKw < kw && s.id !in stopIds) continue
                vis += s to Pricing.quote(s, kw, prof, now)
            }
            val priced = vis.filter { it.second != null && it.first.id !in stopIds }.sortedBy { it.second!!.eur }
            val bestEur = priced.firstOrNull { !it.second!!.free }?.second?.eur
            val best = priced.filter { !it.second!!.free }.take(5)
                .filter { bestEur != null && it.second!!.eur <= bestEur + 0.03 }.map { it.first.id }.toSet()

            val newImages = HashMap<String, Bitmap>()
            fun img(s: Site, q: Quote?, kind: Int): String {
                val text = q?.let { Pricing.labelQ(s, it) + if (s.cur == "HUF") " Ft" else "" } ?: "–"
                val av = Pricing.availability(s, kw)
                val dim = av != null && av.free == 0
                val key = "p|$text|${powerArgb(s.maxKw)}|$kind|$dim"
                if (key !in known && key !in newImages) newImages[key] = pinBitmap(text, powerArgb(s.maxKw), kind, dim)
                return key
            }
            fun pt(s: Site) = Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply { addStringProperty("id", s.id) }

            val pinFeatures = ArrayList<Feature>()
            (priced.take(400) + vis.filter { it.second == null && it.first.id !in stopIds }.take(60))
                .forEachIndexed { i, (s, q) ->
                    pinFeatures += pt(s).apply {
                        addStringProperty("img", img(s, q, if (s.id in best) 1 else 0))
                        addNumberProperty("prio", if (s.id in best) i - 1000 else i)
                    }
                }
            val stopFeatures = vis.filter { it.first.id in stopIds }.map { (s, q) ->
                pt(s).apply { addStringProperty("img", img(s, q, 2)) }
            }
            val dotFeatures = vis.take(6000).map { (s, _) ->
                val av = Pricing.availability(s, kw)
                pt(s).apply {
                    addStringProperty("c", hex(powerArgb(s.maxKw)))
                    addNumberProperty("r", if (s.maxKw >= 150) 4.5f else 3.5f)
                    addNumberProperty("o", if (av != null && av.free == 0) 0.55f else 1f)
                }
            }
            val near = priced.filter { !it.second!!.free || true }.take(80).map { (s, q) ->
                NearItem(s, q!!, Geo.fastKm(ref.latitude, ref.longitude, s.lat, s.lon))
            }
            view.post {
                if (gen != generation) return@post
                val st = style ?: return@post
                for ((k, b) in newImages) { st.addImage(k, b); images += k }
                st.getSourceAs<GeoJsonSource>("dots")?.setGeoJson(FeatureCollection.fromFeatures(dotFeatures))
                st.getSourceAs<GeoJsonSource>("pins")?.setGeoJson(FeatureCollection.fromFeatures(pinFeatures))
                st.getSourceAs<GeoJsonSource>("stops")?.setGeoJson(FeatureCollection.fromFeatures(stopFeatures))
                visible.value = near
            }
        }
    }
}
