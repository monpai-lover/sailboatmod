const state = {
  sessionToken: localStorage.getItem("marketWebSessionToken") || "",
  locale: localStorage.getItem("marketWebLocale") || "zh-CN",
  session: null,
  markets: [],
  selectedMarketId: "",
  selectedCommodityKey: "",
  activeProductTab: "browse",
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
  catalogSort: loadStoredJson("marketWebCatalogSort", {
    mode: "activity",
    direction: "desc"
  }),
  detail: null,
  settings: {
    uiTitle: "Sailboat Market",
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

const els = {
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
  token: document.querySelector("#login-token"),
  loginButton: document.querySelector("#login-button"),
  tokenHelper: document.querySelector("#token-helper"),
  sessionStatus: document.querySelector("#session-status"),
  marketNetworkKicker: document.querySelector("#market-network-kicker"),
  browseMarketsTitle: document.querySelector("#browse-markets-title"),
  refreshMarkets: document.querySelector("#refresh-markets"),
  marketSidebarHint: document.querySelector("#market-sidebar-hint"),
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

const I18N = {
  "zh-CN": {
    hero_title: "市场浏览器，用于查看码头货物、在售挂单、求购订单与价格走势。",
    hero_subtitle: "先在左侧打开一个市场终端，再像 BUFF 一样浏览商品目录，然后切换到单商品的在售、求购和价格走势页。",
    mode_label: "模式",
    mode_value: "商品市场",
    session_label: "会话",
    flow_label: "流程",
    flow_value: "令牌登录",
    portal_kicker: "网页登录",
    auth_title: "从游戏客户端认证",
    auth_subtitle: "在游戏里执行一次命令，把复制出来的 token 或聊天输出贴到这里，然后像正常网页一样浏览市场。",
    command_label: "游戏内命令",
    copy_command: "复制命令",
    fast_signin: "快速登录",
    connect_player: "连接当前玩家",
    not_signed_in: "未登录。",
    paste_chat: "粘贴聊天输出或 token",
    paste_chat_placeholder: "把完整聊天行粘贴到这里，页面会自动识别 token。",
    token_placeholder: "Token 会自动出现在这里",
    sign_in: "登录",
    token_helper: "在游戏里执行命令。模组会复制 token，这个页面也能从聊天文本里自动提取。",
    market_network: "市场网络",
    browse_markets: "浏览市场",
    refresh: "刷新",
    market_sidebar_hint: "先选一个市场终端，再像商品市场一样浏览其货物并打开单商品页。",
    brand_kicker: "Sailboat 市场交易所",
    language_label: "语言",
    empty_select_market: "选择一个市场终端以浏览商品、挂单、求购和价格走势。",
    market_overview: "市场概览",
    browse_goods: "浏览商品",
    commodity_shelf: "商品货架",
    search_placeholder: "搜索物品名或 commodity key",
    sort_price: "价格",
    sort_change: "涨跌",
    sort_activity: "活跃",
    no_match: "没有匹配这个筛选条件的商品。",
    selected_commodity: "当前商品",
    lowest_sell: "最低在售",
    highest_buy: "最高求购",
    avg_24h: "24小时均价",
    trades_24h: "24小时成交",
    sell_listings: "在售行数",
    on_sale: "在售数量",
    buying_demand: "求购需求",
    in_storage: "仓储库存",
    browse_tab: "浏览商品",
    purchase_tab: "购买商品",
    chart_tab: "K线图",
    market_index_tab: "大盘指数",
    units_live: "单位在售",
    units_wanted: "单位求购",
    volume_label: "成交量",
    selling: "在售",
    buying: "求购",
    my_buy_orders: "我的求购单",
    price_history: "价格历史",
    recent_buckets: "最近分时桶",
    chart_context: "图表环境",
    sell_item: "上架商品",
    create_listing: "创建挂单",
    create_buy_order: "创建求购单",
    claim_credits: "领取货款",
    retry_dispatch: "重试发运",
    market_notes: "市场说明",
    sell_summary: "卖盘摘要",
    demand_summary: "买盘流动性",
    market_snapshot: "市场快照",
    no_commodity_data: "这个市场目前还没有商品数据。",
    no_selling_rows: "这个商品当前没有在售挂单。",
    no_buy_rows: "这个商品当前没有公开求购。",
    no_my_buy_rows: "你还没有这个商品的求购单。",
    no_chart_rows: "这个商品还没有价格记录。",
    no_storage_match: "当前没有与该商品匹配的码头仓储条目。",
    seller_note: "卖家备注",
    suggested_word: "建议",
    min_bp: "最低 bp",
    max_bp: "最高 bp",
    to_word: "到",
    offline_word: "离线",
    online_word: "在线",
    connected_word: "已连接",
    signed_in_as: "已登录为 {name}。",
    sign_in_required: "需要先输入登录 token。",
    sign_in_to_trade: "登录后才能进行购买、上架、求购、取消和领取等操作。",
    copied: "已复制。",
    command_copied: "命令已复制，请在游戏内执行。",
    token_extracted: "已提取 token 并复制到剪贴板。",
    clipboard_failed: "无法访问剪贴板，请手动复制。",
    markets_refreshed: "市场已刷新。",
    action_completed: "操作已完成。",
    sign_in_to_load_markets: "登录后即可执行市场操作。",
    guest_mode_ready: "访客模式：可以浏览当前市场和商品，但不能进行操作。",
    no_markets: "还没有注册任何市场终端。",
    select_market_prompt: "选择一个市场终端来浏览它的商品目录。",
    guest_word: "访客",
    live_terminal: "在线终端",
    chunk_cold: "区块未加载",
    manage: "可管理",
    view: "仅查看",
    loaded: "已加载",
    cold: "未加载",
    dock_linked: "已绑定码头",
    no_linked_dock: "未绑定码头",
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
    dock: "码头",
    status: "状态",
    price_band: "价格区间",
    implied_bid: "推算出价",
    time: "时间",
    average: "均价",
    low: "低",
    high: "高",
    volume: "量",
    buy_1: "购买 1",
    cancel: "取消",
    current_sell_side: "当前卖盘",
    current_buy_side: "当前买盘",
    dock_stock: "码头库存",
    terminal_status: "终端状态",
    chart_disabled: "配置已关闭价格图表。",
    no_chart_buckets: "这个商品还没有价格分桶数据。",
    chart_no_selection: "当前没有可展示图表的商品。",
    chart_library_missing: "图表库未加载，已切换为内置K线渲染。",
    chart_init_failed: "图表初始化失败，已切换为内置K线渲染。",
    chart_render_failed: "图表渲染失败。",
    no_demand_ladder: "这个商品目前还没有形成求购梯队。",
    waiting_reference: "暂无明确在售价参考。",
    around_current_ask: "按当前卖价推算大致在 {range}。",
    no_storage: "无仓储",
    storage_rows: "仓储行",
    live_requests: "活跃求购",
    stock_rows: "仓储条目",
    owner_word: "所有者",
    town_word: "城镇",
    pending_credits: "待领货款",
    commodity_types: "商品种类",
    storage_units: "仓储总量",
    open_demand: "开放需求",
    my_buy_orders_metric: "我的求购单",
    net_balance: "净余额",
    reference_price: "参考价",
    liquidity_score: "流动性",
    market_index: "大盘指数",
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
    inflation_unavailable: "当前没有 CPI 数据，暂不可用。",
    reset_zoom: "重置缩放",
    current_change: "当前涨跌",
    max_drawdown: "最大回撤",
    inception_return: "起始收益",
    chart_hover_empty: "移动到图表上查看 OHLC 与成交量",
    market_index_chart: "市场指数走势",
    category_index_chart: "分类指数走势",
    cpi_chart: "消费价格指数",
    loans_chart: "未偿贷款走势",
    inflation_since_inception: "累计通胀",
    loans_change: "贷款变化",
    cpi_now: "当前 CPI",
    macro_overview: "宏观概览"
  },
  "en-US": {
    hero_title: "Market browser for dock goods, selling rows, buy orders, and price history.",
    hero_subtitle: "Open a market terminal on the left, browse commodities like a catalog, then switch between selling, buying, and price chart tabs for one selected item.",
    mode_label: "Mode",
    mode_value: "Commodity Market",
    session_label: "Session",
    flow_label: "Flow",
    flow_value: "Token Login",
    portal_kicker: "Portal Access",
    auth_title: "Authenticate from the game client",
    auth_subtitle: "Run the in-game command once, paste the copied token or chat line here, then browse market pages in a normal web UI.",
    command_label: "In-game command",
    copy_command: "Copy command",
    fast_signin: "Fast Sign In",
    connect_player: "Connect current player",
    not_signed_in: "Not signed in.",
    paste_chat: "Paste chat output or token",
    paste_chat_placeholder: "Paste the full chat line here. The page will detect the token automatically.",
    token_placeholder: "Token appears here automatically",
    sign_in: "Sign in",
    token_helper: "Run the command in game. The mod copies the token, and this page can parse pasted output too.",
    market_network: "Market Network",
    browse_markets: "Browse Markets",
    refresh: "Refresh",
    market_sidebar_hint: "Choose one market terminal, then browse its commodities like a marketplace and drill into one item page.",
    brand_kicker: "Sailboat Market Exchange",
    language_label: "Language",
    empty_select_market: "Select a market terminal to browse commodities, listings, buy orders, and price history.",
    market_overview: "Market Overview",
    browse_goods: "Browse Goods",
    commodity_shelf: "Commodity Shelf",
    search_placeholder: "Search item name or commodity key",
    sort_price: "Price",
    sort_change: "Change",
    sort_activity: "Activity",
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
    claim_credits: "Claim credits",
    retry_dispatch: "Retry dispatch",
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
    suggested_word: "suggested",
    min_bp: "Min bp",
    max_bp: "Max bp",
    to_word: "to",
    offline_word: "offline",
    online_word: "online",
    connected_word: "connected",
    signed_in_as: "Signed in as {name}.",
    sign_in_required: "Login token is required.",
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
    dock_linked: "Dock linked",
    no_linked_dock: "No linked dock",
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
    status: "Status",
    price_band: "Price Band",
    implied_bid: "Implied Bid",
    time: "Time",
    average: "Average",
    low: "Low",
    high: "High",
    volume: "Volume",
    buy_1: "Buy 1",
    cancel: "Cancel",
    current_sell_side: "Current sell side",
    current_buy_side: "Current buy side",
    dock_stock: "Dock stock",
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
  return state.settings.uiTitle || "Sailboat Market";
}

function updateStaticCopy() {
  document.documentElement.lang = state.locale;
  document.title = uiTitle();
  if (els.localeSelect) {
    els.localeSelect.value = state.locale;
  }
  setNodeText(els.brandKicker, t("brand_kicker"));
  setNodeText(els.heroTitle, `${uiTitle()} · ${t("hero_title")}`);
  setNodeText(els.heroSubtitle, t("hero_subtitle"));
  setNodeText(els.localeLabel, t("language_label"));
  setNodeText(els.modeLabel, t("mode_label"));
  setNodeText(els.modeValue, t("mode_value"));
  setNodeText(els.sessionLabel, t("session_label"));
  setNodeText(els.flowLabel, t("flow_label"));
  setNodeText(els.flowValue, t("flow_value"));
  setNodeText(els.portalKicker, t("portal_kicker"));
  setNodeText(els.authTitle, t("auth_title"));
  setNodeText(els.authSubtitle, t("auth_subtitle"));
  setNodeText(els.commandLabel, t("command_label"));
  setNodeText(els.copyCommandButton, t("copy_command"));
  setNodeText(els.fastSigninKicker, t("fast_signin"));
  setNodeText(els.connectPlayerTitle, t("connect_player"));
  setNodeText(els.chatCaptureLabel, t("paste_chat"));
  setNodeText(els.loginButton, t("sign_in"));
  setNodeText(els.tokenHelper, t("token_helper"));
  setNodeText(els.marketNetworkKicker, t("market_network"));
  setNodeText(els.browseMarketsTitle, t("browse_markets"));
  setNodeText(els.refreshMarkets, t("refresh"));
  setNodeText(els.marketSidebarHint, t("market_sidebar_hint"));
  setNodePlaceholder(els.chatCapture, t("paste_chat_placeholder"));
  setNodePlaceholder(els.token, t("token_placeholder"));
  renderSession();
}

function setStatus(message, isError = false) {
  state.status = isError ? "" : message || "";
  state.error = isError ? message || "" : "";
  renderDetail();
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (!headers.has("Content-Type") && options.body) {
    headers.set("Content-Type", "application/json");
  }
  if (state.sessionToken) {
    headers.set("Authorization", `Bearer ${state.sessionToken}`);
  }
  const response = await fetch(path, { ...options, headers });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.message || `Request failed: ${response.status}`);
  }
  return data;
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

async function login() {
  const token = (els.token.value || "").trim();
  if (!token) {
    setStatus(t("sign_in_required"), true);
    return;
  }

  try {
    const data = await api("/api/auth/token-login", {
      method: "POST",
      body: JSON.stringify({ token })
    });
    state.sessionToken = data.sessionToken || "";
    localStorage.setItem("marketWebSessionToken", state.sessionToken);
    els.token.value = "";
    await hydrateSession();
    await loadMarkets();
    setStatus(t("signed_in_as", { name: state.session.playerName }));
  } catch (error) {
    setStatus(error.message, true);
  }
}

async function copyText(value, successMessage) {
  if (!value) {
    return;
  }
  try {
    await navigator.clipboard.writeText(value);
    setStatus(successMessage || t("copied"));
  } catch (_) {
    setStatus(t("clipboard_failed"), true);
  }
}

function extractToken(raw) {
  const text = String(raw || "").trim();
  if (!text) {
    return "";
  }
  const matchedLabel = text.match(/(?:token|login token)\s*[:：]\s*([A-Za-z0-9_-]{24,})/i);
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
    renderSession();
  } catch (error) {
    state.sessionToken = "";
    state.session = null;
    localStorage.removeItem("marketWebSessionToken");
    renderSession();
    throw error;
  }
}

async function loadMarkets() {
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
    await loadMarketDetail(state.selectedMarketId);
  } else {
    state.detail = null;
    renderDetail();
  }
}

async function loadMarketDetail(marketId) {
  if (!marketId) {
    state.detail = null;
    state.selectedCommodityKey = "";
    renderDetail();
    return;
  }

  try {
    state.detail = await api(`/api/markets/${marketId}`);
    state.selectedMarketId = marketId;
    syncCommoditySelection();
    renderMarkets();
    renderDetail();
  } catch (error) {
    setStatus(error.message, true);
  }
}

async function postMarketAction(suffix, payload = {}) {
  if (!state.session) {
    setStatus(t("sign_in_to_trade"), true);
    return;
  }
  if (!state.selectedMarketId) {
    return;
  }
  try {
    const data = await api(`/api/markets/${state.selectedMarketId}${suffix}`, {
      method: "POST",
      body: JSON.stringify(payload)
    });
    state.detail = data;
    syncCommoditySelection();
    setStatus(t("action_completed"));
    renderDetail();
  } catch (error) {
    setStatus(error.message, true);
  }
}

function renderSession() {
  if (!state.session) {
    els.sessionStatus.textContent = `${t("not_signed_in")} ${t("guest_mode_ready")}`;
    if (els.sessionPill) {
      els.sessionPill.textContent = t("guest_word");
    }
    return;
  }

  els.sessionStatus.textContent = `${state.session.playerName} (${state.session.online ? t("online_word") : t("offline_word")})`;
  if (els.sessionPill) {
    els.sessionPill.textContent = state.session.online ? `${state.session.playerName} ${t("online_word")}` : `${state.session.playerName} ${t("connected_word")}`;
  }
}

function renderMarkets() {
  if (!state.markets.length) {
    els.marketList.innerHTML = `<div class="empty-state">${escapeHtml(t("no_markets"))}</div>`;
    return;
  }

  els.marketList.innerHTML = state.markets.map((market) => `
    <button type="button" class="market-card ${market.marketId === state.selectedMarketId ? "active" : ""}" data-market-id="${escapeHtml(market.marketId)}">
      <p class="panel-meta">${market.loaded ? t("live_terminal") : t("chunk_cold")}</p>
      <h3>${escapeHtml(market.marketName)}</h3>
      <div class="muted">${escapeHtml(market.ownerName || "-")}</div>
      <div class="muted">${escapeHtml(market.dimensionId)} @ ${escapeHtml(market.position)}</div>
      <div class="market-meta">
        ${market.canManage ? `<span class="pill success">${escapeHtml(t("manage"))}</span>` : `<span class="pill">${escapeHtml(t("view"))}</span>`}
        ${market.loaded ? `<span class="pill">${escapeHtml(t("loaded"))}</span>` : `<span class="pill warning">${escapeHtml(t("cold"))}</span>`}
      </div>
    </button>
  `).join("");

  document.querySelectorAll("[data-market-id]").forEach((node) => {
    node.addEventListener("click", () => {
      state.commodityQuery = "";
      loadMarketDetail(node.getAttribute("data-market-id") || "");
    });
  });
}

function renderDetail() {
  const bars = [];
  if (state.status) {
    bars.push(`<div class="status-bar">${escapeHtml(state.status)}</div>`);
  }
  if (state.error) {
    bars.push(`<div class="status-bar error">${escapeHtml(state.error)}</div>`);
  }

  if (!state.detail) {
    els.marketDetail.innerHTML = `${bars.join("")}<div class="empty-state">${escapeHtml(t("select_market_prompt"))}</div>`;
    return;
  }

  const detail = state.detail;
  const canManage = !!detail.canManage;
  const canAct = !!state.session;
  const catalog = buildCommodityCatalog(detail);
  const filteredCatalog = filterCatalog(catalog);
  const selectedCommodity = getSelectedCommodity(filteredCatalog, catalog);

  els.marketDetail.innerHTML = `
    ${bars.join("")}
    <div class="market-shell">
      <section class="market-overview">
        <div class="overview-banner">
          <div class="detail-header">
            <div class="overview-title">
              <p class="section-kicker">${escapeHtml(t("market_overview"))}</p>
              <h2>${escapeHtml(detail.marketName)}</h2>
              <div class="overview-subtitle">${escapeHtml(t("owner_word"))} ${escapeHtml(detail.ownerName || "-")} · ${escapeHtml(t("dock"))} ${escapeHtml(detail.linkedDockName || "-")} · ${escapeHtml(t("town_word"))} ${escapeHtml(detail.townName || "-")}</div>
            </div>
            <div class="market-meta">
              <span class="pill ${detail.linkedDock ? "success" : "warning"}">${detail.linkedDock ? escapeHtml(t("dock_linked")) : escapeHtml(t("no_linked_dock"))}</span>
              <span class="pill">${canManage ? escapeHtml(t("manager_access")) : escapeHtml(t("read_only"))}</span>
              <span class="pill">${canAct ? escapeHtml(t("manage")) : escapeHtml(t("browse_only"))}</span>
              <span class="pill">${number((detail.listings || []).length)} ${escapeHtml(t("listings_word"))}</span>
            </div>
          </div>
          <div class="metric-strip">
            ${metricBox(t("pending_credits"), number(detail.pendingCredits))}
            ${metricBox(t("commodity_types"), number(catalog.length))}
            ${metricBox(t("storage_units"), number(detail.stockpileTotalUnits))}
            ${metricBox(t("open_demand"), number(detail.openDemandUnits))}
            ${metricBox(t("my_buy_orders_metric"), number((detail.buyOrderEntries || []).length))}
            ${state.settings.showTownEconomySummary ? metricBox(t("net_balance"), number(detail.netBalance)) : ""}
          </div>
        </div>
      </section>

      ${canAct ? "" : `<div class="status-bar">${escapeHtml(t("guest_mode_ready"))} ${escapeHtml(t("sign_in_to_trade"))}</div>`}

      <section class="goods-market">
        <div class="market-browse">
          <div class="shelf-head">
            <div>
              <p class="section-kicker">${escapeHtml(t("browse_goods"))}</p>
              <h3>${escapeHtml(t("commodity_shelf"))}</h3>
            </div>
            <div class="shelf-tools">
              <div class="sort-controls">
                ${renderCatalogSortButton("activity", t("sort_activity"))}
                ${renderCatalogSortButton("price", t("sort_price"))}
                ${renderCatalogSortButton("change", t("sort_change"))}
              </div>
              <input id="commodity-search" class="goods-search" type="search" value="${escapeHtml(state.commodityQuery)}" placeholder="${escapeHtml(t("search_placeholder"))}">
            </div>
          </div>
          ${filteredCatalog.length ? `
            <div class="goods-grid">
              ${filteredCatalog.map((commodity) => renderCommodityCard(commodity)).join("")}
            </div>
          ` : `<div class="empty-state">${escapeHtml(t("no_match"))}</div>`}
        </div>

        ${selectedCommodity ? renderCommodityDetail(selectedCommodity, detail, canManage, canAct) : `<div class="empty-state">${escapeHtml(t("no_commodity_data"))}</div>`}
      </section>
    </div>
  `;

  bindDetailActions();
  hydrateCommodityIcons();
  hydrateLightweightChart();
}

function bindDetailActions() {
  const search = document.querySelector("#commodity-search");
  if (search) {
    search.addEventListener("input", (event) => {
      state.commodityQuery = event.target.value || "";
      renderDetail();
    });
  }

  document.querySelectorAll("[data-commodity-key]").forEach((node) => {
    node.addEventListener("click", () => {
      state.selectedCommodityKey = node.getAttribute("data-commodity-key") || "";
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-catalog-sort]").forEach((node) => {
    node.addEventListener("click", () => {
      cycleCatalogSort(node.getAttribute("data-catalog-sort") || "activity");
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-tab]").forEach((node) => {
    node.addEventListener("click", () => {
      state.activeProductTab = node.getAttribute("data-tab") || "browse";
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-chart-timeframe]").forEach((node) => {
    node.addEventListener("click", () => {
      state.activeChartTimeframe = node.getAttribute("data-chart-timeframe") || "1h";
      persistChartPreferences();
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-chart-indicator]").forEach((node) => {
    node.addEventListener("click", () => {
      const key = node.getAttribute("data-chart-indicator") || "";
      if (!key) {
        return;
      }
      state.chartIndicators[key] = !state.chartIndicators[key];
      persistChartPreferences();
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-chart-flag]").forEach((node) => {
    node.addEventListener("change", (event) => {
      const key = node.getAttribute("data-chart-flag") || "";
      if (!key) {
        return;
      }
      state.chartFlags[key] = !!event.target.checked;
      persistChartPreferences();
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-chart-reset]").forEach((node) => {
    node.addEventListener("click", () => {
      if (chartState.chart) {
        chartState.chart.timeScale().fitContent();
      }
    });
  });

  const createListingButton = document.querySelector("#create-listing-button");
  if (createListingButton) {
    createListingButton.addEventListener("click", () => postMarketAction("/listings", {
      storageIndex: numberValue("#create-listing-storage", -1),
      quantity: numberValue("#create-listing-quantity", 1),
      priceAdjustmentBp: numberValue("#create-listing-adjustment", 0),
      sellerNote: valueOf("#create-listing-note")
    }));
  }

  const buyOrderButton = document.querySelector("#buy-order-button");
  if (buyOrderButton) {
    buyOrderButton.addEventListener("click", () => postMarketAction("/buy-orders", {
      commodityKey: valueOf("#buy-order-key"),
      quantity: numberValue("#buy-order-quantity", 1),
      minPriceBp: numberValue("#buy-order-min", -1000),
      maxPriceBp: numberValue("#buy-order-max", 1000)
    }));
  }

  const claimCreditsButton = document.querySelector("#claim-credits-button");
  if (claimCreditsButton) {
    claimCreditsButton.addEventListener("click", () => postMarketAction("/credits/claim"));
  }

  const dispatchButton = document.querySelector("#dispatch-button");
  if (dispatchButton) {
    dispatchButton.addEventListener("click", () => postMarketAction("/dispatch/retry"));
  }

  document.querySelectorAll("[data-purchase-index]").forEach((node) => {
    node.addEventListener("click", () => postMarketAction("/purchase", {
      listingIndex: Number(node.getAttribute("data-purchase-index") || "-1"),
      quantity: state.settings.defaultPurchaseQuantity || 1
    }));
  });

  document.querySelectorAll("[data-cancel-listing]").forEach((node) => {
    node.addEventListener("click", () => postMarketAction(`/listings/${node.getAttribute("data-cancel-listing")}/cancel`));
  });

  document.querySelectorAll("[data-cancel-buy-order]").forEach((node) => {
    node.addEventListener("click", () => postMarketAction(`/buy-orders/${node.getAttribute("data-cancel-buy-order")}/cancel`));
  });
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
    const sellUnits = sumBy(commodity.listings, "availableCount");
    const demandUnits = sumBy(commodity.buyBookEntries, "quantity");
    return {
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
  }).sort((left, right) => {
    const leftScore = (left.totalListings * 1000) + left.sellUnits + left.demandUnits;
    const rightScore = (right.totalListings * 1000) + right.sellUnits + right.demandUnits;
    return rightScore - leftScore || left.displayName.localeCompare(right.displayName);
  });
}

function filterCatalog(catalog) {
  const query = state.commodityQuery.trim().toLowerCase();
  const filtered = !query ? catalog : catalog.filter((commodity) => {
    return commodity.displayName.toLowerCase().includes(query) || commodity.commodityKey.toLowerCase().includes(query);
  });
  return sortCatalog(filtered);
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
  const mode = state.catalogSort?.mode || "activity";
  const direction = state.catalogSort?.direction || "desc";
  const factor = direction === "asc" ? 1 : -1;
  return [...catalog].sort((left, right) => {
    if (mode === "price") {
      const leftPrice = left.bestSell == null ? Number.MAX_SAFE_INTEGER : Number(left.bestSell);
      const rightPrice = right.bestSell == null ? Number.MAX_SAFE_INTEGER : Number(right.bestSell);
      const diff = leftPrice - rightPrice;
      if (diff !== 0) {
        return diff * factor;
      }
    } else if (mode === "change") {
      const diff = (commodityChangeRatio(left) ?? -Infinity) - (commodityChangeRatio(right) ?? -Infinity);
      if (diff !== 0) {
        return diff * factor;
      }
    } else {
      const diff = commodityActivityScore(left) - commodityActivityScore(right);
      if (diff !== 0) {
        return diff * factor;
      }
    }
    return left.displayName.localeCompare(right.displayName, state.locale);
  });
}

function cycleCatalogSort(mode) {
  const currentMode = state.catalogSort?.mode || "activity";
  const currentDirection = state.catalogSort?.direction || "desc";
  if (currentMode !== mode) {
    state.catalogSort = { mode, direction: "desc" };
  } else if (currentDirection === "desc") {
    state.catalogSort = { mode, direction: "asc" };
  } else {
    state.catalogSort = { mode: "activity", direction: "desc" };
  }
  persistCatalogSort();
}

function renderCatalogSortButton(mode, label) {
  const active = state.catalogSort?.mode === mode;
  const direction = active ? state.catalogSort?.direction : "";
  const indicator = direction === "desc" ? "▼" : direction === "asc" ? "▲" : "";
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
  if (!state.selectedCommodityKey || !catalog.some((entry) => entry.commodityKey === state.selectedCommodityKey)) {
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
  const endpoint = `/api/icons?commodityKey=${encodeURIComponent(commodityKey)}`;
  if (namespace === "sailboatmod") {
    return unique([
      endpoint,
      `/assets/sailboatmod/textures/item/${path}.png`,
      `/assets/sailboatmod/textures/block/${path}.png`
    ]);
  }
  return unique([
    endpoint,
    `https://assets.mcasset.cloud/1.20.1/assets/${namespace}/textures/item/${path}.png`,
    `https://assets.mcasset.cloud/1.20.1/assets/${namespace}/textures/block/${path}.png`
  ]);
}

function renderCommodityIcon(commodityKey, displayName) {
  const sources = commodityIconSources(commodityKey);
  const fallback = escapeHtml(iconLetter(displayName));
  if (!sources.length) {
    return `<div class="goods-art"><span class="goods-art-fallback">${fallback}</span></div>`;
  }
  return `
    <div class="goods-art" data-icon-shell data-commodity-key="${escapeHtml(commodityKey)}">
      <img alt="${escapeHtml(displayName)}" loading="lazy" src="${escapeHtml(sources[0])}" data-icon-sources="${escapeHtml(sources.join("|"))}" data-icon-index="0">
      <span class="goods-art-fallback">${fallback}</span>
    </div>
  `;
}

function hydrateCommodityIcons() {
  document.querySelectorAll("[data-icon-shell] img").forEach((img) => {
    if (img.dataset.bound === "true") {
      return;
    }
    img.dataset.bound = "true";
    const shell = img.closest("[data-icon-shell]");
    const sources = (img.dataset.iconSources || "").split("|").filter(Boolean);

    const markLoaded = () => {
      if (img.naturalWidth > 0 && shell) {
        shell.classList.add("has-image");
      }
    };

    const tryNext = () => {
      const nextIndex = Number(img.dataset.iconIndex || "0") + 1;
      if (nextIndex < sources.length) {
        img.dataset.iconIndex = String(nextIndex);
        img.src = sources[nextIndex];
        return;
      }
      img.remove();
    };

    img.addEventListener("load", markLoaded);
    img.addEventListener("error", tryNext);

    if (img.complete) {
      if (img.naturalWidth > 0) {
        markLoaded();
      } else {
        tryNext();
      }
    }
  });
}

function renderCommodityCard(commodity) {
  return `
    <button type="button" class="goods-card ${commodity.commodityKey === state.selectedCommodityKey ? "active" : ""}" data-commodity-key="${escapeHtml(commodity.commodityKey)}">
      ${renderCommodityIcon(commodity.commodityKey, commodity.displayName)}
      <h3>${escapeHtml(commodity.displayName)}</h3>
      <div class="goods-key">${escapeHtml(commodity.commodityKey)}</div>
      <div class="goods-summary">
        <span class="goods-badge">${number(commodity.totalListings)} ${escapeHtml(t("selling"))}</span>
        <span class="goods-badge">${number(commodity.demandUnits)} ${escapeHtml(t("buying"))}</span>
        <span class="goods-badge">${number(commodity.storageUnits)} ${escapeHtml(t("in_storage"))}</span>
      </div>
    </button>
  `;
}

function renderCommodityDetail(commodity, detail, canManage, canAct) {
  const activeSeries = primaryChartSeries(commodity);
  const latestPoint = latestChartPoint(activeSeries);
  const chartStats = chartSummary(activeSeries);
  const marketIndex = analyticsSeries(detail, "MARKET_INDEX", "global");
  const categoryIndex = analyticsSeries(detail, "CATEGORY_INDEX", firstListingCategory(commodity) || "");
  return `
    <div class="goods-detail">
      <div class="crumb-strip">
        <span>${escapeHtml(t("market"))}</span>
        <span>/</span>
        <span>${escapeHtml(detail.marketName)}</span>
        <span>/</span>
        <strong>${escapeHtml(commodity.displayName)}</strong>
      </div>

      <section class="goods-hero">
        ${renderCommodityIcon(commodity.commodityKey, commodity.displayName)}
        <div class="goods-main">
          <div class="tiny-label">${escapeHtml(t("selected_commodity"))}</div>
          <h2>${escapeHtml(commodity.displayName)}</h2>
          <div class="muted">${escapeHtml(commodity.commodityKey)}</div>
          <div class="price-line">
            ${commodity.bestSell == null ? "--" : number(commodity.bestSell)}
            <span class="minor">${escapeHtml(t("lowest_sell"))}${commodity.bestBuy == null ? "" : ` · ${escapeHtml(t("highest_buy"))} ${number(commodity.bestBuy)} bp`}</span>
          </div>
          <div class="goods-stats">
            ${metricBox(t("sell_listings"), number(commodity.totalListings))}
            ${metricBox(t("on_sale"), number(commodity.sellUnits))}
            ${metricBox(t("buying_demand"), number(commodity.demandUnits))}
            ${metricBox(t("in_storage"), number(commodity.storageUnits))}
          </div>
        </div>
        <div class="quote-panel">
          <div class="quote-box">
            <span class="quote-label">${escapeHtml(t("lowest_sell"))}</span>
            <strong>${commodity.bestSell == null ? "--" : number(commodity.bestSell)}</strong>
          </div>
          <div class="quote-box buy">
            <span class="quote-label">${escapeHtml(t("highest_buy"))}</span>
            <strong>${commodity.bestBuy == null ? "--" : number(commodity.bestBuy)}</strong>
          </div>
          <div class="quote-box neutral">
            <span class="quote-label">${escapeHtml(t("avg_24h"))}</span>
            <strong>${latestPoint ? number(latestPoint.averageUnitPrice) : "--"}</strong>
          </div>
          <div class="quote-box neutral">
            <span class="quote-label">${escapeHtml(t("trades_24h"))}</span>
            <strong>${number(chartStats.tradeCount)}</strong>
          </div>
          <div class="quote-box neutral">
            <span class="quote-label">${escapeHtml(t("reference_price"))}</span>
            <strong>${commodity.referencePrice == null ? "--" : number(commodity.referencePrice)}</strong>
          </div>
          <div class="quote-box neutral">
            <span class="quote-label">${escapeHtml(t("liquidity_score"))}</span>
            <strong>${number(commodity.liquidityScore)}</strong>
          </div>
        </div>
      </section>

      <div class="tab-strip">
        ${tabButton("browse", t("browse_tab"))}
        ${tabButton("purchase", t("purchase_tab"))}
        ${tabButton("chart", t("chart_tab"))}
        ${tabButton("market-index", t("market_index_tab"))}
      </div>

      <div class="market-tape">
        <div class="tape-card sell">
          <span>${escapeHtml(t("lowest_sell"))}</span>
          <strong>${commodity.bestSell == null ? "--" : number(commodity.bestSell)}</strong>
          <small>${number(commodity.sellUnits)} ${escapeHtml(t("units_live"))}</small>
        </div>
        <div class="tape-card buy">
          <span>${escapeHtml(t("highest_buy"))}</span>
          <strong>${commodity.bestBuy == null ? "--" : number(commodity.bestBuy)}</strong>
          <small>${number(commodity.demandUnits)} ${escapeHtml(t("units_wanted"))}</small>
        </div>
        <div class="tape-card neutral">
          <span>${escapeHtml(t("price_history"))}</span>
          <strong>${chartStats.low == null ? "--" : `${number(chartStats.low)} - ${number(chartStats.high)}`}</strong>
          <small>${number(chartStats.volume)} ${escapeHtml(t("volume_label"))}</small>
        </div>
        <div class="tape-card neutral">
          <span>${escapeHtml(t("reference_price"))}</span>
          <strong>${commodity.referencePrice == null ? "--" : number(commodity.referencePrice)}</strong>
          <small>${escapeHtml(t("liquidity_score"))} ${number(commodity.liquidityScore)}</small>
        </div>
      </div>

      <div class="market-tape">
        ${marketIndex ? renderIndexTapeCard(marketIndex, t("market_index")) : ""}
        ${categoryIndex ? renderIndexTapeCard(categoryIndex, t("category_index")) : ""}
      </div>

      ${renderCommodityTab(commodity, detail, canManage, canAct)}
    </div>
  `;
}

function renderCommodityTab(commodity, detail, canManage, canAct) {
  if (state.activeProductTab === "purchase") {
    return renderBuyingTab(commodity, canManage, canAct);
  }
  if (state.activeProductTab === "chart") {
    return renderChartTab(commodity, detail);
  }
  if (state.activeProductTab === "market-index") {
    return renderIndexTab(commodity, detail);
  }
  return renderSellingTab(commodity, detail, canManage, canAct);
}

function renderSellingTab(commodity, detail, canManage, canAct) {
  const matchingStorage = (detail.storageEntries || []).filter((entry) => normalizeCommodityKey(entry.commodityKey || entry.itemName) === commodity.commodityKey);

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
            <td>${number(entry.availableCount)}</td>
            <td>${number(entry.reservedCount)}</td>
            <td>${number(entry.unitPrice)}</td>
            <td>${number((Number(entry.unitPrice) || 0) * (Number(entry.availableCount) || 0))}</td>
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
              <select id="create-listing-storage">
                ${matchingStorage.map((entry) => `<option value="${entry.index}">${escapeHtml(entry.itemName)} x${number(entry.quantity)} · ${escapeHtml(t("suggested_word"))} ${number(entry.suggestedUnitPrice)}</option>`).join("")}
              </select>
              <input id="create-listing-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultListingQuantity || 1))}" placeholder="${escapeHtml(t("quantity"))}">
              <input id="create-listing-adjustment" type="number" value="${escapeHtml(String(state.settings.defaultPriceAdjustmentBp || 0))}" placeholder="${escapeHtml(t("price_band"))} bp">
              <textarea id="create-listing-note" placeholder="${escapeHtml(t("seller_note"))}"></textarea>
              <div class="actions">
                <button type="button" id="create-listing-button" ${canManage && canAct ? "" : "disabled"}>${escapeHtml(t("create_listing"))}</button>
                <button type="button" id="claim-credits-button" class="secondary" ${canAct ? "" : "disabled"}>${escapeHtml(t("claim_credits"))}</button>
              </div>
            </div>
          ` : `<div class="empty-state">${escapeHtml(t("no_storage_match"))}</div>`}
        </div>

        <div class="surface-card stack">
          <div class="panel-head">
            <div>
              <p class="section-kicker">${escapeHtml(t("market_notes"))}</p>
              <h3>${escapeHtml(t("sell_summary"))}</h3>
            </div>
          </div>
          <div class="summary-note">${escapeHtml(t("lowest_sell"))} ${commodity.bestSell == null ? "--" : number(commodity.bestSell)}. ${number(commodity.sellUnits)} / ${number(commodity.totalListings)}.</div>
          <div class="summary-note">${escapeHtml(detail.linkedDock ? t("dock_linked") : t("no_linked_dock"))}.</div>
          <div class="summary-note">${escapeHtml(canManage ? t("manager_access") : t("read_only"))}.</div>
          <div class="summary-note">${escapeHtml(canAct ? t("manage") : t("browse_only"))}.</div>
        </div>
      </div>
    </div>
  `;
}

function renderBuyingTab(commodity, canManage, canAct) {
  const buyRows = commodity.buyBookEntries || [];
  const ownRows = commodity.myBuyOrders || [];

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
            <input id="buy-order-key" type="text" value="${escapeHtml(commodity.commodityKey)}" placeholder="minecraft:oak_log">
            <input id="buy-order-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultBuyOrderQuantity || 1))}" placeholder="${escapeHtml(t("quantity"))}">
            <input id="buy-order-min" type="number" value="${escapeHtml(String(state.settings.defaultBuyOrderMinPriceBp ?? -1000))}" placeholder="${escapeHtml(t("min_bp"))}">
            <input id="buy-order-max" type="number" value="${escapeHtml(String(state.settings.defaultBuyOrderMaxPriceBp ?? 1000))}" placeholder="${escapeHtml(t("max_bp"))}">
            <div class="actions">
              <button type="button" id="buy-order-button" ${canAct ? "" : "disabled"}>${escapeHtml(t("create_buy_order"))}</button>
              <button type="button" id="dispatch-button" class="warn" ${canManage && canAct ? "" : "disabled"}>${escapeHtml(t("retry_dispatch"))}</button>
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

function renderChartTab(commodity, detail) {
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
              <div id="lw-chart" class="chart-canvas" data-chart-key="${escapeHtml(commodity.commodityKey)}"></div>
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

function renderIndexTab(commodity, detail) {
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
            <div id="market-index-chart" class="macro-chart"></div>
          </div>
        ` : ""}
        ${categoryIndex?.points?.length ? `
          <div class="macro-chart-card">
            <div class="panel-subtitle">${escapeHtml(t("category_index_chart"))}</div>
            <div id="category-index-chart" class="macro-chart"></div>
          </div>
        ` : ""}
        ${cpiSeries?.points?.length ? `
          <div class="macro-chart-card">
            <div class="panel-subtitle">${escapeHtml(t("cpi_chart"))}</div>
            <div id="cpi-chart" class="macro-chart macro-chart-compact"></div>
          </div>
        ` : ""}
        ${loansSeries?.points?.length ? `
          <div class="macro-chart-card">
            <div class="panel-subtitle">${escapeHtml(t("loans_chart"))}</div>
            <div id="loans-chart" class="macro-chart macro-chart-compact"></div>
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

function tabButton(key, label) {
  return `<button type="button" class="tab-button ${state.activeProductTab === key ? "active" : ""}" data-tab="${key}">${escapeHtml(label)}</button>`;
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
}

function hydrateLightweightChart() {
  destroyLightweightChart();
  if (!state.detail || !state.settings.showPriceCharts) {
    return;
  }
  const detail = state.detail;
  const catalog = buildCommodityCatalog(detail);
  const commodity = getSelectedCommodity(filterCatalog(catalog), catalog);
  if (state.activeProductTab === "market-index") {
    hydrateMacroCharts(detail, commodity);
    return;
  }
  if (state.activeProductTab !== "chart") {
    return;
  }
  const container = document.querySelector("#lw-chart");
  if (!container) {
    return;
  }
  if (!commodity) {
    setChartFailure("no-selection", "No selected commodity while hydrating chart.");
    renderChartFallbackMessage(container, "chart_no_selection");
    hydrateMacroCharts(detail, null);
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
    hydrateMacroCharts(detail, commodity);
    return;
  }
  clearChartFailure();

  let chart;
  try {
    chart = LightweightCharts.createChart(container, {
      autoSize: true,
      layout: {
        background: { color: "#fbfdff" },
        textColor: "#526070"
      },
      grid: {
        vertLines: { color: "rgba(125, 139, 161, 0.14)" },
        horzLines: { color: "rgba(125, 139, 161, 0.14)" }
      },
      rightPriceScale: {
        borderColor: "rgba(125, 139, 161, 0.28)"
      },
      timeScale: {
        borderColor: "rgba(125, 139, 161, 0.28)",
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
    upColor: "#22c55e",
    downColor: "#ef4444",
    borderVisible: false,
    wickUpColor: "#16a34a",
    wickDownColor: "#dc2626"
  });
  chartState.volumeSeries = chart.addHistogramSeries({
    priceFormat: { type: "volume" },
    priceScaleId: "",
    color: "rgba(59, 130, 246, 0.35)"
  });
  chart.priceScale("").applyOptions({
    scaleMargins: { top: 0.76, bottom: 0 }
  });
  chartState.maShortSeries = chart.addLineSeries({
    color: "#f59e0b",
    lineWidth: 2,
    priceLineVisible: false,
    lastValueVisible: false
  });
  chartState.maLongSeries = chart.addLineSeries({
    color: "#38bdf8",
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
      borderColor: "rgba(125, 139, 161, 0.28)",
      mode: state.chartFlags.logScale ? LightweightCharts.PriceScaleMode.Logarithmic : LightweightCharts.PriceScaleMode.Normal
    }
  });
  chartState.candleSeries.setData(priceData);
  chartState.volumeSeries.setData(state.chartIndicators.volume ? volumeData : []);
  chartState.maShortSeries.setData(state.chartIndicators.ma5 ? movingAverageData(priceData, 5) : []);
  chartState.maLongSeries.setData(state.chartIndicators.ma20 ? movingAverageData(priceData, 20) : []);
  chart.timeScale().fitContent();
  bindChartCrosshair(chart, hoverPoints);
  hydrateMacroCharts(detail, commodity);

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
  chartState.marketIndexChart = createMacroChart("#market-index-chart", marketIndex, "#2563eb");
  chartState.categoryIndexChart = createMacroChart("#category-index-chart", categoryIndex, "#7c3aed");
  chartState.cpiChart = createMacroChart("#cpi-chart", cpiSeries, "#a855f7");
  chartState.loansChart = createMacroChart("#loans-chart", loansSeries, "#ea580c");
}

function createMacroChart(selector, series, color) {
  const container = document.querySelector(selector);
  if (!container || !series?.points?.length || typeof LightweightCharts === "undefined") {
    return null;
  }
  const chart = LightweightCharts.createChart(container, {
    autoSize: true,
    layout: {
      background: { color: "#fbfdff" },
      textColor: "#526070"
    },
    grid: {
      vertLines: { visible: false },
      horzLines: { color: "rgba(125, 139, 161, 0.12)" }
    },
    leftPriceScale: { visible: false },
    rightPriceScale: { borderColor: "rgba(125, 139, 161, 0.22)" },
    timeScale: { borderColor: "rgba(125, 139, 161, 0.22)", timeVisible: true, secondsVisible: false }
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

function valueOf(selector) {
  return document.querySelector(selector)?.value?.trim() || "";
}

function numberValue(selector, fallback) {
  const value = Number(valueOf(selector));
  return Number.isFinite(value) ? value : fallback;
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

function sumBy(entries, field) {
  return entries.reduce((sum, entry) => sum + (Number(entry?.[field]) || 0), 0);
}

function number(value) {
  return Number(value || 0).toLocaleString(state.locale);
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

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "\"": "&quot;",
    "'": "&#39;"
  }[char]));
}

els.loginButton.addEventListener("click", login);
els.copyCommandButton?.addEventListener("click", () => {
  copyText(state.settings.commandText || "/marketweb token", t("command_copied"));
});
els.refreshMarkets.addEventListener("click", async () => {
  try {
    await loadMarkets();
    setStatus(t("markets_refreshed"));
  } catch (error) {
    setStatus(error.message, true);
  }
});
els.localeSelect?.addEventListener("change", (event) => {
  state.locale = event.target.value || "zh-CN";
  localStorage.setItem("marketWebLocale", state.locale);
  updateStaticCopy();
  renderMarkets();
  renderDetail();
});
els.token.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    login();
  }
});
els.token.addEventListener("input", () => {
  syncTokenFromCapture(false);
});
els.chatCapture?.addEventListener("input", () => {
  syncTokenFromCapture(true);
});

(async function init() {
  await loadSettings();
  renderSession();
  renderMarkets();
  renderDetail();
  if (!state.sessionToken) {
    try {
      await loadMarkets();
    } catch (error) {
      setStatus(error.message, true);
    }
    return;
  }
  try {
    await hydrateSession();
    await loadMarkets();
    setStatus(t("signed_in_as", { name: state.session.playerName }));
  } catch (error) {
    setStatus(error.message, true);
  }
})();
