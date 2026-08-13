#ifndef MAGI_HOST_TEST
#include <jni.h>
#endif
#include <cstdint>
#include <cstdlib>
#include <cmath>
#include <cstring>
#include <random>
#include <vector>
#include <memory>

// [ネイティブ加速 Stage2] Evaluator.kt の忠実な C++ 移植（fullEvalParts 相当）。
// Kotlin 実装が常に「正」: 実行時に Kotlin Evaluator と同一盤面で hard/soft を照合し
// （V6FinalPort の NativeBridge ログ）、不一致ならネイティブ経路を使わない。
// Stage3 でこの評価器の上に SA チャンク（Δ評価＋受理）を載せる。
//
// 移植規約:
// - 添字・番兵は Kotlin と同一（rangeLo/Hi の未設定 = INT32_MIN/INT32_MAX、apt/wish 未設定 = -1）。
// - Java の Math.round(double) は floor(x+0.5)（half-up）。jround() で同一化。
// - c3RunMode=true 固定（Evaluator の既定と同じ。非forbidden 単一シフト連は run-deficit）。

namespace {

inline long long jround(double x) { return (long long)std::floor(x + 0.5); }

struct C1r { int d1, si, d2; };
struct C2r { int si, c; };
struct C41r { int g, s, l, u; };
struct C42r { int g1, s1, g2, s2; };

// [3.318.0] c42/c42s の「同じ日に同時発生している禁止ペア」の数。Kotlin の `c42PairCount`
//   (Evaluator.kt) と同一の式。left(群g1のs1) と right(群g2のs2) が同じ集合になるのは
//   g1==g2 かつ s1==s2 のときだけで、そのとき素朴な積 n^2 は自己ペア n 件と順序重複を余分に
//   数える。異なる2人のペア数 C(n,2) が正しい。4面(checker/Evaluator/Delta/C++)で必ず同期する。
static inline long long c42PairCount(bool sameSet, long long n1, long long n2) {
    return sameSet ? n1 * (n1 - 1) / 2 : n1 * n2;
}
struct C3r { std::vector<int> seq; bool singleRun; };

struct MagiProblem {
    int S = 0, T = 0, K = 0, G = 0, restIdx = 0, dow0 = 0;
    bool use2 = false;
    std::vector<int> sgrp, ssk;              // S
    std::vector<uint8_t> canDo;              // S*K
    std::vector<int> wish;                   // S*T (-1 = none)
    std::vector<int> need1, need2;           // K*T (-1 = none)
    std::vector<int> rangeLo, rangeHi, apt;  // S*K
    std::vector<C1r> cons1;
    std::vector<C2r> cons2;
    std::vector<C41r> cons41, cons41s;
    std::vector<C42r> cons42, cons42s;
    std::vector<C3r> cons3, cons3n, cons3m, cons3mn;
    std::vector<std::vector<int>> bucket;    // G: 群の担当ONシフト
    std::vector<std::vector<int>> members;   // G: 群のメンバー（sgrp から導出）
    std::vector<uint8_t> bucketHas;          // G*K: 群 g がシフト k を担当できるか（fair 用）
    std::vector<std::vector<int>> staffForShift;  // K: シフト k を担当できる職員（opBlockFill 用）

    inline bool cd(int i, int k) const {
        if (i < 0 || i >= S || k < 0 || k >= K) return false;
        return canDo[(size_t)i * K + k] != 0;
    }
    // Problem.covUCell / covOCell と同式（per-cell OR/AND, #4b）。
    inline int covUCell(int k, int j, int got) const {
        int lo1 = need1[(size_t)k * T + j];
        int lo2 = use2 ? need2[(size_t)k * T + j] : -1;
        int u1 = lo1 >= 0 ? (lo1 - got > 0 ? lo1 - got : 0) : -1;
        int u2 = lo2 >= 0 ? (lo2 - got > 0 ? lo2 - got : 0) : -1;
        if (u1 >= 0 && u2 >= 0) return u1 < u2 ? u1 : u2;
        if (u1 >= 0) return u1;
        if (u2 >= 0) return u2;
        return 0;
    }
    inline int covOCell(int k, int j, int got) const {
        int lo1 = need1[(size_t)k * T + j];
        int lo2 = use2 ? need2[(size_t)k * T + j] : -1;
        int o1 = lo1 >= 0 ? (got - lo1 > 0 ? got - lo1 : 0) : -1;
        int o2 = lo2 >= 0 ? (got - lo2 > 0 ? got - lo2 : 0) : -1;
        if (o1 >= 0 && o2 >= 0) return o1 < o2 ? o1 : o2;
        if (o1 >= 0) return o1;
        if (o2 >= 0) return o2;
        return 0;
    }
};

// MirrorCore.weeklyDevOfBucket と同式。
inline long long weeklyDevOfBucket(const int wd[7]) {
    int sum = 0;
    for (int w = 0; w < 7; w++) sum += wd[w];
    long long tgt = jround((double)sum / 7.0);
    long long d = 0;
    for (int w = 0; w < 7; w++) d += std::llabs((long long)wd[w] - tgt);
    return d;
}

// C3Run.rowDeficit と同式（行走査で run の不足 L-r を加算）。
inline long long rowDeficit(const int* row, int T, int k, int L) {
    long long sub = 0;
    int r = 0;
    for (int j = 0; j <= T; j++) {
        bool on = j < T && row[j] == k;
        if (on) {
            r++;
        } else if (r > 0) {
            int d = L - r;
            if (d > 0) sub += d;
            r = 0;
        }
    }
    return sub;
}

// Evaluator.c3check と同式（forbidden=窓マッチ#fire / 非forbidden単一連=run-deficit）。
long long c3check(const MagiProblem& p, const int* a, const std::vector<C3r>& list, bool forbidden) {
    long long sub = 0;
    for (const auto& c : list) {
        const int D = (int)c.seq.size();
        if (D == 0) continue;
        const int first = c.seq[0];
        if (!forbidden && c.singleRun) {
            for (int i = 0; i < p.S; i++) sub += rowDeficit(a + (size_t)i * p.T, p.T, first, D);
            continue;
        }
        for (int i = 0; i < p.S; i++) {
            const int* row = a + (size_t)i * p.T;
            for (int j = 0; j <= p.T - D; j++) {
                if (row[j] == first) {
                    int z = 0;
                    for (int l = 1; l < D; l++) if (row[j + l] == c.seq[l]) z++;
                    bool fire = forbidden ? (z == D - 1) : (z < D - 1);
                    if (fire) sub += 1;
                }
            }
        }
    }
    return sub;
}

// Evaluator.fullEvalParts の忠実移植。a は S*T の平坦配列。out[0]=hard1, out[1]=soft。
void fullEvalParts(const MagiProblem& p, const int* a, long long out[2]) {
    const int S = p.S, T = p.T, K = p.K;
    long long hard1 = 0, soft = 0;

    // c1（canDo ガード＋#fire×重み15、HF77明示数値指示2026-07-20で4→5・2026-07-21で5→15）
    for (const auto& c : p.cons1) {
        for (int i = 0; i < S; i++) {
            if (!p.cd(i, c.si)) continue;
            const int* row = a + (size_t)i * T;
            for (int j = 0; j <= T - c.d1; j++) {
                int z = 0;
                for (int l = 0; l < c.d1; l++) if (row[j + l] == c.si) z++;
                if (z < c.d2) soft += 15;
            }
        }
    }

    // c2（canDo ガード）
    for (const auto& c : p.cons2) {
        for (int i = 0; i < S; i++) {
            if (!p.cd(i, c.si)) continue;
            const int* row = a + (size_t)i * T;
            int z = 0;
            for (int j = 0; j < T; j++) if (row[j] == c.si) z++;
            if (z < c.c) soft += 1;
        }
    }

    // c41 / c42（通常群）
    for (const auto& c : p.cons41) {
        for (int j = 0; j < T; j++) {
            int z = 0;
            for (int i = 0; i < S; i++) if (p.sgrp[i] == c.g && a[(size_t)i * T + j] == c.s) z++;
            if (z < c.l || c.u < z) soft += 1;
        }
    }
    for (const auto& c : p.cons42) {
        for (int j = 0; j < T; j++) {
            long long n1 = 0, n2 = 0;
            for (int i = 0; i < S; i++) {
                int v = a[(size_t)i * T + j];
                if (p.sgrp[i] == c.g1 && v == c.s1) n1++;
                if (p.sgrp[i] == c.g2 && v == c.s2) n2++;
            }
            soft += c42PairCount(c.g1 == c.g2 && c.s1 == c.s2, n1, n2);
        }
    }

    // c41s / c42s（スキル群）
    for (const auto& c : p.cons41s) {
        for (int j = 0; j < T; j++) {
            int z = 0;
            for (int i = 0; i < S; i++) if (p.ssk[i] == c.g && a[(size_t)i * T + j] == c.s) z++;
            if (z < c.l || c.u < z) soft += 1;
        }
    }
    for (const auto& c : p.cons42s) {
        for (int j = 0; j < T; j++) {
            long long n1 = 0, n2 = 0;
            for (int i = 0; i < S; i++) {
                int v = a[(size_t)i * T + j];
                if (p.ssk[i] == c.g1 && v == c.s1) n1++;
                if (p.ssk[i] == c.g2 && v == c.s2) n2++;
            }
            soft += c42PairCount(c.g1 == c.g2 && c.s1 == c.s2, n1, n2);
        }
    }

    // c3 族（重み: c3=3 / c3n=HARD / c3m=2 / c3mn=15、HF77明示数値指示2026-07-20で12→15）
    soft += c3check(p, a, p.cons3, false) * 3;
    hard1 += c3check(p, a, p.cons3n, true);
    soft += c3check(p, a, p.cons3m, false) * 2;
    soft += c3check(p, a, p.cons3mn, true) * 15;

    // pref（実現可能な希望のみ）＋ [3.318.0] groupViol（担当できないシフトに就いているセル）。
    //   MirrorKeys.hard は元から4族（groupViol/c3n/covU/pref）なのに評価器だけ3族で、同じ盤面に
    //   対してチェッカーと評価器が違う hard を返していた。Kotlin Evaluator と同時に揃える。
    for (int i = 0; i < S; i++) {
        const int* row = a + (size_t)i * T;
        for (int j = 0; j < T; j++) {
            int w = p.wish[(size_t)i * T + j];
            if (w >= 0 && p.cd(i, w) && row[j] != w) hard1 += 1;
            int k = row[j];
            if (k >= 0 && k < K && !p.cd(i, k)) hard1 += 1;
        }
    }

    // 回数行列 ssn（range/apt/fair が共有）
    std::vector<int> ssn((size_t)S * K, 0);
    for (int i = 0; i < S; i++) {
        const int* row = a + (size_t)i * T;
        for (int j = 0; j < T; j++) {
            int k = row[j];
            if (k >= 0 && k < K) ssn[(size_t)i * K + k]++;
        }
    }

    // range low(90)/high(45) ＋ apt（L1偏差×1）
    for (int i = 0; i < S; i++) {
        for (int k = 0; k < K; k++) {
            int lo = p.rangeLo[(size_t)i * K + k];
            int hi = p.rangeHi[(size_t)i * K + k];
            int n = ssn[(size_t)i * K + k];
            if (lo != INT32_MIN && lo != 0 && n < lo && p.cd(i, k)) soft += (long long)(lo - n) * 90;
            if (hi != INT32_MAX && n > hi) soft += (long long)(n - hi) * 45;
            int t = p.apt[(size_t)i * K + k];
            if (t >= 0) soft += std::llabs((long long)n - t);
        }
    }

    // fair（群×担当ONシフト、round(平均) からの L1 偏差）
    for (int g = 0; g < p.G; g++) {
        const auto& mem = p.members[g];
        const int m = (int)mem.size();
        if (m < 2) continue;
        for (int k : p.bucket[g]) {
            int sum = 0;
            for (int x : mem) sum += ssn[(size_t)x * K + k];
            long long tgt = jround((double)sum / m);
            for (int x : mem) soft += std::llabs((long long)ssn[(size_t)x * K + k] - tgt);
        }
    }

    // weekly（職員×シフト×曜日、round(そのシフトの回数/7) からの L1 偏差）
    // [parity fix] Kotlin Evaluator / MirrorCore 3.345.0 と同式。休も通常シフト種として均す。
    // 旧: 勤務日(非休)のみの二値 weekly → soft が Kotlin より小さくパリティ不一致の主因だった。
    {
        std::vector<int> wdk((size_t)K * 7, 0);
        for (int i = 0; i < S; i++) {
            std::fill(wdk.begin(), wdk.end(), 0);
            const int* row = a + (size_t)i * T;
            for (int j = 0; j < T; j++) {
                int k = row[j];
                if (k >= 0 && k < K) wdk[(size_t)k * 7 + (p.dow0 + j) % 7]++;
            }
            for (int k = 0; k < K; k++) soft += weeklyDevOfBucket(&wdk[(size_t)k * 7]);
        }
    }

    // 被覆（covU=HARD / covO=SOFT, per-cell OR/AND）
    long long covU = 0;
    // Kotlin は j→k→i の三重ループだが、dsn[k] を日ごとに1回で数える（結果は同一・O(T*(S+K))）。
    std::vector<int> dsn(K, 0);
    for (int j = 0; j < T; j++) {
        for (int k = 0; k < K; k++) dsn[k] = 0;
        for (int i = 0; i < S; i++) {
            int k = a[(size_t)i * T + j];
            if (k >= 0 && k < K) dsn[k]++;
        }
        for (int k = 0; k < K; k++) {
            covU += p.covUCell(k, j, dsn[k]);
            soft += p.covOCell(k, j, dsn[k]);
        }
    }
    hard1 += covU;

    out[0] = hard1;
    out[1] = soft;
}

inline long long fullEvalCombined(const MagiProblem& p, const int* a) {
    long long v[2];
    fullEvalParts(p, a, v);
    return v[0] * 1000000000LL + v[1];
}

// ============ [Stage3] SA チャンク: 差分評価つき冷却ラダーを C++ 内で完走 ============
// 設計: 1チャンク=1冷却ラダー（t0→tf, 温度毎 chain 反復 ≒ 数千反復・数ms）。
// スコアは「変更セルの影響スライスを before/after 再計算」する差分方式で維持し、
// チャンク末尾に fullEval と照合（自己整合検査）。不一致は status!=0 で Kotlin 側が破棄する。
// 乱数は mt19937_64（Kotlin と経路一致は狙わない。パリティはスコアと盤面で取る）。
struct SaChunk {
    const MagiProblem& p;
    const int S, T, K;
    // [レビュー#1 3.213.0] 辞書式パックの HARD 桁単位。Kotlin の SCORE_HARD_UNIT (Evaluator.kt) と要同期。
    const long long M = 1000000000LL;
    std::vector<int> a;    // S*T
    std::vector<int> ssn;  // S*K
    std::vector<int> dsn;  // T*K
    std::vector<int> wd;   // S*K*7 [3.345.0] 職員×シフト×曜日（休も1シフト）
    // [ビット化] c1窓 / c41-c42 の O(1) 評価用マスク（S,T<=64 のとき有効）。効果は deltaApply の contrib* 経由。
    //   fullEvalParts はスカラーのまま＝自己整合(status)がビット化 delta をチャンク毎に照合する基準。
    bool useBits;
    std::vector<uint64_t> rowMask;       // S*K : bit=日（staff i がシフト k を担当する日集合）
    std::vector<uint64_t> dayShiftMask;  // T*K : bit=職員（日 j にシフト k の職員集合）
    std::vector<uint64_t> grpMask;       // sgrp群id -> 職員bit集合（静的）
    std::vector<uint64_t> sskMask;       // skill群id -> 職員bit集合（静的）
    long long score = 0;   // hard*M(=1e9)+soft（combined）
    std::mt19937_64 rng;

    SaChunk(const MagiProblem& prob, const int* cur, uint64_t seed)
        : p(prob), S(prob.S), T(prob.T), K(prob.K),
          useBits(prob.S <= 64 && prob.T <= 64), rng(seed) {
        if (useBits) buildGroupMasks();
        resetBoard(cur);
    }

