const state = {
  sessionToken: localStorage.getItem("marketWebSessionToken") || "",
  locale: localStorage.getItem("marketWebLocale") || "zh-CN",
  session: null,
  webResourceVersion: "",
  markets: [],
  selectedMarketId: "",
  selectedCommodityKey: "",
  activeProductTab: "browse",
  accessPanelOpen: localStorage.getItem("marketWebAccessPanelOpen") !== "0",
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
    maxPrice: ""
  }),
  catalogExpandedGroup: "all",
  catalogHoverGroup: "all",
  detail: null,
  selectedDispatchOrderIndex: 0,
  selectedDispatchTerminal: localStorage.getItem("marketWebDispatchTerminal") || "PORT",
  settings: {
    uiTitle: "Monpai Online Market 路 Monpai鍦ㄧ嚎甯傚満",
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
  accessToggle: document.querySelector("#access-toggle"),
  accessToggleLabel: document.querySelector("#access-toggle-label"),
  sessionStatusInline: document.querySelector("#session-status-inline"),
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
  localStorage.setItem("marketWebAccessPanelOpen", state.accessPanelOpen ? "1" : "0");
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
    hero_title: "Monpai闁革负鍔庨崵搴ｆ暜閸屾碍绨?,
    hero_subtitle: "闁革负鍔忕换鏍煂瀹€鍐閻炴稑鐬奸崵搴㈢▔婵犲嫭鐣遍柣妞绘櫅閹佳勭閵堝棙顫滈柛鎴濇惈閺侇參鏁嶇仦鑲╃☉闁汇垺淇洪崵锕傛焾閹存帞鐟濋柣顫姀缁绘﹢宕楅妷锔界疀闁告柡鈧櫕鐝?闁革负鍔嬬粭鍛村棘瑜版巻鍋撴径瀣仴濞达絿濮峰▓鎴﹀疮閸℃鎯傞柨?,
    mode_label: "婵☆垪鈧磭纭€",
    mode_value: "闁哥喎妫楅幖褏鏁崒姘皻",
    session_label: "濞村吋淇洪惁?,
    flow_label: "婵炵繝鑳堕埢?,
    flow_value: "濞寸姰鍊楁晶婵嬫儌鐠囪尙绉?,
    portal_kicker: "缂傚啯鍨块妴澶愭儌鐠囪尙绉?,
    auth_title: "濡絾鐗楅濂告偨?token 缂備焦鍨甸悾楣冩晬鐏炶姤鍊电紓渚囧幘椤洨鐥捄銊︻仮鐟?,
    auth_subtitle: "闁稿繐鐗嗗﹢顏勩€掗崨濠傜亞闂佹彃鏈晶鐣屾偘鐏炶偐顏辨繛鍡忊偓铏殥濞寸姰鍊栫€ｄ線宕?token闁挎稑鏈俊鍝モ偓鐟板暟缁妇鈧鑹鹃崺宀€绱旈幋锔衡偓澶屾嫻閿曗偓瑜板潡鏁嶇仦鑲╊吅闁告艾楠稿銊╁矗椤栨瑤绨伴柣鈺佺摠鐢挳鎮介妸銊ヮ槱闁告瑥鍢查惁鎴︽儘娴ｇ儤顏㈢憸鐗堟磸閳?,
    command_label: "婵炴挸鎲￠崹娆撳礃閸涱厽鍤掑ù?,
    copy_command: "濠㈣泛绉撮崺妤呭川閹存帗濮?,
    fast_signin: "闊浂鍋婇埀顒傚枔濞呫儴銇?,
    connect_player: "缂備焦鍨甸悾鍓ф嫻閿曗偓瑜板潡骞嬮弽顐ｎ仮鐟?,
    not_signed_in: "闁哄牜浜炲▍銉ㄣ亹閺囨ǚ鍋?,
    paste_chat: "缂侇喗顭堥崚娑㈡嚂婵犲倶浜弶鍫熸尭閸ゎ參骞?token",
    paste_chat_placeholder: "闁硅泛锕ら悾顒勫极绾惧鍠婂鍨涙櫈椤㈡垹鍒掑Ο鍨灡闁告帗濯界换鏍煂瀹€瀣濡炪倗鏁诲鐗堝濮樺啿娈伴柛鏂诲姀閻︽垿宕?token闁?,
    token_placeholder: "Token 濞村吋淇洪崵婊堝礉閵娿儱姣夐柣婊勬緲濠€顏呮交濞嗘挸娅?,
    account_username_placeholder: "閻犳劧绠戣ぐ鍧楀触?,
    account_password_placeholder: "閻庨潧妫涢悥?,
    bind_account: "缂備焦鍨甸悾鍓ф嫻閿曗偓瑜?,
    account_login: "閻犳劧绠戣ぐ鍧楁儌鐠囪尙绉?,
    token_helper: "濡絾鐗楅鑲╃磼閹存繄鏆伴柨娑欐皑閻鎷?token闁挎稑鐭侀鏇犵磾椤斿灝顦╅柛娆忓槻閹锋壆鈧潧妫涢悥婊堟晬瀹€鈧崝褔宕ユ惔锝囨嫧閻庤鍝庨埀顒€鍊风粻锝夊触鎼粹€宠闁烩晛鐡ㄧ敮鎾偨閵娿劌顦╅柛娆忓槻閻︽垿鎯嶆担鐑橆仮鐟滅増娲忛埀?,
    market_network: "閻㈩垰鍊稿┃鈧紓鍐╁灩缁?,
    browse_markets: "婵炴潙绻楅～宥囨暜閸屾碍绨?,
    refresh: "闁告帡鏀遍弻?,
    market_sidebar_hint: "闁稿繐鐗撻埀顒€顦粩瀛樼▔椤忓嫮顏抽柛锕佹缁挾绮╅銈囩闁告劕绉撮崕姘跺疮閸℃鎯傞悽顖氬€稿┃鈧☉鎾亾闁哄秹鏀辩粊鑽ゆ喆閸繂寰撻悹鎰屽懎鈷栨鐐跺煐婢э箑顕ｉ埀顒勫础閺囩偞娅岄柛婵呯窔閵嗗濡?,
    brand_kicker: "Sailboat 閻㈩垰鍊稿┃鈧ù婧垮€栧Σ妤呭箥閳?,
    language_label: "閻犲浂鍙€閳?,
    empty_select_market: "闂侇偄顦扮€氥劍绋夐埀顒佺▔椤忓嫮顏抽柛锕佹缁挾绮╅娆庣鞍婵炴潙绻楅～宥夊疮閸℃鎯傞柕鍡曠劍鐎垫洟宕￠弴妯峰亾娴ｅ湱婀撮悹鎰靛幖閹风増绂掗柨瀣閻犙勬緲婵炲秹濡?,
    market_overview: "閻㈩垰鍊稿┃鈧慨鎺戝€介～?,
    browse_goods: "婵炴潙绻楅～宥夊疮閸℃鎯?,
    commodity_shelf: "闁哥喎妫楅幖褏鎷硅閻?,
    search_placeholder: "闁瑰吋绮庨崒銊╂偋閳轰焦鎯傞柛姘У閸?commodity key",
    price_range: "濞寸娀鏀遍悧鎼佸礌濞差亝锛?,
    min_price: "闁哄牃鍋撳ù锝呯凹閻?,
    max_price: "闁哄牃鍋撳Δ鍌浢奸悳?,
    sort_name: "闁告艾绉惰ⅷ",
    sort_price: "濞寸娀鏀遍悧?,
    sort_time: "闁哄啫鐖煎Λ?,
    sort_quantity: "闁轰椒鍗抽崳?,
    all_types: "闁稿繈鍔戦崕瀵哥尵鐠囪尙鈧?,
    all_rarities: "闁稿繈鍔戦崕瀵哥矙閳ь剟寮垫径濠傤唺",
    rarity_filter: "缂佸鍋撻柡鍫濐槸鐎?,
    sort_label: "闁圭儤甯掔花?,
    inventory_tab: "閹煎瓨鎸搁悺?,
    inventory_title: "閸╁酣鏅ｆ禒鎾崇氨",
    no_inventory_rows: "鐟滅増鎸告晶鐘电磼閸埄浼傛繛灞稿墲濠€渚€宕ｉ姘辨綌缂佲偓閾忚鐣遍幖瀛樻尭閻°劑濡?,
    inventory_requires_manage: "闁告瑯浜濆﹢浣姐亹閹惧啿顤呴柣顔荤閵囨棃骞嬮弽褏顏抽柛锕佹濞堟垹绮婚敍鍕€為柤鏉挎噹瑜板弶绂掗妷锔惧弨闁活亜顑呯花杈┾偓娑櫱滈埀?,
    category_all: "闁稿繈鍔戦崕?,
    category_wood: "闁哄牄鍔嶅?,
    category_luxury: "濠靛偆婢€缁?,
    category_food: "濡炲鍠撴晶?,
    category_ore: "闁活叀娉曢悡?,
    category_gems: "閻庤绻勯悡?,
    category_metal: "闂佸弶鍨甸惈?,
    category_tools: "鐎规悶鍎遍崣?,
    category_spices: "濡絾鐟﹂弸?,
    category_plant: "婵＄偛绉舵晶?,
    category_crop: "闁告劖绮堢紞鏃堟偋?,
    category_material: "闁哄鍔栭弸?,
    category_mob_drop: "闁诡剦浜炴晶鍧楀箳婢跺孩鍎?,
    category_alchemy: "闁绘劘澹堝畵?,
    category_building: "鐎点倛娅ｉ悺?,
    category_nether: "濞戞挸顑囬弲?,
    category_end: "闁哄牜鍋勫﹢?,
    category_treasure: "閻庤绻勬晶?,
    category_redstone: "缂佷勘鍨婚悡?,
    category_utility: "閻庡湱鍋熼弫?,
    category_weapon: "婵繐绠戝▍?,
    category_armor: "闂傚啯褰冮崣?,
    category_other: "闁稿繑婀圭划?,
    rarity_common: "闁哄拋鍣ｉ埀?,
    rarity_uncommon: "缂傚啯娲濋～?,
    rarity_rare: "缂佸鍋撻柡?,
    rarity_epic: "闁告瑨灏惁?,
    rarity_legend: "濞磋偐濮鹃?,
    rarity_extraordinary: "闂傚牏鍋涢崵?,
    no_match: "婵炲备鍓濆﹢渚€宕犺ぐ鎺戝赋閺夆晜鐟ら柌婊呯驳濞戔懇鍋撴径瀣拫濞寸姴澧庡▓鎴﹀疮閸℃鎯傞柕?,
    selected_commodity: "鐟滅増鎸告晶鐘诲疮閸℃鎯?,
    lowest_sell: "闁哄牃鍋撳ù锝呴濠€顏堝船?,
    highest_buy: "闁哄牃鍋撳Δ鍌浬戦惇鎵嫻?,
    avg_24h: "24閻忓繐绻戝鍌炲锤閸ワ妇骞?,
    trades_24h: "24閻忓繐绻戝鍌炲箣閹邦亝鍞?,
    sell_listings: "闁革负鍔岄弫顓犳偘鐏炵偓娈?,
    on_sale: "闁革负鍔岄弫顓㈠极娴兼潙娅?,
    buying_demand: "婵懓鍊介崰姗€妫侀埀顒€效?,
    in_storage: "濞寸姵鎸搁崑宥嗘償閹惧磭鎽?,
    browse_tab: "婵炴潙绻楅～宥夊疮閸℃鎯?,
    purchase_tab: "閻犳劦鍘洪幏閬嶅疮閸℃鎯?,
    chart_tab: "K缂佹儳鐏濆ù?,
    market_index_tab: "濠㈠爢鍛８闁圭娲﹂弳?,
    units_live: "闁告娲戠紞鍛村捶閵娿儲鏆?,
    units_wanted: "闁告娲戠紞鍛ч崒婵嗘灎",
    volume_label: "闁瑰瓨鍔掑锕傛煂?,
    selling: "闁革负鍔岄弫?,
    buying: "婵懓鍊介崰?,
    my_buy_orders: "闁瑰瓨鍨瑰▓鎴澬ч崒婵嗘灎闁?,
    price_history: "濞寸娀鏀遍悧鎼佸储閸℃钑?,
    recent_buckets: "闁哄牃鍋撻弶鈺傚灥閸ㄥ酣寮懜鐐光偓?,
    chart_context: "闁搞儲宕橀妴鍐偝椤栨凹鏆?,
    sell_item: "濞戞挸锕ラ悘锕傚疮閸℃鎯?,
    create_listing: "闁告帗绋戠紓鎾诲箰閸屾艾绀?,
    create_buy_order: "闁告帗绋戠紓鎾承ч崒婵嗘灎闁?,
    claim_credits: "濡澘妫楄ぐ鍥╂嫻瑜庨?,
    retry_dispatch: "闁插秷鐦崣鎴ｆ彛",
    terminal_type_label: "閸欐垼鎻ｇ紒鍫㈩伂",
    terminal_type_port: "濞擃垰褰?,
    terminal_type_post_station: "妞硅法鐝?,
    market_notes: "閻㈩垰鍊稿┃鈧悹鍥х摠濡?,
    sell_summary: "闁告鐗滃ú蹇涘箺濡娲?,
    demand_summary: "濞戞梹澹嗗ú蹇撁规担绋啃楅柟?,
    market_snapshot: "閻㈩垰鍊稿┃鈧煫鍥跺亞閸?,
    no_commodity_data: "閺夆晜鐟ら柌婊呮暜閸屾碍绨氶柣鈺婂枛婢х姵娼诲Ο鑽ゆ⒕闁哄牆顦弲銏ゅ传娴ｈ娈堕柟璇″枔閳?,
    no_selling_rows: "閺夆晜鐟ら柌婊堝疮閸℃鎯傜憸鐗堟尭婢х姴鈻介埄鍐╃畳闁革负鍔岄弫顓㈠箰閸屾艾绀嬮柕?,
    no_buy_rows: "閺夆晜鐟ら柌婊堝疮閸℃鎯傜憸鐗堟尭婢х姴鈻介埄鍐╃畳闁稿浚鍓欑槐鎴澬ч崒婵嗘灎闁?,
    no_my_buy_rows: "濞达絿濮剧换鏇炩柦閳╁啯绠掗弶鈺傜懁闁叉粓宕崱妤佹儌闁汇劌瀚惇鎵嫻椤撶偛绀嬮柕?,
    no_chart_rows: "閺夆晜鐟ら柌婊堝疮閸℃鎯傞弶鈺偵戦惀鍛村嫉婢跺骞嗛柡宥堝椤斿洩銇愰弴妯峰亾?,
    no_storage_match: "鐟滅増鎸告晶鐘测柦閳╁啯绠掑☉鎾虫唉椤曟岸宕崱妤佹儌闁告牕缍婇崢銈夋儍閸曨厾鍨冲鍓佺節缁劑宕掗妸锔借拫闁烩晩鍠撻埀?,
    seller_note: "闁告鐗曢宥嗗緞閸ャ劍鏆?,
    suggested_word: "鐎点倝缂氶?,
    min_bp: "闁哄牃鍋撳ù?bp",
    max_bp: "闁哄牃鍋撳Δ?bp",
    to_word: "闁?,
    offline_word: "缂佸倽宕甸崵?,
    online_word: "闁革负鍔庨崵?,
    connected_word: "鐎规瓕灏换娑㈠箳?,
    signed_in_as: "鐎规瓕灏欏▍銉ㄣ亹閺囨俺绀?{name}闁?,
    sign_in_required: "闂傚洠鍋撻悷鏇氱閸樻稒娼忛幘鍐插汲闁谎嗩嚙缂?token闁?,
    bind_requires_credentials: "缂備焦鍨甸悾鍓ф嫻閿曗偓瑜板潡寮崼鏇熶粯閻熸洑绀侀幃鎾诲籍鐠虹尨缍栭柛鎰懆婢跺嫰宕ｅ畡鐗堝€抽柛婊冭嫰閻︽垿鎯嶆担纰樺亾?,
    account_login_required: "閻犲洨鏌夌欢顓㈠礂閵夈劌顦╅柛娆忓槻閹洟宕仦鐣屾闁活喕闄嶉埀?,
    account_bound: "鐎规瓕灏欑划锔锯偓瑙勪亢婢跺嫰宕?{name}闁?,
    account_login_success: "鐎瑰憡鐓￠埀顒佷亢缁诲啰鎷归敃鈧ぐ?{name} 闁谎嗩嚙缂嶅秹濡?,
    sign_in_to_trade: "闁谎嗩嚙缂嶅秹宕ユ惔銏狀枀闁煎啿鈧喓绠婚悶娑樼焷閸犳ɑ绋婇懜顑藉亾娴ｉ鐟愰柡瀣堪閳ь兛鐒﹂惇鎵嫻椤撴壕鍋撴担绋跨悼婵炴垵鐗嗛幏鐗堬紣閸℃绲跨紒娑橆槹閹奸攱鎷呭┃搴撳亾?,
    copied: "鐎瑰憡褰冮ˇ鏌ュ礆闊祴鍋?,
    command_copied: "闁告稒鍨濋幎銈咁啅閹绘帩妲婚柛鎺曨啇缁辨繄鎷犲畡鐗堣含婵炴挸鎲￠崹娆撳礃閸涱喖鈷旈悶娑樿閳?,
    token_extracted: "鐎圭寮惰ぐ渚€宕?token 妤犵偠娉涢ˇ鏌ュ礆鐠哄搫鐓傞柛鎿冧海閸掓盯寮剁憗銈傚亾?,
    clipboard_failed: "闁哄啰濮电涵鍓佹媼閸ф锛栭柛鎿冧海閸掓盯寮堕崠锛勭閻犲洭鏀辨晶婊堝礉閵娿儺妲婚柛鎺曠堪閳?,
    markets_refreshed: "閻㈩垰鍊稿┃鈧€瑰憡褰冮崺娑㈠棘閼割兘鍋?,
    action_completed: "闁瑰灝绉崇紞鏂款啅閹绘帞鏆氶柟瀛樺姂閳?,
    sign_in_to_load_markets: "闁谎嗩嚙缂嶅秹宕ユ惔鈥崇ギ闁告瑯鍨辨晶鐣屾偘鐏炵晫顏抽柛锔惧劋閹奸攱鎷呭┃搴撳亾?,
    guest_mode_ready: "閻犱礁鐏濋鐟拔熼垾宕囩闁挎稒鑹捐ぐ鍙夌閵夛妇銈婚悷娆忕墕缂嶅宕滃鍛伋闁革箑鎼幏浼村疮閸℃鎯傞柨娑樺缁茬偓绋夊鍫濆幋閺夆晜绋栭、鎴﹀箼瀹ュ嫮绋婇柕?,
    no_markets: "閺夆晜蓱閻ュ懘寮垫径瀣殘闁告劕濂旈幑銏℃媴閺囩偟顏抽柛锕佹缁挾绮╅妯峰亾?,
    select_market_prompt: "闂侇偄顦扮€氥劍绋夐埀顒佺▔椤忓嫮顏抽柛锕佹缁挾绮╅娑欓檷婵炴潙绻楅～宥団偓鐟板暟濞堟垿宕崱妤佹儌闁烩晩鍠栫紞宥夊Υ?,
    guest_word: "閻犱礁鐏濋?,
    live_terminal: "闁革负鍔庨崵搴ｇ磼閸埄浼?,
    chunk_cold: "闁告牕鎼锟犲嫉椤忓嫬顫ｉ弶?,
    manage: "闁告瑯鍨抽鎼佹偠?,
    view: "濞寸姴鎳忛悡锟犳儑?,
    loaded: "鐎瑰憡褰冩慨鐐存姜?,
    cold: "闁哄牜浜滄慨鐐存姜?,
    dock_linked: "瀹歌尙绮︾€规矮绮ㄦ惔?,
    no_linked_dock: "閺堫亞绮︾€规矮绮ㄦ惔?,
    manager_access: "缂佺媴绱曢幃濠囧级閸愵喗顎?,
    read_only: "闁告瑯浜ｉ?,
    browse_only: "濞寸姴鎳忕粊鑽ゆ喆?,
    listings_word: "闁圭鍊稿畷?,
    market: "閻㈩垰鍊稿┃鈧?,
    quantity: "闁轰椒鍗抽崳?,
    total: "闁诡剝顔婇悳?,
    actions: "闁瑰灝绉崇紞?,
    seller: "闁告鐗曢?,
    buyer: "濞戞梹婢橀?,
    available: "闁告瑯鍨伴弫?,
    reserved: "濡澘瀚弳鈧?,
    unit_price: "闁告娲戦悳?,
    dock: "閻礁銇?,
    warehouse_word: "娴犳挸绨?,
    status: "闁绘鍩栭埀?,
    price_band: "濞寸娀鏀遍悧鎼佸礌濞差亝锛?,
    implied_bid: "闁规亽鍔庨悾濠氬礄鏉炴壆骞?,
    time: "闁哄啫鐖煎Λ?,
    average: "闁秆冩矗閻?,
    low: "濞?,
    high: "濡?,
    volume: "闂?,
    buy_1: "閻犳劦鍘洪幏?1",
    cancel: "闁告瑦鐗楃粔?,
    current_sell_side: "鐟滅増鎸告晶鐘诲础閺嶎偅纾?,
    current_buy_side: "鐟滅増鎸告晶鐘崇▕閹殿喗纾?,
    dock_stock: "娴犳挸绨辨惔鎾崇摠",
    terminal_status: "缂備礁鐗忛顒勬偐閼哥鍋?,
    chart_disabled: "闂佹澘绉堕悿鍡楊啅閹绘帒褰犻梻鍌ゅ幒閻滎垶寮介悡搴㈢閻炴稏鍔婇埀?,
    no_chart_buckets: "閺夆晜鐟ら柌婊堝疮閸℃鎯傞弶鈺偵戦惀鍛村嫉婢跺骞嗛柡宥囧帶閸ㄥ骸顩奸懜鍨闁硅鍠撻埀?,
    chart_no_selection: "鐟滅増鎸告晶鐘测柦閳╁啯绠掗柛娆樺灠閻秶绮堥崫鍕閻炴稏鍔庡▓鎴﹀疮閸℃鎯傞柕?,
    chart_library_missing: "闁搞儲宕橀妴鍐╂償閹惧瓨寮撻柛鏃傚Ь濞村洭鏁嶇仦钘夊殥闁告帒娲﹀畷鍙夌▔閸濆嫬鏁剁紓鍐敜缂佺偓瀵х憰鍡涘蓟閹炬墎鍋?,
    chart_init_failed: "闁搞儲宕橀妴鍐礆濠靛棭娼楅柛鏍ㄧ墪閵囨垹鎷归妷顖滅鐎瑰憡褰冮崹蹇涘箲椤叀绀嬮柛鎰噽閻ゅ捑缂佺偓瀵х憰鍡涘蓟閹炬墎鍋?,
    chart_render_failed: "闁搞儲宕橀妴鍐ㄣ€掗崣澶屽帬濠㈡儼绮剧憴锕傚Υ?,
    no_demand_ladder: "閺夆晜鐟ら柌婊堝疮閸℃鎯傞柣鈺婂枛婢х姵娼诲Ο鑽ゆ⒕闁哄牆顦懜浼村箣閹邦厾婀撮悹鎰靛幗椤亪姊奸悢绮瑰亾?,
    waiting_reference: "闁哄棗鍊瑰Λ銈夊及鎼达絺鈧﹢宕烽妸銉︽殯濞寸姴鍢插顒勬嚀閸愶腹鍋?,
    around_current_ask: "闁圭顦紞瀣礈瀹ュ懎绀屽ù鐘绘敱鐢湱绮诲Δ鈧妵鍥嚊閺夋垶韬?{range}闁?,
    no_storage: "闁哄啰濮崇划銊╁磼?,
    storage_rows: "濞寸姵鎸搁崑宥囨偘?,
    live_requests: "婵炲弶妲掔粚顒€效閸屾繂鏋?,
    stock_rows: "濞寸姵鎸搁崑宥夊级閿涘嫭绐?,
    owner_word: "闁圭鍋撻柡鍫濐槼閳?,
    town_word: "闁糕晛閰ｉ弲?,
    pending_credits: "鐎垫澘鎳橀。顐ゆ嫻瑜庨?,
    commodity_types: "闁哥喎妫楅幖褏绮斿鍥潶",
    storage_units: "濞寸姵鎸搁崑宥夊箑婵犳艾娅?,
    open_demand: "鐎殿喒鍋撻柡鈧ィ鍐╀粯婵?,
    my_buy_orders_metric: "闁瑰瓨鍨瑰▓鎴澬ч崒婵嗘灎闁?,
    net_balance: "闁告垟鍋撳ù锝嗙懇椤?,
    reference_price: "闁告瑥鍊介埀顒€鍟╅悳?,
    liquidity_score: "婵炵繝绀佹慨鈺呭箑?,
    market_index: "濠㈠爢鍛８闁圭娲﹂弳?,
    category_index: "闁告帒妫涚悮顐﹀箰閸ャ劍娈?,
    pressure_model: "濞寸娀鏀遍悧姝屻亹閸楃偞鎯欐俊顖椻偓宕団偓?,
    inventory_pressure: "閹煎瓨鎸搁悺銊╁储鐎ｎ亜顫?,
    buy_pressure: "濞戞梹澹嗗ú蹇涘储鐎ｎ亜顫?,
    volatility_word: "婵炲鍨规慨?,
    timeframe: "闁告稏鍔嶅﹢?,
    indicators: "闁圭娲﹂悥?,
    chart_controls: "闁搞儲宕橀妴鍐箳瑜嶉崺?,
    log_scale: "閻庝絻顫夐弳鐔煎锤閹邦厾鍨?,
    inflation_adjust: "闂侇偅淇洪崕澶嬬┍椤旂瓔鍔€",
    inflation_unavailable: "鐟滅増鎸告晶鐘测柦閳╁啯绠?CPI 闁轰胶澧楀畵渚€鏁嶇仦鐐暞濞戞挸绉磋ぐ鏌ユ偨閵婏絺鍋?,
    reset_zoom: "闂佹彃绉堕悿鍡欑磽閳哄倹鏉?,
    current_change: "鐟滅増鎸告晶鐘测槈閵娿劎鈹?,
    max_drawdown: "闁哄牃鍋撳鍫嗗啯绀€闁?,
    inception_return: "閻犙冨槻椤劙寮ㄩ崜浣规妱",
    chart_hover_empty: "缂佸顕ф慨鈺呭礆閺夋寧绂堥悶娑栧妺缁楀倿寮婚妷褎绠?OHLC 濞戞挸瀛╅崹姘閵堝娅?,
    market_index_chart: "閻㈩垰鍊稿┃鈧柟绋挎处閺嗙喓鎸ч弶鍨棦",
    category_index_chart: "闁告帒妫涚悮顐﹀箰閸ャ劍娈堕悹褎婢樻繛?,
    cpi_chart: "婵炴垵鐗愰崹鍌涚闁垮澹愰柟绋挎处閺?,
    loans_chart: "闁哄牜浜滄导鈺冩嫻闁垮鍎ラ悹褎婢樻繛?,
    inflation_since_inception: "缂侀硸鍨甸鎼佹焻濮樺啿鍓?,
    loans_change: "閻犳劙鏀遍娆撳矗濡搫顕?,
    cpi_now: "鐟滅増鎸告晶?CPI",
    macro_overview: "閻庣懓绻楅～鍥ь潡閸屾繍娼?
  },
  */
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
    empty_select_market: "Select a market terminal to browse commodities, listings, buy orders, and price history.",
    market_overview: "Market Overview",
    explore_tab: "Explore",
    trade_tab: "Trade",
    analytics_tab: "Analytics",
    explore_hint: "Browse catalog, compare prices, and jump between goods.",
    trade_hint: "Buy, list, claim credits, and manage demand from one lane.",
    inventory_hint: "Inspect stock that can be listed or dispatched.",
    analytics_hint: "Read price history, index movement, and macro signals.",
    signin_status_guest: "Sign in",
    signin_status_ready: "Account Center",
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
    manual_price: "Manual unit price",
    manual_price_help: "Enter your own unit price. The server converts it to a bp offset and only accepts prices inside the allowed range.",
    listing_price_preview: "Unit price",
    listing_bp_preview: "Derived bp",
    listing_price_range: "Allowed range",
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
  hero_title: "Monpai 鍦ㄧ嚎甯傚満",
  hero_subtitle: "娓稿涔熷彲浠ユ祻瑙堝競鍦恒€佸晢鍝佷笌浠锋牸璧板娍锛涚櫥褰曞悗鎵嶈兘鎵ц缁戝畾銆佷笂鏋躲€佹眰璐笌鍙戣揣绛夋搷浣溿€?,
  mode_label: "妯″紡",
  mode_value: "鍟嗗搧甯傚満",
  session_label: "浼氳瘽",
  flow_label: "娴佺▼",
  flow_value: "缃戦〉璐︽埛鐧诲綍",
  portal_kicker: "缃戦〉鐧诲綍",
  auth_title: "鍏堢粦瀹氱綉椤佃处鎴凤紝鍐嶇绾跨櫥褰?,
  auth_subtitle: "棣栨浣跨敤鏃讹紝浠庢父鎴忎腑鑾峰彇 token锛岃缃綉椤佃处鍙峰拰瀵嗙爜瀹屾垚缁戝畾銆備箣鍚庡彲鐩存帴鐢ㄨ处鍙峰瘑鐮佺櫥褰曘€?,
  command_label: "娓告垙鍐呭懡浠?,
  copy_command: "澶嶅埗鍛戒护",
  fast_signin: "蹇€熺櫥褰?,
  connect_player: "缁戝畾璐︽埛鎴栫櫥褰?,
  not_signed_in: "鏈櫥褰曘€?,
  paste_chat: "绮樿创鑱婂ぉ杈撳嚭鎴?token",
  paste_chat_placeholder: "鎶婂畬鏁磋亰澶╂秷鎭矘璐村埌杩欓噷锛岄〉闈細鑷姩鎻愬彇 token銆?,
  token_placeholder: "Token 浼氳嚜鍔ㄦ樉绀哄湪杩欓噷",
  account_username_placeholder: "璐︽埛鍚?,
  account_password_placeholder: "瀵嗙爜",
  bind_account: "缁戝畾璐︽埛",
  account_login: "璐︽埛鐧诲綍",
  token_helper: "棣栨浣跨敤锛氱矘璐?token锛屽～鍐欒处鎴峰悕鍜屽瘑鐮佸悗鐐瑰嚮缁戝畾銆備箣鍚庡彲鐩存帴浣跨敤璐︽埛鍚嶅拰瀵嗙爜鐧诲綍銆?,
  market_network: "甯傚満缃戠粶",
  browse_markets: "娴忚甯傚満",
  refresh: "鍒锋柊",
  market_sidebar_hint: "閫夋嫨涓€涓晢鍩庣粓绔悗锛屽嵆鍙祻瑙堝叾鍟嗗搧鐩綍銆佸湪鍞寕鍗曘€佹眰璐鍗曞拰浠锋牸璧板娍銆?,
  brand_kicker: "Monpai 鍦ㄧ嚎甯傚満",
  language_label: "璇█",
  empty_select_market: "閫夋嫨涓€涓晢鍩庣粓绔潵娴忚鍟嗗搧銆佹寕鍗曘€佹眰璐拰浠锋牸璧板娍銆?,
  market_overview: "甯傚満姒傝",
  browse_goods: "娴忚鍟嗗搧",
  commodity_shelf: "鍟嗗搧璐ф灦",
  search_placeholder: "鎼滅储鐗╁搧鍚嶆垨鍟嗗搧閿?,
  price_range: "浠锋牸鍖洪棿",
  min_price: "鏈€浣庝环",
  max_price: "鏈€楂樹环",
  sort_name: "鍚嶇О",
  sort_price: "浠锋牸",
  sort_time: "鏃堕棿",
  sort_quantity: "鏁伴噺",
  all_types: "鍏ㄩ儴绫诲瀷",
  all_rarities: "鍏ㄩ儴绋€鏈夊害",
  rarity_filter: "绋€鏈夊害",
  sort_label: "鎺掑簭",
  inventory_tab: "浠撳偍",
  inventory_title: "鍩庨晣浠撳簱",
  no_inventory_rows: "褰撳墠缁堢杩樻病鏈夊彲鏄剧ず鐨勫簱瀛樸€?,
  inventory_requires_manage: "鍙湁褰撳墠鐮佸ご鎴栧競鍦虹鐞嗗憳鍙互鏌ョ湅搴撳瓨銆?,
  category_all: "鍏ㄩ儴",
  category_wood: "鏈ㄦ潗",
  category_luxury: "濂緢鍝?,
  category_food: "椋熺墿",
  category_ore: "鐭跨煶",
  category_gems: "瀹濈煶",
  category_metal: "閲戝睘",
  category_tools: "宸ュ叿",
  category_spices: "棣欐枡",
  category_plant: "妞嶇墿",
  category_crop: "浣滅墿",
  category_material: "鏉愭枡",
  category_mob_drop: "鐢熺墿鎺夎惤",
  category_alchemy: "鐐奸噾",
  category_building: "寤虹瓚",
  category_nether: "涓嬬晫",
  category_end: "鏈湴",
  category_treasure: "瀹濊棌",
  category_redstone: "绾㈢煶",
  category_utility: "瀹炵敤",
  category_weapon: "姝﹀櫒",
  category_armor: "鎶ょ敳",
  category_other: "鍏朵粬",
  rarity_common: "鏅€?,
  rarity_uncommon: "浼樼",
  rarity_rare: "绋€鏈?,
  rarity_epic: "鍙茶瘲",
  rarity_legend: "浼犺",
  rarity_extraordinary: "瓒呭嚒",
  no_match: "娌℃湁绗﹀悎绛涢€夋潯浠剁殑鍟嗗搧銆?,
  selected_commodity: "褰撳墠鍟嗗搧",
  lowest_sell: "鏈€浣庡湪鍞?,
  highest_buy: "鏈€楂樻眰璐?,
  avg_24h: "24灏忔椂鍧囦环",
  trades_24h: "24灏忔椂鎴愪氦",
  sell_listings: "鍦ㄥ敭鎸傚崟",
  on_sale: "鍦ㄥ敭鏁伴噺",
  buying_demand: "姹傝喘闇€姹?,
  in_storage: "浠撳偍搴撳瓨",
  browse_tab: "娴忚鍟嗗搧",
  purchase_tab: "璐拱鍟嗗搧",
  chart_tab: "浠锋牸鍥捐〃",
  market_index_tab: "甯傚満鎸囨暟",
  units_live: "鍦ㄥ敭鍗曚綅",
  units_wanted: "姹傝喘鍗曚綅",
  volume_label: "鎴愪氦閲?,
  selling: "鍦ㄥ敭",
  buying: "姹傝喘",
  my_buy_orders: "鎴戠殑姹傝喘鍗?,
  price_history: "浠锋牸鍘嗗彶",
  recent_buckets: "杩戞湡鍒嗘《",
  chart_context: "鍥捐〃鐜",
  sell_item: "涓婃灦鍟嗗搧",
  create_listing: "鍒涘缓鎸傚崟",
  create_buy_order: "鍒涘缓姹傝喘鍗?,
  claim_credits: "棰嗗彇璐ф",
  retry_dispatch: "閲嶈瘯鍙戣揣",
  terminal_type_label: "鍙戣揣缁堢",
  terminal_type_port: "娓彛",
  terminal_type_post_station: "椹跨珯",
  market_notes: "甯傚満璇存槑",
  sell_summary: "鍗栫洏鎽樿",
  demand_summary: "涔扮洏娴佸姩鎬?,
  market_snapshot: "甯傚満蹇収",
  no_commodity_data: "褰撳墠甯傚満杩樻病鏈夊晢鍝佹暟鎹€?,
  no_selling_rows: "褰撳墠鍟嗗搧娌℃湁鍦ㄥ敭鎸傚崟銆?,
  no_buy_rows: "褰撳墠鍟嗗搧娌℃湁鍏紑姹傝喘銆?,
  no_my_buy_rows: "浣犺繕娌℃湁杩欎釜鍟嗗搧鐨勬眰璐崟銆?,
  no_chart_rows: "褰撳墠鍟嗗搧杩樻病鏈変环鏍艰褰曘€?,
  no_storage_match: "褰撳墠娌℃湁涓庤鍟嗗搧鍖归厤鐨勪粨鍌ㄦ潯鐩€?,
  seller_note: "鍗栧澶囨敞",
  manual_price: "\u624b\u52a8\u5355\u4ef7",
  manual_price_help: "\u8bf7\u76f4\u63a5\u8f93\u5165\u73a9\u5bb6\u60f3\u8981\u7684\u5355\u4ef7\u3002\u670d\u52a1\u7aef\u4f1a\u81ea\u52a8\u6362\u7b97\u4e3a bp \u504f\u79fb\uff0c\u53ea\u5141\u8bb8\u4f7f\u7528\u8303\u56f4\u5185\u7684\u4ef7\u683c\u3002",
  listing_price_preview: "\u5355\u4ef7\u9884\u89c8",
  listing_bp_preview: "\u63a8\u5bfc bp",
  listing_price_range: "\u5141\u8bb8\u8303\u56f4",
  listing_price_invalid: "\u5b9a\u4ef7\u4e0d\u5728\u5141\u8bb8\u8303\u56f4\u5185\u3002",
  suggested_word: "寤鸿浠?,
  min_bp: "鏈€浣?bp",
  max_bp: "鏈€楂?bp",
  to_word: "鍒?,
  offline_word: "绂荤嚎",
  online_word: "鍦ㄧ嚎",
  connected_word: "宸茶繛鎺?,
  signed_in_as: "褰撳墠鐧诲綍涓?{name}銆?,
  sign_in_required: "闇€瑕佸厛杈撳叆鐧诲綍 token銆?,
  bind_requires_credentials: "缁戝畾璐︽埛闇€瑕佸～鍐欒处鎴峰悕鍜屽瘑鐮併€?,
  account_login_required: "鐧诲綍闇€瑕佸～鍐欒处鎴峰悕鍜屽瘑鐮併€?,
  account_bound: "宸茬粦瀹氳处鎴?{name}銆?,
  account_login_success: "宸蹭娇鐢ㄨ处鎴?{name} 鐧诲綍銆?,
  sign_in_to_trade: "鐧诲綍鍚庢墠鑳借喘涔般€佷笂鏋躲€佹眰璐€佸彇娑堛€侀鍙栬揣娆炬垨鎵ц鍙戣揣銆?,
  copied: "宸插鍒躲€?,
  command_copied: "鍛戒护宸插鍒讹紝璇峰洖鍒版父鎴忓唴鎵ц銆?,
  token_extracted: "宸叉彁鍙?token 骞跺鍒跺埌鍓创鏉裤€?,
  clipboard_failed: "鏃犳硶璁块棶鍓创鏉匡紝璇锋墜鍔ㄥ鍒躲€?,
  markets_refreshed: "甯傚満鍒楄〃宸插埛鏂般€?,
  action_completed: "鎿嶄綔宸插畬鎴愩€?,
  sign_in_to_load_markets: "娓稿鍙祻瑙堝競鍦猴紝鐧诲綍鍚庤В閿佷氦鏄撳拰鍙戣揣鎿嶄綔銆?,
  guest_mode_ready: "璁垮妯″紡锛氬彲浠ユ祻瑙堝競鍦轰笌鍟嗗搧锛屼絾涓嶈兘鎵ц浜ゆ槗鎿嶄綔銆?,
  no_markets: "褰撳墠杩樻病鏈夋敞鍐屼换浣曞競鍦虹粓绔€?,
  select_market_prompt: "閫夋嫨涓€涓競鍦虹粓绔潵娴忚瀹冪殑鍟嗗搧鐩綍銆?,
  guest_word: "璁垮",
  live_terminal: "鍦ㄧ嚎缁堢",
  chunk_cold: "鍖哄潡鏈姞杞?,
  manage: "鍙鐞?,
  view: "浠呮煡鐪?,
  loaded: "宸插姞杞?,
  cold: "鏈姞杞?,
  dock_linked: "\u5df2\u7ed1\u5b9a\u4ed3\u5e93",
  no_linked_dock: "\u672a\u7ed1\u5b9a\u4ed3\u5e93",
  manager_access: "绠＄悊鏉冮檺",
  read_only: "鍙",
  browse_only: "浠呮祻瑙?,
  listings_word: "鎸傚崟",
  market: "甯傚満",
  quantity: "鏁伴噺",
  total: "鎬讳环",
  actions: "鎿嶄綔",
  seller: "鍗栧",
  buyer: "涔板",
  available: "鍙敭",
  reserved: "棰勭暀",
  unit_price: "鍗曚环",
  dock: "娓彛",
  warehouse_word: "浠撳簱",
  status: "鐘舵€?,
  price_band: "浠锋牸鍖洪棿",
  implied_bid: "鎺ㄧ畻鍑轰环",
  time: "鏃堕棿",
  average: "鍧囦环",
  low: "鏈€浣?,
  high: "鏈€楂?,
  volume: "鎴愪氦閲?,
  buy_1: "璐拱 1",
  cancel: "鍙栨秷",
  current_sell_side: "褰撳墠鍗栫洏",
  current_buy_side: "褰撳墠涔扮洏",
  dock_stock: "浠撳偍搴撳瓨",
  terminal_status: "缁堢鐘舵€?,
  chart_disabled: "閰嶇疆宸插叧闂环鏍煎浘琛ㄣ€?,
  no_chart_buckets: "褰撳墠鍟嗗搧杩樻病鏈変环鏍煎垎妗舵暟鎹€?,
  chart_no_selection: "褰撳墠娌℃湁鍙睍绀哄浘琛ㄧ殑鍟嗗搧銆?,
  chart_library_missing: "鍥捐〃搴撴湭鍔犺浇锛屽凡鍒囨崲鍒板唴缃?K 绾挎覆鏌撱€?,
  chart_init_failed: "鍥捐〃鍒濆鍖栧け璐ワ紝宸插垏鎹㈠埌鍐呯疆 K 绾挎覆鏌撱€?,
  chart_render_failed: "鍥捐〃娓叉煋澶辫触銆?,
  no_demand_ladder: "褰撳墠鍟嗗搧杩樻病鏈夊舰鎴愭眰璐闃熴€?,
  waiting_reference: "鏆傛棤鏄庣‘鍦ㄥ敭浠峰弬鑰冦€?,
  around_current_ask: "鎸夊綋鍓嶅崠浠锋帹绠楀ぇ鑷村湪 {range}銆?,
  no_storage: "鏃犱粨鍌?,
  storage_rows: "浠撳偍鏉＄洰",
  live_requests: "娲昏穬姹傝喘",
  stock_rows: "搴撳瓨鏉＄洰",
  owner_word: "鎵€鏈夎€?,
  town_word: "鍩庨晣",
  pending_credits: "寰呴璐ф",
  commodity_types: "鍟嗗搧绉嶇被",
  storage_units: "浠撳偍鎬婚噺",
  open_demand: "寮€鏀鹃渶姹?,
  my_buy_orders_metric: "鎴戠殑姹傝喘鍗?,
  net_balance: "鍑€浣欓",
  reference_price: "鍙傝€冧环",
  liquidity_score: "娴佸姩鎬?,
  market_index: "甯傚満鎸囨暟",
  category_index: "鍒嗙被鎸囨暟",
  pressure_model: "浠锋牸褰卞搷妯″瀷",
  inventory_pressure: "搴撳瓨鍘嬪姏",
  buy_pressure: "涔扮洏鍘嬪姏",
  volatility_word: "娉㈠姩",
  timeframe: "鍛ㄦ湡",
  indicators: "鎸囨爣",
  chart_controls: "鍥捐〃鎺у埗",
  log_scale: "瀵规暟鍧愭爣",
  inflation_adjust: "閫氳儉淇",
  inflation_unavailable: "褰撳墠娌℃湁 CPI 鏁版嵁锛屾殏涓嶅彲鐢ㄣ€?,
  reset_zoom: "閲嶇疆缂╂斁",
  current_change: "褰撳墠娑ㄨ穼",
  max_drawdown: "鏈€澶у洖鎾?,
  inception_return: "璧峰鏀剁泭",
  chart_hover_empty: "灏嗛紶鏍囩Щ鍔ㄥ埌鍥捐〃涓婁互鏌ョ湅 OHLC 涓庢垚浜ら噺",
  market_index_chart: "甯傚満鎸囨暟璧板娍",
  category_index_chart: "鍒嗙被鎸囨暟璧板娍",
  cpi_chart: "娑堣垂浠锋牸鎸囨暟",
  loans_chart: "鏈伩璐锋璧板娍",
  inflation_since_inception: "绱閫氳儉",
  loans_change: "璐锋鍙樺寲",
  cpi_now: "褰撳墠 CPI",
  macro_overview: "瀹忚姒傝"
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

