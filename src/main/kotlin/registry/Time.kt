package registry

import java.time.Instant
import java.time.format.DateTimeParseException

interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

class MutableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
    fun advanceSeconds(seconds: Long) {
        instant = instant.plusSeconds(seconds)
    }
}

object Instants {
    fun parse(text: String): Instant {
        val trimmed = text.trim()
        return try {
            Instant.parse(trimmed)
        } catch (e: DateTimeParseException) {
            Instant.parse(trimmed + "Z")
        }
    }

    fun render(instant: Instant): String = instant.toString()
}
