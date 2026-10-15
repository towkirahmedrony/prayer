plugins {
    alias(libs.plugins.android.application)
    // AGP 9 built-in Kotlin: `org.jetbrains.kotlin.android` is deliberately NOT applied here.
    alias(libs.plugins.compose.compiler)
}

// Remote dataset publication configuration.
//
// These two values are NON-SECRET by design: the Supabase project URL and the publishable/anon client
// key. Both are meant to be embedded in a client, and both are constrained by the row-level security the
// schema defines. A privileged key (service_role, secret, or the database password) must NEVER be placed
// here: it would bypass every policy and hand a client the ability to publish datasets.
//
// They are read from Gradle properties so the repository ships no credentials and the default build is the
// supported offline configuration: with neither property set both values are empty, the app reports "no
// remote dataset configured" and runs entirely on its bundled data. Supply them for a real build with
// `-Psalah.supabase.url=... -Psalah.supabase.anonKey=...`, an uncommitted `local.properties`, or the CI
// secret mechanism. See docs/SUPABASE_SETUP.md.
val salahSupabaseUrl: String = (findProperty("salah.supabase.url") as String?)?.trim().orEmpty()
val salahSupabaseAnonKey: String = (findProperty("salah.supabase.anonKey") as String?)?.trim().orEmpty()

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

        // Compiled into BuildConfig. Empty when unconfigured, which the app treats as "no remote
        // dataset" rather than as an error; see DatasetRemoteConfig.isConfigured.
        buildConfigField(
            "String",
            "SALAH_SUPABASE_URL",
            "\"${salahSupabaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
        buildConfigField(
            "String",
            "SALAH_SUPABASE_ANON_KEY",
            "\"${salahSupabaseAnonKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
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

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
