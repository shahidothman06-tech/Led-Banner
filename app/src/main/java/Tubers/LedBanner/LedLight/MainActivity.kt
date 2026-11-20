package Tubers.LedBanner.LedLight

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * LED Banner — single-activity app.
 * The UI (templates, editor, LED renderer) lives in assets/www and runs in a WebView.
 * This activity provides the native parts: immersive landscape "show" mode, keep-screen-on,
 * max brightness, haptics, share sheet, Play Store links and Google Play Billing (PRO).
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var billing: Billing
    private var showMode = false

    companion object {
        private const val APP_URL = "https://appassets.androidplatform.net/assets/www/index.html"
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val BG = 0xFF07070B.toInt()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge with light (white) system bar icons on our dark UI.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController().apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        web = WebView(this).apply { setBackgroundColor(BG) }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Keep the UI clear of status/navigation bars and the keyboard (except in show mode).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            if (showMode) {
                v.setPadding(0, 0, 0, 0)
            } else {
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            }
            WindowInsetsCompat.CONSUMED
        }

        // Serve bundled files from a secure https origin (localStorage works, no file:// access).
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
        }
        web.isVerticalScrollBarEnabled = false
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == APP_HOST) return false
                openExternal(request.url)
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")

        // Back: let the web app close sheets / go back a screen; exit when there is nothing left.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        billing = Billing(this) { event, value -> sendToWeb(event, value) }
        billing.start()

        if (savedInstanceState == null) web.loadUrl(APP_URL) else web.restoreState(savedInstanceState)
    }

    // ---------------- show mode ----------------
    private fun setShowMode(on: Boolean) {
        showMode = on
        val c = insetsController()
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = 1f }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun insetsController() = WindowCompat.getInsetsController(window, window.decorView)

    // ---------------- helpers ----------------
    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun openStoreListing() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (_: ActivityNotFoundException) {
            openExternal(Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        }
    }

    /** Calls window.onNative(event, value) in the web app. */
    private fun sendToWeb(event: String, value: Any?) {
        val js = "window.onNative&&window.onNative(${JSONObject.quote(event)},${toJs(value)})"
        runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) }
    }

    private fun toJs(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        else -> JSONObject.quote(v.toString())
    }

    // ---------------- lifecycle ----------------
    override fun onResume() {
        super.onResume()
        web.onResume()
        if (showMode) setShowMode(true) // re-hide bars after returning to the app
        billing.refresh()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        billing.end()
        web.destroy()
        super.onDestroy()
    }

    // ---------------- JavaScript bridge (window.Android) ----------------
    inner class Bridge {
        @JavascriptInterface fun isPro(): Boolean = billing.isPro()
        @JavascriptInterface fun proPrice(): String = billing.price
        @JavascriptInterface fun buyPro() = runOnUiThread { billing.buy(this@MainActivity) }
        @JavascriptInterface fun restore() = runOnUiThread { billing.restore() }
        @JavascriptInterface fun show(on: Boolean) = runOnUiThread { setShowMode(on) }
        @JavascriptInterface fun haptic() = runOnUiThread { web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        @JavascriptInterface fun rate() = runOnUiThread { openStoreListing() }
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun share(text: String) = runOnUiThread {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, null))
        }

        @JavascriptInterface
        fun open(url: String) = runOnUiThread {
            val u = Uri.parse(url)
            if (u.scheme == "https") openExternal(u)
        }
    }
}
