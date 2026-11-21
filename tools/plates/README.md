# Plate generator

Makes print-ready QR plates: a QR code (payload `WF1:<anchor id>`) inside a thick black square
frame, one plate per page, at exact physical size. The app reads the code to know which plate it
sees and uses the frame's four corners to measure distance and angle (`SPEC.md`, "QR plates and the
QR fix").

## Use

```bash
pip install segno reportlab
```

Then, from the repo root:

```bash
python3 tools/plates/make_plates.py --ids A01,A02,A03 --size-cm 18 --out plates-a4.pdf
```

```bash
python3 tools/plates/make_plates.py --map path/to/map.json --size-cm 27 --out plates-a3.pdf
```

- `--size-cm` is the black frame's outer size. 18 cm fits A4 and 27 cm fits A3; paper is chosen
  automatically, or pass `--paper`.
- Print at **100% / Actual size** and check the 10 cm scale bar.
- Measure each frame with a ruler and enter the real size in the map editor (`size_m`), or in the
  app's "frame size" field for a plate test.

## Which size

From the synthetic tests (M4), with corners measured to half a pixel, the 27 cm plate is accepted
in more views than the 18 cm plate (30 vs 20 of 50 random views, 1–10 m). Readings come from about
**1–6 m for 18 cm** and **1–9 m for 27 cm**. The QR reader itself needs roughly 2 pixels per QR
module, which on a 1080p camera image means about **4 m for 18 cm** and **6 m for 27 cm** plates.

Views from the side (15° or more from straight-on) are the most accurate; distant head-on views
measure distance well but heading poorly.

The layout (frame 2 modules, gap 4, QR 21, gap 4, frame 2 = 33 modules) must match `PlateLayout`
in `android/positioning-core`.
