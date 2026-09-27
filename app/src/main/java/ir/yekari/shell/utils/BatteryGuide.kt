package ir.yekari.shell.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import ir.yekari.shell.R

/**
 * همان الگوی اپ HomeEase: بدون معافیت از بهینه‌سازی باتری، Doze در بیکاری طولانی شبکهٔ
 * سرویس پیش‌زمینه را محدود می‌کند؛ روی شیائومی/هواوی/اوپو/… هم یک «Autostart» اختصاصی
 * هست که اگر روشن نشود، خود رام سرویس را می‌کشد.
 *
 * فقط وقتی سرویس واقعاً لازم است صدا زده می‌شود (پیک آنلاین شد)، نه هنگام باز شدن اپ.
 * دیالوگ باتری هر بار که هنوز معاف نیست، راهنمای OEM فقط یک بار برای همیشه
 * (هیچ API عمومی برای خواندن وضعیت آن تنظیم نیست).
 */
object BatteryGuide {
    private const val TAG = "BatteryGuide"
    private const val PREFS = "shell"
    private const val OEM_SHOWN = "oem_autostart_prompt_shown"

    fun check(activity: Activity) {
        val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(activity.packageName)) {
            showOemGuide(activity)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_body)
            .setPositiveButton(R.string.ok) { _, _ -> requestExemption(activity) }
            .setNegativeButton(R.string.later, null)
            .setOnDismissListener { showOemGuide(activity) }
            .show()
    }

    // مجوز REQUEST_IGNORE_BATTERY_OPTIMIZATIONS فقط در مانیفست اپ پیک است؛ مشتری این را صدا نمی‌زند
    @SuppressLint("BatteryLife")
    private fun requestExemption(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
            )
        } catch (e: Exception) {
            Log.w(TAG, "battery optimization settings unavailable", e)
            runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun showOemGuide(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(OEM_SHOWN, false)) return
        val intent = OemAutostartHelper.getAutostartIntent(activity) ?: return
        prefs.edit().putBoolean(OEM_SHOWN, true).apply()

        AlertDialog.Builder(activity)
            .setTitle(R.string.oem_title)
            .setMessage(activity.getString(R.string.oem_body, Build.MANUFACTURER))
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "OEM autostart screen unavailable", e)
                }
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }
}
