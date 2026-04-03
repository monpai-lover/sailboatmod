package com.monpai.sailboatmod.resident.army;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public record ArmyRecord(
        String armyId,
        String nationId,
        String name,
        UUID commanderUuid,
        Formation formation,
        Stance stance,
        ArmyState state,
        List<String> soldierIds,
        BlockPos rallyPoint,
        BlockPos targetPoint,
        List<BlockPos> marchRoute,
        int marchRouteIndex,
        UUID targetEntity,
        long createdAt
) {
    public static final int MAX_SOLDIERS = 20;

    public ArmyRecord {
        armyId = armyId == null ? "" : armyId.trim();
        nationId = nationId == null ? "" : nationId.trim().toLowerCase(Locale.ROOT);
        name = name == null ? "Army" : name.trim();
        if (formation == null) formation = Formation.LINE;
        if (stance == null) stance = Stance.DEFENSIVE;
        if (state == null) state = ArmyState.IDLE;
        soldierIds = soldierIds == null ? List.of() : List.copyOf(soldierIds);
        if (rallyPoint == null) rallyPoint = BlockPos.ZERO;
        if (targetPoint == null) targetPoint = BlockPos.ZERO;
        marchRoute = marchRoute == null ? List.of() : List.copyOf(marchRoute);
        marchRouteIndex = Math.max(0, Math.min(marchRouteIndex, Math.max(0, marchRoute.size() - 1)));
    }

    public static ArmyRecord create(String nationId, String name, UUID commanderUuid, BlockPos rallyPoint) {
        return new ArmyRecord(
                UUID.randomUUID().toString().substring(0, 8),
                nationId,
                name,
                commanderUuid,
                Formation.LINE,
                Stance.DEFENSIVE,
                ArmyState.IDLE,
                List.of(),
                rallyPoint,
                BlockPos.ZERO,
                List.of(),
                0,
                null,
                System.currentTimeMillis()
        );
    }

    public ArmyRecord withFormation(Formation f) {
        return new ArmyRecord(armyId, nationId, name, commanderUuid, f, stance, state, soldierIds, rallyPoint, targetPoint, marchRoute, marchRouteIndex, targetEntity, createdAt);
    }

    public ArmyRecord withStance(Stance s) {
        return new ArmyRecord(armyId, nationId, name, commanderUuid, formation, s, state, soldierIds, rallyPoint, targetPoint, marchRoute, marchRouteIndex, targetEntity, createdAt);
    }

    public ArmyRecord withState(ArmyState s) {
        return new ArmyRecord(armyId, nationId, name, commanderUuid, formation, stance, s, soldierIds, rallyPoint, targetPoint, marchRoute, marchRouteIndex, targetEntity, createdAt);
    }

    public ArmyRecord withSoldiers(List<String> ids) {
        return new ArmyRecord(armyId, nationId, name, commanderUuid, formation, stance, state, ids, rallyPoint, targetPoint, marchRoute, marchRouteIndex, targetEntity, createdAt);
    }

    public ArmyRecord withRallyPoint(BlockPos pos) {
        return new ArmyRecord(armyId, nationId, name, commanderUuid, formation, stance, state, soldierIds, pos, targetPoint, marchRoute, marchRouteIndex, targetEntity, createdAt);
    }

    public ArmyRecord withTarget(BlockPos pos, UUID entity) {
        return new ArmyRecord(armyId, nationId, name, commanderUuid, formation, stance, state, soldierIds, rallyPoint, pos, marchRoute, marchRouteIndex, entity, createdAt);
    }

    public ArmyRecord withMarchRoute(List<BlockPos> route, int routeIndex) {
        return new ArmyRecord(armyId, nationId, name, commanderUuid, formation, stance, state, soldierIds, rallyPoint, targetPoint, route, routeIndex, targetEntity, createdAt);
    }

    public ArmyRecord withMarchRouteIndex(int routeIndex) {
        return new ArmyRecord(armyId, nationId, name, commanderUuid, formation, stance, state, soldierIds, rallyPoint, targetPoint, marchRoute, routeIndex, targetEntity, createdAt);
    }

    public ArmyRecord clearMarchRoute() {
        return new ArmyRecord(armyId, nationId, name, commanderUuid, formation, stance, state, soldierIds, rallyPoint, targetPoint, List.of(), 0, targetEntity, createdAt);
    }

    public BlockPos currentAnchor() {
        if (!marchRoute.isEmpty()) {
            return marchRoute.get(marchRouteIndex);
        }
        if (state == ArmyState.RALLYING || state == ArmyState.IDLE || state == ArmyState.RETREATING) {
            return rallyPoint;
        }
        return targetPoint.equals(BlockPos.ZERO) ? rallyPoint : targetPoint;
    }

    public boolean isFull() {
        return soldierIds.size() >= MAX_SOLDIERS;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", armyId);
        tag.putString("Nation", nationId);
        tag.putString("Name", name);
        tag.putString("Commander", commanderUuid == null ? "" : commanderUuid.toString());
        tag.putString("Formation", formation.id());
        tag.putString("Stance", stance.id());
        tag.putString("State", state.id());
        tag.putLong("RallyX", rallyPoint.getX());
        tag.putLong("RallyY", rallyPoint.getY());
        tag.putLong("RallyZ", rallyPoint.getZ());
        tag.putLong("TargetX", targetPoint.getX());
        tag.putLong("TargetY", targetPoint.getY());
        tag.putLong("TargetZ", targetPoint.getZ());
        tag.putInt("RouteIndex", marchRouteIndex);
        tag.putString("TargetEntity", targetEntity == null ? "" : targetEntity.toString());
        tag.putLong("CreatedAt", createdAt);

        ListTag soldierList = new ListTag();
        for (String id : soldierIds) {
            soldierList.add(StringTag.valueOf(id));
        }
        tag.put("Soldiers", soldierList);

        ListTag routeList = new ListTag();
        for (BlockPos pos : marchRoute) {
            CompoundTag routePos = new CompoundTag();
            routePos.putLong("Pos", pos.asLong());
            routeList.add(routePos);
        }
        tag.put("Route", routeList);
        return tag;
    }

    public static ArmyRecord load(CompoundTag tag) {
        List<String> soldiers = new ArrayList<>();
        ListTag soldierList = tag.getList("Soldiers", Tag.TAG_STRING);
        for (int i = 0; i < soldierList.size(); i++) {
            soldiers.add(soldierList.getString(i));
        }

        List<BlockPos> route = new ArrayList<>();
        ListTag routeList = tag.getList("Route", Tag.TAG_COMPOUND);
        for (int i = 0; i < routeList.size(); i++) {
            route.add(BlockPos.of(routeList.getCompound(i).getLong("Pos")));
        }

        String commander = tag.getString("Commander");
        UUID commanderUuid = commander.isBlank() ? null : tryUuid(commander);
        String targetEntity = tag.getString("TargetEntity");
        UUID targetEntityUuid = targetEntity.isBlank() ? null : tryUuid(targetEntity);
        return new ArmyRecord(
                tag.getString("Id"),
                tag.getString("Nation"),
                tag.getString("Name"),
                commanderUuid,
                Formation.fromId(tag.getString("Formation")),
                Stance.fromId(tag.getString("Stance")),
                ArmyState.fromId(tag.getString("State")),
                soldiers,
                new BlockPos((int) tag.getLong("RallyX"), (int) tag.getLong("RallyY"), (int) tag.getLong("RallyZ")),
                new BlockPos((int) tag.getLong("TargetX"), (int) tag.getLong("TargetY"), (int) tag.getLong("TargetZ")),
                route,
                tag.getInt("RouteIndex"),
                targetEntityUuid,
                tag.getLong("CreatedAt")
        );
    }

    private static UUID tryUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
