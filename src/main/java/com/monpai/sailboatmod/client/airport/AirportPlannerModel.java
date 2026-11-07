package com.monpai.sailboatmod.client.airport;

import com.monpai.sailboatmod.network.packet.airport.AirportActionPacket;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Pure map editor state. Its draft never becomes authoritative airport configuration. */
public final class AirportPlannerModel {
    public enum Tool {
        SELECT(null), RUNWAY(AirportFacility.Type.RUNWAY), OUTBOUND(null), INBOUND(null), STAND(null),
        HELIPAD(AirportFacility.Type.HELIPAD), MOORING(AirportFacility.Type.MOORING);

        private final AirportFacility.Type facilityType;
        Tool(AirportFacility.Type facilityType) { this.facilityType = facilityType; }
        public boolean isFacility() { return facilityType != null; }
        public boolean isVerticalFacility() { return isFacility() && this != RUNWAY; }
        static Tool forFacility(AirportFacility.Type type) {
            return switch (type) { case RUNWAY -> RUNWAY; case HELIPAD -> HELIPAD; case MOORING -> MOORING; };
        }
    }
    public static final int MAX_UNDO = 64;

    /** Runway points are display centers (block X/Z + .5); other points retain exact world coordinates. */
    public record Draft(Tool kind, String id, String runwayId, String standId, List<Vec3> points,
                        double yaw, double length, double width, int clearance, double speed,
                        int holdPoint, boolean cargoEnabled) {
        public Draft {
            Objects.requireNonNull(kind); Objects.requireNonNull(id); Objects.requireNonNull(runwayId); Objects.requireNonNull(standId);
            if(kind == Tool.SELECT || id.length()>64 || runwayId.length()>64 || standId.length()>64)
                throw new IllegalArgumentException("Invalid draft identity");
            points=List.copyOf(points);
            int limit=kind.isFacility()?2:kind==Tool.STAND?1:AirportActionPacket.MAX_POINTS;
            if(points.size()>limit || !Double.isFinite(yaw) || !Double.isFinite(length) || !Double.isFinite(width) || !Double.isFinite(speed))
                throw new IllegalArgumentException("Invalid draft values");
            for(Vec3 point:points) if(!finitePoint(point))throw new IllegalArgumentException("Invalid draft point");
        }
        public Draft withId(String value){return new Draft(kind,value,runwayId,standId,points,yaw,length,width,clearance,speed,holdPoint,cargoEnabled);}
        public Draft withRunwayId(String value){return new Draft(kind,id,value,standId,points,yaw,length,width,clearance,speed,holdPoint,cargoEnabled);}
        public Draft withStandId(String value){return new Draft(kind,id,runwayId,value,points,yaw,length,width,clearance,speed,holdPoint,cargoEnabled);}
        public Draft withDimensions(double newLength,double newWidth,int newClearance){return new Draft(kind,id,runwayId,standId,points,yaw,newLength,newWidth,newClearance,speed,holdPoint,cargoEnabled);}
        public Draft withSpeed(double value){return new Draft(kind,id,runwayId,standId,points,yaw,length,width,clearance,value,holdPoint,cargoEnabled);}
        public Draft withHoldPoint(int value){return new Draft(kind,id,runwayId,standId,points,yaw,length,width,clearance,speed,value,cargoEnabled);}
        public Draft withCargoEnabled(boolean value){return new Draft(kind,id,runwayId,standId,points,yaw,length,width,clearance,speed,holdPoint,value);}
        public Draft withPoints(List<Vec3> value){return new Draft(kind,id,runwayId,standId,value,yaw,length,width,clearance,speed,holdPoint,cargoEnabled);}
        public Draft withYaw(double value){return new Draft(kind,id,runwayId,standId,points,value,length,width,clearance,speed,holdPoint,cargoEnabled);}
    }
    private record EditState(Draft draft, double groundY) {}
    private record Pending(AirportActionPacket packet, Draft draft, long inputVersion) {}
    private AirportScreenData snapshot;
    private Tool tool=Tool.SELECT;
    private Draft draft, baseline;
    private String selectedSavedId;
    private Tool selectedSavedKind;
    private long draftRevision;
    private double groundY, baselineGroundY;
    private final Deque<EditState> history=new ArrayDeque<>();
    private EditState editStart;
    private Pending pending;
    private String lastResult="";
    private boolean unsavedInput;
    private long inputVersion;

