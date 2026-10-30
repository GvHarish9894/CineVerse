import groovy.json.JsonSlurper
import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode
import org.jetbrains.kotlin.gradle.targets.js.ir.JsIrBinary
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrCompilation
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExec
import org.jetbrains.kotlin.gradle.targets.js.npm.npmProject

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val generateDesignTokens = tasks.register<GenerateDesignTokens>("generateDesignTokens") {
    mobileTokens.set(rootProject.layout.projectDirectory.file("design/mobile/tokens.json"))
    webTokens.set(rootProject.layout.projectDirectory.file("design/web/tokens.json"))
    mainOutput.set(layout.buildDirectory.dir("generated/designTokens/commonMain/kotlin"))
    testOutput.set(layout.buildDirectory.dir("generated/designTokens/androidHostTest/kotlin"))
}

kotlin {
    android {
        namespace = "com.techgv.cineverse.composeapp"
        compileSdk = 37
        minSdk = 26
        // Compose resources (fonts, sample JSON) ship as Android assets; the AGP-9 KMP library needs this switched on.
        androidResources { enable = true }
        withHostTest { isReturnDefaultValues = true }
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    // Verification-only Swift XCTest bridge; never part of production framework/archive builds.
    if (providers.gradleProperty("nativeAuthFixture").orNull == "true") {
        val simulator = targets.getByName<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>("iosSimulatorArm64")
        simulator.binaries.framework("authFixture", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG)) {
            baseName = "AuthFixture"
            compilation = simulator.compilations.getByName("test")
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
        val wasmMain = compilations.getByName("main")
        val accessibilityHarness = compilations.create("accessibilityHarness") {
            associateWith(wasmMain)
        }
        binaries.executable(accessibilityHarness)
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateDesignTokens.flatMap { it.mainOutput })
        }
        val nonWebMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(nonWebMain)
        iosMain.get().dependsOn(nonWebMain)
        val nonWebTest by creating {
            dependsOn(commonTest.get())
        }
        getByName("androidHostTest").dependsOn(nonWebTest)
        iosTest.get().dependsOn(nonWebTest)
        val mobileUiTest by creating {
            dependsOn(nonWebTest)
            dependencies { implementation(libs.compose.mp.ui.test) }
        }
        getByName("androidDeviceTest").dependsOn(mobileUiTest)
        iosTest.get().dependsOn(mobileUiTest)
        getByName("androidHostTest").kotlin.srcDir(generateDesignTokens.flatMap { it.testOutput })
        // C1's boundary fixture uses native-only auth contracts; never compile it into wasm tests.
        val nativeApprovalTest by creating {
            dependsOn(commonTest.get())
        }
        getByName("androidHostTest").dependsOn(nativeApprovalTest)
        iosTest.get().dependsOn(nativeApprovalTest)
        // Compose UI tests run where a real UI runtime exists: device (Android), simulator (iOS), headless Chrome (wasm).
        val composeUiTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.compose.mp.ui.test)
            }
        }
        mobileUiTest.dependsOn(composeUiTest)
        getByName("androidDeviceTest").dependsOn(composeUiTest)
        iosTest.get().dependsOn(composeUiTest)
        wasmJsTest.get().dependsOn(composeUiTest)

        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.ui)
            implementation(libs.compose.mp.material3)
            implementation(libs.compose.mp.components.resources)
            implementation(libs.kotlinx.serialization.json.kmp)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            implementation(libs.androidx.test.espresso.core)
        }
        // coil-network-ktor3 needs a Ktor engine per target.
        androidMain.dependencies {
            implementation(libs.androidx.browser)
            implementation(libs.androidx.datastore.preferences)
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

val accessibilityHarnessCompilation =
    (kotlin.targets.getByName("wasmJs") as KotlinJsIrTarget).compilations.getByName("accessibilityHarness") as KotlinJsIrCompilation
val accessibilityHarnessDist = layout.buildDirectory.dir("dist/wasmJs/accessibilityHarnessDevelopmentExecutable")
// KGP only creates browser webpack tasks for the main compilation, so stage the unbundled ES-module link output.
val accessibilityHarnessBinary =
    accessibilityHarnessCompilation.binaries.withType<JsIrBinary>().single { it.mode == KotlinJsBinaryMode.DEVELOPMENT }
val accessibilityHarnessNpm = accessibilityHarnessCompilation.npmProject

val webAccessibilityHarnessDistribution = tasks.register<Sync>("webAccessibilityHarnessDistribution") {
    group = "verification"
    description = "Stages the isolated real-page accessibility harness distribution."
    // Harness .mjs/.wasm, Skiko runtime (skiko.mjs/.wasm) and processed resources (index.html).
    from(accessibilityHarnessBinary.linkSyncTask)
    // kotlinx-datetime imports bare '@js-joda/core'; index.html maps it to this file with an import map.
    dependsOn(accessibilityHarnessNpm.nodeJsRoot.npmInstallTaskProvider)
    from(accessibilityHarnessNpm.nodeModulesDir.map { it.file("@js-joda/core/dist/js-joda.esm.js") })
    from(layout.projectDirectory.dir("src/commonMain/composeResources/font")) {
        into("composeResources/com.techgv.cineverse.ui.resources/font")
    }
    into(accessibilityHarnessDist)
}

NodeJsExec.register(accessibilityHarnessCompilation, "webAccessibilityRealPageTest") {
    group = "verification"
    description = "Runs the seven isolated real-page accessibility cases in an owned Chrome using Node 22."
    dependsOn(webAccessibilityHarnessDistribution)
    inputFileProperty.set(layout.projectDirectory.file("src/wasmJsTest/kotlin/com/techgv/cineverse/ui/web/WebAccessibilityRealPageTest.cjs"))
    sourceMapStackTraces = false
    args(accessibilityHarnessDist.get().asFile.absolutePath)
}

// commonMain resources = the checked-in composeResources (fonts) + design/sample-tmdb.json for the gallery,
// so the sample data has one source of truth.
val syncCommonComposeResources = tasks.register<Sync>("syncCommonComposeResources") {
    from(layout.projectDirectory.dir("src/commonMain/composeResources"))
    from(rootProject.layout.projectDirectory.file("design/sample-tmdb.json")) { into("files") }
    into(layout.buildDirectory.dir("generated/commonComposeResources"))
}

compose.resources {
    packageOfResClass = "com.techgv.cineverse.ui.resources"
    publicResClass = false
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = syncCommonComposeResources.map { layout.projectDirectory.dir(it.destinationDir.absolutePath) },
    )
}

