/*
 * Mupen64Plus AE - Rollback Netcode JNI Bridge
 * Based on RMG-K's GekkoNet integration (Jay-Day/RMG-K)
 * and the GekkoNet rollback library by Jamie Meyer.
 *
 * This bridge exposes GekkoNet + mupen64plus-core rollback API to Java.
 */
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <chrono>
#include <map>
#include <algorithm>
#include <cmath>
#include <thread>

#include <gekkonet.h>

// POSIX UDP adapter for Android (replaces GekkoNet's ASIO-based default adapter)
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>

static int g_UdpSocket = -1;

static void posix_udp_send(GekkoNetAddress* addr, const char* data, int length) {
    if (g_UdpSocket < 0 || !addr || !data || length <= 0) return;
    // addr->data is a string like "192.168.1.1:4444"
    std::string addrStr(static_cast<const char*>(addr->data), addr->size);
    size_t colonPos = addrStr.find(':');
    if (colonPos == std::string::npos) return;
    std::string ip = addrStr.substr(0, colonPos);
    int port = std::atoi(addrStr.substr(colonPos + 1).c_str());
    if (port <= 0) return;

    struct sockaddr_in dest;
    std::memset(&dest, 0, sizeof(dest));
    dest.sin_family = AF_INET;
    dest.sin_port = htons(static_cast<uint16_t>(port));
    inet_pton(AF_INET, ip.c_str(), &dest.sin_addr);

    sendto(g_UdpSocket, data, static_cast<size_t>(length), 0,
           reinterpret_cast<struct sockaddr*>(&dest), sizeof(dest));
}

static std::vector<GekkoNetResult*> g_PosixResults;

static GekkoNetResult** posix_udp_receive(int* length) {
    g_PosixResults.clear();
    if (length) *length = 0;
    if (g_UdpSocket < 0 || !length) return g_PosixResults.data();

    for (;;) {
        char buf[2048];
        struct sockaddr_in src;
        socklen_t srcLen = sizeof(src);
        ssize_t recvd = recvfrom(g_UdpSocket, buf, sizeof(buf), 0,
                                  reinterpret_cast<struct sockaddr*>(&src), &srcLen);
        if (recvd <= 0) break;

        char addrBuf[64];
        inet_ntop(AF_INET, &src.sin_addr, addrBuf, sizeof(addrBuf));
        std::string addrStr = std::string(addrBuf) + ":" + std::to_string(ntohs(src.sin_port));

        GekkoNetResult* result = reinterpret_cast<GekkoNetResult*>(std::malloc(sizeof(GekkoNetResult)));
        if (!result) break;
        result->addr.data = std::malloc(addrStr.size());
        result->data = std::malloc(static_cast<size_t>(recvd));
        if (!result->addr.data || !result->data) {
            std::free(result->addr.data);
            std::free(result->data);
            std::free(result);
            break;
        }
        std::memcpy(result->addr.data, addrStr.data(), addrStr.size());
        result->addr.size = static_cast<unsigned int>(addrStr.size());
        std::memcpy(result->data, buf, static_cast<size_t>(recvd));
        result->data_len = static_cast<unsigned int>(recvd);
        g_PosixResults.push_back(result);
    }
    *length = static_cast<int>(g_PosixResults.size());
    return g_PosixResults.data();
}

static void posix_udp_free(void* data) {
    std::free(data);
}

static GekkoNetAdapter g_PosixUdpAdapter{
    posix_udp_send,
    posix_udp_receive,
    posix_udp_free
};

static bool posix_udp_init(unsigned short port) {
    if (g_UdpSocket >= 0) {
        close(g_UdpSocket);
        g_UdpSocket = -1;
    }
    g_UdpSocket = socket(AF_INET, SOCK_DGRAM, 0);
    if (g_UdpSocket < 0) {
        LOGE("Failed to create UDP socket: %s", strerror(errno));
        return false;
    }
    // Non-blocking
    int flags = fcntl(g_UdpSocket, F_GETFL, 0);
    fcntl(g_UdpSocket, F_SETFL, flags | O_NONBLOCK);
    // Bind
    struct sockaddr_in addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port);
    if (bind(g_UdpSocket, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        LOGE("Failed to bind UDP socket to port %d: %s", port, strerror(errno));
        close(g_UdpSocket);
        g_UdpSocket = -1;
        return false;
    }
    LOGI("UDP socket bound to port %d", port);
    return true;
}

static void posix_udp_destroy() {
    if (g_UdpSocket >= 0) {
        close(g_UdpSocket);
        g_UdpSocket = -1;
    }
    for (auto* r : g_PosixResults) {
        std::free(r->addr.data);
        std::free(r->data);
        std::free(r);
    }
    g_PosixResults.clear();
}

