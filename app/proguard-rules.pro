# متدهای پل جاوااسکریپت با نام از وب صدا زده می‌شوند؛ R8 نباید حذف یا تغییر نامشان بدهد
-keepclassmembers class ir.yekari.shell.NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
