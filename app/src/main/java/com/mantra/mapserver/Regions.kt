package com.mantra.mapserver

/**
 * THE REGIONS THIS APP CAN FETCH BY ITSELF, from mapsforge's own download server.
 *
 * No Android imports (android-app.md 1). The sizes were measured against the server on 15.9.2026
 * so the screen can say what a download will cost before it starts, rather than after
 * (download-monitor.md).
 */
object Regions {

    data class Region(val name: String, val label: String, val url: String, val bytes: Long)

    private const val BASE = "https://download.mapsforge.org/maps/v5"

    val ALL: List<Region> = listOf(
        Region("croatia", "Croatia", "$BASE/europe/croatia.map", 175_514_764L),
        Region("slovenia", "Slovenia", "$BASE/europe/slovenia.map", 238_465_781L),
        Region("bosnia-herzegovina", "Bosnia and Herzegovina", "$BASE/europe/bosnia-herzegovina.map", 158_631_986L),
        Region("montenegro", "Montenegro", "$BASE/europe/montenegro.map", 28_624_758L),
        Region("serbia", "Serbia", "$BASE/europe/serbia.map", 200_470_005L),
        Region("austria", "Austria", "$BASE/europe/austria.map", 554_110_376L),
        Region("italy", "Italy", "$BASE/europe/italy.map", 1_681_270_135L),
        Region("india", "India", "$BASE/asia/india.map", 1_576_654_675L),
    )

    fun byName(name: String): Region? = ALL.firstOrNull { it.name == name }

    /** A size to show before a download, or a shrug when nobody has measured it yet. */
    fun sizeLabel(region: Region): String = when {
        region.bytes >= 1_000_000_000 -> "${region.bytes / 100_000_000 / 10.0} GB"
        region.bytes > 0 -> "${region.bytes / 1_000_000} MB"
        else -> "size unknown until it starts"
    }
}
