package com.monpai.sailboatmod.transport.air.airport;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class AirportManagementPolicyTest {
    @Test void permissionRequiresActualPlayerOwnerTownDimensionAndEightMetres(){
        assertEquals("ok",AirportManagementService.rejection(true,true,true,true,64,3,3,false));
        assertEquals("missing_sender",AirportManagementService.rejection(false,true,true,true,0,3,3,false));
        assertEquals("forbidden",AirportManagementService.rejection(true,false,true,true,0,3,3,false));
        assertEquals("forbidden",AirportManagementService.rejection(true,true,false,true,0,3,3,false));
        assertEquals("forbidden",AirportManagementService.rejection(true,true,true,false,0,3,3,false));
        assertEquals("out_of_range",AirportManagementService.rejection(true,true,true,true,64.01,3,3,false));
        assertEquals("out_of_range",AirportManagementService.rejection(true,true,true,true,Double.NaN,3,3,false));
    }
    @Test void editorThrottleBoundsRepeatedFullScansAndAllowsLaterRequests(){
        var times=new java.util.HashMap<java.util.UUID,Long>();var player=java.util.UUID.randomUUID();
        assertTrue(AirportManagementService.acceptRequest(times,player,100));
        assertFalse(AirportManagementService.acceptRequest(times,player,101));
        assertTrue(AirportManagementService.acceptRequest(times,player,105));
    }
    @Test void staleOrOccupiedEditsCannotBecomeAuthorized(){
        assertEquals("stale_revision",AirportManagementService.rejection(true,true,true,true,0,2,3,false));
        assertEquals("occupied",AirportManagementService.rejection(true,true,true,true,0,3,3,true));
    }
    @Test void foreignInteriorChunkFailsEvenWhenEndpointChunksBelongToTown(){
        AABB area=new AABB(0,64,0,64,72,8);
        assertFalse(AirportManagementService.withinManagedTown(area,"town",p->p.getX()==32?"foreign":"town"));
        assertTrue(AirportManagementService.withinManagedTown(area,"town",p->"town"));
    }
}
