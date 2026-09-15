plugins {
    base
    id("io.ia.sdk.modl") version "0.5.0"
    id("com.github.spotbugs") version "6.4.8" apply false
    id("org.owasp.dependencycheck") version "12.2.0" apply false
}

// ── OWASP Dependency Check ──────────────────────────────────────────────────
apply(plugin = "org.owasp.dependencycheck")
configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    analyzers.assemblyEnabled = false
}

version = "3.3.0"
group = "com.gaskony"

allprojects {
    version = rootProject.version
    group = "com.gaskony"
}

ignitionModule {
    fileName.set("CameraDriver-${project.version}")
    name.set("Camera Driver")
    id.set("com.gaskony.camera.opcua")
    moduleVersion.set(project.version.toString())
    moduleDescription.set("Multi-protocol camera driver supporting ONVIF, RTSP, MJPEG, and snapshot URL connections to IP cameras with bundled go2rtc streaming")
    requiredIgnitionVersion.set("8.3.0")
    freeModule.set(true)

    projectScopes.putAll(mapOf(
        ":gateway" to "G",
        ":designer" to "D",
        ":common" to "GD"
    ))

    hooks.putAll(mapOf(
        "com.gaskony.camera.gateway.CameraModuleHook" to "G",
        "com.gaskony.camera.designer.DesignerHook" to "D"
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

    // Module signing configuration
    // Auto-skips signing when the keystore file does not exist (e.g. in CI without secrets).
    // To sign locally, set ignition.signing.keystoreFile in gradle.properties.
    val keystoreFilePath = (findProperty("ignition.signing.keystoreFile") as? String) ?: ""
    skipModlSigning.set(keystoreFilePath.isBlank() || !file(keystoreFilePath).exists())
}

// ── Static analysis ──────────────────────────────────────────────────────────
subprojects {
    plugins.withType<JavaPlugin> {
        apply(plugin = "checkstyle")
        apply(plugin = "com.github.spotbugs")

        configure<CheckstyleExtension> {
            toolVersion = "10.26.1"
            configFile = rootProject.file("config/checkstyle/checkstyle.xml")
            isIgnoreFailures = true
        }

        configure<com.github.spotbugs.snom.SpotBugsExtension> {
            ignoreFailures.set(false)
            effort.set(com.github.spotbugs.snom.Effort.MAX)
            reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
            excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
        }

        // Disable SpotBugs on test code — enforce only on production sources
        tasks.matching { it.name == "spotbugsTest" }.configureEach {
            enabled = false
        }
    }
}

// ── Version sync ──────────────────────────────────────────────────────────────
tasks.register("syncVersion") {
    group = "versioning"
    description = "Syncs project.version to all files that embed it"
    doLast {
        val ver = project.version.toString()
        fun sync(f: File, pattern: Regex, replacement: String) {
            if (!f.exists()) return
            val text = f.readText()
            val updated = text.replace(pattern, replacement)
            if (updated != text) { f.writeText(updated); logger.lifecycle("  synced ${f.name} → $ver") }
        }
        sync(file("web-ui/package.json"),
            Regex(""""version":\s*"[^"]+""""), """"version": "$ver"""")
        // package-lock.json embeds the project version twice at the top (root + packages[""]).
        // Only replace the first two occurrences so third-party dependency versions are untouched.
        run {
            val f = file("web-ui/package-lock.json")
            if (f.exists()) {
                var remaining = 2
                val text = f.readText()
                val updated = Regex(""""version":\s*"[^"]+"""").replace(text) { m ->
                    if (remaining > 0) { remaining--; """"version": "$ver"""" } else m.value
                }
                if (updated != text) { f.writeText(updated); logger.lifecycle("  synced ${f.name} → $ver") }
            }
        }
        sync(file("README.md"),
            Regex("""(?m)^\*\*Version\*\*:\s*[\d.]+"""), "**Version**: ${ver}")
        sync(file("README.md"),
            Regex("""CameraDriver-[\d.]+\.modl"""), "CameraDriver-${ver}.modl")
        sync(file("CLAUDE.md"),
            Regex("""(?m)^\*\*Version\*\*:\s*[\d.]+"""), "**Version**: ${ver}")
        sync(file("CLAUDE.md"),
            Regex("""(?m)^\*\*Document Version\*\*:\s*[\d.]+"""), "**Document Version**: ${ver}")
        sync(file("CLAUDE.md"),
            Regex("""CameraDriver-[\d.]+\.modl"""), "CameraDriver-${ver}.modl")
        logger.lifecycle("syncVersion: all files set to $ver")
    }
}

tasks.named("assembleModlStructure") {
    dependsOn("syncVersion")
}

// ---------------------------------------------------------------------------
// verifyModulePackaging — assert what is actually INSIDE the built .modl.
//
// Added 31/07/2026 after two packaging defects reached a green build in the
// same afternoon, neither of which any test could have caught:
//   1. The ffmpeg download task silently no-opped (see the note on
//      downloadFfmpeg), producing a 19MB .modl instead of 110MB with snapshot
//      extraction quietly missing.
//   2. Migrating to httpclient5 dragged in a transitive slf4j-api, shipping a
//      competing copy of a logging facade that must never be bundled.
//
// modules/CLAUDE.md already says "verify packaging by unzipping the built
// .modl" — a build file is the honest place for that rule, because a rule
// enforced by remembering is a rule that holds until the day it matters.
// ---------------------------------------------------------------------------
val verifyModulePackaging by tasks.registering {
    description = "Fails the build if the packaged .modl is missing required content or ships a forbidden library"
    group = "verification"
    dependsOn("signModule")

    doLast {
        val modl = layout.buildDirectory.get().asFile
            .listFiles { f -> f.name.endsWith(".modl") && !f.name.endsWith(".unsigned.modl") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("verifyModulePackaging: no signed .modl found in ${layout.buildDirectory.get()}")

        val entries = mutableListOf<String>()
        java.util.zip.ZipFile(modl).use { zip ->
            zip.entries().asSequence().forEach { e ->
                entries += e.name
                // Binaries live inside gateway-<version>.jar, so look one level in.
                if (e.name.startsWith("gateway-") && e.name.endsWith(".jar")) {
                    java.util.zip.ZipInputStream(zip.getInputStream(e)).use { inner ->
                        generateSequence { inner.nextEntry }.forEach { entries += "gateway.jar!/" + it.name }
                    }
                }
            }
        }

        val problems = mutableListOf<String>()

        // Required: our own shipped HTTP stack.
        listOf("httpclient5-", "httpcore5-").forEach { prefix ->
            if (entries.none { it.startsWith(prefix) && it.endsWith(".jar") }) {
                problems += "missing required jar starting '$prefix'"
            }
        }

        // Required: the bundled streaming binaries. D7-style self-containment —
        // the module must work air-gapped, so a missing binary is a broken release.
        listOf(
            "gateway.jar!/go2rtc/go2rtc_linux_amd64",
            "gateway.jar!/go2rtc/go2rtc_linux_arm64",
            "gateway.jar!/go2rtc/go2rtc_windows_amd64.exe",
            "gateway.jar!/ffmpeg/ffmpeg_linux_amd64",
            "gateway.jar!/ffmpeg/ffmpeg_linux_arm64",
            "gateway.jar!/ffmpeg/ffmpeg_windows_amd64.exe",
        ).forEach { required ->
            if (entries.none { it == required }) problems += "missing bundled binary: $required"
        }

        // Forbidden: boundary libraries the platform must provide. Shipping our
        // own copy of these is the failure mode modules/CLAUDE.md exists to stop.
        listOf("slf4j-api-", "slf4j-simple-", "jetty-", "jakarta.servlet-", "httpclient-4", "httpcore-4")
            .forEach { prefix ->
                entries.filter { it.startsWith(prefix) && it.endsWith(".jar") }
                    .forEach { problems += "FORBIDDEN jar shipped in .modl: $it" }
            }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "verifyModulePackaging FAILED for ${modl.name}:\n  " + problems.joinToString("\n  ") +
                    "\n\nA green build is not proof of a correct package — see the notes on " +
                    "downloadFfmpeg and the httpclient5 slf4j exclusion in gateway/build.gradle.kts."
            )
        }
        logger.lifecycle("verifyModulePackaging: ${modl.name} OK (${entries.size} entries checked)")
    }
}

tasks.named("build") {
    dependsOn(verifyModulePackaging)
}
