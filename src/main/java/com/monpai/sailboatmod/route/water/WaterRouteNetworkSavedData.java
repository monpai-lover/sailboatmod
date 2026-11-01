package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.RouteNbtUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** World/dimension-local navigation infrastructure; voyage cargo/owner/progress is never stored here.
 * Mutations run on the server thread. Worker snapshots are immutable and cannot alter active trips.
 */
public final class WaterRouteNetworkSavedData extends SavedData {
    private static final String NAME="sailboatmod_water_route_network";
    private static final int MAX_ROUTES=256;
    private final LinkedHashMap<String,WaterRouteNetwork.Route> routes=new LinkedHashMap<>();
    public static WaterRouteNetworkSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(WaterRouteNetworkSavedData::new,
                WaterRouteNetworkSavedData::load,null),NAME);
    }
    public List<WaterRouteNetwork.Route> snapshot() {return List.copyOf(routes.values());}
    public void register(BlockPos source,BlockPos target,RouteDefinition definition) {
        if(source==null||target==null||definition==null||definition.waypoints().size()<2
                ||definition.waypoints().size()>65_536)return;
        String id=Long.toString(source.asLong())+":"+target.asLong();
        WaterRouteNetwork.Route entry=new WaterRouteNetwork.Route(id,source.immutable(),target.immutable(),definition.waypoints(),true);
        if(entry.equals(routes.get(id)))return;
        routes.remove(id);routes.put(id,entry);
        int count=routes.values().stream().mapToInt(r->r.points().size()).sum();
        var it=routes.entrySet().iterator();
        while(it.hasNext()&&(routes.size()>MAX_ROUTES||count>WaterRouteNetwork.MAX_GRAPH_POINTS)) {count-=it.next().getValue().points().size();it.remove();}
        setDirty();
    }
    public void importDock(com.monpai.sailboatmod.block.entity.DockBlockEntity dock) {
        if(!PortDockSnapshot.isPort(dock))return;
        for(RouteDefinition route:dock.getRoutesForMap()) {
            if(!WaterAutoRouteService.isGeneratedWaterRoute(route)||route.waypoints().size()<2)continue;
            // A legacy endpoint is a geometric junction, not a guessed remote dock identity.
            register(dock.getBlockPos(),BlockPos.containing(route.waypoints().getLast()),route);
        }
    }
    public void invalidate(Set<String> ids) {boolean changed=false;for(String id:ids)changed|=routes.remove(id)!=null;if(changed)setDirty();}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        ListTag list=new ListTag();
        for(var r:routes.values()) {
            CompoundTag e=new CompoundTag();e.putLong("Source",r.sourceDock().asLong());e.putLong("Target",r.targetDock().asLong());
            RouteNbtUtil.writeRoutes(e,"Geometry",List.of(new RouteDefinition(r.id(),r.points())));list.add(e);
        }
        tag.putInt("Schema",1);tag.put("Routes",list);return tag;
    }
    private static WaterRouteNetworkSavedData load(CompoundTag tag,HolderLookup.Provider registries) {
        WaterRouteNetworkSavedData data=new WaterRouteNetworkSavedData();
        if(tag.getInt("Schema") != 1)return data;
        ListTag list=tag.getList("Routes",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(MAX_ROUTES,list.size());i++) {
            CompoundTag e=list.getCompound(i);var geometry=RouteNbtUtil.readRoutes(e,"Geometry");
            if(!geometry.isEmpty())data.register(BlockPos.of(e.getLong("Source")),BlockPos.of(e.getLong("Target")),geometry.getFirst());
        }
        data.setDirty(false);return data;
    }
}
