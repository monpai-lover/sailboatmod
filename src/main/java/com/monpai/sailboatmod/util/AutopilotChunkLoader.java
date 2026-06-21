package com.monpai.sailboatmod.util;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * autopilot 载具(帆船/马车)强加载区块的 Forge 票据校验回调。
 *
 * <p><b>问题</b>:载具用 {@code ForgeChunkManager.forceChunk(...,entity owner,...,ticking=true)} 强加载自身周围区块,
 * 让它在玩家不在附近时也能 tick/移动。这些 entity-owner 票据会持久化到 {@code ForcedChunksSavedData}。但 Forge 要求
 * mod <b>注册 {@link ForgeChunkManager.LoadingValidationCallback}</b> 才会在世界重载后恢复这些票据——<b>没注册则
 * 全部丢弃</b>。本 mod 一直漏了注册,导致:退档保存(实体随区块写入 NBT)→ 票据丢失 → 重进 Forge 不恢复票据 → 载具
 * 区块不加载 → 实体不 tick → autopilot 不跑 → 永不重申票据 = <b>死锁,实体在磁盘活着但永远加载不回来 = 消失</b>。
 *
 * <p><b>修法</b>:{@link #register()} 在 mod 构造时注册回调。{@link #validateTickets} 对每个 entity-owner 票据
 * <b>原样保留</b>(不 removeAllTickets)→ Forge 重新强加载这些区块 → 实体随区块 NBT 加载回来 → 开始 tick →
 * autopilot 的 updateAutopilotChunkLoading 重新维护票据,航行恢复。无需在回调里碰实体本身(实体随区块加载自然回来)。
 */
public final class AutopilotChunkLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private AutopilotChunkLoader() {
    }

    /** mod 构造时调一次,注册 Forge 票据校验回调(否则退档重进票据全丢→载具消失)。 */
    public static void register() {
        ForgeChunkManager.setForcedChunkLoadingCallback(
                com.monpai.sailboatmod.SailboatMod.MODID, AutopilotChunkLoader::validateTickets);
    }

    /**
     * 世界加载时被 Forge 调:验证本 mod 持久化的强加载票据。我们对 autopilot 载具的 entity-owner 票据<b>全部保留</b>
     * (什么都不做),让 Forge 重新强加载这些区块→实体加载→tick→autopilot 恢复。block-owner 票据本 mod 不再使用,若有
     * 残留(老存档)一并保留无害(实体加载后会自行清理切换为 entity 票据)。
     */
    private static void validateTickets(ServerLevel level, ForgeChunkManager.TicketHelper helper) {
        Map<UUID, ?> entityTickets = helper.getEntityTickets();
        int kept = entityTickets.size();
        // 全部保留:不调 removeAllTickets/removeTicket → Forge 据持久化票据重新强加载区块 → 载具回来。
        if (kept > 0) {
            LOGGER.info("[AutopilotChunkLoader] 世界加载:保留 {} 个 autopilot 载具的强加载票据(令其区块重新加载,载具恢复)",
                    kept);
        }
    }
}
