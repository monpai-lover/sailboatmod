const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const test = require('node:test');

function harness() {
  const source = fs.readFileSync('src/main/resources/marketweb/map.js', 'utf8');
  const timers = [];
  const intervals = new Map();
  const listeners = new Map();
  let nextInterval = 1;
  let clock = 0;
  const environment = {
    window: { innerWidth: 1400, matchMedia: () => ({ matches: false }),
      setTimeout(fn) { timers.push(fn); return timers.length; }, clearTimeout() {},
      setInterval(fn, delay) { const id = nextInterval++; intervals.set(id, { fn, delay, next: clock + delay }); return id; },
      clearInterval(id) { intervals.delete(id); } },
    document: { hidden: false, visibilityState: 'visible',
      addEventListener(name, listener) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(listener); },
      removeEventListener(name, listener) { listeners.get(name)?.delete(listener); } },
    localStorage: { getItem() { return ''; } }, navigator: {}, performance,
    requestAnimationFrame: () => 1, cancelAnimationFrame() {}, Headers,
    Image: class { set src(value) { this.url = value; } get src() { return this.url; } }
  };
  vm.runInNewContext(source.replace(/\}\)\(\);\s*$/, 'window.testApi = { state, tileRefreshKey, configureSquaremapTileLayer, loadTile, applyTerrainChanges: typeof applyTerrainChanges === "function" ? applyTerrainChanges : null, refreshTerrainTiles: typeof refreshTerrainTiles === "function" ? refreshTerrainTiles : null, captureRequestContext, pollTerrainChanges: typeof pollTerrainChanges === "function" ? pollTerrainChanges : null, startTerrainPolling: typeof startTerrainPolling === "function" ? startTerrainPolling : null, stopTerrainPolling: typeof stopTerrainPolling === "function" ? stopTerrainPolling : null, unmount };})();'), environment);
  const api = environment.window.testApi;
  api.state.root = {};
  return { ...api, timers, intervals, listeners, environment,
    advance(milliseconds) {
      const end = clock + milliseconds;
      while (true) {
        const next = Math.min(...Array.from(intervals.values(), entry => entry.next));
        if (next > end) break;
        clock = next;
        for (const entry of intervals.values()) {
          if (entry.next <= clock) { entry.next += entry.delay; entry.fn(); }
        }
      }
      clock = end;
    },
    visibility(visible) {
      environment.document.hidden = !visible;
      environment.document.visibilityState = visible ? 'visible' : 'hidden';
      for (const listener of listeners.get('visibilitychange') || []) listener();
    },
    settle: () => new Promise(setImmediate)
  };
}

function terrainResponse(revision = 0, tiles = []) {
  return { ok: true, json: async () => ({ dimension: 'minecraft:overworld', session: 'one', revision, reset: false, tiles }) };
}

test('terrain feed starts immediately and polls every two seconds independently of metadata', async () => {
  const { startTerrainPolling, stopTerrainPolling, environment, intervals, advance, settle } = harness();
  assert.equal(typeof startTerrainPolling, 'function');
  const urls = [];
  environment.fetch = async url => { urls.push(url); return terrainResponse(); };
  startTerrainPolling();
  assert.equal(urls.length, 1, 'opening the map must not wait for metadata');
  await settle();
  advance(1999);
  assert.equal(urls.length, 1);
  advance(1);
  await settle();
  assert.equal(urls.length, 2);
  assert.equal(intervals.size, 1);
  assert.equal(Array.from(intervals.values())[0].delay, 2000);
  assert.ok(urls.every(url => url.includes('/api/map/changes?')), 'the fast loop only requests the bounded terrain feed');
  stopTerrainPolling();
});

