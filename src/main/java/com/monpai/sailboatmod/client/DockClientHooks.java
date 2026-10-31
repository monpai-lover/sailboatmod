package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.AutoRouteDockSelectionScreen;
import com.monpai.sailboatmod.client.screen.DockScreen;
import com.monpai.sailboatmod.client.screen.PostStationScreen;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class DockClientHooks {
    private static DockScreenData latest;

    public static void openOrUpdate(DockScreenData data) {
        latest = data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof DockScreen dockScreen && dockScreen.isForDock(data.dockPos())) {
            dockScreen.updateData(data);
        }
    }

    public static DockScreenData consumeFor(BlockPos pos) {
        if (latest != null && latest.dockPos().equals(pos)) {
            DockScreenData out = latest;
            latest = null;
            return out;
        }
        return null;
    }

    public static void beginAutoRoute(BlockPos pos,TransportTerminalKind kind) {
        var screen=new AutoRouteDockSelectionScreen(pos,kind,List.of());
        Minecraft.getInstance().setScreen(screen);
        screen.requestValidation();
    }
    public static void openAutoRouteDockSelection(BlockPos pos,TransportTerminalKind kind,List<AvailableDockEntry> docks) {
        openAutoRouteDockSelection(pos,kind,docks,new java.util.UUID(0,0));
    }
    public static void openAutoRouteDockSelection(BlockPos sourceDockPos, TransportTerminalKind terminalKind,
            List<AvailableDockEntry> docks,java.util.UUID requestId) {
        Minecraft minecraft=Minecraft.getInstance();
        if(minecraft.screen instanceof AutoRouteDockSelectionScreen selection
                &&selection.isForDock(sourceDockPos,terminalKind)&&selection.acceptsList(requestId)) {
            selection.updateDocks(docks);
        }
        // Only the explicit local click opens a screen; closed/unrelated screens ignore late packets.
    }
    public static void progress(com.monpai.sailboatmod.network.packet.RouteWorkProgressPacket packet) {
        if(Minecraft.getInstance().screen instanceof AutoRouteDockSelectionScreen screen)screen.updateProgress(packet);
    }

    public static void routeTasks(com.monpai.sailboatmod.network.packet.SyncRouteTasksPacket packet) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof com.monpai.sailboatmod.client.screen.RouteTaskQueueScreen queue) queue.receive(packet);
        else if (screen instanceof AutoRouteDockSelectionScreen selection) selection.restoreTasks(packet);
    }

    private DockClientHooks() {
    }
}
