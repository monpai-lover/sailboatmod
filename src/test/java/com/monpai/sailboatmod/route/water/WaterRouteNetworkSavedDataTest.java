package com.monpai.sailboatmod.route.water;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import com.monpai.sailboatmod.route.RouteDefinition;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WaterRouteNetworkSavedDataTest {
 @Test void dimensionInfrastructureRoundTripsWithoutJourneyState() throws Exception {
  var data=new WaterRouteNetworkSavedData();var a=new BlockPos(0,65,0);var b=new BlockPos(7000,65,0);
  data.register(a,b,new RouteDefinition("sea",List.of(new Vec3(.5,65,.5),new Vec3(7000.5,65,.5))));
  CompoundTag tag=data.save(new CompoundTag(),null);
  var load=WaterRouteNetworkSavedData.class.getDeclaredMethod("load",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);load.setAccessible(true);
  var restored=(WaterRouteNetworkSavedData)load.invoke(null,tag,null);
  assertEquals(data.snapshot(),restored.snapshot());assertEquals(1,restored.snapshot().size());
  assertTrue(WaterRouteNetwork.plan(restored.snapshot(),new Vec3(6000.5,65,.5),new Vec3(1000.5,65,.5)).isPresent());
 }
 @Test void invalidationCannotMutateAlreadyIssuedWorkerSnapshot(){var d=new WaterRouteNetworkSavedData();d.register(BlockPos.ZERO,new BlockPos(100,65,0),new RouteDefinition("a",List.of(new Vec3(0,65,0),new Vec3(100,65,0))));var old=d.snapshot();d.invalidate(Set.of(old.getFirst().id()));assertTrue(d.snapshot().isEmpty());assertEquals(2,old.getFirst().points().size());}
}
