package app.maestri.wire

import org.json.JSONObject

/**
 * Presence of one device in the workspace, as far as a client can know it.
 *
 * The contract documents presence as client -> server only ("o host desenha um cursor para ele no
 * seu canvas, na cor do dispositivo e com o nome dele"): the host draws remote cursors, and no peer
 * roster is delivered to clients. Peers here are therefore whatever the host chooses to send on the
 * feed socket — parsed tolerantly, rendered while fresh, absent otherwise. Self is rendered locally
 * from the last report we sent.
 */
data class PresencePeer(
    val id: String,
    val name: String,
    val color: String? = null,
    val nodeId: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val atMillis: Long,
) {
    val hasPoint: Boolean get() = x != null && y != null

    /** Fresh within [STALE_MS]; older reports are dropped by the canvas. */
    fun freshAt(now: Long): Boolean = now - atMillis <= STALE_MS

    companion object {
        const val STALE_MS = 20_000L

        /** Tolerant parse of a feed frame; null when the frame carries no position at all. */
        fun parse(msg: JSONObject, selfDeviceId: String?, selfLabel: String, now: Long): PresencePeer? {
            val point = msg.optJSONObject("point")
            val nodeId = msg.optString("nodeId").takeIf { it.isNotBlank() }
            val hasX = point != null && point.has("x")
            val hasY = point != null && point.has("y")
            if (nodeId == null && !(hasX && hasY)) return null
            val deviceId = msg.optString("deviceId").takeIf { it.isNotBlank() }
            val isSelf = deviceId != null && deviceId == selfDeviceId
            val name = msg.optString("deviceName").takeIf { it.isNotBlank() }
                ?: msg.optString("name").takeIf { it.isNotBlank() }
                ?: if (isSelf) selfLabel else "device"
            return PresencePeer(
                id = deviceId ?: nodeId ?: name,
                name = name,
                color = msg.optString("color").takeIf { it.isNotBlank() },
                nodeId = nodeId,
                x = if (hasX) point!!.optDouble("x") else null,
                y = if (hasY) point!!.optDouble("y") else null,
                atMillis = now,
            )
        }
    }
}
