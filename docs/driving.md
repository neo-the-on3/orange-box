# Driving service, notifications and trip recording (M4)

## Auto-start
`DrivingController` (installed by `OrangeBoxApplication`) reacts to the OBD link state:

- **Dongle connects**:
  - Start `TripRecorder`; it idles until the engine runs.
  - Start `DrivingService`.
  - If the app is not on screen, or the platform refuses a background service start (`ForegroundServiceStartNotAllowedException`, API 31+), post the **Start driving** notification. Nothing is ever opened from the background.
  - With **Show dashboard automatically** on (Settings → Driving dashboard, off by default): if the app is on screen, bring the dashboard to the front; from the background, the Start driving notification carries a full-screen intent (see below).
- **App comes on screen while connected**: retry the service start and cancel the Start driving notification.
- **Drive ended** (dashboard power button, after its 5 s undo window):
  - close the trip (`user`);
  - stop the service;
  - disconnect the dongle.

## DrivingService
- **Foreground service type:** `connectedDevice`, plus `location` when a location permission is granted. If the location type is refused (e.g. started while not in use), it falls back to `connectedDevice` only. If that is refused too, the service stops instead of crashing.
- **Ongoing notification** (channel `driving`, low importance):
  - "OBD dongle connected" while waiting for the engine;
  - "Recording trip · N km · h:mm:ss" while a trip is recording;
  - a tap or the **Start driving** action opens the dashboard.
- **Stopping:** the service stops itself 60 s after the link goes away. The recorder has already closed the trip after its 30 s grace by then.
- **Not sticky:** after process death the next dongle connect starts a new drive.

## Start driving notification
- Channel `drive_start`, high importance, auto-cancels after 10 min.
- The tap and the action both open the dashboard (`MainActivity` with `EXTRA_OPEN_DASHBOARD`).
- The full-screen intent is only attached when **Show dashboard automatically** is on and `NotificationManager.canUseFullScreenIntent()` allows it (API 34+). Below API 34, the manifest `USE_FULL_SCREEN_INTENT` permission is enough.
- With the setting on but the permission refused (API 34+), the notification is posted normally with an **Allow full screen** action that opens `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` for Orange Box. Settings shows the same link ("Full-screen notifications are off") and re-checks it on resume.

## Show dashboard automatically
- Opt-in, off by default. Its description warns that the dashboard is visible without unlocking.
- While the setting is on, the dashboard screen calls `setShowWhenLocked(true)` and `setTurnScreenOn(true)` (window flags below API 27) and clears them when it closes. An auto-show start (foreground start or full-screen intent, `EXTRA_SHOW_WHEN_LOCKED`) sets them in `MainActivity` before the dashboard is composed, so a locked phone wakes straight into it; during onboarding the request is ignored and the flags are cleared.
- The keyguard is never dismissed (`requestDismissKeyguard` is not used): leaving the dashboard for any other screen or app needs an unlock.
- An activity is only started from the foreground. From the background the platform's full-screen intent does it, and only when allowed.
- **Google Play:** since API 34, Play restricts `USE_FULL_SCREEN_INTENT` to calling and alarm apps; other apps don't get it by default on install and must ask the user. A Play build would need a declaration (or rely on the settings link). Sideloaded/F-Droid builds behave the same: the user grants it in system settings.

## Dashboard bottom-left button
Settings → Driving dashboard → Bottom-left button:
- **Hazard** (default): emergency prompt that opens the dialer with the number pre-filled; it never calls. The number is editable; by default it comes from the SIM country, then the network country, then the locale (`DeviceCountry`, `EmergencyNumbers`): 112 for the EU, India and unknown countries; 911 US/CA/MX; 999 UK; 000 AU; 111 NZ; 110 JP; 190 BR; 10111 ZA, and so on.
- **Quick launcher**: the user picks an installed app; the button shows its icon and launches it. Apps are found through a `<queries>` MAIN/LAUNCHER intent (no `QUERY_ALL_PACKAGES`). If the app is uninstalled, the button falls back to Hazard.
- Nothing is posted without notification permission.

## Trip recording (`TripRecorder`)
- **Start:** RPM > 0 while connected. This zeroes the telemetry trip totals, so the dashboard TRIP card shows the recorded trip.
- **Samples:** about one per second (speed, RPM, fuel flow, coolant), plus the latest `LocationManager` fix (GPS/network, no Google services, only while recording).
- **Events:** `harsh_accel`, `harsh_brake`, `overspeed` and `fuel_cut`, on the rising edge of the `TelemetryEngine` flags.
- **Checkpoints:** every 5 s, the summary and pending rows are written in one Room transaction. Process death loses at most 5 s. On the next start, open trips are closed at their last sample with `process_death`.
- **End:** 30 s without the engine running. The reason is `engine_off` while still connected, otherwise `disconnected`. While connected, the trip ends as `engine_off` only after a sample has actually read the engine as not running (rpm 0 or missing). A poll stall on a live link, where no samples arrive because an ELM327 is slow or busy or the device stalls, says nothing about the engine, so it holds the trip open and logs `trip_grace_held reason=poll_stall` (#142). A link that really fails leaves Connected, and the trip then ends as `disconnected` after the grace. A reconnect inside the grace continues the trip, and the totals are carried over the engine reset.
- **Tables (DB v2, auto-migrated from v1):** `trips`, `trip_samples`, `trip_events`.
