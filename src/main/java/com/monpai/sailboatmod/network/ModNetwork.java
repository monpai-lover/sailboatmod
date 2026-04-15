package com.monpai.sailboatmod.network;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.network.packet.BankActionPacket;
import com.monpai.sailboatmod.network.packet.CancelBuyOrderPacket;
import com.monpai.sailboatmod.network.packet.ClearTerrainCachePacket;
import com.monpai.sailboatmod.network.packet.CancelMarketListingPacket;
import com.monpai.sailboatmod.network.packet.CopyMarketWebTokenPacket;
import com.monpai.sailboatmod.network.packet.CreateAutoRoutePacket;
import com.monpai.sailboatmod.network.packet.BuildingUpgradePacket;
import com.monpai.sailboatmod.network.packet.OpenResidentScreenPacket;
import com.monpai.sailboatmod.network.packet.ResidentActionPacket;
import com.monpai.sailboatmod.network.packet.PlaceBankStructurePacket;
import com.monpai.sailboatmod.network.packet.SyncTreasuryPacket;
import com.monpai.sailboatmod.network.packet.OpenTradeScreenPacket;
import com.monpai.sailboatmod.network.packet.TradeScreenActionPacket;
import com.monpai.sailboatmod.network.packet.ClaimMarketCreditsPacket;
import com.monpai.sailboatmod.network.packet.ControlAutopilotPacket;
import com.monpai.sailboatmod.network.packet.CreateBuyOrderPacket;
import com.monpai.sailboatmod.network.packet.CreateMarketListingPacket;
import com.monpai.sailboatmod.network.packet.DispatchMarketOrderPacket;
import com.monpai.sailboatmod.network.packet.DockGuiActionPacket;
import com.monpai.sailboatmod.network.packet.FinalizeRouteNamePacket;
import com.monpai.sailboatmod.network.packet.MarketGuiActionPacket;
import com.monpai.sailboatmod.network.packet.MarketStatusNoticePacket;
import com.monpai.sailboatmod.network.packet.NationGuiActionPacket;
import com.monpai.sailboatmod.network.packet.NationToastPacket;
import com.monpai.sailboatmod.network.packet.OpenDockScreenPacket;
import com.monpai.sailboatmod.network.packet.OpenMarketScreenPacket;
import com.monpai.sailboatmod.network.packet.OpenNationMenuPacket;
import com.monpai.sailboatmod.network.packet.OpenNationScreenPacket;
import com.monpai.sailboatmod.network.packet.OpenTownMenuPacket;
import com.monpai.sailboatmod.network.packet.OpenTownScreenPacket;
import com.monpai.sailboatmod.network.packet.OpenSailboatStoragePacket;
import com.monpai.sailboatmod.network.packet.PurchaseMarketListingPacket;
import com.monpai.sailboatmod.network.packet.RenameDockPacket;
import com.monpai.sailboatmod.network.packet.RenameMarketPacket;
import com.monpai.sailboatmod.network.packet.RenameSailboatPacket;
import com.monpai.sailboatmod.network.packet.RequestAutoRouteDocksPacket;
import com.monpai.sailboatmod.network.packet.OpenRoadPlannerScreenPacket;
import com.monpai.sailboatmod.network.packet.SelectRoadPlannerTargetPacket;
import com.monpai.sailboatmod.network.packet.SelectRoadPlannerPreviewOptionPacket;
import com.monpai.sailboatmod.network.packet.SelectSailboatSeatPacket;
import com.monpai.sailboatmod.network.packet.SyncConstructionProgressPacket;
import com.monpai.sailboatmod.network.packet.SyncConstructionGhostPreviewPacket;
import com.monpai.sailboatmod.network.packet.SyncConstructorSettingsPacket;
import com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket;
import com.monpai.sailboatmod.network.packet.SyncRoadConstructionProgressPacket;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import com.monpai.sailboatmod.network.packet.UseBuilderHammerPacket;
import com.monpai.sailboatmod.network.packet.SetClaimPermissionPacket;
import com.monpai.sailboatmod.network.packet.SetTownClaimPermissionPacket;
import com.monpai.sailboatmod.network.packet.SetDockZonePacket;
import com.monpai.sailboatmod.network.packet.SetHandlingPresetPacket;
import com.monpai.sailboatmod.network.packet.SetSailboatRentalPricePacket;
import com.monpai.sailboatmod.network.packet.SyncAutoRouteDocksPacket;
import com.monpai.sailboatmod.network.packet.SyncNationFlagChunkPacket;
import com.monpai.sailboatmod.network.packet.TownGuiActionPacket;
import com.monpai.sailboatmod.network.packet.ToggleSailPacket;
import com.monpai.sailboatmod.network.packet.UploadNationFlagChunkPacket;
import com.monpai.sailboatmod.network.packet.UploadTownFlagChunkPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SailboatMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        CHANNEL.registerMessage(
                packetId++,
                OpenSailboatStoragePacket.class,
                OpenSailboatStoragePacket::encode,
                OpenSailboatStoragePacket::decode,
                OpenSailboatStoragePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                RenameSailboatPacket.class,
                RenameSailboatPacket::encode,
                RenameSailboatPacket::decode,
                RenameSailboatPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SetSailboatRentalPricePacket.class,
                SetSailboatRentalPricePacket::encode,
                SetSailboatRentalPricePacket::decode,
                SetSailboatRentalPricePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SelectSailboatSeatPacket.class,
                SelectSailboatSeatPacket::encode,
                SelectSailboatSeatPacket::decode,
                SelectSailboatSeatPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                ToggleSailPacket.class,
                ToggleSailPacket::encode,
                ToggleSailPacket::decode,
                ToggleSailPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SetHandlingPresetPacket.class,
                SetHandlingPresetPacket::encode,
                SetHandlingPresetPacket::decode,
                SetHandlingPresetPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                ControlAutopilotPacket.class,
                ControlAutopilotPacket::encode,
                ControlAutopilotPacket::decode,
                ControlAutopilotPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenDockScreenPacket.class,
                OpenDockScreenPacket::encode,
                OpenDockScreenPacket::decode,
                OpenDockScreenPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                DockGuiActionPacket.class,
                DockGuiActionPacket::encode,
                DockGuiActionPacket::decode,
                DockGuiActionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                FinalizeRouteNamePacket.class,
                FinalizeRouteNamePacket::encode,
                FinalizeRouteNamePacket::decode,
                FinalizeRouteNamePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                RenameDockPacket.class,
                RenameDockPacket::encode,
                RenameDockPacket::decode,
                RenameDockPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SetDockZonePacket.class,
                SetDockZonePacket::encode,
                SetDockZonePacket::decode,
                SetDockZonePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenMarketScreenPacket.class,
                OpenMarketScreenPacket::encode,
                OpenMarketScreenPacket::decode,
                OpenMarketScreenPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                MarketGuiActionPacket.class,
                MarketGuiActionPacket::encode,
                MarketGuiActionPacket::decode,
                MarketGuiActionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                MarketStatusNoticePacket.class,
                MarketStatusNoticePacket::encode,
                MarketStatusNoticePacket::decode,
                MarketStatusNoticePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                CreateMarketListingPacket.class,
                CreateMarketListingPacket::encode,
                CreateMarketListingPacket::decode,
                CreateMarketListingPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                PurchaseMarketListingPacket.class,
                PurchaseMarketListingPacket::encode,
                PurchaseMarketListingPacket::decode,
                PurchaseMarketListingPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                DispatchMarketOrderPacket.class,
                DispatchMarketOrderPacket::encode,
                DispatchMarketOrderPacket::decode,
                DispatchMarketOrderPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                CancelMarketListingPacket.class,
                CancelMarketListingPacket::encode,
                CancelMarketListingPacket::decode,
                CancelMarketListingPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                ClaimMarketCreditsPacket.class,
                ClaimMarketCreditsPacket::encode,
                ClaimMarketCreditsPacket::decode,
                ClaimMarketCreditsPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenNationMenuPacket.class,
                OpenNationMenuPacket::encode,
                OpenNationMenuPacket::decode,
                OpenNationMenuPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenTownMenuPacket.class,
                OpenTownMenuPacket::encode,
                OpenTownMenuPacket::decode,
                OpenTownMenuPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                NationGuiActionPacket.class,
                NationGuiActionPacket::encode,
                NationGuiActionPacket::decode,
                NationGuiActionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                TownGuiActionPacket.class,
                TownGuiActionPacket::encode,
                TownGuiActionPacket::decode,
                TownGuiActionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenNationScreenPacket.class,
                OpenNationScreenPacket::encode,
                OpenNationScreenPacket::decode,
                OpenNationScreenPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenTownScreenPacket.class,
                OpenTownScreenPacket::encode,
                OpenTownScreenPacket::decode,
                OpenTownScreenPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SetClaimPermissionPacket.class,
                SetClaimPermissionPacket::encode,
                SetClaimPermissionPacket::decode,
                SetClaimPermissionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SetTownClaimPermissionPacket.class,
                SetTownClaimPermissionPacket::encode,
                SetTownClaimPermissionPacket::decode,
                SetTownClaimPermissionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncNationFlagChunkPacket.class,
                SyncNationFlagChunkPacket::encode,
                SyncNationFlagChunkPacket::decode,
                SyncNationFlagChunkPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                UploadNationFlagChunkPacket.class,
                UploadNationFlagChunkPacket::encode,
                UploadNationFlagChunkPacket::decode,
                UploadNationFlagChunkPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                UploadTownFlagChunkPacket.class,
                UploadTownFlagChunkPacket::encode,
                UploadTownFlagChunkPacket::decode,
                UploadTownFlagChunkPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                NationToastPacket.class,
                NationToastPacket::encode,
                NationToastPacket::decode,
                NationToastPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                RenameMarketPacket.class,
                RenameMarketPacket::encode,
                RenameMarketPacket::decode,
                RenameMarketPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                BankActionPacket.class,
                BankActionPacket::encode,
                BankActionPacket::decode,
                BankActionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                PlaceBankStructurePacket.class,
                PlaceBankStructurePacket::encode,
                PlaceBankStructurePacket::decode,
                PlaceBankStructurePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncTreasuryPacket.class,
                SyncTreasuryPacket::encode,
                SyncTreasuryPacket::decode,
                SyncTreasuryPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenTradeScreenPacket.class,
                OpenTradeScreenPacket::encode,
                OpenTradeScreenPacket::decode,
                OpenTradeScreenPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                TradeScreenActionPacket.class,
                TradeScreenActionPacket::encode,
                TradeScreenActionPacket::decode,
                TradeScreenActionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncConstructorSettingsPacket.class,
                SyncConstructorSettingsPacket::encode,
                SyncConstructorSettingsPacket::decode,
                SyncConstructorSettingsPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                RequestAutoRouteDocksPacket.class,
                RequestAutoRouteDocksPacket::encode,
                RequestAutoRouteDocksPacket::decode,
                RequestAutoRouteDocksPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncAutoRouteDocksPacket.class,
                SyncAutoRouteDocksPacket::encode,
                SyncAutoRouteDocksPacket::decode,
                SyncAutoRouteDocksPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                CreateAutoRoutePacket.class,
                CreateAutoRoutePacket::encode,
                CreateAutoRoutePacket::decode,
                CreateAutoRoutePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                BuildingUpgradePacket.class,
                BuildingUpgradePacket::encode,
                BuildingUpgradePacket::decode,
                BuildingUpgradePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                ResidentActionPacket.class,
                ResidentActionPacket::encode,
                ResidentActionPacket::decode,
                ResidentActionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenResidentScreenPacket.class,
                OpenResidentScreenPacket::encode,
                OpenResidentScreenPacket::decode,
                OpenResidentScreenPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncConstructionProgressPacket.class,
                SyncConstructionProgressPacket::encode,
                SyncConstructionProgressPacket::decode,
                SyncConstructionProgressPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncConstructionGhostPreviewPacket.class,
                SyncConstructionGhostPreviewPacket::encode,
                SyncConstructionGhostPreviewPacket::decode,
                SyncConstructionGhostPreviewPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                CreateBuyOrderPacket.class,
                CreateBuyOrderPacket::encode,
                CreateBuyOrderPacket::decode,
                CreateBuyOrderPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                CancelBuyOrderPacket.class,
                CancelBuyOrderPacket::encode,
                CancelBuyOrderPacket::decode,
                CancelBuyOrderPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                ClearTerrainCachePacket.class,
                ClearTerrainCachePacket::encode,
                ClearTerrainCachePacket::decode,
                ClearTerrainCachePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                CopyMarketWebTokenPacket.class,
                CopyMarketWebTokenPacket::encode,
                CopyMarketWebTokenPacket::decode,
                CopyMarketWebTokenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenRoadPlannerScreenPacket.class,
                OpenRoadPlannerScreenPacket::encode,
                OpenRoadPlannerScreenPacket::decode,
                OpenRoadPlannerScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                SelectRoadPlannerTargetPacket.class,
                SelectRoadPlannerTargetPacket::encode,
                SelectRoadPlannerTargetPacket::decode,
                SelectRoadPlannerTargetPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SelectRoadPlannerPreviewOptionPacket.class,
                SelectRoadPlannerPreviewOptionPacket::encode,
                SelectRoadPlannerPreviewOptionPacket::decode,
                SelectRoadPlannerPreviewOptionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncRoadPlannerPreviewPacket.class,
                SyncRoadPlannerPreviewPacket::encode,
                SyncRoadPlannerPreviewPacket::decode,
                SyncRoadPlannerPreviewPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncManualRoadPlanningProgressPacket.class,
                SyncManualRoadPlanningProgressPacket::encode,
                SyncManualRoadPlanningProgressPacket::decode,
                SyncManualRoadPlanningProgressPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncRoadConstructionProgressPacket.class,
                SyncRoadConstructionProgressPacket::encode,
                SyncRoadConstructionProgressPacket::decode,
                SyncRoadConstructionProgressPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                UseBuilderHammerPacket.class,
                UseBuilderHammerPacket::encode,
                UseBuilderHammerPacket::decode,
                UseBuilderHammerPacket::handle
        );
    }

    private ModNetwork() {
    }
}
