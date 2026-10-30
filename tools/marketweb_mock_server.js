const http = require("http");
const fs = require("fs");
const path = require("path");

if (process.env.MARKETWEB_PREVIEW_DEBUG === "1") {
  console.log("MarketWeb preview boot.");
}

const root = path.resolve(__dirname, "..");
const staticRoot = path.join(root, "src/main/resources/marketweb");
const requestedPort = Number(process.argv[2] || process.env.PORT || 8787);

const now = Date.now();

function candlePoints(base, step, count, intervalMs) {
  return Array.from({ length: count }, (_, index) => {
    const wave = Math.sin(index / 2.4) * step * 1.8;
    const drift = index * step;
    const open = Math.max(1, Math.round(base + drift + wave));
    const close = Math.max(1, Math.round(open + Math.cos(index / 3) * step * 1.2));
    const high = Math.max(open, close) + Math.round(step * 2 + (index % 4) * step);
    const low = Math.max(1, Math.min(open, close) - Math.round(step * 1.4));
    return {
      bucketAt: now - ((count - index) * intervalMs),
      openUnitPrice: open,
      averageUnitPrice: Math.round((open + close) / 2),
      minUnitPrice: low,
      maxUnitPrice: high,
      closeUnitPrice: close,
      volume: 80 + (index % 7) * 24 + index * 3,
      tradeCount: 3 + (index % 5)
    };
  });
}

function analytics(displayName, scopeType, scopeKey, base, step) {
  return {
    displayName,
    scopeType,
    scopeKey,
    points: Array.from({ length: 18 }, (_, index) => ({
      bucketAt: now - ((18 - index) * 3600_000),
      value: Math.round(base + index * step + Math.sin(index / 2) * step * 2),
      tradeCount: 12 + index
    }))
  };
}

const markets = [
  {
    marketId: "crimea-harbor",
    marketName: "Crimea Harbor Exchange",
    ownerName: "EasyLifeGaming",
    townName: "Crimea Harbor",
    dimensionId: "minecraft:overworld",
    position: "x: 1840, z: -612",
    loaded: true,
    canManage: true
  },
  {
    marketId: "constantinople-bazaar",
    marketName: "Constantinople Grand Bazaar",
    ownerName: "MonpaiTradeCo",
    townName: "Constantinople",
    dimensionId: "minecraft:overworld",
    position: "x: -420, z: 905",
    loaded: true,
    canManage: false
  },
  {
    marketId: "western-relay",
    marketName: "Western Relay Market",
    ownerName: "RoadGuild",
    townName: "Westwatch",
    dimensionId: "minecraft:overworld",
    position: "x: 2760, z: 310",
    loaded: false,
    canManage: false
  }
];

const previewItems = {
  "minecraft:oak_log": { displayName: "Oak Log", category: "wood", suggestedUnitPrice: 18 },
  "minecraft:iron_ingot": { displayName: "Iron Ingot", category: "metal", suggestedUnitPrice: 92 },
  "minecraft:wheat": { displayName: "Wheat", category: "crop", suggestedUnitPrice: 9 },
  "minecraft:stone": { displayName: "Stone", category: "other", suggestedUnitPrice: 6 },
  "minecraft:diamond": { displayName: "Diamond", category: "gems", suggestedUnitPrice: 360 },
  "sailboatmod:sailboat": { displayName: "Sailboat", category: "utility", suggestedUnitPrice: 14500 },
  "sailboatmod:route_book": { displayName: "Route Book", category: "utility", suggestedUnitPrice: 720 }
};

