package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.common.DeviceStatus;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.CameraDevice;
import com.onvif.driver.gateway.device.CameraExtensionPoint;
import com.onvif.driver.gateway.servlet.RateLimiter;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONObject;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Map;

/**
 * Handles health check, diagnostics, and auth-status endpoints.
 */
public class DiagnosticsHandler extends BaseHandler {

    public DiagnosticsHandler(GatewayContext context,
                               CameraExtensionPoint cameraExtensionPoint,
                               Go2RtcManager go2RtcManager,
                               AuthenticationManager authManager,
                               String moduleVersion) {
        super(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    public Object handleHealthCheck(RequestContext requestContext, HttpServletResponse response) throws Exception {
        if (!isAuthenticated(requestContext)) {
            sendAuthenticationRequired(response);
            return null;
        }

        Map<String, CameraDevice> allDevices = CameraExtensionPoint.getAllDevices();

        JSONObject result = new JSONObject();
        result.put("status", "ok");
        result.put("service", "camera-driver");
        result.put("version", moduleVersion);
        result.put("deviceCount", allDevices.size());

        int runningCount = 0;
        int onvifCount = 0;
        for (CameraDevice d : allDevices.values()) {
            if (DeviceStatus.isActive(d.getStatus())) runningCount++;
            if (d.isOnvifAvailable()) onvifCount++;
        }
        result.put("runningCount", runningCount);
        result.put("onvifCount", onvifCount);
        result.put("genericCount", allDevices.size() - onvifCount);

        result.put("activeSnapshots", SnapshotHandler.getActiveSnapshots());
        result.put("maxSnapshots", SnapshotHandler.getMaxConcurrentSnapshots());
        result.put("activeStreams", StreamHandler.getActiveStreams());
        result.put("maxStreams", StreamHandler.getMaxConcurrentStreams());
        result.put("go2rtcAvailable", go2RtcManager != null && go2RtcManager.isAvailable());

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

    public Object handleDiagnostics(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Diagnostics request received");

        if (!isAuthenticated(requestContext)) {
            sendAuthenticationRequired(response);
            return null;
        }

        try {
            JSONObject result = new JSONObject();

            JSONObject streaming = new JSONObject();
            streaming.put("activeSnapshots", SnapshotHandler.getActiveSnapshots());
            streaming.put("maxSnapshots", SnapshotHandler.getMaxConcurrentSnapshots());
            streaming.put("activeStreams", StreamHandler.getActiveStreams());
            streaming.put("maxStreams", StreamHandler.getMaxConcurrentStreams());
            streaming.put("connectedClients", RateLimiter.getConnectedClientCount());
            result.put("streaming", streaming);

            if (go2RtcManager != null) {
                result.put("go2rtc", go2RtcManager.getProcessInfo());
                result.put("go2rtcStreams", go2RtcManager.getStreamInfo());
            } else {
                JSONObject noGo2Rtc = new JSONObject();
                noGo2Rtc.put("alive", false);
                noGo2Rtc.put("port", 0);
                result.put("go2rtc", noGo2Rtc);
            }

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

            Map<String, CameraDevice> allDevices = CameraExtensionPoint.getAllDevices();
            JSONObject deviceCounts = new JSONObject();
            int onvifCount = 0;
            for (CameraDevice d : allDevices.values()) {
                if (d.isOnvifAvailable()) onvifCount++;
            }
            deviceCounts.put("onvif", onvifCount);
            deviceCounts.put("generic", allDevices.size() - onvifCount);
            deviceCounts.put("total", allDevices.size());
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
