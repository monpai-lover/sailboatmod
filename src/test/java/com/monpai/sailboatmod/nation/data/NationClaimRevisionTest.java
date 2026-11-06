package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NationClaimRevisionTest {
    private NationClaimRecord claim(String nation,String town){return new NationClaimRecord("minecraft:overworld",2,3,nation,town,"member","member","member","member","member","member","member",100);}
    @Test void onlyActualOwnershipChangesAdvanceTheTransientRevision(){
        var data=new NationSavedData();assertEquals(0,data.claimRevision());
        data.putClaim(claim("n","t"));long first=data.claimRevision();assertTrue(first>0);
        data.putClaim(claim("n","t").withMobSpawningAllowed(false));data.setDirty();assertEquals(first,data.claimRevision());
        data.putClaim(claim("n","other"));assertTrue(data.claimRevision()>first);
        long changed=data.claimRevision();data.removeClaim("minecraft:overworld",2,3);assertTrue(data.claimRevision()>changed);
        long removed=data.claimRevision();data.removeClaim("minecraft:overworld",2,3);assertEquals(removed,data.claimRevision());
    }
    @Test void everyBulkOwnershipRemovalAdvancesTheSameRevision(){
        for(String operation:new String[]{"clearNation","clearTown","removeNation","removeTown"}){
            var data=new NationSavedData();data.putClaim(claim("n","t"));long before=data.claimRevision();
            switch(operation){case "clearNation"->data.clearClaimsForNation("n");case "clearTown"->data.clearClaimsForTown("t");case "removeNation"->data.removeNation("n");case "removeTown"->data.removeTown("t");}
            assertNull(data.getClaim("minecraft:overworld",2,3));assertTrue(data.claimRevision()>before,operation);
        }
    }
}
