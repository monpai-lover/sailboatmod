package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.market.ProcurementRecord;
import com.monpai.sailboatmod.market.ProcurementService;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.market.commodity.CommodityKeyResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public final class TownDeliveryService {
    private TownDeliveryService() {
    }

    public static String deliverDockArrival(Level level, BlockPos dockPos, String declaredTownId,
                                            List<ItemStack> cargo, List<ShipmentManifestEntry> manifest) {
        if (level == null || cargo == null || cargo.isEmpty()) {
            return "";
        }
        String townId = DockTownResolver.resolveTownForArrival(level, dockPos, declaredTownId);
        if (!townId.isBlank()) {
            TownStockpileService.addCargo(level, townId, cargo);
        }
        if (manifest != null) {
            for (ShipmentManifestEntry entry : manifest) {
                if (entry == null || entry.quantity() <= 0 || entry.itemStack() == null || entry.itemStack().isEmpty()) {
                    continue;
                }
                String commodityKey = CommodityKeyResolver.resolve(entry.itemStack());
                ProcurementRecord procurement = ProcurementService.markDeliveredByOrder(
                        level,
                        entry.purchaseOrderId(),
                        entry.shippingOrderId(),
                        townId,
                        commodityKey,
                        entry.quantity()
                );
                applyPostDeliveryUpdates(level, townId, commodityKey, entry.quantity(), procurement);
            }
        }
        return townId;
    }

    public static void deliverDirectProcurement(Level level, String townId, ItemStack template, int quantity, String procurementId) {
        if (level == null || template == null || template.isEmpty() || quantity <= 0) {
            return;
        }
        List<ItemStack> cargo = splitCargo(template, quantity);
        if (!townId.isBlank()) {
            TownStockpileService.addCargo(level, townId, cargo);
        }
        ProcurementRecord procurement = ProcurementService.markDelivered(level, procurementId, quantity);
        applyPostDeliveryUpdates(level, townId, CommodityKeyResolver.resolve(template), quantity, procurement);
    }

    private static void applyPostDeliveryUpdates(Level level, String townId, String commodityKey, int quantity, ProcurementRecord procurement) {
        if (procurement == null || !"CONSTRUCTION".equals(procurement.purposeType())) {
            return;
        }
        String resolvedTownId = townId == null || townId.isBlank() ? procurement.buyerTownId() : townId;
        ConstructionMaterialRequestService.addFulfillment(level, procurement.purposeRef(), resolvedTownId, commodityKey, quantity);
    }

    private static List<ItemStack> splitCargo(ItemStack template, int quantity) {
        List<ItemStack> cargo = new ArrayList<>();
        if (template == null || template.isEmpty() || quantity <= 0) {
            return cargo;
        }
        int remaining = quantity;
        int stackSize = Math.max(1, template.getMaxStackSize());
        while (remaining > 0) {
            int amount = Math.min(stackSize, remaining);
            ItemStack stack = template.copy();
            stack.setCount(amount);
            cargo.add(stack);
            remaining -= amount;
        }
        return cargo;
    }
}
