const state = {
  sessionToken: localStorage.getItem("marketWebSessionToken") || "",
  locale: preferredLocale(),
  session: null,
  webResourceVersion: "",
  markets: [],
  selectedMarketId: "",
  selectedCommodityKey: "",
  activeProductTab: "all",
  browseOrderView: localStorage.getItem("marketWebBrowseOrderView") || "purchase",
  browseLayout: localStorage.getItem("marketWebBrowseLayout") === "heatmap" ? "heatmap" : "grid",
  watchlist: new Set((() => {
    try {
      const stored = JSON.parse(localStorage.getItem("marketWebWatchlist") || "[]");
      return Array.isArray(stored) ? stored.filter((key) => typeof key === "string") : [];
    } catch (_) {
      return [];
    }
  })()),
  mobileFiltersOpen: false,
  mapMode: localStorage.getItem("marketWebMapMode") === "globe" ? "globe" : "flat",
  mapWorld: null,
  mapWorldLoading: false,
  pendingShipmentFocus: "",
  accessPanelOpen: localStorage.getItem("marketWebAccessPanelOpenV2") === "1",
  walletCollapsed: localStorage.getItem("marketWebWalletCollapsed") !== "0",
  activeChartTimeframe: localStorage.getItem("marketWebChartTimeframe") || "1h",
  chartIndicators: loadStoredJson("marketWebChartIndicators", {
    ma5: true,
    ma20: true,
    volume: true
  }),
  chartFlags: loadStoredJson("marketWebChartFlags", {
    logScale: false,
    inflationAdjusted: false
  }),
  commodityQuery: "",
  marketQuery: "",
  accountUsername: localStorage.getItem("marketWebAccountUsername") || "",
  catalogSort: loadStoredJson("marketWebCatalogSort", {
    mode: "quantity",
    direction: "desc"
  }),
  catalogFilters: loadStoredJson("marketWebCatalogFilters", {
    categoryGroup: "all",
    category: "all",
    rarity: "all",
    minPrice: "",
    maxPrice: "",
    watchOnly: false
  }),
  catalogExpandedGroup: "all",
  catalogHoverGroup: "all",
  detail: null,
  aggregatedListings: [],
  aggregatedLoaded: false,
  aggregatedAuthenticated: false,
  aggregatedViewerTownId: "",
  aggregatedSelectedCommodityKey: "",
  aggregatedDetail: null,
  aggregatedDetailKey: "",
  aggregatedDetailLoaded: false,
  aggregatedDetailLoading: false,
  aggregatedDetailTab: "browse",
  mineColoniesItems: [],
  mineColoniesLoaded: false,
  mineColoniesMarketId: "",
  selectedDispatchOrderIndex: 0,
  selectedDispatchTerminal: localStorage.getItem("marketWebDispatchTerminal") || "PORT",
  settings: {
    uiTitle: "Sailboat Market | Monpai 在线市场",
    commandText: "/marketweb token",
    autoRefreshSeconds: 0,
    defaultPurchaseQuantity: 1,
    defaultListingQuantity: 1,
    defaultPriceAdjustmentBp: 0,
    defaultBuyOrderQuantity: 1,
    defaultBuyOrderMinPriceBp: -1000,
    defaultBuyOrderMaxPriceBp: 1000,
    showShippingPanel: true,
    showPriceCharts: true,
    showTownEconomySummary: true
  },
  status: "",
  error: ""
};

const chartState = {
  chart: null,
  candleSeries: null,
  volumeSeries: null,
  maShortSeries: null,
  maLongSeries: null,
  marketIndexChart: null,
  categoryIndexChart: null,
  cpiChart: null,
  loansChart: null,
  resizeObserver: null,
  marketResizeObserver: null,
  categoryResizeObserver: null,
  cpiResizeObserver: null,
  loansResizeObserver: null,
  lastFailure: null
};

const commodityIconCache = new Map();
const commodityIconRequests = new Map();
const commodityIconBatchRequests = new Map();
const COMMODITY_ICON_BATCH_SIZE = 24;
let buyOrderResolveTimer = 0;
let buyOrderResolveSequence = 0;
const PAGE_ROUTES = new Set(["browse", "inventory", "sell", "buy", "demand", "chart", "index", "map", "my_orders", "all"]);
const BROWSE_ORDER_VIEWS = new Set(["purchase", "demand"]);
const CATALOG_SORT_MODES = new Set(["name", "price", "time", "quantity"]);
const CATALOG_CATEGORY_ORDER = ["wood", "luxury", "food", "fishery", "livestock", "ore", "gems", "metal", "tools", "spices", "plant", "crop", "textile", "material", "construction", "building", "decoration", "furniture", "lighting", "flooring", "landscaping", "machinery", "mob_drop", "alchemy", "magic", "nether", "end", "treasure", "redstone", "utility", "weapon", "armor", "other"];
const CATALOG_CATEGORY_GROUP_ORDER = ["resource", "building", "trade", "combat", "mystic", "other"];
const CATALOG_CATEGORY_GROUPS = {
  resource: ["wood", "ore", "gems", "metal", "plant", "crop", "fishery", "livestock", "mob_drop"],
  building: ["construction", "building", "decoration", "furniture", "lighting", "flooring", "landscaping", "redstone", "machinery"],
  trade: ["food", "spices", "textile", "material", "luxury", "utility"],
  combat: ["weapon", "armor", "tools"],
  mystic: ["alchemy", "magic", "nether", "end", "treasure"],
  other: ["other"]
};
const CATALOG_CATEGORY_GROUP_ICON_ITEMS = {
  all: "minecraft:chest",
  resource: "minecraft:iron_ore",
  building: "minecraft:bricks",
  trade: "minecraft:gold_nugget",
  combat: "minecraft:diamond_sword",
  mystic: "minecraft:enchanting_table",
  other: "minecraft:bundle"
};
const CATALOG_CATEGORY_ICON_ITEMS = {
  all: "minecraft:chest",
  wood: "minecraft:oak_log",
  luxury: "minecraft:golden_apple",
  food: "minecraft:bread",
  fishery: "minecraft:cod",
  livestock: "minecraft:leather",
  ore: "minecraft:iron_ore",
  gems: "minecraft:diamond",
  metal: "minecraft:iron_ingot",
  tools: "minecraft:iron_pickaxe",
  spices: "minecraft:sugar",
  plant: "minecraft:oak_sapling",
  crop: "minecraft:wheat",
  textile: "minecraft:white_wool",
  material: "minecraft:book",
  construction: "minecraft:stone_bricks",
  decoration: "minecraft:flower_pot",
  furniture: "minecraft:bookshelf",
  lighting: "minecraft:lantern",
  flooring: "minecraft:polished_andesite",
  landscaping: "minecraft:grass_block",
  machinery: "minecraft:piston",
  mob_drop: "minecraft:ender_pearl",
  alchemy: "minecraft:glass_bottle",
  magic: "minecraft:enchanted_book",
  building: "minecraft:bricks",
  nether: "minecraft:netherrack",
  end: "minecraft:end_stone",
  treasure: "minecraft:heart_of_the_sea",
  redstone: "minecraft:redstone",
  utility: "minecraft:compass",
  weapon: "minecraft:trident",
  armor: "minecraft:shield",
  other: "minecraft:bundle"
};
const CATALOG_RARITY_ORDER = [0, 1, 2, 3, 4, 5];

const els = {
  statusLive: document.querySelector("#status-live"),
  brandKicker: document.querySelector("#brand-kicker"),
  heroTitle: document.querySelector("#hero-title"),
  heroSubtitle: document.querySelector("#hero-subtitle"),
  localeLabel: document.querySelector("#locale-label"),
  modeLabel: document.querySelector("#mode-label"),
  modeValue: document.querySelector("#mode-value"),
  sessionLabel: document.querySelector("#session-label"),
  sessionPill: document.querySelector("#session-pill"),
  flowLabel: document.querySelector("#flow-label"),
  flowValue: document.querySelector("#flow-value"),
  localeSelect: document.querySelector("#locale-select"),
  themeToggle: document.querySelector("#theme-toggle"),
  workspaceNav: document.querySelector("#workspace-nav"),
  terminalToggle: document.querySelector("#terminal-toggle"),
  terminalToggleLabel: document.querySelector("#terminal-toggle-label"),
  terminalCurrent: document.querySelector("#terminal-current"),
  terminalPanel: document.querySelector("#terminal-panel"),
  accessToggle: document.querySelector("#access-toggle"),
  accessToggleLabel: document.querySelector("#access-toggle-label"),
  sessionStatusInline: document.querySelector("#session-status-inline"),
  walletDock: document.querySelector("#wallet-dock"),
  accessPanel: document.querySelector("#access-panel"),
  portalKicker: document.querySelector("#portal-kicker"),
  authTitle: document.querySelector("#auth-title"),
  authSubtitle: document.querySelector("#auth-subtitle"),
  commandLabel: document.querySelector("#command-label"),
  commandPreview: document.querySelector("#command-preview"),
  copyCommandButton: document.querySelector("#copy-command-button"),
  fastSigninKicker: document.querySelector("#fast-signin-kicker"),
  connectPlayerTitle: document.querySelector("#connect-player-title"),
  chatCaptureLabel: document.querySelector("#chat-capture-label"),
  chatCapture: document.querySelector("#chat-capture"),
  accountUsername: document.querySelector("#account-username"),
  accountPassword: document.querySelector("#account-password"),
  token: document.querySelector("#login-token"),
  bindButton: document.querySelector("#bind-button"),
  loginButton: document.querySelector("#login-button"),
  tokenHelper: document.querySelector("#token-helper"),
  sessionStatus: document.querySelector("#session-status"),
  marketNetworkKicker: document.querySelector("#market-network-kicker"),
  browseMarketsTitle: document.querySelector("#browse-markets-title"),
  refreshMarkets: document.querySelector("#refresh-markets"),
  allListingsNav: document.querySelector("#all-listings-nav"),
  marketSearchLabel: document.querySelector("#market-search-label"),
  marketSearch: document.querySelector("#market-search"),
  marketSidebarHint: document.querySelector("#market-sidebar-hint"),
  marketSummary: document.querySelector("#market-summary"),
  marketList: document.querySelector("#market-list"),
  marketDetail: document.querySelector("#market-detail")
};

function loadStoredJson(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) {
      return fallback;
    }
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? { ...fallback, ...parsed } : fallback;
  } catch (_) {
    return fallback;
  }
}

function persistChartPreferences() {
  localStorage.setItem("marketWebChartTimeframe", state.activeChartTimeframe || "1h");
  localStorage.setItem("marketWebChartIndicators", JSON.stringify(state.chartIndicators));
  localStorage.setItem("marketWebChartFlags", JSON.stringify(state.chartFlags));
}

function persistCatalogSort() {
  localStorage.setItem("marketWebCatalogSort", JSON.stringify(state.catalogSort));
}

function persistCatalogFilters() {
  localStorage.setItem("marketWebCatalogFilters", JSON.stringify(state.catalogFilters));
}

function setAccessPanelOpen(open) {
  state.accessPanelOpen = !!open;
  localStorage.setItem("marketWebAccessPanelOpenV2", state.accessPanelOpen ? "1" : "0");
  localStorage.removeItem("marketWebAccessPanelOpen");
  if (els.accessPanel) {
    els.accessPanel.hidden = !state.accessPanelOpen;
  }
  if (els.accessToggle) {
    els.accessToggle.setAttribute("aria-expanded", state.accessPanelOpen ? "true" : "false");
  }
  if (els.accessToggleLabel) {
    els.accessToggleLabel.textContent = t(state.accessPanelOpen ? "access_panel_open" : "access_panel_closed");
  }
}

function normalizePageRoute(value) {
  const route = String(value || "").trim().toLowerCase();
  return PAGE_ROUTES.has(route) ? route : "browse";
}

function normalizeBrowseOrderView(value) {
  const view = String(value || "").trim().toLowerCase();
  return BROWSE_ORDER_VIEWS.has(view) ? view : "purchase";
}

function setBrowseOrderView(view) {
  state.browseOrderView = normalizeBrowseOrderView(view);
  localStorage.setItem("marketWebBrowseOrderView", state.browseOrderView);
}

function routePath(route = state.activeProductTab) {
  return `/${normalizePageRoute(route)}`;
}

function routeFromLocation(pathname) {
  const raw = String(pathname || "/").trim().replace(/^\/+|\/+$/g, "");
  // 默认进"全部商品"(全服商品总览),无需先点某个终端。
  return normalizePageRoute(raw || "all");
}

function applyRouteFromLocation() {
  const url = new URL(window.location.href);
  state.activeProductTab = routeFromLocation(url.pathname);
  state.selectedMarketId = (url.searchParams.get("market") || "").trim();
  state.selectedCommodityKey = normalizeCommodityKey(url.searchParams.get("commodity") || "");
}

function syncRouteUrl(replace = false) {
  const url = new URL(window.location.href);
  url.pathname = routePath();
  if (state.selectedMarketId) {
    url.searchParams.set("market", state.selectedMarketId);
  } else {
    url.searchParams.delete("market");
  }
  if (state.selectedCommodityKey) {
    url.searchParams.set("commodity", state.selectedCommodityKey);
  } else {
    url.searchParams.delete("commodity");
  }
  const target = `${url.pathname}${url.search}`;
  const current = `${window.location.pathname}${window.location.search}`;
  if (target === current) {
    return;
  }
  window.history[replace ? "replaceState" : "pushState"]({}, "", target);
}

function clearCommodityIconCache() {
  commodityIconCache.clear();
  commodityIconRequests.clear();
  commodityIconBatchRequests.clear();
}

function applyWebResourceVersion(version) {
  const normalized = version == null ? "" : String(version).trim();
  if (!normalized) {
    return;
  }
  if (state.webResourceVersion && state.webResourceVersion !== normalized) {
    clearCommodityIconCache();
  }
  state.webResourceVersion = normalized;
}

function captureDetailScrollState() {
  return {
    detailScrollTop: els.marketDetail?.scrollTop || 0,
    windowScrollY: window.scrollY || 0
  };
}

function restoreDetailScrollState(scrollState) {
  if (!scrollState) {
    return;
  }
  requestAnimationFrame(() => {
    if (els.marketDetail) {
      els.marketDetail.scrollTop = scrollState.detailScrollTop || 0;
    }
    window.scrollTo(0, scrollState.windowScrollY || 0);
  });
}

function renderDetailPreservingScroll() {
  const scrollState = captureDetailScrollState();
  renderDetail();
  restoreDetailScrollState(scrollState);
}


// ---- 重绘保护 ----
// renderDetail() 每次从字符串模板生成整页 HTML。更新时有三样东西不能丢:
// 正在进行的输入法组字(拼音等)、键盘焦点与光标位置、玩家已输入但还没提交的表单内容。
// 组字期间推迟更新;焦点和输入由下面的 morph 原地更新保留。
const renderGuard = {
  composing: null,
  deferred: false,
  resetFormsOnce: false,
  resetWalletOnce: false,
  lastViewKey: null
};

document.addEventListener("compositionstart", (event) => {
  renderGuard.composing = event.target;
}, true);

document.addEventListener("compositionend", () => {
  renderGuard.composing = null;
  if (renderGuard.deferred) {
    renderGuard.deferred = false;
    window.setTimeout(renderDetailPreservingScroll, 0);
  }
}, true);

function isComposingIn(root) {
  const node = renderGuard.composing;
  return !!(node && node.isConnected && root && root.contains(node));
}

// 没有 id 的按钮靠这些属性找回"同一个"元素(对话框关闭后把焦点还给触发按钮)。
const FOCUS_KEY_ATTRIBUTES = [
  "data-route", "data-catalog-group", "data-catalog-subcategory", "data-catalog-rarity", "data-catalog-sort",
  "data-browse-order-view", "data-aggregate-tab", "data-chart-timeframe", "data-chart-indicator", "data-chart-flag",
  "data-card-commodity-key", "data-aggregate-card-commodity-key", "data-purchase-index", "data-aggregate-purchase",
  "data-storage-choice", "data-wallet-action", "data-market-id"
];

function focusSelectorFor(node) {
  if (!node || node === document.body || node.nodeType !== Node.ELEMENT_NODE) {
    return "";
  }
  if (node.id) {
    return `#${CSS.escape(node.id)}`;
  }
  for (const attribute of FOCUS_KEY_ATTRIBUTES) {
    if (node.hasAttribute(attribute)) {
      return `[${attribute}="${CSS.escape(node.getAttribute(attribute) || "")}"]`;
    }
  }
  return "";
}

// ---- 原地更新(morph) ----
// 模板仍是字符串,但不再整体替换 innerHTML:用 Idiomorph 对比新 HTML 与现有 DOM,只改变化的部分。
// 元素长期存在,焦点、光标、输入法组字、悬停和滚动都不会被打断。
// 玩家改过但还没提交的输入("脏"控件)不会被模板默认值覆盖;切换视图或提交成功后清掉脏标记。
// 带 data-island 的元素(图表容器、地图根节点)由各自的控制代码管理,morph 整个跳过。
const dirtyControls = new WeakMap();
const dirtyGenerations = { detail: 1, wallet: 1, other: 1 };

function dirtyScopeOf(node) {
  if (els.marketDetail?.contains(node)) {
    return "detail";
  }
  if (els.walletDock?.contains(node)) {
    return "wallet";
  }
  return "other";
}

function isDirtyControl(node) {
  return !!node && dirtyControls.get(node) === dirtyGenerations[dirtyScopeOf(node)];
}

function clearDirtyControls(scope) {
  dirtyGenerations[scope] += 1;
}

function trackDirtyControl(event) {
  const node = event.target;
  if (node instanceof HTMLInputElement || node instanceof HTMLTextAreaElement || node instanceof HTMLSelectElement) {
    dirtyControls.set(node, dirtyGenerations[dirtyScopeOf(node)]);
  }
}

document.addEventListener("input", trackDirtyControl, true);
document.addEventListener("change", trackDirtyControl, true);

const morphCallbacks = {
  beforeNodeMorphed(oldNode, newNode) {
    if (oldNode.nodeType !== Node.ELEMENT_NODE || newNode.nodeType !== Node.ELEMENT_NODE) {
      return true;
    }
    const island = oldNode.getAttribute("data-island");
    if (island && island === newNode.getAttribute("data-island")) {
      return false;
    }
    // 求购物品预览由解析请求就地更新;玩家输入了物品 ID 时保留解析结果。
    if (oldNode.id === "buy-order-preview" && isDirtyControl(document.querySelector("#buy-order-key"))) {
      return false;
    }
    return true;
  },
  beforeAttributeUpdated(attribute, element) {
    if ((attribute === "value" || attribute === "checked") && isDirtyControl(element)) {
      return false;
    }
    if (attribute === "selected" && isDirtyControl(element.closest("select"))) {
      return false;
    }
    if (element.id === "buy-order-button"
        && (attribute === "data-item-valid" || attribute === "data-resolved-commodity-key")
        && isDirtyControl(document.querySelector("#buy-order-key"))) {
      return false;
    }
    return true;
  }
};

function morphInto(root, html) {
  if (!root) {
    return;
  }
  if (!window.Idiomorph) {
    // 运行库没加载成功时退回整体替换,页面仍然可用。
    root.innerHTML = html;
    return;
  }
  window.Idiomorph.morph(root, html, {
    morphStyle: "innerHTML",
    ignoreActiveValue: true,
    restoreFocus: true,
    callbacks: morphCallbacks
  });
}

// 同一视图内更新时保留玩家输入;切换视图(换商品/换页签)或提交成功后清掉脏标记,表单回到默认值。
function commitDetailHtml(html) {
  const viewKey = currentViewKey();
  if (viewKey !== renderGuard.lastViewKey || renderGuard.resetFormsOnce) {
    clearDirtyControls("detail");
  }
  renderGuard.resetFormsOnce = false;
  morphInto(els.marketDetail, html);
  renderGuard.lastViewKey = viewKey;
}

function selectedDispatchOrder(detail = state.detail) {
  const orders = Array.isArray(detail?.sourceOrders) ? detail.sourceOrders : [];
  if (!orders.length) {
    state.selectedDispatchOrderIndex = 0;
    return null;
  }
  const maxIndex = orders.length - 1;
  state.selectedDispatchOrderIndex = Math.max(0, Math.min(maxIndex, Number(state.selectedDispatchOrderIndex) || 0));
  return orders[state.selectedDispatchOrderIndex] || orders[0] || null;
}

function selectedDispatchOption(order = selectedDispatchOrder()) {
  const options = Array.isArray(order?.dispatchOptions) ? order.dispatchOptions : [];
  const terminal = String(state.selectedDispatchTerminal || "PORT").trim().toUpperCase();
  return options.find((entry) => String(entry?.terminalKind || "").trim().toUpperCase() === terminal) || options[0] || null;
}

function formatDistanceMeters(value) {
  const meters = Number(value || 0);
  return meters > 0 ? `${number(meters)}m` : "-";
}

function formatEtaSeconds(value) {
  const total = Math.max(0, Number(value || 0));
  if (!total) {
    return "-";
  }
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (hours > 0) {
    return `${hours}h ${String(minutes).padStart(2, "0")}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${String(seconds).padStart(2, "0")}s`;
  }
  return `${seconds}s`;
}

function currentDispatchOrder(detail = state.detail) {
  const orders = Array.isArray(detail?.sourceOrders) ? detail.sourceOrders : [];
  if (!orders.length) {
    state.selectedDispatchOrderIndex = 0;
    return null;
  }
  const nextIndex = Math.max(0, Math.min(Number(state.selectedDispatchOrderIndex) || 0, orders.length - 1));
  state.selectedDispatchOrderIndex = nextIndex;
  return orders[nextIndex] || null;
}

function currentDispatchOption(order = currentDispatchOrder(), terminalKind = state.selectedDispatchTerminal) {
  const options = Array.isArray(order?.dispatchOptions) ? order.dispatchOptions : [];
  const normalized = String(terminalKind || "PORT").trim().toUpperCase();
  return options.find((entry) => String(entry?.terminalKind || "").trim().toUpperCase() === normalized) || options[0] || null;
}

const I18N = {
  "zh-CN": {},
  "en-US": {
    hero_title: "Monpai Online Market",
    hero_subtitle: "Browse terminals and prices first. Bind a web account only when you want to trade, list goods, or manage logistics.",
    mode_label: "Mode",
    mode_value: "Commodity Market",
    session_label: "Session",
    flow_label: "Flow",
    flow_value: "Token Login",
    access_panel: "Sign In",
    access_panel_open: "Hide Login",
    access_panel_closed: "Sign In",
    portal_kicker: "Portal Access",
    auth_title: "Bind once with token, then sign in offline",
    auth_subtitle: "Use the in-game token once to bind a web account, then continue using username and password even while offline.",
    command_label: "In-game command",
    copy_command: "Copy command",
    fast_signin: "Fast Sign In",
    connect_player: "Bind account or sign in",
    not_signed_in: "Not signed in.",
    paste_chat: "Paste chat output or token",
    paste_chat_placeholder: "Paste the full chat line here. The page will detect the token automatically.",
    token_placeholder: "Token appears here automatically",
    account_username_placeholder: "Account username",
    account_password_placeholder: "Password",
    bind_account: "Bind account",
    account_login: "Account login",
    token_helper: "First time: paste the token, set username and password, then bind. Later: sign in directly with username and password.",
    market_network: "Market Network",
    browse_markets: "Browse Markets",
    refresh: "Refresh",
    market_search_label: "Find Terminal",
    market_search_placeholder: "Search market, owner, town, or dimension",
    market_sidebar_hint: "Choose one market terminal, then browse its commodities like a marketplace and drill into one item page.",
    market_summary_title: "Network Snapshot",
    market_summary_hint: "Track terminal count, live chunks, and the currently selected market.",
    market_terminal_count: "Terminals",
    selected_market: "Selected Market",
    brand_kicker: "Monpai Online Market",
    language_label: "Language",
    theme_to_light: "Switch to light theme",
    theme_to_dark: "Switch to dark theme",
    terminal_picker: "Terminal",
    terminal_none: "Choose a terminal",
    market_strip_more: "Details",
    category_filter: "Category",
    results_count: "%1 items",
    filters_label: "Filters",
    buy_box_buy: "Buy",
    buy_box_bid: "Place buy order",
    empty_select_market: "Select a market terminal to browse commodities, listings, buy orders, and price history.",
    market_overview: "Market Overview",
    explore_tab: "Explore",
    trade_tab: "Trade",
    demand_tab: "Buy Orders",
    analytics_tab: "Analytics",
    map_tab: "MAP",
    explore_hint: "Browse catalog, compare prices, and jump between goods.",
    trade_hint: "Buy, list, claim credits, and manage demand from one lane.",
    demand_hint: "Create buy orders directly by item id and actual unit price.",
    inventory_hint: "Inspect stock that can be listed or dispatched.",
    analytics_hint: "Read price history, index movement, and macro signals.",
    map_hint: "Reserved for the world map, territories, market locations, and logistics tracking.",
    map_reserved_kicker: "Map Module",
    map_reserved_title: "World market map reserved",
    map_reserved_body: "The map page is reserved for the next phase. It will use sampled minimap data only, with territory overlays and active logistics traces when the backend layer is ready.",
    map_reserved_unknown: "Unknown terrain will stay black.",
    map_reserved_markets: "Market markers will appear here.",
    map_reserved_routes: "Active land and sea logistics will be tracked here.",
    map_live_title: "World Trade Map",
    map_layer_terrain: "Terrain",
    map_layer_territories: "Territories",
    map_layer_markets: "Markets",
    map_layer_shipments: "Logistics",
    map_focus_market: "Focus market",
    map_no_shipments: "No active shipments.",
    map_unknown_tile: "Unknown terrain",
    signin_status_guest: "Sign in",
    signin_status_ready: "Account Center",
    browse_goods: "Browse Goods",
    commodity_shelf: "Commodity Shelf",
    browse_order_mode: "Browse order type",
    browse_purchase_orders: "Purchase",
    browse_demand_orders: "Buy Requests",
    no_purchase_rows: "No active sell listings match this filter.",
    no_demand_rows: "No active buy requests match this filter.",
    search_placeholder: "Search item name or commodity key",
    price_range: "Price Range",
    min_price: "Min Price",
    max_price: "Max Price",
    sort_name: "Name",
    sort_price: "Price",
    sort_time: "Time",
    sort_quantity: "Quantity",
    all_types: "All Types",
    all_rarities: "All Rarities",
    rarity_filter: "Rarity",
    sort_label: "Sort",
    inventory_tab: "Inventory",
    inventory_title: "Town Warehouse",
    no_inventory_rows: "This terminal has no stock to display yet.",
    inventory_requires_manage: "Only the current dock or market manager can view inventory.",
    category_all: "All",
    category_wood: "Wood",
    category_luxury: "Luxury",
    category_food: "Food",
    category_ore: "Ore",
    category_gems: "Gems",
    category_metal: "Metal",
    category_tools: "Tools",
    category_spices: "Spices",
    category_plant: "Plant",
    category_crop: "Crop",
    category_material: "Materials",
    category_mob_drop: "Mob Drop",
    category_alchemy: "Alchemy",
    category_building: "Building",
    category_nether: "Nether",
    category_end: "End",
    category_treasure: "Treasure",
    category_redstone: "Redstone",
    category_utility: "Utility",
    category_weapon: "Weapon",
    category_armor: "Armor",
    category_other: "Other",
    rarity_common: "Common",
    rarity_uncommon: "Uncommon",
    rarity_rare: "Rare",
    rarity_epic: "Epic",
    rarity_legend: "Legend",
    rarity_extraordinary: "Extraordinary",
    no_match: "No commodity matches this filter.",
    selected_commodity: "Selected Commodity",
    lowest_sell: "Lowest Sell",
    highest_buy: "Highest Buy",
    avg_24h: "24h Avg",
    trades_24h: "24h Trades",
    sell_listings: "Sell Listings",
    on_sale: "On Sale",
    buying_demand: "Buying Demand",
    in_storage: "In Storage",
    browse_tab: "Browse Goods",
    purchase_tab: "Buy Goods",
    chart_tab: "Chart",
    market_index_tab: "Market Index",
    units_live: "units live",
    units_wanted: "units wanted",
    volume_label: "volume",
    selling: "Selling",
    buying: "Buying",
    my_buy_orders: "My Buy Orders",
    price_history: "Price History",
    recent_buckets: "Recent chart buckets",
    chart_context: "Chart context",
    sell_item: "Sell Item",
    create_listing: "Create listing",
    create_buy_order: "Create buy order",
    buy_order_item_id: "Item ID",
    buy_order_item_help: "Enter any registered item id, even if it has never appeared in this market.",
    buy_order_item_preview: "Resolved item",
    buy_order_item_resolving: "Resolving item...",
    buy_order_item_not_found: "No registered item matches this id.",
    buy_order_item_valid: "Ready for buy order",
    buy_order_tab: "Buy Orders",
    buy_order_quantity_label: "Buy order quantity",
    buy_order_unit_price: "Buy order unit price",
    buy_order_price_help: "Enter the actual unit price you want to pay. The system converts it to bp automatically.",
    buy_order_bp_preview: "Derived bp",
    claim_credits: "Claim credits",
    retry_dispatch: "Retry dispatch",
    terminal_type_label: "Dispatch terminal",
    terminal_type_port: "Port",
    terminal_type_post_station: "Post Station",
    market_notes: "Market Notes",
    sell_summary: "Sell side summary",
    demand_summary: "Buy side liquidity",
    market_snapshot: "Market Snapshot",
    no_commodity_data: "This market has no commodity data yet.",
    no_selling_rows: "No active sell listings for this commodity.",
    no_buy_rows: "No open buy requests for this commodity.",
    no_my_buy_rows: "You have no buy orders for this commodity.",
    no_chart_rows: "No price chart records yet.",
    no_storage_match: "No matching dock storage entry for this commodity.",
    seller_note: "Seller note",
    manual_price: "Manual unit price",
    manual_price_help: "Enter your own unit price. The server converts it to a bp offset and only accepts prices inside the allowed range.",
    manual_price_free_help: "This item has no configured price model. You can choose any unit price above 0.",
    listing_price_preview: "Unit price",
    listing_bp_preview: "Derived bp",
    listing_price_range: "Allowed range",
    listing_price_free: "Free pricing",
    listing_price_invalid: "Price is out of range.",
    suggested_word: "suggested",
    min_bp: "Min bp",
    max_bp: "Max bp",
    to_word: "to",
    offline_word: "offline",
    online_word: "online",
    connected_word: "connected",
    signed_in_as: "Signed in as {name}.",
    sign_in_required: "Login token is required.",
    bind_requires_credentials: "Username and password are required to bind an account.",
    account_login_required: "Username and password are required.",
    account_bound: "Bound account {name}.",
    account_login_success: "Signed in with account {name}.",
    sign_in_to_trade: "Sign in to purchase, list, create buy orders, cancel, claim credits, or dispatch.",
    copied: "Copied.",
    command_copied: "Command copied. Run it in game.",
    token_extracted: "Token extracted and copied to clipboard.",
    clipboard_failed: "Clipboard access failed. Copy manually instead.",
    markets_refreshed: "Markets refreshed.",
    action_completed: "Action completed.",
    sign_in_to_load_markets: "Sign in to unlock market actions.",
    guest_mode_ready: "Guest mode: you can browse current goods, but all actions stay locked.",
    no_markets: "No market terminals registered yet.",
    select_market_prompt: "Select a market terminal to browse its commodity catalog.",
    guest_word: "Guest",
    live_terminal: "Live terminal",
    chunk_cold: "Chunk cold",
    manage: "Manage",
    view: "View",
    loaded: "Loaded",
    cold: "Cold",
    dock_linked: "Linked warehouse",
    no_linked_dock: "No linked warehouse",
    manager_access: "Manager access",
    read_only: "Read only",
    browse_only: "Browse only",
    listings_word: "listings",
    market: "Market",
    quantity: "Quantity",
    total: "Total",
    actions: "Actions",
    seller: "Seller",
    buyer: "Buyer",
    available: "Available",
    reserved: "Reserved",
    unit_price: "Unit Price",
    dock: "Dock",
    warehouse_word: "Warehouse",
    status: "Status",
    price_band: "Price Band",
    implied_bid: "Implied Bid",
    time: "Time",
    average: "Average",
    low: "Low",
    high: "High",
    volume: "Volume",
    buy_1: "Buy",
    cancel: "Cancel",
    confirm: "Confirm",
    buy_modal_title: "Confirm Order",
    mode_seller_ship: "Seller ships",
    mode_auto_pickup: "Auto pickup",
    mode_real_pickup: "Self pickup",
    receiving_label: "Receiving",
    receiving_none: "No usable warehouse",
    mode_need_warehouse: "No usable warehouse",
    mode_unreachable: "No reachable lane/road",
    queue_position: "Queue #%1 · ~%2 min",
    order_cancel: "Cancel Order",
    current_sell_side: "Current sell side",
    current_buy_side: "Current buy side",
    dock_stock: "Warehouse stock",
    terminal_status: "Terminal status",
    chart_disabled: "Price charts are disabled by config.",
    no_chart_buckets: "No chart buckets for this commodity yet.",
    chart_no_selection: "No commodity is currently selected for chart rendering.",
    chart_library_missing: "Chart library was not loaded. Switched to the built-in candlestick renderer.",
    chart_init_failed: "Chart initialization failed. Switched to the built-in candlestick renderer.",
    chart_render_failed: "Chart rendering failed.",
    no_demand_ladder: "No demand ladder yet for this commodity.",
    waiting_reference: "Waiting for a clear reference sell price.",
    around_current_ask: "Around {range} against the current ask.",
    no_storage: "No storage",
    storage_rows: "storage rows",
    live_requests: "live requests",
    stock_rows: "storage rows",
    owner_word: "Owner",
    town_word: "Town",
    wallet_balance: "My wallet",
    wallet_balance_hint: "Purchases and buy orders spend the market wallet. Move funds between cash, wallet, and treasury here.",
    wallet_reserved: "Reserved",
    wallet_total: "Wallet total",
    cash_balance: "Cash",
    treasury_balance: "Treasury",
    wallet_transfer_amount: "Transfer amount",
    wallet_transfer_placeholder: "Enter transfer amount",
    wallet_transfer_hint: "Withdrawals go to the economy plugin when available. Without one, they become gold in your private warehouse storage.",
    wallet_topbar_hint: "My personal balance",
    wallet_expand: "Expand wallet",
    wallet_collapse: "Collapse wallet",
    wallet_cash_to_wallet: "Cash/Warehouse gold -> Wallet",
    wallet_wallet_to_cash: "Wallet -> Cash",
    wallet_wallet_to_treasury: "Wallet -> Treasury",
    wallet_treasury_to_wallet: "Treasury -> Wallet",
    wallet_claim_to_wallet: "Claim credits -> Wallet",
    pending_credits: "Pending credits",
    commodity_types: "Commodity types",
    storage_units: "Storage units",
    open_demand: "Open demand",
    my_buy_orders_metric: "My buy orders",
    net_balance: "Net balance",
    reference_price: "Reference",
    liquidity_score: "Liquidity",
    market_index: "Market Index",
    category_index: "Category Index",
    pressure_model: "Impact Model",
    inventory_pressure: "Inventory Pressure",
    buy_pressure: "Buy Pressure",
    volatility_word: "Volatility",
    timeframe: "Timeframe",
    indicators: "Indicators",
    chart_controls: "Chart Controls",
    log_scale: "Log Scale",
    inflation_adjust: "Adjust for Inflation",
    inflation_unavailable: "No CPI data is available yet.",
    reset_zoom: "Reset Zoom",
    current_change: "Current Change",
    max_drawdown: "Max Drawdown",
    inception_return: "Inception Return",
    chart_hover_empty: "Move over the chart to inspect OHLC and volume",
    market_index_chart: "Market Index Trend",
    category_index_chart: "Category Index Trend",
    cpi_chart: "Consumer Price Index",
    loans_chart: "Outstanding Loans Trend",
    inflation_since_inception: "Inflation Since Inception",
    loans_change: "Loans Change",
    cpi_now: "Current CPI",
    macro_overview: "Macro Overview"
  }
};

Object.assign(I18N["zh-CN"], I18N["en-US"], {
  hero_title: "Monpai 在线市场",
  hero_subtitle: "游客可以浏览市场商品与价格走势；登录后可以绑定账户、上架、求购和发货。",
  mode_label: "模式",
  mode_value: "商品市场",
  session_label: "会话",
  flow_label: "流程",
  flow_value: "网页账户登录",
  access_panel: "登录",
  access_panel_open: "收起登录",
  access_panel_closed: "登录",
  portal_kicker: "网页登录",
  auth_title: "先绑定网页账户，再用账号登录",
  auth_subtitle: "首次使用时，从游戏中获取 token，设置网页账号和密码完成绑定。之后可直接用账号密码登录。",
  command_label: "游戏内命令",
  copy_command: "复制命令",
  fast_signin: "快速登录",
  connect_player: "绑定账户或登录",
  not_signed_in: "未登录",
  paste_chat: "粘贴聊天输出或 token",
  paste_chat_placeholder: "把完整聊天消息粘贴到这里，页面会自动提取 token",
  token_placeholder: "Token 会自动显示在这里",
  account_username_placeholder: "账户名",
  account_password_placeholder: "密码",
  bind_account: "绑定账户",
  account_login: "账户登录",
  token_helper: "首次使用：粘贴 token，填写账户名和密码后点击绑定。之后可直接使用账户名和密码登录。",
  market_network: "市场网络",
  browse_markets: "浏览市场",
  refresh: "刷新",
  market_search_label: "查找终端",
  market_search_placeholder: "搜索市场、所有者、城镇或维度",
  market_sidebar_hint: "选择一个市场终端后，即可浏览商品目录、在售挂单、求购订单和价格走势。",
  market_summary_title: "网络摘要",
  market_summary_hint: "快速查看终端数量、在线区块和当前选择的市场。",
  market_terminal_count: "终端",
  selected_market: "当前市场",
  brand_kicker: "Monpai 在线市场",
  language_label: "语言",
  theme_to_light: "切换到亮色主题",
  theme_to_dark: "切换到深色主题",
  terminal_picker: "终端",
  terminal_none: "选择终端",
  market_strip_more: "更多",
  category_filter: "分类",
  results_count: "%1 件",
  filters_label: "筛选",
  buy_box_buy: "购买",
  buy_box_bid: "发布求购",
  empty_select_market: "选择一个市场终端来浏览商品、挂单、求购和价格走势。",
  market_overview: "市场概览",
  explore_tab: "浏览",
  trade_tab: "交易",
  demand_tab: "求购",
  analytics_tab: "分析",
  map_tab: "MAP",
  explore_hint: "浏览目录、比较价格并快速切换商品。",
  trade_hint: "集中处理购买、上架、领款和求购操作。",
  demand_hint: "直接按物品 ID 和实际单价创建求购单。",
  inventory_hint: "查看可上架或可发运的库存。",
  analytics_hint: "查看价格走势、指数变化和宏观信号。",
  map_hint: "预留给世界地图、领地范围、市场位置和物流追踪。",
  map_reserved_kicker: "地图模块",
  map_reserved_title: "世界市场地图已预留",
  map_reserved_body: "这个 MAP 大分页先作为下一阶段入口保留。后续会只复用已采样的小地图数据，接入领地覆盖层和进行中的物流轨迹。",
  map_reserved_unknown: "未知地形保持黑色。",
  map_reserved_markets: "市场标记会显示在这里。",
  map_reserved_routes: "陆路和水路进行中物流会在这里追踪。",
  map_live_title: "世界贸易地图",
  map_layer_terrain: "地形",
  map_layer_territories: "领地",
  map_layer_markets: "市场",
  map_layer_shipments: "物流",
  map_focus_market: "聚焦市场",
  map_no_shipments: "暂无进行中的物流。",
  map_unknown_tile: "未知地形",
  signin_status_guest: "立即登录",
  signin_status_ready: "账户中心",
  browse_goods: "浏览商品",
  commodity_shelf: "商品货架",
  browse_order_mode: "浏览订单类型",
  browse_purchase_orders: "购买",
  browse_demand_orders: "求购",
  no_purchase_rows: "没有符合筛选条件的在售商品。",
  no_demand_rows: "没有符合筛选条件的求购订单。",
  search_placeholder: "搜索物品名或商品编号",
  price_range: "价格区间",
  min_price: "最低价",
  max_price: "最高价",
  sort_name: "名称",
  sort_price: "价格",
  sort_time: "时间",
  sort_quantity: "数量",
  all_types: "全部类型",
  all_rarities: "全部稀有度",
  rarity_filter: "稀有度",
  sort_label: "排序",
  inventory_tab: "仓储",
  inventory_title: "城镇仓库",
  no_inventory_rows: "当前终端还没有可显示的库存。",
  inventory_requires_manage: "只有当前码头或市场管理员可以查看库存。",
  category_all: "全部",
  category_wood: "木材",
  category_luxury: "奢侈品",
  category_food: "食物",
  category_ore: "矿石",
  category_gems: "宝石",
  category_metal: "金属",
  category_tools: "工具",
  category_spices: "香料",
  category_plant: "植物",
  category_crop: "作物",
  category_material: "材料",
  category_mob_drop: "生物掉落",
  category_alchemy: "炼金",
  category_building: "建筑",
  category_nether: "下界",
  category_end: "末地",
  category_treasure: "宝藏",
  category_redstone: "红石",
  category_utility: "实用",
  category_weapon: "武器",
  category_armor: "护甲",
  category_other: "其他",
  rarity_common: "普通",
  rarity_uncommon: "优秀",
  rarity_rare: "稀有",
  rarity_epic: "史诗",
  rarity_legend: "传说",
  rarity_extraordinary: "超凡",
  no_match: "没有符合筛选条件的商品。",
  selected_commodity: "当前商品",
  lowest_sell: "最低卖价",
  highest_buy: "最高求购",
  avg_24h: "24小时均价",
  trades_24h: "24小时成交",
  sell_listings: "在售挂单",
  on_sale: "在售数量",
  buying_demand: "求购需求",
  in_storage: "仓储库存",
  browse_tab: "浏览商品",
  purchase_tab: "购买商品",
  chart_tab: "价格图表",
  market_index_tab: "市场指数",
  units_live: "在售单位",
  units_wanted: "求购单位",
  volume_label: "成交",
  selling: "在售",
  buying: "求购",
  my_buy_orders: "我的求购",
  price_history: "价格历史",
  recent_buckets: "近期分桶",
  chart_context: "图表环境",
  sell_item: "上架商品",
  create_listing: "创建挂单",
  create_buy_order: "创建求购",
  buy_order_item_id: "物品 ID",
  buy_order_item_help: "可以输入任意已注册物品 ID，即使它从未在市场出现过。",
  buy_order_item_preview: "已识别物品",
  buy_order_item_resolving: "正在识别物品...",
  buy_order_item_not_found: "没有找到这个已注册物品 ID。",
  buy_order_item_valid: "可以创建求购",
  buy_order_tab: "求购",
  buy_order_quantity_label: "求购数量",
  buy_order_unit_price: "求购单价",
  buy_order_price_help: "输入你愿意支付的实际单价，系统会自动换算为 bp。",
  buy_order_bp_preview: "系统换算 bp",
  claim_credits: "领取货款",
  retry_dispatch: "重试发货",
  terminal_type_label: "发货终端",
  terminal_type_port: "港口",
  terminal_type_post_station: "驿站",
  market_notes: "市场说明",
  sell_summary: "卖盘摘要",
  demand_summary: "买盘流动性",
  market_snapshot: "市场快照",
  no_commodity_data: "当前市场还没有商品数据。",
  no_selling_rows: "当前商品没有在售挂单。",
  no_buy_rows: "当前商品没有公开求购。",
  no_my_buy_rows: "你还没有这个商品的求购单。",
  no_chart_rows: "当前商品还没有价格记录。",
  no_storage_match: "当前没有与该商品匹配的仓储条目。",
  seller_note: "卖家备注",
  manual_price: "手动单价",
  manual_price_help: "直接输入想要的单价。服务端会自动换算为 bp 偏移，并只接受允许范围内的价格。",
  manual_price_free_help: "该物品没有配置价格模型。玩家可以输入任意大于 0 的单价。",
  listing_price_preview: "单价预览",
  listing_bp_preview: "推导 bp",
  listing_price_range: "允许范围",
  listing_price_free: "自由定价",
  listing_price_invalid: "定价不在允许范围内。",
  suggested_word: "建议",
  min_bp: "最低 bp",
  max_bp: "最高 bp",
  to_word: "到",
  offline_word: "离线",
  online_word: "在线",
  connected_word: "已连接",
  signed_in_as: "当前登录：{name}",
  sign_in_required: "需要先输入登录 token。",
  bind_requires_credentials: "绑定账户需要填写账户名和密码。",
  account_login_required: "登录需要填写账户名和密码。",
  account_bound: "已绑定账户：{name}",
  account_login_success: "已使用账户 {name} 登录。",
  sign_in_to_trade: "登录后才能购买、上架、求购、取消、领取货款或执行发货。",
  copied: "已复制",
  command_copied: "命令已复制，请回到游戏内执行",
  token_extracted: "已提取 token 并复制到剪贴板",
  clipboard_failed: "无法访问剪贴板，请手动复制",
  markets_refreshed: "市场列表已刷新",
  action_completed: "操作已完成",
  sign_in_to_load_markets: "游客可浏览市场，登录后解锁交易和发货操作",
  guest_mode_ready: "访客模式：可以浏览市场与商品，但不能执行交易操作",
  no_markets: "当前还没有注册任何市场终端。",
  select_market_prompt: "选择一个市场终端来浏览它的商品目录。",
  guest_word: "访客",
  live_terminal: "在线终端",
  chunk_cold: "区块未加载",
  manage: "管理",
  view: "查看",
  loaded: "已加载",
  cold: "未加载",
  dock_linked: "已绑定仓库",
  no_linked_dock: "未绑定仓库",
  manager_access: "管理权限",
  read_only: "只读",
  browse_only: "仅浏览",
  listings_word: "挂单",
  market: "市场",
  quantity: "数量",
  total: "总价",
  actions: "操作",
  seller: "卖家",
  buyer: "买家",
  available: "可售",
  reserved: "预留",
  unit_price: "单价",
  dock: "港口",
  warehouse_word: "仓库",
  status: "状态",
  price_band: "价格区间",
  implied_bid: "推算出价",
  time: "时间",
  average: "均价",
  low: "低",
  high: "高",
  volume: "成交",
  buy_1: "购买",
  cancel: "取消",
  confirm: "确认",
  buy_modal_title: "确认下单",
  mode_seller_ship: "卖家发货",
  mode_auto_pickup: "自动自提",
  mode_real_pickup: "真人自提",
  receiving_label: "收货地",
  receiving_none: "无可用收货仓库",
  mode_need_warehouse: "无可用收货仓库",
  mode_unreachable: "无可达航线/道路",
  queue_position: "排第 %1 位 · 约 %2 分钟",
  order_cancel: "取消订单",
  current_sell_side: "当前卖盘",
  current_buy_side: "当前买盘",
  dock_stock: "仓储库存",
  terminal_status: "终端状态",
  chart_disabled: "配置已关闭价格图表",
  no_chart_buckets: "当前商品还没有价格分桶数据。",
  chart_no_selection: "当前没有可展示图表的商品。",
  chart_library_missing: "图表库未加载，已切换到内置 K 线渲染。",
  chart_init_failed: "图表初始化失败，已切换到内置 K 线渲染",
  chart_render_failed: "图表渲染失败",
  no_demand_ladder: "当前商品还没有形成求购梯队。",
  waiting_reference: "暂无明确在售价参考。",
  around_current_ask: "按当前卖价推算大致在 {range}",
  no_storage: "无仓储",
  storage_rows: "仓储条目",
  live_requests: "活跃求购",
  stock_rows: "库存条目",
  owner_word: "所有者",
  town_word: "城镇",
  wallet_balance: "我的钱包",
  wallet_balance_hint: "购买商品和发布求购都会从你个人的市场钱包扣款，可在这里和现金、国库互转。",
  wallet_reserved: "冻结余额",
  wallet_total: "钱包合计",
  cash_balance: "现金",
  treasury_balance: "国库",
  wallet_transfer_amount: "转账金额",
  wallet_transfer_placeholder: "输入转账金额",
  wallet_transfer_hint: "提现到个人账户时，有经济插件会进入插件余额；没有插件则转成金存入绑定仓库的私人仓储。",
  wallet_topbar_hint: "我的个人余额",
  wallet_expand: "展开钱包",
  wallet_collapse: "收起钱包",
  wallet_cash_to_wallet: "现金/仓库金 -> 钱包",
  wallet_wallet_to_cash: "钱包 -> 现金",
  wallet_wallet_to_treasury: "钱包 -> 国库",
  wallet_treasury_to_wallet: "国库 -> 钱包",
  wallet_claim_to_wallet: "待领款 -> 钱包",
  pending_credits: "待领货款",
  commodity_types: "商品种类",
  storage_units: "仓储总量",
  open_demand: "开放需求",
  my_buy_orders_metric: "我的求购",
  net_balance: "净余额",
  reference_price: "参考价",
  liquidity_score: "流动性",
  market_index: "市场指数",
  category_index: "分类指数",
  pressure_model: "价格影响模型",
  inventory_pressure: "库存压力",
  buy_pressure: "买盘压力",
  volatility_word: "波动",
  timeframe: "周期",
  indicators: "指标",
  chart_controls: "图表控制",
  log_scale: "对数坐标",
  inflation_adjust: "通胀修正",
  inflation_unavailable: "当前没有 CPI 数据，暂不可用",
  reset_zoom: "重置缩放",
  current_change: "当前涨跌",
  max_drawdown: "最大回撤",
  inception_return: "起始收益",
  chart_hover_empty: "将鼠标移动到图表上以查看 OHLC 与成交量",
  market_index_chart: "市场指数走势",
  category_index_chart: "分类指数走势",
  cpi_chart: "消费价格指数",
  loans_chart: "未偿贷款走势",
  inflation_since_inception: "累计通胀",
  loans_change: "贷款变化",
  cpi_now: "当前 CPI",
  macro_overview: "宏观概览"
});

Object.assign(I18N["zh-CN"], {
  category_group_all: "\u5168\u90e8",
  category_group_resource: "\u8d44\u6e90",
  category_group_building: "\u5efa\u9020",
  category_group_trade: "\u8d38\u6613",
  category_group_combat: "\u6218\u6597",
  category_group_mystic: "\u795e\u79d8",
  category_group_other: "\u5176\u4ed6",
  category_fishery: "\u6e14\u4e1a",
  category_livestock: "\u755c\u4ea7",
  category_textile: "\u7eba\u7ec7",
  category_construction: "\u5efa\u6750",
  category_decoration: "\u88c5\u9970",
  category_furniture: "\u5bb6\u5177",
  category_lighting: "\u7167\u660e",
  category_flooring: "\u5730\u9762",
  category_landscaping: "\u666f\u89c2",
  category_machinery: "\u673a\u68b0",
  category_magic: "\u9b54\u6cd5"
});

Object.assign(I18N["en-US"], {
  category_group_all: "All",
  category_group_resource: "Resources",
  category_group_building: "Build",
  category_group_trade: "Trade",
  category_group_combat: "Combat",
  category_group_mystic: "Mystic",
  category_group_other: "Other",
  category_fishery: "Fishery",
  category_livestock: "Livestock",
  category_textile: "Textiles",
  category_construction: "Construction",
  category_decoration: "Decoration",
  category_furniture: "Furniture",
  category_lighting: "Lighting",
  category_flooring: "Flooring",
  category_landscaping: "Landscaping",
  category_machinery: "Machinery",
  category_magic: "Magic"
});

Object.assign(I18N["en-US"], {
  my_orders_tab: "My Orders",
  my_orders_hint: "Track your purchases, ETA, and queue position.",
  my_orders_empty: "You have no orders yet.",
  listing_word: "Listing",
  total_price_word: "Total",
  shipping_word: "Shipping",
  order_status_paid: "Paid",
  order_status_waiting_shipment: "Awaiting shipment",
  order_status_pickup_locked: "Pickup locked",
  order_status_in_transit: "In transit",
  order_status_delivered: "Delivered",
  order_status_cancelled: "Cancelled"
});

Object.assign(I18N["zh-CN"], {
  my_orders_tab: "我的订单",
  my_orders_hint: "查看你的采购订单、ETA 与排队情况。",
  my_orders_empty: "你还没有订单。",
  listing_word: "挂单",
  total_price_word: "总价",
  shipping_word: "运输",
  order_status_paid: "已付款",
  order_status_waiting_shipment: "等待发货",
  order_status_pickup_locked: "已锁定自提",
  order_status_in_transit: "运输中",
  order_status_delivered: "已送达",
  order_status_cancelled: "已取消"
});

Object.assign(I18N["en-US"], {
  all_listings_tab: "All Goods",
  all_listings_nav_hint: "Browse every listing across all markets.",
  all_listings_title: "All Goods Across Markets",
  all_listings_kicker: "Server Marketplace",
  all_listings_hint: "Every listing on the server, with its source town. Buy items reachable by road or sea route.",
  all_listings_search_placeholder: "Search by item name",
  all_listings_count: "{count} listings",
  all_listings_empty: "No listings are available right now.",
  all_listings_col_item: "Item",
  all_listings_col_town: "Source Town",
  all_listings_col_seller: "Seller",
  all_listings_col_price: "Unit Price",
  all_listings_col_stock: "Stock",
  all_listings_col_action: "Action",
  all_listings_buy: "Buy",
  all_listings_back: "Back to All Goods",
  all_listings_loading: "Loading listings...",
  all_listings_purchased: "Purchase submitted.",
  reason_not_logged_in: "Sign in to purchase.",
  reason_no_town: "You do not belong to any town.",
  reason_own_listing: "This is your own listing.",
  reason_no_route: "No road or sea route connects you to this town.",
  reason_no_market_selected: "Open a market first to use it as the purchase carrier.",
  mc_warehouse_title: "List from Colony Warehouse",
  mc_warehouse_kicker: "MineColonies",
  mc_warehouse_hint: "Pull items straight from the linked colony warehouse and list them on the market.",
  mc_warehouse_col_item: "Item",
  mc_warehouse_col_stock: "In Warehouse",
  mc_warehouse_col_quantity: "Quantity",
  mc_warehouse_col_price: "Unit Price",
  mc_warehouse_col_action: "Action",
  mc_warehouse_list: "List",
  mc_warehouse_empty: "No colony warehouse linked, or it has no listable items.",
  mc_warehouse_count: "{count} item types",
  mc_warehouse_listed: "Listed from colony warehouse."
});

Object.assign(I18N["zh-CN"], {
  all_listings_tab: "全部商品",
  all_listings_nav_hint: "浏览全服所有市场的在售商品。",
  all_listings_title: "全服商品总览",
  all_listings_kicker: "全服市场",
  all_listings_hint: "全服所有在售商品及其来源城镇。可通过道路或航路连通的商品即可购买。",
  all_listings_search_placeholder: "按商品名搜索",
  all_listings_count: "{count} 件挂单",
  all_listings_empty: "当前没有任何在售商品。",
  all_listings_col_item: "商品",
  all_listings_col_town: "来源城镇",
  all_listings_col_seller: "卖家",
  all_listings_col_price: "单价",
  all_listings_col_stock: "库存",
  all_listings_col_action: "操作",
  all_listings_buy: "购买",
  all_listings_back: "返回全部商品",
  all_listings_loading: "正在加载商品...",
  all_listings_purchased: "已提交购买。",
  reason_not_logged_in: "请先登录后再购买。",
  reason_no_town: "你不属于任何城镇。",
  reason_own_listing: "这是你自己的挂单。",
  reason_no_route: "无道路或航路连接,无法购买。",
  reason_no_market_selected: "请先打开一个市场作为购买管线载体。",
  mc_warehouse_title: "从殖民地仓库上架",
  mc_warehouse_kicker: "殖民地仓库",
  mc_warehouse_hint: "直接从关联殖民地仓库抽取物品并上架到市场。",
  mc_warehouse_col_item: "物品",
  mc_warehouse_col_stock: "仓库库存",
  mc_warehouse_col_quantity: "数量",
  mc_warehouse_col_price: "单价",
  mc_warehouse_col_action: "操作",
  mc_warehouse_list: "上架",
  mc_warehouse_empty: "未关联殖民地仓库,或仓库无可上架物品。",
  mc_warehouse_count: "{count} 种物品",
  mc_warehouse_listed: "已从殖民地仓库上架。"
});

Object.assign(I18N["en-US"], {
  dismiss: "Dismiss",
  close: "Close",
  qty_decrease: "Decrease quantity",
  qty_increase: "Increase quantity",
  qty_max: "Max",
  qty_available: "{count} in stock",
  qty_unlimited: "Unlimited stock",
  qty_affordable: "wallet covers {count}",
  purchase_total: "Total",
  wallet_available: "Available balance",
  wallet_after: "Balance after",
  fulfillment_label: "Delivery",
  purchase_over_stock: "Only {count} in stock.",
  purchase_qty_invalid: "Enter a whole number of at least 1.",
  purchase_insufficient_funds: "Not enough available balance in your market wallet.",
  purchase_mode_unavailable: "This delivery option is not available for this listing.",
  purchase_confirm_total: "Buy · {total}",
  purchase_submitting: "Placing order…",
  purchase_success: "Ordered {name} × {qty}.",
  mode_checking: "Checking…",
  receiving_default: "Default receiving point",
  confirm_keep: "Keep",
  confirm_cancel_listing_title: "Take down this listing?",
  confirm_cancel_listing_message: "Buyers will no longer see it on the market.",
  confirm_cancel_listing_action: "Take down",
  confirm_cancel_buy_order_title: "Withdraw this buy order?",
  confirm_cancel_buy_order_message: "Sellers will no longer be able to fill it.",
  confirm_cancel_buy_order_action: "Withdraw",
  confirm_cancel_order_title: "Cancel this order?",
  confirm_cancel_order_message: "A cancelled order can't be restored.",
  confirm_cancel_order_action: "Cancel order",
  session_expired: "Your sign-in has expired. Sign in again to trade.",
  order_word: "Order",
  order_item_title: "{name} × {qty}",
  chart_loading: "Loading chart…",
  live_status_live: "Live",
  live_status_connecting: "Connecting",
  live_status_offline: "Reconnecting",
  live_status_polling: "Auto refresh",
  live_ticker_label: "Latest trades",
  live_order_update: "Order {id}: {status}",
  trend_24h: "24h change",
  trend_7d: "7-day change",
  layout_label: "Layout",
  layout_grid: "Grid",
  layout_heatmap: "Heatmap",
  heatmap_label: "Market heatmap: size is 24h turnover, colour is 24h change",
  order_book_kicker: "Order Book",
  order_book_title: "Market Depth",
  order_book_spread: "Spread",
  watch_add: "Watch",
  watch_on: "Watching",
  watch_filter: "Watchlist",
  filters_toggle: "Filters",
  palette_launch: "Jump to",
  palette_title: "Search goods, terminals and pages",
  palette_placeholder: "Item, terminal, page… (pinyin initials work: zs = 钻石)",
  palette_empty: "Nothing matches.",
  palette_footer: "↑↓ to choose · Enter to open · Esc to close",
  palette_kind_page: "Page",
  palette_kind_market: "Terminal",
  palette_kind_commodity: "Item",
  palette_kind_action: "Action",
  palette_toggle_theme: "Switch light / dark theme",
  palette_language: "Language",
  order_progress: "Order progress",
  order_track_on_map: "Show the ship on the map",
  eta_any_moment: "shipping any moment now",
  eta_countdown: "ships in {time}",
  order_queue_place: "Queue #{position}",
  map_mode_label: "Map view",
  map_mode_flat: "Flat map",
  map_mode_globe: "3D globe",
  globe_loading: "Loading the globe…",
  globe_failed: "The 3D globe needs WebGL, which this browser could not start. Switch back to the flat map.",
  globe_open_market: "Open this terminal",
  globe_not_tellus: "The 3D globe is only available on Tellus real-Earth worlds.",
  globe_ship_progress: "{percent}% of the route done",
  globe_ship_eta: "Arrives in {time}",
  map_library_failed: "The map failed to load. Check your connection and reload the page."
});

Object.assign(I18N["zh-CN"], {
  dismiss: "关闭",
  close: "关闭",
  qty_decrease: "减少数量",
  qty_increase: "增加数量",
  qty_max: "最大",
  qty_available: "库存 {count}",
  qty_unlimited: "库存不限",
  qty_affordable: "余额可买 {count}",
  purchase_total: "合计",
  wallet_available: "可用余额",
  wallet_after: "下单后余额",
  fulfillment_label: "配送方式",
  purchase_over_stock: "库存只有 {count}。",
  purchase_qty_invalid: "请输入不小于 1 的整数。",
  purchase_insufficient_funds: "市场钱包可用余额不足。",
  purchase_mode_unavailable: "这条挂单不支持该配送方式。",
  purchase_confirm_total: "确认购买 · {total}",
  purchase_submitting: "正在下单…",
  purchase_success: "已下单:{name} × {qty}。",
  mode_checking: "检测中…",
  receiving_default: "默认收货地",
  confirm_keep: "保留",
  confirm_cancel_listing_title: "撤下这条挂单?",
  confirm_cancel_listing_message: "撤下后买家将在市场上看不到它。",
  confirm_cancel_listing_action: "撤下挂单",
  confirm_cancel_buy_order_title: "撤销这条求购?",
  confirm_cancel_buy_order_message: "撤销后卖家将无法再向它供货。",
  confirm_cancel_buy_order_action: "撤销求购",
  confirm_cancel_order_title: "取消这笔订单?",
  confirm_cancel_order_message: "订单取消后无法恢复。",
  confirm_cancel_order_action: "取消订单",
  session_expired: "登录已过期,请重新登录后再交易。",
  order_word: "订单",
  order_item_title: "{name} × {qty}",
  chart_loading: "图表加载中…",
  live_status_live: "实时",
  live_status_connecting: "连接中",
  live_status_offline: "重连中",
  live_status_polling: "定时刷新",
  live_ticker_label: "最新成交",
  live_order_update: "订单 {id}:{status}",
  trend_24h: "24 小时涨跌",
  trend_7d: "7 天涨跌",
  layout_label: "显示方式",
  layout_grid: "网格",
  layout_heatmap: "热力图",
  heatmap_label: "市场热力图:面积为 24 小时成交额,颜色为 24 小时涨跌",
  order_book_kicker: "盘口",
  order_book_title: "买卖深度",
  order_book_spread: "价差",
  watch_add: "加入自选",
  watch_on: "已自选",
  watch_filter: "自选",
  filters_toggle: "筛选",
  palette_launch: "快速跳转",
  palette_title: "搜索商品、终端与页面",
  palette_placeholder: "物品、终端、页面…(支持拼音首字母,如 zs = 钻石)",
  palette_empty: "没有匹配的结果。",
  palette_footer: "↑↓ 选择 · Enter 打开 · Esc 关闭",
  palette_kind_page: "页面",
  palette_kind_market: "终端",
  palette_kind_commodity: "商品",
  palette_kind_action: "操作",
  palette_toggle_theme: "切换浅色 / 深色主题",
  palette_language: "语言",
  order_progress: "订单进度",
  order_track_on_map: "在地图上查看船只",
  eta_any_moment: "即将发货",
  eta_countdown: "{time} 后发货",
  order_queue_place: "排第 {position} 位",
  map_mode_label: "地图视图",
  map_mode_flat: "平面地图",
  map_mode_globe: "3D 地球",
  globe_loading: "正在加载地球…",
  globe_failed: "3D 地球需要 WebGL,这个浏览器没能启动它。请切回平面地图。",
  globe_open_market: "进入这个终端",
  globe_not_tellus: "3D 地球只在 Tellus 真实地球世界可用。",
  globe_ship_progress: "航程已完成 {percent}%",
  globe_ship_eta: "预计 {time} 后到达",
  map_library_failed: "地图加载失败,请检查网络后刷新页面。"
});

// Traditional Chinese is derived from the Simplified strings by the generated table in zh-tw.js.
I18N["zh-TW"] = window.SailboatZhTw ? window.SailboatZhTw.convertAll(I18N["zh-CN"]) : I18N["zh-CN"];

/** The saved choice, otherwise the browser language: Taiwan, Hong Kong, Macau and Hant get Traditional. */
function preferredLocale() {
  const saved = localStorage.getItem("marketWebLocale");
  if (saved) {
    return saved;
  }
  const languages = typeof navigator === "undefined" ? [] : (navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language]);
  for (const raw of languages) {
    const lang = String(raw || "").toLowerCase();
    if (lang.startsWith("zh")) {
      return /hant|-tw|-hk|-mo/.test(lang) ? "zh-TW" : "zh-CN";
    }
    if (lang.startsWith("en")) {
      return "en-US";
    }
  }
  return "zh-CN";
}

function t(key, vars = {}) {
  const locale = I18N[state.locale] || I18N["en-US"];
  let text = locale[key] || I18N["en-US"][key] || key;
  return text.replace(/\{(\w+)\}/g, (_, name) => String(vars[name] ?? ""));
}

function setNodeText(node, value) {
  if (node) {
    node.textContent = value;
  }
}

function setNodePlaceholder(node, value) {
  if (node) {
    node.placeholder = value;
  }
}

function uiTitle() {
  return state.settings.uiTitle || "Sailboat Market | Monpai 在线市场";
}

function updateStaticCopy() {
  document.documentElement.lang = state.locale;
  document.title = uiTitle();
  if (els.localeSelect) {
    els.localeSelect.value = state.locale;
  }
  setNodeText(els.brandKicker, t("brand_kicker"));
  setNodeText(els.heroTitle, uiTitle());
  setNodeText(els.heroSubtitle, t("hero_subtitle"));
  setNodeText(els.localeLabel, t("language_label"));
  setNodeText(els.terminalToggleLabel, t("terminal_picker"));
  syncThemeToggle();
  setNodeText(els.modeLabel, t("mode_label"));
  setNodeText(els.modeValue, t("mode_value"));
  setNodeText(els.sessionLabel, t("session_label"));
  setNodeText(els.flowLabel, t("flow_label"));
  setNodeText(els.flowValue, t("flow_value"));
  setNodeText(els.accessToggleLabel, t(state.accessPanelOpen ? "access_panel_open" : "access_panel_closed"));
  setNodeText(els.portalKicker, t("portal_kicker"));
  setNodeText(els.authTitle, t("auth_title"));
  setNodeText(els.authSubtitle, t("auth_subtitle"));
  setNodeText(els.commandLabel, t("command_label"));
  setNodeText(els.copyCommandButton, t("copy_command"));
  setNodeText(els.fastSigninKicker, t("fast_signin"));
  setNodeText(els.connectPlayerTitle, t("connect_player"));
  setNodeText(els.chatCaptureLabel, t("paste_chat"));
  setNodeText(els.bindButton, t("bind_account"));
  setNodeText(els.loginButton, t("account_login"));
  setNodeText(els.tokenHelper, t("token_helper"));
  setNodeText(els.marketNetworkKicker, t("market_network"));
  setNodeText(els.browseMarketsTitle, t("browse_markets"));
  setNodeText(els.refreshMarkets, t("refresh"));
  setNodeText(els.allListingsNav, t("all_listings_tab"));
  setNodeText(els.marketSearchLabel, t("market_search_label"));
  setNodeText(els.marketSidebarHint, t("market_sidebar_hint"));
  setNodePlaceholder(els.chatCapture, t("paste_chat_placeholder"));
  setNodePlaceholder(els.token, t("token_placeholder"));
  setNodePlaceholder(els.accountUsername, t("account_username_placeholder"));
  setNodePlaceholder(els.accountPassword, t("account_password_placeholder"));
  setNodePlaceholder(els.marketSearch, t("market_search_placeholder"));
  if (els.accountUsername && state.accountUsername) {
    els.accountUsername.value = state.accountUsername;
  }
  setAccessPanelOpen(state.accessPanelOpen);
  renderSession();
}

function setStatus(message, isError = false) {
  const text = String(message || "");
  state.status = isError ? "" : text;
  state.error = isError ? text : "";
  if (els.statusLive) {
    els.statusLive.textContent = text;
  }
  if (text) {
    showToast(text, isError ? "error" : "info");
  }
}

// 状态消息以浮动通知显示,不再触发整页重绘;成功消息几秒后自动消失,错误消息停留更久并可手动关闭。
// 读屏软件通过 #status-live 播报,浮动通知本身只负责视觉。
function toastRegion() {
  let region = document.querySelector("#toast-region");
  if (!region) {
    region = document.createElement("div");
    region.id = "toast-region";
    region.className = "toast-region";
    document.body.appendChild(region);
  }
  return region;
}

function showToast(message, tone = "info") {
  const region = toastRegion();
  Array.from(region.children)
    .filter((node) => node.dataset.message === message)
    .forEach((node) => node.remove());
  const toast = document.createElement("div");
  toast.className = `toast${tone === "error" ? " is-error" : ""}`;
  toast.dataset.message = message;
  const text = document.createElement("span");
  text.className = "toast-text";
  text.textContent = message;
  const close = document.createElement("button");
  close.type = "button";
  close.className = "toast-close";
  close.setAttribute("aria-label", t("dismiss"));
  close.textContent = "×";
  close.addEventListener("click", () => dismissToast(toast));
  toast.append(text, close);
  region.appendChild(toast);
  while (region.children.length > 3) {
    region.firstElementChild.remove();
  }
  toast.dismissTimer = window.setTimeout(() => dismissToast(toast), tone === "error" ? 9000 : 4500);
}

function dismissToast(toast) {
  window.clearTimeout(toast.dismissTimer);
  if (!toast.isConnected || toast.classList.contains("is-leaving")) {
    return;
  }
  toast.classList.add("is-leaving");
  window.setTimeout(() => toast.remove(), prefersReducedMotion() ? 0 : 180);
}

async function api(path, options = {}) {
  const { idempotencyKey, ...fetchOptions } = options;
  const headers = new Headers(fetchOptions.headers || {});
  if (!headers.has("Content-Type") && fetchOptions.body) {
    headers.set("Content-Type", "application/json");
  }
  if (state.sessionToken) {
    headers.set("Authorization", `Bearer ${state.sessionToken}`);
  }
  // 同一次下单意图复用同一个 key,服务端可据此丢弃重复提交(网络重试、连点)。
  if (idempotencyKey) {
    headers.set("Idempotency-Key", idempotencyKey);
  }
  const response = await fetch(path, { ...fetchOptions, headers });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && state.sessionToken && !String(path).startsWith("/api/auth/")) {
      expireSession();
    }
    const error = new Error(data.message || `Request failed: ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return data;
}

// 会话失效(服务器重启、令牌过期):清掉本地令牌,页面退回游客模式继续可浏览。
function expireSession() {
  state.sessionToken = "";
  state.session = null;
  localStorage.removeItem("marketWebSessionToken");
  renderSession();
  restartLiveFeed();
}

// crypto.randomUUID 只在 HTTPS/localhost 可用;普通 http:// 部署退回 getRandomValues。
function newIdempotencyKey() {
  try {
    if (typeof window.crypto?.randomUUID === "function") {
      return window.crypto.randomUUID();
    }
    const bytes = new Uint8Array(16);
    window.crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  } catch (_) {
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  }
}

// 绑定实际请求内容；改正业务失败的输入使用新 key，网络结果不明时重试原内容仍复用原 key。
// 保留弹窗里所有已提交内容的 key，避免改动后再改回时重复执行已成功但丢失响应的购买。
function idempotencyKeyForRequest(keys, path, body) {
  const signature = JSON.stringify([path, body]);
  if (!keys.has(signature)) {
    keys.set(signature, newIdempotencyKey());
  }
  return keys.get(signature);
}

async function loadSettings() {
  try {
    const response = await fetch("/config.json", { cache: "no-store" });
    if (response.ok) {
      const data = await response.json();
      state.settings = { ...state.settings, ...(data || {}) };
    }
  } catch (_) {
  }

  if (els.commandPreview) {
    els.commandPreview.textContent = state.settings.commandText || "/marketweb token";
  }
  updateStaticCopy();
}

async function loadServerVersion() {
  try {
    const response = await fetch("/api/debug/version", { cache: "no-store" });
    if (!response.ok) {
      return;
    }
    const data = await response.json().catch(() => null);
    applyWebResourceVersion(data?.resourceVersion);
  } catch (_) {
  }
}

function persistAccountUsername(username) {
  state.accountUsername = (username || "").trim();
  if (state.accountUsername) {
    localStorage.setItem("marketWebAccountUsername", state.accountUsername);
  } else {
    localStorage.removeItem("marketWebAccountUsername");
  }
}

function accountCredentials() {
  return {
    username: (els.accountUsername?.value || "").trim(),
    password: els.accountPassword?.value || ""
  };
}

function clearAuthInputs(clearToken = false) {
  if (clearToken && els.token) {
    els.token.value = "";
  }
  if (els.accountPassword) {
    els.accountPassword.value = "";
  }
}

async function bindAccountLogin() {
  const token = (els.token.value || "").trim();
  if (!token) {
    setStatus(t("sign_in_required"), true);
    return;
  }
  const { username, password } = accountCredentials();
  if (!username || !password) {
    setStatus(t("bind_requires_credentials"), true);
    return;
  }

  try {
    const data = await api("/api/auth/token-login", {
      method: "POST",
      body: JSON.stringify({ token, username, password })
    });
    state.sessionToken = data.sessionToken || "";
    localStorage.setItem("marketWebSessionToken", state.sessionToken);
    persistAccountUsername(data.accountUsername || username);
    clearAuthInputs(true);
    await hydrateSession();
    await loadMarkets();
    restartLiveFeed();
    setStatus(t("account_bound", { name: data.accountUsername || username }));
  } catch (error) {
    setStatus(error.message, true);
  }
}

async function login() {
  const { username, password } = accountCredentials();
  if (!username || !password) {
    setStatus(t("account_login_required"), true);
    return;
  }

  try {
    const data = await api("/api/auth/password-login", {
      method: "POST",
      body: JSON.stringify({ username, password })
    });
    state.sessionToken = data.sessionToken || "";
    localStorage.setItem("marketWebSessionToken", state.sessionToken);
    persistAccountUsername(data.accountUsername || username);
    clearAuthInputs(false);
    await hydrateSession();
    await loadMarkets();
    restartLiveFeed();
    setStatus(t("account_login_success", { name: data.accountUsername || username }));
  } catch (error) {
    setStatus(error.message, true);
  }
}

function fallbackCopyText(value) {
  const text = String(value || "");
  if (!text || !document?.body) {
    return false;
  }
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "");
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  textarea.style.top = "0";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  textarea.focus();
  textarea.select();
  textarea.setSelectionRange(0, textarea.value.length);
  let copied = false;
  try {
    copied = document.execCommand("copy");
  } catch (_) {
    copied = false;
  }
  textarea.remove();
  return copied;
}

async function copyText(value, successMessage) {
  if (!value) {
    return;
  }
  try {
    if (!navigator?.clipboard?.writeText) {
      throw new Error("Clipboard API unavailable");
    }
    await navigator.clipboard.writeText(value);
    setStatus(successMessage || t("copied"));
  } catch (_) {
    if (fallbackCopyText(value)) {
      setStatus(successMessage || t("copied"));
    } else {
      setStatus(t("clipboard_failed"), true);
    }
  }
}

function extractToken(raw) {
  const text = String(raw || "").trim();
  if (!text) {
    return "";
  }
  const matchedLabel = text.match(/(?:token|login token)\s*[:\-]\s*([A-Za-z0-9_-]{24,})/i);
  if (matchedLabel) {
    return matchedLabel[1];
  }
  const matchedBare = text.match(/\b[A-Za-z0-9_-]{24,}\b/g);
  if (!matchedBare || !matchedBare.length) {
    return "";
  }
  return matchedBare.sort((a, b) => b.length - a.length)[0];
}

async function syncTokenFromCapture(autoCopy = true) {
  const source = `${els.chatCapture?.value || ""}\n${els.token?.value || ""}`;
  const token = extractToken(source);
  if (!token) {
    return false;
  }
  els.token.value = token;
  if (autoCopy) {
    await copyText(token, t("token_extracted"));
  }
  return true;
}

async function hydrateSession() {
  if (!state.sessionToken) {
    state.session = null;
    renderSession();
    return;
  }
  try {
    state.session = await api("/api/session/me");
    persistAccountUsername(state.session?.accountUsername || state.accountUsername);
    applyWebResourceVersion(state.session?.webResourceVersion);
    renderSession();
  } catch (error) {
    state.sessionToken = "";
    state.session = null;
    localStorage.removeItem("marketWebSessionToken");
    renderSession();
    throw error;
  }
}

async function loadMarkets(historyMode = "replace", { silent = false } = {}) {
  const data = await api("/api/markets");
  state.markets = data.markets || [];
  if (!state.selectedMarketId && state.markets.length > 0) {
    state.selectedMarketId = state.markets[0].marketId;
  }
  if (state.selectedMarketId && !state.markets.some((market) => market.marketId === state.selectedMarketId)) {
    state.selectedMarketId = state.markets[0]?.marketId || "";
  }
  renderMarkets();

  if (state.selectedMarketId) {
    await loadMarketDetail(state.selectedMarketId, historyMode, { silent });
  } else {
    state.detail = null;
    renderDetail();
  }
}

const loadSequence = { detail: 0, aggregated: 0 };

async function loadMarketDetail(marketId, historyMode = "replace", { silent = false } = {}) {
  if (!marketId) {
    state.detail = null;
    state.selectedCommodityKey = "";
    syncRouteUrl(historyMode !== "push");
    renderDetail();
    return;
  }

  const sequence = ++loadSequence.detail;
  try {
    const detail = await api(`/api/markets/${marketId}`);
    // 防竞态:只采用最后一次请求的结果(快速切换终端,或后台刷新与点击交错)。
    if (sequence !== loadSequence.detail) {
      return;
    }
    state.detail = detail;
    state.detailFetchedAt = Date.now();
    state.selectedMarketId = marketId;
    if (state.mineColoniesMarketId !== marketId) {
      state.mineColoniesItems = [];
      state.mineColoniesLoaded = false;
      state.mineColoniesMarketId = marketId;
    }
    syncCommoditySelection();
    syncRouteUrl(historyMode !== "push");
    renderMarkets();
    renderDetail();
  } catch (error) {
    if (sequence !== loadSequence.detail) {
      return;
    }
    if (silent) {
      console.warn("[marketweb] background refresh failed", error);
      return;
    }
    setStatus(error.message, true);
  }
}

async function loadMineColoniesWarehouse(marketId) {
  if (!marketId) {
    return;
  }
  try {
    const data = await api(`/api/markets/${marketId}/minecolonies-warehouse`);
    // 防竞态:返回时市场已切换则丢弃。
    if (state.selectedMarketId !== marketId) {
      return;
    }
    state.mineColoniesItems = Array.isArray(data.items) ? data.items : [];
    state.mineColoniesLoaded = true;
    state.mineColoniesMarketId = marketId;
    renderDetail();
  } catch (error) {
    state.mineColoniesLoaded = true;
    setStatus(error.message, true);
    renderDetail();
  }
}

async function postMineColoniesListing(itemId, payload = {}) {
  if (!state.session) {
    setStatus(t("sign_in_to_trade"), true);
    return;
  }
  if (!state.selectedMarketId) {
    setStatus(t("reason_no_market_selected"), true);
    return;
  }
  try {
    const data = await api(`/api/markets/${state.selectedMarketId}/minecolonies-listing`, {
      method: "POST",
      body: JSON.stringify(Object.assign({ itemId }, payload))
    });
    state.detail = data;
    // 上架成功后货已离开殖民地仓库,标记未加载;renderDetail 会在库存页按需重拉刷新库存。
    state.mineColoniesLoaded = false;
    syncCommoditySelection();
    syncRouteUrl(true);
    renderGuard.resetFormsOnce = true;
    renderDetail();
    setStatus(t("mc_warehouse_listed"));
  } catch (error) {
    setStatus(error.message, true);
  }
}

const actionsInFlight = new Set();

function setActionBusy(trigger, busy) {
  if (!trigger || !trigger.isConnected) {
    return;
  }
  trigger.disabled = busy;
  trigger.setAttribute("aria-busy", busy ? "true" : "false");
}

// 所有终端写操作的统一入口:同一操作进行中时忽略重复点击,触发按钮在请求期间禁用。
async function postMarketAction(suffix, payload = {}, options = {}) {
  if (!state.session) {
    setStatus(t("sign_in_to_trade"), true);
    return false;
  }
  if (!state.selectedMarketId) {
    return false;
  }
  const marketId = state.selectedMarketId;
  const actionKey = `${marketId}${suffix}`;
  if (actionsInFlight.has(actionKey)) {
    return false;
  }
  actionsInFlight.add(actionKey);
  setActionBusy(options.trigger, true);
  try {
    const data = await api(`/api/markets/${marketId}${suffix}`, {
      method: "POST",
      body: JSON.stringify(payload),
      idempotencyKey: options.idempotencyKey || newIdempotencyKey()
    });
    if (state.selectedMarketId === marketId) {
      state.detail = data;
      syncCommoditySelection();
      syncRouteUrl(true);
      if (suffix === "/wallet/transfer") {
        renderGuard.resetWalletOnce = true;
      } else {
        renderGuard.resetFormsOnce = true;
      }
      renderDetail();
    }
    setStatus(options.successMessage || t("action_completed"));
    return true;
  } catch (error) {
    setStatus(error.message, true);
    return false;
  } finally {
    actionsInFlight.delete(actionKey);
    setActionBusy(options.trigger, false);
  }
}

async function loadAggregatedListings({ silent = false } = {}) {
  const sequence = ++loadSequence.aggregated;
  try {
    const data = await api("/api/listings");
    if (sequence !== loadSequence.aggregated) {
      return;
    }
    state.aggregatedListings = Array.isArray(data.listings) ? data.listings : [];
    state.aggregatedAuthenticated = !!data.authenticated;
    state.aggregatedViewerTownId = String(data.viewerTownId || "");
    state.aggregatedLoaded = true;
    renderDetail();
  } catch (error) {
    if (sequence !== loadSequence.aggregated) {
      return;
    }
    if (silent && state.aggregatedLoaded) {
      console.warn("[marketweb] background refresh failed", error);
      return;
    }
    state.aggregatedLoaded = true;
    setStatus(error.message, true);
    renderDetail();
  }
}

// 拉取某商品的全服"伪 detail"(对标终端详情,数据全服聚合)。race-guard:返回时选中商品已切换则丢弃。
async function loadAggregatedDetail(commodityKey, { force = false, silent = false } = {}) {
  const key = String(commodityKey || "").trim();
  if (!key) {
    return;
  }
  if (!force && state.aggregatedDetailKey === key && state.aggregatedDetailLoaded) {
    return;
  }
  // 后台刷新时保留现有内容直到新数据到达,避免页面闪回"加载中"。
  const quiet = silent && state.aggregatedDetailKey === key && state.aggregatedDetailLoaded && !!state.aggregatedDetail;
  state.aggregatedDetailKey = key;
  if (!quiet) {
    state.aggregatedDetailLoaded = false;
    state.aggregatedDetailLoading = true;
    state.aggregatedDetail = null;
    renderDetail();
  }
  try {
    const data = await api(`/api/listings/commodity?key=${encodeURIComponent(key)}`);
    // 防竞态:返回时用户已切到别的商品或离开详情则丢弃。
    if (state.aggregatedSelectedCommodityKey !== key) {
      return;
    }
    state.aggregatedDetail = data || null;
    state.aggregatedDetailLoaded = true;
    state.aggregatedDetailLoading = false;
    renderDetail();
  } catch (error) {
    if (state.aggregatedSelectedCommodityKey === key) {
      state.aggregatedDetailLoaded = true;
      state.aggregatedDetailLoading = false;
      if (!quiet) {
        setStatus(error.message, true);
        renderDetail();
      }
    }
  }
}

// 聚合详情子 tab(browse/sell/chart/index/demand 等),与终端 activeProductTab 隔离,
// 切换聚合详情 tab 不影响终端,也不会把 activeProductTab 切出 "all"。
function currentProductTab(options) {
  return options && options.aggregate ? state.aggregatedDetailTab : state.activeProductTab;
}

// ---- 对话框 ----
// 统一用原生 <dialog>:showModal() 自带焦点限制、Esc 关闭和背景遮罩;关闭后焦点回到触发按钮。
let dialogSequence = 0;

function createMarketDialog(className, titleId) {
  const dialog = document.createElement("dialog");
  dialog.className = `market-dialog ${className}`;
  if (titleId) {
    dialog.setAttribute("aria-labelledby", titleId);
  }
  document.body.appendChild(dialog);
  return dialog;
}

function showMarketDialog(dialog, { trigger = null, onDismiss = null } = {}) {
  const returnNode = trigger || document.activeElement;
  const returnSelector = focusSelectorFor(returnNode);
  const dismiss = () => (onDismiss ? onDismiss() : dialog.close());
  dialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    dismiss();
  });
  // 只有按下和松开都落在遮罩上才关闭,避免在输入框里拖选文字时误关。
  let pressedOnBackdrop = false;
  dialog.addEventListener("pointerdown", (event) => {
    pressedOnBackdrop = event.target === dialog;
  });
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog && pressedOnBackdrop) {
      dismiss();
    }
    pressedOnBackdrop = false;
  });
  dialog.addEventListener("close", () => {
    dialog.remove();
    const target = returnNode?.isConnected ? returnNode : (returnSelector ? document.querySelector(returnSelector) : null);
    if (target && typeof target.focus === "function") {
      target.focus({ preventScroll: true });
    }
  });
  dialog.showModal();
}

function confirmDialog({ title, message = "", confirmLabel = "", keepLabel = "", danger = false, trigger = null }) {
  return new Promise((resolve) => {
    const titleId = `confirm-title-${++dialogSequence}`;
    const dialog = createMarketDialog("confirm-dialog", titleId);
    dialog.setAttribute("role", "alertdialog");
    dialog.innerHTML = `
      <form class="market-dialog-body" method="dialog">
        <h3 id="${titleId}" class="market-dialog-title">${escapeHtml(title)}</h3>
        ${message ? `<p class="market-dialog-message">${escapeHtml(message)}</p>` : ""}
        <div class="market-dialog-actions">
          <button type="button" class="secondary" data-dialog-cancel autofocus>${escapeHtml(keepLabel || t("cancel"))}</button>
          <button type="submit"${danger ? ' class="danger"' : ""} data-dialog-confirm>${escapeHtml(confirmLabel || t("confirm"))}</button>
        </div>
      </form>
    `;
    let confirmed = false;
    const close = (value) => {
      confirmed = value;
      if (dialog.open) {
        dialog.close();
      }
    };
    dialog.querySelector("form").addEventListener("submit", (event) => {
      event.preventDefault();
      close(true);
    });
    dialog.querySelector("[data-dialog-cancel]").addEventListener("click", () => close(false));
    dialog.addEventListener("close", () => resolve(confirmed));
    showMarketDialog(dialog, { trigger, onDismiss: () => close(false) });
  });
}

// 撤单类操作统一先确认;kind 对应 confirm_<kind>_title / _message / _action 文案。
async function confirmMarketAction(node, kind, run) {
  const confirmed = await confirmDialog({
    title: t(`confirm_${kind}_title`),
    message: t(`confirm_${kind}_message`),
    confirmLabel: t(`confirm_${kind}_action`),
    keepLabel: t("confirm_keep"),
    danger: true,
    trigger: node
  });
  if (confirmed) {
    run();
  }
}

const PURCHASE_MODES = [
  ["SELLER_SHIP", "mode_seller_ship"],
  ["AUTO_PICKUP", "mode_auto_pickup"],
  ["REAL_PICKUP", "mode_real_pickup"]
];

// 下单对话框:终端挂单(aggregate=false,走 /api/markets/{id}/purchase,先探测可用配送方式)
// 与全部商品(aggregate=true,走 /api/listings/{listingId}/purchase)共用。
// 数量、配送方式、收货地都保存在 model 里,局部更新 DOM,不会因为重绘丢失输入。
function openPurchaseDialog({ listing, aggregate = false, trigger = null }) {
  if (!listing) {
    return;
  }
  if (!state.session) {
    setStatus(t("sign_in_to_trade"), true);
    return;
  }
  const overview = (aggregate ? state.aggregatedDetail : state.detail) || state.detail || {};
  const unitPrice = Math.max(0, Number(listing.unitPrice) || 0);
  const available = isInfiniteStock(listing) ? Infinity : Math.max(0, Math.floor(Number(listing.availableCount) || 0));
  const walletRaw = overview.walletAvailableBalance ?? overview.walletBalance;
  const walletKnown = walletRaw != null && walletRaw !== "" && Number.isFinite(Number(walletRaw));
  const wallet = walletKnown ? Number(walletRaw) : Infinity;
  const options = Array.isArray(overview.receivingWarehouseOptions) ? overview.receivingWarehouseOptions : [];
  const canReceive = !!overview.canChooseReceiving;
  const commodityKey = listing.commodityKey || "";
  const displayName = listing.itemName || listing.displayName || commodityKey;
  const origin = [listing.sellerName, listing.sourceTownName || "", listing.sourceDockName].filter(Boolean).join(" · ");
  const affordableUnits = walletKnown && unitPrice > 0 ? Math.floor(wallet / unitPrice) : Infinity;
  const maxUnits = Math.min(available, affordableUnits);
  const defaultQuantity = clampNumber(
    Math.floor(Number(state.settings.defaultPurchaseQuantity) || 1),
    1,
    Number.isFinite(available) ? Math.max(1, available) : Number.MAX_SAFE_INTEGER
  );
  const model = {
    fulfillment: "SELLER_SHIP",
    targetWarehouse: aggregate ? "" : String(options[0]?.pos || ""),
    modes: aggregate ? null : { sellerShip: false, autoPickup: false, realPickup: true, probed: false },
    submitting: false,
    error: "",
    idempotencyKeys: new Map(),
    probeSequence: 0
  };

  const titleId = `purchase-title-${++dialogSequence}`;
  const dialog = createMarketDialog("purchase-dialog", titleId);
  dialog.innerHTML = `
    <form class="market-dialog-body" method="dialog" novalidate>
      <header class="market-dialog-head">
        ${renderCommodityIcon(commodityKey, displayName)}
        <div class="market-dialog-heading">
          <p class="section-kicker">${escapeHtml(t("buy_modal_title"))}</p>
          <h3 id="${titleId}" class="market-dialog-title">${escapeHtml(displayName)}</h3>
          ${origin ? `<p class="market-dialog-origin">${escapeHtml(origin)}</p>` : ""}
        </div>
        <button type="button" class="dialog-close" data-dialog-cancel aria-label="${escapeHtml(t("close"))}">×</button>
      </header>
      <div class="purchase-dialog-qty">
        <label class="tiny-label" for="${titleId}-qty">${escapeHtml(t("quantity"))}</label>
        <div class="qty-stepper">
          <button type="button" class="secondary" data-qty-step="-1" aria-label="${escapeHtml(t("qty_decrease"))}">−</button>
          <input id="${titleId}-qty" data-qty type="number" inputmode="numeric" min="1" step="1"${Number.isFinite(available) ? ` max="${available}"` : ""} value="${defaultQuantity}" autofocus>
          <button type="button" class="secondary" data-qty-step="1" aria-label="${escapeHtml(t("qty_increase"))}">+</button>
          ${Number.isFinite(maxUnits) && maxUnits >= 1 ? `<button type="button" class="secondary" data-qty-max>${escapeHtml(t("qty_max"))}</button>` : ""}
        </div>
        <small class="purchase-dialog-hint" data-qty-hint></small>
      </div>
      <dl class="purchase-dialog-summary">
        <div><dt>${escapeHtml(t("unit_price"))}</dt><dd class="coin-figure">${number(unitPrice)}</dd></div>
        <div class="is-total"><dt>${escapeHtml(t("purchase_total"))}</dt><dd class="coin-figure" data-total>--</dd></div>
        ${walletKnown ? `
          <div><dt>${escapeHtml(t("wallet_available"))}</dt><dd class="coin-figure">${number(wallet)}</dd></div>
          <div><dt>${escapeHtml(t("wallet_after"))}</dt><dd class="coin-figure" data-after>--</dd></div>
        ` : ""}
      </dl>
      ${aggregate ? "" : `
        <fieldset class="purchase-dialog-modes">
          <legend class="tiny-label">${escapeHtml(t("fulfillment_label"))}</legend>
          ${PURCHASE_MODES.map(([mode, labelKey]) => `
            <label class="purchase-mode">
              <input type="radio" name="${titleId}-mode" value="${mode}" data-mode${mode === model.fulfillment ? " checked" : ""}>
              <span class="purchase-mode-label">${escapeHtml(t(labelKey))}</span>
              <small class="purchase-mode-reason" data-mode-reason></small>
            </label>
          `).join("")}
        </fieldset>
      `}
      ${options.length ? `
        <div class="purchase-dialog-receiving" data-receiving-wrap>
          <label class="tiny-label" for="${titleId}-receiving">${escapeHtml(t("receiving_label"))}</label>
          <select id="${titleId}-receiving" data-receiving>
            ${aggregate ? `<option value="">${escapeHtml(t("receiving_default"))}</option>` : ""}
            ${options.map((option) => {
              const value = String(option.pos || "");
              const label = option.displayName + (option.townName ? ` · ${option.townName}` : "");
              return `<option value="${escapeHtml(value)}"${value === model.targetWarehouse ? " selected" : ""}>${escapeHtml(label)}</option>`;
            }).join("")}
          </select>
        </div>
      ` : (aggregate ? "" : `<p class="purchase-dialog-note" data-receiving-none>${escapeHtml(t("receiving_none"))}</p>`)}
      <p class="purchase-dialog-error" data-error role="alert" hidden></p>
      <div class="market-dialog-actions">
        <button type="button" class="secondary" data-dialog-cancel>${escapeHtml(t("cancel"))}</button>
        <button type="submit" data-confirm>${escapeHtml(t("confirm"))}</button>
      </div>
    </form>
  `;

  const form = dialog.querySelector("form");
  const qtyInput = dialog.querySelector("[data-qty]");
  const hint = dialog.querySelector("[data-qty-hint]");
  const totalNode = dialog.querySelector("[data-total]");
  const afterNode = dialog.querySelector("[data-after]");
  const errorNode = dialog.querySelector("[data-error]");
  const confirmButton = dialog.querySelector("[data-confirm]");
  const receivingWrap = dialog.querySelector("[data-receiving-wrap]");
  const receivingNone = dialog.querySelector("[data-receiving-none]");
  const receivingSelect = dialog.querySelector("[data-receiving]");

  function readQuantity() {
    const raw = String(qtyInput.value || "").trim();
    return /^\d+$/.test(raw) ? Number(raw) : NaN;
  }

  function modeDisabled(mode) {
    if (!model.modes) {
      return false;
    }
    if (!model.modes.probed && mode !== "REAL_PICKUP") {
      return true;
    }
    const reachable = mode === "SELLER_SHIP"
      ? model.modes.sellerShip
      : (mode === "AUTO_PICKUP" ? model.modes.autoPickup : model.modes.realPickup);
    return mode === "REAL_PICKUP" ? !reachable : !(canReceive && reachable);
  }

  function modeReason(mode) {
    if (!model.modes || !modeDisabled(mode)) {
      return "";
    }
    if (!model.modes.probed) {
      return t("mode_checking");
    }
    return mode !== "REAL_PICKUP" && !canReceive ? t("mode_need_warehouse") : t("mode_unreachable");
  }

  function update() {
    const quantity = readQuantity();
    const quantityValid = Number.isInteger(quantity) && quantity >= 1 && quantity <= available;
    const total = quantityValid ? quantity * unitPrice : NaN;
    const affordable = !walletKnown || !quantityValid || total <= wallet;
    hint.textContent = [
      Number.isFinite(available) ? t("qty_available", { count: number(available) }) : t("qty_unlimited"),
      Number.isFinite(affordableUnits) ? t("qty_affordable", { count: number(affordableUnits) }) : ""
    ].filter(Boolean).join(" · ");
    totalNode.textContent = quantityValid ? number(total) : "--";
    totalNode.classList.toggle("is-empty", !quantityValid);
    if (afterNode) {
      afterNode.textContent = quantityValid ? number(wallet - total) : "--";
      afterNode.classList.toggle("is-empty", !quantityValid);
      afterNode.classList.toggle("is-negative", quantityValid && total > wallet);
    }
    dialog.querySelectorAll("[data-mode]").forEach((radio) => {
      const disabled = modeDisabled(radio.value);
      const row = radio.closest(".purchase-mode");
      radio.disabled = disabled || model.submitting;
      radio.checked = radio.value === model.fulfillment;
      row?.classList.toggle("is-disabled", disabled);
      const reason = row?.querySelector("[data-mode-reason]");
      if (reason) {
        reason.textContent = modeReason(radio.value);
      }
    });
    const needsReceiving = !aggregate && model.fulfillment !== "REAL_PICKUP";
    if (receivingWrap) {
      receivingWrap.hidden = !aggregate && !needsReceiving;
    }
    if (receivingNone) {
      receivingNone.hidden = !needsReceiving;
    }
    if (receivingSelect) {
      receivingSelect.disabled = model.submitting;
    }
    let problem = "";
    if (!state.selectedMarketId) {
      problem = t("reason_no_market_selected");
    } else if (!quantityValid) {
      problem = Number.isInteger(quantity) && quantity > available
        ? t("purchase_over_stock", { count: number(available) })
        : t("purchase_qty_invalid");
    } else if (!affordable) {
      problem = t("purchase_insufficient_funds");
    } else if (model.modes && model.modes.probed && modeDisabled(model.fulfillment)) {
      problem = t("purchase_mode_unavailable");
    } else if (needsReceiving && !options.length) {
      problem = t("receiving_none");
    }
    const waitingForProbe = !!model.modes && !model.modes.probed && model.fulfillment !== "REAL_PICKUP";
    const message = model.error || problem;
    errorNode.hidden = !message;
    errorNode.textContent = message;
    confirmButton.disabled = model.submitting || !!problem || waitingForProbe;
    confirmButton.setAttribute("aria-busy", model.submitting ? "true" : "false");
    confirmButton.textContent = model.submitting
      ? t("purchase_submitting")
      : (quantityValid ? t("purchase_confirm_total", { total: number(total) }) : t("confirm"));
    qtyInput.readOnly = model.submitting;
  }

  // 探测结果回来时若当前方式不可用,自动切到第一个可用方式。
  function ensureEnabledMode() {
    if (!model.modes || !modeDisabled(model.fulfillment)) {
      return;
    }
    const firstEnabled = PURCHASE_MODES.map(([mode]) => mode).find((mode) => !modeDisabled(mode));
    if (firstEnabled) {
      model.fulfillment = firstEnabled;
    }
  }

  async function probe() {
    if (aggregate) {
      return;
    }
    const sequence = ++model.probeSequence;
    model.modes = { ...model.modes, probed: false };
    update();
    let result = { sellerShip: false, autoPickup: false, realPickup: true };
    try {
      const response = await api(`/api/markets/${state.selectedMarketId}/probe-modes`, {
        method: "POST",
        body: JSON.stringify({ listingIndex: listing.index, targetWarehouse: model.targetWarehouse })
      });
      if (response && typeof response === "object") {
        result = {
          sellerShip: !!response.sellerShip,
          autoPickup: !!response.autoPickup,
          realPickup: response.realPickup !== false
        };
      }
    } catch (_) {
      // 探测失败:其余方式置灰,不阻断真人自提。
    }
    if (sequence !== model.probeSequence || !dialog.open) {
      return;
    }
    model.modes = { ...result, probed: true };
    ensureEnabledMode();
    update();
  }

  function setQuantity(value) {
    const upper = Number.isFinite(available) ? Math.max(1, available) : Number.MAX_SAFE_INTEGER;
    qtyInput.value = String(clampNumber(Math.floor(value) || 1, 1, upper));
    model.error = "";
    update();
  }

  qtyInput.addEventListener("input", () => {
    model.error = "";
    update();
  });
  qtyInput.addEventListener("blur", () => {
    const quantity = readQuantity();
    if (Number.isInteger(quantity)) {
      setQuantity(quantity);
    }
  });
  dialog.querySelectorAll("[data-qty-step]").forEach((button) => {
    button.addEventListener("click", () => {
      const current = readQuantity();
      setQuantity((Number.isInteger(current) ? current : 0) + Number(button.getAttribute("data-qty-step") || 0));
    });
  });
  dialog.querySelector("[data-qty-max]")?.addEventListener("click", () => setQuantity(maxUnits));
  dialog.querySelectorAll("[data-mode]").forEach((radio) => {
    radio.addEventListener("change", () => {
      if (radio.checked) {
        model.fulfillment = radio.value;
        model.error = "";
        update();
      }
    });
  });
  receivingSelect?.addEventListener("change", () => {
    model.targetWarehouse = receivingSelect.value;
    model.error = "";
    probe();
  });

  const dismiss = () => {
    if (!model.submitting && dialog.open) {
      dialog.close();
    }
  };
  dialog.querySelectorAll("[data-dialog-cancel]").forEach((button) => button.addEventListener("click", dismiss));

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    update();
    if (model.submitting || confirmButton.disabled) {
      return;
    }
    const quantity = readQuantity();
    model.submitting = true;
    model.error = "";
    update();
    const marketId = state.selectedMarketId;
    const purchasePath = aggregate ? `/api/listings/${listing.listingId}/purchase` : `/api/markets/${marketId}/purchase`;
    const body = JSON.stringify(aggregate ? {
      executorMarketId: marketId,
      quantity,
      fulfillment: "SELLER_SHIP",
      targetWarehouse: model.targetWarehouse
    } : {
      listingIndex: listing.index,
      quantity,
      fulfillment: model.fulfillment,
      targetWarehouse: model.targetWarehouse
    });
    const request = {
      method: "POST",
      body,
      idempotencyKey: idempotencyKeyForRequest(model.idempotencyKeys, purchasePath, body)
    };
    try {
      if (aggregate) {
        await api(purchasePath, request);
        model.submitting = false;
        dialog.close();
        setStatus(t("purchase_success", { qty: number(quantity), name: displayName }));
        // 详情上下文:刷新该商品的全服伪 detail(挂单数随购买变化);否则刷新聚合网格。钱包随后静默刷新。
        if (state.aggregatedDetailKey) {
          await loadAggregatedDetail(state.aggregatedDetailKey, { force: true, silent: true });
        } else {
          await loadAggregatedListings({ silent: true });
        }
        if (state.selectedMarketId) {
          loadMarketDetail(state.selectedMarketId, "replace", { silent: true });
        }
      } else {
        const data = await api(purchasePath, request);
        model.submitting = false;
        dialog.close();
        if (state.selectedMarketId === marketId) {
          state.detail = data;
          syncCommoditySelection();
          syncRouteUrl(true);
          renderGuard.resetFormsOnce = true;
          renderDetail();
        }
        setStatus(t("purchase_success", { qty: number(quantity), name: displayName }));
      }
    } catch (error) {
      model.submitting = false;
      model.error = error.message || String(error);
      if (dialog.open) {
        update();
        qtyInput.focus({ preventScroll: true });
      }
    }
  });

  showMarketDialog(dialog, { trigger, onDismiss: dismiss });
  update();
  qtyInput.select();
  hydrateCommodityIcons();
  probe();
}

// ---- 交互(事件委托) ----
// 内容由 morph 原地更新,元素会长期存在,也可能被改造成别的按钮,所以不在每次渲染后逐个绑定监听器:
// 所有交互在 document 上委托一次,事件发生时按元素当前的属性分派。
const delegatedHandlers = { click: [], change: [], input: [], compositionend: [] };

function onDelegated(type, selector, handler) {
  delegatedHandlers[type].push({ selector, handler });
}

function installDelegatedHandlers() {
  Object.keys(delegatedHandlers).forEach((type) => {
    document.addEventListener(type, (event) => {
      const origin = event.target instanceof Element ? event.target : event.target?.parentElement;
      if (!origin) {
        return;
      }
      delegatedHandlers[type].forEach(({ selector, handler }) => {
        const node = origin.closest(selector);
        if (node) {
          handler(node, event);
        }
      });
    });
  });
  // toggle 不冒泡,在捕获阶段接收"更多"折叠面板的开合。
  document.addEventListener("toggle", (event) => {
    const strip = event.target;
    if (!(strip instanceof Element) || !strip.matches(".market-strip")) {
      return;
    }
    const open = !!strip.open;
    // 模板改变 open 属性时也会触发 toggle;只在新展开时播放动画。
    const opening = open && !state.marketStripOpen;
    state.marketStripOpen = open;
    strip.classList.toggle("is-opening", opening && !prefersReducedMotion());
  }, true);
  document.addEventListener("animationend", (event) => {
    if (event.animationName === "panelDrop" && event.target instanceof Element && event.target.matches(".market-strip")) {
      event.target.classList.remove("is-opening");
    }
  });
}

function registerDetailActions() {
  // 搜索:输入法组字期间(拼音还没选字)不更新结果,选字完成后再搜索。
  const applySearch = (search) => {
    const value = search.value || "";
    if (value === state.commodityQuery) {
      return;
    }
    state.commodityQuery = value;
    renderDetailPreservingScroll();
  };
  onDelegated("input", "#commodity-search", (search, event) => {
    if (!event.isComposing) {
      applySearch(search);
    }
  });
  onDelegated("compositionend", "#commodity-search", (search) => applySearch(search));

  onDelegated("click", "[data-catalog-group]", (node) => {
    const group = normalizeCatalogGroup(node.getAttribute("data-catalog-group") || "all");
    if (group === "all") {
      state.catalogFilters.categoryGroup = "all";
      state.catalogFilters.category = "all";
      state.catalogExpandedGroup = "all";
      state.catalogHoverGroup = "all";
    } else {
      state.catalogFilters.categoryGroup = group;
      state.catalogFilters.category = "all";
      state.catalogExpandedGroup = group;
      state.catalogHoverGroup = "all";
    }
    persistCatalogFilters();
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-catalog-subcategory]", (node) => {
    const category = normalizeCatalogCategory(node.getAttribute("data-catalog-subcategory") || "all");
    const group = normalizeCatalogGroup(node.getAttribute("data-catalog-subcategory-group") || categoryGroupForCategory(category));
    state.catalogFilters.categoryGroup = group;
    state.catalogFilters.category = category;
    state.catalogExpandedGroup = group;
    state.catalogHoverGroup = "all";
    persistCatalogFilters();
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-catalog-rarity]", (node) => {
    state.catalogFilters.rarity = normalizeCatalogRarity(node.getAttribute("data-catalog-rarity") || "all");
    persistCatalogFilters();
    renderDetailPreservingScroll();
  });

  onDelegated("change", "#catalog-price-min", (node) => {
    state.catalogFilters.minPrice = sanitizeCatalogPriceInput(node.value);
    persistCatalogFilters();
    renderDetailPreservingScroll();
  });

  onDelegated("change", "#catalog-price-max", (node) => {
    state.catalogFilters.maxPrice = sanitizeCatalogPriceInput(node.value);
    persistCatalogFilters();
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-browse-order-view]", (node) => {
    setBrowseOrderView(node.getAttribute("data-browse-order-view") || "purchase");
    state.activeProductTab = "browse";
    syncRouteUrl(false);
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-card-commodity-key]", (node) => {
    withViewTransition(node, () => {
      state.selectedCommodityKey = node.getAttribute("data-card-commodity-key") || "";
      state.activeProductTab = normalizePageRoute(node.getAttribute("data-card-route") || "buy");
      syncRouteUrl(false);
      renderDetailPreservingScroll();
    });
  });

  onDelegated("click", "[data-watch-toggle]", (node) => toggleWatch(node.getAttribute("data-watch-toggle") || ""));

  onDelegated("click", "[data-catalog-watch]", () => {
    state.catalogFilters.watchOnly = !state.catalogFilters.watchOnly;
    persistCatalogFilters();
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-toggle-filters]", () => {
    state.mobileFiltersOpen = !state.mobileFiltersOpen;
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-open-palette]", () => openCommandPalette());

  // 订单 → 地图:切到地图页并跟随这笔订单的运输船。
  onDelegated("click", "[data-track-shipment]", (node) => {
    state.pendingShipmentFocus = node.getAttribute("data-track-shipment") || "";
    navigateToRoute("map");
  });

  onDelegated("click", "[data-map-mode]", (node) => setMapMode(node.getAttribute("data-map-mode")));

  onDelegated("click", "[data-aggregate-card-commodity-key]", (node) => {
    const key = node.getAttribute("data-aggregate-card-commodity-key") || "";
    state.aggregatedSelectedCommodityKey = key;
    state.aggregatedDetailTab = "browse";
    renderDetailPreservingScroll();
    if (key) {
      loadAggregatedDetail(key);
    }
  });

  // 聚合详情子 tab 切换:只改 aggregatedDetailTab(与终端 activeProductTab 隔离,保持在 "all" 视图内)。
  onDelegated("click", "[data-aggregate-tab]", (node) => {
    state.aggregatedDetailTab = normalizePageRoute(node.getAttribute("data-aggregate-tab") || "browse");
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-aggregate-detail-back]", () => {
    state.aggregatedSelectedCommodityKey = "";
    state.aggregatedDetail = null;
    state.aggregatedDetailKey = "";
    state.aggregatedDetailLoaded = false;
    state.aggregatedDetailTab = "browse";
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-browse-layout]", (node) => {
    state.browseLayout = node.getAttribute("data-browse-layout") === "heatmap" ? "heatmap" : "grid";
    localStorage.setItem("marketWebBrowseLayout", state.browseLayout);
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-catalog-sort]", (node) => {
    cycleCatalogSort(node.getAttribute("data-catalog-sort") || "activity");
    renderDetailPreservingScroll();
  });

  onDelegated("click", "#market-detail [data-route], #workspace-nav [data-route]", (node) => {
    navigateToRoute(node.getAttribute("data-route") || "browse");
  });

  onDelegated("click", "[data-inventory-overview]", () => {
    state.selectedCommodityKey = "";
    state.activeProductTab = "inventory";
    syncRouteUrl(false);
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-storage-choice]", (node) => {
    const storageIndex = node.getAttribute("data-storage-choice") || "";
    const input = document.querySelector("#create-listing-storage");
    if (input) {
      input.value = storageIndex;
    }
    document.querySelectorAll("[data-storage-choice]").forEach((candidate) => {
      const active = candidate === node;
      candidate.classList.toggle("active", active);
      candidate.setAttribute("aria-pressed", active ? "true" : "false");
    });
    updateCreateListingFormState();
  });

  onDelegated("click", "[data-chart-timeframe]", (node) => {
    state.activeChartTimeframe = node.getAttribute("data-chart-timeframe") || "1h";
    persistChartPreferences();
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-chart-indicator]", (node) => {
    const key = node.getAttribute("data-chart-indicator") || "";
    if (!key) {
      return;
    }
    state.chartIndicators[key] = !state.chartIndicators[key];
    persistChartPreferences();
    renderDetailPreservingScroll();
  });

  onDelegated("change", "[data-chart-flag]", (node) => {
    const key = node.getAttribute("data-chart-flag") || "";
    if (!key) {
      return;
    }
    state.chartFlags[key] = !!node.checked;
    persistChartPreferences();
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-chart-reset]", () => {
    if (chartState.chart) {
      chartState.chart.timeScale().fitContent();
    }
  });

  onDelegated("change", "#create-listing-storage", () => updateCreateListingFormState());
  onDelegated("input", "#create-listing-price, #create-listing-quantity", () => updateCreateListingFormState());
  onDelegated("click", "#create-listing-button", (button) => postMarketAction("/listings", {
    storageIndex: numberValue("#create-listing-storage", -1),
    quantity: numberValue("#create-listing-quantity", 1),
    unitPrice: numberValue("#create-listing-price", 0),
    sellerNote: valueOf("#create-listing-note")
  }, { trigger: button }));

  // 求购物品 ID:输入停顿 360ms 后解析,失焦(change)时立即解析。
  onDelegated("input", "#buy-order-key", () => {
    window.clearTimeout(buyOrderResolveTimer);
    buyOrderResolveSequence += 1;
    const sequence = buyOrderResolveSequence;
    setBuyOrderPreviewPending(t("buy_order_item_resolving"));
    buyOrderResolveTimer = window.setTimeout(() => resolveBuyOrderItemInput(sequence), 360);
  });
  onDelegated("change", "#buy-order-key", () => {
    window.clearTimeout(buyOrderResolveTimer);
    buyOrderResolveSequence += 1;
    resolveBuyOrderItemInput(buyOrderResolveSequence);
  });
  onDelegated("click", "#buy-order-button", (buyOrderButton) => {
    const resolvedKey = normalizeCommodityKey(buyOrderButton.getAttribute("data-resolved-commodity-key") || valueOf("#buy-order-key"));
    if (buyOrderButton.getAttribute("data-item-valid") !== "true" || !resolvedKey) {
      setBuyOrderPreviewError(t("buy_order_item_not_found"));
      return;
    }
    const referencePrice = numberValue("#buy-order-reference-price", 1);
    const unitPrice = numberValue("#buy-order-unit-price", referencePrice);
    const derivedBp = buyOrderPriceToBp(unitPrice, referencePrice);
    postMarketAction("/buy-orders", {
      commodityKey: resolvedKey,
      quantity: numberValue("#buy-order-quantity", 1),
      minPriceBp: derivedBp,
      maxPriceBp: derivedBp
    }, { trigger: buyOrderButton });
  });
  onDelegated("input", "#buy-order-unit-price", () => updateBuyOrderPricePreview());

  onDelegated("click", "#claim-credits-button", (button) => postMarketAction("/credits/claim", {}, { trigger: button }));

  onDelegated("click", "#dispatch-button", (button) => postMarketAction("/dispatch/retry", {
    orderIndex: numberValue("#dispatch-order-index", 0),
    terminalType: valueOf("#dispatch-terminal-type") || state.selectedDispatchTerminal || "PORT"
  }, { trigger: button }));

  onDelegated("click", "#cancel-order-button", (button) => {
    const orderId = button.getAttribute("data-order-id");
    if (orderId) {
      confirmMarketAction(button, "cancel_order", () => postMarketAction(`/purchase-orders/${orderId}/cancel`, {}, { trigger: button }));
    }
  });

  onDelegated("change", "#dispatch-terminal-type", () => {
    state.selectedDispatchTerminal = valueOf("#dispatch-terminal-type") || "PORT";
    localStorage.setItem("marketWebDispatchTerminal", state.selectedDispatchTerminal);
    renderDetailPreservingScroll();
  });

  onDelegated("change", "#dispatch-order-index", () => {
    state.selectedDispatchOrderIndex = Math.max(0, numberValue("#dispatch-order-index", 0));
    renderDetailPreservingScroll();
  });

  onDelegated("click", "[data-purchase-index]", (node) => {
    const index = Number(node.getAttribute("data-purchase-index") || "-1");
    const listing = (state.detail?.listings || []).find((entry) => Number(entry.index) === index);
    openPurchaseDialog({ listing, trigger: node });
  });

  onDelegated("click", "[data-aggregate-purchase]", (node) => {
    const listingId = node.getAttribute("data-aggregate-purchase") || "";
    const listing = (state.aggregatedDetail?.listings || []).find((entry) => String(entry.listingId) === listingId)
      || state.aggregatedListings.find((entry) => String(entry.listingId) === listingId);
    openPurchaseDialog({ listing, aggregate: true, trigger: node });
  });

  onDelegated("click", "[data-cancel-listing]", (node) => confirmMarketAction(node, "cancel_listing", () => postMarketAction(
    `/listings/${node.getAttribute("data-cancel-listing")}/cancel`, {}, { trigger: node }
  )));

  onDelegated("click", "[data-cancel-buy-order]", (node) => confirmMarketAction(node, "cancel_buy_order", () => postMarketAction(
    `/buy-orders/${node.getAttribute("data-cancel-buy-order")}/cancel`, {}, { trigger: node }
  )));

  onDelegated("click", "[data-cancel-purchase-order]", (node) => {
    const orderId = node.getAttribute("data-cancel-purchase-order");
    if (orderId) {
      confirmMarketAction(node, "cancel_order", () => postMarketAction(`/purchase-orders/${orderId}/cancel`, {}, { trigger: node }));
    }
  });

  onDelegated("click", "[data-mc-list]", (node) => {
    const itemId = node.getAttribute("data-mc-list") || "";
    const index = node.getAttribute("data-mc-index") || "";
    const colonyId = Number(node.getAttribute("data-mc-colony") || "0");
    if (!itemId) {
      return;
    }
    postMineColoniesListing(itemId, {
      colonyId,
      quantity: numberValue(`#mc-qty-${index}`, 1),
      unitPrice: numberValue(`#mc-price-${index}`, 0),
      sellerNote: ""
    });
  });

  onDelegated("click", "#wallet-dock-toggle", () => {
    state.walletCollapsed = !state.walletCollapsed;
    localStorage.setItem("marketWebWalletCollapsed", state.walletCollapsed ? "1" : "0");
    renderTopbarWallet();
  });

  onDelegated("click", "[data-wallet-action]", (node) => {
    const action = node.getAttribute("data-wallet-action") || "";
    const amount = action === "CLAIM_CREDITS_TO_WALLET"
      ? 0
      : numberValue("#wallet-transfer-amount", 0);
    postMarketAction("/wallet/transfer", { action, amount }, { trigger: node });
  });

  // 成交跑马灯:点一条成交,打开该商品的全服详情。
  onDelegated("click", "[data-ticker-commodity]", (node) => openAggregatedCommodity(node.getAttribute("data-ticker-commodity") || ""));

  // 只处理顶栏终端列表;地图侧栏里的市场行也带 data-market-id,由 map.js 自己处理。
  onDelegated("click", "#market-list [data-market-id]", (node) => switchMarket(node.getAttribute("data-market-id") || ""));
}

// ---- 导航 ----
function navigateToRoute(route) {
  const nextRoute = normalizePageRoute(route);
  if (nextRoute === "inventory" && state.activeProductTab !== "inventory") {
    state.selectedCommodityKey = "";
  }
  state.activeProductTab = nextRoute === "inventory" && !(state.detail?.canManage) ? "browse" : nextRoute;
  syncRouteUrl(false);
  renderDetailPreservingScroll();
}

function switchMarket(marketId) {
  if (!marketId) {
    return;
  }
  state.commodityQuery = "";
  state.activeProductTab = "browse";
  state.selectedCommodityKey = "";
  state.catalogHoverGroup = "all";
  state.catalogExpandedGroup = currentCatalogActiveGroup();
  setTerminalPanelOpen(false);
  loadMarketDetail(marketId, "push");
}

// 在当前终端打开商品(交易页:挂单、盘口深度与下单)。
function openMarketCommodity(commodityKey) {
  if (!commodityKey) {
    return;
  }
  state.selectedCommodityKey = commodityKey;
  state.activeProductTab = "sell";
  syncRouteUrl(false);
  renderDetailPreservingScroll();
}

// 打开商品的全服详情(全部商品)。
function openAggregatedCommodity(commodityKey) {
  if (!commodityKey) {
    return;
  }
  state.activeProductTab = "all";
  state.aggregatedSelectedCommodityKey = commodityKey;
  state.aggregatedDetailTab = "browse";
  syncRouteUrl(false);
  renderDetailPreservingScroll();
  loadAggregatedDetail(commodityKey);
}

function switchLocale(locale) {
  state.locale = locale || "zh-CN";
  localStorage.setItem("marketWebLocale", state.locale);
  if (els.localeSelect) {
    els.localeSelect.value = state.locale;
  }
  updateStaticCopy();
  renderMarkets();
  renderDetail();
  renderLiveStatus();
  renderLiveTicker();
}

// 商品卡片 → 详情页:支持 View Transitions 的浏览器里,物品图标从卡片"飞"到详情页大图的位置。
function withViewTransition(sourceNode, update) {
  const art = sourceNode?.querySelector?.(".goods-art");
  if (typeof document.startViewTransition !== "function" || prefersReducedMotion() || !art) {
    update();
    return;
  }
  art.style.viewTransitionName = "commodity-art";
  renderGuard.inViewTransition = true;
  const transition = document.startViewTransition(() => {
    art.style.viewTransitionName = "";
    update();
    const hero = document.querySelector(".goods-hero > .goods-art");
    if (hero) {
      hero.style.viewTransitionName = "commodity-art";
    }
  });
  transition.finished.finally(() => {
    renderGuard.inViewTransition = false;
    const hero = document.querySelector(".goods-hero > .goods-art");
    if (hero) {
      hero.style.viewTransitionName = "";
    }
  });
}

// ---- 命令面板 ----
// Ctrl+K(Mac 为 ⌘K)或 "/" 打开:搜索商品、终端、页面与常用操作,支持拼音首字母;↑↓ 选择,Enter 执行,Esc 关闭。
// 用 lit-html 渲染:列表增量更新,输入框不会因重绘丢失焦点或输入法组字。
const palette = { dialog: null, query: "", active: 0, results: [], items: null };

const PALETTE_PAGES = [
  ["all", "all_listings_tab"],
  ["browse", "explore_tab"],
  ["sell", "trade_tab"],
  ["my_orders", "my_orders_tab"],
  ["demand", "buy_order_tab"],
  ["chart", "analytics_tab"],
  ["map", "map_tab"]
];

function paletteItems() {
  const items = [];
  PALETTE_PAGES.forEach(([route, labelKey]) => {
    items.push({ id: `page:${route}`, kind: "page", label: t(labelKey), run: () => navigateToRoute(route) });
  });
  state.markets.forEach((market) => {
    items.push({
      id: `market:${market.marketId}`,
      kind: "market",
      label: market.marketName || market.marketId,
      hint: [market.townName, market.ownerName].filter(Boolean).join(" · "),
      run: () => switchMarket(market.marketId)
    });
  });
  const seen = new Set();
  if (state.detail) {
    buildCommodityCatalog(state.detail).forEach((commodity) => {
      seen.add(commodity.commodityKey);
      items.push({
        id: `commodity:${commodity.commodityKey}`,
        kind: "commodity",
        label: commodity.displayName,
        key: commodity.commodityKey,
        hint: [commodity.bestSell == null ? "" : number(commodity.bestSell), state.detail.marketName].filter(Boolean).join(" · "),
        run: () => openMarketCommodity(commodity.commodityKey)
      });
    });
  }
  if (state.aggregatedLoaded) {
    buildCommodityCatalog({ listings: state.aggregatedListings }).forEach((commodity) => {
      if (seen.has(commodity.commodityKey)) {
        return;
      }
      items.push({
        id: `all:${commodity.commodityKey}`,
        kind: "commodity",
        label: commodity.displayName,
        key: commodity.commodityKey,
        hint: [commodity.bestSell == null ? "" : number(commodity.bestSell), t("all_listings_tab")].filter(Boolean).join(" · "),
        run: () => openAggregatedCommodity(commodity.commodityKey)
      });
    });
  }
  items.push({ id: "action:theme", kind: "action", label: t("palette_toggle_theme"), run: () => applyTheme(currentTheme() === "light" ? "dark" : "light", true) });
  [["zh-CN", "简体中文"], ["zh-TW", "繁體中文"], ["en-US", "English"]].forEach(([locale, name]) => {
    if (locale !== state.locale) {
      items.push({ id: `action:locale:${locale}`, kind: "action", label: `${t("palette_language")} · ${name}`, run: () => switchLocale(locale) });
    }
  });
  return items;
}

function paletteScore(item, query) {
  if (!query) {
    if (item.kind === "commodity") {
      return isWatched(item.key) ? 4 : 0;
    }
    return item.kind === "page" ? 3 : (item.kind === "market" ? 2 : 1);
  }
  const label = String(item.label || "").toLowerCase();
  const initials = pinyinInitials(item.label);
  const key = String(item.key || "").toLowerCase();
  const bonus = isWatched(item.key) ? 5 : 0;
  if (label.startsWith(query)) {
    return 100 + bonus;
  }
  if (initials && initials.startsWith(query)) {
    return 90 + bonus;
  }
  if (label.includes(query)) {
    return 70 + bonus;
  }
  if (key && (key.split(":").pop().startsWith(query) || key.includes(query))) {
    return 60 + bonus;
  }
  if (initials && initials.includes(query)) {
    return 50 + bonus;
  }
  return String(item.hint || "").toLowerCase().includes(query) ? 30 : 0;
}

function searchPalette(query) {
  const normalized = String(query || "").trim().toLowerCase();
  palette.items = palette.items || paletteItems();
  return palette.items
    .map((item) => ({ item, score: paletteScore(item, normalized) }))
    .filter(({ score }) => (normalized ? score > 0 : score > 0 || palette.items.length < 15))
    .sort((left, right) => right.score - left.score || String(left.item.label).localeCompare(String(right.item.label), "zh-Hans-CN"))
    .slice(0, normalized ? 30 : 14)
    .map(({ item }) => item);
}

function openCommandPalette() {
  if (palette.dialog || !window.litHtml) {
    return;
  }
  const dialog = createMarketDialog("command-palette", "");
  dialog.setAttribute("aria-label", t("palette_title"));
  palette.dialog = dialog;
  palette.query = "";
  palette.active = 0;
  palette.items = null;
  dialog.addEventListener("close", () => {
    palette.dialog = null;
    palette.items = null;
  });
  renderCommandPalette();
  showMarketDialog(dialog, { onDismiss: () => dialog.close() });
  dialog.querySelector(".palette-input")?.focus();
}

function renderCommandPalette({ scroll = false } = {}) {
  const dialog = palette.dialog;
  if (!dialog) {
    return;
  }
  const { html, render, repeat, nothing, live: liveValue } = window.litHtml;
  palette.results = searchPalette(palette.query);
  palette.active = Math.min(palette.active, Math.max(0, palette.results.length - 1));
  const activeId = palette.results.length ? `palette-option-${palette.active}` : "";
  render(html`
    <div class="palette-body">
      <div class="palette-search">
        <input class="palette-input" type="text" role="combobox" aria-expanded="true" aria-controls="palette-listbox"
          aria-activedescendant=${activeId || nothing} aria-label=${t("palette_title")} autocomplete="off" spellcheck="false"
          placeholder=${t("palette_placeholder")} .value=${liveValue(palette.query)}
          @input=${onPaletteInput} @compositionend=${onPaletteInput} @keydown=${onPaletteKeydown}>
        <kbd>Esc</kbd>
      </div>
      <ul id="palette-listbox" class="palette-list" role="listbox" aria-label=${t("palette_title")}>
        ${repeat(palette.results, (item) => item.id, (item, index) => html`
          <li id=${`palette-option-${index}`} role="option" aria-selected=${index === palette.active ? "true" : "false"}
            class=${index === palette.active ? "palette-option is-active" : "palette-option"}
            @click=${() => runPaletteItem(item)} @pointermove=${() => setPaletteActive(index)}>
            <span class="palette-kind">${t(`palette_kind_${item.kind}`)}</span>
            <span class="palette-label">${isWatched(item.key) ? "★ " : ""}${item.label}</span>
            ${item.hint ? html`<span class="palette-hint">${item.hint}</span>` : nothing}
          </li>
        `)}
      </ul>
      ${palette.results.length ? nothing : html`<p class="palette-empty">${t("palette_empty")}</p>`}
      <p class="palette-footer">${t("palette_footer")}</p>
    </div>
  `, dialog);
  if (scroll) {
    dialog.querySelector(".palette-option.is-active")?.scrollIntoView({ block: "nearest" });
  }
}

function onPaletteInput(event) {
  if (event.type === "input" && event.isComposing) {
    return;
  }
  palette.query = event.target.value;
  palette.active = 0;
  renderCommandPalette();
}

function setPaletteActive(index) {
  if (palette.active !== index) {
    palette.active = index;
    renderCommandPalette();
  }
}

function onPaletteKeydown(event) {
  if (event.isComposing) {
    return;
  }
  const count = palette.results.length;
  if (event.key === "ArrowDown" && count) {
    event.preventDefault();
    palette.active = (palette.active + 1) % count;
    renderCommandPalette({ scroll: true });
  } else if (event.key === "ArrowUp" && count) {
    event.preventDefault();
    palette.active = (palette.active - 1 + count) % count;
    renderCommandPalette({ scroll: true });
  } else if (event.key === "Enter" && count) {
    event.preventDefault();
    runPaletteItem(palette.results[palette.active]);
  }
}

function runPaletteItem(item) {
  palette.dialog?.close();
  item?.run?.();
}

document.addEventListener("keydown", (event) => {
  if (event.defaultPrevented || event.isComposing || palette.dialog) {
    return;
  }
  const target = event.target;
  const editing = target instanceof HTMLElement && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));
  const shortcut = (event.key === "k" || event.key === "K") && (event.ctrlKey || event.metaKey) && !event.altKey;
  if (shortcut || (event.key === "/" && !editing && !event.ctrlKey && !event.metaKey && !event.altKey)) {
    event.preventDefault();
    openCommandPalette();
  }
});

// 每次渲染后同步依赖当前 DOM 的派生状态:挂单价格预览、求购物品预览与按钮状态。
function syncDetailControls() {
  flashChangedPrices();
  syncEtaCountdowns();
  if (document.querySelector("#create-listing-button")) {
    updateCreateListingFormState();
  }
  syncBuyOrderControls();
  if (document.querySelector("#buy-order-unit-price")) {
    updateBuyOrderPricePreview();
  }
}

function normalizeCatalogSortMode(mode) {
  const normalized = String(mode || "").trim().toLowerCase();
  if (CATALOG_SORT_MODES.has(normalized)) {
    return normalized;
  }
  if (normalized === "activity") {
    return "quantity";
  }
  if (normalized === "change") {
    return "time";
  }
  return "quantity";
}

function defaultCatalogSortDirection(mode) {
  return normalizeCatalogSortMode(mode) === "name" ? "asc" : "desc";
}

function normalizeCatalogCategory(value) {
  const normalized = String(value || "all").trim().toLowerCase();
  if (!normalized || normalized === "all") {
    return "all";
  }
  return normalized.replace(/\s+/g, "_");
}

function normalizeCatalogGroup(value) {
  const normalized = String(value || "all").trim().toLowerCase();
  if (!normalized || normalized === "all") {
    return "all";
  }
  return CATALOG_CATEGORY_GROUP_ORDER.includes(normalized) ? normalized : "other";
}

function normalizeCatalogRarity(value) {
  const normalized = String(value ?? "all").trim().toLowerCase();
  if (!normalized || normalized === "all") {
    return "all";
  }
  const named = {
    common: 0,
    uncommon: 1,
    rare: 2,
    epic: 3,
    legend: 4,
    legendary: 4,
    extraordinary: 5
  }[normalized];
  if (named !== undefined) {
    return named;
  }
  const numeric = Number(normalized);
  if (!Number.isFinite(numeric)) {
    return 0;
  }
  return Math.max(0, Math.min(5, Math.round(numeric)));
}

function sanitizeCatalogPriceInput(value) {
  const raw = String(value ?? "").trim();
  if (!raw) {
    return "";
  }
  const normalized = raw.replace(/[^\d]/g, "");
  return normalized ? String(Number(normalized)) : "";
}

function commodityEntries(commodity) {
  return [
    ...(commodity?.listings || []),
    ...(commodity?.storageEntries || []),
    ...(commodity?.myBuyOrders || [])
  ];
}

function rawCommodityCategories(commodity) {
  return unique(commodityEntries(commodity)
    .map((entry) => normalizeCatalogCategory(entry.category))
    .filter((value) => value && value !== "all"));
}

function prettyCategoryName(category) {
  return String(category || "")
    .split("_")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ") || "Other";
}

function rarityKey(rarity) {
  return ({
    0: "common",
    1: "uncommon",
    2: "rare",
    3: "epic",
    4: "legend",
    5: "extraordinary"
  })[normalizeCatalogRarity(rarity)] || "common";
}

function deriveCommodityCategory(commodity) {
  const explicit = rawCommodityCategories(commodity);
  if (explicit.length) {
    return explicit[0];
  }
  return "other";
}

function deriveCommodityRarity(commodity) {
  const rarities = commodityEntries(commodity)
    .map((entry) => normalizeCatalogRarity(entry.rarity))
    .filter((value) => value !== "all");
  if (!rarities.length) {
    return 0;
  }
  return Math.max(...rarities);
}

function rarityRank(rarity) {
  return Number(normalizeCatalogRarity(rarity) === "all" ? -1 : normalizeCatalogRarity(rarity));
}

function rarityClassName(rarity) {
  const normalized = normalizeCatalogRarity(rarity);
  return normalized === "all" ? "rarity-all" : `rarity-${rarityKey(normalized)}`;
}

function commodityFilterPrice(commodity) {
  const latestPoint = latestChartPoint(primaryChartSeries(commodity));
  return Number(commodity.bestSell ?? commodity.suggestedUnitPrice ?? commodity.referencePrice ?? latestPoint?.averageUnitPrice ?? latestPoint?.closeUnitPrice ?? NaN);
}

function commoditySortTimestamp(commodity) {
  const timestamps = [];
  const chart = primaryChartSeries(commodity);
  const latest = latestChartPoint(chart);
  if (latest?.bucketAt) {
    timestamps.push(Number(latest.bucketAt));
  }
  Object.values(commodity?.candles || {}).forEach((series) => {
    const points = series?.points || [];
    const last = points[points.length - 1];
    if (last?.bucketAt) {
      timestamps.push(Number(last.bucketAt));
    }
  });
  (commodity?.myBuyOrders || []).forEach((entry) => {
    if (entry?.createdAt) {
      timestamps.push(Number(entry.createdAt));
    }
  });
  (commodity?.storageEntries || []).forEach((entry) => {
    if (Number.isFinite(Number(entry?.index))) {
      timestamps.push(Number(entry.index) + 1);
    }
  });
  return timestamps.reduce((best, value) => (Number.isFinite(value) && value > best ? value : best), 0);
}

function commoditySortQuantity(commodity) {
  return Number(commodity.sellUnits || 0) + Number(commodity.storageUnits || 0) + Number(commodity.demandUnits || 0);
}

function catalogRarities(catalog) {
  return CATALOG_RARITY_ORDER.filter((rarity) => catalog.some((commodity) => commodity.rarity === rarity));
}

function catalogCategories(catalog) {
  const seen = new Set(catalog.map((commodity) => normalizeCatalogCategory(commodity.category)).filter((value) => value && value !== "all"));
  const ordered = CATALOG_CATEGORY_ORDER.filter((category) => seen.has(category));
  const extras = Array.from(seen).filter((category) => !CATALOG_CATEGORY_ORDER.includes(category)).sort((left, right) => left.localeCompare(right, state.locale));
  return [...ordered, ...extras];
}

function categoryGroupForCategory(category) {
  const normalized = normalizeCatalogCategory(category);
  if (normalized === "all") {
    return "all";
  }
  const found = CATALOG_CATEGORY_GROUP_ORDER.find((group) => (CATALOG_CATEGORY_GROUPS[group] || []).includes(normalized));
  return found || "other";
}

function catalogGroups(catalog) {
  const seen = new Set(catalogCategories(catalog).map((category) => categoryGroupForCategory(category)).filter((value) => value && value !== "all"));
  return CATALOG_CATEGORY_GROUP_ORDER.filter((group) => seen.has(group));
}

function categoriesForGroup(group, catalog) {
  const normalized = normalizeCatalogGroup(group);
  if (normalized === "all") {
    return catalogCategories(catalog);
  }
  return catalogCategories(catalog).filter((category) => categoryGroupForCategory(category) === normalized);
}

function currentCatalogActiveGroup() {
  const category = normalizeCatalogCategory(state.catalogFilters.category);
  if (category !== "all") {
    return categoryGroupForCategory(category);
  }
  return normalizeCatalogGroup(state.catalogFilters.categoryGroup);
}

function resolvedCatalogExpandedGroup(catalog) {
  const explicit = normalizeCatalogGroup(state.catalogExpandedGroup);
  const active = currentCatalogActiveGroup();
  const candidate = explicit !== "all" ? explicit : active;
  return candidate !== "all" && categoriesForGroup(candidate, catalog).length ? candidate : "all";
}

function categoryCountInGroup(group, catalog) {
  const normalized = normalizeCatalogGroup(group);
  if (normalized === "all") {
    return catalog.length;
  }
  return catalog.filter((commodity) => categoryGroupForCategory(commodity.category) === normalized).length;
}

function categoryLabel(category) {
  const normalized = normalizeCatalogCategory(category);
  if (normalized === "all") {
    return t("all_types");
  }
  const key = `category_${normalized}`;
  const translated = t(key);
  return translated === key ? prettyCategoryName(normalized) : translated;
}

function categoryGroupLabel(group) {
  const normalized = normalizeCatalogGroup(group);
  const key = `category_group_${normalized}`;
  const translated = t(key);
  return translated === key ? prettyCategoryName(normalized) : translated;
}

function rarityLabel(rarity) {
  return rarity === "all" ? t("all_rarities") : t(`rarity_${rarityKey(rarity)}`);
}

function catalogFallbackIconType(commodityKey) {
  const key = normalizeCommodityKey(commodityKey).toLowerCase();
  if (!key || key.includes("chest") || key.includes("bundle")) {
    return "storage";
  }
  if (key.includes("sword") || key.includes("axe") || key.includes("bow") || key.includes("trident")) {
    return "weapon";
  }
  if (key.includes("helmet") || key.includes("chestplate") || key.includes("leggings") || key.includes("boots") || key.includes("shield")) {
    return "armor";
  }
  if (key.includes("ore") || key.includes("coal")) {
    return "ore";
  }
  if (key.includes("diamond") || key.includes("emerald") || key.includes("amethyst")) {
    return "gem";
  }
  if (key.includes("ingot") || key.includes("nugget")) {
    return "metal";
  }
  if (key.includes("log") || key.includes("wood") || key.includes("sapling")) {
    return "wood";
  }
  if (key.includes("bread") || key.includes("apple") || key.includes("wheat") || key.includes("sugar")) {
    return "food";
  }
  if (key.includes("cod") || key.includes("salmon") || key.includes("fish")) {
    return "fish";
  }
  if (key.includes("brick") || key.includes("stone")) {
    return "building";
  }
  if (key.includes("redstone")) {
    return "redstone";
  }
  if (key.includes("bottle") || key.includes("potion")) {
    return "alchemy";
  }
  if (key.includes("enchant") || key.includes("ender") || key.includes("blaze")) {
    return "mystic";
  }
  return "default";
}

function catalogFallbackIconPath(type) {
  const paths = {
    storage: '<path d="M6.5 9.5h11v8h-11z"/><path d="M8 9.5V7l1.3-1.2h5.4L16 7v2.5"/><path d="M7.5 12h9"/>',
    weapon: '<path d="M14.6 4.8l4.6 4.6-1.8 1.8-1.5-1.5-6.8 6.8-2.6.9.9-2.6 6.8-6.8-1.4-1.4z"/><path d="M5.3 18.7l3 3"/><path d="M11.9 9.1l3 3"/>',
    armor: '<path d="M8 5.5l4-1.5 4 1.5 2 3.5-1.7 1.1V19H7.7v-8.9L6 9z"/><path d="M9.4 6.1c.4 1.2 1.3 2 2.6 2s2.2-.8 2.6-2"/>',
    ore: '<path d="M6.3 8.4l4.1-3.1 5.1.8 2.2 4.5-1.8 5.2-5.4 2.2-4.7-3.1z"/><path d="M10.1 9.1l2.3-.9 1.5 1.8-.8 2.2-2.4.7-1.6-1.6z"/>',
    gem: '<path d="M8.4 5.5h7.2l2.9 4-6.5 9-6.5-9z"/><path d="M8.4 5.5l3.6 13 3.6-13"/><path d="M5.5 9.5h13"/>',
    metal: '<path d="M7 9.2l2.1-3.7h5.8L17 9.2 15.2 18H8.8z"/><path d="M8.2 12h7.6"/><path d="M9 15h6"/>',
    wood: '<path d="M7.5 6.5h9v11h-9z"/><path d="M10 7v10"/><path d="M13.8 7v10"/><path d="M8.7 10.5h6.6"/><path d="M8.7 14.2h6.6"/>',
    food: '<path d="M6.5 13c0-3 2.5-5.5 5.5-5.5s5.5 2.5 5.5 5.5v4.5h-11z"/><path d="M8.2 12.8h7.6"/><path d="M10 7.7c0-1.6.8-2.6 2-3.2"/>',
    fish: '<path d="M5 12s3-4 7.2-4c3 0 5.4 2.2 6.8 4-1.4 1.8-3.8 4-6.8 4C8 16 5 12 5 12z"/><path d="M5 12l-2.2-2.2v4.4z"/><circle cx="15" cy="11" r=".8"/>',
    building: '<path d="M5.5 7.5h13v10h-13z"/><path d="M5.5 11h13"/><path d="M5.5 14.5h13"/><path d="M9.5 7.5v3.5"/><path d="M14.5 11v3.5"/><path d="M9.5 14.5v3"/>',
    redstone: '<path d="M12 4.5l6.5 4v7L12 19.5l-6.5-4v-7z"/><path d="M12 8.2v7.6"/><path d="M8.7 10.1l6.6 3.8"/><path d="M15.3 10.1l-6.6 3.8"/>',
    alchemy: '<path d="M10 4.5h4"/><path d="M11 4.5v4.1l-4 6.5c-1 1.7.2 3.9 2.2 3.9h5.6c2 0 3.2-2.2 2.2-3.9l-4-6.5V4.5"/><path d="M8.4 15h7.2"/>',
    mystic: '<path d="M12 4.5l1.7 4.2 4.3.3-3.3 2.8 1.1 4.2-3.8-2.3L8.2 16l1.1-4.2L6 9l4.3-.3z"/>',
    default: '<path d="M6.5 7.5h11v10h-11z"/><path d="M8.5 9.5h7"/><path d="M8.5 12h7"/><path d="M8.5 14.5h4.5"/>'
  };
  return paths[type] || paths.default;
}

function renderCatalogFallbackIconSvg(commodityKey, label) {
  const type = catalogFallbackIconType(commodityKey);
  return `
    <svg class="catalog-fallback-svg" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <g fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
        ${catalogFallbackIconPath(type)}
      </g>
    </svg>
    <span class="sr-only">${escapeHtml(label)}</span>
  `;
}

function renderCatalogIconShell(commodityKey, label, className = "catalog-category-icon") {
  const cached = commodityIconCache.get(commodityKey);
  if (cached?.status === "loaded" && cached.src) {
    return `
      <span class="${escapeHtml(className)} has-image" data-icon-shell data-commodity-key="${escapeHtml(commodityKey)}" data-display-name="${escapeHtml(label)}">
        <img alt="${escapeHtml(label)}" loading="lazy" src="${escapeHtml(cached.src)}">
      </span>
    `;
  }
  return `
      <span class="${escapeHtml(className)} icon-pending" data-icon-shell data-commodity-key="${escapeHtml(commodityKey)}" data-display-name="${escapeHtml(label)}">
        ${renderCatalogFallbackIconSvg(commodityKey, label)}
      </span>
  `;
}

function renderCatalogCategoryIcon(category) {
  const normalized = normalizeCatalogCategory(category);
  const commodityKey = CATALOG_CATEGORY_ICON_ITEMS[normalized] || CATALOG_CATEGORY_ICON_ITEMS.other || CATALOG_CATEGORY_ICON_ITEMS.all;
  return renderCatalogIconShell(commodityKey, categoryLabel(normalized), "catalog-subcategory-icon");
}

function renderCatalogGroupIcon(group) {
  const normalized = normalizeCatalogGroup(group);
  const commodityKey = CATALOG_CATEGORY_GROUP_ICON_ITEMS[normalized] || CATALOG_CATEGORY_GROUP_ICON_ITEMS.other || CATALOG_CATEGORY_GROUP_ICON_ITEMS.all;
  return renderCatalogIconShell(commodityKey, categoryGroupLabel(normalized), "catalog-category-icon");
}

function renderCatalogGroupButton(group, catalog) {
  const normalized = normalizeCatalogGroup(group);
  const count = categoryCountInGroup(normalized, catalog);
  const active = currentCatalogActiveGroup() === normalized;
  return `
    <button type="button" class="catalog-category-card ${active ? "active" : ""}" data-catalog-group="${escapeHtml(normalized)}" ${count ? "" : "disabled"}>
      ${renderCatalogGroupIcon(normalized)}
      <span class="catalog-category-name">${escapeHtml(categoryGroupLabel(normalized))}</span>
      <span class="catalog-category-count">${number(count)}</span>
    </button>
  `;
}

function renderCatalogSubcategoryButton(category, catalog) {
  const normalized = normalizeCatalogCategory(category);
  const count = catalog.filter((commodity) => commodity.category === normalized).length;
  const active = normalizeCatalogCategory(state.catalogFilters.category) === normalized;
  const group = categoryGroupForCategory(normalized);
  return `
    <button type="button" class="catalog-subcategory-card ${active ? "active" : ""}" data-catalog-subcategory="${escapeHtml(normalized)}" data-catalog-subcategory-group="${escapeHtml(group)}" ${count ? "" : "disabled"}>
      ${renderCatalogCategoryIcon(normalized)}
      <span class="catalog-subcategory-name">${escapeHtml(categoryLabel(normalized))}</span>
      <span class="catalog-subcategory-count">${number(count)}</span>
    </button>
  `;
}

function renderCatalogRarityButton(rarity, catalog) {
  const normalized = rarity === "all" ? "all" : normalizeCatalogRarity(rarity);
  const count = normalized === "all"
    ? catalog.length
    : catalog.filter((commodity) => commodity.rarity === normalized).length;
  const active = normalizeCatalogRarity(state.catalogFilters.rarity) === normalized;
  return `
    <button type="button" class="rarity-filter ${rarityClassName(normalized)} ${active ? "active" : ""}" data-catalog-rarity="${escapeHtml(normalized)}">
      <span>${escapeHtml(rarityLabel(normalized))}</span>
      <span class="rarity-count">${number(count)}</span>
    </button>
  `;
}

function renderBrowseOrderModeSwitch(purchaseCount, demandCount) {
  const current = normalizeBrowseOrderView(state.browseOrderView);
  return `
    <div class="browse-order-mode" role="tablist" aria-label="${escapeHtml(t("browse_order_mode"))}">
      <button type="button" class="${current === "purchase" ? "active" : ""}" data-browse-order-view="purchase" role="tab" aria-selected="${current === "purchase" ? "true" : "false"}">
        <span>${escapeHtml(t("browse_purchase_orders"))}</span>
        <strong>${number(purchaseCount)}</strong>
      </button>
      <button type="button" class="${current === "demand" ? "active" : ""}" data-browse-order-view="demand" role="tab" aria-selected="${current === "demand" ? "true" : "false"}">
        <span>${escapeHtml(t("browse_demand_orders"))}</span>
        <strong>${number(demandCount)}</strong>
      </button>
    </div>
  `;
}

// 商品目录:左侧筛选栏(分类 → 展开的子分类、稀有度、价格),右侧结果区(标题、买/求购切换、搜索、排序、商品格)。
function renderCatalogShelf({ catalog, filteredCatalog, kicker, title, emptyMessage, cardRenderer, headExtra = "", heatmapRoute = "" }) {
  const heatmap = !!heatmapRoute && state.browseLayout === "heatmap";
  const groups = catalogGroups(catalog);
  const expandedGroup = resolvedCatalogExpandedGroup(catalog);
  const subcategories = expandedGroup === "all" ? [] : categoriesForGroup(expandedGroup, catalog);
  const subcategoryList = subcategories.length ? `
    <div class="catalog-subcategory-strip" data-catalog-subcategory-strip>
      <div class="catalog-subcategory-grid">
        ${subcategories.map((category) => renderCatalogSubcategoryButton(category, catalog)).join("")}
      </div>
    </div>
  ` : "";
  const groupButtons = ["all", ...groups].map((group) => {
    const button = renderCatalogGroupButton(group, catalog);
    return normalizeCatalogGroup(group) === expandedGroup && group !== "all" ? `${button}${subcategoryList}` : button;
  }).join("");
  const watchedCount = catalog.filter((commodity) => isWatched(commodity.commodityKey)).length;
  const filterCount = activeFilterCount();
  return `
    <div class="market-browse">
      <aside id="catalog-rail" class="catalog-rail ${state.mobileFiltersOpen ? "is-open" : ""}" aria-label="${escapeHtml(t("filters_label"))}">
        <section class="rail-section">
          <button type="button" class="watch-filter ${state.catalogFilters.watchOnly ? "active" : ""}" data-catalog-watch aria-pressed="${state.catalogFilters.watchOnly ? "true" : "false"}">
            <span aria-hidden="true">★</span> ${escapeHtml(t("watch_filter"))} <strong>${number(watchedCount)}</strong>
          </button>
        </section>
        <section class="rail-section catalog-navigation">
          <h4 class="rail-title">${escapeHtml(t("category_filter"))}</h4>
          <div id="catalog-group-strip" class="catalog-category-strip tap-enabled">
            ${groupButtons}
          </div>
        </section>
        <section class="rail-section">
          <h4 class="rail-title">${escapeHtml(t("rarity_filter"))}</h4>
          <div class="catalog-rarity-strip">
            ${renderCatalogRarityButton("all", catalog)}
            ${catalogRarities(catalog).map((rarity) => renderCatalogRarityButton(rarity, catalog)).join("")}
          </div>
        </section>
        <section class="rail-section">
          <h4 class="rail-title">${escapeHtml(t("price_range"))}</h4>
          <div class="catalog-price-range">
            <input id="catalog-price-min" class="catalog-range-input" type="number" min="0" step="1" value="${escapeHtml(state.catalogFilters.minPrice || "")}" placeholder="${escapeHtml(t("min_price"))}" aria-label="${escapeHtml(t("min_price"))}">
            <span class="range-divider">-</span>
            <input id="catalog-price-max" class="catalog-range-input" type="number" min="0" step="1" value="${escapeHtml(state.catalogFilters.maxPrice || "")}" placeholder="${escapeHtml(t("max_price"))}" aria-label="${escapeHtml(t("max_price"))}">
          </div>
        </section>
      </aside>
      <div class="catalog-main">
        <div class="shelf-head">
          <div class="shelf-title">
            <p class="section-kicker">${escapeHtml(kicker)}</p>
            <h3>${escapeHtml(title)}</h3>
            <span class="result-count">${escapeHtml(t("results_count").replace("%1", number(filteredCatalog.length)))}</span>
          </div>
          <button type="button" class="filters-toggle secondary" data-toggle-filters aria-controls="catalog-rail" aria-expanded="${state.mobileFiltersOpen ? "true" : "false"}">${escapeHtml(t("filters_toggle"))}${filterCount ? ` · ${number(filterCount)}` : ""}</button>
          ${headExtra}
        </div>
        <div class="catalog-toolbar">
          <div class="shelf-tools">
            <input id="commodity-search" class="goods-search" type="search" value="${escapeHtml(state.commodityQuery)}" placeholder="${escapeHtml(t("search_placeholder"))}" aria-label="${escapeHtml(t("search_placeholder"))}">
            <button type="button" class="palette-launch" data-open-palette aria-keyshortcuts="Control+K" title="${escapeHtml(t("palette_title"))} (Ctrl+K)">
              <span>${escapeHtml(t("palette_launch"))}</span><kbd>Ctrl K</kbd>
            </button>
          </div>
          <div class="sort-controls" role="group" aria-label="${escapeHtml(t("sort_label"))}">
            ${renderCatalogSortButton("name", t("sort_name"))}
            ${renderCatalogSortButton("price", t("sort_price"))}
            ${renderCatalogSortButton("time", t("sort_time"))}
            ${renderCatalogSortButton("quantity", t("sort_quantity"))}
          </div>
          ${heatmapRoute ? renderBrowseLayoutSwitch() : ""}
        </div>
        ${filteredCatalog.length ? (heatmap ? renderCatalogHeatmap(filteredCatalog, heatmapRoute) : `
          <div class="goods-grid">
            ${filteredCatalog.map((commodity) => cardRenderer(commodity)).join("")}
          </div>
        `) : `<div class="empty-state">${escapeHtml(emptyMessage)}</div>`}
      </div>
    </div>
  `;
}

function buildCommodityCatalog(detail) {
  const map = new Map();

  function ensureCommodity(key, label) {
    const commodityKey = normalizeCommodityKey(key || label);
    if (!commodityKey) {
      return null;
    }
    if (!map.has(commodityKey)) {
      map.set(commodityKey, {
        commodityKey,
        displayName: label || commodityKey,
        listings: [],
        storageEntries: [],
        myBuyOrders: [],
        buyBookEntries: [],
        priceChart: null,
        candles: {},
        impact: null
      });
    }
    const entry = map.get(commodityKey);
    if (label && (!entry.displayName || entry.displayName === entry.commodityKey)) {
      entry.displayName = label;
    }
    return entry;
  }

  (detail.listings || []).forEach((entry) => {
    const commodity = ensureCommodity(entry.commodityKey, entry.itemName);
    if (commodity) {
      commodity.listings.push(entry);
    }
  });

  (detail.storageEntries || []).forEach((entry) => {
    const commodity = ensureCommodity(entry.commodityKey, entry.itemName);
    if (commodity) {
      commodity.storageEntries.push(entry);
    }
  });

  (detail.buyOrderEntries || []).forEach((entry) => {
    const commodity = ensureCommodity(entry.commodityKey, entry.displayName || entry.itemName || entry.commodityKey);
    if (commodity) {
      commodity.myBuyOrders.push(entry);
    }
  });

  (detail.commodityBuyBooks || []).forEach((book) => {
    const commodity = ensureCommodity(book.commodityKey, book.displayName || book.commodityKey);
    if (commodity) {
      commodity.buyBookEntries = book.entries || [];
    }
  });

  (detail.priceCharts || []).forEach((series) => {
    const commodity = ensureCommodity(series.commodityKey, series.displayName || series.commodityKey);
    if (commodity) {
      commodity.priceChart = series;
    }
  });

  (detail.candleSeries || []).forEach((series) => {
    const commodity = ensureCommodity(series.commodityKey, series.displayName || series.commodityKey);
    if (commodity) {
      commodity.candles[series.timeframe || "1h"] = series;
    }
  });

  (detail.impactSnapshots || []).forEach((snapshot) => {
    const commodity = ensureCommodity(snapshot.commodityKey, snapshot.commodityKey);
    if (commodity) {
      commodity.impact = snapshot;
    }
  });

  return Array.from(map.values()).map((commodity) => {
    const bestSell = minValue(commodity.listings, "unitPrice");
    const bestBuy = maxValue(commodity.buyBookEntries, "maxPriceBp");
    const storageUnits = sumBy(commodity.storageEntries, "quantity");
    // 无限库存项的 availableCount 是哨兵大数,排除出在售总量统计,避免污染排序/热度/显示(∞ 已在各 listing 行单独显示)。
    const sellUnits = (commodity.listings || []).reduce(
      (sum, l) => sum + (isInfiniteStock(l) ? 0 : Number(l.availableCount || 0)), 0);
    const demandUnits = sumBy(commodity.buyBookEntries, "quantity");
    const result = {
      ...commodity,
      displayName: commodity.displayName || commodity.commodityKey,
      bestSell,
      bestBuy,
      storageUnits,
      sellUnits,
      demandUnits,
      totalListings: commodity.listings.length,
      referencePrice: Number(commodity.impact?.referenceUnitPrice) || null,
      liquidityScore: Number(commodity.impact?.liquidityScore) || 0
    };
    result.category = deriveCommodityCategory(result);
    result.rarity = deriveCommodityRarity(result);
    result.lastActivityAt = commoditySortTimestamp(result);
    result.visibleQuantity = commoditySortQuantity(result);
    return result;
  }).sort((left, right) => {
    const leftScore = (left.totalListings * 1000) + left.sellUnits + left.demandUnits;
    const rightScore = (right.totalListings * 1000) + right.sellUnits + right.demandUnits;
    return rightScore - leftScore || left.displayName.localeCompare(right.displayName);
  });
}

function buildInventoryCatalog(detail) {
  const map = new Map();

  function ensureCommodity(key, label) {
    const commodityKey = normalizeCommodityKey(key || label);
    if (!commodityKey) {
      return null;
    }
    if (!map.has(commodityKey)) {
      map.set(commodityKey, {
        commodityKey,
        displayName: label || commodityKey,
        storageEntries: []
      });
    }
    const entry = map.get(commodityKey);
    if (label && (!entry.displayName || entry.displayName === entry.commodityKey)) {
      entry.displayName = label;
    }
    return entry;
  }

  (detail.storageEntries || []).forEach((entry) => {
    const commodity = ensureCommodity(entry.commodityKey, entry.itemName);
    if (commodity) {
      commodity.storageEntries.push(entry);
    }
  });

  return Array.from(map.values()).map((commodity) => {
    const storageUnits = sumBy(commodity.storageEntries, "quantity");
    const suggestedUnitPrice = firstFiniteNumber(commodity.storageEntries, "suggestedUnitPrice");
    const result = {
      ...commodity,
      displayName: commodity.displayName || commodity.commodityKey,
      listings: [],
      myBuyOrders: [],
      buyBookEntries: [],
      candles: {},
      impact: null,
      bestSell: null,
      bestBuy: null,
      suggestedUnitPrice,
      storageUnits,
      sellUnits: 0,
      demandUnits: 0,
      totalListings: 0,
      referencePrice: suggestedUnitPrice,
      liquidityScore: 0
    };
    result.category = deriveCommodityCategory(result);
    result.rarity = deriveCommodityRarity(result);
    result.lastActivityAt = commoditySortTimestamp(result);
    result.visibleQuantity = storageUnits;
    return result;
  }).sort((left, right) => {
    const quantityDiff = Number(right.storageUnits || 0) - Number(left.storageUnits || 0);
    return quantityDiff || left.displayName.localeCompare(right.displayName, state.locale);
  });
}

function filterCatalog(catalog) {
  const query = state.commodityQuery.trim().toLowerCase();
  const categoryGroup = normalizeCatalogGroup(state.catalogFilters.categoryGroup);
  const category = normalizeCatalogCategory(state.catalogFilters.category);
  const rarity = normalizeCatalogRarity(state.catalogFilters.rarity);
  const minPrice = Number(state.catalogFilters.minPrice || 0);
  const maxPrice = Number(state.catalogFilters.maxPrice || 0);
  const filtered = catalog.filter((commodity) => {
    if (query && !commoditySearchText(commodity).includes(query)) {
      return false;
    }
    if (state.catalogFilters.watchOnly && !isWatched(commodity.commodityKey)) {
      return false;
    }
    if (category !== "all") {
      if (commodity.category !== category) {
        return false;
      }
    } else if (categoryGroup !== "all" && categoryGroupForCategory(commodity.category) !== categoryGroup) {
      return false;
    }
    if (rarity !== "all" && commodity.rarity !== rarity) {
      return false;
    }
    const price = commodityFilterPrice(commodity);
    if (minPrice > 0 && (!Number.isFinite(price) || price < minPrice)) {
      return false;
    }
    if (maxPrice > 0 && (!Number.isFinite(price) || price > maxPrice)) {
      return false;
    }
    return true;
  });
  return sortCatalog(filtered);
}

// 搜索范围:商品名、物品 ID、分类名,以及挂单的卖家、来源城镇、港口、卖家留言和求购方。
function commoditySearchText(commodity) {
  if (typeof commodity.searchText !== "string") {
    const parts = [commodity.displayName, pinyinInitials(commodity.displayName), commodity.commodityKey, categoryLabel(commodity.category)];
    (commodity.listings || []).forEach((listing) => {
      parts.push(listing.sellerName, listing.sourceTownName, listing.sourceDockName, listing.sellerNote);
    });
    (commodity.buyBookEntries || []).forEach((entry) => parts.push(entry.buyerName));
    commodity.searchText = parts.filter(Boolean).join(" ").toLowerCase();
  }
  return commodity.searchText;
}

// ---- 拼音首字母 ----
// 不带字典:借助浏览器 ICU 的中文拼音排序,用每个声母排序最靠前的字做边界查找。"钻石" → "zs"。
const PINYIN_BOUNDARIES = "阿八嚓哒妸发旮哈丌咔垃妈拏噢妑七呥仨他屲夕丫帀";
const PINYIN_LETTERS = "abcdefghjklmnopqrstwxyz";
const pinyinCache = new Map();
let pinyinCollator = null;

function pinyinInitials(text) {
  const source = String(text || "");
  if (!/[一-鿿]/.test(source)) {
    return "";
  }
  if (pinyinCache.has(source)) {
    return pinyinCache.get(source);
  }
  try {
    pinyinCollator = pinyinCollator || new Intl.Collator("zh-Hans-CN");
  } catch (_) {
    return "";
  }
  let initials = "";
  for (const char of source) {
    if (!/[一-鿿]/.test(char)) {
      if (/[a-z0-9]/i.test(char)) {
        initials += char.toLowerCase();
      }
      continue;
    }
    for (let index = PINYIN_BOUNDARIES.length - 1; index >= 0; index--) {
      if (pinyinCollator.compare(char, PINYIN_BOUNDARIES[index]) >= 0) {
        initials += PINYIN_LETTERS[index];
        break;
      }
    }
  }
  if (pinyinCache.size > 5000) {
    pinyinCache.clear();
  }
  pinyinCache.set(source, initials);
  return initials;
}

// ---- 自选 ----
function isWatched(commodityKey) {
  return !!commodityKey && state.watchlist.has(commodityKey);
}

function toggleWatch(commodityKey) {
  if (!commodityKey) {
    return;
  }
  if (state.watchlist.has(commodityKey)) {
    state.watchlist.delete(commodityKey);
  } else {
    state.watchlist.add(commodityKey);
  }
  try {
    localStorage.setItem("marketWebWatchlist", JSON.stringify(Array.from(state.watchlist)));
  } catch (_) {
    // 存储不可用时仅本次会话生效。
  }
  renderDetailPreservingScroll();
}

function activeFilterCount() {
  const filters = state.catalogFilters;
  return [
    normalizeCatalogGroup(filters.categoryGroup) !== "all" || normalizeCatalogCategory(filters.category) !== "all",
    normalizeCatalogRarity(filters.rarity) !== "all",
    !!filters.minPrice,
    !!filters.maxPrice,
    !!filters.watchOnly
  ].filter(Boolean).length;
}

function purchaseBrowseCatalog(catalog) {
  return (catalog || []).filter((commodity) => Number(commodity.sellUnits || 0) > 0 || Number(commodity.totalListings || 0) > 0);
}

function demandBrowseCatalog(catalog) {
  return (catalog || []).filter((commodity) =>
    Number(commodity.demandUnits || 0) > 0
    || (commodity.buyBookEntries || []).length > 0
    || (commodity.myBuyOrders || []).length > 0
  );
}

function activeBrowseCatalog(catalog) {
  return normalizeBrowseOrderView(state.browseOrderView) === "demand"
    ? demandBrowseCatalog(catalog)
    : purchaseBrowseCatalog(catalog);
}

function commodityChangeRatio(commodity) {
  const points = normalizedChartPoints(primaryChartSeriesForTimeframe(commodity, "1d"));
  if (points.length < 2) {
    return null;
  }
  const first = Number(points[0].averageUnitPrice || points[0].closeUnitPrice || 0);
  const last = Number(points[points.length - 1].closeUnitPrice || points[points.length - 1].averageUnitPrice || 0);
  if (!first) {
    return null;
  }
  return (last - first) / first;
}

function commodityActivityScore(commodity) {
  const chart = primaryChartSeries(commodity);
  const lastPoint = latestChartPoint(chart);
  return (commodity.totalListings * 1000)
    + commodity.sellUnits
    + commodity.demandUnits
    + (Number(lastPoint?.tradeCount) || 0) * 200
    + (Number(lastPoint?.volume) || 0);
}

function sortCatalog(catalog) {
  const mode = normalizeCatalogSortMode(state.catalogSort?.mode);
  const direction = state.catalogSort?.direction || "desc";
  const factor = direction === "asc" ? 1 : -1;
  return [...catalog].sort((left, right) => {
    if (mode === "name") {
      const diff = left.displayName.localeCompare(right.displayName, state.locale);
      if (diff !== 0) {
        return diff * factor;
      }
    } else if (mode === "time") {
      const leftTime = Number(left.lastActivityAt || 0);
      const rightTime = Number(right.lastActivityAt || 0);
      if (!leftTime && rightTime) {
        return 1;
      }
      if (leftTime && !rightTime) {
        return -1;
      }
      const diff = leftTime - rightTime;
      if (diff !== 0) {
        return diff * factor;
      }
    } else if (mode === "quantity") {
      const diff = Number(left.visibleQuantity || 0) - Number(right.visibleQuantity || 0);
      if (diff !== 0) {
        return diff * factor;
      }
    } else if (mode === "price") {
      const leftPrice = commodityFilterPrice(left);
      const rightPrice = commodityFilterPrice(right);
      if (!Number.isFinite(leftPrice) && Number.isFinite(rightPrice)) {
        return 1;
      }
      if (Number.isFinite(leftPrice) && !Number.isFinite(rightPrice)) {
        return -1;
      }
      const diff = leftPrice - rightPrice;
      if (diff !== 0) {
        return diff * factor;
      }
    }
    return left.displayName.localeCompare(right.displayName, state.locale);
  });
}

function cycleCatalogSort(mode) {
  const nextMode = normalizeCatalogSortMode(mode);
  const currentMode = normalizeCatalogSortMode(state.catalogSort?.mode);
  const currentDirection = state.catalogSort?.direction || defaultCatalogSortDirection(currentMode);
  if (currentMode !== nextMode) {
    state.catalogSort = { mode: nextMode, direction: defaultCatalogSortDirection(nextMode) };
  } else if (currentDirection === "desc") {
    state.catalogSort = { mode: nextMode, direction: "asc" };
  } else {
    state.catalogSort = { mode: nextMode, direction: "desc" };
  }
  if (nextMode === "name" && currentMode !== "name") {
    state.catalogSort.direction = "asc";
  }
  persistCatalogSort();
}

function renderCatalogSortButton(mode, label) {
  const active = normalizeCatalogSortMode(state.catalogSort?.mode) === mode;
  const direction = active ? state.catalogSort?.direction : "";
  const indicator = direction === "desc" ? "↓" : direction === "asc" ? "↑" : "";
  return `
    <button type="button" class="sort-button ${active ? "active-sort" : ""}" data-catalog-sort="${escapeHtml(mode)}">
      <span>${escapeHtml(label)}</span>
      <span class="sort-indicator">${indicator}</span>
    </button>
  `;
}

function syncCommoditySelection() {
  const catalog = buildCommodityCatalog(state.detail || {});
  if (!catalog.length) {
    state.selectedCommodityKey = "";
    return;
  }
  const allowEmptySelection = state.activeProductTab === "inventory";
  if (allowEmptySelection && !state.selectedCommodityKey) {
    return;
  }
  if (!state.selectedCommodityKey || !catalog.some((entry) => entry.commodityKey === state.selectedCommodityKey)) {
    if (allowEmptySelection) {
      state.selectedCommodityKey = "";
      return;
    }
    state.selectedCommodityKey = catalog[0].commodityKey;
  }
}

function getSelectedCommodity(filteredCatalog, fullCatalog) {
  if (!fullCatalog.length) {
    state.selectedCommodityKey = "";
    return null;
  }
  let selected = fullCatalog.find((entry) => entry.commodityKey === state.selectedCommodityKey);
  if (!selected) {
    selected = filteredCatalog[0] || fullCatalog[0];
    state.selectedCommodityKey = selected?.commodityKey || "";
  }
  return selected || null;
}

function findCommodityByKey(catalog, commodityKey) {
  const normalized = normalizeCommodityKey(commodityKey);
  if (!normalized) {
    return null;
  }
  return (catalog || []).find((entry) => normalizeCommodityKey(entry.commodityKey) === normalized) || null;
}

function splitCommodityKey(key) {
  const raw = normalizeCommodityKey(key);
  if (!raw.includes(":")) {
    return { namespace: "minecraft", path: raw };
  }
  const [namespace, ...rest] = raw.split(":");
  return { namespace: namespace || "minecraft", path: rest.join(":") || "" };
}

function unique(values) {
  return Array.from(new Set(values.filter(Boolean)));
}

function commodityIconSources(commodityKey) {
  const { namespace, path } = splitCommodityKey(commodityKey);
  if (!path) {
    return [];
  }
  const versionParam = state.webResourceVersion ? `&v=${encodeURIComponent(state.webResourceVersion)}` : "";
  const assetVersionParam = state.webResourceVersion ? `?v=${encodeURIComponent(state.webResourceVersion)}` : "";
  const endpoint = `/api/icons?commodityKey=${encodeURIComponent(commodityKey)}${versionParam}`;
  if (namespace === "sailboatmod") {
    return unique([
      `/assets/sailboatmod/textures/item/${path}.png${assetVersionParam}`,
      `/assets/sailboatmod/textures/block/${path}.png${assetVersionParam}`,
      endpoint
    ]);
  }
  return unique([endpoint]);
}

function renderCommodityIcon(commodityKey, displayName) {
  const cached = commodityIconCache.get(commodityKey);
  const sources = commodityIconSources(commodityKey);
  const fallback = escapeHtml(iconLetter(displayName));
  if (cached?.status === "loaded" && cached.src) {
    return `
      <div class="goods-art has-image" data-icon-shell data-commodity-key="${escapeHtml(commodityKey)}" data-display-name="${escapeHtml(displayName)}">
        <img alt="${escapeHtml(displayName)}" loading="lazy" src="${escapeHtml(cached.src)}">
      </div>
    `;
  }
  if (cached?.status === "failed" || !sources.length) {
    return `<div class="goods-art"><span class="goods-art-fallback">${fallback}</span></div>`;
  }
  return `
    <div class="goods-art icon-pending" data-icon-shell data-commodity-key="${escapeHtml(commodityKey)}" data-display-name="${escapeHtml(displayName)}">
      <span class="goods-art-fallback">${fallback}</span>
    </div>
  `;
}

function chunkValues(values, size) {
  const chunks = [];
  for (let index = 0; index < values.length; index += size) {
    chunks.push(values.slice(index, index + size));
  }
  return chunks;
}

async function requestCommodityIconBatch(keys) {
  const pending = unique(keys.map((key) => String(key || "").trim())).filter((commodityKey) => {
    if (!commodityKey || commodityIconRequests.has(commodityKey)) {
      return false;
    }
    const cached = commodityIconCache.get(commodityKey);
    return cached?.status !== "loaded" && cached?.status !== "failed" && commodityIconSources(commodityKey).length > 0;
  });
  if (!pending.length) {
    return;
  }

  const waiters = [];
  const freshKeys = [];
  pending.forEach((commodityKey) => {
    const existing = commodityIconBatchRequests.get(commodityKey);
    if (existing) {
      waiters.push(existing);
      return;
    }
    freshKeys.push(commodityKey);
  });

  const chunkRequests = chunkValues(freshKeys, COMMODITY_ICON_BATCH_SIZE).map((chunk) => {
    const params = new URLSearchParams();
    chunk.forEach((commodityKey) => params.append("commodityKey", commodityKey));
    if (state.webResourceVersion) {
      params.set("v", state.webResourceVersion);
    }
    const request = api(`/api/icons/batch?${params.toString()}`).then((data) => {
      const icons = data && typeof data.icons === "object" && data.icons ? data.icons : {};
      const missing = new Set(Array.isArray(data?.missing) ? data.missing : []);
      chunk.forEach((commodityKey) => {
        const src = typeof icons[commodityKey] === "string" ? icons[commodityKey] : "";
        if (src) {
          commodityIconCache.set(commodityKey, { status: "loaded", src });
          return;
        }
        if (missing.has(commodityKey)) {
          commodityIconCache.set(commodityKey, { status: "batch-missing", src: "" });
        }
      });
    }).catch(() => {
    }).finally(() => {
      chunk.forEach((commodityKey) => commodityIconBatchRequests.delete(commodityKey));
    });
    chunk.forEach((commodityKey) => commodityIconBatchRequests.set(commodityKey, request));
    return request;
  });

  await Promise.allSettled(waiters.concat(chunkRequests));
}

function ensureCommodityIcon(commodityKey) {
  const cached = commodityIconCache.get(commodityKey);
  if (cached?.status === "loaded") {
    return Promise.resolve(cached.src);
  }
  if (cached?.status === "failed") {
    return Promise.resolve(null);
  }
  const existing = commodityIconRequests.get(commodityKey);
  if (existing) {
    return existing;
  }
  const sources = commodityIconSources(commodityKey);
  if (!sources.length) {
    commodityIconCache.set(commodityKey, { status: "failed", src: "" });
    return Promise.resolve(null);
  }

  const request = new Promise((resolve) => {
    const probe = new Image();
    let index = 0;

    const finish = (src) => {
      commodityIconRequests.delete(commodityKey);
      if (src) {
        commodityIconCache.set(commodityKey, { status: "loaded", src });
        resolve(src);
        return;
      }
      commodityIconCache.set(commodityKey, { status: "failed", src: "" });
      resolve(null);
    };

    const trySource = () => {
      if (index >= sources.length) {
        finish(null);
        return;
      }
      probe.src = sources[index];
    };

    probe.onload = () => {
      if (probe.naturalWidth > 0) {
        finish(sources[index]);
        return;
      }
      index += 1;
      trySource();
    };
    probe.onerror = () => {
      index += 1;
      trySource();
    };

    trySource();
  });

  commodityIconRequests.set(commodityKey, request);
  return request;
}

function applyCommodityIcon(shell, src) {
  if (!shell || !src) {
    return;
  }
  let img = shell.querySelector("img");
  if (!img) {
    img = document.createElement("img");
    img.loading = "lazy";
    const fallback = shell.querySelector(".goods-art-fallback");
    shell.insertBefore(img, fallback || null);
  }
  img.alt = shell.dataset.displayName || shell.dataset.commodityKey || "";
  if (img.getAttribute("src") !== src) {
    img.src = src;
  }
  const fallback = shell.querySelector(".goods-art-fallback");
  if (fallback) {
    fallback.remove();
  }
  shell.querySelectorAll(".catalog-fallback-svg").forEach((node) => node.remove());
  shell.classList.add("has-image");
}

async function hydrateCommodityIcons() {
  const pendingShells = [];
  const pendingKeys = [];
  document.querySelectorAll("[data-icon-shell]").forEach((shell) => {
    if (shell.dataset.bound === "true") {
      return;
    }
    shell.dataset.bound = "true";
    const commodityKey = shell.dataset.commodityKey || "";
    if (!commodityKey) {
      return;
    }
    pendingShells.push(shell);
    pendingKeys.push(commodityKey);
  });
  if (!pendingShells.length) {
    return;
  }

  await requestCommodityIconBatch(pendingKeys);

  pendingShells.forEach((shell) => {
    const commodityKey = shell.dataset.commodityKey || "";
    ensureCommodityIcon(commodityKey).then((src) => {
      if (!document.body.contains(shell)) {
        return;
      }
      if (!src) {
        shell.classList.remove("icon-pending");
        return;
      }
      applyCommodityIcon(shell, src);
    });
  });
}

function renderCommodityBrowseCard(commodity, route, footerValue, footerLabel, extraBadge) {
  return `
    <button type="button" class="goods-card ${commodity.commodityKey === state.selectedCommodityKey && state.activeProductTab === route ? "active" : ""}" data-card-commodity-key="${escapeHtml(commodity.commodityKey)}" data-card-route="${escapeHtml(route)}">
      ${renderCommodityIcon(commodity.commodityKey, commodity.displayName)}
      <div class="goods-card-topline">
        <span class="goods-category-tag">${escapeHtml(categoryLabel(commodity.category))}</span>
        ${isWatched(commodity.commodityKey) ? `<span class="watch-star" title="${escapeHtml(t("watch_on"))}">★<span class="sr-only">${escapeHtml(t("watch_on"))}</span></span>` : ""}
        <span class="goods-rarity-tag ${rarityClassName(commodity.rarity)}">${escapeHtml(rarityLabel(commodity.rarity))}</span>
      </div>
      <h3>${escapeHtml(commodity.displayName)}</h3>
      <div class="goods-key">${escapeHtml(commodity.commodityKey)}</div>
      <div class="goods-summary">
        <span class="goods-badge">${number(commodity.totalListings)} ${escapeHtml(t("selling"))}</span>
        <span class="goods-badge">${number(commodity.demandUnits)} ${escapeHtml(t("buying"))}</span>
        <span class="goods-badge">${escapeHtml(extraBadge)}</span>
      </div>
      ${renderTrendRow(commodityTrend(commodity))}
      <div class="goods-card-footer">
        <strong data-live-price="${escapeHtml(livePriceKey("card", commodity.commodityKey, route))}" data-price-value="${footerValue == null ? "" : escapeHtml(String(footerValue))}">${footerValue == null ? "--" : number(footerValue)}</strong>
        <span>${escapeHtml(footerLabel)}</span>
      </div>
    </button>
  `;
}

function renderPurchaseCommodityCard(commodity) {
  return renderCommodityBrowseCard(
    commodity,
    "sell",
    commodity.bestSell,
    t("lowest_sell"),
    `${number(commodity.sellUnits)} ${t("units_live")}`
  );
}

function renderDemandCommodityCard(commodity) {
  return renderCommodityBrowseCard(
    commodity,
    "demand",
    bestBidPrice(commodity),
    t("highest_buy"),
    `${number(commodity.demandUnits)} ${t("units_wanted")}`
  );
}

// 求购单的价格以参考价的基点(bp)表示;换算成绿宝石价格再展示,玩家不需要自己算。
function bestBidPrice(commodity) {
  if (commodity?.bestBuy == null) {
    return null;
  }
  return Math.max(1, Math.round(buyOrderReferencePrice(commodity) * (1 + Number(commodity.bestBuy) / 10000)));
}

// 24 小时走势:取 1h K 线最近 25 个收盘价(24 段);没有 1h 数据时退到 1d 的最近 8 个点(约 7 天)。
function commodityTrend(commodity) {
  if (commodity.trend !== undefined) {
    return commodity.trend;
  }
  let points = normalizedChartPoints(commodity?.candles?.["1h"]).slice(-25);
  let windowKey = "trend_24h";
  if (points.length < 2) {
    points = normalizedChartPoints(commodity?.candles?.["1d"] || commodity?.priceChart).slice(-8);
    windowKey = "trend_7d";
  }
  const closes = points.map((point) => Number(point.closeUnitPrice || point.averageUnitPrice || 0)).filter((value) => value > 0);
  if (closes.length < 2) {
    commodity.trend = null;
    return null;
  }
  const first = closes[0];
  const last = closes[closes.length - 1];
  commodity.trend = {
    closes,
    change: (last - first) / first,
    volume: points.reduce((sum, point) => sum + (Number(point.volume) || 0), 0),
    last,
    windowKey
  };
  return commodity.trend;
}

function trendDirection(change) {
  if (change > 0.0005) {
    return "up";
  }
  return change < -0.0005 ? "down" : "flat";
}

function formatChange(ratio) {
  const percent = Number(ratio) * 100;
  const sign = percent > 0.05 ? "+" : (percent < -0.05 ? "−" : "");
  return `${sign}${Math.abs(percent).toFixed(Math.abs(percent) >= 10 ? 0 : 1)}%`;
}

function sparklinePoints(values, width, height) {
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const step = width / (values.length - 1);
  return values.map((value, index) => `${(index * step).toFixed(1)},${(height - 2 - ((value - min) / span) * (height - 4)).toFixed(1)}`).join(" ");
}

function renderTrendRow(trend) {
  if (!trend) {
    return "";
  }
  const direction = trendDirection(trend.change);
  return `
    <div class="goods-trend is-${direction}" title="${escapeHtml(t(trend.windowKey))}">
      <svg class="sparkline" viewBox="0 0 96 24" preserveAspectRatio="none" aria-hidden="true" focusable="false"><polyline points="${sparklinePoints(trend.closes, 96, 24)}"></polyline></svg>
      <span class="trend-change"><span class="sr-only">${escapeHtml(t(trend.windowKey))} </span>${formatChange(trend.change)}</span>
    </div>
  `;
}

// ---- 热力图(矩形树图) ----
// 面积 = 近 24 小时成交额(成交量 × 最新价,开 0.7 次方压缩差距),颜色 = 24 小时涨跌。
// 用 squarified 算法在虚拟画布上排布,输出百分比坐标,窗口缩放时不必重算。
function squarifyTreemap(items, width, height) {
  const total = items.reduce((sum, item) => sum + item.value, 0);
  if (!total) {
    return [];
  }
  const scale = (width * height) / total;
  let rest = items.map((item) => ({ ...item, area: item.value * scale }));
  let x = 0;
  let y = 0;
  let w = width;
  let h = height;
  const placed = [];
  const worst = (row, side) => {
    const sum = row.reduce((acc, item) => acc + item.area, 0);
    const max = Math.max(...row.map((item) => item.area));
    const min = Math.min(...row.map((item) => item.area));
    return Math.max((side * side * max) / (sum * sum), (sum * sum) / (side * side * min));
  };
  while (rest.length) {
    const side = Math.min(w, h);
    let row = [rest[0]];
    let best = worst(row, side);
    for (let index = 1; index < rest.length; index++) {
      const candidate = row.concat(rest[index]);
      const ratio = worst(candidate, side);
      if (ratio > best) {
        break;
      }
      row = candidate;
      best = ratio;
    }
    const rowArea = row.reduce((acc, item) => acc + item.area, 0);
    if (w >= h) {
      const columnWidth = rowArea / h;
      let cursor = y;
      row.forEach((item) => {
        const itemHeight = item.area / columnWidth;
        placed.push({ ...item, x, y: cursor, w: columnWidth, h: itemHeight });
        cursor += itemHeight;
      });
      x += columnWidth;
      w -= columnWidth;
    } else {
      const rowHeight = rowArea / w;
      let cursor = x;
      row.forEach((item) => {
        const itemWidth = item.area / rowHeight;
        placed.push({ ...item, x: cursor, y, w: itemWidth, h: rowHeight });
        cursor += itemWidth;
      });
      y += rowHeight;
      h -= rowHeight;
    }
    rest = rest.slice(row.length);
  }
  return placed;
}

function renderCatalogHeatmap(catalog, route) {
  const narrow = window.matchMedia?.("(max-width: 720px)")?.matches;
  const canvas = narrow ? { w: 600, h: 900 } : { w: 1000, h: 560 };
  const items = catalog.map((commodity) => {
    const trend = commodityTrend(commodity);
    const price = Number(trend?.last || commodityFilterPrice(commodity) || 0);
    const turnover = (Number(trend?.volume) || 0) * (Number.isFinite(price) ? price : 0);
    const fallback = Number(commodity.sellUnits || 0) + Number(commodity.demandUnits || 0);
    return { commodity, trend, value: Math.pow((turnover || fallback) + 1, 0.7) };
  }).sort((left, right) => right.value - left.value);
  const tiles = squarifyTreemap(items, canvas.w, canvas.h);
  const totalArea = canvas.w * canvas.h;
  return `
    <div class="heatmap" role="list" aria-label="${escapeHtml(t("heatmap_label"))}">
      ${tiles.map((tile) => {
        const { commodity, trend } = tile;
        const change = trend ? trend.change : 0;
        const direction = trend ? trendDirection(change) : "flat";
        const intensity = Math.round(Math.min(1, Math.abs(change) / 0.05) * 70 + (direction === "flat" ? 0 : 10));
        const share = (tile.w * tile.h) / totalArea;
        const size = share < 0.012 ? "tiny" : (share < 0.03 ? "small" : "large");
        const price = commodity.bestSell ?? trend?.last;
        const label = `${commodity.displayName} · ${price == null ? "--" : number(price)} · ${trend ? formatChange(change) : "--"}`;
        return `
          <button type="button" role="listitem" class="heat-tile is-${direction} is-${size}" data-card-commodity-key="${escapeHtml(commodity.commodityKey)}" data-card-route="${escapeHtml(route)}"
            title="${escapeHtml(label)}" aria-label="${escapeHtml(label)}"
            style="left:${(tile.x / canvas.w * 100).toFixed(3)}%;top:${(tile.y / canvas.h * 100).toFixed(3)}%;width:${(tile.w / canvas.w * 100).toFixed(3)}%;height:${(tile.h / canvas.h * 100).toFixed(3)}%;--heat:${intensity}%;">
            <span class="heat-name">${escapeHtml(commodity.displayName)}</span>
            <span class="heat-meta"><span class="coin-figure">${price == null ? "--" : number(price)}</span> <span class="heat-change">${trend ? formatChange(change) : "--"}</span></span>
          </button>
        `;
      }).join("")}
    </div>
  `;
}

function renderBrowseLayoutSwitch() {
  const current = state.browseLayout;
  return `
    <div class="layout-switch" role="group" aria-label="${escapeHtml(t("layout_label"))}">
      <button type="button" class="${current === "grid" ? "active" : ""}" data-browse-layout="grid" aria-pressed="${current === "grid" ? "true" : "false"}">${escapeHtml(t("layout_grid"))}</button>
      <button type="button" class="${current === "heatmap" ? "active" : ""}" data-browse-layout="heatmap" aria-pressed="${current === "heatmap" ? "true" : "false"}">${escapeHtml(t("layout_heatmap"))}</button>
    </div>
  `;
}

// ---- 盘口深度 ----
// 卖盘 = 挂单(价格升序),买盘 = 求购(按参考价换算的出价,降序);同价合并、累计数量,画深度图和阶梯。
function orderBookLevels(entries, ascending) {
  const merged = new Map();
  entries.forEach(({ price, qty }) => {
    if (price > 0 && qty > 0) {
      merged.set(price, (merged.get(price) || 0) + qty);
    }
  });
  let cumulative = 0;
  return Array.from(merged.entries())
    .sort((left, right) => (ascending ? left[0] - right[0] : right[0] - left[0]))
    .slice(0, 8)
    .map(([price, qty]) => {
      cumulative += qty;
      return { price, qty, cumulative };
    });
}

function renderOrderBook(commodity) {
  const asks = orderBookLevels((commodity.listings || [])
    .filter((listing) => !isInfiniteStock(listing))
    .map((listing) => ({ price: Number(listing.unitPrice) || 0, qty: Number(listing.availableCount) || 0 })), true);
  const reference = buyOrderReferencePrice(commodity);
  const bids = orderBookLevels((commodity.buyBookEntries || []).map((entry) => ({
    price: Math.max(1, Math.round(reference * (1 + (Number(entry.maxPriceBp) || 0) / 10000))),
    qty: Number(entry.quantity) || 0
  })), false);
  if (!asks.length && !bids.length) {
    return "";
  }
  const bestAsk = asks[0]?.price;
  const bestBid = bids[0]?.price;
  const spread = bestAsk != null && bestBid != null ? bestAsk - bestBid : null;
  const maxDepth = Math.max(1, asks[asks.length - 1]?.cumulative || 0, bids[bids.length - 1]?.cumulative || 0);
  const prices = [...asks, ...bids].map((level) => level.price);
  const minPrice = Math.min(...prices);
  const maxPrice = Math.max(...prices);
  const span = maxPrice - minPrice || 1;
  const width = 320;
  const height = 120;
  const px = (price) => ((price - minPrice) / span) * width;
  const py = (depth) => height - (depth / maxDepth) * (height - 8);
  const stepPath = (levels) => {
    if (!levels.length) {
      return "";
    }
    let path = `M${px(levels[0].price).toFixed(1)},${height} `;
    levels.forEach((level, index) => {
      path += `L${px(level.price).toFixed(1)},${py(level.cumulative).toFixed(1)} `;
      const next = levels[index + 1];
      if (next) {
        path += `L${px(next.price).toFixed(1)},${py(level.cumulative).toFixed(1)} `;
      }
    });
    const last = levels[levels.length - 1];
    return `${path}L${px(last.price).toFixed(1)},${height} Z`;
  };
  const ladderRow = (level, side, max) => `
    <li class="ladder-row is-${side}" style="--depth:${Math.round((level.qty / max) * 100)}%;">
      <span class="coin-figure">${number(level.price)}</span>
      <span>${number(level.qty)}</span>
    </li>
  `;
  const maxAskQty = Math.max(1, ...asks.map((level) => level.qty));
  const maxBidQty = Math.max(1, ...bids.map((level) => level.qty));
  return `
    <section class="order-book surface-card" aria-label="${escapeHtml(t("order_book_title"))}">
      <div class="panel-head">
        <div>
          <p class="section-kicker">${escapeHtml(t("order_book_kicker"))}</p>
          <h3>${escapeHtml(t("order_book_title"))}</h3>
        </div>
        <span class="pill">${escapeHtml(t("order_book_spread"))} ${spread == null ? "--" : number(spread)}</span>
      </div>
      <svg class="depth-chart" viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" aria-hidden="true" focusable="false">
        <path class="depth-bids" d="${stepPath(bids)}"></path>
        <path class="depth-asks" d="${stepPath(asks)}"></path>
      </svg>
      <div class="ladder">
        <div class="ladder-head"><span>${escapeHtml(t("unit_price"))}</span><span>${escapeHtml(t("quantity"))}</span></div>
        <ol class="ladder-side is-asks">${asks.slice(0, 5).reverse().map((level) => ladderRow(level, "ask", maxAskQty)).join("")}</ol>
        <div class="ladder-mid">${bestAsk != null ? `${escapeHtml(t("lowest_sell"))} ${number(bestAsk)}` : ""}${bestAsk != null && bestBid != null ? " · " : ""}${bestBid != null ? `${escapeHtml(t("highest_buy"))} ${number(bestBid)}` : ""}</div>
        <ol class="ladder-side is-bids">${bids.slice(0, 5).map((level) => ladderRow(level, "bid", maxBidQty)).join("")}</ol>
      </div>
    </section>
  `;
}

function renderCommodityCard(commodity) {
  return renderPurchaseCommodityCard(commodity);
}

function renderInventoryCard(commodity) {
  return `
    <button type="button" class="goods-card ${commodity.commodityKey === state.selectedCommodityKey && state.activeProductTab === "inventory" ? "active" : ""}" data-card-commodity-key="${escapeHtml(commodity.commodityKey)}" data-card-route="inventory">
      ${renderCommodityIcon(commodity.commodityKey, commodity.displayName)}
      <div class="goods-card-topline">
        <span class="goods-category-tag">${escapeHtml(categoryLabel(commodity.category))}</span>
        <span class="goods-rarity-tag ${rarityClassName(commodity.rarity)}">${escapeHtml(rarityLabel(commodity.rarity))}</span>
      </div>
      <h3>${escapeHtml(commodity.displayName)}</h3>
      <div class="goods-key">${escapeHtml(commodity.commodityKey)}</div>
      <div class="goods-summary">
        <span class="goods-badge">${number(commodity.storageUnits)} ${escapeHtml(t("in_storage"))}</span>
        <span class="goods-badge">${number((commodity.storageEntries || []).length)} ${escapeHtml(t("storage_rows"))}</span>
      </div>
      <div class="goods-card-footer">
        <strong>${commodity.suggestedUnitPrice == null ? "--" : number(commodity.suggestedUnitPrice)}</strong>
        <span>${escapeHtml(t("suggested_word"))}</span>
      </div>
    </button>
  `;
}

function storageEntriesForCommodity(commodity, detail) {
  const commodityKey = normalizeCommodityKey(commodity?.commodityKey);
  if (!commodityKey) {
    return [];
  }
  const direct = (commodity?.storageEntries || []).filter((entry) => normalizeCommodityKey(entry?.commodityKey || entry?.itemName) === commodityKey);
  if (direct.length) {
    return direct;
  }
  return (detail?.storageEntries || []).filter((entry) => normalizeCommodityKey(entry?.commodityKey || entry?.itemName) === commodityKey);
}

function preferredStorageEntry(storageEntries) {
  return (storageEntries || []).find((entry) => Number(entry?.quantity || 0) > 0) || (storageEntries || [])[0] || null;
}

function isPriceConstrained(entry) {
  return entry?.priceConstrained !== false;
}

function listingPriceHelpKey(entry) {
  return isPriceConstrained(entry) ? "manual_price_help" : "manual_price_free_help";
}

function listingPriceRangeText(entry, minAllowedUnitPrice, maxAllowedUnitPrice) {
  if (!isPriceConstrained(entry)) {
    return t("listing_price_free");
  }
  return `${t("listing_price_range")} ${number(minAllowedUnitPrice)} - ${number(maxAllowedUnitPrice)}`;
}

function renderStorageChoiceGrid(storageEntries, selectedStorageIndex, emptyMessage) {
  if (!(storageEntries || []).length) {
    return `<div class="empty-state compact">${escapeHtml(emptyMessage)}</div>`;
  }
  return `
    <div class="storage-choice-grid">
      ${storageEntries.map((entry) => {
        const selected = Number(entry.index) === Number(selectedStorageIndex);
        return `
          <button type="button" class="goods-card storage-choice-card ${selected ? "active" : ""}" data-storage-choice="${entry.index}" aria-pressed="${selected ? "true" : "false"}">
            ${renderCommodityIcon(entry.commodityKey || "", entry.itemName || entry.commodityKey || "")}
            <div class="goods-card-topline">
              <span class="goods-category-tag">${escapeHtml(categoryLabel(entry.category))}</span>
              <span class="goods-rarity-tag ${rarityClassName(entry.rarity)}">${escapeHtml(rarityLabel(entry.rarity))}</span>
            </div>
            <h3>${escapeHtml(entry.itemName || entry.commodityKey || "-")}</h3>
            <div class="goods-key">${escapeHtml(entry.detail || entry.commodityKey || "-")}</div>
            <div class="goods-summary">
              <span class="goods-badge">${number(entry.quantity)} ${escapeHtml(t("in_storage"))}</span>
            </div>
            <div class="goods-card-footer">
              <strong>${entry.suggestedUnitPrice == null ? "--" : number(entry.suggestedUnitPrice)}</strong>
              <span>${escapeHtml(t("suggested_word"))}</span>
            </div>
          </button>
        `;
      }).join("")}
    </div>
  `;
}

function renderCreateListingPanel(commodity, detail, canManage, canAct) {
  const matchingStorage = storageEntriesForCommodity(commodity, detail);
  const preferredStorage = preferredStorageEntry(matchingStorage);
  const selectedStorageIndex = preferredStorage?.index ?? -1;
  const suggestedUnitPrice = Math.max(1, Number(preferredStorage?.suggestedUnitPrice) || Number(commodity?.suggestedUnitPrice) || Number(commodity?.referencePrice) || 1);
  const minAllowedUnitPrice = Math.max(1, Number(preferredStorage?.minAllowedUnitPrice) || suggestedUnitPrice);
  const maxAllowedUnitPrice = Math.max(minAllowedUnitPrice, Number(preferredStorage?.maxAllowedUnitPrice) || minAllowedUnitPrice);
  const priceRangeText = listingPriceRangeText(preferredStorage, minAllowedUnitPrice, maxAllowedUnitPrice);
  return `
    <div class="action-box">
      <div class="panel-head">
        <div>
          <p class="section-kicker">${escapeHtml(t("sell_item"))}</p>
          <h3>${escapeHtml(t("create_listing"))}</h3>
        </div>
        <span class="pill">${number(matchingStorage.length)} ${escapeHtml(t("storage_rows"))}</span>
      </div>
      ${matchingStorage.length ? `
        <div class="stack">
          <div class="summary-note">${escapeHtml(commodity.displayName)} · ${number(sumBy(matchingStorage, "quantity"))} ${escapeHtml(t("in_storage"))}.</div>
          <input id="create-listing-storage" type="hidden" value="${escapeHtml(String(selectedStorageIndex))}">
          <div class="summary-note">${escapeHtml(t(listingPriceHelpKey(preferredStorage)))}</div>
          ${renderStorageChoiceGrid(matchingStorage, selectedStorageIndex, t("no_storage_match"))}
          <input id="create-listing-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultListingQuantity || 1))}" placeholder="${escapeHtml(t("quantity"))}">
          <input id="create-listing-price" type="number" min="1" value="${escapeHtml(String(suggestedUnitPrice))}" placeholder="${escapeHtml(t("manual_price"))}">
          <div id="create-listing-price-preview" class="summary-note">${escapeHtml(t("listing_price_preview"))} ${number(suggestedUnitPrice)} | ${escapeHtml(priceRangeText)}</div>
          <textarea id="create-listing-note" placeholder="${escapeHtml(t("seller_note"))}"></textarea>
          <div class="actions">
            <button type="button" id="create-listing-button" data-can-create="${canManage && canAct ? "true" : "false"}" ${canManage && canAct ? "" : "disabled"}>${escapeHtml(t("create_listing"))}</button>
            <button type="button" id="claim-credits-button" class="secondary" ${canAct ? "" : "disabled"}>${escapeHtml(t("claim_credits"))}</button>
          </div>
        </div>
      ` : `<div class="empty-state">${escapeHtml(t("no_storage_match"))}</div>`}
    </div>
  `;
}

// 从殖民地(MineColonies)仓库上架面板:仅 canManage 渲染。物品列表来自 state.mineColoniesItems(独立端点拉取)。
function renderMineColoniesPanel(detail, canManage, canAct) {
  if (!canManage) {
    return "";
  }
  const items = Array.isArray(state.mineColoniesItems) ? state.mineColoniesItems : [];
  const head = `
    <div class="panel-head">
      <div>
        <p class="section-kicker">${escapeHtml(t("mc_warehouse_kicker"))}</p>
        <h3>${escapeHtml(t("mc_warehouse_title"))}</h3>
      </div>
      <span class="pill">${escapeHtml(t("mc_warehouse_count", { count: number(items.length) }))}</span>
    </div>
    <p class="muted">${escapeHtml(t("mc_warehouse_hint"))}</p>
  `;
  if (!items.length) {
    return `
      <div class="surface-card stack">
        ${head}
        <div class="empty-state">${escapeHtml(state.mineColoniesLoaded ? t("mc_warehouse_empty") : t("all_listings_loading"))}</div>
      </div>
    `;
  }
  const rows = items.map((item, index) => {
    const itemId = String(item.itemId || "");
    const colonyId = Number(item.colonyId || 0);
    const stock = Number(item.count || 0);
    const suggested = Math.max(1, Number(item.suggestedUnitPrice) || 1);
    const defaultQty = Math.max(1, Math.min(stock, Number(state.settings.defaultListingQuantity) || 1));
    return `
      <tr>
        <td>
          <strong>${escapeHtml(item.itemName || itemId || "-")}</strong>
          <div class="muted-inline">${escapeHtml(item.commodityKey || itemId)}</div>
        </td>
        <td>${number(stock)}</td>
        <td><input id="mc-qty-${index}" type="number" min="1" max="${escapeHtml(String(stock))}" value="${escapeHtml(String(defaultQty))}" placeholder="${escapeHtml(t("mc_warehouse_col_quantity"))}"></td>
        <td><input id="mc-price-${index}" type="number" min="1" value="${escapeHtml(String(suggested))}" placeholder="${escapeHtml(t("mc_warehouse_col_price"))}"></td>
        <td>
          <button type="button"
            data-mc-list="${escapeHtml(itemId)}"
            data-mc-index="${index}"
            data-mc-colony="${escapeHtml(String(colonyId))}"
            ${canAct ? "" : "disabled"}>${escapeHtml(t("mc_warehouse_list"))}</button>
        </td>
      </tr>
    `;
  }).join("");
  return `
    <div class="table-card">
      ${head}
      <div class="table-wrap">
        <table>
          <thead><tr>
            <th>${escapeHtml(t("mc_warehouse_col_item"))}</th>
            <th>${escapeHtml(t("mc_warehouse_col_stock"))}</th>
            <th>${escapeHtml(t("mc_warehouse_col_quantity"))}</th>
            <th>${escapeHtml(t("mc_warehouse_col_price"))}</th>
            <th>${escapeHtml(t("mc_warehouse_col_action"))}</th>
          </tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </div>
  `;
}

function renderInventoryDetailPage(commodity, detail, canManage, canAct) {
  const matchingStorage = storageEntriesForCommodity(commodity, detail);
  const preferredStorage = preferredStorageEntry(matchingStorage);
  return `
    <div class="goods-detail">
      <div class="crumb-strip">
        <span>${escapeHtml(t("market"))}</span>
        <span>/</span>
        <span>${escapeHtml(detail.marketName)}</span>
        <span>/</span>
        <span>${escapeHtml(t("inventory_tab"))}</span>
        <span>/</span>
        <strong>${escapeHtml(commodity.displayName)}</strong>
      </div>

      <section class="goods-hero">
        ${renderCommodityIcon(commodity.commodityKey, commodity.displayName)}
        <div class="goods-main">
          <div class="tiny-label">${escapeHtml(t("inventory_title"))}</div>
          <div class="goods-title-row">
            <h2>${escapeHtml(commodity.displayName)}</h2>
            <button type="button" class="watch-toggle ${isWatched(commodity.commodityKey) ? "is-on" : ""}" data-watch-toggle="${escapeHtml(commodity.commodityKey)}" aria-pressed="${isWatched(commodity.commodityKey) ? "true" : "false"}">
              <span aria-hidden="true">${isWatched(commodity.commodityKey) ? "★" : "☆"}</span> ${escapeHtml(isWatched(commodity.commodityKey) ? t("watch_on") : t("watch_add"))}
            </button>
          </div>
          <div class="muted">${escapeHtml(commodity.commodityKey)}</div>
          <div class="goods-stats">
            ${metricBox(t("in_storage"), number(commodity.storageUnits))}
            ${metricBox(t("storage_rows"), number(matchingStorage.length))}
            ${metricBox(t("suggested_word"), commodity.suggestedUnitPrice == null ? "--" : number(commodity.suggestedUnitPrice))}
            ${metricBox(t("reference_price"), commodity.referencePrice == null ? "--" : number(commodity.referencePrice))}
          </div>
        </div>
        <div class="stack">
          <div class="quote-box neutral">
            <span class="quote-label">${escapeHtml(t("inventory_title"))}</span>
            <strong>${number(sumBy(matchingStorage, "quantity"))}</strong>
          </div>
          <div class="quote-box neutral">
            <span class="quote-label">${escapeHtml(t("storage_rows"))}</span>
            <strong>${number(matchingStorage.length)}</strong>
          </div>
          <div class="quote-box neutral">
            <span class="quote-label">${escapeHtml(t("suggested_word"))}</span>
            <strong>${preferredStorage?.suggestedUnitPrice == null ? "--" : number(preferredStorage.suggestedUnitPrice)}</strong>
          </div>
          <div class="actions">
            <button type="button" class="secondary" data-inventory-overview="true">${escapeHtml(t("inventory_tab"))}</button>
            <button type="button" data-card-commodity-key="${escapeHtml(commodity.commodityKey)}" data-card-route="sell">${escapeHtml(t("selling"))}</button>
          </div>
        </div>
      </section>

      <div class="detail-grid">
        <div class="surface-card stack">
          <div class="panel-head">
            <div>
              <p class="section-kicker">${escapeHtml(t("inventory_tab"))}</p>
              <h3>${escapeHtml(t("inventory_title"))}</h3>
            </div>
            <span class="pill">${number(matchingStorage.length)} ${escapeHtml(t("storage_rows"))}</span>
          </div>
          <div class="summary-note">${escapeHtml(t("dock_stock"))} ${number(sumBy(matchingStorage, "quantity"))}.</div>
          ${renderStorageChoiceGrid(matchingStorage, preferredStorage?.index ?? -1, t("no_storage_match"))}
        </div>

        <div class="stack">
          ${renderCreateListingPanel(commodity, detail, canManage, canAct)}
        </div>
      </div>
    </div>
  `;
}

function renderCommodityPage(commodity, detail, canManage, canAct, options = null) {
  const tab = currentProductTab(options);
  if (tab === "buy" || tab === "demand") {
    return renderBuyingTab(commodity, canManage, canAct, options);
  }
  if (tab === "chart") {
    return renderChartTab(commodity, detail, options);
  }
  if (tab === "index") {
    return renderIndexTab(commodity, detail, options);
  }
  if (tab === "sell") {
    return renderSellingTab(commodity, detail, canManage, canAct, options);
  }
  return renderBrowseTab(commodity, detail, options);
}

function renderBrowseTab(commodity, detail, options = null) {
  const topListings = commodity.listings.slice(0, 8);
  const buyRows = (commodity.buyBookEntries || []).slice(0, 6);
  const activeSeries = primaryChartSeries(commodity);
  const latestPoint = latestChartPoint(activeSeries);
  const chartStats = chartSummary(activeSeries);

  return `
    <div class="detail-grid">
      ${tableSection(
        t("selling"),
        [t("seller"), t("available"), t("unit_price"), t("dock")],
        topListings.map((entry) => `
          <tr>
            <td>
              <strong>${escapeHtml(entry.sellerName || "-")}</strong>
              <div class="muted-inline">${escapeHtml(entry.sellerNote || entry.nationId || "")}</div>
            </td>
            <td>${stockDisplay(entry)}</td>
            <td>${number(entry.unitPrice)}</td>
            <td>${escapeHtml(entry.sourceDockName || "-")}</td>
          </tr>
        `).join(""),
        t("no_selling_rows")
      )}

      <div class="stack">
        <div class="surface-card stack">
          <div class="panel-head">
            <div>
              <p class="section-kicker">${escapeHtml(t("market_snapshot"))}</p>
              <h3>${escapeHtml(t("browse_tab"))}</h3>
            </div>
          </div>
          <div class="summary-note">${escapeHtml(t("lowest_sell"))} ${commodity.bestSell == null ? "--" : number(commodity.bestSell)}.</div>
          <div class="summary-note">${escapeHtml(t("highest_buy"))} ${commodity.bestBuy == null ? "--" : number(commodity.bestBuy)}.</div>
          <div class="summary-note">${escapeHtml(t("avg_24h"))} ${latestPoint ? number(latestPoint.averageUnitPrice) : "--"} · ${escapeHtml(t("trades_24h"))} ${number(chartStats.tradeCount)}.</div>
          <div class="summary-note">${escapeHtml(t("in_storage"))} ${number(commodity.storageUnits)} · ${escapeHtml(t("open_demand"))} ${number(commodity.demandUnits)}.</div>
          <div class="summary-note">${escapeHtml(detail.linkedDock ? t("dock_linked") : t("no_linked_dock"))}.</div>
        </div>

        ${tableSection(
          t("buying"),
          [t("buyer"), t("quantity"), t("price_band")],
          buyRows.map((entry) => `
            <tr>
              <td><strong>${escapeHtml(entry.buyerName || "-")}</strong></td>
              <td>${number(entry.quantity)}</td>
              <td>${number(entry.minPriceBp)} ${escapeHtml(t("to_word"))} ${number(entry.maxPriceBp)} bp</td>
            </tr>
          `).join(""),
          t("no_buy_rows")
        )}
      </div>
    </div>
  `;
}

// 聚合详情"在售购买"tab:全服该商品在售挂单按价升序,带来源城镇与聚合购买按钮(data-aggregate-purchase + listingId)。
function renderAggregateSellingTab(commodity) {
  const rows = (commodity.listings || []).slice().sort((left, right) => {
    return Number(left.unitPrice ?? Infinity) - Number(right.unitPrice ?? Infinity);
  }).map((listing) => `
    <tr>
      <td>
        <strong>${escapeHtml(listing.sellerName || "-")}</strong>
        ${listing.sellerNote ? `<div class="muted-inline">${escapeHtml(listing.sellerNote)}</div>` : ""}
      </td>
      <td>${escapeHtml(listing.sourceTownName || "-")}</td>
      <td>${number(listing.unitPrice)}</td>
      <td>${stockDisplay(listing)}</td>
      <td><div class="actions">${renderAggregatedPurchaseButton(listing)}</div></td>
    </tr>
  `).join("");
  return `
    <div class="goods-detail">
      ${renderOrderBook(commodity)}
      ${tableSection(
        t("all_listings_title"),
        [
          t("all_listings_col_seller"),
          t("all_listings_col_town"),
          t("all_listings_col_price"),
          t("all_listings_col_stock"),
          t("all_listings_col_action")
        ],
        rows,
        t("all_listings_empty")
      )}
    </div>
  `;
}

function renderSellingTab(commodity, detail, canManage, canAct, options = null) {
  // 聚合详情:无单市场库存/创建挂单,只展示全服在售列表 + 聚合购买按钮(走 listingId 端点)。
  if (options && options.aggregate) {
    return renderAggregateSellingTab(commodity);
  }
  const dockStorage = detail.storageEntries || [];
  const matchingStorage = storageEntriesForCommodity(commodity, detail);
  const preferredStorage = preferredStorageEntry(matchingStorage);
  const selectedStorageIndex = preferredStorage?.index ?? dockStorage[0]?.index ?? -1;
  const suggestedUnitPrice = Math.max(1, Number(preferredStorage?.suggestedUnitPrice) || Number(commodity?.suggestedUnitPrice) || Number(commodity?.referencePrice) || 1);
  const minAllowedUnitPrice = Math.max(1, Number(preferredStorage?.minAllowedUnitPrice) || suggestedUnitPrice);
  const maxAllowedUnitPrice = Math.max(minAllowedUnitPrice, Number(preferredStorage?.maxAllowedUnitPrice) || minAllowedUnitPrice);
  const priceRangeText = listingPriceRangeText(preferredStorage, minAllowedUnitPrice, maxAllowedUnitPrice);

  return `
    <div class="detail-grid">
      ${tableSection(
        t("selling"),
        [t("seller"), t("available"), t("reserved"), t("unit_price"), t("total"), t("dock"), t("actions")],
        commodity.listings.map((entry) => `
          <tr>
            <td>
              <strong>${escapeHtml(entry.sellerName || "-")}</strong>
              <div class="muted-inline">${escapeHtml(entry.sellerNote || entry.nationId || "")}</div>
            </td>
            <td>${stockDisplay(entry)}</td>
            <td>${number(entry.reservedCount)}</td>
            <td>${number(entry.unitPrice)}</td>
            <td>${isInfiniteStock(entry) ? "∞" : number((Number(entry.unitPrice) || 0) * (Number(entry.availableCount) || 0))}</td>
            <td>${escapeHtml(entry.sourceDockName || "-")}</td>
            <td>
              <div class="actions">
                <button type="button" data-purchase-index="${entry.index}" ${canAct ? "" : "disabled"}>${escapeHtml(t("buy_1"))}</button>
                ${canManage ? `<button type="button" class="danger" data-cancel-listing="${escapeHtml(entry.listingId || "")}" ${canAct ? "" : "disabled"}>${escapeHtml(t("cancel"))}</button>` : ""}
              </div>
            </td>
          </tr>
        `).join(""),
        t("no_selling_rows")
      )}

      <div class="stack">
        ${renderOrderBook(commodity)}
        <div class="surface-card stack">
          <div class="panel-head">
            <div>
              <p class="section-kicker">${escapeHtml(t("inventory_tab"))}</p>
              <h3>${escapeHtml(t("inventory_title"))}</h3>
            </div>
            <span class="pill">${number(matchingStorage.length)} ${escapeHtml(t("storage_rows"))}</span>
          </div>
          <div class="summary-note">${escapeHtml(t("dock_stock"))} ${number(dockStorage.length)} ${escapeHtml(t("storage_rows"))} / ${number(sumBy(dockStorage, "quantity"))}.</div>
          <div class="summary-note">${preferredStorage ? `${escapeHtml(commodity.displayName)} x${number(preferredStorage.quantity)} · ${escapeHtml(t("suggested_word"))} ${number(preferredStorage.suggestedUnitPrice)}` : escapeHtml(t("no_storage_match"))}</div>
          ${canManage ? `<div class="actions"><button type="button" class="secondary" data-route="inventory">${escapeHtml(t("inventory_tab"))}</button></div>` : ""}
        </div>

        <div class="action-box">
          <div class="panel-head">
            <div>
              <p class="section-kicker">${escapeHtml(t("sell_item"))}</p>
              <h3>${escapeHtml(t("create_listing"))}</h3>
            </div>
            <span class="pill">${number(matchingStorage.length)} ${escapeHtml(t("storage_rows"))}</span>
          </div>
          ${matchingStorage.length ? `
            <div class="stack">
              ${renderStorageChoiceGrid(matchingStorage, selectedStorageIndex, t("no_storage_match"))}
              <input id="create-listing-storage" type="hidden" value="${escapeHtml(String(selectedStorageIndex))}">
              <div class="summary-note">${escapeHtml(t(listingPriceHelpKey(preferredStorage)))}</div>
              <div class="summary-note">${escapeHtml(priceRangeText)} | ${escapeHtml(t("suggested_word"))} ${number(suggestedUnitPrice)}</div>
              <input id="create-listing-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultListingQuantity || 1))}" placeholder="${escapeHtml(t("quantity"))}">
              <input id="create-listing-price" type="number" min="1" value="${escapeHtml(String(suggestedUnitPrice))}" placeholder="${escapeHtml(t("manual_price"))}">
              <div id="create-listing-price-preview" class="summary-note">${escapeHtml(t("listing_price_preview"))} ${number(suggestedUnitPrice)} | ${escapeHtml(priceRangeText)}</div>
              <textarea id="create-listing-note" placeholder="${escapeHtml(t("seller_note"))}"></textarea>
              <div class="actions">
                <button type="button" id="create-listing-button" data-can-create="${canManage && canAct ? "true" : "false"}" ${canManage && canAct ? "" : "disabled"}>${escapeHtml(t("create_listing"))}</button>
                <button type="button" id="claim-credits-button" class="secondary" ${canAct ? "" : "disabled"}>${escapeHtml(t("claim_credits"))}</button>
              </div>
            </div>
          ` : `<div class="empty-state">${escapeHtml(t("no_storage"))}</div>`}
        </div>

      </div>
    </div>
  `;
}

// 聚合详情"求购订单"tab:只读展示全服该商品的求购挂单(无单市场上下文,不提供建单/派发面板)。
function renderAggregateBuyingTab(commodity) {
  const buyRows = commodity.buyBookEntries || [];
  return `
    <div class="goods-detail">
      ${tableSection(
        t("buying"),
        [t("buyer"), t("quantity"), t("price_band"), t("implied_bid"), t("status")],
        buyRows.map((entry) => `
          <tr>
            <td><strong>${escapeHtml(entry.buyerName || "-")}</strong></td>
            <td>${number(entry.quantity)}</td>
            <td>${number(entry.minPriceBp)} ${escapeHtml(t("to_word"))} ${number(entry.maxPriceBp)} bp</td>
            <td>${estimateBidText(commodity.bestSell, entry.minPriceBp, entry.maxPriceBp)}</td>
            <td>${escapeHtml(entry.status || "-")}</td>
          </tr>
        `).join(""),
        t("no_buy_rows")
      )}
    </div>
  `;
}

function renderBuyingTab(commodity, canManage, canAct, options = null) {
  if (options && options.aggregate) {
    return renderAggregateBuyingTab(commodity);
  }
  const pureDemandPage = state.activeProductTab === "demand";
  const buyRows = commodity.buyBookEntries || [];
  const ownRows = commodity.myBuyOrders || [];
  const dispatchOrders = Array.isArray(state.detail?.sourceOrders) ? state.detail.sourceOrders : [];
  const dispatchOrder = selectedDispatchOrder(state.detail);
  const dispatchOption = selectedDispatchOption(dispatchOrder);
  const buyOrderReference = buyOrderReferencePrice(commodity);
  const buyOrderDefaultPrice = buyOrderReference;

  return `
    <div class="detail-grid">
      <div class="stack">
        ${tableSection(
          t("buying"),
          [t("buyer"), t("quantity"), t("price_band"), t("implied_bid"), t("status")],
          buyRows.map((entry) => `
            <tr>
              <td><strong>${escapeHtml(entry.buyerName || "-")}</strong></td>
              <td>${number(entry.quantity)}</td>
              <td>${number(entry.minPriceBp)} ${escapeHtml(t("to_word"))} ${number(entry.maxPriceBp)} bp</td>
              <td>${estimateBidText(commodity.bestSell, entry.minPriceBp, entry.maxPriceBp)}</td>
              <td>${escapeHtml(entry.status || "-")}</td>
            </tr>
        `).join(""),
          t("no_buy_rows")
        )}

        ${tableSection(
          t("my_buy_orders"),
          [t("quantity"), t("price_band"), t("status"), t("actions")],
          ownRows.map((entry) => `
            <tr>
              <td>${number(entry.quantity)}</td>
              <td>${number(entry.minPriceBp)} ${escapeHtml(t("to_word"))} ${number(entry.maxPriceBp)} bp</td>
              <td>${escapeHtml(entry.status || "-")}</td>
              <td><button type="button" class="danger" data-cancel-buy-order="${escapeHtml(entry.orderId || "")}" ${canAct ? "" : "disabled"}>${escapeHtml(t("cancel"))}</button></td>
            </tr>
          `).join(""),
          t("no_my_buy_rows")
        )}
      </div>

      <div class="stack">
        <div class="action-box">
          <div class="panel-head">
            <div>
              <p class="section-kicker">${escapeHtml(t("create_buy_order"))}</p>
              <h3>${escapeHtml(t("buying_demand"))}</h3>
            </div>
            <span class="pill">${number(buyRows.length)} ${escapeHtml(t("live_requests"))}</span>
          </div>
          <div class="stack">
            <label class="tiny-label" for="buy-order-key">${escapeHtml(t("buy_order_item_id"))}</label>
            <input id="buy-order-key" type="text" value="${escapeHtml(commodity.commodityKey)}" placeholder="minecraft:oak_log" aria-describedby="buy-order-preview buy-order-item-help">
            <div id="buy-order-item-help" class="summary-note">${escapeHtml(t("buy_order_item_help"))}</div>
            ${renderBuyOrderItemPreview(commodity)}
            <label class="tiny-label" for="buy-order-quantity">${escapeHtml(t("buy_order_quantity_label"))}</label>
            <input id="buy-order-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultBuyOrderQuantity || 1))}" placeholder="${escapeHtml(t("quantity"))}">
            <label class="tiny-label" for="buy-order-unit-price">${escapeHtml(t("buy_order_unit_price"))}</label>
            <input id="buy-order-unit-price" type="number" min="1" value="${escapeHtml(String(buyOrderDefaultPrice))}" placeholder="${escapeHtml(t("buy_order_unit_price"))}">
            <input id="buy-order-reference-price" type="hidden" value="${escapeHtml(String(buyOrderReference))}">
            <div id="buy-order-price-help" class="summary-note">${escapeHtml(t("buy_order_price_help"))}</div>
            <div id="buy-order-bp-preview" class="summary-note">${escapeHtml(buyOrderBpPreviewText(buyOrderDefaultPrice, buyOrderReference))}</div>
            ${pureDemandPage ? "" : `
              <label class="tiny-label" for="dispatch-order-index">Dispatch order</label>
              <select id="dispatch-order-index">
                ${dispatchOrders.length
                  ? dispatchOrders.map((entry, index) => `<option value="${index}" ${index === Number(state.selectedDispatchOrderIndex || 0) ? "selected" : ""}>${escapeHtml(entry.targetDockName || entry.label || `Order ${index + 1}`)} x${number(entry.quantity)}</option>`).join("")
                  : `<option value="0">No open orders</option>`}
              </select>
              <label class="tiny-label" for="dispatch-terminal-type">${escapeHtml(t("terminal_type_label"))}</label>
              <select id="dispatch-terminal-type">
                <option value="PORT" ${String(state.selectedDispatchTerminal).toUpperCase() === "PORT" ? "selected" : ""}>${escapeHtml(t("terminal_type_port"))}</option>
                <option value="POST_STATION" ${String(state.selectedDispatchTerminal).toUpperCase() === "POST_STATION" ? "selected" : ""}>${escapeHtml(t("terminal_type_post_station"))}</option>
              </select>
              <div class="dispatch-preview">
                ${dispatchOrder ? `
                  <div class="dispatch-preview-line"><strong>${escapeHtml(dispatchOrder.sourceDockName || "-")}</strong> -> <strong>${escapeHtml(dispatchOrder.targetDockName || "-")}</strong></div>
                  <div class="dispatch-preview-line">${number(dispatchOrder.quantity)} units | ${escapeHtml(dispatchOrder.status || "-")}</div>
                  ${dispatchOrder.queuePosition > 0 ? `<div class="dispatch-preview-line order-queue">${escapeHtml(t("queue_position").replace("%1", dispatchOrder.queuePosition).replace("%2", Math.max(1, Math.floor((dispatchOrder.queueEtaSeconds || 0) / 60))))}</div>` : ""}
                  <div class="dispatch-preview-line">${escapeHtml(dispatchOption?.terminalLabel || "-")} | ${escapeHtml(dispatchOption?.availability || "-")}</div>
                  <div class="dispatch-preview-line">${escapeHtml(dispatchOption?.routeName || "-")}</div>
                  <div class="dispatch-preview-meta">
                    <span>${escapeHtml(dispatchOption?.carrierName || "-")}</span>
                    <span>${escapeHtml(formatDistanceMeters(dispatchOption?.distanceMeters))}</span>
                    <span>${escapeHtml(formatEtaSeconds(dispatchOption?.etaSeconds))}</span>
                  </div>
                  ${dispatchOption?.detail ? `<div class="summary-note">${escapeHtml(dispatchOption.detail)}</div>` : ""}
                ` : `<div class="summary-note">No open orders to dispatch.</div>`}
              </div>
            `}
            <div class="actions">
              <button type="button" id="buy-order-button" data-can-create="${canAct ? "true" : "false"}" data-item-valid="true" data-resolved-commodity-key="${escapeHtml(commodity.commodityKey)}" ${canAct ? "" : "disabled"}>${escapeHtml(t("create_buy_order"))}</button>
              ${pureDemandPage ? "" : `<button type="button" id="dispatch-button" class="warn" ${canManage && canAct && dispatchOrder && dispatchOption?.available ? "" : "disabled"}>${escapeHtml(t("retry_dispatch"))}</button>`}
              ${(!pureDemandPage && dispatchOrder && dispatchOrder.cancellable && dispatchOrder.orderId) ? `<button type="button" id="cancel-order-button" class="danger" data-order-id="${escapeHtml(dispatchOrder.orderId)}">${escapeHtml(t("order_cancel"))}</button>` : ""}
            </div>
          </div>
        </div>

        <div class="surface-card">
          <div class="panel-head">
            <div>
              <p class="section-kicker">${escapeHtml(t("demand_summary"))}</p>
              <h3>${escapeHtml(t("demand_summary"))}</h3>
            </div>
          </div>
          ${buyRows.length ? `
            <div class="book-list">
              ${buyRows.slice(0, 6).map((entry) => `
                <div class="book-row">
                  <strong>${escapeHtml(entry.buyerName || "-")}</strong>
                  <div class="muted-inline">${number(entry.quantity)} ${escapeHtml(t("units_wanted"))} at ${number(entry.minPriceBp)} ${escapeHtml(t("to_word"))} ${number(entry.maxPriceBp)} bp. ${escapeHtml(estimateBidSentence(commodity.bestSell, entry.minPriceBp, entry.maxPriceBp))}</div>
                </div>
              `).join("")}
            </div>
          ` : `<div class="empty-state">${escapeHtml(t("no_demand_ladder"))}</div>`}
        </div>
      </div>
    </div>
  `;
}

function renderChartTab(commodity, detail, options = null) {
  const chart = primaryChartSeriesForTimeframe(commodity, state.activeChartTimeframe);
  const points = normalizedChartPoints(chart);
  const cpiSeries = analyticsSeries(detail, "MACRO_INDEX", "cpi");
  const loansSeries = analyticsSeries(detail, "MACRO_INDEX", "outstanding_loans");
  const inflationAvailable = !!detail?.chartCapabilities?.inflation && !!cpiSeries?.points?.length;
  const displayPoints = state.chartFlags.inflationAdjusted && inflationAvailable
    ? adjustChartPointsForInflation(points, cpiSeries)
    : points;
  const lastPoints = displayPoints.slice(-24);
  const stats = chartSummaryFromPoints(displayPoints);
  const derived = derivedChartStats(displayPoints);
  const cpiDerived = derivedSeriesStats(cpiSeries);
  const loansDerived = derivedSeriesStats(loansSeries);
  const currentCpi = cpiSeries?.points?.length ? Number(cpiSeries.points[cpiSeries.points.length - 1].value || 0) : null;

  return `
    <div class="stack">
      <div class="surface-card">
        <div class="panel-head">
          <div>
            <p class="section-kicker">${escapeHtml(t("price_history"))}</p>
            <h3>${escapeHtml(t("chart_tab"))}</h3>
          </div>
        </div>
        ${state.settings.showPriceCharts ? (lastPoints.length ? `
          <div class="kline-board">
            <div class="kline-header">
              <strong>${escapeHtml(commodity.displayName)}</strong>
              <span class="pill">${escapeHtml(state.activeChartTimeframe)}</span>
            </div>
            <div class="chart-controls">
              <div class="chart-control-group">
                <span class="chart-control-label">${escapeHtml(t("timeframe"))}</span>
                <div class="chart-toggle-row">
                  ${renderChartTimeframeButton("1h")}
                  ${renderChartTimeframeButton("1d")}
                  ${renderChartTimeframeButton("1w")}
                </div>
              </div>
              <div class="chart-control-group">
                <span class="chart-control-label">${escapeHtml(t("indicators"))}</span>
                <div class="chart-toggle-row">
                  ${renderIndicatorToggle("ma5", "MA5", state.chartIndicators.ma5)}
                  ${renderIndicatorToggle("ma20", "MA20", state.chartIndicators.ma20)}
                  ${renderIndicatorToggle("volume", "VOL", state.chartIndicators.volume)}
                </div>
              </div>
              <div class="chart-control-group chart-control-inline">
                <label class="chart-check">
                  <input type="checkbox" data-chart-flag="logScale" ${state.chartFlags.logScale ? "checked" : ""}>
                  <span>${escapeHtml(t("log_scale"))}</span>
                </label>
                <label class="chart-check ${inflationAvailable ? "" : "disabled"}" title="${escapeHtml(inflationAvailable ? t("inflation_adjust") : t("inflation_unavailable"))}">
                  <input type="checkbox" data-chart-flag="inflationAdjusted" ${state.chartFlags.inflationAdjusted && inflationAvailable ? "checked" : ""} ${inflationAvailable ? "" : "disabled"}>
                  <span>${escapeHtml(t("inflation_adjust"))}</span>
                </label>
                <button type="button" class="secondary chart-reset" data-chart-reset>${escapeHtml(t("reset_zoom"))}</button>
              </div>
            </div>
            <div class="chart-legend">
              <span class="chart-legend-item"><span class="chart-legend-swatch ${displayPoints.length && displayPoints[displayPoints.length - 1].closeUnitPrice >= displayPoints[0].openUnitPrice ? "up" : "down"}"></span>${escapeHtml(displayPoints.length && displayPoints[displayPoints.length - 1].closeUnitPrice >= displayPoints[0].openUnitPrice ? "UP" : "DOWN")}</span>
              <span class="chart-legend-item"><span class="chart-legend-swatch ma-short"></span>MA5</span>
              <span class="chart-legend-item"><span class="chart-legend-swatch ma-long"></span>MA20</span>
            </div>
            <div class="kline-chart">
              <div id="lw-chart" class="chart-canvas" data-island="chart" data-chart-key="${escapeHtml(commodity.commodityKey)}"></div>
            </div>
            <div id="chart-hover-details" class="chart-hover-details">${escapeHtml(t("chart_hover_empty"))}</div>
          </div>
        ` : `<div class="empty-state">${escapeHtml(t("no_chart_buckets"))}</div>`) : `<div class="empty-state">${escapeHtml(t("chart_disabled"))}</div>`}
        <div class="chart-stats-grid">
          ${metricBox(`24h ${t("low")}`, stats.low == null ? "--" : number(stats.low))}
          ${metricBox(`24h ${t("high")}`, stats.high == null ? "--" : number(stats.high))}
          ${metricBox(`24h ${t("volume")}`, number(stats.volume))}
          ${metricBox(t("trades_24h"), number(stats.tradeCount))}
          ${metricBox(t("current_change"), percent(derived.changeRatio))}
          ${metricBox(t("volatility_word"), percent(derived.volatility))}
          ${metricBox(t("max_drawdown"), percent(derived.maxDrawdown))}
          ${metricBox(t("inception_return"), percent(derived.inceptionReturn))}
          ${metricBox(t("cpi_now"), currentCpi == null ? "--" : number(currentCpi))}
          ${metricBox(t("inflation_since_inception"), percent(cpiDerived.inceptionReturn))}
          ${metricBox(t("loans_change"), percent(loansDerived.changeRatio))}
        </div>
      </div>

      ${tableSection(
        t("chart_tab"),
        [t("time"), "O", "H", "L", "C", t("volume")],
        lastPoints.map((point) => `
          <tr>
            <td>${escapeHtml(formatTime(point.bucketAt))}</td>
            <td>${number(point.openUnitPrice ?? point.averageUnitPrice)}</td>
            <td>${number(point.maxUnitPrice)}</td>
            <td>${number(point.minUnitPrice)}</td>
            <td>${number(point.closeUnitPrice ?? point.averageUnitPrice)}</td>
            <td>${number(point.volume)}</td>
          </tr>
      `).join(""),
        t("no_chart_rows")
      )}
    </div>
  `;
}

function renderIndexTab(commodity, detail, options = null) {
  const impact = commodity.impact || null;
  const marketIndex = analyticsSeries(detail, "MARKET_INDEX", "global");
  const categoryIndex = analyticsSeries(detail, "CATEGORY_INDEX", firstListingCategory(commodity) || "");
  const cpiSeries = analyticsSeries(detail, "MACRO_INDEX", "cpi");
  const loansSeries = analyticsSeries(detail, "MACRO_INDEX", "outstanding_loans");
  const marketRows = [marketIndex, categoryIndex, cpiSeries, loansSeries].filter(Boolean);

  return `
    <div class="stack">
      <div class="surface-card">
        <div class="panel-head">
          <div>
            <p class="section-kicker">${escapeHtml(t("market_snapshot"))}</p>
            <h3>${escapeHtml(t("macro_overview"))}</h3>
          </div>
        </div>
        ${marketIndex?.points?.length ? `
          <div class="macro-chart-card">
            <div class="panel-subtitle">${escapeHtml(t("market_index_chart"))}</div>
            <div id="market-index-chart" class="macro-chart" data-island="macro"></div>
          </div>
        ` : ""}
        ${categoryIndex?.points?.length ? `
          <div class="macro-chart-card">
            <div class="panel-subtitle">${escapeHtml(t("category_index_chart"))}</div>
            <div id="category-index-chart" class="macro-chart" data-island="macro"></div>
          </div>
        ` : ""}
        ${cpiSeries?.points?.length ? `
          <div class="macro-chart-card">
            <div class="panel-subtitle">${escapeHtml(t("cpi_chart"))}</div>
            <div id="cpi-chart" class="macro-chart macro-chart-compact" data-island="macro"></div>
          </div>
        ` : ""}
        ${loansSeries?.points?.length ? `
          <div class="macro-chart-card">
            <div class="panel-subtitle">${escapeHtml(t("loans_chart"))}</div>
            <div id="loans-chart" class="macro-chart macro-chart-compact" data-island="macro"></div>
          </div>
        ` : ""}
        <div class="chart-list">
          ${marketRows.map((series) => renderIndexRow(series)).join("")}
          <div class="chart-row">
            <strong>${escapeHtml(t("current_sell_side"))}</strong>
            <div class="muted-inline">${number(commodity.totalListings)} ${escapeHtml(t("listings_word"))} and ${number(commodity.sellUnits)} ${escapeHtml(t("on_sale"))}.</div>
          </div>
          <div class="chart-row">
            <strong>${escapeHtml(t("current_buy_side"))}</strong>
            <div class="muted-inline">${number(commodity.demandUnits)} ${escapeHtml(t("units_wanted"))} across ${number((commodity.buyBookEntries || []).length)} ${escapeHtml(t("buying"))} rows.</div>
          </div>
          <div class="chart-row">
            <strong>${escapeHtml(t("dock_stock"))}</strong>
            <div class="muted-inline">${number(commodity.storageUnits)} ${escapeHtml(t("in_storage"))}.</div>
          </div>
          <div class="chart-row">
            <strong>${escapeHtml(t("reference_price"))}</strong>
            <div class="muted-inline">${impact?.referenceUnitPrice ? number(impact.referenceUnitPrice) : "--"} · ${escapeHtml(t("liquidity_score"))} ${number(impact?.liquidityScore || 0)}</div>
          </div>
          <div class="chart-row">
            <strong>${escapeHtml(t("pressure_model"))}</strong>
            <div class="muted-inline">${escapeHtml(t("inventory_pressure"))} ${number(impact?.inventoryPressureBp || 0)} bp · ${escapeHtml(t("buy_pressure"))} ${number(impact?.buyPressureBp || 0)} bp · ${escapeHtml(t("volatility_word"))} ${number(impact?.volatilityBp || 0)} bp</div>
          </div>
          <div class="chart-row">
            <strong>${escapeHtml(t("terminal_status"))}</strong>
            <div class="muted-inline">${escapeHtml(detail.linkedDock ? t("dock_linked") : t("no_linked_dock"))}</div>
          </div>
        </div>
      </div>
    </div>
  `;
}

function tableSection(title, headers, rows, emptyMessage) {
  return `
    <div class="table-card">
      <div class="panel-head">
        <div>
          <p class="section-kicker">${escapeHtml(t("commodity_shelf"))}</p>
          <h3>${escapeHtml(title)}</h3>
        </div>
      </div>
      ${rows ? `
        <div class="table-wrap">
          <table>
            <thead><tr>${headers.map((header) => `<th>${escapeHtml(header)}</th>`).join("")}</tr></thead>
            <tbody>${rows}</tbody>
          </table>
        </div>
      ` : `<div class="empty-state">${escapeHtml(emptyMessage)}</div>`}
    </div>
  `;
}

function metricBox(label, value) {
  return `<div class="metric-box"><span class="label">${escapeHtml(label)}</span><span class="value">${escapeHtml(String(value))}</span></div>`;
}

function renderTopbarWallet() {
  if (!els.walletDock) {
    return;
  }
  const detail = state.detail;
  if (!detail || !state.session) {
    // 未选市场或未登录：钱包是玩家个人的，未登录不得显示任何钱包/余额面板
    els.walletDock.hidden = true;
    els.walletDock.innerHTML = "";
    return;
  }

  const canAct = !!state.session;
  const collapsed = !!state.walletCollapsed;
  const amountDisabled = canAct ? "" : "disabled";
  const treasuryEnabled = canAct && detail?.canTransferTreasury;
  const pendingEnabled = canAct && Number(detail?.pendingCredits || 0) > 0;
  const total = detail?.walletTotalBalance ?? detail?.walletBalance ?? 0;
  const selectedName = detail?.marketName || t("selected_market");
  els.walletDock.hidden = false;
  if (renderGuard.resetWalletOnce) {
    clearDirtyControls("wallet");
    renderGuard.resetWalletOnce = false;
  }
  morphInto(els.walletDock, `
    <div class="wallet-dock-card">
      <button id="wallet-dock-toggle" type="button" class="wallet-dock-toggle" aria-expanded="${collapsed ? "false" : "true"}">
        <span>
          <span class="hero-chip-label">${escapeHtml(t("wallet_balance"))}</span>
          <strong>${escapeHtml(number(total))}</strong>
        </span>
        <small>${escapeHtml(t("wallet_topbar_hint"))} · ${escapeHtml(selectedName)}</small>
        <span class="wallet-dock-caret" aria-hidden="true">${collapsed ? "+" : "-"}</span>
      </button>
      <div class="wallet-dock-panel" ${collapsed ? "hidden" : ""}>
        <div class="wallet-dock-metrics">
          ${metricBox(t("wallet_reserved"), number(detail?.walletReservedBalance || 0))}
          ${metricBox(t("cash_balance"), number(detail?.cashBalance || 0))}
          ${metricBox(t("treasury_balance"), number(detail?.treasuryBalance || 0))}
        </div>
        <label class="tiny-label" for="wallet-transfer-amount">${escapeHtml(t("wallet_transfer_amount"))}</label>
        <div class="wallet-dock-transfer-row">
          <input id="wallet-transfer-amount" type="number" min="1" step="1" placeholder="${escapeHtml(t("wallet_transfer_placeholder"))}" aria-label="${escapeHtml(t("wallet_transfer_amount"))}" ${amountDisabled}>
          <div class="wallet-transfer-buttons wallet-dock-buttons">
            <button type="button" data-wallet-action="CASH_TO_WALLET" ${canAct ? "" : "disabled"}>${escapeHtml(t("wallet_cash_to_wallet"))}</button>
            <button type="button" data-wallet-action="WALLET_TO_CASH" ${canAct ? "" : "disabled"}>${escapeHtml(t("wallet_wallet_to_cash"))}</button>
            <button type="button" class="secondary" data-wallet-action="WALLET_TO_TREASURY" ${canAct ? "" : "disabled"}>${escapeHtml(t("wallet_wallet_to_treasury"))}</button>
            <button type="button" class="secondary" data-wallet-action="TREASURY_TO_WALLET" ${treasuryEnabled ? "" : "disabled"}>${escapeHtml(t("wallet_treasury_to_wallet"))}</button>
            <button type="button" class="secondary wallet-claim-button" data-wallet-action="CLAIM_CREDITS_TO_WALLET" ${pendingEnabled ? "" : "disabled"}>${escapeHtml(t("wallet_claim_to_wallet"))}</button>
          </div>
        </div>
        <p class="wallet-dock-hint">${escapeHtml(t("wallet_transfer_hint"))}</p>
      </div>
    </div>
  `);

  const toggle = els.walletDock.querySelector("#wallet-dock-toggle");
  if (toggle) {
    toggle.setAttribute("aria-label", t(collapsed ? "wallet_expand" : "wallet_collapse"));
  }
}

function renderWalletTransferPanel(detail, canAct) {
  const amountDisabled = canAct ? "" : "disabled";
  const treasuryEnabled = canAct && detail?.canTransferTreasury;
  const pendingEnabled = canAct && Number(detail?.pendingCredits || 0) > 0;
  return `
    <div class="wallet-transfer-panel">
      <div class="wallet-transfer-head">
        <div>
          <strong>${escapeHtml(t("wallet_balance"))}</strong>
          <span>${escapeHtml(t("wallet_transfer_hint"))}</span>
        </div>
        <div class="wallet-transfer-total">${escapeHtml(number(detail?.walletTotalBalance ?? detail?.walletBalance ?? 0))}</div>
      </div>
      <div class="wallet-transfer-metrics">
        ${metricBox(t("wallet_reserved"), number(detail?.walletReservedBalance || 0))}
        ${metricBox(t("cash_balance"), number(detail?.cashBalance || 0))}
        ${metricBox(t("treasury_balance"), number(detail?.treasuryBalance || 0))}
      </div>
      <div class="wallet-transfer-controls">
        <label class="tiny-label" for="wallet-transfer-amount">${escapeHtml(t("wallet_transfer_amount"))}</label>
        <input id="wallet-transfer-amount" type="number" min="1" step="1" placeholder="${escapeHtml(t("wallet_transfer_placeholder"))}" aria-label="${escapeHtml(t("wallet_transfer_amount"))}" ${amountDisabled}>
        <div class="wallet-transfer-buttons">
          <button type="button" data-wallet-action="CASH_TO_WALLET" ${canAct ? "" : "disabled"}>${escapeHtml(t("wallet_cash_to_wallet"))}</button>
          <button type="button" data-wallet-action="WALLET_TO_CASH" ${canAct ? "" : "disabled"}>${escapeHtml(t("wallet_wallet_to_cash"))}</button>
          <button type="button" class="secondary" data-wallet-action="WALLET_TO_TREASURY" ${canAct ? "" : "disabled"}>${escapeHtml(t("wallet_wallet_to_treasury"))}</button>
          <button type="button" class="secondary" data-wallet-action="TREASURY_TO_WALLET" ${treasuryEnabled ? "" : "disabled"}>${escapeHtml(t("wallet_treasury_to_wallet"))}</button>
          <button type="button" class="secondary wallet-claim-button" data-wallet-action="CLAIM_CREDITS_TO_WALLET" ${pendingEnabled ? "" : "disabled"}>${escapeHtml(t("wallet_claim_to_wallet"))}</button>
        </div>
      </div>
    </div>
  `;
}

function routeButton(key, label, options = null) {
  if (options && options.aggregate) {
    return `<button type="button" class="tab-button ${state.aggregatedDetailTab === key ? "active" : ""}" data-aggregate-tab="${key}">${escapeHtml(label)}</button>`;
  }
  return `<button type="button" class="tab-button ${state.activeProductTab === key ? "active" : ""}" data-route="${key}">${escapeHtml(label)}</button>`;
}

function renderChartTimeframeButton(key) {
  return `<button type="button" class="chart-toggle ${state.activeChartTimeframe === key ? "active" : ""}" data-chart-timeframe="${key}">${escapeHtml(key.toUpperCase())}</button>`;
}

function renderIndicatorToggle(key, label, active) {
  return `<button type="button" class="chart-toggle ${active ? "active" : ""}" data-chart-indicator="${key}">${escapeHtml(label)}</button>`;
}

function primaryChartSeries(commodity) {
  return commodity?.candles?.["1h"] || commodity?.candles?.["1d"] || commodity?.candles?.["1w"] || commodity?.priceChart || null;
}

function primaryChartSeriesForTimeframe(commodity, timeframe) {
  return commodity?.candles?.[timeframe] || primaryChartSeries(commodity);
}

function normalizedChartPoints(chart) {
  const points = chart?.points || [];
  return points.map((point) => ({
    bucketAt: point.bucketAt,
    openUnitPrice: point.openUnitPrice ?? point.averageUnitPrice ?? point.closeUnitPrice ?? 0,
    averageUnitPrice: point.averageUnitPrice ?? point.closeUnitPrice ?? 0,
    minUnitPrice: point.minUnitPrice ?? point.lowUnitPrice ?? point.closeUnitPrice ?? 0,
    maxUnitPrice: point.maxUnitPrice ?? point.highUnitPrice ?? point.closeUnitPrice ?? 0,
    closeUnitPrice: point.closeUnitPrice ?? point.averageUnitPrice ?? 0,
    volume: point.volume ?? 0,
    tradeCount: point.tradeCount ?? 0
  }));
}

function latestChartPoint(chart) {
  const points = normalizedChartPoints(chart);
  return points.length ? points[points.length - 1] : null;
}

function chartSummary(chart) {
  return chartSummaryFromPoints(normalizedChartPoints(chart));
}

function chartSummaryFromPoints(points) {
  if (!points.length) {
    return {
      low: null,
      high: null,
      volume: 0,
      tradeCount: 0
    };
  }
  return points.reduce((summary, point) => ({
    low: summary.low == null ? Number(point.minUnitPrice || 0) : Math.min(summary.low, Number(point.minUnitPrice || 0)),
    high: summary.high == null ? Number(point.maxUnitPrice || 0) : Math.max(summary.high, Number(point.maxUnitPrice || 0)),
    volume: summary.volume + (Number(point.volume) || 0),
    tradeCount: summary.tradeCount + (Number(point.tradeCount) || 0)
  }), { low: null, high: null, volume: 0, tradeCount: 0 });
}

function derivedChartStats(points) {
  if (!points?.length) {
    return { changeRatio: null, volatility: null, maxDrawdown: null, inceptionReturn: null };
  }
  const closes = points.map((point) => Number(point.closeUnitPrice ?? point.averageUnitPrice ?? 0)).filter((value) => Number.isFinite(value) && value > 0);
  if (!closes.length) {
    return { changeRatio: null, volatility: null, maxDrawdown: null, inceptionReturn: null };
  }
  const first = closes[0];
  const last = closes[closes.length - 1];
  const changeRatio = first > 0 ? (last - first) / first : null;
  const inceptionReturn = changeRatio;
  const mean = closes.reduce((sum, value) => sum + value, 0) / closes.length;
  const variance = closes.reduce((sum, value) => sum + Math.pow(value - mean, 2), 0) / Math.max(1, closes.length);
  const volatility = mean > 0 ? Math.sqrt(variance) / mean : null;
  let peak = closes[0];
  let maxDrawdown = 0;
  for (const value of closes) {
    peak = Math.max(peak, value);
    if (peak > 0) {
      maxDrawdown = Math.max(maxDrawdown, (peak - value) / peak);
    }
  }
  return { changeRatio, volatility, maxDrawdown, inceptionReturn };
}

function derivedSeriesStats(series) {
  const values = (series?.points || [])
    .map((point) => Number(point.value || 0))
    .filter((value) => Number.isFinite(value) && value > 0);
  if (!values.length) {
    return { changeRatio: null, volatility: null, maxDrawdown: null, inceptionReturn: null };
  }
  const first = values[0];
  const last = values[values.length - 1];
  const changeRatio = first > 0 ? (last - first) / first : null;
  const mean = values.reduce((sum, value) => sum + value, 0) / values.length;
  const variance = values.reduce((sum, value) => sum + Math.pow(value - mean, 2), 0) / Math.max(1, values.length);
  const volatility = mean > 0 ? Math.sqrt(variance) / mean : null;
  let peak = values[0];
  let maxDrawdown = 0;
  for (const value of values) {
    peak = Math.max(peak, value);
    if (peak > 0) {
      maxDrawdown = Math.max(maxDrawdown, (peak - value) / peak);
    }
  }
  return { changeRatio, volatility, maxDrawdown, inceptionReturn: changeRatio };
}

function analyticsSeries(detail, scopeType, scopeKey) {
  return (detail?.analyticsSeries || []).find((series) => series.scopeType === scopeType && series.scopeKey === scopeKey) || null;
}

function findAnalyticsValueAt(points, bucketAt) {
  if (!Array.isArray(points) || !points.length) {
    return null;
  }
  let matched = null;
  for (const point of points) {
    if (Number(point.bucketAt || 0) <= Number(bucketAt || 0)) {
      matched = Number(point.value || 0);
      continue;
    }
    break;
  }
  return matched;
}

function adjustChartPointsForInflation(points, cpiSeries) {
  const cpiPoints = cpiSeries?.points || [];
  if (!points?.length || !cpiPoints.length) {
    return points || [];
  }
  return points.map((point) => {
    const cpi = findAnalyticsValueAt(cpiPoints, point.bucketAt);
    if (!cpi || cpi <= 0) {
      return point;
    }
    const factor = cpi / 100;
    return {
      ...point,
      openUnitPrice: Math.round(Number(point.openUnitPrice || 0) / factor),
      averageUnitPrice: Math.round(Number(point.averageUnitPrice || point.closeUnitPrice || 0) / factor),
      minUnitPrice: Math.round(Number(point.minUnitPrice || 0) / factor),
      maxUnitPrice: Math.round(Number(point.maxUnitPrice || 0) / factor),
      closeUnitPrice: Math.round(Number(point.closeUnitPrice || 0) / factor)
    };
  });
}

function firstListingCategory(commodity) {
  return commodity?.listings?.find((entry) => entry.category)?.category || "";
}

function renderIndexTapeCard(series, label) {
  const points = series?.points || [];
  const last = points.length ? points[points.length - 1] : null;
  const prev = points.length > 1 ? points[points.length - 2] : null;
  const delta = last && prev ? Number(last.value || 0) - Number(prev.value || 0) : 0;
  const sign = delta > 0 ? "+" : "";
  return `
    <div class="tape-card neutral">
      <span>${escapeHtml(label)}</span>
      <strong>${last ? number(last.value) : "--"}</strong>
      <small>${sign}${number(delta)}</small>
    </div>
  `;
}

function renderIndexRow(series) {
  const points = series?.points || [];
  const last = points.length ? points[points.length - 1] : null;
  const prev = points.length > 1 ? points[points.length - 2] : null;
  const delta = last && prev ? Number(last.value || 0) - Number(prev.value || 0) : 0;
  const sign = delta > 0 ? "+" : "";
  return `
    <div class="chart-row">
      <strong>${escapeHtml(series.displayName || series.scopeKey || "-")}</strong>
      <div class="muted-inline">${last ? number(last.value) : "--"} · ${sign}${number(delta)} · ${t("trades_24h")} ${number(last?.tradeCount || 0)}</div>
    </div>
  `;
}

function renderCandles(points) {
  const safe = (points || []).map((point) => ({
    bucketAt: point.bucketAt,
    open: Number(point.openUnitPrice ?? point.averageUnitPrice ?? 0),
    high: Number(point.maxUnitPrice ?? 0),
    low: Number(point.minUnitPrice ?? 0),
    close: Number(point.closeUnitPrice ?? point.averageUnitPrice ?? 0)
  }));
  const highBound = safe.reduce((max, point) => Math.max(max, point.high), 0);
  const lowBound = safe.reduce((min, point) => Math.min(min, point.low), Number.POSITIVE_INFINITY);
  const span = Math.max(1, highBound - (Number.isFinite(lowBound) ? lowBound : 0));

  return safe.map((point) => {
    const wickTop = ((highBound - point.high) / span) * 100;
    const wickHeight = Math.max(2, ((point.high - point.low) / span) * 100);
    const bodyTop = ((highBound - Math.max(point.open, point.close)) / span) * 100;
    const bodyHeight = Math.max(4, (Math.abs(point.open - point.close) / span) * 100);
    const direction = point.close >= point.open ? "up" : "down";
    return `
      <div class="candle ${direction}" title="${escapeHtml(formatTime(point.bucketAt))} O:${number(point.open)} H:${number(point.high)} L:${number(point.low)} C:${number(point.close)}">
        <span class="candle-wick" style="top:${wickTop}%;height:${wickHeight}%"></span>
        <span class="candle-body" style="top:${bodyTop}%;height:${bodyHeight}%"></span>
      </div>
    `;
  }).join("");
}

function renderFallbackKlineChart(container, points) {
  if (!container) {
    return;
  }
  if (!points?.length) {
    container.innerHTML = `<div class="chart-fallback">${escapeHtml(t("no_chart_buckets"))}</div>`;
    return;
  }
  const safe = points.map((point) => ({
    bucketAt: point.bucketAt,
    open: Number(point.openUnitPrice ?? point.averageUnitPrice ?? 0),
    high: Number(point.maxUnitPrice ?? point.highUnitPrice ?? point.closeUnitPrice ?? 0),
    low: Number(point.minUnitPrice ?? point.lowUnitPrice ?? point.closeUnitPrice ?? 0),
    close: Number(point.closeUnitPrice ?? point.averageUnitPrice ?? 0),
    volume: Number(point.volume ?? 0)
  }));
  const maxVolume = safe.reduce((max, point) => Math.max(max, point.volume), 0);
  container.innerHTML = `
    <div class="fallback-kline">
      <div class="fallback-kline-grid">
        <div class="fallback-kline-price">
          ${renderCandles(points)}
        </div>
        <div class="fallback-kline-volume">
          ${safe.map((point) => {
            const height = maxVolume > 0 ? Math.max(6, Math.round((point.volume / maxVolume) * 100)) : 6;
            const direction = point.close >= point.open ? "up" : "down";
            return `<span class="fallback-volume-bar ${direction}" style="height:${height}%"></span>`;
          }).join("")}
        </div>
      </div>
      <div class="fallback-kline-axis">
        <span>${escapeHtml(shortTime(safe[0].bucketAt))}</span>
        <span>${escapeHtml(shortTime(safe[safe.length - 1].bucketAt))}</span>
      </div>
    </div>
  `;
}

function setChartFailure(reason, detail) {
  chartState.lastFailure = { reason, detail: detail || "" };
  if (detail) {
    console.warn(`[marketweb:chart] ${reason}`, detail);
  } else {
    console.warn(`[marketweb:chart] ${reason}`);
  }
}

function clearChartFailure() {
  chartState.lastFailure = null;
}

function renderChartFallbackMessage(container, messageKey, extraDetail = "") {
  if (!container) {
    return;
  }
  const detail = extraDetail ? `<small>${escapeHtml(extraDetail)}</small>` : "";
  container.innerHTML = `<div class="chart-fallback"><div class="chart-fallback-copy"><span>${escapeHtml(t(messageKey))}</span>${detail}</div></div>`;
}

function cssThemeValue(name, fallback) {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}

function currentChartPalette() {
  return {
    background: cssThemeValue("--panel", "#fbfdff"),
    text: cssThemeValue("--muted-strong", "#526070"),
    grid: cssThemeValue("--chart-grid", "rgba(125, 139, 161, 0.14)"),
    border: cssThemeValue("--chart-border", "rgba(125, 139, 161, 0.22)"),
    up: cssThemeValue("--trade-up", "#0b8a5a"),
    down: cssThemeValue("--trade-down", "#cc3a32"),
    volume: cssThemeValue("--chart-volume", "rgba(31, 93, 143, 0.28)"),
    maShort: cssThemeValue("--chart-ma-short", "#c98a00"),
    maLong: cssThemeValue("--chart-ma-long", "#1f5d8f")
  };
}

function destroyLightweightChart() {
  if (chartState.resizeObserver) {
    chartState.resizeObserver.disconnect();
    chartState.resizeObserver = null;
  }
  if (chartState.marketResizeObserver) {
    chartState.marketResizeObserver.disconnect();
    chartState.marketResizeObserver = null;
  }
  if (chartState.categoryResizeObserver) {
    chartState.categoryResizeObserver.disconnect();
    chartState.categoryResizeObserver = null;
  }
  if (chartState.cpiResizeObserver) {
    chartState.cpiResizeObserver.disconnect();
    chartState.cpiResizeObserver = null;
  }
  if (chartState.loansResizeObserver) {
    chartState.loansResizeObserver.disconnect();
    chartState.loansResizeObserver = null;
  }
  if (chartState.chart && chartState.viewKey) {
    try {
      chartState.savedRange = { key: chartState.viewKey, range: chartState.chart.timeScale().getVisibleLogicalRange() };
    } catch (_) {
      chartState.savedRange = null;
    }
  }
  if (chartState.chart) {
    chartState.chart.remove();
  }
  if (chartState.marketIndexChart) {
    chartState.marketIndexChart.remove();
  }
  if (chartState.categoryIndexChart) {
    chartState.categoryIndexChart.remove();
  }
  if (chartState.cpiChart) {
    chartState.cpiChart.remove();
  }
  if (chartState.loansChart) {
    chartState.loansChart.remove();
  }
  chartState.chart = null;
  chartState.candleSeries = null;
  chartState.volumeSeries = null;
  chartState.maShortSeries = null;
  chartState.maLongSeries = null;
  chartState.marketIndexChart = null;
  chartState.categoryIndexChart = null;
  chartState.cpiChart = null;
  chartState.loansChart = null;
  chartState.lastFailure = null;
  chartState.signature = "";
  chartState.anchor = null;
}

function seriesFingerprint(series) {
  const points = series?.points || [];
  const last = points[points.length - 1] || {};
  return [points.length, last.bucketAt ?? null, last.closeUnitPrice ?? last.averageUnitPrice ?? last.value ?? null, last.volume ?? null];
}

// 图表签名:商品、周期、指标、主题和最新数据点。签名不变就保留现有图表,重绘不会闪烁、缩放不会重置。
function chartSignature(activeTab, aggregate, detail, commodity) {
  const parts = [activeTab, aggregate ? "all" : state.selectedMarketId, commodity?.commodityKey || "", currentTheme(), !!chartState.libraryFailed];
  if (activeTab === "index") {
    parts.push(
      seriesFingerprint(analyticsSeries(detail, "MARKET_INDEX", "global")),
      seriesFingerprint(analyticsSeries(detail, "CATEGORY_INDEX", firstListingCategory(commodity) || "")),
      seriesFingerprint(analyticsSeries(detail, "MACRO_INDEX", "cpi")),
      seriesFingerprint(analyticsSeries(detail, "MACRO_INDEX", "outstanding_loans"))
    );
  } else {
    parts.push(
      state.activeChartTimeframe,
      state.chartIndicators,
      state.chartFlags,
      seriesFingerprint(commodity ? primaryChartSeriesForTimeframe(commodity, state.activeChartTimeframe) : null),
      state.chartFlags.inflationAdjusted ? seriesFingerprint(analyticsSeries(detail, "MACRO_INDEX", "cpi")) : null
    );
  }
  return JSON.stringify(parts);
}

function showChartLoading(activeTab) {
  const selectors = activeTab === "index"
    ? ["#market-index-chart", "#category-index-chart", "#cpi-chart", "#loans-chart"]
    : ["#lw-chart"];
  selectors.forEach((selector) => {
    const container = document.querySelector(selector);
    if (container && !container.childElementCount) {
      renderChartFallbackMessage(container, "chart_loading");
    }
  });
}

function hydrateLightweightChart() {
  if (!state.settings.showPriceCharts) {
    destroyLightweightChart();
    return;
  }
  // 聚合详情上下文:用 aggregatedDetail + 选中商品 + 子 tab,与终端隔离。
  const aggregate = state.activeProductTab === "all" && !!state.aggregatedSelectedCommodityKey;
  const detail = aggregate ? state.aggregatedDetail : state.detail;
  const activeTab = aggregate ? state.aggregatedDetailTab : state.activeProductTab;
  const anchor = detail && (activeTab === "chart" || activeTab === "index")
    ? document.querySelector(activeTab === "index" ? "#market-index-chart" : "#lw-chart")
    : null;
  if (!anchor) {
    destroyLightweightChart();
    return;
  }
  const catalog = buildCommodityCatalog(detail);
  const commodity = aggregate
    ? findCommodityByKey(catalog, state.aggregatedSelectedCommodityKey)
    : getSelectedCommodity(filterCatalog(catalog), catalog);
  // 图表库按需加载:第一次打开图表时才下载,期间显示"加载中";加载失败则用内置渲染。
  if (typeof LightweightCharts === "undefined" && !chartState.libraryFailed) {
    showChartLoading(activeTab);
    ensureChartLibrary().then(
      () => hydrateLightweightChart(),
      () => {
        chartState.libraryFailed = true;
        hydrateLightweightChart();
      }
    );
    return;
  }
  const signature = chartSignature(activeTab, aggregate, detail, commodity);
  if (signature === chartState.signature && chartState.anchor === anchor && anchor.isConnected) {
    return;
  }
  destroyLightweightChart();
  chartState.signature = signature;
  chartState.anchor = anchor;
  if (activeTab === "index") {
    hydrateMacroCharts(detail, commodity);
    return;
  }
  const container = anchor;
  if (!commodity) {
    setChartFailure("no-selection", "No selected commodity while hydrating chart.");
    renderChartFallbackMessage(container, "chart_no_selection");
    return;
  }
  const series = primaryChartSeriesForTimeframe(commodity, state.activeChartTimeframe);
  const points = normalizedChartPoints(series);
  const cpiSeries = analyticsSeries(detail, "MACRO_INDEX", "cpi");
  if (!points.length) {
    setChartFailure("no-buckets", `${commodity.commodityKey} has no chart buckets for timeframe ${state.activeChartTimeframe}.`);
    renderChartFallbackMessage(container, "no_chart_buckets", `${commodity.displayName} · ${state.activeChartTimeframe.toUpperCase()}`);
    return;
  }
  if (typeof LightweightCharts === "undefined") {
    setChartFailure("library-missing", "window.LightweightCharts is undefined.");
    renderChartFallbackMessage(container, "chart_library_missing", `${commodity.displayName} · ${state.activeChartTimeframe.toUpperCase()}`);
    renderFallbackKlineChart(container, points);
    return;
  }
  clearChartFailure();

  container.replaceChildren();
  let chart;
  const palette = currentChartPalette();
  try {
    chart = LightweightCharts.createChart(container, {
      autoSize: true,
      layout: {
        background: { color: palette.background },
        textColor: palette.text
      },
      grid: {
        vertLines: { color: palette.grid },
        horzLines: { color: palette.grid }
      },
      rightPriceScale: {
        borderColor: palette.border
      },
      timeScale: {
        borderColor: palette.border,
        timeVisible: true,
        secondsVisible: false
      },
      crosshair: {
        mode: LightweightCharts.CrosshairMode.Normal
      }
    });
  } catch (error) {
    console.warn("Failed to initialize lightweight chart, using fallback renderer.", error);
    setChartFailure("init-failed", error?.stack || error?.message || String(error));
    renderChartFallbackMessage(container, "chart_init_failed", `${commodity.displayName} · ${state.activeChartTimeframe.toUpperCase()}`);
    renderFallbackKlineChart(container, points);
    hydrateMacroCharts(detail, commodity);
    return;
  }
  chartState.chart = chart;

  chartState.candleSeries = chart.addCandlestickSeries({
    upColor: palette.up,
    downColor: palette.down,
    borderVisible: false,
    wickUpColor: palette.up,
    wickDownColor: palette.down
  });
  chartState.volumeSeries = chart.addHistogramSeries({
    priceFormat: { type: "volume" },
    priceScaleId: "",
    color: palette.volume
  });
  chart.priceScale("").applyOptions({
    scaleMargins: { top: 0.76, bottom: 0 }
  });
  chartState.maShortSeries = chart.addLineSeries({
    color: palette.maShort,
    lineWidth: 2,
    priceLineVisible: false,
    lastValueVisible: false
  });
  chartState.maLongSeries = chart.addLineSeries({
    color: palette.maLong,
    lineWidth: 2,
    priceLineVisible: false,
    lastValueVisible: false
  });

  const candleData = points.map((point) => ({
    time: Math.floor(Number(point.bucketAt) / 1000),
    open: Number(point.openUnitPrice || 0),
    high: Number(point.maxUnitPrice || 0),
    low: Number(point.minUnitPrice || 0),
    close: Number(point.closeUnitPrice || 0)
  }));
  const priceData = state.chartFlags.inflationAdjusted
    ? applyInflationAdjustment(candleData, cpiSeries)
    : candleData;
  const hoverPoints = state.chartFlags.inflationAdjusted
    ? adjustChartPointsForInflation(points, cpiSeries)
    : points;
  const volumeData = points.map((point) => ({
    time: Math.floor(Number(point.bucketAt) / 1000),
    value: Number(point.volume || 0),
    color: Number(point.closeUnitPrice || 0) >= Number(point.openUnitPrice || 0)
      ? "rgba(34, 197, 94, 0.35)"
      : "rgba(239, 68, 68, 0.35)"
  }));
  chart.applyOptions({
    rightPriceScale: {
      borderColor: palette.border,
      mode: state.chartFlags.logScale ? LightweightCharts.PriceScaleMode.Logarithmic : LightweightCharts.PriceScaleMode.Normal
    }
  });
  chartState.candleSeries.setData(priceData);
  chartState.volumeSeries.setData(state.chartIndicators.volume ? volumeData : []);
  chartState.maShortSeries.setData(state.chartIndicators.ma5 ? movingAverageData(priceData, 5) : []);
  chartState.maLongSeries.setData(state.chartIndicators.ma20 ? movingAverageData(priceData, 20) : []);
  // 同一商品、同一周期重建图表(切换指标、主题、后台刷新)时保留缩放与平移位置。
  const chartViewKey = [
    aggregate ? "all" : state.selectedMarketId,
    commodity.commodityKey,
    state.activeChartTimeframe,
    state.chartFlags.inflationAdjusted ? "real" : "nominal"
  ].join("|");
  const savedRange = chartState.savedRange;
  if (savedRange && savedRange.key === chartViewKey && savedRange.range) {
    chart.timeScale().setVisibleLogicalRange(savedRange.range);
  } else {
    chart.timeScale().fitContent();
  }
  chartState.viewKey = chartViewKey;
  bindChartCrosshair(chart, hoverPoints);

  chartState.resizeObserver = new ResizeObserver((entries) => {
    const rect = entries?.[0]?.contentRect;
    if (!rect || !chartState.chart) {
      return;
    }
    chartState.chart.resize(rect.width, rect.height);
  });
  chartState.resizeObserver.observe(container);
}

function bindChartCrosshair(chart, points) {
  const detailNode = document.querySelector("#chart-hover-details");
  if (!detailNode || !chart) {
    return;
  }
  chart.subscribeCrosshairMove((param) => {
    if (!param || !param.time || !param.seriesData) {
      detailNode.textContent = t("chart_hover_empty");
      return;
    }
    const bucketTime = Number(param.time) * 1000;
    const point = points.find((entry) => Math.floor(Number(entry.bucketAt) / 1000) === Number(param.time));
    if (!point) {
      detailNode.textContent = t("chart_hover_empty");
      return;
    }
    detailNode.textContent = `${shortTime(bucketTime)}  O ${number(point.openUnitPrice)}  H ${number(point.maxUnitPrice)}  L ${number(point.minUnitPrice)}  C ${number(point.closeUnitPrice)}  V ${number(point.volume)}  T ${number(point.tradeCount)}`;
  });
}

function movingAverageData(points, period) {
  const result = [];
  for (let i = 0; i < points.length; i++) {
    const start = Math.max(0, i - period + 1);
    const slice = points.slice(start, i + 1);
    const avg = slice.reduce((sum, point) => sum + Number(point.close || 0), 0) / Math.max(1, slice.length);
    result.push({ time: points[i].time, value: avg });
  }
  return result;
}

function applyInflationAdjustment(candleData, cpiSeries) {
  const cpiPoints = cpiSeries?.points || [];
  if (!candleData?.length || !cpiPoints.length) {
    return candleData;
  }
  return candleData.map((point) => {
    const cpi = findAnalyticsValueAt(cpiPoints, Number(point.time) * 1000);
    if (!cpi || cpi <= 0) {
      return point;
    }
    const factor = cpi / 100;
    return {
      time: point.time,
      open: Number(point.open || 0) / factor,
      high: Number(point.high || 0) / factor,
      low: Number(point.low || 0) / factor,
      close: Number(point.close || 0) / factor
    };
  });
}

function hydrateMacroCharts(detail, commodity) {
  const marketIndex = analyticsSeries(detail, "MARKET_INDEX", "global");
  const categoryIndex = analyticsSeries(detail, "CATEGORY_INDEX", firstListingCategory(commodity) || "");
  const cpiSeries = analyticsSeries(detail, "MACRO_INDEX", "cpi");
  const loansSeries = analyticsSeries(detail, "MACRO_INDEX", "outstanding_loans");
  chartState.marketIndexChart = createMacroChart("#market-index-chart", marketIndex, cssThemeValue("--chart-index-market", "#1f5d8f"));
  chartState.categoryIndexChart = createMacroChart("#category-index-chart", categoryIndex, cssThemeValue("--chart-index-category", "#0c8599"));
  chartState.cpiChart = createMacroChart("#cpi-chart", cpiSeries, cssThemeValue("--chart-index-cpi", "#8b3fc0"));
  chartState.loansChart = createMacroChart("#loans-chart", loansSeries, cssThemeValue("--chart-index-loans", "#c4561a"));
}

function renderMacroChartFallback(container, series, color) {
  if (!container) {
    return;
  }
  const points = (series?.points || []).slice(-8);
  if (!points.length) {
    container.innerHTML = `<div class="chart-fallback"><div class="chart-fallback-copy"><span>${escapeHtml(t("no_chart_buckets"))}</span></div></div>`;
    return;
  }
  const maxValue = Math.max(1, ...points.map((point) => Number(point.value || 0)));
  container.innerHTML = `
    <div class="chart-fallback">
      <div class="chart-fallback-copy" style="width:100%;">
        ${points.map((point) => {
          const value = Number(point.value || 0);
          const ratio = Math.max(0.06, value / maxValue);
          return `
            <div class="chart-bar-row">
              <span>${escapeHtml(shortTime(point.bucketAt))}</span>
              <div class="chart-bar"><span style="width:${Math.round(ratio * 100)}%; background:${escapeHtml(color)};"></span></div>
              <strong>${escapeHtml(number(value))}</strong>
            </div>
          `;
        }).join("")}
      </div>
    </div>
  `;
}

function createMacroChart(selector, series, color) {
  const container = document.querySelector(selector);
  if (!container || !series?.points?.length) {
    return null;
  }
  if (typeof LightweightCharts === "undefined") {
    renderMacroChartFallback(container, series, color);
    return null;
  }
  const palette = currentChartPalette();
  container.replaceChildren();
  const chart = LightweightCharts.createChart(container, {
    autoSize: true,
    layout: {
      background: { color: palette.background },
      textColor: palette.text
    },
    grid: {
      vertLines: { visible: false },
      horzLines: { color: palette.grid }
    },
    leftPriceScale: { visible: false },
    rightPriceScale: { borderColor: palette.border },
    timeScale: { borderColor: palette.border, timeVisible: true, secondsVisible: false }
  });
  const line = chart.addAreaSeries({
    lineColor: color,
    topColor: `${color}33`,
    bottomColor: `${color}08`,
    lineWidth: 2
  });
  line.setData((series.points || []).map((point) => ({
    time: Math.floor(Number(point.bucketAt) / 1000),
    value: Number(point.value || 0)
  })));
  chart.timeScale().fitContent();
  const observer = new ResizeObserver((entries) => {
    const rect = entries?.[0]?.contentRect;
    if (rect && chart) {
      chart.resize(rect.width, rect.height);
    }
  });
  observer.observe(container);
  if (selector === "#market-index-chart") {
    chartState.marketResizeObserver = observer;
  } else if (selector === "#category-index-chart") {
    chartState.categoryResizeObserver = observer;
  } else if (selector === "#cpi-chart") {
    chartState.cpiResizeObserver = observer;
  } else if (selector === "#loans-chart") {
    chartState.loansResizeObserver = observer;
  }
  return chart;
}

function estimateBidText(referencePrice, minPriceBp, maxPriceBp) {
  if (!Number.isFinite(Number(referencePrice)) || Number(referencePrice) <= 0) {
    return `${number(minPriceBp)} ${t("to_word")} ${number(maxPriceBp)} bp`;
  }
  const min = Math.max(1, Math.round(Number(referencePrice) * (1 + (Number(minPriceBp) || 0) / 10000)));
  const max = Math.max(1, Math.round(Number(referencePrice) * (1 + (Number(maxPriceBp) || 0) / 10000)));
  return `${number(min)} - ${number(max)}`;
}

function estimateBidSentence(referencePrice, minPriceBp, maxPriceBp) {
  if (!Number.isFinite(Number(referencePrice)) || Number(referencePrice) <= 0) {
    return t("waiting_reference");
  }
  return t("around_current_ask", { range: estimateBidText(referencePrice, minPriceBp, maxPriceBp) });
}

function clampNumber(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function buyOrderReferencePrice(commodity) {
  const catalogReference = commodityFilterPrice(commodity);
  return Math.max(1, Math.round(
    Number(catalogReference)
    || Number(commodity?.suggestedUnitPrice)
    || Number(commodity?.referencePrice)
    || 1
  ));
}

function buyOrderPriceToBp(unitPrice, referencePrice) {
  const price = Math.max(1, Number(unitPrice) || 1);
  const reference = Math.max(1, Number(referencePrice) || 1);
  return clampNumber(Math.round(((price / reference) - 1) * 10000), -5000, 5000);
}

function buyOrderBpPreviewText(unitPrice, referencePrice) {
  const bp = buyOrderPriceToBp(unitPrice, referencePrice);
  return `${t("buy_order_bp_preview")} ${number(bp)} bp · ${t("reference_price")} ${number(referencePrice)}`;
}

function valueOf(selector) {
  return document.querySelector(selector)?.value?.trim() || "";
}

function numberValue(selector, fallback) {
  const value = Number(valueOf(selector));
  return Number.isFinite(value) ? value : fallback;
}

function selectedCreateListingStorageEntry() {
  const detail = state.detail;
  if (!detail || !Array.isArray(detail.storageEntries)) {
    return null;
  }
  const storageIndex = numberValue("#create-listing-storage", -1);
  return detail.storageEntries.find((entry) => Number(entry?.index) === Number(storageIndex)) || null;
}

function selectedCreateListingPrice() {
  const entry = selectedCreateListingStorageEntry();
  const fallback = Number(entry?.suggestedUnitPrice) || 1;
  return Math.max(1, numberValue("#create-listing-price", fallback));
}

function selectedCreateListingDerivedBp() {
  const entry = selectedCreateListingStorageEntry();
  if (!isPriceConstrained(entry)) {
    return 0;
  }
  const referencePrice = Math.max(1, Number(entry?.suggestedUnitPrice) || 1);
  const requestedPrice = selectedCreateListingPrice();
  return Math.round(((requestedPrice / referencePrice) - 1) * 10000);
}

function selectedCreateListingPriceValid() {
  const entry = selectedCreateListingStorageEntry();
  if (!entry) {
    return false;
  }
  const requestedPrice = selectedCreateListingPrice();
  if (!isPriceConstrained(entry)) {
    return requestedPrice > 0;
  }
  const minAllowed = Math.max(1, Number(entry.minAllowedUnitPrice) || 1);
  const maxAllowed = Math.max(minAllowed, Number(entry.maxAllowedUnitPrice) || minAllowed);
  const derivedBp = selectedCreateListingDerivedBp();
  return requestedPrice >= minAllowed && requestedPrice <= maxAllowed && derivedBp >= -1000 && derivedBp <= 1000;
}

function updateCreateListingFormState() {
  const entry = selectedCreateListingStorageEntry();
  const preview = document.querySelector("#create-listing-price-preview");
  const button = document.querySelector("#create-listing-button");
  if (!preview && !button) {
    return;
  }

  const canCreate = button?.getAttribute("data-can-create") === "true";
  const valid = !!entry && selectedCreateListingPriceValid();
  if (button) {
    button.disabled = !canCreate || !valid;
  }

  if (!preview) {
    return;
  }
  if (!entry) {
    preview.textContent = t("no_storage_match");
    preview.classList.remove("error");
    return;
  }

  const requestedPrice = selectedCreateListingPrice();
  const derivedBp = selectedCreateListingDerivedBp();
  const minAllowed = Math.max(1, Number(entry.minAllowedUnitPrice) || 1);
  const maxAllowed = Math.max(minAllowed, Number(entry.maxAllowedUnitPrice) || minAllowed);
  if (!isPriceConstrained(entry)) {
    if (!valid) {
      preview.textContent = `${t("listing_price_invalid")} ${t("listing_price_free")}`;
      preview.classList.add("error");
      return;
    }
    preview.textContent = `${t("listing_price_preview")} ${number(requestedPrice)} | ${t("listing_price_free")}`;
    preview.classList.remove("error");
    return;
  }
  if (!valid) {
    preview.textContent = `${t("listing_price_invalid")} ${number(minAllowed)} - ${number(maxAllowed)} | ${t("suggested_word")} ${number(entry.suggestedUnitPrice)}`;
    preview.classList.add("error");
    return;
  }

  preview.textContent = `${t("listing_price_preview")} ${number(requestedPrice)} | ${t("listing_bp_preview")} ${number(derivedBp)} bp | ${t("listing_price_range")} ${number(minAllowed)} - ${number(maxAllowed)}`;
  preview.classList.remove("error");
}

function renderBuyOrderItemPreview(item) {
  const commodityKey = normalizeCommodityKey(item?.commodityKey);
  const displayName = item?.displayName || commodityKey || "-";
  const category = item?.category ? categoryLabel(item.category) : t("category_other");
  const suggested = Number(item?.suggestedUnitPrice ?? item?.referencePrice ?? item?.bestSell ?? 0);
  return `
    <div id="buy-order-preview" class="buy-order-preview ready" data-item-valid="${commodityKey ? "true" : "false"}" data-resolved-commodity-key="${escapeHtml(commodityKey)}">
      ${renderCommodityIcon(commodityKey, displayName)}
      <div class="buy-order-preview-copy">
        <span class="tiny-label">${escapeHtml(t("buy_order_item_preview"))}</span>
        <strong>${escapeHtml(displayName)}</strong>
        <span class="muted-inline">${escapeHtml(commodityKey || "-")}</span>
        <span class="muted-inline">${escapeHtml(category)}${suggested > 0 ? ` | ${escapeHtml(t("suggested_word"))} ${number(suggested)}` : ""}</span>
      </div>
      <span class="pill success">${escapeHtml(t("buy_order_item_valid"))}</span>
    </div>
  `;
}

function renderDemandOnlyPage(commodity, canAct, options = null) {
  const ownRows = commodity.myBuyOrders || [];
  const buyOrderReference = buyOrderReferencePrice(commodity);
  const buyOrderDefaultPrice = buyOrderReference;

  return `
    <div class="demand-only-grid">
      ${tableSection(
        t("my_buy_orders"),
        [t("quantity"), t("price_band"), t("status"), t("actions")],
        ownRows.map((entry) => `
          <tr>
            <td>${number(entry.quantity)}</td>
            <td>${number(entry.minPriceBp)} ${escapeHtml(t("to_word"))} ${number(entry.maxPriceBp)} bp</td>
            <td>${escapeHtml(entry.status || "-")}</td>
            <td><button type="button" class="danger" data-cancel-buy-order="${escapeHtml(entry.orderId || "")}" ${canAct ? "" : "disabled"}>${escapeHtml(t("cancel"))}</button></td>
          </tr>
        `).join(""),
        t("no_my_buy_rows")
      )}

      <div class="action-box">
        <div class="panel-head">
          <div>
            <p class="section-kicker">${escapeHtml(t("create_buy_order"))}</p>
            <h3>${escapeHtml(t("buying_demand"))}</h3>
          </div>
          <span class="pill">${number(ownRows.length)} ${escapeHtml(t("my_buy_orders"))}</span>
        </div>
        <div class="stack">
          <label class="tiny-label" for="buy-order-key">${escapeHtml(t("buy_order_item_id"))}</label>
          <input id="buy-order-key" type="text" value="${escapeHtml(commodity.commodityKey)}" placeholder="minecraft:oak_log" aria-describedby="buy-order-preview buy-order-item-help">
          <div id="buy-order-item-help" class="summary-note">${escapeHtml(t("buy_order_item_help"))}</div>
          ${renderBuyOrderItemPreview(commodity)}
          <label class="tiny-label" for="buy-order-quantity">${escapeHtml(t("buy_order_quantity_label"))}</label>
          <input id="buy-order-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultBuyOrderQuantity || 1))}" placeholder="${escapeHtml(t("quantity"))}">
          <label class="tiny-label" for="buy-order-unit-price">${escapeHtml(t("buy_order_unit_price"))}</label>
          <input id="buy-order-unit-price" type="number" min="1" value="${escapeHtml(String(buyOrderDefaultPrice))}" placeholder="${escapeHtml(t("buy_order_unit_price"))}">
          <input id="buy-order-reference-price" type="hidden" value="${escapeHtml(String(buyOrderReference))}">
          <div id="buy-order-price-help" class="summary-note">${escapeHtml(t("buy_order_price_help"))}</div>
          <div id="buy-order-bp-preview" class="summary-note">${escapeHtml(buyOrderBpPreviewText(buyOrderDefaultPrice, buyOrderReference))}</div>
          <div class="actions">
            <button type="button" id="buy-order-button" data-can-create="${canAct ? "true" : "false"}" data-item-valid="true" data-resolved-commodity-key="${escapeHtml(commodity.commodityKey)}" ${canAct ? "" : "disabled"}>${escapeHtml(t("create_buy_order"))}</button>
          </div>
        </div>
      </div>
    </div>
  `;
}

function setBuyOrderButtonState(valid, commodityKey = "") {
  const button = document.querySelector("#buy-order-button");
  if (!button) {
    return;
  }
  const canCreate = button.getAttribute("data-can-create") === "true";
  button.disabled = !canCreate || !valid;
  button.setAttribute("data-item-valid", valid ? "true" : "false");
  button.setAttribute("data-resolved-commodity-key", valid ? normalizeCommodityKey(commodityKey) : "");
}

function setBuyOrderPreviewPending(message) {
  const preview = document.querySelector("#buy-order-preview");
  const input = document.querySelector("#buy-order-key");
  if (!preview) {
    return;
  }
  preview.classList.remove("ready", "error");
  preview.classList.add("pending");
  preview.setAttribute("data-item-valid", "false");
  preview.setAttribute("data-resolved-commodity-key", "");
  preview.innerHTML = `
    <div class="goods-art"><span class="goods-art-fallback">${escapeHtml(iconLetter(input?.value || "?"))}</span></div>
    <div class="buy-order-preview-copy">
      <span class="tiny-label">${escapeHtml(t("buy_order_item_preview"))}</span>
      <strong>${escapeHtml(message)}</strong>
      <span class="muted-inline">${escapeHtml(input?.value || "-")}</span>
    </div>
  `;
  setBuyOrderButtonState(false);
}

function setBuyOrderPreviewError(message) {
  const preview = document.querySelector("#buy-order-preview");
  const input = document.querySelector("#buy-order-key");
  if (!preview) {
    return;
  }
  preview.classList.remove("ready", "pending");
  preview.classList.add("error");
  preview.setAttribute("data-item-valid", "false");
  preview.setAttribute("data-resolved-commodity-key", "");
  preview.innerHTML = `
    <div class="goods-art"><span class="goods-art-fallback">${escapeHtml(iconLetter(input?.value || "?"))}</span></div>
    <div class="buy-order-preview-copy">
      <span class="tiny-label">${escapeHtml(t("buy_order_item_preview"))}</span>
      <strong>${escapeHtml(message)}</strong>
      <span class="muted-inline">${escapeHtml(input?.value || "-")}</span>
    </div>
  `;
  setBuyOrderButtonState(false);
}

function setBuyOrderPreviewResolved(item) {
  const preview = document.querySelector("#buy-order-preview");
  if (!preview || !item?.commodityKey) {
    setBuyOrderPreviewError(t("buy_order_item_not_found"));
    return;
  }
  if (item.icon) {
    commodityIconCache.set(item.commodityKey, { status: "loaded", src: item.icon });
  }
  preview.outerHTML = renderBuyOrderItemPreview(item);
  const reference = Math.max(1, Math.round(Number(item.suggestedUnitPrice ?? item.referencePrice ?? item.bestSell ?? 1) || 1));
  const referenceInput = document.querySelector("#buy-order-reference-price");
  const priceInput = document.querySelector("#buy-order-unit-price");
  if (referenceInput) {
    referenceInput.value = String(reference);
  }
  if (priceInput) {
    priceInput.value = String(reference);
  }
  setBuyOrderButtonState(true, item.commodityKey);
  updateBuyOrderPricePreview();
  hydrateCommodityIcons();
}

function updateBuyOrderPricePreview() {
  const preview = document.querySelector("#buy-order-bp-preview");
  if (!preview) {
    return;
  }
  const referencePrice = numberValue("#buy-order-reference-price", 1);
  const unitPrice = numberValue("#buy-order-unit-price", referencePrice);
  preview.textContent = buyOrderBpPreviewText(unitPrice, referencePrice);
}

async function resolveBuyOrderItemInput(sequence) {
  const input = document.querySelector("#buy-order-key");
  if (!input) {
    return;
  }
  const itemId = normalizeCommodityKey(input.value);
  if (!itemId || !itemId.includes(":")) {
    setBuyOrderPreviewError(t("buy_order_item_not_found"));
    return;
  }
  setBuyOrderPreviewPending(t("buy_order_item_resolving"));
  try {
    const item = await api(`/api/items/resolve?itemId=${encodeURIComponent(itemId)}`);
    if (sequence !== buyOrderResolveSequence) {
      return;
    }
    if (item?.commodityKey && input.value.trim() !== item.commodityKey) {
      input.value = item.commodityKey;
    }
    setBuyOrderPreviewResolved(item);
  } catch (_) {
    if (sequence === buyOrderResolveSequence) {
      setBuyOrderPreviewError(t("buy_order_item_not_found"));
    }
  }
}

function syncBuyOrderControls() {
  const input = document.querySelector("#buy-order-key");
  const preview = document.querySelector("#buy-order-preview");
  if (!input || !preview) {
    return;
  }
  const initialKey = normalizeCommodityKey(preview.getAttribute("data-resolved-commodity-key"));
  setBuyOrderButtonState(preview.getAttribute("data-item-valid") === "true", initialKey);
}

function normalizeCommodityKey(value) {
  return String(value || "").trim();
}

function iconLetter(text) {
  const cleaned = String(text || "").trim();
  return cleaned ? cleaned[0].toUpperCase() : "?";
}

function minValue(entries, field) {
  let result = null;
  entries.forEach((entry) => {
    const numeric = Number(entry?.[field]);
    if (!Number.isFinite(numeric)) {
      return;
    }
    if (result == null || numeric < result) {
      result = numeric;
    }
  });
  return result;
}

function maxValue(entries, field) {
  let result = null;
  entries.forEach((entry) => {
    const numeric = Number(entry?.[field]);
    if (!Number.isFinite(numeric)) {
      return;
    }
    if (result == null || numeric > result) {
      result = numeric;
    }
  });
  return result;
}

function firstFiniteNumber(entries, field) {
  for (const entry of entries || []) {
    const numeric = Number(entry?.[field]);
    if (Number.isFinite(numeric)) {
      return numeric;
    }
  }
  return null;
}

function sumBy(entries, field) {
  return entries.reduce((sum, entry) => sum + (Number(entry?.[field]) || 0), 0);
}

function number(value) {
  return Number(value || 0).toLocaleString(state.locale);
}

// 无限库存系统商店:后端发 infinite=true,或 availableCount 是哨兵大数(>= 5 亿)→ 显示 ∞,不显示 10 亿这个数字。
const INFINITE_STOCK_THRESHOLD = 500000000;
function isInfiniteStock(entry) {
  if (entry && entry.infinite === true) {
    return true;
  }
  return entry && Number(entry.availableCount || 0) >= INFINITE_STOCK_THRESHOLD;
}
function stockDisplay(entry) {
  return isInfiniteStock(entry) ? "∞" : number(entry.availableCount);
}

function percent(value) {
  if (value == null || !Number.isFinite(Number(value))) {
    return "--";
  }
  return `${value >= 0 ? "+" : ""}${(Number(value) * 100).toFixed(2)}%`;
}

function formatTime(epochMs) {
  if (!epochMs) {
    return "-";
  }
  return new Date(epochMs).toLocaleString(state.locale);
}

function shortTime(epochMs) {
  if (!epochMs) {
    return "-";
  }
  const date = new Date(epochMs);
  return date.toLocaleDateString(state.locale, { month: "2-digit", day: "2-digit" }) + " " +
    date.toLocaleTimeString(state.locale, { hour: "2-digit", minute: "2-digit" });
}

function activeWorkspaceSection(route = state.activeProductTab) {
  const normalized = normalizePageRoute(route);
  if (normalized === "all") {
    return "all";
  }
  if (normalized === "map") {
    return "map";
  }
  if (normalized === "demand") {
    return "demand";
  }
  if (normalized === "my_orders") {
    return "orders";
  }
  if (normalized === "inventory") {
    return "inventory";
  }
  if (normalized === "sell" || normalized === "buy") {
    return "trade";
  }
  if (normalized === "chart" || normalized === "index") {
    return "analytics";
  }
  return "explore";
}

function workspaceTargetRoute(section) {
  if (section === "all") {
    return "all";
  }
  if (section === "map") {
    return "map";
  }
  if (section === "demand") {
    return "demand";
  }
  if (section === "orders") {
    return "my_orders";
  }
  if (section === "trade") {
    return state.activeProductTab === "buy" ? "buy" : "sell";
  }
  if (section === "analytics") {
    return state.activeProductTab === "index" ? "index" : "chart";
  }
  if (section === "inventory") {
    return "inventory";
  }
  return "browse";
}

function workspaceHint(section) {
  if (section === "map") {
    return t("map_hint");
  }
  if (section === "demand") {
    return t("demand_hint");
  }
  if (section === "orders") {
    return t("my_orders_hint");
  }
  if (section === "trade") {
    return t("trade_hint");
  }
  if (section === "analytics") {
    return t("analytics_hint");
  }
  if (section === "inventory") {
    return t("inventory_hint");
  }
  return t("explore_hint");
}

function workspaceButton(section, label) {
  const active = activeWorkspaceSection() === section;
  return `
    <button type="button" class="workspace-button ${active ? "active" : ""}" data-route="${workspaceTargetRoute(section)}" title="${escapeHtml(workspaceHint(section))}" ${active ? 'aria-current="page"' : ""}>
      <span class="workspace-title">${escapeHtml(label)}</span>
    </button>
  `;
}

function summaryBlock(label, value) {
  return `<div class="summary-block"><span class="tiny-label">${escapeHtml(label)}</span><strong>${escapeHtml(String(value))}</strong></div>`;
}

function filteredMarkets() {
  const query = String(state.marketQuery || "").trim().toLowerCase();
  if (!query) {
    return state.markets;
  }
  return state.markets.filter((market) => {
    const haystack = [
      market.marketName,
      market.ownerName,
      market.townName,
      market.dimensionId,
      market.position
    ].filter(Boolean).join(" ").toLowerCase();
    return haystack.includes(query);
  });
}

function renderMarketSummary(markets) {
  if (!els.marketSummary) {
    return;
  }
  const selected = state.markets.find((market) => market.marketId === state.selectedMarketId) || null;
  const liveCount = state.markets.filter((market) => market.loaded).length;
  const manageableCount = state.markets.filter((market) => market.canManage).length;
  els.marketSummary.innerHTML = `
    <div class="panel-head">
      <div>
        <p class="section-kicker">${escapeHtml(t("market_summary_title"))}</p>
        <h3>${escapeHtml(t("market_overview"))}</h3>
      </div>
    </div>
    <p class="muted">${escapeHtml(t("market_summary_hint"))}</p>
    <div class="market-summary-grid">
      ${summaryBlock(t("market_terminal_count"), number(state.markets.length))}
      ${summaryBlock(t("live_terminal"), number(liveCount))}
      ${summaryBlock(t("manage"), number(manageableCount))}
      ${summaryBlock(t("selected_market"), selected?.marketName || "-")}
    </div>
    ${state.marketQuery && markets.length !== state.markets.length
      ? `<div class="summary-note">${escapeHtml(number(markets.length))} / ${escapeHtml(number(state.markets.length))}</div>`
      : ""}
  `;
}

function renderSession() {
  document.body.classList.toggle("is-signed-in", !!state.session);
  if (!state.session) {
    if (els.sessionStatus) {
      els.sessionStatus.textContent = `${t("not_signed_in")} ${t("guest_mode_ready")}`;
    }
    if (els.sessionStatusInline) {
      els.sessionStatusInline.textContent = t("signin_status_guest");
    }
    if (els.sessionPill) {
      els.sessionPill.textContent = t("guest_word");
    }
    renderTopbarWallet();
    return;
  }

  const accountPart = state.session.accountBound && state.session.accountUsername
    ? ` · @${state.session.accountUsername}`
    : "";
  const sessionSummary = `${state.session.playerName} (${state.session.online ? t("online_word") : t("offline_word")})${accountPart}`;
  if (els.sessionStatus) {
    els.sessionStatus.textContent = sessionSummary;
  }
  if (els.sessionStatusInline) {
    els.sessionStatusInline.textContent = t("signin_status_ready");
  }
  if (els.sessionPill) {
    els.sessionPill.textContent = state.session.online ? `${state.session.playerName} ${t("online_word")}` : `${state.session.playerName} ${t("connected_word")}`;
  }
  renderTopbarWallet();
}

// 顶栏终端下拉:当前终端名 + 开合。终端列表(#market-list)渲染在下拉面板里。
function syncTerminalPicker() {
  const selected = state.markets.find((market) => market.marketId === state.selectedMarketId) || null;
  setNodeText(els.terminalCurrent, selected?.marketName || t("terminal_none"));
}

function setTerminalPanelOpen(open) {
  if (!els.terminalPanel || !els.terminalToggle) {
    return;
  }
  els.terminalPanel.hidden = !open;
  els.terminalToggle.setAttribute("aria-expanded", open ? "true" : "false");
  if (open) {
    els.marketSearch?.focus({ preventScroll: true });
  }
}

function renderMarkets() {
  const markets = filteredMarkets();
  renderMarketSummary(markets);
  syncTerminalPicker();

  if (!markets.length) {
    morphInto(els.marketList, `<div class="empty-state">${escapeHtml(state.marketQuery ? t("no_match") : t("no_markets"))}</div>`);
    return;
  }

  morphInto(els.marketList, markets.map((market) => `
    <button type="button" class="market-card ${market.marketId === state.selectedMarketId ? "active" : ""} ${market.loaded ? "" : "is-cold"}" data-market-id="${escapeHtml(market.marketId)}">
      <p class="panel-meta">${market.loaded ? t("live_terminal") : t("chunk_cold")}</p>
      <h3>${escapeHtml(market.marketName)}</h3>
      <div class="muted">${escapeHtml(market.ownerName || market.townName || "-")}</div>
      <div class="muted">${escapeHtml(market.dimensionId || "-")} @ ${escapeHtml(market.position || "-")}</div>
      <div class="market-meta">
        ${market.canManage ? `<span class="pill success">${escapeHtml(t("manage"))}</span>` : `<span class="pill">${escapeHtml(t("view"))}</span>`}
        ${market.loaded ? `<span class="pill">${escapeHtml(t("loaded"))}</span>` : `<span class="pill warning">${escapeHtml(t("cold"))}</span>`}
      </div>
    </button>
  `).join(""));
}

// 市场单行摘要:默认只显示终端名、状态与几个关键数字;"更多"展开完整账本(钱包/国库/库存等)。
function renderMarketStrip(detail, catalog, canAct, canManage) {
  const figure = (label, value, accent = false) => `
    <span class="strip-figure${accent ? " accent" : ""}"><small>${escapeHtml(label)}</small><strong>${escapeHtml(String(value))}</strong></span>
  `;
  return `
    <section class="market-overview">
      <details class="market-strip" ${state.marketStripOpen ? "open" : ""}>
        <summary>
          <span class="market-strip-name">
            <h2>${escapeHtml(detail.marketName)}</h2>
            <span class="pill ${detail.linkedDock ? "success" : "warning"}">${detail.linkedDock ? escapeHtml(t("dock_linked")) : escapeHtml(t("no_linked_dock"))}</span>
            ${canManage ? `<span class="pill">${escapeHtml(t("manager_access"))}</span>` : ""}
          </span>
          <span class="market-strip-figures">
            ${canAct ? figure(t("wallet_balance"), number(detail.walletBalance), true) : ""}
            ${canAct ? figure(t("pending_credits"), number(detail.pendingCredits)) : ""}
            ${figure(t("commodity_types"), number(catalog.length))}
            ${figure(t("listings_word"), number((detail.listings || []).length))}
            ${figure(t("open_demand"), number(detail.openDemandUnits))}
          </span>
          <span class="market-strip-more">${escapeHtml(t("market_strip_more"))}</span>
        </summary>
        <div class="market-strip-body">
          <div class="overview-subtitle">${escapeHtml(t("owner_word"))} ${escapeHtml(detail.ownerName || "-")} · ${escapeHtml(t("warehouse_word"))} ${escapeHtml(detail.linkedWarehouseName || detail.linkedDockName || "-")} · ${escapeHtml(t("town_word"))} ${escapeHtml(detail.townName || "-")}</div>
          <div class="metric-strip">
            ${metricBox(t("wallet_balance"), number(detail.walletBalance))}
            ${metricBox(t("wallet_reserved"), number(detail.walletReservedBalance || 0))}
            ${metricBox(t("treasury_balance"), number(detail.treasuryBalance || 0))}
            ${metricBox(t("pending_credits"), number(detail.pendingCredits))}
            ${metricBox(t("commodity_types"), number(catalog.length))}
            ${metricBox(t("storage_units"), number(detail.stockpileTotalUnits))}
            ${metricBox(t("open_demand"), number(detail.openDemandUnits))}
            ${metricBox(t("my_buy_orders_metric"), number((detail.buyOrderEntries || []).length))}
            ${state.settings.showTownEconomySummary ? metricBox(t("net_balance"), number(detail.netBalance)) : ""}
          </div>
          <div class="summary-note">${escapeHtml(t("wallet_balance_hint"))}</div>
        </div>
      </details>
    </section>
  `;
}

function renderMarketMapPlaceholder(detail, canAct, canManage) {
  const selectedName = detail?.marketName || t("selected_market");
  const selectedTown = detail?.townName || "-";
  const selectedWarehouse = detail?.linkedWarehouseName || detail?.linkedDockName || "-";
  const focusedMarketId = detail?.marketId || state.selectedMarketId || "";
  return `
    <section
      id="market-map-root"
      class="market-map-reserved market-map-workspace map-panel-left-collapsed map-panel-right-collapsed"
      aria-label="${escapeHtml(t("map_tab"))}"
      data-market-map-root
      data-island="map"
      data-focused-market="${escapeHtml(focusedMarketId)}"
      data-selected-name="${escapeHtml(selectedName)}"
      data-selected-town="${escapeHtml(selectedTown)}"
      data-selected-warehouse="${escapeHtml(selectedWarehouse)}"
      data-can-act="${canAct ? "true" : "false"}"
      data-can-manage="${canManage ? "true" : "false"}">
      <aside class="map-side-panel map-side-panel-left">
        <button type="button" class="map-panel-collapse map-panel-collapse-left" data-map-panel-toggle="left" aria-label="切换地图图层栏" aria-pressed="true">☰</button>
        <div class="map-panel-section">
          <p class="section-kicker">${escapeHtml(t("map_tab"))}</p>
          <h2>${escapeHtml(t("map_live_title"))}</h2>
          <p>${escapeHtml(t("map_reserved_body"))}</p>
        </div>
        <div class="map-layer-toggles" data-map-layer-toggles></div>
        <div class="map-market-list" data-map-market-list></div>
      </aside>
      <div class="map-canvas-shell">
        <div class="squaremap-leaflet" data-square-map></div>
        <canvas class="market-map-canvas" data-market-map-canvas width="1280" height="720"></canvas>
        <div class="map-floating-toolbar" data-map-toolbar></div>
        <div class="map-tooltip" data-map-tooltip hidden></div>
      </div>
      <aside class="map-side-panel map-side-panel-right">
        <button type="button" class="map-panel-collapse map-panel-collapse-right" data-map-panel-toggle="right" aria-label="切换地图详情栏" aria-pressed="true">⌕</button>
        <div class="map-selection-detail" data-map-selection-detail></div>
        <div class="map-shipment-list" data-map-shipment-list></div>
      </aside>
    </section>
  `;
}

// ---- 3D 地球视图 ----
// Tellus 真实地球世界才提供:终端和运输船画在 MapLibre 地球仪上。平面地图仍是默认,玩家的选择记在本机。
function isTellusWorld(world) {
  return !!(world && world.tellus === true && Number(world.worldScale) > 0);
}

// 选了地球但还不知道是不是 Tellus 世界时先按地球渲染(显示加载中),确认不是再退回平面地图。
function effectiveMapMode() {
  if (state.mapMode !== "globe") {
    return "flat";
  }
  return state.mapWorld === null || isTellusWorld(state.mapWorld) ? "globe" : "flat";
}

// 世界信息只取一次(公开接口,很小);失败按非 Tellus 处理,不反复重试。
function loadMapWorld() {
  if (state.mapWorld !== null || state.mapWorldLoading) {
    return;
  }
  state.mapWorldLoading = true;
  api("/api/map/world")
    .catch(() => ({ tellus: false }))
    .then((world) => {
      state.mapWorldLoading = false;
      state.mapWorld = world || { tellus: false };
      if (state.activeProductTab === "map") {
        renderDetailPreservingScroll();
      }
    });
}

function renderMapModeSwitch() {
  if (!isTellusWorld(state.mapWorld)) {
    return "";
  }
  const current = effectiveMapMode();
  return `
    <div class="map-mode-bar">
      <div class="layout-switch" role="group" aria-label="${escapeHtml(t("map_mode_label"))}">
        <button type="button" class="${current === "flat" ? "active" : ""}" data-map-mode="flat" aria-pressed="${current === "flat" ? "true" : "false"}">${escapeHtml(t("map_mode_flat"))}</button>
        <button type="button" class="${current === "globe" ? "active" : ""}" data-map-mode="globe" aria-pressed="${current === "globe" ? "true" : "false"}">${escapeHtml(t("map_mode_globe"))}</button>
      </div>
    </div>
  `;
}

function renderGlobeStage(detail) {
  const focusedMarketId = detail?.marketId || state.selectedMarketId || "";
  return `
    <section
      id="market-globe-root"
      class="globe-stage"
      aria-label="${escapeHtml(t("map_mode_globe"))}"
      data-globe-root
      data-island="globe"
      data-focused-market="${escapeHtml(focusedMarketId)}">
      <div class="globe-canvas"></div>
      <p class="globe-message" role="status">${escapeHtml(t("globe_loading"))}</p>
    </section>
  `;
}

function renderMapWorkspace(detail, canAct, canManage) {
  return `${renderMapModeSwitch()}${effectiveMapMode() === "globe" ? renderGlobeStage(detail) : renderMarketMapPlaceholder(detail, canAct, canManage)}`;
}

function setMapMode(mode) {
  const next = mode === "globe" ? "globe" : "flat";
  if (next === state.mapMode) {
    return;
  }
  state.mapMode = next;
  try {
    localStorage.setItem("marketWebMapMode", next);
  } catch (_) {
  }
  renderDetailPreservingScroll();
}

function mountGlobeIfNeeded(onMap) {
  const root = document.querySelector("[data-globe-root]");
  if (!root || !onMap) {
    window.SailboatGlobe?.unmount();
    return;
  }
  if (!isTellusWorld(state.mapWorld)) {
    return; // 世界信息还在加载,加载完会重新渲染再挂载
  }
  if (!window.SailboatGlobe || !window.maplibregl) {
    ensureGlobeLibraries().then(
      () => mountGlobeIfNeeded(state.activeProductTab === "map"),
      () => {
        const note = root.querySelector(".globe-message");
        if (note) {
          note.textContent = t("map_library_failed");
        }
        setStatus(t("map_library_failed"), true);
      }
    );
    return;
  }
  const mounted = window.SailboatGlobe.mount(root, {
    fetchJson: (path) => api(path),
    includeShipments: !!state.sessionToken,
    focusedMarketId: state.selectedMarketId || root.getAttribute("data-focused-market") || "",
    formatEta: formatEtaSeconds,
    t,
    onSelectMarket: (marketId) => switchMarket(marketId)
  });
  if (mounted && state.pendingShipmentFocus) {
    window.SailboatGlobe.focusShipment(state.pendingShipmentFocus);
    state.pendingShipmentFocus = "";
  }
}

function mountMarketMapIfNeeded() {
  const onMap = state.activeProductTab === "map";
  if (onMap) {
    loadMapWorld();
  }
  mountGlobeIfNeeded(onMap);
  const root = document.querySelector("[data-market-map-root]");
  const mapApi = window.SailboatSquareMap || window.SailboatMarketMap;
  if (!root || !onMap) {
    if (mapApi && typeof mapApi.unmount === "function") {
      mapApi.unmount();
    }
    return;
  }
  // 地图脚本(Leaflet + map.js 等)按需加载;根节点是 data-island,map.js 在其中自行渲染,重绘不会重新挂载。
  if (!mapApi) {
    ensureMapLibraries().then(
      () => mountMarketMapIfNeeded(),
      () => setStatus(t("map_library_failed"), true)
    );
    return;
  }
  const focusedMarketId = state.selectedMarketId || root.getAttribute("data-focused-market") || "";
  root.setAttribute("data-focused-market", focusedMarketId);
  if (typeof mapApi.mount === "function") {
    mapApi.mount(root, {
      focusedMarketId,
      locale: state.locale || "zh-CN",
      apiBase: ""
    });
  }
  if (state.pendingShipmentFocus && typeof mapApi.focusShipment === "function") {
    mapApi.focusShipment(state.pendingShipmentFocus);
    state.pendingShipmentFocus = "";
  }
}

// ---- 按需加载 ----
// 图表库与地图相关脚本只在第一次打开对应页面时下载,首屏少下载约 450 KB。
const assetLoads = new Map();

function loadAssetOnce(url, kind = "script") {
  if (!assetLoads.has(url)) {
    assetLoads.set(url, new Promise((resolve, reject) => {
      let node;
      if (kind === "style") {
        node = document.createElement("link");
        node.rel = "stylesheet";
        node.href = url;
      } else {
        node = document.createElement("script");
        node.src = url;
        node.async = false;
      }
      node.addEventListener("load", () => resolve());
      node.addEventListener("error", () => {
        assetLoads.delete(url);
        node.remove();
        reject(new Error(`Failed to load ${url}`));
      });
      if (kind === "style") {
        // 插在 app.css 之前,保持原来的层叠顺序(app.css 可以覆盖第三方样式)。
        document.head.insertBefore(node, document.querySelector('link[href="/app.css"]'));
      } else {
        document.head.appendChild(node);
      }
    }));
  }
  return assetLoads.get(url);
}

function ensureChartLibrary() {
  if (typeof LightweightCharts !== "undefined") {
    return Promise.resolve();
  }
  return loadAssetOnce("/lightweight-charts.standalone.production.js");
}

function ensureMapLibraries() {
  if (window.SailboatSquareMap || window.SailboatMarketMap) {
    return Promise.resolve();
  }
  return Promise.all([
    loadAssetOnce("/vendor/leaflet/leaflet.css", "style"),
    loadAssetOnce("/vendor/leaflet/leaflet.js"),
    loadAssetOnce("/tellus-basemap.js"),
    loadAssetOnce("/logistics-render.js"),
    loadAssetOnce("/logistics-panel.js"),
    loadAssetOnce("/map.js")
  ]);
}

// 地球视图:MapLibre GL(约 280 KB gzip)只在第一次切到 3D 地球时下载。
function ensureGlobeLibraries() {
  if (window.SailboatGlobe && window.maplibregl && window.SailboatTellusBasemap) {
    return Promise.resolve();
  }
  return Promise.all([
    loadAssetOnce("/maplibre-gl.css", "style"),
    loadAssetOnce("/maplibre-gl.js"),
    loadAssetOnce("/tellus-basemap.js"),
    loadAssetOnce("/market-globe.js")
  ]);
}

function aggregatedPurchaseReasonText(reason) {
  switch (String(reason || "")) {
    case "not_logged_in":
      return t("reason_not_logged_in");
    case "no_town":
      return t("reason_no_town");
    case "own_listing":
      return t("reason_own_listing");
    case "no_route":
      return t("reason_no_route");
    default:
      return "";
  }
}

function renderAggregatedPurchaseButton(listing) {
  const listingId = String(listing.listingId || "");
  const purchasable = !!listing.purchasable && !!state.selectedMarketId;
  const reason = !state.selectedMarketId && listing.purchasable
    ? t("reason_no_market_selected")
    : aggregatedPurchaseReasonText(listing.purchaseReason);
  return purchasable
    ? `<button type="button" data-aggregate-purchase="${escapeHtml(listingId)}">${escapeHtml(t("all_listings_buy"))}</button>`
    : `<button type="button" class="danger" disabled title="${escapeHtml(reason)}">${escapeHtml(t("all_listings_buy"))}</button>`;
}

// 聚合卡片:复用市场浏览卡的样式(图标+类别+稀有度+在售数+最低价),但 route 用聚合专属标识,
// 点击进聚合商品详情(全服在售列表)而非单市场详情。
function renderAggregatedCommodityCard(commodity) {
  return `
    <button type="button" class="goods-card ${commodity.commodityKey === state.aggregatedSelectedCommodityKey ? "active" : ""}" data-aggregate-card-commodity-key="${escapeHtml(commodity.commodityKey)}">
      ${renderCommodityIcon(commodity.commodityKey, commodity.displayName)}
      <div class="goods-card-topline">
        <span class="goods-category-tag">${escapeHtml(categoryLabel(commodity.category))}</span>
        <span class="goods-rarity-tag ${rarityClassName(commodity.rarity)}">${escapeHtml(rarityLabel(commodity.rarity))}</span>
      </div>
      <h3>${escapeHtml(commodity.displayName)}</h3>
      <div class="goods-key">${escapeHtml(commodity.commodityKey)}</div>
      <div class="goods-summary">
        <span class="goods-badge">${number(commodity.totalListings)} ${escapeHtml(t("selling"))}</span>
        <span class="goods-badge">${number(commodity.sellUnits)} ${escapeHtml(t("units_live"))}</span>
      </div>
      <div class="goods-card-footer">
        <strong data-live-price="${escapeHtml(livePriceKey("card", commodity.commodityKey, "all"))}" data-price-value="${commodity.bestSell == null ? "" : escapeHtml(String(commodity.bestSell))}">${commodity.bestSell == null ? "--" : number(commodity.bestSell)}</strong>
        <span>${escapeHtml(t("lowest_sell"))}</span>
      </div>
    </button>
  `;
}

function renderAggregatedView() {
  if (!state.aggregatedLoaded) {
    return `
      <section class="goods-market">
        <div class="empty-state">${escapeHtml(t("all_listings_loading"))}</div>
      </section>
    `;
  }

  const catalog = buildCommodityCatalog({ listings: state.aggregatedListings });

  if (state.aggregatedSelectedCommodityKey) {
    // 详情:用全服伪 detail 喂 buildCommodityCatalog + renderCommodityDetailPage(aggregate 模式),
    // 复用终端的 5 个 tab / quote-panel / 图表;数据全服,购买走聚合端点。
    const detailReady = state.aggregatedDetailLoaded
      && state.aggregatedDetail
      && state.aggregatedDetailKey === state.aggregatedSelectedCommodityKey;
    if (!detailReady) {
      return `
        <section class="goods-market">
          <div class="empty-state">${escapeHtml(t("all_listings_loading"))}</div>
        </section>
      `;
    }
    const detailCatalog = buildCommodityCatalog(state.aggregatedDetail);
    const selected = findCommodityByKey(detailCatalog, state.aggregatedSelectedCommodityKey)
      || findCommodityByKey(catalog, state.aggregatedSelectedCommodityKey);
    if (selected) {
      const canAct = !!state.session;
      return `
        <section class="goods-market">
          ${renderCommodityDetailPage(selected, state.aggregatedDetail, false, canAct, { aggregate: true })}
        </section>
      `;
    }
    state.aggregatedSelectedCommodityKey = "";
  }

  const filteredCatalog = filterCatalog(catalog);
  return `
    <section class="goods-market">
      ${renderCatalogShelf({
        catalog,
        filteredCatalog,
        kicker: t("all_listings_kicker"),
        title: t("all_listings_title"),
        emptyMessage: t("all_listings_empty"),
        cardRenderer: (commodity) => renderAggregatedCommodityCard(commodity)
      })}
    </section>
  `;
}

// 动效:renderDetail 每次整页重建 DOM(含搜索输入、状态提示),所以入场动画只在真正变化时播放——
// 视图(路由/终端/商品/加载完成)变化 → view-enter;筛选或排序变化 → grid-enter(格子依次"装入");
// 新状态消息 → status-enter。普通重绘不加这些 class,新节点就不会重复播放动画。
const motionState = { viewKey: null, gridKey: null, statusKey: "" };

function prefersReducedMotion() {
  return !!window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
}

function currentViewKey() {
  return [
    state.activeProductTab,
    state.selectedMarketId,
    state.detail?.marketId || "",
    state.selectedCommodityKey,
    state.aggregatedLoaded ? "agg" : "",
    state.aggregatedSelectedCommodityKey,
    state.aggregatedDetailLoaded ? "agg-detail" : "",
    state.aggregatedDetailTab
  ].join("|");
}

function currentGridKey() {
  const filters = state.catalogFilters || {};
  return [
    filters.categoryGroup,
    filters.category,
    filters.rarity,
    filters.minPrice,
    filters.maxPrice,
    state.catalogSort?.mode,
    state.catalogSort?.direction,
    state.browseOrderView
  ].join("|");
}

// 数字从 0 滚到目标值(市场摘要、商品价格),只在切换终端/商品时播放。
function countUpFigures(nodes) {
  nodes.forEach((node) => {
    const textNode = node.firstChild;
    if (!textNode || textNode.nodeType !== Node.TEXT_NODE) {
      return;
    }
    const text = textNode.textContent;
    const target = Number(String(text).replace(/[^0-9.-]/g, ""));
    if (!text.trim() || !Number.isFinite(target) || target <= 0) {
      return;
    }
    const started = performance.now();
    const duration = 520;
    const step = (now) => {
      const progress = Math.min(1, (now - started) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      textNode.textContent = progress >= 1 ? text : number(Math.round(target * eased));
      if (progress < 1 && textNode.isConnected) {
        requestAnimationFrame(step);
      }
    };
    requestAnimationFrame(step);
  });
}

function playViewMotion() {
  const viewKey = currentViewKey();
  const gridKey = currentGridKey();
  const statusKey = `${state.status || ""}|${state.error || ""}`;
  const firstPaint = motionState.viewKey === null;
  const viewChanged = viewKey !== motionState.viewKey;
  const gridChanged = gridKey !== motionState.gridKey;
  const statusChanged = statusKey !== motionState.statusKey && !!(state.status || state.error);
  motionState.viewKey = viewKey;
  motionState.gridKey = gridKey;
  motionState.statusKey = statusKey;

  const body = document.body;
  body.classList.remove("view-enter", "grid-enter", "status-enter");
  if (prefersReducedMotion() || renderGuard.inViewTransition) {
    return;
  }
  if (viewChanged) {
    body.classList.add("view-enter");
  }
  if (viewChanged || gridChanged) {
    els.marketDetail?.querySelectorAll(".goods-grid > .goods-card").forEach((card, index) => {
      card.style.setProperty("--i", String(Math.min(index, 24)));
    });
    body.classList.add("grid-enter");
  }
  if (statusChanged) {
    body.classList.add("status-enter");
  }
  if (viewChanged && !firstPaint) {
    countUpFigures(els.marketDetail?.querySelectorAll(".strip-figure strong, .price-line") || []);
  }
}

function renderDetail() {
  // 输入法正在组字时推迟重绘,组字结束(compositionend)后补一次,避免拼音字母被当成文字提交。
  if (isComposingIn(els.marketDetail)) {
    renderGuard.deferred = true;
    return;
  }
  if (els.allListingsNav) {
    els.allListingsNav.classList.toggle("active", state.activeProductTab === "all");
  }

  // 全部商品作为一级入口放导航首位;点它留在全服视图,点其余进选中市场对应功能。全服视图与市场详情共用这排导航。
  const workspaceNav = `
    <div class="workspace-switch">
        ${workspaceButton("all", t("all_listings_tab"))}
        ${workspaceButton("explore", t("explore_tab"))}
        ${workspaceButton("trade", t("trade_tab"))}
        ${workspaceButton("orders", t("my_orders_tab"))}
        ${workspaceButton("demand", t("buy_order_tab"))}
        ${state.detail?.canManage ? workspaceButton("inventory", t("inventory_tab")) : ""}
        ${workspaceButton("analytics", t("analytics_tab"))}
        ${workspaceButton("map", t("map_tab"))}
    </div>
  `;
  // 一级导航渲染在顶栏 #workspace-nav,原地更新;点击由事件委托处理。
  if (els.workspaceNav) {
    morphInto(els.workspaceNav, workspaceNav);
    // 顶栏放不下全部页签时在右侧淡出,提示可以横向滚动。
    const navStrip = els.workspaceNav.querySelector(".workspace-switch");
    navStrip?.classList.toggle("is-overflowing", navStrip.scrollWidth > navStrip.clientWidth + 2);
  }
  syncTerminalPicker();

  if (state.activeProductTab === "all") {
    renderTopbarWallet();
    commitDetailHtml(`<div class="market-shell">${renderAggregatedView()}</div>`);
    syncDetailControls();
    playViewMotion();
    if (!state.aggregatedLoaded) {
      loadAggregatedListings();
    }
    // 选中某商品但其全服伪 detail 未加载/已过期时按需拉取。
    if (state.aggregatedSelectedCommodityKey
        && !state.aggregatedDetailLoading
        && (state.aggregatedDetailKey !== state.aggregatedSelectedCommodityKey || !state.aggregatedDetailLoaded)) {
      loadAggregatedDetail(state.aggregatedSelectedCommodityKey);
    }
    // 网格视图(未选商品)和详情视图都要挂载图标——否则全服商品卡片图标永远不加载(不依赖选中终端)。
    hydrateCommodityIcons();
    // 详情已就绪时再挂图表(K线/指数 tab 复用终端图表渲染,数据来自 aggregatedDetail)。
    if (state.aggregatedSelectedCommodityKey && state.aggregatedDetailLoaded && state.aggregatedDetail) {
      hydrateLightweightChart();
    }
    return;
  }

  if (!state.detail) {
    renderTopbarWallet();
    commitDetailHtml(`<div class="market-shell">${state.activeProductTab === "map" ? renderMapWorkspace(null, !!state.session, false) : `<div class="empty-state">${escapeHtml(t("select_market_prompt"))}</div>`}</div>`);
    syncDetailControls();
    playViewMotion();
    mountMarketMapIfNeeded();
    return;
  }

  const detail = state.detail;
  const canManage = !!detail.canManage;
  if (state.activeProductTab === "inventory" && !canManage) {
    state.activeProductTab = "browse";
  }
  const canAct = !!state.session;
  renderTopbarWallet();
  const catalog = buildCommodityCatalog(detail);
  const inventoryCatalog = canManage ? buildInventoryCatalog(detail) : [];
  const purchaseCatalog = purchaseBrowseCatalog(catalog);
  const demandCatalog = demandBrowseCatalog(catalog);
  const browseSourceCatalog = activeBrowseCatalog(catalog);
  const browseCatalog = filterCatalog(browseSourceCatalog);
  const inventoryFilteredCatalog = filterCatalog(inventoryCatalog);
  const inventorySelectedCommodity = findCommodityByKey(inventoryCatalog, state.selectedCommodityKey);
  const selectedCommodity = state.activeProductTab === "inventory"
    ? findCommodityByKey(catalog, state.selectedCommodityKey)
    : getSelectedCommodity(browseCatalog, catalog);
  syncRouteUrl(true);

  const browseSection = renderCatalogShelf({
    catalog: browseSourceCatalog,
    filteredCatalog: browseCatalog,
    kicker: t("browse_goods"),
    title: normalizeBrowseOrderView(state.browseOrderView) === "demand" ? t("browse_demand_orders") : t("browse_purchase_orders"),
    emptyMessage: normalizeBrowseOrderView(state.browseOrderView) === "demand" ? t("no_demand_rows") : t("no_purchase_rows"),
    cardRenderer: (commodity) => normalizeBrowseOrderView(state.browseOrderView) === "demand"
      ? renderDemandCommodityCard(commodity)
      : renderPurchaseCommodityCard(commodity),
    headExtra: renderBrowseOrderModeSwitch(purchaseCatalog.length, demandCatalog.length),
    heatmapRoute: normalizeBrowseOrderView(state.browseOrderView) === "demand" ? "demand" : "sell"
  });
  const inventorySection = renderCatalogShelf({
    catalog: inventoryCatalog,
    filteredCatalog: inventoryFilteredCatalog,
    kicker: t("inventory_tab"),
    title: t("inventory_title"),
    emptyMessage: t("no_inventory_rows"),
    cardRenderer: (commodity) => renderInventoryCard(commodity)
  });

  commitDetailHtml(`
    <div class="market-shell">
      ${renderMarketStrip(detail, catalog, canAct, canManage)}

      <section class="goods-market">
        ${activeWorkspaceSection() === "orders"
          ? renderMyOrdersPage(detail)
          : (state.activeProductTab === "map"
          ? renderMapWorkspace(detail, canAct, canManage)
          : (state.activeProductTab === "browse"
          ? browseSection
          : (state.activeProductTab === "inventory"
            ? (inventorySelectedCommodity
              ? renderInventoryDetailPage(inventorySelectedCommodity, detail, canManage, canAct)
              : `${inventorySection}${renderMineColoniesPanel(detail, canManage, canAct)}`)
            : (selectedCommodity
              ? renderCommodityDetailPage(selectedCommodity, detail, canManage, canAct)
              : browseSection))))}
      </section>
    </div>
  `);

  syncDetailControls();
  playViewMotion();
  hydrateCommodityIcons();
  hydrateLightweightChart();
  mountMarketMapIfNeeded();

  // 进入库存(管理)工作区时按需拉取殖民地仓库可上架列表(仅 canManage)。
  // 市场切换 / 上架成功后(mineColoniesLoaded=false)都会触发重拉。
  if (canManage && state.activeProductTab === "inventory"
      && (!state.mineColoniesLoaded || state.mineColoniesMarketId !== state.selectedMarketId)) {
    state.mineColoniesMarketId = state.selectedMarketId;
    loadMineColoniesWarehouse(state.selectedMarketId);
  }
}

const CANCELLABLE_ORDER_STATUSES = new Set(["PAID", "WAITING_SHIPMENT", "PICKUP_LOCKED"]);

function localizeOrderStatus(status) {
  const raw = String(status || "").trim();
  if (!raw) {
    return "-";
  }
  const key = "order_status_" + raw.toLowerCase();
  const localized = t(key);
  return localized === key ? raw : localized;
}

function orderQueueText(order) {
  const position = Number(order.queuePosition || 0);
  if (position <= 0) {
    return "";
  }
  const minutes = Math.max(1, Math.floor((Number(order.queueEtaSeconds || 0)) / 60));
  return t("queue_position").replace("%1", position).replace("%2", minutes);
}

// 后端 myOrders 目前只带 listingId:能在当前终端的挂单里找到时显示物品名和图标;
// 找不到(已售罄下架,或是在别的终端买的)时标题显示订单号。
function orderCommodity(order, detail) {
  const listing = (detail?.listings || []).find((entry) => String(entry.listingId) === String(order.listingId));
  return {
    key: order.commodityKey || listing?.commodityKey || "",
    name: order.itemName || order.displayName || listing?.itemName || ""
  };
}

// ---- 物流时间轴 ----
// 卖家发货 / 自动自提:已付款 → 等待发货 → 运输中 → 已送达;真人自提:已付款 → 已锁定自提 → 已送达。
const ORDER_FLOW_SHIP = ["PAID", "WAITING_SHIPMENT", "IN_TRANSIT", "DELIVERED"];
const ORDER_FLOW_PICKUP = ["PAID", "PICKUP_LOCKED", "DELIVERED"];

function renderOrderTimeline(order) {
  const status = String(order.status || "").toUpperCase();
  const cancelled = status === "CANCELLED";
  const flow = status === "PICKUP_LOCKED" ? ORDER_FLOW_PICKUP : ORDER_FLOW_SHIP;
  const reached = cancelled ? -1 : flow.indexOf(status);
  if (!cancelled && reached < 0) {
    return "";
  }
  return `
    <ol class="order-timeline ${cancelled ? "is-cancelled" : ""}" aria-label="${escapeHtml(t("order_progress"))}">
      ${flow.map((step, index) => {
        const stage = cancelled ? "is-pending" : (index < reached ? "is-done" : (index === reached ? "is-current" : "is-pending"));
        return `<li class="${stage}"${stage === "is-current" ? ' aria-current="step"' : ""}><span>${escapeHtml(localizeOrderStatus(step))}</span></li>`;
      }).join("")}
      ${cancelled ? `<li class="is-cancelled-step" aria-current="step"><span>${escapeHtml(localizeOrderStatus("CANCELLED"))}</span></li>` : ""}
    </ol>
  `;
}

// 排队预计时间按秒倒数:以数据拉取时刻为起点,页面上每秒更新一次文字。
function etaCountdownText(remainingSeconds) {
  return remainingSeconds > 0 ? t("eta_countdown", { time: formatEtaSeconds(remainingSeconds) }) : t("eta_any_moment");
}

function renderEtaCountdown(order) {
  const seconds = Number(order.queueEtaSeconds || 0);
  if (seconds <= 0 || !state.detailFetchedAt) {
    return "";
  }
  const deadline = state.detailFetchedAt + seconds * 1000;
  return `<span class="eta-countdown" data-eta-deadline="${deadline}">${escapeHtml(etaCountdownText(Math.max(0, Math.round((deadline - Date.now()) / 1000))))}</span>`;
}

// 有倒数时用"排第 N 位 · 21m 00s 后发货"代替静态的"约 21 分钟"。
function renderOrderQueue(order) {
  const position = Number(order.queuePosition || 0);
  if (position <= 0) {
    return "";
  }
  const countdown = renderEtaCountdown(order);
  return `<div class="muted-inline order-queue">${countdown
    ? `${escapeHtml(t("order_queue_place", { position }))} · ${countdown}`
    : escapeHtml(orderQueueText(order))}</div>`;
}

let etaTimer = 0;

function syncEtaCountdowns() {
  const nodes = document.querySelectorAll("[data-eta-deadline]");
  if (!nodes.length) {
    window.clearInterval(etaTimer);
    etaTimer = 0;
    return;
  }
  if (!etaTimer) {
    etaTimer = window.setInterval(syncEtaCountdowns, 1000);
  }
  nodes.forEach((node) => {
    const remaining = Math.max(0, Math.round((Number(node.getAttribute("data-eta-deadline")) - Date.now()) / 1000));
    const text = etaCountdownText(remaining);
    if (node.textContent !== text) {
      node.textContent = text;
    }
  });
}

function renderMyOrdersPage(detail) {
  const orders = Array.isArray(detail?.myOrders) ? detail.myOrders : [];
  const canAct = !!state.session;
  const head = `
    <div class="panel-head">
      <div>
        <p class="section-kicker">${escapeHtml(t("my_orders_tab"))}</p>
        <h3>${escapeHtml(t("my_orders_tab"))}</h3>
      </div>
      <span class="pill">${number(orders.length)}</span>
    </div>
    <p class="muted">${escapeHtml(t("my_orders_hint"))}</p>
  `;
  if (!orders.length) {
    return `
      <div class="goods-detail">
        <div class="table-card">
          ${head}
          <div class="empty-state">${escapeHtml(t("my_orders_empty"))}</div>
        </div>
      </div>
    `;
  }

  const cards = orders.map((order) => {
    const orderId = String(order.orderId || "");
    const cancellable = CANCELLABLE_ORDER_STATUSES.has(String(order.status || "").toUpperCase());
    const shipping = order.shipping;
    const item = orderCommodity(order, detail);
    return `
      <div class="surface-card my-order-card">
        <div class="panel-head">
          <div class="order-card-title">
            ${item.key ? renderCommodityIcon(item.key, item.name || item.key) : ""}
            <div>
              <p class="section-kicker">${escapeHtml(item.name ? `${t("order_word")} ${orderId || "-"}` : t("order_word"))}</p>
              <h3>${escapeHtml(item.name ? t("order_item_title", { name: item.name, qty: number(order.quantity) }) : (orderId || "-"))}</h3>
            </div>
          </div>
          <span class="pill">${escapeHtml(localizeOrderStatus(order.status))}</span>
        </div>
        <div class="metric-strip">
          ${metricBox(t("quantity"), number(order.quantity))}
          ${metricBox(t("total_price_word"), number(order.totalPrice))}
        </div>
        ${renderOrderTimeline(order)}
        <div class="muted-inline"><strong>${escapeHtml(order.sourceDockName || "-")}</strong> → <strong>${escapeHtml(order.targetDockName || "-")}</strong></div>
        ${renderOrderQueue(order)}
        ${order.shippingOrderId ? `
          <div class="actions">
            <button type="button" class="secondary" data-track-shipment="${escapeHtml(order.shippingOrderId)}">${escapeHtml(t("order_track_on_map"))}</button>
          </div>
        ` : ""}
        ${shipping ? `
          <div class="muted-inline">
            ${escapeHtml(t("shipping_word"))}: ${escapeHtml(shipping.boatName || "-")} · ${escapeHtml(shipping.routeName || "-")} · ${escapeHtml(localizeOrderStatus(shipping.status))}
          </div>
        ` : ""}
        ${cancellable ? `
          <div class="actions">
            <button type="button" class="danger" data-cancel-purchase-order="${escapeHtml(orderId)}" ${canAct ? "" : "disabled"}>${escapeHtml(t("order_cancel"))}</button>
          </div>
        ` : ""}
      </div>
    `;
  }).join("");

  return `
    <div class="goods-detail">
      <div class="table-card">
        ${head}
      </div>
      <div class="stack">
        ${cards}
      </div>
    </div>
  `;
}

function renderCommodityDetailPage(commodity, detail, canManage, canAct, options = null) {
  const tab = currentProductTab(options);
  const aggregate = !!(options && options.aggregate);
  if (tab === "demand" || activeWorkspaceSection(tab) === "demand") {
    return renderDemandOnlyPage(commodity, canAct, options);
  }
  const activeSeries = primaryChartSeries(commodity);
  const latestPoint = latestChartPoint(activeSeries);
  const chartStats = chartSummary(activeSeries);
  const workspaceSection = activeWorkspaceSection(tab);
  // 聚合详情:固定展示对标终端的全部 5 个 tab(浏览/在售购买/求购订单/价格K线/市场指数),全服数据。
  const detailNav = aggregate
    ? `<div class="tab-strip">${routeButton("browse", t("browse_tab"), options)}${routeButton("sell", t("selling"), options)}${routeButton("buy", t("buying"), options)}${routeButton("chart", t("chart_tab"), options)}${routeButton("index", t("market_index_tab"), options)}</div>`
    : workspaceSection === "trade"
    ? `<div class="tab-strip">${routeButton("sell", t("selling"), options)}${routeButton("buy", t("buying"), options)}</div>`
    : workspaceSection === "demand"
      ? `<div class="tab-strip">${routeButton("demand", t("buy_order_tab"), options)}</div>`
    : workspaceSection === "analytics"
      ? `<div class="tab-strip">${routeButton("chart", t("chart_tab"), options)}${routeButton("index", t("market_index_tab"), options)}</div>`
      : `<div class="tab-strip">${routeButton("browse", t("browse_tab"), options)}${routeButton("sell", t("selling"), options)}${routeButton("chart", t("chart_tab"), options)}</div>`;

  const crumb = aggregate
    ? `
      <div class="crumb-strip">
        <button type="button" class="link-button" data-aggregate-detail-back>${escapeHtml(t("all_listings_back"))}</button>
        <span>/</span>
        <strong>${escapeHtml(commodity.displayName)}</strong>
      </div>`
    : `
      <div class="crumb-strip">
        <span>${escapeHtml(t("market"))}</span>
        <span>/</span>
        <span>${escapeHtml(detail.marketName)}</span>
        <span>/</span>
        <strong>${escapeHtml(commodity.displayName)}</strong>
      </div>`;

  return `
    <div class="goods-detail">
      ${crumb}

      <section class="goods-hero ${rarityClassName(commodity.rarity)}">
        ${renderCommodityIcon(commodity.commodityKey, commodity.displayName)}
        <div class="goods-main">
          <div class="goods-card-topline">
            <span class="goods-category-tag">${escapeHtml(categoryLabel(commodity.category))}</span>
            <span class="goods-rarity-tag ${rarityClassName(commodity.rarity)}">${escapeHtml(rarityLabel(commodity.rarity))}</span>
          </div>
          <div class="goods-title-row">
            <h2>${escapeHtml(commodity.displayName)}</h2>
            <button type="button" class="watch-toggle ${isWatched(commodity.commodityKey) ? "is-on" : ""}" data-watch-toggle="${escapeHtml(commodity.commodityKey)}" aria-pressed="${isWatched(commodity.commodityKey) ? "true" : "false"}">
              <span aria-hidden="true">${isWatched(commodity.commodityKey) ? "★" : "☆"}</span> ${escapeHtml(isWatched(commodity.commodityKey) ? t("watch_on") : t("watch_add"))}
            </button>
          </div>
          <div class="muted">${escapeHtml(commodity.commodityKey)}</div>
          <div class="goods-stats">
            ${metricBox(t("sell_listings"), number(commodity.totalListings))}
            ${metricBox(t("on_sale"), number(commodity.sellUnits))}
            ${metricBox(t("buying_demand"), number(commodity.demandUnits))}
            ${metricBox(t("in_storage"), number(commodity.storageUnits))}
            ${metricBox(t("avg_24h"), latestPoint ? number(latestPoint.averageUnitPrice) : "--")}
            ${metricBox(t("trades_24h"), number(chartStats.tradeCount))}
            ${metricBox(t("reference_price"), commodity.referencePrice == null ? "--" : number(commodity.referencePrice))}
            ${metricBox(t("liquidity_score"), number(commodity.liquidityScore))}
          </div>
        </div>
        <aside class="buy-box quote-panel">
          <div class="quote-box">
            <span class="quote-label">${escapeHtml(t("lowest_sell"))}</span>
            <strong class="price-line" data-live-price="${escapeHtml(livePriceKey("quote", commodity.commodityKey, aggregate ? "all" : ""))}" data-price-value="${commodity.bestSell == null ? "" : escapeHtml(String(commodity.bestSell))}">${commodity.bestSell == null ? "--" : number(commodity.bestSell)}</strong>
          </div>
          <div class="quote-box buy">
            <span class="quote-label">${escapeHtml(t("highest_buy"))}</span>
            <strong class="${commodity.bestBuy == null ? "" : "coin-figure"}">${commodity.bestBuy == null ? "--" : `${number(bestBidPrice(commodity))} <small>${commodity.bestBuy > 0 ? "+" : ""}${number(commodity.bestBuy)} bp</small>`}</strong>
          </div>
          <div class="actions buy-box-actions">
            ${aggregate
              ? `<button type="button" data-aggregate-tab="sell">${escapeHtml(t("buy_box_buy"))}</button>
                 <button type="button" class="secondary" data-aggregate-tab="buy">${escapeHtml(t("buying"))}</button>`
              : `<button type="button" data-route="sell">${escapeHtml(t("buy_box_buy"))}</button>
                 <button type="button" class="secondary" data-route="demand">${escapeHtml(t("buy_box_bid"))}</button>`}
          </div>
        </aside>
      </section>

      ${detailNav}

      ${renderCommodityPage(commodity, detail, canManage, canAct, options)}
    </div>
  `;
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "\"": "&quot;",
    "'": "&#39;"
  }[char]));
}

els.bindButton?.addEventListener("click", bindAccountLogin);
els.loginButton.addEventListener("click", login);
els.copyCommandButton?.addEventListener("click", () => {
  copyText(state.settings.commandText || "/marketweb token", t("command_copied"));
});
els.accessToggle?.addEventListener("click", () => {
  setAccessPanelOpen(!state.accessPanelOpen);
  setNodeText(els.accessToggleLabel, t(state.accessPanelOpen ? "access_panel_open" : "access_panel_closed"));
});
els.refreshMarkets.addEventListener("click", async () => {
  try {
    await loadMarkets();
    setStatus(t("markets_refreshed"));
  } catch (error) {
    setStatus(error.message, true);
  }
});
els.allListingsNav?.addEventListener("click", () => {
  setTerminalPanelOpen(false);
  state.activeProductTab = "all";
  state.aggregatedLoaded = false;
  state.aggregatedSelectedCommodityKey = "";
  state.aggregatedDetail = null;
  state.aggregatedDetailKey = "";
  state.aggregatedDetailLoaded = false;
  state.aggregatedDetailTab = "browse";
  syncRouteUrl(false);
  renderDetail();
});
// 主题:index.html 头部已按 marketWebTheme / 系统偏好设置 data-theme;这里负责切换与持久化。
function currentTheme() {
  return document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark";
}

function syncThemeToggle() {
  const theme = currentTheme();
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) {
    meta.setAttribute("content", theme === "light" ? "#ffffff" : "#0d0f14");
  }
  if (!els.themeToggle) {
    return;
  }
  const label = t(theme === "light" ? "theme_to_dark" : "theme_to_light");
  els.themeToggle.setAttribute("aria-pressed", theme === "light" ? "true" : "false");
  els.themeToggle.setAttribute("aria-label", label);
  els.themeToggle.setAttribute("title", label);
}

function applyTheme(theme, persist) {
  const root = document.documentElement;
  if (!prefersReducedMotion()) {
    root.classList.add("theme-switching");
    window.clearTimeout(applyTheme.timer);
    applyTheme.timer = window.setTimeout(() => root.classList.remove("theme-switching"), 320);
  }
  root.setAttribute("data-theme", theme === "light" ? "light" : "dark");
  if (persist) {
    try {
      localStorage.setItem("marketWebTheme", currentTheme());
    } catch (error) {
      // 存储不可用时仅本次会话生效。
    }
  }
  syncThemeToggle();
  // 图表在创建时读取 CSS 主题色,切换后重建一次。
  hydrateLightweightChart();
}

els.terminalToggle?.addEventListener("click", (event) => {
  event.stopPropagation();
  setTerminalPanelOpen(!!els.terminalPanel?.hidden);
});

document.addEventListener("click", (event) => {
  if (els.terminalPanel && !els.terminalPanel.hidden && !event.target.closest?.(".terminal-picker")) {
    setTerminalPanelOpen(false);
  }
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && els.terminalPanel && !els.terminalPanel.hidden) {
    setTerminalPanelOpen(false);
    els.terminalToggle?.focus();
  }
});

els.themeToggle?.addEventListener("click", () => {
  applyTheme(currentTheme() === "light" ? "dark" : "light", true);
});

window.matchMedia?.("(prefers-color-scheme: light)")?.addEventListener?.("change", (event) => {
  let saved = null;
  try {
    saved = localStorage.getItem("marketWebTheme");
  } catch (error) {
    saved = null;
  }
  if (saved !== "light" && saved !== "dark") {
    applyTheme(event.matches ? "light" : "dark", false);
  }
});

els.localeSelect?.addEventListener("change", (event) => switchLocale(event.target.value || "zh-CN"));
els.marketSearch?.addEventListener("input", (event) => {
  state.marketQuery = event.target.value || "";
  renderMarkets();
});
els.token.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    bindAccountLogin();
  }
});
els.token.addEventListener("input", () => {
  syncTokenFromCapture(false);
});
els.accountUsername?.addEventListener("input", (event) => {
  persistAccountUsername(event.target.value || "");
});
els.accountUsername?.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    login();
  }
});
els.accountPassword?.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    login();
  }
});
els.chatCapture?.addEventListener("input", () => {
  syncTokenFromCapture(true);
});
window.addEventListener("popstate", async () => {
  applyRouteFromLocation();
  renderMarkets();
  renderDetail();
  try {
    await loadMarkets("replace");
  } catch (error) {
    setStatus(error.message, true);
  }
});

// 后台自动刷新:config.json 的 autoRefreshSeconds > 0 时启用(最短 5 秒)。
// 只在页面可见、没有打开对话框、没有在输入法组字时刷新当前视图;失败静默,下次再试。
const autoRefresh = { timer: 0, running: false, lastRun: 0 };
// 实时推送在线时,配置的轮询放慢到至少一分钟一次兜底:服务器可能只推送网页上的操作,不推送游戏内终端的变化。
const LIVE_SAFETY_POLL_MS = 60000;

function autoRefreshIntervalMs() {
  const seconds = Number(state.settings.autoRefreshSeconds) || 0;
  return seconds > 0 ? Math.max(5, seconds) * 1000 : 0;
}

async function refreshVisibleData({ force = false } = {}) {
  if (autoRefresh.running || document.visibilityState !== "visible") {
    return;
  }
  // 实时推送在线时由事件驱动刷新,定时轮询只做慢速兜底。
  if (!force && live.status === "live" && Date.now() - autoRefresh.lastRun < LIVE_SAFETY_POLL_MS) {
    return;
  }
  if (document.querySelector("dialog[open]") || renderGuard.composing) {
    return;
  }
  autoRefresh.running = true;
  try {
    if (state.activeProductTab === "all") {
      await loadAggregatedListings({ silent: true });
      if (state.aggregatedSelectedCommodityKey) {
        await loadAggregatedDetail(state.aggregatedSelectedCommodityKey, { force: true, silent: true });
      }
    } else if (state.selectedMarketId && state.activeProductTab !== "map") {
      await loadMarketDetail(state.selectedMarketId, "replace", { silent: true });
    }
  } finally {
    autoRefresh.running = false;
    autoRefresh.lastRun = Date.now();
  }
}

function startAutoRefresh() {
  window.clearInterval(autoRefresh.timer);
  const interval = autoRefreshIntervalMs();
  if (!interval) {
    return;
  }
  autoRefresh.lastRun = Date.now();
  autoRefresh.timer = window.setInterval(refreshVisibleData, interval);
}

document.addEventListener("visibilitychange", () => {
  const interval = autoRefreshIntervalMs();
  if (interval && document.visibilityState === "visible" && Date.now() - autoRefresh.lastRun >= interval) {
    refreshVisibleData();
  }
});

// ---- 实时推送(SSE) ----
// 服务器提供 /api/events 时用 fetch 读取事件流:可以带 Authorization 头,令牌不会出现在 URL 和日志里。
// 断线后指数退避重连,并带 Last-Event-ID 补收错过的事件;服务器没有这个接口(404)时退回 autoRefreshSeconds 轮询。
// 事件只是"某处变了"的通知,页面收到后静默拉取当前视图的数据,再由 morph 原地更新。
const live = {
  status: "idle",
  controller: null,
  lastEventId: "",
  retryMs: 1000,
  retryTimer: 0,
  reconnecting: false,
  rotating: false,
  marketTimer: 0,
  aggregatedTimer: 0,
  trades: [],
  tradeSerial: 0
};

const LIVE_STATUS_LABELS = {
  connecting: "live_status_connecting",
  live: "live_status_live",
  offline: "live_status_offline",
  unsupported: "live_status_polling"
};

function setLiveStatus(status) {
  live.status = status;
  renderLiveStatus();
}

function renderLiveStatus() {
  const host = document.querySelector("#live-status");
  if (!host || !window.litHtml) {
    return;
  }
  // 服务器不支持实时推送、也没有开启定时刷新时,不显示状态标识。
  const visible = live.status !== "idle" && !(live.status === "unsupported" && !autoRefreshIntervalMs());
  host.hidden = !visible;
  if (!visible) {
    return;
  }
  const { html, render } = window.litHtml;
  const label = t(LIVE_STATUS_LABELS[live.status] || "live_status_offline");
  host.title = label;
  // 顶栏空间有限:只显示状态圆点,文字给读屏软件和鼠标悬停提示。
  render(html`<span class="live-dot" data-live=${live.status} aria-hidden="true"></span><span class="sr-only">${label}</span>`, host);
}

function startLiveFeed() {
  if (live.status !== "idle") {
    return;
  }
  connectLiveFeed();
}

// 登录、登出后事件的接收人变了(个人订单与钱包事件),重新建立连接。
function restartLiveFeed() {
  if (live.status === "idle" || live.status === "unsupported") {
    return;
  }
  live.controller?.abort();
  live.controller = null;
  live.lastEventId = "";
  connectLiveFeed();
}

async function connectLiveFeed({ quiet = false } = {}) {
  window.clearTimeout(live.retryTimer);
  const controller = new AbortController();
  live.controller = controller;
  live.rotating = false;
  if (!quiet) {
    setLiveStatus("connecting");
  }
  try {
    const headers = new Headers({ Accept: "text/event-stream" });
    if (state.sessionToken) {
      headers.set("Authorization", `Bearer ${state.sessionToken}`);
    }
    if (live.lastEventId) {
      headers.set("Last-Event-ID", live.lastEventId);
    }
    const response = await fetch("/api/events", { headers, signal: controller.signal, cache: "no-store" });
    if (!response.ok || !response.body) {
      // 读掉/取消响应体,否则连接会一直挂着。
      response.body?.cancel().catch(() => {});
      if (response.status === 404 || response.status === 405 || response.status === 501) {
        live.controller = null;
        setLiveStatus("unsupported");
        return;
      }
      throw new Error(`HTTP ${response.status}`);
    }
    setLiveStatus("live");
    live.retryMs = 1000;
    if (live.reconnecting) {
      // 断线期间可能错过个人事件:重连后静默刷新一次当前视图。
      live.reconnecting = false;
      refreshVisibleData({ force: true });
    }
    await readEventStream(response.body, controller.signal);
    if (live.rotating && !controller.signal.aborted) {
      // 服务器按计划轮换连接(JDK HttpServer 有响应时长上限):立即重连,Last-Event-ID 补收,状态保持"实时"。
      connectLiveFeed({ quiet: true });
      return;
    }
    throw new Error("stream closed");
  } catch (error) {
    if (controller.signal.aborted) {
      return;
    }
    live.controller = null;
    live.reconnecting = true;
    setLiveStatus("offline");
    live.retryTimer = window.setTimeout(connectLiveFeed, live.retryMs);
    live.retryMs = Math.min(30000, live.retryMs * 2);
  }
}

// text/event-stream 解析:按空行分隔事件,支持 event / data / id / retry 字段,冒号开头的是注释(心跳)。
async function readEventStream(body, signal) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let event = { type: "message", data: [] };
  while (!signal.aborted) {
    const { value, done } = await reader.read();
    if (done) {
      return;
    }
    buffer += decoder.decode(value, { stream: true });
    let newline = buffer.indexOf("\n");
    while (newline >= 0) {
      let line = buffer.slice(0, newline);
      buffer = buffer.slice(newline + 1);
      newline = buffer.indexOf("\n");
      if (line.endsWith("\r")) {
        line = line.slice(0, -1);
      }
      if (!line) {
        if (event.data.length) {
          dispatchLiveEvent(event.type, event.data.join("\n"));
        }
        event = { type: "message", data: [] };
        continue;
      }
      if (line.startsWith(":")) {
        continue;
      }
      const colon = line.indexOf(":");
      const field = colon < 0 ? line : line.slice(0, colon);
      let fieldValue = colon < 0 ? "" : line.slice(colon + 1);
      if (fieldValue.startsWith(" ")) {
        fieldValue = fieldValue.slice(1);
      }
      if (field === "event") {
        event.type = fieldValue || "message";
      } else if (field === "data") {
        event.data.push(fieldValue);
      } else if (field === "id") {
        live.lastEventId = fieldValue;
      } else if (field === "retry" && Number(fieldValue) > 0) {
        live.retryMs = Number(fieldValue);
      }
    }
  }
}

function dispatchLiveEvent(type, data) {
  let payload;
  try {
    payload = JSON.parse(data);
  } catch (_) {
    return;
  }
  if (!payload || typeof payload !== "object") {
    return;
  }
  if (type === "market") {
    // marketId "*": the server only knows that market data changed (in game or on the web), so refresh what is open.
    const marketId = String(payload.marketId || "");
    if (state.activeProductTab === "all") {
      scheduleLiveRefresh("aggregated");
    } else if (marketId && (marketId === "*" || marketId === state.selectedMarketId)) {
      scheduleLiveRefresh("market");
    }
  } else if (type === "trade") {
    recordLiveTrade(payload);
  } else if (type === "order") {
    setStatus(t("live_order_update", { id: String(payload.orderId || ""), status: localizeOrderStatus(payload.status) }));
    scheduleLiveRefresh("market");
  } else if (type === "wallet") {
    scheduleLiveRefresh("market");
  } else if (type === "rotate") {
    live.rotating = true;
  }
}

// 同一波事件只刷新一次:350ms 内的多条通知合并。
function scheduleLiveRefresh(kind) {
  const timerKey = kind === "aggregated" ? "aggregatedTimer" : "marketTimer";
  window.clearTimeout(live[timerKey]);
  live[timerKey] = window.setTimeout(() => {
    if (kind === "aggregated") {
      loadAggregatedListings({ silent: true });
      if (state.aggregatedSelectedCommodityKey) {
        loadAggregatedDetail(state.aggregatedSelectedCommodityKey, { force: true, silent: true });
      }
    } else if (state.selectedMarketId) {
      loadMarketDetail(state.selectedMarketId, "replace", { silent: true });
    }
  }, 350);
}

function recordLiveTrade(payload) {
  const unitPrice = Number(payload.unitPrice);
  const commodityKey = String(payload.commodityKey || "");
  if (!commodityKey || !Number.isFinite(unitPrice)) {
    return;
  }
  const previous = live.trades.find((trade) => trade.commodityKey === commodityKey);
  let direction = "flat";
  if (previous && unitPrice > previous.unitPrice) {
    direction = "up";
  } else if (previous && unitPrice < previous.unitPrice) {
    direction = "down";
  }
  live.trades.unshift({
    id: ++live.tradeSerial,
    commodityKey,
    itemName: String(payload.itemName || knownItemName(commodityKey) || commodityKey),
    unitPrice,
    quantity: Number(payload.quantity) || 0,
    direction
  });
  live.trades.length = Math.min(live.trades.length, 16);
  renderLiveTicker();
}

// 服务器的成交事件只带物品 key:从已加载的终端详情或全服挂单里找显示名称。
function knownItemName(commodityKey) {
  const rows = [...(state.detail?.listings || []), ...(state.aggregatedListings || [])];
  const hit = rows.find((row) => row.commodityKey === commodityKey && (row.itemName || row.displayName));
  return hit ? String(hit.itemName || hit.displayName) : "";
}

// 成交跑马灯用 lit-html 渲染:列表按成交编号增量更新,物品名等玩家数据自动转义。
function renderLiveTicker() {
  const host = document.querySelector("#live-ticker");
  if (!host || !window.litHtml) {
    return;
  }
  host.hidden = !live.trades.length;
  if (!live.trades.length) {
    return;
  }
  host.setAttribute("aria-label", t("live_ticker_label"));
  const { html, render, repeat } = window.litHtml;
  render(html`
    <span class="live-ticker-label">${t("live_ticker_label")}</span>
    <ol class="live-ticker-list">
      ${repeat(live.trades, (trade) => trade.id, (trade) => html`
        <li class=${`is-${trade.direction}`}>
          <button type="button" class="ticker-item" data-ticker-commodity=${trade.commodityKey}>
            <span>${trade.itemName}</span>
            <strong class="coin-figure">${number(trade.unitPrice)}</strong>
            <small>×${number(trade.quantity)}</small>
          </button>
        </li>
      `)}
    </ol>
  `, host);
}

// 价格闪烁:每次渲染后比较 data-live-price 元素与上次显示的价格,上涨闪绿、下跌闪红。
const shownPrices = new Map();

function livePriceKey(kind, commodityKey, scope = "") {
  const market = state.activeProductTab === "all" ? "all" : state.selectedMarketId;
  return `${market}|${kind}|${scope}|${commodityKey}`;
}

function flashChangedPrices() {
  document.querySelectorAll("[data-live-price]").forEach((node) => {
    const key = node.getAttribute("data-live-price") || "";
    const raw = node.getAttribute("data-price-value") || "";
    const value = Number(raw);
    if (!key || !raw || !Number.isFinite(value)) {
      return;
    }
    const previous = shownPrices.get(key);
    shownPrices.set(key, value);
    if (previous === undefined || previous === value || prefersReducedMotion()) {
      return;
    }
    node.classList.remove("price-up", "price-down");
    void node.offsetWidth;
    node.classList.add(value > previous ? "price-up" : "price-down");
  });
}

registerDetailActions();
installDelegatedHandlers();

(async function init() {
  applyRouteFromLocation();
  // 配置、版本与会话互不依赖,并行请求;会话失效时退回游客模式,市场照常加载。
  let sessionError = null;
  await Promise.all([
    loadSettings(),
    loadServerVersion(),
    state.sessionToken ? hydrateSession().catch((error) => { sessionError = error; }) : null
  ]);
  renderSession();
  renderMarkets();
  renderDetail();
  try {
    await loadMarkets("replace");
  } catch (error) {
    setStatus(error.message, true);
  }
  if (sessionError) {
    setStatus(t("session_expired"), true);
  } else if (state.session) {
    setStatus(t("signed_in_as", { name: state.session.playerName }));
  }
  startAutoRefresh();
  startLiveFeed();
})();
