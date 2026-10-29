package com.monpai.sailboatmod.client.ui.territory;

import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import com.monpai.sailboatmod.client.screen.ClaimWorldMapView;
import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ClaimMapPainterBoundedWorkTest {
    @Test
    void continentScaleOverlayVisitsOnlyKnownClaimsAndFourOwnerNeighbours() throws Exception {
        NationOverviewClaim first = claim(0, 0, "a"), second = claim(1, 0, "a"), outside = claim(9_000_000, 9_000_000, "b");
        Map<Long, NationOverviewClaim> known = new HashMap<>();
        for (var claim : List.of(first, second, outside)) known.put(key(claim.chunkX(), claim.chunkZ()), claim);
        AtomicInteger lookups = new AtomicInteger(), projected = new AtomicInteger();
        ClaimMapPainter.ClaimLookup lookup = (x, z) -> { lookups.incrementAndGet(); return known.get(key(x, z)); };
        Function<NationOverviewClaim, ClaimWorldMapView.ScreenRect> project = value -> {
            projected.incrementAndGet();
            return new ClaimWorldMapView.ScreenRect(value.chunkX() == 0 ? 5 : 25, 15, 20, 20);
        };
        List<?> fills = claims(known.values(), new ClaimWorldMapView.ChunkBounds(-2_000_000, 2_000_000, -2_000_000, 2_000_000),
                new ClaimMapViewport(10, 20, 100, 80), project, lookup);
        assertEquals(2, projected.get());
        assertEquals(8, lookups.get(), "draw work must not scale with the trillion unknown chunks inside the viewport");
        assertEquals(8, fills.size(), "two same-owner neighbours share their interior border");
        for (Object fill : fills) {
            assertTrue(value(fill, "x1") >= 10 && value(fill, "y1") >= 20);
            assertTrue(value(fill, "x2") <= 110 && value(fill, "y2") <= 100);
        }
    }

    @Test
    void hugeAreaSelectionIsOneClippedBorderWithAtMostFourRectangles() throws Exception {
        List<?> fills = area(new ClaimWorldMapView.ScreenRect(-1_000_000, -1_000_000, 1, 1),
                new ClaimWorldMapView.ScreenRect(1_000_000, 1_000_000, 1, 1), new ClaimMapViewport(10, 20, 100, 80));
        assertEquals(4, fills.size(), "area selection cannot iterate every selected world chunk");
        for (Object fill : fills) {
            assertTrue(value(fill, "x1") >= 10 && value(fill, "y1") >= 20);
            assertTrue(value(fill, "x2") <= 110 && value(fill, "y2") <= 100);
            assertTrue(value(fill, "x2") - value(fill, "x1") == 1 || value(fill, "y2") - value(fill, "y1") == 1);
        }
        assertTrue(area(new ClaimWorldMapView.ScreenRect(-1000, -1000, 1, 1),
                new ClaimWorldMapView.ScreenRect(-900, -900, 1, 1), new ClaimMapViewport(10, 20, 100, 80)).isEmpty());
    }

    private static List<?> claims(Collection<NationOverviewClaim> known, ClaimWorldMapView.ChunkBounds bounds,
                                   ClaimMapViewport viewport, Function<NationOverviewClaim, ClaimWorldMapView.ScreenRect> project,
                                   ClaimMapPainter.ClaimLookup lookup) throws Exception {
        try {
            return (List<?>) ClaimMapPainter.class.getMethod("planClaims", Collection.class,
                    ClaimWorldMapView.ChunkBounds.class, ClaimMapViewport.class, Function.class,
                    ClaimMapPainter.ClaimLookup.class, Function.class).invoke(null, known, bounds, viewport, project,
                    lookup, (Function<NationOverviewClaim, String>) NationOverviewClaim::nationId);
        } catch (NoSuchMethodException missing) {
            return fail("claim painting must enumerate known claims rather than the entire far-zoom chunk rectangle");
        }
    }

    private static List<?> area(ClaimWorldMapView.ScreenRect first, ClaimWorldMapView.ScreenRect last,
                                ClaimMapViewport viewport) throws Exception {
        try { return (List<?>) ClaimMapPainter.class.getMethod("areaBorder", ClaimWorldMapView.ScreenRect.class,
                ClaimWorldMapView.ScreenRect.class, ClaimMapViewport.class, int.class).invoke(null, first, last, viewport, 0xAAFF8844); }
        catch (NoSuchMethodException missing) { return fail("area borders must be projected and clipped once, independently of selected chunk count"); }
    }

    private static int value(Object rectangle, String field) throws Exception {
        return (int) rectangle.getClass().getMethod(field).invoke(rectangle);
    }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private static NationOverviewClaim claim(int x, int z, String owner) {
        return new NationOverviewClaim(x, z, owner, owner, 0x335577, 0x112233, "", "", "", "", "", "", "", "", "");
    }
}
