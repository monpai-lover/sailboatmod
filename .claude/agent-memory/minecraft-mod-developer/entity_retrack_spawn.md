---
name: entity-retrack-spawn
description: How to force ChunkMap EntityTracker to re-send spawn packet to nearby players in Forge 1.20.1 (multi-mod "invisible vehicle" bug)
metadata:
  type: project
---

载具(CarriageEntity/SailboatEntity)用 ENTITY_TICKING 票据远行返回到站后,在多模组服务器上「看不见但有音效、驿站能扫到」。根因:服务端一切正确(chunkForced/nearbyPlayers/added 全正常),但某 mod 干扰 ChunkMap 让 EntityTracker 从未对该玩家发 spawn 包(ClientboundAddEntityPacket)。

**Why:** 已实测诊断确认是 spawn 包从未补发,不是位置不同步。旧失败修法 `hasImpulse=true; setPos(...)` 只发移动包,对「客户端不知道存在」的实体无效。

**How to apply:** 到站收尾点(CarriageEntity.stopAutopilot L1642 调 forceRetrackForNearbyPlayers;SailboatEntity 所有到站汇入 stopAutopilot(boolean) L1506,其 `if (level() instanceof ServerLevel)` 块 L1525 是镜像落点)强制重发 spawn。

仓库无 mixin 框架、无 AccessTransformer(build.gradle:72 注释掉),只能纯原版 API 或反射。

1.20.1 official-mappings 已 javap 验证的关键签名:
- `ServerChunkCache.chunkMap` = `public final ChunkMap`(无反射;ServerLevel.getChunkSource() 返回 ServerChunkCache)
- `ChunkMap.getPlayers(ChunkPos, boolean)` = public(项目已用:RoadPlannerBuildControlService:579)
- `ChunkMap.entityMap` = `private final Int2ObjectMap<ChunkMap.TrackedEntity>`(SRG f_140150_,需反射;reobf 后用 ObfuscationReflectionHelper 传 SRG 名,否则 NoSuchFieldException)
- `ChunkMap.TrackedEntity`(package-private 类)有 public `removePlayer(ServerPlayer)`/`updatePlayer(ServerPlayer)`/`broadcastRemoved()`,字段 `serverEntity`/`entity` package-private
- `ServerEntity.addPairing(ServerPlayer)`/`removePairing(ServerPlayer)` = public
- `Entity.getAddEntityPacket()` = public(Boat 不 override,用默认 ClientboundAddEntityPacket(Entity));`Entity.broadcastToPlayer()` vanilla 恒返回 true(说明 spawn 没被 hook 挡)

机制(bytecode 验证):updatePlayer = 范围内且 `seenBy.add(player.connection)` 返回 true 才 `serverEntity.addPairing`(完整 spawn+data+motion+passengers 打包 BundlePacket)。所以 removePlayer 然后 updatePlayer = 干净 untrack→retrack 重发完整 spawn。

推荐:反射 entityMap→TrackedEntity 调 removePlayer+updatePlayer 为主(唯一能保证 seenBy 注册+后续 delta+乘客);失败兜底用纯原版 chunkMap.getPlayers + player.connection.send(getAddEntityPacket()) + ClientboundSetEntityDataPacket(getNonDefaultValues) + ClientboundSetPassengersPacket。PacketDistributor.TRACKING_ENTITY 用不了(本 bug 恰恰无人追踪)。setRemoved+revive/addFreshEntity 高风险否决。
