# Crash prompt (M7)

Orange Box replaces the legacy app's silent crash call and SMS (`ErsProcessor`, `ACTION_CALL` + `SmsManager`)
with a prompt the driver answers. **The app never calls or texts by itself.** It only opens the phone's
dialer (`ACTION_DIAL`) or SMS app (`ACTION_SENDTO smsto:`) with the number and text filled in, so the
manifest has no `CALL_PHONE`, `SEND_SMS` or `READ_CONTACTS` (`ManifestPermissionsTest` checks the merged manifest).

## Detection (`core:driving` `crash/`)

- **`MotionSource`**: the platform accelerometer (`TYPE_ACCELEROMETER`, `SENSOR_DELAY_GAME`, about 50 Hz,
  no permission). It is cold, so the sensor is registered only while the crash monitor collects it.
  `ReplayMotionSource` replays traces in tests.
- **`ImpactDetector`** works on the total magnitude in g (gravity included, so 1 g at rest). An impact is
  confirmed 250 ms after the magnitude first reaches the threshold (default **4 g**, 2.5–8 g), when:
  - at least 2 samples reached the threshold (a single spike is a knock on the mount);
  - the magnitude stayed above 60 % of the threshold for ≥ 30 ms (potholes are sharper and shorter);
  - there was no free fall (< 0.35 g for ≥ 120 ms) in the 800 ms before, because that is a phone dropped
    from its mount hitting the floor.

  A 30 s cooldown follows, so a tumbling phone can't raise a second prompt.
- **`CrashMonitor`** reads motion only **while driving**: while the OBD link is connected, and for 60 s
  after it drops, because a collision can cut the dongle's power. Detection must also be on in Settings
  (it is on by default). An impact only counts if the car was doing ≥ 15 km/h in the last 15 s, so a phone
  knocked around in a parked car doesn't raise a prompt.

The test traces in `core/driving/src/test/resources/motion/` (impact, pothole, knock, phone_drop) are
**synthetic** 50 Hz traces shaped after published crash-pulse and drop profiles. They are not recordings
from a real car. The real-device checklist asks for a phone-drop and pothole check in the car.

## Prompt (`feature:driving` `CrashPromptScreen`, drawn by the app over every screen)

- **Impact**: "Crash detected" with a countdown (default 30 s, 10–60 s in Settings) and **I'm OK** first.
  On timeout, the dialer opens with the emergency number ready; the driver still presses call.
  **Call** opens the dialer now. **SMS** opens the SMS app with
  `Orange Box: I may have been in a car accident. Last known location (HH:mm): https://www.openstreetmap.org/?mlat=…&mlon=…`,
  using the trip recorder's last fix and a keyless OSM link (no Google services).
- **Manual** (the dashboard's hazard button) shows the same screen without a countdown.
- While an impact prompt is up, the activity may show over the lock screen.
- **Not on screen**: an alarm-category, high-priority notification counts down (a chronometer) with
  *I'm OK*, *Call* and *SMS location* actions, plus a full-screen intent where the platform allows it.
  On timeout it changes to "No answer. Tap to open the dialer". Android does not let a backgrounded app
  open the dialer by itself, so a tap is needed.

The emergency number is the dashboard setting (country default: 112, 911, 999 and so on). The SMS
recipient is the emergency contact from Settings → Crash detection (M8 #60, see `docs/settings.md`); without one, the SMS app asks for it.

## Debug

Simulator → **Crash prompt (M7)** → *Inject impact* raises the prompt as a 6.5 g impact, skipping the
sensor and the speed check.
