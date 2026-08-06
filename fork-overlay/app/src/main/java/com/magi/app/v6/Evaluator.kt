package com.magi.app.v6

/** [レビュー#1 3.213.0] 辞書式パック score = hard × SCORE_HARD_UNIT + soft の HARD 桁単位。
 *  soft がこの値以上になると hard/soft の分解・比較（split / SA の HARD ゲート / LAHC / GLS）が壊れる。
 *  実機実測 soft は ~2e3 だが理論上限を強制しないままだったため、余裕を 1e6→1e9 へ拡大（Long 上限まで
 *  hard ~9e9 の余地＝実データ規模の hard 数千に対し十分）。C++ (magi_native.cpp の SaChunk::M と
 *  リテラル 1000000000LL 群) と必ず同期させること（乖離は2層番兵＋native-parity CI が検出）。 */
const val SCORE_HARD_UNIT = 1_000_000_000L

/**
 * [3.318.0] c42/c42s の「同じ日に同時発生している禁止ペア」の数。**チェッカー・評価器・Δ評価器・C++ の
 * 共通ソース**（4面が同じ式を持つ必要があるため、Kotlin 側はこの1関数へ集約する）。
 *
 * left = 群 g1 で s1 に就いている職員、right = 群 g2 で s2 に就いている職員。両者が互いに素なら
 * ペア数は素直に |left|×|right| でよい。**両者が同じ集合になるのは g1==g2 かつ s1==s2 のときだけ**で
 * （s1!=s2 なら同じ職員が同日に両方へ就くことはなく、g1!=g2 なら sgrp が違うので両方には入らない）、
 * そのとき素朴な積 n² は ①自分自身とのペア n 件 ②同じペアを (a,b) と (b,a) で2回、を余分に数える。
 *
 * 実データで確認した実害（HF77 ユーザー明示指示により修正）: `群9/休 × 群9/休` の行が**1人だけの群**に
 * 付いており、その人が休むたび自己ペアを1件ずつ数えていた。real は c42=16 のうち **9件**、user は 13 の
 * うち **8件** がこれ。異なる2人のペア数＝C(n,2) が正しい（順序重複は当該データでは 0 件だが、同一集合の
 * 群に2人以上いれば同じ理由で二重計上になるため合わせて是正する）。
 */
internal fun c42PairCount(sameSet: Boolean, n1: Int, n2: Int): Long =
    if (sameSet) n1.toLong() * (n1 - 1) / 2 else n1.toLong() * n2

/**
 * Faithful port of the Web worker's `fullEval`.
 *
 * Lexicographic objective:  score = hard1 * SCORE_HARD_UNIT + soft
 *   hard1 = c3n (forbidden seq) + covU (per-cell OR/AND shortfall over P1/P2, #4b) + pref
 *           + groupViol (担当できないシフトに就いているセル。3.318.0 でチェッカーの MirrorKeys.hard と揃えた)
 *   soft  = c1 (window) + c2 (per-staff total) + c41 (group/day range)
 *           + c42 (group pair conflict) + c41s/c42s (skill-group変種) + c3 (want seq) + c3m + c3mn
 *           + [統一a/b] low/high (range, amount×90/45) + covO (over-coverage, amount)
 *   ※ range と covO は UnifiedViolationChecker と同分類(SOFT)・同重み。Web betterVec の lim 層は
 *     soft 内の高重み(90/45)として表現（hard1 は ×SCORE_HARD_UNIT で常に優先）。
 *
 * The solution `a[i][j]` is the assigned shift index (exactly one shift per cell),
 * the equivalent of the Web's one-hot `x[i][j][k] === 1`.
 *
 * Phase 1 recomputes the whole objective per candidate (no BIT-DELTA). It is exact;
 * native speed absorbs the cost. Δ-evaluation is a later optimization.
 */
class Evaluator(private val p: Problem, private val c3RunMode: Boolean = true) {

    fun fullEval(a: Array<IntArray>): Long {
        val v = fullEvalParts(a)
        // [3.336.0/敵対レビュー H10] 辞書式パックは soft < SCORE_HARD_UNIT を前提にする（超えると
        //   hard へ繰り上がり、SA/LAHC の HARD ゲートが静かに壊れる）。実運用（30名×31日）の実測は
        //   soft ~2e3 で 1e9 に遠く及ばないが、**契約として一度も検査していなかった**。重みの変更
        //   （HF77）や制約の大量複製で膨らんだときに、原因不明の挙動でなく明示的な失敗にする。
        check(v[1] in 0 until SCORE_HARD_UNIT) {
            "soft=${v[1]} が辞書式パックの桁(${SCORE_HARD_UNIT})を超えました。重みか制約数を見直してください"
        }
        return v[0] * SCORE_HARD_UNIT + v[1]
    }