Object.assign(I18N["zh-CN"], {
  hero_title: "Monpai 鍦ㄧ嚎甯傚満",
  hero_subtitle: "鍏堟祻瑙堝競鍦虹粓绔笌浠锋牸锛屽啀鍐冲畾鏄惁缁戝畾缃戦〉璐︽埛杩涜浜ゆ槗銆佷笂鏋舵垨鐗╂祦绠＄悊銆?,
  access_panel: "鐧诲綍",
  access_panel_open: "鏀惰捣鐧诲綍",
  access_panel_closed: "鐧诲綍",
  portal_kicker: "缃戦〉鐧诲綍",
  auth_title: "鍏堢粦瀹氱綉椤佃处鎴凤紝鍐嶇绾跨櫥褰?,
  auth_subtitle: "棣栨浣跨敤鏃讹紝浠庢父鎴忎腑鑾峰彇 token锛岃缃綉椤佃处鎴峰拰瀵嗙爜瀹屾垚缁戝畾銆備箣鍚庡彲鐩存帴浣跨敤璐︽埛鍚嶅拰瀵嗙爜鐧诲綍銆?,
  command_label: "娓告垙鍐呭懡浠?,
  copy_command: "澶嶅埗鍛戒护",
  fast_signin: "蹇€熺櫥褰?,
  connect_player: "缁戝畾璐︽埛鎴栫櫥褰?,
  not_signed_in: "鏈櫥褰曘€?,
  paste_chat: "绮樿创鑱婂ぉ杈撳嚭鎴?token",
  paste_chat_placeholder: "鎶婂畬鏁磋亰澶╂秷鎭矘璐村埌杩欓噷锛岄〉闈細鑷姩鎻愬彇 token銆?,
  token_placeholder: "Token 浼氳嚜鍔ㄦ樉绀哄湪杩欓噷",
  account_username_placeholder: "璐︽埛鍚?,
  account_password_placeholder: "瀵嗙爜",
  bind_account: "缁戝畾璐︽埛",
  account_login: "璐︽埛鐧诲綍",
  token_helper: "棣栨浣跨敤锛氱矘璐?token锛屽～鍐欒处鎴峰悕鍜屽瘑鐮佸悗鐐瑰嚮缁戝畾銆備箣鍚庡彲鐩存帴浣跨敤璐︽埛鍚嶅拰瀵嗙爜鐧诲綍銆?,
  market_network: "甯傚満缃戠粶",
  browse_markets: "娴忚甯傚満",
  refresh: "鍒锋柊",
  market_search_label: "鏌ユ壘缁堢",
  market_search_placeholder: "鎼滅储甯傚満銆佹墍鏈夎€呫€佸煄闀囨垨缁村害",
  market_sidebar_hint: "閫夋嫨涓€涓競鍦虹粓绔悗锛屽嵆鍙祻瑙堝畠鐨勫晢鍝佺洰褰曘€佸湪鍞寕鍗曘€佹眰璐鍗曞拰浠锋牸璧板娍銆?,
  market_summary_title: "缃戠粶鎽樿",
  market_summary_hint: "蹇€熸煡鐪嬬粓绔暟閲忋€佸湪绾垮尯鍧楀拰褰撳墠閫夋嫨鐨勫競鍦恒€?,
  market_terminal_count: "缁堢鏁?,
  selected_market: "褰撳墠甯傚満",
  brand_kicker: "Monpai 鍦ㄧ嚎甯傚満",
  language_label: "璇█",
  market_overview: "甯傚満姒傝",
  explore_tab: "娴忚",
  trade_tab: "浜ゆ槗",
  analytics_tab: "鍒嗘瀽",
  explore_hint: "娴忚鐩綍銆佹瘮杈冧环鏍煎苟蹇€熷垏鎹㈠晢鍝併€?,
  trade_hint: "闆嗕腑澶勭悊璐拱銆佷笂鏋躲€侀娆惧拰姹傝喘鎿嶄綔銆?,
  inventory_hint: "鏌ョ湅鍙笂鏋舵垨鍙彂杩愮殑搴撳瓨銆?,
  analytics_hint: "鏌ョ湅浠锋牸璧板娍銆佹寚鏁板彉鍖栧拰瀹忚淇″彿銆?,
  signin_status_guest: "绔嬪嵆鐧诲綍",
  signin_status_ready: "璐︽埛涓績",
  dock_linked: "宸茬粦瀹氫粨搴?,
  no_linked_dock: "鏈粦瀹氫粨搴?,
  chart_disabled: "閰嶇疆宸插叧闂环鏍煎浘琛ㄣ€?,
  no_chart_buckets: "褰撳墠鍟嗗搧杩樻病鏈変环鏍煎垎妗舵暟鎹€?,
  chart_no_selection: "褰撳墠娌℃湁鍙睍绀哄浘琛ㄧ殑鍟嗗搧銆?,
  chart_library_missing: "鍥捐〃搴撴湭鍔犺浇锛屽凡鍒囨崲鍒板唴缃覆鏌撱€?,
  chart_init_failed: "鍥捐〃鍒濆鍖栧け璐ワ紝宸插垏鎹㈠埌鍐呯疆娓叉煋銆?,
  chart_render_failed: "鍥捐〃娓叉煋澶辫触銆?,
  no_chart_rows: "褰撳墠鍟嗗搧杩樻病鏈変环鏍艰褰曘€?,
  timeframe: "鍛ㄦ湡",
  indicators: "鎸囨爣",
  chart_controls: "鍥捐〃鎺у埗",
  log_scale: "瀵规暟鍧愭爣",
  inflation_adjust: "閫氳儉淇",
  inflation_unavailable: "褰撳墠娌℃湁 CPI 鏁版嵁锛屾殏涓嶅彲鐢ㄣ€?,
  reset_zoom: "閲嶇疆缂╂斁",
  current_change: "褰撳墠娑ㄨ穼",
  max_drawdown: "鏈€澶у洖鎾?,
  inception_return: "璧峰鏀剁泭",
  chart_hover_empty: "灏嗛紶鏍囩Щ鍔ㄥ埌鍥捐〃涓婁互鏌ョ湅 OHLC 涓庢垚浜ら噺",
  market_index_chart: "甯傚満鎸囨暟璧板娍",
  category_index_chart: "鍒嗙被鎸囨暟璧板娍",
  cpi_chart: "娑堣垂浠锋牸鎸囨暟",
  loans_chart: "鏈伩璐锋璧板娍",
  inflation_since_inception: "绱閫氳儉",
  loans_change: "璐锋鍙樺寲",
  cpi_now: "褰撳墠 CPI",
  macro_overview: "瀹忚姒傝"
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
  return state.settings.uiTitle || "Monpai Online Market 路 Monpai鍦ㄧ嚎甯傚満";
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
  state.status = isError ? "" : message || "";
  state.error = isError ? message || "" : "";
  if (els.statusLive) {
    els.statusLive.textContent = message || "";
  }
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
    ? ` 路 @${state.session.accountUsername}`
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
              <div class="overview-subtitle">${escapeHtml(t("owner_word"))} ${escapeHtml(detail.ownerName || "-")} 路 ${escapeHtml(t("warehouse_word"))} ${escapeHtml(detail.linkedWarehouseName || detail.linkedDockName || "-")} 路 ${escapeHtml(t("town_word"))} ${escapeHtml(detail.townName || "-")}</div>
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
      updateCreateListingFormState();
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
  const createListingStorage = document.querySelector("#create-listing-storage");
  const createListingPrice = document.querySelector("#create-listing-price");
  const createListingQuantity = document.querySelector("#create-listing-quantity");
  if (createListingStorage) {
    createListingStorage.addEventListener("change", () => updateCreateListingFormState());
  }
  if (createListingPrice) {
    createListingPrice.addEventListener("input", () => updateCreateListingFormState());
  }
  if (createListingQuantity) {
    createListingQuantity.addEventListener("input", () => updateCreateListingFormState());
  }
  if (createListingButton) {
    createListingButton.addEventListener("click", () => postMarketAction("/listings", {
      storageIndex: numberValue("#create-listing-storage", -1),
      quantity: numberValue("#create-listing-quantity", 1),
      unitPrice: numberValue("#create-listing-price", 0),
      sellerNote: valueOf("#create-listing-note")
    }));
    updateCreateListingFormState();
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
              <span class="catalog-subcategory-hint">${escapeHtml(t("all_types"))} 路 ${escapeHtml(categoryGroupLabel(expandedGroup))}</span>
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
  const indicator = direction === "desc" ? "鈫? : direction === "asc" ? "鈫? : "";
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
          commodityIconCache.set(commodityKey, { status: "failed", src: "" });
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
  const suggestedUnitPrice = Math.max(1, Number(preferredStorage?.suggestedUnitPrice) || Number(commodity?.suggestedUnitPrice) || Number(commodity?.referencePrice) || 1);
  const minAllowedUnitPrice = Math.max(1, Number(preferredStorage?.minAllowedUnitPrice) || suggestedUnitPrice);
  const maxAllowedUnitPrice = Math.max(minAllowedUnitPrice, Number(preferredStorage?.maxAllowedUnitPrice) || minAllowedUnitPrice);
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
          <div class="summary-note">${escapeHtml(commodity.displayName)} 路 ${number(sumBy(matchingStorage, "quantity"))} ${escapeHtml(t("in_storage"))}.</div>
          <input id="create-listing-storage" type="hidden" value="${escapeHtml(String(selectedStorageIndex))}">
          <div class="summary-note">${escapeHtml(t("manual_price_help"))}</div>
          ${renderStorageChoiceGrid(matchingStorage, selectedStorageIndex, t("no_storage_match"))}
          <input id="create-listing-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultListingQuantity || 1))}" placeholder="${escapeHtml(t("quantity"))}">
          <input id="create-listing-price" type="number" min="1" value="${escapeHtml(String(suggestedUnitPrice))}" placeholder="${escapeHtml(t("manual_price"))}">
          <div id="create-listing-price-preview" class="summary-note">${escapeHtml(t("listing_price_preview"))} ${number(suggestedUnitPrice)} | ${escapeHtml(t("listing_price_range"))} ${number(minAllowedUnitPrice)} - ${number(maxAllowedUnitPrice)}</div>
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
            <span class="minor">${escapeHtml(t("lowest_sell"))}${commodity.bestBuy == null ? "" : ` 路 ${escapeHtml(t("highest_buy"))} ${number(commodity.bestBuy)} bp`}</span>
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
          <div class="summary-note">${escapeHtml(t("avg_24h"))} ${latestPoint ? number(latestPoint.averageUnitPrice) : "--"} 路 ${escapeHtml(t("trades_24h"))} ${number(chartStats.tradeCount)}.</div>
          <div class="summary-note">${escapeHtml(t("in_storage"))} ${number(commodity.storageUnits)} 路 ${escapeHtml(t("open_demand"))} ${number(commodity.demandUnits)}.</div>
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
  const suggestedUnitPrice = Math.max(1, Number(preferredStorage?.suggestedUnitPrice) || Number(commodity?.suggestedUnitPrice) || Number(commodity?.referencePrice) || 1);
  const minAllowedUnitPrice = Math.max(1, Number(preferredStorage?.minAllowedUnitPrice) || suggestedUnitPrice);
  const maxAllowedUnitPrice = Math.max(minAllowedUnitPrice, Number(preferredStorage?.maxAllowedUnitPrice) || minAllowedUnitPrice);

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
          <div class="summary-note">${preferredStorage ? `${escapeHtml(commodity.displayName)} x${number(preferredStorage.quantity)} 路 ${escapeHtml(t("suggested_word"))} ${number(preferredStorage.suggestedUnitPrice)}` : escapeHtml(t("no_storage_match"))}</div>
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
              <div class="summary-note">${escapeHtml(t("manual_price_help"))}</div>
              <div class="summary-note">${escapeHtml(t("listing_price_range"))} ${number(minAllowedUnitPrice)} - ${number(maxAllowedUnitPrice)} | ${escapeHtml(t("suggested_word"))} ${number(suggestedUnitPrice)}</div>
              <input id="create-listing-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultListingQuantity || 1))}" placeholder="${escapeHtml(t("quantity"))}">
              <input id="create-listing-price" type="number" min="1" value="${escapeHtml(String(suggestedUnitPrice))}" placeholder="${escapeHtml(t("manual_price"))}">
              <div id="create-listing-price-preview" class="summary-note">${escapeHtml(t("listing_price_preview"))} ${number(suggestedUnitPrice)} | ${escapeHtml(t("listing_price_range"))} ${number(minAllowedUnitPrice)} - ${number(maxAllowedUnitPrice)}</div>
              <textarea id="create-listing-note" placeholder="${escapeHtml(t("seller_note"))}"></textarea>
              <div class="actions">
                <button type="button" id="create-listing-button" data-can-create="${canManage && canAct ? "true" : "false"}" ${canManage && canAct ? "" : "disabled"}>${escapeHtml(t("create_listing"))}</button>
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
          <div class="summary-note">${escapeHtml(detail.linkedDock ? t("dock_linked") : t("no_linked_dock"))} 路 ${escapeHtml(detail.linkedDockName || "-")}.</div>
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
            <div class="muted-inline">${impact?.referenceUnitPrice ? number(impact.referenceUnitPrice) : "--"} 路 ${escapeHtml(t("liquidity_score"))} ${number(impact?.liquidityScore || 0)}</div>
          </div>
          <div class="chart-row">
            <strong>${escapeHtml(t("pressure_model"))}</strong>
            <div class="muted-inline">${escapeHtml(t("inventory_pressure"))} ${number(impact?.inventoryPressureBp || 0)} bp 路 ${escapeHtml(t("buy_pressure"))} ${number(impact?.buyPressureBp || 0)} bp 路 ${escapeHtml(t("volatility_word"))} ${number(impact?.volatilityBp || 0)} bp</div>
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
      <div class="muted-inline">${last ? number(last.value) : "--"} 路 ${sign}${number(delta)} 路 ${t("trades_24h")} ${number(last?.tradeCount || 0)}</div>
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
    renderChartFallbackMessage(container, "no_chart_buckets", `${commodity.displayName} 路 ${state.activeChartTimeframe.toUpperCase()}`);
    return;
  }
  if (typeof LightweightCharts === "undefined") {
    setChartFailure("library-missing", "window.LightweightCharts is undefined.");
    renderChartFallbackMessage(container, "chart_library_missing", `${commodity.displayName} 路 ${state.activeChartTimeframe.toUpperCase()}`);
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
    renderChartFallbackMessage(container, "chart_init_failed", `${commodity.displayName} 路 ${state.activeChartTimeframe.toUpperCase()}`);
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
  if (!valid) {
    preview.textContent = `${t("listing_price_invalid")} ${number(minAllowed)} - ${number(maxAllowed)} | ${t("suggested_word")} ${number(entry.suggestedUnitPrice)}`;
    preview.classList.add("error");
    return;
  }

  preview.textContent = `${t("listing_price_preview")} ${number(requestedPrice)} | ${t("listing_bp_preview")} ${number(derivedBp)} bp | ${t("listing_price_range")} ${number(minAllowed)} - ${number(maxAllowed)}`;
  preview.classList.remove("error");
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

function activeWorkspaceSection(route = state.activeProductTab) {
  const normalized = normalizePageRoute(route);
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
    <button type="button" class="workspace-button ${active ? "active" : ""}" data-route="${workspaceTargetRoute(section)}">
      <span class="workspace-kicker">${escapeHtml(t("mode_label"))}</span>
      <span class="workspace-title">${escapeHtml(label)}</span>
      <span class="muted-inline">${escapeHtml(workspaceHint(section))}</span>
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
    return;
  }

  const accountPart = state.session.accountBound && state.session.accountUsername
    ? ` 路 @${state.session.accountUsername}`
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
}

function renderMarkets() {
  const markets = filteredMarkets();
  renderMarketSummary(markets);

  if (!markets.length) {
    els.marketList.innerHTML = `<div class="empty-state">${escapeHtml(state.marketQuery ? t("no_match") : t("no_markets"))}</div>`;
    return;
  }

  els.marketList.innerHTML = markets.map((market) => `
    <button type="button" class="market-card ${market.marketId === state.selectedMarketId ? "active" : ""}" data-market-id="${escapeHtml(market.marketId)}">
      <p class="panel-meta">${market.loaded ? t("live_terminal") : t("chunk_cold")}</p>
      <h3>${escapeHtml(market.marketName)}</h3>
      <div class="muted">${escapeHtml(market.ownerName || market.townName || "-")}</div>
      <div class="muted">${escapeHtml(market.dimensionId || "-")} @ ${escapeHtml(market.position || "-")}</div>
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

function renderWorkspaceRail(detail, catalog, canAct, canManage) {
  const accessBody = canAct
    ? (canManage ? t("manager_access") : t("browse_only"))
    : `${t("guest_mode_ready")} ${t("sign_in_to_trade")}`;
  return `
    <div class="market-rail">
      <div class="section-copy">
        <p class="section-kicker">${escapeHtml(t("market_summary_title"))}</p>
        <strong>${escapeHtml(activeWorkspaceSection() === "inventory" ? t("inventory_tab") : t(`${activeWorkspaceSection()}_tab`))}</strong>
        <p>${escapeHtml(workspaceHint(activeWorkspaceSection()))}</p>
      </div>
      <div class="section-copy">
        <p class="section-kicker">${escapeHtml(t("session_label"))}</p>
        <strong>${escapeHtml(canAct ? (state.session?.playerName || t("signin_status_ready")) : t("signin_status_guest"))}</strong>
        <p>${escapeHtml(accessBody)}</p>
      </div>
      <div class="section-copy">
        <p class="section-kicker">${escapeHtml(t("commodity_types"))}</p>
        <strong>${escapeHtml(number(catalog.length))}</strong>
        <p>${escapeHtml(detail.linkedDock ? t("dock_linked") : t("no_linked_dock"))}</p>
      </div>
    </div>
  `;
}

function renderDetail() {
  const bars = [];
  if (state.status) {
    bars.push(`<div class="status-bar" aria-live="polite">${escapeHtml(state.status)}</div>`);
  }
  if (state.error) {
    bars.push(`<div class="status-bar error" aria-live="polite">${escapeHtml(state.error)}</div>`);
  }

  const workspaceNav = `
    <div class="workspace-toolbar">
      <div class="workspace-switch">
        ${workspaceButton("explore", t("explore_tab"))}
        ${workspaceButton("trade", t("trade_tab"))}
        ${state.detail?.canManage ? workspaceButton("inventory", t("inventory_tab")) : ""}
        ${workspaceButton("analytics", t("analytics_tab"))}
      </div>
    </div>
  `;

  if (!state.detail) {
    els.marketDetail.innerHTML = `${bars.join("")}<div class="market-shell">${workspaceNav}<div class="empty-state">${escapeHtml(t("select_market_prompt"))}</div></div>`;
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
      ${workspaceNav}
      <section class="market-overview">
        <div class="market-header-card">
          <div class="market-overview-layout">
            <div class="overview-main">
              <div class="detail-header">
                <div class="overview-title">
                  <p class="section-kicker">${escapeHtml(t("market_overview"))}</p>
                  <h2>${escapeHtml(detail.marketName)}</h2>
                  <div class="overview-subtitle">${escapeHtml(t("owner_word"))} ${escapeHtml(detail.ownerName || "-")} 路 ${escapeHtml(t("warehouse_word"))} ${escapeHtml(detail.linkedWarehouseName || detail.linkedDockName || "-")} 路 ${escapeHtml(t("town_word"))} ${escapeHtml(detail.townName || "-")}</div>
                </div>
                <div class="market-meta">
                  <span class="pill ${detail.linkedDock ? "success" : "warning"}">${detail.linkedDock ? escapeHtml(t("dock_linked")) : escapeHtml(t("no_linked_dock"))}</span>
                  <span class="pill">${canManage ? escapeHtml(t("manager_access")) : escapeHtml(t("read_only"))}</span>
                  <span class="pill">${canAct ? escapeHtml(t("signin_status_ready")) : escapeHtml(t("signin_status_guest"))}</span>
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
            ${renderWorkspaceRail(detail, catalog, canAct, canManage)}
          </div>
        </div>
      </section>

      <section class="goods-market">
        ${state.activeProductTab === "browse"
          ? browseSection
          : (state.activeProductTab === "inventory"
            ? (inventorySelectedCommodity
              ? renderInventoryDetailPage(inventorySelectedCommodity, detail, canManage, canAct)
              : inventorySection)
            : (selectedCommodity
              ? renderCommodityDetailPage(selectedCommodity, detail, canManage, canAct)
              : browseSection))}
      </section>
    </div>
  `;

  bindDetailActions();
  hydrateCommodityIcons();
  hydrateLightweightChart();
}

function renderCommodityDetailPage(commodity, detail, canManage, canAct) {
  const activeSeries = primaryChartSeries(commodity);
  const latestPoint = latestChartPoint(activeSeries);
  const chartStats = chartSummary(activeSeries);
  const workspaceSection = activeWorkspaceSection();
  const detailNav = workspaceSection === "trade"
    ? `<div class="tab-strip">${routeButton("sell", t("selling"))}${routeButton("buy", t("buying"))}</div>`
    : workspaceSection === "analytics"
      ? `<div class="tab-strip">${routeButton("chart", t("chart_tab"))}${routeButton("index", t("market_index_tab"))}</div>`
      : `<div class="tab-strip">${routeButton("browse", t("browse_tab"))}${routeButton("sell", t("selling"))}${routeButton("chart", t("chart_tab"))}</div>`;

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
            <span class="minor">${escapeHtml(t("lowest_sell"))}${commodity.bestBuy == null ? "" : ` 路 ${escapeHtml(t("highest_buy"))} ${number(commodity.bestBuy)} bp`}</span>
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

      ${detailNav}

      ${renderCommodityPage(commodity, detail, canManage, canAct)}
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
els.localeSelect?.addEventListener("change", (event) => {
  state.locale = event.target.value || "zh-CN";
  localStorage.setItem("marketWebLocale", state.locale);
  updateStaticCopy();
  renderMarkets();
  renderDetail();
});
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
