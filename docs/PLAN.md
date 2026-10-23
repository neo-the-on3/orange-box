# orange-box: Rewrite Plan (Kotlin + Jetpack Compose)

Status: **approved by Kiran on 2026-10-23**. The decisions below are final. Companion docs: `FEASIBILITY.md` (the original analysis) and `openapi.yaml` (legacy backend contract).
Approach: a **clean-room re-implementation** of CarooLive's OBD-II features. No Pokevian jars, `.so` files, keys, keystore passwords, trust-all SSL code, graphics or sounds are copied. Apache-2.0 attribution goes in `NOTICE`.

## 0. Final decisions

| Topic | Decision |
|---|---|
| Package / name / icon | `io.github.neotheon3.orangebox`, "Orange Box", new simple adaptive icon (vector) |
| minSdk / target | **minSdk 24** (Android Tester's API 25 armeabi-v7a emulator must install it), compile/target 36. Pure Kotlin, **no native libs of our own** (AndroidX ships tiny `libandroidx.graphics.path.so` / `libdatastore_shared_counter.so` for all ABIs incl. armeabi-v7a). Behaviours for API 26+ (notification channels, FGS start), 31+ (BLUETOOTH_CONNECT/SCAN, exact alarms), 33+ (POST_NOTIFICATIONS) and 34+ (FGS types) are version-gated |
| Dashcam | **Dropped entirely** (no camera, recording, video list/player, impact-triggered clips, YouTube) |
| Maps | Keyless: osmdroid or MapLibre (decided when the trip-detail milestone starts) |
| DI | Hilt |
| Sign-in | Out of v1 |
| Dropped from v1 | Floating overlay, call hang-up, silent crash call/SMS, push (GCM/FingerPush), social login, YouTube upload, server web pages |
| Crash prompt | Kept as a prompt that opens the **dialer / SMS app** with prefilled data (no CALL_PHONE / SEND_SMS permissions) |
| Rankings / points | Remote mode only. Stats are rebuilt locally from trips |
| Backend | Mode **Off** (default) / **Fake** / **Remote**. Debug builds accept the launch intent extra `backend_mode=off\|fake\|remote`. Release builds ignore it |
| Simulator | Debug-only Simulator screen (Section 6) |
| Local emulator | **Never start an x86/KVM emulator on the box** (host kernel BUG). Don't touch Android Tester's API 25 emulator. API 34 checks run in GitHub Actions only |
| Cloud agents | Not used |

## 1. Toolchain (pinned in `gradle/libs.versions.toml`)

| Tool | Version |
|---|---|
| Android Gradle Plugin | 9.4.1 (built-in Kotlin, so no `kotlin-android` plugin) |
| Gradle wrapper | 9.7.1 |
| Kotlin | 2.4.21 (+ compose & serialization compiler plugins) |
| KSP | 2.3.x (KSP2, independent of Kotlin version) for Hilt + Room |
| Compose BOM | 2026.09.00 |
| JDK | 17 (Temurin) |
| compileSdk / targetSdk / minSdk | **37** / 36 / 24 (current stable AndroidX, e.g. core 1.19 / Compose 1.12 / lifecycle 2.11, requires compileSdk 37; targetSdk stays 36 to meet Play without opting into Android 17 behaviour) |
| Libraries | Hilt 2.60.x, hilt-navigation-compose, navigation-compose (type-safe routes), lifecycle 2.11, activity-compose 1.13, DataStore, Room (from M4), WorkManager (Remote uploads), kotlinx-coroutines, kotlinx-serialization-json, Retrofit + OkHttp (Remote mode only), JUnit4, Turbine, Robolectric, androidx.test |

## 2. Architecture

- **Single-activity Compose app**, Material 3, Navigation-Compose with type-safe routes, edge-to-edge.
- **UDF/MVVM**: `ViewModel` → `StateFlow<UiState>` + events. Repositories expose `Flow`.
- **Hilt** for DI. Debug-only bindings live in `src/debug` (for example the Simulator), so release builds can't reach them.
- **Hardware behind interfaces** so everything runs in the emulator and CI:
  - `ObdTransport` (open / write line / read until `>` prompt / close) → `BluetoothSppTransport` (RFCOMM UUID 00001101-0000-1000-8000-00805F9B34FB) | `SimulatedElm327Transport`.
  - `ObdDongleProvider` lists dongles (bonded BT devices plus the simulated dongle in debug).
  - `ObdConnectionManager`: state machine `Idle → Connecting(attempt n) → Initializing → Connected → Failed(reason)`, with **exponential backoff 2, 4, 8, 16, 30 s and max 5 attempts**. After that it enters the `Failed` state and waits for a manual `retry()`. Blocked/unpaired and unsupported-protocol errors fail right away (they count as terminal, so there's no retry storm).
  - `Elm327Session`: AT init (`ATZ, ATE0, ATL0, ATS0, ATH0, ATSP0`), `ATDPN`, `ATRV`, `0100` probe, request/response with timeout. Garbled or out-of-range frames are dropped, not propagated.
  - `DongleLinkEvents`: a single flow fed by the real `ACL_CONNECTED/ACL_DISCONNECTED` receiver **and** by the Simulator's "dongle connected / disconnected" buttons, so the auto-start path is the same.
  - From M2: `PidDecoder` (SAE J1979), `TelemetryEngine` (derived metrics), `LocationSource`, `MotionSource` (accelerometer, for the crash prompt).
- **Foreground service** `DrivingService` (type `connectedDevice`, plus `location` while recording a trip) from M4.
- **Persistence**: DataStore (settings, backend mode, simulator state). Room for vehicles, trips, samples, DTC history (from M3/M4).

### Optional backend (the app works fully offline)
- Interfaces in `core/data`: `VehicleCatalogRepository`, `TelemetryUploader`, `RankingRepository`.
- Implementations: `Offline*` (default: local data, bundled catalogue, no-op uploader), `Fake*` (canned responses, no network), `Remote*` (Retrofit, HTTPS only; legacy RPC adapter per `openapi.yaml`).
- `BackendConfig { mode, baseUrl }` lives in DataStore and is editable in Settings → Server (advanced). The debug intent extra overrides it at launch; invalid values are ignored.
- Network security config: cleartext disabled. No custom TrustManagers.

## 3. Module layout

```
orange-box/
├─ app/                      # Application, MainActivity, NavHost, theme wiring, debug/ source set (Simulator entry, intent-extra handling)
├─ core/
│  ├─ model/                 # pure Kotlin types (BackendMode, ObdState, Dtc, ...)
│  ├─ common/                # dispatchers, time source, Result helpers
│  ├─ datastore/             # settings + BackendConfig + simulator state store
│  ├─ designsystem/          # Compose theme, components
│  ├─ obd/                   # transport abstraction, ELM327 session, connection manager (pure Kotlin/JVM)
│  ├─ bluetooth/             # Android BT SPP transport, dongle link receiver
│  ├─ data/                  # repositories (Offline/Fake/Remote)
│  ├─ network/               # Retrofit (Remote mode), from M9
│  ├─ database/              # Room, from M3/M4
│  └─ testing/               # fakes, test dispatchers
├─ feature/
│  ├─ home/  ├─ settings/  ├─ simulator/ (debug-only consumer)  ├─ onboarding/  ├─ vehicle/
│  ├─ dashboard/  ├─ trips/  └─ diagnostics/
├─ docs/  (FEASIBILITY.md, PLAN.md, openapi.yaml)
├─ gradle/libs.versions.toml
└─ .github/workflows/ci.yml
```
Modules are added when a milestone first needs them. M0 creates only the ones it uses.

## 4. Milestones (one PR each, CI green, Kiran reviews and merges)

| M | Deliverable |
|---|---|
| **M0** | Scaffold: Gradle (KTS, version catalog), modules, Hilt, navigation, theme, icon, backend-mode setting + debug intent extra, placeholder Home + Settings, unit tests, CI (build/lint/unit + API 34 emulator smoke with screenshots/logcat) |
| **M1** | `core:obd` transport abstraction, ELM327 session, `ObdConnectionManager` (backoff 2/4/8/16/30 s, max 5, Failed + Retry), `SimulatedElm327`, `DongleLinkEvents`, debug **Simulator screen** with all toggles, OBD status + Retry on Home. Unit tests for backoff, protocol and the simulator |
| **M2** | PID decoder (≈40 PIDs), DTC read (03/07/0A) / clear (04), VIN (0902), `TelemetryEngine` (fuel flow/economy, fuel-cut, idling, eco/overspeed, harsh accel/brake, speed zones, CO₂, warnings), scripted simulator drives |
| **M3** | Onboarding + runtime permissions (BT, location, notifications; version-gated), vehicle setup (local catalogue + manual), dongle pairing incl. simulated, opt-in auto-start via `DongleLinkEvents` |
| **M4** | `DrivingService` FGS + notification "Start driving" (no background activity launch; full-screen intent only if permitted), driving dashboard **mimicking the legacy CarooLive dashboard** (clusters, centre FUEL/TRIP cards, Detailed Info. grid; own artwork, see `docs/ui/LEGACY_OBD_UI.md`), Home Live data card as a mini dashboard, trip recording (Room) |
| **M5** | Trips: history (calendar, **Material 3**), detail (keyless map, metrics, speed-zone chart), aggregate stats (local; Material 3 with legacy hierarchy) |
| **M6** | Diagnostics **mimicking the legacy screen** (10 DTC-group lamps, vehicle illustration, message bar), group detail, clear with confirmation, "Car Diagnosing…" prompt at drive start |
| **M7** | Crash prompt: accelerometer impact detection while driving → countdown prompt → dialer / SMS app intents (no CALL_PHONE/SEND_SMS) |
| **M8** | Settings: units, alerts/sounds (new assets)/TTS, crash contact, Server (advanced) |
| **M9** *(optional)* | Remote/Fake backend: Retrofit per `openapi.yaml`, upload queue + WorkManager, rankings/points screens (Remote only) |
| **M10** | Polish: a11y, dark theme, localisation scaffold, R8 release build, secret scan in CI |

## 5. Testing
- JVM unit tests (JUnit4, Turbine, coroutines-test with virtual time for the backoff), Robolectric for Android-bound code and Compose UI tests.
- CI emulator smoke (API 34 x86_64, GitHub Actions only): launch with `backend_mode=off`, screenshot Home/Settings/Simulator, collect logcat.
- Local manual testing on Android Tester's API 25 armeabi-v7a emulator (they own it).
- Real-device checklist for Bluetooth SPP + real ELM327 before release.

## 6. Debug Simulator screen (debug builds only)
Reachable from Home's overflow/menu on debug builds. On release builds the activity/route, the simulator bindings and the `backend_mode` extra handling are absent (in the `src/debug` source set).

| Control | Effect |
|---|---|
| Use simulated dongle | Adds the "Simulated ELM327" to the dongle list and selects it |
| Dongle paired state: paired / **unpaired (blocked)** | Unpaired makes connect fail right away with `Failed(NotPaired)` and re-pair guidance |
| **Dongle connected / disconnected over Bluetooth** buttons | Emit `DongleLinkEvent.Connected/Disconnected` through the same path as the real ACL receiver |
| Engine on / off | Off: RPM 0, speed 0, `ATRV` ≈12.4 V. On: idle ≈800 rpm, ≈14.1 V |
| Speed / RPM / coolant / fuel level sliders | Live PID values served by the simulator |
| Inject DTCs (P0300, P0420, U0100, custom) + MIL toggle; Clear | Mode 01 PID 01 reflects MIL/count; mode 03 returns the codes; mode 04 clears them |
| Connection failure | `open()` throws (drives backoff → Failed after 5 attempts); turning it on mid-session drops the link |
| Unsupported protocol | `ATSP0`/`0100` replies `UNABLE TO CONNECT` → `Failed(UnsupportedProtocol)` |
| Slow responses | Adds a 1.5 s delay per response |
| Garbled responses | Corrupts about 30% of responses (the session drops invalid frames) |
| Garbled protocol reply (ATDPN, always) | Every `ATDPN` reply is garbage. `connect` re-queries 3 times, fails with "Could not determine the vehicle protocol (ATDPN unreadable)", retries on the back-off schedule and ends in Failed |
| Backend mode Off / Fake / Remote | Writes `BackendConfig` |
| Reset simulator | Restores defaults. Simulator state persists in DataStore across restarts; the screen shows the active failure toggles prominently |
| Status panel | Live `ObdConnectionState`, attempt n/5, next retry countdown, last error, last link event |

## 7. CI (`.github/workflows/ci.yml`)
- `build` job: ubuntu-latest, Temurin 17, `gradle/actions/setup-gradle`, `./gradlew assembleDebug lintDebug testDebugUnitTest`, uploads the debug APK + reports.
- `emulator-smoke` job: needs `build`, enables KVM (udev rule), `reactivecircus/android-emulator-runner` API 34 x86_64 `google_apis`, installs the APK, `am start ... --es backend_mode off`, screenshots + logcat as artifacts.

## 8. UI direction (Kiran, 2026-10-24)

- Screens whose main job is showing OBD values (driving dashboard, detail readings, Home Live data card, diagnostics) **mimic the legacy CarooLive UI**: layout, hierarchy, panel styles, colours, type scale and flow, "same or better". The spec is [`docs/ui/LEGACY_OBD_UI.md`](ui/LEGACY_OBD_UI.md).
- **All artwork is our own** (vector drawables or Compose Canvas). No Pokevian image, drawable, font, icon or sound is copied, traced or embedded. The images in `docs/ui/legacy-reference/` are for reference only.
- The trip list, Settings, onboarding, permissions, pairing and vehicles use **default Material 3** components.
- Everywhere: 48 dp touch targets, content descriptions, font scaling, edge-to-edge, dark theme, and modern version-gated permission flows.
