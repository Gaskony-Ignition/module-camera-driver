plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Output path for generated resources
val projectOutput: String by extra("${layout.buildDirectory.get().asFile}/generated-resources/")

/**
 * Check if npm/node is available on the system.
 */
fun isNpmAvailable(): Boolean {
    return try {
        val process = ProcessBuilder("npm", "--version").start()
        process.waitFor() == 0
    } catch (e: Exception) {
        false
    }
}

/**
 * Task: Install npm dependencies.
 * Only runs if node_modules doesn't exist.
 */
val npmInstall by tasks.registering(Exec::class) {
    group = "build"
    description = "Install npm dependencies"

    workingDir = project.projectDir
    commandLine = if (System.getProperty("os.name").lowercase().contains("windows")) {
        listOf("cmd", "/c", "npm", "install")
    } else {
        listOf("npm", "install")
    }

    inputs.files(
        fileTree(project.projectDir).matching {
            include("**/package.json", "**/package-lock.json")
        }
    )
    outputs.dirs(file("node_modules"))

    onlyIf {
        !file("${project.projectDir}/node_modules").exists() && isNpmAvailable()
    }

    doFirst {
        if (!isNpmAvailable()) {
            throw GradleException("npm is not available. Please install Node.js and npm to build the web UI.")
        }
        logger.lifecycle("Installing npm dependencies...")
    }
}

/**
 * Task: Build React component with webpack.
 */
val webpack by tasks.registering(Exec::class) {
    group = "Ignition Module"
    description = "Build React component with webpack"

    workingDir = project.projectDir
    commandLine = if (System.getProperty("os.name").lowercase().contains("windows")) {
        listOf("cmd", "/c", "npm", "run", "build")
    } else {
        listOf("npm", "run", "build")
    }

    dependsOn(npmInstall)

    inputs.files(project.fileTree(project.projectDir).matching {
        exclude("**/node_modules/**", "**/dist/**", "**/build/**")
    }.toList())
    outputs.files(fileTree(projectOutput))

    onlyIf { isNpmAvailable() }
}

/**
 * Task: Run Vitest frontend unit tests.
 */
val frontendTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run Vitest frontend unit tests"

    workingDir = project.projectDir
    commandLine = if (System.getProperty("os.name").lowercase().contains("windows")) {
        listOf("cmd", "/c", "npm", "test")
    } else {
        listOf("npm", "test")
    }

    dependsOn(npmInstall)
    onlyIf { isNpmAvailable() }
}

tasks {
    processResources {
        dependsOn(webpack, npmInstall)
    }

    clean {
        delete(file("build"))
    }
}

val deepClean by tasks.registering {
    doLast {
        delete(file(".gradle"))
        delete(file("node_modules"))
    }

    dependsOn(project.tasks.named("clean"))
}

// Make gateway processResources wait for webpack
project(":gateway")?.tasks?.named("processResources")?.configure {
    dependsOn(webpack)
}

sourceSets {
    main {
        output.dir(projectOutput, "builtBy" to listOf(webpack))
    }
}