    // 群→職員bit集合（静的・盤面非依存）。制約が参照する群idまで被覆してサイズ確保。
    void buildGroupMasks() {
        int maxG = p.G - 1, maxGs = 0;
        for (int i = 0; i < S; i++) { if (p.sgrp[i] > maxG) maxG = p.sgrp[i]; if (p.ssk[i] > maxGs) maxGs = p.ssk[i]; }
        for (const auto& c : p.cons41) if (c.g > maxG) maxG = c.g;
        for (const auto& c : p.cons42) { if (c.g1 > maxG) maxG = c.g1; if (c.g2 > maxG) maxG = c.g2; }
        for (const auto& c : p.cons41s) if (c.g > maxGs) maxGs = c.g;
        for (const auto& c : p.cons42s) { if (c.g1 > maxGs) maxGs = c.g1; if (c.g2 > maxGs) maxGs = c.g2; }
        grpMask.assign((size_t)(maxG < 0 ? 0 : maxG) + 1, 0ULL);
        sskMask.assign((size_t)(maxGs < 0 ? 0 : maxGs) + 1, 0ULL);
        for (int i = 0; i < S; i++) {
            if (p.sgrp[i] >= 0 && p.sgrp[i] < (int)grpMask.size()) grpMask[(size_t)p.sgrp[i]] |= (1ULL << i);
            if (p.ssk[i]  >= 0 && p.ssk[i]  < (int)sskMask.size()) sskMask[(size_t)p.ssk[i]]  |= (1ULL << i);
        }
    }

    // 盤面を差し替えて counts(ssn/dsn/wd/bit)と score を再初期化（rng 系列は継続）。
    // [3.381] ssn/dsn/rowMask を盤面から再構築（未初期化のまま deltaApply しない）。
    void resetBoard(const int* cur) {
        a.assign(cur, cur + (size_t)S * T);
        ssn.assign((size_t)S * K, 0);
        dsn.assign((size_t)T * K, 0);
        wd.assign((size_t)S * K * 7, 0);
        if (useBits) {
            rowMask.assign((size_t)S * K, 0ULL);
            dayShiftMask.assign((size_t)T * K, 0ULL);
        }
        for (int i = 0; i < S; i++) {
            const int* row = &a[(size_t)i * T];
            for (int j = 0; j < T; j++) {
                int k = row[j];
                if (k < 0 || k >= K) continue;
                ssn[(size_t)i * K + k]++;
                dsn[(size_t)j * K + k]++;
                wd[((size_t)i * K + k) * 7 + (p.dow0 + j) % 7]++;
                if (useBits) {
                    rowMask[(size_t)i * K + k] |= (1ULL << j);
                    dayShiftMask[(size_t)j * K + k] |= (1ULL << i);
                }
            }
        }
        score = fullEvalCombined(p, a.data());
    }

    inline int nextInt(int bound) { return (int)(rng() % (uint64_t)bound); }
    inline double nextDouble() { return (double)(rng() >> 11) * 0x1.0p-53; }

    // ---- 影響スライスの寄与（combined: HARD族は ×1e6）----
    // c1 重み15・c3mn重み15（HF77明示数値指示2026-07-20で旧4/12から5/15へ・2026-07-21でc1を5→15へ変更）。
    long long contribC1Row(int i) const {
        long long v = 0;
        if (useBits) {
            for (const auto& c : p.cons1) {
                if (!p.cd(i, c.si)) continue;
                uint64_t rm = rowMask[(size_t)i * K + c.si];
                uint64_t wmask = (c.d1 >= 64) ? ~0ULL : ((1ULL << c.d1) - 1ULL);
                for (int j = 0; j <= T - c.d1; j++)
                    if (__builtin_popcountll((rm >> j) & wmask) < c.d2) v += 15;
            }
            return v;
        }
        for (const auto& c : p.cons1) {
            if (!p.cd(i, c.si)) continue;
            const int* row = a.data() + (size_t)i * T;
            for (int j = 0; j <= T - c.d1; j++) {
                int z = 0;
                for (int l = 0; l < c.d1; l++) if (row[j + l] == c.si) z++;
                if (z < c.d2) v += 15;
            }
        }
        return v;
    }
    long long contribC2Row(int i) const {
        long long v = 0;
        for (const auto& c : p.cons2) {
            if (!p.cd(i, c.si)) continue;
            if (ssn[(size_t)i * K + c.si] < c.c) v += 1;
        }
        return v;
    }
    long long contribC3RowFam(int i, const std::vector<C3r>& list, bool forbidden, long long w) const {
        long long v = 0;
        const int* row = a.data() + (size_t)i * T;
        for (const auto& c : list) {
            const int D = (int)c.seq.size();
            if (D == 0) continue;
            const int first = c.seq[0];
            // 非forbidden単一シフト連は run-deficit（run長ベース＝popcount化困難。3.172.0 の対象外方針を踏襲）。
            if (!forbidden && c.singleRun) { v += rowDeficit(row, T, first, D) * w; continue; }
            // [ビット化] 窓マッチ（forbidden 完全一致 / 非forbidden 多シフト部分不一致）を popcount で。
            //   rowMask[i*K+k]=職員 i がシフト k を持つ日ビット集合（deltaApply が維持済＝新規マスク不要）。
            //   (rowMask[seq[l]] >> l) の bit j = (row[j+l]==seq[l])。AND で「窓開始 j に完全一致」の集合を得る。
            //   forbidden: fire=完全一致数（z==D-1）。非forbidden多シフト: fire=先頭一致−完全一致（z<D-1）。
            //   マスク索引の安全のため seq が全て [0,K) のときのみ（範囲外は理論上到達不能だが scalar へ退避）。
            if (useBits && D <= T) {
                bool seqOk = first >= 0 && first < K;
                for (int l = 1; seqOk && l < D; l++) if (c.seq[l] < 0 || c.seq[l] >= K) seqOk = false;
                if (seqOk) {
                    uint64_t full = rowMask[(size_t)i * K + first];
                    for (int l = 1; l < D; l++) full &= (rowMask[(size_t)i * K + c.seq[l]] >> l);
                    uint64_t range = (T - D + 1 >= 64) ? ~0ULL : ((1ULL << (T - D + 1)) - 1ULL);
                    long long fullCnt = __builtin_popcountll(full & range);
                    if (forbidden) v += fullCnt * w;
                    else v += (__builtin_popcountll(rowMask[(size_t)i * K + first] & range) - fullCnt) * w;
                    continue;
                }
            }
            for (int j = 0; j <= T - D; j++) {
                if (row[j] == first) {
                    int z = 0;
                    for (int l = 1; l < D; l++) if (row[j + l] == c.seq[l]) z++;
                    bool fire = forbidden ? (z == D - 1) : (z < D - 1);
                    if (fire) v += w;
                }
            }
        }
        return v;
    }
    long long contribC3Row(int i) const {
        return contribC3RowFam(i, p.cons3, false, 3)
             + contribC3RowFam(i, p.cons3n, true, (long long)M)
             + contribC3RowFam(i, p.cons3m, false, 2)
             + contribC3RowFam(i, p.cons3mn, true, 15);
    }
    // [3.318.0] このセルの HARD 寄与＝pref（実現可能な希望の未充足）＋ groupViol（担当できないシフト）。
    //   どちらもセル単位なので deltaApply の before/after で呼べば差分は自動的に正しい。
    //   旧名 contribPrefCell（pref だけ）から改名。
    long long contribCellHard(int i, int j) const {
        long long v = 0;
        int cur = a[(size_t)i * T + j];
        int w = p.wish[(size_t)i * T + j];
        if (w >= 0 && p.cd(i, w) && cur != w) v += (long long)M;
        if (cur >= 0 && cur < K && !p.cd(i, cur)) v += (long long)M;
        return v;
    }
    long long contribRangeApt(int i, int k) const {
        // [実データ対応] k=-1(未割当セル=normalizeSchedule の正規化結果)や範囲外は寄与0。
        //   contribCov/contribFair と同じガード（旧: 無ガードで rangeLo[i*K-1] 等の範囲外読み）。
        if (k < 0 || k >= K) return 0;
        long long v = 0;
        int lo = p.rangeLo[(size_t)i * K + k];
        int hi = p.rangeHi[(size_t)i * K + k];
        int n = ssn[(size_t)i * K + k];
        if (lo != INT32_MIN && lo != 0 && n < lo && p.cd(i, k)) v += (long long)(lo - n) * 90;
        if (hi != INT32_MAX && n > hi) v += (long long)(n - hi) * 45;
        int t = p.apt[(size_t)i * K + k];
        if (t >= 0) v += std::llabs((long long)n - t);
        return v;
    }
    long long contribFair(int g, int k) const {
        if (g < 0 || g >= p.G || k < 0 || k >= K) return 0;
        if (!p.bucketHas[(size_t)g * K + k]) return 0;
        const auto& mem = p.members[g];
        const int m = (int)mem.size();
        if (m < 2) return 0;
        int sum = 0;
        for (int x : mem) sum += ssn[(size_t)x * K + k];
        long long tgt = jround((double)sum / m);
        long long v = 0;
        for (int x : mem) v += std::llabs((long long)ssn[(size_t)x * K + k] - tgt);
        return v;
    }
    // [3.345.0] weekly は職員×シフト。deltaApply では old/nw の2バケットだけが動くので
    // 該当2本だけ再計算する（他シフトの偏差は不変）。
    long long contribWeeklyK(int i, int k) const {
        if (k < 0 || k >= K) return 0;
        return weeklyDevOfBucket(&wd[((size_t)i * K + k) * 7]);
    }
    long long contribDayGroups(int j) const {
        long long v = 0;
        if (useBits) {
            const uint64_t* dm = &dayShiftMask[(size_t)j * K];
            for (const auto& c : p.cons41) {
                int z = __builtin_popcountll(dm[c.s] & grpMask[(size_t)c.g]);
                if (z < c.l || c.u < z) v += 1;
            }
            for (const auto& c : p.cons42) {
                long long n1 = __builtin_popcountll(dm[c.s1] & grpMask[(size_t)c.g1]);
                long long n2 = __builtin_popcountll(dm[c.s2] & grpMask[(size_t)c.g2]);
                v += c42PairCount(c.g1 == c.g2 && c.s1 == c.s2, n1, n2);
            }
            for (const auto& c : p.cons41s) {
                int z = __builtin_popcountll(dm[c.s] & sskMask[(size_t)c.g]);
                if (z < c.l || c.u < z) v += 1;
            }
            for (const auto& c : p.cons42s) {
                long long n1 = __builtin_popcountll(dm[c.s1] & sskMask[(size_t)c.g1]);
                long long n2 = __builtin_popcountll(dm[c.s2] & sskMask[(size_t)c.g2]);
                v += c42PairCount(c.g1 == c.g2 && c.s1 == c.s2, n1, n2);
            }
            return v;
        }
        for (const auto& c : p.cons41) {
            int z = 0;
            for (int i = 0; i < S; i++) if (p.sgrp[i] == c.g && a[(size_t)i * T + j] == c.s) z++;
            if (z < c.l || c.u < z) v += 1;
        }
        for (const auto& c : p.cons42) {
            long long n1 = 0, n2 = 0;
            for (int i = 0; i < S; i++) {
                int x = a[(size_t)i * T + j];
                if (p.sgrp[i] == c.g1 && x == c.s1) n1++;
                if (p.sgrp[i] == c.g2 && x == c.s2) n2++;
            }
            v += c42PairCount(c.g1 == c.g2 && c.s1 == c.s2, n1, n2);
        }
        for (const auto& c : p.cons41s) {
            int z = 0;
            for (int i = 0; i < S; i++) if (p.ssk[i] == c.g && a[(size_t)i * T + j] == c.s) z++;
            if (z < c.l || c.u < z) v += 1;
        }
        for (const auto& c : p.cons42s) {
            long long n1 = 0, n2 = 0;
            for (int i = 0; i < S; i++) {
                int x = a[(size_t)i * T + j];
                if (p.ssk[i] == c.g1 && x == c.s1) n1++;
                if (p.ssk[i] == c.g2 && x == c.s2) n2++;
            }
            v += c42PairCount(c.g1 == c.g2 && c.s1 == c.s2, n1, n2);
        }
        return v;
    }
    long long contribCov(int k, int j) const {
        if (k < 0 || k >= K) return 0;
        int got = dsn[(size_t)j * K + k];
        return (long long)p.covUCell(k, j, got) * M + p.covOCell(k, j, got);
    }

