package ir.yekari.shell

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * یک موقعیت تازهٔ گوشی برای دکمهٔ «موقعیت من» وب‌اپ (`YekariAndroid.getLocation`).
 *
 * چرا پل و نه `navigator.geolocation` خود وب‌ویو: روی آدرس http (محیط توسعه) کرومیوم اصلاً
 * موقعیت نمی‌دهد، وقتی مکان‌یاب گوشی خاموش است فقط «پیدا نشد» می‌گوید، و وب‌اپ نمی‌فهمد مجوز
 * «برای همیشه» رد شده. این‌جا هر حالت جواب جدای خودش را دارد.
 * بدون Google Play Services (گوشی‌های بدون سرویس گوگل کم نیستند) — فقط LocationManager.
 */
class CurrentLocation(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** @return true اگر مکان‌یاب کلی گوشی روشن است */
    fun enabled(): Boolean = lm != null && LocationManagerCompat.isLocationEnabled(lm)

    /**
     * `done(location)` یک بار و روی نخ اصلی؛ null یعنی در زمان [TIMEOUT_MS] پیدا نشد.
     * فقط بعد از گرفتن مجوز صدا بزن.
     */
    @SuppressLint("MissingPermission")
    fun fetch(done: (Location?) -> Unit) {
        val lm = lm ?: return done(null)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val providers = buildList {
            if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val age = last?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        // همین چند ثانیه پیش موقعیت گرفته شده (مثلاً دو بار زدن دکمه) — منتظر GPS نمان
        if (last != null && age < FRESH_MS) return done(last)
        // اگر تازه پیدا نشد، موقعیت قدیمیِ چنددقیقه‌ای بهتر از هیچ است
        val fallback = last?.takeIf { age < STALE_MS }
        if (providers.isEmpty()) return done(fallback)

        val signal = CancellationSignal()
        var finished = false
        var pending = providers.size
        lateinit var timeout: Runnable
        val finish = { loc: Location? ->
            if (!finished) {
                finished = true
                main.removeCallbacks(timeout)
                signal.cancel()
                done(loc)
            }
        }
        timeout = Runnable { finish(fallback) }
        main.postDelayed(timeout, TIMEOUT_MS)

        // GPS و شبکه هم‌زمان: هرکدام زودتر جواب داد (شبکه در ساختمان معمولاً زودتر است)
        providers.forEach { provider ->
            LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                pending--
                if (loc != null) finish(loc) else if (pending == 0) finish(fallback)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val FRESH_MS = 20_000L
        const val STALE_MS = 10 * 60_000L
    }
}
