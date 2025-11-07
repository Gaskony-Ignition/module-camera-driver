package com.onvif.driver.gateway.device;

import com.inductiveautomation.ignition.gateway.config.ValidationErrors.Builder;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceProfileConfig;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;

import java.util.Optional;
import java.util.Set;

/**
 * Extension point for the ONVIF Driver device.
 * This class registers the device type with Ignition, making it appear in the
 * device connection dropdown list.
 *
 * CRITICAL LEARNING: The 2nd and 3rd parameters to the super() constructor are
 * i18n resource bundle KEYS, not direct text. If you pass direct text, Ignition
 * will treat it as a key and show "¿text?" when it can't find the translation.
 *
 * The resource bundle MUST be registered in the ModuleHook.startup() using:
 * BundleUtil.get().addBundle("BundleName", ExtensionPoint.class, "BundleName");
 */
public class ONVIFDeviceExtensionPoint extends DeviceExtensionPoint<ONVIFDeviceConfig> {

    /**
     * Unique identifier for this device type.
     * This will be used internally by Ignition to identify this device driver.
     */
    public static final String TYPE_ID = "com.onvif.driver.ONVIFDevice";

    /**
     * Constructor registers this device type with Ignition.
     *
     * IMPORTANT: The 2nd and 3rd parameters are i18n resource bundle keys:
     * - "ONVIFDevice.Meta.DisplayName" maps to properties file
     * - "ONVIFDevice.Meta.Description" maps to properties file
     */
    public ONVIFDeviceExtensionPoint() {
        super(
            TYPE_ID,
            "ONVIFDevice.Meta.DisplayName",      // i18n key for display name
            "ONVIFDevice.Meta.Description",      // i18n key for description
            ONVIFDeviceConfig.class
        );
    }

    /**
     * Creates a new device instance when user creates a device connection.
     *
     * @param context Device context provided by Ignition
     * @param profileConfig Profile-level configuration
     * @param deviceConfig Device-specific configuration from user
     * @return New device instance
     */
    @Override
    protected Device createDevice(
        DeviceContext context,
        DeviceProfileConfig profileConfig,
        ONVIFDeviceConfig deviceConfig) {

        return new ONVIFDevice(context, deviceConfig);
    }

    /**
     * Provides the web UI component for device configuration.
     * This generates the configuration form in the Gateway automatically.
     *
     * @param type Component type
     * @return Web UI component for configuration form
     */
    @Override
    public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
        return Optional.of(
            new ExtensionPointResourceForm(
                DeviceExtensionPoint.DEVICE_RESOURCE_TYPE,
                "Device Connection",
                TYPE_ID,
                SchemaUtil.fromType(DeviceProfileConfig.class),
                SchemaUtil.fromType(ONVIFDeviceConfig.class),
                Set.of()
            )
        );
    }

    /**
     * Validates device configuration before saving.
     * Checks that required fields are valid and settings make sense.
     *
     * @param config Device configuration to validate
     * @param errors Error builder for collecting validation errors
     */
    @Override
    protected void validate(ONVIFDeviceConfig config, Builder errors) {
        // Validate device name
        if (config.general().deviceName() == null || config.general().deviceName().trim().isEmpty()) {
            errors.check(false, "Device name is required");
        }

        // Validate IP address
        String ipAddress = config.connection().ipAddress();
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            errors.check(false, "IP address is required");
        } else {
            // Basic IP address validation
            String ipRegex = "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$";
            if (!ipAddress.matches(ipRegex)) {
                errors.check(false, "Invalid IP address format: " + ipAddress);
            }
        }

        // Validate port
        int port = config.connection().port();
        if (port < 1 || port > 65535) {
            errors.check(false, "Port must be between 1 and 65535");
        }

        // Validate credentials
        if (config.connection().username() == null || config.connection().username().trim().isEmpty()) {
            errors.check(false, "Username is required");
        }
        if (config.connection().password() == null || config.connection().password().trim().isEmpty()) {
            errors.check(false, "Password is required");
        }

        // Validate timeout
        if (config.connection().timeout() < 1) {
            errors.check(false, "Connection timeout must be at least 1 second");
        }

        // Validate poll interval
        if (config.onvif().pollInterval() < 1) {
            errors.check(false, "Poll interval must be at least 1 second");
        }
    }
}