const baseDetail = {
  canManage: true,
  marketName: "Crimea Harbor Exchange",
  ownerName: "EasyLifeGaming",
  townName: "Crimea Harbor",
  linkedDock: true,
  linkedDockName: "Crimea Harbor Pier A",
  linkedWarehouseName: "Crimea Private Warehouse",
  pendingCredits: 128940,
  stockpileTotalUnits: 2847,
  openDemandUnits: 1190,
  netBalance: 324500,
  chartCapabilities: { inflation: true },
  listings: [
    {
      listingId: "lst-oak-1",
      commodityKey: "minecraft:oak_log",
      itemName: "Oak Log",
      category: "wood",
      rarity: 0,
      availableCount: 640,
      reservedCount: 96,
      unitPrice: 18,
      sellerName: "Crimea Lumber Yard",
      sellerNote: "Stable dockside supply",
      createdAt: now - 5400_000
    },
    {
      listingId: "lst-iron-1",
      commodityKey: "minecraft:iron_ingot",
      itemName: "Iron Ingot",
      category: "metal",
      rarity: 1,
      availableCount: 384,
      reservedCount: 32,
      unitPrice: 92,
      sellerName: "Harbor Foundry",
      sellerNote: "Bulk order preferred",
      createdAt: now - 3600_000
    },
    {
      listingId: "lst-wheat-1",
      commodityKey: "minecraft:wheat",
      itemName: "Wheat",
      category: "crop",
      rarity: 0,
      availableCount: 920,
      reservedCount: 180,
      unitPrice: 9,
      sellerName: "Crimea Granary",
      sellerNote: "Fresh harvest",
      createdAt: now - 2600_000
    },
    {
      listingId: "lst-boat-1",
      commodityKey: "sailboatmod:sailboat",
      itemName: "Sailboat",
      category: "utility",
      rarity: 3,
      availableCount: 3,
      reservedCount: 0,
      unitPrice: 14500,
      sellerName: "Dockwright",
      sellerNote: "Fitted for coastal cargo",
      createdAt: now - 7200_000
    }
  ],
  storageEntries: [
    {
      index: 0,
      commodityKey: "minecraft:oak_log",
      itemName: "Oak Log",
      category: "wood",
      rarity: 0,
      quantity: 1280,
      suggestedUnitPrice: 18,
      minAllowedUnitPrice: 14,
      maxAllowedUnitPrice: 24,
      status: "warehouse",
      detail: "Private warehouse slot A1"
    },
    {
      index: 1,
      commodityKey: "minecraft:iron_ingot",
      itemName: "Iron Ingot",
      category: "metal",
      rarity: 1,
      quantity: 512,
      suggestedUnitPrice: 94,
      minAllowedUnitPrice: 80,
      maxAllowedUnitPrice: 112,
      status: "warehouse",
      detail: "Private warehouse slot B4"
    },
    {
      index: 2,
      commodityKey: "sailboatmod:route_book",
      itemName: "Route Book",
      category: "utility",
      rarity: 2,
      quantity: 26,
      suggestedUnitPrice: 720,
      minAllowedUnitPrice: 0,
      maxAllowedUnitPrice: 0,
      status: "free pricing",
      detail: "No configured price model"
    }
  ],
  buyOrderEntries: [
    {
      orderId: "buy-stone-1",
      commodityKey: "minecraft:stone",
      displayName: "Stone",
      quantity: 960,
      minPriceBp: -300,
      maxPriceBp: 450,
      buyerName: "RoadGuild",
      status: "open",
      createdAt: now - 8200_000
    }
  ],
  commodityBuyBooks: [
    {
      commodityKey: "minecraft:oak_log",
      displayName: "Oak Log",
      entries: [
        { buyerName: "Shipyard", quantity: 400, maxPriceBp: 220, createdAt: now - 1500_000 },
        { buyerName: "Builder Union", quantity: 240, maxPriceBp: 90, createdAt: now - 6200_000 }
      ]
    },
    {
      commodityKey: "minecraft:iron_ingot",
      displayName: "Iron Ingot",
      entries: [
        { buyerName: "Armory", quantity: 180, maxPriceBp: 360, createdAt: now - 2700_000 }
      ]
    },
    {
      commodityKey: "minecraft:wheat",
      displayName: "Wheat",
      entries: [
        { buyerName: "Tavern Quarter", quantity: 550, maxPriceBp: 120, createdAt: now - 3300_000 }
      ]
    }
  ],
  candleSeries: [
    { commodityKey: "minecraft:oak_log", displayName: "Oak Log", timeframe: "1h", points: candlePoints(16, 1, 36, 3600_000) },
    { commodityKey: "minecraft:oak_log", displayName: "Oak Log", timeframe: "1d", points: candlePoints(15, 2, 30, 86_400_000) },
    { commodityKey: "minecraft:iron_ingot", displayName: "Iron Ingot", timeframe: "1h", points: candlePoints(78, 3, 36, 3600_000) },
    { commodityKey: "minecraft:wheat", displayName: "Wheat", timeframe: "1h", points: candlePoints(8, 1, 36, 3600_000) },
    { commodityKey: "sailboatmod:sailboat", displayName: "Sailboat", timeframe: "1h", points: candlePoints(13200, 140, 36, 3600_000) }
  ],
  impactSnapshots: [
    { commodityKey: "minecraft:oak_log", referenceUnitPrice: 18, liquidityScore: 82, inventoryPressureBp: -120, buyPressureBp: 220, volatilityBp: 80 },
    { commodityKey: "minecraft:iron_ingot", referenceUnitPrice: 94, liquidityScore: 74, inventoryPressureBp: 60, buyPressureBp: 360, volatilityBp: 140 },
    { commodityKey: "minecraft:wheat", referenceUnitPrice: 9, liquidityScore: 61, inventoryPressureBp: -220, buyPressureBp: 120, volatilityBp: 60 },
    { commodityKey: "sailboatmod:sailboat", referenceUnitPrice: 14500, liquidityScore: 37, inventoryPressureBp: -80, buyPressureBp: 0, volatilityBp: 260 }
  ],
  analyticsSeries: [
    analytics("Global Market Index", "MARKET_INDEX", "global", 10000, 64),
    analytics("Utility Category Index", "CATEGORY_INDEX", "utility", 2400, 28),
    analytics("Wood Category Index", "CATEGORY_INDEX", "wood", 1480, 18),
    analytics("Consumer Price Index", "MACRO_INDEX", "cpi", 100, 1),
    analytics("Outstanding Loans", "MACRO_INDEX", "outstanding_loans", 42000, 620)
  ],
  sourceOrders: [
    {
      label: "Crimea Harbor to Constantinople",
      sourceDockName: "Crimea Harbor Pier A",
      targetDockName: "Constantinople Dock",
      quantity: 320,
      status: "READY",
      dispatchOptions: [
        {
          terminalKind: "PORT",
          terminalLabel: "Pier A: Sailboat Dawn Ledger",
          available: true,
          availability: "ready",
          routeName: "Black Sea Coastal Route",
          carrierName: "Dawn Ledger",
          distanceMeters: 2840,
          etaSeconds: 740,
          detail: "Sea route uses an existing dock route."
        },
        {
          terminalKind: "POST_STATION",
          terminalLabel: "Harbor Post Station",
          available: true,
          availability: "ready",
          routeName: "North Road Convoy",
          carrierName: "Cart 07",
          distanceMeters: 3540,
          etaSeconds: 920,
          detail: "Land route uses the post station network."
        }
      ]
    }
  ]
};

