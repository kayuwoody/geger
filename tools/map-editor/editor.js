/*
 * editor.js — the map editor's screen: drawing, tools, panels, import and export.
 *
 * Concept: the whole map lives in one plain object (S.map) shaped exactly like map.json. Every
 * edit goes through edit(), which saves an undo snapshot, applies the change and redraws. The
 * canvas shows one level at a time in map coordinates (metres, x right, y up), converted to screen
 * pixels by a pan/zoom transform. Validation (map-core.js) re-runs after every change so problems
 * show up while drawing, not only at export.
 */
(function () {
  'use strict';
  const C = window.MapCore;

  // ===========================================================================================
  // State
  // ===========================================================================================

  /**
   * Everything the editor knows.
   *  map       the map being edited, in map.json shape
   *  level     id of the level shown on the canvas
   *  images    loaded floor images per level id: { img, objectUrl }
   *  view      pan/zoom: scale = screen px per metre, (ox, oy) = screen position of map origin
   *  tool      active tool name (see TOOLS)
   *  draft     tool-specific work in progress (polygon corners, first edge node, drag start...)
   *  sel       current selection: { type, index } or { type: 'vertex', area, index, vertex }
   *  baseline  canonical text of the map as last opened or exported (to detect unsaved changes)
   *  undo/redo stacks of JSON snapshots of map
   */
  const S = {
    map: C.emptyMap(),
    level: 'L1',
    images: {},
    view: { scale: 20, ox: 80, oy: 500 },
    tool: 'select',
    draft: null,
    sel: null,
    baseline: '',
    undo: [],
    redo: [],
    mouse: { sx: 0, sy: 0, inside: false },
    pan: null,
    drag: null,
    spaceDown: false,
    bgOpacity: 0.6,
    validation: { errors: [], warnings: [] },
  };
  S.baseline = C.formatMap(S.map);

  const $ = id => document.getElementById(id);
  const canvas = $('canvas');
  const ctx = canvas.getContext('2d');

  /** Tool names, their status-bar hints and keyboard shortcuts. */
  const TOOLS = {
    select: { key: 'v', hint: 'Click to select, drag to move. Delete removes the selection. Right-drag or Space+drag pans, wheel zooms, F fits.' },
    walkable: { key: 'w', hint: 'Click to add corners of a walkable area. Click the first corner or press Enter to finish. Backspace removes the last corner, Esc cancels.' },
    obstacle: { key: 'o', hint: 'Click to add corners of an obstacle (pillar, kiosk, planter). Click the first corner or press Enter to finish.' },
    node: { key: 'n', hint: 'Click to place a route node (junction, door, escalator or lift end).' },
    edge: { key: 'e', hint: 'Click the first node, then the second. Switch level between clicks for lifts and escalators; escalators run from first to second.' },
    anchor: { key: 'a', hint: 'Press where the QR plate hangs and drag the way it faces (into the corridor). Type exact values in Selection.' },
    checkpoint: { key: 'c', hint: 'Press on the floor mark and drag along its tape arrow. Type exact surveyed values in Selection.' },
    scale: { key: 's', hint: 'Click two points on the floor image a known distance apart, then type the measured distance in metres.' },
    origin: { key: 'g', hint: 'Click the point on the floor image that should be the map origin (0, 0).' },
    beacon: { key: 'b', hint: 'Click where a Bluetooth beacon hangs. Set its iBeacon UUID, major and minor in Selection (defaults under New edges).' },
  };

  // ===========================================================================================
  // Coordinates
  // ===========================================================================================

  /** Map metres → canvas pixels. Map y points up, screen y points down, hence the minus. */
  const toScreen = p => ({ x: S.view.ox + p.x * S.view.scale, y: S.view.oy - p.y * S.view.scale });

  /** Canvas pixels → map metres (inverse of toScreen). */
  const toMap = (sx, sy) => ({ x: (sx - S.view.ox) / S.view.scale, y: (S.view.oy - sy) / S.view.scale });

  /** Rounds a point to 0.1 m when snapping is on, otherwise to a micrometre. */
  function snapPt(p) {
    const step = $('snap').checked ? 10 : 1e6;
    return { x: C.roundNum(Math.round(p.x * step) / step), y: C.roundNum(Math.round(p.y * step) / step) };
  }

  /**
   * Map heading (degrees, 0 = map +y, clockwise) of the direction from a to b.
   * Rounded to whole degrees when snapping is on (a mouse drag can't aim better than that anyway;
   * exact surveyed angles are typed in Selection), otherwise to 0.1°.
   */
  function headingDeg(a, b) {
    const h = (Math.atan2(b.x - a.x, b.y - a.y) * 180) / Math.PI;
    const n = ((h % 360) + 360) % 360;
    const step = $('snap').checked ? 1 : 10;
    return C.roundNum((Math.round(n * step) / step) % 360);
  }

  /** Unit map vector for a heading (inverse of headingDeg). */
  const headingVec = deg => ({ x: Math.sin((deg * Math.PI) / 180), y: Math.cos((deg * Math.PI) / 180) });

  /** The background entry for a level, if any. */
  const bgFor = level => items('backgrounds').find(b => b && b.level === level && Array.isArray(b.origin_px));

  /** Image pixel (u right, v down) → map metres, using the level's px_per_m and origin_px. */
  const imageToMap = (b, u, v) => ({ x: (u - b.origin_px[0]) / b.px_per_m, y: (b.origin_px[1] - v) / b.px_per_m });

  /** Map metres → image pixel (inverse of imageToMap). */
  const mapToImage = (b, p) => ({ u: b.origin_px[0] + p.x * b.px_per_m, v: b.origin_px[1] - p.y * b.px_per_m });

  // ===========================================================================================
  // Editing core: undo, change tracking, validation
  // ===========================================================================================

  /**
   * Applies a change to the map with undo support.
   * Concept: snapshot first (a JSON copy of the whole map — maps are small), then mutate in place,
   * then refresh everything that depends on the map.
   */
  function edit(fn) {
    pushUndo();
    fn();
    afterChange();
  }

  /** Saves the current map on the undo stack and clears redo (a new change forks history). */
  function pushUndo() {
    S.undo.push(JSON.stringify(S.map));
    if (S.undo.length > 200) S.undo.shift();
    S.redo = [];
  }

  /** Restores the previous snapshot (Ctrl+Z). */
  function undo() {
    if (!S.undo.length) return;
    S.redo.push(JSON.stringify(S.map));
    S.map = JSON.parse(S.undo.pop());
    S.sel = null;
    S.draft = null;
    afterChange();
  }

  /** Re-applies an undone snapshot (Ctrl+Y). */
  function redo() {
    if (!S.redo.length) return;
    S.undo.push(JSON.stringify(S.map));
    S.map = JSON.parse(S.redo.pop());
    S.sel = null;
    afterChange();
  }

  /** True when the map differs from what was last opened or exported. */
  const isChanged = () => C.formatMap(S.map) !== S.baseline;

  /** Refreshes panels, validation and the canvas after any map change. */
  function afterChange() {
    const levels = items('levels');
    if (!levels.some(l => l && l.id === S.level)) S.level = levels.length && levels[0] ? levels[0].id : '';
    S.validation = C.validateMap(S.map);
    renderPanels();
    requestDraw();
  }

  // ===========================================================================================
  // Small helpers over the map
  // ===========================================================================================

  /** A section of the map as a list (empty if a hand-edited file has something else there). */
  const items = type => (Array.isArray(S.map[type]) ? S.map[type] : []);
  const nodeById = id => items('nodes').find(n => n && n.id === id);
  /** Items of a list on the current level, with their index in the full list. */
  const onLevel = (list) => list.map((it, index) => ({ it, index })).filter(({ it }) => it && it.level === S.level);

  /** True if an area's polygon is a list of [x, y] number pairs (safe to draw and hit-test). */
  const drawablePoly = it => Array.isArray(it.polygon) && it.polygon.every(p => Array.isArray(p) && p.length === 2 && p.every(Number.isFinite));

  /** Areas of a section on the current level that have a drawable polygon. */
  const areasOn = section => onLevel(items(section)).filter(({ it }) => drawablePoly(it));

  /** Ids already used in a section (for generating new ones). */
  const idsOf = type => items(type).map(x => x.id);

  /** True if anything other than the background is drawn on [level]. */
  function levelHasContent(level) {
    return ['walkable', 'obstacles', 'nodes', 'anchors', 'checkpoints', 'beacons'].some(t => items(t).some(x => x.level === level));
  }

  /** Shows a short message in the status bar for a few seconds. */
  let flashTimer = null;
  function flash(msg) {
    $('hint').textContent = msg;
    clearTimeout(flashTimer);
    flashTimer = setTimeout(() => { $('hint').textContent = TOOLS[S.tool].hint; }, 5000);
  }

  // ===========================================================================================
  // Hit testing: what is under the mouse?
  // ===========================================================================================

  const HIT_PX = 10;

  /** Distance in screen pixels between a map point and a screen point. */
  const screenDist = (p, sx, sy) => { const s = toScreen(p); return Math.hypot(s.x - sx, s.y - sy); };

  /** The nearest point-like item (node, anchor, checkpoint) on this level within HIT_PX, or null. */
  function hitPoint(sx, sy, types = ['nodes', 'anchors', 'checkpoints', 'beacons']) {
    let best = null;
    for (const type of types) {
      for (const { it, index } of onLevel(items(type))) {
        const d = screenDist(it, sx, sy);
        if (d <= HIT_PX && (!best || d < best.d)) best = { type, index, d };
      }
    }
    return best;
  }

  /** A polygon corner on this level within HIT_PX, preferring the selected polygon. */
  function hitVertex(sx, sy) {
    const selArea = S.sel && (S.sel.type === 'vertex' ? S.sel.area : S.sel.type);
    const areas = selArea === 'obstacles' ? ['obstacles', 'walkable'] : ['walkable', 'obstacles'];
    for (const area of areas) {
      let list = areasOn(area);
      if (selArea === area) {
        // Check the selected polygon's corners first so overlapping corners pick the expected one.
        list = [...list.filter(x => x.index === S.sel.index), ...list.filter(x => x.index !== S.sel.index)];
      }
      for (const { it, index } of list) {
        for (let v = 0; v < it.polygon.length; v++) {
          const [x, y] = it.polygon[v];
          if (screenDist({ x, y }, sx, sy) <= HIT_PX) return { type: 'vertex', area, index, vertex: v };
        }
      }
    }
    return null;
  }

  /** An edge drawn on this level whose line passes within 6 px, or null. */
  function hitEdge(sx, sy) {
    const edges = items('edges');
    for (let i = 0; i < edges.length; i++) {
      const e = edges[i];
      if (!e) continue;
      const a = nodeById(e.from);
      const b = nodeById(e.to);
      if (!a || !b || (a.level !== S.level && b.level !== S.level)) continue;
      const sa = toScreen(a.level === S.level ? a : b);
      const sb = toScreen(a.level === S.level ? b : a);
      if (C.distanceToSegment({ x: sx, y: sy }, sa, sb) <= 6) return { type: 'edges', index: i };
    }
    return null;
  }

  /** The topmost polygon on this level containing the point (obstacles first), or null. */
  function hitArea(sx, sy) {
    const p = toMap(sx, sy);
    for (const area of ['obstacles', 'walkable']) {
      const list = areasOn(area).reverse();
      for (const { it, index } of list) {
        if (C.containsInclusive(C.toPoints(it.polygon), p)) return { type: area, index };
      }
    }
    return null;
  }

  /** Everything the select tool can pick, in priority order. */
  function hitAny(sx, sy) {
    return hitPoint(sx, sy) || hitVertex(sx, sy) || hitEdge(sx, sy) || hitArea(sx, sy);
  }

  // ===========================================================================================
  // Mouse and keyboard
  // ===========================================================================================

  /** Mouse position relative to the canvas, in CSS pixels. */
  function eventPos(e) {
    const r = canvas.getBoundingClientRect();
    return { sx: e.clientX - r.left, sy: e.clientY - r.top };
  }

  canvas.addEventListener('contextmenu', e => e.preventDefault());

  canvas.addEventListener('mousedown', e => {
    // Commit any half-typed field first (its change event fires on blur) so panels can refresh.
    if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur();
    const { sx, sy } = eventPos(e);
    // Pan: right or middle button, or left with Space held.
    if (e.button === 2 || e.button === 1 || (e.button === 0 && S.spaceDown)) {
      S.pan = { sx, sy, ox: S.view.ox, oy: S.view.oy };
      return;
    }
    if (e.button !== 0) return;
    const p = snapPt(toMap(sx, sy));
    const handler = DOWN[S.tool];
    if (handler) handler(p, sx, sy, e);
  });

  window.addEventListener('mousemove', e => {
    const { sx, sy } = eventPos(e);
    S.mouse = { sx, sy, inside: sx >= 0 && sy >= 0 && sx <= canvas.clientWidth && sy <= canvas.clientHeight };
    if (S.pan) {
      S.view.ox = S.pan.ox + (sx - S.pan.sx);
      S.view.oy = S.pan.oy + (sy - S.pan.sy);
      requestDraw();
      return;
    }
    if (S.drag) dragMove(snapPt(toMap(sx, sy)));
    const m = toMap(sx, sy);
    if (S.mouse.inside) $('cursor').textContent = `x ${m.x.toFixed(2)} m, y ${m.y.toFixed(2)} m · ${S.level}`;
    requestDraw();
  });

  window.addEventListener('mouseup', e => {
    if (S.pan) { S.pan = null; return; }
    if (S.drag) { S.drag = null; afterChange(); }
    if (S.draft && S.draft.pressing) {
      const { sx, sy } = eventPos(e);
      finishPointDrag(snapPt(toMap(sx, sy)), sx, sy);
    }
  });

  /**
   * Wheel zooms around the cursor.
   * Concept: keep the map point under the cursor fixed while the scale changes, by moving the
   * origin's screen position by the same proportion.
   */
  canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const { sx, sy } = eventPos(e);
    const before = toMap(sx, sy);
    const factor = Math.pow(1.0015, -e.deltaY);
    S.view.scale = Math.min(2000, Math.max(0.5, S.view.scale * factor));
    S.view.ox = sx - before.x * S.view.scale;
    S.view.oy = sy + before.y * S.view.scale;
    requestDraw();
  }, { passive: false });

  window.addEventListener('keydown', e => {
    const typing = ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName);
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !typing) { e.preventDefault(); e.shiftKey ? redo() : undo(); return; }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y' && !typing) { e.preventDefault(); redo(); return; }
    if (typing) return;
    if (e.key === ' ') { S.spaceDown = true; e.preventDefault(); return; }
    if (e.key === 'Escape') { S.draft = null; requestDraw(); return; }
    if (e.key === 'Enter') { finishPolygon(); return; }
    if (e.key === 'Backspace' && S.draft && S.draft.corners) { S.draft.corners.pop(); requestDraw(); e.preventDefault(); return; }
    if (e.key === 'Delete' || e.key === 'Backspace') { deleteSelection(); e.preventDefault(); return; }
    if (e.key === 'f' || e.key === 'F') { fitView(); return; }
    const tool = Object.keys(TOOLS).find(t => TOOLS[t].key === e.key.toLowerCase());
    if (tool && !e.ctrlKey && !e.metaKey && !e.altKey) setTool(tool);
  });
  window.addEventListener('keyup', e => { if (e.key === ' ') S.spaceDown = false; });

  /** Switches tool, clearing unfinished work that belongs to the old tool. */
  function setTool(tool) {
    S.tool = tool;
    S.draft = null;
    document.querySelectorAll('#tools button').forEach(b => b.classList.toggle('active', b.dataset.tool === tool));
    $('hint').textContent = TOOLS[tool].hint;
    canvas.style.cursor = tool === 'select' ? 'default' : 'crosshair';
    requestDraw();
  }

  // ---- Per-tool mouse-down handlers ----------------------------------------------------------

  const DOWN = {
    /** Select: pick the item under the mouse and start dragging it if it is a point or corner. */
    select(p, sx, sy) {
      const hit = hitAny(sx, sy);
      S.sel = hit ? (({ d, ...rest }) => rest)(hit) : null;
      if (hit && (['nodes', 'anchors', 'checkpoints', 'beacons'].includes(hit.type) || hit.type === 'vertex')) {
        S.drag = { hit, start: p, snapshotTaken: false };
      }
      renderProps();
      requestDraw();
    },

    /** Walkable / obstacle: add a corner, or close the shape when the first corner is clicked. */
    walkable(p, sx, sy) { addCorner(p, sx, sy); },
    obstacle(p, sx, sy) { addCorner(p, sx, sy); },

    /** Node: place a new route node with the next free id. */
    node(p) {
      edit(() => {
        const id = C.nextId('n', idsOf('nodes'));
        S.map.nodes.push({ id, level: S.level, x: p.x, y: p.y });
        S.sel = { type: 'nodes', index: S.map.nodes.length - 1 };
      });
    },

    /** Edge: first click picks the start node, second click (any level) creates the edge. */
    edge(p, sx, sy) {
      const hit = hitPoint(sx, sy, ['nodes']);
      if (!hit) { flash('Click on a node.'); return; }
      const node = S.map.nodes[hit.index];
      if (!S.draft || !S.draft.from) {
        S.draft = { from: node.id };
        flash(`From ${node.id}: now click the second node${S.map.levels.length > 1 ? ' (switch level first for a lift or escalator)' : ''}.`);
        requestDraw();
        return;
      }
      const from = S.draft.from;
      if (from === node.id) { flash('Pick a different node.'); return; }
      const kind = $('edge-kind').value;
      if (S.map.edges.some(e => e.from === from && e.to === node.id && e.kind === kind)) { flash('That edge already exists.'); S.draft = null; return; }
      edit(() => {
        S.map.edges.push({ from, to: node.id, kind });
        S.sel = { type: 'edges', index: S.map.edges.length - 1 };
      });
      S.draft = null;
      flash(`Added ${kind} edge ${from} → ${node.id}.`);
    },

    /** Anchor: press marks the plate position; the drag sets its facing (finished on mouse-up). */
    anchor(p, sx, sy) { S.draft = { pressing: 'anchor', at: p, sx, sy }; },

    /** Checkpoint: press marks the floor point; the drag sets its heading (finished on mouse-up). */
    checkpoint(p, sx, sy) { S.draft = { pressing: 'checkpoint', at: p, sx, sy }; },

    /**
     * Beacon: place a Bluetooth beacon with the default height and UUID, major = the level's
     * position in the level list (1, 2, ...), and the next free minor for that UUID and major.
     */
    beacon(p) {
      const uuid = ($('def-beacon-uuid').value || '').trim().toUpperCase();
      const major = Math.max(1, S.map.levels.findIndex(l => l.id === S.level) + 1);
      const used = new Set(items('beacons').filter(b => (b.uuid || '').toUpperCase() === uuid && b.major === major).map(b => b.minor));
      let minor = 1;
      while (used.has(minor)) minor++;
      edit(() => {
        if (!Array.isArray(S.map.beacons)) S.map.beacons = [];
        S.map.beacons.push({ id: C.nextId('B', idsOf('beacons'), 2), level: S.level, x: p.x, y: p.y,
          height_m: Number($('def-beacon-height').value) || 2.8, uuid, major, minor });
        S.sel = { type: 'beacons', index: S.map.beacons.length - 1 };
      });
    },

    /** Scale: two clicks on the image, then the measured distance sets px_per_m. */
    scale(p, sx, sy) {
      const b = bgFor(S.level);
      if (!b) { flash('Load a floor image for this level first.'); return; }
      const raw = toMap(sx, sy); // unsnapped: scale picks should be exact
      const px = mapToImage(b, raw);
      if (!S.draft || !S.draft.first) { S.draft = { first: px, firstMap: raw }; requestDraw(); return; }
      const pixels = Math.hypot(px.u - S.draft.first.u, px.v - S.draft.first.v);
      S.draft = null;
      if (pixels < 2) { flash('The two points are too close together.'); return; }
      const answer = window.prompt('Measured distance between the two points, in metres:');
      const metres = Number(answer);
      if (!answer || !(metres > 0)) { flash('Scale unchanged.'); requestDraw(); return; }
      if (levelHasContent(S.level) && !window.confirm('Changing the scale moves the image under the items already drawn on this level. Continue?')) return;
      edit(() => { b.px_per_m = C.roundNum(pixels / metres); });
      fitView();
      flash(`Scale set: ${b.px_per_m} image pixels per metre.`);
    },

    /** Origin: the clicked image point becomes map (0, 0); the view shifts so nothing jumps. */
    origin(p, sx, sy) {
      const b = bgFor(S.level);
      if (!b) { flash('Load a floor image for this level first.'); return; }
      if (levelHasContent(S.level) && !window.confirm('Moving the origin moves the image under the items already drawn on this level. Continue?')) return;
      const px = mapToImage(b, toMap(sx, sy));
      edit(() => { b.origin_px = [C.roundNum(px.u), C.roundNum(px.v)]; });
      S.view.ox = sx;
      S.view.oy = sy;
      requestDraw();
      flash('Origin set. Coordinates are now measured from this point.');
    },
  };

  /** Adds a polygon corner, or finishes the polygon if the first corner was clicked again. */
  function addCorner(p, sx, sy) {
    if (!S.draft || !S.draft.corners) S.draft = { corners: [] };
    const corners = S.draft.corners;
    if (corners.length >= 3 && screenDist(corners[0], sx, sy) <= HIT_PX) { finishPolygon(); return; }
    corners.push(p);
    requestDraw();
  }

  /** Turns the corners being drawn into a walkable area or obstacle, if they form a valid polygon. */
  function finishPolygon() {
    if (!S.draft || !S.draft.corners || !['walkable', 'obstacle'].includes(S.tool)) return;
    const pts = S.draft.corners;
    const problem = C.polygonProblem(pts);
    if (problem) { flash(`Can't finish: the shape ${problem}. Backspace removes the last corner.`); return; }
    const section = S.tool === 'walkable' ? 'walkable' : 'obstacles';
    edit(() => {
      S.map[section].push({ level: S.level, polygon: pts.map(q => [q.x, q.y]) });
      S.sel = { type: section, index: S.map[section].length - 1 };
    });
    S.draft = null;
  }

  /** Finishes an anchor or checkpoint placement: direction from press point to release point. */
  function finishPointDrag(p, sx, sy) {
    const d = S.draft;
    S.draft = null;
    const dragged = Math.hypot(sx - d.sx, sy - d.sy) >= 6;
    const angle = dragged ? headingDeg(d.at, toMap(sx, sy)) : 0;
    if (d.pressing === 'anchor') {
      edit(() => {
        S.map.anchors.push({
          id: C.nextId('A', idsOf('anchors'), 2), level: S.level, x: d.at.x, y: d.at.y,
          mount_height_m: Number($('def-mount').value) || 1.5, facing_deg: angle, size_m: Number($('def-size').value) || 0.18,
        });
        S.sel = { type: 'anchors', index: S.map.anchors.length - 1 };
      });
    } else {
      edit(() => {
        S.map.checkpoints.push({ id: C.nextId('C', idsOf('checkpoints'), 2), level: S.level, x: d.at.x, y: d.at.y, heading_deg: angle });
        S.sel = { type: 'checkpoints', index: S.map.checkpoints.length - 1 };
      });
    }
    if (!dragged) flash('Placed with direction 0°. Drag while placing, or type the angle in Selection.');
  }

  /** Moves the dragged point or polygon corner, taking one undo snapshot per drag. */
  function dragMove(p) {
    const { hit } = S.drag;
    if (!S.drag.snapshotTaken) {
      if (p.x === S.drag.start.x && p.y === S.drag.start.y) return;
      pushUndo();
      S.drag.snapshotTaken = true;
    }
    if (hit.type === 'vertex') {
      S.map[hit.area][hit.index].polygon[hit.vertex] = [p.x, p.y];
    } else {
      const it = S.map[hit.type][hit.index];
      it.x = p.x;
      it.y = p.y;
    }
    renderProps();
  }

  /** Deletes the selected item, cleaning up anything that referred to it. */
  function deleteSelection() {
    const sel = S.sel;
    if (!sel) return;
    if (sel.type === 'vertex') {
      const poly = S.map[sel.area][sel.index].polygon;
      if (poly.length <= 3) { flash('A shape needs at least 3 corners. Select the shape itself to delete it.'); return; }
      edit(() => poly.splice(sel.vertex, 1));
      S.sel = { type: sel.area, index: sel.index };
      renderProps();
      return;
    }
    if (sel.type === 'nodes') {
      const id = S.map.nodes[sel.index].id;
      const edges = S.map.edges.filter(e => e.from === id || e.to === id).length;
      const dests = S.map.destinations.filter(d => d.node === id).length;
      if ((edges || dests) && !window.confirm(`Node ${id} has ${edges} edge(s) and ${dests} destination(s). Delete them too?`)) return;
      edit(() => {
        S.map.edges = S.map.edges.filter(e => e.from !== id && e.to !== id);
        S.map.destinations = S.map.destinations.filter(d => d.node !== id);
        S.map.nodes.splice(sel.index, 1);
      });
    } else {
      edit(() => S.map[sel.type].splice(sel.index, 1));
    }
    S.sel = null;
    renderProps();
  }

  // ===========================================================================================
  // Drawing
  // ===========================================================================================

  let drawPending = false;

  /** Schedules one redraw on the next animation frame (many calls collapse into one). */
  function requestDraw() {
    if (drawPending) return;
    drawPending = true;
    requestAnimationFrame(() => { drawPending = false; draw(); });
  }

  /** Matches the canvas's pixel buffer to its on-screen size (sharp on high-DPI screens). */
  function resizeCanvas() {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  window.addEventListener('resize', requestDraw);

  const COLORS = {
    grid: '#e3e7ea', gridMajor: '#cfd5da', walkFill: 'rgba(11,110,116,0.16)', walkLine: '#0b6e74',
    obstFill: 'rgba(179,38,30,0.28)', obstLine: '#b3261e', node: '#1d2327', anchor: '#6a1b9a', checkpoint: '#c45c00',
    sel: '#ff9800', draft: '#1565c0',
    edge: { walk: '#7a8790', stairs: '#c45c00', lift: '#1565c0', escalator_up: '#6a1b9a', escalator_down: '#6a1b9a' },
  };

  /**
   * Redraws everything for the current level.
   * A map opened from a hand-edited file can be structurally broken (e.g. a polygon that isn't a
   * list); drawing then stops with a message instead of freezing the editor, and Checks lists why.
   */
  function draw() {
    resizeCanvas();
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    ctx.fillStyle = '#fbfcfc';
    ctx.fillRect(0, 0, w, h);
    try {
      drawGrid(w, h);
      drawBackground();
      drawAreas('walkable', COLORS.walkFill, COLORS.walkLine);
      drawAreas('obstacles', COLORS.obstFill, COLORS.obstLine);
      drawEdges();
      drawNodes();
      drawAnchors();
      drawCheckpoints();
      drawBeacons();
      drawOrigin();
      drawSelection();
      drawDraft();
    } catch (err) {
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.font = '14px system-ui, sans-serif';
      ctx.fillStyle = '#b3261e';
      ctx.fillText(`Can't draw this map (${err.message}). See Checks for what to fix in the file.`, 16, 28);
    }
  }

  /**
   * 1 m grid (5 m lines darker), skipped when lines would be closer than 6 px.
   * Concept: compute which whole metres are visible from the screen corners, draw only those.
   */
  function drawGrid(w, h) {
    const step = S.view.scale >= 6 ? 1 : S.view.scale >= 1.2 ? 5 : 25;
    const tl = toMap(0, 0);
    const br = toMap(w, h);
    ctx.lineWidth = 1;
    for (let x = Math.floor(tl.x / step) * step; x <= br.x; x += step) {
      const s = toScreen({ x, y: 0 }).x;
      ctx.strokeStyle = x % (step * 5) === 0 ? COLORS.gridMajor : COLORS.grid;
      ctx.beginPath(); ctx.moveTo(s + 0.5, 0); ctx.lineTo(s + 0.5, h); ctx.stroke();
    }
    for (let y = Math.floor(br.y / step) * step; y <= tl.y; y += step) {
      const s = toScreen({ x: 0, y }).y;
      ctx.strokeStyle = y % (step * 5) === 0 ? COLORS.gridMajor : COLORS.grid;
      ctx.beginPath(); ctx.moveTo(0, s + 0.5); ctx.lineTo(w, s + 0.5); ctx.stroke();
    }
  }

  /** Draws the level's floor image scaled by px_per_m with origin_px at map (0, 0). */
  function drawBackground() {
    const b = bgFor(S.level);
    const loaded = S.images[S.level];
    if (!b || !loaded) return;
    const img = loaded.img;
    const topLeft = toScreen(imageToMap(b, 0, 0));
    const size = (img.naturalWidth / b.px_per_m) * S.view.scale;
    const sizeY = (img.naturalHeight / b.px_per_m) * S.view.scale;
    ctx.globalAlpha = S.bgOpacity;
    ctx.drawImage(img, topLeft.x, topLeft.y, size, sizeY);
    ctx.globalAlpha = 1;
  }

  /** Traces a map.json polygon as a closed canvas path. */
  function tracePolygon(poly) {
    ctx.beginPath();
    poly.forEach(([x, y], i) => { const s = toScreen({ x, y }); i ? ctx.lineTo(s.x, s.y) : ctx.moveTo(s.x, s.y); });
    ctx.closePath();
  }

  /** Fills and outlines every polygon of a section on the current level. */
  function drawAreas(section, fill, line) {
    for (const { it } of areasOn(section)) {
      tracePolygon(it.polygon);
      ctx.fillStyle = fill; ctx.fill();
      ctx.strokeStyle = line; ctx.lineWidth = 1.5; ctx.stroke();
    }
  }

  /** Draws an arrow from screen point a to b with a filled head. */
  function arrow(a, b, color, width = 1.5, head = 7) {
    const ang = Math.atan2(b.y - a.y, b.x - a.x);
    ctx.strokeStyle = color; ctx.fillStyle = color; ctx.lineWidth = width;
    ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(b.x, b.y);
    ctx.lineTo(b.x - head * Math.cos(ang - 0.45), b.y - head * Math.sin(ang - 0.45));
    ctx.lineTo(b.x - head * Math.cos(ang + 0.45), b.y - head * Math.sin(ang + 0.45));
    ctx.closePath(); ctx.fill();
  }

  /** Small text label with a white halo so it stays readable over the image. */
  function label(text, s, color = '#1d2327', dx = 7, dy = -7) {
    ctx.font = '12px system-ui, sans-serif';
    ctx.lineWidth = 3; ctx.strokeStyle = 'rgba(255,255,255,0.9)'; ctx.strokeText(text, s.x + dx, s.y + dy);
    ctx.fillStyle = color; ctx.fillText(text, s.x + dx, s.y + dy);
  }

  /**
   * Edges: solid lines between nodes on this level, coloured by kind, with a direction arrow on
   * escalators. An edge to another level is drawn dashed towards the other node's plan position,
   * labelled with the level it leads to.
   */
  function drawEdges() {
    for (const e of items('edges')) {
      const a = e && nodeById(e.from);
      const b = nodeById(e.to);
      if (!a || !b) continue;
      const color = COLORS.edge[e.kind] || '#999';
      if (a.level === S.level && b.level === S.level) {
        const sa = toScreen(a);
        const sb = toScreen(b);
        if (e.kind.startsWith('escalator')) {
          arrow(sa, { x: (sa.x + sb.x) / 2, y: (sa.y + sb.y) / 2 }, color, 2);
          ctx.beginPath(); ctx.moveTo(sa.x, sa.y); ctx.lineTo(sb.x, sb.y); ctx.stroke();
        } else {
          ctx.strokeStyle = color; ctx.lineWidth = e.kind === 'walk' ? 1.5 : 2.5;
          ctx.beginPath(); ctx.moveTo(sa.x, sa.y); ctx.lineTo(sb.x, sb.y); ctx.stroke();
        }
      } else if (a.level === S.level || b.level === S.level) {
        const here = a.level === S.level ? a : b;
        const there = a.level === S.level ? b : a;
        const sh = toScreen(here);
        let st = toScreen(there);
        if (Math.hypot(st.x - sh.x, st.y - sh.y) < 18) st = { x: sh.x + 18, y: sh.y - 18 };
        ctx.setLineDash([5, 4]);
        ctx.strokeStyle = color; ctx.lineWidth = 2;
        ctx.beginPath(); ctx.moveTo(sh.x, sh.y); ctx.lineTo(st.x, st.y); ctx.stroke();
        ctx.setLineDash([]);
        const dir = here === a ? '→' : '←';
        label(`${dir} ${there.level} (${e.kind})`, st, color, 4, -4);
      }
    }
  }

  /** Nodes as dots with their ids; destination names in bold next to their node. */
  function drawNodes() {
    for (const { it } of onLevel(items('nodes'))) {
      const s = toScreen(it);
      ctx.fillStyle = COLORS.node;
      ctx.beginPath(); ctx.arc(s.x, s.y, 4.5, 0, Math.PI * 2); ctx.fill();
      label(it.id, s, COLORS.node);
      const dests = items('destinations').filter(d => d && d.node === it.id).map(d => d.name);
      if (dests.length) {
        ctx.font = 'bold 12px system-ui, sans-serif';
        label(`★ ${dests.join(', ')}`, s, '#0b6e74', 7, 16);
      }
    }
  }

  /** QR plates: a short bar along the wall plus an arrow showing which way the plate faces. */
  function drawAnchors() {
    for (const { it } of onLevel(items('anchors'))) {
      const s = toScreen(it);
      const f = headingVec(it.facing_deg);
      const half = Math.max(5, (it.size_m / 2) * S.view.scale);
      const along = { x: f.y, y: f.x }; // perpendicular to facing, in screen terms (y flipped)
      ctx.strokeStyle = COLORS.anchor; ctx.lineWidth = 4;
      ctx.beginPath(); ctx.moveTo(s.x - along.x * half, s.y - along.y * half); ctx.lineTo(s.x + along.x * half, s.y + along.y * half); ctx.stroke();
      arrow(s, { x: s.x + f.x * 24, y: s.y - f.y * 24 }, COLORS.anchor, 1.5);
      label(it.id, s, COLORS.anchor, 8, 14);
    }
  }

  /** Checkpoints: an X on the floor mark plus an arrow along its heading. */
  function drawCheckpoints() {
    for (const { it } of onLevel(items('checkpoints'))) {
      const s = toScreen(it);
      const f = headingVec(it.heading_deg);
      ctx.strokeStyle = COLORS.checkpoint; ctx.lineWidth = 2;
      ctx.beginPath(); ctx.moveTo(s.x - 5, s.y - 5); ctx.lineTo(s.x + 5, s.y + 5); ctx.moveTo(s.x + 5, s.y - 5); ctx.lineTo(s.x - 5, s.y + 5); ctx.stroke();
      arrow(s, { x: s.x + f.x * 20, y: s.y - f.y * 20 }, COLORS.checkpoint, 1.5);
      label(it.id, s, COLORS.checkpoint, 8, 14);
    }
  }

  /** Bluetooth beacons: a blue dot with two signal arcs, labelled with id and major/minor. */
  function drawBeacons() {
    for (const { it } of onLevel(items('beacons'))) {
      const s = toScreen(it);
      ctx.fillStyle = '#1565c0';
      ctx.beginPath(); ctx.arc(s.x, s.y, 4, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = '#1565c0'; ctx.lineWidth = 1.5;
      for (const r of [8, 13]) { ctx.beginPath(); ctx.arc(s.x, s.y, r, -Math.PI / 4, Math.PI / 4); ctx.stroke(); }
      label(`${it.id} (${it.major}/${it.minor})`, s, '#1565c0', 15, 4);
    }
  }

  /** Map origin with short x (right) and y (up) axes. */
  function drawOrigin() {
    const o = toScreen({ x: 0, y: 0 });
    arrow(o, { x: o.x + 28, y: o.y }, '#b3261e', 1.5, 6);
    arrow(o, { x: o.x, y: o.y - 28 }, '#2e7d32', 1.5, 6);
    label('0,0', o, '#5f6b73', -26, 14);
  }

  /** Highlights the selected item in orange. */
  function drawSelection() {
    const sel = S.sel;
    if (!sel) return;
    ctx.strokeStyle = COLORS.sel; ctx.lineWidth = 3;
    if (sel.type === 'walkable' || sel.type === 'obstacles') {
      const it = S.map[sel.type][sel.index];
      if (!it || it.level !== S.level || !drawablePoly(it)) return;
      tracePolygon(it.polygon); ctx.stroke();
      it.polygon.forEach(([x, y]) => { const s = toScreen({ x, y }); ctx.strokeRect(s.x - 4, s.y - 4, 8, 8); });
    } else if (sel.type === 'vertex') {
      const it = S.map[sel.area][sel.index];
      if (!it || it.level !== S.level) return;
      tracePolygon(it.polygon); ctx.lineWidth = 1.5; ctx.stroke();
      const [x, y] = it.polygon[sel.vertex];
      const s = toScreen({ x, y }); ctx.lineWidth = 3; ctx.strokeRect(s.x - 6, s.y - 6, 12, 12);
    } else if (sel.type === 'edges') {
      const e = S.map.edges[sel.index];
      const a = e && nodeById(e.from);
      const b = e && nodeById(e.to);
      if (!a || !b) return;
      const sa = toScreen(a.level === S.level ? a : b);
      const sb = toScreen(a.level === S.level ? b : a);
      ctx.beginPath(); ctx.moveTo(sa.x, sa.y); ctx.lineTo(sb.x, sb.y); ctx.stroke();
    } else {
      const it = S.map[sel.type][sel.index];
      if (!it || it.level !== S.level) return;
      const s = toScreen(it);
      ctx.beginPath(); ctx.arc(s.x, s.y, 10, 0, Math.PI * 2); ctx.stroke();
    }
  }

  /** Work in progress: polygon corners, the first edge node, a placement drag, scale picks. */
  function drawDraft() {
    const d = S.draft;
    if (!d) return;
    const cur = { x: S.mouse.sx, y: S.mouse.sy };
    ctx.strokeStyle = COLORS.draft; ctx.fillStyle = COLORS.draft; ctx.lineWidth = 2;
    if (d.corners && d.corners.length) {
      ctx.beginPath();
      d.corners.forEach((p, i) => { const s = toScreen(p); i ? ctx.lineTo(s.x, s.y) : ctx.moveTo(s.x, s.y); });
      ctx.lineTo(cur.x, cur.y); ctx.stroke();
      d.corners.forEach(p => { const s = toScreen(p); ctx.fillRect(s.x - 3, s.y - 3, 6, 6); });
      const first = toScreen(d.corners[0]);
      if (d.corners.length >= 3) { ctx.beginPath(); ctx.arc(first.x, first.y, HIT_PX, 0, Math.PI * 2); ctx.stroke(); }
    } else if (d.from) {
      const n = nodeById(d.from);
      if (n && n.level === S.level) { const s = toScreen(n); ctx.setLineDash([4, 4]); ctx.beginPath(); ctx.moveTo(s.x, s.y); ctx.lineTo(cur.x, cur.y); ctx.stroke(); ctx.setLineDash([]); }
      else label(`edge from ${d.from} (${n ? n.level : '?'})`, cur, COLORS.draft, 12, -12);
    } else if (d.pressing) {
      const s = toScreen(d.at);
      if (Math.hypot(cur.x - d.sx, cur.y - d.sy) >= 6) {
        arrow(s, cur, COLORS.draft, 2);
        label(`${headingDeg(d.at, toMap(cur.x, cur.y)).toFixed(1)}°`, cur, COLORS.draft);
      }
    } else if (d.first) {
      const s = toScreen(d.firstMap);
      ctx.beginPath(); ctx.moveTo(s.x, s.y); ctx.lineTo(cur.x, cur.y); ctx.stroke();
      const b = bgFor(S.level);
      const px = mapToImage(b, toMap(cur.x, cur.y));
      label(`${Math.hypot(px.u - d.first.u, px.v - d.first.v).toFixed(1)} image px`, cur, COLORS.draft);
    }
  }

  /**
   * Zooms and pans so the current level's content (and image, if loaded) fills the canvas.
   * Concept: collect a bounding box in map metres, then pick the scale that fits it with a margin.
   */
  function fitView() {
    const pts = [];
    for (const t of ['walkable', 'obstacles']) for (const { it } of areasOn(t)) it.polygon.forEach(([x, y]) => pts.push({ x, y }));
    for (const t of ['nodes', 'anchors', 'checkpoints', 'beacons']) for (const { it } of onLevel(items(t))) pts.push(it);
    const b = bgFor(S.level);
    const loaded = S.images[S.level];
    if (b && loaded) {
      pts.push(imageToMap(b, 0, 0));
      pts.push(imageToMap(b, loaded.img.naturalWidth, loaded.img.naturalHeight));
    }
    if (!pts.length) pts.push({ x: 0, y: 0 }, { x: 20, y: 20 });
    const minX = Math.min(...pts.map(p => p.x)); const maxX = Math.max(...pts.map(p => p.x));
    const minY = Math.min(...pts.map(p => p.y)); const maxY = Math.max(...pts.map(p => p.y));
    const w = canvas.clientWidth; const h = canvas.clientHeight;
    const spanX = Math.max(maxX - minX, 1); const spanY = Math.max(maxY - minY, 1);
    // Generous side margins leave room for labels (ids, cross-level edge notes) beside the content.
    S.view.scale = Math.min((w - 220) / spanX, (h - 90) / spanY);
    S.view.ox = (w - spanX * S.view.scale) / 2 - minX * S.view.scale;
    S.view.oy = (h + spanY * S.view.scale) / 2 + minY * S.view.scale;
    requestDraw();
  }

  // ===========================================================================================
  // Side panels
  // ===========================================================================================

  /** Re-renders every panel that shows map data. */
  function renderPanels() {
    renderHeader();
    renderLevels();
    renderBackgroundInfo();
    renderProps();
    renderDestinations();
    renderValidation();
  }

  /** Venue, version and the unsaved-changes marker. */
  function renderHeader() {
    if (document.activeElement !== $('venue')) $('venue').value = S.map.venue;
    $('version').textContent = S.map.version;
    $('changed').textContent = isChanged() ? '● unsaved changes' : '';
    const { errors, warnings } = S.validation;
    const badge = $('checks-badge');
    badge.textContent = errors.length ? `${errors.length} error(s)` : warnings.length ? `${warnings.length} warning(s)` : 'Checks OK';
    badge.style.color = errors.length ? 'var(--err)' : warnings.length ? 'var(--warn)' : 'var(--ok)';
  }

  /** Level buttons plus the current level's id, name and elevation fields. */
  function renderLevels() {
    const list = $('level-list');
    list.innerHTML = '';
    for (const l of items('levels')) {
      const b = document.createElement('button');
      b.textContent = `${l.id} · ${l.name}`;
      b.className = l.id === S.level ? 'active' : '';
      b.onclick = () => { S.level = l.id; if (S.draft && S.draft.corners) S.draft = null; renderPanels(); fitView(); };
      list.appendChild(b);
    }
    const cur = items('levels').find(l => l && l.id === S.level);
    for (const [id, key] of [['lvl-id', 'id'], ['lvl-name', 'name'], ['lvl-elev', 'elevation_m']]) {
      if (document.activeElement !== $(id)) $(id).value = cur ? cur[key] : '';
    }
  }

  /** Shows which image is loaded for this level and its scale, or asks for it. */
  function renderBackgroundInfo() {
    const b = bgFor(S.level);
    const loaded = S.images[S.level];
    const info = $('bg-info');
    if (!b) { info.textContent = 'No image for this level. Load one, then use Scale (S) and Origin (G).'; return; }
    const state = loaded ? 'loaded' : 'NOT loaded — click "Load image…" and pick it';
    const r2 = v => Number(v).toFixed(2);
    info.textContent = `${b.image} (${state}). ${r2(b.px_per_m)} px per metre; origin at image pixel (${b.origin_px.map(r2).join(', ')}).`;
  }

  /** Builds a labelled input row and wires its change handler. */
  function propRow(container, labelText, value, onChange, opts = {}) {
    const row = document.createElement('div');
    row.className = 'row';
    const lab = document.createElement('label');
    lab.textContent = labelText;
    let input;
    if (opts.options) {
      input = document.createElement('select');
      for (const o of opts.options) { const op = document.createElement('option'); op.value = o; op.textContent = o; input.appendChild(op); }
      input.value = value;
    } else {
      input = document.createElement('input');
      input.type = opts.number ? 'number' : 'text';
      if (opts.number) input.step = opts.step || '0.1';
      input.value = value;
      if (opts.readOnly) input.readOnly = true;
    }
    input.dataset.prop = labelText;
    input.addEventListener('change', () => onChange(opts.number ? Number(input.value) : input.value));
    row.append(lab, input);
    container.appendChild(row);
    return input;
  }

  /**
   * Renaming an id updates everything that refers to it (edges and destinations for nodes).
   * Returns false (and explains) if the new id is blank or already used.
   */
  function renameId(type, index, newId) {
    newId = String(newId).trim();
    const it = S.map[type][index];
    if (!newId || newId === it.id) return false;
    if (S.map[type].some(x => x.id === newId)) { flash(`Id ${newId} is already used.`); renderProps(); return false; }
    edit(() => {
      const old = it.id;
      it.id = newId;
      if (type === 'nodes') {
        for (const e of S.map.edges) { if (e.from === old) e.from = newId; if (e.to === old) e.to = newId; }
        for (const d of S.map.destinations) if (d.node === old) d.node = newId;
      }
    });
    return true;
  }

  /** The Selection panel: editable fields for whatever is selected (typed coordinates live here). */
  function renderProps() {
    const box = $('props');
    // Don't rebuild a field under the user's cursor while they type in it. Clicking the canvas
    // blurs the field first, so a new selection always refreshes the panel.
    if (box.contains(document.activeElement) && ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName)) return;
    box.innerHTML = '';
    const sel = S.sel;
    if (!sel) { box.innerHTML = '<div class="muted">Nothing selected. Use Sel (V) and click an item to edit its exact values.</div>'; return; }
    const num = (v) => (Number.isFinite(v) ? v : 0);
    const setNum = (obj, key) => v => { if (Number.isFinite(v)) edit(() => { obj[key] = C.roundNum(v); }); };

    if (sel.type === 'nodes' || sel.type === 'anchors' || sel.type === 'checkpoints' || sel.type === 'beacons') {
      const it = S.map[sel.type][sel.index];
      if (!it) { S.sel = null; return renderProps(); }
      const title = { nodes: 'Route node', anchors: 'QR plate', checkpoints: 'Checkpoint', beacons: 'Bluetooth beacon' }[sel.type];
      box.insertAdjacentHTML('beforeend', `<div class="muted" style="margin-bottom:6px">${title} on ${it.level}</div>`);
      propRow(box, 'Id', it.id, v => renameId(sel.type, sel.index, v));
      propRow(box, 'x (m)', num(it.x), setNum(it, 'x'), { number: true });
      propRow(box, 'y (m)', num(it.y), setNum(it, 'y'), { number: true });
      if (sel.type === 'anchors') {
        propRow(box, 'Facing (°)', num(it.facing_deg), setNum(it, 'facing_deg'), { number: true, step: '1' });
        propRow(box, 'Height (m)', num(it.mount_height_m), setNum(it, 'mount_height_m'), { number: true, step: '0.01' });
        propRow(box, 'Frame (m)', num(it.size_m), setNum(it, 'size_m'), { number: true, step: '0.001' });
      }
      if (sel.type === 'checkpoints') propRow(box, 'Heading (°)', num(it.heading_deg), setNum(it, 'heading_deg'), { number: true, step: '1' });
      if (sel.type === 'beacons') {
        const setInt = key => v => { if (Number.isInteger(v)) edit(() => { it[key] = v; }); else flash(`${key} must be a whole number.`); };
        propRow(box, 'Height (m)', num(it.height_m), setNum(it, 'height_m'), { number: true, step: '0.1' });
        propRow(box, 'UUID', it.uuid || '', v => edit(() => { it.uuid = String(v).trim().toUpperCase(); }));
        propRow(box, 'Major', num(it.major), setInt('major'), { number: true, step: '1' });
        propRow(box, 'Minor', num(it.minor), setInt('minor'), { number: true, step: '1' });
      }
      if (sel.type === 'nodes') {
        const btns = document.createElement('div'); btns.className = 'btns';
        const add = document.createElement('button'); add.textContent = 'Make destination';
        add.onclick = () => edit(() => S.map.destinations.push({ id: C.nextId('D', idsOf('destinations')), name: `Place ${it.id}`, node: it.id }));
        btns.appendChild(add); box.appendChild(btns);
      }
    } else if (sel.type === 'edges') {
      const e = S.map.edges[sel.index];
      if (!e) { S.sel = null; return renderProps(); }
      propRow(box, 'From', e.from, () => {}, { readOnly: true });
      propRow(box, 'To', e.to, () => {}, { readOnly: true });
      propRow(box, 'Kind', e.kind, v => edit(() => { e.kind = v; }), { options: Object.keys(C.EDGE_KINDS) });
      const btns = document.createElement('div'); btns.className = 'btns';
      const rev = document.createElement('button'); rev.textContent = 'Reverse direction';
      rev.onclick = () => edit(() => { [e.from, e.to] = [e.to, e.from]; });
      btns.appendChild(rev); box.appendChild(btns);
    } else {
      const area = sel.type === 'vertex' ? sel.area : sel.type;
      const it = S.map[area][sel.index];
      if (!it) { S.sel = null; return renderProps(); }
      box.insertAdjacentHTML('beforeend', `<div class="muted" style="margin-bottom:6px">${area === 'walkable' ? 'Walkable area' : 'Obstacle'} on ${it.level} — one corner per line, "x, y" in metres</div>`);
      const ta = document.createElement('textarea');
      ta.value = it.polygon.map(([x, y]) => `${x}, ${y}`).join('\n');
      box.appendChild(ta);
      const btns = document.createElement('div'); btns.className = 'btns';
      const apply = document.createElement('button'); apply.textContent = 'Apply corners';
      apply.onclick = () => {
        const pts = ta.value.split('\n').map(l => l.trim()).filter(Boolean).map(l => l.split(/[,\s]+/).map(Number));
        if (pts.some(p => p.length !== 2 || !p.every(Number.isFinite))) { flash('Each line must be "x, y".'); return; }
        const problem = C.polygonProblem(pts.map(([x, y]) => ({ x, y })));
        if (problem) { flash(`Not applied: the shape ${problem}.`); return; }
        edit(() => { it.polygon = pts.map(([x, y]) => [C.roundNum(x), C.roundNum(y)]); });
      };
      btns.appendChild(apply); box.appendChild(btns);
    }
    const del = document.createElement('button');
    del.textContent = 'Delete';
    del.onclick = deleteSelection;
    const wrap = document.createElement('div'); wrap.className = 'btns'; wrap.appendChild(del);
    box.appendChild(wrap);
  }

  /** Destinations table: id, name, node (any level), delete. */
  function renderDestinations() {
    const rows = $('dest-rows');
    if (rows.contains(document.activeElement)) return;
    rows.innerHTML = '';
    items('destinations').forEach((d, i) => {
      if (!d) return;
      const tr = document.createElement('tr');
      const id = document.createElement('input'); id.type = 'text'; id.value = d.id; id.style.width = '52px';
      id.onchange = () => {
        const v = id.value.trim();
        if (!v || S.map.destinations.some((x, j) => j !== i && x.id === v)) { flash('Destination ids must be unique and not blank.'); renderDestinations(); return; }
        edit(() => { d.id = v; });
      };
      const name = document.createElement('input'); name.type = 'text'; name.value = d.name;
      name.onchange = () => edit(() => { d.name = name.value; });
      const node = document.createElement('select');
      for (const n of items('nodes')) { const o = document.createElement('option'); o.value = n.id; o.textContent = `${n.id} (${n.level})`; node.appendChild(o); }
      if (!items('nodes').some(n => n && n.id === d.node)) { const o = document.createElement('option'); o.value = d.node; o.textContent = `${d.node} (missing)`; node.appendChild(o); }
      node.value = d.node;
      node.onchange = () => edit(() => { d.node = node.value; });
      const del = document.createElement('button'); del.textContent = '✕'; del.title = 'Delete destination';
      del.onclick = () => edit(() => S.map.destinations.splice(i, 1));
      for (const el of [id, name, node, del]) { const td = document.createElement('td'); td.appendChild(el); tr.appendChild(td); }
      rows.appendChild(tr);
    });
  }

  /** Errors and warnings from map-core's validator, the same rules the phone app applies. */
  function renderValidation() {
    const { errors, warnings } = S.validation;
    const list = $('val-list');
    list.innerHTML = '';
    $('val-summary').innerHTML = errors.length
      ? `<span style="color:var(--err);font-weight:600">${errors.length} error(s)</span> — export is blocked until they are fixed. ${warnings.length} warning(s).`
      : warnings.length ? `<span class="ok">No errors.</span> ${warnings.length} warning(s) — allowed, but worth checking.` : '<span class="ok">No errors or warnings.</span>';
    for (const m of errors) { const li = document.createElement('li'); li.className = 'err'; li.textContent = m; list.appendChild(li); }
    for (const m of warnings) { const li = document.createElement('li'); li.className = 'warn'; li.textContent = m; list.appendChild(li); }
  }

  // ===========================================================================================
  // Panel wiring
  // ===========================================================================================

  document.querySelectorAll('#tools button').forEach(b => { b.onclick = () => setTool(b.dataset.tool); });

  $('venue').addEventListener('change', () => edit(() => { S.map.venue = $('venue').value.trim(); }));
  $('snap').addEventListener('change', requestDraw);
  $('bg-opacity').addEventListener('input', () => { S.bgOpacity = Number($('bg-opacity').value); requestDraw(); });

  /** Level id change: renames the level everywhere it is referenced. */
  $('lvl-id').addEventListener('change', () => {
    const v = $('lvl-id').value.trim();
    const old = S.level;
    if (!v || v === old) return renderLevels();
    if (S.map.levels.some(l => l.id === v)) { flash(`Level ${v} already exists.`); return renderLevels(); }
    edit(() => {
      S.map.levels.find(l => l.id === old).id = v;
      for (const t of ['walkable', 'obstacles', 'nodes', 'anchors', 'checkpoints', 'backgrounds', 'beacons']) for (const x of items(t)) if (x.level === old) x.level = v;
      if (S.images[old]) { S.images[v] = S.images[old]; delete S.images[old]; }
      S.level = v;
    });
  });
  $('lvl-name').addEventListener('change', () => edit(() => { S.map.levels.find(l => l.id === S.level).name = $('lvl-name').value; }));
  $('lvl-elev').addEventListener('change', () => {
    const v = Number($('lvl-elev').value);
    if (Number.isFinite(v)) edit(() => { S.map.levels.find(l => l.id === S.level).elevation_m = C.roundNum(v); });
  });

  $('btn-add-level').onclick = () => {
    const id = C.nextId('L', S.map.levels.map(l => l.id));
    const top = Math.max(0, ...S.map.levels.map(l => l.elevation_m));
    edit(() => {
      S.map.levels.push({ id, name: `Level ${id.slice(1)}`, elevation_m: S.map.levels.length ? C.roundNum(top + 5) : 0 });
      S.level = id;
    });
    fitView();
  };

  $('btn-del-level').onclick = () => {
    const id = S.level;
    if (S.map.levels.length <= 1) { flash('A map needs at least one level.'); return; }
    if (levelHasContent(id)) { flash(`Level ${id} still has items on it. Delete them first.`); return; }
    edit(() => {
      S.map.levels = S.map.levels.filter(l => l.id !== id);
      S.map.backgrounds = S.map.backgrounds.filter(b => b.level !== id);
      delete S.images[id];
    });
  };

  $('btn-add-dest').onclick = () => {
    if (!S.map.nodes.length) { flash('Place a route node first; destinations attach to nodes.'); return; }
    const node = S.sel && S.sel.type === 'nodes' ? S.map.nodes[S.sel.index].id : S.map.nodes[0].id;
    edit(() => S.map.destinations.push({ id: C.nextId('D', idsOf('destinations')), name: 'New destination', node }));
  };

  // ---- Floor images ------------------------------------------------------------------------

  $('btn-load-image').onclick = () => $('file-image').click();

  /**
   * Loads a floor image for the current level. The image itself stays on this laptop (map.json
   * only stores its file name, scale and origin), so after reopening a map, load it again.
   * A level without a background entry gets a provisional one: origin at the image's bottom-left
   * corner and 20 px per metre, to be corrected with the Scale and Origin tools.
   */
  $('file-image').addEventListener('change', () => {
    const file = $('file-image').files[0];
    $('file-image').value = '';
    if (!file) return;
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      if (S.images[S.level]) URL.revokeObjectURL(S.images[S.level].objectUrl);
      S.images[S.level] = { img, objectUrl: url };
      const b = bgFor(S.level);
      edit(() => {
        if (b) b.image = file.name;
        else S.map.backgrounds.push({ level: S.level, image: file.name, px_per_m: 20, origin_px: [0, img.naturalHeight] });
      });
      fitView();
      flash(b ? 'Image loaded with the saved scale and origin.' : 'Image loaded. Next: Scale (S) with a measured distance, then Origin (G).');
    };
    img.onerror = () => flash('That file could not be read as an image.');
    img.src = url;
  });

  // ---- New, open, export -------------------------------------------------------------------

  /** Asks before throwing away unsaved changes. */
  const okToDiscard = () => !isChanged() || window.confirm('You have unsaved changes. Discard them?');

  $('btn-new').onclick = () => {
    if (!okToDiscard()) return;
    loadMapObject(C.emptyMap());
  };

  $('btn-open').onclick = () => { if (okToDiscard()) $('file-map').click(); };

  $('file-map').addEventListener('change', () => {
    const file = $('file-map').files[0];
    $('file-map').value = '';
    if (!file) return;
    file.text().then(text => {
      let obj;
      try { obj = JSON.parse(text); } catch (err) { window.alert(`Not valid JSON: ${err.message}`); return; }
      if (!obj || typeof obj !== 'object' || Array.isArray(obj)) { window.alert('Not a map.json file.'); return; }
      loadMapObject(C.withDefaultLists(obj));
      const missing = S.map.backgrounds.map(b => `${b.level}: ${b.image}`);
      flash(missing.length ? `Opened. Load the floor images again: ${missing.join(', ')}.` : 'Opened.');
    });
  });

  /** Replaces the whole map (new or opened), resetting history, selection and images. */
  function loadMapObject(obj) {
    for (const k of Object.keys(S.images)) URL.revokeObjectURL(S.images[k].objectUrl);
    S.map = obj;
    S.images = {};
    S.undo = [];
    S.redo = [];
    S.sel = null;
    S.draft = null;
    S.level = Array.isArray(obj.levels) && obj.levels.length && obj.levels[0] && obj.levels[0].id ? obj.levels[0].id : '';
    S.baseline = C.formatMap(S.map);
    afterChange();
    fitView();
  }

  /**
   * Export: refuse while there are errors; otherwise bump the version if the map changed since it
   * was opened or last exported, write canonical JSON and download it as map.json.
   * Concept: an unchanged map re-exports byte-for-byte identical, with the same version.
   */
  $('btn-export').onclick = () => {
    const { errors, warnings } = C.validateMap(S.map);
    if (errors.length) {
      window.alert(`Fix ${errors.length} error(s) before exporting. They are listed under Checks.`);
      $('validation').scrollIntoView({ behavior: 'smooth' });
      return;
    }
    if (isChanged()) S.map.version = (Number.isInteger(S.map.version) ? S.map.version : 0) + 1;
    const text = C.formatMap(S.map);
    S.baseline = text;
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
    a.download = 'map.json';
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
    afterChange();
    flash(`Exported map.json, version ${S.map.version}${warnings.length ? ` (${warnings.length} warning(s))` : ''}.`);
  };

  $('btn-undo').onclick = undo;
  $('btn-redo').onclick = redo;
  $('btn-fit').onclick = fitView;

  window.addEventListener('beforeunload', e => { if (isChanged()) { e.preventDefault(); e.returnValue = ''; } });

  // ---- Start-up -------------------------------------------------------------------------------

  /** Read-only hooks for automated tests (tools/map-editor/test/e2e.js). */
  window.MapEditorDebug = {
    map: () => JSON.parse(JSON.stringify(S.map)),
    level: () => S.level,
    view: () => ({ ...S.view }),
    validation: () => C.validateMap(S.map),
    mapToScreen: (x, y) => toScreen({ x, y }),
  };

  setTool('select');
  afterChange();
  requestAnimationFrame(fitView);
})();
