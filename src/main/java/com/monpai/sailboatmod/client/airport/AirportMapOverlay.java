package com.monpai.sailboatmod.client.airport;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapView;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerOverlayLineRenderer;
import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Airport geometry only. The owning screen renders RoadPlanner terrain underneath this layer. */
public final class AirportMapOverlay {
    public static final int SAVED=0xFF84B9D9,VALID=0xFF8FD5AA,RESERVED=0xFFE5AE61,INVALID=0xFFEF8888;
    public static final int OUTBOUND=0xFF79C6F0,INBOUND=0xFFC09AEF;
    public static final int SELECTED=0xFF79E8F4,DRAFT=0xFFFFD16F,CONFLICT=0xFFFF9471;
    private static final int AUTO_REGION = 0xFF87B7FF;
    private AirportMapOverlay() {}
    public static void render(GuiGraphics graphics,Font font,RoadPlannerMapView view,ClaimMapViewport viewport,AirportPlannerModel model) {
        if(viewport.width()<4||viewport.height()<4)return;
        graphics.enableScissor(viewport.x(),viewport.y(),viewport.right(),viewport.bottom());
        try {
            AirportScreenData data=model.snapshot();
            for(AirportFacility runway:data.facilities()) {
                if(runway.type()!=AirportFacility.Type.RUNWAY && !data.supportsRoutePublication()) continue;
                Vec3 start = center(runway.start()), end = center(runway.end());
                int color = stateColor(data, "runway", runway.id());
                if (runway.type() == AirportFacility.Type.RUNWAY) {
                    runway(graphics, font, view, viewport, runway.id(), start, end, runway.width(), color, false, false);
                } else {
                    areaFacility(graphics, font, view, viewport, runway.id(), start, end, runway.width(), color, false, false);
                }
            }
            for(AirportTaxiRoute route:data.groundLayout().taxiRoutes())
                taxi(graphics,font,view,viewport,route.id(),route.points(),route.width(),route.holdPointIndex(),
                        route.direction()==AirportTaxiRoute.Direction.OUTBOUND?OUTBOUND:INBOUND,stateColor(data,"taxi",route.id()),
                        route.direction()==AirportTaxiRoute.Direction.OUTBOUND,false,false);
            for(AirportParkingStand stand:data.groundLayout().stands())
                stand(graphics,font,view,viewport,stand.id(),stand.position(),stand.yawDegrees(),stand.length(),stand.width(),
                        stateColor(data,"stand",stand.id()),false,false);
            model.draft().ifPresent(draft -> {
                int color=model.conflict()?CONFLICT:model.dirty()?DRAFT:SELECTED;
                switch(draft.kind()) {
                    case RUNWAY, HELIPAD, MOORING -> {
                        if (draft.points().size() == 2) {
                            Vec3 start = draft.points().get(0), end = draft.points().get(1);
                            if (draft.kind() == AirportPlannerModel.Tool.RUNWAY)
                                runway(graphics, font, view, viewport, draft.id(), start, end, draft.width(), color, true, model.dirty());
                            else areaFacility(graphics, font, view, viewport, draft.id(), start, end, draft.width(), color, true, model.dirty());
                        } else if (draft.points().size() == 1) point(graphics, font, view, viewport, draft.points().getFirst(), "1", color, true);
                    }
                    case OUTBOUND,INBOUND -> taxi(graphics,font,view,viewport,draft.id(),draft.points(),draft.width(),draft.holdPoint(),color,color,draft.kind()==AirportPlannerModel.Tool.OUTBOUND,true,model.dirty());
                    case STAND -> {
                        if(!draft.points().isEmpty())stand(graphics,font,view,viewport,draft.id(),draft.points().getFirst(),draft.yaw(),draft.length(),draft.width(),color,true,model.dirty());
                    }
                    default -> { }
                }
            });
            AirportMapGeometry.ScreenPoint airport=AirportMapGeometry.project(center(data.airportPos()),view,viewport);
            if(onMap(airport,viewport)) {
                int x=(int)Math.round(airport.x()),y=(int)Math.round(airport.y());
                graphics.fill(x-3,y-3,x+4,y+4,0xFF172C3A);graphics.fill(x-1,y-5,x+2,y+6,0xFFF3F4E8);
                graphics.fill(x-5,y-1,x+6,y+2,0xFFF3F4E8);
            }
        } finally {graphics.disableScissor();}
    }
    public static void renderAutoLayout(GuiGraphics g, Font font, RoadPlannerMapView view, ClaimMapViewport vp,
                                        BlockPos first, BlockPos second, AirportEditorSession.Proposal proposal) {
        if (vp.width() < 4 || vp.height() < 4) return;
        g.enableScissor(vp.x(), vp.y(), vp.right(), vp.bottom());
        try {
            if (second != null) {
                int minX = Math.min(first.getX(), second.getX()), maxX = Math.max(first.getX(), second.getX()) + 1;
                int minZ = Math.min(first.getZ(), second.getZ()), maxZ = Math.max(first.getZ(), second.getZ()) + 1;
                int y = first.getY();
                polygon(g, view, vp, List.of(new Vec3(minX,y,minZ), new Vec3(maxX,y,minZ),
                        new Vec3(maxX,y,maxZ), new Vec3(minX,y,maxZ)), AUTO_REGION, true, true);
            }
            point(g, font, view, vp, center(first), "1", AUTO_REGION, true);
            if (second != null) point(g, font, view, vp, center(second), "2", AUTO_REGION, true);
            if (proposal == null) return;
            AirportFacility proposedRunway = proposal.changedRunway();
            runway(g, font, view, vp, proposedRunway.id(), center(proposedRunway.start()), center(proposedRunway.end()),
                    proposedRunway.width(), DRAFT, false, true);
            for (AirportFacility facility : proposal.additionalChecks()) {
                areaFacility(g, font, view, vp, Component.translatable("screen.sailboatmod.airport.legend_helipad").getString(),
                        center(facility.start()), center(facility.end()), facility.width(), SELECTED, false, true);
            }
            String proposedStandId = "";
            for (AirportTaxiRoute route : proposal.ground().taxiRoutes()) {
                if (!route.runwayId().equals(proposedRunway.id())) continue;
                proposedStandId = route.standId();
                boolean outbound = route.direction() == AirportTaxiRoute.Direction.OUTBOUND;
                taxi(g, font, view, vp, route.id(), route.points(), route.width(), route.holdPointIndex(),
                        outbound ? OUTBOUND : INBOUND, SAVED, outbound, false, true);
            }
            proposal.ground().stand(proposedStandId).ifPresent(stand -> {
                stand(g, font, view, vp, Component.translatable("screen.sailboatmod.airport.legend_hangar").getString(),
                        stand.position(), stand.yawDegrees(), stand.length(), stand.width(), VALID, false, true);
            });
        } finally { g.disableScissor(); }
    }
    public static void renderPrefabDetails(GuiGraphics g, RoadPlannerMapView view, ClaimMapViewport vp,
                                           List<AirportPrefab.Piece> pieces) {
        double scale = view.scale();
        if (vp.width() < 4 || vp.height() < 4 || scale < .2 || pieces.isEmpty()) return;
        int blockPixels = Math.max(1, (int) Math.round(scale));
        g.enableScissor(vp.x(), vp.y(), vp.right(), vp.bottom());
        try {
            for (AirportPrefab.Piece piece : pieces) {
                AirportPrefab.Part part = piece.part();
                boolean marking = part == AirportPrefab.Part.MARKING;
                if (part == AirportPrefab.Part.STRUCTURE || marking && scale < .5) continue;
                var p = AirportMapGeometry.project(center(piece.pos()), view, vp);
                if (!onMap(p, vp)) continue;
                int size = Math.max(blockPixels, part == AirportPrefab.Part.ROOF_LIGHT ? 3 : marking ? 1 : 2);
                int x = (int) Math.round(p.x() - size / 2.0);
                int y = (int) Math.round(p.y() - size / 2.0);
                int color = switch (part) {
                    case MARKING -> 0xFFF7FAFF;
                    case RUNWAY_LIGHT -> 0xFF67EFDC;
                    case ROOF_LIGHT -> 0xFFFFC16D;
                    case STRUCTURE -> throw new IllegalStateException("Structure has no detail marker");
                };
                if (!marking) g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF1A2934);
                g.fill(x, y, x + size, y + size, color);
            }
        } finally { g.disableScissor(); }
    }
    public static void renderIssue(GuiGraphics g, Font font, RoadPlannerMapView view, ClaimMapViewport vp, BlockPos issue) {
        g.enableScissor(vp.x(), vp.y(), vp.right(), vp.bottom());
        try { point(g, font, view, vp, center(issue), "!", INVALID, true); }
        finally { g.disableScissor(); }
    }
    static int stateColor(AirportScreenData data,String kind,String id) {
        for(String status:data.statuses()) {
            String[] fields=status.split("\\|",3);
            if(fields.length<3||!fields[0].equals(kind)||!fields[1].equals(id))continue;
            String value=fields[2].toLowerCase(java.util.Locale.ROOT);
            // A fresh or stale scan is WAITING, not proof of invalid geometry. Future statuses stay neutral
            // until their semantics are known; only explicit server validation failures certify a red state.
            return switch(value) {
                case "valid","ready" -> VALID;
                case "reserved","occupied","review","needs_review" -> RESERVED;
                case "invalid","invalid_geometry","too_large","obstructed","unsupported","missing",
                        "missing_reference","disconnected_route" -> INVALID;
                default -> SAVED;
            };
        }
        return SAVED;
    }
    private static void runway(GuiGraphics g,Font font,RoadPlannerMapView view,ClaimMapViewport vp,String id,Vec3 start,Vec3 end,
                               double width,int color,boolean handles,boolean dashed) {
        double distance=Math.hypot(end.x-start.x,end.z-start.z);
        double approach=Math.max(16,Math.min(64,Math.ceil(distance/2)));
        polygon(g,view,vp,AirportMapGeometry.rectangle(start.add(end).scale(.5),AirportMapGeometry.headingDegrees(start,end),distance+width+approach*2,width),
                (color&0xFFFFFF)|0x88000000,false,true);
        polygon(g,view,vp,AirportMapGeometry.runwayFootprint(start,end,width),color,handles,dashed);
        line(g,view,vp,start,end,color,2,true);arrow(g,view,vp,start,end,color);
        label(g,font,view,vp,start.add(end).scale(.5),id,color);
        if(handles){point(g,font,view,vp,start,"1",color,true);point(g,font,view,vp,end,"2",color,true);}
    }
    private static void areaFacility(GuiGraphics g, Font font, RoadPlannerMapView view, ClaimMapViewport vp, String label,
                                     Vec3 start, Vec3 end, double width, int color, boolean handles, boolean dashed) {
        polygon(g, view, vp, AirportMapGeometry.runwayFootprint(start, end, width), color, handles, dashed);
        label(g, font, view, vp, start.add(end).scale(.5), label, color);
        if (handles) {
            point(g, font, view, vp, start, "1", color, true);
            if (!start.equals(end)) point(g, font, view, vp, end, "2", color, true);
        }
    }
    private static void taxi(GuiGraphics g,Font font,RoadPlannerMapView view,ClaimMapViewport vp,String id,List<Vec3> points,
                             double width,int hold,int color,int statusColor,boolean outbound,boolean handles,boolean dashed) {
        for(List<Vec3> footprint:AirportMapGeometry.taxiFootprint(points,width,view,vp))
            polygon(g,view,vp,footprint,color,handles,dashed);
        for(int i=0;i+1<points.size();i++) {
            Vec3 start=points.get(i),end=points.get(i+1);
            line(g,view,vp,start,end,color,1,dashed);arrow(g,view,vp,start,end,color);
        }
        for(int i=0;i<points.size();i++)if(handles)point(g,font,view,vp,points.get(i),Integer.toString(i+1),color,true);
        if(!points.isEmpty()) {
            String direction=Component.translatable("screen.sailboatmod.airport."+(outbound?"legend_outbound":"legend_inbound")).getString();
            label(g,font,view,vp,points.getFirst(),direction+" "+id,color);
            if(statusColor!=SAVED&&statusColor!=VALID&&statusColor!=color) {
                var p=AirportMapGeometry.project(points.getFirst(),view,vp);
                if(onMap(p,vp))g.fill((int)p.x()-5,(int)p.y()-5,(int)p.x()+6,(int)p.y()+6,statusColor);
            }
        }
        if(hold>=0&&hold<points.size()-1) {
            Vec3 position=points.get(hold),next=points.get(hold+1);double dx=next.x-position.x,dz=next.z-position.z,len=Math.hypot(dx,dz);
            if(len>1e-7) {
                double half=width/2;Vec3 offset=new Vec3(-dz/len*half,0,dx/len*half);
                line(g,view,vp,position.subtract(offset),position.add(offset),RESERVED,3,false);
                label(g,font,view,vp,position,"H",RESERVED);
            }
        }
    }
    private static void stand(GuiGraphics g,Font font,RoadPlannerMapView view,ClaimMapViewport vp,String id,Vec3 center,double yaw,
                              double length,double width,int color,boolean handles,boolean dashed) {
        polygon(g,view,vp,AirportMapGeometry.rectangle(center,yaw,length,width),color,handles,dashed);
        var p=AirportMapGeometry.project(center,view,vp);
        var heading=AirportMapGeometry.headingHandle(center,yaw,length,view,vp);
        screenLine(g,p,heading,color,handles?2:1,false,vp);
        screenArrow(g,p,heading,color,vp);
        point(g,font,view,vp,center,"",color,handles);label(g,font,view,vp,center,id,color);
        if(handles&&onMap(heading,vp)) {
            int x=(int)Math.round(heading.x()),y=(int)Math.round(heading.y());
            g.fill(x-4,y-4,x+5,y+5,0xFF112C35);g.fill(x-2,y-2,x+3,y+3,color);
        }
    }
    private static void point(GuiGraphics g,Font font,RoadPlannerMapView view,ClaimMapViewport vp,Vec3 world,String text,int color,boolean selected) {
        var p=AirportMapGeometry.project(world,view,vp);if(!onMap(p,vp))return;
        int x=(int)Math.round(p.x()),y=(int)Math.round(p.y()),r=selected?4:2;
        g.fill(x-r-1,y-r-1,x+r+2,y+r+2,0xFF10202D);g.fill(x-r,y-r,x+r+1,y+r+1,color);
        if(!text.isEmpty())g.drawString(font,text,x+7,y+4,0xFFFFFFFF,false);
    }
    private static void label(GuiGraphics g,Font font,RoadPlannerMapView view,ClaimMapViewport vp,Vec3 point,String text,int color) {
        var p=AirportMapGeometry.project(point,view,vp);if(!onMap(p,vp))return;
        int x=(int)Math.round(p.x())+7,y=(int)Math.round(p.y())-12;
        g.fill(x-2,y-1,x+font.width(text)+2,y+font.lineHeight,0xC510202D);g.drawString(font,text,x,y,color,false);
    }
    private static void polygon(GuiGraphics g,RoadPlannerMapView view,ClaimMapViewport vp,List<Vec3> points,int color,boolean fill,boolean dashed) {
        if(points.size()<3)return;
        List<AirportMapGeometry.ScreenPoint> screen=points.stream().map(point->AirportMapGeometry.project(point,view,vp)).toList();
        if(screen.stream().anyMatch(point->!point.finite()))return;
        if(fill)fillPolygon(g,screen,vp,(color&0xFFFFFF)|0x24000000);
        for(int i=0;i<screen.size();i++)screenLine(g,screen.get(i),screen.get((i+1)%screen.size()),color,fill?2:1,dashed,vp);
    }
    /** Scan only visible rows; extreme world coordinates never become raster loop bounds. */
    private static void fillPolygon(GuiGraphics g,List<AirportMapGeometry.ScreenPoint> points,ClaimMapViewport vp,int color) {
        double min=points.stream().mapToDouble(AirportMapGeometry.ScreenPoint::y).min().orElse(0);
        double max=points.stream().mapToDouble(AirportMapGeometry.ScreenPoint::y).max().orElse(-1);
        int top=(int)Math.max(vp.y(),Math.min(vp.bottom(),Math.floor(min)));
        int bottom=(int)Math.max(vp.y(),Math.min(vp.bottom(),Math.ceil(max)));
        for(int y=top;y<bottom;y++) {
            double row=y+.5;List<Double> crossings=new ArrayList<>(4);
            for(int i=0,j=points.size()-1;i<points.size();j=i++) {
                var a=points.get(i);var b=points.get(j);
                if((a.y()>row)!=(b.y()>row))crossings.add(a.x()+(row-a.y())*(b.x()-a.x())/(b.y()-a.y()));
            }
            crossings.sort(Comparator.naturalOrder());
            for(int i=0;i+1<crossings.size();i+=2) {
                int left=(int)Math.max(vp.x(),Math.min(vp.right(),Math.floor(crossings.get(i))));
                int right=(int)Math.max(vp.x(),Math.min(vp.right(),Math.ceil(crossings.get(i+1))));
                if(left<right)g.fill(left,y,right,y+1,color);
            }
        }
    }
    private static void line(GuiGraphics g,RoadPlannerMapView view,ClaimMapViewport vp,Vec3 start,Vec3 end,int color,int thickness,boolean dashed) {
        screenLine(g,AirportMapGeometry.project(start,view,vp),AirportMapGeometry.project(end,view,vp),color,thickness,dashed,vp);
    }
    private static void screenLine(GuiGraphics g,AirportMapGeometry.ScreenPoint a,AirportMapGeometry.ScreenPoint b,int color,int thickness,boolean dashed,ClaimMapViewport vp) {
        AirportMapGeometry.clipLine(a,b,vp).ifPresent(segment->{
            int x1=(int)Math.round(segment.start().x()),y1=(int)Math.round(segment.start().y()),x2=(int)Math.round(segment.end().x()),y2=(int)Math.round(segment.end().y());
            if(dashed)RoadPlannerOverlayLineRenderer.drawThickDashedLine(g,x1,y1,x2,y2,color,thickness,6,4,vp.x(),vp.y(),vp.right()-1,vp.bottom()-1);
            else RoadPlannerOverlayLineRenderer.drawThickLine(g,x1,y1,x2,y2,color,thickness,vp.x(),vp.y(),vp.right()-1,vp.bottom()-1);
        });
    }
    private static void arrow(GuiGraphics g,RoadPlannerMapView view,ClaimMapViewport vp,Vec3 start,Vec3 end,int color) {
        screenArrow(g,AirportMapGeometry.project(start,view,vp),AirportMapGeometry.project(end,view,vp),color,vp);
    }
    private static void screenArrow(GuiGraphics g,AirportMapGeometry.ScreenPoint a,AirportMapGeometry.ScreenPoint b,int color,ClaimMapViewport vp) {
        double dx=b.x()-a.x(),dy=b.y()-a.y(),length=Math.hypot(dx,dy);if(!Double.isFinite(length)||length<14)return;
        double ux=dx/length,uy=dy/length,mx=(a.x()+b.x())/2,my=(a.y()+b.y())/2;
        var tip=new AirportMapGeometry.ScreenPoint(mx+ux*5,my+uy*5);
        screenLine(g,tip,new AirportMapGeometry.ScreenPoint(mx-ux*4-uy*4,my-uy*4+ux*4),color,2,false,vp);
        screenLine(g,tip,new AirportMapGeometry.ScreenPoint(mx-ux*4+uy*4,my-uy*4-ux*4),color,2,false,vp);
    }
    private static boolean onMap(AirportMapGeometry.ScreenPoint point,ClaimMapViewport viewport){return point.finite()&&viewport.contains(point.x(),point.y());}
    private static Vec3 center(BlockPos p){return new Vec3(p.getX()+.5,p.getY(),p.getZ()+.5);}
}
