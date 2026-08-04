package com.magi.app.v6.engine.config

/** エンジン内研磨用の制約パラメータ（main Checker が正。ここは探索ヒント） */
class ConstraintConfig {
    var enableWeekly: Boolean = true
    var enableWeeklyLeveling: Boolean = true
    var maxWorkDaysPerWeek: Int = 0
    var enableC3: Boolean = true
    var maxConsecutiveWork: Int = 5
    var minConsecutiveOff: Int = 2
    var enableFair: Boolean = true
    var nightShiftId: Int = -1
    var forbiddenAfterNight: IntArray = intArrayOf()
}
