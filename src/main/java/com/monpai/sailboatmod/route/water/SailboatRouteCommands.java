package com.monpai.sailboatmod.route.water;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.monpai.sailboatmod.route.water.debug.RouteDebugServer;
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
                                        StringArgumentType.getString(ctx, "mode")))))
                .then(Commands.literal("routedebug")
                        .then(Commands.literal("start")
                                .executes(ctx -> startDebug(ctx.getSource(), 8899))
                                .then(Commands.argument("port", IntegerArgumentType.integer(1024, 65535))
                                        .executes(ctx -> startDebug(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "port")))))
                        .then(Commands.literal("stop")
                                .executes(ctx -> stopDebug(ctx.getSource())))
                        .then(Commands.literal("status")
                                .executes(ctx -> statusDebug(ctx.getSource())))));
    }

    private static int startDebug(CommandSourceStack source, int port) {
        try {
            RouteDebugServer.start(source.getServer(), "127.0.0.1", port);
            source.sendSuccess(() -> Component.literal(
                    "路由调试服务已启动：http://127.0.0.1:" + port + "/  （仅本机，调试用，记得 stop）"), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("启动失败：" + e.getMessage()));
            return 0;
        }
    }

    private static int stopDebug(CommandSourceStack source) {
        RouteDebugServer.stop();
        source.sendSuccess(() -> Component.literal("路由调试服务已停止"), true);
        return 1;
    }

    private static int statusDebug(CommandSourceStack source) {
        RouteDebugServer s = RouteDebugServer.get();
        boolean running = s != null && s.isRunning();
        int p = s == null ? -1 : s.port();
        source.sendSuccess(() -> Component.literal(
                running ? "运行中：http://127.0.0.1:" + p + "/" : "未运行（/sailboat routedebug start [port]）"), false);
        return 1;
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
