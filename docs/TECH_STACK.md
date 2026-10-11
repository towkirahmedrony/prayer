# Salah — Technology Stack

This document describes the toolchain **as it is configured in this repository**, not a plan.
Every version below appears in `gradle/libs.versions.toml`, the Gradle scripts, or the wrapper
configuration.

## 1. Configured toolchain

| Component | Configured value | Where it is declared |
|---|---|---|
| Kotlin | `2.4.21` | `build.gradle.kts` (buildscript classpath) and `gradle/libs.versions.toml` (`kotlin`) |
| Android Gradle Plugin | `9.3.3` | `gradle/libs.versions.toml` (`agp`) |
| Gradle wrapper | `9.8.1` | `gradle/wrapper/gradle-wrapper.properties` |
| JDK | `17` | `app/build.gradle.kts` (`compileOptions`), `.github/workflows/android-apk.yml` (Temurin 17) |
| Compose compiler plugin | `2.4.21` (`org.jetbrains.kotlin.plugin.compose`) | `gradle/libs.versions.toml` (`compose-compiler`) |
| Jetpack Compose BOM | `2026.09.00` | `gradle/libs.versions.toml` (`composeBom`) |
| compileSdk | `37` with minor version `0` (`android-37.0`) — baseline was `36`; both changes come from real remote build output, see §5.1 and §5.2 | `gradle/libs.versions.toml`, `app/build.gradle.kts` (`compileSdkMinor`) |
| targetSdk | `36` | `gradle/libs.versions.toml` |
| minSdk | `26` | `gradle/libs.versions.toml` |

Versions resolved from the Compose BOM `2026.09.00` (read from its published POM):

| Library | Version |
|---|---|
| `androidx.compose.ui` / `ui-graphics` / `foundation` / `runtime` | `1.12.1` |
| `androidx.compose.material3:material3` | `1.4.0` |

Pinned Android and Kotlin libraries (not covered by the BOM):

| Library | Version |
|---|---|
| `androidx.core:core-ktx` | `1.19.1` |
| `androidx.activity:activity-compose` | `1.13.0` |
| `androidx.lifecycle:lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` | `2.11.0` |
| `androidx.navigation:navigation-compose` | `2.10.2` |
| `androidx.datastore:datastore-preferences` | `1.2.1` |
| `io.insert-koin:koin-android`, `koin-androidx-compose` | `4.2.2` |
| `org.jetbrains.kotlinx:kotlinx-coroutines(-android/-test)` | `1.11.0` |
| `junit:junit` | `4.13.2` |

Verification performed when this configuration was written (all stable releases, no
`1.+`-style dynamic versions, no alpha/beta/RC/EAP):

- `com.android.tools.build:gradle:9.3.3` — published on Google Maven.
- `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21` — published on Maven Central.
- `androidx.compose:compose-bom:2026.09.00` — published on Google Maven (latest stable).
- `org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21` — published on Maven Central.
- Gradle `9.8.1` distribution resolves, official `sha256`
  `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`, pinned in
  `gradle/wrapper/gradle-wrapper.properties` as `distributionSha256Sum`.

## 2. Official references used for these decisions

Supplied baseline references (not re-researched during implementation):

- Kotlin releases — https://kotlinlang.org/docs/releases.html
- AGP 9.3 release notes — https://developer.android.com/build/releases/agp-9-3-0-release-notes
- Gradle 9.8.1 release notes — https://docs.gradle.org/9.8.1/release-notes.html
- Jetpack Compose releases — https://developer.android.com/jetpack/androidx/releases/compose
- Compose BOM guidance — https://developer.android.com/develop/ui/compose/bom
- Android JDK guidance — https://developer.android.com/build/jdks
- Google Play target SDK requirements — https://developer.android.com/google/play/requirements/target-sdk
- Android architecture guidance — https://developer.android.com/topic/architecture

Additional references consulted only to resolve the AGP 9 Kotlin question (this repository had
no pre-existing Android project to copy):

- Migrate to AGP built-in Kotlin — https://developer.android.com/build/migrate-to-built-in-kotlin
- AGP 9.0 release notes — https://developer.android.com/build/releases/agp-9-0-0-release-notes
- Compose compiler Gradle plugin — https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler

## 3. Compatibility decisions

### 3.1 AGP 9 built-in Kotlin — `org.jetbrains.kotlin.android` is not applied

AGP 9 enables built-in Kotlin. Applying `org.jetbrains.kotlin.android` alongside it conflicts
with the new DSL. Therefore:

