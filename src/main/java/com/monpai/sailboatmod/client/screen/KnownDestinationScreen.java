package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.vehicle.DestinationPicker;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.*;

/** One row click issues departure; there is deliberately no "plan/create route" button. */
public final class KnownDestinationScreen extends ThemedScreen {
    private final Screen parent;private final BlockPos source;private final UUID vessel;
    private final boolean aboard;private final int cargo;private UUID query;
    private boolean autoReturn,autoUnload,submitted,departed,initialised;
    private ScrollerView list;private Label status;
    private List<KnownDestinationsPacket.Port> ports=List.of();
    public KnownDestinationScreen(Screen parent,BlockPos source,UUID vessel,boolean aboard,int cargo){
        super(DestinationPicker.title());
        this.parent=parent;this.source=source;this.vessel=vessel;this.aboard=aboard;this.cargo=cargo;
    }
    public static void openShip(SailboatEntity ship){
        Minecraft mc=Minecraft.getInstance();mc.setScreen(new KnownDestinationScreen(mc.screen,BlockPos.ZERO,ship.getUUID(),true,-1));
    }
    @Override protected float windowWidth(){return 380;}
    @Override protected UIElement buildBody(){
        UIElement body=Ui.column();
        status=Ui.text("读取已知航路网络…","sm-muted");
        UIElement options=Ui.row();
        var returnButton=Ui.button(Component.literal("自动返航：关"),Ui.Tone.SECONDARY,()->{autoReturn=!autoReturn;renderPorts();});
        var unloadButton=Ui.button(Component.literal("自动卸货：关"),Ui.Tone.SECONDARY,()->{autoUnload=!autoUnload;renderPorts();});
        // Tick text derives from these booleans; no UI callback is allowed to modify server state directly.
        this.returnButton=returnButton;this.unloadButton=unloadButton;
        returnButton.setActive(!aboard);options.addChildren(returnButton,unloadButton);
        list=Ui.scroller();list.layout(l->l.height(210).flexGrow(0));
        UIElement controls=Ui.row();controls.addChildren(
                Ui.button(Component.literal("刷新目的地"),Ui.Tone.SECONDARY,this::request),
                Ui.button(Component.literal("返回"),Ui.Tone.SECONDARY,this::onClose));
        body.addChildren(status,options,list,Ui.paragraph(Component.literal(
                aboard?"只列出附近航路可通达的港口。接入前仍需验证水域；不会自动建新航线。"
                        :"只显示已有航路连通的港口；不连通时请先在航线管理中创建航线。"),"sm-muted"),controls);
        return body;
    }
    private com.lowdragmc.lowdraglib2.gui.ui.elements.Button returnButton,unloadButton;
    @Override protected void afterInit(){if(!initialised){initialised=true;request();}}
    private void request(){
        if(query!=null)send(2,BlockPos.ZERO);
        query=UUID.randomUUID();submitted=false;ports=List.of();
        status.setValue(Component.literal("读取已知航路网络…"));renderPorts();send(0,BlockPos.ZERO);
    }
    private void send(int action,BlockPos target){
        ModNetwork.CHANNEL.sendToServer(new KnownVoyageMenuPacket(action,query,vessel,source,aboard,cargo,target,autoReturn,autoUnload));
    }
    public static void receiveCurrent(KnownDestinationsPacket packet){
        if(Minecraft.getInstance().screen instanceof KnownDestinationScreen screen&&packet.query().equals(screen.query))screen.receive(packet);
    }
    private void receive(KnownDestinationsPacket p){
        status.setValue(Component.literal(p.message()));ports=p.ports();departed=p.departed();
        if(departed){if(Minecraft.getInstance().player!=null)Minecraft.getInstance().player.displayClientMessage(Component.literal(p.message()),false);onClose();return;}
        renderPorts();
    }
    private void renderPorts(){
        if(list==null)return;
        returnButton.setText(Component.literal(aboard?"海上出发不自动返港":"自动返航："+(autoReturn?"开":"关")));
        unloadButton.setText(Component.literal("自动卸货："+(autoUnload?"开":"关")));
        list.clearAllScrollViewChildren();
        for(var port:ports){
            boolean canReturn = !autoReturn || port.returnAllowed();
            Component note = canReturn ? Component.empty() : Component.literal("（当前航程不支持原路返航）");
            DestinationPicker.add(list, port.name(), port.distance(), note, !submitted && canReturn, ()->{
                if(submitted)return;submitted=true;status.setValue(Component.literal(aboard?"验证短接入段…":"正在出发…"));renderPorts();send(1,port.position());
            });
        }
        if(ports.isEmpty())list.addScrollViewChild(Ui.emptyState(Component.literal("没有可选择的已知目的地")));
    }
    @Override public void onClose(){if(query!=null&&!departed)send(2,BlockPos.ZERO);Minecraft.getInstance().setScreen(parent);}
}
