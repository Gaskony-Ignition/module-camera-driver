package com.onvif.driver.gateway.device.generic;

import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.onvif.driver.gateway.stream.Go2RtcManager;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Generic Camera device implementation.
 *
 * Supports IP cameras that provide RTSP, snapshot, or MJPEG URLs
 * but may not support the ONVIF protocol.
 *
 * On startup:
 * 1. Validates configured URLs
 * 2. Creates HTTP client for snapshot fetching
 * 3. Registers RTSP URL with go2rtc if available
 * 4. Builds OPC-UA address space with stream tags
 * 5. Registers in device registry for servlet access
 *
 * On shutdown:
 * 1. Removes stream from go2rtc
 * 2. Closes HTTP client
 * 3. Unregisters from device registry
 */
public class GenericCameraDevice extends ManagedAddressSpaceWithLifecycle implements Device {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final DeviceContext context;
    private final GenericCameraConfig config;
    private final Go2RtcManager go2RtcManager;
    private final SubscriptionModel subscriptionModel;

    private UaFolderNode rootNode;
    private String deviceStatus = "Initializing";
    private GenericCameraClient cameraClient;
    private GenericCameraAddressSpaceBuilder addressSpaceBuilder;
    private boolean go2RtcStreamRegistered = false;

    public GenericCameraDevice(DeviceContext context, GenericCameraConfig config, Go2RtcManager go2RtcManager) {
        super(context.getServer());

        this.context = context;
        this.config = config;
        this.go2RtcManager = go2RtcManager;

        subscriptionModel = new SubscriptionModel(context.getServer(), this);

        getLifecycleManager().addLifecycle(subscriptionModel);
        getLifecycleManager().addStartupTask(this::onStartup);
        getLifecycleManager().addShutdownTask(this::onShutdown);
    }

    @Override
    public String getStatus() {
        return deviceStatus;
    }

    private void onStartup() {
        logger.info("=== Generic Camera Device Startup: {} ===", context.getName());

        // Register in device registry
        GenericCameraExtensionPoint.registerDevice(context.getName(), this);

        if (!config.general().enabled()) {
            deviceStatus = "Disabled";
            logger.info("Device is disabled: {}", context.getName());
            return;
        }

        try {
            deviceStatus = "Connecting";

            // Resolve password if configured
            String password = resolvePassword();

            String username = config.cameraConnection().username();
            String snapshotUrl = config.cameraConnection().snapshotUrl();
            String mjpegUrl = config.cameraConnection().mjpegUrl();
            String rtspUrl = config.cameraConnection().rtspUrl();

            // Create HTTP client for snapshot/MJPEG access
            cameraClient = new GenericCameraClient(
                snapshotUrl, mjpegUrl,
                username, password,
                config.cameraConnection().timeout()
            );

            // Test HTTP connection if snapshot or MJPEG URL is configured
            boolean httpReachable = false;
            if ((snapshotUrl != null && !snapshotUrl.trim().isEmpty()) ||
                (mjpegUrl != null && !mjpegUrl.trim().isEmpty())) {
                httpReachable = cameraClient.testConnection();
                if (httpReachable) {
                    logger.info("HTTP connection test passed for {}", context.getName());
                } else {
                    logger.warn("HTTP connection test failed for {} - camera may be unreachable", context.getName());
                }
            }

            // Register RTSP stream with go2rtc if available
            boolean go2RtcAvailable = false;
            if (rtspUrl != null && !rtspUrl.trim().isEmpty() && go2RtcManager != null) {
                // Build RTSP URL with credentials if provided
                String effectiveRtspUrl = buildAuthenticatedRtspUrl(rtspUrl, username, password);

                if (go2RtcManager.isAvailable()) {
                    go2RtcStreamRegistered = go2RtcManager.addStream(context.getName(), effectiveRtspUrl);
                    go2RtcAvailable = go2RtcStreamRegistered;
                    if (go2RtcStreamRegistered) {
                        logger.info("RTSP stream registered with go2rtc: {}", context.getName());
                    }
                } else {
                    logger.info("go2rtc not available - RTSP stream will not be converted for browser playback");
                }
            }

            // Build OPC-UA address space
            deviceStatus = "Building Address Space";
            createRootNode();

            addressSpaceBuilder = new GenericCameraAddressSpaceBuilder(
                context, getNodeContext(), rootNode, getNodeManager()::addNode
            );
            addressSpaceBuilder.buildStreamInfo(config);

            String connectionStatus = httpReachable || go2RtcStreamRegistered ? "Connected" : "URL-Only";
            addressSpaceBuilder.buildStatus(connectionStatus, go2RtcAvailable);

            deviceStatus = httpReachable || go2RtcStreamRegistered ? "Running" : "Connected";
            logger.info("=== Generic Camera Device Started: {} (Status: {}) ===", context.getName(), deviceStatus);

        } catch (Exception e) {
            deviceStatus = "Error: " + e.getMessage();
            logger.error("Failed to start Generic Camera device: {}", context.getName(), e);
        }
    }

