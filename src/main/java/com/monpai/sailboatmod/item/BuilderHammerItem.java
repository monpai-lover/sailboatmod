package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.client.ConstructionGhostClientHooks;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.UseBuilderHammerPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class BuilderHammerItem extends Item {
    private static final double DEFAULT_REACH = 6.0D;

    public BuilderHammerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        ConstructionGhostClientHooks.HammerTarget target = ConstructionGhostClientHooks.pickTarget(player, DEFAULT_REACH);
        if (target == null) {
            return InteractionResultHolder.pass(stack);
        }

        ModNetwork.CHANNEL.sendToServer(new UseBuilderHammerPacket(target.kind(), target.jobId(), target.hitPos()));
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.sailboatmod.builder_hammer.tip.use"));
        tooltip.add(Component.translatable("item.sailboatmod.builder_hammer.tip.cost"));
    }
}