    // セル(i,j)を nw へ差分適用（影響スライスの before/after 再計算で score を維持）。
    // [実データ対応・重要] 盤面には -1（未割当）が正当に現れる: normalizeSchedule（MirrorCore）は
    //   範囲外セルを -1 に写像し、その盤面が各ネイティブランナー（SA/LAHC/ALNS/Polish は全て本構造体を
    //   共有）へ flatten されて届く。さらに undo バッファのリバートは旧値=-1 をそのまま復元する。
    //   旧実装は old/nw を無条件に ssn/dsn/rowMask/dayShiftMask へ索引し（-1 で範囲外書込＝カウント/
    //   ヒープ破壊。ホスト再現で free(): invalid pointer を確認）、wd の work 判定にも範囲ガードが
    //   無かった（Kotlin DeltaEvaluator は 3.92.0/3.95.1 で同一クラスをハードニング済み＝C++ ミラーの
    //   直し漏れ）。score がドリフトしチャンク末尾の自己整合で status=1 → 番兵発火 → Kotlin 退化
    //   （実機ログ「NativeBridge: SAチャンク整合性NG status=1」の根本原因）。resetBoard/fullEvalParts
    //   と同じ range ガードで対称化する。
    void deltaApply(int i, int j, int nw) {
        int old = a[(size_t)i * T + j];
        if (old == nw) return;
        const bool oldIn = old >= 0 && old < K;
        const bool newIn = nw >= 0 && nw < K;
        int g = p.sgrp[i];
        long long before = contribC1Row(i) + contribC2Row(i) + contribC3Row(i)
            + contribCellHard(i, j)
            + contribRangeApt(i, old) + contribRangeApt(i, nw)
            + contribFair(g, old) + contribFair(g, nw)
            + contribWeeklyK(i, old) + contribWeeklyK(i, nw)
            + contribDayGroups(j)
            + contribCov(old, j) + contribCov(nw, j);
        a[(size_t)i * T + j] = nw;
        if (oldIn) { ssn[(size_t)i * K + old]--; dsn[(size_t)j * K + old]--; }
        if (newIn) { ssn[(size_t)i * K + nw]++; dsn[(size_t)j * K + nw]++; }
        if (useBits) {
            uint64_t jb = 1ULL << j, ib = 1ULL << i;
            if (oldIn) { rowMask[(size_t)i * K + old] &= ~jb; dayShiftMask[(size_t)j * K + old] &= ~ib; }
            if (newIn) { rowMask[(size_t)i * K + nw] |= jb;  dayShiftMask[(size_t)j * K + nw] |= ib; }
        }
        // [3.345.0] weekly シフト別バケット更新
        {
            const size_t b = (size_t)(p.dow0 + j) % 7;
            if (oldIn) wd[((size_t)i * K + old) * 7 + b]--;
            if (newIn) wd[((size_t)i * K + nw) * 7 + b]++;
        }
        long long after = contribC1Row(i) + contribC2Row(i) + contribC3Row(i)
            + contribCellHard(i, j)
            + contribRangeApt(i, old) + contribRangeApt(i, nw)
            + contribFair(g, old) + contribFair(g, nw)
            + contribWeeklyK(i, old) + contribWeeklyK(i, nw)
            + contribDayGroups(j)
            + contribCov(old, j) + contribCov(nw, j);
        score += after - before;
    }
};

// SA チャンク実行（SaOptimizer PhaseA の冷却ラダー1本ぶん、Kotlin と同じ4オペレータ＋Metropolis）。
// out: [status, curScore, bestScore, iters, improvedInChunk, tailIters]
// status: 0=OK / 1=cur整合性NG / 2=best整合性NG（いずれもKotlin側で破棄・退化）。
// [3.334.0] 実現可能な希望が入ったセルか（Kotlin `Problem.wishLocked` と同義）。
//   SA/LAHC の近傍と修復オペレータの両方から使うので、SA の手前で定義する。
inline bool wishLockedN(const MagiProblem& p, int i, int j) {
    int w = p.wish[(size_t)i * p.T + j];
    return w >= 0 && p.cd(i, w);
}

void runSaChunk(const MagiProblem& p, int* cur, int* best, long long bestScoreIn,
                uint64_t seed, double t0, double tf, double alpha, int chain, long long out[6]) {
    const int S = p.S, T = p.T;
    SaChunk st(p, cur, seed);
    long long curVal = st.score;
    std::vector<int> bestSol(best, best + (size_t)S * T);
    long long bestScore = bestScoreIn;
    bool improved = false;
    long long iters = 0, tail = 0;

    const int cap = T + S + 16;
    std::vector<int> bi(cap), bj(cap), bOld(cap);
    int bn = 0;
    auto applyCell = [&](int i, int j, int nw) {
        if (bn >= cap) return;
        bi[bn] = i; bj[bn] = j; bOld[bn] = st.a[(size_t)i * T + j]; bn++;
        st.deltaApply(i, j, nw);
    };
    auto revert = [&]() {
        for (int k = bn - 1; k >= 0; k--) st.deltaApply(bi[k], bj[k], bOld[k]);
        bn = 0;
    };
    auto randShiftFor = [&](int i) -> int {
        const auto& b = p.bucket[p.sgrp[i]];
        return b.empty() ? st.a[(size_t)i * T] : b[st.nextInt((int)b.size())];
    };
    auto opSingle = [&]() {
        int i = st.nextInt(S), j = st.nextInt(T);
        for (int tries = 0; tries < 4 && wishLockedN(p, i, j); tries++) j = st.nextInt(T);
        if (wishLockedN(p, i, j)) return;
        const auto& b = p.bucket[p.sgrp[i]];
        if (b.empty()) return;
        applyCell(i, j, b[st.nextInt((int)b.size())]);
    };
    auto opSwapDays = [&]() {
        if (T < 2) return;
        int i = st.nextInt(S);
        int j1 = st.nextInt(T), j2 = st.nextInt(T);
        if (j1 == j2) j2 = (j2 + 1) % T;
        if (wishLockedN(p, i, j1) || wishLockedN(p, i, j2)) return;
        int o1 = st.a[(size_t)i * T + j1], o2 = st.a[(size_t)i * T + j2];
        if (o1 == o2) return;
        applyCell(i, j1, o2);
        applyCell(i, j2, o1);
    };
    auto opBlockFill = [&]() {
        if (p.cons1.empty()) { opSingle(); return; }
        const auto& c = p.cons1[st.nextInt((int)p.cons1.size())];
        const auto& pool = p.staffForShift[c.si];
        if (pool.empty()) { opSingle(); return; }
        int i = pool[st.nextInt((int)pool.size())];
        int maxStart = T - c.d1;
        if (maxStart < 0) { opSingle(); return; }
        int js = st.nextInt(maxStart + 1);
        // [3.341.0] 固定セルを飛ばして「部分的に埋まった窓」を作らない（Kotlin 側と同型）。
        for (int l = 0; l < c.d1; l++) if (wishLockedN(p, i, js + l)) return;
        for (int l = 0; l < c.d1; l++) applyCell(i, js + l, c.si);
    };
    auto opLns = [&]() {
        // [3.341.0] 破壊する集合を先に決め、固定セルが混ざっていたら手ごと見送る（部分適用しない）。
        switch (st.nextInt(3)) {
            case 0: { int i = st.nextInt(S); int cnt = 2 + st.nextInt(T < 7 ? T : 7);
                std::vector<int> js(cnt);
                for (int k = 0; k < cnt; k++) js[k] = st.nextInt(T);
                for (int k = 0; k < cnt; k++) if (wishLockedN(p, i, js[k])) return;
                for (int k = 0; k < cnt; k++) applyCell(i, js[k], randShiftFor(i)); break; }
            case 1: { int j = st.nextInt(T);
                for (int i = 0; i < S; i++) if (wishLockedN(p, i, j)) return;
                for (int i = 0; i < S; i++) applyCell(i, j, randShiftFor(i)); break; }
            default: { int cnt = 3 + st.nextInt(8);
                std::vector<int> ci(cnt), cj(cnt);
                for (int k = 0; k < cnt; k++) { ci[k] = st.nextInt(S); cj[k] = st.nextInt(T); }
                for (int k = 0; k < cnt; k++) if (wishLockedN(p, ci[k], cj[k])) return;
                for (int k = 0; k < cnt; k++) applyCell(ci[k], cj[k], randShiftFor(ci[k])); break; }
        }
    };
    const bool hasC1 = !p.cons1.empty();
    auto pickOperator = [&]() {
        int r = st.nextInt(100);
        if (r < 60) opSingle();
        else if (r < 80) opSwapDays();
        else if (r < 92) { if (hasC1) opBlockFill(); else opSingle(); }
        else opLns();
    };

    // 冷却ラダー（暴走ガード: パラメータ異常でも反復上限で必ず返る）。
    const long long maxIters = 200000;
    for (double t = t0; t >= tf && iters < maxIters; t *= alpha) {
        for (int ls = 0; ls < chain && iters < maxIters; ls++) {
            bn = 0;
            pickOperator();
            long long cand = st.score;
            long long dE = cand - curVal;
            if (dE <= 0 || std::exp(-(double)dE / t) > st.nextDouble()) {
                curVal = cand;
                if (cand < bestScore) {
                    bestScore = cand;
                    std::memcpy(bestSol.data(), st.a.data(), sizeof(int) * (size_t)S * T);
                    improved = true;
                    tail = 0;
                } else tail++;
                bn = 0;
            } else { revert(); tail++; }
            iters++;
        }
    }

    // 自己整合検査（差分スコア==フル再計算）。best 更新があれば best 側も検査。
    // [backlog#8] LAHC/ALNS/Polish と対称に curVal!=st.score（局所受理簿記==チャンク増分スコア）も検査。
    //   受理時 curVal=st.score・revert で st.score 復元のため通常は恒真＝挙動不変、不整合時のみ退化。
    long long full = fullEvalCombined(p, st.a.data());
    long long status = 0;
    if (full != curVal || curVal != st.score) status = 1;
    else if (improved && fullEvalCombined(p, bestSol.data()) != bestScore) status = 2;

    if (status == 0) {
        std::memcpy(cur, st.a.data(), sizeof(int) * (size_t)S * T);
        std::memcpy(best, bestSol.data(), sizeof(int) * (size_t)S * T);
    }
    out[0] = status;
    out[1] = curVal;
    out[2] = bestScore;
    out[3] = iters;
    out[4] = improved ? 1 : 0;
    out[5] = tail;
}

// ============================================================================
// [Stage11/第3期] SaOptimizer PhaseB（HARD ガード付き LAHC ソフト研磨）のチャンク。
// Kotlin SaOptimizer.runWorker PhaseB と同一: オペ=PhaseA と同じ4種(60/20/12/8)、
// 受理 candHard<=bestHard && (cand<=hist[bIt%L] || cand<=cur)、hist 更新 cur<hist→hist=cur、
// keep-best。状態(hist/bIt/bestHard)はチャンク跨ぎで保持。番兵1層目=チャンク末尾の自己整合。
// ============================================================================
struct LahcState {
    const MagiProblem& p;
    SaChunk st;                          // cur 盤面＋差分スコア維持
    std::vector<int> bestSol;
    long long bestScore;
    long long bestHard;                  // 到達済み HARD 水準（これを超える手は受理しない）
    std::vector<long long> hist;
    long long bIt = 0;

    LahcState(const MagiProblem& prob, const int* board, uint64_t seed, int lahcLen)
        : p(prob), st(prob, board, seed),
          bestSol(board, board + (size_t)prob.S * prob.T) {
        bestScore = st.score;
        bestHard = bestScore / 1000000000LL;
        hist.assign((size_t)(lahcLen < 1 ? 1 : lahcLen), st.score);   // Kotlin: LongArray(lahcLen){curVal}
    }
};

// 1チャンク＝iters 反復。out[5]: [status, curScore, bestScore, bestImproved, itersDone]。
void runLahcChunk(LahcState& s, int iters, long long out[5]) {
    const MagiProblem& p = s.p;
    const int S = p.S, T = p.T;
    auto& st = s.st;
    long long curVal = st.score;
    bool improved = false;

    // ---- runSaChunk と同一の undo バッファ＋4オペ（PhaseA/B 共通近傍）----
    const int cap = T + S + 16;
    std::vector<int> bi(cap), bj(cap), bOld(cap);
    int bn = 0;
    auto applyCell = [&](int i, int j, int nw) {
        if (bn >= cap) return;
        bi[bn] = i; bj[bn] = j; bOld[bn] = st.a[(size_t)i * T + j]; bn++;
        st.deltaApply(i, j, nw);
    };
    auto revert = [&]() {
        for (int k = bn - 1; k >= 0; k--) st.deltaApply(bi[k], bj[k], bOld[k]);
        bn = 0;
    };
    auto randShiftFor = [&](int i) -> int {
        const auto& b = p.bucket[p.sgrp[i]];
        return b.empty() ? st.a[(size_t)i * T] : b[st.nextInt((int)b.size())];
    };
    auto opSingle = [&]() {
        int i = st.nextInt(S), j = st.nextInt(T);
        for (int tries = 0; tries < 4 && wishLockedN(p, i, j); tries++) j = st.nextInt(T);
        if (wishLockedN(p, i, j)) return;
        const auto& b = p.bucket[p.sgrp[i]];
        if (b.empty()) return;
        applyCell(i, j, b[st.nextInt((int)b.size())]);
    };
    auto opSwapDays = [&]() {
        if (T < 2) return;
        int i = st.nextInt(S);
        int j1 = st.nextInt(T), j2 = st.nextInt(T);
        if (j1 == j2) j2 = (j2 + 1) % T;
        if (wishLockedN(p, i, j1) || wishLockedN(p, i, j2)) return;
        int o1 = st.a[(size_t)i * T + j1], o2 = st.a[(size_t)i * T + j2];
        if (o1 == o2) return;
        applyCell(i, j1, o2);
        applyCell(i, j2, o1);
    };
    auto opBlockFill = [&]() {
        if (p.cons1.empty()) { opSingle(); return; }
        const auto& c = p.cons1[st.nextInt((int)p.cons1.size())];
        const auto& pool = p.staffForShift[c.si];
        if (pool.empty()) { opSingle(); return; }
        int i = pool[st.nextInt((int)pool.size())];
        int maxStart = T - c.d1;
        if (maxStart < 0) { opSingle(); return; }
        int js = st.nextInt(maxStart + 1);
        // [3.341.0] 固定セルを飛ばして「部分的に埋まった窓」を作らない（Kotlin 側と同型）。
        for (int l = 0; l < c.d1; l++) if (wishLockedN(p, i, js + l)) return;
        for (int l = 0; l < c.d1; l++) applyCell(i, js + l, c.si);
    };
    auto opLns = [&]() {
        // [3.341.0] 破壊する集合を先に決め、固定セルが混ざっていたら手ごと見送る（部分適用しない）。
        switch (st.nextInt(3)) {
            case 0: { int i = st.nextInt(S); int cnt = 2 + st.nextInt(T < 7 ? T : 7);
                std::vector<int> js(cnt);
                for (int k = 0; k < cnt; k++) js[k] = st.nextInt(T);
                for (int k = 0; k < cnt; k++) if (wishLockedN(p, i, js[k])) return;
                for (int k = 0; k < cnt; k++) applyCell(i, js[k], randShiftFor(i)); break; }
            case 1: { int j = st.nextInt(T);
                for (int i = 0; i < S; i++) if (wishLockedN(p, i, j)) return;
                for (int i = 0; i < S; i++) applyCell(i, j, randShiftFor(i)); break; }
            default: { int cnt = 3 + st.nextInt(8);
                std::vector<int> ci(cnt), cj(cnt);
                for (int k = 0; k < cnt; k++) { ci[k] = st.nextInt(S); cj[k] = st.nextInt(T); }
                for (int k = 0; k < cnt; k++) if (wishLockedN(p, ci[k], cj[k])) return;
                for (int k = 0; k < cnt; k++) applyCell(ci[k], cj[k], randShiftFor(ci[k])); break; }
        }
    };
    const bool hasC1 = !p.cons1.empty();
    auto pickOperator = [&]() {
        int r = st.nextInt(100);
        if (r < 60) opSingle();
        else if (r < 80) opSwapDays();
        else if (r < 92) { if (hasC1) opBlockFill(); else opSingle(); }
        else opLns();
    };

    const long long L = (long long)s.hist.size();
    for (int it = 0; it < iters; it++) {
        bn = 0;
        pickOperator();
        long long cand = st.score;
        long long candHard = cand / 1000000000LL;
        long long v = s.hist[(size_t)(s.bIt % L)];
        if (candHard <= s.bestHard && (cand <= v || cand <= curVal)) {
            curVal = cand;
            if (candHard < s.bestHard) s.bestHard = candHard;
            if (cand < s.bestScore) {
                s.bestScore = cand;
                std::memcpy(s.bestSol.data(), st.a.data(), sizeof(int) * (size_t)S * T);
                improved = true;
            }
            bn = 0;
        } else revert();
        size_t hi = (size_t)(s.bIt % L);
        if (curVal < s.hist[hi]) s.hist[hi] = curVal;
        s.bIt++;
    }

    // ---- 番兵1層目: チャンク末尾の自己整合検査（差分スコア==フル再計算）----
    long long full = fullEvalCombined(p, st.a.data());
    long long status = 0;
    if (full != curVal || curVal != st.score) status = 1;
    else if (improved && fullEvalCombined(p, s.bestSol.data()) != s.bestScore) status = 2;

    out[0] = status;
    out[1] = curVal;
    out[2] = s.bestScore;
    out[3] = improved ? 1 : 0;
    out[4] = iters;
}

// ============ [第2期 Stage5] 違反セル抽出 ＋ GLS ペナルティ ＋ 受理判定 ============
// ALNS チャンク(Stage8)の部品。violations セルは GLSキック/destroyRepairViolations の hint 用で
// スコアには不使用（正しさは番兵が担保・hint はチェッカーの mark 位置へ忠実に移植）。

// UnifiedViolationChecker の violations マップ（セル着色）のキー集合と同じセルを列挙する。
// 対象8族: c1(違反窓ランの先頭)・c42/c42s(ペア両セル)・c3×4(単一連=run先頭/非forbidden窓=先頭/
// forbidden=パターン全セル)・pref・groupViol。countViolations/needViolations 系はループ内未使用のため対象外。
void collectViolationCells(const MagiProblem& p, const int* a, std::vector<int>& out) {
    const int S = p.S, T = p.T, K = p.K;
    out.clear();
    std::vector<uint8_t> seen((size_t)S * T, 0);
    auto markCell = [&](int i, int j) {
        if (i < 0 || i >= S || j < 0 || j >= T) return;
        size_t x = (size_t)i * T + j;
        if (!seen[x]) { seen[x] = 1; out.push_back((int)x); }
    };
    // c1: canDo ガード＋違反窓ランの先頭セル
    for (const auto& c : p.cons1) {
        for (int i = 0; i < S; i++) {
            if (!p.cd(i, c.si)) continue;
            const int* row = a + (size_t)i * T;
            bool prevViol = false;
            for (int j = 0; j <= T - c.d1; j++) {
                int z = 0;
                for (int l = 0; l < c.d1; l++) if (row[j + l] == c.si) z++;
                bool viol = z < c.d2;
                if (viol && !prevViol) markCell(i, j);
                prevViol = viol;
            }
        }
    }
    // c42/c42s: 同日ペアの両セル
    auto pairCells = [&](const std::vector<C42r>& list, const std::vector<int>& grp) {
        for (const auto& c : list) {
            // [3.318.0] 同一集合(g1==g2 && s1==s2)は「異なる2人が同時に就いている」ときだけ違反。
            //   旧: anyL && anyR だと1人しか居なくても真になり、違反していないセルをヒントに出していた。
            const bool sameSet = (c.g1 == c.g2 && c.s1 == c.s2);
            for (int j = 0; j < T; j++) {
                int nL = 0, nR = 0;
                for (int i = 0; i < S; i++) {
                    int v = a[(size_t)i * T + j];
                    if (grp[i] == c.g1 && v == c.s1) nL++;
                    if (grp[i] == c.g2 && v == c.s2) nR++;
                }
                if (sameSet ? (nL >= 2) : (nL > 0 && nR > 0)) {
                    for (int i = 0; i < S; i++) {
                        int v = a[(size_t)i * T + j];
                        if ((grp[i] == c.g1 && v == c.s1) || (grp[i] == c.g2 && v == c.s2)) markCell(i, j);
                    }
                }
            }
        }
    };
    pairCells(p.cons42, p.sgrp);
    pairCells(p.cons42s, p.ssk);
    // c3×4: checkC3Family の mark 位置
    auto c3Cells = [&](const std::vector<C3r>& list, bool forbidden) {
        for (const auto& c : list) {
            const int D = (int)c.seq.size();
            if (D == 0 || D > T) continue;
            const int first = c.seq[0];
            if (!forbidden && c.singleRun) {
                for (int i = 0; i < S; i++) {
                    const int* row = a + (size_t)i * T;
                    int runStart = -1, r = 0;
                    for (int j = 0; j <= T; j++) {
                        bool on = j < T && row[j] == first;
                        if (on) { if (r == 0) runStart = j; r++; }
                        else if (r > 0) {
                            if (D - r > 0) markCell(i, runStart);
                            r = 0; runStart = -1;
                        }
                    }
                }
                continue;
            }
            for (int i = 0; i < S; i++) {
                const int* row = a + (size_t)i * T;
                for (int j = 0; j <= T - D; j++) {
                    if (row[j] == first) {
                        int z = 0;
                        for (int l = 1; l < D; l++) if (row[j + l] == c.seq[l]) z++;
                        bool fire = forbidden ? (z == D - 1) : (z < D - 1);
                        if (fire) {
                            if (forbidden) { for (int l = 0; l < D; l++) markCell(i, j + l); }
                            else markCell(i, j);
                        }
                    }
                }
            }
        }
    };
    c3Cells(p.cons3, false);
    c3Cells(p.cons3n, true);
    c3Cells(p.cons3m, false);
    c3Cells(p.cons3mn, true);
    // pref（実現可能な希望の未充足）と groupViol（担当外割当）
    for (int i = 0; i < S; i++) {
        const int* row = a + (size_t)i * T;
        for (int j = 0; j < T; j++) {
            int w = p.wish[(size_t)i * T + j];
            if (w >= 0 && p.cd(i, w) && row[j] != w) markCell(i, j);
            int k = row[j];
            if (k >= 0 && k < K && !p.cd(i, k)) markCell(i, j);
        }
    }
}

// GlsPenalty.kt の移植（疎HashMap→密配列。S*T*K は実データ規模で数千intと小さい）。
struct GlsPenaltyN {
    int S, T, K;
    double lambda = 200.0;
    std::vector<int> pen;   // S*T*K
    long long kicks = 0;
    GlsPenaltyN(int s, int t, int k) : S(s), T(t), K(k), pen((size_t)s * t * k, 0) {}
    inline int penaltyOf(int i, int j, int k) const { return pen[((size_t)i * T + j) * K + k]; }
    double augment(const int* a) const {
        long long sum = 0;
        for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) {
            int k = a[(size_t)i * T + j];
            if (k >= 0 && k < K) sum += penaltyOf(i, j, k);
        }
        return lambda * (double)sum;
    }
    // V6SearchOperators.glsMoveAug と同式（変更セルだけの O(1) 差分）。
    inline double moveAug(int i, int j, int oldK, int nwK) const {
        return oldK == nwK ? 0.0 : lambda * (double)(penaltyOf(i, j, nwK) - penaltyOf(i, j, oldK));
    }
    // util = 1/(1+penalty) 最大の違反セル割当を penalty+1（severity は既定 1.0 のみ使用＝Kotlin 呼出と同じ）。
    bool penalizeWorst(const int* a, const std::vector<int>& cells) {
        long long bestKey = -1;
        double bestUtil = -1.0;
        for (int flat : cells) {
            int i = flat / T, j = flat % T;
            if (i < 0 || i >= S || j < 0 || j >= T) continue;
            int k = a[(size_t)i * T + j];
            if (k < 0 || k >= K) continue;
            double util = 1.0 / (1.0 + penaltyOf(i, j, k));
            if (util > bestUtil) { bestUtil = util; bestKey = ((long long)i * T + j) * K + k; }
        }
        if (bestKey < 0) return false;
        pen[(size_t)bestKey]++;
        kicks++;
        return true;
    }
    // [GLS aging] keepPercent% へ整数床で減衰（Kotlin decay と同算術）。戻り値=非ゼロ項目数。
    int decay(int keepPercent = 80) {
        int nonZero = 0;
        for (auto& v : pen) { v = v * keepPercent / 100; if (v > 0) nonZero++; }
        return nonZero;
    }
};

