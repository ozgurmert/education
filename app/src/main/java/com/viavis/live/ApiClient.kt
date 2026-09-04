package com.viavis.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val VIAVIS_API_BASE = "https://viavis.com.tr/wp-json/viavis/v1/"

data class DashboardData(
    val viewers: Int = 0,
    val cartUsers: Int = 0,
    val checkoutUsers: Int = 0,
    val orders: Int = 0,
    val revenue: Double = 0.0,
    val abandoned: Int = 0,
    val abandonedValue: Double = 0.0,
    val viewToCart: Double = 0.0,
    val cartToOrder: Double = 0.0
)

data class EventItem(
    val id: Long,
    val type: String,
    val label: String,
    val visitor: String,
    val journeyId: String,
    val productName: String?,
    val quantity: Double,
    val value: Double,
    val orderId: Int,
    val createdAt: String
)

data class ProductItem(
    val productId: Int,
    val name: String,
    val views: Int,
    val uniqueViews: Int,
    val cartUsers: Int,
    val checkoutUsers: Int,
    val buyers: Int,
    val revenue: Double,
    val viewToCart: Double,
    val cartToOrder: Double
)

data class JourneyItem(
    val journeyId: String,
    val visitor: String,
    val status: String,
    val lastEvent: String,
    val lastSeen: String,
    val cartItems: Int,
    val cartTotal: Double,
    val checkoutStarted: String?
)

data class AbandonedItem(
    val journeyId: String,
    val visitor: String,
    val orderId: Int,
    val checkoutStarted: Boolean,
    val lastSeen: String,
    val cartItems: Int,
    val cartTotal: Double,
    val cartNames: List<String>
)

data class JourneyCartItem(
    val name: String,
    val quantity: Double,
    val lineTotal: Double
)

data class TimelineEvent(
    val id: Long,
    val type: String,
    val label: String,
    val productName: String?,
    val quantity: Double,
    val value: Double,
    val time: String
)

data class JourneyDetail(
    val journeyId: String,
    val visitor: String,
    val status: String,
    val cartItems: Int,
    val cartTotal: Double,
    val checkoutStarted: String?,
    val purchasedAt: String?,
    val cart: List<JourneyCartItem>,
    val timeline: List<TimelineEvent>
)

class ApiClient(private val token: String) {

    // OverviewScreen eski isteği iptal etmeden yeni gün aralığı isteği başlatabildiği için
    // dashboard çağrılarını sıraya alıyoruz. Böylece eski 7 günlük cevap, daha sonra
    // seçilmiş "Bugün" cevabının üstüne yazamaz.
    private val dashboardMutex = Mutex()

