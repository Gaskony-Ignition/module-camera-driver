# Changelog

All notable changes to the Ignition ONVIF Driver module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.3] - 2025-01-10

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

### Planned
- Event subscription support (motion detection, tampering alerts)
- Snapshot capture via GetSnapshotUri
- Media streaming URL exposure via GetStreamUri
- ONVIF Profile G support (recording search and playback)
- Comprehensive automated test suite
- Unit tests for ONVIF protocol operations
- Integration tests with camera simulators

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
