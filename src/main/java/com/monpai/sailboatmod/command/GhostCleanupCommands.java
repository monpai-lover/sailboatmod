package com.monpai.sailboatmod.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.monpai.sailboatmod.block.DockBlock;
import com.monpai.sailboatmod.block.NationCoreBlock;
import com.monpai.sailboatmod.block.TownCoreBlock;
import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>幽灵数据清理</b>:崩服回档后,world 区块/方块回滚,但 mod 的 NBT SavedData(town/nation/market listing)
 * 可能比世界数据更新而残留 → 记录指向的 town core / nation core 方块、dock 在世界里已不存在 = 幽灵。
 *
 * <pre>
 *   /marketadmin cleanghosts                              全类别 DRY-RUN(只报告,不删)
 *   /marketadmin cleanghosts confirm                      全类别 真删
 *   /marketadmin cleanghosts towns|nations|listings [confirm]   单类别
 * </pre>
 *
 * <p><b>安全铁律</b>:判定前必先 {@code level.hasChunkAt(pos)} 门禁。回档后 core/dock 所在区块可能未加载,
 * 直接 getBlockState 会返回空气 → 把好记录误判幽灵删掉。<b>未加载一律 SKIPPED_UNLOADED,绝不删、绝不 force-load</b>
 * (force 加载会重蹈 forceChunk 卡服/崩服)。dry-run 是默认且判定层纯只读,只有显式 {@code confirm} 才真删。</p>
 */
public final class GhostCleanupCommands {
    private GhostCleanupCommands() {
    }

    /** 单条记录相对世界对象的判定结果。 */
    public enum Verdict {
        OK,                 // 世界对象存在 → 健康
        GHOST,             // 区块已加载且确认无对应方块 → 可删
        SKIPPED_UNLOADED,  // 区块未加载,无法验证 → 绝不删
        SKIPPED_DIM        // 维度解析失败 → 保守跳过
    }

    /**
     * <b>判定纯函数</b>(不依赖 MC 世界,便于单测)。<b>最重要保证:chunkLoaded=false 永远不返回 GHOST。</b>
     *
     * @param hasReference 记录是否声明了世界引用(town/nation 的 hasCore;listing 的 dockPos!=ZERO)。false→OK(不在职责内)
     * @param dimResolved  维度是否解析成功
     * @param chunkLoaded  目标坐标所在区块是否已加载
     * @param blockPresent 已加载时,目标方块是否就是期望的 core/dock 方块
     */
    public static Verdict classify(boolean hasReference, boolean dimResolved, boolean chunkLoaded, boolean blockPresent) {
        if (!hasReference) {
            return Verdict.OK; // 无世界引用的记录不在本命令职责内,绝不动
        }
        if (!dimResolved) {
            return Verdict.SKIPPED_DIM;
        }
        if (!chunkLoaded) {
            return Verdict.SKIPPED_UNLOADED; // 铁律:未加载绝不判幽灵
        }
        return blockPresent ? Verdict.OK : Verdict.GHOST;
    }

    // ============================ 命令树 ============================

    public static LiteralArgumentBuilder<CommandSourceStack> cleanGhostsNode() {
        return Commands.literal("cleanghosts")
                .executes(ctx -> run(ctx, Scope.ALL, false))
                .then(Commands.literal("confirm").executes(ctx -> run(ctx, Scope.ALL, true)))
                .then(scopeNode("towns", Scope.TOWNS))
                .then(scopeNode("nations", Scope.NATIONS))
                .then(scopeNode("listings", Scope.LISTINGS));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> scopeNode(String name, Scope scope) {
        return Commands.literal(name)
                .executes(ctx -> run(ctx, scope, false))
                .then(Commands.literal("confirm").executes(ctx -> run(ctx, scope, true)));
    }

    private enum Scope { ALL, TOWNS, NATIONS, LISTINGS }

    // ============================ 执行 ============================

    private static int run(CommandContext<CommandSourceStack> ctx, Scope scope, boolean apply) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel anyLevel = source.getLevel();
        NationSavedData nationData = NationSavedData.get(anyLevel);
        MarketSavedData marketData = MarketSavedData.get(anyLevel);

        List<Component> lines = new ArrayList<>();
        int ghostTotal = 0;

        source.sendSuccess(() -> Component.translatable(
                apply ? "command.sailboatmod.marketadmin.cleanghosts.header_apply"
                        : "command.sailboatmod.marketadmin.cleanghosts.header_dryrun"), false);

        if (scope == Scope.ALL || scope == Scope.TOWNS) {
            ghostTotal += cleanTowns(server, nationData, apply, lines);
        }
        if (scope == Scope.ALL || scope == Scope.NATIONS) {
            ghostTotal += cleanNations(server, nationData, apply, lines);
        }
        if (scope == Scope.ALL || scope == Scope.LISTINGS) {
            ghostTotal += cleanListings(server, marketData, apply, lines);
        }

        int shown = 0;
        for (Component line : lines) {
            if (shown++ >= 50) {
                int remaining = lines.size() - 50;
                source.sendSuccess(() -> Component.translatable(
                        "command.sailboatmod.marketadmin.cleanghosts.more", remaining), false);
                break;
            }
            source.sendSuccess(() -> line, false);
        }

        int finalGhostTotal = ghostTotal;
        source.sendSuccess(() -> Component.translatable(
                apply ? "command.sailboatmod.marketadmin.cleanghosts.done_apply"
                        : "command.sailboatmod.marketadmin.cleanghosts.done_dryrun",
                finalGhostTotal), true);
        return ghostTotal;
    }

    // ---- town ----

