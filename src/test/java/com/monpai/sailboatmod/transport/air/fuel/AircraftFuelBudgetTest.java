package com.monpai.sailboatmod.transport.air.fuel;

import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.air.fuel.AircraftFuelBudget.*;

class AircraftFuelBudgetTest {
    static Engine engine(int x,long ticks){return new Engine(new BlockPos(x,0,0),ticks,true,"");}

    @Test void fullOutboundAndActualReturnPlusPerLegDwellGetTwentyPercentReserve() {
        var result=evaluate(List.of(engine(0,800+10*1600)),6000,6640,20);
        assertTrue(result.ready());assertEquals(15264,result.requiredTicks());assertEquals(2544,result.reserveTicks());assertEquals(16800,result.availableTicks());assertTrue(result.deficits().isEmpty());
        var shortFuel=evaluate(List.of(engine(0,800+8*1600)),6000,6640,20);
        assertFalse(shortFuel.ready());assertEquals("fuel_insufficient",shortFuel.reason());assertEquals(1664,shortFuel.deficits().getFirst().shortfallTicks());
    }
    @Test void independentRequiredEnginesCannotPoolTheirFuel() {
        var result=evaluate(List.of(engine(0,16000),engine(1,4000)),4760,0,20);
        assertEquals(6000,result.requiredTicks());assertEquals(4000,result.availableTicks());assertFalse(result.ready());assertEquals(1,result.deficits().size());
        assertEquals(new BlockPos(1,0,0),result.deficits().getFirst().offset());assertEquals(2000,result.deficits().getFirst().shortfallTicks());
    }
    @Test void returnIsAnExactSeparateLegInsteadOfTwiceTheOutboundGuess() {
        var outbound=evaluate(List.of(engine(0,10000)),12,0,20);var returning=evaluate(List.of(engine(0,10000)),12,37,20);
        assertEquals(1252,outbound.requiredTicks());assertEquals(1329,returning.requiredTicks());assertEquals(77,returning.requiredTicks()-outbound.requiredTicks());
    }
    @Test void minimumOneMinuteReserveAndFractionalPercentageRoundUp() {
        assertEquals(1200,evaluate(List.of(engine(0,10000)),100,0,0).reserveTicks());
        var fractional=evaluate(List.of(engine(0,10000)),5961,0,20);assertEquals(1201,fractional.reserveTicks());assertEquals(7202,fractional.requiredTicks());
    }
    @Test void exactCoveragePassesAndOneTickShortFails() {
        long required=evaluate(List.of(engine(0,10000)),100,0,20).requiredTicks();
        assertTrue(evaluate(List.of(engine(0,required)),100,0,20).ready());assertFalse(evaluate(List.of(engine(0,required-1)),100,0,20).ready());
    }
    @Test void sufficientInventoryWaitsForActualPoweredReadback() {
        var waiting=evaluate(List.of(new Engine(BlockPos.ZERO,10000,false,"engine_warming")),100,0,20);
        assertFalse(waiting.ready());assertEquals("fuel_power_not_ready",waiting.reason());assertEquals(0,waiting.deficits().getFirst().shortfallTicks());assertEquals("engine_warming",waiting.deficits().getFirst().reason());
        assertTrue(evaluate(List.of(new Engine(BlockPos.ZERO,10000,true,"observed_native")),100,0,20).ready());
    }
    @Test void emptyOrUnknownFuelNeverBecomesReady() {
        var empty=evaluate(List.of(),100,0,20);assertFalse(empty.ready());assertEquals("fuel_no_engines",empty.reason());assertEquals(0,empty.availableTicks());
        var unknown=evaluate(List.of(new Engine(BlockPos.ZERO,-1,false,"fuel_unreadable")),100,0,20);
        assertFalse(unknown.ready());assertEquals("fuel_unknown",unknown.reason());assertEquals(-1,unknown.availableTicks());assertEquals(-1,unknown.deficits().getFirst().shortfallTicks());
        assertEquals("fuel_insufficient",evaluate(List.of(new Engine(BlockPos.ZERO,0,false,"engine_empty")),100,0,20).reason());
    }
    @Test void negativeInputsAndAllLongArithmeticOverflowFailClosed() {
        for(long[] input:new long[][]{{-1,0},{0,-1},{Long.MAX_VALUE,0},{Long.MAX_VALUE-40,1},{Long.MAX_VALUE/2,0}}) {
            var result=evaluate(List.of(engine(0,Long.MAX_VALUE)),input[0],input[1],20);assertFalse(result.ready());assertEquals(-1,result.requiredTicks());
        }
        assertEquals("fuel_budget_invalid",evaluate(List.of(engine(0,10000)),100,0,-1).reason());
        assertEquals("fuel_budget_overflow",evaluate(List.of(engine(0,Long.MAX_VALUE)),Long.MAX_VALUE/2,0,20).reason());
    }
    @Test void invalidEngineIdentityAndDuplicatePositionsAreNotAValidFuelConfiguration() {
        assertFalse(evaluate(null,100,0,20).ready());assertFalse(evaluate(Arrays.asList((Engine)null),100,0,20).ready());
        assertFalse(evaluate(List.of(new Engine(null,10000,true,"")),100,0,20).ready());
        assertEquals("fuel_budget_invalid",evaluate(List.of(engine(0,10000),engine(0,10000)),100,0,20).reason());
    }
    @Test void offsetAndResultRowsAreImmutableAndInputCollectionsRemainUnchanged() {
        var offset=new BlockPos.MutableBlockPos(1,2,3);var engine=new Engine(offset,10000,false,null);offset.set(9,9,9);assertEquals(new BlockPos(1,2,3),engine.offset());
        var list=new ArrayList<>(List.of(engine));var result=evaluate(list,100,0,20);assertEquals(1,list.size());
        assertThrows(UnsupportedOperationException.class,()->result.deficits().clear());assertEquals("",engine.reason());
    }
}
