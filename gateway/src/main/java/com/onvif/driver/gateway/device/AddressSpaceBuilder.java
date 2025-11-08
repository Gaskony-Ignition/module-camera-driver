package com.onvif.driver.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.onvif.driver.gateway.onvif.DeviceInformation;
import com.onvif.driver.gateway.onvif.ONVIFService;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Builds OPC-UA address space from ONVIF data.
 * Creates hierarchical folder and variable nodes representing ONVIF device information.
 */
public class AddressSpaceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(AddressSpaceBuilder.class);

    private final DeviceContext deviceContext;
    private final UaNodeContext nodeContext;
    private final UaFolderNode rootNode;

    public AddressSpaceBuilder(DeviceContext deviceContext, UaNodeContext nodeContext, UaFolderNode rootNode) {
        this.deviceContext = deviceContext;
        this.nodeContext = nodeContext;
        this.rootNode = rootNode;
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
        rootNode.addComponent(deviceInfoFolder);

        // Add device info variables
        addVariableNode(deviceInfoFolder, "Manufacturer", deviceInfo.getManufacturer());
        addVariableNode(deviceInfoFolder, "Model", deviceInfo.getModel());
        addVariableNode(deviceInfoFolder, "FirmwareVersion", deviceInfo.getFirmwareVersion());
        addVariableNode(deviceInfoFolder, "SerialNumber", deviceInfo.getSerialNumber());
        addVariableNode(deviceInfoFolder, "HardwareId", deviceInfo.getHardwareId());

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
        rootNode.addComponent(servicesFolder);

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
            servicesFolder.addComponent(serviceFolder);

            // Add service details
            addVariableNode(serviceFolder, "Namespace", service.getNamespace());
            addVariableNode(serviceFolder, "XAddr", service.getXAddr());
            addVariableNode(serviceFolder, "Version", service.getVersion());
        }

        logger.info("Services address space created with {} services", services.size());
    }

    /**
     * Builds Connection Status section.
     *
     * @param status Connection status
     */
    public void buildConnectionStatus(String status) {
        logger.info("Building ConnectionStatus address space");

        // Create Status folder
        UaFolderNode statusFolder = new UaFolderNode(
            nodeContext,
            deviceContext.nodeId("Status"),
            deviceContext.qualifiedName("Status"),
            LocalizedText.english("Connection Status")
        );
        rootNode.addComponent(statusFolder);

        // Add status variable
        addVariableNode(statusFolder, "ConnectionStatus", status);
        addVariableNode(statusFolder, "LastUpdate", System.currentTimeMillis());

        logger.info("ConnectionStatus address space created");
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

            parent.addComponent(variableNode);

            logger.debug("Added variable: {} = {}", name, value);

        } catch (Exception e) {
            logger.error("Failed to add variable node: " + name, e);
        }
    }

    /**
     * Updates a variable node value.
     *
     * @param nodePath Path to the variable node (e.g., "DeviceInfo/Manufacturer")
     * @param value New value
     */
    public void updateVariableValue(String nodePath, Object value) {
        try {
            // This would be implemented to update existing node values
            // For now, just log
            logger.debug("Would update {} to {}", nodePath, value);
        } catch (Exception e) {
            logger.error("Failed to update variable: " + nodePath, e);
        }
    }
}
