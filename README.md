# Ignition ONVIF Driver

An Inductive Automation Ignition module that provides device driver connectivity to ONVIF-compatible IP cameras and devices.

## Overview

This module allows Ignition users to:
- Connect to ONVIF-compatible IP cameras and devices on the network
- Authenticate using username/password credentials
- Discover and access ONVIF services (Device, Media, PTZ, Imaging, Analytics)
- Pull device data into Ignition's OPC-UA server
- Monitor and control devices through standard Ignition interfaces

## Current Status

**✅ FULLY FUNCTIONAL - CORE FEATURES IMPLEMENTED**

The ONVIF Driver is now fully operational with complete ONVIF protocol communication, authentication, and OPC-UA integration. Ready for deployment and testing with real ONVIF devices.

### What's Complete ✅

**Phase 1-3: Core ONVIF Communication**
- [x] Gradle build configuration
- [x] Module structure (common, designer, gateway)
- [x] Java implementation with extensive documentation
- [x] Configuration UI setup (IP, Port, Username, Password, etc.)
- [x] Resource bundle configuration (i18n)
- [x] Validation logic
- [x] Device lifecycle hooks (startup, shutdown)
- [x] **ONVIF SOAP client with HTTP/HTTPS support**
- [x] **WS-UsernameToken authentication (SHA-1 digest)**
- [x] **GetDeviceInformation implementation**
- [x] **GetServices discovery**
- [x] **OPC-UA address space builder**
- [x] **DeviceInfo, Services, and Status nodes**
- [x] **Complete XML parsing for SOAP responses**

### Future Enhancements ⏳

**Phase 4: Polling & Real-time Updates**
- [ ] Periodic polling mechanism for data updates
- [ ] Dynamic OPC-UA value updates
- [ ] Media profile information retrieval

**Phase 5: Advanced Features**
- [ ] PTZ control (writable OPC-UA nodes)
- [ ] Event subscription (motion detection, tampering)
- [ ] Snapshot capture capability
- [ ] Media streaming URL exposure

**Phase 6: Enhanced Error Handling**
- [ ] Auto-reconnect on network disconnect
- [ ] Retry logic with exponential backoff
- [ ] Improved error reporting in status nodes

## Configuration Fields

The device driver exposes the following configuration options:

### General
- **Device Name**: Unique name for the connection
- **Enabled**: Enable/disable the device

### Connection
- **IP Address**: IP address of the ONVIF device
- **Port**: ONVIF service port (default: 80 for HTTP, 443 for HTTPS)
- **Username**: ONVIF authentication username
- **Password**: ONVIF authentication password (masked)
- **Use HTTPS**: Connect using HTTPS instead of HTTP
- **Connection Timeout**: Timeout in seconds for connection attempts

### ONVIF Settings
- **Auto-discover Services**: Automatically discover available ONVIF services
- **Poll Interval**: How often to poll the device for updates (seconds)
- **Service Type**: Type of ONVIF service (Device, Media, PTZ, Imaging, Analytics)

## Project Structure

```
ignition-ONVIF-driver/
├── README.md                     # This file
├── LEARNINGS.md                  # CRITICAL lessons from PLC Simulator
├── PLAN.md                       # Implementation roadmap
├── build.gradle.kts              # Root build configuration
├── settings.gradle.kts           # Subprojects
├── gradle.properties             # Module settings
├── common/                       # Shared code (currently minimal)
├── designer/                     # Designer-side code (minimal hook)
└── gateway/                      # Gateway-side implementation
    ├── build.gradle.kts
    └── src/main/
        ├── java/com/onvif/driver/gateway/
        │   ├── ONVIFModuleHook.java                    # Module entry point
        │   └── device/
        │       ├── ONVIFDeviceConfig.java              # Configuration records
        │       ├── ONVIFDeviceExtensionPoint.java      # Device registration
        │       └── ONVIFDevice.java                    # Device implementation
        └── resources/com/onvif/driver/gateway/
            └── ONVIFDevice.properties                   # i18n translations
```

## Building the Module

```bash
# Build the module
./gradlew clean build

# Output will be at:
# build/ONVIFDriver-1.0.0.unsigned.modl (1.6 MB)
```

## Quick Start

### 1. Build
```bash
cd /modules/ignition-ONVIF-driver
./gradlew clean build
```

### 2. Install (Docker)
```bash
docker cp build/ONVIFDriver-1.0.0.unsigned.modl ignition-gateway:/usr/local/bin/ignition/user-lib/modules/
docker restart ignition-gateway
```

### 3. Configure
1. Gateway → Config → OPC UA → Device Connections
2. Create new Device → Select "ONVIF Driver"
3. Enter IP address, username, password
4. Enable "Auto-discover Services"
5. Save and view in OPC Browser

See **TESTING.md** for complete testing instructions.

## BEFORE YOU START IMPLEMENTING

**🚨 CRITICAL: Read `LEARNINGS.md` first!**

The LEARNINGS.md file documents critical bugs and lessons learned from the PLC Simulator project that took significant time to debug. Reading it will save you hours of troubleshooting.

Key topics covered:
- Why display names show as "¿...?" and how to fix it
- FormFieldType options and their actual behavior
- Resource bundle registration (critical!)
- Validation best practices
- Common pitfalls to avoid

## Implementation Roadmap

See `PLAN.md` for the detailed implementation plan.

## ONVIF Resources

- **ONVIF Official**: https://www.onvif.org/
- **ONVIF Specifications**: https://www.onvif.org/profiles/
- **ONVIF Test Tool**: https://www.onvif.org/test-tools/
- **Java ONVIF Libraries**:
  - onvif-java: https://github.com/milg0/onvif-java
  - Consider implementing SOAP client manually for better control

## Docker Testing

When testing in Docker (like the Ignition gateway):

1. Network connectivity:
   ```bash
   # Ensure ONVIF device is accessible from Docker container
   docker exec ignition-gateway ping 192.168.1.100
   ```

2. Check firewall rules allow ONVIF ports (80, 443, etc.)

3. File paths must be container paths:
   - `/usr/local/bin/ignition/data/`
   - NOT Windows paths like `C:\Users\...`

## Git Repository

- **Location**: `/modules/ignition-ONVIF-driver/`
- **Remote**: (To be added when ready to push to GitHub)

## Version History

- **1.0.0** - Initial skeleton project (current)

## License

(Add license information)

## Contact

(Add contact/author information)
