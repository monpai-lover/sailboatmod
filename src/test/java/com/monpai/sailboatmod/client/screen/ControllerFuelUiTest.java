package com.monpai.sailboatmod.client.screen;

import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener;
import com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket;
import com.monpai.sailboatmod.transport.control.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Actual fuel widgets, snapshots and callbacks; native fuel/power authority is tested separately. */
class ControllerFuelUiTest {
    private static final String PREFIX="screen.sailboatmod.controller.";
    private static final BlockPos POS=new BlockPos(20481000,130,20483000);
    private static final UUID ID=UUID.fromString("8c1bf792-9220-4376-b333-680a95d85a1b");

    @Test void powerPageShowsComputedTripReserveEachEngineAndActualPropellerSeparately() throws Exception {
        var fuel=budget(true,false);var engines=new ListTag();
        engines.add(engine(1,4200,5000,false,"fuel_insufficient"));engines.add(engine(2,8000,5000,true,""));fuel.put("Engines",engines);
        var props=new ListTag();var prop=new CompoundTag();prop.putLong("Position",POS.offset(3,0,0).asLong());prop.putDouble("Rpm",-32);prop.putBoolean("Assembled",true);prop.putBoolean("Moving",true);props.add(prop);fuel.put("Propellers",props);
        var screen=new RegisteredVehicleControllerScreen(data(fuel,false,false));var body=page(screen,"power");var keys=translations(body);
        for(String suffix:List.of("fuel_title","fuel_planned","fuel_return","fuel_reserve","fuel_required","fuel_available","fuel_engine","fuel_propeller","fuel_hint","fuel_shortfall"))assertTrue(keys.contains(PREFIX+suffix),suffix);
        assertTrue(keys.contains(PREFIX+"reason.fuel_insufficient"));assertTrue(keys.contains(PREFIX+"fuel_propeller_running"));
        assertTrue(text(body).stream().anyMatch(s->s.contains("-32.0 RPM")),"Actual propeller RPM must stay distinct from engine generation");
        var summary=RegisteredVehicleControllerScreen.class.getDeclaredField("fuelStatusLabel");summary.setAccessible(true);
        assertEquals(PREFIX+"reason.fuel_insufficient",translation(((Label)summary.get(screen)).getValue()).getKey(),"A healthy second engine cannot turn the overall check green");
    }

    @Test void anUnevaluatedSnapshotOffersCheckAndStartWithoutClaimingZeroFuelOrReady() throws Exception {
        var sent=new ArrayList<RegisteredVehicleControllerActionPacket>();
        var screen=new RegisteredVehicleControllerScreen(data(new CompoundTag(),false,false),sent::add);var body=page(screen,"routes");
        assertTrue(translations(body).contains(PREFIX+"fuel_check_pending"));assertFalse(translations(body).contains(PREFIX+"fuel_ready"));
        click(button(body,"fuel_check_start"));assertEquals(1,sent.size());assertEquals(ControllerAction.START,sent.getFirst().action());
    }

    @Test void departurePreflightAcknowledgesTheStartClickAndCannotSubmitItAgain() throws Exception {
        var sent = new ArrayList<RegisteredVehicleControllerActionPacket>();
        var screen = new RegisteredVehicleControllerScreen(data(new CompoundTag(),false,false),sent::add);
        var body = page(screen,"routes");
        var start = button(body,"fuel_check_start");
        click(start);
        assertEquals(1,sent.size());

        var queued = data(new CompoundTag(),true,false).values();
        queued.putBoolean("DepartureFlightPending",true);
        queued.putString("Status","WAITING_TERRAIN");
        queued.putString("Reason","waiting_airport_terrain");
        screen.update(new ControllerScreenData(POS,ID,queued),false);
        assertTrue(translations(body).contains(PREFIX+"airport_preflight_queued"));
        click(start);
        click(button(body,"airport_preflight_queued"));
        assertEquals(1,sent.size());

        queued.putBoolean("DepartureFlightPending",false);
        queued.putString("Status","RUNNING");
        queued.putString("Reason","");
        queued.put("AirFuel",budget(true,true));
        screen.update(new ControllerScreenData(POS,ID,queued),false);
        assertFalse(translations(body).contains(PREFIX+"airport_preflight_queued"));
        assertNotNull(button(body,"start"));
    }

