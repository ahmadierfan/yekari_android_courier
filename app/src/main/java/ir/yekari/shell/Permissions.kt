package ir.yekari.shell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** دسترسی‌هایی که وب‌اپ با نام می‌خواهد (`YekariAndroid.requestPermission('camera')`) */
enum class Kind(val permissions: Array<String>) {
    /** هرکدام از دو مجوز کافی است؛ «تقریبی» هم برای انتخاب محله کار می‌کند */
    LOCATION(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    CAMERA(arrayOf(Manifest.permission.CAMERA)),
    MICROPHONE(arrayOf(Manifest.permission.RECORD_AUDIO)),

    /** پیش از اندروید ۱۳ مجوز زمان اجرا ندارد */
    NOTIFICATIONS(
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    );

    companion object {
        fun of(name: String): Kind? = entries.find { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * درخواست مجوز «درست همان لحظه که لازم است» — نه همه با هم هنگام باز شدن اپ.
 * درخواست‌ها صف می‌شوند چون اندروید هم‌زمان فقط یک دیالوگ مجوز نشان می‌دهد
 * (مثلاً getUserMedia صدا و تصویر را با هم می‌خواهد).
 */
class PermissionGate(private val activity: ComponentActivity) {
    private class Request(val kind: Kind, val done: (Boolean) -> Unit)

    private val queue = ArrayDeque<Request>()
    private var running: Request? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val req = running ?: return@registerForActivityResult
        running = null
        req.done(has(req.kind))
        next()
    }

    fun has(kind: Kind): Boolean {
        if (kind == Kind.NOTIFICATIONS && !NotificationManagerCompat.from(activity).areNotificationsEnabled()) return false
        if (kind.permissions.isEmpty()) return true
        return kind.permissions.any {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** `done` روی نخ اصلی صدا زده می‌شود */
    fun request(kind: Kind, done: (Boolean) -> Unit) {
        if (has(kind) || kind.permissions.isEmpty()) {
            done(has(kind))
            return
        }
        queue.addLast(Request(kind, done))
        if (running == null) next()
    }

    private fun next() {
        val req = queue.removeFirstOrNull() ?: return
        if (has(req.kind)) {
            req.done(true)
            next()
            return
        }
        running = req
        launcher.launch(req.kind.permissions)
    }
}
