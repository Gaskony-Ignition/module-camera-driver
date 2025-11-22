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
        // CRITICAL: Create device extension point in setup, NOT startup
        // This ensures it's available when getDeviceExtensionPoints() is called
        this.deviceExtensionPoint = new ONVIFDeviceExtensionPoint();
        logger.info("ONVIF Driver module setup complete");
        logger.debug("Created device extension point: {}", deviceExtensionPoint);
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
        logger.debug("Registered ONVIFDevice resource bundle");

        // Device extension point already created in setup()
        logger.debug("Device extension point ready: {}", deviceExtensionPoint);
        logger.info("ONVIF Driver module started successfully");
    }

    /**
     * Mounts route handlers for snapshot and stream endpoints.
     * Routes will be available at /data/onvif-driver/*
     *
     * IMPORTANT: URLs are /data/{alias}/* NOT /main/data/{alias}/*
     *
     * Specifically:
     * - http://gateway:8088/data/onvif-driver/test
     * - http://gateway:8088/data/onvif-driver/snapshot?device=X&profile=Y
     * - http://gateway:8088/data/onvif-driver/stream?device=X&profile=Y&fps=Z
     */
    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        logger.info("Mounting ONVIF route handlers at /data/onvif-driver/*");
        logger.debug("RouteGroup: {}", routes);

        new ONVIFRoutes(context, deviceExtensionPoint).mountRoutes(routes);

        logger.info("Route handlers mounted successfully");
    }

    /**
     * Returns the mount path alias for this module.
     * This determines the path at which routes are mounted: /main/data/{alias}/*
     */
    @Override
    public Optional<String> getMountPathAlias() {
        logger.debug("getMountPathAlias() returning: onvif-driver");
        return Optional.of("onvif-driver");
    }

    @Override
    public void shutdown() {
        logger.info("ONVIF Driver module shutting down...");

        // Shutdown rate limiting executor to prevent resource leak
        try {
            ONVIFRoutes.shutdown();
        } catch (Exception e) {
            logger.error("Error shutting down ONVIF routes", e);
        }

        logger.info("ONVIF Driver module shutdown complete");
    }

    /**
     * Returns the list of device extension points provided by this module.
     * This makes "ONVIF Driver" appear in the device type dropdown.
     */
    @Override
    protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
        logger.debug("getDeviceExtensionPoints() called");
        // CRITICAL: Return the SAME instance created in setup()
        // Do NOT create a new instance here!
        if (deviceExtensionPoint == null) {
            logger.error("ERROR: deviceExtensionPoint is null - should have been created in setup()!");
            deviceExtensionPoint = new ONVIFDeviceExtensionPoint();
        }
        logger.debug("Returning device extension point: {}", deviceExtensionPoint);
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
