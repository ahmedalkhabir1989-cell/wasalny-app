package com.wasalny.sidisalem

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.math.*

val Context.dataStore by preferencesDataStore(name = "wasalny_v4")

object Config {
    const val LAT = 31.27133
    const val LON = 30.786165
    const val RADIUS_KM = 5.0
    const val PHONE = "01069631950"
    const val BASE = 10.0
    const val PER_KM = 5.0
    // من BuildConfig (build.gradle.kts) — غيّره هناك أو بـ -PAPI_BASE=https://...
    val API_BASE: String = try {
        BuildConfig.API_BASE
    } catch (_: Throwable) {
        "https://wasalny-sidi-salem.onrender.com"
    }
    val CENTER = LatLng(LAT, LON)
}

data class FavPlace(val name: String, val address: String, val lat: Double, val lon: Double)

data class RideItem(
    val id: Int,
    val from: String,
    val to: String,
    val price: Int,
    val status: String,
    val securityCode: String,
    val driverName: String?,
    val distanceKm: Double,
    val createdAt: String?
)

fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun calcPrice(km: Double, type: String = "now"): Int {
    var p = Config.BASE + km * Config.PER_KM
    if (type == "school") {
        p *= 0.8
        p = max(p, 15.0)
    }
    p = round(p / 5) * 5
    return max(p.toInt(), 10)
}

fun inside(lat: Double, lon: Double) =
    distKm(lat, lon, Config.LAT, Config.LON) <= Config.RADIUS_KM

suspend fun geocode(context: Context, ll: LatLng): String = withContext(Dispatchers.IO) {
    try {
        val g = Geocoder(context, Locale("ar"))
        @Suppress("DEPRECATION")
        val l = g.getFromLocation(ll.latitude, ll.longitude, 1)
        if (!l.isNullOrEmpty()) l[0].getAddressLine(0)
            ?: "%.4f, %.4f".format(ll.latitude, ll.longitude)
        else "%.4f, %.4f".format(ll.latitude, ll.longitude)
    } catch (_: Exception) {
        "%.4f, %.4f".format(ll.latitude, ll.longitude)
    }
}

suspend fun getFavs(ctx: Context): List<FavPlace> {
    val s = ctx.dataStore.data.first()[stringPreferencesKey("favs")] ?: "[]"
    val arr = JSONArray(s)
    return (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        FavPlace(o.getString("name"), o.getString("address"), o.getDouble("lat"), o.getDouble("lon"))
    }
}

suspend fun saveFav(ctx: Context, p: FavPlace) {
    val cur = ctx.dataStore.data.first()[stringPreferencesKey("favs")] ?: "[]"
    val arr = JSONArray(cur)
    val na = JSONArray()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        if (o.getString("name") != p.name) na.put(o)
    }
    val o = JSONObject()
    o.put("name", p.name)
    o.put("address", p.address)
    o.put("lat", p.lat)
    o.put("lon", p.lon)
    na.put(o)
    ctx.dataStore.edit { it[stringPreferencesKey("favs")] = na.toString() }
}

suspend fun deleteFav(ctx: Context, name: String) {
    val cur = ctx.dataStore.data.first()[stringPreferencesKey("favs")] ?: "[]"
    val arr = JSONArray(cur)
    val na = JSONArray()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        if (o.getString("name") != name) na.put(o)
    }
    ctx.dataStore.edit { it[stringPreferencesKey("favs")] = na.toString() }
}

suspend fun getOrCreateUserId(ctx: Context): String {
    val key = stringPreferencesKey("user_id")
    val existing = ctx.dataStore.data.first()[key]
    if (existing != null) return existing
    val id = UUID.randomUUID().toString().take(12)
    ctx.dataStore.edit { it[key] = id }
    return id
}

suspend fun getUserName(ctx: Context): String {
    return ctx.dataStore.data.first()[stringPreferencesKey("user_name")] ?: "مستخدم"
}

