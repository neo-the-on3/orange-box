# CarooLive → Kotlin + Compose: Feasibility Analysis

_Phase 1 (analysis only). Source: https://github.com/Pokevian/caroolive-app, cloned read-only to `/workspace/caroolive-app` on 2026-10-23._
_All paths below are relative to that clone unless stated._

> **Decision update (2026-10-23, Kiran):** the dashcam is **dropped** from orange-box, along with the floating overlay, call hang-up, silent crash call/SMS (replaced by a dialer/SMS-app prompt), push, social login, YouTube upload and server web pages. minSdk is 24. Maps are keyless. See `PLAN.md` section 0 for the final decisions. The analysis below is kept as written for reference; the dashcam sections now describe legacy behaviour only.

## 1. Verdict

**Feasible, with caveats.** The app can be rebuilt as a modern Kotlin + Jetpack Compose app. It will be a **re-implementation, not a port**: the core OBD-II engine, dashcam engine and MP4 muxer are **closed, partly obfuscated binary jars** plus **32-bit ARMv5 native libraries**. We would not carry any of them forward. We would rewrite those functions from public standards (ELM327 AT commands, SAE J1979 PIDs, Android CameraX/MediaCodec/MediaMuxer). Several 2015-era features are impossible or restricted on modern Android/Play: hidden-API call hang-up, auto SMS/call, Google+ sign-in, GCM/FingerPush push, and the server-rendered web dashboards. They need to be dropped or redesigned. The original backend is offline, so all "connected" features must run against a local/fake data source by default.

## 2. Repository facts

| Item | Value | Evidence |
|---|---|---|
| Size | 39 MB working tree incl. `.git`; 1,332 files; 11 commits | `du`, `git log` |
| First / last commit | 2017-08-22 / **2017-09-04** ("Clean up codes"). Unmaintained for 9 years | `git log` |
| License | Apache-2.0 (`LICENSE`, README "(c) 2017 Pokevian Inc. … Apache 2.0") | `LICENSE`, `README.md` |
| Language | 100% Java (245 `.java` in app, ~57.7k LOC); 1 AIDL (`com.android.internal.telephony.ITelephony`) | `app/src/main/java`, `app/src/main/aidl` |
| Modules | `:app`, `:caldroid` (vendored Caldroid calendar, Apache), `:android-support-v4-preferencefragment` (vendored, Apache) | `settings.gradle` |
| Build | Gradle 3.3 wrapper, AGP 2.3.3, `jcenter()` (shut down), buildTools 25.0.0. **Will not build today** | `build.gradle`, `gradle/wrapper/gradle-wrapper.properties` |
| SDK | `compileSdk 22`, `minSdk 15`, `targetSdk 22`, multidex | `app/build.gradle` |
| App id / version | `com.pokevian.optimus` (manifest package `com.pokevian.app.smartfleet`), 1.06.01 | `app/build.gradle`, `AndroidManifest.xml` |
| UI | 36 `<activity>` (≈31 app-owned), 78 Fragment classes (51 DialogFragments), 127 layouts, ~985 res files (675 PNGs), 536 strings (English only) | `AndroidManifest.xml`, `res/` |
| Background | 13 services (Driving, Vehicle, SoundEffect, TripReport, FloatingHead, DataUpload `:upload`, YoutubeUpload `:upload`, CrashDetect, AutoConnect, ScreenMonitor, GCM×3), 5 receivers (boot, connectivity, OBD timeout, bring-to-front, GCM) | `AndroidManifest.xml` |
| Local storage | SharedPreferences + Gson (`setting/SettingsStore.java`, `service/TripStore.java`); raw SQLiteOpenHelper queues (`DataUploadService.RequestStore` "data-request", YoutubeUploadService store); video files + `.smi` subtitle files with trip data on external storage | listed files |
| Logging | log4j 1.2.17 (EOL, known CVEs) + custom appenders `org/apache/log4j/*` | `app/build.gradle` |

### 2.1 Dependencies

