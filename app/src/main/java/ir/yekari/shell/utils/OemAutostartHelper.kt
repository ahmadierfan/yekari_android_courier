package ir.yekari.shell.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

/**
 * روی چند OEM رایج (شیائومی، هواوی/آنر، اوپو، ویوو، وان‌پلاس، سامسونگ)، اندروید استاندارد
 * کافی نیست — این گوشی‌ها یک تنظیم اختصاصی خودشون به اسم «Autostart»/«App Protection»
 * دارن که اگه کاربر دستی فعالش نکنه، فورگراند سرویس (مثل سرویس آنلاین پیک)
 * می‌تونه توسط خودِ سیستم (نه AOSP استاندارد) کشته بشه.
 *
 * هیچ API عمومی اندرویدی برای این تنظیمات وجود نداره؛ فقط می‌شه سعی کرد صفحهٔ
 * اختصاصی همون OEM رو با یک Intent صریح باز کرد. اگه صفحه روی این نسخهٔ خاص از
 * رام وجود نداشته باشه (برندها مدام این اکتیویتی‌ها رو جابه‌جا می‌کنن)، فقط null برمی‌گرده.
 *
 * منبع Intentها: پروژهٔ متن‌باز و مرجع رایج dontkillmyapp.com
 */
object OemAutostartHelper {

    private fun candidateIntents(manufacturer: String): List<Intent> {
        return when (manufacturer) {
            "xiaomi" -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.securitycenter.permission.AutoStartManagementActivity"
                    )
                )
            )

            "huawei", "honor" -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                )
            )

            "oppo" -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"
                    )
                )
            )

            "vivo" -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                    )
                )
            )

            "oneplus" -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                    )
                )
            )

            "samsung" -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    )
                )
            )

            "meizu" -> listOf(
                Intent().setComponent(
                    ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")
                )
            )

            "asus" -> listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.asus.mobilemanager",
                        "com.asus.mobilemanager.autostart.AutoStartActivity"
                    )
                )
            )

            else -> emptyList()
        }
    }

    /**
     * اولین Intent‌ای که روی این گوشی واقعاً resolve می‌شه رو برمی‌گردونه، یا null
     * اگه این OEM شناخته‌شده نیست یا هیچ‌کدوم از صفحات کاندید روی این رام وجود نداره.
     */
    fun getAutostartIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val packageManager = context.packageManager
        return candidateIntents(manufacturer).firstOrNull { intent ->
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    }
}
