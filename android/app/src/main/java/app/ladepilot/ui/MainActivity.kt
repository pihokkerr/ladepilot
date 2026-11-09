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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.key
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

/** Text shared to the app (e.g. a Google Maps place link) waiting to become the destination. */
private object ShareIn { val text = MutableStateFlow<String?>(null) }

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
        handleShare(intent)
        setContent { MaterialTheme(colorScheme = Scheme) { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            ShareIn.text.value = t
        }
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
    val shared by ShareIn.text.collectAsState()
    LaunchedEffect(shared) { if (shared != null) tab = 3 }
    val onProfile: (Profile) -> Unit = { p -> profile = p; ProfileStore.save(ctx, p) }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Card1) {
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Place, null) }, label = { Text("Térkép") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Person, null) }, label = { Text("Profil") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Bg)) {
            when (tab) {
                3 -> MapRouteTab(profile, onProfile)
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

@Composable
private fun DataInfo() {
    val ctx = LocalContext.current
    val ver by DataRepo.version.collectAsState()
    val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" } }
    val rows = remember(ver) {
        val by = DataRepo.sites.groupingBy { it.src }.eachCount()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd. HH:mm")
        listOf("AT" to "Ausztria", "DE" to "Németország", "HU" to "Magyarország", "IT" to "Olaszország", "HR" to "Horvátország").map { (code, name) ->
            val g = DataRepo.generated[code]?.let {
                try { java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(fmt) } catch (e: Exception) { it }
            } ?: "–"
            Triple(name, by[code] ?: 0, g)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Ladepilot $version", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        for ((name, n, g) in rows) {
            Row(Modifier.fillMaxWidth()) {
                Text(name, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("%,d töltőhely".format(n).replace(',', ' '), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                Text(g, color = Muted, fontSize = 12.sp)
            }
        }
        if (DataRepo.loading) Text("Frissítés folyamatban…", color = Muted, fontSize = 12.sp)
    }
}

private fun dataSummary(): String {
    val s = DataRepo.sites
    if (s.isEmpty()) return if (DataRepo.loading) "Adatok betöltése…" else (DataRepo.lastError ?: "Nincs adat")
    val by = s.groupingBy { it.src }.eachCount()
    return listOf("AT", "HU", "DE", "IT", "HR").filter { by.containsKey(it) }.joinToString(" · ") { "$it ${by[it]}" } +
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
                        if (q.approx) "Becsült ár: a szolgáltató nyilvános ad-hoc listaára (legfeljebb ennyi, helyszínenként olcsóbb is lehet)."
                        else if (q.baseEur == 0.0) "Percdíjas töltő – ${Pricing.eurText(q.eurMin ?: 0.0)}/perc, ${Pricing.effectiveKw(q.kw).roundToInt()} kW átlaggal számolva"
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
                        "IT" -> "Forrás: Piattaforma Unica Nazionale (MASE). Ár csak ott, ahol a szolgáltató megadta."
                        "HR" -> "Forrás: Open Charge Map (CC BY 4.0). Ár: szolgáltatói listaár vagy közösségi adat – becslés."
                        "DE" -> if (site.id.startsWith("DE-BNA-")) "Helyszín: Bundesnetzagentur Ladesäulenregister. Ár: a szolgáltató listaára." else "Forrás: Mobilithek (AFIR, CC0). Foglaltság: ha a szolgáltató küldi."
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
    /** intermediate destinations, in the order the user arranged them */
    val vias = MutableStateFlow<List<Place>>(emptyList())

    /** "my location" placeholder that can sit anywhere in the list (resolved when planning) */
    val OWN = Place("Saját hely", Double.NaN, Double.NaN)
    fun isOwn(p: Place?) = p == null || p.lat.isNaN()

    /** start, vias, destination as one list (destination only if set) */
    fun points(): List<Place> = listOf(origin.value ?: OWN) + vias.value + listOfNotNull(dest.value)

    fun setPoints(list: List<Place>) {
        if (list.isEmpty()) return
        origin.value = list.first().takeUnless { isOwn(it) }
        if (list.size >= 2) {
            dest.value = list.last()
            vias.value = list.subList(1, list.size - 1)
        } else {
            dest.value = null; vias.value = emptyList()
        }
        fixed.value = emptyList(); excluded.value = emptySet()
    }
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
    val vias by RouteUi.vias.collectAsState()
    val prof by rememberUpdatedState(profile)
    var opsDialog by remember { mutableStateOf(false) }
    var soc by remember { mutableFloatStateOf(plan?.startSoc?.toFloat() ?: profile.startSoc.toFloat()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Site?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }   // 0 = origin, 1 = destination, 2 = new via, 10+i = via i
    val holder = remember { MapHolder(ctx) { s -> detail = s } }
    val near by holder.visible.collectAsState()

    DisposableEffect(Unit) {
        holder.onStart(); holder.onResume()
        onDispose { holder.onPause(); holder.onStop(); holder.onDestroy() }
    }
    SideEffect { holder.profile = profile; holder.minKw = profile.minKw; holder.applyStyle(profile.mapStyle) }
    LaunchedEffect(ver, tick, profile) {
        val l = LocationHelper.last(ctx)
        if (l != null) holder.me = org.maplibre.android.geometry.LatLng(l.latitude, l.longitude)
        holder.refresh()
    }
    LaunchedEffect(plan) { holder.setPlan(plan) }
    val shared by ShareIn.text.collectAsState()

    fun resolve(p: Place?): Place? =
        if (RouteUi.isOwn(p)) LocationHelper.last(ctx)?.let { Place("Saját hely", it.latitude, it.longitude) } else p

    fun replan(optimize: String = prof.optimize) {
        if (RouteUi.dest.value == null) return
        val d = resolve(RouteUi.dest.value)
        val o = resolve(RouteUi.origin.value)
        val vs = RouteUi.vias.value.map { resolve(it) }
        if (o == null || d == null || vs.any { it == null }) { error = "A saját helyed nem ismert – engedélyezd a helymeghatározást."; return }
        busy = true; error = null
        scope.launch {
            try {
                PlanStore.plan.value = RoutePlanner.plan(
                    o, d, prof, DataRepo.sites, soc.roundToInt(),
                    fixedStopIds = RouteUi.fixed.value, excludedIds = RouteUi.excluded.value, optimize = optimize,
                    vias = vs.filterNotNull(),
                )
            } catch (e: Exception) {
                error = "Útvonaltervezési hiba: ${e.message}"
            }
            busy = false
        }
    }

    LaunchedEffect(shared) {
        val t = shared ?: return@LaunchedEffect
        ShareIn.text.value = null
        busy = true; error = null
        val near = LocationHelper.last(ctx)?.let { Place("", it.latitude, it.longitude) }
        val found = try { RoutePlanner.geocode(t, near) } catch (e: Exception) { emptyList() }
        busy = false
        val p = found.firstOrNull()
        if (p == null) { error = "A megosztott helyet nem találtam: ${t.take(80)}"; return@LaunchedEffect }
        val pts = RouteUi.points()
        RouteUi.setPoints(if (RouteUi.dest.value == null) pts + p else pts.dropLast(1) + p)
        replan()
    }

    val sheet = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    )
    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = if (plan != null) 64.dp else 76.dp,
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
                if (p != null) item {
                    val total = (p.arrivalMillis - p.startMillis) / 1000.0
                    Text(
                        "${p.distanceKm.roundToInt()} km · ${fmtDuration(total)} · " +
                            (if (p.stops.isEmpty()) "töltés nélkül" else "${p.stops.size}× töltés · ${Pricing.eurText(p.totalCost)}") +
                            " · érk. ${Pricing.hhmm(p.arrivalMillis)}",
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (p == null) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LEGOLCSÓBB A TÉRKÉPEN", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                            Text(dataSummary(), color = Color(0xFF5C5C62), fontSize = 11.sp)
                        }
                    }
                    if (near.isEmpty()) item { Text(if (DataRepo.sites.isEmpty()) "Adatok betöltése…" else "Ezen a területen nincs töltő árral – húzd odébb a térképet.", color = Muted) }
                    val bestEur = near.firstOrNull { !it.quote.free }?.quote?.eur ?: 0.0
                    items(near, key = { "n" + it.site.id }) { r ->
                        Card(colors = CardDefaults.cardColors(containerColor = Bg), shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable { holder.flyTo(r.site.lat, r.site.lon, 14.0); detail = r.site }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(80.dp)) { PriceQ(r.site, r.quote, best = !r.quote.free && r.quote.eur <= bestEur + 0.02) }
                                Column(Modifier.weight(1f)) {
                                    Text(r.site.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${r.site.op} · ${Geo.fmtKm(r.distKm)}" + (r.quote.tariff?.let { " · ${it.name}" } ?: ""),
                                        color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    KwBadge(r.quote.kw)
                                    AvailText(Pricing.availability(r.site, profile.minKw))
                                }
                            }
                        }
                    }
                } else {
                    item { PlanSummary(p) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NavAppButton("com.google.android.apps.maps", "Google Maps", Modifier.weight(1f)) {
                                Nav.open(ctx, Nav.mapsRouteUri(p.dest, p.navPoints))
                            }
                            NavAppButton("com.waze", "Waze", Modifier.weight(1f)) {
                                val first = p.navPoints.firstOrNull()
                                if (first != null) Nav.waze(ctx, first.first, first.second) else Nav.waze(ctx, p.dest.lat, p.dest.lon)
                            }
                        }
                        if (p.navPoints.isNotEmpty()) Text("A Google Maps útvonal-előnézettel nyílik (minden megálló + végcél) – ott nyomd meg az Indítást. A Waze csak egy célt fogad: először az első megállóhoz navigál.", color = Muted, fontSize = 11.sp)
                        if (p.navPoints.size > 9) Text("A Google Maps legfeljebb 9 megállót fogad – a többit útközben add hozzá.", color = Warn, fontSize = 11.sp)
                    }
                }
                // battery + ranking
                if (dest != null) item {
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
                            onClick = { holder.flyTo(st.cand.site.lat, st.cand.site.lon) },
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
                item { Spacer(Modifier.height(24.dp)) }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            AndroidView(factory = { holder.view }, modifier = Modifier.fillMaxSize())

            // ---- origin / vias / destination card + filters
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Card(colors = CardDefaults.cardColors(containerColor = Card1.copy(alpha = 0.96f)), shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        if (dest == null) {
                            EndpointRow(Color.White, origin?.name ?: "Saját hely", false,
                                onClear = if (origin != null) ({ RouteUi.origin.value = null }) else null,
                            ) { editing = 100 }
                            HorizontalDivider(Modifier.padding(start = 44.dp), color = Color(0x22FFFFFF))
                            EndpointRow(Bad, "Hová mész?", true) { editing = 1 }
                        } else {
                            PointRows(
                                points = listOf(origin ?: RouteUi.OWN) + vias + listOf(dest!!),
                                onEdit = { i -> editing = 100 + i },
                                onRemove = { i ->
                                    val pts = RouteUi.points()
                                    when {
                                        i == 0 -> RouteUi.setPoints(listOf(RouteUi.OWN) + pts.drop(1))
                                        pts.size <= 2 -> {   // removing the destination of a simple route
                                            RouteUi.setPoints(pts.take(1)); PlanStore.plan.value = null
                                            LocationHelper.last(ctx)?.let { holder.flyTo(it.latitude, it.longitude, 12.0) }
                                        }
                                        else -> RouteUi.setPoints(pts.filterIndexed { j, _ -> j != i })
                                    }
                                    replan()
                                },
                                onSwap = { RouteUi.setPoints(RouteUi.points().reversed()); replan() },
                                onDragEnd = { RouteUi.fixed.value = emptyList(); RouteUi.excluded.value = emptySet(); replan() },
                            )
                        }
                        if (dest != null) Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { editing = 2 }) {
                                Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text(" Köztes cél")
                            }
                            Spacer(Modifier.weight(1f))
                            if (vias.size >= 2) TextButton(onClick = {
                                val o = resolve(RouteUi.origin.value)
                                val d = resolve(RouteUi.dest.value)
                                if (o != null && d != null && RouteUi.vias.value.none { RouteUi.isOwn(it) }) {
                                    busy = true
                                    scope.launch {
                                        try {
                                            RouteUi.vias.value = RoutePlanner.optimizeOrder(o, RouteUi.vias.value, d)
                                            RouteUi.fixed.value = emptyList(); RouteUi.excluded.value = emptySet()
                                        } catch (e: Exception) { error = "Optimalizálási hiba: ${e.message}" }
                                        busy = false
                                        replan()
                                    }
                                }
                            }) { Text("Sorrend optimalizálása") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 50, 150, 250).forEach { k ->
                        MapChip(profile.minKw == k, if (k == 0) "Minden töltő" else "$k+ kW") { onProfile(profile.copy(minKw = k)) }
                    }
                    MapChip(profile.excludedOps.isNotEmpty(),
                        if (profile.excludedOps.isEmpty()) "Szolgáltatók" else "Szolgáltatók (${profile.excludedOps.size} rejtve)") { opsDialog = true }
                }
            }
            // ---- my location
            IconButton(
                onClick = {
                    LocationHelper.last(ctx)?.let {
                        holder.me = org.maplibre.android.geometry.LatLng(it.latitude, it.longitude)
                        holder.flyTo(it.latitude, it.longitude, 13.0)
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp).size(52.dp)
                    .background(Card1, CircleShape),
            ) { Icon(Icons.Filled.LocationOn, "Saját hely") }

            if (editing != null) EndpointSearch(
                which = editing!!,
                onClose = { editing = null },
                onPick = { place ->
                    val e = editing ?: 0
                    val pt = place ?: RouteUi.OWN
                    when {
                        e == 1 -> RouteUi.setPoints(RouteUi.points() + pt)                       // set destination
                        e == 2 -> RouteUi.points().let { p -> RouteUi.setPoints(p.dropLast(1) + pt + p.last()) }   // add via
                        e >= 100 -> RouteUi.points().let { p ->
                            val i = e - 100
                            if (i < p.size) RouteUi.setPoints(p.mapIndexed { j, v -> if (j == i) pt else v })
                        }
                    }
                    editing = null
                    replan()
                },
            )
        }
    }
    if (opsDialog) OperatorDialog(profile.excludedOps, onDismiss = { opsDialog = false }) { set ->
        onProfile(profile.copy(excludedOps = set)); opsDialog = false
        if (RouteUi.dest.value != null) replan()
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
private fun MapChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) Color(0xFFF4F4F5) else Card1.copy(alpha = 0.94f), RoundedCornerShape(18.dp))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(label, color = if (selected) Color(0xFF0B0B0C) else Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Start, intermediate destinations and destination; every row can be dragged by its handle. */
@Composable
private fun PointRows(points: List<Place>, onEdit: (Int) -> Unit, onRemove: (Int) -> Unit, onSwap: () -> Unit, onDragEnd: () -> Unit) {
    val rowH = with(LocalDensity.current) { 48.dp.toPx() }
    var dragged by remember { mutableStateOf<Place?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    Box {
        Column {
            points.forEachIndexed { i, v ->
                key(v.name, v.lat, v.lon) {
                    val isDragged = dragged == v
                    val dot = when (i) { 0 -> Color.White; points.lastIndex -> Bad; else -> Color(0xFFFFD60A) }
                    Row(
                        Modifier.fillMaxWidth().height(48.dp)
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragged) offset else 0f }
                            .background(if (isDragged) Color(0xFF26282E) else Color.Transparent)
                            .clickable { onEdit(i) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Menu, "Áthelyezés", tint = Muted,
                            modifier = Modifier.size(44.dp).padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)
                                .pointerInput(v) {
                                    detectDragGestures(
                                        onDragStart = { dragged = v; offset = 0f },
                                        onDragEnd = { dragged = null; offset = 0f; onDragEnd() },
                                        onDragCancel = { dragged = null; offset = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            offset += amount.y
                                            val list = RouteUi.points()
                                            val cur = list.indexOf(dragged ?: return@detectDragGestures)
                                            if (cur < 0) return@detectDragGestures
                                            val target = when {
                                                offset > rowH / 2 && cur < list.lastIndex -> cur + 1
                                                offset < -rowH / 2 && cur > 0 -> cur - 1
                                                else -> null
                                            }
                                            if (target != null) {
                                                val l = list.toMutableList()
                                                l.add(target, l.removeAt(cur))
                                                RouteUi.setPoints(l)
                                                offset += if (target > cur) -rowH else rowH
                                            }
                                        },
                                    )
                                })
                        Box(Modifier.size(10.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (i == 0 && points.size == 2) IconButton(onClick = onSwap, modifier = Modifier.size(40.dp)) {
                            Text("⇅", fontSize = 20.sp, color = Muted)
                        }
                        if (!(i == 0 && RouteUi.isOwn(v))) IconButton(onClick = { onRemove(i) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, "Törlés", tint = Muted)
                        } else Spacer(Modifier.size(40.dp))
                    }
                    if (i < points.lastIndex) HorizontalDivider(Modifier.padding(start = 44.dp), color = Color(0x22FFFFFF))
                }
            }
        }
    }
}

@Composable
private fun OperatorDialog(excluded: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val ops = remember { DataRepo.sites.groupingBy { it.op }.eachCount().entries.filter { it.key.isNotBlank() }.sortedByDescending { it.value } }
    var hidden by remember { mutableStateOf(excluded) }
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card1,
        title = { Text("Szolgáltatók") },
        confirmButton = { Button(onClick = { onSave(hidden) }) { Text("Kész") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Keresés") }, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { hidden = emptySet() }) { Text("Mind látszik") }
                    TextButton(onClick = { hidden = ops.map { it.key }.toSet() }) { Text("Mind rejtve") }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(ops.filter { q.isBlank() || it.key.contains(q, true) }, key = { it.key }) { (op, n) ->
                        Row(Modifier.fillMaxWidth().clickable { hidden = if (op in hidden) hidden - op else hidden + op },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = op !in hidden, onCheckedChange = { on -> hidden = if (on) hidden - op else hidden + op })
                            Text(op, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$n", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun EndpointRow(dot: Color, text: String, placeholder: Boolean, onClear: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(dot, CircleShape))
        Spacer(Modifier.width(16.dp))
        Text(text, color = if (placeholder) Muted else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (placeholder) FontWeight.Normal else FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp))
        if (onClear != null) IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, "Törlés", tint = Muted)
        } else Spacer(Modifier.size(40.dp))
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
    val near = remember { LocationHelper.last(ctx)?.let { Place("", it.latitude, it.longitude) } }
    // suggestions while typing (debounced)
    LaunchedEffect(query) {
        if (query.trim().length < 3) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        val r = RoutePlanner.suggest(query, near)
        if (r.isNotEmpty()) { results = r; msg = null }
    }
    fun search() {
        if (query.isBlank()) return
        busy = true; msg = null
        scope.launch {
            try {
                results = RoutePlanner.geocode(query, near)
                if (results.isEmpty()) msg = "Nincs találat"
            } catch (e: Exception) { msg = "Keresési hiba: ${e.message}" }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when { which == 100 -> "Indulás"; which == 1 -> "Úti cél"; which == 2 -> "Köztes cél"; else -> "Pont módosítása" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Bezár") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Cím, hely, cégnév, koordináta vagy Google Maps-link") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().clickable {
                onPick(null)
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
            val total = (p.arrivalMillis - p.startMillis) / 1000.0
            val driving = total - p.chargeMinutes * 60 - p.stops.size * 300
            Text("${p.distanceKm.roundToInt()} km · vezetés ${fmtDuration(driving)} (kitérőkkel)", color = Muted, fontSize = 13.sp)
            Text("Teljes menetidő töltéssel: ${fmtDuration(total)} · érkezés ${Pricing.hhmm(p.arrivalMillis)}",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (p.avgKwh100 > 0) Text(String.format(Locale.GERMANY, "Becsült átlagfogyasztás ezen az úton: %.1f kWh/100 km", p.avgKwh100),
                color = Muted, fontSize = 12.sp)
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
    val v = profile.vehicle
    var soh by remember(profile) { mutableStateOf(fmtNum(v.sohPct)) }
    var correction by remember(profile) { mutableStateOf(profile.correctionPct.toString()) }
    var margin by remember(profile) { mutableStateOf(profile.marginPct.toString()) }
    var reserve by remember(profile) { mutableStateOf(profile.reservePct.toString()) }
    var maxSoc by remember(profile) { mutableStateOf(profile.maxChargeSoc.toString()) }
    var routeKw by remember(profile) { mutableStateOf(profile.routeMinKw.toString()) }
    var baseUrl by remember(profile) { mutableStateOf(profile.baseUrl) }
    var adding by remember { mutableStateOf(false) }
    var tariffGroup by remember { mutableStateOf(if (profile.tariffs.any { it.enabled }) "Bekapcsolt" else "Ausztria") }
    var carSearch by remember { mutableStateOf(false) }

    fun d(s: String, def: Double) = s.replace(',', '.').toDoubleOrNull() ?: def
    fun i(s: String, def: Int) = s.trim().replace("+", "").toIntOrNull() ?: def
    fun save() = onProfile(profile.copy(
        vehicles = profile.vehicles.map { if (it.id == v.id) it.copy(sohPct = d(soh, v.sohPct).coerceIn(50.0, 100.0)) else it },
        correctionPct = i(correction, profile.correctionPct).coerceIn(-40, 60),
        marginPct = i(margin, profile.marginPct).coerceIn(0, 30),
        reservePct = i(reserve, profile.reservePct).coerceIn(0, 50),
        maxChargeSoc = i(maxSoc, profile.maxChargeSoc).coerceIn(50, 100),
        routeMinKw = i(routeKw, profile.routeMinKw),
    ))

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Header("Profil", v.name) }

        // ---- car
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AUTÓ", color = Muted, fontSize = 12.sp)
                    profile.vehicles.forEach { car ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable { onProfile(profile.copy(activeVehicleId = car.id)) }) {
                            androidx.compose.material3.RadioButton(selected = car.id == v.id, onClick = { onProfile(profile.copy(activeVehicleId = car.id)) })
                            Column(Modifier.weight(1f)) {
                                Text(car.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(String.format(Locale.GERMANY, "%.1f kWh hasznos · SoH %s%% → %.1f kWh · max %d kW DC",
                                    car.usableKwh, fmtNum(car.sohPct), car.capacityKwh, car.dcMaxKw.roundToInt()), color = Muted, fontSize = 12.sp)
                            }
                            if (profile.vehicles.size > 1) IconButton(onClick = {
                                val rest = profile.vehicles.filter { it.id != car.id }
                                onProfile(profile.copy(vehicles = rest, activeVehicleId = if (car.id == v.id) rest.first().id else profile.activeVehicleId))
                            }) { Icon(Icons.Filled.Delete, "Törlés", tint = Muted) }
                        }
                    }
                    OutlinedButton(onClick = { carSearch = true }, modifier = Modifier.fillMaxWidth()) { Text("Autó keresése és hozzáadása") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Akku állapota, SoH (%)", soh, { soh = it }, Modifier.weight(1f))
                        NumField("Saját korrekció (%)", correction, { correction = it }, Modifier.weight(1f))
                    }
                    Text("SoH: a battery-teszt szerinti állapot (pl. 91,9). Korrekció: ha a valós fogyasztásod több (+) vagy kevesebb (−), mint amit az app számol.",
                        color = Muted, fontSize = 12.sp)
                }
            }
        }

        // ---- driving
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VEZETÉS ÉS TERVEZÉS", color = Muted, fontSize = 12.sp)
                    Text("Tempó autópályán", fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(110, 120, 130, 140).forEach { k ->
                            FilterChip(selected = profile.highwayKmh == k, onClick = { onProfile(profile.copy(highwayKmh = k)) }, label = { Text("$k km/h") })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "Évszak: auto", "summer" to "Nyár", "winter" to "Tél").forEach { (k, l) ->
                            FilterChip(selected = profile.season == k, onClick = { onProfile(profile.copy(season = k)) }, label = { Text(l) })
                        }
                    }
                    val est = { kmh: Int -> app.ladepilot.data.EnergyModel.kwhPerKm(v, profile, kmh.toDouble()) * 100 }
                    Text(String.format(Locale.GERMANY,
                        "Becsült fogyasztás (ráhagyással): autópálya %d km/h → %.1f · országút 90 km/h → %.1f · város → %.1f kWh/100 km",
                        profile.highwayKmh, est(profile.highwayKmh), est(90), est(40)), color = Muted, fontSize = 12.sp)
                    Text("Az útvonalon a szakaszok jellege szerint számol: autópályán a választott tempóval, országúton 90–100, településen a városi értékkel.",
                        color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Tartalék (%)", reserve, { reserve = it }, Modifier.weight(1f))
                        NumField("Ráhagyás (%)", margin, { margin = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Max. töltés (%)", maxSoc, { maxSoc = it }, Modifier.weight(1f))
                        NumField("Útvonalon min. kW", routeKw, { routeKw = it }, Modifier.weight(1f))
                    }
                    Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Mentés") }
                }
            }
        }

        // ---- map style
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TÉRKÉP", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("dark" to "Sötét", "liberty" to "Színes", "light" to "Világos").forEach { (k, l) ->
                            FilterChip(selected = profile.mapStyle == k, onClick = { onProfile(profile.copy(mapStyle = k)) }, label = { Text(l) })
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text("KÁRTYÁIM / TARIFÁIM", color = Muted, fontSize = 12.sp)
                Text("Kapcsold be, amid van – a térkép és az útvonaltervező a legolcsóbbal számol.", color = Muted, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Bekapcsolt", "Ausztria", "Németország", "Magyarország", "Több ország", "Saját").forEach { g ->
                        FilterChip(selected = tariffGroup == g, onClick = { tariffGroup = g }, label = { Text(g) })
                    }
                }
            }
        }
        val shown = profile.tariffs.filter { if (tariffGroup == "Bekapcsolt") it.enabled else Tariffs.group(it) == tariffGroup }
        if (shown.isEmpty()) item {
            Text(if (tariffGroup == "Bekapcsolt") "Még nincs bekapcsolt kártya – válassz országot fent." else "Nincs ilyen tarifa.", color = Muted, fontSize = 13.sp)
        }
        items(shown, key = { it.id }) { t ->
            Card(colors = CardDefaults.cardColors(containerColor = Card1), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.SemiBold)
                        val homeUnit = if (t.country == "HU") "Ft" else "€"
                        Text(if (t.fee > 0) "${fmtNum(t.fee)} $homeUnit / hó" else "nincs havidíj", color = Muted, fontSize = 12.sp)
                        Text(t.effectiveRules.joinToString(" · ") { ruleText(it) }, fontSize = 12.sp)
                        val extra = listOf(t.note, t.source).filter { it.isNotBlank() }.joinToString(" · ")
                        if (extra.isNotBlank()) Text(extra, color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = t.enabled, onCheckedChange = { on ->
                        onProfile(profile.copy(tariffs = profile.tariffs.map { if (it.id == t.id) it.copy(enabled = on) else it }))
                    })
                    if (t.id.startsWith("u-")) {
                        IconButton(onClick = { onProfile(profile.copy(tariffs = profile.tariffs.filter { it.id != t.id })) }) {
                            Icon(Icons.Filled.Delete, "Törlés", tint = Muted)
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Saját tarifa hozzáadása") } }
        item {
            Text("Ha a kártyád nincs a listában: Saját tarifa, pl. ország AT, szöveg „Tesla”, fix ár 0,48 €/kWh. " +
                "Az árak tájékoztató jellegűek – a pontos árat a szolgáltató appja mutatja.", color = Muted, fontSize = 12.sp)
        }
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SPECIÁLIS", color = Muted, fontSize = 12.sp)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true,
                    label = { Text("Adatszerver") }, modifier = Modifier.fillMaxWidth())
                if (baseUrl.trim() != profile.baseUrl) TextButton(onClick = { onProfile(profile.copy(baseUrl = baseUrl.trim().ifBlank { profile.baseUrl })) }) { Text("Adatszerver mentése") }
                DataInfo()
                Text("Adatok: E-Control ladestellen.at, OTPR (ELMOB), Mobilithek (AFIR, CC0), Bundesnetzagentur Ladesäulenregister, Piattaforma Unica Nazionale (IT), Open Charge Map (HR) · Járműadatok: Open EV Data (github.com/KilowattApp/open-ev-data) · Térkép: OpenFreeMap, © OpenStreetMap",
                    color = Color(0xFF5C5C62), fontSize = 11.sp)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (carSearch) CarSearchDialog(onDismiss = { carSearch = false }) { car ->
        val list = if (profile.vehicles.any { it.id == car.id }) profile.vehicles else profile.vehicles + car
        onProfile(profile.copy(vehicles = list, activeVehicleId = car.id)); carSearch = false
    }
    if (adding) AddTariffDialog(onDismiss = { adding = false }) { t ->
        onProfile(profile.copy(tariffs = profile.tariffs + t)); adding = false
    }
}

private fun ruleText(r: app.ladepilot.data.TariffRule): String {
    val unit = if (r.countries == "HU") "Ft" else "€"
    fun v(x: Double?) = when {
        x == null -> "–"
        r.mode == "percent" -> "−${fmtNum(x)}%"
        r.mode == "discount" -> "−${fmtNum(x)} $unit"
        else -> "${fmtNum(x)} $unit"
    }
    val where = (r.match.ifBlank { "többi" }) + if (r.countries != "*") " (${r.countries})" else ""
    val price = when {
        r.ac == r.dc && (r.hpc == null || r.hpc == r.dc) -> v(r.dc)
        r.hpc != null && r.hpc != r.dc -> "AC ${v(r.ac)} / DC ${v(r.dc)} / HPC ${v(r.hpc)}"
        else -> "AC ${v(r.ac)} / DC ${v(r.dc)}"
    }
    return "$where: $price"
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
private fun CarSearchDialog(onDismiss: () -> Unit, onPick: (app.ladepilot.data.Vehicle) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val results = remember(q) { if (q.length < 2) emptyList() else app.ladepilot.data.CarDb.search(ctx, q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card1,
        title = { Text("Autó keresése") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Bezár") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("pl. ioniq 5 awd, model y long") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(results, key = { it.id }) { e ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(e.toVehicle()) }.padding(vertical = 8.dp)) {
                            Text(e.title, fontWeight = FontWeight.SemiBold)
                            Text(String.format(Locale.GERMANY, "%.1f kWh hasznos · átlag %.1f kWh/100 km · max %d kW DC",
                                e.kwh, e.avg, e.dc.roundToInt()), color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                Text("Forrás: Open EV Data", color = Color(0xFF5C5C62), fontSize = 11.sp)
            }
        },
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
