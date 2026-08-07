/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 *   Mupen64plus - pif_sync_callback.h                                     *
 *   Rollback netcode support (ported from RMG-K, Jay-Day/RMG-K)           *
 *                                                                         *
 *   Lets a frontend (e.g. the rollback JNI bridge) register a callback   *
 *   that fires every time the PIF has finished a controller-read cycle.  *
 *   This is the hook GekkoNet/rollback code uses to know exactly when    *
 *   controller state for the current frame has been finalized, so it     *
 *   can log/verify the polled input against what it predicted.           *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
#ifndef M64P_MAIN_PIF_SYNC_CALLBACK_H
#define M64P_MAIN_PIF_SYNC_CALLBACK_H

#include "api/m64p_types.h"

struct pif;

typedef void (*pif_sync_callback_t)(struct pif* pif);

/* Exported through api/api_export.ver so the rollback JNI bridge can call it
 * directly via dlsym/CoreAttachPlugin-style linkage. */
EXPORT void CALL set_pif_sync_callback(pif_sync_callback_t callback);

/* Internal: invoked once per update_pif_ram() call, after rollback input
 * injection has finished writing controller state into the PIF channels. */
void call_pif_sync_callback(struct pif* pif);

#endif /* M64P_MAIN_PIF_SYNC_CALLBACK_H */
