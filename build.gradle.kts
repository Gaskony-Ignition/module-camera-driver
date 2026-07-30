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

version = "3.2.0"
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
