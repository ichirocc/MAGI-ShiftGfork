package com.magi.app.v6
import java.util.WeakHashMap

import com.magi.app.v6.engine.config.ConstraintConfig

/**
 * main [Problem] をエンジン API に合わせる拡張（同一 package で import 不要）。
 */

fun Problem.canDo(staff: Int, shift: Int): Boolean {
    if (staff !in 0 until S || shift !in 0 until K) return false
    val g = sgrp.getOrNull(staff) ?: return false
    return bucket.getOrNull(g)?.contains(shift) == true
}

fun Problem.allowedShiftsForStaff(staff: Int): List<Int> {
    if (staff !in 0 until S) return emptyList()
    val g = sgrp.getOrNull(staff) ?: return emptyList()
    return bucket.getOrNull(g)?.toList() ?: emptyList()
}

fun Problem.wishLocked(staff: Int, day: Int): Boolean {
    if (staff !in 0 until S || day !in 0 until T) return false
    val w = wish[staff][day]
    if (w < 0) return false
    return canDo(staff, w)
}

val Problem.preferred: Array<IntArray> get() = wish

val Problem.dayDemand: IntArray
    get() {
        val out = IntArray(T)
        for (j in 0 until T) {
            var sum = 0
            for (k in 0 until K) {
                if (k == restIdx) continue
                val n = need1[k][j]
                if (n > 0) sum += n
            }
            out[j] = sum
        }
        return out
    }

val Problem.shiftDemand: Array<IntArray> get() = need1

fun Problem.staffGroup(): IntArray = sgrp.copyOf()

fun Problem.groupDemand(): Array<IntArray> = Array(T) { IntArray(G) { 0 } }

val Problem.skillMatrix: Array<BooleanArray>
    get() = Array(S) { s -> BooleanArray(K) { k -> canDo(s, k) } }

private val configCache = java.util.Collections.synchronizedMap(WeakHashMap<Problem, ConstraintConfig>())

fun Problem.constraintConfig(): ConstraintConfig =
    configCache.getOrPut(this) {
        ConstraintConfig().also {
            it.enableWeekly = true
            it.enableC3 = true
        }
    }