    public AirportPlannerModel(AirportScreenData snapshot) {
        this.snapshot=Objects.requireNonNull(snapshot);draftRevision=snapshot.revision();groundY=snapshot.airportPos().getY();baselineGroundY=groundY;lastResult=snapshot.result();
    }
    public AirportScreenData snapshot(){return snapshot;}
    public Tool tool(){return tool;}
    public Optional<Draft> draft(){return Optional.ofNullable(draft);}
    public long draftRevision(){return draftRevision;}
    public double groundY(){return groundY;}
    public boolean dirty(){return draft!=null&&(unsavedInput||!draft.equals(baseline)||Double.compare(groundY,baselineGroundY)!=0);}
    public boolean conflict(){return draft!=null&&draftRevision!=snapshot.revision();}
    public String lastResult(){return lastResult;}
    public boolean awaitingReply(){return pending!=null;}
    public long validationProgress(){
        for(String row:snapshot.statuses())if(row.startsWith("editor||"))try{return Math.max(0,Long.parseLong(row.substring(8)));}catch(NumberFormatException ignored){}
        return 0;
    }

    /** Selection does not replace a dirty draft. SELECT only changes the input tool, not the draft. */
    public boolean setTool(Tool value) {
        Objects.requireNonNull(value);
        if(pending!=null)return false;
        if(value==Tool.SELECT){endEdit();tool=value;return true;}
        if(draft!=null&&draft.kind()==value){tool=value;return true;}
        return startNew(value);
    }
    public boolean startNew(Tool value) {
        Objects.requireNonNull(value);
        if(value==Tool.SELECT)return setTool(value);
        if(pending!=null||!snapshot.canManage()||dirty()||value.isVerticalFacility()&&!snapshot.supportsRoutePublication())return false;
        String runway=snapshot.facilities().stream().filter(f->f.type()==AirportFacility.Type.RUNWAY).map(AirportFacility::id).findFirst().orElse("");
        String stand=snapshot.groundLayout().stands().stream().map(AirportParkingStand::id).findFirst().orElse("");
        String prefix=switch(value){case RUNWAY,HELIPAD,MOORING->value.name().toLowerCase(Locale.ROOT);case OUTBOUND->"taxi-out";case INBOUND->"taxi-in";case STAND->"stand";default->throw new IllegalArgumentException();};
        Draft created=new Draft(value,uniqueId(value,prefix),runway,stand,List.of(),0,24,value==Tool.RUNWAY?9:24,16,2,-1,true);
        select(created,null);return true;
    }
    public boolean selectRunway(String id) {
        var value=snapshot.facilities().stream().filter(f->f.type()==AirportFacility.Type.RUNWAY&&f.id().equals(id)).findFirst();
        return value.isPresent()&&selectSaved(from(value.get()),id);
    }
    public boolean selectFacility(String id) {
        var value = snapshot.facilities().stream().filter(facility -> facility.id().equals(id))
                .filter(facility -> facility.type() == AirportFacility.Type.RUNWAY || snapshot.supportsRoutePublication()).findFirst();
        return value.isPresent() && selectSaved(from(value.get()), id);
    }
    public boolean selectTaxi(String id) {
        var value=snapshot.groundLayout().taxiRoutes().stream().filter(r->r.id().equals(id)).findFirst();
        return value.isPresent()&&selectSaved(from(value.get()),id);
    }
    public boolean selectStand(String id) {
        var value=snapshot.groundLayout().stand(id);return value.isPresent()&&selectSaved(from(value.get()),id);
    }
    private boolean selectSaved(Draft value,String id) {
        if(pending!=null)return false;
        if(draft!=null&&Objects.equals(selectedSavedId,id)&&selectedSavedKind==value.kind())return true;
        if(dirty())return false;
        select(value,id);return true;
    }
    private void select(Draft value,String savedId) {
        draft=value;baseline=value;selectedSavedId=savedId;selectedSavedKind=savedId==null?null:value.kind();
        tool=value.kind();draftRevision=snapshot.revision();groundY=value.points().isEmpty()?snapshot.airportPos().getY():value.points().getFirst().y;
        baselineGroundY=groundY;history.clear();editStart=null;unsavedInput=false;
    }
    private String uniqueId(Tool kind,String prefix) {
        Set<String> ids=new HashSet<>();
        if(kind.isFacility())snapshot.facilities().forEach(f->ids.add(f.id()));
        else if(kind==Tool.STAND)snapshot.groundLayout().stands().forEach(s->ids.add(s.id()));
        else snapshot.groundLayout().taxiRoutes().forEach(r->ids.add(r.id()));
        String value=prefix;for(int i=2;ids.contains(value);i++)value=prefix+"_"+i;return value;
    }
    public void click(Vec3 world) {
        editable();Objects.requireNonNull(world);
        if(tool==Tool.SELECT)return;
        if(tool!=draft.kind())throw new IllegalStateException("Tool does not match draft");
        List<Vec3> points=new ArrayList<>(draft.points());
        if(draft.kind().isFacility()&&points.size()==2)return;
        if(draft.kind()==Tool.STAND){if(points.isEmpty())points.add(onPlane(world));else points.set(0,onPlane(world));}
        else {if(points.size()>=AirportActionPacket.MAX_POINTS)throw new IllegalArgumentException("Too many points");points.add(onPlane(world));}
        change(draft.withPoints(points),groundY);
    }
    public void movePoint(int index,Vec3 point) {
        editable();List<Vec3> points=new ArrayList<>(draft.points());points.set(index,onPlane(point));change(draft.withPoints(points),groundY);
    }
    /** Insert before index; index == point count appends. */
    public void insertPoint(int index,Vec3 point) {
        editable();taxiOnly();if(draft.points().size()>=AirportActionPacket.MAX_POINTS)throw new IllegalArgumentException("Too many points");
        List<Vec3> points=new ArrayList<>(draft.points());points.add(index,onPlane(point));int hold=draft.holdPoint();
        if(hold>=index)hold++;change(draft.withPoints(points).withHoldPoint(hold),groundY);
    }
    public void deletePoint(int index) {
        editable();List<Vec3> points=new ArrayList<>(draft.points());points.remove(index);int hold=draft.holdPoint();
        if(hold==index)hold=-1;else if(hold>index)hold--;change(draft.withPoints(points).withHoldPoint(hold),groundY);
    }
    public void setHoldPoint(int index) {
        editable();taxiOnly();
        if(draft.kind()!=Tool.OUTBOUND||index< -1||index>=draft.points().size()-1)throw new IllegalArgumentException("Invalid hold point");
        change(draft.withHoldPoint(index),groundY);
    }
    public void setHeading(double yaw) {
        editable();if(draft.kind()!=Tool.STAND||!Double.isFinite(yaw))throw new IllegalArgumentException("Invalid heading");
        double normalized=yaw%360;if(normalized>180)normalized-=360;if(normalized< -180)normalized+=360;
        change(draft.withYaw(normalized),groundY);
    }
    public void headingToward(Vec3 world) {
        editable();if(draft.kind()!=Tool.STAND||draft.points().isEmpty()||!finitePoint(world))throw new IllegalArgumentException("Missing stand position");
        Vec3 center=draft.points().getFirst();double dx=world.x-center.x,dz=world.z-center.z;
        if(Math.hypot(dx,dz)>1e-8)setHeading(Math.toDegrees(Math.atan2(-dx,dz)));
    }
    public void setGroundY(int y) {
        editable();if(y< -2048||y>2047)throw new IllegalArgumentException("Invalid ground Y");
        change(draft.withPoints(draft.points().stream().map(p->new Vec3(p.x,y,p.z)).toList()),y);
    }
    public void updateProperties(Draft value) {
        editable();Objects.requireNonNull(value);if(value.kind()!=draft.kind())throw new IllegalArgumentException("Cannot change facility kind");
        List<Vec3> points=value.points();
        if(value.kind().isFacility())points=points.stream().map(AirportPlannerModel::runwayCenter).toList();
        double plane=points.isEmpty()?groundY:points.getFirst().y;
        change(value.withPoints(points),plane);
    }
    /** A precision field may be temporarily unparsable while the geometric draft itself is unchanged. */
    public void retainUnsavedInput(){editable();unsavedInput=true;inputVersion++;}
    public void clearUnsavedInput(){if(pending==null&&unsavedInput){unsavedInput=false;inputVersion++;}}
    public void reverseRunway() {
        editable();if(!draft.kind().isFacility())throw new IllegalArgumentException("Not a landing facility");
        List<Vec3> points=new ArrayList<>(draft.points());Collections.reverse(points);change(draft.withPoints(points),groundY);
    }
    public void beginEdit(){editable();if(editStart==null)editStart=new EditState(draft,groundY);}
    public void endEdit(){if(editStart!=null){if(!editStart.equals(new EditState(draft,groundY)))push(editStart);editStart=null;}}
    public void cancelEdit(){if(editStart!=null){draft=editStart.draft();groundY=editStart.groundY();editStart=null;}}
    public boolean undo() {
        if(pending!=null||!snapshot.canManage())return false;
        if(editStart!=null){cancelEdit();return true;}
        if(history.isEmpty())return false;var previous=history.removeLast();draft=previous.draft();groundY=previous.groundY();return true;
    }
    private void change(Draft next,double plane) {
        if(next.equals(draft)&&Double.compare(plane,groundY)==0)return;
        if(editStart==null)push(new EditState(draft,groundY));draft=next;groundY=plane;
    }
    private void push(EditState value){if(history.size()==MAX_UNDO)history.removeFirst();history.addLast(value);}
    private void editable(){if(pending!=null)throw new IllegalStateException("Awaiting server validation");if(!snapshot.canManage())throw new IllegalStateException("Read-only airport");if(draft==null)throw new IllegalStateException("No selected facility");}
    private void taxiOnly(){if(draft.kind()!=Tool.OUTBOUND&&draft.kind()!=Tool.INBOUND)throw new IllegalArgumentException("Not a taxi route");}
    private Vec3 onPlane(Vec3 point){if(!finitePoint(point))throw new IllegalArgumentException("Invalid point");Vec3 value=new Vec3(point.x,groundY,point.z);return draft.kind().isFacility()?runwayCenter(value):value;}
    private static Vec3 runwayCenter(Vec3 value){return new Vec3(Math.floor(value.x)+.5,value.y,Math.floor(value.z)+.5);}
    private static boolean finitePoint(Vec3 point){return point!=null&&Double.isFinite(point.x)&&Double.isFinite(point.y)&&Double.isFinite(point.z)&&Math.abs(point.x)<=30_000_000&&Math.abs(point.z)<=30_000_000&&point.y>= -2048&&point.y<=2047;}

