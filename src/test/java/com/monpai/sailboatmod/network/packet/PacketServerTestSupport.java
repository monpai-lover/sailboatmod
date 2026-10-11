package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeIds;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class PacketServerTestSupport {
    private PacketServerTestSupport() { }

    static TestPlayer player(Path dataDirectory, String nationId) throws ReflectiveOperationException {
        TestLevel level = allocate(TestLevel.class);
        level.storage = new DimensionDataStorage(dataDirectory.toFile(), null, null);
        level.server = allocate(DedicatedServer.class);
        Field levels = MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(level.server, Map.of(Level.OVERWORLD, level));
        TestPlayer player = allocate(TestPlayer.class);
        player.testLevel = level;
        player.profile = new GameProfile(UUID.randomUUID(), "Treasurer");
        player.inventory = new Inventory(player);
        player.abilities = new Abilities();
        player.containerMenu = new AbstractContainerMenu(null, 0) {
            @Override public ItemStack quickMoveStack(Player owner, int index) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(Player owner) { return true; }
        };
        NationSavedData data = NationSavedData.get(level);
        data.putMember(new NationMemberRecord(player.getUUID(), "Treasurer", nationId, NationOfficeIds.LEADER, 0L));
        return player;
    }

    static void town(TestPlayer player, String nationId, BlockPos pos) {
        NationSavedData.get(player.level()).putTown(new TownRecord(
                nationId + "_town", nationId, "Test Town", UUID.randomUUID(), 0L,
                "minecraft:overworld", pos.asLong(), "", "european"));
    }

    static NetworkEvent.Context context(TestPlayer player) throws ReflectiveOperationException {
        IPayloadContext payload = (IPayloadContext) Proxy.newProxyInstance(
                IPayloadContext.class.getClassLoader(), new Class<?>[]{IPayloadContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "player" -> player;
                    case "enqueueWork" -> {
                        ((Runnable) args[0]).run();
                        yield CompletableFuture.completedFuture(null);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var constructor = NetworkEvent.Context.class.getDeclaredConstructor(IPayloadContext.class);
        constructor.setAccessible(true);
        return constructor.newInstance(payload);
    }

    @SuppressWarnings("unchecked")
    static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((Unsafe) field.get(null)).allocateInstance(type);
    }

    static final class TestLevel extends ServerLevel {
        private DedicatedServer server;
        private DimensionDataStorage storage;
        Map<BlockPos, BlockEntity> blockEntities = Map.of();

        private TestLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override public MinecraftServer getServer() { return server; }
        @Override public DimensionDataStorage getDataStorage() { return storage; }
        @Override public net.minecraft.resources.ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return blockEntities == null ? null : blockEntities.get(pos); }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.STONE.defaultBlockState(); }
    }

    static final class TestPlayer extends ServerPlayer {
        private TestLevel testLevel;
        private GameProfile profile;
        private Inventory inventory;
        private Abilities abilities;
        Component lastMessage;
        Consumer<Component> onSystemMessage;
        boolean operator;

        private TestPlayer() { super(null, null, null, null); }

        @Override public Level level() { return testLevel; }
        @Override public UUID getUUID() { return profile.getId(); }
        @Override public GameProfile getGameProfile() { return profile; }
        @Override public Component getName() { return Component.literal(profile.getName()); }
        @Override public Inventory getInventory() { return inventory; }
        @Override public Abilities getAbilities() { return abilities; }
        @Override public boolean hasInfiniteMaterials() { return false; }
        @Override public boolean hasPermissions(int level) { return operator; }
        @Override public void sendSystemMessage(Component message) {
            lastMessage = message;
            if (onSystemMessage != null) onSystemMessage.accept(message);
        }
    }
}
