# Changelog

All notable changes to the Ignition ONVIF Driver module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2025-11-22

### SECURITY
- **CRITICAL**: Removed hardcoded credentials from gradle.properties
- **CRITICAL**: Implemented configurable SSL/TLS validation with three modes:
  - STRICT: Full certificate validation (recommended for production)
  - TRUST_FIRST_USE: Trust certificate on first connection (planned for future)
  - INSECURE: Accept all certificates (backward compatibility mode)
- Environment-based credential management for module signing
- Created comprehensive SECURITY.md documentation
- Added ValidationUtil for centralized input validation

### BREAKING CHANGES
- **SSL validation mode now configurable**: Default remains INSECURE for backward compatibility, but STRICT mode is now available and recommended for production
- **Module signing credentials**: Must be set via environment variables (KEYSTORE_PASSWORD, CERT_PASSWORD) instead of gradle.properties
- **ONVIFClient constructor**: Added sslValidationMode parameter (existing code must be updated)

### Added
- SslValidationMode enum with STRICT, TRUST_FIRST_USE, and INSECURE options
- SSL validation configuration field in device configuration UI
- ValidationUtil class for centralized input validation (eliminates code duplication)
- CLAUDE_CONTEXT.md for AI-assisted development
- Comprehensive SECURITY.md documentation
- gradle.properties.template for secure credential setup
- CORS origin validation helper methods

### Changed
- Updated Gson dependency from 2.10.1 to 2.11.0
- Refactored validation logic to use centralized ValidationUtil
- Updated all documentation to reflect v2.0.0 status
- Streaming features now documented as IMPLEMENTED
- Improved CORS policy with origin validation
- Enhanced security documentation across all files

### Fixed
- Security vulnerability: Hardcoded credentials in gradle.properties (now use environment variables)
- Security issue: Overly permissive SSL validation (now configurable with STRICT mode)
- Code duplication: Validation logic consolidated into ValidationUtil
- Build configuration: Module signing properly configured for environment-based credentials

### Removed
- Dead servlet code files (ONVIFSnapshotServlet.java, ONVIFStreamServlet.java, ONVIFServlet.java) - ~600 lines of unused code
- Hardcoded credentials from gradle.properties

### Known Limitations
- **HTTP endpoint authentication**: Currently uses OPEN_ROUTE. Full authentication implementation planned for v2.1.0 (requires custom session validation due to Ignition SDK limitations)
- **TRUST_FIRST_USE mode**: Not fully implemented, falls back to INSECURE mode
- **CORS configuration**: Origin validation is permissive; should be made configurable in future release

### Planned for v2.1.0
- HTTP endpoint authentication with session validation
- Full TRUST_FIRST_USE implementation with certificate pinning
- Configurable CORS origins
- Per-IP rate limiting
- Comprehensive automated test suite

## [1.0.23] - 2025-11-21

### Changed
- Clean ONVIF implementation with proper error handling
- Code cleanup and refactoring for improved maintainability
- Enhanced error messages and logging

### Fixed
- Error handling edge cases in ONVIF client
- Improved robustness of ONVIF operations

## [1.0.14] - 2025-11-20

### Fixed
- HTTP route mounting with proper access control
- MIME type specification for routes
- Route registration reliability

### Changed
- Improved route configuration and registration
- Enhanced route handler error handling

## [1.0.11] - 2025-11-19

### Fixed
- HTTP route mounting with proper .type() specification
- Route type detection and content type handling

## [1.0.7] - 2025-11-18

### Added
- RTSP to MJPEG streaming capabilities
- Live video streaming through HTTP endpoints
- Frame rate control for MJPEG streams
- Resource protection with concurrent stream limits

### Changed
- Enhanced streaming performance and reliability
- Improved frame delivery consistency

## [1.0.6] - 2025-11-18

### Fixed
- Empty tags folder in OPC-UA browser
- Node registration with NodeManager
- Tag visibility in Ignition Designer

### Changed
- Improved OPC-UA node management
- Enhanced tag browser integration

## [1.0.5] - 2025-11-11

### Fixed
- Password field handling in configuration UI
- Device visibility in Tag Browser
- Configuration persistence issues

### Changed
- Improved configuration UI reliability
- Enhanced device status reporting

## [1.0.4] - 2025-11-10

### Fixed
- Property bundle loading by relocating properties file
- Service Type dropdown formatting
- Display name localization

### Changed
- Properties file moved to correct package structure: `gateway/src/main/resources/com/onvif/driver/gateway/device/ONVIFDevice.properties`