// mupen64plus-core API (linked as shared library)
#include "api/m64p_types.h"
#include "api/m64p_frontend.h"
#include "api/m64p_common.h"

#define LOG_TAG "RollbackJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------
static JavaVM* g_JavaVM = nullptr;
static jobject g_CallbackObj = nullptr;
static jmethodID g_OnSessionEventMethod = nullptr;
static jmethodID g_OnGameEventMethod = nullptr;
static jmethodID g_OnInputMethod = nullptr;
static jmethodID g_OnFrameMethod = nullptr;
static jmethodID g_OnStateSaveMethod = nullptr;
static jmethodID g_OnStateLoadMethod = nullptr;
static jmethodID g_OnLogMethod = nullptr;

static GekkoSession* g_GekkoSession = nullptr;
static int g_GekkoPlayers = 0;
static int g_GekkoActors = 0;
static int g_GekkoInputSize = 4; // sizeof(uint32_t)
static int g_GekkoLocalPlayer = 0;
static int g_GekkoLocalHandle = -1;
static int g_GekkoRemoteHandle = -1;
static std::vector<int> g_GekkoPlayerHandles;
static std::vector<int> g_GekkoLocalHandles;
static std::vector<unsigned char> g_GekkoLatchedInput;
static bool g_GekkoHasLatchedInput = false;
static std::atomic_bool g_GekkoExecuting{false};
static std::atomic_bool g_GekkoStopRequested{false};
static int g_GekkoLastDesyncFrame = -1;
static std::vector<unsigned char> g_StateBuffer;
static constexpr unsigned int kStateCapacity = 24u * 1024u * 1024u; // 24MB

// Frame pacing
static double g_SpeedScale = 1.0;
static double g_TimesyncTargetScale = 1.0;
static int g_TimesyncSampleCounter = 0;

// Asymmetric pacing constants (Slippi-style)
static constexpr float kAsymAheadDeadzone = 0.48f;
static constexpr float kAsymBehindDeadzone = 0.015f;
static constexpr double kAsymSpeedUpWindow = 3.0;
static constexpr double kAsymSlowDownWindow = 3.0;
static constexpr double kAsymMaxSpeedUp = 0.01;
static constexpr double kAsymMaxSlowDown = 0.005;
static constexpr double kAsymLerp = 0.15;
static constexpr int kAsymIntervalFrames = 30;

// Core API is linked directly - no function pointers needed

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------
static JNIEnv* getEnv() {
    JNIEnv* env = nullptr;
    if (g_JavaVM) {
        int status = g_JavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            g_JavaVM->AttachCurrentThread(&env, nullptr);
        }
    }
    return env;
}

static void logCallback(void* context, int level, const char* message) {
    if (message) {
        LOGI("[core] %s", message);
    }
}

// ---------------------------------------------------------------------------
// Core rollback API wrappers (call through m64p frontend)
// ---------------------------------------------------------------------------
static bool coreRollbackSaveState(unsigned char* buffer, int capacity, int frame,
                                   unsigned char** outBuffer, int* outLen, unsigned int* outChecksum) {
    m64p_rollback_state state = {};
    state.frame = frame;
    state.buffer = buffer;
    state.len = capacity;
    m64p_error ret = CoreDoCommand(M64CMD_ROLLBACK_SAVE_STATE, 0, &state);
    if (ret != M64ERR_SUCCESS) return false;
    if (outBuffer) *outBuffer = state.buffer;
    if (outLen) *outLen = state.len;
    if (outChecksum) *outChecksum = state.checksum;
    return true;
}

static bool coreRollbackLoadState(unsigned char* buffer, int len, unsigned int checksum, int frame) {
    m64p_rollback_state state = {};
    state.buffer = buffer;
    state.len = len;
    state.checksum = checksum;
    state.frame = frame;
    return CoreDoCommand(M64CMD_ROLLBACK_LOAD_STATE, 0, &state) == M64ERR_SUCCESS;
}

static void coreRollbackFreeState(void* buffer) {
    if (buffer) {
        CoreDoCommand(M64CMD_ROLLBACK_FREE_STATE, 0, buffer);
    }
}

static bool coreRollbackSampleInput(void* values, int size, int players) {
    m64p_rollback_input_sample sample = {};
    sample.values = values;
    sample.size = size;
    sample.players = players;
    return CoreDoCommand(M64CMD_ROLLBACK_SAMPLE_INPUT, 0, &sample) == M64ERR_SUCCESS;
}

