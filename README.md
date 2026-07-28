# Tendroid

Android importer and live wallpaper renderer for Apple PosterBoard `.tendies` packages.

## Current prototype

- Opens a `.tendies` document through Android's system file picker.
- Imports the bundled Roxy package automatically on first launch.
- Copies the package into app-private storage using a SHA-256 content ID.
- Applies entry-count, compressed-size, uncompressed-size, path, and XML safety checks.
- Reads layered CAML scenes and reports images, text layers, states, and animation types.
- Renders CAML image/text layer hierarchies, transforms, opacity, colors, and state overrides.
- Displays the selected scene both in-app and through Android `WallpaperService`.
- Detects active package scripts and reports them as unsupported; inert Apple template scripts are ignored.
- Checks GitHub Releases for newer builds and verifies the published SHA-256 before opening Android's installer.

## Build

The project uses JDK 17-compatible bytecode, Gradle Wrapper 9.4.1, Android Gradle Plugin 9.2.0, and Android SDK 36.

```bash
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To include a real package in the parser test without committing its assets:

```bash
GRADLE_USER_HOME="$PWD/.gradle" \
TENDIES_SAMPLE=/absolute/path/to/sample.tendies \
./gradlew testDebugUnitTest --rerun-tasks
```

## Supported CAML subset

The renderer currently supports `CALayer`, `CGImage`, `CATextLayer`, nested transforms, `LKState` overrides, `Locked`/`Unlock`/`Sleep` state transitions, per-key-path timing, and `CASpringAnimation` parameters.

Every push to `main` runs tests and lint, builds an installable debug-signed APK, and publishes it as a GitHub prerelease. Production distribution should use a persistent release keystore instead.

The in-app updater can access releases without credentials only when the release repository is public. The repository is currently private, so the app reports that the update channel is unavailable rather than embedding a GitHub token in the APK.

JavaScript execution is intentionally not enabled. PosterBoard scripts use Apple-specific host objects such as `layer`, `document`, and `system`; supporting active scripts requires a constrained compatibility layer, not just a generic JavaScript engine.

The bundled Roxy asset is intended only for local prototype testing. Confirm its redistribution rights before publishing an APK containing it.
