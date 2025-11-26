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
    modlImplementation("com.google.code.gson:gson:2.11.0")

    // Jakarta Servlet API (provided by Ignition 8.3)
    compileOnly("jakarta.servlet:jakarta.servlet-api:5.0.0")

    // Include web-ui component bundle
    modlImplementation(projects.webUi)

    // Test dependencies (v2.1.0)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")

    // Mockito for mocking
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.8.0")

    // AssertJ for fluent assertions
    testImplementation("org.assertj:assertj-core:3.25.1")

    // SLF4J for test logging
    testImplementation("org.slf4j:slf4j-api:2.0.9")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.9")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
