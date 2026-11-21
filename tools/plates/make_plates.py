#!/usr/bin/env python3
"""
Print-ready QR plates for the positioning trial (SPEC.md, "QR plates and the QR fix").

Each plate is a QR code inside a thick black square frame. The QR code tells the app *which*
plate it is looking at (payload "WF1:<anchor id>"); the frame's four sharp outer corners let the
app measure *where* the phone is (distance and angle), because the frame's real size is known.

Layout, in QR modules (the QR code's little squares) from the outside in:
    frame 2 | white gap 4 | QR code 21 | white gap 4 | frame 2   = 33 modules across
The outer side of the black frame is the plate size (size_m in map.json). These numbers must
match PlateLayout in android/positioning-core (the app's detector relies on them).

Usage (from the repo root; needs: pip install segno reportlab):
    python3 tools/plates/make_plates.py --ids A01,A02,A03 --size-cm 18 --out plates.pdf
    python3 tools/plates/make_plates.py --map path/to/map.json --size-cm 27 --out plates-a3.pdf

Paper is chosen from the size (A4 up to 18.5 cm, A3 above) unless --paper is given.
Print at 100% ("Actual size"), then measure each frame with a ruler and enter the real size in
the map editor: printers are rarely exact.
"""
import argparse
import json
import sys

import segno
from reportlab.lib.pagesizes import A3, A4
from reportlab.lib.units import mm
from reportlab.pdfgen import canvas

# ---- Plate layout (keep in step with PlateLayout.kt) -------------------------------------------
FRAME_MODULES = 2
GAP_MODULES = 4
QR_MODULES = 21
TOTAL_MODULES = 2 * FRAME_MODULES + 2 * GAP_MODULES + QR_MODULES  # 33
PAYLOAD_PREFIX = "WF1:"


def qr_matrix(anchor_id):
    """
    The 21 x 21 module grid (True = dark) of the plate's QR code.

    Concept: a short payload keeps the code at version 1, the smallest QR size (21 x 21 modules);
    for a fixed printed size, fewer modules means bigger modules, which read from further away.
    Error correction level M survives some dirt or glare.
    """
    payload = PAYLOAD_PREFIX + anchor_id
    qr = segno.make_qr(payload, error="m", boost_error=False)
    if qr.version != 1:
        sys.exit(f"Anchor id '{anchor_id}' is too long: '{payload}' needs QR version {qr.version}; keep ids short (e.g. A01).")
    rows = [[bool(v) for v in row] for row in qr.matrix_iter(scale=1, border=0)]
    assert len(rows) == QR_MODULES and all(len(r) == QR_MODULES for r in rows)
    return rows


def draw_plate(c, page_w, page_h, anchor_id, size_mm):
    """
    Draws one plate centred near the top of the page, with its id, size note and a 10 cm
    scale bar underneath for checking the print scale.
    """
    module = size_mm / TOTAL_MODULES
    left = (page_w - size_mm * mm) / 2
    top = page_h - 22 * mm
    bottom = top - size_mm * mm

    c.setFont("Helvetica-Bold", 11)
    c.drawCentredString(page_w / 2, top + 7 * mm, "TOP  -  mount with this edge up")

    # Black frame: a full black square, then the white inside (gap + QR area).
    c.setFillColorRGB(0, 0, 0)
    c.rect(left, bottom, size_mm * mm, size_mm * mm, stroke=0, fill=1)
    inset = FRAME_MODULES * module * mm
    c.setFillColorRGB(1, 1, 1)
    c.rect(left + inset, bottom + inset, size_mm * mm - 2 * inset, size_mm * mm - 2 * inset, stroke=0, fill=1)

    # QR modules. Row 0 of the matrix is the top row of the printed code. All dark modules go into
    # one path filled at once, so touching squares merge with no hairline seams between them.
    c.setFillColorRGB(0, 0, 0)
    qr_left = left + (FRAME_MODULES + GAP_MODULES) * module * mm
    qr_top = top - (FRAME_MODULES + GAP_MODULES) * module * mm
    path = c.beginPath()
    for r, row in enumerate(qr_matrix(anchor_id)):
        col = 0
        while col < QR_MODULES:
            if not row[col]:
                col += 1
                continue
            start = col
            while col < QR_MODULES and row[col]:
                col += 1
            # One rectangle per horizontal run of dark modules.
            path.rect(qr_left + start * module * mm, qr_top - (r + 1) * module * mm, (col - start) * module * mm, module * mm)
    c.drawPath(path, stroke=0, fill=1)

    # Id and instructions.
    y = bottom - 22 * mm
    c.setFont("Helvetica-Bold", 44)
    c.drawCentredString(page_w / 2, y, anchor_id)
    c.setFont("Helvetica", 10)
    lines = [
        f"QR payload {PAYLOAD_PREFIX}{anchor_id}   |   black frame (outer edge): {size_mm / 10:.1f} cm nominal",
        "Measure the black frame's outer edge with a ruler and enter the real size in the map editor (size_m).",
        "Print at 100% / Actual size - not 'fit to page'. Keep the white border around the frame.",
    ]
    for i, text in enumerate(lines):
        c.drawCentredString(page_w / 2, y - (9 + 5 * i) * mm, text)

    # 10 cm scale bar with 1 cm ticks.
    bar_y = y - 32 * mm
    bar_left = (page_w - 100 * mm) / 2
    c.setLineWidth(0.8)
    c.line(bar_left, bar_y, bar_left + 100 * mm, bar_y)
    for i in range(11):
        h = 4 * mm if i % 5 == 0 else 2.5 * mm
        c.line(bar_left + i * 10 * mm, bar_y, bar_left + i * 10 * mm, bar_y + h)
    c.setFont("Helvetica", 9)
    c.drawCentredString(page_w / 2, bar_y - 5 * mm, "10 cm - check with a ruler: if this isn't 10 cm, the print was scaled")


def main():
    p = argparse.ArgumentParser(description="Make print-ready QR plates (one per page).")
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--ids", help="comma-separated anchor ids, e.g. A01,A02")
    src.add_argument("--map", help="map.json: makes a plate for every anchor in it")
    p.add_argument("--size-cm", type=float, default=18.0, help="black frame outer size in cm (default 18)")
    p.add_argument("--paper", choices=["A4", "A3"], help="paper size (default: A4 up to 18.5 cm, else A3)")
    p.add_argument("--out", default="plates.pdf", help="output PDF path")
    a = p.parse_args()

    if a.map:
        with open(a.map, encoding="utf-8") as f:
            ids = [anchor["id"] for anchor in json.load(f).get("anchors", [])]
        if not ids:
            sys.exit("The map has no anchors.")
    else:
        ids = [s.strip() for s in a.ids.split(",") if s.strip()]

    paper = a.paper or ("A4" if a.size_cm <= 18.5 else "A3")
    page_w, page_h = A4 if paper == "A4" else A3
    size_mm = a.size_cm * 10
    if size_mm * mm > page_w - 10 * mm:
        sys.exit(f"A {a.size_cm} cm plate doesn't fit on {paper}; use A3 or a smaller size.")

    c = canvas.Canvas(a.out, pagesize=(page_w, page_h))
    c.setTitle(f"Geger QR plates {a.size_cm:.0f} cm")
    for anchor_id in ids:
        draw_plate(c, page_w, page_h, anchor_id, size_mm)
        c.showPage()
    c.save()
    print(f"Wrote {len(ids)} plate(s), {a.size_cm:.1f} cm frame on {paper}: {a.out}")


if __name__ == "__main__":
    main()
