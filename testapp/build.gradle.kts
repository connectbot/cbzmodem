plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":zmodemlib")) }
application { mainClass.set("MainKt") }
