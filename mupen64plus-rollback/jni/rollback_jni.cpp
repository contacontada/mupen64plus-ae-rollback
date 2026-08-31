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
#include <cstdio>
#include <ctime>
#include <cstdarg>

#include <gekkonet.h>

#define LOG_TAG "RollbackJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// POSIX UDP adapter for Android (replaces GekkoNet's ASIO-based default adapter)
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>

static int g_UdpSocket = -1;
static std::atomic<int> g_DiagUdpSendCalls{0};
static std::atomic<int> g_DiagUdpSendErrors{0};
static std::atomic<long long> g_DiagUdpBytesSent{0};
static std::atomic<int> g_DiagUdpRecvCalls{0};
static std::atomic<long long> g_DiagUdpBytesRecv{0};
static std::string g_LastNativeError;

// --- Native debug log (writes into the same rollback_debug.log the user
// already knows how to retrieve, since adb/logcat isn't always available) --
static char g_DebugLogPath[512] = {0};
static std::mutex g_DebugLogMutex;

static void nativeDebugLog(const char* tag, const char* message) {
    LOGI("%s: %s", tag, message); // still goes to logcat too, in case adb IS available
    if (g_DebugLogPath[0] == '\0') return;

    std::lock_guard<std::mutex> lock(g_DebugLogMutex);
    FILE* f = fopen(g_DebugLogPath, "a");
    if (!f) return;

    time_t now = time(nullptr);
    struct tm tmNow{};
    localtime_r(&now, &tmNow);
    struct timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    int millis = (int)(ts.tv_nsec / 1000000);

    fprintf(f, "%02d:%02d:%02d.%03d [%d] %s: %s\n",
        tmNow.tm_hour, tmNow.tm_min, tmNow.tm_sec, millis,
        getpid(), tag, message);
    fclose(f);
}

// printf-style convenience wrapper around nativeDebugLog() above.
static void nativeDebugLogf(const char* tag, const char* fmt, ...) {
    char buf[256];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    nativeDebugLog(tag, buf);
}

extern "C"
JNIEXPORT void JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeSetDebugLogPath(
    JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath != nullptr) {
        std::lock_guard<std::mutex> lock(g_DebugLogMutex);
        strncpy(g_DebugLogPath, cpath, sizeof(g_DebugLogPath) - 1);
        g_DebugLogPath[sizeof(g_DebugLogPath) - 1] = '\0';
        env->ReleaseStringUTFChars(path, cpath);
    }
}

// --- Native crash marker -----------------------------------------------
//
// RollbackCrashLogger.java catches uncaught *Java* exceptions and writes
// them to a file the user can read without adb. It cannot see native
// (C/C++) crashes at all - a SIGSEGV/SIGABRT here takes down the whole
// process instantly, before any Java code (including that handler) ever
// runs, and before RollbackNetplayService.onDestroy() can log anything.
// From the outside this looks exactly like "the match froze": input stops,
// nothing renders, and the next log entry is a brand new process/PID with
// no shutdown log in between - because there was no shutdown, just a kill.
//
// This installs a signal handler that writes a minimal marker (signal
// number/name, faulting address, timestamp) to a plain text file before
// the process goes down, then re-raises the signal so the OS's normal
// tombstone generation still happens unchanged. Deliberately uses only
// low-level POSIX calls (open/write/close), not fopen/malloc/std::string
// formatting - the C++ heap and libc streams can themselves be in a bad
// state during a crash, and this only needs to survive long enough to
// write a couple hundred bytes.
static char g_CrashLogPath[512] = {0};

static const char* signalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        default:      return "UNKNOWN";
    }
}

// Minimal itoa for use inside the signal handler (no malloc, no locale).
static char* writeUInt(char* buf, unsigned long v) {
    char tmp[20];
    int n = 0;
    if (v == 0) tmp[n++] = '0';
    while (v > 0) { tmp[n++] = '0' + (v % 10); v /= 10; }
    while (n > 0) *buf++ = tmp[--n];
    return buf;
}

