package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.TabBar;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CreateAutoRoutePacket;
import com.monpai.sailboatmod.network.packet.RequestAutoRouteDocksPacket;
import com.monpai.sailboatmod.route.water.WaterMidMode;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the destination for an automatic route. Ports also choose the mid-route pathfinding mode (default hybrid).
 * "Create" stays disabled until a destination is selected and sends the same {@link CreateAutoRoutePacket}.
 */
public class AutoRouteDockSelectionScreen extends ThemedScreen {
    private final BlockPos sourceDockPos;
    private final TransportTerminalKind terminalKind;
    private List<AvailableDockEntry> docks;
    private ScrollerView dockList;
    private final List<UIElement> rows = new ArrayList<>();
    private int selectedIndex = -1;
    private WaterMidMode selectedMode = WaterMidMode.NBT;
    private TabBar<WaterMidMode> modeTabs;
    private Label modeDescription;
    private Button createButton;
    private Button retryButton,cancelWorkButton;
    private com.monpai.sailboatmod.client.ui.kit.MeterBar progressBar;
    private Label progressLabel;
    private java.util.UUID listRequest=new java.util.UUID(0,0),routeRequest=new java.util.UUID(0,0);
    private boolean listing,building;
    private int progressPercent,progressState;
    private String progressStage="准备中";
    private long progressStarted;
    private java.util.UUID recoveryQuery;



    public AutoRouteDockSelectionScreen(BlockPos sourceDockPos, TransportTerminalKind terminalKind, List<AvailableDockEntry> docks) {
        super(Component.translatable(baseKey(terminalKind) + ".title"));
        this.sourceDockPos = sourceDockPos;
        this.terminalKind = terminalKind == null ? TransportTerminalKind.PORT : terminalKind;
        this.docks = docks == null ? List.of() : docks;
    }

    @Override
    protected float windowWidth() {
        return 320;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        body.addChild(Ui.paragraph(autoRouteText("subtitle"), "sm-muted"));

        dockList = Ui.scroller();
        dockList.layout(l -> l.height(150).flexGrow(0));
        rebuildRows();
        body.addChild(dockList);
        progressLabel=Ui.text("准备中","sm-muted");
        progressBar=new com.monpai.sailboatmod.client.ui.kit.MeterBar();
        progressBar.layout(l->l.widthPercent(100).height(8));
        body.addChildren(progressLabel,progressBar);


        if (terminalKind == TransportTerminalKind.PORT) {
            body.addChild(Ui.paragraph(Component.literal("后台异步规划：真实方块 / NBT 优先，Tellus 补足未生成地形。"),"sm-muted"));
            if (net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().isLoaded(com.monpai.sailboatmod.route.terrain.TellusWorlds.MOD_ID)) {
                // Tellus 地球世界:服务器忽略上面的模式,一律用真实地形 + 既有方块规划(协定不变,只做说明)。
                body.addChild(Ui.paragraph(Component.translatable("screen.sailboatmod.ui.route_mode.tellus_hint"), "sm-muted"));
            }
        }

        createButton = Ui.button(autoRouteText("create"), Ui.Tone.PRIMARY, this::createRoute);
        UIElement actions = Ui.row();
        actions.layout(l -> l.widthPercent(100).minWidth(0).gapAll(Ui.GAP_S)
                .flexWrap(dev.vfyjxf.taffy.style.FlexWrap.WRAP).flexShrink(0));
        actions.addChildren(Ui.clippedText(Component.literal("关闭窗口后航线继续；取消任务会停止计算"), "sm-muted"), Ui.spacer(),
                Ui.button(Component.translatable("screen.sailboatmod.route_name.cancel"), Ui.Tone.SECONDARY, this::onClose), createButton);
        retryButton=Ui.button(Component.literal("重新验证"),Ui.Tone.SECONDARY,this::requestValidation);
        cancelWorkButton=Ui.button(Component.literal("取消任务"),Ui.Tone.DANGER,this::cancelWork);
        actions.addChildren(retryButton,cancelWorkButton,
                Ui.button(Component.literal("寻路任务队列"), Ui.Tone.SECONDARY, this::openTaskQueue));
        body.addChild(actions);
        refresh();
        return body;
    }

    public boolean isForDock(BlockPos pos, TransportTerminalKind kind) {
        return sourceDockPos.equals(pos) && terminalKind == kind;
    }

