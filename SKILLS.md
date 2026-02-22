# Camera Driver Module - Skills & Knowledge Base

This document captures critical lessons learned during the development of the Camera Driver module. **Read this carefully before making changes to avoid repeating past mistakes.**

---

## 🔴 CRITICAL BUG #1: Display Names Showing as Question Marks

### The Problem
Display names and descriptions appeared as:
```
¿com.gaskony.plcsimulator.EnhancedSimulator?
¿com.gaskony.plcsimulator.EnhancedSimulator?
```

### Root Causes
1. **Resource bundle was created but NEVER REGISTERED**
   - Created `EnhancedSimulator.properties` file ✅
   - **FORGOT** to register it with `BundleUtil` ❌

2. **Constructor parameters misunderstood**
   - `DeviceExtensionPoint` constructor takes **i18n resource bundle KEYS**, not direct text
   - If you pass direct text, Ignition treats it as a key and shows `¿text?` when not found

3. **Override methods don't work**
   - Adding `getDisplayName()` and `getDescription()` override methods **does nothing**
   - Ignition uses `BundleUtil` for i18n, not method overrides

### The Fix

**Step 1: Register Resource Bundle in ModuleHook**

```java
import com.inductiveautomation.ignition.common.BundleUtil;

@Override
public void startup(LicenseState licenseState) {
    // CRITICAL: Register resource bundle
    BundleUtil.get().addBundle(
        "ONVIFDevice",                        // Bundle name
        ONVIFDeviceExtensionPoint.class,      // Class for package path
        "ONVIFDevice"                         // Properties file name (without .properties)
    );
    logger.info("Registered ONVIFDevice resource bundle");
}
```

**Step 2: Use i18n Keys in Constructor**

```java
public ONVIFDeviceExtensionPoint() {
    super(
        TYPE_ID,
        "ONVIFDevice.Meta.DisplayName",      // i18n key, NOT direct text!
        "ONVIFDevice.Meta.Description",      // i18n key, NOT direct text!
        ONVIFDeviceConfig.class
    );
}
```

**Step 3: Create Properties File**

File: `gateway/src/main/resources/com/onvif/driver/gateway/device/ONVIFDevice.properties`

```properties
ONVIFDevice.Meta.DisplayName=Camera Driver
ONVIFDevice.Meta.Description=Connect to ONVIF-compatible IP cameras and devices
```

**⚠️ CRITICAL: All three steps are required. Missing any one will cause the bug.**

---

## 🔴 CRITICAL BUG #2: FormFieldType Confusion

### The Problem
Used `FormFieldType.FILE` expecting a file browser dialog, but behavior varied.

### Available FormFieldType Options

```java
public enum FormFieldType {
    TEXT,        // Simple text input
    TEXTAREA,    // Multi-line text area
    NUMBER,      // Numeric input
    SELECT,      // Dropdown (requires enum)
    REFERENCE,   // Reference to another object
    CHECKBOX,    // Boolean checkbox
    RADIO,       // Radio buttons
    DATETIME,    // Date/time picker
    FILE,        // FILE UPLOAD button (not file browser!)
    SECRET,      // Password field (masked)
    VALUE_TABLE  // Table of values
}
```

### Common Misunderstandings

1. **`FormFieldType.FILE` is for UPLOAD, not browsing**
   - Creates an upload button
   - User selects file from their LOCAL machine
   - File content is uploaded to Gateway
   - Perfect for Docker environments ✅

2. **No way to browse Gateway filesystem**
   - Can't browse server-side files with a dialog
   - Use `TEXT` field and have users type paths
   - Or use `FILE` to upload from client

3. **`SELECT` requires an enum**
   - Must define enum in Config record
   - Enum `displayName` appears in dropdown
   - Don't forget to update `.properties` file with enum values!

---

## 🔴 CRITICAL BUG #3: Capitalization Inconsistencies

### The Problem
Claimed parser types were capitalized correctly, but they weren't.

### The Lesson
**VERIFY YOUR CHANGES!** Don't trust memory. Check the actual code.

### Correct Capitalization Rules

1. **Acronyms: ALL CAPS**
   - JSON (not Json)
   - L5K (not L5k or l5k)
   - TIA (not Tia or tia)
   - OPC-UA (not Opc-Ua)
   - ONVIF (not Onvif)

2. **Product Names: Official Capitalization**
   - TwinCAT (not Twincat or TWINCAT)
   - Allen-Bradley (not allen-bradley)

3. **Apply to BOTH**:
   - Java enum display names
   - Properties file values

```java
// Enum in Config
public enum ServiceType {
    DEVICE("device", "Device Management"),  // ✅ Correct
    MEDIA("media", "Media Service"),        // ✅ Correct
    PTZ("ptz", "PTZ Control"),              // ✅ PTZ is acronym
}
```

```properties
# Properties file
DEVICE=Device Management  # ✅ Correct
MEDIA=Media Service      # ✅ Correct
PTZ=PTZ Control          # ✅ PTZ is acronym
```

---

## 🔴 BUG #4: Validation Errors Not Clear

### The Problem
Validation errors were too generic.

### Best Practices

