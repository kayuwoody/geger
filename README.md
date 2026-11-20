# geger

Indoor AR wayfinding for shopping malls: directory kiosks, a web map and an AR phone app that
guides shoppers with arrows through the camera.

This repo currently contains **Phase 0 — the AR positioning trial**: a native Android test app
(Kotlin + ARCore) that measures how accurately a phone can track a shopper indoors using QR plates,
the phone's motion tracking, step counting and the barometer, plus a browser map editor,
a plate generator, a replay CLI and an analysis report.

- Build brief: [`SPEC.md`](SPEC.md)
- Agent instructions: [`CLAUDE.md`](CLAUDE.md)
- Decisions not covered by the spec: [`DECISIONS.md`](DECISIONS.md)

## Layout

```
android/                 Gradle project (run all Gradle commands from here)
  positioning-core/      plain Kotlin: map model + validation, geometry, routing, frames, config
  replay-cli/            command-line tool: validates maps today, replays sessions from M7
  app/                   Android test app (M1: skeleton screen only)
maps/sample/map.json     hand-written two-level sample map used by the tests
tools/                   map editor (M2), plate generator (M4), analysis report (M7)
```

## Requirements

- JDK 17 or newer (21 recommended)
- Android SDK with platform **37** (only needed for the `app` module). Android Studio installs this.
  Point Gradle at it with `android/local.properties`:

  ```
  sdk.dir=/path/to/Android/sdk
  ```

  Without an SDK the build still runs the `positioning-core` and `replay-cli` modules and skips `app`.

The Gradle wrapper downloads the right Gradle version on first run.

## Build and test

From the `android/` folder:

```bash
./gradlew test
```

Runs every unit test (map loading and validation, geometry and wall checks, routing, coordinate
frames, config, CLI).

```bash
./gradlew :app:assembleDebug
```

Builds the test app APK at `android/app/build/outputs/apk/debug/app-debug.apk`. Install it on a
phone with USB debugging on:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Check a map file

```bash
./gradlew -q :replay-cli:run --args="validate-map ../maps/sample/map.json"
```

Prints a summary and every warning; exits with code 1 and lists the errors if the map is unusable.
Check a config file the same way with `validate-config <config.json>`.
