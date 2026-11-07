package com.monpai.sailboatmod.compat.aeronautics.mixin;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftMotionCompatibility;
import com.monpai.sailboatmod.compat.aeronautics.RailAircraftNativeResources;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RailMotionNativeResourcesTest {
    @Test void originalOuterSableAloneProvidesEveryPinnedBodyAndNestedRapierClass() throws Exception {
        String configured = System.getenv("SAILBOAT_NATIVE_SABLE_JAR");
        assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)), "Set SAILBOAT_NATIVE_SABLE_JAR");
        Map<String, String> pins = new HashMap<>(RailAircraftMotionCompatibility.PINNED_CLASSES);
        pins.putAll(RailAircraftMotionCompatibility.PINNED_RAPIER_CLASSES);
        try (ZipFile outer = new ZipFile(configured)) {
            assertNull(outer.getEntry(RailAircraftMotionCompatibility.RAPIER.replace('.', '/') + ".class"),
                    "Rapier is a nested GAMELIBRARY, absent from the outer mod's direct class resources");
            List<String> requests = new ArrayList<>();
            Map<String, byte[]> actual = RailAircraftNativeResources.read(resource -> {
                requests.add(resource);
                var entry = outer.getEntry(resource);
                if (entry == null) throw new FileNotFoundException(resource);
                return outer.getInputStream(entry);
            });
            assertEquals(pins.keySet(), actual.keySet());
            for (var pin : pins.entrySet()) assertEquals(pin.getValue(), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(actual.get(pin.getKey()))), pin.getKey());
            assertFalse(requests.contains(RailAircraftMotionCompatibility.RAPIER.replace('.', '/') + ".class"),
                    "The mod-file list need not expose the Rapier game library as a standalone mod");
            assertTrue(RailMotionMixinPlugin.supportsNativeResources("2.0.6", resource -> {
                var entry = outer.getEntry(resource);
                if (entry == null) throw new FileNotFoundException(resource);
                return outer.getInputStream(entry);
            }), "The actual plugin's complete hash/ABI gate must accept the outer-only resource source");
            assertFalse(RailMotionMixinPlugin.supportsNativeResources("2.0.7", resource -> outer.getInputStream(outer.getEntry(resource))));
            assertFalse(RailMotionMixinPlugin.supportsNativeResources("2.0.6", resource -> {
                String actualPath = resource.equals(RailAircraftMotionCompatibility.BODY.replace('.', '/') + ".class")
                        ? RailAircraftMotionCompatibility.PLOT.replace('.', '/') + ".class" : resource;
                return outer.getInputStream(outer.getEntry(actualPath));
            }), "Correct nested loading must not relax any original class pin");
        }
    }

    @Test void missingNestedArchiveNeverFallsBackToAnUnrelatedVisibleClass() {
        var source = synthetic(); source.remove(RailAircraftNativeResources.RAPIER_NESTED);
        for (String name : RailAircraftMotionCompatibility.PINNED_RAPIER_CLASSES.keySet())
            source.put(name.replace('.', '/') + ".class", new byte[]{1, 2, 3});
        assertThrows(FileNotFoundException.class, () -> RailAircraftNativeResources.read(open(source)));
    }
    @Test void missingOrMalformedNestedClassesAreRejected() throws Exception {
        var source = synthetic(); source.put(RailAircraftNativeResources.RAPIER_NESTED, new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> RailAircraftNativeResources.read(open(source)));
        source.put(RailAircraftNativeResources.RAPIER_NESTED, archive(Map.of("unrelated.class", new byte[]{1})));
        assertThrows(IOException.class, () -> RailAircraftNativeResources.read(open(source)));
    }
    @Test void originalClassAndNestedArchiveHaveIndependentByteLimits() {
        var oversizedClass = synthetic(); String first = RailAircraftMotionCompatibility.PINNED_CLASSES.keySet().iterator().next();
        oversizedClass.put(first.replace('.', '/') + ".class", new byte[RailAircraftNativeResources.MAX_CLASS_BYTES + 1]);
        assertThrows(IOException.class, () -> RailAircraftNativeResources.read(open(oversizedClass)));
        var oversizedNested = synthetic(); oversizedNested.put(RailAircraftNativeResources.RAPIER_NESTED, new byte[RailAircraftNativeResources.MAX_NESTED_BYTES + 1]);
        assertThrows(IOException.class, () -> RailAircraftNativeResources.read(open(oversizedNested)));
    }
    @Test void excessiveZipExpansionAndEntryCountAreRejectedBeforeUnboundedWork() throws Exception {
        var source = synthetic(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("padding")); byte[] zeros = new byte[8192];
            for (int written = 0; written <= RailAircraftNativeResources.MAX_EXPANDED_BYTES; written += zeros.length) zip.write(zeros);
            zip.closeEntry();
        }
        source.put(RailAircraftNativeResources.RAPIER_NESTED, bytes.toByteArray());
        assertTrue(assertThrows(IOException.class, () -> RailAircraftNativeResources.read(open(source))).getMessage().contains("expansion_limit"));
        var entries = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i <= RailAircraftNativeResources.MAX_ENTRIES; i++) entries.put("empty-" + i, new byte[0]);
        source.put(RailAircraftNativeResources.RAPIER_NESTED, archive(entries));
        assertTrue(assertThrows(IOException.class, () -> RailAircraftNativeResources.read(open(source))).getMessage().contains("entry_limit"));
    }
    private static Map<String, byte[]> synthetic() {
        var source = new HashMap<String, byte[]>();
        for (String name : RailAircraftMotionCompatibility.PINNED_CLASSES.keySet()) source.put(name.replace('.', '/') + ".class", new byte[]{1});
        var nativeEntries = new HashMap<String, byte[]>();
        for (String name : RailAircraftMotionCompatibility.PINNED_RAPIER_CLASSES.keySet()) nativeEntries.put(name.replace('.', '/') + ".class", new byte[]{2});
        try { source.put(RailAircraftNativeResources.RAPIER_NESTED, archive(nativeEntries)); }
        catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        return source;
    }
    private static RailAircraftNativeResources.Source open(Map<String, byte[]> resources) {
        return name -> {
            byte[] bytes = resources.get(name);
            if (bytes == null) throw new FileNotFoundException(name);
            return new ByteArrayInputStream(bytes);
        };
    }
    private static byte[] archive(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
}
