const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const basemap = require("../src/main/resources/marketweb/tellus-basemap.js");

// Run the real map code with deferred network responses. The observer exposes private state and loaders only
// inside this test context; the public unmount path and all response handling run unchanged.
function mapHarness() {
  const filename = path.join(__dirname, "../src/main/resources/marketweb/map.js");
  const source = fs.readFileSync(filename, "utf8");
  const instrumented = source.replace(/\}\)\(\);\s*$/, "window.__mapTest = { state, loadAll, loadShipmentHistory, loadShipments, createBasemapLayer, drawBasemapTile, renderBasemapAttribution, setBasemapSource };})();");
  assert.notEqual(instrumented, source, "the test observer must attach to the map closure");
  let sessionToken = "session-A";
  const requests = [];
  const images = [];
  const context = {
    window: { matchMedia: () => ({ matches: false }), clearTimeout() {}, clearInterval() {}, SailboatTellusBasemap: basemap },
    document: {
      createElement() {
        const classes = new Set();
        return { textContent: "", remove() {}, classList: {
          toggle(name, active) { active ? classes.add(name) : classes.delete(name); },
          contains: (name) => classes.has(name)
        } };
      }
    },
    Image: class {
      set src(url) { this.url = url; images.push(this); }
    },
    localStorage: { getItem: (key) => key === "marketWebSessionToken" ? sessionToken : null },
    Headers,
    performance,
    cancelAnimationFrame() {},
    requestAnimationFrame: () => 1,
    fetch(url, options) {
      return new Promise((resolve) => requests.push({ url, options, resolve }));
    }
  };
  vm.runInNewContext(instrumented, context, { filename });
  const { state, loadAll, loadShipmentHistory, loadShipments, createBasemapLayer, drawBasemapTile, renderBasemapAttribution, setBasemapSource } = context.window.__mapTest;
  const root = { getAttribute: () => "", querySelector: () => null, classList: { contains: () => false } };
  state.root = root;
  state.mountedToken = 1;
  return {
    state,
    root,
    requests,
    images,
    createBasemapLayer,
    drawBasemapTile,
    renderBasemapAttribution,
    setBasemapSource,
    setLeaflet(api) { context.window.L = api; },
    loadAll: () => loadAll(state.mountedToken),
    loadHistory: loadShipmentHistory,
    loadShipments,
    mount: context.window.SailboatMarketMap.mount,
    unmount: context.window.SailboatMarketMap.unmount,
    changeSession(token) { sessionToken = token; },
    respond(index, body, ok = true) { requests[index].resolve({ ok, json: async () => body }); }
  };
}

function ids(entries) {
  return Array.from(entries, (entry) => entry.shippingOrderId);
}

test("missingTerrainTilePlaceholderHasAZeroAlphaPixel", () => {
  const source = fs.readFileSync(path.join(__dirname, "../src/main/resources/marketweb/map.js"), "utf8");
  const match = source.match(/const TRANSPARENT_TILE_URL = "data:image\/gif;base64,([^"]+)";/);
  assert.ok(match, "the missing-tile placeholder remains an inline GIF");
  const gif = Buffer.from(match[1], "base64");
  assert.match(gif.toString("ascii", 0, 6), /^GIF8[79]a$/);
  assert.equal(gif.readUInt16LE(6), 1);
  assert.equal(gif.readUInt16LE(8), 1);
  let offset = 13 + ((gif[10] & 0x80) ? 3 * (1 << ((gif[10] & 7) + 1)) : 0);
  let transparentIndex = -1;
  while (offset < gif.length) {
    const marker = gif[offset++];
    if (marker === 0x21) {
      const label = gif[offset++];
      if (label === 0xf9 && (gif[offset + 1] & 1)) {
        transparentIndex = gif[offset + 4];
      }
      while (gif[offset]) offset += gif[offset] + 1;
      offset += 1;
    } else if (marker === 0x2c) {
      const packed = gif[offset + 8];
      offset += 9 + ((packed & 0x80) ? 3 * (1 << ((packed & 7) + 1)) : 0);
      const minimumCodeSize = gif[offset++];
      const blocks = [];
      while (gif[offset]) {
        const size = gif[offset++];
        blocks.push(gif.subarray(offset, offset + size));
        offset += size;
      }
      const compressed = Buffer.concat(blocks);
      const codeSize = minimumCodeSize + 1;
      let bit = 0;
      const readCode = () => {
        let code = 0;
        for (let i = 0; i < codeSize; i++, bit++) code |= ((compressed[bit >> 3] >> (bit & 7)) & 1) << i;
        return code;
      };
      // For a one-pixel GIF the first literal after CLEAR is the actual pixel's palette index.
      assert.equal(readCode(), 1 << minimumCodeSize);
      const pixelIndex = readCode();
      assert.ok(pixelIndex < (1 << minimumCodeSize));
      assert.equal(pixelIndex === transparentIndex ? 0 : 255, 0, "missing terrain must reveal the basemap beneath it");
      return;
    } else {
      assert.fail("the placeholder must contain an image pixel");
    }
  }
  assert.fail("the placeholder must contain an image pixel");
});

