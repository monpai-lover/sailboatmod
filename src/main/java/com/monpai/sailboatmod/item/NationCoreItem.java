package com.monpai.sailboatmod.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class NationCoreItem extends DescribedBlockItem {
    public NationCoreItem(Block block, Properties properties, String descriptionKey) {
        super(block, properties, descriptionKey);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        if (stack.hasTag() && stack.getTag().contains("RelocatingNationName")) {
            tooltip.add(Component.translatable("item.sailboatmod.core.relocating", stack.getTag().getString("RelocatingNationName")));
            tooltip.add(Component.translatable("item.sailboatmod.core.relocation_hint"));
        }
    }
}
