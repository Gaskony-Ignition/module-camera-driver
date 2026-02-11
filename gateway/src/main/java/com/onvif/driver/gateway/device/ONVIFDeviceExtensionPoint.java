package com.onvif.driver.gateway.device;

import com.inductiveautomation.ignition.gateway.config.ValidationErrors.Builder;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceProfileConfig;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;

import com.onvif.driver.gateway.stream.Go2RtcManager;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extension point for the ONVIF Camera device type.
 * This class registers the ONVIF device type with Ignition, making it appear in the
 * device connection dropdown list as "ONVIF Camera".
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
     * Registry of all active ONVIF devices.
     * Used by servlets to look up devices by name.
     */
    private static final Map<String, ONVIFDevice> deviceRegistry = new ConcurrentHashMap<>();

    private volatile Go2RtcManager go2RtcManager;

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
     * Updates the go2rtc manager reference. Used when the extension point is created
     * before setup() runs (getDeviceExtensionPoints() can be called first).
     */
    public void setGo2RtcManager(Go2RtcManager go2RtcManager) {
        this.go2RtcManager = go2RtcManager;
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

        return new ONVIFDevice(context, deviceConfig, go2RtcManager);
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
        // NOTE: Device connection name is validated by Ignition's DeviceProfileConfig
        // We only validate module-specific configuration here

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
        if (config.connection().password() == null) {
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

    /**
     * Registers a device in the registry.
     * Called by ONVIFDevice when it starts up.
     *
     * @param name Device name
     * @param device Device instance
     */
    public static void registerDevice(String name, ONVIFDevice device) {
        deviceRegistry.put(name, device);
    }

    /**
     * Unregisters a device from the registry.
     * Called by ONVIFDevice when it shuts down.
     *
     * @param name Device name
     */
    public static void unregisterDevice(String name) {
        deviceRegistry.remove(name);
    }

    /**
     * Gets a device by name.
     * Used by servlets to access device instances.
     *
     * @param name Device name
     * @return Device instance or null if not found
     */
    public ONVIFDevice getDevice(String name) {
        return deviceRegistry.get(name);
    }

    /**
     * Gets all registered devices.
     *
     * @return Map of device names to device instances
     */
    public static Map<String, ONVIFDevice> getAllDevices() {
        return Map.copyOf(deviceRegistry);
    }
}
