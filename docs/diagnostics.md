# Diagnostics (M6)

How Orange Box reads, explains and clears trouble codes. UI reference: [`ui/LEGACY_OBD_UI.md`](ui/LEGACY_OBD_UI.md) §5.

## Modules

| Module | What it holds |
|---|---|
| `core:diagnostics` | `DtcGroup` (10 legacy lamps → 7 illustration zones), `DtcCatalog` (descriptions), `ObdDiagnosticsRepository` (scan / clear over the live session), `DiagnosingPromptController` (one prompt per drive) |
| `core:database` | `dtc_records` table, `DtcHistoryRepository` (schema 4) |
| `core:dashboard-ui` | `DtcGroupIcons`, `DtcLamp`, `DiagnosticsColors` |
| `feature:diagnostics` | `DiagnosticsScreen`, group and history sheets, clear dialogs, `VehicleSystemsIllustration`, `ScanSweep`, `DiagnosingPromptCard` |

## Reading codes

A scan reads, in one session and one after the other (the ELM327 session mutex keeps them from interleaving with PID polling):

1. PID 01 01 for the MIL state;
2. mode 03 (stored), 07 (pending) and 0A (permanent) codes;
3. mode 09 02 for the VIN.

A list the car doesn't answer readably is shown as "The car didn't answer: pending" instead of "no codes", and its history records are left alone. The Diagnostics screen scans when it opens and whenever the car (re)connects while it is open; **Refresh** scans again.

## Groups

Lamp order and ranges follow the legacy `DtcParser` (§5.1). Gaps in the legacy table are filled from SAE J2012: P09xx → Transmission, P0000 and the hybrid P0Axx–P0Fxx block → Powertrain. A lamp counts codes from all three lists; the group sheet labels each code Stored, Pending or Permanent.

## Descriptions

`DtcCatalog` has our own short text for about 155 common generic codes. Every other code gets a generic line by SAE range ("Generic code: ignition system or misfire.", "Manufacturer-specific code. Check the service information for your car."). Nothing is copied from the legacy app, SAE documents or other code databases. English only until the M10 localisation scaffold.

## Clearing (mode 04)

- **Clear stored DTC** shows only when there is a stored or pending code (permanent codes can't be cleared with mode 04).
- While the car is moving (speed > 0) it explains "Clearing DTC while driving is not supported…" and sends nothing. The repository checks again before sending.
- Otherwise a Material 3 confirmation warns that the MIL goes off, readiness monitors reset, and the engine should be off with the ignition on. **Cancel** sends nothing; **Clear** sends `04`, records the clear in the history and scans again.
- A missing `44` reply shows "The car didn't clear the codes…".

## History (#51)

`dtc_records` has one row per code and list: first seen, last seen, and an end time with reason `cleared` (mode 04 acknowledged) or `resolved` (a later successful read of that list no longer reports it). A code that comes back opens a new row. Rows are per active vehicle. The group sheet lists earlier codes of that group; the list icon opens the whole history.

## "Car Diagnosing…" prompt (#50)

On the first Connected of a drive, `DiagnosingPromptController` scans and shows a floating card over whatever is on screen (not over onboarding or Diagnostics itself). It doesn't block the screen beneath, and × closes it at any time.

| Phase | Shows | Ends |
|---|---|---|
| Scanning (≥ 2.5 s) | Canvas sweep over the car, 3 dp red progress bar | result |
| No Troubles | blue car, "Car status is good." | auto-dismiss after 2.5 s |
| DTC Warning | red car, code count, MIL line | × or car image / **Open Diagnostics** |

A failed or not-connected scan hides the card. A dropped link that reconnects is the same drive; only a session that ended (Idle after "End driving", or Failed) lets the next Connected prompt again. With the system "Remove animations" setting the sweep isn't drawn and the bar fills in four steps.

## Simulator

Everything above runs against the debug Simulator: inject stored, pending or permanent codes (or **Inject P0300 + P0420 + U0100** for the acceptance set), toggle the MIL alone, and use **Clear DTCs** to compare with the app's clear. Mode 04 on the simulator clears stored and pending codes and turns the MIL off, but keeps permanent codes.

## Artwork provenance (#100)

- [x] Group glyphs (`DtcGroupIcons`) drawn on a 24-unit grid from lines, arcs and rectangles, designed from the group names only.
- [x] Lamp face: rounded square with our own gradient; only the three colours (`#0cb0dc`, `#f06860`, `#b02420`) and the 48 dp size come from the legacy XML.
- [x] Vehicle illustration and scan sweep drawn in Compose Canvas on a 290 × 163 grid (the legacy view size). The legacy layer bitmaps and `diagnostic_composite_land.png` were not traced, sampled or used as an underlay.
- [x] No Pokevian drawable, bitmap, font, icon or sound is in the repo.
