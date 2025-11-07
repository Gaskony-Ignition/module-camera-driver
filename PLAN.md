# ONVIF Driver Implementation Plan

This document outlines the step-by-step plan for implementing the ONVIF driver functionality.

## Prerequisites

**🚨 BEFORE YOU START**: Read `LEARNINGS.md` to understand critical lessons from the PLC Simulator project.

## Phase 1: ONVIF Protocol Foundation

### 1.1 Research ONVIF Protocol
- [ ] Read ONVIF Core Specification (https://www.onvif.org/specs/core/ONVIF-Core-Specification.pdf)
- [ ] Understand SOAP/XML message format
- [ ] Study WS-Discovery protocol
- [ ] Review WS-UsernameToken authentication
- [ ] Identify required ONVIF services for initial implementation

### 1.2 Choose ONVIF Library Approach

**Option A: Use Existing Java Library**
- Pros: Faster development, tested code
- Cons: Large dependency, may not fit all use cases
- Library: `onvif-java` (https://github.com/milg0/onvif-java)

**Option B: Implement SOAP Client Manually**
- Pros: Full control, smaller footprint, better understanding
- Cons: More development time, potential for bugs
- Use: Java's built-in XML libraries + HTTP client

**Recommendation**: Start with Option A for rapid prototyping, consider Option B if issues arise.

### 1.3 Add Dependencies

Update `gateway/build.gradle.kts`:
```kotlin
dependencies {
    // ... existing dependencies

    // For ONVIF communication
    modlImplementation("org.apache.httpcomponents:httpclient:4.5.14")
    modlImplementation("org.apache.httpcomponents:httpcore:4.4.16")

    // For XML parsing
    modlImplementation("javax.xml.bind:jaxb-api:2.3.1")
    modlImplementation("org.glassfish.jaxb:jaxb-runtime:2.3.1")

    // For SOAP (if not using onvif-java)
    // modlImplementation("com.sun.xml.ws:jaxws-rt:2.3.5")
}
```

## Phase 2: Basic ONVIF Connection

### 2.1 Implement ONVIF Client Class

Create `gateway/src/main/java/com/onvif/driver/gateway/onvif/ONVIFClient.java`:

```java
public class ONVIFClient {
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean useHttps;

    public ONVIFClient(String host, int port, String username, String password, boolean useHttps) {
        // Initialize
    }

    public boolean testConnection() {
        // Test connectivity
        // Send GetDeviceInformation request
        // Return true if successful
    }

    public DeviceInformation getDeviceInformation() {
        // Get device info (manufacturer, model, firmware, serial)
    }

    public List<Service> getServices() {
        // WS-Discovery or GetServices
        // Return list of available ONVIF services
    }
}
```

### 2.2 Implement Authentication

Create `gateway/src/main/java/com/onvif/driver/gateway/onvif/ONVIFAuth.java`:

```java
public class ONVIFAuth {
    public static String generateUsernameToken(String username, String password) {
        // Generate WS-UsernameToken
        // Include: Username, Password (digest), Nonce, Created timestamp
        // Return XML string for SOAP header
    }
}
```

### 2.3 Update ONVIFDevice.onStartup()

Modify `ONVIFDevice.java`:

```java
private void onStartup() {
    try {
        logger.info("Starting ONVIF device: {}", context.getName());
        deviceStatus = "Connecting";

        // Create ONVIF client
        ONVIFClient client = new ONVIFClient(
            config.connection().ipAddress(),
            config.connection().port(),
            config.connection().username(),
            config.connection().password(),
            config.connection().useHttps()
        );

        // Test connection
        if (!client.testConnection()) {
            throw new IOException("Failed to connect to ONVIF device");
        }

        deviceStatus = "Discovering Services";

        // Get device information
        DeviceInformation deviceInfo = client.getDeviceInformation();
        logger.info("Connected to: {} {} ({})",
            deviceInfo.getManufacturer(),
            deviceInfo.getModel(),
            deviceInfo.getFirmwareVersion());

        // Discover services
        List<Service> services = client.getServices();
        logger.info("Discovered {} ONVIF services", services.size());

        // Create OPC-UA address space
        createRootNode();
        buildAddressSpace(deviceInfo, services);

        deviceStatus = "Running";
        logger.info("ONVIF device started successfully");

    } catch (Exception e) {
        deviceStatus = "Error: " + e.getMessage();
        logger.error("Error starting device", e);
    }
}
```

## Phase 3: OPC-UA Address Space Creation

### 3.1 Design Address Space Structure

```
[DeviceName]/
├── DeviceInfo/
│   ├── Manufacturer
│   ├── Model
│   ├── FirmwareVersion
│   ├── SerialNumber
│   └── HardwareId
├── NetworkSettings/
│   ├── IPAddress
│   ├── SubnetMask
│   ├── Gateway
│   └── DNS
├── MediaProfiles/
│   ├── Profile_1/
│   │   ├── Name
│   │   ├── VideoSource
│   │   ├── VideoEncoder
│   │   └── Resolution
│   └── Profile_2/
│       └── ...
└── Services/
    ├── DeviceService
    ├── MediaService
    ├── PTZService
    └── ...
```

### 3.2 Implement Address Space Builder

Create `gateway/src/main/java/com/onvif/driver/gateway/device/AddressSpaceBuilder.java`:

```java
public class AddressSpaceBuilder {
    private final DeviceContext context;
    private final NodeManager nodeManager;
    private final UaFolderNode rootNode;

    public void buildDeviceInfo(DeviceInformation info) {
        // Create DeviceInfo folder
        // Add child nodes for manufacturer, model, etc.
    }

    public void buildMediaProfiles(List<MediaProfile> profiles) {
        // Create MediaProfiles folder
        // For each profile, create folder with settings
    }

    // ... other builders
}
```

### 3.3 Update ONVIFDevice

Modify `createRootNode()` and add `buildAddressSpace()`:

```java
private void buildAddressSpace(DeviceInformation deviceInfo, List<Service> services) {
    AddressSpaceBuilder builder = new AddressSpaceBuilder(
        context, getNodeManager(), rootNode
    );

    // Build device info section
    builder.buildDeviceInfo(deviceInfo);

    // Build services section
    builder.buildServices(services);

    // Build media profiles if media service available
    if (hasService(services, "Media")) {
        List<MediaProfile> profiles = client.getMediaProfiles();
        builder.buildMediaProfiles(profiles);
    }

    // Build PTZ if available
    if (hasService(services, "PTZ")) {
        PTZConfiguration ptzConfig = client.getPTZConfiguration();
        builder.buildPTZ(ptzConfig);
    }

    logger.info("Address space built successfully");
}
```

## Phase 4: Polling and Updates

### 4.1 Implement Polling Mechanism

Create `gateway/src/main/java/com/onvif/driver/gateway/device/ONVIFPoller.java`:

```java
public class ONVIFPoller {
    private final ScheduledExecutorService executor;
    private final ONVIFClient client;
    private final int pollInterval;
    private ScheduledFuture<?> pollingTask;

    public void start() {
        pollingTask = executor.scheduleAtFixedRate(
            this::poll,
            0,
            pollInterval,
            TimeUnit.SECONDS
        );
    }

    private void poll() {
        try {
            // Poll device for updates
            // Update OPC-UA node values
        } catch (Exception e) {
            logger.error("Polling error", e);
        }
    }

    public void stop() {
        if (pollingTask != null) {
            pollingTask.cancel(true);
        }
        executor.shutdown();
    }
}
```

### 4.2 Integrate Polling into Device

Update `ONVIFDevice.java`:

```java
private ONVIFPoller poller;

private void onStartup() {
    // ... existing startup code

    // Start polling if configured
    if (config.onvif().pollInterval() > 0) {
        poller = new ONVIFPoller(client, config.onvif().pollInterval());
        poller.setUpdateCallback(this::updateNodeValues);
        poller.start();
        logger.info("Started polling at {} second interval", config.onvif().pollInterval());
    }
}

private void onShutdown() {
    // Stop polling
    if (poller != null) {
        poller.stop();
    }

    // ... rest of shutdown
}

private void updateNodeValues(Map<String, Object> updates) {
    // Update OPC-UA node values from polling data
}
```

## Phase 5: Testing and Validation

### 5.1 Unit Tests

Create test classes:
- `ONVIFClientTest.java` - Test ONVIF communication
- `ONVIFAuthTest.java` - Test authentication
- `AddressSpaceBuilderTest.java` - Test OPC-UA node creation

### 5.2 Integration Testing

1. **Set up test environment**:
   - Real ONVIF camera OR
   - ONVIF simulator/emulator

2. **Test scenarios**:
   - [ ] Connect to device
   - [ ] Authenticate successfully
   - [ ] Handle authentication failure
   - [ ] Discover services
   - [ ] Create OPC-UA nodes
   - [ ] Poll for updates
   - [ ] Handle network disconnect
   - [ ] Reconnect after disconnect
   - [ ] Multiple simultaneous devices

### 5.3 Docker Testing

Test in Ignition Docker container:
```bash
# Build module
./gradlew clean build

# Copy to Docker container
docker cp build/ONVIFDriver-1.0.0.modl ignition-gateway:/usr/local/bin/ignition/user-lib/modules/

# Restart gateway
docker restart ignition-gateway

# Check logs
docker logs -f ignition-gateway
```

## Phase 6: Error Handling and Robustness

### 6.1 Connection Error Handling

```java
private void onStartup() {
    int maxRetries = 3;
    int retryDelay = 5; // seconds

    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try {
            // Connection logic
            break; // Success
        } catch (Exception e) {
            if (attempt == maxRetries) {
                deviceStatus = "Error: Max retries exceeded";
                logger.error("Failed to connect after {} attempts", maxRetries, e);
                return;
            }
            logger.warn("Connection attempt {} failed, retrying in {}s", attempt, retryDelay);
            Thread.sleep(retryDelay * 1000);
        }
    }
}
```

### 6.2 Network Disconnect Handling

```java
private void poll() {
    try {
        // Poll device
        consecutiveErrors = 0; // Reset on success
    } catch (IOException e) {
        consecutiveErrors++;
        if (consecutiveErrors >= maxConsecutiveErrors) {
            deviceStatus = "Disconnected";
            logger.error("Device disconnected after {} errors", consecutiveErrors);
            attemptReconnect();
        }
    }
}

private void attemptReconnect() {
    // Try to reconnect
    // If successful, reset state and resume polling
}
```

## Phase 7: Advanced Features (Optional)

### 7.1 PTZ Control

Add writable OPC-UA nodes for PTZ control:
- Pan/Tilt position
- Zoom level
- Preset positions
- Home position

### 7.2 Event Handling

Implement ONVIF event subscription:
- Motion detection
- Tampering alerts
- Connection status changes

### 7.3 Snapshot/Image Capture

Add ability to request snapshots:
- Trigger snapshot via OPC-UA method
- Store to Ignition file system
- Return file path

## Phase 8: Documentation and Release

### 8.1 User Documentation

Create user guide covering:
- How to install the module
- How to configure a device connection
- Supported ONVIF services
- Troubleshooting common issues

### 8.2 Developer Documentation

Update javadoc comments:
- All public classes and methods
- Configuration options
- Extension points

### 8.3 Release Checklist

- [ ] All tests passing
- [ ] No compiler warnings
- [ ] Version number updated
- [ ] Changelog updated
- [ ] README updated
- [ ] Built and tested in Docker
- [ ] Git committed with descriptive message
- [ ] Git tag created for release
- [ ] Pushed to GitHub
- [ ] Release notes created

## Estimated Timeline

- **Phase 1**: 2-3 days (research and setup)
- **Phase 2**: 3-5 days (basic connection)
- **Phase 3**: 3-5 days (OPC-UA address space)
- **Phase 4**: 2-3 days (polling)
- **Phase 5**: 3-5 days (testing)
- **Phase 6**: 2-3 days (error handling)
- **Phase 7**: 5-10 days (advanced features - optional)
- **Phase 8**: 2-3 days (documentation)

**Total**: 22-37 days (without advanced features: 17-27 days)

## Next Steps

1. **Read LEARNINGS.md** - Understand critical lessons
2. **Research ONVIF** - Study protocol and specifications
3. **Choose library approach** - Decide on onvif-java vs. manual implementation
4. **Start Phase 1** - Begin with protocol foundation

---

**Good luck! Remember to commit frequently and push to GitHub regularly.**
