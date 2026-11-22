package com.shinpstudio.igoreview;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AppSettingsFontScaleTest {
    @Test public void normalizesTextScale() {
        assertEquals(70,AppSettings.normalizeFontScale(40));
        assertEquals(70,AppSettings.normalizeFontScale(70));
        assertEquals(100,AppSettings.normalizeFontScale(100));
        assertEquals(120,AppSettings.normalizeFontScale(116));
        assertEquals(150,AppSettings.normalizeFontScale(200));
    }
}
