# ONVIF Driver - Testing Guide

## Overview

The ONVIF Driver module is production-ready with version 2.0.0 featuring major security enhancements. This guide explains how to test the module with real ONVIF devices.

## What's Been Implemented

### ✅ Complete ONVIF Implementation with Security Enhancements
- **ONVIF SOAP Client** - Full HTTP/HTTPS SOAP communication with XXE protection
- **WS-UsernameToken Authentication** - Secure digest-based authentication
- **Configurable SSL Validation** - STRICT, TRUST_FIRST_USE, or INSECURE modes
- **Authenticated HTTP Endpoints** - All streaming/snapshot endpoints require login
- **GetDeviceInformation** - Retrieves manufacturer, model, firmware, serial number
- **GetServices** - Discovers available ONVIF services
- **GetMediaProfiles** - Retrieves media stream profiles
- **PTZ Control** - Get status, absolute move, stop commands
- **RTSP to MJPEG Streaming** - Live video streaming with authentication
- **Snapshot Capture** - GetSnapshotUri with authentication
- **Polling Mechanism** - Periodic device updates via ONVIFPoller
- **Auto-Reconnect** - Exponential backoff retry logic
- **OPC-UA Address Space** - Hierarchical nodes representing ONVIF data (DeviceInfo, MediaProfiles, PTZ, Status)
- **Environment-Based Credentials** - No hardcoded secrets in gradle.properties

### 🔧 Components

**ONVIF Layer:**
- `ONVIFClient.java` - Main SOAP client for ONVIF communication
- `ONVIFAuth.java` - WS-UsernameToken authentication generator
- `DeviceInformation.java` - Device metadata model
- `ONVIFService.java` - Service endpoint model

**OPC-UA Layer:**
- `AddressSpaceBuilder.java` - Builds OPC-UA nodes from ONVIF data
- `ONVIFDevice.java` - Device lifecycle and integration

## Installation

### 1. Build the Module

```bash
cd /modules/ignition-ONVIF-driver
./gradlew clean build
```

Output: `build/ONVIFDriver-2.0.0.unsigned.modl`

### 2. Install in Ignition Gateway

**Option A: Docker (Recommended)**
```bash
# Copy module to container
docker cp build/ONVIFDriver-2.0.0.unsigned.modl ignition-gateway:/usr/local/bin/ignition/user-lib/modules/

# Restart gateway
docker restart ignition-gateway

# Check logs
docker logs -f ignition-gateway
```

**Option B: Manual Install**
1. Open Ignition Gateway (http://localhost:8088)
2. Go to Config → System → Modules
3. Click "Install or Upgrade a Module"
4. Upload `ONVIFDriver-2.0.0.unsigned.modl`
5. Gateway will restart automatically

### 3. Verify Installation

After restart, check:
1. Config → System → Modules
2. Look for "ONVIF Driver 2.0.0" in the module list
3. Status should be "Running"

## Configuration

### 1. Create Device Connection

1. Go to Config → OPC UA → Device Connections
2. Click "Create new Device..."
3. Select "ONVIF Driver" from device type dropdown
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
[INFO] ONVIF Driver module started successfully
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

**Current Status:** ✅ Production Ready - Major Security Update
**Build:** ONVIFDriver-2.0.0.unsigned.modl
**Last Updated:** 2025-11-22
