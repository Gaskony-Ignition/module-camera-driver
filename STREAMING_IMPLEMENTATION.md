# ONVIF Driver - RTSP to MJPEG Streaming Implementation

**Version:** 1.0.7
**Date:** November 18, 2024
**Status:** ✅ Complete and Ready for Testing

---

## 📋 Summary

Successfully implemented MJPEG streaming capabilities for the ONVIF Driver module. The implementation uses **Option 1** (snapshot polling) which is the simplest and most compatible approach - no additional dependencies or transcoding required.

---

## 🎯 What Was Implemented

### New HTTP Endpoints

The module now provides two HTTP endpoints accessible from Perspective, Vision, or any web client:

#### 1. Snapshot Endpoint
```
GET /main/data/onvif-driver/snapshot?device=<DeviceName>&profile=<ProfileToken>
```
- Returns a single JPEG snapshot
- Rate limited to 50 concurrent requests
- Direct camera access via ONVIF

#### 2. MJPEG Stream Endpoint
```
GET /main/data/onvif-driver/stream?device=<DeviceName>&profile=<ProfileToken>&fps=<FrameRate>
```
- Returns multipart/x-mixed-replace MJPEG stream
- FPS parameter: 1-30 (default: 10)
- Rate limited to 20 concurrent streams
- Auto-recovery from transient errors

### Auto-Generated Tags

For each media profile discovered on the camera, the driver now creates:

- `IgnitionSnapshotUrl` - Ready-to-use snapshot URL
- `IgnitionStreamUrl` - Ready-to-use stream URL (default 10 FPS)

**Tag Location:**
```
Devices/[DeviceName]/MediaProfiles/[ProfileName]/IgnitionStreamUrl
```

---

## 📁 Files Added/Modified

### New Files
1. **`gateway/src/main/java/com/onvif/driver/gateway/servlet/ONVIFRoutes.java`**
   - Route handlers for snapshot and streaming
   - Resource management and rate limiting
   - Error handling and recovery

2. **`gateway/src/main/java/com/onvif/driver/gateway/servlet/ONVIFSnapshotServlet.java`**
   - Legacy servlet (kept for reference, not actively used)

3. **`gateway/src/main/java/com/onvif/driver/gateway/servlet/ONVIFStreamServlet.java`**
   - Legacy servlet (kept for reference, not actively used)

### Modified Files
1. **`build.gradle.kts`**
   - Version bumped to 1.0.7

2. **`gateway/build.gradle.kts`**
   - Added Jakarta Servlet API dependency (Ignition 8.3)

3. **`gateway/src/main/java/com/onvif/driver/gateway/ONVIFModuleHook.java`**
   - Implemented `mountRouteHandlers()` method
   - Implemented `getMountPathAlias()` returning "onvif-driver"

4. **`gateway/src/main/java/com/onvif/driver/gateway/device/ONVIFDeviceExtensionPoint.java`**
   - Added static device registry (ConcurrentHashMap)
   - Added `registerDevice()`, `unregisterDevice()`, `getDevice()` methods

5. **`gateway/src/main/java/com/onvif/driver/gateway/device/ONVIFDevice.java`**
   - Added device registration in `onStartup()`
   - Added device unregistration in `onShutdown()`
   - Added `getClient()` method for servlet access

6. **`gateway/src/main/java/com/onvif/driver/gateway/device/AddressSpaceBuilder.java`**
   - Added `IgnitionSnapshotUrl` tag generation
   - Added `IgnitionStreamUrl` tag generation
   - Added URL encoding helper method

7. **`gateway/src/main/java/com/onvif/driver/gateway/onvif/ONVIFClient.java`**
   - Added `getSnapshot(String profileToken)` method
   - Returns JPEG image as byte array

---

## 🔧 Technical Details

### Architecture Choice: RouteGroup API (Ignition 8.3)

Instead of traditional servlets, we use Ignition's modern RouteGroup API:
- Routes mounted at `/main/data/onvif-driver/*`
- Automatic request/response handling
- Better integration with Ignition's web framework
- Compatible with Ignition 8.3+

### Streaming Approach: Snapshot Polling

**Why Option 1 (MJPEG via Snapshots)?**
- ✅ No additional dependencies required
- ✅ Works with all browsers and Perspective
- ✅ Simple implementation
- ✅ Easy to debug and maintain
- ⚠️ Higher bandwidth than native RTSP
- ⚠️ Limited to ~30 FPS realistically

