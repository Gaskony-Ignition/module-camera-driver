package com.gaskony.camera.gateway.device;

import com.inductiveautomation.ignition.gateway.config.ValidationErrors.Builder;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceProfileConfig;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy alias extension point for device profiles created under the pre-v3.0.0
 * type ID {@code com.onvif.driver.Camera}.
 *
 * <p>Background. The Camera Driver module renamed its Java packages from
 * {@code com.onvif.driver.*} to {@code com.gaskony.camera.*} in v3.0.0 as part
 * of the suite-wide Gaskony rename (§10 #6). The Ignition OPC-UA device-driver
 * subsystem stores each device profile with the extension-point type ID baked
 * into the persisted JSON; profiles created before v3.0.0 carry
 * {@code "profile.type": "com.onvif.driver.Camera"}. Without this alias, those
 * profiles would fail to deserialize on startup with
 * {@code "Extension point 'com.onvif.driver.Camera' not found"} and the device
 * would silently disappear from OPC-UA until the operator manually recreated
 * it via the Gateway UI.</p>
 *
 * <p>What this class does. It registers a second {@link DeviceExtensionPoint}
 * with the legacy type ID, delegating device creation to the same
 * {@link CameraDevice} class as the new {@link CameraExtensionPoint}. Existing
 * profiles continue to work transparently — the customer's tag bindings,
 * device names, and OPC-UA address spaces are preserved.</p>
 *
 * <p>What this class does NOT do. The legacy form is intentionally hidden
 * from the Gateway "Add Device" dropdown ({@link #getWebUiComponent} returns
 * {@link Optional#empty()}), so new profiles can only be created under the
 * new type ID. The alias is therefore one-way: it accepts legacy profiles
 * but doesn't perpetuate the old naming.</p>
 *
 * <h2>Migration story</h2>
 *
 * <p>On the first {@link #createDevice} call for each legacy profile, this
 * extension point emits a one-time {@code WARN} log naming the device and
 * pointing operators at the migration guide. The operator is then free to
 * recreate the profile under the new type at their convenience — at the
 * latest, when the alias is removed in a future major release
 * (see {@code /modules/.review/MIGRATION-v3-v4.md} for the schedule).</p>
 *
 * <p>Removal plan. The alias is shipped in Camera Driver v3.0.0 and is
 * scheduled for removal in v4.0.0. Removing it earlier would require the
 * customer to recreate profiles at upgrade time; keeping it longer than one
 * major version compounds the surface area we have to maintain for the
 * rename. v4.0.0 will fail loudly with an actionable error message if any
 * legacy profiles still exist, rather than silently dropping them.</p>
 *
 * @since v3.0.0
 */
public class LegacyCameraExtensionPoint extends DeviceExtensionPoint<CameraConfig> {

    private static final Logger logger = LoggerFactory.getLogger(LegacyCameraExtensionPoint.class);

    /** The pre-v3.0.0 extension-point type ID stored in legacy profile JSON. */
    public static final String LEGACY_TYPE_ID = "com.onvif.driver.Camera";

    /**
     * Names of legacy profiles we've already warned about. Logs the
     * deprecation warning once per device per Gateway run rather than on
     * every reconnect / restart of the device, so the operator's log isn't
     * flooded but they always see the warning at least once per startup.
     */
    private static final Set<String> warnedDevices = ConcurrentHashMap.newKeySet();

    private volatile Go2RtcManager go2RtcManager;

    public LegacyCameraExtensionPoint() {
        super(
            LEGACY_TYPE_ID,
            "Camera.Meta.DisplayName",
            "Camera.Meta.Description",
            CameraConfig.class
        );
    }

    public void setGo2RtcManager(Go2RtcManager go2RtcManager) {
        this.go2RtcManager = go2RtcManager;
    }

    @Override
    protected Device createDevice(
        DeviceContext context,
        DeviceProfileConfig profileConfig,
        CameraConfig deviceConfig) {

        String deviceName = context.getName();
        if (warnedDevices.add(deviceName)) {
            logger.warn(
                "Device '{}' is using the legacy Camera Driver type "
                + "'{}'. The device will continue to work, but please "
                + "delete and recreate it under the new 'Camera' device "
                + "type from the OPC-UA device dropdown. The legacy alias "
                + "is scheduled for removal in Camera Driver v4.0.0. See "
                + "/modules/.review/MIGRATION-v3-v4.md for the migration "
                + "guide.",
                deviceName,
                LEGACY_TYPE_ID
            );
        }
        return new CameraDevice(context, deviceConfig, go2RtcManager);
    }

    /**
     * Hides the legacy type from the "Add Device" dropdown.
     *
     * <p>Operators must create new profiles under
     * {@link CameraExtensionPoint#TYPE_ID}. The legacy ID is accepted only
     * for back-compat deserialization of pre-v3.0.0 profiles.</p>
     */
    @Override
    public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
        return Optional.empty();
    }

    /**
     * Delegates validation to the same rules as {@link CameraExtensionPoint}.
     * Legacy profiles still have to pass the current configuration checks —
     * the alias is for type-ID compatibility, not bypass.
     */
    @Override
    protected void validate(CameraConfig config, Builder errors) {
        CameraExtensionPoint.applyValidation(config, errors);
    }
}
