package com.monpai.sailboatmod.client.airport;

import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirportMapInputCaptureTest {
    private final ClaimMapViewport viewport = new ClaimMapViewport(10,20,400,300);
    @Test void outsideDragCancelsCaptureWithoutProducingMutationCoordinates() {
        var input=new AirportMapInputCapture();
        assertTrue(input.begin(100,100,0,viewport));
        assertEquals(AirportMapInputCapture.MotionKind.MOVED,input.drag(110,105,0,viewport).kind());
        var outside=input.drag(500,105,0,viewport);
        assertEquals(AirportMapInputCapture.MotionKind.CANCELLED,outside.kind());
        assertFalse(input.active());
        assertEquals(AirportMapInputCapture.MotionKind.IGNORED,input.drag(110,100,0,viewport).kind());
    }
    @Test void releaseOutsideAndWrongButtonDoNotLeaveCaptureStuck() {
        var input=new AirportMapInputCapture();
        assertTrue(input.begin(100,100,2,viewport));
        assertFalse(input.release(0));
        assertTrue(input.active());
        assertTrue(input.release(2));
        assertFalse(input.active());
    }
    @Test void finiteBoundsAndSingleCaptureAreRequired() {
        var input=new AirportMapInputCapture();
        assertFalse(input.begin(Double.NaN,100,0,viewport));
        assertFalse(input.begin(410,100,0,viewport));
        assertTrue(input.begin(100,100,2,viewport));
        assertFalse(input.begin(100,100,0,viewport));
        assertEquals(AirportMapInputCapture.MotionKind.CANCELLED,input.drag(Double.POSITIVE_INFINITY,100,2,viewport).kind());
        assertFalse(input.active());
    }
    @Test void dragDeltasAreConsecutiveScreenPixels() {
        var input=new AirportMapInputCapture();
        input.begin(100,100,2,viewport);
        var first=input.drag(110,105,2,viewport);
        assertEquals(10,first.deltaX());assertEquals(5,first.deltaY());
        var second=input.drag(113,103,2,viewport);
        assertEquals(3,second.deltaX());assertEquals(-2,second.deltaY());
        input.cancel();assertFalse(input.active());
    }
}