    /** [監査#7] hard/soft を分離して返す（soft の SCORE_HARD_UNIT 桁溢れ＝辞書式崩壊の診断用）。fullEval はこの合成で挙動不変。 */
    fun fullEvalParts(a: Array<IntArray>): LongArray {
        // [MAGI-ShiftGfork] フル評価ホットパスを C++ nativeFullEval へ。不一致・未ロード時のみ Kotlin。
        NativeFullEval.tryParts(a)?.let { return it }
        NativeFullEval.noteKotlinFallback()
        val S = p.S; val T = p.T; val K = p.K
        var hard1 = 0L
        var soft = 0L

        // c1: every window of length day1 must contain >= day2 of shiftIdx
        // [統一] (1)担当不可スタッフは対象外(canDoガード=チェッカーと一致、解消不能な幻の違反を除去)、
        //   (2)#fire 計上(soft += 1*重み15)。旧: 全スタッフ・soft += d1(フラット)。
        // [HF77明示数値指示(2026-07-20)] 窓の要件(c1)の重みを4→5→15に変更。
        for (c in p.cons1) {
            val d1 = c.day1; val si = c.shiftIdx; val d2 = c.day2
            for (i in 0 until S) {
                if (!p.canDo(i, si)) continue
                var j = 0
                while (j <= T - d1) {
                    var z = 0
                    var l = 0
                    while (l < d1) { if (a[i][j + l] == si) z++; l++ }
                    if (z < d2) soft += 15L
                    j++
                }
            }
        }

        // c2: per-staff total of a shift must reach count
        for (c in p.cons2) {
            for (i in 0 until S) {
                if (!p.canDo(i, c.shiftIdx)) continue   // [監査#5] 担当不可の職員は対象外（チェッカーと同一条件）
                var z = 0
                for (j in 0 until T) if (a[i][j] == c.shiftIdx) z++
                if (z < c.count) soft += 1
            }
        }

        // c41: per-day, count of (group, shift) must lie in [l, u]
        for (c in p.cons41) {
            for (j in 0 until T) {
                var z = 0
                for (i in 0 until S) if (p.sgrp[i] == c.groupIdx && a[i][j] == c.shiftIdx) z++
                if (z < c.l || c.u < z) soft += 1
            }
        }

        // c42: per-day, (g1,s1) co-occurring with (g2,s2) is penalized per pair
        for (c in p.cons42) {
            for (j in 0 until T) {
                var n1 = 0; var n2 = 0
                for (i in 0 until S) {
                    if (p.sgrp[i] == c.g1 && a[i][j] == c.s1) n1++
                    if (p.sgrp[i] == c.g2 && a[i][j] == c.s2) n2++
                }
                soft += c42PairCount(c.g1 == c.g2 && c.s1 == c.s2, n1, n2)
            }
        }

        // c41s / c42s: スキルグループ版（ssk = スキル群index。既存 sgrp とは独立）。罰則は c41/c42 と同等(soft)。
        for (c in p.cons41s) {
            for (j in 0 until T) {
                var z = 0
                for (i in 0 until S) if (p.ssk[i] == c.groupIdx && a[i][j] == c.shiftIdx) z++
                if (z < c.l || c.u < z) soft += 1
            }
        }
        for (c in p.cons42s) {
            for (j in 0 until T) {
                var n1 = 0; var n2 = 0
                for (i in 0 until S) {
                    if (p.ssk[i] == c.g1 && a[i][j] == c.s1) n1++
                    if (p.ssk[i] == c.g2 && a[i][j] == c.s2) n2++
                }
                soft += c42PairCount(c.g1 == c.g2 && c.s1 == c.s2, n1, n2)
            }
        }

        // c3 family — [統一] UnifiedViolationChecker と同じ重み(c3=3/c3m=2/c3mn=15)を soft に適用。
        // c3n は forbidden=HARD として hard1(count, ×1e6) のまま。窓マッチは #fire 計上(後述の sub += 1)。
        // [HF77明示数値指示(2026-07-20)] 回避の並び(c3mn)の重みを12→15に変更。
        soft += c3check(a, p.cons3, false) * 3L
        hard1 += c3check(a, p.cons3n, true)    // forbidden -> display HARD (count)
        soft += c3check(a, p.cons3m, false) * 2L
        soft += c3check(a, p.cons3mn, true) * 15L

        // pref: wished cell not honored -> display HARD（[監査#11②] 実現可能な希望のみ計上。不可能希望は計数から対称除外）
        for (i in 0 until S) for (j in 0 until T) {
            val w = p.wish[i][j]
            if (w >= 0 && p.canDo(i, w) && a[i][j] != w) hard1 += 1
            // [3.318.0] groupViol（担当できないシフトに就いているセル）も HARD へ。`MirrorKeys.hard` は
            //   元から4族（groupViol/c3n/covU/pref）なのに評価器だけ3族で、同じ盤面に対してチェッカーと
            //   評価器が違う hard を返していた（SA の受理と最終採否が別基準）。実データでは入口の
            //   hf67HardRepair が群外セルを正規化するため通常 0 に落ちるが、契約の非対称は残っていた。
            val k = a[i][j]
            if (k in 0 until K && !p.canDo(i, k)) hard1 += 1
        }

        // [統一a/b] range (LimMin/LimMax) は SOFT。UnifiedViolationChecker と同じ amount×重み(low=90/high=45)・
        // 同じガード(lo!=0, low は canDo 必須)。旧実装は hard2(=表示HARD) として +1 計上していた。
        val ssn = Array(S) { IntArray(K) }
        // [レビュー#7 3.213.0] normalizeSchedule は不正セルを -1 に写像する（MirrorCore:476）。
        //   旧: 無ガードの ssn[i][a[i][j]]++ が -1 で ArrayIndexOutOfBoundsException。C++ fullEvalParts
        //   （3.199.0 で全面ガード済＝範囲外セルはスキップ）と同じ意味論へ対称化する。
        for (i in 0 until S) for (j in 0 until T) { val k = a[i][j]; if (k in 0 until K) ssn[i][k]++ }
        for (i in 0 until S) for (k in 0 until K) {
            val lo = p.rangeLo[i][k]; val hi = p.rangeHi[i][k]
            val n = ssn[i][k]
            if (lo != Int.MIN_VALUE && lo != 0 && n < lo && p.canDo(i, k)) soft += (lo - n).toLong() * 90L
            if (hi != Int.MAX_VALUE && n > hi) soft += (n - hi).toLong() * 45L
            // [統一apt] 適切回数(双方向目標) SOFT・重み1・L1偏差|n-t|。UnifiedViolationChecker の "apt" と一致。
            val t = p.apt[i][k]
            if (t >= 0) soft += kotlin.math.abs(n - t).toLong()
        }

        // [統一fair] グループ内公平化 SOFT・重み1。群×担当ONシフトごと、メンバー回数の round(平均) からの
        // L1偏差和。同群の職員間で各シフト回数を均す（UnifiedViolationChecker の "fair" と一致）。
        for (g in 0 until p.G) {
            val mem = p.groupMembers[g]
            val m = mem.size
            if (m < 2) continue
            for (k in p.bucket[g]) {
                var sum = 0
                for (x in mem) sum += ssn[x][k]
                val tgt = Math.round(sum.toDouble() / m).toInt()
                for (x in mem) soft += kotlin.math.abs(ssn[x][k] - tgt).toLong()
            }
        }

        // [統一weekly] 7日周期のシフト平準化 SOFT・重み1。職員ごと**シフトごと**に、そのシフトが入る日の
        // 曜日別カウントの round(回数/7) からの L1偏差和（UnifiedViolationChecker の "weekly" と一致）。
        // [3.345.0] 休も1シフトとして数える（旧: 勤務日=非休の二値）。
        for (i in 0 until S) {
            val wd = Array(K) { IntArray(7) }
            for (j in 0 until T) { val k = a[i][j]; if (k in 0 until K) wd[k][(p.dow0 + j) % 7]++ }
            for (k in 0 until K) soft += weeklyDevOfBucket(wd[k]).toLong()
        }

        // [監査#4b] 被覆は per-cell OR/AND（VBA本家=Web HF574 と三面統一）。共有ヘルパで Δ/Checker と同式。
        //   旧: 総量min（#4のhasP2式）は「日毎OR」の業務意味と不一致（大域コミット強制）だったため置換。
        var covU = 0L
        for (j in 0 until T) {
            for (k in 0 until K) {
                var dsn = 0
                for (i in 0 until S) if (a[i][j] == k) dsn++
                covU += p.covUCell(k, j, dsn)
                soft += p.covOCell(k, j, dsn).toLong()
            }
        }
        hard1 += covU

        return longArrayOf(hard1, soft)
    }

