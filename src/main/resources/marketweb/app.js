const state = {
  sessionToken: localStorage.getItem("marketWebSessionToken") || "",
  session: null,
  markets: [],
  selectedMarketId: "",
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

const els = {
  commandPreview: document.querySelector("#command-preview"),
  copyCommandButton: document.querySelector("#copy-command-button"),
  chatCapture: document.querySelector("#chat-capture"),
  token: document.querySelector("#login-token"),
  loginButton: document.querySelector("#login-button"),
  tokenHelper: document.querySelector("#token-helper"),
  sessionStatus: document.querySelector("#session-status"),
  refreshMarkets: document.querySelector("#refresh-markets"),
  marketList: document.querySelector("#market-list"),
  marketDetail: document.querySelector("#market-detail")
};

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
    if (!response.ok) {
      return;
    }
    const data = await response.json();
    state.settings = { ...state.settings, ...(data || {}) };
  } catch (_) {
  }
  document.title = state.settings.uiTitle || "Sailboat Market";
  if (els.commandPreview) {
    els.commandPreview.textContent = state.settings.commandText || "/marketweb token";
  }
}

async function login() {
  const token = (els.token.value || "").trim();
  if (!token) {
    setStatus("Login token is required.", true);
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
    setStatus(`Signed in as ${state.session.playerName}.`);
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
    setStatus(successMessage || "Copied.");
  } catch (_) {
    setStatus("Clipboard access failed. Copy manually instead.", true);
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
    await copyText(token, "Token extracted and copied to clipboard.");
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
  if (!state.sessionToken) {
    state.markets = [];
    state.selectedMarketId = "";
    state.detail = null;
    renderMarkets();
    renderDetail();
    return;
  }
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
    renderDetail();
    return;
  }
  try {
    state.detail = await api(`/api/markets/${marketId}`);
    state.selectedMarketId = marketId;
    renderMarkets();
    renderDetail();
  } catch (error) {
    setStatus(error.message, true);
  }
}

async function postMarketAction(suffix, payload = {}) {
  if (!state.selectedMarketId) {
    return;
  }
  try {
    const data = await api(`/api/markets/${state.selectedMarketId}${suffix}`, {
      method: "POST",
      body: JSON.stringify(payload)
    });
    state.detail = data;
    setStatus("Action completed.");
    renderDetail();
  } catch (error) {
    setStatus(error.message, true);
  }
}

function renderSession() {
  if (!state.session) {
    els.sessionStatus.textContent = "Not signed in.";
    return;
  }
  els.sessionStatus.textContent = `${state.session.playerName} (${state.session.online ? "online" : "offline session"})`;
}

