package com.monpai.sailboatmod.route.water;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WaterGridTraversalCursorTest {
    @Test
    void lookaheadCursorMatchesTheFinePlannerIncludingCornerContactsInEveryDirection() {
        for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
            int x = -17, z = 15;
            List<String> fine = new ArrayList<>();
            WaterGridTraversal.all(x, z, x + dx, z + dz, (cx, cz) -> {
                fine.add(cx + "," + cz);
                return true;
            });
            List<String> lookahead = new ArrayList<>();
            var cursor = WaterGridTraversal.cursor(x, z, x + dx, z + dz);
            while (cursor.advance()) {
                if (cursor.corner()) {
                    lookahead.add(cursor.x() + "," + cursor.previousZ());
                    lookahead.add(cursor.previousX() + "," + cursor.z());
                }
                lookahead.add(cursor.x() + "," + cursor.z());
            }
            assertEquals(fine, lookahead, "dx=" + dx + " dz=" + dz);
        }
    }

    @Test
    void aLongRouteCanBeScannedOnlyAsFarAsTheLocalLookaheadNeeds() {
        var cursor = WaterGridTraversal.cursor(-33_000_000, 20_000_000, 33_000_000, -20_000_000);
        for (int i = 0; i < 160; i++) assertTrue(cursor.advance());
        assertTrue(cursor.hasNext());
        assertTrue(Math.abs((long) cursor.x() + 33_000_000) <= 160);
        assertTrue(Math.abs((long) cursor.z() - 20_000_000) <= 160);
    }
}
