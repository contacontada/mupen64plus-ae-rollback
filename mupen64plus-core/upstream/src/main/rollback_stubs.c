/* rollback_stubs.c - Rollback netcode glue for Android.
 *
 * savestates_save_rollback_buffer / savestates_save_full_buffer /
 * savestates_load_rollback_buffer / savestates_free_rollback_buffer /
 * savestates_get_rollback_load_counter are implemented in savestates.c
 * (they need access to that file's static savestate parser/writer).
 *
 * This file wires the remaining M64CMD_ROLLBACK_* entry points to the
 * real subsystems that implement them.
 */
#include "rollback_stubs.h"
#include "api/m64p_types.h"
#include "api/m64p_plugin.h"
#include "main.h"
#include "savestates.h"
#include "device/pif/pif.h"
#include "plugin/plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/* ---- Input callback: delegates to the PIF-level injection hooks in
 * device/pif/pif.c, so controller reads are actually intercepted during
 * emulation instead of being stored somewhere nothing reads from. ---- */

static int g_deterministic = 0;

void savestates_set_rollback_input_callback(m64p_rollback_input_callback callback)
{
    pif_set_rollback_input_callback(callback);
}

void savestates_set_rollback_input_players(int players)
{
    pif_set_rollback_input_players(players);
}

void savestates_set_rollback_deterministic(int enabled)
{
    /* See main_rollback_run_frame() in main.c: when enabled, frame
     * stepping is driven purely by CPU execution (no audio/video pacing
     * wait), which is required for fast rollback re-simulation. */
    g_deterministic = enabled;
}

int rollback_is_deterministic_mode(void)
{
    return g_deterministic;
}

int savestates_rollback_sample_input(void* values, int size, int players)
{
    /* Reads the current physical controller state directly from the input
     * plugin (control index 0 = the local player's pad), independent of
     * PIF/frame timing. This is what rollback_jni.cpp's submitLocalInput()
     * uses to grab "what is the local player pressing right now" before
     * handing it to GekkoNet; it is a different code path from the
     * PIF-level injection in device/pif/pif.c, which instead *writes*
     * GekkoNet's already-decided input into the emulated controller. */
    BUTTONS keys;
    uint32_t* out = (uint32_t*)values;
    int i;

    if (values == NULL || size != (int)sizeof(uint32_t) || players <= 0) {
        return 0;
    }

    for (i = 0; i < players && i < NUM_CONTROLLER; ++i) {
        keys.Value = 0;
        if (input.getKeys) {
            input.getKeys(i, &keys);
        }
        out[i] = keys.Value;
    }

    return 1;
}

void savestates_set_rollback_verbose_stats(int enabled)
{
    (void)enabled;
}
