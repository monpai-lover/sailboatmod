package com.monpai.sailboatmod.route.water;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /sailboat} 调试/配置指令。当前提供中段寻路地形真相源切换({@link WaterMidMode}):
 * <pre>
 *   /sailboat watermid                 查看当前模式
 *   /sailboat watermid noise           切到 NoiseChunk 纯精判(默认、快)
 *   /sailboat watermid realchunk       切到真实区块分批加载(运河算数、慢、带进度条)
 * </pre>
 */
public final class SailboatRouteCommands {
    private SailboatRouteCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sailboat")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("watermid")
                        .executes(ctx -> showMode(ctx.getSource()))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .executes(ctx -> setMode(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mode"))))));
    }

    private static int showMode(CommandSourceStack source) {
        WaterMidMode m = WaterMidMode.current();
        source.sendSuccess(() -> Component.literal("当前中段地形源:" + m.name().toLowerCase(java.util.Locale.ROOT)
                + " —— " + m.label()), false);
        source.sendSuccess(() -> Component.literal("切换默认:/sailboat watermid noise | nbt | hybrid(创建航线 UI 也可单独选)"), false);
        return 1;
    }

    private static int setMode(CommandSourceStack source, String arg) {
        WaterMidMode mode = WaterMidMode.parse(arg);
        if (mode == null) {
            source.sendFailure(Component.literal("无效模式「" + arg + "」。可选:noise | nbt | hybrid"));
            return 0;
        }
        WaterMidMode.set(mode);
        source.sendSuccess(() -> Component.literal("中段默认模式已切到:" + mode.name().toLowerCase(java.util.Locale.ROOT)
                + " —— " + mode.label() + "(下次创建航线生效)"), true);
        return 1;
    }
}
