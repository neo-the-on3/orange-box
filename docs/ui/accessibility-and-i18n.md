# Accessibility, dark theme and localisation (M10: #71, #72, #73)

## Accessibility (#71)
- **Checks in UI tests.** Google's Accessibility Test Framework (`enableAccessibilityChecks`) needs a device on API 34+
  and does not run under Robolectric. So `core:testing` has `AccessibilityChecks`, which covers the same ground from
  the Compose semantics tree:
  - every clickable, toggleable or selectable node has a label TalkBack can read;
  - touch targets are at least 48 × 48 dp (Compose's extended touch bounds count);
  - no content description is an empty string.

  `screenGallery(...)` runs these checks on every screen it renders.
- **Contrast** is checked exactly on the palettes, not on pixels:
  - `ContrastTest` (designsystem): every text/background pair of the light and dark Material schemes is ≥ 4.5:1, and
    outlines are ≥ 3:1.
  - `DashboardContrastTest` (dashboard-ui): text on the dashboard surfaces is ≥ 4.5:1; lamps, icons and unlit
    captions are ≥ 3:1.
  - Fixes this needed:
    - Light `primary` is #B34700 instead of the brand #F57C00. White on #F57C00, and #F57C00 text on white, are only
      2.7:1.
    - Dark `primaryContainer` is #8B3A00.
    - Unlit lamp captions use #A6A6A6 instead of #686868 (3.8:1 on black).
- **Font scale 200 %.** Every gallery screen is rendered at fontScale 2.0 (`*-font200.png`), applied linearly, which is
  stricter than Android 14's non-linear scaling.
  - The dashboard caps its own scale at 1.5 (`MAX_DASHBOARD_FONT_SCALE`) and auto-fits the meters.
  - Segmented buttons cap at 1.5 (`SegmentLabel`).
  - Tabs shrink to fit (`FitLabel`).
  - Diagnostics lamp captions shrink inside their slot.
- **TalkBack.** The meters read as values with the unit spelled out: "Speed 59 kilometres per hour", "Engine speed
  2100 rpm". With no data they read "Speed, no data" rather than "---". Lamps read "<name>, <state>".

## Dark theme (#72)
- Every main screen is rendered light and dark by its module's `*GalleryTest`. The files are written to
  `build/outputs/gallery/`; collect them with `scripts/collect-screenshots.sh`.
- The driving dashboard and Diagnostics are always dark (legacy cluster style). Their light and dark shots must look
  the same.
- Dynamic colour (Android 12+) is intentionally off, so the brand orange stays the same on every device. Turning it on
  would be a one-line change in `OrangeBoxTheme`, but the contrast fixes above would then depend on the wallpaper.

## Localisation (#73)
- **All user-visible text comes from string resources.**
  - Lint enforces this as an error: `HardcodedText` covers XML, and the custom `HardcodedComposeText` check
    (`:lint-rules`) covers Compose `text` / `contentDescription` / `label` / `title` / `placeholder` / `onClickLabel`
    arguments and semantics assignments.
  - Literals without letters ("·", "---") and `@Preview` functions are allowed.
  - The debug-only Simulator is English-only and suppressed file-wide.
- **Pseudo-locales** are enabled for debug builds: en-XA (accented, longer) and ar-XB (right-to-left).
  `PseudoLocaleTest` (app) renders Home and Settings in both (`pseudo-*.png`).
- **Per-app language (Android 13+), without AppCompat.**
  - `androidResources.generateLocaleConfig = true` generates `android:localeConfig` from the `res/values-*` folders.
    The default locale is in `app/src/main/res/resources.properties`.
  - Settings → About → *App language* opens the system's per-app language screen.
  - Today that screen lists English only, plus the pseudo-locales in debug builds. Adding `values-xx/strings.xml`
    files adds a language there.
