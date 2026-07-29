<p align="center">
  <img src="docs/tendroid-mark.svg" width="112" height="112" alt="Tendroid icon">
</p>

<h1 align="center">Tendroid</h1>

<p align="center">
  Apple PosterBoard <code>.tendies</code> wallpapers, rendered as native Android live wallpapers.
</p>

<p align="center">
  <a href="https://github.com/DrArzter/tendroid/actions/workflows/release.yml"><img alt="Build" src="https://github.com/DrArzter/tendroid/actions/workflows/release.yml/badge.svg"></a>
  <a href="https://github.com/DrArzter/tendroid/releases"><img alt="Release" src="https://img.shields.io/github/v/release/DrArzter/tendroid?include_prereleases&label=release"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white">
</p>

## Demo

The lock, AOD, and home states are driven by the source wallpaper's own CAML transitions.

<!-- Demo GIF goes here once recorded:
<p align="center">
  <img src="docs/demo.gif" width="360" alt="Tendroid lock-screen transition demo">
</p>
-->

## What it does

- Imports `.tendies` packages through Android's document picker.
- Browses the independently maintained [Tendroid Gallery](https://github.com/DrArzter/tendroid-gallery), verifies each download, and imports it without leaving the app.
- Renders layered CAML scenes with images, text, nested transforms, opacity, and masks.
- Maps PosterBoard `Sleep`, `Locked`, and `Unlock` states to Android AOD, lock screen, and home screen.
- Reproduces per-property timing and `CASpringAnimation` transitions.
- Tracks the device's active refresh mode instead of forcing a fixed frame rate.
- Restores a cached AOD frame if Android recreates the wallpaper process.
- Checks GitHub Releases in-app and verifies the published SHA-256 before opening Android's installer.

## Install

1. Download `tendroid-debug.apk` from [GitHub Releases](https://github.com/DrArzter/tendroid/releases).
2. Allow installs from the browser or file manager when Android asks.
3. Open Tendroid, import a `.tendies` package, then choose **Set live wallpaper**.

Tendroid currently ships as a signed prerelease build. The asset keeps its historical `tendroid-debug.apk` filename so older installed builds can discover updates, but the APK itself is a non-debuggable release build. A persistent project key lets later builds update it in place; do not install APKs carrying a different signing certificate over an existing installation.

## Compatibility

| Feature | Status |
| --- | --- |
| `CALayer`, `CGImage`, `CATextLayer` | Supported |
| Nested transforms and state overrides | Supported |
| `Sleep` / `Locked` / `Unlock` | Supported |
| Per-key-path timing and spring curves | Supported |
| Samsung AOD and adaptive refresh rate | Tested on device |
| Early unlock gesture signal | Supported where the OEM forwards it |
| Continuous raw lock-screen swipe progress | Not exposed to third-party wallpapers on every OEM |
| PosterBoard JavaScript host objects | Not supported |

JavaScript is not executed. PosterBoard scripts depend on Apple-only objects such as `layer`, `document`, and `system`; safely supporting them requires a constrained compatibility runtime, not a generic JavaScript engine.

## Import safety

Packages are copied into app-private storage under a SHA-256 content ID. Before rendering, Tendroid enforces limits for entry count, compressed and expanded sizes, XML depth, texture dimensions, and decoded bitmap memory. It also rejects duplicate entries, traversal paths, external entities, DTDs, and oversized CAML documents.

## Build

Requirements: JDK 17 and Android SDK 36.

```bash
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew testDebugUnitTest lintRelease assembleRelease
```

The unsigned local APK is written to `app/build/outputs/apk/release/app-release-unsigned.apk`. CI supplies the persistent signing key and produces the installable release asset.

To run the parser suite against a real package without committing it:

```bash
GRADLE_USER_HOME="$PWD/.gradle" \
TENDIES_SAMPLE=/absolute/path/to/sample.tendies \
./gradlew testDebugUnitTest --rerun-tasks
```

Every push to `main` repeats the tests and lint, builds the signed APK, and publishes a GitHub prerelease.

## Project status

Tendroid is an experimental compatibility renderer, not an Apple or Android system component. OEM lock-screen behavior differs, and some transitions necessarily use the nearest signal available from Android's public wallpaper APIs.

The bundled Roxy package is retained as a compatibility fixture and default demo. Its artwork and the original `.tendies` format may contain third-party material; verify redistribution rights before repackaging or distributing those assets separately.
