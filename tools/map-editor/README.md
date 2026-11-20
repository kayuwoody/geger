# Map editor

Draws the placeholder `map.json` for the positioning trial over any floor image: walkable areas,
obstacles, the route network, QR plates, checkpoints and destinations. It checks the map with the
same rules as the phone app while you draw. See `SPEC.md`, "Placeholder map".

## Open it

Double-click `index.html` (Chrome on a laptop). No install, no server, no internet needed.
`map-core.js` and `editor.js` must stay in the same folder as `index.html`.

## Build a map from a photo

1. **Floor image.** Take a straight-on photo of the floor plan (old directory board, a printout,
   a sketch). *Load image…* in the Floor image panel.
2. **Scale.** Press **S**, click two points on the image whose real distance you measured with the
   laser (the further apart the better), and type the distance in metres.
3. **Origin.** Press **G** and click a fixed corner you can measure from (e.g. a corridor corner).
   That point becomes (0, 0); x runs right and y runs up on the image.
4. **Walkable areas.** Press **W** and click the corners of each corridor or open area. Click the
   first corner (or press Enter) to finish. Overlapping areas are fine.
5. **Obstacles.** Press **O** the same way for pillars, kiosks, planters.
6. **Route network.** Press **N** to place nodes at junctions, doors, and escalator and lift ends.
   Press **E**, pick the kind under *New edges*, then click two nodes. For a lift or escalator,
   click the first node, switch level, click the second; escalators run from first to second.
7. **QR plates.** Press **A**, press where the plate hangs and drag the way it faces. Set the
   default height and frame size under *New edges* first, or type them per plate.
8. **Checkpoints.** Press **C**, press on the floor mark and drag along its tape arrow.
9. **Destinations.** Select a node (**V**, click it) and *Make destination*, then name it.
10. **Exact values.** Select any item with **V** to type surveyed coordinates and angles.
11. **Export.** *Export map.json*. Export is blocked while there are errors; warnings are allowed.
    The version goes up by one each time you export a changed map.

The image itself is not stored in `map.json` (only its file name, scale and origin). After opening a
map again, load the same image for each level.

Controls: wheel zooms, right-drag or Space+drag pans, **F** fits the view, Ctrl+Z / Ctrl+Y undo
and redo, Delete removes the selection, Esc cancels a shape in progress.

## Check a map with the phone app's loader

From `android/`:

```bash
./gradlew -q :replay-cli:run --args="validate-map /path/to/map.json"
```

## Tests

From the repo root:

```bash
node --test tools/map-editor/test/*.test.js
```

Unit tests for `map-core.js`. The shared fixtures in `maps/fixtures/` are also checked by the
Kotlin tests, so both validators agree on which maps are usable.

```bash
node tools/map-editor/test/e2e.js /tmp/editor-e2e
```

Optional browser test (needs Playwright with Chromium). It draws a map on a generated floor image,
exports it, reopens it and checks the re-export is byte-identical.
