# Nation Multiplayer Manual Test Pass

## Scope

This checklist covers the current nation MVP surface after the nation home screen, claim permission editor, war feedback improvements, and nationadmin moderation commands landed.

## Test Setup

- Build the mod with .\gradlew.bat build and use the latest 1.1.2 jar on both client and dedicated server.
- Start one dedicated server and connect with at least two operator accounts plus one non-operator account.
- Keep BlueMap enabled if territory overlay validation is part of the pass.
- Enable command feedback so command results are visible in chat logs.

## Suggested Test Accounts

- AdminA: operator, used for /nationadmin
- PlayerA: nation founder / attacker
- PlayerB: second nation founder / defender
- PlayerC: neutral or invited member for permission checks

## Phase 1: Nation Creation And UI Sync

1. PlayerA runs /nation create Alpha.
Expected: nation created, nation chat prefix appears, nation home screen opens and shows overview data.
2. PlayerB runs /nation create Beta.
Expected: a second independent nation is created with separate overview data.
3. Both players open the nation screen with the keybind.
Expected: overview, member count, core status, claim summary, and flag panel all populate from server data.
4. Reconnect both players.
Expected: nation data persists and the home screen still reflects the saved server state.

## Phase 2: Claims And Permission Editor

1. Place each nation core in different locations.
Expected: each nation gets an active core and the core chunk becomes claimed.
2. PlayerA claims one adjacent chunk.
Expected: claim succeeds and BlueMap border updates if enabled.
3. PlayerA opens the nation home screen and cycles break/place/use permissions for the claimed chunk.
Expected: UI updates without command usage and the values persist after reopening the screen.
4. PlayerC attempts break/place/use in Alpha land before and after permission changes.
Expected: protection behavior matches the configured access levels.

## Phase 3: War Flow

1. Ensure both nations have cores, then run /nation war declare Beta as PlayerA.
Expected: declaration succeeds, both sides receive broadcast text, timer text appears, and the war section shows active status.
2. Move PlayerA into Beta core radius with PlayerB absent.
Expected: capture state changes to attacking and progress increases.
3. Add PlayerB to the core radius.
Expected: capture state changes to contested.
4. Remove attackers and leave only defenders.
Expected: capture state changes to defending and attacker progress decays.
5. End the war through score cap, timeout, or admin command.
Expected: winner/draw message appears, active war clears, cooldown starts, and a fresh declaration is blocked during cooldown.

## Phase 4: Flag Upload And Moderation

1. Upload a valid PNG flag as the nation leader.
Expected: upload succeeds, flag metadata shows in the nation screen, and placed nation flags render the uploaded texture.
2. Place at least one nation flag block in the world.
Expected: block entity binds to the nation and refreshes correctly after relog.
3. Run /nationadmin clearflag Alpha as AdminA.
Expected: flag metadata is removed, on-disk PNG is deleted, and placed flag blocks fall back to default visuals.
4. Re-upload a new flag.
Expected: the new flag ID is synced and old texture cache is no longer used.

## Phase 5: Admin Moderation Commands

1. Run /nationadmin debug dump.
Expected: summary lines show nation, member, claim, flag, and war totals.
2. Run /nationadmin debug dump Alpha.
Expected: nation-specific debug lines show counts and the current active war state.
3. Run /nationadmin setclaim Alpha <chunkX> <chunkZ> on an arbitrary chunk.
Expected: the chunk is force-assigned to Alpha even if it is not adjacent.
4. Run /nationadmin endwar <warId> during an active war.
Expected: war ends immediately and cooldown starts through the normal war service path.
5. Run /nationadmin disband Beta.
Expected: nation data, claims, flags, and wars tied to Beta are removed, and placed Beta flag blocks refresh to an unbound state.

## Phase 6: Persistence Checks

1. Restart the dedicated server after claims, flags, and at least one ended war exist.
Expected: nations, claims, flag metadata, and ended-war cooldown data still load correctly.
2. Reopen the nation home screen for surviving nations.
Expected: overview data still matches saved server state.
3. Check world/data/sailboatmod_flags/.
Expected: only active flag PNGs remain; cleared or disbanded nation flags are gone.

## Regression Notes

- zh_cn.json nation-tail localization should be spot-checked in-game because the file had prior structural corruption during development.
- No automated GameTests exist yet, so this checklist is the minimum release gate for multiplayer behavior.
- If any step fails, capture the exact command used, player role, dimension, chunk coordinates, and whether the failure reproduces after reconnect.