| Dependency | Kind | Status / problem |
|---|---|---|
| `libs/lib-obd2-android.jar` (65 classes, **ProGuard-obfuscated** `engine/a..o`) | Pokevian closed OBD engine: ELM327 over BT SPP (UUID 00001101-…), PID polling, ~200 computed KEYs (fuel economy/flow from MAF, fuel-cut, ignition/engine-status detection, harsh accel/brake/turn, speed-zone times, CO₂, warnings) | No source. Reimplement |
| `libs/lib-blackbox2.jar` (128 classes) | Closed dashcam engine (Camera1 + MediaRecorder/MediaCodec, file splitting, event clips, storage management) | No source, uses **deprecated `android.hardware.Camera`**. Reimplement with CameraX |
| `libs/lib-mp4box-android-1.1.0-test.jar` | Closed MP4 box reader/writer (partial extraction) | Replace with `MediaExtractor`/`MediaMuxer` or Media3 Transformer |
| `libs/lib-common-android-v1.0.10.jar` | Closed utilities (image loader, StorageFinder, ReverseGeocoder, AES util) | Replace with Coil, `Geocoder`, Jetpack |
| `libs/caroo-smart-model_1.3.14.jar` (80 classes, not obfuscated) | Server DTOs/enums | Field names recovered → `analysis/smart-model-fields.txt`. Re-model as Kotlin `@Serializable` |
| `jniLibs/armeabi/libnative_buffer.so`, `libnative_file.so`, `libcolor_correction.so` | **ELF32 ARMv5TE only**, loaded by blackbox/mp4 jars | `armeabi` was removed in NDK r17, and Play requires 64-bit. They cannot ship. Not needed with CameraX/MediaCodec |
| `libs/gcm.jar`, `libs/fingerpush2.2.9.jar` | GCM (shut down 2019) + FingerPush (Korean push SaaS) | Dead. Drop, or FCM later (optional) |
| `play-services:6.1.71` (monolithic), Google+ `Plus.API` sign-in | Google sign-in via Google+ (`ui/common/GoogleAuthFragment.java`) | **Google+ API shut down**. Use Credential Manager + Sign in with Google if auth is kept |
| `facebook-android-sdk:4.6.0` | FB login | Ancient. Drop or update (optional) |
| `google-api-services-youtube v3`, `google-api-client-android 1.19` | Upload of event clips to YouTube (`service/YoutubeUploadService.java`) | Possible with modern OAuth, but needs a Google Cloud project and verification. Recommend dropping or replacing with the system share sheet |
| Volley 1.0.19 (mcxiaoke fork), Apache HttpClient, Gson | Networking | Replace with Retrofit/OkHttp or Ktor + kotlinx.serialization |
| Google Maps v2 (`ui/video/PlayerMapFragment.java`) | Trip route on video playback | Maps Compose (needs a key) **or** osmdroid/MapLibre (no key) |
| Caldroid (vendored) | Calendar picker for trip dates | Replace with Compose Material3 DatePicker / custom calendar |

### 2.2 Hardware / platform integrations (from manifest + code)

- **Bluetooth Classic SPP to ELM327 OBD-II dongles** (BLUETOOTH, BLUETOOTH_ADMIN; pairing UI `ui/setup/PickObdDeviceFragment.java`; auto-connect `service/AutoConnectService.java`). Reads PIDs, VIN, DTC read/clear (`ui/diagnostic/*`).
- **GPS** (fine location; `service/LocationWrapper.java`, `VehicleLocationQueue.java`).
- **Camera + microphone** dashcam with continuous/loop recording and event (impact) clips (`service/BlackboxManager.java`, `ui/driving/BlackboxPreview.java`, `BlackboxControlFragment.java`).
- **Accelerometer** impact/crash detection and mount alignment (`service/ImpactDetector.java`, `AlignMonitor.java`, `CrashDetectService.java`).
- **Emergency rescue (ERS)**: on impact, automatic phone call (`ACTION_CALL`) and SMS (`SmsManager`) to a contact (`ui/driving/ErsProcessor.java`), plus optional YouTube upload.
- **Floating head overlay** (SYSTEM_ALERT_WINDOW) that handles incoming calls (ends calls via **reflection on hidden `ITelephony`**, `service/floatinghead/CallHandler.java`), SMS replies (`SmsHandler.java`) and TTS read-out (`TtsHandler.java`) while driving.
- Sound effects (`res/raw/*.ogg`), TTS strings, boot-completed autostart, connectivity receiver, screen monitor.

### 2.3 Feature inventory (what the app does)

