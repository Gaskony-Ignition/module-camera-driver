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

    // HTTP client for ONVIF communication
    modlImplementation("org.apache.httpcomponents:httpclient:4.5.14")
    modlImplementation("org.apache.httpcomponents:httpcore:4.4.16")

    // JSON support for configuration
    modlImplementation("com.google.code.gson:gson:2.10.1")

    // Jakarta Servlet API (provided by Ignition 8.3)
    compileOnly("jakarta.servlet:jakarta.servlet-api:5.0.0")
}
