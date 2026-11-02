package com.monpai.sailboatmod.route.water;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
class AsyncRouteExecutorTest {
    @Test void independentWorkActuallyRunsInParallel() throws Exception {
        WaterRouteTaskExecutor.setExecutorForTesting(null);
        CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);
        try{
            var a=WaterRouteTaskExecutor.submit(()->waitWork(started,release));var b=WaterRouteTaskExecutor.submit(()->waitWork(started,release));
            assertTrue(started.await(3,TimeUnit.SECONDS));release.countDown();assertTrue(a.get(3,TimeUnit.SECONDS));assertTrue(b.get(3,TimeUnit.SECONDS));
        }finally{release.countDown();WaterRouteTaskExecutor.shutdown();}
    }
    private boolean waitWork(CountDownLatch start,CountDownLatch release){
        start.countDown();try{return release.await(5,TimeUnit.SECONDS)&&Thread.currentThread().getName().startsWith("WaterRoute-Worker");}
        catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    }
    @Test void rejectedWorkNeverRunsOnCaller(){
        AtomicBoolean ran=new AtomicBoolean();WaterRouteTaskExecutor.setExecutorForTesting(r->{throw new RejectedExecutionException();});
        try{var f=WaterRouteTaskExecutor.submit(()->{ran.set(true);return 1;});assertTrue(f.isCompletedExceptionally());assertFalse(ran.get());}
        finally{WaterRouteTaskExecutor.setExecutorForTesting(null);}
    }
}
