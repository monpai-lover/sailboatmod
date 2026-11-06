package com.monpai.sailboatmod.compat.aeronautics;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Checks the installed native body without initializing Sable, its JNI library or Flight Control. */
class NativeRailAircraftMotionJarContractTest {
    @Test void installedFccOwnershipReadbackHasTheInspectedCachedFieldsAndPureReaders() throws Exception {
        String configured = System.getenv("SAILBOAT_NATIVE_FCC_JAR");
        if ((configured == null || configured.isBlank()) && System.getenv("SAILBOAT_NATIVE_SABLE_JAR") != null)
            configured = Path.of(System.getenv("SAILBOAT_NATIVE_SABLE_JAR")).resolveSibling("create_flight_control-neoforge-0.8.3.jar").toString();
        assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)), "Set SAILBOAT_NATIVE_FCC_JAR for the optional ownership ABI contract");
        var nodes = new HashMap<String, ClassNode>();
        try (var zip = new ZipFile(Path.of(configured).toFile())) {
            for (var pin : RailAircraftFlightControlGuard.PINS.entrySet()) {
                byte[] bytes;
                try (var input = zip.getInputStream(zip.getEntry(pin.getKey().replace('.', '/') + ".class"))) { bytes = input.readAllBytes(); }
                assertEquals(pin.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                nodes.put(pin.getKey(), node(bytes));
            }
        }
        ClassNode fcc = nodes.get(RailAircraftFlightControlGuard.COMPUTER);
        assertTrue(fcc.interfaces.contains("dev/ryanhcode/sable/api/block/BlockEntitySubLevelActor"));
        for (String name : java.util.List.of("working", "workingDefaultEnabled"))
            assertTrue(fcc.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals("Z")));
        assertTrue(fcc.fields.stream().anyMatch(field -> field.name.equals("navigationLeaseBroker")
                && field.desc.equals("Lace/flight/navigation/NavigationLeaseBroker;")));
        pureReader(nodes.get(RailAircraftFlightControlGuard.BROKER), "activeBundleCount", "()I", "size");
        pureReader(nodes.get(RailAircraftFlightControlGuard.BROKER), "effectiveFrame",
                "()Lace/flight/navigation/NavigationEffectiveCommandFrame;", "get");
        pureReader(nodes.get(RailAircraftFlightControlGuard.FRAME), "commands", "()Ljava/util/Map;", null);
    }
    @Test void actualSableBodyAndItsTwoScopedFenceTargetsMatchTheInspectedAbi() throws Exception {
        String configured = System.getenv("SAILBOAT_NATIVE_SABLE_JAR");
        assumeTrue(configured != null && !configured.isBlank(), "Set SAILBOAT_NATIVE_SABLE_JAR for the native motion contract");
        var hashes = new HashMap<String, String>();
        var nodes = new HashMap<String, ClassNode>();
        try (var zip = new ZipFile(Path.of(configured).toFile())) {
            for (String name : RailAircraftMotionCompatibility.PINNED_CLASSES.keySet()) {
                var entry = zip.getEntry(name.replace('.', '/') + ".class");
                assertNotNull(entry, name);
                byte[] bytes;
                try (var input = zip.getInputStream(entry)) { bytes = input.readAllBytes(); }
                hashes.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                nodes.put(name, node(bytes));
            }
            byte[] nested;
            try (var input = zip.getInputStream(zip.getEntry(
                    "META-INF/jarjar/dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.6.jar"))) {
                nested = input.readAllBytes();
            }
            try (var nativeZip = new ZipInputStream(new ByteArrayInputStream(nested))) {
                for (var entry = nativeZip.getNextEntry(); entry != null; entry = nativeZip.getNextEntry()) {
                    String name = entry.getName().replace('/', '.').replaceAll("\\.class$", "");
                    if (!RailAircraftMotionCompatibility.PINNED_RAPIER_CLASSES.containsKey(name)) continue;
                    byte[] bytes = nativeZip.readAllBytes();
                    hashes.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    nodes.put(name, node(bytes));
                }
            }
        }
        ClassNode body = nodes.get(RailAircraftMotionCompatibility.BODY);
        assertNotNull(body);
        assertTrue(body.fields.stream().anyMatch(field -> field.name.equals("latestAngularVelocity")
                && field.desc.equals("Lorg/joml/Vector3d;")
                && (field.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0),
                "Stand readiness must read the native current angular velocity field");
        int actors = 0, queued = 0;
        for (var method : body.methods) if (method.desc.equals(RailAircraftMotionCompatibility.PHYSICS_METHOD)) {
            if (method.name.equals("prePhysicsTick")) actors++;
            if (method.name.equals("applyQueuedForces")) queued++;
        }
        assertTrue(RailAircraftMotionCompatibility.supports("2.0.6", hashes, actors, queued));
        pureReader(nodes.get(RailAircraftMotionCompatibility.PLOT), "getBlockEntityActors", "()Ljava/lang/Iterable;", "values");
        ClassNode pipeline = nodes.get(RailAircraftMotionCompatibility.RAPIER);
        ClassNode constraint = nodes.get(RailAircraftMotionCompatibility.CONSTRAINT);
        assertTrue(RailAircraftMotionCompatibility.supportsTopology("2.0.6", hashes,
                count(pipeline, "addConstraint", RailAircraftMotionCompatibility.ADD_CONSTRAINT_METHOD),
                count(pipeline, "dispose", "()V"), count(pipeline, "remove", RailAircraftMotionCompatibility.REMOVE_BODY_METHOD),
                count(constraint, "remove", "()V"), count(constraint, "isValid", "()Z")));
        for (var field : constraint.fields) if ((field.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0)
            assertTrue(field.desc.equals("J") || field.desc.equals("[D"), "Strong numeric native handles must not retain a world");
        for (var pin : RailAircraftMotionCompatibility.PINNED_RAPIER_CLASSES.keySet())
            if (!pin.equals(RailAircraftMotionCompatibility.RAPIER) && !pin.equals(RailAircraftMotionCompatibility.CONSTRAINT))
                assertTrue(nodes.get(pin).fields.stream().allMatch(field -> (field.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0));
    }
    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
    private static int count(ClassNode node, String name, String descriptor) {
        assertNotNull(node);
        return (int) node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).count();
    }
    private static void pureReader(ClassNode node, String name, String descriptor, String permittedCall) {
        var method = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElseThrow();
        for (var instruction : method.instructions) {
            assertNotEquals(org.objectweb.asm.Opcodes.PUTFIELD, instruction.getOpcode());
            assertNotEquals(org.objectweb.asm.Opcodes.PUTSTATIC, instruction.getOpcode());
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) assertEquals(permittedCall, call.name);
        }
    }
}
