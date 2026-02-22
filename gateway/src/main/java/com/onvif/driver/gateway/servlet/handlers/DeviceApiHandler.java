package com.onvif.driver.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.onvif.driver.common.DeviceStatus;
import com.onvif.driver.gateway.auth.AuthenticationManager;
import com.onvif.driver.gateway.device.ONVIFDevice;
import com.onvif.driver.gateway.device.ONVIFDeviceExtensionPoint;
import com.onvif.driver.gateway.device.generic.GenericCameraConfig;
import com.onvif.driver.gateway.device.generic.GenericCameraDevice;
import com.onvif.driver.gateway.device.generic.GenericCameraExtensionPoint;
import com.onvif.driver.gateway.onvif.DeviceInformation;
import com.onvif.driver.gateway.onvif.MediaProfile;
import com.onvif.driver.gateway.stream.Go2RtcManager;
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
                            ONVIFDeviceExtensionPoint deviceExtensionPoint,
                            GenericCameraExtensionPoint genericCameraExtensionPoint,
                            Go2RtcManager go2RtcManager,
                            AuthenticationManager authManager,
                            String moduleVersion) {
        super(context, deviceExtensionPoint, genericCameraExtensionPoint, go2RtcManager, authManager, moduleVersion);
    }

    /**
     * Handles listing all devices.
     * URL: http://gateway:8088/data/camera-driver/devices
     */
    public Object handleListDevices(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("List devices request received");

        if (!isAuthenticated(requestContext)) {
            sendAuthenticationRequired(response);
            return null;
        }

        try {
            JSONObject result = new JSONObject();
            JSONArray devices = new JSONArray();

            Map<String, ONVIFDevice> allDevices = ONVIFDeviceExtensionPoint.getAllDevices();
            for (Map.Entry<String, ONVIFDevice> entry : allDevices.entrySet()) {
                ONVIFDevice device = entry.getValue();

                // Skip disabled devices — they have no active configuration
                if (DeviceStatus.DISABLED.displayName().equalsIgnoreCase(device.getStatus())) {
                    continue;
                }

                JSONObject deviceJson = new JSONObject();
                deviceJson.put("name", entry.getKey());
                deviceJson.put("type", "onvif");
                deviceJson.put("status", device.getStatus());

                // Try to get additional info from the device's client
                try {
                    if (device.getClient() != null) {
                        DeviceInformation info = device.getClient().getDeviceInformation();
                        if (info != null) {
                            deviceJson.put("manufacturer", info.manufacturer());
                            deviceJson.put("model", info.model());
                            deviceJson.put("firmwareVersion", info.firmwareVersion());
                            deviceJson.put("serialNumber", info.serialNumber());
                        }

                        List<MediaProfile> profiles = device.getClient().getMediaProfiles();
                        if (profiles != null) {
                            JSONArray profilesJson = new JSONArray();
                            for (MediaProfile profile : profiles) {
                                profilesJson.put(buildOnvifProfileJson(device, profile));
                            }
                            deviceJson.put("profiles", profilesJson);
                            deviceJson.put("profileCount", profiles.size());
                        }
                    }
                    deviceJson.put("go2rtcRegistered", device.isGo2RtcStreamRegistered());
                } catch (Exception e) {
                    logger.debug("Could not get additional info for device {}: {}", entry.getKey(), e.getMessage());
                    try { deviceJson.put("infoError", true); } catch (Exception ignored) {}
                }

                devices.put(deviceJson);
            }

            // Include generic cameras
            if (genericCameraExtensionPoint != null) {
                Map<String, GenericCameraDevice> genericDevices = GenericCameraExtensionPoint.getAllDevices();
                for (Map.Entry<String, GenericCameraDevice> entry : genericDevices.entrySet()) {
                    GenericCameraDevice device = entry.getValue();

                    // Skip disabled devices — they have no active configuration
                    if (DeviceStatus.DISABLED.displayName().equalsIgnoreCase(device.getStatus())) {
                        continue;
                    }

                    JSONObject deviceJson = new JSONObject();
                    deviceJson.put("name", entry.getKey());
                    deviceJson.put("type", "generic");
                    deviceJson.put("status", device.getStatus());

                    GenericCameraConfig cfg = device.getConfig();
                    if (cfg.cameraConnection().rtspUrl() != null) {
                        deviceJson.put("rtspUrl", cfg.cameraConnection().rtspUrl());
                    }
                    if (cfg.cameraConnection().snapshotUrl() != null) {
                        deviceJson.put("snapshotUrl", cfg.cameraConnection().snapshotUrl());
                    }
                    if (cfg.cameraConnection().mjpegUrl() != null) {
                        deviceJson.put("mjpegUrl", cfg.cameraConnection().mjpegUrl());
                    }
                    deviceJson.put("go2rtcRegistered", device.isGo2RtcStreamRegistered());

                    JSONArray genericProfiles = buildGenericCameraProfiles(device);
                    if (genericProfiles.length() > 0) {
                        deviceJson.put("profiles", genericProfiles);
                        deviceJson.put("profileCount", genericProfiles.length());
                    }

                    devices.put(deviceJson);
                }
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

    /**
     * Handles getting status of a specific device (ONVIF or Generic Camera).
     * URL: http://gateway:8088/data/camera-driver/device/:name/status
     */
    public Object handleDeviceStatus(RequestContext requestContext, HttpServletResponse response) throws Exception {
        logger.debug("Device status request received");

        String deviceName = requestContext.getParameter("name");
        if (deviceName == null || deviceName.trim().isEmpty()) {
            response.sendError(400, "Missing required parameter: name");
            return null;
        }

        if (!isAuthenticated(requestContext)) {
            sendAuthenticationRequired(response);
            return null;
        }

        try {
            // Check both registries
            DeviceLookup devices = findDevice(deviceName);
            if (!devices.found()) {
                response.sendError(404, "Device not found");
                return null;
            }
            ONVIFDevice onvifDevice = devices.onvif();
            GenericCameraDevice genericDevice = devices.generic();

            // Use whichever device was found (ONVIF device block follows for backward compatibility)
            if (genericDevice != null && onvifDevice == null) {
                // Return generic camera status
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("name", deviceName);
                result.put("type", "generic");
                result.put("status", genericDevice.getStatus());
                result.put("go2rtcRegistered", genericDevice.isGo2RtcStreamRegistered());
                result.put("timestamp", System.currentTimeMillis());

                JSONArray genericProfiles = buildGenericCameraProfiles(genericDevice);
                if (genericProfiles.length() > 0) {
                    result.put("profiles", genericProfiles);
                    result.put("profileCount", genericProfiles.length());
                }

                response.setContentType("application/json");
                response.getWriter().write(result.toString());
                return null;
            }

            // ONVIF device (original behavior)
            ONVIFDevice device = onvifDevice;

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("name", deviceName);
            result.put("status", device.getStatus());
            result.put("timestamp", System.currentTimeMillis());

            // Add detailed info if available
            try {
                if (device.getClient() != null) {
                    DeviceInformation info = device.getClient().getDeviceInformation();
                    if (info != null) {
                        JSONObject deviceInfo = new JSONObject();
                        deviceInfo.put("manufacturer", info.manufacturer());
                        deviceInfo.put("model", info.model());
                        deviceInfo.put("firmwareVersion", info.firmwareVersion());
                        deviceInfo.put("serialNumber", info.serialNumber());
                        deviceInfo.put("hardwareId", info.hardwareId());
                        result.put("deviceInfo", deviceInfo);
                    }

                    List<MediaProfile> profiles = device.getClient().getMediaProfiles();
                    if (profiles != null) {
                        JSONArray profilesJson = new JSONArray();
                        for (MediaProfile profile : profiles) {
                            profilesJson.put(buildOnvifProfileJson(device, profile));
                        }
                        result.put("profiles", profilesJson);
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not get detailed info for device {}: {}", deviceName, e.getMessage());
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
     * Builds a JSON object for a single ONVIF MediaProfile, including snapshot and stream URIs.
     * Single source of truth for the /devices and /device/:name/status endpoints.
     */
    private JSONObject buildOnvifProfileJson(ONVIFDevice device, MediaProfile profile) throws Exception {
        JSONObject profileJson = new JSONObject();
        profileJson.put("token", profile.getToken());
        profileJson.put("name", profile.getName());
        profileJson.put("width", profile.getWidth());
        profileJson.put("height", profile.getHeight());
        profileJson.put("frameRate", profile.getFrameRate());
        profileJson.put("encoding", profile.getEncoding());

        try {
            String snapshotUri = device.getClient().getSnapshotUri(profile.getToken());
            profileJson.put("snapshotUri", device.getAuthenticatedUrl(snapshotUri));
        } catch (Exception e) {
            logger.debug("Could not get snapshot URI for profile {}: {}", profile.getToken(), e.getMessage());
        }
        try {
            String streamUri = device.getClient().getStreamUri(profile.getToken());
            profileJson.put("streamUri", device.getAuthenticatedUrl(streamUri));
        } catch (Exception e) {
            logger.debug("Could not get stream URI for profile {}: {}", profile.getToken(), e.getMessage());
        }

        return profileJson;
    }

    /**
     * Builds the synthetic profiles JSON array for a Generic Camera device.
     * Single source of truth for the /devices and /device/:name/status endpoints.
     */
    private JSONArray buildGenericCameraProfiles(GenericCameraDevice device) throws Exception {
        GenericCameraConfig cfg = device.getConfig();
        JSONArray profiles = new JSONArray();

        String rtspUrl = device.getAuthenticatedRtspUrl();
        if (rtspUrl != null && !rtspUrl.trim().isEmpty()) {
            JSONObject rtspProfile = new JSONObject();
            rtspProfile.put("token", "rtsp");
            rtspProfile.put("name", "RTSP Stream");
            rtspProfile.put("encoding", "H.264");
            rtspProfile.put("streamUri", rtspUrl);
            int fps = cfg.streamSettings() != null ? cfg.streamSettings().defaultFps() : 15;
            rtspProfile.put("frameRate", fps);
            profiles.put(rtspProfile);
        }
        String snapshotUrl = cfg.cameraConnection().snapshotUrl();
        if (snapshotUrl != null && !snapshotUrl.trim().isEmpty()) {
            JSONObject snapshotProfile = new JSONObject();
            snapshotProfile.put("token", "snapshot");
            snapshotProfile.put("name", "HTTP Snapshot");
            snapshotProfile.put("encoding", "JPEG");
            profiles.put(snapshotProfile);
        }
        String mjpegUrl = cfg.cameraConnection().mjpegUrl();
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
