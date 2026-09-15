package com.gaskony.camera.gateway.device;

import com.gaskony.camera.gateway.onvif.DeviceInformation;
import com.gaskony.camera.gateway.onvif.MediaProfile;
import com.gaskony.camera.gateway.onvif.ONVIFClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

/**
 * Regression tests for the device-list timeout fix: GET /data/camera-driver/devices
 * used to time out ("signal timed out" in the web UI) with several registered
 * cameras whenever the ONVIF caches were cold (e.g. right after a gateway restart
 * while the camera is busy), because the *Cached() accessors below fell back to a
 * live SOAP call on a cache miss and DeviceApiHandler.handleListDevices() called
 * them for every device and every profile.
 *
 * <p>The fix adds peek-only accessors ({@code peekDeviceInformation()},
 * {@code peekMediaProfiles()}, {@code peekStreamUri()}, {@code peekSnapshotUri()})
 * that read the connect-time cache and NEVER perform network I/O. These tests
 * prove that contract: cold caches yield {@code null} without touching the ONVIF
 * client, and warm caches yield the cached value — in both cases with zero
 * interaction with the (possibly slow/busy) camera.
 *
 * <p>A real {@link CameraDevice} can't easily be constructed in a unit test — its
 * constructor requires a live {@code DeviceContext}/{@code OpcUaServer} (see
 * {@link CameraProbeAsyncTest}'s note on the same limitation). Instead we obtain
 * an instance via Mockito with {@code CALLS_REAL_METHODS} as the default answer:
 * Mockito creates the instance via Objenesis (bypassing the constructor, so no
 * OPC-UA server is needed) but delegates unstubbed method calls — including the
 * peek accessors under test — to the real method bodies, which read the real
 * instance fields set up below via reflection.
 */
class CameraDeviceTest {

    private CameraDevice device;
    private ONVIFClient onvifClientMock;

