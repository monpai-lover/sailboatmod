package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.util.ClientSideExecutor;
import com.mojang.logging.LogUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;

/** A public route map entry point; opening it grants no construction or dispatch authority. */
public final class RouteAtlasItem extends Item {
    public RouteAtlasItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) openClient();
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        if (context.getLevel().isClientSide) openClient();
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    private static void openClient() {
        ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class.forName("com.monpai.sailboatmod.client.RouteAtlasClientHooks").getMethod("open").invoke(null);
            } catch (ReflectiveOperationException | LinkageError failure) {
                LogUtils.getLogger().warn("Unable to open the route atlas", failure);
            }
        });
    }
}
