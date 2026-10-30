package com.monpai.sailboatmod.network;

import com.monpai.sailboatmod.network.packet.BankActionPacket;
import com.monpai.sailboatmod.network.packet.CancelBuyOrderPacket;
import com.monpai.sailboatmod.network.packet.CancelMarketListingPacket;
import com.monpai.sailboatmod.network.packet.CancelPurchaseOrderPacket;
import com.monpai.sailboatmod.network.packet.CarriageControlInputPacket;
import com.monpai.sailboatmod.network.packet.InteractCarriagePacket;
import com.monpai.sailboatmod.network.packet.ConfigureRoadPlannerPacket;
import com.monpai.sailboatmod.network.packet.CloseClaimMapViewportPacket;
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
import com.monpai.sailboatmod.network.packet.SetUnloadOnArrivalPacket;
import com.monpai.sailboatmod.network.packet.CreateBuyOrderPacket;
import com.monpai.sailboatmod.network.packet.CreateMarketListingPacket;
import com.monpai.sailboatmod.network.packet.DispatchMarketOrderPacket;
import com.monpai.sailboatmod.network.packet.DockGuiActionPacket;
import com.monpai.sailboatmod.network.packet.FinalizeRouteNamePacket;
import com.monpai.sailboatmod.network.packet.MarketGuiActionPacket;
import com.monpai.sailboatmod.network.packet.MarketWalletActionPacket;
import com.monpai.sailboatmod.network.packet.MarketStatusNoticePacket;
import com.monpai.sailboatmod.network.packet.NationGuiActionPacket;
import com.monpai.sailboatmod.network.packet.NationToastPacket;
import com.monpai.sailboatmod.network.packet.OpenDockScreenPacket;
import com.monpai.sailboatmod.network.packet.OpenMarketScreenPacket;
import com.monpai.sailboatmod.network.packet.OpenNationMenuPacket;
import com.monpai.sailboatmod.network.packet.OpenNationScreenPacket;
import com.monpai.sailboatmod.network.packet.OpenPostStationScreenPacket;
import com.monpai.sailboatmod.network.packet.OpenTownMenuPacket;
import com.monpai.sailboatmod.network.packet.OpenTownScreenPacket;
import com.monpai.sailboatmod.network.packet.OpenSailboatStoragePacket;
import com.monpai.sailboatmod.network.packet.PostStationGuiActionPacket;
import com.monpai.sailboatmod.network.packet.ProbeFulfillmentModesPacket;
import com.monpai.sailboatmod.network.packet.ProbeFulfillmentModesResultPacket;
import com.monpai.sailboatmod.network.packet.RefreshClaimMapViewportPacket;
import com.monpai.sailboatmod.network.packet.PurchaseMarketListingPacket;
import com.monpai.sailboatmod.network.packet.RenameDockPacket;
import com.monpai.sailboatmod.network.packet.RenamePostStationPacket;
import com.monpai.sailboatmod.network.packet.RenameMarketPacket;
import com.monpai.sailboatmod.network.packet.RenameSailboatPacket;
import com.monpai.sailboatmod.network.packet.RequestClaimMapViewportPacket;
import com.monpai.sailboatmod.network.packet.RequestAutoRouteDocksPacket;
import com.monpai.sailboatmod.network.packet.OpenRoadPlannerScreenPacket;
import com.monpai.sailboatmod.network.packet.SailboatControlInputPacket;
import com.monpai.sailboatmod.network.packet.SelectRoadPlannerTargetPacket;
import com.monpai.sailboatmod.network.packet.SelectRoadPlannerPreviewOptionPacket;
import com.monpai.sailboatmod.network.packet.SelectSailboatSeatPacket;
import com.monpai.sailboatmod.network.packet.SyncConstructionProgressPacket;
import com.monpai.sailboatmod.network.packet.SyncConstructionGhostPreviewPacket;
import com.monpai.sailboatmod.network.packet.SyncConstructorSettingsPacket;
import com.monpai.sailboatmod.network.packet.SyncClaimPreviewMapPacket;
import com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket;
import com.monpai.sailboatmod.network.packet.SyncRoadConstructionProgressPacket;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerResultPacket;
import com.monpai.sailboatmod.network.packet.UseBuilderHammerPacket;
import com.monpai.sailboatmod.network.packet.SetClaimPermissionPacket;
import com.monpai.sailboatmod.network.packet.SetTownClaimPermissionPacket;
import com.monpai.sailboatmod.network.packet.SetDockZonePacket;
import com.monpai.sailboatmod.network.packet.SetHandlingPresetPacket;
import com.monpai.sailboatmod.network.packet.SetSailboatRentalPricePacket;
import com.monpai.sailboatmod.network.packet.SyncAutoRouteDocksPacket;
import com.monpai.sailboatmod.network.packet.SyncClaimHighlightsPacket;
import com.monpai.sailboatmod.network.packet.SyncNationFlagChunkPacket;
import com.monpai.sailboatmod.network.packet.TownGuiActionPacket;
import com.monpai.sailboatmod.network.packet.ToggleSailPacket;
import com.monpai.sailboatmod.network.packet.UploadNationFlagChunkPacket;
import com.monpai.sailboatmod.network.packet.UploadTownFlagChunkPacket;
import com.monpai.sailboatmod.network.packet.marketweb.MarketWebMapTileUploadPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadDemolitionSelectionPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadEditSelectionPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadMergeCandidatesPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadPlannerEditScreenPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadPlannerActionMenuPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadCancelPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadProgressPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoCompleteRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoCompleteResultPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerCancelJobPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerConfirmBuildPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerDemolishRoadPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerEditCommitPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerGraphSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMenuActionPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMergeCandidateRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRegionNavigationPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRenameRoadPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlayRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlaySyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerSelectDemolitionRoadPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerSelectEditRoadPacket;
import net.neoforged.bus.api.IEventBus;
import java.util.Optional;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "16"; // Live market screen updates.
    public static final PayloadChannel CHANNEL = new PayloadChannel(PROTOCOL_VERSION);

    private static int packetId = 0;

    public static void register(IEventBus modEventBus) {
        registerPackets();
        modEventBus.addListener(CHANNEL::registerPayloads);
    }

    private static void registerPackets() {
        CHANNEL.registerMessage(
                packetId++,
                OpenSailboatStoragePacket.class,
                OpenSailboatStoragePacket::encode,
                OpenSailboatStoragePacket::decode,
                OpenSailboatStoragePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RenameSailboatPacket.class,
                RenameSailboatPacket::encode,
                RenameSailboatPacket::decode,
                RenameSailboatPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SetSailboatRentalPricePacket.class,
                SetSailboatRentalPricePacket::encode,
                SetSailboatRentalPricePacket::decode,
                SetSailboatRentalPricePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SelectSailboatSeatPacket.class,
                SelectSailboatSeatPacket::encode,
                SelectSailboatSeatPacket::decode,
                SelectSailboatSeatPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                ToggleSailPacket.class,
                ToggleSailPacket::encode,
                ToggleSailPacket::decode,
                ToggleSailPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SetHandlingPresetPacket.class,
                SetHandlingPresetPacket::encode,
                SetHandlingPresetPacket::decode,
                SetHandlingPresetPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                ControlAutopilotPacket.class,
                ControlAutopilotPacket::encode,
                ControlAutopilotPacket::decode,
                ControlAutopilotPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                CarriageControlInputPacket.class,
                CarriageControlInputPacket::encode,
                CarriageControlInputPacket::decode,
                CarriageControlInputPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SailboatControlInputPacket.class,
                SailboatControlInputPacket::encode,
                SailboatControlInputPacket::decode,
                SailboatControlInputPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenDockScreenPacket.class,
                OpenDockScreenPacket::encode,
                OpenDockScreenPacket::decode,
                OpenDockScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                DockGuiActionPacket.class,
                DockGuiActionPacket::encode,
                DockGuiActionPacket::decode,
                DockGuiActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenPostStationScreenPacket.class,
                OpenPostStationScreenPacket::encode,
                OpenPostStationScreenPacket::decode,
                OpenPostStationScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                PostStationGuiActionPacket.class,
                PostStationGuiActionPacket::encode,
                PostStationGuiActionPacket::decode,
                PostStationGuiActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                FinalizeRouteNamePacket.class,
                FinalizeRouteNamePacket::encode,
                FinalizeRouteNamePacket::decode,
                FinalizeRouteNamePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RenameDockPacket.class,
                RenameDockPacket::encode,
                RenameDockPacket::decode,
                RenameDockPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SetDockZonePacket.class,
                SetDockZonePacket::encode,
                SetDockZonePacket::decode,
                SetDockZonePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenMarketScreenPacket.class,
                OpenMarketScreenPacket::encode,
                OpenMarketScreenPacket::decode,
                OpenMarketScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                MarketGuiActionPacket.class,
                MarketGuiActionPacket::encode,
                MarketGuiActionPacket::decode,
                MarketGuiActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
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
                CreateMarketListingPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                PurchaseMarketListingPacket.class,
                PurchaseMarketListingPacket::encode,
                PurchaseMarketListingPacket::decode,
                PurchaseMarketListingPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                DispatchMarketOrderPacket.class,
                DispatchMarketOrderPacket::encode,
                DispatchMarketOrderPacket::decode,
                DispatchMarketOrderPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                CancelMarketListingPacket.class,
                CancelMarketListingPacket::encode,
                CancelMarketListingPacket::decode,
                CancelMarketListingPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                ClaimMarketCreditsPacket.class,
                ClaimMarketCreditsPacket::encode,
                ClaimMarketCreditsPacket::decode,
                ClaimMarketCreditsPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                MarketWalletActionPacket.class,
                MarketWalletActionPacket::encode,
                MarketWalletActionPacket::decode,
                MarketWalletActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenNationMenuPacket.class,
                OpenNationMenuPacket::encode,
                OpenNationMenuPacket::decode,
                OpenNationMenuPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenTownMenuPacket.class,
                OpenTownMenuPacket::encode,
                OpenTownMenuPacket::decode,
                OpenTownMenuPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                NationGuiActionPacket.class,
                NationGuiActionPacket::encode,
                NationGuiActionPacket::decode,
                NationGuiActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                TownGuiActionPacket.class,
                TownGuiActionPacket::encode,
                TownGuiActionPacket::decode,
                TownGuiActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenNationScreenPacket.class,
                OpenNationScreenPacket::encode,
                OpenNationScreenPacket::decode,
                OpenNationScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenTownScreenPacket.class,
                OpenTownScreenPacket::encode,
                OpenTownScreenPacket::decode,
                OpenTownScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncClaimPreviewMapPacket.class,
                SyncClaimPreviewMapPacket::encode,
                SyncClaimPreviewMapPacket::decode,
                SyncClaimPreviewMapPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RequestClaimMapViewportPacket.class,
                RequestClaimMapViewportPacket::encode,
                RequestClaimMapViewportPacket::decode,
                RequestClaimMapViewportPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RefreshClaimMapViewportPacket.class,
                RefreshClaimMapViewportPacket::encode,
                RefreshClaimMapViewportPacket::decode,
                RefreshClaimMapViewportPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                CloseClaimMapViewportPacket.class,
                CloseClaimMapViewportPacket::encode,
                CloseClaimMapViewportPacket::decode,
                CloseClaimMapViewportPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SetClaimPermissionPacket.class,
                SetClaimPermissionPacket::encode,
                SetClaimPermissionPacket::decode,
                SetClaimPermissionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SetTownClaimPermissionPacket.class,
                SetTownClaimPermissionPacket::encode,
                SetTownClaimPermissionPacket::decode,
                SetTownClaimPermissionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncClaimHighlightsPacket.class,
                SyncClaimHighlightsPacket::encode,
                SyncClaimHighlightsPacket::decode,
                SyncClaimHighlightsPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncNationFlagChunkPacket.class,
                SyncNationFlagChunkPacket::encode,
                SyncNationFlagChunkPacket::decode,
                SyncNationFlagChunkPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                UploadNationFlagChunkPacket.class,
                UploadNationFlagChunkPacket::encode,
                UploadNationFlagChunkPacket::decode,
                UploadNationFlagChunkPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                UploadTownFlagChunkPacket.class,
                UploadTownFlagChunkPacket::encode,
                UploadTownFlagChunkPacket::decode,
                UploadTownFlagChunkPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                NationToastPacket.class,
                NationToastPacket::encode,
                NationToastPacket::decode,
                NationToastPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RenameMarketPacket.class,
                RenameMarketPacket::encode,
                RenameMarketPacket::decode,
                RenameMarketPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                BankActionPacket.class,
                BankActionPacket::encode,
                BankActionPacket::decode,
                BankActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                PlaceBankStructurePacket.class,
                PlaceBankStructurePacket::encode,
                PlaceBankStructurePacket::decode,
                PlaceBankStructurePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncTreasuryPacket.class,
                SyncTreasuryPacket::encode,
                SyncTreasuryPacket::decode,
                SyncTreasuryPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenTradeScreenPacket.class,
                OpenTradeScreenPacket::encode,
                OpenTradeScreenPacket::decode,
                OpenTradeScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                TradeScreenActionPacket.class,
                TradeScreenActionPacket::encode,
                TradeScreenActionPacket::decode,
                TradeScreenActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncConstructorSettingsPacket.class,
                SyncConstructorSettingsPacket::encode,
                SyncConstructorSettingsPacket::decode,
                SyncConstructorSettingsPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RequestAutoRouteDocksPacket.class,
                RequestAutoRouteDocksPacket::encode,
                RequestAutoRouteDocksPacket::decode,
                RequestAutoRouteDocksPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncAutoRouteDocksPacket.class,
                SyncAutoRouteDocksPacket::encode,
                SyncAutoRouteDocksPacket::decode,
                SyncAutoRouteDocksPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                CreateAutoRoutePacket.class,
                CreateAutoRoutePacket::encode,
                CreateAutoRoutePacket::decode,
                CreateAutoRoutePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                BuildingUpgradePacket.class,
                BuildingUpgradePacket::encode,
                BuildingUpgradePacket::decode,
                BuildingUpgradePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                ResidentActionPacket.class,
                ResidentActionPacket::encode,
                ResidentActionPacket::decode,
                ResidentActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenResidentScreenPacket.class,
                OpenResidentScreenPacket::encode,
                OpenResidentScreenPacket::decode,
                OpenResidentScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
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
                CreateBuyOrderPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                CancelBuyOrderPacket.class,
                CancelBuyOrderPacket::encode,
                CancelBuyOrderPacket::decode,
                CancelBuyOrderPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
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
                SelectRoadPlannerTargetPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SelectRoadPlannerPreviewOptionPacket.class,
                SelectRoadPlannerPreviewOptionPacket::encode,
                SelectRoadPlannerPreviewOptionPacket::decode,
                SelectRoadPlannerPreviewOptionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                SyncRoadPlannerResultPacket.class,
                SyncRoadPlannerResultPacket::encode,
                SyncRoadPlannerResultPacket::decode,
                SyncRoadPlannerResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
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
                UseBuilderHammerPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                ConfigureRoadPlannerPacket.class,
                ConfigureRoadPlannerPacket::encode,
                ConfigureRoadPlannerPacket::decode,
                ConfigureRoadPlannerPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadMapSnapshotRequestPacket.class,
                RoadMapSnapshotRequestPacket::encode,
                RoadMapSnapshotRequestPacket::decode,
                RoadMapSnapshotRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadMapSnapshotSyncPacket.class,
                RoadMapSnapshotSyncPacket::encode,
                RoadMapSnapshotSyncPacket::decode,
                RoadMapSnapshotSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerMapPreloadRequestPacket.class,
                RoadPlannerMapPreloadRequestPacket::encode,
                RoadPlannerMapPreloadRequestPacket::decode,
                RoadPlannerMapPreloadRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerMapPreloadCancelPacket.class,
                RoadPlannerMapPreloadCancelPacket::encode,
                RoadPlannerMapPreloadCancelPacket::decode,
                RoadPlannerMapPreloadCancelPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerMapPreloadProgressPacket.class,
                RoadPlannerMapPreloadProgressPacket::encode,
                RoadPlannerMapPreloadProgressPacket::decode,
                RoadPlannerMapPreloadProgressPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerMapTileSyncPacket.class,
                RoadPlannerMapTileSyncPacket::encode,
                RoadPlannerMapTileSyncPacket::decode,
                RoadPlannerMapTileSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                MarketWebMapTileUploadPacket.class,
                MarketWebMapTileUploadPacket::encode,
                MarketWebMapTileUploadPacket::decode,
                MarketWebMapTileUploadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerRegionNavigationPacket.class,
                RoadPlannerRegionNavigationPacket::encode,
                RoadPlannerRegionNavigationPacket::decode,
                RoadPlannerRegionNavigationPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenRoadPlannerActionMenuPacket.class,
                OpenRoadPlannerActionMenuPacket::encode,
                OpenRoadPlannerActionMenuPacket::decode,
                OpenRoadPlannerActionMenuPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerConfirmBuildPacket.class,
                RoadPlannerConfirmBuildPacket::encode,
                RoadPlannerConfirmBuildPacket::decode,
                RoadPlannerConfirmBuildPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerAutoCompleteRequestPacket.class,
                RoadPlannerAutoCompleteRequestPacket::encode,
                RoadPlannerAutoCompleteRequestPacket::decode,
                RoadPlannerAutoCompleteRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerAutoCompleteResultPacket.class,
                RoadPlannerAutoCompleteResultPacket::encode,
                RoadPlannerAutoCompleteResultPacket::decode,
                RoadPlannerAutoCompleteResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerPreviewRequestPacket.class,
                RoadPlannerPreviewRequestPacket::encode,
                RoadPlannerPreviewRequestPacket::decode,
                RoadPlannerPreviewRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerMenuActionPacket.class,
                RoadPlannerMenuActionPacket::encode,
                RoadPlannerMenuActionPacket::decode,
                RoadPlannerMenuActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerCancelJobPacket.class,
                RoadPlannerCancelJobPacket::encode,
                RoadPlannerCancelJobPacket::decode,
                RoadPlannerCancelJobPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerRenameRoadPacket.class,
                RoadPlannerRenameRoadPacket::encode,
                RoadPlannerRenameRoadPacket::decode,
                RoadPlannerRenameRoadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerDemolishRoadPacket.class,
                RoadPlannerDemolishRoadPacket::encode,
                RoadPlannerDemolishRoadPacket::decode,
                RoadPlannerDemolishRoadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerGraphSyncPacket.class,
                RoadPlannerGraphSyncPacket::encode,
                RoadPlannerGraphSyncPacket::decode,
                RoadPlannerGraphSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenRoadDemolitionSelectionPacket.class,
                OpenRoadDemolitionSelectionPacket::encode,
                OpenRoadDemolitionSelectionPacket::decode,
                OpenRoadDemolitionSelectionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerSelectDemolitionRoadPacket.class,
                RoadPlannerSelectDemolitionRoadPacket::encode,
                RoadPlannerSelectDemolitionRoadPacket::decode,
                RoadPlannerSelectDemolitionRoadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenRoadEditSelectionPacket.class,
                OpenRoadEditSelectionPacket::encode,
                OpenRoadEditSelectionPacket::decode,
                OpenRoadEditSelectionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerSelectEditRoadPacket.class,
                RoadPlannerSelectEditRoadPacket::encode,
                RoadPlannerSelectEditRoadPacket::decode,
                RoadPlannerSelectEditRoadPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenRoadPlannerEditScreenPacket.class,
                OpenRoadPlannerEditScreenPacket::encode,
                OpenRoadPlannerEditScreenPacket::decode,
                OpenRoadPlannerEditScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerEditCommitPacket.class,
                RoadPlannerEditCommitPacket::encode,
                RoadPlannerEditCommitPacket::decode,
                RoadPlannerEditCommitPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerMergeCandidateRequestPacket.class,
                RoadPlannerMergeCandidateRequestPacket::encode,
                RoadPlannerMergeCandidateRequestPacket::decode,
                RoadPlannerMergeCandidateRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                OpenRoadMergeCandidatesPacket.class,
                OpenRoadMergeCandidatesPacket::encode,
                OpenRoadMergeCandidatesPacket::decode,
                OpenRoadMergeCandidatesPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerAutoMergeRouteRequestPacket.class,
                RoadPlannerAutoMergeRouteRequestPacket::encode,
                RoadPlannerAutoMergeRouteRequestPacket::decode,
                RoadPlannerAutoMergeRouteRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerAutoMergeRouteSyncPacket.class,
                RoadPlannerAutoMergeRouteSyncPacket::encode,
                RoadPlannerAutoMergeRouteSyncPacket::decode,
                RoadPlannerAutoMergeRouteSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerRoadOverlayRequestPacket.class,
                RoadPlannerRoadOverlayRequestPacket::encode,
                RoadPlannerRoadOverlayRequestPacket::decode,
                RoadPlannerRoadOverlayRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RoadPlannerRoadOverlaySyncPacket.class,
                RoadPlannerRoadOverlaySyncPacket::encode,
                RoadPlannerRoadOverlaySyncPacket::decode,
                RoadPlannerRoadOverlaySyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                SetUnloadOnArrivalPacket.class,
                SetUnloadOnArrivalPacket::encode,
                SetUnloadOnArrivalPacket::decode,
                SetUnloadOnArrivalPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                ProbeFulfillmentModesPacket.class,
                ProbeFulfillmentModesPacket::encode,
                ProbeFulfillmentModesPacket::decode,
                ProbeFulfillmentModesPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                ProbeFulfillmentModesResultPacket.class,
                ProbeFulfillmentModesResultPacket::encode,
                ProbeFulfillmentModesResultPacket::decode,
                ProbeFulfillmentModesResultPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                CancelPurchaseOrderPacket.class,
                CancelPurchaseOrderPacket::encode,
                CancelPurchaseOrderPacket::decode,
                CancelPurchaseOrderPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                RenamePostStationPacket.class,
                RenamePostStationPacket::encode,
                RenamePostStationPacket::decode,
                RenamePostStationPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPauseBuildPacket.class,
                com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPauseBuildPacket::encode,
                com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPauseBuildPacket::decode,
                com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPauseBuildPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                packetId++,
                InteractCarriagePacket.class,
                InteractCarriagePacket::encode,
                InteractCarriagePacket::decode,
                InteractCarriagePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(packetId++,
                com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainRequestPacket.class,
                com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainRequestPacket::encode,
                com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainRequestPacket::decode,
                com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++,
                com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainPatchPacket.class,
                com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainPatchPacket::encode,
                com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainPatchPacket::decode,
                com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainPatchPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(packetId++,
                com.monpai.sailboatmod.network.packet.OpenRegisteredVehicleControllerPacket.class,
                com.monpai.sailboatmod.network.packet.OpenRegisteredVehicleControllerPacket::encode,
                com.monpai.sailboatmod.network.packet.OpenRegisteredVehicleControllerPacket::decode,
                com.monpai.sailboatmod.network.packet.OpenRegisteredVehicleControllerPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(packetId++,
                com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket.class,
                com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket::encode,
                com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket::decode,
                com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++,
                com.monpai.sailboatmod.network.packet.maproute.MapRouteViewportRequestPacket.class,
                com.monpai.sailboatmod.network.packet.maproute.MapRouteViewportRequestPacket::encode,
                com.monpai.sailboatmod.network.packet.maproute.MapRouteViewportRequestPacket::decode,
                com.monpai.sailboatmod.network.packet.maproute.MapRouteViewportRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++,
                com.monpai.sailboatmod.network.packet.maproute.MapRouteSnapshotPacket.class,
                com.monpai.sailboatmod.network.packet.maproute.MapRouteSnapshotPacket::encode,
                com.monpai.sailboatmod.network.packet.maproute.MapRouteSnapshotPacket::decode,
                com.monpai.sailboatmod.network.packet.maproute.MapRouteSnapshotPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(packetId++,
                com.monpai.sailboatmod.network.packet.MarketLiveUpdatePacket.class,
                com.monpai.sailboatmod.network.packet.MarketLiveUpdatePacket::encode,
                com.monpai.sailboatmod.network.packet.MarketLiveUpdatePacket::decode,
                com.monpai.sailboatmod.network.packet.MarketLiveUpdatePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private ModNetwork() {
    }
}
