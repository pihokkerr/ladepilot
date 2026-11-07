package app.ladepilot.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.ladepilot.data.Availability
import app.ladepilot.data.DataRepo
import app.ladepilot.data.Geo
import app.ladepilot.data.LocationHelper
import app.ladepilot.data.Nav
import app.ladepilot.data.Place
import app.ladepilot.data.Plan
import app.ladepilot.data.PlanStore
import app.ladepilot.data.Pricing
import app.ladepilot.data.Profile
import app.ladepilot.data.ProfileStore
import app.ladepilot.data.Quote
import app.ladepilot.data.RoutePlanner
import app.ladepilot.data.Site
import app.ladepilot.data.Tariff
import app.ladepilot.data.Tariffs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private val Bg = Color(0xFF0B0B0C)
private val Card1 = Color(0xFF16181C)
private val Muted = Color(0xFF9A9AA0)
private val Accent = Color(0xFF3E6AE1)
private val Good = Color(0xFF30D158)
private val Warn = Color(0xFFFFB340)
private val Bad = Color(0xFFFF453A)

private val Scheme = darkColorScheme(
    primary = Accent, onPrimary = Color.White,
    background = Bg, onBackground = Color(0xFFF4F4F5),
    surface = Card1, onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF1E1F23), onSurfaceVariant = Muted,
    secondaryContainer = Color(0xFF2A2C33), onSecondaryContainer = Color.White,
)

private object UiTick { val tick = MutableStateFlow(0) }

private fun powerColor(kw: Int) = when {
    kw >= 250 -> Color(0xFFFF3B30)
    kw >= 150 -> Warn
    kw > 22 -> Good
    else -> Color(0xFF8A94A6)
}

class MainActivity : ComponentActivity() {
    private val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        UiTick.tick.value = UiTick.tick.value + 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LocationHelper.hasPermission(this)) {
            perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        DataRepo.ensureLoaded(this)
        setContent { MaterialTheme(colorScheme = Scheme) { App() } }
    }

    override fun onResume() {
        super.onResume()
        DataRepo.ensureLoaded(this)
        UiTick.tick.value = UiTick.tick.value + 1
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(3) }
    var profile by remember { mutableStateOf(ProfileStore.load(ctx)) }
    val onProfile: (Profile) -> Unit = { p -> profile = p; ProfileStore.save(ctx, p) }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Card1) {
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Place, null) }, label = { Text("Térkép") })
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.LocationOn, null) }, label = { Text("Közeli") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Profil") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Bg)) {
            when (tab) {
                3 -> MapRouteTab(profile, onProfile)
                0 -> NearbyTab(profile)
                else -> ProfileTab(profile, onProfile)
            }
        }
    }
}

// ================================================================== shared bits
@Composable
private fun Header(title: String, sub: String?, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (sub != null) Text(sub, color = Muted, fontSize = 13.sp)
        }
        actions()
    }
}

@Composable
private fun PriceQ(site: Site, q: Quote, best: Boolean = false, big: Boolean = false) {
    if (q.free) {
        Text("Ingyenes", fontSize = if (big) 28.sp else 15.sp, fontWeight = FontWeight.Bold, color = Good)
    } else {
        Price(site, q.eur, best, big, prefix = if (q.estimated) "~" else "")
    }
}

@Composable
private fun Price(site: Site, eur: Double, best: Boolean = false, big: Boolean = false, prefix: String = "") {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(prefix + Pricing.label(site, eur), fontSize = if (big) 40.sp else 24.sp, fontWeight = FontWeight.Bold,
            color = if (best) Good else MaterialTheme.colorScheme.onSurface)
        Text(" " + Pricing.unit(site), fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = if (big) 8.dp else 3.dp))
    }
}

@Composable
private fun AvailText(av: Availability?) {
    when {
        av == null -> Text("nincs élő adat", color = Color(0xFF5C5C62), fontSize = 12.sp)
        av.free > 0 -> Text("${av.free}/${av.known} szabad", color = Good, fontSize = 12.sp)
        av.off == av.known -> Text("üzemen kívül", color = Bad, fontSize = 12.sp)
        else -> Text("foglalt", color = Warn, fontSize = 12.sp)
    }
}

