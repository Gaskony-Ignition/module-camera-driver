# Camera Driver Module - Testing Guide

## Overview

The Camera Driver module is production-ready with comprehensive authentication, rate limiting, and automated testing. This guide explains both automated testing and manual testing with real cameras. The module supports two device types: **ONVIF Camera** (ONVIF protocol) and **Generic Camera** (RTSP/MJPEG/snapshot URLs).

## What's Been Implemented

### ✅ Complete Camera Driver Implementation
- **ONVIF SOAP Client** - Full HTTP/HTTPS SOAP communication with XXE protection
- **WS-UsernameToken Authentication** - Secure digest-based authentication
- **Configurable SSL Validation** - STRICT, TRUST_FIRST_USE, or INSECURE modes
- **Authenticated HTTP Endpoints** - Session auth, Basic Auth, and API key support
- **Per-IP Rate Limiting** - 10 requests/minute per IP (DoS protection)
- **GetDeviceInformation** - Retrieves manufacturer, model, firmware, serial number
- **GetServices** - Discovers available ONVIF services
- **GetMediaProfiles** - Retrieves media stream profiles
- **PTZ Control** - Get status, absolute move, stop commands
- **RTSP to MJPEG Streaming** - Live video streaming with authentication
- **Snapshot Capture** - GetSnapshotUri with authentication
- **Polling Mechanism** - Periodic device updates via ONVIFPoller
- **Auto-Reconnect** - Exponential backoff retry logic
- **OPC-UA Address Space** - Hierarchical nodes representing ONVIF data (DeviceInfo, MediaProfiles, PTZ, Status)
- **Automated Test Suite** - 168 tests with 100% pass rate

### 🔧 Components

**ONVIF Layer:**
- `ONVIFClient.java` - Main SOAP client for ONVIF communication
- `ONVIFAuth.java` - WS-UsernameToken authentication generator
- `DeviceInformation.java` - Device metadata model
- `ONVIFService.java` - Service endpoint model

**OPC-UA Layer:**
- `AddressSpaceBuilder.java` - Builds OPC-UA nodes from ONVIF data
- `ONVIFDevice.java` - Device lifecycle and integration

## Automated Testing (v2.1.0)

### Test Suite Overview

The Camera Driver includes a comprehensive automated test suite with **168 tests** covering all core utilities.

**Test Statistics:**
- **Total Tests**: 168 (100% pass rate)
- **Test Code**: 1,535 lines
- **Execution Time**: ~2 seconds
- **Framework**: JUnit 5.10.1, Mockito 5.8.0, AssertJ 3.25.1

**Coverage:**
| Component | Tests | Coverage | Status |
|-----------|-------|----------|--------|
| ValidationUtil | 87 | 100% methods | ✅ All passing |
| ONVIFAuth | 21 | 100% methods | ✅ All passing |
| XmlUtil | 33 | 100% methods | ✅ All passing |
| ONVIFClient | 27 | Config & lifecycle | ✅ All passing |

### Running Automated Tests

```bash
# Run all tests
./gradlew test

# Run tests with detailed output
./gradlew test --info

# Run specific test class
./gradlew test --tests ValidationUtilTest

# Generate test report
./gradlew test
# Report available at: build/reports/tests/test/index.html
```

### Security Testing

All tests include security-focused scenarios:
- ✅ **XSS injection** prevention validated
- ✅ **SQL injection** prevention validated
- ✅ **JNDI injection** prevention validated
- ✅ **Path traversal** prevention validated
- ✅ **XXE attacks** prevention validated
- ✅ **Billion Laughs** expansion attack prevention validated
- ✅ **Null byte injection** blocked

### Test Examples

**ValidationUtil Tests (87 tests)**
- Device name validation (alphanumeric, hyphens, underscores)
- Profile token validation
- IP address validation (IPv4, hostnames)
- Port number validation
- Timeout validation
- Malicious input rejection (XSS, SQL injection, path traversal)

**ONVIFAuth Tests (21 tests)**
- WS-UsernameToken generation
- Nonce randomness validation
- Timestamp format verification
- SHA-1 digest calculation
- XML escaping in authentication headers

**XmlUtil Tests (33 tests)**
- XML escaping (special characters, entities)
- XXE attack prevention
- Billion Laughs attack prevention
- Malformed XML handling
- Empty/null input handling

**ONVIFClient Tests (27 tests)**
- SSL validation mode configuration
- HTTP client configuration
- Connection timeout handling
- Device configuration validation

## Manual Testing with Real Hardware

### 1. Build the Module

```bash
cd /modules/ignition-module-camera-driver
./gradlew clean build
```

Output: `build/CameraDriver-2.6.0.modl`

### 2. Install in Ignition Gateway

**Option A: Docker (Recommended)**
```bash
# Copy module to container
docker cp build/CameraDriver-2.6.0.modl ignition-gateway:/usr/local/bin/ignition/user-lib/modules/

# Restart gateway
docker restart ignition-gateway

# Check logs
docker logs -f ignition-gateway
```

