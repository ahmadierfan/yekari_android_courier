package ir.yekari.shell

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import kotlin.math.max

/**
 * پوستهٔ بومی: یک وب‌ویو تمام‌صفحه روی وب‌اپ Nuxt (`BuildConfig.WEB_URL`).
 *
 * هرچه وب‌اپ از مرورگر می‌خواهد و وب‌ویو خودش ندارد این‌جا پر می‌شود: مجوز موقعیت،
 * دوربین/گالری، میکروفون، باز کردن لینک‌های بیرونی، دکمهٔ برگشت، رنگ نوارهای سیستم،
 * صفحهٔ «اتصال برقرار نیست». جزئیات هر بخش در فایل خودش.
 */
class MainActivity : ComponentActivity() {
    val gate = PermissionGate(this)
    private val picker = FilePicker(this)
    private val bridge = NativeBridge(this)

    private lateinit var root: FrameLayout
    private lateinit var web: WebView

    @Volatile
    private var firstPaint = false
    private var lastBack = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // اسپلش تا اولین رنگ صفحه می‌ماند، ولی نه بیشتر از چند ثانیه روی اینترنت کند
        splash.setKeepOnScreenCondition { !firstPaint }
        Handler(Looper.getMainLooper()).postDelayed({ firstPaint = true }, SPLASH_MAX_MS)

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        root = FrameLayout(this)
        setContentView(root)
        // وب‌اپ زیر نوارهای سیستم و کیبورد نرود؛ رنگ پشت نوارها را setBars می‌دهد
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        Notifier.createChannels(this)
        FilePicker.cleanup(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        web = buildWebView()
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        web.loadUrl(Links.resolve(intent) ?: BuildConfig.WEB_URL)

        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Links.resolve(intent)?.let(web::loadUrl)
    }

    override fun onStart() {
        super.onStart()
        inForeground = true
    }

    override fun onStop() {
        inForeground = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        emit("resume")
    }

    override fun onPause() {
        emit("pause")
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun buildWebView(): WebView = WebView(this).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            allowFileAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // چیدمان وب‌اپ با px دقیق طراحی شده؛ بزرگ‌نمایی فونت سیستم آن را می‌شکند
            textZoom = 100
            userAgentString = "$userAgentString YekariAndroid/${BuildConfig.VERSION_NAME} (${BuildConfig.APP_KIND})"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(bridge, NativeBridge.NAME)
        webViewClient = ShellWebClient()
        webChromeClient = ShellChromeClient()
        setDownloadListener { url, _, _, _, _ -> openExternal(Uri.parse(url)) }
        overScrollMode = WebView.OVER_SCROLL_NEVER
    }

    /* ── دکمهٔ برگشت ─────────────────────────────────────────── */

