# Real-device checklist

Things CI, Robolectric and the simulator cannot prove. Run through this list on a real phone and a real ELM327 dongle
before a release, and after changes to Bluetooth, polling, telemetry or sync.

**Setup**
- Install the debug build from `main`: the CI artifact or `orange-box-<version>-debug.apk`.
- Only debug builds have Home ⋮ → *Export connection log* and *Export trips*.
- For the release-build section at the end, use the release APK.
- Note the build (Settings → About), phone model and Android version, car, and dongle at the top of the report.

**Logs.** After each drive:
- export the connection log (Home ⋮ → *Export connection log*);
- export the trips (Home ⋮ → *Export trips*);
- attach both to the issue being checked, together with a note of anything odd and the time it happened.

## 1. Pairing and setup
- [ ] Fresh install shows onboarding: welcome, disclaimer (accept), permissions, your car, dongle, finish.
- [ ] *Your car*: pick a model from the list, and also try *Enter manually*. Both appear in Settings → Vehicles.
- [ ] Pair the ELM327 in Android's Bluetooth settings first. The dongle step then lists it. With nothing paired, it
      explains how to pair.
- [ ] Unpair the dongle in Android settings. The app says it isn't paired any more and offers to pair it again.
- [ ] API 31+: deny *Nearby devices* (BLUETOOTH_CONNECT). Connect ends in *Failed – Bluetooth permission needed*,
      with no crash. Allow it, and Connect works.
- [ ] API 24–30 (e.g. Android 7/8): connect works without a runtime prompt.

## 2. Connecting (M1)
- [ ] Tap Connect on Home with the ignition on. The card shows *Connected*, the protocol (e.g. ISO 15765-4 CAN 11/500)
      and a battery voltage of about 12–14.5 V.
- [ ] Turn the dongle off while connected. The card shows *Retry 1/5 in 2 s* and reconnects when the dongle comes back.
- [ ] Leave the dongle off. After 5 retries (2, 4, 8, 16, 30 s) the card shows *Failed* with a Retry button. Retry
      starts again at the first attempt.
- [ ] Ignition off / no vehicle: the probe reports an error, with no crash.
- [ ] Key off with the dongle still powered:
  - `ecu_silent` appears in the log, the values clear, and the trip ends with `engine_off`;
  - polling backs off to about one round every 2 s, until `ecu_awake` when the key goes back on.

## 3. Dashboard
- [ ] *Start driving* opens the cluster dashboard. RPM and speed are live, and the lamps light for real conditions:
      coolant, battery, eco, overspeed, harsh acceleration/braking, idling, fuel cut.
- [ ] Portrait and landscape both fit, and so does the *Detailed info* panel. There is no clipping at the largest
      system font size.
- [ ] TalkBack on: the meters read "Speed 52 kilometres per hour" and "Engine speed 2100 rpm". Lamps read their name
      and state.
- [ ] Check engine light on (if the car has a stored code): the Car Diagnosing prompt at drive start lists it, and
      Diagnostics shows the code.

### Speed lag (#193)
- [ ] Speed tracks the car's speedometer within about a second. The speedometer reads a few % high by design, so
      expect the app to read slightly lower.
- [ ] RPM is equally fresh.
- [ ] Connection log:
  - `polling_stats` lines appear about every 30 s, with ticks/s, the age of the speed value and request latency;
  - the gaps between `010D` (and `010C`) requests are about 0.5 s at the median and under 1 s, with no multi-second
    stalls when slow PIDs are read.
- [ ] Coolant, fuel level and the other slow values still refresh within a few seconds.

### Fuel economy and fuel cut (#194)
- [ ] Fuel cut: come off the accelerator in gear above 20 km/h and 1,000 rpm. The FUEL-CUT lamp lights and fuel flow
      reads 0.0 L/h. This must also work when the throttle plate stays partly open on overrun, e.g. about 16 % closed
      throttle on the SX4.
- [ ] No false fuel cut while cruising with a light foot on the pedal.
- [ ] The connection log has a `fuel_source` line (`fuel_rate`, `maf` or `speed_density`). On the SX4 expect
      `maf`. No phantom fuel after `ecu_silent`.
