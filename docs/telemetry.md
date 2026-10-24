# Telemetry: decoding, formulas and thresholds

Orange Box computes everything below from standard OBD-II data (SAE J1979 / ISO 15031-5). It is a
clean-room implementation: the numbers are **intentionally not** the legacy app's, and nothing here
comes from the legacy code. Source: `core:obd` (decoding, polling) and `core:telemetry` (metrics).

## 1. Decoding (mode 01)

`ObdPid` decodes 46 PIDs. Data bytes are named A, B, C… in the order received.

| PID | Name | Formula | Unit |
|---|---|---|---|
| 03 | Fuel system status (bank 1) | A (bit code, 0x04 = open loop / decel fuel cut) | code |
| 04 | Calculated engine load | A·100/255 | % |
| 05 | Coolant temperature | A − 40 | °C |
| 06–09 | Short/long fuel trim bank 1/2 | A·100/128 − 100 | % |
| 0A | Fuel pressure | 3·A | kPa |
| 0B | Intake MAP | A | kPa |
| 0C | Engine speed | (256A + B)/4 | rpm |
| 0D | Vehicle speed | A | km/h |
| 0E | Timing advance | A/2 − 64 | ° |
| 0F | Intake air temperature | A − 40 | °C |
| 10 | MAF | (256A + B)/100 | g/s |
| 11 | Throttle position | A·100/255 | % |
| 14 | O2 sensor bank 1 sensor 1 voltage | A/200 (B = short-term trim, unused) | V |
| 1F | Run time since start | 256A + B | s |
| 21 | Distance with MIL on | 256A + B | km |
| 22 | Fuel rail pressure (rel.) | 0.079·(256A + B) | kPa |
| 23 | Fuel rail gauge pressure | 10·(256A + B) | kPa |
| 2C | Commanded EGR | A·100/255 | % |
| 2D | EGR error | A·100/128 − 100 | % |
| 2E | Commanded evap purge | A·100/255 | % |
| 2F | Fuel tank level | A·100/255 | % |
| 30 | Warm-ups since clear | A | count |
| 31 | Distance since clear | 256A + B | km |
| 33 | Barometric pressure | A | kPa |
| 3C, 3D | Catalyst temp B1S1, B2S1 | (256A + B)/10 − 40 | °C |
| 42 | Control module voltage | (256A + B)/1000 | V |
| 43 | Absolute load | (256A + B)·100/255 | % |
| 44 | Commanded equivalence ratio (λ) | 2·(256A + B)/65536 | – |
| 45 | Relative throttle | A·100/255 | % |
| 46 | Ambient temperature | A − 40 | °C |
| 47 | Absolute throttle B | A·100/255 | % |
| 49, 4A | Accelerator pedal D, E | A·100/255 | % |
| 4C | Commanded throttle actuator | A·100/255 | % |
| 4D | Time with MIL on | 256A + B | min |
| 4E | Time since codes cleared | 256A + B | min |
| 51 | Fuel type | A (1 gasoline, 4 diesel, 5 LPG, …) | code |
| 52 | Ethanol % | A·100/255 | % |
| 5A | Relative pedal position | A·100/255 | % |
| 5B | Hybrid battery remaining | A·100/255 | % |
| 5C | Engine oil temperature | A − 40 | °C |
| 5E | Engine fuel rate | (256A + B)/20 | L/h |

**Supported PIDs.** PIDs 00, 20, 40, 60, 80, A0 and C0 return 32-bit bitmaps. Bit 31 means PID base+1, …, and bit 0
means "the next bitmap exists". The chain is read once per connection, and only supported PIDs are polled.

**Frames.** With headers off, a CAN multi-frame answer is printed as a byte count (`014`) followed by `0:`, `1:` …
lines. These are joined and cut to the count. Every line of a non-CAN answer is its own message. An incomplete
or garbled multi-frame answer is dropped.

**DTCs (03 stored / 07 pending / 0A permanent, 04 clear).** On CAN, `4x <count> A1 B1 …`. On non-CAN, each line is
`4x A1 B1 A2 B2 A3 B3`, padded with `00 00`. A code is `[PCBU][A>>4 & 3][A & F][B]`. MIL and the code count come
from 01 01, A bit 7 / A & 0x7F.

