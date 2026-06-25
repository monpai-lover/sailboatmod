(function () {
  "use strict";

  const TILE_SIZE = 512;
  const CHUNK_SIZE = 16;
  const MIN_ZOOM = 0.25;
  const MAX_ZOOM = 8;
  const MAX_TILE_ZOOM = 4;
  const SHIPMENT_REFRESH_MS = 2000;
  const MAP_REFRESH_MS = 5000;
  const DESKTOP_TILE_CACHE_LIMIT = 320;
  const MOBILE_TILE_CACHE_LIMIT = 96;
  const TILE_OVERDRAW_PIXELS = 1;
  const DESKTOP_MAX_DPR = 2;
  const MOBILE_MAX_DPR = 1.15;
  const DESKTOP_TILE_PADDING = 1;
  const MOBILE_TILE_PADDING = 0;
  const DESKTOP_HOVER_THROTTLE_MS = 32;
  const MISSING_TILE_RETRY_MS = 10_000;
  const TRANSPARENT_TILE_URL = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";

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
      flag: "Flag"
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
      flag: "国旗"
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
    localTileRevision: 0
  };

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

  function authHeaders() {
    const headers = new Headers();
    const token = localStorage.getItem("marketWebSessionToken") || "";
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
    const SquaremapTileLayer = L.TileLayer.extend({
      createTile(coords, done) {
        const tile = document.createElement("img");
        L.DomEvent.on(tile, "load", () => {
          if (tile.src && tile.src.startsWith("blob:")) {
            URL.revokeObjectURL(tile.src);
          }
          this._tileOnLoad(done, tile);
        });
        L.DomEvent.on(tile, "error", L.Util.bind(this._tileOnError, this, done, tile));
        if (this.options.crossOrigin || this.options.crossOrigin === "") {
          tile.crossOrigin = this.options.crossOrigin === true ? "" : this.options.crossOrigin;
        }
        tile.alt = "";
        tile.setAttribute("role", "presentation");
        fetch(this.getTileUrl(coords))
          .then((res) => {
            if (!res.ok) {
              this._tileOnError(done, tile, null);
              return;
            }
            res.blob().then((blob) => {
              tile.src = URL.createObjectURL(blob);
            });
          })
          .catch(() => this._tileOnError(done, tile, null));
        return tile;
      }
    });
    return new SquaremapTileLayer("", {
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
    syncSquaremapTerrain();
  }

  function syncSquaremapTerrain() {
    if (!state.leafletMap) {
      return;
    }
    state.leafletMap.invalidateSize(false);
    const leafletZoom = Math.log2(Math.max(1 / (1 << MAX_TILE_ZOOM), Number(state.zoom) || 1));
    state.leafletMap.setView(window.L.latLng(-state.center.z, state.center.x), leafletZoom, { animate: false });
  }

  function destroySquaremapTerrain() {
    if (state.leafletRetryTimer) {
      window.clearTimeout(state.leafletRetryTimer);
      state.leafletRetryTimer = 0;
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
      x: (point.x - state.center.x) * state.zoom + state.canvas.width / 2,
      y: (point.z - state.center.z) * state.zoom + state.canvas.height / 2
    };
  }

  function screenToWorld(x, y) {
    return {
      x: (x - state.canvas.width / 2) / state.zoom + state.center.x,
      z: (y - state.canvas.height / 2) / state.zoom + state.center.z
    };
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
    if (state.canvas.width !== width || state.canvas.height !== height) {
      state.canvas.width = width;
      state.canvas.height = height;
      state.ctx.setTransform(1, 0, 0, 1, 0, 0);
      state.ctx.imageSmoothingEnabled = false;
      syncSquaremapTerrain();
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
      <button type="button" data-map-action="focus">${escapeHtml(label("focus"))}</button>
      <button type="button" data-map-action="reset">${escapeHtml(label("reset"))}</button>
    `;
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

  function renderShipments() {
    if (!state.shipmentList) {
      return;
    }
    const shipments = state.shipments || [];
    if (!shipments.length) {
      state.shipmentList.innerHTML = `<div class="map-empty-note">${escapeHtml(label("noShipments"))}</div>`;
      return;
    }
    state.shipmentList.innerHTML = `
      <div class="map-list-heading">${escapeHtml(label("activeShipments"))}</div>
      ${shipments.map((shipment) => {
        const stuck = String(shipment.status || "").toUpperCase() === "STUCK";
        return `
        <button type="button" class="map-shipment-row" data-shipment-id="${escapeHtml(shipment.shippingOrderId || "")}"${stuck ? ' style="color:#dc2626;font-weight:600;"' : ""}>
          <span>${escapeHtml(`${shipment.sourceTownName || shipment.sourceName || "-"} → ${shipment.targetTownName || shipment.targetName || "-"}`)}</span>
          <small>${escapeHtml(shipment.transportMode || "-")} · ${Math.round((Number(shipment.progressRatio) || 0) * 100)}%${stuck ? ` · ${escapeHtml(label("statusStuck"))}` : ""}</small>
        </button>
      `;
      }).join("")}
    `;
    state.shipmentList.querySelectorAll("[data-shipment-id]").forEach((button) => {
      button.addEventListener("click", () => {
        const shipment = state.shipments.find((entry) => entry.shippingOrderId === button.getAttribute("data-shipment-id"));
        if (shipment) {
          state.selected = { type: "shipment", data: shipment };
          const points = (shipment.points || []).map(normalizePoint);
          if (points.length) {
            centerOn(points[Math.min(points.length - 1, Math.max(0, Number(shipment.completedPointCount) || 0))], Math.max(state.zoom, 1));
          }
          renderSelection();
        }
      });
    });
  }

  function renderSelection() {
    if (!state.detailPanel) {
      return;
    }
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
    const shipmentStuck = String(shipment.status || "").toUpperCase() === "STUCK";
    const statusText = shipmentStuck ? label("statusStuck") : (shipment.status || "-");
    const townLine = `${shipment.sourceTownName || shipment.sourceName || "-"} → ${shipment.targetTownName || shipment.targetName || "-"}`;
    const etaSec = Number(shipment.etaSeconds) || 0;
    const speed = Number(shipment.currentSpeed) || 0;
    const cargo = Array.isArray(shipment.cargo) ? shipment.cargo : [];
    const cargoHtml = cargo.length
      ? cargo.map((c) => `${Number(c.quantity) || 0}× ${escapeHtml(c.name || "-")}${c.recipient ? ` → ${escapeHtml(c.recipient)}` : ""}`).join("<br>")
      : "-";
    state.detailPanel.innerHTML = `
      <div class="map-detail-card">
        <p class="section-kicker">${escapeHtml(label("route"))}</p>
        <h3>${escapeHtml(townLine)}</h3>
        <dl>
          ${shipment.vehicleName ? `<dt>${escapeHtml(label("vehicle"))}</dt><dd>${escapeHtml(shipment.vehicleName)}</dd>` : ""}
          ${shipment.ownerName ? `<dt>${escapeHtml(label("owner"))}</dt><dd>${escapeHtml(shipment.ownerName)}</dd>` : ""}
          <dt>${escapeHtml(label("mode"))}</dt><dd>${escapeHtml(shipment.transportMode || "-")}</dd>
          <dt>${escapeHtml(label("status"))}</dt><dd${shipmentStuck ? ' style="color:#dc2626;font-weight:600;"' : ""}>${escapeHtml(statusText)}</dd>
          ${etaSec > 0 ? `<dt>${escapeHtml(label("eta"))}</dt><dd>${escapeHtml(formatEta(etaSec))}</dd>` : ""}
          ${speed > 0 ? `<dt>${escapeHtml(label("speed"))}</dt><dd>${escapeHtml(formatSpeed(speed))}</dd>` : ""}
          <dt>${escapeHtml(label("route"))}</dt><dd>${escapeHtml(shipment.sourceName || "-")} → ${escapeHtml(shipment.targetName || "-")}</dd>
          <dt>${escapeHtml(label("cargo"))}</dt><dd>${cargoHtml}</dd>
        </dl>
      </div>
    `;
  }

  function formatEta(seconds) {
    const s = Math.max(0, Math.round(Number(seconds) || 0));
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    if (m < 60) return `${m}m ${s % 60}s`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
  }

  function formatSpeed(blocksPerTick) {
    // 服务端 currentSpeed 单位为 方块/tick；换算成 方块/秒（×20）便于阅读。
    return `${((Number(blocksPerTick) || 0) * 20).toFixed(1)} m/s`;
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

  function centerOn(point, zoom) {
    state.center = { x: Number(point.x) || 0, z: Number(point.z) || 0 };
    state.zoom = clamp(Number(zoom) || state.zoom, MIN_ZOOM, MAX_ZOOM);
    renderToolbar();
    scheduleRender();
  }

  function zoomAt(screenX, screenY, ratio) {
    const before = screenToWorld(screenX, screenY);
    state.zoom = clamp(state.zoom * ratio, MIN_ZOOM, MAX_ZOOM);
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

  function drawTerritories(ctx) {
    if (!state.layers.territories) {
      return;
    }
    const territoryIndex = buildTerritoryIndex();
    for (const territory of state.territories || []) {
      const x = Number(territory.chunkX) * CHUNK_SIZE;
      const z = Number(territory.chunkZ) * CHUNK_SIZE;
      const rect = screenRectForWorldBounds(x, z, CHUNK_SIZE, CHUNK_SIZE);
      if (rect.right < -4 || rect.bottom < -4 || rect.x > state.canvas.width + 4 || rect.y > state.canvas.height + 4) {
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
      if (rect.right < -4 || rect.bottom < -4 || rect.x > state.canvas.width + 4 || rect.y > state.canvas.height + 4) {
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
            ? s.points[Math.min(Number(s.completedPointCount) || 0, s.points.length - 1)]
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
    for (const shipment of state.shipments || []) {
      const points = (shipment.points || []).map(normalizePoint);
      if (points.length < 2) {
        continue;
      }
      const completed = clamp(Number(shipment.completedPointCount) || 0, 0, points.length - 1);
      const isManual = !!shipment.manual;
      const isStuck = String(shipment.status || "").toUpperCase() === "STUCK";
      const doneColor = isManual ? "#f59e0b" : "#0ea5e9";
      const pendColor = isStuck ? "#dc2626" : (isManual ? "#b45309" : "#2563eb");
      drawRoute(ctx, points.slice(0, completed + 1), false, doneColor, 4);
      drawRoute(ctx, points.slice(completed), true, pendColor, isStuck ? 5 : 3);
      // debugroute 调试航线:在金线上叠加航点节点层(原始=大圆/插值=小圆,水=蓝描边/陆=红描边)。
      drawRouteNodes(ctx, points);
      drawShipmentVehicleIcon(ctx, shipment, points, completed, isStuck);
    }
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
    const size = clamp(18 + state.zoom * 2, 18, 28);
    const mode = String(shipment.transportMode || "").toUpperCase();
    // 脉动光晕：随时间正弦呼吸，卡死告警用红色、手动车暖色、调度车冷色。
    const pulse = 0.5 + 0.5 * Math.sin(performance.now() / 350);
    const haloColor = isStuck ? "#dc2626" : (shipment.manual ? "#f59e0b" : "#0ea5e9");
    ctx.save();
    ctx.translate(screen.x, screen.y);
    ctx.globalAlpha = isStuck ? (0.30 + 0.30 * pulse) : (0.18 + 0.16 * pulse);
    ctx.fillStyle = haloColor;
    ctx.beginPath();
    ctx.arc(0, 0, size * (0.7 + 0.25 * pulse), 0, Math.PI * 2);
    ctx.fill();
    ctx.globalAlpha = 1;
    ctx.rotate(angle);
    if (mode.includes("PORT") || mode.includes("WATER") || mode.includes("SEA") || mode.includes("SAIL") || mode.includes("BOAT")) {
      drawSailboatIcon(ctx, size);
    } else {
      drawCarriageIcon(ctx, size);
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

  function drawSailboatIcon(ctx, size) {
    const half = size / 2;
    ctx.save();
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.shadowColor = "rgba(15, 23, 42, 0.36)";
    ctx.shadowBlur = 8;
    ctx.shadowOffsetY = 2;
    ctx.fillStyle = "rgba(255, 255, 255, 0.94)";
    ctx.strokeStyle = "rgba(15, 23, 42, 0.78)";
    ctx.lineWidth = 1.3;
    ctx.beginPath();
    ctx.moveTo(half, 0);
    ctx.lineTo(half * 0.34, -half * 0.3);
    ctx.lineTo(-half * 0.48, -half * 0.3);
    ctx.lineTo(-half * 0.64, half * 0.2);
    ctx.lineTo(half * 0.26, half * 0.32);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    ctx.shadowColor = "transparent";
    ctx.beginPath();
    ctx.moveTo(-half * 0.1, -half * 0.75);
    ctx.lineTo(-half * 0.1, half * 0.42);
    ctx.strokeStyle = "rgba(15, 23, 42, 0.8)";
    ctx.lineWidth = 1.2;
    ctx.stroke();
    ctx.fillStyle = "rgba(37, 99, 235, 0.95)";
    ctx.strokeStyle = "rgba(255, 255, 255, 0.9)";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(-half * 0.04, -half * 0.68);
    ctx.lineTo(half * 0.44, -half * 0.08);
    ctx.lineTo(-half * 0.04, half * 0.08);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = "#0ea5e9";
    ctx.beginPath();
    ctx.moveTo(half * 0.84, 0);
    ctx.lineTo(half * 0.45, -half * 0.16);
    ctx.lineTo(half * 0.45, half * 0.16);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
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

  function drawCarriageIcon(ctx, size) {
    const half = size / 2;
    ctx.save();
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.shadowColor = "rgba(15, 23, 42, 0.36)";
    ctx.shadowBlur = 8;
    ctx.shadowOffsetY = 2;
    ctx.fillStyle = "rgba(217, 154, 0, 0.96)";
    ctx.strokeStyle = "rgba(75, 46, 10, 0.9)";
    ctx.lineWidth = 1.3;
    ctx.beginPath();
    roundedRectPath(ctx, -half * 0.58, -half * 0.34, half * 1.1, half * 0.68, 3);
    ctx.fill();
    ctx.stroke();
    ctx.shadowColor = "transparent";
    ctx.strokeStyle = "rgba(75, 46, 10, 0.82)";
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.moveTo(half * 0.52, 0);
    ctx.lineTo(half * 0.84, 0);
    ctx.stroke();
    ctx.fillStyle = "#111827";
    ctx.strokeStyle = "rgba(255, 255, 255, 0.86)";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(-half * 0.32, half * 0.4, half * 0.18, 0, Math.PI * 2);
    ctx.arc(half * 0.26, half * 0.4, half * 0.18, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = "#f59e0b";
    ctx.beginPath();
    ctx.moveTo(half * 0.9, 0);
    ctx.lineTo(half * 0.52, -half * 0.18);
    ctx.lineTo(half * 0.52, half * 0.18);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
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

  function drawMarkets(ctx) {
    if (!state.layers.markets) {
      return;
    }
    for (const marker of state.markets || []) {
      const screen = worldToScreen({ x: Number(marker.x) || 0, z: Number(marker.z) || 0 });
      if (screen.x < -32 || screen.y < -32 || screen.x > state.canvas.width + 32 || screen.y > state.canvas.height + 32) {
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
      const size = CHUNK_SIZE * state.zoom;
      ctx.save();
      ctx.strokeStyle = "#ffffff";
      ctx.lineWidth = 2;
      ctx.strokeRect(screen.x, screen.y, size, size);
      ctx.restore();
    }
  }

  function renderCanvas() {
    if (!state.ctx || !state.canvas) {
      return;
    }
    syncSquaremapTerrain();
    const ctx = state.ctx;
    ctx.clearRect(0, 0, state.canvas.width, state.canvas.height);
    if (!state.leafletMap) {
      ctx.fillStyle = "#000000";
      ctx.fillRect(0, 0, state.canvas.width, state.canvas.height);
      drawTerrain(ctx);
    }
    drawTerritories(ctx);
    drawShipments(ctx);
    drawMarkets(ctx);
    drawHoverHighlight(ctx);
    // 有在途运输时持续重绘，让实时插值动画连续推进
    if (state.layers.shipments && (state.shipments || []).length > 0 && !state.animationFrame) {
      state.animationFrame = requestAnimationFrame(() => {
        state.animationFrame = 0;
        renderCanvas();
      });
    }
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

  function nearestShipment(world, maxScreenDistance) {
    let best = null;
    let bestDistance = maxScreenDistance;
    for (const shipment of state.shipments || []) {
      const points = (shipment.points || []).map(normalizePoint);
      for (let index = 1; index < points.length; index += 1) {
        const distance = distanceToSegment(world, points[index - 1], points[index]) * state.zoom;
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

  function distanceToSegment(point, a, b) {
    const dx = b.x - a.x;
    const dz = b.z - a.z;
    const lengthSq = dx * dx + dz * dz;
    if (lengthSq <= 0.0001) {
      return Math.hypot(point.x - a.x, point.z - a.z);
    }
    const t = clamp(((point.x - a.x) * dx + (point.z - a.z) * dz) / lengthSq, 0, 1);
    const x = a.x + dx * t;
    const z = a.z + dz * t;
    return Math.hypot(point.x - x, point.z - z);
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
        const rect = state.canvas.getBoundingClientRect();
        const scaleX = state.canvas.width / rect.width;
        const scaleY = state.canvas.height / rect.height;
        state.center = {
          x: state.dragStart.center.x - ((event.clientX - state.dragStart.x) * scaleX) / state.zoom,
          z: state.dragStart.center.z - ((event.clientY - state.dragStart.y) * scaleY) / state.zoom
        };
        scheduleRender();
        return;
      }
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
    setPanelMessage(label("loading"));
    const focused = encodeURIComponent(state.focusedMarketId || "");
    const [snapshot, markets, territories, shipments] = await Promise.all([
      fetchJson(`/api/map/snapshot${focused ? `?focusedMarketId=${focused}` : ""}`),
      fetchJson("/api/map/markets"),
      fetchJson("/api/map/territories"),
      fetchOptionalJson("/api/map/shipments", { shipments: [] })
    ]);
    if (token !== state.mountedToken) {
      return;
    }
    state.snapshot = snapshot;
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

  async function loadShipments() {
    const data = await fetchOptionalJson("/api/map/shipments", { shipments: state.shipments || [] });
    state.shipments = applyShipmentInterpolation(data.shipments || []);
    renderShipments();
    renderSelection();
    scheduleRender();
  }

  // 轻量轮询 snapshot:服务端每渲染一张瓦片 tileEpoch +1。检测到变化就清本地瓦片缓存重绘,
  // 这样 render 指令渲染完(radius/full/area)网页能自动拉到新瓦片,无需手动刷新页面。
  async function pollMapRefresh() {
    const focused = encodeURIComponent(state.focusedMarketId || "");
    const next = await fetchOptionalJson(`/api/map/snapshot${focused ? `?focusedMarketId=${focused}` : ""}`, null);
    if (!next) {
      return;
    }
    const prev = state.snapshot || {};
    const changed = (next.tileEpoch || 0) !== (prev.tileEpoch || 0)
      || (next.renderVersion || 0) !== (prev.renderVersion || 0);
    if (!changed) {
      return;
    }
    state.snapshot = next;
    clearLocalTileImages(); // 清瓦片缓存 + bump localTileRevision → 重新拉新瓦片
  }

  function mount(root, options = {}) {
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
    if (state.resizeObserver) {
      state.resizeObserver.disconnect();
      state.resizeObserver = null;
    }
    state.root = null;
    state.mapElement = null;
    state.canvas = null;
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
