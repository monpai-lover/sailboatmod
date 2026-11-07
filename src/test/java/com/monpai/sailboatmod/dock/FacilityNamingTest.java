package com.monpai.sailboatmod.dock;

import com.monpai.sailboatmod.block.entity.*;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;

/** Reads the actual terminal getters against live SavedData, rather than copying a town name into a fixture. */
class FacilityNamingTest {
    @TempDir Path temporaryDirectory;
    private TestLevel level;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @BeforeEach void fixture() throws Exception {
        level = allocate(TestLevel.class);
        level.server = allocate(DedicatedServer.class);
        set(MinecraftServer.class, level.server, "levels", Map.of(Level.OVERWORLD, level));
        level.storage = new DimensionDataStorage(temporaryDirectory.toFile(), null, level.registryAccess());
        renameTown("Harbor Town");
    }
    @Test void landStationDefaultFollowsTheCurrentTownName() throws Exception { followsTown(PostStationBlockEntity.class); }
    @Test void airportDefaultFollowsTheCurrentTownName() throws Exception { followsTown(AirportBlockEntity.class); }
    @Test void railTerminalDefaultFollowsTheCurrentTownName() throws Exception { followsTown(RailFreightTerminalBlockEntity.class); }
    @Test void explicitNamesRemainOverridesAndClearingRestoresFollowing() throws Exception {
        for (var type : types()) {
            DockBlockEntity terminal = terminal(type);
            set(DockBlockEntity.class, terminal, "dockName", "East Terminal");
            renameTown("Renamed Town");
            assertEquals("East Terminal", terminal.getDockName());
            set(DockBlockEntity.class, terminal, "dockName", "");
            assertEquals("Renamed Town", terminal.getDockName());
        }
    }
    @Test void legacyPortsKeepTheirExistingOwnerFallback() throws Exception {
        assertEquals("Owner's Dock", terminal(DockBlockEntity.class).getDockName());
    }
    private void followsTown(Class<? extends DockBlockEntity> type) throws Exception {
        DockBlockEntity terminal = terminal(type);
        assertEquals("Harbor Town", terminal.getDockName());
        renameTown("Renamed Town");
        assertEquals("Renamed Town", terminal.getDockName(), "Default names must not freeze a copied town name");
    }
    private static List<Class<? extends DockBlockEntity>> types() {
        return List.of(PostStationBlockEntity.class, AirportBlockEntity.class, RailFreightTerminalBlockEntity.class);
    }
    private DockBlockEntity terminal(Class<? extends DockBlockEntity> type) throws Exception {
        DockBlockEntity terminal = allocate(type);
        set(BlockEntity.class, terminal, "worldPosition", new BlockPos(8, 64, 8));
        set(BlockEntity.class, terminal, "blockState", Blocks.AIR.defaultBlockState());
        set(DockBlockEntity.class, terminal, "townId", "town-a");
        set(DockBlockEntity.class, terminal, "ownerName", "Owner");
        set(DockBlockEntity.class, terminal, "dockName", "");
        terminal.setLevel(level);
        return terminal;
    }
    private void renameTown(String name) {
        NationSavedData.get(level).putTown(new TownRecord("town-a", "nation-a", name, new UUID(0, 1), 1,
                "minecraft:overworld", BlockPos.ZERO.asLong(), "", "european"));
    }
    private static final class TestLevel extends ServerLevel {
        DedicatedServer server; DimensionDataStorage storage;
        private TestLevel() { super(null, command -> {}, null, null, null, null, null, false, 0L, List.of(), false, null); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public DimensionDataStorage getDataStorage() { return storage; }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY); }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }
    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
