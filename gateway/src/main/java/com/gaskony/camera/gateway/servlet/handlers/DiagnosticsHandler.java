package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.common.DeviceStatus;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraDevice;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.servlet.RateLimiter;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONObject;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.Set;

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
        // P3-CD: shared AccessControl pattern.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }

        if (!RateLimiter.checkRateLimit(requestContext.getRequest(), response)) {
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
        result.put("onvifDeviceCount", onvifCount);
        result.put("genericCameraCount", allDevices.size() - onvifCount);

        result.put("activeSnapshots", SnapshotHandler.getActiveSnapshots());
        result.put("maxSnapshots", SnapshotHandler.getMaxConcurrentSnapshots());
        result.put("activeStreams", StreamHandler.getActiveStreams());
        result.put("maxStreams", StreamHandler.getMaxConcurrentStreams());
        result.put("go2rtcAvailable", go2RtcManager != null && go2RtcManager.isAvailable());

        // CPU: system-wide load (what the machine is doing overall)
        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunBean = (com.sun.management.OperatingSystemMXBean) osBean;
                double cpuLoad = sunBean.getCpuLoad();
                result.put("cpuPercent", cpuLoad >= 0 ? Math.round(cpuLoad * 100) : -1);
            }
        } catch (Exception e) {
            logger.debug("Could not read CPU stats: {}", e.getMessage());
        }

        // RAM: JVM heap (what this process actually allocates — system RAM is not actionable here)
        try {
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            long heapUsed = memBean.getHeapMemoryUsage().getUsed();
            long heapMax = memBean.getHeapMemoryUsage().getMax();
            result.put("ramUsedMb", heapUsed / (1024 * 1024));
            result.put("ramTotalMb", heapMax > 0 ? heapMax / (1024 * 1024) : -1);
            result.put("ramPercent", heapMax > 0 ? Math.round((double) heapUsed / heapMax * 100) : -1);
        } catch (Exception e) {
            logger.debug("Could not read heap stats: {}", e.getMessage());
        }

        result.put("timestamp", System.currentTimeMillis());

        response.setContentType("application/json");
        response.getWriter().write(result.toString());
        return null;
    }

    public Object handleDiagnostics(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Diagnostics request received");

        // P3-CD: shared AccessControl pattern.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }

        if (!RateLimiter.checkRateLimit(requestContext.getRequest(), response)) {
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

    /**
     * GET /data/camera-driver/metrics
     *
     * Returns per-camera resource metrics (go2rtc viewers, bitrate, snapshot latency)
     * and module-level memory (JVM heap + go2rtc process RSS). Inspired by Frigate's
     * per-camera stats view. Auth required.
     */
    public Object handleMetrics(RequestContext requestContext, HttpServletResponse response) throws Exception {
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }

        if (!RateLimiter.checkRateLimit(requestContext.getRequest(), response)) {
            return null;
        }

        try {
            JSONObject result = new JSONObject();

            // ── JVM heap ─────────────────────────────────────────────────────────
            JSONObject jvm = new JSONObject();
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            long heapUsed = memBean.getHeapMemoryUsage().getUsed();
            long heapMax  = memBean.getHeapMemoryUsage().getMax();
            jvm.put("heapUsedMb",  heapUsed / (1024 * 1024));
            jvm.put("heapMaxMb",   heapMax > 0 ? heapMax / (1024 * 1024) : -1);
            jvm.put("heapPercent", heapMax > 0 ? Math.round((double) heapUsed / heapMax * 100) : -1);
            result.put("jvm", jvm);

            // ── go2rtc process memory ─────────────────────────────────────────────
            JSONObject go2rtcInfo = new JSONObject();
            long go2rtcRssKb = 0;
            boolean go2rtcAlive = false;
            if (go2RtcManager != null) {
                JSONObject procInfo = go2RtcManager.getProcessInfo();
                go2rtcAlive = procInfo.optBoolean("alive", false);
                go2rtcRssKb = procInfo.optLong("vmRssKb", 0);
            }
            go2rtcInfo.put("alive", go2rtcAlive);
            go2rtcInfo.put("processMemoryMb", go2rtcRssKb / 1024);
            go2rtcInfo.put("processMemoryKb", go2rtcRssKb);
            result.put("go2rtc", go2rtcInfo);

            // ── Per-camera stats ──────────────────────────────────────────────────
            // go2rtc stream data indexed by camera name
            java.util.Map<String, org.json.JSONObject> streamsByName =
                (go2RtcManager != null) ? go2RtcManager.getStreamsByName() : new java.util.HashMap<>();

            // Snapshot tracking from SnapshotHandler
            Set<String> trackedDevices = SnapshotHandler.getTrackedDevices();

            // Union of all known device names
            Map<String, CameraDevice> allDevices = CameraExtensionPoint.getAllDevices();
            java.util.Set<String> allNames = new java.util.HashSet<>();
            allNames.addAll(allDevices.keySet());
            allNames.addAll(streamsByName.keySet());
            allNames.addAll(trackedDevices);

            JSONObject cameras = new JSONObject();
            for (String name : allNames) {
                JSONObject cam = new JSONObject();

                // go2rtc stream metrics
                org.json.JSONObject stream = streamsByName.get(name);
                if (stream != null) {
                    cam.put("go2rtcConsumers",    stream.optInt("consumers", 0));
                    cam.put("go2rtcBitrateKbps",  stream.optLong("bitrateKbps", 0));
                    cam.put("go2rtcProducerState", stream.opt("producerState"));
                    Object tracks = stream.opt("producerTracks");
                    cam.put("go2rtcProducerTracks", tracks != null ? tracks : new org.json.JSONArray());
                } else {
                    cam.put("go2rtcConsumers", 0);
                    cam.put("go2rtcBitrateKbps", 0);
                    cam.put("go2rtcProducerState", JSONObject.NULL);
                    cam.put("go2rtcProducerTracks", new org.json.JSONArray());
                }

                // Snapshot metrics
                Map<String, Object> snapMetrics = SnapshotHandler.getDeviceMetrics(name);
                cam.put("snapshotLastDurationMs",  snapMetrics.get("lastFetchDurationMs"));
                cam.put("snapshotLastTimestampMs", snapMetrics.get("lastFetchTimestampMs"));
                cam.put("snapshotTotalFetches",    snapMetrics.get("totalFetches"));
                cam.put("snapshotErrors",          snapMetrics.get("fetchErrors"));

                // Device status
                CameraDevice device = allDevices.get(name);
                if (device != null) {
                    cam.put("status", device.getStatus());
                    cam.put("onvifAvailable", device.isOnvifAvailable());
                    cam.put("go2rtcRegistered", device.isGo2RtcStreamRegistered());
                }

                cameras.put(name, cam);
            }
            result.put("cameras", cameras);
            result.put("timestamp", System.currentTimeMillis());

            response.setContentType("application/json");
            response.getWriter().write(result.toString());

        } catch (Exception e) {
            logger.error("Error generating metrics", e);
            response.sendError(500, "Internal server error");
        }

        return null;
    }
}