function marketDetail(id) {
  if (id === "constantinople-bazaar") {
    return {
      ...baseDetail,
      canManage: false,
      marketName: "Constantinople Grand Bazaar",
      ownerName: "MonpaiTradeCo",
      townName: "Constantinople",
      linkedDockName: "Constantinople Dock",
      linkedWarehouseName: "Bazaar Warehouse",
      pendingCredits: 0,
      netBalance: 892000
    };
  }
  if (id === "western-relay") {
    return {
      ...baseDetail,
      canManage: false,
      marketName: "Western Relay Market",
      ownerName: "RoadGuild",
      townName: "Westwatch",
      linkedDock: false,
      linkedDockName: "",
      linkedWarehouseName: "Westwatch Road Warehouse",
      pendingCredits: 0,
      netBalance: 184200
    };
  }
  return baseDetail;
}

function json(exchange, status, body) {
  const payload = Buffer.from(JSON.stringify(body), "utf8");
  exchange.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store"
  });
  exchange.end(payload);
}

function text(exchange, status, body, type = "text/plain; charset=utf-8") {
  const payload = Buffer.from(body, "utf8");
  exchange.writeHead(status, {
    "content-type": type,
    "cache-control": "no-store"
  });
  exchange.end(payload);
}

function serveStatic(exchange, pathname) {
  const safePath = pathname === "/" ? "/index.html" : pathname;
  const filePath = path.normalize(path.join(staticRoot, safePath));
  if (!filePath.startsWith(staticRoot)) {
    text(exchange, 403, "Forbidden");
    return;
  }
  fs.readFile(filePath, (error, data) => {
    if (error) {
      const hasExtension = !!path.extname(filePath);
      if (!hasExtension) {
        serveStatic(exchange, "/");
        return;
      }
      text(exchange, 404, "Not found");
      return;
    }
    const ext = path.extname(filePath).toLowerCase();
    const type = {
      ".html": "text/html; charset=utf-8",
      ".css": "text/css; charset=utf-8",
      ".js": "application/javascript; charset=utf-8",
      ".json": "application/json; charset=utf-8",
      ".svg": "image/svg+xml",
      ".ico": "image/x-icon",
      ".png": "image/png"
    }[ext] || "application/octet-stream";
    exchange.writeHead(200, { "content-type": type, "cache-control": "no-store" });
    exchange.end(data);
  });
}

