package ir.yekari.shell

import android.content.Context

/**
 * کار پس‌زمینه‌ای که وب‌اپ با `YekariAndroid.startTracking(json)` روشن می‌کند.
 * فقط اپ پیک پیاده‌سازی دارد (ارسال موقعیت + اعلان پیشنهاد وقتی اپ در پس‌زمینه است)؛
 * در اپ مشتری [AppFeatures.tracker] خالی است.
 */
interface Tracker {
    /** روی نخ اصلی؛ `config` همان JSONی است که وب‌اپ داده */
    fun start(activity: MainActivity, config: String)

    fun stop(context: Context)
}
