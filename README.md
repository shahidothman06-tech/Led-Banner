# LED Banner (Android)

Package: `Tubers.LedBanner.LedLight` · Kotlin · minSdk 26 · targetSdk 36 · Play Billing 9.1

## Project layout
```
app/src/main/
  java/Tubers/LedBanner/LedLight/
    MainActivity.kt   – WebView host, immersive landscape show mode, keep-screen-on, max brightness,
                        haptics, share sheet, Play Store links, back handling
    Billing.kt        – Google Play Billing: one-time product "pro_lifetime"
  assets/www/
    index.html        – the whole UI: templates, editor, LED renderer, library, settings, paywall
    fonts.css + fonts/ – Rubik + Cairo bundled (works 100% offline)
  res/                – launcher icon (adaptive + themed), splash, theme
```
The UI talks to Kotlin through `window.Android` (see `MainActivity.Bridge`), and Kotlin answers through `window.onNative(event, value)`.

## Build
1. Open the `LedBanner` folder in Android Studio and let Gradle sync. Gradle 8.14.3 and AGP 8.13 download automatically.
2. Run on a device: ▶ Run.
3. Release: **Build → Generate Signed App Bundle → Android App Bundle**, then create or choose your upload keystore. Keep the keystore safe; you need it for every update.
4. Upload `app/release/app-release.aab` to Play Console.

> If Android Studio offers to upgrade to AGP 9, Kotlin is built in there: remove the
> `org.jetbrains.kotlin.android` plugin lines from both build files, or say "no" to the upgrade for now.

## Google Play Billing (PRO)
- Play Console → Monetize with Play → Products → **One-time products** → Create product
  - Product ID: `pro_lifetime` (must match `Billing.PRO_ID`)
  - Name: LED Banner PRO · Price: USD 4.99 (let Play set local prices)
  - Activate it.
- Billing only works for builds installed from Play (internal testing track is fine).
- Add your Gmail as a **License tester** (Settings → License testing) to buy with test cards.
- Purchases are acknowledged automatically, and refunds remove PRO on the next app start.

## Updating content
Templates, translations and prices shown before Play loads live in `assets/www/index.html`
(search for `const T=[` for templates and `const I18N=` for UI text). Bump `versionCode` and `versionName` in
`app/build.gradle.kts` for every release.
