package com.onvif.driver.gateway.device.generic;

import com.inductiveautomation.ignition.gateway.config.ValidationErrors.Builder;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceProfileConfig;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import com.onvif.driver.gateway.util.ValidationUtil;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extension point for the Generic Camera device type.
 * Registers the device type with Ignition, making it appear in the device connection dropdown.
 *
 * Supports IP cameras that provide RTSP, snapshot, or MJPEG URLs
 * but may not support the ONVIF protocol.
 */
public class GenericCameraExtensionPoint extends DeviceExtensionPoint<GenericCameraConfig> {

    public static final String TYPE_ID = "com.onvif.driver.GenericCamera";

    private static final Map<String, GenericCameraDevice> deviceRegistry = new ConcurrentHashMap<>();

    private final Go2RtcManager go2RtcManager;

    public GenericCameraExtensionPoint(Go2RtcManager go2RtcManager) {
        super(
            TYPE_ID,
            "GenericCamera.Meta.DisplayName",
            "GenericCamera.Meta.Description",
            GenericCameraConfig.class
        );
        this.go2RtcManager = go2RtcManager;
    }

    @Override
    protected Device createDevice(
        DeviceContext context,
        DeviceProfileConfig profileConfig,
        GenericCameraConfig deviceConfig) {

        return new GenericCameraDevice(context, deviceConfig, go2RtcManager);
    }

    @Override
    public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
        return Optional.of(
            new ExtensionPointResourceForm(
                DeviceExtensionPoint.DEVICE_RESOURCE_TYPE,
                "Device Connection",
                TYPE_ID,
                SchemaUtil.fromType(DeviceProfileConfig.class),
                SchemaUtil.fromType(GenericCameraConfig.class),
                Set.of()
            )
        );
    }

    @Override
    protected void validate(GenericCameraConfig config, Builder errors) {
        // At least one URL must be provided
        String rtspUrl = config.cameraConnection().rtspUrl();
        String snapshotUrl = config.cameraConnection().snapshotUrl();
        String mjpegUrl = config.cameraConnection().mjpegUrl();

        boolean hasRtsp = rtspUrl != null && !rtspUrl.trim().isEmpty();
        boolean hasSnapshot = snapshotUrl != null && !snapshotUrl.trim().isEmpty();
        boolean hasMjpeg = mjpegUrl != null && !mjpegUrl.trim().isEmpty();

        if (!hasRtsp && !hasSnapshot && !hasMjpeg) {
            errors.check(false, "At least one URL (RTSP, Snapshot, or MJPEG) must be provided");
        }

        // Validate URL formats
        if (hasRtsp && !ValidationUtil.isValidRtspUrl(rtspUrl)) {
            errors.check(false, "Invalid RTSP URL format. Must start with rtsp:// or rtsps://");
        }
        if (hasSnapshot && !ValidationUtil.isValidUrl(snapshotUrl)) {
            errors.check(false, "Invalid Snapshot URL format. Must start with http:// or https://");
        }
        if (hasMjpeg && !ValidationUtil.isValidUrl(mjpegUrl)) {
            errors.check(false, "Invalid MJPEG URL format. Must start with http:// or https://");
        }

        // Validate timeout
        if (config.cameraConnection().timeout() < 1) {
            errors.check(false, "Connection timeout must be at least 1 second");
        }

        // Validate FPS
        int fps = config.streamSettings().defaultFps();
        if (fps < 1 || fps > 30) {
            errors.check(false, "Default frame rate must be between 1 and 30");
        }
    }

    public static void registerDevice(String name, GenericCameraDevice device) {
        deviceRegistry.put(name, device);
    }

    public static void unregisterDevice(String name) {
        deviceRegistry.remove(name);
    }

    public GenericCameraDevice getDevice(String name) {
        return deviceRegistry.get(name);
    }

    public static Map<String, GenericCameraDevice> getAllDevices() {
        return Map.copyOf(deviceRegistry);
    }
}
