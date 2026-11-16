# NetAdvisor (Wi-Fi vs Mobile Data Speed Advisor)

A lightweight, battery-friendly Android app (Kotlin + Jetpack Compose + Material 3, `minSdk 29`, `targetSdk 35`) designed for Samsung Galaxy S25 Ultra and other Android 10+ phones. NetAdvisor quietly compares real-world internet speed and latency between Wi-Fi and Mobile Data when both are available and alerts you when switching to the non-active network is worthwhile.

## Implemented Features

- **Master On/Off Control (`DataStore` + Quick Settings Tile):**
  - Persists enabled state, check interval (default `3 min`), speed-advantage threshold (default `2.0x`), and last probe metrics in Jetpack Preferences `DataStore`.
  - Material 3 Compose dashboard with live Wi-Fi vs Mobile Data speed/latency cards, a **Test Now** button, a **Switch Network** button, and runtime permission prompts (`POST_NOTIFICATIONS` and optional `ACCESS_FINE_LOCATION` for mesh BSSID roaming).
  - Android Quick Settings Tile (`NetAdvisorTileService`) for 1-tap toggling from the notification shade.
- **Battery-Friendly Screen-Aware Service (`NetMonitorService`):**
  - Runs as a `ForegroundService` (`foregroundServiceType="specialUse"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declared for Android 14/15) only while enabled.
  - Listens for `ACTION_SCREEN_ON` and `ACTION_SCREEN_OFF` to completely pause periodic probing while the screen is off and resume when the screen turns on.
  - Triggers a debounced (3s) check when the active Wi-Fi BSSID or network handle changes.
- **Dual-Network Speed & Latency Probe (`DualNetworkProber`):**
  - Uses `ConnectivityManager.requestNetwork()` for `TRANSPORT_WIFI` and `TRANSPORT_CELLULAR` (with `NET_CAPABILITY_INTERNET`) in parallel without disconnecting Wi-Fi.
  - Runs a lightweight `generate_204` latency check and a ~200 KB timed HTTP download probe (`speed.cloudflare.com` with `proof.ovh.net` fallback) with short timeouts.
  - Always unregisters each `NetworkCallback` in a `finally` block immediately after probing so the cellular radio is released right away.
- **Smart Alerting with Hysteresis (`AlertEvaluator` & `NotificationHelper`):**
  - Alerts only when the non-active network is meaningfully better (`>= threshold * active` and `> 5 Mbps` ahead, or active `< 2 Mbps` while alternate `> 10 Mbps`) with a 10-minute cooldown.
  - Tapping the notification or its **Switch** action opens `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` (falling back to `Settings.ACTION_WIFI_SETTINGS`).
- **Samsung One UI Battery Guidance:**
  - Displays current `isIgnoringBatteryOptimizations` status and links straight to App Info / Battery settings with guidance to select **Unrestricted** on One UI if Android pauses background checks.

## Project Structure

- `app/src/main/java/com/netadvisor/app/domain/`: `Models.kt`, `AlertEvaluator.kt`
- `app/src/main/java/com/netadvisor/app/data/`: `AdvisorRepository.kt`
- `app/src/main/java/com/netadvisor/app/network/`: `DualNetworkProber.kt`
- `app/src/main/java/com/netadvisor/app/notification/`: `NotificationHelper.kt`
- `app/src/main/java/com/netadvisor/app/service/`: `NetMonitorService.kt`
- `app/src/main/java/com/netadvisor/app/tile/`: `NetAdvisorTileService.kt`
- `app/src/main/java/com/netadvisor/app/ui/`: `MainViewModel.kt`, `MainScreen.kt`, `theme/Theme.kt`
- `app/src/test/java/com/netadvisor/app/domain/`: `AlertEvaluatorTest.kt`

## Build & Run

1. Open `d:\Antigravity projects\wifi-switch` in **Android Studio** (Ladybug or newer recommended for AGP 8.7.3 / Kotlin 2.0.21 / SDK 35).
2. Let Android Studio sync Gradle and install/configure the Gradle wrapper (`gradle-wrapper.jar` is generated/managed by Android Studio or `gradle wrapper` when Gradle & JDK 17+ are installed).
3. Run unit tests:
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
4. Build and install on your Galaxy S25 Ultra (with USB debugging enabled):
   ```powershell
   .\gradlew.bat installDebug
   ```
