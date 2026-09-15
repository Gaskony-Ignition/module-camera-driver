package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.common.DeviceStatus;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraDevice;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.onvif.DeviceInformation;
import com.gaskony.camera.gateway.onvif.MediaProfile;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import jakarta.servlet.http.HttpServletResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Handles device listing and status API endpoints.
 * URL: /data/camera-driver/devices and /data/camera-driver/device/:name/status
 */
public class DeviceApiHandler extends BaseHandler {

    public DeviceApiHandler(GatewayContext context,
                            CameraExtensionPoint cameraExtensionPoint,
                            Go2RtcManager go2RtcManager,
                            AuthenticationManager authManager,
                            String moduleVersion) {
        super(context, cameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    public Object handleListDevices(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("List devices request received");

        // P3-CD: shared AccessControl pattern.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }

        try {
            JSONObject result = new JSONObject();
            JSONArray devices = new JSONArray();

            Map<String, CameraDevice> allDevices = CameraExtensionPoint.getAllDevices();
            for (Map.Entry<String, CameraDevice> entry : allDevices.entrySet()) {
                CameraDevice device = entry.getValue();

                if (DeviceStatus.DISABLED.displayName().equalsIgnoreCase(device.getStatus())) {
                    continue;
                }

                JSONObject deviceJson = new JSONObject();
                deviceJson.put("name", entry.getKey());
                deviceJson.put("type", device.isOnvifAvailable() ? "onvif" : "generic");
                deviceJson.put("status", device.getStatus());
                deviceJson.put("onvifAvailable", device.isOnvifAvailable());
                deviceJson.put("hasPTZ", device.hasPTZ());
                deviceJson.put("go2rtcRegistered", device.isGo2RtcStreamRegistered());

                // ONVIF device info — peeked from the device's connect-time cache only.
                // Listing N devices must never fan out into live SOAP calls (the
                // 10-camera scale test timed this endpoint out when the cache was cold
                // right after a gateway restart); a cold value is simply omitted and the
                // UI fills it in once the per-device cache warms up.
                if (device.isOnvifAvailable() && device.getClient() != null) {
                    try {
                        DeviceInformation info = device.peekDeviceInformation();
                        if (info != null) {
                            deviceJson.put("manufacturer", info.manufacturer());
                            deviceJson.put("model", info.model());
                            deviceJson.put("firmwareVersion", info.firmwareVersion());
                            deviceJson.put("serialNumber", info.serialNumber());
                        }

                        List<MediaProfile> profiles = device.peekMediaProfiles();
                        if (profiles != null) {
                            JSONArray profilesJson = new JSONArray();
                            for (MediaProfile profile : profiles) {
                                profilesJson.put(buildOnvifProfileJson(device, profile, false));
                            }
                            deviceJson.put("profiles", profilesJson);
                            deviceJson.put("profileCount", profiles.size());
                        }
                    } catch (Exception e) {
                        logger.debug("Could not get additional info for device {}: {}", entry.getKey(), e.getMessage());
                        try { deviceJson.put("infoError", true); } catch (Exception ignored) {}
                    }
                } else {
                    // Non-ONVIF device — show effective (discovered or override) URLs
                    String rtspUrl = device.getEffectiveRtspUrl();
                    if (rtspUrl != null && !rtspUrl.trim().isEmpty()) {
                        deviceJson.put("rtspUrl", rtspUrl);
                    }
                    String snapshotUrl = device.getEffectiveSnapshotUrl();
                    if (snapshotUrl != null && !snapshotUrl.trim().isEmpty()) {
                        deviceJson.put("snapshotUrl", snapshotUrl);
                    }
                    String mjpegUrl = device.getEffectiveMjpegUrl();
                    if (mjpegUrl != null && !mjpegUrl.trim().isEmpty()) {
                        deviceJson.put("mjpegUrl", mjpegUrl);
                    }

                    JSONArray genericProfiles = buildGenericProfiles(device);
                    if (genericProfiles.length() > 0) {
                        deviceJson.put("profiles", genericProfiles);
                        deviceJson.put("profileCount", genericProfiles.length());
                    }
                }

                devices.put(deviceJson);
            }

            result.put("success", true);
            result.put("devices", devices);
            result.put("count", devices.length());
            result.put("timestamp", System.currentTimeMillis());

            response.setContentType("application/json");
            response.getWriter().write(result.toString());

        } catch (Exception e) {
            logger.error("Error listing devices", e);
            response.sendError(500, "Internal server error");
        }

        return null;
    }

    public Object handleDeviceStatus(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Device status request received");

        String deviceName = requestContext.getParameter("name");
        if (deviceName == null || deviceName.trim().isEmpty()) {
            response.sendError(400, "Missing required parameter: name");
            return null;
        }

        // P3-CD: shared AccessControl pattern.
        if (!requireAuthenticated(requestContext, response)) {
            return null;
        }

        try {
            CameraDevice device = findDevice(deviceName);
            if (device == null) {
                response.sendError(404, "Device not found");
                return null;
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("name", deviceName);
            result.put("type", device.isOnvifAvailable() ? "onvif" : "generic");
            result.put("status", device.getStatus());
            result.put("onvifAvailable", device.isOnvifAvailable());
            result.put("hasPTZ", device.hasPTZ());
            result.put("go2rtcRegistered", device.isGo2RtcStreamRegistered());
            result.put("timestamp", System.currentTimeMillis());

            if (device.isOnvifAvailable() && device.getClient() != null) {
                try {
                    DeviceInformation info = device.getDeviceInformationCached();
                    if (info != null) {
                        JSONObject deviceInfo = new JSONObject();
                        deviceInfo.put("manufacturer", info.manufacturer());
                        deviceInfo.put("model", info.model());
                        deviceInfo.put("firmwareVersion", info.firmwareVersion());
                        deviceInfo.put("serialNumber", info.serialNumber());
                        deviceInfo.put("hardwareId", info.hardwareId());
                        result.put("deviceInfo", deviceInfo);
                    }

                    List<MediaProfile> profiles = device.getMediaProfilesCached();
                    if (profiles != null) {
                        JSONArray profilesJson = new JSONArray();
                        for (MediaProfile profile : profiles) {
                            profilesJson.put(buildOnvifProfileJson(device, profile, true));
                        }
                        result.put("profiles", profilesJson);
                    }
                } catch (Exception e) {
                    logger.debug("Could not get detailed info for device {}: {}", deviceName, e.getMessage());
                }
            } else {
                JSONArray genericProfiles = buildGenericProfiles(device);
                if (genericProfiles.length() > 0) {
                    result.put("profiles", genericProfiles);
                    result.put("profileCount", genericProfiles.length());
                }
            }

            response.setContentType("application/json");
            response.getWriter().write(result.toString());

        } catch (Exception e) {
            logger.error("Error getting device status", e);
            response.sendError(500, "Internal server error");
        }

        return null;
    }

    /**
     * Builds the JSON representation of an ONVIF media profile.
     *
     * @param allowLiveFetch when {@code false} (the LIST endpoint), stream/snapshot
     *                       URIs are peeked from cache only and never trigger a live
     *                       SOAP call — a cold value is simply omitted from the JSON.
     *                       When {@code true} (the single-device status endpoint), a
     *                       cache miss may lazily fetch once, which is acceptable for
     *                       a single device.
     */
    private JSONObject buildOnvifProfileJson(CameraDevice device, MediaProfile profile, boolean allowLiveFetch)
            throws Exception {
        JSONObject profileJson = new JSONObject();
        profileJson.put("token", profile.getToken());
        profileJson.put("name", profile.getName());
        profileJson.put("width", profile.getWidth());
        profileJson.put("height", profile.getHeight());
        profileJson.put("frameRate", profile.getFrameRate());
        profileJson.put("encoding", profile.getEncoding());

        if (allowLiveFetch) {
            // Cached after the first lookup per profile — never a repeated SOAP fan-out.
            try {
                String snapshotUri = device.getSnapshotUriCached(profile.getToken());
                profileJson.put("snapshotUri", snapshotUri);
            } catch (Exception e) {
                logger.debug("Could not get snapshot URI for profile {}: {}", profile.getToken(), e.getMessage());
            }
            try {
                String streamUri = device.getStreamUriCached(profile.getToken());
                profileJson.put("streamUri", streamUri);
            } catch (Exception e) {
                logger.debug("Could not get stream URI for profile {}: {}", profile.getToken(), e.getMessage());
            }
        } else {
            // LIST endpoint — peek only, never blocks on SOAP. Omit if cold.
            String snapshotUri = device.peekSnapshotUri(profile.getToken());
            if (snapshotUri != null) {
                profileJson.put("snapshotUri", snapshotUri);
            }
            String streamUri = device.peekStreamUri(profile.getToken());
            if (streamUri != null) {
                profileJson.put("streamUri", streamUri);
            }
        }

        return profileJson;
    }

    private JSONArray buildGenericProfiles(CameraDevice device) throws Exception {
        JSONArray profiles = new JSONArray();

        // Use effective (discovered or override) URLs, not just config overrides
        String rtspUrl = device.getEffectiveRtspUrl();
        if (rtspUrl != null && !rtspUrl.trim().isEmpty()) {
            JSONObject rtspProfile = new JSONObject();
            rtspProfile.put("token", "rtsp");
            rtspProfile.put("name", "RTSP Stream");
            rtspProfile.put("encoding", "H.264");
            rtspProfile.put("streamUri", rtspUrl);
            profiles.put(rtspProfile);
        }
        String snapshotUrl = device.getEffectiveSnapshotUrl();
        if (snapshotUrl != null && !snapshotUrl.trim().isEmpty()) {
            JSONObject snapshotProfile = new JSONObject();
            snapshotProfile.put("token", "snapshot");
            snapshotProfile.put("name", "HTTP Snapshot");
            snapshotProfile.put("encoding", "JPEG");
            profiles.put(snapshotProfile);
        }
        String mjpegUrl = device.getEffectiveMjpegUrl();
        if (mjpegUrl != null && !mjpegUrl.trim().isEmpty()) {
            JSONObject mjpegProfile = new JSONObject();
            mjpegProfile.put("token", "mjpeg");
            mjpegProfile.put("name", "MJPEG Stream");
            mjpegProfile.put("encoding", "MJPEG");
            mjpegProfile.put("streamUri", mjpegUrl);
            profiles.put(mjpegProfile);
        }
        return profiles;
    }
}
