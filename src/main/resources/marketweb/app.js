const state = {
  sessionToken: localStorage.getItem("marketWebSessionToken") || "",
  locale: localStorage.getItem("marketWebLocale") || "zh-CN",
  session: null,
  webResourceVersion: "",
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
    maxPrice: ""
  }),
  catalogExpandedGroup: "all",
  catalogHoverGroup: "all",
  detail: null,
  selectedDispatchOrderIndex: 0,
  selectedDispatchTerminal: localStorage.getItem("marketWebDispatchTerminal") || "PORT",
  settings: {
    uiTitle: "Monpai Online Market · Monpai在线市场",
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
const PAGE_ROUTES = new Set(["browse", "inventory", "sell", "buy", "chart", "index"]);
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

function persistCatalogFilters() {
  localStorage.setItem("marketWebCatalogFilters", JSON.stringify(state.catalogFilters));
}

function normalizePageRoute(value) {
  const route = String(value || "").trim().toLowerCase();
  return PAGE_ROUTES.has(route) ? route : "browse";
}

function routePath(route = state.activeProductTab) {
  return `/${normalizePageRoute(route)}`;
}

function routeFromLocation(pathname) {
  const raw = String(pathname || "/").trim().replace(/^\/+|\/+$/g, "");
  return normalizePageRoute(raw || "browse");
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
  /*
  "zh-CN": {
    hero_title: "Monpai閸︺劎鍤庣敮鍌氭簚",
    hero_subtitle: "閸︺劏绻栭柌宀冪箻鐞涘瞼鍤庢稉濠勬畱閻椻晛鎼ф禍銈嗘閸戝搫鏁敍灞肩稑閻㈡俺鍤﹂柈鎴掔瑝閻劏绻橀崗銉︽箛閸斺€虫珤,閸︺劋绗呴弬褰掆偓澶嬪娴ｇ姷娈戦崯鍡楁惂閿?,
    mode_label: "濡€崇础",
    mode_value: "閸熷棗鎼х敮鍌氭簚",
    session_label: "娴兼俺鐦?,
    flow_label: "濞翠胶鈻?,
    flow_value: "娴犮倗澧濋惂璇茬秿",
    portal_kicker: "缂冩垿銆夐惂璇茬秿",
    auth_title: "妫ｆ牗顐奸悽?token 缂佹垵鐣鹃敍灞芥倵缂侇厾顬囩痪璺ㄦ瑜?,
    auth_subtitle: "閸忓牆婀〒鍛婂灆闁插本澧界悰灞肩濞嗏€虫嚒娴犮倖瀣侀崚?token閿涘本濡哥€瑰啰绮︾€规艾鍩岀純鎴︺€夌拹锕€褰块敍灞肩閸氬骸姘ㄩ崣顖欎簰閻╁瓨甯撮悽銊ㄥ閸欏嘲鐦戦惍浣烘瑜版洏鈧?,
    command_label: "濞撳憡鍨欓崘鍛嚒娴?,
    copy_command: "婢跺秴鍩楅崨鎴掓姢",
    fast_signin: "韫囶偊鈧喓娅ヨぐ?,
    connect_player: "缂佹垵鐣剧拹锕€褰块幋鏍瑜?,
    not_signed_in: "閺堫亞娅ヨぐ鏇樷偓?,
    paste_chat: "缁鍒涢懕濠傘亯鏉堟挸鍤幋?token",
    paste_chat_placeholder: "閹跺﹤鐣弫纾嬩喊婢垛晞顢戠划妯垮垱閸掓媽绻栭柌宀嬬礉妞ょ敻娼版导姘冲殰閸斻劏鐦戦崚?token閵?,
    token_placeholder: "Token 娴兼俺鍤滈崝銊ュ毉閻滄澘婀潻娆撳櫡",
    account_username_placeholder: "鐠愶箑褰块崥?,
    account_password_placeholder: "鐎靛棛鐖?,
    bind_account: "缂佹垵鐣剧拹锕€褰?,
    account_login: "鐠愶箑褰块惂璇茬秿",
    token_helper: "妫ｆ牗顐肩紒鎴濈暰閿涙氨鐭樼拹?token閿涘矁顔曠純顔垮閸欏嘲鎷扮€靛棛鐖滈敍宀€鍔ч崥搴ｇ拨鐎规哎鈧倷绠ｉ崥搴″讲閻╁瓨甯撮悽銊ㄥ閸欏嘲鐦戦惍浣烘瑜版洏鈧?,
    market_network: "鐢倸婧€缂冩垹绮?,
    browse_markets: "濞村繗顫嶇敮鍌氭簚",
    refresh: "閸掗攱鏌?,
    market_sidebar_hint: "閸忓牓鈧绔存稉顏勭閸﹁櫣绮撶粩顖ょ礉閸愬秴鍎氶崯鍡楁惂鐢倸婧€娑撯偓閺嶉攱绁荤憴鍫濆従鐠愌呭⒖楠炶埖澧﹀鈧崡鏇炴櫌閸濅線銆夐妴?,
    brand_kicker: "Sailboat 鐢倸婧€娴溿倖妲楅幍鈧?,
    language_label: "鐠囶叀鈻?,
    empty_select_market: "闁瀚ㄦ稉鈧稉顏勭閸﹁櫣绮撶粩顖欎簰濞村繗顫嶉崯鍡楁惂閵嗕焦瀵曢崡鏇樷偓浣圭湴鐠愵厼鎷版禒閿嬬壐鐠ф澘濞嶉妴?,
    market_overview: "鐢倸婧€濮掑倽顫?,
    browse_goods: "濞村繗顫嶉崯鍡楁惂",
    commodity_shelf: "閸熷棗鎼х拹褎鐏?,
    search_placeholder: "閹兼粎鍌ㄩ悧鈺佹惂閸氬秵鍨?commodity key",
    price_range: "娴犻攱鐗搁崠娲？",
    min_price: "閺堚偓娴ｅ簼鐜?,
    max_price: "閺堚偓妤傛ü鐜?,
    sort_name: "閸氬秶袨",
    sort_price: "娴犻攱鐗?,
    sort_time: "閺冨爼妫?,
    sort_quantity: "閺佷即鍣?,
    all_types: "閸忋劑鍎寸猾璇茬€?,
    all_rarities: "閸忋劑鍎寸粙鈧張澶婂",
    rarity_filter: "缁嬧偓閺堝瀹?,
    sort_label: "閹烘帒绨?,
    inventory_tab: "鎼存挸鐡?,
    inventory_title: "鍩庨晣浠撳簱",
    no_inventory_rows: "瑜版挸澧犵紒鍫㈩伂濞屸剝婀侀崣顖氱潔缁€铏规畱鎼存挸鐡ㄩ妴?,
    inventory_requires_manage: "閸欘亝婀佽ぐ鎾冲閻礁銇旈幋鏍х閸﹁櫣娈戠粻锛勬倞閼板懎褰叉禒銉︾叀閻绨辩€涙ǜ鈧?,
    category_all: "閸忋劑鍎?,
    category_wood: "閺堛劍娼?,
    category_luxury: "婵傤澀绶?,
    category_food: "妞嬬喓澧?,
    category_ore: "閻法鐓?,
    category_gems: "鐎规繄鐓?,
    category_metal: "闁叉垵鐫?,
    category_tools: "瀹搞儱鍙?,
    category_spices: "妫ｆ瑦鏋?,
    category_plant: "濡炲秶澧?,
    category_crop: "閸愭粈缍旈悧?,
    category_material: "閺夋劖鏋?,
    category_mob_drop: "閹亞澧块幒澶庢儰",
    category_alchemy: "閻愯壈宓?,
    category_building: "瀵よ櫣鐡?,
    category_nether: "娑撳鏅?,
    category_end: "閺堫偄婀?,
    category_treasure: "鐎规繄澧?,
    category_redstone: "缁俱垻鐓?,
    category_utility: "鐎圭偟鏁?,
    category_weapon: "濮濓箑娅?,
    category_armor: "闂冩彃鍙?,
    category_other: "閸忔湹绮?,
    rarity_common: "閺咁噣鈧?,
    rarity_uncommon: "缂冩洝顫?,
    rarity_rare: "缁嬧偓閺?,
    rarity_epic: "閸欒尪鐦?,
    rarity_legend: "娴肩姾顕?,
    rarity_extraordinary: "闂堢偛鍤?,
    no_match: "濞屸剝婀侀崠褰掑帳鏉╂瑤閲滅粵娑⑩偓澶嬫蒋娴犲墎娈戦崯鍡楁惂閵?,
    selected_commodity: "瑜版挸澧犻崯鍡楁惂",
    lowest_sell: "閺堚偓娴ｅ骸婀崬?,
    highest_buy: "閺堚偓妤傛ɑ鐪扮拹?,
    avg_24h: "24鐏忓繑妞傞崸鍥︾幆",
    trades_24h: "24鐏忓繑妞傞幋鎰唉",
    sell_listings: "閸︺劌鏁悰灞炬殶",
    on_sale: "閸︺劌鏁弫浼村櫤",
    buying_demand: "濮瑰倽鍠橀棁鈧Ч?,
    in_storage: "娴犳挸鍋嶆惔鎾崇摠",
    browse_tab: "濞村繗顫嶉崯鍡楁惂",
    purchase_tab: "鐠愵厺鎷遍崯鍡楁惂",
    chart_tab: "K缁惧灝娴?,
    market_index_tab: "婢堆呮磸閹稿洦鏆?,
    units_live: "閸楁洑缍呴崷銊ユ暛",
    units_wanted: "閸楁洑缍呭Ч鍌濆枠",
    volume_label: "閹存劒姘﹂柌?,
    selling: "閸︺劌鏁?,
    buying: "濮瑰倽鍠?,
    my_buy_orders: "閹存垹娈戝Ч鍌濆枠閸?,
    price_history: "娴犻攱鐗搁崢鍡楀蕉",
    recent_buckets: "閺堚偓鏉╂垵鍨庨弮鑸点€?,
    chart_context: "閸ユ崘銆冮悳顖氼暔",
    sell_item: "娑撳﹥鐏﹂崯鍡楁惂",
    create_listing: "閸掓稑缂撻幐鍌氬礋",
    create_buy_order: "閸掓稑缂撳Ч鍌濆枠閸?,
    claim_credits: "妫板棗褰囩拹褎顑?,
    retry_dispatch: "閲嶈瘯鍙戣揣",
    terminal_type_label: "鍙戣揣缁堢",
    terminal_type_port: "娓彛",
    terminal_type_post_station: "椹跨珯",
    market_notes: "鐢倸婧€鐠囧瓨妲?,
    sell_summary: "閸楁牜娲忛幗妯款洣",
    demand_summary: "娑旀壆娲忓ù浣稿З閹?,
    market_snapshot: "鐢倸婧€韫囶偆鍙?,
    no_commodity_data: "鏉╂瑤閲滅敮鍌氭簚閻╊喖澧犳潻妯荤梾閺堝鏅㈤崫浣规殶閹诡喓鈧?,
    no_selling_rows: "鏉╂瑤閲滈崯鍡楁惂瑜版挸澧犲▽鈩冩箒閸︺劌鏁幐鍌氬礋閵?,
    no_buy_rows: "鏉╂瑤閲滈崯鍡楁惂瑜版挸澧犲▽鈩冩箒閸忣剙绱戝Ч鍌濆枠閵?,
    no_my_buy_rows: "娴ｇ姾绻曞▽鈩冩箒鏉╂瑤閲滈崯鍡楁惂閻ㄥ嫭鐪扮拹顓炲礋閵?,
    no_chart_rows: "鏉╂瑤閲滈崯鍡楁惂鏉╂ɑ鐥呴張澶夌幆閺嶈壈顔囪ぐ鏇樷偓?,
    no_storage_match: "瑜版挸澧犲▽鈩冩箒娑撳氦顕氶崯鍡楁惂閸栧綊鍘ら惃鍕垳婢剁繝绮ㄩ崒銊︽蒋閻╊喓鈧?,
    seller_note: "閸楁牕顔嶆径鍥ㄦ暈",
    suggested_word: "瀵ら缚顔?,
    min_bp: "閺堚偓娴?bp",
    max_bp: "閺堚偓妤?bp",
    to_word: "閸?,
    offline_word: "缁傝崵鍤?,
    online_word: "閸︺劎鍤?,
    connected_word: "瀹歌尪绻涢幒?,
    signed_in_as: "瀹歌尙娅ヨぐ鏇氳礋 {name}閵?,
    sign_in_required: "闂団偓鐟曚礁鍘涙潏鎾冲弳閻ц缍?token閵?,
    bind_requires_credentials: "缂佹垵鐣剧拹锕€褰块弮鍫曟付鐟曚礁鎮撻弮璺猴綖閸愭瑨澶勯崣宄版倳閸滃苯鐦戦惍浣碘偓?,
    account_login_required: "鐠囩柉绶崗銉ㄥ閸欏嘲鎮曢崪灞界槕閻降鈧?,
    account_bound: "瀹歌尙绮︾€规俺澶勯崣?{name}閵?,
    account_login_success: "瀹告煡鈧俺绻冪拹锕€褰?{name} 閻ц缍嶉妴?,
    sign_in_to_trade: "閻ц缍嶉崥搴㈠閼冲€熺箻鐞涘矁鍠樻稊鑸偓浣风瑐閺嬭翰鈧焦鐪扮拹顓溾偓浣稿絿濞戝牆鎷版０鍡楀絿缁涘鎼锋担婧库偓?,
    copied: "瀹告彃顦查崚韬测偓?,
    command_copied: "閸涙垝鎶ゅ鎻掝槻閸掕绱濈拠宄版躬濞撳憡鍨欓崘鍛⒔鐞涘被鈧?,
    token_extracted: "瀹稿弶褰侀崣?token 楠炶泛顦查崚璺哄煂閸擃亣鍒涢弶瑁も偓?,
    clipboard_failed: "閺冪姵纭剁拋鍧楁６閸擃亣鍒涢弶鍖＄礉鐠囬攱澧滈崝銊ヮ槻閸掕翰鈧?,
    markets_refreshed: "鐢倸婧€瀹告彃鍩涢弬鑸偓?,
    action_completed: "閹垮秳缍斿鎻掔暚閹存劑鈧?,
    sign_in_to_load_markets: "閻ц缍嶉崥搴″祮閸欘垱澧界悰灞界閸︾儤鎼锋担婧库偓?,
    guest_mode_ready: "鐠佸灝顓瑰Ο鈥崇础閿涙艾褰叉禒銉︾セ鐟欏牆缍嬮崜宥呯閸﹀搫鎷伴崯鍡楁惂閿涘奔绲炬稉宥堝厴鏉╂稖顢戦幙宥勭稊閵?,
    no_markets: "鏉╂ɑ鐥呴張澶嬫暈閸愬奔鎹㈡担鏇炵閸﹁櫣绮撶粩顖樷偓?,
    select_market_prompt: "闁瀚ㄦ稉鈧稉顏勭閸﹁櫣绮撶粩顖涙降濞村繗顫嶇€瑰啰娈戦崯鍡楁惂閻╊喖缍嶉妴?,
    guest_word: "鐠佸灝顓?,
    live_terminal: "閸︺劎鍤庣紒鍫㈩伂",
    chunk_cold: "閸栧搫娼￠張顏勫鏉?,
    manage: "閸欘垳顓搁悶?,
    view: "娴犲懏鐓￠惇?,
    loaded: "瀹告彃濮炴潪?,
    cold: "閺堫亜濮炴潪?,
    dock_linked: "宸茬粦瀹氫粨搴?,
    no_linked_dock: "鏈粦瀹氫粨搴?,
    manager_access: "缁狅紕鎮婇弶鍐",
    read_only: "閸欘亣顕?,
    browse_only: "娴犲懏绁荤憴?,
    listings_word: "閹稿倸宕?,
    market: "鐢倸婧€",
    quantity: "閺佷即鍣?,
    total: "閹鐜?,
    actions: "閹垮秳缍?,
    seller: "閸楁牕顔?,
    buyer: "娑旀澘顔?,
    available: "閸欘垰鏁?,
    reserved: "妫板嫮鏆€",
    unit_price: "閸楁洑鐜?,
    dock: "鐮佸ご",
    warehouse_word: "浠撳簱",
    status: "閻樿埖鈧?,
    price_band: "娴犻攱鐗搁崠娲？",
    implied_bid: "閹恒劎鐣婚崙杞扮幆",
    time: "閺冨爼妫?,
    average: "閸у洣鐜?,
    low: "娴?,
    high: "妤?,
    volume: "闁?,
    buy_1: "鐠愵厺鎷?1",
    cancel: "閸欐牗绉?,
    current_sell_side: "瑜版挸澧犻崡鏍磸",
    current_buy_side: "瑜版挸澧犳稊鎵磸",
    dock_stock: "浠撳簱搴撳瓨",
    terminal_status: "缂佸牏顏悩鑸碘偓?,
    chart_disabled: "闁板秶鐤嗗鎻掑彠闂傤厺鐜弽鐓庢禈鐞涖劊鈧?,
    no_chart_buckets: "鏉╂瑤閲滈崯鍡楁惂鏉╂ɑ鐥呴張澶夌幆閺嶇厧鍨庡鑸垫殶閹诡喓鈧?,
    chart_no_selection: "瑜版挸澧犲▽鈩冩箒閸欘垰鐫嶇粈鍝勬禈鐞涖劎娈戦崯鍡楁惂閵?,
    chart_library_missing: "閸ユ崘銆冩惔鎾存弓閸旂姾娴囬敍灞藉嚒閸掑洦宕叉稉鍝勫敶缂冪攤缁炬寧瑕嗛弻鎾扁偓?,
    chart_init_failed: "閸ユ崘銆冮崚婵嗩潗閸栨牕銇戠拹銉礉瀹告彃鍨忛幑顫礋閸愬懐鐤咾缁炬寧瑕嗛弻鎾扁偓?,
    chart_render_failed: "閸ユ崘銆冨〒鍙夌厠婢惰精瑙﹂妴?,
    no_demand_ladder: "鏉╂瑤閲滈崯鍡楁惂閻╊喖澧犳潻妯荤梾閺堝鑸伴幋鎰湴鐠愵厽顫梼鐔粹偓?,
    waiting_reference: "閺嗗倹妫ら弰搴ｂ€橀崷銊ユ暛娴犲嘲寮懓鍐︹偓?,
    around_current_ask: "閹稿缍嬮崜宥呭礌娴犻攱甯圭粻妤€銇囬懛鏉戞躬 {range}閵?,
    no_storage: "閺冪姳绮ㄩ崒?,
    storage_rows: "娴犳挸鍋嶇悰?,
    live_requests: "濞叉槒绌Ч鍌濆枠",
    stock_rows: "娴犳挸鍋嶉弶锛勬窗",
    owner_word: "閹碘偓閺堝鈧?,
    town_word: "閸╁酣鏅?,
    pending_credits: "瀵板懘顣拹褎顑?,
    commodity_types: "閸熷棗鎼х粔宥囪",
    storage_units: "娴犳挸鍋嶉幀濠氬櫤",
    open_demand: "瀵偓閺€楣冩付濮?,
    my_buy_orders_metric: "閹存垹娈戝Ч鍌濆枠閸?,
    net_balance: "閸戔偓娴ｆ瑩顤?,
    reference_price: "閸欏倽鈧啩鐜?,
    liquidity_score: "濞翠礁濮╅幀?,
    market_index: "婢堆呮磸閹稿洦鏆?,
    category_index: "閸掑棛琚幐鍥ㄦ殶",
    pressure_model: "娴犻攱鐗歌ぐ鍗炴惙濡€崇€?,
    inventory_pressure: "鎼存挸鐡ㄩ崢瀣",
    buy_pressure: "娑旀壆娲忛崢瀣",
    volatility_word: "濞夈垹濮?,
    timeframe: "閸涖劍婀?,
    indicators: "閹稿洦鐖?,
    chart_controls: "閸ユ崘銆冮幒褍鍩?,
    log_scale: "鐎佃鏆熼崸鎰垼",
    inflation_adjust: "闁俺鍎夋穱顔筋劀",
    inflation_unavailable: "瑜版挸澧犲▽鈩冩箒 CPI 閺佺増宓侀敍灞炬畯娑撳秴褰查悽銊ｂ偓?,
    reset_zoom: "闁插秶鐤嗙紓鈺傛杹",
    current_change: "瑜版挸澧犲☉銊ㄧ┘",
    max_drawdown: "閺堚偓婢堆冩礀閹?,
    inception_return: "鐠у嘲顫愰弨鍓佹抄",
    chart_hover_empty: "缁夎濮╅崚鏉挎禈鐞涖劋绗傞弻銉ф箙 OHLC 娑撳孩鍨氭禍銈夊櫤",
    market_index_chart: "鐢倸婧€閹稿洦鏆熺挧鏉垮◢",
    category_index_chart: "閸掑棛琚幐鍥ㄦ殶鐠ф澘濞?,
    cpi_chart: "濞戝牐鍨傛禒閿嬬壐閹稿洦鏆?,
    loans_chart: "閺堫亜浼╃拹閿嬵儥鐠ф澘濞?,
    inflation_since_inception: "缁鳖垵顓搁柅姘冲剦",
    loans_change: "鐠愰攱顑欓崣妯哄",
    cpi_now: "瑜版挸澧?CPI",
    macro_overview: "鐎瑰繗顫囧鍌濐潔"
  },
  */
  "zh-CN": {},
  "en-US": {
    hero_title: "Monpai Online Market",
    hero_subtitle: "Trade and sell items online here without even entering the server. Pick your goods below.",
    mode_label: "Mode",
    mode_value: "Commodity Market",
    session_label: "Session",
    flow_label: "Flow",
    flow_value: "Token Login",
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
    market_sidebar_hint: "Choose one market terminal, then browse its commodities like a marketplace and drill into one item page.",
    brand_kicker: "Monpai Online Market",
    language_label: "Language",
    empty_select_market: "Select a market terminal to browse commodities, listings, buy orders, and price history.",
    market_overview: "Market Overview",
    browse_goods: "Browse Goods",
    commodity_shelf: "Commodity Shelf",
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
    dock_linked: "Warehouse linked",
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
    buy_1: "Buy 1",
    cancel: "Cancel",
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
  hero_subtitle: "游客也可以浏览市场、商品与价格走势；登录后才能执行绑定、上架、求购与发货等操作。",
  mode_label: "模式",
  mode_value: "商品市场",
  session_label: "会话",
  flow_label: "流程",
  flow_value: "网页账户登录",
  portal_kicker: "网页登录",
  auth_title: "先绑定网页账户，再离线登录",
  auth_subtitle: "首次使用时，从游戏中获取 token，设置网页账号和密码完成绑定。之后可直接用账号密码登录。",
  command_label: "游戏内命令",
  copy_command: "复制命令",
  fast_signin: "快速登录",
  connect_player: "绑定账户或登录",
  not_signed_in: "未登录。",
  paste_chat: "粘贴聊天输出或 token",
  paste_chat_placeholder: "把完整聊天消息粘贴到这里，页面会自动提取 token。",
  token_placeholder: "Token 会自动显示在这里",
  account_username_placeholder: "账户名",
  account_password_placeholder: "密码",
  bind_account: "绑定账户",
  account_login: "账户登录",
  token_helper: "首次使用：粘贴 token，填写账户名和密码后点击绑定。之后可直接使用账户名和密码登录。",
  market_network: "市场网络",
  browse_markets: "浏览市场",
  refresh: "刷新",
  market_sidebar_hint: "选择一个商城终端后，即可浏览其商品目录、在售挂单、求购订单和价格走势。",
  brand_kicker: "Monpai 在线市场",
  language_label: "语言",
  empty_select_market: "选择一个商城终端来浏览商品、挂单、求购和价格走势。",
  market_overview: "市场概览",
  browse_goods: "浏览商品",
  commodity_shelf: "商品货架",
  search_placeholder: "搜索物品名或商品键",
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
  lowest_sell: "最低在售",
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
  volume_label: "成交量",
  selling: "在售",
  buying: "求购",
  my_buy_orders: "我的求购单",
  price_history: "价格历史",
  recent_buckets: "近期分桶",
  chart_context: "图表环境",
  sell_item: "上架商品",
  create_listing: "创建挂单",
  create_buy_order: "创建求购单",
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
  suggested_word: "建议价",
  min_bp: "最低 bp",
  max_bp: "最高 bp",
  to_word: "到",
  offline_word: "离线",
  online_word: "在线",
  connected_word: "已连接",
  signed_in_as: "当前登录为 {name}。",
  sign_in_required: "需要先输入登录 token。",
  bind_requires_credentials: "绑定账户需要填写账户名和密码。",
  account_login_required: "登录需要填写账户名和密码。",
  account_bound: "已绑定账户 {name}。",
  account_login_success: "已使用账户 {name} 登录。",
  sign_in_to_trade: "登录后才能购买、上架、求购、取消、领取货款或执行发货。",
  copied: "已复制。",
  command_copied: "命令已复制，请回到游戏内执行。",
  token_extracted: "已提取 token 并复制到剪贴板。",
  clipboard_failed: "无法访问剪贴板，请手动复制。",
  markets_refreshed: "市场列表已刷新。",
  action_completed: "操作已完成。",
  sign_in_to_load_markets: "游客可浏览市场，登录后解锁交易和发货操作。",
  guest_mode_ready: "访客模式：可以浏览市场与商品，但不能执行交易操作。",
  no_markets: "当前还没有注册任何市场终端。",
  select_market_prompt: "选择一个市场终端来浏览它的商品目录。",
  guest_word: "访客",
  live_terminal: "在线终端",
  chunk_cold: "区块未加载",
  manage: "可管理",
  view: "仅查看",
  loaded: "已加载",
  cold: "未加载",
  dock_linked: "已绑定港口",
  no_linked_dock: "未绑定港口",
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
  low: "最低",
  high: "最高",
  volume: "成交量",
  buy_1: "购买 1",
  cancel: "取消",
  current_sell_side: "当前卖盘",
  current_buy_side: "当前买盘",
  dock_stock: "仓储库存",
  terminal_status: "终端状态",
  chart_disabled: "配置已关闭价格图表。",
  no_chart_buckets: "当前商品还没有价格分桶数据。",
  chart_no_selection: "当前没有可展示图表的商品。",
  chart_library_missing: "图表库未加载，已切换到内置 K 线渲染。",
  chart_init_failed: "图表初始化失败，已切换到内置 K 线渲染。",
  chart_render_failed: "图表渲染失败。",
  no_demand_ladder: "当前商品还没有形成求购梯队。",
  waiting_reference: "暂无明确在售价参考。",
  around_current_ask: "按当前卖价推算大致在 {range}。",
  no_storage: "无仓储",
  storage_rows: "仓储条目",
  live_requests: "活跃求购",
  stock_rows: "库存条目",
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
  inflation_unavailable: "当前没有 CPI 数据，暂不可用。",
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
  return state.settings.uiTitle || "Monpai Online Market · Monpai在线市场";
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
  setNodeText(els.bindButton, t("bind_account"));
  setNodeText(els.loginButton, t("account_login"));
  setNodeText(els.tokenHelper, t("token_helper"));
  setNodeText(els.marketNetworkKicker, t("market_network"));
  setNodeText(els.browseMarketsTitle, t("browse_markets"));
  setNodeText(els.refreshMarkets, t("refresh"));
  setNodeText(els.marketSidebarHint, t("market_sidebar_hint"));
  setNodePlaceholder(els.chatCapture, t("paste_chat_placeholder"));
  setNodePlaceholder(els.token, t("token_placeholder"));
  setNodePlaceholder(els.accountUsername, t("account_username_placeholder"));
  setNodePlaceholder(els.accountPassword, t("account_password_placeholder"));
  if (els.accountUsername && state.accountUsername) {
    els.accountUsername.value = state.accountUsername;
  }
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
    setStatus(t("account_login_success", { name: data.accountUsername || username }));
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

async function loadMarkets(historyMode = "replace") {
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
    await loadMarketDetail(state.selectedMarketId, historyMode);
  } else {
    state.detail = null;
    renderDetail();
  }
}

async function loadMarketDetail(marketId, historyMode = "replace") {
  if (!marketId) {
    state.detail = null;
    state.selectedCommodityKey = "";
    syncRouteUrl(historyMode !== "push");
    renderDetail();
    return;
  }

  try {
    state.detail = await api(`/api/markets/${marketId}`);
    state.selectedMarketId = marketId;
    syncCommoditySelection();
    syncRouteUrl(historyMode !== "push");
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
    syncRouteUrl(true);
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

  const accountPart = state.session.accountBound && state.session.accountUsername
    ? ` · @${state.session.accountUsername}`
    : "";
  els.sessionStatus.textContent = `${state.session.playerName} (${state.session.online ? t("online_word") : t("offline_word")})${accountPart}`;
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
      state.activeProductTab = "browse";
      state.selectedCommodityKey = "";
      state.catalogHoverGroup = "all";
      state.catalogExpandedGroup = currentCatalogActiveGroup();
      loadMarketDetail(node.getAttribute("data-market-id") || "", "push");
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
  if (state.activeProductTab === "inventory" && !canManage) {
    state.activeProductTab = "browse";
  }
  const canAct = !!state.session;
  const catalog = buildCommodityCatalog(detail);
  const inventoryCatalog = canManage ? buildInventoryCatalog(detail) : [];
  const browseCatalog = filterCatalog(catalog);
  const inventoryFilteredCatalog = filterCatalog(inventoryCatalog);
  const inventorySelectedCommodity = findCommodityByKey(inventoryCatalog, state.selectedCommodityKey);
  const selectedCommodity = state.activeProductTab === "inventory"
    ? findCommodityByKey(catalog, state.selectedCommodityKey)
    : getSelectedCommodity(browseCatalog, catalog);
  syncRouteUrl(true);

  const routeNav = `
    <div class="tab-strip route-strip">
      ${routeButton("browse", t("browse_tab"))}
      ${canManage ? routeButton("inventory", t("inventory_tab")) : ""}
      ${routeButton("buy", t("buying"))}
      ${routeButton("sell", t("selling"))}
      ${routeButton("chart", t("chart_tab"))}
      ${routeButton("index", t("market_index_tab"))}
    </div>
  `;

  const browseSection = renderCatalogShelf({
    catalog,
    filteredCatalog: browseCatalog,
    kicker: t("browse_goods"),
    title: t("commodity_shelf"),
    emptyMessage: t("no_match"),
    cardRenderer: (commodity) => renderCommodityCard(commodity)
  });
  const inventorySection = renderCatalogShelf({
    catalog: inventoryCatalog,
    filteredCatalog: inventoryFilteredCatalog,
    kicker: t("inventory_tab"),
    title: t("inventory_title"),
    emptyMessage: t("no_inventory_rows"),
    cardRenderer: (commodity) => renderInventoryCard(commodity)
  });

  els.marketDetail.innerHTML = `
    ${bars.join("")}
    <div class="market-shell">
      <section class="market-overview">
        <div class="overview-banner">
          <div class="detail-header">
            <div class="overview-title">
              <p class="section-kicker">${escapeHtml(t("market_overview"))}</p>
              <h2>${escapeHtml(detail.marketName)}</h2>
              <div class="overview-subtitle">${escapeHtml(t("owner_word"))} ${escapeHtml(detail.ownerName || "-")} · ${escapeHtml(t("warehouse_word"))} ${escapeHtml(detail.linkedWarehouseName || detail.linkedDockName || "-")} · ${escapeHtml(t("town_word"))} ${escapeHtml(detail.townName || "-")}</div>
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
        ${routeNav}
        ${state.activeProductTab === "browse"
          ? browseSection
          : (state.activeProductTab === "inventory"
            ? (inventorySelectedCommodity
              ? renderInventoryDetailPage(inventorySelectedCommodity, detail, canManage, canAct)
              : inventorySection)
          : (selectedCommodity
            ? renderCommodityDetailPage(selectedCommodity, detail, canManage, canAct)
            : `<div class="empty-state">${escapeHtml(t("select_market_prompt"))}</div>`))}
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
      const value = event.target.value || "";
      state.commodityQuery = value;
      renderDetailPreservingScroll();
      const nextSearch = document.querySelector("#commodity-search");
      if (nextSearch) {
        nextSearch.focus({ preventScroll: true });
        nextSearch.setSelectionRange(value.length, value.length);
      }
    });
  }

  document.querySelectorAll("[data-catalog-group]").forEach((node) => {
    node.addEventListener("click", () => {
      const group = normalizeCatalogGroup(node.getAttribute("data-catalog-group") || "all");
      if (group === "all") {
        state.catalogFilters.categoryGroup = "all";
        state.catalogFilters.category = "all";
        state.catalogExpandedGroup = "all";
        state.catalogHoverGroup = "all";
      } else {
        const hoverEnabled = supportsCatalogGroupHover();
        const isSameExpanded = normalizeCatalogGroup(state.catalogExpandedGroup) === group;
        const isSameGroupFilter = normalizeCatalogGroup(state.catalogFilters.categoryGroup) === group
          && normalizeCatalogCategory(state.catalogFilters.category) === "all";
        state.catalogFilters.categoryGroup = group;
        state.catalogFilters.category = "all";
        state.catalogExpandedGroup = !hoverEnabled && isSameExpanded && isSameGroupFilter ? "all" : group;
        state.catalogHoverGroup = hoverEnabled ? group : "all";
      }
      persistCatalogFilters();
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-catalog-subcategory]").forEach((node) => {
    node.addEventListener("click", () => {
      const category = normalizeCatalogCategory(node.getAttribute("data-catalog-subcategory") || "all");
      const group = normalizeCatalogGroup(node.getAttribute("data-catalog-subcategory-group") || categoryGroupForCategory(category));
      state.catalogFilters.categoryGroup = group;
      state.catalogFilters.category = category;
      state.catalogExpandedGroup = group;
      state.catalogHoverGroup = supportsCatalogGroupHover() ? group : "all";
      persistCatalogFilters();
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-catalog-group]").forEach((node) => {
    node.addEventListener("mouseenter", () => {
      if (!supportsCatalogGroupHover()) {
        return;
      }
      const group = normalizeCatalogGroup(node.getAttribute("data-catalog-group") || "all");
      if (group === "all" || state.catalogHoverGroup === group) {
        return;
      }
      state.catalogHoverGroup = group;
      renderDetailPreservingScroll();
    });
  });

  const groupStrip = document.querySelector("#catalog-group-strip");
  if (groupStrip) {
    groupStrip.addEventListener("mouseleave", () => {
      if (!supportsCatalogGroupHover() || normalizeCatalogGroup(state.catalogHoverGroup) === "all") {
        return;
      }
      state.catalogHoverGroup = "all";
      renderDetailPreservingScroll();
    });
  }

  document.querySelectorAll("[data-catalog-rarity]").forEach((node) => {
    node.addEventListener("click", () => {
      state.catalogFilters.rarity = normalizeCatalogRarity(node.getAttribute("data-catalog-rarity") || "all");
      persistCatalogFilters();
      renderDetailPreservingScroll();
    });
  });

  const minPrice = document.querySelector("#catalog-price-min");
  if (minPrice) {
    minPrice.addEventListener("change", (event) => {
      state.catalogFilters.minPrice = sanitizeCatalogPriceInput(event.target.value);
      persistCatalogFilters();
      renderDetailPreservingScroll();
    });
  }

  const maxPrice = document.querySelector("#catalog-price-max");
  if (maxPrice) {
    maxPrice.addEventListener("change", (event) => {
      state.catalogFilters.maxPrice = sanitizeCatalogPriceInput(event.target.value);
      persistCatalogFilters();
      renderDetailPreservingScroll();
    });
  }

  document.querySelectorAll("[data-card-commodity-key]").forEach((node) => {
    node.addEventListener("click", () => {
      state.selectedCommodityKey = node.getAttribute("data-card-commodity-key") || "";
      state.activeProductTab = normalizePageRoute(node.getAttribute("data-card-route") || "buy");
      syncRouteUrl(false);
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-catalog-sort]").forEach((node) => {
    node.addEventListener("click", () => {
      cycleCatalogSort(node.getAttribute("data-catalog-sort") || "activity");
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-route]").forEach((node) => {
    node.addEventListener("click", () => {
      const nextRoute = normalizePageRoute(node.getAttribute("data-route") || "browse");
      if (nextRoute === "inventory" && state.activeProductTab !== "inventory") {
        state.selectedCommodityKey = "";
      }
      state.activeProductTab = nextRoute === "inventory" && !(state.detail?.canManage) ? "browse" : nextRoute;
      syncRouteUrl(false);
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-inventory-overview]").forEach((node) => {
    node.addEventListener("click", () => {
      state.selectedCommodityKey = "";
      state.activeProductTab = "inventory";
      syncRouteUrl(false);
      renderDetailPreservingScroll();
    });
  });

  document.querySelectorAll("[data-storage-choice]").forEach((node) => {
    node.addEventListener("click", () => {
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
    dispatchButton.addEventListener("click", () => postMarketAction("/dispatch/retry", {
      orderIndex: numberValue("#dispatch-order-index", 0),
      terminalType: valueOf("#dispatch-terminal-type") || state.selectedDispatchTerminal || "PORT"
    }));
  }

  const dispatchTerminal = document.querySelector("#dispatch-terminal-type");
  if (dispatchTerminal) {
    dispatchTerminal.addEventListener("change", () => {
      state.selectedDispatchTerminal = valueOf("#dispatch-terminal-type") || "PORT";
      localStorage.setItem("marketWebDispatchTerminal", state.selectedDispatchTerminal);
      renderDetailPreservingScroll();
    });
  }

  const dispatchOrder = document.querySelector("#dispatch-order-index");
  if (dispatchOrder) {
    dispatchOrder.addEventListener("change", () => {
      state.selectedDispatchOrderIndex = Math.max(0, numberValue("#dispatch-order-index", 0));
      renderDetailPreservingScroll();
    });
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

function supportsCatalogGroupHover() {
  return typeof window !== "undefined"
    && typeof window.matchMedia === "function"
    && window.matchMedia("(hover: hover) and (pointer: fine)").matches;
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
  const hovered = supportsCatalogGroupHover() ? normalizeCatalogGroup(state.catalogHoverGroup) : "all";
  const explicit = normalizeCatalogGroup(state.catalogExpandedGroup);
  const active = currentCatalogActiveGroup();
  const candidate = hovered !== "all"
    ? hovered
    : (explicit !== "all" ? explicit : active);
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

function renderCatalogIconShell(commodityKey, label, className = "catalog-category-icon") {
  const fallback = escapeHtml(iconLetter(label));
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
        <span class="goods-art-fallback">${fallback}</span>
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

function renderCatalogShelf({ catalog, filteredCatalog, kicker, title, emptyMessage, cardRenderer }) {
  const groups = catalogGroups(catalog);
  const expandedGroup = resolvedCatalogExpandedGroup(catalog);
  const subcategories = expandedGroup === "all" ? [] : categoriesForGroup(expandedGroup, catalog);
  return `
    <div class="market-browse">
      <div class="shelf-head">
        <div>
          <p class="section-kicker">${escapeHtml(kicker)}</p>
          <h3>${escapeHtml(title)}</h3>
        </div>
      </div>
      <div class="catalog-navigation">
        <div id="catalog-group-strip" class="catalog-category-strip ${supportsCatalogGroupHover() ? "hover-enabled" : "tap-enabled"}">
          ${renderCatalogGroupButton("all", catalog)}
          ${groups.map((group) => renderCatalogGroupButton(group, catalog)).join("")}
        </div>
        ${expandedGroup !== "all" && subcategories.length ? `
          <div class="catalog-subcategory-strip" data-catalog-subcategory-strip>
            <div class="catalog-subcategory-head">
              <span class="toolbar-label">${escapeHtml(categoryGroupLabel(expandedGroup))}</span>
              <span class="catalog-subcategory-hint">${escapeHtml(t("all_types"))} · ${escapeHtml(categoryGroupLabel(expandedGroup))}</span>
            </div>
            <div class="catalog-subcategory-grid">
              ${subcategories.map((category) => renderCatalogSubcategoryButton(category, catalog)).join("")}
            </div>
          </div>
        ` : ""}
      </div>
      <div class="catalog-refine-row">
        <div class="catalog-rarity-strip">
          <span class="toolbar-label">${escapeHtml(t("rarity_filter"))}</span>
          ${renderCatalogRarityButton("all", catalog)}
          ${catalogRarities(catalog).map((rarity) => renderCatalogRarityButton(rarity, catalog)).join("")}
        </div>
        <div class="shelf-tools">
          <input id="commodity-search" class="goods-search" type="search" value="${escapeHtml(state.commodityQuery)}" placeholder="${escapeHtml(t("search_placeholder"))}">
        </div>
      </div>
      <div class="catalog-toolbar">
        <div class="catalog-price-range">
          <span class="toolbar-label">${escapeHtml(t("price_range"))}</span>
          <input id="catalog-price-min" class="catalog-range-input" type="number" min="0" step="1" value="${escapeHtml(state.catalogFilters.minPrice || "")}" placeholder="${escapeHtml(t("min_price"))}">
          <span class="range-divider">-</span>
          <input id="catalog-price-max" class="catalog-range-input" type="number" min="0" step="1" value="${escapeHtml(state.catalogFilters.maxPrice || "")}" placeholder="${escapeHtml(t("max_price"))}">
        </div>
        <div class="shelf-tools">
          <span class="toolbar-label">${escapeHtml(t("sort_label"))}</span>
          <div class="sort-controls">
            ${renderCatalogSortButton("name", t("sort_name"))}
            ${renderCatalogSortButton("price", t("sort_price"))}
            ${renderCatalogSortButton("time", t("sort_time"))}
            ${renderCatalogSortButton("quantity", t("sort_quantity"))}
          </div>
        </div>
      </div>
      ${filteredCatalog.length ? `
        <div class="goods-grid">
          ${filteredCatalog.map((commodity) => cardRenderer(commodity)).join("")}
        </div>
      ` : `<div class="empty-state">${escapeHtml(emptyMessage)}</div>`}
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
    const sellUnits = sumBy(commodity.listings, "availableCount");
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
    if (query && !commodity.displayName.toLowerCase().includes(query) && !commodity.commodityKey.toLowerCase().includes(query)) {
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
      endpoint,
      `/assets/sailboatmod/textures/item/${path}.png${assetVersionParam}`,
      `/assets/sailboatmod/textures/block/${path}.png${assetVersionParam}`
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
  shell.classList.add("has-image");
}

function hydrateCommodityIcons() {
  document.querySelectorAll("[data-icon-shell]").forEach((shell) => {
    if (shell.dataset.bound === "true") {
      return;
    }
    shell.dataset.bound = "true";
    const commodityKey = shell.dataset.commodityKey || "";
    if (!commodityKey) {
      return;
    }
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

function renderCommodityCard(commodity) {
  return `
    <button type="button" class="goods-card ${commodity.commodityKey === state.selectedCommodityKey ? "active" : ""}" data-card-commodity-key="${escapeHtml(commodity.commodityKey)}" data-card-route="buy">
      ${renderCommodityIcon(commodity.commodityKey, commodity.displayName)}
      <div class="goods-card-topline">
        <span class="goods-category-tag">${escapeHtml(categoryLabel(commodity.category))}</span>
        <span class="goods-rarity-tag ${rarityClassName(commodity.rarity)}">${escapeHtml(rarityLabel(commodity.rarity))}</span>
      </div>
      <h3>${escapeHtml(commodity.displayName)}</h3>
      <div class="goods-key">${escapeHtml(commodity.commodityKey)}</div>
      <div class="goods-summary">
        <span class="goods-badge">${number(commodity.totalListings)} ${escapeHtml(t("selling"))}</span>
        <span class="goods-badge">${number(commodity.demandUnits)} ${escapeHtml(t("buying"))}</span>
        <span class="goods-badge">${number(commodity.storageUnits)} ${escapeHtml(t("in_storage"))}</span>
      </div>
      <div class="goods-card-footer">
        <strong>${commodity.bestSell == null ? "--" : number(commodity.bestSell)}</strong>
        <span>${escapeHtml(t("lowest_sell"))}</span>
      </div>
    </button>
  `;
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
          ${renderStorageChoiceGrid(matchingStorage, selectedStorageIndex, t("no_storage_match"))}
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
          <h2>${escapeHtml(commodity.displayName)}</h2>
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

function renderCommodityDetailPage(commodity, detail, canManage, canAct) {
  const activeSeries = primaryChartSeries(commodity);
  const latestPoint = latestChartPoint(activeSeries);
  const chartStats = chartSummary(activeSeries);
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
        ${routeButton("buy", t("buying"))}
        ${routeButton("sell", t("selling"))}
        ${routeButton("chart", t("chart_tab"))}
        ${routeButton("index", t("market_index_tab"))}
      </div>

      ${renderCommodityPage(commodity, detail, canManage, canAct)}
    </div>
  `;
}

function renderCommodityPage(commodity, detail, canManage, canAct) {
  if (state.activeProductTab === "buy") {
    return renderBuyingTab(commodity, canManage, canAct);
  }
  if (state.activeProductTab === "chart") {
    return renderChartTab(commodity, detail);
  }
  if (state.activeProductTab === "index") {
    return renderIndexTab(commodity, detail);
  }
  if (state.activeProductTab === "sell") {
    return renderSellingTab(commodity, detail, canManage, canAct);
  }
  return renderBrowseTab(commodity, detail);
}

function renderBrowseTab(commodity, detail) {
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
            <td>${number(entry.availableCount)}</td>
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

function renderSellingTab(commodity, detail, canManage, canAct) {
  const dockStorage = detail.storageEntries || [];
  const matchingStorage = storageEntriesForCommodity(commodity, detail);
  const preferredStorage = preferredStorageEntry(matchingStorage);
  const selectedStorageIndex = preferredStorage?.index ?? dockStorage[0]?.index ?? -1;

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
              <select id="create-listing-storage">
                ${dockStorage.map((entry) => `<option value="${entry.index}" ${Number(entry.index) === Number(selectedStorageIndex) ? "selected" : ""}>${escapeHtml(entry.itemName)} x${number(entry.quantity)} · ${escapeHtml(t("suggested_word"))} ${number(entry.suggestedUnitPrice)}</option>`).join("")}
              </select>
              <input id="create-listing-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultListingQuantity || 1))}" placeholder="${escapeHtml(t("quantity"))}">
              <input id="create-listing-adjustment" type="number" value="${escapeHtml(String(state.settings.defaultPriceAdjustmentBp || 0))}" placeholder="${escapeHtml(t("price_band"))} bp">
              <textarea id="create-listing-note" placeholder="${escapeHtml(t("seller_note"))}"></textarea>
              <div class="actions">
                <button type="button" id="create-listing-button" ${canManage && canAct ? "" : "disabled"}>${escapeHtml(t("create_listing"))}</button>
                <button type="button" id="claim-credits-button" class="secondary" ${canAct ? "" : "disabled"}>${escapeHtml(t("claim_credits"))}</button>
              </div>
            </div>
          ` : `<div class="empty-state">${escapeHtml(t("no_storage"))}</div>`}
        </div>

        <div class="surface-card stack">
          <div class="panel-head">
            <div>
              <p class="section-kicker">${escapeHtml(t("market_notes"))}</p>
              <h3>${escapeHtml(t("sell_summary"))}</h3>
            </div>
          </div>
          <div class="summary-note">${escapeHtml(t("lowest_sell"))} ${commodity.bestSell == null ? "--" : number(commodity.bestSell)}. ${number(commodity.sellUnits)} / ${number(commodity.totalListings)}.</div>
          <div class="summary-note">${escapeHtml(detail.linkedDock ? t("dock_linked") : t("no_linked_dock"))} · ${escapeHtml(detail.linkedDockName || "-")}.</div>
          <div class="summary-note">${escapeHtml(t("dock_stock"))} ${number(dockStorage.length)} ${escapeHtml(t("storage_rows"))} / ${number(sumBy(dockStorage, "quantity"))}.</div>
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
  const dispatchOrders = Array.isArray(state.detail?.sourceOrders) ? state.detail.sourceOrders : [];
  const dispatchOrder = selectedDispatchOrder(state.detail);
  const dispatchOption = selectedDispatchOption(dispatchOrder);

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
            <div class="actions">
              <button type="button" id="buy-order-button" ${canAct ? "" : "disabled"}>${escapeHtml(t("create_buy_order"))}</button>
              <button type="button" id="dispatch-button" class="warn" ${canManage && canAct && dispatchOrder && dispatchOption?.available ? "" : "disabled"}>${escapeHtml(t("retry_dispatch"))}</button>
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

function routeButton(key, label) {
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
    border: cssThemeValue("--chart-border", "rgba(125, 139, 161, 0.22)")
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
  if (state.activeProductTab === "index") {
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
      borderColor: palette.border,
      mode: state.chartFlags.logScale ? LightweightCharts.PriceScaleMode.Logarithmic : LightweightCharts.PriceScaleMode.Normal
    }
  });
  chartState.candleSeries.setData(priceData);
  chartState.volumeSeries.setData(state.chartIndicators.volume ? volumeData : []);
  chartState.maShortSeries.setData(state.chartIndicators.ma5 ? movingAverageData(priceData, 5) : []);
  chartState.maLongSeries.setData(state.chartIndicators.ma20 ? movingAverageData(priceData, 20) : []);
  chart.timeScale().fitContent();
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
  const palette = currentChartPalette();
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

els.bindButton?.addEventListener("click", bindAccountLogin);
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

(async function init() {
  applyRouteFromLocation();
  await loadSettings();
  await loadServerVersion();
  renderSession();
  renderMarkets();
  renderDetail();
  if (!state.sessionToken) {
    try {
      await loadMarkets("replace");
    } catch (error) {
      setStatus(error.message, true);
    }
    return;
  }
  try {
    await hydrateSession();
    await loadMarkets("replace");
    setStatus(t("signed_in_as", { name: state.session.playerName }));
  } catch (error) {
    setStatus(error.message, true);
  }
})();
