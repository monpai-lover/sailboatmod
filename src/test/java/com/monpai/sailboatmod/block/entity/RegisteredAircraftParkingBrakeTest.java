package com.monpai.sailboatmod.block.entity;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class RegisteredAircraftParkingBrakeTest {
 final UUID body=UUID.randomUUID();
 static final class Outputs implements RegisteredAircraftParkingBrake.Outputs {
  final List<String> events=new ArrayList<>(); boolean accepts=true;
  public boolean neutral(int signal){events.add("neutral:"+signal);return accepts;}
  public void brake(){events.add("brake:15");}
  public void clear(){events.add("clear:0");}
 }
 RegisteredAircraftParkingBrake.Evidence good(boolean acquire){return new RegisteredAircraftParkingBrake.Evidence(body,true,true,true,true,false,false,true,acquire,15);}
 @Test void strictFreshStandAcquiresNormalNeutralAndBrake(){var p=new RegisteredAircraftParkingBrake();var out=new Outputs();p.update(good(true),out);assertTrue(p.held());assertEquals(List.of("neutral:15","brake:15"),out.events);}
 @Test void preflightRetainsOwnedBrakeWithoutAnIntermediateClearOrNewClaim(){var p=new RegisteredAircraftParkingBrake();var out=new Outputs();p.update(good(true),out);p.update(good(false),out);assertTrue(p.held());assertEquals(List.of("neutral:15","brake:15","neutral:15"),out.events);}
 @Test void completedGroundHoldTransfersToIdleAndThenTaxiWithNoBrakeZeroPulse(){var p=new RegisteredAircraftParkingBrake();var out=new Outputs();p.adopt(body);p.update(good(false),out);assertTrue(p.held());p.toGroundMission();assertFalse(p.held());assertEquals(List.of("neutral:15"),out.events);}
 @Test void everyMissingInitialProofPreventsNewAuthority(){
  for(var e:List.of(new RegisteredAircraftParkingBrake.Evidence(body,false,true,true,true,false,false,true,true,15),new RegisteredAircraftParkingBrake.Evidence(body,true,false,true,true,false,false,true,true,15),new RegisteredAircraftParkingBrake.Evidence(body,true,true,false,true,false,false,true,true,15),new RegisteredAircraftParkingBrake.Evidence(body,true,true,true,false,false,false,true,true,15),new RegisteredAircraftParkingBrake.Evidence(body,true,true,true,true,false,false,false,true,15),good(false))){var p=new RegisteredAircraftParkingBrake();var out=new Outputs();p.update(e,out);assertFalse(p.held());assertTrue(out.events.isEmpty());}
 }
 @Test void manualForeignAndChangedBodyClearOnlyOurOutput(){for(int mode=0;mode<3;mode++){var p=new RegisteredAircraftParkingBrake();var out=new Outputs();p.adopt(body);p.update(new RegisteredAircraftParkingBrake.Evidence(mode==2?UUID.randomUUID():body,true,true,true,true,mode==0,mode==1,true,false,15),out);assertFalse(p.held());assertEquals(List.of("clear:0"),out.events);}}
 @Test void staleOrMissingGroundDoesNotWriteAgainOrClaim(){var p=new RegisteredAircraftParkingBrake();var out=new Outputs();p.adopt(body);p.update(new RegisteredAircraftParkingBrake.Evidence(body,false,false,true,false,false,false,false,false,15),out);assertTrue(p.held());assertTrue(out.events.isEmpty());}
 @Test void ownerReleaseStaysReleasedUntilExplicitRearm(){var p=new RegisteredAircraftParkingBrake();var out=new Outputs();p.adopt(body);p.ownerRelease(out);p.update(good(true),out);assertFalse(p.held());assertTrue(p.released());assertEquals(List.of("clear:0"),out.events);p.rearm();p.update(good(true),out);assertTrue(p.held());assertEquals(List.of("clear:0","neutral:15","brake:15"),out.events);}
 @Test void reloadRestoresOnlyIntentAndAlwaysNeedsFreshProof(){var p=new RegisteredAircraftParkingBrake();var out=new Outputs();p.adopt(body);p.restoreReleased(false);assertFalse(p.held());p.update(good(false),out);assertFalse(p.held());p.update(good(true),out);assertTrue(p.held());p.restoreReleased(true);p.update(good(true),out);assertFalse(p.held());}
 @Test void heldParkingRevalidatesTheExistingNativeNeutralLease(){var p=new RegisteredAircraftParkingBrake();var out=new Outputs();p.adopt(body);p.update(good(false),out);assertEquals(List.of("neutral:15"),out.events,"Native throttle lease validation detects a real manual lever change before any setter");assertTrue(p.held());}
 @Test void refusedNeutralDoesNotClaimOrApplyBrake(){var p=new RegisteredAircraftParkingBrake();var out=new Outputs();out.accepts=false;p.update(good(true),out);assertFalse(p.held());assertEquals(List.of("neutral:15"),out.events);}
}