static void nativeCrashHandler(int sig, siginfo_t* info, void* /*ucontext*/) {
    if (g_CrashLogPath[0] != '\0') {
        char msg[512];
        char* p = msg;
        const char* prefix = "Rollback Netplay native crash\nSignal: ";
        for (const char* c = prefix; *c; ++c) *p++ = *c;
        for (const char* c = signalName(sig); *c; ++c) *p++ = *c;
        const char* signumLabel = " (";
        for (const char* c = signumLabel; *c; ++c) *p++ = *c;
        p = writeUInt(p, (unsigned long) sig);
        *p++ = ')'; *p++ = '\n';
        if (info != nullptr) {
            const char* addrLabel = "Faulting address: 0x";
            for (const char* c = addrLabel; *c; ++c) *p++ = *c;
            char hex[17];
            unsigned long addr = (unsigned long) info->si_addr;
            const char* digits = "0123456789abcdef";
            int hn = 0;
            if (addr == 0) hex[hn++] = '0';
            while (addr > 0) { hex[hn++] = digits[addr % 16]; addr /= 16; }
            while (hn > 0) *p++ = hex[--hn];
            *p++ = '\n';
        }
        int fd = open(g_CrashLogPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) {
            write(fd, msg, (size_t)(p - msg));
            close(fd);
        }
    }

    // Restore the default handler and re-raise so the OS still produces
    // its normal tombstone/crash-reporting exactly as if this handler
    // were never installed - this only adds a marker, it never suppresses
    // the crash.
    signal(sig, SIG_DFL);
    raise(sig);
}

extern "C"
JNIEXPORT void JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeSetCrashLogPath(
    JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath != nullptr) {
        strncpy(g_CrashLogPath, cpath, sizeof(g_CrashLogPath) - 1);
        g_CrashLogPath[sizeof(g_CrashLogPath) - 1] = '\0';
        env->ReleaseStringUTFChars(path, cpath);

        struct sigaction sa{};
        sa.sa_sigaction = nativeCrashHandler;
        sa.sa_flags = SA_SIGINFO;
        sigemptyset(&sa.sa_mask);
        sigaction(SIGSEGV, &sa, nullptr);
        sigaction(SIGABRT, &sa, nullptr);
        sigaction(SIGBUS, &sa, nullptr);
        sigaction(SIGFPE, &sa, nullptr);
        sigaction(SIGILL, &sa, nullptr);
        LOGI("Native crash handler installed, writing to: %s", g_CrashLogPath);
    }
}
// -------------------------------------------------------------------------


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

    ssize_t sent = sendto(g_UdpSocket, data, static_cast<size_t>(length), 0,
           reinterpret_cast<struct sockaddr*>(&dest), sizeof(dest));
    g_DiagUdpSendCalls.fetch_add(1);
    if (sent < 0) {
        g_DiagUdpSendErrors.fetch_add(1);
        int n = g_DiagUdpSendErrors.load();
        if (n <= 10) {
            nativeDebugLogf("RollbackInputDiag", "posix_udp_send: sendto FAILED errno=%d (%s) dest=%s",
                errno, strerror(errno), addrStr.c_str());
        }
    } else {
        g_DiagUdpBytesSent.fetch_add((long long)sent);
    }
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
        g_DiagUdpRecvCalls.fetch_add(1);
        g_DiagUdpBytesRecv.fetch_add((long long)recvd);

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
#define M64P_CORE_PROTOTYPES
#include "api/m64p_types.h"
#include "api/m64p_frontend.h"
#include "api/m64p_common.h"

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
// g_GekkoLatchedInput/g_GekkoHasLatchedInput are written by latchGekkoInput()
// on the rollback/netplay thread (inside gekkoTick()) and read by
// rollbackInputCallback() on the SEPARATE emulation thread (see
// main_rollback_run_frame()'s own comment: it blocks the calling thread
// while a different thread actually executes the frame - that's where
// PIF's rollback_sync_input() ends up invoking this callback). A plain
// bool/vector shared across real OS threads with no synchronization has
// no visibility guarantee on ARM's weak memory model - the writing
// thread's update to g_GekkoHasLatchedInput was never guaranteed to
// become visible to the emulation thread at all, which is exactly why
// rollbackInputCallback() was failing on *every single call* ("FAIL no
// latched input") even though latchGekkoInput() was genuinely running
// and setting it - frames kept advancing and audio kept playing (the
// core doesn't need synced input for that), but the game only ever saw
// zeroed controller state. This mutex is the fix: it gives both sides an
// actual happens-before relationship, independent of whatever mechanism
// (busy-wait or otherwise) triggers the frame on the other thread.
static std::mutex g_GekkoLatchedInputMutex;
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
static std::atomic<int> g_DiagCallbackCalls{0};
static std::atomic<int> g_DiagCallbackFailNoSession{0};
static std::atomic<int> g_DiagCallbackFailNoLatch{0};
static std::atomic<int> g_DiagCallbackSuccess{0};
static std::atomic<int> g_DiagCallbackSuccessNonzero{0};
static std::atomic<int> g_DiagAdvanceEventCount{0};
static std::atomic<int> g_DiagHiddenFrameCount{0};
static std::atomic<int> g_DiagVisibleFrameCount{0};
static std::atomic<int> g_DiagRealAdvanceCount{0};
static std::atomic<int> g_DiagSampleNonzeroCount{0};

