# Decisions

One line per choice that SPEC.md didn't cover: what was decided, and why. Newest last.

- Phase 0 is a native Android app (Kotlin + ARCore) for the S22/S23 Ultra: the only test phones, full sensor and camera access, and ARCore results carry over to any final AR stack.
- QR plates put the code inside a black square frame: a QR code has finder patterns on only three corners, so the frame's four sharp corners drive the pose maths.
- M1: toolchain is Gradle 9.9.2, Android Gradle Plugin 9.5.1, Kotlin 2.4.21, JUnit 6.1.3 — the latest stable releases at the time; AGP 9 compiles Kotlin itself, so the app applies no separate Kotlin Android plugin.
- M1: compileSdk and targetSdk 37 — the Compose libraries in BOM 2026.09.00 require compiling against API 37, and the spec asks for the latest stable SDK; minSdk stays 29.
- M1: the Gradle project root is `android/` (matching the spec's repo layout); the `app` module is only included when an Android SDK is found, so the JVM modules and their tests build anywhere.
- M1: Java packages are `com.geger.positioning` (core), `com.geger.replay` (CLI) and `com.geger.trial` (app); application id `com.geger.trial`. Rename when the product has a name.
- M1: route cost = 3D length in metres, plus a 20 m lift penalty (config `display.liftPenaltyM`) standing in for waiting time; step-free routes skip stairs and escalators.
- M1: ARCore-world "heading 0" is defined as world −z, measured clockwise from above, so the ARCore→map alignment is just "heading + yaw"; at zero yaw ARCore −z maps to map +y.
- M1: the phone-heading rule's top-edge case uses ARCore's display-oriented camera pose (`camera.displayOrientedPose`), because the sensor-oriented `camera.pose` is landscape and its +y is not the screen's top edge in a portrait app.
- M1: map validation errors make a map unusable; geometry findings (nodes, plates, checkpoints outside walkable space, edges cutting through walls, unconnected nodes) are warnings so an imperfect survey doesn't block a test day. Lift edges must change level; stairs may stay on one level.
- M1: the config reader rejects unknown field names so a misspelt setting fails loudly instead of being ignored; map files ignore unknown fields so newer editors can add data.
- M2: the map editor is `index.html` plus two plain scripts (`map-core.js`, `editor.js`) in one folder — still no build step and opens from disk — so the validation logic can also be unit-tested under Node.
- M2: the editor re-implements map validation in JavaScript; shared fixtures in `maps/fixtures/` (ok-* load, bad-* rejected) are checked by both the Kotlin and the Node tests to keep the two in step.
- M2: map numbers must be real JSON numbers in both validators; the Kotlin loader previously accepted numbers written as text ("x": "1"), found by the shared fixtures and made strict.
- M2: export writes canonical JSON (fixed key order, one item per line, numbers rounded to 6 decimals, unknown fields kept at the end). "Version incremented on every save" is implemented as: the version goes up by one when a *changed* map is exported, so re-exporting an unchanged map gives a byte-identical file (the M2 check).
- M2: with Snap on, placed points snap to 0.1 m and dragged angles to 1°; exact surveyed values are typed in the Selection panel.
- M2: a loaded floor image gets a provisional background (origin at its bottom-left corner, 20 px/m) until Scale and Origin are set; the image file itself is not stored, only its name, scale and origin.
- M2: new plates and checkpoints get ids A01/C01 (zero-padded as in the spec's examples); nodes n1, levels L1, destinations D1.
