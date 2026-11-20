import com.android.build.api.artifact.SingleArtifact
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional: sign release builds from the command line too.
// Create keystore.properties in the project root (it is git-ignored), see README.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "Tubers.LedBanner.LedLight"
    compileSdk = 36

    defaultConfig {
        applicationId = "Tubers.LedBanner.LedLight"
        minSdk = 26          // Android 8.0+
        targetSdk = 36       // required by Google Play since Aug 31, 2026
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystorePropsFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.android.billingclient:billing-ktx:9.1.0")
}

// ---------------------------------------------------------------------------
// Copy every build to the Desktop
//   assembleDebug  (Build > Build APK(s))          -> Desktop/LedBanner-<version>-debug.apk
//   bundleRelease  (Generate Signed App Bundle)    -> Desktop/LedBanner-<version>-release.aab
//   ./gradlew buildToDesktop                       -> both at once
// To use another folder, add  desktopDir=D:/Builds  to ~/.gradle/gradle.properties.
// ---------------------------------------------------------------------------

/** Copies a finished APK or AAB to the user's Desktop. */
abstract class CopyToDesktop : DefaultTask() {
    /** The APK output folder or the .aab file that AGP produced. */
    @get:InputFiles
    abstract val builtOutput: ConfigurableFileCollection

    @get:Input
    abstract val fileName: Property<String>

    /** Optional override from the `desktopDir` Gradle property. */
    @get:Input
    @get:Optional
    abstract val desktopDir: Property<String>

    /** True for Android Studio's ▶ Run builds, which are marked test-only. */
    @get:Input
    abstract val testOnlyBuild: Property<Boolean>

    @TaskAction
    fun copyToDesktop() {
        if (testOnlyBuild.get()) {
            logger.lifecycle(
                "Desktop copy skipped: this Run build is test-only and won't install from a file. " +
                    "Use Build > Build APK(s) or the buildToDesktop task for a shareable APK."
            )
            return
        }
        val source = builtOutput.asFileTree.files.firstOrNull { it.extension == "apk" || it.extension == "aab" }
            ?: throw GradleException("No APK or AAB found in ${builtOutput.files}")
        val dir = desktopDir.orNull?.takeIf { it.isNotBlank() }?.let(::File) ?: findDesktop()
        dir.mkdirs()
        val target = File(dir, fileName.get())
        source.copyTo(target, overwrite = true)
        logger.lifecycle("Copied ${source.name} to ${target.absolutePath}")
        if (target.extension == "aab" && !isSigned(target)) {
            logger.warn(
                "WARNING: ${target.name} is not signed, so Play Console will reject it. " +
                    "Use Build > Generate Signed App Bundle, or add keystore.properties (see README)."
            )
        }
    }

    private fun findDesktop(): File {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            // Ask Windows where the Desktop really is: OneDrive often moves it.
            val fromRegistry = runCatching {
                val process = ProcessBuilder(
                    "reg", "query",
                    """HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders""",
                    "/v", "Desktop",
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                Regex("""REG_(?:EXPAND_)?SZ\s+(.+)""").find(output)?.groupValues?.get(1)?.trim()
                    ?.let { path -> Regex("%([^%]+)%").replace(path) { System.getenv(it.groupValues[1]) ?: it.value } }
                    ?.let(::File)
                    ?.takeIf { it.isDirectory }
            }.getOrNull()
            if (fromRegistry != null) return fromRegistry
        }
        return File(System.getProperty("user.home"), "Desktop")
    }

    private fun isSigned(file: File): Boolean = ZipFile(file).use { zip ->
        zip.entries().asSequence().any { it.name.startsWith("META-INF/") && it.name.endsWith(".SF") }
    }
}

val desktopDirOverride = providers.gradleProperty("desktopDir")
val isTestOnlyBuild = providers.gradleProperty("android.injected.testOnly").map { it.toBoolean() }.orElse(false)

androidComponents {
    onVariants { variant ->
        val versionName = variant.outputs.first().versionName
        when (variant.name) {
            "debug" -> tasks.register<CopyToDesktop>("copyDebugApkToDesktop") {
                group = "desktop"
                description = "Copies the debug (test) APK to the Desktop."
                builtOutput.from(variant.artifacts.get(SingleArtifact.APK))
                fileName.set(versionName.map { "LedBanner-$it-debug.apk" })
                desktopDir.set(desktopDirOverride)
                testOnlyBuild.set(isTestOnlyBuild)
            }
            "release" -> tasks.register<CopyToDesktop>("copyReleaseAabToDesktop") {
                group = "desktop"
                description = "Copies the release .aab (for Google Play) to the Desktop."
                builtOutput.from(variant.artifacts.get(SingleArtifact.BUNDLE))
                fileName.set(versionName.map { "LedBanner-$it-release.aab" })
                desktopDir.set(desktopDirOverride)
                testOnlyBuild.set(false)
            }
        }
    }
}

// Run the copy automatically after every normal build.
tasks.configureEach {
    when (name) {
        "assembleDebug" -> finalizedBy("copyDebugApkToDesktop")
        "bundleRelease" -> finalizedBy("copyReleaseAabToDesktop")
    }
}

tasks.register("buildToDesktop") {
    group = "desktop"
    description = "Builds the test APK and the Play .aab and copies both to the Desktop."
    dependsOn("copyDebugApkToDesktop", "copyReleaseAabToDesktop")
}