// Periodic (time-throttled, not call-count-limited) summary of the
// counters above, so a long test session's LATER behavior is still
// visible even once the earlier <200-call-capped per-event logs have
// all stopped firing. Safe to call from any thread that already calls
// nativeDebugLog reasonably often (rollbackInputCallback fires at
// roughly the game's frame rate for the whole session).
static void maybeLogDiagSummary() {
    static std::atomic<long long> s_LastSummaryMs{0};
    auto now = std::chrono::steady_clock::now();
    long long nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()).count();
    long long last = s_LastSummaryMs.load();
    if (nowMs - last < 3000) return;
    if (!s_LastSummaryMs.compare_exchange_strong(last, nowMs)) return; // another thread won the race

    nativeDebugLogf("RollbackInputDiag",
        "SUMMARY (every 3s): callback calls=%d success=%d (nonzero=%d) fail_no_session=%d fail_no_latch=%d | "
        "advance_events=%d hidden_frames=%d visible_frames=%d real_advances=%d sampled_nonzero=%d | "
        "udp: send_calls=%d send_errors=%d bytes_sent=%lld recv_calls=%d bytes_recv=%lld",
        g_DiagCallbackCalls.load(), g_DiagCallbackSuccess.load(), g_DiagCallbackSuccessNonzero.load(),
        g_DiagCallbackFailNoSession.load(), g_DiagCallbackFailNoLatch.load(),
        g_DiagAdvanceEventCount.load(), g_DiagHiddenFrameCount.load(), g_DiagVisibleFrameCount.load(),
        g_DiagRealAdvanceCount.load(), g_DiagSampleNonzeroCount.load(),
        g_DiagUdpSendCalls.load(), g_DiagUdpSendErrors.load(), g_DiagUdpBytesSent.load(),
        g_DiagUdpRecvCalls.load(), g_DiagUdpBytesRecv.load());
}

