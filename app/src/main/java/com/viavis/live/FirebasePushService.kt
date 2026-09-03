package com.viavis.live

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val PUSH_PREFS = "viavis_live"
private const val PUSH_API_TOKEN = "api_token"
private const val CHANNEL_CHECKOUT = "viavis_checkout"
private const val CHANNEL_ORDERS = "viavis_orders"
private const val CHANNEL_ALERTS = "viavis_alerts"

class ViavisApplication : Application(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
        prefs = getSharedPreferences(PUSH_PREFS, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        registerCurrentFcmToken()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == PUSH_API_TOKEN) {
            registerCurrentFcmToken()
        }
    }

    private fun registerCurrentFcmToken() {
        val apiToken = prefs.getString(PUSH_API_TOKEN, "").orEmpty()
        if (apiToken.isBlank()) return

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { fcmToken ->
                if (fcmToken.isNotBlank()) {
                    ViavisFcmRegistrar.register(apiToken, fcmToken)
                }
            }
    }
}

class ViavisFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        val apiToken = getSharedPreferences(PUSH_PREFS, MODE_PRIVATE)
            .getString(PUSH_API_TOKEN, "")
            .orEmpty()

        if (apiToken.isNotBlank() && token.isNotBlank()) {
            ViavisFcmRegistrar.register(apiToken, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        createNotificationChannels(this)

        val type = message.data["type"].orEmpty()
        val title = message.notification?.title
            ?: message.data["title"]
            ?: when (type) {
                "checkout" -> "Viavis — Checkout başladı"
                "order" -> "Viavis — Yeni sipariş"
                else -> "Viavis Live"
            }

        val body = message.notification?.body
            ?: message.data["body"]
            ?: "Viavis'te yeni bir hareket var."

        val channel = when (type) {
            "checkout" -> CHANNEL_CHECKOUT
            "order" -> CHANNEL_ORDERS
            else -> CHANNEL_ALERTS
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("push_type", type)
            putExtra("journey_id", message.data["journey_id"].orEmpty())
            putExtra("order_id", message.data["order_id"].orEmpty())
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() and 0x7fffffff).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(
                (System.currentTimeMillis() and 0x7fffffff).toInt(),
                notification
            )
        } catch (_: SecurityException) {
            // Android 13+ bildirim izni verilmediyse sessizce geç.
        }
    }
}

object ViavisFcmRegistrar {

    fun register(apiToken: String, fcmToken: String) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(VIAVIS_API_BASE + "device").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 12_000
                connection.readTimeout = 12_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $apiToken")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")

                val payload = JSONObject().apply {
                    put("token", fcmToken)
                    put("platform", "android")
                    put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    put("android_version", Build.VERSION.RELEASE ?: "")
                }.toString()

                connection.outputStream.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                }

                // Response'u tüket; kayıt başarısız olursa FCM onNewToken veya
                // bir sonraki uygulama açılışında tekrar denenecek.
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                stream?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                // Ağ geçici olarak yoksa sonraki açılışta yeniden denenir.
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
}

private fun createNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

    val manager = context.getSystemService(NotificationManager::class.java)

    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_CHECKOUT,
            "Checkout bildirimleri",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Bir ziyaretçi ödeme sayfasına geçtiğinde bildirim gönderir."
        }
    )

    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ORDERS,
            "Sipariş bildirimleri",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Yeni Viavis siparişleri."
        }
    )

    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ALERTS,
            "Viavis uyarıları",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Diğer Viavis Live bildirimleri."
        }
    )
}
