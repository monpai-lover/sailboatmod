package com.monpai.sailboatmod.client.integration.xaero;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class SailboatXaeroCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String HIGHLIGHTER_CLASS_PREFIX = "com.monpai.sailboatmod.client.integration.xaero.SailboatXaero";

    private static boolean detected;
    private static boolean minimapLoaded;
    private static boolean worldMapLoaded;
    private static boolean xaeroPlusLoaded;
    private static boolean loggedPresence;
    private static boolean warnedMinimapFailure;
    private static boolean warnedWorldMapFailure;
    private static int tickCounter;

    private static Object registeredMinimapRegistry;
    private static Object registeredMinimapWriter;
    private static Object registeredWorldMapRegistry;
    private static Object registeredWorldMapProcessor;

    public static void onClientSetup() {
        detectLoadedMods();
        tryRegister();
    }

    public static void onClientTickEnd() {
        if (!isCompatRelevant()) {
            return;
        }
        tickCounter++;
        if ((tickCounter % 40) == 0) {
            tryRegister();
        }
    }

    public static void onHighlightDataUpdated() {
        if (!isCompatRelevant()) {
            return;
        }
        tryRegister();
        refreshXaeroCaches();
    }

    public static boolean isCompatRelevant() {
        detectLoadedMods();
        return minimapLoaded || worldMapLoaded || xaeroPlusLoaded;
    }

    static boolean shouldEnable(boolean minimapLoaded, boolean worldMapLoaded, boolean xaeroPlusLoaded) {
        return minimapLoaded || worldMapLoaded || xaeroPlusLoaded;
    }

    private static void detectLoadedMods() {
        if (detected) {
            return;
        }
        ModList modList = ModList.get();
        minimapLoaded = modList.isLoaded("xaerominimap");
        worldMapLoaded = modList.isLoaded("xaeroworldmap");
        xaeroPlusLoaded = modList.isLoaded("xaeroplus");
        detected = true;
        if (!loggedPresence && shouldEnable(minimapLoaded, worldMapLoaded, xaeroPlusLoaded)) {
            LOGGER.info(
                    "Sailboat Xaero compatibility detected: minimap={}, worldmap={}, xaeroplus={}",
                    minimapLoaded,
                    worldMapLoaded,
                    xaeroPlusLoaded
            );
            loggedPresence = true;
        }
    }

    private static void tryRegister() {
        if (minimapLoaded) {
            tryRegisterMinimap();
        }
        if (worldMapLoaded) {
            tryRegisterWorldMap();
        }
    }

    private static void tryRegisterMinimap() {
        try {
            Object writer = findMinimapWriter();
            if (writer == null) {
                return;
            }
            Object registry = getField(writer, "highlighterRegistry");
            if (registry == null || registry == registeredMinimapRegistry) {
                return;
            }
            if (installHighlighter(registry, SailboatXaeroRuntimeHighlighterFactory::createMinimapHighlighter)) {
                registeredMinimapRegistry = registry;
                registeredMinimapWriter = writer;
                refreshMinimap();
                LOGGER.info("Registered Sailboat claim highlights with Xaero Minimap");
            }
        } catch (Throwable throwable) {
            if (!warnedMinimapFailure) {
                LOGGER.warn("Failed to register Sailboat claim highlights with Xaero Minimap", throwable);
                warnedMinimapFailure = true;
            }
        }
    }

    private static void tryRegisterWorldMap() {
        try {
            Object session = invokeStatic("xaero.map.WorldMapSession", "getCurrentSession");
            if (session == null) {
                return;
            }
            Object processor = invoke(session, "getMapProcessor");
            if (processor == null) {
                return;
            }
            Object registry = invoke(processor, "getHighlighterRegistry");
            if (registry == null || registry == registeredWorldMapRegistry) {
                return;
            }
            if (installHighlighter(registry, SailboatXaeroRuntimeHighlighterFactory::createWorldMapHighlighter)) {
                registeredWorldMapRegistry = registry;
                registeredWorldMapProcessor = processor;
                refreshWorldMap();
                LOGGER.info("Registered Sailboat claim highlights with Xaero World Map");
            }
        } catch (Throwable throwable) {
            if (!warnedWorldMapFailure) {
                LOGGER.warn("Failed to register Sailboat claim highlights with Xaero World Map", throwable);
                warnedWorldMapFailure = true;
            }
        }
    }

    private static Object findMinimapWriter() throws ReflectiveOperationException {
        Object hudMod = getStaticField("xaero.common.HudMod", "INSTANCE");
        if (hudMod == null) {
            return null;
        }
        Object hud = invoke(hudMod, "getHud");
        if (hud == null) {
            return null;
        }
        Object moduleManager = invoke(hud, "getModuleManager");
        if (moduleManager == null) {
            return null;
        }
        Object modules = invoke(moduleManager, "getModules");
        if (!(modules instanceof Iterable<?> iterable)) {
            return null;
        }
        for (Object module : iterable) {
            Object session = invoke(module, "getCurrentSession");
            if (session == null) {
                continue;
            }
            Object processor = invoke(session, "getProcessor");
            if (processor == null) {
                continue;
            }
            Object writer = invoke(processor, "getMinimapWriter");
            if (writer != null) {
                return writer;
            }
        }
        return null;
    }

    private static boolean installHighlighter(Object registry, HighlighterSupplier supplier) throws ReflectiveOperationException {
        List<?> current = getHighlighters(registry);
        if (containsSailboatHighlighter(current)) {
            return true;
        }
        Object highlighter = supplier.get();
        try {
            Method register = findMethod(registry.getClass(), "register", 1);
            register.invoke(registry, highlighter);
            return true;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (!(cause instanceof UnsupportedOperationException)) {
                throw exception;
            }
        }

        Field field = findField(registry.getClass(), "highlighters");
        field.setAccessible(true);
        List<Object> replacement = new ArrayList<>(current);
        replacement.add(highlighter);
        field.set(registry, replacement);
        return true;
    }

    private static List<?> getHighlighters(Object registry) throws ReflectiveOperationException {
        Object highlighters = invoke(registry, "getHighlighters");
        return highlighters instanceof List<?> list ? list : List.of();
    }

    private static boolean containsSailboatHighlighter(List<?> highlighters) {
        for (Object highlighter : highlighters) {
            if (highlighter != null && highlighter.getClass().getName().startsWith(HIGHLIGHTER_CLASS_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private static void refreshXaeroCaches() {
        refreshMinimap();
        refreshWorldMap();
    }

    private static void refreshMinimap() {
        if (registeredMinimapWriter == null) {
            return;
        }
        try {
            Object handler = invoke(registeredMinimapWriter, "getDimensionHighlightHandler");
            if (handler != null) {
                invoke(handler, "requestRefresh");
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void refreshWorldMap() {
        if (registeredWorldMapProcessor == null) {
            return;
        }
        try {
            Object mapWorld = invoke(registeredWorldMapProcessor, "getMapWorld");
            if (mapWorld != null) {
                invoke(mapWorld, "clearAllCachedHighlightHashes");
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Object invokeStatic(String className, String methodName) throws ReflectiveOperationException {
        Class<?> targetClass = Class.forName(className);
        Method method = findMethod(targetClass, methodName, 0);
        return method.invoke(null);
    }

    private static Object getStaticField(String className, String fieldName) throws ReflectiveOperationException {
        Class<?> targetClass = Class.forName(className);
        Field field = findField(targetClass, fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Object getField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invoke(Object target, String methodName, Object... args) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = findMethod(target.getClass(), methodName, args.length);
        return method.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + "/" + parameterCount);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }

    private SailboatXaeroCompat() {
    }

    @FunctionalInterface
    private interface HighlighterSupplier {
        Object get() throws ReflectiveOperationException;
    }
}
