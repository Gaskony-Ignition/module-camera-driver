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

**⚠️ SKELETON PROJECT - READY FOR IMPLEMENTATION**

This project has been set up with the complete structure, configuration, and skeleton code based on learnings from the Ignition PLC Simulator project. The core ONVIF protocol communication has NOT been implemented yet.

### What's Complete ✅

- [x] Gradle build configuration
- [x] Module structure (common, designer, gateway)
- [x] Java skeleton files with extensive documentation
- [x] Configuration UI setup (IP, Port, Username, Password, etc.)
- [x] Resource bundle configuration (i18n)
- [x] Validation logic
- [x] Device lifecycle hooks (startup, shutdown)
- [x] Critical learnings documented from PLC Simulator project

### What Needs Implementation ❌

- [ ] ONVIF protocol communication (SOAP/XML)
- [ ] WS-Discovery for service discovery
- [ ] WS-UsernameToken authentication
- [ ] Parse ONVIF service responses
- [ ] Create OPC-UA nodes from ONVIF data
- [ ] Implement polling mechanism
- [ ] Error handling for network issues
- [ ] Support for different ONVIF services (Device, Media, PTZ, etc.)

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
# build/ONVIFDriver-1.0.0.modl
```

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
