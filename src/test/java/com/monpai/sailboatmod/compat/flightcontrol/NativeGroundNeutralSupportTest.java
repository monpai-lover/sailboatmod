package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeGroundNeutralSupportTest {
    static final Object PROP=new Object(), FLAP=new Object(), FOREIGN=new Object();
    static final class Plane implements NativeGroundNeutralSupport.View {
        List<?> thrust=List.of("prop"),flaps=List.of("a","b","c","d");
        boolean children;int reads;
        final Map<Object,Object> existing=new HashMap<>(Map.of("prop",PROP,"a",FLAP,"b",FLAP,"c",FLAP,"d",FLAP));
        public List<?> thrusters(){return thrust;}public List<?> flaps(){return flaps;}
        public boolean unsupportedChildren(){return children;}
        public Object existing(Object entry){reads++;return existing.get(entry);}
        public boolean knownThruster(Object entity){return entity==PROP;}
        public boolean knownFlap(Object entity){return entity==FLAP;}
    }
    @Test void theActualOnePropFourMechanicalLayoutPassesWithoutScanningUnlinkedActors(){
        Plane p=new Plane();p.existing.put("unlinked-unknown",FOREIGN);
        assertTrue(NativeGroundNeutralSupport.inspect(p),"the bounded known live layout must be eligible");
        assertEquals(5,p.reads,"only the five linked existing actors are inspected");
    }
    @Test void unknownOrUnloadedLinkedOutputCannotReachTheNativeActionStage(){
        Plane p=new Plane();p.existing.put("prop",FOREIGN);
        assertFalse(NativeGroundNeutralSupport.inspect(p));
        p=new Plane();p.existing.remove("c");assertFalse(NativeGroundNeutralSupport.inspect(p));
    }
    @Test void unsupportedMountedOrWeldedChildrenAreRejectedBeforeAnyNativeLookup(){
        Plane p=new Plane();p.children=true;
        assertFalse(NativeGroundNeutralSupport.inspect(p));assertEquals(0,p.reads);
    }
    @Test void oversizedCorruptLinkTablesAreRejectedBeforeAnyNativeLookup(){
        Plane p=new Plane();p.thrust=Collections.nCopies(NativeGroundNeutralSupport.MAX_LINKS+1,"prop");
        assertFalse(NativeGroundNeutralSupport.inspect(p));assertEquals(0,p.reads);
    }
}
