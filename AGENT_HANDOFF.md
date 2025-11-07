# ONVIF Driver - Agent Handoff Document

**Created:** 2025-11-07
**Status:** Skeleton project complete, ready for implementation
**Location:** `/modules/ignition-ONVIF-driver/`

---

## 🎯 Project Goal

Create an Ignition module that connects to ONVIF-compatible IP cameras and devices on the network, allowing users to:
- Input IP address, port, username, and password
- Authenticate and connect to the device
- Pull ONVIF data (device info, media profiles, PTZ status, etc.)
- Expose data through Ignition's OPC-UA server

---

## ✅ What's Been Done

### 1. Complete Project Structure
- Gradle multi-module build configuration
- Common, Designer, Gateway subprojects
- Module signing configuration
- Dependencies configured (including OPC-UA driver API)

### 2. Java Skeleton Files (All Documented)

**Gateway Side:**
- `ONVIFModuleHook.java` - Module entry point with resource bundle registration
- `ONVIFDeviceConfig.java` - Configuration records with form annotations
- `ONVIFDeviceExtensionPoint.java` - Device registration and validation
- `ONVIFDevice.java` - Main device implementation (lifecycle hooks)
- `ONVIFDevice.properties` - i18n translations for UI

**Designer Side:**
- `DesignerHook.java` - Minimal designer-side hook

### 3. Configuration UI Setup

The device driver exposes these configuration fields:

**General:**
- Device Name (required)
- Enabled (checkbox)

**Connection:**
- IP Address (required, validated)
- Port (default: 80)
- Username (required)
- Password (required, masked with FormFieldType.SECRET)
- Use HTTPS (checkbox)
- Connection Timeout (seconds)

**ONVIF Settings:**
- Auto-discover Services (checkbox)
- Poll Interval (seconds)
- Service Type (dropdown: Device, Media, PTZ, Imaging, Analytics)

### 4. Critical Learnings Documented

**LEARNINGS.md** contains critical lessons including:
- How to properly register resource bundles (BundleUtil)
- Why display names show as "¿...?" and how to fix it
- FormFieldType options and their actual behavior
- Validation best practices
- Common pitfalls to avoid

**This file is CRITICAL to read before implementing!**

### 5. Implementation Roadmap

**PLAN.md** provides detailed 8-phase implementation plan:
- Phase 1: ONVIF Protocol Foundation (research, library selection)
- Phase 2: Basic ONVIF Connection (authentication, device info)
- Phase 3: OPC-UA Address Space Creation
- Phase 4: Polling and Updates
- Phase 5: Testing and Validation
- Phase 6: Error Handling and Robustness
- Phase 7: Advanced Features (optional: PTZ, events, snapshots)
- Phase 8: Documentation and Release

### 6. Git Repository Initialized

- All files committed with descriptive message
- Clean git history
- Ready to push to GitHub when remote is configured

---

## ❌ What Needs Implementation

The skeleton is complete, but the **ONVIF protocol communication has NOT been implemented**:

### Core Tasks:
1. **ONVIF Client Implementation**
   - SOAP/XML request/response handling
   - WS-UsernameToken authentication
   - WS-Discovery for service discovery

2. **Device Connection**
   - Connect to ONVIF device via HTTP/HTTPS
   - Authenticate with username/password
   - Get device information

3. **OPC-UA Address Space**
   - Parse ONVIF responses
   - Create hierarchical folder structure
   - Add nodes for device data (manufacturer, model, IP, etc.)

4. **Polling Mechanism**
   - Poll device at configured interval
   - Update OPC-UA node values
   - Handle disconnects and reconnection

5. **Error Handling**
   - Network errors
   - Authentication failures
   - Invalid responses
   - Service unavailable

---

## 🚀 Quick Start for Implementation

### Step 1: Read Critical Documentation

**MANDATORY:** Read `LEARNINGS.md` first! It will save you hours of debugging.

### Step 2: Build and Test Skeleton

```bash
cd /modules/ignition-ONVIF-driver

# Build the module (will compile skeleton code)
./gradlew clean build

# Output will be at:
# build/ONVIFDriver-1.0.0.modl
```

Expected result: Build should succeed (skeleton compiles).

### Step 3: Research ONVIF Protocol

- Read ONVIF Core Specification
- Study SOAP/XML message format
- Review WS-UsernameToken authentication
- Decide on library approach (onvif-java vs. manual)

### Step 4: Follow PLAN.md

Implement each phase sequentially:
1. ONVIF Protocol Foundation
2. Basic Connection
3. Address Space
4. Polling
5. Testing
6. Error Handling

### Step 5: Test in Docker

```bash
# Build module
./gradlew clean build

# Copy to Docker container
docker cp build/ONVIFDriver-1.0.0.modl ignition-gateway:/usr/local/bin/ignition/user-lib/modules/

# Restart gateway
docker restart ignition-gateway

# Check logs
docker logs -f ignition-gateway
```

---

## 📁 Project Structure

