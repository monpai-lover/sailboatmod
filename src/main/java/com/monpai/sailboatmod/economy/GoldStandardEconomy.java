package com.monpai.sailboatmod.economy;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

public final class GoldStandardEconomy {
    public static final int BALANCE_PER_GOLD_NUGGET = 2;
    public static final int BALANCE_PER_GOLD_INGOT = 18;
    public static final int BALANCE_PER_GOLD_BLOCK = BALANCE_PER_GOLD_INGOT * 9;
    public static final String LEDGER_CURRENCY = "GOLD_STANDARD";

    /**
     * Returns the market currency value of a currency item stack under the <b>current standard</b>, or 0 if not a
     * currency item of the active standard. 货币作硬通货，双向换算统一用固定面额率，杜绝"高估值存、固定率取"的套利。
     * 注意:语义随本位变化——金本位下只认金物品，紫水晶本位下只认紫水晶物品(金返回 0)。
     */
    public static long goldItemMarketValue(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        int unitValue = CurrencyStandard.current().unitValueOf(stack);
        if (unitValue <= 0) return 0;
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
        // 紫水晶本位的前提就是"无 Vault",直接走物理物品路径,不碰 Vault(避免本位判定与 VaultBridge 结果矛盾)。
        if (CurrencyStandard.current() != CurrencyStandard.AMETHYST) {
            Boolean vaultResult = VaultEconomyBridge.tryWithdraw(player, safeAmount);
            if (vaultResult != null) {
                return vaultResult;
            }
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
        // 紫水晶本位无 Vault，直接给物理物品。
        if (CurrencyStandard.current() != CurrencyStandard.AMETHYST) {
            Boolean vaultResult = VaultEconomyBridge.tryDeposit(player, safeAmount);
            if (vaultResult != null && vaultResult) {
                return Boolean.TRUE;
            }
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

    public static long getBalance(Player player) {
        if (player == null) {
            return 0L;
        }
        // 紫水晶本位无 Vault，余额即背包内当前本位货币物品折算。
        if (CurrencyStandard.current() != CurrencyStandard.AMETHYST) {
            long vault = getBalanceByIdentity(player.getUUID(), player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName());
            if (vault > 0L) {
                return vault;
            }
        }
        return countPhysicalGoldBalance(player.getInventory());
    }

    public static long getBalanceByIdentity(@Nullable UUID playerUuid, String playerName) {
        Long vault = VaultEconomyBridge.getBalanceByIdentity(playerUuid, playerName);
        return vault == null ? 0L : Math.max(0L, vault);
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
        // 按当前本位面额表降序给币(大面额在前)。末位面额值为 1，会吃掉所有奇数余额，无需硬编码半币尾巴。
        for (CurrencyStandard.Denomination denomination : CurrencyStandard.current().denominations()) {
            remaining = giveCurrencyStacks(player, denomination.item(), denomination.unitValue(), remaining);
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
        // 查当前本位面额表;非本本位货币物品返回 0(紫水晶本位下金不被当钱)。
        return CurrencyStandard.current().unitValueOf(stack);
    }

    private static int saturatingInt(long amount) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, amount));
    }
}
