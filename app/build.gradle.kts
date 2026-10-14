plugins {
    alias(libs.plugins.android.application)
    // AGP 9 built-in Kotlin: `org.jetbrains.kotlin.android` is deliberately NOT applied here.
    alias(libs.plugins.compose.compiler)
}

// Map picker configuration, resolved at build time into `BuildConfig` so no URL or key is written into
// the source. The defaults are key-free public services (OpenFreeMap for the map style, OSM Nominatim for
// reverse geocoding); each can be overridden with a Gradle property — for example
// `-Psalah.map.styleUrl=https://…` or `salah.geocoder.baseUrl=…` in `gradle.properties` — so a release
// can point at a self-hosted or paid provider without a code change. See docs/MAP_PICKER.md.
val mapStyleUrl: String = providers.gradleProperty("salah.map.styleUrl")
    .getOrElse("https://tiles.openfreemap.org/styles/liberty")
val mapDarkStyleUrl: String = providers.gradleProperty("salah.map.darkStyleUrl")
    .getOrElse("https://tiles.openfreemap.org/styles/dark")
val geocoderBaseUrl: String = providers.gradleProperty("salah.geocoder.baseUrl")
    .getOrElse("https://nominatim.openstreetmap.org")
// The reverse-geocoding provider's usage policy requires the app to identify itself with a real
// User-Agent, so this is a build-time constant rather than a default HTTP client's.
val geocoderUserAgent: String = providers.gradleProperty("salah.geocoder.userAgent")
    .getOrElse("Salah/0.1.0 (https://github.com/towkirahmedrony/prayer)")

android {
    namespace = "io.github.towkirahmedrony.salah"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // API 37 is a minor-versioned platform (`platforms;android-37.0`); without this AGP looks
    // for `platforms;android-37`, which Google no longer publishes.
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        applicationId = "io.github.towkirahmedrony.salah"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // The map style the picker loads, the geocoder it queries, and the User-Agent it identifies
        // itself with. Values, never secrets: the defaults need no key, and an override for a keyed
        // provider is expected to carry the key in the URL, supplied by the build rather than committed.
        buildConfigField("String", "MAP_STYLE_URL", "\"$mapStyleUrl\"")
        buildConfigField("String", "MAP_DARK_STYLE_URL", "\"$mapDarkStyleUrl\"")
        buildConfigField("String", "GEOCODER_BASE_URL", "\"$geocoderBaseUrl\"")
        buildConfigField("String", "GEOCODER_USER_AGENT", "\"$geocoderUserAgent\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Local testing only: signed with the debug key so the APK installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Lint failures fail the CI job; warnings are reported but do not block this phase.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            // `HomeViewModel` records start-up timing through `StartupTrace`, which reads
            // `android.os.Process`, `android.os.SystemClock` and `android.util.Log`. Those are
            // stubs in a JVM unit test and throw `Method ... not mocked` by default, which would
            // make the view model untestable outside an emulator. Returning the default value
            // instead makes the trace inert (a `0` elapsed time and a no-op log line) and lets the
            // flow assembly be tested on the JVM.
            //
            // Every other test in this source set is pure JVM code — none of them imports
            // `android.*` — so no existing test relied on a stub throwing, and this cannot mask a
            // real "not mocked" failure anywhere else.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Installs the baseline profiles that Compose and Material3 ship, so first frames are AOT-compiled.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Local astronomical prayer-time engine (Adhan Kotlin). Runs on-device, offline.
    implementation(libs.adhan.kotlin)

    // Map rendering for the location picker. The SDK is a View-based map wrapped in `AndroidView`; the
    // selection, validation and persistence built on it are pure Kotlin and unit-tested without it.
    implementation(libs.maplibre.android.sdk)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
