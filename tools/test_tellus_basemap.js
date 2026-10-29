const assert = require("node:assert/strict");
const path = require("node:path");
const test = require("node:test");

const basemap = require(path.join(__dirname, "../src/main/resources/marketweb/tellus-basemap.js"));
const SCALE = 30;

test("everestSpawnMapsToEverest", () => {
  const { lat, lon } = basemap.blockToLatLon(322548, -108250, SCALE);
  assert.ok(Math.abs(lat - 27.99) < 0.01, `lat ${lat}`);
  assert.ok(Math.abs(lon - 86.92) < 0.01, `lon ${lon}`);
});

test("roundTripBlockLatLon", () => {
  for (const [x, z] of [[0, 0], [322548, -108250], [-740, -20530], [-600000, 200000], [12345, 54321]]) {
    const { lat, lon } = basemap.blockToLatLon(x, z, SCALE);
    const back = basemap.latLonToBlock(lat, lon, SCALE);
    assert.ok(Math.abs(back.x - x) < 0.5 && Math.abs(back.z - z) < 0.5, `${x},${z} -> ${back.x},${back.z}`);
  }
});

test("chooseXyzZoomMatchesScale", () => {
  assert.equal(basemap.chooseXyzZoom(1, SCALE, 19), 12);
  assert.equal(basemap.chooseXyzZoom(2048, SCALE, 19), 1);
  assert.equal(basemap.chooseXyzZoom(1 / 64, SCALE, 17), 17);
  assert.equal(basemap.chooseXyzZoom(1 << 20, SCALE, 19), 0);
});

function sourceZoom(bounds, source = basemap.sourceById("gaode"), scale = 100) {
  assert.equal(typeof basemap.chooseSourceZoom, "function", "the basemap needs a source-aware zoom chooser");
  return basemap.chooseSourceZoom(bounds, scale, source);
}

function geographicBounds(west, east, south, north, scale = 100) {
  const nw = basemap.latLonToBlock(north, west, scale);
  const se = basemap.latLonToBlock(south, east, scale);
  return { minX: nw.x, maxX: se.x, minZ: nw.z, maxZ: se.z, widthPx: 512, heightPx: 512 };
}

test("gaodeDeclaresItsWorkingGlobalAndInternationalZoomRange", () => {
  const source = basemap.sourceById("gaode");
  assert.equal(source.minZoom, 1, "AutoNavi zoom 0 returns a blank placeholder");
  assert.equal(source.internationalMaxZoom, 9, "the saved Greek market has coverage only through zoom 9");
  assert.equal(source.maxZoom, 18);
});

test("savedScale100MarketUsesCoveredGaodeZoomAndStillFillsItsCanvas", () => {
  // The actual market is (28110, 66, -48635), inside this Leaflet tile at native zoom 0.
  const bounds = { minX: 54 * 512, maxX: 55 * 512, minZ: -95 * 512, maxZ: -94 * 512, widthPx: 512, heightPx: 512 };
  const zoom = sourceZoom(bounds);
  assert.equal(zoom, 9);
  const crops = basemap.xyzCrops(bounds, 100, zoom);
  assert.ok(crops.length > 0);
  assert.ok(crops.every((crop) => crop.z <= 9));
  const area = crops.reduce((sum, crop) => sum + crop.dw * crop.dh, 0);
  assert.ok(Math.abs(area - 512 * 512) < 1e-6, `area ${area}`);
});

test("gaodeRetainsZoom18InsideBeijing", () => {
  assert.equal(sourceZoom(geographicBounds(116.39749, 116.39751, 39.90869, 39.90871)), 18);
});

test("gaodeNeverRequestsItsBlankZoomZeroForAGlobalView", () => {
  const bounds = { minX: 0, maxX: 1048576, minZ: -1048576, maxZ: 0, widthPx: 512, heightPx: 512 };
  assert.equal(sourceZoom(bounds), 1);
});