// V6SearchOperators.glsAccept と同式（AcceptMode: 0=SA, 1=GREAT_DELUGE, 2=LAM_ADAPTIVE）。
inline bool glsAcceptN(long long ns, long long curScore, double moveAug, double curAug,
                       int mode, double temp, double gdLevel, double u01) {
    // [3.213.0見落とし修正] M(=SCORE_HARD_UNIT)を1e6→1e9へ拡大した際にこの閾値だけ旧スケール
    //   (2*1e6)のまま残っていた。Kotlin側(V6SearchOperators.glsAccept)と同期し2*1e9へ。
    if (ns > curScore + 2000000000LL) return false;
    if (mode == 1) {
        return ((double)ns + curAug + moveAug) <= gdLevel && (ns / 1000000000LL) <= (curScore / 1000000000LL);
    }
    double delta = (double)(ns - curScore) + moveAug;
    if (delta <= 0.0) return true;
    return u01 < std::exp(-(delta > 0.0 ? delta : 0.0) / (200.0 * temp + 1e-9));
}

// ============ [第2期 Stage6] soft-aware 修復3種 ＋ ターゲット修正8種 ============
// V6NativeOptimizer.destroyRepair* / V6SearchOperators.find*Fix の忠実移植。
// 盤面(平坦 a)と counts(ssn/dsn 相当)を引数に取り、Kotlin と同じ選択規則で動く
// （乱数系列は別物＝経路一致は狙わない。採否は呼び出し側の受理と番兵が担保）。

inline int rnInt(std::mt19937_64& rng, int bound) { return (int)(rng() % (uint64_t)bound); }
// staffCountPenaltyAt と同式（低90/高45/apt L1、n=回数）。
inline long long staffCountPenaltyAtN(const MagiProblem& p, int i, int k, int n) {
    long long pen = 0;
    int lo = p.rangeLo[(size_t)i * p.K + k], hi = p.rangeHi[(size_t)i * p.K + k];
    if (lo != INT32_MIN && lo != 0 && n < lo) pen += (long long)(lo - n) * 90;
    if (hi != INT32_MAX && n > hi) pen += (long long)(n - hi) * 45;
    int t = p.apt[(size_t)i * p.K + k];
    if (t >= 0) pen += std::llabs((long long)n - t);
    return pen;
}

void randomAllowedCellN(const MagiProblem& p, int* a, std::mt19937_64& rng) {
    if (p.S == 0 || p.T == 0) return;
    int i = rnInt(rng, p.S), j = rnInt(rng, p.T);
    if (wishLockedN(p, i, j)) return;
    const auto& allowed = p.bucket[p.sgrp[i]];
    if (!allowed.empty()) a[(size_t)i * p.T + j] = allowed[rnInt(rng, (int)allowed.size())];
}

// destroyRepairDayAt: 非希望セルを休へ destroy → need1 の不足を marginal soft（個人low/high/apt＋群c41）最小の休職員で repair。
void destroyRepairDayAtN(const MagiProblem& p, int* a, int j, std::mt19937_64&) {
    const int S = p.S, T = p.T, K = p.K, rest = p.restIdx;
    if (T == 0 || rest < 0 || rest >= K) return;
    std::vector<int> cnt((size_t)S * K, 0);
    for (int i = 0; i < S; i++) for (int jj = 0; jj < T; jj++) {
        int k = a[(size_t)i * T + jj];
        if (k >= 0 && k < K) cnt[(size_t)i * K + k]++;
    }
    for (int i = 0; i < S; i++) {
        if (wishLockedN(p, i, j) || !p.cd(i, rest)) continue;
        int old = a[(size_t)i * T + j];
        if (old != rest && old >= 0 && old < K) { a[(size_t)i * T + j] = rest; cnt[(size_t)i * K + old]--; cnt[(size_t)i * K + rest]++; }
    }
    std::vector<int> covJ(K, 0);
    for (int i = 0; i < S; i++) { int k = a[(size_t)i * T + j]; if (k >= 0 && k < K) covJ[k]++; }
    const bool hasC41 = !p.cons41.empty();
    std::vector<int> grpCnt(hasC41 ? (size_t)p.G * K : 0, 0);
    if (hasC41) for (int i = 0; i < S; i++) {
        int k = a[(size_t)i * T + j];
        if (k >= 0 && k < K) grpCnt[(size_t)p.sgrp[i] * K + k]++;
    }
    auto c41DayMarg = [&](int g, int k) -> long long {
        if (!hasC41) return 0;
        long long d = 0;
        for (const auto& c : p.cons41) {
            if (c.g != g || c.s != k) continue;
            int z = grpCnt[(size_t)g * K + k], z1 = z + 1;
            int before = (z < c.l ? c.l - z : 0) + (z > c.u ? z - c.u : 0);
            int after = (z1 < c.l ? c.l - z1 : 0) + (z1 > c.u ? z1 - c.u : 0);
            d += after - before;
        }
        return d;
    };
    for (int k = 0; k < K; k++) {
        if (k == rest) continue;
        int need = p.need1[(size_t)k * T + j];
        if (need <= 0) continue;
        int miss = need - covJ[k];
        while (miss > 0) {
            int bestI = -1;
            long long bestDelta = INT64_MAX;
            for (int i = 0; i < S; i++) {
                if (a[(size_t)i * T + j] != rest || wishLockedN(p, i, j) || !p.cd(i, k)) continue;
                long long delta = staffCountPenaltyAtN(p, i, k, cnt[(size_t)i * K + k] + 1)
                    - staffCountPenaltyAtN(p, i, k, cnt[(size_t)i * K + k]) + c41DayMarg(p.sgrp[i], k);
                if (delta < bestDelta) { bestDelta = delta; bestI = i; }
            }
            if (bestI < 0) break;
            a[(size_t)bestI * T + j] = k;
            cnt[(size_t)bestI * K + k]++; cnt[(size_t)bestI * K + rest]--;
            covJ[k]++; miss--;
            if (hasC41) grpCnt[(size_t)p.sgrp[bestI] * K + k]++;
        }
    }
}

// destroyRepairStaffAt: 職員 i の非希望セルを休へ → 各日の被覆穴を marginal soft 最小のシフトで repair（covO を作らない）。
void destroyRepairStaffAtN(const MagiProblem& p, int* a, int i, std::mt19937_64&) {
    const int S = p.S, T = p.T, K = p.K, rest = p.restIdx;
    const auto& allowed = p.bucket[p.sgrp[i]];
    if (allowed.empty() || rest < 0 || rest >= K || !p.cd(i, rest)) return;
    std::vector<int> cntI(K, 0);
    for (int jj = 0; jj < T; jj++) { int k = a[(size_t)i * T + jj]; if (k >= 0 && k < K) cntI[k]++; }
    for (int j = 0; j < T; j++) {
        if (wishLockedN(p, i, j)) continue;
        int old = a[(size_t)i * T + j];
        if (old != rest && old >= 0 && old < K) { a[(size_t)i * T + j] = rest; cntI[old]--; cntI[rest]++; }
    }
    std::vector<int> cov((size_t)T * K, 0);
    for (int x = 0; x < S; x++) for (int j = 0; j < T; j++) {
        int k2 = a[(size_t)x * T + j];
        if (k2 >= 0 && k2 < K) cov[(size_t)j * K + k2]++;
    }
    for (int j = 0; j < T; j++) {
        if (wishLockedN(p, i, j) || a[(size_t)i * T + j] != rest) continue;
        int bestK = -1;
        long long bestDelta = INT64_MAX;
        for (int k = 0; k < K; k++) {
            if (k == rest || !p.cd(i, k)) continue;
            int need = p.need1[(size_t)k * T + j];
            if (need <= 0) continue;
            if (cov[(size_t)j * K + k] >= need) continue;
            long long delta = staffCountPenaltyAtN(p, i, k, cntI[k] + 1) - staffCountPenaltyAtN(p, i, k, cntI[k]);
            if (delta < bestDelta) { bestDelta = delta; bestK = k; }
        }
        if (bestK >= 0) {
            a[(size_t)i * T + j] = bestK;
            cntI[bestK]++; cntI[rest]--;
            cov[(size_t)j * K + bestK]++; cov[(size_t)j * K + rest]--;
        }
    }
}

// destroyRepairViolations: 違反セル(hint)から最大8セルを marginal soft 最小の担当可シフトへ再割当。
void destroyRepairViolationsN(const MagiProblem& p, int* a, const std::vector<int>& cells, std::mt19937_64& rng) {
    const int T = p.T, K = p.K;
    if (cells.empty()) { randomAllowedCellN(p, a, rng); return; }
    int reps = (int)cells.size() < 8 ? (int)cells.size() : 8;
    for (int r = 0; r < reps; r++) {
        int flat = cells[rnInt(rng, (int)cells.size())];
        int i = flat / T, j = flat % T;
        if (i < 0 || i >= p.S || j < 0 || j >= T || wishLockedN(p, i, j)) continue;
        const auto& allowed = p.bucket[p.sgrp[i]];
        if (allowed.empty()) continue;
        std::vector<int> cntI(K, 0);
        for (int jj = 0; jj < T; jj++) { int k = a[(size_t)i * T + jj]; if (k >= 0 && k < K) cntI[k]++; }
        int old = a[(size_t)i * T + j];
        int bestK = old;
        long long bestDelta = INT64_MAX;
        for (int k : allowed) {
            if (k == old) continue;
            long long dOld = (old >= 0 && old < K)
                ? staffCountPenaltyAtN(p, i, old, cntI[old] - 1) - staffCountPenaltyAtN(p, i, old, cntI[old]) : 0;
            long long dK = staffCountPenaltyAtN(p, i, k, cntI[k] + 1) - staffCountPenaltyAtN(p, i, k, cntI[k]);
            if (dOld + dK < bestDelta) { bestDelta = dOld + dK; bestK = k; }
        }
        if (bestK != old) a[(size_t)i * T + j] = bestK;
    }
}

// ── find*Fix 群: SaChunk の a/ssn/dsn を読み [i, j, newK] を返す（無ければ {-1}）。
// eval.at → a / countForStaff → ssn / countOnDay → dsn の対応で V6SearchOperators と同式。
struct Fix { int i = -1, j = -1, k = -1; bool ok() const { return i >= 0; } };

Fix findCovOFixN(const MagiProblem& p, const SaChunk& st, std::mt19937_64& rng) {
    const int S = p.S, T = p.T, K = p.K;
    if (T == 0 || K == 0) return {};
    int j = rnInt(rng, T);
    int overK = -1, maxOver = 0;
    for (int k = 0; k < K; k++) {
        int lo = p.need1[(size_t)k * T + j];
        if (lo < 0) continue;
        int hi = (p.use2 && p.need2[(size_t)k * T + j] >= 0) ? p.need2[(size_t)k * T + j] : lo;
        int over = st.dsn[(size_t)j * K + k] - hi;
        if (over > maxOver) { maxOver = over; overK = k; }
    }
    if (overK < 0) return {};
    int wCnt = 0;
    for (int i = 0; i < S; i++) if (st.a[(size_t)i * T + j] == overK && !wishLockedN(p, i, j)) wCnt++;
    if (wCnt == 0) return {};
    int pickW = rnInt(rng, wCnt), sel = 0;
    for (int ii = 0; ii < S; ii++) if (st.a[(size_t)ii * T + j] == overK && !wishLockedN(p, ii, j)) { if (pickW-- == 0) { sel = ii; break; } }
    int bestNw = -1, bestDef = INT32_MIN;
    for (int k = 0; k < K; k++) {
        if (k == overK || !p.cd(sel, k)) continue;
        int lo = p.need1[(size_t)k * T + j];
        int def = lo >= 0 ? lo - st.dsn[(size_t)j * K + k] : 0;
        if (def > bestDef) { bestDef = def; bestNw = k; }
    }
    return bestNw >= 0 ? Fix{sel, j, bestNw} : Fix{};
}

Fix findC2FixN(const MagiProblem& p, const SaChunk& st, std::mt19937_64& rng) {
    const int S = p.S, T = p.T, K = p.K;
    if (p.cons2.empty()) return {};
    const auto& c = p.cons2[rnInt(rng, (int)p.cons2.size())];
    int dCnt = 0;
    for (int i = 0; i < S; i++) { if (!p.cd(i, c.si)) continue; if (st.ssn[(size_t)i * K + c.si] < c.c) dCnt++; }
    if (dCnt == 0) return {};
    int pickI = rnInt(rng, dCnt), stf = 0;
    for (int i = 0; i < S; i++) { if (!p.cd(i, c.si)) continue; if (st.ssn[(size_t)i * K + c.si] < c.c) { if (pickI-- == 0) { stf = i; break; } } }
    int dayCnt = 0;
    for (int j = 0; j < T; j++) if (st.a[(size_t)stf * T + j] != c.si && !wishLockedN(p, stf, j)) dayCnt++;
    if (dayCnt == 0) return {};
    int pickJ = rnInt(rng, dayCnt), day = 0;
    for (int j = 0; j < T; j++) if (st.a[(size_t)stf * T + j] != c.si && !wishLockedN(p, stf, j)) { if (pickJ-- == 0) { day = j; break; } }
    return Fix{stf, day, c.si};
}

