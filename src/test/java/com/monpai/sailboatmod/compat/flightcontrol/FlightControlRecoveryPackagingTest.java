package com.monpai.sailboatmod.compat.flightcontrol;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FlightControlRecoveryPackagingTest {
    private static final String MIXIN_PACKAGE="com/monpai/sailboatmod/compat/flightcontrol/mixin/";
    @Test void parsedMetadataKeepsTheMixinOptionalAndCommonForIntegratedServers() throws Exception {
        var config=JsonParser.parseString(Files.readString(Path.of("src/main/resources/sailboatmod.flightcontrol.mixins.json"))).getAsJsonObject();
        assertFalse(config.get("required").getAsBoolean());
        assertFalse(config.has("server"),"Physical-server-only entries omit integrated servers");
        assertEquals(10,config.getAsJsonArray("mixins").size());
        assertEquals("NavigationTerminalRecoveryMixin",config.getAsJsonArray("mixins").get(0).getAsString());
        assertEquals("FixedWingCruiseTrimMixin",config.getAsJsonArray("mixins").get(1).getAsString());
        assertEquals("FlightControlCruiseBudgetMixin",config.getAsJsonArray("mixins").get(2).getAsString());
        assertEquals("FlightControlGroundNeutralMixin",config.getAsJsonArray("mixins").get(3).getAsString());
        assertEquals("AircraftWheelContactMixin",config.getAsJsonArray("mixins").get(4).getAsString());
        assertEquals("NativePitchBootstrapScopeMixin",config.getAsJsonArray("mixins").get(5).getAsString());
        assertEquals("FixedWingMeasuredPitchMixin",config.getAsJsonArray("mixins").get(6).getAsString());
        assertEquals("TrimEnvironmentScopeMixin",config.getAsJsonArray("mixins").get(7).getAsString());
        assertEquals("TrimEnvironmentBudgetMixin",config.getAsJsonArray("mixins").get(8).getAsString());
        assertEquals("TrimEnvironmentSampleMixin",config.getAsJsonArray("mixins").get(9).getAsString());
        var metadata=new TomlParser().parse(new StringReader(Files.readString(Path.of("build/resources/main/META-INF/neoforge.mods.toml"))));
        List<UnmodifiableConfig> mixins=metadata.get("mixins");
        var entry=mixins.stream().filter(item->"sailboatmod.flightcontrol.mixins.json".equals(item.get("config"))).findFirst().orElseThrow();
        assertEquals(List.of("create_flight_control"),entry.get("requiredMods"));
    }
    @Test void compiledBridgeNeverLoadsTheDefinedMixinPackageOrOptionalClassDescriptors() throws Exception {
        for(String name:List.of("FlightControlBridge","FlightTaskRecoveryHooks","FlightControlRecoverySupport",
                "CruiseTrimCompatibility","NativeCruiseTrimSearch","BoundedTrimSearch",
                "SolverBudgetContext","NativeCruiseBudgetContext","NativeGroundNeutral","NativeGroundNeutralHook",
                "NativeGroundNeutralSupport","NativeGroundNeutralCompatibility","AircraftWheelContactCompatibility",
                "PitchBootstrapCompatibility")) {
            ClassNode node=compiled("com/monpai/sailboatmod/compat/flightcontrol/"+name);
            for(var field:node.fields) {
                assertFalse(field.desc.contains("Lace/flight/"));
                assertFalse(field.desc.contains("Lcom/simibubi/create/"));
            }
            for(var method:node.methods) {
                assertFalse(method.desc.contains("Lace/flight/"));
                assertFalse(method.desc.contains("Lcom/simibubi/create/"));
                for(var instruction:method.instructions) if(instruction instanceof MethodInsnNode call)
                    assertFalse(call.owner.startsWith(MIXIN_PACKAGE),"Business code must not directly load mixin-package classes");
            }
        }
    }
    @Test void theWrapperInvokesTheOriginalResumeExactlyOnceAndHasNoOptionalTypeInItsSignature() throws Exception {
        ClassNode node=compiled(MIXIN_PACKAGE+"NavigationTerminalRecoveryMixin");
        var wrappers=node.methods.stream().filter(method->method.name.equals("sailboat$observeAutomaticResume")).toList();
        assertEquals(1,wrappers.size());
        var wrapper=wrappers.getFirst();
        assertEquals("(Ljava/lang/Object;)Z",wrapper.desc);
        long originalCalls=0;
        for(var instruction:wrapper.instructions) if(instruction instanceof MethodInsnNode call
                && call.name.equals("requestResume") && call.desc.equals("()Z")) originalCalls++;
        assertEquals(1,originalCalls);
        assertTrue(wrapper.tryCatchBlocks.isEmpty(),"Native failures must propagate instead of becoming diagnostic success");
    }
    @Test void cruiseDispatchWrapsOneSupplierAndSubmitsItExactlyOnce() throws Exception {
        ClassNode node=compiled(MIXIN_PACKAGE+"FlightControlCruiseBudgetMixin");
        var wrappers=node.methods.stream().filter(method->method.name.equals("sailboat$inheritCruiseBudget")).toList();
        assertEquals(1,wrappers.size());
        var wrapper=wrappers.getFirst();
        assertEquals("(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",wrapper.desc);
        int captures=0, submissions=0;
        for(var instruction:wrapper.instructions) if(instruction instanceof MethodInsnNode call) {
            if(call.owner.equals("com/monpai/sailboatmod/compat/flightcontrol/NativeCruiseBudgetContext")
                    && call.name.equals("wrap") && call.desc.equals("(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/util/function/Supplier;")) captures++;
            if(call.owner.equals("java/util/concurrent/CompletableFuture") && call.name.equals("supplyAsync")
                    && call.desc.equals("(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;")) submissions++;
            assertFalse(call.owner.equals("java/util/concurrent/CompletableFuture")
                    && (call.name.equals("get") || call.name.equals("join")),"Submission must never block on a future");
        }
        assertEquals(1,captures);
        assertEquals(1,submissions);
        assertTrue(wrapper.tryCatchBlocks.isEmpty());
    }
    @Test void wheelHookHasNoOptionalTypesAndCanOnlyCancelTheExistingForceCall() throws Exception {
        ClassNode node=compiled(MIXIN_PACKAGE+"AircraftWheelContactMixin");
        var handler=node.methods.stream().filter(method->method.name.equals("sailboat$unloadedWheel")).findFirst().orElseThrow();
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;DLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",handler.desc);
        int checks=0,cancellations=0;
        for(var instruction:handler.instructions) if(instruction instanceof MethodInsnNode call) {
            if(call.owner.equals("com/monpai/sailboatmod/compat/flightcontrol/NativeTakeoffAssist")
                    && call.name.equals("ignoreUnloadedWheelForce")) checks++;
            if(call.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")
                    && call.name.equals("cancel")) cancellations++;
            assertFalse(call.owner.startsWith("dev/ryanhcode/"),"Hook must not write native forces, body state or control outputs");
        }
        assertEquals(1,checks);
        assertEquals(1,cancellations);
    }
    @Test void pitchScopeWrapsExactlyOneRunningUpdateAndClosesOnNativeFailureWithoutReplay() throws Exception {
        var handler=compiled(MIXIN_PACKAGE+"NativePitchBootstrapScopeMixin").methods.stream()
                .filter(method->method.name.equals("sailboat$scopeMeasuredPitch")).findFirst().orElseThrow();
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;ILcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Ljava/lang/Object;",handler.desc);
        assertNotNull(annotation(handler,"Lorg/spongepowered/asm/mixin/injection/Coerce;"));
        for(int index=0;index<3;index++)
            assertTrue(handler.invisibleParameterAnnotations[index].stream()
                    .anyMatch(value->value.desc.equals("Lorg/spongepowered/asm/mixin/injection/Coerce;")));
        var wrap=annotation(handler,"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        assertEquals(List.of("runRouteTask(Lace/flight/block/NavigationTerminalBlockEntity$ExecutionContext;)V"),value(wrap,"method"));
        assertEquals(1,value(wrap,"require"));
        assertEquals(1,value(wrap,"allow"));
        var at=(AnnotationNode)((List<?>)value(wrap,"at")).getFirst();
        assertEquals("Lace/flight/navigation/NavigationRouteFollower;updateFromComputer(Lace/flight/navigation/NavigationVehicleStateSnapshot;Lace/flight/navigation/NavigationComputer;I)Lace/flight/navigation/NavigationRouteFollower$Output;",value(at,"target"));
        int entries=0,calls=0,closes=0,throwsCount=0;
        MethodInsnNode nativeCall=null;
        for(var instruction:handler.instructions) {
            if(instruction.getOpcode()==Opcodes.ATHROW) throwsCount++;
            if(instruction instanceof MethodInsnNode call) {
                if(call.owner.equals("com/monpai/sailboatmod/compat/flightcontrol/NativeFixedWingPitchBootstrap")&&call.name.equals("enter")) entries++;
                if(call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")&&call.name.equals("call")) {calls++;nativeCall=call;}
                if(call.owner.equals("com/monpai/sailboatmod/compat/flightcontrol/NativeFixedWingPitchBootstrap$Scope")&&call.name.equals("close")) closes++;
                assertFalse(call.owner.startsWith("ace/flight/"),"The wrapper must use the original operation rather than a reflective replay");
            }
        }
        assertEquals(1,entries);
        assertEquals(1,calls);
        assertEquals(2,closes,"Both normal return and exceptional return must close the scope");
        assertEquals(1,throwsCount,"The original failure must propagate");
        int callIndex=handler.instructions.indexOf(nativeCall);
        assertTrue(handler.tryCatchBlocks.stream().anyMatch(block->block.type==null
                &&handler.instructions.indexOf(block.start)<=callIndex&&callIndex<handler.instructions.indexOf(block.end)),
                "The original operation must be inside the finally-protected range");
    }
    @Test void pitchCallCountersExcludeStartupAndRejectDuplicateScopedCalls() throws Exception {
        Class<?> plugin=isolatedPlugin();
        var scopeCount=plugin.getDeclaredMethod("pitchScopeCallCount",ClassNode.class);
        var demandCount=plugin.getDeclaredMethod("pitchDemandCallCount",ClassNode.class);
        var storeCount=plugin.getDeclaredMethod("pitchCompensationStoreCount",ClassNode.class);
        storeCount.setAccessible(true);
        scopeCount.setAccessible(true);demandCount.setAccessible(true);
        ClassNode terminal=new ClassNode();
        var run=new MethodNode(Opcodes.ACC_PRIVATE,"runRouteTask","(Lace/flight/block/NavigationTerminalBlockEntity$ExecutionContext;)V",null,null);
        var start=new MethodNode(Opcodes.ACC_PRIVATE,"startRouteTask",run.desc,null,null);
        var update=new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"ace/flight/navigation/NavigationRouteFollower","updateFromComputer",
                "(Lace/flight/navigation/NavigationVehicleStateSnapshot;Lace/flight/navigation/NavigationComputer;I)Lace/flight/navigation/NavigationRouteFollower$Output;",false);
        terminal.methods.add(start);terminal.methods.add(run);
        start.instructions.add(update.clone(null));
        assertEquals(0,scopeCount.invoke(null,terminal));
        run.instructions.add(update.clone(null));
        assertEquals(1,scopeCount.invoke(null,terminal));
        run.instructions.add(update.clone(null));
        assertEquals(2,scopeCount.invoke(null,terminal));
        ClassNode guidance=new ClassNode();
        var feedback=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"flightPathFeedback",
                "(Lace/flight/navigation/FixedWingGuidance$Settings;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDD)Lorg/joml/Vector3d;",null,null);
        guidance.methods.add(feedback);
        var demand=new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"ace/flight/navigation/FixedWingGuidance$Settings","demand","()Lace/flight/navigation/FixedWingAxialDemand;",false);
        assertEquals(0,demandCount.invoke(null,guidance));
        feedback.instructions.add(demand.clone(null));
        assertEquals(1,demandCount.invoke(null,guidance));
        feedback.instructions.add(demand.clone(null));
        assertEquals(2,demandCount.invoke(null,guidance));
        feedback.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.DSTORE,25));
        assertEquals(0,storeCount.invoke(null,guidance));
        feedback.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.DSTORE,23));
        assertEquals(1,storeCount.invoke(null,guidance));
        feedback.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.DSTORE,23));
        assertEquals(2,storeCount.invoke(null,guidance));
        feedback.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.DSTORE,23));
        assertEquals(3,storeCount.invoke(null,guidance));
    }
    @Test void pitchMarkersCannotClaimRecoveryOrEnableWithOnlyOneAppliedHook() throws Exception {
        Class<?> type=isolatedPlugin();Object plugin=type.getConstructor().newInstance();
        var post=type.getMethod("postApply",String.class,ClassNode.class,String.class,
                org.spongepowered.asm.mixin.extensibility.IMixinInfo.class);
        var recovery=FlightControlRecoverySupport.class.getDeclaredField("applied");
        var scope=PitchBootstrapCompatibility.class.getDeclaredField("scopeApplied");
        var guidance=PitchBootstrapCompatibility.class.getDeclaredField("guidanceApplied");
        var measurement=PitchBootstrapCompatibility.class.getDeclaredField("measurementApplied");measurement.setAccessible(true);
        boolean previousMeasurement=measurement.getBoolean(null);
        recovery.setAccessible(true);scope.setAccessible(true);guidance.setAccessible(true);
        boolean previousRecovery=recovery.getBoolean(null),previousScope=scope.getBoolean(null),previousGuidance=guidance.getBoolean(null);
        String prefix=MIXIN_PACKAGE.replace('/','.');
        try {
            recovery.setBoolean(null,false);scope.setBoolean(null,false);guidance.setBoolean(null,false);measurement.setBoolean(null,false);
            post.invoke(plugin,"ace.flight.block.NavigationTerminalBlockEntity",new ClassNode(),prefix+"UnknownMixin",null);
            assertFalse(FlightControlRecoverySupport.isApplied());
            assertFalse(PitchBootstrapCompatibility.isApplied());
            post.invoke(plugin,"ace.flight.block.NavigationTerminalBlockEntity",new ClassNode(),prefix+"NativePitchBootstrapScopeMixin",null);
            assertFalse(FlightControlRecoverySupport.isApplied());
            assertTrue(scope.getBoolean(null));assertFalse(PitchBootstrapCompatibility.isApplied());
            post.invoke(plugin,"ace.flight.navigation.FixedWingGuidance",new ClassNode(),prefix+"UnknownMixin",null);
            assertFalse(PitchBootstrapCompatibility.isApplied());
            post.invoke(plugin,"ace.flight.navigation.FixedWingGuidance",new ClassNode(),prefix+"FixedWingMeasuredPitchMixin",null);
            assertFalse(PitchBootstrapCompatibility.isApplied(),"No marker without the actual transformed calls");
            ClassNode transformed=new ClassNode();
            var feedback=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,"flightPathFeedback",
                    "(Lace/flight/navigation/FixedWingGuidance$Settings;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDD)Lorg/joml/Vector3d;",null,null);
            transformed.methods.add(feedback);
            feedback.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"ace/flight/navigation/FixedWingGuidance","handler$test$sailboat$captureMeasuredPitch",
                    "(DLcom/llamalad7/mixinextras/sugar/ref/LocalDoubleRef;)D",false));
            post.invoke(plugin,"ace.flight.navigation.FixedWingGuidance",transformed,prefix+"FixedWingMeasuredPitchMixin",null);
            assertFalse(PitchBootstrapCompatibility.isApplied(),"Both STORE hooks are required");
            feedback.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"ace/flight/navigation/FixedWingGuidance","handler$test$sailboat$blendMeasuredPitch",
                    "(DLjava/lang/Object;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDDLcom/llamalad7/mixinextras/sugar/ref/LocalDoubleRef;)D",false));
            post.invoke(plugin,"ace.flight.navigation.FixedWingGuidance",transformed,prefix+"FixedWingMeasuredPitchMixin",null);
            assertTrue(PitchBootstrapCompatibility.isApplied());assertFalse(FlightControlRecoverySupport.isApplied());
            post.invoke(plugin,"ace.flight.block.NavigationTerminalBlockEntity",new ClassNode(),prefix+"NavigationTerminalRecoveryMixin",null);
            assertTrue(FlightControlRecoverySupport.isApplied());
        } finally {
            recovery.setBoolean(null,previousRecovery);scope.setBoolean(null,previousScope);guidance.setBoolean(null,previousGuidance);measurement.setBoolean(null,previousMeasurement);
        }
    }
    private static Class<?> isolatedPlugin() throws Exception {
        String name=MIXIN_PACKAGE+"FlightControlMixinPlugin";
        try(var stream=FlightControlRecoveryPackagingTest.class.getResourceAsStream("/"+name+".class")) {
            assertNotNull(stream);byte[] bytes=stream.readAllBytes();
            return new ClassLoader(FlightControlRecoveryPackagingTest.class.getClassLoader()) {
                Class<?> loadPlugin() { return defineClass(name.replace('/','.'),bytes,0,bytes.length); }
            }.loadPlugin();
        }
    }
    private static AnnotationNode annotation(MethodNode method,String descriptor) {
        for(var annotations:List.of(method.visibleAnnotations==null?List.<AnnotationNode>of():method.visibleAnnotations,
                method.invisibleAnnotations==null?List.<AnnotationNode>of():method.invisibleAnnotations))
            for(var annotation:annotations) if(annotation.desc.equals(descriptor)) return annotation;
        fail("Missing annotation "+descriptor+" on "+method.name);return null;
    }
    private static Object value(AnnotationNode annotation,String name) {
        for(int index=0;index<annotation.values.size();index+=2)
            if(name.equals(annotation.values.get(index))) return annotation.values.get(index+1);
        fail("Missing annotation value "+name);return null;
    }
    private static ClassNode compiled(String name) throws Exception {
        try(var stream=FlightControlRecoveryPackagingTest.class.getResourceAsStream("/"+name+".class")) {
            assertNotNull(stream,name);
            ClassNode node=new ClassNode();new ClassReader(stream).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return node;
        }
    }
}