static int rollbackInputCallback(void* values, int size, int players) {
    int calls = g_DiagCallbackCalls.fetch_add(1);
    maybeLogDiagSummary();
    if (!g_GekkoSession) {
        g_DiagCallbackFailNoSession++;
        if (calls < 200) nativeDebugLogf("RollbackInputDiag", "rollbackInputCallback: FAIL no session (call #%d)", calls);
        return 0;
    }

    std::lock_guard<std::mutex> lock(g_GekkoLatchedInputMutex);

    if (!g_GekkoHasLatchedInput) {
        g_DiagCallbackFailNoLatch++;
        if (calls < 200) nativeDebugLogf("RollbackInputDiag", "rollbackInputCallback: FAIL no latched input (call #%d)", calls);
        return 0;
    }
    int expectedBytes = g_GekkoPlayers * g_GekkoInputSize;
    if (!values || size != g_GekkoInputSize || players < g_GekkoPlayers) {
        if (calls < 200) nativeDebugLogf("RollbackInputDiag", "rollbackInputCallback: FAIL bad args (size=%d expected=%d players=%d expectedPlayers=%d)",
            size, g_GekkoInputSize, players, g_GekkoPlayers);
        return 0;
    }
    if (static_cast<int>(g_GekkoLatchedInput.size()) < expectedBytes) {
        if (calls < 200) nativeDebugLogf("RollbackInputDiag", "rollbackInputCallback: FAIL latched buffer too small (%zu < %d)",
            g_GekkoLatchedInput.size(), expectedBytes);
        return 0;
    }

    std::memset(values, 0, static_cast<size_t>(size) * static_cast<size_t>(players));
    std::memcpy(values, g_GekkoLatchedInput.data(), static_cast<size_t>(expectedBytes));

    int succ = g_DiagCallbackSuccess.fetch_add(1);
    if (succ < 200) {
        uint32_t p0 = 0;
        std::memcpy(&p0, g_GekkoLatchedInput.data(), sizeof(uint32_t));
        if (p0 != 0) {
            g_DiagCallbackSuccessNonzero.fetch_add(1);
            nativeDebugLogf("RollbackInputDiag", "rollbackInputCallback: SUCCESS, player0 raw value=0x%08x (call #%d)", p0, calls);
        }
    } else {
        uint32_t p0 = 0;
        std::memcpy(&p0, g_GekkoLatchedInput.data(), sizeof(uint32_t));
        if (p0 != 0) g_DiagCallbackSuccessNonzero.fetch_add(1);
    }
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

    if (physicalInputs[0] != 0) {
        int n = g_DiagSampleNonzeroCount.fetch_add(1);
        if (n < 200) {
            nativeDebugLogf("RollbackInputDiag", "submitLocalInput: sampled nonzero physical input=0x%08x", physicalInputs[0]);
        }
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

    std::lock_guard<std::mutex> lock(g_GekkoLatchedInputMutex);

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
    // Note: the native core always allocates a fresh buffer here (it does
    // NOT write in-place into `state`) - the caller (us) is responsible
    // for copying the data out and freeing the core's allocation via
    // M64CMD_ROLLBACK_FREE_STATE, same as everywhere else this pattern is
    // used in this file.
    if (!coreRollbackSaveState(state, static_cast<int>(kStateCapacity), frame,
                                &outBuffer, &outLen, &outChecksum)) {
        return false;
    }

    if (!outBuffer || outLen < 1 || static_cast<unsigned int>(outLen) > kStateCapacity) {
        if (outBuffer) CoreDoCommand(M64CMD_ROLLBACK_FREE_STATE, 0, outBuffer);
        return false;
    }

    memcpy(state, outBuffer, static_cast<size_t>(outLen));
    CoreDoCommand(M64CMD_ROLLBACK_FREE_STATE, 0, outBuffer);

    *stateLen = static_cast<unsigned int>(outLen);
    if (checksum) *checksum = outChecksum;
    return true;
}

static bool loadGekkoState(const GekkoGameEvent* event) {
    return coreRollbackLoadState(event->data.load.state, event->data.load.state_len,
                                  0, event->data.load.frame);
}

// ---------------------------------------------------------------------------
// GekkoNet tick: polls the network, processes session/game events, and
// advances exactly as many frames as GekkoNet currently has ready (hidden
// rollback/runahead frames with no output, then one real visible frame).
//
// IMPORTANT: this must be driven from an OUTER loop (nativeExecute(), see
// below) - it must NEVER be called from inside the begin_frame/end_frame
// callbacks that the core invokes around a single M64CMD_ROLLBACK_RUN_FRAME
// step. This function itself calls coreRollbackRunFrame(), which triggers
// the core to call begin_frame() - if THIS function were also registered
// as begin_frame, that call would recurse into itself on every single game
// frame, nesting one more native stack frame deeper with no way to ever
// unwind until the whole session ends, guaranteeing a stack overflow crash
// within seconds of real gameplay. (This was exactly that bug - previously
// this logic lived directly inside rollbackBeginFrame.)
// ---------------------------------------------------------------------------
static int gekkoTick() {
    maybeLogDiagSummary();
    if (!g_GekkoSession) {
        g_LastNativeError = "gekkoTick: g_GekkoSession is null (session was destroyed or never created)";
        return 0;
    }
    if (g_GekkoStopRequested.load()) {
        g_LastNativeError = "gekkoTick: g_GekkoStopRequested was true at tick start (someone called nativeRequestStop())";
        return 0;
    }

    {
        std::lock_guard<std::mutex> lock(g_GekkoLatchedInputMutex);
        g_GekkoHasLatchedInput = false;
    }

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
                LOGE("Player disconnected: handle=%d", ev->data.disconnected.handle);
                // Previously this only logged and let the tick loop keep
                // running - with the remote gone, gekko_update_session()
                // then just returns count==0 forever, so the loop spins
                // waiting for input that will never arrive. That's what
                // produced the "ran for a while then returned false with
                // an empty reason" symptom: something *else* eventually
                // tore the session down (app backgrounded, socket error,
                // etc.) well after the disconnect, by which point there
                // was no useful error state left to report. Treat the
                // disconnect itself as fatal and return immediately (not
                // just flag-and-continue - the stop-requested check
                // further down would otherwise overwrite this specific
                // message with a generic one) so the real cause is what
                // gets surfaced to Java.
                g_LastNativeError = "Remote player disconnected (handle="
                    + std::to_string(ev->data.disconnected.handle) + ")";
                g_GekkoStopRequested.store(true);
                return 0;
            case GekkoPlayerConnected:
                LOGI("Player connected: handle=%d", ev->data.connected.handle);
                break;
            default:
                break;
            }
        }
    }
    applyFramePacing();

    if (!submitLocalInput()) {
        g_LastNativeError = "gekkoTick: submitLocalInput() failed";
        return 0;
    }

    // Event loop - process until we get a real advance
    for (;;) {
        if (g_GekkoStopRequested.load()) {
            g_LastNativeError = "gekkoTick: g_GekkoStopRequested became true inside the event loop";
            return 0;
        }

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
                {
                    static std::atomic<int> s_SaveEvtCount{0};
                    if (s_SaveEvtCount.fetch_add(1) < 10) {
                        nativeDebugLog("RollbackInputDiag", "gekkoTick: GekkoSaveEvent received");
                    }
                }
                // Save state into GekkoNet's provided buffer
                unsigned int* checksum = event->data.save.checksum;
                unsigned int* stateLen = event->data.save.state_len;
                unsigned char* state = event->data.save.state;

                if (!deferSaves) {
                    if (!saveGekkoState(event->data.save.frame, checksum, stateLen, state)) {
                        LOGE("Save state failed at frame %d", event->data.save.frame);
                        g_LastNativeError = "gekkoTick: saveGekkoState() failed at frame "
                            + std::to_string(event->data.save.frame);
                        return 0;
                    }
                }
                break;
            }
            case GekkoLoadEvent:
                if (!loadGekkoState(event)) {
                    LOGE("Load state failed at frame %d", event->data.load.frame);
                    g_LastNativeError = "gekkoTick: loadGekkoState() failed at frame "
                        + std::to_string(event->data.load.frame);
                    return 0;
                }
                break;
            case GekkoAdvanceEvent:
                {
                    int advCount = g_DiagAdvanceEventCount.fetch_add(1);
                    if (advCount < 30) {
                        nativeDebugLogf("RollbackInputDiag",
                            "gekkoTick: GekkoAdvanceEvent received (rolling_back=%d running_ahead=%d frame=%d)",
                            (int)event->data.adv.rolling_back, (int)event->data.adv.running_ahead, event->data.adv.frame);
                    }
                }
                if (!latchGekkoInput(event)) {
                    g_LastNativeError = "gekkoTick: latchGekkoInput() failed at frame "
                        + std::to_string(event->data.adv.frame);
                    return 0;
                }

                if (event->data.adv.rolling_back || event->data.adv.running_ahead) {
                    // Hidden frame (rollback or runahead) — no video/audio output
                    {
                        // Diagnostic: does the input callback actually fire
                        // DURING this specific synchronous call, while we
                        // know g_GekkoHasLatchedInput is true (we just set
                        // it above)? If the call counts don't move at all
                        // across this window, the callback is being invoked
                        // from somewhere else entirely, asynchronously -
                        // not from inside this frame's execution at all.
                        int before = g_DiagCallbackCalls.load();
                        if (!coreRollbackRunFrame(0)) {
                            g_LastNativeError = "gekkoTick: coreRollbackRunFrame(hidden) failed at frame "
                                + std::to_string(event->data.adv.frame);
                            return 0;
                        }
                        int after = g_DiagCallbackCalls.load();
                        int hiddenCount = g_DiagHiddenFrameCount.fetch_add(1);
                        if (hiddenCount < 30) {
                            nativeDebugLogf("RollbackInputDiag",
                                "hidden frame: callback calls before=%d after=%d (delta=%d) during coreRollbackRunFrame(0)",
                                before, after, after - before);
                        }
                    }
                    {
                        std::lock_guard<std::mutex> lock(g_GekkoLatchedInputMutex);
                        g_GekkoHasLatchedInput = false;
                    }
                } else {
                    // Real visible frame — run with video+audio output
                    int before = g_DiagCallbackCalls.load();
                    if (!coreRollbackRunFrame(M64FRAME_OUTPUT_VIDEO | M64FRAME_OUTPUT_AUDIO)) {
                        g_LastNativeError = "gekkoTick: coreRollbackRunFrame(visible) failed at frame "
                            + std::to_string(event->data.adv.frame);
                        return 0;
                    }
                    int after = g_DiagCallbackCalls.load();
                    int visibleCount = g_DiagVisibleFrameCount.fetch_add(1);
                    if (visibleCount < 30) {
                        nativeDebugLogf("RollbackInputDiag",
                            "visible frame: callback calls before=%d after=%d (delta=%d) during coreRollbackRunFrame(visible)",
                            before, after, after - before);
                    }
                    hasRealAdvance = true;
                    deferSaves = true;
                }
                break;
            default:
                break;
            }
        }

        if (hasRealAdvance) {
            int realAdvCount = g_DiagRealAdvanceCount.fetch_add(1);
            if (realAdvCount < 30) {
                nativeDebugLog("RollbackInputDiag", "gekkoTick: returning 1 (real advance happened this tick)");
            }
            return 1;
        }
    }
}

