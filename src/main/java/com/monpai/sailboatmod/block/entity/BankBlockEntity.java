package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.menu.BankMenu;
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
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class BankBlockEntity extends BlockEntity implements MenuProvider {
    private static final int REFRESH_INTERVAL_TICKS = 20;

    private String townId = "";
    private String townName = "";
    private String nationId = "";
    private int secondaryColor = 0xD8B35A;

    public BankBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BANK_BLOCK_ENTITY.get(), pos, state);
    }

    public String getTownId() { return townId; }
    public String getTownName() { return townName == null || townName.isBlank() ? "" : townName; }
    public String getNationId() { return nationId; }
    public int getSecondaryColor() { return secondaryColor & 0x00FFFFFF; }

    @Override
    public Component getDisplayName() {
        if (townName == null || townName.isBlank()) {
            return Component.translatable("block.sailboatmod.bank_block");
        }
        return Component.literal("[" + townName + "] ")
                .append(Component.translatable("block.sailboatmod.bank_block"));
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new BankMenu(containerId, inventory, worldPosition);
    }

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
        TownRecord town = TownService.getTownAt(level, worldPosition);
        if (town == null) {
            updateState("", "", "", 0xD8B35A);
            return;
        }
        NationSavedData data = NationSavedData.get(level);
        NationRecord nation = town.nationId().isBlank() ? null : data.getNation(town.nationId());
        updateState(
                town.townId(),
                town.name(),
                town.nationId(),
                nation == null ? 0xD8B35A : nation.secondaryColorRgb()
        );
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BankBlockEntity blockEntity) {
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
        tag.putString("NationId", nationId == null ? "" : nationId);
        tag.putInt("SecondaryColor", secondaryColor);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        townId = tag.getString("TownId");
        townName = tag.getString("TownName");
        nationId = tag.getString("NationId");
        secondaryColor = tag.contains("SecondaryColor") ? tag.getInt("SecondaryColor") : 0xD8B35A;
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

    private void updateState(String nextTownId, String nextTownName, String nextNationId, int nextSecondaryColor) {
        String safeTownId = nextTownId == null ? "" : nextTownId.trim();
        String safeTownName = nextTownName == null ? "" : nextTownName;
        String safeNationId = nextNationId == null ? "" : nextNationId.trim();
        int safeColor = nextSecondaryColor & 0x00FFFFFF;
        boolean changed = !safeTownId.equals(townId)
                || !safeTownName.equals(townName)
                || !safeNationId.equals(nationId)
                || safeColor != secondaryColor;
        if (!changed) {
            return;
        }
        townId = safeTownId;
        townName = safeTownName;
        nationId = safeNationId;
        secondaryColor = safeColor;
        setChanged();
        if (level != null) {
            BlockState st = getBlockState();
            level.sendBlockUpdated(worldPosition, st, st, 3);
        }
    }
}