## [1.0.3] - 2025-11-10

### Added
- XmlUtil utility class for secure XML parsing and manipulation
- XXE (XML External Entity) attack protection in all XML parsing
- XML injection prevention with proper escaping of user inputs
- HTTPS certificate validation with self-signed certificate support
- Thread-safe error counting using AtomicInteger in ONVIFPoller
- Comprehensive security hardening across all network operations
- IMPLEMENTATION_STATUS.md document tracking project completion
- Proper @SuppressWarnings annotations for SDK interface compliance

### Changed
- DeviceInformation converted from class to immutable record (72% code reduction)
- ONVIFClient now delegates to XmlUtil for all XML operations
- ONVIFPoller uses AtomicInteger for concurrent error counting
- All SOAP requests now escape user-controlled data to prevent XML injection
- SSL/TLS configuration added to HTTP client for secure HTTPS connections
- Updated README.md to reflect Phase 1-6 completion status
- Updated TESTING.md with correct version numbers and feature list

### Fixed
- Critical XXE vulnerability in DocumentBuilderFactory configuration
- Critical XML injection vulnerability in SOAP request building
- Thread safety issue in ONVIFPoller consecutive error counter
- Raw type warnings in ONVIFDevice OPC-UA interface methods
- Documentation inconsistencies (version numbers, feature status, file paths)

### Security
- Hardened XML parser against XXE attacks with disabled external entities
- All profile tokens and user inputs now properly escaped in SOAP requests
- Added SSL context configuration for HTTPS connections
- Documented security trade-offs (SHA-1, self-signed certs, hostname verification)

## [1.0.2] - 2025-01-09

### Fixed
- Property bundle loading by moving properties file to correct package structure
- Properties file now correctly located at `gateway/src/main/resources/com/onvif/driver/gateway/device/ONVIFDevice.properties`
- Service Type dropdown formatting in designer configuration UI
- Display names now show correctly instead of "¿...?" placeholder text

### Changed
- Certificate regenerated with SHA256 signature algorithm
- Module version bumped to reflect property bundle fixes

## [1.0.1] - 2025-01-08

### Fixed
- Module signing with DER certificate format
- PKCS7 certificate chain generation for proper module validation
- Build configuration to use correct certificate encoding

### Changed
- Gradle build now uses DER-encoded certificate for signing
- Certificate conversion process updated in build documentation

## [1.0.0] - 2025-01-07

### Added
- Initial complete implementation of ONVIF driver (Phases 1-6)
- ONVIF SOAP client with HTTP/HTTPS support
- WS-UsernameToken authentication with SHA-1 digest and nonce
- GetDeviceInformation operation
- GetServices operation for dynamic service discovery
- GetMediaProfiles operation
- PTZ status retrieval and control (absolute move, stop)
- Configurable polling mechanism (ONVIFPoller)
- Auto-reconnect with exponential backoff (max 5 attempts)
- Hierarchical OPC-UA address space with DeviceInfo, MediaProfiles, PTZ, and Status nodes
- Comprehensive error handling and logging
- Designer configuration UI with validation
- Localized resource bundles for i18n support
- Module signing infrastructure
- Complete build system with Gradle

### Components
- ONVIFClient - Main SOAP client for ONVIF communication
- ONVIFAuth - WS-UsernameToken authentication generator
- ONVIFPoller - Periodic device polling mechanism
- AddressSpaceBuilder - OPC-UA node hierarchy builder
- ONVIFDevice - Device lifecycle and integration
- ONVIFDeviceConfig - Configuration record with validation
- DeviceInformation, MediaProfile, PTZStatus, ONVIFService data models

## [Unreleased]

### Planned for v2.1.0
- Event subscription support (motion detection, tampering alerts)
- ONVIF Profile G support (recording search and playback)
- Comprehensive automated test suite
- Unit tests for ONVIF protocol operations
- Integration tests with camera simulators
- Performance optimization and connection pooling

---

## Version Numbering

- **Major** (X.0.0): Breaking changes, major feature additions
- **Minor** (0.X.0): New features, non-breaking changes
- **Patch** (0.0.X): Bug fixes, security patches, documentation updates

---

## Links

- [README.md](README.md) - Project overview and quick start
- [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) - Detailed implementation status
- [TESTING.md](TESTING.md) - Testing and installation guide
- [GitHub Repository](https://github.com/nigelgwork/ignition-ONVIF-driver)