```kotlin
// root build.gradle.kts
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }
}
plugins {
    alias(libs.plugins.android.application) apply false   // com.android.application 9.3.3
    alias(libs.plugins.compose.compiler) apply false      // org.jetbrains.kotlin.plugin.compose 2.4.21
}

// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
```

Putting the Kotlin Gradle Plugin on the root buildscript classpath is how AGP's built-in Kotlin
selects a Kotlin version newer than the one bundled with AGP, which keeps the Kotlin version
deterministic. The literal `2.4.21` in the buildscript must stay in sync with `kotlin` in
`gradle/libs.versions.toml` — version-catalog accessors are not available inside `buildscript {}`.

### 3.2 Compose compiler plugin version follows Kotlin, not the BOM

`org.jetbrains.kotlin.plugin.compose` is pinned to `2.4.21`, the Kotlin version. The Compose BOM
aligns Compose *libraries* only.

### 3.3 JVM target

`compileOptions.sourceCompatibility/targetCompatibility` are `17`. With AGP built-in Kotlin the
Kotlin JVM target follows `compileOptions`, so no separate `kotlin { compilerOptions { } }` block
is required. CI runs Temurin JDK 17, matching AGP 9.3's documented JDK baseline.

### 3.4 Version-range caveat (declared, not hidden)

The Kotlin 2.4.21 compatibility table lists fully supported Gradle versions up to `9.7.0` and AGP
versions up to `9.3.1`, while this project pins Gradle `9.8.1` and AGP `9.3.3` per the project
baseline. AGP 9.3 requires Gradle ≥ 9.5 and JDK 17, and Gradle 9.8 lies inside the AGP 9.3
range — so the combination is consistent from the Gradle/AGP side and newer than Kotlin's
published "tested up to" markers.

**Resolution rule for this repository:** keep the baseline, and let the GitHub Actions build be
the authority. If the remote build reports a real incompatibility, the smallest justified
adjustment (for example AGP `9.3.1` or Gradle `9.7.0`) will be made and documented here with the
exact error output. Compatibility has not been *claimed* as proven by inspection.

### 3.5 minSdk 26

`26` (Android 8.0) gives `java.time` without desugaring, adaptive-icon resources without legacy
PNG fallbacks, and satisfies the newer floors of the libraries in use (Navigation 2.10 requires
24, Koin 4 requires 23).

### 3.6 Dependency injection: Koin, not Hilt

Koin's DSL needs no annotation processor, so the project avoids a KSP/Kotlin-version coupling
that would otherwise have to be tracked on every Kotlin release. Bindings are declared once in
`di/SalahModules.kt` and always against domain interfaces.

### 3.7 Room deliberately deferred

Room requires KSP, and this phase persists no entity: preferences and the manual location are
stored with DataStore (no code generation), and the schedule cache is in-memory. Adding a
database now would be a dependency without a consumer. Room is expected in the same change that
introduces the first persisted schedule entity.

### 3.8 No networking stack yet

No HTTP client (Retrofit or Ktor) is declared. The first phase performs no network call at all,
and offline-first means local state is the read source. A client is introduced with the first
remote provider, together with its validation and caching rules.

### 3.9 Prayer calculation engine: boundary only

`PrayerTimesProvider` is the port, `UnconfiguredLocalPrayerTimesProvider` is the wired
implementation, and it reports `NOT_CONFIGURED`. The Adhan Kotlin library (candidate:
`adhan2:0.0.7`) is *not* integrated in this phase, so no version is claimed as verified here and
no prayer time is displayed. See `docs/ARCHITECTURE.md` for the integration criteria.

### 3.10 Build and release configuration

- Debug APK only; release signing and shrinking are not configured and no keystore material
  exists in the repository.
- `buildFeatures.buildConfig = true` so the Settings screen can show the version.
- Lint: `abortOnError = true`, `warningsAsErrors = false`, `checkReleaseBuilds = false`.
- Gradle caching and configuration parallelism are enabled in `gradle.properties`.

## 4. GitHub Actions toolchain

Workflow: `.github/workflows/android-apk.yml`

