package com.shinpstudio.igoreview;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
public class KataGoCoordinateTest {
    @Test public void topLeft() { assertEquals(0,KataGoClient.parseCoordinate("A9")); }
    @Test public void bottomRight() { assertEquals(80,KataGoClient.parseCoordinate("J1")); }
    @Test public void skipsI() { assertEquals(-1,KataGoClient.parseCoordinate("I3")); }
    @Test public void rejectsPass() { assertEquals(-1,KataGoClient.parseCoordinate("pass")); }
    @Test public void center() { assertEquals(40,KataGoClient.parseCoordinate("E5")); }
}
