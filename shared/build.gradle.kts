import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    android {
        namespace = "com.techgv.cineverse.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            // nonWeb = android + ios + jvm: everything that may use Room, DataStore files or platform crypto.
            group("nonWeb") {
                group("ios") // so iosMain / iosTest see nonWeb (e.g. the Room tests)
                withJvm()
            }
        }
    }

    sourceSets {
        // The AGP-9 android target is not matched by the hierarchy template's withAndroidTarget(); attach it by hand.
        androidMain.get().dependsOn(getByName("nonWebMain"))
        getByName("androidHostTest").dependsOn(getByName("nonWebTest"))

        commonMain.dependencies {
            api(libs.jetbrains.lifecycle.viewmodel)
            api(libs.jetbrains.lifecycle.viewmodel.savedstate)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            api(libs.kotlinx.datetime)
            // Declared only (P0 tuple); wired at composition roots in P5.
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("nonWebMain").dependencies {
            api(libs.androidx.datastore.preferences.core)
            // Guest library + import ledger (library.db). Must never reach wasmJs.
            api(libs.androidx.room.runtime)
        }
        // Room's bundled SQLite driver for the jvm / iOS database tests (Android uses the framework SQLite).
        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        iosTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.kotlinx.browser)
        }
    }
}

// Room's KSP processor runs per nonWeb target (never on commonMain / wasmJs).
dependencies {
    listOf("kspAndroid", "kspJvm", "kspIosArm64", "kspIosSimulatorArm64").forEach { add(it, libs.androidx.room.compiler) }
}

// The AGP-9 KMP library names its host-test task testAndroidHostTest; keep the gate name used by the other modules.
tasks.register("testDebugUnitTest") { dependsOn("testAndroidHostTest") }
