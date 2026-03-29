package com.example.examplemod.block.entity;

import com.example.examplemod.nation.data.NationSavedData;
import com.example.examplemod.nation.model.NationFlagRecord;
import com.example.examplemod.nation.model.NationRecord;
import com.example.examplemod.nation.model.TownRecord;
import com.example.examplemod.nation.service.TownFlagBlockTracker;
import com.example.examplemod.nation.service.TownService;
import com.example.examplemod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class TownFlagBlockEntity extends BlockEntity {
    private static final int DEFAULT_PRIMARY_COLOR = 0x4FA89B;
    private static final int DEFAULT_SECONDARY_COLOR = 0xD8B35A;

    private String townId = "";
    private String townName = "";
    private String nationId = "";
    private String flagId = "";
    private int primaryColor = DEFAULT_PRIMARY_COLOR;
    private int secondaryColor = DEFAULT_SECONDARY_COLOR;
    private int flagWidth = 64;
    private int flagHeight = 32;
    private boolean flagMirrored;

    public TownFlagBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TOWN_FLAG_BLOCK_ENTITY.get(), pos, state);
    }

    public boolean bindToManagedTown(ServerPlayer player) {
        if (level == null || level.isClientSide || player == null) {
            return false;
        }
        TownRecord town = TownService.getManagedTownAt(player, worldPosition);
        if (town == null) {
            return false;
        }
        applyTown(NationSavedData.get(level), town);
        return true;
    }

    public void refreshFromTown() {
        if (level == null || level.isClientSide || townId == null || townId.isBlank()) {
            return;
        }
        NationSavedData data = NationSavedData.get(level);
        TownRecord town = data.getTown(townId);
        if (town != null) {
            applyTown(data, town);
        } else {
            clearTownBinding();
        }
    }

    public String getTownId() { return townId; }
    public String getTownName() { return townName == null || townName.isBlank() ? "-" : townName; }
    public String getNationId() { return nationId == null ? "" : nationId; }
    public String getFlagId() { return flagId == null || flagId.isBlank() ? "none" : flagId; }
    public int getPrimaryColor() { return primaryColor & 0x00FFFFFF; }
    public int getSecondaryColor() { return secondaryColor & 0x00FFFFFF; }
    public int getFlagWidth() { return Math.max(1, flagWidth); }
    public int getFlagHeight() { return Math.max(1, flagHeight); }
    public boolean isFlagMirrored() { return flagMirrored; }
    public Component getDisplayName() { return Component.translatable("block.sailboatmod.town_flag"); }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            TownFlagBlockTracker.register(serverLevel, worldPosition);
            refreshFromTown();
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TownFlagBlockTracker.unregister(serverLevel, worldPosition);
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("TownId", townId == null ? "" : townId);
        tag.putString("TownName", townName == null ? "" : townName);
        tag.putString("NationId", nationId == null ? "" : nationId);
        tag.putString("FlagId", flagId == null ? "" : flagId);
        tag.putInt("PrimaryColor", primaryColor);
        tag.putInt("SecondaryColor", secondaryColor);
        tag.putInt("FlagWidth", flagWidth);
        tag.putInt("FlagHeight", flagHeight);
        tag.putBoolean("FlagMirrored", flagMirrored);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        townId = tag.getString("TownId");
        townName = tag.getString("TownName");
        nationId = tag.getString("NationId");
        flagId = tag.getString("FlagId");
        primaryColor = tag.contains("PrimaryColor") ? tag.getInt("PrimaryColor") : DEFAULT_PRIMARY_COLOR;
        secondaryColor = tag.contains("SecondaryColor") ? tag.getInt("SecondaryColor") : DEFAULT_SECONDARY_COLOR;
        flagWidth = tag.contains("FlagWidth") ? Math.max(1, tag.getInt("FlagWidth")) : 64;
        flagHeight = tag.contains("FlagHeight") ? Math.max(1, tag.getInt("FlagHeight")) : 32;
        flagMirrored = tag.contains("FlagMirrored") && tag.getBoolean("FlagMirrored");
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

    private void clearTownBinding() {
        townId = "";
        townName = "";
        nationId = "";
        flagId = "";
        primaryColor = DEFAULT_PRIMARY_COLOR;
        secondaryColor = DEFAULT_SECONDARY_COLOR;
        flagWidth = 64;
        flagHeight = 32;
        flagMirrored = false;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    private void applyTown(NationSavedData data, TownRecord town) {
        NationRecord nation = town.hasNation() ? data.getNation(town.nationId()) : null;
        NationFlagRecord flag = town.flagId().isBlank() ? null : data.getFlag(town.flagId());
        townId = town.townId();
        townName = town.name();
        nationId = town.nationId();
        flagId = town.flagId();
        primaryColor = nation == null ? DEFAULT_PRIMARY_COLOR : nation.primaryColorRgb();
        secondaryColor = nation == null ? DEFAULT_SECONDARY_COLOR : nation.secondaryColorRgb();
        flagWidth = flag == null ? 64 : Math.max(1, flag.width());
        flagHeight = flag == null ? 32 : Math.max(1, flag.height());
        flagMirrored = flag != null && flag.mirrored();
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }
}