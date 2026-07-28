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
- Never executes JavaScript bundled in an imported package.

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

The bundled Roxy asset is intended only for local prototype testing. Confirm its redistribution rights before publishing an APK containing it.
