# CLAUDE.md — geger

Indoor AR wayfinding platform for shopping malls. First client: EkoCheras Mall (Kuala Lumpur).
This repo currently holds **Phase 0: the AR positioning trial** — a native Android test app
that measures how well a phone can track a shopper indoors, plus the tools around it.

## Start here

- **`SPEC.md` is the build brief.** Read it fully before writing code. Build the milestones
  (M1–M7) in order; each is done only when its checks pass and the work is committed.
- The spec is an export of the "Phase 0 build spec" tab in the shared spec doc. If they
  disagree, ask the owner which is current.
- `DECISIONS.md`: one line per choice the spec didn't cover (what + why).
- `NOTES.md`: ideas outside Phase 0 scope. Write them down; don't build them.

## Non-negotiables (summary — full list in SPEC.md, "Rules for the coding agent")

- **Verbose documentation.** Every function, class and file section gets a doc comment saying
  what it does *and* the core concept behind it. This is the owner's standing preference.
- **Portable logic.** Positioning maths lives in `android/positioning-core` (plain Kotlin, no
  Android imports) so it runs in unit tests and in the replay CLI.
- **Coordinate conventions in SPEC.md are binding.** Convert library conventions at the boundary
  in one named, unit-tested function.
- **No network, no backend, no camera images by default** (PDPA: shoppers appear in frame).
- **One JSON config** for every tunable number, logged at session start.
- Units: metres / seconds / degrees in files and UI; radians inside maths code.

## Build

Gradle root is `android/`. From there: `./gradlew test` (all unit tests), `./gradlew :app:assembleDebug`
(APK; needs Android SDK platform 37 via `local.properties` `sdk.dir`). Map check:
`./gradlew -q :replay-cli:run --args="validate-map ../maps/sample/map.json"`.
Map editor (`tools/map-editor`, open `index.html`): unit tests `node --test tools/map-editor/test/*.test.js`,
browser test `node tools/map-editor/test/e2e.js <out-dir>` (needs Playwright). Kotlin and JS validators must
agree on `maps/fixtures/` (ok-* load, bad-* rejected) — add a fixture to both when changing a rule.
Work on a branch per milestone; merge to `main` when its checks pass.

## Test devices

Samsung Galaxy S22 Ultra and S23 Ultra (ARCore supported). No iPhone in Phase 0.

## Milestone status

- [x] M1 repo and core model
- [x] M2 map editor (automated checks pass; owner still to build the office dry-run map from a real photo)
- [ ] M3 sensor recorder and logging
- [ ] M4 plates, QR fix and calibration mode
- [ ] M5 estimators and 2D map
- [ ] M6 AR overlay
- [ ] M7 replay and analysis
