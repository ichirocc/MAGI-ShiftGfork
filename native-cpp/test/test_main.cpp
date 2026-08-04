#include "magi_native_api.h"
#include <cstdio>
#include <cstdint>
#include <vector>
#include <cstring>

static std::vector<uint8_t> make_flat(int S, int T, int K) {
  std::vector<int32_t> schedule(S * T, 0);
  // put 2 on-duty on day 0
  schedule[0 * T + 0] = 1;
  schedule[1 * T + 0] = 1;
  std::vector<uint8_t> wish(S * T, 0);
  wish[0 * T + 0] = 1; // lock staff0 day0
  std::vector<uint8_t> can(S * K, 1);
  std::vector<int32_t> demand(T, 0);
  demand[0] = 2;
  demand[1] = 2;

  size_t n = 12 + schedule.size()*4 + wish.size() + can.size() + demand.size()*4;
  std::vector<uint8_t> flat(n);
  int32_t hdr[3] = {S, T, K};
  size_t off = 0;
  std::memcpy(flat.data()+off, hdr, 12); off += 12;
  std::memcpy(flat.data()+off, schedule.data(), schedule.size()*4); off += schedule.size()*4;
  std::memcpy(flat.data()+off, wish.data(), wish.size()); off += wish.size();
  std::memcpy(flat.data()+off, can.data(), can.size()); off += can.size();
  std::memcpy(flat.data()+off, demand.data(), demand.size()*4);
  return flat;
}

int main() {
  printf("abi=%d\n", magi_abi_version());
  auto flat = make_flat(4, 7, 3);
  int64_t h = magi_create_problem(flat.data(), (int32_t)flat.size());
  if (!h) { printf("create fail\n"); return 1; }
  int64_t sc0 = magi_packed_score(h);
  printf("score0=%lld ver=%lld\n", (long long)sc0, (long long)magi_version(h));

  // try break wish lock -> reject
  int32_t bad[] = {0, 0, 2};
  int r = magi_try_writes(h, 0, bad, 3, 0, 0.0, 0);
  printf("lock_write=%d (expect 0)\n", r);

  // fill day1 deficit STRICT
  int32_t w[] = {2, 1, 1};
  r = magi_try_writes(h, magi_version(h), w, 3, 0, 0.0, 0);
  printf("fill_day1=%d score=%lld ver=%lld\n", r,
         (long long)magi_packed_score(h), (long long)magi_version(h));

  magi_destroy_problem(h);
  printf("OK\n");
  return 0;
}
