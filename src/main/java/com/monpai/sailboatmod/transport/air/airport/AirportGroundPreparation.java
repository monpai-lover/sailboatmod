package com.monpai.sailboatmod.transport.air.airport;

import java.util.*;

/** Transient per-intent cursor. A WAITING provider must continue its existing incremental scan. */
public final class AirportGroundPreparation {
    public static final int MAX_HARD_REJECTIONS_PER_CALL=4;
    public static final long RETRY_TICKS=20;
    public enum Status { READY, WAITING, BLOCKED }
    public record Check(Status status,String reason) { }
    public record Outcome(Status status,String reason,AirportGroundSelection.Leg leg) { }
    @FunctionalInterface public interface Provider { Check prepare(AirportGroundSelection.Candidate candidate); }
    private final Set<GroundAssignment> blockedSources=new HashSet<>(),blockedDestinations=new HashSet<>();
    private AirportGroundSelection.Leg pinned;
    private long retryAt=-1,lastTick=-1;
    private String terminalReason="airport_unavailable";

    /** Compatibility overload for callers without a clock; runtime callers supply the real game tick. */
    public Outcome advance(List<AirportGroundSelection.Candidate> sources,List<AirportGroundSelection.Candidate> destinations,
            AirportGroundSelection.LegGate gate,Provider source,Provider destination) {
        return advance(0,sources,destinations,gate,source,destination);
    }
    public Outcome advance(long now,List<AirportGroundSelection.Candidate> sources,List<AirportGroundSelection.Candidate> destinations,
            AirportGroundSelection.LegGate gate,Provider source,Provider destination) {
        if(now<0||sources==null||destinations==null||gate==null||source==null||destination==null
                ||sources.size()>AirportGroundSelection.MAX_PAIRS||destinations.size()>AirportGroundSelection.MAX_PAIRS
                ||sources.stream().anyMatch(Objects::isNull)||destinations.stream().anyMatch(Objects::isNull))
            return new Outcome(Status.BLOCKED,"invalid_candidates",null);
        if(lastTick>now)clear();lastTick=now;
        if(retryAt>=0){
            if(now<retryAt)return new Outcome(Status.BLOCKED,terminalReason,null);
            blockedSources.clear();blockedDestinations.clear();pinned=null;retryAt=-1;
        }
        if(pinned!=null&&(!sources.contains(pinned.source())||!destinations.contains(pinned.destination())))
            return terminal(now,"ground_binding_changed");
        if(pinned!=null){
            try{if(!gate.taxi(pinned.source(),pinned.destination()))return terminal(now,"no_compatible_leg");}
            catch(RuntimeException unproven){return terminal(now,"airport_unavailable");}
        }
        for(int rejected=0;rejected<MAX_HARD_REJECTIONS_PER_CALL;){
            if(pinned==null){
                // Filter exact tuples first. Active-route deduplication must be able to see a
                // second opposite route paired with the same active OUT/IN after a failure.
                var from=sources.stream().filter(c->!blockedSources.contains(c.assignment())).toList();
                var to=destinations.stream().filter(c->!blockedDestinations.contains(c.assignment())).toList();
                var search=AirportGroundSelection.leg(from,to,gate);
                if(search.selected().isEmpty())return terminal(now,search.reason());
                pinned=search.selected().orElseThrow();
            }
            Check from=observe(source,pinned.source());
            if(from==null||from.status()==null)return terminal(now,"airport_unavailable");
            if(from.status()==Status.BLOCKED){blockedSources.add(pinned.source().assignment());pinned=null;rejected++;continue;}
            // Independent airports each retain their own shared per-tick scan budget.
            // A source WAIT must not prevent the destination from making progress.
            Check to=observe(destination,pinned.destination());
            if(to==null||to.status()==null)return terminal(now,"airport_unavailable");
            if(to.status()==Status.BLOCKED){blockedDestinations.add(pinned.destination().assignment());pinned=null;rejected++;continue;}
            if(from.status()==Status.WAITING||to.status()==Status.WAITING)
                return new Outcome(Status.WAITING,from.status()==Status.WAITING?from.reason():to.reason(),pinned);
            return new Outcome(Status.READY,"",pinned);
        }
        return new Outcome(Status.WAITING,"airport_unavailable",null);
    }
    private static Check observe(Provider provider,AirportGroundSelection.Candidate candidate){
        try{return provider.prepare(candidate);}catch(RuntimeException unproven){return new Check(Status.BLOCKED,"airport_unavailable");}
    }
    private Outcome terminal(long now,String reason){
        pinned=null;terminalReason=reason==null||reason.isBlank()?"airport_unavailable":reason;
        retryAt=now>Long.MAX_VALUE-RETRY_TICKS?Long.MAX_VALUE:now+RETRY_TICKS;
        return new Outcome(Status.BLOCKED,terminalReason,null);
    }
    public void clear(){blockedSources.clear();blockedDestinations.clear();pinned=null;retryAt=lastTick=-1;terminalReason="airport_unavailable";}
}
