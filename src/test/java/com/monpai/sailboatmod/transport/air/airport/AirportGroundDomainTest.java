package com.monpai.sailboatmod.transport.air.airport;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Reflection keeps the initial RED a missing-feature assertion rather than a compiler error. */
class AirportGroundDomainTest {
    private Class<?> type(String name) {
        return assertDoesNotThrow(() -> Class.forName(getClass().getPackageName() + "." + name), "ground-layout API must exist");
    }
    private Object stand(double yaw, double width) throws Exception {
        return type("AirportParkingStand").getConstructor(String.class, Vec3.class, double.class, double.class, double.class, int.class, boolean.class)
                .newInstance("standA", new Vec3(-20.5,64,.5), yaw, 12D, width, 8, true);
    }
    private Object route(String direction, String runway, List<Vec3> points, int hold) throws Exception {
        Class<?> dir = type("AirportTaxiRoute$Direction");
        Object kind = Arrays.stream(dir.getEnumConstants()).filter(value -> value.toString().equals(direction)).findFirst().orElseThrow();
        return type("AirportTaxiRoute").getConstructor(String.class, dir, String.class, String.class, List.class, double.class, int.class, double.class, int.class)
                .newInstance(direction.toLowerCase(), kind, runway, "standA", points, 8D,8,2D,hold);
    }
    private Object layout(List<?> stands, List<?> routes) throws Exception {
        return type("AirportGroundLayout").getConstructor(long.class,List.class,List.class).newInstance(7L,stands,routes);
    }
    @Test void standRequiresFiniteGeometryWholeHullHeightHeadingAndRealLowSpeed() throws Exception {
        Object stand = stand(0, 8);
        var parked = stand.getClass().getMethod("contains", AABB.class,double.class,double.class);
        AABB hull = new AABB(-22,64,-2,-19,67,3);
        assertEquals(true, parked.invoke(stand,hull,0D,.1));
        assertEquals(false, parked.invoke(stand,hull,90D,.1));
        assertEquals(false, parked.invoke(stand,hull,0D,.5));
        assertEquals(false, parked.invoke(stand,hull,0D,Double.NaN));
        assertEquals(false, parked.invoke(stand,hull.move(0,9,0),0D,0D));
        assertEquals(false, parked.invoke(stand,new AABB(-30,64,-2,-19,67,3),0D,0D));
        assertEquals(false, stand(Double.NaN,8).getClass().getMethod("validGeometry").invoke(stand(Double.NaN,8)));
        assertEquals(false, stand(0,Double.POSITIVE_INFINITY).getClass().getMethod("validGeometry").invoke(stand(0,Double.POSITIVE_INFINITY)));
    }
    @Test void layoutsNeverInventReverseRoutesOrMissingRunways() throws Exception {
        Object out=route("OUTBOUND","main",List.of(new Vec3(-20.5,64,.5),new Vec3(-10,64,.5),new Vec3(.5,64,.5)),1);
        Object layout=layout(List.of(stand(0,8)),List.of(out));
        var ready=layout.getClass().getMethod("departureReady",String.class,String.class);
        assertEquals(true,ready.invoke(layout,"standA","main"));
        assertEquals(false,ready.invoke(layout,"standA","missingRunway"));
        assertEquals(false,layout.getClass().getMethod("arrivalReady",String.class,String.class).invoke(layout,"standA","main"));
    }
    @Test void layoutCopiesListsAndRoundTripsAllDirectedGeometry() throws Exception {
        var stands=new ArrayList<>(List.of(stand(0,8)));
        var out=route("OUTBOUND","main",List.of(new Vec3(-20.5,64,.5),new Vec3(-10,64,.5),new Vec3(.5,64,.5)),1);
        var in=route("INBOUND","main",List.of(new Vec3(30.5,64,.5),new Vec3(-20.5,64,.5)),-1);
        Object value=layout(stands,List.of(out,in)); stands.clear();
        CompoundTag saved=(CompoundTag)value.getClass().getMethod("save").invoke(value);
        assertEquals(value,value.getClass().getMethod("load",CompoundTag.class).invoke(null,saved));
        assertEquals(1,((List<?>)value.getClass().getMethod("stands").invoke(value)).size());
    }
    @Test void outboundNeedsAnExplicitHoldBeforeFinalRunwayEntry() throws Exception {
        Object route=route("OUTBOUND","main",List.of(new Vec3(-20.5,64,.5),new Vec3(.5,64,.5)),-1);
        assertEquals(false,route.getClass().getMethod("validGeometry").invoke(route));
        Object inbound=route("INBOUND","main",List.of(new Vec3(.5,64,.5),new Vec3(-20.5,64,.5)),0);
        assertEquals(false,inbound.getClass().getMethod("validGeometry").invoke(inbound));
    }
}