**VIN (09 02).** On CAN, one message `49 02 01` + 17 ASCII bytes. On ISO/KWP, five lines `49 02 <seq>` + 4 bytes,
with 3 leading pad bytes. The VIN must be 17 characters from `A–H J–N P R–Z 0–9`. The ISO 3779 check digit
(position 9) is reported as `checkDigitValid` but is not enforced, because it is only mandatory in North America.

**Polling.** Fast PIDs are 0C, 0D, 10, 04, 11, 5E and 49 (accelerator pedal, for the Detailed Info grid), every
500 ms. Slow PIDs are 05, 2F, 42, 0F, 0B, 03, 46, 51 and 5C, plus 33, 06, 07, 0A, 21 and 14 for the Detailed Info
grid, every 10th tick (5 s). Slow ticks also read 01 01 (MIL + stored DTC count) when the car announces it; it lands
in `VehicleSample.monitor`. A `VehicleSample` holds the latest value of each PID. Polling stops when the
connection leaves Connected and restarts, with a fresh bitmap read, after reconnecting.

## 2. Derived metrics (`TelemetryEngine`)

Intervals between consecutive samples are integrated with the trapezoid rule. Interval-based state times
(idle, eco, …) use the state at the start of the interval. Gaps over 5 s (signal loss) are skipped.

**Fuel flow (L/h)**, in order of preference:
1. PID 5E engine fuel rate.
2. MAF: `flow = MAF[g/s] · 3600 / (AFR_stoich · λ · ρ_fuel[g/L])`, where λ comes from PID 44 (default 1).
3. Speed-density, when there is no MAF:
   `MAF = RPM/120 · V_d[m³] · VE · (MAP[Pa] · M_air / (R · T_intake[K])) · 1000`,
   with M_air = 0.0289647 kg/mol, R = 8.314462618 J/(mol·K), and V_d, VE from the config.

Flow is 0 during a fuel cut.

| Fuel | AFR_stoich | ρ (g/L) | CO₂ (g/L) |
|---|---|---|---|
| Gasoline (default) | 14.7 | 745 | 2310 |
| Diesel | 14.5 | 832 | 2640 |
| LPG | 15.5 | 540 | 1640 |
| Ethanol/E85, methanol | 9.8 | 785 | 1610 |

The CO₂ factors are approximate tank-to-tailpipe values (EU/EPA order of magnitude). The fuel comes from PID 51
if the car reports it, otherwise from `TelemetryConfig.defaultFuelType`.

**Economy.** Instant economy is `flow / speed · 100` L/100 km, and only above 5 km/h. Trip economy is
`fuel / distance · 100`, from 0.1 km of distance onwards. Distance is `∫ speed dt`, fuel is `∫ flow dt`,
and CO₂ = fuel · factor.

**Acceleration** is `Δspeed / 3.6 / Δt` (m/s²) between the newest sample and the latest one at least
`accelWindowMillis` (1 s) older, using **sample timestamps** (the time SPEED was requested), not arrival
time. Samples whose timestamp isn't after the last accepted one (duplicates, late or out-of-order) are
ignored.

**Events** (harsh acceleration, harsh braking, overspeed) use hysteresis so jitter, a stale repeated
value or a short gap can't split one sustained event:
- an event starts when the value reaches its threshold, and no sooner than `eventRearmMillis` (2 s) after the previous event of that kind ended;
- a harsh event ends only after |accel| stays below `harshReleaseFraction` (50 %) of its threshold for `eventReleaseHoldMillis` (1 s);
- overspeed ends below `overspeedKph − overspeedHysteresisKph` (threshold − 3, so 117 km/h at the default 120); overspeed time counts while the event is active;
- missing values and gaps of up to 5 s hold the state, and a longer gap (signal lost) ends it at the
  timestamp of the **last sample before the gap** (`reason=signal_lost`), see below.

Start and end are logged under `OrangeBox/Telemetry` (`harsh_accel_start`, `harsh_accel_end` with its duration, peak and the value it ended at (for `overspeed_end`, the speed), and the same for `harsh_brake` and `overspeed`).
`overspeed_end` also repeats the release speed as `speed_kmh`, for example:

