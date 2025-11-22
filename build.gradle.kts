plugins {
    base
    id("io.ia.sdk.modl") version "0.4.0"
}

version = "2.1.0"
group = "com.onvif.driver"

ignitionModule {
    fileName.set("ONVIFDriver-${project.version}")
    name.set("ONVIF Driver")
    id.set("com.onvif.driver.opcua")
    moduleVersion.set(project.version.toString())
    license.set("license.html")
    moduleDescription.set("ONVIF network device driver for connecting to IP cameras and devices supporting ONVIF protocol")
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
    }

    // Module signing enabled (configured in gradle.properties)
    skipModlSigning.set(false)
}
