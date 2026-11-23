/*
 * End-to-end test of the map editor in a real browser (Chromium via Playwright).
 *
 * Run from the repo root:   node tools/map-editor/test/e2e.js [output-folder]
 * Needs Playwright with Chromium installed (npm i -D playwright && npx playwright install chromium).
 *
 * What it does, like a tester would:
 *  1. makes a fake "floor photo" with a 10 m scale bar 400 px long,
 *  2. loads it, sets the scale from the bar (typing 10 m) and the origin at a corridor corner,
 *  3. draws a walkable corridor, an obstacle, two nodes and an edge, a QR plate, a checkpoint and
 *     a destination,
 *  4. exports map.json and checks it validates with no errors,
 *  5. opens the exported file again and re-exports: the two files must be byte-identical
 *     (same version, because nothing changed).
 * It writes map.json, map-reexport.json and a screenshot into the output folder; check map.json
 * with the Kotlin loader too:  ./gradlew -q :replay-cli:run --args="validate-map <folder>/map.json"
 */
'use strict';
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const core = require('../map-core.js');

const outDir = path.resolve(process.argv[2] || path.join(__dirname, 'e2e-output'));
const editorUrl = 'file://' + path.resolve(__dirname, '..', 'index.html');

/** Draws the fake floor photo in a scratch page and saves it as PNG (1000 × 700 px). */
async function makeFloorImage(browser, file) {
  const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
  await page.setContent('<canvas id="c" width="1000" height="700" style="display:block"></canvas>');
  await page.evaluate(() => {
    const ctx = document.getElementById('c').getContext('2d');
    ctx.fillStyle = '#f2efe8'; ctx.fillRect(0, 0, 1000, 700);
    ctx.fillStyle = '#c9c3b6';
    ctx.fillRect(100, 300, 800, 160);   // corridor: 20 m × 4 m at 40 px/m, lower-left corner at (100, 460)
    ctx.fillRect(740, 60, 160, 400);    // side corridor
    ctx.fillStyle = '#8d8578'; ctx.fillRect(420, 320, 40, 40); // pillar 1 m × 1 m
    ctx.strokeStyle = '#000'; ctx.lineWidth = 4;
    ctx.beginPath(); ctx.moveTo(100, 600); ctx.lineTo(500, 600); ctx.stroke(); // 10 m scale bar
    ctx.font = '24px sans-serif'; ctx.fillStyle = '#000'; ctx.fillText('10 m', 270, 640);
  });
  await page.locator('#c').screenshot({ path: file });
  await page.close();
}