- [ ] Compare the trip's fuel economy with a tank-to-tank fill (fuel pump litres ÷ trip km) or the car's MID/trip
      computer. Note both figures in the report. Settings → Units → Petrol blend shows E20 in India (#208); set it to
      what the pump sells.
- [ ] Low-speed fuel cut (#209): rolling in gear to a stop with the foot off, the FUEL-CUT lamp stays lit down to about
      1,000 rpm (below 20 km/h), and goes out when the clutch goes in or the idle speed returns.
- [ ] Trip detail lists far fewer fuel cuts than before (#207): a cut with a brief pedal blip is one row, no rows under 1 s.
- [ ] The exported connection log starts with `# session N started …` headers (#200) listing the adapter, protocol,
      PID bitmaps, supported PIDs, poll plan and vehicle (fuel, petrol blend, displacement); no MAC or VIN. A full
      day's drives fit (the last 3 connections' headers are kept).

## 4. Trips
- [ ] A drive of a few minutes appears in Trips with its distance, time, economy, eco time and events. The route map
      shows OpenStreetMap tiles.
- [ ] The month summary and Stats (weekly / monthly) add up the trips.
- [ ] Trip detail → ⋮ → *Exclude from stats* (#211): the trip stays listed, marked *Excluded from stats*, and the month
      summary and Stats drop it. *Include in stats* puts it back. *Delete trip* asks first.
- [ ] Settings → Driving dashboard → *Speedometer offset* (#210): at +N %, the dashboard and Home card speed read N %
      higher; the overspeed warning and trips don't change.
- [ ] If map tiles fail, the log's `map_tiles_unavailable` line has `status=` (HTTP) or `error=` (exception) (#212).
- [ ] Kill the app during a drive: the trip is closed and kept on the next launch, not lost.

## 5. Alerts and voice (M8)
- [ ] Settings → Driving alerts:
  - the overspeed tone plays over music;
  - with *Speak alerts* on, the phone's TTS engine speaks the alert;
  - with the TTS engine missing or disabled, there is a tone only and no crash.
- [ ] Harsh acceleration/braking alert at most once every 10 s. Long idling after 3 minutes standing still.
      Check-engine once per drive.
- [ ] Units: switch to mi / mpg / °F and check the dashboard, Home card, trips, notification and spoken alerts.

## 6. Show dashboard automatically, over the lock screen
- [ ] Settings → Driving dashboard → *Show dashboard automatically* on. Lock the phone and start the car. The dongle
      connects, the screen wakes, and the dashboard shows over the lock screen without unlocking.
- [ ] Back or close from that dashboard returns to the lock screen. The rest of the app needs an unlock.
- [ ] With the setting off, connecting while locked does not wake the screen.
- [ ] Android 14+: with *Full-screen notifications* denied for the app, connecting in the background only posts the
      Start driving notification. Tapping it opens the dashboard.

## 7. Crash prompt (M7)
- [ ] Fresh install: Settings → Crash detection is off. With it off, a hard knock while driving raises no prompt.
- [ ] Turn Crash detection on for the checks below that need the sensor.
- [ ] Knock the phone on a table while connected and parked: no prompt (not moving).
- [ ] Drop the phone onto a cushion while driving (as a passenger): no prompt (free fall is rejected).
- [ ] Simulator → "Inject impact": the countdown shows over every screen. With the app in the background, a
      full-screen notification appears.
- [ ] When the countdown reaches 0, the dialer opens with the number filled in. Nothing is called.
- [ ] "Text my location" opens the SMS app with the emergency contact and an OpenStreetMap link. Nothing is sent.
- [ ] Choose an emergency contact with the system picker: no contacts permission prompt appears.
- [ ] Turn off "Check the car when a drive starts": the next drive shows no Car Diagnosing prompt.

## 8. Backend sign-in and sync (M9)
- [ ] Settings → Account and sync: save the https:// server address, enter your e-mail, and tap *Send code*. The
      6-digit code arrives, and *Sign in* shows *Signed in as …*.
- [ ] Wrong code: *That code is wrong or has expired*. Too many wrong codes ask you to request a new one.
- [ ] After a drive, *Sync now* uploads it: *Last sync …* with counts. Rankings show the member summary.
- [ ] Flight mode during a sync: *No connection to the server*, then *Retrying automatically*. It syncs once back
      online.
- [ ] Sign out, then sign in again: trips stay on the phone and sync again.

### Merge or replace
- [ ] Record a trip while signed out, then sign in to an account that already has trips on another phone. The screen
      shows *Trips on this phone*.
- [ ] *Merge with account*: the local trips and their vehicles upload, and the account's trips are added. No
      duplicates after another *Sync now*.
- [ ] On a second test install, *Replace with account's trips* asks for confirmation. Then only the account's trips
      are shown, and the local ones are deleted.
- [ ] Delete a vehicle on one phone. On the other, its trips show *Vehicle deleted on another device* and can be
      moved to another vehicle.

## 9. Release build (#74)
- [ ] The release APK installs, walks onboarding and connects to the dongle. Do not install it over the debug build:
      they have different application ids, so both can be installed at once.
- [ ] A drive records a trip, and sync works. This confirms the R8 keep rules on real hardware.
- [ ] If the release is signed with the real key, `apksigner verify --print-certs` shows the release certificate, not
      the shared debug key (`CN=Orange Box Debug`).
