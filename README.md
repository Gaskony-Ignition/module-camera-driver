# Ignition Camera Driver Module

A multi-protocol camera driver module for Inductive Automation's Ignition SCADA platform. Connects to IP cameras using ONVIF, RTSP, MJPEG, or snapshot URLs and exposes camera data through Ignition's OPC-UA server.

## Overview

The Camera Driver module provides two device types for connecting to IP cameras:

### ONVIF Camera
For cameras that support the ONVIF protocol (Profile S/T). Provides full device discovery, media profile enumeration, PTZ control, streaming, and snapshot capture via ONVIF SOAP services.

### Generic Camera
For cameras that provide direct RTSP, MJPEG, or snapshot HTTP URLs but may not support ONVIF. Uses bundled go2rtc for RTSP-to-MJPEG transcoding.

## Current Status

**Version**: 2.34.2 | **Status**: Production Ready

### Supported Connection Types

| Type | Protocol | Features |
|------|----------|----------|
| **ONVIF Camera** | ONVIF SOAP | Device discovery, media profiles, PTZ control, snapshots, streaming |
| **Generic Camera** | RTSP / MJPEG / HTTP | Direct URL connections, go2rtc RTSP transcoding, snapshot polling |

### Key Features
- Multi-protocol camera connectivity (ONVIF, RTSP, MJPEG, snapshot URLs)
- Authenticated HTTP endpoints (session auth, Basic Auth, API key)
- Per-IP rate limiting (DoS protection)
- Bundled go2rtc for RTSP-to-MJPEG transcoding
- Hierarchical OPC-UA address space with camera data
- Connection Browser UI in Gateway Config
- Configurable SSL/TLS validation modes
- 168 automated tests with 100% pass rate

## Quick Start

### 1. Build
```bash
cd /modules/ignition-module-camera-driver
./gradlew clean build
# Output: build/CameraDriver-2.34.2.modl
```

### 2. Install
```bash
docker cp build/CameraDriver-2.34.2.modl ignition-gateway:/usr/local/bin/ignition/user-lib/modules/
docker restart ignition-gateway
```

### 3. Configure

**For ONVIF cameras:**
1. Gateway > Config > OPC UA > Device Connections
2. Create new Device > Select **"ONVIF Camera"**
3. Enter camera IP address, username, password
4. Enable "Auto-discover Services"
5. Save and view tags in OPC Browser

**For generic cameras (RTSP/MJPEG/Snapshot):**
1. Gateway > Config > OPC UA > Device Connections
2. Create new Device > Select **"Generic Camera"**
3. Enter RTSP URL, snapshot URL, and/or MJPEG URL
4. Save and view in Connection Browser

See **[docs/TESTING.md](docs/TESTING.md)** for complete testing instructions.

## Configuration Fields

### ONVIF Camera

**General**: Device Name, Enabled

**Connection**: IP Address, Port, Username, Password, Use HTTPS, Connection Timeout, SSL Validation Mode

**ONVIF Settings**: Auto-discover Services, Poll Interval, Service Type

### Generic Camera

**General**: Enabled

**Camera Connection**: RTSP URL, Snapshot URL, MJPEG URL, Username, Password, Connection Timeout

**Stream Settings**: Default Frame Rate (FPS)

## HTTP Endpoints

All endpoints are at `/data/camera-driver/*` and require authentication.

| Endpoint | Description |
|----------|-------------|
| `/data/camera-driver/snapshot?device=X&profile=Y` | JPEG snapshot |
| `/data/camera-driver/stream?device=X&profile=Y&fps=Z` | MJPEG stream |
| `/data/camera-driver/devices` | List all devices |
| `/data/camera-driver/device/:name/status` | Device status |
| `/data/camera-driver/connection-browser` | Connection Browser UI |
| `/data/camera-driver/health` | Health check |

## Project Structure

```
ignition-module-camera-driver/
├── build.gradle.kts              # Root build configuration
├── settings.gradle.kts           # Subprojects
├── common/                       # Shared code
├── designer/                     # Designer-side hook
├── gateway/                      # Gateway-side implementation
│   └── src/main/java/com/onvif/driver/gateway/
│       ├── ONVIFModuleHook.java                    # Module entry point
│       ├── device/
│       │   ├── ONVIFDevice*.java                   # ONVIF Camera device type
│       │   ├── ONVIFPoller.java                    # ONVIF polling mechanism
│       │   ├── AddressSpaceBuilder.java            # OPC-UA node builder
│       │   └── generic/
│       │       └── GenericCamera*.java             # Generic Camera device type
│       ├── onvif/                                   # ONVIF protocol layer
│       │   ├── ONVIFClient.java                    # SOAP client
│       │   ├── ONVIFAuth.java                      # WS-UsernameToken auth
│       │   └── ...                                 # Data models
│       ├── servlet/
│       │   └── ONVIFRoutes.java                    # HTTP endpoints (all device types)
│       ├── stream/
│       │   └── Go2RtcManager.java                  # RTSP transcoding
│       └── auth/
│           └── AuthenticationManager.java          # HTTP auth
└── web-ui/                       # Connection Browser React component
```

## Documentation

| Document | Description |
|----------|-------------|
| [CHANGELOG.md](CHANGELOG.md) | Complete version history |
| [SKILLS.md](SKILLS.md) | Critical SDK lessons (read first!) |
| [docs/USAGE.md](docs/USAGE.md) | HTTP endpoint usage guide |
| [docs/SECURITY.md](docs/SECURITY.md) | Security architecture |
| [docs/TESTING.md](docs/TESTING.md) | Testing guide |
| [docs/CAMERA_COMPATIBILITY.md](docs/CAMERA_COMPATIBILITY.md) | Camera compatibility notes |
| [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) | Feature tracking |
| [CLAUDE.md](CLAUDE.md) | AI assistant context |

## BEFORE YOU START IMPLEMENTING

**Read `SKILLS.md` first!** It documents critical bugs and lessons learned that took significant time to debug:
- Why display names show as "?...?" and how to fix it
- Resource bundle registration (critical!)
- FormFieldType options and their actual behavior

## Camera Resources

### ONVIF
- **ONVIF Official**: https://www.onvif.org/
- **ONVIF Specifications**: https://www.onvif.org/profiles/

### RTSP / Streaming
- **go2rtc**: https://github.com/AlexxIT/go2rtc (bundled for RTSP transcoding)

## Building

```bash
./gradlew clean build        # Build module
./gradlew test               # Run tests
./gradlew clean build test   # Build and test
```

## Git Repository

- **Local Path**: `/modules/ignition-module-camera-driver/`
- **Remote**: https://github.com/Gaskony-Ignition/ignition-module-camera-driver.git

## License

MIT License - see [LICENSE](LICENSE) for details.

Copyright (c) 2025 Nigel Gwork

## Author

**Nigel Gwork** - [@nigelgwork](https://github.com/nigelgwork)

## Project Links

- **GitHub**: https://github.com/Gaskony-Ignition/ignition-module-camera-driver
- **Issues**: https://github.com/Gaskony-Ignition/ignition-module-camera-driver/issues
- **Security Policy**: See [docs/SECURITY.md](docs/SECURITY.md)
