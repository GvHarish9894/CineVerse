import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
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
                withIos()
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
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.datetime)
            // Declared only (P0 tuple); wired at composition roots in P5.
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("nonWebMain").dependencies {
            implementation(libs.androidx.datastore.preferences.core)
            // Declared only (P0 tuple); the guest library moves here in P1. Must never reach wasmJs.
            implementation(libs.androidx.room.runtime)
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

// The AGP-9 KMP library names its host-test task testAndroidHostTest; keep the gate name used by the other modules.
tasks.register("testDebugUnitTest") { dependsOn("testAndroidHostTest") }
