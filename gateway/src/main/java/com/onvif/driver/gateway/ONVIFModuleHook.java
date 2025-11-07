package com.onvif.driver.gateway;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.migration.IdbMigrationStrategy;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Module hook for the ONVIF Driver.
 * This registers the device driver with Ignition's device connection system.
 *
 * CRITICAL LEARNING: Must register resource bundle with BundleUtil.get().addBundle()
 * or display names will show as "¿key?" in the UI.
 */
public class ONVIFModuleHook extends AbstractDeviceModuleHook {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private GatewayContext context;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        logger.info("ONVIF Driver module setup");
    }

    @Override
    public void startup(LicenseState licenseState) {
        logger.info("ONVIF Driver module starting...");

        // CRITICAL: Register resource bundle for i18n support
        // Without this, display names will show as "¿ONVIFDevice.Meta.DisplayName?"
        BundleUtil.get().addBundle(
            "ONVIFDevice",
            ONVIFDeviceExtensionPoint.class,
            "ONVIFDevice"
        );
        logger.info("Registered ONVIFDevice resource bundle");

        logger.info("ONVIF Driver module started successfully");
    }

    @Override
    public void shutdown() {
        logger.info("ONVIF Driver module shutting down");
        logger.info("ONVIF Driver module shutdown complete");
    }

    /**
     * Returns the list of device extension points provided by this module.
     * This makes "ONVIF Driver" appear in the device type dropdown.
     */
    @Override
    protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
        return List.of(new ONVIFDeviceExtensionPoint());
    }

    /**
     * Database migration strategies.
     * Currently not needed as we use modern DeviceConfig records.
     */
    @Override
    public List<IdbMigrationStrategy> getRecordMigrationStrategies() {
        return List.of();
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }
}
