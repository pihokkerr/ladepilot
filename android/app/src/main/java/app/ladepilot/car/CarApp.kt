package app.ladepilot.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Place as CarPlace
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.ladepilot.data.DataRepo
import app.ladepilot.data.Geo
import app.ladepilot.data.LocationHelper
import app.ladepilot.data.Nav
import app.ladepilot.data.Place
import app.ladepilot.data.PlanStore
import app.ladepilot.data.Pricing
import app.ladepilot.data.ProfileStore
import app.ladepilot.data.RoutePlanner
import app.ladepilot.data.Site
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LadepilotCarAppService : CarAppService() {
    // Personal, side-loaded app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = LadepilotSession()
}

class LadepilotSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = NearbyScreen(carContext)
}

// ------------------------------------------------------------------ helpers
internal fun powerColor(kw: Int): CarColor = when {
    kw >= 250 -> CarColor.createCustom(0xFFFF3B30.toInt(), 0xFFFF453A.toInt())
    kw >= 150 -> CarColor.createCustom(0xFFFF9F0A.toInt(), 0xFFFFB340.toInt())
    kw > 22 -> CarColor.createCustom(0xFF28A745.toInt(), 0xFF30D158.toInt())
    else -> CarColor.createCustom(0xFF6B7280.toInt(), 0xFF8A94A6.toInt())
}

internal fun placeFor(site: Site, label: String?): CarPlace {
    val m = PlaceMarker.Builder().setColor(powerColor(site.maxKw))
    if (!label.isNullOrEmpty()) m.setLabel(label.take(3))
    return CarPlace.Builder(CarLocation.create(site.lat, site.lon)).setMarker(m.build()).build()
}

internal fun listLimit(ctx: CarContext): Int = try {
    ctx.getCarService(ConstraintManager::class.java)
        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST).coerceIn(2, 12)
} catch (e: Exception) { 6 }

internal fun navigateTo(ctx: CarContext, lat: Double, lon: Double, label: String) {
    try {
        ctx.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Nav.geoUri(lat, lon, label)))
    } catch (e: Exception) {
        CarToast.makeText(ctx, "Nem sikerült elindítani a navigációt", CarToast.LENGTH_LONG).show()
    }
}

// ------------------------------------------------------------------ nearby
class NearbyScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {
    private var loc: Location? = null
    private var minKw = ProfileStore.load(ctx).minKw
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val locListener = LocationListener { l ->
        val old = loc
        loc = l
        if (old == null || Geo.fastKm(old.latitude, old.longitude, l.latitude, l.longitude) > 1.0) invalidate()
    }
    private val dataListener: () -> Unit = { invalidate() }

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) {
        DataRepo.addListener(dataListener)
        DataRepo.ensureLoaded(carContext)
        startLocation()
    }

    override fun onStop(owner: LifecycleOwner) {
        DataRepo.removeListener(dataListener)
        try { lm?.removeUpdates(locListener) } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!LocationHelper.hasPermission(carContext)) {
            carContext.requestPermissions(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) { granted, _ -> if (granted.isNotEmpty()) startLocation(); invalidate() }
            return
        }
        loc = loc ?: LocationHelper.last(carContext)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm?.isProviderEnabled(p) == true) lm.requestLocationUpdates(p, 5000L, 200f, locListener, Looper.getMainLooper())
            } catch (e: Exception) { }
        }
    }

    private fun nextKw(k: Int) = when (k) { 0 -> 50; 50 -> 150; 150 -> 250; else -> 0 }

    override fun onGetTemplate(): Template {
        val profile = ProfileStore.load(carContext)
        val b = PlaceListMapTemplate.Builder()
            .setTitle("Ladepilot")
            .setHeaderAction(Action.APP_ICON)
            .setCurrentLocationEnabled(LocationHelper.hasPermission(carContext))
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle(if (minKw == 0) "Mind" else "$minKw+ kW")
                        .setOnClickListener { minKw = nextKw(minKw); invalidate() }.build())
                    .addAction(Action.Builder().setTitle("Útvonal")
                        .setOnClickListener {
                            screenManager.push(if (PlanStore.plan.value != null) RouteScreen(carContext) else SearchScreen(carContext))
                        }.build())
                    .build()
            )

        val here = loc
        val sites = DataRepo.sites
        if (here == null || sites.isEmpty()) {
            if (DataRepo.loading || (here == null && LocationHelper.hasPermission(carContext))) return b.setLoading(true).build()
            val msg = when {
                !LocationHelper.hasPermission(carContext) -> "Engedélyezd a helymeghatározást a telefonon"
                DataRepo.lastError != null -> "Nincs adat – ellenőrizd az internetet"
                else -> "Nincs adat"
            }
            return b.setItemList(ItemList.Builder().setNoItemsMessage(msg).build()).build()
        }

        val limit = listLimit(carContext)
        val now = System.currentTimeMillis()
        var radius = 25.0
        var near: List<Triple<Site, Double, app.ladepilot.data.Quote>> = emptyList()
        while (radius <= 100) {
            near = sites.asSequence()
                .filter { it.maxKw >= minKw }
                .map { it to Geo.fastKm(here.latitude, here.longitude, it.lat, it.lon) }
                .filter { it.second <= radius }
                .mapNotNull { (s, d) -> Pricing.quote(s, minKw, profile, now)?.let { Triple(s, d, it) } }
                .sortedWith(compareBy({ it.third.eur }, { it.second }))
                .take(limit)
                .toList()
            if (near.size >= limit) break
            radius *= 2
        }

        val list = ItemList.Builder()
        for ((s, d, q) in near) {
            val av = Pricing.availability(s, minKw)
            val line = buildString {
                append(Pricing.label(s, q.eur)).append(' ').append(Pricing.unit(s))
                append(" · ").append(Geo.fmtKm(d))
                append(" · ").append(q.kw).append(" kW")
                if (av != null) append(" · ").append(av.free).append('/').append(av.known).append(" szabad")
            }
            list.addItem(
                Row.Builder()
                    .setTitle(s.name)
                    .addText(line)
                    .addText(s.op + (q.tariff?.let { " · ${it.name}" } ?: ""))
                    .setMetadata(Metadata.Builder().setPlace(placeFor(s, Pricing.label(s, q.eur))).build())
                    .setOnClickListener { screenManager.push(DetailScreen(carContext, s.id)) }
                    .build()
            )
        }
        if (near.isEmpty()) list.setNoItemsMessage("Nincs ${if (minKw > 0) "$minKw+ kW-os " else ""}töltő a közelben")
        return b.setItemList(list.build()).build()
    }
}

