# Decisions

One line per choice that SPEC.md didn't cover: what was decided, and why. Newest last.

- Phase 0 is a native Android app (Kotlin + ARCore) for the S22/S23 Ultra: the only test phones, full sensor and camera access, and ARCore results carry over to any final AR stack.
- QR plates put the code inside a black square frame: a QR code has finder patterns on only three corners, so the frame's four sharp corners drive the pose maths.