test('hidden maps do no terrain polling and becoming visible polls immediately', async () => {
  const { state, startTerrainPolling, stopTerrainPolling, environment, advance, visibility, settle } = harness();
  assert.equal(typeof startTerrainPolling, 'function');
  const urls = [];
  environment.fetch = async url => { urls.push(url); return terrainResponse(); };
  visibility(false);
  startTerrainPolling();
  advance(10000);
  assert.equal(urls.length, 0);
  visibility(true);
  assert.equal(urls.length, 1);
  await settle();
  advance(2000);
  await settle();
  assert.equal(urls.length, 2);
  visibility(false);
  advance(10000);
  assert.equal(urls.length, 2);
  visibility(true);
  await settle();
  assert.equal(urls.length, 3);
  state.root = null;
  advance(2000);
  assert.equal(urls.length, 3, 'detached maps cannot continue the fast feed');
  stopTerrainPolling();
});

test('the fast feed keeps one request in flight and rejects stale login and unmounted responses', async () => {
  const { state, startTerrainPolling, unmount, environment, intervals, listeners, advance, visibility, settle } = harness();
  assert.equal(typeof startTerrainPolling, 'function');
  const requests = [];
  environment.fetch = (url, options) => new Promise(resolve => requests.push({ url, options, resolve }));
  startTerrainPolling();
  advance(10000);
  visibility(true);
  assert.equal(requests.length, 1, 'timer and visibility events must share the same in-flight owner');
  environment.localStorage.getItem = () => 'new-login';
  requests[0].resolve(terrainResponse(99));
  await settle();
  assert.equal(state.terrainSession, '', 'the old login response must be discarded');
  advance(2000);
  assert.equal(requests.length, 2);
  assert.match(requests[1].options.headers.get('Authorization'), /new-login/);
  const lateTick = Array.from(intervals.values())[0].fn;
  const lateVisibility = Array.from(listeners.get('visibilitychange'))[0];
  unmount();
  assert.equal(intervals.size, 0);
  assert.equal(listeners.get('visibilitychange')?.size || 0, 0);
  requests[1].resolve(terrainResponse(100));
  await settle();
  assert.equal(state.terrainSession, '');
  lateTick();
  lateVisibility();
  visibility(true);
  advance(10000);
  assert.equal(requests.length, 2);
});

test('a terrain response arriving while hidden is deferred until the map becomes visible again', async () => {
  const { state, startTerrainPolling, stopTerrainPolling, environment, visibility, settle } = harness();
  const requests = [];
  environment.fetch = () => new Promise(resolve => requests.push(resolve));
  startTerrainPolling();
  visibility(false);
  requests[0](terrainResponse(5));
  await settle();
  assert.equal(state.terrainRevision, 0);
  visibility(true);
  assert.equal(requests.length, 2);
  requests[1](terrainResponse(6));
  await settle();
  assert.equal(state.terrainRevision, 6);
  stopTerrainPolling();
});

test('restarting the poller leaves one timer and listener and invalidates already queued callbacks', async () => {
  const { startTerrainPolling, stopTerrainPolling, environment, intervals, listeners, settle } = harness();
  let requests = 0;
  environment.fetch = async () => { requests++; return terrainResponse(); };
  startTerrainPolling();
  await settle();
  const lateTick = Array.from(intervals.values())[0].fn;
  const lateVisibility = Array.from(listeners.get('visibilitychange'))[0];
  startTerrainPolling();
  await settle();
  assert.equal(requests, 2);
  assert.equal(intervals.size, 1);
  assert.equal(listeners.get('visibilitychange').size, 1);
  lateTick();
  lateVisibility();
  assert.equal(requests, 2);
  stopTerrainPolling();
});

