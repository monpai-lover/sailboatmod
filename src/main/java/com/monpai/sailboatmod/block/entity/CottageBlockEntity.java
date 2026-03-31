package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.TownService;
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

public class CottageBlockEntity extends BlockEntity {
    private static final int REFRESH_INTERVAL_TICKS = 40;
    private String townId = "";
    private String townName = "";
    private int secondaryColor = 0xD8B35A;
    private int residentCapacity = 2;

    public CottageBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COTTAGE_BLOCK_ENTITY.get(), pos, state);
    }

    public String getTownId() { return townId; }
    public String getTownName() { return townName == null || townName.isBlank() ? "" : townName; }
    public int getSecondaryColor() { return secondaryColor & 0x00FFFFFF; }
    public int getResidentCapacity() { return residentCapacity; }

    public void refreshFromTown() {
        if (level == null || level.isClientSide) return;
        TownRecord town = TownService.getTownAt(level, worldPosition);
        if (town == null) { updateState("", "", 0xD8B35A); return; }
        NationSavedData data = NationSavedData.get(level);
        NationRecord nation = town.nationId().isBlank() ? null : data.getNation(town.nationId());
        updateState(town.townId(), town.name(), nation == null ? 0xD8B35A : nation.secondaryColorRgb());
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CottageBlockEntity be) {
        if (!(level instanceof ServerLevel sl) || be == null) return;
        if ((sl.getGameTime() % REFRESH_INTERVAL_TICKS) != 0L) return;
        be.refreshFromTown();
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("TownId", townId); tag.putString("TownName", townName);
        tag.putInt("SecondaryColor", secondaryColor); tag.putInt("Capacity", residentCapacity);
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag);
        townId = tag.getString("TownId"); townName = tag.getString("TownName");
        secondaryColor = tag.contains("SecondaryColor") ? tag.getInt("SecondaryColor") : 0xD8B35A;
        residentCapacity = tag.contains("Capacity") ? tag.getInt("Capacity") : 2;
    }
    @Override public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }
    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) { if (pkt.getTag() != null) load(pkt.getTag()); }

    private void updateState(String tid, String tname, int color) {
        boolean changed = !tid.equals(townId) || !tname.equals(townName) || color != secondaryColor;
        if (!changed) return;
        townId = tid; townName = tname; secondaryColor = color;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
}
