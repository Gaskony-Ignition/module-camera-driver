# ONVIF Driver v1.0.11 - Testing Notes

## What Was Fixed Today

After extensive debugging with multiple agents, discovered the correct way to mount HTTP routes in Ignition 8.3.

### The Problem
- Routes were failing to mount with error: "Access control must be specified"
- Tried multiple approaches: `/main/`, `/system/`, different parent classes
- AbstractDeviceModuleHook was calling mountRouteHandlers() but routes still failed

### The Solution
Found in official Ignition SDK examples: **Must call `.type()` before `.mount()`**

```java
routes.newRoute("/snapshot")
    .handler(this::handleSnapshot)
    .type(RouteGroup.TYPE_JSON)  // THIS WAS MISSING!
    .mount();
```

## Module Details

**File:** `build/ONVIFDriver-1.0.11.modl`  
**Version:** 1.0.11  
**Size:** 1.6 MB  
**Build Status:** ✅ Successful  

## Installation Steps for Tomorrow

1. **Upload Module**
   - Gateway: http://192.168.7.111:9088
   - Go to: Config → System → Modules
   - Upload: `ONVIFDriver-1.0.11.modl`
   - Click Install/Upgrade

2. **Restart Gateway**
   - Wait for gateway to restart completely

3. **Check Logs**
   Look for these messages in gateway logs:
   ```
   ========== mountRouteHandlers() CALLED! ==========
   ========== Mounting ONVIF route handlers at /main/data/onvif-driver/* ==========
   Mounted ONVIF routes: /snapshot and /stream
   ========== Route handlers mounted successfully ==========
   ```

   **Important:** Should NOT see "Access control must be specified" error anymore!

4. **Test URLs**

   **Test 1: Snapshot**
   ```
   http://192.168.7.111:9088/main/data/onvif-driver/snapshot?device=SideCamera&profile=000
   ```
   Expected: Single JPEG image

   **Test 2: MJPEG Stream**
   ```
   http://192.168.7.111:9088/main/data/onvif-driver/stream?device=SideCamera&profile=000&fps=10
   ```
   Expected: Live MJPEG video stream

5. **Test in Perspective**
   - Add Image component
   - Set `imageUrl` property to:
     ```
     /main/data/onvif-driver/stream?device=SideCamera&profile=000&fps=10
     ```
   - Or bind to tag:
     ```
     tag("[default]Devices/SideCamera/MediaProfiles/Profile_0/IgnitionStreamUrl")
     ```

## Expected Results

### ✅ Success Indicators
- No errors in gateway logs
- URLs return video content (not 404)
- Perspective Image component shows live stream
- Can adjust FPS parameter (1-30)

### ❌ If Still Failing
Check gateway logs for:
- Any exceptions during route mounting
- Whether mountRouteHandlers() was actually called
- Any new error messages

## What Changed (Technical Details)

### ONVIFRoutes.java
Added `.type(RouteGroup.TYPE_JSON)` specification:
- Line 46: Snapshot route
- Line 52: Stream route

### ONVIFModuleHook.java  
Added verbose logging with `==========` markers for easy debugging

### build.gradle.kts
Version bumped: 1.0.10 → 1.0.11

## Key Learnings

1. **Error messages can be misleading**
   - "Access control must be specified" actually meant "Route type must be specified"

2. **Official SDK examples are the source of truth**
   - Found solution in: ignition-sdk-examples/perspective-component
   - DataEndpoints.java showed correct pattern

3. **AbstractDeviceModuleHook DOES support route mounting**
   - Previous assumption was wrong
   - It properly calls mountRouteHandlers() in Ignition 8.3

4. **Route type is mandatory**
   - Must call `.type()` before `.mount()`
   - Options: RouteGroup.TYPE_JSON, etc.

## Git Status

- **Commit:** 3f6211e
- **Branch:** master  
- **Status:** ✅ Pushed to GitHub
- **Repository:** https://github.com/nigelgwork/ignition-ONVIF-driver

## Next Session Tasks

- [ ] Install module 1.0.11
- [ ] Verify routes mount without errors
- [ ] Test snapshot URL in browser
- [ ] Test stream URL in browser
- [ ] Test in Perspective Image component
- [ ] Adjust FPS and test performance
- [ ] If successful, update documentation
- [ ] If failed, check logs and report findings

---

**Ready for testing tomorrow!** 🚀
