package com.monpai.sailboatmod.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.monpai.sailboatmod.transport.control.ControlChannel;

import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

class ControllerControlPortsTest {
    @Test
    void everyNativeChannelMustHaveAnOutputFace() throws Exception {
        var complete = uniquePorts();
        assertTrue(valid(complete));
        for (ControlChannel missing : ControlChannel.values()) {
            var partial = uniquePorts();
            partial.remove(missing);
            assertFalse(valid(partial), "Missing channel must prevent confirmation: " + missing);
        }
    }

    @Test
    void throttleBrakeAndOpposingDirectionsCannotSharePhysicalOutputFaces() throws Exception {
        var duplicate = uniquePorts();
        duplicate.put(ControlChannel.BRAKE, duplicate.get(ControlChannel.THROTTLE));
        assertFalse(valid(duplicate), "The brake signal must not share the throttle face");
        duplicate = uniquePorts();
        duplicate.put(ControlChannel.RIGHT, duplicate.get(ControlChannel.LEFT));
        assertFalse(valid(duplicate));
        duplicate = uniquePorts();
        duplicate.put(ControlChannel.REVERSE, duplicate.get(ControlChannel.FORWARD));
        assertFalse(valid(duplicate));
    }

    @Test
    void missingOrInvalidSavedPortBindingsFailClosed() throws Exception {
        assertFalse(valid(null));
        assertFalse(valid(new EnumMap<>(ControlChannel.class)));
        var broken = uniquePorts();
        broken.put(ControlChannel.REVERSE, null);
        assertFalse(valid(broken));
    }

    private static EnumMap<ControlChannel, Direction> uniquePorts() {
        var ports = new EnumMap<ControlChannel, Direction>(ControlChannel.class);
        for (ControlChannel channel : ControlChannel.values())
            ports.put(channel, Direction.values()[channel.ordinal()]);
        return ports;
    }

    private static boolean valid(Map<ControlChannel, Direction> ports) throws Exception {
        return (boolean)
                Class.forName("com.monpai.sailboatmod.transport.control.ControllerRegistration")
                        .getMethod("validControlPorts", Map.class)
                        .invoke(null, ports);
    }
}
