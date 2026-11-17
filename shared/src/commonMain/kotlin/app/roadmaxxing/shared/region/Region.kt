package app.roadmaxxing.shared.region

enum class RoadLayer { Major, Local }

/** A province (level 1) or district (level 2) of a region pack. */
data class Region(
    val id: Long,
    val level: Int,
    val parentId: Long?,
    val name: String,
    val nameTr: String?,
    val nameEn: String?,
    val lengthDmMajor: Long,
    val lengthDmLocal: Long,
) {
    val lengthDmAll: Long get() = lengthDmMajor + lengthDmLocal
}
