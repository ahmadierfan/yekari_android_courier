package ir.yekari.shell.tracking

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ir.yekari.shell.Hosts
import ir.yekari.shell.MainActivity
import ir.yekari.shell.Notifier
import ir.yekari.shell.R
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * سرویس پیش‌زمینهٔ «آنلاین هستم». تا وقتی پیک آنلاین است (حتی اگر اپ را ببندد):
 *
 * - هر `interval` ثانیه آخرین موقعیت را به `POST /courier/location` می‌فرستد — همان کاری که
 *   `courier.sendLocation()` وب‌اپ می‌کند، ولی وقتی وب‌ویو در پس‌زمینه متوقف است.
 * - وقتی اپ جلوی چشم نیست، `GET /courier/current` را می‌پرسد و برای پیشنهاد تازه
 *   اعلان پرصدا می‌دهد؛ اعلان وقتی فرصت پذیرش تمام شد خودش پاک می‌شود.
 *
 * از `LocationManager` خود اندروید استفاده می‌کند، نه Google Play Services — روی گوشی‌های
 * بدون سرویس گوگل (رایج در ایران) هم کار کند.
 *
 * توکن و آدرس API را خود وب‌اپ می‌دهد (`startTracking`) و فقط در حافظه نگه داشته
 * می‌شود؛ ۴۰۱ یعنی خروج/ابطال → سرویس خاموش.
 */
class TrackingService : Service() {
    private data class Config(val apiBase: String, val token: String, val intervalSec: Long)

    @Volatile
    private var config: Config? = null

    @Volatile
    private var latest: Location? = null

    private var worker: ScheduledExecutorService? = null
    private var lastOfferId: String? = null