// ------------------------------------------------------------------ detail
class DetailScreen(ctx: CarContext, private val siteId: String) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val s = DataRepo.site(siteId)
            ?: return MessageTemplate.Builder("A töltő nem található").setHeaderAction(Action.BACK).build()
        val profile = ProfileStore.load(carContext)
        val q = Pricing.quote(s, 0, profile)
        val pane = Pane.Builder()

        val priceRow = Row.Builder().setTitle(
            if (q != null) Pricing.perKwh(s, q.eur) + (if (s.daily != null || s.abs != null) " most" else "") else "Nincs árinformáció"
        )
        val sub = buildList {
            if (q?.tariff != null) add("${q.tariff.name} (ad-hoc: ${Pricing.perKwh(s, q.baseEur)})")
            Pricing.nextChange(s)?.let { (at, eur) ->
                val eff = Pricing.applyTariffs(s, eur, profile.tariffs).first
                add("${Pricing.hhmm(at)}-tól ${Pricing.perKwh(s, eff)}")
            }
            if (q?.eurMin != null && q.eurMin > 0) add("+ ${Pricing.eurText(q.eurMin)} / perc")
        }
        if (sub.isNotEmpty()) priceRow.addText(sub.joinToString(" · "))
        pane.addRow(priceRow.build())

        val av = Pricing.availability(s, 0)
        val plugs = s.points.map { it.plugs }.distinct().joinToString(", ")
        pane.addRow(
            Row.Builder()
                .setTitle(if (av != null) "${av.free}/${av.known} szabad" else "${s.points.size} töltőpont")
                .addText("max. ${s.maxKw} kW · $plugs")
                .build()
        )
        pane.addRow(
            Row.Builder().setTitle(s.op.ifBlank { "Üzemeltető ismeretlen" })
                .apply { if (s.addr.isNotBlank()) addText(s.addr) }
                .build()
        )

        pane.addAction(
            Action.Builder().setTitle("Navigálás")
                .setBackgroundColor(CarColor.BLUE)
                .setOnClickListener { navigateTo(carContext, s.lat, s.lon, s.name) }
                .build()
        )
        PlanStore.plan.value?.let { plan ->
            pane.addAction(
                Action.Builder().setTitle("Tovább: ${plan.dest.name.substringBefore(',').take(16)}")
                    .setOnClickListener { navigateTo(carContext, plan.dest.lat, plan.dest.lon, plan.dest.name) }
                    .build()
            )
        }
        return PaneTemplate.Builder(pane.build()).setTitle(s.name).setHeaderAction(Action.BACK).build()
    }
}

// ------------------------------------------------------------------ destination search
class SearchScreen(ctx: CarContext) : Screen(ctx) {
    private var results: List<Place> = emptyList()
    private var loading = false
    private var message: String? = null

