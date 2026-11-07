package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket;
import com.monpai.sailboatmod.transport.control.ControllerAction;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Client draft and packet construction only; the server checks ownership, body, bearing and axis. */
public final class AirSurfaceClientSelection {
    public static final List<String> ROLES=List.of("ELEVATOR","AILERON_LEFT","AILERON_RIGHT","RUDDER","ELEVON_LEFT","ELEVON_RIGHT");
    public record Config(String role,double neutral,int polarity,double maximum,double rate) {
        public Config {
            if(!ROLES.contains(role)||!Double.isFinite(neutral)||Math.abs(neutral)>180||polarity!=1&&polarity!=-1
                    ||!Double.isFinite(maximum)||maximum<=0||maximum>30||!Double.isFinite(rate)||rate<=0||rate>90)
                throw new IllegalArgumentException("invalid_air_surface_configuration");
        }
        public String key(BlockPos position){return role+";"+position.getX()+","+position.getY()+","+position.getZ();}
        public String value(BlockPos position){return key(position)+";"+neutral+";"+polarity+";"+maximum+";"+rate;}
    }
    public record Pending(BlockPos controllerPos,UUID registrationId,String dimension,Config config) {
        public Pending {controllerPos=Objects.requireNonNull(controllerPos).immutable();Objects.requireNonNull(registrationId);Objects.requireNonNull(dimension);Objects.requireNonNull(config);}
    }
    private AirSurfaceClientSelection() { }
    public static boolean canEdit(boolean active,boolean paused){return !active||paused;}
    public static boolean canRestoreDrive(boolean active,boolean paused){return !active&&!paused;}
    public static Optional<Config> parse(String role,String neutral,int polarity,String maximum,String rate) {
        try{return Optional.of(new Config(role,Double.parseDouble(neutral),polarity,Double.parseDouble(maximum),Double.parseDouble(rate)));}
        catch(IllegalArgumentException|NullPointerException invalid){return Optional.empty();}
    }
    public static Optional<RegisteredVehicleControllerActionPacket> select(Pending pending,String dimension,BlockPos actualHit) {
        if(pending==null||actualHit==null||!pending.dimension().equals(dimension))return Optional.empty();
        return Optional.of(new RegisteredVehicleControllerActionPacket(pending.controllerPos(),pending.registrationId(),ControllerAction.BIND_AIR_SURFACE,pending.config().value(actualHit),0));
    }
}
