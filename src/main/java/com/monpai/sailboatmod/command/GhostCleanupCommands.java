package com.monpai.sailboatmod.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.monpai.sailboatmod.block.DockBlock;
import com.monpai.sailboatmod.block.MarketBlock;
import com.monpai.sailboatmod.block.NationCoreBlock;
import com.monpai.sailboatmod.block.TownCoreBlock;
import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.registry.ModBlocks;
import com.monpai.sailboatmod.util.OfflineChunkNbtReader;
import com.monpai.sailboatmod.util.OfflineChunkPalette;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        GHOST,             // 已确认(内存或磁盘)无对应方块 → 可删
        SKIPPED_UNLOADED,  // 区块未加载且磁盘也读不到,无法验证 → 绝不删
        SKIPPED_DIM        // 维度解析失败 → 保守跳过
    }

    /**
     * 目标坐标处方块的<b>三态</b>探测结果。区分"确认不在"和"读不到无法验证"是关键——
     * 前者才是幽灵,后者绝不删。
     */
    public enum BlockProbe {
        PRESENT,       // 确认是期望的 core/dock 方块
        ABSENT,        // 确认不是(内存已加载读到别的,或磁盘 NBT 已生成但该格不是)→ 幽灵
        UNVERIFIABLE   // 区块未加载且磁盘也读不到(从未生成)→ 无法验证
    }

    /**
     * <b>判定纯函数</b>(不依赖 MC 世界,便于单测)。<b>最重要保证:probe==UNVERIFIABLE 永远不返回 GHOST。</b>
     *
     * @param hasReference 记录是否声明了世界引用(town/nation 的 hasCore;listing 的 dockPos!=ZERO)。false→OK(不在职责内)
     * @param dimResolved  维度是否解析成功
     * @param probe        目标方块三态探测结果(内存→离线磁盘→读不到)
     */
    public static Verdict classify(boolean hasReference, boolean dimResolved, BlockProbe probe) {
        if (!hasReference) {
            return Verdict.OK; // 无世界引用的记录不在本命令职责内,绝不动
        }
        if (!dimResolved) {
            return Verdict.SKIPPED_DIM;
        }
        switch (probe) {
            case PRESENT:
                return Verdict.OK;
            case ABSENT:
                return Verdict.GHOST;   // 已确认不在(内存或磁盘) = 幽灵
            case UNVERIFIABLE:
            default:
                return Verdict.SKIPPED_UNLOADED; // 铁律:读不到绝不判幽灵
        }
    }

    // ============================ 命令树 ============================

    public static LiteralArgumentBuilder<CommandSourceStack> cleanGhostsNode() {
        return Commands.literal("cleanghosts")
                .executes(ctx -> run(ctx, Scope.ALL, false))
                .then(Commands.literal("confirm").executes(ctx -> run(ctx, Scope.ALL, true)))
                .then(scopeNode("towns", Scope.TOWNS))
                .then(scopeNode("nations", Scope.NATIONS))
                .then(scopeNode("listings", Scope.LISTINGS))
                .then(scopeNode("terminals", Scope.TERMINALS));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> scopeNode(String name, Scope scope) {
        return Commands.literal(name)
                .executes(ctx -> run(ctx, scope, false))
                .then(Commands.literal("confirm").executes(ctx -> run(ctx, scope, true)));
    }

    private enum Scope { ALL, TOWNS, NATIONS, LISTINGS, TERMINALS }

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
        if (scope == Scope.ALL || scope == Scope.TERMINALS) {
            ghostTotal += cleanTerminals(server, MarketTerminalSavedData.get(anyLevel), apply, lines);
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
        if (!hasRef || !dimOk) {
            return classify(hasRef, dimOk, BlockProbe.UNVERIFIABLE);
        }
        BlockProbe probe = probeBlock(level, BlockPos.of(town.corePos()), ModBlocks.TOWN_CORE_BLOCK.get(), TownCoreBlock.class);
        return classify(true, true, probe);
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
        if (!hasRef || !dimOk) {
            return classify(hasRef, dimOk, BlockProbe.UNVERIFIABLE);
        }
        BlockProbe probe = probeBlock(level, BlockPos.of(nation.corePos()), ModBlocks.NATION_CORE_BLOCK.get(), NationCoreBlock.class);
        return classify(true, true, probe);
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

    // ---- market terminal(地图标记来源:MarketTerminalSavedData,持久化,回档后残留=幽灵)----

    private static int cleanTerminals(MinecraftServer server, MarketTerminalSavedData data, boolean apply, List<Component> lines) {
        List<MarketTerminalSavedData.MarketTerminalEntry> toRemove = new ArrayList<>();
        int unloaded = 0;
        for (MarketTerminalSavedData.MarketTerminalEntry entry : data.entries()) {
            ServerLevel level = resolveDimension(server, entry.dimensionId());
            if (level == null) {
                continue; // 维度解析失败:保守跳过
            }
            BlockProbe probe = probeBlock(level, entry.marketPos(), ModBlocks.MARKET_BLOCK.get(), MarketBlock.class);
            if (probe == BlockProbe.ABSENT) {
                lines.add(Component.translatable("command.sailboatmod.marketadmin.cleanghosts.ghost_terminal",
                        entry.marketName().isBlank() ? entry.marketPos().toShortString() : entry.marketName(),
                        entry.marketPos().toShortString()));
                toRemove.add(entry);
            } else if (probe == BlockProbe.UNVERIFIABLE) {
                unloaded++;
            }
        }
        if (unloaded > 0) {
            int u = unloaded;
            lines.add(Component.translatable("command.sailboatmod.marketadmin.cleanghosts.skipped_unloaded", "terminal", u));
        }
        if (apply) {
            for (MarketTerminalSavedData.MarketTerminalEntry e : toRemove) {
                data.removeEntry(e.dimensionId(), e.marketPos());
            }
        }
        return toRemove.size();
    }

    private enum ListingVerdict { OK, GHOST, SKIPPED_UNLOADED }

    /**
     * listing 无维度字段,全维度扫描 dock。每个维度三态探测(内存→离线磁盘);<b>宁漏删不误删</b>:
     * 任一维度确认有 dock→OK;某维度确认无(已加载或磁盘已生成)记为 ABSENT;全维度都读不到→SKIPPED_UNLOADED。
     * DockRegistry 是已加载 dock 快路径。
     */
    private static ListingVerdict classifyListing(MinecraftServer server, BlockPos dockPos) {
        boolean anyAbsent = false;
        for (ServerLevel level : server.getAllLevels()) {
            if (DockRegistry.get(level).contains(dockPos)) {
                return ListingVerdict.OK; // 已注册 dock(快路径)
            }
            BlockProbe probe = probeBlock(level, dockPos, ModBlocks.DOCK_BLOCK.get(), DockBlock.class);
            if (probe == BlockProbe.PRESENT) {
                return ListingVerdict.OK;
            }
            if (probe == BlockProbe.ABSENT) {
                anyAbsent = true; // 该维度确认无 dock;继续看别的维度有没有
            }
        }
        return anyAbsent ? ListingVerdict.GHOST : ListingVerdict.SKIPPED_UNLOADED;
    }

    // ---- 核心:三态方块探测(内存 → 离线磁盘 NBT → 读不到) ----

    /**
     * 探测 {@code pos} 处是否是期望方块。<b>不 force 加载区块</b>:
     * <ol>
     *   <li>区块已加载 → 内存 {@code getBlockState}(最准),instanceof 命中=PRESENT,否则 ABSENT。</li>
     *   <li>未加载 → {@link OfflineChunkNbtReader#readSafeOnMainThread} 离线读磁盘 NBT(不生成),
     *       {@link OfflineChunkPalette} 解出该坐标 blockId:命中 expectedId=PRESENT,full 但不是=ABSENT。</li>
     *   <li>磁盘也读不到(从未生成/读失败)→ UNVERIFIABLE(绝不删)。</li>
     * </ol>
     * 只主世界等可解析维度;离线读纯磁盘只读,安全。
     */
    static BlockProbe probeBlock(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block expected,
                                 Class<? extends net.minecraft.world.level.block.Block> expectedClass) {
        // 1) 已加载:内存判最准。
        if (level.hasChunkAt(pos)) {
            return expectedClass.isInstance(level.getBlockState(pos).getBlock())
                    ? BlockProbe.PRESENT : BlockProbe.ABSENT;
        }
        // 2) 未加载:离线读磁盘 NBT(不 force 生成)。
        ChunkPos chunkPos = new ChunkPos(pos);
        Optional<CompoundTag> tag = OfflineChunkNbtReader.readSafeOnMainThread(level, chunkPos, 2000L, false);
        if (tag.isEmpty()
                || OfflineChunkNbtReader.classify(tag) != OfflineChunkNbtReader.ChunkStatusClass.FULL) {
            return BlockProbe.UNVERIFIABLE; // 磁盘没有/非 full → 无法验证
        }
        try {
            OfflineChunkPalette.OfflineChunkBlocks blocks =
                    OfflineChunkPalette.decode(tag.get(), level.getMinBuildHeight(), level.getMaxBuildHeight());
            if (blocks.isEmpty()) {
                return BlockProbe.UNVERIFIABLE;
            }
            String expectedId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(expected).toString();
            String actualId = blocks.blockId(pos.getX() & 15, pos.getY(), pos.getZ() & 15);
            return expectedId.equals(actualId) ? BlockProbe.PRESENT : BlockProbe.ABSENT;
        } catch (RuntimeException e) {
            return BlockProbe.UNVERIFIABLE; // 解码异常:保守当无法验证,绝不删
        }
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
