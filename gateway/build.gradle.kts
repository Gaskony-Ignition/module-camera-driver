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
    compileOnly(libs.ignition.driver.api)

    // ONVIF Java library for connecting to ONVIF devices
    // Note: You may need to add onvif-java or implement SOAP client manually
    modlImplementation("com.google.code.gson:gson:2.10.1")
}
