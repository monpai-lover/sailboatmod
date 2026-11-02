package com.monpai.sailboatmod.nation.model;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.service.ClaimMobSpawnPolicy;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class ClaimMobSpawningTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private NationClaimRecord claim(){return new NationClaimRecord("minecraft:overworld",-1,2,"nation","town",
            "member","member","member","member","member","member","member",42,"town_core");}
    @Test void newClaimAllowsSpawning(){assertTrue(claim().mobSpawningAllowed());}
    @Test void oldSaveAbsentFlagAllowsSpawning(){var tag=claim().save();tag.remove("MobSpawningAllowed");assertTrue(NationClaimRecord.load(tag).mobSpawningAllowed());}
    @Test void disabledFlagRoundTrips(){var c=claim().withMobSpawningAllowed(false);assertEquals(c,NationClaimRecord.load(c.save()));}
    @Test void enabledFlagRoundTrips(){assertEquals(claim(),NationClaimRecord.load(claim().save()));}
    @Test void changingRolePermissionPreservesSpawnFlag(){for(String action:new String[]{"break","place","use","container","redstone","entity_use","entity_damage"})assertFalse(claim().withMobSpawningAllowed(false).withPermission(action,NationClaimAccessLevel.ANYONE).mobSpawningAllowed());}
    @Test void changingCombinedPermissionsPreservesSpawnFlag(){assertFalse(claim().withMobSpawningAllowed(false).withPermissions(null,NationClaimAccessLevel.ANYONE,null).mobSpawningAllowed());}
    @Test void togglingSpawnDoesNotChangeOtherPermissionOrOwnership(){var c=claim().withMobSpawningAllowed(false);assertEquals(claim(),c.withMobSpawningAllowed(true));}
    @Test void invalidRoleActionIsNotASpawnToggle(){assertTrue(claim().withPermission("mob_spawn",NationClaimAccessLevel.ANYONE).mobSpawningAllowed());}
    @Test void missingClaimKeepsVanillaBehaviour(){assertTrue(new NationSavedData().allowsMonsterSpawning("minecraft:overworld",-1,2));}
    @Test void permissionChangeUpdatesIndexImmediately(){var data=new NationSavedData();data.putClaim(claim().withMobSpawningAllowed(false));assertFalse(data.allowsMonsterSpawning("minecraft:overworld",-1,2));data.putClaim(claim());assertTrue(data.allowsMonsterSpawning("minecraft:overworld",-1,2));}
    @Test void differentDimensionAndAdjacentChunksUnaffected(){var d=new NationSavedData();d.putClaim(claim().withMobSpawningAllowed(false));assertTrue(d.allowsMonsterSpawning("minecraft:the_nether",-1,2));assertTrue(d.allowsMonsterSpawning("minecraft:overworld",0,2));assertTrue(d.allowsMonsterSpawning("minecraft:overworld",-1,3));}
    @Test void removalClearsIndex(){var d=new NationSavedData();d.putClaim(claim().withMobSpawningAllowed(false));d.removeClaim("minecraft:overworld",-1,2);assertTrue(d.allowsMonsterSpawning("minecraft:overworld",-1,2));}
    @Test void clearingTownAndNationClearsIndex(){for(boolean town:new boolean[]{true,false}){var d=new NationSavedData();d.putClaim(claim().withMobSpawningAllowed(false));if(town)d.clearClaimsForTown("town");else d.clearClaimsForNation("nation");assertTrue(d.allowsMonsterSpawning("minecraft:overworld",-1,2));}}
    @Test void saveLoadRebuildsSpawnIndex(){var tag=new CompoundTag();var claims=new ListTag();claims.add(claim().withMobSpawningAllowed(false).save());tag.put("Claims",claims);assertFalse(NationSavedData.load(tag).allowsMonsterSpawning("minecraft:overworld",-1,2));}
    @Test void generatorThreadCanReadIndexWithoutLoadingWorld(){var d=new NationSavedData();d.putClaim(claim().withMobSpawningAllowed(false));assertFalse(CompletableFuture.supplyAsync(()->d.allowsMonsterSpawning("minecraft:overworld",-1,2)).join());}
    @Test void naturalAndSpawnerMonstersAreControlled(){for(var t:new MobSpawnType[]{MobSpawnType.NATURAL,MobSpawnType.SPAWNER,MobSpawnType.TRIAL_SPAWNER,MobSpawnType.PATROL,MobSpawnType.REINFORCEMENT,MobSpawnType.EVENT})assertTrue(ClaimMobSpawnPolicy.automaticMonster(MobCategory.MONSTER,t));}
    @Test void animalsAndVillagersAreNeverControlled(){for(var cat:MobCategory.values())if(cat!=MobCategory.MONSTER)for(var type:MobSpawnType.values())assertFalse(ClaimMobSpawnPolicy.automaticMonster(cat,type));}
    @Test void explicitSpawningAndConversionsAreNotControlled(){for(var t:new MobSpawnType[]{MobSpawnType.COMMAND,MobSpawnType.SPAWN_EGG,MobSpawnType.DISPENSER,MobSpawnType.CONVERSION,MobSpawnType.BREEDING,MobSpawnType.BUCKET})assertFalse(ClaimMobSpawnPolicy.automaticMonster(MobCategory.MONSTER,t));}
    @Test void unknownSpawnReasonIsNotRemoved(){assertFalse(ClaimMobSpawnPolicy.automaticMonster(MobCategory.MONSTER,null));}
}
