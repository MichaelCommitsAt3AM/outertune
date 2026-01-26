package com.dd3boh.outertune.utils.analysis

/**
 * Musical scale type (Major or Minor)
 */
enum class Scale {
    MAJOR, MINOR
}

/**
 * Represents a musical key with Camelot wheel notation for DJ mixing.
 * 
 * The Camelot wheel uses numbers 1-12 with letters A (minor) and B (major).
 * Keys one step apart on the wheel are harmonically compatible for mixing.
 */
enum class MusicalKey(
    val root: String,
    val scale: Scale,
    val camelot: String,
    val camelotIndex: Int
) {
    // Minor keys (A suffix in Camelot)
    A_MINOR("A", Scale.MINOR, "8A", 1),
    E_MINOR("E", Scale.MINOR, "9A", 2),
    B_MINOR("B", Scale.MINOR, "10A", 3),
    F_SHARP_MINOR("F♯", Scale.MINOR, "11A", 4),
    D_FLAT_MINOR("D♭", Scale.MINOR, "12A", 5),
    A_FLAT_MINOR("A♭", Scale.MINOR, "1A", 6),
    E_FLAT_MINOR("E♭", Scale.MINOR, "2A", 7),
    B_FLAT_MINOR("B♭", Scale.MINOR, "3A", 8),
    F_MINOR("F", Scale.MINOR, "4A", 9),
    C_MINOR("C", Scale.MINOR, "5A", 10),
    G_MINOR("G", Scale.MINOR, "6A", 11),
    D_MINOR("D", Scale.MINOR, "7A", 12),
    
    // Major keys (B suffix in Camelot)
    B_MAJOR("B", Scale.MAJOR, "8B", 13),
    F_SHARP_MAJOR("F♯", Scale.MAJOR, "9B", 14),
    D_FLAT_MAJOR("D♭", Scale.MAJOR, "10B", 15),
    A_FLAT_MAJOR("A♭", Scale.MAJOR, "11B", 16),
    E_FLAT_MAJOR("E♭", Scale.MAJOR, "12B", 17),
    B_FLAT_MAJOR("B♭", Scale.MAJOR, "1B", 18),
    F_MAJOR("F", Scale.MAJOR, "2B", 19),
    C_MAJOR("C", Scale.MAJOR, "3B", 20),
    G_MAJOR("G", Scale.MAJOR, "4B", 21),
    D_MAJOR("D", Scale.MAJOR, "5B", 22),
    A_MAJOR("A", Scale.MAJOR, "6B", 23),
    E_MAJOR("E", Scale.MAJOR, "7B", 24);
    
    /**
     * Returns a display string combining root and scale.
     * Example: "C Major" or "A Minor"
     */
    fun toDisplayString(): String {
        return "$root ${scale.name.lowercase().replaceFirstChar { it.uppercase() }}"
    }
    
    companion object {
        /**
         * Find a key by its Camelot notation (e.g., "8A", "5B")
         */
        fun fromCamelot(camelot: String): MusicalKey? {
            return entries.find { it.camelot == camelot }
        }
        
        /**
         * Find a key by its Camelot index (1-24)
         */
        fun fromCamelotIndex(index: Int): MusicalKey? {
            return entries.find { it.camelotIndex == index }
        }
        
        /**
         * Convert libKeyFinder key_t enum to Camelot index.
         * 
         * This mapping is handled natively in C++, so this function
         * is kept for reference and potential future use.
         * 
         * The native code returns Camelot indices directly (1-24).
         */
        @Deprecated("Mapping is done in native code")
        fun keyFinderToCamelotIndex(keyFinderKey: Int): Int {
            // This is now handled in native code (native-lib.cpp)
            // The JNI method returns Camelot index directly
            return keyFinderKey
        }
    }
}
