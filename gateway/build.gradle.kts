plugins {
    `java-library`
    jacoco
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
    modlImplementation(libs.httpclient)
    modlImplementation(libs.httpcore)

    // JSON support for configuration (provided by Ignition at runtime)
    compileOnly(libs.gson)

    // Jakarta Servlet API (provided by Ignition 8.3)
    compileOnly(libs.jakarta.servlet)

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

    // Test dependencies
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)

    // Mockito for mocking
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)

    // AssertJ for fluent assertions
    testImplementation(libs.assertj.core)

    // SLF4J for test logging
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
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
                    "skipexisting" to "true",
                    "maxtime" to "300"
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

// ffmpeg static binary download task
// Downloads platform-specific static ffmpeg binaries for bundling.
// Required by go2rtc for JPEG snapshot extraction and MJPEG transcoding.
val ffmpegOutputDir = layout.buildDirectory.dir("ffmpeg-binaries/ffmpeg")

val downloadFfmpeg by tasks.registering {
    description = "Downloads static ffmpeg binaries for bundling in the module"
    group = "build"

    outputs.dir(ffmpegOutputDir)

    doLast {
        val outputDir = ffmpegOutputDir.get().asFile
        outputDir.mkdirs()

        // Linux amd64 - download from johnvansickle.com static builds
        val linuxAmd64File = File(outputDir, "ffmpeg_linux_amd64")
        if (!linuxAmd64File.exists()) {
            logger.lifecycle("Downloading static ffmpeg binary: linux amd64")
            val tarFile = File(outputDir, "ffmpeg-amd64.tar.xz")
            try {
                ant.invokeMethod("get", mapOf(
                    "src" to "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz",
                    "dest" to tarFile,
                    "skipexisting" to "true",
                    "maxtime" to "300"
                ))
                // Extract just the ffmpeg binary from the tarball
                exec {
                    commandLine("bash", "-c",
                        "tar -xf '${tarFile.absolutePath}' -C '${outputDir.absolutePath}' --wildcards '*/ffmpeg' --strip-components=1")
                }
                File(outputDir, "ffmpeg").renameTo(linuxAmd64File)
                tarFile.delete()
            } catch (e: Exception) {
                logger.warn("Failed to download linux amd64 ffmpeg: ${e.message}. Snapshot extraction will not be available.")
                tarFile.delete()
            }
        }

        // Linux arm64
        val linuxArm64File = File(outputDir, "ffmpeg_linux_arm64")
        if (!linuxArm64File.exists()) {
            logger.lifecycle("Downloading static ffmpeg binary: linux arm64")
            val tarFile = File(outputDir, "ffmpeg-arm64.tar.xz")
            try {
                ant.invokeMethod("get", mapOf(
                    "src" to "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz",
                    "dest" to tarFile,
                    "skipexisting" to "true",
                    "maxtime" to "300"
                ))
                exec {
                    commandLine("bash", "-c",
                        "tar -xf '${tarFile.absolutePath}' -C '${outputDir.absolutePath}' --wildcards '*/ffmpeg' --strip-components=1")
                }
                File(outputDir, "ffmpeg").renameTo(linuxArm64File)
                tarFile.delete()
            } catch (e: Exception) {
                logger.warn("Failed to download linux arm64 ffmpeg: ${e.message}. Snapshot extraction will not be available.")
                tarFile.delete()
            }
        }

        // Windows amd64
        val windowsFile = File(outputDir, "ffmpeg_windows_amd64.exe")
        if (!windowsFile.exists()) {
            logger.lifecycle("Downloading static ffmpeg binary: windows amd64")
            val zipFile = File(outputDir, "ffmpeg-win64.zip")
            try {
                ant.invokeMethod("get", mapOf(
                    "src" to "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
                    "dest" to zipFile,
                    "skipexisting" to "true",
                    "maxtime" to "300"
                ))
                ant.invokeMethod("unzip", mapOf("src" to zipFile, "dest" to outputDir))
                // Find the extracted ffmpeg.exe (nested in a version-named folder)
                val extracted = outputDir.walkTopDown().find { it.name == "ffmpeg.exe" && it.parentFile.name == "bin" }
                if (extracted != null) {
                    extracted.renameTo(windowsFile)
                } else {
                    logger.warn("ffmpeg.exe not found in extracted ZIP")
                }
                // Clean up extracted folders and zip
                outputDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
                zipFile.delete()
            } catch (e: Exception) {
                logger.warn("Failed to download windows ffmpeg: ${e.message}. Snapshot extraction will not be available on Windows.")
                zipFile.delete()
            }
        }
    }
}

// Add go2rtc and ffmpeg binaries to resources when download tasks run
sourceSets {
    main {
        resources {
            // go2rtc binaries are placed in build/go2rtc-binaries/go2rtc/ by downloadGo2Rtc
            // They end up at classpath: go2rtc/go2rtc_linux_amd64 etc.
            srcDir(layout.buildDirectory.dir("go2rtc-binaries"))

            // ffmpeg binaries are placed in build/ffmpeg-binaries/ffmpeg/ by downloadFfmpeg
            // They end up at classpath: ffmpeg/ffmpeg_linux_amd64 etc.
            srcDir(layout.buildDirectory.dir("ffmpeg-binaries"))

            // Include web-ui webpack output (perspective.js, connectionBrowser.js) in gateway.jar
            // so they're served via getMountedResourceFolder() at /res/camera-driver/*
            srcDir(project(":web-ui").layout.buildDirectory.dir("generated-resources"))
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(downloadGo2Rtc, downloadFfmpeg)
    // Inject the Gradle project version into module.properties at build time.
    // Single source of truth: build.gradle.kts → module.properties → CameraModuleHook
    filesMatching("module.properties") {
        expand(mapOf("moduleVersion" to rootProject.version))
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                // Coverage floor — set to current measured value minus 1% so any
                // regression beyond that 1% headroom fails the build. Real instruction
                // coverage (per persisted JaCoCo XML at the time of writing) is ~15%.
                minimum = "0.14".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
