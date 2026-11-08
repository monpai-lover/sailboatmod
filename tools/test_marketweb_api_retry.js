const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../src/main/resources/marketweb/app.js"), "utf8")
  .replace(/\r\n/g, "\n");
const apiStart = source.indexOf("async function api(");
const apiEnd = source.indexOf("\nfunction expireSession(", apiStart);
assert.ok(apiStart >= 0 && apiEnd > apiStart, "the application API must remain available to the request tests");

function pending(code = "market_loading", retryAfter = "5") {
  return new Response(JSON.stringify({ success: false, errorCode: code, message: "Market is temporarily unavailable" }), {
    status: 503,
    headers: { "Retry-After": retryAfter }
  });
}

function harness(responses, { manualTimers = false } = {}) {
  const requests = [];
  const delays = [];
  const timers = new Map();
  let nextTimer = 0;
  let expired = 0;
  const now = Date.parse("2026-11-08T05:00:00Z");
  const environment = {
    state: { sessionToken: "session-token" },
    Headers,
    DOMException,
    Date: class extends Date { static now() { return now; } },
    expireSession() { expired++; },
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (options.signal?.aborted) throw options.signal.reason;
      const response = responses.shift();
      if (response instanceof Error) throw response;
      assert.ok(response, "the API exceeded its expected request bound");
      return response;
    },
    window: {
      setTimeout(callback, delay) {
        const id = ++nextTimer;
        delays.push(delay);
        timers.set(id, callback);
        if (!manualTimers) queueMicrotask(() => {
          if (timers.delete(id)) callback();
        });
        return id;
      },
      clearTimeout(id) { timers.delete(id); }
    }
  };
  vm.runInNewContext(`${source.slice(apiStart, apiEnd)}\nglobalThis.runApi = api;`, environment);
  return { api: environment.runApi, requests, delays, timers, get expired() { return expired; } };
}

test("GET reads retry pending markets and preserve request headers and cancellation signal", async () => {
  const fixture = harness([pending(), pending("market_probe_busy", "2"), new Response('{"marketName":"Ready"}')]);
  const controller = new AbortController();
  const result = await fixture.api("/api/markets/market-id", {
    signal: controller.signal,
    headers: { "X-Request-Test": "preserved" },
    cache: "no-store"
  });

  assert.equal(result.marketName, "Ready");
  assert.equal(fixture.requests.length, 3);
  assert.deepEqual(fixture.delays, [5000, 2000]);
  for (const { options } of fixture.requests) {
    assert.equal(options.signal, controller.signal);
    assert.equal(options.cache, "no-store");
    assert.equal(options.headers.get("Authorization"), "Bearer session-token");
    assert.equal(options.headers.get("X-Request-Test"), "preserved");
  }
});

test("a pending GET stops after five total attempts", async () => {
  const fixture = harness(Array.from({ length: 5 }, () => pending()));

  await assert.rejects(fixture.api("/api/markets/market-id"), error => error.status === 503);

  assert.equal(fixture.requests.length, 5);
  assert.deepEqual(fixture.delays, [5000, 5000, 5000, 5000]);
});

test("only explicit recoverable market codes receive GET retries", async () => {
  for (const code of ["market_loading", "market_probe_busy", "market_probe_timeout", "market_probe_failed", "market_activation_unavailable", "market_unloading"]) {
    const fixture = harness([pending(code), new Response('{"ready":true}')]);
    assert.equal((await fixture.api("/api/markets/market-id", { method: "get" })).ready, true, code);
    assert.equal(fixture.requests.length, 2, code);
  }
});

test("mutations and other HTTP methods are never automatically retried", async () => {
  for (const method of ["POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]) {
    const fixture = harness([pending()]);

    await assert.rejects(fixture.api("/api/markets/market-id/purchase", {
      method,
      body: '{"quantity":1}',
      idempotencyKey: "purchase-intent"
    }), error => error.status === 503);

    assert.equal(fixture.requests.length, 1, method);
    assert.equal(fixture.delays.length, 0, method);
    assert.equal(fixture.requests[0].options.headers.get("Idempotency-Key"), "purchase-intent");
    assert.equal(fixture.requests[0].options.headers.get("Content-Type"), "application/json");
  }
});

test("permanent and unrelated service errors return immediately", async () => {
  for (const code of ["market_dimension_unavailable", "market_position_unverified", "market_saved_data_unavailable", "market_server_unavailable", "unrelated_service_busy", ""]) {
    const fixture = harness([pending(code)]);
    await assert.rejects(fixture.api("/api/markets/market-id"), error => error.status === 503);
    assert.equal(fixture.requests.length, 1, code);
    assert.equal(fixture.delays.length, 0, code);
  }
  for (const status of [400, 404, 429, 500]) {
    const fixture = harness([new Response('{"errorCode":"market_loading"}', { status })]);
    await assert.rejects(fixture.api("/api/markets/market-id"), error => error.status === status);
    assert.equal(fixture.requests.length, 1, String(status));
    assert.equal(fixture.delays.length, 0, String(status));
  }
});

test("Retry-After supports seconds and HTTP dates within a one-to-five-second bound", async () => {
  for (const [header, delay] of [["120", 5000], ["2.5", 2500], ["0", 1000], ["invalid", 1000], ["Sun, 08 Nov 2026 05:00:03 GMT", 3000]]) {
    const fixture = harness([pending("market_loading", header), new Response('{}')]);
    await fixture.api("/api/markets/market-id");
    assert.deepEqual(fixture.delays, [delay], header);
  }
});

test("aborting during backoff clears its timer and prevents another request", async () => {
  const fixture = harness([pending()], { manualTimers: true });
  const controller = new AbortController();
  const result = fixture.api("/api/markets/market-id", { signal: controller.signal });
  const rejected = assert.rejects(result, error => error === controller.signal.reason);
  await new Promise(setImmediate);
  assert.equal(fixture.timers.size, 1);

  controller.abort(new DOMException("View changed", "AbortError"));
  await rejected;

  assert.equal(fixture.requests.length, 1);
  assert.equal(fixture.timers.size, 0);
});

test("an already-aborted request preserves the caller's abort reason", async () => {
  const fixture = harness([]);
  const controller = new AbortController();
  const reason = new Error("View no longer active");
  controller.abort(reason);

  await assert.rejects(fixture.api("/api/markets/market-id", { signal: controller.signal }), error => error === reason);

  assert.equal(fixture.delays.length, 0);
});

test("network errors keep their existing immediate failure behavior", async () => {
  const reason = new TypeError("Connection lost");
  const fixture = harness([reason]);

  await assert.rejects(fixture.api("/api/markets/market-id"), error => error === reason);

  assert.equal(fixture.requests.length, 1);
  assert.equal(fixture.delays.length, 0);
});

test("unauthorized responses still expire the session without a retry", async () => {
  const fixture = harness([new Response('{"message":"Session expired"}', { status: 401 })]);

  await assert.rejects(fixture.api("/api/session/me"), error => error.status === 401 && error.message === "Session expired");

  assert.equal(fixture.expired, 1);
  assert.equal(fixture.requests.length, 1);
  assert.equal(fixture.delays.length, 0);
});
