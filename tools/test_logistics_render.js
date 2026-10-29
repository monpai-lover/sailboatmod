const assert = require("node:assert/strict");
const path = require("node:path");
const test = require("node:test");

const render = require(path.join(__dirname, "../src/main/resources/marketweb/logistics-render.js"));
const panel = require(path.join(__dirname, "../src/main/resources/marketweb/logistics-panel.js"));

const LABELS = { statusStuck: "Stuck", statusMoving: "In transit", statusArrived: "Arrived", progress: "Progress", eta: "ETA",
  speed: "Speed", status: "Status", cargo: "Cargo", follow: "Follow vehicle", unfollow: "Stop following" };
const ctx = (extra) => Object.assign({ label: (key) => LABELS[key] || key, modeLabel: (labels) => labels.en }, extra);
const boat = {
  shippingOrderId: "o1", transportMode: "PORT", status: "SAILING", sourceTownName: "Tema", targetTownName: "Lomé",
  sourceName: "Tema Harbour", targetName: "Lomé Dock", vehicleName: "Aurora", ownerName: "Ada",
  progressRatio: 0.654, etaSeconds: 125, currentSpeed: 0.4,
  cargo: [{ name: "Wheat", quantity: 64, recipient: "Lomé Market" }, { name: "Salt", quantity: 32 }]
};

test("cardShowsRouteProgressEtaAndCargo", () => {
  const html = panel.cardHtml(boat, ctx({ selected: false }));
  assert.match(html, /data-shipment-id="o1"/);
  assert.match(html, /data-kind="sailboat"/);
  assert.match(html, /Tema → Lomé/);
  assert.match(html, />65%</);
  assert.match(html, /2m 5s/);
  assert.match(html, /64× Wheat \+1/);
  assert.doesNotMatch(html, /aria-current/);
  assert.match(panel.cardHtml(boat, ctx({ selected: true })), /aria-current="true"/);
});

test("cardEscapesPlayerTextAndFlagsStuckVehicles", () => {
  const html = panel.cardHtml(Object.assign({}, boat, { status: "STUCK", sourceTownName: "<img src=x onerror=alert(1)>" }), ctx({}));
  assert.doesNotMatch(html, /<img/);
  assert.match(html, /&lt;img/);
  assert.match(html, /data-stuck/);
  assert.match(html, /<b>Stuck<\/b>/);
});

test("detailShowsStatsCargoAndFollowState", () => {
  const html = panel.detailHtml(boat, ctx({ following: false }));
  assert.match(html, /Aurora · Ada/);
  assert.match(html, /Tema Harbour/);
  assert.match(html, /8\.0 m\/s/);
  assert.match(html, /In transit/);
  assert.match(html, /<span>32×<\/span> Salt/);
  assert.match(html, /→ Lomé Market/);
  assert.match(html, /aria-pressed="false">Follow vehicle</);
  assert.match(panel.detailHtml(boat, ctx({ following: true })), /aria-pressed="true">Stop following</);
  const still = panel.detailHtml(Object.assign({}, boat, { etaSeconds: 0, currentSpeed: 0, cargo: [], status: "WAITING" }), ctx({}));
  assert.match(still, /<dd>-<\/dd>/);
  assert.match(still, />WAITING</);
});

const TIMELINE_LABELS = Object.assign({}, LABELS, { timeline: "Timeline", eventDeparted: "Departed", eventStuck: "Stuck",
  eventResumed: "Back on the way", eventDelivered: "Delivered", eventArrived: "Arrived", eventFailed: "Failed",
  eventClaimed: "Collected", eventEnded: "Ended", justNow: "just now", minutesAgo: "{n} min ago", hoursAgo: "{n} h ago",
  daysAgo: "{n} d ago", ended: "Ended" });
const tctx = (extra) => Object.assign({ label: (key) => TIMELINE_LABELS[key] || key, modeLabel: (labels) => labels.en }, extra);

