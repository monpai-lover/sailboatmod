package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.market.ProcurementRecord;
import com.monpai.sailboatmod.market.ProcurementService;
import com.monpai.sailboatmod.nation.model.TownDemandRecord;
import com.monpai.sailboatmod.nation.model.TownStockpileRecord;
import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.world.level.Level;

import java.util.List;

public final class TownEconomySnapshotService {
    public record TownEconomySnapshot(
            int stockpileCommodityTypes,
            int stockpileTotalUnits,
            int openDemandCount,
            int openDemandUnits,
            int activeProcurementCount,
            long totalIncome,
            long totalExpense,
            long netBalance,
            float employmentRate
    ) {
    }

    private TownEconomySnapshotService() {
    }

    public static TownEconomySnapshot build(Level level, String townId) {
        if (level == null || townId == null || townId.isBlank()) {
            return new TownEconomySnapshot(0, 0, 0, 0, 0, 0L, 0L, 0L, 0.0f);
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
                employmentRate
        );
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
