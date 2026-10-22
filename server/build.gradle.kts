plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Independent of the client modules (plan §1): no project dependencies.
dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.techgv.cineverse.server.ApplicationKt")
}
