package com.monpai.sailboatmod.transport.air.surface;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;

/** Saved explicit configuration; loading it neither discovers nor acquires hardware. */
public record AircraftSurfaceProfile(UUID bodyId,Direction localForward,List<AircraftSurfaceBinding> bindings) {
    public static final int MAX_BINDINGS=32;
    private static final int VERSION=1;

    public AircraftSurfaceProfile {
        if(bodyId==null||localForward==null||!localForward.getAxis().isHorizontal()||bindings==null||bindings.size()>MAX_BINDINGS)
            throw invalid();
        var seen=new HashSet<BlockPos>();
        for(var binding:bindings)if(binding==null||!seen.add(binding.localOffset()))throw invalid();
        bindings=List.copyOf(bindings);
    }
    public CompoundTag save() {
        var t=new CompoundTag();t.putInt("Version",VERSION);t.putUUID("Body",bodyId);t.putString("Forward",localForward.name());
        var list=new ListTag();
        for(var b:bindings) {
            var entry=new CompoundTag();entry.putString("Role",b.role().name());entry.putLong("Offset",b.localOffset().asLong());
            entry.putDouble("Neutral",b.neutralAngleDeg());entry.putInt("Polarity",b.polarity());entry.putDouble("Max",b.maxDeflectionDeg());
            entry.putDouble("Rate",b.rateDegPerSecond());entry.putString("Mode",b.mode().name());list.add(entry);
        }
        t.put("Bindings",list);return t;
    }
    public static AircraftSurfaceProfile load(CompoundTag t) {
        if(t==null)throw invalid();
        require(t,"Version",Tag.TAG_INT);require(t,"Body",Tag.TAG_INT_ARRAY);require(t,"Forward",Tag.TAG_STRING);require(t,"Bindings",Tag.TAG_LIST);
        if(t.getInt("Version")!=VERSION||!t.hasUUID("Body"))throw invalid();
        Tag raw=t.get("Bindings");
        if(!(raw instanceof ListTag list)||list.size()>MAX_BINDINGS||(!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND))throw invalid();
        try {
            var bindings=new ArrayList<AircraftSurfaceBinding>(list.size());
            for(int i=0;i<list.size();i++) {
                var e=list.getCompound(i);
                require(e,"Role",Tag.TAG_STRING);require(e,"Offset",Tag.TAG_LONG);require(e,"Neutral",Tag.TAG_DOUBLE);require(e,"Polarity",Tag.TAG_INT);
                require(e,"Max",Tag.TAG_DOUBLE);require(e,"Rate",Tag.TAG_DOUBLE);require(e,"Mode",Tag.TAG_STRING);
                bindings.add(new AircraftSurfaceBinding(AircraftSurfaceBinding.Role.valueOf(e.getString("Role")),BlockPos.of(e.getLong("Offset")),
                        e.getDouble("Neutral"),e.getInt("Polarity"),e.getDouble("Max"),e.getDouble("Rate"),AircraftSurfaceBinding.Mode.valueOf(e.getString("Mode"))));
            }
            return new AircraftSurfaceProfile(t.getUUID("Body"),Direction.valueOf(t.getString("Forward")),bindings);
        } catch(IllegalArgumentException ex) {throw invalid();}
    }
    private static void require(CompoundTag t,String key,int type){if(!t.contains(key,type))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("surface_invalid_saved_profile");}
}
