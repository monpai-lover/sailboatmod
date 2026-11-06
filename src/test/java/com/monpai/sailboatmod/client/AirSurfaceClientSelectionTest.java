package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket;
import com.monpai.sailboatmod.transport.control.ControllerAction;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirSurfaceClientSelectionTest {
    private Class<?> type(){try{return Class.forName("com.monpai.sailboatmod.client.AirSurfaceClientSelection");}catch(ClassNotFoundException missing){return fail("Surface pick configuration feature is missing",missing);}}
    @SuppressWarnings("unchecked") private Optional<Object> parse(String role,String neutral,int polarity,String max,String rate){
        try{return (Optional<Object>)type().getMethod("parse",String.class,String.class,int.class,String.class,String.class).invoke(null,role,neutral,polarity,max,rate);}
        catch(ReflectiveOperationException failure){return fail("Surface draft validation must fail closed",failure);}
    }
    private Object config(){return parse("ELEVATOR","0",1,"20","60").orElseThrow();}
    private Object pending(BlockPos controller,UUID id,String dimension,Object config){
        try{return Class.forName(type().getName()+"$Pending").getConstructor(BlockPos.class,UUID.class,String.class,Class.forName(type().getName()+"$Config")).newInstance(controller,id,dimension,config);}
        catch(ReflectiveOperationException failure){return fail("Surface pick must capture an immutable controller identity",failure);}
    }
    @SuppressWarnings("unchecked") private Optional<RegisteredVehicleControllerActionPacket> select(Object pending,String dimension,BlockPos hit){
        try{return (Optional<RegisteredVehicleControllerActionPacket>)type().getMethod("select",Class.forName(type().getName()+"$Pending"),String.class,BlockPos.class).invoke(null,pending,dimension,hit);}
        catch(ReflectiveOperationException failure){return fail("Surface pick packet must use the actual selected position",failure);}
    }
    @Test void defaultsProduceTheAgreedPacketWithActualAbsolutePlotCoordinates(){
        UUID id=UUID.randomUUID();var controller=new BlockPos(20481000,130,20483000);var hit=new BlockPos(20481038,130,20483086);
        var packet=select(pending(controller,id,"minecraft:overworld",config()),"minecraft:overworld",hit).orElseThrow();
        assertEquals(controller,packet.controllerPos());assertEquals(id,packet.registrationId());assertEquals("BIND_AIR_SURFACE",packet.action().name());
        assertEquals("ELEVATOR;20481038,130,20483086;0.0;1;20.0;60.0",packet.value());assertEquals(0,packet.index());
    }
    @Test void allSixRolesUseTheSameFiniteConfigurationContract(){
        for(String role:List.of("ELEVATOR","AILERON_LEFT","AILERON_RIGHT","RUDDER","ELEVON_LEFT","ELEVON_RIGHT"))assertTrue(parse(role,"-180",-1,"30","90").isPresent(),role);
    }
    @Test void invalidRolesNonFiniteValuesAndOutOfRangeLimitsCannotArmAPick(){
        for(String role:List.of("UNKNOWN","elevator","ELEVATOR;extra",""))assertTrue(parse(role,"0",1,"20","60").isEmpty());
        for(String number:List.of("NaN","Infinity","-Infinity","", "oops")){
            assertTrue(parse("ELEVATOR",number,1,"20","60").isEmpty());assertTrue(parse("ELEVATOR","0",1,number,"60").isEmpty());assertTrue(parse("ELEVATOR","0",1,"20",number).isEmpty());
        }
        for(String neutral:List.of("-180.1","180.1"))assertTrue(parse("ELEVATOR",neutral,1,"20","60").isEmpty());
        for(String max:List.of("0","-1","30.1"))assertTrue(parse("ELEVATOR","0",1,max,"60").isEmpty());
        for(String rate:List.of("0","-1","90.1"))assertTrue(parse("ELEVATOR","0",1,"20",rate).isEmpty());
        for(int polarity:new int[]{0,2,-2})assertTrue(parse("ELEVATOR","0",polarity,"20","60").isEmpty());
    }
    @Test void pendingControllerPositionIsCopiedBeforeASelectionCanMutateIt(){
        var controller=new BlockPos.MutableBlockPos(20481000,130,20483000);UUID id=UUID.randomUUID();Object pick=pending(controller,id,"minecraft:overworld",config());controller.set(1,2,3);
        var hit=new BlockPos.MutableBlockPos(20481038,130,20483086);var packet=select(pick,"minecraft:overworld",hit).orElseThrow();hit.set(4,5,6);
        assertEquals(new BlockPos(20481000,130,20483000),packet.controllerPos());assertTrue(packet.value().startsWith("ELEVATOR;20481038,130,20483086;"));
    }
    @Test void dimensionChangesAndMissingHitsCannotSendAStaleMutation(){
        Object pick=pending(BlockPos.ZERO,UUID.randomUUID(),"minecraft:overworld",config());
        assertTrue(select(pick,"minecraft:the_nether",new BlockPos(1,2,3)).isEmpty());assertTrue(select(pick,"minecraft:overworld",null).isEmpty());assertTrue(select(null,"minecraft:overworld",BlockPos.ZERO).isEmpty());
    }
    @Test void packetCoordinatesAndBindingKeysDoNotBecomeControllerRelative(){
        try{
            var config=config();var hit=new BlockPos(20481038,130,20483086);
            assertEquals("ELEVATOR;20481038,130,20483086",config.getClass().getMethod("key",BlockPos.class).invoke(config,hit));
        }catch(ReflectiveOperationException failure){fail(failure);}
    }
    @Test void newActionsAppendWithoutChangingExistingNetworkOrdinals(){
        assertEquals(32,ControllerAction.SET_TAKEOFF_ASSIST_ACCELERATION.ordinal());
        try{assertEquals(33,ControllerAction.valueOf("BIND_AIR_SURFACE").ordinal());assertEquals(34,ControllerAction.valueOf("REMOVE_AIR_SURFACE").ordinal());assertEquals(35,ControllerAction.valueOf("CLEAR_AIR_SURFACE_BINDING").ordinal());}
        catch(IllegalArgumentException missing){fail("Surface actions must append to the existing action protocol",missing);}
    }
    @Test void surfaceActionsRoundTripThroughTheUnchangedExistingPacketCodec(){
        for(String name:List.of("BIND_AIR_SURFACE","REMOVE_AIR_SURFACE","CLEAR_AIR_SURFACE_BINDING")){
            var packet=new RegisteredVehicleControllerActionPacket(new BlockPos(20481000,130,20483000),UUID.randomUUID(),ControllerAction.valueOf(name),
                    name.equals("BIND_AIR_SURFACE")?"ELEVATOR;20481038,130,20483086;0.0;1;20.0;60.0":"ELEVATOR;20481038,130,20483086",0);
            var buffer=new FriendlyByteBuf(Unpooled.buffer());
            try{RegisteredVehicleControllerActionPacket.encode(packet,buffer);assertEquals(packet,RegisteredVehicleControllerActionPacket.decode(buffer));assertEquals(0,buffer.readableBytes());}
            finally{buffer.release();}
        }
    }
    @Test void anActiveTripMustBePausedBeforeEditingSurfaceCommands(){
        assertTrue(policy("canEdit",false,false));assertTrue(policy("canEdit",false,true));
        assertFalse(policy("canEdit",true,false));assertTrue(policy("canEdit",true,true));
    }
    @Test void restoringOrdinaryDriveRequiresAnInactiveUnpausedController(){
        assertTrue(policy("canRestoreDrive",false,false));assertFalse(policy("canRestoreDrive",false,true));
        assertFalse(policy("canRestoreDrive",true,false));assertFalse(policy("canRestoreDrive",true,true));
    }
    private boolean policy(String name,boolean active,boolean paused){
        try{return (boolean)type().getMethod(name,boolean.class,boolean.class).invoke(null,active,paused);}
        catch(ReflectiveOperationException missing){return fail("Surface editing controls must match the server's pause/stop gates",missing);}
    }
}