```
OrangeBox/Telemetry overspeed_end duration_s=2.00 peak=130.00 value=116.00 speed_kmh=116.00 unit=km/h reason=released
```

**Signal-lost duration matches the trip's event time.** Trip totals never integrate across a gap over
5 s, so overspeed time stops at the last sample before a stall. Up to build 0.1.0.38 the `signal_lost` end
was stamped with the first sample *after* the gap, so the logged `duration_s` included the stall. In the M4
retest a host-load stall of about 16.5 s during Highway logged `overspeed_end duration_s=57.07
reason=signal_lost`, while the readout showed 40.6 s. Overspeed started at 12:02:15.25 (logcat), the last
pre-stall sample was at about 12:02:55.85 (start + 40.6 s), and polling resumed at about 12:03:12.3
(start + 57.07 s, with Choreographer skipping 857 frames just before). 57.07 − 40.6 = 16.47 s, which is the
stall. The readout was right. The log line now closes at the last pre-stall sample, so for an event cut
by a stall `duration_s` equals the event time it added to the trip (`SignalLostEventTest`). The same applies
to `harsh_accel_end` and `harsh_brake_end`. The rearm delay is measured from that time too, so if the car is
still over the limit when polling resumes, a second event starts on that sample and is counted.
`value` / `speed_kmh` on a `signal_lost` end is the last value before the gap.

In debug builds the Simulator's "App readout (debug)" card shows the trip's cumulative eco time as
`Eco driving (trip): 91.0 s`, to compare with the eco column of the goldens below.

| Metric | Rule | Default |
|---|---|---|
| Stationary | speed < `stationaryKph` | 1 km/h |
| Idling | stationary and RPM > 0 | – |
| Fuel cut | speed ≥ 20 km/h, RPM ≥ 1000, and throttle (or load) ≤ 8 %; with no throttle/load, PID 03 = 0x04 | 20 km/h, 1000 rpm, 8 % |
| Eco | moving, RPM ≤ 2500 and abs(accel) ≤ 1.5 m/s² | 2500 rpm, 1.5 m/s² |
| Overspeed | speed > threshold; ends below threshold − 3 km/h (hysteresis) | Settings → Driving dashboard, 40–200 km/h in 5 km/h steps, default 120; hysteresis 3 km/h |
| Harsh acceleration | accel ≥ 3.0 m/s² (≈ 0.31 g), counted once per event | 3.0 m/s² |
| Harsh braking | −accel ≥ 3.5 m/s² (≈ 0.36 g), counted once per event | 3.5 m/s² |
| Speed zones | stationary, ≤30, ≤60, ≤90, ≤120, >120 km/h | `speedZones` |
| Coolant warning | ≥ 110 °C | 110 °C |
| Battery warnings | < 11.8 V low, > 15.0 V over-voltage | 11.8 / 15.0 V |
| Low fuel | < 10 % | 10 % |

### Dashboard state (#99)

`InstantTelemetry` also carries what the driving dashboard shows. `TelemetrySnapshot.values` passes the raw PIDs
through for the Detailed Info grid.

| Field | Rule | Default |
|---|---|---|
| `ecoLevel` | Only while moving (else 0). Petrol/LPG: engine load (PID 04) < 50 → 1, < 80 → 2, < 90 → 3, else 4. Diesel: boost = MAP − baro (PID 0B − 33, baro 101.3 kPa if missing) < 50 / < 80 / < 100 kPa. 0 without the PID | `ecoLoadThresholds` 50/80/90 %, `ecoBoostThresholdsKpa` 50/80/100 |
| `coolantWarning` | coolant ≥ 100 °C (the dashboard lamp; `Warning.COOLANT_OVERHEAT` stays at 110 °C) | `coolantWarningC` |
| `batteryWarning` | engine running (RPM > 0) and voltage outside 13.2–14.8 V | `chargingVoltsRange` |
| `instantKmPerL` | fuel cut → 30 (full bar); below 5 km/h → 0; else speed / fuel flow, capped at 30; null without speed or flow | `instantKmPerLMax` |
| `milOn`, `dtcCount` | PID 01 01 bit 7 and A & 0x7F; null if not read | – |
| `TripTotals.economyKmPerL` | distance / litres once ≥ 0.1 km and some fuel is burned (the dashboard caps it at 99.9) | – |

