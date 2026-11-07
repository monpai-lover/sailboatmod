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
    private final LinkedHashSet<String> removedLegacyGeometry=new LinkedHashSet<>();
    private static String geometryKey(List<net.minecraft.world.phys.Vec3> points) {
        StringBuilder forward=new StringBuilder(),reverse=new StringBuilder();
        for(var p:points)forward.append(Double.toHexString(p.x)).append(',').append(Double.toHexString(p.y)).append(',').append(Double.toHexString(p.z)).append(';');
        for(int i=points.size()-1;i>=0;i--){var p=points.get(i);reverse.append(Double.toHexString(p.x)).append(',').append(Double.toHexString(p.y)).append(',').append(Double.toHexString(p.z)).append(';');}
        String a=forward.toString(),b=reverse.toString();
        return UUID.nameUUIDFromBytes((a.compareTo(b)<0?a:b).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    public static WaterRouteNetworkSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(WaterRouteNetworkSavedData::new,
                WaterRouteNetworkSavedData::load,null),NAME);
    }
    private long revision;
    public long revision() { return revision; }
    public void invalidateDock(BlockPos dock) {
        Set<String> ids=new HashSet<>();
        for(var route:routes.values()) if(route.sourceDock().equals(dock)||route.targetDock().equals(dock))ids.add(route.id());
        invalidate(ids);
    }
    public void invalidateGeometry(List<net.minecraft.world.phys.Vec3> geometry) {
        Set<String> ids=new HashSet<>(); var reverse=new ArrayList<>(geometry); Collections.reverse(reverse);
        for(var route:routes.values()) if(route.points().equals(geometry)||route.points().equals(reverse))ids.add(route.id());
        removedLegacyGeometry.add(geometryKey(geometry));
        while(removedLegacyGeometry.size()>4096)removedLegacyGeometry.removeFirst();
        invalidate(ids); revision++; setDirty();
    }
    public List<WaterRouteNetwork.Route> snapshot() {return List.copyOf(routes.values());}
    public void register(BlockPos source,BlockPos target,RouteDefinition definition) { register(source,target,definition,true); }
    public void register(BlockPos source,BlockPos target,RouteDefinition definition,boolean reversible) {
        if(source==null||target==null||definition==null||definition.waypoints().size()<2
                ||definition.waypoints().size()>65_536)return;
        String id=Long.toString(source.asLong())+":"+target.asLong();
        WaterRouteNetwork.Route entry=new WaterRouteNetwork.Route(id,source.immutable(),target.immutable(),definition.waypoints(),reversible);
        removedLegacyGeometry.remove(geometryKey(definition.waypoints()));
        if(entry.equals(routes.get(id)))return;
        routes.remove(id);routes.put(id,entry);
        int count=routes.values().stream().mapToInt(r->r.points().size()).sum();
        var it=routes.entrySet().iterator();
        while(it.hasNext()&&(routes.size()>MAX_ROUTES||count>WaterRouteNetwork.MAX_GRAPH_POINTS)) {count-=it.next().getValue().points().size();it.remove();}
        revision++; setDirty();
    }
    /** Import only legacy endpoints that can be tied unambiguously to registered real dock IDs.
     * Loading a chunk must not manufacture a port ID from its berth coordinate.
     */
    public void importDock(com.monpai.sailboatmod.block.entity.DockBlockEntity dock) {
        if(!PortDockSnapshot.isPort(dock))return;
        importRoutes(dock,dock.getRoutesForMap(),false);
    }
    /** An explicit book import may restore only its newly added routes, after normal endpoint validation. */
    public void publishImportedBookRoutes(com.monpai.sailboatmod.block.entity.DockBlockEntity dock,
                                         List<RouteDefinition> importedRoutes) {
        importRoutes(dock,importedRoutes,true);
    }
    private void importRoutes(com.monpai.sailboatmod.block.entity.DockBlockEntity dock,
                              List<RouteDefinition> importedRoutes,boolean explicitImport) {
        if(!PortDockSnapshot.isPort(dock)||!(dock.getLevel() instanceof ServerLevel level))return;
        var terminals=com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData.get(level).all();
        String dimension=level.dimension().location().toString();
        for(RouteDefinition route:importedRoutes) {
            if(route.waypoints().size()<2||(!explicitImport&&removedLegacyGeometry.contains(geometryKey(route.waypoints()))))continue;
            if(routes.values().stream().anyMatch(r->r.sourceDock().equals(dock.getBlockPos())&&r.points().equals(route.waypoints())))continue;
            var end=route.waypoints().getLast();BlockPos target=null;boolean ambiguous=false;
            for(var port:terminals) {
                BlockPos pos=BlockPos.of(port.packedPos());
                if(!port.dimId().equals(dimension)||port.kind()!=com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData.Kind.PORT
                        ||pos.equals(dock.getBlockPos())||!port.dockName().equals(route.endDockName())
                        ||Math.abs(pos.getX()-end.x)>32||Math.abs(pos.getZ()-end.z)>32)continue;
                if(target!=null){ambiguous=true;break;}target=pos;
            }
            if(target!=null&&!ambiguous)register(dock.getBlockPos(),target,route);
        }
    }
    public void invalidate(Set<String> ids) {boolean changed=false;for(String id:ids)changed|=routes.remove(id)!=null;if(changed){revision++;setDirty();}}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        ListTag list=new ListTag();
        for(var r:routes.values()) {
            CompoundTag e=new CompoundTag();e.putLong("Source",r.sourceDock().asLong());e.putLong("Target",r.targetDock().asLong());e.putBoolean("Reversible",r.reversible());
            RouteNbtUtil.writeRoutes(e,"Geometry",List.of(new RouteDefinition(r.id(),r.points())));list.add(e);
        }
        tag.putInt("Schema",2);tag.put("Routes",list);
        ListTag removed=new ListTag();for(String key:removedLegacyGeometry)removed.add(StringTag.valueOf(key));
        tag.put("RemovedLegacyGeometry",removed);return tag;
    }
    private static WaterRouteNetworkSavedData load(CompoundTag tag,HolderLookup.Provider registries) {
        WaterRouteNetworkSavedData data=new WaterRouteNetworkSavedData();
        if(tag.getInt("Schema") != 1 && tag.getInt("Schema") != 2)return data;
        ListTag list=tag.getList("Routes",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(MAX_ROUTES,list.size());i++) {
            CompoundTag e=list.getCompound(i);var geometry=RouteNbtUtil.readRoutes(e,"Geometry");
            if(!geometry.isEmpty())data.register(BlockPos.of(e.getLong("Source")),BlockPos.of(e.getLong("Target")),geometry.getFirst(),!e.contains("Reversible")||e.getBoolean("Reversible"));
        }
        ListTag removed=tag.getList("RemovedLegacyGeometry",Tag.TAG_STRING);
        for(int i=0;i<Math.min(4096,removed.size());i++)data.removedLegacyGeometry.add(removed.getString(i));
        data.setDirty(false);return data;
    }
}