// ---- world map preview data: three moving routes (sea, road, rail) and one stuck carriage ----
const mapRoutes = [
  { id: "demo-airship", mode: "AIRSHIP", status: "IN_TRANSIT", from: "Tema Mooring", to: "Lomé Mooring", fromTown: "Tema", toTown: "Lomé",
    vehicle: "Cloud Trader", owner: "PreviewPlayer", period: 130, speed: 0.5,
    flight: { worldY: 148, flightState: "CLIMBING", executionMode: "PHYSICAL" },
    cargo: [{ name: "Wool", quantity: 160, recipient: "Lomé Market" }],
    points: [[-210, -220], [-120, -200], [10, -190], [170, -170]] },
  { id: "demo-helicopter", mode: "HELICOPTER", status: "IN_TRANSIT", from: "Tema Helipad", to: "Kumasi Helipad", fromTown: "Tema", toTown: "Kumasi",
    vehicle: "Rescue Rotor", owner: "Kofi", period: 65, speed: 0.85,
    flight: { worldY: 116.5, flightState: "APPROACHING", executionMode: "PHYSICAL" },
    cargo: [{ name: "Medicine", quantity: 32, recipient: "Kumasi Clinic" }],
    points: [[-210, -220], [-200, -30], [-200, 160], [-140, 220]] },
  { id: "demo-airplane", mode: "AIRPLANE", status: "IN_TRANSIT", from: "Tema Runway", to: "Lomé Runway", fromTown: "Tema", toTown: "Lomé",
    vehicle: "Cargo Wing 1", owner: "Ada", period: 55, speed: 1.8,
    flight: { worldY: 256, flightState: "CRUISING", executionMode: "VIRTUAL" },
    cargo: [{ name: "Redstone", quantity: 192, recipient: "Lomé Workshop" }],
    points: [[-210, -220], [-100, -60], [80, 120], [180, 220]] },
  { id: "demo-sea", mode: "PORT", status: "SAILING", from: "Tema Harbour", to: "Lomé Docks", fromTown: "Tema", toTown: "Lomé",
    vehicle: "Aurora", owner: "PreviewPlayer", period: 90, speed: 0.42,
    cargo: [{ name: "Wheat", quantity: 64, recipient: "Lomé Market" }, { name: "Salt", quantity: 32, recipient: "" }],
    points: [[-180, 120], [-120, 60], [-40, 40], [40, 70], [120, 40], [190, -20]] },
  { id: "demo-road", mode: "LAND", status: "IN_TRANSIT", from: "Accra Post", to: "Kumasi Post", fromTown: "Accra", toTown: "Kumasi",
    vehicle: "Wagon 3", owner: "Kofi", period: 70, speed: 0.21,
    cargo: [{ name: "Iron ingot", quantity: 24, recipient: "Kumasi Smithy" }],
    points: [[-160, -140], [-90, -110], [-30, -130], [30, -90], [80, -110], [140, -70]] },
  { id: "demo-rail", mode: "RAIL", status: "IN_TRANSIT", from: "Lagos Yard", to: "Ibadan Yard", fromTown: "Lagos", toTown: "Ibadan",
    vehicle: "Express 7", owner: "Ada", period: 50, speed: 0.6,
    cargo: [{ name: "Coal", quantity: 128, recipient: "" }, { name: "Glass", quantity: 16, recipient: "Ibadan Market" }, { name: "Brick", quantity: 64, recipient: "" }],
    points: [[-200, 10], [-100, 10], [-20, -30], [60, -30], [150, 0], [220, 0]] },
  { id: "demo-stuck", mode: "LAND", status: "STUCK", from: "Cape Coast", to: "Elmina", fromTown: "Cape Coast", toTown: "Elmina",
    vehicle: "Cart 1", owner: "PreviewPlayer", period: 0, speed: 0, fixed: 0.45,
    cargo: [{ name: "Fish", quantity: 40, recipient: "Elmina Market" }],
    points: [[60, 150], [100, 130], [150, 140], [200, 110]] }
];

