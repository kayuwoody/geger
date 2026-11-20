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
  replay-cli/            command-line tool: validates maps and configs, checks recorded sessions; replays from M7
  app/                   Android test app: sensor recorder with checks and export (M3)
maps/sample/map.json     hand-written two-level sample map used by the tests
docs/PHONE_SETUP.md      installing the app on the test phones, exporting sessions
maps/fixtures/           small valid (ok-*) and broken (bad-*) maps shared by the Kotlin and editor tests
tools/map-editor/        browser map editor: open index.html (see its README)
tools/                   plate generator (M4), analysis report (M7)
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

Builds the test app APK at `android/app/build/outputs/apk/debug/app-debug.apk`. To put it on a
phone (from a file, or with adb), follow [`docs/PHONE_SETUP.md`](docs/PHONE_SETUP.md).

## Check a recorded session

```bash
./gradlew -q :replay-cli:run --args="check-session /path/to/session-YYYYMMDD-HHMMSS.zip"
```

Reads the exported zip, confirms every stream's record count matches the phone's own totals, and
prints rates against target and the clock checks. Ends with `RESULT: PASS` or the problems found.

## Check a map file

```bash
./gradlew -q :replay-cli:run --args="validate-map ../maps/sample/map.json"
```

Prints a summary and every warning; exits with code 1 and lists the errors if the map is unusable.
Check a config file the same way with `validate-config <config.json>`.

## Draw a map

Open `tools/map-editor/index.html` in Chrome. Step-by-step instructions are in
[`tools/map-editor/README.md`](tools/map-editor/README.md). Editor tests, from the repo root:

```bash
node --test tools/map-editor/test/*.test.js
```
