package com.jepongdevxyz.remote.core

import org.json.JSONObject

/**
 * JSON wire protocol for the "devxyz" DataChannel (control/chat/meta).
 *
 * Both sides MUST build and parse with these helpers so the strings match
 * exactly. Coordinates are normalized 0..1 floats (fraction of the
 * controlled device's screen).
 */
object Protocol {

    /** Sent by the controlled side once the peer connection is up. */
    fun hello(w: Int, h: Int, dpi: Int, name: String): String =
        JSONObject().apply {
            put("t", "hello")
            put("w", w)
            put("h", h)
            put("dpi", dpi)
            put("name", name)
        }.toString()

    /** x, y normalized 0..1 */
    fun tap(x: Float, y: Float): String =
        JSONObject().apply {
            put("t", "tap")
            put("x", x.toDouble())
            put("y", y.toDouble())
        }.toString()

    /** x1..y2 normalized 0..1, durMs gesture duration */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Int): String =
        JSONObject().apply {
            put("t", "swipe")
            put("x1", x1.toDouble())
            put("y1", y1.toDouble())
            put("x2", x2.toDouble())
            put("y2", y2.toDouble())
            put("durMs", durMs)
        }.toString()

    /** dx, dy normalized 0..1 scroll deltas */
    fun scroll(dx: Float, dy: Float): String =
        JSONObject().apply {
            put("t", "scroll")
            put("dx", dx.toDouble())
            put("dy", dy.toDouble())
        }.toString()

    /** Type text into the currently focused editable field. */
    fun text(v: String): String =
        JSONObject().apply {
            put("t", "text")
            put("v", v)
        }.toString()

    /** System action. name must be one of: back | home | recents */
    fun action(name: String): String {
        require(name == "back" || name == "home" || name == "recents") {
            "action name must be back|home|recents, was: $name"
        }
        return JSONObject().apply {
            put("t", "action")
            put("name", name)
        }.toString()
    }

    /** Set the controlled device's clipboard. */
    fun clip(v: String): String =
        JSONObject().apply {
            put("t", "clip")
            put("v", v)
        }.toString()

    /** In-session chat message. ts = epoch millis of the sender. */
    fun chat(v: String, ts: Long): String =
        JSONObject().apply {
            put("t", "chat")
            put("v", v)
            put("ts", ts)
        }.toString()

    /** Graceful hangup. */
    fun bye(): String =
        JSONObject().apply {
            put("t", "bye")
        }.toString()

    // ---- file transfer (sent on the "devxyz-file" DataChannel) ----

    /** Announces an outgoing file: {"t":"fmeta","id","name","size","mime"} */
    fun fmeta(id: String, name: String, size: Long, mime: String): String =
        JSONObject().apply {
            put("t", "fmeta")
            put("id", id)
            put("name", name)
            put("size", size)
            put("mime", mime)
        }.toString()

    /** One 16KB chunk, base64-encoded: {"t":"fchunk","id","seq","data"} */
    fun fchunk(id: String, seq: Int, base64: String): String =
        JSONObject().apply {
            put("t", "fchunk")
            put("id", id)
            put("seq", seq)
            put("data", base64)
        }.toString()

    /** Acknowledgement for one chunk: {"t":"fack","id","seq"} */
    fun fack(id: String, seq: Int): String =
        JSONObject().apply {
            put("t", "fack")
            put("id", id)
            put("seq", seq)
        }.toString()

    /**
     * Parses any protocol message into a map. "t" is always a String when
     * present; every JSON number is normalized to Double, booleans stay
     * Boolean, everything else becomes String. Returns an empty map for
     * malformed input (callers treat missing "t" as unknown).
     */
    fun parse(json: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.opt(k)
                out[k] = when {
                    v == null || v === JSONObject.NULL -> null
                    v is Number -> v.toDouble()
                    v is Boolean -> v
                    else -> v.toString()
                }
            }
        } catch (e: Exception) {
            // malformed: return what we have (possibly empty)
        }
        return out
    }
}
