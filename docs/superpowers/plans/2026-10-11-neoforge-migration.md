# NeoForge 1.21.1 migration implementation plan

> **For agentic workers:** Execute using superpowers:subagent-driven-development, with separate client, entity, networking/persistence and build/service ownership.

**Goal:** Preserve the current sailing, carriage, nation, town, trading, NPC, road and market web features on Minecraft 1.21.1 / NeoForge 21.1.259.

**Architecture:** Keep mod IDs, persistent data IDs and business services. Replace the loader, packet transport, NBT access, entity/block lifecycle and client rendering interfaces with their current equivalents. Read target sources and compile incrementally to validate API assumptions.

**Tech stack:** Java 21, Gradle 9.2.1, ModDevGradle 2.0.148, NeoForge 21.1.259, GeckoLib 4.9.3.

- [x] Inventory repository and dependencies; establish baseline. Current production sources compile, but test compilation has six stale signature errors.
- [x] Create requested `1.21.1-neoforge` branch. Preserve original branch and main.
- [x] Replace ForgeGradle and metadata; resolve target dependencies and download target sources.
- [x] Apply mechanical loader namespace, ResourceLocation factory and registry-aware NBT API changes.
- [x] Adapt all network payload registrations and packet direction/client handler boundaries.
- [x] Preserve SavedData layouts with registry-aware factories; update entity/block registry-aware NBT and item components.
- [x] Adapt all registries, entities, blocks and inventory interfaces.
- [x] Adapt screens, input and renderer APIs, including the native NeoForge BlockUI dependency.
- [x] Compile all production and test sources; fix existing stale test fixtures.
- [x] Run all 1,267 unit tests: 1,265 pass; two preexisting web failures remain assigned to Claude. Build main/addon artifacts separately with `releaseMod -x test`; inspect metadata and nested dependencies.
- [x] Run isolated development dedicated-server and client startup checks. SQLite database and web HTTP startup verified. No existing world or multiplayer gameplay checked; production JAR installation smoke remains a release follow-up.
- [x] Review final migration for feature/data preservation; update migration instructions.
- [ ] Commit and push the requested branch, then set GitHub default branch to `1.21.1-neoforge` when verified.

## Verification commands

Use JDK 21 (`C:\Program Files\Java\jdk-21`) explicitly. Run `./gradlew.bat compileJava`, `./gradlew.bat test` and `./gradlew.bat releaseMod`. Keep diagnostic logs under ignored `build/`. New persistence adapters must be verified with legacy-format NBT fixtures. Server/client launch tests use isolated `run-neoforge/` only.

Detailed outcomes and deferred web failures: `docs/handoff/VALIDATION.md` and `docs/handoff/VERIFICATION_RESULTS.json`.
