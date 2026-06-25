package com.monpai.sailboatmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /marketadmin} OP 管理员指令。当前提供:
 * <pre>
 *   /marketadmin infinitestock   切换玩家正对的市场终端为「无限库存系统商店」(toggle)
 * </pre>
 * 无限库存终端:上架不扣货、库存无限不减,其它(购买/发货/价格/税收/UI)与普通终端一致。
 */
public final class MarketAdminCommands {
    private static final double REACH = 6.0D;

    private MarketAdminCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("marketadmin")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("infinitestock")
                        .executes(MarketAdminCommands::toggleInfiniteStock))
                .then(GhostCleanupCommands.cleanGhostsNode()));
    }

    private static int toggleInfiniteStock(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.translatable("command.sailboatmod.marketadmin.infinitestock.no_terminal"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 reach = eye.add(player.getViewVector(1.0F).scale(REACH));
        BlockHitResult hit = level.clip(new ClipContext(
                eye, reach, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.translatable("command.sailboatmod.marketadmin.infinitestock.no_terminal"));
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        if (!(level.getBlockEntity(pos) instanceof MarketBlockEntity market)) {
            source.sendFailure(Component.translatable("command.sailboatmod.marketadmin.infinitestock.no_terminal"));
            return 0;
        }
        boolean now = market.toggleInfiniteStock();
        source.sendSuccess(() -> Component.translatable(now
                ? "command.sailboatmod.marketadmin.infinitestock.enabled"
                : "command.sailboatmod.marketadmin.infinitestock.disabled"), true);
        return 1;
    }
}