test("gaodeConservativelyCapsTilesCrossingItsDomesticCoverageBoundary", () => {
  for (const bounds of [
    geographicBounds(72.0039, 72.0041, 30, 30.0001),
    geographicBounds(137.8346, 137.8348, 30, 30.0001),
    geographicBounds(116, 116.0001, 55.827, 55.8272),
    geographicBounds(116, 116.0001, 0.8292, 0.8294)
  ]) {
    assert.equal(sourceZoom(bounds), 9);
  }
});

test("gaodeCoverageSelectionHandlesWrappedLongitudesAndTheAntimeridian", () => {
  assert.equal(sourceZoom(geographicBounds(476.39749, 476.39751, 39.90869, 39.90871)), 18, "a repeated world still contains Beijing");
  const bounds = geographicBounds(179.99, 180.01, 39.99, 40.01);
  const zoom = sourceZoom(bounds);
  assert.equal(zoom, 9);
  const crops = basemap.xyzCrops(bounds, 100, zoom);
  const xs = new Set(crops.map((crop) => crop.x));
  assert.ok(xs.has(511) && xs.has(0), `tiles ${[...xs]}`);
  assert.ok(Math.abs(crops.reduce((sum, crop) => sum + crop.dw * crop.dh, 0) - 512 * 512) < 1e-6);
});

test("otherBasemapSourcesKeepTheirExistingZoomSelection", () => {
  const bounds = geographicBounds(25.25163, 25.25165, 39.98318, 39.9832);
  for (const source of basemap.SOURCES.filter((source) => source.id !== "gaode")) {
    assert.equal(sourceZoom(bounds, source), source.maxZoom, source.id);
    const global = { minX: 0, maxX: 1048576, minZ: -1048576, maxZ: 0, widthPx: 512, heightPx: 512 };
    assert.equal(sourceZoom(global, source), basemap.chooseXyzZoom(2048, 100, source.maxZoom), source.id);
  }
});

test("negativeBlockCoordinatesMapToWesternSouthernTiles", () => {
  const crops = basemap.xyzCrops({ minX: -1000, minZ: 1000, maxX: -999, maxZ: 1001, widthPx: 1, heightPx: 1 }, SCALE, 3, { dx: 0, dy: 0 });
  assert.equal(crops.length, 1);
  assert.ok(crops[0].x < 4, `x ${crops[0].x}`);
  assert.ok(crops[0].y >= 4, `y ${crops[0].y}`);
});

test("cropsCoverTheCanvasExactly", () => {
  // a 512x512 canvas over 4096 blocks around the origin at zoom 6
  const crops = basemap.xyzCrops({ minX: -2048, minZ: -2048, maxX: 2048, maxZ: 2048, widthPx: 512, heightPx: 512 }, SCALE, 6, { dx: 0, dy: 0 });
  const area = crops.reduce((sum, c) => sum + c.dw * c.dh, 0);
  assert.ok(Math.abs(area - 512 * 512) < 1e-6, `area ${area}`);
  for (const c of crops) {
    assert.ok(c.sx >= 0 && c.sy >= 0 && c.sx + c.sw <= 256 + 1e-9 && c.sy + c.sh <= 256 + 1e-9);
  }
});

test("tilesOutsideMercatorRangeAreSkipped", () => {
  const top = basemap.latLonToBlock(86, 0, SCALE);
  const crops = basemap.xyzCrops({ minX: -100, minZ: top.z - 5000, maxX: 100, maxZ: top.z, widthPx: 64, heightPx: 64 }, SCALE, 2, { dx: 0, dy: 0 });
  for (const c of crops) {
    assert.ok(c.y >= 0 && c.y < 4, `y ${c.y}`);
  }
});

test("antimeridianWraps", () => {
  const east = basemap.latLonToBlock(0, 179.9, SCALE);
  const crops = basemap.xyzCrops({ minX: east.x, minZ: -10, maxX: east.x + 2000, maxZ: 10, widthPx: 64, heightPx: 8 }, SCALE, 3, { dx: 0, dy: 0 });
  const xs = new Set(crops.map((c) => c.x));
  assert.ok(xs.has(7) && xs.has(0), `tiles ${[...xs]}`);
});

