package com.onvif.driver.gateway;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.migration.IdbMigrationStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.servlet.ONVIFRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

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
    private ONVIFDeviceExtensionPoint deviceExtensionPoint;

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

        // Create device extension point
        deviceExtensionPoint = new ONVIFDeviceExtensionPoint();

        logger.info("ONVIF Driver module started successfully");
    }

    /**
     * Mounts route handlers for snapshot and stream endpoints.
     * Routes will be available at /main/data/onvif-driver/*
     *
     * Specifically:
     * - /main/data/onvif-driver/snapshot?device=X&profile=Y
     * - /main/data/onvif-driver/stream?device=X&profile=Y&fps=Z
     */
    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        logger.info("Mounting ONVIF route handlers");
        new ONVIFRoutes(context, deviceExtensionPoint).mountRoutes(routes);
    }

    /**
     * Returns the mount path alias for this module.
     * This determines the path at which routes are mounted: /main/data/{alias}/*
     */
    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of("onvif-driver");
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
        // Return the same instance we use for servlets
        if (deviceExtensionPoint == null) {
            deviceExtensionPoint = new ONVIFDeviceExtensionPoint();
        }
        return List.of(deviceExtensionPoint);
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
