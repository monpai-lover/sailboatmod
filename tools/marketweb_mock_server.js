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

function handle(request, response) {
  const url = new URL(request.url, "http://127.0.0.1");
  const pathname = decodeURIComponent(url.pathname);

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
