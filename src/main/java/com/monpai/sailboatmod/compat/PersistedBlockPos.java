package com.monpai.sailboatmod.compat;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.route.terrain.TellusWorlds;
import com.monpai.sailboatmod.util.OfflineChunkNbtReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Persists explicit coordinates alongside the native long so saved records survive BlockPos encoding changes.
 * Old Tellus dense longs are recovered only after physical block-entity identity checks, never by coordinate size.
 */
public final class PersistedBlockPos {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TELLUS_XZ_SIZE = 40_075_018L;
    private static final long TELLUS_Y_SIZE = 11_472L;
    private static final long TELLUS_COLUMN_COUNT = TELLUS_XZ_SIZE * TELLUS_XZ_SIZE;
    private static final int TELLUS_XZ_MIN = -20_037_509;
    private static final long OFFLINE_READ_TIMEOUT_MS = 250L;

    private PersistedBlockPos() {
    }

    public static void write(CompoundTag tag, String key, BlockPos pos) {
        tag.putLong(key, pos.asLong());
        tag.putIntArray(key + "XYZ", new int[] {pos.getX(), pos.getY(), pos.getZ()});
    }

    public static BlockPos read(CompoundTag tag, String key) {
        if (hasPortableCoordinates(tag, key)) {
            int[] coordinates = tag.getIntArray(key + "XYZ");
            return new BlockPos(coordinates[0], coordinates[1], coordinates[2]);
        }
        return BlockPos.of(tag.getLong(key));
    }

    /** Original persisted facts retained until a position is verified or explicitly moved. */
    public record Evidence(long originalPacked, BlockPos loadedPosition, BlockPos portablePosition) {
    }

    /** Saves the literal portable coordinates separately from any lossy runtime long-codec round trip. */
    public static Evidence remember(CompoundTag source, String key, BlockPos loadedPosition) {
        BlockPos portable = hasPortableCoordinates(source, key) ? read(source, key) : null;
        return new Evidence(source.getLong(key), loadedPosition.immutable(), portable);
    }

    /** Unknown legacy coordinates stay unknown across saves; known portable literals keep their full range. */
    public static void writePreservingEvidence(CompoundTag target, String key, BlockPos current,
                                               Evidence evidence) {
        if (evidence != null && current.equals(evidence.loadedPosition())) {
            if (evidence.portablePosition() != null) {
                write(target, key, evidence.portablePosition());
            } else {
                target.putLong(key, evidence.originalPacked());
                target.remove(key + "XYZ");
            }
            return;
        }
        write(target, key, current);
    }

