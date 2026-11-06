package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.client.airport.AirportPlannerModel;
import com.monpai.sailboatmod.network.packet.airport.AirportActionPacket;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirportFacilityWidthTest {
    private static AirportFacility runway(int length,int width,int height) {
        return new AirportFacility("wide",AirportFacility.Type.RUNWAY,new BlockPos(0,80,0),new BlockPos(length,80,0),width,height);
    }
    @Test void width64IsLegalWidth65IsRejectedAndLegacyWidthsRemainLegal() {
        for(int width:new int[]{5,16,32,36,48,64})assertTrue(runway(256,width,12).validGeometry(),"width "+width);
        assertFalse(runway(256,4,12).validGeometry());assertFalse(runway(256,65,12).validGeometry());
        double halfSweep=33.12/2;
        assertFalse(runway(256,32,12).containsHorizontal(128.5,.5+halfSweep,1));
        assertTrue(runway(256,36,12).containsHorizontal(128.5,.5+halfSweep,1));
    }
    @Test void nbtPreservesBothLegacyAndWiderGeometryWithoutAFormatChange() {
        for(int width:new int[]{16,32,36,64}){var original=runway(256,width,12);assertEquals(original,AirportFacility.load(original.save()));}
        assertThrows(IllegalArgumentException.class,()->AirportFacility.load(runway(256,65,12).save()));
    }
    @Test void theExistingPacketSchemaRoundTripsTheFullWidth64() {
        var packet=new AirportActionPacket(ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,4,
                AirportActionPacket.Action.UPSERT_RUNWAY,"wide","","",List.of(new Vec3(0,80,0),new Vec3(256,80,0)),false,0,256,64,12,2,-1);
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {
            AirportActionPacket.encode(packet,buffer);var decoded=AirportActionPacket.decode(buffer);
            assertEquals(packet,decoded);assertEquals(0,buffer.readableBytes());
            assertEquals(64,AirportFacility.load(decoded.toActionTag().getCompound("Facility")).width());
        } finally {buffer.release();}
    }
    @Test void thePlannerUsesTheServerWidthLimitAndRejects65() {
        var data=new AirportScreenData(ResourceLocation.parse("minecraft:overworld"),new BlockPos(0,80,0),"Airport","","",true,0,
                List.of(),AirportGroundLayout.empty(),List.of(),"");
        var model=new AirportPlannerModel(data);assertTrue(model.startNew(AirportPlannerModel.Tool.RUNWAY));
        model.click(new Vec3(.5,80,.5));model.click(new Vec3(256.5,80,.5));
        model.updateProperties(model.draft().orElseThrow().withDimensions(256,64,12));
        assertTrue(model.canSave());assertEquals(64,model.savePacket().width());
        model.updateProperties(model.draft().orElseThrow().withDimensions(256,65,12));assertFalse(model.canSave());
    }
    @Test void theNewOuterRunwayEdgeIsStillCheckedForObstructionsAndUnknownTerrain() {
        AirportFacility wide=runway(128,64,8);BlockPos edge=new BlockPos(64,84,30);
        assertEquals(AirportValidation.Result.OBSTRUCTED,AirportValidation.validate(wide,p->true,p->!p.equals(edge),p->true));
        assertEquals(AirportValidation.Result.UNLOADED,AirportValidation.validate(wide,p->p.getZ()!=31,
                p->{throw new AssertionError("Unknown chunks cannot read clearance");},p->{throw new AssertionError("Unknown chunks cannot read support");}));
    }
    @Test void theLongestWidestTallestRunwayFinishesWithinPerTickAndAggregateBudgets() {
        var scan=new AirportValidation.Scan(runway(2048,64,64));var result=AirportValidation.Result.WAITING;
        long steps=0;int ticks=0;
        while(result==AirportValidation.Result.WAITING&&steps<=AirportValidation.MAX_SCAN_STEPS) {
            int[] reads={0};result=scan.advance(2048,p->{reads[0]++;return true;},p->true,p->true);
            assertTrue(scan.stepsUsed()<=2048);assertTrue(reads[0]<=2048);steps+=scan.stepsUsed();ticks++;
        }
        assertEquals(AirportValidation.Result.VALID,result);assertTrue(ticks>100);
        assertTrue(steps<=AirportValidation.MAX_SCAN_STEPS);
    }
}
