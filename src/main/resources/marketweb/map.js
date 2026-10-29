(function () {
  "use strict";

  const TILE_SIZE = 512;
  const CHUNK_SIZE = 16;
  const MIN_ZOOM = 0.25;
  const MAX_ZOOM = 8;
  const MAX_TILE_ZOOM = 4;
  const SHIPMENT_REFRESH_MS = 2000;
  /** Finished shipments change rarely; the history list is refetched this often while it is shown. */
  const HISTORY_REFRESH_MS = 30000;
  const MAP_REFRESH_MS = 30000;
  const DESKTOP_TILE_CACHE_LIMIT = 320;
  const MOBILE_TILE_CACHE_LIMIT = 96;
  const TILE_OVERDRAW_PIXELS = 1;
  const DESKTOP_MAX_DPR = 2;
  const MOBILE_MAX_DPR = 1.15;
  const DESKTOP_TILE_PADDING = 1;
  const MOBILE_TILE_PADDING = 0;
  const DESKTOP_HOVER_THROTTLE_MS = 32;
  const MISSING_TILE_RETRY_MS = 10_000;
  const TRANSPARENT_TILE_URL = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";

  const LABELS = {
    "en-US": {
      terrain: "Terrain",
      territories: "Territories",
      markets: "Markets",
      shipments: "Logistics",
      focus: "Focus market",
      reset: "Reset view",
      zoomIn: "Zoom in",
      zoomOut: "Zoom out",
      basemap: "Basemap",
      basemapUnavailable: "Basemap source unreachable — try another source.",
      basemapOutsideProjection: "Map coordinates are outside the Earth's projection range. Check the server coordinate encoding / world scale.",
      loading: "Loading map data...",
      loginRequired: "Sign in to view the world map.",
      loadFailed: "Map data unavailable.",
      noShipments: "No active shipments.",
      selectedMarket: "Selected market",
      owner: "Owner",
      town: "Town",
      warehouse: "Warehouse",
      unknown: "Unknown terrain",
      activeShipments: "Active logistics",
      liveShipments: "In progress",
      historyShipments: "History",
      shipmentViews: "Shipments to show",
      noHistory: "No finished shipments yet.",
      timeline: "Timeline",
      eventDeparted: "Departed",
      eventStuck: "Stuck",
      eventResumed: "Back on the way",
      eventDelivered: "Delivered",
      eventArrived: "Arrived",
      eventClaimed: "Collected",
      eventFailed: "Failed",
      eventEnded: "Ended",
      justNow: "just now",
      minutesAgo: "{n} min ago",
      hoursAgo: "{n} h ago",
      daysAgo: "{n} d ago",
      route: "Route",
      status: "Status",
      statusStuck: "Stuck · needs rescue",
      mode: "Mode",
      eta: "ETA",
      speed: "Speed",
      cargo: "Cargo",
      vehicle: "Vehicle",
      owner: "Owner",
      emptySelection: "Hover or click a market, territory, or shipment.",
      modeAll: "All",
      modeFilter: "Transport mode",
      searchShipments: "Search route, vehicle or cargo",
      sortLabel: "Sort",
      sortEta: "Arriving soonest",
      sortProgress: "Most progress",
      sortName: "Origin A–Z",
      noMatches: "No shipments match these filters.",
      follow: "Follow vehicle",
      unfollow: "Stop following",
      progress: "Progress",
      statusMoving: "On the way",
      statusArrived: "Arrived",
      flag: "Flag",
      coords: "Coords"
    },
    "zh-CN": {
      terrain: "地形",
      territories: "领地",
      markets: "市场",
      shipments: "物流",
      focus: "聚焦市场",
      reset: "重置视图",
      zoomIn: "放大",
      zoomOut: "缩小",
      basemap: "底图",
      basemapUnavailable: "底图来源无法连线，可切换来源。",
      basemapOutsideProjection: "地图坐标超出地球投影范围，请检查服务端坐标编码／世界比例。",
      loading: "正在加载地图数据...",
      loginRequired: "登录后才能查看世界地图。",
      loadFailed: "地图数据暂不可用。",
      noShipments: "暂无进行中的物流。",
      selectedMarket: "当前市场",
      owner: "所有者",
      town: "城镇",
      warehouse: "仓库",
      unknown: "未知地形",
      activeShipments: "进行中物流",
      liveShipments: "进行中",
      historyShipments: "历史",
      shipmentViews: "显示的物流",
      noHistory: "还没有已结束的物流。",
      timeline: "时间线",
      eventDeparted: "出发",
      eventStuck: "受阻",
      eventResumed: "恢复行驶",
      eventDelivered: "已送达",
      eventArrived: "已抵达",
      eventClaimed: "已领取",
      eventFailed: "失败",
      eventEnded: "已结束",
      justNow: "刚刚",
      minutesAgo: "{n} 分钟前",
      hoursAgo: "{n} 小时前",
      daysAgo: "{n} 天前",
      route: "线路",
      status: "状态",
      statusStuck: "阻塞 · 需救援",
      mode: "方式",
      eta: "预计到达",
      speed: "当前速度",
      cargo: "货物",
      vehicle: "载具",
      owner: "拥有者",
      emptySelection: "悬停或点击市场、领地、物流线路。",
      modeAll: "全部",
      modeFilter: "运输方式",
      searchShipments: "搜索路线、载具或货物",
      sortLabel: "排序",
      sortEta: "最快到达",
      sortProgress: "进度最多",
      sortName: "起点名称",
      noMatches: "没有符合筛选的物流。",
      follow: "跟随载具",
      unfollow: "停止跟随",
      progress: "进度",
      statusMoving: "运输中",
      statusArrived: "已到达",
      flag: "国旗",
      coords: "坐标"
    }
  };

  const state = {
    root: null,
    mapElement: null,
    canvas: null,
    ctx: null,
    leafletMap: null,
    leafletLayer: null,
    leafletLayers: [],
    activeLeafletLayerIndex: 0,
    leafletRefreshToken: 0,
    leafletRetryTimer: 0,
    tooltip: null,
    toolbar: null,
    layerPanel: null,
    marketList: null,
    detailPanel: null,
    shipmentList: null,
    locale: "zh-CN",
    apiBase: "",
    focusedMarketId: "",
    snapshot: null,
    markets: [],
    territories: [],
    shipments: [],
    layers: {
      terrain: true,
      territories: true,
      markets: true,
      shipments: true
    },
    center: { x: 0, z: 0 },
    // zoom is CSS pixels per block (the Leaflet contract); drawing coordinates are backing pixels.
    canvasScaleX: 1,
    canvasScaleY: 1,
    zoom: 0.85,
    hover: null,
    selected: null,
    dragging: false,
    dragStart: null,
    resizeObserver: null,
    shipmentTimer: 0,
    animationFrame: 0,
    tileCache: new Map(),
    missingTiles: new Map(),
    lastPointerHitTestAt: 0,
    panels: {
      leftCollapsed: true,
      rightCollapsed: true
    },
    mountedToken: 0,
    sessionToken: null,
    localTileRevision: 0,
    lastTerrainSync: null,
    shipmentFilter: { kind: "all", query: "", sort: "eta" },
    followShipmentId: "",
    logisticsEffects: null,
    shipmentView: "live",
    shipmentHistory: [],
    serverGameTime: 0,
    serverGameTimeAt: 0,
    historyFetchedAt: 0,
    world: null,
    basemapLayer: null,
    tellusLayer: null,
    basemapSourceId: "esri",
    basemapTiles: new Map(),
    basemapImages: new Map(),
    basemapAttribution: null,
    tellusRetryTimer: 0,
    shipmentLoop: 0,
    lastShipmentFrameMs: 0,
    staticCanvas: null,
    staticKey: "",
    staticAnchor: null
  };

  // 静态层(领地/市场)缓存:画布比视口大一圈 padding,以"锚点 center"画一次。拖动时不重画领地,
  // 只按 (anchor - center) 像素偏移 drawImage 平移位图(Safari/Retina 上把每帧 O(n) 路径重画降为一次贴图)。
  // 仅当 zoom/数据/图层变,或 center 拖出 padding 缓冲区时才重建。
  const STATIC_LAYER_PAD = 256;

  // Traditional Chinese labels come from the generated table in zh-tw.js.
  LABELS["zh-TW"] = typeof window !== "undefined" && window.SailboatZhTw ? window.SailboatZhTw.convertAll(LABELS["zh-CN"]) : LABELS["zh-CN"];

  /** Picks the en/zh text of a {en, zh} label, in Traditional characters for zh-TW. */
  function localeText(labels) {
    const text = labels ? labels[localeKey()] : "";
    return state.locale === "zh-TW" && typeof window !== "undefined" && window.SailboatZhTw ? window.SailboatZhTw.convert(text) : text;
  }

  function label(key) {
    return (LABELS[state.locale] || LABELS["zh-CN"] || LABELS["en-US"])[key] || LABELS["en-US"][key] || key;
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#39;");
  }

  function mediaMatches(query) {
    try {
      return typeof window !== "undefined"
        && typeof window.matchMedia === "function"
        && window.matchMedia(query).matches;
    } catch (_) {
      return false;
    }
  }

  function isMobileMapDevice() {
    const coarsePointer = mediaMatches("(hover: none), (pointer: coarse)");
    const narrowViewport = typeof window !== "undefined" && window.innerWidth <= 720;
    const touchCapable = typeof navigator !== "undefined" && Number(navigator.maxTouchPoints || 0) > 0;
    return coarsePointer || narrowViewport || touchCapable;
  }

  function tileCacheLimit() {
    return isMobileMapDevice() ? MOBILE_TILE_CACHE_LIMIT : DESKTOP_TILE_CACHE_LIMIT;
  }

  function visibleTilePadding() {
    return isMobileMapDevice() ? MOBILE_TILE_PADDING : DESKTOP_TILE_PADDING;
  }

  function canvasPixelRatio() {
    const raw = typeof window === "undefined" ? 1 : Number(window.devicePixelRatio || 1);
    const max = isMobileMapDevice() ? MOBILE_MAX_DPR : DESKTOP_MAX_DPR;
    return Math.max(1, Math.min(max, raw || 1));
  }

  function applyPerformanceClass() {
    if (!state.root) {
      return;
    }
    state.root.classList.toggle("map-performance-lite", isMobileMapDevice());
  }

  function currentSessionToken() {
    return localStorage.getItem("marketWebSessionToken") || "";
  }

  function clearPrivateShipmentData() {
    state.shipments = [];
    state.shipmentHistory = [];
    state.shipmentView = "live";
    state.historyFetchedAt = 0;
    state.serverGameTime = 0;
    state.serverGameTimeAt = 0;
    state.followShipmentId = "";
    state.logisticsEffects = null;
    state.selected = null;
    state.hover = null;
  }

  // Both the view and the login may change while a request is in flight, including without replacing the root.
  function captureRequestContext() {
    const sessionToken = currentSessionToken();
    if (state.sessionToken !== sessionToken) {
      clearPrivateShipmentData();
      state.sessionToken = sessionToken;
      renderShipments();
      renderSelection();
      if (state.root) {
        scheduleRender();
      }
    }
    return { mountedToken: state.mountedToken, sessionToken };
  }

  function requestContextCurrent(context) {
    return context.mountedToken === state.mountedToken
      && context.sessionToken === state.sessionToken
      && context.sessionToken === currentSessionToken();
  }

  function authHeaders() {
    const headers = new Headers();
    const token = currentSessionToken();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    return headers;
  }

  async function fetchJson(path) {
    const response = await fetch(`${state.apiBase}${path}`, { headers: authHeaders() });
    let body = null;
    try {
      body = await response.json();
    } catch (_) {
      body = null;
    }
    if (!response.ok) {
      const message = body?.message || body?.error || (response.status === 401 ? label("loginRequired") : label("loadFailed"));
      throw new Error(message);
    }
    return body || {};
  }

  async function fetchOptionalJson(path, fallback = {}) {
    try {
      return await fetchJson(path);
    } catch (_) {
      return fallback;
    }
  }

  function squaremapLeafletAvailable() {
    return typeof window !== "undefined" && !!window.L && !!state.mapElement;
  }

  function createSquaremapTileLayer() {
    const L = window.L;
    // 用原生 L.TileLayer(img.src=url)而非自定义 fetch()+createObjectURL(blob)。
    // blob 路径会在 load 后 revokeObjectURL 即焚:Leaflet keepBuffer 复用旧 img DOM 时 blob 已失效,
    // 缩放/平移要复用旧瓦片只能重新 createTile→重新 fetch → "缩放就丢图、白屏重下"。
    // 原生 img.src 直接吃浏览器 HTTP 缓存:同 URL(同 ?v)复用零网络、瞬间显示。瓦片接口不鉴权,
    // 原生路径与原 fetch(未带 auth 头)行为一致。
    return new L.TileLayer("", {
      tileSize: TILE_SIZE,
      minZoom: -MAX_TILE_ZOOM,
      maxZoom: 4,
      minNativeZoom: -MAX_TILE_ZOOM,
      maxNativeZoom: 0,
      errorTileUrl: TRANSPARENT_TILE_URL,
      noWrap: true,
      updateWhenIdle: true,
      keepBuffer: isMobileMapDevice() ? 1 : 2,
      className: "squaremap-terrain-tile"
    });
  }

  // ---- Tellus world map: real-world basemap (A) and Tellus prediction tiles (B) under the terrain tiles ----
  const TELLUS_MIN_ZOOM = 1 / 2048; // whole Earth (~1.34M blocks wide at scale 30) fits on screen
  const BASEMAP_IMAGE_LIMIT = 256;

  function tellusBasemap() {
    return typeof window !== "undefined" ? window.SailboatTellusBasemap || null : null;
  }

  function tellusWorld() {
    const api = tellusBasemap();
    return api && api.shouldShowBasemap(state.world) ? state.world : null;
  }

  function minZoom() {
    return tellusWorld() ? TELLUS_MIN_ZOOM : MIN_ZOOM;
  }

  function minLeafletScale() {
    return tellusWorld() ? TELLUS_MIN_ZOOM : 1 / (1 << MAX_TILE_ZOOM);
  }

  function applyWorldInfo(world) {
    state.world = world || { tellus: false };
    if (!tellusWorld() || !state.leafletMap || state.basemapLayer) {
      return;
    }
    const api = window.SailboatTellusBasemap;
    state.basemapSourceId = api.basemapSourceFromStorage(safeLocalStorage());
    state.leafletMap.setMinZoom(Math.log2(TELLUS_MIN_ZOOM));
    state.basemapLayer = createBasemapLayer();
    state.basemapLayer.setZIndex(-2).addTo(state.leafletMap);
    const maxTellusZoom = Number(state.world.tellusTileMaxZoom);
    if (Number.isInteger(maxTellusZoom) && maxTellusZoom >= 0) {
      state.tellusLayer = createTellusTileLayer(maxTellusZoom);
      state.tellusLayer.setZIndex(-1).addTo(state.leafletMap);
    }
    renderBasemapAttribution();
    renderToolbar();
    syncSquaremapTerrain(true);
  }

  function safeLocalStorage() {
    try {
      return window.localStorage;
    } catch (_) {
      return null;
    }
  }

  function createBasemapLayer() {
    const L = window.L;
    const BasemapLayer = L.GridLayer.extend({
      createTile(coords, done) {
        const tile = document.createElement("canvas");
        const size = this.getTileSize();
        tile.width = size.x;
        tile.height = size.y;
        drawBasemapTile(tile, coords).then(() => done(null, tile), () => done(null, tile));
        return tile;
      }
    });
    const layer = new BasemapLayer({
      tileSize: TILE_SIZE,
      minZoom: Math.log2(TELLUS_MIN_ZOOM),
      maxZoom: 4,
      noWrap: true,
      updateWhenIdle: true,
      keepBuffer: 1,
      className: "tellus-basemap-tile"
    });
    layer.on("tileunload", (event) => {
      if (state.basemapLayer !== layer) {
        return;
      }
      state.basemapTiles.delete(event.tile);
      renderBasemapAttribution();
    });
    return layer;
  }

  async function drawBasemapTile(canvas, coords) {
    const api = window.SailboatTellusBasemap;
    const world = tellusWorld();
    if (!world) {
      return;
    }
    const scale = Number(world.worldScale);
    const source = api.sourceById(state.basemapSourceId);
    const blocksPerTile = TILE_SIZE / Math.pow(2, coords.z);
    const bounds = {
      minX: coords.x * blocksPerTile,
      minZ: coords.y * blocksPerTile,
      maxX: (coords.x + 1) * blocksPerTile,
      maxZ: (coords.y + 1) * blocksPerTile,
      widthPx: canvas.width,
      heightPx: canvas.height
    };
    const zoom = api.chooseSourceZoom(bounds, scale, source);
    let offset = { dx: 0, dy: 0 };
    if (source.gcj02) {
      const centre = api.blockToLatLon((bounds.minX + bounds.maxX) / 2, (bounds.minZ + bounds.maxZ) / 2, scale);
      offset = api.gcjOffsetMeters(centre.lat, centre.lon);
    }
    const ctx = canvas.getContext("2d");
    const crops = api.xyzCrops(bounds, scale, zoom, offset);
    const tiles = state.basemapTiles;
    const tile = { bounds, requested: crops.length > 0, pending: crops.length > 0, drawn: 0 };
    const diagnostics = canvas.dataset ??= {};
    diagnostics.basemapSource = source.id;
    diagnostics.basemapZoom = String(zoom);
    diagnostics.basemapRequested = String(crops.length);
    diagnostics.basemapDrawn = "0";
    tiles.set(canvas, tile);
    renderBasemapAttribution();
    await Promise.all(crops.map(async (crop, index) => {
      const image = await loadBasemapImage(api.tileUrl(source, crop.x, crop.y, crop.z, crop.x + crop.y + index));
      if (image) {
        ctx.drawImage(image, crop.sx, crop.sy, crop.sw, crop.sh, crop.dx, crop.dy, crop.dw, crop.dh);
        tile.drawn += 1;
      }
    }));
    tile.pending = false;
    diagnostics.basemapDrawn = String(tile.drawn);
    if (state.basemapTiles === tiles && tiles.has(canvas)) {
      renderBasemapAttribution();
    }
  }

  function loadBasemapImage(url) {
    const cache = state.basemapImages;
    const cached = cache.get(url);
    if (cached) {
      return cached;
    }
    const promise = new Promise((resolve) => {
      const image = new Image();
      image.onload = () => {
        resolve(image);
      };
      image.onerror = () => {
        cache.delete(url);
        resolve(null);
      };
      image.src = url;
    });
    cache.set(url, promise);
    if (cache.size > BASEMAP_IMAGE_LIMIT) {
      cache.delete(cache.keys().next().value);
    }
    return promise;
  }

  function createTellusTileLayer(maxTellusZoom) {
    const L = window.L;
    const half = window.SailboatTellusBasemap.earthHalfWidthBlocks(Number(state.world.worldScale));
    const layer = new L.TileLayer("", {
      tileSize: TILE_SIZE,
      bounds: L.latLngBounds(L.latLng(-half, -half), L.latLng(half, half)),
      minZoom: -maxTellusZoom,
      maxZoom: 4,
      minNativeZoom: -maxTellusZoom,
      maxNativeZoom: 0,
      errorTileUrl: TRANSPARENT_TILE_URL,
      noWrap: true,
      updateWhenIdle: true,
      keepBuffer: isMobileMapDevice() ? 1 : 2,
      className: "tellus-prediction-tile"
    });
    layer.getTileUrl = (coords) => {
      const zoom = clamp(-Math.round(Number(coords.z) || 0), 0, maxTellusZoom);
      const revision = Number(state.snapshot?.tellusRenderVersion) || 0;
      return `${state.apiBase}/api/map/tellus/tile/${zoom}/${coords.x}_${coords.y}.png?v=${revision}`;
    };
    // Tiles not rendered yet answer 404 and are queued on the server. Reload only those tiles later: redrawing the
    // whole layer would blink every tile and, at far zoom, request a huge grid.
    const failed = new Map();
    layer.on("tileerror", (event) => {
      if (event.tile && event.coords) {
        failed.set(event.tile, event.coords);
      }
      if (state.tellusRetryTimer) {
        return;
      }
      state.tellusRetryTimer = window.setTimeout(() => {
        state.tellusRetryTimer = 0;
        if (state.tellusLayer !== layer) {
          return;
        }
        const retry = [...failed.entries()];
        failed.clear();
        retry.forEach(([tile, coords]) => {
          if (tile.isConnected) {
            tile.src = `${layer.getTileUrl(coords)}&retry=${Date.now()}`;
          }
        });
      }, MISSING_TILE_RETRY_MS);
    });
    return layer;
  }

  function setBasemapSource(id) {
    const api = tellusBasemap();
    if (!api || !state.basemapLayer) {
      return;
    }
    state.basemapSourceId = api.sourceById(id).id;
    api.saveBasemapSource(safeLocalStorage(), state.basemapSourceId);
    state.basemapImages = new Map();
    state.basemapTiles = new Map();
    state.basemapLayer.redraw();
    renderBasemapAttribution();
  }

  function basemapPickerHtml() {
    const api = tellusBasemap();
    if (!api || !tellusWorld()) {
      return "";
    }
    const options = api.SOURCES.map((source) => `<option value="${escapeHtml(source.id)}"${source.id === state.basemapSourceId ? " selected" : ""}>${escapeHtml(localeText(source.label))}</option>`).join("");
    return `<label class="map-basemap-picker">${escapeHtml(label("basemap"))} <select data-map-basemap>${options}</select></label>`;
  }

  function renderBasemapAttribution() {
    const api = tellusBasemap();
    if (!api || !tellusWorld() || !state.mapElement) {
      return;
    }
    if (!state.basemapAttribution) {
      state.basemapAttribution = document.createElement("div");
      state.basemapAttribution.className = "map-basemap-attribution";
      state.mapElement.parentElement?.appendChild(state.basemapAttribution);
    }
    const source = api.sourceById(state.basemapSourceId);
    const centerZ = Number(state.center.z);
    const outsideProjection = !Number.isFinite(centerZ) || Math.abs(centerZ) > api.earthHalfWidthBlocks(Number(state.world.worldScale));
    const problem = outsideProjection ? "basemapOutsideProjection" : basemapConnectionFailed() ? "basemapUnavailable" : "";
    const notice = problem ? ` · ${label(problem)}` : "";
    state.basemapAttribution.textContent = `${source.attribution} · Tellus${notice}`;
    state.basemapAttribution.classList.toggle("is-warning", notice !== "");
  }

  function basemapConnectionFailed() {
    // Ignore buffered/offscreen tiles and empty tiles beyond Earth's north/south edges. A source warning means
    // every requested tile in this viewport has finished without drawing an image, rather than a global error count.
    const view = state.leafletMap?.getBounds?.();
    const zoom = Math.max(TELLUS_MIN_ZOOM, Number(state.zoom) || 1);
    const halfWidth = (state.mapElement.clientWidth || state.canvas?.width || TILE_SIZE) / (2 * zoom);
    const halfHeight = (state.mapElement.clientHeight || state.canvas?.height || TILE_SIZE) / (2 * zoom);
    const minX = view ? view.getWest() : state.center.x - halfWidth;
    const maxX = view ? view.getEast() : state.center.x + halfWidth;
    const minZ = view ? -view.getNorth() : state.center.z - halfHeight;
    const maxZ = view ? -view.getSouth() : state.center.z + halfHeight;
    const visible = [...state.basemapTiles.values()].filter((tile) => tile.requested
      && tile.bounds.minX < maxX && tile.bounds.maxX > minX
      && tile.bounds.minZ < maxZ && tile.bounds.maxZ > minZ);
    return visible.length > 0 && visible.every((tile) => !tile.pending && !tile.drawn);
  }

  function tileZoomFromLeafletZoom(leafletZoom) {
    return clamp(-Math.floor(Number(leafletZoom) || 0), 0, MAX_TILE_ZOOM);
  }

  function configureSquaremapTileLayer(layer) {
    if (!layer) {
      return layer;
    }
    layer.getTileUrl = (coords) => {
      const tileZoom = tileZoomFromLeafletZoom(coords.z);
      return `${state.apiBase}/api/map/square/tile/overworld/${tileZoom}/${coords.x}_${coords.y}.png?v=${encodeURIComponent(tileRefreshKey())}`;
    };
    layer.on("tileerror", () => {
      if (state.leafletRetryTimer) {
        return;
      }
      state.leafletRetryTimer = window.setTimeout(() => {
        state.leafletRetryTimer = 0;
        redrawSquaremapTerrain();
      }, MISSING_TILE_RETRY_MS);
    });
    return layer;
  }

  function switchSquaremapLayer(index) {
    if (!state.leafletMap || !state.leafletLayers.length) {
      return;
    }
    state.activeLeafletLayerIndex = index;
    state.leafletLayers.forEach((layer, layerIndex) => {
      if (!layer) {
        return;
      }
      if (!state.leafletMap.hasLayer(layer)) {
        layer.addTo(state.leafletMap);
      }
      layer.setZIndex(layerIndex === index ? 1 : 0);
    });
    state.leafletLayer = state.leafletLayers[index] || null;
  }

  function redrawSquaremapTerrain() {
    if (!state.leafletMap || !state.leafletLayers.length) {
      return;
    }
    // Below the layer's zoom range Leaflet's redraw() would clamp to the native zoom and request a huge tile grid
    // (reachable only with the Tellus global zoom); the layer reloads normally once the view zooms back in.
    const api = tellusBasemap();
    if (api && !api.tileLayerActiveAt(state.leafletMap.getZoom(), -MAX_TILE_ZOOM, 4)) {
      return;
    }
    if (state.leafletLayers.length < 2) {
      state.leafletLayers[0]?.redraw();
      return;
    }
    const nextIndex = state.activeLeafletLayerIndex === 0 ? 1 : 0;
    const nextLayer = state.leafletLayers[nextIndex];
    if (!nextLayer) {
      return;
    }
    const refreshToken = ++state.leafletRefreshToken;
    nextLayer.setZIndex(0);
    nextLayer.once("load", () => {
      if (refreshToken === state.leafletRefreshToken) {
        switchSquaremapLayer(nextIndex);
      }
    });
    if (!state.leafletMap.hasLayer(nextLayer)) {
      nextLayer.addTo(state.leafletMap);
    } else {
      nextLayer.redraw();
    }
  }

  function initSquaremapTerrain() {
    if (!squaremapLeafletAvailable() || state.leafletMap) {
      return;
    }
    const L = window.L;
    state.leafletMap = L.map(state.mapElement, {
      crs: L.CRS.Simple,
      center: [0, 0],
      zoom: 0,
      minZoom: -MAX_TILE_ZOOM,
      maxZoom: 4,
      zoomSnap: 0,
      zoomDelta: 0.25,
      attributionControl: false,
      preferCanvas: true,
      noWrap: true,
      zoomControl: false,
      dragging: false,
      scrollWheelZoom: false,
      doubleClickZoom: false,
      touchZoom: false,
      boxZoom: false,
      keyboard: false
    });
    state.leafletLayers = [
      configureSquaremapTileLayer(createSquaremapTileLayer()),
      configureSquaremapTileLayer(createSquaremapTileLayer())
    ];
    state.activeLeafletLayerIndex = 0;
    state.leafletRefreshToken = 0;
    state.leafletLayer = state.leafletLayers[0];
    state.leafletLayer.setZIndex(1).addTo(state.leafletMap);
    syncSquaremapTerrain(true);
  }

  function syncSquaremapTerrain(force) {
    if (!state.leafletMap) {
      renderBasemapAttribution();
      return;
    }
    const leafletZoom = Math.log2(Math.max(minLeafletScale(), Number(state.zoom) || 1));
    const cx = state.center.x;
    const cz = state.center.z;
    const last = state.lastTerrainSync;
    // center/zoom 未变且非强制 → 跳过昂贵的 Leaflet invalidateSize+setView 全量回流。
    // 这是 Safari 卡顿根因:物流动画每帧调 renderCanvas 会触发 60次/秒回流。
    if (!force && last && last.x === cx && last.z === cz && last.zoom === leafletZoom) {
      return;
    }
    state.lastTerrainSync = { x: cx, z: cz, zoom: leafletZoom };
    state.leafletMap.invalidateSize(false);
    state.leafletMap.setView(window.L.latLng(-cz, cx), leafletZoom, { animate: false });
    renderBasemapAttribution();
  }

  function destroySquaremapTerrain() {
    if (state.leafletRetryTimer) {
      window.clearTimeout(state.leafletRetryTimer);
      state.leafletRetryTimer = 0;
    }
    if (state.tellusRetryTimer) {
      window.clearTimeout(state.tellusRetryTimer);
      state.tellusRetryTimer = 0;
    }
    state.basemapLayer = null;
    state.tellusLayer = null;
    state.basemapImages = new Map();
    state.basemapTiles = new Map();
    if (state.basemapAttribution) {
      state.basemapAttribution.remove();
      state.basemapAttribution = null;
    }
    if (state.leafletMap) {
      state.leafletMap.remove();
      state.leafletMap = null;
      state.leafletLayer = null;
      state.leafletLayers = [];
      state.activeLeafletLayerIndex = 0;
      state.leafletRefreshToken = 0;
    }
  }

  function hexFromRgb(value, fallback) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) {
      return fallback;
    }
    return `#${(numeric & 0xffffff).toString(16).padStart(6, "0")}`;
  }

  function rgbaFromRgb(value, alpha) {
    const numeric = Number(value) & 0xffffff;
    const r = (numeric >> 16) & 255;
    const g = (numeric >> 8) & 255;
    const b = numeric & 255;
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function normalizePoint(point) {
    const out = {
      x: Number(point?.x) || 0,
      z: Number(point?.z) || 0
    };
    // debugroute 调试航点携带的真实方块快照 + 出身标记(origin/segment >=0 才有效),透传供节点层渲染 + tooltip。
    if (point && typeof point.origin === "number" && point.origin >= 0) {
      out.origin = point.origin;     // 0=寻路原始节点 1=样条插值点
      out.segment = typeof point.segment === "number" ? point.segment : -1; // 0=起始 1=中段 2=尾段
      out.block = typeof point.block === "string" ? point.block : "";
      out.water = point.water === true;
      out.hasWater = typeof point.water === "boolean";
    }
    return out;
  }

  function worldToScreen(point) {
    return {
      x: (point.x - state.center.x) * state.zoom * state.canvasScaleX + state.canvas.width / 2,
      y: (point.z - state.center.z) * state.zoom * state.canvasScaleY + state.canvas.height / 2
    };
  }

  function screenToWorld(x, y) {
    return {
      x: (x - state.canvas.width / 2) / (state.zoom * state.canvasScaleX) + state.center.x,
      z: (y - state.canvas.height / 2) / (state.zoom * state.canvasScaleY) + state.center.z
    };
  }

  // 把鼠标客户端坐标换算成世界方块坐标(X/Z)显示在工具栏。传 null 则清空(鼠标移出/拖拽时)。
  function updateCoordReadout(clientX, clientY) {
    if (!state.toolbar) {
      return;
    }
    const readout = state.toolbar.querySelector("[data-map-coord]");
    if (!readout) {
      return;
    }
    if (clientX == null || clientY == null || !state.canvas) {
      readout.innerHTML = "X: —   Z: —";
      return;
    }
    const rect = state.canvas.getBoundingClientRect();
    const x = (clientX - rect.left) * (state.canvas.width / rect.width);
    const y = (clientY - rect.top) * (state.canvas.height / rect.height);
    const world = screenToWorld(x, y);
    readout.innerHTML = `X: ${Math.round(world.x)}   Z: ${Math.round(world.z)}`;
  }

  function activeTileZoom() {
    if (state.zoom < 0.34) {
      return Math.min(2, MAX_TILE_ZOOM);
    }
    if (state.zoom < 0.68) {
      return Math.min(1, MAX_TILE_ZOOM);
    }
    return 0;
  }

  function tileWorldSize(tileZoom) {
    return TILE_SIZE * (1 << Math.max(0, Math.min(MAX_TILE_ZOOM, Number(tileZoom) || 0)));
  }

  function tileKey(tileZoom, tileX, tileZ) {
    return `${tileZoom},${tileX},${tileZ}`;
  }

  function tileRefreshKey() {
    const snapshot = state.snapshot || {};
    return [
      snapshot.renderVersion || snapshot.tileCacheVersion || 0,
      snapshot.tileEpoch || 0,
      snapshot.territoryRevision || 0,
      snapshot.marketRevision || 0,
      state.localTileRevision || 0
    ].join("-");
  }

  function clearLocalTileImages() {
    state.tileCache = new Map();
    state.missingTiles = new Map();
    state.localTileRevision = (state.localTileRevision || 0) + 1;
    redrawSquaremapTerrain();
  }

  // 软刷新:后台渲染更新瓦片时调,不 new Map() 清空整个缓存(那会让全图重下、每次轮询都闪),
  // 只 bump localTileRevision → tileRefreshKey 变 → 新 ?v。可见瓦片按新 URL 取真正变了的内容;
  // 未变的瓦片旧 URL 仍在浏览器磁盘缓存,长缓存命中零网络。reset 按钮仍用 clearLocalTileImages 强清。
  function softRefreshTiles() {
    state.missingTiles = new Map(); // 给之前 404 的瓦片一次重试机会(渲染后可能已出图)
    state.localTileRevision = (state.localTileRevision || 0) + 1;
    redrawSquaremapTerrain();
  }

  function trimTileCache() {
    const limit = tileCacheLimit();
    while (state.tileCache.size > limit) {
      const first = state.tileCache.keys().next().value;
      if (first == null) {
        return;
      }
      state.tileCache.delete(first);
    }
  }

  function loadTile(tileZoom, tileX, tileZ) {
    const key = tileKey(tileZoom, tileX, tileZ);
    const missedAt = state.missingTiles.get(key);
    if (missedAt && Date.now() - missedAt < MISSING_TILE_RETRY_MS) {
      return null;
    }
    if (missedAt) {
      state.missingTiles.delete(key);
    }
    if (state.tileCache.has(key)) {
      return state.tileCache.get(key) || null;
    }
    const image = new Image();
    image.decoding = "async";
    image.onload = () => {
      scheduleRender();
    };
    image.onerror = () => {
      state.tileCache.delete(key);
      state.missingTiles.set(key, Date.now());
      window.setTimeout(() => {
        if (state.missingTiles.has(key)) {
          state.missingTiles.delete(key);
          scheduleRender();
        }
      }, MISSING_TILE_RETRY_MS);
      scheduleRender();
    };
    image.src = `${state.apiBase}/api/map/square/tile/overworld/${tileZoom}/${tileX}_${tileZ}.png?v=${encodeURIComponent(tileRefreshKey())}`;
    state.tileCache.set(key, image);
    trimTileCache();
    return image;
  }

  function resizeCanvas() {
    if (!state.canvas) {
      return;
    }
    applyPerformanceClass();
    const rect = state.canvas.getBoundingClientRect();
    const dpr = canvasPixelRatio();
    const width = Math.max(320, Math.floor(rect.width * dpr));
    const height = Math.max(320, Math.floor(rect.height * dpr));
    // Floors and the minimum backing size can make these differ from DPR and from each other.
    // Cache them here so drawing thousands of vertices never needs a layout read.
    const scaleX = rect.width > 0 ? width / rect.width : 1;
    const scaleY = rect.height > 0 ? height / rect.height : 1;
    if (state.canvas.width !== width || state.canvas.height !== height
      || state.canvasScaleX !== scaleX || state.canvasScaleY !== scaleY) {
      state.canvas.width = width;
      state.canvas.height = height;
      state.canvasScaleX = scaleX;
      state.canvasScaleY = scaleY;
      state.ctx.setTransform(1, 0, 0, 1, 0, 0);
      state.ctx.imageSmoothingEnabled = false;
      state.staticKey = ""; // 画布尺寸变 → 静态层缓存失效
      syncSquaremapTerrain(true);
      scheduleRender();
    }
  }

  function renderToolbar() {
    if (!state.toolbar) {
      return;
    }
    state.toolbar.innerHTML = `
      <button type="button" data-map-action="zoom-out" aria-label="${escapeHtml(label("zoomOut"))}">-</button>
      <span class="map-zoom-readout">${Math.round(state.zoom * 100)}%</span>
      <button type="button" data-map-action="zoom-in" aria-label="${escapeHtml(label("zoomIn"))}">+</button>
      <span class="map-coord-readout" data-map-coord title="${escapeHtml(label("coords"))}">X: — &nbsp; Z: —</span>
      <button type="button" data-map-action="focus">${escapeHtml(label("focus"))}</button>
      <button type="button" data-map-action="reset">${escapeHtml(label("reset"))}</button>
      ${basemapPickerHtml()}
    `;
    const picker = state.toolbar.querySelector("[data-map-basemap]");
    if (picker) {
      picker.addEventListener("change", () => setBasemapSource(picker.value));
    }
    state.toolbar.querySelectorAll("[data-map-action]").forEach((button) => {
      button.addEventListener("click", () => {
        const action = button.getAttribute("data-map-action");
        if (action === "zoom-in") {
          zoomAt(state.canvas.width / 2, state.canvas.height / 2, 1.25);
        } else if (action === "zoom-out") {
          zoomAt(state.canvas.width / 2, state.canvas.height / 2, 0.8);
        } else if (action === "focus") {
          focusSelectedMarket();
        } else if (action === "reset") {
          clearLocalTileImages();
          applySnapshotFocus();
          scheduleRender();
        }
      });
    });
  }

  function syncPanelClasses() {
    if (!state.root) {
      return;
    }
    state.root.classList.toggle("map-panel-left-collapsed", !!state.panels.leftCollapsed);
    state.root.classList.toggle("map-panel-right-collapsed", !!state.panels.rightCollapsed);
    state.root.querySelectorAll("[data-map-panel-toggle]").forEach((button) => {
      const side = button.getAttribute("data-map-panel-toggle");
      const collapsed = side === "left" ? state.panels.leftCollapsed : state.panels.rightCollapsed;
      button.setAttribute("aria-pressed", collapsed ? "true" : "false");
    });
    resizeCanvas();
    scheduleRender();
  }

  function togglePanel(side) {
    if (side === "left") {
      state.panels.leftCollapsed = !state.panels.leftCollapsed;
    } else if (side === "right") {
      state.panels.rightCollapsed = !state.panels.rightCollapsed;
    }
    syncPanelClasses();
  }

  function bindPanelToggles() {
    if (!state.root) {
      return;
    }
    state.root.querySelectorAll("[data-map-panel-toggle]").forEach((button) => {
      button.addEventListener("click", () => togglePanel(button.getAttribute("data-map-panel-toggle")));
    });
  }

  function renderLayerToggles() {
    if (!state.layerPanel) {
      return;
    }
    const rows = [
      ["terrain", label("terrain")],
      ["territories", label("territories")],
      ["markets", label("markets")],
      ["shipments", label("shipments")]
    ];
    state.layerPanel.innerHTML = rows.map(([key, text]) => `
      <label class="map-layer-toggle">
        <input type="checkbox" data-map-layer="${key}" ${state.layers[key] ? "checked" : ""}>
        <span>${escapeHtml(text)}</span>
      </label>
    `).join("");
    state.layerPanel.querySelectorAll("[data-map-layer]").forEach((input) => {
      input.addEventListener("change", () => {
        const key = input.getAttribute("data-map-layer");
        state.layers[key] = !!input.checked;
        scheduleRender();
      });
    });
  }

  function renderMarketList() {
    if (!state.marketList) {
      return;
    }
    if (!state.markets.length) {
      state.marketList.innerHTML = `<div class="map-empty-note">${escapeHtml(label("loadFailed"))}</div>`;
      return;
    }
    state.marketList.innerHTML = `
      <div class="map-list-heading">${escapeHtml(label("markets"))}</div>
      ${state.markets.map((market) => `
        <button type="button" class="map-market-row" data-market-id="${escapeHtml(market.marketId || "")}">
          <span>${escapeHtml(market.marketName || market.marketId || "-")}</span>
          <small>${escapeHtml(market.townName || market.ownerName || "-")}</small>
        </button>
      `).join("")}
    `;
    state.marketList.querySelectorAll("[data-market-id]").forEach((button) => {
      button.addEventListener("click", () => {
        const marker = state.markets.find((market) => market.marketId === button.getAttribute("data-market-id"));
        if (marker) {
          state.selected = { type: "market", data: marker };
          centerOn({ x: marker.x, z: marker.z }, Math.max(state.zoom, 1.35));
          renderSelection();
        }
      });
    });
  }

  function logistics() {
    return typeof window !== "undefined" ? window.SailboatLogistics || null : null;
  }

  function shipmentTargetIndex(shipment) {
    const api = logistics();
    if (api) {
      return api.targetIndexOf(shipment);
    }
    const value = shipment && shipment.targetPointIndex != null ? shipment.targetPointIndex : shipment && shipment.completedPointCount;
    return Math.max(0, Math.floor(Number(value) || 0));
  }

  const REDUCED_MOTION = typeof window !== "undefined" && typeof window.matchMedia === "function"
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function shipmentIsStuck(shipment) {
    return String(shipment.status || "").toUpperCase() === "STUCK";
  }

  function shipmentKind(shipment) {
    const api = logistics();
    return api ? api.vehicleKindOf(shipment) : "carriage";
  }

  function shipmentStyle(shipment) {
    const api = logistics();
    return api ? api.MODE_STYLES[shipmentKind(shipment)] : { color: "#0ea5e9", trail: "rgba(14, 165, 233, ", label: { en: "", zh: "" } };
  }

  function localeKey() {
    return state.locale && state.locale.startsWith("zh") ? "zh" : "en";
  }

  function logisticsPanel() {
    return typeof window !== "undefined" ? window.SailboatLogisticsPanel || null : null;
  }

  /** Server game time now, from the last response's gameTime plus the time since (20 ticks a second). */
  function nowGameTime() {
    return state.serverGameTimeAt ? state.serverGameTime + (performance.now() - state.serverGameTimeAt) / 50 : 0;
  }

  function noteGameTime(data) {
    if (data && Number.isFinite(Number(data.gameTime))) {
      state.serverGameTime = Number(data.gameTime);
      state.serverGameTimeAt = performance.now();
    }
  }

  function renderShipments() {
    if (!state.shipmentList) {
      return;
    }
    const api = logistics();
    const panel = logisticsPanel();
    if (!api || !panel) {
      state.shipmentList.innerHTML = `<div class="map-empty-note">${escapeHtml(label("noShipments"))}</div>`;
      return;
    }
    // The toolbar is built once and kept: the list refreshes every few seconds and must not steal focus from
    // the search box or close the sort menu. Only the counts and the cards are updated.
    if (!state.shipmentList.querySelector("[data-shipment-cards]")) {
      buildShipmentToolbar(api);
    }
    const history = state.shipmentView === "history";
    const all = (history ? state.shipmentHistory : state.shipments) || [];
    const filter = state.shipmentFilter;
    state.shipmentList.querySelectorAll("[data-shipment-view]").forEach((button) => {
      button.setAttribute("aria-pressed", String(button.getAttribute("data-shipment-view") === state.shipmentView));
    });
    const sort = state.shipmentList.querySelector("[data-shipment-sort]");
    if (sort) {
      sort.disabled = history; // history is always newest first
    }
    const counts = { all: all.length };
    for (const shipment of all) {
      const kind = shipmentKind(shipment);
      counts[kind] = (counts[kind] || 0) + 1;
    }
    state.shipmentList.querySelectorAll("[data-shipment-kind]").forEach((button) => {
      const kind = button.getAttribute("data-shipment-kind");
      button.setAttribute("aria-pressed", String(filter.kind === kind));
      button.disabled = kind !== "all" && !counts[kind] && filter.kind !== kind;
      const count = button.querySelector("[data-shipment-count]");
      if (count) {
        count.textContent = String(counts[kind] || 0);
      }
    });
    const filtered = api.filterShipments(all, filter);
    const shown = history ? filtered : api.sortShipments(filtered, filter.sort);
    const now = nowGameTime();
    const idAttr = history ? "data-history-id" : "data-shipment-id";
    const isSelected = (shipment) => state.selected?.type === (history ? "history" : "shipment")
      && state.selected.data?.shippingOrderId === shipment.shippingOrderId;
    const cards = shown.map((shipment) => history
      ? panel.historyCardHtml(shipment, now, { label, modeLabel: localeText, selected: isSelected(shipment) })
      : panel.cardHtml(shipment, { label, modeLabel: localeText, selected: isSelected(shipment) })).join("");
    const container = state.shipmentList.querySelector("[data-shipment-cards]");
    const active = document.activeElement;
    const focusedId = active && container.contains(active) ? active.getAttribute(idAttr) : null;
    const empty = all.length ? label("noMatches") : label(history ? "noHistory" : "noShipments");
    container.innerHTML = cards || `<div class="map-empty-note">${escapeHtml(empty)}</div>`;
    container.querySelectorAll(`[${idAttr}]`).forEach((button) => {
      button.addEventListener("click", () => {
        const shipment = all.find((entry) => entry.shippingOrderId === button.getAttribute(idAttr));
        if (!shipment) {
          return;
        }
        if (state.followShipmentId && state.followShipmentId !== shipment.shippingOrderId) {
          state.followShipmentId = ""; // a different shipment was chosen: stop following the old one
        }
        state.selected = { type: history ? "history" : "shipment", data: shipment };
        const points = (shipment.points || []).map(normalizePoint);
        if (points.length) {
          const index = history ? points.length - 1 : Math.min(points.length - 1, shipmentTargetIndex(shipment));
          centerOn(points[index], Math.max(state.zoom, 1));
        }
        renderSelection();
        renderShipments();
        scheduleRender();
      });
    });
    if (focusedId) {
      // the list refreshes every few seconds; keyboard and screen-reader users keep their place
      [...container.querySelectorAll(`[${idAttr}]`)].find((card) => card.getAttribute(idAttr) === focusedId)?.focus();
    }
  }

  function buildShipmentToolbar(api) {
    const filter = state.shipmentFilter;
    const kinds = api ? api.KINDS : [];
    const kindButtons = [`<button type="button" data-shipment-kind="all">${escapeHtml(label("modeAll"))} <span data-shipment-count></span></button>`]
      .concat(kinds.map((kind) => `<button type="button" data-shipment-kind="${kind}" data-kind="${kind}" style="--mode:${api.MODE_STYLES[kind].color}">${api.LIST_ICONS[kind]}${escapeHtml(localeText(api.MODE_STYLES[kind].label))} <span data-shipment-count></span></button>`))
      .join("");
    state.shipmentList.innerHTML = `
      <div class="map-shipment-views" role="group" aria-label="${escapeHtml(label("shipmentViews"))}">
        <button type="button" data-shipment-view="live">${escapeHtml(label("liveShipments"))}</button>
        <button type="button" data-shipment-view="history">${escapeHtml(label("historyShipments"))}</button>
      </div>
      <div class="map-shipment-tools">
        <div class="map-shipment-kinds" role="group" aria-label="${escapeHtml(label("modeFilter"))}">${kindButtons}</div>
        <div class="map-shipment-search">
          <input type="search" data-shipment-query value="${escapeHtml(filter.query)}" placeholder="${escapeHtml(label("searchShipments"))}" aria-label="${escapeHtml(label("searchShipments"))}">
          <select data-shipment-sort aria-label="${escapeHtml(label("sortLabel"))}">
            <option value="eta"${filter.sort === "eta" ? " selected" : ""}>${escapeHtml(label("sortEta"))}</option>
            <option value="progress"${filter.sort === "progress" ? " selected" : ""}>${escapeHtml(label("sortProgress"))}</option>
            <option value="name"${filter.sort === "name" ? " selected" : ""}>${escapeHtml(label("sortName"))}</option>
          </select>
        </div>
      </div>
      <div class="map-shipment-cards" data-shipment-cards></div>
    `;
    state.shipmentList.querySelectorAll("[data-shipment-view]").forEach((button) => {
      button.addEventListener("click", () => {
        state.shipmentView = button.getAttribute("data-shipment-view");
        if (state.shipmentView === "history") {
          loadShipmentHistory();
        }
        renderShipments();
      });
    });
    state.shipmentList.querySelectorAll("[data-shipment-kind]").forEach((button) => {
      button.addEventListener("click", () => {
        state.shipmentFilter.kind = button.getAttribute("data-shipment-kind");
        renderShipments();
      });
    });
    state.shipmentList.querySelector("[data-shipment-query]")?.addEventListener("input", (event) => {
      state.shipmentFilter.query = event.target.value;
      renderShipments();
    });
    state.shipmentList.querySelector("[data-shipment-sort]")?.addEventListener("change", (event) => {
      state.shipmentFilter.sort = event.target.value;
      renderShipments();
    });
  }

  function renderSelection() {
    if (!state.detailPanel) {
      return;
    }
    const followFocused = !!document.activeElement?.matches?.("[data-shipment-follow]");
    const item = state.selected || state.hover;
    if (!item) {
      state.detailPanel.innerHTML = `<div class="map-empty-note">${escapeHtml(label("emptySelection"))}</div>`;
      return;
    }
    if (item.type === "market") {
      const market = item.data;
      state.detailPanel.innerHTML = `
        <div class="map-detail-card">
          <p class="section-kicker">${escapeHtml(label("selectedMarket"))}</p>
          <h3>${escapeHtml(market.marketName || market.marketId || "-")}</h3>
          <dl>
            <dt>${escapeHtml(label("owner"))}</dt><dd>${escapeHtml(market.ownerName || "-")}</dd>
            <dt>${escapeHtml(label("town"))}</dt><dd>${escapeHtml(market.townName || "-")}</dd>
            <dt>X/Z</dt><dd>${Math.round(Number(market.x) || 0)}, ${Math.round(Number(market.z) || 0)}</dd>
          </dl>
        </div>
      `;
      return;
    }
    if (item.type === "territory") {
      const territory = item.data;
      const fill = hexFromRgb(territory.fillRgb, "#2563eb");
      const border = hexFromRgb(territory.borderRgb, "#111827");
      state.detailPanel.innerHTML = `
        <div class="map-detail-card">
          <p class="section-kicker">${escapeHtml(label("territories"))}</p>
          <div class="map-detail-flag-frame">
            ${territory.flagUrl ? `<img data-map-flag-image src="${escapeHtml(territory.flagUrl)}" alt="${escapeHtml(label("flag"))}">` : `<span>${escapeHtml(label("flag"))}</span>`}
          </div>
          <h3>${escapeHtml(territory.nationName || territory.townName || "-")}</h3>
          <div class="map-color-line">
            <span style="background:${fill}"></span>
            <span style="background:${border}"></span>
          </div>
          <dl>
            <dt>${escapeHtml(label("town"))}</dt><dd>${escapeHtml(territory.townName || "-")}</dd>
            <dt>Chunk</dt><dd>${territory.chunkX}, ${territory.chunkZ}</dd>
          </dl>
        </div>
      `;
      bindFlagFallbacks(state.detailPanel);
      return;
    }
    const shipment = item.data;
    const panel = logisticsPanel();
    if (!panel) {
      state.detailPanel.innerHTML = "";
      return;
    }
    const history = item.type === "history";
    const following = !history && !!state.followShipmentId && state.followShipmentId === shipment.shippingOrderId;
    state.detailPanel.innerHTML = panel.detailHtml(shipment, { label, modeLabel: localeText, following, history, nowGameTime: nowGameTime() });
    state.detailPanel.querySelectorAll("[data-event-x]").forEach((button) => {
      button.addEventListener("click", () => {
        // show where this happened
        centerOn({ x: Number(button.getAttribute("data-event-x")) || 0, z: Number(button.getAttribute("data-event-z")) || 0 }, Math.max(state.zoom, 2));
      });
    });
    if (followFocused) {
      state.detailPanel.querySelector("[data-shipment-follow]")?.focus();
    }
    state.detailPanel.querySelector("[data-shipment-follow]")?.addEventListener("click", () => {
      state.followShipmentId = following ? "" : (shipment.shippingOrderId || "");
      renderSelection();
      scheduleRender();
    });
  }

  function formatEta(seconds) {
    const panel = logisticsPanel();
    return panel ? panel.formatEta(seconds) : `${Math.round(Number(seconds) || 0)}s`;
  }

  function formatSpeed(blocksPerTick) {
    const panel = logisticsPanel();
    return panel ? panel.formatSpeed(blocksPerTick) : "";
  }

  function setPanelMessage(message) {
    if (state.detailPanel) {
      state.detailPanel.innerHTML = `<div class="map-empty-note">${escapeHtml(message)}</div>`;
    }
  }

  function applySnapshotFocus() {
    const focus = normalizePoint(state.snapshot?.defaultFocus);
    state.center = focus;
    state.zoom = 0.85;
    focusSelectedMarket(false);
    renderToolbar();
    scheduleRender();
  }

  function focusSelectedMarket(forceZoom = true) {
    const marker = state.markets.find((market) => market.marketId && market.marketId === state.focusedMarketId)
      || state.markets.find((market) => market.marketId && market.marketId === state.root?.getAttribute("data-focused-market"));
    if (!marker) {
      return;
    }
    state.selected = { type: "market", data: marker };
    centerOn({ x: Number(marker.x) || 0, z: Number(marker.z) || 0 }, forceZoom ? Math.max(1.25, state.zoom) : state.zoom);
    renderSelection();
  }

  function clearPointerHover() {
    state.hover = null;
    renderTooltip(null);
    renderSelection();
    scheduleRender();
  }

  function centerOn(point, zoom) {
    clearPointerHover();
    state.center = { x: Number(point.x) || 0, z: Number(point.z) || 0 };
    state.zoom = clamp(Number(zoom) || state.zoom, minZoom(), MAX_ZOOM);
    renderToolbar();
    scheduleRender();
  }

  function zoomAt(screenX, screenY, ratio) {
    clearPointerHover();
    const before = screenToWorld(screenX, screenY);
    state.zoom = clamp(state.zoom * ratio, minZoom(), MAX_ZOOM);
    const after = screenToWorld(screenX, screenY);
    state.center.x += before.x - after.x;
    state.center.z += before.z - after.z;
    renderToolbar();
    scheduleRender();
  }

  function visibleTileRange(tileZoom = activeTileZoom()) {
    const topLeft = screenToWorld(0, 0);
    const bottomRight = screenToWorld(state.canvas.width, state.canvas.height);
    const padding = visibleTilePadding();
    const worldSize = tileWorldSize(tileZoom);
    const minX = Math.floor(Math.min(topLeft.x, bottomRight.x) / worldSize) - padding;
    const maxX = Math.floor(Math.max(topLeft.x, bottomRight.x) / worldSize) + padding;
    const minZ = Math.floor(Math.min(topLeft.z, bottomRight.z) / worldSize) - padding;
    const maxZ = Math.floor(Math.max(topLeft.z, bottomRight.z) / worldSize) + padding;
    return { minX, maxX, minZ, maxZ };
  }

  function screenRectForWorldBounds(x, z, width, height) {
    const start = worldToScreen({ x, z });
    const end = worldToScreen({ x: x + width, z: z + height });
    const left = Math.floor(Math.min(start.x, end.x));
    const top = Math.floor(Math.min(start.y, end.y));
    const right = Math.ceil(Math.max(start.x, end.x));
    const bottom = Math.ceil(Math.max(start.y, end.y));
    return {
      x: left,
      y: top,
      width: Math.max(1, right - left),
      height: Math.max(1, bottom - top),
      right,
      bottom
    };
  }

  function drawPixelAlignedTile(ctx, image, x, z, worldSize) {
    const rect = screenRectForWorldBounds(x, z, worldSize, worldSize);
    ctx.drawImage(
      image,
      rect.x - TILE_OVERDRAW_PIXELS,
      rect.y - TILE_OVERDRAW_PIXELS,
      rect.width + TILE_OVERDRAW_PIXELS * 2,
      rect.height + TILE_OVERDRAW_PIXELS * 2
    );
  }

  function drawTerrain(ctx) {
    const tileZoom = activeTileZoom();
    const worldSize = tileWorldSize(tileZoom);
    const range = visibleTileRange(tileZoom);
    for (let tileX = range.minX; tileX <= range.maxX; tileX += 1) {
      for (let tileZ = range.minZ; tileZ <= range.maxZ; tileZ += 1) {
        const worldX = tileX * worldSize;
        const worldZ = tileZ * worldSize;
        const rect = screenRectForWorldBounds(worldX, worldZ, worldSize, worldSize);
        ctx.fillStyle = "#000000";
        ctx.fillRect(rect.x, rect.y, rect.width, rect.height);
        if (!state.layers.terrain) {
          continue;
        }
        const image = loadTile(tileZoom, tileX, tileZ);
        if (image && image.complete && image.naturalWidth > 0) {
          drawPixelAlignedTile(ctx, image, worldX, worldZ, worldSize);
        }
      }
    }
  }

  function territoryKey(chunkX, chunkZ) {
    return `${chunkX},${chunkZ}`;
  }

  function territoryOwnerKey(territory) {
    return String(territory?.nationId || territory?.townId || "");
  }

  function buildTerritoryIndex() {
    const index = new Map();
    for (const territory of state.territories || []) {
      index.set(territoryKey(Number(territory.chunkX), Number(territory.chunkZ)), territory);
    }
    return index;
  }

  function hasAdjacentTerritory(index, territory, dx, dz) {
    const neighbor = index.get(territoryKey(Number(territory.chunkX) + dx, Number(territory.chunkZ) + dz));
    return !!neighbor && territoryOwnerKey(neighbor) === territoryOwnerKey(territory);
  }

  function drawTerritories(ctx, padding = 0) {
    if (!state.layers.territories) {
      return;
    }
    const territoryIndex = buildTerritoryIndex();
    for (const territory of state.territories || []) {
      const x = Number(territory.chunkX) * CHUNK_SIZE;
      const z = Number(territory.chunkZ) * CHUNK_SIZE;
      const rect = screenRectForWorldBounds(x, z, CHUNK_SIZE, CHUNK_SIZE);
      if (rect.right < -padding - 4 || rect.bottom < -padding - 4
        || rect.x > state.canvas.width + padding + 4 || rect.y > state.canvas.height + padding + 4) {
        continue;
      }
      ctx.fillStyle = rgbaFromRgb(territory.fillRgb, 0.28);
      ctx.fillRect(rect.x, rect.y, rect.width, rect.height);
    }
    ctx.lineCap = "square";
    ctx.lineJoin = "miter";
    for (const territory of state.territories || []) {
      const x = Number(territory.chunkX) * CHUNK_SIZE;
      const z = Number(territory.chunkZ) * CHUNK_SIZE;
      const rect = screenRectForWorldBounds(x, z, CHUNK_SIZE, CHUNK_SIZE);
      if (rect.right < -padding - 4 || rect.bottom < -padding - 4
        || rect.x > state.canvas.width + padding + 4 || rect.y > state.canvas.height + padding + 4) {
        continue;
      }
      ctx.beginPath();
      if (!hasAdjacentTerritory(territoryIndex, territory, 0, -1)) {
        ctx.moveTo(rect.x, rect.y);
        ctx.lineTo(rect.right, rect.y);
      }
      if (!hasAdjacentTerritory(territoryIndex, territory, 1, 0)) {
        ctx.moveTo(rect.right, rect.y);
        ctx.lineTo(rect.right, rect.bottom);
      }
      if (!hasAdjacentTerritory(territoryIndex, territory, 0, 1)) {
        ctx.moveTo(rect.right, rect.bottom);
        ctx.lineTo(rect.x, rect.bottom);
      }
      if (!hasAdjacentTerritory(territoryIndex, territory, -1, 0)) {
        ctx.moveTo(rect.x, rect.bottom);
        ctx.lineTo(rect.x, rect.y);
      }
      ctx.strokeStyle = rgbaFromRgb(territory.borderRgb, 0.92);
      ctx.lineWidth = Math.max(1, Math.min(2.5, state.zoom * 0.18));
      ctx.stroke();
    }
  }

  function lerp(a, b, t) {
    return a + (b - a) * Math.max(0, Math.min(1, t));
}

  // 实时位置插值：在两次轮询之间按时间补间，载具图标平滑跟走。
  function shipmentLivePoint(shipment) {
    if (shipment._toX === undefined) {
      return null;
    }
    const t = (performance.now() - (shipment._lerpStartMs || 0)) / SHIPMENT_REFRESH_MS;
    return {
      x: lerp(shipment._fromX || 0, shipment._toX || 0, t),
      z: lerp(shipment._fromZ || 0, shipment._toZ || 0, t)
    };
  }

  // 用新一帧 shipments 的 current 作插值终点，旧帧终点作起点，供 shipmentLivePoint 平滑补间。
  function applyShipmentInterpolation(next) {
    const prevById = {};
    for (const s of (state.shipments || [])) {
      prevById[s.shippingOrderId] = s;
    }
    const now = performance.now();
    for (const s of (next || [])) {
      const prev = prevById[s.shippingOrderId];
      const cur = s.current
        || (s.points && s.points.length
            ? s.points[Math.min(shipmentTargetIndex(s), s.points.length - 1)]
            : null);
      const from = (prev && prev._toX !== undefined) ? { x: prev._toX, z: prev._toZ } : cur;
      s._fromX = from ? from.x : (cur ? cur.x : 0);
      s._fromZ = from ? from.z : (cur ? cur.z : 0);
      s._toX = cur ? cur.x : s._fromX;
      s._toZ = cur ? cur.z : s._fromZ;
      s._lerpStartMs = now;
    }
    return next || [];
  }

  function drawShipments(ctx) {
    if (!state.layers.shipments) {
      return;
    }
    const api = logistics();
    if (!api) {
      return;
    }
    if (!state.logisticsEffects) {
      state.logisticsEffects = api.createEffects();
    }
    const now = performance.now();
    // Dashes flow and particles drift only while motion is allowed; vehicle positions always update.
    const phase = REDUCED_MOTION ? 0 : now / 1000;
    const pulse = REDUCED_MOTION ? 0.5 : (now % 1800) / 1800;
    for (const shipment of state.shipments || []) {
      const points = (shipment.points || []).map(normalizePoint);
      if (points.length < 2) {
        continue;
      }
      const kind = api.vehicleKindOf(shipment);
      const style = api.MODE_STYLES[kind];
      const stuck = shipmentIsStuck(shipment);
      const selected = state.selected?.type === "shipment" && state.selected.data?.shippingOrderId === shipment.shippingOrderId;
      const completed = clamp(api.targetIndexOf(shipment), 0, points.length - 1);
      const live = shipmentLivePoint(shipment);
      const split = api.splitPath(points, completed, live ? normalizePoint(live) : null);
      const toScreen = (list) => list.map((point) => worldToScreen(point));
      if (kind === "train") {
        api.drawRailBed(ctx, toScreen(points), style.color);
      }
      const width = selected ? 5 : 3.5;
      api.drawDoneTrail(ctx, toScreen(split.done), style, stuck, width);
      api.drawRemainingTrail(ctx, toScreen(split.remaining), style, stuck, Math.max(2, width - 1.5), phase);
      api.drawEndpoints(ctx, worldToScreen(points[0]), worldToScreen(points[points.length - 1]), style, pulse);
      // debugroute 调试航线:航点节点层(原始=大圆/插值=小圆,水=蓝描边/陆=红描边)。
      drawRouteNodes(ctx, points);
      drawShipmentVehicleIcon(ctx, shipment, points, completed, stuck);
    }
    if (state.selected?.type === "history") {
      // the whole route a finished shipment travelled
      const entry = state.selected.data;
      const points = (entry.points || []).map(normalizePoint);
      if (points.length >= 2) {
        const kind = api.vehicleKindOf(entry);
        const style = api.MODE_STYLES[kind];
        const screen = points.map((point) => worldToScreen(point));
        if (kind === "train") {
          api.drawRailBed(ctx, screen, style.color);
        }
        api.drawDoneTrail(ctx, screen, style, false, 4);
        api.drawEndpoints(ctx, screen[0], screen[screen.length - 1], style, 0.5);
      }
    }
    api.pruneEffects(state.logisticsEffects, now);
  }

  // 调试航点节点层:仅 debugroute 投放、Point 带 origin 标记的航点才画圆点。
  // 大圆(r6)=寻路原始节点,小圆(r3)=样条插值点;蓝描边=海平面是水,红描边=陆(穿陆嫌疑)。
  function drawRouteNodes(ctx, points) {
    if (!points || !points.length) {
      return;
    }
    for (const point of points) {
      if (typeof point.origin !== "number" || point.origin < 0) {
        continue; // 普通航点无 debug 数据,不画节点
      }
      const screen = worldToScreen(point);
      if (screen.x < -16 || screen.y < -16 || screen.x > state.canvas.width + 16 || screen.y > state.canvas.height + 16) {
        continue;
      }
      const raw = point.origin === 0;
      const radius = raw ? 6 : 3;
      const onLand = point.hasWater && !point.water;
      ctx.save();
      ctx.beginPath();
      ctx.arc(screen.x, screen.y, radius, 0, Math.PI * 2);
      ctx.fillStyle = raw ? "rgba(250, 204, 21, 0.95)" : "rgba(125, 211, 252, 0.95)";
      ctx.fill();
      ctx.lineWidth = 2;
      // 陆地点红描边醒目(穿陆嫌疑),水格蓝描边。
      ctx.strokeStyle = onLand ? "#dc2626" : "#1d4ed8";
      ctx.stroke();
      ctx.restore();
    }
  }

  function shipmentIconPose(points, completedPointCount) {
    if (!points || points.length < 2) {
      return null;
    }
    const index = clamp(Number(completedPointCount) || 0, 0, points.length - 1);
    const fromIndex = index > 0 ? index - 1 : 0;
    const toIndex = index < points.length - 1 ? index + 1 : index;
    const from = points[fromIndex];
    const current = points[index];
    const to = points[toIndex];
    const headingSource = toIndex === index ? from : current;
    const headingTarget = toIndex === index ? current : to;
    const angle = Math.atan2(headingTarget.z - headingSource.z, headingTarget.x - headingSource.x);
    return { point: current, angle };
  }

  function drawShipmentVehicleIcon(ctx, shipment, points, completedPointCount, isStuck) {
    const fallbackPose = shipmentIconPose(points, completedPointCount);
    // 优先用实时插值坐标跟走；无实时数据时回退到路点定位。
    const live = shipmentLivePoint(shipment);
    let posePoint;
    let angle;
    if (live) {
      posePoint = normalizePoint(live);
      angle = Math.atan2((shipment._toZ || 0) - (shipment._fromZ || 0),
                         (shipment._toX || 0) - (shipment._fromX || 0));
    } else if (fallbackPose) {
      posePoint = fallbackPose.point;
      angle = fallbackPose.angle;
    } else {
      return;
    }
    const screen = worldToScreen(posePoint);
    if (screen.x < -40 || screen.y < -40 || screen.x > state.canvas.width + 40 || screen.y > state.canvas.height + 40) {
      return;
    }
    const size = clamp(20 + state.zoom * 2, 20, 32);
    const api = logistics();
    const kind = shipmentKind(shipment);
    const style = shipmentStyle(shipment);
    const now = performance.now();
    if (api && state.logisticsEffects && !REDUCED_MOTION) {
      const moving = !isStuck && String(shipment.status || "").toUpperCase() !== "ARRIVED";
      api.stepEffects(state.logisticsEffects, shipment.shippingOrderId || "", kind, screen.x, screen.y, angle, now, moving);
      api.drawEffects(ctx, state.logisticsEffects, shipment.shippingOrderId || "", kind, style, now);
    }
    // 脉动光晕：随时间正弦呼吸，卡死告警用红色、手动车暖色、调度车冷色。
    const pulse = 0.5 + 0.5 * Math.sin(performance.now() / 350);
    const haloColor = isStuck ? "#dc2626" : (shipment.manual ? "#f59e0b" : style.color);
    ctx.save();
    ctx.translate(screen.x, screen.y);
    ctx.globalAlpha = isStuck ? (0.30 + 0.30 * pulse) : (0.18 + 0.16 * pulse);
    ctx.fillStyle = haloColor;
    ctx.beginPath();
    ctx.arc(0, 0, size * (0.7 + 0.25 * pulse), 0, Math.PI * 2);
    ctx.fill();
    ctx.globalAlpha = 1;
    ctx.rotate(angle);
    if (api) {
      api.drawVehicle(ctx, kind, size, style, REDUCED_MOTION ? 0 : now / 1000);
    }
    ctx.restore();
    // 卡死告警角标：图标右上角红圈白感叹号（不随船头旋转）。
    if (isStuck) {
      const badge = size * 0.42;
      const bx = screen.x + size * 0.55;
      const by = screen.y - size * 0.55;
      ctx.save();
      ctx.fillStyle = "#dc2626";
      ctx.strokeStyle = "#ffffff";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(bx, by, badge, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.fillStyle = "#ffffff";
      ctx.font = `bold ${Math.round(badge * 1.4)}px sans-serif`;
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText("!", bx, by + badge * 0.05);
      ctx.restore();
    }
  }

  function roundedRectPath(ctx, x, y, width, height, radius) {
    const r = Math.min(radius, Math.abs(width) / 2, Math.abs(height) / 2);
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + width - r, y);
    ctx.quadraticCurveTo(x + width, y, x + width, y + r);
    ctx.lineTo(x + width, y + height - r);
    ctx.quadraticCurveTo(x + width, y + height, x + width - r, y + height);
    ctx.lineTo(x + r, y + height);
    ctx.quadraticCurveTo(x, y + height, x, y + height - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
  }

  function drawRoute(ctx, points, dashed, color, width) {
    if (!points || points.length < 2) {
      return;
    }
    ctx.save();
    ctx.beginPath();
    points.forEach((point, index) => {
      const screen = worldToScreen(point);
      if (index === 0) {
        ctx.moveTo(screen.x, screen.y);
      } else {
        ctx.lineTo(screen.x, screen.y);
      }
    });
    ctx.strokeStyle = color;
    ctx.lineWidth = Math.max(1.5, width);
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.setLineDash(dashed ? [12, 9] : []);
    ctx.stroke();
    ctx.restore();
  }

  function drawMarkets(ctx, padding = 0) {
    if (!state.layers.markets) {
      return;
    }
    for (const marker of state.markets || []) {
      const screen = worldToScreen({ x: Number(marker.x) || 0, z: Number(marker.z) || 0 });
      if (screen.x < -padding - 32 || screen.y < -padding - 32
        || screen.x > state.canvas.width + padding + 32 || screen.y > state.canvas.height + padding + 32) {
        continue;
      }
      const selected = state.selected?.type === "market" && state.selected.data?.marketId === marker.marketId;
      ctx.save();
      ctx.translate(screen.x, screen.y);
      ctx.fillStyle = selected ? "#d99a00" : "#2563eb";
      ctx.strokeStyle = "rgba(255, 255, 255, 0.92)";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(0, 0, selected ? 8 : 6, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.fillStyle = "rgba(17, 24, 39, 0.86)";
      ctx.fillRect(10, -13, Math.min(180, 28 + String(marker.marketName || "").length * 7), 24);
      ctx.fillStyle = "#ffffff";
      ctx.font = "12px Inter, Segoe UI, sans-serif";
      ctx.fillText(String(marker.marketName || marker.marketId || "-").slice(0, 20), 18, 3);
      ctx.restore();
    }
  }

  function drawHoverHighlight(ctx) {
    const item = state.hover || state.selected;
    if (!item) {
      return;
    }
    if (item.type === "territory") {
      const territory = item.data;
      const screen = worldToScreen({ x: Number(territory.chunkX) * CHUNK_SIZE, z: Number(territory.chunkZ) * CHUNK_SIZE });
      const width = CHUNK_SIZE * state.zoom * state.canvasScaleX;
      const height = CHUNK_SIZE * state.zoom * state.canvasScaleY;
      ctx.save();
      ctx.strokeStyle = "#ffffff";
      ctx.lineWidth = 2;
      ctx.strokeRect(screen.x, screen.y, width, height);
      ctx.restore();
    }
  }

  // 静态层(地形兜底 + 领地 + 市场)缓存键:仅 center/zoom/画布尺寸/数据修订/图层开关变化才需重建。
  // 物流动画每帧只需 blit 这张缓存,避免重复跑 O(n) 的领地两遍绘制。
  // 缓存键【不含 center】:拖动(仅 center 变)不触发重建,改由 renderOverlayFrame 平移贴图。
  // zoom/数据/图层/尺寸变才换 key 重建。
  function staticLayerKey() {
    const snapshot = state.snapshot || {};
    return [
      state.zoom,
      state.canvas.width, state.canvas.height,
      state.canvasScaleX, state.canvasScaleY,
      snapshot.territoryRevision || 0,
      snapshot.marketRevision || 0,
      state.localTileRevision || 0,
      state.leafletMap ? 1 : 0,
      state.layers.terrain ? 1 : 0,
      state.layers.territories ? 1 : 0,
      state.layers.markets ? 1 : 0
    ].join("|");
  }

  // 拖动时 center 偏离锚点的像素距离;超过 padding 缓冲就必须重建(否则贴图边缘露白)。
  function staticAnchorDriftPx() {
    if (!state.staticAnchor) {
      return Infinity;
    }
    const dx = Math.abs(state.center.x - state.staticAnchor.x) * state.zoom * state.canvasScaleX;
    const dz = Math.abs(state.center.z - state.staticAnchor.z) * state.zoom * state.canvasScaleY;
    return Math.max(dx, dz);
  }

  function ensureStaticLayer() {
    const key = staticLayerKey();
    const fresh = state.staticKey === key && state.staticCanvas && staticAnchorDriftPx() <= STATIC_LAYER_PAD;
    if (fresh) {
      return state.staticCanvas;
    }
    let off = state.staticCanvas;
    if (!off) {
      off = document.createElement("canvas");
      state.staticCanvas = off;
    }
    const w = state.canvas.width + STATIC_LAYER_PAD * 2;
    const h = state.canvas.height + STATIC_LAYER_PAD * 2;
    if (off.width !== w || off.height !== h) {
      off.width = w;
      off.height = h;
    }
    const octx = off.getContext("2d");
    octx.setTransform(1, 0, 0, 1, 0, 0);
    octx.imageSmoothingEnabled = false;
    octx.clearRect(0, 0, off.width, off.height);
    // 以"当前 center"为锚点画静态层。画函数用 worldToScreen(读 state.center 与 state.canvas 尺寸),
    // 故临时把视口原点平移 +PAD(等价于在更大画布上、坐标系整体右下移 PAD),画完恢复 transform。
    state.staticAnchor = { x: state.center.x, z: state.center.z, zoom: state.zoom };
    octx.translate(STATIC_LAYER_PAD, STATIC_LAYER_PAD);
    if (!state.leafletMap) {
      octx.fillStyle = "#000000";
      octx.fillRect(-STATIC_LAYER_PAD, -STATIC_LAYER_PAD, off.width, off.height);
      drawTerrain(octx);
    }
    drawTerritories(octx, STATIC_LAYER_PAD);
    drawMarkets(octx, STATIC_LAYER_PAD);
    state.staticKey = key;
    return off;
  }

  // 覆盖层帧:绝不触碰 Leaflet。静态层(缓存位图,按锚点 center 画)+ 物流 + 悬停高亮。物流动画循环只调它。
  // 静态层贴图按 (anchor - center)*zoom 偏移:拖动时只平移位图,不重画领地/市场。
  function renderOverlayFrame() {
    if (!state.ctx || !state.canvas) {
      return;
    }
    const ctx = state.ctx;
    const layer = ensureStaticLayer();
    ctx.clearRect(0, 0, state.canvas.width, state.canvas.height);
    const anchor = state.staticAnchor || { x: state.center.x, z: state.center.z };
    const offsetX = (anchor.x - state.center.x) * state.zoom * state.canvasScaleX - STATIC_LAYER_PAD;
    const offsetZ = (anchor.z - state.center.z) * state.zoom * state.canvasScaleY - STATIC_LAYER_PAD;
    ctx.drawImage(layer, Math.round(offsetX), Math.round(offsetZ));
    drawShipments(ctx);
    drawHoverHighlight(ctx);
  }

  function renderCanvas() {
    if (!state.ctx || !state.canvas) {
      return;
    }
    syncSquaremapTerrain(); // 已门控:center/zoom 不动时几乎免费
    renderOverlayFrame();
    maybeStartShipmentLoop();
  }

  function scheduleRender() {
    if (state.animationFrame) {
      return;
    }
    state.animationFrame = requestAnimationFrame(() => {
      state.animationFrame = 0;
      renderCanvas();
    });
  }

  // 物流动画:限频(移动端 ~20fps,桌面 ~30fps),只重绘覆盖层(不碰 Leaflet);物流排空后自动停止回空闲。
  const SHIPMENT_FRAME_MS = isMobileMapDevice() ? 50 : 33;
  const FOLLOW_TERRAIN_SYNC_MS = 200;

  function shipmentsActive() {
    return !!state.layers.shipments && (state.shipments || []).length > 0;
  }

  function maybeStartShipmentLoop() {
    if (!shipmentsActive() || state.shipmentLoop) {
      return;
    }
    state.shipmentLoop = requestAnimationFrame(stepShipmentLoop);
  }

  function stepShipmentLoop(ts) {
    state.shipmentLoop = 0;
    if (!shipmentsActive()) {
      return;
    }
    if (!state.lastShipmentFrameMs || ts - state.lastShipmentFrameMs >= SHIPMENT_FRAME_MS) {
      state.lastShipmentFrameMs = ts;
      if (followSelectedShipment()) {
        renderOverlayFrame();
        // Leaflet's setView is the expensive part (Safari stutter), so the terrain catches up a few times a second.
        if (!state.lastFollowSyncMs || ts - state.lastFollowSyncMs >= FOLLOW_TERRAIN_SYNC_MS) {
          state.lastFollowSyncMs = ts;
          syncSquaremapTerrain();
        }
      } else {
        renderOverlayFrame();
      }
    }
    state.shipmentLoop = requestAnimationFrame(stepShipmentLoop);
  }

  /** Keeps the view centred on the followed vehicle; returns true when the centre moved. */
  function followSelectedShipment() {
    if (!state.followShipmentId) {
      return false;
    }
    const shipment = (state.shipments || []).find((entry) => entry.shippingOrderId === state.followShipmentId);
    if (!shipment) {
      state.followShipmentId = "";
      renderSelection(); // the vehicle arrived or vanished: the button goes back to "Follow vehicle"
      return false;
    }
    const live = shipmentLivePoint(shipment);
    if (!live) {
      return false;
    }
    const point = normalizePoint(live);
    if (Math.abs(point.x - state.center.x) < 1e-3 && Math.abs(point.z - state.center.z) < 1e-3) {
      return false;
    }
    state.center = { x: point.x, z: point.z };
    return true;
  }

  function nearestShipment(world, maxScreenDistance) {
    let best = null;
    let bestDistance = maxScreenDistance;
    for (const shipment of state.shipments || []) {
      const points = (shipment.points || []).map(normalizePoint);
      for (let index = 1; index < points.length; index += 1) {
        const distance = distanceToSegment(world, points[index - 1], points[index],
          state.zoom * state.canvasScaleX, state.zoom * state.canvasScaleY);
        if (distance < bestDistance) {
          bestDistance = distance;
          best = shipment;
        }
      }
    }
    return best;
  }

  // 命中最近的调试航点节点(屏幕坐标系内,像素距离)。返回富化后的节点对象(含 block/water/origin/segment),
  // 供 tooltip 显示;无 debug 节点的航线返回 null。
  function nearestRouteNode(screenX, screenY, maxScreenDistance) {
    let best = null;
    let bestDistance = maxScreenDistance;
    for (const shipment of state.shipments || []) {
      for (const raw of shipment.points || []) {
        if (!raw || typeof raw.origin !== "number" || raw.origin < 0) {
          continue;
        }
        const node = normalizePoint(raw);
        const screen = worldToScreen(node);
        const distance = Math.hypot(screen.x - screenX, screen.y - screenY);
        if (distance < bestDistance) {
          bestDistance = distance;
          best = node;
        }
      }
    }
    return best;
  }

  function distanceToSegment(point, a, b, scaleX = 1, scaleZ = 1) {
    const dx = (b.x - a.x) * scaleX;
    const dz = (b.z - a.z) * scaleZ;
    const px = (point.x - a.x) * scaleX;
    const pz = (point.z - a.z) * scaleZ;
    const lengthSq = dx * dx + dz * dz;
    if (lengthSq <= 0.0001) {
      return Math.hypot(px, pz);
    }
    const t = clamp((px * dx + pz * dz) / lengthSq, 0, 1);
    return Math.hypot(px - dx * t, pz - dz * t);
  }

  function hitTest(clientX, clientY) {
    const rect = state.canvas.getBoundingClientRect();
    const x = (clientX - rect.left) * (state.canvas.width / rect.width);
    const y = (clientY - rect.top) * (state.canvas.height / rect.height);
    const world = screenToWorld(x, y);
    if (state.layers.markets) {
      for (const marker of state.markets || []) {
        const screen = worldToScreen({ x: Number(marker.x) || 0, z: Number(marker.z) || 0 });
        if (Math.hypot(screen.x - x, screen.y - y) <= 14) {
          return { type: "market", data: marker, screen: { x, y } };
        }
      }
    }
    if (state.layers.shipments) {
      // 优先命中调试航点节点(圆点),tooltip 显示该点真实方块/水陆/段/来源;命中半径取节点大圆 + 余量。
      const node = nearestRouteNode(x, y, 8);
      if (node) {
        return { type: "routeNode", data: node, screen: { x, y } };
      }
      const shipment = nearestShipment(world, 9);
      if (shipment) {
        return { type: "shipment", data: shipment, screen: { x, y } };
      }
    }
    if (state.layers.territories) {
      const chunkX = Math.floor(world.x / CHUNK_SIZE);
      const chunkZ = Math.floor(world.z / CHUNK_SIZE);
      const territory = (state.territories || []).find((entry) => Number(entry.chunkX) === chunkX && Number(entry.chunkZ) === chunkZ);
      if (territory) {
        return { type: "territory", data: territory, screen: { x, y } };
      }
    }
    return null;
  }

  function hideBrokenFlag(image) {
    if (!image) {
      return;
    }
    const frame = image.closest(".map-tooltip-flag-frame, .map-detail-flag-frame");
    image.remove();
    if (frame && !frame.querySelector("span")) {
      const fallback = document.createElement("span");
      fallback.textContent = label("flag");
      frame.appendChild(fallback);
    }
  }

  function bindTooltipFlagFallback() {
    bindFlagFallbacks(state.tooltip);
  }

  function bindFlagFallbacks(root) {
    if (!root) {
      return;
    }
    root.querySelectorAll("[data-map-flag-image]").forEach((image) => {
      image.addEventListener("error", () => hideBrokenFlag(image), { once: true });
      if (image.complete && image.naturalWidth === 0) {
        hideBrokenFlag(image);
      }
    });
  }

  function renderTooltip(item, clientX, clientY) {
    if (!state.tooltip) {
      return;
    }
    if (!item) {
      state.tooltip.hidden = true;
      state.tooltip.innerHTML = "";
      return;
    }
    let html = "";
    if (item.type === "market") {
      const marker = item.data;
      html = `
        <strong>${escapeHtml(marker.marketName || marker.marketId || "-")}</strong>
        <span>${escapeHtml(label("town"))}: ${escapeHtml(marker.townName || "-")}</span>
        <span>X/Z ${Math.round(Number(marker.x) || 0)}, ${Math.round(Number(marker.z) || 0)}</span>
      `;
    } else if (item.type === "territory") {
      const territory = item.data;
      html = `
        <div class="map-tooltip-flag-frame">
          ${territory.flagUrl ? `<img data-map-flag-image src="${escapeHtml(territory.flagUrl)}" alt="${escapeHtml(label("flag"))}">` : `<span>${escapeHtml(label("flag"))}</span>`}
        </div>
        <strong>${escapeHtml(territory.nationName || "-")}</strong>
        <span>${escapeHtml(label("town"))}: ${escapeHtml(territory.townName || "-")}</span>
      `;
    } else if (item.type === "routeNode") {
      // 调试航点 tooltip:坐标 / 真实方块 / 水陆 / 段 / 来源,用于分清陆地上的点是寻路寻到陆还是样条过冲。
      const node = item.data;
      const segName = node.segment === 0 ? "起始段" : node.segment === 1 ? "中段" : node.segment === 2 ? "尾段" : "-";
      const originName = node.origin === 0 ? "原始节点(寻路)" : node.origin === 1 ? "插值点(样条)" : "-";
      const waterName = node.hasWater ? (node.water ? "水" : "陆") : "-";
      html = `
        <strong>调试航点</strong>
        <span>X/Z ${Math.round(node.x)}, ${Math.round(node.z)}</span>
        <span>方块: ${escapeHtml(node.block || "-")}</span>
        <span>水陆: ${escapeHtml(waterName)}</span>
        <span>段: ${escapeHtml(segName)}</span>
        <span>来源: ${escapeHtml(originName)}</span>
      `;
    } else {
      const shipment = item.data;
      const tipStuck = String(shipment.status || "").toUpperCase() === "STUCK";
      const tipTown = `${shipment.sourceTownName || shipment.sourceName || "-"} → ${shipment.targetTownName || shipment.targetName || "-"}`;
      const tipEta = Number(shipment.etaSeconds) || 0;
      const tipSpeed = Number(shipment.currentSpeed) || 0;
      html = `
        <strong>${escapeHtml(tipTown)}</strong>
        ${shipment.vehicleName ? `<span>${escapeHtml(label("vehicle"))}: ${escapeHtml(shipment.vehicleName)}${shipment.ownerName ? `（${escapeHtml(shipment.ownerName)}）` : ""}</span>` : ""}
        <span>${escapeHtml(label("mode"))}: ${escapeHtml(shipment.transportMode || "-")}</span>
        <span>${Math.round((Number(shipment.progressRatio) || 0) * 100)}%</span>
        ${tipEta > 0 ? `<span>${escapeHtml(label("eta"))}: ${escapeHtml(formatEta(tipEta))}</span>` : ""}
        ${tipSpeed > 0 ? `<span>${escapeHtml(label("speed"))}: ${escapeHtml(formatSpeed(tipSpeed))}</span>` : ""}
        ${tipStuck ? `<span style="color:#dc2626;font-weight:600;">${escapeHtml(label("statusStuck"))}</span>` : ""}
      `;
    }
    state.tooltip.innerHTML = html;
    bindTooltipFlagFallback();
    state.tooltip.hidden = false;
    const rootRect = state.root.getBoundingClientRect();
    state.tooltip.style.left = `${clientX - rootRect.left + 14}px`;
    state.tooltip.style.top = `${clientY - rootRect.top + 14}px`;
  }

  function bindCanvasEvents() {
    state.canvas.addEventListener("pointerdown", (event) => {
      clearPointerHover();
      if (state.followShipmentId) {
        state.followShipmentId = ""; // dragging the map takes the camera back
        renderSelection();
      }
      state.dragging = true;
      state.dragStart = {
        pointerId: event.pointerId,
        x: event.clientX,
        y: event.clientY,
        center: { ...state.center }
      };
      state.canvas.setPointerCapture(event.pointerId);
    });
    state.canvas.addEventListener("pointermove", (event) => {
      if (state.dragging && state.dragStart) {
        state.center = {
          x: state.dragStart.center.x - (event.clientX - state.dragStart.x) / state.zoom,
          z: state.dragStart.center.z - (event.clientY - state.dragStart.y) / state.zoom
        };
        updateCoordReadout(event.clientX, event.clientY);
        scheduleRender();
        return;
      }
      updateCoordReadout(event.clientX, event.clientY);
      if (isMobileMapDevice()) {
        state.hover = null;
        renderTooltip(null);
        return;
      }
      const now = typeof performance === "undefined" ? Date.now() : performance.now();
      if (now - state.lastPointerHitTestAt < DESKTOP_HOVER_THROTTLE_MS) {
        return;
      }
      state.lastPointerHitTestAt = now;
      const hit = hitTest(event.clientX, event.clientY);
      state.hover = hit ? { type: hit.type, data: hit.data } : null;
      renderTooltip(hit, event.clientX, event.clientY);
      renderSelection();
      scheduleRender();
    });
    state.canvas.addEventListener("pointerup", (event) => {
      state.dragging = false;
      state.dragStart = null;
      try {
        state.canvas.releasePointerCapture(event.pointerId);
      } catch (_) {
        // Pointer capture may already be released by the browser.
      }
    });
    state.canvas.addEventListener("pointerleave", () => {
      state.dragging = false;
      state.dragStart = null;
      state.hover = null;
      updateCoordReadout(null, null);
      renderTooltip(null);
      renderSelection();
      scheduleRender();
    });
    state.canvas.addEventListener("click", (event) => {
      const hit = hitTest(event.clientX, event.clientY);
      if (hit) {
        state.selected = { type: hit.type, data: hit.data };
        renderSelection();
        scheduleRender();
      }
    });
    state.canvas.addEventListener("dblclick", (event) => {
      const hit = hitTest(event.clientX, event.clientY);
      if (hit?.type === "market") {
        centerOn({ x: Number(hit.data.x) || 0, z: Number(hit.data.z) || 0 }, Math.max(state.zoom, 1.8));
      }
    });
    state.canvas.addEventListener("wheel", (event) => {
      event.preventDefault();
      const rect = state.canvas.getBoundingClientRect();
      const x = (event.clientX - rect.left) * (state.canvas.width / rect.width);
      const y = (event.clientY - rect.top) * (state.canvas.height / rect.height);
      zoomAt(x, y, event.deltaY < 0 ? 1.14 : 0.88);
    }, { passive: false });
  }

  async function loadAll(token) {
    const context = captureRequestContext();
    setPanelMessage(label("loading"));
    const focused = encodeURIComponent(state.focusedMarketId || "");
    const responses = await Promise.all([
      fetchJson(`/api/map/snapshot${focused ? `?focusedMarketId=${focused}` : ""}`),
      fetchJson("/api/map/markets"),
      fetchJson("/api/map/territories"),
      fetchOptionalJson("/api/map/shipments", { shipments: [] }),
      fetchOptionalJson("/api/map/world", { tellus: false })
    ]).catch((error) => {
      if (token !== state.mountedToken || !requestContextCurrent(context)) {
        return null;
      }
      throw error;
    });
    if (!responses || token !== state.mountedToken || !requestContextCurrent(context)) {
      return;
    }
    const [snapshot, markets, territories, shipments, world] = responses;
    state.snapshot = snapshot;
    applyWorldInfo(world);
    state.markets = markets.markets || [];
    state.territories = territories.territories || [];
    state.shipments = applyShipmentInterpolation(shipments.shipments || []);
    applySnapshotFocus();
    redrawSquaremapTerrain();
    renderLayerToggles();
    renderMarketList();
    renderShipments();
    renderSelection();
    scheduleRender();
  }

  async function loadShipmentHistory() {
    const context = captureRequestContext();
    state.historyFetchedAt = performance.now();
    const data = await fetchOptionalJson("/api/map/shipments/history", { history: state.shipmentHistory || [] });
    if (!requestContextCurrent(context)) {
      return;
    }
    noteGameTime(data);
    state.shipmentHistory = data.history || [];
    if (state.selected?.type === "history") {
      const fresh = state.shipmentHistory.find((entry) => entry.shippingOrderId === state.selected.data?.shippingOrderId);
      if (fresh) {
        state.selected = { type: "history", data: fresh };
      }
    }
    renderShipments();
    renderSelection();
  }

  async function loadShipments() {
    const context = captureRequestContext();
    const data = await fetchOptionalJson("/api/map/shipments", { shipments: state.shipments || [] });
    if (!requestContextCurrent(context)) {
      return;
    }
    noteGameTime(data);
    state.shipments = applyShipmentInterpolation(data.shipments || []);
    if (state.shipmentView === "history" && performance.now() - state.historyFetchedAt > HISTORY_REFRESH_MS) {
      loadShipmentHistory();
    }
    if (state.selected?.type === "shipment") {
      // keep the detail card on the latest data for the same shipment (progress, ETA, status)
      const fresh = state.shipments.find((entry) => entry.shippingOrderId === state.selected.data?.shippingOrderId);
      if (fresh) {
        state.selected = { type: "shipment", data: fresh };
      }
    }
    renderShipments();
    renderSelection();
    scheduleRender();
  }

  // 轻量轮询 snapshot:服务端每渲染一张瓦片 tileEpoch +1。检测到变化就清本地瓦片缓存重绘,
  // 这样 render 指令渲染完(radius/full/area)网页能自动拉到新瓦片,无需手动刷新页面。
  async function pollMapRefresh() {
    const context = captureRequestContext();
    const focused = encodeURIComponent(state.focusedMarketId || "");
    const next = await fetchOptionalJson(`/api/map/snapshot${focused ? `?focusedMarketId=${focused}` : ""}`, null);
    if (!next || !requestContextCurrent(context)) {
      return;
    }
    const prev = state.snapshot || {};
    const predictionChanged = (next.tellusRenderVersion || 0) !== (prev.tellusRenderVersion || 0);
    const changed = (next.tileEpoch || 0) !== (prev.tileEpoch || 0)
      || (next.renderVersion || 0) !== (prev.renderVersion || 0)
      || predictionChanged;
    if (!changed) {
      return;
    }
    state.snapshot = next;
    if (predictionChanged) {
      state.tellusLayer?.redraw();
    }
    softRefreshTiles(); // 软刷新:只 bump 版本号让可见瓦片按新 ?v 增量取新图,不清空整个缓存
  }

  function mount(root, options = {}) {
    captureRequestContext();
    if (state.root === root) {
      state.locale = options.locale || state.locale;
      state.focusedMarketId = options.focusedMarketId || state.focusedMarketId;
      renderToolbar();
      renderLayerToggles();
      renderMarketList();
      renderShipments();
      renderSelection();
      scheduleRender();
      return;
    }
    unmount();
    state.root = root;
    state.locale = options.locale || "zh-CN";
    state.apiBase = options.apiBase || "";
    state.focusedMarketId = options.focusedMarketId || root.getAttribute("data-focused-market") || "";
    state.panels.leftCollapsed = root.classList.contains("map-panel-left-collapsed");
    state.panels.rightCollapsed = root.classList.contains("map-panel-right-collapsed");
    state.mapElement = root.querySelector("[data-square-map]");
    state.canvas = root.querySelector("[data-market-map-canvas]");
    state.ctx = state.canvas?.getContext("2d") || null;
    state.tooltip = root.querySelector("[data-map-tooltip]");
    state.toolbar = root.querySelector("[data-map-toolbar]");
    state.layerPanel = root.querySelector("[data-map-layer-toggles]");
    state.marketList = root.querySelector("[data-map-market-list]");
    state.detailPanel = root.querySelector("[data-map-selection-detail]");
    state.shipmentList = root.querySelector("[data-map-shipment-list]");
    state.tileCache = new Map();
    state.missingTiles = new Map();
    state.hover = null;
    state.selected = null;
    state.mountedToken += 1;
    if (!state.canvas || !state.ctx) {
      return;
    }
    state.resizeObserver = new ResizeObserver(resizeCanvas);
    state.resizeObserver.observe(state.canvas);
    applyPerformanceClass();
    resizeCanvas();
    initSquaremapTerrain();
    bindPanelToggles();
    syncPanelClasses();
    bindCanvasEvents();
    renderToolbar();
    renderLayerToggles();
    setPanelMessage(label("loading"));
    const token = state.mountedToken;
    loadAll(token).catch((error) => {
      if (token !== state.mountedToken) {
        return;
      }
      setPanelMessage(error.message || label("loadFailed"));
      renderShipments();
      scheduleRender();
    });
    state.shipmentTimer = window.setInterval(loadShipments, SHIPMENT_REFRESH_MS);
    state.mapRefreshTimer = window.setInterval(pollMapRefresh, MAP_REFRESH_MS);
  }

  function unmount() {
    clearPrivateShipmentData();
    state.sessionToken = null;
    destroySquaremapTerrain();
    if (state.shipmentTimer) {
      window.clearInterval(state.shipmentTimer);
      state.shipmentTimer = 0;
    }
    if (state.mapRefreshTimer) {
      window.clearInterval(state.mapRefreshTimer);
      state.mapRefreshTimer = 0;
    }
    if (state.animationFrame) {
      cancelAnimationFrame(state.animationFrame);
      state.animationFrame = 0;
    }
    if (state.shipmentLoop) {
      cancelAnimationFrame(state.shipmentLoop);
      state.shipmentLoop = 0;
    }
    state.staticCanvas = null;
    state.staticKey = "";
    state.staticAnchor = null;
    state.lastTerrainSync = null;
    if (state.resizeObserver) {
      state.resizeObserver.disconnect();
      state.resizeObserver = null;
    }
    state.root = null;
    state.mapElement = null;
    state.canvas = null;
    state.canvasScaleX = 1;
    state.canvasScaleY = 1;
    state.ctx = null;
    state.tooltip = null;
    state.toolbar = null;
    state.layerPanel = null;
    state.marketList = null;
    state.detailPanel = null;
    state.shipmentList = null;
    state.dragging = false;
    state.dragStart = null;
    state.hover = null;
    state.selected = null;
    state.mountedToken += 1;
  }

  window.SailboatMarketMap = {
    mount,
    unmount,
    refresh() {
      const token = state.mountedToken;
      if (state.root) {
        loadAll(token).catch((error) => setPanelMessage(error.message || label("loadFailed")));
      }
    }
  };
  window.SailboatSquareMap = window.SailboatMarketMap;
})();
