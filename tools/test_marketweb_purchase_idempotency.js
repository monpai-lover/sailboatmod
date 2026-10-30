const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../src/main/resources/marketweb/app.js"), "utf8").replace(/\r\n/g, "\n");
const keyStart = source.indexOf("function newIdempotencyKey(");
const keyEnd = source.indexOf("\nasync function loadSettings(", keyStart);
const dialogStart = source.indexOf("const PURCHASE_MODES =");
const dialogEnd = source.indexOf("\n// ---- 交互", dialogStart);
assert.ok(keyStart >= 0 && keyEnd > keyStart && dialogStart >= 0 && dialogEnd > dialogStart);

class Node {
  constructor(value = "") { this.value = value; this.listeners = new Map(); this.classList = { toggle() {} }; }
  addEventListener(name, callback) {
    if (!this.listeners.has(name)) this.listeners.set(name, []);
    this.listeners.get(name).push(callback);
  }
  async fire(name) {
    await Promise.all((this.listeners.get(name) || []).map(callback => callback({ preventDefault() {} })));
  }
  setAttribute() {}
  closest() { return null; }
  focus() {}
  select() {}
}

// Execute the application's real input/probe/submit callbacks. The API double models the
// server's (endpoint, key) response cache, including cached business failures and lost replies.
async function purchaseDialog({ aggregate = false, execute = async () => ({ status: 200, data: {} }) } = {}) {
  const nodes = new Map(["form", "[data-qty]", "[data-qty-hint]", "[data-total]", "[data-after]", "[data-error]",
    "[data-confirm]", "[data-receiving-wrap]", "[data-receiving-none]", "[data-receiving]", "[data-dialog-cancel]"]
    .map(selector => [selector, new Node()]));
  nodes.get("[data-qty]").value = "1";
  nodes.get("[data-receiving]").value = aggregate ? "" : "warehouse-a";
  const radios = aggregate ? [] : ["SELLER_SHIP", "AUTO_PICKUP", "REAL_PICKUP"].map(value => new Node(value));
  const dialog = Object.assign(new Node(), {
    open: false,
    querySelector: selector => nodes.get(selector) || null,
    querySelectorAll: selector => selector === "[data-mode]" ? radios
      : selector === "[data-dialog-cancel]" ? [nodes.get(selector)] : [],
    close() { this.open = false; }
  });
  const overview = { walletBalance: 1000, canChooseReceiving: true,
    receivingWarehouseOptions: [{ pos: "warehouse-a", label: "A" }, { pos: "warehouse-b", label: "B" }] };
  const state = { session: {}, selectedMarketId: "market-a", settings: {}, detail: overview, aggregatedDetail: overview };
  const requests = [], executions = [], cached = new Map();
  let nextKey = 0;
  const context = vm.createContext({
    state, dialogSequence: 0, renderGuard: {},
    window: { crypto: { randomUUID: () => `test-key-${++nextKey}` } },
    t: key => key, number: String, escapeHtml: value => String(value ?? ""),
    clampNumber: (value, min, max) => Math.max(min, Math.min(max, value)),
    isInfiniteStock: () => false, renderCommodityIcon: () => "", createMarketDialog: () => dialog,
    showMarketDialog: () => { dialog.open = true; }, hydrateCommodityIcons() {}, setStatus() {},
    syncCommoditySelection() {}, syncRouteUrl() {}, renderDetail() {},
    async loadAggregatedListings() {}, async loadAggregatedDetail() {}, async loadMarketDetail() {},
    async api(endpoint, options) {
      if (endpoint.endsWith("/probe-modes")) return { sellerShip: true, autoPickup: true, realPickup: true };
      const request = { endpoint, body: JSON.parse(options.body), key: options.idempotencyKey };
      requests.push(request);
      const cacheKey = `${endpoint}\n${request.key}`;
      let response = cached.get(cacheKey);
      if (!response) {
        executions.push(request);
        response = await execute(request);
        if (response.status < 500) cached.set(cacheKey, response);
        if (response.loseReply) throw new TypeError("Network response lost");
      }
      if (response.status >= 400) {
        const error = new Error(response.message || "Business failure");
        error.status = response.status;
        throw error;
      }
      return response.data || {};
    }
  });
  vm.runInContext(`${source.slice(keyStart, keyEnd)}\n${source.slice(dialogStart, dialogEnd)}\n`
    + `openPurchaseDialog({ aggregate: ${aggregate}, listing: { index: 3, listingId: "listing-a", unitPrice: 10, availableCount: 50, itemName: "Iron" } });`, context);
  const flush = () => new Promise(resolve => setImmediate(resolve));
  await flush();
  return {
    requests, executions, dialog, state,
    submit: () => nodes.get("form").fire("submit"),
    async quantity(value) { nodes.get("[data-qty]").value = String(value); await nodes.get("[data-qty]").fire("input"); },
    async fulfillment(value) {
      for (const radio of radios) radio.checked = radio.value === value;
      await radios.find(radio => radio.value === value).fire("change");
    },
    async warehouse(value) {
      nodes.get("[data-receiving]").value = value;
      await nodes.get("[data-receiving]").fire("change");
      await flush();
    }
  };
}

for (const aggregate of [false, true]) {
  const kind = aggregate ? "aggregate" : "terminal";
  for (const change of ["quantity", "warehouse", ...(aggregate ? [] : ["fulfillment"])]) {
    test(`${kind} purchase can correct ${change} after a cached 400 without reopening`, async () => {
      const ui = await purchaseDialog({ aggregate, execute: async () =>
        ({ status: ui.executions.length === 1 ? 400 : 200 }) });
      await ui.submit();
      assert.equal(ui.dialog.open, true);
      await ui[change](change === "quantity" ? 2 : change === "warehouse" ? "warehouse-b" : "REAL_PICKUP");
      await ui.submit();
      assert.equal(ui.executions.length, 2, "corrected body must reach the purchase service");
      assert.notEqual(ui.requests[0].key, ui.requests[1].key);
      assert.equal(ui.dialog.open, false);
    });
  }
  test(`${kind} identical retry after a lost success response executes only once`, async () => {
    const ui = await purchaseDialog({ aggregate, execute: async () => ({ status: 200, loseReply: true }) });
    await ui.submit();
    assert.equal(ui.dialog.open, true);
    await ui.quantity("01"); // Same numeric payload, despite a textual input edit.
    await ui.submit();
    assert.equal(ui.executions.length, 1);
    assert.equal(ui.requests[0].key, ui.requests[1].key);
    assert.equal(ui.dialog.open, false);
  });
}

test("returning to a previously uncertain payload reuses its original key", async () => {
  const ui = await purchaseDialog({ execute: async request => request.body.quantity === 1
    ? { status: 200, loseReply: true } : { status: 400 } });
  await ui.submit();
  await ui.quantity(2);
  await ui.submit();
  assert.equal(ui.dialog.open, true);
  await ui.quantity(1);
  await ui.submit();
  assert.equal(ui.executions.length, 2);
  assert.equal(ui.requests[0].key, ui.requests[2].key);
  assert.notEqual(ui.requests[0].key, ui.requests[1].key);
});

test("an in-flight purchase still suppresses a second submit", async () => {
  let finish;
  const ui = await purchaseDialog({ execute: () => new Promise(resolve => { finish = resolve; }) });
  const first = ui.submit();
  await ui.submit();
  assert.equal(ui.requests.length, 1);
  finish({ status: 200 });
  await first;
  assert.equal(ui.executions.length, 1);
});
