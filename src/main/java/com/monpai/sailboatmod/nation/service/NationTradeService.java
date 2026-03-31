package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import com.monpai.sailboatmod.nation.model.*;
import com.monpai.sailboatmod.network.packet.NationToastPacket;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class NationTradeService {
    private NationTradeService() {}

    public static NationResult proposeTrade(ServerPlayer actor, String targetNationName, long offerCurrency, long requestCurrency) {
        return proposeTrade(actor, targetNationName, offerCurrency, List.of(), requestCurrency, List.of());
    }

    public static NationResult proposeTrade(ServerPlayer actor, String targetNationName, long offerCurrency, List<ItemStack> offerItems, long requestCurrency, List<ItemStack> requestItems) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord member = data.getMember(actor.getUUID());
        if (member == null) return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        if (!NationService.hasPermission(actor.level(), actor.getUUID(), NationPermission.MANAGE_TREASURY))
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));

        NationRecord nation = data.getNation(member.nationId());
        if (nation == null) return NationResult.failure(Component.translatable("command.sailboatmod.nation.data_missing"));
        NationRecord target = NationService.findNation(actor.level(), targetNationName);
        if (target == null) return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.target_not_found", targetNationName));
        if (nation.nationId().equals(target.nationId()))
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.diplomacy.self"));

        NationTreasuryRecord treasury = data.getOrCreateTreasury(nation.nationId());
        if (offerCurrency > 0 && treasury.currencyBalance() < offerCurrency)
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.trade.insufficient_funds"));

        // Validate offer items exist in proposer treasury
        if (offerItems == null) offerItems = List.of();
        if (requestItems == null) requestItems = List.of();
        if (offerItems.size() > TradeScreenData.MAX_TRADE_ITEMS || requestItems.size() > TradeScreenData.MAX_TRADE_ITEMS)
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.trade.too_many_items"));
        if (!validateItemsInTreasury(treasury, offerItems))
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.trade.items_missing"));

        TradeProposalRecord proposal = new TradeProposalRecord(
                UUID.randomUUID().toString().substring(0, 8),
                nation.nationId(), target.nationId(),
                offerCurrency, List.copyOf(offerItems),
                requestCurrency, List.copyOf(requestItems),
                System.currentTimeMillis());
        data.putTradeProposal(proposal);

        // Notify target
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            for (ServerPlayer p : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
                NationMemberRecord m = data.getMember(p.getUUID());
                if (m != null && target.nationId().equals(m.nationId())) {
                    NationToastPacket.send(p, Component.translatable("toast.sailboatmod.nation.trade.title"),
                            Component.translatable("command.sailboatmod.nation.trade.proposed", nation.name()));
                }
            }
        }
        return NationResult.success(Component.translatable("command.sailboatmod.nation.trade.sent", target.name()));
    }

    public static NationResult acceptTrade(ServerPlayer actor, String proposerNationId) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord member = data.getMember(actor.getUUID());
        if (member == null) return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        if (!NationService.hasPermission(actor.level(), actor.getUUID(), NationPermission.MANAGE_TREASURY))
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));

        TradeProposalRecord proposal = findProposalForTarget(data, member.nationId(), proposerNationId);
        if (proposal == null || proposal.isExpired())
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.trade.no_proposal"));

        // Execute trade
        NationTreasuryRecord proposerTreasury = data.getOrCreateTreasury(proposal.proposerNationId());
        NationTreasuryRecord targetTreasury = data.getOrCreateTreasury(proposal.targetNationId());

        // Validate items still exist in treasuries
        if (!proposal.offerItems().isEmpty() && !validateItemsInTreasury(proposerTreasury, proposal.offerItems())) {
            data.removeTradeProposal(proposal.proposalId());
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.trade.items_missing"));
        }
        if (!proposal.requestItems().isEmpty() && !validateItemsInTreasury(targetTreasury, proposal.requestItems())) {
            data.removeTradeProposal(proposal.proposalId());
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.trade.items_missing"));
        }

        // Currency exchange
        if (proposal.offerCurrency() > 0) {
            if (proposerTreasury.currencyBalance() < proposal.offerCurrency()) {
                data.removeTradeProposal(proposal.proposalId());
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.trade.insufficient_funds"));
            }
            data.putTreasury(proposerTreasury.withBalance(proposerTreasury.currencyBalance() - proposal.offerCurrency()));
            targetTreasury = data.getOrCreateTreasury(proposal.targetNationId());
            data.putTreasury(targetTreasury.withBalance(targetTreasury.currencyBalance() + proposal.offerCurrency()));
        }
        if (proposal.requestCurrency() > 0) {
            targetTreasury = data.getOrCreateTreasury(proposal.targetNationId());
            if (targetTreasury.currencyBalance() < proposal.requestCurrency()) {
                data.removeTradeProposal(proposal.proposalId());
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.trade.target_insufficient"));
            }
            data.putTreasury(targetTreasury.withBalance(targetTreasury.currencyBalance() - proposal.requestCurrency()));
            proposerTreasury = data.getOrCreateTreasury(proposal.proposerNationId());
            data.putTreasury(proposerTreasury.withBalance(proposerTreasury.currencyBalance() + proposal.requestCurrency()));
        }

        // Item exchange: offer items (proposer -> target)
        proposerTreasury = data.getOrCreateTreasury(proposal.proposerNationId());
        targetTreasury = data.getOrCreateTreasury(proposal.targetNationId());
        for (ItemStack offerItem : proposal.offerItems()) {
            if (offerItem.isEmpty()) continue;
            removeItemFromTreasury(proposerTreasury, offerItem);
            addItemToTreasury(targetTreasury, offerItem);
        }
        data.putTreasury(proposerTreasury);
        data.putTreasury(targetTreasury);

        // Item exchange: request items (target -> proposer)
        proposerTreasury = data.getOrCreateTreasury(proposal.proposerNationId());
        targetTreasury = data.getOrCreateTreasury(proposal.targetNationId());
        for (ItemStack requestItem : proposal.requestItems()) {
            if (requestItem.isEmpty()) continue;
            removeItemFromTreasury(targetTreasury, requestItem);
            addItemToTreasury(proposerTreasury, requestItem);
        }
        data.putTreasury(proposerTreasury);
        data.putTreasury(targetTreasury);

        data.removeTradeProposal(proposal.proposalId());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.trade.accepted"));
    }

    public static NationResult rejectTrade(ServerPlayer actor, String proposerNationId) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord member = data.getMember(actor.getUUID());
        if (member == null) return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));

        TradeProposalRecord proposal = findProposalForTarget(data, member.nationId(), proposerNationId);
        if (proposal == null) return NationResult.failure(Component.translatable("command.sailboatmod.nation.trade.no_proposal"));
        data.removeTradeProposal(proposal.proposalId());
        return NationResult.success(Component.translatable("command.sailboatmod.nation.trade.rejected"));
    }

    public static void tickExpiry(NationSavedData data) {
        List<String> expired = new ArrayList<>();
        for (TradeProposalRecord p : data.getTradeProposals()) {
            if (p.isExpired()) expired.add(p.proposalId());
        }
        for (String id : expired) data.removeTradeProposal(id);
    }

    public static NationResult cancelTrade(ServerPlayer actor) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord member = data.getMember(actor.getUUID());
        if (member == null) return NationResult.failure(Component.translatable("command.sailboatmod.nation.invite.no_nation"));
        for (TradeProposalRecord p : data.getTradeProposals()) {
            if (p.proposerNationId().equals(member.nationId())) {
                data.removeTradeProposal(p.proposalId());
                return NationResult.success(Component.translatable("command.sailboatmod.nation.trade.cancelled"));
            }
        }
        return NationResult.failure(Component.translatable("command.sailboatmod.nation.trade.no_proposal"));
    }

    public static TradeScreenData buildTradeScreenData(ServerPlayer actor, String targetNationId) {
        NationSavedData data = NationSavedData.get(actor.level());
        NationMemberRecord member = data.getMember(actor.getUUID());
        if (member == null) return TradeScreenData.empty();
        NationRecord ourNation = data.getNation(member.nationId());
        if (ourNation == null) return TradeScreenData.empty();
        NationRecord targetNation = data.getNation(targetNationId);
        if (targetNation == null) {
            targetNation = NationService.findNation(actor.level(), targetNationId);
        }
        if (targetNation == null) return TradeScreenData.empty();

        NationTreasuryRecord ourTreasury = data.getOrCreateTreasury(ourNation.nationId());
        NationTreasuryRecord targetTreasury = data.getOrCreateTreasury(targetNation.nationId());
        boolean canManage = NationService.hasPermission(actor.level(), actor.getUUID(), NationPermission.MANAGE_TREASURY);

        // Diplomacy status
        NationDiplomacyRecord dipRecord = data.getDiplomacy(ourNation.nationId(), targetNation.nationId());
        String dipStatus = dipRecord != null ? dipRecord.statusId() : "neutral";

        // Target treasury visible only if allied or trade
        boolean showTargetTreasury = "allied".equals(dipStatus) || "trade".equals(dipStatus);
        List<ItemStack> targetItems = showTargetTreasury ? copyTreasuryItems(targetTreasury) : List.of();
        long targetBalance = showTargetTreasury ? targetTreasury.currencyBalance() : 0L;

        // Find existing proposal between these two nations
        TradeProposalRecord proposal = findProposalBetween(data, ourNation.nationId(), targetNation.nationId());
        boolean hasProposal = proposal != null && !proposal.isExpired();
        boolean weAreProposer = hasProposal && proposal.proposerNationId().equals(ourNation.nationId());
        int remainingSec = 0;
        if (hasProposal) {
            long elapsed = System.currentTimeMillis() - proposal.createdAt();
            remainingSec = Math.max(0, (int) ((TradeProposalRecord.EXPIRY_MILLIS - elapsed) / 1000L));
        }

        return new TradeScreenData(
                ourNation.nationId(), ourNation.name(), ourNation.primaryColorRgb(),
                ourTreasury.currencyBalance(), copyTreasuryItems(ourTreasury), canManage,
                targetNation.nationId(), targetNation.name(), targetNation.primaryColorRgb(),
                targetBalance, targetItems,
                hasProposal, hasProposal ? proposal.proposalId() : "", weAreProposer,
                hasProposal ? proposal.offerCurrency() : 0L,
                hasProposal ? List.copyOf(proposal.offerItems()) : List.of(),
                hasProposal ? proposal.requestCurrency() : 0L,
                hasProposal ? List.copyOf(proposal.requestItems()) : List.of(),
                remainingSec, dipStatus
        );
    }

    private static List<ItemStack> copyTreasuryItems(NationTreasuryRecord treasury) {
        List<ItemStack> items = new ArrayList<>(NationTreasuryRecord.TREASURY_SLOTS);
        for (int i = 0; i < treasury.items().size(); i++) {
            items.add(treasury.items().get(i).copy());
        }
        return items;
    }

    private static boolean validateItemsInTreasury(NationTreasuryRecord treasury, List<ItemStack> items) {
        // Build a mutable copy of treasury contents for counting
        List<ItemStack> available = new ArrayList<>();
        for (ItemStack stack : treasury.items()) {
            if (!stack.isEmpty()) available.add(stack.copy());
        }
        for (ItemStack required : items) {
            if (required.isEmpty()) continue;
            int needed = required.getCount();
            for (ItemStack avail : available) {
                if (avail.isEmpty()) continue;
                if (ItemStack.isSameItemSameTags(avail, required)) {
                    int take = Math.min(needed, avail.getCount());
                    avail.shrink(take);
                    needed -= take;
                    if (needed <= 0) break;
                }
            }
            if (needed > 0) return false;
        }
        return true;
    }

    private static void removeItemFromTreasury(NationTreasuryRecord treasury, ItemStack toRemove) {
        int remaining = toRemove.getCount();
        NonNullList<ItemStack> items = treasury.items();
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, toRemove)) {
                int take = Math.min(remaining, slot.getCount());
                slot.shrink(take);
                remaining -= take;
            }
        }
    }

    private static void addItemToTreasury(NationTreasuryRecord treasury, ItemStack toAdd) {
        ItemStack remaining = toAdd.copy();
        NonNullList<ItemStack> items = treasury.items();
        // Try to merge into existing stacks first
        for (int i = 0; i < items.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, remaining)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space > 0) {
                    int add = Math.min(space, remaining.getCount());
                    slot.grow(add);
                    remaining.shrink(add);
                }
            }
        }
        // Place in first empty slot
        for (int i = 0; i < items.size() && !remaining.isEmpty(); i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, remaining.copy());
                remaining.setCount(0);
            }
        }
    }

    private static TradeProposalRecord findProposalBetween(NationSavedData data, String nationA, String nationB) {
        for (TradeProposalRecord p : data.getTradeProposals()) {
            if ((p.proposerNationId().equals(nationA) && p.targetNationId().equals(nationB))
                    || (p.proposerNationId().equals(nationB) && p.targetNationId().equals(nationA)))
                return p;
        }
        return null;
    }

    private static TradeProposalRecord findProposalForTarget(NationSavedData data, String targetNationId, String proposerNationId) {
        for (TradeProposalRecord p : data.getTradeProposals()) {
            if (p.targetNationId().equals(targetNationId) && (proposerNationId == null || proposerNationId.isBlank() || p.proposerNationId().equals(proposerNationId)))
                return p;
        }
        return null;
    }
}
