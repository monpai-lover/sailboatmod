package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class FlightControlEnvironmentPluginTest {
    private static final String PREFIX="com.monpai.sailboatmod.compat.flightcontrol.mixin.";
    @Test void allThreeCallCountersBindToTheirExactNativeMethodsAndDescriptors() throws Exception {
        Class<?> plugin=plugin();
        assertTrue(Arrays.stream(plugin.getDeclaredMethods()).anyMatch(m->m.getName().equals("trimEnvironmentCallCounts")),
                "Environment hooks must be checked by the optional-native plugin before they can apply");
        var count=plugin.getDeclaredMethod("trimEnvironmentCallCounts",ClassNode.class);count.setAccessible(true);
        var node=new ClassNode();
        var scope=method(TrimEnvironmentCompatibility.SCOPE_METHOD);
        var allocation=method(TrimEnvironmentCompatibility.ALLOCATION);
        node.methods.add(scope);node.methods.add(allocation);
        assertArrayEquals(new int[]{0,0,0},(int[])count.invoke(null,node));
        scope.instructions.add(call(TrimEnvironmentCompatibility.SCOPE_TARGET,Opcodes.INVOKEVIRTUAL));
        allocation.instructions.add(call(TrimEnvironmentCompatibility.BUDGET_TARGET,Opcodes.INVOKEVIRTUAL));
        allocation.instructions.add(call(TrimEnvironmentCompatibility.SAMPLE_TARGET,Opcodes.INVOKESPECIAL));
        assertArrayEquals(new int[]{1,1,1},(int[])count.invoke(null,node));
        var unrelated=method("unrelated()V");node.methods.add(unrelated);
        unrelated.instructions.add(call(TrimEnvironmentCompatibility.SAMPLE_TARGET,Opcodes.INVOKESPECIAL));
        allocation.instructions.add(call(TrimEnvironmentCompatibility.SCOPE_TARGET,Opcodes.INVOKEVIRTUAL));
        allocation.instructions.add(call(TrimEnvironmentCompatibility.BUDGET_TARGET,Opcodes.INVOKESTATIC));
        assertArrayEquals(new int[]{1,1,1},(int[])count.invoke(null,node));
        scope.instructions.add(call(TrimEnvironmentCompatibility.SCOPE_TARGET,Opcodes.INVOKEVIRTUAL));
        allocation.instructions.add(call(TrimEnvironmentCompatibility.BUDGET_TARGET,Opcodes.INVOKEVIRTUAL));
        allocation.instructions.add(call(TrimEnvironmentCompatibility.SAMPLE_TARGET,Opcodes.INVOKESPECIAL));
        assertArrayEquals(new int[]{2,2,2},(int[])count.invoke(null,node));
        node.methods.add(method(TrimEnvironmentCompatibility.ALLOCATION));
        assertArrayEquals(new int[]{2,0,0},(int[])count.invoke(null,node));
    }
    @Test void onlyAllThreeNamedHooksOnTheNativeComputerCanEnableTheEnvironment() throws Exception {
        var fields=new java.lang.reflect.Field[3];boolean[] previous=new boolean[3];
        String[] names={"scopeApplied","budgetApplied","sampleApplied"};
        for(int i=0;i<3;i++) {fields[i]=TrimEnvironmentCompatibility.class.getDeclaredField(names[i]);fields[i].setAccessible(true);previous[i]=fields[i].getBoolean(null);fields[i].setBoolean(null,false);}
        Class<?> type=plugin();Object plugin=type.getConstructor().newInstance();
        var post=type.getMethod("postApply",String.class,ClassNode.class,String.class,org.spongepowered.asm.mixin.extensibility.IMixinInfo.class);
        try {
            post.invoke(plugin,TrimEnvironmentCompatibility.COMPUTER,new ClassNode(),PREFIX+"UnknownMixin",null);
            for(var field:fields) assertFalse(field.getBoolean(null));
            String[] mixins={"TrimEnvironmentScopeMixin","TrimEnvironmentBudgetMixin","TrimEnvironmentSampleMixin"};
            for(int i=0;i<3;i++) {
                post.invoke(plugin,"ace.flight.navigation.FixedWingGuidance",new ClassNode(),PREFIX+mixins[i],null);
                assertFalse(fields[i].getBoolean(null));
                post.invoke(plugin,TrimEnvironmentCompatibility.COMPUTER,new ClassNode(),PREFIX+mixins[i],null);
                assertTrue(fields[i].getBoolean(null),mixins[i]+" must have its own application marker");
                assertEquals(i==2,TrimEnvironmentCompatibility.isApplied());
            }
        } finally {for(int i=0;i<3;i++)fields[i].setBoolean(null,previous[i]);}
    }
    private static MethodNode method(String signature) {
        int index=signature.indexOf('(');return new MethodNode(Opcodes.ACC_PRIVATE,signature.substring(0,index),signature.substring(index),null,null);
    }
    private static MethodInsnNode call(String target,int opcode) {
        int split=target.indexOf(';'), args=target.indexOf('(',split);
        return new MethodInsnNode(opcode,target.substring(1,split),target.substring(split+1,args),target.substring(args),false);
    }
    private static Class<?> plugin() throws Exception {
        String name=PREFIX+"FlightControlMixinPlugin";
        try(var in=FlightControlEnvironmentPluginTest.class.getResourceAsStream("/"+name.replace('.','/')+".class")) {
            assertNotNull(in);byte[] bytes=in.readAllBytes();
            return new ClassLoader(FlightControlEnvironmentPluginTest.class.getClassLoader()) {
                Class<?> define(){return defineClass(name,bytes,0,bytes.length);}
            }.define();
        }
    }
}
