# Notes

Ideas outside Phase 0 scope. Recorded here instead of built.

- Walkability checks every polygon edge on a level for each path (exact but O(edges)). If the particle filter is too slow on the phone in M5, add a uniform grid index of polygon edges per level.
- Map files could carry a `name`/`label` per walkable area (e.g. "Concourse L1") for nicer instructions later.
- QR fixes are weak for distant head-on views. Ideas for later: combine several sightings of the same plate as the user moves (ARCore knows the camera motion, so the bearings triangulate the plate); seed the particle filter with BOTH mirror hypotheses when a fix is ambiguous and let the walls decide; use two plates in view for an accurate heading.
- Plate size trade-off for the mall: 27 cm plates on A3 roughly double the usable range of 18 cm plates.

## Positioning beyond QR plates (discussion, 2026-11-23)

- **QR plates are chokepoint anchors, not continuous positioning.** Codes need a steady, close, sharp view, and most of the time people's phones are in a pocket or held flat looking down. Place plates where people pause or funnel: entrances, kiosks, escalator entries/exits, lift lobbies.
- **Re-measure instead of re-read.** Once a plate has been decoded, its position in ARCore's tracked space is known; later frames can re-find its black frame by edge fitting near the predicted spot without decoding the QR again. Frame finding tolerates far more blur and distance than QR decoding.
- **Tracking markers.** ArUco/AprilTag-style square markers are built for fast detection from far away and through motion blur. A plate could carry one for the app plus a small QR so a normal camera app still opens a link. Worth a side-by-side test against QR.
- **Floor decals at chokepoints.** When the phone is held flat its camera sees the floor, so a marker decal on the floor at an escalator landing could anchor people looking down. Only where foot traffic funnels (we can't predict where people walk elsewhere).
- **Phone flat still tracks motion.** ARCore tracks from floor texture when the phone is held flat (no plates needed); shiny plain floors may be weak. The trial's flat runs measure this.
- **Pocket mode relies on steps + gyroscope + barometer + map walls** (E2/E3). The trial's pocket runs measure the drift.
- **Bluetooth beacons** are the practical infrastructure option for phones that aren't looking at anything: the phone hears them in a pocket, roughly 2–4 m accuracy, coin-cell batteries last 1–3 years. GPS does not work indoors. Candidate for the trial: log every beacon's signal strength alongside the other sensors and evaluate offline in the replay.
- **Magnetic fingerprinting** needs no hardware: steel in the building distorts the magnetic field in a repeatable pattern that can be mapped by walking the routes. The magnetometer is already logged at 50 Hz, so this can be evaluated offline from trial walks.
