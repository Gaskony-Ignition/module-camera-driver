# Claude Context - Ignition Camera Driver Module

This file contains module-specific instructions. Shared standards are in `/modules/CLAUDE.md` and `/modules/.claude/skills/`.

## Project Overview

**Name**: Ignition Camera Driver Module
**Version**: 3.0.0
**Status**: Production Ready
**Language**: Java 17
**Framework**: Inductive Automation Ignition SDK 8.3.0
**Purpose**: Multi-protocol camera driver module supporting ONVIF, RTSP, MJPEG, and snapshot URL connections to IP cameras

## Project Location

```
/modules/ignition-module-camera-driver/
```

## Quick Start for AI Assistants

### Essential Reading Order
1. **This file** - Overall context and architecture
2. **`.claude/skills/`** - Module-specific skills (`camera-i18n-bugs/SKILL.md`, `camera-protocols/SKILL.md`) — load to avoid hours of debugging
3. **`/modules/.claude/skills/`** - Shared skills (building, reviewing, testing, security)
4. **SECURITY.md** - Security architecture and practices
5. **README.md** - User-facing documentation
6. **docs/IMPLEMENTATION_STATUS.md** - Current implementation status

### Building the Module
```bash
cd /modules/ignition-module-camera-driver
./gradlew clean build
# Output: build/CameraDriver-3.0.0.modl
```

### Common Commands
```bash
./gradlew clean build          # Build module
./gradlew clean build test     # Build and test
./gradlew test                 # Run tests only
```

## Architecture

### Module Structure

The Camera Driver module provides **two device types** within a single Ignition module:

1. **ONVIF Camera** - For cameras supporting the ONVIF protocol
2. **Generic Camera** - For cameras providing direct RTSP/MJPEG/snapshot URLs

```
ignition-module-camera-driver/
├── build.gradle.kts                 # Root build configuration
├── settings.gradle.kts              # Multi-module settings
├── gradle.properties                # Module metadata
├── common/                          # Shared code (currently minimal)
│   └── build.gradle.kts
├── designer/                        # Designer-side code (minimal hook)
│   └── src/main/java/com/onvif/driver/designer/
│       └── DesignerHook.java
├── gateway/                         # Gateway-side implementation (main code)
│   ├── build.gradle.kts
│   └── src/main/java/com/onvif/driver/gateway/
│       ├── ONVIFModuleHook.java                    # Module entry point
│       ├── device/                                  # ONVIF Camera device type
│       │   ├── ONVIFDeviceConfig.java              # Configuration record
│       │   ├── ONVIFDeviceExtensionPoint.java      # Device type registration
│       │   ├── ONVIFDevice.java                    # Main device implementation
│       │   ├── ONVIFPoller.java                    # Polling mechanism
│       │   ├── AddressSpaceBuilder.java            # OPC-UA node builder
│       │   └── generic/                            # Generic Camera device type
│       │       ├── GenericCameraConfig.java
│       │       ├── GenericCameraExtensionPoint.java
│       │       ├── GenericCameraDevice.java
│       │       ├── GenericCameraClient.java
│       │       └── GenericCameraAddressSpaceBuilder.java
│       ├── onvif/                                   # ONVIF protocol layer
│       │   ├── ONVIFClient.java                    # SOAP client
│       │   ├── ONVIFAuth.java                      # WS-UsernameToken auth
│       │   ├── ONVIFService.java                   # Service endpoint model
│       │   ├── DeviceInformation.java              # Device info record
│       │   ├── MediaProfile.java                   # Media profile model
│       │   ├── PTZStatus.java                      # PTZ status model
│       │   └── util/XmlUtil.java                   # Secure XML parsing
│       ├── servlet/                                 # HTTP endpoints
│       │   └── ONVIFRoutes.java                    # Routes for all device types
│       ├── stream/                                  # Streaming infrastructure
│       │   ├── Go2RtcManager.java                  # go2rtc process manager
│       │   └── Go2RtcBinaryExtractor.java          # Binary extraction
│       ├── auth/                                    # Authentication
│       │   └── AuthenticationManager.java
│       └── util/                                    # Utilities
│           └── ValidationUtil.java
└── web-ui/                          # Gateway Config UI (React/TypeScript)
    ├── package.json
    └── src/pages/ConnectionBrowser/
```

### Key Components

#### 1. ONVIFModuleHook (Gateway Entry Point)
- Registers module with Ignition
- Creates both device extension points (ONVIF Camera + Generic Camera)
- Initializes go2rtc streaming manager
- Registers resource bundles for i18n
- Mounts HTTP routes and Gateway Config navigation

