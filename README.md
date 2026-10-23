# Orange Box

Real-time vehicle telemetry and data logging over OBD-II (ELM327 Bluetooth adapters), written in Kotlin and Jetpack Compose.

Orange Box is a clean-room re-implementation of the OBD-II features of the Apache-2.0 [CarooLive](https://github.com/Pokevian/caroolive-app) app (Pokevian Inc., 2017). It reuses none of the original binaries, native libraries, keys or branding.

- **Works fully offline.** A backend is optional: the mode is Off (default), Fake or Remote.
- Debug builds include a **Simulator** screen backed by a simulated ELM327, so the app can be exercised on an emulator without hardware.

## Docs
- [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md): analysis of the legacy app
- [`docs/PLAN.md`](docs/PLAN.md): architecture, decisions and milestones
- [`docs/openapi.yaml`](docs/openapi.yaml): OpenAPI 3.1 description of the legacy backend, derived from the client code

## Build
Requirements: JDK 17 and the Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`.

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest :core:model:test
# APK: app/build/outputs/apk/debug/app-debug.apk  (applicationId io.github.neotheon3.orangebox.debug)
```

## Debug launch options
Debug builds accept a backend mode on the launch intent (release builds ignore it):

```bash
adb shell am start -n io.github.neotheon3.orangebox.debug/io.github.neotheon3.orangebox.MainActivity --es backend_mode off   # or fake / remote
```
Invalid values are ignored and the saved mode is kept.

## Modules
`app`, `core:model` (pure Kotlin), `core:datastore`, `core:designsystem`, `feature:home`, `feature:settings`. More modules are added per milestone (see PLAN.md).

## License
Apache License 2.0. See [LICENSE](LICENSE).