    public String validationError() {
        if(!snapshot.canManage())return "forbidden";if(draft==null)return "no_selection";
        if(pending!=null)return "pending";if(unsavedInput)return "invalid_geometry";
        int minimum=draft.kind()==Tool.STAND?1:2;if(draft.points().size()<minimum)return "incomplete";
        try {
            var packet=packet(draft,draftRevision);var tag=packet.toActionTag();
            boolean valid=switch(draft.kind()) {
                case RUNWAY, HELIPAD, MOORING -> AirportFacility.load(tag.getCompound("Facility")).validGeometry();
                case STAND -> AirportParkingStand.load(tag.getCompound("Stand")).validGeometry();
                case OUTBOUND,INBOUND -> AirportTaxiRoute.load(tag.getCompound("TaxiRoute")).validGeometry();
                default -> false;
            };
            if(!valid)return "invalid_geometry";
            if(draft.kind()==Tool.INBOUND||draft.kind()==Tool.OUTBOUND) {
                if(snapshot.facilities().stream().noneMatch(f->f.type()==AirportFacility.Type.RUNWAY&&f.id().equals(draft.runwayId()))
                        ||snapshot.groundLayout().stand(draft.standId()).isEmpty())return "invalid_geometry";
                var route=AirportTaxiRoute.load(tag.getCompound("TaxiRoute"));
                if(!AirportGroundValidation.withinManagementDistance(route,snapshot.facilities(),snapshot.groundLayout().stands(),snapshot.airportPos()))return "invalid_geometry";
            }
            return "";
        }catch(IllegalArgumentException|NullPointerException failure){return "invalid_geometry";}
    }
    /** Local completeness/shape check only. Terrain, authority and current revision remain server checked. */
    public boolean canSave(){return validationError().isEmpty();}
    public AirportActionPacket savePacket(){if(pending!=null)throw new IllegalStateException("Awaiting server reply");String error=validationError();if(!error.isEmpty())throw new IllegalArgumentException(error);return packet(draft,draftRevision);}
    private AirportActionPacket packet(Draft value,long revision) {
        List<Vec3> points=value.kind().isFacility()?value.points().stream().map(p->new Vec3(Math.floor(p.x),p.y,Math.floor(p.z))).toList():value.points();
        var action=switch(value.kind()){case RUNWAY->AirportActionPacket.Action.UPSERT_RUNWAY;
            case HELIPAD,MOORING->AirportActionPacket.Action.UPSERT_LANDING_FACILITY;
            case STAND->AirportActionPacket.Action.UPSERT_STAND;case OUTBOUND,INBOUND->AirportActionPacket.Action.UPSERT_TAXI_ROUTE;default->throw new IllegalArgumentException();};
        String facility = value.kind().isVerticalFacility() ? value.kind().facilityType.name() : value.runwayId();
        return new AirportActionPacket(snapshot.dimension(),snapshot.airportPos(),revision,action,value.id(),facility,value.standId(),points,
                value.kind()==Tool.STAND?value.cargoEnabled():value.kind()==Tool.INBOUND,value.yaw(),value.length(),value.width(),value.clearance(),value.speed(),value.holdPoint());
    }
    public AirportActionPacket removePacket() {
        editable();if(pending!=null)throw new IllegalStateException("Awaiting server reply");if(selectedSavedId==null)throw new IllegalStateException("Unsaved facility");
        var action=switch(selectedSavedKind){case RUNWAY,HELIPAD,MOORING->AirportActionPacket.Action.REMOVE_RUNWAY;case STAND->AirportActionPacket.Action.REMOVE_STAND;case OUTBOUND,INBOUND->AirportActionPacket.Action.REMOVE_TAXI_ROUTE;default->throw new IllegalArgumentException();};
        return AirportActionPacket.simple(snapshot.dimension(),snapshot.airportPos(),draftRevision,action,selectedSavedId);
    }
    public void markSubmitted(AirportActionPacket packet) {
        editable();Objects.requireNonNull(packet);if(pending!=null)throw new IllegalStateException("Awaiting server reply");
        boolean removal=switch(packet.action()){case REMOVE_RUNWAY,REMOVE_STAND,REMOVE_TAXI_ROUTE->true;default->false;};
        if(!packet.equals(removal?removePacket():savePacket()))throw new IllegalArgumentException("Packet does not match current draft");
        pending=new Pending(packet,draft,inputVersion);
    }
    public void applySnapshot(AirportScreenData update) {
        Objects.requireNonNull(update);
        if(!snapshot.dimension().equals(update.dimension())||!snapshot.airportPos().equals(update.airportPos()))return;
        boolean current=update.revision()>=snapshot.revision();
        boolean newer=update.revision()>snapshot.revision();
        Pending reply=pending;
        // Replies can arrive after a newer periodic snapshot. Resolve waiting without rolling state back.
        boolean hasReply=reply!=null&&!update.result().isEmpty()&&!update.result().equals("pending")&&update.revision()>=reply.packet().revision();
        if(!current&&!hasReply)return;
        if(current)snapshot=update;
        if(!update.result().isEmpty())lastResult=update.result();
        if(hasReply) {
            pending=null;
            if(current&&"ok".equals(update.result())&&acknowledges(update,reply.packet())
                    &&Objects.equals(draft,reply.draft())&&inputVersion==reply.inputVersion()) {
                if(isRemoval(reply.packet().action())) {
                    draft=null;baseline=null;selectedSavedId=null;selectedSavedKind=null;tool=Tool.SELECT;history.clear();editStart=null;unsavedInput=false;
                }else {
                    baseline=draft;baselineGroundY=groundY;selectedSavedId=reply.packet().id();selectedSavedKind=draft.kind();history.clear();unsavedInput=false;
                }
                draftRevision=update.revision();return;
            }
        }
        // A refresh must not split a captured drag or erase undo history merely because it is clean now.
        if(current&&newer&&!dirty()&&draft!=null&&editStart==null&&history.isEmpty())reloadCleanSelection();
    }
    private static boolean isRemoval(AirportActionPacket.Action action){return action==AirportActionPacket.Action.REMOVE_RUNWAY||action==AirportActionPacket.Action.REMOVE_STAND||action==AirportActionPacket.Action.REMOVE_TAXI_ROUTE;}
    private static boolean acknowledges(AirportScreenData update,AirportActionPacket packet) {
        if(update.revision()!=packet.revision()+1)return false;
        var tag=packet.toActionTag();
        return switch(packet.action()) {
            case UPSERT_RUNWAY, UPSERT_LANDING_FACILITY -> update.facilities().contains(AirportFacility.load(tag.getCompound("Facility")));
            case UPSERT_STAND -> update.groundLayout().stands().contains(AirportParkingStand.load(tag.getCompound("Stand")));
            case UPSERT_TAXI_ROUTE -> update.groundLayout().taxiRoutes().contains(AirportTaxiRoute.load(tag.getCompound("TaxiRoute")));
            case REMOVE_RUNWAY -> update.facilities().stream().noneMatch(f->f.id().equals(packet.id()));
            case REMOVE_STAND -> update.groundLayout().stands().stream().noneMatch(s->s.id().equals(packet.id()));
            case REMOVE_TAXI_ROUTE -> update.groundLayout().taxiRoutes().stream().noneMatch(r->r.id().equals(packet.id()));
            default -> false;
        };
    }
    public void acceptCurrentRevision(){if(pending==null&&draft!=null){endEdit();draftRevision=snapshot.revision();}}
    public void discard() {
        if(pending!=null)return;
        history.clear();editStart=null;unsavedInput=false;inputVersion++;
        Draft saved=selectedSavedId==null?null:savedDraft(selectedSavedKind,selectedSavedId);
        if(saved!=null){select(saved,selectedSavedId);return;}
        Tool previous=draft==null?Tool.SELECT:draft.kind();draft=null;baseline=null;selectedSavedId=null;selectedSavedKind=null;
        groundY=snapshot.airportPos().getY();baselineGroundY=groundY;draftRevision=snapshot.revision();tool=Tool.SELECT;
        if(previous!=Tool.SELECT&&snapshot.canManage())startNew(previous);
    }
    private void reloadCleanSelection() {
        if(selectedSavedId!=null){Draft saved=savedDraft(selectedSavedKind,selectedSavedId);if(saved!=null){Tool current=tool;select(saved,selectedSavedId);tool=current;return;}
            draft=null;baseline=null;selectedSavedId=null;selectedSavedKind=null;tool=Tool.SELECT;}
        draftRevision=snapshot.revision();
    }
    private Draft savedDraft(Tool kind,String id) {
        return switch(kind){
            case RUNWAY, HELIPAD, MOORING -> snapshot.facilities().stream().filter(f->f.type()==kind.facilityType&&f.id().equals(id)).map(AirportPlannerModel::from).findFirst().orElse(null);
            case STAND -> snapshot.groundLayout().stand(id).map(AirportPlannerModel::from).orElse(null);
            case OUTBOUND,INBOUND -> snapshot.groundLayout().taxiRoutes().stream().filter(r->r.id().equals(id)).map(AirportPlannerModel::from).findFirst().orElse(null);
            default -> null;
        };
    }
    public List<AirportFacility> previewRunways() {
        List<AirportFacility> values=new ArrayList<>(snapshot.facilities());
        if(draft!=null&&draft.kind().isFacility())try{var value=AirportFacility.load(packet(draft,draftRevision).toActionTag().getCompound("Facility"));values.removeIf(f->f.id().equals(value.id()));values.add(value);}catch(IllegalArgumentException ignored){}
        return List.copyOf(values);
    }
    public List<AirportParkingStand> previewStands() {
        List<AirportParkingStand> values=new ArrayList<>(snapshot.groundLayout().stands());
        if(draft!=null&&draft.kind()==Tool.STAND)try{var value=AirportParkingStand.load(packet(draft,draftRevision).toActionTag().getCompound("Stand"));values.removeIf(s->s.id().equals(value.id()));values.add(value);}catch(IllegalArgumentException ignored){}
        return List.copyOf(values);
    }
    public List<AirportTaxiRoute> previewTaxiRoutes() {
        List<AirportTaxiRoute> values=new ArrayList<>(snapshot.groundLayout().taxiRoutes());
        if(draft!=null&&(draft.kind()==Tool.OUTBOUND||draft.kind()==Tool.INBOUND))try{var value=AirportTaxiRoute.load(packet(draft,draftRevision).toActionTag().getCompound("TaxiRoute"));values.removeIf(r->r.id().equals(value.id()));values.add(value);}catch(IllegalArgumentException ignored){}
        return List.copyOf(values);
    }
    private static Draft from(AirportFacility value){return new Draft(Tool.forFacility(value.type()),value.id(),"","",List.of(new Vec3(value.start().getX()+.5,value.start().getY(),value.start().getZ()+.5),new Vec3(value.end().getX()+.5,value.end().getY(),value.end().getZ()+.5)),0,value.length(),value.width(),value.clearanceHeight(),2,-1,true);}
    private static Draft from(AirportParkingStand value){return new Draft(Tool.STAND,value.id(),"","",List.of(value.position()),value.yawDegrees(),value.length(),value.width(),value.clearanceHeight(),2,-1,value.cargoEnabled());}
    private static Draft from(AirportTaxiRoute value){return new Draft(value.direction()==AirportTaxiRoute.Direction.INBOUND?Tool.INBOUND:Tool.OUTBOUND,value.id(),value.runwayId(),value.standId(),value.points(),0,24,value.width(),value.clearanceHeight(),value.speedLimitMetersPerSecond(),value.holdPointIndex(),true);}
}
