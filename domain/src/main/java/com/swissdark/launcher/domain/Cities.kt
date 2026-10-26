package com.swissdark.launcher.domain

/**
 * Cities the Home weather line can show (open-meteo coordinates). The key is stored in
 * settings; the owner picks one in Settings › Home › Weather city.
 */
object Cities {
    data class City(val key: String, val name: String, val latitude: Double, val longitude: Double)

    val all = listOf(
        City("mumbai", "Mumbai", 19.0760, 72.8777),
        City("delhi", "Delhi", 28.6139, 77.2090),
        City("bengaluru", "Bengaluru", 12.9716, 77.5946),
        City("pune", "Pune", 18.5204, 73.8567),
        City("chennai", "Chennai", 13.0827, 80.2707),
        City("hyderabad", "Hyderabad", 17.3850, 78.4867),
        City("kolkata", "Kolkata", 22.5726, 88.3639),
        City("jaipur", "Jaipur", 26.9124, 75.7873),
        City("kochi", "Kochi", 9.9312, 76.2672),
        City("ahmedabad", "Ahmedabad", 23.0225, 72.5714),
    )

    fun byKey(key: String): City? = all.firstOrNull { it.key == key }

    /** The next city in the cycle (OK in settings); an unknown key starts at the first. */
    fun next(key: String): City {
        val i = all.indexOfFirst { it.key == key }
        return if (i < 0) all[0] else all[(i + 1) % all.size]
    }
}