    @Test void returnPreflightStopsClaimingItIsQueuedWhenPausedByAFailure() throws Exception {
        var screen = new RegisteredVehicleControllerScreen(data(budget(true,true),true,false));
        var body = page(screen,"routes");
        var returning = data(budget(true,true),true,false).values();
        returning.putBoolean("ReturnFlightPending",true);
        returning.putString("Status","WAITING_TERRAIN");
        screen.update(new ControllerScreenData(POS,ID,returning),false);
        assertNotNull(button(body,"airport_preflight_queued"));

        returning.putBoolean("Paused",true);
        returning.putString("Status","FAULT");
        returning.putString("Reason","fuel_insufficient");
        screen.update(new ControllerScreenData(POS,ID,returning),false);
        assertFalse(translations(body).contains(PREFIX+"airport_preflight_queued"));
        assertNotNull(button(body,"start"));
    }

    @Test void aKnownFailedCheckCannotSubmitStartButRefreshCanRecoverIt() throws Exception {
        var sent=new ArrayList<RegisteredVehicleControllerActionPacket>();
        var screen=new RegisteredVehicleControllerScreen(data(budget(true,false),false,false),sent::add);var body=page(screen,"routes");
        click(button(body,"start"));assertTrue(sent.isEmpty());
        screen.update(data(budget(true,true),false,false),false);click(button(body,"start"));
        assertEquals(1,sent.size());assertEquals(ControllerAction.START,sent.getFirst().action());
    }

    @Test void automaticReturnCannotChangeDuringActiveOrPausedTripsEvenThroughAnOldCallback() throws Exception {
        var sent=new ArrayList<RegisteredVehicleControllerActionPacket>();
        var screen=new RegisteredVehicleControllerScreen(data(budget(true,true),false,false),sent::add);var body=page(screen,"routes");
        var toggle=button(body,"auto_return_on");
        screen.update(data(budget(true,true),true,false),false);click(toggle);assertTrue(sent.isEmpty());
        screen.update(data(budget(true,true),false,true),false);click(button(body,"auto_return_on"));assertTrue(sent.isEmpty());
        screen.update(data(budget(true,true),false,false),false);click(button(body,"auto_return_on"));
        assertEquals(1,sent.size());assertEquals(ControllerAction.TOGGLE_AUTO_RETURN,sent.getFirst().action());
    }

    @Test void fuelRefreshUpdatesReadingsWithoutReplacingTheEditedSpeedField() throws Exception {
        var first=budget(true,true);var screen=new RegisteredVehicleControllerScreen(data(first,false,false));var body=page(screen,"power");
        var field=fields(body).getFirst();field.setText("7.",true);
        var second=budget(true,false);second.putLong("AvailableTicks",20);screen.update(data(second,false,false),false);
        assertSame(field,fields(body).getFirst());assertEquals("7.",field.getText());
        assertTrue(translations(body).contains(PREFIX+"reason.fuel_insufficient"));assertFalse(translations(body).contains(PREFIX+"fuel_ready"));
    }

    @Test void durationsUseRealLongTicksAndConservativeWholeSeconds() throws Exception {
        var tag=new CompoundTag();tag.putLong("Ticks",61);
        assertEquals(PREFIX+"fuel_time_seconds",translation(duration(tag,"Ticks",true)).getKey());
        assertEquals(4L,translation(duration(tag,"Ticks",true)).getArgs()[0]);
        assertEquals(3L,translation(duration(tag,"Ticks",false)).getArgs()[0]);
        tag.putLong("Ticks",1220);var minute=translation(duration(tag,"Ticks",false));assertEquals(PREFIX+"fuel_time_minutes",minute.getKey());assertArrayEquals(new Object[]{1L,1L},minute.getArgs());
        tag.putLong("Ticks",Long.MAX_VALUE);assertDoesNotThrow(()->duration(tag,"Ticks",true));
    }

    @Test void absentWrongTypeOrNegativeTicksRemainUnknownInsteadOfDefaultingToZero() throws Exception {
        for(Tag raw:List.of(IntTag.valueOf(20),DoubleTag.valueOf(Double.NaN),DoubleTag.valueOf(Double.POSITIVE_INFINITY),LongTag.valueOf(-1),StringTag.valueOf("20"))) {
            var tag=new CompoundTag();tag.put("Ticks",raw);assertEquals(PREFIX+"fuel_unknown",translation(duration(tag,"Ticks",false)).getKey());
        }
        assertEquals(PREFIX+"fuel_unknown",translation(duration(new CompoundTag(),"Ticks",false)).getKey());
    }

