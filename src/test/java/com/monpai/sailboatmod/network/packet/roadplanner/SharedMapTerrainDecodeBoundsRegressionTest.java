package com.monpai.sailboatmod.network.packet.roadplanner;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapTerrainDecodeBoundsRegressionTest {
    @Test void networkPredictionCannotAllocateAWholeNativeTilePerPacket(){
        assertThrows(IllegalArgumentException.class,()->new SharedMapTerrainPatchPacket(
                "earth","minecraft:overworld",0,0,0,0,256,256,new int[65536],true));
    }
    @Test void invalidGeometryIsRejectedBeforeReadingAnyDeclaredPixel(){
        FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.buffer());
        try{
            b.writeUtf("earth",128);b.writeUtf("minecraft:overworld",128);
            b.writeInt(0);b.writeInt(0);b.writeVarInt(0);b.writeVarInt(0);
            b.writeVarInt(Integer.MAX_VALUE);b.writeVarInt(1);b.writeBoolean(true);
            b.writeVarInt(1);b.writeVarInt(1);
            int pixelStart=b.writerIndex();b.writeInt(0xFF123456);
            assertThrows(IllegalArgumentException.class,()->SharedMapTerrainPatchPacket.decode(b));
            assertEquals(pixelStart,b.readerIndex(),"reject metadata before pixel reads/array allocation");
        }finally{b.release();}
    }
}
