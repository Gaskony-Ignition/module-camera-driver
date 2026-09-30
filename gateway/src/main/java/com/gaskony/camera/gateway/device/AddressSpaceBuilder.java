package com.gaskony.camera.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.gaskony.camera.gateway.onvif.DeviceInformation;
import com.gaskony.camera.gateway.onvif.MediaProfile;
import com.gaskony.camera.gateway.onvif.ONVIFClient;
import com.gaskony.camera.gateway.onvif.ONVIFService;
import com.gaskony.camera.gateway.onvif.PTZStatus;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaMethodNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.filters.AttributeFilters;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Builds OPC-UA address space from ONVIF camera data.
 * Creates hierarchical folder and variable nodes representing camera device information.
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
     * Selects the OPC-UA data type NodeId for a given Java value.
     * Defaults to String for unknown types.
     *
     * Package-private to allow direct testing without the OPC-UA server stack.
     */
    static org.eclipse.milo.opcua.stack.core.types.builtin.NodeId selectDataType(Object value) {
        if (value instanceof String) return Identifiers.String;
        if (value instanceof Integer) return Identifiers.Int32;
        if (value instanceof Long) return Identifiers.Int64;
        if (value instanceof Double) return Identifiers.Double;
        if (value instanceof Boolean) return Identifiers.Boolean;
        return Identifiers.String;
    }

    /**
     * Builds the Ignition snapshot gateway URL for an ONVIF profile.
     *
     * Package-private to allow direct testing without the OPC-UA server stack.
     */
    static String buildSnapshotUrl(String deviceName, String profileToken) {
        return String.format(
            "/main/data/camera-driver/snapshot?device=%s&profile=%s",
            urlEncode(deviceName), urlEncode(profileToken)
        );
    }

    /**
     * Builds the Ignition MJPEG stream gateway URL for an ONVIF profile.
     *
     * Package-private to allow direct testing without the OPC-UA server stack.
     */
    static String buildStreamUrl(String deviceName, String profileToken, int fps) {
        return String.format(
            "/main/data/camera-driver/stream?device=%s&profile=%s&fps=%d",
            urlEncode(deviceName), urlEncode(profileToken), fps
        );
    }

    /**
     * URL encodes a string for use in URL parameters.
     *
     * Package-private to allow direct testing without the OPC-UA server stack.
     *
     * @param value String to encode
     * @return URL-encoded string
     */
    static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            logger.warn("Failed to URL encode value: {}", value);
            return value;
        }
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
            org.eclipse.milo.opcua.stack.core.types.builtin.NodeId dataType = selectDataType(value);
            if (!(value instanceof String) && !(value instanceof Integer)
                    && !(value instanceof Long) && !(value instanceof Double)
                    && !(value instanceof Boolean)) {
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
            logger.error("Failed to add variable node: {}", name, e);
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

            // Snapshot route URL (GET /main/data/camera-driver/snapshot)
            addVariableNode(profileFolder, "IgnitionSnapshotUrl",
                buildSnapshotUrl(deviceName, profileToken));

            // MJPEG stream route URL (GET /main/data/camera-driver/stream)
            addVariableNode(profileFolder, "IgnitionStreamUrl",
                buildStreamUrl(deviceName, profileToken, 10));

            logger.debug("Added Ignition route URLs for profile {}", profileName);
        }

        logger.info("MediaProfiles address space created with {} profiles", profiles.size());
    }

    /**
     * Identifies which PTZ axis a writable Set* node controls.
     *
     * Used by {@link #addPtzWritableNode} to translate a single-axis OPC-UA
     * write into a SOAP {@code AbsoluteMove(pan, tilt, zoom)} request. The
     * other axes are filled in from the most recent values held by
     * {@link #ptzAxisState}.
     */
    enum PtzAxis { PAN, TILT, ZOOM }

    /**
     * Holds the most recently written/read values per PTZ axis. The
     * {@code AttributeFilter} installed by {@link #addPtzWritableNode}
     * combines the just-written axis with the cached values for the others
     * so SOAP {@code AbsoluteMove(pan, tilt, zoom)} sees a coherent triple.
     *
     * Volatile reads are not enough for triple-coordination, so we use a
     * single {@link AtomicReference} containing an immutable snapshot.
     */
    private final AtomicReference<double[]> ptzAxisState =
        new AtomicReference<>(new double[]{0.0, 0.0, 0.0});

    /**
     * Builds PTZ section with control nodes.
     *
     * <p>C10 (per /modules/.review/FINAL_REVIEW.md §4 — Sprint 1 chose option (b),
     * Sprint 2 upgrades to option (a) per C10-followup): the PTZ writable
     * nodes ({@code SetPan} / {@code SetTilt} / {@code SetZoom}) are now
     * declared {@code READ_WRITE} and have a Milo {@code AttributeFilter}
     * installed via {@link AttributeFilters#setValue(java.util.function.BiConsumer)}.
     * When a client writes a value, the filter intercepts the write, updates
     * the per-axis cache, and issues a SOAP {@code AbsoluteMove} via
     * {@link ONVIFClient#absoluteMove(String, double, double, double)}.</p>
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

        // Seed the per-axis cache from initial status so the first write to
        // any single axis produces a valid (pan, tilt, zoom) triple.
        ptzAxisState.set(new double[]{
            initialStatus.getPan(), initialStatus.getTilt(), initialStatus.getZoom()
        });

        // Add status nodes (read-only)
        addVariableNode(ptzFolder, "Pan", initialStatus.getPan());
        addVariableNode(ptzFolder, "Tilt", initialStatus.getTilt());
        addVariableNode(ptzFolder, "Zoom", initialStatus.getZoom());
        addVariableNode(ptzFolder, "MoveStatus", initialStatus.getMoveStatus());
        addVariableNode(ptzFolder, "LastUpdate", initialStatus.getTimestamp());

        // C10-followup: writable PTZ nodes with real SOAP wiring.
        addPtzWritableNode(ptzFolder, "SetPan",  PtzAxis.PAN,  profileToken);
        addPtzWritableNode(ptzFolder, "SetTilt", PtzAxis.TILT, profileToken);
        addPtzWritableNode(ptzFolder, "SetZoom", PtzAxis.ZOOM, profileToken);

        logger.info("PTZ address space created with READ_WRITE Set* nodes wired to "
            + "ONVIF AbsoluteMove via Milo AttributeFilter — see C10-followup");
    }

    /**
     * Adds a READ_WRITE PTZ control node ({@code SetPan} / {@code SetTilt} /
     * {@code SetZoom}). Installs a Milo {@code AttributeFilter} that translates
     * a write into a SOAP {@code AbsoluteMove} call on {@link #onvifClient}.
     *
     * <p>This is the C10-followup wiring (per /modules/.review/FINAL_REVIEW.md
     * §4 and the C10 fix report). Pre-Sprint-1, these nodes were declared
     * {@code READ_WRITE} but no filter was installed — writes silently
     * no-op'd. Sprint 1 dropped them to {@code READ_ONLY} (option b) so
     * clients got {@code Bad_NotWritable} instead of a silent success.
     * Sprint 2 (this method) restores {@code READ_WRITE} with a real
     * filter.</p>
     *
     * <p>The filter:
     * <ol>
     *   <li>Extracts the new axis value from the written {@code DataValue}.</li>
     *   <li>Clamps to ONVIF's normalised range {@code [-1.0, 1.0]}.</li>
     *   <li>Updates {@link #ptzAxisState} with the new triple.</li>
     *   <li>Calls {@link ONVIFClient#absoluteMove(String, double, double, double)}.</li>
     * </ol></p>
     */
    private void addPtzWritableNode(UaFolderNode parent, String name, PtzAxis axis, String profileToken) {
        try {
            UaVariableNode variableNode = new UaVariableNode.UaVariableNodeBuilder(nodeContext)
                .setNodeId(deviceContext.nodeId(parent.getBrowseName().getName() + "/" + name))
                .setBrowseName(deviceContext.qualifiedName(name))
                .setDisplayName(LocalizedText.english(name))
                .setDataType(Identifiers.Double)
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .setAccessLevel(AccessLevel.READ_WRITE)
                .setUserAccessLevel(AccessLevel.READ_WRITE)
                .build();

            // Seed initial value from the per-axis cache.
            double initial = ptzAxisState.get()[axis.ordinal()];
            variableNode.setValue(new DataValue(new Variant(initial)));

            // Install the AttributeFilter that translates writes into SOAP
            // AbsoluteMove. The lambda runs on the OPC-UA write thread.
            //
            // The actual logic lives in handlePtzWrite(...) so it can be unit
            // tested without Milo on the classpath; the lambda is a thin
            // adapter that pulls the raw value out of the DataValue.
            variableNode.getFilterChain().addLast(AttributeFilters.setValue((ctx, dataValue) -> {
                Object raw = dataValue == null || dataValue.getValue() == null
                    ? null : dataValue.getValue().getValue();
                handlePtzWrite(name, axis, profileToken, raw, onvifClient, ptzAxisState);
            }));

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

            logger.debug("Added READ_WRITE PTZ control node: {} (axis={})", name, axis);
        } catch (Exception e) {
            logger.error("Failed to add PTZ writable node: {}", name, e);
        }
    }

    /**
     * Clamps a PTZ value to ONVIF's normalised range {@code [-1.0, 1.0]}.
     * Package-private for direct testing.
     */
    static double clampPtz(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(-1.0, Math.min(1.0, v));
    }

    /**
     * Implementation of the per-axis PTZ write handler — extracted from the
     * Milo {@code AttributeFilter} lambda so the C10-followup logic is unit
     * testable without Milo on the test classpath.
     *
     * <p>Validates the incoming value, clamps it to ONVIF range, atomically
     * updates the per-axis cache, then dispatches a SOAP {@code AbsoluteMove}
     * with the latest (pan, tilt, zoom) triple.</p>
     *
     * @param nodeName     the name of the OPC-UA node that received the write
     *                     (for log context only)
     * @param axis         which axis this write affects
     * @param profileToken ONVIF media-profile token (must not be null/empty)
     * @param rawValue     raw value extracted from the DataValue's Variant —
     *                     must be a {@link Number}; otherwise the write is
     *                     ignored
     * @param onvifClient  the ONVIF client (must not be null — camera must
     *                     be connected)
     * @param axisState    per-axis cache shared with other PTZ writable nodes
     *                     so AbsoluteMove always sees a coherent triple
     */
    static void handlePtzWrite(String nodeName,
                               PtzAxis axis,
                               String profileToken,
                               Object rawValue,
                               ONVIFClient onvifClient,
                               AtomicReference<double[]> axisState) {
        if (!(rawValue instanceof Number)) {
            logger.warn("PTZ write to {} ignored — non-numeric value: {}", nodeName, rawValue);
            return;
        }
        double v = clampPtz(((Number) rawValue).doubleValue());

        double[] updated = axisState.updateAndGet(prev -> {
            double[] next = prev.clone();
            next[axis.ordinal()] = v;
            return next;
        });

        if (onvifClient == null) {
            logger.warn("PTZ write to {} ignored — no ONVIF client (camera not connected?)", nodeName);
            return;
        }
        if (profileToken == null || profileToken.isEmpty()) {
            logger.warn("PTZ write to {} ignored — no media profile token", nodeName);
            return;
        }

        try {
            onvifClient.absoluteMove(profileToken, updated[0], updated[1], updated[2]);
            logger.debug("PTZ AbsoluteMove sent: pan={}, tilt={}, zoom={} (triggered by {} write)",
                updated[0], updated[1], updated[2], nodeName);
        } catch (Exception e) {
            logger.error("PTZ AbsoluteMove failed for {}: {}", nodeName, e.getMessage());
        }
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

}