function routePosition(points, ratio) {
  const lengths = [];
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    const d = Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]);
    lengths.push(d);
    total += d;
  }
  let target = total * ratio;
  for (let i = 0; i < lengths.length; i++) {
    if (target <= lengths[i] || i === lengths.length - 1) {
      const t = lengths[i] === 0 ? 0 : Math.min(1, target / lengths[i]);
      return {
        completed: i,
        x: points[i][0] + (points[i + 1][0] - points[i][0]) * t,
        z: points[i][1] + (points[i + 1][1] - points[i][1]) * t,
        total
      };
    }
    target -= lengths[i];
  }
  return { completed: 0, x: points[0][0], z: points[0][1], total };
}

function mapShipments() {
  const now = Date.now() / 1000;
  return mapRoutes.map((route) => {
    const ratio = route.period ? (now % route.period) / route.period : route.fixed;
    const pos = routePosition(route.points, ratio);
    const vehicleKind = ({ AIRSHIP: "airship", HELICOPTER: "helicopter", AIRPLANE: "airplane", RAIL: "train", PORT: "sailboat" })[route.mode] || "carriage";
    return {
      shippingOrderId: route.id,
      label: route.vehicle,
      transportMode: route.mode,
      vehicleKind,
      status: route.status,
      ...(route.flight ? { flight: route.flight } : {}),
      sourceName: route.from,
      targetName: route.to,
      sourceTownName: route.fromTown,
      targetTownName: route.toTown,
      vehicleName: route.vehicle,
      ownerName: route.owner,
      etaSeconds: route.period ? Math.round((1 - ratio) * route.period) : 0,
      currentSpeed: route.speed,
      cargo: route.cargo,
      points: route.points.map(([x, z]) => ({ x, z })),
      // like the game: the waypoint the vehicle is heading to (autopilotTargetIndex)
      targetPointIndex: Math.min(route.points.length - 1, pos.completed + 1),
      completedPointCount: Math.min(route.points.length - 1, pos.completed + 1),
      progressRatio: ratio,
      manual: route.id === "demo-road",
      current: { x: pos.x, z: pos.z },
      events: demoEvents(route, ratio)
    };
  });
}