```
/modules/ignition-ONVIF-driver/
├── README.md                     # Project overview
├── LEARNINGS.md                  # ⚠️ CRITICAL - Read first!
├── PLAN.md                       # Implementation roadmap
├── AGENT_HANDOFF.md             # This file
├── build.gradle.kts              # Root build config
├── settings.gradle.kts           # Subprojects
├── gradle.properties             # Module settings
├── gradle/                       # Gradle wrapper files
├── common/                       # Shared code (minimal)
├── designer/                     # Designer hook (minimal)
└── gateway/                      # Main implementation
    ├── build.gradle.kts
    └── src/main/
        ├── java/com/onvif/driver/gateway/
        │   ├── ONVIFModuleHook.java          # Entry point (✅ resource bundle registered)
        │   └── device/
        │       ├── ONVIFDeviceConfig.java    # Config with IP, Port, User, Pass
        │       ├── ONVIFDeviceExtensionPoint.java  # Registration & validation
        │       └── ONVIFDevice.java          # Device implementation (TODO: ONVIF comm)
        └── resources/com/onvif/driver/gateway/
            └── ONVIFDevice.properties         # i18n translations
```

---

## 🎓 Key Learnings Applied

These critical lessons from the PLC Simulator project are **already implemented** in this skeleton:

✅ **Resource Bundle Registered** - `BundleUtil.get().addBundle()` in `ONVIFModuleHook.startup()`
✅ **i18n Keys Used** - Extension point uses keys, not direct text
✅ **Properties File Created** - All display names in `ONVIFDevice.properties`
✅ **FormFieldType.SECRET** - Password field is masked
✅ **Validation Logic** - IP address, port, credentials validated
✅ **Specific Error Messages** - Validation provides helpful context
✅ **Proper Capitalization** - ONVIF, PTZ, etc. use correct casing
✅ **Lifecycle Hooks** - Startup/shutdown properly structured

---

## 📚 Resources

### ONVIF Resources
- ONVIF Official: https://www.onvif.org/
- ONVIF Specifications: https://www.onvif.org/profiles/
- ONVIF Test Tool: https://www.onvif.org/test-tools/

### Ignition Resources
- SDK Javadoc: https://files.inductiveautomation.com/sdk/javadoc/ignition83/8.3.0/
- FormFieldType enum: `.../FormFieldType.html`
- DeviceExtensionPoint: `.../DeviceExtensionPoint.html`

### Java Libraries
- onvif-java: https://github.com/milg0/onvif-java
- Apache HttpClient: https://hc.apache.org/httpcomponents-client-5.1.x/

---

## ⚠️ Common Pitfalls (Already Avoided)

The skeleton project has **already avoided** these common mistakes:

❌ Forgetting to register resource bundle → ✅ Registered in ModuleHook
❌ Passing direct text to constructor → ✅ Using i18n keys
❌ Generic validation errors → ✅ Specific, helpful messages
❌ Wrong FormFieldType → ✅ SECRET for password, TEXT for IP
❌ Missing properties file → ✅ Created with all keys

---

## 🎯 Success Criteria

The ONVIF driver will be considered complete when:

1. ✅ Module builds successfully (`./gradlew build`)
2. ✅ Module installs in Ignition Gateway without errors
3. ✅ Device appears in "Create New Device" dropdown as "ONVIF Driver"
4. ✅ Configuration form displays correctly with all fields
5. ⏳ Can connect to ONVIF camera using IP, port, username, password
6. ⏳ Successfully authenticates with device
7. ⏳ Creates OPC-UA address space with device data
8. ⏳ Polls device for updates at configured interval
9. ⏳ Handles network disconnects gracefully
10. ⏳ Works with multiple simultaneous ONVIF devices

Items 1-4 are complete (skeleton). Items 5-10 need implementation.

---

## 🤝 Handoff Checklist

- [x] Project structure created
- [x] Gradle build configured
- [x] Java skeleton files created
- [x] Configuration UI defined
- [x] Validation logic implemented
- [x] Resource bundle registered
- [x] Documentation created (README, LEARNINGS, PLAN)
- [x] Git repository initialized
- [x] Initial commit created
- [x] Handoff document created
- [ ] ONVIF protocol implementation (TODO for next agent)
- [ ] OPC-UA address space building (TODO)
- [ ] Polling mechanism (TODO)
- [ ] Testing and validation (TODO)

---

## 📞 Next Steps for Implementation

1. **Read LEARNINGS.md** - Understand critical lessons (30 minutes)
2. **Read PLAN.md** - Understand implementation roadmap (30 minutes)
3. **Research ONVIF** - Study protocol and specifications (2-3 days)
4. **Choose Library** - Decide on onvif-java vs. manual SOAP (1 day)
5. **Start Phase 1** - ONVIF Protocol Foundation (3-5 days)
6. **Continue through phases** - Follow PLAN.md sequentially

---

**Good luck! The foundation is solid. Now it's time to implement the ONVIF protocol communication.**

---

**Git Repository:**
- Location: `/modules/ignition-ONVIF-driver/`
- Branch: `master`
- Commit: `8a23e9b` (Initial commit: ONVIF Driver skeleton project)
- Files: 22 files, 1,988 lines of code/documentation
- Status: Clean, ready for development

**To push to GitHub (when ready):**
```bash
cd /modules/ignition-ONVIF-driver
git remote add origin git@github.com:username/ignition-ONVIF-driver.git
git push -u origin master
```