1. **Onboarding/setup**: intro, disclaimers/terms (server HTML), account sign-in (Google/Facebook → server `checkMember`/`regMember`), vehicle registration via a cascading server catalogue (maker → type → model → fuel → displacement → year), OBD device pick, odometer input. (`ui/intro`, `ui/setup` ~12k LOC, much of it copy-pasted across 4 vehicle fragments.)
2. **Driving mode** (landscape dashboard, `screenshots/driving_mode.png`): RPM, speed, fuel economy, fuel-cut, overspeed, idling, eco, harsh accel/brake, coolant, battery, MIL/OBD status, dashcam preview, emergency button. (`ui/driving` ~6k LOC.)
3. **Trip tracking & upload**: trip aggregation (`service/TripManager.java`), queued gzip upload to gathering server (`service/DataUploadService.java`), trip report (`ui/report/TripReportActivity`).
4. **Diagnostics**: read/clear DTCs, MIL status, car status (`ui/diagnostic`).
5. **Dashcam video**: list/copy/archive/delete, playback with synced map and driving data from `.smi` (`ui/video` ~4.4k LOC).
6. **Connected/web features**: dashboard, stats charts, eco points, rankings, hall of fame, notices, events, YouTube video list. **Almost all are server-rendered WebViews** (`ui/tripmonitor`, `ui/web`, `ui/main/MainActivity.java:891-931`) with a JS bridge.
7. **Settings**: general, dashcam, vehicle(s), ERS, engine-off behaviour (`res/xml/preference_*.xml`, `ui/settings`).
8. Misc: crash reporter (`:crash` process), push alerts (FingerPush), floating head.

## 3. Backend summary (full spec: `analysis/openapi.yaml`, validated as OpenAPI 3.1)

- Two hosts on a **raw IP over plain HTTP**: service `http://14.63.216.111:9801` and gathering `:9701` (`setting/ServerUrl.java`). **Both timed out on 2026-10-23**, and the beta link `carooplus.com:9801` is also down. The backend is gone, so the rewrite cannot be tested against it.
- Transport: Volley `GsonRequest`, always **POST form-urlencoded**, 5 s timeout, 2 retries (`volley/GsonRequest.java`).
- **RPC endpoint `POST /smart.json`**, multiplexed by `cmd` with a JSON-string `data` field:
  `checkMember`, `regMember`, `withdrawMember`, `getMemberCarList`, `insertCar`, `updateCar`, `getCarModelList` (6 cascading variants via `targetField`), `getCodeList` (country/region), and `carMessage` (gathering server; `data` = base64(gzip(JSON SmartMessageList)), `zipFlag=Y`, carrying trip summaries, location+OBD samples, events, video refs).
- **Session**: `POST /mbrLogin.do` `cmd=login.json` / `logout.json`. The returned cookies are injected into WebView `CookieManager`.
- **Stats JSON**: `POST /api/v2/trip/stats/getRanking.do` (used); `getScoreStar.do`, `ranking/getBestTripRankingList.do`, `ranking/getBestTripMyRanking.do`, and 7 `*RankingTop10.do` endpoints (request classes exist, **no UI uses them**).
- **Web-JSON**: `POST /m/trip.do?cmd=qSelectUserDrivingTripDate` (used, cookie); `/m/board.do?cmd=qSelectCategoryList` (call commented out).
- **~23 server-rendered pages** (`/m/view.do?cmd=…`, `/m/chart.do?…`, `/web/contractDetail_0x.html`) are listed under `x-webview-pages`. Their content cannot be recovered from the client.
- **Auth is insecure by design**: there is no password or token. The server trusts a client-supplied `loginId` (e-mail) and `memberNo`. The client also installs a **trust-all SSL** context (`volley/VolleySingleton.java`). The new spec must not copy this. The rewrite should use HTTPS and bearer tokens.