The panel glows are mapped in `core:dashboard-ui` (`DashboardStateMapper`): the RPM panel is green during fuel cut,
else tinted green while eco time is counted (the `eco` rule: moving, rpm ≤ 2500 and |accel| ≤ 1.5 m/s²), shaded
yellow when that happens under moderate or heavy load (`ecoLevel` ≥ 2), and untinted otherwise. The ECO lamp follows
the same rule (#138), so it is never lit while no eco time is being counted; the speed panel is red during overspeed, else orange during a
harsh accel/brake event (priority overspeed > harsh > none). Glows animate over 300 ms unless "Remove animations" is on.

All thresholds live in `TelemetryConfig` and can be changed. The overspeed threshold is also a user setting
(`DashboardPreferences.overspeedKph`, Settings → Driving dashboard → Overspeed warning): `LiveTelemetry` feeds it to
`TelemetryEngine.overspeedKph`, which retunes `EventDetector` (start above N, end below N − 3) before the next sample.
A change during an active event keeps the event until speed drops below the new release point. The dashboard's red
speed glow follows the same event, so it uses the setting too. The golden drive numbers below use the default 120 km/h.

## 3. Scripted drives (debug Simulator)

`DriveScript` provides idle (120 s), city stop-and-go (3 × 0→50→0), highway (0→100→130→100) and harsh
(0→60 in 4 s, 60→0 in 3 s, twice). RPM comes from a 5-speed gear model. The throttle follows the
acceleration demand and is 0 when decelerating, so coasting above 20 km/h is a fuel cut. The simulator's toy
engine is a 1.6 L petrol: `MAF ≈ RPM/120 · 1.6 · 1.18 · VE`, with VE from 0.2 at closed throttle to 1.0 wide open.

`DriveScriptGoldenTest` replays each script through the simulated ELM327 and the real decoding path every
0.5 s and compares the trip totals with recorded golden values:

| Script | km | L | L/100 km | idle s | fuel-cut s | eco s | overspeed s | harsh accel/brake |
|---|---|---|---|---|---|---|---|---|
| idle | 0 | 0.0275 | – | 120 | 0 | 0 | 0 | 0/0 |
| city | 1.211 | 0.116 | 9.6 | 31.5 | 13.5 | 91 | 0 | 0/0 |
| highway | 4.787 | 0.245 | 5.1 | 0.5 | 15 | 0 | 46.5 | 0/0 |
| harsh | 0.451 | 0.044 | 9.7 | 11 | 4 | 19 | 0 | 2/2 |

The distances also match the analytic integral of each script to within 1.5 % (PID 0D is whole km/h).

**Playback on a slow device (#120).** The golden numbers are right for the eco rule above, so they stay. On an
API 25 ARM emulator the tester saw City eco 104.7–106.9 s, Harsh 20.3 s, Highway 2 s and overspeed 50.05 s.
Every one is the golden stretched by ≈ 7–9 % in wall time: the debug `DriveScriptPlayer` used to advance
script time by a fixed 250 ms per loop, so each overrunning `delay` slowed the whole drive down, and every
acceleration with it. City's 8 s, 1.74 m/s² stops then ran at ≈ 1.5–1.6 m/s², so with sampling jitter part
of each stop fell under the 1.5 m/s² eco limit. Replaying the scripts with a 7–10 % stretch reproduces the
device numbers (City 98–109 s, Harsh 20–22 s, Highway coast up to 2 s). The player now reads script time and
the coast-down from a monotonic clock, so a late tick can't stretch the drive. `SlowDevicePlaybackTest` runs
the whole debug path with an 8 % lagging clock and 0.5–1.1 s polling and checks City 85–95 s, Harsh 17–21.5 s
with 2/2 harsh events, Highway's duration and overspeed, and at most one eco sample on the coast to a stop.

Expect a few seconds of scatter on a device even so. The 1 s acceleration window and the interval's starting
state mean one poll interval at a phase change can go either way. For example, the first sample of the
Highway coast-down is eco: its window still half covers the 100 km/h cruise, and the RPM is already under 2500.

**Stale simulator values (#142).** On build 0.1.0.35 the API 25 ARM emulator still read City eco low
(87.4–88.0 s on clean runs, mean ≈ 88.4) and one Harsh brake peak at +19 %. Telemetry time already comes
from sample timestamps: `PidPoller` stamps each sample when SPEED is requested, and `TelemetryEngine` and
its event detectors use only those stamps, never arrival time. The skew was in the simulator. The player
refreshed `SimulatorState` once per tick (100 ms, overrunning on a slow device), and the simulated dongle
answered from that state, so a value could be up to a tick older than the stamp on its sample.
The windowed acceleration then came out too high or too low by roughly accel × skew / window.
- City's 10 s, 1.39 m/s² pull-aways sit only 0.11 m/s² under the 1.5 m/s² eco limit, so skew (plus whole-km/h
  speed rounding) pushed some of their samples over the limit, cutting eco time.
- The same skew inflated the 5.56 m/s² braking peaks.
- A JVM replay with 0.5–1.1 s polling and a 250 ms effective player tick reproduces it: City eco
  81–93 s (mean 88.9 s) and Harsh brake peaks up to +22 %.

The dongle now evaluates the playing script (or the coast-down) at the moment of each read
(`SimulatorController.readState()`); the player's tick only refreshes the Simulator screen. With the same
replay, City eco is 85.9–95.5 s over 150 runs (mean 90.7 s, 1st–99th percentile 86.1–94.6 s), Harsh eco
17.0–22.2 s, and brake peaks stay within +4 %. `SlowDevicePlaybackTest` covers this with a 250 ms tick and
10 ms per PID read.

The scatter that remains is inherent to polling. The tick period varies between 0.5 and 1.1 s, PID 0D is whole
km/h, and each eco edge falls somewhere inside one poll interval. City has six eco edges, each with a
pull-away that is barely under the eco limit. An exact 1 s window (speed interpolated at t − 1 s) was tried
and rejected, because it lets the ±1 km/h rounding through unsmoothed and biased City eco low again (mean 88.9 s).
Hence the City eco tolerance below is ±5 s, and Harsh eco is ±3.5 s.

The legacy app's "eco speed" is a different metric (time between 60 and 80 km/h,
`DEFAULT_ECO_MIN_ECO_SPEED`/`DEFAULT_ECO_MAX_ECO_SPEED`, computed inside its closed OBD library). By design
it isn't reproduced here (see the note at the top).

### On-device tolerances

On a real device or a slow emulator, a scripted drive passes when it is within these bounds of the golden
values above. They cover the sampling scatter described above. The playback-clock bug (#120) and the stale
simulator values (#142) were systematic drifts, and these bounds would not have hidden them: a mean shift
shows up as runs that all land on one side of the golden.

| Metric | Tolerance |
|---|---|
| Eco time, City | ±5 s of golden (polling scatter, see #142 above) |
| Eco time, Harsh | ±3.5 s of golden |
| Overspeed duration (Highway) | ±2 s of golden |
| Harsh accel/brake peaks | +15 % / −10 % of nominal |
| Event counts (harsh accel/brake, overspeed) | exact |
| Highway eco | 0–2 s, all from the coast-down |

Reference run, 0.1.0.16 on the API 25 ARM emulator. Everything is inside the bounds:

| Metric | Golden / nominal | Measured | Deviation |
|---|---|---|---|
| City eco | 91 s | 90.4 s | −0.6 s |
| Harsh eco | 19 s | 19.7 s | +0.7 s |
| Highway overspeed | 46.5 s | 46.05 s | −0.45 s |
| Highway eco | 0–2 s | 1.4 s | in range |
| Harsh brake peaks | 5.56 m/s² | 5.96 / 5.80 m/s² | +7.2 % / +4.3 % |
| Harsh accel peaks | 4.17 m/s² | 4.63 / 4.24 m/s² | +11.0 % / +1.7 % |
