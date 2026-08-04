#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MAGI_ABI_VERSION 3

int32_t magi_abi_version(void);

/* flat layout:
 * int32 S,T,K
 * int32 schedule[S*T]
 * uint8 wish_lock[S*T]
 * uint8 can_do[S*K]
 * int32 day_demand[T]
 */
int64_t magi_create_problem(const uint8_t* flat, int32_t n);
void    magi_destroy_problem(int64_t h);

/* mode: 0 STRICT 1 ANNEAL 2 LAHC
 * return: 0 reject 1 accept_current 2 accept_best_hint
 */
int32_t magi_try_writes(int64_t h, int64_t base_ver,
                        const int32_t* writes, int32_t n_writes,
                        int32_t mode, double temp, int64_t lahc_thr);

int64_t magi_packed_score(int64_t h);
void    magi_read_schedule(int64_t h, int32_t* out_st); /* length S*T */
int64_t magi_version(int64_t h);

#ifdef __cplusplus
}
#endif