static bool coreRollbackSetInputCallback(void* callback) {
    return CoreDoCommand(M64CMD_ROLLBACK_SET_INPUT_CALLBACK, 0, callback) == M64ERR_SUCCESS;
}

static bool coreRollbackSetInputPlayers(int players) {
    return CoreDoCommand(M64CMD_ROLLBACK_SET_INPUT_PLAYERS, players, nullptr) == M64ERR_SUCCESS;
}

static bool coreRollbackSetDeterministic(bool enabled) {
    return CoreDoCommand(M64CMD_ROLLBACK_SET_DETERMINISTIC, enabled ? 1 : 0, nullptr) == M64ERR_SUCCESS;
}

static bool coreRollbackSetTimesyncScale(double scale) {
    return CoreDoCommand(M64CMD_ROLLBACK_SET_TIMESYNC_SCALE, 0, &scale) == M64ERR_SUCCESS;
}

static bool coreRollbackRunFrame(int flags) {
    return CoreDoCommand(M64CMD_ROLLBACK_RUN_FRAME, flags, nullptr) == M64ERR_SUCCESS;
}

static bool coreRollbackExecute(m64p_rollback_execute_callbacks* callbacks) {
    return CoreDoCommand(M64CMD_ROLLBACK_EXECUTE, 0, callbacks) == M64ERR_SUCCESS;
}

// ---------------------------------------------------------------------------
// Frame output flags from m64p_types.h
static constexpr int M64FRAME_OUTPUT_VIDEO  = 1 << 0;
static constexpr int M64FRAME_OUTPUT_AUDIO  = 1 << 1;
static constexpr int M64FRAME_OUTPUT_PACING = 1 << 2;
static constexpr int M64FRAME_OUTPUT_INPUT  = 1 << 3;

// Frame pacing (asymmetric, Slippi-style)
// ---------------------------------------------------------------------------
static void applyFramePacing() {
    if (!g_GekkoSession) return;
    float framesAhead = gekko_frames_ahead(g_GekkoSession);

    bool isSampleFrame = (g_TimesyncSampleCounter % kAsymIntervalFrames) == 0;
    if (isSampleFrame) {
        double deviation = 0.0;
        if (framesAhead < -kAsymBehindDeadzone) {
            double multiplier = std::min(-static_cast<double>(framesAhead) / kAsymSpeedUpWindow, 1.0);
            deviation = multiplier * kAsymMaxSpeedUp;
        } else if (framesAhead > kAsymAheadDeadzone) {
            double multiplier = std::min(static_cast<double>(framesAhead) / kAsymSlowDownWindow, 1.0);
            deviation = multiplier * -kAsymMaxSlowDown;
        }
        g_TimesyncTargetScale = 1.0 + deviation;
    }
    g_TimesyncSampleCounter++;

    g_SpeedScale += (g_TimesyncTargetScale - g_SpeedScale) * kAsymLerp;
    coreRollbackSetTimesyncScale(g_SpeedScale);
}

// ---------------------------------------------------------------------------
// GekkoNet input synchronization callback
// ---------------------------------------------------------------------------
static int rollbackInputCallback(void* values, int size, int players) {
    if (!g_GekkoSession || !g_GekkoHasLatchedInput) return 0;
    int expectedBytes = g_GekkoPlayers * g_GekkoInputSize;
    if (!values || size != g_GekkoInputSize || players < g_GekkoPlayers) return 0;
    if (static_cast<int>(g_GekkoLatchedInput.size()) < expectedBytes) return 0;

    std::memset(values, 0, static_cast<size_t>(size) * static_cast<size_t>(players));
    std::memcpy(values, g_GekkoLatchedInput.data(), static_cast<size_t>(expectedBytes));
    return 1;
}

// ---------------------------------------------------------------------------
// Submit local input
// ---------------------------------------------------------------------------
static bool submitLocalInput() {
    std::vector<uint32_t> physicalInputs(std::max(g_GekkoPlayers, 1), 0);
    if (!coreRollbackSampleInput(physicalInputs.data(), g_GekkoInputSize, 1)) {
        LOGE("Failed to sample input");
        return false;
    }

    bool submitted = false;
    for (int player = 1; player <= g_GekkoPlayers; player++) {
        size_t idx = static_cast<size_t>(player - 1);
        if (idx >= g_GekkoLocalHandles.size()) continue;
        int handle = g_GekkoLocalHandles[idx];
        if (handle < 0) continue;

        uint32_t input = physicalInputs[0];
        gekko_add_local_input(g_GekkoSession, handle, &input);
        submitted = true;
    }
    return submitted;
}

