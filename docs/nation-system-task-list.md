# Nation System Task List

## Goal

Build a server-authoritative nation system for `sailboatmod` with:

- nation creation and membership
- offices and permissions
- chunk-based territorial claims
- chat/name prefixes using nation color
- BlueMap territory rendering
- war declarations and core capture gameplay
- PNG flag upload and placeable flag blocks

This document maps the feature to the current codebase so implementation can proceed in phases without reworking the existing mod structure.

## Current Status

- [x] Nation creation, membership, offices, permissions, and chat prefixes
- [x] Chunk claims, nation core placement, basic protection hooks, and BlueMap territory overlays
- [x] War declaration and core capture score ticking
- [x] Server-side PNG flag storage, client upload flow, S2C flag sync, and placeable flag rendering
- [x] Minimal nation menu with keybind, quick actions, flag upload, and claim-permission info
- [x] Per-claim break/place/use permission thresholds via command controls
- [x] Full multi-page nation management UI
- [ ] Advanced diplomacy polish, remaining moderation polish, and extra claim-editing refinements

## Next Work Queue

- [x] Build a real nation home screen with overview, members, claims, war, and flag sections
- [x] Add graphical claim-permission editing instead of command-only controls
- [x] Expand war gameplay with better feedback, timers, and post-war cleanup rules
- [x] Add admin moderation commands for nations, wars, and abusive flags
- [x] Add diplomacy controls, relation summaries, and alliance-request actions to the war page
- [ ] Add in-game validation/manual test pass for multiplayer flows (`docs/nation-multiplayer-manual-test.md` prepared; execution pending)

## Saved Next Fixes

- [x] Keep claim selection and rendered ownership strictly chunk-based, not block-based
- [x] Render claimed chunks with the owning nation's color as a semi-transparent overlay so terrain stays readable
- [x] Show a HUD message when entering claimed territory: `You entered xxx`
- [x] Show leader info on foreign-territory entry messages
- [x] Show `Welcome back to xxx` when entering your own nation's territory
- [x] Tint territory-entry text using the owning nation's configured color
- [x] Verify claim cost is fully backed by the economy system instead of a placeholder-only flow
- [x] Make the uploaded player image replace the vanilla banner cloth texture itself, so the white flag fabric shows the uploaded picture instead of using a separate overlay plane

## Current Integration Points

- Mod bootstrap: `src/main/java/com/example/examplemod/SailboatMod.java`
- Forge server events: `src/main/java/com/example/examplemod/ServerEvents.java`
- Client keybinds: `src/main/java/com/example/examplemod/client/ClientKeyMappings.java`
- Client input handling: `src/main/java/com/example/examplemod/client/ClientInputHandler.java`
- Existing screens: `src/main/java/com/example/examplemod/client/screen/`
- Packet channel: `src/main/java/com/example/examplemod/network/ModNetwork.java`
- Persistent world data pattern: `src/main/java/com/example/examplemod/market/MarketSavedData.java`
- BlueMap marker persistence: `src/main/java/com/example/examplemod/integration/bluemap/BlueMapMarkerSavedData.java`
- Existing block and item registries: `src/main/java/com/example/examplemod/registry/`

## Proposed Package Layout

Add a new feature root:

- `src/main/java/com/example/examplemod/nation/`

Recommended subpackages:

- `nation.data` for `SavedData`, snapshots, and NBT codecs
- `nation.model` for immutable nation/member/claim/war records
- `nation.service` for game rules and mutations
- `nation.permission` for claim permission evaluation
- `nation.flag` for server-side PNG storage and sync metadata
- `nation.war` for core capture and score logic
- `nation.menu` for non-container DTOs sent to the client
- `nation.command` for `/nation` commands

Client-side additions:

- `src/main/java/com/example/examplemod/client/screen/nation/`
- `src/main/java/com/example/examplemod/client/render/`
- `src/main/java/com/example/examplemod/client/texture/`

## Data Model Tasks

### Core Saved Data

- [x] Add `NationSavedData.java`
- [x] Load and save all nation-related state from overworld data storage
- [x] Mark dirty on every server-side mutation
- [x] Provide `get(Level)` following the same pattern as `MarketSavedData`

### Nation Records

- [x] Add `NationRecord.java`
- [x] Fields: `nationId`, `name`, `shortName`, `primaryColorRgb`, `secondaryColorRgb`, `leaderUuid`, `createdAt`, `corePos`, `coreDimension`, `flagId`
- [x] Add validation helpers for name length, reserved words, and color bounds

### Member Records

