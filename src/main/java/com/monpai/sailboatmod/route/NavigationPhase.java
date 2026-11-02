package com.monpai.sailboatmod.route;

/** Both the R menu and map status derive from the server's movement phase. */
public enum NavigationPhase {
    IDLE("stopped","STOPPED"), SAILING("running","SAILING"), TURNING("turning","SAILING"),
    WAITING_TERRAIN("waiting_terrain","WAITING_TERRAIN"), WAITING_DEPARTURE("waiting_departure","WAITING"),
    CONNECTING("connecting","REROUTING"), RECOVERING("recovering","REROUTING"),
    PAUSED("paused","PAUSED"), BLOCKED("blocked","STUCK");
    private final String key,trace;
    NavigationPhase(String key,String trace){this.key=key;this.trace=trace;}
    public String translationKey(){return "screen.sailboatmod.route_status."+key;}
    public String traceStatus(){return trace;}
    public static NavigationPhase of(int id){return id>=0&&id<values().length?values()[id]:IDLE;}
}