test("gcjOffsetIsZeroOutsideChina", () => {
  assert.deepEqual(basemap.gcjOffsetMeters(35.68, 139.76), { dx: 0, dy: 0 });
  assert.deepEqual(basemap.gcjOffsetMeters(5.55, -0.2), { dx: 0, dy: 0 });
  assert.equal(basemap.outOfChina(35.68, 139.76), true);
});

test("gcjOffsetInBeijingHasTheKnownMagnitude", () => {
  const gcj = basemap.wgs84ToGcj02(39.9087, 116.3975);
  const dLat = gcj.lat - 39.9087;
  const dLon = gcj.lon - 116.3975;
  assert.ok(dLat >= 0.0010 && dLat <= 0.0020, `dLat ${dLat}`);
  assert.ok(dLon >= 0.0055 && dLon <= 0.0070, `dLon ${dLon}`);
  const offset = basemap.gcjOffsetMeters(39.9087, 116.3975);
  assert.ok(offset.dx > 300 && offset.dy > 100, `offset ${JSON.stringify(offset)}`);
});

test("sourcesKeepRequiredAttribution", () => {
  assert.deepEqual(basemap.SOURCES.map((s) => s.id), ["esri", "gaode", "osm", "opentopo"]);
  for (const source of basemap.SOURCES) {
    assert.ok(source.attribution && source.url && source.maxZoom > 0, source.id);
  }
  assert.equal(basemap.SOURCES.find((s) => s.id === "gaode").gcj02, true);
  assert.equal(basemap.tileUrl(basemap.SOURCES[0], 3, 5, 4, 0),
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/4/5/3");
  assert.equal(basemap.tileUrl(basemap.SOURCES[1], 3, 5, 4, 1),
    "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x=3&y=5&z=4");
});

test("storedSourceIsRestored", () => {
  assert.equal(basemap.basemapSourceFromStorage({ getItem: () => "gaode" }), "gaode");
});

test("brokenStorageFallsBackToEsri", () => {
  assert.equal(basemap.basemapSourceFromStorage({ getItem: () => { throw new Error("blocked"); } }), "esri");
  assert.equal(basemap.basemapSourceFromStorage(null), "esri");
});

test("unknownStoredSourceFallsBackToEsri", () => {
  assert.equal(basemap.basemapSourceFromStorage({ getItem: () => "bing" }), "esri");
});

test("nonTellusWorldAddsNoBasemap", () => {
  assert.equal(basemap.shouldShowBasemap({ tellus: false, worldScale: 0 }), false);
  assert.equal(basemap.shouldShowBasemap(null), false);
  assert.equal(basemap.shouldShowBasemap({ tellus: true, worldScale: 0 }), false, "no scale, no projection");
  assert.equal(basemap.shouldShowBasemap({ tellus: true, worldScale: 30 }), true);
});

test("layerOnlyRedrawsWithinItsZoomRange", () => {
  assert.equal(basemap.tileLayerActiveAt(-11, -4, 4), false, "Leaflet would clamp to the native zoom and request ~130k tiles");
  assert.equal(basemap.tileLayerActiveAt(-4.4, -4, 4), true);
  assert.equal(basemap.tileLayerActiveAt(-4.6, -4, 4), false);
  assert.equal(basemap.tileLayerActiveAt(2, -4, 4), true);
  assert.equal(basemap.tileLayerActiveAt(NaN, -4, 4), false);
});

test("earthHalfWidthMatchesMercator", () => {
  const half = basemap.earthHalfWidthBlocks(30);
  assert.ok(Math.abs(half - (Math.PI * 6378137) / 30) < 1e-6);
  assert.ok(Math.abs(half - 667917.9) < 1);
});
