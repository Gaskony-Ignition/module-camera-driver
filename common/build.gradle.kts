plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly(libs.ignition.common)
    compileOnly(libs.ignition.gateway.api)
    compileOnly(libs.perspective.common)

    // Test dependencies
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // Pin a launcher that matches Jupiter rather than relying on the one Gradle
    // supplies (Gradle 8.10.2's was too old for Jupiter >=5.12: "OutputDirectoryCreator
    // not available; probably due to unaligned versions").
    testRuntimeOnly(libs.junit.platform.launcher)

    // AssertJ for fluent assertions
    testImplementation(libs.assertj.core)
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