// ---------------------------------------------------------------------------
// Latch input from GekkoNet advance event
// ---------------------------------------------------------------------------
static bool latchGekkoInput(const GekkoGameEvent* event) {
    int actorBytes = g_GekkoActors * g_GekkoInputSize;
    int expectedBytes = g_GekkoPlayers * g_GekkoInputSize;
    if (!event->data.adv.inputs || static_cast<int>(event->data.adv.input_len) < actorBytes) {
        return false;
    }

    if (static_cast<int>(g_GekkoLatchedInput.size()) != expectedBytes) {
        g_GekkoLatchedInput.resize(static_cast<size_t>(expectedBytes));
    }
    std::memset(g_GekkoLatchedInput.data(), 0, static_cast<size_t>(expectedBytes));

    for (int player = 1; player <= g_GekkoPlayers; player++) {
        size_t idx = static_cast<size_t>(player - 1);
        if (idx >= g_GekkoPlayerHandles.size()) continue;
        int handle = g_GekkoPlayerHandles[idx];
        if (handle < 0) continue;
        if (handle >= g_GekkoActors) return false;

        std::memcpy(g_GekkoLatchedInput.data() + (idx * g_GekkoInputSize),
                    event->data.adv.inputs + (handle * g_GekkoInputSize),
                    static_cast<size_t>(g_GekkoInputSize));
    }
    g_GekkoHasLatchedInput = true;
    return true;
}

// ---------------------------------------------------------------------------
// Save/Load GekkoNet state
// ---------------------------------------------------------------------------
static bool saveGekkoState(int frame, unsigned int* checksum, unsigned int* stateLen, unsigned char* state) {
    if (!state || !stateLen) return false;
    if (frame < 0) {
        *stateLen = 0;
        if (checksum) *checksum = 0;
        return true;
    }

    unsigned char* outBuffer = nullptr;
    int outLen = 0;
    unsigned int outChecksum = 0;
    if (!coreRollbackSaveState(state, static_cast<int>(kStateCapacity), frame,
                                &outBuffer, &outLen, &outChecksum)) {
        return false;
    }

    if (outLen < 1 || static_cast<unsigned int>(outLen) > kStateCapacity) return false;
    if (outBuffer != state) return false;

    if (stateLen) *stateLen = static_cast<unsigned int>(outLen);
    if (checksum) *checksum = outChecksum;
    return true;
}

static bool loadGekkoState(const GekkoGameEvent* event) {
    return coreRollbackLoadState(event->data.load.state, event->data.load.state_len,
                                  0, event->data.load.frame);
}

// ---------------------------------------------------------------------------
// Rollback execute callbacks (called by core)
// ---------------------------------------------------------------------------
static int rollbackBeginFrame(void* userData) {
    (void)userData;
    if (!g_GekkoSession) return 0;
    if (g_GekkoStopRequested.load()) return 0;

    g_GekkoHasLatchedInput = false;

    gekko_network_poll(g_GekkoSession);
    // Process session events (desync, disconnect, etc.)
    {
        int eventCount = 0;
        GekkoSessionEvent** sessionEvents = gekko_session_events(g_GekkoSession, &eventCount);
        for (int i = 0; i < eventCount; i++) {
            GekkoSessionEvent* ev = sessionEvents[i];
            if (!ev) continue;
            switch (ev->type) {
            case GekkoDesyncDetected: {
                LOGE("DESYNC detected at frame %d (local=0x%x remote=0x%x)",
                     ev->data.desynced.frame,
                     ev->data.desynced.local_checksum,
                     ev->data.desynced.remote_checksum);
                g_GekkoLastDesyncFrame = ev->data.desynced.frame;
                // Notify Java via callback if set
                JNIEnv* env = getEnv();
                if (env && g_CallbackObj && g_OnSessionEventMethod) {
                    env->CallVoidMethod(g_CallbackObj, g_OnSessionEventMethod, 1, ev->data.desynced.frame);
                }
                break;
            }
            case GekkoPlayerDisconnected:
                LOGI("Player disconnected: handle=%d", ev->data.disconnected.handle);
                break;
            case GekkoPlayerConnected:
                LOGI("Player connected: handle=%d", ev->data.connected.handle);
                break;
            default:
                break;
            }
        }
    }
    applyFramePacing();

    if (!submitLocalInput()) return 0;

    // Event loop - process until we get a real advance
    for (;;) {
        if (g_GekkoStopRequested.load()) return 0;

        int count = 0;
        GekkoGameEvent** events = gekko_update_session(g_GekkoSession, &count);

        if (count == 0) {
            // Waiting for remote input
            std::this_thread::sleep_for(std::chrono::microseconds(100));
            continue;
        }

        bool hasRealAdvance = false;
        bool deferSaves = false;

        for (int i = 0; i < count; i++) {
            GekkoGameEvent* event = events[i];
            if (!event) continue;

            switch (event->type) {
            case GekkoSaveEvent: {
                // Save state into GekkoNet's provided buffer
                unsigned int* checksum = event->data.save.checksum;
                unsigned int* stateLen = event->data.save.state_len;
                unsigned char* state = event->data.save.state;

                if (!deferSaves) {
                    if (!saveGekkoState(event->data.save.frame, checksum, stateLen, state)) {
                        LOGE("Save state failed at frame %d", event->data.save.frame);
                        return 0;
                    }
                }
                break;
            }
            case GekkoLoadEvent:
                if (!loadGekkoState(event)) {
                    LOGE("Load state failed at frame %d", event->data.load.frame);
                    return 0;
                }
                break;
            case GekkoAdvanceEvent:
                if (!latchGekkoInput(event)) return 0;

                if (event->data.adv.rolling_back || event->data.adv.running_ahead) {
                    // Hidden frame (rollback or runahead) — no video/audio output
                    if (!coreRollbackRunFrame(0)) return 0;
                    g_GekkoHasLatchedInput = false;
                } else {
                    // Real visible frame — run with video+audio output
                    if (!coreRollbackRunFrame(M64FRAME_OUTPUT_VIDEO | M64FRAME_OUTPUT_AUDIO)) return 0;
                    hasRealAdvance = true;
                    deferSaves = true;
                }
                break;
            default:
                break;
            }
        }

        if (hasRealAdvance) return 1;
    }
}

