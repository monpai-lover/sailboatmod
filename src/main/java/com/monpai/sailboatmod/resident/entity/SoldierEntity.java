package com.monpai.sailboatmod.resident.entity;

import com.monpai.sailboatmod.resident.army.*;
import com.monpai.sailboatmod.resident.entity.goal.FollowCommandGoal;
import com.monpai.sailboatmod.resident.entity.goal.FormationKeepGoal;
import com.monpai.sailboatmod.resident.entity.goal.SoldierAttackGoal;
import com.monpai.sailboatmod.resident.entity.goal.ThreatScanGoal;
import com.monpai.sailboatmod.resident.model.Profession;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.level.Level;

public class SoldierEntity extends ResidentEntity {
    private String armyId = "";

    public SoldierEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
        setProfession(Profession.SOLDIER);
    }

    public static AttributeSupplier.Builder createSoldierAttributes() {
        return ResidentEntity.createAttributes()
                .add(Attributes.MAX_HEALTH, 30.0D)
                .add(Attributes.ATTACK_DAMAGE, 4.0D)
                .add(Attributes.ARMOR, 2.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new FollowCommandGoal(this));
        this.goalSelector.addGoal(2, new SoldierAttackGoal(this, 1.2D));
        this.goalSelector.addGoal(3, new FormationKeepGoal(this, 1.0D));
        this.targetSelector.addGoal(1, new ThreatScanGoal(this));
    }

    public String getArmyId() { return armyId; }
    public void setArmyId(String id) { this.armyId = id == null ? "" : id; }

    public ArmyRecord getArmy() {
        if (armyId.isBlank() || level().isClientSide) return null;
        return ArmySavedData.get(level()).getArmy(armyId);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("ArmyId", armyId);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        armyId = tag.getString("ArmyId");
    }
}
