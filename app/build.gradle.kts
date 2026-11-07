plugins {
    alias(libs.plugins.android.application)
    // AGP 9 built-in Kotlin: `org.jetbrains.kotlin.android` is deliberately NOT applied here.
    alias(libs.plugins.compose.compiler)
}

// Salah's Supabase client configuration.
//
// These two values are NON-SECRET by design: the Supabase project URL and the publishable/anon client key.
// Both are meant to be embedded in a client, and both are constrained by the row-level security the schema
// defines. A privileged key (service_role, secret, or the database password) must NEVER be placed here: it
// would bypass every policy and hand a client the ability to publish datasets, prayer configurations or
// reference prices. See docs/SUPABASE_SETUP.md.
//
// The default below is the Salah project itself, so a plain `./gradlew assembleDebug` — locally or on a
// GitHub Actions runner — produces a build that is configured for the same project. The two properties
// remain the supported override, and the two environment variables are the mapping for a build environment
// that supplies configuration through its environment instead of the command line:
//
//   1. Gradle property  `salah.supabase.url` / `salah.supabase.anonKey`
//                       (`-P...`, an uncommitted `local.properties`, or `~/.gradle/gradle.properties`)
//   2. Environment      `SALAH_SUPABASE_URL` / `SALAH_SUPABASE_ANON_KEY`
//   3. The default below
//
// The environment names are Android-native on purpose. The web/admin panel's `NEXT_PUBLIC_SUPABASE_URL`
// and `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY` hold the same public pair, but they are deliberately NOT read
// straight into the Android build: the values are mapped onto the two names above, so a Next.js variable
// can never be consumed here as if it were an Android one. An empty or whitespace-only override falls back
// to the next source, so a blank value can never produce a request to an invalid endpoint.
val salahSupabaseDefaultUrl: String = "https://aagyqdiwsokebmgexazj.supabase.co"
val salahSupabaseDefaultAnonKey: String = "sb_publishable_w74K1UEYg5XirDjV_rLj7g_lY6VYOqh"

val salahSupabaseUrl: String =
    (findProperty("salah.supabase.url") as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable("SALAH_SUPABASE_URL").getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: salahSupabaseDefaultUrl

val salahSupabaseAnonKey: String =
    (findProperty("salah.supabase.anonKey") as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable("SALAH_SUPABASE_ANON_KEY").getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: salahSupabaseDefaultAnonKey

// Geoapify reverse-geocoding API key — optional and secret. Resolved at build time and
// never hardcoded in Kotlin source or committed to version control: supplied via the
// uncommitted root `local.properties` (`GEOAPIFY_API_KEY=...`, which is git-ignored), a
// Gradle property (`-PGEOAPIFY_API_KEY=...` or `~/.gradle/gradle.properties`), or the
// `GEOAPIFY_API_KEY` environment variable. A missing or blank key falls back to `""` so the
// build never fails on configuration alone; consumers must treat `""` as "not configured"
// and skip network use instead of crashing.
val geoapifyApiKey: String =
    (findProperty("GEOAPIFY_API_KEY") as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable("GEOAPIFY_API_KEY").getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: run<String?> {
            val localProperties = rootProject.file("local.properties")
            if (localProperties.exists()) {
                localProperties.readLines()
                    .map { it.trim() }
                    .firstOrNull { it.substringBefore("=").trim() == "GEOAPIFY_API_KEY" }
                    ?.substringAfter("=")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
        ?: ""

// Release signing credentials — production Release builds only.
//
// These four environment variables are supplied by the GitHub Actions Release
// workflow (.github/workflows/release-apk.yml), which decodes the
// KEYSTORE_BASE64 GitHub secret into a temporary keystore file and exports the
// result as KEYSTORE_FILE alongside the three password/alias secrets. No
// credential value ever appears in this file, in generated files, or in
// version control: the workflow masks secret values in logs, and the
// keystore file itself lives only under $RUNNER_TEMP and is deleted after
// the build.
//
// Debug builds never read these variables and keep the default AGP debug
// signing configuration, so `./gradlew assembleDebug` (locally or in CI)
// works with nothing configured. An `assemble`/`bundle` Release task
// requested without all four variables (i.e. `assembleRelease`) fails fast
// with an explicit message instead of silently falling back to the debug
// key — a debug-signed "release" APK would previously have been
// indistinguishable from a production-signed one until signature inspection.
// Unit tests (`testDebugUnitTest`) and lint (`lintDebug`) do not require
// signing and are deliberately exempt so CI can run them before the
// keystore is decoded. See docs/TECH_STACK.md §3.11.
val releaseKeyStoreFile: String? =
    providers.environmentVariable("KEYSTORE_FILE").getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
val releaseKeyStorePassword: String? =
    providers.environmentVariable("KEYSTORE_PASSWORD").getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
val releaseKeyAlias: String? =
    providers.environmentVariable("KEY_ALIAS").getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
val releaseKeyPassword: String? =
    providers.environmentVariable("KEY_PASSWORD").getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

val hasReleaseSigning: Boolean =
    releaseKeyStoreFile != null &&
        releaseKeyStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

// Only APK/AAB-producing Release tasks require signing. Unit tests
// (`testDebugUnitTest`) and lint (`lintDebug`) run before the keystore is
// decoded in CI and must not fail here.
if (
    gradle.startParameter.taskNames.any {
        it.contains("Release", ignoreCase = true) &&
            (it.startsWith("assemble", ignoreCase = true) || it.startsWith("bundle", ignoreCase = true))
    } && !hasReleaseSigning
) {
    error(
        "Release signing credentials are not set. The Release workflow provides " +
            "KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD as " +
            "environment variables; locally, use a debug build instead " +
            "(./gradlew assembleDebug).",
    )
}

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
        // Single source of truth for the app version. Bump versionCode (monotonic)
        // and versionName (semver) here, then tag the release as "v<versionName>"
        // (e.g. v0.1.0) so the GitHub Release and APK filename match this value.
        versionCode = 1
        versionName = "0.1.0"

        // Compiled into BuildConfig — the single source of truth for the endpoint and the public client
        // key. A blank value (only possible if every source above is blank) is treated as "no remote
        // publication configured": the app then runs entirely on its bundled data instead of failing, and
        // a malformed one is rejected by the same configuration gate. See DatasetRemoteConfig.isConfigured
        // and SupabaseConfiguration.
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
        // Optional Geoapify key (see `geoapifyApiKey` above). `""` means not configured: the
        // app must no-op instead of crashing when the feature needs a network call.
        buildConfigField(
            "String",
            "GEOAPIFY_API_KEY",
            "\"${geoapifyApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    signingConfigs {
        // The release signing configuration exists only when the workflow has
        // supplied all four credentials (see hasReleaseSigning above). There
        // is deliberately no debug-key fallback: a release build without
        // credentials fails fast in the check above, and a release build with
        // credentials is signed with the production key. The default `debug`
        // signing configuration is left untouched.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = project.file(releaseKeyStoreFile!!)
                storePassword = releaseKeyStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Shrinking stays enabled: the app uses no reflection-based or
            // annotation-generated entry points that would need keep rules
            // (Koin DSL modules, Compose, DataStore, coroutines and the Adhan
            // engine all ship their own consumer rules or are reached by
            // direct calls), and CI verifies the release APK signs and
            // assembles correctly. See app/proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
