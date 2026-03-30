package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.NationWarRecord;
import com.monpai.sailboatmod.nation.service.NationWarService;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class NationCoreBlockEntity extends BlockEntity {
    private static final int REFRESH_INTERVAL_TICKS = 20;

    private String nationId = "";
    private String nationName = "";
    private int primaryColor = 0xAA3333;
    private boolean activeWar;
    private String warRole = "";
    private String warOpponentName = "";
    private String warCaptureState = "idle";
    private int attackerScore;
    private int defenderScore;
    private int remainingWarSeconds;

    public NationCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NATION_CORE_BLOCK_ENTITY.get(), pos, state);
    }

    public String getNationId() {
        return nationId;
    }

    public String getNationName() {
        return nationName == null || nationName.isBlank() ? "-" : nationName;
    }

    public int getPrimaryColor() {
        return primaryColor & 0x00FFFFFF;
    }

    public boolean isActiveWar() {
        return activeWar;
    }

    public String getWarRole() {
        return warRole == null || warRole.isBlank() ? "-" : warRole;
    }

    public String getWarOpponentName() {
        return warOpponentName == null || warOpponentName.isBlank() ? "-" : warOpponentName;
    }

    public String getWarCaptureState() {
        return warCaptureState == null || warCaptureState.isBlank() ? "idle" : warCaptureState;
    }

    public int getAttackerScore() {
        return Math.max(0, attackerScore);
    }

    public int getDefenderScore() {
        return Math.max(0, defenderScore);
    }

    public int getRemainingWarSeconds() {
        return Math.max(0, remainingWarSeconds);
    }

    public Component getDisplayName() {
        return Component.translatable("block.sailboatmod.nation_core");
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            refreshFromNation();
        }
    }

    public void refreshFromNation() {
        if (level == null || level.isClientSide) {
            return;
        }
        NationSavedData data = NationSavedData.get(level);
        NationRecord nation = findCoreNation(data);
        if (nation == null) {
            clearNationBinding();
            return;
        }
        applyNation(data, nation);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, NationCoreBlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel) || blockEntity == null) {
            return;
        }
        if ((serverLevel.getGameTime() % REFRESH_INTERVAL_TICKS) != 0L) {
            return;
        }
        blockEntity.refreshFromNation();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("NationId", nationId == null ? "" : nationId);
        tag.putString("NationName", nationName == null ? "" : nationName);
        tag.putInt("PrimaryColor", primaryColor);
        tag.putBoolean("ActiveWar", activeWar);
        tag.putString("WarRole", warRole == null ? "" : warRole);
        tag.putString("WarOpponentName", warOpponentName == null ? "" : warOpponentName);
        tag.putString("WarCaptureState", warCaptureState == null ? "idle" : warCaptureState);
        tag.putInt("AttackerScore", attackerScore);
        tag.putInt("DefenderScore", defenderScore);
        tag.putInt("RemainingWarSeconds", remainingWarSeconds);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        nationId = tag.getString("NationId");
        nationName = tag.getString("NationName");
        primaryColor = tag.contains("PrimaryColor") ? tag.getInt("PrimaryColor") : 0xAA3333;
        activeWar = tag.contains("ActiveWar") && tag.getBoolean("ActiveWar");
        warRole = tag.getString("WarRole");
        warOpponentName = tag.getString("WarOpponentName");
        warCaptureState = tag.contains("WarCaptureState") ? tag.getString("WarCaptureState") : "idle";
        attackerScore = tag.contains("AttackerScore") ? Math.max(0, tag.getInt("AttackerScore")) : 0;
        defenderScore = tag.contains("DefenderScore") ? Math.max(0, tag.getInt("DefenderScore")) : 0;
        remainingWarSeconds = tag.contains("RemainingWarSeconds") ? Math.max(0, tag.getInt("RemainingWarSeconds")) : 0;
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() != null) {
            load(pkt.getTag());
        }
    }

    @Nullable
    private NationRecord findCoreNation(NationSavedData data) {
        for (NationRecord nation : data.getNations()) {
            if (!nation.hasCore()) {
                continue;
            }
            if (!level.dimension().location().toString().equalsIgnoreCase(nation.coreDimension())) {
                continue;
            }
            if (worldPosition.asLong() == nation.corePos()) {
                return nation;
            }
        }
        if (nationId == null || nationId.isBlank()) {
            return null;
        }
        NationRecord nation = data.getNation(nationId);
        if (nation == null || !nation.hasCore()) {
            return null;
        }
        if (!level.dimension().location().toString().equalsIgnoreCase(nation.coreDimension())) {
            return null;
        }
        return worldPosition.asLong() == nation.corePos() ? nation : null;
    }

    private void clearNationBinding() {
        updateState("", "", 0xAA3333, false, "", "", "idle", 0, 0, 0);
    }

    private void applyNation(NationSavedData data, NationRecord nation) {
        NationWarRecord war = NationWarService.getActiveWarForNation(data, nation.nationId());
        boolean nationIsAttacker = war != null && nation.nationId().equals(war.attackerNationId());
        NationRecord opponent = war == null
                ? null
                : data.getNation(nationIsAttacker ? war.defenderNationId() : war.attackerNationId());
        updateState(
                nation.nationId(),
                nation.name(),
                nation.primaryColorRgb(),
                war != null,
                war == null ? "" : (nationIsAttacker ? "attacker" : "defender"),
                opponent == null ? "" : opponent.name(),
                war == null ? "idle" : safeId(war.captureState()),
                war == null ? 0 : war.attackerScore(),
                war == null ? 0 : war.defenderScore(),
                war == null ? 0 : NationWarService.remainingWarSeconds(war, System.currentTimeMillis())
        );
    }

    private void updateState(String nextNationId, String nextNationName, int nextPrimaryColor, boolean nextActiveWar,
                             String nextWarRole, String nextWarOpponentName, String nextWarCaptureState,
                             int nextAttackerScore, int nextDefenderScore, int nextRemainingWarSeconds) {
        String safeNationId = safeId(nextNationId);
        String safeNationName = nextNationName == null ? "" : nextNationName;
        int safePrimaryColor = nextPrimaryColor & 0x00FFFFFF;
        String safeWarRole = safeId(nextWarRole);
        String safeWarOpponentName = nextWarOpponentName == null ? "" : nextWarOpponentName;
        String safeWarCaptureState = safeId(nextWarCaptureState).isBlank() ? "idle" : safeId(nextWarCaptureState);
        int safeAttackerScore = Math.max(0, nextAttackerScore);
        int safeDefenderScore = Math.max(0, nextDefenderScore);
        int safeRemainingWarSeconds = Math.max(0, nextRemainingWarSeconds);
        boolean changed = !safeNationId.equals(nationId)
                || !safeNationName.equals(nationName)
                || safePrimaryColor != primaryColor
                || nextActiveWar != activeWar
                || !safeWarRole.equals(warRole)
                || !safeWarOpponentName.equals(warOpponentName)
                || !safeWarCaptureState.equals(warCaptureState)
                || safeAttackerScore != attackerScore
                || safeDefenderScore != defenderScore
                || safeRemainingWarSeconds != remainingWarSeconds;
        if (!changed) {
            return;
        }
        nationId = safeNationId;
        nationName = safeNationName;
        primaryColor = safePrimaryColor;
        activeWar = nextActiveWar;
        warRole = safeWarRole;
        warOpponentName = safeWarOpponentName;
        warCaptureState = safeWarCaptureState;
        attackerScore = safeAttackerScore;
        defenderScore = safeDefenderScore;
        remainingWarSeconds = safeRemainingWarSeconds;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    private static String safeId(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}