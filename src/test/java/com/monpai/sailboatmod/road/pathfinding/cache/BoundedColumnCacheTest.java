package com.monpai.sailboatmod.road.pathfinding.cache;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class BoundedColumnCacheTest {
 @Test void boundedAndRecomputesEvictedValues(){var c=new BoundedColumnCache<Long>();c.limit(32);for(long i=0;i<10000;i++){long k=i;assertEquals(k,c.computeIfAbsent(k,x->x).longValue());assertTrue(c.size()<=32);}assertEquals(0,c.computeIfAbsent(0L,x->x).longValue());assertTrue(c.size()<=32);}
 @Test void cachesSuccessButNotExceptions(){var c=new BoundedColumnCache<Integer>();var count=new AtomicInteger();assertThrows(IllegalStateException.class,()->c.computeIfAbsent(1L,k->{throw new IllegalStateException();}));for(int i=0;i<20;i++)assertEquals(7,c.computeIfAbsent(1L,k->{count.incrementAndGet();return 7;}));assertEquals(1,count.get());}
 @Test void concurrentSamplingKeepsResultsAndBound()throws Exception{var c=new BoundedColumnCache<Long>();c.limit(64);try(var pool=Executors.newFixedThreadPool(4)){var work=new java.util.ArrayList<Future<?>>();for(int i=0;i<4;i++)work.add(pool.submit(()->{for(long k=0;k<4000;k++)assertEquals(k,c.computeIfAbsent(k,x->x).longValue());}));for(var f:work)f.get(10,TimeUnit.SECONDS);}assertTrue(c.size()<=64);}
}