    /** Resolves a saved dimension without falling back to a different world. */
    public static ServerLevel forDimension(ServerLevel context, String dimensionId) {
        if (context == null || dimensionId == null || dimensionId.isBlank() || context.getServer() == null) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(dimensionId);
        return id == null ? null : context.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /**
     * Reads on the server thread without loading or generating chunks. Failed or timed-out world probes leave
     * the native position unchanged. This method never mutates the supplied saved-data tag.
     */
    public static BlockPos readVerified(ServerLevel level, CompoundTag tag, String key,
                                        String blockEntityId, String identityKey, String identityValue) {
        BlockPos current = read(tag, key);
        if (level == null || hasPortableCoordinates(tag, key) || !tag.contains(key, Tag.TAG_LONG)
                || !TellusWorlds.isTellusLevel(level)) {
            return current;
        }
        Map<ChunkPos, Optional<CompoundTag>> offlineChunks = new HashMap<>();
        BlockPos result;
        try {
            result = recoverVerified(current, decodeTellusDense(tag.getLong(key)), true,
                    blockEntityId, identityKey, identityValue,
                    pos -> readPhysicalEntity(level, pos, blockEntityId, offlineChunks),
                    pos -> !level.isOutsideBuildHeight(pos));
        } catch (UnverifiablePositionException ignored) {
            return current;
        }
        if (!result.equals(current)) {
            LOGGER.warn("Recovered legacy Tellus saved position '{}' in {} from {} to {} using verified {} {}={}",
                    key, level.dimension().location(), current, result, blockEntityId, identityKey, identityValue);
        }
        return result;
    }

    /** Pure verification entry point: empty means the probe succeeded and found no relevant block entity. */
    static BlockPos readVerified(CompoundTag tag, String key, boolean tellusLevel,
                                 String blockEntityId, String identityKey, String identityValue,
                                 Function<BlockPos, Optional<CompoundTag>> reader,
                                 Predicate<BlockPos> withinBuildHeight) {
        BlockPos current = read(tag, key);
        if (hasPortableCoordinates(tag, key) || !tag.contains(key, Tag.TAG_LONG)) {
            return current;
        }
        return recoverVerified(current, decodeTellusDense(tag.getLong(key)), tellusLevel,
                blockEntityId, identityKey, identityValue, reader, withinBuildHeight);
    }

    static BlockPos recoverVerified(BlockPos current, Optional<BlockPos> candidate, boolean tellusLevel,
                                    String blockEntityId, String identityKey, String identityValue,
                                    Function<BlockPos, Optional<CompoundTag>> reader,
                                    Predicate<BlockPos> withinBuildHeight) {
        if (!tellusLevel || candidate.isEmpty() || candidate.get().equals(current)
                || blockEntityId == null || blockEntityId.isBlank()
                || identityKey == null || identityKey.isBlank()
                || identityValue == null || identityValue.isBlank()) {
            return current;
        }
        BlockPos recovered = candidate.get();
        if (!withinBuildHeight.test(recovered)
                || !reader.apply(recovered).filter(tag -> matchesPhysicalEntity(
                        tag, recovered, blockEntityId, identityKey, identityValue)).isPresent()) {
            return current;
        }
        if (withinBuildHeight.test(current)
                && reader.apply(current).filter(tag -> matchesPhysicalTypeAndPosition(
                        tag, current, blockEntityId)).isPresent()) {
            // Ownership may have legitimately changed; an existing terminal/core must never be moved away.
            return current;
        }
        return recovered;
    }

    /** Tellus 0.8.3 dense format, decoded without linking any optional Tellus classes. */
    static Optional<BlockPos> decodeTellusDense(long raw) {
        long column = Long.divideUnsigned(raw, TELLUS_Y_SIZE);
        if (column >= TELLUS_COLUMN_COUNT) {
            return Optional.empty();
        }
        int x = (int) Long.remainderUnsigned(column, TELLUS_XZ_SIZE) + TELLUS_XZ_MIN;
        int z = (int) Long.divideUnsigned(column, TELLUS_XZ_SIZE) + TELLUS_XZ_MIN;
        int ySlot = (int) Long.remainderUnsigned(raw, TELLUS_Y_SIZE);
        int y = ySlot < 11_280 ? ySlot - 640 : ySlot - 11_280 - 832;
        return Optional.of(new BlockPos(x, y, z));
    }

    private static boolean hasPortableCoordinates(CompoundTag tag, String key) {
        return tag.contains(key + "XYZ", Tag.TAG_INT_ARRAY) && tag.getIntArray(key + "XYZ").length == 3;
    }

    private static boolean matchesPhysicalEntity(CompoundTag tag, BlockPos pos, String blockEntityId,
                                                  String identityKey, String identityValue) {
        return matchesPhysicalTypeAndPosition(tag, pos, blockEntityId)
                && tag.contains(identityKey, Tag.TAG_STRING) && identityValue.equals(tag.getString(identityKey));
    }

    private static boolean matchesPhysicalTypeAndPosition(CompoundTag tag, BlockPos pos, String blockEntityId) {
        return tag.contains("id", Tag.TAG_STRING) && blockEntityId.equals(tag.getString("id"))
                && tag.contains("x", Tag.TAG_INT) && tag.getInt("x") == pos.getX()
                && tag.contains("y", Tag.TAG_INT) && tag.getInt("y") == pos.getY()
                && tag.contains("z", Tag.TAG_INT) && tag.getInt("z") == pos.getZ();
    }

    private static Optional<CompoundTag> readPhysicalEntity(ServerLevel level, BlockPos pos, String blockEntityId,
                                                            Map<ChunkPos, Optional<CompoundTag>> offlineChunks) {
        if (level.isOutsideBuildHeight(pos)) {
            return Optional.empty();
        }
        ChunkPos chunkPos = new ChunkPos(pos);
        LevelChunk loaded = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (loaded != null) {
            try {
                BlockEntity entity = loaded.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                if (entity == null) {
                    return Optional.empty();
                }
                ResourceLocation actualType = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
                if (actualType == null || !blockEntityId.equals(actualType.toString())) {
                    return Optional.empty();
                }
                return Optional.of(entity.saveWithFullMetadata(level.registryAccess()));
            } catch (RuntimeException ignored) {
                throw new UnverifiablePositionException();
            }
        }
        Optional<CompoundTag> chunk = offlineChunks.computeIfAbsent(chunkPos,
                key -> OfflineChunkNbtReader.readSafeOnMainThread(level, key, OFFLINE_READ_TIMEOUT_MS, false));
        if (chunk.isEmpty()) {
            // An absent disk result may also mean IO failed or timed out; do not infer physical absence.
            throw new UnverifiablePositionException();
        }
        ListTag entities = chunk.get().getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entity = entities.getCompound(i);
            if (matchesPhysicalTypeAndPosition(entity, pos, blockEntityId)) {
                return Optional.of(entity);
            }
        }
        return Optional.empty();
    }

    /** Internal control flow for an unknown world state; no stack trace is needed for a skipped migration. */
    private static final class UnverifiablePositionException extends RuntimeException {
        private UnverifiablePositionException() {
            super(null, null, false, false);
        }
    }
}
