package com.vicuna.clicker.root.api

/** A process-wide lease shared by manual and API execution. */
object ExecutionGate {
    private var owner: Any? = null
    @Synchronized fun acquire(candidate: Any): Boolean {
        if (owner != null && owner !== candidate) return false
        owner = candidate
        return true
    }
    @Synchronized fun release(candidate: Any) {
        if (owner === candidate) owner = null
    }
}
