package com.monpai.sailboatmod.resident.army;

import com.monpai.sailboatmod.resident.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles army commands: rally, march, attack, retreat.
 * Mount & Blade / Holdfast style: soldiers rally at a point, then move as a unit.
 */
public final class ArmyCommandManager {
    private static final double RALLY_RADIUS = 5.0D;
    private static final double ARRIVAL_THRESHOLD = 3.0D;
    private static final double WAYPOINT_ADVANCE_RADIUS = 6.0D;
    private static final int RALLY_CHECK_INTERVAL = 20;
    private static final int TARGET_REPATH_DISTANCE = 6;

    private ArmyCommandManager() {}

    public static void orderRally(ServerLevel level, String armyId, BlockPos rallyPoint) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) {
            return;
        }
        ArmyRecord updated = army
                .withRallyPoint(rallyPoint)
                .withTarget(rallyPoint, null)
                .withMarchRoute(buildRoute(level, army, rallyPoint), 0)
                .withState(ArmyState.RALLYING);
        data.putArmy(updated);
    }

    public static void orderMarch(ServerLevel level, String armyId, BlockPos target) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) {
            return;
        }
        ArmyRecord updated = army
                .withTarget(target, null)
                .withMarchRoute(buildRoute(level, army, target), 0)
                .withState(ArmyState.MARCHING);
        data.putArmy(updated);
    }

    public static void orderAttackMove(ServerLevel level, String armyId, BlockPos target) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) {
            return;
        }
        ArmyRecord updated = army
                .withTarget(target, null)
                .withMarchRoute(buildRoute(level, army, target), 0)
                .withState(ArmyState.ATTACKING)
                .withStance(Stance.AGGRESSIVE);
        data.putArmy(updated);
    }

    public static void orderAttackEntity(ServerLevel level, String armyId, UUID targetEntity) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) {
            return;
        }
        Entity entity = targetEntity == null ? null : level.getEntity(targetEntity);
        BlockPos target = entity == null ? army.targetPoint() : entity.blockPosition();
        ArmyRecord updated = army
                .withTarget(target, targetEntity)
                .withMarchRoute(buildRoute(level, army, target), 0)
                .withState(ArmyState.ATTACKING)
                .withStance(Stance.AGGRESSIVE);
        data.putArmy(updated);
    }

    public static void orderHold(ServerLevel level, String armyId) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) {
            return;
        }
        data.putArmy(army.clearMarchRoute().withState(ArmyState.DEFENDING).withStance(Stance.HOLD));
    }

    public static void orderRetreat(ServerLevel level, String armyId) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) {
            return;
        }
        ArmyRecord updated = army
                .withTarget(army.rallyPoint(), null)
                .withMarchRoute(buildRoute(level, army, army.rallyPoint()), 0)
                .withState(ArmyState.RETREATING);
        data.putArmy(updated);
    }

    public static void orderDisband(ServerLevel level, String armyId) {
        ArmySavedData data = ArmySavedData.get(level);
        data.removeArmy(armyId);
    }

    public static void setFormation(ServerLevel level, String armyId, Formation formation) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) {
            return;
        }
        data.putArmy(army.withFormation(formation));
    }

    public static void setStance(ServerLevel level, String armyId, Stance stance) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) {
            return;
        }
        data.putArmy(army.withStance(stance));
    }

    public static void tickAll(ServerLevel level) {
        if (level.getGameTime() % RALLY_CHECK_INTERVAL != 0) {
            return;
        }
        ArmySavedData data = ArmySavedData.get(level);
        for (ArmyRecord army : data.getAllArmies()) {
            tickArmy(level, data, army);
        }
    }

    private static void tickArmy(ServerLevel level, ArmySavedData data, ArmyRecord army) {
        army = refreshDynamicTarget(level, data, army);
        List<SoldierEntity> soldiers = getActiveSoldiers(level, army);
        if (soldiers.isEmpty()) {
            return;
        }
        switch (army.state()) {
            case RALLYING -> tickRallying(data, army, soldiers);
            case MARCHING, ATTACKING, RETREATING -> tickMovingArmy(data, army, soldiers);
            default -> {
            }
        }
    }

    private static ArmyRecord refreshDynamicTarget(ServerLevel level, ArmySavedData data, ArmyRecord army) {
        if (army.state() != ArmyState.ATTACKING || army.targetEntity() == null) {
            return army;
        }
        Entity entity = level.getEntity(army.targetEntity());
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            ArmyRecord updated = army.withTarget(army.currentAnchor(), null).clearMarchRoute().withState(ArmyState.DEFENDING);
            data.putArmy(updated);
            return updated;
        }
        BlockPos newTarget = entity.blockPosition();
        if (army.targetPoint().distManhattan(newTarget) < TARGET_REPATH_DISTANCE) {
            return army;
        }
        ArmyRecord updated = army.withTarget(newTarget, army.targetEntity()).withMarchRoute(buildRoute(level, army, newTarget), 0);
        data.putArmy(updated);
        return updated;
    }

    private static void tickRallying(ArmySavedData data, ArmyRecord army, List<SoldierEntity> soldiers) {
        if (!army.marchRoute().isEmpty()) {
            ArmyRecord advanced = maybeAdvanceWaypoint(data, army, soldiers);
            if (advanced != army) {
                army = advanced;
            }
        }
        if (countNear(soldiers, army.rallyPoint(), RALLY_RADIUS) >= requiredCount(soldiers.size())) {
            data.putArmy(army.clearMarchRoute().withTarget(army.rallyPoint(), null));
        }
    }

    private static void tickMovingArmy(ArmySavedData data, ArmyRecord army, List<SoldierEntity> soldiers) {
        ArmyRecord updated = maybeAdvanceWaypoint(data, army, soldiers);
        if (updated != army) {
            army = updated;
        }
        if (!hasReachedFinalDestination(soldiers, army)) {
            return;
        }
        ArmyRecord settled = switch (army.state()) {
            case RETREATING -> army.clearMarchRoute().withTarget(army.rallyPoint(), null).withState(ArmyState.RALLYING);
            case ATTACKING -> army.clearMarchRoute().withState(ArmyState.DEFENDING);
            case MARCHING -> army.clearMarchRoute().withState(ArmyState.DEFENDING);
            default -> army;
        };
        data.putArmy(settled);
    }

    private static ArmyRecord maybeAdvanceWaypoint(ArmySavedData data, ArmyRecord army, List<SoldierEntity> soldiers) {
        if (army.marchRoute().isEmpty()) {
            return army;
        }
        BlockPos waypoint = army.currentAnchor();
        int reached = countNear(soldiers, waypoint, WAYPOINT_ADVANCE_RADIUS);
        if (reached < requiredCount(soldiers.size())) {
            return army;
        }
        if (army.marchRouteIndex() >= army.marchRoute().size() - 1) {
            return army.clearMarchRoute();
        }
        ArmyRecord updated = army.withMarchRouteIndex(army.marchRouteIndex() + 1);
        data.putArmy(updated);
        return updated;
    }

    private static boolean hasReachedFinalDestination(List<SoldierEntity> soldiers, ArmyRecord army) {
        if (!army.marchRoute().isEmpty()) {
            return false;
        }
        BlockPos destination = army.state() == ArmyState.RETREATING ? army.rallyPoint() : army.targetPoint();
        if (destination.equals(BlockPos.ZERO)) {
            destination = army.rallyPoint();
        }
        return countNear(soldiers, destination, ARRIVAL_THRESHOLD + 1.5D) >= requiredCount(soldiers.size());
    }

    private static int countNear(List<SoldierEntity> soldiers, BlockPos target, double radius) {
        double radiusSqr = radius * radius;
        int count = 0;
        for (SoldierEntity soldier : soldiers) {
            if (soldier.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D) <= radiusSqr) {
                count++;
            }
        }
        return count;
    }

    private static int requiredCount(int total) {
        return Math.max(1, (int) Math.ceil(total * 0.6D));
    }

    private static List<BlockPos> buildRoute(ServerLevel level, ArmyRecord army, BlockPos target) {
        if (target == null || target.equals(BlockPos.ZERO)) {
            return List.of();
        }
        // Road system refactored - pending integration
        return List.of();
    }

    private static BlockPos resolveArmyCenter(ServerLevel level, ArmyRecord army) {
        List<SoldierEntity> soldiers = getActiveSoldiers(level, army);
        if (soldiers.isEmpty()) {
            if (!army.marchRoute().isEmpty()) {
                return army.currentAnchor();
            }
            return army.rallyPoint();
        }
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (SoldierEntity soldier : soldiers) {
            Vec3 pos = soldier.position();
            x += pos.x;
            y += pos.y;
            z += pos.z;
        }
        int count = soldiers.size();
        return BlockPos.containing(x / count, y / count, z / count);
    }

    private static List<SoldierEntity> getActiveSoldiers(ServerLevel level, ArmyRecord army) {
        List<SoldierEntity> soldiers = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof SoldierEntity soldier
                    && soldier.isAlive()
                    && army.armyId().equals(soldier.getArmyId())
                    && army.soldierIds().contains(soldier.getResidentId())) {
                soldiers.add(soldier);
            }
        }
        return soldiers;
    }

    /**
     * Get the world position a soldier should move to, based on army state and formation.
     */
    public static BlockPos getSoldierTargetPos(ArmyRecord army, int soldierIndex) {
        BlockPos anchor = army.currentAnchor();
        int[] offset = army.formation().getSlotOffset(soldierIndex, army.soldierIds().size());
        return anchor.offset(offset[0], 0, offset[1]);
    }

    /**
     * Find the index of a soldier in the army's soldier list.
     */
    public static int getSoldierIndex(ArmyRecord army, String residentId) {
        return army.soldierIds().indexOf(residentId);
    }
}
