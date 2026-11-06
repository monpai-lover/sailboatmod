package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.screen.AirportScreen;
import com.monpai.sailboatmod.transport.air.airport.*;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import java.util.List;
import java.util.function.Consumer;

/** Client-only interaction and wireframe preview. Picking never sends a mutation or uses the underlying block. */
@EventBusSubscriber(modid=SailboatMod.MODID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.GAME)
public final class AirportClientHooks {
    private static AirportScreen pickingScreen;
    private static AirportScreen cachedScreen;
    private static Level levelIdentity;
    private static Consumer<Vec3> selected;
    private static AirportScreen previewScreen;
    private static ResourceKey<Level> dimension;
    private static List<AirportFacility> runways=List.of();
    private static List<AirportParkingStand> stands=List.of();
    private static List<AirportTaxiRoute> routes=List.of();
    private AirportClientHooks() {}
    public static void openOrUpdate(AirportScreenData data,boolean repaint) {
        Minecraft mc=Minecraft.getInstance();
        observeLevel(mc.level);
        // A delayed snapshot belongs to its server dimension, never to an airport at the same coordinates elsewhere.
        if(mc.level==null || !data.dimension().equals(mc.level.dimension().location())) return;
        if(mc.screen instanceof AirportScreen screen && matchesAirport(screen,data)) {cachedScreen=screen;screen.update(data,repaint);}
        else if(pickingScreen!=null && matchesAirport(pickingScreen,data)) pickingScreen.update(data,false);
        else if(!repaint && cachedScreen!=null && matchesAirport(cachedScreen,data)) cachedScreen.update(data,false);
        else if(repaint) {
            if(cachedScreen!=null && matchesAirport(cachedScreen,data)) cachedScreen.update(data,true);
            else cachedScreen=new AirportScreen(data);
            mc.setScreen(cachedScreen);
        }
    }
    private static boolean matchesAirport(AirportScreen screen,AirportScreenData data) {
        return screen.airportPos().equals(data.airportPos()) && screen.data().dimension().equals(data.dimension());
    }
    public static void beginPick(AirportScreen screen,Consumer<Vec3> callback) {
        Minecraft mc=Minecraft.getInstance();observeLevel(mc.level);
        if(mc.level==null||mc.player==null||!screen.data().dimension().equals(mc.level.dimension().location()))return;
        pickingScreen=screen;cachedScreen=screen;selected=callback;levelIdentity=mc.level;dimension=mc.level.dimension();previewScreen=screen;updatePreview(screen);
        mc.setScreen(null);mc.player.displayClientMessage(Component.translatable("screen.sailboatmod.airport.pick_hint"),true);
    }
    public static void togglePreview(AirportScreen screen) {
        Minecraft mc=Minecraft.getInstance();observeLevel(mc.level);
        if(mc.level==null || !screen.data().dimension().equals(mc.level.dimension().location()))return;
        if(previewScreen==screen) {previewScreen=null;runways=List.of();stands=List.of();routes=List.of();}
        else {previewScreen=screen;dimension=mc.level.dimension();updatePreview(screen);}
    }
    public static void updatePreview(AirportScreen screen) {
        if(previewScreen!=screen)return;
        runways=screen.previewRunways();stands=screen.previewStands();routes=screen.previewTaxiRoutes();
    }
    @SubscribeEvent public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        if(pickingScreen==null||!event.isUseItem())return;
        Minecraft mc=Minecraft.getInstance();if(mc.screen!=null)return;
        event.setCanceled(true);event.setSwingHand(false);
        observeLevel(mc.level);
        if(pickingScreen==null)return;
        if(mc.player==null||mc.level==null||!mc.level.dimension().equals(dimension)) {clear();return;}
        if(!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return;
        AirportScreen screen=pickingScreen;Consumer<Vec3> callback=selected;pickingScreen=null;selected=null;
        // Geometry uses the free cell above the support surface, consistent with runway registration.
        Vec3 point=Vec3.atBottomCenterOf(hit.getBlockPos().above());
        callback.accept(point);updatePreview(screen);mc.setScreen(screen);
    }
    @SubscribeEvent public static void onScreenOpening(ScreenEvent.Opening event) {
        // Key events arrive after vanilla pauseGame() has opened its screen. Intercept that opening instead.
        if(pickingScreen==null || !(event.getNewScreen() instanceof PauseScreen) || event.getCurrentScreen()!=null)return;
        AirportScreen screen=pickingScreen;pickingScreen=null;selected=null;
        event.setNewScreen(screen);
    }
    private static void clear() {cachedScreen=null;levelIdentity=null;pickingScreen=null;selected=null;previewScreen=null;dimension=null;runways=List.of();stands=List.of();routes=List.of();}
    private static void observeLevel(Level current) {
        if(current!=levelIdentity) {clear();levelIdentity=current;}
    }
    @SubscribeEvent public static void onLevelLoad(LevelEvent.Load event) {
        if(event.getLevel() instanceof ClientLevel level)observeLevel(level);
    }
    @SubscribeEvent public static void onLevelUnload(LevelEvent.Unload event) {
        if(event.getLevel()==levelIdentity)clear();
    }
    @SubscribeEvent public static void onClientTick(ClientTickEvent.Post event) {observeLevel(Minecraft.getInstance().level);}
    @SubscribeEvent public static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {clear();}
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS||previewScreen==null)return;
        Minecraft mc=Minecraft.getInstance();
        observeLevel(mc.level);if(previewScreen==null)return;
        if(mc.level==null||mc.player==null||!mc.level.dimension().equals(dimension)||mc.player.distanceToSqr(Vec3.atCenterOf(previewScreen.airportPos()))>1024D*1024) {clear();return;}
        Vec3 camera=event.getCamera().getPosition();var pose=event.getPoseStack();pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
        VertexConsumer vertices=mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());Matrix4f matrix=pose.last().pose();
        for(AirportFacility runway:runways) {
            Vec3 a=Vec3.atBottomCenterOf(runway.start()),b=Vec3.atBottomCenterOf(runway.end());
            Vec3 along=b.subtract(a).lengthSqr()<1e-8?new Vec3(1,0,0):b.subtract(a).normalize();
            Vec3 deckStart=a.subtract(along.scale(runway.width()/2.0)),deckEnd=b.add(along.scale(runway.width()/2.0));
            box(vertices,matrix,deckStart,deckEnd,runway.width(),runway.clearanceHeight(),.15f,.8f,1f);arrow(vertices,matrix,a,b,.15f,.8f,1f);
            int approach=runway.approachLength();
            if(approach>0) {
                box(vertices,matrix,deckStart.subtract(along.scale(approach)).add(0,approach/4.0,0),deckStart,runway.width(),runway.clearanceHeight(),.3f,.45f,.85f);
                box(vertices,matrix,deckEnd,deckEnd.add(along.scale(approach)).add(0,approach/4.0,0),runway.width(),runway.clearanceHeight(),.3f,.45f,.85f);
            }
        }
        for(AirportParkingStand stand:stands) {
            double angle=Math.toRadians(stand.yawDegrees());Vec3 forward=new Vec3(-Math.sin(angle),0,Math.cos(angle));
            Vec3 a=stand.position().subtract(forward.scale(stand.length()/2)),b=stand.position().add(forward.scale(stand.length()/2));
            box(vertices,matrix,a,b,stand.width(),stand.clearanceHeight(),.3f,1f,.35f);arrow(vertices,matrix,stand.position(),b,.3f,1f,.35f);
        }
        for(AirportTaxiRoute route:routes) {
            for(int i=1;i<route.points().size();i++) {Vec3 a=route.points().get(i-1),b=route.points().get(i);
                box(vertices,matrix,a,b,route.width(),route.clearanceHeight(),1f,.65f,.15f);arrow(vertices,matrix,a,b,1f,.65f,.15f);}
            if(route.holdPointIndex()>=0&&route.holdPointIndex()<route.points().size()) {
                Vec3 p=route.points().get(route.holdPointIndex());line(vertices,matrix,p.add(-1,.1,0),p.add(1,.1,0),1f,.2f,.2f);
                line(vertices,matrix,p.add(0,.1,-1),p.add(0,.1,1),1f,.2f,.2f);
            }
        }
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());pose.popPose();
    }
    private static void box(VertexConsumer out,Matrix4f matrix,Vec3 a,Vec3 b,double width,double height,float r,float g,float blue) {
        Vec3 delta=b.subtract(a);Vec3 side=new Vec3(-delta.z,0,delta.x);side=side.lengthSqr()<1e-8?new Vec3(width/2,0,0):side.normalize().scale(width/2);
        Vec3[] corners={a.add(side),b.add(side),b.subtract(side),a.subtract(side)};
        for(int i=0;i<4;i++){Vec3 from=corners[i],to=corners[(i+1)%4];line(out,matrix,from,to,r,g,blue);
            line(out,matrix,from.add(0,height,0),to.add(0,height,0),r,g,blue);line(out,matrix,from,from.add(0,height,0),r,g,blue);}
    }
    private static void arrow(VertexConsumer out,Matrix4f matrix,Vec3 a,Vec3 b,float r,float g,float blue) {
        Vec3 delta=b.subtract(a);if(delta.lengthSqr()<1e-8)return;
        Vec3 unit=delta.normalize(),side=new Vec3(-unit.z,0,unit.x);Vec3 tip=a.add(delta.scale(.6)).add(0,.1,0);
        line(out,matrix,tip,tip.subtract(unit.scale(2)).add(side),r,g,blue);line(out,matrix,tip,tip.subtract(unit.scale(2)).subtract(side),r,g,blue);
    }
    private static void line(VertexConsumer out,Matrix4f matrix,Vec3 from,Vec3 to,float r,float g,float b) {
        Vec3 normal=to.subtract(from).normalize();
        out.addVertex(matrix,(float)from.x,(float)from.y+.025f,(float)from.z).setColor(r,g,b,.85f).setNormal((float)normal.x,(float)normal.y,(float)normal.z);
        out.addVertex(matrix,(float)to.x,(float)to.y+.025f,(float)to.z).setColor(r,g,b,.85f).setNormal((float)normal.x,(float)normal.y,(float)normal.z);
    }
}
