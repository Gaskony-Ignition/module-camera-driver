package com.onvif.driver.gateway.device;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Required;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

/**
 * Configuration for the ONVIF Driver device.
 * Uses modern Java records approach with form annotations for auto-generated UI.
 *
 * LEARNING: FormFieldType options:
 * - TEXT: Simple text input
 * - TEXTAREA: Multi-line text area
 * - NUMBER: Numeric input
 * - SELECT: Dropdown (requires enum)
 * - CHECKBOX: Boolean checkbox
 * - FILE: File upload button (NOT file browser, actual upload)
 * - SECRET: Password field (masked)
 */
public record ONVIFDeviceConfig(General general, Connection connection, ONVIFSettings onvif) {

    /**
     * General device settings.
     */
    public record General(
        @FormCategory("GENERAL")
        @Label("Device Name")
        @FormField(FormFieldType.TEXT)
        @Description("Name of the ONVIF device connection")
        @Required
        String deviceName,

        @FormCategory("GENERAL")
        @Label("Enabled")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Enable or disable this device")
        @DefaultValue("true")
        boolean enabled
    ) {}

    /**
     * Network connection settings.
     */
    public record Connection(
        @FormCategory("CONNECTION")
        @Label("IP Address")
        @FormField(FormFieldType.TEXT)
        @Description("IP address of the ONVIF device (e.g., 192.168.1.100)")
        @Required
        String ipAddress,

        @FormCategory("CONNECTION")
        @Label("Port")
        @FormField(FormFieldType.NUMBER)
        @Description("ONVIF service port (default: 80 for HTTP, 443 for HTTPS)")
        @DefaultValue("80")
        @Required
        int port,

        @FormCategory("CONNECTION")
        @Label("Username")
        @FormField(FormFieldType.TEXT)
        @Description("ONVIF authentication username")
        @Required
        String username,

        @FormCategory("CONNECTION")
        @Label("Password")
        @FormField(FormFieldType.SECRET)
        @Description("ONVIF authentication password")
        @Required
        String password,

        @FormCategory("CONNECTION")
        @Label("Use HTTPS")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Connect using HTTPS instead of HTTP")
        @DefaultValue("false")
        boolean useHttps,

        @FormCategory("CONNECTION")
        @Label("Connection Timeout (seconds)")
        @FormField(FormFieldType.NUMBER)
        @Description("Timeout for connecting to the device")
        @DefaultValue("10")
        int timeout
    ) {}

    /**
     * ONVIF-specific settings.
     */
    public record ONVIFSettings(
        @FormCategory("ONVIF")
        @Label("Auto-discover Services")
        @FormField(FormFieldType.CHECKBOX)
        @Description("Automatically discover available ONVIF services")
        @DefaultValue("true")
        boolean autoDiscover,

        @FormCategory("ONVIF")
        @Label("Poll Interval (seconds)")
        @FormField(FormFieldType.NUMBER)
        @Description("How often to poll device for data updates")
        @DefaultValue("5")
        int pollInterval,

        @FormCategory("ONVIF")
        @Label("Service Type")
        @FormField(FormFieldType.SELECT)
        @Description("Type of ONVIF service to connect to")
        @DefaultValue("DEVICE")
        ServiceType serviceType
    ) {}

    /**
     * ONVIF service types.
     */
    public enum ServiceType {
        DEVICE("device", "Device Management"),
        MEDIA("media", "Media Service"),
        PTZ("ptz", "PTZ Control"),
        IMAGING("imaging", "Imaging Settings"),
        ANALYTICS("analytics", "Analytics");

        private final String key;
        private final String displayName;

        ServiceType(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        public String getKey() {
            return key;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
