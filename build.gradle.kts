import net.researchgate.release.ReleaseExtension

plugins {
    base
    alias(libs.plugins.release)
    alias(libs.plugins.cyclonedx) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.metalava) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    group = rootProject.group
    version = rootProject.version
    repositories {
        google()
        mavenCentral()
    }
}

// Keep the native Spotless Black executable local to this build, including on CI/release runners.
val blackVersion = libs.versions.black.get()
val pythonFormatterEnvironment = layout.buildDirectory.dir("python-formatter")
val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val formatterPython = pythonFormatterEnvironment.map { it.file(if (windows) "Scripts/python.exe" else "bin/python") }
val formatterBlack = pythonFormatterEnvironment.map { it.file(if (windows) "Scripts/black.exe" else "bin/black") }
val formatterConfig = pythonFormatterEnvironment.map { it.file("pyvenv.cfg") }
val pythonExecutable = providers.gradleProperty("pythonExecutable").orElse(if (windows) "python" else "python3")

val createPythonFormatterEnvironment = tasks.register<Exec>("createPythonFormatterEnvironment") {
    group = "formatting"
    description = "Creates the isolated Python environment used by Spotless"
    inputs.property("pythonExecutable", pythonExecutable)
    outputs.files(formatterConfig, formatterPython)
    commandLine(pythonExecutable.get(), "-m", "venv", pythonFormatterEnvironment.get().asFile.absolutePath)
}

val installPythonFormatter = tasks.register<Exec>("installPythonFormatter") {
    group = "formatting"
    description = "Installs the pinned Black formatter for Spotless"
    dependsOn(createPythonFormatterEnvironment)
    inputs.property("blackVersion", blackVersion)
    inputs.file(formatterConfig).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(formatterBlack)
    commandLine(
        formatterPython.get().asFile.absolutePath,
        "-m",
        "pip",
        "install",
        "--disable-pip-version-check",
        "black==$blackVersion",
    )
}

spotless {
    python {
        target("**/*.py")
        targetExclude("**/build/**", "**/.gradle/**", "**/.venv/**", "**/venv/**")
        black(blackVersion).pathToExe(formatterBlack.get().asFile.absolutePath)
    }
    kotlin {
        target("**/src/**/*.kt")
        ktlint("1.8.0")
        licenseHeaderFile("spotless/license-header.txt")
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint("1.8.0")
    }
    yaml {
        target(".github/**/*.yml", ".github/**/*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("xml") {
        target("**/*.xml")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("toml") {
        target("**/*.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target("**/*.md", "**/.gitignore", "**/.gitattributes", "**/.editorconfig")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.matching { it.name == "spotlessPython" }.configureEach {
    dependsOn(installPythonFormatter)
}

tasks.named("check") { dependsOn("spotlessCheck", ":zmodemlib:check") }
tasks.named("build") { dependsOn(":zmodemlib:build", ":testapp:build") }

configure<ReleaseExtension> {
    tagTemplate.set("v\${version}")
    buildTasks.set(listOf("build"))
    git {
        requireBranch.set("^(main|release/[0-9]+\\.[0-9]+|release-work/.+)$")
        commitVersionFileOnly.set(true)
        if (providers.gradleProperty("release.noPush").isPresent) pushToRemote.set(false)
    }
}

tasks.register("stageMavenPublication") {
    group = "publishing"
    description = "Stages unsigned artifacts for the shared attested Maven Central publisher"
    dependsOn(":zmodemlib:stageMavenPublication")
}