suspend fun getUserPhone(ctx: Context): String {
    return ctx.dataStore.data.first()[stringPreferencesKey("user_phone")] ?: ""
}

// ========== API Helpers (hardened for free-tier cold start) ==========
private const val CONNECT_TIMEOUT_MS = 45000  // Render free cold start ~30-50s
private const val READ_TIMEOUT_MS = 45000
private const val MAX_RETRIES = 2

fun isOnline(ctx: Context): Boolean {
    return try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        true // assume online if check fails
    }
}

fun friendlyNetworkError(e: Exception?): String {
    val msg = e?.message?.lowercase() ?: ""
    return when {
        msg.contains("timeout") || msg.contains("timed out") ->
            "السيرفر بيستيقظ (مجاني على Render) — استنى 30 ثانية وحاول تاني"
        msg.contains("unable to resolve") || msg.contains("unknown host") ->
            "مفيش نت أو لينك السيرفر غلط — تأكد من API_BASE"
        msg.contains("failed to connect") || msg.contains("connection") ->
            "مفيش اتصال بالسيرفر — تأكد من النت أو إن السيرفر شغال"
        else ->
            "فشل الاتصال بالسيرفر — جرب تاني أو استخدم SMS بدون نت"
    }
}

suspend fun apiRequest(
    method: String,
    path: String,
    body: JSONObject? = null,
    retries: Int = MAX_RETRIES
): Pair<JSONObject?, String?> = withContext(Dispatchers.IO) {
    var lastError: Exception? = null
    repeat(retries) { attempt ->
        try {
            val url = URL("${Config.API_BASE}$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Connection", "close")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                if (body != null) {
                    doOutput = true
                }
            }
            if (body != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else ""
            conn.disconnect()
            if (text.isBlank()) {
                return@withContext null to "رد فاضي من السيرفر"
            }
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                return@withContext null to "رد غير مفهوم من السيرفر"
            }
            if (code in 200..299) {
                return@withContext json to null
            }
            val detail = json.optString("detail", "خطأ $code")
            return@withContext json to detail
        } catch (e: Exception) {
            lastError = e
            e.printStackTrace()
            if (attempt < retries - 1) {
                try {
                    Thread.sleep(2000L * (attempt + 1))
                } catch (_: InterruptedException) {
                }
            }
        }
    }
    null to friendlyNetworkError(lastError)
}

suspend fun apiPost(path: String, body: JSONObject): JSONObject? {
    val (json, err) = apiRequest("POST", path, body)
    if (err != null && json == null) {
        // wrap error so callers can show message
        return JSONObject().put("_error", err).put("success", false).put("detail", err)
    }
    return json
}

suspend fun apiGet(path: String): JSONObject? {
    val (json, err) = apiRequest("GET", path, null)
    if (err != null && json == null) {
        return JSONObject().put("_error", err).put("success", false).put("detail", err)
    }
    return json
}



suspend fun createRideOnServer(
    customerId: String,
    name: String,
    phone: String,
    fromAddr: String,
    toAddr: String,
    from: LatLng,
    to: LatLng,
    km: Double,
    price: Int,
    rideType: String,
    femaleMode: Boolean,
    withLuggage: Boolean
): JSONObject? {
    val body = JSONObject().apply {
        put("customer_id", customerId)
        put("customer_name", name)
        put("customer_phone", phone.ifEmpty { "01000000000" })
        put("from_address", fromAddr)
        put("to_address", toAddr)
        put("from_lat", from.latitude)
        put("from_lon", from.longitude)
        put("to_lat", to.latitude)
        put("to_lon", to.longitude)
        put("distance_km", km)
        put("price", price)
        put("ride_type", rideType)
        put("female_mode", femaleMode)
        put("with_luggage", withLuggage)
    }
    return apiPost("/api/rides/create", body)
}

