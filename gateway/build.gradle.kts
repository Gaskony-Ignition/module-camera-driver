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

    // HTTP client for ONVIF communication.
    //
    // slf4j-api is EXCLUDED deliberately. httpclient5 declares it as a compile
    // dependency, so without this it gets dragged into the .modl (verified: the
    // first 3.3.0 build shipped slf4j-api-1.7.36.jar, which HttpClient 4 never
    // pulled in). SLF4J is a boundary library under modules/CLAUDE.md and must
    // NEVER be shipped: it is a logging FACADE whose ServiceLoader binding has to
    // resolve to the gateway's own logging backend. A second copy on the module
    // classloader risks binding to nothing, which silently sends this module's
    // logs into a void — the worst possible failure for a driver, because the
    // symptom is "no errors reported" rather than an error.
    // The platform provides slf4j-api at runtime (lib/core/common 2.0.12,
    // lib/core/gateway 1.7.36) and ignition-common supplies it at compile time.
    modlImplementation(libs.httpclient5) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    modlImplementation(libs.httpcore5) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    // JSON support for API key persistence (ApiKeyStore). Shipped via modlImplementation
    // at latest stable rather than pinned to the platform's bundled 2.8.9 copy: the only
    // Gson usage in this module (ApiKeyStore) stays fully internal (String in/out to a
    // file; no Gson type ever crosses an Ignition SDK API boundary), so the module
    // classloader's own newer copy is safe to ship and load — see modules/CLAUDE.md
    // "compileOnly dependency versions" note for the reasoning and precedent
    // (ignition-module-git ships its own newer sqlite-jdbc/Jackson the same way).
    modlImplementation(libs.gson)

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
    // Gradle 8.10.2 bundles a junit-platform-launcher too old for Jupiter >=5.12
    // ("OutputDirectoryCreator not available; probably due to unaligned versions")
    // — pin an explicit matching launcher version.
    testRuntimeOnly(libs.junit.platform.launcher)

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

    // See the full note on downloadFfmpeg: with no declared INPUTS, Gradle's
    // up-to-date check compares only the output snapshot, so output left behind
    // by a failed (warn-only) download matches forever and the task never
    // retries. Name every expected file — a PARTIAL download is the normal
    // failure mode, so "the directory has something in it" is the wrong test.
    outputs.upToDateWhen {
        val dir = go2rtcOutputDir.get().asFile
        listOf("go2rtc_linux_amd64", "go2rtc_linux_arm64", "go2rtc_windows_amd64.exe")
            .all { File(dir, it).exists() }
    }

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
                    "maxtime" to "1800"
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
//
// DOWNLOAD TIMEOUT (31/07/2026): every ant `get` here uses maxtime=1800, raised
// from the original 300. That 300s ceiling was not a safety margin, it was a
// silent size limit: the arm64 tarball (~51MB) finished inside it, while the
// amd64 (~79MB) and Windows (~101MB) archives did not, so those two ALWAYS
// failed on a normal connection and were then swallowed by the warn-only catch
// below. The release that did include them simply caught a faster moment on the
// network. Combined with the up-to-date bug noted on the task itself, that is
// how a build reached "SUCCESSFUL" while shipping a module with no ffmpeg at
// all. Prefer a long timeout that occasionally waits over a short one that
// quietly truncates the artefact.
val ffmpegOutputDir = layout.buildDirectory.dir("ffmpeg-binaries/ffmpeg")

val downloadFfmpeg by tasks.registering {
    description = "Downloads static ffmpeg binaries for bundling in the module"
    group = "build"

    outputs.dir(ffmpegOutputDir)

    // WHY THIS LINE EXISTS (31/07/2026 — it cost a silently-broken release build):
    // this task declares outputs but NO inputs, so Gradle's up-to-date check has
    // nothing to compare except the output snapshot. Every download here is
    // wrapped in a try/catch that only WARNS on failure (deliberate — a network
    // blip shouldn't fail the build), so a failed download leaves the output dir
    // empty and Gradle snapshots "empty". On every later build "empty" still
    // matches "empty", the task reports UP-TO-DATE, and it never retries — not
    // even after `clean`, because the snapshot lives in .gradle/, not build/.
    //
    // The result was a BUILD SUCCESSFUL that produced a 19MB .modl instead of
    // 110MB, with ffmpeg (and therefore snapshot extraction) silently missing.
    // One transient network failure poisoned every subsequent build.
    //
    // The condition must name EVERY expected file, not just "the directory is
    // non-empty" — the first version of this fix checked only for non-emptiness
    // and was satisfied by a single arm64 binary while amd64 and windows were
    // both still missing, so the task kept reporting UP-TO-DATE and kept not
    // retrying them. A partial download is the normal failure here (these are
    // 50-100MB files), so "some output exists" is precisely the wrong test.
    outputs.upToDateWhen {
        val dir = ffmpegOutputDir.get().asFile
        listOf("ffmpeg_linux_amd64", "ffmpeg_linux_arm64", "ffmpeg_windows_amd64.exe")
            .all { File(dir, it).exists() }
    }

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
                    "maxtime" to "1800"
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
                    "maxtime" to "1800"
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
                    "maxtime" to "1800"
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
