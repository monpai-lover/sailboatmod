package com.monpai.sailboatmod.roadplanner.service;

import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapGlobalTickBudgetTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void ActualServiceDoesNotReplenishSamplingOrDeadlineForAnotherDimensionInTheSameTick()throws Exception{
        var service=new SharedMapTerrainService();var server=allocate(DedicatedServer.class);
        try{
            set(service,"server",server);set(MinecraftServer.class,server,"tickCount",101);
            service.tick(allocate(ServerLevel.class));
            set(service,"advanceRemaining",3);set(service,"changedRemaining",2);set(service,"sampleDeadline",Long.MAX_VALUE);
            service.tick(allocate(ServerLevel.class));
            assertEquals(3,get(service,"advanceRemaining"));assertEquals(2,get(service,"changedRemaining"));
            assertEquals(Long.MAX_VALUE,get(service,"sampleDeadline"));
            set(MinecraftServer.class,server,"tickCount",102);service.tick(allocate(ServerLevel.class));
            assertEquals(8,get(service,"advanceRemaining"));assertEquals(8,get(service,"changedRemaining"));
            assertNotEquals(Long.MAX_VALUE,get(service,"sampleDeadline"));
        }finally{var m=SharedMapTerrainService.class.getDeclaredMethod("close");m.setAccessible(true);m.invoke(service);}
    }
    private static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{set(target.getClass(),target,name,value);}
    private static void set(Class<?> owner,Object target,String name,Object value)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @SuppressWarnings("unchecked")private static <T>T allocate(Class<T> type)throws Exception{Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return(T)((Unsafe)f.get(null)).allocateInstance(type);}
}