function renderMarkets() {
  if (!state.sessionToken) {
    els.marketList.innerHTML = '<div class="empty-state">Sign in to load markets.</div>';
    return;
  }
  if (state.markets.length === 0) {
    els.marketList.innerHTML = '<div class="empty-state">No market terminals registered yet.</div>';
    return;
  }
  els.marketList.innerHTML = state.markets.map((market) => `
    <button type="button" class="market-card ${market.marketId === state.selectedMarketId ? "active" : ""}" data-market-id="${escapeHtml(market.marketId)}">
      <h3>${escapeHtml(market.marketName)}</h3>
      <div class="muted">${escapeHtml(market.ownerName || "-")}</div>
      <div class="muted">${escapeHtml(market.dimensionId)} @ ${escapeHtml(market.position)}</div>
      <div class="actions">
        ${market.canManage ? '<span class="pill">Manage</span>' : ""}
        ${market.loaded ? '<span class="pill">Loaded</span>' : '<span class="pill">Chunk cold</span>'}
      </div>
    </button>
  `).join("");
  document.querySelectorAll("[data-market-id]").forEach((node) => {
    node.addEventListener("click", () => {
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
    els.marketDetail.innerHTML = `${bars.join("")}<div class="empty-state">Select a market to inspect listings, storage, orders, and dispatch.</div>`;
    return;
  }
  const detail = state.detail;
  const canManage = !!detail.canManage;
  els.marketDetail.innerHTML = `
    ${bars.join("")}
    <div class="detail-grid">
      <div class="section-card">
        <div class="panel-head">
          <div>
            <h2>${escapeHtml(detail.marketName)}</h2>
            <p class="muted">Owner: ${escapeHtml(detail.ownerName || "-")}</p>
          </div>
          <div class="actions">
            <span class="pill">${detail.linkedDock ? "Dock linked" : "No linked dock"}</span>
            <span class="pill">${escapeHtml(detail.linkedDockName || "-")}</span>
          </div>
        </div>
        <div class="summary-grid">
          ${metric("Pending credits", number(detail.pendingCredits))}
          ${metric("Town", detail.townName || "-")}
          ${metric("Storage rows", number((detail.storageEntries || []).length))}
          ${metric("Listings", number((detail.listings || []).length))}
          ${metric("My orders", number((detail.myOrders || []).length))}
          ${metric("Source orders", number((detail.sourceOrders || []).length))}
          ${state.settings.showTownEconomySummary ? metric("Net balance", number(detail.netBalance)) : ""}
        </div>
      </div>

      <div class="two-col">
        <div class="form-card">
          <h3>Create Listing</h3>
          <div class="stack">
            <select id="create-listing-storage">
              ${(detail.storageEntries || []).map((entry) => `<option value="${entry.index}">${escapeHtml(entry.itemName)} x${entry.quantity}</option>`).join("")}
            </select>
            <input id="create-listing-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultListingQuantity || 1))}" placeholder="Quantity">
            <input id="create-listing-adjustment" type="number" value="${escapeHtml(String(state.settings.defaultPriceAdjustmentBp || 0))}" placeholder="Price adjustment bp">
            <textarea id="create-listing-note" placeholder="Seller note"></textarea>
            <button type="button" id="create-listing-button" ${canManage ? "" : "disabled"}>Create listing</button>
          </div>
        </div>

        <div class="form-card">
          <h3>Buy Order</h3>
          <div class="stack">
            <input id="buy-order-key" type="text" placeholder="minecraft:oak_log">
            <input id="buy-order-quantity" type="number" min="1" value="${escapeHtml(String(state.settings.defaultBuyOrderQuantity || 1))}" placeholder="Quantity">
            <input id="buy-order-min" type="number" value="${escapeHtml(String(state.settings.defaultBuyOrderMinPriceBp ?? -1000))}" placeholder="Min bp">
            <input id="buy-order-max" type="number" value="${escapeHtml(String(state.settings.defaultBuyOrderMaxPriceBp ?? 1000))}" placeholder="Max bp">
            <div class="actions">
              <button type="button" id="buy-order-button">Create buy order</button>
              <button type="button" id="claim-credits-button" class="secondary">Claim credits</button>
              <button type="button" id="dispatch-button" class="warn" ${canManage ? "" : "disabled"}>Retry dispatch</button>
            </div>
          </div>
        </div>
      </div>

      ${tableSection("Storage", ["Item", "Qty", "Suggested unit price", "Detail"], (detail.storageEntries || []).map((entry) => `
        <tr>
          <td>${escapeHtml(entry.itemName)}</td>
          <td>${number(entry.quantity)}</td>
          <td>${number(entry.suggestedUnitPrice)}</td>
          <td>${escapeHtml(entry.detail || "")}</td>
        </tr>
      `).join(""), "No accessible dock storage.")}

      ${tableSection("Listings", ["Item", "Available", "Reserved", "Unit price", "Seller", "Dock", "Actions"], (detail.listings || []).map((entry) => `
        <tr>
          <td>
            <strong>${escapeHtml(entry.itemName)}</strong><br>
            <span class="muted">${escapeHtml(entry.commodityKey || "")}</span>
          </td>
          <td>${number(entry.availableCount)}</td>
          <td>${number(entry.reservedCount)}</td>
          <td>${number(entry.unitPrice)}</td>
          <td>${escapeHtml(entry.sellerName || "-")}</td>
          <td>${escapeHtml(entry.sourceDockName || "-")}</td>
          <td>
            <div class="actions">
              <button type="button" data-purchase-index="${entry.index}">Buy 1</button>
              ${canManage ? `<button type="button" class="danger" data-cancel-listing="${escapeHtml(entry.listingId || "")}">Cancel</button>` : ""}
            </div>
          </td>
        </tr>
      `).join(""), "No listings available.")}

      ${tableSection("My Orders", ["Order", "Quantity", "Total", "Route", "Status"], (detail.myOrders || []).map((entry) => `
        <tr>
          <td>${escapeHtml(entry.orderId)}</td>
          <td>${number(entry.quantity)}</td>
          <td>${number(entry.totalPrice)}</td>
          <td>${escapeHtml(entry.sourceDockName || "-")} -> ${escapeHtml(entry.targetDockName || "-")}</td>
          <td>${escapeHtml(entry.status || "-")}${entry.shipping ? `<br><span class="muted">${escapeHtml(entry.shipping.boatName || "-")} / ${escapeHtml(entry.shipping.routeName || "-")}</span>` : ""}</td>
        </tr>
      `).join(""), "No orders for this account.")}

      ${tableSection("Source Orders", ["Route", "Quantity", "Status"], (detail.sourceOrders || []).map((entry) => `
        <tr>
          <td>${escapeHtml(entry.sourceDockName || "-")} -> ${escapeHtml(entry.targetDockName || "-")}</td>
          <td>${number(entry.quantity)}</td>
          <td>${escapeHtml(entry.status || "-")}</td>
        </tr>
      `).join(""), "No source orders waiting at this market.")}

      ${state.settings.showShippingPanel ? tableSection("Shipping", ["Boat", "Route", "Mode"], (detail.shippingEntries || []).map((entry) => `
        <tr>
          <td>${escapeHtml(entry.boatName || "-")}</td>
          <td>${escapeHtml(entry.routeName || "-")}</td>
          <td>${escapeHtml(entry.mode || "-")}</td>
        </tr>
      `).join(""), "No available shipping entries.") : ""}

      ${tableSection("My Buy Orders", ["Commodity", "Quantity", "Band", "Status", "Actions"], (detail.buyOrderEntries || []).map((entry) => `
        <tr>
          <td>${escapeHtml(entry.commodityKey || "-")}</td>
          <td>${number(entry.quantity)}</td>
          <td>${number(entry.minPriceBp)} to ${number(entry.maxPriceBp)} bp</td>
          <td>${escapeHtml(entry.status || "-")}</td>
          <td><button type="button" class="danger" data-cancel-buy-order="${escapeHtml(entry.orderId || "")}">Cancel</button></td>
        </tr>
      `).join(""), "No buy orders yet.")}

      <div class="two-col">
        <div class="section-card">
          <h3>Price Charts</h3>
          ${state.settings.showPriceCharts ? renderPriceCharts(detail.priceCharts || []) : '<div class="empty-state">Disabled by config.</div>'}
        </div>
        <div class="section-card">
          <h3>Commodity Buy Books</h3>
          ${renderBuyBooks(detail.commodityBuyBooks || [])}
        </div>
      </div>
    </div>
  `;

  bindDetailActions();
}

function bindDetailActions() {
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

function tableSection(title, headers, rows, emptyMessage) {
  return `
    <div class="table-card">
      <h3>${escapeHtml(title)}</h3>
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

function renderPriceCharts(charts) {
  if (!charts.length) {
    return '<div class="empty-state">No commodity history yet.</div>';
  }
  return charts.map((series) => `
    <div class="stack">
      <div>
        <strong>${escapeHtml(series.displayName || series.commodityKey)}</strong><br>
        <span class="muted">${escapeHtml(series.commodityKey || "")}</span>
      </div>
      ${(series.points || []).length ? `
        <ol class="mini-list">
          ${(series.points || []).slice(-8).map((point) => `
            <li>${formatTime(point.bucketAt)}: avg ${number(point.averageUnitPrice)}, min ${number(point.minUnitPrice)}, max ${number(point.maxUnitPrice)}, vol ${number(point.volume)}</li>
          `).join("")}
        </ol>
      ` : '<div class="empty-state">No chart buckets.</div>'}
    </div>
  `).join("<hr>");
}

function renderBuyBooks(books) {
  if (!books.length) {
    return '<div class="empty-state">No open commodity buy books.</div>';
  }
  return books.map((book) => `
    <div class="stack">
      <div>
        <strong>${escapeHtml(book.displayName || book.commodityKey)}</strong><br>
        <span class="muted">${escapeHtml(book.commodityKey || "")}</span>
      </div>
      ${(book.entries || []).length ? `
        <ol class="mini-list">
          ${(book.entries || []).slice(0, 10).map((entry) => `
            <li>${escapeHtml(entry.buyerName || "-")} wants ${number(entry.quantity)} at ${number(entry.minPriceBp)} to ${number(entry.maxPriceBp)} bp (${escapeHtml(entry.status || "-")})</li>
          `).join("")}
        </ol>
      ` : '<div class="empty-state">No active orders.</div>'}
    </div>
  `).join("<hr>");
}

function metric(label, value) {
  return `<div class="metric"><span class="label">${escapeHtml(label)}</span><span class="value">${escapeHtml(String(value))}</span></div>`;
}

function valueOf(selector) {
  return document.querySelector(selector)?.value?.trim() || "";
}

function numberValue(selector, fallback) {
  const value = Number(valueOf(selector));
  return Number.isFinite(value) ? value : fallback;
}

function number(value) {
  return Number(value || 0).toLocaleString();
}

function formatTime(epochMs) {
  if (!epochMs) {
    return "-";
  }
  return new Date(epochMs).toLocaleString();
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;"
  }[char]));
}

els.loginButton.addEventListener("click", login);
els.copyCommandButton?.addEventListener("click", () => {
  copyText(state.settings.commandText || "/marketweb token", "Command copied. Run it in game.");
});
els.refreshMarkets.addEventListener("click", async () => {
  try {
    await loadMarkets();
    setStatus("Markets refreshed.");
  } catch (error) {
    setStatus(error.message, true);
  }
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
  if (els.tokenHelper) {
    els.tokenHelper.textContent = "Command copied: run it in game. This page can parse pasted chat output and the mod will also copy the token directly to your clipboard.";
  }
  renderSession();
  renderMarkets();
  renderDetail();
  if (!state.sessionToken) {
    return;
  }
  try {
    await hydrateSession();
    await loadMarkets();
    setStatus(`Signed in as ${state.session.playerName}.`);
  } catch (error) {
    setStatus(error.message, true);
  }
})();