static int rollbackEndFrame(void* userData) {
    (void)userData;
    g_GekkoHasLatchedInput = false;
    return 1;
}

// ---------------------------------------------------------------------------
// JNI Methods
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_JavaVM = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    g_JavaVM = nullptr;
}

// Core API is linked directly - no setup needed
JNIEXPORT jboolean JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeInit(
    JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    LOGI("Rollback native initialized (core linked directly)");
    return JNI_TRUE;
}

// Set callback object for events
JNIEXPORT void JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeSetCallback(
    JNIEnv* env, jclass clazz, jobject callback) {
    (void)clazz;
    if (g_CallbackObj) {
        env->DeleteGlobalRef(g_CallbackObj);
        g_CallbackObj = nullptr;
    }
    if (callback) {
        g_CallbackObj = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_OnSessionEventMethod = env->GetMethodID(cls, "onSessionEvent", "(II)V");
        g_OnLogMethod = env->GetMethodID(cls, "onLog", "(Ljava/lang/String;)V");
    }
}

// Start a P2P rollback session (direct connection)
JNIEXPORT jboolean JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeStartP2PSession(
    JNIEnv* env, jclass clazz,
    jstring gameName, jint players, jint inputSize,
    jint localPlayer, jint localPort,
    jstring remoteIp, jint remotePort,
    jint localDelay, jint predictionWindow) {
    (void)clazz;

    if (g_GekkoSession) {
        LOGE("Session already active");
        return JNI_FALSE;
    }

    const char* game = env->GetStringUTFChars(gameName, nullptr);
    const char* remote = env->GetStringUTFChars(remoteIp, nullptr);

    LOGI("Starting P2P session: game=%s players=%d local=%d remote=%s:%d",
         game, players, localPlayer, remote, remotePort);

    g_GekkoLocalPlayer = localPlayer;
    g_GekkoStopRequested.store(false);

    if (!gekko_create(&g_GekkoSession, GekkoGameSession)) {
        LOGE("Failed to create GekkoNet session");
        env->ReleaseStringUTFChars(gameName, game);
        env->ReleaseStringUTFChars(remoteIp, remote);
        return JNI_FALSE;
    }

    GekkoConfig config = {};
    config.num_players = static_cast<unsigned char>(players);
    config.max_spectators = 0;
    config.input_prediction_window = static_cast<unsigned char>(std::clamp(predictionWindow, 1, 10));
    config.input_size = static_cast<unsigned int>(inputSize);
    config.state_size = kStateCapacity;
    config.limited_saving = false;
    config.desync_detection = true;
    config.check_distance = 10;
    gekko_start(g_GekkoSession, &config);

    // Use POSIX UDP adapter (Android-compatible, no ASIO)
    if (!posix_udp_init(static_cast<unsigned short>(localPort))) {
        LOGE("Failed to create UDP adapter");
        gekko_destroy(&g_GekkoSession);
        g_GekkoSession = nullptr;
        env->ReleaseStringUTFChars(gameName, game);
        env->ReleaseStringUTFChars(remoteIp, remote);
        return JNI_FALSE;
    }
    gekko_net_adapter_set(g_GekkoSession, &g_PosixUdpAdapter);

    gekko_set_runahead(g_GekkoSession, 0);

    g_GekkoPlayers = players;
    g_GekkoActors = players;
    g_GekkoInputSize = inputSize;
    g_GekkoLocalHandle = -1;
    g_GekkoRemoteHandle = -1;
    g_GekkoPlayerHandles.assign(players, -1);
    g_GekkoLocalHandles.assign(players, -1);
    g_GekkoLatchedInput.assign(players * inputSize, 0);
    g_GekkoHasLatchedInput = false;

    std::string remoteAddr = std::string(remote) + ":" + std::to_string(remotePort);

    for (int player = 1; player <= players; player++) {
        if (player == localPlayer) {
            int handle = gekko_add_actor(g_GekkoSession, GekkoLocalPlayer, nullptr);
            if (handle < 0) {
                LOGE("Failed to add local actor");
                gekko_destroy(&g_GekkoSession);
                g_GekkoSession = nullptr;
                env->ReleaseStringUTFChars(gameName, game);
                env->ReleaseStringUTFChars(remoteIp, remote);
                return JNI_FALSE;
            }
            g_GekkoLocalHandle = handle;
            g_GekkoPlayerHandles[player - 1] = handle;
            g_GekkoLocalHandles[player - 1] = handle;
            gekko_set_local_delay(g_GekkoSession, handle, static_cast<unsigned char>(std::clamp(localDelay, 0, 10)));
            LOGI("Local player %d -> handle %d", player, handle);
        } else {
            GekkoNetAddress addr = {};
            addr.data = remoteAddr.data();
            addr.size = static_cast<unsigned int>(remoteAddr.size());
            int handle = gekko_add_actor(g_GekkoSession, GekkoRemotePlayer, &addr);
            if (handle < 0) {
                LOGE("Failed to add remote actor");
                gekko_destroy(&g_GekkoSession);
                g_GekkoSession = nullptr;
                env->ReleaseStringUTFChars(gameName, game);
                env->ReleaseStringUTFChars(remoteIp, remote);
                return JNI_FALSE;
            }
            if (g_GekkoRemoteHandle < 0) g_GekkoRemoteHandle = handle;
            g_GekkoPlayerHandles[player - 1] = handle;
            LOGI("Remote player %d -> handle %d @ %s", player, handle, remoteAddr.c_str());
        }
    }

    // Install input callback
    if (!coreRollbackSetInputPlayers(g_GekkoPlayers) ||
        !coreRollbackSetInputCallback(reinterpret_cast<void*>(rollbackInputCallback))) {
        LOGE("Failed to install input callback");
        gekko_destroy(&g_GekkoSession);
        g_GekkoSession = nullptr;
        env->ReleaseStringUTFChars(gameName, game);
        env->ReleaseStringUTFChars(remoteIp, remote);
        return JNI_FALSE;
    }

    // Reset pacing
    g_SpeedScale = 1.0;
    g_TimesyncTargetScale = 1.0;
    g_TimesyncSampleCounter = 0;

    env->ReleaseStringUTFChars(gameName, game);
    env->ReleaseStringUTFChars(remoteIp, remote);

    LOGI("P2P session started successfully");
    return JNI_TRUE;
}

