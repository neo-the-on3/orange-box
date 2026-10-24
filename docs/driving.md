# Driving service, notifications and trip recording (M4)

## Auto-start
`DrivingController` (installed by `OrangeBoxApplication`) reacts to the OBD link state:

- **Dongle connects**:
  - Start `TripRecorder`; it idles until the engine runs.
  - Start `DrivingService`.
  - If the app is not on screen, or the platform refuses a background service start (`ForegroundServiceStartNotAllowedException`, API 31+), post the **Start driving** notification. Nothing is ever opened from the background.
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
- The full-screen intent is only attached when `NotificationManager.canUseFullScreenIntent()` allows it (API 34+). Below API 34, the manifest `USE_FULL_SCREEN_INTENT` permission is enough.
- Nothing is posted without notification permission.

## Trip recording (`TripRecorder`)
- **Start:** RPM > 0 while connected. This zeroes the telemetry trip totals, so the dashboard TRIP card shows the recorded trip.
- **Samples:** about one per second (speed, RPM, fuel flow, coolant), plus the latest `LocationManager` fix (GPS/network, no Google services, only while recording).
- **Events:** `harsh_accel`, `harsh_brake`, `overspeed` and `fuel_cut`, on the rising edge of the `TelemetryEngine` flags.
- **Checkpoints:** every 5 s, the summary and pending rows are written in one Room transaction. Process death loses at most 5 s. On the next start, open trips are closed at their last sample with `process_death`.
- **End:** 30 s without the engine running. The reason is `engine_off` while still connected, otherwise `disconnected`. A reconnect inside the grace continues the trip, and the totals are carried over the engine reset.
- **Tables (DB v2, auto-migrated from v1):** `trips`, `trip_samples`, `trip_events`.