// Same gate task name as :shared (the AGP-9 KMP library calls its host-test task testAndroidHostTest).
tasks.register("testDebugUnitTest") { dependsOn("testAndroidHostTest") }

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
    // Positive control: reachable product UI copy from WebHome.kt, proving the scan can see Kotlin strings in .wasm.
    val controlLiteral = "Discover your next favourite"
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

/**
 * Turns design/{mobile,web}/tokens.json into Tokens.kt (commonMain) plus a JSON-path → symbol table for
 * TokensJsonTest (androidHostTest). Both kit files must stay identical: the JSON already namespaces per platform
 * (scaleMobile/scaleWeb, mobilePoster/webPoster…), so a divergence needs a decision (docs/kits/TOKEN_CONFLICTS.md).
 */
@CacheableTask
abstract class GenerateDesignTokens : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val mobileTokens: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val webTokens: RegularFileProperty
    @get:OutputDirectory abstract val mainOutput: DirectoryProperty
    @get:OutputDirectory abstract val testOutput: DirectoryProperty

    @TaskAction
    fun generate() {
        val slurper = JsonSlurper()
        @Suppress("UNCHECKED_CAST")
        val root = slurper.parse(mobileTokens.get().asFile) as Map<String, Any?>
        check(root == slurper.parse(webTokens.get().asFile)) {
            "design/mobile/tokens.json and design/web/tokens.json diverged; record the decision in docs/kits/TOKEN_CONFLICTS.md and namespace the key"
        }
        val body = StringBuilder()
        val paths = mutableListOf<Pair<String, String>>()

        fun pascal(key: String) = key.replaceFirstChar { it.uppercaseChar() }
        fun num(value: Any?) = (value as Number).toString()
        fun str(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        fun color(css: String): String {
            val hex = Regex("#([0-9A-Fa-f]{6})").matchEntire(css)
            if (hex != null) return "Color(0xFF${hex.groupValues[1].uppercase()})"
            val rgba = Regex("""rgba\((\d+),\s*(\d+),\s*(\d+),\s*([0-9.]+)\)""").matchEntire(css)
                ?: error("unsupported colour '$css'")
            val (r, g, b) = rgba.groupValues.subList(1, 4).map { it.toInt() }
            val a = Math.round(rgba.groupValues[4].toDouble() * 255).toInt()
            return "Color(0x%02X%02X%02X%02X)".format(a, r, g, b)
        }
        fun motion(spec: String): String {
            val m = Regex("""^(\d+)ms(?:\s+cubic-bezier\(([^)]*)\))?""").find(spec) ?: error("motion '$spec' has no duration")
            val easing = m.groupValues[2].takeIf { it.isNotEmpty() }
                ?.split(",")?.joinToString(", ", "CubicBezierEasing(", ")") { it.trim().toDouble().toString() + "f" } ?: "null"
            return "MotionSpec(durationMillis = ${m.groupValues[1]}, easing = $easing, spec = ${str(spec)})"
        }
        fun haptic(spec: String): String {
            val m = Regex("""^\[([^\]]*)\]\s*(.*)$""").matchEntire(spec) ?: error("haptic '$spec' is not '[steps] note'")
            val steps = m.groupValues[1].split(",").map { step ->
                val s = step.trim()
                Regex("""base(?:\*(\d+))?(?:\+(\d+))?""").matchEntire(s)?.let { "HapticStep(${it.groupValues[1].ifEmpty { "1" }}, ${it.groupValues[2].ifEmpty { "0" }})" }
                    ?: "HapticStep(0, ${s.toIntOrNull() ?: error("haptic step '$s'")})"
            }
            return "HapticTokenPattern(listOf(${steps.joinToString()}), spec = ${str(spec)})"
        }
        fun emit(indent: String, path: String, name: String, decl: String) {
            body.append(indent).append(decl).append('\n')
            paths += path to name
        }
        fun group(indent: String, objectName: String, path: String, values: Map<*, *>, leaf: (String, Any?) -> String) {
            body.append("${indent}object $objectName {\n")
            for ((k, v) in values) emit("$indent    ", "$path.$k", "$objectName.${pascal(k as String)}", leaf(pascal(k), v))
            body.append("$indent}\n")
        }

        for ((section, values) in root) {
            if (section == "\$description") continue
            values as Map<*, *>
            when (section) {
                "color" -> group("    ", "Colors", section, values) { n, v ->
                    if (v is List<*>) "val $n: List<Color> = listOf(${v.joinToString { color(it as String) }})" else "val $n = ${color(v as String)}"
                }
                "scrim" -> group("    ", "Scrim", section, values) { n, v -> "const val $n = ${str(v as String)}" }
                "glow" -> {
                    group("    ", "Glow", section, values) { n, v -> "const val $n = ${str(v as String)}" }
                    val ambient = values["ambient"] as String
                    val opacities = Regex("""([0-9.]+) × strength \((\w+)\)""").findAll(ambient).associate { it.groupValues[2] to it.groupValues[1] }
                    val strength = Regex("""default strength ([0-9.]+)""").find(ambient)?.groupValues?.get(1) ?: error("glow.ambient default strength")
                    check(opacities.keys == setOf("TV", "mobile", "web")) { "glow.ambient opacities: $opacities" }
                    body.setLength(body.length - "    }\n".length)
                    body.append("        /** Parsed from [Ambient]. */\n")
                    body.append("        const val AmbientOpacityTv = ${opacities["TV"]}f\n")
                    body.append("        const val AmbientOpacityMobile = ${opacities["mobile"]}f\n")
                    body.append("        const val AmbientOpacityWeb = ${opacities["web"]}f\n")
                    body.append("        const val AmbientDefaultStrength = ${strength}f\n    }\n")
                }
                "typography" -> {
                    body.append("    object Typography {\n")
                    for ((k, v) in values) {
                        val name = pascal(k as String)
                        v as Map<*, *>
                        group("        ", name, "$section.$k", v) { n, leaf ->
                            when (leaf) {
                                is String -> "const val $n = ${str(leaf)}"
                                is List<*> -> "val $n: List<Int> = listOf(${leaf.joinToString { num(it) }})"
                                else -> "val $n = ${num(leaf)}.sp"
                            }
                        }
                    }
                    body.append("    }\n")
                    paths.replaceAll { (p, n) -> if (p.startsWith("typography.")) p to "Typography.$n" else p to n }
                }
                "radius", "spacing" -> group("    ", pascal(section), section, values) { n, v -> "val $n = ${num(v)}.dp" }
                "size" -> group("    ", "Size", section, values) { n, v ->
                    if (v is List<*>) "val $n = DpSize(${num(v[0])}.dp, ${num(v[1])}.dp)" else "val $n = ${num(v)}.dp"
                }
                "motion" -> group("    ", "Motion", section, values) { n, v ->
                    if (v is Number) "const val $n = ${num(v)}f" else "val $n = ${motion(v as String)}"
                }
                "haptics" -> {
                    body.append("    object Haptics {\n")
                    for ((k, v) in values) {
                        if (v is Map<*, *>) group("        ", pascal(k as String), "$section.$k", v) { n, ms -> "const val $n = ${num(ms)}" }
                        else emit("        ", "$section.$k", pascal(k as String), "val ${pascal(k)} = ${haptic(v as String)}")
                    }
                    body.append("    }\n")
                    paths.replaceAll { (p, n) -> if (p.startsWith("haptics.")) p to "Haptics.$n" else p to n }
                }
                else -> error("tokens.json section '$section' has no generator rule")
            }
        }

        val pkg = "com.techgv.cineverse.ui.design"
        val header = "// GENERATED by :composeApp:generateDesignTokens from design/{mobile,web}/tokens.json. Do not edit.\npackage $pkg\n\n"
        val mainDir = mainOutput.get().asFile.resolve(pkg.replace('.', '/')).apply { deleteRecursively(); mkdirs() }
        mainDir.resolve("Tokens.kt").writeText(
            header + """
            import androidx.compose.animation.core.CubicBezierEasing
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.unit.DpSize
            import androidx.compose.ui.unit.dp
            import androidx.compose.ui.unit.sp

            /** Size pairs are [width, height] except Size.TvRail = [collapsed, expanded] width. */
            object Tokens {
            """.trimIndent() + "\n" + body + "}\n",
        )
        val testDir = testOutput.get().asFile.resolve(pkg.replace('.', '/')).apply { deleteRecursively(); mkdirs() }
        testDir.resolve("GeneratedTokenPaths.kt").writeText(
            header + "/** Every tokens.json leaf (JSON path) → the generated symbol, for TokensJsonTest. */\n" +
                "internal val generatedTokenPaths: Map<String, Any?> = mapOf(\n" +
                paths.joinToString("") { (p, n) -> "    ${str(p)} to Tokens.$n,\n" } + ")\n",
        )
    }
}
