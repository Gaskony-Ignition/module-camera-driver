package com.onvif.driver.gateway.device.generic;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

/**
 * Configuration for the Generic Camera device type.
 * Accepts manual RTSP/snapshot/MJPEG URLs for non-ONVIF IP cameras.
 *
 * At least one URL (RTSP, snapshot, or MJPEG) must be provided.
 * Credentials are optional and used for Basic Auth on HTTP URLs
 * and embedded in RTSP URLs when connecting via go2rtc.
 */
public record GenericCameraConfig(General general, CameraConnection cameraConnection, StreamSettings streamSettings) {

    /**
     * General device settings.
     */
    public record General(
        @FormCategory("GENERAL")
        @Label("Enabled")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Enable or disable this device")
        @DefaultValue("true")
        boolean enabled
    ) {}

    /**
     * Camera connection settings.
     */
    public record CameraConnection(
        @FormCategory("CAMERA_CONNECTION")
        @Label("RTSP URL")
        @FormField(FormFieldType.TEXT)
        @Description("RTSP stream URL (e.g., rtsp://192.168.1.50:554/stream1). Used for live streaming via go2rtc.")
        String rtspUrl,

        @FormCategory("CAMERA_CONNECTION")
        @Label("Snapshot URL")
        @FormField(FormFieldType.TEXT)
        @Description("HTTP URL for JPEG snapshots (e.g., http://192.168.1.50/snapshot.jpg)")
        String snapshotUrl,

        @FormCategory("CAMERA_CONNECTION")
        @Label("MJPEG URL")
        @FormField(FormFieldType.TEXT)
        @Description("MJPEG stream URL (e.g., http://192.168.1.50/mjpeg). Used as fallback if RTSP/go2rtc is unavailable.")
        String mjpegUrl,

        @FormCategory("CAMERA_CONNECTION")
        @Label("Username")
        @FormField(FormFieldType.TEXT)
        @Description("Camera authentication username (optional)")
        String username,

        @FormCategory("CAMERA_CONNECTION")
        @Label("Password")
        @FormField(FormFieldType.SECRET)
        @Description("Camera authentication password (optional)")
        SecretConfig password,

        @FormCategory("CAMERA_CONNECTION")
        @Label("Connection Timeout (seconds)")
        @FormField(FormFieldType.NUMBER)
        @Description("Timeout for HTTP connections to the camera")
        @DefaultValue("10")
        int timeout
    ) {}

    /**
     * Stream settings.
     */
    public record StreamSettings(
        @FormCategory("STREAM_SETTINGS")
        @Label("Default Frame Rate (FPS)")
        @FormField(FormFieldType.NUMBER)
        @Description("Default frame rate for snapshot-polling MJPEG streams (1-30)")
        @DefaultValue("15")
        int defaultFps
    ) {}
}