```java
@Override
protected void validate(ONVIFDeviceConfig config, Builder errors) {
    // ❌ BAD: Generic error
    if (config.connection().port() < 1) {
        errors.check(false, "Invalid port");
    }

    // ✅ GOOD: Specific error with context
    if (config.connection().port() < 1 || config.connection().port() > 65535) {
        errors.check(false, "Port must be between 1 and 65535");
    }

    // ✅ EXCELLENT: Include the invalid value
    String ipAddress = config.connection().ipAddress();
    if (!isValidIp(ipAddress)) {
        errors.check(false, "Invalid IP address format: " + ipAddress);
    }
}
```

---

## 📚 Module Structure Best Practices

### 1. Directory Structure

```
camera-driver/
├── build.gradle.kts              # Root build file with ignitionModule config
├── settings.gradle.kts           # Subproject includes
├── gradle.properties             # Module signing, versions
├── gradle/
│   ├── wrapper/                  # Gradle wrapper files
│   └── libs.versions.toml        # Dependency versions
├── common/
│   ├── build.gradle.kts
│   └── src/main/java/            # Shared code
├── designer/
│   ├── build.gradle.kts
│   └── src/main/java/            # Designer-side code
└── gateway/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── java/
        │   │   └── com/onvif/driver/gateway/
        │   │       ├── ONVIFModuleHook.java           # Module entry point
        │   │       └── device/
        │   │           ├── ONVIFDeviceConfig.java     # Configuration records
        │   │           ├── ONVIFDeviceExtensionPoint.java  # Registration
        │   │           └── ONVIFDevice.java           # Device implementation
        │   └── resources/
        │       └── com/onvif/driver/gateway/
        │           └── ONVIFDevice.properties         # i18n translations
```

### 2. Module Hook Responsibilities

```java
public class ONVIFModuleHook extends AbstractDeviceModuleHook {
    @Override
    public void startup(LicenseState licenseState) {
        // 1. ALWAYS register resource bundles FIRST
        BundleUtil.get().addBundle(...);

        // 2. Then initialize services
        // 3. Then log success
    }

    @Override
    protected List<DeviceExtensionPoint<?>> getDeviceExtensionPoints() {
        // Return list of device types this module provides
        return List.of(new ONVIFDeviceExtensionPoint());
    }
}
```

### 3. Config Record Pattern

```java
public record DeviceConfig(Category1 cat1, Category2 cat2) {

    public record Category1(
        @FormCategory("CATEGORY")    // Groups fields in UI
        @Label("Field Label")         // Display label
        @FormField(FormFieldType.TEXT)  // Field type
        @Description("Help text")     // Tooltip/help
        @Required                     // Validation
        @DefaultValue("default")      // Default value
        String fieldName
    ) {}

    // Enums for SELECT fields
    public enum MyEnum {
        OPTION1("key1", "Display Name 1"),
        OPTION2("key2", "Display Name 2");

        private final String key;
        private final String displayName;
        // ... getters
    }
}
```

---

## 🚨 Common Pitfalls to Avoid

### 1. **Don't claim you fixed something without verifying**
   - Use agents to investigate root causes
   - Read the actual code, don't trust memory
   - Test changes before claiming success

### 2. **Don't forget resource bundle registration**
   - Creating `.properties` file is not enough
   - Must call `BundleUtil.get().addBundle()` in `ModuleHook.startup()`

### 3. **Don't pass direct text to DeviceExtensionPoint constructor**
   - Parameters 2 and 3 are i18n keys
   - Create corresponding entries in `.properties` file

### 4. **Don't use FormFieldType.FILE thinking it's a file browser**
   - It's a file upload button
   - Perfect for Docker, but not for browsing server filesystem

### 5. **Don't forget to increment version before building**
   - Update `version` in `build.gradle.kts`
   - Always commit with descriptive message
   - Always push to GitHub after building

### 6. **Don't skip validation**
   - Validate ALL required fields
   - Provide specific error messages
   - Check ranges, formats, and logic

---

## ✅ Checklist Before Building

- [ ] Resource bundle registered in `ModuleHook.startup()`
- [ ] Extension point uses i18n keys (not direct text)
- [ ] Properties file created with all keys
- [ ] All enum display names in properties file
- [ ] Capitalization correct (acronyms, product names)
- [ ] FormFieldType appropriate for each field
- [ ] Validation complete with specific errors
- [ ] Version incremented in `build.gradle.kts`
- [ ] Code compiles (`./gradlew clean build`)
- [ ] Ready to commit to git

---

## 📖 References

- **Ignition SDK Javadoc**: https://files.inductiveautomation.com/sdk/javadoc/ignition83/8.3.0/
- **FormFieldType enum**: `.../com/inductiveautomation/ignition/gateway/web/nav/FormFieldType.html`
- **DeviceExtensionPoint**: `.../com/inductiveautomation/ignition/gateway/opcua/server/api/DeviceExtensionPoint.html`
- **BundleUtil**: `.../com/inductiveautomation/ignition/common/BundleUtil.html`

---

**🎯 Bottom Line: Read this file BEFORE implementing the Camera Driver. These lessons were learned the hard way.**