## 4. Blockers & risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | **OBD engine is closed/obfuscated.** Its derived metrics (fuel economy without a fuel-rate PID, fuel-cut detection, ignition/engine-status detection, harsh-event thresholds, eco/safe scores) are proprietary algorithms | High | Clean-room Kotlin ELM327 client + SAE J1979 decoding. Implement our own documented formulas: MAF-based fuel flow with AFR/density per fuel type; fuel-cut ≈ RPM > idle && load≈0 && closed-loop status; thresholds as config. Numbers will differ from the original |
| R2 | **Dashcam engine closed + ARMv5 native libs** | High | CameraX `VideoCapture`/Recorder with segmenting, plus an event-clip ring buffer (keep N segments, then lock/copy). Partial-clip extraction via Media3 Transformer. Can't be byte-for-byte faithful |
| R3 | **Hardware-only features can't be tested in CI/emulator** (BT SPP, real camera recording, accelerometer impact, GPS in vehicle) | High | Hexagonal design: `ObdTransport` interface with a **simulated ELM327** (scripted PID replay) for emulator/unit tests. Fake camera/location sources. Manual device test checklist for Kiran |
| R4 | **Restricted/removed Android APIs**: hidden `ITelephony.endCall`, auto `ACTION_CALL`, background `SEND_SMS` (Play SMS/Call-Log policy), Google+ API, GCM, `SYSTEM_ALERT_WINDOW` overlay, background services/implicit broadcasts (O+), external storage paths (scoped storage, Q+), BLUETOOTH_CONNECT/SCAN (S+), foreground-service types `connectedDevice`/`location`/`camera`/`microphone` (U+), notification permission (T+) | High | Drop the floating head and auto call-hangup. ERS becomes "prompt user, open dialer/SMS composer with prefilled text" via intents (no special permissions), or an opt-in direct-send flagged as non-Play. One foreground service with proper types. MediaStore/app-specific storage. Runtime permission flows in Compose |
| R5 | **Backend offline; web dashboards irrecoverable** | Medium | Backend is optional. Default `OfflineRepository` (Room). Rebuild stats/charts/trip history **natively from local data**. Rankings, points, notices and events only work in "server mode" and are hidden otherwise |
| R6 | **Licensing/assets**: code is Apache-2.0, but the README calls the OBD/blackbox libs "commercial" and the jars carry no license notice. Brand name "CaroO Live", logos, icons and screenshots are Pokevian trademarks. Committed secrets (Google Maps API key in `res/values/config.xml`, FingerPush APP_KEY/SECRET in `assets/FingerPush.properties`, **release keystore password in `app/build.gradle`**) | Medium | Do **not** copy jars, `.so`, keys, keystores, or branding into orange-box. Reuse Apache code ideas, string texts and UX flow with attribution (NOTICE). New icons and theme. Assets need Kiran's call (see questions) |
| R7 | Faithfulness: the vehicle catalogue (≈700 Korean-market `CarModelNameCd` values) came from the server | Low/Med | Ship a small local seed (maker/model/fuel/displacement) or free-text entry. Optional server lookup |
| R8 | Scope: ~57k LOC Java and 31 activities. Heavy duplication (e.g. `RegisterVehicleFragment.java`/`SelectVehicleFragment.java` ≈2.2k LOC each, near copies) | Medium | Feature-sliced milestones (see PLAN). The real unique surface is ~12–15 screens |
| R9 | Manifest references `ui.pattern.DrivingPatternActivity`, which has no source in the repo. Minor dead code (unused request classes, commented calls) | Low | Ignore |

## 5. Scope estimate (what we'd build)

| Area | Screens (Compose destinations) | Port fidelity |
|---|---|---|
| Onboarding (welcome, permissions, terms, vehicle setup, OBD pairing) | 5–6 | High (UX flow). Vehicle catalogue simplified |
| Driving dashboard (live gauges, warnings, status) | 1 (+landscape) | High for PIDs. Medium for derived metrics (own algorithms) |
| Trip history / trip detail / stats charts (native, local) | 3 | Medium (replaces WebView pages with native) |
| Diagnostics (DTC read/clear, MIL, VIN, freeze info) | 1–2 | High |
| Dashcam (preview/record, list, player with map + data overlay) | 3 | Medium (CameraX re-implementation) |
| Settings (general, vehicle(s), dashcam, ERS, server/backend) | 4–5 | High |
| Account / connected features (sign-in, rankings, sync) | 2–3, **optional**, behind flag | Low–Medium (depends on a server that doesn't exist) |
| **Total** | **~19–23 destinations**, 1 foreground service, 1 upload worker | |

**Cannot be ported faithfully**: the floating-head call/SMS overlay, automatic call hang-up, silent auto-SMS/call on crash (Play policy), Google+ sign-in, FingerPush/GCM push, YouTube auto-upload (possible, but needs OAuth app verification), server WebView dashboards/rankings/points/notices, the exact proprietary eco/fuel algorithms.

## 6. Environment readiness (box, checked read-only)

| Check | Result |
|---|---|
| `gh` | v2.103.0 installed, logged in as **neo-the-on3** (scopes: `repo`, `workflow`, `read:org`, `gist`) |
| Target repo `neo-the-on3/orange-box` | Exists, **private**, **empty** (no default branch yet), `viewerPermission: ADMIN`. **Push access: yes.** Actions enabled (`allowed_actions: all`). Description: "Real-time vehicle telemetry and data logging over OBD-II…" |
| JDK | **Not installed** (`java`/`javac` absent, no `/usr/lib/jvm`) |
| Android SDK | **Not installed** (no `ANDROID_HOME`, no `sdkmanager`/`adb`) |
| Host | x86_64, 8 vCPU, 15 GB RAM, 115 GB free. Fine for Gradle builds. No KVM check done, and an emulator is likely impractical; rely on CI and unit tests |
| Nothing pushed, no PRs, no tokens created | ✔ |

Artifacts produced: `analysis/openapi.yaml`, `analysis/smart-model-fields.txt` (DTO fields dumped from the jar), `analysis/tools/classfields.py`.
