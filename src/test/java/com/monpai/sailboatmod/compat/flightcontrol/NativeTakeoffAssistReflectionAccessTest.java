package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.block.entity.HiddenTakeoffCalibrationFixture;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class NativeTakeoffAssistReflectionAccessTest {
    private static Object readAccessor(Object owner, String name) throws ReflectiveOperationException {
        Method call = NativeTakeoffAssistReflection.class.getDeclaredMethod("call", Object.class, String.class);
        call.setAccessible(true);
        return call.invoke(null,owner,name);
    }
    private static String failure(Throwable cause) throws ReflectiveOperationException {
        Method failure = NativeTakeoffAssist.class.getDeclaredMethod("failure",Throwable.class);
        failure.setAccessible(true);
        return (String)failure.invoke(null,cause);
    }
    @Test void configurationAccessorsOnPackagePrivateRecordsAreReadableAcrossPackages() {
        Object calibration = assertDoesNotThrow(() -> readAccessor(HiddenTakeoffCalibrationFixture.configuration(),"calibration"));
        assertEquals(Boolean.TRUE,assertDoesNotThrow(() -> readAccessor(calibration,"liveVerified")));
    }
    @Test void anObservationFailurePreservesItsReflectionExceptionType() throws ReflectiveOperationException {
        assertEquals("native_observation_unavailable:IllegalAccessException",failure(new IllegalAccessException("hidden owner")));
    }
    @Test void nestedReflectionWrappersPreserveTheUnderlyingTypeAndSpecificNativeReason() throws ReflectiveOperationException {
        assertEquals("native_observation_unavailable:NoSuchMethodException",failure(new InvocationTargetException(
                new InvocationTargetException(new NoSuchMethodException("calibration")))));
        assertEquals("calibration_lost",failure(new InvocationTargetException(new IllegalStateException("calibration_lost"))));
    }
}
