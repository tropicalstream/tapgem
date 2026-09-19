package com.tapgem.app.core.model

import java.util.Locale
import java.util.TimeZone

/** City names and abbreviations people actually say → IANA zones, and back to a short label. */
object WorldClocks {

    /** The popular picks shown as chips in the clock settings, in display order. */
    val POPULAR: List<Pair<String, String>> = listOf(
        "Local" to "", "New York" to "America/New_York", "Los Angeles" to "America/Los_Angeles", "Chicago" to "America/Chicago",
        "London" to "Europe/London", "Paris" to "Europe/Paris", "Berlin" to "Europe/Berlin", "Dubai" to "Asia/Dubai",
        "Mumbai" to "Asia/Kolkata", "Singapore" to "Asia/Singapore", "Hong Kong" to "Asia/Hong_Kong", "Tokyo" to "Asia/Tokyo",
        "Sydney" to "Australia/Sydney", "Honolulu" to "Pacific/Honolulu", "São Paulo" to "America/Sao_Paulo", "UTC" to "UTC"
    )

    private val ALIASES: Map<String, String> = mapOf(
        "nyc" to "America/New_York", "new york city" to "America/New_York", "manhattan" to "America/New_York", "boston" to "America/New_York",
        "miami" to "America/New_York", "washington" to "America/New_York", "toronto" to "America/Toronto", "eastern" to "America/New_York", "est" to "America/New_York", "edt" to "America/New_York",
        "la" to "America/Los_Angeles", "san francisco" to "America/Los_Angeles", "sf" to "America/Los_Angeles", "seattle" to "America/Los_Angeles", "oakland" to "America/Los_Angeles",
        "berkeley" to "America/Los_Angeles", "san jose" to "America/Los_Angeles", "portland" to "America/Los_Angeles", "san diego" to "America/Los_Angeles", "las vegas" to "America/Los_Angeles",
        "san diego" to "America/Los_Angeles", "vancouver" to "America/Vancouver", "pacific" to "America/Los_Angeles", "pst" to "America/Los_Angeles", "pdt" to "America/Los_Angeles",
        "denver" to "America/Denver", "phoenix" to "America/Phoenix", "mountain" to "America/Denver", "mst" to "America/Denver",
        "dallas" to "America/Chicago", "houston" to "America/Chicago", "central" to "America/Chicago", "cst" to "America/Chicago", "mexico city" to "America/Mexico_City",
        "anchorage" to "America/Anchorage", "alaska" to "America/Anchorage", "hawaii" to "Pacific/Honolulu", "hst" to "Pacific/Honolulu",
        "buenos aires" to "America/Argentina/Buenos_Aires", "rio" to "America/Sao_Paulo", "sao paulo" to "America/Sao_Paulo", "lima" to "America/Lima", "bogota" to "America/Bogota", "santiago" to "America/Santiago",
        "uk" to "Europe/London", "england" to "Europe/London", "gmt" to "Europe/London", "bst" to "Europe/London", "dublin" to "Europe/Dublin", "lisbon" to "Europe/Lisbon",
        "madrid" to "Europe/Madrid", "barcelona" to "Europe/Madrid", "rome" to "Europe/Rome", "milan" to "Europe/Rome", "amsterdam" to "Europe/Amsterdam", "brussels" to "Europe/Brussels",
        "zurich" to "Europe/Zurich", "vienna" to "Europe/Vienna", "prague" to "Europe/Prague", "stockholm" to "Europe/Stockholm", "oslo" to "Europe/Oslo", "copenhagen" to "Europe/Copenhagen",
        "helsinki" to "Europe/Helsinki", "warsaw" to "Europe/Warsaw", "athens" to "Europe/Athens", "cet" to "Europe/Paris", "cest" to "Europe/Paris", "moscow" to "Europe/Moscow", "istanbul" to "Europe/Istanbul",
        "cairo" to "Africa/Cairo", "johannesburg" to "Africa/Johannesburg", "cape town" to "Africa/Johannesburg", "lagos" to "Africa/Lagos", "nairobi" to "Africa/Nairobi",
        "tel aviv" to "Asia/Jerusalem", "jerusalem" to "Asia/Jerusalem", "riyadh" to "Asia/Riyadh", "tehran" to "Asia/Tehran", "karachi" to "Asia/Karachi",
        "delhi" to "Asia/Kolkata", "new delhi" to "Asia/Kolkata", "bangalore" to "Asia/Kolkata", "india" to "Asia/Kolkata", "ist" to "Asia/Kolkata", "kolkata" to "Asia/Kolkata",
        "bangkok" to "Asia/Bangkok", "jakarta" to "Asia/Jakarta", "manila" to "Asia/Manila", "kuala lumpur" to "Asia/Kuala_Lumpur", "hanoi" to "Asia/Ho_Chi_Minh", "ho chi minh" to "Asia/Ho_Chi_Minh",
        "shanghai" to "Asia/Shanghai", "beijing" to "Asia/Shanghai", "china" to "Asia/Shanghai",
        "shenzhen" to "Asia/Shanghai", "guangzhou" to "Asia/Shanghai", "hangzhou" to "Asia/Shanghai", "chengdu" to "Asia/Shanghai", "wuhan" to "Asia/Shanghai", "nanjing" to "Asia/Shanghai", "chongqing" to "Asia/Shanghai", "tianjin" to "Asia/Shanghai", "taipei" to "Asia/Taipei", "seoul" to "Asia/Seoul", "korea" to "Asia/Seoul",
        "japan" to "Asia/Tokyo", "jst" to "Asia/Tokyo", "osaka" to "Asia/Tokyo", "melbourne" to "Australia/Melbourne", "brisbane" to "Australia/Brisbane", "perth" to "Australia/Perth",
        "aest" to "Australia/Sydney", "auckland" to "Pacific/Auckland", "new zealand" to "Pacific/Auckland", "wellington" to "Pacific/Auckland",
        "zulu" to "UTC", "gmt+0" to "UTC", "greenwich" to "UTC", "local" to "", "here" to "", "home" to "", "my time" to ""
    )

