// Logistics on the world map: vehicle kinds, route splitting, list filtering, and the canvas drawing for
// sailboats, carriages and trains (icons, trails, wake/dust/smoke effects). Pure helpers are exported for node tests.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  if (root) {
    root.SailboatLogistics = api;
  }
})(typeof window !== "undefined" ? window : null, function () {
  "use strict";

  const KINDS = ["sailboat", "carriage", "train", "airship", "helicopter", "airplane"];

  // One colour per transport mode, shared by the list, the detail card and the map. Red is reserved for "stuck".
  const MODE_STYLES = {
    sailboat: { color: "#4fb6e8", trail: "rgba(79, 182, 232, ", label: { en: "Sea", zh: "海运" } },
    carriage: { color: "#c99a52", trail: "rgba(201, 154, 82, ", label: { en: "Road", zh: "陆运" } },
    train: { color: "#a48cf2", trail: "rgba(164, 140, 242, ", label: { en: "Rail", zh: "铁路" } },
    airship: { color: "#6fcbb0", trail: "rgba(111, 203, 176, ", label: { en: "Airship", zh: "飞艇" } },
    helicopter: { color: "#e2b66f", trail: "rgba(226, 182, 111, ", label: { en: "Helicopter", zh: "旋翼" } },
    airplane: { color: "#90b8f4", trail: "rgba(144, 184, 244, ", label: { en: "Airplane", zh: "固定翼" } }
  };
  const STUCK_COLOR = "#ff6b6b";
  const MAX_PARTICLES = 24;

  // ---- pure helpers ----

  const TRAIN_WORDS = ["RAIL", "RAILWAY", "TRAIN"];
  const WATER_WORDS = ["PORT", "WATER", "SEA", "SAIL", "SAILBOAT", "BOAT", "SHIP"];

  /** Whole words only, same rule as TransportModes.vehicleKind on the server: CREATE_RAIL is a train, BOATYARD_EXPRESS is not a boat. */
  function kindFromMode(mode) {
    const words = String(mode || "").trim().toUpperCase().split(/[^A-Z0-9]+/);
    if (words.includes("AIRSHIP") || words.includes("ZEPPELIN")) return "airship";
    if (words.includes("HELICOPTER") || words.includes("ROTORCRAFT")) return "helicopter";
    if (words.some((word) => ["AIRPLANE", "AIRCRAFT", "AIR", "AIRPORT", "PLANE", "FIXEDWING"].includes(word)) || (words.includes("FIXED") && words.includes("WING"))) return "airplane";
    if (words.some((word) => TRAIN_WORDS.includes(word))) {
      return "train";
    }
    if (words.some((word) => WATER_WORDS.includes(word))) {
      return "sailboat";
    }
    return "carriage";
  }

  /** Index of the waypoint the vehicle is heading to; servers before targetPointIndex sent it as completedPointCount. */
  function targetIndexOf(shipment) {
    const value = shipment && shipment.targetPointIndex != null ? shipment.targetPointIndex : shipment && shipment.completedPointCount;
    return Math.max(0, Math.floor(Number(value) || 0));
  }

  /** The server sends vehicleKind; older servers only send transportMode, so infer it the same way the server does. */
  function vehicleKindOf(shipment) {
    const kind = shipment && shipment.vehicleKind;
    return KINDS.includes(kind) ? kind : kindFromMode(shipment && shipment.transportMode);
  }

  function distanceToSegment(p, a, b) {
    const dx = b.x - a.x;
    const dz = b.z - a.z;
    const lengthSq = dx * dx + dz * dz;
    const t = lengthSq === 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.z - a.z) * dz) / lengthSq));
    return Math.hypot(p.x - (a.x + dx * t), p.z - (a.z + dz * t));
  }

  /**
   * Splits a route at the vehicle. `targetIndex` is the waypoint the vehicle is heading to (the game sends its
   * autopilot target). With a live position the split happens on the leg the vehicle is actually on — searched
   * around the target because the interpolated position can lag a leg behind or run slightly ahead.
   */
  function splitPath(points, targetIndex, live) {
    if (!points || points.length < 2) {
      return { done: [], remaining: [] };
    }
    const last = points.length - 1;
    const index = Math.max(0, Math.min(last, Math.floor(Number(targetIndex) || 0)));
    if (!live) {
      return { done: points.slice(0, index + 1), remaining: [points[index]].concat(points.slice(index + 1)) };
    }
    let leg = Math.max(0, index - 1);
    let best = Number.POSITIVE_INFINITY;
    for (let s = Math.max(0, index - 2); s <= Math.min(index, last - 1); s++) {
      const d = distanceToSegment(live, points[s], points[s + 1]);
      if (d < best) {
        best = d;
        leg = s;
      }
    }
    return { done: points.slice(0, leg + 1).concat([live]), remaining: [live].concat(points.slice(leg + 1)) };
  }

  /** Points every `spacing` pixels along a screen-space polyline, starting `offset` pixels in, with the local heading. */
  function placeAlong(points, spacing, offset) {
    const out = [];
    if (!points || points.length < 2 || !(spacing > 0)) {
      return out;
    }
    let next = Math.max(0, offset || 0);
    let walked = 0;
    for (let i = 1; i < points.length; i++) {
      const a = points[i - 1];
      const b = points[i];
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      const length = Math.hypot(dx, dy);
      if (length === 0) {
        if (out.length === 0 && next === 0) {
          out.push({ x: a.x, y: a.y, angle: 0 });
          next += spacing;
        }
        continue;
      }
      const angle = Math.atan2(dy, dx);
      while (next <= walked + length + 1e-9) {
        const t = (next - walked) / length;
        out.push({ x: a.x + dx * t, y: a.y + dy * t, angle });
        next += spacing;
      }
      walked += length;
    }
    return out;
  }

  function searchText(shipment) {
    const cargo = Array.isArray(shipment.cargo) ? shipment.cargo.map((c) => c && c.name).join(" ") : "";
    return [shipment.sourceTownName, shipment.sourceName, shipment.targetTownName, shipment.targetName,
      shipment.vehicleName, shipment.ownerName, cargo].filter(Boolean).join(" ").toLowerCase();
  }

  function filterShipments(list, filter) {
    const kind = filter && filter.kind && filter.kind !== "all" ? filter.kind : null;
    const query = String((filter && filter.query) || "").trim().toLowerCase();
    return (list || []).filter((s) => (!kind || vehicleKindOf(s) === kind) && (!query || searchText(s).includes(query)));
  }

  function routeName(s) {
    return String(s.sourceTownName || s.sourceName || "");
  }

  function sortShipments(list, key) {
    const copy = (list || []).slice();
    if (key === "progress") {
      copy.sort((a, b) => (Number(b.progressRatio) || 0) - (Number(a.progressRatio) || 0));
    } else if (key === "name") {
      copy.sort((a, b) => routeName(a).localeCompare(routeName(b)));
    } else {
      const eta = (s) => (Number(s.etaSeconds) > 0 ? Number(s.etaSeconds) : Number.POSITIVE_INFINITY);
      copy.sort((a, b) => eta(a) - eta(b));
    }
    return copy;
  }

  /** Readable state for a trace status code; null for codes the UI has no wording for (shown as-is). */
  function statusKey(status) {
    const s = String(status || "").trim().toUpperCase();
    if (!s || s === "SAILING" || s === "IN_TRANSIT" || s === "DEPARTED") {
      return "moving";
    }
    if (s === "STUCK") {
      return "stuck";
    }
    if (s === "ARRIVED" || s === "DELIVERED") {
      return "arrived";
    }
    return null;
  }

  function cargoSummary(cargo) {
    const items = Array.isArray(cargo) ? cargo.filter(Boolean) : [];
    if (!items.length) {
      return "";
    }
    const first = `${Number(items[0].quantity) || 0}× ${items[0].name || "-"}`;
    return items.length > 1 ? `${first} +${items.length - 1}` : first;
  }

  // ---- canvas drawing (screen space) ----

  function strokePath(ctx, pts) {
    ctx.beginPath();
    pts.forEach((p, i) => (i === 0 ? ctx.moveTo(p.x, p.y) : ctx.lineTo(p.x, p.y)));
  }

  function onCanvas(ctx, p, margin) {
    const c = ctx.canvas;
    return !c || (p.x > -margin && p.y > -margin && p.x < c.width + margin && p.y < c.height + margin);
  }

  /** Rail bed under a train route: sleepers across the line (one path, off-screen ones skipped), then two thin rails. */
  function drawRailBed(ctx, pts, color) {
    if (pts.length < 2) {
      return;
    }
    ctx.save();
    ctx.strokeStyle = "rgba(120, 104, 86, 0.75)";
    ctx.lineWidth = 2;
    ctx.beginPath();
    for (const s of placeAlong(pts, 9, 0)) {
      if (!onCanvas(ctx, s, 12)) {
        continue;
      }
      const nx = -Math.sin(s.angle) * 5;
      const ny = Math.cos(s.angle) * 5;
      ctx.moveTo(s.x - nx, s.y - ny);
      ctx.lineTo(s.x + nx, s.y + ny);
    }
    ctx.stroke();
    ctx.strokeStyle = color;
    ctx.globalAlpha = 0.55;
    ctx.lineWidth = 1.2;
    for (const side of [-2.6, 2.6]) {
      ctx.beginPath();
      pts.forEach((p, i) => {
        const a = pts[Math.min(i + 1, pts.length - 1)];
        const b = pts[Math.max(i - 1, 0)];
        const angle = Math.atan2(a.y - b.y, a.x - b.x);
        const x = p.x - Math.sin(angle) * side;
        const y = p.y + Math.cos(angle) * side;
        if (i === 0) {
          ctx.moveTo(x, y);
        } else {
          ctx.lineTo(x, y);
        }
      });
      ctx.stroke();
    }
    ctx.restore();
  }

  const TRAIL_BANDS = 6;

  /** Travelled part: brighter towards the vehicle, like a comet tail — a few alpha bands, one stroke each. */
  function drawDoneTrail(ctx, pts, style, stuck, width) {
    if (pts.length < 2) {
      return;
    }
    ctx.save();
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.lineWidth = width;
    const n = pts.length - 1;
    for (let band = 0; band < TRAIL_BANDS; band++) {
      const from = Math.floor((band * n) / TRAIL_BANDS);
      const to = Math.floor(((band + 1) * n) / TRAIL_BANDS);
      if (to <= from) {
        continue;
      }
      const alpha = 0.25 + 0.7 * ((band + 1) / TRAIL_BANDS);
      ctx.strokeStyle = stuck ? `rgba(255, 107, 107, ${alpha})` : `${style.trail}${alpha})`;
      ctx.beginPath();
      ctx.moveTo(pts[from].x, pts[from].y);
      for (let i = from + 1; i <= to; i++) {
        ctx.lineTo(pts[i].x, pts[i].y);
      }
      ctx.stroke();
    }
    ctx.restore();
  }

  /** Remaining part: dashes that flow towards the destination, with direction chevrons. */
  function drawRemainingTrail(ctx, pts, style, stuck, width, phase) {
    if (pts.length < 2) {
      return;
    }
    const color = stuck ? STUCK_COLOR : style.color;
    ctx.save();
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.strokeStyle = color;
    ctx.globalAlpha = 0.85;
    ctx.lineWidth = width;
    ctx.setLineDash([10, 8]);
    ctx.lineDashOffset = -phase * 18;
    strokePath(ctx, pts);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.globalAlpha = 0.9;
    ctx.fillStyle = color;
    for (const c of placeAlong(pts, 64, 36 + (phase * 18) % 64)) {
      ctx.save();
      ctx.translate(c.x, c.y);
      ctx.rotate(c.angle);
      ctx.beginPath();
      ctx.moveTo(5, 0);
      ctx.lineTo(-3, -4);
      ctx.lineTo(-1, 0);
      ctx.lineTo(-3, 4);
      ctx.closePath();
      ctx.fill();
      ctx.restore();
    }
    ctx.restore();
  }

  /** Origin ring and destination pin; the destination breathes so the eye finds where the cargo is going. */
  function drawEndpoints(ctx, start, end, style, pulse) {
    ctx.save();
    ctx.lineWidth = 2;
    ctx.strokeStyle = style.color;
    ctx.fillStyle = "rgba(15, 20, 32, 0.85)";
    ctx.beginPath();
    ctx.arc(start.x, start.y, 4.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.globalAlpha = 0.35 * (1 - pulse);
    ctx.beginPath();
    ctx.arc(end.x, end.y, 6 + 10 * pulse, 0, Math.PI * 2);
    ctx.stroke();
    ctx.globalAlpha = 1;
    ctx.fillStyle = style.color;
    ctx.beginPath();
    ctx.moveTo(end.x, end.y);
    ctx.bezierCurveTo(end.x - 7, end.y - 9, end.x - 6, end.y - 16, end.x, end.y - 16);
    ctx.bezierCurveTo(end.x + 6, end.y - 16, end.x + 7, end.y - 9, end.x, end.y);
    ctx.fill();
    ctx.fillStyle = "rgba(15, 20, 32, 0.9)";
    ctx.beginPath();
    ctx.arc(end.x, end.y - 10.5, 2.4, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  // Vehicles are drawn top-down, heading along +x, centred on the origin.

  function drawSailboat(ctx, size, style, t) {
    const s = size / 24;
    ctx.save();
    ctx.scale(s, s);
    ctx.fillStyle = "#6b4a2b";
    ctx.strokeStyle = "rgba(15, 20, 32, 0.9)";
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    ctx.moveTo(13, 0);
    ctx.bezierCurveTo(9, -6, -6, -6.5, -11, -5);
    ctx.lineTo(-11, 5);
    ctx.bezierCurveTo(-6, 6.5, 9, 6, 13, 0);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = "#a07447";
    ctx.fillRect(-8, -3.2, 15, 6.4);
    const billow = 1 + 0.08 * Math.sin(t * 2.2);
    ctx.fillStyle = "#f4f1e8";
    ctx.strokeStyle = style.color;
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.moveTo(3, -9);
    ctx.quadraticCurveTo(8 * billow, 0, 3, 9);
    ctx.lineTo(3, -9);
    ctx.fill();
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-4, -7);
    ctx.quadraticCurveTo(0.5 * billow, 0, -4, 7);
    ctx.lineTo(-4, -7);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = "#3d2a17";
    ctx.beginPath();
    ctx.arc(3, 0, 1.3, 0, Math.PI * 2);
    ctx.arc(-4, 0, 1.1, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  function drawCarriage(ctx, size, style, t) {
    const s = size / 24;
    const trot = Math.sin(t * 9) * 0.8;
    ctx.save();
    ctx.scale(s, s);
    ctx.strokeStyle = "rgba(15, 20, 32, 0.9)";
    ctx.lineWidth = 1.2;
    ctx.strokeStyle = "#5b4630";
    ctx.beginPath();
    ctx.moveTo(-1, -2);
    ctx.lineTo(5, -1.4);
    ctx.moveTo(-1, 2);
    ctx.lineTo(5, 1.4);
    ctx.stroke();
    ctx.fillStyle = "#7a5634";
    ctx.strokeStyle = "rgba(15, 20, 32, 0.9)";
    ctx.beginPath();
    ctx.ellipse(8 + trot * 0.3, 0, 4.4, 2.4, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.beginPath();
    ctx.ellipse(12.6 + trot * 0.3, 0, 1.9, 1.4, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = "#2b2b2b";
    for (const [x, y] of [[-9, -6.4], [-9, 6.4], [-2.5, -6.4], [-2.5, 6.4]]) {
      ctx.fillRect(x - 2, y - 0.9, 4, 1.8);
    }
    ctx.fillStyle = "#8b6a42";
    ctx.beginPath();
    ctx.roundRect(-12, -5.4, 12.5, 10.8, 1.6);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = "#efe6d2";
    ctx.strokeStyle = style.color;
    ctx.beginPath();
    ctx.roundRect(-11, -4.4, 10.5, 8.8, 3.5);
    ctx.fill();
    ctx.stroke();
    ctx.strokeStyle = "rgba(139, 106, 66, 0.55)";
    ctx.beginPath();
    for (const x of [-8.2, -5.6, -3]) {
      ctx.moveTo(x, -4.2);
      ctx.lineTo(x, 4.2);
    }
    ctx.stroke();
    ctx.restore();
  }

  function drawTrain(ctx, size, style) {
    const s = size / 24;
    ctx.save();
    ctx.scale(s, s);
    ctx.strokeStyle = "rgba(15, 20, 32, 0.9)";
    ctx.lineWidth = 1.2;
    ctx.fillStyle = "#3a3f4b";
    ctx.beginPath();
    ctx.roundRect(-18, -4.2, 11, 8.4, 1.4);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = "#4a3b2a";
    ctx.fillRect(-16.5, -2.8, 8, 5.6);
    ctx.fillStyle = "#5a6070";
    ctx.beginPath();
    ctx.roundRect(-6, -5, 16, 10, 2);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = style.color;
    ctx.fillRect(-6, -5, 16, 2.2);
    ctx.fillRect(-6, 2.8, 16, 2.2);
    ctx.fillStyle = "#c9a14a";
    ctx.beginPath();
    ctx.moveTo(10, -5);
    ctx.lineTo(14, -2.5);
    ctx.lineTo(14, 2.5);
    ctx.lineTo(10, 5);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = "#1c1f26";
    ctx.beginPath();
    ctx.arc(6, 0, 2, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = "#e8e4d8";
    ctx.fillRect(-5, -2.2, 4, 4.4);
    ctx.restore();
  }

  function drawAircraft(ctx, kind, size, style, t) {
    ctx.save();
    ctx.scale(size / 24, size / 24);
    ctx.fillStyle = style.color;
    ctx.strokeStyle = "rgba(255,255,255,.9)";
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    if (kind === "airship") {
      ctx.ellipse(0, 0, 12, 6, 0, 0, Math.PI * 2);
      ctx.fill(); ctx.stroke();
      ctx.fillRect(-5, 5, 8, 3);
      ctx.beginPath(); ctx.moveTo(-8, 0); ctx.lineTo(-13, -7); ctx.lineTo(-12, 6); ctx.closePath(); ctx.fill();
    } else if (kind === "helicopter") {
      ctx.ellipse(3, 0, 7, 4, 0, 0, Math.PI * 2);
      ctx.fill(); ctx.stroke();
      ctx.fillRect(-12, -1.5, 12, 3);
      ctx.beginPath(); ctx.moveTo(-10, -5); ctx.lineTo(-10, 5); ctx.stroke();
      ctx.rotate(t ? t * 15 : Math.PI / 4);
      ctx.beginPath(); ctx.moveTo(-12, 0); ctx.lineTo(12, 0); ctx.moveTo(0, -12); ctx.lineTo(0, 12); ctx.stroke();
    } else {
      [[13,0],[-3,-3],[-7,-12],[-10,-12],[-7,-2],[-12,-2],[-14,-6],[-16,-6],[-14,0],[-16,6],[-14,6],[-12,2],[-7,2],[-10,12],[-7,12],[-3,3]].forEach(([x,y],i) => i ? ctx.lineTo(x,y) : ctx.moveTo(x,y));
      ctx.closePath(); ctx.fill(); ctx.stroke();
    }
    ctx.restore();
  }

  function drawVehicle(ctx, kind, size, style, t) {
    if (kind === "sailboat") {
      drawSailboat(ctx, size, style, t);
    } else if (kind === "train") {
      drawTrain(ctx, size, style);
    } else if (["airship", "helicopter", "airplane"].includes(kind)) {
      drawAircraft(ctx, kind, size, style, t);
    } else {
      drawCarriage(ctx, size, style, t);
    }
  }

  // ---- effects: a small particle system per vehicle (wake, dust, smoke), capped per vehicle ----

  function createEffects() {
    return new Map();
  }

  function emitterFor(effects, id) {
    let e = effects.get(id);
    if (!e) {
      e = { particles: [], last: 0 };
      effects.set(id, e);
    }
    return e;
  }

  /** Spawns and ages particles behind a vehicle at screen position (x, y) heading `angle`. Call once per frame. */
  function stepEffects(effects, id, kind, x, y, angle, nowMs, moving) {
    if (["airship", "helicopter", "airplane"].includes(kind)) return;
    const e = emitterFor(effects, id);
    e.seen = nowMs;
    const interval = kind === "sailboat" ? 140 : kind === "train" ? 180 : 110;
    if (moving && nowMs - e.last > interval) {
      e.last = nowMs;
      const back = kind === "train" ? 4 : 11;
      const bx = x - Math.cos(angle) * back;
      const by = y - Math.sin(angle) * back;
      e.particles.push({ x: bx, y: by, angle, born: nowMs, life: kind === "sailboat" ? 1600 : kind === "train" ? 1400 : 900,
        drift: (Math.random() - 0.5) * 0.6 });
      while (e.particles.length > MAX_PARTICLES) {
        e.particles.shift();
      }
    }
    e.particles = e.particles.filter((p) => nowMs - p.born < p.life);
  }

  function drawEffects(ctx, effects, id, kind, style, nowMs) {
    const e = effects.get(id);
    if (!e) {
      return;
    }
    ctx.save();
    for (const p of e.particles) {
      const age = (nowMs - p.born) / p.life;
      if (kind === "sailboat") {
        // wake: a widening V of two short arcs behind the hull
        ctx.strokeStyle = `rgba(214, 238, 250, ${0.55 * (1 - age)})`;
        ctx.lineWidth = 1.4;
        const spread = 3 + age * 9;
        for (const side of [-1, 1]) {
          ctx.beginPath();
          ctx.arc(p.x, p.y, spread, p.angle + Math.PI + side * 0.5, p.angle + Math.PI + side * 0.9, side < 0);
          ctx.stroke();
        }
      } else if (kind === "train") {
        // smoke: grey puffs rising off the chimney and drifting sideways
        const r = 2 + age * 6;
        ctx.fillStyle = `rgba(200, 200, 205, ${0.45 * (1 - age)})`;
        ctx.beginPath();
        ctx.arc(p.x + p.drift * age * 14, p.y - age * 10, r, 0, Math.PI * 2);
        ctx.fill();
      } else {
        // dust: small warm clouds kicked up behind the wheels
        const r = 1.5 + age * 4;
        ctx.fillStyle = `rgba(210, 180, 130, ${0.4 * (1 - age)})`;
        ctx.beginPath();
        ctx.arc(p.x + p.drift * age * 10, p.y + p.drift * age * 6, r, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    ctx.restore();
  }

  /** Drops emitters of vehicles that are no longer drawn. */
  function pruneEffects(effects, nowMs) {
    for (const [id, e] of effects) {
      if (nowMs - (e.seen || 0) > 3000) {
        effects.delete(id);
      }
    }
  }

  // Small inline icons for the list and detail card (same silhouettes as the map, simplified).
  const LIST_ICONS = {
    airship: '<svg viewBox="0 0 24 24" aria-hidden="true"><ellipse cx="13" cy="10" rx="10" ry="6" fill="currentColor" opacity=".65"/><path d="M6 10L1 3v14zM9 17h7v3H9z" fill="currentColor"/></svg>',
    helicopter: '<svg viewBox="0 0 24 24" aria-hidden="true"><ellipse cx="15" cy="13" rx="6" ry="4" fill="currentColor" opacity=".65"/><path d="M3 13h8M3 9v8M15 5v5M7 5h16M9 20h13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
    airplane: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2l2 8 8 5v2l-8-2v5l3 2v1l-5-1-5 1v-1l3-2v-5l-8 2v-2l8-5z" fill="currentColor"/></svg>',
    sailboat: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 16h18l-3 4H6z" fill="currentColor"/><path d="M12 3v12M12 4l6 10h-6zM11 6l-5 8h5z" fill="currentColor" opacity=".55"/></svg>',
    carriage: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="8" width="11" height="7" rx="2" fill="currentColor" opacity=".55"/><circle cx="6" cy="17" r="2.2" fill="currentColor"/><circle cx="12" cy="17" r="2.2" fill="currentColor"/><path d="M14 12h4l2-3" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round"/></svg>',
    train: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="4" width="14" height="12" rx="3" fill="currentColor" opacity=".55"/><rect x="8" y="7" width="8" height="4" rx="1" fill="currentColor"/><path d="M7 20l2-3M17 20l-2-3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>'
  };

  return {
    KINDS,
    MODE_STYLES,
    STUCK_COLOR,
    LIST_ICONS,
    vehicleKindOf,
    targetIndexOf,
    splitPath,
    placeAlong,
    filterShipments,
    sortShipments,
    cargoSummary,
    statusKey,
    drawRailBed,
    drawDoneTrail,
    drawRemainingTrail,
    drawEndpoints,
    drawVehicle,
    createEffects,
    stepEffects,
    drawEffects,
    pruneEffects
  };
});
