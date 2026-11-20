/*
 * Unit tests for map-core.js. Run from the repo root with:
 *   node --test tools/map-editor/test/
 *
 * Concept: the same shared fixtures (maps/fixtures/ok-*.json, bad-*.json) are checked by the
 * Kotlin tests in positioning-core, so the editor and the phone app agree on which maps are usable.
 */
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const core = require('../map-core.js');

const repo = path.resolve(__dirname, '..', '..', '..');
const fixturesDir = path.join(repo, 'maps', 'fixtures');
const sample = () => JSON.parse(fs.readFileSync(path.join(repo, 'maps', 'sample', 'map.json'), 'utf8'));

/** Asserts that some message contains every fragment, printing all messages on failure. */
function mentions(messages, ...fragments) {
  assert.ok(messages.some(m => fragments.every(f => m.includes(f))), `expected ${JSON.stringify(fragments)} in ${JSON.stringify(messages)}`);
}

test('sample map validates with no errors or warnings (same as the Kotlin loader)', () => {
  const r = core.validateMap(sample());
  assert.deepEqual(r, { errors: [], warnings: [] });
});

test('shared fixtures: ok-* load, bad-* are rejected', () => {
  const files = fs.readdirSync(fixturesDir).filter(f => f.endsWith('.json'));
  assert.ok(files.length >= 10, 'expected the fixture set');
  for (const f of files) {
    const r = core.validateMap(JSON.parse(fs.readFileSync(path.join(fixturesDir, f), 'utf8')));
    if (f.startsWith('ok-')) assert.deepEqual(r.errors, [], `${f} should load`);
    else if (f.startsWith('bad-')) assert.ok(r.errors.length > 0, `${f} should be rejected`);
  }
});

test('error messages name the problem', () => {
  const load = name => core.validateMap(JSON.parse(fs.readFileSync(path.join(fixturesDir, name), 'utf8')));
  mentions(load('bad-duplicate-node.json').errors, "nodes: id 'a' is used 2 times");
  mentions(load('bad-escalator-wrong-way.json').errors, 'escalator_down must go down');
  mentions(load('bad-bowtie.json').errors, 'walkable[3]', 'cross');
  mentions(load('bad-unknown-kind.json').errors, "unknown kind 'travelator'");
  mentions(load('bad-heading-range.json').errors, 'heading_deg', '[0, 360)');
  mentions(load('bad-missing-version.json').errors, "'version' is required");
  mentions(load('bad-unknown-level.json').errors, "unknown level 'L9'");
  mentions(load('bad-walk-across-levels.json').errors, 'walk edge must stay on one level');
  mentions(load('bad-lift-same-level.json').errors, 'lift edge must join two different levels');
  mentions(load('bad-wrong-type.json').errors, "nodes[0]: 'x' is required and must be a number");
  const warn = load('ok-warnings-only.json').warnings;
  mentions(warn, "node 'g'", 'outside walkable space');
  mentions(warn, 'edge b→g', 'leaves walkable space');
});

test('walkability matches the Kotlin edge cases', () => {
  const m = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'ok-full.json'), 'utf8'));
  const w = core.makeWalkability(m);
  assert.equal(w.isWalkable('L1', { x: 8.5, y: 3.4 }), false, 'inside the pillar');
  assert.equal(w.isWalkable('L1', { x: 8, y: 3.4 }), true, 'on the pillar face');
  assert.equal(w.pathCrossesWall('L1', { x: 17, y: 1 }, { x: 18, y: 15 }), false, 'across overlapping corridors');
  assert.equal(w.pathCrossesWall('L1', { x: 1, y: 2 }, { x: 18, y: 15 }), true, 'cutting the inside corner');
  assert.equal(w.pathCrossesWall('L1', { x: 7, y: 3.4 }, { x: 10, y: 3.4 }), true, 'through the pillar');
});

test('canonical format is stable: format(parse(format(m))) === format(m)', () => {
  for (const m of [sample(), ...fs.readdirSync(fixturesDir).filter(f => f.startsWith('ok-')).map(f => JSON.parse(fs.readFileSync(path.join(fixturesDir, f), 'utf8')))]) {
    const a = core.formatMap(m);
    const b = core.formatMap(JSON.parse(a));
    assert.equal(b, a);
    assert.deepEqual(JSON.parse(a), JSON.parse(JSON.stringify(m)), 'formatting must not change values');
  }
});

test('canonical format orders keys, rounds numbers and keeps unknown fields', () => {
  const m = core.emptyMap();
  m.nodes.push({ y: 0.1 + 0.2, x: -0.0000001, level: 'L1', id: 'n1', label: 'door' });
  m.custom = true;
  const text = core.formatMap(m);
  assert.ok(text.includes('{"id": "n1", "level": "L1", "x": 0, "y": 0.3, "label": "door"}'), text);
  assert.ok(text.trimEnd().endsWith('"custom": true\n}'), text);
  assert.ok(text.startsWith('{\n  "schema": 1,\n  "venue": "new-venue",\n  "version": 1,\n  "units": "m",'));
});

test('nextId finds the first free number', () => {
  assert.equal(core.nextId('n', []), 'n1');
  assert.equal(core.nextId('n', ['n1', 'n2', 'n4']), 'n3');
});
