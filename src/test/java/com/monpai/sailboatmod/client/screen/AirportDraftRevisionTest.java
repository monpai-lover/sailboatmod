package com.monpai.sailboatmod.client.screen;
import com.monpai.sailboatmod.client.airport.AirportPlannerModel;
import net.minecraft.world.phys.Vec3;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class AirportDraftRevisionTest {
    @Test void automaticStatusRefreshCannotRebaseAnOlderGeometryDraft() throws Exception {
        AirportScreen screen = new AirportScreen(snapshot(5));
        assertTrue(screen.planner().startNew(AirportPlannerModel.Tool.RUNWAY));
        screen.planner().click(new Vec3(0.5, 0, 0.5)); screen.planner().click(new Vec3(48.5, 0, 0.5));
        screen.update(snapshot(6),false);
        var packet = screen.planner().savePacket();
        assertEquals(5,packet.revision(),"an older draft must be rejected if another manager changed the airport");
        assertEquals(6,screen.data().revision(),"the status view still receives the latest server state");
    }
    private static AirportScreenData snapshot(long revision) {
        return new AirportScreenData(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO,"Airport","Town","Owner",true,revision,List.of(),
                new AirportGroundLayout(revision,List.of(),List.of()),List.of(),"");
    }
}
