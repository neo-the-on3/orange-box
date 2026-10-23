# Legacy OBD UI study (CarooLive → Orange Box)

Status: study for M4–M6, written 2026-10-24. Sources: `Pokevian/caroolive-app` @ `c358d9c` (layouts, styles, dimens, fragment code; bitmap sizes and colours measured from the PNGs), the repo screenshots, and two device screenshots Kiran supplied.

## 1. Rules (Kiran, 2026-10-24)

1. **OBD-data screens mimic the legacy UI**: same layout, information hierarchy, panel and gauge styles, colour use, type scale and flow. "Same or better": we may improve readability, portrait support, accessibility and theming, and drive panel glows from real state.
2. **All artwork is recreated**: our own vector drawables or Compose Canvas drawing. Never copy, trace-export or embed any Pokevian image, drawable, font, icon or sound. Matching geometry and colours read from XML or measured from the reference is fine.
3. **Trip list and Settings use default Material 3 components**, not the legacy look.
4. **Everything else is modern Material 3** (onboarding, permissions, pairing, vehicles, dialogs that aren't OBD readouts), with accessibility (48 dp touch targets, content descriptions, font scaling), edge-to-edge and dark-theme handling throughout.

Reference images: [`legacy-reference/`](legacy-reference/README.md) (committed, Apache-2.0, cropped). Box-only extras under `/workspace/analysis/ui-reference/`:
- `kiran-dashboard-main.jpg` and `kiran-dashboard-detail-grid.jpg`: **primary reference** for M4.
- `legacy-screenshots/`: all 12 repo screenshots.
- `diagnostic_composite_land.png`: an approximate render of the diagnostics screen built from the legacy layer bitmaps (no screenshot exists).

**Why not a legacy build or a layoutlib render?** The legacy build needs JDK 8, Gradle 3.3, AGP 2.3.3, compileSdk 22 and build-tools 25.0.0. Its dependencies resolve from JCenter (sunset), and it bundles closed jars (`lib-obd2-android.jar`, `lib-blackbox2.jar`, FingerPush, GCM). Getting it to build within 45 min isn't realistic, and we couldn't install it ourselves anyway. Rendering with layoutlib or Paparazzi needs the support-library-22 custom views and bitmap layer lists. The screenshots cover the dashboard, so specs below come from XML and code, with one composite render for diagnostics.

## 2. Inventory: legacy screens that present OBD data

| # | Legacy screen | Files | Native? | Orange Box | Milestone |
|---|---|---|---|---|---|
| A | **Driving dashboard**: clusters (RPM/speed), centre indicators, FUEL/TRIP cards | `DrivingActivity`, `ObdClusterFragment`, `TripClusterFragment`, `IndicatorFragment`, `BottomFragment`; `layout{,-land}/activity_driving.xml`, `fragment_driving_{obd,trip,indicator,bottom}.xml`; `values{,-land,-sw360dp-land}/dimens.xml`; `styles.xml` (`cluster*Text`) | yes | `feature:driving` → `DashboardScreen` | **M4** (#38) |
| B | **Detailed Info.** pager (3 pages × 9 tiles) | `DrivingDetailInfoFragment`, `ValueView`, `fragment_driving_detail_info{,_page}.xml`, `widget_value{,_large}.xml` | yes | `DashboardScreen` detail mode | **M4** |
| C | Dashcam preview overlay with `SpeedMeter` (text speed, km/h) | `BlackboxPreview*`, `SpeedMeter`, `blackbox_preview_*.xml` | yes | **dropped** (dashcam) | none |
| D | Floating head / popup (speed bubble over other apps) | `floating_head.xml`, `PopupActivity` | yes | **dropped** (overlay) | none |
| E | **Diagnostics** (10 DTC-group lamps, car illustration, message bar, Clear) | `DiagnosticActivity`, `DiagnosticFragment`, `DtcParser`, `fragment_dignostic.xml`, `layer_diagnostic.xml`, `btn_dtc01..10` | yes | `feature:diagnostics` → `DiagnosticsScreen` | **M6** (#48, #49) |
| F | DTC group detail dialog (code list + group text) | `DiagnosticFragment.DiagnosticDetailFragment`, `dialog_diagnostic_detail.xml` | yes | bottom sheet in `DiagnosticsScreen` | **M6** (#48) |
| G | **MIL / diagnosing prompt** at drive start ("Car Diagnosing…", scan + progress, "No Troubles" / "DTC Warning") | `DiagnosticProcess`, `DiagnosticDialogFragment`, `dialog_diagnostic.xml` | yes | `DiagnosingDialog` | **M6** (#50) |
| H | Clear-DTC confirmation | `dialog_dtc_reset.xml` | yes | M3 `AlertDialog` with legacy copy | **M6** (#49) |
| I | Trip list (per-trip metrics card) | `TripMonitorFragment` → **WebView** | no (server page) | Material 3 list (Kiran) | M5 (#42) |
| J | Trip Stats / Driving Pattern / Car Status | WebView (server) | no | local stats, Material 3 with legacy hierarchy (decision D3) | M5 (#46) |
| K | Main weekly score ring / points | `MainFragment`, `WeeklyRecordFragment` (server data) | native shell, server data | Remote-only (M9), not OBD values | M9 |

Only A, B, E, F, G and H are native OBD-value screens, so they are the mimic scope.

## 3. Shared visual language (dashboard and diagnostics)

**Background:** pure black `#000000` with a dark "bezel" shape (`bg_main`: 640×335 dp landscape, anchored bottom-centre). Its fill is `#232323`, with a curved top edge rising to a central arch where "Preview" sat, and a lighter rim `#545454` → black. Portrait uses a 483×48 dp arch (`bg_main_portrate`) over a tiled dark pattern.

**Cluster panel** (`bg_left_cluster` and similar: 234×232 dp land, 349×165 dp portrait), measured from the bitmap at xhdpi:

| Ring (from outside) | Width | Colour |
|---|---|---|
| Outer highlight | ~3 dp | white, 30→75 % alpha |
| Bezel | ~4 dp | `#4a4a4a` → `#242424` (top lighter `#515151`) |
| Dark band | ~15 dp | `#0a0a0a` |
| Screen stroke | 1 dp + 0.5 dp | `#f1f1f1` then `#969696` (idle); state colour when tinted |
| Screen | n/a | diagonal gradient `#494949` (upper-left) → `#000000` (lower-right), centre ≈ `#1f1f1f`, a soft diagonal gloss band and a faint dot grid |

- Corner radii: outer ≈ 32 dp, screen ≈ 26 dp.
- **Eco tint** (left panel, `ObdClusterFragment.setClusterBackground`). The stroke becomes bright and the screen gets a radial glow from the centre:
  - green: stroke `#b6ffbd`, glow `#1f611f`;
  - yellow: `#ffe96e` / `#77691f`;
  - red: `#ff8686` / `#621f1f`.

**Centre cards** (`bg_center_connect`, `bg_center_indicator`): fill `#1a1a1a`, 1 dp rim `#393939` with a faint top highlight, corner ≈ 3 dp.

**Value tile** (`center_infor` 9-patch, detail grid): fill `#1b1b1b` with a 1 dp `#3a3a3a` border, corner ≈ 2 dp. Large tile (`bg_value_dark_large_*`): black with a radial glow at the bottom-centre; `off` is a grey-white glow, `on` (warning) a red glow. Kiran's screenshot shows the newer build's **blue glow** for normal state.

**Type** (legacy uses dp, not sp; system Roboto, no custom font):

| Role | Legacy size / weight | Colour |
|---|---|---|
| Cluster meter (RPM, speed) | 60 dp (land 62 sp, sw360-land 75 dp), bold, right-aligned, `includeFontPadding=false`, −12 dp to the unit | `#ffffff` |
| Cluster meter unit (`rpm`, `km/h`) | 18 dp regular | `#ffffff` |
| Cluster value (km/L, km, time) | 20 dp bold | `#ffffff` |
| Cluster unit | 12 dp | `#ffffff` |
| Cluster header (FUEL-CUT, OVERSPEED) | image text ≈ 14 dp bold caps | on `#18f800` / `#f80000`, off white at ~35 % alpha |
| Indicator caption (BATTERY, IDLING…) | ≈ 10 dp bold caps | `#f8f8f8` |
| Tile title | 12 dp | `#cccccc` |
| Tile value | 22 dp bold | `#ffffff` |
| Tile unit | 12 dp, min width 40 dp | `#cccccc` |
| Fuel bar scale | 6 dp | `#bcbcbc` |

Placeholders: int `---`, float `--.-`, time `-:--:--`, short time `--:--`, count `--`.

**Lamp colours** (measured):
- MIL on `#f80028`; OBD on `#80d8f0`; both off `#686868`.
- Fuel-cut on `#18f800`; overspeed on `#f80000`.
- Accel, brake and idling on `#f89018`, with icon body `#f8f8f8`.
- Eco: green `#10c800`, yellow `#f8e000`, red `#f80000`.
- Coolant and battery: normal `#00a0e0`, warning `#f89018` (indicator) or `#e00000` (tile icon).
- Diagnostics lamps: ok `#0cb0dc`, fault `#f06860`, pressed `#b02420`.
- Brand green `#8cbc4c` (refresh button, app bars); accent `#33b5e5`; detail button on `#0448c0`.

## 4. Screen A: driving dashboard (M4)

### 4.1 Layout: landscape (primary; Kiran's `kiran-dashboard-main.jpg`)

```
┌──────────────────────────── bezel (black bg, #232323 arch) ───────────────────────────┐
│  ┌──── LEFT CLUSTER 234×232 ────┐ ┌ CENTRE 110 ┐ ┌──── RIGHT CLUSTER 234×232 ────┐  │
│  │ [drop] FUEL-CUT              │ │ [MIL] [OBD] │ │ [gauge] OVERSPEED              │  │
│  │                        1698  │ ├─────────────┤ │                            59  │  │
│  │                         rpm  │ │ FUEL        │ │                          km/h  │  │
│  │                              │ │   8.9 km/L  │ │                                │  │
│  │ COOLANT  BATTERY   ECO       │ │ ▓▓▓░░ 0..30 │ │ ACCEL.   BRAKE    IDLING       │  │
│  └──────────────────────────────┘ │   0.2 L     │ └────────────────────────────────┘  │
│                                   ├─────────────┤                                     │
│                                   │ TRIP 2.0 km │                                     │
│                                   │    0:04:29  │                                     │
│                                   └─────────────┘                                     │
│ (hazard)        [app]      [ Detailed Info. ]      [call]                    (power)  │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

- Clusters are 234×232 dp, bottom margin 68 dp, 6 dp from the centre column. The centre column is 110×214 dp with a 72 dp bottom margin. The total width of 590 dp fits a 640 dp-wide landscape window.
- **Left cluster:** header lamp "FUEL-CUT" (margin 48/34 dp), RPM meter right-aligned (padding-right 33 dp; unit margin-right 38 dp), and a status row of COOLANT, BATTERY and ECO icons with captions.
  - In the newer build (Kiran's shot) the status icons move into the cluster. The repo build used the centre table instead.
  - The repo build also had the instant fuel bar (160×10 dp, track `#1e253b`, fill `#819ff7`, radius 4 dp, max 30, ticks 0/10/20/30) and the trip km/L inside this cluster. The newer build moves both into the centre FUEL card.
- **Right cluster:** header lamp "OVERSPEED" (margin 41/34 dp), speed meter right-aligned (padding-right 49 dp; unit 54 dp), and a status row of ACCEL., BRAKE and IDLING. The repo build instead showed distance (pin icon) and run time (clock icon) at the bottom of this cluster.
- **Centre column (newer build):**
  - Connect card 110×28 dp with MIL (left) and OBD (right) chips.
  - **FUEL** card (header icon + "FUEL"; trip average km/L 20 dp bold; instant economy bar 0–30; fuel used in L).
  - **TRIP** card (header icon + "TRIP"; distance km; elapsed `H:MM:SS`).
  - Repo build: a 110×167 dp 2×3 table of BATTERY/IDLING, COOLANT/ACCEL., ECO/BRAKE icons.
- **Bottom bar** (bottom margin 2 dp). Hazard (red triangle in a round black button) sits at the far left and power at the far right, each 160 dp from the centre button. Two square launchers sit 12 dp either side of the centre button, which reads "Detailed Info." ("More Info." in the repo build). The centre button is a toggle (checked = blue `#0448c0` fill).

### 4.2 Layout: portrait (legacy `layout/activity_driving.xml`)

The portrait layout is a vertical stack:
1. Top arch.
2. A 338 dp block with the **left cluster** (now wide, 349×165 dp) above the **right cluster**.
3. The indicator strip: a 63×100 dp connect card (OBD above MIL) plus a 248×100 dp 2×3 indicator table.
4. The bottom bar: navi, ERS, Detailed Info., DTC, exit.

Weighted spaces separate the blocks. Orange Box keeps this order and also places the FUEL and TRIP cards side by side under the clusters (the newer centre cards).

### 4.3 Data and state mapping

| Legacy element | Source (legacy) | Orange Box source | Rule |
|---|---|---|---|
| RPM meter | `SAE_RPM` (fast "extra data" path) | `VehicleSample[RPM]` | int; `---` if missing or < 0 |
| Speed meter | `SAE_VSS` (extra data) | `VehicleSample[SPEED]` | int; `---` if missing |
| FUEL-CUT lamp | `CALC_FUEL_CUT` | `InstantTelemetry.fuelCut` | on = green `#18f800` text + icon |
| OVERSPEED lamp | `VSS ≥ threshold` (default 110 km/h, user setting) | `InstantTelemetry.overspeed` (event with hysteresis, 120/117 km/h, configurable in M8) | on = red `#f80000` |
| Left panel tint | eco level from load % (petrol/LPG: <50 green, 50–80 or 80–90 yellow, ≥90 red; diesel MAP−BARO <50/<80/<100/≥100; only while VSS > 0) | `InstantTelemetry.ecoLevel` (new, same thresholds) | see 4.4 |
| ECO icon | same eco level | same | green/yellow/red; off grey-white |
| COOLANT icon | `SAE_ECT` valid; `WARN_OVERHEAT` (≥ 100 °C) | `coolantC`, warning flag | blue normal, orange warning, off |
| BATTERY icon | `CALC_AUX_BAT` valid; warning outside 13.2–14.8 V (engine on) | `batteryV`, warning flag | blue / orange / off |
| ACCEL. / BRAKE / IDLING | `CALC_HARSH_ACCEL` / `_BRAKE` / `CALC_IDLING` | `harshAccel` / `harshBrake` / `idling` (event-active) | orange `#f89018` when on |
| MIL chip | MIL bit + stored DTC | Mode 01 PID 01 | red when on |
| OBD chip | engine "on driving" (VES) | `ObdConnectionState.Connected` and samples flowing | cyan when on |
| FUEL km/L | `TRIP_FUEL_ECONOMY` (cap 99.9) | `TripTotals.economyKmPerL` | `--.-` until valid |
| Fuel bar | `CALC_FUEL_ECONOMY` (instant, 0–30); full when fuel-cut | `InstantTelemetry.instantEconomy` | full on fuel-cut |
| Fuel used L | trip fuel | `TripTotals.fuelLitres` | 1 decimal |
| TRIP km / time | `TRIP_DRIVING_DIST`, `TRIP_DURATION` | `TripTotals.distanceKm`, `durationS` | `0.0`, `H:MM:SS` (`DateUtils.formatElapsedTime`) |
| Engine off / no data | `init()` resets everything | `Disconnected` or `LinkLost` | placeholders, all lamps off, panels idle |

### 4.4 Improvements ("same or better")

- **Panel glows driven by real state.**
  - The left panel glow follows eco level (green/yellow/red). While **fuel-cut** is active it shows green with the FUEL-CUT header lit, as in Kiran's shot.
  - The right panel, which never glowed in legacy, glows **red** while overspeed is active and **orange** while a harsh accel or brake event is active (priority: overspeed > harsh > none).
  - Glows animate in 300 ms and are disabled when *Remove animations* is on.
- **Colour isn't the only signal.** Every lamp has a caption and its on state is also shown by weight or filled shape. Content descriptions include state (e.g. "Overspeed, active").
- **Font scaling.** Meter text is sized in `sp` with an auto-fit cap (`BasicText` with `TextAutoSize`) so 200 % font scale doesn't clip, and the layout reflows to portrait-style stacking below 600 dp width.
- **Touch targets.** Bottom-bar buttons are ≥ 56 dp, lamps aren't interactive, and the MIL chip (tap → Diagnostics) is 48 dp.
- **Edge-to-edge.** The bezel draws behind system bars, and content respects `WindowInsets.safeDrawing`. Keep-screen-on applies while driving.
- **Theme.** The dashboard is always dark (decision D1); there's an optional high-contrast variant with no gloss, no dot grid and stronger strokes.
- **Portrait support** as in 4.2.

### 4.5 Bottom bar mapping (Kiran)

| Legacy | Orange Box | Milestone |
|---|---|---|
| Hazard / ERS (emergency) | **Emergency** button → crash/emergency prompt flow (countdown → dialer/SMS app) | M7 (#54/#55); button hidden until M7 lands, or opens the prompt in "manual" mode |
| Map / app launcher (navi quick launch) | Replaced by **Diagnostics** shortcut (DTC lamp icon) → `DiagnosticsScreen`; long-press none | M4 (stub) → M6 |
| "Detailed Info." toggle | Same: toggles clusters ↔ detail grid (4.6) | M4 |
| Quick-call launcher | **Dropped** (overlay and call features are out of scope). The slot holds the **Trip** chip (opens current-trip summary) or stays empty; decision D2 | n/a |
| Power (exit) | **End driving**: stops `DrivingService`, finalises the trip, returns to Home (confirmation snackbar with Undo for 5 s) | M4 |
| "Preview" chip (dashcam) | **Omitted** (dashcam dropped). The arch stays as decoration | n/a |

### 4.6 Screen B: Detailed Info. grid (M4; Kiran's `kiran-dashboard-detail-grid.jpg`)

Toggling Detailed Info. slides the clusters out sideways (left by −width, right by +width), fades the centre column out and fades the grid in, all over 500 ms. Toggling back reverses it. The grid is 538×182 dp land (331 dp tall in portrait).

A ViewPager runs with a **wrapping** adapter (last↔first virtual pages). Page dots sit under the grid: active `accent` blue, inactive grey. Each page is a 3×3 table with 8 dp gutters. Rows 1–2 use small tiles (46 dp); row 3 uses large tiles (70 dp) with icon (38 dp) + glow.

| Page | Row 1 | Row 2 | Row 3 (large, icon + glow) |
|---|---|---|---|
| 1 | Speed km/h · RPM rpm · Fuel Level % | Accel. Pos. % · Eng. Load % · Throttle Pos. % (1 dp) | Batt. Volt. V (1 dp) · Coolant Temp. °C · Malfunction (MIL) ea |
| 2 | ST Fuel Trim % · LT Fuel Trim % · Fuel Pressure kPa | In. Air-Temp. °C · Amb. Air-Temp. °C · Baro. kPa | Distance w. MIL km · Eng. Oil Temp. °C · O2 Sensor Volt. V |
| 3 | Avg. Fuel Eco. km/L · Fuel Consump. L · CO2 Emission kg | Fuelcut Time · Harsh Accel. Count · Harsh Brake Count | Eco-speed Time · Overspeed Time · Idle Time |

Kiran's screenshot shows a newer 2-page order (Accel Pedal Position, Fuel Level, Baro Pressure / Speed, RPM, Engine Load / Battery Voltage, Coolant Temp., MIL). Orange Box keeps the **3-page set** (more data) with page 1 matching Kiran's top-level picks:
- page 1: Accel Pedal, Fuel Level, Baro / Speed, RPM, Engine Load / Battery, Coolant, MIL;
- pages 2 and 3 as above (Throttle moves to page 2).

Large-tile glow:
- **blue** (`#00a0e0` family) when normal;
- **red** when that value is in warning (battery outside 13.2–14.8 V, coolant ≥ 100 °C (legacy `MAX_ENGINE_COOLANT_TEMPERATURE`), MIL count > 0);
- none when there's no data (legacy `bg_value_dark_large_disabled`).

Values the car doesn't support show `---`, and the tile dims to 60 % with the content description "not supported".

## 5. Screen E–H: diagnostics (M6)

Reference: `/workspace/analysis/ui-reference/diagnostic_composite_land.png` (box only).

### 5.1 Diagnostics screen

- **Landscape:**
  - Left: the vehicle illustration, 290×163 dp. It's a wireframe car over a grid with 7 highlightable zones: chassis, transmission, electronic circuit, network, engine, exhaust, electronic device. A brand-green round **Refresh** button sits 15 dp in from the top-left, and **Clear stored DTC** (dark bezel pill, white 12 dp) sits under the illustration, visible only when there are DTCs.
  - Right: two rows of 5 **group lamps** (48 dp square rounded icon + 12 dp caption `#cbcbcb`, 6 dp gap), and below them a 47 dp message bar `#2e2e2e` with white 12 dp text, 19 dp horizontal padding.
- **Portrait:** Clear, illustration, Refresh (overlapping by −24 dp), then lamps and message.
- Groups and DTC mapping (legacy `DtcParser`):

  | # | Group | Codes | Illustration zone |
  |---|---|---|---|
  | 1 | Air/Fuel | P0001–P0199, P0400–P0499 | exhaust |
  | 2 | Injection | P0200–P0299 | engine |
  | 3 | Ignition | P0300–P0399 | engine |
  | 4 | Speed | P0500–P0599 | engine |
  | 5 | Circuits | P0600–P0699 | electronic circuit |
  | 6 | Transmission | P0700–P0899 | transmission |
  | 7 | Powertrain | P1000+ | engine |
  | 8 | Body | B… | electronic device |
  | 9 | Chassis | C… | chassis |
  | 10 | Network | U… | network |

  Lamp colours: no codes = blue `#0cb0dc` (disabled, not clickable); has codes = coral `#f06860` (clickable → detail); pressed `#b02420`. A faulted illustration zone turns red.
- Message: "Car status is good." when there are no DTCs; "Press any red-colored lamp if want to check DTC details." when there are. Orange Box rewords the second to "Tap a red system to see its trouble codes."

### 5.2 Group detail (F)

Title = group icon + name. Below it come the code list (one per line), the group description text (`dtc_groupNN_text`), and "Car maintenance and inspection recommended." Orange Box uses a modal bottom sheet in the same dark style. Each code row shows its description from our own DTC table, with pending and permanent codes in M6 and history in #51.

### 5.3 Diagnosing prompt (G) at drive start

A dark popup with the title "Car Diagnosing…" and a close (×) button, a scanning animation (sweep over the car silhouette) and a 3 dp progress bar `#ff4d4d`. The result is "No Troubles" (auto-dismiss) or "DTC Warning" with "Press car image for checking more details." → Diagnostics. Orange Box recreates the sweep with Canvas and offers "Reduce motion" via a static progress bar.

### 5.4 Clear confirmation (H)

The copy is "Manual DTC clearance is not recommended.\n\nSure to continue?". While moving it shows "Clearing DTC while driving is not supported. Please stop your car and retry." Orange Box uses a Material 3 `AlertDialog` (rule 4) with this copy, and blocks clearing while speed > 0.

## 6. Mapping to Orange Box

| Orange Box component | Mimics | Module | Issue |
|---|---|---|---|
| `DashboardTheme` + `ClusterPanel`, `CenterCard`, `ValueTile`, `Lamp`, `BezelBackground`, `BottomBarButton` (Canvas/vector) | §3 | new `core:dashboard-ui` | M4 design kit issue |
| Icon set: fuel-drop, overspeed gauge, coolant, battery, eco leaf, accel car, brake car, idling, MIL engine, OBD plug, pin, clock, fuel pump, trip doc, hazard, power, 10 DTC groups | §3, §5 | `core:dashboard-ui` (vector XML authored by us) | M4 design kit issue; DTC icons in M6 issue |
| `DashboardScreen` (clusters + centre + bottom bar, land/portrait) | §4.1–4.5 | `feature:driving` | #38 |
| Detail grid pager | §4.6 | `feature:driving` | M4 detail-grid issue |
| Home **Live data** card | mini cluster (§7) | `feature:home` | M4 Home card issue |
| `DiagnosticsScreen`, group sheet, vehicle illustration | §5.1–5.2 | `feature:diagnostics` | #48, M6 illustration issue |
| `DiagnosingDialog` | §5.3 | `feature:diagnostics` | #50 |
| Clear confirmation | §5.4 | `feature:diagnostics` | #49 |
| Trip list, trip detail, stats | Material 3 (rule 3); legacy hierarchy only (D3) | `feature:trips` | #42, #44, #46 |
| Settings | Material 3 | `feature:settings` | M8 |

## 7. Home "Live data" card plan (M4)

Today (M2) the Home card is a Material list of values.

In M4 it becomes a **mini dashboard**: a dark card (always dark, rounded 16 dp) with two compact cluster panels side by side.
- Left: RPM with the eco/fuel-cut glow and FUEL-CUT lamp.
- Right: speed with the overspeed/harsh glow and OVERSPEED lamp.
- Under them, a one-line lamp strip: MIL, OBD, Coolant, Battery, Eco, Accel, Brake, Idle.
- At the bottom, km/L and trip km/time.

Tapping the card (or "Open dashboard") opens `DashboardScreen`. Below 360 dp width the two panels stack vertically. The data comes from the same `DashboardUiState` as the dashboard, so both show identical values.

## 8. Decisions needed

- **D1 (proposed: yes):** the dashboard, detail grid, Home mini-dashboard and diagnostics are **always dark**, as in legacy, whatever the system theme. Light theme gets a high-contrast dark variant only. Everything else follows system light/dark (Material 3 dynamic colour off; brand seed orange).
- **D2:** what goes in the old quick-call slot? Proposed: empty on phones, and a **Trip summary** chip on ≥ 600 dp wide screens. The map-launcher slot becomes the **Diagnostics** shortcut (the legacy repo build had a DTC launcher there).
- **D3:** the Trip Stats, Driving Pattern and Car Status pages were server web pages, not native. Proposed: rebuild them locally in M5 with **Material 3 components** but the legacy information hierarchy (score ring with time/distance/harsh/speed around it; bar list of Eco/Safe driving scores). They are *not* mimicked pixel-for-pixel, because they aren't live OBD readouts.
- **D4:** overspeed threshold. Legacy defaulted to **110 km/h** (user-configurable), while Orange Box currently uses 120 km/h with 3 km/h hysteresis. Proposed: switch the default to 110 to match legacy and expose it in M8 Units/Alerts.
- **D5:** detail-grid page count is 3 pages (legacy repo, more data) rather than the 2 pages in Kiran's newer build. Page 1 matches his screenshot.