suspend fun fetchCustomerRides(customerId: String): List<RideItem> {
    val res = apiGet("/api/customer/$customerId/rides?limit=15") ?: return emptyList()
    if (res.has("_error")) return emptyList()
    val arr = res.optJSONArray("rides") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        RideItem(
            id = o.optInt("id"),
            from = o.optString("from_address"),
            to = o.optString("to_address"),
            price = o.optInt("price"),
            status = o.optString("status"),
            securityCode = o.optString("security_code"),
            driverName = o.optString("driver_name").takeIf { it.isNotBlank() && it != "null" },
            distanceKm = o.optDouble("distance_km"),
            createdAt = o.optString("created_at").takeIf { it.isNotBlank() }
        )
    }
}

suspend fun fetchNearbyRides(lat: Double, lon: Double, femaleOnly: Boolean = false): List<RideItem> {
    val path = "/api/rides/nearby?lat=$lat&lon=$lon&radius_km=5&female_only=$femaleOnly"
    val res = apiGet(path) ?: return emptyList()
    if (res.has("_error")) return emptyList()
    val arr = res.optJSONArray("rides") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        RideItem(
            id = o.optInt("id"),
            from = o.optString("from_address"),
            to = o.optString("to_address"),
            price = o.optInt("price"),
            status = o.optString("status"),
            securityCode = o.optString("security_code"),
            driverName = null,
            distanceKm = o.optDouble("distance_to_driver_km", o.optDouble("distance_km")),
            createdAt = o.optString("created_at").takeIf { it.isNotBlank() }
        )
    }
}

suspend fun acceptRideOnServer(rideId: Int, driverId: String, driverName: String): JSONObject? {
    val body = JSONObject().apply {
        put("ride_id", rideId)
        put("driver_id", driverId)
        put("driver_name", driverName)
    }
    return apiPost("/api/rides/accept", body)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppV4() }
    }
}

@Composable
fun AppV4() {
    val navController = rememberNavController()
    val context = LocalContext.current
    var role by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        role = prefs[stringPreferencesKey("role")]
        loaded = true
    }
    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0D7C3E))) {
        if (role == null) {
            WelcomeV4(onSelect = { r -> role = r })
        } else {
            Scaffold(bottomBar = { BottomBarV4(navController, role!!) }) { padding ->
                NavHost(
                    navController,
                    startDestination = "home",
                    modifier = Modifier.padding(padding)
                ) {
                    composable("home") { HomeV4(navController, role!!) }
                    composable("map") { MapV4(role!!) }
                    composable("rides") { RidesV4(navController, role!!) }
                    composable("wallet") { WalletV4() }
                    composable("account") { AccountV4(navController) }
                    composable("manage_favs") { ManageFavsV4() }
                }
            }
        }
    }
}

