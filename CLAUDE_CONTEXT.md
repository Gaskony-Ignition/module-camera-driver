# Claude Context - Ignition ONVIF Driver

## Project Overview

**Name**: Ignition ONVIF Driver Module
**Version**: 2.0.0
**Status**: Production Ready - Major Security Update
**Language**: Java 17
**Framework**: Inductive Automation Ignition SDK 8.3.0
**Purpose**: Device driver module for connecting Ignition to ONVIF-compatible IP cameras and devices

## Project Location

```
/modules/ignition-ONVIF-driver/
```

## Quick Start for AI Assistants

### Essential Reading Order
1. **This file** - Overall context and architecture
2. **LEARNINGS.md** - Critical bugs and lessons learned (READ THIS to avoid hours of debugging)
3. **SECURITY.md** - Security architecture and practices
4. **README.md** - User-facing documentation
5. **IMPLEMENTATION_STATUS.md** - Current implementation status

### Building the Module
```bash
cd /modules/ignition-ONVIF-driver
./gradlew clean build
# Output: build/ONVIFDriver-2.0.0.unsigned.modl
```

### Common Commands
```bash
# Build module
./gradlew clean build

# Build and test
./gradlew clean build test

# Check for updates
./gradlew dependencyUpdates
```

## Architecture

### Module Structure

```
ignition-ONVIF-driver/
├── build.gradle.kts                 # Root build configuration
├── settings.gradle.kts              # Multi-module settings
├── gradle.properties                # Module metadata (credentials via env vars)
├── common/                          # Shared code (currently minimal)
│   └── build.gradle.kts
├── designer/                        # Designer-side code (minimal hook)
│   ├── build.gradle.kts
│   └── src/main/java/com/onvif/driver/designer/
│       └── DesignerHook.java
└── gateway/                         # Gateway-side implementation (main code)
    ├── build.gradle.kts
    └── src/main/java/com/onvif/driver/gateway/
        ├── ONVIFModuleHook.java                    # Module entry point
        ├── device/                                  # Device driver implementation
        │   ├── ONVIFDeviceConfig.java              # Configuration record
        │   ├── ONVIFDeviceExtensionPoint.java      # Device type registration
        │   ├── ONVIFDevice.java                    # Main device implementation
        │   └── AddressSpaceBuilder.java            # OPC-UA node builder
        ├── onvif/                                   # ONVIF protocol layer
        │   ├── ONVIFClient.java                    # SOAP client
        │   ├── ONVIFAuth.java                      # WS-UsernameToken auth
        │   ├── ONVIFPoller.java                    # Polling mechanism
        │   ├── DeviceInformation.java              # Device info record
        │   ├── MediaProfile.java                   # Media profile model
        │   ├── PTZStatus.java                      # PTZ status model
        │   └── ONVIFService.java                   # Service endpoint model
        ├── servlet/                                 # HTTP endpoints
        │   └── ONVIFRoutes.java                    # Snapshot/streaming routes
        └── util/                                    # Utility classes
            └── XmlUtil.java                        # Secure XML parsing
```

### Key Components

#### 1. ONVIFModuleHook (Gateway Entry Point)
- Registers module with Ignition
- Initializes resource bundles (CRITICAL - see LEARNINGS.md)
- Registers device type with OPC-UA driver
- Mounts HTTP routes for streaming/snapshots

#### 2. ONVIFDevice (Device Implementation)
- Manages device lifecycle (startup, shutdown, reconnect)
- Builds OPC-UA address space
- Coordinates polling and status updates
- Implements driver API interfaces

#### 3. ONVIFClient (ONVIF Protocol)
- SOAP communication with cameras
- WS-UsernameToken authentication (SHA-1 digest)
- XXE-protected XML parsing
- Configurable SSL/TLS validation (STRICT/TRUST_FIRST_USE/INSECURE)

#### 4. ONVIFRoutes (HTTP Endpoints)
- `/data/onvif-driver/snapshot` - JPEG snapshots (authenticated)
- `/data/onvif-driver/stream` - MJPEG streaming (authenticated)
- Resource protection with concurrent request limits

## Security Architecture (v2.0.0)

### BREAKING CHANGES in v2.0.0
1. **HTTP endpoints now require authentication** (previously open)
2. **SSL validation defaults to STRICT** (previously INSECURE)
3. **Gradle credentials via environment variables** (no hardcoded secrets)

### Security Features
- **XXE Protection**: All XML parsing hardened against injection
- **XML Injection Prevention**: All user inputs escaped in SOAP requests
- **Configurable SSL Validation**: Three modes (STRICT/TRUST_FIRST_USE/INSECURE)
- **Authenticated HTTP Endpoints**: REQUIRE_LOGIN access control
- **Environment-Based Credentials**: No secrets in version control
- **Input Validation**: All parameters validated (device names, profile tokens, etc.)

