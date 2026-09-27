package ir.yekari.shell.services

import android.content.Context
import ir.yekari.shell.Kind
import ir.yekari.shell.MainActivity
import ir.yekari.shell.Tracker
import ir.yekari.shell.utils.BatteryGuide
import org.json.JSONObject

/**
 * `YekariAndroid.startTracking(json)` از وب‌اپ پیک وقتی آنلاین می‌شود.
 *
 * مجوز موقعیت «هنگام استفاده» کافی است: سرویس پیش‌زمینه همین حالا که اپ جلوی چشم
 * است روشن می‌شود و بعد از رفتن اپ به پس‌زمینه ادامه می‌دهد. مجوز «همیشه»
 * (ACCESS_BACKGROUND_LOCATION) لازم نیست و مارکت‌ها برایش توجیه جدا می‌خواهند.
 *
 * نتیجه با رویداد `yekari:tracking` — `detail: {running, reason?}` برمی‌گردد.
 */
object CourierTracker : Tracker {
    /** راهنمای باتری/Autostart هر بار اجرای اپ حداکثر یک بار (arm در هر صفحهٔ داشبورد صدا زده می‌شود) */
    private var guided = false

    override fun start(activity: MainActivity, config: String) {
        activity.gate.request(Kind.LOCATION) { located ->
            if (!located) {
                activity.emit("tracking", JSONObject().put("running", false).put("reason", "location"))
                return@request
            }
            // بدون اجازهٔ اعلان سرویس کار می‌کند، ولی پیشنهادِ پس‌زمینه دیده نمی‌شود
            activity.gate.request(Kind.NOTIFICATIONS) {
                val started = runCatching { TrackingService.start(activity, config) }.isSuccess
                if (started && !guided) {
                    guided = true
                    BatteryGuide.check(activity)
                }
                activity.emit(
                    "tracking",
                    JSONObject().put("running", started).apply { if (!started) put("reason", "service") },
                )
            }
        }
    }

    override fun stop(context: Context) = TrackingService.stop(context)
}
