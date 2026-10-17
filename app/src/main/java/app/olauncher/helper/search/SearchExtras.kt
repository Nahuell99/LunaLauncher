package app.olauncher.helper.search

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.pow

/**
 * Inline answers for the drawer search: a calculator, unit conversion and `>` commands.
 * Pure logic (no Android types) so it is unit-tested on the JVM.
 */
object SearchExtras {

    // ---------------------------------------------------------------------------------
    // Calculator
    // ---------------------------------------------------------------------------------

    private val looksLikeMath = Regex("""^[\d\s.,+\-*/x×÷^%()πe]+$|^=.+""")

    /** Evaluates [query] when it is an arithmetic expression, else null. "=" forces evaluation. */
    fun calculate(query: String): Double? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val forced = q.startsWith("=")
        val expr = q.removePrefix("=").trim()
        if (!forced) {
            // A lone number is not a calculation, and an operator is required.
            if (!looksLikeMath.matches(q) || !q.any { it in "+-*/x×÷^%" } || !q.any { it.isDigit() }) return null
        }
        return runCatching { Parser(expr).parse() }.getOrNull()?.takeIf { it.isFinite() }
    }

    fun formatNumber(value: Double): String {
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
        return BigDecimal(value).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }

    /** Recursive-descent parser: + - * / % ^, parentheses, unary minus, π, e, sqrt(). */
    private class Parser(source: String) {
        private val s = source.lowercase(Locale.ROOT)
            .replace('×', '*').replace('÷', '/').replace('x', '*').replace(',', '.')
            .replace("sqrt", "√").replace("pi", "π").filterNot { it.isWhitespace() }
        private var pos = 0

        fun parse(): Double {
            val v = expression()
            require(pos == s.length) { "Unexpected '${s[pos]}'" }
            return v
        }

        private fun peek() = s.getOrNull(pos)
        private fun eat(c: Char): Boolean = (peek() == c).also { if (it) pos++ }

        private fun expression(): Double {
            var v = term()
            while (true) v = when {
                eat('+') -> v + term()
                eat('-') -> v - term()
                else -> return v
            }
        }

        private fun term(): Double {
            var v = factor()
            while (true) v = when {
                eat('*') -> v * factor()
                eat('/') -> v / factor()
                eat('%') -> v % factor()
                else -> return v
            }
        }

        private fun factor(): Double {
            val base = unary()
            return if (eat('^')) base.pow(factor()) else base // right-associative
        }

        private fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            eat('√') -> kotlin.math.sqrt(unary())
            else -> atom()
        }

        private fun atom(): Double {
            if (eat('(')) {
                val v = expression()
                require(eat(')')) { "Missing )" }
                return v
            }
            if (eat('π')) return Math.PI
            if (eat('e')) return Math.E
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            require(pos > start) { "Number expected" }
            return s.substring(start, pos).toDouble()
        }
    }

    // ---------------------------------------------------------------------------------
    // Units
    // ---------------------------------------------------------------------------------

    private data class Unit(val group: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double)

    private fun linear(group: String, factor: Double) = Unit(group, { it * factor }, { it / factor })

    // Aliases in English and Spanish; base units: metre, gram, litre, m/s, second, byte, °C.
    private val UNITS: Map<String, Unit> = buildMap {
        fun put(unit: Unit, vararg names: String) = names.forEach { put(it, unit) }
        put(linear("len", 0.001), "mm", "milimetro", "milimetros", "millimeter", "millimeters")
        put(linear("len", 0.01), "cm", "centimetro", "centimetros", "centimeter", "centimeters")
        put(linear("len", 1.0), "m", "metro", "metros", "meter", "meters", "metre", "metres")
        put(linear("len", 1000.0), "km", "kilometro", "kilometros", "kilometer", "kilometers")
        put(linear("len", 0.0254), "in", "inch", "inches", "pulgada", "pulgadas", "\"")
        put(linear("len", 0.3048), "ft", "foot", "feet", "pie", "pies", "'")
        put(linear("len", 0.9144), "yd", "yard", "yards", "yarda", "yardas")
        put(linear("len", 1609.344), "mi", "mile", "miles", "milla", "millas")
        put(linear("mass", 0.001), "mg", "miligramo", "miligramos")
        put(linear("mass", 1.0), "g", "gr", "gramo", "gramos", "gram", "grams")
        put(linear("mass", 1000.0), "kg", "kilo", "kilos", "kilogramo", "kilogramos", "kilogram", "kilograms")
        put(linear("mass", 1_000_000.0), "t", "ton", "tonelada", "toneladas", "tonne", "tonnes")
        put(linear("mass", 453.59237), "lb", "lbs", "libra", "libras", "pound", "pounds")
        put(linear("mass", 28.349523125), "oz", "onza", "onzas", "ounce", "ounces")
        put(linear("vol", 0.001), "ml", "mililitro", "mililitros", "milliliter", "milliliters")
        put(linear("vol", 1.0), "l", "lt", "litro", "litros", "liter", "liters", "litre", "litres")
        put(linear("vol", 3.785411784), "gal", "galon", "galones", "gallon", "gallons")
        put(linear("vol", 0.0295735295625), "floz", "fl oz")
        put(linear("vol", 0.2365882365), "cup", "cups", "taza", "tazas")
        put(linear("vol", 0.01478676478125), "tbsp", "cucharada", "cucharadas")
        put(linear("vol", 0.00492892159375), "tsp", "cucharadita", "cucharaditas")
        put(linear("speed", 1.0), "m/s", "ms")
        put(linear("speed", 1 / 3.6), "km/h", "kmh", "kph")
        put(linear("speed", 0.44704), "mph")
        put(linear("speed", 0.514444), "kn", "kt", "nudo", "nudos", "knot", "knots")
        put(linear("time", 1.0), "s", "seg", "segundo", "segundos", "sec", "second", "seconds")
        put(linear("time", 60.0), "min", "minuto", "minutos", "minute", "minutes")
        put(linear("time", 3600.0), "h", "hr", "hora", "horas", "hour", "hours")
        put(linear("time", 86400.0), "d", "dia", "dias", "day", "days")
        put(linear("data", 1.0), "b", "byte", "bytes")
        put(linear("data", 1024.0), "kb", "kib")
        put(linear("data", 1024.0 * 1024), "mb", "mib")
        put(linear("data", 1024.0 * 1024 * 1024), "gb", "gib")
        put(linear("data", 1024.0 * 1024 * 1024 * 1024), "tb", "tib")
        put(Unit("temp", { it }, { it }), "c", "°c", "celsius")
        put(Unit("temp", { (it - 32) * 5 / 9 }, { it * 9 / 5 + 32 }), "f", "°f", "fahrenheit")
        put(Unit("temp", { it - 273.15 }, { it + 273.15 }), "k", "kelvin")
    }

    private val conversion = Regex(
        """^(-?[\d.,]+)\s*(.+?)\s+(?:a|en|to|in|->|=|>)?\s*([^\s].*)$""",
        RegexOption.IGNORE_CASE,
    )

    private fun normalize(unit: String) = unit.trim().lowercase(Locale.ROOT)
        .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")

    /** "10 km a mi" → "10 km = 6.213712 mi", or null when [query] is not a conversion. */
    fun convert(query: String): String? {
        val match = conversion.matchEntire(query.trim()) ?: return null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val fromName = normalize(match.groupValues[2])
        val toName = normalize(match.groupValues[3])
        val from = UNITS[fromName] ?: return null
        val to = UNITS[toName] ?: return null
        if (from.group != to.group || fromName == toName) return null
        val result = to.fromBase(from.toBase(amount))
        return "${formatNumber(amount)} ${match.groupValues[2].trim()} = ${formatNumber(result)} ${match.groupValues[3].trim()}"
    }

    // ---------------------------------------------------------------------------------
    // Commands ("> wifi", "> alarma 7:30", "> timer 10m")
    // ---------------------------------------------------------------------------------

    data class Command(val id: String, val argument: String = "")

    private val COMMANDS = mapOf(
        "wifi" to listOf("wifi", "wi-fi", "red"),
        "bluetooth" to listOf("bluetooth", "bt"),
        "internet" to listOf("internet", "datos", "data", "mobile"),
        "volume" to listOf("volumen", "volume", "sonido", "sound"),
        "torch" to listOf("linterna", "torch", "flashlight", "luz"),
        "alarm" to listOf("alarma", "alarm", "despertador"),
        "timer" to listOf("timer", "temporizador"),
        "battery" to listOf("bateria", "battery"),
        "display" to listOf("pantalla", "display", "brillo", "brightness"),
        "notifications" to listOf("notificaciones", "notifications"),
        "settings" to listOf("ajustes", "settings", "configuracion"),
        "launcher" to listOf("launcher", "luna"),
    )

    /** Commands whose keyword starts with what follows ">" (all of them for a bare ">"). */
    fun commands(query: String): List<Command> {
        val q = query.trim()
        if (!q.startsWith(">")) return emptyList()
        val body = normalize(q.removePrefix(">"))
        val word = body.substringBefore(' ')
        val argument = if (body.contains(' ')) body.substringAfter(' ').trim() else ""
        return COMMANDS.filter { (_, keys) -> keys.any { it.startsWith(word) } }.map { (id, _) -> Command(id, argument) }
    }

    /** "7:30", "07.30", "7" → (7, 30); null when invalid. */
    fun parseTime(text: String): Pair<Int, Int>? {
        val m = Regex("""^(\d{1,2})(?:[:.h](\d{2}))?$""").matchEntire(text.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /** "10m", "90s", "1h30m", "10" (minutes) → seconds; null when invalid. */
    fun parseDuration(text: String): Int? {
        val t = text.trim().lowercase(Locale.ROOT)
        t.toIntOrNull()?.let { return (it * 60).takeIf { s -> s > 0 } }
        val parts = Regex("""(\d+)\s*(h|m|s)""").findAll(t).toList()
        if (parts.isEmpty() || parts.joinToString("") { it.value.replace(" ", "") } != t.replace(" ", "")) return null
        return parts.sumOf {
            val n = it.groupValues[1].toInt()
            when (it.groupValues[2]) { "h" -> n * 3600; "m" -> n * 60; else -> n }
        }.takeIf { it > 0 }
    }
}
