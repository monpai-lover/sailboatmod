package com.monpai.sailboatmod.market.wallet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MarketWalletGoldSource {
    private MarketWalletGoldSource() {
    }

    public static boolean withdrawFromLinkedWarehouse(MarketBlockEntity market, UUID ownerId, long amount) {
        TownWarehouseBlockEntity warehouse = linkedWarehouse(market, ownerId, amount);
        if (warehouse == null) {
            return false;
        }
        Denomination[] denominations = denominations();
        long[] removeCounts = planRemoval(warehouse, ownerId, amount, denominations);
        if (removeCounts == null) {
            return false;
        }

        List<ItemStack> removedCargo = cargoForCounts(denominations, removeCounts);
        long removedValue = valueOf(denominations, removeCounts);
        List<ItemStack> extracted = new ArrayList<>();
        for (int i = 0; i < denominations.length; i++) {
            long count = removeCounts[i];
            if (count <= 0L) {
                continue;
            }
            ItemStack sample = new ItemStack(denominations[i].item());
            if (!warehouse.extractMatchingStock(ownerId, sample, safeCount(count))) {
                warehouse.insertCargo(ownerId, extracted);
                return false;
            }
            extracted.addAll(cargoFor(denominations[i].item(), count));
        }

        long change = removedValue - amount;
        if (change > 0L && !warehouse.insertCargo(ownerId, currencyStacks(change))) {
            warehouse.insertCargo(ownerId, removedCargo);
            return false;
        }
        return true;
    }

    public static boolean depositToLinkedWarehouse(MarketBlockEntity market, UUID ownerId, long amount) {
        TownWarehouseBlockEntity warehouse = linkedWarehouse(market, ownerId, amount);
        if (warehouse == null || amount > maxWarehouseCurrency()) {
            return false;
        }
        return warehouse.insertCargo(ownerId, currencyStacks(amount));
    }

    /**
     * 只读盘点：绑定仓库内该 owner 的金类物品折算为货币总值，与提取走同一口径同一仓库。
     * 供两端 UI 显示"可存入数值"，不扣减任何物品。
     */
    public static long linkedWarehouseGoldValue(MarketBlockEntity market, UUID ownerId) {
        if (market == null || ownerId == null) {
            return 0L;
        }
        TownWarehouseBlockEntity warehouse = market.getLinkedWarehouse();
        if (warehouse == null) {
            return 0L;
        }
        long total = 0L;
        for (Denomination denomination : denominations()) {
            long count = Math.max(0, warehouse.countMatchingStock(ownerId, new ItemStack(denomination.item())));
            total += count * denomination.unitValue();
        }
        return total;
    }

    private static TownWarehouseBlockEntity linkedWarehouse(MarketBlockEntity market, UUID ownerId, long amount) {
        if (market == null || ownerId == null || amount <= 0L) {
            return null;
        }
        return market.getLinkedWarehouse();
    }

    private static long[] planRemoval(TownWarehouseBlockEntity warehouse,
                                      UUID ownerId,
                                      long amount,
                                      Denomination[] denominations) {
        long[] available = new long[denominations.length];
        long total = 0L;
        for (int i = 0; i < denominations.length; i++) {
            available[i] = Math.max(0, warehouse.countMatchingStock(ownerId, new ItemStack(denominations[i].item())));
            total += available[i] * denominations[i].unitValue();
        }
        if (total < amount) {
            return null;
        }

        BestPlan best = new BestPlan();
        searchPlan(0, amount, denominations, available, new long[denominations.length], 0L, best);
        return best.counts;
    }

    private static void searchPlan(int index,
                                   long amount,
                                   Denomination[] denominations,
                                   long[] available,
                                   long[] current,
                                   long currentValue,
                                   BestPlan best) {
        if (currentValue >= amount || index >= denominations.length) {
            best.consider(current, currentValue, amount);
            return;
        }

        long unitValue = denominations[index].unitValue();
        long remaining = Math.max(0L, amount - currentValue);
        long floor = Math.min(available[index], remaining / unitValue);
        long ceil = Math.min(available[index], ceilDiv(remaining, unitValue));
        Set<Long> candidates = new LinkedHashSet<>();
        candidates.add(0L);
        candidates.add(floor);
        candidates.add(ceil);
        candidates.add(Math.min(available[index], Math.max(floor, ceil)));
        candidates.add(available[index]);

        for (long candidate : candidates) {
            if (candidate < 0L || candidate > available[index]) {
                continue;
            }
            current[index] = candidate;
            searchPlan(index + 1, amount, denominations, available, current,
                    currentValue + candidate * unitValue, best);
            current[index] = 0L;
        }
    }

    public static List<ItemStack> currencyStacks(long amount) {
        List<ItemStack> out = new ArrayList<>();
        long remaining = Math.max(0L, amount);
        for (Denomination denomination : denominations()) {
            remaining = appendCurrencyStacks(out, denomination.item(), denomination.unitValue(), remaining);
        }
        return out;
    }

    private static List<ItemStack> cargoForCounts(Denomination[] denominations, long[] counts) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < denominations.length; i++) {
            out.addAll(cargoFor(denominations[i].item(), counts[i]));
        }
        return out;
    }

    private static List<ItemStack> cargoFor(Item item, long count) {
        List<ItemStack> out = new ArrayList<>();
        appendItemStacks(out, item, count);
        return out;
    }

    private static long appendCurrencyStacks(List<ItemStack> out, Item item, int unitValue, long amount) {
        if (unitValue <= 0 || amount <= 0L) {
            return Math.max(0L, amount);
        }
        long count = amount / unitValue;
        appendItemStacks(out, item, count);
        return amount % unitValue;
    }

    private static void appendItemStacks(List<ItemStack> out, Item item, long count) {
        if (out == null || item == null || count <= 0L) {
            return;
        }
        int maxStack = Math.max(1, Math.min(64, item.getMaxStackSize()));
        long remaining = count;
        while (remaining > 0L) {
            int stackSize = (int) Math.min(maxStack, remaining);
            out.add(new ItemStack(item, stackSize));
            remaining -= stackSize;
        }
    }

    private static long valueOf(Denomination[] denominations, long[] counts) {
        long value = 0L;
        for (int i = 0; i < denominations.length; i++) {
            value += counts[i] * denominations[i].unitValue();
        }
        return value;
    }

    private static long ceilDiv(long left, long right) {
        if (right <= 0L) {
            return 0L;
        }
        return left <= 0L ? 0L : (left + right - 1L) / right;
    }

    private static int safeCount(long count) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, count));
    }

    private static long maxWarehouseCurrency() {
        return (long) TownWarehouseBlockEntity.STORAGE_SIZE
                * 64L
                * GoldStandardEconomy.BALANCE_PER_GOLD_BLOCK;
    }

    private static Denomination[] denominations() {
        return new Denomination[]{
                new Denomination(Items.GOLD_BLOCK, GoldStandardEconomy.BALANCE_PER_GOLD_BLOCK),
                new Denomination(Items.GOLD_INGOT, GoldStandardEconomy.BALANCE_PER_GOLD_INGOT),
                new Denomination(Items.GOLD_NUGGET, GoldStandardEconomy.BALANCE_PER_GOLD_NUGGET),
                new Denomination(ModItems.HALF_NUGGET_ITEM.get(), 1)
        };
    }

    private record Denomination(Item item, int unitValue) {
    }

    private static final class BestPlan {
        private long value = Long.MAX_VALUE;
        private long itemCount = Long.MAX_VALUE;
        private long[] counts;

        private void consider(long[] candidate, long candidateValue, long amount) {
            if (candidateValue < amount) {
                return;
            }
            long candidateItemCount = 0L;
            for (long count : candidate) {
                candidateItemCount += count;
            }
            if (candidateValue < value || (candidateValue == value && candidateItemCount < itemCount)) {
                value = candidateValue;
                itemCount = candidateItemCount;
                counts = candidate.clone();
            }
        }
    }
}