### SSL Validation Modes
```java
public enum SslValidationMode {
    STRICT,           // Full certificate validation (production)
    TRUST_FIRST_USE,  // Pin certificate on first connection
    INSECURE          // Accept all certificates (development only)
}
```

## ONVIF Protocol Implementation

### Implemented ONVIF Operations
- GetDeviceInformation - Device metadata
- GetServices - Service discovery
- GetMediaProfiles - Video encoder configurations
- GetProfiles - Media profiles
- GetStreamUri - RTSP stream URLs
- GetSnapshotUri - Snapshot URLs
- GetStatus (PTZ) - Pan/tilt/zoom positions
- AbsoluteMove (PTZ) - Position control
- Stop (PTZ) - Stop movement

### Authentication
Uses WS-UsernameToken with:
- Random nonce generation
- UTC timestamp
- SHA-1 password digest (ONVIF specification requirement)

### Polling Mechanism
- Configurable interval (default: 5 seconds)
- PTZ status updates
- Automatic error detection (max 3 consecutive errors)
- Exponential backoff retry (max 5 attempts)

## OPC-UA Address Space

```
[DeviceName]/
├── DeviceInfo/
│   ├── Manufacturer
│   ├── Model
│   ├── FirmwareVersion
│   ├── SerialNumber
│   └── HardwareId
├── Profiles/
│   └── [ProfileToken]/
│       ├── Name
│       ├── Encoding
│       ├── Resolution
│       ├── Quality
│       ├── FrameRate
│       ├── BitrateLimit
│       └── StreamUri
├── PTZ/
│   ├── Pan
│   ├── Tilt
│   ├── Zoom
│   ├── MoveStatus
│   └── LastUpdate
└── Status/
    ├── ConnectionStatus
    ├── ErrorMessage
    └── LastUpdate
```

## Configuration Fields

### General
- **Device Name** - Unique identifier
- **Enabled** - Enable/disable device

### Connection
- **IP Address** - Camera IP (validated)
- **Port** - ONVIF port (default: 80 or 443)
- **Username** - ONVIF credentials
- **Password** - Encrypted password (SecretConfig)
- **Use HTTPS** - Enable SSL/TLS
- **SSL Validation Mode** - STRICT/TRUST_FIRST_USE/INSECURE (NEW in v2.0.0)
- **Connection Timeout** - Timeout in seconds

### ONVIF Settings
- **Auto-discover Services** - Automatic service discovery
- **Poll Interval** - Polling frequency in seconds
- **Service Type** - Service type selection

## Critical Bugs to Avoid

### Bug #1: Display Names as "¿...?"
**Cause**: Resource bundle not registered with BundleUtil
**Fix**: Register in ONVIFModuleHook.startup()
```java
BundleUtil.get().addBundle("ONVIFDevice", ONVIFDeviceExtensionPoint.class, "ONVIFDevice");
```
See LEARNINGS.md for complete details.

### Bug #2: FormFieldType Confusion
**Issue**: FormFieldType.FILE is for UPLOAD, not browsing
**Fix**: Use appropriate FormFieldType for each field (TEXT, NUMBER, SELECT, SECRET, etc.)

### Bug #3: Property File Location
**Issue**: Properties file must be in exact package structure
**Fix**: `gateway/src/main/resources/com/onvif/driver/gateway/device/ONVIFDevice.properties`

## Dependencies

### Core Dependencies
```kotlin
// Ignition SDK (compileOnly)
- ignition-common
- ignition-gateway-api
- ignition-driver-api

// HTTP Client (modlImplementation)
- httpclient:4.5.14
- httpcore:4.4.16

// JSON (modlImplementation)
- gson:2.10.1  // TODO: Update to 2.13.2

// Servlet API (compileOnly)
- jakarta.servlet-api:5.0.0
```

## Known Issues & Limitations

1. **SHA-1 Hashing**: WS-UsernameToken uses SHA-1 (ONVIF spec requirement, not a bug)
2. **Single Profile Polling**: PTZ polling uses first media profile only
3. **No Event Support**: ONVIF events not yet implemented
4. **Static Device Registry**: Uses static ConcurrentHashMap (should be instance-based)

## Testing

### Build and Install
```bash
# Build module
./gradlew clean build

# Install to Docker container
docker cp build/ONVIFDriver-2.0.0.unsigned.modl ignition-gateway:/usr/local/bin/ignition/user-lib/modules/
docker restart ignition-gateway

# Check logs
docker logs -f ignition-gateway
```