    /** "Tokyo", "new york", "Asia/Tokyo", "PST" → zone id ("" = local); null when unknown. */
    fun resolve(name: String?): String? {
        val q = name?.trim()?.lowercase(Locale.US)?.replace(Regex("\\s+"), " ") ?: return null
        if (q.isBlank()) return ""
        ALIASES[q]?.let { return it }
        POPULAR.firstOrNull { it.first.lowercase(Locale.US) == q }?.let { return it.second }
        // A real IANA id (any case) — "asia/tokyo", "Europe/Berlin".
        val ids = TimeZone.getAvailableIDs()
        ids.firstOrNull { it.equals(name.trim(), ignoreCase = true) }?.let { return it }
        ids.firstOrNull { it.equals(name.trim().replace(' ', '_'), ignoreCase = true) }?.let { return it }
        // City part of an IANA id: "Tokyo" → Asia/Tokyo, "Buenos Aires" → …/Buenos_Aires.
        val city = q.replace(' ', '_')
        ids.firstOrNull { it.substringAfterLast('/').equals(city, ignoreCase = true) && it.contains('/') && !it.startsWith("Etc/") }?.let { return it }
        return null
    }

    /** Short label for a zone id: the chip name if popular, else the city part ("Buenos Aires"). */
    fun label(zoneId: String): String {
        if (zoneId.isBlank()) return "Local"
        POPULAR.firstOrNull { it.second == zoneId }?.let { return it.first }
        return zoneId.substringAfterLast('/').replace('_', ' ')
    }

    fun timeZone(zoneId: String): TimeZone = if (zoneId.isBlank()) TimeZone.getDefault() else TimeZone.getTimeZone(zoneId)

    /** "+9" / "−8" / "" — the zone's offset from local time, for world-clock rows. */
    fun offsetLabel(zoneId: String, nowMs: Long = System.currentTimeMillis()): String {
        if (zoneId.isBlank()) return ""
        val diffMin = (timeZone(zoneId).getOffset(nowMs) - TimeZone.getDefault().getOffset(nowMs)) / 60_000
        if (diffMin == 0) return "same time"
        val h = diffMin / 60; val m = Math.abs(diffMin % 60)
        val sign = if (diffMin > 0) "+" else "−"
        return sign + Math.abs(h) + (if (m != 0) ":" + m.toString().padStart(2, '0') else "") + "h"
    }
}