    private void onShutdown() {
        logger.info("Shutting down Generic Camera device: {}", context.getName());

        // Remove stream from go2rtc
        if (go2RtcStreamRegistered && go2RtcManager != null) {
            go2RtcManager.removeStream(context.getName());
            go2RtcStreamRegistered = false;
        }

        // Close HTTP client
        if (cameraClient != null) {
            try {
                cameraClient.close();
            } catch (Exception e) {
                logger.error("Error closing camera client", e);
            }
        }

        deviceStatus = "Stopped";

        // Unregister from device registry
        GenericCameraExtensionPoint.unregisterDevice(context.getName());
        logger.info("Generic Camera device shutdown complete: {}", context.getName());
    }

    /**
     * Resolves the password from SecretConfig, returns null if not configured.
     */
    private String resolvePassword() {
        if (config.cameraConnection().password() == null) {
            return null;
        }
        try (Plaintext plaintext = Secret.create(context.getGatewayContext(), config.cameraConnection().password()).getPlaintext()) {
            return plaintext.getAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("Failed to retrieve password from SecretConfig: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds an RTSP URL with embedded credentials for go2rtc.
     * e.g., rtsp://user:pass@192.168.1.50:554/stream1
     */
    private String buildAuthenticatedRtspUrl(String rtspUrl, String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return rtspUrl;
        }

        try {
            // Parse the RTSP URL and inject credentials
            // Expected format: rtsp://host:port/path or rtsps://host:port/path
            String protocol;
            String remainder;
            if (rtspUrl.startsWith("rtsps://")) {
                protocol = "rtsps://";
                remainder = rtspUrl.substring(8);
            } else if (rtspUrl.startsWith("rtsp://")) {
                protocol = "rtsp://";
                remainder = rtspUrl.substring(7);
            } else {
                return rtspUrl;
            }

            // Check if credentials are already embedded
            if (remainder.contains("@")) {
                return rtspUrl;
            }

            String credentials = username;
            if (password != null && !password.isEmpty()) {
                credentials += ":" + password;
            }

            return protocol + credentials + "@" + remainder;
        } catch (Exception e) {
            logger.warn("Failed to build authenticated RTSP URL, using original: {}", e.getMessage());
            return rtspUrl;
        }
    }

    /**
     * Returns the RTSP URL with stored credentials embedded, for display in the UI.
     * If no credentials are configured, returns the original URL.
     */
    public String getAuthenticatedRtspUrl() {
        String rtspUrl = config.cameraConnection().rtspUrl();
        if (rtspUrl == null || rtspUrl.trim().isEmpty()) {
            return rtspUrl;
        }
        String username = config.cameraConnection().username();
        String password = resolvePassword();
        return buildAuthenticatedRtspUrl(rtspUrl, username, password);
    }

    private void createRootNode() {
        String deviceName = context.getName();

        rootNode = new UaFolderNode(
            getNodeContext(),
            context.nodeId(deviceName),
            context.qualifiedName(String.format("[%s]", deviceName)),
            new LocalizedText(String.format("[%s]", deviceName))
        );

        getNodeManager().addNode(rootNode);

        rootNode.addReference(new Reference(
            rootNode.getNodeId(),
            NodeIds.Organizes,
            context.getRootNodeId().expanded(),
            Reference.Direction.INVERSE
        ));

        logger.info("Created root node: [{}]", deviceName);
    }

    /**
     * Gets the camera client for HTTP operations.
     */
    public GenericCameraClient getCameraClient() {
        return cameraClient;
    }

    /**
     * Gets the device configuration.
     */
    public GenericCameraConfig getConfig() {
        return config;
    }

    /**
     * Returns whether this device has a stream registered with go2rtc.
     */
    public boolean isGo2RtcStreamRegistered() {
        return go2RtcStreamRegistered;
    }

    /**
     * Attempts to register this device's RTSP stream with go2rtc if not already registered.
     * This handles the case where go2rtc wasn't available during device startup.
     *
     * @return true if the stream is registered (either already was, or was just registered)
     */
    public boolean tryRegisterGo2Rtc() {
        if (go2RtcStreamRegistered) {
            return true;
        }

        String rtspUrl = config.cameraConnection().rtspUrl();
        if (rtspUrl == null || rtspUrl.trim().isEmpty() || go2RtcManager == null) {
            return false;
        }

        if (!go2RtcManager.isAvailable()) {
            return false;
        }

        String password = resolvePassword();
        String username = config.cameraConnection().username();
        String effectiveRtspUrl = buildAuthenticatedRtspUrl(rtspUrl, username, password);
        go2RtcStreamRegistered = go2RtcManager.addStream(context.getName(), effectiveRtspUrl);

        if (go2RtcStreamRegistered) {
            logger.info("Late registration of RTSP stream with go2rtc succeeded for device: {}", context.getName());
            deviceStatus = "Running";
        }

        return go2RtcStreamRegistered;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void onMonitoringModeChanged(List items) {
        subscriptionModel.onMonitoringModeChanged(items);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void onDataItemsCreated(List items) {
        subscriptionModel.onDataItemsCreated(items);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void onDataItemsModified(List items) {
        subscriptionModel.onDataItemsModified(items);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void onDataItemsDeleted(List items) {
        subscriptionModel.onDataItemsDeleted(items);
    }
}
