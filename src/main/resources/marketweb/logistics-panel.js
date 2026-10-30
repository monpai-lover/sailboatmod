// The shipment list cards and the shipment detail card, as HTML strings built from shipment data.
// map.js inserts them and wires the events; keeping the markup here lets node tests check it.
(function (root, factory) {
  const render = typeof module === "object" && module.exports
    ? require("./logistics-render.js")
    : root && root.SailboatLogistics;
  const api = factory(render);
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  if (root) {
    root.SailboatLogisticsPanel = api;
  }
})(typeof window !== "undefined" ? window : null, function (render) {
  "use strict";

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function formatEta(seconds) {
    const s = Math.max(0, Math.round(Number(seconds) || 0));
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    if (m < 60) return `${m}m ${s % 60}s`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
  }

  /** The server sends currentSpeed in blocks per tick; shown as blocks (metres) per second. */
  function formatSpeed(blocksPerTick) {
    return `${((Number(blocksPerTick) || 0) * 20).toFixed(1)} m/s`;
  }

  function shipmentRoute(shipment) {
    return `${shipment.sourceTownName || shipment.sourceName || "-"} → ${shipment.targetTownName || shipment.targetName || "-"}`;
  }

  function shipmentPercent(shipment) {
    return Math.round(Math.max(0, Math.min(1, Number(shipment.progressRatio) || 0)) * 100);
  }

  function isStuck(shipment) {
    return String(shipment.status || "").toUpperCase() === "STUCK";
  }

  function styleOf(kind) {
    return render.MODE_STYLES[kind] || render.MODE_STYLES.carriage;
  }

  /**
   * One list card. ctx: label(key) for interface text, modeLabel({en, zh}) for the transport mode name,
   * selected when this shipment is the one shown in the detail card.
   */
  function cardHtml(shipment, ctx) {
    const kind = render.vehicleKindOf(shipment);
    const style = styleOf(kind);
    const stuck = isStuck(shipment);
    const percent = shipmentPercent(shipment);
    const eta = Number(shipment.etaSeconds) || 0;
    const cargo = render.cargoSummary(shipment.cargo);
    return `
        <button type="button" class="map-shipment-card" data-shipment-id="${escapeHtml(shipment.shippingOrderId || "")}" data-kind="${kind}"${stuck ? " data-stuck" : ""}${ctx.selected ? ' aria-current="true"' : ""} style="--mode:${style.color};--progress:${percent}%">
          <span class="map-shipment-glyph">${render.LIST_ICONS[kind]}</span>
          <span class="map-shipment-route">${escapeHtml(shipmentRoute(shipment))}</span>
          <span class="map-shipment-pct">${percent}%</span>
          <span class="map-shipment-vehicle">${stuck ? `<b>${escapeHtml(ctx.label("statusStuck"))}</b>` : escapeHtml(shipment.vehicleName || ctx.modeLabel(style.label))}</span>
          <span class="map-shipment-eta">${eta > 0 ? escapeHtml(formatEta(eta)) : ""}</span>
          ${cargo ? `<span class="map-shipment-cargo">${escapeHtml(cargo)}</span>` : ""}
        </button>`;
  }

  function statusText(shipment, label) {
    const key = render.statusKey(shipment.status);
    return key === "stuck" ? label("statusStuck")
      : key === "arrived" ? label("statusArrived")
        : key === "moving" ? label("statusMoving")
          : (shipment.status || "-");
  }

  const TICKS_PER_SECOND = 20;
  const MOVING = ["SAILING", "IN_TRANSIT", "MOVING"];

  /** "just now", "5 min ago", "3 h ago", "2 d ago" from an age in game ticks. */
  function relativeTime(ticksAgo, label) {
    const seconds = Math.max(0, Math.floor((Number(ticksAgo) || 0) / TICKS_PER_SECOND));
    if (seconds < 60) return label("justNow");
    if (seconds < 3600) return label("minutesAgo").replace("{n}", String(Math.floor(seconds / 60)));
    if (seconds < 86400) return label("hoursAgo").replace("{n}", String(Math.floor(seconds / 3600)));
    return label("daysAgo").replace("{n}", String(Math.floor(seconds / 86400)));
  }

  /** Label key for a timeline event; moving again right after being stuck reads as "back on the way". */
  function eventKey(kind, previousKind) {
    const k = String(kind || "").toUpperCase();
    if (k === "DEPARTED") return "eventDeparted";
    if (k === "STUCK") return "eventStuck";
    if (MOVING.includes(k)) return String(previousKind || "").toUpperCase() === "STUCK" ? "eventResumed" : "eventDeparted";
    if (k === "DELIVERED") return "eventDelivered";
    if (k === "ARRIVED") return "eventArrived";
    if (k === "CLAIMED") return "eventClaimed";
    if (k.startsWith("FAILED") || k === "CANCELLED") return "eventFailed";
    return "eventEnded";
  }

  function eventTone(key) {
    return key === "eventStuck" || key === "eventFailed" ? "bad" : key === "eventDelivered" || key === "eventArrived" || key === "eventClaimed" ? "good" : "plain";
  }

  /** The shipment's timeline, newest first; each entry is a button that shows where it happened. */
  function timelineHtml(events, nowGameTime, ctx) {
    const label = ctx.label;
    const list = Array.isArray(events) ? events : [];
    if (!list.length) {
      return "";
    }
    const items = list.map((event, i) => {
      const key = eventKey(event.kind, i > 0 ? list[i - 1].kind : "");
      const x = Math.round(Number(event.x) || 0);
      const z = Math.round(Number(event.z) || 0);
      return `<li data-tone="${eventTone(key)}"><button type="button" data-event-x="${x}" data-event-z="${z}">`
        + `<span>${escapeHtml(label(key))}</span><time>${escapeHtml(relativeTime(nowGameTime - (Number(event.gameTime) || 0), label))}</time></button></li>`;
    }).reverse().join("");
    return `<div class="map-shipment-timeline"><h4>${escapeHtml(label("timeline"))}</h4><ol>${items}</ol></div>`;
  }

  /** A finished shipment in the history list. ctx as for cardHtml. */
  function historyCardHtml(shipment, nowGameTime, ctx) {
    const kind = render.vehicleKindOf(shipment);
    const style = styleOf(kind);
    const outcome = eventKey(shipment.status, "");
    const cargo = render.cargoSummary(shipment.cargo);
    return `
        <button type="button" class="map-shipment-card map-shipment-history" data-history-id="${escapeHtml(shipment.shippingOrderId || "")}" data-kind="${kind}" data-tone="${eventTone(outcome)}"${ctx.selected ? ' aria-current="true"' : ""} style="--mode:${style.color};--progress:100%">
          <span class="map-shipment-glyph">${render.LIST_ICONS[kind]}</span>
          <span class="map-shipment-route">${escapeHtml(shipmentRoute(shipment))}</span>
          <span class="map-shipment-pct">${escapeHtml(ctx.label(outcome))}</span>
          <span class="map-shipment-vehicle">${escapeHtml(shipment.vehicleName || ctx.modeLabel(style.label))}</span>
          <span class="map-shipment-eta">${escapeHtml(relativeTime(nowGameTime - (Number(shipment.endedGameTime) || 0), ctx.label))}</span>
          ${cargo ? `<span class="map-shipment-cargo">${escapeHtml(cargo)}</span>` : ""}
        </button>`;
  }

  /**
   * The detail card. ctx: label, modeLabel as for cardHtml; following when the camera follows this vehicle;
   * history for a finished shipment (no follow button); nowGameTime for the timeline's "ago" times.
   */
  function flightStatsHtml(flight, label) {
    if (!flight) return "";
    const states = { PREFLIGHT: "flightPreflight", QUIESCING: "flightQuiescing", AWAITING_RECOVERY: "flightRecovery", CANCELLED: "eventFailed", UNKNOWN: "flightUnknown", PARKED: "flightParked", LOADING: "flightLoading", TAXIING: "flightTaxiing", TAKEOFF: "flightTakeoff", TAKING_OFF: "flightTakeoff", CLIMBING: "flightClimbing", CRUISING: "flightCruising", CRUISE: "flightCruising", APPROACH: "flightApproach", APPROACHING: "flightApproach", LANDING: "flightLanding", LANDED: "flightLanded", HOLDING: "flightHolding", BLOCKED: "flightBlocked" };
    const executions = { QUIESCING: "flightQuiescing", MATERIALIZING: "flightRecovery", PHYSICAL: "executionPhysical", VIRTUAL: "executionVirtual", OFFLINE: "executionVirtual" };
    const height = flight.worldY != null && Number.isFinite(Number(flight.worldY)) ? `${Number(flight.worldY).toFixed(1)} m` : "-";
    return `<div><dt>${escapeHtml(label("worldY"))}</dt><dd>${escapeHtml(height)}</dd></div>`
      + `<div><dt>${escapeHtml(label("flightState"))}</dt><dd>${escapeHtml(states[flight.flightState] ? label(states[flight.flightState]) : flight.flightState || "-")}</dd></div>`
      + `<div><dt>${escapeHtml(label("executionMode"))}</dt><dd>${escapeHtml(executions[flight.executionMode] ? label(executions[flight.executionMode]) : flight.executionMode || "-")}</dd></div>`;
  }

  function airportHtml(airport, ctx) {
    const label = ctx.label;
    const typeKeys = { RUNWAY: "runway", HELIPAD: "helipad", MOORING: "mooring" };
    const facilities = Array.isArray(airport.facilities) ? airport.facilities : [];
    return `<div class="map-detail-card"><p class="section-kicker">${escapeHtml(label("airports"))}</p><h3>${escapeHtml(airport.name || airport.airportId || "-")}</h3>`
      + `<dl><dt>X / Y / Z</dt><dd>${[airport.x, airport.y, airport.z].map(v => Math.round(Number(v) || 0)).join(" / ")}</dd></dl>`
      + `<p class="map-empty-note">${escapeHtml(label("facilityUnchecked"))}</p>`
      + facilities.map(f => {
        const length = Math.hypot(Number(f.end?.x) - Number(f.start?.x), Number(f.end?.z) - Number(f.start?.z));
        return `<div class="map-shipment-cargo-list"><h4>${escapeHtml(typeKeys[f.type] ? label(typeKeys[f.type]) : f.type || "-")} · ${escapeHtml(f.id || "-")}</h4>`
          + `<dl class="map-shipment-stats"><div><dt>${escapeHtml(label("length"))}</dt><dd>${Number.isFinite(length) ? length.toFixed(0) : "-"} m</dd></div>`
          + `<div><dt>${escapeHtml(label("width"))}</dt><dd>${Math.max(0, Number(f.width) || 0)} m</dd></div>`
          + `<div><dt>${escapeHtml(label("clearance"))}</dt><dd>${Math.max(0, Number(f.clearanceHeight) || 0)} m</dd></div></dl></div>`;
      }).join("") + `</div>`;
  }

  function detailHtml(shipment, ctx) {
    const label = ctx.label;
    const kind = render.vehicleKindOf(shipment);
    const style = styleOf(kind);
    const stuck = isStuck(shipment);
    const percent = shipmentPercent(shipment);
    const etaSec = Number(shipment.etaSeconds) || 0;
    const speed = Number(shipment.currentSpeed) || 0;
    const cargo = Array.isArray(shipment.cargo) ? shipment.cargo : [];
    const following = !!ctx.following;
    return `
      <div class="map-detail-card map-shipment-detail" data-kind="${kind}"${stuck ? " data-stuck" : ""} style="--mode:${style.color};--progress:${percent}%">
        <div class="map-shipment-detail-head">
          <span class="map-shipment-glyph">${render.LIST_ICONS[kind]}</span>
          <div>
            <h3>${escapeHtml(shipmentRoute(shipment))}</h3>
            <p>${escapeHtml(shipment.vehicleName || ctx.modeLabel(style.label))}${shipment.ownerName ? ` · ${escapeHtml(shipment.ownerName)}` : ""}</p>
          </div>
        </div>
        <div class="map-shipment-journey" aria-label="${escapeHtml(label("progress"))} ${percent}%">
          <span>${escapeHtml(shipment.sourceName || shipment.sourceTownName || "-")}</span>
          <div class="map-shipment-track"><i></i><b></b></div>
          <span>${escapeHtml(shipment.targetName || shipment.targetTownName || "-")}</span>
        </div>
        <dl class="map-shipment-stats">
          <div><dt>${escapeHtml(label("progress"))}</dt><dd>${percent}%</dd></div>
          <div><dt>${escapeHtml(label("eta"))}</dt><dd>${etaSec > 0 ? escapeHtml(formatEta(etaSec)) : "-"}</dd></div>
          <div><dt>${escapeHtml(label("speed"))}</dt><dd>${speed > 0 ? escapeHtml(formatSpeed(speed)) : "-"}</dd></div>
          <div><dt>${escapeHtml(label("status"))}</dt><dd class="map-shipment-status">${escapeHtml(ctx.history ? label(eventKey(shipment.status, "")) : statusText(shipment, label))}</dd></div>
          ${flightStatsHtml(shipment.flight, label)}
        </dl>
        ${timelineHtml(shipment.events, Number(ctx.nowGameTime) || 0, ctx)}
        <div class="map-shipment-cargo-list">
          <h4>${escapeHtml(label("cargo"))}</h4>
          ${cargo.length ? `<ul>${cargo.map((c) => `<li><span>${Number(c.quantity) || 0}×</span> ${escapeHtml(c.name || "-")}${c.recipient ? ` <small>→ ${escapeHtml(c.recipient)}</small>` : ""}</li>`).join("")}</ul>` : `<p class="map-empty-note">-</p>`}
        </div>
        ${ctx.history ? "" : `<button type="button" class="map-shipment-follow" data-shipment-follow aria-pressed="${following}">${escapeHtml(following ? label("unfollow") : label("follow"))}</button>`}
      </div>
    `;
  }

  return { escapeHtml, formatEta, formatSpeed, shipmentRoute, shipmentPercent, cardHtml, detailHtml, airportHtml,
    relativeTime, timelineHtml, historyCardHtml };
});
