const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const appSource = fs.readFileSync(path.join(__dirname, "../src/main/resources/marketweb/app.js"), "utf8").replace(/\r\n/g, "\n");

function functionSource(name, nextName) {
  const start = appSource.indexOf(`function ${name}(`);
  const end = appSource.indexOf(`\nfunction ${nextName}(`, start);
  assert.ok(start >= 0 && end > start, `${name} must remain available for the callback tests`);
  return appSource.slice(start, end);
}

// The strip's toggle/animationend handling lives in the document-level listeners installed once at startup.
const bindingsSource = functionSource("installDelegatedHandlers", "registerDetailActions");
const preferenceSource = functionSource("prefersReducedMotion", "currentViewKey");

function bindStrip({ savedOpen = false, open = savedOpen, reducedMotion = false } = {}) {
  const classes = new Set();
  const listeners = new Map();
  class Element {}
  const strip = Object.assign(new Element(), {
    open,
    matches(selector) { return selector === ".market-strip"; },
    classList: {
      toggle(name, enabled) {
        if (enabled) classes.add(name);
        else classes.delete(name);
      },
      remove(name) { classes.delete(name); },
      contains(name) { return classes.has(name); }
    }
  });
  const state = { marketStripOpen: savedOpen };
  // Execute the application's real listeners, without rendering a page or starting its browser bootstrap/network code.
  const context = vm.createContext({
    state,
    Element,
    document: {
      addEventListener(name, callback) {
        if (!listeners.has(name)) listeners.set(name, []);
        listeners.get(name).push(callback);
      }
    },
    window: { matchMedia() { return { matches: reducedMotion }; } }
  });
  vm.runInContext(`const delegatedHandlers = { click: [], change: [], input: [], compositionend: [] };\n${preferenceSource}\n${bindingsSource}\ninstallDelegatedHandlers();`, context);
  return {
    strip,
    state,
    fire(name, details = {}) {
      (listeners.get(name) || []).forEach((listener) => listener({ target: strip, currentTarget: strip, ...details }));
    }
  };
}

test("rebuilding an already-open market strip does not replay opening motion", () => {
  const { strip, state, fire } = bindStrip({ savedOpen: true });
  fire("toggle");
  assert.equal(state.marketStripOpen, true);
  assert.equal(strip.classList.contains("is-opening"), false);
});

test("a real closed-to-open toggle starts opening motion", () => {
  const { strip, state, fire } = bindStrip();
  strip.open = true;
  fire("toggle");
  assert.equal(state.marketStripOpen, true);
  assert.equal(strip.classList.contains("is-opening"), true);
});

test("closing the market strip clears opening motion and persists closed state", () => {
  const { strip, state, fire } = bindStrip();
  strip.open = true;
  fire("toggle");
  strip.open = false;
  fire("toggle");
  assert.equal(state.marketStripOpen, false);
  assert.equal(strip.classList.contains("is-opening"), false);
});

test("the opening animation end clears its class", () => {
  const { strip, fire } = bindStrip();
  strip.open = true;
  fire("toggle");
  fire("animationend", { animationName: "panelDrop" });
  assert.equal(strip.classList.contains("is-opening"), false);
});

test("an unrelated animation end does not stop opening motion", () => {
  const { strip, fire } = bindStrip();
  strip.open = true;
  fire("toggle");
  fire("animationend", { animationName: "stageIn" });
  assert.equal(strip.classList.contains("is-opening"), true);
});

test("reduced motion preserves open state without starting opening motion", () => {
  const { strip, state, fire } = bindStrip({ reducedMotion: true });
  strip.open = true;
  fire("toggle");
  assert.equal(state.marketStripOpen, true);
  assert.equal(strip.classList.contains("is-opening"), false);
});

test("a closed strip can animate again after a completed opening and close", () => {
  const { strip, state, fire } = bindStrip();
  strip.open = true;
  fire("toggle");
  fire("animationend", { animationName: "panelDrop" });
  strip.open = false;
  fire("toggle");
  strip.open = true;
  fire("toggle");
  assert.equal(state.marketStripOpen, true);
  assert.equal(strip.classList.contains("is-opening"), true);
});
