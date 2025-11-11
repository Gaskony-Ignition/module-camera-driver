package com.onvif.driver.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.onvif.driver.gateway.onvif.DeviceInformation;
import com.onvif.driver.gateway.onvif.MediaProfile;
import com.onvif.driver.gateway.onvif.ONVIFClient;
import com.onvif.driver.gateway.onvif.ONVIFService;
import com.onvif.driver.gateway.onvif.PTZStatus;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * ONVIF Device implementation.
 *
 * This device:
 * 1. Connects to an ONVIF-compatible IP camera/device via network
 * 2. Authenticates using provided credentials
 * 3. Discovers available ONVIF services
 * 4. Creates hierarchical OPC-UA address space with device data
 * 5. Polls device for updates at configured interval
 *
 * TODO: Implement ONVIF protocol communication
 * - SOAP/XML requests for ONVIF services
 * - WS-Discovery for service discovery
 * - Authentication (WS-UsernameToken)
 * - Parse ONVIF responses
 * - Create OPC-UA nodes from ONVIF data
 */
public class ONVIFDevice extends ManagedAddressSpaceWithLifecycle implements Device {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final DeviceContext context;
    private final ONVIFDeviceConfig config;
    private final SubscriptionModel subscriptionModel;

    private UaFolderNode rootNode;
    private String deviceStatus = "Initializing";
    private ONVIFClient onvifClient;
    private ONVIFPoller poller;
    private AddressSpaceBuilder addressSpaceBuilder;

    // Auto-reconnect state
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int BASE_RETRY_DELAY_MS = 1000; // 1 second

    /**
     * Creates a new ONVIF Device.
     *
     * @param context Device context provided by Ignition
     * @param config Device configuration from user
     */
    public ONVIFDevice(DeviceContext context, ONVIFDeviceConfig config) {
        super(context.getServer());

        this.context = context;
        this.config = config;

        subscriptionModel = new SubscriptionModel(context.getServer(), this);

        getLifecycleManager().addLifecycle(subscriptionModel);
        getLifecycleManager().addStartupTask(this::onStartup);
        getLifecycleManager().addShutdownTask(this::onShutdown);
    }

    @Override
    public String getStatus() {
        return deviceStatus;
    }

    /**
     * Called when device starts up.
     * Connects to ONVIF device and builds address space.
     *
     * Note: ManagedAddressSpaceWithLifecycle handles address space registration automatically.
     * The critical step for visibility is adding the inverse reference in createRootNode().
     */
    private void onStartup() {
        logger.info("=== ONVIF Device Startup: {} ===", context.getName());
        connectWithRetry();
    }

    /**
     * Connects to ONVIF device with retry logic and exponential backoff.
     */
    private void connectWithRetry() {
        for (reconnectAttempts = 0; reconnectAttempts < MAX_RECONNECT_ATTEMPTS; reconnectAttempts++) {
            try {
                if (reconnectAttempts > 0) {
                    int delayMs = calculateRetryDelay(reconnectAttempts);
                    logger.info("Retry attempt {}/{} after {}ms delay",
                        reconnectAttempts + 1, MAX_RECONNECT_ATTEMPTS, delayMs);
                    Thread.sleep(delayMs);
                }

                performConnection();

                // Success - reset reconnect counter
                reconnectAttempts = 0;
                return;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                deviceStatus = "Error: Connection interrupted";
                logger.error("Connection interrupted", e);
                return;

            } catch (Exception e) {
                deviceStatus = String.format("Error (attempt %d/%d): %s",
                    reconnectAttempts + 1, MAX_RECONNECT_ATTEMPTS, e.getMessage());
                logger.error("Connection attempt {}/{} failed: {}",
                    reconnectAttempts + 1, MAX_RECONNECT_ATTEMPTS, e.getMessage());

                if (reconnectAttempts + 1 >= MAX_RECONNECT_ATTEMPTS) {
                    deviceStatus = "Error: Max connection attempts exceeded";
                    logger.error("Failed to connect after {} attempts", MAX_RECONNECT_ATTEMPTS);
                }
            }
        }
    }