/** Mock server time in ticks (20 per second), like the game's overworld game time. */
function gameTime() {
  return Math.floor(Date.now() / 50);
}

function demoEvents(route, ratio) {
  const [x0, z0] = route.points[0];
  const [x1, z1] = route.points[Math.floor(route.points.length / 2)];
  const events = [{ kind: "DEPARTED", gameTime: gameTime() - 20 * 60 * 9, x: x0, z: z0 }];
  if (route.status === "STUCK") {
    events.push({ kind: "STUCK", gameTime: gameTime() - 20 * 60 * 2, x: x1, z: z1 });
  } else if (ratio > 0.5) {
    events.push({ kind: "STUCK", gameTime: gameTime() - 20 * 60 * 6, x: x1, z: z1 });
    events.push({ kind: route.mode === "PORT" ? "SAILING" : "IN_TRANSIT", gameTime: gameTime() - 20 * 60 * 5, x: x1, z: z1 });
  }
  return events;
}

/** A few finished shipments for the History view. */
function mapShipmentHistory() {
  const now = gameTime();
  return mapShipments().slice(0, 3).map((shipment, i) => {
    const outcome = ["DELIVERED", "CLAIMED", "FAILED"][i];
    const ended = now - 20 * 60 * (12 + i * 55);
    const last = shipment.points[shipment.points.length - 1];
    return Object.assign({}, shipment, {
      shippingOrderId: `${shipment.shippingOrderId}-done`,
      status: outcome,
      progressRatio: outcome === "FAILED" ? 0.6 : 1,
      etaSeconds: 0,
      currentSpeed: 0,
      endedGameTime: ended,
      events: [
        { kind: "DEPARTED", gameTime: ended - 20 * 60 * 20, x: shipment.points[0].x, z: shipment.points[0].z },
        { kind: outcome, gameTime: ended, x: last.x, z: last.z }
      ]
    });
  });
}

const mapAirports = [
  { airportId: "tema-airport", name: "Tema Airport", dimensionId: "minecraft:overworld", x: -210, y: 70, z: -220,
    facilities: [
      { id: "runway-09", type: "RUNWAY", start: {x:-210,y:70,z:-220}, end:{x:-90,y:70,z:-220}, width:12, clearanceHeight:24, availability:"not_checked" },
      { id: "pad-1", type: "HELIPAD", start: {x:-210,y:70,z:-190}, end:{x:-210,y:70,z:-190}, width:14, clearanceHeight:32, availability:"not_checked" },
      { id: "mast-1", type: "MOORING", start: {x:-240,y:70,z:-220}, end:{x:-240,y:70,z:-220}, width:20, clearanceHeight:48, availability:"not_checked" }
    ] },
  { airportId: "lome-airport", name: "Lomé Airport", dimensionId: "minecraft:overworld", x: 180, y: 72, z: 220,
    facilities: [{ id: "runway-27", type: "RUNWAY", start: {x:180,y:72,z:220}, end:{x:60,y:72,z:220}, width:12, clearanceHeight:24, availability:"not_checked" }] }
];

