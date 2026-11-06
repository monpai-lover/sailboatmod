package com.monpai.sailboatmod.block.entity;

import java.util.UUID;

/** Runtime ownership of the registrar's ground outputs, never a native flight lease or saved actuator command. */
final class RegisteredAircraftParkingBrake {
    // Match the existing native noncollinear three-point support rule; every configured contact is still required.
    static final int MINIMUM_WHEELS=3;
    static boolean supportedObservation(com.monpai.sailboatmod.transport.physical.NativeGroundObservation observation,int configured){
        return observation!=null&&configured>=MINIMUM_WHEELS&&observation.available()
                &&observation.wheels().size()==configured&&observation.fullyGrounded()
                &&observation.wheels().stream().allMatch(w->w.supported()&&Double.isFinite(w.tireRadius())&&w.tireRadius()>0
                    &&Double.isFinite(w.suspensionCompression())&&w.suspensionCompression()>0&&w.worldCenter()!=null
                    &&Double.isFinite(w.worldCenter().x)&&Double.isFinite(w.worldCenter().y)&&Double.isFinite(w.worldCenter().z));
    }
    record Evidence(UUID body, boolean fresh, boolean supported, boolean hardware, boolean nativeReleased,
                    boolean foreignOwner, boolean manual, boolean inCargoStand, boolean mayAcquire, int neutralSignal) { }
    interface Outputs { boolean neutral(int signal); void brake(); void clear(); }
    private UUID body;
    private boolean held, released;
    boolean held() { return held; }
    boolean released() { return released; }
    boolean holdsBody(UUID physicalBody) { return held && !released && physicalBody != null && physicalBody.equals(body); }
    void update(Evidence e, Outputs outputs) {
        if (held && (e.body()==null || !e.body().equals(body) || !e.hardware() || e.foreignOwner() || e.manual())) {
            yieldToOther(outputs); return;
        }
        if (e.manual() || e.foreignOwner() || released || e.body()==null || !e.fresh() || !e.supported()
                || !e.hardware() || !e.nativeReleased()) return;
        if (held) { outputs.neutral(e.neutralSignal()); return; }
        if (!e.mayAcquire() || !e.inCargoStand() || e.neutralSignal()<0 || e.neutralSignal()>15) return;
        if (outputs.neutral(e.neutralSignal())) { outputs.brake(); held=true; body=e.body(); }
    }
    void adopt(UUID physicalBody) { if (physicalBody==null) throw new IllegalArgumentException("body"); body=physicalBody; held=true; released=false; }
    void ownerRelease(Outputs outputs) { released=true; yieldToOther(outputs); }
    void rearm() { released=false; }
    void yieldToOther(Outputs outputs) { if (held) outputs.clear(); forget(); }
    void forget() { held=false; body=null; }
    void toGroundMission() { forget(); }
    void restoreReleased(boolean ownerReleased) { forget(); released=ownerReleased; }
}
