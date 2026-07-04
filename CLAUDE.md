# Claude Context - Ignition Camera Driver Module

This file contains module-specific instructions. Shared standards are in `/modules/CLAUDE.md` and `/modules/.claude/skills/`.

## Project Overview

**Name**: Ignition Camera Driver Module
**Version**: 3.1.3
**Status**: Production Ready
**Language**: Java 17
**Framework**: Inductive Automation Ignition SDK 8.3.0
**Purpose**: Multi-protocol IP camera driver module exposing a single unified `Camera` device type, with per-device connection methods (RTSP, MJPEG, snapshot URLs, and ONVIF)

## Project Location

```
/modules/ignition-module-camera-driver/
```

## Quick Start for AI Assistants

### Essential Reading Order

1. **`docs/PROJECT_CHARTER.md`** - Purpose, definition of done, won't-do list — drives all release decisions
2. **This file** - Overall context and architecture
3. **`/modules/.claude/skills/`** - Shared skills (building, reviewing, testing, security)
4. **SECURITY.md** - Security architecture and practices
5. **README.md** - User-facing documentation
6. **docs/IMPLEMENTATION_STATUS.md** - Current implementation status

(The former module-local skills `camera-i18n-bugs` and `camera-protocols` were lost
in the June 2026 data loss and have not been recreated — the surviving knowledge is
in "Critical Bugs to Avoid" below. Do not go looking for a `.claude/` directory here.)

### Building the Module

```bash
cd /modules/ignition-module-camera-driver
./gradlew clean build
# Output: build/CameraDriver-3.1.3.modl
```

### Common Commands

```bash
./gradlew clean build          # Build module
./gradlew clean build test     # Build and test
./gradlew test                 # Run tests only
```

## Architecture

### Module Structure

The Camera Driver module registers a **single unified `Camera` device type** (`CameraExtensionPoint`, type id `com.gaskony.camera.Camera`). Each Camera device supports multiple connection methods, enabled per device:

1. **RTSP** - direct video streaming (browser playback via bundled go2rtc + ffmpeg)
2. **MJPEG** - direct HTTP MJPEG streaming
3. **Snapshot** - HTTP JPEG snapshot capture
4. **ONVIF** - SOAP-based discovery, media profiles, PTZ, and auto-detection of stream/snapshot URLs

A hidden legacy alias type (`LegacyCameraExtensionPoint`, type id `com.onvif.driver.Camera`) exists ONLY so pre-v3.0.0 device profiles still load and remain editable. It is not a separate device type for new use.

```
ignition-module-camera-driver/
├── build.gradle.kts                 # Root build configuration
├── settings.gradle.kts              # Multi-module settings
├── gradle.properties                # Module metadata
├── common/                          # Shared code (currently minimal)
│   └── build.gradle.kts
├── designer/                        # Designer-side code (minimal hook)
│   └── src/main/java/com/gaskony/camera/designer/
│       └── DesignerHook.java
├── gateway/                         # Gateway-side implementation (main code)
│   ├── build.gradle.kts
│   └── src/main/java/com/gaskony/camera/gateway/
│       ├── CameraModuleHook.java                   # Module entry point
│       ├── device/                                  # Unified Camera device type
│       │   ├── CameraConfig.java                   # Configuration record
│       │   ├── CameraExtensionPoint.java           # Device type registration
│       │   ├── CameraDevice.java                   # Main device implementation
│       │   ├── LegacyCameraExtensionPoint.java     # Hidden pre-v3.0.0 alias (back-compat)
│       │   ├── ONVIFPoller.java                    # ONVIF polling mechanism
│       │   ├── AddressSpaceBuilder.java            # OPC-UA node builder
│       │   └── generic/                            # URL-based stream/snapshot helpers
│       │       ├── GenericCameraConfig.java
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
│       │   └── CameraRoutes.java                   # Shared HTTP routes
│       ├── stream/                                  # Streaming infrastructure
│       │   ├── Go2RtcManager.java                  # go2rtc process manager
│       │   ├── Go2RtcBinaryExtractor.java          # go2rtc binary extraction
│       │   └── FfmpegBinaryExtractor.java          # ffmpeg binary extraction
│       ├── auth/                                    # Authentication
│       │   └── AuthenticationManager.java
│       └── util/                                    # Utilities
│           └── ValidationUtil.java
└── web-ui/                          # Gateway Config UI (React/TypeScript)
    ├── package.json
    └── src/pages/ConnectionBrowser/
```

### Key Components

#### 1. CameraModuleHook (Gateway Entry Point)

- Registers module with Ignition
- Registers the unified `Camera` extension point (plus the hidden legacy alias)
- Initialises go2rtc + ffmpeg streaming manager
- Registers resource bundles for i18n
- Mounts HTTP routes and Gateway Config navigation

#### 2. Camera Device Type (CameraExtensionPoint / CameraDevice)