test("unmountClearsPrivateLiveAndHistoryData", () => {
  const map = mapHarness();
  map.state.shipments = [{ shippingOrderId: "private-live-A" }];
  map.state.shipmentHistory = [{ shippingOrderId: "private-history-A" }];
  map.state.shipmentView = "history";
  map.state.historyFetchedAt = 100;
  map.state.serverGameTime = 300;
  map.state.serverGameTimeAt = 200;
  map.state.followShipmentId = "private-live-A";
  map.state.selected = { type: "shipment", data: map.state.shipments[0] };
  map.state.hover = { type: "history", data: map.state.shipmentHistory[0] };
  map.state.logisticsEffects = new Map([["private-live-A", { particles: [{ x: 12, y: 34 }] }]]);

  map.unmount();

  assert.deepEqual(ids(map.state.shipments), []);
  assert.deepEqual(ids(map.state.shipmentHistory), []);
  assert.equal(map.state.shipmentView, "live");
  assert.equal(map.state.historyFetchedAt, 0);
  assert.equal(map.state.serverGameTime, 0);
  assert.equal(map.state.serverGameTimeAt, 0);
  assert.equal(map.state.followShipmentId, "");
  assert.equal(map.state.selected, null);
  assert.equal(map.state.hover, null);
  assert.equal(map.state.logisticsEffects, null);
});

function basemapHarness() {
  const map = mapHarness();
  map.state.world = { tellus: true, worldScale: 100 };
  map.state.mapElement = { clientWidth: 512, clientHeight: 512, parentElement: { appendChild() {} } };
  map.state.basemapLayer = { redraw() {} };
  map.state.center = { x: 28110, z: -48635 };
  map.state.zoom = 1;
  map.state.locale = "zh-CN";
  map.renderBasemapAttribution();
  return {
    ...map,
    draw(coords) {
      const draws = [];
      const canvas = { width: 512, height: 512, dataset: {}, getContext: () => ({ drawImage: (...args) => draws.push(args) }) };
      return { pending: map.drawBasemapTile(canvas, coords), canvas, draws };
    },
    settleImages(success) {
      for (const image of map.images.splice(0)) {
        (success ? image.onload : image.onerror)();
      }
    },
    notice: () => map.state.basemapAttribution.textContent,
    warning: () => map.state.basemapAttribution.classList.contains("is-warning")
  };
}

test("invalidTellusViewCenterShowsCoordinateDiagnosticInChineseAndEnglish", () => {
  const map = basemapHarness();
  map.state.center = { x: 33431923, z: -1978998 };
  map.renderBasemapAttribution();
  assert.match(map.notice(), /地图坐标超出地球投影范围.*服务端坐标编码.*世界比例/);
  assert.equal(map.warning(), true);
  map.state.locale = "en-US";
  map.renderBasemapAttribution();
  assert.match(map.notice(), /coordinates.*outside.*projection.*server coordinate encoding.*world scale/i);
  map.state.center.z = -48635;
  map.renderBasemapAttribution();
  assert.equal(map.warning(), false, "the coordinate warning clears when the view returns inside the projection");
});

test("emptyTileBeyondEarthEdgeDoesNotWarnForAValidViewCenter", async () => {
  const map = basemapHarness();
  map.state.center = { x: 256, z: -basemap.earthHalfWidthBlocks(100) + 64 };
  const tile = map.draw({ x: 0, y: -393, z: 0 });
  await tile.pending;
  assert.equal(map.images.length, 0, "a tile beyond the north edge has no source images");
  assert.equal(map.warning(), false);
});

test("realSavedMarketRequestsOnlyCoveredGaodeZooms", async () => {
  const map = basemapHarness();
  map.state.basemapSourceId = "gaode";
  const tile = map.draw({ x: 54, y: -95, z: 0 });
  const zooms = map.images.map((image) => Number(new URL(image.url).searchParams.get("z")));
  map.settleImages(true);
  await tile.pending;
  assert.ok(zooms.length > 0);
  assert.ok(zooms.every((zoom) => zoom >= 1 && zoom <= 9), `requested Gaode zooms ${zooms}`);
  const area = tile.draws.reduce((sum, args) => sum + args[7] * args[8], 0);
  assert.ok(Math.abs(area - 512 * 512) < 1e-6, `drawn area ${area}`);
});