    /**
     * Every GET request is intentionally cache-busted. The Viavis site sits behind
     * LiteSpeed/Cloudflare and range queries must never reuse a response generated
     * for another `days` value.
     */
    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val separator = if (path.contains('?')) '&' else '?'
        val requestPath = "$path${separator}_viavis_ts=${System.currentTimeMillis()}"
        val connection = URL(VIAVIS_API_BASE + requestPath).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.useCaches = false
        connection.defaultUseCaches = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate")
        connection.setRequestProperty("Pragma", "no-cache")
        connection.setRequestProperty("Expires", "0")

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "HTTP $code" })
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun dashboard(days: Int): DashboardData = dashboardMutex.withLock {
        val safeDays = normalizeDays(days)
        val o = get("dashboard?days=$safeDays&range=${rangeName(safeDays)}")
        verifyPeriodIfPresent(o, safeDays)
        DashboardData(
            viewers = o.optInt("viewers"),
            cartUsers = o.optInt("cart_users"),
            checkoutUsers = o.optInt("checkout_users"),
            orders = o.optInt("orders"),
            revenue = o.optDouble("revenue"),
            abandoned = o.optInt("abandoned"),
            abandonedValue = o.optDouble("abandoned_value"),
            viewToCart = o.optDouble("view_to_cart"),
            cartToOrder = o.optDouble("cart_to_order")
        )
    }

    suspend fun events(limit: Int = 50, afterId: Long = 0): List<EventItem> {
        val safeLimit = limit.coerceIn(1, 200)
        val suffix = if (afterId > 0) {
            "events?limit=$safeLimit&after_id=$afterId"
        } else {
            "events?limit=$safeLimit"
        }
        val array = get(suffix).optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    EventItem(
                        id = o.optLong("id"),
                        type = o.optString("type"),
                        label = o.optString("label"),
                        visitor = o.optString("visitor"),
                        journeyId = o.optString("journey_id"),
                        productName = o.optString("product_name").takeIf { it.isNotBlank() && it != "null" },
                        quantity = o.optDouble("quantity"),
                        value = o.optDouble("value"),
                        orderId = o.optInt("order_id"),
                        createdAt = o.optString("created_at")
                    )
                )
            }
        }
    }

    suspend fun products(days: Int): List<ProductItem> {
        val safeDays = normalizeDays(days)
        val root = get("products?days=$safeDays&range=${rangeName(safeDays)}")
        verifyPeriodIfPresent(root, safeDays)
        val array = root.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    ProductItem(
                        productId = o.optInt("product_id"),
                        name = o.optString("name"),
                        views = o.optInt("views"),
                        uniqueViews = o.optInt("unique_views"),
                        cartUsers = o.optInt("cart_users"),
                        checkoutUsers = o.optInt("checkout_users"),
                        buyers = o.optInt("buyers"),
                        revenue = o.optDouble("revenue"),
                        viewToCart = o.optDouble("view_to_cart"),
                        cartToOrder = o.optDouble("cart_to_order")
                    )
                )
            }
        }
    }

    suspend fun journeys(days: Int): List<JourneyItem> {
        val safeDays = normalizeDays(days)
        val root = get("journeys?days=$safeDays&range=${rangeName(safeDays)}")
        verifyPeriodIfPresent(root, safeDays)
        val array = root.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    JourneyItem(
                        journeyId = o.optString("journey_id"),
                        visitor = o.optString("visitor"),
                        status = o.optString("status"),
                        lastEvent = o.optString("last_event"),
                        lastSeen = o.optString("last_seen"),
                        cartItems = o.optInt("cart_items"),
                        cartTotal = o.optDouble("cart_total"),
                        checkoutStarted = o.optString("checkout_started").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
        }
    }

    suspend fun abandoned(days: Int): List<AbandonedItem> {
        val safeDays = normalizeDays(days)
        val root = get("abandoned?days=$safeDays&range=${rangeName(safeDays)}")
        verifyPeriodIfPresent(root, safeDays)
        val array = root.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val cart = o.optJSONArray("cart") ?: JSONArray()
                val names = buildList {
                    for (j in 0 until cart.length()) {
                        add(cart.getJSONObject(j).optString("name", "Ürün"))
                    }
                }
                add(
                    AbandonedItem(
                        journeyId = o.optString("journey_id"),
                        visitor = o.optString("visitor"),
                        orderId = o.optInt("order_id"),
                        checkoutStarted = o.optBoolean("checkout_started"),
                        lastSeen = o.optString("last_seen"),
                        cartItems = o.optInt("cart_items"),
                        cartTotal = o.optDouble("cart_total"),
                        cartNames = names
                    )
                )
            }
        }
    }

    suspend fun journey(journeyId: String): JourneyDetail {
        val cleanJourneyId = journeyId.trim()
        require(cleanJourneyId.matches(Regex("^[a-fA-F0-9]{64}$"))) { "Geçersiz journey ID" }

        val o = get("journey/$cleanJourneyId")

        val cartArray = o.optJSONArray("cart") ?: JSONArray()
        val cartItems = buildList {
            for (i in 0 until cartArray.length()) {
                val item = cartArray.getJSONObject(i)
                add(
                    JourneyCartItem(
                        name = item.optString("name", "Ürün"),
                        quantity = item.optDouble("quantity", 1.0),
                        lineTotal = item.optDouble("line_total", 0.0)
                    )
                )
            }
        }

        val timelineArray = o.optJSONArray("timeline") ?: JSONArray()
        val timeline = buildList {
            for (i in 0 until timelineArray.length()) {
                val event = timelineArray.getJSONObject(i)
                add(
                    TimelineEvent(
                        id = event.optLong("id"),
                        type = event.optString("type"),
                        label = event.optString("label"),
                        productName = event.optString("product_name")
                            .takeIf { it.isNotBlank() && it != "null" },
                        quantity = event.optDouble("quantity"),
                        value = event.optDouble("value"),
                        time = event.optString("time")
                    )
                )
            }
        }

        return JourneyDetail(
            journeyId = o.optString("journey_id"),
            visitor = o.optString("visitor"),
            status = o.optString("status"),
            cartItems = o.optInt("cart_items"),
            cartTotal = o.optDouble("cart_total"),
            checkoutStarted = o.optString("checkout_started").takeIf { it.isNotBlank() && it != "null" },
            purchasedAt = o.optString("purchased_at").takeIf { it.isNotBlank() && it != "null" },
            cart = cartItems,
            timeline = timeline
        )
    }

    private fun verifyPeriodIfPresent(root: JSONObject, requestedDays: Int) {
        val period = root.optJSONObject("_period") ?: return
        val responseDays = period.optInt("days", requestedDays)
        if (responseDays != requestedDays) {
            throw IllegalStateException("Gün filtresi yanıtı uyuşmadı. Tekrar deneyin.")
        }
    }

    private fun normalizeDays(days: Int): Int = when (days) {
        1, 7, 30, 60 -> days
        else -> 7
    }

    private fun rangeName(days: Int): String = if (days == 1) "today" else "${days}d"
}
