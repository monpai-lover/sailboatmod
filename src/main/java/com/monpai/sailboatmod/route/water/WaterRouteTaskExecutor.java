package com.monpai.sailboatmod.route.water;
import java.util.concurrent.*;
import java.util.function.Supplier;
/** Dedicated bounded pool; queue saturation never runs pathfinding on the server thread. */
public final class WaterRouteTaskExecutor {
    private static Executor injected;
    private static ThreadPoolExecutor owned;
    private WaterRouteTaskExecutor(){}
    private static synchronized Executor executor(){
        if(injected!=null)return injected;
        if(owned==null||owned.isShutdown()){
            owned=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{
                Thread t=new Thread(r,"WaterRoute-Worker");t.setDaemon(true);return t;
            },new ThreadPoolExecutor.AbortPolicy());owned.allowCoreThreadTimeOut(true);
        }
        return owned;
    }
    public static <T> CompletableFuture<T> submit(Supplier<T> task){
        try{return CompletableFuture.supplyAsync(task,executor());}
        catch(RejectedExecutionException busy){return CompletableFuture.failedFuture(busy);}
    }
    public static synchronized void setExecutorForTesting(Executor value){shutdown();injected=value;}
    public static synchronized void shutdown(){if(owned!=null){owned.shutdownNow();owned=null;}}
}
