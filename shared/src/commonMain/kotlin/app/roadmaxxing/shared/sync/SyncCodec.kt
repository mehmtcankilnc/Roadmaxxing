package app.roadmaxxing.shared.sync

/**
 * Schema-versioned plain-map documents for Firestore (`v` = [SCHEMA_VERSION]). Firestore hands numbers back as
 * Long or Double, so decoding accepts any [Number]. Documents with another version decode to null (ignored, never
 * guessed at). Raw GPS fixes are never part of a document.
 *
 * Drive document: `v, start, end?, verdict?, dist?, dur?, avg?, max?, newDm?, route (polyline), segs (delta varints)`.
 * Profile document: `v, updatedAt, settings {key: value}, zones [{k, n?, la, lo, r}]`.
 */
object SyncCodec {
    const val SCHEMA_VERSION = 1

    /** Hard cap for one drive document (Firestore allows 1 MiB). Route detail is dropped first, never segment ids. */
    const val MAX_DRIVE_DOC_BYTES = 900_000

    private const val FIXED_OVERHEAD = 400

    fun encodeDrive(d: RemoteDrive): Map<String, Any?> {
        val segs = SyncEncoding.encodeIds(d.firstDrivenSegmentIds)
        var route = d.route
        var routeText = SyncEncoding.encodeRoute(route)
        while (route.isNotEmpty() && segs.length + routeText.length + FIXED_OVERHEAD > MAX_DRIVE_DOC_BYTES) {
            val current = route
            route = if (current.size <= 2) emptyList() else current.filterIndexed { i, _ -> i % 2 == 0 || i == current.lastIndex }
            routeText = SyncEncoding.encodeRoute(route)
        }
        val m = LinkedHashMap<String, Any?>()
        m["v"] = SCHEMA_VERSION.toLong()
        m["start"] = d.startMs
        d.endMs?.let { m["end"] = it }
        d.verdict?.let { m["verdict"] = it }
        d.distanceM?.let { m["dist"] = it }
        d.durationMs?.let { m["dur"] = it }
        d.avgSpeedMps?.let { m["avg"] = it }
        d.maxSpeedMps?.let { m["max"] = it }
        d.newLengthDm?.let { m["newDm"] = it }
        m["route"] = routeText
        m["segs"] = segs
        return m
    }

    fun decodeDrive(syncId: String, m: Map<String, Any?>): RemoteDrive? {
        if (syncId.isEmpty() || m.long("v") != SCHEMA_VERSION.toLong()) return null
        val start = m.long("start") ?: return null
        val route = SyncEncoding.decodeRoute(m["route"] as? String ?: "") ?: return null
        val segs = SyncEncoding.decodeIds(m["segs"] as? String ?: "") ?: return null
        return RemoteDrive(
            syncId = syncId, startMs = start, endMs = m.long("end"), verdict = m["verdict"] as? String,
            distanceM = m.double("dist"), durationMs = m.long("dur"), avgSpeedMps = m.double("avg"),
            maxSpeedMps = m.double("max"), newLengthDm = m.long("newDm"),
            route = route, firstDrivenSegmentIds = segs,
        )
    }

    fun encodeProfile(p: RemoteProfile): Map<String, Any?> = mapOf(
        "v" to SCHEMA_VERSION.toLong(),
        "updatedAt" to p.updatedAtMs,
        "settings" to p.settings.entries.sortedBy { it.key }.associate { it.key to it.value },
        "zones" to p.zones.map { z ->
            buildMap<String, Any?> {
                put("k", z.kind)
                z.name?.let { put("n", it) }
                put("la", z.lat)
                put("lo", z.lon)
                put("r", z.radiusM.toLong())
            }
        },
    )

    fun decodeProfile(m: Map<String, Any?>): RemoteProfile? {
        if (m.long("v") != SCHEMA_VERSION.toLong()) return null
        val updated = m.long("updatedAt") ?: return null
        val settings = HashMap<String, String>()
        (m["settings"] as? Map<*, *>)?.forEach { (k, v) ->
            if (k is String && v is String) settings[k] = v
        }
        val zones = ArrayList<RemoteZone>()
        for (raw in m["zones"] as? List<*> ?: emptyList<Any?>()) {
            val z = raw as? Map<*, *> ?: return null
            val lat = (z["la"] as? Number)?.toDouble() ?: return null
            val lon = (z["lo"] as? Number)?.toDouble() ?: return null
            val r = (z["r"] as? Number)?.toInt() ?: return null
            zones.add(RemoteZone(z["k"] as? String ?: "CUSTOM", z["n"] as? String, lat, lon, r))
        }
        return RemoteProfile(updated, settings, zones)
    }

    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
}