test("timelineNamesEventsAndTellsHowLongAgo", () => {
  const events = [
    { kind: "DEPARTED", gameTime: 0, x: 0, z: 0 },
    { kind: "STUCK", gameTime: 1200, x: 40, z: 8 },
    { kind: "SAILING", gameTime: 2400, x: 41, z: 8 },
    { kind: "DELIVERED", gameTime: 72000 + 2400, x: 99, z: 0 }
  ];
  const html = panel.timelineHtml(events, 72000 + 2400 + 20 * 30, tctx({}));
  assert.match(html, /Departed/);
  assert.match(html, /Stuck/);
  assert.match(html, /Back on the way/, "moving again after being stuck reads as resumed");
  assert.match(html, /Delivered/);
  assert.match(html, /just now/);
  assert.match(html, /1 h ago/);
  assert.match(html, /data-event-x="40" data-event-z="8"/);
  // newest first
  assert.ok(html.indexOf("Delivered") < html.indexOf("Departed"));
});

test("relativeTimeCoversMinutesHoursAndDays", () => {
  const label = (key) => TIMELINE_LABELS[key];
  assert.equal(panel.relativeTime(20 * 20, label), "just now");
  assert.equal(panel.relativeTime(20 * 60 * 5, label), "5 min ago");
  assert.equal(panel.relativeTime(20 * 3600 * 3, label), "3 h ago");
  assert.equal(panel.relativeTime(20 * 3600 * 50, label), "2 d ago");
});

test("historyCardShowsTheOutcomeAndWhenItEnded", () => {
  const done = Object.assign({}, boat, { status: "DELIVERED", progressRatio: 1, endedGameTime: 1000, events: [] });
  const html = panel.historyCardHtml(done, 1000 + 20 * 60 * 7, tctx({ selected: true }));
  assert.match(html, /data-history-id="o1"/);
  assert.match(html, /Tema → Lomé/);
  assert.match(html, /Delivered/);
  assert.match(html, /7 min ago/);
  assert.match(html, /aria-current="true"/);
  assert.doesNotMatch(html, /data-shipment-id/, "history cards are not live cards");
});

test("historyDetailHasATimelineAndNoFollowButton", () => {
  const done = Object.assign({}, boat, { status: "DELIVERED", progressRatio: 1, endedGameTime: 1000,
    events: [{ kind: "DEPARTED", gameTime: 0, x: 0, z: 0 }, { kind: "DELIVERED", gameTime: 1000, x: 9, z: 9 }] });
  const html = panel.detailHtml(done, tctx({ history: true, nowGameTime: 1200 }));
  assert.match(html, /Timeline/);
  assert.match(html, /Delivered/);
  assert.doesNotMatch(html, /data-shipment-follow/);
  const live = panel.detailHtml(Object.assign({}, boat, { events: [{ kind: "DEPARTED", gameTime: 0, x: 0, z: 0 }] }), tctx({ nowGameTime: 100 }));
  assert.match(live, /Timeline/);
  assert.match(live, /data-shipment-follow/);
});

test("formatsEtaAndSpeed", () => {
  assert.equal(panel.formatEta(59), "59s");
  assert.equal(panel.formatEta(61), "1m 1s");
  assert.equal(panel.formatEta(3700), "1h 1m");
  assert.equal(panel.formatSpeed(0.5), "10.0 m/s");
});


test("modesAreMatchedByWholeWordsNotFragments", () => {
  const kind = (transportMode) => render.vehicleKindOf({ transportMode });
  assert.equal(kind("create-rail"), "train");
  assert.equal(kind("RIVER_BOAT"), "sailboat");
  assert.equal(kind("BOATYARD_EXPRESS"), "carriage");
  assert.equal(kind("SPORTS_CARGO"), "carriage");
  assert.equal(kind("TRAINING_ROUTE"), "carriage");
});

test("targetIndexPrefersTargetPointIndexOverTheOldField", () => {
  assert.equal(render.targetIndexOf({ targetPointIndex: 3, completedPointCount: 1 }), 3);
  assert.equal(render.targetIndexOf({ completedPointCount: 2 }), 2);
  assert.equal(render.targetIndexOf({}), 0);
});

test("vehicleKindPrefersTheServerField", () => {
  assert.equal(render.vehicleKindOf({ vehicleKind: "train", transportMode: "PORT" }), "train");
});

