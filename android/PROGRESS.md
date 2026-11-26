# Leash for Android — progress

## Phase 1a: Scaffold — done

### What exists
- Gradle project in `android/` (Kotlin DSL, version catalog, wrapper 8.14.8 with verified jar/dist sha256), module `:app`,
  app id/namespace `dev.leash.personal`, name "Leash", minSdk 29, targetSdk 35, **compileSdk 36**.
- Single `MainActivity` (edge-to-edge) hosting Compose `LeashRoot` (empty shell: space gradient + "Leash / Not connected").
- `LeashApplication` (empty; will own AppModel in 1b).
- Theme: `ui/theme/LeashPalette.kt` (port of `ios/SharedDesign/LeashPalette.swift`, Compose-free) and `Theme.kt`
  (Material 3 light/dark ColorScheme from the palette, `LeashTheme.colors` for roles Material lacks, `LeashRadius`, Shapes).
  Light/dark follows the system; no dynamic colour. Window theme (`Theme.Leash`, day/night) paints the bg colour to avoid a flash.
- Manifest: INTERNET + ACCESS_NETWORK_STATE, cleartext allowed via `res/xml/network_security_config.xml`, no backup
  (`allowBackup=false` + data extraction rules), predictive back enabled, `leash://pair` and `leash://session` VIEW filters.
- Placeholder adaptive launcher icon (vector ring + lead); replace later if wanted.
- Unit tests: `LeashPaletteTest` (3 tests).

### Toolchain findings (important for later phases)
- Windows SDK has platform 36 (compileSdk 36 builds). It does NOT have 37.
- Versions in use: AGP 8.13.2, Gradle 8.14.8, Kotlin 2.3.21, Compose BOM **2025.12.01** (Material3 1.4.x), core-ktx 1.17.0,
  activity-compose 1.11.0, lifecycle 2.9.4, navigation-compose 2.9.5.
- **Do not bump to the newest BOM (2026.11.01) or core-ktx 1.19**: they require AGP 9.1+ and compileSdk 37. Stay on AGP 8.13.x / SDK 36.
- Applying `org.jetbrains.kotlin.android` is required with AGP 8.x (the `kotlin {}` block needs it).
- First build on the host downloads Gradle + dependencies (~7 min); incremental builds ~45 s; unit tests ~35 s.
- Emulator: 1080x1920, API 35 (confirmed via getprop).
- Commit trailer used: `Co-Authored-By: Claude Sonnet 5.5` (the executing model; PLAN.md names Opus 5.5).

### Verified (emulator)
- `android_build install=true launch=dev.leash.personal/.MainActivity`: BUILD SUCCESSFUL, installed, launched.
- `android_test mode=unit`: 3 tests, 0 failures.
- logcat: no AndroidRuntime/FATAL entries; Displayed MainActivity.
- Screenshot (light mode) shows the title and status text, status bar insets respected.
  Dark mode was not screenshotted.

### Known issues / left
- Dark mode not visually checked on the emulator.
- Nothing from phase 1b onwards exists (no kotlinx.serialization / OkHttp / DataStore dependencies yet; add them in 1b).
- No real pairing link was provided.

## Next: phase 1b (Core port)
