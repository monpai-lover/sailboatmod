package com.monpai.sailboatmod.resident.army;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles army commands: rally, march, attack, retreat.
 * Mount & Blade / Holdfast style: soldiers rally at a point, then move as a unit.
 */
public final class ArmyCommandManager {
    private static final double RALLY_RADIUS = 5.0;
    private static final double ARRIVAL_THRESHOLD = 3.0;
    private static final int RALLY_CHECK_INTERVAL = 20; // ticks

    private ArmyCommandManager() {}

    // ── Commands (called by player via CommandBaton or UI) ──

    public static void orderRally(ServerLevel level, String armyId, BlockPos rallyPoint) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) return;
        data.putArmy(army.withRallyPoint(rallyPoint).withState(ArmyState.RALLYING));
    }

    public static void orderMarch(ServerLevel level, String armyId, BlockPos target) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) return;
        data.putArmy(army.withTarget(target, null).withState(ArmyState.MARCHING));
    }

    public static void orderAttackMove(ServerLevel level, String armyId, BlockPos target) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) return;
        data.putArmy(army.withTarget(target, null).withState(ArmyState.ATTACKING).withStance(Stance.AGGRESSIVE));
    }

    public static void orderAttackEntity(ServerLevel level, String armyId, UUID targetEntity) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) return;
        data.putArmy(army.withTarget(BlockPos.ZERO, targetEntity).withState(ArmyState.ATTACKING));
    }

    public static void orderHold(ServerLevel level, String armyId) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) return;
        data.putArmy(army.withState(ArmyState.DEFENDING).withStance(Stance.HOLD));
    }

    public static void orderRetreat(ServerLevel level, String armyId) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) return;
        // Retreat to rally point
        data.putArmy(army.withState(ArmyState.RETREATING).withTarget(army.rallyPoint(), null));
    }

    public static void orderDisband(ServerLevel level, String armyId) {
        ArmySavedData data = ArmySavedData.get(level);
        data.removeArmy(armyId);
    }

    public static void setFormation(ServerLevel level, String armyId, Formation formation) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) return;
        data.putArmy(army.withFormation(formation));
    }

    public static void setStance(ServerLevel level, String armyId, Stance stance) {
        ArmySavedData data = ArmySavedData.get(level);
        ArmyRecord army = data.getArmy(armyId);
        if (army == null) return;
        data.putArmy(army.withStance(stance));
    }

    // ── Tick logic (called from ServerEvents) ──

    public static void tickAll(ServerLevel level) {
        if (level.getGameTime() % RALLY_CHECK_INTERVAL != 0) return;
        ArmySavedData data = ArmySavedData.get(level);
        for (ArmyRecord army : data.getAllArmies()) {
            tickArmy(level, data, army);
        }
    }

    private static void tickArmy(ServerLevel level, ArmySavedData data, ArmyRecord army) {
        switch (army.state()) {
            case RALLYING -> tickRallying(level, data, army);
            case RETREATING -> {
                // When retreating soldiers reach rally point, go IDLE
                // (actual movement handled by SoldierEntity AI goals)
            }
            default -> {} // MARCHING, ATTACKING, DEFENDING handled by entity AI
        }
    }

    private static void tickRallying(ServerLevel level, ArmySavedData data, ArmyRecord army) {
        // Check if enough soldiers have arrived at rally point
        // In M&B style: commander decides when to move out, not automatic
        // But we auto-transition RALLYING display info for the UI
    }

    // ── Soldier position queries ──

    /**
     * Get the world position a soldier should move to, based on army state and formation.
     */
    public static BlockPos getSoldierTargetPos(ArmyRecord army, int soldierIndex) {
        BlockPos anchor;
        if (army.state() == ArmyState.RALLYING || army.state() == ArmyState.IDLE || army.state() == ArmyState.RETREATING) {
            anchor = army.rallyPoint();
        } else {
            anchor = army.targetPoint().equals(BlockPos.ZERO) ? army.rallyPoint() : army.targetPoint();
        }

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
