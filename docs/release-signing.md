# Release build and signing (#74)

## What the release build does
- `./gradlew assembleRelease` runs R8: code minification and optimisation, plus resource shrinking.
  - Keep rules come from the libraries' consumer rules (Hilt, Room, kotlinx.serialization, OkHttp, Compose) and
    `app/proguard-rules.pro`.
  - `proguard-rules.pro` only keeps readable class names for log lines, plus line numbers.
- The mapping file is `app/build/outputs/mapping/release/mapping.txt`. Keep it with every APK you distribute; you need
  it to retrace stack traces.
- `scripts/ci/check-release.sh` fails the build if Simulator / debug-override code or Google Play Services / Firebase
  end up in it.
- CI's emulator job installs the minified APK, walks onboarding and relaunches it (`scripts/ci/release-smoke.sh`).
  This catches missing keep rules.

## Signing
The signing values are read from Gradle properties (`-P…` or `~/.gradle/gradle.properties`), or from environment
variables with the same names. Nothing secret is committed.

| Name | Value |
|---|---|
| `ORANGEBOX_KEYSTORE_FILE` | path to the release keystore (`.jks` / `.p12`) |
| `ORANGEBOX_KEYSTORE_PASSWORD` | keystore password |
| `ORANGEBOX_KEY_ALIAS` | key alias |
| `ORANGEBOX_KEY_PASSWORD` | key password (same as the store password for PKCS12) |
| `ORANGEBOX_RELEASE_DEBUG_SIGNED` | `true` = sign with the shared debug key when there is no release key (testing only) |

- If `ORANGEBOX_KEYSTORE_FILE` is set, the release APK is signed with that key. The build fails if any of the other
  three values is missing.
- Without it, the APK is unsigned (`app-release-unsigned.apk`), or debug-signed when `ORANGEBOX_RELEASE_DEBUG_SIGNED`
  is `true`.
- A debug-signed release is for testing only. Never publish one: Android won't let it update, or be updated by, a
  properly signed install.

### Local
Add the values to `~/.gradle/gradle.properties`, not the repo:
```
ORANGEBOX_KEYSTORE_FILE=/home/you/keys/orangebox-release.jks
ORANGEBOX_KEYSTORE_PASSWORD=…
ORANGEBOX_KEY_ALIAS=orangebox
ORANGEBOX_KEY_PASSWORD=…
```

### CI secrets (to be created by the repository owner)
Repository → Settings → Secrets and variables → Actions → *New repository secret*:

| Secret | Content |
|---|---|
| `ORANGEBOX_KEYSTORE_BASE64` | the keystore file, base64-encoded: `base64 -w0 orangebox-release.jks` |
| `ORANGEBOX_KEYSTORE_PASSWORD` | keystore password |
| `ORANGEBOX_KEY_ALIAS` | key alias |
| `ORANGEBOX_KEY_PASSWORD` | key password |

- The `release-check` job signs with the release key only when `ORANGEBOX_KEYSTORE_BASE64` exists. The keystore is
  decoded to the runner's temp dir and deleted at the end of the job.
- Without the secret, the job signs with the debug key, so the emulator smoke test can still install the APK.
- The signed APK and its mapping are uploaded as the `app-release-apk` artifact, which is kept for 7 days.

### Creating the key (owner, once, offline)
```
keytool -genkeypair -v -keystore orangebox-release.jks -storetype PKCS12 \
  -alias orangebox -keyalg RSA -keysize 4096 -validity 10000
```
- Back up the keystore and its passwords somewhere safe, outside the repository. Losing them means you can never ship
  an update to existing installs.
- If you will publish through Google Play with Play App Signing, this key is the *upload* key.