// Start a lobby session (multiple peers, used for RMG-K compatible matchmaking)
JNIEXPORT jboolean JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeStartLobbySession(
    JNIEnv* env, jclass clazz,
    jstring gameName, jint players, jint inputSize,
    jint localPlayer, jint localPort,
    jintArray remoteSlots, jobjectArray remoteIps, jintArray remotePorts,
    jint localDelay, jint predictionWindow) {
    (void)clazz;

    if (g_GekkoSession) {
        LOGE("Session already active");
        return JNI_FALSE;
    }

    const char* game = env->GetStringUTFChars(gameName, nullptr);
    int numRemotes = env->GetArrayLength(remoteSlots);

    LOGI("Starting lobby session: game=%s seats=%d remotes=%d local=%d",
         game, players, numRemotes, localPlayer);

    jint* slots = env->GetIntArrayElements(remoteSlots, nullptr);
    jint* ports = env->GetIntArrayElements(remotePorts, nullptr);

    g_GekkoLocalPlayer = localPlayer;
    g_GekkoStopRequested.store(false);

    if (!gekko_create(&g_GekkoSession, GekkoGameSession)) {
        LOGE("Failed to create GekkoNet session");
        env->ReleaseStringUTFChars(gameName, game);
        env->ReleaseIntArrayElements(remoteSlots, slots, 0);
        env->ReleaseIntArrayElements(remotePorts, ports, 0);
        return JNI_FALSE;
    }

    GekkoConfig config = {};
    config.num_players = static_cast<unsigned char>(players);
    config.max_spectators = 0;
    config.input_prediction_window = static_cast<unsigned char>(std::clamp(predictionWindow, 1, 10));
    config.input_size = static_cast<unsigned int>(inputSize);
    config.state_size = kStateCapacity;
    config.limited_saving = false;
    config.desync_detection = true;
    config.check_distance = 10;
    gekko_start(g_GekkoSession, &config);

    if (!posix_udp_init(static_cast<unsigned short>(localPort))) {
        LOGE("Failed to create UDP adapter");
        gekko_destroy(&g_GekkoSession);
        g_GekkoSession = nullptr;
        env->ReleaseStringUTFChars(gameName, game);
        env->ReleaseIntArrayElements(remoteSlots, slots, 0);
        env->ReleaseIntArrayElements(remotePorts, ports, 0);
        return JNI_FALSE;
    }
    gekko_net_adapter_set(g_GekkoSession, &g_PosixUdpAdapter);

    gekko_set_runahead(g_GekkoSession, 0);

    g_GekkoPlayers = players;
    g_GekkoActors = numRemotes + 1;
    g_GekkoInputSize = inputSize;
    g_GekkoLocalHandle = -1;
    g_GekkoRemoteHandle = -1;
    g_GekkoPlayerHandles.assign(players, -1);
    g_GekkoLocalHandles.assign(players, -1);
    g_GekkoLatchedInput.assign(players * inputSize, 0);
    g_GekkoHasLatchedInput = false;

    // Pre-build remote address strings
    std::vector<std::string> remoteAddrStrings(numRemotes);
    for (int i = 0; i < numRemotes; i++) {
        jstring ipStr = (jstring)env->GetObjectArrayElement(remoteIps, i);
        const char* ip = env->GetStringUTFChars(ipStr, nullptr);
        remoteAddrStrings[i] = std::string(ip) + ":" + std::to_string(ports[i]);
        env->ReleaseStringUTFChars(ipStr, ip);
    }

    // Add actors for each seat
    for (int player = 1; player <= players; player++) {
        if (player == localPlayer) {
            int handle = gekko_add_actor(g_GekkoSession, GekkoLocalPlayer, nullptr);
            if (handle < 0) {
                LOGE("Failed to add local actor");
                goto cleanup;
            }
            g_GekkoLocalHandle = handle;
            g_GekkoPlayerHandles[player - 1] = handle;
            g_GekkoLocalHandles[player - 1] = handle;
            gekko_set_local_delay(g_GekkoSession, handle, static_cast<unsigned char>(std::clamp(localDelay, 0, 10)));
            LOGI("Local player %d -> handle %d", player, handle);
        } else {
            // Find remote for this slot
            int remoteIdx = -1;
            for (int i = 0; i < numRemotes; i++) {
                if (slots[i] == player) { remoteIdx = i; break; }
            }
            if (remoteIdx < 0) {
                LOGI("Skipping empty seat %d", player);
                continue;
            }

            std::string& addr = remoteAddrStrings[remoteIdx];
            GekkoNetAddress gekkoAddr = {};
            gekkoAddr.data = addr.data();
            gekkoAddr.size = static_cast<unsigned int>(addr.size());
            int handle = gekko_add_actor(g_GekkoSession, GekkoRemotePlayer, &gekkoAddr);
            if (handle < 0) {
                LOGE("Failed to add remote actor for slot %d", player);
                goto cleanup;
            }
            if (g_GekkoRemoteHandle < 0) g_GekkoRemoteHandle = handle;
            g_GekkoPlayerHandles[player - 1] = handle;
            LOGI("Remote player %d -> handle %d @ %s", player, handle, addr.c_str());
        }
    }

    if (g_GekkoLocalHandle < 0) {
        LOGE("No local handle");
        goto cleanup;
    }

    if (!coreRollbackSetInputPlayers(g_GekkoPlayers) ||
        !coreRollbackSetInputCallback(reinterpret_cast<void*>(rollbackInputCallback))) {
        LOGE("Failed to install input callback");
        goto cleanup;
    }

    g_SpeedScale = 1.0;
    g_TimesyncTargetScale = 1.0;
    g_TimesyncSampleCounter = 0;

    env->ReleaseStringUTFChars(gameName, game);
    env->ReleaseIntArrayElements(remoteSlots, slots, 0);
    env->ReleaseIntArrayElements(remotePorts, ports, 0);

    LOGI("Lobby session started successfully");
    return JNI_TRUE;

cleanup:
    gekko_destroy(&g_GekkoSession);
    g_GekkoSession = nullptr;
    env->ReleaseStringUTFChars(gameName, game);
    env->ReleaseIntArrayElements(remoteSlots, slots, 0);
    env->ReleaseIntArrayElements(remotePorts, ports, 0);
    return JNI_FALSE;
}

