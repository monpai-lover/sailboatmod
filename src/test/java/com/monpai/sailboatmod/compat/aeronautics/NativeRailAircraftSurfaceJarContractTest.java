package com.monpai.sailboatmod.compat.aeronautics;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Reads original Create bytes only; never instantiates a native bearing or JNI body. */
class NativeRailAircraftSurfaceJarContractTest {
    @Test void originalCreateAbiHasTheFiniteStepAndClientSynchronizationHooks() throws Exception {
        String configured = System.getenv("SAILBOAT_NATIVE_CREATE_JAR");
        Path path = configured == null ? Path.of("libs/compile-only/create-1.21.1-6.0.10.jar") : Path.of(configured);
        assumeTrue(Files.isRegularFile(path), "Set SAILBOAT_NATIVE_CREATE_JAR");
        var classes = new HashMap<String, byte[]>();
        try (ZipFile jar = new ZipFile(path.toFile())) {
            for (String name : RailAircraftSurfaceCompatibility.PINS.keySet())
                try (var input = jar.getInputStream(jar.getEntry(name.replace('.', '/') + ".class"))) { classes.put(name, input.readAllBytes()); }
        }
        assertTrue(RailAircraftSurfaceCompatibility.supports("6.0.10", classes));
        assertFalse(RailAircraftSurfaceCompatibility.supports("6.0.11", classes));
        var changed = new HashMap<>(classes); byte[] bytes = changed.get(RailAircraftSurfaceCompatibility.BEARING).clone();
        bytes[bytes.length - 1] ^= 1; changed.put(RailAircraftSurfaceCompatibility.BEARING, bytes);
        assertFalse(RailAircraftSurfaceCompatibility.supports("6.0.10", changed));
        var body = node(classes.get(RailAircraftSurfaceCompatibility.BEARING));
        for (String[] method : new String[][]{{"tick", "()V"}, {"getAngularSpeed", "()F"},
                {"setAngle", "(F)V"}, {"getMovedContraption", "()Lcom/simibubi/create/content/contraptions/ControlledContraptionEntity;"},
                {"write", "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V"},
                {"read", "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V"}})
            assertEquals(1, body.methods.stream().filter(m -> m.name.equals(method[0]) && m.desc.equals(method[1])).count());
        assertTrue(body.fields.stream().anyMatch(f -> f.name.equals("angle") && f.desc.equals("F")
                && (f.access & Opcodes.ACC_PROTECTED) != 0));
        var part = node(classes.get(RailAircraftSurfaceCompatibility.CONTRAPTION));
        assertEquals(1, part.methods.stream().filter(m -> m.name.equals("getAngle") && m.desc.equals("(F)F")).count());
        assertEquals(1, part.methods.stream().filter(m -> m.name.equals("setAngle") && m.desc.equals("(F)V")).count());
    }
    @Test void absentOptionalModsAndInvalidCallerCannotInitiateAClaim() {
        assertFalse(RailAircraftSurfaceBridge.available(null));
        assertFalse(RailAircraftSurfaceBridge.claim(null, null, null, null).accepted());
        assertFalse(RailAircraftSurfaceBridge.setTarget(null, null, 0, 60).success());
        assertFalse(RailAircraftSurfaceBridge.status(null, null).success());
        assertFalse(RailAircraftSurfaceCompatibility.supports("6.0.10", new HashMap<>()));
    }
    private static ClassNode node(byte[] bytes) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); return node;
    }
}
