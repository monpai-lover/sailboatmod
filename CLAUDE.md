# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build              # Full build (compile + reobfuscate + jarJar)
./gradlew compileJava        # Compile only, no jar packaging
./gradlew runClient          # Launch Minecraft client with mod loaded
./gradlew runServer          # Launch dedicated server with mod loaded
./gradlew genIntellijRuns    # Generate IntelliJ run configurations
./gradlew genEclipseRuns     # Generate Eclipse run configurations
```

Output JARs land in `build/libs/`. The `-reobf.jar` variant is the production artifact (SRG-remapped). The `-all.jar` includes bundled dependencies (JarJar).

A `fixIndex` property can be passed to append a `-fix_N` suffix: `./gradlew build -PfixIndex=1`

No test suite or linter is configured.

## Project Overview

Minecraft Forge 1.20.1 mod (Java 17) implementing a multiplayer sailing/nation system. Key dependencies: GeckoLib (entity animation), Vault (optional economy), BlueMap (optional map rendering).

**Mod ID:** `sailboatmod`
**Package:** `com.monpai.sailboatmod`
**Entry point:** `SailboatMod.java` — initializes GeckoLib and registers all DeferredRegister instances on the mod event bus.

## Architecture

### Registry Layer (`registry/`)
All content uses Forge's `DeferredRegister` pattern. Six registries initialized in `SailboatMod` constructor:
- `ModEntities` — single entity type (`SAILBOAT`)
- `ModBlocks` / `ModBlockEntities` — dock, market, town core, nation core, flag variants
- `ModItems` — sailboat spawn item, route book, block items
- `ModMenus` — dock and market container menus
- `ModCreativeTabs` — single creative tab

### Network (`network/`)
`ModNetwork` registers ~31 packets on a `SimpleChannel` (protocol version "1") with sequential IDs. Every packet class follows the same static-method pattern: `encode`, `decode`, `handle`. Packet names are self-descriptive (e.g., `ToggleSailPacket`, `CreateMarketListingPacket`).

### Entity (`entity/SailboatEntity.java`)
Extends `Boat`, implements GeckoLib's `GeoEntity`. Features: 5-seat passengers, 27-slot inventory, sail/engine/autopilot mechanics, rental pricing, route waypoint following. Physics uses knot-based speed with drag coefficients. Autopilot has stall detection and dock parking logic with chunk loading.

### Nation System (`nation/`)
Server-authoritative nation/town system with chunk-based territory claims.

- **Data:** `NationSavedData` persists nations, towns, members, offices, claims, wars, flags, diplomacy to `"sailboatmod_nations"` via Forge `SavedData`.
- **Services:** `NationService`, `TownService`, `NationClaimService`, `NationFlagService`, `NationDiplomacyService`, `NationWarService`, `NationAdminService`, `NationPermissionService`, `NationOverviewService`, `ClaimPreviewTerrainService` — each handles one concern.
- **Commands:** `NationCommands` registers Brigadier commands under `/nation` (create, rename, invite, town management, diplomacy, war, claims).
- **Models:** Immutable record classes (`NationRecord`, `TownRecord`, `NationMemberRecord`, `NationClaimRecord`, etc.).

### Market System (`market/`)
`MarketSavedData` persists listings, purchase orders, shipping orders, and pending credits to `"sailboatmod_market"`. Listings are tied to dock positions for shipping logistics.

### Dock System (`dock/`)
`DockRegistry` tracks dock positions per dimension in a `ConcurrentHashMap`. `DockBlockEntity` stores dock name, owner, rental price, routes, and parking zones.

### Route System (`route/`)
`RouteDefinition` record holds waypoints, author info, distances, and dock names. Routes serialize to NBT via `RouteNbtUtil` and are stored on sailboat entities or route book items.

### Economy (`economy/`)
`VaultEconomyBridge` resolves Vault's Economy provider via reflection at runtime — no hard dependency. Methods return null when Vault is absent.

### Client Side (`client/`)
- **Events:** `ClientEvents` registers renderers (GeckoLib entity renderer, block entity renderers) and screen factories.
- **Input:** `ClientInputHandler` + `ClientKeyMappings` handle keybindings (nation menu, sailboat storage, info screen).
- **HUD:** `SailboatSpeedHud` renders speed/gear overlay.
- **Screens:** `DockScreen`, `MarketScreen`, `SailboatInfoScreen`, plus nation/town sub-screens.
- **Client Hooks:** Per-system UI state caches (`DockClientHooks`, `MarketClientHooks`, `NationClientHooks`, `NationFlagTextureCache`).

### BlueMap Integration (`integration/bluemap/`)
`BlueMapIntegration` uses reflection to render docks, sailboats, routes, town/nation cores, and nation borders on BlueMap. `BlueMapMarkerSavedData` persists marker snapshots; sync runs every 40 ticks via `ServerEvents`.

### Event Handling
- `ServerEvents` (Forge bus) — server lifecycle, tick-based BlueMap sync, entity join tracking.
- `ClientEvents` (mod bus, client dist) — renderer and screen registration.
- `NationEvents` — nation-specific server events (territory entry messages, claim protection).

## Key Patterns

- **All persistence** uses Minecraft's `SavedData` (NBT-backed, per-world).
- **Optional integrations** (Vault, BlueMap) use reflection to avoid hard compile dependencies.
- **Packets** always follow `encode`/`decode`/`handle` static triple; add new packets at the end of `ModNetwork.register()` with the next sequential ID.
- **Block entities** that need client sync override `getUpdatePacket`/`getUpdateTag`.
- **Nation services** receive `NationSavedData` and operate on it; commands delegate to services.

## Resource Layout

- `assets/sailboatmod/` — GeckoLib models/animations, blockstates, block/item models, lang files (en_us, zh_cn)
- `data/sailboatmod/` — loot tables, recipes
- `META-INF/mods.toml` — mod metadata (uses variable substitution from `gradle.properties`)
