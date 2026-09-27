package ir.yekari.shell

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/** اعلان‌های محلی. لمس هر اعلان اپ را روی مسیر وب مربوطش باز می‌کند. */
object Notifier {
    const val CHANNEL_GENERAL = "general"

    private val ids = AtomicInteger(1000)

    fun createChannels(context: Context) {
        val general = NotificationChannelCompat.Builder(CHANNEL_GENERAL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.channel_general))
            .setDescription(context.getString(R.string.channel_general_desc))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(listOf(general) + AppFeatures.channels(context))
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun openApp(context: Context, path: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (!path.isNullOrBlank()) putExtra(Links.EXTRA_PATH, path) }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun builder(context: Context, channel: String, title: String, body: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))

    /** false یعنی اعلان نشان داده نشد (مجوز نداریم) */
    fun show(context: Context, channel: String, title: String, body: String, path: String?, id: Int = ids.incrementAndGet()): Boolean {
        if (!canPost(context)) return false
        val notification = builder(context, channel, title, body)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, path, id))
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