// Execute the rollback loop (blocks until session ends)
JNIEXPORT jboolean JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeExecute(
    JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;

    if (!g_GekkoSession) {
        LOGE("No active session");
        return JNI_FALSE;
    }

    m64p_rollback_execute_callbacks callbacks = {};
    callbacks.begin_frame = rollbackBeginFrame;
    callbacks.end_frame = rollbackEndFrame;
    callbacks.pace_before_present = nullptr;
    callbacks.pacing_trace_enabled = 0;

    g_GekkoExecuting.store(true);
    LOGI("Starting rollback execution loop");

    bool result = coreRollbackExecute(&callbacks);

    g_GekkoExecuting.store(false);
    LOGI("Rollback execution loop ended: %s", result ? "success" : "failure");

    return result ? JNI_TRUE : JNI_FALSE;
}

// Close the current session
JNIEXPORT void JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeCloseSession(
    JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;

    g_GekkoStopRequested.store(false);
    coreRollbackSetInputCallback(nullptr);

    if (g_GekkoSession) {
        gekko_destroy(&g_GekkoSession);
    }
    posix_udp_destroy();

    g_GekkoSession = nullptr;
    g_GekkoPlayers = 0;
    g_GekkoActors = 0;
    g_GekkoInputSize = 4;
    g_GekkoLocalPlayer = 0;
    g_GekkoLocalHandle = -1;
    g_GekkoRemoteHandle = -1;
    g_GekkoPlayerHandles.clear();
    g_GekkoLocalHandles.clear();
    g_GekkoLatchedInput.clear();
    g_GekkoHasLatchedInput = false;
    g_GekkoExecuting.store(false);
    g_SpeedScale = 1.0;
    g_TimesyncTargetScale = 1.0;
    g_TimesyncSampleCounter = 0;

    LOGI("Session closed");
}

