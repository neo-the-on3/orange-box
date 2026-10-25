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

## Getting to the dashboard (#192)
- **Home Drive button**, the first item on Home in every connection state: "Start driving" connects and opens
  the dashboard (Idle, Link lost, Failed); "Open dashboard" just opens it while connecting or connected.
  The mapping is `DriveAction.of(state)` in `feature:home`.
- **Live data card:** tapping anywhere on it opens the dashboard; the "Open dashboard" button stays for TalkBack.
- **Launcher shortcut "Drive"** (long-press the app icon, or pin it): a dynamic shortcut published by
  `LauncherShortcuts` on launch, opening `MainActivity` with `EXTRA_OPEN_DASHBOARD`. Dynamic rather than
  `shortcuts.xml`, so it follows the `.debug` application id.
- **Backend pill** ("Backend: Fake/Remote") only shows when the backend is on; Off is the default and says nothing.
- **Show dashboard automatically** stays off by default (`DashboardPreferences.autoShowDashboard`); flipping
  the default is a one-line change there.

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
- **Events:** `harsh_accel`, `harsh_brake`, `overspeed` and `fuel_cut`, from the `TelemetryEngine` flags. A row is stamped at the rising edge but written when the flag falls (or the trip ends), with the peak as `value` (max acceleration; the strongest braking, negative; max speed for overspeed; the start speed for fuel cut) and `durationS` (#164). Rows from before DB v3 keep the start sample and a null duration. An event still running at process death is lost; the trip's counts still include it.
- **What counts as an event (#165):** the trip list's "N events" and the detail's Events section are the same set: harsh acceleration, harsh braking and overspeed. Fuel cut is engine braking that saves fuel, so the detail lists it in its own "Fuel cut" section and it is never counted.
- **Checkpoints:** every 5 s, the summary and pending rows are written in one Room transaction. Process death loses at most 5 s. On the next start, open trips are closed at their last sample with `process_death`.
- **End:** 30 s without the engine running. The reason is `engine_off` while still connected, otherwise `disconnected`. While connected, the trip ends as `engine_off` only after a sample has actually read the engine as not running (rpm 0 or missing). A poll stall on a live link, where no samples arrive because an ELM327 is slow or busy or the device stalls, says nothing about the engine, so it holds the trip open and logs `trip_grace_held reason=poll_stall` (#142). A link that really fails leaves Connected, and the trip then ends as `disconnected` after the grace. A reconnect inside the grace continues the trip, and the totals are carried over the engine reset. The ongoing notification, the dashboard TRIP/FUEL tiles, the Home mini-dashboard and `trip_end` all read these recorded totals (`RecordedTripTotals`), never the telemetry engine's per-connection counters (#144).
- **Stats (#46):** Trips → Stats shows weekly (locale's first day of week) or monthly totals of finished trips, the current period and the 7 before it, from one `TripDao.totals(from, to)` query per period. A trip still recording is left out until it ends.
- **Tables (DB v3; v1→v2 and v2→v3 are Room auto-migrations):** `trips`, `trip_samples`, `trip_events`.