    @Test void anUnreadableEngineAndUnknownReasonStayVisibleWithoutInventedShortfall() throws Exception {
        var fuel=budget(true,false);fuel.putString("Reason","new_unknown_native_reason");var engines=new ListTag();
        var engine=new CompoundTag();engine.putLong("Position",POS.offset(1,0,0).asLong());engine.putString("Reason","fuel_unknown");engines.add(engine);fuel.put("Engines",engines);
        var props=new ListTag();var prop=new CompoundTag();prop.putLong("Position",POS.offset(2,0,0).asLong());prop.putBoolean("Assembled",true);prop.putBoolean("Moving",true);props.add(prop);fuel.put("Propellers",props);
        var body=page(new RegisteredVehicleControllerScreen(data(fuel,false,false)),"power");var keys=translations(body);
        assertTrue(keys.contains(PREFIX+"reason.fuel_unknown"));assertTrue(keys.contains(PREFIX+"fuel_unknown"));
        assertFalse(keys.contains(PREFIX+"fuel_shortfall"));assertFalse(text(body).stream().anyMatch(s->s.contains("0.0 RPM")));
    }

    @Test void nonGuidedControllersDoNotAdvertiseThisFuelProof() throws Exception {
        var values=data(budget(true,true),false,false).values();values.putBoolean("AirRail",false);
        assertFalse(translations(page(new RegisteredVehicleControllerScreen(new ControllerScreenData(POS,ID,values)),"power")).contains(PREFIX+"fuel_title"));
    }

    @Test void aReadyFlagCannotHideMissingEngineTimeReadings() throws Exception {
        var fuel=budget(true,false);var engines=new ListTag();var engine=new CompoundTag();engine.putBoolean("Ready",true);engine.putLong("Position",POS.asLong());engines.add(engine);fuel.put("Engines",engines);
        var body=page(new RegisteredVehicleControllerScreen(data(fuel,false,false)),"power");
        assertFalse(translations(body).contains(PREFIX+"fuel_ready"));
        assertTrue(translations(body).contains(PREFIX+"reason.fuel_unknown"));
    }

    @Test void nativeFuelFailuresRemainSpecificInsteadOfFallingBackToAnUnknownReading() throws Exception {
        var method=RegisteredVehicleControllerScreen.class.getDeclaredMethod("fuelReason",String.class);method.setAccessible(true);
        for(String reason:nativeReasons())assertEquals(PREFIX+"reason."+reason,translation((Component)method.invoke(null,reason)).getKey());
        assertEquals(PREFIX+"reason.fuel_unknown",translation((Component)method.invoke(null,"unrecognized_native_reason")).getKey());
    }

    @Test void fuelCopyAndEveryAgreedFailureHaveEnglishAndChineseText() throws Exception {
        var folder=Path.of("src/main/resources/assets/sailboatmod/lang");
        for(String language:List.of("en_us","zh_cn")) {
            var json=JsonParser.parseString(Files.readString(folder.resolve(language+".json"))).getAsJsonObject();
            for (String key : List.of("airport_preflight_queued", "reason.airport_preflight_queued",
                    "reason.waiting_airport_metadata", "reason.waiting_airport_terrain"))
                assertTrue(json.has(PREFIX+key)&&!json.get(PREFIX+key).getAsString().isBlank(),language+":"+key);
            for(String key:List.of("fuel_title","fuel_check_pending","fuel_check_start","fuel_ready","fuel_unknown","fuel_planned","fuel_return","fuel_reserve","fuel_required","fuel_available","fuel_engine","fuel_propeller","fuel_propeller_running","fuel_shortfall","fuel_hint","fuel_time_seconds","fuel_time_minutes","reason.fuel_insufficient","reason.fuel_empty","reason.fuel_engine_missing","reason.fuel_unknown","reason.fuel_power_waiting","reason.fuel_network_unavailable","reason.fuel_network_foreign","reason.fuel_duration_unavailable","reason.fuel_no_engines","reason.fuel_power_not_ready","reason.fuel_budget_invalid","reason.fuel_budget_overflow"))
                assertTrue(json.has(PREFIX+key)&&!json.get(PREFIX+key).getAsString().isBlank(),language+":"+key);
            var hint=json.get(PREFIX+"fuel_hint").getAsString();assertTrue(hint.contains(language.equals("zh_cn")?"暂停":"paus"));
            for(String reason:nativeReasons())assertTrue(json.has(PREFIX+"reason."+reason)&&!json.get(PREFIX+"reason."+reason).getAsString().isBlank(),language+":"+reason);
        }
    }

