package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.market.ProcurementRecord;
import com.monpai.sailboatmod.market.ProcurementService;
import com.monpai.sailboatmod.nation.model.TownFinanceEntryRecord;
import com.monpai.sailboatmod.nation.model.TownDemandRecord;
import com.monpai.sailboatmod.nation.model.TownStockpileRecord;
import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TownEconomySnapshotService {
    private static final int PREVIEW_LINE_LIMIT = 10;

    public record TownEconomySnapshot(
            int stockpileCommodityTypes,
            int stockpileTotalUnits,
            int openDemandCount,
            int openDemandUnits,
            int activeProcurementCount,
            long totalIncome,
            long totalExpense,
            long netBalance,
            float employmentRate,
            List<String> stockpilePreviewLines,
            List<String> demandPreviewLines,
            List<String> procurementPreviewLines,
            List<String> financePreviewLines
    ) {
    }

    private TownEconomySnapshotService() {
    }

    public static TownEconomySnapshot build(Level level, String townId) {
        if (level == null || townId == null || townId.isBlank()) {
            return new TownEconomySnapshot(0, 0, 0, 0, 0, 0L, 0L, 0L, 0.0f, List.of(), List.of(), List.of(), List.of());
        }

        TownStockpileRecord stockpile = TownStockpileService.getStockpile(level, townId);
        int stockpileCommodityTypes = stockpile.commodityAmounts().size();
        int stockpileTotalUnits = 0;
        for (Integer amount : stockpile.commodityAmounts().values()) {
            stockpileTotalUnits += Math.max(0, amount == null ? 0 : amount);
        }

        List<TownDemandRecord> openDemands = TownDemandLedgerService.getOpenDemands(level, townId);
        int openDemandUnits = 0;
        for (TownDemandRecord demand : openDemands) {
            openDemandUnits += Math.max(0, demand.requiredAmount() - demand.fulfilledAmount());
        }

        List<ProcurementRecord> procurements = ProcurementService.getProcurementsForTown(level, townId);
        int activeProcurementCount = 0;
        for (ProcurementRecord procurement : procurements) {
            if (procurement.hasRemainingQuantity()) {
                activeProcurementCount++;
            }
        }

        TownFinanceLedgerService.TownBalanceSnapshot finance = TownFinanceLedgerService.getTownBalanceSnapshot(level, townId);
        List<TownFinanceEntryRecord> financeEntries = TownFinanceLedgerService.getRecentEntries(level, townId);
        float employmentRate = calculateEmploymentRate(level, townId);

        return new TownEconomySnapshot(
                stockpileCommodityTypes,
                stockpileTotalUnits,
                openDemands.size(),
                openDemandUnits,
                activeProcurementCount,
                finance.totalIncome(),
                finance.totalExpense(),
                finance.netBalance(),
                employmentRate,
                buildStockpilePreview(stockpile),
                buildDemandPreview(openDemands),
                buildProcurementPreview(procurements),
                buildFinancePreview(financeEntries)
        );
    }

    private static List<String> buildStockpilePreview(TownStockpileRecord stockpile) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(stockpile.commodityAmounts().entrySet());
        entries.sort((left, right) -> Integer.compare(Math.max(0, right.getValue()), Math.max(0, left.getValue())));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < entries.size() && lines.size() < PREVIEW_LINE_LIMIT; i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            lines.add(displayCommodity(entry.getKey()) + " x" + Math.max(0, entry.getValue()));
        }
        return lines.isEmpty() ? List.of("-") : List.copyOf(lines);
    }

    private static List<String> buildDemandPreview(List<TownDemandRecord> openDemands) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < openDemands.size() && lines.size() < PREVIEW_LINE_LIMIT; i++) {
            TownDemandRecord demand = openDemands.get(i);
            int shortage = Math.max(0, demand.requiredAmount() - demand.fulfilledAmount());
            lines.add(demand.demandType() + " | " + displayCommodity(demand.commodityKey()) + " " + shortage);
        }
        return lines.isEmpty() ? List.of("-") : List.copyOf(lines);
    }

    private static List<String> buildProcurementPreview(List<ProcurementRecord> procurements) {
        List<String> lines = new ArrayList<>();
        for (ProcurementRecord procurement : procurements) {
            if (!procurement.hasRemainingQuantity()) {
                continue;
            }
            lines.add(procurement.fulfillmentStatus() + " | " + displayCommodity(procurement.commodityKey()) + " x"
                    + Math.max(0, procurement.quantity() - procurement.deliveredQuantity()));
            if (lines.size() >= PREVIEW_LINE_LIMIT) {
                break;
            }
        }
        return lines.isEmpty() ? List.of("-") : List.copyOf(lines);
    }

    private static List<String> buildFinancePreview(List<TownFinanceEntryRecord> financeEntries) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < financeEntries.size() && lines.size() < PREVIEW_LINE_LIMIT; i++) {
            TownFinanceEntryRecord entry = financeEntries.get(i);
            String prefix = "INCOME".equals(entry.entryKind()) ? "+" : "-";
            String commodity = entry.commodityKey().isBlank() ? "" : " | " + displayCommodity(entry.commodityKey());
            lines.add(prefix + entry.amount() + " | " + entry.type() + commodity);
        }
        return lines.isEmpty() ? List.of("-") : List.copyOf(lines);
    }

    private static String displayCommodity(String commodityKey) {
        ResourceLocation key = ResourceLocation.tryParse(commodityKey == null ? "" : commodityKey.trim());
        if (key != null) {
            Item item = ForgeRegistries.ITEMS.getValue(key);
            if (item != null) {
                return item.getDefaultInstance().getHoverName().getString();
            }
        }
        return commodityKey == null || commodityKey.isBlank() ? "-" : commodityKey;
    }

    private static float calculateEmploymentRate(Level level, String townId) {
        if (level == null || townId == null || townId.isBlank()) {
            return 0.0f;
        }
        List<ResidentRecord> residents = ResidentSavedData.get(level).getResidentsForTown(townId);
        if (residents.isEmpty()) {
            return 0.0f;
        }
        int employed = 0;
        for (ResidentRecord resident : residents) {
            if (resident != null && resident.profession() != Profession.UNEMPLOYED) {
                employed++;
            }
        }
        return (float) employed / (float) residents.size();
    }
}
