import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Secrets & local configuration
// ---------------------------------------------------------------------------
// Values are read (in order of precedence) from:
//   1. Environment variables (used by GitHub Actions secrets)
//   2. local.properties (never committed, see local.properties.example)
//   3. Empty string (the app then shows an honest "provider not configured" state)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

fun secret(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: ""

// Report which provider credentials reached the build (names only, never values) so a
// misconfigured GitHub Secret is visible in the Actions log instead of only on the phone.
val providerConfigSummary = listOf("YOUTUBE_API_KEY", "SPOTIFY_CLIENT_ID", "SPOTIFY_CLIENT_SECRET")
    .joinToString(", ") { "$it=" + if (secret(it).isEmpty()) "missing" else "set" }
logger.lifecycle("BlazeMuzix provider configuration: $providerConfigSummary")

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val releaseKeystorePath = secret("KEYSTORE_FILE")
val hasReleaseKeystore = releaseKeystorePath.isNotEmpty() && file(releaseKeystorePath).exists()

android {
    namespace = "com.blazemuzix.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blazemuzix.app"
        // Android 4.4 (KitKat) is the lowest supported version. Every library below
        // has been selected so that it still supports API 19.
        minSdk = 19
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // Legacy multidex is required for API < 21 because the debug build easily
        // exceeds the 65k method limit.
        multiDexEnabled = true

        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "YOUTUBE_API_KEY", quoted(secret("YOUTUBE_API_KEY")))
        buildConfigField("String", "SPOTIFY_CLIENT_ID", quoted(secret("SPOTIFY_CLIENT_ID")))
        buildConfigField("String", "SPOTIFY_CLIENT_SECRET", quoted(secret("SPOTIFY_CLIENT_SECRET")))

        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // When no release keystore is provided (e.g. forks without secrets) the
            // release build falls back to the debug key so CI can still produce an
            // installable APK. See README "Release signing".
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("BlazeMuzix: no release keystore configured, release APK will be signed with the debug key.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Enables java.time & friends on API 19 through D8 desugaring.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += listOf("META-INF/*.kotlin_module", "kotlin/**", "META-INF/versions/**")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the real manifest + resources to launch activities on the JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // --- AndroidX (all versions below support minSdk 19 or lower) ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.multidex:multidex:2.0.1")

    // --- Material Components (1.12.0 is the last release supporting API 19) ---
    implementation("com.google.android.material:material:1.12.0")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Image loading with downsampling + disk cache (API 14+) ---
    implementation("com.github.bumptech.glide:glide:4.16.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation for JVM unit tests (the Android SDK stubs return null).
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Launches the real Application + MainActivity on the JVM to catch startup crashes in CI.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
