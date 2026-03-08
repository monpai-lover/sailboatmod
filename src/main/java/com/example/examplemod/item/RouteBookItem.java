package com.example.examplemod.item;

import com.example.examplemod.block.entity.DockBlockEntity;
import com.example.examplemod.entity.SailboatEntity;
import com.example.examplemod.route.RouteDefinition;
import com.example.examplemod.route.RouteNbtUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RouteBookItem extends Item {
    private static final String TAG_ROUTES = "Routes";
    private static final String TAG_ACTIVE_ROUTE = "ActiveRoute";
    private static final int MAX_ROUTES = 24;
    private static final int MAX_WAYPOINTS_PER_ROUTE = 128;
    private static final int MIN_ROUTE_POINTS = 2;

    public RouteBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();
        if (hasFinalizedRoute(stack)) {
            if (!context.getLevel().isClientSide) {
                player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.locked"));
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        ensureAtLeastOneRoute(stack);
        if (player.isShiftKeyDown()) {
            if (context.getLevel().isClientSide) {
                openNamingScreen(context.getHand(), getSuggestedActiveRouteName(stack));
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        int count = addWaypointFromPlayerXZ(stack, player);
        if (!context.getLevel().isClientSide) {
            if (count == -3) {
                player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.start_need_dock"));
            } else if (count == -2) {
                player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.need_water"));
            } else if (count < 0) {
                player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.full", MAX_WAYPOINTS_PER_ROUTE));
            } else {
                int x = Mth.floor(player.getX());
                int z = Mth.floor(player.getZ());
                if (count == 1) {
                    player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.record_start", x, z));
                }
                player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.point_added", count, x, z));
            }
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hasFinalizedRoute(stack)) {
            if (!level.isClientSide) {
                player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.locked"));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        ensureAtLeastOneRoute(stack);
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                openNamingScreen(hand, getSuggestedActiveRouteName(stack));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (!level.isClientSide) {
            int count = addWaypointFromPlayerXZ(stack, player);
            if (count == -3) {
                player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.start_need_dock"));
            } else if (count == -2) {
                player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.need_water"));
            } else if (count < 0) {
                player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.full", MAX_WAYPOINTS_PER_ROUTE));
            } else {
                int x = Mth.floor(player.getX());
                int z = Mth.floor(player.getZ());
                if (count == 1) {
                    player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.record_start", x, z));
                }
                player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.point_added", count, x, z));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    public InteractionResult useOnSailboat(Player player, InteractionHand hand, SailboatEntity sailboat) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (!player.level().isClientSide) {
                sailboat.stopAutopilot();
                player.displayClientMessage(Component.translatable("item.sailboatmod.route_book.stop"), true);
            }
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }

        List<RouteDefinition> routes = getRoutes(stack);
        if (routes.isEmpty()) {
            if (!player.level().isClientSide) {
                player.displayClientMessage(Component.translatable("item.sailboatmod.route_book.too_short", MIN_ROUTE_POINTS), true);
            }
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }

        int activeRoute = getActiveRouteIndex(stack);
        if (!player.level().isClientSide) {
            sailboat.setRouteCatalog(routes, activeRoute, null);
            sailboat.setPendingShipper(player.getName().getString());
            boolean started = sailboat.startAutopilotFromRouteStart();
            if (started) {
                player.displayClientMessage(Component.translatable("item.sailboatmod.route_book.start", sailboat.getSelectedRouteName(), sailboat.getRouteCount()), true);
            } else {
                player.displayClientMessage(Component.translatable("screen.sailboatmod.route_start_need_zone"), true);
            }
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    public static boolean finalizeActiveRoute(Player player, InteractionHand hand, String routeName) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof RouteBookItem routeBookItem)) {
            return false;
        }
        return routeBookItem.finalizeCurrentRoute(stack, player, routeName);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("item.sailboatmod.route_book.tip.record"));
        tooltip.add(Component.translatable("item.sailboatmod.route_book.tip.finish"));
        tooltip.add(Component.translatable("item.sailboatmod.route_book.tip.apply"));
        tooltip.add(Component.translatable("item.sailboatmod.route_book.tip.stop"));
        if (hasFinalizedRoute(stack)) {
            tooltip.add(Component.translatable("item.sailboatmod.route_book.locked"));
        }

        List<RouteDefinition> routes = RouteNbtUtil.readRoutes(stack.getOrCreateTag(), TAG_ROUTES);
        int activeIndex = getActiveRouteIndex(stack);
        tooltip.add(Component.translatable("item.sailboatmod.route_book.active", activeIndex + 1, Math.max(routes.size(), 1)));

        int shown = 0;
        for (int i = 0; i < routes.size(); i++) {
            RouteDefinition route = routes.get(i);
            if (route.waypoints().size() < MIN_ROUTE_POINTS) {
                continue;
            }
            shown++;
            if (shown > 4) {
                tooltip.add(Component.translatable("item.sailboatmod.route_book.more", routes.size() - 4));
                break;
            }
            String routeName = route.name().isBlank() ? nextRouteName(i) : route.name();
            String author = route.authorName().isBlank() ? "-" : route.authorName();
            String time = route.createdAtEpochMillis() <= 0L ? "-" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(route.createdAtEpochMillis()));
            tooltip.add(Component.translatable("item.sailboatmod.route_book.info.name", routeName));
            tooltip.add(Component.translatable("item.sailboatmod.route_book.info.meta", formatLength(route.routeLengthMeters()), author, time));
        }
    }

    public static List<RouteDefinition> getRoutes(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return List.of();
        }
        List<RouteDefinition> raw = RouteNbtUtil.readRoutes(tag, TAG_ROUTES);
        List<RouteDefinition> sanitized = new ArrayList<>();
        for (RouteDefinition route : raw) {
            if (route.waypoints().size() >= MIN_ROUTE_POINTS) {
                sanitized.add(route.copy());
            }
        }
        return sanitized;
    }

    public static int getActiveRouteIndex(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return 0;
        }
        int count = Math.max(1, RouteNbtUtil.readRoutes(tag, TAG_ROUTES).size());
        return Mth.clamp(tag.getInt(TAG_ACTIVE_ROUTE), 0, count - 1);
    }

    private int addWaypointFromPlayerXZ(ItemStack stack, Player player) {
        double recordX = player.getX();
        double recordZ = player.getZ();
        boolean onWater;
        if (player.getVehicle() instanceof SailboatEntity sailboat) {
            recordX = sailboat.getX();
            recordZ = sailboat.getZ();
            if (sailboat.isInWater()) {
                onWater = true;
            } else {
                BlockPos boatBelow = BlockPos.containing(recordX, sailboat.getY() - 0.2D, recordZ);
                onWater = player.level().getFluidState(boatBelow).is(net.minecraft.tags.FluidTags.WATER);
            }
        } else {
            BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.2D, player.getZ());
            onWater = player.level().getFluidState(below).is(net.minecraft.tags.FluidTags.WATER);
        }

        List<RouteDefinition> routes = new ArrayList<>(RouteNbtUtil.readRoutes(stack.getOrCreateTag(), TAG_ROUTES));
        int active = getActiveRouteIndex(stack);
        RouteDefinition route = routes.get(active);
        List<Vec3> points = new ArrayList<>(route.waypoints());
        if (points.isEmpty()) {
            Vec3 startPoint = new Vec3(recordX, 0.0D, recordZ);
            if (DockBlockEntity.findDockZoneContains(player.level(), startPoint) == null) {
                return -3;
            }
        }
        if (!onWater) {
            return -2;
        }
        if (points.size() >= MAX_WAYPOINTS_PER_ROUTE) {
            return -1;
        }
        points.add(new Vec3(recordX, 0.0D, recordZ));
        routes.set(active, new RouteDefinition(route.name(), points, route.authorName(), route.authorUuid(), route.createdAtEpochMillis(), route.routeLengthMeters()));
        RouteNbtUtil.writeRoutes(stack.getOrCreateTag(), TAG_ROUTES, routes);
        return points.size();
    }

    private int getRouteCount(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return 0;
        }
        return RouteNbtUtil.readRoutes(tag, TAG_ROUTES).size();
    }

    private boolean finalizeCurrentRoute(ItemStack stack, Player player, String name) {
        ensureAtLeastOneRoute(stack);
        CompoundTag tag = stack.getOrCreateTag();
        List<RouteDefinition> routes = new ArrayList<>(RouteNbtUtil.readRoutes(tag, TAG_ROUTES));
        int active = getActiveRouteIndex(stack);
        RouteDefinition route = routes.get(active);
        if (route.waypoints().size() < MIN_ROUTE_POINTS) {
            return false;
        }
        double length = computeRouteLength(route.waypoints());
        Vec3 first = route.waypoints().get(0);
        Vec3 last = route.waypoints().get(route.waypoints().size() - 1);
        BlockPos startDock = DockBlockEntity.findDockZoneContains(player.level(), first);
        BlockPos endDock = DockBlockEntity.findDockZoneContains(player.level(), last);
        if (startDock == null || endDock == null || startDock.equals(endDock)) {
            return false;
        }
        String startName = DockBlockEntity.getDockDisplayName(player.level(), startDock);
        String endName = DockBlockEntity.getDockDisplayName(player.level(), endDock);
        String suffix = name == null ? "" : name.trim();
        String finalName = startName + "-" + endName + "路线";
        if (!suffix.isBlank()) {
            finalName = finalName + "-\"" + suffix + "\"";
        }
        RouteDefinition finalized = new RouteDefinition(
                finalName,
                route.waypoints(),
                player.getName().getString(),
                player.getUUID().toString(),
                System.currentTimeMillis(),
                length,
                startName,
                endName
        );
        routes.set(active, finalized);
        RouteNbtUtil.writeRoutes(tag, TAG_ROUTES, routes);
        player.sendSystemMessage(Component.translatable("item.sailboatmod.route_book.finish", finalName, formatLength(length)));
        return true;
    }

    private double computeRouteLength(List<Vec3> points) {
        double total = 0.0D;
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            total += Math.sqrt(dx * dx + dz * dz);
        }
        return total;
    }

    private String getSuggestedActiveRouteName(ItemStack stack) {
        return "";
    }

    private void ensureAtLeastOneRoute(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        List<RouteDefinition> routes = RouteNbtUtil.readRoutes(tag, TAG_ROUTES);
        if (routes.isEmpty()) {
            routes = new ArrayList<>();
            routes.add(new RouteDefinition(nextRouteName(0), List.of()));
            tag.putInt(TAG_ACTIVE_ROUTE, 0);
            RouteNbtUtil.writeRoutes(tag, TAG_ROUTES, routes);
        }
    }

    private boolean hasFinalizedRoute(ItemStack stack) {
        List<RouteDefinition> routes = RouteNbtUtil.readRoutes(stack.getOrCreateTag(), TAG_ROUTES);
        for (RouteDefinition route : routes) {
            if (route.waypoints().size() >= MIN_ROUTE_POINTS && route.createdAtEpochMillis() > 0L) {
                return true;
            }
        }
        return false;
    }

    private static String nextRouteName(int index) {
        return "Route-" + (index + 1);
    }

    private static String formatLength(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private void openNamingScreen(InteractionHand hand, String suggestedName) {
        try {
            Class<?> hooks = Class.forName("com.example.examplemod.client.RouteBookClientHooks");
            hooks.getMethod("openNamingScreen", InteractionHand.class, String.class).invoke(null, hand, suggestedName);
        } catch (Exception ignored) {
        }
    }
}
