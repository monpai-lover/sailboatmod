package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Immutable server snapshot. No client draft is used as authoritative airport state. */
public record AirportScreenData(ResourceLocation dimension, BlockPos airportPos, String name, String town, String owner, boolean canManage,
        long revision, List<AirportFacility> facilities, AirportGroundLayout groundLayout,
        List<String> statuses, String result, String customName, List<RouteTarget> routeTargets) {
    private static final String AUTO_LAYOUT_FEATURE = "feature|auto_layout|1";
    private static final String PREFAB_FEATURE = "feature|airport_prefab|1";
    private static final String LIT_PREFAB_FEATURE = "feature|airport_lit_prefab|1";
    private static final String EDITOR_ISSUE_PREFIX = "editor_issue||";
    private static final String ROUTE_PUBLICATION_FEATURE = "feature|route_publication|1";
    private static final String ROUTE_NAME_CONFLICT = "feature|route_name_conflict|1";
    public record RouteTarget(BlockPos pos, String name, int suggestedCruiseY, boolean canPublishReturn) {
        public RouteTarget {
            pos = pos.immutable();
            if (name.isBlank() || name.length() > 128) throw new IllegalArgumentException("Invalid airport name");
        }
    }

    public AirportScreenData {
        customName = customName == null ? "" : customName.trim();
        if (dimension == null || dimension.toString().length() > 256) throw new IllegalArgumentException("Invalid dimension");
        airportPos = airportPos.immutable(); facilities = List.copyOf(facilities); statuses = List.copyOf(statuses);
        routeTargets = List.copyOf(routeTargets);
        if (facilities.size() > 16 || groundLayout.stands().size() > 32 || groundLayout.taxiRoutes().size() > 64 || statuses.size() > 160)
            throw new IllegalArgumentException("Snapshot too large");
        if (routeTargets.size() > AirportRoutePublication.MAX_TARGETS) throw new IllegalArgumentException("Too many airports");
        if (revision != groundLayout.revision()) throw new IllegalArgumentException("Conflicting snapshot revisions");
        if (customName.length() > 128 || revision < 0 || name.length() > 128 || town.length() > 128 || owner.length() > 128 || result.length() > 64
                || statuses.stream().anyMatch(value -> value.length() > 256)) throw new IllegalArgumentException("Invalid snapshot");
    }
    public AirportScreenData(ResourceLocation dimension, BlockPos airportPos, String name, String town, String owner, boolean canManage,
            long revision, List<AirportFacility> facilities, AirportGroundLayout groundLayout, List<String> statuses, String result) {
        this(dimension, airportPos, name, town, owner, canManage, revision, facilities, groundLayout, statuses, result, name);
    }
    public AirportScreenData(ResourceLocation dimension, BlockPos airportPos, String name, String town, String owner, boolean canManage,
            long revision, List<AirportFacility> facilities, AirportGroundLayout groundLayout, List<String> statuses, String result, String customName) {
        this(dimension, airportPos, name, town, owner, canManage, revision, facilities, groundLayout, statuses, result, customName, List.of());
    }
    public boolean supportsAutoLayout() { return statuses.contains(AUTO_LAYOUT_FEATURE); }
    public boolean supportsPrefab() { return statuses.contains(PREFAB_FEATURE); }
    public boolean supportsLitPrefab() { return statuses.contains(LIT_PREFAB_FEATURE); }
    public Optional<BlockPos> editorIssue() {
        for (String status : statuses) {
            if (!status.startsWith(EDITOR_ISSUE_PREFIX)) continue;
            try { return Optional.of(BlockPos.of(Long.parseLong(status.substring(EDITOR_ISSUE_PREFIX.length())))); }
            catch (NumberFormatException ignored) { return Optional.empty(); }
        }
        return Optional.empty();
    }
    public boolean supportsRoutePublication() { return statuses.contains(ROUTE_PUBLICATION_FEATURE); }
    public boolean hasRouteNameConflict() { return statuses.contains(ROUTE_NAME_CONFLICT); }
    public static AirportScreenData capture(ServerPlayer player, AirportBlockEntity airport, String result) {
        return capture(player, airport, result, null);
    }
    public static AirportScreenData capture(ServerPlayer player, AirportBlockEntity airport, String result, BlockPos issue) {
        List<String> statuses = new ArrayList<>();
        for (AirportFacility facility : airport.facilities())
            statuses.add("runway|" + facility.id() + "|" + airport.facilityStatus(facility.id()).name().toLowerCase(java.util.Locale.ROOT));
        for (AirportParkingStand stand : airport.groundLayout().stands()) statuses.add("stand|"+stand.id()+"|"+airport.standStatus(stand.id()));
        for (AirportTaxiRoute route : airport.groundLayout().taxiRoutes()) statuses.add("taxi|"+route.id()+"|"+airport.taxiStatus(route.id()));
        for (var lease : airport.reservations()) statuses.add("reservation|" + lease.facilityId() + "|reserved");
        if (airport.groundReserved()) statuses.add("ground||reserved");
        if (airport.editorSession() != null) {
            statuses.add("editor||" + airport.editorSession().processedSteps());
            if (airport.editorSession().loadingTerrain()) statuses.add("editor_terrain||loading");
        }
        if (issue != null && statuses.size() < 160) statuses.add(EDITOR_ISSUE_PREFIX + issue.asLong());
        if (statuses.size() < 160) statuses.add(AUTO_LAYOUT_FEATURE);
        if (statuses.size() < 160) statuses.add(PREFAB_FEATURE);
        if (statuses.size() < 160) statuses.add(ROUTE_PUBLICATION_FEATURE);
        var town = com.monpai.sailboatmod.nation.service.TownService.getTownAt(player.serverLevel(),airport.getBlockPos());
        boolean canManage = AirportManagementService.canManage(player,airport);
        if (canManage && !airport.facilities().isEmpty() && statuses.size() < 160
                && AirportRoutePublication.sourceNameConflict(player.serverLevel(), airport)) statuses.add(ROUTE_NAME_CONFLICT);
        if (statuses.size() < 160) statuses.add(LIT_PREFAB_FEATURE);
        List<RouteTarget> targets = canManage ? AirportRoutePublication.targets(player, airport) : List.of();
        long editorRevision = airport.editorRevision();
        AirportGroundLayout layout = airport.groundLayout();
        // The wire layout belongs to the UI draft. Keep its existing shared version contract.
        if (layout.revision() != editorRevision) layout = layout.withRevision(editorRevision);
        return new AirportScreenData(player.serverLevel().dimension().location(),airport.getBlockPos(),airport.getDockName(),town == null ? "" : town.name(),airport.getOwnerName(),
                canManage,editorRevision,airport.facilities(),layout,statuses,result,airport.getCustomDockName(),targets);
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putString("Dimension",dimension.toString()); tag.putLong("Pos",airportPos.asLong()); tag.putString("Name",name); tag.putString("CustomName",customName);
        tag.putString("Town",town == null ? "" : town); tag.putString("Owner",owner); tag.putBoolean("CanManage",canManage); tag.putLong("Revision",revision);
        ListTag values = new ListTag(); facilities.forEach(value -> values.add(value.save())); tag.put("Facilities",values);
        tag.put("Ground",groundLayout.save()); ListTag lines = new ListTag(); statuses.forEach(value -> lines.add(StringTag.valueOf(value)));
        tag.put("Statuses",lines); tag.putString("Result",result);
        ListTag targets = new ListTag();
        for (RouteTarget routeTarget : routeTargets) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", routeTarget.pos().asLong());
            entry.putString("Name", routeTarget.name());
            entry.putInt("CruiseY", routeTarget.suggestedCruiseY());
            entry.putBoolean("Return", routeTarget.canPublishReturn());
            targets.add(entry);
        }
        tag.put("RouteTargets", targets);
        return tag;
    }
    public static AirportScreenData load(CompoundTag tag) {
        if (!tag.contains("Dimension", Tag.TAG_STRING)) throw new IllegalArgumentException("Missing dimension");
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimension == null) throw new IllegalArgumentException("Invalid dimension");
        ListTag raw = tag.getList("Facilities",Tag.TAG_COMPOUND), lines = tag.getList("Statuses",Tag.TAG_STRING);
        if (raw.size() > 16 || lines.size() > 160) throw new IllegalArgumentException("Snapshot too large");
        List<AirportFacility> facilities = new ArrayList<>(); raw.forEach(value -> facilities.add(AirportFacility.load((CompoundTag)value)));
        List<String> statuses = new ArrayList<>(); lines.forEach(value -> statuses.add(value.getAsString()));
        ListTag rawTargets = tag.getList("RouteTargets", Tag.TAG_COMPOUND);
        if (rawTargets.size() > AirportRoutePublication.MAX_TARGETS) throw new IllegalArgumentException("Too many airports");
        List<RouteTarget> targets = new ArrayList<>();
        for (Tag rawTarget : rawTargets) {
            CompoundTag entry = (CompoundTag) rawTarget;
            targets.add(new RouteTarget(BlockPos.of(entry.getLong("Pos")), entry.getString("Name"),
                    entry.getInt("CruiseY"), entry.getBoolean("Return")));
        }
        return new AirportScreenData(dimension,BlockPos.of(tag.getLong("Pos")),tag.getString("Name"),tag.getString("Town"),tag.getString("Owner"),tag.getBoolean("CanManage"),
                tag.getLong("Revision"),facilities,AirportGroundLayout.load(tag.getCompound("Ground")),statuses,tag.getString("Result"),
                tag.contains("CustomName",Tag.TAG_STRING)?tag.getString("CustomName"):tag.getString("Name"),targets);
    }
}