test("basemapCanvasExposesTheProviderZoomAndCompletedDrawCountForBrowserDiagnostics", async () => {
  const map = basemapHarness();
  map.state.basemapSourceId = "gaode";
  const tile = map.draw({ x: 54, y: -95, z: 0 });
  assert.equal(tile.canvas.dataset.basemapSource, "gaode");
  assert.equal(tile.canvas.dataset.basemapZoom, "9");
  assert.ok(Number(tile.canvas.dataset.basemapRequested) > 0);
  assert.equal(tile.canvas.dataset.basemapDrawn, "0", "pending images have not drawn yet");
  map.settleImages(true);
  await tile.pending;
  assert.equal(tile.canvas.dataset.basemapDrawn, String(tile.draws.length));
  assert.equal(tile.canvas.dataset.basemapDrawn, tile.canvas.dataset.basemapRequested);
});

test("allVisibleBasemapImagesFailingWarnsEvenWithFewerThanEightRequests", async () => {
  const map = basemapHarness();
  map.state.center = { x: 0, z: 0 };
  map.state.zoom = 1 / 2048;
  const tile = map.draw({ x: 0, y: -1, z: -11 });
  assert.ok(map.images.length > 0 && map.images.length < 8, "the global view needs fewer than eight source images");
  map.settleImages(false);
  await tile.pending;
  assert.match(map.notice(), /底图来源无法连线/);
  assert.equal(map.warning(), true);
});

test("offscreenBasemapSuccessCannotHideCurrentViewFailureAndRecoveryClearsIt", async () => {
  const map = basemapHarness();
  const first = map.draw({ x: 54, y: -95, z: 0 });
  map.settleImages(true);
  await first.pending;
  assert.ok(first.draws.length > 0, "the real scale-100 market draws its basemap");
  map.state.center.x = 35610;
  const failed = map.draw({ x: 69, y: -95, z: 0 });
  map.settleImages(false);
  await failed.pending;
  assert.match(map.notice(), /底图来源无法连线/);
  const recovered = map.draw({ x: 69, y: -95, z: 0 });
  map.settleImages(true);
  await recovered.pending;
  assert.ok(recovered.draws.length > 0);
  assert.equal(map.warning(), false);
});

test("aVisibleSuccessfulBasemapTilePreventsAConnectionWarningForOtherFailedTiles", async () => {
  const map = basemapHarness();
  map.state.center.x = 28160;
  map.state.mapElement.clientWidth = 1024;
  const first = map.draw({ x: 54, y: -95, z: 0 });
  map.settleImages(true);
  await first.pending;
  map.state.basemapImages = new Map();
  const second = map.draw({ x: 55, y: -95, z: 0 });
  map.settleImages(false);
  await second.pending;
  assert.ok(first.draws.length > 0);
  assert.equal(second.draws.length, 0);
  assert.equal(map.warning(), false, "part of the current viewport has a working basemap");
});

test("previousSourceFailuresCannotRestoreAWarningAfterSwitchingBasemaps", async () => {
  const map = basemapHarness();
  const previous = map.draw({ x: 54, y: -95, z: 0 });
  map.setBasemapSource("gaode");
  map.settleImages(false);
  await previous.pending;
  assert.equal(map.warning(), false, "a replaced source cannot report failure for the active source");
});

test("unloadingBasemapTilesDuringUnmountCannotRecreateTheRemovedAttribution", () => {
  const map = basemapHarness();
  map.setLeaflet({ GridLayer: { extend: () => class {
    constructor() { this.handlers = {}; }
    on(event, handler) { this.handlers[event] = handler; }
  } } });
  const layer = map.createBasemapLayer();
  map.state.basemapLayer = layer;
  map.state.leafletMap = { remove: () => layer.handlers.tileunload({ tile: {} }) };

  map.unmount();

  assert.equal(map.state.basemapAttribution, null);
});

test("historyResponseCannotRestoreDataAfterUnmount", async () => {
  const map = mapHarness();
  const pending = map.loadHistory();
  map.unmount();
  map.respond(0, { history: [{ shippingOrderId: "private-A" }], gameTime: 100 });

  await pending;

  assert.deepEqual(ids(map.state.shipmentHistory), []);
  assert.equal(map.state.serverGameTime, 0);
});

test("oldSessionHistoryCannotOverwriteTheNewSessionsHistory", async () => {
  const map = mapHarness();
  const oldRequest = map.loadHistory();
  assert.equal(map.requests[0].options.headers.get("Authorization"), "Bearer session-A");
  map.changeSession("session-B");
  const currentRequest = map.loadHistory();
  assert.equal(map.requests[1].options.headers.get("Authorization"), "Bearer session-B");
  map.respond(1, { history: [{ shippingOrderId: "private-B" }], gameTime: 200 });
  await currentRequest;
  map.respond(0, { history: [{ shippingOrderId: "private-A" }], gameTime: 100 });

  await oldRequest;

  assert.deepEqual(ids(map.state.shipmentHistory), ["private-B"]);
  assert.equal(map.state.serverGameTime, 200);
});