// Request stop
JNIEXPORT void JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeRequestStop(
    JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    g_GekkoStopRequested.store(true);
}

// Check if session is active
JNIEXPORT jboolean JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeIsSessionActive(
    JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return g_GekkoSession != nullptr ? JNI_TRUE : JNI_FALSE;
}

// Check if executing
JNIEXPORT jboolean JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeIsExecuting(
    JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return g_GekkoExecuting.load() ? JNI_TRUE : JNI_FALSE;
}

// Get network stats
JNIEXPORT jfloatArray JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeGetNetworkStats(
    JNIEnv* env, jclass clazz, jint player) {
    (void)clazz;

    jfloatArray result = env->NewFloatArray(5);
    if (!g_GekkoSession || player < 1 || player > g_GekkoPlayers) {
        float zeros[5] = {0, 0, 0, 0, 0};
        env->SetFloatArrayRegion(result, 0, 5, zeros);
        return result;
    }

    int handle = g_GekkoPlayerHandles[player - 1];
    if (handle < 0) {
        float zeros[5] = {0, 0, 0, 0, 0};
        env->SetFloatArrayRegion(result, 0, 5, zeros);
        return result;
    }

    GekkoNetworkStats stats = {};
    gekko_network_stats(g_GekkoSession, handle, &stats);

    float data[5] = {
        stats.kb_sent,
        stats.kb_received,
        static_cast<float>(stats.last_ping),
        stats.avg_ping,
        stats.jitter
    };
    env->SetFloatArrayRegion(result, 0, 5, data);
    return result;
}

// Get frames ahead
JNIEXPORT jfloat JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeGetFramesAhead(
    JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (!g_GekkoSession) return 0.0f;
    return gekko_frames_ahead(g_GekkoSession);
}

// Disconnect a player
JNIEXPORT void JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeDisconnectPlayer(
    JNIEnv* env, jclass clazz, jint handle) {
    (void)env;
    (void)clazz;
    if (g_GekkoSession && handle >= 0) {
        gekko_disconnect_player(g_GekkoSession, handle);
    }
}

// Get the frame number of the last detected desync (-1 if none)
JNIEXPORT jint JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeGetLastDesyncFrame(
    JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return g_GekkoLastDesyncFrame;
}

} // extern "C"
