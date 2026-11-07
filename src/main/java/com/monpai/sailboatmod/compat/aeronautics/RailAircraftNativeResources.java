package com.monpai.sailboatmod.compat.aeronautics;

import java.io.*;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Original native resources only; never loads or initializes a Sable class. */
public final class RailAircraftNativeResources {
    public static final String RAPIER_NESTED = "META-INF/jarjar/dev.ryanhcode.sable.sable-sable_rapier-1.21.1-2.0.6.jar";
    public static final int MAX_CLASS_BYTES = 1024 * 1024, MAX_NESTED_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ENTRIES = 512, MAX_EXPANDED_BYTES = 32 * 1024 * 1024;
    @FunctionalInterface public interface Source { InputStream open(String resource) throws IOException; }
    private RailAircraftNativeResources() { }

    public static Map<String, byte[]> read(Source outerSable) throws IOException {
        if (outerSable == null) throw new IOException("missing_outer_sable_resource_source");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (String name : RailAircraftMotionCompatibility.PINNED_CLASSES.keySet())
            try (InputStream input = required(outerSable, name.replace('.', '/') + ".class")) {
                classes.put(name, bounded(input, MAX_CLASS_BYTES));
            }
        byte[] nested;
        try (InputStream input = required(outerSable, RAPIER_NESTED)) { nested = bounded(input, MAX_NESTED_BYTES); }
        Map<String, String> requiredEntries = new HashMap<>();
        for (String name : RailAircraftMotionCompatibility.PINNED_RAPIER_CLASSES.keySet())
            requiredEntries.put(name.replace('.', '/') + ".class", name);
        int entries = 0, expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(nested))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > MAX_ENTRIES) throw new IOException("native_nested_entry_limit");
                String name = requiredEntries.get(entry.getName());
                if (name != null) {
                    if (classes.containsKey(name)) throw new IOException("duplicate_pinned_native_class:" + name);
                    byte[] bytes = bounded(zip, MAX_CLASS_BYTES);
                    classes.put(name, bytes); expanded += bytes.length;
                } else {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = zip.read(buffer)) != -1) {
                        expanded += count;
                        if (expanded > MAX_EXPANDED_BYTES) throw new IOException("native_nested_expansion_limit");
                    }
                }
                if (expanded > MAX_EXPANDED_BYTES) throw new IOException("native_nested_expansion_limit");
            }
        }
        if (!classes.keySet().containsAll(RailAircraftMotionCompatibility.PINNED_RAPIER_CLASSES.keySet()))
            throw new IOException("missing_pinned_native_classes_in_sable_nested_jar");
        return Map.copyOf(classes);
    }
    private static InputStream required(Source source, String name) throws IOException {
        InputStream input = source.open(name);
        if (input == null) throw new FileNotFoundException(name);
        return input;
    }
    private static byte[] bounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - output.size()) throw new IOException("native_resource_size_limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