    private val locations by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    // همهٔ متدها صریح پیاده شده‌اند: پیش از اندروید ۱۱ متدهای پیش‌فرض واسط را ندارد و کرش می‌کند
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latest = pickBetter(latest, location)
        }

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cfg = intent?.getStringExtra(EXTRA_CONFIG)?.let(::parse)
        if (cfg == null || !hasLocationPermission()) {
            shutdown()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, ONGOING_ID, ongoing(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // مثلاً اندروید ۱۴ وقتی مجوز موقعیت در همین لحظه گرفته نشده
            Log.w(TAG, "cannot start foreground", e)
            shutdown()
            return START_NOT_STICKY
        }

        val restart = config?.intervalSec != cfg.intervalSec
        config = cfg
        if (restart) begin(cfg)
        // بازشروع خودکار نه: بدون اپ، توکن و آدرس را نداریم و اندروید ۱۴ هم اجازه نمی‌دهد
        return START_NOT_STICKY
    }

    private fun begin(cfg: Config) {
        worker?.shutdownNow()
        locations.removeUpdates(listener)
        requestUpdates(cfg.intervalSec * 1000)
        worker = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({ guarded(::sendLocation) }, 0, cfg.intervalSec, TimeUnit.SECONDS)
            scheduleWithFixedDelay({ guarded(::pollOffer) }, POLL_SEC, POLL_SEC, TimeUnit.SECONDS)
        }
    }

    override fun onDestroy() {
        worker?.shutdownNow()
        worker = null
        locations.removeUpdates(listener)
        config = null
        super.onDestroy()
    }

    private fun shutdown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /* ── موقعیت ─────────────────────────────────────────────── */

    private fun hasLocationPermission() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestUpdates(intervalMs: Long) {
        if (!hasLocationPermission()) return
        val providers = locations.getProviders(true)
            .filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        try {
            for (p in providers) {
                locations.getLastKnownLocation(p)?.let { latest = pickBetter(latest, it) }
                locations.requestLocationUpdates(p, intervalMs, MIN_DISTANCE_M, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission revoked", e)
        }
    }

    /** تازه‌تر بهتر است، مگر محسوساً بی‌دقت‌تر باشد؛ موقعیتِ کهنه همیشه جایگزین می‌شود */
    private fun pickBetter(current: Location?, candidate: Location): Location {
        if (current == null) return candidate
        val newer = candidate.time - current.time
        return when {
            newer > STALE_MS -> candidate
            newer < -STALE_MS -> current
            candidate.accuracy <= current.accuracy + ACCURACY_SLACK_M -> candidate
            else -> current
        }
    }

    private fun sendLocation() {
        val loc = latest ?: return
        call("POST", "/courier/location", JSONObject().put("lat", loc.latitude).put("lng", loc.longitude))
    }

    /* ── پیشنهاد تازه در پس‌زمینه ──────────────────────────── */

    private fun pollOffer() {
        // اپ باز است → خود وب‌اپ پیشنهاد را با شمارش معکوس نشان می‌دهد
        if (MainActivity.inForeground) return
        val res = call("GET", "/courier/current", null) ?: return
        val offer = res.optJSONObject("offer")
        if (offer == null || res.optJSONObject("active") != null) {
            if (offer == null) lastOfferId = null
            return
        }
        val id = offer.opt("id")?.toString() ?: return
        if (id == lastOfferId) return
        lastOfferId = id
        notifyOffer(offer.optInt("left", 0))
    }

    private fun notifyOffer(leftSec: Int) {
        if (!Notifier.canPost(this)) return
        val body = if (leftSec > 0) {
            getString(R.string.offer_body_left, NumberFormat.getIntegerInstance(Locale("fa", "IR")).format(leftSec))
        } else {
            getString(R.string.offer_body)
        }
        val builder = Notifier.builder(this, CHANNEL_OFFERS, getString(R.string.offer_title), body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(Notifier.openApp(this, "/courier", OFFER_ID))
        if (leftSec > 0) builder.setTimeoutAfter(leftSec * 1000L)
        try {
            NotificationManagerCompat.from(this).notify(OFFER_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notification blocked", e)
        }
    }

    private fun ongoing() = Notifier.builder(this, CHANNEL_TRACKING, getString(R.string.tracking_title), getString(R.string.tracking_body))
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(Notifier.openApp(this, "/courier", ONGOING_ID))
        .build()

    /* ── HTTP ───────────────────────────────────────────────── */

    /** پاسخ JSON یا null؛ ۴۰۱ یعنی توکن باطل شد → سرویس خاموش */
    private fun call(method: String, path: String, body: JSONObject?): JSONObject? {
        val cfg = config ?: return null
        val conn = URL(cfg.apiBase + path).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            if (code == 401) {
                ContextCompat.getMainExecutor(this).execute { shutdown() }
                return null
            }
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }.let { runCatching { JSONObject(it) }.getOrNull() }
        } catch (e: IOException) {
            null // اینترنت قطع است؛ دور بعد
        } finally {
            conn.disconnect()
        }
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // خطای یک دور نباید زمان‌بند را برای همیشه متوقف کند
            Log.w(TAG, "tracking tick failed", e)
        }
    }

    /** `{apiBase, token, interval?}` — آدرس API باید از میزبان‌های خود یکاری باشد تا توکن جای دیگری نرود */
    private fun parse(json: String): Config? = runCatching {
        val o = JSONObject(json)
        val api = o.getString("apiBase").trimEnd('/')
        val token = o.getString("token")
        if (token.isBlank() || !Hosts.isApp(Uri.parse(api))) return null
        Config(api, token, o.optLong("interval", DEFAULT_INTERVAL_SEC).coerceIn(10, 120))
    }.getOrNull()

    companion object {
        const val CHANNEL_OFFERS = "offers"
        const val CHANNEL_TRACKING = "tracking"

        private const val TAG = "YekariTracking"
        private const val EXTRA_CONFIG = "config"
        private const val ONGOING_ID = 1
        private const val OFFER_ID = 2
        private const val DEFAULT_INTERVAL_SEC = 15L
        private const val POLL_SEC = 8L
        private const val MIN_DISTANCE_M = 15f
        private const val STALE_MS = 2 * 60 * 1000L
        private const val ACCURACY_SLACK_M = 50f
        private const val TIMEOUT_MS = 10_000

        fun start(context: Context, config: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).putExtra(EXTRA_CONFIG, config),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
