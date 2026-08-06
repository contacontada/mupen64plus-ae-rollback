/* rollback_stubs.c - Minimal rollback API stubs for Android compilation
 * These stubs allow the rollback JNI to compile and link.
 * For full rollback functionality, replace with RMG-K core's implementations.
 */
#include "rollback_stubs.h"
#include "api/m64p_types.h"
#include "main.h"
#include "savestates.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/* Stub: save state to rollback buffer */
int savestates_save_rollback_buffer(unsigned char** buffer, int* len,
                                     unsigned int* checksum, int frame)
{
    /* TODO: Implement actual state save using mupen64plus savestate system */
    (void)buffer; (void)len; (void)checksum; (void)frame;
    fprintf(stderr, "rollback_stubs: savestates_save_rollback_buffer not implemented\n");
    return 0;
}

int savestates_save_full_buffer(unsigned char** buffer, int* len,
                                 unsigned int* checksum, int frame)
{
    (void)buffer; (void)len; (void)checksum; (void)frame;
    fprintf(stderr, "rollback_stubs: savestates_save_full_buffer not implemented\n");
    return 0;
}

/* Stub: load state from rollback buffer */
int savestates_load_rollback_buffer(unsigned char* buffer, int len, int frame)
{
    (void)buffer; (void)len; (void)frame;
    fprintf(stderr, "rollback_stubs: savestates_load_rollback_buffer not implemented\n");
    return 0;
}

/* Stub: free rollback buffer */
void savestates_free_rollback_buffer(unsigned char* buffer)
{
    if (buffer) free(buffer);
}

/* Stub: input callback */
static m64p_rollback_input_callback g_input_callback = NULL;
static int g_input_players = 0;
static int g_deterministic = 0;

void savestates_set_rollback_input_callback(m64p_rollback_input_callback callback)
{
    g_input_callback = callback;
}

void savestates_set_rollback_input_players(int players)
{
    g_input_players = players;
}

void savestates_set_rollback_deterministic(int enabled)
{
    g_deterministic = enabled;
}

int savestates_rollback_sample_input(void* values, int size, int players)
{
    if (g_input_callback) {
        return g_input_callback(values, size, players);
    }
    (void)values; (void)size; (void)players;
    return 0;
}

void savestates_set_rollback_verbose_stats(int enabled)
{
    (void)enabled;
}

/* Stub: execute callbacks */
static m64p_rollback_execute_callbacks* g_execute_callbacks = NULL;

void main_set_rollback_execute_callbacks(m64p_rollback_execute_callbacks* callbacks)
{
    g_execute_callbacks = callbacks;
}

/* Stub: run one rollback frame */
int main_rollback_run_frame(int output_flags)
{
    (void)output_flags;
    /* TODO: Implement actual frame run with rollback support */
    return 1;
}

void main_get_rollback_run_frame_stats(m64p_rollback_run_frame_stats* stats)
{
    if (stats) memset(stats, 0, sizeof(*stats));
}

void main_set_rollback_timesync_scale(double scale)
{
    (void)scale;
}

int savestates_get_rollback_load_counter(void)
{
    return 0;
}
void set_pif_sync_callback(void* cb) { (void)cb; }
