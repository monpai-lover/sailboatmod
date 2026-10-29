const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

// Run the real map code with deferred network responses. The observer exposes private state and loaders only
// inside this test context; the public unmount path and all response handling run unchanged.
function mapHarness() {
  const filename = path.join(__dirname, "../src/main/resources/marketweb/map.js");
  const source = fs.readFileSync(filename, "utf8");
  const instrumented = source.replace(/\}\)\(\);\s*$/, "window.__mapTest = { state, loadAll, loadShipmentHistory, loadShipments };})();");
  assert.notEqual(instrumented, source, "the test observer must attach to the map closure");
  let sessionToken = "session-A";
  const requests = [];
  const context = {
    window: { matchMedia: () => ({ matches: false }), clearTimeout() {}, clearInterval() {} },
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
  const { state, loadAll, loadShipmentHistory, loadShipments } = context.window.__mapTest;
  const root = { getAttribute: () => "", querySelector: () => null, classList: { contains: () => false } };
  state.root = root;
  state.mountedToken = 1;
  return {
    state,
    root,
    requests,
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

  await assert.rejects(pending, /Current request failed/);
});
