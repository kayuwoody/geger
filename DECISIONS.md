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
