# Keep the JavaScript bridge methods called from assets/www/index.html
-keepattributes JavascriptInterface
-keepclassmembers class Tubers.LedBanner.LedLight.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
