package com.monpai.sailboatmod.economy;

import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Supplier;

/**
 * 货币本位:决定"哪些物品算钱、各值多少"。当前支持金本位与紫水晶本位。
 *
 * <p>每个本位携带一张<b>降序</b>面额表(大面额在前,供找零/给币时优先用大面额)。
 * 面额表是<b>唯一数据源</b>:{@link GoldStandardEconomy} 与 {@link com.monpai.sailboatmod.market.wallet.MarketWalletGoldSource}
 * 都查 {@link #current()}.{@link #denominations()},不再各自硬编码,杜绝两处面额表漂移。</p>
 *
 * <p><b>为什么 item 用 {@link Supplier}</b>:{@link ModItems#HALF_AMETHYST_ITEM} 等 RegistryObject 在类加载期
 * 还没 {@code .get()} 出真正的 Item(注册晚于本 enum 的静态初始化),直接调 {@code .get()} 会 NPE。
 * 故面额表存 {@code Supplier<Item>},到运行期(扣款/给币)才解析。原版 {@link Items#GOLD_NUGGET} 等是常量,安全。</p>
 *
 * <p><b>"切后只认本本位"</b>:{@link #unitValueOf(ItemStack)} 只认本本位面额表里的物品,其他一律返回 0
 * —— 紫水晶本位下金块/金锭/金粒返回 0,天然不被当货币识别。</p>
 */
public enum CurrencyStandard {
    GOLD(List.of(
            new Denomination(() -> Items.GOLD_BLOCK, GoldStandardEconomy.BALANCE_PER_GOLD_BLOCK),
            new Denomination(() -> Items.GOLD_INGOT, GoldStandardEconomy.BALANCE_PER_GOLD_INGOT),
            new Denomination(() -> Items.GOLD_NUGGET, GoldStandardEconomy.BALANCE_PER_GOLD_NUGGET),
            new Denomination(() -> ModItems.HALF_NUGGET_ITEM.get(), 1)
    )),
    AMETHYST(List.of(
            new Denomination(() -> Items.AMETHYST_BLOCK, 8),
            new Denomination(() -> Items.AMETHYST_SHARD, 2),
            new Denomination(() -> ModItems.HALF_AMETHYST_ITEM.get(), 1)
    ));

    private final List<Denomination> denominations;

    CurrencyStandard(List<Denomination> denominations) {
        this.denominations = denominations;
    }

    /** 当前生效本位(配置 + 自动判定)。 */
    public static CurrencyStandard current() {
        return CurrencyStandardService.current();
    }

    /** 降序面额表(大面额在前)。运行期调用,Supplier 已可安全 get。 */
    public List<Denomination> denominations() {
        return denominations;
    }

    /** 最大面额单值(GOLD=162 / AMETHYST=8),供 maxWarehouseCurrency 等上限估算。 */
    public int topDenominationValue() {
        int top = 0;
        for (Denomination d : denominations) {
            top = Math.max(top, d.unitValue());
        }
        return top;
    }

    /** 该物品在本本位下的单位货币值;非本本位货币物品返回 0(实现"切后只认本本位")。 */
    public int unitValueOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        for (Denomination d : denominations) {
            if (stack.is(d.item())) {
                return d.unitValue();
            }
        }
        return 0;
    }

    /** 一档面额:物品(延迟解析)+ 单位货币值。 */
    public record Denomination(Supplier<Item> itemSupplier, int unitValue) {
        public Item item() {
            return itemSupplier.get();
        }
    }
}
