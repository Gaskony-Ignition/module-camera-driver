package com.onvif.driver.gateway;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.migration.IdbMigrationStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.onvif.driver.common.CameraComponents;
import com.onvif.driver.common.CameraDriverPaths;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
import com.onvif.driver.gateway.servlet.ONVIFRoutes;
import com.onvif.driver.gateway.servlet.RateLimiter;
import com.onvif.driver.gateway.servlet.handlers.SnapshotHandler;
import com.onvif.driver.gateway.servlet.handlers.StreamHandler;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

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

        // Create Go2RtcManager for RTSP-to-MJPEG conversion
        Path dataDir = context.getSystemManager().getDataDir().toPath();
        this.go2RtcManager = new Go2RtcManager(dataDir);

        // Start go2rtc process early so it's available before devices start up
        // (devices start between setup() and startup() in the OPC-UA lifecycle)
        try {
            go2RtcManager.start();
            logger.info("go2rtc manager started in setup (available: {})", go2RtcManager.isAvailable());
        } catch (Exception e) {
            logger.warn("Failed to start go2rtc in setup - RTSP streaming will use fallback modes: {}", e.getMessage());
        }

        // Create or update extension points.
        // NOTE: getDeviceExtensionPoints() may have been called before setup(),
        // creating extension points with go2RtcManager=null. If so, reuse them
        // and inject the now-initialized go2RtcManager.
        if (this.deviceExtensionPoint == null) {
            this.deviceExtensionPoint = new ONVIFDeviceExtensionPoint();
        }
        // Inject go2rtc manager (may have been created before setup() with null manager)
        this.deviceExtensionPoint.setGo2RtcManager(go2RtcManager);

        if (this.genericCameraExtensionPoint == null) {
            this.genericCameraExtensionPoint = new GenericCameraExtensionPoint(go2RtcManager);
        } else {
            // Extension point was created early by getDeviceExtensionPoints() with null manager
            this.genericCameraExtensionPoint.setGo2RtcManager(go2RtcManager);
        }

        logger.info("Camera Driver module setup complete");
        logger.debug("Device extension points: ONVIF={}, GenericCamera={}", deviceExtensionPoint, genericCameraExtensionPoint);

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
                        .mount("/camera-connection-browser", "CameraConnectionBrowser", connectionBrowserModule)
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

        // Device extension points already created in setup(), go2rtc already started in setup()
        logger.debug("Device extension points ready: ONVIF={}, GenericCamera={}", deviceExtensionPoint, genericCameraExtensionPoint);

        // Register Perspective components (if Perspective module is loaded)
        try {
            PerspectiveContext perspectiveContext = PerspectiveContext.get(context);
            perspectiveContext.getComponentRegistry().registerComponent(CameraComponents.VIEWER_DESCRIPTOR);
            perspectiveContext.getComponentRegistry().registerComponent(CameraComponents.GRID_DESCRIPTOR);
            logger.info("Registered Perspective components: Camera Viewer, Camera Grid");
        } catch (Throwable t) {
            // Perspective module not loaded or class loading issue - camera driver works without it
            logger.debug("Perspective component registration skipped: {}", t.getMessage());
        }

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

        String moduleVersion = loadModuleVersion();
        new ONVIFRoutes(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, moduleVersion).mountRoutes(routes);

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
        logger.debug("getMountPathAlias() returning: {}", CameraDriverPaths.MOUNT_ALIAS);
        return Optional.of(CameraDriverPaths.MOUNT_ALIAS);
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

        // Unregister Perspective components
        try {
            PerspectiveContext perspectiveContext = PerspectiveContext.get(context);
            perspectiveContext.getComponentRegistry().removeComponent(CameraComponents.VIEWER_ID);
            perspectiveContext.getComponentRegistry().removeComponent(CameraComponents.GRID_ID);
        } catch (Throwable t) {
            logger.debug("Perspective cleanup skipped: {}", t.getMessage());
        }

        // Clear rate limit state before shutting down executor (ensures clean reload)
        RateLimiter.reset();

        // Shutdown rate limiting executor to prevent resource leak
        try {
            ONVIFRoutes.shutdown();
        } catch (Exception e) {
            logger.error("Error shutting down ONVIF routes", e);
        }

        // Reset static counters so a hot-reload starts from zero
        SnapshotHandler.resetCounters();
        StreamHandler.resetCounters();

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

    /**
     * Loads the module version from module.properties, which is populated at build time
     * by Gradle resource filtering from build.gradle.kts (the single source of truth).
     */
    private static String loadModuleVersion() {
        try (InputStream is = ONVIFModuleHook.class.getResourceAsStream("/module.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("module.version", "unknown");
            }
        } catch (Exception e) {
            // Non-fatal — version is informational only
        }
        return "unknown";
    }
}
