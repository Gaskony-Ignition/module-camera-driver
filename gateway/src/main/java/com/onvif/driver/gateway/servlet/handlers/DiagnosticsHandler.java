package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.common.DeviceStatus;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraDevice;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
import com.onvif.driver.gateway.servlet.RateLimiter;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONObject;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;

/**
 * Handles health check, diagnostics, and auth-status endpoints.
 */
public class DiagnosticsHandler extends BaseHandler {

    public DiagnosticsHandler(GatewayContext context,
                               ONVIFDeviceExtensionPoint deviceExtensionPoint,
                               GenericCameraExtensionPoint genericCameraExtensionPoint,
                               Go2RtcManager go2RtcManager,
                               AuthenticationManager authManager,
                               String moduleVersion) {
        super(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    /**
     * Handles health check requests.
     * URL: http://gateway:8088/data/camera-driver/health
     */
    public Object handleHealthCheck(RequestContext requestContext, HttpServletResponse response) throws Exception {
        if (!isAuthenticated(requestContext)) {
            sendAuthenticationRequired(response);
            return null;
        }

        JSONObject result = new JSONObject();
        result.put("status", "ok");
        result.put("service", "camera-driver");
        result.put("version", moduleVersion);
        result.put("onvifDeviceCount", ONVIFDeviceExtensionPoint.getAllDevices().size());
        result.put("genericCameraCount", GenericCameraExtensionPoint.getAllDevices().size());
        int totalDevices = ONVIFDeviceExtensionPoint.getAllDevices().size()
            + GenericCameraExtensionPoint.getAllDevices().size();
        result.put("deviceCount", totalDevices);

        // Count running devices
        int runningCount = 0;
        for (ONVIFDevice d : ONVIFDeviceExtensionPoint.getAllDevices().values()) {
            if (DeviceStatus.isActive(d.getStatus())) runningCount++;
        }
        for (GenericCameraDevice d : GenericCameraExtensionPoint.getAllDevices().values()) {
            if (DeviceStatus.isActive(d.getStatus())) runningCount++;
        }
        result.put("runningCount", runningCount);

        result.put("activeSnapshots", SnapshotHandler.getActiveSnapshots());
        result.put("maxSnapshots", SnapshotHandler.getMaxConcurrentSnapshots());
        result.put("activeStreams", StreamHandler.getActiveStreams());
        result.put("maxStreams", StreamHandler.getMaxConcurrentStreams());
        result.put("go2rtcAvailable", go2RtcManager != null && go2RtcManager.isAvailable());

        // CPU and RAM system stats
        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunBean = (com.sun.management.OperatingSystemMXBean) osBean;
                double cpuLoad = sunBean.getCpuLoad();
                result.put("cpuPercent", cpuLoad >= 0 ? Math.round(cpuLoad * 100) : -1);
                long totalMem = sunBean.getTotalMemorySize();
                long freeMem = sunBean.getFreeMemorySize();
                long usedMem = totalMem - freeMem;
                result.put("ramUsedMb", usedMem / (1024 * 1024));
                result.put("ramTotalMb", totalMem / (1024 * 1024));
                result.put("ramPercent", totalMem > 0 ? Math.round((double) usedMem / totalMem * 100) : -1);
            }
        } catch (Exception e) {
            logger.debug("Could not read CPU/RAM stats: {}", e.getMessage());
        }

        result.put("timestamp", System.currentTimeMillis());

        response.setContentType("application/json");
        response.getWriter().write(result.toString());
        return null;
    }

    /**
     * Handles diagnostics requests - returns JSON with resource usage metrics.
     * URL: http://gateway:8088/data/camera-driver/diagnostics
     */
    public Object handleDiagnostics(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Diagnostics request received");

        if (!isAuthenticated(requestContext)) {
            sendAuthenticationRequired(response);
            return null;
        }

        try {
            JSONObject result = new JSONObject();

            // Streaming activity
            JSONObject streaming = new JSONObject();
            streaming.put("activeSnapshots", SnapshotHandler.getActiveSnapshots());
            streaming.put("maxSnapshots", SnapshotHandler.getMaxConcurrentSnapshots());
            streaming.put("activeStreams", StreamHandler.getActiveStreams());
            streaming.put("maxStreams", StreamHandler.getMaxConcurrentStreams());
            streaming.put("connectedClients", RateLimiter.getConnectedClientCount());
            result.put("streaming", streaming);

            // go2rtc process info
            if (go2RtcManager != null) {
                result.put("go2rtc", go2RtcManager.getProcessInfo());
                result.put("go2rtcStreams", go2RtcManager.getStreamInfo());
            } else {
                JSONObject noGo2Rtc = new JSONObject();
                noGo2Rtc.put("alive", false);
                noGo2Rtc.put("port", 0);
                result.put("go2rtc", noGo2Rtc);
            }

            // Gateway JVM metrics
            JSONObject gateway = new JSONObject();
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            long heapUsed = memBean.getHeapMemoryUsage().getUsed();
            long heapMax = memBean.getHeapMemoryUsage().getMax();
            gateway.put("heapUsedMb", heapUsed / (1024 * 1024));
            gateway.put("heapMaxMb", heapMax > 0 ? heapMax / (1024 * 1024) : -1);
            gateway.put("heapPercent", heapMax > 0 ? Math.round((double) heapUsed / heapMax * 100) : -1);

            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            gateway.put("threadCount", threadBean.getThreadCount());
            result.put("gateway", gateway);

            // Device counts
            JSONObject deviceCounts = new JSONObject();
            deviceCounts.put("onvif", ONVIFDeviceExtensionPoint.getAllDevices().size());
            deviceCounts.put("generic", GenericCameraExtensionPoint.getAllDevices().size());
            deviceCounts.put("total", ONVIFDeviceExtensionPoint.getAllDevices().size()
                + GenericCameraExtensionPoint.getAllDevices().size());
            result.put("devices", deviceCounts);

            result.put("timestamp", System.currentTimeMillis());

            response.setContentType("application/json");
            response.getWriter().write(result.toString());

        } catch (Exception e) {
            logger.error("Error generating diagnostics", e);
            response.sendError(500, "Internal server error");
        }

        return null;
    }

    /**
     * Handles auth status check requests.
     * URL: http://gateway:8088/data/camera-driver/auth-status
     *
     * Returns 200 with {"authenticated": true/false} — never returns 401 or
     * WWW-Authenticate header, so the browser won't show a native Basic Auth popup.
     * Used by standalone.html for continuous auth monitoring.
     */
    public Object handleAuthStatus(RequestContext requestContext, HttpServletResponse response) throws Exception {
        JSONObject result = new JSONObject();
        try {
            boolean authenticated = isAuthenticated(requestContext);
            result.put("authenticated", authenticated);
        } catch (Exception e) {
            result.put("authenticated", false);
        }
        response.setContentType("application/json");
        response.getWriter().write(result.toString());
        return null;
    }
}
