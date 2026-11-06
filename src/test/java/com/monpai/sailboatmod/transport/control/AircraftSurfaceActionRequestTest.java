package com.monpai.sailboatmod.transport.control;

import com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceBinding;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AircraftSurfaceActionRequestTest {
    final BlockPos controller=new BlockPos(20481040,129,20483090);
    @Test void selectedPlotCoordinatesBecomeExactControllerRelativeBindings() {
        var b=AircraftSurfaceActionRequest.bind("RUDDER;20481046,130,20483086;0;-1;20;60",controller);
        assertEquals(AircraftSurfaceBinding.Role.RUDDER,b.role());assertEquals(new BlockPos(6,1,-4),b.localOffset());
        assertEquals(-1,b.polarity());assertEquals(AircraftSurfaceBinding.Mode.AUTO,b.mode());
    }
    @Test void untrustedSelectionCannotWrapCoordinatesOrExceedSurfaceLimits() {
        for(String bad:new String[]{"RUDDER;2147483647,130,20483086;0;1;20;60",
                "RUDDER;-2147483648,130,20483086;0;1;20;60","RUDDER;20482040,130,20483086;0;1;20;60",
                "RUDDER;20481046,130,20483086;NaN;1;20;60","RUDDER;20481046,130,20483086;0;0;20;60",
                "RUDDER;20481046,130,20483086;0;1;90;60","RUDDER;20481046,130,20483086;0;1;20;Infinity",
                "RUDDER;20481046,130,20483086;0;1;20;60;extra","PROPELLER;20481046,130,20483086;0;1;20;60"})
            assertThrows(IllegalArgumentException.class,()->AircraftSurfaceActionRequest.bind(bad,controller),bad);
    }
    @Test void removalUsesExactRoleAndAbsoluteBearingNotAnUntrustedListIndex() {
        var key=AircraftSurfaceActionRequest.key("ELEVATOR;20481046,130,20483086",controller);
        assertEquals(AircraftSurfaceBinding.Role.ELEVATOR,key.role());assertEquals(new BlockPos(6,1,-4),key.localOffset());
        assertThrows(IllegalArgumentException.class,()->AircraftSurfaceActionRequest.key("ELEVATOR;1,2,3",controller));
    }
}
