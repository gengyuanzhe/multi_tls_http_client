package uds.osc.retrieve.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DeviceKeyTest {
    @Test
    void equals_sameValues_true() {
        DeviceKey a = new DeviceKey("dev1", true);
        DeviceKey b = new DeviceKey("dev1", true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
    @Test
    void equals_differentDeviceId_false() {
        assertNotEquals(new DeviceKey("dev1", true), new DeviceKey("dev2", true));
    }
    @Test
    void equals_differentVerifyCert_false() {
        assertNotEquals(new DeviceKey("dev1", true), new DeviceKey("dev1", false));
    }
    @Test
    void mapKey_consistency() {
        var map = new java.util.HashMap<DeviceKey, String>();
        map.put(new DeviceKey("dev1", true), "ssl");
        assertEquals("ssl", map.get(new DeviceKey("dev1", true)));
        assertNull(map.get(new DeviceKey("dev1", false)));
    }
}
