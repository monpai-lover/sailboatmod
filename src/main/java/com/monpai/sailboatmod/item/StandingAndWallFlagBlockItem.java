package com.monpai.sailboatmod.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class StandingAndWallFlagBlockItem extends StandingAndWallBlockItem {
    private final String descriptionKey;

    public StandingAndWallFlagBlockItem(Block standingBlock, Block wallBlock, Properties properties, String descriptionKey) {
        super(standingBlock, wallBlock, properties, net.minecraft.core.Direction.DOWN);
        this.descriptionKey = descriptionKey == null ? "" : descriptionKey.trim();
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        if (!descriptionKey.isBlank()) {
            tooltip.add(Component.translatable(descriptionKey));
        }
    }
}
