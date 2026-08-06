/* rollback_stubs.h - Minimal rollback API for Android compilation
 * These are stub declarations. Full implementation is in the RMG-K core.
 */
#ifndef ROLLBACK_STUBS_H
#define ROLLBACK_STUBS_H

#include "api/m64p_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Save state to rollback buffer */
int savestates_save_rollback_buffer(unsigned char** buffer, int* len,
                                     unsigned int* checksum, int frame);

/* Save full state to buffer (for spectate keyframes) */
int savestates_save_full_buffer(unsigned char** buffer, int* len,
                                 unsigned int* checksum, int frame);

/* Load state from rollback buffer */
int savestates_load_rollback_buffer(unsigned char* buffer, int len, int frame);

/* Free rollback buffer */
void savestates_free_rollback_buffer(unsigned char* buffer);

/* Set rollback input callback */
void savestates_set_rollback_input_callback(m64p_rollback_input_callback callback);

/* Set number of rollback input players */
void savestates_set_rollback_input_players(int players);

/* Set deterministic mode */
void savestates_set_rollback_deterministic(int enabled);

/* Sample input for rollback */
int savestates_rollback_sample_input(void* values, int size, int players);

/* Set verbose stats */
void savestates_set_rollback_verbose_stats(int enabled);

/* Set rollback execute callbacks */
void main_set_rollback_execute_callbacks(m64p_rollback_execute_callbacks* callbacks);

/* Run one rollback frame */
int main_rollback_run_frame(int output_flags);

/* Get rollback run frame stats */
void main_get_rollback_run_frame_stats(m64p_rollback_run_frame_stats* stats);

/* Set timesync scale */
void main_set_rollback_timesync_scale(double scale);

/* Get current frame count */
int savestates_get_rollback_load_counter(void);

#ifdef __cplusplus
}
#endif

#endif /* ROLLBACK_STUBS_H */
