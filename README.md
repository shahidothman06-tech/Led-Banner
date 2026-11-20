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
4. Upload `app/release/app-release.aab` to Play Console (a copy also lands on your Desktop, see below).

### Builds on your Desktop
Every build copies its output to your Desktop automatically:

| You do | Desktop file |
|---|---|
| **Build → Build App Bundle(s) / APK(s) → Build APK(s)** | `LedBanner-test.apk`, the test APK you can install on any phone (overwritten each time) |
| **Build → Generate Signed App Bundle** (release) | `LedBanner-release-<versionName>.aab` for Play Console; the previous version's `.aab` goes to the Recycle Bin |
| Gradle panel → **app → Tasks → desktop → buildToDesktop**, or `./gradlew buildToDesktop` | both files at once |

- The ▶ Run button does **not** update the Desktop APK. Android Studio marks Run builds test-only, and phones refuse to install those from a file. Use **Build APK(s)** for an APK to share.
- The Build output shows the exact path of each copy. On Windows the real Desktop is used, including one moved to OneDrive.
- For a different folder, add `desktopDir=D:/Builds` to `~/.gradle/gradle.properties` (on Windows, `C:\Users\<you>\.gradle\gradle.properties`).

**Signing for `buildToDesktop`.** Generate Signed App Bundle signs the bundle for you. The `buildToDesktop` task signs only if you create `keystore.properties` in the project root. Git ignores that file, so it is never committed:
```properties
storeFile=keystore/ledbanner-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```
Keep the upload key in `keystore/ledbanner-upload.jks`, with its details in `keystore/info.txt`. Git ignores the
whole `keystore/` folder. Without a keystore the `.aab` is unsigned, which Play Console rejects, and the build prints a warning.

GitHub signs its `.aab` with the same key from two repository secrets (Settings → Secrets and variables → Actions):
`LEDBANNER_KEYSTORE_BASE64` (the `.jks` file as one line of base64) and `LEDBANNER_KEYSTORE_PASSWORD`.

### Builds from GitHub, straight to your Desktop
Every push to `main` makes GitHub build `LedBanner-test.apk` and `LedBanner-release-<versionName>.aab`
(`.github/workflows/build.yml`) and publish them on the **latest** release:
https://github.com/shahidothman06-tech/Led-Banner/releases/tag/latest

To have them land on your Desktop automatically, run `tools/desktop-sync.ps1` once on your PC
(right-click → **Run with PowerShell**). It downloads the newest files now, then checks every 15 minutes
in the background. Your own Android Studio build is kept whenever it is newer. To stop it:
```powershell
Unregister-ScheduledTask -TaskName 'LED Banner Desktop Sync' -Confirm:$false
```

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
