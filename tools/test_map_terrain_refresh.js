const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const test = require('node:test');

function harness() {
  const source = fs.readFileSync('src/main/resources/marketweb/map.js', 'utf8');
  const timers = [];
  const environment = {
    window: { innerWidth: 1400, matchMedia: () => ({ matches: false }),
      setTimeout(fn) { timers.push(fn); return timers.length; }, clearTimeout() {}, clearInterval() {} },
    localStorage: { getItem() { return ''; } }, navigator: {}, performance,
    requestAnimationFrame: () => 1, cancelAnimationFrame() {}, Headers,
    Image: class { set src(value) { this.url = value; } get src() { return this.url; } }
  };
  vm.runInNewContext(source.replace(/\}\)\(\);\s*$/, 'window.testApi = { state, tileRefreshKey, configureSquaremapTileLayer, loadTile, applyTerrainChanges: typeof applyTerrainChanges === "function" ? applyTerrainChanges : null, refreshTerrainTiles: typeof refreshTerrainTiles === "function" ? refreshTerrainTiles : null, captureRequestContext, pollTerrainChanges: typeof pollTerrainChanges === "function" ? pollTerrainChanges : null };})();'), environment);
  const api = environment.window.testApi;
  api.state.root = {};
  return { ...api, timers, environment };
}

test('terrain URLs ignore business, selection and global tile epochs', () => {
  const { state, tileRefreshKey } = harness();
  state.snapshot = { renderVersion: 6, tileEpoch: 1, territoryRevision: 1, marketRevision: 1 };
  const original = tileRefreshKey(0, 1, 2);
  state.snapshot.tileEpoch++;
  state.snapshot.territoryRevision++;
  state.snapshot.marketRevision++;
  state.airportRevision++;
  state.selected = { type: 'airport', data: { airportId: 'changed' } };
  assert.equal(tileRefreshKey(0, 1, 2), original);
});

test('one changed terrain tile refreshes only that image and keeps another image', () => {
  const { state, applyTerrainChanges, refreshTerrainTiles, tileRefreshKey } = harness();
  state.snapshot = { renderVersion: 6 };
  const changed = { src: 'before', isConnected: true };
  const other = { src: 'unchanged', isConnected: true };
  state.tileCache.set('0,1,2', changed);
  state.tileCache.set('0,2,2', other);
  const before = tileRefreshKey(0, 2, 2);
  applyTerrainChanges({ dimension: 'minecraft:overworld', session: 'one', revision: 8, reset: false,
    tiles: [{ kind: 'square', zoom: 0, x: 1, z: 2, revision: 8 }] });
  refreshTerrainTiles();
  assert.notEqual(changed.src, 'before');
  assert.equal(other.src, 'unchanged');
  assert.equal(state.tileCache.get('0,2,2'), other);
  assert.equal(tileRefreshKey(0, 2, 2), before);
});

test('overflow revalidates visible Leaflet images in bounded batches without a layer redraw', () => {
  const { state, applyTerrainChanges, refreshTerrainTiles } = harness();
  let redraws = 0;
  const entries = Object.fromEntries(Array.from({ length: 90 }, (_, x) => [x, {
    coords: { x, y: 0, z: 0 }, current: true, el: { src: 'old', isConnected: true }
  }]));
  state.leafletLayers = [{ _tiles: entries, redraw() { redraws++; } }];
  state.leafletMap = { getZoom: () => 0 };
  applyTerrainChanges({ dimension: 'minecraft:overworld', session: 'next', revision: 200, reset: true, tiles: [] });
  refreshTerrainTiles();
  assert.equal(Object.values(entries).filter(e => e.el.src !== 'old').length, 64);
  refreshTerrainTiles();
  assert.equal(Object.values(entries).filter(e => e.el.src !== 'old').length, 90);
  assert.equal(redraws, 0);
});

test('foreign dimension results do not mutate terrain state', () => {
  const { state, applyTerrainChanges } = harness();
  applyTerrainChanges({ dimension: 'minecraft:the_nether', session: 'foreign', revision: 99, reset: true, tiles: [] });
  assert.notEqual(state.terrainSession, 'foreign');
});

test('missing Leaflet retry only touches failed connected tiles', () => {
  const { state, configureSquaremapTileLayer, timers } = harness();
  const events = {};
  const layer = { on(name, callback) { events[name] = callback; }, redraw() { throw new Error('whole layer redrawn'); } };
  configureSquaremapTileLayer(layer);
  const image = { src: 'missing', isConnected: true };
  const coords = { x: 1, y: 2, z: 0 };
  events.tileerror({ tile: image, coords });
  assert.equal(timers.length, 1);
  timers.shift()();
  assert.match(image.src, /\/0\/1_2\.png\?v=/);
  assert.ok(state.terrainFailedTiles.size <= 320);
});

test('missing image state and retry timers remain bounded under many failures', () => {
  const { state, configureSquaremapTileLayer, timers } = harness();
  const events = {};
  const layer = { on(name, callback) { events[name] = callback; } };
  configureSquaremapTileLayer(layer);
  for (let x = 0; x < 1000; x++) events.tileerror({ tile: { isConnected: false }, coords: { x, y: 0, z: 0 } });
  assert.equal(timers.length, 1);
  assert.equal(state.terrainFailedTiles.size, 320);
  timers.shift()();
  assert.equal(state.terrainFailedTiles.size, 0);
});

