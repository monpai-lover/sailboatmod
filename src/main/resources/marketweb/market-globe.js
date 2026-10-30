// 3D 地球贸易视图:Tellus 真实地球世界里的终端与运输线路画在地球仪上。
// 依赖 maplibre-gl(按需加载)和 tellus-basemap.js 的坐标换算;app.js 通过 window.SailboatGlobe 挂载。
(function () {
  "use strict";

  const REFRESH_MS = 10000;
  const state = {
    map: null,
    container: null,
    failedContainer: null,
    options: null,
    token: 0,
    world: null,
    markets: [],
    shipments: [],
    marketMarkers: new Map(),
    shipMarkers: new Map(),
    popup: null,
    refreshTimer: 0,
    spinFrame: 0,
    spinning: false,
    engaged: false,
    focusedOnce: false,
    pendingShipmentId: ""
  };

  function t(key, vars) {
    return state.options && typeof state.options.t === "function" ? state.options.t(key, vars) : key;
  }

  function tellus() {
    return window.SailboatTellusBasemap || null;
  }

  function safeLocalStorage() {
    try {
      return window.localStorage;
    } catch (_) {
      return null;
    }
  }

  function basemap() {
    const api = tellus();
    const chosen = api.sourceById(api.basemapSourceFromStorage(safeLocalStorage()));
    // 高德等 GCJ-02 瓦片与 WGS84 坐标有偏移;地球视图改用不偏移的图源,标记才会落在正确位置。
    return chosen && !chosen.gcj02 ? chosen : (api.SOURCES.find((source) => !source.gcj02) || null);
  }

  function styleFor(source) {
    const style = {
      version: 8,
      projection: { type: "globe" },
      sources: {},
      layers: [{ id: "space", type: "background", paint: { "background-color": "#060a14" } }],
      sky: {
        "sky-color": "#0b1a33",
        "horizon-color": "#16365c",
        "atmosphere-blend": ["interpolate", ["linear"], ["zoom"], 0, 1, 5, 1, 7, 0]
      }
    };
    if (source) {
      const tiles = source.subdomains && source.subdomains.length
        ? source.subdomains.map((sub) => source.url.replace("{s}", sub))
        : [source.url];
      style.sources.imagery = { type: "raster", tiles, tileSize: 256, maxzoom: source.maxZoom || 18, attribution: source.attribution || "" };
      style.layers.push({
        id: "imagery",
        type: "raster",
        source: "imagery",
        paint: { "raster-saturation": -0.2, "raster-brightness-max": 0.92 }
      });
    }
    return style;
  }

  function worldScale() {
    return Number(state.world && state.world.worldScale) || 0;
  }

  function toLngLat(x, z) {
    const point = tellus().blockToLatLon(Number(x) || 0, Number(z) || 0, worldScale());
    return [point.lon, point.lat];
  }

  function emptyCollection() {
    return { type: "FeatureCollection", features: [] };
  }

  // 船的位置与平面地图一致:优先用服务端给的 current,否则取目标航点;都没有时按 progressRatio 沿线插值。
  function shipmentTrack(shipment) {
    const points = shipment.points || [];
    const coordinates = points.map((point) => toLngLat(point.x, point.z));
    if (coordinates.length < 2) {
      return null;
    }
    const indexValue = shipment.targetPointIndex != null ? shipment.targetPointIndex : shipment.completedPointCount;
    if (indexValue != null || shipment.current) {
      const index = Math.min(coordinates.length - 1, Math.max(0, Math.floor(Number(indexValue) || 0)));
      const position = shipment.current ? toLngLat(shipment.current.x, shipment.current.z) : coordinates[index];
      const travelled = coordinates.slice(0, Math.max(1, index)).concat([position]);
      return { coordinates, travelled, position };
    }
    return { coordinates, ...splitByRatio(coordinates, shipment.progressRatio) };
  }

  function splitByRatio(coordinates, ratio) {
    const lengths = [];
    let total = 0;
    for (let i = 1; i < coordinates.length; i++) {
      const length = Math.hypot(coordinates[i][0] - coordinates[i - 1][0], coordinates[i][1] - coordinates[i - 1][1]);
      lengths.push(length);
      total += length;
    }
    let remaining = Math.max(0, Math.min(1, Number(ratio) || 0)) * total;
    const travelled = [coordinates[0]];
    for (let i = 1; i < coordinates.length; i++) {
      const length = lengths[i - 1];
      if (remaining <= length || i === coordinates.length - 1) {
        const f = length ? Math.min(1, remaining / length) : 0;
        const [ax, ay] = coordinates[i - 1];
        const [bx, by] = coordinates[i];
        const position = [ax + (bx - ax) * f, ay + (by - ay) * f];
        travelled.push(position);
        return { travelled, position };
      }
      remaining -= length;
      travelled.push(coordinates[i]);
    }
    return { travelled, position: coordinates[coordinates.length - 1] };
  }

  function addLayers() {
    const map = state.map;
    map.addSource("routes", { type: "geojson", data: emptyCollection() });
    map.addSource("progress", { type: "geojson", data: emptyCollection() });
    map.addLayer({ id: "routes-glow", type: "line", source: "routes", paint: { "line-color": "#34d399", "line-width": 7, "line-opacity": 0.16, "line-blur": 5 } });
    map.addLayer({ id: "routes", type: "line", source: "routes", paint: { "line-color": "#7ef0c4", "line-width": 1.4, "line-dasharray": [2, 2] } });
    map.addLayer({ id: "progress", type: "line", source: "progress", paint: { "line-color": "#f5b13d", "line-width": 2.6 } });
  }

  // 键盘触发的点击(detail 为 0)才把焦点移进弹窗,鼠标点击不出现焦点框。
  function markerButton(className, onClick) {
    const element = document.createElement("button");
    element.type = "button";
    element.className = className;
    element.addEventListener("click", (event) => {
      event.stopPropagation();
      onClick(event.detail === 0);
    });
    return element;
  }

  // 让整个地球大致占满舞台短边:桌面约 2.2 级,手机约 1.2 级。
  function fitZoom(host) {
    const element = host || (state.map && state.map.getContainer());
    const size = element ? Math.min(element.clientWidth, element.clientHeight) : 0;
    return size > 0 ? Math.max(0.6, Math.min(2.6, 2.2 + Math.log2((0.84 * size) / 650))) : 1.4;
  }

  function syncMarkets() {
    const seen = new Set();
    const focusedId = state.options && state.options.focusedMarketId;
    state.markets.forEach((market) => {
      const id = String(market.marketId || "");
      if (!id) {
        return;
      }
      seen.add(id);
      let marker = state.marketMarkers.get(id);
      if (!marker) {
        const element = markerButton("globe-market", (viaKeyboard) => openMarketPopup(id, viaKeyboard));
        const dot = document.createElement("span");
        dot.className = "globe-market-dot";
        const label = document.createElement("span");
        label.className = "globe-market-label";
        element.append(dot, label);
        marker = new window.maplibregl.Marker({ element, anchor: "bottom" }).setLngLat(toLngLat(market.x, market.z)).addTo(state.map);
        state.marketMarkers.set(id, marker);
      } else {
        marker.setLngLat(toLngLat(market.x, market.z));
      }
      const element = marker.getElement();
      element.querySelector(".globe-market-label").textContent = market.marketName || id;
      element.setAttribute("aria-label", [market.marketName || id, market.townName].filter(Boolean).join(" · "));
      element.classList.toggle("is-focused", id === focusedId);
      element.setAttribute("data-market-id", id);
    });
    state.marketMarkers.forEach((marker, id) => {
      if (!seen.has(id)) {
        marker.remove();
        state.marketMarkers.delete(id);
      }
    });
  }

  function shipmentTitle(shipment) {
    const name = shipment.vehicleName || shipment.shippingOrderId;
    const route = [shipment.sourceName, shipment.targetName].filter(Boolean).join(" → ");
    return route ? `${name} · ${route}` : name;
  }

  function syncShipments() {
    const routes = [];
    const progress = [];
    const seen = new Set();
    state.shipments.forEach((shipment) => {
      const id = String(shipment.shippingOrderId || "");
      const track = id ? shipmentTrack(shipment) : null;
      if (!track) {
        return;
      }
      seen.add(id);
      routes.push({ type: "Feature", properties: { id }, geometry: { type: "LineString", coordinates: track.coordinates } });
      if (track.travelled.length > 1) {
        progress.push({ type: "Feature", properties: { id }, geometry: { type: "LineString", coordinates: track.travelled } });
      }
      let marker = state.shipMarkers.get(id);
      if (!marker) {
        const element = markerButton("globe-ship", (viaKeyboard) => focusShipment(id, viaKeyboard));
        marker = new window.maplibregl.Marker({ element }).setLngLat(track.position).addTo(state.map);
        state.shipMarkers.set(id, marker);
      } else {
        marker.setLngLat(track.position);
      }
      const element = marker.getElement();
      element.title = shipmentTitle(shipment);
      element.setAttribute("aria-label", element.title);
      element.setAttribute("data-shipping-order", id);
    });
    state.map.getSource("routes")?.setData({ type: "FeatureCollection", features: routes });
    state.map.getSource("progress")?.setData({ type: "FeatureCollection", features: progress });
    state.shipMarkers.forEach((marker, id) => {
      if (!seen.has(id)) {
        marker.remove();
        state.shipMarkers.delete(id);
      }
    });
  }

  function showPopup(lngLat, content, focus) {
    state.popup?.remove();
    state.popup = new window.maplibregl.Popup({ offset: 18, closeButton: true, maxWidth: "260px", className: "globe-popup-shell", focusAfterOpen: !!focus })
      .setLngLat(lngLat)
      .setDOMContent(content)
      .addTo(state.map);
  }

  function popupContent(titleText, lines) {
    const content = document.createElement("div");
    content.className = "globe-popup";
    const title = document.createElement("strong");
    title.textContent = titleText;
    content.append(title);
    lines.filter(Boolean).forEach((text) => {
      const line = document.createElement("p");
      line.textContent = text;
      content.append(line);
    });
    return content;
  }

  function engage() {
    state.engaged = true;
    stopSpin();
  }

  function openMarketPopup(marketId, viaKeyboard) {
    const market = state.markets.find((entry) => String(entry.marketId) === marketId);
    if (!market || !state.map) {
      return;
    }
    engage();
    const content = popupContent(market.marketName || marketId, [[market.townName, market.ownerName].filter(Boolean).join(" · ")]);
    const open = document.createElement("button");
    open.type = "button";
    open.textContent = t("globe_open_market");
    open.addEventListener("click", () => {
      state.popup?.remove();
      state.options?.onSelectMarket?.(marketId);
    });
    content.append(open);
    const lngLat = toLngLat(market.x, market.z);
    showPopup(lngLat, content, viaKeyboard);
    state.map.easeTo({ center: lngLat, duration: 800 });
  }

  // 订单页"在地图上查看船只"会走到这里:运单还没加载时先记下,刷新后再定位。
  function focusShipment(shippingOrderId, viaKeyboard) {
    const id = String(shippingOrderId || "");
    if (!id) {
      return false;
    }
    const shipment = state.shipments.find((entry) => String(entry.shippingOrderId) === id);
    const marker = state.shipMarkers.get(id);
    engage();
    if (!state.map || !shipment || !marker) {
      state.pendingShipmentId = id;
      return false;
    }
    state.pendingShipmentId = "";
    const ratio = Number(shipment.progressRatio);
    const eta = Number(shipment.etaSeconds);
    const formatEta = state.options && state.options.formatEta;
    showPopup(marker.getLngLat(), popupContent(shipment.vehicleName || id, [
      [shipment.sourceName, shipment.targetName].filter(Boolean).join(" → "),
      Number.isFinite(ratio) && ratio > 0 ? t("globe_ship_progress", { percent: Math.round(Math.min(1, ratio) * 100) }) : "",
      eta > 0 && typeof formatEta === "function" ? t("globe_ship_eta", { time: formatEta(eta) }) : ""
    ]), viaKeyboard);
    state.shipMarkers.forEach((entry, key) => entry.getElement().classList.toggle("is-focused", key === id));
    state.map.flyTo({ center: marker.getLngLat(), zoom: Math.max(state.map.getZoom(), fitZoom() + 0.8), duration: 1600 });
    return true;
  }

  function focusMarket(marketId, animate) {
    const market = state.markets.find((entry) => String(entry.marketId) === marketId);
    if (!market || !state.map) {
      return false;
    }
    const center = toLngLat(market.x, market.z);
    if (animate) {
      state.map.flyTo({ center, zoom: Math.max(state.map.getZoom(), fitZoom()), duration: 1600 });
    } else {
      state.map.jumpTo({ center, zoom: fitZoom() });
    }
    return true;
  }

  async function refresh(token) {
    const options = state.options;
    if (!options || typeof options.fetchJson !== "function") {
      return;
    }
    try {
      const [world, markets, shipments] = await Promise.all([
        state.world ? Promise.resolve(state.world) : options.fetchJson("/api/map/world"),
        options.fetchJson("/api/map/markets"),
        // 运单接口需要登录;游客只看终端。
        options.includeShipments ? options.fetchJson("/api/map/shipments").catch(() => ({ shipments: [] })) : Promise.resolve({ shipments: [] })
      ]);
      if (token !== state.token || !state.map) {
        return;
      }
      state.world = world;
      if (!tellus().shouldShowBasemap(world)) {
        setMessage(t("globe_not_tellus"));
        return;
      }
      state.markets = (markets && markets.markets) || [];
      state.shipments = (shipments && shipments.shipments) || [];
      syncMarkets();
      syncShipments();
      setMessage("");
      if (!state.focusedOnce) {
        state.focusedOnce = true;
        if (focusMarket(options.focusedMarketId, false)) {
          state.engaged = true;
        }
      }
      if (state.pendingShipmentId) {
        focusShipment(state.pendingShipmentId);
      }
    } catch (error) {
      if (token === state.token) {
        setMessage(error && error.message ? error.message : t("globe_failed"));
      }
    }
  }

  function setMessage(text) {
    const note = state.container && state.container.querySelector(".globe-message");
    if (note) {
      note.textContent = text;
      note.hidden = !text;
    }
  }

  // 没有聚焦目标时缓慢自转,一碰地图就停;系统要求减少动效时不转。
  function startSpin() {
    if (state.engaged || state.spinning || (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches)) {
      return;
    }
    state.spinning = true;
    let last = performance.now();
    const step = (now) => {
      if (!state.spinning || !state.map) {
        return;
      }
      const delta = Math.min(64, now - last);
      last = now;
      if (!state.map.isMoving()) {
        const center = state.map.getCenter();
        state.map.setCenter([center.lng + delta * 0.0025, center.lat]);
      }
      state.spinFrame = requestAnimationFrame(step);
    };
    state.spinFrame = requestAnimationFrame(step);
  }

  function stopSpin() {
    state.spinning = false;
    cancelAnimationFrame(state.spinFrame);
  }

  function mount(container, options) {
    if (state.container === container && state.map) {
      const previousFocus = state.options && state.options.focusedMarketId;
      state.options = options;
      if (options.focusedMarketId && options.focusedMarketId !== previousFocus) {
        syncMarkets();
        engage();
        focusMarket(options.focusedMarketId, true);
      }
      return true;
    }
    // 同一个容器创建失败过(浏览器不支持 WebGL 等)就不在每次重绘时重试。
    if (state.failedContainer === container) {
      return false;
    }
    unmount();
    const maplibregl = window.maplibregl;
    if (!maplibregl || !tellus()) {
      return false;
    }
    state.container = container;
    state.options = options;
    state.token += 1;
    const token = state.token;
    const host = container.querySelector(".globe-canvas") || container;
    let map;
    try {
      map = new maplibregl.Map({
        container: host,
        style: styleFor(basemap()),
        center: [110, 22],
        zoom: fitZoom(host),
        minZoom: 0.6,
        maxZoom: 12,
        attributionControl: { compact: true },
        fadeDuration: 0
      });
    } catch (error) {
      setMessage(t("globe_failed"));
      state.failedContainer = container;
      state.container = null;
      return false;
    }
    state.map = map;
    map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), "top-right");
    map.on("load", () => {
      if (token !== state.token) {
        return;
      }
      addLayers();
      refresh(token).then(() => {
        if (token === state.token) {
          startSpin();
        }
      });
    });
    ["mousedown", "touchstart", "wheel", "dragstart"].forEach((type) => map.on(type, engage));
    state.refreshTimer = window.setInterval(() => refresh(token), REFRESH_MS);
    return true;
  }

  function unmount() {
    stopSpin();
    window.clearInterval(state.refreshTimer);
    state.refreshTimer = 0;
    state.popup?.remove();
    state.popup = null;
    state.marketMarkers.forEach((marker) => marker.remove());
    state.shipMarkers.forEach((marker) => marker.remove());
    state.marketMarkers.clear();
    state.shipMarkers.clear();
    if (state.map) {
      state.map.remove();
    }
    state.map = null;
    state.container = null;
    state.markets = [];
    state.shipments = [];
    state.engaged = false;
    state.focusedOnce = false;
    state.pendingShipmentId = "";
    state.token += 1;
  }

  window.SailboatGlobe = {
    mount,
    unmount,
    focusShipment,
    refresh() {
      if (state.map) {
        refresh(state.token);
      }
    },
    isMounted() {
      return !!state.map;
    },
    debugState() {
      return {
        markets: state.markets.length,
        shipments: state.shipments.length,
        marketMarkers: state.marketMarkers.size,
        shipMarkers: state.shipMarkers.size,
        spinning: state.spinning,
        projection: state.map && state.map.getProjection ? state.map.getProjection() : null,
        center: state.map ? state.map.getCenter().toArray() : null,
        zoom: state.map ? state.map.getZoom() : null
      };
    }
  };
})();
