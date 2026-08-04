#include "magi_native_api.h"
#include <cmath>
#include <cstring>
#include <unordered_set>
#include <vector>
#include <algorithm>

namespace {

struct State {
  int S = 0, T = 0, K = 0;
  std::vector<int32_t> schedule;   // S*T
  std::vector<uint8_t> wish_lock;  // S*T
  std::vector<uint8_t> can_do;     // S*K
  std::vector<int32_t> day_demand; // T
  std::vector<int32_t> best;
  int64_t version = 0;
  int64_t best_pack = 0;
};

constexpr int64_t HARD_UNIT = 1000000000LL;

static int32_t idx(const State* p, int s, int d) { return s * p->T + d; }

/* 簡易 pack: covU hard 近似 + soft 0
 * on-duty = shift != 0
 */
static int64_t pack_score(const State* p, const std::vector<int32_t>& sch) {
  int hard = 0;
  for (int d = 0; d < p->T; ++d) {
    int need = p->day_demand[d];
    if (need <= 0) continue;
    int have = 0;
    for (int s = 0; s < p->S; ++s) {
      if (sch[idx(p, s, d)] != 0) ++have;
    }
    if (have < need) hard += (need - have);
  }
  // wish break soft-ish counted as hard for lock (should never happen if pin enforced)
  return int64_t(hard) * HARD_UNIT;
}

static bool parse_flat(State* p, const uint8_t* flat, int32_t n) {
  if (n < 12) return false;
  const int32_t* i32 = reinterpret_cast<const int32_t*>(flat);
  p->S = i32[0]; p->T = i32[1]; p->K = i32[2];
  if (p->S <= 0 || p->T <= 0 || p->K <= 0) return false;
  size_t need = 12u + size_t(p->S * p->T) * 4u
              + size_t(p->S * p->T)
              + size_t(p->S * p->K)
              + size_t(p->T) * 4u;
  if (size_t(n) < need) return false;
  size_t off = 12;
  p->schedule.resize(p->S * p->T);
  std::memcpy(p->schedule.data(), flat + off, p->schedule.size() * 4);
  off += p->schedule.size() * 4;
  p->wish_lock.resize(p->S * p->T);
  std::memcpy(p->wish_lock.data(), flat + off, p->wish_lock.size());
  off += p->wish_lock.size();
  p->can_do.resize(p->S * p->K);
  std::memcpy(p->can_do.data(), flat + off, p->can_do.size());
  off += p->can_do.size();
  p->day_demand.resize(p->T);
  std::memcpy(p->day_demand.data(), flat + off, p->T * 4);
  p->best = p->schedule;
  p->best_pack = pack_score(p, p->schedule);
  p->version = 0;
  return true;
}

} // namespace

extern "C" int32_t magi_abi_version(void) { return MAGI_ABI_VERSION; }

extern "C" int64_t magi_create_problem(const uint8_t* flat, int32_t n) {
  auto* p = new (std::nothrow) State();
  if (!p) return 0;
  if (!parse_flat(p, flat, n)) { delete p; return 0; }
  return reinterpret_cast<int64_t>(p);
}

extern "C" void magi_destroy_problem(int64_t h) {
  delete reinterpret_cast<State*>(h);
}

extern "C" int64_t magi_packed_score(int64_t h) {
  auto* p = reinterpret_cast<State*>(h);
  if (!p) return 0;
  return pack_score(p, p->schedule);
}

extern "C" int64_t magi_version(int64_t h) {
  auto* p = reinterpret_cast<State*>(h);
  return p ? p->version : -1;
}

extern "C" void magi_read_schedule(int64_t h, int32_t* out_st) {
  auto* p = reinterpret_cast<State*>(h);
  if (!p || !out_st) return;
  std::memcpy(out_st, p->schedule.data(), p->schedule.size() * sizeof(int32_t));
}

extern "C" int32_t magi_try_writes(
    int64_t h, int64_t base_ver,
    const int32_t* writes, int32_t n_writes,
    int32_t mode, double temp, int64_t /*lahc_thr*/) {
  auto* p = reinterpret_cast<State*>(h);
  if (!p || !writes || n_writes <= 0 || n_writes % 3 != 0) return 0;
  if (base_ver != p->version) return 0;

  std::unordered_set<int64_t> seen;
  struct Ch { int s, d, old, sh; };
  std::vector<Ch> ch;
  ch.reserve(n_writes / 3);

  for (int i = 0; i < n_writes; i += 3) {
    int s = writes[i], d = writes[i + 1], sh = writes[i + 2];
    if (s < 0 || s >= p->S || d < 0 || d >= p->T || sh < 0 || sh >= p->K) return 0;
    int64_t key = (int64_t(s) << 32) | uint32_t(d);
    if (!seen.insert(key).second) return 0;
    int old = p->schedule[idx(p, s, d)];
    if (old == sh) continue;
    if (p->wish_lock[idx(p, s, d)]) return 0;
    if (!p->can_do[s * p->K + sh]) return 0;
    ch.push_back({s, d, old, sh});
  }
  if (ch.empty()) return 0;

  int64_t before = pack_score(p, p->schedule);
  for (auto& c : ch) p->schedule[idx(p, c.s, c.d)] = c.sh;
  int64_t after = pack_score(p, p->schedule);

  auto revert = [&] {
    for (auto& c : ch) p->schedule[idx(p, c.s, c.d)] = c.old;
  };

  auto commit = [&]() -> int32_t {
    p->version++;
    if (after < p->best_pack) {
      p->best_pack = after;
      p->best = p->schedule;
      return 2;
    }
    return 1;
  };

  if (mode == 0) { // STRICT
    if (after >= before) { revert(); return 0; }
    return commit();
  }
  if (mode == 1) { // ANNEAL: accept improve always; worsen with temp (deterministic threshold)
    if (after > before) {
      double delta = double(after - before);
      double thr = std::exp(-delta / std::max(temp, 1e-12));
      // 決定的近似: temp が大きいほど悪化を許しやすい（rng 無し版）
      if (thr < 0.5) { revert(); return 0; }
    }
    return commit();
  }
  // LAHC simplified: accept if not worse than before hard-pack
  if (after > before) { revert(); return 0; }
  return commit();
}
