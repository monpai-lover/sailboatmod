package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PitchBootstrapStoreContractTest {
    private static final String MODIFY="Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
    private static final String SHARE="Lcom/llamalad7/mixinextras/sugar/Share;";
    private static final String REF="Lcom/llamalad7/mixinextras/sugar/ref/LocalDoubleRef;";
    private static final String FEEDBACK="flightPathFeedback(Lace/flight/navigation/FixedWingGuidance$Settings;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDD)Lorg/joml/Vector3d;";

    @Test void bothStoreHandlersUseOnePerInvocationSharedValueAndNeverRedirectDemand() throws Exception {
        ClassNode node=new ClassNode();
        try(var in=getClass().getResourceAsStream("/com/monpai/sailboatmod/compat/flightcontrol/mixin/FixedWingMeasuredPitchMixin.class")) {
            assertNotNull(in);new ClassReader(in).accept(node,0);
        }
        var handlers=node.methods.stream().filter(m->annotation(m,MODIFY)!=null).toList();
        assertEquals(2,handlers.size(),"Capture native clamped AoA, then blend only its override store");
        for(var method:node.methods) assertNull(annotation(method,"Lorg/spongepowered/asm/mixin/injection/Redirect;"));
        String shareId=null;
        for(int ordinal=0;ordinal<2;ordinal++) {
            int wanted=ordinal;
            var method=handlers.stream().filter(m->value((AnnotationNode)value(annotation(m,MODIFY),"at"),"ordinal").equals(wanted)).findFirst().orElseThrow();
            var annotation=annotation(method,MODIFY);
            assertEquals(List.of(FEEDBACK),value(annotation,"method"));
            assertEquals(23,value(annotation,"index"));
            assertEquals(1,value(annotation,"require"));assertEquals(1,value(annotation,"allow"));
            assertEquals("STORE",value((AnnotationNode)value(annotation,"at"),"value"));
            assertTrue((method.access&Opcodes.ACC_STATIC)!=0);
            assertEquals(ordinal==0 ? "(D"+REF+")D" : "(DLjava/lang/Object;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDD"+REF+")D",method.desc);
            var params=method.invisibleParameterAnnotations;
            var share=params[params.length-1].stream().filter(a->SHARE.equals(a.desc)).findFirst().orElseThrow();
            if(shareId==null) shareId=(String)value(share,"value");else assertEquals(shareId,value(share,"value"));
            int setters=0, blends=0;
            for(var instruction:method.instructions) if(instruction instanceof MethodInsnNode call) {
                assertFalse(call.owner.startsWith("ace/flight/"),"No repeated native calls or state writes");
                if(call.name.equals("set")&&call.owner.endsWith("/LocalDoubleRef")) setters++;
                if(call.name.equals("nativeCompensation")) blends++;
            }
            assertEquals(ordinal==0?1:0,setters);assertEquals(ordinal==1?1:0,blends);
        }
    }
    @Test void scopePreservesTheNativeRecoveryGatesAndUsesTheActualForwardSpeedArgument() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/compat/flightcontrol/NativeFixedWingPitchBootstrap.java"));
        assertFalse(source.contains("frame.speed() <"),"Horizontal speed must not withdraw feedback at VR");
        assertTrue(source.contains("\"fixedWingAirspeedLimited\""));
        assertTrue(source.contains("\"fixedWingSpeedRecovering\""));
        assertTrue(source.contains("compensation(nativeValue, measured, actualForwardSpeed"));
        assertFalse(source.contains("public static Object nativeDemand("));
    }
    private static AnnotationNode annotation(MethodNode method,String desc) {
        for(var list:List.of(method.visibleAnnotations==null?List.<AnnotationNode>of():method.visibleAnnotations,
                method.invisibleAnnotations==null?List.<AnnotationNode>of():method.invisibleAnnotations))
            for(var a:list) if(desc.equals(a.desc)) return a;
        return null;
    }
    private static Object value(AnnotationNode annotation,String key) {
        for(int i=0;i<annotation.values.size();i+=2) if(key.equals(annotation.values.get(i))) return annotation.values.get(i+1);
        throw new AssertionError("Missing "+key);
    }
}