- [x] Add `NationMemberRecord.java`
- [x] Fields: `playerUuid`, `lastKnownName`, `nationId`, `officeId`, `joinedAt`
- [x] Support offline members

### Office Records

- [x] Add `NationOfficeRecord.java`
- [x] Fields: `officeId`, `name`, `priority`, `permissions`
- [x] Reserve a non-deletable leader office

### Claim Records

- [x] Add `NationClaimRecord.java`
- [x] Fields: `dimensionId`, `chunkX`, `chunkZ`, `nationId`, `breakAccessLevel`, `placeAccessLevel`, `useAccessLevel`, `claimedAt`
- [x] Store claims by chunk key for fast lookup

### Claim Permission Profiles

- [ ] Add `ClaimPermissionProfile.java`
- [ ] Include booleans for `break`, `place`, `interact`, `container`, edstone`, `entityUse`, `entityDamage`
- [ ] Separate rules for `leader`, `officer`, `member`, `ally`, `neutral`, `enemy`, `outsider`

### War Records

- [x] Add `NationWarRecord.java`
- [x] Fields: `warId`, `attackerNationId`, `defenderNationId`, `state`, `attackerScore`, `defenderScore`, `captureProgress`, `startedAt`, `lastCaptureTick`, `winnerNationId`, `endedAt`, `captureState`
- [x] Add war cooldown and anti-spam timestamps

### Flag Records

- [x] Add `NationFlagRecord.java`
- [x] Fields: `flagId`, `nationId`, `sha256`, `width`, `height`, `uploadedBy`, `uploadedAt`, `byteSize`, `mirrored`
- [x] Store only metadata in NBT, not raw image bytes

## Service Layer Tasks

### Nation Service

- [x] Add `NationService.java`
- [x] Create nation
- [x] Rename nation
- [x] Change colors
- [x] Transfer leadership
- [x] Disband nation
- [x] Invite member
- [x] Accept or reject applications
- [x] Join and leave flows
- [x] Kick member
- [x] Assign office
- [x] Validate every mutation server-side

### Claim Service

- [x] Add `NationClaimService.java`
- [x] Claim chunk
- [x] Unclaim chunk
- [x] Enforce adjacency to nation core or existing claims
- [x] Reject claims in blocked dimensions or protected zones
- [x] Compute border edges for BlueMap rendering
- [x] Expose lookup methods by `Level` and `ChunkPos`

### Permission Service

- [x] Add `NationPermissionService.java`
- [x] Resolve player nation role in a chunk
- [x] Resolve diplomatic relationship between two nations
- [x] Evaluate block, entity, and container permissions
- [x] Centralize all land-protection decisions

### War Service

- [x] Add `NationWarService.java`
- [x] Declare war
- [x] Start active war state
- [x] Tick capture progress near enemy core
- [x] Award score by occupation and optional combat events
- [x] End war on score cap or timeout
- [x] Apply cooldown after end

### Flag Service

- [x] Add `NationFlagStorage.java`
- [x] Save validated PNG files outside NBT, under world data folder
- [x] Deduplicate by hash
- [x] Load image metadata for sync
- [x] Delete orphaned flag files if a nation is removed

## Registry and World Object Tasks

### Blocks

- [x] Add `NationCoreBlock.java`
- [x] Add `NationFlagBlock.java`
- [x] Register both in `ModBlocks.java`

### Block Entities

- [x] Add `NationCoreBlockEntity.java`
- [x] Add `NationFlagBlockEntity.java`
- [x] Register both in `ModBlockEntities.java`

### Items

- [x] Add matching block items
- [ ] Decide whether `Nation Flag` is placeable by any member or only officers
- [x] Register items in `ModItems.java`

### Creative Tab

- [x] Decide whether nation blocks appear in the existing creative tab
- [x] Update `ModCreativeTabs.java` if needed

## Event Hook Tasks

### Server Events

- [x] Extend `ServerEvents.java` or create `NationServerEvents.java`
- [x] Tick war progress every server tick
- [x] Initialize and refresh BlueMap nation overlays
- [ ] Clear temporary war tracking on server stop

### Protection Events

- [x] Handle block break protection
- [x] Handle block place protection
- [x] Handle right-click block interaction
- [x] Handle entity interaction
- [x] Handle container access checks
- [x] Handle explosion filtering in claimed chunks
- [x] Handle fire or fluid griefing if required

### Player Lifecycle Events

- [x] Track last known player names for member lists
- [x] Sync nation data on player login
- [x] Sync name prefix on dimension change or join

### Chat and Display Events

- [x] Add chat formatting hook for `[Nation][Office] Player`
- [x] Use nation primary color for prefix rendering
- [x] Update player display name and tab list name if feasible
- [x] Keep formatting logic server-authoritative

## Network Tasks

Add packet classes under `src/main/java/com/example/examplemod/network/packet/`.

### C2S Packets

- [x] `OpenNationMenuPacket`
- [ ] `CreateNationPacket`
- [ ] `RenameNationPacket`
- [ ] `SetNationColorsPacket`
- [ ] `InviteNationMemberPacket`
- [ ] `RespondNationInvitePacket`
- [ ] `LeaveNationPacket`
- [ ] `KickNationMemberPacket`
- [ ] `AssignNationOfficePacket`
- [ ] `ClaimChunkPacket`
- [ ] `UnclaimChunkPacket`
- [x] `SetClaimPermissionPacket`
- [ ] `DeclareNationWarPacket`
- [ ] `InteractNationCorePacket`
- [x] `UploadNationFlagChunkPacket`
- [ ] `PlaceNationFlagSettingsPacket` if the flag block has configuration UI

### S2C Packets

- [x] `OpenNationScreenPacket`
- [ ] `NationOverviewSyncPacket`
- [ ] `NationMapSyncPacket`
- [ ] `NationWarSyncPacket`
- [ ] `NationFlagMetaSyncPacket`
- [ ] `NationFlagImageChunkPacket`
- [x] `NationToastPacket` for invites, war alerts, and claim failures

### Channel Registration

- [x] Register nation packets in `ModNetwork.java`
- [ ] Keep packet version compatibility in mind if the system evolves later

## Client Tasks

### Keybinds

- [x] Add `OPEN_NATION_MENU` to `ClientKeyMappings.java`
- [x] Default key recommendation: `N`

### Input Handling

- [x] Update `ClientInputHandler.java`
- [x] Open nation menu when the key is pressed and no other screen is open
- [x] Request fresh nation overview from the server

### Screens

Create screens under `client/screen/nation/`.

- [x] `NationHomeScreen` (replaces the `NationMenuScreen` placeholder)
- [ ] `NationMembersScreen`
- [ ] `NationOfficesScreen`
- [ ] `NationClaimsScreen`
- [ ] `NationDiplomacyScreen`
- [ ] `NationWarScreen`
- [ ] `NationFlagScreen`
- [ ] `NationCreateScreen`

### Client Data Objects

- [x] Add lightweight DTOs for nation overview, claims, war state, flag metadata, and diplomacy state
- [x] Cache the last synced nation state client-side

### Client Rendering

- [x] Render nation flag textures in menus
- [x] Render nation core status if it has a custom renderer
- [x] Render placed nation flags with the uploaded texture

## BlueMap Tasks

### Persistence

- [x] Extend the BlueMap save model or add a separate `NationBlueMapSavedData.java`
- [x] Persist nation overlay snapshots independent of runtime state

### Overlay Generation

- [x] Add nation marker set ID for borders and core markers
- [x] Convert chunk claims into contiguous border lines or polygons
- [x] Color the overlay using `NationRecord.primaryColorRgb`
- [x] Add core POI markers
- [x] Highlight active war targets

### Popup Details

- [x] Show nation name
- [x] Show leader name if known
- [x] Show chunk count
- [x] Show current war state
- [ ] Optionally show a tiny flag icon later

## War Gameplay Tasks

### Nation Core Rules

- [x] A nation must place a `Nation Core` to activate claims
- [x] Limit one active core per nation
- [x] Claims may require connection to the core or connected territory
- [x] Prevent unauthorized removal of the core

### Capture Rules

- [x] Define capture radius around the enemy core
- [x] Require attacker presence and defender absence, or set a contested state
- [x] Tick capture progress in server ticks
- [x] Convert full capture into war score
- [x] Reset or decay capture progress when the area is abandoned

### End Conditions

- [x] End on score limit
- [x] End on elapsed war time
- [x] End on admin command
- [x] Announce winner and stop capture logic

## Flag System Tasks

### Upload Pipeline

- [x] Restrict uploads to leader or authorized offices
- [x] Accept PNG, WebP, JPG, and JPEG uploads
- [ ] Limit dimensions, recommended first version: `64x64` or `128x64`
- [x] Limit file size, recommended first version: `128 KB`
- [x] Validate header and image bounds server-side
- [x] Reject malformed or suspicious files
- [ ] Normalize the final stored image to one standard size

### Transport

- [x] Implement chunked upload because raw images may exceed safe packet size
- [x] Track upload sessions server-side
- [x] Assemble and verify the image before committing

### Storage

- [x] Save under a stable folder such as `world/data/sailboatmod_flags/`
- [x] Name files by `flagId` or content hash
- [x] Keep only metadata in `NationSavedData`

### Client Download and Cache

- [x] Sync missing flag metadata on login or nation screen open
- [x] Download flag image in chunks if absent locally
- [x] Cache decoded textures
- [x] Invalidate cache when the nation changes flag

### Moderation and Safety

- [ ] Add cooldown for flag changes
- [x] Add admin command to clear or replace an abusive flag
- [ ] Decide whether multiplayer servers need manual review hooks

## Placeable Flag Tasks

### Basic Placement

- [x] Placed flag block stores `nationId`, not raw image data
- [x] On placement, infer nation from the placing player
- [x] Reject placement if the player has no nation

### Rendering

- [x] Pull nation flag texture from client cache
- [x] Add fallback texture if the image is missing
- [ ] Support standing and wall variants if desired
- [ ] Add simple wave animation later, not in MVP

### Protection

- [x] Use nation claim permissions for breaking or editing placed flags
- [ ] Prevent enemies from editing the flag outside war rules

## Commands and Admin Tasks

### Player Commands

- [x] `/nation create <name>`
- [x] `/nation invite <player>`
- [x] `/nation join <nation>`
- [x] `/nation leave`
- [x] `/nation claim`
- [x] `/nation unclaim`
- [x] `/nation info`
- [x] `/nation color <rgb>`
- [x] `/nation war declare <nation>`

### Admin Commands

- [x] `/nationadmin disband <nation>`
- [x] `/nationadmin setclaim <nation> <chunk>`
- [x] `/nationadmin endwar <warId>`
- [x] `/nationadmin clearflag <nation>`
- [x] `/nationadmin debug dump`

## Assets and Resource Tasks

### Block and Item Assets

- [x] Add models and blockstates for `nation_core`
- [x] Add models and blockstates for `nation_flag`
- [x] Add base textures and placeholders

### Language Keys

- [x] Add nation UI strings to `en_us.json`
- [x] Add names for offices, claims, war states, and errors

### Optional Data Generation

- [x] Add loot tables for nation blocks
- [x] Add recipes if these blocks are craftable
- [ ] Add tags if interaction restrictions depend on them

## Recommended Milestones

### Milestone 1: Nation Foundation

- [x] `NationSavedData`
- [x] nation records
- [x] `NationService`
- [x] basic `/nation` commands
- [x] create, join, leave, invite, kick

### Milestone 2: Nation UI and Prefixes

- [x] nation keybind
- [x] nation screens (tabbed `NationHomeScreen` now replaces the placeholder menu)
- [ ] overview sync packets
- [x] chat and display prefixes

### Milestone 3: Claims and Protection

- [x] claim service
- [x] chunk ownership
- [x] land permissions
- [x] block and interaction protection

### Milestone 4: Core and BlueMap

- [x] nation core block
- [x] BlueMap border rendering
- [x] BlueMap core marker

### Milestone 5: Flag Upload and Placement

- [x] PNG upload path
- [x] flag storage
- [x] nation flag screen (implemented as the flag tab inside `NationHomeScreen`)
- [x] placeable flag block

### Milestone 6: War System

- [x] war declarations
- [x] capture logic
- [ ] war score sync (server-side war logic exists; dedicated client sync still pending)
- [x] war announcements and end conditions

## MVP Cut Recommendation

If the goal is to reach a playable first release quickly, build only this subset first:

- [x] nation create, invite, join, leave
- [x] leader and officer permissions
- [x] nation colors
- [x] chat prefix
- [x] nation core
- [x] chunk claims
- [x] basic protection rules
- [x] BlueMap territory borders
- [x] one flag per nation with PNG upload
- [x] placeable nation flag block

Defer these until after the MVP:

- [ ] ally-specific permission templates
- [ ] complex diplomacy states
- [ ] score from combat kills
- [ ] animated large flags
- [ ] advanced moderation workflows

## Acceptance Checks

- [ ] A player can create a nation and see it persisted after restart (manual restart test pending)
- [x] A player can join a nation and receive the right chat prefix
- [ ] Claimed chunks block unauthorized placement and breaking (manual multiplayer test pending)
- [ ] BlueMap shows nation territory and core markers (manual integration test pending)
- [ ] War capture progress advances only under valid conditions (manual war test pending)
- [ ] A PNG flag survives restart and renders in UI and in-world (restart test pending)
- [ ] The system tolerates invalid packets and malformed uploads without corrupting saved data (targeted abuse tests pending)












