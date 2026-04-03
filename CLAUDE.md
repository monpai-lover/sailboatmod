# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build              # Full build (compile + reobfuscate + jarJar)
./gradlew compileJava        # Compile only, no jar packaging
./gradlew runClient          # Launch Minecraft client with mod loaded
./gradlew runServer          # Launch dedicated server with mod loaded
./gradlew runData            # Data generation
./gradlew genIntellijRuns    # Generate IntelliJ run configurations
```

Output JARs land in `build/libs/`. The `-reobf.jar` variant is the production artifact (SRG-remapped). The `-all.jar` includes bundled dependencies (JarJar).

A `fixIndex` property can be passed to append a `-fix_N` suffix: `./gradlew build -PfixIndex=1`

No test suite or linter is configured.

## Project Overview

Minecraft Forge 1.20.1 mod (Java 17) implementing a multiplayer system with sailing/transport, nation governance, territory control, market trading, resident NPCs, and blueprint construction.

**Mod ID:** `sailboatmod`
**Package:** `com.monpai.sailboatmod`
**Entry point:** `SailboatMod.java` — initializes GeckoLib and registers all DeferredRegister instances on the mod event bus.

**Key dependencies:** GeckoLib 4.4.9 (entity animation), gdx-ai 1.8.2 (AI behaviors), SQLite JDBC 3.46.1.3 (commodity market persistence), BlockUI (UI framework), webp-imageio (JarJar bundled), Vault (optional economy), BlueMap (optional map rendering).

## Architecture

### Registry Layer (`registry/`)
All content uses Forge's `DeferredRegister` pattern. Registries initialized in `SailboatMod` constructor:
- `ModEntities` — three entity types: `SAILBOAT`, `RESIDENT`, `SOLDIER`
- `ModBlocks` / `ModBlockEntities` — dock, market, town core, nation core, flag variants
- `ModItems` — sailboat spawn item, route book, block items
- `ModMenus` — dock and market container menus
- `ModCreativeTabs` — single creative tab

### Network (`network/`)
`ModNetwork` registers 46 packets on a `SimpleChannel` (protocol version "1") with sequential IDs. Every packet class follows the same static-method pattern: `encode`, `decode`, `handle`. Add new packets at the end of `ModNetwork.register()` with `packetId++`. `PacketStringCodec` provides safe UTF encoding helpers.

### Entity System (`entity/`)
- **SailboatEntity** — extends `Boat`, implements GeckoLib's `GeoEntity`. Features: 5-seat passengers, 27-slot inventory, sail/engine/autopilot mechanics, rental pricing, route waypoint following. Physics uses knot-based speed with drag coefficients. Autopilot has stall detection and dock parking logic with chunk loading.
- **ResidentEntity / SoldierEntity** — NPC entities with AI goals, pathfinding, jobs, and army behavior (see Resident System below).

### Nation System (`nation/`)
Server-authoritative nation/town system with chunk-based territory claims.

- **Data:** `NationSavedData` persists nations, towns, members, offices, claims, wars, flags, diplomacy to `"sailboatmod_nations"` via Forge `SavedData`.
- **Services (`nation/service/`):** ~39 service classes, each handling one concern:
  - Core: `NationService`, `TownService`, `NationClaimService`, `TownClaimService`
  - Governance: `NationPermissionService`, `NationAdminService`, `NationOverviewService`, `TownOverviewService`
  - Diplomacy/War: `NationDiplomacyService`, `NationWarService`, `NationTradeService`
  - Economy: `TaxService` (sales tax, import tariffs), `NationTreasuryService`, `TownFinanceLedgerService`, `TownEconomySnapshotService`, `TownStockpileService`, `TownDemandLedgerService`
  - Construction: `BlueprintService`, `StructureConstructionManager`, `BuildingUpgradeService`, `BuildingPreviewService`, `ConstructionCostService`, `ConstructionMaterialRequestService`, `BankConstructionManager`
  - Flags: `NationFlagService`, `NationFlagStorage`, `NationFlagSyncService`, `NationFlagUploadService`, `TownFlagService`
  - Infrastructure: `DockTownResolver`, `TownDeliveryService`, `TownCultureService`, `RoadPathfinder`
- **Commands:** `NationCommands` registers Brigadier commands under `/nation`.
- **Models:** Immutable record classes (`NationRecord`, `TownRecord`, `NationMemberRecord`, `NationClaimRecord`, etc.).

### Market System (`market/`)

Two persistence layers coexist:

1. **NBT-backed (`MarketSavedData`):** Persists listings, purchase orders, shipping orders, and pending credits to `"sailboatmod_market"` via Forge `SavedData`. Listings are tied to dock positions for shipping logistics.

2. **SQLite-backed Commodity Market (`market/commodity/` + `market/db/`):**
   - `MarketDatabase` — singleton JDBC connection pool to a per-world SQLite file
   - `MarketSchemaManager` — versioned schema migrations via `SchemaPatch` records (currently 4 patches: base tables, indexes, player_market_settings, buy_orders)
   - `CommodityMarketRepository` — data access layer (commodity definitions, market state, trade records, player settings, buy orders)
   - `CommodityMarketService` — business logic: quoting (multi-factor pricing with supply/demand), stock adjustment, trade recording, buy orders, player price settings
   - Key models: `CommodityDefinition`, `CommodityMarketState`, `CommodityQuote`, `CommodityTradeRecord`, `BuyOrder`, `PlayerMarketSettings`, `CommodityCategories`
   - `CommodityKeyResolver` — resolves `ItemStack` to a canonical commodity key string
   - Pricing formula: `basePrice × volatilityFactor^(-stock)` with buy/sell spread, player adjustment (±10%), and seller price adjustment (±10%)

### Resident System (`resident/`)
NPC system with jobs, families, armies, and economy — the largest subsystem.

- **Entities:** `ResidentEntity` (civilian NPC), `SoldierEntity` (military NPC) with custom AI goals and pathfinding (`SimplePathfinder`, `MaplePathfinder`)
- **Data:** `ResidentSavedData` persists all resident records; `ArmySavedData` persists army state
- **Services:** `ResidentService`, `FamilyService`, `ResidentDeathService`, `ResidentEconomyService`, `BuildingConstructionService`, `ConstructionScaffoldingService`
- **Job/Task:** `JobManager`, `TaskManager` with pluggable job and task classes
- **Army:** `ArmyRecord`, `ArmyCommandManager`, `Formation`, `Stance`, `ArmyState`
- **Models:** `ResidentRecord`, `Gender`, `Culture`, `Profession`, `EducationLevel`, `HappinessData`, `FamilyData`, `DiseaseData`, `BuildingConstructionRecord`, `BuildingComplexity`

### Dock System (`dock/`)
`DockRegistry` tracks dock positions per dimension in a `ConcurrentHashMap`. `DockBlockEntity` stores dock name, owner, rental price, routes, parking zones, and storage. Provides shipment receiving and cargo management.

### Route System (`route/`)
`RouteDefinition` record holds waypoints, author info, distances, and dock names. Routes serialize to NBT via `RouteNbtUtil` and are stored on sailboat entities or route book items.

### Economy (`economy/`)
`VaultEconomyBridge` resolves Vault's Economy provider via reflection at runtime — no hard dependency. Methods return null when Vault is absent.

### Client Side (`client/`)
- **Events:** `ClientEvents` registers renderers (GeckoLib entity renderer, block entity renderers, resident renderer) and screen factories.
- **Input:** `ClientInputHandler` + `ClientKeyMappings` handle keybindings.
- **HUD:** `SailboatSpeedHud` renders speed/gear overlay.
- **Screens:** `DockScreen`, `MarketScreen`, `SailboatInfoScreen`, plus nation/town/resident sub-screens.
- **Client Hooks:** Per-system UI state caches (`DockClientHooks`, `MarketClientHooks`, `NationClientHooks`, `NationFlagTextureCache`).

### BlueMap Integration (`integration/bluemap/`)
`BlueMapIntegration` uses reflection to render docks, sailboats, routes, town/nation cores, and nation borders on BlueMap. `BlueMapMarkerSavedData` persists marker snapshots; sync runs every 40 ticks via `ServerEvents`.

## Key Patterns

- **Dual persistence:** Nation/dock/market listing data uses NBT-backed `SavedData`; commodity market pricing/history uses SQLite via `MarketDatabase`.
- **Optional integrations** (Vault, BlueMap) use reflection to avoid hard compile dependencies.
- **Packets** always follow `encode`/`decode`/`handle` static triple; add new packets at the end of `ModNetwork.register()` with the next sequential `packetId++`.
- **Block entities** that need client sync override `getUpdatePacket`/`getUpdateTag`.
- **Nation services** receive `NationSavedData` and operate on it; commands delegate to services.
- **Schema migrations** use the `SchemaPatch` record pattern in `MarketSchemaManager` — add new patches at the end with incrementing IDs.
- **MarketListing** is an immutable record; updates create new instances with modified fields and call `market.putListing()`.
- **Lang files** exist in both `en_us.json` and `zh_cn.json` — always update both when adding translatable strings.

## Resource Layout

- `assets/sailboatmod/` — GeckoLib models/animations, blockstates, block/item models, lang files (en_us, zh_cn), textures
- `data/sailboatmod/` — loot tables, recipes
- `META-INF/mods.toml` — mod metadata (uses variable substitution from `gradle.properties`)
