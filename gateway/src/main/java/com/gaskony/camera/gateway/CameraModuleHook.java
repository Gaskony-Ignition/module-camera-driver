package com.gaskony.camera.gateway;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.migration.IdbMigrationStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.gaskony.camera.common.CameraComponents;
import com.gaskony.camera.common.CameraDriverPaths;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.device.LegacyCameraExtensionPoint;
import com.gaskony.camera.gateway.servlet.CameraRoutes;
import com.gaskony.camera.gateway.servlet.RateLimiter;
import com.gaskony.camera.gateway.servlet.handlers.SnapshotHandler;
import com.gaskony.camera.gateway.servlet.handlers.StreamHandler;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Module hook for the Camera Driver.
 * Registers a single "Camera" device type with Ignition's device connection system.
 */
public class CameraModuleHook extends AbstractDeviceModuleHook {

    private static final Logger logger = LoggerFactory.getLogger(CameraModuleHook.class);
    private GatewayContext context;
    private CameraExtensionPoint cameraExtensionPoint;
    /**
     * v3.0.0 back-compat alias under the pre-rename type ID
     * {@code com.onvif.driver.Camera}. Lets existing device profiles deserialise
     * without manual intervention. Removal scheduled for v4.0.0 — see
     * {@link com.gaskony.camera.gateway.device.LegacyCameraExtensionPoint} for
     * the full migration story.
     */
    private LegacyCameraExtensionPoint legacyCameraExtensionPoint;
    private Go2RtcManager go2RtcManager;

    /**
     * Daemon executor used to launch go2rtc off the Gateway lifecycle thread.
     *
     * Per /modules/.review/FINAL_REVIEW.md §5 P2 (P2-CD-1): the previous build
     * called {@code go2RtcManager.start()} from {@link #setup(GatewayContext)},
     * which violates the SDK contract — {@code setup()} performs ~30 MB of
     * binary extraction (with SHA-256 hashing), spawns a subprocess, and slept
     * 1 s waiting for readiness. {@code setup()} must only register extension
     * points; even {@code startup()} must not block the lifecycle thread.
     *
     * The launch is now deferred to a single-threaded daemon executor kicked
     * off from {@link #startup(LicenseState)}; {@code shutdown()} drains the
     * executor with a short timeout before stopping the manager.
     */
    private ExecutorService go2RtcStartExecutor;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;

        // setup() is restricted to extension-point registration and other
        // non-blocking initialisation. See P2-CD-1 — go2rtc startup is
        // deferred to startup() on a daemon thread.
        Path dataDir = context.getSystemManager().getDataDir().toPath();
        this.go2RtcManager = new Go2RtcManager(dataDir);

        if (this.cameraExtensionPoint == null) {
            this.cameraExtensionPoint = new CameraExtensionPoint();
        }
        this.cameraExtensionPoint.setGo2RtcManager(go2RtcManager);

        // v3.0.0 back-compat: also register the legacy com.onvif.driver.Camera
        // type so pre-rename profiles deserialise. See LegacyCameraExtensionPoint
        // class-level doc for the removal schedule (v4.0.0).
        if (this.legacyCameraExtensionPoint == null) {
            this.legacyCameraExtensionPoint = new LegacyCameraExtensionPoint();
        }
        this.legacyCameraExtensionPoint.setGo2RtcManager(go2RtcManager);

        logger.info("Camera Driver module setup complete (go2rtc launch deferred to startup)");

        // Register WebUI component for connection browser
        try {
            SystemJsModule connectionBrowserModule = new SystemJsModule(
                "com.gaskony.camera.ConnectionBrowser",
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

        // Launch go2rtc on a daemon thread so startup() returns promptly.
        // See P2-CD-1 in /modules/.review/FINAL_REVIEW.md.
        go2RtcStartExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CameraDriver-go2rtc-startup");
            t.setDaemon(true);
            return t;
        });
        go2RtcStartExecutor.submit(this::startGo2RtcAsync);

        logger.info("Camera Driver module started successfully (go2rtc launch in background)");
    }

    /**
     * Launches go2rtc on the dedicated start executor. Any failure is logged —
     * RTSP streaming will fall back to MJPEG/snapshot polling and the device
     * remains usable.
     */
    private void startGo2RtcAsync() {
        try {
            go2RtcManager.start();
            logger.info("go2rtc manager started asynchronously (available: {})", go2RtcManager.isAvailable());
        } catch (Exception e) {
            logger.warn("Failed to start go2rtc - RTSP streaming will use fallback modes: {}", e.getMessage());
        }
    }

    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        logger.info("Mounting route handlers at /data/camera-driver/*");

        String moduleVersion = loadModuleVersion();
        new CameraRoutes(context, cameraExtensionPoint, go2RtcManager, moduleVersion).mountRoutes(routes);

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
            CameraRoutes.shutdown();
        } catch (Exception e) {
            logger.error("Error shutting down ONVIF routes", e);
        }

        SnapshotHandler.resetCounters();
        StreamHandler.resetCounters();

        // Drain the go2rtc start executor — if startGo2RtcAsync is still in-flight
        // (e.g., extraction, process spawn) we wait briefly so stop() has a fully
        // initialised manager to act on. Bounded so a stuck launch can't block
        // module shutdown indefinitely.
        if (go2RtcStartExecutor != null) {
            go2RtcStartExecutor.shutdown();
            try {
                if (!go2RtcStartExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    logger.warn("go2rtc start executor did not finish within 3s — forcing shutdown");
                    go2RtcStartExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                go2RtcStartExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            go2RtcStartExecutor = null;
        }

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
        if (legacyCameraExtensionPoint == null) {
            logger.warn("legacyCameraExtensionPoint is null in getDeviceExtensionPoints - creating early instance");
            legacyCameraExtensionPoint = new LegacyCameraExtensionPoint();
        }
        return List.of(cameraExtensionPoint, legacyCameraExtensionPoint);
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
        try (InputStream is = CameraModuleHook.class.getResourceAsStream("/module.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("module.version", "unknown");
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(CameraModuleHook.class)
                .debug("Could not load module.properties: {}", e.getMessage());
        }
        return "unknown";
    }
}