    /** A late terrain update must preserve selection and the chosen route mode. */
    public void updateDocks(List<AvailableDockEntry> updated) {
        BlockPos selected = selectedIndex >= 0 && selectedIndex < docks.size() ? docks.get(selectedIndex).pos() : null;
        docks = updated == null ? List.of() : List.copyOf(updated);
        selectedIndex = -1;
        for (int i = 0; i < docks.size(); i++) if (docks.get(i).pos().equals(selected)) selectedIndex = i;
        rebuildRows();
        refresh();
    }

    private void rebuildRows() {
        if (dockList == null) return;
        dockList.clearAllScrollViewChildren();
        rows.clear();
        if (docks.isEmpty()) dockList.addScrollViewChild(Ui.emptyState(autoRouteText("empty")));
        for (int i = 0; i < docks.size(); i++) {
            int index = i;
            UIElement row = dockRow(docks.get(i));
            Ui.clickable(row, () -> select(index));
            rows.add(row);
            dockList.addScrollViewChild(row);
        }
    }

    private UIElement dockRow(AvailableDockEntry dock) {
        UIElement row = Ui.row();
        row.addClass("sm-row");
        row.layout(l -> l.widthPercent(100).height(22).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).flexShrink(0));
        UIElement names = Ui.column();
        names.layout(l -> l.width(0).flexGrow(1).minWidth(0));
        names.addChildren(Ui.clippedText(dockName(dock.dockName())),
                Ui.clippedText(Component.translatable("screen.sailboatmod.ui.owner_nation",
                        fallback(dock.ownerName(), "screen.sailboatmod.auto_route.unknown_owner"),
                        fallback(dock.nationName(), "screen.sailboatmod.auto_route.no_nation")), "sm-muted"));
        row.addChildren(Ui.slot(new ItemStack(terminalKind == TransportTerminalKind.POST_STATION ? Items.MINECART : Items.OAK_BOAT), Ui.SLOT),
                names, Ui.alignRight(Ui.text(dock.distance() + "m", "sm-price")));
        row.style(s -> s.tooltips(rowText(dock)));
        return row;
    }

    private void select(int index) {
        selectedIndex = index;
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            Ui.setClass(rows.get(i), "selected", i == selectedIndex);
        }
        if (modeTabs != null) {
            modeTabs.select(selectedMode);
            modeDescription.setValue(Component.literal(selectedMode.label()));
        }
        if (createButton != null) {
            createButton.setActive(!building && selectedIndex >= 0 && selectedIndex < docks.size());
        }
    }

    private void createRoute() {
        if (selectedIndex >= 0 && selectedIndex < docks.size()) {
            // Freeze the selected destination and retire the old list request before tracking a build.
            // Otherwise a list completion hidden by build progress can leave the retry button disabled.
            if (listing) ModNetwork.CHANNEL.sendToServer(new com.monpai.sailboatmod.network.packet.CancelPortWorkPacket(listRequest,false));
            listing=false;
            listRequest=java.util.UUID.randomUUID();
            routeRequest=java.util.UUID.randomUUID();building=true;progressStarted=ticks();
            progressPercent=0;progressState=0;progressStage="排队 / 异步载入码头";
            ModNetwork.CHANNEL.sendToServer(new CreateAutoRoutePacket(sourceDockPos,docks.get(selectedIndex).pos(),selectedMode,routeRequest));
            refreshProgress();refresh();
            return;
        }
        toast.show(autoRouteText("select_target"), false, ticks());
    }

    public boolean acceptsList(java.util.UUID id){return listRequest.equals(id);}
    public void requestValidation(){
        if(building)return;
        listRequest=java.util.UUID.randomUUID();listing=true;progressStarted=ticks();progressPercent=0;progressState=0;
        progressStage="读取码头元数据 / 准备地形";
        ModNetwork.CHANNEL.sendToServer(new RequestAutoRouteDocksPacket(sourceDockPos,listRequest));refreshProgress();
    }
    public void updateProgress(com.monpai.sailboatmod.network.packet.RouteWorkProgressPacket p){
        if(!p.matches(sourceDockPos,listRequest,routeRequest))return;
        if(building&&!p.route())return;
        progressPercent=p.percent();progressState=p.state();
        progressStage=p.stage()+(p.total()>0?" ("+p.checked()+"/"+p.total()+")":"");
        if(p.route())building=p.state()==0;else listing=p.state()==0;
        refreshProgress();refresh();
    }
    private void refreshProgress(){
        if(progressBar==null)return;
        int color=progressState>=2?0xFFE09A59:progressState==1?0xFF58B58D:0xFF7AA2C6;
        progressBar.set(progressPercent/100f,color);
        progressLabel.setValue(Component.literal(progressStage+" · "+progressPercent+"%"+
                ((listing||building)?" · "+((ticks()-progressStarted)/20)+"秒":"")));
        if(retryButton!=null)retryButton.setActive(!building&&!listing);
        if(cancelWorkButton!=null)cancelWorkButton.setActive(building||listing);
    }
    private void cancelWork(){
        boolean route=building;
        ModNetwork.CHANNEL.sendToServer(new com.monpai.sailboatmod.network.packet.CancelPortWorkPacket(route?routeRequest:listRequest,route));
        // Keep the stable route ID until the server confirms cancellation.
        if (!route) { listing=false; listRequest=java.util.UUID.randomUUID(); }
        progressStage="已请求取消，等待服务端确认";refreshProgress();refresh();
    }
    @Override protected void onUiTick(){refreshProgress();}
    @Override public void onClose(){
        if(listing)ModNetwork.CHANNEL.sendToServer(new com.monpai.sailboatmod.network.packet.CancelPortWorkPacket(listRequest,false));
        // A route keeps running when the dialog closes; the top-screen boss bar remains visible.
        super.onClose();
    }

    @Override
    protected void afterInit() {
        refresh();
        recoveryQuery = java.util.UUID.randomUUID();
        ModNetwork.CHANNEL.sendToServer(new com.monpai.sailboatmod.network.packet.RequestRouteTasksPacket(recoveryQuery));
    }

    private void openTaskQueue() {
        if (listing) ModNetwork.CHANNEL.sendToServer(new com.monpai.sailboatmod.network.packet.CancelPortWorkPacket(listRequest,false));
        listing = false;
        net.minecraft.client.Minecraft.getInstance().setScreen(new RouteTaskQueueScreen(this));
    }

    public void restoreTasks(com.monpai.sailboatmod.network.packet.SyncRouteTasksPacket packet) {
        if (!packet.query().equals(recoveryQuery)) return;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return;
        // Prefer this window's running task when returning from the queue; otherwise restore the newest one.
        var task = packet.tasks().stream().filter(t -> t.id().equals(routeRequest)).findFirst().orElse(null);
        if (task == null) task = packet.tasks().stream().filter(t -> t.active() && t.source().equals(sourceDockPos)
                && t.dimension().equals(level.dimension().location().toString())).findFirst().orElse(null);
        if (task == null) return;
        if (listing) ModNetwork.CHANNEL.sendToServer(new com.monpai.sailboatmod.network.packet.CancelPortWorkPacket(listRequest,false));
        listing = false;
        listRequest = java.util.UUID.randomUUID();
        routeRequest = task.id();
        building = task.active();
        progressPercent = task.percent(); progressState = task.state(); progressStage = task.stage();
        progressStarted = ticks() - task.elapsedMillis(System.currentTimeMillis()) / 50L;
        refreshProgress(); refresh();
    }

    private Component rowText(AvailableDockEntry dock) {
        return Component.translatable(
                baseKey(terminalKind) + ".entry",
                dockName(dock.dockName()),
                fallback(dock.ownerName(), "screen.sailboatmod.auto_route.unknown_owner"),
                fallback(dock.nationName(), "screen.sailboatmod.auto_route.no_nation"),
                dock.distance()
        );
    }

    private Component dockName(String name) {
        if (name == null || name.isBlank()) {
            return Component.translatable(terminalKind == TransportTerminalKind.POST_STATION
                    ? "block.sailboatmod.post_station"
                    : "block.sailboatmod.dock");
        }
        return Component.literal(name);
    }

    private Component autoRouteText(String suffix, Object... args) {
        return Component.translatable(baseKey(terminalKind) + "." + suffix, args);
    }

    private static String baseKey(TransportTerminalKind terminalKind) {
        return terminalKind == TransportTerminalKind.POST_STATION
                ? "screen.sailboatmod.auto_route.post_station"
                : "screen.sailboatmod.auto_route.port";
    }

    private static Component fallback(String value, String key) {
        if (value == null || value.isBlank()) {
            return Component.translatable(key);
        }
        return Component.literal(value);
    }
}