    /** Returns the hard / soft split for display (運用違反 vs SOFT). */
    fun split(score: Long): Pair<Long, Long> = (score / SCORE_HARD_UNIT) to (score % SCORE_HARD_UNIT)

    private fun c3check(a: Array<IntArray>, list: List<C3>, forbidden: Boolean): Long {
        val S = p.S; val T = p.T
        var sub = 0L
        for (c in list) {
            val seq = c.seq
            val D = seq.size
            if (D == 0) continue
            val first = seq[0]
            // [HF507] non-forbidden single-shift run -> run deficit (per staff whole-row)
            if (!forbidden && c3RunMode && C3Run.isSingleShiftSeq(seq)) {
                for (i in 0 until S) sub += C3Run.rowDeficit(a, i, first, D)
                continue
            }
            for (i in 0 until S) {
                var j = 0
                while (j <= T - D) {
                    if (a[i][j] == first) {
                        var z = 0
                        var l = 1
                        while (l < D) { if (a[i][j + l] == seq[l]) z++; l++ }
                        val fire = if (forbidden) (z == D - 1) else (z < D - 1)
                        if (fire) sub += 1   // [統一] #fire 計上(チェッカー inc(key,1) と一致)。重みは呼び出し側で適用
                    }
                    j++
                }
            }
        }
        return sub
    }
}
