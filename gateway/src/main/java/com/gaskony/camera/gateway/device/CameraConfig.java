package com.gaskony.camera.gateway.device;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Required;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

/**
 * Unified configuration for the Camera device type.
 * Replaces both ONVIFDeviceConfig and GenericCameraConfig.
 *
 * Minimal required fields: just IP address.
 * The module auto-detects ONVIF, RTSP, snapshot, and MJPEG capabilities.
 * Advanced URL overrides let users bypass auto-detection when needed.
 */
public record CameraConfig(General general, Connection connection, Streams streams, Advanced advanced) {

    public record General(
        @FormCategory("GENERAL")
        @Label("Enabled")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Enable or disable this device")
        @DefaultValue("true")
        boolean enabled
    ) {}

    public record Connection(
        @FormCategory("CONNECTION")
        @Label("IP Address")
        @FormField(FormFieldType.TEXT)
        @Description("IP address of the camera (e.g., 192.168.1.100)")
        @Required
        String ipAddress,

        @FormCategory("CONNECTION")
        @Label("Port")
        @FormField(FormFieldType.NUMBER)
        @Description("Camera port (default: 80 for HTTP, 443 for HTTPS)")
        @DefaultValue("80")
        int port,

        @FormCategory("CONNECTION")
        @Label("Username")
        @FormField(FormFieldType.TEXT)
        @Description("Camera authentication username (optional)")
        String username,

        @FormCategory("CONNECTION")
        @Label("Password")
        @FormField(FormFieldType.SECRET)
        @Description("Camera authentication password (optional)")
        SecretConfig password,

        @FormCategory("CONNECTION")
        @Label("Connection Timeout (seconds)")
        @FormField(FormFieldType.NUMBER)
        @Description("Timeout for connecting to the camera")
        @DefaultValue("10")
        int timeout
    ) {}

    public record Streams(
        @FormCategory("STREAMS")
        @Label("RTSP Stream")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Enable RTSP video streaming (auto-detected on port 554)")
        @DefaultValue("true")
        boolean enableRtsp,

        @FormCategory("STREAMS")
        @Label("Snapshot")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Enable HTTP snapshot capture")
        @DefaultValue("true")
        boolean enableSnapshot,

        @FormCategory("STREAMS")
        @Label("MJPEG Stream")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Enable MJPEG video streaming")
        @DefaultValue("true")
        boolean enableMjpeg,

        @FormCategory("STREAMS")
        @Label("ONVIF / PTZ")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Enable ONVIF protocol for PTZ control and service discovery")
        @DefaultValue("true")
        boolean enableOnvif
    ) {}

    public record Advanced(
        @FormCategory("ADVANCED")
        @Label("RTSP URL Override")
        @FormField(FormFieldType.TEXT)
        @Description("Override RTSP stream URL (leave empty for auto-detection via ONVIF)")
        String rtspUrl,

        @FormCategory("ADVANCED")
        @Label("Snapshot URL Override")
        @FormField(FormFieldType.TEXT)
        @Description("Override snapshot URL (leave empty for auto-detection via ONVIF)")
        String snapshotUrl,

        @FormCategory("ADVANCED")
        @Label("MJPEG URL Override")
        @FormField(FormFieldType.TEXT)
        @Description("Override MJPEG stream URL (leave empty for auto-detection)")
        String mjpegUrl,

        @FormCategory("ADVANCED")
        @Label("Use HTTPS")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Connect using HTTPS instead of HTTP")
        @DefaultValue("false")
        boolean useHttps,

        @FormCategory("ADVANCED")
        @Label("SSL/TLS Validation Mode")
        @FormField(FormFieldType.SELECT)
        @Description("Certificate validation mode for HTTPS connections (STRICT recommended for production)")
        @DefaultValue("STRICT")
        SslValidationMode sslValidationMode,

        @FormCategory("ADVANCED")
        @Label("Poll Interval (seconds)")
        @FormField(FormFieldType.NUMBER)
        @Description("How often to poll the camera for data updates (ONVIF only, 0 to disable)")
        @DefaultValue("5")
        int pollInterval
    ) {}

    /**
     * SSL/TLS certificate validation modes.
     */
    public enum SslValidationMode {
        STRICT("strict", "Strict - Full certificate validation (production)"),
        TRUST_FIRST_USE("trust_first", "Trust First Use - Accept and pin self-signed on first connection"),
        INSECURE("insecure", "Insecure - Accept any certificate (development only)");

        private final String key;
        private final String displayName;

        SslValidationMode(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        public String getKey() { return key; }
        public String getDisplayName() { return displayName; }

        @Override
        public String toString() { return displayName; }
    }
}
