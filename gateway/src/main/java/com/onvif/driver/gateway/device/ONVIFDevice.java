package com.onvif.driver.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.onvif.driver.gateway.onvif.DeviceInformation;
import com.onvif.driver.gateway.onvif.MediaProfile;
import com.onvif.driver.gateway.onvif.ONVIFClient;
import com.onvif.driver.gateway.onvif.ONVIFService;
import com.onvif.driver.gateway.onvif.PTZStatus;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
     */
    private void onStartup() {
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
        logger.info("Starting ONVIF device: {}", context.getName());
        deviceStatus = "Connecting";

        // Create ONVIF client
        onvifClient = new ONVIFClient(
            config.connection().ipAddress(),
            config.connection().port(),
            config.connection().username(),
            config.connection().password(),
            config.connection().useHttps(),
            config.connection().timeout()
        );

        // Test connection
        logger.info("Testing connection to ONVIF device...");
        if (!onvifClient.testConnection()) {
            throw new IOException("Connection test failed");
        }

        deviceStatus = "Discovering Services";

        // Get device information
        DeviceInformation deviceInfo = onvifClient.getDeviceInformation();
        if (deviceInfo == null) {
            throw new IOException("Failed to retrieve device information");
        }

        logger.info("Connected to ONVIF device: {}", deviceInfo);

        // Discover services if configured
        List<ONVIFService> services = null;
        List<MediaProfile> mediaProfiles = null;
        boolean hasPTZ = false;

        if (config.onvif().autoDiscover()) {
            logger.info("Discovering ONVIF services...");
            services = onvifClient.getServices();
            logger.info("Discovered {} ONVIF services", services != null ? services.size() : 0);

            // Get media profiles if media service available
            if (services != null) {
                for (ONVIFService service : services) {
                    if (service.getServiceName().equalsIgnoreCase("media")) {
                        try {
                            mediaProfiles = onvifClient.getMediaProfiles();
                            logger.info("Retrieved {} media profiles", mediaProfiles.size());
                        } catch (Exception e) {
                            logger.warn("Failed to get media profiles: {}", e.getMessage());
                        }
                    } else if (service.getServiceName().equalsIgnoreCase("ptz")) {
                        hasPTZ = true;
                    }
                }
            }
        }

        // Create OPC-UA address space
        deviceStatus = "Building Address Space";
        createRootNode();
        buildAddressSpace(deviceInfo, services, mediaProfiles, hasPTZ);

        // Start polling if configured
        if (config.onvif().pollInterval() > 0) {
            startPolling(mediaProfiles, hasPTZ);
        }

        deviceStatus = "Running";
        logger.info("ONVIF device started successfully: {}", context.getName());
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

        // TODO: Add child nodes for ONVIF data
        // - Device Information
        // - Network Settings
        // - Media Profiles
        // - PTZ Status (if supported)
        // - etc.

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

        // Build connection status section
        addressSpaceBuilder.buildConnectionStatus("Connected");

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
    public void onMonitoringModeChanged(List items) {
        // Handle monitoring mode changes if needed
        subscriptionModel.onMonitoringModeChanged(items);
    }

    /**
     * Called when data items are created.
     * Required by AddressSpace interface.
     *
     * @param items List of data items that were created
     */
    @Override
    public void onDataItemsCreated(List items) {
        // Handle data item creation if needed
        subscriptionModel.onDataItemsCreated(items);
    }

    /**
     * Called when data items are modified.
     * Required by AddressSpace interface.
     *
     * @param items List of data items that were modified
     */
    @Override
    public void onDataItemsModified(List items) {
        // Handle data item modification if needed
        subscriptionModel.onDataItemsModified(items);
    }

    /**
     * Called when data items are deleted.
     * Required by AddressSpace interface.
     *
     * @param items List of data items that were deleted
     */
    @Override
    public void onDataItemsDeleted(List items) {
        // Handle data item deletion if needed
        subscriptionModel.onDataItemsDeleted(items);
    }
}
