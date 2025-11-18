package com.onvif.driver.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.onvif.driver.gateway.onvif.DeviceInformation;
import com.onvif.driver.gateway.onvif.MediaProfile;
import com.onvif.driver.gateway.onvif.ONVIFClient;
import com.onvif.driver.gateway.onvif.ONVIFService;
import com.onvif.driver.gateway.onvif.PTZStatus;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaMethodNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds OPC-UA address space from ONVIF data.
 * Creates hierarchical folder and variable nodes representing ONVIF device information.
 *
 * IMPORTANT: ALL nodes (folders and variables) must be added to the NodeManager AND
 * linked via references. The ManagedAddressSpaceWithLifecycle manages their lifecycle,
 * but does NOT automatically discover nodes - they must be explicitly added.
 */
public class AddressSpaceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(AddressSpaceBuilder.class);

    private final DeviceContext deviceContext;
    private final UaNodeContext nodeContext;
    private final UaFolderNode rootNode;
    private final ONVIFClient onvifClient;
    private final Consumer<UaNode> addNodeCallback;

    // Cache of variable nodes for updates
    private final Map<String, UaVariableNode> nodeCache = new HashMap<>();

    public AddressSpaceBuilder(DeviceContext deviceContext, UaNodeContext nodeContext,
                              UaFolderNode rootNode, ONVIFClient onvifClient, Consumer<UaNode> addNodeCallback) {
        this.deviceContext = deviceContext;
        this.nodeContext = nodeContext;
        this.rootNode = rootNode;
        this.onvifClient = onvifClient;
        this.addNodeCallback = addNodeCallback;
    }

    /**
     * Builds Device Information section of address space.
     *
     * @param deviceInfo Device information from ONVIF
     */
    public void buildDeviceInfo(DeviceInformation deviceInfo) {
        logger.info("Building DeviceInfo address space");

        // Create DeviceInfo folder
        UaFolderNode deviceInfoFolder = new UaFolderNode(
            nodeContext,
            deviceContext.nodeId("DeviceInfo"),
            deviceContext.qualifiedName("DeviceInfo"),
            LocalizedText.english("Device Information")
        );

        // Add to NodeManager
        addNodeCallback.accept(deviceInfoFolder);

        // Create bidirectional reference
        rootNode.addOrganizes(deviceInfoFolder);
        deviceInfoFolder.addReference(new Reference(
            deviceInfoFolder.getNodeId(),
            NodeIds.Organizes,
            rootNode.getNodeId().expanded(),
            Reference.Direction.INVERSE
        ));

        // Add device info variables
        addVariableNode(deviceInfoFolder, "Manufacturer", deviceInfo.manufacturer());
        addVariableNode(deviceInfoFolder, "Model", deviceInfo.model());
        addVariableNode(deviceInfoFolder, "FirmwareVersion", deviceInfo.firmwareVersion());
        addVariableNode(deviceInfoFolder, "SerialNumber", deviceInfo.serialNumber());
        addVariableNode(deviceInfoFolder, "HardwareId", deviceInfo.hardwareId());

        logger.info("DeviceInfo address space created with {} variables", 5);
    }

    /**
     * Builds Services section of address space.
     *
     * @param services List of ONVIF services
     */
    public void buildServices(List<ONVIFService> services) {
        logger.info("Building Services address space");

        // Create Services folder
        UaFolderNode servicesFolder = new UaFolderNode(
            nodeContext,
            deviceContext.nodeId("Services"),
            deviceContext.qualifiedName("Services"),
            LocalizedText.english("ONVIF Services")
        );

        // Add to NodeManager
        addNodeCallback.accept(servicesFolder);

        // Create bidirectional reference
        rootNode.addOrganizes(servicesFolder);
        servicesFolder.addReference(new Reference(
            servicesFolder.getNodeId(),
            NodeIds.Organizes,
            rootNode.getNodeId().expanded(),
            Reference.Direction.INVERSE
        ));

        // Add each service
        for (int i = 0; i < services.size(); i++) {
            ONVIFService service = services.get(i);
            String serviceName = service.getServiceName();

            // Create folder for this service
            UaFolderNode serviceFolder = new UaFolderNode(
                nodeContext,
                deviceContext.nodeId("Services/" + serviceName),
                deviceContext.qualifiedName(serviceName),
                LocalizedText.english(serviceName + " Service")
            );

            // Add to NodeManager
            addNodeCallback.accept(serviceFolder);

            // Create bidirectional reference
            servicesFolder.addComponent(serviceFolder);
            serviceFolder.addReference(new Reference(
                serviceFolder.getNodeId(),
                NodeIds.HasComponent,
                servicesFolder.getNodeId().expanded(),
                Reference.Direction.INVERSE
            ));

            // Add service details
            addVariableNode(serviceFolder, "Namespace", service.getNamespace());
            addVariableNode(serviceFolder, "XAddr", service.getXAddr());
            addVariableNode(serviceFolder, "Version", service.getVersion());
        }

        logger.info("Services address space created with {} services", services.size());
    }

    /**
     * Builds Connection Status section with detailed connection information.
     *
     * @param status Connection status
     * @param ipAddress Camera IP address
     * @param port Camera port
     * @param useHttps Whether HTTPS is used
     * @param deviceInfo Device information
     */
    public void buildConnectionStatus(String status, String ipAddress, int port, boolean useHttps, DeviceInformation deviceInfo) {
        logger.info("Building ConnectionStatus address space");

        // Create Status folder
        UaFolderNode statusFolder = new UaFolderNode(
            nodeContext,
            deviceContext.nodeId("Status"),
            deviceContext.qualifiedName("Status"),
            LocalizedText.english("Connection Status")
        );

        // Add to NodeManager
        addNodeCallback.accept(statusFolder);

        // Create bidirectional reference
        rootNode.addOrganizes(statusFolder);
        statusFolder.addReference(new Reference(
            statusFolder.getNodeId(),
            NodeIds.Organizes,
            rootNode.getNodeId().expanded(),
            Reference.Direction.INVERSE
        ));

        // Add connection status variables
        addVariableNode(statusFolder, "ConnectionStatus", status);
        addVariableNode(statusFolder, "LastUpdate", System.currentTimeMillis());

        // Add connection details
        addVariableNode(statusFolder, "IPAddress", ipAddress);
        addVariableNode(statusFolder, "Port", port);
        addVariableNode(statusFolder, "Protocol", useHttps ? "HTTPS" : "HTTP");
        addVariableNode(statusFolder, "Endpoint", String.format("%s://%s:%d",
            useHttps ? "https" : "http", ipAddress, port));

        // Add device summary
        if (deviceInfo != null) {
            addVariableNode(statusFolder, "DeviceManufacturer", deviceInfo.manufacturer());
            addVariableNode(statusFolder, "DeviceModel", deviceInfo.model());
            addVariableNode(statusFolder, "FirmwareVersion", deviceInfo.firmwareVersion());
        }

        logger.info("ConnectionStatus address space created with detailed connection info");
    }

    /**
     * Helper method to add a variable node to a folder.
     *
     * @param parent Parent folder node
     * @param name Variable name
     * @param value Variable value
     */
    private void addVariableNode(UaFolderNode parent, String name, Object value) {
        try {
            // Determine data type
            org.eclipse.milo.opcua.stack.core.types.builtin.NodeId dataType;
            if (value instanceof String) {
                dataType = Identifiers.String;
            } else if (value instanceof Integer) {
                dataType = Identifiers.Int32;
            } else if (value instanceof Long) {
                dataType = Identifiers.Int64;
            } else if (value instanceof Double) {
                dataType = Identifiers.Double;
            } else if (value instanceof Boolean) {
                dataType = Identifiers.Boolean;
            } else {
                dataType = Identifiers.String;
                value = String.valueOf(value);
            }

            UaVariableNode variableNode = new UaVariableNode.UaVariableNodeBuilder(nodeContext)
                .setNodeId(deviceContext.nodeId(parent.getBrowseName().getName() + "/" + name))
                .setBrowseName(deviceContext.qualifiedName(name))
                .setDisplayName(LocalizedText.english(name))
                .setDataType(dataType)
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .setAccessLevel(AccessLevel.READ_ONLY)
                .setUserAccessLevel(AccessLevel.READ_ONLY)
                .build();

            variableNode.setValue(new DataValue(new Variant(value)));

            // Add to NodeManager
            addNodeCallback.accept(variableNode);

            // Create bidirectional reference
            parent.addComponent(variableNode);
            variableNode.addReference(new Reference(
                variableNode.getNodeId(),
                NodeIds.HasComponent,
                parent.getNodeId().expanded(),
                Reference.Direction.INVERSE
            ));

            // Cache node for updates
            String nodePath = parent.getBrowseName().getName() + "/" + name;
            nodeCache.put(nodePath, variableNode);

            logger.debug("Added variable: {} = {}", name, value);

        } catch (Exception e) {
            logger.error("Failed to add variable node: " + name, e);
        }
    }

    /**
     * Builds Media Profiles section.
     */
    public void buildMediaProfiles(List<MediaProfile> profiles) {
        logger.info("Building MediaProfiles address space");

        UaFolderNode mediaFolder = new UaFolderNode(
            nodeContext,
            deviceContext.nodeId("MediaProfiles"),
            deviceContext.qualifiedName("MediaProfiles"),
            LocalizedText.english("Media Profiles")
        );

        // Add to NodeManager
        addNodeCallback.accept(mediaFolder);

        // Create bidirectional reference
        rootNode.addOrganizes(mediaFolder);
        mediaFolder.addReference(new Reference(
            mediaFolder.getNodeId(),
            NodeIds.Organizes,
            rootNode.getNodeId().expanded(),
            Reference.Direction.INVERSE
        ));

        for (MediaProfile profile : profiles) {
            String profileName = profile.getName() != null ? profile.getName() : profile.getToken();

            UaFolderNode profileFolder = new UaFolderNode(
                nodeContext,
                deviceContext.nodeId("MediaProfiles/" + profileName),
                deviceContext.qualifiedName(profileName),
                LocalizedText.english(profileName)
            );

            // Add to NodeManager
            addNodeCallback.accept(profileFolder);

            // Create bidirectional reference
            mediaFolder.addComponent(profileFolder);
            profileFolder.addReference(new Reference(
                profileFolder.getNodeId(),
                NodeIds.HasComponent,
                mediaFolder.getNodeId().expanded(),
                Reference.Direction.INVERSE
            ));

            // Add profile details
            addVariableNode(profileFolder, "Token", profile.getToken());
            addVariableNode(profileFolder, "Encoding", profile.getEncoding());
            addVariableNode(profileFolder, "Width", profile.getWidth());
            addVariableNode(profileFolder, "Height", profile.getHeight());
            addVariableNode(profileFolder, "FrameRate", profile.getFrameRate());
            addVariableNode(profileFolder, "Bitrate", profile.getBitrate());

            // Add snapshot and stream URIs from camera
            try {
                String snapshotUri = onvifClient.getSnapshotUri(profile.getToken());
                if (snapshotUri != null) {
                    addVariableNode(profileFolder, "SnapshotUri", snapshotUri);
                }

                String streamUri = onvifClient.getStreamUri(profile.getToken());
                if (streamUri != null) {
                    addVariableNode(profileFolder, "StreamUri", streamUri);
                }
            } catch (Exception e) {
                logger.warn("Failed to get URIs for profile {}: {}", profileName, e.getMessage());
            }

            // Add Ignition route URLs for easy access in Perspective
            String deviceName = deviceContext.getName();
            String profileToken = profile.getToken();

            // Snapshot route URL (GET /main/data/onvif-driver/snapshot)
            String snapshotUrl = String.format(
                "/main/data/onvif-driver/snapshot?device=%s&profile=%s",
                urlEncode(deviceName), urlEncode(profileToken)
            );
            addVariableNode(profileFolder, "IgnitionSnapshotUrl", snapshotUrl);

            // MJPEG stream route URL (GET /main/data/onvif-driver/stream)
            String streamUrl = String.format(
                "/main/data/onvif-driver/stream?device=%s&profile=%s&fps=10",
                urlEncode(deviceName), urlEncode(profileToken)
            );
            addVariableNode(profileFolder, "IgnitionStreamUrl", streamUrl);

            logger.debug("Added Ignition route URLs for profile {}", profileName);
        }

        logger.info("MediaProfiles address space created with {} profiles", profiles.size());
    }

    /**
     * Builds PTZ section with control nodes.
     */
    public void buildPTZ(PTZStatus initialStatus, String profileToken) {
        logger.info("Building PTZ address space");

        UaFolderNode ptzFolder = new UaFolderNode(
            nodeContext,
            deviceContext.nodeId("PTZ"),
            deviceContext.qualifiedName("PTZ"),
            LocalizedText.english("PTZ Control")
        );

        // Add to NodeManager
        addNodeCallback.accept(ptzFolder);

        // Create bidirectional reference
        rootNode.addOrganizes(ptzFolder);
        ptzFolder.addReference(new Reference(
            ptzFolder.getNodeId(),
            NodeIds.Organizes,
            rootNode.getNodeId().expanded(),
            Reference.Direction.INVERSE
        ));

        // Add status nodes (read-only)
        addVariableNode(ptzFolder, "Pan", initialStatus.getPan());
        addVariableNode(ptzFolder, "Tilt", initialStatus.getTilt());
        addVariableNode(ptzFolder, "Zoom", initialStatus.getZoom());
        addVariableNode(ptzFolder, "MoveStatus", initialStatus.getMoveStatus());
        addVariableNode(ptzFolder, "LastUpdate", initialStatus.getTimestamp());

        // Add control nodes (writable)
        addWritableNode(ptzFolder, "SetPan", 0.0, (value) -> {
            try {
                double pan = ((Number) value).doubleValue();
                PTZStatus current = onvifClient.getPTZStatus(profileToken);
                onvifClient.absoluteMove(profileToken, pan, current.getTilt(), current.getZoom());
            } catch (Exception e) {
                logger.error("Failed to set pan", e);
            }
        });

        addWritableNode(ptzFolder, "SetTilt", 0.0, (value) -> {
            try {
                double tilt = ((Number) value).doubleValue();
                PTZStatus current = onvifClient.getPTZStatus(profileToken);
                onvifClient.absoluteMove(profileToken, current.getPan(), tilt, current.getZoom());
            } catch (Exception e) {
                logger.error("Failed to set tilt", e);
            }
        });

        addWritableNode(ptzFolder, "SetZoom", 0.0, (value) -> {
            try {
                double zoom = ((Number) value).doubleValue();
                PTZStatus current = onvifClient.getPTZStatus(profileToken);
                onvifClient.absoluteMove(profileToken, current.getPan(), current.getTilt(), zoom);
            } catch (Exception e) {
                logger.error("Failed to set zoom", e);
            }
        });

        logger.info("PTZ address space created");
    }

    /**
     * Updates a variable node value.
     *
     * @param nodePath Path to the variable node (e.g., "PTZ/Pan")
     * @param value New value
     */
    public void updateVariableValue(String nodePath, Object value) {
        try {
            UaVariableNode node = nodeCache.get(nodePath);
            if (node != null) {
                node.setValue(new DataValue(new Variant(value)));
                logger.trace("Updated {} to {}", nodePath, value);
            } else {
                logger.debug("Node not found in cache: {}", nodePath);
            }
        } catch (Exception e) {
            logger.error("Failed to update variable: " + nodePath, e);
        }
    }

    /**
     * Updates multiple variable values.
     */
    public void updateVariableValues(Map<String, Object> updates) {
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            updateVariableValue(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Adds a writable variable node.
     * Note: For full PTZ control integration, use OPC-UA Methods or external scripting.
     */
    private void addWritableNode(UaFolderNode parent, String name, Object initialValue,
                                 java.util.function.Consumer<Object> writeHandler) {
        try {
            org.eclipse.milo.opcua.stack.core.types.builtin.NodeId dataType = Identifiers.Double;

            UaVariableNode variableNode = new UaVariableNode.UaVariableNodeBuilder(nodeContext)
                .setNodeId(deviceContext.nodeId(parent.getBrowseName().getName() + "/" + name))
                .setBrowseName(deviceContext.qualifiedName(name))
                .setDisplayName(LocalizedText.english(name))
                .setDataType(dataType)
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .setAccessLevel(AccessLevel.READ_WRITE)
                .setUserAccessLevel(AccessLevel.READ_WRITE)
                .build();

            variableNode.setValue(new DataValue(new Variant(initialValue)));

            // Add to NodeManager
            addNodeCallback.accept(variableNode);

            // Create bidirectional reference
            parent.addComponent(variableNode);
            variableNode.addReference(new Reference(
                variableNode.getNodeId(),
                NodeIds.HasComponent,
                parent.getNodeId().expanded(),
                Reference.Direction.INVERSE
            ));

            String nodePath = parent.getBrowseName().getName() + "/" + name;
            nodeCache.put(nodePath, variableNode);

            logger.debug("Added writable variable: {} (write handler requires OPC-UA Methods for full integration)", name);

        } catch (Exception e) {
            logger.error("Failed to add writable node: " + name, e);
        }
    }

    /**
     * URL encodes a string for use in URL parameters.
     *
     * @param value String to encode
     * @return URL-encoded string
     */
    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            logger.warn("Failed to URL encode value: {}", value);
            return value;
        }
    }
}
