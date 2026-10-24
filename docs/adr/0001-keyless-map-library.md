# ADR 0001: Keyless map for trip detail

- Status: Accepted (2026-10-24)
- Issue: #43 (epic #41, M5)
- Deciders: Orange Box maintainers (review waived by Kiran)

## Context

Trip detail (#44) shows the recorded route (`trip_samples.latitude/longitude`, about 1 Hz) on a map.
Constraints from `docs/PLAN.md` and Kiran:

- **No Google-run services**: no Google Maps SDK, no Play Services. No API key of any kind.
- **minSdk 24**, and the APK must keep working on Android Tester's **API 25 armeabi-v7a** emulator.
  So far the APK has **no native libraries** (M0 acceptance), which keeps it small and ABI-neutral.
- Must work **offline** and for trips **without location** (the route can't depend on tiles).
- Testable in **Robolectric** (JVM screenshots) and on the CI API 34 emulator.
- The map is a static review of one drive: pan, pinch-zoom and a polyline. No navigation, no
  offline region download, no search.

## Options

| | osmdroid 6.1.20 | MapLibre Native Android 13.x | **MapCompose 3.2.8** |
|---|---|---|---|
| Licence | Apache-2.0 | BSD-2-Clause | Apache-2.0 |
| Maintenance | **Archived** Nov 2024, last release Aug 2024 | Active | Active (3.2.8, Jun 2026, Kotlin 2.4, Compose BOM 2026.05) |
| UI toolkit | View (`MapView` via `AndroidView`) | View (`MapView`/`GLSurfaceView`) | **Compose-native** (`MapUI`) |
| Native code | None | **Yes**: `.so` per ABI, AAR ≈ 15–18 MB | None (AAR ≈ 0.6 MB) |
| GPU | Canvas | **OpenGL ES 3.0** (v11+) or Vulkan (default artifact) | Compose Canvas |
| minSdk | 8 | 21 | 21 |
| Tiles | Raster | Vector (style JSON) + raster | Raster (any `TileStreamProvider`) |
| Keyless tile source | OSM raster | **OpenFreeMap** vector (no key, no limits) | OSM raster |
| Robolectric | Partly (View, network-bound tile loader) | No (native renderer) | Yes (plain Compose) |

Notes:
- **osmdroid** works, but adopting an archived library for new code means we inherit every future
  Android/AGP incompatibility and any security fix ourselves.
- **MapLibre** is the strongest renderer and pairs with OpenFreeMap's keyless vector tiles. But it
  adds native libraries for every ABI (or a per-ABI split we don't have), needs GLES 3.0 (older
  devices and ARM emulators with software GL often lack it), is the only option that can't render
  in Robolectric, and is far more than a route preview needs.
- **MapCompose** is a small, pure-Kotlin Compose library for tiled maps with paths and markers. It
  matches the app's Compose-only UI, needs no native code or GPU features, and is easy to test.

## Decision

Use **MapCompose** (`ovh.plrapps:mapcompose`) with **raster tiles from the OpenStreetMap
Foundation's standard tile server** (`https://tile.openstreetmap.org/{z}/{x}/{y}.png`), keyless.

The route is **always** drawn by the app itself (projected Web-Mercator polyline with start/end
markers and event markers). Tiles are only a backdrop: with tiles off, offline or blocked, the route
still renders on a neutral grid, so trip detail never depends on the network.

### Tile usage policy (OSMF) – how we comply

From <https://operations.osmfoundation.org/policies/tiles/> (read 2026-10-24, re-checked 2026-10-25):

| Requirement | Orange Box |
|---|---|
| Use exactly `https://tile.openstreetmap.org/{z}/{x}/{y}.png` (HTTPS) | `MapTiles.URL_TEMPLATE`, HTTPS only |
| Recommended: avoid hard-coding the URL, so it can switch without an update | **Not met yet**: the template is one build-time constant (`MapTiles`), so switching needs a release. A remote or user-editable source is a later change; the Settings switch can turn tiles off without one |
| Visible attribution on the map ("© OpenStreetMap contributors"), not hidden behind UI or toggles | Always-visible label, bottom-right of the map whenever tiles are shown, linking to <https://www.openstreetmap.org/copyright> |
| Distinct, stable User-Agent naming the app (no library default such as `okhttp/x.y`, no browser spoofing) | `OrangeBox/<versionName> (+https://github.com/neo-the-on3/orange-box)`, set on every request by an interceptor |
| Recommended: a published contact | The project URL in the User-Agent (issues on GitHub) |
| Recommended: HTTP/2 or HTTP/3 | OkHttp negotiates HTTP/2 over TLS by default |
| Referer / Referrer-Policy | Web only; not applicable to a native app |
| Cache per HTTP caching headers (or ≥ 7 days); conditional requests | OkHttp disk cache (honours `Cache-Control`/`ETag`, sends `If-None-Match`); responses without freshness info get a 7-day max-age |
| No `no-cache` headers by default | None sent |
| **No bulk download, prefetch or offline packs; no headless bots that pan/zoom to force rendering** | Only tiles in the current viewport are requested; no "download area" feature, ever, on this server. Automated runs never fetch tiles: Robolectric uses a fake tile provider, and the CI emulator smoke starts with `--es map_tiles off` |
| Recommended: "Report a map issue" link | Trip detail ⋮ menu → "Report a map issue" opens <https://www.openstreetmap.org/fixthemap> |

Other rules we follow:
- **Privacy**: tile requests reveal the viewed area to OSMF. A switch "Map tiles (OpenStreetMap)"
  (trip detail ⋮ menu, stored in app preferences) turns them off entirely; the route still shows. No
  location or trip data is ever sent, only tile coordinates.
- Limit concurrency to a few workers and cap zoom at 18.
- If OSMF blocks or rate-limits us (HTTP 403/429), tiles fail quietly and the grid stays.
- **INTERNET permission** is added for tiles only (the app was offline-only until now). Backend
  modes are unaffected.

## Consequences

- No API key, no Google services, no native libraries; APK grows by under 1 MB (MapCompose + OkHttp).
- Map, route and markers render in Robolectric (fake tiles, no network); the CI emulator smoke runs with tiles off.
- Raster tiles only; no vector styling or dark map style. A dark theme dims the tiles.
- Heavy use of `tile.openstreetmap.org` is not allowed. If Orange Box ever has many users or
  needs offline maps, switch the URL template to a provider that allows it, or move to **MapLibre +
  OpenFreeMap** vector tiles (attribution "OpenFreeMap © OpenMapTiles Data from OpenStreetMap").
  That is a new ADR.
