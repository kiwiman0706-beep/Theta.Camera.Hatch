package com.theta.camera.hatch

/** One request per physical closed -> fully-open cycle; no retry loop after denial. */
class FoldTrigger {
    var armed = false
        private set
    fun update(angle: Float): Boolean {
        if (angle < 15f) { armed = true; return false }
        if (angle >= 155f && armed) { armed = false; return true }
        return false
    }
    fun reset() { armed = false }
}
