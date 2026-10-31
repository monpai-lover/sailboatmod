package com.monpai.sailboatmod.route.water;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in live test of the pinned Tellus binary, never a replacement coastline fixture. */
@EnabledIfSystemProperty(named="tellus.native", matches="true")
class TellusNativeDataProbeTest {
    @Test @Timeout(240)
    void samplePinnedTellusGeographicProviders() throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path out=Path.of("build/native-evidence");Files.createDirectories(out);
        Class<?> type=Class.forName("com.yucareux.tellus.worldgen.EarthGeneratorSettings");
        Object defaults=type.getField("DEFAULT").get(null);
        RecordComponent[] components=type.getRecordComponents();
        Class<?>[] types=new Class<?>[components.length];Object[] values=new Object[components.length];
        for(int i=0;i<components.length;i++) {
            types[i]=components[i].getType();values[i]=components[i].getAccessor().invoke(defaults);
            if(components[i].getName().equals("worldScale"))values[i]=100.0;
        }
        Object settings=type.getConstructor(types).newInstance(values);
        Files.writeString(out.resolve("settings.txt"),settings.toString());
        Class<?> sources=Class.forName("com.yucareux.tellus.worldgen.TellusWorldgenSources");
        Method sample=sources.getMethod("samplePreloadTerrain",int.class,int.class,type,double.class);
        StringBuilder csv=new StringBuilder("x,z,millis,sample\n");
        int[][] points={{28238,-43642},{32258,-50119},{27997,-42143},{28000,-45000},{27830,-42000}};
        for(int[] point:points) {
            long start=System.nanoTime();
            Object value=sample.invoke(null,point[0],point[1],settings,100.0);
            double millis=(System.nanoTime()-start)/1_000_000.0;
            csv.append(point[0]).append(',').append(point[1]).append(',').append(millis).append(',').append('"').append(value).append('"').append('\n');
            Files.writeString(out.resolve("native-probe.csv"),csv);
            System.out.println("TELLUS_NATIVE "+point[0]+","+point[1]+" "+value+" ms="+millis);
            assertNotNull(value);
            assertEquals(Boolean.TRUE,value.getClass().getMethod("landMaskKnown").invoke(value),"native data not ready, must not count a synthetic fallback as real-data success");
        }
    }
}
