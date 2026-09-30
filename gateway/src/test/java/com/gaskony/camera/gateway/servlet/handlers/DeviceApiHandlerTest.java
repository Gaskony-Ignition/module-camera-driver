package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.common.DeviceStatus;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraDevice;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.onvif.DeviceInformation;
import com.gaskony.camera.gateway.onvif.MediaProfile;
import com.gaskony.camera.gateway.onvif.ONVIFClient;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeviceApiHandler — in particular the fix for the device-list
 * timeout: GET /data/camera-driver/devices used to time out with several
 * registered cameras because it fell back to a live ONVIF SOAP call for every
 * device/profile whenever the connect-time cache was cold (e.g. right after a
 * gateway restart while the camera is busy).
 *
 * <p>handleListDevices() must now use the peek-only accessors on
 * {@link CameraDevice} (never touching the network), while
 * handleDeviceStatus() may still use the lazy *Cached() accessors for a
 * single device.
 *
 * <p>CameraExtensionPoint.getAllDevices() is backed by a static registry
 * (see {@code DeviceRegistryTest}), so devices are registered/unregistered
 * directly against it rather than mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceApiHandlerTest {

    @Mock GatewayContext gatewayContext;
    @Mock CameraExtensionPoint cameraExtensionPoint;
    @Mock Go2RtcManager go2RtcManager;
    @Mock AuthenticationManager authManager;
    @Mock RequestContext requestContext;
    @Mock HttpServletResponse response;
    @Mock CameraDevice device;
    @Mock ONVIFClient onvifClient;

    DeviceApiHandler handler;
    StringWriter responseBody;

    private static final String DEVICE_NAME = "FrontPTZ";

    @BeforeEach
    void setUp() throws Exception {
        handler = new DeviceApiHandler(
            gatewayContext,
            cameraExtensionPoint,
            go2RtcManager,
            authManager,
            "3.1.6"
        );

        when(authManager.isAuthenticated(requestContext)).thenReturn(true);

        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));

        when(device.getStatus()).thenReturn(DeviceStatus.RUNNING.displayName());
        when(device.isOnvifAvailable()).thenReturn(true);
        when(device.getClient()).thenReturn(onvifClient);
        when(device.hasPTZ()).thenReturn(false);
        when(device.isGo2RtcStreamRegistered()).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        // Static registry — must not leak a mock device into other test classes.
        CameraExtensionPoint.unregisterDevice(DEVICE_NAME);
    }

    // -----------------------------------------------------------------------
    // handleListDevices — cold cache: peek only, never a live SOAP fallback
    // -----------------------------------------------------------------------

    @Test
    void testHandleListDevices_ColdCache_OmitsFieldsAndNeverCallsLiveCachedAccessors() throws Exception {
        when(device.peekDeviceInformation()).thenReturn(null);
        when(device.peekMediaProfiles()).thenReturn(null);
        CameraExtensionPoint.registerDevice(DEVICE_NAME, device);

        handler.handleListDevices(requestContext, response);

        String body = responseBody.toString();
        assertThat(body).contains("\"success\":true");
        assertThat(body).doesNotContain("manufacturer");
        assertThat(body).doesNotContain("profiles");

        // The whole point of the fix: the list endpoint must never fall back to
        // a live SOAP call when the cache is cold.
        verify(device, never()).getDeviceInformationCached();
        verify(device, never()).getMediaProfilesCached();
        verify(device, never()).getStreamUriCached(anyString());
        verify(device, never()).getSnapshotUriCached(anyString());
        verifyNoInteractions(onvifClient);
    }

    // -----------------------------------------------------------------------
    // handleListDevices — warm cache: peeked values are included
    // -----------------------------------------------------------------------

    @Test
    void testHandleListDevices_WarmCache_IncludesPeekedFields() throws Exception {
        DeviceInformation info = new DeviceInformation("Gaskony", "FrontPTZ-Cam", "1.0.0", "SN1", "HW1");
        MediaProfile profile = new MediaProfile("profile_1", "MainStream");
        profile.setEncoding("H264");
        profile.setWidth(1920);
        profile.setHeight(1080);
        profile.setFrameRate(25);

        when(device.peekDeviceInformation()).thenReturn(info);
        when(device.peekMediaProfiles()).thenReturn(List.of(profile));
        when(device.peekStreamUri("profile_1")).thenReturn("rtsp://camera/stream1");
        when(device.peekSnapshotUri("profile_1")).thenReturn("http://camera/snapshot.jpg");
        CameraExtensionPoint.registerDevice(DEVICE_NAME, device);

        handler.handleListDevices(requestContext, response);

        String body = responseBody.toString();
        assertThat(body).contains("\"manufacturer\":\"Gaskony\"");
        assertThat(body).contains("\"streamUri\":\"rtsp://camera/stream1\"");
        assertThat(body).contains("\"snapshotUri\":\"http://camera/snapshot.jpg\"");

        verify(device, never()).getDeviceInformationCached();
        verify(device, never()).getMediaProfilesCached();
        verify(device, never()).getStreamUriCached(anyString());
        verify(device, never()).getSnapshotUriCached(anyString());
        verifyNoInteractions(onvifClient);
    }

    @Test
    void testHandleListDevices_DisabledDevice_IsSkipped() throws Exception {
        when(device.getStatus()).thenReturn(DeviceStatus.DISABLED.displayName());
        CameraExtensionPoint.registerDevice(DEVICE_NAME, device);

        handler.handleListDevices(requestContext, response);

        String body = responseBody.toString();
        assertThat(body).contains("\"count\":0");
        verifyNoInteractions(onvifClient);
    }

    // -----------------------------------------------------------------------
    // handleDeviceStatus — single device may still use the lazy accessors
    // -----------------------------------------------------------------------

    @Test
    void testHandleDeviceStatus_UsesLazyCachedAccessors_NotPeek() throws Exception {
        when(requestContext.getParameter("name")).thenReturn(DEVICE_NAME);
        when(cameraExtensionPoint.getDevice(DEVICE_NAME)).thenReturn(device);

        DeviceInformation info = new DeviceInformation("Gaskony", "FrontPTZ-Cam", "1.0.0", "SN1", "HW1");
        MediaProfile profile = new MediaProfile("profile_1", "MainStream");
        when(device.getDeviceInformationCached()).thenReturn(info);
        when(device.getMediaProfilesCached()).thenReturn(List.of(profile));
        when(device.getStreamUriCached("profile_1")).thenReturn("rtsp://camera/stream1");
        when(device.getSnapshotUriCached("profile_1")).thenReturn("http://camera/snapshot.jpg");

        handler.handleDeviceStatus(requestContext, response);

        String body = responseBody.toString();
        assertThat(body).contains("\"success\":true");
        assertThat(body).contains("\"manufacturer\":\"Gaskony\"");

        verify(device).getDeviceInformationCached();
        verify(device).getMediaProfilesCached();
        verify(device).getStreamUriCached("profile_1");
        verify(device).getSnapshotUriCached("profile_1");
        // handleDeviceStatus is intentionally allowed to lazily fetch; it must
        // not need the peek variants at all.
        verify(device, never()).peekDeviceInformation();
        verify(device, never()).peekMediaProfiles();
        verify(device, never()).peekStreamUri(anyString());
        verify(device, never()).peekSnapshotUri(anyString());
    }

    @Test
    void testHandleDeviceStatus_UnknownDevice_Returns404() throws Exception {
        when(requestContext.getParameter("name")).thenReturn("NoSuchCamera");
        when(cameraExtensionPoint.getDevice("NoSuchCamera")).thenReturn(null);

        handler.handleDeviceStatus(requestContext, response);

        verify(response).sendError(eq(404), anyString());
    }
}
