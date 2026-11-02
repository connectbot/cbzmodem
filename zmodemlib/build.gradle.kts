import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.tasks.GenerateModuleMetadata

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
    alias(libs.plugins.metalava)
    signing
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.dokka)
}

kotlin { jvmToolchain(17) }

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(libs.kstatemachine)
    api(libs.coroutines)
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val testJdkVersion = providers.gradleProperty("jdkVersion").map(String::toInt).orElse(17)
tasks.test {
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(testJdkVersion.get())) })
}

// Kotlin documentation belongs in the Maven javadoc artifact, rather than an empty JavaDoc jar.
tasks.named<Jar>("javadocJar") { from(tasks.named("dokkaGeneratePublicationHtml")) }
dokka { moduleName.set("ConnectBot Z-Modem Library") }

tasks.cyclonedxDirectBom {
    includeConfigs.set(listOf("runtimeClasspath"))
    includeLicenseText.set(true)
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx-direct/zmodemlib-bom.json"))
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifact(tasks.cyclonedxDirectBom.flatMap { it.jsonOutput }) {
                classifier = "cyclonedx"
                extension = "json"
            }
            pom {
                name.set("ConnectBot Z-Modem")
                description.set("Incremental transport-independent Kotlin Z-Modem protocol")
                url.set("https://github.com/connectbot/cbzmodem")
                inceptionYear.set("2026")
                developers {
                    developer {
                        id.set("kruton")
                        name.set("Kenny Root")
                        url.set("https://github.com/kruton")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/connectbot/cbzmodem.git")
                    developerConnection.set("scm:git:ssh://git@github.com/connectbot/cbzmodem.git")
                    url.set("https://github.com/connectbot/cbzmodem")
                }
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}

metalava { javaSourceLevel.set(JavaVersion.VERSION_17) }

tasks.named("metalavaCheckCompatibility") {
    doFirst { layout.buildDirectory.dir("metalava").get().asFile.mkdirs() }
}

tasks.named("check") { dependsOn("metalavaCheckCompatibility") }

publishing {
    repositories {
        providers.environmentVariable("MAVEN_REPOSITORY_URL").orNull?.let { endpoint ->
            maven {
                url = uri(endpoint)
                credentials {
                    username = providers.environmentVariable("MAVEN_USERNAME").orNull
                    password = providers.environmentVariable("MAVEN_PASSWORD").orNull
                }
            }
        }
    }
}

signing {
    val key = providers.environmentVariable("SIGNING_KEY").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, providers.environmentVariable("SIGNING_PASSWORD").orNull)
        sign(publishing.publications)
    }
}

// The shared publisher signs and uploads these exact bytes in isolated jobs. Never stage secrets.
val stagedArtifactPrefix = "${project.name}-${project.version}"
tasks.register<Sync>("stageMavenPublication") {
    group = "publishing"
    into(rootProject.layout.buildDirectory.dir("publication/${project.name}"))
    listOf("jar", "sourcesJar", "javadocJar").forEach { artifactTask ->
        from(tasks.named<Jar>(artifactTask).flatMap { it.archiveFile })
    }
    from(tasks.named<GenerateMavenPom>("generatePomFileForLibraryPublication").map { it.destination }) {
        rename { "$stagedArtifactPrefix.pom" }
    }
    from(tasks.named<GenerateModuleMetadata>("generateMetadataFileForLibraryPublication").flatMap { it.outputFile }) {
        rename { "$stagedArtifactPrefix.module" }
    }
    from(tasks.cyclonedxDirectBom.flatMap { it.jsonOutput }) {
        rename { "$stagedArtifactPrefix-cyclonedx.json" }
    }
}
