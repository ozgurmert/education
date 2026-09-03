package com.viavis.live

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private const val PREFS = "viavis_live"
private const val KEY_API_TOKEN = "api_token"

class MainActivity : ComponentActivity() {

    private var pendingJourneyId by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingJourneyId = extractJourneyId(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme {
                ViavisApp(
                    initialToken = getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(KEY_API_TOKEN, "")
                        .orEmpty(),
                    saveToken = { token ->
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_API_TOKEN, token)
                            .apply()
                    },
                    externalJourneyId = pendingJourneyId,
                    consumeExternalJourney = { pendingJourneyId = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractJourneyId(intent)?.let { pendingJourneyId = it }
    }

    private fun extractJourneyId(intent: Intent?): String? {
        val value = intent?.getStringExtra("journey_id")?.trim().orEmpty()
        return value.takeIf { it.matches(Regex("^[a-fA-F0-9]{64}$")) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private enum class AppTab(val title: String) {
    OVERVIEW("Özet"),
    LIVE("Canlı"),
    CARTS("Sepetler"),
    PRODUCTS("Ürünler"),
    VISITORS("Ziyaretçiler")
}

@Composable
private fun ViavisApp(
    initialToken: String,
    saveToken: (String) -> Unit,
    externalJourneyId: String?,
    consumeExternalJourney: () -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }

    if (token.isBlank()) {
        ApiSetupScreen { newToken ->
            saveToken(newToken)
            token = newToken
        }
    } else {
        MainShell(
            token = token,
            externalJourneyId = externalJourneyId,
            consumeExternalJourney = consumeExternalJourney,
            resetToken = {
                saveToken("")
                token = ""
            }
        )
    }
}

@Composable
private fun ApiSetupScreen(onSave: (String) -> Unit) {
    var token by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Viavis Live") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Viavis Mobile API bağlantısı",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text("WooCommerce → Viavis Mobile API ekranında oluşturduğun API anahtarını gir. Anahtar APK içine gömülmez; bu telefonda saklanır.")
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text("API anahtarı") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { if (token.length >= 32) onSave(token) },
                enabled = token.length >= 32,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Bağlan") }
            Spacer(Modifier.height(10.dp))
            Text(
                VIAVIS_API_BASE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    token: String,
    externalJourneyId: String?,
    consumeExternalJourney: () -> Unit,
    resetToken: () -> Unit
) {
    var tab by remember { mutableStateOf(AppTab.OVERVIEW) }
    var days by remember { mutableIntStateOf(7) }
    var selectedJourneyId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(externalJourneyId) {
        if (!externalJourneyId.isNullOrBlank()) {
            selectedJourneyId = externalJourneyId
            consumeExternalJourney()
        }
    }

    val showingTimeline = !selectedJourneyId.isNullOrBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Viavis Live", fontWeight = FontWeight.Bold)
                        Text(
                            if (showingTimeline) "Ziyaretçi Timeline" else tab.title,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    if (showingTimeline) {
                        IconButton(onClick = { selectedJourneyId = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                        }
                    }
                },
                actions = {
                    if (!showingTimeline) {
                        IconButton(onClick = resetToken) {
                            Icon(Icons.Default.Key, contentDescription = "API anahtarını değiştir")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!showingTimeline) {
                NavigationBar {
                    AppTab.entries.forEach { item ->
                        val icon = when (item) {
                            AppTab.OVERVIEW -> Icons.Default.Dashboard
                            AppTab.LIVE -> Icons.Default.Bolt
                            AppTab.CARTS -> Icons.Default.ShoppingCart
                            AppTab.PRODUCTS -> Icons.Default.Inventory2
                            AppTab.VISITORS -> Icons.Default.People
                        }
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(icon, contentDescription = item.title) },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (showingTimeline) {
                JourneyDetailScreen(
                    token = token,
                    journeyId = selectedJourneyId!!,
                    onBack = { selectedJourneyId = null }
                )
            } else {
                if (tab != AppTab.LIVE) {
                    DaySelector(days = days, onDays = { days = it })
                }
                when (tab) {
                    AppTab.OVERVIEW -> OverviewScreen(token, days)
                    AppTab.LIVE -> LiveScreen(token, onJourney = { selectedJourneyId = it })
                    AppTab.CARTS -> AbandonedScreen(token, days, onJourney = { selectedJourneyId = it })
                    AppTab.PRODUCTS -> ProductsScreen(token, days)
                    AppTab.VISITORS -> VisitorsScreen(token, days, onJourney = { selectedJourneyId = it })
                }
            }
        }
    }
}

@Composable
private fun DaySelector(days: Int, onDays: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(1, 7, 30, 60).forEach { value ->
            FilterChip(
                selected = days == value,
                onClick = { onDays(value) },
                label = { Text(if (value == 1) "Bugün" else "$value gün") }
            )
        }
    }
}

@Composable
private fun OverviewScreen(token: String, days: Int) {
    val api = remember(token) { ApiClient(token) }
    var data by remember { mutableStateOf<DashboardData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { api.dashboard(days) }
                .onSuccess { data = it }
                .onFailure { error = it.message ?: "Bağlantı hatası" }
            loading = false
        }
    }

    LaunchedEffect(days, token) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Performans", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Yenile") }
            }
        }
        if (loading && data == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { ErrorCard(message) } }
        data?.let { d ->
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Görüntüleyen", d.viewers.toString(), Modifier.weight(1f))
                    MetricCard("Sepete ekleyen", d.cartUsers.toString(), Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Checkout", d.checkoutUsers.toString(), Modifier.weight(1f))
                    MetricCard("Sipariş", d.orders.toString(), Modifier.weight(1f))
                }
            }
            item { MetricCard("Satış", money(d.revenue), Modifier.fillMaxWidth()) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("View → Cart", "%${d.viewToCart}", Modifier.weight(1f))
                    MetricCard("Cart → Order", "%${d.cartToOrder}", Modifier.weight(1f))
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Terk edilmiş sepetler", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("${d.abandoned} sepet · ${money(d.abandonedValue)}")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveScreen(token: String, onJourney: (String) -> Unit) {
    val api = remember(token) { ApiClient(token) }
    var events by remember { mutableStateOf<List<EventItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(token) {
        while (true) {
            runCatching { api.events(limit = 60) }
                .onSuccess { events = it.asReversed(); error = null }
                .onFailure { error = it.message ?: "Canlı akış alınamadı" }
            delay(15_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Canlı hareketler", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("15 saniyede bir yenilenir · Bir harekete dokunarak Timeline'ı açabilirsin.", style = MaterialTheme.typography.bodySmall)
        }
        error?.let { item { ErrorCard(it) } }
        items(events, key = { it.id }) { event ->
            EventCard(event, onClick = {
                if (event.journeyId.isNotBlank()) onJourney(event.journeyId)
            })
        }
    }
}

@Composable
private fun AbandonedScreen(token: String, days: Int, onJourney: (String) -> Unit) {
    val api = remember(token) { ApiClient(token) }
    var rows by remember { mutableStateOf<List<AbandonedItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(days, token) {
        runCatching { api.abandoned(days) }
            .onSuccess { rows = it; error = null }
            .onFailure { error = it.message ?: "Sepetler alınamadı" }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Terk edilen sepetler", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        error?.let { item { ErrorCard(it) } }
        if (rows.isEmpty() && error == null) item { Text("Bu dönem için terk edilmiş sepet görünmüyor.") }
        items(rows, key = { it.journeyId }) { item ->
            Card(
                onClick = { onJourney(item.journeyId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Misafir #${item.visitor}", fontWeight = FontWeight.Bold)
                        Text(money(item.cartTotal), fontWeight = FontWeight.Bold)
                    }
                    Text("${item.cartItems} ürün · ${item.lastSeen}", style = MaterialTheme.typography.bodySmall)
                    if (item.checkoutStarted) {
                        Spacer(Modifier.height(6.dp))
                        AssistChip(onClick = { onJourney(item.journeyId) }, label = { Text("Checkout'a ulaştı") })
                    }
                    if (item.cartNames.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        item.cartNames.take(5).forEach { Text("• $it") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Timeline'ı aç →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ProductsScreen(token: String, days: Int) {
    val api = remember(token) { ApiClient(token) }
    var rows by remember { mutableStateOf<List<ProductItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(days, token) {
        runCatching { api.products(days) }
            .onSuccess { rows = it; error = null }
            .onFailure { error = it.message ?: "Ürün verileri alınamadı" }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Ürün performansı", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        error?.let { item { ErrorCard(it) } }
        items(rows, key = { it.productId }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(p.name, fontWeight = FontWeight.Bold)
                    Text("#${p.productId}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Tekil ${p.uniqueViews} · Sepet ${p.cartUsers} · Checkout ${p.checkoutUsers} · Alıcı ${p.buyers}")
                    Text("View→Cart %${p.viewToCart} · Cart→Order %${p.cartToOrder}")
                    if (p.revenue > 0) Text("Satış: ${money(p.revenue)}", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun VisitorsScreen(token: String, days: Int, onJourney: (String) -> Unit) {
    val api = remember(token) { ApiClient(token) }
    var rows by remember { mutableStateOf<List<JourneyItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(days, token) {
        runCatching { api.journeys(days) }
            .onSuccess { rows = it; error = null }
            .onFailure { error = it.message ?: "Ziyaretçiler alınamadı" }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Ziyaretçiler", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        error?.let { item { ErrorCard(it) } }
        items(rows, key = { it.journeyId }) { j ->
            Card(
                onClick = { onJourney(j.journeyId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Misafir #${j.visitor}", fontWeight = FontWeight.Bold)
                        Text(j.status)
                    }
                    Text("Son hareket: ${j.lastEvent}")
                    Text(j.lastSeen, style = MaterialTheme.typography.bodySmall)
                    if (j.cartItems > 0) {
                        Text("Sepet: ${j.cartItems} ürün · ${money(j.cartTotal)}", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Timeline'ı aç →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun JourneyDetailScreen(token: String, journeyId: String, onBack: () -> Unit) {
    val api = remember(token) { ApiClient(token) }
    var detail by remember(journeyId) { mutableStateOf<JourneyDetail?>(null) }
    var error by remember(journeyId) { mutableStateOf<String?>(null) }
    var loading by remember(journeyId) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { api.journey(journeyId) }
                .onSuccess { detail = it }
                .onFailure { error = it.message ?: "Timeline alınamadı" }
            loading = false
        }
    }

    LaunchedEffect(journeyId, token) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Ziyaretçi Timeline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Hareketler kronolojik sıradadır.", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Yenile") }
            }
        }

        if (loading && detail == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message ->
            item {
                ErrorCard(message)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack) { Text("Geri dön") }
            }
        }

        detail?.let { d ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Misafir #${d.visitor}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(d.status, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Sepet: ${d.cartItems} ürün · ${money(d.cartTotal)}")
                        d.checkoutStarted?.let { Text("Checkout: $it", style = MaterialTheme.typography.bodySmall) }
                        d.purchasedAt?.let { Text("Satın alma: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            if (d.cart.isNotEmpty()) {
                item { Text("Mevcut / son sepet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(d.cart) { cartItem ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(cartItem.name, fontWeight = FontWeight.SemiBold)
                                Text("Adet: ${formatQty(cartItem.quantity)}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (cartItem.lineTotal > 0) Text(money(cartItem.lineTotal), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            item { Text("Hareket geçmişi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }

            if (d.timeline.isEmpty()) {
                item { Text("Bu ziyaret için kayıtlı hareket bulunamadı.") }
            } else {
                items(d.timeline, key = { it.id }) { event -> TimelineEventCard(event) }
            }
        }
    }
}

@Composable
private fun TimelineEventCard(event: TimelineEvent) {
    val icon = when (event.type) {
        "begin_checkout", "checkout_item" -> Icons.Default.CreditCard
        "add_to_cart" -> Icons.Default.AddShoppingCart
        "remove_from_cart", "cart_emptied" -> Icons.Default.RemoveShoppingCart
        "order_success", "purchase_item" -> Icons.Default.Paid
        "product_view" -> Icons.Default.Visibility
        "coupon_applied", "coupon_removed" -> Icons.Default.LocalOffer
        else -> Icons.Default.Bolt
    }

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(event.label, fontWeight = FontWeight.Bold)
                    Text(event.time, style = MaterialTheme.typography.labelSmall)
                }
                event.productName?.let { Text(it) }
                if (event.quantity > 0 && event.productName != null) {
                    Text("Adet: ${formatQty(event.quantity)}", style = MaterialTheme.typography.bodySmall)
                }
                if (event.value > 0) Text(money(event.value), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EventCard(event: EventItem, onClick: () -> Unit) {
    val icon = when (event.type) {
        "begin_checkout" -> Icons.Default.CreditCard
        "add_to_cart" -> Icons.Default.AddShoppingCart
        "remove_from_cart" -> Icons.Default.RemoveShoppingCart
        "order_success", "purchase_item" -> Icons.Default.Paid
        "product_view" -> Icons.Default.Visibility
        else -> Icons.Default.Bolt
    }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(event.label, fontWeight = FontWeight.Bold)
                    Text(event.createdAt, style = MaterialTheme.typography.labelSmall)
                }
                Text("Misafir #${event.visitor}", style = MaterialTheme.typography.bodySmall)
                event.productName?.let { Text(it) }
                if (event.value > 0) Text(money(event.value), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            message,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

private fun formatQty(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun money(value: Double): String =
    NumberFormat.getCurrencyInstance(Locale("tr", "TR")).format(value)