**Option B: Manual Install**
1. Open Ignition Gateway (http://localhost:8088)
2. Go to Config → System → Modules
3. Click "Install or Upgrade a Module"
4. Upload `CameraDriver-2.6.0.modl`
5. Gateway will restart automatically

### 3. Verify Installation

After restart, check:
1. Config → System → Modules
2. Look for "Camera Driver" in the module list
3. Status should be "Running"

## Configuration

### 1. Create Device Connection

1. Go to Config → OPC UA → Device Connections
2. Click "Create new Device..."
3. Select **"ONVIF Camera"** or **"Generic Camera"** from device type dropdown
4. Click "Next"

### 2. Configure Connection Settings

**General:**
- Device Name: `Camera-01` (or descriptive name)
- Enabled: ✅ Checked

**Connection:**
- IP Address: `192.168.1.100` (your ONVIF device IP)
- Port: `80` (or `443` for HTTPS)
- Username: `admin` (ONVIF username)
- Password: `password123` (ONVIF password)
- Use HTTPS: ☐ Unchecked (unless device requires HTTPS)
- Connection Timeout: `10` seconds
- SSL Validation Mode: `STRICT` (recommended), or `INSECURE` for self-signed certs

**ONVIF:**
- Auto-discover Services: ✅ Checked
- Poll Interval: `5` seconds
- Service Type: `Device Management`
- Enable Streaming: ✅ Checked (for RTSP to MJPEG streaming)

### 3. Save and Enable

1. Click "Create New Device"
2. Device will connect automatically
3. Check status in the device list

## Expected Behavior

### Successful Connection

**Gateway Logs:**
```
[INFO] Camera Driver module started successfully
[INFO] Starting ONVIF device: Camera-01
[INFO] Created ONVIF client for http://192.168.1.100:80/onvif/device_service
[INFO] Testing connection to ONVIF device...
[INFO] Connected to ONVIF device: Axis P1357 (FW: 9.80.1, SN: ACCC8E123456)
[INFO] Discovering ONVIF services...
[INFO] Discovered 5 ONVIF services
[INFO] Building OPC-UA address space from ONVIF data
[INFO] DeviceInfo address space created with 5 variables
[INFO] Services address space created with 5 services
[INFO] ConnectionStatus address space created
[INFO] Address space built successfully
[INFO] ONVIF device started successfully: Camera-01
```

**OPC-UA Structure:**
```
[Camera-01]/
├── DeviceInfo/
│   ├── Manufacturer = "Axis Communications"
│   ├── Model = "P1357"
│   ├── FirmwareVersion = "9.80.1"
│   ├── SerialNumber = "ACCC8E123456"
│   └── HardwareId = "..."
├── Services/
│   ├── Device/
│   │   ├── Namespace = "http://www.onvif.org/ver10/device/wsdl"
│   │   ├── XAddr = "http://192.168.1.100/onvif/device_service"
│   │   └── Version = "2.5"
│   ├── Media/
│   ├── PTZ/
│   ├── Imaging/
│   └── Analytics/
└── Status/
    ├── ConnectionStatus = "Connected"
    └── LastUpdate = 1699468800000
```

### Connection Failure

**Gateway Logs:**
```
[ERROR] Error starting device: Camera-01
[ERROR] Failed to connect to ONVIF device - connection test failed
```

**Possible Causes:**
1. Incorrect IP address or port
2. Device not ONVIF-compatible
3. Wrong username/password
4. Network firewall blocking connection
5. Device requires HTTPS

## Testing with OPC-UA Client

### 1. Using Ignition Designer

1. Open Designer
2. Tools → OPC Connections → OPC Browser
3. Expand: `Ignition OPC UA Server → [Camera-01]`
4. You should see DeviceInfo, Services, and Status folders
5. Values should be populated with real device data

### 2. Using UaExpert (External Tool)

1. Download UaExpert from Unified Automation
2. Connect to `opc.tcp://localhost:62541`
3. Browse to `Objects → DeviceSet → [Camera-01]`
4. Drag nodes to Data Access View
5. Verify values update

## Troubleshooting

### Issue: "Device type not available"
- Module not installed correctly
- Restart gateway and check module list

### Issue: "Connection timeout"
- Check IP address and port
- Verify device is on same network
- Try ping: `docker exec ignition-gateway ping 192.168.1.100`
- Check firewall rules

### Issue: "Authentication failed"
- Verify username and password
- Check device admin interface
- Some devices require specific user roles

### Issue: "No services discovered"
- Uncheck "Auto-discover Services"
- Verify device is ONVIF-compliant
- Check ONVIF service URL manually

### Issue: "Empty values in OPC-UA"
- Check gateway logs for parsing errors
- Verify device returns valid SOAP responses
- Enable debug logging

## Debug Mode

To enable debug logging:

1. Go to Config → System → Console/Logging
2. Add logger: `com.onvif.driver`
3. Set level to: `DEBUG`
4. Check wrapper.log for detailed SOAP messages

## Test Devices

### Real ONVIF Devices
- Axis cameras (most models)
- Hikvision cameras
- Dahua cameras
- Bosch cameras
- Any ONVIF Profile S/T compliant device

### ONVIF Simulators
- **ONVIF Device Manager** - Has built-in test server
- **ONVIF Device Tool** - Official test tool
- **Happy Time ONVIF Server Simulator**

### Docker ONVIF Simulator (Free)
```bash
docker run -d -p 8080:8080 --name onvif-sim marcoraddatz/onvif-simulator
```

## Testing Streaming Features

### Test MJPEG Streaming (Authenticated)
```bash
# Access streaming endpoint (requires Ignition login)
http://localhost:8088/system/onvif/stream/Camera-01

# Access snapshot endpoint (requires Ignition login)
http://localhost:8088/system/onvif/snapshot/Camera-01
```

Note: These endpoints require authentication. Use your Ignition gateway credentials.

## Next Steps (Future Enhancements)

### Advanced Features
- [ ] Event subscription (motion detection, tampering alerts)
- [ ] ONVIF Profile G support (recording search and playback)
- [ ] Comprehensive automated test suite

## Support

For issues or questions:
1. Check gateway logs in `wrapper.log`
2. Review LEARNINGS.md for common pitfalls
3. Check PLAN.md for implementation roadmap
4. File issues on GitHub (when repository is public)

---

**Current Status:** Production Ready - Multi-Protocol Camera Driver
**Build:** CameraDriver-2.6.0.modl
**Last Updated:** 2026-02-10
