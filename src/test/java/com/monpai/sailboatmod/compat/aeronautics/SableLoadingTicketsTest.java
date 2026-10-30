package com.monpai.sailboatmod.compat.aeronautics;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SableLoadingTicketsTest {
    private static final String BRIDGE = SableLoadingTickets.class.getName();
    private static final String API = "dev.ryanhcode.sable.api.sublevel.";
    private static final String BODY = "dev.ryanhcode.sable.sublevel.";
    private static final String CLIENT = "net.minecraft.client.multiplayer.ClientLevel";
    @TempDir static Path classes;

    // Compile only API shapes. The code under test is always the real compiled bridge, including its Access record.
    @BeforeAll
    static void compileApiShapes() throws IOException {
        Map<String, String> sources = Map.ofEntries(
                Map.entry("com.mojang.serialization.Codec", "public class Codec<T> {}"),
                Map.entry("net.minecraft.core.UUIDUtil", "public class UUIDUtil { public static final com.mojang.serialization.Codec<java.util.UUID> CODEC = new com.mojang.serialization.Codec<>(); }"),
                Map.entry("net.minecraft.resources.ResourceLocation", """
                        public record ResourceLocation(String namespace, String path) {
                            public static ResourceLocation fromNamespaceAndPath(String namespace, String path) {
                                return new ResourceLocation(namespace, path);
                            }
                        }
                        """),
                Map.entry("net.minecraft.server.level.ServerLevel", "public class ServerLevel {}"),
                Map.entry(CLIENT, "public class ClientLevel {}"),
                Map.entry(BODY + "SubLevel", "public class SubLevel { public java.util.UUID id; }"),
                Map.entry(BODY + "ServerSubLevel", "public class ServerSubLevel extends SubLevel { public ServerSubLevel(java.util.UUID id) { this.id = id; } }"),
                Map.entry(API + "ClientSubLevelContainer", "public class ClientSubLevelContainer extends SubLevelContainer {}"),
                Map.entry(API + "SubLevelContainer", """
                        public class SubLevelContainer {
                            public static final ServerSubLevelContainer INSTANCE = new ServerSubLevelContainer();
                            public final Map<UUID, SubLevel> bodies = new HashMap<>();
                            public static ServerSubLevelContainer getContainer(net.minecraft.server.level.ServerLevel level) { return INSTANCE; }
                            public static ClientSubLevelContainer getContainer(net.minecraft.client.multiplayer.ClientLevel level) { return null; }
                            public SubLevel getSubLevel(UUID id) { return bodies.get(id); }
                        }
                        """),
                Map.entry(API + "ServerSubLevelContainer", """
                        public class ServerSubLevelContainer extends SubLevelContainer {
                            public final Map<ServerSubLevel, Set<SubLevelLoadingTicket<?>>> tickets = new HashMap<>();
                            public <T> boolean addForceLoadTicket(ServerSubLevel body, SubLevelLoadingTicketType<T> type, T key) {
                                return tickets.computeIfAbsent(body, ignored -> new HashSet<>()).add(new SubLevelLoadingTicket<>(type, body.id, key));
                            }
                            public <T> boolean removeForceLoadTicket(ServerSubLevel body, SubLevelLoadingTicketType<T> type, T key) {
                                Set<SubLevelLoadingTicket<?>> values = tickets.get(body);
                                if (values == null) return false;
                                boolean removed = values.remove(new SubLevelLoadingTicket<>(type, body.id, key));
                                if (values.isEmpty()) tickets.remove(body);
                                return removed;
                            }
                            public Map<ServerSubLevel, Set<SubLevelLoadingTicket<?>>> collectForceLoadTickets() {
                                return Collections.unmodifiableMap(tickets);
                            }
                        }
                        """),
                Map.entry(API + "ticket.SubLevelLoadingTicketType", """
                        public record SubLevelLoadingTicketType<T>(net.minecraft.resources.ResourceLocation name, com.mojang.serialization.Codec<T> codec) {
                            public static SubLevelLoadingTicketType<?> registered;
                            public static <T> SubLevelLoadingTicketType<T> create(net.minecraft.resources.ResourceLocation name, com.mojang.serialization.Codec<T> codec) {
                                SubLevelLoadingTicketType<T> type = new SubLevelLoadingTicketType<>(name, codec);
                                registered = type;
                                return type;
                            }
                        }
                        """),
                Map.entry(API + "ticket.SubLevelLoadingTicket", """
                        public record SubLevelLoadingTicket<T>(SubLevelLoadingTicketType<T> type, UUID subLevelId, T key) {}
                        """));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Regression tests require the project's JDK toolchain");
        var units = sources.entrySet().stream().map(entry -> new SimpleJavaFileObject(
                URI.create("string:///" + entry.getKey().replace('.', '/') + ".java"), SimpleJavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                String pkg = entry.getKey().substring(0, entry.getKey().lastIndexOf('.'));
                return "package " + pkg + "; import java.util.*; import " + BODY + "*; import " + API + "ticket.*;\n" + entry.getValue();
            }
        }).toList();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, files, null,
                    List.of("-proc:none", "-classpath", "", "-d", classes.toString()), null, units).call());
        }
    }

    @Test
    void initializeNeverResolvesTheClientOnlyOverload() throws Exception {
        try (var fixture = new Fixture(true)) {
            assertDoesNotThrow(fixture::initialize);
            UUID physical = UUID.randomUUID();
            fixture.addBody(physical);
            assertTrue(fixture.update(physical, UUID.randomUUID(), true), "Initialization must bind usable tickets, not silently disable Sable");
            assertEquals(0, fixture.clientLoads, "Even a caught client-side class loading attempt is a regression");
        }
    }

    @Test
    void retainAndRemoveAreIdempotentAndUnknownBodiesFail() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.initialize();
            UUID physical = UUID.randomUUID(), registration = UUID.randomUUID();
            fixture.addBody(physical);
            assertTrue(fixture.update(physical, registration, true));
            assertTrue(fixture.update(physical, registration, true));
            assertEquals(1, fixture.tickets().values().iterator().next().size());
            assertTrue(fixture.update(physical, registration, false));
            assertTrue(fixture.update(physical, registration, false));
            assertTrue(fixture.tickets().isEmpty());
            assertFalse(fixture.update(UUID.randomUUID(), registration, true));
            assertEquals(0, fixture.clientLoads);
        }
    }

    @Test
    void reconcileRemovesOnlyStaleKeysOfOurTicketType() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.initialize();
            UUID physical = UUID.randomUUID(), otherPhysical = UUID.randomUUID();
            UUID keep = UUID.randomUUID(), stale = UUID.randomUUID();
            Object body = fixture.addBody(physical);
            fixture.addBody(otherPhysical);
            assertTrue(fixture.update(physical, keep, true));
            assertTrue(fixture.update(physical, stale, true));
            assertTrue(fixture.update(otherPhysical, stale, true));
            Set<Object> tickets = fixture.tickets().get(body);
            Object ownKept = tickets.stream().filter(ticket -> {
                try { return keep.equals(ticket.getClass().getMethod("key").invoke(ticket)); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }).findFirst().orElseThrow();
            Object player = fixture.foreignTicket("sable", "command_forced", physical, "player");
            Object otherMod = fixture.foreignTicket("other_mod", "ticket", physical, stale);
            tickets.add(player);
            tickets.add(otherMod);

            fixture.reconcile(Map.of(physical, Set.of(keep)));

            assertEquals(Map.of(body, Set.of(ownKept, player, otherMod)), fixture.tickets());
            assertEquals(0, fixture.clientLoads);
        }
    }

    @Test
    void absentSableDoesNotBreakOrdinaryWorlds() throws Exception {
        try (var fixture = new Fixture(false)) {
            assertDoesNotThrow(fixture::initialize);
            assertFalse(fixture.update(UUID.randomUUID(), UUID.randomUUID(), true));
            assertDoesNotThrow(() -> fixture.reconcile(Map.of()));
            assertEquals(0, fixture.clientLoads);
        }
    }

    private static final class Fixture extends URLClassLoader {
        private final boolean sablePresent;
        private final Class<?> bridge, levelType;
        private int clientLoads;

        Fixture(boolean sablePresent) throws Exception {
            super(new URL[]{classes.toUri().toURL()}, SableLoadingTicketsTest.class.getClassLoader());
            this.sablePresent = sablePresent;
            bridge = loadClass(BRIDGE);
            levelType = loadClass("net.minecraft.server.level.ServerLevel");
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.equals(CLIENT)) {
                    clientLoads++;
                    // NeoForge's RuntimeDistCleaner throws RuntimeException, not ClassNotFoundException.
                    throw new RuntimeException("Attempted to load class " + name + " for invalid dist DEDICATED_SERVER");
                }
                if (name.startsWith("dev.ryanhcode.sable.") && !sablePresent) throw new ClassNotFoundException(name);
                boolean realBridge = name.equals(BRIDGE) || name.startsWith(BRIDGE + "$");
                boolean fakeApi = name.startsWith("dev.ryanhcode.sable.") || name.startsWith("net.minecraft.")
                        || name.equals("com.mojang.serialization.Codec");
                if (!realBridge && !fakeApi) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && realBridge) {
                    try (var stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (stream == null) throw new ClassNotFoundException(name);
                        byte[] bytes = stream.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                }
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        void initialize() throws ReflectiveOperationException { bridge.getMethod("initialize").invoke(null); }
        boolean update(UUID physical, UUID registration, boolean retain) throws ReflectiveOperationException {
            return (boolean) bridge.getMethod("update", levelType, UUID.class, UUID.class, boolean.class)
                    .invoke(null, levelType.getConstructor().newInstance(), physical, registration, retain);
        }
        void reconcile(Map<UUID, Set<UUID>> expected) throws ReflectiveOperationException {
            bridge.getMethod("reconcileOwned", levelType, Map.class)
                    .invoke(null, levelType.getConstructor().newInstance(), expected);
        }
        Object container() throws ReflectiveOperationException {
            return loadClass(API + "SubLevelContainer").getField("INSTANCE").get(null);
        }
        @SuppressWarnings("unchecked")
        Object addBody(UUID physical) throws ReflectiveOperationException {
            Object body = loadClass(BODY + "ServerSubLevel").getConstructor(UUID.class).newInstance(physical);
            ((Map<UUID, Object>) container().getClass().getField("bodies").get(container())).put(physical, body);
            return body;
        }
        @SuppressWarnings("unchecked")
        Map<Object, Set<Object>> tickets() throws ReflectiveOperationException {
            return (Map<Object, Set<Object>>) container().getClass().getField("tickets").get(container());
        }
        Object foreignTicket(String namespace, String path, UUID physical, Object key) throws ReflectiveOperationException {
            Class<?> location = loadClass("net.minecraft.resources.ResourceLocation");
            Class<?> type = loadClass(API + "ticket.SubLevelLoadingTicketType");
            Object foreign = type.getConstructor(location, loadClass("com.mojang.serialization.Codec"))
                    .newInstance(location.getConstructor(String.class, String.class).newInstance(namespace, path), null);
            return loadClass(API + "ticket.SubLevelLoadingTicket").getConstructor(type, UUID.class, Object.class)
                    .newInstance(foreign, physical, key);
        }
    }
}
