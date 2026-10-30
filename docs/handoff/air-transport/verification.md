# Verification record — 2026-10-30

## Air transport before importing the supplied live-market patch

- `./gradlew test --no-daemon`: BUILD SUCCESSFUL; 2026 tests across 435 classes, 0 failures, 0 errors, 1 skipped. Log: `/tmp/sailboat-air-before-merge-test.log`.
- Six MarketWeb Node suites: 100 tests, 100 passed. `node tools/check_marketweb_frontend.js` succeeded. Log: `/tmp/sailboat-air-web-test.log`.
- Actual Java 21/Minecraft-classpath footprint regression: observed two failures for the old width calculation, then 6/6 route-planner tests passed after the logical-origin radius fix. Logs: `/tmp/sailboat-flight-footprint/{red,green}.log`.
- Airport/preflight/controller targeted suites: 75/75 passed. Market source-terminal regressions: 62/62 passed. These are included in the full test total, not additional tests to sum into it.
- Browser renders captured from the local mock server in this directory, covering light/dark fixed wing, physical-mode airship and airport facility details. They are UI fixtures, not real game flights.
- The cached official Minecraft 1.21.1 client supplied 8120 blockstate/model/texture resources for local packaging. Generated assets are ignored by Git.

No real three-airframe flight, server restart flight, crash recovery, offline custody or fuel-debit integration was tested. Virtual travel remains disabled. See README.md for setup and limitations.
