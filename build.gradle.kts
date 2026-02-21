plugins {
    base
    id("io.ia.sdk.modl") version "0.4.0"
}

version = "2.11.1"
group = "com.onvif.driver"

ignitionModule {
    fileName.set("CameraDriver-${project.version}")
    name.set("Camera Driver")
    id.set("com.onvif.driver.opcua")
    moduleVersion.set(project.version.toString())
    license.set("license.html")
    moduleDescription.set("Multi-protocol camera driver supporting ONVIF, RTSP, MJPEG, and snapshot URL connections to IP cameras with bundled go2rtc streaming")
    requiredIgnitionVersion.set("8.3.0")
    freeModule.set(true)

    projectScopes.putAll(mapOf(
        ":gateway" to "G",
        ":designer" to "D",
        ":common" to "GD"
    ))

    hooks.putAll(mapOf(
        "com.onvif.driver.gateway.ONVIFModuleHook" to "G",
        "com.onvif.driver.designer.DesignerHook" to "D"
    ))

    // Declare dependency on OPC-UA module for device driver APIs (Ignition 8.3+ format)
    moduleDependencySpecs {
        register("com.inductiveautomation.opcua") {
            scope = "G"
            required = true
        }
        register("com.inductiveautomation.perspective") {
            scope = "GD"
            required = false
        }
    }

    // Module signing enabled (configured in gradle.properties)
    skipModlSigning.set(false)
}
