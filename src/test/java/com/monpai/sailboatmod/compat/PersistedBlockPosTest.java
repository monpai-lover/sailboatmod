package com.monpai.sailboatmod.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistedBlockPosTest {
    private static final String KEY = "CorePos";
    private static final String BLOCK_ENTITY_ID = "sailboatmod:nation_core";
    private static final String IDENTITY_KEY = "NationId";
    private static final String IDENTITY_VALUE = "verified-nation";
    private static final long TELLUS_RAW = 9189697286124907378L;
    private static final BlockPos TRUE_POS = new BlockPos(28110, 66, -48635);
    private static final BlockPos VANILLA_POS = new BlockPos(33431923, -1166, -1978998);

    @Test
    void writesLegacyLongAndPortableCoordinates() {
        CompoundTag tag = new CompoundTag();

        PersistedBlockPos.write(tag, KEY, TRUE_POS);

        assertEquals(TRUE_POS.asLong(), tag.getLong(KEY));
        assertArrayEquals(new int[] {28110, 66, -48635}, tag.getIntArray(KEY + "XYZ"));
        assertEquals(TRUE_POS, PersistedBlockPos.read(tag, KEY));
    }

    @Test
    void unknownLegacyReadThenSavePreservesRawWithoutAuthoritativeCoordinates() {
        CompoundTag source = legacyTag(TELLUS_RAW);
        BlockPos loaded = PersistedBlockPos.read(source, KEY);
        PersistedBlockPos.Evidence evidence = PersistedBlockPos.remember(source, KEY, loaded);
        CompoundTag saved = new CompoundTag();
        saved.putIntArray(KEY + "XYZ", new int[] {1, 2, 3});

        PersistedBlockPos.writePreservingEvidence(saved, KEY, loaded, evidence);

        assertEquals(TELLUS_RAW, saved.getLong(KEY));
        assertFalse(saved.contains(KEY + "XYZ"));
        assertEquals(loaded, evidence.loadedPosition());
        assertNull(evidence.portablePosition());
    }

    @Test
    void existingPortableHighYSurvivesWrappedRuntimePositionOnSave() {
        BlockPos portable = new BlockPos(-20037509, 10639, 20037508);
        CompoundTag source = new CompoundTag();
        PersistedBlockPos.write(source, KEY, portable);
        BlockPos wrappedRuntime = BlockPos.of(source.getLong(KEY));
        assertEquals(-1649, wrappedRuntime.getY());
        PersistedBlockPos.Evidence evidence = PersistedBlockPos.remember(source, KEY, wrappedRuntime);
        CompoundTag saved = new CompoundTag();

        PersistedBlockPos.writePreservingEvidence(saved, KEY, wrappedRuntime, evidence);

        assertArrayEquals(new int[] {-20037509, 10639, 20037508}, saved.getIntArray(KEY + "XYZ"));
        assertEquals(portable, PersistedBlockPos.read(saved, KEY));
        assertEquals(portable, evidence.portablePosition());
    }

    @Test
    void explicitMovementReplacesRememberedPortablePosition() {
        CompoundTag source = new CompoundTag();
        PersistedBlockPos.write(source, KEY, TRUE_POS);
        PersistedBlockPos.Evidence evidence = PersistedBlockPos.remember(source, KEY, TRUE_POS);
        BlockPos moved = TRUE_POS.offset(12, 4, -8);
        CompoundTag saved = new CompoundTag();

        PersistedBlockPos.writePreservingEvidence(saved, KEY, moved, evidence);

        assertEquals(moved.asLong(), saved.getLong(KEY));
        assertArrayEquals(new int[] {moved.getX(), moved.getY(), moved.getZ()}, saved.getIntArray(KEY + "XYZ"));
    }

    @Test
    void newlyKnownPositionWithoutEvidenceWritesPortableCoordinates() {
        CompoundTag saved = new CompoundTag();

        PersistedBlockPos.writePreservingEvidence(saved, KEY, TRUE_POS, null);

        assertEquals(TRUE_POS.asLong(), saved.getLong(KEY));
        assertArrayEquals(new int[] {28110, 66, -48635}, saved.getIntArray(KEY + "XYZ"));
    }

    @Test
    void verifiedRecoveryCanBeRememberedAsAuthoritativePortableEvidence() {
        CompoundTag legacy = legacyTag(TELLUS_RAW);
        BlockPos recovered = verified(legacy, true, pos -> pos.equals(TRUE_POS)
                ? Optional.of(physicalEntity(pos)) : Optional.empty());
        CompoundTag recoveredTag = legacy.copy();
        PersistedBlockPos.write(recoveredTag, KEY, recovered);
        PersistedBlockPos.Evidence evidence = PersistedBlockPos.remember(recoveredTag, KEY, recovered);
        CompoundTag saved = new CompoundTag();

        PersistedBlockPos.writePreservingEvidence(saved, KEY, recovered, evidence);

        assertEquals(TRUE_POS, evidence.portablePosition());
        assertEquals(recovered.asLong(), evidence.originalPacked());
        assertArrayEquals(new int[] {28110, 66, -48635}, saved.getIntArray(KEY + "XYZ"));
    }

    @Test
    void malformedPortableEvidenceRemainsUnknownOnSave() {
        CompoundTag source = legacyTag(TELLUS_RAW);
        source.putIntArray(KEY + "XYZ", new int[] {28110, 66});
        BlockPos loaded = PersistedBlockPos.read(source, KEY);
        PersistedBlockPos.Evidence evidence = PersistedBlockPos.remember(source, KEY, loaded);
        CompoundTag saved = new CompoundTag();

        PersistedBlockPos.writePreservingEvidence(saved, KEY, loaded, evidence);

        assertEquals(TELLUS_RAW, saved.getLong(KEY));
        assertFalse(saved.contains(KEY + "XYZ"));
        assertNull(evidence.portablePosition());
    }

    @Test
    void explicitCoordinatesTakePriorityOverDenseLongWithoutWorldProbe() {
        CompoundTag tag = legacyTag(TELLUS_RAW);
        tag.putIntArray(KEY + "XYZ", new int[] {28110, 66, -48635});

        assertEquals(TRUE_POS, PersistedBlockPos.read(tag, KEY));
        assertEquals(TRUE_POS, verified(tag, true, pos -> {
            throw new AssertionError("Portable positions must not probe the world");
        }));
    }

    @Test
    void explicitIntCoordinatesPreserveTellusVerticalRange() {
        CompoundTag tag = legacyTag(0L);
        tag.putIntArray(KEY + "XYZ", new int[] {-20037509, 10639, 20037508});

        assertEquals(new BlockPos(-20037509, 10639, 20037508), PersistedBlockPos.read(tag, KEY));
    }

    @Test
    void malformedCoordinateArraysFallBackToNativeCodec() {
        for (int[] coordinates : new int[][] {{}, {1, 2}, {1, 2, 3, 4}}) {
            CompoundTag tag = legacyTag(TELLUS_RAW);
            tag.putIntArray(KEY + "XYZ", coordinates);
            assertEquals(VANILLA_POS, PersistedBlockPos.read(tag, KEY));
        }
        CompoundTag tag = legacyTag(TELLUS_RAW);
        tag.putLongArray(KEY + "XYZ", new long[] {28110, 66, -48635});
        assertEquals(VANILLA_POS, PersistedBlockPos.read(tag, KEY));
    }

    @Test
    void ordinaryLegacyPositionsUseNativeCodec() {
        BlockPos nativePos = new BlockPos(-120, 75, 900);

        assertEquals(nativePos, PersistedBlockPos.read(legacyTag(nativePos.asLong()), KEY));
        assertEquals(VANILLA_POS, PersistedBlockPos.read(legacyTag(TELLUS_RAW), KEY));
    }

    @Test
    void decodesTheProvenTellusDenseLong() {
        assertEquals(Optional.of(TRUE_POS), PersistedBlockPos.decodeTellusDense(TELLUS_RAW));
    }

    @Test
    void denseDecodeUsesUnsignedDivisionForNegativeLongs() {
        assertEquals(Optional.of(new BlockPos(11405886, 9712, 24612)),
                PersistedBlockPos.decodeTellusDense(Long.MIN_VALUE));
        assertEquals(Optional.of(new BlockPos(20037508, -641, 20037508)),
                PersistedBlockPos.decodeTellusDense(-22630993051434689L));
    }

    @Test
    void denseDecodeRejectsColumnsOutsideCanonicalRange() {
        assertTrue(PersistedBlockPos.decodeTellusDense(-22630993051434688L).isEmpty());
        assertTrue(PersistedBlockPos.decodeTellusDense(-1L).isEmpty());
    }

    @Test
    void denseDecodePreservesBothVerticalSlotsAtBoundary() {
        assertEquals(Optional.of(new BlockPos(-20037509, -640, -20037509)),
                PersistedBlockPos.decodeTellusDense(0L));
        assertEquals(Optional.of(new BlockPos(-20037509, 10639, -20037509)),
                PersistedBlockPos.decodeTellusDense(11279L));
        assertEquals(Optional.of(new BlockPos(-20037509, -832, -20037509)),
                PersistedBlockPos.decodeTellusDense(11280L));
        assertEquals(Optional.of(new BlockPos(-20037509, -641, -20037509)),
                PersistedBlockPos.decodeTellusDense(11471L));
    }

    @Test
    void verifiedDenseRecoveryRequiresMatchingPhysicalEntity() {
        CompoundTag tag = legacyTag(TELLUS_RAW);
        List<BlockPos> queried = new ArrayList<>();

        assertEquals(TRUE_POS, verified(tag, true, pos -> {
            queried.add(pos);
            return pos.equals(TRUE_POS) ? Optional.of(physicalEntity(TRUE_POS)) : Optional.empty();
        }));
        assertEquals(List.of(TRUE_POS, VANILLA_POS), queried);
        assertFalse(tag.contains(KEY + "XYZ"), "Reading must not mutate the caller's saved tag");
        assertEquals(TELLUS_RAW, tag.getLong(KEY));
    }

    @Test
    void mismatchedCandidateTypeCannotRecover() {
        CompoundTag wrongType = physicalEntity(TRUE_POS);
        wrongType.putString("id", "sailboatmod:town_core");

        assertRejectedCandidate(wrongType);
    }

    @Test
    void mismatchedCandidateIdentityCannotRecover() {
        CompoundTag wrongOwner = physicalEntity(TRUE_POS);
        wrongOwner.putString(IDENTITY_KEY, "another-nation");

        assertRejectedCandidate(wrongOwner);
    }

    @Test
    void missingOrBlankPhysicalIdentityCannotRecover() {
        CompoundTag missingOwner = physicalEntity(TRUE_POS);
        missingOwner.remove(IDENTITY_KEY);
        assertRejectedCandidate(missingOwner);
        CompoundTag blankOwner = physicalEntity(TRUE_POS);
        blankOwner.putString(IDENTITY_KEY, " ");
        assertRejectedCandidate(blankOwner);
    }

    @Test
    void mismatchedOrMissingPhysicalCoordinatesCannotRecover() {
        CompoundTag wrongCoordinates = physicalEntity(TRUE_POS);
        wrongCoordinates.putInt("x", TRUE_POS.getX() + 1);
        assertRejectedCandidate(wrongCoordinates);
        CompoundTag missingCoordinates = physicalEntity(TRUE_POS);
        missingCoordinates.remove("y");
        assertRejectedCandidate(missingCoordinates);
    }

    @Test
    void matchingPhysicalEntitiesAtBothPositionsPreventRecovery() {
        assertEquals(VANILLA_POS, verified(legacyTag(TELLUS_RAW), true,
                pos -> Optional.of(physicalEntity(pos))));
    }

    @Test
    void physicalEntityWithDifferentIdentityAtCurrentPositionPreventsRecovery() {
        assertEquals(VANILLA_POS, verified(legacyTag(TELLUS_RAW), true, pos -> {
            CompoundTag entity = physicalEntity(pos);
            if (pos.equals(VANILLA_POS)) {
                entity.putString(IDENTITY_KEY, "unrelated-nation");
            }
            return Optional.of(entity);
        }));
    }

    @Test
    void physicalEntityWithMissingIdentityAtCurrentPositionPreventsRecovery() {
        assertEquals(VANILLA_POS, verified(legacyTag(TELLUS_RAW), true, pos -> {
            CompoundTag entity = physicalEntity(pos);
            if (pos.equals(VANILLA_POS)) {
                entity.remove(IDENTITY_KEY);
            }
            return Optional.of(entity);
        }));
    }

    @Test
    void nonTellusLevelDoesNotProbeOrRecover() {
        assertEquals(VANILLA_POS, verified(legacyTag(TELLUS_RAW), false, noProbe()));
    }

    @Test
    void absentCandidateDoesNotProbeOrRecover() {
        assertEquals(BlockPos.of(-1L), verified(legacyTag(-1L), true, noProbe()));
        assertEquals(VANILLA_POS, verified(legacyTag(TELLUS_RAW), true, pos -> Optional.empty()));
    }

    @Test
    void missingRawLongDoesNotProbeOrRecover() {
        assertEquals(BlockPos.ZERO, verified(new CompoundTag(), true, noProbe()));
    }

    @Test
    void blankExpectedIdentityCannotRecover() {
        assertEquals(VANILLA_POS, PersistedBlockPos.readVerified(legacyTag(TELLUS_RAW), KEY, true,
                BLOCK_ENTITY_ID, IDENTITY_KEY, " ", noProbe(), pos -> true));
    }

    @Test
    void candidateOutsideBuildHeightDoesNotProbe() {
        assertEquals(VANILLA_POS, PersistedBlockPos.readVerified(legacyTag(TELLUS_RAW), KEY, true,
                BLOCK_ENTITY_ID, IDENTITY_KEY, IDENTITY_VALUE, noProbe(), pos -> false));
    }

    @Test
    void currentOutsideBuildHeightIsAbsentWithoutProbe() {
        assertEquals(TRUE_POS, PersistedBlockPos.readVerified(legacyTag(TELLUS_RAW), KEY, true,
                BLOCK_ENTITY_ID, IDENTITY_KEY, IDENTITY_VALUE, pos -> {
                    assertEquals(TRUE_POS, pos);
                    return Optional.of(physicalEntity(pos));
                }, pos -> pos.equals(TRUE_POS)));
    }

    @Test
    void nativeTellusCodecAlreadyAtCandidateDoesNotDecodeAgainOrProbe() {
        assertEquals(TRUE_POS, PersistedBlockPos.recoverVerified(TRUE_POS, Optional.of(TRUE_POS), true,
                BLOCK_ENTITY_ID, IDENTITY_KEY, IDENTITY_VALUE, noProbe(), pos -> true));
    }

    @Test
    void missingWorldContextDoesNotResolveDimensionOrProbe() {
        assertNull(PersistedBlockPos.forDimension(null, "minecraft:overworld"));
        assertEquals(VANILLA_POS, PersistedBlockPos.readVerified(null, legacyTag(TELLUS_RAW), KEY,
                BLOCK_ENTITY_ID, IDENTITY_KEY, IDENTITY_VALUE));
    }

    private static void assertRejectedCandidate(CompoundTag candidate) {
        assertEquals(VANILLA_POS, verified(legacyTag(TELLUS_RAW), true, pos -> {
            assertEquals(TRUE_POS, pos, "A rejected candidate must not probe the current position");
            return Optional.of(candidate);
        }));
    }

    private static BlockPos verified(CompoundTag tag, boolean tellus,
                                     Function<BlockPos, Optional<CompoundTag>> reader) {
        return PersistedBlockPos.readVerified(tag, KEY, tellus, BLOCK_ENTITY_ID, IDENTITY_KEY,
                IDENTITY_VALUE, reader, pos -> true);
    }

    private static Function<BlockPos, Optional<CompoundTag>> noProbe() {
        return pos -> {
            throw new AssertionError("Unexpected world probe at " + pos);
        };
    }

    private static CompoundTag legacyTag(long raw) {
        CompoundTag tag = new CompoundTag();
        tag.putLong(KEY, raw);
        return tag;
    }

    private static CompoundTag physicalEntity(BlockPos pos) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", BLOCK_ENTITY_ID);
        entity.putInt("x", pos.getX());
        entity.putInt("y", pos.getY());
        entity.putInt("z", pos.getZ());
        entity.putString(IDENTITY_KEY, IDENTITY_VALUE);
        return entity;
    }
}
