package com.monpai.sailboatmod.route.water;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PortDockSnapshotTest {
    @Test void unloadedDockMetadataDecodesWithoutGeneratingChunk(){
        var t=new CompoundTag();t.putString("id","sailboatmod:dock");t.putString("DockName","君士坦丁堡");t.putString("NationId","n");t.putString("TownId","t");
        t.putInt("ZoneMinX",-4);t.putInt("ZoneMaxX",12);
        var d=PortDockSnapshot.fromNbt(new BlockPos(32258,66,-50127),t).orElseThrow();
        assertEquals("君士坦丁堡",d.name());assertEquals(-4,d.zone().minX());assertEquals(12,d.zone().maxX());assertEquals("n",d.access().nationId());
    }
    @Test void nonDockBlockEntityIsRejected(){var t=new CompoundTag();t.putString("id","sailboatmod:post_station");assertTrue(PortDockSnapshot.fromNbt(BlockPos.ZERO,t).isEmpty());}
}
