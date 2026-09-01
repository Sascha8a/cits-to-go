#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
void cits_rs_run(void) __attribute__((noreturn));
void cits_rs_capture(const uint8_t *, size_t, uint64_t, int8_t, uint8_t, uint8_t);
bool cits_rs_input(const uint8_t *, size_t, bool);
void cits_rs_ble_gap(void);
void cits_rs_ble_reset(void);
void cits_rs_tx_done(int32_t);
