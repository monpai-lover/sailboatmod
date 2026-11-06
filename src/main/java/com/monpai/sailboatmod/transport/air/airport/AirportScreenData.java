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

/** Immutable server snapshot. No client draft is used as authoritative airport state. */
public record AirportScreenData(ResourceLocation dimension, BlockPos airportPos, String name, String town, String owner, boolean canManage,
        long revision, List<AirportFacility> facilities, AirportGroundLayout groundLayout,
        List<String> statuses, String result) {
    public AirportScreenData {
        if (dimension == null || dimension.toString().length() > 256) throw new IllegalArgumentException("Invalid dimension");
        airportPos = airportPos.immutable(); facilities = List.copyOf(facilities); statuses = List.copyOf(statuses);
        if (facilities.size() > 16 || groundLayout.stands().size() > 32 || groundLayout.taxiRoutes().size() > 64 || statuses.size() > 160)
            throw new IllegalArgumentException("Snapshot too large");
        if (revision != groundLayout.revision()) throw new IllegalArgumentException("Conflicting snapshot revisions");
        if (revision < 0 || name.length() > 128 || town.length() > 128 || owner.length() > 128 || result.length() > 64
                || statuses.stream().anyMatch(value -> value.length() > 256)) throw new IllegalArgumentException("Invalid snapshot");
    }
    public static AirportScreenData capture(ServerPlayer player, AirportBlockEntity airport, String result) {
        List<String> statuses = new ArrayList<>();
        for (AirportFacility facility : airport.facilities())
            statuses.add("runway|" + facility.id() + "|" + airport.facilityStatus(facility.id()).name().toLowerCase(java.util.Locale.ROOT));
        for (AirportParkingStand stand : airport.groundLayout().stands()) statuses.add("stand|"+stand.id()+"|"+airport.standStatus(stand.id()));
        for (AirportTaxiRoute route : airport.groundLayout().taxiRoutes()) statuses.add("taxi|"+route.id()+"|"+airport.taxiStatus(route.id()));
        for (var lease : airport.reservations()) statuses.add("reservation|" + lease.facilityId() + "|reserved");
        if (airport.groundReserved()) statuses.add("ground||reserved");
        if (airport.editorSession()!=null) statuses.add("editor||"+airport.editorSession().processedSteps());
        var town = com.monpai.sailboatmod.nation.service.TownService.getTownAt(player.serverLevel(),airport.getBlockPos());
        return new AirportScreenData(player.serverLevel().dimension().location(),airport.getBlockPos(),airport.getDockName(),town == null ? "" : town.name(),airport.getOwnerName(),
                AirportManagementService.canManage(player,airport),airport.groundLayoutRevision(),airport.facilities(),airport.groundLayout(),statuses,result);
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putString("Dimension",dimension.toString()); tag.putLong("Pos",airportPos.asLong()); tag.putString("Name",name);
        tag.putString("Town",town == null ? "" : town); tag.putString("Owner",owner); tag.putBoolean("CanManage",canManage); tag.putLong("Revision",revision);
        ListTag values = new ListTag(); facilities.forEach(value -> values.add(value.save())); tag.put("Facilities",values);
        tag.put("Ground",groundLayout.save()); ListTag lines = new ListTag(); statuses.forEach(value -> lines.add(StringTag.valueOf(value)));
        tag.put("Statuses",lines); tag.putString("Result",result); return tag;
    }
    public static AirportScreenData load(CompoundTag tag) {
        if (!tag.contains("Dimension", Tag.TAG_STRING)) throw new IllegalArgumentException("Missing dimension");
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimension == null) throw new IllegalArgumentException("Invalid dimension");
        ListTag raw = tag.getList("Facilities",Tag.TAG_COMPOUND), lines = tag.getList("Statuses",Tag.TAG_STRING);
        if (raw.size() > 16 || lines.size() > 160) throw new IllegalArgumentException("Snapshot too large");
        List<AirportFacility> facilities = new ArrayList<>(); raw.forEach(value -> facilities.add(AirportFacility.load((CompoundTag)value)));
        List<String> statuses = new ArrayList<>(); lines.forEach(value -> statuses.add(value.getAsString()));
        return new AirportScreenData(dimension,BlockPos.of(tag.getLong("Pos")),tag.getString("Name"),tag.getString("Town"),tag.getString("Owner"),tag.getBoolean("CanManage"),
                tag.getLong("Revision"),facilities,AirportGroundLayout.load(tag.getCompound("Ground")),statuses,tag.getString("Result"));
    }
}
