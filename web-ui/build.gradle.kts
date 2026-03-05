import com.github.gradle.node.yarn.task.YarnTask
import com.github.gradle.node.npm.task.NpmTask

plugins {
    java
    id("com.github.node-gradle.node") version("7.0.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Output path for generated resources
val projectOutput: String by extra("$buildDir/generated-resources/")

// Node.js configuration
node {
    version.set("20.18.0")
    yarnVersion.set("1.22.18")
    npmVersion.set("8.5.5")
    download.set(true)
    nodeProjectDir.set(file(project.projectDir))
}

// Install npm dependencies using yarn
val yarnPackages by tasks.registering(YarnTask::class) {
    description = "Install npm dependencies"
    args.set(listOf("install", "--verbose"))

    inputs.files(
        fileTree(project.projectDir).matching {
            include("**/package.json", "**/yarn.lock")
        }
    )

    outputs.dirs(file("node_modules"))

    dependsOn("${project.path}:yarn", ":web-ui:npmSetup")
}

// Build with webpack
val webpack by tasks.registering(NpmTask::class) {
    group = "Ignition Module"
    description = "Build React component with webpack"

    args.set(listOf("run", "build-dev"))

    dependsOn(yarnPackages)

    inputs.files(project.fileTree(project.projectDir).matching {
        exclude("**/node_modules/**", "**/dist/**", "**/build/**", "**/yarn-error.log")
    }.toList())

    outputs.files(fileTree(projectOutput))
}

// Run Vitest frontend tests
val frontendTest by tasks.registering(YarnTask::class) {
    group = "verification"
    description = "Run Vitest frontend unit tests"
    args.set(listOf("test"))
    dependsOn(yarnPackages)
}

tasks {
    processResources {
        dependsOn(webpack, yarnPackages)
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
