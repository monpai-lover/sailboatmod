package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class TownCoreBlockEntity extends BlockEntity {
    private static final int REFRESH_INTERVAL_TICKS = 20;

    private String townId = "";
    private String townName = "";
    private String nationName = "";
    private String mayorName = "";
    private int primaryColor = 0x4FA89B;

    public TownCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TOWN_CORE_BLOCK_ENTITY.get(), pos, state);
    }

    public String getTownId() { return townId; }
    public String getTownName() { return townName == null || townName.isBlank() ? "-" : townName; }
    public String getNationName() { return nationName == null || nationName.isBlank() ? "-" : nationName; }
    public String getMayorName() { return mayorName == null || mayorName.isBlank() ? "-" : mayorName; }
    public int getPrimaryColor() { return primaryColor & 0x00FFFFFF; }
    public Component getDisplayName() { return Component.translatable("block.sailboatmod.town_core"); }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            refreshFromTown();
        }
    }

    public void refreshFromTown() {
        if (level == null || level.isClientSide) {
            return;
        }
        NationSavedData data = NationSavedData.get(level);
        TownRecord town = findCoreTown(data);
        if (town == null) {
            clearTownBinding();
            return;
        }
        applyTown(data, town);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, TownCoreBlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel) || blockEntity == null) {
            return;
        }
        if ((serverLevel.getGameTime() % REFRESH_INTERVAL_TICKS) != 0L) {
            return;
        }
        blockEntity.refreshFromTown();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("TownId", townId == null ? "" : townId);
        tag.putString("TownName", townName == null ? "" : townName);
        tag.putString("NationName", nationName == null ? "" : nationName);
        tag.putString("MayorName", mayorName == null ? "" : mayorName);
        tag.putInt("PrimaryColor", primaryColor);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        townId = tag.getString("TownId");
        townName = tag.getString("TownName");
        nationName = tag.getString("NationName");
        mayorName = tag.getString("MayorName");
        primaryColor = tag.contains("PrimaryColor") ? tag.getInt("PrimaryColor") : 0x4FA89B;
    }

    @Override
    public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() != null) {
            load(pkt.getTag());
        }
    }

    @Nullable
    private TownRecord findCoreTown(NationSavedData data) {
        for (TownRecord town : data.getTowns()) {
            if (!town.hasCore()) {
                continue;
            }
            if (!level.dimension().location().toString().equalsIgnoreCase(town.coreDimension())) {
                continue;
            }
            if (worldPosition.asLong() == town.corePos()) {
                return town;
            }
        }
        if (townId == null || townId.isBlank()) {
            return null;
        }
        TownRecord town = data.getTown(townId);
        if (town == null || !town.hasCore()) {
            return null;
        }
        if (!level.dimension().location().toString().equalsIgnoreCase(town.coreDimension())) {
            return null;
        }
        return worldPosition.asLong() == town.corePos() ? town : null;
    }

    private void clearTownBinding() {
        updateState("", "", "", "", 0x4FA89B);
    }

    private void applyTown(NationSavedData data, TownRecord town) {
        NationRecord nation = town.nationId().isBlank() ? null : data.getNation(town.nationId());
        updateState(
                town.townId(),
                town.name(),
                nation == null ? "" : nation.name(),
                resolveMayorName(data, town.mayorUuid()),
                nation == null ? 0x4FA89B : nation.primaryColorRgb()
        );
    }

    private String resolveMayorName(NationSavedData data, UUID mayorUuid) {
        if (mayorUuid == null) {
            return "-";
        }
        if (level != null && level.getServer() != null) {
            ServerPlayer online = level.getServer().getPlayerList().getPlayer(mayorUuid);
            if (online != null) {
                return online.getGameProfile().getName();
            }
        }
        NationMemberRecord member = data.getMember(mayorUuid);
        if (member != null && !member.lastKnownName().isBlank()) {
            return member.lastKnownName();
        }
        return mayorUuid.toString();
    }

    private void updateState(String nextTownId, String nextTownName, String nextNationName, String nextMayorName, int nextPrimaryColor) {
        String safeTownId = safeValue(nextTownId);
        String safeTownName = nextTownName == null ? "" : nextTownName;
        String safeNationName = nextNationName == null ? "" : nextNationName;
        String safeMayorName = nextMayorName == null ? "" : nextMayorName;
        int safePrimaryColor = nextPrimaryColor & 0x00FFFFFF;
        boolean changed = !safeTownId.equals(townId)
                || !safeTownName.equals(townName)
                || !safeNationName.equals(nationName)
                || !safeMayorName.equals(mayorName)
                || safePrimaryColor != primaryColor;
        if (!changed) {
            return;
        }
        townId = safeTownId;
        townName = safeTownName;
        nationName = safeNationName;
        mayorName = safeMayorName;
        primaryColor = safePrimaryColor;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    private static String safeValue(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}