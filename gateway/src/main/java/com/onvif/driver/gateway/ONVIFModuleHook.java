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
import com.onvif.driver.gateway.device.CameraExtensionPoint;
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
 * Registers a single "Camera" device type with Ignition's device connection system.
 */
public class ONVIFModuleHook extends AbstractDeviceModuleHook {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private GatewayContext context;
    private CameraExtensionPoint cameraExtensionPoint;
    private Go2RtcManager go2RtcManager;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;

        Path dataDir = context.getSystemManager().getDataDir().toPath();
        this.go2RtcManager = new Go2RtcManager(dataDir);

        try {
            go2RtcManager.start();
            logger.info("go2rtc manager started in setup (available: {})", go2RtcManager.isAvailable());
        } catch (Exception e) {
            logger.warn("Failed to start go2rtc in setup - RTSP streaming will use fallback modes: {}", e.getMessage());
        }

        if (this.cameraExtensionPoint == null) {
            this.cameraExtensionPoint = new CameraExtensionPoint();
        }
        this.cameraExtensionPoint.setGo2RtcManager(go2RtcManager);

        logger.info("Camera Driver module setup complete");

        // Register WebUI component for connection browser
        try {
            SystemJsModule connectionBrowserModule = new SystemJsModule(
                "com.onvif.driver.ConnectionBrowser",
                "/res/camera-driver/connectionBrowser.js"
            );

            context.getWebResourceManager().getNavigationModel().getConnections()
                .addCategory("camera-driver", cat -> cat
                    .label("Camera Driver")
                    .addPage("Devices", page -> page
                        .position(10)
                        .mount("/camera-connection-browser", "CameraConnectionBrowser", connectionBrowserModule)
                    )
                );

            logger.info("Added 'Camera Driver' menu item to Gateway Config");
        } catch (Exception e) {
            logger.error("Failed to add WebUI navigation menu item", e);
        }
    }

    @Override
    public void startup(LicenseState licenseState) {
        logger.info("Camera Driver module starting...");

        // Register single resource bundle for the unified Camera device type
        BundleUtil.get().addBundle(
            "Camera",
            CameraExtensionPoint.class,
            "Camera"
        );
        logger.debug("Registered resource bundle: Camera");

        // Register Perspective components (if Perspective module is loaded)
        try {
            PerspectiveContext perspectiveContext = PerspectiveContext.get(context);
            perspectiveContext.getComponentRegistry().registerComponent(CameraComponents.VIEWER_DESCRIPTOR);
            perspectiveContext.getComponentRegistry().registerComponent(CameraComponents.GRID_DESCRIPTOR);
            logger.info("Registered Perspective components: Camera Viewer, Camera Grid");
        } catch (Throwable t) {
            logger.debug("Perspective component registration skipped: {}", t.getMessage());
        }

        logger.info("Camera Driver module started successfully");
    }

    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        logger.info("Mounting route handlers at /data/camera-driver/*");

        String moduleVersion = loadModuleVersion();
        new ONVIFRoutes(context, cameraExtensionPoint, go2RtcManager, moduleVersion).mountRoutes(routes);

        logger.info("Route handlers mounted successfully");
    }

    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of(CameraDriverPaths.MOUNT_ALIAS);
    }

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

        RateLimiter.reset();

        try {
            ONVIFRoutes.shutdown();
        } catch (Exception e) {
            logger.error("Error shutting down ONVIF routes", e);
        }

        SnapshotHandler.resetCounters();
        StreamHandler.resetCounters();

        if (go2RtcManager != null) {
            try {
                go2RtcManager.stop();
            } catch (Exception e) {
                logger.error("Error stopping go2rtc manager", e);
            }
        }

        logger.info("Camera Driver module shutdown complete");
    }

    @Override
    protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
        if (cameraExtensionPoint == null) {
            logger.warn("cameraExtensionPoint is null in getDeviceExtensionPoints - creating early instance");
            cameraExtensionPoint = new CameraExtensionPoint();
        }
        return List.of(cameraExtensionPoint);
    }

    @Override
    public List<IdbMigrationStrategy> getRecordMigrationStrategies() {
        return List.of();
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    private static String loadModuleVersion() {
        try (InputStream is = ONVIFModuleHook.class.getResourceAsStream("/module.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("module.version", "unknown");
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(ONVIFModuleHook.class)
                .debug("Could not load module.properties: {}", e.getMessage());
        }
        return "unknown";
    }
}
