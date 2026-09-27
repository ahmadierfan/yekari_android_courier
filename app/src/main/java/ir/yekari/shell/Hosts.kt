package ir.yekari.shell

import android.net.Uri

/**
 * کدام آدرس داخل همین وب‌ویو باز می‌شود و کدام به اپ/مرورگر بیرونی می‌رود.
 *
 * - میزبان‌های خود اپ (`TRUSTED_HOSTS` + میزبان `WEB_URL`، با زیردامنه‌ها) داخل اپ
 *   می‌مانند و فقط همین‌ها به پل بومی (`YekariAndroid`) دسترسی دارند.
 * - درگاه‌های پرداخت هم داخل اپ می‌مانند، وگرنه برگشت به `/app/wallet?status=` در
 *   مرورگر بیرونی باز می‌شود و کاربر از اپ جا می‌ماند. پل بومی روی آن‌ها بسته است.
 * - بقیه (نشان، گوگل‌مپ، tel:, geo:, intent:, …) بیرون باز می‌شوند.
 */
object Hosts {
    private val appHost: String = Uri.parse(BuildConfig.WEB_URL).host.orEmpty().lowercase()

    private val trusted: List<String> =
        BuildConfig.TRUSTED_HOSTS.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } + appHost

    private val gateways = listOf("zibal.ir", "zarinpal.com", "shaparak.ir")

    private fun matches(host: String?, list: List<String>): Boolean {
        val h = host?.lowercase() ?: return false
        return list.any { h == it || h.endsWith(".$it") }
    }

    private fun isWeb(uri: Uri) = uri.scheme == "http" || uri.scheme == "https"

    /** صفحهٔ خود یکاری — فقط این‌ها پل بومی را می‌بینند */
    fun isApp(uri: Uri?): Boolean = uri != null && isWeb(uri) && matches(uri.host, trusted)

    /** در همین وب‌ویو باز شود؟ */
    fun staysInside(uri: Uri): Boolean = isApp(uri) || (uri.scheme == "https" && matches(uri.host, gateways))
}