    @BeforeEach
    void setUp() throws Exception {
        device = mock(CameraDevice.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        onvifClientMock = mock(ONVIFClient.class);

        // Field initializers (e.g. `= new ConcurrentHashMap<>()`) are compiled into
        // the constructor, which Objenesis-created mocks never run — so the map
        // fields start out null and must be seeded before peekStreamUri/peekSnapshotUri
        // (which call .get() on them) can be exercised.
        setField("cachedStreamUris", new ConcurrentHashMap<String, String>());
        setField("cachedSnapshotUris", new ConcurrentHashMap<String, String>());
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field f = CameraDevice.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(device, value);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, String> getMapField(String fieldName) throws Exception {
        Field f = CameraDevice.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (ConcurrentHashMap<String, String>) f.get(device);
    }

    // -----------------------------------------------------------------------
    // Cold cache — peek must return null and never touch the ONVIF client
    // -----------------------------------------------------------------------

    @Test
    void testPeekDeviceInformation_ColdCache_ReturnsNullWithoutTouchingClient() throws Exception {
        setField("onvifClient", onvifClientMock);

        DeviceInformation result = device.peekDeviceInformation();

        assertThat(result).isNull();
        verifyNoInteractions(onvifClientMock);
    }

    @Test
    void testPeekMediaProfiles_ColdCache_ReturnsNullWithoutTouchingClient() throws Exception {
        setField("onvifClient", onvifClientMock);

        List<MediaProfile> result = device.peekMediaProfiles();

        assertThat(result).isNull();
        verifyNoInteractions(onvifClientMock);
    }

    @Test
    void testPeekStreamUri_ColdCache_ReturnsNullWithoutTouchingClient() throws Exception {
        setField("onvifClient", onvifClientMock);

        String result = device.peekStreamUri("profile_1");

        assertThat(result).isNull();
        verifyNoInteractions(onvifClientMock);
    }

    @Test
    void testPeekSnapshotUri_ColdCache_ReturnsNullWithoutTouchingClient() throws Exception {
        setField("onvifClient", onvifClientMock);

        String result = device.peekSnapshotUri("profile_1");

        assertThat(result).isNull();
        verifyNoInteractions(onvifClientMock);
    }

    @Test
    void testPeek_ColdCache_EvenWithNoOnvifClientAtAll_ReturnsNullNotThrows() {
        // onvifClient left null (as it would be before ONVIF ever connects)
        assertThat(device.peekDeviceInformation()).isNull();
        assertThat(device.peekMediaProfiles()).isNull();
        assertThat(device.peekStreamUri("token")).isNull();
        assertThat(device.peekSnapshotUri("token")).isNull();
    }

    // -----------------------------------------------------------------------
    // Warm cache — peek returns the cached value, still without touching the client
    // -----------------------------------------------------------------------

    @Test
    void testPeekDeviceInformation_WarmCache_ReturnsCachedValue() throws Exception {
        DeviceInformation cached = new DeviceInformation("Gaskony", "FrontPTZ", "1.2.3", "SN123", "HW1");
        setField("cachedDeviceInfo", cached);
        setField("onvifClient", onvifClientMock);

        DeviceInformation result = device.peekDeviceInformation();

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(onvifClientMock);
    }

    @Test
    void testPeekMediaProfiles_WarmCache_ReturnsCachedValue() throws Exception {
        MediaProfile profile = new MediaProfile("token1", "MainStream");
        List<MediaProfile> cached = List.of(profile);
        setField("cachedMediaProfiles", cached);
        setField("onvifClient", onvifClientMock);

        List<MediaProfile> result = device.peekMediaProfiles();

        // Defensive DEEP copy: equal by content, but a distinct list instance,
        // AND distinct element instances — MediaProfile is setter-mutable, so
        // callers must not be able to reach (and mutate) the cached objects.
        assertThat(result).isEqualTo(cached).isNotSameAs(cached);
        assertThat(result.get(0)).isNotSameAs(cached.get(0));
        verifyNoInteractions(onvifClientMock);
    }

    @Test
    void testGetMediaProfilesCached_WarmCache_ReturnsDeepCopy() throws Exception {
        MediaProfile profile = new MediaProfile("token1", "MainStream");
        List<MediaProfile> cached = List.of(profile);
        setField("cachedMediaProfiles", cached);
        setField("onvifClient", onvifClientMock);

        List<MediaProfile> result = device.getMediaProfilesCached();

        // Same hazard as peekMediaProfiles() — getMediaProfilesCached() previously
        // returned the live cached list reference with no copy at all.
        assertThat(result).isEqualTo(cached).isNotSameAs(cached);
        assertThat(result.get(0)).isNotSameAs(cached.get(0));
        verifyNoInteractions(onvifClientMock);
    }

    @Test
    void testPeekStreamUri_WarmCache_ReturnsCachedValue() throws Exception {
        getMapField("cachedStreamUris").put("profile_1", "rtsp://camera/stream1");
        setField("onvifClient", onvifClientMock);

        String result = device.peekStreamUri("profile_1");

        assertThat(result).isEqualTo("rtsp://camera/stream1");
        verifyNoInteractions(onvifClientMock);
    }

    @Test
    void testPeekSnapshotUri_WarmCache_ReturnsCachedValue() throws Exception {
        getMapField("cachedSnapshotUris").put("profile_1", "http://camera/snapshot.jpg");
        setField("onvifClient", onvifClientMock);

        String result = device.peekSnapshotUri("profile_1");

        assertThat(result).isEqualTo("http://camera/snapshot.jpg");
        verifyNoInteractions(onvifClientMock);
    }

    @Test
    void testPeekStreamUri_WarmCache_UnknownToken_ReturnsNull() throws Exception {
        getMapField("cachedStreamUris").put("profile_1", "rtsp://camera/stream1");

        assertThat(device.peekStreamUri("profile_unknown")).isNull();
    }
}
