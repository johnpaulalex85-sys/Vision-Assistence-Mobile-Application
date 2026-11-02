package com.visionassistant.app

data class Alert(val message: String, val urgent: Boolean)

/**
 * Port of the backend's corridor + cooldown idea.
 * Distance is estimated from how much of the frame the box fills (no depth model).
 */
class HazardEngine(
    private val leftRatio: Float = 0.32f,
    private val rightRatio: Float = 0.68f,
    private val cooldownMs: Long = 3500,
    private val urgentCooldownMs: Long = 2000
) {
    private val hazards = setOf(
        "person", "bicycle", "car", "motorcycle", "bus", "truck", "train",
        "bench", "chair", "couch", "bed", "dining table", "potted plant",
        "dog", "cat", "horse", "fire hydrant", "stop sign", "suitcase", "backpack",
        "tv", "refrigerator", "toilet", "sink", "umbrella"
    )
    private val lastSpoken = HashMap<String, Long>()

    fun evaluateWall(wall: WallResult, nowMs: Long): Alert? {
        val urgent = wall.coverage >= 0.65f
        val wait = if (urgent) urgentCooldownMs else cooldownMs
        if (nowMs - (lastSpoken["wall"] ?: 0L) < wait) return null
        lastSpoken["wall"] = nowMs
        return Alert(if (urgent) "Stop. Wall ahead" else "Wall ahead", urgent)
    }

    private class Candidate(val d: Detection, val zone: String, val level: Int, val priority: Float)

    fun evaluate(dets: List<Detection>, nowMs: Long): Alert? {
        val candidates = dets.filter { it.label in hazards }.mapNotNull { d ->
            val cx = (d.x1 + d.x2) / 2f
            val area = (d.x2 - d.x1) * (d.y2 - d.y1)
            val height = d.y2 - d.y1
            val level = when {
                area > 0.30f || height > 0.75f -> 2   // very close
                area > 0.08f || height > 0.45f -> 1   // close
                else -> 0                              // far, ignore
            }
            if (level == 0) return@mapNotNull null
            val zone = when {
                cx < leftRatio -> "left"
                cx > rightRatio -> "right"
                else -> "ahead"
            }
            val inPath = if (zone == "ahead") 10f else 0f
            Candidate(d, zone, level, inPath + level * 3f + d.score)
        }
        val top = candidates.maxByOrNull { it.priority } ?: return null

        val urgent = top.level == 2 && top.zone == "ahead"
        val key = top.d.label + top.zone
        val wait = if (urgent) urgentCooldownMs else cooldownMs
        if (nowMs - (lastSpoken[key] ?: 0L) < wait) return null
        lastSpoken[key] = nowMs

        val where = when (top.zone) {
            "ahead" -> "ahead"
            else -> "on your ${top.zone}"
        }
        val msg = when {
            urgent -> "Stop. ${top.d.label} ${where}"
            top.level == 2 -> "${top.d.label} ${where}, very close"
            else -> "${top.d.label} ${where}, close"
        }
        return Alert(msg, urgent)
    }
}
