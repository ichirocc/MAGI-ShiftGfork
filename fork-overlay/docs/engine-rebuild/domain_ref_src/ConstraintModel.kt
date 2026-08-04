package com.magi.app.v6.engine.domain_ref

/**
 * 制約パラメータ（Problem が所有。identity マップは使わない）。
 */
class ConstraintConfig {
    var maxConsecutiveWork: Int = 5
    var minConsecutiveOff: Int = 1
    var nightShiftId: Int = 2
    var forbiddenAfterNight: Set<Int> = setOf(1)
    /** 週上限（任意。0 で無効）。主制約は 7 日周期平準化 */
    var maxWorkDaysPerWeek: Int = 0
    /** 7日周期（曜日）の勤務シフト平準化を有効化 */
    var enableWeeklyLeveling: Boolean = true
    var enableFair: Boolean = true
    var enableWeekly: Boolean = true
    var enableC3: Boolean = true
    var enableC3n: Boolean = true
    var enableC1: Boolean = true
    var enableC2: Boolean = true
    var enableApt: Boolean = true
    var enableGroup: Boolean = true
}

/** Problem 上の制約拡張フィールド（同一インスタンスに保持） */
class ConstraintExtras(S: Int, T: Int, K: Int) {
    val config: ConstraintConfig = ConstraintConfig()
    val skillOk: Array<BooleanArray> = Array(S) { BooleanArray(K) { true } }
    val staffGroup: IntArray = IntArray(S) { it % maxOf(1, S / 2) }
    val groupDemand: Array<IntArray> = Array(T) { IntArray(maxOf(1, S / 2)) { 0 } }
}

private val extrasLock = Any()
private val extras = java.util.WeakHashMap<Problem, ConstraintExtras>()

fun Problem.constraintExtras(): ConstraintExtras = synchronized(extrasLock) {
    extras.getOrPut(this) { ConstraintExtras(S, T, K) }
}

fun Problem.constraintConfig(): ConstraintConfig = constraintExtras().config

fun Problem.skillMatrix(): Array<BooleanArray> = constraintExtras().skillOk

fun Problem.staffGroup(): IntArray = constraintExtras().staffGroup

fun Problem.groupDemand(): Array<IntArray> = constraintExtras().groupDemand

fun Problem.setSkill(staff: Int, shift: Int, ok: Boolean) {
    skillMatrix()[staff][shift] = ok
}

fun Problem.setStaffGroup(staff: Int, g: Int) {
    staffGroup()[staff] = g
}

fun Problem.setGroupMin(day: Int, group: Int, min: Int) {
    val gd = groupDemand()
    if (day in gd.indices && group in gd[day].indices) gd[day][group] = min
}

fun Problem.installFullConstraintDemo() {
    val cfg = constraintConfig()
    cfg.maxConsecutiveWork = 4
    cfg.minConsecutiveOff = 1
    cfg.nightShiftId = if (K > 2) 2 else -1
    cfg.forbiddenAfterNight = if (K > 1) setOf(1) else emptySet()
    cfg.maxWorkDaysPerWeek = 0 // 平準化が主; 上限は任意
    if (K > 2 && S > 0) setSkill(0, K - 1, false)
    for (i in 0 until S) setStaffGroup(i, i % 2)
    for (d in 0 until T) {
        setGroupMin(d, 0, 1)
        if (S > 1) setGroupMin(d, 1, 1)
    }
}