    /**
     * Performs the actual connection and initialization.
     */
    private void performConnection() throws Exception {
        logger.info("=== Starting ONVIF Device Connection ===");
        logger.info("Device Name: {}", context.getName());
        logger.info("IP Address: {}", config.connection().ipAddress());
        logger.info("Port: {}", config.connection().port());
        logger.info("Username: {}", config.connection().username());
        logger.info("Use HTTPS: {}", config.connection().useHttps());
        logger.info("Connection Timeout: {} seconds", config.connection().timeout());
        logger.info("Poll Interval: {} seconds", config.onvif().pollInterval());
        logger.info("Auto-discover: {}", config.onvif().autoDiscover());

        deviceStatus = "Connecting";

        // Retrieve password from SecretConfig
        String password;
        if (config.connection().password() != null) {
            try (Plaintext plaintext = Secret.create(context.getGatewayContext(), config.connection().password()).getPlaintext()) {
                password = plaintext.getAsString(StandardCharsets.UTF_8);
                logger.info("Password retrieved successfully from SecretConfig");
            } catch (Exception e) {
                logger.error("Failed to retrieve password from SecretConfig", e);
                throw new RuntimeException("Failed to retrieve password", e);
            }
        } else {
            throw new IllegalArgumentException("Password is required");
        }

        // Create ONVIF client
        logger.info("Creating ONVIF client with endpoint: {}://{}:{}",
            config.connection().useHttps() ? "https" : "http",
            config.connection().ipAddress(),
            config.connection().port());

        onvifClient = new ONVIFClient(
            config.connection().ipAddress(),
            config.connection().port(),
            config.connection().username(),
            password,
            config.connection().useHttps(),
            config.connection().timeout()
        );

        // Test connection
        logger.info("Testing connection to ONVIF device at {}:{}...",
            config.connection().ipAddress(), config.connection().port());
        if (!onvifClient.testConnection()) {
            logger.error("❌ Connection test FAILED to {}:{}",
                config.connection().ipAddress(), config.connection().port());
            throw new IOException("Connection test failed");
        }
        logger.info("✅ Connection test PASSED");

        deviceStatus = "Discovering Services";

        // Get device information
        logger.info("Retrieving device information...");
        DeviceInformation deviceInfo = onvifClient.getDeviceInformation();
        if (deviceInfo == null) {
            logger.error("❌ Failed to retrieve device information");
            throw new IOException("Failed to retrieve device information");
        }

        logger.info("✅ Device Information Retrieved:");
        logger.info("  - Manufacturer: {}", deviceInfo.manufacturer());
        logger.info("  - Model: {}", deviceInfo.model());
        logger.info("  - Firmware: {}", deviceInfo.firmwareVersion());
        logger.info("  - Serial Number: {}", deviceInfo.serialNumber());
        logger.info("  - Hardware ID: {}", deviceInfo.hardwareId());

        // Discover services if configured
        List<ONVIFService> services = null;
        List<MediaProfile> mediaProfiles = null;
        boolean hasPTZ = false;

        if (config.onvif().autoDiscover()) {
            logger.info("Auto-discovering ONVIF services...");
            services = onvifClient.getServices();
            if (services != null && !services.isEmpty()) {
                logger.info("✅ Discovered {} ONVIF service(s):", services.size());
                for (ONVIFService service : services) {
                    logger.info("  - {} (v{}): {}",
                        service.getServiceName(),
                        service.getVersion(),
                        service.getXAddr());
                }
            } else {
                logger.warn("⚠️ No ONVIF services discovered");
            }

            // Get media profiles if media service available
            if (services != null) {
                for (ONVIFService service : services) {
                    if (service.getServiceName().equalsIgnoreCase("media")) {
                        try {
                            logger.info("Retrieving media profiles...");
                            mediaProfiles = onvifClient.getMediaProfiles();
                            if (mediaProfiles != null && !mediaProfiles.isEmpty()) {
                                logger.info("✅ Retrieved {} media profile(s):", mediaProfiles.size());
                                for (MediaProfile profile : mediaProfiles) {
                                    logger.info("  - {}: {}x{} @ {}fps ({})",
                                        profile.getName(),
                                        profile.getWidth(),
                                        profile.getHeight(),
                                        profile.getFrameRate(),
                                        profile.getEncoding());
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("⚠️ Failed to get media profiles: {}", e.getMessage());
                        }
                    } else if (service.getServiceName().equalsIgnoreCase("ptz")) {
                        hasPTZ = true;
                        logger.info("✅ PTZ support detected");
                    }
                }
            }
        } else {
            logger.info("Auto-discover disabled, skipping service discovery");
        }

        // Create OPC-UA address space
        deviceStatus = "Building Address Space";
        logger.info("Creating OPC UA address space...");
        createRootNode();
        buildAddressSpace(deviceInfo, services, mediaProfiles, hasPTZ);
        logger.info("✅ OPC UA address space created successfully");

        // Start polling if configured
        if (config.onvif().pollInterval() > 0) {
            logger.info("Starting polling with interval: {} seconds", config.onvif().pollInterval());
            startPolling(mediaProfiles, hasPTZ);
            logger.info("✅ Polling started successfully");
        } else {
            logger.info("Polling disabled (interval = 0)");
        }

        deviceStatus = "Running";
        logger.info("===========================================");
        logger.info("✅ ONVIF DEVICE STARTED SUCCESSFULLY");
        logger.info("Device Name: {}", context.getName());
        logger.info("Status: CONNECTED AND RUNNING");
        logger.info("Endpoint: {}://{}:{}",
            config.connection().useHttps() ? "https" : "http",
            config.connection().ipAddress(),
            config.connection().port());
        logger.info("Device: {} {} ({})",
            deviceInfo.manufacturer(),
            deviceInfo.model(),
            deviceInfo.firmwareVersion());
        logger.info("Services: {} discovered", services != null ? services.size() : 0);
        logger.info("Media Profiles: {} available", mediaProfiles != null ? mediaProfiles.size() : 0);
        logger.info("PTZ Support: {}", hasPTZ ? "YES" : "NO");
        logger.info("Tags should now be visible in Tag Browser");
        logger.info("Navigate to: OPC UA > [{}]", context.getName());
        logger.info("===========================================");
    }

    /**
     * Calculates retry delay with exponential backoff.
     */
    private int calculateRetryDelay(int attemptNumber) {
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s
        return BASE_RETRY_DELAY_MS * (int) Math.pow(2, attemptNumber - 1);
    }

    /**
     * Called when device shuts down.
     */
    private void onShutdown() {
        logger.info("Shutting down ONVIF device: {}", context.getName());

        // Stop polling
        if (poller != null) {
            try {
                poller.stop();
                logger.info("Polling stopped");
            } catch (Exception e) {
                logger.error("Error stopping poller", e);
            }
        }

        // Close ONVIF client
        if (onvifClient != null) {
            try {
                onvifClient.close();
                logger.info("ONVIF client closed");
            } catch (Exception e) {
                logger.error("Error closing ONVIF client", e);
            }
        }

        deviceStatus = "Stopped";
        logger.info("Device shutdown complete: {}", context.getName());
    }

    /**
     * Creates the root folder node for this device.
     */
    private void createRootNode() {
        String deviceName = config.general().deviceName();

        rootNode = new UaFolderNode(
            getNodeContext(),
            context.nodeId(deviceName),
            context.qualifiedName(String.format("[%s]", deviceName)),
            new LocalizedText(String.format("[%s]", deviceName))
        );

        // Add the folder node to the server
        getNodeManager().addNode(rootNode);

        // CRITICAL: Add reference to the root "Devices" folder node
        // Without this, the device will not be visible in the tag browser!
        rootNode.addReference(new Reference(
            rootNode.getNodeId(),
            NodeIds.Organizes,
            context.getRootNodeId().expanded(),
            Reference.Direction.INVERSE
        ));

        logger.info("Created root node: [{}]", deviceName);
    }

    /**
     * Builds the OPC-UA address space from ONVIF data.
     *
     * @param deviceInfo Device information from ONVIF
     * @param services List of discovered services (may be null)
     * @param mediaProfiles List of media profiles (may be null)
     * @param hasPTZ Whether PTZ is available
     */
    private void buildAddressSpace(DeviceInformation deviceInfo, List<ONVIFService> services,
                                  List<MediaProfile> mediaProfiles, boolean hasPTZ) {
        logger.info("Building OPC-UA address space from ONVIF data");

        addressSpaceBuilder = new AddressSpaceBuilder(context, getNodeContext(), rootNode, onvifClient);

        // Build device information section
        if (deviceInfo != null) {
            addressSpaceBuilder.buildDeviceInfo(deviceInfo);
        }

        // Build services section if discovered
        if (services != null && !services.isEmpty()) {
            addressSpaceBuilder.buildServices(services);
        }

        // Build media profiles section
        if (mediaProfiles != null && !mediaProfiles.isEmpty()) {
            addressSpaceBuilder.buildMediaProfiles(mediaProfiles);
        }

        // Build PTZ section if available
        if (hasPTZ && mediaProfiles != null && !mediaProfiles.isEmpty()) {
            try {
                String profileToken = mediaProfiles.get(0).getToken();
                PTZStatus initialStatus = onvifClient.getPTZStatus(profileToken);
                addressSpaceBuilder.buildPTZ(initialStatus, profileToken);
            } catch (Exception e) {
                logger.warn("Failed to initialize PTZ: {}", e.getMessage());
            }
        }

        // Build connection status section with details
        addressSpaceBuilder.buildConnectionStatus(
            "Connected",
            config.connection().ipAddress(),
            config.connection().port(),
            config.connection().useHttps(),
            deviceInfo
        );

        logger.info("Address space built successfully");
    }

    /**
     * Starts the polling mechanism.
     */
    private void startPolling(List<MediaProfile> mediaProfiles, boolean hasPTZ) {
        logger.info("Starting polling with interval: {} seconds", config.onvif().pollInterval());

        poller = new ONVIFPoller(onvifClient, config.onvif().pollInterval());
        poller.setMediaProfiles(mediaProfiles);
        poller.setHasPTZ(hasPTZ);

        // Set update callback
        poller.setUpdateCallback(this::handlePollingUpdates);

        poller.start();
    }

    /**
     * Handles polling updates from the ONVIF device.
     */
    private void handlePollingUpdates(Map<String, Object> updates) {
        try {
            // Update OPC-UA nodes with new values
            if (addressSpaceBuilder != null) {
                addressSpaceBuilder.updateVariableValues(updates);
            }

            // Check for errors
            if (updates.containsKey("Status/ConnectionStatus")) {
                String status = (String) updates.get("Status/ConnectionStatus");
                if ("Error".equals(status)) {
                    // Attempt reconnection
                    logger.warn("Polling detected error - attempting reconnection");
                    attemptReconnect();
                }
            }

        } catch (Exception e) {
            logger.error("Error handling polling updates", e);
        }
    }

    /**
     * Attempts to reconnect to the device.
     */
    private void attemptReconnect() {
        logger.info("Attempting to reconnect to device...");

        try {
            // Stop current polling
            if (poller != null) {
                poller.stop();
            }

            // Close current client
            if (onvifClient != null) {
                onvifClient.close();
            }

            // Reconnect with retry logic
            connectWithRetry();

        } catch (Exception e) {
            logger.error("Reconnection failed", e);
            deviceStatus = "Error: Reconnection failed - " + e.getMessage();
        }
    }

    /**
     * Called when the monitoring mode of items changes.
     * Required by AddressSpace interface.
     *
     * @param items List of monitored items that changed
     */
    @Override
    @SuppressWarnings("rawtypes")  // SDK interface uses raw types
    public void onMonitoringModeChanged(List items) {
        subscriptionModel.onMonitoringModeChanged(items);
    }

    /**
     * Called when data items are created.
     * Required by AddressSpace interface.
     *
     * @param items List of data items that were created
     */
    @Override
    @SuppressWarnings("rawtypes")  // SDK interface uses raw types
    public void onDataItemsCreated(List items) {
        subscriptionModel.onDataItemsCreated(items);
    }

    /**
     * Called when data items are modified.
     * Required by AddressSpace interface.
     *
     * @param items List of data items that were modified
     */
    @Override
    @SuppressWarnings("rawtypes")  // SDK interface uses raw types
    public void onDataItemsModified(List items) {
        subscriptionModel.onDataItemsModified(items);
    }

    /**
     * Called when data items are deleted.
     * Required by AddressSpace interface.
     *
     * @param items List of data items that were deleted
     */
    @Override
    @SuppressWarnings("rawtypes")  // SDK interface uses raw types
    public void onDataItemsDeleted(List items) {
        subscriptionModel.onDataItemsDeleted(items);
    }
}
