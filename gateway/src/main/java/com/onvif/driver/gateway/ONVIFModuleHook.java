package com.onvif.driver.gateway;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.migration.IdbMigrationStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
import com.onvif.driver.gateway.servlet.ONVIFRoutes;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Module hook for the Camera Driver.
 * This registers the device driver with Ignition's device connection system.
 *
 * CRITICAL LEARNING: Must register resource bundle with BundleUtil.get().addBundle()
 * or display names will show as "¿key?" in the UI.
 */
public class ONVIFModuleHook extends AbstractDeviceModuleHook {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private GatewayContext context;
    private ONVIFDeviceExtensionPoint deviceExtensionPoint;
    private GenericCameraExtensionPoint genericCameraExtensionPoint;
    private Go2RtcManager go2RtcManager;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        // CRITICAL: Create device extension points in setup, NOT startup
        // This ensures they're available when getDeviceExtensionPoints() is called
        this.deviceExtensionPoint = new ONVIFDeviceExtensionPoint();

        // Create Go2RtcManager for RTSP-to-MJPEG conversion
        Path dataDir = context.getSystemManager().getDataDir().toPath();
        this.go2RtcManager = new Go2RtcManager(dataDir);

        // Create Generic Camera extension point with go2rtc support
        this.genericCameraExtensionPoint = new GenericCameraExtensionPoint(go2RtcManager);

        logger.info("Camera Driver module setup complete");
        logger.debug("Created device extension points: ONVIF={}, GenericCamera={}", deviceExtensionPoint, genericCameraExtensionPoint);

        // Register WebUI component for connection browser
        try {
            // Create SystemJsModule that points to our connection browser page
            // The page is served via authenticated data routes, not static resources
            SystemJsModule connectionBrowserModule = new SystemJsModule(
                "com.onvif.driver.ConnectionBrowser",
                "/res/camera-driver/connectionBrowser.js"
            );

            // Add navigation menu item in the Connections section
            context.getWebResourceManager().getNavigationModel().getConnections()
                .addCategory("camera-driver", cat -> cat
                    .label("Camera Driver")
                    .addPage("Connection Browser", page -> page
                        .position(10)
                        .mount("/camera-connection-browser", "ConnectionBrowser", connectionBrowserModule)
                    )
                );

            logger.info("Added 'Camera Driver' menu item to Gateway Config:");
            logger.info("  - Connection Browser: /app/camera-connection-browser");
        } catch (Exception e) {
            logger.error("Failed to add WebUI navigation menu item", e);
        }
    }

    @Override
    public void startup(LicenseState licenseState) {
        logger.info("Camera Driver module starting...");

        // CRITICAL: Register resource bundles for i18n support
        // Without this, display names will show as "¿key?"
        BundleUtil.get().addBundle(
            "ONVIFDevice",
            ONVIFDeviceExtensionPoint.class,
            "ONVIFDevice"
        );
        BundleUtil.get().addBundle(
            "GenericCamera",
            GenericCameraExtensionPoint.class,
            "GenericCamera"
        );
        logger.debug("Registered resource bundles: ONVIFDevice, GenericCamera");

        // Start go2rtc process for RTSP-to-MJPEG conversion
        try {
            go2RtcManager.start();
            logger.info("go2rtc manager started (available: {})", go2RtcManager.isAvailable());
        } catch (Exception e) {
            logger.warn("Failed to start go2rtc - RTSP streaming will use fallback modes: {}", e.getMessage());
        }

        // Device extension points already created in setup()
        logger.debug("Device extension points ready: ONVIF={}, GenericCamera={}", deviceExtensionPoint, genericCameraExtensionPoint);
        logger.info("Camera Driver module started successfully");
    }

    /**
     * Mounts route handlers for snapshot and stream endpoints.
     * Routes will be available at /data/camera-driver/*
     *
     * IMPORTANT: URLs are /data/{alias}/* NOT /main/data/{alias}/*
     *
     * Specifically:
     * - http://gateway:8088/data/camera-driver/test
     * - http://gateway:8088/data/camera-driver/snapshot?device=X&profile=Y
     * - http://gateway:8088/data/camera-driver/stream?device=X&profile=Y&fps=Z
     */
    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        logger.info("Mounting route handlers at /data/camera-driver/*");
        logger.debug("RouteGroup: {}", routes);

        new ONVIFRoutes(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager).mountRoutes(routes);

        logger.info("Route handlers mounted successfully");
    }

    /**
     * Returns the mount path alias for this module.
     * This determines the path at which routes are mounted: /data/{alias}/*
     *
     * Public resources (from getMountedResourceFolder) at /res/camera-driver/*:
     * - /res/camera-driver/connectionBrowser.js - React component for connection browser
     *
     * Authenticated data routes (from mountRouteHandlers) at /data/camera-driver/*:
     * - /data/camera-driver/connection-browser - Connection browser page (requires login)
     * - /data/camera-driver/devices - List devices (requires login)
     * - /data/camera-driver/device/:name/status - Device status (requires login)
     * - /data/camera-driver/snapshot - Snapshot endpoint (requires login)
     * - /data/camera-driver/stream - Stream endpoint (requires login)
     * - /data/camera-driver/health - Health check (public)
     */
    @Override
    public Optional<String> getMountPathAlias() {
        logger.debug("getMountPathAlias() returning: camera-driver");
        return Optional.of("camera-driver");
    }

    /**
     * Mount web resources from the "mounted" folder.
     * Files in the mounted/ directory will be accessible at /res/camera-driver/*
     *
     * IMPORTANT: Ignition automatically adds the /res/camera-driver prefix based on
     * getMountPathAlias(). Do NOT replicate this path structure in your filesystem.
     *
     * Example mapping:
     *   Filesystem: gateway/src/main/resources/mounted/connectionBrowser.js
     *   URL:        /res/camera-driver/connectionBrowser.js
     */
    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    @Override
    public void shutdown() {
        logger.info("Camera Driver module shutting down...");

        // Shutdown rate limiting executor to prevent resource leak
        try {
            ONVIFRoutes.shutdown();
        } catch (Exception e) {
            logger.error("Error shutting down ONVIF routes", e);
        }

        // Stop go2rtc process
        if (go2RtcManager != null) {
            try {
                go2RtcManager.stop();
            } catch (Exception e) {
                logger.error("Error stopping go2rtc manager", e);
            }
        }

        logger.info("Camera Driver module shutdown complete");
    }

    /**
     * Returns the list of device extension points provided by this module.
     * This makes "ONVIF Camera" and "Generic Camera" appear in the device type dropdown.
     */
    @Override
    protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
        logger.debug("getDeviceExtensionPoints() called");
        // CRITICAL: Return the SAME instances created in setup()
        // Do NOT create new instances here!
        if (deviceExtensionPoint == null) {
            logger.error("ERROR: deviceExtensionPoint is null - should have been created in setup()!");
            deviceExtensionPoint = new ONVIFDeviceExtensionPoint();
        }
        if (genericCameraExtensionPoint == null) {
            logger.error("ERROR: genericCameraExtensionPoint is null - should have been created in setup()!");
            genericCameraExtensionPoint = new GenericCameraExtensionPoint(go2RtcManager);
        }
        logger.debug("Returning device extension points: ONVIF={}, GenericCamera={}", deviceExtensionPoint, genericCameraExtensionPoint);
        return List.of(deviceExtensionPoint, genericCameraExtensionPoint);
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
