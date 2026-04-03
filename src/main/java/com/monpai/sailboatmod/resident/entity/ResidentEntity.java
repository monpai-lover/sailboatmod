package com.monpai.sailboatmod.resident.entity;

import com.monpai.sailboatmod.resident.entity.goal.BuildGoal;
import com.monpai.sailboatmod.resident.entity.goal.BuilderJobGoal;
import com.monpai.sailboatmod.resident.entity.goal.GuardPatrolGoal;
import com.monpai.sailboatmod.resident.entity.goal.SeekFoodGoal;
import com.monpai.sailboatmod.resident.entity.goal.SleepAtHomeGoal;
import com.monpai.sailboatmod.resident.entity.goal.Victoria3StudyGoal;
import com.monpai.sailboatmod.resident.entity.goal.WanderAroundHomeGoal;
import com.monpai.sailboatmod.resident.model.Culture;
import com.monpai.sailboatmod.resident.model.DiseaseData;
import com.monpai.sailboatmod.resident.model.EducationLevel;
import com.monpai.sailboatmod.resident.model.Gender;
import com.monpai.sailboatmod.resident.model.HappinessData;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import com.monpai.sailboatmod.resident.service.ResidentSkinService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.phys.Vec3;

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
    private static final EntityDataAccessor<Float> DATA_LITERACY =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<String> DATA_EDUCATION_LEVEL =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_CULTURE =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_LEARNING =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_MARTIAL =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_STEWARDSHIP =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_ASSIGNED_BED =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DATA_BUILDING_ACTIVE =
            SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.BOOLEAN);

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
        this.entityData.define(DATA_LITERACY, 0.0f);
        this.entityData.define(DATA_EDUCATION_LEVEL, EducationLevel.ILLITERATE.id());
        this.entityData.define(DATA_CULTURE, Culture.EUROPEAN.id());
        this.entityData.define(DATA_LEARNING, 5 + this.random.nextInt(11));
        this.entityData.define(DATA_MARTIAL, 5 + this.random.nextInt(11));
        this.entityData.define(DATA_STEWARDSHIP, 5 + this.random.nextInt(11));
        this.entityData.define(DATA_ASSIGNED_BED, "");
        this.entityData.define(DATA_BUILDING_ACTIVE, false);
    }
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SleepAtHomeGoal(this));
        this.goalSelector.addGoal(2, new SeekFoodGoal(this));
        this.goalSelector.addGoal(3, new BuilderJobGoal(this));
        this.goalSelector.addGoal(3, new GuardPatrolGoal(this));
        this.goalSelector.addGoal(4, new Victoria3StudyGoal(this));
        this.goalSelector.addGoal(4, new BuildGoal(this));
        this.goalSelector.addGoal(5, new WanderAroundHomeGoal(this));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    private HappinessData happinessData = new HappinessData();
    private DiseaseData diseaseData = new DiseaseData();

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide) {
            if (isBuildingActive() && this.tickCount % 10 == 0 && !this.swinging) {
                this.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            // Disease tick every tick
            if (diseaseData.isSick()) {
                diseaseData.tick();
                // Sick residents move slower
                this.setSpeed(0.15f);
            } else {
                this.setSpeed(0.25f);
            }

            // Every 200 ticks: hunger, happiness, disease check
            if (this.tickCount % 200 == 0) {
                String id = getResidentId();
                if (!id.isEmpty() && this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    com.monpai.sailboatmod.resident.data.ResidentSavedData data =
                        com.monpai.sailboatmod.resident.data.ResidentSavedData.get(serverLevel);
                    ResidentRecord record = data.getResident(id);
                    if (record != null) {
                        // Decrease hunger
                        if (record.hunger() > 0) {
                            record = record.withHunger(record.hunger() - (diseaseData.isSick() ? 2 : 1));
                        }
                        // Random sickness check
                        boolean hasHome = record.assignedBed() != null && !record.assignedBed().equals(net.minecraft.core.BlockPos.ZERO);
                        diseaseData.processRandomSickness(record.hunger(), hasHome);

                        // Disease factor affects happiness
                        happinessData.setModifier("health", diseaseData.isSick() ? -20 : 5);

                        // Update happiness
                        happinessData.processDailyUpdate(record);
                        record = record.withHappiness(happinessData.calculateHappiness());
                        data.putResident(record);
                    }
                }
            }
        }
    }

    public HappinessData getHappinessData() { return happinessData; }
    public DiseaseData getDiseaseData() { return diseaseData; }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        if (!this.level().isClientSide && this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            String cause = source.getLocalizedDeathMessage(this).getString();
            com.monpai.sailboatmod.resident.service.ResidentDeathService.onResidentDeath(
                serverLevel, getResidentId(), this.blockPosition(), cause);
            com.monpai.sailboatmod.resident.service.ResidentDeathService.triggerMourning(
                serverLevel, getTownId(), this.blockPosition());
        }
        super.die(source);
    }

    // --- Synched data accessors ---

    public String getResidentId() { return this.entityData.get(DATA_RESIDENT_ID); }
    public void setResidentId(String id) { this.entityData.set(DATA_RESIDENT_ID, id == null ? "" : id); }

    public String getResidentName() { return this.entityData.get(DATA_RESIDENT_NAME); }
    public void setResidentName(String name) {
        String resolvedName = (name == null || name.isBlank()) ? "Villager" : name;
        this.entityData.set(DATA_RESIDENT_NAME, resolvedName);
        this.setCustomName(net.minecraft.network.chat.Component.literal(resolvedName));
        this.setCustomNameVisible(true);
    }

    public Profession getProfession() { return Profession.fromId(this.entityData.get(DATA_PROFESSION)); }
    public void setProfession(Profession p) { this.entityData.set(DATA_PROFESSION, p == null ? Profession.UNEMPLOYED.id() : p.id()); }

    public String getTownId() { return this.entityData.get(DATA_TOWN_ID); }
    public void setTownId(String id) { this.entityData.set(DATA_TOWN_ID, id == null ? "" : id); }

    public String getSkinHash() { return this.entityData.get(DATA_SKIN_HASH); }
    public void setSkinHash(String hash) {
        this.entityData.set(DATA_SKIN_HASH, ResidentSkinService.resolveSkinHash(
                hash,
                getResidentId(),
                getProfession(),
                getGender()
        ));
    }

    public Gender getGender() { return Gender.fromId(this.entityData.get(DATA_GENDER)); }
    public void setGender(Gender g) { this.entityData.set(DATA_GENDER, g == null ? Gender.MALE.id() : g.id()); }

    public int getAge() { return this.entityData.get(DATA_AGE); }
    public void setAge(int a) { this.entityData.set(DATA_AGE, a); }

    public int getHunger() { return this.entityData.get(DATA_HUNGER); }
    public void setHunger(int h) { this.entityData.set(DATA_HUNGER, h); }

    public boolean isEducated() { return this.entityData.get(DATA_EDUCATED); }
    public void setEducated(boolean e) { this.entityData.set(DATA_EDUCATED, e); }

    public float getLiteracy() { return this.entityData.get(DATA_LITERACY); }
    public void setLiteracy(float l) { this.entityData.set(DATA_LITERACY, Math.max(0.0f, Math.min(1.0f, l))); }

    public EducationLevel getEducationLevel() { return EducationLevel.fromId(this.entityData.get(DATA_EDUCATION_LEVEL)); }
    public void setEducationLevel(EducationLevel level) { this.entityData.set(DATA_EDUCATION_LEVEL, level == null ? EducationLevel.ILLITERATE.id() : level.id()); }

    public Culture getCulture() { return Culture.fromId(this.entityData.get(DATA_CULTURE)); }
    public void setCulture(Culture c) { this.entityData.set(DATA_CULTURE, c == null ? Culture.EUROPEAN.id() : c.id()); }

    public int getLearning() { return this.entityData.get(DATA_LEARNING); }
    public void setLearning(int l) { this.entityData.set(DATA_LEARNING, Math.max(0, Math.min(20, l))); }

    public int getMartial() { return this.entityData.get(DATA_MARTIAL); }
    public void setMartial(int m) { this.entityData.set(DATA_MARTIAL, Math.max(0, Math.min(20, m))); }

    public int getStewardship() { return this.entityData.get(DATA_STEWARDSHIP); }
    public void setStewardship(int s) { this.entityData.set(DATA_STEWARDSHIP, Math.max(0, Math.min(20, s))); }

    public net.minecraft.core.BlockPos getAssignedBed() {
        String raw = this.entityData.get(DATA_ASSIGNED_BED);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return net.minecraft.core.BlockPos.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }
    public void setAssignedBed(net.minecraft.core.BlockPos pos) {
        this.entityData.set(DATA_ASSIGNED_BED, pos == null || pos.equals(net.minecraft.core.BlockPos.ZERO) ? "" : String.valueOf(pos.asLong()));
    }

    public boolean isBuildingActive() {
        return this.entityData.get(DATA_BUILDING_ACTIVE);
    }

    public void setBuildingActive(boolean active) {
        this.entityData.set(DATA_BUILDING_ACTIVE, active);
    }

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
        tag.putFloat("Literacy", getLiteracy());
        tag.putString("EducationLevel", getEducationLevel().id());
        tag.putString("Culture", getCulture().id());
        tag.putInt("Learning", getLearning());
        tag.putInt("Martial", getMartial());
        tag.putInt("Stewardship", getStewardship());
        net.minecraft.core.BlockPos bed = getAssignedBed();
        if (bed != null) {
            tag.putLong("AssignedBed", bed.asLong());
        }
        tag.put("Happiness", happinessData.save());
        tag.put("Disease", diseaseData.save());
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
        setLiteracy(tag.contains("Literacy") ? tag.getFloat("Literacy") : 0.0f);
        setEducationLevel(EducationLevel.fromId(tag.getString("EducationLevel")));
        setCulture(Culture.fromId(tag.getString("Culture")));
        setLearning(tag.contains("Learning") ? tag.getInt("Learning") : 5 + this.random.nextInt(11));
        setMartial(tag.contains("Martial") ? tag.getInt("Martial") : 5 + this.random.nextInt(11));
        setStewardship(tag.contains("Stewardship") ? tag.getInt("Stewardship") : 5 + this.random.nextInt(11));
        if (tag.contains("AssignedBed")) {
            setAssignedBed(net.minecraft.core.BlockPos.of(tag.getLong("AssignedBed")));
        }
        if (tag.contains("Happiness")) {
            happinessData = HappinessData.load(tag.getCompound("Happiness"));
        }
        if (tag.contains("Disease")) {
            diseaseData = DiseaseData.load(tag.getCompound("Disease"));
        }
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        String name = getResidentName();
        if (name.isBlank()) return super.getDisplayName();
        return net.minecraft.network.chat.Component.literal(name);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return super.mobInteract(player, hand);
        }
        if (!player.level().isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ResidentRecord record = buildInteractionRecord(serverPlayer.serverLevel());
            if (record != null) {
                com.monpai.sailboatmod.network.ModNetwork.CHANNEL.sendTo(
                    new com.monpai.sailboatmod.network.packet.OpenResidentScreenPacket(record),
                    serverPlayer.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                );
            }
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override
    public InteractionResult interactAt(Player player, Vec3 hitVec, InteractionHand hand) {
        return mobInteract(player, hand);
    }

    @Override
    protected boolean shouldDespawnInPeaceful() { return false; }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    private ResidentRecord buildInteractionRecord(net.minecraft.server.level.ServerLevel level) {
        String residentId = getResidentId();
        com.monpai.sailboatmod.resident.data.ResidentSavedData data =
                com.monpai.sailboatmod.resident.data.ResidentSavedData.get(level);
        ResidentRecord record = residentId.isEmpty() ? null : data.getResident(residentId);
        if (record != null) {
            String resolvedSkin = ResidentSkinService.resolveSkinHash(
                    record.skinHash(),
                    record.residentId(),
                    record.profession(),
                    record.gender()
            );
            if (!resolvedSkin.equals(record.skinHash())) {
                record = record.withSkinHash(resolvedSkin);
                data.putResident(record);
            }
            return record;
        }
        if (residentId.isEmpty()) {
            return null;
        }
        return new ResidentRecord(
                residentId,
                getTownId(),
                getResidentName(),
                ResidentSkinService.resolveSkinHash(getSkinHash(), residentId, getProfession(), getGender()),
                getProfession(),
                getGender(),
                getAge(),
                getHunger(),
                isEducated(),
                getCulture(),
                1,
                50,
                BlockPos.ZERO,
                getAssignedBed() == null ? BlockPos.ZERO : getAssignedBed(),
                System.currentTimeMillis()
        );
    }
}
