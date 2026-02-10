# Camera Driver - HTTP Endpoint Usage

## Important Discovery

The HTTP routes are accessible at `/data/{module-alias}/*` **NOT** `/main/data/{module-alias}/*` as the SDK documentation states.

This was discovered by comparing with the working PLC Simulator module logs.

## Available Endpoints

### 1. Test Endpoint (Diagnostic)
**URL:** `http://gateway:8088/data/camera-driver/test`

**Response:**
```json
{
  "status": "success",
  "message": "Camera Driver test route is working!",
  "timestamp": 1763722849385
}
```

**Purpose:** Verify that route mounting is working correctly.

---

### 2. Snapshot Endpoint
**URL:** `http://gateway:8088/data/camera-driver/snapshot?device={DeviceName}&profile={ProfileToken}`

**Authentication:** **REQUIRED** (v2.1.0+)

**Parameters:**
- `device` (required): The name of the ONVIF device connection (as configured in Ignition)
- `profile` (required): The media profile token (e.g., "000", "001", "main_stream", etc.)

**Example with Session Auth (Perspective/Vision):**
```
http://192.168.7.111:9088/data/camera-driver/snapshot?device=SideCamera&profile=000
```

**Example with Basic Auth:**
```bash
curl -u username:password \
  "http://192.168.7.111:9088/data/camera-driver/snapshot?device=SideCamera&profile=000"
```

**Example with API Key:**
```
http://192.168.7.111:9088/data/camera-driver/snapshot?device=SideCamera&profile=000&apiKey=YOUR_API_KEY
```

**Response:** JPEG image (Content-Type: image/jpeg)

**Error Codes:**
- `400` - Missing or invalid parameters
- `401` - Authentication required
- `404` - Device not found
- `429` - Rate limit exceeded (10 requests/minute per IP)
- `503` - Device not connected
- `500` - Failed to retrieve snapshot from camera

---

### 3. Stream Endpoint (MJPEG)
**URL:** `http://gateway:8088/data/camera-driver/stream?device={DeviceName}&profile={ProfileToken}&fps={FrameRate}`

**Authentication:** **REQUIRED** (v2.1.0+)

**Parameters:**
- `device` (required): The name of the ONVIF device connection
- `profile` (required): The media profile token
- `fps` (optional): Frames per second (1-30, default: 10)

**Example with Session Auth:**
```
http://192.168.7.111:9088/data/camera-driver/stream?device=SideCamera&profile=000&fps=15
```

**Example with Basic Auth:**
```bash
curl -u username:password \
  "http://192.168.7.111:9088/data/camera-driver/stream?device=SideCamera&profile=000&fps=15"
```

**Example with API Key:**
```
http://192.168.7.111:9088/data/camera-driver/stream?device=SideCamera&profile=000&fps=15&apiKey=YOUR_API_KEY
```

**Response:** MJPEG stream (Content-Type: multipart/x-mixed-replace; boundary=onvif-stream-boundary)

**Usage in HTML (with session auth):**
```html
<img src="http://192.168.7.111:9088/data/camera-driver/stream?device=SideCamera&profile=000&fps=15" />
```

**Note:** For external applications without session auth, use Basic Auth or API key in the URL.

---

## Testing

### Prerequisites
1. Create an ONVIF device connection in Ignition:
   - Go to: Config → OPC UA → Device Connections
   - Create New Device → Camera Driver
   - Configure camera IP, username, password
   - Save with a meaningful name (e.g., "SideCamera")

2. Ensure device is connected and running

### Test Sequence

1. **Test route mounting:**
   ```
   http://192.168.7.111:9088/data/camera-driver/test
   ```
   Should return JSON with success message.

2. **List available profiles:**
   - Check device tags in Tag Browser: `OPC UA > [DeviceName] > Profiles`
   - Profile tokens are listed as tag names (e.g., "000", "001")

3. **Get a snapshot:**
   ```
   http://192.168.7.111:9088/data/camera-driver/snapshot?device=SideCamera&profile=000
   ```
   Should display a JPEG image from the camera.

4. **View live stream:**
   ```
   http://192.168.7.111:9088/data/camera-driver/stream?device=SideCamera&profile=000&fps=10
   ```
   Should display live MJPEG stream.

---

## Troubleshooting

### 404 Not Found
- **Wrong URL pattern**: Ensure you're using `/data/` not `/main/data/`
- **Module not loaded**: Check Config → System → Modules for "Camera Driver"
- **Wrong alias**: The alias is `camera-driver` (with hyphen)

### 400 Bad Request
- **Missing parameters**: Both `device` and `profile` are required
- **Invalid characters**: Only alphanumeric, underscore, and hyphen allowed

### 404 Device Not Found
- **Device name mismatch**: Check exact name in Config → OPC UA → Device Connections
- **Case sensitive**: Device names are case-sensitive

### 503 Device Not Connected
- **Device offline**: Check device status in OPC UA connections
- **Wrong credentials**: Verify username/password in device settings
- **Network issue**: Ensure camera is reachable from gateway

### 500 Internal Server Error
- **Camera error**: Camera may have returned an error
- **Profile not supported**: Try a different profile token
- **Check logs**: View Status → Logs → Filter by "ONVIF"

---

## Access Control (v2.1.0+)

**All endpoints require authentication.** Three methods are supported:

1. **Session Authentication** - For Ignition users (Perspective/Vision)
   - Automatic for logged-in Gateway users
   - No additional parameters needed

2. **Basic Authentication** - For external tools
   - Standard HTTP Basic Auth headers
   - Example: `Authorization: Basic base64(username:password)`

3. **API Key** - For programmatic access
   - Pass `apiKey` query parameter
   - Example: `?apiKey=YOUR_API_KEY`

**Rate Limiting:**
- 10 requests per minute per IP address
- HTTP 429 returned when limit exceeded
- Prevents DoS attacks and resource exhaustion

---

## Performance Considerations

### Snapshot Endpoint
- **Concurrent limit**: 50 simultaneous snapshot requests
- **Timeout**: Depends on camera response time
- **Caching**: No caching - each request gets fresh snapshot

### Stream Endpoint
- **Concurrent limit**: 20 simultaneous streams
- **Frame rate**: 1-30 fps (default 10 fps)
- **Bandwidth**: ~50-500 KB/s per stream (depends on resolution)
- **Auto-recovery**: Stops after 5 consecutive errors

---

## Integration Examples

### Perspective View (IFRAME)
```python
# In a Perspective component
self.view.custom.snapshotUrl = f"http://gateway:8088/data/camera-driver/snapshot?device={deviceName}&profile=000"
```

### Vision RTSP Viewer
```python
# Get RTSP URL from device tag
rtspUrl = system.tag.read("[default]OPC UA/SideCamera/Profiles/000/StreamUri").value
```

### Web Page Embed
```html
<img src="http://192.168.7.111:9088/data/camera-driver/stream?device=SideCamera&profile=000"
     alt="Camera Feed"
     style="width:100%; height:auto;" />
```