    override fun onGetTemplate(): Template {
        val cb = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}
            override fun onSearchSubmitted(searchText: String) { search(searchText) }
        }
        val b = SearchTemplate.Builder(cb)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Úti cél, pl. Békéscsaba")
            .setShowKeyboardByDefault(results.isEmpty())
        if (loading) return b.setLoading(true).build()
        val list = ItemList.Builder()
        results.forEach { p ->
            list.addItem(Row.Builder().setTitle(p.name).setOnClickListener { startPlan(p) }.build())
        }
        if (results.isEmpty()) list.setNoItemsMessage(message ?: "Add meg az úti célt (álló helyzetben vagy hanggal)")
        return b.setItemList(list.build()).build()
    }

    private fun search(text: String) {
        if (text.isBlank()) return
        loading = true; invalidate()
        lifecycleScope.launch {
            try {
                results = RoutePlanner.geocode(text)
                message = if (results.isEmpty()) "Nincs találat" else null
            } catch (e: Exception) {
                message = "Hiba a keresésnél: ${e.message}"
            }
            loading = false; invalidate()
        }
    }

    private fun startPlan(dest: Place) {
        val l = LocationHelper.last(carContext)
        if (l == null) {
            message = "A saját helyed nem ismert"; invalidate(); return
        }
        loading = true; invalidate()
        lifecycleScope.launch {
            try {
                val profile = ProfileStore.load(carContext)
                PlanStore.plan.value = RoutePlanner.plan(
                    Place("Saját hely", l.latitude, l.longitude), dest, profile, DataRepo.sites, profile.startSoc,
                    optimize = profile.optimize,
                )
                loading = false
                screenManager.pop()
                screenManager.push(RouteScreen(carContext))
            } catch (e: Exception) {
                loading = false
                message = "Útvonaltervezési hiba: ${e.message}"
                invalidate()
            }
        }
    }
}

// ------------------------------------------------------------------ planned route
class RouteScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val plan = PlanStore.plan.value
            ?: return MessageTemplate.Builder("Nincs tervezett útvonal").setHeaderAction(Action.BACK).build()
        val limit = listLimit(carContext)
        val list = ItemList.Builder()
        val planned = plan.stops.map { it.cand.site.id }.toSet()

        // 1) planned stops with amount + cost
        for (st in plan.stops) {
            val s = st.cand.site
            list.addItem(
                Row.Builder()
                    .setTitle("★ ${s.name}")
                    .addText("${st.cand.alongKm.roundToInt()}. km · ${Pricing.hhmm(st.etaMillis)} · ${Pricing.perKwh(s, st.quote.eur)}")
                    .addText("${st.arrivalSoc.roundToInt()}% → ${st.targetSoc.roundToInt()}% · ${st.minutes.roundToInt()} perc · ${Pricing.money(s, st.cost)}")
                    .setMetadata(Metadata.Builder().setPlace(placeFor(s, Pricing.label(s, st.quote.eur))).build())
                    .setOnClickListener { screenManager.push(DetailScreen(carContext, s.id)) }
                    .build()
            )
        }
        // 2) cheapest reachable alternatives
        plan.candidates.asSequence()
            .filter { it.reachable && it.quote != null && it.cand.site.id !in planned }
            .sortedWith(
                if (plan.optimize == "time") compareByDescending<app.ladepilot.data.CandidateView> { it.quote!!.kw }.thenBy { it.cand.offKm }
                else compareBy<app.ladepilot.data.CandidateView> { it.quote!!.eur }
            )
            .take((limit - plan.stops.size).coerceAtLeast(0))
            .forEach { v ->
                val s = v.cand.site
                val q = v.quote!!
                list.addItem(
                    Row.Builder()
                        .setTitle(s.name)
                        .addText("${v.cand.alongKm.roundToInt()}. km · ${Pricing.perKwh(s, q.eur)} · ${q.kw} kW")
                        .addText("érkezéskor ${v.arrivalSoc.roundToInt()}%")
                        .setMetadata(Metadata.Builder().setPlace(placeFor(s, Pricing.label(s, q.eur))).build())
                        .setOnClickListener { screenManager.push(DetailScreen(carContext, s.id)) }
                        .build()
                )
            }

        val destShort = plan.dest.name.substringBefore(',')
        val title = when {
            plan.stops.isEmpty() && plan.feasible -> "$destShort · töltés nem kell (${plan.arrivalSoc.roundToInt()}%)"
            !plan.feasible -> "$destShort · nem érhető el"
            else -> "$destShort · ${plan.distanceKm.roundToInt()} km"
        }
        if (plan.stops.isEmpty() && plan.candidates.none { it.reachable }) {
            list.setNoItemsMessage(plan.note ?: "Nincs töltő az útvonal mentén")
        }
        val first = plan.stops.firstOrNull()
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle(if (first != null) "Indulás" else "Navigálás").setOnClickListener {
                if (first != null) navigateTo(carContext, first.cand.site.lat, first.cand.site.lon, first.cand.site.name)
                else navigateTo(carContext, plan.dest.lat, plan.dest.lon, plan.dest.name)
            }.build())
            .addAction(Action.Builder().setTitle("Új cél").setOnClickListener { screenManager.push(SearchScreen(carContext)) }.build())
            .build()
        return PlaceListMapTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setCurrentLocationEnabled(LocationHelper.hasPermission(carContext))
            .setItemList(list.build())
            .setActionStrip(strip)
            .build()
    }
}
