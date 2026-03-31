package com.monpai.sailboatmod.resident.entity;

import com.monpai.sailboatmod.resident.model.Culture;
import com.monpai.sailboatmod.resident.model.Gender;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public class ResidentEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> DATA_RESIDENT_ID =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_RESIDENT_NAME =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_PROFESSION =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_TOWN_ID =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SKIN_HASH =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_GENDER =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_AGE =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_HUNGER =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_EDUCATED =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DATA_CULTURE =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);

    public ResidentEntity(EntityType<? extends ResidentEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_RESIDENT_ID, "");
        this.entityData.define(DATA_RESIDENT_NAME, "Villager");
        this.entityData.define(DATA_PROFESSION, Profession.UNEMPLOYED.id());
        this.entityData.define(DATA_TOWN_ID, "");
        this.entityData.define(DATA_SKIN_HASH, "");
        this.entityData.define(DATA_GENDER, Gender.MALE.id());
        this.entityData.define(DATA_AGE, 25);
        this.entityData.define(DATA_HUNGER, ResidentRecord.MAX_HUNGER);
        this.entityData.define(DATA_EDUCATED, false);
        this.entityData.define(DATA_CULTURE, Culture.EUROPEAN.id());
    }
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    // --- Synched data accessors ---

    public String getResidentId() { return this.entityData.get(DATA_RESIDENT_ID); }
    public void setResidentId(String id) { this.entityData.set(DATA_RESIDENT_ID, id == null ? "" : id); }

    public String getResidentName() { return this.entityData.get(DATA_RESIDENT_NAME); }
    public void setResidentName(String name) { this.entityData.set(DATA_RESIDENT_NAME, name == null ? "Villager" : name); }

    public Profession getProfession() { return Profession.fromId(this.entityData.get(DATA_PROFESSION)); }
    public void setProfession(Profession p) { this.entityData.set(DATA_PROFESSION, p == null ? Profession.UNEMPLOYED.id() : p.id()); }

    public String getTownId() { return this.entityData.get(DATA_TOWN_ID); }
    public void setTownId(String id) { this.entityData.set(DATA_TOWN_ID, id == null ? "" : id); }

    public String getSkinHash() { return this.entityData.get(DATA_SKIN_HASH); }
    public void setSkinHash(String hash) { this.entityData.set(DATA_SKIN_HASH, hash == null ? "" : hash); }

    public Gender getGender() { return Gender.fromId(this.entityData.get(DATA_GENDER)); }
    public void setGender(Gender g) { this.entityData.set(DATA_GENDER, g == null ? Gender.MALE.id() : g.id()); }

    public int getAge() { return this.entityData.get(DATA_AGE); }
    public void setAge(int a) { this.entityData.set(DATA_AGE, a); }

    public int getHunger() { return this.entityData.get(DATA_HUNGER); }
    public void setHunger(int h) { this.entityData.set(DATA_HUNGER, h); }

    public boolean isEducated() { return this.entityData.get(DATA_EDUCATED); }
    public void setEducated(boolean e) { this.entityData.set(DATA_EDUCATED, e); }

    public Culture getCulture() { return Culture.fromId(this.entityData.get(DATA_CULTURE)); }
    public void setCulture(Culture c) { this.entityData.set(DATA_CULTURE, c == null ? Culture.EUROPEAN.id() : c.id()); }

    // --- NBT persistence ---

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("ResidentId", getResidentId());
        tag.putString("ResidentName", getResidentName());
        tag.putString("Profession", getProfession().id());
        tag.putString("TownId", getTownId());
        tag.putString("SkinHash", getSkinHash());
        tag.putString("Gender", getGender().id());
        tag.putInt("Age", getAge());
        tag.putInt("Hunger", getHunger());
        tag.putBoolean("Educated", isEducated());
        tag.putString("Culture", getCulture().id());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setResidentId(tag.getString("ResidentId"));
        setResidentName(tag.getString("ResidentName"));
        setProfession(Profession.fromId(tag.getString("Profession")));
        setTownId(tag.getString("TownId"));
        setSkinHash(tag.getString("SkinHash"));
        setGender(Gender.fromId(tag.getString("Gender")));
        setAge(tag.contains("Age") ? tag.getInt("Age") : 25);
        setHunger(tag.contains("Hunger") ? tag.getInt("Hunger") : ResidentRecord.MAX_HUNGER);
        setEducated(tag.getBoolean("Educated"));
        setCulture(Culture.fromId(tag.getString("Culture")));
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        String name = getResidentName();
        if (name.isBlank()) return super.getDisplayName();
        return net.minecraft.network.chat.Component.literal(name);
    }

    @Override
    protected net.minecraft.world.InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
        if (!player.level().isClientSide) {
            String genderStr = getGender().displayName();
            String profStr = getProfession().displayName();
            String eduStr = isEducated() ? "\u2713" : "\u2717";
            String hungerBar = getHunger() + "/" + ResidentRecord.MAX_HUNGER;
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "entity.sailboatmod.resident.info",
                    getResidentName(), genderStr, getAge(), profStr, hungerBar, eduStr, getCulture().displayName()));
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override
    protected boolean shouldDespawnInPeaceful() { return false; }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }
}
