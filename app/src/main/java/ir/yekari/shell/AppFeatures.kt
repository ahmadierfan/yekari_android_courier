package ir.yekari.shell

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import ir.yekari.shell.tracking.CourierTracker
import ir.yekari.shell.tracking.TrackingService

/** تفاوت رفتاری اپ پیک با اپ مشتری (بقیهٔ پوستهٔ بومی در دو ریپو یکی است) */
object AppFeatures {
    /** آنلاین‌بودن در پس‌زمینه: موقعیت + اعلان پیشنهاد */
    val tracker: Tracker? = CourierTracker

    fun channels(context: Context): List<NotificationChannelCompat> = listOf(
        NotificationChannelCompat.Builder(TrackingService.CHANNEL_OFFERS, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.channel_offers))
            .setDescription(context.getString(R.string.channel_offers_desc))
            .setVibrationEnabled(true)
            .build(),
        NotificationChannelCompat.Builder(TrackingService.CHANNEL_TRACKING, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.channel_tracking))
            .setDescription(context.getString(R.string.channel_tracking_desc))
            .setShowBadge(false)
            .build(),
    )
}
