# Ignition Camera Driver

An Inductive Automation Ignition module that provides device driver connectivity to IP cameras via ONVIF protocol, RTSP, MJPEG, or snapshot URLs.

## Overview

This module allows Ignition users to:
- Connect to ONVIF-compatible IP cameras and devices on the network
- Authenticate using username/password credentials
- Discover and access ONVIF services (Device, Media, PTZ, Imaging, Analytics)
- Pull device data into Ignition's OPC-UA server
- Monitor and control devices through standard Ignition interfaces

## Current Status

**✅ PRODUCTION READY - CONNECTION BROWSER UI**
**Version**: 2.5.0

The Camera Driver is production-ready with comprehensive security enhancements, authenticated HTTP endpoints, configurable SSL/TLS validation, and full ONVIF protocol communication including PTZ control, streaming capabilities, polling, auto-reconnect, and OPC-UA integration.

### What's Complete ✅

**Full ONVIF Implementation with Security Enhancements**
- [x] Complete Gradle build configuration with module signing
- [x] Multi-module structure (common, designer, gateway)
- [x] Configuration UI with validation (IP, Port, Credentials, ONVIF settings)
- [x] Localized resource bundles (i18n) - properly configured
- [x] **Secure ONVIF SOAP client** with HTTP/HTTPS support
- [x] **WS-UsernameToken authentication** (SHA-1 digest with nonce)
- [x] **XXE-protected XML parsing** (secure against injection attacks)
- [x] **GetDeviceInformation** - manufacturer, model, firmware, serial number
- [x] **GetServices** - dynamic ONVIF service discovery
- [x] **GetMediaProfiles** - video encoder configurations
- [x] **PTZ Status retrieval** - pan, tilt, zoom positions
- [x] **PTZ Control** - absolute positioning and stop commands
- [x] **Polling mechanism** - configurable interval updates
- [x] **Auto-reconnect** - exponential backoff retry (max 5 attempts)
- [x] **Hierarchical OPC-UA address space** - DeviceInfo, MediaProfiles, PTZ, Status
- [x] **Thread-safe operations** - AtomicInteger for concurrent access
- [x] **Comprehensive error handling** and logging
- [x] **Configurable SSL/TLS validation** - STRICT, TRUST_FIRST_USE, or INSECURE modes
- [x] **Authenticated HTTP endpoints** - Session auth, Basic Auth, and API key support
- [x] **Per-IP rate limiting** - DoS protection (10 requests/minute per IP)
- [x] **RTSP to MJPEG streaming** - Live video streaming with authentication
- [x] **Snapshot capture** - GetSnapshotUri with authentication
- [x] **Environment-based credentials** - No hardcoded secrets
- [x] **Comprehensive test suite** - 168 automated tests with 100% pass rate

### Future Enhancements ⏳

**Additional Features**
- [ ] Event subscription (motion detection, tampering alerts)
- [ ] ONVIF Profile G support (recording search and playback)
- [ ] Performance optimization (connection pooling, caching)

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
- **SSL Validation Mode**: STRICT (validate certificates), TRUST_FIRST_USE (trust on first connection), or INSECURE (accept all certificates)

### ONVIF Settings
- **Auto-discover Services**: Automatically discover available ONVIF services
- **Poll Interval**: How often to poll the device for updates (seconds)
- **Service Type**: Type of ONVIF service (Device, Media, PTZ, Imaging, Analytics)
- **Enable Streaming**: Enable RTSP to MJPEG streaming endpoints (authenticated)

## Project Structure

```
ignition-module-camera-driver/
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
# build/CameraDriver-2.5.0.modl
```

## Quick Start

### 1. Build
```bash
cd /modules/ignition-module-camera-driver
./gradlew clean build
```

### 2. Install (Docker)
```bash
docker cp build/CameraDriver-2.5.0.modl ignition-gateway:/usr/local/bin/ignition/user-lib/modules/
docker restart ignition-gateway
```

### 3. Configure
1. Gateway → Config → OPC UA → Device Connections
2. Create new Device → Select "Camera Driver"
3. Enter IP address, username, password
4. Enable "Auto-discover Services"
5. Save and view in OPC Browser

See **[docs/TESTING.md](docs/TESTING.md)** for complete testing instructions.

## Documentation

### Getting Started
- **README.md** (this file) - Project overview and quick start
- **[CHANGELOG.md](CHANGELOG.md)** - Complete version history
- **[RELEASE_NOTES_v2.1.0.md](RELEASE_NOTES_v2.1.0.md)** - Current release notes

### Developer Resources
- **[LEARNINGS.md](LEARNINGS.md)** - Critical SDK lessons (read this first!)
- **[CLAUDE_CONTEXT.md](CLAUDE_CONTEXT.md)** - AI assistant context and architecture
- **[docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md)** - Feature tracking and status

### Technical Documentation
- **[docs/TESTING.md](docs/TESTING.md)** - Testing guide (automated + manual)
- **[docs/SECURITY.md](docs/SECURITY.md)** - Security architecture and best practices
- **[docs/USAGE.md](docs/USAGE.md)** - HTTP endpoint usage guide
- **[docs/CAMERA_COMPATIBILITY.md](docs/CAMERA_COMPATIBILITY.md)** - Camera compatibility notes

### Archived Documentation
- **[docs/archive/](docs/archive/)** - Historical planning and implementation docs

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

- **Local Path**: `/modules/ignition-module-camera-driver/`
- **Remote**: https://github.com/Gaskony-Ignition/ignition-module-camera-driver.git

## Version History

See [CHANGELOG.md](CHANGELOG.md) for detailed version history.

- **2.3.2** - UI compactness: Reduced header/card sizes by ~50% for efficient screen usage (current)
- **2.3.1** - Connection Browser fixes: Fixed authentication to use Gateway session, added device dropdown selector
- **2.3.0** - Connection Browser UI: Gateway Config menu item, web dashboard for viewing device connections
- **2.2.0** - Production authentication: Account lockout, SHA-256 hashed API keys, security event logging
- **2.1.0** - Authentication & comprehensive testing: HTTP endpoint authentication (session, Basic Auth, API key), per-IP rate limiting, 168 automated tests
- **2.0.0** - Major security update: Authenticated endpoints, configurable SSL validation, environment-based credentials
- **1.0.23** - Clean ONVIF implementation with proper error handling
- **1.0.3** - Security hardening, code quality improvements
- **1.0.0** - Initial implementation complete

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Copyright (c) 2025 Nigel Gwork

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Project Links

- **GitHub Repository**: https://github.com/Gaskony-Ignition/ignition-module-camera-driver
- **Issue Tracker**: https://github.com/Gaskony-Ignition/ignition-module-camera-driver/issues
- **Security Policy**: See [SECURITY.md](docs/SECURITY.md) for reporting security vulnerabilities

## Author

**Nigel Gwork**
- GitHub: [@nigelgwork](https://github.com/nigelgwork)