function handleMap(pathname, response) {
  if (pathname === "/api/map/airports") {
    json(response, 200, { ok: true, airports: mapAirports });
    return true;
  }
  if (pathname === "/api/map/snapshot") {
    json(response, 200, { dimensionId: "minecraft:overworld", unknownColor: "#000000", tileSize: 512, lod: 1, renderVersion: 1,
      tileEpoch: 0, defaultFocus: { x: 0, z: 0 }, territoryRevision: 0, marketRevision: 0 });
    return true;
  }
  if (pathname === "/api/map/markets") {
    json(response, 200, { markets: [] });
    return true;
  }
  if (pathname === "/api/map/territories") {
    json(response, 200, { territories: [] });
    return true;
  }
  if (pathname === "/api/map/shipments/history") {
    json(response, 200, { ok: true, history: mapShipmentHistory(), gameTime: gameTime() });
    return true;
  }
  if (pathname === "/api/map/shipments") {
    json(response, 200, { ok: true, shipments: mapShipments(), gameTime: gameTime() });
    return true;
  }
  if (pathname === "/api/map/world") {
    json(response, 200, { tellus: false, worldScale: 0, tellusTileMaxZoom: -1 });
    return true;
  }
  if (pathname === "/api/map/render/status") {
    json(response, 200, { running: false });
    return true;
  }
  if (pathname.startsWith("/api/map/")) {
    text(response, 404, "Tile not found");
    return true;
  }
  return false;
}

function handle(request, response) {
  const url = new URL(request.url, "http://127.0.0.1");
  const pathname = decodeURIComponent(url.pathname);

  if (handleMap(pathname, response)) {
    return;
  }

  if (pathname === "/config.json") {
    json(response, 200, {
      uiTitle: "Sailboat Market Demo",
      commandText: "/marketweb token",
      autoRefreshSeconds: 0,
      defaultPurchaseQuantity: 1,
      defaultListingQuantity: 32,
      defaultPriceAdjustmentBp: 0,
      defaultBuyOrderQuantity: 64,
      defaultBuyOrderMinPriceBp: -500,
      defaultBuyOrderMaxPriceBp: 650,
      showShippingPanel: true,
      showPriceCharts: true,
      showTownEconomySummary: true
    });
    return;
  }

  if (pathname === "/api/debug/version") {
    json(response, 200, { resourceVersion: "demo-preview" });
    return;
  }

  if (pathname === "/api/auth/password-login" || pathname === "/api/auth/token-login") {
    json(response, 200, { sessionToken: "demo-token", accountUsername: "demo" });
    return;
  }

  if (pathname === "/api/session/me") {
    json(response, 200, {
      playerName: "PreviewPlayer",
      online: true,
      accountBound: true,
      accountUsername: "demo",
      webResourceVersion: "demo-preview"
    });
    return;
  }

  if (pathname === "/api/markets") {
    json(response, 200, { markets });
    return;
  }

  if (pathname === "/api/items/resolve") {
    const itemId = String(url.searchParams.get("itemId") || url.searchParams.get("commodityKey") || "").trim();
    const item = previewItems[itemId];
    if (!item) {
      json(response, 404, { ok: false, error: "item_not_found", message: "Item not found" });
      return;
    }
    json(response, 200, {
      ok: true,
      commodityKey: itemId,
      itemId,
      displayName: item.displayName,
      category: item.category,
      suggestedUnitPrice: item.suggestedUnitPrice
    });
    return;
  }

  const marketMatch = pathname.match(/^\/api\/markets\/([^/]+)(?:\/.*)?$/);
  if (marketMatch) {
    json(response, 200, marketDetail(marketMatch[1]));
    return;
  }

  if (pathname === "/api/icons/batch") {
    const missing = url.searchParams.getAll("commodityKey");
    json(response, 200, { icons: {}, missing });
    return;
  }

  if (pathname === "/api/icons") {
    text(response, 404, "icon missing");
    return;
  }

  serveStatic(response, pathname);
}

function listen(port) {
  const server = http.createServer(handle);
  server.on("error", (error) => {
    if (error.code === "EADDRINUSE" && port < requestedPort + 20) {
      listen(port + 1);
      return;
    }
    throw error;
  });
  server.listen(port, "127.0.0.1", () => {
    console.log(`MarketWeb preview: http://127.0.0.1:${port}/`);
  });
}

if (process.env.MARKETWEB_PREVIEW_DEBUG === "1") {
  console.log("MarketWeb preview data ready.");
}

listen(requestedPort);
