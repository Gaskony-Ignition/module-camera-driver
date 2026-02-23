plugins {
    base
    id("io.ia.sdk.modl") version "0.4.0"
    id("com.github.spotbugs") version "6.0.27" apply false
}

version = "2.28.0"
group = "com.onvif.driver"

ignitionModule {
    fileName.set("CameraDriver-${project.version}")
    name.set("Camera Driver")
    id.set("com.onvif.driver.opcua")
    moduleVersion.set(project.version.toString())
    license.set("license.html")
    moduleDescription.set("Multi-protocol camera driver supporting ONVIF, RTSP, MJPEG, and snapshot URL connections to IP cameras with bundled go2rtc streaming")
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
        register("com.inductiveautomation.perspective") {
            scope = "GD"
            required = false
        }
    }

    // Module signing enabled (configured in gradle.properties)
    skipModlSigning.set(false)
}

// ── Static analysis ──────────────────────────────────────────────────────────
subprojects {
    afterEvaluate {
        if (plugins.hasPlugin("java") || plugins.hasPlugin("java-library")) {
            apply(plugin = "checkstyle")
            apply(plugin = "com.github.spotbugs")

            configure<CheckstyleExtension> {
                toolVersion = "10.21.4"
                configFile = rootProject.file("config/checkstyle/checkstyle.xml")
                isIgnoreFailures = true
            }

            configure<com.github.spotbugs.snom.SpotBugsExtension> {
                ignoreFailures.set(true)
                effort.set(com.github.spotbugs.snom.Effort.MAX)
                reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
            }
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
        sync(file("license.html"),
            Regex("""(?<=<strong>Version:</strong> )[0-9.]+"""), ver)
        logger.lifecycle("syncVersion: all files set to $ver")
    }
}
