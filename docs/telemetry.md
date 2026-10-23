# Telemetry: decoding, formulas and thresholds

Orange Box computes everything below from standard OBD-II data (SAE J1979 / ISO 15031-5). It is a
clean-room implementation: the numbers are **intentionally not** the legacy app's, and nothing here
comes from the legacy code. Source: `core:obd` (decoding, polling) and `core:telemetry` (metrics).

## 1. Decoding (mode 01)

`ObdPid` decodes 45 PIDs. Data bytes are named A, B, C… in the order received.

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

**Polling.** Fast PIDs are 0C, 0D, 10, 04, 11 and 5E, every 500 ms. Slow PIDs are 05, 2F, 42, 0F, 0B, 03, 46, 51
and 5C, every 10th tick (5 s). A `VehicleSample` holds the latest value of each PID. Polling stops when the
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
- overspeed ends below `overspeedKph − overspeedHysteresisKph` (117 km/h); overspeed time counts while the event is active;
- missing values and gaps of up to 5 s hold the state, and a longer gap (signal lost) ends it.

Start and end are logged under `OrangeBox/Telemetry` (`harsh_accel_start`, `harsh_accel_end` with its duration and peak, and the same for `harsh_brake` and `overspeed`).

| Metric | Rule | Default |
|---|---|---|
| Stationary | speed < `stationaryKph` | 1 km/h |
| Idling | stationary and RPM > 0 | – |
| Fuel cut | speed ≥ 20 km/h, RPM ≥ 1000, and throttle (or load) ≤ 8 %; with no throttle/load, PID 03 = 0x04 | 20 km/h, 1000 rpm, 8 % |
| Eco | moving, RPM ≤ 2500 and abs(accel) ≤ 1.5 m/s² | 2500 rpm, 1.5 m/s² |
| Overspeed | speed > 120 km/h; ends below 117 km/h (hysteresis) | 120 km/h, 3 km/h |
| Harsh acceleration | accel ≥ 3.0 m/s² (≈ 0.31 g), counted once per event | 3.0 m/s² |
| Harsh braking | −accel ≥ 3.5 m/s² (≈ 0.36 g), counted once per event | 3.5 m/s² |
| Speed zones | stationary, ≤30, ≤60, ≤90, ≤120, >120 km/h | `speedZones` |
| Coolant warning | ≥ 110 °C | 110 °C |
| Battery warnings | < 11.8 V low, > 15.0 V over-voltage | 11.8 / 15.0 V |
| Low fuel | < 10 % | 10 % |

All thresholds live in `TelemetryConfig` and can be changed.

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
