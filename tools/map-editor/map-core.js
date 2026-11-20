/*
 * map-core.js — the map editor's non-UI logic: geometry, validation and canonical JSON output.
 *
 * Concept: this file mirrors the Kotlin map loader in android/positioning-core (MapLoader.kt,
 * Walkability.kt, Polygon.kt, Segments.kt) so the editor can show the same errors and warnings the
 * phone app would, before a map is exported. The two implementations are kept in step by shared
 * test fixtures in maps/fixtures/: both the Kotlin tests and this file's Node tests must agree on
 * which fixtures load and which are rejected.
 *
 * It is a plain script (no build step, no modules) so index.html can load it straight from disk,
 * and it also exports itself for Node (`require('./map-core.js')`) so it can be unit-tested.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.MapCore = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /** Tolerance in metres for "touching" tests: 1 nanometre, far below survey precision. */
  const EPS = 1e-9;

  /** The only map schema version supported (same as MapLoader.SUPPORTED_SCHEMA). */
  const SUPPORTED_SCHEMA = 1;

  // ---------------------------------------------------------------------------------------------
  // Small 2D vector helpers. Points are {x, y} in map-frame metres (x right, y up on the plan).
  // ---------------------------------------------------------------------------------------------

  const sub = (a, b) => ({ x: a.x - b.x, y: a.y - b.y });
  const add = (a, b) => ({ x: a.x + b.x, y: a.y + b.y });
  const mul = (a, k) => ({ x: a.x * k, y: a.y * k });
  const dot = (a, b) => a.x * b.x + a.y * b.y;
  /** 2D cross product: sign says left (+) or right (−); zero means parallel. */
  const cross = (a, b) => a.x * b.y - a.y * b.x;
  const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);

  /**
   * Parameters t in [0, 1] along segment a→b where it meets segment c→d.
   *
   * Concept: write the segments as a + t·r and c + u·s and solve with cross products. Parallel
   * segments only meet if collinear; then the overlap's ends are returned as projected t values.
   * Port of Segments.crossingParams in Kotlin.
   */
  function crossingParams(a, b, c, d) {
    const r = sub(b, a);
    const s = sub(d, c);
    const denom = cross(r, s);
    const ca = sub(c, a);
    if (Math.abs(denom) > EPS) {
      const t = cross(ca, s) / denom;
      const u = cross(ca, r) / denom;
      if (t >= -EPS && t <= 1 + EPS && u >= -EPS && u <= 1 + EPS) return [Math.min(1, Math.max(0, t))];
      return [];
    }
    if (Math.abs(cross(ca, r)) > EPS * Math.max(1, Math.hypot(r.x, r.y))) return [];
    const rr = dot(r, r);
    if (rr < EPS * EPS) return [];
    const tc = dot(ca, r) / rr;
    const td = dot(sub(d, a), r) / rr;
    const lo = Math.min(tc, td);
    const hi = Math.max(tc, td);
    if (hi < -EPS || lo > 1 + EPS) return [];
    return [Math.min(1, Math.max(0, lo)), Math.min(1, Math.max(0, hi))];
  }

  /** Shortest distance from point p to segment a→b (project, clamp, measure). */
  function distanceToSegment(p, a, b) {
    const r = sub(b, a);
    const rr = dot(r, r);
    if (rr < EPS * EPS) return dist(p, a);
    const t = Math.min(1, Math.max(0, dot(sub(p, a), r) / rr));
    return dist(p, add(a, mul(r, t)));
  }

  // ---------------------------------------------------------------------------------------------
  // Polygons. A polygon is an array of {x, y}; the last point joins back to the first.
  // ---------------------------------------------------------------------------------------------

  /** The polygon's sides as [start, end] pairs, closing side last. */
  function polygonEdges(pts) {
    return pts.map((p, i) => [p, pts[(i + 1) % pts.length]]);
  }

  /** True if p lies on the outline (within EPS). Checked first because ray casting is ambiguous there. */
  function onBoundary(pts, p) {
    return polygonEdges(pts).some(([a, b]) => distanceToSegment(p, a, b) <= EPS);
  }

  /**
   * Even–odd ray casting: count outline crossings of a ray from p towards +x; odd = inside.
   * The half-open y test makes a ray through a vertex count once.
   */
  function rayCastInside(pts, p) {
    let inside = false;
    for (const [a, b] of polygonEdges(pts)) {
      if ((a.y > p.y) !== (b.y > p.y)) {
        const xAtY = a.x + ((p.y - a.y) * (b.x - a.x)) / (b.y - a.y);
        if (p.x < xAtY) inside = !inside;
      }
    }
    return inside;
  }

  /** Inside or on the outline (used for walkable areas). */
  const containsInclusive = (pts, p) => onBoundary(pts, p) || rayCastInside(pts, p);
  /** Strictly inside (used for obstacles: brushing a pillar is fine, entering it is not). */
  const containsStrict = (pts, p) => !onBoundary(pts, p) && rayCastInside(pts, p);

  /** Shoelace formula: positive for anticlockwise points. Zero area means a flat shape. */
  function signedArea(pts) {
    let s = 0;
    for (const [a, b] of polygonEdges(pts)) s += cross(a, b);
    return s / 2;
  }

  /**
   * Why a polygon is invalid, or null if it is a valid simple polygon (port of Polygon.problem()).
   * Concept: at least 3 points, no repeated neighbours, real area, and sides that only meet their
   * two neighbours at shared corners — a self-crossing "bow-tie" has no clear inside.
   */
  function polygonProblem(pts) {
    if (pts.length < 3) return `needs at least 3 points, has ${pts.length}`;
    if (pts.some(p => !Number.isFinite(p.x) || !Number.isFinite(p.y))) return 'has a coordinate that is not a finite number';
    for (let i = 0; i < pts.length; i++) {
      if (dist(pts[i], pts[(i + 1) % pts.length]) <= EPS) return `has a repeated point at index ${(i + 1) % pts.length}`;
    }
    if (Math.abs(signedArea(pts)) <= EPS) return 'has zero area';
    const edges = polygonEdges(pts);
    const n = edges.length;
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const adjacent = j === i + 1 || (i === 0 && j === n - 1);
        const params = crossingParams(edges[i][0], edges[i][1], edges[j][0], edges[j][1]);
        if (params.length === 0) continue;
        if (!adjacent) return `sides ${i} and ${j} cross or touch`;
        if (params.length > 1 && Math.abs(params[0] - params[1]) > EPS) return `sides ${i} and ${j} overlap`;
      }
    }
    return null;
  }

  /** Converts a map.json polygon ([[x, y], ...]) into points. */
  const toPoints = poly => poly.map(([x, y]) => ({ x, y }));

  // ---------------------------------------------------------------------------------------------
  // Walkability (port of Walkability.kt)
  // ---------------------------------------------------------------------------------------------

  /**
   * Builds walkability queries for a (structurally valid) map.
   *
   * Concept: walkable space = union of a level's walkable polygons minus its obstacles. A straight
   * path is fine if every stretch between consecutive outline crossings has a walkable midpoint —
   * exact, and correct for overlapping corridors.
   */
  function makeWalkability(map) {
    const byLevel = (areas) => {
      const out = {};
      for (const a of areas || []) (out[a.level] = out[a.level] || []).push(toPoints(a.polygon));
      return out;
    };
    const walkable = byLevel(map.walkable);
    const obstacles = byLevel(map.obstacles);

    function isWalkable(level, p) {
      const w = walkable[level];
      if (!w || !w.some(poly => containsInclusive(poly, p))) return false;
      return !(obstacles[level] || []).some(poly => containsStrict(poly, p));
    }

    function pathCrossesWall(level, a, b) {
      if (!isWalkable(level, a) || !isWalkable(level, b)) return true;
      if (dist(a, b) <= EPS) return false;
      const ts = [0, 1];
      for (const poly of [...(walkable[level] || []), ...(obstacles[level] || [])]) {
        for (const [c, d] of polygonEdges(poly)) ts.push(...crossingParams(a, b, c, d));
      }
      ts.sort((x, y) => x - y);
      const r = sub(b, a);
      for (let i = 0; i < ts.length - 1; i++) {
        if (ts[i + 1] - ts[i] <= EPS) continue;
        if (!isWalkable(level, add(a, mul(r, (ts[i] + ts[i + 1]) / 2)))) return true;
      }
      return false;
    }

    return { isWalkable, pathCrossesWall };
  }

  // ---------------------------------------------------------------------------------------------
  // Edge kinds (same table as EdgeKind.kt)
  // ---------------------------------------------------------------------------------------------

  /** twoWay: usable both directions. stepFree: allowed on step-free routes. */
  const EDGE_KINDS = {
    walk: { twoWay: true, stepFree: true },
    stairs: { twoWay: true, stepFree: false },
    lift: { twoWay: true, stepFree: true },
    escalator_up: { twoWay: false, stepFree: false },
    escalator_down: { twoWay: false, stepFree: false },
  };

  // ---------------------------------------------------------------------------------------------
  // Validation (port of MapLoader.validate)
  // ---------------------------------------------------------------------------------------------

  const isNum = v => typeof v === 'number' && Number.isFinite(v);
  const isStr = v => typeof v === 'string';
  const fmtPt = p => `(${p.x.toFixed(2)}, ${p.y.toFixed(2)})`;

  /**
   * Validates a parsed map.json object. Returns {errors, warnings} (arrays of messages).
   *
   * Concept (same as the Kotlin loader): pass 1 checks structure — required fields and types,
   * unique ids, references that resolve, numbers in range, valid polygons, edge kinds that match
   * the level change. Only if that passes, pass 2 checks geometry and reports warnings: nodes,
   * plates and checkpoints outside walkable space, edges cutting through walls, unconnected nodes.
   * Errors make the map unusable; warnings are allowed.
   */
  function validateMap(m) {
    const errors = [];
    const warnings = [];
    if (!m || typeof m !== 'object' || Array.isArray(m)) return { errors: ['map must be a JSON object'], warnings };

    // Field-type helper: records an error and returns false if obj[field] isn't of the wanted type.
    const need = (obj, where, field, kind) => {
      const v = obj == null ? undefined : obj[field];
      const ok = kind === 'number' ? typeof v === 'number' : kind === 'int' ? Number.isInteger(v) : kind === 'string' ? isStr(v) : Array.isArray(v);
      if (!ok) errors.push(`${where}: '${field}' is required and must be ${kind === 'int' ? 'a whole number' : kind === 'array' ? 'a list' : kind === 'string' ? 'text' : 'a number'}`);
      return ok;
    };
    const list = (field) => {
      if (m[field] === undefined) return [];
      if (!Array.isArray(m[field])) { errors.push(`${field}: must be a list`); return []; }
      return m[field];
    };

    // ---- Top level
    if (need(m, 'map', 'schema', 'int') && m.schema !== SUPPORTED_SCHEMA) errors.push(`schema: expected ${SUPPORTED_SCHEMA}, got ${m.schema}`);
    if (need(m, 'map', 'units', 'string') && m.units !== 'm') errors.push(`units: expected "m", got "${m.units}"`);
    if (need(m, 'map', 'venue', 'string') && !m.venue.trim()) errors.push('venue: must not be blank');
    need(m, 'map', 'version', 'int');
    const levels = need(m, 'map', 'levels', 'array') ? m.levels : [];
    if (Array.isArray(m.levels) && m.levels.length === 0) errors.push('levels: at least one level is required');

    const checkIds = (section, items) => {
      const counts = {};
      items.forEach((it, i) => {
        if (!isStr(it && it.id)) return; // type error reported elsewhere
        if (!it.id.trim()) errors.push(`${section}[${i}]: id must not be blank`);
        else counts[it.id] = (counts[it.id] || 0) + 1;
      });
      for (const [id, n] of Object.entries(counts)) if (n > 1) errors.push(`${section}: id '${id}' is used ${n} times`);
    };

    // ---- Levels
    levels.forEach((l, i) => {
      const where = `levels[${i}]`;
      need(l, where, 'id', 'string');
      need(l, where, 'name', 'string');
      if (need(l, where, 'elevation_m', 'number') && !isNum(l.elevation_m)) errors.push(`${where} '${l.id}': elevation_m must be a finite number`);
    });
    checkIds('levels', levels);
    const levelIds = new Set(levels.filter(l => l && isStr(l.id)).map(l => l.id));
    const elevation = {};
    levels.forEach(l => { if (l && isStr(l.id)) elevation[l.id] = l.elevation_m; });
    const checkLevel = (where, level) => { if (isStr(level) && !levelIds.has(level)) errors.push(`${where}: unknown level '${level}'`); };

    // ---- Areas
    for (const section of ['walkable', 'obstacles']) {
      list(section).forEach((a, i) => {
        const where = `${section}[${i}]`;
        if (need(a, where, 'level', 'string')) checkLevel(where, a.level);
        if (!need(a, where, 'polygon', 'array')) return;
        const bad = a.polygon.findIndex(p => !Array.isArray(p) || p.length !== 2 || p.some(c => typeof c !== 'number'));
        if (bad >= 0) { errors.push(`${where}: point ${bad} must be [x, y], got ${JSON.stringify(a.polygon[bad])}`); return; }
        const problem = polygonProblem(toPoints(a.polygon));
        if (problem) errors.push(`${where}: polygon ${problem}`);
      });
    }

    // ---- Nodes
    const nodes = list('nodes');
    nodes.forEach((n, i) => {
      const where = `nodes[${i}]`;
      need(n, where, 'id', 'string');
      if (need(n, where, 'level', 'string')) checkLevel(`${where} '${n.id}'`, n.level);
      const hasXY = need(n, where, 'x', 'number') & need(n, where, 'y', 'number');
      if (hasXY && (!isNum(n.x) || !isNum(n.y))) errors.push(`${where} '${n.id}': x and y must be finite numbers`);
    });
    checkIds('nodes', nodes);
    const nodeById = {};
    nodes.forEach(n => { if (n && isStr(n.id)) nodeById[n.id] = n; });

    // ---- Edges
    const seen = new Set();
    list('edges').forEach((e, i) => {
      const okTypes = need(e, `edges[${i}]`, 'from', 'string') & need(e, `edges[${i}]`, 'to', 'string') & need(e, `edges[${i}]`, 'kind', 'string');
      if (!okTypes) return;
      const where = `edges[${i}] ${e.from}→${e.to}`;
      const kind = EDGE_KINDS[e.kind];
      if (!kind) errors.push(`${where}: unknown kind '${e.kind}' (expected one of ${Object.keys(EDGE_KINDS).join(', ')})`);
      const from = nodeById[e.from];
      const to = nodeById[e.to];
      if (!from) errors.push(`${where}: unknown node '${e.from}'`);
      if (!to) errors.push(`${where}: unknown node '${e.to}'`);
      if (e.from === e.to) errors.push(`${where}: an edge must join two different nodes`);
      if (!kind || !from || !to || e.from === e.to) return;
      const key = `${e.from}|${e.to}|${e.kind}`;
      if (seen.has(key)) warnings.push(`${where}: duplicate edge`);
      seen.add(key);
      const fe = elevation[from.level];
      const te = elevation[to.level];
      if (!isNum(fe) || !isNum(te)) return;
      const same = from.level === to.level;
      if (e.kind === 'walk' && !same) errors.push(`${where}: a walk edge must stay on one level (${from.level} → ${to.level})`);
      if (e.kind === 'lift' && same) errors.push(`${where}: a lift edge must join two different levels`);
      if (e.kind === 'escalator_up' && te <= fe) errors.push(`${where}: escalator_up must go up, but ${from.level} (${fe} m) → ${to.level} (${te} m)`);
      if (e.kind === 'escalator_down' && te >= fe) errors.push(`${where}: escalator_down must go down, but ${from.level} (${fe} m) → ${to.level} (${te} m)`);
    });

    const checkAngle = (where, v) => { if (!isNum(v) || v < 0 || v >= 360) errors.push(`${where} must be in [0, 360), got ${v}`); };

    // ---- Anchors
    const anchors = list('anchors');
    anchors.forEach((a, i) => {
      const where0 = `anchors[${i}]`;
      const ok = ['id', 'level'].every(f => need(a, where0, f, 'string')) &
        ['x', 'y', 'mount_height_m', 'facing_deg', 'size_m'].every(f => need(a, where0, f, 'number'));
      if (!ok) return;
      const where = `${where0} '${a.id}'`;
      checkLevel(where, a.level);
      if (!isNum(a.x) || !isNum(a.y)) errors.push(`${where}: x and y must be finite numbers`);
      checkAngle(`${where}: facing_deg`, a.facing_deg);
      if (!(a.size_m > 0) || !isNum(a.size_m)) errors.push(`${where}: size_m must be a positive number, got ${a.size_m}`);
      if (!(a.mount_height_m >= 0) || !isNum(a.mount_height_m)) errors.push(`${where}: mount_height_m must be zero or more, got ${a.mount_height_m}`);
    });
    checkIds('anchors', anchors);

    // ---- Checkpoints
    const checkpoints = list('checkpoints');
    checkpoints.forEach((c, i) => {
      const where0 = `checkpoints[${i}]`;
      const ok = ['id', 'level'].every(f => need(c, where0, f, 'string')) & ['x', 'y', 'heading_deg'].every(f => need(c, where0, f, 'number'));
      if (!ok) return;
      const where = `${where0} '${c.id}'`;
      checkLevel(where, c.level);
      if (!isNum(c.x) || !isNum(c.y)) errors.push(`${where}: x and y must be finite numbers`);
      checkAngle(`${where}: heading_deg`, c.heading_deg);
    });
    checkIds('checkpoints', checkpoints);

    // ---- Destinations
    const destinations = list('destinations');
    destinations.forEach((d, i) => {
      const where0 = `destinations[${i}]`;
      if (!(['id', 'name', 'node'].every(f => need(d, where0, f, 'string')))) return;
      const where = `${where0} '${d.id}'`;
      if (!d.name.trim()) errors.push(`${where}: name must not be blank`);
      if (!nodeById[d.node]) errors.push(`${where}: unknown node '${d.node}'`);
    });
    checkIds('destinations', destinations);

    // ---- Backgrounds
    list('backgrounds').forEach((b, i) => {
      const where = `backgrounds[${i}]`;
      if (need(b, where, 'level', 'string')) checkLevel(where, b.level);
      if (need(b, where, 'image', 'string') && !b.image.trim()) errors.push(`${where}: image must not be blank`);
      if (need(b, where, 'px_per_m', 'number') && !(b.px_per_m > 0 && isNum(b.px_per_m))) errors.push(`${where}: px_per_m must be a positive number`);
      if (need(b, where, 'origin_px', 'array') && (b.origin_px.length !== 2 || !b.origin_px.every(isNum))) errors.push(`${where}: origin_px must be [x, y]`);
    });

    if (errors.length) return { errors, warnings };

    // ---- Pass 2: geometry warnings
    const walk = makeWalkability(m);
    for (const l of levels) if (!list('walkable').some(a => a.level === l.id)) warnings.push(`level '${l.id}': has no walkable area`);
    for (const n of nodes) {
      if (!walk.isWalkable(n.level, n)) warnings.push(`node '${n.id}': at ${fmtPt(n)} on ${n.level} is outside walkable space`);
    }
    const connected = new Set();
    list('edges').forEach(e => { connected.add(e.from); connected.add(e.to); });
    for (const n of nodes) if (!connected.has(n.id)) warnings.push(`node '${n.id}': has no edges, so routes can't use it`);
    for (const e of list('edges')) {
      const a = nodeById[e.from];
      const b = nodeById[e.to];
      if (a.level === b.level && walk.pathCrossesWall(a.level, a, b)) {
        warnings.push(`edge ${e.from}→${e.to}: the straight line between the nodes leaves walkable space`);
      }
    }
    for (const a of anchors) {
      if (!walk.isWalkable(a.level, a)) {
        warnings.push(`anchor '${a.id}': at ${fmtPt(a)} on ${a.level} is outside walkable space (fine if it marks a wall, but check the survey)`);
      }
    }
    for (const c of checkpoints) {
      if (!walk.isWalkable(c.level, c)) warnings.push(`checkpoint '${c.id}': at ${fmtPt(c)} on ${c.level} is outside walkable space`);
    }
    return { errors, warnings };
  }

  // ---------------------------------------------------------------------------------------------
  // Canonical JSON output
  // ---------------------------------------------------------------------------------------------

  /** Key order per section, matching SPEC.md's example. Unknown keys follow in their original order. */
  const KEY_ORDER = {
    top: ['schema', 'venue', 'version', 'units', 'levels', 'walkable', 'obstacles', 'nodes', 'edges', 'anchors', 'checkpoints', 'destinations', 'backgrounds'],
    levels: ['id', 'name', 'elevation_m'],
    walkable: ['level', 'polygon'],
    obstacles: ['level', 'polygon'],
    nodes: ['id', 'level', 'x', 'y'],
    edges: ['from', 'to', 'kind'],
    anchors: ['id', 'level', 'x', 'y', 'mount_height_m', 'facing_deg', 'size_m'],
    checkpoints: ['id', 'level', 'x', 'y', 'heading_deg'],
    destinations: ['id', 'name', 'node'],
    backgrounds: ['level', 'image', 'px_per_m', 'origin_px'],
  };

  /**
   * Rounds to 6 decimal places (a micrometre) and turns −0 into 0.
   * Concept: floating-point sums like 0.1 + 0.2 = 0.30000000000000004 would otherwise leak into
   * the file and make exports differ for no real reason.
   */
  function roundNum(v) {
    const r = Math.round(v * 1e6) / 1e6;
    return Object.is(r, -0) ? 0 : r;
  }

  /** Keys of obj in the preferred order first, then any others in their existing order. */
  function orderedKeys(obj, order) {
    const known = (order || []).filter(k => Object.prototype.hasOwnProperty.call(obj, k));
    return [...known, ...Object.keys(obj).filter(k => !known.includes(k))];
  }

  /** One-line JSON for a value, with ", " and ": " spacing and rounded numbers. */
  function compact(v, order) {
    if (typeof v === 'number') return Number.isFinite(v) ? String(roundNum(v)) : 'null';
    if (Array.isArray(v)) return '[' + v.map(x => compact(x)).join(', ') + ']';
    if (v && typeof v === 'object') return '{' + orderedKeys(v, order).map(k => JSON.stringify(k) + ': ' + compact(v[k])).join(', ') + '}';
    return JSON.stringify(v);
  }

  /**
   * The map as canonical map.json text: fixed key order, one item per line, rounded numbers,
   * trailing newline.
   *
   * Concept: "canonical" means the same map always produces byte-identical text, so re-exporting
   * an unchanged map gives an identical file and real changes show up cleanly in a diff.
   */
  function formatMap(m) {
    const lines = ['{'];
    const keys = orderedKeys(m, KEY_ORDER.top);
    keys.forEach((k, i) => {
      const v = m[k];
      const comma = i < keys.length - 1 ? ',' : '';
      if (Array.isArray(v) && KEY_ORDER[k]) {
        if (v.length === 0) {
          lines.push(`  ${JSON.stringify(k)}: []${comma}`);
        } else {
          lines.push(`  ${JSON.stringify(k)}: [`);
          v.forEach((item, j) => lines.push(`    ${compact(item, KEY_ORDER[k])}${j < v.length - 1 ? ',' : ''}`));
          lines.push(`  ]${comma}`);
        }
      } else {
        lines.push(`  ${JSON.stringify(k)}: ${compact(v)}${comma}`);
      }
    });
    lines.push('}');
    return lines.join('\n') + '\n';
  }

  /** A new, empty schema-1 map with one level. */
  function emptyMap() {
    return {
      schema: SUPPORTED_SCHEMA, venue: 'new-venue', version: 1, units: 'm',
      levels: [{ id: 'L1', name: 'Level 1', elevation_m: 0 }],
      walkable: [], obstacles: [], nodes: [], edges: [], anchors: [], checkpoints: [], destinations: [], backgrounds: [],
    };
  }

  /**
   * Fills in any missing optional lists so the editor can rely on them. Does not touch values,
   * so validation still sees exactly what was in the file.
   */
  function withDefaultLists(m) {
    for (const k of ['walkable', 'obstacles', 'nodes', 'edges', 'anchors', 'checkpoints', 'destinations', 'backgrounds']) {
      if (m[k] === undefined) m[k] = [];
    }
    return m;
  }

  /**
   * The next free id like prefix + number, given the ids already used. [width] zero-pads the
   * number (width 2 gives A01, A02, ... as in the spec's examples).
   */
  function nextId(prefix, usedIds, width = 0) {
    const used = new Set(usedIds);
    const make = i => prefix + String(i).padStart(width, '0');
    let i = 1;
    while (used.has(make(i))) i++;
    return make(i);
  }

  return {
    EPS, SUPPORTED_SCHEMA, EDGE_KINDS, KEY_ORDER,
    crossingParams, distanceToSegment, polygonProblem, containsInclusive, containsStrict, toPoints,
    makeWalkability, validateMap, formatMap, roundNum, emptyMap, withDefaultLists, nextId,
  };
});
