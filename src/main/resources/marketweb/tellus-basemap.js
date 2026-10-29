// Tellus world map: pure math for the real-world basemap layer (no DOM access).
// Tellus maps blocks linearly onto Web Mercator: mercatorX = blockX * worldScale, mercatorY = -blockZ * worldScale,
// which is the projection standard XYZ tiles use, so provider tiles only need scaling and cropping.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  if (root) {
    root.SailboatTellusBasemap = api;
  }
})(typeof window !== "undefined" ? window : null, function () {
  "use strict";

  const EARTH_RADIUS = 6378137;
  const WORLD_METERS = 2 * Math.PI * EARTH_RADIUS;
  const HALF_WORLD = WORLD_METERS / 2;
  const TILE_PIXELS = 256;
  const MAX_LATITUDE = 85.05112878;

  const SOURCES = [
    {
      id: "esri",
      label: { en: "Satellite (Esri)", zh: "卫星图（Esri）" },
      url: "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
      subdomains: [],
      maxZoom: 19,
      gcj02: false,
      attribution: "Esri"
    },
    {
      id: "gaode",
      label: { en: "Streets (AutoNavi)", zh: "高德街道" },
      url: "https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}",
      subdomains: ["1", "2", "3", "4"],
      maxZoom: 18,
      gcj02: true,
      attribution: "高德地图 / AutoNavi"
    },
    {
      id: "osm",
      label: { en: "OpenStreetMap", zh: "OpenStreetMap" },
      url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
      subdomains: [],
      maxZoom: 19,
      gcj02: false,
      attribution: "© OpenStreetMap contributors"
    },
    {
      id: "opentopo",
      label: { en: "Topographic (OpenTopoMap)", zh: "地形图（OpenTopoMap）" },
      url: "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
      subdomains: [],
      maxZoom: 17,
      gcj02: false,
      attribution: "© OpenTopoMap (CC-BY-SA)"
    }
  ];

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function blockToMercator(x, z, scale) {
    return { mx: x * scale, my: -z * scale };
  }

  function mercatorToBlock(mx, my, scale) {
    return { x: mx / scale, z: -my / scale };
  }

  function mercatorToLatLon(mx, my) {
    return {
      lat: (Math.atan(Math.sinh(my / EARTH_RADIUS)) * 180) / Math.PI,
      lon: (mx / EARTH_RADIUS) * (180 / Math.PI)
    };
  }

  function latLonToMercator(lat, lon) {
    const phi = (clamp(lat, -MAX_LATITUDE, MAX_LATITUDE) * Math.PI) / 180;
    return {
      mx: (lon * Math.PI * EARTH_RADIUS) / 180,
      my: EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + phi / 2))
    };
  }

  function blockToLatLon(x, z, scale) {
    const m = blockToMercator(x, z, scale);
    return mercatorToLatLon(m.mx, m.my);
  }

  function latLonToBlock(lat, lon, scale) {
    const m = latLonToMercator(lat, lon);
    return mercatorToBlock(m.mx, m.my, scale);
  }

  /** XYZ zoom whose meters-per-pixel best matches the map's blocks-per-pixel. */
  function chooseXyzZoom(blocksPerPixel, scale, maxZoom) {
    const metersPerPixel = Math.max(1e-9, blocksPerPixel * scale);
    return clamp(Math.round(Math.log2(WORLD_METERS / (TILE_PIXELS * metersPerPixel))), 0, maxZoom);
  }

  /**
   * Provider tile crops that cover a canvas showing blocks [minX, maxX) x [minZ, maxZ).
   * offsetMeters shifts the source (GCJ-02 tiles are drawn where their WGS84 positions are).
   * Longitude wraps; rows outside the Mercator range are skipped.
   */
  function xyzCrops(bounds, scale, zoom, offsetMeters) {
    const offset = offsetMeters || { dx: 0, dy: 0 };
    const n = Math.pow(2, zoom);
    const worldPixels = TILE_PIXELS * n;
    const toPx = (mx) => ((mx + HALF_WORLD) / WORLD_METERS) * worldPixels;
    const toPy = (my) => ((HALF_WORLD - my) / WORLD_METERS) * worldPixels;
    const px0 = toPx(bounds.minX * scale + offset.dx);
    const px1 = toPx(bounds.maxX * scale + offset.dx);
    const py0 = toPy(-bounds.minZ * scale + offset.dy);
    const py1 = toPy(-bounds.maxZ * scale + offset.dy);
    const spanX = px1 - px0;
    const spanY = py1 - py0;
    if (!(spanX > 0) || !(spanY > 0)) {
      return [];
    }
    const crops = [];
    for (let ty = Math.floor(py0 / TILE_PIXELS); ty * TILE_PIXELS < py1; ty++) {
      if (ty < 0 || ty >= n) {
        continue;
      }
      const iy0 = Math.max(py0, ty * TILE_PIXELS);
      const iy1 = Math.min(py1, (ty + 1) * TILE_PIXELS);
      for (let tx = Math.floor(px0 / TILE_PIXELS); tx * TILE_PIXELS < px1; tx++) {
        const ix0 = Math.max(px0, tx * TILE_PIXELS);
        const ix1 = Math.min(px1, (tx + 1) * TILE_PIXELS);
        crops.push({
          x: ((tx % n) + n) % n,
          y: ty,
          z: zoom,
          sx: ix0 - tx * TILE_PIXELS,
          sy: iy0 - ty * TILE_PIXELS,
          sw: ix1 - ix0,
          sh: iy1 - iy0,
          dx: ((ix0 - px0) / spanX) * bounds.widthPx,
          dy: ((iy0 - py0) / spanY) * bounds.heightPx,
          dw: ((ix1 - ix0) / spanX) * bounds.widthPx,
          dh: ((iy1 - iy0) / spanY) * bounds.heightPx
        });
      }
    }
    return crops;
  }

  function tileUrl(source, x, y, z, subdomainIndex) {
    const subs = source.subdomains || [];
    const s = subs.length ? subs[Math.abs(subdomainIndex | 0) % subs.length] : "";
    return source.url.replace("{s}", s).replace("{x}", String(x)).replace("{y}", String(y)).replace("{z}", String(z));
  }

  // ---- WGS84 -> GCJ-02 (the standard public conversion used by Chinese map providers) ----
  const GCJ_A = 6378245.0;
  const GCJ_EE = 0.00669342162296594323;

  function outOfChina(lat, lon) {
    return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
  }

  function transformLat(x, y) {
    let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
    ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
    ret += ((20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin((y / 3.0) * Math.PI)) * 2.0) / 3.0;
    ret += ((160.0 * Math.sin((y / 12.0) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30.0)) * 2.0) / 3.0;
    return ret;
  }

  function transformLon(x, y) {
    let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
    ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
    ret += ((20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin((x / 3.0) * Math.PI)) * 2.0) / 3.0;
    ret += ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300.0 * Math.sin((x / 30.0) * Math.PI)) * 2.0) / 3.0;
    return ret;
  }

  function wgs84ToGcj02(lat, lon) {
    if (outOfChina(lat, lon)) {
      return { lat, lon };
    }
    let dLat = transformLat(lon - 105.0, lat - 35.0);
    let dLon = transformLon(lon - 105.0, lat - 35.0);
    const radLat = (lat / 180.0) * Math.PI;
    let magic = Math.sin(radLat);
    magic = 1 - GCJ_EE * magic * magic;
    const sqrtMagic = Math.sqrt(magic);
    dLat = (dLat * 180.0) / (((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic)) * Math.PI);
    dLon = (dLon * 180.0) / ((GCJ_A / sqrtMagic) * Math.cos(radLat) * Math.PI);
    return { lat: lat + dLat, lon: lon + dLon };
  }

  /** Mercator shift (meters, east/north) from a WGS84 position to where a GCJ-02 tile draws it; zero outside China. */
  function gcjOffsetMeters(lat, lon) {
    if (outOfChina(lat, lon)) {
      return { dx: 0, dy: 0 };
    }
    const gcj = wgs84ToGcj02(lat, lon);
    const a = latLonToMercator(lat, lon);
    const b = latLonToMercator(gcj.lat, gcj.lon);
    return { dx: b.mx - a.mx, dy: b.my - a.my };
  }

  /**
   * Whether a tile layer with [minZoom, maxZoom] shows tiles at this map zoom. Leaflet's GridLayer.redraw() skips this
   * check and clamps to minNativeZoom, so a redraw far below the range would request an enormous tile grid.
   */
  function tileLayerActiveAt(mapZoom, minZoom, maxZoom) {
    const zoom = Math.round(Number(mapZoom));
    return Number.isFinite(zoom) && zoom >= minZoom && zoom <= maxZoom;
  }

  /** Half the Earth's width (and Mercator height) in blocks: the world spans [-half, half] on both axes. */
  function earthHalfWidthBlocks(scale) {
    return HALF_WORLD / scale;
  }

  const STORAGE_KEY = "sailboat.basemap";

  /** The viewer's saved basemap source; unknown values or blocked storage fall back to the default (Esri). */
  function basemapSourceFromStorage(storage) {
    let stored = null;
    try {
      stored = storage ? storage.getItem(STORAGE_KEY) : null;
    } catch (_) {
      stored = null;
    }
    return SOURCES.some((source) => source.id === stored) ? stored : SOURCES[0].id;
  }

  function saveBasemapSource(storage, id) {
    try {
      if (storage) {
        storage.setItem(STORAGE_KEY, id);
      }
    } catch (_) {
      // per-viewer convenience only
    }
  }

  function sourceById(id) {
    return SOURCES.find((source) => source.id === id) || SOURCES[0];
  }

  /** Only Tellus worlds with a known world scale get the basemap, the global zoom and the source picker. */
  function shouldShowBasemap(world) {
    return !!(world && world.tellus === true && Number(world.worldScale) > 0);
  }

  return {
    SOURCES,
    basemapSourceFromStorage,
    saveBasemapSource,
    sourceById,
    shouldShowBasemap,
    tileLayerActiveAt,
    earthHalfWidthBlocks,
    TILE_PIXELS,
    blockToMercator,
    mercatorToBlock,
    blockToLatLon,
    latLonToBlock,
    chooseXyzZoom,
    xyzCrops,
    tileUrl,
    outOfChina,
    wgs84ToGcj02,
    gcjOffsetMeters
  };
});
