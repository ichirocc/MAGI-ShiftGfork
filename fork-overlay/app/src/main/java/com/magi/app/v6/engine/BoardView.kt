package com.magi.app.v6.engine

/**
 * Move 生成が必要とする最小の盤面ビュー。
 * SearchSession / SearchSessionFull の両方から実装できる。
 */
interface BoardView {
    val version: Long
    val current: Array<IntArray>
}