test("vehicleKindFallsBackToTheModeString", () => {
  assert.equal(render.vehicleKindOf({ transportMode: "PORT" }), "sailboat");
  assert.equal(render.vehicleKindOf({ transportMode: "LAND" }), "carriage");
  assert.equal(render.vehicleKindOf({ transportMode: "POST_STATION" }), "carriage");
  assert.equal(render.vehicleKindOf({ transportMode: "RAIL" }), "train");
  assert.equal(render.vehicleKindOf({ transportMode: "" }), "carriage");
  assert.equal(render.vehicleKindOf({ vehicleKind: "spaceship", transportMode: "SEA" }), "sailboat", "unknown kinds are ignored");
});

// completedCount is the waypoint the vehicle is heading to (the game sends autopilotTargetIndex).
test("splitPathJoinsAtTheLivePositionOnTheLegToTheTarget", () => {
  const points = [{ x: 0, z: 0 }, { x: 10, z: 0 }, { x: 20, z: 10 }, { x: 30, z: 30 }];
  const split = render.splitPath(points, 2, { x: 14, z: 4 });
  assert.deepEqual(split.done, [{ x: 0, z: 0 }, { x: 10, z: 0 }, { x: 14, z: 4 }], "the tail stops at the vehicle");
  assert.deepEqual(split.remaining, [{ x: 14, z: 4 }, { x: 20, z: 10 }, { x: 30, z: 30 }], "the dashes follow the bend");
});

test("splitPathFollowsALaggingLivePoint", () => {
  // the live point interpolates towards the newest position, so it can still be on the previous leg
  const points = [{ x: 0, z: 0 }, { x: 10, z: 0 }, { x: 20, z: 0 }, { x: 30, z: 0 }];
  const split = render.splitPath(points, 3, { x: 15, z: 0 });
  assert.deepEqual(split.done, [{ x: 0, z: 0 }, { x: 10, z: 0 }, { x: 15, z: 0 }]);
  assert.deepEqual(split.remaining, [{ x: 15, z: 0 }, { x: 20, z: 0 }, { x: 30, z: 0 }]);
});

test("splitPathWithoutLivePositionUsesTheTargetWaypoint", () => {
  const points = [{ x: 0, z: 0 }, { x: 10, z: 0 }, { x: 20, z: 0 }];
  assert.deepEqual(render.splitPath(points, 0, null), { done: [{ x: 0, z: 0 }], remaining: points });
  assert.deepEqual(render.splitPath(points, 99, null), { done: points, remaining: [{ x: 20, z: 0 }] });
  assert.deepEqual(render.splitPath([{ x: 1, z: 1 }], 0, null), { done: [], remaining: [] });
});

function countingContext() {
  const calls = { stroke: 0, fill: 0 };
  const noop = () => {};
  return {
    calls,
    save: noop, restore: noop, beginPath: noop, moveTo: noop, lineTo: noop, closePath: noop, arc: noop,
    translate: noop, rotate: noop, setLineDash: noop, bezierCurveTo: noop,
    stroke() { calls.stroke++; },
    fill() { calls.fill++; },
    canvas: { width: 800, height: 600 }
  };
}

function longRoute(n) {
  const pts = [];
  for (let i = 0; i < n; i++) {
    pts.push({ x: 10 + i * 0.7, y: 300 + Math.sin(i / 20) * 80 });
  }
  return pts;
}

test("longTrailsUseAFewStrokesPerFrame", () => {
  const ctx = countingContext();
  render.drawDoneTrail(ctx, longRoute(1000), render.MODE_STYLES.sailboat, false, 3);
  assert.ok(ctx.calls.stroke <= 8, `done trail strokes: ${ctx.calls.stroke}`);
  const rail = countingContext();
  render.drawRailBed(rail, longRoute(2000), render.MODE_STYLES.train.color);
  assert.ok(rail.calls.stroke <= 4, `rail bed strokes: ${rail.calls.stroke}`);
});

