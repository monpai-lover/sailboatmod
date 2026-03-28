package com.example.examplemod.block.entity;

import com.example.examplemod.nation.data.NationSavedData;
import com.example.examplemod.nation.model.NationFlagRecord;
import com.example.examplemod.nation.model.NationMemberRecord;
import com.example.examplemod.nation.model.NationRecord;
import com.example.examplemod.nation.service.NationFlagBlockTracker;
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

public class NationFlagBlockEntity extends BlockEntity {
    private String nationId = "";
    private String nationName = "";
    private String flagId = "";
    private int primaryColor = 0x3A6EA5;
    private int secondaryColor = 0xF2C14E;
    private int flagWidth = 64;
    private int flagHeight = 32;
    private boolean flagMirrored;

    public NationFlagBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NATION_FLAG_BLOCK_ENTITY.get(), pos, state);
    }

    public boolean bindToPlayerNation(ServerPlayer player) {
        if (level == null || level.isClientSide || player == null) {
            return false;
        }
        NationSavedData data = NationSavedData.get(level);
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) {
            return false;
        }
        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) {
            return false;
        }
        applyNation(nation);
        return true;
    }

    public void refreshFromNation() {
        if (level == null || level.isClientSide || nationId == null || nationId.isBlank()) {
            return;
        }
        NationRecord nation = NationSavedData.get(level).getNation(nationId);
        if (nation != null) {
            applyNation(nation);
        } else {
            clearNationBinding();
        }
    }

    public String getNationId() { return nationId; }
    public String getNationName() { return nationName == null || nationName.isBlank() ? "-" : nationName; }
    public String getFlagId() { return flagId == null || flagId.isBlank() ? "none" : flagId; }
    public int getPrimaryColor() { return primaryColor & 0x00FFFFFF; }
    public int getSecondaryColor() { return secondaryColor & 0x00FFFFFF; }
    public int getFlagWidth() { return Math.max(1, flagWidth); }
    public int getFlagHeight() { return Math.max(1, flagHeight); }
    public boolean isFlagMirrored() { return flagMirrored; }
    public Component getDisplayName() { return Component.translatable("block.sailboatmod.nation_flag"); }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            NationFlagBlockTracker.register(serverLevel, worldPosition);
            refreshFromNation();
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            NationFlagBlockTracker.unregister(serverLevel, worldPosition);
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("NationId", nationId == null ? "" : nationId);
        tag.putString("NationName", nationName == null ? "" : nationName);
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
        nationId = tag.getString("NationId");
        nationName = tag.getString("NationName");
        flagId = tag.getString("FlagId");
        primaryColor = tag.contains("PrimaryColor") ? tag.getInt("PrimaryColor") : 0x3A6EA5;
        secondaryColor = tag.contains("SecondaryColor") ? tag.getInt("SecondaryColor") : 0xF2C14E;
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
        if (pkt.getTag() != null) { load(pkt.getTag()); }
    }

    private void clearNationBinding() {
        nationId = "";
        nationName = "";
        flagId = "";
        primaryColor = 0x3A6EA5;
        secondaryColor = 0xF2C14E;
        flagWidth = 64;
        flagHeight = 32;
        flagMirrored = false;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    private void applyNation(NationRecord nation) {
        NationFlagRecord flag = level == null ? null : NationSavedData.get(level).getFlag(nation.flagId());
        nationId = nation.nationId();
        nationName = nation.name();
        flagId = nation.flagId();
        primaryColor = nation.primaryColorRgb();
        secondaryColor = nation.secondaryColorRgb();
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
