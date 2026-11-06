package com.monpai.sailboatmod.transport.air.surface;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceBinding.*;

class AircraftSurfaceProfileTest {
    static final UUID BODY=UUID.fromString("0bf1d134-7a6b-45d9-825e-4a569c9addce");
    static AircraftSurfaceBinding binding(Role role,int x) {return new AircraftSurfaceBinding(role,new BlockPos(x,1,0),0,1,20,60);}
    static AircraftSurfaceProfile profile(AircraftSurfaceBinding... bindings) {return new AircraftSurfaceProfile(BODY,Direction.WEST,List.of(bindings));}

    @Test void explicitPolarttBindingsHaveDifferentialRolesAndNoInventedRudder() {
        var p=profile(new AircraftSurfaceBinding(Role.AILERON_RIGHT,new BlockPos(-4,1,-5),0,1,20,60),
                new AircraftSurfaceBinding(Role.AILERON_LEFT,new BlockPos(-4,1,-1),0,1,20,60),
                new AircraftSurfaceBinding(Role.ELEVATOR,new BlockPos(6,1,-4),0,1,20,60),
                new AircraftSurfaceBinding(Role.ELEVATOR,new BlockPos(6,1,-2),0,1,20,60));
        assertEquals(4,p.bindings().size());assertFalse(p.bindings().stream().anyMatch(b->b.role()==Role.RUDDER));
        assertTrue(p.bindings().stream().allMatch(b->b.axisCompatible(Direction.WEST,Direction.Axis.Z)));
    }
    @Test void axisCompatibilityRejectsLongitudinalAndVerticalAileronsAndHorizontalRudder() {
        for(Role role:Role.values()) {
            var b=binding(role,1);
            assertEquals(role==Role.RUDDER,b.axisCompatible(Direction.WEST,Direction.Axis.Y));
            assertEquals(role!=Role.RUDDER,b.axisCompatible(Direction.WEST,Direction.Axis.Z));
            assertFalse(b.axisCompatible(Direction.WEST,Direction.Axis.X));
            assertFalse(b.axisCompatible(Direction.UP,Direction.Axis.Z));
            assertEquals(role!=Role.RUDDER,b.axisCompatible(Direction.NORTH,Direction.Axis.X));
        }
    }
    @Test void limitsRejectNonfiniteAnglesWrongPolarityAndUnboundedOffsets() {
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-181,181})
            assertThrows(IllegalArgumentException.class,()->new AircraftSurfaceBinding(Role.ELEVATOR,BlockPos.ZERO,bad,1,20,60));
        for(int sign:new int[]{-2,0,2})assertThrows(IllegalArgumentException.class,()->new AircraftSurfaceBinding(Role.ELEVATOR,BlockPos.ZERO,0,sign,20,60));
        for(double bad:new double[]{0,-1,30.001,Double.NaN})assertThrows(IllegalArgumentException.class,()->new AircraftSurfaceBinding(Role.ELEVATOR,BlockPos.ZERO,0,1,bad,60));
        for(double bad:new double[]{0,-1,90.001,Double.NaN})assertThrows(IllegalArgumentException.class,()->new AircraftSurfaceBinding(Role.ELEVATOR,BlockPos.ZERO,0,1,20,bad));
        assertThrows(IllegalArgumentException.class,()->new AircraftSurfaceBinding(Role.ELEVATOR,new BlockPos(129,0,0),0,1,20,60));
        assertDoesNotThrow(()->new AircraftSurfaceBinding(Role.ELEVATOR,new BlockPos(-128,128,128),180,-1,30,90));
    }
    @Test void profileDefensivelyCopiesMutableOffsetsAndListsAndRejectsDuplicateHardware() {
        var mutable=new BlockPos.MutableBlockPos(1,2,3);var b=new AircraftSurfaceBinding(Role.ELEVATOR,mutable,0,1,20,60);
        var list=new ArrayList<>(List.of(b));var p=new AircraftSurfaceProfile(BODY,Direction.WEST,list);
        mutable.set(9,9,9);list.clear();assertEquals(new BlockPos(1,2,3),p.bindings().getFirst().localOffset());
        assertThrows(UnsupportedOperationException.class,()->p.bindings().clear());
        assertThrows(IllegalArgumentException.class,()->profile(binding(Role.ELEVATOR,1),binding(Role.RUDDER,1)));
        assertThrows(IllegalArgumentException.class,()->new AircraftSurfaceProfile(BODY,Direction.UP,List.of()));
        assertThrows(IllegalArgumentException.class,()->new AircraftSurfaceProfile(null,Direction.WEST,List.of()));
        var tooMany=new ArrayList<AircraftSurfaceBinding>();for(int i=0;i<33;i++)tooMany.add(binding(Role.ELEVATOR,i));
        assertThrows(IllegalArgumentException.class,()->new AircraftSurfaceProfile(BODY,Direction.WEST,tooMany));
    }
    @Test void everyRoleAndHoldModeRoundTripsWithoutClaimingAnyAdditionalSurface() {
        var all=new ArrayList<AircraftSurfaceBinding>();int x=0;
        for(Role role:Role.values())all.add(new AircraftSurfaceBinding(role,new BlockPos(x++,0,0),179,-1,30,90,Mode.HOLD));
        var original=new AircraftSurfaceProfile(BODY,Direction.NORTH,all);
        assertEquals(original,AircraftSurfaceProfile.load(original.save()));
        assertEquals(profile(),AircraftSurfaceProfile.load(profile().save()));
    }
    @Test void strictLoadRejectsMissingFieldsWrongTypesNonfiniteAndDuplicateEntries() {
        CompoundTag good=profile(binding(Role.ELEVATOR,1)).save();
        for(String key:List.of("Version","Body","Forward","Bindings")) {var bad=good.copy();bad.remove(key);assertThrows(IllegalArgumentException.class,()->AircraftSurfaceProfile.load(bad),key);}
        for(String key:List.of("Role","Offset","Neutral","Polarity","Max","Rate","Mode")) {var bad=good.copy();bad.getList("Bindings",Tag.TAG_COMPOUND).getCompound(0).remove(key);assertThrows(IllegalArgumentException.class,()->AircraftSurfaceProfile.load(bad),key);}
        var wrongNumber=good.copy();wrongNumber.getList("Bindings",Tag.TAG_COMPOUND).getCompound(0).putInt("Max",20);
        assertThrows(IllegalArgumentException.class,()->AircraftSurfaceProfile.load(wrongNumber));
        var nan=good.copy();nan.getList("Bindings",Tag.TAG_COMPOUND).getCompound(0).putDouble("Neutral",Double.NaN);
        assertThrows(IllegalArgumentException.class,()->AircraftSurfaceProfile.load(nan));
        var duplicate=good.copy();duplicate.getList("Bindings",Tag.TAG_COMPOUND).add(duplicate.getList("Bindings",Tag.TAG_COMPOUND).getCompound(0).copy());
        assertThrows(IllegalArgumentException.class,()->AircraftSurfaceProfile.load(duplicate));
    }
    @Test void strictLoadDoesNotMistakeWrongListElementsOrOversizeListForEmptyBindings() {
        var wrong=profile().save();var strings=new ListTag();strings.add(StringTag.valueOf("not a binding"));wrong.put("Bindings",strings);
        assertThrows(IllegalArgumentException.class,()->AircraftSurfaceProfile.load(wrong));
        var oversized=profile().save();var entries=new ListTag();for(int i=0;i<33;i++)entries.add(new CompoundTag());oversized.put("Bindings",entries);
        assertThrows(IllegalArgumentException.class,()->AircraftSurfaceProfile.load(oversized));
        var unknown=profile(binding(Role.ELEVATOR,0)).save();unknown.getList("Bindings",Tag.TAG_COMPOUND).getCompound(0).putString("Role","AUTOMATIC");
        assertThrows(IllegalArgumentException.class,()->AircraftSurfaceProfile.load(unknown));
    }
}