test("oldSessionLivePollCannotOverwriteTheNewSessionsShipments", async () => {
  const map = mapHarness();
  const oldRequest = map.loadShipments();
  map.changeSession("session-B");
  const currentRequest = map.loadShipments();
  map.respond(1, { shipments: [{ shippingOrderId: "private-live-B" }], gameTime: 200 });
  await currentRequest;
  map.respond(0, { shipments: [{ shippingOrderId: "private-live-A" }], gameTime: 100 });

  await oldRequest;

  assert.deepEqual(ids(map.state.shipments), ["private-live-B"]);
  assert.equal(map.state.serverGameTime, 200);
});

test("historyForTheCurrentSessionStillLoads", async () => {
  const map = mapHarness();
  const pending = map.loadHistory();
  map.respond(0, { history: [{ shippingOrderId: "private-A" }], gameTime: 100 });

  await pending;

  assert.deepEqual(ids(map.state.shipmentHistory), ["private-A"]);
  assert.equal(map.state.serverGameTime, 100);
});

test("remountingTheSameRootForANewSessionClearsPrivateData", () => {
  const map = mapHarness();
  map.mount(map.root);
  map.state.shipments = [{ shippingOrderId: "private-live-A" }];
  map.state.shipmentHistory = [{ shippingOrderId: "private-history-A" }];
  map.state.shipmentView = "history";
  map.changeSession("session-B");

  map.mount(map.root);

  assert.deepEqual(ids(map.state.shipments), []);
  assert.deepEqual(ids(map.state.shipmentHistory), []);
  assert.equal(map.state.shipmentView, "live");
});

test("initialLoadFromAnOldSessionCannotReplaceCurrentPrivateData", async () => {
  const map = mapHarness();
  const pending = map.loadAll();
  map.changeSession("session-B");
  map.state.shipments = [{ shippingOrderId: "private-live-B" }];
  map.respond(0, {});
  map.respond(1, { markets: [] });
  map.respond(2, { territories: [] });
  map.respond(3, { shipments: [{ shippingOrderId: "private-live-A" }] });
  map.respond(4, { tellus: false });
  map.respond(5, { airports: [] });

  await pending;

  assert.deepEqual(ids(map.state.shipments), ["private-live-B"]);
});

test("failedHistoryFetchForANewSessionCannotReuseThePreviousSessionsCache", async () => {
  const map = mapHarness();
  const first = map.loadHistory();
  map.respond(0, { history: [{ shippingOrderId: "private-A" }] });
  await first;
  map.changeSession("session-B");
  const second = map.loadHistory();
  map.respond(1, { message: "Unauthorized" }, false);

  await second;

  assert.deepEqual(ids(map.state.shipmentHistory), []);
});

test("livePollCannotRestoreDataAfterUnmount", async () => {
  const map = mapHarness();
  const pending = map.loadShipments();
  map.unmount();
  map.respond(0, { shipments: [{ shippingOrderId: "private-live-A" }], gameTime: 100 });

  await pending;

  assert.deepEqual(ids(map.state.shipments), []);
  assert.equal(map.state.serverGameTime, 0);
});

test("initialLoadForTheCurrentSessionStillLoads", async () => {
  const map = mapHarness();
  const pending = map.loadAll();
  map.respond(0, {});
  map.respond(1, { markets: [] });
  map.respond(2, { territories: [] });
  map.respond(3, { shipments: [{ shippingOrderId: "private-live-A" }] });
  map.respond(4, { tellus: false });
  map.respond(5, { airports: [] });

  await pending;

  assert.deepEqual(ids(map.state.shipments), ["private-live-A"]);
});

test("initialLoadFailureFromAnOldSessionCannotSurfaceInTheNewSession", async () => {
  const map = mapHarness();
  const pending = map.loadAll();
  map.changeSession("session-B");
  map.respond(0, { message: "Old session expired" }, false);
  map.respond(1, { markets: [] });
  map.respond(2, { territories: [] });
  map.respond(3, { shipments: [] });
  map.respond(4, { tellus: false });
  map.respond(5, { airports: [] });

  await assert.doesNotReject(pending);
});

test("initialLoadFailureForTheCurrentSessionIsStillReported", async () => {
  const map = mapHarness();
  const pending = map.loadAll();
  map.respond(0, { message: "Current request failed" }, false);
  map.respond(1, { markets: [] });
  map.respond(2, { territories: [] });
  map.respond(3, { shipments: [] });
  map.respond(4, { tellus: false });
  map.respond(5, { airports: [] });

  await assert.rejects(pending, /Current request failed/);
});
