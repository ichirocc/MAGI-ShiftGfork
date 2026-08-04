package com.magi.app.v6.engine

/**
 * writes の正規化を生成側で強制する完成ユーティリティ。
 * tryTransition は再検証するが、ここで弾いて無駄な評価を減らす。
 */
object MoveNormalizer {
    /**
     * @return 正規化済み Move、または不正なら null
     */
    fun normalize(
        baseVersion: Long,
        rawWrites: IntArray,
        family: String,
        source: String,
        S: Int,
        T: Int,
        K: Int,
    ): Move? {
        if (rawWrites.isEmpty() || rawWrites.size % 3 != 0) return null
        data class Cell(val s: Int, val d: Int, val sh: Int)
        val cells = ArrayList<Cell>(rawWrites.size / 3)
        val seen = HashSet<Long>()
        var i = 0
        while (i < rawWrites.size) {
            val s = rawWrites[i]
            val d = rawWrites[i + 1]
            val sh = rawWrites[i + 2]
            i += 3
            if (s !in 0 until S || d !in 0 until T || sh !in 0 until K) return null
            val key = (s.toLong() shl 32) or (d.toLong() and 0xffffffffL)
            if (!seen.add(key)) return null
            cells.add(Cell(s, d, sh))
        }
        cells.sortWith(compareBy({ it.s }, { it.d }))
        val out = IntArray(cells.size * 3)
        for (idx in cells.indices) {
            out[idx * 3] = cells[idx].s
            out[idx * 3 + 1] = cells[idx].d
            out[idx * 3 + 2] = cells[idx].sh
        }
        return Move(baseVersion, out, family, source)
    }
}