- **CameraDevice** - Device lifecycle, connection handling, OPC-UA address space
- **CameraConfig** - Configuration record with per-method toggles (RTSP, MJPEG, Snapshot, ONVIF)
- **AddressSpaceBuilder** - OPC-UA node hierarchy from camera data

#### 3. Connection Method Layers

- **onvif/** - ONVIF SOAP layer: `ONVIFClient` (SOAP, WS-UsernameToken auth, XXE-protected XML), `ONVIFPoller` (PTZ/status polling), data models
- **generic/** - URL-based helpers: `GenericCameraClient` (HTTP snapshot fetching), address-space support for direct stream/snapshot URLs
- **Go2RtcManager** - RTSP-to-browser streaming via bundled go2rtc + ffmpeg: WebRTC (primary, sub-second; signaling proxied through `WebRtcHandler`, media over ICE port 8555) with fMP4/MSE and snapshot fallbacks. Client side is one shared `CameraStreamEngine` (web-ui/src/utils/) used by Perspective components, the Gateway Config UI, and the static pages' player — never fork it per-scope

#### 4. Shared HTTP Endpoints (CameraRoutes)

- `/data/camera-driver/snapshot` - JPEG snapshots
- `/data/camera-driver/stream` - MJPEG/stream output
- `/data/camera-driver/devices` - List all devices
- Authentication, rate limiting, and resource protection

## Connection Methods in Detail

All methods belong to the single `Camera` device type and can be enabled in any combination per device.

### ONVIF

- ONVIF SOAP protocol (Profile S/T)
- WS-UsernameToken authentication (SHA-1 digest)
- Service discovery, media profiles, PTZ control
- Auto-detects RTSP/snapshot URLs for the other methods
- Configurable SSL/TLS validation (STRICT/TRUST_FIRST_USE/INSECURE)
- Auto-reconnect with exponential backoff

### RTSP / MJPEG / Snapshot

- Connects via direct URLs (RTSP, MJPEG, HTTP snapshot), entered manually or auto-detected via ONVIF
- go2rtc + ffmpeg for RTSP-to-browser streaming
- Fallback chain: go2rtc RTSP -> native MJPEG -> snapshot polling
- No ONVIF required when URLs are supplied directly

## Security Architecture

### HTTP Endpoint Authentication (v2.2.0+)

1. **Session Authentication** - Ignition Gateway sessions
2. **Basic Authentication** - With account lockout (5 failures = 15min lockout)
3. **API Key Authentication** - SHA-256 hashed keys

### Per-IP Rate Limiting

- 600 requests/minute per IP (deliberately high to support Perspective polling many cameras)
- Proxy-aware (X-Forwarded-For, X-Real-IP)

### XML Security

- XXE protection on all XML parsing
- XML injection prevention with proper escaping

## OPC-UA Address Space (Camera, ONVIF-populated nodes)

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
**Fix**: Register in CameraModuleHook.startup()

```java
BundleUtil.get().addBundle("Camera", CameraExtensionPoint.class, "Camera");
```

### Bug #2: Property File Location

Properties file must be in exact package structure:
`gateway/src/main/resources/com/gaskony/camera/gateway/device/Camera.properties`

## Automated Test Suite

- **557 tests** with 100% pass rate (535 gateway/common JUnit + 22 web-ui vitest)
- **Framework**: JUnit 5.10.1, Mockito 5.8.0, AssertJ 3.25.1
- **Coverage**: ValidationUtil (87), ONVIFAuth (21), XmlUtil (33), ONVIFClient (27)
- **Security tests**: XSS, SQL injection, XXE, Billion Laughs, path traversal

## Working with This Project

### Before Making Changes

1. Load the relevant shared skills from `/modules/.claude/skills/`
2. Check SECURITY.md (security requirements)
3. Review docs/IMPLEMENTATION_STATUS.md (what's done)

### Making Changes

1. Follow existing code patterns
2. Validate all user inputs
3. Escape XML properly (use XmlUtil)
4. Update tests if applicable
5. Update CHANGELOG.md

### Note on Package and Class Names

Java packages are `com.gaskony.camera.*` as of v3.0.0 (renamed from `com.onvif.driver.*`). The module entry point and shared routes are now `CameraModuleHook` and `CameraRoutes` (renamed from `ONVIFModuleHook` / `ONVIFRoutes`). Classes that genuinely implement the ONVIF protocol keep the `ONVIF` prefix — `ONVIFClient`, `ONVIFAuth`, `ONVIFService`, `ONVIFPoller`, plus the data models `DeviceInformation`, `MediaProfile`, `PTZStatus` — because they describe the protocol, not the package vendor. The module ID is `com.gaskony.camera.opcua` (was `com.onvif.driver.opcua` pre-v3.0.0). Existing pre-v3.0.0 device profiles load via the hidden legacy alias type `com.onvif.driver.Camera`.

---

**Last Updated**: 2026-07-03
**Document Version**: 3.1.3