@Composable
fun WelcomeV4(onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🕌 وصلني", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D7C3E))
        Text("سيدي سالم - شبكة أمان", fontSize = 16.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Text(
            "التوكتوك اللي جارك ضامنه - فكرة متعملتش",
            fontSize = 11.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                scope.launch {
                    ctx.dataStore.edit { it[stringPreferencesKey("role")] = "customer" }
                    onSelect("customer")
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("أنا راكب") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    ctx.dataStore.edit { it[stringPreferencesKey("role")] = "driver" }
                    onSelect("driver")
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("أنا سائق") }
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
            Column(Modifier.padding(12.dp)) {
                Text("✨ مميزات جديدة تنافس أوبر:", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(
                    "• وضع الستات الآمن\n• باقة العيلة\n• توكتوك بيشيل حمولة\n• بدون نت SMS\n• ثواب المسجد",
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun BottomBarV4(nav: NavController, role: String) {
    val items = if (role == "driver") {
        listOf(
            Triple("home", "الرئيسية", Icons.Default.Home),
            Triple("map", "الخريطة", Icons.Default.Map),
            Triple("rides", "طلبات", Icons.Default.List),
            Triple("wallet", "محفظتي", Icons.Default.AccountBalanceWallet),
            Triple("account", "حسابي", Icons.Default.Person)
        )
    } else {
        listOf(
            Triple("home", "روحني", Icons.Default.Home),
            Triple("map", "الخريطة", Icons.Default.Map),
            Triple("rides", "رحلاتي", Icons.Default.History),
            Triple("wallet", "محفظتي", Icons.Default.AccountBalanceWallet),
            Triple("account", "أماني", Icons.Default.Security)
        )
    }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    NavigationBar {
        items.forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = current == route,
                onClick = { nav.navigate(route) { launchSingleTop = true } },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label, fontSize = 10.sp) }
            )
        }
    }
}

@Composable
fun HomeV4(nav: NavController, role: String) {
    val ctx = LocalContext.current
    var favs by remember { mutableStateOf<List<FavPlace>>(emptyList()) }
    LaunchedEffect(Unit) { favs = getFavs(ctx) }

    if (role == "driver") {
        DriverHomeV4(nav)
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("أهلاً 👋", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "📍 سيدي سالم - 5 كم - شبكة أمان - خرائط جوجل 100%",
                fontSize = 11.sp,
                color = Color(0xFF0D7C3E)
            )
            Spacer(Modifier.height(8.dp))
            Text("🏠 دوس تروح على طول - بدون كتابة", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        if (favs.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("لسه محفظتش أماكن", fontWeight = FontWeight.Bold)
                        Text("روح للخريطة واحفظ البيت أو الشغل", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { nav.navigate("map") }) { Text("افتح الخريطة") }
                    }
                }
            }
        } else {
            items(favs) { fav ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { nav.navigate("map") },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Place, null, tint = Color(0xFF0D7C3E))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fav.name, fontWeight = FontWeight.Bold)
                            Text(fav.address, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                        }
                        Text("روحني →", color = Color(0xFF0D7C3E), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { nav.navigate("manage_favs") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("إدارة الأماكن المحفوظة") }
        }
    }
}

@Composable
fun DriverHomeV4(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf(true) }
    var statusMsg by remember { mutableStateOf("جاهز لاستقبال الطلبات") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("🚖 وضع السائق", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("سيدي سالم - شبكة أمان", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (available) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (available) "🟢 متاح" else "🔴 مش متاح", fontWeight = FontWeight.Bold)
                    Switch(checked = available, onCheckedChange = { available = it })
                }
                Text(statusMsg, fontSize = 12.sp, color = Color.Gray)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { nav.navigate("rides") },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("عرض الطلبات القريبة") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { nav.navigate("map") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("فتح الخريطة") }
    }
}