| Step | Action / command |
|---|---|
| Checkout | `actions/checkout` pinned to `3d3c42e5aac5ba805825da76410c181273ba90b1` (v7.0.1) |
| JDK | `actions/setup-java` pinned to `de7274f081f381c8f8158605e0321c36c376e2e6` (v6.0.1), Temurin 17 |
| Gradle + cache | `gradle/actions/setup-gradle` pinned to `3f5f9adaf7d9fecd50b5935e54106014257a94e6` (v6.4.0) |
| Android SDK | `sdkmanager` from the runner image: `platform-tools`, `platforms;android-37.0`, `build-tools;36.0.0`, `build-tools;37.0.0` (retried with `--channel=3` if the image's package index is stale) |
| Wrapper validation | `./gradlew --version` must report `Gradle 9.8.1` |
| Tests | `./gradlew testDebugUnitTest` |
| Lint | `./gradlew lintDebug` |
| Build | `./gradlew assembleDebug` |
| APK check | file exists and size > 0 |
| Artifact | `actions/upload-artifact` pinned to `cf430e030ddbb5b0abf93d22962f4752f3646cd9` (v7.0.2), name `salah-debug-apk`, retention 14 days |

Third-party actions are pinned to immutable commit SHAs with the corresponding release tag
recorded in a trailing comment. Job permissions are `contents: read` only. No secrets are used
by the workflow, and the debug build requires no signing credentials.

## 5. Adjustments made from real build output

### 5.1 compileSdk raised from 36 to 37

The baseline specified `compileSdk`/`targetSdk` 36. The first remote build failed in
`checkDebugAarMetadata` before any test ran. Abridged from the Actions log for run
`38130939759`:

```
Dependency 'androidx.compose.ui:ui-android:1.12.1' requires libraries and applications that
  depend on it to compile against version 37 or later of the Android APIs.
  :app is currently compiled against android-36.
  Recommended action: Update this project to use a newer compileSdk of at least 37
  … 16. Dependency 'androidx.core:core-ktx:1.19.1' requires … version 37 or later …
at com.android.build.gradle.internal.tasks.CheckAarMetadataWorkAction.execute(CheckAarMetadataTask.kt:251)
BUILD FAILED in 1m 9s
```

The same message appeared for `androidx.compose.runtime:runtime-saveable-android:1.12.1`,
`androidx.compose.ui:ui-android:1.12.1`,
`androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0`,
`androidx.lifecycle:lifecycle-viewmodel-compose-android:2.11.0` and `androidx.core:core-ktx:1.19.1`
(16 dependencies in total) — i.e. the current Compose BOM and AndroidX releases simply target
API 37.

**Adjustment:** `compileSdk` → `37`. `targetSdk` stays `36` (the Play target-SDK requirement and
the runtime behaviour of the baseline) and `minSdk` stays `26`. This keeps every library version
from the baseline; the alternative would be downgrading the Compose BOM, lifecycle and core to
API-36-era releases, which contradicts the specified BOM and moves several libraries at once.

**CI impact:** the workflow installs `platforms;android-37` and `build-tools;37.0.0` in addition
to `build-tools;36.0.0`; both package identifiers were verified against Google's SDK
repository index before the change.

### 5.2 The platform package is `platforms;android-37.0`, not `platforms;android-37`

Run `38131358330` then failed in the SDK install step — the package name used in §5.1 does not
exist in Google's index:

```
Warning: Failed to find package 'platforms;android-37'
##[error]Process completed with exit code 1.
```

Android now publishes **minor API versions**, so API 37 (Android 17) is shipped as
`platforms;android-37.0` / `platforms;android-37.1` / `platforms;android-37.2`, and a bare
`platforms;android-37` is no longer published (`android-36.1` similarly exists alongside
`android-36`). AGP therefore needs the minor version too: `compileSdk = 37` alone resolves to the
target hash `android-37`, which cannot be installed. The fix uses AGP's documented
`compileSdkMinor` DSL:

```kotlin
// app/build.gradle.kts
compileSdk = 37        // libs.versions.compileSdk
compileSdkMinor = 0    // libs.versions.compileSdkMinor -> platforms;android-37.0
```

Verification performed for this adjustment (no local Android SDK in the workspace, so the
configuration was checked against published artifacts rather than guessed):

- `com.android.tools.build:gradle-api:9.3.3` — `CommonExtension` in the published jar exposes
  `compileSdkMinor` (`getCompileSdkMinor`/`setCompileSdkMinor`), so the property exists in the
  AGP version this project pins, not only in newer docs.
- AGP 9.3 release notes — "The maximum API level that Android Gradle plugin 9.3 supports is API
  level 37", so compileSdk 37 is inside the supported range for AGP `9.3.3`.
- Google's SDK repository index — `platforms;android-37.0`, `platforms;android-37.1`,
  `build-tools;37.0.0` are listed on the stable channel.

If a future AGP release changes the target-hash scheme, the declared minor version is the single
place to adjust; `targetSdk` and `minSdk` are unaffected.
