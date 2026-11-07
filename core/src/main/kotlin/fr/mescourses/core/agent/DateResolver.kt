package fr.mescourses.core.agent

import fr.mescourses.core.model.stripAccents
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Comprend « 2026-11-09 », « 09/11 », « aujourd'hui », « demain », « lundi »… */
object DateResolver {
    private val days = mapOf(
        "lundi" to DayOfWeek.MONDAY, "mardi" to DayOfWeek.TUESDAY, "mercredi" to DayOfWeek.WEDNESDAY,
        "jeudi" to DayOfWeek.THURSDAY, "vendredi" to DayOfWeek.FRIDAY, "samedi" to DayOfWeek.SATURDAY,
        "dimanche" to DayOfWeek.SUNDAY,
    )

    fun resolve(text: String?, today: LocalDate): LocalDate {
        val t = text?.trim()?.lowercase()?.stripAccents().orEmpty()
        if (t.isEmpty() || "aujourd" in t || t == "today") return today
        if ("apres-demain" in t || "apres demain" in t) return today.plusDays(2)
        if ("demain" in t || t == "tomorrow") return today.plusDays(1)
        runCatching { return LocalDate.parse(t.take(10)) }
        Regex("""^(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{2,4}))?$""").matchEntire(t)?.let { m ->
            val (d, mo, y) = m.destructured
            val year = y.toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: today.year
            val date = runCatching { LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull()
            if (date != null) return if (y.isEmpty() && date.isBefore(today)) date.plusYears(1) else date
        }
        days.entries.firstOrNull { it.key in t }?.let { (_, dow) ->
            var d = today
            while (d.dayOfWeek != dow) d = d.plusDays(1)
            if ("prochain" in t && d == today) d = d.plusWeeks(1)
            return d
        }
        return today
    }

    fun label(date: LocalDate): String {
        val day = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRANCE)
        return "$day ${date.format(DateTimeFormatter.ofPattern("dd/MM"))}"
    }
}