Fix findRangeLowFixN(const MagiProblem& p, const SaChunk& st, std::mt19937_64& rng) {
    const int S = p.S, T = p.T, K = p.K;
    int cCnt = 0;
    for (int i = 0; i < S; i++) for (int k = 0; k < K; k++) {
        int lo = p.rangeLo[(size_t)i * K + k];
        if (lo == INT32_MIN || !p.cd(i, k)) continue;
        if (st.ssn[(size_t)i * K + k] < lo) cCnt++;
    }
    if (cCnt == 0) return {};
    int pickC = rnInt(rng, cCnt), rlI = 0, rlK = 0;
    bool done = false;
    for (int i = 0; i < S && !done; i++) for (int k = 0; k < K && !done; k++) {
        int lo = p.rangeLo[(size_t)i * K + k];
        if (lo == INT32_MIN || !p.cd(i, k)) continue;
        if (st.ssn[(size_t)i * K + k] < lo) { if (pickC-- == 0) { rlI = i; rlK = k; done = true; } }
    }
    int dayCnt = 0;
    for (int j = 0; j < T; j++) if (st.a[(size_t)rlI * T + j] != rlK && !wishLockedN(p, rlI, j)) dayCnt++;
    if (dayCnt == 0) return {};
    int pickJ = rnInt(rng, dayCnt), day = 0;
    for (int j = 0; j < T; j++) if (st.a[(size_t)rlI * T + j] != rlK && !wishLockedN(p, rlI, j)) { if (pickJ-- == 0) { day = j; break; } }
    return Fix{rlI, day, rlK};
}

Fix findRangeHighFixN(const MagiProblem& p, const SaChunk& st, std::mt19937_64& rng) {
    const int S = p.S, T = p.T, K = p.K;
    int cCnt = 0;
    for (int i = 0; i < S; i++) for (int k = 0; k < K; k++) {
        int hi = p.rangeHi[(size_t)i * K + k];
        if (hi == INT32_MAX) continue;
        if (st.ssn[(size_t)i * K + k] > hi) cCnt++;
    }
    if (cCnt == 0) return {};
    int pickC = rnInt(rng, cCnt), rhI = 0, rhK = 0;
    bool done = false;
    for (int i = 0; i < S && !done; i++) for (int k = 0; k < K && !done; k++) {
        int hi = p.rangeHi[(size_t)i * K + k];
        if (hi == INT32_MAX) continue;
        if (st.ssn[(size_t)i * K + k] > hi) { if (pickC-- == 0) { rhI = i; rhK = k; done = true; } }
    }
    int dayCnt = 0;
    for (int j = 0; j < T; j++) if (st.a[(size_t)rhI * T + j] == rhK && !wishLockedN(p, rhI, j)) dayCnt++;
    if (dayCnt == 0) return {};
    int pickJ = rnInt(rng, dayCnt), day = 0;
    for (int j = 0; j < T; j++) if (st.a[(size_t)rhI * T + j] == rhK && !wishLockedN(p, rhI, j)) { if (pickJ-- == 0) { day = j; break; } }
    const auto& allowed = p.bucket[p.sgrp[rhI]];
    int oCnt = 0;
    for (int ak : allowed) if (ak != rhK) oCnt++;
    if (oCnt == 0) return {};
    int pickK = rnInt(rng, oCnt), nwK = 0;
    for (int ak : allowed) if (ak != rhK) { if (pickK-- == 0) { nwK = ak; break; } }
    return Fix{rhI, day, nwK};
}

// c41/c41s（群/スキル群の日次レンジ）: 超過なら群内の1人を別シフトへ、不足なら群内の1人を対象シフトへ。
Fix findC41FamFixN(const MagiProblem& p, const SaChunk& st, std::mt19937_64& rng,
                   const std::vector<C41r>& cons, const std::vector<int>& grp) {
    const int S = p.S, T = p.T;
    if (cons.empty() || T == 0) return {};
    const auto& c = cons[rnInt(rng, (int)cons.size())];
    int j = rnInt(rng, T);
    int cnt = 0;
    for (int i = 0; i < S; i++) if (grp[i] == c.g && st.a[(size_t)i * T + j] == c.s) cnt++;
    if (cnt > c.u) {
        int wCnt = 0;
        for (int i = 0; i < S; i++) if (grp[i] == c.g && st.a[(size_t)i * T + j] == c.s && !wishLockedN(p, i, j)) wCnt++;
        if (wCnt == 0) return {};
        int pickW = rnInt(rng, wCnt), ci = 0;
        for (int i = 0; i < S; i++) if (grp[i] == c.g && st.a[(size_t)i * T + j] == c.s && !wishLockedN(p, i, j)) { if (pickW-- == 0) { ci = i; break; } }
        const auto& allowed = p.bucket[p.sgrp[ci]];
        int oCnt = 0;
        for (int ak : allowed) if (ak != c.s) oCnt++;
        if (oCnt == 0) return {};
        int pickK = rnInt(rng, oCnt), nwK = 0;
        for (int ak : allowed) if (ak != c.s) { if (pickK-- == 0) { nwK = ak; break; } }
        return Fix{ci, j, nwK};
    }
    if (cnt < c.l) {
        int aCnt = 0;
        for (int i = 0; i < S; i++) if (grp[i] == c.g && st.a[(size_t)i * T + j] != c.s && !wishLockedN(p, i, j) && p.cd(i, c.s)) aCnt++;
        if (aCnt == 0) return {};
        int pickA = rnInt(rng, aCnt), ai = 0;
        for (int i = 0; i < S; i++) if (grp[i] == c.g && st.a[(size_t)i * T + j] != c.s && !wishLockedN(p, i, j) && p.cd(i, c.s)) { if (pickA-- == 0) { ai = i; break; } }
        return Fix{ai, j, c.s};
    }
    return {};
}

Fix findC3WantFixN(const MagiProblem& p, const SaChunk& st, std::mt19937_64& rng) {
    const int S = p.S, T = p.T;
    const std::vector<C3r>* list;
    if (!p.cons3.empty() && !p.cons3m.empty()) list = (rng() & 1) ? &p.cons3 : &p.cons3m;
    else if (!p.cons3.empty()) list = &p.cons3;
    else if (!p.cons3m.empty()) list = &p.cons3m;
    else return {};
    const auto& c = (*list)[rnInt(rng, (int)list->size())];
    const int D = (int)c.seq.size();
    if (D < 2 || D > T) return {};
    int iStart = rnInt(rng, S);
    for (int di = 0; di < S; di++) {
        int i = (iStart + di) % S;
        const int* row = st.a.data() + (size_t)i * T;
        for (int j = 0; j <= T - D; j++) {
            if (row[j] == c.seq[0]) {
                int miss = 0, missL = -1;
                for (int l = 1; l < D; l++) {
                    if (row[j + l] != c.seq[l]) { miss++; if (miss > 1) break; missL = l; }
                }
                if (miss == 1 && missL >= 0) {
                    int ml = j + missL;
                    if (!wishLockedN(p, i, ml) && p.cd(i, c.seq[missL])) return Fix{i, ml, c.seq[missL]};
                }
            }
        }
    }
    return {};
}

Fix findAptFixN(const MagiProblem& p, const SaChunk& st, std::mt19937_64& rng) {
    const int S = p.S, T = p.T, K = p.K;
    if (S == 0 || T == 0) return {};
    std::vector<int> order(S);
    for (int i = 0; i < S; i++) order[i] = i;
    for (int i = S - 1; i >= 1; i--) { int j = rnInt(rng, i + 1); std::swap(order[i], order[j]); }
    for (int i : order) {
        const auto& allowed = p.bucket[p.sgrp[i]];
        if (allowed.empty()) continue;
        int kOver = -1, kUnder = -1;
        for (int k = 0; k < K; k++) {
            int tg = p.apt[(size_t)i * K + k];
            if (tg < 0) continue;
            int n = st.ssn[(size_t)i * K + k];
            if (kOver < 0 && n > tg) kOver = k;
            if (kUnder < 0 && n < tg) {
                bool can = false;
                for (int ak : allowed) if (ak == k) { can = true; break; }
                if (can) kUnder = k;
            }
        }
        if (kOver < 0 || kUnder < 0 || kOver == kUnder) continue;
        int dayStart = rnInt(rng, T);
        for (int d = 0; d < T; d++) {
            int j = (dayStart + d) % T;
            if (!wishLockedN(p, i, j) && st.a[(size_t)i * T + j] == kOver) return Fix{i, j, kUnder};
        }
    }
    return {};
}

// findTargetedFix: 8種を一様シャッフル順に試し最初の手を返す（Kotlin と同順序集合）。
Fix findTargetedFixN(const MagiProblem& p, const SaChunk& st, std::mt19937_64& rng) {
    int order[8] = {0, 1, 2, 3, 4, 5, 6, 7};
    for (int i = 7; i >= 1; i--) { int j = rnInt(rng, i + 1); std::swap(order[i], order[j]); }
    for (int idx : order) {
        Fix f;
        switch (idx) {
            case 0: f = findCovOFixN(p, st, rng); break;
            case 1: f = findC2FixN(p, st, rng); break;
            case 2: f = findRangeLowFixN(p, st, rng); break;
            case 3: f = findC41FamFixN(p, st, rng, p.cons41, p.sgrp); break;
            case 4: f = findRangeHighFixN(p, st, rng); break;
            case 5: f = findC41FamFixN(p, st, rng, p.cons41s, p.ssk); break;
            case 6: f = findC3WantFixN(p, st, rng); break;
            default: f = findAptFixN(p, st, rng); break;
        }
        if (f.ok()) return f;
    }
    return {};
}

// ============ [第2期 Stage7] hf67HardRepair 移植 ============
// hf66DataHardening（範囲外/担当外→先頭担当可）→ 実現可能希望の適用 → 被覆不足の3周充填 →
// range下限充填、の合成（V6NativeOptimizer.hf67HardRepair と同順・同式）。in-place 変異。

// coverageShortageCost: セル(j,k) から1人引き抜くと per-cell 実需要(U)の不足が増えるなら 50。
inline int coverageShortageCostN(const MagiProblem& p, const int* a, int j, int k) {
    if (k < 0 || k >= p.K) return 0;
    int cov = 0;
    for (int i = 0; i < p.S; i++) if (a[(size_t)i * p.T + j] == k) cov++;
    return p.covUCell(k, j, cov - 1) > p.covUCell(k, j, cov) ? 50 : 0;
}

// bestStaffForCoverage: (j,k) の被覆に入れる職員を「上限超過500＋現回数×3＋引き抜き不足コスト」最小で選ぶ。
inline int bestStaffForCoverageN(const MagiProblem& p, const int* a, const std::vector<int>& counts, int j, int k) {
    int bestI = -1, bestScore = INT32_MAX;
    for (int i = 0; i < p.S; i++) {
        if (!p.cd(i, k)) continue;
        if (wishLockedN(p, i, j) && p.wish[(size_t)i * p.T + j] != k) continue;
        int old = a[(size_t)i * p.T + j];
        if (old == k) continue;
        int hi = p.rangeHi[(size_t)i * p.K + k];
        int over = (hi != INT32_MAX && counts[(size_t)i * p.K + k] >= hi) ? 500 : 0;
        int score = over + counts[(size_t)i * p.K + k] * 3 + coverageShortageCostN(p, a, j, old);
        if (score < bestScore) { bestScore = score; bestI = i; }
    }
    return bestI;
}

int hf67HardRepairN(const MagiProblem& p, int* a, std::mt19937_64& rng) {
    const int S = p.S, T = p.T, K = p.K;
    int changed = 0;
    // hf66DataHardening: 範囲外・担当外セルを先頭の担当可シフト（無ければ0）へ。
    for (int i = 0; i < S; i++) {
        const auto& allowed = p.bucket[p.sgrp[i]];
        int fallback = allowed.empty() ? 0 : allowed[0];
        for (int j = 0; j < T; j++) {
            int k = a[(size_t)i * T + j];
            if (k < 0 || k >= K || !p.cd(i, k)) a[(size_t)i * T + j] = fallback;
        }
    }
    // 実現可能な希望を適用（不可能希望は強制しない=Sanityの領分）。
    for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) {
        int w = p.wish[(size_t)i * T + j];
        if (w >= 0 && w < K && p.cd(i, w) && a[(size_t)i * T + j] != w) { a[(size_t)i * T + j] = w; changed++; }
    }
    // 被覆不足の充填 ×3周（counts は周の頭で再計算・周内は Kotlin と同じく据え置き）。
    for (int rep = 0; rep < 3; rep++) {
        std::vector<int> cov((size_t)T * K, 0), counts((size_t)S * K, 0);
        for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) {
            int k = a[(size_t)i * T + j];
            if (k >= 0 && k < K) { cov[(size_t)j * K + k]++; counts[(size_t)i * K + k]++; }
        }
        for (int j = 0; j < T; j++) for (int k = 0; k < K; k++) {
            int miss = p.covUCell(k, j, cov[(size_t)j * K + k]);
            while (miss > 0) {
                int i = bestStaffForCoverageN(p, a, counts, j, k);
                if (i < 0) break;
                int old = a[(size_t)i * T + j];
                if (old == k) break;
                a[(size_t)i * T + j] = k;
                cov[(size_t)j * K + k]++;
                if (old >= 0 && old < K) cov[(size_t)j * K + old]--;
                changed++; miss--;
            }
        }
    }
    // range 下限の充填（希望ロックは触らない・引き抜き不足コスト＋乱数タイブレーク最小の日）。
    std::vector<int> counts((size_t)S * K, 0);
    for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) {
        int k = a[(size_t)i * T + j];
        if (k >= 0 && k < K) counts[(size_t)i * K + k]++;
    }
    for (int i = 0; i < S; i++) for (int k = 0; k < K; k++) {
        int lo = p.rangeLo[(size_t)i * K + k];
        if (lo == INT32_MIN || !p.cd(i, k)) continue;
        int need = lo - counts[(size_t)i * K + k];
        int guard = 0;
        while (need > 0 && guard++ < T) {
            int bestJ = -1, bestScore = INT32_MAX;
            for (int jj = 0; jj < T; jj++) {
                if (wishLockedN(p, i, jj) || a[(size_t)i * T + jj] == k) continue;
                int score = coverageShortageCostN(p, a, jj, a[(size_t)i * T + jj]) + rnInt(rng, 3);
                if (score < bestScore) { bestScore = score; bestJ = jj; }
            }
            if (bestJ < 0) break;
            int old = a[(size_t)i * T + bestJ];
            a[(size_t)i * T + bestJ] = k;
            if (old >= 0 && old < K) counts[(size_t)i * K + old]--;
            counts[(size_t)i * K + k]++;
            changed++; need--;
        }
    }
    return changed;
}

// ============ [第2期 Stage8] ALNS チャンク統合 ============
// runAlns の反復ループ（V6NativeOptimizer 404-597）を 1チャンク=N反復で C++ 内完走する。
// Kotlin 保持: restart境界(perturb+hf67入口)・時間/キャンセル・進捗/liveBest・2層番兵。
// 状態は AlnsState がチャンクを跨いで保持（GLS・適応重み・Lam温度・best・停滞カウンタ）。
// 温度/GD水位の frac はチャンク単位で固定（200反復≒ms級のため clock 粒度の意味差は無視できる）。

int rouletteSelectN(const double* w, int n, std::mt19937_64& rng) {
    double sum = 0.0;
    for (int i = 0; i < n; i++) sum += w[i];
    if (sum <= 0.0) return rnInt(rng, n);
    double r = ((double)(rng() >> 11) * 0x1.0p-53) * sum;
    for (int i = 0; i < n; i++) { r -= w[i]; if (r <= 0.0) return i; }
    return n - 1;
}
int thompsonSelectN(const double* w, int n, long long iter, std::mt19937_64& rng) {
    double sigma = 0.5 / std::sqrt(1.0 + (double)iter / 500.0);
    int bestOp = 0;
    double bestSample = -1e300;
    for (int k = 0; k < n; k++) {
        double u1 = (double)(rng() >> 11) * 0x1.0p-53;
        if (u1 < 1e-9) u1 = 1e-9;
        double u2 = (double)(rng() >> 11) * 0x1.0p-53;
        double g = std::sqrt(-2.0 * std::log(u1)) * std::cos(2.0 * 3.14159265358979323846 * u2);
        double s = w[k] + g * sigma;
        if (s > bestSample) { bestSample = s; bestOp = k; }
    }
    return bestOp;
}
inline double greatDelugeLevelN(double initial, double best, double frac) {
    if (frac < 0.0) frac = 0.0;
    if (frac > 1.0) frac = 1.0;
    return best + (initial - best) * frac;
}

constexpr long long GLS_TRIGGER_N = 200;
constexpr int GLS_DECAY_EVERY_N = 256;

struct AlnsState {
    const MagiProblem& p;
    SaChunk st;                  // cur 盤面＋counts＋生スコア（差分維持）
    GlsPenaltyN gls;
    double curAug = 0.0;
    std::vector<int> bestSol;
    long long bestScore;
    double opW[7] = {1, 1, 1, 1, 1, 1, 1};
    double opScore[7] = {0, 0, 0, 0, 0, 0, 0};
    int opCnt[7] = {0, 0, 0, 0, 0, 0, 0};
    int sinceUpdate = 0;
    double lamTemp, lamAcc = 0.44;
    double gdInitial;
    long long itersRestart = 0, itersSinceImprove = 0;
    int acceptMode;              // 0=SA, 1=GD, 2=LAM
    int opSelectMode;            // 0=roulette, 1=thompson
    double explore;
    std::vector<int> vioCells;
    std::vector<int> scratch, diffFlat, diffOld;

