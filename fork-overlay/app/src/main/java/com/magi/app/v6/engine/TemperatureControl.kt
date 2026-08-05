package com.magi.app.v6.engine

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * SA 温度スケジュールの完成形。
 *
 * 契約:
 * - Metropolis 受理判定は [Metropolis.accept] に一元化（Session からも参照可）
 * - 冷却は幾何減衰が既定。受理率が低すぎるときだけ再加熱
 * - 再加熱は current 悪化探索用。best は Session 側の better のみ更新
 */
data class TemperatureParams(
    /** 初期温度。0 以下は自動（初期スコア差スケール） */
    val t0: Double = 1.0,
    /** 幾何冷却係数 (0,1) */
    val alpha: Double = 0.9995,
    /** これ未満は事実上ゼロ（悪化受理しない） */
    val tMin: Double = 1e-12,
    /** 再加熱後の温度上限（t0 比） */
    val reheatFactor: Double = 0.5,
    /** 受理率がこの閾値未満が続いたら再加熱を検討 */
    val lowAcceptRate: Double = 0.02,
    /** 受理率監視ウィンドウ（試行数） */
    val acceptWindow: Int = 200,
    /** 再加熱の最小間隔（試行数） */
    val reheatCooldownoutIters: Long = 2_000L,
    /** 同一 best 指紋が続いた試行数で停滞とみなす */
    val stagnateIters: Long = 5_000L,
    /** hard==0 後に LAHC へ切り替える */
    val hardFloorThenLahc: Boolean = true,
    /** true なら受理率に応じて alpha を微調整（0.999–0.9999） */
    val adaptiveAlpha: Boolean = false,
)

enum class SearchPhase {
    ANNEAL,
    LAHC,
}

/**
 * 純関数の Metropolis 判定（Session の tryMetropolis と一致させる）。
 * delta = scoreAfter - scoreBefore（大きいほど悪い、最小化問題）。
 */
object Metropolis {
    fun accept(delta: Double, temperature: Double, rng: Random): Boolean {
        if (delta <= 0.0) return true
        if (temperature <= 0.0 || temperature.isNaN()) return false
        val p = exp(-delta / temperature)
        return rng.nextDouble() < p
    }

    /**
     * 初期温度の目安: 典型的な悪化幅 Δ を受理確率 p0 で通す。
     * T0 = -Δ / ln(p0)
     */
    fun suggestT0(typicalWorsening: Double, p0: Double = 0.8): Double {
        val d = max(typicalWorsening, 1.0)
        val p = p0.coerceIn(0.01, 0.99)
        return -d / ln(p)
    }
}

class TemperatureController(
    params: TemperatureParams = TemperatureParams(),
    initialScore: Long = 0L,
) {
    var params: TemperatureParams = params.copy(
        alpha = params.alpha.coerceIn(0.5, 0.999999),
        tMin = params.tMin.coerceAtLeast(0.0),
        acceptWindow = params.acceptWindow.coerceAtLeast(1),
        stagnateIters = params.stagnateIters.coerceAtLeast(1L),
    )
        private set

    var temperature: Double = params.t0.coerceAtLeast(params.tMin)
        private set

    var phase: SearchPhase = SearchPhase.ANNEAL
        private set

    var iterations: Long = 0L
        private set

    private var acceptsInWindow: Int = 0
    private var trialsInWindow: Int = 0
    private var lastReheatIter: Long = -params.reheatCooldownoutIters
    private var lastBestScore: Long = initialScore
    private var itersSinceBestImprove: Long = 0L
    private var reheatCount: Int = 0

    val reheatCountValue: Int get() = reheatCount

    fun currentAlpha(): Double {
        if (!params.adaptiveAlpha || trialsInWindow < params.acceptWindow / 2) {
            return params.alpha
        }
        val rate = acceptsInWindow.toDouble() / trialsInWindow.coerceAtLeast(1)
        // 受理が多すぎ → 速く冷やす、少なすぎ → ゆっくり
        return when {
            rate > 0.4 -> min(0.9999, params.alpha + 0.0003)
            rate < 0.05 -> max(0.998, params.alpha - 0.0003)
            else -> params.alpha
        }
    }

    /**
     * 1 試行後に呼ぶ。
     * @param accepted Metropolis/改善の受理
     * @param bestScore 現在の best の packed score（改善監視）
     * @param bestHard best の hard（LAHC 切替）
     */
    fun onTrial(accepted: Boolean, bestScore: Long, bestHard: Int) {
        iterations++
        trialsInWindow++
        if (accepted) acceptsInWindow++

        if (bestScore < lastBestScore) {
            lastBestScore = bestScore
            itersSinceBestImprove = 0L
        } else {
            itersSinceBestImprove++
        }

        if (phase == SearchPhase.ANNEAL) {
            temperature = max(params.tMin, temperature * currentAlpha())
            maybeReheat()
            if (params.hardFloorThenLahc && bestHard == 0) {
                enterLahc()
            }
        }

        if (trialsInWindow >= params.acceptWindow) {
            acceptsInWindow = 0
            trialsInWindow = 0
        }
    }

    private fun maybeReheat() {
        if (iterations - lastReheatIter < params.reheatCooldownoutIters) return
        if (itersSinceBestImprove < params.stagnateIters) return
        val rate = if (trialsInWindow > 0) {
            acceptsInWindow.toDouble() / trialsInWindow
        } else {
            0.0
        }
        if (rate > params.lowAcceptRate && itersSinceBestImprove < params.stagnateIters * 2) {
            return
        }
        reheat()
    }

    /** Conductor 相当の再加熱。best は触らない。 */
    fun reheat(factor: Double = params.reheatFactor) {
        val target = max(params.tMin, params.t0 * factor.coerceIn(0.05, 1.0))
        temperature = max(temperature, target)
        lastReheatIter = iterations
        itersSinceBestImprove = 0L
        reheatCount++
        acceptsInWindow = 0
        trialsInWindow = 0
    }

    fun enterLahc() {
        phase = SearchPhase.LAHC
        temperature = params.tMin
    }

    fun forceAnnealing() {
        phase = SearchPhase.ANNEAL
    }

    /**
     * 初期温度をスコアスケールから設定し直す（run 開始時）。
     * typicalDelta が不明なら params.t0 を維持。
     */
    fun bootstrap(typicalDelta: Double? = null, p0: Double = 0.8) {
        if (typicalDelta != null && typicalDelta > 0) {
            temperature = max(params.tMin, Metropolis.suggestT0(typicalDelta, p0))
            params = params.copy(t0 = temperature)
        } else {
            temperature = max(params.tMin, params.t0)
        }
        phase = SearchPhase.ANNEAL
        iterations = 0L
        acceptsInWindow = 0
        trialsInWindow = 0
        lastReheatIter = -params.reheatCooldownoutIters
        itersSinceBestImprove = 0L
        reheatCount = 0
    }
}

/**
 * LAHC 履歴リング。閾値は「これ以下（良い・同等）なら受理」。
 */
class LahcHistory(len: Int, initialScore: Long) {
    private val buf = LongArray(len.coerceAtLeast(1)) { initialScore }
    private var pos = 0

    fun threshold(): Long = buf[pos]

    fun onAccept(score: Long) {
        buf[pos] = score
        pos = (pos + 1) % buf.size
    }

    fun reset(score: Long) {
        for (i in buf.indices) buf[i] = score
        pos = 0
    }
}