### Configuration
1. Gateway → Config → OPC UA → Device Connections
2. Create new Device → Select "ONVIF Driver"
3. Configure IP, credentials, SSL validation mode
4. Save and verify in OPC Browser

### Testing Endpoints
```bash
# Snapshot (requires authentication)
curl -u admin:password http://localhost:8088/data/onvif-driver/snapshot?device=Camera01&profile=Profile_1

# Stream (requires authentication)
curl -u admin:password http://localhost:8088/data/onvif-driver/stream?device=Camera01&profile=Profile_1&fps=10
```

## Version History

### v2.0.0 (2025-11-22) - CURRENT
- **SECURITY**: Removed hardcoded credentials
- **SECURITY**: Authenticated HTTP endpoints
- **SECURITY**: Configurable SSL validation
- **BREAKING**: Endpoints require login
- **BREAKING**: SSL defaults to STRICT

### v1.0.23 (2025-11-21)
- Clean implementation with error handling

### v1.0.0-1.0.14
- Initial implementation
- Streaming features
- Bug fixes

See CHANGELOG.md for complete history.

## Planned Enhancements (v2.1.0)

- Event subscription (motion detection, tampering alerts)
- ONVIF Profile G support (recording/playback)
- Comprehensive automated test suite
- Performance optimization
- Connection pooling

## Code Quality Notes

### Completed Improvements
- DeviceInformation converted to immutable record (72% code reduction)
- XmlUtil extraction (eliminated XML duplication)
- XXE vulnerability fixed
- XML injection vulnerability fixed
- Thread safety improvements (AtomicInteger)
- Static device registry (NEEDS FIX - see issues)

### TODO Items
1. Extract ValidationUtil.java (eliminate validation duplication)
2. Update Gson 2.10.1 → 2.13.2
3. Fix static device registry pattern (make instance-based)
4. Add comprehensive JavaDoc to public APIs
5. Add unit tests for ONVIF protocol

## Important Files for Context

### Must Read
- **LEARNINGS.md** - Avoid critical bugs
- **SECURITY.md** - Security practices
- **README.md** - User documentation
- **IMPLEMENTATION_STATUS.md** - Feature status

### Reference
- **TESTING.md** - Testing procedures
- **CHANGELOG.md** - Version history
- **STREAMING_IMPLEMENTATION.md** - Streaming details
- **CAMERA_COMPATIBILITY.md** - Camera compatibility

## Working with This Project

### Before Making Changes
1. Read LEARNINGS.md (avoid repeated mistakes)
2. Check SECURITY.md (security requirements)
3. Review IMPLEMENTATION_STATUS.md (what's done)
4. Update CHANGELOG.md (document changes)

### Making Changes
1. Follow existing code patterns
2. Add JavaDoc to public methods
3. Validate all user inputs
4. Escape XML properly (use XmlUtil)
5. Update tests if applicable
6. Update documentation

### Before Committing
1. Build successfully: `./gradlew clean build`
2. Test with real camera (if possible)
3. Update CHANGELOG.md
4. Update version in build.gradle.kts (if needed)
5. Update documentation (README, IMPLEMENTATION_STATUS)

## Contact & Support

**Repository**: /modules/ignition-ONVIF-driver/
**Documentation**: See *.md files in root directory
**Issues**: Track in GitHub issues (when repository published)

## AI Assistant Notes

When working with this codebase:

1. **Always read LEARNINGS.md first** - It documents critical bugs that took hours to debug
2. **Security is critical** - This is a production module handling camera credentials
3. **Resource bundles are mandatory** - Ignition requires proper i18n setup
4. **XML security is paramount** - Always use XmlUtil for XML operations
5. **Test thoroughly** - This module handles real-time video streaming
6. **Document changes** - Update CHANGELOG.md and relevant documentation

### Common Tasks

**Add new ONVIF operation**:
1. Add method to ONVIFClient.java with JavaDoc
2. Use XmlUtil for XML operations
3. Update AddressSpaceBuilder.java if creating new nodes
4. Document in IMPLEMENTATION_STATUS.md

**Add new configuration field**:
1. Add to ONVIFDeviceConfig.java record
2. Add i18n keys to ONVIFDevice.properties
3. Update README.md configuration section
4. Update TESTING.md with new field

**Fix security issue**:
1. Document in CHANGELOG.md under SECURITY section
2. Update SECURITY.md
3. Bump major version if breaking change
4. Update all documentation

---

**Last Updated**: 2025-11-22
**Document Version**: 2.0.0
**Maintained By**: Project maintainers
