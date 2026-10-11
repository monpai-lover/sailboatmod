# Claude web market archive integration

Imported from the user-provided `sailboatmod.7z` on 2026-10-12.

- Archive SHA256: `fec7b6c058b2f750d6a3d1ef784dafeaadeadbfb6561aab0ea79d78fc54dcd70`.
- Archive Git HEAD: `30f3008875fc5740dcef5c98ad69b82cb3ca0902` (`master`).
- Target remains Minecraft 1.21.1 / NeoForge 21.1.259 / Java 21 on `1.21.1-neoforge`.

The substantive frontend changes are `app.css`, `app.js`, `index.html`, and the matching frontend checker/card contract. They introduce dark/light themes, top navigation, a terminal selector, filter sidebar, rarity-based cards, and chart theme colors. Market/backend Java is unchanged apart from older migration differences.

Integration keeps the newer repository's development addon metadata, embedded SQLite resource, resource model fixes, test path/newline fixes, and existing Git history. The archive's Git directory and older build configuration are not copied into the checkout.

The actual HTTP server previously rejected the packaged `/vendor/leaflet/` resources requested by the page. Static routing now serves these resources while rejecting decoded traversal, backslashes, dot segments, and empty path segments. Behavioral tests verify JavaScript, CSS, and marker image bytes/MIME types and invalid-path rejection.

Validation logs and archive extraction are under ignored `build/` directories. The interrupted audit was preserved under `build/logic-audit-paused-20261012/` and resumed after the archive startup check.

Real development server startup was verified with SailboatMod, GeckoLib 4.9.3, Tellus 0.8.3 and the supplied TellusMinimap 0.0.1. The isolated test world uses Tellus's EarthChunkGenerator. SQLite initializes and the actual HTTP service serves the imported page, JavaScript/CSS, Leaflet JS/CSS/PNG, and markets API with HTTP 200. This does not establish gameplay transaction coverage or visual browser correctness; browser access to localhost was denied, so no UI screenshot was captured.
