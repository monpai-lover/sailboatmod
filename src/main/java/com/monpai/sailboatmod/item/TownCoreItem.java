package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.compat.ItemStackNbt;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class TownCoreItem extends DescribedBlockItem {
    public TownCoreItem(Block block, Properties properties, String descriptionKey) {
        super(block, properties, descriptionKey);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        if (ItemStackNbt.hasTag(stack) && ItemStackNbt.tag(stack).contains("RelocatingTownName")) {
            tooltip.add(Component.translatable("item.sailboatmod.core.relocating", ItemStackNbt.tag(stack).getString("RelocatingTownName")));
            tooltip.add(Component.translatable("item.sailboatmod.core.relocation_hint"));
        }
    }
}
