package com.gaskony.camera.gateway.device;

import com.inductiveautomation.ignition.gateway.config.ValidationErrors.Builder;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceExtensionPoint;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceProfileConfig;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import com.gaskony.camera.gateway.util.ValidationUtil;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified extension point for the Camera device type.
 * Replaces both ONVIFDeviceExtensionPoint and GenericCameraExtensionPoint.
 * Registers a single "Camera" entry in the device connection dropdown.
 */
public class CameraExtensionPoint extends DeviceExtensionPoint<CameraConfig> {

    public static final String TYPE_ID = "com.gaskony.camera.Camera";

    private static final DeviceRegistry<CameraDevice> registry = new DeviceRegistry<>();

    /**
     * Bounded executor used by {@link CameraDevice} to perform RTSP / HTTP /
     * ONVIF probing on a background thread.
     *
     * Per /modules/.review/FINAL_REVIEW.md §5 P2 (P2-CD-2) and
     * {@code reports/xc-performance.md}: the previous build executed the
     * synchronous probe ladder (~22 paths × 3 s) inline on the device-startup
     * thread. With N cameras + any unreachable host, OPC-UA device readiness
     * was delayed by tens of seconds (worst case ~90 s per camera).
     *
     * Each device now submits its probe work here; {@code onStartup} returns
     * immediately with status {@code DISCOVERING}, then transitions to
     * {@code RUNNING} when the probe task finishes. Threads are named
     * {@code camera-probe-N} so they're identifiable in jstack output.
     *
     * The pool is bounded to 4 threads so that a Gateway with many unreachable
     * cameras can't spawn one probe-thread per camera.
     */
    private static final ExecutorService PROBE_EXECUTOR = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "camera-probe-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private volatile Go2RtcManager go2RtcManager;

    public CameraExtensionPoint() {
        super(
            TYPE_ID,
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

        return new CameraDevice(context, deviceConfig, go2RtcManager);
    }

    @Override
    public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
        return Optional.of(
            new ExtensionPointResourceForm(
                DeviceExtensionPoint.DEVICE_RESOURCE_TYPE,
                "Device Connection",
                TYPE_ID,
                SchemaUtil.fromType(DeviceProfileConfig.class),
                SchemaUtil.fromType(CameraConfig.class),
                Set.of()
            )
        );
    }

    @Override
    protected void validate(CameraConfig config, Builder errors) {
        // Validate IP address (required)
        String ipAddress = config.connection().ipAddress();
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            errors.check(false, "IP address is required");
        } else {
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

        // Validate timeout
        if (config.connection().timeout() < 1) {
            errors.check(false, "Connection timeout must be at least 1 second");
        }

        // Validate poll interval (0 = disabled, otherwise >= 1)
        if (config.advanced().pollInterval() < 0) {
            errors.check(false, "Poll interval must be 0 (disabled) or at least 1 second");
        }

        // Validate optional URL overrides
        String rtspUrl = config.advanced().rtspUrl();
        if (rtspUrl != null && !rtspUrl.trim().isEmpty() && !ValidationUtil.isValidRtspUrl(rtspUrl)) {
            errors.check(false, "Invalid RTSP URL format. Must start with rtsp:// or rtsps://");
        }
        String snapshotUrl = config.advanced().snapshotUrl();
        if (snapshotUrl != null && !snapshotUrl.trim().isEmpty() && !ValidationUtil.isValidUrl(snapshotUrl)) {
            errors.check(false, "Invalid Snapshot URL format. Must start with http:// or https://");
        }
        String mjpegUrl = config.advanced().mjpegUrl();
        if (mjpegUrl != null && !mjpegUrl.trim().isEmpty() && !ValidationUtil.isValidUrl(mjpegUrl)) {
            errors.check(false, "Invalid MJPEG URL format. Must start with http:// or https://");
        }
    }

    public static void registerDevice(String name, CameraDevice device) {
        registry.register(name, device);
    }

    public static void unregisterDevice(String name) {
        registry.unregister(name);
    }

    public CameraDevice getDevice(String name) {
        return registry.get(name);
    }

    public static Map<String, CameraDevice> getAllDevices() {
        return registry.getAll();
    }

    /**
     * Shared bounded executor for camera startup probes.
     * See {@link #PROBE_EXECUTOR} javadoc for rationale (P2-CD-2).
     */
    public static ExecutorService getProbeExecutor() {
        return PROBE_EXECUTOR;
    }
}
