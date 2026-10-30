# Verification record — 2026-10-30

## Air transport before importing the supplied live-market patch

- `./gradlew test --no-daemon`: BUILD SUCCESSFUL; 2026 tests across 435 classes, 0 failures, 0 errors, 1 skipped. Log: `/tmp/sailboat-air-before-merge-test.log`.
- Six MarketWeb Node suites: 100 tests, 100 passed. `node tools/check_marketweb_frontend.js` succeeded. Log: `/tmp/sailboat-air-web-test.log`.
- Actual Java 21/Minecraft-classpath footprint regression: observed two failures for the old width calculation, then 6/6 route-planner tests passed after the logical-origin radius fix. Logs: `/tmp/sailboat-flight-footprint/{red,green}.log`.
- Airport/preflight/controller targeted suites: 75/75 passed. Market source-terminal regressions: 62/62 passed. These are included in the full test total, not additional tests to sum into it.
- Browser renders captured from the local mock server in this directory, covering light/dark fixed wing, physical-mode airship and airport facility details. They are UI fixtures, not real game flights.
- The cached official Minecraft 1.21.1 client supplied 8120 blockstate/model/texture resources for local packaging. Generated assets are ignored by Git.

No real three-airframe flight, server restart flight, crash recovery, offline custody or fuel-debit integration was tested. Virtual travel remains disabled. See README.md for setup and limitations.

## Combined build with the supplied live-market patch

Imported `0001-feat-live-market-screens-trading-info-and-web-market.patch` with `git am`, preserving its author. Import commit: `352cc419`; original patch commit ID: `6c88717c3805d0dabee56a54c702fbd8a1a104c0`. The combined protocol is **17**, with 113 directional payload registrations; standalone protocol 16 from either change is incompatible.

The merge retains the airport API, three aircraft renderers and translations alongside lazy map loading, live market packets and SSE. Review reproduced and fixed three additional issues before integration:

- In-flight idempotency records could be removed by capacity or TTL, permitting a duplicate write. Capacity now rejects only new keys with 503, and pending records survive pruning.
- Slow SSE connection closure could block the publishing/game thread. Closing is delegated to a virtual thread; the regression uses a real loopback HTTP connection with backpressure.
- Corrected purchase inputs reused the key of a cached 400 response. Dialog keys now bind endpoint and serialized payload, retaining the original key for uncertain retries of the same content.

Current checks:

- `./gradlew test releaseMod --no-daemon --console=plain -PfixIndex=14`: **BUILD SUCCESSFUL**. 2057 tests in 442 classes, 0 failures, 0 errors, 1 skipped. Log: `/tmp/sailboat-merged-release.log`.
- Seven Node suites including `test_marketweb_purchase_idempotency.js`: **109/109 passed**. Log: `/tmp/sailboat-merged-web-test.log`.
- `node tools/check_marketweb_frontend.js`: passed after updating stale assertions for delegated input resolution, lazy map loading and the intentional globe backdrop. Trading surfaces retain the flat-background check.
- Packaging checked both JARs for new classes/resources and all 8120 cached Minecraft assets. The main JAR excludes the web server package and `marketweb/` resources. The web addon contains the runtime/globe libraries and SSE classes.
- Actual merged browser preview: `merged-rotorcraft-light.png`. The local mock server has no SSE endpoint, so this screenshot validates rendering, not live multiplayer push. Original fix_13 native harness claims remain the supplied patch's historical verification; they were not rerun locally.
- Source whitespace checks pass excluding the unchanged bundled `market-runtime.js` from the supplied patch, whose two reported trailing-whitespace lines are inside JavaScript template literals.

Local artifacts (not a GitHub Release):

| File under `build/libs/` | Bytes | SHA-256 |
| --- | ---: | --- |
| `sailboatmod-1.3.13-fix_14-neoforge-1.21.1.jar` | 31465472 | `7bf67a38af202d90baea3cff4fe2400459811687de0e6de7df4dbca6a952d6e1` |
| `sailboatmod-marketweb-1.3.13-fix_14-neoforge-1.21.1.jar` | 5008270 | `5ac1a4a1db2efa2c6519861bbf4c42865c12e6402009cb80bf8a4e7f6580eeba` |