    private static int cleanTowns(MinecraftServer server, NationSavedData data, boolean apply, List<Component> lines) {
        List<String> toRemove = new ArrayList<>();
        int unloaded = 0;
        for (TownRecord town : data.getAllTowns()) {
            Verdict v = classifyTown(server, town);
            if (v == Verdict.GHOST) {
                lines.add(Component.translatable("command.sailboatmod.marketadmin.cleanghosts.ghost_town",
                        town.name(), town.townId()));
                toRemove.add(town.townId());
            } else if (v == Verdict.SKIPPED_UNLOADED) {
                unloaded++;
            }
        }
        if (unloaded > 0) {
            int u = unloaded;
            lines.add(Component.translatable("command.sailboatmod.marketadmin.cleanghosts.skipped_unloaded", "town", u));
        }
        if (apply) {
            for (String id : toRemove) {
                data.removeTown(id);
            }
        }
        return toRemove.size();
    }

    static Verdict classifyTown(MinecraftServer server, TownRecord town) {
        boolean hasRef = town.hasCore();
        ServerLevel level = hasRef ? resolveDimension(server, town.coreDimension()) : null;
        boolean dimOk = level != null;
        BlockPos pos = hasRef ? BlockPos.of(town.corePos()) : BlockPos.ZERO;
        boolean loaded = dimOk && level.hasChunkAt(pos);
        boolean present = loaded && level.getBlockState(pos).getBlock() instanceof TownCoreBlock;
        return classify(hasRef, dimOk, loaded, present);
    }

    // ---- nation ----

    private static int cleanNations(MinecraftServer server, NationSavedData data, boolean apply, List<Component> lines) {
        List<String> toRemove = new ArrayList<>();
        int unloaded = 0;
        for (NationRecord nation : data.getNations()) {
            Verdict v = classifyNation(server, nation);
            if (v == Verdict.GHOST) {
                int townCount = data.getTownsForNation(nation.nationId()).size();
                lines.add(Component.translatable("command.sailboatmod.marketadmin.cleanghosts.ghost_nation",
                        nation.name(), nation.nationId(), townCount));
                toRemove.add(nation.nationId());
            } else if (v == Verdict.SKIPPED_UNLOADED) {
                unloaded++;
            }
        }
        if (unloaded > 0) {
            int u = unloaded;
            lines.add(Component.translatable("command.sailboatmod.marketadmin.cleanghosts.skipped_unloaded", "nation", u));
        }
        if (apply) {
            for (String id : toRemove) {
                data.removeNation(id); // 级联删该国 town/member/claim/war/flag/外交/treasury
            }
        }
        return toRemove.size();
    }

    static Verdict classifyNation(MinecraftServer server, NationRecord nation) {
        boolean hasRef = nation.hasCore();
        ServerLevel level = hasRef ? resolveDimension(server, nation.coreDimension()) : null;
        boolean dimOk = level != null;
        BlockPos pos = hasRef ? BlockPos.of(nation.corePos()) : BlockPos.ZERO;
        boolean loaded = dimOk && level.hasChunkAt(pos);
        boolean present = loaded && level.getBlockState(pos).getBlock() instanceof NationCoreBlock;
        return classify(hasRef, dimOk, loaded, present);
    }

    // ---- market listing ----

    private static int cleanListings(MinecraftServer server, MarketSavedData data, boolean apply, List<Component> lines) {
        List<String> toRemove = new ArrayList<>();
        int unloaded = 0;
        for (MarketListing listing : data.getAllListingsRaw()) {
            BlockPos dockPos = listing.sourceDockPos();
            if (dockPos == null || dockPos.equals(BlockPos.ZERO)) {
                continue; // 无 dock 哨兵,不在职责内
            }
            ListingVerdict lv = classifyListing(server, dockPos);
            if (lv == ListingVerdict.GHOST) {
                int orders = data.getActiveOrdersForListing(listing.listingId()).size();
                lines.add(Component.translatable("command.sailboatmod.marketadmin.cleanghosts.ghost_listing",
                        listing.listingId(), orders));
                toRemove.add(listing.listingId());
            } else if (lv == ListingVerdict.SKIPPED_UNLOADED) {
                unloaded++;
            }
        }
        if (unloaded > 0) {
            int u = unloaded;
            lines.add(Component.translatable("command.sailboatmod.marketadmin.cleanghosts.skipped_unloaded", "listing", u));
        }
        if (apply) {
            for (String id : toRemove) {
                data.removeListing(id);
            }
        }
        return toRemove.size();
    }

    private enum ListingVerdict { OK, GHOST, SKIPPED_UNLOADED }

    /**
     * listing 无维度字段,全维度扫描 dock。<b>宁漏删不误删</b>:存在已加载区块但都无 dock→GHOST;
     * 任一坐标处区块都未加载→SKIPPED_UNLOADED。DockRegistry 只反映已加载 dock,故叠加 hasChunkAt+instanceof 复核。
     */
    private static ListingVerdict classifyListing(MinecraftServer server, BlockPos dockPos) {
        boolean anyChunkLoaded = false;
        for (ServerLevel level : server.getAllLevels()) {
            if (DockRegistry.get(level).contains(dockPos)) {
                return ListingVerdict.OK; // 已注册 dock(快路径)
            }
            if (level.hasChunkAt(dockPos)) {
                anyChunkLoaded = true;
                if (level.getBlockState(dockPos).getBlock() instanceof DockBlock) {
                    return ListingVerdict.OK;
                }
            }
        }
        return anyChunkLoaded ? ListingVerdict.GHOST : ListingVerdict.SKIPPED_UNLOADED;
    }

    // ---- helper(照搬 NationService.resolveDimension) ----

    private static ServerLevel resolveDimension(MinecraftServer server, String dimensionId) {
        if (server == null || dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimensionId);
        if (dimLoc == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, dimLoc));
    }
}