### Security Features

1. **Input Validation**
   - Only alphanumeric, dash, and underscore allowed in device/profile names
   - Prevents path traversal and injection attacks

2. **Rate Limiting**
   - Max 50 concurrent snapshot requests
   - Max 20 concurrent video streams
   - Prevents resource exhaustion

3. **CORS Headers**
   - `Access-Control-Allow-Origin: *`
   - Enables Perspective client access

4. **Error Recovery**
   - Automatic cleanup on client disconnect
   - Graceful handling of camera errors
   - Max 5 consecutive errors before stream termination

---

## 🚀 Usage Instructions

### In Perspective

**Option 1: Using Image Component with Tag Binding**
1. Add an Image component to your view
2. Bind the `imageUrl` property to the tag:
   ```
   tag("[default]Devices/MyCam/MediaProfiles/Profile_1/IgnitionStreamUrl")
   ```

**Option 2: Using Direct URL**
1. Add an Image component
2. Set `imageUrl` to:
   ```
   /main/data/onvif-driver/stream?device=MyCam&profile=Profile_1&fps=15
   ```

### In Vision

Use the tag browser to reference:
```
[default]Devices/MyCam/MediaProfiles/Profile_1/IgnitionStreamUrl
```

### Direct HTTP Access

From any web browser or HTTP client:
```
http://your-gateway:8088/main/data/onvif-driver/stream?device=MyCam&profile=Profile_1&fps=10
```

---

## 📦 Build Output

**Module File:** `build/ONVIFDriver-1.0.7.modl`

**Build Status:** ✅ Successful
- Signed module ready for deployment
- Compatible with Ignition 8.3+
- All dependencies bundled

---

## 🧪 Testing Checklist

Before deploying to production, test:

- [ ] Install module in Ignition 8.3 Gateway
- [ ] Create ONVIF device connection
- [ ] Verify `IgnitionStreamUrl` tags appear in tag browser
- [ ] Test snapshot endpoint in web browser
- [ ] Test stream endpoint in web browser
- [ ] Test MJPEG stream in Perspective Image component
- [ ] Verify FPS parameter works (try fps=5, 10, 20)
- [ ] Test concurrent stream limits
- [ ] Test error recovery (disconnect camera during stream)
- [ ] Check Gateway logs for any errors

---

## 🐛 Known Limitations

1. **Frame Rate Constraints**
   - Practical limit ~20-30 FPS depending on camera and network
   - Higher FPS = more bandwidth and CPU usage
   - Each frame requires a full HTTP snapshot request

2. **Bandwidth Usage**
   - MJPEG streams use more bandwidth than h.264/h.265
   - Each frame is a complete JPEG image
   - Consider network capacity with multiple concurrent streams

3. **Browser Compatibility**
   - MJPEG is universally supported
   - Some mobile browsers may have different performance characteristics

---

## 🔮 Future Enhancements (Not Implemented)

If you need better performance in the future, consider:

- **Option 2:** FFmpeg transcoding (RTSP → HLS/DASH)
- **Option 3:** WebRTC integration for ultra-low latency
- **Option 4:** Native RTSP player in custom Perspective component

---

## 📝 Git Status

**Branch:** master
**Commit:** a6d1bd9
**Status:** Pushed to GitHub ✅

All changes committed with comprehensive commit message and pushed to:
```
https://github.com/nigelgwork/ignition-ONVIF-driver
```

---

## 📞 Next Steps for Tomorrow

1. **Install and Test**
   - Deploy `ONVIFDriver-1.0.7.modl` to test gateway
   - Create test device connection
   - Verify streaming works in Perspective

2. **Performance Tuning**
   - Test different FPS settings
   - Monitor bandwidth usage
   - Check CPU usage on Gateway

3. **Documentation**
   - Create user guide for Perspective integration
   - Document best practices for frame rate selection
   - Add troubleshooting guide

4. **Optional: Advanced Features**
   - Add authentication to endpoints (if needed)
   - Implement caching layer for snapshots
   - Add recording/playback capabilities

---

## 🎉 Success Criteria - ALL MET ✅

- ✅ Module compiles without errors
- ✅ Version incremented to 1.0.7
- ✅ Routes registered using Ignition 8.3 API
- ✅ Stream URLs auto-generated in tags
- ✅ Security controls implemented
- ✅ Code committed to GitHub
- ✅ Build artifacts created
- ✅ Documentation complete

**The module is ready for installation and testing!**
