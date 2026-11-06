package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.AirportScreen;
import com.monpai.sailboatmod.transport.air.airport.AirportGroundLayout;
import com.monpai.sailboatmod.transport.air.airport.AirportScreenData;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.*;

class AirportPickerLifecycleTest {
    @AfterEach void clearPicker() throws Exception {
        set("pickingScreen", null);
        set("selected", null);
        set("cachedScreen", null);
        set("previewScreen", null);
        set("levelIdentity", null);
    }

    @Test void escapeRestoresPickerDraftBeforeVanillaCanOpenPauseScreen() throws Exception {
        var screen = new AirportScreen(new AirportScreenData(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO, "Airport", "Town", "Owner", true,
                0, List.of(), AirportGroundLayout.empty(), List.of(), ""));
        set("pickingScreen", screen);
        set("selected", (Consumer<Object>) ignored -> fail("Escape must not select a point"));
        var opening = new ScreenEvent.Opening(null, new PauseScreen(true));

        var listener = AirportClientHooks.class.getDeclaredMethod("onScreenOpening", ScreenEvent.Opening.class);
        listener.invoke(null, opening);

        assertSame(screen, opening.getNewScreen());
        assertNull(get("pickingScreen"));
        assertNull(get("selected"));
    }

    @Test void unloadingTheActualClientLevelInvalidatesPickerDraftCacheAndPreview() throws Exception {
        var level = allocate(ClientLevel.class);
        var screen = screen();
        set("levelIdentity", level);
        set("pickingScreen", screen);
        set("cachedScreen", screen);
        set("previewScreen", screen);
        var listener = AirportClientHooks.class.getDeclaredMethod("onLevelUnload", LevelEvent.Unload.class);

        listener.invoke(null, new LevelEvent.Unload(level));

        assertNull(get("pickingScreen"));
        assertNull(get("cachedScreen"));
        assertNull(get("previewScreen"));
        assertNull(get("levelIdentity"));
    }

    @Test void aDifferentClientLevelObjectInvalidatesSameDimensionPickerState() throws Exception {
        var first = allocate(ClientLevel.class);
        var second = allocate(ClientLevel.class);
        var screen = screen();
        set("levelIdentity", first);
        set("pickingScreen", screen);
        set("cachedScreen", screen);
        var observe = AirportClientHooks.class.getDeclaredMethod("observeLevel", net.minecraft.world.level.Level.class);
        observe.setAccessible(true);

        observe.invoke(null, second);

        assertNull(get("pickingScreen"));
        assertNull(get("cachedScreen"));
        assertSame(second, get("levelIdentity"));
    }

    private static AirportScreen screen() {
        return new AirportScreen(new AirportScreenData(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO,
                "Airport", "Town", "Owner", true, 0, List.of(), AirportGroundLayout.empty(), List.of(), ""));
    }

    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((Unsafe)field.get(null)).allocateInstance(type);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = AirportClientHooks.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
    private static Object get(String name) throws Exception {
        Field field = AirportClientHooks.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