#### 2. ONVIF Camera Device Type
- **ONVIFDevice** - Device lifecycle, ONVIF connection, OPC-UA address space
- **ONVIFClient** - SOAP communication, WS-UsernameToken auth, XXE-protected XML
- **ONVIFPoller** - Periodic device polling for PTZ and status updates
- **AddressSpaceBuilder** - OPC-UA node hierarchy from ONVIF data

#### 3. Generic Camera Device Type
- **GenericCameraDevice** - Device lifecycle for URL-based cameras
- **GenericCameraClient** - HTTP snapshot fetching
- **Go2RtcManager** - RTSP-to-MJPEG transcoding via bundled go2rtc

#### 4. Shared HTTP Endpoints (ONVIFRoutes)
- `/data/camera-driver/snapshot` - JPEG snapshots from any device type
- `/data/camera-driver/stream` - MJPEG streaming from any device type
- `/data/camera-driver/devices` - List all devices (both types)
- Authentication, rate limiting, and resource protection

## Device Types in Detail

### ONVIF Camera
- Connects via ONVIF SOAP protocol
- WS-UsernameToken authentication (SHA-1 digest)
- Service discovery, media profiles, PTZ control
- Configurable SSL/TLS validation (STRICT/TRUST_FIRST_USE/INSECURE)
- Auto-reconnect with exponential backoff

### Generic Camera
- Connects via direct URLs (RTSP, MJPEG, HTTP snapshot)
- go2rtc integration for RTSP-to-MJPEG transcoding
- Fallback chain: go2rtc RTSP -> native MJPEG -> snapshot polling
- No ONVIF dependency required

## Security Architecture

### HTTP Endpoint Authentication (v2.2.0+)
1. **Session Authentication** - Ignition Gateway sessions
2. **Basic Authentication** - With account lockout (5 failures = 15min lockout)
3. **API Key Authentication** - SHA-256 hashed keys

### Per-IP Rate Limiting
- 10 requests/minute per IP
- Proxy-aware (X-Forwarded-For, X-Real-IP)

### XML Security
- XXE protection on all XML parsing
- XML injection prevention with proper escaping

## OPC-UA Address Space (ONVIF Camera)

```
[DeviceName]/
├── DeviceInfo/         (Manufacturer, Model, Firmware, Serial, Hardware)
├── Services/           (Discovered ONVIF services)
├── MediaProfiles/      (Per-profile: encoding, resolution, URIs)
├── PTZ/                (Pan, Tilt, Zoom, MoveStatus)
└── Status/             (ConnectionStatus, LastUpdate, IP, Port)
```

## Critical Bugs to Avoid

### Bug #1: Display Names as "?...?"
**Cause**: Resource bundle not registered with BundleUtil
**Fix**: Register in ONVIFModuleHook.startup()
```java
BundleUtil.get().addBundle("ONVIFDevice", ONVIFDeviceExtensionPoint.class, "ONVIFDevice");
```

### Bug #2: Property File Location
Properties file must be in exact package structure:
`gateway/src/main/resources/com/onvif/driver/gateway/device/ONVIFDevice.properties`

## Automated Test Suite

- **168 tests** with 100% pass rate
- **Framework**: JUnit 5.10.1, Mockito 5.8.0, AssertJ 3.25.1
- **Coverage**: ValidationUtil (87), ONVIFAuth (21), XmlUtil (33), ONVIFClient (27)
- **Security tests**: XSS, SQL injection, XXE, Billion Laughs, path traversal

## Working with This Project

### Before Making Changes
1. Load the module skills in `.claude/skills/` (avoid repeated mistakes)
2. Check SECURITY.md (security requirements)
3. Review docs/IMPLEMENTATION_STATUS.md (what's done)

### Making Changes
1. Follow existing code patterns
2. Validate all user inputs
3. Escape XML properly (use XmlUtil)
4. Update tests if applicable
5. Update CHANGELOG.md

### Note on Package Names
Java packages are `com.gaskony.camera.*` as of v3.0.0 (renamed from `com.onvif.driver.*`). Class names like `ONVIFDevice`, `ONVIFModuleHook`, etc. retain their original capitalisation — they describe the ONVIF protocol, not the package vendor. The module ID is `com.gaskony.camera.opcua` (was `com.onvif.driver.opcua` pre-v3.0.0). Existing device profiles must be recreated on upgrade — see `MIGRATION-v3.md`.

---

**Last Updated**: 2026-02-11
**Document Version**: 3.0.0
