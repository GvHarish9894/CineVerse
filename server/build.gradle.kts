plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// Independent of the client modules (plan §1): no project dependencies.
dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.techgv.cineverse.server.ApplicationKt")
}

tasks.test {
    // The log-redaction test captures everything the server logs at trace level.
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace")
}
