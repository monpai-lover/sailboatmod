package com.monpai.sailboatmod.transport.air.airport;

import net.minecraft.nbt.CompoundTag;
import java.util.List;
import java.util.Optional;

/** Exact resource identities captured before a flight, never silently reselected during arrival. */
public record GroundAssignment(long layoutRevision,String runwayId,String standId,String outboundRouteId,String inboundRouteId,
                               AirportFacility.Type directType) {
    public GroundAssignment(long layoutRevision,String runwayId,String standId,String outboundRouteId,String inboundRouteId) {
        this(layoutRevision,runwayId,standId,outboundRouteId,inboundRouteId,null);
    }
    public GroundAssignment {
        if(layoutRevision<0||!AirportGroundLayout.validId(runwayId))throw new IllegalArgumentException("Invalid ground assignment");
        if (directType == null) {
            if(!AirportGroundLayout.validId(standId)||!AirportGroundLayout.validId(outboundRouteId)||!AirportGroundLayout.validId(inboundRouteId))
                throw new IllegalArgumentException("Invalid ground assignment");
        } else if (directType == AirportFacility.Type.RUNWAY || !"".equals(standId) || !"".equals(outboundRouteId) || !"".equals(inboundRouteId)) {
            throw new IllegalArgumentException("Invalid direct airport assignment");
        }
    }
    public static GroundAssignment direct(long revision,AirportFacility facility) {
        if (facility == null || !facility.validGeometry()) throw new IllegalArgumentException("Invalid direct airport facility");
        return new GroundAssignment(revision,facility.id(),"","","",facility.type());
    }
    public boolean direct() { return directType != null; }
    /** Resolve only the captured route IDs. A return leg changes role, never stand or route. */
    public Optional<GroundAssignment> forDirection(AirportGroundLayout layout,List<AirportFacility> facilities,AirportTaxiRoute.Direction direction){
        if(layout==null||facilities==null||direction==null||layout.revision()!=layoutRevision)return Optional.empty();
        if (direct()) return facilities.stream().filter(f -> f != null && f.id().equals(runwayId) && f.type()==directType
                && f.validGeometry()).findFirst().map(f -> this);
        if(layout.stand(standId).filter(AirportParkingStand::cargoEnabled).isEmpty())return Optional.empty();
        var out=layout.taxiRoutes().stream().filter(r->r.id().equals(outboundRouteId)).findFirst().orElse(null);
        var in=layout.taxiRoutes().stream().filter(r->r.id().equals(inboundRouteId)).findFirst().orElse(null);
        if(out==null||in==null||out.direction()!=AirportTaxiRoute.Direction.OUTBOUND||in.direction()!=AirportTaxiRoute.Direction.INBOUND
                ||!out.standId().equals(standId)||!in.standId().equals(standId)
                ||!runwayId.equals(out.runwayId())&&!runwayId.equals(in.runwayId())
                ||!runway(facilities,out.runwayId())||!runway(facilities,in.runwayId()))return Optional.empty();
        String selected=direction==AirportTaxiRoute.Direction.OUTBOUND?out.runwayId():in.runwayId();
        return Optional.of(new GroundAssignment(layoutRevision,selected,standId,outboundRouteId,inboundRouteId));
    }
    /** Initial selection prefers an existing same-runway pair before another configured direction. */
    public static Optional<GroundAssignment> select(AirportGroundLayout layout,List<AirportFacility> facilities,
            String runwayId,String standId,AirportTaxiRoute.Direction direction){
        if(layout==null||facilities==null||direction==null||!runway(facilities,runwayId))return Optional.empty();
        var route=layout.route(direction,standId,runwayId).orElse(null);if(route==null)return Optional.empty();
        var opposite=direction==AirportTaxiRoute.Direction.OUTBOUND?AirportTaxiRoute.Direction.INBOUND:AirportTaxiRoute.Direction.OUTBOUND;
        var other=layout.route(opposite,standId,runwayId).orElseGet(()->layout.taxiRoutes().stream()
                .filter(r->r.direction()==opposite&&r.standId().equals(standId)&&runway(facilities,r.runwayId())).findFirst().orElse(null));
        if(other==null)return Optional.empty();
        return new GroundAssignment(layout.revision(),runwayId,standId,
                direction==AirportTaxiRoute.Direction.OUTBOUND?route.id():other.id(),
                direction==AirportTaxiRoute.Direction.INBOUND?route.id():other.id()).forDirection(layout,facilities,direction);
    }
    private static boolean runway(List<AirportFacility> facilities,String id){return facilities.stream()
            .anyMatch(f->f!=null&&f.id().equals(id)&&f.type()==AirportFacility.Type.RUNWAY&&f.validGeometry());}
    public CompoundTag save(){CompoundTag tag=new CompoundTag();tag.putLong("Revision",layoutRevision);tag.putString("Runway",runwayId);tag.putString("Stand",standId);tag.putString("Outbound",outboundRouteId);tag.putString("Inbound",inboundRouteId);if(direct())tag.putString("DirectType",directType.name());return tag;}
    public static GroundAssignment load(CompoundTag tag){return new GroundAssignment(tag.getLong("Revision"),tag.getString("Runway"),tag.getString("Stand"),tag.getString("Outbound"),tag.getString("Inbound"),
            tag.contains("DirectType")?AirportFacility.Type.valueOf(tag.getString("DirectType")):null);}
}