test('a terrain feed change refreshes a failed visible tile within two seconds without the ten-second retry', async () => {
  const { state, configureSquaremapTileLayer, startTerrainPolling, stopTerrainPolling, environment, advance, settle } = harness();
  assert.equal(typeof startTerrainPolling, 'function');
  state.snapshot = { renderVersion: 6 };
  state.terrainSession = 'one';
  const events = {};
  const changed = { src: 'failed-before-feed', isConnected: true, __terrainVersion: '6-0' };
  const other = { src: 'unchanged', isConnected: true, __terrainVersion: '6-0' };
  const layer = { on(name, callback) { events[name] = callback; }, _tiles: {
    changed: { el: changed, coords: { x: 1, y: 2, z: 0 }, current: true },
    other: { el: other, coords: { x: 2, y: 2, z: 0 }, current: true }
  } };
  state.leafletLayers = [layer];
  configureSquaremapTileLayer(layer);
  events.tileerror({ tile: changed, coords: { x: 1, y: 2, z: 0 } });
  let polls = 0;
  environment.fetch = async () => ++polls === 1 ? terrainResponse() : terrainResponse(1,
    [{ kind: 'square', zoom: 0, x: 1, z: 2, revision: 1 }]);
  startTerrainPolling();
  await settle();
  assert.equal(changed.src, 'failed-before-feed');
  advance(2000);
  await settle();
  assert.match(changed.src, /\/0\/1_2\.png\?v=6-1$/);
  assert.equal(other.src, 'unchanged');
  stopTerrainPolling();
});

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

test('a pending missing Leaflet retry stays dormant while hidden and resumes after a visible feed poll', async () => {
  const { state, configureSquaremapTileLayer, pollTerrainChanges, timers, environment, visibility } = harness();
  state.terrainSession = 'one';
  const events = {};
  const layer = { on(name, callback) { events[name] = callback; } };
  configureSquaremapTileLayer(layer);
  const image = { src: 'missing-before-hide', isConnected: true };
  events.tileerror({ tile: image, coords: { x: 1, y: 2, z: 0 } });
  visibility(false);
  timers.shift()();
  assert.equal(image.src, 'missing-before-hide', 'the old retry cannot start an image request while hidden');
  assert.equal(state.terrainFailedTiles.size, 1);
  assert.equal(state.leafletRetryTimer, 0);
  assert.equal(timers.length, 0, 'hidden retries retain work without continually rescheduling');
  visibility(true);
  environment.fetch = async () => terrainResponse();
  await pollTerrainChanges();
  assert.equal(timers.length, 1);
  timers.shift()();
  assert.match(image.src, /\/0\/1_2\.png\?v=.*&retry=/);
  assert.equal(state.terrainFailedTiles.size, 0);
});

test('a pending canvas miss is retained while hidden and becomes requestable again when visible', async () => {
  const { state, loadTile, pollTerrainChanges, timers, environment, visibility } = harness();
  state.terrainSession = 'one';
  loadTile(0, 1, 2).onerror();
  state.missingTiles.set('0,1,2', 0);
  visibility(false);
  timers.shift()();
  assert.equal(state.missingTiles.size, 1, 'the hidden callback cannot consume retained canvas retry work');
  assert.equal(timers.length, 0);
  visibility(true);
  environment.fetch = async () => terrainResponse();
  await pollTerrainChanges();
  timers.shift()();
  assert.equal(state.missingTiles.size, 0);
});

test('hidden terrain refresh batches retain their remaining images and a visible poll finishes them', async () => {
  const { state, applyTerrainChanges, refreshTerrainTiles, pollTerrainChanges, timers, environment, visibility } = harness();
  const entries = Object.fromEntries(Array.from({ length: 90 }, (_, x) => [x, {
    coords: { x, y: 0, z: 0 }, current: true, el: { src: 'old', isConnected: true }
  }]));
  state.leafletLayers = [{ _tiles: entries }];
  applyTerrainChanges({ dimension: 'minecraft:overworld', session: 'one', revision: 200, reset: true, tiles: [] });
  refreshTerrainTiles();
  assert.equal(Object.values(entries).filter(entry => entry.el.src !== 'old').length, 64);
  assert.equal(timers.length, 1);
  visibility(false);
  refreshTerrainTiles();
  timers.shift()();
  assert.equal(Object.values(entries).filter(entry => entry.el.src !== 'old').length, 64,
    'neither direct refresh nor its already queued batch may issue hidden image requests');
  assert.equal(state.terrainRefreshTimer, 0);
  assert.equal(timers.length, 0);
  visibility(true);
  environment.fetch = async () => terrainResponse(200);
  await pollTerrainChanges();
  assert.equal(Object.values(entries).filter(entry => entry.el.src !== 'old').length, 90);
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
