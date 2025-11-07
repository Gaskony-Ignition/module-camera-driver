plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(projects.common)
    compileOnly(libs.ignition.common)
    compileOnly(libs.ignition.gateway.api)

    // Import driver-api platform (BOM that manages versions)
    compileOnly(platform(libs.ignition.driver.api))

    // OPC-UA device driver API - provided by OPC-UA module at runtime
    compileOnly("com.inductiveautomation.opcua:opc-ua-gateway-api:10.3.0")

    // ONVIF Java library for connecting to ONVIF devices
    // Note: You may need to add onvif-java or implement SOAP client manually
    modlImplementation("com.google.code.gson:gson:2.10.1")
}
