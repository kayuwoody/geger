# Notes

Ideas outside Phase 0 scope. Recorded here instead of built.

- Walkability checks every polygon edge on a level for each path (exact but O(edges)). If the particle filter is too slow on the phone in M5, add a uniform grid index of polygon edges per level.
- Map files could carry a `name`/`label` per walkable area (e.g. "Concourse L1") for nicer instructions later.
- QR fixes are weak for distant head-on views. Ideas for later: combine several sightings of the same plate as the user moves (ARCore knows the camera motion, so the bearings triangulate the plate); seed the particle filter with BOTH mirror hypotheses when a fix is ambiguous and let the walls decide; use two plates in view for an accurate heading.
- Plate size trade-off for the mall: 27 cm plates on A3 roughly double the usable range of 18 cm plates.
