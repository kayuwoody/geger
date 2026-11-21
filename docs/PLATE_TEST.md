# Plate test (milestone M4 check)

Checks that the app reads a QR plate and measures its distance correctly. Takes about 15 minutes.
No map is needed.

**You need:**
- one printed plate
- a tape measure (or laser)
- some floor tape
- a wall with about 5 m of clear floor in front of it

## 1. Print and measure the plate

1. Print **page 1 (A01)** of `geger-plates-A4-18cm.pdf` at **100% / Actual size**. Don't use "Fit to page".
2. Check the **10 cm scale bar** with a ruler. If it isn't 10 cm, the printer scaled the page; reprint.
3. Measure the **black frame's outer edge** in cm, to the millimetre (about 18.0). Write it down.

## 2. Put it on the wall

1. Stick the plate **flat on the wall**, upright (the "TOP" text at the top). Use tape on all four
   corners so it doesn't curl.
2. Put its centre at about the height you naturally hold your phone when looking at its screen
   (around 1.4–1.5 m).
3. On the floor, measure straight out from the wall below the plate. Put tape marks at **1 m, 2 m and 4 m**.

## 3. Record

1. Open **Geger Trial**. Run type: **Plates**.
2. In **Frame size for plates not on the map**, type the size you measured (e.g. `18.0`).
   Map: **No map**.
3. Tap **Start recording**. Move the phone gently for a few seconds until AR says **TRACKING**.
4. For each spot, 1 m, then 2 m, then 4 m:
   1. Stand at the tape mark and hold the phone **directly above the mark**, camera at about plate
      height, pointing at the plate.
   2. The **QR** line turns green and shows the distance, e.g. `QR A01: 2.01 m`.
   3. In the panel at the bottom, pick the **distance** chip (1, 2 or 4) and the angle **0**,
      then tap **Capture**.
   4. Hold still until it says **Captured … measured 2.01 m (+0.5%)**. It takes 3 good readings
      and gives up after 10 s.
5. **Rotation check:** at 1 m, look at the "rotated …°" number in the QR line. Take the plate off,
   stick it back **turned a quarter turn**, then **upside down**. Each time the rotated number should
   change by about 90°, and the line should say *Rejected: plate not upright*.

   That rejection is correct, because a plate mounted sideways on a wall would mislead the
   tracking. Put it back upright afterwards.
6. **Optional:** stand 2 m away but off to one side, about 30° from straight on, and capture with
   chips **2** and **30**.
7. Tap **Stop**. On the summary, check the line **QR plates: N of M sightings accepted**.

## 4. Send the results

1. Take a screenshot of the summary.
2. Tap **Export session** and attach the zip in the chat.

The M4 check passes when the 1, 2 and 4 m captures come back within **5%** of the tape (e.g. 4 m
reads between 3.80 and 4.20 m).

## What the QR line means

| Shown | Meaning |
| --- | --- |
| `2.01 m · 12° from head-on` | Distance from the camera to the plate centre, and how far off straight-on you are |
| `frame 118 px` | How big the black frame looks in the camera image; under 40 px is too small to trust |
| `fit 0.21 px` | How well the measured corners match a perfect square; under 2 px is good |
| `tilt 1.3°` | How far the plate seems to lean from vertical; over 10° means it's skewed or curling |
| `frame` / `detector` | Whether the black frame's edges were found precisely (`frame`) or only roughly |
| `Rejected: …` | Why this reading won't be used for positioning (it's still logged) |
