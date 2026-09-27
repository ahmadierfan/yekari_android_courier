package ir.yekari.shell

import android.content.Intent

/**
 * تبدیل intent ورودی (لینک عمیق، App Link، لمس اعلان) به آدرس وب‌اپ.
 *
 * - `yekari://app/orders/12?x=1` → `WEB_URL/app/orders/12?x=1`
 * - `https://<دامنهٔ اپ>/…` (App Link) → همان آدرس
 * - اکسترای [EXTRA_PATH] (از اعلان‌ها) → `WEB_URL` + مسیر
 */
object Links {
    const val EXTRA_PATH = "ir.yekari.shell.PATH"

    fun url(path: String): String = BuildConfig.WEB_URL + (if (path.startsWith("/")) path else "/$path")

    /** null یعنی این intent لینکی ندارد (مثلاً باز کردن ساده از لانچر) */
    fun resolve(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(EXTRA_PATH)?.let { if (isSafePath(it)) return url(it) }
        val data = intent.data ?: return null
        return when {
            data.scheme == BuildConfig.DEEP_LINK_SCHEME -> {
                val path = "/" + listOfNotNull(data.host, data.encodedPath?.trim('/'))
                    .filter { it.isNotEmpty() }
                    .joinToString("/")
                val query = data.encodedQuery?.let { "?$it" }.orEmpty()
                if (isSafePath(path)) url(path + query) else null
            }
            Hosts.isApp(data) -> data.toString()
            else -> null
        }
    }

    /** مسیر نسبی خود اپ؛ `//evil.com` یا بک‌اسلش نمی‌تواند اپ را به دامنهٔ دیگری ببرد */
    private fun isSafePath(path: String) = path.startsWith("/") && !path.startsWith("//") && '\\' !in path
}
