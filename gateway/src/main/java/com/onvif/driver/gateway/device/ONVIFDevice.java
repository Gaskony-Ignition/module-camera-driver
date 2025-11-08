package com.onvif.driver.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.onvif.driver.gateway.onvif.DeviceInformation;
import com.onvif.driver.gateway.onvif.ONVIFClient;
import com.onvif.driver.gateway.onvif.ONVIFService;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
        try {
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
                throw new Exception("Failed to connect to ONVIF device - connection test failed");
            }

            deviceStatus = "Discovering Services";

            // Get device information
            DeviceInformation deviceInfo = onvifClient.getDeviceInformation();
            if (deviceInfo == null) {
                throw new Exception("Failed to retrieve device information");
            }

            logger.info("Connected to ONVIF device: {}", deviceInfo);

            // Discover services if configured
            List<ONVIFService> services = null;
            if (config.onvif().autoDiscover()) {
                logger.info("Discovering ONVIF services...");
                services = onvifClient.getServices();
                logger.info("Discovered {} ONVIF services", services != null ? services.size() : 0);
            }

            // Create OPC-UA address space
            deviceStatus = "Building Address Space";
            createRootNode();
            buildAddressSpace(deviceInfo, services);

            deviceStatus = "Running";
            logger.info("ONVIF device started successfully: {}", context.getName());

        } catch (Exception e) {
            deviceStatus = "Error: " + e.getMessage();
            logger.error("Error starting device: {}", context.getName(), e);
        }
    }

    /**
     * Called when device shuts down.
     */
    private void onShutdown() {
        logger.info("Shutting down ONVIF device: {}", context.getName());

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
     */
    private void buildAddressSpace(DeviceInformation deviceInfo, List<ONVIFService> services) {
        logger.info("Building OPC-UA address space from ONVIF data");

        AddressSpaceBuilder builder = new AddressSpaceBuilder(context, getNodeContext(), rootNode);

        // Build device information section
        if (deviceInfo != null) {
            builder.buildDeviceInfo(deviceInfo);
        }

        // Build services section if discovered
        if (services != null && !services.isEmpty()) {
            builder.buildServices(services);
        }

        // Build connection status section
        builder.buildConnectionStatus("Connected");

        logger.info("Address space built successfully");
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
