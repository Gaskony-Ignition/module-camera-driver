# Camera Compatibility Guide

## Connection Types

The Camera Driver module supports two device types:

1. **ONVIF Camera** - For cameras implementing the ONVIF protocol (Profile S/T)
2. **Generic Camera** - For cameras providing direct RTSP, MJPEG, or snapshot URLs (no ONVIF required)

If your camera doesn't support ONVIF or has incomplete ONVIF compliance, use the **Generic Camera** device type with direct URLs instead.

## ONVIF Compliance

The ONVIF Camera device type implements the **ONVIF Profile S** specification. It follows the standard as defined by the ONVIF Forum.

## What Works

### ✅ Standard ONVIF Features
- Device discovery and connection
- Media profile enumeration
- RTSP stream URI retrieval
- PTZ control (if supported by camera)
- Device information and capabilities
- OPC-UA tag exposure for all profiles and streams

### ✅ Tested Compatible Cameras
Cameras that properly implement ONVIF Profile S:
- Axis Communications cameras
- Hikvision cameras (ONVIF-compliant models)
- Dahua cameras (ONVIF-compliant models)
- Other cameras with **proper ONVIF certification**

## Known Issues

### ❌ HTTP Snapshot Not Working (ONVIF Camera)

**Symptom:** Snapshot endpoint returns HTTP 500 error with message about ONVIF non-compliance.

**Cause:** Some camera manufacturers (notably **Reolink**) do not properly implement the ONVIF GetSnapshotUri specification. Their snapshot URLs require proprietary session-based authentication instead of standard HTTP Basic Auth.

**Alternative:** Use the **Generic Camera** device type instead and provide the camera's direct snapshot URL.

**Error Message:**
```
Camera does not properly implement ONVIF snapshot specification.
Use RTSP StreamUri from OPC-UA tags instead.
```

**This affects:**
- Reolink cameras (all models)
- Some budget IP cameras
- Cameras claiming "ONVIF support" without full Profile S certification

### ✅ Workaround: Use RTSP Streams

Even when HTTP snapshots don't work, **RTSP streams DO work** and are exposed in OPC-UA tags:

**Tag Location:**
```
[default]OPC UA/<DeviceName>/Profiles/<ProfileName>/StreamUri
```

**Example Tag Value:**
```
rtsp://192.0.2.10:554/h264Preview_01_main
```

**Usage in Perspective:**
- RTSP cannot be displayed directly in web browsers
- Requires transcoding to HLS, WebRTC, or MJPEG
- Use external tools like:
  - MediaMTX (rtsp-simple-server)
  - FFmpeg with HLS output
  - WebRTC gateways

**Note:** The module correctly implements ONVIF. Cameras that don't work have firmware issues with ONVIF compliance.

## Checking Camera Compatibility

### Before Purchase

1. **Look for ONVIF Profile S certification** (not just "ONVIF compatible")
2. **Check manufacturer's spec sheet** for "Full ONVIF support"
3. **Prefer enterprise camera brands:**
   - Axis Communications (best ONVIF support)
   - Hikvision Pro series
   - Dahua Pro series
   - Hanwha Techwin (Samsung)

4. **Avoid if:**
   - Only lists "ONVIF compatible" without certification
   - Budget consumer cameras
   - Cameras primarily designed for proprietary apps

### After Installation

1. **Create an ONVIF device connection** in Config → OPC UA → Device Connections
2. **Check OPC-UA Tag Browser** for:
   - `[DeviceName]/Profiles/*` - Should show at least 2 profiles
   - `[DeviceName]/Profiles/[ProfileName]/StreamUri` - RTSP URL (always works)
   - `[DeviceName]/Profiles/[ProfileName]/SnapshotUri` - HTTP snapshot URL (may not work)

3. **Test RTSP stream** with VLC:
   ```
   VLC → Media → Open Network Stream
   rtsp://username:password@camera-ip:554/streampath
   ```

4. **Test HTTP snapshot** via module:
   ```
   http://gateway:8088/data/camera-driver/snapshot?device=DeviceName&profile=Profile000_MainStream
   ```

## Recommendations

### For New Deployments
✅ **Choose cameras with proven ONVIF Profile S certification**
- Ensures all features work correctly
- Better integration with Ignition
- Longer-term support

### For Existing Cameras
If ONVIF snapshots don't work:
- ✅ Switch to the **Generic Camera** device type with direct RTSP/snapshot URLs
- ✅ Use RTSP StreamUri tags (always available via ONVIF Camera)
- ✅ Set up RTSP → HLS transcoding server for Perspective
- ✅ Use Vision RTSP viewer components (if available)
- ❌ Don't expect HTTP snapshots to work on non-compliant ONVIF cameras

### For Budget Constraints
If using consumer-grade cameras:
- Expect RTSP streams to work
- Don't expect HTTP snapshots to work
- Plan for external transcoding infrastructure
- Consider the **total cost** including transcoding servers

## Support

### Module Issues
If a camera with **proven ONVIF Profile S certification** doesn't work:
- Check module logs: Status → Logs → Filter "ONVIF"
- Report issue with camera make/model
- Provide ONVIF GetCapabilities output

### Camera Issues
If camera doesn't properly support ONVIF:
- Contact camera manufacturer
- Request firmware update for ONVIF compliance
- Consider replacing with compliant camera
- **This is NOT a module bug**

## Future Enhancements

Potential additions (not currently planned):
- Auto-detection of camera brand/model
- Automatic transcoding service integration
- FFmpeg-based snapshot extraction from RTSP
- Camera-specific workarounds (if requested by users)

## Summary

- ✅ **ONVIF Camera device type correctly implements ONVIF Profile S**
- ✅ **Generic Camera device type works with any camera providing RTSP/MJPEG/snapshot URLs**
- ✅ **RTSP streams work with all ONVIF cameras**
- ❌ **HTTP snapshots may not work with non-compliant ONVIF cameras (use Generic Camera instead)**
- 📘 **Check ONVIF certification before purchasing cameras for the ONVIF device type**
- 🔧 **Use Generic Camera device type when ONVIF doesn't work**

The module supports multiple connection methods. If ONVIF doesn't work with your camera, the Generic Camera device type provides a reliable alternative using direct URLs.