    private static List<String> nativeReasons(){return List.of("fuel_controller_not_loaded","fuel_native_abi_unavailable","fuel_native_state_unavailable","fuel_body_unavailable","fuel_body_not_loaded","fuel_body_changed","fuel_observation_limit","fuel_unsupported_engine","fuel_unsupported_propeller","fuel_unsupported_fuel","fuel_unsupported_source","fuel_foreign_source","fuel_foreign_propeller_part","fuel_network_unavailable","fuel_network_unloaded","fuel_invalid_inventory","fuel_inventory_changed","fuel_empty","fuel_waiting_power","fuel_overstressed","fuel_propeller_unassembled","fuel_propeller_stopped","fuel_engine_missing","fuel_propeller_missing","fuel_world_frozen");}

    private static CompoundTag budget(boolean evaluated,boolean ready) {
        var f=new CompoundTag();f.putBoolean("Evaluated",evaluated);f.putBoolean("Ready",ready);f.putString("Reason",ready?"":"fuel_insufficient");
        f.putLong("PlannedTicks",3000);f.putLong("ReturnTicks",1000);f.putLong("ReserveTicks",1200);f.putLong("RequiredTicks",5200);f.putLong("AvailableTicks",ready?6000:4200);return f;
    }
    private static CompoundTag engine(int x,long available,long required,boolean ready,String reason) {var t=new CompoundTag();t.putLong("Position",POS.offset(x,0,0).asLong());t.putLong("AvailableTicks",available);t.putLong("RequiredTicks",required);t.putBoolean("Ready",ready);t.putString("Reason",reason);return t;}
    private static ControllerScreenData data(CompoundTag fuel,boolean active,boolean paused) {var t=new CompoundTag();t.putString("Name","Aircraft");t.putString("Kind","AIR");t.putString("AircraftClass","FIXED_WING");t.putString("Status","IDLE");t.putBoolean("AirRail",true);t.putBoolean("AutoReturn",true);t.putBoolean("Active",active);t.putBoolean("Paused",paused);t.putDouble("MaxSpeed",20);t.put("AirFuel",fuel);return new ControllerScreenData(POS,ID,t);}
    private static UIElement page(RegisteredVehicleControllerScreen s,String name) throws Exception {var root=s.buildBody();var page=RegisteredVehicleControllerScreen.class.getDeclaredField("page");page.setAccessible(true);page.set(s,name);var render=RegisteredVehicleControllerScreen.class.getDeclaredMethod("renderPage");render.setAccessible(true);render.invoke(s);return root;}
    private static Component duration(CompoundTag tag,String key,boolean ceiling) throws Exception {var method=RegisteredVehicleControllerScreen.class.getDeclaredMethod("fuelDuration",CompoundTag.class,String.class,boolean.class);method.setAccessible(true);return (Component)method.invoke(null,tag,key,ceiling);}
    private static TranslatableContents translation(Component value){return assertInstanceOf(TranslatableContents.class,value.getContents());}
    private static void click(Button b)throws Exception {var field=Button.class.getDeclaredField("onClick");field.setAccessible(true);((UIEventListener)field.get(b)).handleEvent(null);}
    private static Button button(UIElement root,String suffix){var found=new ArrayList<Button>();walk(root,e->{if(e instanceof Button b&&b.text.getText().getContents() instanceof TranslatableContents t&&t.getKey().equals(PREFIX+suffix))found.add(b);});assertFalse(found.isEmpty(),suffix);return found.getFirst();}
    private static List<TextField> fields(UIElement root){var found=new ArrayList<TextField>();walk(root,e->{if(e instanceof TextField f)found.add(f);});return found;}
    private static Set<String> translations(UIElement root){var out=new HashSet<String>();walk(root,e->{if(e instanceof Label l)translations(l.getValue(),out);if(e instanceof TextElement t)translations(t.getText(),out);});return out;}
    private static void translations(Component c,Set<String> out){if(c.getContents() instanceof TranslatableContents t){out.add(t.getKey());for(Object a:t.getArgs())if(a instanceof Component nested)translations(nested,out);}c.getSiblings().forEach(s->translations(s,out));}
    private static List<String> text(UIElement root){var out=new ArrayList<String>();walk(root,e->{if(e instanceof Label l)out.add(l.getValue().getString());if(e instanceof TextElement t)out.add(t.getText().getString());});return out;}
    private static void walk(UIElement e,Consumer<UIElement> visit){visit.accept(e);e.getChildren().forEach(c->walk(c,visit));}
}
