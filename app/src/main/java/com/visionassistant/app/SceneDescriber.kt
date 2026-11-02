package com.visionassistant.app

/** Template-based scene description (replaces the backend's Qwen paragraph, which cannot run on a phone). */
object SceneDescriber {
    private fun zone(d: Detection): String {
        val cx = (d.x1 + d.x2) / 2f
        return if (cx < 0.32f) "left" else if (cx > 0.68f) "right" else "ahead"
    }

    private fun plural(label: String) = when {
        label == "person" -> "people"
        label.endsWith("s") || label.endsWith("ch") || label.endsWith("sh") || label.endsWith("x") -> label + "es"
        else -> label + "s"
    }

    private fun count(label: String, n: Int): String =
        if (n == 1) (if (label[0] in "aeiou") "an $label" else "a $label") else "$n ${plural(label)}"

    private fun where(zones: List<String>): String {
        val names = zones.map { if (it == "ahead") "ahead" else "on your $it" }
        return when (names.size) {
            1 -> names[0]
            2 -> names.joinToString(" and ")
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    fun describe(dets: List<Detection>, wall: WallResult?): String {
        if (dets.isEmpty() && wall == null) return "I do not see anything notable in front of you."
        val order = listOf("left", "ahead", "right")
        val groups = dets.groupBy { it.label }.entries
            .sortedByDescending { e -> e.value.maxOf { (it.x2 - it.x1) * (it.y2 - it.y1) } }
            .take(6)
        val parts = groups.map { (label, list) ->
            val zones = list.map(::zone).distinct().sortedBy { order.indexOf(it) }
            "${count(label, list.size)} ${where(zones)}"
        }
        val sb = StringBuilder()
        if (parts.isNotEmpty()) sb.append("I can see ").append(parts.joinToString("; ")).append(". ")
        if (wall != null) sb.append(if (wall.coverage >= 0.65f) "There is a wall very close ahead." else "There is a wall ahead.")
        return sb.toString().trim()
    }
}
