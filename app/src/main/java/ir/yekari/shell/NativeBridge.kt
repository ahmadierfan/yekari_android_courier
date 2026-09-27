package ir.yekari.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * `window.YekariAndroid` — تنها راه وب‌اپ به امکانات گوشی.
 *
 * وب‌اپ با `if (window.YekariAndroid)` تشخیص می‌دهد داخل اپ است؛ User-Agent هم
 * `YekariAndroid/<نسخه> (<customer|courier>)` دارد. متدها فقط روی صفحهٔ خود یکاری
 * کار می‌کنند ([trusted])، نه روی درگاه پرداخت یا هر صفحهٔ دیگری که در وب‌ویو باز شود.
 * نتیجهٔ کارهای ناهم‌زمان به‌شکل رویداد `yekari:*` روی `window` برمی‌گردد.
 */
class NativeBridge(private val activity: MainActivity) {
    /** از WebViewClient روی هر ناوبری به‌روز می‌شود؛ متدها روی نخ پل صدا زده می‌شوند */
    @Volatile
    var trusted = false

    private fun ui(block: () -> Unit) {
        if (!trusted) return
        activity.runOnUiThread { if (trusted) block() }
    }

    /** `{platform, app, version, build, sdk, tracking}` */
    @JavascriptInterface
    fun info(): String {
        if (!trusted) return "{}"
        return JSONObject()
            .put("platform", "android")
            .put("app", BuildConfig.APP_KIND)
            .put("version", BuildConfig.VERSION_NAME)
            .put("build", BuildConfig.VERSION_CODE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("tracking", AppFeatures.tracker != null)
            .toString()
    }

    /** پشتوانهٔ `navigator.share` (وب‌ویو خودش ندارد) — `{title, text, url}` */
    @JavascriptInterface
    fun share(json: String) = ui {
        val data = runCatching { JSONObject(json) }.getOrNull() ?: return@ui
        val text = listOf("text", "url").mapNotNull { data.optString(it).takeIf(String::isNotBlank) }.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, data.optString("title"))
            .putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_via)))
    }

    /** پشتوانهٔ `navigator.clipboard.writeText` وقتی وب‌ویو اجازه نمی‌دهد */
    @JavascriptInterface
    fun copy(text: String) = ui {
        val cm = activity.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("yekari", text))
    }

    /** رنگ نوار وضعیت/ناوبری = `--surface` صفحه ("255 255 255" یا #hex) */
    @JavascriptInterface
    fun setBars(surface: String, dark: Boolean) = ui {
        activity.setBars(parseColor(surface), dark)
    }

    /** `light` | `success` | `error` */
    @JavascriptInterface
    fun haptic(kind: String) = ui { activity.haptic(kind) }

    /** `granted` | `prompt` | `unsupported` — نوع‌ها: location, camera, microphone, notifications */
    @JavascriptInterface
    fun permission(kind: String): String {
        if (!trusted) return "unsupported"
        val k = Kind.of(kind) ?: return "unsupported"
        return if (activity.gate.has(k)) "granted" else "prompt"
    }

    /** جواب با رویداد `yekari:permission` — `detail: {kind, granted}` */
    @JavascriptInterface
    fun requestPermission(kind: String) = ui {
        val k = Kind.of(kind) ?: return@ui
        activity.gate.request(k) { ok -> activity.emit("permission", JSONObject().put("kind", kind).put("granted", ok)) }
    }

    /** اعلان محلی؛ لمسش اپ را روی `path` باز می‌کند */
    @JavascriptInterface
    fun notify(title: String, body: String, path: String?) = ui {
        Notifier.show(activity, Notifier.CHANNEL_GENERAL, title, body, path)
    }

    /** وقتی کاربر مجوزی را برای همیشه رد کرده، تنها راه برگرداندنش تنظیمات است */
    @JavascriptInterface
    fun openSettings() = ui {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
        )
    }

    @JavascriptInterface
    fun openExternal(url: String) = ui { activity.openExternal(Uri.parse(url)) }

    /** فقط اپ پیک: روشن‌کردن سرویس پس‌زمینه. false یعنی این اپ چنین کاری ندارد */
    @JavascriptInterface
    fun startTracking(config: String): Boolean {
        val tracker = AppFeatures.tracker ?: return false
        if (!trusted) return false
        activity.runOnUiThread { tracker.start(activity, config) }
        return true
    }

    @JavascriptInterface
    fun stopTracking() = ui { AppFeatures.tracker?.stop(activity) }

    private fun parseColor(value: String): Int? = runCatching {
        val v = value.trim()
        if (v.startsWith("#")) return@runCatching Color.parseColor(v)
        val parts = v.removePrefix("rgb(").removeSuffix(")").split(' ', ',', '/').filter { it.isNotBlank() }.map { it.trim().toInt() }
        Color.rgb(parts[0], parts[1], parts[2])
    }.getOrNull()

    companion object {
        const val NAME = "YekariAndroid"

        /**
         * بعد از هر بارگذاری کامل صفحهٔ یکاری تزریق می‌شود. وب‌اپ لازم نیست چیزی بداند:
         * `navigator.share` و `clipboard` کار می‌کنند و رنگ نوارها با تم عوض می‌شود.
         */
        val SHIM = """
            (function () {
              if (window.__yekariShell || !window.$NAME) return;
              window.__yekariShell = true;
              var N = window.$NAME;
              if (!navigator.share) {
                navigator.share = function (d) {
                  try { N.share(JSON.stringify(d || {})); return Promise.resolve(); }
                  catch (e) { return Promise.reject(e); }
                };
              }
              var cb = navigator.clipboard;
              var write = cb && cb.writeText ? cb.writeText.bind(cb) : null;
              var copy = function (t) {
                return (write ? write(t) : Promise.reject()).catch(function () { N.copy(String(t)); });
              };
              if (cb) { try { cb.writeText = copy; } catch (e) {} }
              else { try { Object.defineProperty(navigator, 'clipboard', { value: { writeText: copy } }); } catch (e) {} }
              var root = document.documentElement;
              var bars = function () {
                N.setBars(getComputedStyle(root).getPropertyValue('--surface').trim(), root.dataset.theme === 'dark');
              };
              new MutationObserver(bars).observe(root, { attributes: true, attributeFilter: ['data-theme'] });
              bars();
              window.dispatchEvent(new Event('yekari:ready'));
            })();
        """.trimIndent()
    }
}
