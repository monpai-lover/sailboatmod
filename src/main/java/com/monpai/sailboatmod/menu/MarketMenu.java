package com.monpai.sailboatmod.menu;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class MarketMenu extends AbstractContainerMenu {
    private final BlockPos marketPos;
    private final MarketBlockEntity market;

    public MarketMenu(int containerId, Inventory inventory, net.minecraft.network.FriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos());
    }

    public MarketMenu(int containerId, Inventory inventory, BlockPos marketPos) {
        super(ModMenus.MARKET_MENU.get(), containerId);
        this.marketPos = marketPos;
        this.market = inventory.player.level().getBlockEntity(marketPos) instanceof MarketBlockEntity be ? be : null;
    }

    @Override
    public boolean stillValid(Player player) {
        if (market == null || market.isRemoved()) {
            return false;
        }
        return player.distanceToSqr(marketPos.getX() + 0.5D, marketPos.getY() + 0.5D, marketPos.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    public BlockPos getMarketPos() {
        return marketPos;
    }
}
