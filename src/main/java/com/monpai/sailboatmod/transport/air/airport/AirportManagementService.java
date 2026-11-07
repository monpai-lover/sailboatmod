package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import com.monpai.sailboatmod.dock.FacilityNames;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.airport.AirportSnapshotPacket;

/** Single server authority for airport UI and command edits; never loads a chunk or trusts client ownership. */
public final class AirportManagementService {
    public record Result(boolean success,String code,long revision){}
    private static final Map<net.minecraft.server.MinecraftServer,Map<UUID,Long>> REQUESTS=new WeakHashMap<>();
    private AirportManagementService(){}
    static boolean acceptRequest(Map<UUID,Long> requests,UUID player,long now){
        Long previous=requests.get(player);if(previous!=null&&now>=previous&&now-previous<5)return false;
        if(requests.size()>=4096)requests.entrySet().removeIf(e->now<e.getValue()||now-e.getValue()>=20);
        if(requests.size()>=4096&&!requests.containsKey(player))return false;
        requests.put(player,now);return true;
    }
    public static boolean canManage(ServerPlayer player,AirportBlockEntity airport){
        if(player==null||player instanceof FakePlayer||airport==null||airport.isRemoved()||airport.getLevel()!=player.serverLevel()
                ||!player.serverLevel().hasChunkAt(airport.getBlockPos())||!Double.isFinite(player.distanceToSqr(airport.getBlockPos().getCenter()))
                ||player.distanceToSqr(airport.getBlockPos().getCenter())>64||!airport.canManageDock(player))return false;
        var town=TownService.getManagedTownAt(player,airport.getBlockPos());return town!=null&&town.townId().equals(airport.getTownId());
    }
    static String rejection(boolean realPlayer,boolean sameLevel,boolean owner,boolean sameTown,double distanceSquared,long expected,long current,boolean occupied){
        if(!realPlayer)return "missing_sender";if(!sameLevel||!owner||!sameTown)return "forbidden";
        if(!Double.isFinite(distanceSquared)||distanceSquared<0||distanceSquared>64)return "out_of_range";
        if(expected!=current||expected<0||current>=Long.MAX_VALUE-1)return "stale_revision";if(occupied)return "occupied";return "ok";
    }
    public static Result apply(ServerPlayer player,BlockPos airportPos,long expectedRevision,CompoundTag action){
        if(player==null||player instanceof FakePlayer)return new Result(false,"missing_sender",-1);
        ServerLevel level=player.serverLevel();
        if(airportPos==null||!level.hasChunkAt(airportPos)||!(level.getBlockEntity(airportPos) instanceof AirportBlockEntity airport))return new Result(false,"unloaded_airport",-1);
        long revision=airport.editorRevision();
        if(player.distanceToSqr(airportPos.getCenter())>64)return new Result(false,"out_of_range",revision);
        if(!canManage(player,airport))return new Result(false,"forbidden",revision);
        String gate=rejection(true,true,true,true,player.distanceToSqr(airportPos.getCenter()),expectedRevision,revision,false);
        if(!gate.equals("ok"))return new Result(false,gate,revision);
        if(action==null)return new Result(false,"invalid_action",revision);
        String kind=action.getString("Action");
        synchronized(REQUESTS){if(!acceptRequest(REQUESTS.computeIfAbsent(level.getServer(),server->new HashMap<>()),player.getUUID(),level.getGameTime()))return new Result(false,"rate_limited",revision);}
        if (kind.equals("rename")) {
            String name = action.getString("Name").trim();
            if (!FacilityNames.validOverride(name)) return new Result(false,"invalid_geometry",revision);
            if (AirportSavedData.get(level).hasOtherRouteName(level.dimension().location().toString(), airportPos, name))
                return new Result(false,"duplicate_name",revision);
            boolean renamed = airport.renameFromEditor(expectedRevision, name);
            return new Result(renamed, renamed ? "ok" : "stale_revision", airport.editorRevision());
        }
        if(kind.equals("validate")){
            var facility=airport.facilities().stream().filter(f->f.id().equals(action.getString("Id"))).findFirst().orElse(null);
            if(facility==null)return new Result(false,"invalid_action",revision);
            var proposal=new AirportEditorSession.Proposal(airport.facilities(),airport.groundLayout(),facility,true);
            return queue(player,airport,revision,proposal);
        }
        List<AirportFacility> facilities=new ArrayList<>(airport.facilities());
        List<AirportParkingStand> stands=new ArrayList<>(airport.groundLayout().stands());
        List<AirportTaxiRoute> routes=new ArrayList<>(airport.groundLayout().taxiRoutes());
        try{
            switch(kind){
                case "upsert_facility"->{var value=AirportFacility.load(action.getCompound("Facility"));facilities.removeIf(f->f.id().equals(value.id()));facilities.add(value);}
                case "remove_facility"->{if(!facilities.removeIf(f->f.id().equals(action.getString("Id"))))return new Result(false,"invalid_action",revision);}
                case "upsert_stand"->{var value=AirportParkingStand.load(action.getCompound("Stand"));stands.removeIf(s->s.id().equals(value.id()));stands.add(value);}
                case "remove_stand"->{if(!stands.removeIf(s->s.id().equals(action.getString("Id"))))return new Result(false,"invalid_action",revision);}
                case "upsert_taxi_route"->{var value=AirportTaxiRoute.load(action.getCompound("TaxiRoute"));routes.removeIf(r->r.id().equals(value.id()));routes.add(value);}
                case "remove_taxi_route"->{if(!routes.removeIf(r->r.id().equals(action.getString("Id"))))return new Result(false,"invalid_action",revision);}
                default->{return new Result(false,"invalid_action",revision);}
            }
            AirportGroundLayout candidate=new AirportGroundLayout(airport.groundLayoutRevision()+1,stands,routes);
            if(facilities.size()>16)return new Result(false,"too_large",revision);
            var references=AirportGroundValidation.references(candidate,facilities);
            if(references!=AirportGroundValidation.Result.VALID)return new Result(false,references.name().toLowerCase(Locale.ROOT),revision);
            if(airport.configurationOccupied(facilities,candidate))return new Result(false,"occupied",revision);
            for(var facility:facilities){
                double radius=facility.managementDistance();
                if(facility.start().distSqr(airportPos)>radius*radius||facility.end().distSqr(airportPos)>radius*radius)return new Result(false,"invalid_geometry",revision);
            }
            for(var stand:stands)if(stand.position().distanceToSqr(airportPos.getCenter())>512D*512)return new Result(false,"invalid_geometry",revision);
            for(var route:routes)if(!AirportGroundValidation.withinManagementDistance(route,facilities,stands,airportPos))return new Result(false,"invalid_geometry",revision);
            AirportFacility changed=kind.equals("upsert_facility")?AirportFacility.load(action.getCompound("Facility")):null;
            return queue(player,airport,revision,new AirportEditorSession.Proposal(facilities,candidate,changed,false));
        }catch(IllegalArgumentException|NullPointerException failure){return new Result(false,"invalid_geometry",revision);}
    }
    private static Result queue(ServerPlayer player,AirportBlockEntity airport,long revision,AirportEditorSession.Proposal proposal){
        var request=new AirportEditorSession(player.getUUID(),revision,player.serverLevel().getGameTime(),proposal,airport.getTownId());
        if(!request.result().equals("pending"))return new Result(false,request.result(),revision);
        if(!airport.queueEditor(request))return new Result(false,"occupied",revision);
        return new Result(false,"pending",revision);
    }
    /** Called only by the airport BE ticker; shares its existing per-tick budget with flight preflights. */
    public static int advanceEditor(AirportBlockEntity airport,int budget){
        var request=airport.editorSession();
        if(request==null||!(airport.getLevel() instanceof ServerLevel level))return 0;
        ServerPlayer player=level.getServer().getPlayerList().getPlayer(request.owner);
        long now=level.getGameTime();
        boolean authorized=player!=null&&player.serverLevel()==level&&canManage(player,airport);
        if(!request.isCurrent(airport.editorSession(),request.owner,airport.editorRevision(),authorized,now)){
            String code=airport.editorRevision()!=request.baseRevision?"stale_revision":"forbidden";
            airport.clearEditor(request);reply(player,airport,code);return 0;
        }
        if(airport.configurationOccupied(request.proposal.facilities(),request.proposal.ground())){
            airport.clearEditor(request);reply(player,airport,"occupied");return 0;
        }
        Function<BlockPos,String> townAt=pos->{var town=TownService.getManagedTownAt(player,pos);return town==null?"":town.townId();};
        NationSavedData authority=NationSavedData.get(level);
        String result=request.advance(Math.max(0,budget),townAt,p->!level.isOutsideBuildHeight(p)&&level.hasChunkAt(p),p->clear(level,p),p->support(level,p),authority::claimRevision);
        int used=request.stepsUsed();
        if(result.equals("pending")){if(now%20==0)reply(player,airport,"pending");return used;}
        if(result.equals("valid")){
            boolean current=request.canCommit(airport.editorSession(),player.getUUID(),airport.editorRevision(),canManage(player,airport),now,authority.claimRevision());
            if(!current)result="stale_revision";
            else if(!request.loadedBeforeCommit(p->!level.isOutsideBuildHeight(p)&&level.hasChunkAt(p)))result="unloaded";
            else if(request.proposal.validationOnly()){
                airport.editorValidatedFacility(request.proposal.changedRunway());
                result="valid";
            }else if(airport.replaceGroundConfiguration(request.proposal.ground().revision()-1,request.proposal.facilities(),request.proposal.ground())){
                result="ok";
            }else result="occupied";
        }
        // An overwritten/cancelled job can never publish an old completion into a newer request.
        if(airport.editorSession()==request){airport.clearEditor(request);reply(player,airport,result);}
        return used;
    }
    private static void reply(ServerPlayer player,AirportBlockEntity airport,String code){
        if(player!=null&&player.serverLevel()==airport.getLevel()) {
            ModNetwork.CHANNEL.send(player,new AirportSnapshotPacket(AirportScreenData.capture(player,airport,code),false));
            if(!code.equals("pending")) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("screen.sailboatmod.airport.result."+code));
        }
    }
    private static String toCode(AirportValidation.Result result){return result==AirportValidation.Result.VALID?"ok":result.name().toLowerCase(Locale.ROOT);}
    private static boolean clear(ServerLevel level,BlockPos pos){return level.hasChunkAt(pos)&&level.getFluidState(pos).isEmpty()&&level.getBlockState(pos).getCollisionShape(level,pos).isEmpty();}
    private static boolean support(ServerLevel level,BlockPos pos){return level.hasChunkAt(pos)&&level.getBlockState(pos).isFaceSturdy(level,pos,Direction.UP);}
    public static boolean withinManagedTown(AABB area,String townId,Function<BlockPos,String> managedTown){
        if(area==null||townId==null||townId.isBlank()||managedTown==null||!Double.isFinite(area.minX)||!Double.isFinite(area.maxX)||!Double.isFinite(area.minZ)||!Double.isFinite(area.maxZ)
                ||area.getXsize()>2048||area.getZsize()>2048)return false;
        int minX=(int)Math.floor(area.minX)>>4,maxX=((int)Math.ceil(area.maxX)-1)>>4,minZ=(int)Math.floor(area.minZ)>>4,maxZ=((int)Math.ceil(area.maxZ)-1)>>4;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(!townId.equals(managedTown.apply(new BlockPos(x<<4,(int)area.minY,z<<4))))return false;return true;
    }
}
