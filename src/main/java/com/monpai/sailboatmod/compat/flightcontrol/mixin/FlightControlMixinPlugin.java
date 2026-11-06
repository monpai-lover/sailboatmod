package com.monpai.sailboatmod.compat.flightcontrol.mixin;

import com.monpai.sailboatmod.compat.flightcontrol.FlightControlRecoverySupport;
import com.monpai.sailboatmod.compat.flightcontrol.CruiseTrimCompatibility;
import com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutralCompatibility;
import com.monpai.sailboatmod.compat.flightcontrol.AircraftWheelContactCompatibility;
import com.monpai.sailboatmod.compat.flightcontrol.PitchBootstrapCompatibility;
import com.monpai.sailboatmod.compat.flightcontrol.TrimEnvironmentCompatibility;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.HashMap;
import java.util.Set;

/** No optional class is loaded while deciding whether to install this version-specific observer. */
public final class FlightControlMixinPlugin implements IMixinConfigPlugin {
    private static final String TARGET = "ace.flight.block.NavigationTerminalBlockEntity";
    private static final String OWNER = TARGET.replace('.', '/');
    private static final String CRUISE = "ace.flight.physics.FixedWingCruise";
    private static final String COMPUTER = "ace.flight.block.FlightControlComputerBlockEntity";
    private static final String GUIDANCE = "ace.flight.navigation.FixedWingGuidance";
    private static final String MIXIN_PACKAGE = "com.monpai.sailboatmod.compat.flightcontrol.mixin.";
    private static final String PITCH_SCOPE_MIXIN = MIXIN_PACKAGE + "NativePitchBootstrapScopeMixin";
    private static final String PITCH_GUIDANCE_MIXIN = MIXIN_PACKAGE + "FixedWingMeasuredPitchMixin";
    private static final String TRIM_SCOPE_MIXIN = MIXIN_PACKAGE + "TrimEnvironmentScopeMixin";
    private static final String TRIM_BUDGET_MIXIN = MIXIN_PACKAGE + "TrimEnvironmentBudgetMixin";
    private static final String TRIM_SAMPLE_MIXIN = MIXIN_PACKAGE + "TrimEnvironmentSampleMixin";
    private static final String WHEEL = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity";
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (WHEEL.equals(target)) {
            if (!mixin.equals("com.monpai.sailboatmod.compat.flightcontrol.mixin.AircraftWheelContactMixin")) return false;
            try {
                var list = LoadingModList.get();
                var file = list == null ? null : list.getModFileById("offroad");
                if (file == null) return false;
                String version = file.getMods().stream().filter(mod -> "offroad".equals(mod.getModId()))
                        .map(mod -> mod.getVersion().toString()).findFirst().orElse("");
                byte[] bytes = Files.readAllBytes(file.getFile().findResource(WHEEL.replace('.', '/') + ".class"));
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                int count = 0;
                for (var method : node.methods) if ("sable$physicsTick".equals(method.name)
                        && "(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V".equals(method.desc))
                    for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                            && "dev/ryanhcode/sable/api/physics/force/ForceTotal".equals(call.owner)
                            && "applyImpulseAtPoint".equals(call.name)
                            && "(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V".equals(call.desc)) count++;
                return AircraftWheelContactCompatibility.supports(version, hash(bytes), count);
            } catch (Exception | LinkageError unavailable) { return false; }
        }
        boolean pitchScope = TARGET.equals(target) && PITCH_SCOPE_MIXIN.equals(mixin);
        boolean pitchGuidance = GUIDANCE.equals(target) && PITCH_GUIDANCE_MIXIN.equals(mixin);
        if (!TARGET.equals(target) && !CRUISE.equals(target) && !COMPUTER.equals(target) && !pitchGuidance) return false;
        if (TARGET.equals(target) && !pitchScope && !(MIXIN_PACKAGE + "NavigationTerminalRecoveryMixin").equals(mixin)) return false;
        try {
            var list = LoadingModList.get();
            var file = list == null ? null : list.getModFileById("create_flight_control");
            if (file == null) return false;
            String version = file.getMods().stream().filter(mod -> "create_flight_control".equals(mod.getModId()))
                    .map(mod -> mod.getVersion().toString()).findFirst().orElse("");
            if (COMPUTER.equals(target) && (TRIM_SCOPE_MIXIN.equals(mixin)
                    || TRIM_BUDGET_MIXIN.equals(mixin) || TRIM_SAMPLE_MIXIN.equals(mixin))) {
                var hashes = new HashMap<String, String>();
                ClassNode computer = null;
                for (String name : TrimEnvironmentCompatibility.PINNED_CLASSES.keySet()) {
                    byte[] bytes = Files.readAllBytes(file.getFile().findResource(name.replace('.', '/') + ".class"));
                    hashes.put(name, hash(bytes));
                    if (COMPUTER.equals(name)) {
                        computer = new ClassNode();
                        new ClassReader(bytes).accept(computer, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    }
                }
                int[] counts = trimEnvironmentCallCounts(computer);
                return TrimEnvironmentCompatibility.supports(version,
                        hash(Files.readAllBytes(file.getFile().getFilePath())), hashes, counts[0], counts[1], counts[2]);
            }
            if (pitchScope || pitchGuidance) {
                var hashes = new HashMap<String, String>();
                ClassNode terminal = null, guidance = null;
                for (String name : PitchBootstrapCompatibility.PINNED_CLASSES.keySet()) {
                    byte[] bytes = Files.readAllBytes(file.getFile().findResource(name.replace('.', '/') + ".class"));
                    hashes.put(name, hash(bytes));
                    if (name.equals(TARGET) || name.equals(GUIDANCE)) {
                        ClassNode node = new ClassNode();
                        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        if (name.equals(TARGET)) terminal = node; else guidance = node;
                    }
                }
                return PitchBootstrapCompatibility.supports(version, hashes,
                        pitchScopeCallCount(terminal), pitchDemandCallCount(guidance), pitchCompensationStoreCount(guidance));
            }
            if (COMPUTER.equals(target)) {
                if (mixin.equals("com.monpai.sailboatmod.compat.flightcontrol.mixin.FlightControlGroundNeutralMixin")) {
                    byte[] computer = Files.readAllBytes(file.getFile().findResource("ace/flight/block/FlightControlComputerBlockEntity.class"));
                    ClassNode node = new ClassNode();
                    new ClassReader(computer).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    return NativeGroundNeutralCompatibility.supports(version, hash(Files.readAllBytes(file.getFile().getFilePath())),
                            hash(computer), groundNeutralCallCount(node));
                }
                if (!mixin.equals("com.monpai.sailboatmod.compat.flightcontrol.mixin.FlightControlCruiseBudgetMixin")) return false;
                byte[] computer = Files.readAllBytes(file.getFile().findResource("ace/flight/block/FlightControlComputerBlockEntity.class"));
                byte[] budget = Files.readAllBytes(file.getFile().findResource("ace/flight/physics/AllocationBudget.class"));
                ClassNode node = new ClassNode();
                new ClassReader(computer).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return CruiseTrimCompatibility.supportsBudgetPropagation(version, hash(computer), hash(budget), budgetCallCount(node));
            }
            if (CRUISE.equals(target)) {
                if (!mixin.equals("com.monpai.sailboatmod.compat.flightcontrol.mixin.FixedWingCruiseTrimMixin")) return false;
                var hashes = new HashMap<String, String>();
                byte[] cruise = null;
                for (String name : CruiseTrimCompatibility.PINNED_CLASSES.keySet()) {
                    byte[] bytes = Files.readAllBytes(file.getFile().findResource("ace/flight/physics/" + name + ".class"));
                    hashes.put(name, hash(bytes));
                    if (name.equals("FixedWingCruise")) cruise = bytes;
                }
                ClassNode node = new ClassNode();
                new ClassReader(cruise).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return CruiseTrimCompatibility.supports(version, hashes, cruiseCallCount(node));
            }
            byte[] terminal = Files.readAllBytes(file.getFile().findResource("ace/flight/block/NavigationTerminalBlockEntity.class"));
            byte[] computer = Files.readAllBytes(file.getFile().findResource("ace/flight/block/FlightControlComputerBlockEntity.class"));
            ClassNode node = new ClassNode();
            new ClassReader(terminal).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            int[] counts = callCounts(node);
            return FlightControlRecoverySupport.supports(version, hash(terminal), hash(computer), counts[0], counts[1]);
        } catch (Exception | LinkageError unavailable) { return false; }
    }
    private static int cruiseCallCount(ClassNode node) {
        var methods = node.methods.stream().filter(method -> "lambda$solve$0".equals(method.name)
                && "(Lace/flight/physics/FixedWingLevelTrim;D)Lace/flight/physics/FixedWingCruise$Point;".equals(method.desc)).toList();
        if (methods.size() != 1) return 0;
        int count = 0;
        for (var instruction : methods.getFirst().instructions) if (instruction instanceof MethodInsnNode call
                && "ace/flight/physics/FixedWingLevelTrim".equals(call.owner) && "controls".equals(call.name)
                && "(DLorg/joml/Vector3d;)[D".equals(call.desc)) count++;
        return count;
    }
    private static int[] trimEnvironmentCallCounts(ClassNode node) {
        return new int[]{
                exactNativeCallCount(node, TrimEnvironmentCompatibility.SCOPE_METHOD, TrimEnvironmentCompatibility.SCOPE_TARGET, Opcodes.INVOKEVIRTUAL),
                exactNativeCallCount(node, TrimEnvironmentCompatibility.ALLOCATION, TrimEnvironmentCompatibility.BUDGET_TARGET, Opcodes.INVOKEVIRTUAL),
                exactNativeCallCount(node, TrimEnvironmentCompatibility.ALLOCATION, TrimEnvironmentCompatibility.SAMPLE_TARGET, Opcodes.INVOKESPECIAL)};
    }
    private static int exactNativeCallCount(ClassNode node, String methodSignature, String target, int opcode) {
        if (node == null) return 0;
        int args = methodSignature.indexOf('(');
        var methods = node.methods.stream().filter(method -> methodSignature.substring(0, args).equals(method.name)
                && methodSignature.substring(args).equals(method.desc)).toList();
        if (methods.size() != 1) return 0;
        int separator = target.indexOf(';'), targetArgs = target.indexOf('(', separator);
        String owner = target.substring(1, separator), name = target.substring(separator+1, targetArgs), descriptor = target.substring(targetArgs);
        int count = 0;
        for (var instruction : methods.getFirst().instructions) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == opcode && owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc)) count++;
        return count;
    }
    private static int pitchScopeCallCount(ClassNode node) {
        if (node == null) return 0;
        var methods = node.methods.stream().filter(method -> "runRouteTask".equals(method.name)
                && "(Lace/flight/block/NavigationTerminalBlockEntity$ExecutionContext;)V".equals(method.desc)).toList();
        if (methods.size() != 1) return 0;
        int count = 0;
        for (var instruction : methods.getFirst().instructions) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == 182 && "ace/flight/navigation/NavigationRouteFollower".equals(call.owner)
                && "updateFromComputer".equals(call.name)
                && "(Lace/flight/navigation/NavigationVehicleStateSnapshot;Lace/flight/navigation/NavigationComputer;I)Lace/flight/navigation/NavigationRouteFollower$Output;".equals(call.desc)) count++;
        return count;
    }
    private static int pitchDemandCallCount(ClassNode node) {
        if (node == null) return 0;
        var methods = node.methods.stream().filter(method -> "flightPathFeedback".equals(method.name)
                && "(Lace/flight/navigation/FixedWingGuidance$Settings;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDD)Lorg/joml/Vector3d;".equals(method.desc)).toList();
        if (methods.size() != 1) return 0;
        int count = 0;
        for (var instruction : methods.getFirst().instructions) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == 182 && "ace/flight/navigation/FixedWingGuidance$Settings".equals(call.owner)
                && "demand".equals(call.name) && "()Lace/flight/navigation/FixedWingAxialDemand;".equals(call.desc)) count++;
        return count;
    }
    private static int pitchCompensationStoreCount(ClassNode node) {
        if (node == null) return 0;
        var methods = node.methods.stream().filter(method -> "flightPathFeedback".equals(method.name)
                && "(Lace/flight/navigation/FixedWingGuidance$Settings;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDD)Lorg/joml/Vector3d;".equals(method.desc)).toList();
        if (methods.size() != 1) return 0;
        int count = 0;
        for (var instruction : methods.getFirst().instructions) if (instruction instanceof VarInsnNode variable
                && variable.getOpcode() == Opcodes.DSTORE && variable.var == 23) count++;
        return count;
    }
    /** A post-apply marker requires both actual injected calls, not only a mixin class name. */
    private static boolean pitchStoresApplied(ClassNode node) {
        if (node == null) return false;
        var methods = node.methods.stream().filter(method -> "flightPathFeedback".equals(method.name)
                && "(Lace/flight/navigation/FixedWingGuidance$Settings;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDD)Lorg/joml/Vector3d;".equals(method.desc)).toList();
        if (methods.size() != 1) return false;
        int captures = 0, blends = 0;
        for (var instruction : methods.getFirst().instructions) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC && "ace/flight/navigation/FixedWingGuidance".equals(call.owner)) {
            if (call.name.endsWith("sailboat$captureMeasuredPitch")
                    && "(DLcom/llamalad7/mixinextras/sugar/ref/LocalDoubleRef;)D".equals(call.desc)) captures++;
            if (call.name.endsWith("sailboat$blendMeasuredPitch")
                    && "(DLjava/lang/Object;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Lorg/joml/Vector3d;DDDDLcom/llamalad7/mixinextras/sugar/ref/LocalDoubleRef;)D".equals(call.desc)) blends++;
        }
        return captures == 1 && blends == 1;
    }
    private static int budgetCallCount(ClassNode node) {
        var methods = node.methods.stream().filter(method -> "startCruiseEstimate".equals(method.name)
                && "(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;DLace/flight/block/FlightControlComputerBlockEntity$FixedWingValidation;Ljava/util/List;)V".equals(method.desc)).toList();
        if (methods.size() != 1) return 0;
        int count = 0;
        for (var instruction : methods.getFirst().instructions) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == 184 && "java/util/concurrent/CompletableFuture".equals(call.owner)
                && "supplyAsync".equals(call.name)
                && "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;".equals(call.desc)) count++;
        return count;
    }
    private static int groundNeutralCallCount(ClassNode node) {
        var methods = node.methods.stream().filter(method -> "cfc$physicsTickAllocation".equals(method.name)
                && "(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V".equals(method.desc)).toList();
        if (methods.size() != 1) return 0;
        int count = 0;
        for (var instruction : methods.getFirst().instructions) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == 182 && "ace/flight/block/FlightControlComputerBlockEntity".equals(call.owner)
                && "computeCompositeBodyState".equals(call.name)
                && "(Ljava/lang/Object;)Lace/flight/physics/FlightPhysicsController$BodyStateOverride;".equals(call.desc)) count++;
        return count;
    }
    private static int[] callCounts(ClassNode node) {
        var methods = node.methods.stream().filter(method -> "reconcileAutomaticEnableState".equals(method.name)
                && "()V".equals(method.desc)).toList();
        if (methods.size() != 1) return new int[]{0, 0};
        int resumes = 0, starts = 0;
        for (var instruction : methods.getFirst().instructions) if (instruction instanceof MethodInsnNode call && OWNER.equals(call.owner)) {
            if ("requestResume".equals(call.name) && "()Z".equals(call.desc)) resumes++;
            if ("requestStart".equals(call.name) && "(Z)Z".equals(call.desc)) starts++;
        }
        return new int[]{resumes, starts};
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (WHEEL.equals(target) && mixin.equals("com.monpai.sailboatmod.compat.flightcontrol.mixin.AircraftWheelContactMixin"))
            AircraftWheelContactCompatibility.markApplied();
        if (TARGET.equals(target) && (MIXIN_PACKAGE + "NavigationTerminalRecoveryMixin").equals(mixin))
            FlightControlRecoverySupport.markApplied();
        if (TARGET.equals(target) && PITCH_SCOPE_MIXIN.equals(mixin)) PitchBootstrapCompatibility.markScopeApplied();
        if (GUIDANCE.equals(target) && PITCH_GUIDANCE_MIXIN.equals(mixin) && pitchStoresApplied(node)) {
            PitchBootstrapCompatibility.markMeasurementApplied();
            PitchBootstrapCompatibility.markGuidanceApplied();
        }
        if (COMPUTER.equals(target) && TRIM_SCOPE_MIXIN.equals(mixin)) TrimEnvironmentCompatibility.markScopeApplied();
        if (COMPUTER.equals(target) && TRIM_BUDGET_MIXIN.equals(mixin)) TrimEnvironmentCompatibility.markBudgetApplied();
        if (COMPUTER.equals(target) && TRIM_SAMPLE_MIXIN.equals(mixin)) TrimEnvironmentCompatibility.markSampleApplied();
        if (CRUISE.equals(target) && (MIXIN_PACKAGE + "FixedWingCruiseTrimMixin").equals(mixin))
            CruiseTrimCompatibility.markApplied();
        if (COMPUTER.equals(target)
                && mixin.equals("com.monpai.sailboatmod.compat.flightcontrol.mixin.FlightControlCruiseBudgetMixin"))
            CruiseTrimCompatibility.markBudgetPropagationApplied();
        if (COMPUTER.equals(target)
                && mixin.equals("com.monpai.sailboatmod.compat.flightcontrol.mixin.FlightControlGroundNeutralMixin"))
            NativeGroundNeutralCompatibility.markApplied();
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