@Composable
fun MapV4(role: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fused = remember { LocationServices.getFusedLocationProviderClient(ctx) }
    var pickup by remember { mutableStateOf<LatLng?>(null) }
    var dropoff by remember { mutableStateOf<LatLng?>(null) }
    var pickupAddr by remember { mutableStateOf("") }
    var dropoffAddr by remember { mutableStateOf("") }
    var selectingPickup by remember { mutableStateOf(true) }
    var rideType by remember { mutableStateOf("now") }
    var femaleMode by remember { mutableStateOf(false) }
    var withLuggage by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf((1000..9999).random().toString()) }
    var showSave by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    val cam = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(Config.CENTER, 14.5f)
    }
    val permLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                scope.launch {
                    try {
                        val loc = fused.lastLocation.await()
                        loc?.let {
                            val ll = LatLng(it.latitude, it.longitude)
                            pickup = ll
                            pickupAddr = geocode(ctx, ll)
                            cam.position = CameraPosition.fromLatLngZoom(ll, 16f)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
    val km =
        if (pickup != null && dropoff != null)
            distKm(
                pickup!!.latitude, pickup!!.longitude,
                dropoff!!.latitude, dropoff!!.longitude
            )
        else 0.0
    val price = if (km > 0) calcPrice(km, rideType) else 0
    val isInside = pickup?.let { inside(it.latitude, it.longitude) } ?: true

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cam,
            properties = MapProperties(isMyLocationEnabled = true),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = true),
            onMapClick = { ll ->
                scope.launch {
                    if (selectingPickup) {
                        pickup = ll
                        pickupAddr = geocode(ctx, ll)
                    } else {
                        dropoff = ll
                        dropoffAddr = geocode(ctx, ll)
                    }
                }
            }
        ) {
            pickup?.let {
                Marker(
                    state = MarkerState(it),
                    title = "من هنا",
                    snippet = pickupAddr
                )
            }
            dropoff?.let {
                Marker(
                    state = MarkerState(it),
                    title = "إلى هنا",
                    snippet = dropoffAddr
                )
            }
            if (pickup != null && dropoff != null) {
                Polyline(
                    points = listOf(pickup!!, dropoff!!),
                    color = Color(0xFF0D7C3E),
                    width = 8f
                )
            }
            Circle(
                center = Config.CENTER,
                radius = Config.RADIUS_KM * 1000,
                fillColor = Color(0x220D7C3E),
                strokeColor = Color(0xFF0D7C3E),
                strokeWidth = 2f
            )
        }

        // Top controls
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .align(Alignment.TopCenter)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectingPickup,
                            onClick = { selectingPickup = true },
                            label = { Text("📍 من", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = !selectingPickup,
                            onClick = { selectingPickup = false },
                            label = { Text("🏁 إلى", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = femaleMode,
                            onClick = { femaleMode = !femaleMode },
                            label = { Text("👩 وضع الستات", fontSize = 10.sp) }
                        )
                    }
                    if (pickupAddr.isNotEmpty())
                        Text("من: $pickupAddr", fontSize = 10.sp, maxLines = 1)
                    if (dropoffAddr.isNotEmpty())
                        Text("إلى: $dropoffAddr", fontSize = 10.sp, maxLines = 1)
                    if (!isInside)
                        Text(
                            "⚠️ خارج نطاق 5 كم",
                            color = Color.Red,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = rideType == "now",
                            onClick = { rideType = "now" },
                            label = { Text("حالاً", fontSize = 10.sp) }
                        )
                        FilterChip(
                            selected = rideType == "scheduled",
                            onClick = { rideType = "scheduled" },
                            label = { Text("بموعد", fontSize = 10.sp) }
                        )
                        FilterChip(
                            selected = rideType == "school",
                            onClick = { rideType = "school" },
                            label = { Text("مدارس -20%", fontSize = 10.sp) }
                        )
                        FilterChip(
                            selected = withLuggage,
                            onClick = { withLuggage = !withLuggage },
                            label = { Text("📦 حمولة", fontSize = 10.sp) }
                        )
                    }
                }
            }
        }

        // Bottom card
        Card(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                if (pickup != null && dropoff != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("%.2f كم".format(km), fontWeight = FontWeight.Bold)
                        Text(
                            "💰 $price ج",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color(0xFF0D7C3E)
                        )
                        Text("🔐 $code", fontSize = 12.sp)
                    }
                    if (femaleMode)
                        Text(
                            "👩 وضع الستات: سواق موثوق تقييمه من الستات فوق 4.8",
                            fontSize = 10.sp,
                            color = Color(0xFF880E4F)
                        )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showConfirm = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = isInside && !isLoading && role == "customer"
                    ) {
                        if (isLoading) CircularProgressIndicator(
                            Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        else Text("✅ اطلب التوكتوك حالاً")
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showSave = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("💾 احفظ كـ بيت", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = {
                                val sms = Intent(
                                    Intent.ACTION_SENDTO,
                                    Uri.parse("smsto:${Config.PHONE}")
                                )
                                sms.putExtra(
                                    "sms_body",
                                    "طلب توكتوك من $pickupAddr إلى $dropoffAddr - السعر $price ج"
                                )
                                ctx.startActivity(sms)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("📱 SMS بدون نت", fontSize = 11.sp) }
                    }
                } else {
                    Text(
                        "👆 اضغط على الخريطة تحدد من فين لفين\n💾 احفظه كـ بيت عشان تطلبه بضغطة واحدة بعد كده\n🎤 قريباً: اطلب بصوتك - للستات وكبار السن",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isLoading) showConfirm = false },
            title = { Text("تأكيد - شبكة أمان") },
            text = {
                Text(
                    "من: $pickupAddr\nإلى: $dropoffAddr\n📏 %.2f كم - 💰 $price ج\n✅ السعر للرحلة كاملة\n${if (femaleMode) "👩 وضع الستات مفعل\n" else ""}جاري إرسال الطلب...\n(لو السيرفر نايم أول مرة ممكن تاخد دقيقة)".format(
                        km
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pickup == null || dropoff == null) return@Button
                        isLoading = true
                        scope.launch {
                            val uid = getOrCreateUserId(ctx)
                            val name = getUserName(ctx)
                            val phone = getUserPhone(ctx)
                            val res = createRideOnServer(
                                uid, name, phone,
                                pickupAddr, dropoffAddr,
                                pickup!!, dropoff!!,
                                km, price, rideType, femaleMode, withLuggage
                            )
                            isLoading = false
                            showConfirm = false
                            if (res != null && res.optBoolean("success", false)) {
                                code = res.optString("security_code", code)
                                resultMsg =
                                    "✅ تم إنشاء الرحلة #${res.optInt("ride_id")}\nكود الأمان: $code\nجاري البحث عن سواقين..."
                            } else {
                                val detail =
                                    res?.optString("detail") ?: "فشل الاتصال بالسيرفر - جرب تاني أو استخدم SMS"
                                resultMsg = "❌ $detail"
                            }
                        }
                    },
                    enabled = !isLoading
                ) {
                    if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("تأكيد وإرسال")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }, enabled = !isLoading) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (resultMsg != null) {
        AlertDialog(
            onDismissRequest = { resultMsg = null },
            title = { Text("نتيجة الطلب") },
            text = { Text(resultMsg!!) },
            confirmButton = {
                Button(onClick = { resultMsg = null }) { Text("حسناً") }
            }
        )
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("احفظ مكانك") },
            text = {
                Column {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("البيت / الشغل / مدرسة العيال") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "محفوظ على جهازك ومش بيتمسح إلا بمسح التطبيق",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (saveName.isNotEmpty() && dropoff != null) {
                        scope.launch {
                            saveFav(
                                ctx,
                                FavPlace(
                                    saveName,
                                    dropoffAddr,
                                    dropoff!!.latitude,
                                    dropoff!!.longitude
                                )
                            )
                            showSave = false
                            saveName = ""
                        }
                    }
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
fun RidesV4(nav: NavController, role: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rides by remember { mutableStateOf<List<RideItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var acceptMsg by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            try {
                if (role == "driver") {
                    // استخدام مركز سيدي سالم كافتراضي لو مفيش موقع
                    rides = fetchNearbyRides(Config.LAT, Config.LON)
                } else {
                    val uid = getOrCreateUserId(ctx)
                    rides = fetchCustomerRides(uid)
                }
            } catch (e: Exception) {
                error = e.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (role == "driver") "📜 الطلبات القريبة" else "📜 رحلاتي",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "تحديث")
            }
        }
        Text(
            if (role == "driver") "اضغط قبول عشان تاخد الرحلة" else "آخر الرحلات من السيرفر",
            fontSize = 11.sp,
            color = Color.Gray
        )
        Spacer(Modifier.height(12.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Text("❌ $error", color = Color.Red)
            Button(onClick = { refresh() }) { Text("إعادة المحاولة") }
        } else if (rides.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("مفيش رحلات حالياً", fontWeight = FontWeight.Bold)
                    Text(
                        if (role == "driver") "استنى طلبات جديدة أو حدّث"
                        else "اطلب رحلة من الخريطة",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    if (role != "driver") {
                        Button(onClick = { nav.navigate("map") }) { Text("اطلب دلوقتي") }
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rides) { ride ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${ride.from.take(25)} → ${ride.to.take(25)}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${ride.price}ج",
                                    color = Color(0xFF0D7C3E),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                "الحالة: ${ride.status} | %.1f كم".format(ride.distanceKm) +
                                        if (ride.securityCode.isNotBlank()) " | كود: ${ride.securityCode}" else "",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                            if (ride.driverName != null) {
                                Text("السائق: ${ride.driverName}", fontSize = 11.sp)
                            }
                            if (role == "driver" && ride.status == "pending") {
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val uid = getOrCreateUserId(ctx)
                                            val name = getUserName(ctx)
                                            val res = acceptRideOnServer(ride.id, uid, name)
                                            if (res != null && res.optBoolean("success", false)) {
                                                acceptMsg =
                                                    "✅ تم قبول الرحلة\nكود الأمان: ${res.optString("security_code")}\nالراكب: ${res.optString("customer_phone")}"
                                                refresh()
                                            } else {
                                                acceptMsg =
                                                    "❌ ${res?.optString("detail") ?: "فشل القبول - ممكن اتاخدت"}"
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("قبول الرحلة") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (acceptMsg != null) {
        AlertDialog(
            onDismissRequest = { acceptMsg = null },
            title = { Text("نتيجة") },
            text = { Text(acceptMsg!!) },
            confirmButton = {
                Button(onClick = { acceptMsg = null }) { Text("حسناً") }
            }
        )
    }
}

@Composable
fun WalletV4() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("💳 محفظتي ونقاطي", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("الرصيد", fontSize = 12.sp, color = Color.Gray)
                Text("0 ج", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D7C3E))
                Text("نقاط الولاء: 0", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("قريباً: شحن محفظة + خصومات + باقة العيلة", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
fun AccountV4(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        name = getUserName(ctx)
        phone = getUserPhone(ctx)
        if (name == "مستخدم") name = ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("🛡️ أماني وحسابي", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("الاسم") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("رقم الموبايل") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                scope.launch {
                    ctx.dataStore.edit {
                        it[stringPreferencesKey("user_name")] = name.ifBlank { "مستخدم" }
                        it[stringPreferencesKey("user_phone")] = phone
                    }
                    saved = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("حفظ البيانات") }
        if (saved) {
            Text("✅ تم الحفظ", color = Color(0xFF0D7C3E), fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
            Column(Modifier.padding(12.dp)) {
                Text("أرقام الطوارئ", fontWeight = FontWeight.Bold)
                Text("الدعم: ${Config.PHONE}", fontSize = 13.sp)
                Text("الطوارئ: 122", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    ctx.dataStore.edit {
                        it.remove(stringPreferencesKey("role"))
                    }
                    // Force restart flow - user needs to reopen app or we navigate
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("تغيير الدور (راكب / سائق)") }
        Text(
            "بعد الضغط اقفل التطبيق وافتحه تاني عشان تختار الدور من الأول",
            fontSize = 10.sp,
            color = Color.Gray
        )
    }
}

@Composable
fun ManageFavsV4() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var favs by remember { mutableStateOf<List<FavPlace>>(emptyList()) }
    LaunchedEffect(Unit) { favs = getFavs(ctx) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("الأماكن المحفوظة", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (favs.isEmpty()) {
            Text("مفيش أماكن محفوظة لسه", color = Color.Gray)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(favs) { fav ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(fav.name, fontWeight = FontWeight.Bold)
                                Text(fav.address, fontSize = 11.sp, color = Color.Gray, maxLines = 2)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    deleteFav(ctx, fav.name)
                                    favs = getFavs(ctx)
                                }
                            }) {
                                Icon(Icons.Default.Delete, null, tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}
