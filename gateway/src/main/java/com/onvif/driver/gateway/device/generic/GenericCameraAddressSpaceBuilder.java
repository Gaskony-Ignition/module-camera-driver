package com.onvif.driver.gateway.device.generic;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.onvif.driver.common.CameraDriverPaths;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
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
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds OPC-UA address space for Generic Camera devices.
 * Creates tags for stream URLs, connection status, and Ignition gateway endpoints.
 */
public class GenericCameraAddressSpaceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(GenericCameraAddressSpaceBuilder.class);

    private final DeviceContext deviceContext;
    private final UaNodeContext nodeContext;
    private final UaFolderNode rootNode;
    private final Consumer<UaNode> addNodeCallback;
    private final Map<String, UaVariableNode> nodeCache = new HashMap<>();

    public GenericCameraAddressSpaceBuilder(DeviceContext deviceContext, UaNodeContext nodeContext,
                                           UaFolderNode rootNode, Consumer<UaNode> addNodeCallback) {
        this.deviceContext = deviceContext;
        this.nodeContext = nodeContext;
        this.rootNode = rootNode;
        this.addNodeCallback = addNodeCallback;
    }

    /**
     * Builds the StreamInfo section with camera URLs and stream metadata.
     */
    public void buildStreamInfo(GenericCameraConfig config) {
        logger.info("Building StreamInfo address space");

        UaFolderNode streamInfoFolder = createFolder("StreamInfo", "Stream Information");
        linkToParent(rootNode, streamInfoFolder);

        // Raw URLs from config
        String rtspUrl = config.cameraConnection().rtspUrl();
        String snapshotUrl = config.cameraConnection().snapshotUrl();
        String mjpegUrl = config.cameraConnection().mjpegUrl();

        addVariableNode(streamInfoFolder, "RtspUrl", rtspUrl != null ? rtspUrl : "");
        addVariableNode(streamInfoFolder, "SnapshotUrl", snapshotUrl != null ? snapshotUrl : "");
        addVariableNode(streamInfoFolder, "MjpegUrl", mjpegUrl != null ? mjpegUrl : "");
        addVariableNode(streamInfoFolder, "FrameRate", config.streamSettings().defaultFps());

        // Ignition gateway endpoints for Perspective consumption
        String deviceName = deviceContext.getName();
        addVariableNode(streamInfoFolder, "IgnitionSnapshotUrl",
            buildSnapshotEndpoint(deviceName));
        addVariableNode(streamInfoFolder, "IgnitionStreamUrl",
            buildStreamEndpoint(deviceName, config.streamSettings().defaultFps()));

        logger.info("StreamInfo address space created");
    }

    /**
     * Builds the Status section with connection and go2rtc status.
     */
    public void buildStatus(String connectionStatus, boolean go2RtcAvailable) {
        logger.info("Building Status address space");

        UaFolderNode statusFolder = createFolder("Status", "Connection Status");
        linkToParent(rootNode, statusFolder);

        addVariableNode(statusFolder, "ConnectionStatus", connectionStatus);
        addVariableNode(statusFolder, "Go2RtcAvailable", go2RtcAvailable);
        addVariableNode(statusFolder, "LastUpdate", System.currentTimeMillis());
        addVariableNode(statusFolder, "DeviceType", "Generic Camera");

        logger.info("Status address space created");
    }

    /**
     * Updates a variable node value.
     */
    public void updateVariableValue(String nodePath, Object value) {
        try {
            UaVariableNode node = nodeCache.get(nodePath);
            if (node != null) {
                node.setValue(new DataValue(new Variant(value)));
                logger.trace("Updated {} to {}", nodePath, value);
            }
        } catch (Exception e) {
            logger.error("Failed to update variable: {}", nodePath, e);
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

    private UaFolderNode createFolder(String name, String displayName) {
        UaFolderNode folder = new UaFolderNode(
            nodeContext,
            deviceContext.nodeId(name),
            deviceContext.qualifiedName(name),
            LocalizedText.english(displayName)
        );
        addNodeCallback.accept(folder);
        return folder;
    }

    private void linkToParent(UaFolderNode parent, UaFolderNode child) {
        parent.addOrganizes(child);
        child.addReference(new Reference(
            child.getNodeId(),
            NodeIds.Organizes,
            parent.getNodeId().expanded(),
            Reference.Direction.INVERSE
        ));
    }

    /**
     * Selects the OPC-UA data type NodeId for a given Java value.
     * Defaults to String for unknown types.
     *
     * Package-private to allow direct testing without the OPC-UA server stack.
     */
    static org.eclipse.milo.opcua.stack.core.types.builtin.NodeId selectDataType(Object value) {
        if (value instanceof Boolean) return Identifiers.Boolean;
        if (value instanceof Integer) return Identifiers.Int32;
        if (value instanceof Long) return Identifiers.Int64;
        if (value instanceof Double) return Identifiers.Double;
        return Identifiers.String;
    }

    /**
     * Builds the Ignition snapshot gateway endpoint URL for a generic camera device.
     *
     * Package-private to allow direct testing without the OPC-UA server stack.
     */
    static String buildSnapshotEndpoint(String deviceName) {
        return CameraDriverPaths.DATA_BASE + CameraDriverPaths.ROUTE_SNAPSHOT
            + "?device=" + urlEncode(deviceName);
    }

    /**
     * Builds the Ignition stream gateway endpoint URL for a generic camera device.
     *
     * Package-private to allow direct testing without the OPC-UA server stack.
     */
    static String buildStreamEndpoint(String deviceName, int fps) {
        return CameraDriverPaths.DATA_BASE + CameraDriverPaths.ROUTE_STREAM
            + "?device=" + urlEncode(deviceName) + "&fps=" + fps;
    }

    /**
     * URL encodes a string for use in URL parameters.
     *
     * Package-private to allow direct testing without the OPC-UA server stack.
     */
    static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private void addVariableNode(UaFolderNode parent, String name, Object value) {
        try {
            org.eclipse.milo.opcua.stack.core.types.builtin.NodeId dataType = selectDataType(value);
            if (!(value instanceof Boolean) && !(value instanceof Integer)
                    && !(value instanceof Long) && !(value instanceof Double)) {
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

            addNodeCallback.accept(variableNode);

            parent.addComponent(variableNode);
            variableNode.addReference(new Reference(
                variableNode.getNodeId(),
                NodeIds.HasComponent,
                parent.getNodeId().expanded(),
                Reference.Direction.INVERSE
            ));

            String nodePath = parent.getBrowseName().getName() + "/" + name;
            nodeCache.put(nodePath, variableNode);

        } catch (Exception e) {
            logger.error("Failed to add variable node: {}", name, e);
        }
    }

}
