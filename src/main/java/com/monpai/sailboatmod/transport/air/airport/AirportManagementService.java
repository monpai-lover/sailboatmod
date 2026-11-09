package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import com.monpai.sailboatmod.dock.FacilityNames;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.airport.AirportSnapshotPacket;

/** Single server authority for airport UI and command edits, including bounded auto-layout terrain admission. */
@EventBusSubscriber(modid = SailboatMod.MODID)
public final class AirportManagementService {
    public record Result(boolean success,String code,long revision){}
    private static final Map<MinecraftServer,Map<UUID,Long>> REQUESTS=new WeakHashMap<>();
    private static final int MAX_AUTO_EDITS = 64;
    private static final Map<MinecraftServer, Map<AirportBlockEntity, AirportEditorSession>> AUTO_EDITS = new WeakHashMap<>();
    private AirportManagementService(){}
    static boolean acceptRequest(Map<UUID,Long> requests,UUID player,long now){
        Long previous=requests.get(player);if(previous!=null&&now>=previous&&now-previous<5)return false;
        if(requests.size()>=4096)requests.entrySet().removeIf(e->now<e.getValue()||now-e.getValue()>=20);
        if(requests.size()>=4096&&!requests.containsKey(player))return false;
        requests.put(player,now);return true;
    }
    public static boolean canManage(ServerPlayer player,AirportBlockEntity airport){
        if (player == null || airport == null) return false;
        double distance = player.distanceToSqr(airport.getBlockPos().getCenter());
        return Double.isFinite(distance) && distance <= 64 && canManageSite(player, airport);
    }
    static boolean canManageSite(ServerPlayer player, AirportBlockEntity airport) {
        if(player==null||player instanceof FakePlayer||airport==null||airport.isRemoved()||airport.getLevel()!=player.serverLevel()
                ||!player.serverLevel().hasChunkAt(airport.getBlockPos())||!airport.canManageDock(player))return false;
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
        if (kind.equals("auto_layout")) {
            if (!action.contains("CornerA", Tag.TAG_LONG) || !action.contains("CornerB", Tag.TAG_LONG))
                return new Result(false, "invalid_geometry", revision);
            BlockPos cornerA = BlockPos.of(action.getLong("CornerA"));
            BlockPos cornerB = BlockPos.of(action.getLong("CornerB"));
            var planned = AirportAutoLayout.propose(cornerA, cornerB, airport.facilities(), airport.groundLayout());
            if (planned.isEmpty()) return new Result(false, "invalid_geometry", revision);
            var proposal = planned.orElseThrow();
            if (action.getBoolean("Prefab")) {
                var blocks = action.getBoolean("LitPrefab") ? AirportPrefab.planDetailed(proposal, cornerA, cornerB)
                        : AirportPrefab.plan(proposal, cornerA, cornerB).map(positions -> positions.stream()
                                .map(pos -> new AirportPrefab.Piece(pos, AirportPrefab.Part.STRUCTURE)).toList());
                if (blocks.isEmpty()) return new Result(false, "prefab_invalid", revision);
                proposal = new AirportEditorSession.Proposal(proposal.facilities(), proposal.ground(), proposal.changedRunway(),
                        proposal.validationOnly(), proposal.additionalChecks(), blocks.orElseThrow());
            }
            return validateAndQueue(player, airport, revision, proposal, true);
        }
        if (kind.equals("publish_route")) {
            if (!action.contains("Target", Tag.TAG_LONG) || !action.contains("CruiseY", Tag.TAG_INT))
                return new Result(false, "invalid_geometry", revision);
            String result = AirportRoutePublication.publish(player, airport, BlockPos.of(action.getLong("Target")),
                    action.getInt("CruiseY"), action.getBoolean("Return"));
            return new Result(result.equals("ok"), result, airport.editorRevision());
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
            AirportFacility changed=kind.equals("upsert_facility")?AirportFacility.load(action.getCompound("Facility")):null;
            return validateAndQueue(player,airport,revision,new AirportEditorSession.Proposal(facilities,candidate,changed,false));
        }catch(IllegalArgumentException|NullPointerException failure){return new Result(false,"invalid_geometry",revision);}
    }
    private static Result validateAndQueue(ServerPlayer player, AirportBlockEntity airport, long revision,
                                           AirportEditorSession.Proposal proposal) {
        return validateAndQueue(player, airport, revision, proposal, false);
    }
    private static Result validateAndQueue(ServerPlayer player, AirportBlockEntity airport, long revision,
                                           AirportEditorSession.Proposal proposal, boolean loadTerrain) {
        var facilities = proposal.facilities();
        var ground = proposal.ground();
        var airportPos = airport.getBlockPos();
        if (facilities.size() > 16) return new Result(false, "too_large", revision);
        var references = AirportGroundValidation.references(ground, facilities);
        if (references != AirportGroundValidation.Result.VALID)
            return new Result(false, references.name().toLowerCase(Locale.ROOT), revision);
        if (airport.configurationOccupied(facilities, ground)) return new Result(false, "occupied", revision);
        for (var facility : facilities) {
            double radius = facility.managementDistance();
            if (facility.start().distSqr(airportPos) > radius * radius || facility.end().distSqr(airportPos) > radius * radius)
                return new Result(false, "invalid_geometry", revision);
        }
        for (var stand : ground.stands())
            if (stand.position().distanceToSqr(airportPos.getCenter()) > 512D * 512)
                return new Result(false, "invalid_geometry", revision);
        for (var route : ground.taxiRoutes())
            if (!AirportGroundValidation.withinManagementDistance(route, facilities, ground.stands(), airportPos))
                return new Result(false, "invalid_geometry", revision);
        return queue(player, airport, revision, proposal, loadTerrain);
    }
    private static Result queue(ServerPlayer player, AirportBlockEntity airport, long revision, AirportEditorSession.Proposal proposal) {
        return queue(player, airport, revision, proposal, false);
    }
    private static Result queue(ServerPlayer player, AirportBlockEntity airport, long revision, AirportEditorSession.Proposal proposal,
                                boolean loadTerrain) {
        ServerLevel level = player.serverLevel();
        var request = new AirportEditorSession(player.getUUID(), revision, level.getGameTime(), proposal, airport.getTownId(),
                loadTerrain ? AirportEditorTerrain.forLevel(level) : null);
        if (!request.result().equals("pending")) return new Result(false, request.result(), revision);
        Map<AirportBlockEntity, AirportEditorSession> active = loadTerrain
                ? AUTO_EDITS.computeIfAbsent(level.getServer(), server -> new LinkedHashMap<>()) : null;
        if (active != null && active.size() >= MAX_AUTO_EDITS && !active.containsKey(airport))
            return new Result(false, "rate_limited", revision);
        if (!airport.queueEditor(request)) return new Result(false, "occupied", revision);
        if (active != null) active.put(airport, airport.editorSession());
        return new Result(false, "pending", revision);
    }
    /** The airport can stop ticking when its owner leaves, even while its FULL terrain lease keeps it accessible. */
    @SubscribeEvent
    public static void checkAutoEditOwners(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) return;
        var active = AUTO_EDITS.get(server);
        if (active == null) return;
        var iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var airport = entry.getKey();
            var request = entry.getValue();
            if (airport.editorSession() != request || !(airport.getLevel() instanceof ServerLevel level)) {
                request.cancel();
                iterator.remove();
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(request.owner);
            long now = level.getGameTime();
            boolean authorized = player != null && canManage(player, airport);
            if (!request.isCurrent(airport.editorSession(), request.owner, airport.editorRevision(), authorized, now)) {
                airport.clearEditor(request);
                iterator.remove();
                reply(player, airport, staleCode(request, airport, player, authorized, now), null);
            }
        }
        if (active.isEmpty()) AUTO_EDITS.remove(server);
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        var active = AUTO_EDITS.remove(event.getServer());
        if (active != null) active.forEach((airport, request) -> {
            airport.clearEditor(request);
            request.cancel();
        });
    }

    private static String staleCode(AirportEditorSession request, AirportBlockEntity airport, @Nullable ServerPlayer player,
                                    boolean authorized, long now) {
        if (airport.editorRevision() != request.baseRevision) return "stale_revision";
        if (authorized && request.expired(now)) return "validation_timeout";
        if (player != null && player.serverLevel() == airport.getLevel()
                && player.distanceToSqr(airport.getBlockPos().getCenter()) > 64) return "out_of_range";
        return "forbidden";
    }
    /** Called only by the airport BE ticker; shares its existing per-tick budget with flight preflights. */
    public static int advanceEditor(AirportBlockEntity airport,int budget){
        var request=airport.editorSession();
        if(request==null||!(airport.getLevel() instanceof ServerLevel level))return 0;
        ServerPlayer player=level.getServer().getPlayerList().getPlayer(request.owner);
        long now=level.getGameTime();
        boolean authorized=player!=null&&player.serverLevel()==level&&canManage(player,airport);
        if(!request.isCurrent(airport.editorSession(),request.owner,airport.editorRevision(),authorized,now)){
            String code = staleCode(request, airport, player, authorized, now);
            airport.clearEditor(request);reply(player,airport,code,null);return 0;
        }
        if(airport.configurationOccupied(request.proposal.facilities(),request.proposal.ground())){
            airport.clearEditor(request);reply(player,airport,"occupied",null);return 0;
        }
        Function<BlockPos,String> townAt=pos->{var town=TownService.getManagedTownAt(player,pos);return town==null?"":town.townId();};
        NationSavedData authority=NationSavedData.get(level);
        String result=request.advance(Math.max(0,budget),townAt,p->!level.isOutsideBuildHeight(p)&&level.hasChunkAt(p),p->clear(level,p),p->support(level,p),authority::claimRevision);
        int used=request.stepsUsed();
        if(result.equals("pending")){if(now%20==0)reply(player,airport,"pending",null);return used;}
        if(result.equals("valid")){
            boolean current=request.canCommit(airport.editorSession(),player.getUUID(),airport.editorRevision(),canManage(player,airport),now,authority.claimRevision());
            if(!current)result="stale_revision";
            else if(!request.loadedBeforeCommit(p->!level.isOutsideBuildHeight(p)&&level.hasChunkAt(p)))result="unloaded";
            else if(request.proposal.validationOnly()){
                airport.editorValidatedFacility(request.proposal.changedRunway());
                result="valid";
            } else if (!request.proposal.prefabBlocks().isEmpty()) {
                result = AirportPrefab.install(player, airport, request.proposal, request::recordFailurePosition);
            }else if(airport.replaceGroundConfiguration(request.proposal.ground().revision()-1,request.proposal.facilities(),request.proposal.ground())){
                result="ok";
            }else result="occupied";
        }
        // An overwritten/cancelled job can never publish an old completion into a newer request.
        if(airport.editorSession()==request){airport.clearEditor(request);reply(player,airport,result,request.failurePosition());}
        return used;
    }
    private static void reply(ServerPlayer player, AirportBlockEntity airport, String code, @Nullable BlockPos failurePosition) {
        if(player!=null&&player.serverLevel()==airport.getLevel()) {
            ModNetwork.CHANNEL.send(player, new AirportSnapshotPacket(AirportScreenData.capture(player, airport, code, failurePosition), false));
            if (!code.equals("pending")) {
                var message = net.minecraft.network.chat.Component.translatable("screen.sailboatmod.airport.result." + code);
                if (failurePosition != null) message.append(" · ").append(failurePosition.toShortString());
                player.sendSystemMessage(message);
            }
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
