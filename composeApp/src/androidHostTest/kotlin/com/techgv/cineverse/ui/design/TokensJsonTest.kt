package com.techgv.cineverse.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Generated Tokens.kt == design/{mobile,web}/tokens.json, checked with an independent parser (kotlinx.serialization +
 * the CSS rules below), leaf by leaf, plus the leaf set itself (nothing dropped, nothing invented).
 */
class TokensJsonTest {
    private val root = File("../design")
    private fun load(kit: String) = Json.parseToJsonElement(File(root, "$kit/tokens.json").readText()).let { it as JsonObject }

    private fun jsonLeaves(): Map<String, JsonElement> {
        val out = linkedMapOf<String, JsonElement>()
        load("mobile").filterKeys { it != "\$description" }.forEach { (section, value) ->
            (value as JsonObject).forEach { (k, v) ->
                if (v is JsonObject && section in setOf("typography", "haptics")) v.forEach { (k2, v2) -> out["$section.$k.$k2"] = v2 }
                else out["$section.$k"] = v
            }
        }
        return out
    }

    private fun cssColor(css: String): Int {
        Regex("#([0-9A-Fa-f]{6})").matchEntire(css)?.let { return (0xFF000000 or it.groupValues[1].toLong(16)).toInt() }
        val m = Regex("""rgba\((\d+),\s*(\d+),\s*(\d+),\s*([0-9.]+)\)""").matchEntire(css) ?: fail("not a colour: $css")
        val (r, g, b) = m.groupValues.subList(1, 4).map { it.toInt() }
        return Color(r, g, b, (m.groupValues[4].toDouble() * 255).roundToInt()).toArgb()
    }

    @Test
    fun bothKitFilesAreIdentical() {
        assertEquals(load("mobile"), load("web"))
    }

    @Test
    fun everyLeafIsGeneratedWithItsValue() {
        val json = jsonLeaves()
        assertEquals(json.keys, generatedTokenPaths.keys, "JSON leaves vs generated symbols")
        for ((path, expected) in json) {
            val actual = generatedTokenPaths.getValue(path)
            val section = path.substringBefore('.')
            when {
                section == "color" && expected is JsonArray ->
                    assertEquals(expected.map { cssColor((it as JsonPrimitive).content) }, (actual as List<*>).map { (it as Color).toArgb() }, path)
                section == "color" -> assertEquals(cssColor((expected as JsonPrimitive).content), (actual as Color).toArgb(), path)
                section == "scrim" || section == "glow" -> assertEquals((expected as JsonPrimitive).content, actual, path)
                section == "typography" && expected is JsonArray -> assertEquals(expected.map { (it as JsonPrimitive).int }, actual, path)
                section == "typography" && (expected as JsonPrimitive).isString -> assertEquals(expected.content, actual, path)
                section == "typography" -> assertEquals((expected as JsonPrimitive).double.toFloat().sp, actual as TextUnit, path)
                section == "radius" || section == "spacing" -> assertEquals((expected as JsonPrimitive).double.toFloat().dp, actual as Dp, path)
                section == "size" && expected is JsonArray ->
                    assertEquals(DpSize((expected[0] as JsonPrimitive).int.dp, (expected[1] as JsonPrimitive).int.dp), actual as DpSize, path)
                section == "size" -> assertEquals((expected as JsonPrimitive).int.dp, actual as Dp, path)
                section == "motion" && (expected as JsonPrimitive).isString -> checkMotion(path, expected.content, actual as MotionSpec)
                section == "motion" -> assertEquals((expected as JsonPrimitive).double.toFloat(), actual as Float, path)
                section == "haptics" && path.startsWith("haptics.strengthMs.") -> assertEquals((expected as JsonPrimitive).int, actual, path)
                section == "haptics" -> checkHaptic(path, (expected as JsonPrimitive).content, actual as HapticTokenPattern)
                else -> fail("no comparison rule for $path")
            }
        }
    }

    private fun checkMotion(path: String, spec: String, actual: MotionSpec) {
        assertEquals(spec, actual.spec, path)
        assertEquals(spec.substringBefore("ms").toInt(), actual.durationMillis, path)
        val bezier = Regex("""cubic-bezier\(([^)]*)\)""").find(spec)?.groupValues?.get(1)?.split(",")?.map { it.trim().toFloat() }
        assertEquals(bezier != null, actual.easing != null, "$path easing presence")
        if (bezier != null) {
            // A CubicBezierEasing is pinned down by its value curve; compare it to the CSS control points.
            val expected = androidx.compose.animation.core.CubicBezierEasing(bezier[0], bezier[1], bezier[2], bezier[3])
            for (x in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) assertEquals(expected.transform(x), actual.easing!!.transform(x), 1e-4f, "$path at $x")
        }
    }

    private fun checkHaptic(path: String, spec: String, actual: HapticTokenPattern) {
        assertEquals(spec, actual.spec, path)
        val steps = spec.substringAfter('[').substringBefore(']').split(',').map { it.trim() }
        for (base in listOf(6, 12, 20)) {
            val expected = steps.map { s ->
                when {
                    s == "base" -> base
                    s.startsWith("base+") -> base + s.removePrefix("base+").toInt()
                    s.startsWith("base*") -> base * s.removePrefix("base*").toInt()
                    else -> s.toInt()
                }
            }
            assertEquals(expected, actual.timingsMs(base), "$path @ base $base")
        }
    }

    @Test
    fun ambientOpacitiesParsedFromGlowProse() {
        val ambient = (load("mobile")["glow"] as JsonObject)["ambient"].toString()
        assertTrue("0.42 × strength (TV)" in ambient && "0.60 × strength (mobile)" in ambient && "0.45 × strength (web)" in ambient && "default strength 0.70" in ambient)
        assertEquals(0.42f, Tokens.Glow.AmbientOpacityTv)
        assertEquals(0.60f, Tokens.Glow.AmbientOpacityMobile)
        assertEquals(0.45f, Tokens.Glow.AmbientOpacityWeb)
        assertEquals(0.70f, Tokens.Glow.AmbientDefaultStrength)
    }
}
