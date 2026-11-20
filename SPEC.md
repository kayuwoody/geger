# Phase 0 build spec: AR positioning trial

This is the build brief for Phase 0: a native Android test app that measures how well we can track a shopper indoors, plus the small tools around it. It's written for a coding agent to execute top to bottom.

> Source of truth: the "Phase 0 build spec" tab of the shared spec doc. This file is an export of it; if the two disagree, ask the owner which is current.

## Goal and scope

Phase 0 answers one question: can a phone's own sensors, re-anchored by QR plates, keep a shopper's position within about 3 m and heading within about 15° along a real EkoCheras route?

The app runs on a Galaxy S22 Ultra and S23 Ultra. It shows AR arrows along a route on a placeholder map and logs every sensor reading for analysis.

**Why native Android:** these are the only test phones, native gives full access to sensors and camera, and the results carry over to whichever final AR stack we choose (Unity's AR on Android uses the same ARCore underneath).

### In scope

- Native Android app (Kotlin, ARCore) for the S22 Ultra and S23 Ultra
- QR fix: phone position and heading from one plate scan, taken from a distance
- Three estimators running side by side: ARCore + QR, step counting + QR, and a fused particle filter that uses the map
- Floor-change detection with the barometer, for one escalator and one lift
- Basic AR overlay: chevrons on the floor along a route; a 2D map when the phone is held flat
- Ground-truth capture at checkpoints, full sensor logging, export
- Tools: browser map editor, plate generator, replay CLI, analysis report

### Out of scope

- iPhone, App Clip, web map, WebXR (one optional stretch item in Milestones)
- Kiosks, admin system, cloud backend, user accounts
- Real CAD import and IMDF
- Shop search, translations, ads
- Production UI polish

### Pass targets (proposed, confirm after the first walks)

| Measure | Target | Measured in |
| --- | --- | --- |
| Position error at checkpoints | 90% of checkpoints within 3 m | Every route run, per estimator |
| Heading error at checkpoints | 90% within 15° | Every route run, per estimator |
| QR fix error | Recorded against scan distance and angle; sets the plate size | QR calibration test |
| Floor detection | Correct floor within 10 s of leaving the escalator or lift | Floor-change runs |
| Tracking loss | Every loss logged with time, duration and ARCore's stated reason | Every run |

## Rules for the coding agent

1. **Build the milestones in order.** Each ends with its acceptance checks passing and a commit. If a check can't pass, stop and report; never skip or weaken a check.
2. **Document every function.** Each function, class and file section gets a doc comment saying what it does and the core concept behind it (for example, why a particle filter resamples). This is the owner's standing preference.
3. **Keep logic portable.** Pure logic lives in `positioning-core` (plain Kotlin, no Android imports) so it runs in unit tests and in the replay CLI. Android code only gathers sensor data, draws and logs.
4. **Test the maths first.** Unit-test frame conversions, pose solving, alignment, the particle filter, routing and floor detection with synthetic data before testing on a phone.
5. **No network.** No backend, accounts, analytics SDKs or crash reporters. Everything stays on the phone until the tester exports it.
6. **No camera images by default.** Shoppers appear in frame, which raises PDPA (personal data law) issues. A debug toggle may save one low-resolution frame per second; it is off by default.
7. **One config file.** Every tunable number (noise levels, thresholds, step length, particle count) lives in one JSON config with defaults in code. It is editable on the phone without rebuilding, and logged at session start.
8. **Units.** Metres, seconds and degrees in files and on screen; radians inside maths code. Put units in names where it's ambiguous (`headingDeg`, `tNanos`).
9. **Conventions are binding.** Follow "Coordinate frames and conventions" exactly. Where a library differs, convert at the boundary in one named function that has a unit test.
10. **Stay in scope.** Put ideas outside scope in `NOTES.md` instead of building them.
11. **Record decisions.** When this spec doesn't cover a choice, pick the simplest option, add one line to `DECISIONS.md` saying why, and carry on.

## Architecture

The Android app gathers sensor readings and solves QR fixes; all positioning logic sits in a plain-Kotlin core that the replay CLI reuses unchanged.

```
Android app: sensors
  ARCore (camera pose, image) | Motion sensors (IMU, rotation vectors)
  Steps, barometer            | Proximity, light (pocket detection)
        |                                   |
        | image                             | raw readings, timestamped
        v                                   |
  QR fix (ML Kit + OpenCV, pose from plate) |
        | fixes      + camera pose          |
        v                                   v
positioning-core: plain Kotlin, no Android
  Map and routing (walkable areas, A* route search)
  Phone mode, floor detection (up/flat/pocket; barometer level changes)
  E1: ARCore + QR  |  E2: steps + QR  |  E3: fused filter (particles, walls, floors)
        |                         |
        | estimates               | all records
        v                         v
  AR overlay, 2D map        JSONL log  --export-->  Replay CLI, report (laptop)
                                                          |
                     replay re-runs the same core code <--+  (with new settings)
```

E3 is the estimator we're developing; E1 and E2 run beside it as baselines. Because the replay CLI runs the same core code, any walk can be re-run at a desk with new settings.

### Repo layout

This repo (`kayuwoody/geger`):

```
geger/
  SPEC.md               this spec
  DECISIONS.md          one line per choice the spec didn't cover
  NOTES.md              out-of-scope ideas
  android/
    app/                ARCore, sensors, QR fix, screens, AR overlay, logger
    positioning-core/   plain Kotlin: map, geometry, routing, estimators, log reader
    replay-cli/         JVM CLI that replays logs through positioning-core
  tools/
    map-editor/         single static HTML page
    plates/             plate PDF generator (Python)
    analysis/           report generator (Python)
  maps/
    sample/map.json     hand-written map for tests
```

### Tech stack

| Part | Choice |
| --- | --- |
| Languages | Kotlin (app, core, CLI); Python 3 (plates, analysis); plain JavaScript (map editor) |
| Android | minSdk 29, target the latest stable SDK |
| AR | ARCore SDK, latest stable |
| QR reading | ML Kit Barcode Scanning, bundled model (works offline) |
| Pose maths | OpenCV for Android: `solvePnPGeneric`, `cornerSubPix`, contour finding |
| UI | Jetpack Compose; OpenGL ES for the AR surface |
| JSON | kotlinx.serialization |
| Tests | JUnit for core and CLI; on-device checks as listed in Milestones |
| Plates | Python: `segno` (QR) and `reportlab` (PDF) |
| Analysis | Python: pandas, numpy, matplotlib |

## Coordinate frames and conventions

Every position is converted into the **map frame**, in metres. Most bugs in this kind of app come from mixing frames, so these definitions are binding.

| Frame | Origin | Axes | Notes |
| --- | --- | --- | --- |
| Map | A point picked in the map editor, e.g. a corridor corner | x = right on the plan, y = up on the plan, z = up | A 2D position is (x, y) plus a level id; z is height above that level's floor |
| Heading and facing | — | Degrees, 0 = map +y, clockwise positive, range \[0, 360) | Like a compass, but relative to the plan's "up", not north |
| Plate | Centre of the printed QR code (not the plate edge) | x = right as seen facing the plate, y = up, z = out of the plate toward the viewer | `facing_deg` = map heading of the plate's +z axis |
| ARCore world | Where the session started | Gravity-aligned, y = up, arbitrary yaw | Treat each tracking segment separately; it can jump after a tracking reset |
| ARCore camera | Camera centre | x = right, y = up, z = backward (OpenGL style) | From `frame.camera.pose` |
| OpenCV camera | Camera centre | x = right, y = down, z = forward | What `solvePnP` returns |
| CPU image | Top-left pixel of ARCore's CPU image | u = right, v = down | Sensor orientation (landscape); `camera.imageIntrinsics` matches this image |

**Phone heading** = the horizontal direction of the camera's viewing axis (ARCore camera −z). When the camera points within 30° of straight down (phone held flat), use the phone's top edge (ARCore camera +y) instead. Testers aim the phone the same way when capturing ground truth.

**Phone height**: from the QR fix when available (plate mounting height is known), else 1.4 m above the floor.

**Levels**: each level has `elevation_m`, its floor height above the lowest mapped level.

Required conversion functions, each with a unit test:

- `openCvCamToArCoreCam`: rotate 180° about x (flips y and z).
- `arCoreWorldToMap(alignment)`: a yaw rotation plus a translation (4 degrees of freedom), valid because both frames are gravity-aligned.
- `phoneHeadingDeg(cameraPoseInMap)`: applies the phone-heading rule above.
- `normalizeDeg` and `angleDiffDeg`: wrap to \[0, 360) and to (−180, 180\].

**Timestamps**: everything uses the `SystemClock.elapsedRealtimeNanos()` time base. ARCore frame timestamps and `SensorEvent.timestamp` should share it on these phones; verify in milestone M3 and log any offset found.

## Placeholder map

The placeholder map is one JSON file describing the test route: walkable areas, a route network, QR anchors and checkpoints. It's drawn in the browser map editor over any floor image (a photo of the old directory board, a sketch), scaled with one laser-measured distance.

### Format: `map.json`, schema version 1

```json
{
  "schema": 1,
  "venue": "ekocheras-trial",
  "version": 3,
  "units": "m",
  "levels": [
    {"id": "L1", "name": "Level 1", "elevation_m": 0.0},
    {"id": "L2", "name": "Level 2", "elevation_m": 5.0}
  ],
  "walkable": [
    {"level": "L1", "polygon": [[0, 0], [60, 0], [60, 4], [0, 4]]}
  ],
  "obstacles": [
    {"level": "L1", "polygon": [[20, 1], [22, 1], [22, 3], [20, 3]]}
  ],
  "nodes": [
    {"id": "n1", "level": "L1", "x": 2.0, "y": 2.0},
    {"id": "n2", "level": "L1", "x": 55.0, "y": 2.0},
    {"id": "n3", "level": "L2", "x": 55.0, "y": 12.0}
  ],
  "edges": [
    {"from": "n1", "to": "n2", "kind": "walk"},
    {"from": "n2", "to": "n3", "kind": "escalator_up"}
  ],
  "anchors": [
    {"id": "A01", "level": "L1", "x": 0.2, "y": 2.0, "mount_height_m": 1.5, "facing_deg": 90, "size_m": 0.20}
  ],
  "checkpoints": [
    {"id": "C01", "level": "L1", "x": 10.0, "y": 2.0, "heading_deg": 90}
  ],
  "destinations": [
    {"id": "D1", "name": "Escalator to L2", "node": "n2"}
  ],
  "backgrounds": [
    {"level": "L1", "image": "l1.png", "px_per_m": 12.5, "origin_px": [140, 820]}
  ]
}
```

The numbers above are an illustration, not survey data.

### Rules

- **Walkable**: a position outside every walkable polygon on its level, or inside an obstacle, is impossible. Polygons may overlap; no self-intersections; any winding order.
- **Edge kinds**: `walk`, `stairs`, `lift` (two-way) and `escalator_up`, `escalator_down` (one-way, `from` → `to`).
- **`size_m`**: outer side length of the plate's black square frame (see QR plates), measured with a ruler after printing. Not the white margin, not the plate.
- **`version`**: incremented on every save and logged with each session.
- **Validation on load**: unique ids; edges reference existing nodes; angles in \[0, 360); anchors and checkpoints inside walkable areas (a warning, not an error, since plates sit on walls).

### Map editor (`tools/map-editor`)

A single static HTML page with no build step and no server, opened in Chrome on a laptop.

- Load one background image per level.
- Set scale: click two points, type the laser-measured distance. Set the origin by clicking a point.
- Draw walkable polygons and obstacles.
- Place nodes, connect them with edges, pick each edge's kind.
- Place anchors (click, drag to set facing, type height and size) and checkpoints (click, drag to set heading).
- Name destinations.
- Every placed item also takes typed coordinates, so surveyed positions can be entered exactly.
- Show the cursor position in metres; snap to 0.1 m.
- Import and export `map.json`, showing validation errors before export.

The app loads `map.json` from a file picker, so it can be copied over USB or shared from the laptop.

## QR plates and the QR fix

A QR fix measures the phone's position and heading from one view of a plate of known size, so the tester never has to stand at the plate.

### Plate design

A plain QR code has finder patterns on only three corners, so its fourth corner is a guess. Each plate therefore puts the QR code inside a **black square frame**, and the frame's four sharp outer corners drive the pose maths. The QR code supplies the identity.

- **Payload:** `WF1:<anchorId>`, e.g. `WF1:A01`. Kept short so the code stays version 1 (21 × 21 modules): bigger modules read from further away. Error correction level M.
- **Layout, in QR modules across:** frame 2, white gap 4, QR 21, white gap 4, frame 2 = 33 modules. The frame's outer side is `size_m`.
- **Also printed:** the anchor id in large text, a 10 cm scale bar to check print scale, an arrow marking the top edge, and the nominal size.
- **Default sizes:** 18 cm frame on A4 and 27 cm frame on A3. The calibration test compares both.

### Plate generator (`tools/plates`)

A Python script. Input: `map.json` (or a list of anchor ids) and a size. Output: a print-ready PDF, one plate per page at exact physical size. After printing, the tester measures the frame with a ruler and enters the real size in the map editor.

### Detection pipeline (in the app)

1. Configure the ARCore session with the highest CPU-image resolution the device offers, since distant codes need pixels. Log the resolution chosen.
2. Up to 5 times a second (configurable), take the CPU image (`frame.acquireCameraImage()`) and run ML Kit Barcode Scanning (bundled model, QR only) on its brightness (Y) plane.
3. Keep codes whose payload starts with `WF1:` and whose anchor id exists in the map. ML Kit's corner points give a rough outline.
4. Find the frame: in a region around that outline, threshold the image, find the four-sided contour that encloses the code, and take its outer corners. Refine them to sub-pixel accuracy with OpenCV `cornerSubPix`. If the frame isn't found, fall back to ML Kit's corners and mark the fix as lower quality.
5. Solve the pose with OpenCV `solvePnPGeneric` using `SOLVEPNP_IPPE_SQUARE`. Object points are the frame corners at ±`size_m`/2 in the plate frame, in the order top-left, top-right, bottom-right, bottom-left. Camera matrix from `camera.imageIntrinsics`; no distortion.
6. Resolve the two-solution ambiguity of a flat target using gravity: plates hang on walls, so keep the solution whose plate +y is closest to world up. If neither is within 20° of up, reject the fix.
7. Compute the alignment between ARCore world and the map (next section), then the phone's map pose.

### Alignment maths

Written as transforms, where `T_a←b` maps points in frame b into frame a:

- `T_cam←plate` = `openCvCamToArCoreCam` applied to the `solvePnP` result
- `T_world←cam` = `frame.camera.pose` for the same frame as the image
- `T_map←plate` = from the anchor: (x, y), z = level elevation + `mount_height_m`, yaw from `facing_deg`
- `T_map←world` = `T_map←plate` · (`T_world←cam` · `T_cam←plate`)⁻¹, then reduced to yaw plus translation. The leftover tilt it discards is logged as a quality measure.
- Phone pose in the map = `T_map←world` · `T_world←cam`

### Quality gating

Every fix is logged, accepted or not, with: distance, viewing angle (between plate normal and line of sight), reprojection error in pixels, frame side in pixels, leftover tilt, ambiguity ratio and corner source.

| Check | Default threshold (config) |
| --- | --- |
| Reprojection error | ≤ 2 px |
| Viewing angle | ≤ 65° |
| Frame side in the image | ≥ 40 px |
| Leftover tilt | ≤ 10° |

Starting uncertainty for the fused estimator: position σ = 0.05 m + 3% of distance; heading σ = 2° + 0.5° per metre of distance. These are starting values, refitted from the QR calibration test.

## Estimators

Three estimators run at once on the same inputs, so every walk compares them directly. All three live in `positioning-core` and output a map pose at 10 Hz: x, y, level, heading, and a 1σ position radius. None outputs anything before the first QR fix.

| Id | Name | Motion from | Corrected by | What it tells us |
| --- | --- | --- | --- | --- |
| E1 | ARCore + QR | ARCore camera pose | Each QR fix resets the alignment | How good camera tracking is on its own |
| E2 | Steps + QR | Step detector + gyroscope | Each QR fix resets position and heading | How far we get with the camera blind (pocket) |
| E3 | Fused | ARCore while tracking, steps otherwise | QR fixes, map walls, barometer | The candidate for the product |

### E1: ARCore + QR

Phone map pose = `T_map←world` (from the latest accepted fix) · `T_world←cam`. While ARCore isn't tracking, output the last pose flagged `stale`. Keep the alignment after tracking resumes; measuring the jump is part of the test.

### E2: steps + QR

- **Steps:** Android `TYPE_STEP_DETECTOR`. Step length from config (default 0.70 m), calibrated per tester by walking between two checkpoints a known distance apart.
- **Walking direction:** last known walking direction plus the change in rotation about the vertical axis since then, from `TYPE_GAME_ROTATION_VECTOR` (gyroscope and accelerometer, no compass). Rotation about vertical doesn't depend on how the phone sits in a pocket, as long as it stays put.
- **Pocket transitions:** while the phone is going into or out of a pocket (phone-mode changes), freeze the direction; afterwards assume the walking direction is unchanged.
- **Start:** position and walking direction from the QR fix; direction updates to the phone heading while the phone is held up or flat.

### E3: fused particle filter

The filter keeps many guesses (particles) of the phone's state. Each movement moves every guess with a little random noise; guesses that walk through walls lose weight; QR fixes and floor changes reward guesses that agree with them. Resampling then copies the strong guesses and drops the weak ones.

**Particle state:** x, y, level, `yawOffsetDeg` (rotation from the current motion frame to the map), `stepScale`, weight. Default 1,000 particles.

**Motion frames:** the ARCore world frame while tracking; the game-rotation-vector frame otherwise. On every switch (including ARCore resuming after a loss), shift each particle's `yawOffsetDeg` so its map heading is unchanged at that instant. Motion deltas are only ever taken within one tracking segment.

| Step | Rule | Defaults (config) |
| --- | --- | --- |
| Start | On the first accepted QR fix, draw all particles from the fix's uncertainty; `stepScale` around 1.0 | stepScale σ 0.1 |
| Move (ARCore) | Horizontal camera displacement since the last frame, rotated by each particle's `yawOffsetDeg`, plus noise | 2% of distance + 0.02 m; yaw drift 0.1° per update |
| Move (steps) | One step event: length × `stepScale` along the walking direction + `yawOffsetDeg`, plus noise | length 10%; direction 5° |
| Walls | A move whose path leaves the walkable area or enters an obstacle multiplies the weight by a small factor (not zero, so the filter can't collapse) | × 0.001 |
| QR fix | Weight × Gaussian likelihood of position and heading against the fix. If the effective particle count drops below 10%, redraw half the particles around the fix | σ from the fix's quality |
| Floor change | Particles within reach of a matching escalator, lift or stairs edge move to that edge's far node on the new level, plus noise; all others lose weight | reach 15 m; others × 0.01 |
| Resample | Systematic resampling when the effective particle count drops below half | 50% |
| Output | Weighted mean position on the heaviest level; circular mean heading; σ from the weighted spread | — |

### Floor detection (E2 and E3)

- **Input:** `TYPE_PRESSURE`, smoothed with a 2 s moving median.
- **Relative height:** about 8.3 m per hPa of pressure drop. The baseline resets at every QR fix on a known level, cancelling weather and air-conditioning drift.
- **Event:** height changes by more than 60% of the gap to an adjacent level within 60 s, then holds steady for 3 s. The new level is the one whose elevation best matches.

### Phone mode

Used for the display, for E2's pocket handling and in the logs. Each change needs 0.5 s of agreement (hysteresis).

- **Up:** camera axis within 35° of horizontal → AR view.
- **Flat:** camera axis within 40° of straight down → 2D map.
- **Pocket:** proximity sensor reports near and ambient light below 10 lux for over 1 s.
- **Other:** anything else; keep the previous view.

All thresholds above are starting values in the config file, tuned from the logs.

## App screens and AR overlay

The app is a test instrument: plain screens, big buttons, and a debug overlay. Jetpack Compose for everything except the AR surface. The screen stays on for the whole session.

### Screens

1. **Session setup:** pick `map.json`; pick the run type (QR calibration, route walk, free walk); for route walks, pick the start anchor and destination; label the planned carry mode (up, flat, pocket, mixed); tester initials only; free-text notes. A step-length calibration button. Start.
2. **Main view**, switched by phone mode:
   - **Up:** camera view with the AR overlay, next instruction and distance remaining.
   - **Flat:** 2D map with walkable areas, route line, E3 position dot and uncertainty circle, checkpoints and anchors. Debug toggles show E1 and E2 dots in other colours, and the particles.
   - **Pocket:** logging carries on; the screen dims.
   - A manual override pins the view to AR or 2D for testing.
3. **Debug overlay** (toggle): ARCore tracking state and reason, last QR fix age and quality, current level and relative height, step count, effective particle count, phone mode, and which estimator drives the display (E3 by default, switchable to E1 or E2).
4. **Checkpoint capture:** a big button on the main view. Suggests the next planned checkpoint (or the nearest by E3); the tester confirms the id, stands on the floor mark, aims the phone along the floor arrow and holds still for 2 s. The app logs the truth, all three estimates and the raw sensor heading, then vibrates.
5. **QR calibration mode:** lists the grid positions (distance × angle) for one plate. At each marked spot the tester aims at the plate; the app collects 3 accepted fixes (or gives up after 10 s) and logs them against the spot. Shows live distance, angle and reprojection error.
6. **Sessions:** list of recorded sessions; export one as a zip (logs, `map.json`, config) through the Android share sheet; delete.

There's also a **Note** button on the main view that logs free text with a timestamp ("crowd", "shiny floor", "phone in bag").

### AR overlay

- **Route:** A\* search over the map's node graph, from the node nearest the displayed position (same level, reachable) to the destination. Replan when the position drifts more than 5 m from the route, or every 5 s.
- **Chevrons:** on the floor every 1 m along the next 20 m of route. Floor height: ARCore's detected horizontal plane under the phone when available, else phone height below the camera.
- **Placing map points in AR:** each frame, derive `T_map←world` from the display estimator's pose and the current ARCore camera pose, smoothed with a 1 s low-pass so the arrows don't jitter. Map route points are then drawn through its inverse.
- **Destination pin:** floats 2 m above the destination's floor and stays visible through walls, so it always shows the direction.
- **Instruction banner:** from route geometry. A turn where the route bends more than 30°, "Take the escalator up", "Take the lift to L2", each with the distance to it.
- **Renderer:** ARCore with OpenGL ES, following the patterns of Google's `hello_ar_kotlin` sample (camera background plus simple meshes). No third-party 3D engine.

## Logging

Each session writes one JSON Lines file to app-private storage, gzipped when the session ends: `session-<yyyyMMdd-HHmmss>.jsonl.gz`. Every record has `t` (`elapsedRealtimeNanos`) and `type`. The logs hold everything needed to replay a walk offline, so the estimators can be tuned without going back to the mall.

| `type` | Rate | Fields |
| --- | --- | --- |
| `session_start` | Once | App version, device model, Android version, map venue and version, full config, ARCore camera config (CPU image size, frame rate), run type, tester initials, planned carry mode, notes |
| `arcore_frame` | Every frame (\~30 Hz) | Tracking state, failure reason, camera position (x, y, z) and orientation quaternion in ARCore world |
| `imu` | 100 Hz | Accelerometer and gyroscope, x y z |
| `rotation` | 50 Hz | Game rotation vector quaternion; rotation vector quaternion (with compass, for comparison) |
| `magnetometer` | 50 Hz | x y z in µT |
| `pressure` | Sensor rate | hPa |
| `step` | Each step | — |
| `proximity_light` | On change | Near or far, lux |
| `qr_detection` | Each detection | Anchor id, payload, ML Kit corners, frame corners (or null), corner source, intrinsics (fx, fy, cx, cy, width, height), both pose candidates, chosen one, reprojection error, distance, viewing angle, leftover tilt, accepted or rejected with reason, resulting phone map pose |
| `estimate` | 10 Hz per estimator | Estimator id, x, y, level, heading, σ, stale flag, motion source |
| `particles` | 1 Hz, off by default | E3 snapshot: x, y, level, weight per particle |
| `floor_event` | Each event | From level, to level, height change, confidence |
| `phone_mode` | On change | up, flat, pocket, other |
| `route` | Each replan | Destination, node ids |
| `checkpoint` | Each capture | Checkpoint id, truth (x, y, level, heading), each estimator's estimate at that moment, raw camera heading, phone mode |
| `calib_point` | Each grid spot | Anchor id, grid distance and angle, ids of the fixes collected |
| `note` | Each note | Text |
| `session_end` | Once | Duration, record counts, dropped-record count, end reason |

- **Writer:** its own background thread with a large buffered queue, flushed every second. It never blocks the sensor or render threads; any dropped records are counted and reported in `session_end`.
- **Reader:** `positioning-core` has a `LogReader` for the same format, used by the replay CLI. A write-then-read round-trip test is part of M3.
- **Export zip:** the session file, the exact `map.json` used and the config.

## Replay and analysis tools

The replay CLI re-runs the estimators on a recorded walk with new settings; the analysis script turns any run into a report. Together they let us tune at a desk after each mall visit.

### Replay CLI (`android/replay-cli`)

- A JVM command-line app that uses `positioning-core` unchanged.
- **Input:** an exported session zip, plus an optional config override JSON.
- **Run:** feeds the logged sensor records through E1, E2 and E3 in timestamp order. QR fixes come from the logged `qr_detection` records, so no images are needed.
- **Output:** `replay-estimates.jsonl` in the same `estimate` format, plus the checkpoint comparisons.
- **Deterministic:** a fixed random seed in the config, so the same input and config always give identical output. Covered by a unit test.
- **Sweeps:** `--sweep name=a,b,c` runs once per value and writes one output each, for tuning.

### Analysis report (`tools/analysis`)

`analyse.py <session zip or replay output> --out <folder>` writes a self-contained `report.html` that opens offline:

- **Summary per estimator:** checkpoints captured; position and heading error as median, 90th percentile and max; pass or fail against the targets.
- **Error at each checkpoint**, in route order, per estimator.
- **Position error against distance walked since the last QR fix.**
- **Track map:** true checkpoints and each estimator's track on the 2D map.
- **Timeline:** tracking state, phone mode, estimated level (with true level at checkpoints), barometer relative height.
- **QR calibration:** fix error against distance and against viewing angle, per plate size, with a fitted error model to feed the config's σ values.
- **Tracking losses:** start, duration, ARCore's reason.

It also writes `summary.csv`, one row per session and estimator, to compare many runs in a spreadsheet. Python 3 with pandas, numpy and matplotlib, pinned in `requirements.txt`; plots embedded in the HTML.

## Milestones

Seven milestones, built in order. Each one is done when every check under it passes and the work is committed. An office or home dry run (a 30 m loop with 4 checkpoints and 2 plates) happens before any mall visit.

- [ ] **M1: repo and core model**
  - Build: Gradle project with `app`, `positioning-core` and `replay-cli`; `tools/` folders; README with build steps; config defaults. In core: map model, loader and validator; point-in-polygon and path-crosses-wall geometry; A\* routing; frame conversions; angle helpers. A hand-written sample map for tests.
  - Check: `./gradlew test` passes. Tests cover: sample map loads; broken maps are rejected with clear messages; A\* routes across an escalator and respects one-way edges; wall-crossing edge cases; every conversion round-trips.
- [ ] **M2: map editor**
  - Build: `tools/map-editor` as specified in Placeholder map.
  - Check: build the dry-run map from a photo and one measured distance; export passes validation; the app's loader accepts it; import then export gives identical JSON.
- [ ] **M3: sensor recorder and logging**
  - Build: ARCore session at the highest CPU-image resolution; every sensor in the Logging table; JSONL writer; session list; export.
  - Check: a 5-minute desk session on the S23 Ultra records with zero dropped records; the export reads back through `LogReader` with matching counts; each sensor rate is within 10% of target; the timestamp-base check is done and written up in `DECISIONS.md`.
- [ ] **M4: plates, QR fix and calibration mode**
  - Build: `tools/plates`; detection pipeline; pose solve; gravity disambiguation; alignment; gating; calibration screen.
  - Check: synthetic test projects the frame corners from 50 random poses (1–10 m, up to 60°), adds 0.5 px noise, and recovers position within 2% of distance and heading within 2°. A plate rotated 90° and 180° on a desk gives correctly rotated poses. Tape-measured desk distances of 1, 2 and 4 m come back within 5%.
- [ ] **M5: estimators and 2D map**
  - Build: E1, E2 and E3 in core; floor detection; phone mode; live 2D map; checkpoint capture.
  - Check: unit tests where a synthetic corridor walk with added drift stays inside the walls and snaps back after a QR fix; the floor detector fires on a synthetic one-level pressure step and not on 0.3 hPa of drift over 10 minutes; resampling keeps total weight at 1; a synthetic L-shaped walk works for E2. The dry run produces complete checkpoint records.
- [ ] **M6: AR overlay**
  - Build: routing to a destination, floor chevrons, destination pin, instruction banner, view switching by phone mode.
  - Check: on the dry run, chevrons sit on the floor and follow the corridor after a QR fix; the view switches between AR and 2D within 1 s of tilting; frame rate and phone temperature are logged, with no thermal shutdown over 15 minutes.
- [ ] **M7: replay and analysis**
  - Build: replay CLI and analysis report as specified.
  - Check: replaying a session with its own config reproduces E1 exactly and E3's median checkpoint error within 10%; the report renders for the dry run; a sweep writes one output per value.
- [ ] **Stretch, only when asked: WebXR check**
  - Build: one web page using WebXR AR in Chrome on Android, showing floor chevrons from a fixed alignment and logging poses.
  - Check: a side-by-side note comparing its tracking with the native app on the same loop.

Phase 0 build is done when M1–M7 are ticked, both phones have the app installed, plates are printed and measured, and the dry-run report exists.

## On-site test protocol

This part is for the tester. Run the same steps at small scale in the office dry run first.

### Before the visit

- [ ] **Mall permission:** plates on walls and pillars (removable mounting), tape marks on the floor, walking around holding a phone up, and agreed times.
- [ ] **Pick the route:** about 200 m with at least 3 turns, one long straight corridor, a shiny-floor stretch, an escalator up and a lift back down.
- [ ] **Equipment:** laser distance meter, tape measure, floor tape in two colours, printed plates in both sizes, removable mounting, both phones charged plus a power bank, laptop with the map editor.
- [ ] **Plates:** 4–6 along the route (start, before and after the escalator, mid-route, at the lift), frame centre at 1.5 m, facing into the corridor. Measure each printed frame with a ruler.
- [ ] **Checkpoints:** a tape X every 15–25 m and at every turn, with a tape arrow pointing along the route.
- [ ] **Survey:** measure every plate and checkpoint with the laser from two fixed features (walls, pillars) that appear on the floor image. Type the coordinates, heights and facings into the map editor.
- [ ] **Check the map:** load it in the app and confirm plates and checkpoints land in the right places on the 2D map.

### Runs

Unless a run says otherwise, scan only the start plate, from wherever you stand (vary it between 2 and 6 m away), and capture every checkpoint.

| Run | What | Carry | Repeats per phone |
| --- | --- | --- | --- |
| A | QR calibration: each plate size at 1, 2, 4, 6, 8 and 10 m, at 0°, 30°, 50° and 65° (spots marked with tape) | Hand | 1 |
| B | Full route | Held up (AR) | 3 |
| C | Full route | Flat (2D map) | 3 |
| D | Full route; phone out only to capture checkpoints | Pocket | 3 |
| E | Full route, scanning every plate passed (re-anchoring) | Mixed | 2 |
| F | Escalator up, then lift down | Held up | 3 |
| G | Run B again at a busy time (weekend afternoon) | Held up | 2 |

During every run: add a note for anything unusual (crowd, blocked path, phone in a bag), keep walkways clear, and don't photograph shoppers.

### After each visit

- [ ] Export every session from both phones to the laptop.
- [ ] Run the analysis report for each session and the combined `summary.csv`.
- [ ] Hand the zips and reports to the coding agent for tuning with the replay CLI.
- [ ] Note map corrections and bump the map version.
