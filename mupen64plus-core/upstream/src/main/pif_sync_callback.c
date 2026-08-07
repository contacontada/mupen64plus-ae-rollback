/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 *   Mupen64plus - pif_sync_callback.c                                     *
 *   Rollback netcode support (ported from RMG-K, Jay-Day/RMG-K)           *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
#include "pif_sync_callback.h"

static pif_sync_callback_t g_pif_sync_callback = NULL;

EXPORT void CALL set_pif_sync_callback(pif_sync_callback_t callback)
{
    g_pif_sync_callback = callback;
}

void call_pif_sync_callback(struct pif* pif)
{
    if (g_pif_sync_callback != NULL) {
        g_pif_sync_callback(pif);
    }
}