(async () => {
  fs.mkdirSync(outDir, { recursive: true });
  const browser = await chromium.launch();
  const floor = path.join(outDir, 'floor.png');
  await makeFloorImage(browser, floor);

  const context = await browser.newContext({ viewport: { width: 1400, height: 900 }, acceptDownloads: true });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  const dialogs = [];
  page.on('dialog', async d => { dialogs.push(d.message()); await d.accept(d.type() === 'prompt' ? '10' : undefined); });
  await page.goto(editorUrl);

  const canvasBox = await page.locator('#canvas').boundingBox();
  /** Screen position (page pixels) of a map point, via the editor's own transform. */
  const at = async (x, y) => {
    const s = await page.evaluate(([x, y]) => window.MapEditorDebug.mapToScreen(x, y), [x, y]);
    return { x: canvasBox.x + s.x, y: canvasBox.y + s.y };
  };
  const clickMap = async (x, y) => { const p = await at(x, y); await page.mouse.click(p.x, p.y); };
  const dragMap = async (x1, y1, x2, y2) => {
    const a = await at(x1, y1); const b = await at(x2, y2);
    await page.mouse.move(a.x, a.y); await page.mouse.down();
    await page.mouse.move((a.x + b.x) / 2, (a.y + b.y) / 2); await page.mouse.move(b.x, b.y); await page.mouse.up();
  };
  /** Map position of an image pixel, given the level's current background settings. */
  const imagePixelToMap = async (u, v) => page.evaluate(([u, v]) => {
    const b = window.MapEditorDebug.map().backgrounds[0];
    return { x: (u - b.origin_px[0]) / b.px_per_m, y: (b.origin_px[1] - v) / b.px_per_m };
  }, [u, v]);

  // 1–2. Load the image, set the scale from the 10 m bar, set the origin at the corridor's lower-left corner.
  await page.locator('#file-image').setInputFiles(floor);
  await page.waitForFunction(() => window.MapEditorDebug.map().backgrounds.length === 1);
  await page.keyboard.press('s');
  let p = await imagePixelToMap(100, 600); await clickMap(p.x, p.y);
  p = await imagePixelToMap(500, 600); await clickMap(p.x, p.y);
  await page.waitForFunction(() => Math.abs(window.MapEditorDebug.map().backgrounds[0].px_per_m - 40) < 0.5);
  await page.keyboard.press('g');
  p = await imagePixelToMap(100, 460); await clickMap(p.x, p.y);
  const bg = (await page.evaluate(() => window.MapEditorDebug.map())).backgrounds[0];
  assert.ok(Math.abs(bg.px_per_m - 40) < 0.5, `scale should be ~40 px/m, got ${bg.px_per_m}`);
  assert.ok(Math.abs(bg.origin_px[0] - 100) < 2 && Math.abs(bg.origin_px[1] - 460) < 2, `origin ${bg.origin_px}`);

  // 3. Draw: corridor 20 × 4 m plus side corridor, a pillar, nodes and an edge, a plate, a checkpoint.
  await page.keyboard.press('w');
  for (const [x, y] of [[0, 0], [20, 0], [20, 4], [0, 4]]) await clickMap(x, y);
  await page.keyboard.press('Enter');
  await page.keyboard.press('w');
  for (const [x, y] of [[16, 0], [20, 0], [20, 10], [16, 10]]) await clickMap(x, y);
  await clickMap(16, 0); // clicking the first corner closes the shape
  await page.keyboard.press('o');
  for (const [x, y] of [[8, 2.5], [9, 2.5], [9, 3.5], [8, 3.5]]) await clickMap(x, y);
  await page.keyboard.press('Enter');
  await page.keyboard.press('n');
  await clickMap(1, 1.5);
  await clickMap(18, 1.5);
  await clickMap(18, 8);
  await page.keyboard.press('e');
  await clickMap(1, 1.5); await clickMap(18, 1.5);
  await clickMap(18, 1.5); await clickMap(18, 8);
  await page.keyboard.press('a');
  await dragMap(0, 2, 2, 2);          // plate on the west wall, facing east (90°)
  await page.keyboard.press('c');
  await dragMap(10, 1.5, 12, 1.5);    // checkpoint heading east (90°)
  await page.keyboard.press('b');
  await clickMap(15, 4);              // a Bluetooth beacon on the corridor's north wall
  await page.keyboard.press('v');
  await clickMap(18, 8);              // select node n3 and make it a destination
  await page.getByRole('button', { name: 'Make destination' }).click();
  await page.locator('#venue').fill('dry-run-office');
  await page.locator('#venue').press('Enter');

  const drawn = await page.evaluate(() => window.MapEditorDebug.map());
  assert.equal(drawn.walkable.length, 2);
  assert.equal(drawn.obstacles.length, 1);
  assert.deepEqual(drawn.nodes.map(n => [n.id, n.x, n.y]), [['n1', 1, 1.5], ['n2', 18, 1.5], ['n3', 18, 8]]);
  assert.deepEqual(drawn.edges, [{ from: 'n1', to: 'n2', kind: 'walk' }, { from: 'n2', to: 'n3', kind: 'walk' }]);
  assert.deepEqual([drawn.anchors[0].id, drawn.anchors[0].x, drawn.anchors[0].y, drawn.anchors[0].facing_deg], ['A01', 0, 2, 90]);
  assert.deepEqual([drawn.checkpoints[0].id, drawn.checkpoints[0].heading_deg], ['C01', 90]);
  assert.equal(drawn.destinations[0].node, 'n3');
  assert.deepEqual(drawn.beacons, [{ id: 'B01', level: 'L1', x: 15, y: 4, height_m: 2.8, uuid: 'D55248B4-8F80-46C8-88AC-EEC9C72B2B7C', major: 1, minor: 1 }]);
  const v = core.validateMap(drawn);
  assert.deepEqual(v.errors, [], 'drawn map should have no errors');

  // 4. Export.
  const exportOnce = async (file) => {
    const [download] = await Promise.all([page.waitForEvent('download'), page.locator('#btn-export').click()]);
    await download.saveAs(file);
    return fs.readFileSync(file, 'utf8');
  };
  const first = await exportOnce(path.join(outDir, 'map.json'));
  const firstMap = JSON.parse(first);
  assert.equal(firstMap.version, 2, 'version bumps from 1 to 2 because the map changed');
  assert.deepEqual(core.validateMap(firstMap).errors, []);
  await page.screenshot({ path: path.join(outDir, 'editor.png') });

  // 5. Re-open the exported file and export again without changes: identical bytes.
  await page.locator('#file-map').setInputFiles(path.join(outDir, 'map.json'));
  await page.waitForFunction(() => window.MapEditorDebug.map().venue === 'dry-run-office');
  const second = await exportOnce(path.join(outDir, 'map-reexport.json'));
  assert.equal(second, first, 'open then export must give identical JSON');

  // 6. Broken files: they open, the checks list the problems, and the page keeps working.
  const fixtures = path.resolve(__dirname, '..', '..', '..', 'maps', 'fixtures');
  const mangled = path.join(outDir, 'mangled.json');
  fs.writeFileSync(mangled, JSON.stringify({ schema: 1, venue: 'x', version: 1, units: 'm',
    levels: [{ id: 'L1', name: 'L1', elevation_m: 0 }], walkable: [{ level: 'L1', polygon: 'oops' }] }));
  for (const file of [path.join(fixtures, 'bad-bowtie.json'), mangled]) {
    await page.locator('#file-map').setInputFiles(file);
    await page.waitForFunction(() => window.MapEditorDebug.validation().errors.length > 0);
    const badge = await page.locator('#checks-badge').textContent();
    assert.match(badge, /error/, `badge should report errors for ${path.basename(file)}`);
  }

  assert.deepEqual(pageErrors, [], 'no JavaScript errors on the page');
  await browser.close();
  console.log(`E2E OK: drew, exported (version ${firstMap.version}), re-opened and re-exported identically.`);
  console.log(`Warnings in drawn map: ${v.warnings.length ? v.warnings.join(' | ') : 'none'}`);
  console.log(`Dialogs: ${dialogs.join(' | ')}`);
  console.log(`Output: ${outDir}`);
})().catch(err => { console.error(err); process.exit(1); });
