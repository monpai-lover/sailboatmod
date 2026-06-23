package com.monpai.sailboatmod.economy;

import com.monpai.sailboatmod.ModConfig;
import net.minecraftforge.fml.ModList;

import java.util.Locale;

/**
 * 解析"当前生效货币本位"。
 *
 * <p>规则:先读 {@link ModConfig#currencyStandard()} —— {@code GOLD}/{@code AMETHYST} 强制对应本位;
 * {@code AUTO}(默认)时按"装了 MineColonies 且无 Vault 经济插件 → 紫水晶本位,否则金本位"自动判定。</p>
 *
 * <p><b>为什么缓存</b>:{@link VaultEconomyBridge#hasProvider()} 走反射解析 Vault provider,开销不低;
 * 而本位判定在每次扣款/给币/盘点时都会被调到(很频繁)。故缓存判定结果,{@value #CACHE_TTL_MS}ms 内复用。
 * 本位在运行中几乎不变(装没装 mod、有没有 Vault 是启动期定的),TTL 足够。</p>
 *
 * <p><b>线程</b>:调用点都在服务端主线程(menu 交互 / tick),{@code volatile} 缓存 + {@code ModList}/{@code VaultBridge}
 * 内部线程安全,无需额外锁。</p>
 */
public final class CurrencyStandardService {
    private static final long CACHE_TTL_MS = 60_000L;

    private static volatile CurrencyStandard cached;
    private static volatile long cachedAtMs;

    private CurrencyStandardService() {
    }

    public static CurrencyStandard current() {
        long now = System.currentTimeMillis();
        CurrencyStandard snapshot = cached;
        if (snapshot != null && now - cachedAtMs < CACHE_TTL_MS) {
            return snapshot;
        }
        CurrencyStandard resolved = resolve();
        cached = resolved;
        cachedAtMs = now;
        return resolved;
    }

    /** 配置/环境变化后强制下次重算(如 onServerStopped 或 reload)。 */
    public static void invalidate() {
        cached = null;
        cachedAtMs = 0L;
    }

    private static CurrencyStandard resolve() {
        String configured = safeConfigured();
        if ("GOLD".equals(configured)) {
            return CurrencyStandard.GOLD;
        }
        if ("AMETHYST".equals(configured)) {
            return CurrencyStandard.AMETHYST;
        }
        // AUTO:装了 MineColonies 且没有 Vault 经济插件 → 紫水晶本位。
        boolean mineColonies = ModList.get().isLoaded("minecolonies");
        boolean vault = VaultEconomyBridge.hasProvider();
        return (mineColonies && !vault) ? CurrencyStandard.AMETHYST : CurrencyStandard.GOLD;
    }

    private static String safeConfigured() {
        try {
            String value = ModConfig.currencyStandard();
            return value == null ? "AUTO" : value.trim().toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            // 配置尚未加载(极早期调用)时退回 AUTO。
            return "AUTO";
        }
    }
}