    /**
     * اول از وب‌اپ می‌پرسیم (`window.__yekariBack()` → true یعنی خودش بست، مثلاً یک شیت)،
     * بعد تاریخچهٔ وب‌ویو، و در صفحهٔ اول دو بار زدن برای خروج.
     */
    private fun handleBack() {
        web.evaluateJavascript(BACK_JS) { handled ->
            if (handled == "true") return@evaluateJavascript
            if (web.canGoBack()) {
                web.goBack()
                return@evaluateJavascript
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastBack < 2000) {
                finish()
            } else {
                lastBack = now
                Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* ── خدمت به پل ──────────────────────────────────────────── */

    /** رویداد `yekari:<name>` روی window وب‌اپ */
    fun emit(name: String, detail: JSONObject? = null) {
        if (!bridge.trusted || !::web.isInitialized) return
        web.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent(${JSONObject.quote("yekari:$name")},{detail:${detail ?: "null"}}))",
            null,
        )
    }

    fun setBars(color: Int?, dark: Boolean) {
        if (color != null) root.setBackgroundColor(color)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    fun haptic(kind: String) {
        val constant = when {
            kind == "success" && Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
            kind == "error" && Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.REJECT
            kind == "error" -> HapticFeedbackConstants.LONG_PRESS
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }
        web.performHapticFeedback(constant)
    }

    /** tel:, geo:, intent:, نشان، گوگل‌مپ، … — هرچه داخل اپ نمی‌ماند */
    fun openExternal(uri: Uri) {
        val intent = if (uri.scheme == "intent") {
            runCatching {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    // صفحهٔ وب نباید بتواند کامپوننت داخلی هیچ اپی را مستقیم صدا بزند
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            }.getOrNull() ?: return
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")?.let(Uri::parse)
            if (fallback != null && fallback.scheme?.startsWith("http") == true) {
                openExternal(fallback)
            } else {
                Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onFirstPaint() {
        if (firstPaint) return
        firstPaint = true
        // اجازهٔ اعلان یک بار، بعد از دیدن اپ — نه روی اسپلش
        val prefs = getSharedPreferences("shell", MODE_PRIVATE)
        if (!prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            gate.request(Kind.NOTIFICATIONS) { }
        }
    }

    /** پروسهٔ رندر وب‌ویو مرد (کمبود حافظه) — بدون این، کل اپ کرش می‌کند */
    private fun recoverRenderer() {
        root.removeView(web)
        web.destroy()
        web = buildWebView()
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        web.loadUrl(BuildConfig.WEB_URL)
    }

    private fun trackTrust(url: String?) {
        bridge.trusted = Hosts.isApp(url?.let(Uri::parse))
    }

    private inner class ShellWebClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val isWeb = uri.scheme == "http" || uri.scheme == "https"
            if (isWeb && (!request.isForMainFrame || Hosts.staysInside(uri))) return false
            openExternal(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = trackTrust(url)

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = trackTrust(url)

        override fun onPageFinished(view: WebView, url: String?) {
            trackTrust(url)
            if (bridge.trusted) view.evaluateJavascript(NativeBridge.SHIM, null)
            onFirstPaint()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val scheme = request.url.scheme
            if (scheme != "http" && scheme != "https") return
            if (BuildConfig.DEBUG) Log.w(TAG, "load failed ${error.errorCode} ${error.description}: ${request.url}")
            view.loadUrl(OFFLINE_PAGE + "#" + Uri.encode(request.url.toString()))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            recoverRenderer()
            return true
        }
    }

    private inner class ShellChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = picker.open(filePathCallback, fileChooserParams)

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (!Hosts.isApp(Uri.parse(origin))) {
                callback.invoke(origin, false, false)
                return
            }
            gate.request(Kind.LOCATION) { granted -> callback.invoke(origin, granted, false) }
        }

        /** getUserMedia: پیام صوتی چت (میکروفون) */
        override fun onPermissionRequest(request: PermissionRequest) {
            if (!Hosts.isApp(request.origin)) {
                request.deny()
                return
            }
            val wanted = request.resources.mapNotNull {
                when (it) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> it to Kind.MICROPHONE
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> it to Kind.CAMERA
                    else -> null
                }
            }
            if (wanted.isEmpty()) {
                request.deny()
                return
            }
            val granted = mutableListOf<String>()
            var left = wanted.size
            wanted.forEach { (resource, kind) ->
                gate.request(kind) { ok ->
                    if (ok) granted += resource
                    if (--left == 0) {
                        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
                    }
                }
            }
        }

        /** `target="_blank"` / `window.open` — آدرس را می‌گیریم و خودمان مسیریابی می‌کنیم */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val popup = WebView(view.context)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (Hosts.staysInside(uri)) web.loadUrl(uri.toString()) else openExternal(uri)
                    v.destroy()
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) Log.d(TAG, "${message.sourceId()}:${message.lineNumber()} ${message.message()}")
            return true
        }
    }

    companion object {
        private const val TAG = "YekariShell"
        private const val SPLASH_MAX_MS = 4000L
        private const val OFFLINE_PAGE = "file:///android_asset/offline.html"
        private const val BACK_JS =
            "(function(){try{return !!(window.__yekariBack&&window.__yekariBack())}catch(e){return false}})()"

        /** برای سرویس پیک: اعلان پیشنهاد فقط وقتی اپ جلوی چشم نیست */
        @Volatile
        var inForeground = false
            private set
    }
}
