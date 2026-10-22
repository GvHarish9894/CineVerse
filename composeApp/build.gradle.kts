import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "com.techgv.cineverse.composeapp"
        compileSdk = 37
        minSdk = 26
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.ui)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        // coil-network-ktor3 needs a Ktor engine per target.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.kotlinx.browser)
        }
    }
}

// The native read token must never reach the browser bundle (plan §5). CI places the sentinel in secrets.properties
// (the native config path read by tvApp/androidApp/iosApp); locally the real configured token is scanned for as well.
tasks.register("checkWasmHasNoSecrets") {
    group = "verification"
    description = "Builds the wasm distribution and fails if the native read token or the sentinel appears in any .wasm/.js/.mjs/.map output."
    dependsOn("wasmJsBrowserDistribution")
    val wasmSecretSentinel = "SENTINEL_TMDB_TOKEN_DO_NOT_SHIP"
    val nativeReadToken = providers.fileContents(rootProject.layout.projectDirectory.file("secrets.properties"))
        .asText.map { text -> Properties().apply { load(text.reader()) }.getProperty("TMDB_READ_ACCESS_TOKEN", "").trim() }
        .orElse("")
    val distribution = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
    val scanRoots = listOf(
        distribution,
        layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable"),
        layout.buildDirectory.dir("kotlin-webpack/wasmJs/productionExecutable"),
    )
    // Positive control: a string literal from App.kt that must be found, proving the scan can see Kotlin strings in .wasm.
    val controlLiteral = "qJ2tW6WMUDux911r6m7haRef0WH"
    doLast {
        val token = nativeReadToken.get()
        check(token.isNotEmpty()) {
            "secrets.properties has no TMDB_READ_ACCESS_TOKEN; put TMDB_READ_ACCESS_TOKEN=$wasmSecretSentinel there first"
        }
        val files = scanRoots.flatMap { root -> root.get().asFile.walk().filter { it.isFile && it.extension in setOf("wasm", "js", "mjs", "map") }.toList() }
        val shipped = distribution.get().asFile.walk().filter { it.isFile }.map { it.extension }.toSet()
        check("wasm" in shipped && "js" in shipped) { "wasm distribution is missing .wasm or .js outputs: $shipped" }
        // Kotlin/Wasm may store string literals as UTF-16, so look for both encodings; Latin-1 keeps bytes 1:1.
        fun encodings(value: String) = listOf(Charsets.UTF_8, Charsets.UTF_16LE).map { String(value.toByteArray(it), Charsets.ISO_8859_1) }
        val contents = files.associateWith { String(it.readBytes(), Charsets.ISO_8859_1) }
        check(contents.filterKeys { it.extension == "wasm" }.values.any { body -> encodings(controlLiteral).any(body::contains) }) {
            "scanner self-check failed: control literal not found in any .wasm"
        }
        val leaks = setOf(wasmSecretSentinel, token).flatMap { secret ->
            contents.filter { (_, body) -> encodings(secret).any(body::contains) }.keys
                .map { "${if (secret == wasmSecretSentinel) "sentinel" else "configured token"} in $it" }
        }
        check(leaks.isEmpty()) { "native read token leaked into the wasm bundle:\n" + leaks.joinToString("\n") }
        println("checkWasmHasNoSecrets: ${files.size} files scanned (sentinel=${token == wasmSecretSentinel}), no leak")
    }
}