// ---------------------------------------------------------------------------
// Rollback execute callbacks (called by core around each single
// M64CMD_ROLLBACK_RUN_FRAME step - see main_rollback_run_frame() in
// main.c). Deliberately minimal and non-recursive: the real per-tick work
// lives in gekkoTick() above, driven from nativeExecute()'s own loop.
// ---------------------------------------------------------------------------
static int rollbackBeginFrame(void* userData) {
    (void)userData;
    return g_GekkoSession && !g_GekkoStopRequested.load() ? 1 : 0;
}

static int rollbackEndFrame(void* userData) {
    (void)userData;
    {
        std::lock_guard<std::mutex> lock(g_GekkoLatchedInputMutex);
        g_GekkoHasLatchedInput = false;
    }
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
        g_LastNativeError = "Session already active";
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
        g_LastNativeError = "gekko_create() failed";
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
        g_LastNativeError = "posix_udp_init() failed to bind local port " + std::to_string(localPort)
            + " (port may already be in use)";
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
    {
        std::lock_guard<std::mutex> lock(g_GekkoLatchedInputMutex);
        g_GekkoHasLatchedInput = false;
    }

    std::string remoteAddr = std::string(remote) + ":" + std::to_string(remotePort);

    for (int player = 1; player <= players; player++) {
        if (player == localPlayer) {
            int handle = gekko_add_actor(g_GekkoSession, GekkoLocalPlayer, nullptr);
            if (handle < 0) {
                LOGE("Failed to add local actor");
                g_LastNativeError = "gekko_add_actor(local) failed for player " + std::to_string(player);
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
                g_LastNativeError = "gekko_add_actor(remote) failed for player " + std::to_string(player)
                    + " @ " + remoteAddr;
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
    if (!coreRollbackSetDeterministic(true) ||
        !coreRollbackSetInputPlayers(g_GekkoPlayers) ||
        !coreRollbackSetInputCallback(reinterpret_cast<void*>(rollbackInputCallback))) {
        LOGE("Failed to install input callback");
        g_LastNativeError = "coreRollbackSetDeterministic/SetInputPlayers/SetInputCallback failed";
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
        g_LastNativeError = "Session already active";
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
        g_LastNativeError = "gekko_create() failed";
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
        g_LastNativeError = "posix_udp_init() failed to bind local port " + std::to_string(localPort)
            + " (port may already be in use)";
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
    {
        std::lock_guard<std::mutex> lock(g_GekkoLatchedInputMutex);
        g_GekkoHasLatchedInput = false;
    }

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
                g_LastNativeError = "gekko_add_actor(local) failed for player " + std::to_string(player);
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
                g_LastNativeError = "gekko_add_actor(remote) failed for slot " + std::to_string(player)
                    + " @ " + addr;
                goto cleanup;
            }
            if (g_GekkoRemoteHandle < 0) g_GekkoRemoteHandle = handle;
            g_GekkoPlayerHandles[player - 1] = handle;
            LOGI("Remote player %d -> handle %d @ %s", player, handle, addr.c_str());
        }
    }

    if (g_GekkoLocalHandle < 0) {
        LOGE("No local handle");
        g_LastNativeError = "No local handle assigned (localPlayer=" + std::to_string(localPlayer) + ")";
        goto cleanup;
    }

    if (!coreRollbackSetDeterministic(true) ||
        !coreRollbackSetInputPlayers(g_GekkoPlayers) ||
        !coreRollbackSetInputCallback(reinterpret_cast<void*>(rollbackInputCallback))) {
        LOGE("Failed to install input callback");
        g_LastNativeError = "coreRollbackSetDeterministic/SetInputPlayers/SetInputCallback failed";
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

// Returns a human-readable reason for the last nativeStartLobbySession()/
// nativeStartP2PSession() failure - since those only return a boolean,
// this is what lets the Java side (and RollbackDebugLog) show *why*
// something failed instead of just "returned false".
JNIEXPORT jstring JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeGetLastError(
    JNIEnv* env, jclass clazz) {
    (void)clazz;
    return env->NewStringUTF(g_LastNativeError.c_str());
}

// Execute the rollback loop (blocks until session ends)
JNIEXPORT jboolean JNICALL
Java_paulscode_mupen64plusae_rollback_RollbackNative_nativeExecute(
    JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;

    // Unconditional build marker - no logic gates this, it always fires
    // exactly once per nativeExecute() call. If this line is ever
    // missing from the log, the running .so predates this patch (a stale
    // build), full stop - nothing else in this diagnosis matters until
    // that's fixed first.
    nativeDebugLog("RollbackInputDiag", "BUILD MARKER: nativeExecute() ENTER (patch48)");

    if (!g_GekkoSession) {
        LOGE("No active session");
        g_LastNativeError = "nativeExecute() called with no active session (g_GekkoSession is null)";
        return JNI_FALSE;
    }

    m64p_rollback_execute_callbacks callbacks = {};
    callbacks.begin_frame = rollbackBeginFrame;
    callbacks.end_frame = rollbackEndFrame;
    callbacks.pace_before_present = nullptr;
    callbacks.pacing_trace_enabled = 0;

    // Registers the callbacks with the core (this call itself returns
    // immediately - see main_set_rollback_execute_callbacks in main.c).
    // The actual game loop is the gekkoTick() loop below, not something
    // coreRollbackExecute() does internally.
    if (!coreRollbackExecute(&callbacks)) {
        LOGE("Failed to register rollback execute callbacks");
        g_LastNativeError = "coreRollbackExecute() failed to register callbacks";
        return JNI_FALSE;
    }

    g_GekkoExecuting.store(true);
    LOGI("Starting rollback execution loop");

    bool result = true;
    while (!g_GekkoStopRequested.load()) {
        if (!gekkoTick()) {
            result = false;
            break;
        }
    }

    g_GekkoExecuting.store(false);
    LOGI("Rollback execution loop ended: %s", result ? "success" : "failure");

    // Safety net: every known failure path above sets g_LastNativeError,
    // but if this ever returns false with it still empty (a path we
    // haven't accounted for, or memory corruption), an empty reason is
    // strictly worse than a vague one - it makes the failure look like
    // it never happened. Never let that reach Java blank.
    if (!result && g_LastNativeError.empty()) {
        g_LastNativeError = "nativeExecute() failed but no specific reason was recorded "
            "(unhandled gekkoTick() failure path - please report this)";
    }

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
    {
        std::lock_guard<std::mutex> lock(g_GekkoLatchedInputMutex);
        g_GekkoHasLatchedInput = false;
    }
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
