#!/bin/bash
# Build script for Mupen64Plus AE with Rollback Netcode
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Mupen64Plus AE Rollback Netcode Build ==="
echo ""

# Verify prerequisites
ERRORS=0

if [ ! -d "mupen64plus-core/upstream/src/api" ]; then
    echo "ERROR: Modified mupen64plus-core not found"
    ERRORS=$((ERRORS+1))
fi

if [ ! -f "mupen64plus-core/upstream/src/api/m64p_types.h" ]; then
    echo "ERROR: m64p_types.h not found"
    ERRORS=$((ERRORS+1))
fi

if ! grep -q "M64CMD_ROLLBACK_SAVE_STATE" "mupen64plus-core/upstream/src/api/m64p_types.h" 2>/dev/null; then
    echo "ERROR: Rollback types not in m64p_types.h - core replacement failed"
    ERRORS=$((ERRORS+1))
fi

if [ ! -d "mupen64plus-rollback/jni/GekkoLib/GekkoLib/src" ]; then
    echo "ERROR: GekkoNet source not found"
    ERRORS=$((ERRORS+1))
fi

if [ ! -f "mupen64plus-rollback/jni/rollback_jni.cpp" ]; then
    echo "ERROR: JNI bridge not found"
    ERRORS=$((ERRORS+1))
fi

if [ ! -f "mupen64plus-rollback/src/main/java/paulscode/mupen64plusae/rollback/RmgkLobbyClient.java" ]; then
    echo "ERROR: Lobby client not found"
    ERRORS=$((ERRORS+1))
fi

if [ $ERRORS -gt 0 ]; then
    echo ""
    echo "$ERRORS errors found. Fix them before building."
    exit 1
fi

echo "All prerequisites verified."
echo ""

# Check for NDK
if [ -z "$ANDROID_NDK_HOME" ] && [ -z "$ANDROID_HOME" ]; then
    echo "Warning: ANDROID_NDK_HOME not set. Gradle will use local.properties."
fi

echo "Building rollback module..."
./gradlew :mupen64plus-rollback:assembleRelease 2>&1 | tail -30

echo ""
echo "=== Build complete ==="
echo ""
echo "To integrate with the main app:"
echo "  1. Launch: RollbackNetplayActivity.launch(context, romMd5, romName)"
echo "  2. Or via intent: paulscode.mupen64plusae.ROLLBACK_NETPLAY"
echo ""
echo "See ROLLBACK_NETCODE_README.md for details."
