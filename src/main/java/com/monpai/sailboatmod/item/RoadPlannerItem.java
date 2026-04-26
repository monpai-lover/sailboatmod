package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenRoadPlannerScreenPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class RoadPlannerItem extends Item {
    public enum EntryAction {
        OPEN_NEW_PLANNER(true),
        SET_CURRENT_POSITION_DESTINATION(false);

        private final boolean opensPlanner;

        EntryAction(boolean opensPlanner) {
            this.opensPlanner = opensPlanner;
        }

        public boolean opensPlanner() {
            return opensPlanner;
        }
    }

    public RoadPlannerItem(Properties properties) {
        super(properties);
    }

    public static EntryAction entryAction(boolean sneaking) {
        return sneaking ? EntryAction.SET_CURRENT_POSITION_DESTINATION : EntryAction.OPEN_NEW_PLANNER;
    }

    public static boolean usesLegacyManualPlanner() {
        return false;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            EntryAction action = entryAction(player.isShiftKeyDown());
            if (action.opensPlanner()) {
                ModNetwork.CHANNEL.sendTo(
                        new OpenRoadPlannerScreenPacket(hand == InteractionHand.OFF_HAND, "", "", List.of()),
                        serverPlayer.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                );
            } else {
                serverPlayer.sendSystemMessage(Component.translatable("item.sailboatmod.road_planner.destination.current_position"));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.sailboatmod.road_planner.tip.use"));
        tooltip.add(Component.translatable("item.sailboatmod.road_planner.tip.select"));
        tooltip.add(Component.translatable("item.sailboatmod.road_planner.tip.confirm"));
        tooltip.add(Component.translatable("item.sailboatmod.road_planner.tip.mode"));
    }
}