    AlnsState(const MagiProblem& prob, const int* cur, uint64_t seed, int accept, int opSel, double expl)
        : p(prob), st(prob, cur, seed), gls(prob.S, prob.T, prob.K),
          bestSol(cur, cur + (size_t)prob.S * prob.T),
          acceptMode(accept), opSelectMode(opSel), explore(expl) {
        bestScore = st.score;
        gdInitial = (double)st.score;
        lamTemp = expl > 1.0 ? expl : 1.0;
        scratch.resize((size_t)prob.S * prob.T);
        diffFlat.resize((size_t)prob.S * prob.T);
        diffOld.resize((size_t)prob.S * prob.T);
    }

    void lamUpdate(bool accepted, double frac) {
        lamAcc = 0.97 * lamAcc + 0.03 * (accepted ? 1.0 : 0.0);
        double target = frac > 0.85 ? 0.44 : (frac > 0.15 ? 0.44 * (frac - 0.15) / 0.70 : 0.0);
        lamTemp *= (lamAcc > target) ? 0.998 : 1.002;
        if (lamTemp < 0.03) lamTemp = 0.03;
        if (lamTemp > 4.0) lamTemp = 4.0;
    }
};

// 1チャンク＝iters 反復。戻り out[6]: [status, curScore, bestScore, bestImproved, itersDone, kicks]。
// status: 0=OK / 1=cur整合性NG / 2=best整合性NG（Kotlin側が破棄・NativeGate退化）。
void runAlnsChunk(AlnsState& s, int iters, double frac, long long out[6]) {
    const MagiProblem& p = s.p;
    const int S = p.S, T = p.T, K = p.K;
    auto& st = s.st;
    auto& rng = st.rng;
    long long curScore = st.score;
    bool bestImproved = false;

    // 違反セル hint はチャンク頭で更新（Kotlin の 200反復周期 curReport 更新に対応）。
    collectViolationCells(p, st.a.data(), s.vioCells);

    for (int it = 0; it < iters; it++) {
        int op = s.opSelectMode == 1 ? thompsonSelectN(s.opW, 7, s.itersRestart, rng)
                                     : rouletteSelectN(s.opW, 7, rng);
        long long globalHard = s.bestScore / 1000000000LL;
        long long curHard = curScore / 1000000000LL;
        double softFocusProb = globalHard == 0 ? 0.30 : 0.15;
        if (curHard <= globalHard && st.nextDouble() < softFocusProb) op = 5;
        double temp = s.acceptMode == 2 ? s.lamTemp
                      : (frac * s.explore > 0.03 ? frac * s.explore : 0.03);
        double gdLevel = s.acceptMode == 1 ? greatDelugeLevelN(s.gdInitial, (double)s.bestScore, frac) : 0.0;
        double reward = 0.2;
        bool armed = false;         // opScore/opCnt を更新するか（Kotlin: op0-2=常時 / op3-6=moved時のみ）

        if (op >= 3 && op <= 6) {
            bool moved = false;
            long long ns = curScore;
            double moveAug = 0.0;
            int c0i = -1, c0j = -1, c0old = -1, c1i = -1, c1j = -1, c1old = -1;
            if (op == 3 && S > 0 && T >= 2) {           // swapWithinStaff
                int i = rnInt(rng, S);
                int ja = rnInt(rng, T), jb = rnInt(rng, T);
                if (ja == jb) jb = (jb + 1) % T;
                if (!wishLockedN(p, i, ja) && !wishLockedN(p, i, jb)) {
                    int ka = st.a[(size_t)i * T + ja], kb = st.a[(size_t)i * T + jb];
                    if (ka != kb) {
                        st.deltaApply(i, ja, kb); st.deltaApply(i, jb, ka);
                        c0i = i; c0j = ja; c0old = ka; c1i = i; c1j = jb; c1old = kb;
                        moveAug = s.gls.moveAug(i, ja, ka, kb) + s.gls.moveAug(i, jb, kb, ka);
                        ns = st.score; moved = true;
                    }
                }
            } else if (op == 4 && S > 0 && T > 0) {     // randomAllowedCell
                int i = rnInt(rng, S), j = rnInt(rng, T);
                if (!wishLockedN(p, i, j)) {
                    const auto& allowed = p.bucket[p.sgrp[i]];
                    if (!allowed.empty()) {
                        int oldK = st.a[(size_t)i * T + j];
                        int nw = allowed[rnInt(rng, (int)allowed.size())];
                        if (nw != oldK) {
                            st.deltaApply(i, j, nw);
                            c0i = i; c0j = j; c0old = oldK;
                            moveAug = s.gls.moveAug(i, j, oldK, nw);
                            ns = st.score; moved = true;
                        }
                    }
                }
            } else if (op == 5) {                        // targeted single-cell repair
                Fix fix = findTargetedFixN(p, st, rng);
                if (fix.ok()) {
                    int oldK = st.a[(size_t)fix.i * T + fix.j];
                    if (fix.k != oldK) {
                        st.deltaApply(fix.i, fix.j, fix.k);
                        c0i = fix.i; c0j = fix.j; c0old = oldK;
                        moveAug = s.gls.moveAug(fix.i, fix.j, oldK, fix.k);
                        ns = st.score; moved = true;
                    }
                }
            } else if (op == 6 && S >= 2 && T > 0) {     // swapTwoStaffSameDay
                int j = rnInt(rng, T);
                int i1 = rnInt(rng, S), i2 = rnInt(rng, S);
                if (i2 == i1) i2 = (i2 + 1) % S;
                if (!wishLockedN(p, i1, j) && !wishLockedN(p, i2, j)) {
                    int k1 = st.a[(size_t)i1 * T + j], k2 = st.a[(size_t)i2 * T + j];
                    if (k1 != k2 && p.cd(i1, k2) && p.cd(i2, k1)) {
                        st.deltaApply(i1, j, k2); st.deltaApply(i2, j, k1);
                        c0i = i1; c0j = j; c0old = k1; c1i = i2; c1j = j; c1old = k2;
                        moveAug = s.gls.moveAug(i1, j, k1, k2) + s.gls.moveAug(i2, j, k2, k1);
                        ns = st.score; moved = true;
                    }
                }
            }
            if (moved) {
                bool improvedCur = ns < curScore;
                bool accepted = improvedCur ||
                    glsAcceptN(ns, curScore, moveAug, s.curAug, s.acceptMode, temp, gdLevel, st.nextDouble());
                if (s.acceptMode == 2) s.lamUpdate(accepted, frac);
                if (accepted) {
                    curScore = ns;
                    s.curAug += moveAug;
                    if (ns < s.bestScore) {
                        std::memcpy(s.bestSol.data(), st.a.data(), sizeof(int) * (size_t)S * T);
                        s.bestScore = ns;
                        s.itersSinceImprove = 0;
                        bestImproved = true;
                        reward = 4.0;
                    } else reward = improvedCur ? 2.0 : 1.0;
                } else {
                    if (c1i >= 0) st.deltaApply(c1i, c1j, c1old);
                    st.deltaApply(c0i, c0j, c0old);
                }
                armed = true;
            }
        } else {
            // ── copy系(op0-2): scratch へ修復を適用し、差分だけ deltaApply ──
            std::memcpy(s.scratch.data(), st.a.data(), sizeof(int) * (size_t)S * T);
            switch (op) {
                case 0: if (T > 0) destroyRepairDayAtN(p, s.scratch.data(), rnInt(rng, T), rng); break;
                case 1: if (S > 0) destroyRepairStaffAtN(p, s.scratch.data(), rnInt(rng, S), rng); break;
                default: destroyRepairViolationsN(p, s.scratch.data(), s.vioCells, rng); break;
            }
            if (s.itersRestart % 7 == 0 && curHard > 0) hf67HardRepairN(p, s.scratch.data(), rng);
            int nDiffs = 0;
            double moveAug = 0.0;
            for (int f = 0; f < S * T; f++) {
                if (st.a[(size_t)f] != s.scratch[(size_t)f]) {
                    s.diffFlat[nDiffs] = f;
                    s.diffOld[nDiffs] = st.a[(size_t)f];
                    moveAug += s.gls.moveAug(f / T, f % T, st.a[(size_t)f], s.scratch[(size_t)f]);
                    nDiffs++;
                }
            }
            for (int d = 0; d < nDiffs; d++) {
                int f = s.diffFlat[d];
                st.deltaApply(f / T, f % T, s.scratch[(size_t)f]);
            }
            long long ns = st.score;
            bool improvedCur = ns < curScore;
            bool accepted = improvedCur ||
                glsAcceptN(ns, curScore, moveAug, s.curAug, s.acceptMode, temp, gdLevel, st.nextDouble());
            if (s.acceptMode == 2) s.lamUpdate(accepted, frac);
            if (accepted) {
                curScore = ns;
                s.curAug += moveAug;
                if (ns < s.bestScore) {
                    std::memcpy(s.bestSol.data(), st.a.data(), sizeof(int) * (size_t)S * T);
                    s.bestScore = ns;
                    s.itersSinceImprove = 0;
                    bestImproved = true;
                    reward = 4.0;
                } else reward = improvedCur ? 2.0 : 1.0;
            } else {
                for (int d = nDiffs - 1; d >= 0; d--) {
                    int f = s.diffFlat[d];
                    st.deltaApply(f / T, f % T, s.diffOld[d]);
                }
            }
            armed = true;
        }
        if (armed) { s.opScore[op] += reward; s.opCnt[op]++; }

        // GLS キック（停滞 GLS_TRIGGER 超・50反復毎）＋ 256キック毎の減衰。
        if (s.itersSinceImprove > GLS_TRIGGER_N && s.itersRestart % 50 == 0) {
            collectViolationCells(p, st.a.data(), s.vioCells);
            if (s.gls.penalizeWorst(st.a.data(), s.vioCells)) {
                s.curAug += s.gls.lambda;
                if (s.gls.kicks % GLS_DECAY_EVERY_N == 0) { s.gls.decay(); s.curAug = s.gls.augment(st.a.data()); }
            }
        }
        // 適応重みの更新（64反復毎・反応係数0.2・下限0.05）。
        if (++s.sinceUpdate >= 64) {
            for (int k = 0; k < 7; k++) {
                if (s.opCnt[k] > 0) {
                    s.opW[k] = 0.8 * s.opW[k] + 0.2 * (s.opScore[k] / s.opCnt[k]);
                    if (s.opW[k] < 0.05) s.opW[k] = 0.05;
                }
                s.opScore[k] = 0.0; s.opCnt[k] = 0;
            }
            s.sinceUpdate = 0;
        }
        s.itersRestart++;
        s.itersSinceImprove++;
        if (bestImproved && s.itersSinceImprove == 1) { /* 直前反復で更新済み＝カウンタは0起点 */ }
    }

    // ---- 番兵1層目: チャンク末尾の自己整合検査（差分スコア==フル再計算）----
    long long full = fullEvalCombined(p, st.a.data());
    long long status = 0;
    if (full != curScore || curScore != st.score) status = 1;
    else if (bestImproved && fullEvalCombined(p, s.bestSol.data()) != s.bestScore) status = 2;

    out[0] = status;
    out[1] = curScore;
    out[2] = s.bestScore;
    out[3] = bestImproved ? 1 : 0;
    out[4] = iters;
    out[5] = s.gls.kicks;
}

// ============================================================================
// [Stage10/第3期] hf80PostPolish（最終研磨）の C++ チャンク。
// Kotlin V6NativeOptimizer.hf80PostPolish と同一のオペ構成(11-way: 0=単一セル/
// 1=行内2日swap/2=同日2者swap/3..8=targetedFix/9-10=copy系DR+hard時hf67)・
// 同一受理(best-hard ゲート＋SA temp0.15固定)・keep-best。GLS/適応重みは無し
// (Kotlin 側に無いため)。番兵1層目=チャンク末尾の自己整合（差分スコア==フル再計算）。
// 2層目は Kotlin 側（best 改善チャンクの盤面を Evaluator.fullEval で Long== 照合）。
// ============================================================================
struct PolishState {
    const MagiProblem& p;
    SaChunk st;                         // cur 盤面＋差分スコア維持
    std::vector<int> bestSol;
    long long bestScore;
    std::vector<int> vioCells;          // 修復 hint（best 盤面の違反セル=Kotlin の bestReport.violations 相当）
    std::vector<int> scratch, diffFlat, diffOld;

    PolishState(const MagiProblem& prob, const int* cur, uint64_t seed)
        : p(prob), st(prob, cur, seed),
          bestSol(cur, cur + (size_t)prob.S * prob.T) {
        bestScore = st.score;
        scratch.resize((size_t)prob.S * prob.T);
        diffFlat.resize((size_t)prob.S * prob.T);
        diffOld.resize((size_t)prob.S * prob.T);
        // [全体計算の最小化] hint は best 盤面基準で、best 改善時にのみ変わる。生成時に1回収集し、
        // 以後は runPolishChunk 内の best 改善時のみ更新（旧: 毎チャンク頭の全面スキャン＝冗長）。
        collectViolationCells(p, bestSol.data(), vioCells);
    }
};

// V6SearchOperators.acceptWorseScore(temp=0.15) と同式: hard +2 超は却下、
// Δ<=0 受理、それ以外 exp(-Δ/(200*0.15)) = exp(-Δ/30)。
inline bool polishAcceptN(long long ns, long long cur, double u01) {
    // [3.213.0見落とし修正] Kotlin側(acceptWorseScore)と同じく2*SCORE_HARD_UNIT(=2e9)へ同期。
    if (ns > cur + 2000000000LL) return false;
    long long d = ns - cur;
    if (d <= 0) return true;
    return u01 < std::exp(-(double)d / 30.0);
}