test('stale retry timers cannot write images after a new view or login', () => {
  const { state, configureSquaremapTileLayer, timers } = harness();
  const events = {};
  const layer = { on(name, callback) { events[name] = callback; } };
  configureSquaremapTileLayer(layer);
  const image = { src: 'old-view', isConnected: true };
  events.tileerror({ tile: image, coords: { x: 0, y: 0, z: 0 } });
  state.mountedToken++;
  timers.shift()();
  assert.equal(image.src, 'old-view');
});

test('a new login rearms public Leaflet retries after the old login timer is discarded', async () => {
  const { state, configureSquaremapTileLayer, captureRequestContext, pollTerrainChanges, timers, environment } = harness();
  const events = {};
  const layer = { on(name, callback) { events[name] = callback; } };
  configureSquaremapTileLayer(layer);
  const image = { src: 'missing-before-login', isConnected: true };
  state.terrainSession = 'one';
  events.tileerror({ tile: image, coords: { x: 1, y: 2, z: 0 } });
  environment.localStorage.getItem = () => 'new-login';
  timers.shift()();
  assert.equal(image.src, 'missing-before-login', 'the stale timer still cannot touch the view');
  assert.equal(timers.length, 0);
  captureRequestContext();
  environment.fetch = async () => ({ ok: true, json: async () => ({
    dimension: 'minecraft:overworld', session: 'one', revision: 0, reset: false, tiles: []
  }) });

  await pollTerrainChanges();

  assert.equal(timers.length, 1, 'normal polling must wake retained public terrain work in the new login');
  timers.shift()();
  assert.match(image.src, /\/0\/1_2\.png\?v=.*&retry=/);
  assert.equal(state.terrainFailedTiles.size, 0);
});

test('a new login also rearms missing canvas tiles without a terrain revision change', async () => {
  const { state, loadTile, captureRequestContext, pollTerrainChanges, timers, environment } = harness();
  state.terrainSession = 'one';
  const image = loadTile(0, 1, 2);
  image.onerror();
  assert.equal(state.missingTiles.size, 1);
  state.missingTiles.set('0,1,2', 0); // The retry delay has elapsed when the timer is delivered.
  environment.localStorage.getItem = () => 'new-login';
  timers.shift()();
  assert.equal(timers.length, 0);
  captureRequestContext();
  environment.fetch = async () => ({ ok: true, json: async () => ({
    dimension: 'minecraft:overworld', session: 'one', revision: 0, reset: false, tiles: []
  }) });

  await pollTerrainChanges();

  assert.equal(timers.length, 1);
  timers.shift()();
  assert.equal(state.missingTiles.size, 0, 'the retained canvas miss must become requestable again');
});

test('late errors from a removed terrain layer do not poison the next view', () => {
  const { state, configureSquaremapTileLayer, timers } = harness();
  const events = {};
  const layer = { on(name, callback) { events[name] = callback; } };
  configureSquaremapTileLayer(layer);
  state.mountedToken++;
  events.tileerror({ tile: { isConnected: true }, coords: { x: 0, y: 0, z: 0 } });
  assert.equal(state.terrainFailedTiles.size, 0);
  assert.equal(timers.length, 0);
});

test('an image started before an overflow still revalidates when its old request finishes', () => {
  const { state, configureSquaremapTileLayer, applyTerrainChanges, refreshTerrainTiles } = harness();
  const events = {};
  const image = { src: '/tile?v=0-0', isConnected: true };
  const coords = { x: 0, y: 0, z: 0 };
  const layer = { on(name, callback) { events[name] = callback; }, _tiles: { one: { el: image, coords, current: true } } };
  state.leafletLayers = [layer];
  configureSquaremapTileLayer(layer);
  events.tileloadstart({ tile: image, coords });
  applyTerrainChanges({ dimension: 'minecraft:overworld', session: 'new', revision: 100, reset: true, tiles: [] });
  events.tileload({ tile: image, coords });
  refreshTerrainTiles();
  assert.notEqual(image.src, '/tile?v=0-0');
});

test('a response for an old dimension is discarded while a new request can proceed', async () => {
  const { state, pollTerrainChanges, environment } = harness();
  let finish;
  environment.fetch = () => new Promise(resolve => { finish = resolve; });
  const pending = pollTerrainChanges();
  state.terrainDimension = 'minecraft:the_nether';
  finish({ ok: true, json: async () => ({ dimension: 'minecraft:overworld', session: 'old', revision: 2, reset: true, tiles: [] }) });
  await pending;
  assert.equal(state.terrainSession, '');
  assert.equal(state.terrainPollToken, null);
});

test('history overflow touches visible canvas images and defers cached offscreen images until used', () => {
  const { state, applyTerrainChanges, refreshTerrainTiles, loadTile } = harness();
  state.canvas = { width: 512, height: 512 };
  state.center = { x: 256, z: 256 };
  state.zoom = 1;
  const visible = { src: 'visible-old' };
  const hidden = { src: 'hidden-old' };
  state.tileCache.set('0,0,0', visible);
  state.tileCache.set('0,99,99', hidden);
  applyTerrainChanges({ dimension: 'minecraft:overworld', session: 'new', revision: 100, reset: true, tiles: [] });
  refreshTerrainTiles();
  assert.notEqual(visible.src, 'visible-old');
  assert.equal(hidden.src, 'hidden-old');
  state.center = { x: 99 * 512 + 256, z: 99 * 512 + 256 };
  assert.equal(loadTile(0, 99, 99), hidden);
  assert.notEqual(hidden.src, 'hidden-old');
});

test('canvas terrain replaces its cached bitmap when a refreshed image finishes', () => {
  const { state, loadTile } = harness();
  const image = loadTile(0, 0, 0);
  state.staticKey = 'previous-terrain-bitmap';
  image.onload();
  assert.equal(state.staticKey, '');
});
