#!/bin/bash
# Quick verification script for Manus AI
# Run this before attempting to build

echo "=== Mupen64Plus AE Rollback Netcode - Build Verification ==="
echo ""

ERRORS=0
WARNINGS=0

# Check project root
if [ ! -f "build.gradle" ]; then
    echo "ERROR: Not in project root. Run from mupen64plus-ae/"
    exit 1
fi

# Check rollback module
echo "1. Checking rollback module..."
if [ ! -d "mupen64plus-rollback" ]; then
    echo "   ERROR: mupen64plus-rollback directory missing"
    ERRORS=$((ERRORS+1))
else
    echo "   OK: mupen64plus-rollback exists"
fi

# Check JNI bridge
if [ ! -f "mupen64plus-rollback/jni/rollback_jni.cpp" ]; then
    echo "   ERROR: rollback_jni.cpp missing"
    ERRORS=$((ERRORS+1))
else
    echo "   OK: rollback_jni.cpp exists ($(wc -l < mupen64plus-rollback/jni/rollback_jni.cpp) lines)"
fi

# Check GekkoNet
if [ ! -d "mupen64plus-rollback/jni/GekkoLib/GekkoLib/src" ]; then
    echo "   ERROR: GekkoNet source missing"
    ERRORS=$((ERRORS+1))
else
    COUNT=$(find mupen64plus-rollback/jni/GekkoLib/GekkoLib/src -name "*.cpp" | wc -l)
    echo "   OK: GekkoNet source exists ($COUNT .cpp files)"
fi

# Check Java files
echo ""
echo "2. Checking Java files..."
JAVA_COUNT=$(find mupen64plus-rollback -name "*.java" | wc -l)
echo "   Found $JAVA_COUNT Java files"
for f in mupen64plus-rollback/src/main/java/paulscode/mupen64plusae/rollback/*.java; do
    if [ -f "$f" ]; then
        echo "   OK: $(basename $f)"
    fi
done

# Check JNI method match
echo ""
echo "3. Checking JNI methods..."
JAVA_NATIVE=$(grep -c "native " mupen64plus-rollback/src/main/java/paulscode/mupen64plusae/rollback/RollbackNative.java 2>/dev/null || echo 0)
CPP_NATIVE=$(grep -c "Java_paulscode" mupen64plus-rollback/jni/rollback_jni.cpp 2>/dev/null || echo 0)
if [ "$JAVA_NATIVE" = "$CPP_NATIVE" ]; then
    echo "   OK: $JAVA_NATIVE Java native methods = $CPP_NATIVE JNI exports"
else
    echo "   ERROR: Mismatch - $JAVA_NATIVE Java vs $CPP_NATIVE JNI"
    ERRORS=$((ERRORS+1))
fi

# Check core rollback support
echo ""
echo "4. Checking mupen64plus-core..."
if [ ! -f "mupen64plus-core/upstream/src/api/m64p_types.h" ]; then
    echo "   ERROR: m64p_types.h missing"
    ERRORS=$((ERRORS+1))
else
    ROLLBACK_COUNT=$(grep -c "M64CMD_ROLLBACK" mupen64plus-core/upstream/src/api/m64p_types.h)
    if [ "$ROLLBACK_COUNT" -gt 0 ]; then
        echo "   OK: Core has $ROLLBACK_COUNT rollback commands"
    else
        echo "   ERROR: Core has no rollback commands - replacement failed"
        ERRORS=$((ERRORS+1))
    fi
fi

# Check build config
echo ""
echo "5. Checking build configuration..."
if grep -q "mupen64plus-rollback" settings.gradle; then
    echo "   OK: settings.gradle includes rollback module"
else
    echo "   ERROR: settings.gradle missing rollback module"
    ERRORS=$((ERRORS+1))
fi

if grep -q "mupen64plus-rollback" app/build.gradle; then
    echo "   OK: app/build.gradle has rollback dependency"
else
    echo "   ERROR: app/build.gradle missing rollback dependency"
    ERRORS=$((ERRORS+1))
fi

# Check manifest
echo ""
echo "6. Checking manifests..."
if grep -q "RollbackNetplay" app/src/main/AndroidManifest.xml; then
    echo "   OK: app manifest has rollback activity"
else
    echo "   WARNING: app manifest missing rollback activity"
    WARNINGS=$((WARNINGS+1))
fi

# Check XML layouts
echo ""
echo "7. Checking XML layouts..."
LAYOUT_COUNT=$(find mupen64plus-rollback -name "*.xml" -path "*/res/*" | wc -l)
echo "   Found $LAYOUT_COUNT XML resource files"

# Summary
echo ""
echo "========================================="
if [ $ERRORS -eq 0 ]; then
    echo "ALL CHECKS PASSED"
    echo "Ready to build: ./gradlew :mupen64plus-rollback:assembleRelease"
else
    echo "$ERRORS ERRORS found - fix before building"
fi
if [ $WARNINGS -gt 0 ]; then
    echo "$WARNINGS warnings (non-critical)"
fi
echo "========================================="