test("placementsAlongPathAreEvenlySpacedAndOriented", () => {
  const placed = render.placeAlong([{ x: 0, y: 0 }, { x: 100, y: 0 }], 25, 0);
  assert.deepEqual(placed.map((p) => Math.round(p.x)), [0, 25, 50, 75, 100]);
  assert.ok(placed.every((p) => Math.abs(p.angle) < 1e-9));
  const turned = render.placeAlong([{ x: 0, y: 0 }, { x: 0, y: 30 }], 10, 5);
  assert.deepEqual(turned.map((p) => Math.round(p.y)), [5, 15, 25]);
  assert.ok(Math.abs(turned[0].angle - Math.PI / 2) < 1e-9);
  assert.deepEqual(render.placeAlong([{ x: 0, y: 0 }, { x: 0, y: 0 }], 10, 0).length, 1, "zero-length paths do not loop");
});

test("filterByModeAndSearch", () => {
  const list = [
    { shippingOrderId: "a", transportMode: "PORT", sourceTownName: "Tema", targetTownName: "Lomé", vehicleName: "Aurora", cargo: [{ name: "Wheat" }] },
    { shippingOrderId: "b", transportMode: "LAND", sourceTownName: "Accra", targetTownName: "Kumasi", vehicleName: "Wagon 3", cargo: [] },
    { shippingOrderId: "c", transportMode: "RAIL", sourceTownName: "Lagos", targetTownName: "Ibadan", vehicleName: "Express", cargo: [{ name: "Iron ingot" }] }
  ];
  assert.deepEqual(render.filterShipments(list, { kind: "all", query: "" }).map((s) => s.shippingOrderId), ["a", "b", "c"]);
  assert.deepEqual(render.filterShipments(list, { kind: "train", query: "" }).map((s) => s.shippingOrderId), ["c"]);
  assert.deepEqual(render.filterShipments(list, { kind: "all", query: "kum" }).map((s) => s.shippingOrderId), ["b"]);
  assert.deepEqual(render.filterShipments(list, { kind: "all", query: "wheat" }).map((s) => s.shippingOrderId), ["a"], "cargo names are searchable");
  assert.deepEqual(render.filterShipments(list, { kind: "sailboat", query: "express" }), []);
});

test("sortByEtaProgressAndName", () => {
  const list = [
    { shippingOrderId: "a", etaSeconds: 300, progressRatio: 0.2, sourceTownName: "Tema" },
    { shippingOrderId: "b", etaSeconds: 0, progressRatio: 0.9, sourceTownName: "Accra" },
    { shippingOrderId: "c", etaSeconds: 60, progressRatio: 0.5, sourceTownName: "Lagos" }
  ];
  assert.deepEqual(render.sortShipments(list, "eta").map((s) => s.shippingOrderId), ["c", "a", "b"], "unknown ETA goes last");
  assert.deepEqual(render.sortShipments(list, "progress").map((s) => s.shippingOrderId), ["b", "c", "a"]);
  assert.deepEqual(render.sortShipments(list, "name").map((s) => s.shippingOrderId), ["b", "c", "a"]);
  assert.deepEqual(list.map((s) => s.shippingOrderId), ["a", "b", "c"], "the input list is not mutated");
});

test("cargoSummaryShowsTheFirstItemAndHowManyMore", () => {
  assert.equal(render.cargoSummary([]), "");
  assert.equal(render.cargoSummary([{ name: "Wheat", quantity: 64 }]), "64× Wheat");
  assert.equal(render.cargoSummary([{ name: "Wheat", quantity: 64 }, { name: "Iron", quantity: 3 }, { name: "Salt", quantity: 1 }]), "64× Wheat +2");
});

test("modeStylesCoverEveryKind", () => {
  for (const kind of ["sailboat", "carriage", "train"]) {
    assert.match(render.MODE_STYLES[kind].color, /^#[0-9a-f]{6}$/);
  }
});

test("statusKeysAreReadableStates", () => {
  assert.equal(render.statusKey("STUCK"), "stuck");
  assert.equal(render.statusKey("sailing"), "moving");
  assert.equal(render.statusKey("IN_TRANSIT"), "moving");
  assert.equal(render.statusKey("ARRIVED"), "arrived");
  assert.equal(render.statusKey("LOADING"), null, "unknown codes are shown as they are");
  assert.equal(render.statusKey(""), "moving");
});
