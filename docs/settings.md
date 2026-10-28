# Settings (M8)

Settings is a single Material 3 screen (default components: list items, switches, segmented buttons,
sliders). Everything is stored locally in DataStore (`AppPreferencesRepository`); nothing needs a
server or a Google service.

Top to bottom:

| Section | What | Default |
|---|---|---|
| Car and dongle | **Vehicles** list (#61), OBD dongle, permissions, start when the dongle connects | — / off |
| | **Check the car when a drive starts**: M6's "Car Diagnosing…" scan and prompt | on |
| Driving dashboard | bottom-left button (hazard with emergency number, or quick launcher), overspeed warning, speedometer offset (#210), show dashboard automatically | hazard, country number, 120 km/h, 0 %, off |
| Units (#58) | distance and speed, fuel economy, temperature, petrol blend (#208) | from the device country (E20 in India) |
| Driving alerts (#59) | overspeed, harsh accel/brake, long idling, check-engine; tone; voice | on, on, on (3 min), on; tone on; voice off |
| Crash detection (M7 + #60) | detection on/off, impact threshold, countdown, emergency contact | off, 4.0 g, 30 s, none |
| Server (advanced) (#62) | backend mode Off / Fake / Remote, https base URL | Off |
| About | version, commit, CI run | |

## Units (#58)
Trips, samples and telemetry stay **stored in metric**; only what is shown is converted
(`UnitPreferences` in `core:model`). Until the user picks units, the defaults follow the device
country (`DeviceCountry`): US/PR → mi, mph, mpg (US), °F; LR/MM → mi, mpg (US), °C; GB → mi, mpg (UK),
°C; everywhere else metric. Converted: dashboard clusters and detail tiles, the Home mini dashboard,
trip list / detail / charts / stats, the recording notification and the overspeed slider label. The
overspeed threshold itself is stored and stepped in km/h. Warnings (coolant, overspeed) always use
the metric values.

**Petrol blend (#208):** E0 / E10 / E20 for the fuel estimate (litres, km/L, CO₂); see `docs/telemetry.md`.
Until picked it follows the device country (E20 in India, E0 elsewhere).

## Speedometer offset (#210)
Orange Box shows the car's true speed (PID 0D, which matched GPS within about 1 % in RDF2). Car speedometers are
built to read high, typically 3–8 %, so the dashboard reads lower than the car's own gauge. The setting (0–10 %,
default 0) shows the dashboard and Home card speed that much higher. It is display only: the overspeed warning,
alerts, trips, stats and exports keep the true speed.

## Driving alerts (#59)
`AlertMonitor` (app lifetime, active only while connected) feeds live telemetry to `AlertPolicy`
(pure, unit-tested):

- overspeed, harsh acceleration, harsh braking: when the event starts; cooldown 60 s / 10 s / 10 s;
- long idling: once after 3 min idling, again only after the car moved;
- check-engine lamp: once per connection.

`AndroidAlertOutput` plays the tones with `SoundPool` as navigation-guidance audio, and speaks the
alert with the platform `android.speech.tts.TextToSpeech` when voice is on (created on first use). It uses
whichever text-to-speech engine is already the phone's default (no engine package is forced, and there is no
TTS SDK, cloud voice or Play Services dependency); the manifest's `<queries>` entry for
`android.intent.action.TTS_SERVICE` is what lets Android 11+ see that engine. With no engine on the phone,
voice stays silent and the tones still play. The tones in
`app/src/main/res/raw/alert_*.wav` are synthesized by `tools/gen_alert_sounds.py` (sine waves); no
CarooLive/Pokevian sound is used.

## Emergency contact (#60)
"Choose contact" opens the system phone picker (`ACTION_PICK`, `Phone.CONTENT_TYPE`). The picker
grants read access to the chosen row only, so there is **no READ_CONTACTS permission**
(`ManifestPermissionsTest` checks). The number can also be typed. Numbers are normalized and
validated by `CrashPreferences.normalizeContactNumber` (separators dropped, optional `+`, 3–15
digits). The crash prompt's SMS button texts this contact ("Text my location to Asha"); the call
button always dials the dashboard's emergency number. Nothing is sent automatically.

## Vehicles (#61)
Settings → Vehicles lists every stored car; the active one (used for trips and the fuel estimate)
has its radio selected. Tap a row to make it active; edit or delete with the row icons (delete asks
first; recorded trips are kept). Deleting the active car makes the first remaining one active. Adding
a car from the list only makes it active if there was none. Onboarding and the vehicle form keep
working on the active car as before.

## Server (#62)
Off is the default and the app works fully offline. Remote only accepts an `https://` base URL
(`BaseUrlValidator`); cleartext URLs are rejected and not stored.

## Screenshots
Robolectric screenshots are written by `SettingsScreenshotTest` to
`feature/settings/build/outputs/settings/` and by `VehicleListTest` to
`feature/setup/build/outputs/vehicles/`.
