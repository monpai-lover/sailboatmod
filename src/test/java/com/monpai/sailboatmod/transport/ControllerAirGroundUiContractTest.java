package com.monpai.sailboatmod.transport;

import com.google.gson.JsonParser;
import com.monpai.sailboatmod.transport.control.ControllerAction;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Static client wiring and enum wire-compatibility checks; actual Minecraft UI is a separate acceptance. */
class ControllerAirGroundUiContractTest {
    @Test void groundActionsAppendWithoutChangingAnyExistingPacketOrdinal() {
        List<String> existing=List.of("REFRESH","REGISTER","UNREGISTER","RENAME","ADD_CARGO","REMOVE_CARGO","SET_THROTTLE_TARGET",
                "SET_STEERING_TARGET","CLEAR_NATIVE_TARGETS","SET_PORT","CONFIRM_PORTS","TOGGLE_INVERTED","SET_SPEED","TOGGLE_REVERSE",
                "BIND_NEAREST_TERMINAL","IMPORT_ROUTES","SELECT_ROUTE","START","PAUSE","RESUME","STOP","TOGGLE_AUTO_RETURN","TOGGLE_AUTO_UNLOAD",
                "SET_AIRCRAFT_CLASS","SET_FLIGHT_TERMINAL");
        List<String> actual=Arrays.stream(ControllerAction.values()).map(Enum::name).toList();
        assertEquals(existing,actual.subList(0,existing.size()));
        assertEquals(List.of("ADD_GROUND_WHEEL","REMOVE_GROUND_WHEEL","SET_GROUND_CALIBRATION","CONFIRM_GROUND_CALIBRATION","CLEAR_GROUND_CALIBRATION","TOGGLE_PARKING_BRAKE"),actual.subList(existing.size(),existing.size()+6));
        assertEquals(List.of("TOGGLE_TAKEOFF_ASSIST","SET_TAKEOFF_ASSIST_ACCELERATION"),actual.subList(existing.size()+6,existing.size()+8));
    }
    @Test void airPowerWiresNativeGroundControlsAndKeepsDraftsOutsideRefreshUpdates() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/screen/RegisteredVehicleControllerScreen.java"));
        assertTrue(source.contains("renderAirGroundPower(values)"));
        for(String action:List.of("ADD_GROUND_WHEEL","REMOVE_GROUND_WHEEL","SET_GROUND_CALIBRATION","CONFIRM_GROUND_CALIBRATION","CLEAR_GROUND_CALIBRATION"))
            assertTrue(source.contains("ControllerAction."+action),action);
        for(String key:List.of("GroundWheels","GroundCalibration","GroundCalibrated","GroundCapability","GroundPhase","GroundPoint","SourceStand","TargetStand"))
            assertTrue(source.contains('"'+key+'"'),key);
        assertTrue(source.contains("ControlChannel.LEFT, ControlChannel.RIGHT, ControlChannel.BRAKE"));
        String update=source.substring(source.indexOf("public void update("),source.indexOf("private Component statusText()"));
        assertFalse(update.contains("groundCalibrationDraft ="));assertFalse(update.contains("groundWheelDraft ="));assertFalse(update.contains("groundThrottleDraft ="));
    }
    @Test void fixedWingAirportBindingExplainsTheActualStandInsteadOfNearestTerminal() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/screen/RegisteredVehicleControllerScreen.java"));
        assertTrue(source.contains("bind_stand_airport"),"Fixed-wing binding needs its own stand-specific button label");
        Path lang=Path.of("src/main/resources/assets/sailboatmod/lang");
        var en=JsonParser.parseString(Files.readString(lang.resolve("en_us.json"))).getAsJsonObject();
        var zh=JsonParser.parseString(Files.readString(lang.resolve("zh_cn.json"))).getAsJsonObject();
        assertEquals("绑定所在机位的机场",zh.get("screen.sailboatmod.controller.bind_stand_airport").getAsString());
        assertFalse(en.get("screen.sailboatmod.controller.bind_stand_airport").getAsString().isBlank());
    }
    @Test void everyGroundControlLabelHasMatchingEnglishAndChineseText() throws Exception {
        Path lang=Path.of("src/main/resources/assets/sailboatmod/lang");
        var en=JsonParser.parseString(Files.readString(lang.resolve("en_us.json"))).getAsJsonObject();
        var zh=JsonParser.parseString(Files.readString(lang.resolve("zh_cn.json"))).getAsJsonObject();
        assertEquals(en.keySet(),zh.keySet());
        String source=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/screen/RegisteredVehicleControllerScreen.java"));
        var matcher=Pattern.compile("(?:t|button)\\(\"(ground_[^\"]+)\"").matcher(source);
        int labels=0;while(matcher.find()){String key="screen.sailboatmod.controller."+matcher.group(1);assertTrue(en.has(key),key);assertTrue(zh.has(key),key);assertFalse(en.get(key).getAsString().isBlank());assertFalse(zh.get(key).getAsString().isBlank());labels++;}
        assertTrue(labels>=10,"ground controls need visible translated labels and instructions");
    }
}