@Composable
private fun KwBadge(kw: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(powerColor(kw), CircleShape))
        Text(" $kw kW", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun dataSummary(): String {
    val s = DataRepo.sites
    if (s.isEmpty()) return if (DataRepo.loading) "Adatok betöltése…" else (DataRepo.lastError ?: "Nincs adat")
    val by = s.groupingBy { it.src }.eachCount()
    return listOf("AT", "HU", "DE").filter { by.containsKey(it) }.joinToString(" · ") { "$it ${by[it]}" } +
        if (DataRepo.loading) " · frissítés…" else ""
}

// ================================================================== nearby
private data class NearRow(val site: Site, val dist: Double, val quote: Quote, val avail: Availability?)

@Composable
private fun NearbyTab(profile: Profile) {
    val ctx = LocalContext.current
    val ver by DataRepo.version.collectAsState()
    val tick by UiTick.tick.collectAsState()
    var minKw by remember { mutableIntStateOf(profile.minKw) }
    var detail by remember { mutableStateOf<Site?>(null) }
    val loc = remember(ver, tick) { LocationHelper.last(ctx) }
    val rows = remember(ver, tick, minKw, profile) {
        if (loc == null) emptyList() else {
            val now = System.currentTimeMillis()
            DataRepo.sites.asSequence()
                .filter { it.maxKw >= minKw }
                .map { it to Geo.fastKm(loc.latitude, loc.longitude, it.lat, it.lon) }
                .filter { it.second <= 40 }
                .mapNotNull { (s, d) -> Pricing.quote(s, minKw, profile, now)?.let { NearRow(s, d, it, Pricing.availability(s, minKw)) } }
                .sortedWith(compareBy({ it.quote.eur }, { it.dist }))
                .take(80).toList()
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("LADEPILOT", "${dataSummary()} helyszín") {
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profile.baseUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Térkép") }
            IconButton(onClick = { DataRepo.refreshNow(ctx); UiTick.tick.value++ }) { Icon(Icons.Filled.Refresh, "Frissítés") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 50, 150, 250).forEach { k ->
                FilterChip(selected = minKw == k, onClick = { minKw = k }, label = { Text(if (k == 0) "Mind" else "$k+ kW") })
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            !LocationHelper.hasPermission(ctx) -> Text("Engedélyezd a helymeghatározást az app beállításaiban.", color = Muted)
            loc == null -> Text("Saját hely keresése…", color = Muted)
            rows.isEmpty() && DataRepo.sites.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  ${dataSummary()}", color = Muted)
            }
            rows.isEmpty() -> Text("Nincs töltő árral 40 km-en belül.", color = Muted)
            else -> {
                val bestEur = rows.firstOrNull { !it.quote.free }?.quote?.eur ?: 0.0
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.site.id }) { r ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Card1),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().clickable { detail = r.site },
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(84.dp)) { PriceQ(r.site, r.quote, best = !r.quote.free && r.quote.eur <= bestEur + 0.02) }
                                Column(Modifier.weight(1f)) {
                                    Text(r.site.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${r.site.op} · ${Geo.fmtKm(r.dist)}" + (r.quote.tariff?.let { " · ${it.name}" } ?: ""),
                                        color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    KwBadge(r.quote.kw)
                                    AvailText(r.avail)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    detail?.let { SiteDialog(it, profile) { detail = null } }
}

@Composable
private fun SiteDialog(site: Site, profile: Profile, onAddStop: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val q = Pricing.quote(site, 0, profile)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card1,
        confirmButton = { Button(onClick = { Nav.open(ctx, Nav.mapsToSite(site)); onDismiss() }) { Text("Navigáció") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Bezár") } },
        title = { Text(site.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(site.op + if (site.addr.isNotBlank()) " · ${site.addr}" else "", color = Muted, fontSize = 13.sp)
                if (onAddStop != null) OutlinedButton(onClick = { onAddStop(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Megálló ide az útvonalon")
                }
                if (q != null) {
                    PriceQ(site, q, big = true)
                    if (q.estimated) Text(
                        if (q.baseEur == 0.0) "Percdíjas töltő – ${Pricing.eurText(q.eurMin ?: 0.0)}/perc, ${Pricing.effectiveKw(q.kw).roundToInt()} kW átlaggal számolva"
                        else "Benne: ${Pricing.perKwh(site, q.energyEur)} + ${Pricing.eurText(q.eurMin ?: 0.0)}/perc",
                        color = Muted, fontSize = 12.sp)
                    if (site.cur == "HUF") Text("≈ ${Pricing.eurText(q.eur)} / kWh", color = Muted, fontSize = 13.sp)
                    q.tariff?.let { Text("${it.name} – ad-hoc ár: ${Pricing.perKwh(site, q.baseEur)}", color = Muted, fontSize = 13.sp) }
                } else Text("Nincs árinformáció", color = Muted)
                Pricing.nextChange(site)?.let { (at, eur) ->
                    val eff = Pricing.applyTariffs(site, eur, profile.tariffs).first
                    Text("${Pricing.hhmm(at)}-tól ${Pricing.perKwh(site, eff)}", color = Warn, fontSize = 13.sp)
                }
                site.startFeeLocal?.let { Text("Indítási díj: ${it.roundToInt()} Ft", color = Muted, fontSize = 13.sp) }
                site.idleFeeLocal?.let { Text("Blokkolási díj: ${it.roundToInt()} Ft/perc", color = Muted, fontSize = 13.sp) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                site.points.groupBy { Triple(it.kw, it.plugs, it.eurKwh) }.forEach { (k, pts) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KwBadge(k.first)
                        Text("  ${k.second} · ${pts.size} db", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        val known = pts.count { it.status != 0 }
                        if (known > 0) Text("${pts.count { it.status == 1 }}/$known szabad", fontSize = 13.sp,
                            color = if (pts.any { it.status == 1 }) Good else Warn)
                    }
                }
                Text(
                    when (site.src) {
                        "HU" -> "Forrás: OTPR (ELMOB). Élő foglaltság nélkül."
                        "DE" -> "Forrás: Mobilithek (Tesla, AFIR)."
                        else -> "Forrás: E-Control ladestellen.at, élő adat."
                    }, color = Color(0xFF5C5C62), fontSize = 11.sp,
                )
            }
        },
    )
}

// ================================================================== map + route planner (one screen)
private object RouteUi {
    /** null = own location */
    val origin = MutableStateFlow<Place?>(null)
    val dest = MutableStateFlow<Place?>(null)
    val fixed = MutableStateFlow<List<String>>(emptyList())
    val excluded = MutableStateFlow<Set<String>>(emptySet())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapRouteTab(profile: Profile, onProfile: (Profile) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val plan by PlanStore.plan.collectAsState()
    val ver by DataRepo.version.collectAsState()
    val tick by UiTick.tick.collectAsState()
    val origin by RouteUi.origin.collectAsState()
    val dest by RouteUi.dest.collectAsState()
    val fixed by RouteUi.fixed.collectAsState()
    val excluded by RouteUi.excluded.collectAsState()
    val prof by rememberUpdatedState(profile)
    var soc by remember { mutableFloatStateOf(plan?.startSoc?.toFloat() ?: profile.startSoc.toFloat()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Site?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }   // 0 = origin, 1 = destination
    val holder = remember { MapHolder(ctx) { s -> detail = s } }

    DisposableEffect(Unit) {
        holder.view.onResume()
        onDispose { holder.view.onPause() }
    }
    SideEffect { holder.profile = profile; holder.minKw = profile.minKw }
    LaunchedEffect(ver, tick, profile) {
        val l = LocationHelper.last(ctx)
        holder.pins.me = l?.let { org.osmdroid.util.GeoPoint(it.latitude, it.longitude) }
        if (plan == null && l != null && ver <= 2) holder.view.controller.setCenter(org.osmdroid.util.GeoPoint(l.latitude, l.longitude))
        holder.refresh()
    }
    LaunchedEffect(plan) { holder.setPlan(plan) }

    fun replan(optimize: String = prof.optimize) {
        val d = RouteUi.dest.value ?: return
        val o = RouteUi.origin.value ?: LocationHelper.last(ctx)?.let { Place("Saját hely", it.latitude, it.longitude) }
        if (o == null) { error = "A saját helyed nem ismert – engedélyezd a helymeghatározást."; return }
        busy = true; error = null
        scope.launch {
            try {
                PlanStore.plan.value = RoutePlanner.plan(
                    o, d, prof, DataRepo.sites, soc.roundToInt(),
                    fixedStopIds = RouteUi.fixed.value, excludedIds = RouteUi.excluded.value, optimize = optimize,
                )
            } catch (e: Exception) {
                error = "Útvonaltervezési hiba: ${e.message}"
            }
            busy = false
        }
    }

    val sheet = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    )
    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = if (plan != null) 210.dp else 110.dp,
        sheetContainerColor = Card1,
        containerColor = Bg,
        sheetContent = {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  Tervezés…", color = Muted) } }
                error?.let { e -> item { Text(e, color = Bad) } }
                val p = plan
                if (p == null) {
                    item {
                        Text(if (dest == null) "Hová mész? Add meg fent az úti célt." else "Tervezés…", fontWeight = FontWeight.SemiBold)
                        Text(dataSummary() + " helyszín", color = Muted, fontSize = 12.sp)
                    }
                } else {
                    item { PlanSummary(p) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NavAppButton("com.google.android.apps.maps", "Google Maps", Modifier.weight(1f)) {
                                Nav.open(ctx, Nav.mapsRouteUri(p.dest, p.stops.map { it.cand.site }))
                            }
                            NavAppButton("com.waze", "Waze", Modifier.weight(1f)) {
                                val first = p.stops.firstOrNull()?.cand?.site
                                if (first != null) Nav.waze(ctx, first.lat, first.lon) else Nav.waze(ctx, p.dest.lat, p.dest.lon)
                            }
                        }
                        if (p.stops.isNotEmpty()) Text("A Waze csak egy célt fogad: először az első töltőhöz navigál.", color = Muted, fontSize = 11.sp)
                    }
                }
                // battery + ranking
                item {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Akku indulásnál", modifier = Modifier.weight(1f), color = Muted)
                            Text("${soc.roundToInt()} %", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Slider(value = soc, onValueChange = { soc = it }, valueRange = 5f..100f, onValueChangeFinished = { replan() })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = profile.optimize == "price", onClick = {
                                onProfile(profile.copy(optimize = "price")); replan("price")
                            }, label = { Text("Legolcsóbb") })
                            FilterChip(selected = profile.optimize == "time", onClick = {
                                onProfile(profile.copy(optimize = "time")); replan("time")
                            }, label = { Text("Leggyorsabb") })
                        }
                    }
                }
                if (p != null) {
                    if (p.stops.isNotEmpty() || fixed.isNotEmpty() || excluded.isNotEmpty()) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MEGÁLLÓK", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                            if (fixed.isNotEmpty() || excluded.isNotEmpty()) TextButton(onClick = {
                                RouteUi.fixed.value = emptyList(); RouteUi.excluded.value = emptySet(); replan()
                            }) { Text("Automatikus") }
                        }
                    }
                    items(p.stops, key = { "s" + it.cand.site.id }) { st ->
                        StopCard(st,
                            onClick = { holder.view.controller.animateTo(org.osmdroid.util.GeoPoint(st.cand.site.lat, st.cand.site.lon), 13.0, 600L) },
                            onRemove = {
                                val id = st.cand.site.id
                                RouteUi.fixed.value = RouteUi.fixed.value - id
                                RouteUi.excluded.value = RouteUi.excluded.value + id
                                replan()
                            })
                    }
                    val stopIds = p.stops.map { it.cand.site.id }.toSet()
                    val others = p.candidates.filter { it.quote != null && it.cand.site.id !in stopIds && it.cand.site.id !in excluded }
                        .sortedWith(
                            if (p.optimize == "time") compareByDescending<app.ladepilot.data.CandidateView> { it.quote!!.kw }.thenBy { it.cand.offKm }
                            else compareBy<app.ladepilot.data.CandidateView> { it.quote!!.eur }
                        )
                    if (others.isNotEmpty()) item {
                        Text("TOVÁBBI TÖLTŐK AZ ÚTON", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    items(others.take(40), key = { "c" + it.cand.site.id }) { v ->
                        val s = v.cand.site
                        val q = v.quote!!
                        Card(colors = CardDefaults.cardColors(containerColor = Bg), shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable { detail = s }) {
                            Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(72.dp)) { PriceQ(s, q) }
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${v.cand.alongKm.roundToInt()}. km · ${q.kw} kW · ${s.op}", color = Muted, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = {
                                    RouteUi.fixed.value = (RouteUi.fixed.value + s.id).distinct()
                                    RouteUi.excluded.value = RouteUi.excluded.value - s.id
                                    replan()
                                }) { Text("+ Megálló") }
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Térképen:", color = Muted, fontSize = 12.sp)
                        listOf(0, 50, 150, 250).forEach { k ->
                            FilterChip(selected = profile.minKw == k, onClick = { onProfile(profile.copy(minKw = k)) },
                                label = { Text(if (k == 0) "Mind" else "$k+") })
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            AndroidView(factory = { holder.view }, modifier = Modifier.fillMaxSize())

            // ---- origin / destination card
            Card(colors = CardDefaults.cardColors(containerColor = Card1.copy(alpha = 0.96f)), shape = RoundedCornerShape(18.dp),
                modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    EndpointRow(Color.White, origin?.name ?: "Saját hely", false) { editing = 0 }
                    HorizontalDivider(Modifier.padding(start = 44.dp), color = Color(0x22FFFFFF))
                    EndpointRow(Bad, dest?.name ?: "Hová mész?", dest == null) { editing = 1 }
                }
            }
            // ---- my location
            IconButton(
                onClick = {
                    LocationHelper.last(ctx)?.let { holder.view.controller.animateTo(org.osmdroid.util.GeoPoint(it.latitude, it.longitude), 13.0, 600L) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp).size(52.dp)
                    .background(Card1, CircleShape),
            ) { Icon(Icons.Filled.LocationOn, "Saját hely") }

            if (editing != null) EndpointSearch(
                which = editing!!,
                onClose = { editing = null },
                onPick = { place ->
                    if (editing == 0) RouteUi.origin.value = place else RouteUi.dest.value = place
                    RouteUi.fixed.value = emptyList(); RouteUi.excluded.value = emptySet()
                    editing = null
                    replan()
                },
            )
        }
    }
    detail?.let { s ->
        SiteDialog(s, profile, onAddStop = if (plan != null && plan!!.candidates.any { it.cand.site.id == s.id }) ({
            RouteUi.fixed.value = (RouteUi.fixed.value + s.id).distinct()
            RouteUi.excluded.value = RouteUi.excluded.value - s.id
            replan()
        }) else null) { detail = null }
    }
}

@Composable
private fun EndpointRow(dot: Color, text: String, placeholder: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(dot, CircleShape))
        Spacer(Modifier.width(16.dp))
        Text(text, color = if (placeholder) Muted else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (placeholder) FontWeight.Normal else FontWeight.SemiBold)
    }
}

/** Full-screen search for origin (0) or destination (1). null place = own location. */
@Composable
private fun EndpointSearch(which: Int, onClose: () -> Unit, onPick: (Place?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    fun search() {
        if (query.isBlank()) return
        busy = true; msg = null
        scope.launch {
            try {
                results = RoutePlanner.geocode(query)
                if (results.isEmpty()) msg = "Nincs találat"
            } catch (e: Exception) { msg = "Keresési hiba: ${e.message}" }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (which == 0) "Indulás" else "Úti cél", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Bezár") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (which == 0) "Honnan?" else "Hová? pl. Békéscsaba") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().clickable {
                if (which == 0) onPick(null) else {
                    val l = LocationHelper.last(ctx)
                    if (l != null) onPick(Place("Saját hely", l.latitude, l.longitude)) else msg = "A saját helyed nem ismert"
                }
            }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, null, tint = Accent)
                Text("  Saját hely használata", fontWeight = FontWeight.SemiBold)
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        msg?.let { Text(it, color = Muted) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { p ->
                Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onPick(p) }) {
                    Text(p.name, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }
}

@Composable
private fun NavAppButton(pkg: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val icon = remember(pkg) {
        try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null }
    }
    OutlinedButton(onClick = onClick, enabled = icon != null, modifier = modifier.height(52.dp)) {
        if (icon != null) Image(icon, null, Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (icon != null) label else "$label (nincs telepítve)", maxLines = 1)
    }
}

private fun fmtDuration(sec: Double): String {
    val m = (sec / 60).roundToInt()
    return if (m >= 60) "${m / 60} ó ${m % 60} p" else "$m perc"
}

@Composable
private fun PlanSummary(p: Plan) {
    Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("→ ${p.dest.name}", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${p.distanceKm.roundToInt()} km · vezetés ${fmtDuration(p.durationSec)} · érkezés ${Pricing.hhmm(p.arrivalMillis)}", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            when {
                !p.feasible -> Text(p.note ?: "Nem tervezhető", color = Bad)
                p.stops.isEmpty() -> Text("Töltés nélkül odaérsz – érkezéskor kb. ${p.arrivalSoc.roundToInt()}%", color = Good, fontWeight = FontWeight.SemiBold)
                else -> {
                    Row {
                        Stat("Töltés", "${p.stops.size}×")
                        Stat("Idő", fmtDuration(p.chargeMinutes * 60))
                        Stat("Költség", Pricing.eurText(p.totalCost))
                        Stat("Érkezés", "${p.arrivalSoc.roundToInt()}%")
                    }
                    p.note?.let { Text(it, color = Warn, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.widthIn(min = 76.dp).padding(end = 12.dp)) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun StopCard(st: app.ladepilot.data.ChargeStop, onClick: () -> Unit = {}, onRemove: (() -> Unit)? = null) {
    val s = st.cand.site
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2340)), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${st.cand.alongKm.roundToInt()}. km · érkezés ${Pricing.hhmm(st.etaMillis)} · ${s.op}", color = Muted, fontSize = 12.sp)
                }
                PriceQ(s, st.quote)
                if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Megálló törlése", tint = Muted) }
            }
            Text(
                "${st.arrivalSoc.roundToInt()}% → ${st.targetSoc.roundToInt()}%  ·  ${st.kwhBattery.roundToInt()} kWh  ·  ${st.minutes.roundToInt()} perc  ·  ${Pricing.money(s, st.cost)}",
                fontWeight = FontWeight.SemiBold,
            )
            st.quote.tariff?.let { Text("Tarifa: ${it.name}", color = Muted, fontSize = 12.sp) }
        }
    }
}

// ================================================================== profile
@Composable
private fun ProfileTab(profile: Profile, onProfile: (Profile) -> Unit) {
    var cap by remember(profile) { mutableStateOf(profile.capacityKwh.toString()) }
    var summer by remember(profile) { mutableStateOf(profile.consSummer.toString()) }
    var winter by remember(profile) { mutableStateOf(profile.consWinter.toString()) }
    var margin by remember(profile) { mutableStateOf(profile.marginPct.toString()) }
    var reserve by remember(profile) { mutableStateOf(profile.reservePct.toString()) }
    var maxSoc by remember(profile) { mutableStateOf(profile.maxChargeSoc.toString()) }
    var routeKw by remember(profile) { mutableStateOf(profile.routeMinKw.toString()) }
    var baseUrl by remember(profile) { mutableStateOf(profile.baseUrl) }
    var adding by remember { mutableStateOf(false) }

    fun d(s: String, def: Double) = s.replace(',', '.').toDoubleOrNull() ?: def
    fun i(s: String, def: Int) = s.trim().toIntOrNull() ?: def

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Header("Profil", "Tesla Model Y Long Range AWD") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AUTÓ ÉS FOGYASZTÁS", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Akku (kWh)", cap, { cap = it }, Modifier.weight(1f))
                        NumField("Tartalék (%)", reserve, { reserve = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Nyár kWh/100", summer, { summer = it }, Modifier.weight(1f))
                        NumField("Tél kWh/100", winter, { winter = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Ráhagyás (%)", margin, { margin = it }, Modifier.weight(1f))
                        NumField("Max. töltés (%)", maxSoc, { maxSoc = it }, Modifier.weight(1f))
                    }
                    NumField("Útvonalon min. kW", routeKw, { routeKw = it }, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "Évszak: auto", "summer" to "Nyár", "winter" to "Tél").forEach { (k, l) ->
                            FilterChip(selected = profile.season == k, onClick = { onProfile(profile.copy(season = k)) }, label = { Text(l) })
                        }
                    }
                    Text("Autópályás átlag a fórumok alapján: nyáron kb. 18–20, télen 22–25 kWh/100 km (130 km/h).", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true,
                        label = { Text("Adatszerver") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        onProfile(profile.copy(
                            capacityKwh = d(cap, profile.capacityKwh), consSummer = d(summer, profile.consSummer),
                            consWinter = d(winter, profile.consWinter), marginPct = i(margin, profile.marginPct),
                            reservePct = i(reserve, profile.reservePct).coerceIn(0, 50),
                            maxChargeSoc = i(maxSoc, profile.maxChargeSoc).coerceIn(50, 100),
                            routeMinKw = i(routeKw, profile.routeMinKw), baseUrl = baseUrl.trim().ifBlank { profile.baseUrl },
                        ))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Mentés") }
                }
            }
        }
        item { Text("KÁRTYÁIM / TARIFÁIM", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        items(profile.tariffs, key = { it.id }) { t ->
            Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.SemiBold)
                        val unit = if (t.country == "HU") "Ft" else "€"
                        val what = when (t.mode) {
                            "fixed" -> "${fmtNum(t.value)} $unit/kWh"
                            "percent" -> "−${fmtNum(t.value)}%"
                            else -> "−${fmtNum(t.value)} $unit/kWh"
                        }
                        Text("${t.country} · ${t.match.ifBlank { "minden töltő" }} · $what" + (if (t.note.isNotBlank()) " · ${t.note}" else ""),
                            color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = t.enabled, onCheckedChange = { on ->
                        onProfile(profile.copy(tariffs = profile.tariffs.map { if (it.id == t.id) it.copy(enabled = on) else it }))
                    })
                    if (Tariffs.presets.none { it.id == t.id }) {
                        IconButton(onClick = { onProfile(profile.copy(tariffs = profile.tariffs.filter { it.id != t.id })) }) {
                            Icon(Icons.Filled.Delete, "Törlés", tint = Muted)
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Saját tarifa hozzáadása") } }
        item {
            Text("Példa: Tesla-tulajdonosi ár Ausztriában → ország AT, szöveg „Tesla”, fix ár 0,48 €/kWh. " +
                "Ionity Power → szöveg „IONITY”, fix ár a szerződésed szerint.", color = Muted, fontSize = 12.sp)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (adding) AddTariffDialog(onDismiss = { adding = false }) { t ->
        onProfile(profile.copy(tariffs = profile.tariffs + t)); adding = false
    }
}

private fun fmtNum(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format(Locale.GERMANY, "%.2f", v)

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

@Composable
private fun AddTariffDialog(onDismiss: () -> Unit, onAdd: (Tariff) -> Unit) {
    var name by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("AT") }
    var match by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("fixed") }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card1,
        title = { Text("Saját tarifa") },
        confirmButton = {
            Button(enabled = name.isNotBlank() && value.replace(',', '.').toDoubleOrNull() != null, onClick = {
                onAdd(Tariff("u-" + System.currentTimeMillis(), name.trim(), country, match.trim(), mode,
                    value.replace(',', '.').toDouble(), true))
            }) { Text("Hozzáadás") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Név, pl. Tesla AT") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("AT", "HU", "DE", "*").forEach { c ->
                        FilterChip(selected = country == c, onClick = { country = c }, label = { Text(if (c == "*") "Mind" else c) })
                    }
                }
                OutlinedTextField(match, { match = it }, label = { Text("Üzemeltető (szövegrész)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("fixed" to "Fix ár", "discount" to "Kedvezmény", "percent" to "%").forEach { (k, l) ->
                        FilterChip(selected = mode == k, onClick = { mode = k }, label = { Text(l) })
                    }
                }
                val unit = when { mode == "percent" -> "%"; country == "HU" -> "Ft/kWh"; else -> "€/kWh" }
                NumField("Érték ($unit)", value, { value = it }, Modifier.fillMaxWidth())
            }
        },
    )
}
