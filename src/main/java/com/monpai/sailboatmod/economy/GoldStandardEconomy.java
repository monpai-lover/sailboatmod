package com.monpai.sailboatmod.economy;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

public final class GoldStandardEconomy {
    public static final int BALANCE_PER_GOLD_NUGGET = 2;
    public static final int BALANCE_PER_GOLD_INGOT = 18;
    public static final int BALANCE_PER_GOLD_BLOCK = BALANCE_PER_GOLD_INGOT * 9;
    public static final String LEDGER_CURRENCY = "GOLD_STANDARD";

    /** Returns the market currency value of a gold item stack, or 0 if not a gold item. */
    public static long goldItemMarketValue(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (!stack.is(Items.GOLD_INGOT) && !stack.is(Items.GOLD_BLOCK) && !stack.is(Items.GOLD_NUGGET)) return 0;
        try {
            com.monpai.sailboatmod.market.commodity.CommodityMarketService svc = new com.monpai.sailboatmod.market.commodity.CommodityMarketService();
            int unitPrice = svc.ensureCommodity(stack).state().basePrice();
            return (long) unitPrice * stack.getCount();
        } catch (Exception ignored) {}
        // fallback to fixed rate
        int unitValue = stack.is(Items.GOLD_BLOCK) ? BALANCE_PER_GOLD_BLOCK
                : stack.is(Items.GOLD_INGOT) ? BALANCE_PER_GOLD_INGOT : BALANCE_PER_GOLD_NUGGET;
        return (long) unitValue * stack.getCount();
    }

    private GoldStandardEconomy() {
    }

    public static String formatBalance(long amount) {
        return String.format(Locale.ROOT, "%,d", Math.max(0L, amount));
    }

    public static String formatSignedBalance(long amount) {
        long safe = Math.max(Long.MIN_VALUE + 1, Math.min(Long.MAX_VALUE, amount));
        if (safe > 0L) {
            return "+" + formatBalance(safe);
        }
        if (safe < 0L) {
            return "-" + formatBalance(Math.abs(safe));
        }
        return "0";
    }

    @Nullable
    public static Boolean tryWithdraw(Player player, long amount) {
        if (player == null || amount <= 0L || player.getAbilities().instabuild) {
            return Boolean.TRUE;
        }
        int safeAmount = saturatingInt(amount);
        Boolean vaultResult = VaultEconomyBridge.tryWithdraw(player, safeAmount);
        if (vaultResult != null) {
            return vaultResult;
        }
        return withdrawPhysicalGold(player, safeAmount);
    }

    @Nullable
    public static Boolean tryWithdrawByIdentity(@Nullable UUID playerUuid, String playerName, long amount) {
        if (amount <= 0L) {
            return Boolean.TRUE;
        }
        return VaultEconomyBridge.tryWithdrawByIdentity(playerUuid, playerName, saturatingInt(amount));
    }

    @Nullable
    public static Boolean tryDeposit(Player player, long amount) {
        if (player == null || amount <= 0L) {
            return Boolean.TRUE;
        }
        int safeAmount = saturatingInt(amount);
        Boolean vaultResult = VaultEconomyBridge.tryDeposit(player, safeAmount);
        if (vaultResult != null && vaultResult) {
            return Boolean.TRUE;
        }
        givePhysicalGold(player, safeAmount);
        return Boolean.TRUE;
    }

    @Nullable
    public static Boolean tryDepositByIdentity(@Nullable UUID playerUuid, String playerName, long amount) {
        if (amount <= 0L) {
            return Boolean.TRUE;
        }
        return VaultEconomyBridge.tryDepositByIdentity(playerUuid, playerName, saturatingInt(amount));
    }

    private static boolean withdrawPhysicalGold(Player player, int amount) {
        Inventory inventory = player.getInventory();
        int total = countPhysicalGoldBalance(inventory);
        if (total < amount) {
            return false;
        }

        int removedValue = 0;
        for (int i = 0; i < inventory.getContainerSize() && removedValue < amount; i++) {
            ItemStack stack = inventory.getItem(i);
            int unitValue = balanceValue(stack);
            if (unitValue <= 0) {
                continue;
            }
            removedValue += stack.getCount() * unitValue;
            inventory.setItem(i, ItemStack.EMPTY);
        }

        if (removedValue > amount) {
            givePhysicalGold(player, removedValue - amount);
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        return true;
    }

    private static void givePhysicalGold(Player player, int amount) {
        int remaining = Math.max(0, amount);
        remaining = giveCurrencyStacks(player, Items.GOLD_BLOCK, BALANCE_PER_GOLD_BLOCK, remaining);
        remaining = giveCurrencyStacks(player, Items.GOLD_INGOT, BALANCE_PER_GOLD_INGOT, remaining);
        remaining = giveCurrencyStacks(player, Items.GOLD_NUGGET, BALANCE_PER_GOLD_NUGGET, remaining);
        // Give half-nuggets for odd remainder (value=1 each)
        if (remaining > 0) {
            ItemStack halfNugget = new ItemStack(com.monpai.sailboatmod.registry.ModItems.HALF_NUGGET_ITEM.get(), remaining);
            boolean added = player.getInventory().add(halfNugget);
            if (!added || !halfNugget.isEmpty()) player.drop(halfNugget, false);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static int giveCurrencyStacks(Player player, net.minecraft.world.item.Item item, int unitValue, int amount) {
        if (amount < unitValue) {
            return amount;
        }
        int count = amount / unitValue;
        while (count > 0) {
            int stackSize = Math.min(item.getMaxStackSize(), count);
            ItemStack stack = new ItemStack(item, stackSize);
            boolean added = player.getInventory().add(stack);
            if (!added || !stack.isEmpty()) {
                player.drop(stack, false);
            }
            count -= stackSize;
        }
        return amount % unitValue;
    }

    private static int countPhysicalGoldBalance(Inventory inventory) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            int unitValue = balanceValue(stack);
            if (unitValue > 0) {
                total += stack.getCount() * unitValue;
            }
        }
        return total;
    }

    private static int balanceValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        if (stack.is(Items.GOLD_BLOCK)) {
            return BALANCE_PER_GOLD_BLOCK;
        }
        if (stack.is(Items.GOLD_INGOT)) {
            return BALANCE_PER_GOLD_INGOT;
        }
        if (stack.is(Items.GOLD_NUGGET)) {
            return BALANCE_PER_GOLD_NUGGET;
        }
        if (stack.is(com.monpai.sailboatmod.registry.ModItems.HALF_NUGGET_ITEM.get())) {
            return 1;
        }
        return 0;
    }

    private static int saturatingInt(long amount) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, amount));
    }
}
