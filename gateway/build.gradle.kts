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
    compileOnly(libs.perspective.common)
    compileOnly(libs.perspective.gateway)

    // HTTP client for ONVIF communication
    modlImplementation("org.apache.httpcomponents:httpclient:4.5.14")
    modlImplementation("org.apache.httpcomponents:httpcore:4.4.16")

    // JSON support for configuration
    modlImplementation("com.google.code.gson:gson:2.11.0")

    // Jakarta Servlet API (provided by Ignition 8.3)
    compileOnly("jakarta.servlet:jakarta.servlet-api:5.0.0")

    // Include web-ui component bundle
    modlImplementation(projects.webUi)

    // Make Ignition gateway + driver APIs available during test compilation and runtime.
    // Required because mocked classes (ONVIFDeviceExtensionPoint, GenericCameraExtensionPoint)
    // extend DeviceExtensionPoint from driver-api, and Mockito resolves the full class hierarchy
    // at test runtime even when the mock value is never dereferenced.
    testCompileOnly(libs.ignition.gateway.api)
    testRuntimeOnly(libs.ignition.gateway.api)
    testCompileOnly(libs.ignition.driver.api)
    testRuntimeOnly(libs.ignition.driver.api)

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

// go2rtc binary download task
// Downloads platform-specific go2rtc binaries into build directory for bundling.
// If binaries are absent, go2rtc simply won't be available at runtime
// and streaming falls back gracefully.
//
// Usage:
//   ./gradlew build                   # Build without go2rtc (fallback mode)
//   ./gradlew downloadGo2Rtc build    # Full build with go2rtc binaries
//   -x downloadGo2Rtc                 # Explicitly skip download
val go2rtcVersion = "1.9.4"
val go2rtcOutputDir = layout.buildDirectory.dir("go2rtc-binaries/go2rtc")

val downloadGo2Rtc by tasks.registering {
    description = "Downloads go2rtc binaries for bundling in the module"
    group = "build"

    outputs.dir(go2rtcOutputDir)

    doLast {
        val outputDir = go2rtcOutputDir.get().asFile
        outputDir.mkdirs()

        // Linux binaries are direct downloads
        val linuxBinaries = mapOf(
            "go2rtc_linux_amd64" to "https://github.com/AlexxIT/go2rtc/releases/download/v${go2rtcVersion}/go2rtc_linux_amd64",
            "go2rtc_linux_arm64" to "https://github.com/AlexxIT/go2rtc/releases/download/v${go2rtcVersion}/go2rtc_linux_arm64"
        )

        for ((fileName, url) in linuxBinaries) {
            val outputFile = File(outputDir, fileName)
            if (!outputFile.exists()) {
                logger.lifecycle("Downloading go2rtc binary: $fileName")
                try {
                    ant.invokeMethod("get", mapOf("src" to url, "dest" to outputFile, "skipexisting" to "true"))
                } catch (e: Exception) {
                    logger.warn("Failed to download $fileName: ${e.message}. go2rtc will not be available for this platform.")
                }
            }
        }

        // Windows binary is distributed as a ZIP archive - download and extract
        val windowsExeFile = File(outputDir, "go2rtc_windows_amd64.exe")
        if (!windowsExeFile.exists()) {
            logger.lifecycle("Downloading go2rtc binary: go2rtc_windows_amd64.exe")
            val windowsZipFile = File(outputDir, "go2rtc_win64.zip")
            try {
                ant.invokeMethod("get", mapOf(
                    "src" to "https://github.com/AlexxIT/go2rtc/releases/download/v${go2rtcVersion}/go2rtc_win64.zip",
                    "dest" to windowsZipFile,
                    "skipexisting" to "true"
                ))
                ant.invokeMethod("unzip", mapOf("src" to windowsZipFile, "dest" to outputDir))
                File(outputDir, "go2rtc.exe").renameTo(windowsExeFile)
                windowsZipFile.delete()
            } catch (e: Exception) {
                logger.warn("Failed to download/extract Windows go2rtc: ${e.message}. go2rtc will not be available on Windows.")
                windowsZipFile.delete()
            }
        }
    }
}

// Add go2rtc binaries to resources when the download task runs
sourceSets {
    main {
        resources {
            // go2rtc binaries are placed in build/go2rtc-binaries/go2rtc/ by downloadGo2Rtc
            // They end up at classpath: go2rtc/go2rtc_linux_amd64 etc.
            srcDir(layout.buildDirectory.dir("go2rtc-binaries"))

            // Include web-ui webpack output (perspective.js, connectionBrowser.js) in gateway.jar
            // so they're served via getMountedResourceFolder() at /res/camera-driver/*
            srcDir(project(":web-ui").layout.buildDirectory.dir("generated-resources"))
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(downloadGo2Rtc)
    // Inject the Gradle project version into module.properties at build time.
    // Single source of truth: build.gradle.kts → module.properties → ONVIFModuleHook
    filesMatching("module.properties") {
        expand(mapOf("moduleVersion" to project.version))
    }
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