// 1チャンク＝iters 反復。out[5]: [status, curScore, bestScore, bestImproved, itersDone]。
// status: 0=OK / 1=cur整合性NG / 2=best整合性NG（Kotlin 側が破棄・NativeGate 退化）。
void runPolishChunk(PolishState& s, int iters, long long out[5]) {
    const MagiProblem& p = s.p;
    const int S = p.S, T = p.T;
    auto& st = s.st;
    auto& rng = st.rng;
    long long curScore = st.score;
    bool bestImproved = false;
    // hint(vioCells) は PolishState 生成時＋best 改善時に更新済み（Kotlin の bestReport 相当＝同源・同鮮度）。

    for (int it = 0; it < iters; it++) {
        long long curHard = curScore / 1000000000LL;
        long long bestHard = s.bestScore / 1000000000LL;
        int op = rnInt(rng, 11);
        if (op <= 8) {
            // ── 単一/2セルの直接評価オペ（3..8 は targetedFix の6スロット＝Kotlin と同比率）──
            int c0i = -1, c0j = -1, c0old = -1, c1i = -1, c1j = -1, c1old = -1;
            bool moved = false;
            if (op == 0 && S > 0 && T > 0) {            // random allowed single cell
                int i = rnInt(rng, S), j = rnInt(rng, T);
                if (!wishLockedN(p, i, j)) {
                    const auto& allowed = p.bucket[p.sgrp[i]];
                    if (!allowed.empty()) {
                        int oldK = st.a[(size_t)i * T + j];
                        int nw = allowed[rnInt(rng, (int)allowed.size())];
                        if (nw != oldK) { st.deltaApply(i, j, nw); c0i = i; c0j = j; c0old = oldK; moved = true; }
                    }
                }
            } else if (op == 1 && S > 0 && T >= 2) {    // swap two days within one staff row
                int i = rnInt(rng, S);
                int ja = rnInt(rng, T), jb = rnInt(rng, T);
                if (ja == jb) jb = (jb + 1) % T;
                if (!wishLockedN(p, i, ja) && !wishLockedN(p, i, jb)) {
                    int ka = st.a[(size_t)i * T + ja], kb = st.a[(size_t)i * T + jb];
                    if (ka != kb) {
                        st.deltaApply(i, ja, kb); st.deltaApply(i, jb, ka);
                        c0i = i; c0j = ja; c0old = ka; c1i = i; c1j = jb; c1old = kb; moved = true;
                    }
                }
            } else if (op == 2 && S >= 2 && T > 0) {    // swap two staff on same day (coverage-neutral)
                int j = rnInt(rng, T);
                int i1 = rnInt(rng, S), i2 = rnInt(rng, S);
                if (i2 == i1) i2 = (i2 + 1) % S;
                if (!wishLockedN(p, i1, j) && !wishLockedN(p, i2, j)) {
                    int k1 = st.a[(size_t)i1 * T + j], k2 = st.a[(size_t)i2 * T + j];
                    if (k1 != k2 && p.cd(i1, k2) && p.cd(i2, k1)) {
                        st.deltaApply(i1, j, k2); st.deltaApply(i2, j, k1);
                        c0i = i1; c0j = j; c0old = k1; c1i = i2; c1j = j; c1old = k2; moved = true;
                    }
                }
            } else if (op >= 3) {                        // targeted single-cell fix
                Fix fix = findTargetedFixN(p, st, rng);
                if (fix.ok()) {
                    int oldK = st.a[(size_t)fix.i * T + fix.j];
                    if (fix.k != oldK) { st.deltaApply(fix.i, fix.j, fix.k); c0i = fix.i; c0j = fix.j; c0old = oldK; moved = true; }
                }
            }
            if (moved) {
                long long ns = st.score;
                if (ns / 1000000000LL <= bestHard && (ns < curScore || polishAcceptN(ns, curScore, st.nextDouble()))) {
                    curScore = ns;
                    if (ns < s.bestScore) {
                        std::memcpy(s.bestSol.data(), st.a.data(), sizeof(int) * (size_t)S * T);
                        s.bestScore = ns; bestImproved = true;
                        collectViolationCells(p, st.a.data(), s.vioCells);   // hint を新 best で更新（Kotlin の bestReport 更新と同鮮度）
                    }
                } else {
                    if (c1i >= 0) st.deltaApply(c1i, c1j, c1old);
                    if (c0i >= 0) st.deltaApply(c0i, c0j, c0old);
                }
            }
        } else {
            // ── copy系 op9-10: violations 50% / day 50%、hard>0 なら hf67（Kotlin と同一条件）──
            std::memcpy(s.scratch.data(), st.a.data(), sizeof(int) * (size_t)S * T);
            if (rnInt(rng, 2) == 0) destroyRepairViolationsN(p, s.scratch.data(), s.vioCells, rng);
            else if (T > 0) destroyRepairDayAtN(p, s.scratch.data(), rnInt(rng, T), rng);
            if (curHard > 0) hf67HardRepairN(p, s.scratch.data(), rng);
            int nDiffs = 0;
            for (int f = 0; f < S * T; f++) {
                if (st.a[(size_t)f] != s.scratch[(size_t)f]) {
                    s.diffFlat[nDiffs] = f; s.diffOld[nDiffs] = st.a[(size_t)f]; nDiffs++;
                }
            }
            for (int d = 0; d < nDiffs; d++) { int f = s.diffFlat[d]; st.deltaApply(f / T, f % T, s.scratch[(size_t)f]); }
            long long ns = st.score;
            if (ns / 1000000000LL <= bestHard && (ns < curScore || polishAcceptN(ns, curScore, st.nextDouble()))) {
                curScore = ns;
                if (ns < s.bestScore) {
                    std::memcpy(s.bestSol.data(), st.a.data(), sizeof(int) * (size_t)S * T);
                    s.bestScore = ns; bestImproved = true;
                    collectViolationCells(p, st.a.data(), s.vioCells);
                }
            } else {
                for (int d = nDiffs - 1; d >= 0; d--) { int f = s.diffFlat[d]; st.deltaApply(f / T, f % T, s.diffOld[d]); }
            }
        }
    }

    // ---- 番兵1層目: チャンク末尾の自己整合検査（差分スコア==フル再計算）----
    long long full = fullEvalCombined(p, st.a.data());
    long long status = 0;
    if (full != curScore || curScore != st.score) status = 1;
    else if (bestImproved && fullEvalCombined(p, s.bestSol.data()) != s.bestScore) status = 2;

    out[0] = status;
    out[1] = curScore;
    out[2] = s.bestScore;
    out[3] = bestImproved ? 1 : 0;
    out[4] = iters;
}

// ---- JNI 平坦データの読み取りヘルパ ----
#ifndef MAGI_HOST_TEST
std::vector<int> readIntArray(JNIEnv* env, jintArray arr) {
    jsize n = env->GetArrayLength(arr);
    std::vector<int> v((size_t)n);
    if (n > 0) env->GetIntArrayRegion(arr, 0, n, reinterpret_cast<jint*>(v.data()));
    return v;
}
#endif

}  // namespace

#ifndef MAGI_HOST_TEST
extern "C" JNIEXPORT jint JNICALL
Java_com_magi_app_v6_NativeBridge_nativeAbiVersion(JNIEnv*, jclass) {
    return 7;
}

// [Stage8] ALNS チャンク状態の生成。problem ハンドル＋初期盤面 cur から AlnsState を作る。
// accept: 0=SA 1=GreatDeluge 2=LamAdaptive / opSel: 0=roulette 1=thompson。0=失敗。
extern "C" JNIEXPORT jlong JNICALL
Java_com_magi_app_v6_NativeBridge_nativeAlnsCreate(
    JNIEnv* env, jclass, jlong problemHandle, jintArray curArr,
    jlong seed, jint accept, jint opSel, jdouble explore) {
    auto* p = reinterpret_cast<MagiProblem*>(problemHandle);
    if (p == nullptr) return 0;
    jsize n = env->GetArrayLength(curArr);
    if (n != p->S * p->T) return 0;
    std::vector<int> cur((size_t)n);
    env->GetIntArrayRegion(curArr, 0, n, reinterpret_cast<jint*>(cur.data()));
    auto* s = new AlnsState(*p, cur.data(), (uint64_t)seed, (int)accept, (int)opSel, (double)explore);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magi_app_v6_NativeBridge_nativeAlnsDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<AlnsState*>(handle);
}

// [Stage8] 1チャンク実行。戻り値 long[6]=[status, curScore, bestScore, bestImproved, iters, kicks]。
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_NativeBridge_nativeAlnsChunk(
    JNIEnv* env, jclass, jlong handle, jint iters, jdouble frac) {
    jlongArray outArr = env->NewLongArray(6);
    long long out[6] = {3, -1, -1, 0, 0, 0};
    auto* s = reinterpret_cast<AlnsState*>(handle);
    if (s != nullptr && iters > 0) runAlnsChunk(*s, (int)iters, (double)frac, out);
    jlong jv[6];
    for (int x = 0; x < 6; x++) jv[x] = (jlong)out[x];
    env->SetLongArrayRegion(outArr, 0, 6, jv);
    return outArr;
}

// [Stage8] best/cur 盤面の読み出し（which: 0=best, 1=cur）。S*T の配列へ書き込む。
extern "C" JNIEXPORT void JNICALL
Java_com_magi_app_v6_NativeBridge_nativeAlnsRead(
    JNIEnv* env, jclass, jlong handle, jint which, jintArray outArr) {
    auto* s = reinterpret_cast<AlnsState*>(handle);
    if (s == nullptr) return;
    jsize n = env->GetArrayLength(outArr);
    if (n != s->p.S * s->p.T) return;
    const int* src = which == 0 ? s->bestSol.data() : s->st.a.data();
    env->SetIntArrayRegion(outArr, 0, n, reinterpret_cast<const jint*>(src));
}

// [Stage10] Polish チャンク状態の生成（problemHandle＋初期盤面）。0=失敗。
extern "C" JNIEXPORT jlong JNICALL
Java_com_magi_app_v6_NativeBridge_nativePolishCreate(
    JNIEnv* env, jclass, jlong problemHandle, jintArray curArr, jlong seed) {
    auto* p = reinterpret_cast<MagiProblem*>(problemHandle);
    if (p == nullptr) return 0;
    jsize n = env->GetArrayLength(curArr);
    if (n != p->S * p->T) return 0;
    std::vector<int> cur((size_t)n);
    env->GetIntArrayRegion(curArr, 0, n, reinterpret_cast<jint*>(cur.data()));
    auto* s = new PolishState(*p, cur.data(), (uint64_t)seed);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magi_app_v6_NativeBridge_nativePolishDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<PolishState*>(handle);
}

// [Stage10] 1チャンク実行。戻り値 long[5]=[status, curScore, bestScore, bestImproved, iters]。status!=0 で退化。
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_NativeBridge_nativePolishChunk(JNIEnv* env, jclass, jlong handle, jint iters) {
    jlongArray outArr = env->NewLongArray(5);
    long long out[5] = {3, -1, -1, 0, 0};
    auto* s = reinterpret_cast<PolishState*>(handle);
    if (s != nullptr && iters > 0) runPolishChunk(*s, (int)iters, out);
    jlong jv[5];
    for (int x = 0; x < 5; x++) jv[x] = (jlong)out[x];
    env->SetLongArrayRegion(outArr, 0, 5, jv);
    return outArr;
}

// [Stage10] best/cur 盤面の読み出し（which: 0=best, 1=cur）。S*T の配列へ書き込む。
extern "C" JNIEXPORT void JNICALL
Java_com_magi_app_v6_NativeBridge_nativePolishRead(
    JNIEnv* env, jclass, jlong handle, jint which, jintArray outArr) {
    auto* s = reinterpret_cast<PolishState*>(handle);
    if (s == nullptr) return;
    jsize n = env->GetArrayLength(outArr);
    if (n != s->p.S * s->p.T) return;
    const int* src = which == 0 ? s->bestSol.data() : s->st.a.data();
    env->SetIntArrayRegion(outArr, 0, n, reinterpret_cast<const jint*>(src));
}

// [Stage11] LAHC チャンク状態の生成（problemHandle＋PhaseB 入口の best 盤面＋lahcLen）。0=失敗。
extern "C" JNIEXPORT jlong JNICALL
Java_com_magi_app_v6_NativeBridge_nativeLahcCreate(
    JNIEnv* env, jclass, jlong problemHandle, jintArray boardArr, jlong seed, jint lahcLen) {
    auto* p = reinterpret_cast<MagiProblem*>(problemHandle);
    if (p == nullptr || lahcLen < 1) return 0;
    jsize n = env->GetArrayLength(boardArr);
    if (n != p->S * p->T) return 0;
    std::vector<int> board((size_t)n);
    env->GetIntArrayRegion(boardArr, 0, n, reinterpret_cast<jint*>(board.data()));
    auto* s = new LahcState(*p, board.data(), (uint64_t)seed, (int)lahcLen);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magi_app_v6_NativeBridge_nativeLahcDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<LahcState*>(handle);
}

// [Stage11] 1チャンク実行。戻り値 long[5]=[status, curScore, bestScore, bestImproved, iters]。status!=0 で退化。
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_NativeBridge_nativeLahcChunk(JNIEnv* env, jclass, jlong handle, jint iters) {
    jlongArray outArr = env->NewLongArray(5);
    long long out[5] = {3, -1, -1, 0, 0};
    auto* s = reinterpret_cast<LahcState*>(handle);
    if (s != nullptr && iters > 0) runLahcChunk(*s, (int)iters, out);
    jlong jv[5];
    for (int x = 0; x < 5; x++) jv[x] = (jlong)out[x];
    env->SetLongArrayRegion(outArr, 0, 5, jv);
    return outArr;
}

// [Stage11] best/cur 盤面の読み出し（which: 0=best, 1=cur）。
extern "C" JNIEXPORT void JNICALL
Java_com_magi_app_v6_NativeBridge_nativeLahcRead(
    JNIEnv* env, jclass, jlong handle, jint which, jintArray outArr) {
    auto* s = reinterpret_cast<LahcState*>(handle);
    if (s == nullptr) return;
    jsize n = env->GetArrayLength(outArr);
    if (n != s->p.S * s->p.T) return;
    const int* src = which == 0 ? s->bestSol.data() : s->st.a.data();
    env->SetIntArrayRegion(outArr, 0, n, reinterpret_cast<const jint*>(src));
}

// [Stage3] SA チャンク（SaOptimizer PhaseA の冷却ラダー1本）。cur/best は S*T の平坦配列で、
// status==0 のときのみ書き戻される（in/out）。戻り値 long[6] = [status, curScore, bestScore,
// iters, improvedInChunk, tailIters]。パラメータ異常は status=3。
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_NativeBridge_nativeSaChunk(
    JNIEnv* env, jclass, jlong handle, jintArray curArr, jintArray bestArr,
    jlong bestScoreIn, jlong seed, jdouble t0, jdouble tf, jdouble alpha, jint chain) {
    jlongArray outArr = env->NewLongArray(6);
    long long out[6] = {3, -1, -1, 0, 0, 0};
    auto* p = reinterpret_cast<MagiProblem*>(handle);
    if (p != nullptr && t0 > 0 && tf > 0 && tf <= t0 && alpha > 0 && alpha < 1 && chain >= 1) {
        jsize n1 = env->GetArrayLength(curArr);
        jsize n2 = env->GetArrayLength(bestArr);
        if (n1 == p->S * p->T && n2 == n1) {
            std::vector<int> cur((size_t)n1), best((size_t)n1);
            env->GetIntArrayRegion(curArr, 0, n1, reinterpret_cast<jint*>(cur.data()));
            env->GetIntArrayRegion(bestArr, 0, n1, reinterpret_cast<jint*>(best.data()));
            runSaChunk(*p, cur.data(), best.data(), (long long)bestScoreIn,
                       (uint64_t)seed, (double)t0, (double)tf, (double)alpha, (int)chain, out);
            if (out[0] == 0) {
                env->SetIntArrayRegion(curArr, 0, n1, reinterpret_cast<jint*>(cur.data()));
                env->SetIntArrayRegion(bestArr, 0, n1, reinterpret_cast<jint*>(best.data()));
            }
        }
    }
    jlong jv[6];
    for (int x = 0; x < 6; x++) jv[x] = (jlong)out[x];
    env->SetLongArrayRegion(outArr, 0, 6, jv);
    return outArr;
}

