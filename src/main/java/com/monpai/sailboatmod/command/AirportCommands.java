package com.monpai.sailboatmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Function;

/** Explicit registration of existing player-built airports; never generates or loads terrain. */
public final class AirportCommands {
    private AirportCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var position = Commands.argument("pos", BlockPosArgument.blockPos())
                .then(Commands.literal("name").then(Commands.argument("name", StringArgumentType.greedyString()).executes(AirportCommands::rename)))
                .then(Commands.literal("list").executes(AirportCommands::list))
                .then(registrationNode())
                .then(Commands.literal("validate").then(Commands.argument("id", StringArgumentType.word()).executes(AirportCommands::validate)))
                .then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).executes(AirportCommands::remove)))
                .then(Commands.literal("route").then(Commands.argument("target", BlockPosArgument.blockPos())
                        .then(Commands.argument("cruiseY", IntegerArgumentType.integer()).executes(AirportCommands::route))))
                .then(Commands.literal("menu").executes(context -> { airport(context, "pos", true); return help(context.getSource()); }));
        dispatcher.register(Commands.literal("airport").executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource()))).then(position));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> registrationNode() {
        var height = Commands.argument("height", IntegerArgumentType.integer(4, 64)).executes(AirportCommands::registerFacility);
        var width = Commands.argument("width", IntegerArgumentType.integer(5, 32)).then(height);
        var end = Commands.argument("end", BlockPosArgument.blockPos()).then(width);
        var start = Commands.argument("start", BlockPosArgument.blockPos()).then(end);
        var type = Commands.argument("type", StringArgumentType.word()).suggests((context, builder) -> {
            for (AirportFacility.Type value : AirportFacility.Type.values()) builder.suggest(value.name().toLowerCase(Locale.ROOT));
            return builder.buildFuture();
        }).then(start);
        return Commands.literal("register").then(Commands.argument("id", StringArgumentType.word()).then(type));
    }
    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("机场指令（坐标只读取本维度已加载区块）：\n"
                + "/airport <x y z> name <名称> | list | validate <id> | remove <id>\n"
                + "/airport <x y z> register <id> <runway|helipad|mooring> <起点x y z> <终点x y z> <宽5..32> <净空高4..64>\n"
                + "端点Y是支撑面上方的空气格；跑道需水平且长至少32格。全部设施与进近范围须属机场同一受管辖城镇。\n"
                + "/airport <x y z> route <目标机场x y z> <巡航Y>：登记单向航线；返程需在另一机场登记。\n"
                + "机场继续使用市场/城镇仓库交付；menu显示本说明，设施管理使用上述指令。"), false);
        return 1;
    }
    private static AirportBlockEntity airport(CommandContext<CommandSourceStack> context, String argument, boolean manage) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, argument);
        if (!(context.getSource().getLevel().getBlockEntity(pos) instanceof AirportBlockEntity airport)) throw error("该坐标不是机场登记台。");
        if (manage) {
            ServerPlayer player = context.getSource().getPlayerOrException();
            var town = TownService.getManagedTownAt(player, pos);
            if (!airport.canManageDock(player) || town == null || !town.townId().equals(airport.getTownId())) throw error("需要机场所有权与所在城镇的管理权限。");
        }
        return airport;
    }
    private static int rename(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AirportBlockEntity airport = airport(context, "pos", true);
        String name = StringArgumentType.getString(context, "name").trim();
        if (name.isEmpty() || name.length() > 64) throw error("机场名称须为1至64个字符。");
        ensureUniqueName(context.getSource().getLevel(), airport, name);
        airport.setDockName(name);
        context.getSource().sendSuccess(() -> Component.literal("机场已命名为「" + name + "」。"), false);
        return 1;
    }
    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AirportBlockEntity airport = airport(context, "pos", true);
        context.getSource().sendSuccess(() -> Component.literal(airport.getDockName() + "：" + airport.facilities().size() + "个设施，" + airport.reservations().size() + "项占用预约。"), false);
        for (AirportFacility facility : airport.facilities()) context.getSource().sendSuccess(() -> Component.literal(facility.id() + " " + facility.type() + " "
                + facility.start().toShortString() + " → " + facility.end().toShortString() + " 宽=" + facility.width() + " 净空=" + facility.clearanceHeight() + "（登记几何；validate复查当前地形）"), false);
        return 1;
    }
    private static int registerFacility(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AirportBlockEntity airport = airport(context, "pos", true);
        AirportFacility facility;
        try {
            facility = new AirportFacility(StringArgumentType.getString(context, "id"), AirportFacility.Type.valueOf(StringArgumentType.getString(context, "type").toUpperCase(Locale.ROOT)),
                    BlockPosArgument.getLoadedBlockPos(context, "start"), BlockPosArgument.getLoadedBlockPos(context, "end"),
                    IntegerArgumentType.getInteger(context, "width"), IntegerArgumentType.getInteger(context, "height"));
        } catch (IllegalArgumentException failure) { throw error("设施id仅支持字母、数字、_、-，长度1..48；type使用runway、helipad或mooring。"); }
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!withinManagedTown(facility, airport.getTownId(), pos -> {
            var town = TownService.getManagedTownAt(player, pos); return town == null ? "" : town.townId();
        })) throw error("设施及两端进近范围必须全部处于机场同一受管辖城镇内。");
        AirportValidation.Result result = airport.registerFacility(facility);
        context.getSource().sendSuccess(() -> Component.literal("设施 " + facility.id() + "：" + describe(result)), false);
        return result == AirportValidation.Result.VALID ? 1 : 0;
    }
    private static int validate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AirportBlockEntity airport = airport(context, "pos", true);
        String id = StringArgumentType.getString(context, "id");
        AirportValidation.Result result = airport.validateFacility(id);
        context.getSource().sendSuccess(() -> Component.literal(id + "：" + describe(result)), false);
        return result == AirportValidation.Result.VALID ? 1 : 0;
    }
    private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AirportBlockEntity airport = airport(context, "pos", true);
        if (!airport.removeFacility(StringArgumentType.getString(context, "id"))) throw error("设施不存在或有尚未到期的占用预约。");
        context.getSource().sendSuccess(() -> Component.literal("设施登记已移除；玩家建筑保留。"), false); return 1;
    }
    private static int route(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AirportBlockEntity source = airport(context, "pos", true), target = airport(context, "target", false);
        ServerLevel level = context.getSource().getLevel();
        if (source == target || source.getDockName().equalsIgnoreCase(target.getDockName())) throw error("源和目的机场必须不同且名称不重复。先使用name命令设置唯一名称。");
        ensureUniqueName(level, source, source.getDockName()); ensureUniqueName(level, target, target.getDockName());
        AirportFacility start = null, end = null;
        for (AircraftClass type : AircraftClass.values()) {
            Optional<AirportFacility> from = source.facilities().stream().filter(value -> value.supports(type)).findFirst();
            Optional<AirportFacility> to = target.facilities().stream().filter(value -> value.supports(type)).findFirst();
            if (from.isPresent() && to.isPresent()) { start = from.get(); end = to.get(); break; }
        }
        if (start == null) throw error("两端机场均须登记设施，并至少支持一种相同的飞机类型。");
        int cruiseY = IntegerArgumentType.getInteger(context, "cruiseY");
        if (cruiseY < level.getMinBuildHeight() || cruiseY >= level.getMaxBuildHeight()
                || cruiseY < Math.max(start.start().getY(), end.start().getY()) + 8) throw error("巡航高度必须位于本世界建造高度内，并高于两端停机平面至少8格。");
        ServerPlayer player = context.getSource().getPlayerOrException();
        RouteDefinition route = airRoute(source.getDockName(), target.getDockName(), start.landingPosition(), end.landingPosition(), cruiseY, player.getName().getString(), player.getUUID().toString(), System.currentTimeMillis());
        source.setRoutes(replaceDestination(source.getRoutesForMap(), route), 0);
        context.getSource().sendSuccess(() -> Component.literal("已登记单向航线：" + route.name() + "。起降前仍会检查当前净空和设施占用。"), false); return 1;
    }
    private static void ensureUniqueName(ServerLevel level, AirportBlockEntity airport, String name) throws CommandSyntaxException {
        if (AirportSavedData.get(level).airports(level.dimension().location().toString()).stream().anyMatch(value -> !value.pos().equals(airport.getBlockPos()) && value.name().equalsIgnoreCase(name))) throw error("本维度已有同名机场，请使用唯一名称。");
    }
    public static boolean withinManagedTown(AirportFacility facility, String townId, Function<BlockPos, String> managedTown) {
        if (facility == null || !facility.validGeometry() || townId == null || townId.isBlank() || managedTown == null) return false;
        if (!townId.equals(managedTown.apply(facility.start())) || !townId.equals(managedTown.apply(facility.end()))) return false;
        var area = facility.clearanceBounds();
        int minX = (int) Math.floor(area.minX), maxX = (int) Math.ceil(area.maxX) - 1;
        int minZ = (int) Math.floor(area.minZ), maxZ = (int) Math.ceil(area.maxZ) - 1;
        for (int x : new int[] {minX, maxX}) for (int z : new int[] {minZ, maxZ})
            if (!townId.equals(managedTown.apply(new BlockPos(x, facility.start().getY(), z)))) return false;
        // Territory ownership is chunk based. Checking every covered chunk catches an enclave
        // between otherwise valid endpoints; bounding-box corners are intentionally conservative.
        for (int x = minX >> 4; x <= (maxX >> 4); x++) for (int z = minZ >> 4; z <= (maxZ >> 4); z++)
            if (!townId.equals(managedTown.apply(new BlockPos(x << 4, facility.start().getY(), z << 4)))) return false;
        return true;
    }
    public static RouteDefinition airRoute(String sourceName, String targetName, Vec3 source, Vec3 target, int cruiseY, String authorName, String authorUuid, long createdAt) {
        List<Vec3> points = List.of(source, new Vec3(source.x, cruiseY, source.z), new Vec3(target.x, cruiseY, target.z), target);
        double distance = 0; for (int i = 1; i < points.size(); i++) distance += points.get(i-1).distanceTo(points.get(i));
        return new RouteDefinition(sourceName + " → " + targetName, points, authorName, authorUuid, createdAt, distance, sourceName, targetName);
    }
    public static List<RouteDefinition> replaceDestination(List<RouteDefinition> existing, RouteDefinition route) {
        List<RouteDefinition> routes = new ArrayList<>(existing.stream().filter(value -> !value.endDockName().equalsIgnoreCase(route.endDockName())).toList()); routes.add(route); return List.copyOf(routes);
    }
    private static String describe(AirportValidation.Result result) {
        return switch (result) {
            case VALID -> "验证通过";
            case WAITING -> "正在分段检查，稍后重试";
            case UNLOADED -> "范围包含未加载区块或超出世界高度，未判定为可用";
            case OBSTRUCTED -> "净空受阻或设施已被预约";
            case UNSUPPORTED -> "跑道/停机坪地面或系留锚点缺少完整支撑";
            case TOO_LARGE -> "检查范围超过安全预算，请缩短设施或降低净空高度";
            case INVALID_GEOMETRY -> "设施不存在、几何无效、距机场超过512格或超过16设施上限";
        };
    }
    private static CommandSyntaxException error(String message) { return new SimpleCommandExceptionType(Component.literal(message)).create(); }
}
