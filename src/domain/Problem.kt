package com.magi.app.v6

/**
 * 勤務表問題定義（スタンドアロン実装）。
 * 本番接続時はアプリ側の同名型に差し替え、このファイルはビルドから外す。
 *
 * シフト ID 規約（デモ既定）:
 *   0 = 休, 1 = 日勤, 2 = 夜勤, …（いずれも通常のシフト種。休を OFF 特殊値として扱わない）
 */
class Problem(
    val S: Int,
    val T: Int,
    val K: Int,
) {
    init {
        require(S > 0 && T > 0 && K > 0) { "S,T,K must be positive" }
        require(T <= 64) { "BitMasks path assumes T<=64; got T=$T" }
        require(S <= 64) { "BitMasks path assumes S<=64; got S=$S" }
    }

    /** [staff][day] = true なら希望固定（書き換え禁止） */
    val wishLock: Array<BooleanArray> = Array(S) { BooleanArray(T) }

    /** [staff][shift] 担当可否 */
    val canDoMask: Array<BooleanArray> = Array(S) { BooleanArray(K) { true } }

    /** 職員×シフトの回数範囲。未設定は MIN/MAX */
    val rangeLo: Array<IntArray> = Array(S) { IntArray(K) { Int.MIN_VALUE } }
    val rangeHi: Array<IntArray> = Array(S) { IntArray(K) { Int.MAX_VALUE } }

    /**
     * 日ごとの必要人数（勤務シフト = shift>=1 の合計）。
     * 長さ T。未設定日は 0。
     */
    val dayDemand: IntArray = IntArray(T)

    /**
     * 日×シフト別の需要（任意）。[day][shift]、shift0 は通常 0。
     * null なら dayDemand のみ使う。
     */
    var shiftDemand: Array<IntArray>? = null

    /** 希望シフト [staff][day] = 希望 shift、負なら無し */
    val preferred: Array<IntArray> = Array(S) { IntArray(T) { -1 } }

    fun wishLocked(staff: Int, day: Int): Boolean =
        staff in 0 until S && day in 0 until T && wishLock[staff][day]

    fun canDo(staff: Int, shift: Int): Boolean =
        staff in 0 until S && shift in 0 until K && canDoMask[staff][shift]

    fun allowedShiftsForStaff(staff: Int): List<Int> {
        if (staff !in 0 until S) return emptyList()
        return (0 until K).filter { canDoMask[staff][it] }
    }

    fun setExactCount(staff: Int, shift: Int, count: Int) {
        require(staff in 0 until S && shift in 0 until K)
        rangeLo[staff][shift] = count
        rangeHi[staff][shift] = count
    }

    fun setRange(staff: Int, shift: Int, lo: Int, hi: Int) {
        require(staff in 0 until S && shift in 0 until K && lo <= hi)
        rangeLo[staff][shift] = lo
        rangeHi[staff][shift] = hi
    }

    fun lockWish(staff: Int, day: Int, shift: Int) {
        require(staff in 0 until S && day in 0 until T)
        wishLock[staff][day] = true
        preferred[staff][day] = shift
    }

}