// Problem の平坦化データからネイティブ側の MagiProblem を構築してハンドル(jlong)を返す。
// 配列レイアウトは NativeEval.kt の flatten と厳密に一致させること。
extern "C" JNIEXPORT jlong JNICALL
Java_com_magi_app_v6_NativeBridge_nativeCreateProblem(
    JNIEnv* env, jclass,
    jintArray metaArr, jintArray staffArr, jintArray canDoArr, jintArray wishArr,
    jintArray needsArr, jintArray rangesArr, jintArray consArr, jintArray c3Arr,
    jintArray bucketArr) {
    auto meta = readIntArray(env, metaArr);
    if (meta.size() < 7) return 0;
    auto* p = new MagiProblem();
    p->S = meta[0]; p->T = meta[1]; p->K = meta[2]; p->G = meta[3];
    p->restIdx = meta[4]; p->dow0 = meta[5]; p->use2 = meta[6] != 0;
    const int S = p->S, T = p->T, K = p->K, G = p->G;
    if (S <= 0 || T <= 0 || K <= 0 || G < 0) { delete p; return 0; }

    auto staff = readIntArray(env, staffArr);          // sgrp(S) + ssk(S)
    auto canDo = readIntArray(env, canDoArr);          // S*K (0/1)
    auto wish = readIntArray(env, wishArr);            // S*T
    auto needs = readIntArray(env, needsArr);          // need1(K*T) + need2(K*T)
    auto ranges = readIntArray(env, rangesArr);        // lo(S*K) + hi(S*K) + apt(S*K)
    auto cons = readIntArray(env, consArr);
    auto c3 = readIntArray(env, c3Arr);
    auto bucket = readIntArray(env, bucketArr);        // per g: len, shifts...
    if ((int)staff.size() != 2 * S || (int)canDo.size() != S * K || (int)wish.size() != S * T ||
        (int)needs.size() != 2 * K * T || (int)ranges.size() != 3 * S * K) { delete p; return 0; }

    p->sgrp.assign(staff.begin(), staff.begin() + S);
    // [監査#7 安全retreat] 探索オペレータ約13箇所が p.bucket[p.sgrp[i]] / grpCnt[sgrp[i]*K+k] を
    // sgrp範囲未検証で使う。Kotlin側は不正indexで例外→runCatchingにより安全に Kotlin パスへ退化するが、
    // C++ は UB（bucket=範囲外読み・grpCnt=範囲外書込=ヒープ破壊）でSIGSEGVがrunCatchingに捕まらず
    // プロセスクラッシュし得る。正規のエディタ/取込では groupIdx は常に [0,G) のはずだが、construction
    // 時点で一括検証し、外れていればハンドル生成自体を拒否（0=native不可）してKotlinへ安全退化させる。
    for (int i = 0; i < S; i++) {
        if (p->sgrp[i] < 0 || p->sgrp[i] >= G) { delete p; return 0; }
    }
    p->ssk.assign(staff.begin() + S, staff.end());
    // [3.311.0] ssk も同じ理由で検証する。sskMask は ssk の最大値からサイズを決めるため
    // （buildGroupMasks の maxGs）、外部JSON由来の巨大な skillIdx がそのまま届くと巨大確保に
    // なり得る。未所属の -1 は正規の値（3.70.0「(なし)」）なので許可し、正の値だけ上限を課す。
    // 上限は「スキル群の実数」ではなく制約が参照しうる範囲＝職員数と同じ保守的な上限で十分
    // （実運用の群数は職員数を超えない）。外れていればハンドル生成を拒否＝Kotlinへ安全退化。
    for (int i = 0; i < S; i++) {
        if (p->ssk[i] < -1 || p->ssk[i] > S) { delete p; return 0; }
    }
    p->canDo.resize((size_t)S * K);
    for (int x = 0; x < S * K; x++) p->canDo[x] = canDo[x] != 0 ? 1 : 0;
    p->wish = std::move(wish);
    p->need1.assign(needs.begin(), needs.begin() + (size_t)K * T);
    p->need2.assign(needs.begin() + (size_t)K * T, needs.end());
    p->rangeLo.assign(ranges.begin(), ranges.begin() + (size_t)S * K);
    p->rangeHi.assign(ranges.begin() + (size_t)S * K, ranges.begin() + 2 * (size_t)S * K);
    p->apt.assign(ranges.begin() + 2 * (size_t)S * K, ranges.end());

    // cons レイアウト: [n1,(d1,si,d2)*] [n2,(si,c)*] [n41,(g,s,l,u)*] [n42,(g1,s1,g2,s2)*]
    //                  [n41s,(g,s,l,u)*] [n42s,(g1,s1,g2,s2)*]
    // [3.284.0/外部レビュー=JNI hardening] 可変長領域(cons/c3/bucket)を checked cursor で検証する。
    //   旧: count/len 未検証で、負の len が (size_t) 変換で巨大 reserve → C++例外が JNI 境界を越えて
    //   プロセスクラッシュ、巨大 count が 0 埋めの大量 push で異常確保になり得た（正規の Kotlin
    //   serializer(NativeEval.flatten) 経路では発生しないが、破損入力・将来の呼出し追加への防御）。
    //   違反時はハンドル生成を拒否（0=native不可→Kotlinへ安全退化＝sgrp検証(監査#7)と同じ確立済みの契約）。
    size_t idx = 0;
    bool parseOk = true;
    auto next = [&]() -> int {
        if (idx < cons.size()) return cons[idx++];
        parseOk = false; return 0;
    };
    // count 読取: 非負かつ「残り要素数 >= count×行幅」を要求（積の overflow は除算形で回避）。
    auto takeCount = [&](int rowWidth) -> int {
        if (idx >= cons.size()) { parseOk = false; return 0; }
        int n = cons[idx++];
        if (n < 0 || (size_t)n > (cons.size() - idx) / (size_t)rowWidth) { parseOk = false; return 0; }
        return n;
    };
    int n1 = takeCount(3);
    for (int r = 0; r < n1; r++) { C1r c{next(), next(), next()}; p->cons1.push_back(c); }
    int n2 = takeCount(2);
    for (int r = 0; r < n2; r++) { C2r c{next(), next()}; p->cons2.push_back(c); }
    int n41 = takeCount(4);
    for (int r = 0; r < n41; r++) { C41r c{next(), next(), next(), next()}; p->cons41.push_back(c); }
    int n42 = takeCount(4);
    for (int r = 0; r < n42; r++) { C42r c{next(), next(), next(), next()}; p->cons42.push_back(c); }
    int n41s = takeCount(4);
    for (int r = 0; r < n41s; r++) { C41r c{next(), next(), next(), next()}; p->cons41s.push_back(c); }
    int n42s = takeCount(4);
    for (int r = 0; r < n42s; r++) { C42r c{next(), next(), next(), next()}; p->cons42s.push_back(c); }
    if (!parseOk) { delete p; return 0; }

    // c3 レイアウト: 4族順(c3, c3n, c3m, c3mn) に [count, (len, seq...)*count]
    size_t ci = 0;
    bool c3Ok = true;
    auto cnext = [&]() -> int {
        if (ci < c3.size()) return c3[ci++];
        c3Ok = false; return 0;
    };
    std::vector<C3r>* fams[4] = {&p->cons3, &p->cons3n, &p->cons3m, &p->cons3mn};
    for (auto* fam : fams) {
        if (ci >= c3.size()) { c3Ok = false; break; }
        int cnt = c3[ci++];
        // 各行は最低1要素(len)を持つため count は残り要素数を超えられない。
        if (cnt < 0 || (size_t)cnt > c3.size() - ci) { c3Ok = false; break; }
        for (int r = 0; r < cnt && c3Ok; r++) {
            if (ci >= c3.size()) { c3Ok = false; break; }
            int len = c3[ci++];
            if (len < 0 || (size_t)len > c3.size() - ci) { c3Ok = false; break; }
            C3r row;
            row.seq.reserve((size_t)len);
            for (int l = 0; l < len; l++) row.seq.push_back(cnext());
            row.singleRun = !row.seq.empty();
            for (size_t l = 1; l < row.seq.size(); l++) if (row.seq[l] != row.seq[0]) { row.singleRun = false; break; }
            fam->push_back(std::move(row));
        }
        if (!c3Ok) break;
    }
    if (!c3Ok) { delete p; return 0; }

    // bucket レイアウト: per g: [len, shifts...]。members は sgrp から導出。
    p->bucket.resize((size_t)G);
    size_t bi = 0;
    bool bOk = true;
    auto bnext = [&]() -> int {
        if (bi < bucket.size()) return bucket[bi++];
        bOk = false; return 0;
    };
    for (int g = 0; g < G; g++) {
        if (bi >= bucket.size()) { bOk = false; break; }
        int len = bucket[bi++];
        if (len < 0 || (size_t)len > bucket.size() - bi) { bOk = false; break; }
        p->bucket[g].reserve((size_t)len);
        for (int l = 0; l < len; l++) p->bucket[g].push_back(bnext());
    }
    if (!bOk) { delete p; return 0; }
    p->members.resize((size_t)G);
    for (int i = 0; i < S; i++) {
        int g = p->sgrp[i];
        if (g >= 0 && g < G) p->members[g].push_back(i);
    }
    // fair の O(1) 判定用と opBlockFill 用の導出テーブル。
    p->bucketHas.assign((size_t)G * K, 0);
    for (int g = 0; g < G; g++) for (int k : p->bucket[g]) if (k >= 0 && k < K) p->bucketHas[(size_t)g * K + k] = 1;
    p->staffForShift.resize((size_t)K);
    for (int i = 0; i < S; i++) {
        int g = p->sgrp[i];
        if (g < 0 || g >= G) continue;
        for (int k : p->bucket[g]) if (k >= 0 && k < K) p->staffForShift[k].push_back(i);
    }

    return reinterpret_cast<jlong>(p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magi_app_v6_NativeBridge_nativeDestroyProblem(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<MagiProblem*>(handle);
}

// フル評価。schedule は S*T の平坦配列。戻り値 long[2] = {hard1, soft}（Evaluator.fullEvalParts と同値）。
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_NativeBridge_nativeFullEval(JNIEnv* env, jclass, jlong handle, jintArray schedArr) {
    auto* p = reinterpret_cast<MagiProblem*>(handle);
    jlongArray out = env->NewLongArray(2);
    long long v[2] = {-1, -1};
    if (p != nullptr) {
        jsize n = env->GetArrayLength(schedArr);
        if (n == p->S * p->T) {
            std::vector<int> a((size_t)n);
            env->GetIntArrayRegion(schedArr, 0, n, reinterpret_cast<jint*>(a.data()));
            fullEvalParts(*p, a.data(), v);
        }
    }
    jlong jv[2] = {(jlong)v[0], (jlong)v[1]};
    env->SetLongArrayRegion(out, 0, 2, jv);
    return out;
}

// ---- [Rebuild] Move 契約: writes を SaChunk.deltaApply で差分評価して採否 ----
// 旧実装は fullEval を before/after 2 回走らせていた（O(制約×盤面)×2）。
// 高速化: SaChunk を1回構築し、各セルを deltaApply（影響スライスのみ再計算）。
// schedule は受理時のみ書き戻す。戻り値 long[4] = { status, score, hard, soft }
// status: 0=reject 1=accept_current 2=accept_best_hint
// [3.380/3.381] JNI ホットパス用スクラッチ + SaChunk プローブ常駐
// thread_local: handle 単位の単スレッド JNI（並列 Session は本番無効）を前提に容量再利用。
namespace {
struct MagiJniScratch {
    std::vector<int> a;
    std::vector<int> w;
    std::vector<uint8_t> seen;
    struct Ch { int s, d, oldv, sh; };
    std::vector<Ch> ch;
};
inline MagiJniScratch& jniScratch() {
    thread_local MagiJniScratch s;
    return s;
}
struct MagiProbeCache {
    const MagiProblem* problem = nullptr;
    std::unique_ptr<SaChunk> chunk;
};
inline SaChunk& jniProbeChunk(MagiProblem* p, const int* board) {
    thread_local MagiProbeCache cache;
    if (cache.chunk == nullptr || cache.problem != p) {
        cache.chunk = std::make_unique<SaChunk>(*p, board, 1ULL);
        cache.problem = p;
    } else {
        cache.chunk->resetBoard(board);
    }
    return *cache.chunk;
}
}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_NativeBridge_nativeTryWrites(
        JNIEnv* env, jclass, jlong handle, jintArray schedArr, jintArray writesArr,
        jint mode, jdouble temp, jlong /*lahcThr*/) {
    jlongArray out = env->NewLongArray(4);
    jlong z[4] = {0, -1, -1, -1};
    auto* p = reinterpret_cast<MagiProblem*>(handle);
    if (p == nullptr || schedArr == nullptr || writesArr == nullptr) {
        env->SetLongArrayRegion(out, 0, 4, z);
        return out;
    }
    jsize n = env->GetArrayLength(schedArr);
    jsize nw = env->GetArrayLength(writesArr);
    if (n != p->S * p->T || nw <= 0 || nw % 3 != 0) {
        env->SetLongArrayRegion(out, 0, 4, z);
        return out;
    }
    MagiJniScratch& sc = jniScratch();
    sc.a.resize((size_t)n);
    sc.w.resize((size_t)nw);
    sc.seen.assign((size_t)n, 0);
    sc.ch.clear();
    if (sc.ch.capacity() < (size_t)(nw / 3)) sc.ch.reserve((size_t)(nw / 3));
    env->GetIntArrayRegion(schedArr, 0, n, reinterpret_cast<jint*>(sc.a.data()));
    env->GetIntArrayRegion(writesArr, 0, nw, reinterpret_cast<jint*>(sc.w.data()));

    for (jsize i = 0; i < nw; i += 3) {
        int s = sc.w[(size_t)i], d = sc.w[(size_t)i + 1], sh = sc.w[(size_t)i + 2];
        if (s < 0 || s >= p->S || d < 0 || d >= p->T || sh < 0 || sh >= p->K) {
            env->SetLongArrayRegion(out, 0, 4, z);
            return out;
        }
        size_t cell = (size_t)s * (size_t)p->T + (size_t)d;
        if (sc.seen[cell]) { env->SetLongArrayRegion(out, 0, 4, z); return out; }
        sc.seen[cell] = 1;
        int oldv = sc.a[cell];
        if (oldv == sh) continue;
        int wish = p->wish[(size_t)s * (size_t)p->T + (size_t)d];
        if (wish >= 0 && p->cd(s, wish) && oldv != sh) {
            env->SetLongArrayRegion(out, 0, 4, z);
            return out;
        }
        if (!p->cd(s, sh)) {
            env->SetLongArrayRegion(out, 0, 4, z);
            return out;
        }
        sc.ch.push_back({s, d, oldv, sh});
    }
    if (sc.ch.empty()) {
        env->SetLongArrayRegion(out, 0, 4, z);
        return out;
    }

    // 差分評価: 1 回の full 初期化 + |ch| 回の deltaApply（フル評価2回より高速）
    SaChunk& st = jniProbeChunk(p, sc.a.data());
    const long long before = st.score;
    for (auto& c : sc.ch) {
        st.deltaApply(c.s, c.d, c.sh);
        sc.a[(size_t)c.s * (size_t)p->T + (size_t)c.d] = c.sh;
    }
    const long long after = st.score;
    // 自己整合（稀なビット/デルタドリフト検出 → reject で Kotlin へ）
    const long long verify = fullEvalCombined(*p, sc.a.data());
    if (verify != after) {
        // ドリフト時はフル評価結果を採用しつつ厳密比較（加速は諦めるが正しさ優先）
        long long parts[2];
        fullEvalParts(*p, sc.a.data(), parts);
        auto reject = [&]() {
            env->SetLongArrayRegion(out, 0, 4, z);
            return out;
        };
        if (mode == 0 && verify >= before) return reject();
        if (mode == 1 && verify / 1000000000LL > before / 1000000000LL) return reject();
        if (mode == 2 && verify > before) return reject();
        env->SetIntArrayRegion(schedArr, 0, n, reinterpret_cast<jint*>(sc.a.data()));
        jlong ok[4] = { verify < before ? 2 : 1, (jlong)verify, (jlong)parts[0], (jlong)parts[1] };
        env->SetLongArrayRegion(out, 0, 4, ok);
        return out;
    }

    long long parts[2];
    parts[0] = after / st.M;
    parts[1] = after % st.M;

    auto reject = [&]() {
        env->SetLongArrayRegion(out, 0, 4, z);
        return out;
    };
    // mode: 0 STRICT / 1 ANNEAL / 2 LAHC
    // プローブ用途: 悪化案は reject。Metropolis 乱数は Kotlin Session 側のみ（ここは決定的）。
    // 旧: ANNEAL で exp(-Δ/T)<0.5 の決定的閾値 → 温度と無関係に約半分を落とし品質を歪める。
    if (mode == 0) {
        if (after >= before) return reject();
    } else if (mode == 1) {
        // hard 増加は不可。soft 悪化は Kotlin Metropolis に委ねるため accept_hint(1) を返す。
        if (after / 1000000000LL > before / 1000000000LL) return reject();
        // after<=before → improve/equal; after>before かつ hard 同値 → hint accept で Kotlin が温度判定
    } else {
        if (after > before) return reject();
    }
    env->SetIntArrayRegion(schedArr, 0, n, reinterpret_cast<jint*>(sc.a.data()));
    jlong ok[4] = { after < before ? 2 : 1, (jlong)after, (jlong)parts[0], (jlong)parts[1] };
    env->SetLongArrayRegion(out, 0, 4, ok);
    return out;
}

// ---- [高速化] 差分スコアのみ（採否なし）。Kotlin DeltaEvaluator 相当の C++ 移植入口 ----
// 戻り値 long[4] = { status, before, after, soft }  status 0=NG 1=OK
// hard = after / 1e9（呼び出し側で算出可）
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_NativeBridge_nativeDeltaEval(
        JNIEnv* env, jclass, jlong handle, jintArray schedArr, jintArray writesArr) {
    jlongArray out = env->NewLongArray(4);
    jlong z[4] = {0, -1, -1, -1};
    auto* p = reinterpret_cast<MagiProblem*>(handle);
    if (p == nullptr || schedArr == nullptr || writesArr == nullptr) {
        env->SetLongArrayRegion(out, 0, 4, z);
        return out;
    }
    jsize n = env->GetArrayLength(schedArr);
    jsize nw = env->GetArrayLength(writesArr);
    if (n != p->S * p->T || nw <= 0 || nw % 3 != 0) {
        env->SetLongArrayRegion(out, 0, 4, z);
        return out;
    }
    MagiJniScratch& sc = jniScratch();
    sc.a.resize((size_t)n);
    sc.w.resize((size_t)nw);
    env->GetIntArrayRegion(schedArr, 0, n, reinterpret_cast<jint*>(sc.a.data()));
    env->GetIntArrayRegion(writesArr, 0, nw, reinterpret_cast<jint*>(sc.w.data()));

    SaChunk& st = jniProbeChunk(p, sc.a.data());
    const long long before = st.score;
    for (jsize i = 0; i < nw; i += 3) {
        int s = sc.w[(size_t)i], d = sc.w[(size_t)i + 1], sh = sc.w[(size_t)i + 2];
        if (s < 0 || s >= p->S || d < 0 || d >= p->T || sh < 0 || sh >= p->K) {
            env->SetLongArrayRegion(out, 0, 4, z);
            return out;
        }
        st.deltaApply(s, d, sh);
    }
    const long long after = st.score;
    jlong ok[4] = {1, (jlong)before, (jlong)after, (jlong)(after % 1000000000LL)};
    env->SetLongArrayRegion(out, 0, 4, ok);
    return out;
}

#endif  // MAGI_HOST_TEST
