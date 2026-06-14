const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const indexHtml = fs.readFileSync(path.join(root, "src/main/resources/marketweb/index.html"), "utf8");
const appJs = fs.readFileSync(path.join(root, "src/main/resources/marketweb/app.js"), "utf8");
const appCss = fs.readFileSync(path.join(root, "src/main/resources/marketweb/app.css"), "utf8");
const mapJsPath = path.join(root, "src/main/resources/marketweb/map.js");
const mapJs = fs.existsSync(mapJsPath) ? fs.readFileSync(mapJsPath, "utf8") : "";

function fail(message) {
  console.error(`marketweb frontend check failed: ${message}`);
  process.exitCode = 1;
}

function count(pattern) {
  const matches = appJs.match(pattern);
  return matches ? matches.length : 0;
}

function expectContains(source, needle, message) {
  if (!source.includes(needle)) {
    fail(message);
  }
}

function expectCount(name, pattern, expected) {
  const actual = count(pattern);
  if (actual !== expected) {
    fail(`${name} should appear ${expected} time(s), found ${actual}`);
  }
}

const elsBlock = appJs.match(/const\s+els\s*=\s*\{([\s\S]*?)\n\};/);
if (!elsBlock) {
  fail("static DOM reference block `els` is missing");
} else {
  const ids = [...elsBlock[1].matchAll(/querySelector\("#([^"]+)"\)/g)].map((match) => match[1]);
  ids.forEach((id) => {
    if (!indexHtml.includes(`id="${id}"`)) {
      fail(`static DOM id #${id} is referenced by app.js but missing from index.html`);
    }
  });
}

expectContains(appJs, '"map"', "MAP route must remain registered");
expectContains(appJs, '"demand"', "dedicated buy-order route must remain registered");
expectContains(appJs, "marketWebBrowseOrderView", "browse page should persist the buy/demand order view");
expectContains(appJs, "renderBrowseOrderModeSwitch", "browse page should expose a buy/demand segmented switch");
expectContains(appJs, 'data-browse-order-view="purchase"', "browse page should include a purchase-orders view");
expectContains(appJs, 'data-browse-order-view="demand"', "browse page should include a demand-orders view");
expectContains(appJs, "renderPurchaseCommodityCard", "purchase browse view should render purchase cards separately");
expectContains(appJs, "renderDemandCommodityCard", "demand browse view should render demand cards separately");
expectContains(appJs, "renderCatalogFallbackIconSvg", "catalog filter icons should fall back to stable SVG symbols instead of text glyphs");
expectContains(appJs, "catalog-fallback-svg", "catalog filter fallback icons must use an SVG icon class");
expectContains(appJs, '"batch-missing"', "batch icon misses should still fall through to single-icon probing before final fallback");
expectContains(appJs, 'routeButton("demand"', "dedicated buy-order page should route through the commodity detail view");
expectContains(appJs, 'workspaceButton("map"', "MAP workspace tab must remain visible");
expectContains(appJs, 'workspaceButton("demand"', "dedicated buy-order workspace tab must remain visible");
expectContains(appJs, "pureDemandPage", "dedicated buy-order page should hide dispatch-only controls");
expectContains(appJs, "renderMarketMapPlaceholder", "MAP placeholder page must remain wired");
expectContains(appJs, "SailboatMarketMap.mount", "MAP page must mount the live canvas module");
expectContains(appJs, "marketWebAccessPanelOpenV2", "login panel should use the compact-layout access state key");
expectContains(appJs, "/api/items/resolve", "buy order form should resolve arbitrary registered item ids");
expectContains(appJs, "buy-order-preview", "buy order form should render an item preview panel");
expectContains(appJs, "attachBuyOrderItemResolver", "buy order item id input should hydrate icon/name before submitting");
expectContains(appJs, "commodityIconCache.set(item.commodityKey", "buy order resolver should cache the resolved item icon immediately");
expectContains(appJs, "buy-order-unit-price", "buy order form should use an actual unit price field");
expectContains(appJs, "buyOrderPriceToBp", "buy order form should derive bp from the actual unit price");
expectContains(appJs, "buy_order_quantity_label", "buy order quantity field should have a clear label");
expectContains(appJs, "buy_order_unit_price", "buy order price field should have a clear label");
expectContains(appJs, "fallbackCopyText", "copy command should fall back when Clipboard API is blocked");
expectContains(appJs, "document.execCommand(\"copy\")", "copy fallback should use the legacy selection path for non-secure contexts");
expectContains(appCss, ".market-map-reserved", "MAP placeholder styles must remain present");
expectContains(appCss, ".market-map-workspace", "MAP live workspace styles must remain present");
expectContains(appCss, ".market-map-canvas", "MAP canvas styles must remain present");
expectContains(appCss, ".map-tooltip-flag-frame", "MAP flag tooltip frame must remain fixed-size");
expectContains(appCss, ".buy-order-preview", "buy order item preview styles must remain present");
expectContains(indexHtml, 'lang="zh-CN"', "marketweb should default to the Chinese locale");
expectContains(indexHtml, "/map.js", "marketweb must load the live map script");
expectContains(mapJs, "window.SailboatMarketMap", "map.js must expose SailboatMarketMap");
expectContains(mapJs, "/api/map/tile/lod_1/", "map.js must request cached map tiles only");
expectContains(mapJs, "setLineDash", "map.js must draw dashed remaining shipment traces");
expectContains(mapJs, "drawShipmentVehicleIcon", "map.js must draw a directional vehicle icon on active shipment traces");
expectContains(mapJs, "drawSailboatIcon", "map.js must include a sailboat-shaped shipment icon");
expectContains(mapJs, "drawCarriageIcon", "map.js must include a carriage-shaped shipment icon");
expectContains(mapJs, "shipmentIconPose", "map.js must derive vehicle icon position and route direction");

expectCount("renderSession", /function\s+renderSession\s*\(/g, 1);
expectCount("renderMarkets", /function\s+renderMarkets\s*\(/g, 1);
expectCount("renderDetail", /function\s+renderDetail\s*\(/g, 1);
expectCount("renderCommodityDetailPage", /function\s+renderCommodityDetailPage\s*\(/g, 1);

if (appJs.includes(" 路 ")) {
  fail("garbled separator ` 路 ` should not appear in marketweb app.js");
}

if (!mapJs) {
  fail("map.js must exist");
}

if (indexHtml.includes("\uFFFD") || appJs.includes("\uFFFD") || appCss.includes("\uFFFD") || mapJs.includes("\uFFFD")) {
  fail("replacement characters should not appear in marketweb resources");
}

if (indexHtml.includes("锟") || appJs.includes("锟") || appCss.includes("锟") || mapJs.includes("锟")) {
  fail("mojibake marker `锟` should not appear in marketweb resources");
}

if (/letter-spacing\s*:\s*-/.test(appCss)) {
  fail("negative letter spacing should not be used in marketweb CSS");
}

if (/font-size\s*:\s*clamp\([^;]*vw/.test(appCss)) {
  fail("font sizes should not scale directly with viewport width");
}

if (appCss.includes("radial-gradient")) {
  fail("decorative radial gradients should not be used in the trading terminal UI");
}

if (/\.section-copy\s+p\s*\{/.test(appCss)) {
  fail("section-copy paragraph rules must not target section-kicker labels");
}

if (/addEventListener\("mouse(?:enter|leave)"[\s\S]{0,700}renderDetailPreservingScroll/.test(appJs)) {
  fail("catalog hover must not re-render the full market detail view");
}

if (!appCss.includes("--chart-control-height") || !appCss.includes(".chart-check input")) {
  fail("chart controls must use compact market-terminal sizing");
}

if (!appCss.includes("--surface-0") || !appCss.includes("--trade-up") || !appCss.includes("--trade-down")) {
  fail("trading-dashboard design tokens are missing from app.css");
}

if (!appCss.includes("/* Liquid glass light redesign */")
  || !appCss.includes("--glass-surface")
  || !appCss.includes("color-scheme: light")
  || !appCss.includes("backdrop-filter: saturate(180%) blur(20px)")) {
  fail("marketweb must use the approved light liquid-glass visual system");
}

if (!appCss.includes("--glass-caustic")
  || !appCss.includes("--glass-rim")
  || !appCss.includes("box-shadow: var(--glass-rim), var(--glass-shadow-soft)")
  || !appCss.includes("background-attachment: fixed")) {
  fail("liquid-glass theme must include visible refraction, rim lighting, and a fixed backdrop");
}

if (!indexHtml.includes("liquid-glass-distortion")
  || !indexHtml.includes("feTurbulence")
  || !appCss.includes("url(#liquid-glass-distortion)")
  || !appCss.includes("@keyframes liquidGlassFlow")
  || !appCss.includes("animation: liquidGlassFlow")) {
  fail("liquid-glass theme must use an SVG distortion filter with a reduced-motion-safe flowing refraction layer");
}
