# Ignition Camera Driver Module

A multi-protocol camera driver module for Inductive Automation's Ignition SCADA platform. Connects to IP cameras using ONVIF, RTSP, MJPEG, or snapshot URLs and exposes camera data through Ignition's OPC-UA server.

## Overview

The Camera Driver module provides a single unified **Camera** device type for connecting to IP cameras. Each camera supports multiple connection methods, enabled per device, and exposes its data through Ignition's OPC-UA server.

### Connection methods

A single Camera device can use any combination of the following:

| Method | Protocol | Capabilities |
|--------|----------|-------------|
| **RTSP** | RTSP | Direct video streaming, browser playback via bundled go2rtc + ffmpeg |
| **MJPEG** | HTTP MJPEG | Direct MJPEG video streaming |
| **Snapshot** | HTTP | JPEG snapshot capture from a still-image URL |
| **ONVIF** | ONVIF SOAP | Device discovery, media profile enumeration, PTZ control, and auto-detection of stream/snapshot URLs (Profile S/T) |

URLs for RTSP, MJPEG, and snapshot can be entered directly or auto-detected via ONVIF when enabled.

## Current Status

**Version**: 3.0.9 | **Status**: Production Ready

### Key Features
- Single unified **Camera** device type with per-device connection methods
- Multi-protocol camera connectivity (RTSP, MJPEG, snapshot URLs, ONVIF)
- Authenticated HTTP endpoints (session auth, Basic Auth, API key)
- Per-IP rate limiting (DoS protection)
- Bundled go2rtc and ffmpeg for RTSP-to-browser (MP4/MJPEG) streaming
- Hierarchical OPC-UA address space with camera data
- Connection Browser UI in Gateway Config
- Configurable SSL/TLS validation modes
- 168 automated tests with 100% pass rate

## Quick Start

### 1. Build
```bash
cd /modules/ignition-module-camera-driver
./gradlew clean build
# Output: build/CameraDriver-3.0.9.modl
```

### 2. Install
```bash
docker cp build/CameraDriver-3.0.9.modl ignition-gateway:/usr/local/bin/ignition/user-lib/modules/
docker restart ignition-gateway
```

### 3. Configure

1. Gateway > Config > OPC UA > Device Connections
2. Create new Device > Select **"Camera"**
3. Enter the camera IP address, username, and password
4. Enable the connection methods the camera supports (RTSP, MJPEG, Snapshot, ONVIF)
5. For RTSP/MJPEG/snapshot, either enter URLs directly or enable ONVIF to auto-detect them
6. Save and view tags in OPC Browser, or open the Connection Browser

See **[docs/TESTING.md](docs/TESTING.md)** for complete testing instructions.

## Configuration Fields

All fields belong to the single **Camera** device type.

**General**: Device Name, Enabled

**Connection**: IP Address, Port, Username, Password, Use HTTPS, Connection Timeout, SSL Validation Mode

**Streams** (connection methods): RTSP Stream, MJPEG Stream, Snapshot, ONVIF / PTZ, plus optional RTSP URL / Snapshot URL / MJPEG URL overrides

**Advanced**: Poll Interval (ONVIF only)

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
│   └── src/main/java/com/gaskony/camera/gateway/
│       ├── CameraModuleHook.java                   # Module entry point
│       ├── device/
│       │   ├── CameraExtensionPoint.java           # Unified "Camera" device type
│       │   ├── CameraDevice.java                   # Device lifecycle
│       │   ├── CameraConfig.java                   # Device configuration record
│       │   ├── LegacyCameraExtensionPoint.java     # Hidden pre-v3.0.0 alias (back-compat)
│       │   ├── ONVIFPoller.java                    # ONVIF polling mechanism
│       │   ├── AddressSpaceBuilder.java            # OPC-UA node builder
│       │   └── generic/                            # URL-based stream/snapshot helpers
│       ├── onvif/                                   # ONVIF protocol layer
│       │   ├── ONVIFClient.java                    # SOAP client
│       │   ├── ONVIFAuth.java                      # WS-UsernameToken auth
│       │   └── ...                                 # Data models
│       ├── servlet/
│       │   └── CameraRoutes.java                   # HTTP endpoints
│       ├── stream/
│       │   └── Go2RtcManager.java                  # RTSP-to-browser streaming (go2rtc + ffmpeg)
│       └── auth/
│           └── AuthenticationManager.java          # HTTP auth
└── web-ui/                       # Connection Browser React component
```

## Documentation

| Document | Description |
|----------|-------------|
| [CHANGELOG.md](CHANGELOG.md) | Complete version history |
| [.claude/skills/](.claude/skills/) | Module-specific skills (camera-protocols, camera-i18n-bugs) |
| [/modules/.claude/skills/](../.claude/skills/) | Shared skills across all 5 modules |
| [docs/USAGE.md](docs/USAGE.md) | HTTP endpoint usage guide |
| [SECURITY.md](SECURITY.md) | Security architecture |
| [docs/TESTING.md](docs/TESTING.md) | Testing guide |
| [docs/CAMERA_COMPATIBILITY.md](docs/CAMERA_COMPATIBILITY.md) | Camera compatibility notes |
| [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) | Feature tracking |
| [CLAUDE.md](CLAUDE.md) | AI assistant context |

## BEFORE YOU START IMPLEMENTING

**Load the module-specific skills in `.claude/skills/`** (`camera-i18n-bugs/SKILL.md` and `camera-protocols/SKILL.md`). They document critical bugs and lessons learned that took significant time to debug:
- Why display names show as "?...?" and how to fix it
- Resource bundle registration (critical!)
- FormFieldType options and their actual behaviour

## Camera Resources

### RTSP / Streaming
- **go2rtc**: https://github.com/AlexxIT/go2rtc (bundled for RTSP-to-browser streaming)

### ONVIF
- **ONVIF Official**: https://www.onvif.org/
- **ONVIF Specifications**: https://www.onvif.org/profiles/

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
